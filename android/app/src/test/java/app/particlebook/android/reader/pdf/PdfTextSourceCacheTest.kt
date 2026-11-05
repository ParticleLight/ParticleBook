package app.particlebook.android.reader.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0-4：页缓存上下限、LRU 淘汰与 close 生命周期。 */
class PdfTextSourceCacheTest {

    @Test
    fun cacheKeepsAtMostThreePagesAndEvictsLru() {
        val document = FakeDocument(pageCount = 5)
        val source = open(document)
        source.page(0)
        source.page(1)
        source.page(2)
        assertEquals(listOf(0, 1, 2), document.opened)
        assertEquals(3, source.cachedPageCount())
        assertFalse(document.pages[0].closed)

        source.page(3)
        assertEquals(listOf(0, 1, 2, 3), document.opened)
        assertTrue("最久未使用的第 0 页应被淘汰并关闭", document.pages[0].closed)
        assertFalse(document.pages[1].closed)
        assertEquals(3, source.cachedPageCount())
    }

    @Test
    fun cacheHitDoesNotReopenPage() {
        val document = FakeDocument(pageCount = 4)
        val source = open(document)
        source.page(0)
        source.page(1)
        source.page(2)
        source.page(1)
        assertEquals(listOf(0, 1, 2), document.opened)
        assertEquals(3, source.cachedPageCount())
    }

    @Test
    fun closeClosesCachedPagesAndDocumentOnce() {
        val document = FakeDocument(pageCount = 3)
        val source = open(document)
        source.page(0)
        source.page(1)
        source.close()
        assertTrue(document.pages[0].closed)
        assertTrue(document.pages[1].closed)
        assertEquals(1, document.closeCount)
        assertEquals(0, source.cachedPageCount())

        source.close()
        assertEquals(1, document.closeCount)
    }

    @Test
    fun pageAfterCloseThrows() {
        val source = open(FakeDocument(pageCount = 1))
        source.close()
        assertThrows(IllegalStateException::class.java) { source.page(0) }
    }

    @Test
    fun invalidPageIndexesAreRejected() {
        val source = open(FakeDocument(pageCount = 2))
        assertThrows(IllegalArgumentException::class.java) { source.page(-1) }
        assertThrows(IllegalArgumentException::class.java) { source.page(2) }
    }

    @Test
    fun textHandleIsClosedAfterEachRead() {
        val document = FakeDocument(pageCount = 1)
        val source = open(document)
        source.page(0)
        assertEquals(1, document.pages[0].textOpened)
        assertEquals(1, document.pages[0].textClosed)
    }

    @Test
    fun emptyPageHasNoTextLayerAndDoesNotCrash() {
        val document = FakeDocument(pageCount = 1, charCount = 0, textOverride = "")
        val source = open(document)
        val page = source.page(0)
        assertTrue(page.chars.isEmpty())
        assertEquals("", page.text)
        assertFalse(page.hasTextLayer)
    }

    @Test
    fun textFallsBackToJoinedCharactersWhenExtractionReturnsNull() {
        val document = FakeDocument(pageCount = 1, charCount = 2, textOverride = null)
        val source = open(document)
        val page = source.page(0)
        assertEquals("ab", page.text)
        assertEquals(2, page.chars.size)
        assertNotNull(page.chars[0].rect)
    }

    @Test
    fun textOpenFailureEvictsAndClosesPage() {
        val document = FakeDocument(pageCount = 1, failTextOpen = true)
        val source = open(document)
        assertThrows(PdfTextSourceException::class.java) { source.page(0) }
        assertTrue(document.pages[0].closed)
        assertEquals(0, source.cachedPageCount())
    }

    private fun open(document: FakeDocument): PdfTextSource {
        val result = PdfTextSource.open({ document }, cacheLimit = 3)
        assertTrue("打开失败了：" + result, result is PdfTextOpenResult.Success)
        return (result as PdfTextOpenResult.Success).source
    }

    private class FakeDocument(
        override val pageCount: Int,
        private val charCount: Int = 2,
        private val textOverride: String? = "ab",
        private val failTextOpen: Boolean = false,
    ) : PdfTextDocumentHandle {
        val pages = ArrayList<FakePage>()
        val opened = ArrayList<Int>()
        var closeCount = 0

        override fun openPage(pageIndex: Int): PdfTextPageHandle {
            opened.add(pageIndex)
            val page = FakePage(pageIndex, charCount, textOverride, failTextOpen)
            pages.add(page)
            return page
        }

        override fun close() {
            closeCount++
        }
    }

    private class FakePage(
        val pageIndex: Int,
        private val charCount: Int,
        private val textOverride: String?,
        private val failTextOpen: Boolean,
    ) : PdfTextPageHandle {
        var closed = false
        var textOpened = 0
        var textClosed = 0
        override val widthPoints: Int = 200
        override val heightPoints: Int = 300
        override val cropBox: PdfBox? = null
        override val rotationDegrees: Int = 0

        override fun openTextPage(): PdfTextTextHandle {
            textOpened++
            if (failTextOpen) throw IllegalStateException("cannot open text page")
            return FakeText(this, charCount, textOverride)
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeText(
        private val page: FakePage,
        private val charCountValue: Int,
        private val textOverride: String?,
    ) : PdfTextTextHandle {
        override val charCount: Int get() = charCountValue

        override fun charAt(index: Int): Char {
            return if (index == 0) 'a' else 'b'
        }

        override fun charBox(index: Int): PdfBox? {
            return PdfBox(10f, 10f, 20f, 20f)
        }

        override fun text(): String? = textOverride

        override fun close() {
            page.textClosed++
        }
    }
}
