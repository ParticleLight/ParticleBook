package app.particlebook.android.reader.epub.layout

import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.Layout.Alignment
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import app.particlebook.android.reader.epub.EpubAlign
import app.particlebook.android.reader.epub.EpubBlock
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Android StaticLayout 适配器：把 EpubBlock 量成 [MeasuredBlock]。
 *
 * 只在设备/仪器测试里使用；纯 JVM 分页测试用 fake MeasuredBlock。
 * density 用于 dp->px，scaledDensity 用于 sp->px（受系统字体缩放影响）。
 */
class StaticLayoutBlockMeasurer(
    private val density: Float = 1f,
    private val scaledDensity: Float = density,
) : EpubBlockMeasurer {

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    override fun measure(block: EpubBlock, blockIndex: Int, config: EpubLayoutConfig): MeasuredBlock {
        val style = block.style
        val marginTop = (style.marginTopDp * density).roundToInt().coerceAtLeast(0)
        val marginBottom = (style.marginBottomDp * density).roundToInt().coerceAtLeast(0)
        val pageBreakBefore = style.pageBreakBefore

        return when (block) {
            is EpubBlock.Paragraph -> textBlock(
                block.text, block, blockIndex, config, marginTop, marginBottom, pageBreakBefore,
                headingLevel = 0, defaultBold = false,
            )

            is EpubBlock.Heading -> textBlock(
                block.text, block, blockIndex, config, marginTop, marginBottom, pageBreakBefore,
                headingLevel = block.level, defaultBold = true,
            )

            is EpubBlock.ListItem -> textBlock(
                block.text, block, blockIndex, config, marginTop, marginBottom, pageBreakBefore,
                headingLevel = 0, defaultBold = false,
            )

            is EpubBlock.Image -> {
                val maxWidth = config.contentWidthPx
                val rawWidth = block.width?.takeIf { it > 0 }?.let { (it * density).roundToInt() }
                val rawHeight = block.height?.takeIf { it > 0 }?.let { (it * density).roundToInt() }
                val width = (rawWidth ?: maxWidth).coerceIn(1, maxWidth)
                val height = when {
                    rawHeight != null -> rawHeight.coerceAtLeast(1)
                    else -> (width * 3f / 4f).roundToInt().coerceAtLeast(1)
                }
                MeasuredBlock(
                    blockIndex, block, heightPx = height,
                    marginTopPx = marginTop, marginBottomPx = marginBottom,
                    lines = null, pageBreakBefore = pageBreakBefore,
                )
            }

            is EpubBlock.Table -> {
                val basePx = (config.baseTextSizeSp * scaledDensity).coerceAtLeast(1f)
                val linePx = (basePx * (config.lineHeightMultiplier)).coerceAtLeast(1f)
                val rowCount = max(1, block.rows.size)
                val height = (rowCount * linePx + (rowCount + 1) * 8f * density).roundToInt().coerceAtLeast(1)
                MeasuredBlock(
                    blockIndex, block, heightPx = height,
                    marginTopPx = marginTop, marginBottomPx = marginBottom,
                    lines = null, pageBreakBefore = pageBreakBefore,
                )
            }

            EpubBlock.HorizontalRule -> MeasuredBlock(
                blockIndex, block, heightPx = max(1, density.roundToInt()),
                marginTopPx = marginTop, marginBottomPx = marginBottom,
                lines = null, pageBreakBefore = pageBreakBefore,
            )

            EpubBlock.PageBreak -> MeasuredBlock(
                blockIndex, block, heightPx = 0,
                marginTopPx = 0, marginBottomPx = 0,
                lines = null, pageBreakBefore = false, forcePageBreakAfter = true,
            )
        }
    }

    private fun textBlock(
        text: String,
        block: EpubBlock,
        blockIndex: Int,
        config: EpubLayoutConfig,
        marginTop: Int,
        marginBottom: Int,
        pageBreakBefore: Boolean,
        headingLevel: Int,
        defaultBold: Boolean,
    ): MeasuredBlock {
        if (text.isBlank()) {
            return MeasuredBlock(
                blockIndex, block, heightPx = 0,
                marginTopPx = marginTop, marginBottomPx = marginBottom,
                lines = null, pageBreakBefore = pageBreakBefore,
            )
        }
        val style = block.style
        val headingScale = if (headingLevel in 1..6 && style.fontSizeSp == null) {
            when (headingLevel) {
                1 -> 1.6f
                2 -> 1.4f
                3 -> 1.2f
                4 -> 1.1f
                5 -> 1.0f
                else -> 0.95f
            }
        } else {
            1f
        }
        val fontSizePx = ((style.fontSizeSp ?: config.baseTextSizeSp) * headingScale * scaledDensity)
            .coerceAtLeast(1f)
        val textColor = style.color ?: config.defaultTextColor
        paint.textSize = fontSizePx
        paint.color = textColor
        paint.typeface = Typeface.DEFAULT
        paint.isAntiAlias = true

        val spannable = SpannableString(text)
        val bold = style.bold || defaultBold
        val italic = style.italic
        if (bold || italic) {
            val typefaceStyle = when {
                bold && italic -> Typeface.BOLD_ITALIC
                bold -> Typeface.BOLD
                else -> Typeface.ITALIC
            }
            spannable.setSpan(
                StyleSpan(typefaceStyle), 0, spannable.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        if (style.indentEm != 0f) {
            val firstIndent = (style.indentEm * fontSizePx).roundToInt()
            spannable.setSpan(
                LeadingMarginSpan.Standard(firstIndent, 0),
                0, spannable.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

        val width = config.contentWidthPx
        val builder = StaticLayout.Builder.obtain(spannable, 0, spannable.length, paint, width)
            .setIncludePad(false)
            .setLineSpacing(0f, style.lineHeightMultiplier ?: config.lineHeightMultiplier)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
        when (style.align) {
            EpubAlign.CENTER -> builder.setAlignment(Alignment.ALIGN_CENTER)
            EpubAlign.END -> builder.setAlignment(Alignment.ALIGN_OPPOSITE)
            EpubAlign.JUSTIFY -> {
                builder.setAlignment(Alignment.ALIGN_NORMAL)
                builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
            }
            EpubAlign.START -> builder.setAlignment(Alignment.ALIGN_NORMAL)
        }
        val layout = builder.build()
        return MeasuredBlock(
            blockIndex = blockIndex,
            block = block,
            heightPx = layout.height,
            marginTopPx = marginTop,
            marginBottomPx = marginBottom,
            lines = StaticLineMetrics(layout),
            pageBreakBefore = pageBreakBefore,
        )
    }
}

private class StaticLineMetrics(private val layout: StaticLayout) : LineMetrics {
    override val lineCount: Int
        get() = layout.lineCount

    override fun lineTop(line: Int): Int = layout.getLineTop(line)

    override fun lineBottom(line: Int): Int = layout.getLineBottom(line)

    override fun lineBaseline(line: Int): Int = layout.getLineBaseline(line)
}
