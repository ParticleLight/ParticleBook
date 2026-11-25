package app.particlebook.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.particlebook.android.reader.tts.TtsPlaybackState

/** 朗读用的语速档位；顺序即点击循环顺序。 */
internal val TtsRates = listOf(0.8f, 1.0f, 1.2f, 1.5f)

/**
 * 朗读控制条：Idle 时本组件自己提前 return，只有朗读/暂停时才渲染一行。
 *
 * 一行实测 56dp 高：48dp 图标按钮（Spacing.minTouchTarget）+ 上下各 4dp 内边距。
 * 它是浮在正文之上的卡片，**不占**底栏高度——
 * EPUB 侧用 align(BottomCenter) 盖在底栏之上（底栏必须始终可点，否则朗读时翻不了页），
 * PDF 侧由调用方用它替换底栏；摆位一律交给调用方的 modifier。
 *
 * 规范对齐（M3 Expressive 可用面）：
 * - 容器：colorScheme.surfaceContainerHigh + shapes.extraLarge（浮层条档位）+ Elevation.level2/level3。
 * - 按钮：M3 IconButton / TextButton，不另造按钮；命中区固定 48dp（Spacing.minTouchTarget）。
 * - 图标一律带随状态变化的 contentDescription（播放/暂停/上一句/下一句/停止/语速），
 *   语速按钮额外用 semantics 播报「语速 x，点按切换」。
 * - 禁用色按规范取 onSurface.copy(alpha = 0.38f)（当前回调恒可用，但颜色契约先立好）。
 * - 会变的数字走 TypeTokens 的数值档：进度「本页 n / m」用 [AppType.compactNumber]（labelMedium + tnum），
 *   语速「1.2x」用 [AppType.inlineNumber]（labelLarge + tnum，与 TextButton 默认文字档同高）。
 *   两档都是「源 slot + tnum」派生，尺寸与原来逐项相同，只是数字等宽，位宽变化不再推挤同一行。
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
    val iconButtonColors = IconButtonDefaults.iconButtonColors(
        contentColor = scheme.onSurface,
        disabledContentColor = scheme.onSurface.copy(alpha = 0.38f),
    )
    val textButtonColors = ButtonDefaults.textButtonColors(
        contentColor = scheme.primary,
        disabledContentColor = scheme.onSurface.copy(alpha = 0.38f),
    )
    // 浮在正文之上时是"卡片"而不是"整宽色带"，圆角让读者能看出这是可关闭的控件层。
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurface,
        tonalElevation = Elevation.level2,
        shadowElevation = Elevation.level3,
    ) {
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
            Text(
                text = progressLabel,
                style = AppType.compactNumber,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
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
}
