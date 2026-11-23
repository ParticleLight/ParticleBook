package app.particlebook.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import app.particlebook.android.zlib.ZMirrorItem
import app.particlebook.android.zlib.ZlibClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书源管理（设置 → 书源管理，task-41）。
 *
 * 数据只读复用 [ZlibClient]：[ZlibClient.mirrorRows] 提供同一时刻的线路快照，
 * [ZlibClient.switchMirror] 按 URL 切换；本页不复制线路注册表，也不向 zlib/ 增加状态。
 *
 * 探测与取消为什么这样写：
 * - [ZlibClient.probeMirrors] 是阻塞调用，整轮有客户端硬预算（默认 12s，可由 probeBudgetMs
 *   调整，下限 750ms），单个 host 另有 3s 预算；它没有取消参数，协程取消也打断不了正在进行的
 *   socket 读写。
 * - 因此探测放在进程级单飞 [ZlibProbeMonitor] 的 IO 协程里：同一时刻只允许一轮。用户点「取消」
 *   只停止等待并丢弃结果，阻塞调用仍由自身预算收尾；这段时间按钮显示「收尾中」且保持禁用，
 *   不会出现第二轮探测与第一轮并发。离开页面不取消探测，回来后仍能看到结果。
 * - 不额外加 UI 超时：客户端硬预算就是上界；提前解锁按钮会允许第二轮探测与第一轮并发，反而
 *   破坏防重入。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ZlibSourcesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val client = remember(context) { ZlibClient.get(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val probeRunning by ZlibProbeMonitor.running.collectAsState()
    val probeDraining by ZlibProbeMonitor.draining.collectAsState()
    val probeReport by ZlibProbeMonitor.report.collectAsState()
    val probeRevision by ZlibProbeMonitor.revision.collectAsState()

    var rows by remember { mutableStateOf<List<ZMirrorItem>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var switchingUrl by remember { mutableStateOf<String?>(null) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(reloadTick, probeRevision) {
        loadError = null
        runCatching {
            withContext(Dispatchers.IO) { client.mirrorRows() }
        }
            .onSuccess {
                rows = it
                nowMs = System.currentTimeMillis()
            }
            .onFailure {
                val message = ZlibUiError.message(it)
                if (rows == null) {
                    loadError = message
                } else {
                    snackbarHostState.showSnackbar(message)
                }
            }
    }

    // 相对时间跨分钟仍然准确；只在页面存活期间刷新，离开即取消。
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(60_000L)
            nowMs = System.currentTimeMillis()
        }
    }

    val switchTo: (ZMirrorItem) -> Unit = { item ->
        if (!item.current && switchingUrl == null) {
            scope.launch {
                switchingUrl = item.url
                val result = runCatching {
                    withContext(Dispatchers.IO) { client.switchMirror(item.url) }
                }
                switchingUrl = null
                val switched = result.getOrDefault(false)
                // 成功与失败都重读快照：失败可能是该 URL 已不在名单里，刷新后用户能改选。
                reloadTick++
                if (switched) {
                    val host = item.host.ifBlank { zlibMirrorHost(item.url) }
                    snackbarHostState.showSnackbar("已切换到 $host")
                } else {
                    snackbarHostState.showSnackbar(ZlibUiError.message(result.exceptionOrNull()))
                }
            }
        }
    }

    val startProbe: () -> Unit = {
        if (!ZlibProbeMonitor.start(client)) {
            scope.launch { snackbarHostState.showSnackbar("已有探测正在进行") }
        }
    }

    val cancelProbe: () -> Unit = {
        if (probeRunning && ZlibProbeMonitor.cancel()) {
            scope.launch { snackbarHostState.showSnackbar("已取消等待，探测会在后台收尾") }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { AppSnackbarHost(snackbarHostState, aboveCapsule = false) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "书源管理",
                        style = AppType.topBarTitle,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(Spacing.minTouchTarget)) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    when {
                        probeDraining -> ProbeAction(
                            label = "收尾中",
                            loading = true,
                            enabled = false,
                            onClick = {},
                        )

                        probeRunning -> ProbeAction(
                            label = "取消",
                            loading = true,
                            enabled = true,
                            onClick = cancelProbe,
                        )

                        else -> ProbeAction(
                            label = "探测全部",
                            loading = false,
                            enabled = true,
                            onClick = startProbe,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // 无胶囊的全屏二级页：只吃系统导航条，列表自身再留 32dp。
                .bottomBarPadding(),
        ) {
            val loaded = rows
            when {
                // 首次加载失败：整页错误态 + 重试，避免只剩「空列表」。
                loaded == null && loadError != null -> ReaderMessage(
                    title = "线路加载失败",
                    message = loadError.orEmpty(),
                    actionLabel = "重试",
                    onAction = { reloadTick++ },
                )

                loaded == null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingIndicator()
                }

                loaded.isEmpty() -> ReaderMessage(
                    title = "暂无可展示的线路",
                    message = "内置线路尚未加载。点右上角「探测全部」重新发现，或直接重试。",
                    actionLabel = "重新加载",
                    onAction = { reloadTick++ },
                )

                else -> SourcesList(
                    rows = loaded,
                    nowMs = nowMs,
                    switchingUrl = switchingUrl,
                    probeRunning = probeRunning,
                    probeDraining = probeDraining,
                    probeReport = probeReport,
                    onSelect = switchTo,
                )
            }
        }
    }
}

/** 顶栏右侧的探测动作：状态文案 + 可选进度环，整个按钮保持 48dp 触达高度。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProbeAction(
    label: String,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = Spacing.minTouchTarget),
    ) {
        if (loading) {
            LoadingIndicator(modifier = Modifier.size(Spacing.md))
            Spacer(Modifier.width(Spacing.xs))
        }
        Text(label)
    }
}

/** 概览 + 三组线路；所有行来自同一份 [groupMirrorRows] 快照。 */
@Composable
private fun SourcesList(
    rows: List<ZMirrorItem>,
    nowMs: Long,
    switchingUrl: String?,
    probeRunning: Boolean,
    probeDraining: Boolean,
    probeReport: ProbeReport?,
    onSelect: (ZMirrorItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = remember(rows) { groupMirrorRows(rows) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.screen,
            end = Spacing.screen,
            top = Spacing.sm,
            bottom = Spacing.xl,
        ),
    ) {
        item(key = "sources-overview") {
            SourcesOverview(
                rows = rows,
                nowMs = nowMs,
                probeRunning = probeRunning,
                probeDraining = probeDraining,
                probeReport = probeReport,
            )
            Spacer(Modifier.height(Spacing.sm))
        }
        groups.forEach { group ->
            item(key = "sources-header-" + group.tier) {
                SourceGroupHeader(group)
            }
            items(group.rows, key = { it.url }) { item ->
                SourceRow(
                    item = item,
                    nowMs = nowMs,
                    switching = switchingUrl == item.url,
                    listenerEnabled = switchingUrl == null && !item.current,
                    onSelect = { onSelect(item) },
                )
            }
        }
        item(key = "sources-footer") {
            Text(
                text = "「未测速」表示还没有测到延迟；「未探测」是探测预算内未轮到的线路，不代表不可用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.md),
            )
        }
    }
}

/** 当前线路 / 可用数量 / 最近一次探测结果。 */
@Composable
private fun SourcesOverview(
    rows: List<ZMirrorItem>,
    nowMs: Long,
    probeRunning: Boolean,
    probeDraining: Boolean,
    probeReport: ProbeReport?,
    modifier: Modifier = Modifier,
) {
    val currentHost = currentSourceHost(rows)
    val available = availableSourceCount(rows)
    val probeLine = when {
        probeDraining -> "已取消等待，后台探测正在收尾"
        probeRunning -> "正在探测全部线路…"
        probeReport != null -> "上次探测：" + probeReport.message
        else -> "上次探测：本次启动后尚未探测"
    }
    val probeColor = if (probeReport?.isError == true && !probeRunning && !probeDraining) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(Spacing.md)) {
            Text(
                text = "当前线路",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.xxs))
            Text(
                text = currentHost,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "可用线路 $available 条 · 共 ${rows.size} 条",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.xxs))
            Text(
                text = probeLine,
                style = MaterialTheme.typography.bodySmall,
                color = probeColor,
            )
        }
    }
}

/** 组头固定为「官方 · N 条」/「动态 · N 条」/「回退 · N 条」。 */
@Composable
private fun SourceGroupHeader(
    group: SourceGroup,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.lg, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = sourceGroupTitle(group),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 单条线路：host / 状态徽章 / 延迟 · 上次成功；点击切换，当前行不可再点。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SourceRow(
    item: ZMirrorItem,
    nowMs: Long,
    switching: Boolean,
    listenerEnabled: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.minTouchTarget)
            .clip(MaterialTheme.shapes.medium)
            .background(
                color = if (item.current) {
                    MaterialTheme.colorScheme.surfaceContainerLow
                } else {
                    Color.Transparent
                },
            )
            .selectable(
                selected = item.current,
                enabled = listenerEnabled,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(horizontal = Spacing.xs, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = item.current,
            onClick = null,
            enabled = !switching,
        )
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.host.ifBlank { zlibMirrorHost(item.url) },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Spacing.xxs))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                RankBadge(item.rank)
                if (item.current) {
                    StatusBadge(
                        text = "当前",
                        container = MaterialTheme.colorScheme.primary,
                        content = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                if (item.pinned) {
                    StatusBadge(
                        text = "已固定",
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(Spacing.xxs))
            Text(
                text = mirrorRowMeta(item, nowMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (switching) {
            Spacer(Modifier.width(Spacing.sm))
            LoadingIndicator(modifier = Modifier.size(Spacing.lg))
        }
    }
}

/** rank 徽章：不同 rank 用不同语义色，不只靠文字区分。 */
@Composable
private fun RankBadge(rank: Int) {
    val colors = when (rank) {
        0 -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        1 -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
        2 -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        3 -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        4 -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }
    StatusBadge(
        text = rankLabel(rank),
        container = colors.first,
        content = colors.second,
    )
}

/** 小号状态徽章；文字始终保留，颜色只做辅助。 */
@Composable
private fun StatusBadge(
    text: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
        )
    }
}

// ---------------------------------------------------------------------------
// 纯函数：分组 / 排序 / 时间与延迟文案。全部可直接单测，不依赖 Android framework。
// ---------------------------------------------------------------------------

/** 同一 tier 的一组线路；[count] 只用于组头，避免调用方再次 list.size。 */
internal data class SourceGroup(
    val tier: String,
    val label: String,
    val rows: List<ZMirrorItem>,
) {
    val count: Int get() = rows.size
}

/** 一轮探测的展示结果；[isError] 只影响概览行颜色。 */
internal data class ProbeReport(
    val message: String,
    val isError: Boolean,
)

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 86_400_000L

private val SOURCE_TIER_ORDER = listOf("official", "dynamic", "fallback")

/**
 * 官方 → 动态 → 回退固定顺序；空组不产生组头，未知 tier 归入回退，组内按
 * 当前 > 固定 > rank > 延迟（未知最后）> host 排序。
 */
internal fun groupMirrorRows(rows: List<ZMirrorItem>): List<SourceGroup> =
    SOURCE_TIER_ORDER.mapNotNull { tier ->
        val grouped = rows
            .filter { normalizeSourceTier(it.tier) == tier }
            .sortedWith(mirrorRowComparator)
        if (grouped.isEmpty()) {
            null
        } else {
            SourceGroup(tier = tier, label = sourceTierLabel(tier), rows = grouped)
        }
    }

/** 组头「官方 · N 条」；计数直接来自 [SourceGroup.count]。 */
internal fun sourceGroupTitle(group: SourceGroup): String = group.label + " · " + group.count + " 条"

/** tier 展示名；未知 tier 与 fallback 同组同标签。 */
internal fun sourceTierLabel(tier: String): String = when (normalizeSourceTier(tier)) {
    "official" -> "官方"
    "dynamic" -> "动态"
    else -> "回退"
}

private fun normalizeSourceTier(tier: String): String = when (tier.trim().lowercase()) {
    "official" -> "official"
    "dynamic" -> "dynamic"
    else -> "fallback"
}

/**
 * rank 展示顺序：0 正式站、1 未探测、2 推广页、3 其它都按原值，
 * 4 不通压到已知 rank 之后，未知 rank 再往后。
 */
private fun sourceRankOrder(rank: Int): Int = when (rank) {
    0, 1, 2, 3 -> rank
    4 -> 5
    else -> 6
}

private val mirrorRowComparator: Comparator<ZMirrorItem> =
    compareByDescending<ZMirrorItem> { it.current }
        .thenByDescending { it.pinned }
        .thenBy { sourceRankOrder(it.rank) }
        .thenBy { if (it.latencyMs >= 0L) it.latencyMs else Long.MAX_VALUE }
        .thenBy { it.host.lowercase() }
        .thenBy { it.url }

/** 延迟：未知显示「未测速」；毫秒内给整数 ms；≥1s 给一位小数的秒（截断，不四舍五入）。 */
internal fun formatLatency(ms: Long): String = when {
    ms < 0L -> "未测速"
    ms < 1_000L -> ms.toString() + " ms"
    else -> {
        val tenths = ms / 100L
        (tenths / 10L).toString() + "." + (tenths % 10L) + " s"
    }
}

/** 相对时间：<1 分钟「刚刚」、<1 小时「N 分钟前」、<1 天「N 小时前」、其余「N 天前」。 */
internal fun formatRelativeTime(deltaMs: Long): String {
    val delta = deltaMs.coerceAtLeast(0L)
    return when {
        delta < MINUTE_MS -> "刚刚"
        delta < HOUR_MS -> (delta / MINUTE_MS).toString() + " 分钟前"
        delta < DAY_MS -> (delta / HOUR_MS).toString() + " 小时前"
        else -> (delta / DAY_MS).toString() + " 天前"
    }
}

/** rank 文案与 [ZlibClient.mirrorRows] 的 0..4 契约一致，未知值不冒充「不通」。 */
internal fun rankLabel(rank: Int): String = when (rank) {
    0 -> "正式站"
    1 -> "未探测"
    2 -> "推广页"
    3 -> "其它"
    4 -> "不通"
    else -> "未知"
}

/** 上次成功时间；从未成功（lastOkAt <= 0）与「刚刚」必须能区分。 */
internal fun formatLastSuccess(lastOkAt: Long, now: Long): String =
    if (lastOkAt <= 0L) {
        "从未成功"
    } else {
        "上次成功 " + formatRelativeTime(now - lastOkAt)
    }

/** 行副信息：延迟 · 上次成功 · 暂不可用；未测速时不写「延迟 未测速」。 */
internal fun mirrorRowMeta(item: ZMirrorItem, nowMs: Long): String {
    val parts = ArrayList<String>(3)
    if (item.latencyMs >= 0L) {
        parts.add("延迟 " + formatLatency(item.latencyMs))
    } else {
        parts.add(formatLatency(item.latencyMs))
    }
    parts.add(formatLastSuccess(item.lastOkAt, nowMs))
    if (item.demoted) parts.add("暂不可用")
    return parts.joinToString(" · ")
}

/** 探测成功返回被 adopt 的 URL（不是摘要）；空值/异常兜底文案与 URL 分开处理。 */
internal fun probeResultSummary(raw: String?): String {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return "探测完成"
    val isUrl = value.startsWith("http://", ignoreCase = true) ||
        value.startsWith("https://", ignoreCase = true)
    if (!isUrl) return value
    val host = zlibMirrorHost(value)
    return if (host.isBlank()) "探测完成" else "探测完成 · 已选择 " + host
}

/** 当前线路展示名：优先注册表 host，缺失时从 URL 提取，仍为空则「未选择」。 */
internal fun currentSourceHost(rows: List<ZMirrorItem>): String {
    val current = rows.firstOrNull { it.current } ?: return "未选择"
    return current.host
        .ifBlank { zlibMirrorHost(current.url) }
        .ifBlank { "未选择" }
}

/** 可用线路只数 rank 0（探测确认的正式站）；未探测/推广页/不通都不算「可用」。 */
internal fun availableSourceCount(rows: List<ZMirrorItem>): Int = rows.count { it.rank == 0 }

/**
 * 进程级探测单飞。
 *
 * 为什么不是页面级 scope：阻塞调用在协程取消后仍会跑完，如果页面级控制器在返回后把
 * running 置 false，再次进入页面就能启动第二轮，两轮并发写注册表。这里用进程级 StateFlow
 * 持有 running/draining/report/revision，任何组合都看到同一状态，直到阻塞调用真的返回。
 *
 * 取消语义：[ZlibClient.probeMirrors] 没有取消参数，[cancel] 只能停止等待并丢弃本轮结果；
 * 阻塞调用由它自己的整轮预算收尾，完成前 [draining] 保持 true，UI 按钮保持禁用。
 */
internal object ZlibProbeMonitor {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _running = MutableStateFlow(false)
    private val _draining = MutableStateFlow(false)
    private val _report = MutableStateFlow<ProbeReport?>(null)
    private val _revision = MutableStateFlow(0)

    val running: StateFlow<Boolean> = _running.asStateFlow()
    val draining: StateFlow<Boolean> = _draining.asStateFlow()
    val report: StateFlow<ProbeReport?> = _report.asStateFlow()

    /** 每轮探测真正收尾后 +1；页面据此重读 mirrorRows，不轮询。 */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private var job: Job? = null
    private var cancelRequested = false

    /**
     * 启动一轮探测；已有探测（含取消后仍在收尾的那一轮）时返回 false。
     *
     * 只做状态切换和协程派发，不阻塞调用线程；真正的 [ZlibClient.probeMirrors] 在 IO 上执行。
     */
    fun start(client: ZlibClient): Boolean {
        synchronized(lock) {
            if (_running.value) return false
            _running.value = true
            _draining.value = false
            cancelRequested = false
            val newJob = scope.launch {
                val outcome = runCatching {
                    // NonCancellable：取消只影响等待方，不把正在跑的阻塞探测半路撕掉。
                    withContext(NonCancellable + Dispatchers.IO) { client.probeMirrors() }
                }
                val cancelled = synchronized(lock) { cancelRequested }
                if (!cancelled) {
                    _report.value = outcome.fold(
                        onSuccess = { ProbeReport(probeResultSummary(it), isError = false) },
                        onFailure = { ProbeReport(ZlibUiError.message(it), isError = true) },
                    )
                }
            }
            job = newJob
            // 即使协程在进入 NonCancellable 之前就被取消（body 可能完全不执行），
            // invokeOnCompletion 也会收尾状态，running 不会永久卡住。
            newJob.invokeOnCompletion {
                synchronized(lock) {
                    if (job === newJob) job = null
                    _running.value = false
                    _draining.value = false
                    cancelRequested = false
                    _revision.value = _revision.value + 1
                }
            }
        }
        return true
    }

    /**
     * 停止等待当前轮；重复调用无副作用。真正收尾由 start 的 completion 统一处理。
     *
     * @return true 表示本轮已进入「取消等待」状态；false 表示当时没有可取消的探测。
     */
    fun cancel(): Boolean {
        synchronized(lock) {
            if (!_running.value || _draining.value) return false
            cancelRequested = true
            _draining.value = true
            job?.cancel()
        }
        return true
    }
}
