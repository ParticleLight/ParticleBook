package app.particlebook.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

/**
 * 阅读设置（字号 / 行高）。
 *
 * 为什么单独一个文件而不是塞进 [EpubReaderScreen]：这两个值是**排版输入**，
 * 真正决定分页结果的只有 [app.particlebook.android.reader.epub.layout.EpubLayoutConfig]
 * 的 `baseTextSizeSp` 与 `lineHeightMultiplier`；弹层只负责产出这两个数，
 * 取值规则（夹取 / 吸附 / 文案）全部抽成纯函数，JVM 单测直接打真实实现。
 *
 * 取值范围与 [app.particlebook.android.settings.SettingsSchema] 的 fontSize(12..32)/
 * lineHeight(1.2..3.0) 对齐：设置页与阅读器改的是同一份持久化值，两边不能各说各话。
 *
 * 弹层形态沿用仓库约定（[BookDetailSheet] / [ZlibMirrorSheet]）：
 * `skipPartiallyExpanded`、`contentWindowInsets = WindowInsets(0)` + `navigationBarsPadding()`、
 * 左右边距 [Spacing.screen]；200% 字号下内容可滚动，不截断。
 */
object ReaderTypographySpec {
    const val MIN_FONT_SIZE_SP = 12f
    const val MAX_FONT_SIZE_SP = 32f
    /** 与 SettingsSchema 的 fontSize 默认值一致：设置页显示的「默认」和这里的「默认」必须是同一个数。 */
    const val DEFAULT_FONT_SIZE_SP = 18f
    /** 字号滑杆步进 1sp → 19 个中间点。 */
    const val FONT_SIZE_STEPS = 19

    const val MIN_LINE_HEIGHT = 1.2f
    const val MAX_LINE_HEIGHT = 3.0f
    /** 与 SettingsSchema 的 lineHeight 默认值一致。 */
    const val DEFAULT_LINE_HEIGHT = 1.8f
    /** 行高滑杆步进 0.1 → 17 个中间点。 */
    const val LINE_HEIGHT_STEPS = 17

    /** 浮点比较容差：滑杆回传 16.999998 时不该被当成「改过」，文案也不该抖成 16.999。 */
    const val EPSILON = 1e-4f
}

/**
 * 夹取 + 吸附到 1sp。NaN / 无穷 / 越界都收敛到合法值：
 * 排版输入不能带着脏值进 StaticLayout（行长、分页都会被带偏）。
 */
internal fun normalizeFontSize(raw: Float): Float {
    if (!raw.isFinite()) return ReaderTypographySpec.DEFAULT_FONT_SIZE_SP
    // 四舍五入（半数向上），不是 kotlin.math.round 的「半数取偶」：滑杆停在 16.5 时
    // 用户看到的是往大走，取偶会让 16.5 → 16 这种反直觉结果。
    val rounded = floor(raw + 0.5f)
    return rounded.coerceIn(ReaderTypographySpec.MIN_FONT_SIZE_SP, ReaderTypographySpec.MAX_FONT_SIZE_SP)
}

/** 夹取 + 吸附到 0.1。1.2..3.0 之外的值收敛到最近端点。 */
internal fun normalizeLineHeight(raw: Float): Float {
    if (!raw.isFinite()) return ReaderTypographySpec.DEFAULT_LINE_HEIGHT
    val snapped = floor(raw * 10f + 0.5f) / 10f
    return snapped.coerceIn(ReaderTypographySpec.MIN_LINE_HEIGHT, ReaderTypographySpec.MAX_LINE_HEIGHT)
}

/** 滑杆位置文案：整数不带小数点，"17 sp"。 */
internal fun fontSizeLabel(fontSizeSp: Float): String {
    val value = normalizeFontSize(fontSizeSp)
    val text = if (abs(value - round(value)) < ReaderTypographySpec.EPSILON) {
        round(value).toInt().toString()
    } else {
        value.toString()
    }
    return text + " sp"
}

/** 行高文案固定一位小数："1.5 倍"。 */
internal fun lineHeightLabel(multiplier: Float): String {
    val value = normalizeLineHeight(multiplier)
    // 用整数运算拼一位小数，避免 1.4999999 被格式化成 "1.5" 之外的抖动写法。
    val tenths = round(value * 10f).toInt()
    return (tenths / 10).toString() + "." + (tenths % 10).toString() + " 倍"
}

/**
 * 预览用的行高（sp）：字号 × 倍数。
 *
 * 预览必须走这条换算而不是写死 `lineHeight = 24.sp`：用户调字号时行距要跟着走，
 * 否则「预览看着变宽、正文没变」又是同一种外观耦合。
 */
internal fun previewLineHeightSp(fontSizeSp: Float, multiplier: Float): Float {
    val size = normalizeFontSize(fontSizeSp)
    val factor = normalizeLineHeight(multiplier)
    return size * factor
}

/** 预览段落：固定文案，只随两个设置变；不读真实书内容，避免弹层依赖阅读器状态。 */
internal const val READER_PREVIEW_TEXT =
    "字里行间留一点空白，读久了眼睛才不累。调整字号与行高，下面的正文会立即跟着变。"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    fontSizeSp: Float,
    lineHeightMultiplier: Float,
    onFontSizeChange: (Float) -> Unit,
    onLineHeightChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val size = normalizeFontSize(fontSizeSp)
    val lineHeight = normalizeLineHeight(lineHeightMultiplier)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = Spacing.screen),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "阅读设置",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("完成") }
            }
            Spacer(Modifier.height(Spacing.xs))

            SliderRow(
                label = "字号",
                valueLabel = fontSizeLabel(size),
                value = size,
                valueRange = ReaderTypographySpec.MIN_FONT_SIZE_SP..ReaderTypographySpec.MAX_FONT_SIZE_SP,
                steps = ReaderTypographySpec.FONT_SIZE_STEPS,
                onChange = { raw -> onFontSizeChange(normalizeFontSize(raw)) },
                onReset = { onFontSizeChange(ReaderTypographySpec.DEFAULT_FONT_SIZE_SP) },
                resetVisible = abs(size - ReaderTypographySpec.DEFAULT_FONT_SIZE_SP) > ReaderTypographySpec.EPSILON,
            )
            SliderRow(
                label = "行高",
                valueLabel = lineHeightLabel(lineHeight),
                value = lineHeight,
                valueRange = ReaderTypographySpec.MIN_LINE_HEIGHT..ReaderTypographySpec.MAX_LINE_HEIGHT,
                steps = ReaderTypographySpec.LINE_HEIGHT_STEPS,
                onChange = { raw -> onLineHeightChange(normalizeLineHeight(raw)) },
                onReset = { onLineHeightChange(ReaderTypographySpec.DEFAULT_LINE_HEIGHT) },
                resetVisible = abs(lineHeight - ReaderTypographySpec.DEFAULT_LINE_HEIGHT) > ReaderTypographySpec.EPSILON,
            )

            Text(
                text = "预览",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.xs))
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = READER_PREVIEW_TEXT,
                    modifier = Modifier.padding(Spacing.md),
                    fontSize = size.sp,
                    lineHeight = previewLineHeightSp(size, lineHeight).sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = "字号与行高会立即生效，并作为全局阅读偏好保存。PDF 是固定版式，这两项只对 EPUB 正文有效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Spacing.lg))
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
    onReset: () -> Unit,
    resetVisible: Boolean,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            if (resetVisible) {
                TextButton(
                    onClick = onReset,
                    modifier = Modifier.heightIn(min = Spacing.minTouchTarget),
                ) { Text("默认") }
            }
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.minTouchTarget)
                // 读屏要能听到「字号 17 sp」整体值，而不只是孤立的一条滑杆。
                .semantics {
                    contentDescription = label
                    stateDescription = valueLabel
                },
        )
    }
}