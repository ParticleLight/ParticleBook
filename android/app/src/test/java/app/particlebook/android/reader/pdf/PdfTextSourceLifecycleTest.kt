package app.particlebook.android.reader.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0-4：文本源打开失败分类、文档/页句柄关闭与异常路径。 */
class PdfTextSourceLifecycleTest {

    @Test
    fun openSuccessExposesPageCountAndPageText() {
        val document = FakeDocument(pageCount = 2)
        val result = PdfTextSource.open({ document }, cacheLimit = 3)
        assertTrue(result is PdfTextOpenResult.Success)
        val source = (result as PdfTextOpenResult.Success).source
        assertEquals(2, source.pageCount)
        val page = source.page(0)
        assertEquals(0, page.pageIndex)
        assertEquals(200, page.pageWidthPoints)
        assertEquals(90, page.rotationDegrees)
        assertEquals(2, page.charCount)
        assertEquals("ab", page.text)
        source.close()
        assertTrue(document.closed)
    }

    @Test
    fun closeClosesCachedPageHandles() {
        val document = FakeDocument(pageCount = 2)
        val source = (PdfTextSource.open({ document }) as PdfTextOpenResult.Success).source
        source.page(0)
        source.close()
        assertTrue(document.pages[0].closed)
        assertFalse("未打开的页不应被创建", document.pages.size > 1)
    }

    @Test
    fun closeIsIdempotent() {
        val document = FakeDocument(pageCount = 1)
        val source = (PdfTextSource.open({ document }) as PdfTextOpenResult.Success).source
        source.page(0)
        source.close()
        source.close()
        assertEquals(1, document.closeCount)
    }

    @Test
    fun encryptedOpenFailureIsClassified() {
        val result = PdfTextSource.open({ throw FakePasswordException("password required") })
        assertTrue(result is PdfTextOpenResult.Failure)
        val failure = result as PdfTextOpenResult.Failure
        assertEquals(PdfTextFailureReason.ENCRYPTED, failure.reason)
        assertTrue(failure.cause is FakePasswordException)
    }

    @Test
    fun badPdfOpenFailureIsClassifiedAsOpenFailed() {
        val result = PdfTextSource.open({ throw IllegalStateException("not a pdf") })
        val failure = result as PdfTextOpenResult.Failure
        assertEquals(PdfTextFailureReason.OPEN_FAILED, failure.reason)
    }

    @Test
    fun negativePageCountClosesDocument() {
        val document = FakeDocument(pageCount = -1)
        val result = PdfTextSource.open({ document })
        val failure = result as PdfTextOpenResult.Failure
        assertEquals(PdfTextFailureReason.PAGE_COUNT_INVALID, failure.reason)
        assertTrue(document.closed)
    }

    @Test
    fun pageCountExceptionClosesDocument() {
        val document = BrokenPageCountDocument()
        val result = PdfTextSource.open({ document })
        assertTrue(result is PdfTextOpenResult.Failure)
        assertTrue(document.closed)
    }

    @Test
    fun failedOpenPageKeepsSourceUsable() {
        val document = FakeDocument(pageCount = 2, failFirstPage = true)
        val source = (PdfTextSource.open({ document }) as PdfTextOpenResult.Success).source
        assertThrows(PdfTextSourceException::class.java) { source.page(0) }
        assertEquals(0, source.cachedPageCount())
        assertNotNull(source.page(1))
        source.close()
    }

    @Test
    fun invalidCharBoxIsExposedAsNullRect() {
        val document = FakeDocument(pageCount = 1, validCharBox = false)
        val source = (PdfTextSource.open({ document }) as PdfTextOpenResult.Success).source
        val page = source.page(0)
        assertNull(page.chars[0].rect)
        assertEquals("ab", page.text)
    }

    private class FakeDocument(
        override val pageCount: Int,
        private val failFirstPage: Boolean = false,
        private val validCharBox: Boolean = true,
    ) : PdfTextDocumentHandle {
        val pages = ArrayList<FakePage>()
        var closed = false
        var closeCount = 0

        override fun openPage(pageIndex: Int): PdfTextPageHandle {
            if (pageIndex == 0 && failFirstPage) throw IllegalStateException("bad page")
            val page = FakePage(pageIndex, validCharBox)
            pages.add(page)
            return page
        }

        override fun close() {
            closed = true
            closeCount++
        }
    }

    private class BrokenPageCountDocument : PdfTextDocumentHandle {
        var closed = false
        override val pageCount: Int get() = throw IllegalStateException("cannot count pages")
        override fun openPage(pageIndex: Int): PdfTextPageHandle = throw IllegalStateException("not used")
        override fun close() {
            closed = true
        }
    }

    private class FakePage(
        val pageIndex: Int,
        private val validCharBox: Boolean,
    ) : PdfTextPageHandle {
        var closed = false
        override val widthPoints: Int = 200
        override val heightPoints: Int = 300
        override val cropBox: PdfBox? = PdfBox(10f, 20f, 190f, 280f)
        override val rotationDegrees: Int = 90

        override fun openTextPage(): PdfTextTextHandle = FakeText(validCharBox)

        override fun close() {
            closed = true
        }
    }

    private class FakeText(private val validCharBox: Boolean) : PdfTextTextHandle {
        override val charCount: Int = 2

        override fun charAt(index: Int): Char = if (index == 0) 'a' else 'b'

        override fun charBox(index: Int): PdfBox? {
            return if (validCharBox) PdfBox(30f, 30f, 40f, 40f) else null
        }

        override fun text(): String = "ab"

        override fun close() {
        }
    }

    private class FakePasswordException(message: String) : IllegalStateException(message)
}
