package app.particlebook.android.reader.pdf

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import kotlin.math.abs

/** 文本层窗口预加载结果；失败的页会保留在 failures 中，不阻塞其他页。 */
data class PdfTextLayerWindow(
    val window: IntRange,
    val pages: List<PdfPageText>,
    val failures: Map<Int, String> = emptyMap(),
) {
    val allLoaded: Boolean get() = failures.isEmpty()
}

/** 文本层控制器打开结果；失败分类复用 P0 的 PdfTextFailureReason。 */
sealed interface PdfTextLayerOpenResult {
    data class Success(val controller: PdfTextLayerController) : PdfTextLayerOpenResult
    data class Failure(
        val reason: PdfTextFailureReason,
        val message: String? = null,
        val cause: Throwable? = null,
    ) : PdfTextLayerOpenResult
}

/**
 * PDF 文本层控制器：把 P0 的 PdfTextSource 包成 ReaderScreen 可用的页窗口。
 *
 * 坐标契约：对外全部使用 PdfNormalizedRect，左上原点、0..1；P0 的
 * PdfPageGeometry 已经把 PDF 点、CropBox 和页面旋转处理完，本类不再做
 * 第二套坐标变换，避免两处结果不一致。
 *
 * 缓存契约：控制器缓存最近使用的 PdfPageText，默认最多 5 页 / 8 MiB。
 * preloadVisible 会把可见窗口之外的页从控制器和 PdfTextSource 中都释放，
 * 因此正常情况下 PDFium 只为当前页 ±1 保留 native 页句柄。
 *
 * 线程契约：所有公开方法内部串行；PDFium 调用本身也串行。page() 不应在
 * 主线程调用，调用方应放到 Dispatchers.IO。
 */
class PdfTextLayerController internal constructor(
    private val source: PdfTextSource,
    private val rendererHandle: AutoCloseable?,
    val pageCount: Int,
    maxPages: Int = DEFAULT_MAX_PAGES,
    maxBytes: Long = DEFAULT_MAX_BYTES,
) : Closeable {

    private val lock = Any()
    private val cache = PdfTextPageCache(maxPages, maxBytes)
    private var visibleWindow: IntRange? = null
    private var closed = false

    /**
     * 供 ReaderScreen 渲染层复用。PdfRenderer 与 PDFium 各自持有独立的 fd，
     * 文本层不会改变渲染器当前页，也不会共享 fd 偏移。
     */
    val renderer: PdfRenderer? get() = rendererHandle as? PdfRenderer

    /**
     * 预加载当前页 ±radius，并释放窗口外页面。返回已加载页和失败页。
     * radius=0 时只保留当前页；半径自动裁剪到文档边界。
     */
    fun preloadVisible(page: Int, radius: Int = 1): PdfTextLayerWindow = synchronized(lock) {
        checkOpen()
        requirePage(page)
        val safeRadius = radius.coerceAtLeast(0)
        val start = (page - safeRadius).coerceAtLeast(0)
        val end = (page + safeRadius).coerceAtMost(pageCount - 1)
        val window = start..end
        visibleWindow = window
        releaseOutsideLocked(window)

        val loaded = ArrayList<PdfPageText>(end - start + 1)
        val failures = LinkedHashMap<Int, String>()
        for (index in start..end) {
            try {
                loaded.add(loadPageLocked(index))
            } catch (t: Exception) {
                failures[index] = describeFailure(t)
            }
        }
        PdfTextLayerWindow(window, loaded, failures)
    }

    /** 加载一页并更新 LRU；页不在当前可见窗口时仍可被后续 load 淘汰。 */
    fun loadPage(page: Int): PdfPageText = synchronized(lock) {
        checkOpen()
        requirePage(page)
        loadPageLocked(page)
    }

    /** 只保留给定窗口内的页，返回被释放的页号。窗口会裁剪到文档范围。 */
    fun releaseOutside(window: IntRange): List<Int> = synchronized(lock) {
        checkOpen()
        if (pageCount == 0) return emptyList()
        val start = window.first.coerceIn(0, pageCount - 1)
        val end = window.last.coerceIn(0, pageCount - 1)
        if (start > end) {
            visibleWindow = null
            return emptyList()
        }
        val clamped = start..end
        visibleWindow = clamped
        releaseOutsideLocked(clamped)
    }

    /**
     * 命中测试。xNorm/yNorm 是左上原点的页面归一化坐标，范围 0..1。
     * 边界使用半个像素级 epsilon，因此点在字符右/下边缘也命中；若相邻
     * 字符框共享边缘，优先返回面积更小、索引更小的字符。
     */
    fun charAt(page: Int, xNorm: Float, yNorm: Float): PdfChar? = synchronized(lock) {
        checkOpen()
        requirePage(page)
        if (!xNorm.isFinite() || !yNorm.isFinite()) return null
        if (xNorm < 0f || xNorm > 1f || yNorm < 0f || yNorm > 1f) return null
        val text = loadPageLocked(page)
        PdfTextHitTest.hit(text.chars, xNorm, yNorm)
    }

    /**
     * 选区矩形。start/end 是 PdfChar.index 的半开区间 [start,end)，
     * 不是 chars 列表下标；空白字符没有 rect 会被跳过。返回按行合并后的
     * 归一化矩形，供选区高亮和翻译浮层使用。
     */
    fun selectionRects(page: Int, start: Int, end: Int): List<PdfNormalizedRect> = synchronized(lock) {
        checkOpen()
        requirePage(page)
        if (end <= start || start < 0) return emptyList()
        val text = loadPageLocked(page)
        PdfSelectionGeometry.rects(text.chars, start, end)
    }

    /** 当前控制器缓存页数，供单测和诊断使用。 */
    internal fun cachedPageCount(): Int = synchronized(lock) { cache.size }

    internal fun cachedPageIndexes(): List<Int> = synchronized(lock) { cache.indexes() }

    internal fun visibleWindowForTest(): IntRange? = synchronized(lock) { visibleWindow }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            cache.clear()
            try {
                source.close()
            } catch (_: Throwable) {
            }
            try {
                rendererHandle?.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun loadPageLocked(page: Int): PdfPageText {
        cache.get(page)?.let { return it }
        val text = source.page(page)
        val protected = visibleWindow?.toSet() ?: emptySet()
        val evicted = cache.put(text, protected)
        for (index in evicted) source.releasePage(index)
        return text
    }

    private fun releaseOutsideLocked(window: IntRange): List<Int> {
        val removed = cache.retainOnly(window)
        for (index in removed) source.releasePage(index)
        return removed
    }

    private fun describeFailure(t: Exception): String {
        val message = t.message ?: t.javaClass.simpleName
        val cause = t.cause?.message
        return if (cause.isNullOrBlank() || message.contains(cause)) {
            message
        } else {
            message + " (" + cause + ")"
        }
    }

    private fun checkOpen() {
        check(!closed) { "PdfTextLayerController is closed" }
    }

    private fun requirePage(page: Int) {
        require(page in 0 until pageCount) {
            "page index out of range: " + page + ", pageCount=" + pageCount
        }
    }

    companion object {
        const val DEFAULT_MAX_PAGES: Int = 5
        const val DEFAULT_MAX_BYTES: Long = 8L * 1024L * 1024L

        /**
         * 双 fd 打开：PdfRenderer 持有第一个只读 fd，PDFium 持有第二个只读 fd。
         * 两个组件都不需要 seek 到对方的文件偏移；任一打开失败都会关闭已经
         * 创建的句柄，不泄漏 fd。
         *
         * password 只传给 PDFium。Android PdfRenderer 没有密码参数，受密码
         * 保护的 PDF 目前会在这里打开失败，P1 真机阶段需要单独处理。
         */
        fun open(
            context: Context,
            file: File,
            password: String? = null,
            maxPages: Int = DEFAULT_MAX_PAGES,
            maxBytes: Long = DEFAULT_MAX_BYTES,
        ): PdfTextLayerOpenResult {
            val rendererDescriptor = try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (t: Throwable) {
                return PdfTextLayerOpenResult.Failure(
                    PdfTextFailureReason.OPEN_FAILED,
                    t.message,
                    t,
                )
            }

            val renderer = try {
                PdfRenderer(rendererDescriptor)
            } catch (t: Throwable) {
                closeQuietly(rendererDescriptor)
                return PdfTextLayerOpenResult.Failure(
                    classifyPdfOpenFailure(t),
                    t.message,
                    t,
                )
            }

            val rendererPageCount = try {
                renderer.pageCount
            } catch (t: Throwable) {
                closeQuietly(renderer)
                return PdfTextLayerOpenResult.Failure(
                    PdfTextFailureReason.PAGE_COUNT_INVALID,
                    t.message,
                    t,
                )
            }

            val textDescriptor = try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (t: Throwable) {
                closeQuietly(renderer)
                return PdfTextLayerOpenResult.Failure(
                    PdfTextFailureReason.OPEN_FAILED,
                    t.message,
                    t,
                )
            }

            val textResult = PdfiumAndroidTextSource.openDescriptor(
                context = context,
                descriptor = textDescriptor,
                password = password,
                cacheLimit = maxPages.coerceAtLeast(1),
                closeDescriptor = true,
            )
            val source = when (textResult) {
                is PdfTextOpenResult.Success -> textResult.source
                is PdfTextOpenResult.Failure -> {
                    closeQuietly(renderer)
                    return PdfTextLayerOpenResult.Failure(
                        textResult.reason,
                        textResult.message,
                        textResult.cause,
                    )
                }
            }

            val textPageCount = try {
                source.pageCount
            } catch (t: Throwable) {
                closeQuietly(source)
                closeQuietly(renderer)
                return PdfTextLayerOpenResult.Failure(
                    PdfTextFailureReason.PAGE_COUNT_INVALID,
                    t.message,
                    t,
                )
            }

            if (textPageCount != rendererPageCount) {
                closeQuietly(source)
                closeQuietly(renderer)
                return PdfTextLayerOpenResult.Failure(
                    PdfTextFailureReason.PAGE_COUNT_INVALID,
                    "page count mismatch: renderer=" + rendererPageCount + ", pdfium=" + textPageCount,
                )
            }

            return PdfTextLayerOpenResult.Success(
                PdfTextLayerController(
                    source = source,
                    rendererHandle = renderer,
                    pageCount = textPageCount,
                    maxPages = maxPages,
                    maxBytes = maxBytes,
                )
            )
        }

        private fun closeQuietly(closeable: AutoCloseable?) {
            try {
                closeable?.close()
            } catch (_: Throwable) {
            }
        }
    }
}

/** 控制器页缓存；页数和字节数双上限，按 LRU 淘汰。 */
internal class PdfTextPageCache(maxPages: Int, maxBytes: Long) {
    private val limitPages: Int = maxPages.coerceAtLeast(1)
    private val limitBytes: Long = maxBytes.coerceAtLeast(1L)
    private val pages = LinkedHashMap<Int, PdfPageText>(limitPages, 0.75f, true)
    private var bytes: Long = 0L

    val size: Int get() = pages.size
    val byteSize: Long get() = bytes

    fun get(page: Int): PdfPageText? = pages[page]

    /** 写入一页并返回被淘汰的页号；受保护页不会被淘汰。 */
    fun put(page: PdfPageText, protected: Set<Int> = emptySet()): List<Int> {
        pages.remove(page.pageIndex)?.let { bytes -= estimatePdfPageBytes(it) }
        pages[page.pageIndex] = page
        bytes += estimatePdfPageBytes(page)
        return trim(protected)
    }

    /** 只保留窗口内的页，返回被移除的页号。 */
    fun retainOnly(window: IntRange): List<Int> {
        val removed = ArrayList<Int>()
        val iterator = pages.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in window) {
                bytes -= estimatePdfPageBytes(entry.value)
                iterator.remove()
                removed.add(entry.key)
            }
        }
        return removed
    }

    fun indexes(): List<Int> = pages.keys.toList()

    fun clear() {
        pages.clear()
        bytes = 0L
    }

    private fun trim(protected: Set<Int>): List<Int> {
        val removed = ArrayList<Int>()
        while (pages.size > limitPages || bytes > limitBytes) {
            val candidate = pages.keys.firstOrNull { it !in protected } ?: break
            val old = pages.remove(candidate) ?: break
            bytes -= estimatePdfPageBytes(old)
            removed.add(candidate)
        }
        return removed
    }
}

/** 估算一页文本层占用的堆内存；用于控制器字节预算。 */
internal fun estimatePdfPageBytes(page: PdfPageText): Long {
    var total = 128L + page.text.length.toLong() * 2L
    for (char in page.chars) {
        total += 48L + char.text.length.toLong() * 2L
        if (char.rect != null) total += 32L
    }
    return total
}

/** 归一化坐标命中测试。 */
internal object PdfTextHitTest {
    private const val EDGE_EPSILON = 0.0005f

    fun hit(chars: List<PdfChar>, x: Float, y: Float): PdfChar? {
        var best: PdfChar? = null
        var bestArea = Float.MAX_VALUE
        for (char in chars) {
            val rect = char.rect ?: continue
            if (x < rect.left - EDGE_EPSILON || x > rect.right + EDGE_EPSILON) continue
            if (y < rect.top - EDGE_EPSILON || y > rect.bottom + EDGE_EPSILON) continue
            val area = rect.width * rect.height
            val better = best == null || area < bestArea - 1e-6f ||
                (abs(area - bestArea) <= 1e-6f && char.index < best.index)
            if (better) {
                best = char
                bestArea = area
            }
        }
        return best
    }
}

/** 选区矩形合并：先按字符索引分成行，再把同一行内过宽的间隙拆开。 */
internal object PdfSelectionGeometry {
    fun rects(chars: List<PdfChar>, start: Int, end: Int): List<PdfNormalizedRect> {
        if (end <= start) return emptyList()
        val selected = chars
            .filter { it.index >= start && it.index < end && it.rect != null }
            .sortedBy { it.index }
        if (selected.isEmpty()) return emptyList()

        val lines = ArrayList<MutableList<PdfNormalizedRect>>()
        for (char in selected) {
            val rect = char.rect ?: continue
            val line = lines.lastOrNull()
            val anchor = line?.lastOrNull()
            if (line != null && anchor != null &&
                verticalOverlap(anchor, rect) >= 0.5f * minOf(anchor.height, rect.height)
            ) {
                line.add(rect)
            } else {
                lines.add(mutableListOf(rect))
            }
        }

        val result = ArrayList<PdfNormalizedRect>()
        for (line in lines) result.addAll(splitWideGaps(line))
        return result
    }

    private fun splitWideGaps(line: List<PdfNormalizedRect>): List<PdfNormalizedRect> {
        if (line.isEmpty()) return emptyList()
        val sorted = line.sortedBy { it.left }
        val result = ArrayList<PdfNormalizedRect>()
        var current = sorted[0]
        for (index in 1 until sorted.size) {
            val next = sorted[index]
            val gap = next.left - current.right
            val maxGap = maxOf(0.02f, 1.5f * maxOf(current.height, next.height))
            if (gap <= maxGap) {
                current = union(current, next)
            } else {
                result.add(current)
                current = next
            }
        }
        result.add(current)
        return result
    }

    private fun verticalOverlap(a: PdfNormalizedRect, b: PdfNormalizedRect): Float {
        val top = maxOf(a.top, b.top)
        val bottom = minOf(a.bottom, b.bottom)
        return (bottom - top).coerceAtLeast(0f)
    }

    private fun union(a: PdfNormalizedRect, b: PdfNormalizedRect): PdfNormalizedRect {
        val left = minOf(a.left, b.left)
        val top = minOf(a.top, b.top)
        val right = maxOf(a.right, b.right)
        val bottom = maxOf(a.bottom, b.bottom)
        return PdfNormalizedRect(left, top, right - left, bottom - top)
    }
}
