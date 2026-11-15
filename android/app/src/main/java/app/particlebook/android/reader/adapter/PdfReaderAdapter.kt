package app.particlebook.android.reader.adapter

import app.particlebook.android.reader.AnnotationStore
import app.particlebook.android.reader.ReaderAdapter
import app.particlebook.android.reader.ReaderAnnotation
import app.particlebook.android.reader.ReaderFormat
import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.reader.ReaderSearchHit
import app.particlebook.android.reader.ReaderSelection
import app.particlebook.android.reader.ReaderTextModel
import app.particlebook.android.reader.pdf.PdfDocumentTextSource
import app.particlebook.android.reader.pdf.PdfSelectionController
import app.particlebook.android.reader.pdf.PdfTextLayerController
import app.particlebook.android.reader.pdf.PdfTextLayerDocumentSource

/**
 * PDF 的 ReaderAdapter 实现：位置、文本模型、搜索、标注、选区查询。
 *
 * 构造后不拥有调用方的 PdfTextLayerController（默认 ownsController=false）；阅读屏退出时
 * 由创建者负责 close，避免适配器和 UI 双重关闭。
 */
class PdfReaderAdapter internal constructor(
    private val document: PdfDocumentTextSource,
    override val bookId: Long,
    private val annotationStore: AnnotationStore?,
    private val ownsDocument: Boolean,
    initialPage: Int,
) : ReaderAdapter {

    /** 便捷构造：阅读屏已有文本层控制器时直接包一层。 */
    constructor(
        controller: PdfTextLayerController,
        annotationStore: AnnotationStore? = null,
        ownsController: Boolean = false,
        bookId: Long = 0,
        initialPage: Int = 0,
    ) : this(
        document = PdfTextLayerDocumentSource(controller),
        bookId = bookId,
        annotationStore = annotationStore,
        ownsDocument = ownsController,
        initialPage = initialPage,
    )

    override val format: ReaderFormat = ReaderFormat.PDF

    private val selectionController = PdfSelectionController(document)
    private var closed = false

    val pageCount: Int get() = runCatching { document.pageCount }.getOrDefault(0)

    var currentPage: Int = initialPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        private set

    override fun getLocator(): ReaderLocator = ReaderLocator.Pdf(currentPage)

    override fun goTo(locator: ReaderLocator): Boolean {
        if (closed || locator !is ReaderLocator.Pdf) return false
        val count = pageCount
        if (count <= 0) return false
        val target = locator.page.coerceIn(0, count - 1)
        return runCatching {
            document.loadPage(target)
            currentPage = target
            selectionController.clear()
            true
        }.getOrDefault(false)
    }

    override fun getSelection(): ReaderSelection? = selectionController.getSelection()

    override fun getTextModel(locator: ReaderLocator, maxChars: Int): ReaderTextModel? {
        if (closed) return null
        val pdf = locator as? ReaderLocator.Pdf ?: return null
        val count = pageCount
        if (count <= 0) return null
        val page = pdf.page.coerceIn(0, count - 1)
        val text = runCatching { document.loadPage(page).text }.getOrNull() ?: return null
        val limit = maxChars.coerceAtLeast(0)
        val visible = if (text.length > limit) text.take(limit) else text
        return ReaderTextModel(
            locator = ReaderLocator.Pdf(page),
            text = visible,
            truncated = text.length > limit,
        )
    }

    override fun search(query: String, limit: Int): List<ReaderSearchHit> {
        if (closed || query.isBlank() || limit <= 0) return emptyList()
        val count = pageCount
        if (count <= 0) return emptyList()
        val hits = ArrayList<ReaderSearchHit>(minOf(limit, count))
        for (page in 0 until count) {
            if (hits.size >= limit) break
            val text = runCatching { document.loadPage(page).text }.getOrNull() ?: continue
            val index = text.indexOf(query, ignoreCase = true)
            if (index < 0) continue
            hits += ReaderSearchHit(
                locator = ReaderLocator.Pdf(page),
                snippet = snippetAround(text, index, query.length),
            )
        }
        return hits
    }

    override fun applyAnnotation(annotation: ReaderAnnotation): ReaderAnnotation {
        val store = annotationStore
            ?: throw IllegalStateException("PdfReaderAdapter requires AnnotationStore")
        return store.add(annotation.copy(bookId = bookId))
    }

    override fun removeAnnotation(annotationId: Long): Boolean =
        annotationStore?.remove(annotationId) ?: false

    /** 从当前页开始一次性选一个字符；供 UI 长按调用。 */
    fun beginSelection(page: Int, xNorm: Float, yNorm: Float): ReaderSelection? {
        if (closed) return null
        val target = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        selectionController.begin(target, xNorm, yNorm) ?: return null
        currentPage = target
        return selectionController.getSelection()
    }

    /** 拖动扩展；只允许同一页，返回最新 ReaderSelection。 */
    fun extendSelection(page: Int, xNorm: Float, yNorm: Float): ReaderSelection? {
        if (closed) return null
        selectionController.extend(page, xNorm, yNorm)
        return selectionController.getSelection()
    }

    fun clearSelection() {
        selectionController.clear()
    }

    override fun close() {
        if (closed) return
        closed = true
        selectionController.clear()
        if (ownsDocument) runCatching { document.close() }
    }

    private fun snippetAround(text: String, matchStart: Int, matchLength: Int): String {
        val from = (matchStart - SNIPPET_CONTEXT).coerceAtLeast(0)
        val to = (matchStart + matchLength + SNIPPET_CONTEXT).coerceAtMost(text.length)
        return text.substring(from, to)
            .replace(WHITESPACE, " ")
            .trim()
    }

    private companion object {
        const val SNIPPET_CONTEXT: Int = 40
        val WHITESPACE: Regex = Regex("\\s+")
    }
}
