package app.particlebook.android.ui

import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.reader.epub.layout.EpubChapterLayout
import app.particlebook.android.reader.epub.layout.EpubLayoutConfig
import app.particlebook.android.reader.epub.layout.Page
import app.particlebook.android.reader.epub.layout.PageSlice
import org.junit.Assert.assertEquals
import org.junit.Test

class EpubReaderScreenTest {

    private val config = EpubLayoutConfig(viewportWidthPx = 320, viewportHeightPx = 640)

    private fun layout(vararg pages: List<PageSlice>): EpubChapterLayout =
        EpubChapterLayout(
            chapterHref = "OEBPS/ch1.xhtml",
            chapterTitle = "第一章",
            config = config,
            measuredBlocks = emptyList(),
            pages = pages.mapIndexed { index, slices -> Page(index, slices, 100) },
        )

    @Test
    fun locatorInSameChapterRestoresBlockPage() {
        val book = layout(
            listOf(PageSlice(blockIndex = 0, lineStart = 0, lineEnd = 2, topPx = 0, heightPx = 40)),
            listOf(PageSlice(blockIndex = 1, lineStart = 0, lineEnd = 3, topPx = 0, heightPx = 60)),
        )
        val locator = ReaderLocator.Epub("OEBPS/ch1.xhtml", blockIndex = 1, charOffset = 0)
        assertEquals(1, initialPageFor(locator, book, fallbackPage = 0))
    }

    @Test
    fun locatorFromAnotherChapterFallsBackAndClamps() {
        val book = layout(
            listOf(PageSlice(blockIndex = 0, lineStart = 0, lineEnd = 1, topPx = 0, heightPx = 20)),
            listOf(PageSlice(blockIndex = 1, lineStart = 0, lineEnd = 1, topPx = 0, heightPx = 20)),
        )
        val locator = ReaderLocator.Epub("OEBPS/ch2.xhtml", blockIndex = 9, charOffset = 0)
        assertEquals(1, initialPageFor(locator, book, fallbackPage = 7))
    }

    @Test
    fun missingLocatorUsesClampedFallback() {
        val book = layout(
            listOf(PageSlice(blockIndex = 0, lineStart = 0, lineEnd = 1, topPx = 0, heightPx = 20)),
        )
        assertEquals(0, initialPageFor(null, book, fallbackPage = -3))
        val locator = ReaderLocator.Epub("OEBPS/ch1.xhtml", blockIndex = 42, charOffset = 0)
        assertEquals(0, initialPageFor(locator, book, fallbackPage = 0))
    }
}
