package app.particlebook.android.reader.pdf

import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.reader.ReaderRect
import app.particlebook.android.reader.ReaderSelection
import java.io.Closeable

/**
 * PDF 选区所需的文本层最小能力。
 *
 * 单独抽成接口是为了让选区逻辑和 PdfReaderAdapter 可以用纯 JVM fake 测试，
 * 不把 PDFium / Android PdfRenderer 拖进单测。
 */
interface PdfDocumentTextSource : Closeable {
    val pageCount: Int

    fun loadPage(page: Int): PdfPageText

    fun charAt(page: Int, xNorm: Float, yNorm: Float): PdfChar?

    fun selectionRects(page: Int, startChar: Int, endChar: Int): List<PdfNormalizedRect>
}

/**
 * 一次 PDF 选择结果。startChar 是 PdfChar.index 的闭区间起点，endChar 是开区间终点；
 * 与 PdfTextLayerController.selectionRects 的约定一致。
 */
data class PdfSelection(
    val page: Int,
    val startChar: Int,
    val endChar: Int,
    val text: String,
    val rects: List<PdfNormalizedRect>,
) {
    val length: Int get() = (endChar - startChar).coerceAtLeast(0)

    val hasText: Boolean get() = text.isNotBlank()

    fun readerRects(): List<ReaderRect> = rects.mapNotNull { it.toReaderRect() }
}

/**
 * PDF 选区状态机。只做纯逻辑：命中、同页扩展、文本拼接、矩形和 locator 转换。
 *
 * 线程契约：调用方在 IO 线程调用；内部不持有 native 资源。
 */
class PdfSelectionController(
    private val source: PdfDocumentTextSource,
) {
    private var anchorPage: Int = -1
    private var anchorChar: Int = -1
    private var current: PdfSelection? = null

    val selection: PdfSelection? get() = current

    /**
     * 长按/点击开始选区：命中一个字符，初始选一个字符（start=index, end=index+1）。
     * 返回 null 表示该点没有可选择的文本层字符。
     */
    fun begin(page: Int, xNorm: Float, yNorm: Float): PdfSelection? {
        current = null
        anchorPage = -1
        anchorChar = -1
        if (page < 0 || page >= pageCount()) return null
        val hit = runCatching { source.charAt(page, xNorm, yNorm) }.getOrNull() ?: return null
        anchorPage = page
        anchorChar = hit.index
        return update(page, hit.index)
    }

    /**
     * 拖动扩展。只接受与 anchor 同页的点；反向拖动会自动把选区归一化为较小/较大索引。
     * 命中空隙时退化为距离最近的有矩形字符，避免手指落在空白处选区直接断掉。
     */
    fun extend(page: Int, xNorm: Float, yNorm: Float): PdfSelection? {
        val active = current ?: return null
        if (page != anchorPage) return active
        val hit = runCatching { source.charAt(page, xNorm, yNorm) }.getOrNull()
            ?: nearestChar(page, xNorm, yNorm)
            ?: return active
        return update(page, hit.index)
    }

    /** 取 [startChar, endChar) 内的字符文本；保留空白，按 PdfChar.index 排序。 */
    fun text(page: Int, startChar: Int, endChar: Int): String {
        if (endChar <= startChar) return ""
        val pageText = runCatching { source.loadPage(page) }.getOrNull() ?: return ""
        return pageText.chars
            .asSequence()
            .filter { it.index in startChar until endChar }
            .sortedBy { it.index }
            .joinToString(separator = "") { it.text }
    }

    /** 把合并后的选区矩形转成 locator；x/y 取包围盒左上角，rect 取包围盒。 */
    fun toLocator(page: Int, rect: PdfNormalizedRect?): ReaderLocator.Pdf =
        toLocator(page, listOfNotNull(rect))

    /** 多个矩形按包围盒合并；没有有效矩形时退化成单点 locator。 */
    fun toLocator(page: Int, rects: List<PdfNormalizedRect>): ReaderLocator.Pdf {
        val box = boundingBox(rects)
        val safePage = page.coerceAtLeast(0)
        if (box == null) return ReaderLocator.Pdf(safePage)
        return ReaderLocator.Pdf(
            page = safePage,
            x = box.left.coerceIn(0f, 1f),
            y = box.top.coerceIn(0f, 1f),
            rect = box.toReaderRect(),
        )
    }

    /** 当前选区转成 ReaderSelection；没有选区返回 null。 */
    fun getSelection(): ReaderSelection? {
        val active = current ?: return null
        return ReaderSelection(
            locator = toLocator(active.page, active.rects),
            text = active.text,
            rects = active.readerRects(),
        )
    }

    fun clear() {
        anchorPage = -1
        anchorChar = -1
        current = null
    }

    private fun pageCount(): Int = runCatching { source.pageCount }.getOrDefault(0)

    private fun update(page: Int, cursorChar: Int): PdfSelection? {
        val pageText = runCatching { source.loadPage(page) }.getOrNull() ?: return null
        if (pageText.chars.isEmpty()) {
            current = null
            return null
        }
        val minIndex = pageText.chars.minOf { it.index }
        val maxIndex = pageText.chars.maxOf { it.index }
        val anchor = anchorChar.coerceIn(minIndex, maxIndex)
        val cursor = cursorChar.coerceIn(minIndex, maxIndex)
        val start = minOf(anchor, cursor)
        val end = maxOf(anchor, cursor) + 1
        val rects = runCatching { source.selectionRects(page, start, end) }
            .getOrDefault(emptyList())
        val selection = PdfSelection(
            page = page,
            startChar = start,
            endChar = end,
            text = text(page, start, end),
            rects = rects,
        )
        current = selection
        return selection
    }

    private fun nearestChar(page: Int, xNorm: Float, yNorm: Float): PdfChar? {
        val pageText = runCatching { source.loadPage(page) }.getOrNull() ?: return null
        return pageText.chars
            .asSequence()
            .filter { it.rect != null }
            .minByOrNull { distanceSquared(it.rect!!, xNorm, yNorm) }
    }

    private fun distanceSquared(rect: PdfNormalizedRect, x: Float, y: Float): Float {
        val dx = when {
            x < rect.left -> rect.left - x
            x > rect.right -> x - rect.right
            else -> 0f
        }
        val dy = when {
            y < rect.top -> rect.top - y
            y > rect.bottom -> y - rect.bottom
            else -> 0f
        }
        return dx * dx + dy * dy
    }

    private fun boundingBox(rects: List<PdfNormalizedRect>): PdfNormalizedRect? {
        var left = 1f
        var top = 1f
        var right = 0f
        var bottom = 0f
        var found = false
        for (rect in rects) {
            if (!rect.isValid) continue
            found = true
            left = minOf(left, rect.left)
            top = minOf(top, rect.top)
            right = maxOf(right, rect.right)
            bottom = maxOf(bottom, rect.bottom)
        }
        if (!found || right <= left || bottom <= top) return null
        return PdfNormalizedRect(
            left = left.coerceIn(0f, 1f),
            top = top.coerceIn(0f, 1f),
            width = (right - left).coerceIn(0f, 1f - left.coerceIn(0f, 1f)),
            height = (bottom - top).coerceIn(0f, 1f - top.coerceIn(0f, 1f)),
        )
    }
}

private fun PdfNormalizedRect.toReaderRect(): ReaderRect? {
    val left = this.left.coerceIn(0f, 1f)
    val top = this.top.coerceIn(0f, 1f)
    val right = this.right.coerceIn(0f, 1f)
    val bottom = this.bottom.coerceIn(0f, 1f)
    return ReaderRect.validated(left, top, right, bottom)
}
