package app.particlebook.android.reader.pdf

import java.io.Closeable

/** PDFium 文本层打开的失败分类；UI 可据此选择提示文案。 */
enum class PdfTextFailureReason {
    OPEN_FAILED,
    ENCRYPTED,
    PAGE_COUNT_INVALID,
}

sealed interface PdfTextOpenResult {
    data class Success(val source: PdfTextSource) : PdfTextOpenResult
    data class Failure(
        val reason: PdfTextFailureReason,
        val message: String? = null,
        val cause: Throwable? = null,
    ) : PdfTextOpenResult
}

class PdfTextSourceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 根据异常信息把打开失败分为加密或一般失败；Android adapter 也复用。 */
fun classifyPdfOpenFailure(t: Throwable): PdfTextFailureReason {
    val message = t.message.orEmpty().lowercase()
    val name = t.javaClass.simpleName.orEmpty().lowercase()
    return if ("password" in message || "password" in name ||
        "encrypted" in message || "encrypted" in name
    ) {
        PdfTextFailureReason.ENCRYPTED
    } else {
        PdfTextFailureReason.OPEN_FAILED
    }
}

/**
 * 文档句柄：只暴露文本层需要的最小能力，便于纯 JVM 单测。
 * 实现方负责 close 的顺序（先关页、再关文档、最后关文件描述符）。
 */
interface PdfTextDocumentHandle : Closeable {
    val pageCount: Int
    fun openPage(pageIndex: Int): PdfTextPageHandle
}

interface PdfTextPageHandle : Closeable {
    val widthPoints: Int
    val heightPoints: Int

    /** PDF 用户空间中的裁剪框；缺失时返回 null。 */
    val cropBox: PdfBox?

    /** 实际旋转角度，0/90/180/270。 */
    val rotationDegrees: Int

    fun openTextPage(): PdfTextTextHandle
}

interface PdfTextTextHandle : Closeable {
    val charCount: Int
    fun charAt(index: Int): Char
    fun charBox(index: Int): PdfBox?
    fun text(): String?
}

/**
 * PDF 文本层核心，不依赖 Android。
 *
 * 缓存策略：只缓存可见页附近由调用方请求的页，默认上限 3 页；所有 PDFium
 * 调用在同一个锁内串行，避免 PdfiumAndroid 全局锁之外的竞态。close 幂等，
 * 会关闭缓存页和文档句柄（Android adapter 会继续关闭 ParcelFileDescriptor）。
 */
class PdfTextSource private constructor(
    private val document: PdfTextDocumentHandle,
    cacheLimit: Int,
) : Closeable {

    private val lock = Any()
    private val cacheLimit: Int = cacheLimit.coerceAtLeast(1)
    private val cache = LinkedHashMap<Int, PdfTextPageHandle>(this.cacheLimit, 0.75f, true)
    private var closed = false

    val pageCount: Int
        get() = synchronized(lock) {
            check(!closed) { "PdfTextSource is closed" }
            document.pageCount
        }

    /**
     * 读取一页。调用方应保持当前页和前后各一页常驻，本类最多保留 cacheLimit 页。
     * 返回的 PdfPageText 不持有任何 native 句柄。
     */
    fun page(pageIndex: Int): PdfPageText = synchronized(lock) {
        check(!closed) { "PdfTextSource is closed" }
        val count = document.pageCount
        require(pageIndex in 0 until count) { "page index out of range: " + pageIndex + ", pageCount=" + count }
        val page = cache[pageIndex] ?: openAndCache(pageIndex)
        readPage(pageIndex, page)
    }

    private fun openAndCache(pageIndex: Int): PdfTextPageHandle {
        val page = try {
            document.openPage(pageIndex)
        } catch (t: Throwable) {
            throw PdfTextSourceException("failed to open page " + pageIndex, t)
        }
        cache[pageIndex] = page
        trimCache()
        return page
    }

    private fun trimCache() {
        while (cache.size > cacheLimit) {
            val iterator = cache.entries.iterator()
            if (!iterator.hasNext()) return
            val eldest = iterator.next()
            iterator.remove()
            closeQuietly(eldest.value)
        }
    }

    private fun readPage(pageIndex: Int, page: PdfTextPageHandle): PdfPageText {
        val textPage = try {
            page.openTextPage()
        } catch (t: Throwable) {
            evict(pageIndex, page)
            throw PdfTextSourceException("failed to open text page " + pageIndex, t)
        }
        var failure: Throwable? = null
        try {
            val geometry = PdfPageGeometry(
                pageWidthPoints = page.widthPoints.toFloat(),
                pageHeightPoints = page.heightPoints.toFloat(),
                cropBox = page.cropBox,
                rotationDegrees = page.rotationDegrees,
            )
            val count = textPage.charCount.coerceAtLeast(0)
            val chars = ArrayList<PdfChar>(count)
            for (index in 0 until count) {
                val char = textPage.charAt(index)
                if (char == '\u0000') continue
                val rect = textPage.charBox(index)?.let { geometry.normalize(it) }
                chars.add(PdfChar(index = index, text = char.toString(), rect = rect))
            }
            val extracted = try { textPage.text() } catch (t: Throwable) { failure = t; null }
            val text = if (!extracted.isNullOrEmpty()) {
                extracted
            } else {
                chars.joinToString(separator = "") { it.text }
            }
            return PdfPageText(
                pageIndex = pageIndex,
                pageWidthPoints = page.widthPoints,
                pageHeightPoints = page.heightPoints,
                rotationDegrees = geometry.rotationDegrees,
                chars = chars,
                text = text,
            )
        } catch (t: Throwable) {
            failure = t
            throw PdfTextSourceException("failed to read text page " + pageIndex, t)
        } finally {
            closeQuietly(textPage)
            if (failure != null) evict(pageIndex, page)
        }
    }

    private fun evict(pageIndex: Int, page: PdfTextPageHandle) {
        if (cache[pageIndex] === page) {
            cache.remove(pageIndex)
        }
        closeQuietly(page)
    }

    /** 仅供单测和诊断使用。 */
    internal fun cachedPageCount(): Int = synchronized(lock) { cache.size }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            for (page in cache.values) closeQuietly(page)
            cache.clear()
            closeQuietly(document)
        }
    }

    companion object {
        /** 打开文本源；任何失败都会关闭已创建的句柄，不泄漏 fd。 */
        fun open(
            opener: () -> PdfTextDocumentHandle,
            cacheLimit: Int = 3,
        ): PdfTextOpenResult {
            val document = try {
                opener()
            } catch (t: Throwable) {
                return PdfTextOpenResult.Failure(classifyPdfOpenFailure(t), t.message, t)
            }
            return try {
                val count = document.pageCount
                if (count < 0) {
                    throw PdfTextSourceException("invalid page count: " + count)
                }
                PdfTextOpenResult.Success(PdfTextSource(document, cacheLimit))
            } catch (t: Throwable) {
                closeQuietly(document)
                val reason = if (t is PdfTextSourceException) {
                    PdfTextFailureReason.PAGE_COUNT_INVALID
                } else {
                    classifyPdfOpenFailure(t)
                }
                PdfTextOpenResult.Failure(reason, t.message, t)
            }
        }


        internal fun closeQuietly(closeable: Closeable?) {
            try {
                closeable?.close()
            } catch (_: Throwable) {
                // 关闭失败不应覆盖主流程异常或导致 close 不幂等。
            }
        }
    }
}
