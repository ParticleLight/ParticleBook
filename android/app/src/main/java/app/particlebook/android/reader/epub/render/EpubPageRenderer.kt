package app.particlebook.android.reader.epub.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import app.particlebook.android.reader.epub.EpubAlign
import app.particlebook.android.reader.epub.EpubBlock
import app.particlebook.android.reader.epub.EpubResourceResolver
import app.particlebook.android.reader.epub.EpubStyle
import app.particlebook.android.reader.epub.layout.EpubChapterLayout
import app.particlebook.android.reader.epub.layout.EpubLayoutConfig
import app.particlebook.android.reader.epub.layout.MeasuredBlock
import app.particlebook.android.reader.epub.layout.Page
import java.io.Closeable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 把 [EpubChapterLayout] 的 [Page] 画到 Canvas。
 *
 * 文本：按 MeasuredBlock 的块内容重建 StaticLayout，复用布局层的行切片和行几何；
 * 图片：按页面显示尺寸采样，单章累计预算 [EpubImageBudget]（默认 24MB），超限/失败画占位；
 * 表格/hr：按 M1 最小保真绘制。
 *
 * 渲染器不负责翻页、点击或目录；这些在 [EpubPageView]/EpubReaderScreen。
 * 线程：只能在主线程 draw；章节切换和 close 也在主线程调用。
 */
class EpubPageRenderer(
    private val density: Float = 1f,
    private val scaledDensity: Float = density,
    private val maxImageBytes: Long = EpubImageBudget.DEFAULT_MAX_BYTES,
) : Closeable {

    private val imageBudget = EpubImageBudget(maxImageBytes)
    private val bitmapCache = HashMap<String, Bitmap?>()
    private val bitmapBytes = HashMap<String, Long>()
    private val textLayouts = HashMap<Int, StaticLayout?>()
    private val blocks = HashMap<Int, MeasuredBlock>()

    private var chapter: EpubChapterLayout? = null
    private var resolver: EpubResourceResolver? = null

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22000000
        style = Paint.Style.FILL
    }
    private val placeholderTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99000000.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val tableTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A1A1A.toInt()
    }
    private val tableLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000
        strokeWidth = max(1f, density)
        style = Paint.Style.STROKE
    }
    private val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55000000
        style = Paint.Style.FILL
    }

    /**
     * 绑定章节。同一章同一 config 的重复调用不会清图片缓存；换章/换排版配置会 recycle。
     * [resolver] 用于读 EPUB 内部图片，通常由 EpubArchive 或 controller 适配器提供。
     */
    fun setChapter(layout: EpubChapterLayout, resolver: EpubResourceResolver? = this.resolver) {
        val previous = chapter
        val sameChapter = previous != null &&
            previous.chapterHref == layout.chapterHref &&
            previous.config == layout.config &&
            previous.pages === layout.pages
        if (!sameChapter) {
            clearImages()
            textLayouts.clear()
        }
        chapter = layout
        this.resolver = resolver
        blocks.clear()
        for (block in layout.measuredBlocks) blocks[block.blockIndex] = block
    }

    fun draw(canvas: Canvas, page: Page) {
        val layout = chapter ?: return
        val config = layout.config
        val left = config.horizontalPaddingPx.toFloat()
        val top = config.verticalPaddingPx.toFloat()
        val right = left + config.contentWidthPx
        val bottom = top + config.contentHeightPx
        canvas.save()
        canvas.clipRect(left, top, right, bottom)
        for (slice in page.slices) {
            val measured = blocks[slice.blockIndex] ?: continue
            val sliceTop = top + slice.topPx.toFloat()
            when (val block = measured.block) {
                is EpubBlock.Paragraph -> drawTextBlock(canvas, measured, slice.lineStart, slice.lineEnd, sliceTop, slice.heightPx)
                is EpubBlock.Heading -> drawTextBlock(canvas, measured, slice.lineStart, slice.lineEnd, sliceTop, slice.heightPx)
                is EpubBlock.ListItem -> drawTextBlock(canvas, measured, slice.lineStart, slice.lineEnd, sliceTop, slice.heightPx)
                is EpubBlock.Image -> drawImage(canvas, block, sliceTop, slice.heightPx, config)
                is EpubBlock.Table -> drawTable(canvas, block, sliceTop, slice.heightPx, config)
                EpubBlock.HorizontalRule -> drawRule(canvas, measured, sliceTop, slice.heightPx, config)
                EpubBlock.PageBreak -> Unit
            }
        }
        canvas.restore()
    }

    fun drawSelection(
        canvas: Canvas,
        rects: List<EpubSelectionRect>,
        color: Int = 0x664A90D9,
    ) {
        if (rects.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        for (rect in rects) {
            canvas.drawRect(rect.left, rect.top, rect.right, rect.bottom, paint)
        }
    }

    fun selectionRects(page: Page, range: EpubSelectionRange): List<EpubSelectionRect> {
        val geometry = selectionGeometry(page) ?: return emptyList()
        return EpubSelection.rects(geometry, range)
    }

    fun hitTest(page: Page, x: Float, y: Float): EpubSelectionHit? {
        val geometry = selectionGeometry(page) ?: return null
        return EpubSelection.hitTest(geometry, x, y)
    }

    /**
     * 当前页的字符选区几何。文本行用 StaticLayout 的 lineStart/End 和
     * getPrimaryHorizontal/getOffsetForHorizontal 做到字符级精确；图片/表格不参与文本选区。
     */
    fun selectionGeometry(page: Page): EpubSelectionGeometry? {
        val layout = chapter ?: return null
        val config = layout.config
        val entries = ArrayList<EpubSelectionLine>()
        val refs = ArrayList<LineRef>()
        val left = config.horizontalPaddingPx.toFloat()
        val pageTop = config.verticalPaddingPx.toFloat()
        for (slice in page.slices) {
            if (!slice.isTextSlice) continue
            val measured = blocks[slice.blockIndex] ?: continue
            val static = textLayouts.getOrPut(slice.blockIndex) { buildTextLayout(measured, config) } ?: continue
            val metrics = measured.lines ?: continue
            val sliceTop = pageTop + slice.topPx
            for (line in slice.lineStart until slice.lineEnd) {
                if (line !in 0 until metrics.lineCount) continue
                val lineTop = sliceTop + (metrics.lineTop(line) - metrics.lineTop(slice.lineStart))
                val lineBottom = sliceTop + (metrics.lineBottom(line) - metrics.lineTop(slice.lineStart))
                entries += EpubSelectionLine(
                    blockIndex = slice.blockIndex,
                    line = line,
                    charStart = static.getLineStart(line),
                    charEnd = static.getLineEnd(line),
                    left = left,
                    top = lineTop,
                    right = left + config.contentWidthPx,
                    bottom = lineBottom,
                )
                refs += LineRef(static, line, left)
            }
        }
        if (entries.isEmpty()) return null
        return object : EpubSelectionGeometry {
            override val lines: List<EpubSelectionLine> = entries

            override fun xForChar(lineIndex: Int, charOffset: Int): Float {
                val ref = refs.getOrNull(lineIndex) ?: return 0f
                val end = ref.layout.getLineEnd(ref.line)
                val start = ref.layout.getLineStart(ref.line)
                val offset = charOffset.coerceIn(start, end)
                return ref.left + ref.layout.getPrimaryHorizontal(offset)
            }

            override fun charAt(lineIndex: Int, x: Float): Int {
                val ref = refs.getOrNull(lineIndex) ?: return 0
                val local = (x - ref.left).coerceIn(0f, ref.layout.width.toFloat())
                return ref.layout.getOffsetForHorizontal(ref.line, local)
            }
        }
    }

    override fun close() {
        clearImages()
        textLayouts.clear()
        blocks.clear()
        chapter = null
        resolver = null
    }

    private fun drawTextBlock(
        canvas: Canvas,
        measured: MeasuredBlock,
        lineStart: Int,
        lineEnd: Int,
        top: Float,
        height: Int,
    ) {
        val config = chapter?.config ?: return
        val metrics = measured.lines ?: return
        if (lineStart !in 0 until metrics.lineCount) return
        val static = textLayouts.getOrPut(measured.blockIndex) { buildTextLayout(measured, config) } ?: return
        val sliceHeight = height.coerceAtLeast(1)
        val left = config.horizontalPaddingPx.toFloat()
        canvas.save()
        canvas.clipRect(left, top, left + config.contentWidthPx, top + sliceHeight)
        canvas.translate(left, top - metrics.lineTop(lineStart))
        static.draw(canvas)
        canvas.restore()
    }

    private fun drawImage(
        canvas: Canvas,
        block: EpubBlock.Image,
        top: Float,
        height: Int,
        config: EpubLayoutConfig,
    ) {
        val left = config.horizontalPaddingPx.toFloat()
        val width = config.contentWidthPx.toFloat()
        val safeHeight = height.coerceAtLeast(1).toFloat()
        val destination = RectF(left, top, left + width, top + safeHeight)
        val bitmap = bitmapFor(block.src, width.roundToInt(), safeHeight.roundToInt())
        if (bitmap != null) {
            val scale = min(width / bitmap.width.toFloat(), safeHeight / bitmap.height.toFloat())
            val drawWidth = bitmap.width * scale
            val drawHeight = bitmap.height * scale
            val drawLeft = left + (width - drawWidth) / 2f
            val drawTop = top + (safeHeight - drawHeight) / 2f
            canvas.drawBitmap(bitmap, null, RectF(drawLeft, drawTop, drawLeft + drawWidth, drawTop + drawHeight), imagePaint)
        } else {
            canvas.drawRect(destination, placeholderPaint)
            val label = block.alt?.takeIf { it.isNotBlank() } ?: "图片"
            placeholderTextPaint.textSize = min(16f * density, max(8f, safeHeight / 4f))
            canvas.drawText(
                label.take(24),
                destination.centerX(),
                destination.centerY() - (placeholderTextPaint.ascent() + placeholderTextPaint.descent()) / 2f,
                placeholderTextPaint,
            )
        }
    }

    private fun drawTable(
        canvas: Canvas,
        block: EpubBlock.Table,
        top: Float,
        height: Int,
        config: EpubLayoutConfig,
    ) {
        if (block.rows.isEmpty()) return
        val left = config.horizontalPaddingPx.toFloat()
        val width = config.contentWidthPx.toFloat()
        val rowHeight = height.toFloat().coerceAtLeast(1f) / block.rows.size
        val columns = block.rows.maxOf { it.size }.coerceAtLeast(1)
        val cellWidth = width / columns
        val base = (config.baseTextSizeSp * scaledDensity).coerceAtLeast(1f)
        tableTextPaint.textSize = min(base, max(8f, rowHeight * 0.55f))
        tableTextPaint.color = block.style.color ?: config.defaultTextColor
        val padding = 4f * density
        for (rowIndex in block.rows.indices) {
            val rowTop = top + rowIndex * rowHeight
            val baseline = rowTop + rowHeight / 2f -
                (tableTextPaint.ascent() + tableTextPaint.descent()) / 2f
            for (column in 0 until columns) {
                val text = block.rows[rowIndex].getOrNull(column).orEmpty()
                if (text.isEmpty()) continue
                val cellLeft = left + column * cellWidth
                canvas.save()
                canvas.clipRect(cellLeft + padding, rowTop, cellLeft + cellWidth - padding, rowTop + rowHeight)
                canvas.drawText(text, cellLeft + padding, baseline, tableTextPaint)
                canvas.restore()
            }
            canvas.drawLine(left, rowTop, left + width, rowTop, tableLinePaint)
        }
        canvas.drawLine(left, top + height, left + width, top + height, tableLinePaint)
    }

    private fun drawRule(
        canvas: Canvas,
        measured: MeasuredBlock,
        top: Float,
        height: Int,
        config: EpubLayoutConfig,
    ) {
        val left = config.horizontalPaddingPx.toFloat()
        val right = left + config.contentWidthPx
        val y = top + height.coerceAtLeast(1) / 2f
        val color = measured.block.style.color ?: 0x55000000
        rulePaint.color = color
        canvas.drawRect(left, y, right, y + max(1f, density), rulePaint)
    }

    private fun buildTextLayout(measured: MeasuredBlock, config: EpubLayoutConfig): StaticLayout? {
        val text = textOf(measured.block) ?: return null
        if (text.isEmpty()) return null
        val style = measured.block.style
        val headingScale = when (val block = measured.block) {
            is EpubBlock.Heading -> if (style.fontSizeSp == null) {
                when (block.level) {
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
            else -> 1f
        }
        val fontSizePx = ((style.fontSizeSp ?: config.baseTextSizeSp) * headingScale * scaledDensity)
            .coerceAtLeast(1f)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSizePx
            color = style.color ?: config.defaultTextColor
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }
        val spannable = SpannableString(text)
        val bold = style.bold || measured.block is EpubBlock.Heading
        if (bold || style.italic) {
            val typefaceStyle = when {
                bold && style.italic -> Typeface.BOLD_ITALIC
                bold -> Typeface.BOLD
                else -> Typeface.ITALIC
            }
            spannable.setSpan(
                StyleSpan(typefaceStyle),
                0,
                spannable.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        if (style.indentEm != 0f) {
            val indent = (style.indentEm * fontSizePx).roundToInt()
            spannable.setSpan(
                LeadingMarginSpan.Standard(indent, 0),
                0,
                spannable.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        val builder = StaticLayout.Builder.obtain(spannable, 0, spannable.length, paint, config.contentWidthPx)
            .setIncludePad(false)
            .setLineSpacing(0f, style.lineHeightMultiplier ?: config.lineHeightMultiplier)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
        when (style.align) {
            EpubAlign.CENTER -> builder.setAlignment(Layout.Alignment.ALIGN_CENTER)
            EpubAlign.END -> builder.setAlignment(Layout.Alignment.ALIGN_OPPOSITE)
            EpubAlign.JUSTIFY -> {
                builder.setAlignment(Layout.Alignment.ALIGN_NORMAL)
                builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
            }
            EpubAlign.START -> builder.setAlignment(Layout.Alignment.ALIGN_NORMAL)
        }
        return builder.build()
    }

    private fun bitmapFor(src: String, targetWidth: Int, targetHeight: Int): Bitmap? {
        if (src.isBlank()) return null
        if (bitmapCache.containsKey(src)) return bitmapCache[src]
        val bytes = runCatching { resolver?.read(src) }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            bitmapCache[src] = null
            return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            bitmapCache[src] = null
            return null
        }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth &&
            bounds.outHeight / (sample * 2) >= targetHeight
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }.getOrNull()
        if (bitmap == null) {
            bitmapCache[src] = null
            return null
        }
        val size = imageBudget.bytesFor(bitmap.width, bitmap.height)
        if (!imageBudget.tryReserve(bitmap.width, bitmap.height)) {
            bitmap.recycle()
            bitmapCache[src] = null
            return null
        }
        bitmapCache[src] = bitmap
        bitmapBytes[src] = size
        return bitmap
    }

    private fun textOf(block: EpubBlock): String? = when (block) {
        is EpubBlock.Paragraph -> block.text
        is EpubBlock.Heading -> block.text
        is EpubBlock.ListItem -> block.text
        else -> null
    }

    private fun clearImages() {
        for (bitmap in bitmapCache.values) {
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
        }
        bitmapCache.clear()
        bitmapBytes.clear()
        imageBudget.reset()
    }

    private data class LineRef(val layout: StaticLayout, val line: Int, val left: Float)
}