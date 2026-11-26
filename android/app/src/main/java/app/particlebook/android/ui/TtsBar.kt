package app.particlebook.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.particlebook.android.reader.tts.TtsPlaybackState

/** 朗读用的语速档位；顺序即点击循环顺序。 */
internal val TtsRates = listOf(0.8f, 1.0f, 1.2f, 1.5f)

/** 没有定时计划时，定时按钮上的字面（有计划时换成宿主给的 m:ss）。 */
private const val SLEEP_TIMER_IDLE_LABEL = "定时"

/**
 * 朗读控制条：Idle 时本组件自己提前 return，只有朗读/暂停时才渲染。
 *
 * 两种形态，由 [onSleepTimer] 决定（宿主还没接定时入口时行为与旧版逐项相同）：
 * - **单行**（[onSleepTimer] == null）：一行实测 56dp 高——48dp 图标按钮（Spacing.minTouchTarget）
 *   + 上下各 4dp 内边距，进度夹在播放控制与语速之间。
 * - **双行**（给了 [onSleepTimer]）：同一张 Surface 里加一条顶行「进度 + 定时按钮」，
 *   下面是原样的播放控制行。**不在单行里加第 6 个按钮**：这一行已经有 4 个 48dp 图标按钮 +
 *   语速 + 3 个固定部件，再挤一个 48dp 目标只会把「本页 n / m」压成省略号，或者逼着缩小命中区；
 *   而定时是低频入口，抬到顶行后既保住 48dp，也不用改写播放行的间距关系。
 *
 * 摆位与容器：浮在正文之上的卡片，**不占**底栏高度——EPUB 侧用 align(BottomCenter) 盖在底栏之上
 * （底栏必须始终可点，否则朗读时翻不了页），PDF 侧由调用方用它替换底栏；一律交给调用方的 modifier。
 * 双行形态比单行高约 52dp（顶行 48dp 触控目标 + 4dp 间距），调用方按实际测量留出遮挡余量。
 *
 * 规范对齐（M3 Expressive 可用面）：
 * - 容器：colorScheme.surfaceContainerHigh + shapes.extraLarge（浮层条档位）+ Elevation.level2/level3。
 * - 按钮：M3 IconButton / TextButton，不另造按钮；命中区固定 48dp（Spacing.minTouchTarget）。
 * - 图标一律带随状态变化的 contentDescription（播放/暂停/上一句/下一句/停止/语速/定时），
 *   语速按钮额外用 semantics 播报「语速 x，点按切换」。
 * - 禁用色按规范取 onSurface.copy(alpha = 0.38f)（当前回调恒可用，但颜色契约先立好）。
 * - 会变的数字走 TypeTokens 的数值档：进度「本页 n / m」用 [AppType.compactNumber]（labelMedium + tnum），
 *   语速「1.2x」与定时「m:ss」用 [AppType.inlineNumber]（labelLarge + tnum）。
 *   两档都是「源 slot + tnum」派生，尺寸与原来逐项相同，只是数字等宽，位宽变化不再推挤同一行。
 *
 * 参数 [sleepTimerLabel] 是**函数**而不是 String：倒计时每秒变一次，若父阅读器在自己的作用域里读
 * `sleepTimer.label`，观察点落在整块阅读器上，每秒钟整屏重组一次。传 lambda 后读取发生在
 * [TtsSleepTimerRow] 内部（推迟读取），重组范围缩到定时那一行。
 *
 * 无障碍：定时倒计时每秒变一次，所以**不设 liveRegion**（设了就是每秒一次 TalkBack 播报）；
 * 剩余时间由定时按钮的 contentDescription 承载（「朗读定时停止，当前剩余 m:ss」），
 * 可见文字从语义树摘掉，读屏不会把同一串数字读两遍。
 *
 * 无状态组件：所有动作都由上层回调驱动。仓内没有 @Preview（全仓没有 Compose 预览），
 * 拆成这样是为了不拉起 TTS 引擎也能断言按钮与进度文案。
 */
@Composable
internal fun TtsBar(
    state: TtsPlaybackState,
    rate: Float,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCycleRate: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    progressUnit: String = TtsPlaybackState.DEFAULT_PROGRESS_UNIT,
    sleepTimerLabel: () -> String? = { null },
    onSleepTimer: (() -> Unit)? = null,
) {
    if (state is TtsPlaybackState.Idle) return
    val playing = state is TtsPlaybackState.Playing
    // 进度文案由状态机给（"本页 n / total"）：total 是**当前朗读单元**的句数而不是全书句数，
    // UI 再自己下标/判空就会重演「把 total 当全书」和「Idle 显示 0 / 0」两个老问题。
    // 单位（页 / 段）由调用方给：文本阅读器按段落朗读时传「本段」。
    val progressLabel = state.progressLabel(progressUnit).orEmpty()
    // 语速文案保持原格式（1.0 -> "1x"、1.2 -> "1.2x"），只多算一次供无障碍播报复用。
    val rateLabel = rate.toString().trimEnd('0').trimEnd('.') + "x"
    val scheme = MaterialTheme.colorScheme
    // 浮在正文之上时是"卡片"而不是"整宽色带"，圆角让读者能看出这是可关闭的控件层。
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurface,
        tonalElevation = Elevation.level2,
        shadowElevation = Elevation.level3,
    ) {
        if (onSleepTimer == null) {
            // 没有定时入口：保持原单行布局，进度仍夹在播放控制与语速之间。
            TtsPlaybackRow(
                playing = playing,
                rateLabel = rateLabel,
                progressLabel = progressLabel,
                onPlayPause = onPlayPause,
                onPrevious = onPrevious,
                onNext = onNext,
                onCycleRate = onCycleRate,
                onStop = onStop,
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                TtsSleepTimerRow(
                    progressLabel = progressLabel,
                    sleepTimerLabel = sleepTimerLabel,
                    onSleepTimer = onSleepTimer,
                )
                TtsPlaybackRow(
                    playing = playing,
                    rateLabel = rateLabel,
                    // 进度已经挪到顶行；这一行留空，让语速 / 停止照旧贴右缘。
                    progressLabel = null,
                    onPlayPause = onPlayPause,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onCycleRate = onCycleRate,
                    onStop = onStop,
                )
            }
        }
    }
}

/**
 * 顶行：左边朗读进度，右边定时入口。
 *
 * 只在 [TtsBar] 拿到 [onSleepTimer] 时存在。进度用与单行形态同一个 [AppType.compactNumber]，
 * 两种形态里"本页 3 / 9"的字号与等宽特性一致，接上定时不会让这串数字跳一档。
 *
 * 剩余时间在这里读一次：[sleepTimerLabel] 是 lambda，读取发生在本行作用域——
 * 每秒变化只重组这一行，不会把父阅读器的整屏拖进来。
 */
@Composable
private fun TtsSleepTimerRow(
    progressLabel: String,
    sleepTimerLabel: () -> String?,
    onSleepTimer: () -> Unit,
) {
    val sleepTimerText = sleepTimerLabel()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.xs, end = Spacing.xxs, top = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = progressLabel,
            style = AppType.compactNumber,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onSleepTimer,
            // 与语速按钮同一套收法：M3 TextButton 自带 58dp 最小宽度，不收会把顶行的进度挤成省略号；
            // 高度仍按 48dp 准入，命中区不缩。
            modifier = Modifier
                .defaultMinSize(minWidth = 0.dp, minHeight = Spacing.minTouchTarget)
                .semantics { contentDescription = sleepTimerContentDescription(sleepTimerText) },
            contentPadding = PaddingValues(horizontal = Spacing.xs, vertical = Spacing.xxs),
            colors = ttsTextButtonColors(),
        ) {
            Icon(
                imageVector = Icons.Outlined.Timer,
                contentDescription = null,
            )
            Spacer(Modifier.width(Spacing.xxs))
            Text(
                // 有计划时是宿主格式化好的 m:ss，没计划时是「定时」两字；
                // 两档都用 inlineNumber（labelLarge + tnum），m:ss 每秒变化时按钮宽度不抖。
                text = sleepTimerText ?: SLEEP_TIMER_IDLE_LABEL,
                style = AppType.inlineNumber,
                // 可见数字从语义树摘掉：读屏只念按钮的 contentDescription，
                // 不会把「4:32」连同「朗读定时停止，当前剩余 4:32」念两遍。
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

/**
 * 定时按钮的可读名称：视觉上只有「定时 / m:ss」，读屏要能听出这是定时入口、当前还剩多久。
 * 没有计划时明确说「未设置」，而不是让按钮沉默。
 */
private fun sleepTimerContentDescription(label: String?): String =
    if (label == null) "朗读定时停止，未设置" else "朗读定时停止，当前剩余 " + label

/**
 * 原样的播放控制行：上一句 / 播放暂停 / 下一句 /（进度）/ 语速 / 停止。
 *
 * [progressLabel] 为 null 时不渲染进度文本、改用一个 weight(1f) 占位——
 * 双行形态里进度在顶行，但语速与停止必须仍贴右缘，两行右侧控件才成一列。
 */
@Composable
private fun TtsPlaybackRow(
    playing: Boolean,
    rateLabel: String,
    progressLabel: String?,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCycleRate: () -> Unit,
    onStop: () -> Unit,
) {
    val iconButtonColors = ttsIconButtonColors()
    val textButtonColors = ttsTextButtonColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        IconButton(
            onClick = onPrevious,
            modifier = Modifier.size(Spacing.minTouchTarget),
            colors = iconButtonColors,
        ) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = "上一句")
        }
        IconButton(
            onClick = onPlayPause,
            modifier = Modifier.size(Spacing.minTouchTarget),
            colors = iconButtonColors,
        ) {
            Icon(
                imageVector = if (playing) Icons.Outlined.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "暂停朗读" else "继续朗读",
            )
        }
        IconButton(
            onClick = onNext,
            modifier = Modifier.size(Spacing.minTouchTarget),
            colors = iconButtonColors,
        ) {
            Icon(Icons.Filled.SkipNext, contentDescription = "下一句")
        }
        if (progressLabel == null) {
            Spacer(Modifier.weight(1f))
        } else {
            Text(
                text = progressLabel,
                style = AppType.compactNumber,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        // 语速按钮的留白必须收到 8dp：M3 TextButton 内部还套着一层 ButtonDefaults.MinWidth
        // （58dp）的最小宽度约束，外面再写 defaultMinSize(minWidth = 0.dp) 也压不到它下面，
        // 真正决定宽度的是内容 + contentPadding。不收的话这行的固定部件会把「本页 n / m」
        // 挤成省略号，真正要看的进度反而看不见。高度仍按 48dp 准入，不牺牲命中区。
        TextButton(
            onClick = onCycleRate,
            modifier = Modifier
                .defaultMinSize(minWidth = 0.dp, minHeight = Spacing.minTouchTarget)
                .semantics { contentDescription = "语速 " + rateLabel + "，点按切换" },
            contentPadding = PaddingValues(horizontal = Spacing.xs, vertical = Spacing.xxs),
            colors = textButtonColors,
        ) {
            // 语速是循环切换的会变数字，必须等宽：1x → 1.2x → 1.5x 时按钮宽度不能跟着跳。
            Text(rateLabel, style = AppType.inlineNumber)
        }
        IconButton(
            onClick = onStop,
            modifier = Modifier.size(Spacing.minTouchTarget),
            colors = iconButtonColors,
        ) {
            Icon(Icons.Filled.Close, contentDescription = "停止朗读")
        }
    }
}

/** 图标按钮着色：启用用 onSurface、禁用按规范 38% 透明（颜色只从语义角色来）。 */
@Composable
private fun ttsIconButtonColors(): IconButtonColors {
    val scheme = MaterialTheme.colorScheme
    return IconButtonDefaults.iconButtonColors(
        contentColor = scheme.onSurface,
        disabledContentColor = scheme.onSurface.copy(alpha = 0.38f),
    )
}

/** 文字按钮着色：动作色 primary、禁用同样 38% 透明；语速与定时共用一套，避免两处各写一份。 */
@Composable
private fun ttsTextButtonColors(): ButtonColors {
    val scheme = MaterialTheme.colorScheme
    return ButtonDefaults.textButtonColors(
        contentColor = scheme.primary,
        disabledContentColor = scheme.onSurface.copy(alpha = 0.38f),
    )
}
