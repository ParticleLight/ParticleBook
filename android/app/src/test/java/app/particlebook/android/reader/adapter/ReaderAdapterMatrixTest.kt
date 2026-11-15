package app.particlebook.android.reader.adapter

import app.particlebook.android.reader.AnnotationStore
import app.particlebook.android.reader.ReaderAdapter
import app.particlebook.android.reader.ReaderAnnotation
import app.particlebook.android.reader.ReaderAnnotationType
import app.particlebook.android.reader.ReaderFormat
import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.reader.ReaderSearchHit
import app.particlebook.android.reader.ReaderSelection
import app.particlebook.android.reader.ReaderTextModel
import app.particlebook.android.reader.epub.EpubBlock
import app.particlebook.android.reader.epub.EpubCharLineMetrics
import app.particlebook.android.reader.epub.EpubReaderController
import app.particlebook.android.reader.epub.layout.EpubBlockMeasurer
import app.particlebook.android.reader.epub.layout.EpubLayoutConfig
import app.particlebook.android.reader.epub.layout.MeasuredBlock
import app.particlebook.android.reader.pdf.PdfChar
import app.particlebook.android.reader.pdf.PdfDocumentTextSource
import app.particlebook.android.reader.pdf.PdfNormalizedRect
import app.particlebook.android.reader.pdf.PdfPageText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import kotlin.math.ceil
import kotlin.math.max

/**
 * ReaderAdapter 跨格式契约矩阵：EPUB/PDF 真实现 + TXT/HTML fake 实现。
 *
 * 目的不是重复各格式细节，而是保证 UI 只依赖 ReaderAdapter 时，
 * 7 个方法在所有格式上有一致的最低语义（位置、文本窗口、搜索、标注、生命周期）。
 */
class ReaderAdapterMatrixTest {

    private val layoutConfig = EpubLayoutConfig(viewportWidthPx = 400, viewportHeightPx = 100)

    @Test
    fun allAdaptersSatisfySevenMethodContract() {
        val cases = createCases()
        try {
            cases.forEach { case -> assertContract(case) }
        } finally {
            cases.forEach { it.close() }
        }
    }

    @Test
    fun closeIsIdempotentAndBoundsEveryAdapter() {
        val cases = createCases()
        try {
            cases.forEach { case -> assertClosedBoundary(case) }
        } finally {
            cases.forEach { it.close() }
        }
    }

    private fun assertContract(case: AdapterCase) {
        val adapter = case.adapter
        assertEquals(case.name, case.format, adapter.format)
        assertEquals(case.name, case.bookId, adapter.bookId)
        assertNotNull(case.name, adapter.getLocator())
        assertNull(case.name, adapter.getSelection())

        assertTrue(case.name + " goTo target", adapter.goTo(case.targetLocator))
        assertEquals(case.name + " locator after goTo", case.targetLocator, adapter.getLocator())

        val model = adapter.getTextModel(adapter.getLocator(), maxChars = 5)
        assertNotNull(case.name + " text model", model)
        assertTrue(case.name + " text length", model!!.text.length <= 5)
        assertFalse(case.name + " empty text", model.text.isEmpty())
        assertTrue(case.name + " truncated", model.truncated)

        assertNull(case.name + " wrong locator kind", adapter.getTextModel(case.wrongLocator(), 10))

        val hits = adapter.search(case.query, limit = 5)
        assertTrue(case.name + " search hits", hits.isNotEmpty())
        assertTrue(case.name + " search limit", hits.size <= 5)
        assertTrue(case.name + " snippet", hits.first().snippet.contains(case.query, ignoreCase = true))

        val annotation = ReaderAnnotation(
            bookId = 0L,
            type = ReaderAnnotationType.NOTE,
            locator = case.targetLocator,
            quote = "matrix",
        )
        val saved = adapter.applyAnnotation(annotation)
        assertTrue(case.name + " annotation id", saved.id > 0L)
        assertTrue(case.name + " remove annotation", adapter.removeAnnotation(saved.id))
        assertFalse(case.name + " remove missing annotation", adapter.removeAnnotation(Long.MAX_VALUE))
    }

    private fun assertClosedBoundary(case: AdapterCase) {
        val adapter = case.adapter
        adapter.close()
        adapter.close()

        assertNull(case.name + " text model after close", adapter.getTextModel(case.targetLocator, 10))
        assertFalse(case.name + " goTo after close", adapter.goTo(case.targetLocator))
        assertTrue(case.name + " search after close", adapter.search(case.query, 10).isEmpty())
        assertFalse(case.name + " remove after close", adapter.removeAnnotation(1L))
        assertNull(case.name + " selection after close", adapter.getSelection())

        val locator = runCatching { adapter.getLocator() }
        if (case.format == ReaderFormat.PDF) {
            // PDF adapter 的位置是纯页码快照，允许在 close 后读取；它不持有 native 资源。
            assertEquals(case.name + " locator after close", case.initialLocator, locator.getOrNull())
        } else {
            assertTrue(case.name + " locator after close should fail", locator.isFailure)
        }
    }

    private fun createCases(): List<AdapterCase> {
        val cases = mutableListOf<AdapterCase>()

        val seed = listOf(
            File("src/main/assets/seed/seed-01.epub"),
            File("app/src/main/assets/seed/seed-01.epub"),
        ).firstOrNull { it.isFile }
        Assume.assumeNotNull(seed)
        val epubController = EpubReaderController.open(seed!!, MatrixMeasurer(), bookId = 42L)
        epubController.openChapter(0)
        epubController.layoutChapter(layoutConfig)
        val epubStore = MatrixStore()
        val epubAdapter = EpubReaderAdapter(epubController, layoutConfig, epubStore)
        cases += AdapterCase(
            name = "EPUB",
            adapter = epubAdapter,
            format = ReaderFormat.EPUB,
            bookId = 42L,
            initialLocator = ReaderLocator.Epub(epubController.book.spine[0].href, 0, 0),
            targetLocator = ReaderLocator.Epub(epubController.book.spine[1].href, 0, 0),
            query = "河",
            close = { epubAdapter.close(); epubController.close() },
        )

        val pdfStore = MatrixStore()
        val pdfAdapter = PdfReaderAdapter(
            document = MatrixPdfSource(
                listOf(
                    pdfPage(0, "alpha beta"),
                    pdfPage(1, "beta page two"),
                    pdfPage(2, "gamma"),
                ),
            ),
            bookId = 7L,
            annotationStore = pdfStore,
            ownsDocument = false,
            initialPage = 0,
        )
        cases += AdapterCase(
            name = "PDF",
            adapter = pdfAdapter,
            format = ReaderFormat.PDF,
            bookId = 7L,
            initialLocator = ReaderLocator.Pdf(0),
            targetLocator = ReaderLocator.Pdf(1),
            query = "beta",
            close = { pdfAdapter.close() },
        )

        val textStore = MatrixStore()
        val textAdapter = FakeReaderAdapter(
            bookId = 11L,
            format = ReaderFormat.TEXT,
            initialLocator = ReaderLocator.Text(0),
            body = "alpha beta gamma",
            annotationStore = textStore,
        )
        cases += AdapterCase(
            name = "TXT",
            adapter = textAdapter,
            format = ReaderFormat.TEXT,
            bookId = 11L,
            initialLocator = ReaderLocator.Text(0),
            targetLocator = ReaderLocator.Text(1),
            query = "alpha",
            close = { textAdapter.close() },
        )

        val htmlStore = MatrixStore()
        val htmlAdapter = FakeReaderAdapter(
            bookId = 12L,
            format = ReaderFormat.HTML,
            initialLocator = ReaderLocator.Html("ch1.xhtml", "sec1"),
            body = "alpha beta gamma",
            annotationStore = htmlStore,
        )
        cases += AdapterCase(
            name = "HTML",
            adapter = htmlAdapter,
            format = ReaderFormat.HTML,
            bookId = 12L,
            initialLocator = ReaderLocator.Html("ch1.xhtml", "sec1"),
            targetLocator = ReaderLocator.Html("ch2.xhtml", "sec2"),
            query = "alpha",
            close = { htmlAdapter.close() },
        )

        return cases
    }

    private fun pdfPage(index: Int, text: String): PdfPageText = PdfPageText(
        pageIndex = index,
        pageWidthPoints = 612,
        pageHeightPoints = 792,
        rotationDegrees = 0,
        chars = emptyList(),
        text = text,
    )

    private data class AdapterCase(
        val name: String,
        val adapter: ReaderAdapter,
        val format: ReaderFormat,
        val bookId: Long,
        val initialLocator: ReaderLocator,
        val targetLocator: ReaderLocator,
        val query: String,
        val close: () -> Unit,
    ) {
        fun wrongLocator(): ReaderLocator = when (format) {
            ReaderFormat.EPUB, ReaderFormat.TEXT, ReaderFormat.HTML -> ReaderLocator.Pdf(0)
            ReaderFormat.PDF -> ReaderLocator.Text(0)
            else -> ReaderLocator.Scroll(0f)
        }
    }

    private class MatrixPdfSource(
        private val pages: List<PdfPageText>,
    ) : PdfDocumentTextSource {
        override val pageCount: Int get() = pages.size

        override fun loadPage(page: Int): PdfPageText = pages[page]

        override fun charAt(page: Int, xNorm: Float, yNorm: Float): PdfChar? = null

        override fun selectionRects(
            page: Int,
            startChar: Int,
            endChar: Int,
        ): List<PdfNormalizedRect> = emptyList()

        override fun close() = Unit
    }

    private class MatrixStore : AnnotationStore {
        private val added = ArrayList<ReaderAnnotation>()
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

    /** TXT/HTML 在 P1 没有真实现；这个 fake 只验证接口语义，不参与宿主 UI。 */
    private class FakeReaderAdapter(
        override val bookId: Long,
        override val format: ReaderFormat,
        initialLocator: ReaderLocator,
        private val body: String,
        private val annotationStore: AnnotationStore?,
    ) : ReaderAdapter {
        private var current = initialLocator
        private var closed = false

        override fun getLocator(): ReaderLocator {
            check(!closed) { "FakeReaderAdapter is closed" }
            return current
        }

        override fun goTo(locator: ReaderLocator): Boolean {
            if (closed || locator::class != current::class) return false
            current = locator
            return true
        }

        override fun getSelection(): ReaderSelection? = null

        override fun getTextModel(locator: ReaderLocator, maxChars: Int): ReaderTextModel? {
            if (closed || locator::class != current::class) return null
            val limit = max(0, maxChars)
            val text = if (body.length > limit) body.take(limit) else body
            return ReaderTextModel(locator, text, truncated = text.length < body.length)
        }

        override fun search(query: String, limit: Int): List<ReaderSearchHit> {
            if (closed || query.isBlank() || limit <= 0) return emptyList()
            if (!body.contains(query, ignoreCase = true)) return emptyList()
            return listOf(ReaderSearchHit(current, body.take(20)))
        }

        override fun applyAnnotation(annotation: ReaderAnnotation): ReaderAnnotation =
            annotationStore?.add(annotation) ?: annotation.copy(id = 0L)

        override fun removeAnnotation(annotationId: Long): Boolean =
            annotationStore?.remove(annotationId) ?: false

        override fun close() {
            closed = true
        }
    }

    private class MatrixMeasurer : EpubBlockMeasurer {
        override fun measure(block: EpubBlock, blockIndex: Int, config: EpubLayoutConfig): MeasuredBlock {
            val text = when (block) {
                is EpubBlock.Paragraph -> block.text
                is EpubBlock.Heading -> block.text
                is EpubBlock.ListItem -> block.text
                else -> ""
            }
            if (text.isEmpty()) return MeasuredBlock(blockIndex, block, heightPx = 0)
            val lines = MatrixLines(text.length, charsPerLine = 10, lineHeight = 10)
            return MeasuredBlock(
                blockIndex = blockIndex,
                block = block,
                heightPx = lines.lineCount * 10,
                lines = lines,
            )
        }
    }

    private class MatrixLines(
        private val textLength: Int,
        private val charsPerLine: Int,
        private val lineHeight: Int,
    ) : EpubCharLineMetrics {
        override val lineCount: Int = max(1, ceil(textLength.toFloat() / charsPerLine).toInt())
        override fun lineTop(line: Int): Int = line.coerceIn(0, lineCount) * lineHeight
        override fun lineBottom(line: Int): Int = (line.coerceIn(0, lineCount) + 1) * lineHeight
        override fun lineBaseline(line: Int): Int = lineBottom(line) - 2
        override fun lineStart(line: Int): Int = line.coerceIn(0, lineCount) * charsPerLine
        override fun lineEnd(line: Int): Int = minOf(textLength, lineStart(line) + charsPerLine)
    }
}
