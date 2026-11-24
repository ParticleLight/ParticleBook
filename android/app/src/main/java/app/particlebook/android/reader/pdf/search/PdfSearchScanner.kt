package app.particlebook.android.reader.pdf.search

import app.particlebook.android.reader.pdf.PdfPageText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 整本 PDF 的文本搜索扫描器。
 *
 * 只负责「逐页取文本 → 交给 [PdfPageSearcher] → 累计到上限就停」这条纯逻辑：
 * 取页由调用方通过 [loadPage] 提供，线程由调用方通过 Dispatchers 决定，
 * 这里不起线程、不持有任何句柄，因此可以在 JVM 单测里直接跑。
 *
 * 取消契约：每页开始前检查协程取消状态，CancellationException 原样抛出——
 * 「换关键词 / 退出界面」靠 cancel Job 生效，不能被当成坏页吞掉。
 * 坏页契约与此相反：loadPage 抛出的非取消异常、返回 null、没有文本层，
 * 都按「这一页没文本」处理，扫描继续，只少几个命中。
 */
object PdfSearchScanner {

    /** 默认命中上限：够结果列表翻几屏，又不至于让整本扫描卡在某本大字典里。 */
    const val DEFAULT_LIMIT: Int = 200

    /**
     * 从第 0 页扫到 pageCount - 1 页，最多收集 [limit] 条命中。
     *
     * - 命中顺序：页号升序、页内出现顺序。
     * - 每页扫完回调一次 [onProgress]（已扫页数, 累计命中数），被跳过的页也算扫过；
     *   函数返回前还有一次收尾回调，整本扫完时是 (pageCount, hits.size)。
     * - 达到 [limit] 立即结束，此时 scannedPages 计入触发上限的这一页，
     *   limitReached = true。
     */
    suspend fun scan(
        pageCount: Int,
        query: String,
        limit: Int = DEFAULT_LIMIT,
        onProgress: (scannedPages: Int, hitCount: Int) -> Unit = { _, _ -> },
        // loadPage 必须是最后一个参数：调用方都以尾随 lambda 形式传它。
        loadPage: (page: Int) -> PdfPageText?,
    ): PdfSearchReport {
        if (query.isBlank() || pageCount <= 0 || limit <= 0) {
            return PdfSearchReport(
                query = query,
                hits = emptyList(),
                scannedPages = 0,
                pageCount = pageCount.coerceAtLeast(0),
                limitReached = false,
            )
        }

        val hits = ArrayList<PdfSearchHit>()
        var scannedPages = 0
        var limitReached = false
        for (page in 0 until pageCount) {
            // 取消检查放最前面：已被取消的扫描不再取页，也不再上报进度。
            currentCoroutineContext().ensureActive()

            val text = loadPageOrNull { loadPage(page) }
            if (text != null && text.hasTextLayer) {
                // 只搜还差的那几条，最后一条命中就能让 hits.size 正好等于 limit。
                val pageHits = PdfPageSearcher.find(text, query, limit - hits.size)
                hits.addAll(pageHits)
            }

            scannedPages = page + 1
            if (hits.size >= limit) {
                limitReached = true
                break
            }
            onProgress(scannedPages, hits.size)
        }

        // 收尾回调：正常扫完上报 pageCount，因上限提前结束时上报已扫页数，
        // 保证调用方最后一次收到的就是最终进度。
        onProgress(scannedPages, hits.size)
        return PdfSearchReport(
            query = query,
            hits = hits,
            scannedPages = scannedPages,
            pageCount = pageCount,
            limitReached = limitReached,
        )
    }

    /**
     * 取一页并折算成「这一页的文本，没有就是 null」。
     *
     * 取消异常必须穿透：它可能以具体的 CancellationException 子类抛出，也可能被
     * 调用方的包装转成 Throwable，所以先按类型判断，再按全限定类名兜底。
     * 其余异常一律视为坏页（这一页没文本），扫描继续。
     */
    private inline fun loadPageOrNull(load: () -> PdfPageText?): PdfPageText? = try {
        load()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        if (t.isCancellation()) throw t else null
    }

    /** Throwable 是否携带取消语义（含被包装过的取消异常链）。 */
    private fun Throwable.isCancellation(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is CancellationException) return true
            current = current.cause.takeIf { it !== current }
        }
        return false
    }
}