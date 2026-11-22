package app.particlebook.android.reader.epub.perf

import app.particlebook.android.reader.epub.EpubBlock
import app.particlebook.android.reader.epub.EpubChapter
import app.particlebook.android.reader.epub.EpubStyle
import app.particlebook.android.reader.epub.layout.EpubBlockMeasurer
import app.particlebook.android.reader.epub.layout.EpubLayoutEngine
import app.particlebook.android.reader.epub.layout.LineMetrics
import app.particlebook.android.reader.epub.layout.EpubChapterLayout
import app.particlebook.android.reader.epub.layout.EpubLayoutConfig
import app.particlebook.android.reader.epub.layout.MeasuredBlock
import app.particlebook.android.reader.epub.layout.Page
import app.particlebook.android.reader.epub.layout.PageSlice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubPerfPageRunsTest {

    private val config = EpubLayoutConfig(viewportWidthPx = 100, viewportHeightPx = 100)
    private val blocks = listOf(
        MeasuredBlock(0, EpubBlock.Paragraph("hello"), 20),
        MeasuredBlock(1, EpubBlock.Heading(1, "title"), 20),
        MeasuredBlock(2, EpubBlock.Image("a.png"), 30),
        MeasuredBlock(3, EpubBlock.Table(listOf(listOf("a"))), 20),
        MeasuredBlock(4, EpubBlock.HorizontalRule, 2),
        MeasuredBlock(5, EpubBlock.PageBreak, 0),
        MeasuredBlock(6, EpubBlock.Paragraph(""), 20),
    )

    private fun content(vararg slices: PageSlice): EpubPerfPageContent {
        val layout = EpubChapterLayout(
            chapterHref = "c.xhtml",
            chapterTitle = null,
            config = config,
            measuredBlocks = blocks,
            pages = listOf(Page(0, slices.toList(), 100)),
        )
        return EpubPerfPageRuns.of(layout, layout.pages.single())
    }

    @Test
    fun hiddenAndZeroHeightBlocksKeepOriginalSliceIndices() {
        val chapter = EpubChapter(
            href = "sparse.xhtml",
            blocks = listOf(
                EpubBlock.Paragraph("hidden", EpubStyle(hidden = true)),
                EpubBlock.Paragraph("visible"),
                EpubBlock.Paragraph(""),
                EpubBlock.Image("visible.png"),
                EpubBlock.Paragraph("also hidden", EpubStyle(hidden = true)),
                EpubBlock.ListItem(false, 0, "last"),
            ),
        )
        val lines = object : LineMetrics {
            override val lineCount = 1
            override fun lineTop(line: Int) = 0
            override fun lineBottom(line: Int) = 20
            override fun lineBaseline(line: Int) = 15
        }
        val engine = EpubLayoutEngine(EpubBlockMeasurer { block, index, _ ->
            when (block) {
                is EpubBlock.Image -> MeasuredBlock(index, block, 30)
                is EpubBlock.Paragraph -> MeasuredBlock(
                    index, block, if (block.text.isEmpty()) 0 else 20,
                    lines = if (block.text.isEmpty()) null else lines,
                )
                else -> MeasuredBlock(index, block, 20, lines = lines)
            }
        })
        val layout = engine.layout(chapter, config)
        val runs = EpubPerfPageRuns(layout)
        val visibleText = runs.of(Page(0, listOf(PageSlice(1, 0, 1, 0, 20)), 20))
        assertTrue(visibleText.hasContent)
        assertTrue(visibleText.textOnly)
        val image = runs.of(Page(1, listOf(PageSlice(3, 0, 0, 0, 30)), 30))
        assertTrue(image.hasContent)
        assertFalse(image.textOnly)
        assertTrue(runs.of(Page(2, listOf(PageSlice(5, 0, 1, 0, 20)), 20)).textOnly)
        assertFalse(runs.of(Page(3, listOf(PageSlice(0, 0, 1, 0, 20)), 20)).hasContent)
        assertFalse(runs.of(Page(4, listOf(PageSlice(2, 0, 1, 0, 20)), 20)).hasContent)
    }

    @Test
    fun paragraphAndHeadingAreTextOnly() {
        assertTrue(content(PageSlice(0, 0, 1, 0, 20)).textOnly)
        assertTrue(content(PageSlice(1, 0, 2, 0, 20)).textOnly)
    }

    @Test
    fun imageOrTableMakesPageNonText() {
        val withText = content(
            PageSlice(0, 0, 1, 0, 20),
            PageSlice(2, 0, 0, 20, 30),
        )
        assertTrue(withText.hasContent)
        assertFalse(withText.textOnly)

        val table = content(PageSlice(3, 0, 0, 0, 20))
        assertTrue(table.hasContent)
        assertFalse(table.textOnly)
    }

    @Test
    fun ruleAndPageBreakHandling() {
        val rule = content(PageSlice(4, 0, 0, 0, 2))
        assertTrue(rule.hasContent)
        assertFalse(rule.textOnly)

        val breakOnly = content(PageSlice(5, 0, 0, 0, 0))
        assertFalse(breakOnly.hasContent)
        assertFalse(breakOnly.textOnly)
    }

    @Test
    fun blankTextSliceAndEmptyPageHaveNoContent() {
        val blank = content(PageSlice(6, 0, 0, 0, 20))
        assertFalse(blank.hasContent)
        assertFalse(blank.textOnly)

        val empty = content()
        assertFalse(empty.hasContent)
        assertFalse(empty.textOnly)
    }
}
