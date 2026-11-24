package app.particlebook.android.reader.pdf.search

import android.content.Context
import app.particlebook.android.reader.pdf.PdfPageText
import app.particlebook.android.reader.pdf.PdfTextFailureReason
import app.particlebook.android.reader.pdf.PdfTextOpenResult
import app.particlebook.android.reader.pdf.PdfTextSource
import app.particlebook.android.reader.pdf.PdfiumAndroidTextSource
import java.io.Closeable
import java.io.File

/** 搜索会话打开结果；失败分类复用文本层那套，UI 不用再分叉一套提示文案。 */
sealed interface PdfSearchSessionOpenResult {
    data class Success(val session: PdfSearchSession) : PdfSearchSessionOpenResult
    data class Failure(
        val reason: PdfTextFailureReason,
        val message: String? = null,
    ) : PdfSearchSessionOpenResult
}

/**
 * 一次「整本 PDF 文本搜索」的会话。
 *
 * 为什么不复用阅读屏的 PdfTextLayerController：那个控制器的页缓存是可见页窗口专用的
 * （5 页 / 8 MiB，preloadVisible 会主动释放窗口外的 native 页）。整本扫描会把几百页
 * 轮流塞进同一份预算，既拖慢当前页预载，也会让翻页时反复重开句柄。这里用独立的
 * PdfTextSource（cacheLimit = 1）——扫描和阅读各自持有自己的 fd 与页句柄，
 * 扫描结束 close 就全部还回去，互不影响。
 *
 * 线程契约：scan / close 都不在主线程调用；PdfTextSource 内部已串行化 PDFium 调用。
 * 生命周期：由调用方（阅读屏）在协程里 open → scan → close，异常路径也必须 close。
 */
class PdfSearchSession private constructor(
    private val source: PdfTextSource,
    val pageCount: Int,
) : Closeable {

    private var closed = false

    /**
     * 逐页扫描整本。取消由调用方的协程负责：PdfSearchScanner 每页都会检查取消状态，
     * 所以关掉界面 / 换关键词时直接 cancel 那个 Job 即可，不需要额外的取消标志。
     */
    suspend fun scan(
        query: String,
        limit: Int = PdfSearchScanner.DEFAULT_LIMIT,
        onProgress: (scannedPages: Int, hitCount: Int) -> Unit = { _, _ -> },
    ): PdfSearchReport {
        if (closed) return PdfSearchReport.empty(query, 0)
        return PdfSearchScanner.scan(
            pageCount = pageCount,
            query = query,
            limit = limit,
            loadPage = { page -> pageOrNull(page) },
            onProgress = onProgress,
        )
    }

    /**
     * 单页读取；任何异常都折算成"这一页没有文本层"。
     *
     * 扫描器自己也会吞异常，这里再包一层是因为 UI 侧可能在扫描之外单独取页，
     * 两处语义保持一致：坏页只会少几个命中，不会让整次搜索失败。
     */
    private fun pageOrNull(page: Int): PdfPageText? {
        if (closed || page < 0 || page >= pageCount) return null
        return try {
            source.page(page)
        } catch (_: Throwable) {
            null
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { source.close() }
    }

    companion object {
        /**
         * 打开一份独立只读句柄。密码参数与阅读屏保持一致（当前都传 null）：
         * 受密码保护的 PDF 在 PdfRenderer 那一步就打不开，搜索不应该比阅读更宽松。
         */
        fun open(
            context: Context,
            file: File,
            password: String? = null,
        ): PdfSearchSessionOpenResult {
            val opened = try {
                PdfiumAndroidTextSource.open(context, file, password, cacheLimit = 1)
            } catch (t: Throwable) {
                return PdfSearchSessionOpenResult.Failure(
                    PdfTextFailureReason.OPEN_FAILED,
                    t.message,
                )
            }
            return when (opened) {
                is PdfTextOpenResult.Failure -> PdfSearchSessionOpenResult.Failure(
                    opened.reason,
                    opened.message,
                )

                is PdfTextOpenResult.Success -> {
                    val pages = runCatching { opened.source.pageCount }.getOrNull()
                    if (pages == null || pages < 0) {
                        runCatching { opened.source.close() }
                        PdfSearchSessionOpenResult.Failure(
                            PdfTextFailureReason.PAGE_COUNT_INVALID,
                            "invalid page count",
                        )
                    } else {
                        PdfSearchSessionOpenResult.Success(PdfSearchSession(opened.source, pages))
                    }
                }
            }
        }
    }
}
