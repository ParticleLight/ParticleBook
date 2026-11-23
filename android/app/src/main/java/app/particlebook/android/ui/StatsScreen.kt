package app.particlebook.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.particlebook.android.data.ReadingDay
import app.particlebook.android.data.ReadingStats
import java.time.LocalDate

/**
 * 阅读统计页（无悬浮胶囊的二级页）：顶栏返回 + hero 卡 + 最近 7 天柱状图 + 三宫格汇总。
 *
 * ## 版式（M3 2026-11，密度低的「数据页」档）
 * - 表达强度：hero 一处强（primaryContainer 大卡 + 36sp 衬线数字），其余静。
 *   统计页属于「表单 / 设置 / 密集数据」一档，只让标题区表达，图表与汇总保持克制。
 * - 只出现两级形状：hero 与汇总卡用 extraLarge（28dp），柱顶用 small（8dp）。
 *   不给每块内容都套卡片——图表直接落在页面上，靠区块标题与留白分组。
 * - 容器色：hero 用 primaryContainer（本周是这页的主角），汇总用 surfaceContainerLow
 *   （低一档的层次），正文与说明用 onSurface / onSurfaceVariant。
 * - 触控目标：唯一的按钮是返回，size = [Spacing.minTouchTarget]（48dp）。
 *
 * ## 数字的字体
 * 三处会变的数字走 TypeTokens 的数值档（tabular figures，tnum）：hero 主数字 = [AppType.heroNumber]
 * （displaySmall 36sp 衬线），汇总三宫格 = [AppType.summaryNumber]（titleMedium），
 * 柱顶 = [AppType.tickNumber]（labelSmall）。这三档都是「源 slot + tnum」派生，
 * 字号/行高/字重/字距与原来的 typography slot 逐项相同，本页只是把数字换成等宽字形：
 * 9 → 10、45 → 100 这类变化不再改行宽，柱顶居中数字与汇总格不再左右抖。
 * 不在本文件自造 TextStyle，也不换字号——换字号会重排柱状图列宽。
 *
 * 边界：hero 副行「今天 X · 连续 N 天」与「统计范围为最近 N 天」是整句文案（数字夹在句子里，
 * 不是可对齐的计数列），继续用 bodyMedium / labelSmall 的比例数字；它们变化频率低，
 * 且换成等宽要另立 body 级数值档，收益不抵多一档维护成本。
 *
 * ## 时区
 * 页面不做任何日期推算：`ReadingStats.days` 已经由数据层按系统时区切好自然日，
 * 桶顺序固定为旧→新，最后一格就是今天。星期缩写由 `epochDay` 反查（与时区无关），
 * 因此「今天高亮哪一根柱」不依赖第二个时钟，跨零点时不会和数字口径打架。
 *
 * @param state 页面状态（由 [StatsViewModel] 提供；页面本身不持有 ViewModel）。
 * @param onBack 顶栏返回。
 * @param onRetry 失败态的重试入口；默认空实现，接线时传 `viewModel::refresh`。
 *   放在 modifier 之后是为了保住「状态 + 返回」的调用形状，接线方按命名参数传即可。
 */
@Composable
fun StatsScreen(
    state: StatsUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 二级页自己吃状态栏；底部只避让导航条，内容再留 32dp（无悬浮胶囊，不用 listBottomInset）。
                .windowInsetsPadding(WindowInsets.statusBars)
                .navigationBarsPadding()
                .padding(horizontal = Spacing.screen),
        ) {
            Spacer(Modifier.height(Spacing.lg))
            StatsTopBar(onBack = onBack)
            when (state) {
                StatsUiState.Loading -> StatsLoading(Modifier.fillMaxWidth().weight(1f))
                StatsUiState.Empty -> StatsEmpty(Modifier.fillMaxWidth().weight(1f))
                is StatsUiState.Failed -> StatsFailed(
                    message = state.message,
                    onRetry = onRetry,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )

                is StatsUiState.Ready -> StatsContent(
                    stats = state.stats,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StatsTopBar(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(Spacing.minTouchTarget)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = "阅读统计",
            style = AppType.sectionTitle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

// ---------------------------------------------------------------------------
// 四种状态
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatsLoading(modifier: Modifier = Modifier) {
    CenteredState(modifier) {
        LoadingIndicator(
            modifier = Modifier.semantics { contentDescription = "正在统计阅读记录" },
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "正在统计阅读记录…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StatsEmpty(modifier: Modifier = Modifier) {
    CenteredState(modifier) {
        Icon(
            imageVector = Icons.Outlined.MenuBook,
            // 装饰图标：含义由下面的标题与说明承载。
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Spacing.xxl),
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "还没有阅读记录",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.xxs))
        Text(
            text = "打开一本书开始计时",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StatsFailed(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    CenteredState(modifier) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Spacing.xxl),
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "统计没有加载出来",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.xxs))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.md))
        FilledTonalButton(onClick = onRetry) {
            Text(text = "重试", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * 状态页的共用骨架：内容不足一屏时垂直居中，超过一屏（200% 字号）时可滚动，
 * 不设固定高度，避免文字被裁。
 */
@Composable
private fun CenteredState(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

// ---------------------------------------------------------------------------
// Ready：hero + 图表 + 汇总
// ---------------------------------------------------------------------------

@Composable
private fun StatsContent(stats: ReadingStats, modifier: Modifier = Modifier) {
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(Spacing.md))
        StatsHeroCard(stats)
        StatsSectionHeader(title = "最近 7 天", trailing = "单位：分钟")
        WeekBarChart(days = stats.days)
        StatsSectionHeader(title = "累计")
        SummaryGrid(stats)
        Text(
            // 汇总卡的窗口写成常量引用：文案随 [StatsViewModel.LOOKBACK_DAYS] 一起变，不会各说各话。
            text = "统计范围为最近 " + StatsViewModel.LOOKBACK_DAYS + " 天",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm),
        )
        Spacer(Modifier.height(Spacing.xl))
    }
}

/**
 * 本周 hero：「本周阅读」+ 总时长 + 「今天 X · 连续 N 天」。
 *
 * 「本周」取的是图表窗口（最近 7 天）的合计，不是自然周：数据层只给 7 天窗口，
 * 另按自然周重算会让「柱高之和」和 hero 数字对不上。用户在图上看到的七天之和就是这里的数。
 */
@Composable
private fun StatsHeroCard(stats: ReadingStats, modifier: Modifier = Modifier) {
    val weekMillis = stats.days.sumOf { it.millis }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Text(
                text = "本周阅读",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = formatDurationOrZero(weekMillis),
                style = AppType.heroNumber,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "今天 " + formatDurationOrZero(stats.todayMillis) + " · 连续 " + stats.streakDays + " 天",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** 区块标题（22sp 衬线，与书架 / 搜索页同一个 slot）+ 可选的右侧单位说明。 */
@Composable
private fun StatsSectionHeader(title: String, trailing: String? = null, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.lg, bottom = Spacing.xs),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = title,
            style = AppType.sectionTitle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.xxs),
            )
        }
    }
}

/**
 * 最近 7 天柱状图：纯 Compose 的 Box 高度，不引第三方图表库。
 *
 * 结构是三段同步的行（数值 / 柱 / 星期），而不是 7 个「数值 + 柱 + 星期」的列——
 * 这样 200% 字号下数值行整体变高时，七根柱的起点仍然对齐；列式布局里每列文字行数不同会把柱顶错开。
 *
 * 柱高 = millis / 当日最大值；0 值日不画柱，数值位显示「—」。今天用 primary，
 * 其余用 secondaryContainer；颜色之外还有文字线索（星期位写「今天」、数值行加粗色），
 * 色觉障碍用户不靠颜色也能分辨。
 */
@Composable
private fun WeekBarChart(days: List<ReadingDay>, modifier: Modifier = Modifier) {
    if (days.isEmpty()) return
    val maxMillis = days.maxOf { it.millis }.coerceAtLeast(0L)
    val context = LocalContext.current
    // 系统「移除动画」开关命中时直接落位，不做柱高过渡（前庭敏感用户）。
    val reducedMotion = remember(context) { Motion.isReducedMotion(context) }
    // 组件尺寸，不是间距节奏：96dp = Spacing.xxl × 2，只服务本屏柱形比例，不进 SpacingTokens。
    val trackHeight = Spacing.xxl * 2
    val largeFont = LocalDensity.current.fontScale >= LARGE_FONT_SCALE
    // 形状阶梯的 small 档，只圆上沿（柱脚贴基线）；remember 让 7 根柱共用同一个 shape 实例。
    val barShape = remember { RoundedCornerShape(topStart = ShapeTokens.small, topEnd = ShapeTokens.small) }

    Column(modifier.fillMaxWidth()) {
        if (!largeFont) {
            // 数值行只在常规字号出现：列宽固定，200% 字号下四位数会被裁，此时以柱高与读屏描述为准。
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                days.forEachIndexed { index, day ->
                    Text(
                        text = chartMinuteLabel(day.millis),
                        style = AppType.tickNumber,
                        color = if (index == days.lastIndex) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        // 装饰：完整信息在柱节点的 contentDescription 里，避免读屏重复念数字。
                        modifier = Modifier
                            .weight(1f)
                            .clearAndSetSemantics {},
                    )
                }
            }
            Spacer(Modifier.height(Spacing.xxs))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            days.forEachIndexed { index, day ->
                val isToday = index == days.lastIndex
                val target = if (maxMillis <= 0L) 0f else (day.millis.toFloat() / maxMillis.toFloat()).coerceIn(0f, 1f)
                val spec = if (reducedMotion) snap<Float>() else Motion.product.spatialDefault.spec<Float>()
                val fraction by animateFloatAsState(
                    targetValue = target,
                    animationSpec = spec,
                    label = "statsBar_" + day.epochDay,
                )
                val barHeight = if (day.millis <= 0L) 0.dp else maxOf(Spacing.xxs, trackHeight * fraction)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(trackHeight)
                        .semantics(mergeDescendants = true) {
                            contentDescription = dayDescription(day.epochDay, day.millis, isToday)
                        },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(BAR_WIDTH_FRACTION)
                            .height(barHeight)
                            .clip(barShape)
                            .background(
                                if (isToday) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                },
                            ),
                    )
                }
            }
        }
        Spacer(Modifier.height(Spacing.xxs))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            days.forEachIndexed { index, day ->
                val isToday = index == days.lastIndex
                Text(
                    // 大字号下「今天」两字会挤爆列宽，退回单字星期；今天的语义仍由柱描述承载。
                    text = if (isToday && !largeFont) "今天" else weekdayShortLabel(day.epochDay),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isToday) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .clearAndSetSemantics {},
                )
            }
        }
    }
}

/**
 * 三宫格汇总：累计时长 / 累计翻页 / 读过 N 本。
 *
 * 数值允许换行（titleMedium 在 1/3 列宽里放不下「12 小时 30 分」），宁可格子变高也不缩字号；
 * 三格各自成一块读屏节点（值 + 标签一起念），标签用 onSurfaceVariant 与数值分层。
 */
@Composable
private fun SummaryGrid(stats: ReadingStats, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(vertical = Spacing.md, horizontal = Spacing.xxs),
            verticalAlignment = Alignment.Top,
        ) {
            SummaryCell(
                value = formatDuration(stats.totalMillis),
                label = "累计时长",
                modifier = Modifier.weight(1f),
            )
            SummaryCell(
                value = stats.pagesTurned.toString() + " 页",
                label = "累计翻页",
                modifier = Modifier.weight(1f),
            )
            SummaryCell(
                value = stats.booksRead.toString() + " 本",
                label = "读过",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SummaryCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(horizontal = Spacing.xxs)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = AppType.summaryNumber,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.xxs))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------
// 纯函数（JVM 可单测，见 StatsViewModelTest）
// ---------------------------------------------------------------------------

/**
 * 时长 → 中文文案。
 *
 * 口径按分钟向下取整（59 分 59 秒读作「59 分钟」，不掉到小时档）：
 * - 不足 1 分钟：「不到 1 分钟」（0 也走这里，因为 0 只在「有记录但极短」时出现）
 * - 不足 1 小时：「N 分钟」
 * - 不足 1 天：「X 小时 Y 分」；整小时省掉「0 分」（「2 小时」而不是「2 小时 0 分」）
 * - 1 天以上：「X 天 Y 小时」；整小时同上省掉尾项
 *
 * 负数按 0 处理：时钟回拨或脏数据不应该显示「-1 分钟」。
 */
internal fun formatDuration(millis: Long): String {
    val minutes = millis.coerceAtLeast(0L) / MINUTE_MILLIS
    return when {
        minutes < 1L -> "不到 1 分钟"
        minutes < HOUR_MINUTES -> minutes.toString() + " 分钟"
        minutes < DAY_MINUTES -> {
            val hours = minutes / HOUR_MINUTES
            val rest = minutes % HOUR_MINUTES
            if (rest == 0L) hours.toString() + " 小时" else hours.toString() + " 小时 " + rest + " 分"
        }

        else -> {
            val days = minutes / DAY_MINUTES
            val hours = (minutes % DAY_MINUTES) / HOUR_MINUTES
            if (hours == 0L) days.toString() + " 天" else days.toString() + " 天 " + hours + " 小时"
        }
    }
}

/**
 * hero / 副行的时长：0 必须显示「0 分钟」而不是 [formatDuration] 的「不到 1 分钟」——
 * 「本周阅读 不到 1 分钟」在完全没读过的时候是错误信息。
 */
internal fun formatDurationOrZero(millis: Long): String =
    if (millis <= 0L) "0 分钟" else formatDuration(millis)

/**
 * 柱顶的分钟数值：0 值日显示「—」（没有数据，不是「0 分钟」），不足 1 分钟显示「<1」。
 * 单位由区块标题右侧的「单位：分钟」统一交代，柱顶只留数字，列宽才够。
 */
internal fun chartMinuteLabel(millis: Long): String {
    if (millis <= 0L) return "—"
    val minutes = millis / MINUTE_MILLIS
    return if (minutes < 1L) "<1" else minutes.toString()
}

/** `epochDay` → 单字星期（一…日）。与时区无关：epochDay 已经是按日切好的序数。 */
internal fun weekdayShortLabel(epochDay: Long): String {
    val index = LocalDate.ofEpochDay(epochDay).dayOfWeek.value - 1
    return WEEKDAY_LABELS[index.coerceIn(0, WEEKDAY_LABELS.lastIndex)]
}

/** 单根柱的读屏描述：「今天，阅读 45 分钟」/「周三，没有阅读」。 */
internal fun dayDescription(epochDay: Long, millis: Long, isToday: Boolean): String {
    val day = if (isToday) "今天" else "周" + weekdayShortLabel(epochDay)
    val read = if (millis <= 0L) "没有阅读" else "阅读 " + formatDuration(millis)
    return day + "，" + read
}

private const val MINUTE_MILLIS: Long = 60_000L
private const val HOUR_MINUTES: Long = 60L
private const val DAY_MINUTES: Long = 24L * 60L

/** 大字号断点：≥1.3 倍时收起柱顶数值、「今天」退回单字星期（列宽固定，文字会挤爆）。 */
private const val LARGE_FONT_SCALE = 1.3f

/** 柱宽占列宽的比例：留出列间空气，7 根柱在同一行里仍然分得清。 */
private const val BAR_WIDTH_FRACTION = 0.62f

private val WEEKDAY_LABELS = arrayOf("一", "二", "三", "四", "五", "六", "日")
