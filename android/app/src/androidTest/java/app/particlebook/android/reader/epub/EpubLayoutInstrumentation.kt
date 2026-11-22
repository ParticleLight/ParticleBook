package app.particlebook.android.reader.epub

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.text.Layout
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.util.Log
import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.reader.epub.layout.EpubChapterLayout
import app.particlebook.android.reader.epub.layout.EpubLayoutConfig
import app.particlebook.android.reader.epub.layout.EpubLayoutEngine
import app.particlebook.android.reader.epub.layout.MeasuredBlock
import app.particlebook.android.reader.epub.layout.Page
import app.particlebook.android.reader.epub.layout.StaticLayoutBlockMeasurer
import app.particlebook.android.reader.epub.layout.StaticLineMetrics
import app.particlebook.android.reader.epub.render.EpubPageIndex
import app.particlebook.android.reader.epub.render.EpubPageRenderer
import app.particlebook.android.reader.epub.render.EpubSelectionRange
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Real-framework checks; no AndroidX/JUnit dependency and no production-data writes. */
class EpubLayoutInstrumentation : Instrumentation() {
    private data class Case(val name: String, val body: () -> Unit)

    private val runnerClass = "app.particlebook.android.reader.epub.EpubLayoutInstrumentation"
    private val config = EpubLayoutConfig(
        viewportWidthPx = 320,
        viewportHeightPx = 200,
        horizontalPaddingPx = 12,
        verticalPaddingPx = 10,
        baseTextSizeSp = 18f,
        lineHeightMultiplier = 1.3f,
        defaultTextColor = 0xFF253247.toInt(),
    )

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        super.onStart()
        val cases = listOf(
            Case("mixedStylePaintAndIdentity", ::mixedStylePaintAndIdentity),
            Case("bindingConfigAndCloseLifecycle", ::bindingConfigAndCloseLifecycle),
            Case("longParagraphLocatorAndSelection", ::longParagraphLocatorAndSelection),
            Case("fallbackPreparedAtBinding", ::fallbackPreparedAtBinding),
            Case("hiddenSparseBlockIndices", ::hiddenSparseBlockIndices),
            Case("pixelsMatchPreviousBuilder", ::pixelsMatchPreviousBuilder),
        )
        var passed = 0
        val failures = ArrayList<String>()
        for ((index, case) in cases.withIndex()) {
            val status = Bundle().apply {
                putString("class", runnerClass)
                putString("test", case.name)
                putInt("numtests", cases.size)
                putInt("current", index + 1)
            }
            sendStatus(1, status)
            var failure: Throwable? = null
            // Renderer is main-thread confined; catch inside the main-thread runnable.
            runOnMainSync {
                try {
                    case.body()
                } catch (error: Throwable) {
                    failure = error
                }
            }
            if (failure == null) {
                passed++
                status.putString("stream", "PASS " + case.name + "\n")
                sendStatus(0, status)
            } else {
                val trace = Log.getStackTraceString(failure)
                failures += case.name + ": " + trace
                status.putString("stack", trace)
                status.putString("stream", "FAIL " + case.name + ": " + trace + "\n")
                sendStatus(-2, status)
            }
        }
        finish(if (failures.isEmpty()) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putInt("tests", cases.size)
            putInt("passed", passed)
            putInt("failed", failures.size)
            putString("stream", "EPUB_LAYOUT tests=" + cases.size + " passed=" + passed +
                " failed=" + failures.size + "\n" + failures.joinToString("\n"))
        })
    }

    private fun mixedStylePaintAndIdentity() {
        val layout = measure(mixedBlocks())
        val paints = IdentityHashMap<TextPaint, Int>()
        val renderer = EpubPageRenderer()
        try {
            renderer.setChapter(layout)
            for (block in layout.measuredBlocks) {
                val actual = measuredLayout(block)
                check(paints.put(actual.paint, block.blockIndex) == null) { "TextPaint shared across blocks" }
                check(renderer.textLayoutFor(block.blockIndex) === actual) { "Renderer rebuilt block " + block.blockIndex }
                compareGeometry(actual, referenceLayout(block.block, layout.config))
            }
            // Run after all blocks are measured: later font/color assignments must not leak.
            check(measuredLayout(layout.measuredBlocks.first()).paint.textSize >
                measuredLayout(layout.measuredBlocks[1]).paint.textSize)
        } finally {
            renderer.close()
        }
    }

    private fun bindingConfigAndCloseLifecycle() {
        val engine = EpubLayoutEngine(StaticLayoutBlockMeasurer())
        val chapter = EpubChapter("text/reuse.xhtml", blocks = mixedBlocks())
        val first = engine.layout(chapter, config)
        val original = first.measuredBlocks.associate { it.blockIndex to measuredLayout(it) }
        val renderer = EpubPageRenderer()
        try {
            renderer.setChapter(first)
            for (page in first.pages) {
                drawPage(renderer, first, page)
                renderer.selectionGeometry(page)
            }
            renderer.setChapter(first)
            original.forEach { (index, static) -> check(renderer.textLayoutFor(index) === static) }
            val changedConfig = config.copy(viewportWidthPx = 260, baseTextSizeSp = 24f,
                defaultTextColor = Color.MAGENTA)
            val changed = engine.layout(chapter, changedConfig)
            renderer.setChapter(changed)
            for (block in changed.measuredBlocks) {
                val static = measuredLayout(block)
                check(renderer.textLayoutFor(block.blockIndex) === static)
                check(static !== original[block.blockIndex])
                compareGeometry(static, referenceLayout(block.block, changedConfig))
            }
            first.measuredBlocks.forEach { block ->
                compareGeometry(original.getValue(block.blockIndex), referenceLayout(block.block, config))
            }
            renderer.close()
            original.keys.forEach { check(renderer.textLayoutFor(it) == null) }
            renderer.setChapter(first)
            original.forEach { (index, static) -> check(renderer.textLayoutFor(index) === static) }
            drawPage(renderer, first, first.pages.first())
        } finally {
            renderer.close()
        }
    }

    private fun longParagraphLocatorAndSelection() {
        val text = (1..24).joinToString(" ") { "Whale $it crosses the sea with 中文 and a 🐳 beside the boat." }
        val layout = measure(listOf(EpubBlock.Paragraph(text, EpubStyle(indentEm = 1.2f))))
        check(layout.pageCount > 2)
        val measured = layout.measuredBlocks.single()
        val static = measuredLayout(measured)
        val reference = referenceLayout(measured.block, config)
        compareGeometry(static, reference)
        val renderer = EpubPageRenderer()
        try {
            renderer.setChapter(layout)
            for (page in layout.pages) {
                val slice = page.slices.first()
                val start = static.getLineStart(slice.lineStart)
                val locator = ReaderLocator.Epub(layout.chapterHref, measured.blockIndex, start)
                check(EpubPageIndex.pageForLocator(layout, locator) == page.index) { "UTF-16 locator page " + page.index }
                val geometry = checkNotNull(renderer.selectionGeometry(page))
                check(geometry.lines.map { it.line } == (slice.lineStart until slice.lineEnd).toList())
                val yOrigin = config.verticalPaddingPx + slice.topPx - reference.getLineTop(slice.lineStart)
                for ((index, line) in geometry.lines.withIndex()) {
                    check(line.charStart == reference.getLineStart(line.line))
                    check(line.charEnd == reference.getLineEnd(line.line))
                    near(line.top, (yOrigin + reference.getLineTop(line.line)).toFloat())
                    near(line.bottom, (yOrigin + reference.getLineBottom(line.line)).toFloat())
                    val probe = (line.charStart + 2).coerceAtMost(line.charEnd)
                    val x = config.horizontalPaddingPx + reference.getPrimaryHorizontal(probe)
                    near(geometry.xForChar(index, probe), x)
                    val hit = checkNotNull(renderer.hitTest(page, x, (line.top + line.bottom) / 2f))
                    val expectedChar = reference.getOffsetForHorizontal(line.line,
                        (x - config.horizontalPaddingPx).coerceIn(0f, reference.width.toFloat()))
                        .coerceIn(line.charStart, line.charEnd)
                    check(hit.blockIndex == measured.blockIndex && hit.charOffset == expectedChar && hit.lineIndex == index)
                }
                val line = geometry.lines.first()
                // Keep the end inside this line; a line-end offset may have the next line's x.
                val end = minOf(line.charStart + 5, line.charEnd - 1)
                if (end > line.charStart) {
                    val rect = renderer.selectionRects(page,
                        EpubSelectionRange(measured.blockIndex, line.charStart, end)).single()
                    val left = config.horizontalPaddingPx + reference.getPrimaryHorizontal(line.charStart)
                    val right = config.horizontalPaddingPx + reference.getPrimaryHorizontal(end)
                    near(rect.left, left.coerceIn(line.left, line.right))
                    near(rect.right, right.coerceIn(line.left, line.right))
                    near(rect.top, line.top)
                    near(rect.bottom, line.bottom)
                }
                check(renderer.textLayoutFor(measured.blockIndex) === static)
                drawPage(renderer, layout, page)
            }
        } finally {
            renderer.close()
        }
    }

    private fun fallbackPreparedAtBinding() {
        val production = measure(listOf(EpubBlock.Paragraph("Fallback text retains the same line geometry. ".repeat(12))))
        val original = production.measuredBlocks.single()
        val metrics = original.lines as EpubCharLineMetrics
        val wrapped = object : EpubCharLineMetrics {
            override val lineCount: Int get() = metrics.lineCount
            override fun lineTop(line: Int) = metrics.lineTop(line)
            override fun lineBottom(line: Int) = metrics.lineBottom(line)
            override fun lineBaseline(line: Int) = metrics.lineBaseline(line)
            override fun lineStart(line: Int) = metrics.lineStart(line)
            override fun lineEnd(line: Int) = metrics.lineEnd(line)
        }
        val fallback = production.copy(measuredBlocks = listOf(original.copy(lines = wrapped)))
        val renderer = EpubPageRenderer()
        try {
            renderer.setChapter(fallback)
            // This is before any Canvas draw, selection, hitTest or warm-up.
            val prepared = checkNotNull(renderer.textLayoutFor(original.blockIndex))
            check(prepared !== measuredLayout(original))
            compareGeometry(prepared, measuredLayout(original))
            for (page in fallback.pages) {
                drawPage(renderer, fallback, page)
                val geometry = checkNotNull(renderer.selectionGeometry(page))
                val line = geometry.lines.first()
                renderer.hitTest(page, line.left, (line.top + line.bottom) / 2f)
                check(renderer.textLayoutFor(original.blockIndex) === prepared)
            }
            renderer.setChapter(fallback)
            check(renderer.textLayoutFor(original.blockIndex) === prepared)
        } finally {
            renderer.close()
        }
    }

    private fun hiddenSparseBlockIndices() {
        val layout = measure(listOf(
            EpubBlock.Paragraph("hidden", EpubStyle(hidden = true)),
            EpubBlock.Paragraph("First visible text"),
            EpubBlock.Paragraph("  \n\t"),
            EpubBlock.Heading(2, "Second visible text", EpubStyle(color = Color.BLUE)),
            EpubBlock.Paragraph("hidden two", EpubStyle(hidden = true)),
            EpubBlock.ListItem(false, 0, "Third visible text"),
        ))
        check(layout.measuredBlocks.map { it.blockIndex } == listOf(1, 3, 5))
        val renderer = EpubPageRenderer()
        try {
            renderer.setChapter(layout)
            for (missing in listOf(0, 2, 4)) check(renderer.textLayoutFor(missing) == null)
            for (block in layout.measuredBlocks) check(renderer.textLayoutFor(block.blockIndex) === measuredLayout(block))
            val hitBlocks = linkedSetOf<Int>()
            for (page in layout.pages) {
                drawPage(renderer, layout, page)
                val geometry = checkNotNull(renderer.selectionGeometry(page))
                for (line in geometry.lines) {
                    val hit = checkNotNull(renderer.hitTest(page, line.left, (line.top + line.bottom) / 2f))
                    check(hit.blockIndex == line.blockIndex)
                    hitBlocks += hit.blockIndex
                }
            }
            check(hitBlocks.toList() == listOf(1, 3, 5)) { "Sparse block IDs lost in hit testing: $hitBlocks" }
        } finally {
            renderer.close()
        }
    }

    private fun pixelsMatchPreviousBuilder() {
        for ((index, currentConfig) in listOf(config, config.copy(viewportWidthPx = 370,
            baseTextSizeSp = 21f, lineHeightMultiplier = 1.6f)).withIndex()) {
            val scaledDensity = if (index == 0) 1f else 1.35f
            val layout = measure(mixedBlocks(), currentConfig, scaledDensity)
            val references = layout.measuredBlocks.associate {
                it.blockIndex to referenceLayout(it.block, currentConfig, scaledDensity)
            }
            val renderer = EpubPageRenderer(scaledDensity = scaledDensity)
            try {
                renderer.setChapter(layout)
                for (page in layout.pages) {
                    val actual = Bitmap.createBitmap(currentConfig.viewportWidthPx, currentConfig.viewportHeightPx,
                        Bitmap.Config.ARGB_8888)
                    val expected = Bitmap.createBitmap(actual.width, actual.height, Bitmap.Config.ARGB_8888)
                    try {
                        actual.eraseColor(Color.WHITE)
                        expected.eraseColor(Color.WHITE)
                        renderer.draw(Canvas(actual), page)
                        drawReference(Canvas(expected), page, layout, references)
                        val a = IntArray(actual.width * actual.height)
                        val b = IntArray(a.size)
                        actual.getPixels(a, 0, actual.width, 0, 0, actual.width, actual.height)
                        expected.getPixels(b, 0, expected.width, 0, 0, expected.width, expected.height)
                        check(b.any { it != Color.WHITE }) { "Reference drew no text on page " + page.index }
                        check(a.contentEquals(b)) { "Pixel mismatch config=" + currentConfig + " page=" + page.index }
                        if (index == 0 && page.index == layout.pages.last().index) {
                            // Negative control: alter only the old-builder reference on its final draw.
                            references.values.forEach { it.paint.color = Color.WHITE }
                            expected.eraseColor(Color.WHITE)
                            drawReference(Canvas(expected), page, layout, references)
                            expected.getPixels(b, 0, expected.width, 0, 0, expected.width, expected.height)
                            check(!a.contentEquals(b)) { "Pixel comparison accepted a wrong text color" }
                        }
                    } finally {
                        actual.recycle()
                        expected.recycle()
                    }
                }
            } finally {
                renderer.close()
            }
        }
    }

    private fun mixedBlocks(): List<EpubBlock> = listOf(
        EpubBlock.Heading(1, "A whale and 中文 🐳", EpubStyle(color = 0xFF89214A.toInt())),
        EpubBlock.Paragraph("Small red italic text appears before the next block.",
            EpubStyle(fontSizeSp = 13f, italic = true, color = Color.RED)),
        EpubBlock.Heading(3, "Explicit heading size", EpubStyle(fontSizeSp = 20f, color = Color.BLUE)),
        EpubBlock.ListItem(true, 1, "An indented bold italic list item with more words.",
            EpubStyle(fontSizeSp = 19f, bold = true, italic = true, indentEm = 1.4f)),
        EpubBlock.Paragraph("The long justified paragraph keeps every measured line and character position. ".repeat(5),
            EpubStyle(align = EpubAlign.JUSTIFY, lineHeightMultiplier = 1.4f)),
        EpubBlock.Paragraph("Centered words wrap onto another measured line.", EpubStyle(align = EpubAlign.CENTER)),
        EpubBlock.Paragraph("End aligned words", EpubStyle(align = EpubAlign.END, bold = true)),
    )

    private fun measure(
        blocks: List<EpubBlock>,
        currentConfig: EpubLayoutConfig = config,
        scaledDensity: Float = 1f,
    ): EpubChapterLayout = EpubLayoutEngine(StaticLayoutBlockMeasurer(scaledDensity = scaledDensity))
        .layout(EpubChapter("text/reuse.xhtml", blocks = blocks), currentConfig)

    private fun measuredLayout(block: MeasuredBlock): StaticLayout = (block.lines as StaticLineMetrics).layout

    private fun compareGeometry(actual: StaticLayout, expected: StaticLayout) {
        check(actual.width == expected.width && actual.height == expected.height)
        check(actual.lineCount == expected.lineCount && actual.alignment == expected.alignment)
        near(actual.paint.textSize, expected.paint.textSize)
        check(actual.paint.color == expected.paint.color)
        for (line in 0 until actual.lineCount) {
            check(actual.getLineStart(line) == expected.getLineStart(line))
            check(actual.getLineEnd(line) == expected.getLineEnd(line))
            check(actual.getLineTop(line) == expected.getLineTop(line))
            check(actual.getLineBottom(line) == expected.getLineBottom(line))
            check(actual.getLineBaseline(line) == expected.getLineBaseline(line))
            near(actual.getLineLeft(line), expected.getLineLeft(line))
            near(actual.getLineRight(line), expected.getLineRight(line))
        }
    }

    private fun near(actual: Float, expected: Float) {
        check(abs(actual - expected) < 0.01f) { "$actual != $expected" }
    }

    private fun drawPage(renderer: EpubPageRenderer, layout: EpubChapterLayout, page: Page) {
        val bitmap = Bitmap.createBitmap(layout.config.viewportWidthPx, layout.config.viewportHeightPx, Bitmap.Config.ARGB_8888)
        try {
            renderer.draw(Canvas(bitmap), page)
        } finally {
            bitmap.recycle()
        }
    }

    /** The pre-change renderer's Builder parameters, executed on Android as a visual reference. */
    private fun referenceLayout(
        block: EpubBlock,
        currentConfig: EpubLayoutConfig,
        scaledDensity: Float = 1f,
    ): StaticLayout {
        val text = when (block) {
            is EpubBlock.Paragraph -> block.text
            is EpubBlock.Heading -> block.text
            is EpubBlock.ListItem -> block.text
            else -> error("Text-only reference")
        }
        val style = block.style
        val scale = if (block is EpubBlock.Heading && style.fontSizeSp == null) {
            when (block.level) { 1 -> 1.6f; 2 -> 1.4f; 3 -> 1.2f; 4 -> 1.1f; 5 -> 1f; else -> 0.95f }
        } else 1f
        val size = ((style.fontSizeSp ?: currentConfig.baseTextSizeSp) * scale * scaledDensity).coerceAtLeast(1f)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            color = style.color ?: currentConfig.defaultTextColor
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }
        val spannable = SpannableString(text)
        val bold = style.bold || block is EpubBlock.Heading
        if (bold || style.italic) {
            val face = if (bold && style.italic) Typeface.BOLD_ITALIC else if (bold) Typeface.BOLD else Typeface.ITALIC
            spannable.setSpan(StyleSpan(face), 0, spannable.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (style.indentEm != 0f) {
            spannable.setSpan(LeadingMarginSpan.Standard((style.indentEm * size).roundToInt(), 0),
                0, spannable.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val builder = StaticLayout.Builder.obtain(spannable, 0, spannable.length, paint, currentConfig.contentWidthPx)
            .setIncludePad(false)
            .setLineSpacing(0f, style.lineHeightMultiplier ?: currentConfig.lineHeightMultiplier)
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

    private fun drawReference(canvas: Canvas, page: Page, chapter: EpubChapterLayout, references: Map<Int, StaticLayout>) {
        val cfg = chapter.config
        val left = cfg.horizontalPaddingPx.toFloat()
        val top = cfg.verticalPaddingPx.toFloat()
        canvas.save()
        canvas.clipRect(left, top, left + cfg.contentWidthPx, top + cfg.contentHeightPx)
        for (slice in page.slices) {
            val static = references.getValue(slice.blockIndex)
            val sliceTop = top + slice.topPx
            canvas.save()
            canvas.clipRect(left, sliceTop, left + cfg.contentWidthPx, sliceTop + max(1, slice.heightPx))
            canvas.translate(left, sliceTop - static.getLineTop(slice.lineStart))
            static.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }
}
