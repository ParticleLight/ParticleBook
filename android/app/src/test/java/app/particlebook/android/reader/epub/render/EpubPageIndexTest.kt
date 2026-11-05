package app.particlebook.android.reader.epub.render

import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.reader.epub.EpubCharLineMetrics
import app.particlebook.android.reader.epub.EpubBlock
import app.particlebook.android.reader.epub.EpubStyle
import app.particlebook.android.reader.epub.layout.EpubChapterLayout
import app.particlebook.android.reader.epub.layout.EpubLayoutConfig
import app.particlebook.android.reader.epub.layout.MeasuredBlock
import app.particlebook.android.reader.epub.layout.Page
import app.particlebook.android.reader.epub.layout.PageSlice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpubPageIndexTest {

    private val config = EpubLayoutConfig(viewportWidthPx = 100, viewportHeightPx = 100)
    private val layout = EpubChapterLayout(
        chapterHref = "OEBPS/Text/ch1.xhtml",
        chapterTitle = "第一章",
        config = config,
        measuredBlocks = listOf(
            MeasuredBlock(
                0,
                EpubBlock.Paragraph("hello", EpubStyle()),
                heightPx = 40,
                lines = fakeMetrics(),
            ),
            MeasuredBlock(1, EpubBlock.Image("cover.jpg"), heightPx = 30),
        ),
        pages = listOf(
            Page(0, listOf(PageSlice(0, 0, 1, 0, 20)), 20),
            Page(1, listOf(PageSlice(0, 1, 3, 0, 20)), 20),
            Page(2, listOf(PageSlice(1, 0, 0, 0, 30)), 30),
        ),
    )

    @Test
    fun findsPageForBlockLine() {
        assertEquals(0, EpubPageIndex.pageForBlockLine(layout, 0, 0))
        assertEquals(1, EpubPageIndex.pageForBlockLine(layout, 0, 1))
        assertEquals(1, EpubPageIndex.pageForBlockLine(layout, 0, 2))
        assertEquals(2, EpubPageIndex.pageForBlockLine(layout, 1, 0))
        assertNull(EpubPageIndex.pageForBlockLine(layout, 1, 1))
        assertNull(EpubPageIndex.pageForBlockLine(layout, 99, 0))
    }

    private fun fakeMetrics(): EpubCharLineMetrics = object : EpubCharLineMetrics {
        override val lineCount: Int = 2
        override fun lineTop(line: Int): Int = line * 10
        override fun lineBottom(line: Int): Int = line * 10 + 10
        override fun lineBaseline(line: Int): Int = line * 10 + 8
        override fun lineStart(line: Int): Int = line * 3
        override fun lineEnd(line: Int): Int = (line + 1) * 3
    }

    @Test
    fun locatorUsesChapterAndBlock() {
        assertEquals(
            1,
            EpubPageIndex.pageForLocator(layout, ReaderLocator.Epub("OEBPS/Text/ch1.xhtml#top", 0, 3)),
        )
        assertEquals(
            2,
            EpubPageIndex.pageForLocator(layout, ReaderLocator.Epub("OEBPS/Text/ch1.xhtml", 1, 0)),
        )
        assertNull(
            EpubPageIndex.pageForLocator(layout, ReaderLocator.Epub("OEBPS/Text/ch2.xhtml", 0, 0)),
        )
    }

    @Test
    fun locatorForPageUsesFirstSlice() {
        assertEquals(
            ReaderLocator.Epub("OEBPS/Text/ch1.xhtml", 0, 0),
            EpubPageIndex.locatorForPage(layout, 1),
        )
        assertEquals(
            ReaderLocator.Epub("OEBPS/Text/ch1.xhtml", 1, 0),
            EpubPageIndex.locatorForPage(layout, 2),
        )
        assertNull(EpubPageIndex.locatorForPage(layout, 99))
    }

    @Test
    fun clampsAndComputesPercent() {
        assertEquals(0, EpubPageIndex.clampPage(-1, 3))
        assertEquals(2, EpubPageIndex.clampPage(99, 3))
        assertEquals(0, EpubPageIndex.clampPage(1, 0))
        assertEquals(0.5f, EpubPageIndex.percent(1, 2, 0, 2), 0.0001f)
        assertEquals(0.75f, EpubPageIndex.percent(1, 2, 1, 2), 0.0001f)
    }
}