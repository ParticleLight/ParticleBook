package app.particlebook.android.reader.epub.layout

import app.particlebook.android.reader.epub.EpubBlock
import app.particlebook.android.reader.epub.EpubChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubPaginatorTest {

    private val paginator = EpubPaginator()

    @Test
    fun splitsTextBlockByLineAcrossPages() {
        val block = textBlock(0, lineHeights = List(10) { 10 })
        val pages = paginator.paginate(listOf(block), 30)
        assertEquals(4, pages.size)
        assertEquals(listOf(0, 3, 6, 9), pages.map { it.slices.single().lineStart })
        assertEquals(listOf(3, 6, 9, 10), pages.map { it.slices.single().lineEnd })
        assertEquals(listOf(30, 30, 30, 10), pages.map { it.usedHeightPx })
        assertTrue(pages.all { it.slices.single().isTextSlice })
    }

    @Test
    fun handlesVariableLineHeightsAndMargins() {
        val block = textBlock(0, lineHeights = listOf(10, 20, 10, 30), marginTop = 5, marginBottom = 7)
        val onePage = paginator.paginate(listOf(block), 100)
        assertEquals(1, onePage.size)
        assertEquals(5, onePage[0].slices.single().topPx)
        assertEquals(70, onePage[0].slices.single().heightPx)
        assertEquals(82, onePage[0].usedHeightPx)

        val split = paginator.paginate(listOf(block), 60)
        assertEquals(2, split.size)
        assertEquals(0, split[0].slices.single().lineStart)
        assertEquals(3, split[0].slices.single().lineEnd)
        assertEquals(5, split[0].slices.single().topPx)
        assertEquals(45, split[0].usedHeightPx)
        assertEquals(3, split[1].slices.single().lineStart)
        assertEquals(4, split[1].slices.single().lineEnd)
        assertEquals(5, split[1].slices.single().topPx)
        assertEquals(42, split[1].usedHeightPx)
    }

    @Test
    fun movesUnsplittableBlockToNextPage() {
        val paragraph = textBlock(0, lineHeights = listOf(10, 10))
        val image = solidBlock(1, height = 40, marginTop = 5)
        val pages = paginator.paginate(listOf(paragraph, image), 50)
        assertEquals(2, pages.size)
        assertEquals(0, pages[0].slices.single().blockIndex)
        assertEquals(1, pages[1].slices.single().blockIndex)
        assertEquals(5, pages[1].slices.single().topPx)
        assertEquals(45, pages[1].usedHeightPx)
    }

    @Test
    fun explicitPageBreakDoesNotCreateBlankPages() {
        val first = solidBlock(0, height = 10)
        val breakBlock = MeasuredBlock(
            blockIndex = 1,
            block = EpubBlock.PageBreak,
            heightPx = 0,
            forcePageBreakAfter = true,
        )
        val second = solidBlock(2, height = 10)
        val pages = paginator.paginate(listOf(first, breakBlock, second), 50)
        assertEquals(2, pages.size)
        assertEquals(listOf(0), pages[0].slices.map { it.blockIndex })
        assertEquals(listOf(2), pages[1].slices.map { it.blockIndex })
    }

    @Test
    fun pageBreakBeforeStartsNewPageOnlyWhenNeeded() {
        val first = solidBlock(0, height = 10)
        val second = solidBlock(1, height = 10, pageBreakBefore = true)
        val pages = paginator.paginate(listOf(first, second), 50)
        assertEquals(2, pages.size)
        assertEquals(listOf(0), pages[0].slices.map { it.blockIndex })
        assertEquals(listOf(1), pages[1].slices.map { it.blockIndex })

        val firstWithBreak = solidBlock(0, height = 10, pageBreakBefore = true)
        val alone = paginator.paginate(listOf(firstWithBreak), 50)
        assertEquals(1, alone.size)
    }

    @Test
    fun exactFitDoesNotCreateExtraPage() {
        val pages = paginator.paginate(
            listOf(solidBlock(0, height = 50), solidBlock(1, height = 50)),
            100,
        )
        assertEquals(1, pages.size)
        assertEquals(100, pages[0].usedHeightPx)
        assertEquals(listOf(0, 1), pages[0].slices.map { it.blockIndex })
    }

    @Test
    fun oversizedBlockIsPlacedOnEmptyPageWithoutLooping() {
        val pages = paginator.paginate(listOf(solidBlock(0, height = 200)), 100)
        assertEquals(1, pages.size)
        assertEquals(200, pages[0].usedHeightPx)
    }

    @Test
    fun lineTallerThanPageStillMakesProgress() {
        val block = textBlock(0, lineHeights = listOf(100, 10))
        val pages = paginator.paginate(listOf(block), 30)
        assertEquals(2, pages.size)
        assertEquals(0, pages[0].slices.single().lineStart)
        assertEquals(1, pages[0].slices.single().lineEnd)
        assertEquals(100, pages[0].slices.single().heightPx)
        assertEquals(1, pages[1].slices.single().lineStart)
        assertEquals(2, pages[1].slices.single().lineEnd)
    }

    @Test
    fun emptyInputHasNoPages() {
        assertTrue(paginator.paginate(emptyList(), 100).isEmpty())
    }

    @Test
    fun layoutEngineSkipsHiddenBlocksAndCountsLines() {
        val text = EpubBlock.Paragraph("hello", app.particlebook.android.reader.epub.EpubStyle())
        val hidden = EpubBlock.Paragraph("hidden", app.particlebook.android.reader.epub.EpubStyle(hidden = true))
        val chapter = EpubChapter("OEBPS/Text/ch1.xhtml", "T", listOf(text, hidden))
        val measurer = EpubBlockMeasurer { block, index, _ ->
            if (block is EpubBlock.Paragraph) {
                val fake = FakeLines(listOf(10, 10))
                MeasuredBlock(index, block, heightPx = 20, lines = fake)
            } else {
                MeasuredBlock(index, block, heightPx = 1)
            }
        }
        val engine = EpubLayoutEngine(measurer)
        val layout = engine.layout(
            chapter,
            EpubLayoutConfig(viewportWidthPx = 100, viewportHeightPx = 100),
        )
        assertEquals(1, layout.measuredBlocks.size)
        assertEquals(2, layout.totalLines)
        assertEquals(1, layout.pageCount)
    }

    private fun textBlock(
        index: Int,
        lineHeights: List<Int>,
        marginTop: Int = 0,
        marginBottom: Int = 0,
    ): MeasuredBlock = MeasuredBlock(
        blockIndex = index,
        block = EpubBlock.Paragraph("text"),
        heightPx = lineHeights.sum(),
        marginTopPx = marginTop,
        marginBottomPx = marginBottom,
        lines = FakeLines(lineHeights),
    )

    private fun solidBlock(
        index: Int,
        height: Int,
        marginTop: Int = 0,
        pageBreakBefore: Boolean = false,
    ): MeasuredBlock = MeasuredBlock(
        blockIndex = index,
        block = EpubBlock.Image("img"),
        heightPx = height,
        marginTopPx = marginTop,
        pageBreakBefore = pageBreakBefore,
    )

    private class FakeLines(private val heights: List<Int>) : LineMetrics {
        private val tops = IntArray(heights.size + 1)
        init {
            for (i in heights.indices) tops[i + 1] = tops[i] + heights[i]
        }
        override val lineCount: Int get() = heights.size
        override fun lineTop(line: Int): Int = tops[line]
        override fun lineBottom(line: Int): Int = tops[line + 1]
        override fun lineBaseline(line: Int): Int = tops[line + 1]
    }
}
