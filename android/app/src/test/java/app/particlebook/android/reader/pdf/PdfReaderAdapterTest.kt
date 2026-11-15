package app.particlebook.android.reader.pdf

import app.particlebook.android.reader.AnnotationStore
import app.particlebook.android.reader.ReaderAnnotation
import app.particlebook.android.reader.ReaderAnnotationType
import app.particlebook.android.reader.ReaderFormat
import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.reader.adapter.PdfReaderAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** task-153：PdfReaderAdapter 的位置/文本/搜索/标注/选区与所有权。 */
class PdfReaderAdapterTest {

    @Test
    fun locatorAndGoToClampToDocumentRange() {
        val adapter = PdfReaderAdapter(fakeSource(), 7L, null, false, 0)

        assertEquals(ReaderLocator.Pdf(0), adapter.getLocator())
        assertTrue(adapter.goTo(ReaderLocator.Pdf(99)))
        assertEquals(ReaderLocator.Pdf(2), adapter.getLocator())
        assertTrue(adapter.goTo(ReaderLocator.Pdf(-1)))
        assertEquals(ReaderLocator.Pdf(0), adapter.getLocator())
        assertFalse(adapter.goTo(ReaderLocator.Text(0)))
        assertEquals(ReaderFormat.PDF, adapter.format)
        assertEquals(7L, adapter.bookId)
    }

    @Test
    fun getTextModelTruncatesAndRejectsNonPdfLocator() {
        val adapter = PdfReaderAdapter(fakeSource(), 1L, null, false, 0)

        val short = adapter.getTextModel(ReaderLocator.Pdf(0), maxChars = 4)!!
        assertEquals("0123", short.text)
        assertTrue(short.truncated)
        assertEquals(ReaderLocator.Pdf(0), short.locator)

        val whole = adapter.getTextModel(ReaderLocator.Pdf(2), maxChars = 100)!!
        assertEquals("page-two", whole.text)
        assertFalse(whole.truncated)

        assertNull(adapter.getTextModel(ReaderLocator.Text(0), maxChars = 10))
        val empty = adapter.getTextModel(ReaderLocator.Pdf(0), maxChars = 0)!!
        assertEquals("", empty.text)
        assertTrue(empty.truncated)
    }

    @Test
    fun searchIsCaseInsensitiveSkipsFailuresAndHonorsLimit() {
        val source = FailingDocumentTextSource(
            pages = listOf(
                pageText(0, "Hello Beta world"),
                pageText(1, "unavailable"),
                pageText(2, "beta beta"),
            ),
            failingPages = setOf(1),
        )
        val adapter = PdfReaderAdapter(source, 1L, null, false, 0)

        val hits = adapter.search("beta", limit = 50)
        assertEquals(2, hits.size)
        assertEquals(ReaderLocator.Pdf(0), hits[0].locator)
        assertTrue(hits[0].snippet.contains("Beta"))
        assertEquals(ReaderLocator.Pdf(2), hits[1].locator)

        assertEquals(1, adapter.search("beta", limit = 1).size)
        assertTrue(adapter.search("", limit = 10).isEmpty())
        assertTrue(adapter.search("missing", limit = 10).isEmpty())
    }

    @Test
    fun annotationsDelegateToStoreAndForceBookId() {
        val store = FakeAnnotationStore()
        val adapter = PdfReaderAdapter(fakeSource(), 42L, store, false, 0)
        val annotation = ReaderAnnotation(
            bookId = 0,
            type = ReaderAnnotationType.NOTE,
            locator = ReaderLocator.Pdf(1),
            quote = "selected",
            note = "my note",
        )

        val added = adapter.applyAnnotation(annotation)
        assertEquals(42L, added.bookId)
        assertEquals(1, store.added.size)
        assertTrue(adapter.removeAnnotation(added.id))
        assertFalse(adapter.removeAnnotation(999L))

        val noStore = PdfReaderAdapter(fakeSource(), 1L, null, false, 0)
        assertThrows(IllegalStateException::class.java) {
            noStore.applyAnnotation(annotation)
        }
        assertFalse(noStore.removeAnnotation(1L))
    }

    @Test
    fun selectionHelpersExposeReaderSelection() {
        val adapter = PdfReaderAdapter(selectionSource(), 3L, null, false, 0)

        val first = requireNotNull(adapter.beginSelection(0, 0.1f, 0.1f))
        assertEquals("H", first.text)
        assertEquals(1, first.rects.size)

        val extended = requireNotNull(adapter.extendSelection(0, 0.4f, 0.1f))
        assertEquals("Hello", extended.text)
        assertEquals(extended, adapter.getSelection())

        adapter.clearSelection()
        assertNull(adapter.getSelection())
    }

    @Test
    fun closeOnlyClosesOwnedDocument() {
        val owned = fakeSource()
        val ownedAdapter = PdfReaderAdapter(owned, 1L, null, true, 0)
        ownedAdapter.close()
        ownedAdapter.close()
        assertEquals(1, owned.closeCount)

        val borrowed = fakeSource()
        val borrowedAdapter = PdfReaderAdapter(borrowed, 1L, null, false, 0)
        borrowedAdapter.close()
        assertEquals(0, borrowed.closeCount)
    }

    private fun fakeSource(): FakeDocumentTextSource = FakeDocumentTextSource(
        listOf(
            pageText(0, "0123456789"),
            pageText(1, "page-one"),
            pageText(2, "page-two"),
        )
    )

    private fun selectionSource(): FakeDocumentTextSource {
        val chars = ArrayList<PdfChar>()
        "Hello".forEachIndexed { index, char ->
            chars += PdfChar(index, char.toString(), PdfNormalizedRect(0.1f + index * 0.06f, 0.1f, 0.06f, 0.1f))
        }
        return FakeDocumentTextSource(
            listOf(
                PdfPageText(
                    pageIndex = 0,
                    pageWidthPoints = 612,
                    pageHeightPoints = 792,
                    rotationDegrees = 0,
                    chars = chars,
                    text = "Hello",
                ),
            )
        )
    }

    private fun pageText(page: Int, text: String): PdfPageText = PdfPageText(
        pageIndex = page,
        pageWidthPoints = 612,
        pageHeightPoints = 792,
        rotationDegrees = 0,
        chars = emptyList(),
        text = text,
    )
}

/** 只让指定页 loadPage 失败，用于验证搜索的逐页降级。 */
private class FailingDocumentTextSource(
    private val pages: List<PdfPageText>,
    private val failingPages: Set<Int>,
) : PdfDocumentTextSource {
    override val pageCount: Int get() = pages.size

    override fun loadPage(page: Int): PdfPageText {
        if (page in failingPages) throw IllegalStateException("page failed: " + page)
        return pages[page]
    }

    override fun charAt(page: Int, xNorm: Float, yNorm: Float): PdfChar? = null

    override fun selectionRects(
        page: Int,
        startChar: Int,
        endChar: Int,
    ): List<PdfNormalizedRect> = emptyList()

    override fun close() {}
}

/** 最小 AnnotationStore 实现，记录 add 便于断言。 */
private class FakeAnnotationStore : AnnotationStore {
    val added = ArrayList<ReaderAnnotation>()
    private var nextId = 1L

    override fun list(bookId: Long): List<ReaderAnnotation> = added.filter { it.bookId == bookId }

    override fun add(annotation: ReaderAnnotation): ReaderAnnotation {
        val stored = annotation.copy(id = if (annotation.id == 0L) nextId++ else annotation.id)
        added += stored
        return stored
    }

    override fun update(annotation: ReaderAnnotation): ReaderAnnotation? = null

    override fun remove(annotationId: Long): Boolean = added.removeAll { it.id == annotationId }
}