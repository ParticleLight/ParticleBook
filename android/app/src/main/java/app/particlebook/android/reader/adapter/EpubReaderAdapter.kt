package app.particlebook.android.reader.adapter

import app.particlebook.android.reader.AnnotationStore
import app.particlebook.android.reader.ReaderAdapter
import app.particlebook.android.reader.ReaderAnnotation
import app.particlebook.android.reader.ReaderFormat
import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.reader.ReaderSearchHit
import app.particlebook.android.reader.ReaderSelection
import app.particlebook.android.reader.ReaderTextModel
import app.particlebook.android.reader.epub.EpubReaderController
import app.particlebook.android.reader.epub.layout.EpubLayoutConfig
import kotlin.math.max

/**
 * EPUB 的 [ReaderAdapter] 实现：把 P1 冻结的阅读器接口委托给 [EpubReaderController]。
 *
 * - 位置与文本模型都基于 controller 的章/块/字符 locator；
 * - [getTextModel] 与 [goTo] 可能切换当前章节，这是 ReaderAdapter 契约允许的阅读位置变更；
 * - [search] 只读，不改变当前章节/页；
 * - 标注持久化完全委托 [AnnotationStore]；P1 的 UI 可先不传 store，用返回 id=0 的模型做本地预览。
 *
 * 线程约定与 controller 一致：单阅读 worker 使用；本类不加锁。
 */
class EpubReaderAdapter(
    controller: EpubReaderController,
    private val layoutConfig: EpubLayoutConfig,
    private val annotationStore: AnnotationStore? = null,
    private val ownsController: Boolean = false,
) : ReaderAdapter {

    private var controller: EpubReaderController? = controller

    override val bookId: Long = controller.bookId
    override val format: ReaderFormat = ReaderFormat.EPUB

    private var lastSelection: ReaderSelection? = null
    private var closed = false

    override fun getLocator(): ReaderLocator {
        val current = requireController()
        return current.locatorForPage(current.currentPageIndex)
    }

    /**
     * 跳转到 EPUB locator。
     *
     * 非法 href、非 EPUB locator、布局失败或 closed 状态统一返回 false；
     * 失败时 controller 可能已处于部分跳转状态，调用方可重新 goTo 恢复的 locator。
     */
    override fun goTo(locator: ReaderLocator): Boolean {
        val current = controller ?: return false
        if (locator !is ReaderLocator.Epub) return false
        return runCatching {
            current.openChapterByHref(locator.chapterHref)
            current.layoutChapter(layoutConfig)
            val page = current.pageAt(locator) ?: return@runCatching false
            current.setPage(page)
            true
        }.getOrDefault(false)
    }

    override fun getSelection(): ReaderSelection? = lastSelection

    /**
     * 用页内 UTF-16 偏移建立选区；page 必须是当前章已布局的页。
     *
     * 选区 locator 是起点，length 为选中文本长度（UTF-16）；失败时清空最近选区并返回 null。
     */
    fun select(pageIndex: Int, startChar: Int, endChar: Int): ReaderSelection? {
        val current = controller
        if (current == null) {
            lastSelection = null
            return null
        }
        val epubSelection = runCatching {
            current.selectionRange(pageIndex, startChar, endChar)
        }.getOrNull()
        if (epubSelection == null) {
            lastSelection = null
            return null
        }
        val start = epubSelection.start.copy(length = epubSelection.text.length)
        val selection = ReaderSelection(
            locator = start,
            text = epubSelection.text,
        )
        lastSelection = selection
        return selection
    }

    /**
     * 当前章：pageAt + pageText；非当前章：先 openChapterByHref + layoutChapter。
     * 失败、非 EPUB locator 或已关闭返回 null。
     */
    override fun getTextModel(locator: ReaderLocator, maxChars: Int): ReaderTextModel? {
        val current = controller ?: return null
        if (locator !is ReaderLocator.Epub) return null
        return runCatching {
            val chapterIndex = current.chapterIndexForHref(locator.chapterHref)
                ?: return@runCatching null
            if (chapterIndex != current.currentChapterIndex) {
                current.openChapterByHref(locator.chapterHref)
            }
            // 同一个 controller 可能被别的 config 布局过；这里始终按适配器 config 取页。
            current.layoutChapter(layoutConfig)
            val page = current.pageAt(locator) ?: return@runCatching null
            val fullText = current.pageText(page)
            val limit = max(0, maxChars)
            val text = if (fullText.length > limit) fullText.take(limit) else fullText
            ReaderTextModel(
                locator = locator,
                text = text,
                truncated = text.length < fullText.length,
            )
        }.getOrNull()
    }

    override fun search(query: String, limit: Int): List<ReaderSearchHit> =
        controller?.search(query, limit).orEmpty()

    override fun applyAnnotation(annotation: ReaderAnnotation): ReaderAnnotation {
        val store = annotationStore ?: return annotation.copy(id = 0)
        return store.add(annotation)
    }

    override fun removeAnnotation(annotationId: Long): Boolean =
        annotationStore?.remove(annotationId) ?: false

    /** 默认只释放适配器引用；[ownsController] 为 true 时才关闭底层 controller。 */
    override fun close() {
        if (closed) return
        closed = true
        lastSelection = null
        val owned = controller
        controller = null
        if (ownsController) owned?.close()
    }

    private fun requireController(): EpubReaderController =
        controller ?: error("EpubReaderAdapter is closed")
}
