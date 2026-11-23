package app.particlebook.android.ui.about

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import app.particlebook.android.ui.Motion
import app.particlebook.android.ui.ReaderMessage
import app.particlebook.android.ui.Spacing
import kotlinx.coroutines.launch

/**
 * 开源许可页：本地搜索 + 按许可证分组的折叠列表。
 *
 * 纯渲染 + 两个只在页面内成立的 UI 状态：搜索词（[rememberSaveable]，旋屏/进程重建后还在）
 * 与折叠集合（同样是 UI 状态）。数据、分组算法、过滤算法都在 [AboutModels] 的纯函数里，
 * 本页只负责把 [LicenseGroup] 摆出来。
 *
 * 交互决定：
 * - 搜索词一变就把折叠状态清空：否则新命中的分组可能正好在用户折叠过的那一组里，
 *   用户搜到了却看不见，会以为「没有这个库」。
 * - 行只有在 `url` 是 http(s) 时才可点。许可文本用系统浏览器打开（ACTION_VIEW），
 *   本页不自带 WebView；打不开时用 snackbar 说明，而不是静默失败。
 * - 「未标注」是数据事实（依赖的 POM 没声明许可），组头与页脚都如实显示，不猜许可证。
 *
 * 200% 字号：库名、版本、组头都不锁 maxLines；行高只给 [Spacing.minTouchTarget] 下限。
 */
@Composable
fun LicensesScreen(
    entries: List<LicenseEntry>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    // 折叠的是「组」：集合里是许可证名。空集合 = 全部展开（本地清单只有两三种许可，全折叠像个空页）。
    var collapsed by rememberSaveable { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(query) { collapsed = emptySet() }

    val visible = remember(entries, query) { filterLicenses(entries, query) }
    val groups = remember(visible) { groupLicenses(visible) }
    val searching = query.isNotBlank()

    val openUrl: (LicenseEntry) -> Unit = { entry ->
        val opened = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.url)))
        }.isSuccess
        if (!opened) {
            scope.launch { snackbar.showSnackbar("没有能打开这个链接的应用") }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.statusBars)
                .navigationBarsPadding()
                .padding(horizontal = Spacing.screen),
        ) {
            Spacer(Modifier.height(Spacing.lg))
            AboutTopBar(title = "开源许可", onBack = onBack)
            Spacer(Modifier.height(Spacing.md))
            SearchField(
                query = query,
                onQueryChange = { query = it },
                onSearchKey = { keyboard?.hide() },
            )
            Spacer(Modifier.height(Spacing.sm))
            when {
                entries.isEmpty() -> ReaderMessage(
                    title = "还没有许可清单",
                    message = "这个构建没有内置许可清单数据。安装带许可清单的版本后，第三方库会按许可证列在这里。",
                    modifier = Modifier.weight(1f),
                )

                groups.isEmpty() -> ReaderMessage(
                    title = "没有匹配的库",
                    message = "「" + query.trim() + "」在库名、版本和许可证里都没有命中。",
                    actionLabel = "清除搜索",
                    onAction = { query = "" },
                    modifier = Modifier.weight(1f),
                )

                else -> LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = Spacing.xl),
                ) {
                    item(key = "summary") {
                        Text(
                            text = summaryText(total = entries.size, matches = visible.size, groupCount = groups.size, searching = searching),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = Spacing.xs),
                        )
                    }
                    for (group in groups) {
                        val expanded = group.license !in collapsed
                        item(key = "group:" + group.license) {
                            LicenseGroupHeader(
                                group = group,
                                expanded = expanded,
                                onToggle = {
                                    collapsed = if (expanded) collapsed + group.license else collapsed - group.license
                                },
                            )
                        }
                        if (expanded) {
                            items(
                                items = group.entries,
                                key = { entry -> "entry:" + entry.name + "@" + entry.version },
                            ) { entry ->
                                LicenseRow(entry = entry, onOpen = openUrl)
                            }
                        }
                    }
                    item(key = "footer") { LicensesFooter() }
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )
    }
}

/** 搜索框：与全文搜索页同款组件与形状，清空按钮走 Motion token 的进出场。 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchKey: () -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
        placeholder = {
            Text(
                text = "搜索库名、版本或许可证",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            // 装饰图标：搜索语义由输入框本身与占位文案承载。
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.size(Spacing.lg),
            )
        },
        trailingIcon = {
            AnimatedVisibility(
                visible = query.isNotEmpty(),
                enter = Motion.enter(),
                exit = Motion.exit(),
            ) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(Spacing.minTouchTarget),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "清除搜索词",
                        modifier = Modifier.size(Spacing.lg),
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearchKey() }),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.xxl),
    )
}

/** 组头：「许可证 · N 个」+ 展开/收起。状态同时用图标方向与可读描述表达，不只靠颜色。 */
@Composable
private fun LicenseGroupHeader(
    group: LicenseGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.minTouchTarget)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClickLabel = if (expanded) "收起这组依赖" else "展开这组依赖") { onToggle() }
            .padding(horizontal = Spacing.xs, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.license + " · " + group.entries.size + " 个",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "已展开" else "已折叠",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Spacing.lg),
        )
    }
}

/** 单条依赖：坐标 → 版本 · 许可证；有链接时整行可点，右侧给出「另开」线索。 */
@Composable
private fun LicenseRow(entry: LicenseEntry, onOpen: (LicenseEntry) -> Unit) {
    val link = entry.url.trim()
    val canOpen = link.startsWith("http://", ignoreCase = true) || link.startsWith("https://", ignoreCase = true)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.minTouchTarget)
            .clip(MaterialTheme.shapes.small)
            .then(
                if (canOpen) {
                    Modifier.clickable(onClickLabel = "在浏览器中打开许可文本") { onOpen(entry) }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = Spacing.xs, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val meta = metaLine(entry)
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (canOpen) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                // 动作已经由整行的 onClickLabel 朗读，这里的图标不重复发音。
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Spacing.md),
            )
        }
    }
}

/** 页脚：说明数据来源与「未标注」的确切含义。 */
@Composable
private fun LicensesFooter() {
    Text(
        text = "清单由构建期脚本从依赖的 POM 生成，不联网更新。POM 未声明许可证的依赖显示为「" + LICENSE_UNKNOWN + "」。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.md),
    )
}

/** 条目副行：版本与许可证（分组已经按许可证分开，这里仍保留，便于搜索命中后核对）。 */
private fun metaLine(entry: LicenseEntry): String =
    listOf(entry.version, entry.license).filter { it.isNotBlank() }.joinToString(" · ")

private fun summaryText(total: Int, matches: Int, groupCount: Int, searching: Boolean): String =
    if (searching) {
        "匹配 " + matches + " 条 · " + groupCount + " 组许可"
    } else {
        "共 " + total + " 个依赖 · " + groupCount + " 种许可"
    }
