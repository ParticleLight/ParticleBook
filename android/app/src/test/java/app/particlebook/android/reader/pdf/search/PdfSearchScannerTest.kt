package app.particlebook.android.reader.pdf.search

import app.particlebook.android.reader.pdf.PdfChar
import app.particlebook.android.reader.pdf.PdfNormalizedRect
import app.particlebook.android.reader.pdf.PdfPageText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [PdfSearchScanner] 的纯 JVM 行为：跨页顺序、上限提前结束、坏页/无文本层跳过、进度与取消。 */
class PdfSearchScannerTest {

    // ---------------------------------------------------------------- 夹具

    /** 把一段文本拆成逐字符的页；rect 全给，index 从 0 递增。 */
    private fun page(pageIndex: Int, text: String): PdfPageText {
        val chars = text.mapIndexed { position, character ->
            PdfChar(
                index = position,
                text = character.toString(),
                rect = PdfNormalizedRect(0.01f, 0.01f, 0.008f, 0.02f),
            )
        }
        return PdfPageText(pageIndex, 600, 800, 0, chars, text)
    }

    /** 真正的空页：没有 chars、没有 text，hasTextLayer == false。 */
    private fun blankPage(pageIndex: Int): PdfPageText =
        PdfPageText(pageIndex, 600, 800, 0, emptyList(), "")

    // ---------------------------------------------------------------- 多页顺序

    @Test
    fun hitsAcrossPagesKeepPageAndAppearanceOrder() = runBlocking {
        val texts = listOf("hit one hit", "nothing here", "hit hit")
        val report = PdfSearchScanner.scan(texts.size, "hit") { page ->
            texts.getOrNull(page)?.let { page(page, it) }
        }

        assertEquals(listOf(0, 0, 2, 2), report.hits.map { it.page })
        assertEquals(listOf(0, 8, 0, 4), report.hits.map { it.charStart })
        assertEquals(4, report.hitCount)
        assertEquals(3, report.scannedPages)
        assertEquals(3, report.pageCount)
        assertFalse(report.limitReached)
        assertEquals(listOf(0, 2), report.hitsByPage().keys.toList())
        assertEquals("hit", report.query)
    }

    @Test
    fun emptyDocumentReturnsEmptyReport() = runBlocking {
        // 有页但一命中没有：全部扫完，isEmpty 为 true。
        val report = PdfSearchScanner.scan(2, "zzz") { page -> page(page, "abc") }

        assertTrue(report.hits.isEmpty())
        assertTrue(report.isEmpty)
        assertEquals(2, report.scannedPages)
        assertFalse(report.limitReached)
    }

    // ---------------------------------------------------------------- 上限

    @Test
    fun limitReachedStopsBeforeLastPage() = runBlocking {
        val loaded = ArrayList<Int>()
        val report = PdfSearchScanner.scan(
            pageCount = 10,
            query = "hit",
            limit = 2,
        ) { page ->
            loaded.add(page)
            page(page, "hit hit")
        }

        assertEquals(2, report.hitCount)
        assertTrue(report.limitReached)
        // 第 0 页就攒满上限，第 1 页根本没取。
        assertEquals(listOf(0), loaded)
        assertEquals(1, report.scannedPages)
        assertFalse(report.isEmpty)
    }

    @Test
    fun limitIsFilledExactlyAcrossPartiallyMatchingPages() = runBlocking {
        val texts = listOf("hit hit hit", "hit hit", "hit")
        val report = PdfSearchScanner.scan(texts.size, "hit", limit = 4) { page ->
            page(page, texts[page])
        }

        assertEquals(4, report.hitCount)
        assertTrue(report.limitReached)
        // 第 0 页 3 条，第 1 页只要还差的 1 条，第 2 页不再扫。
        assertEquals(listOf(0, 0, 0, 1), report.hits.map { it.page })
        assertEquals(2, report.scannedPages)
    }

    @Test
    fun zeroLimitReturnsEmptyWithoutLoadingPages() = runBlocking {
        var loadCalls = 0
        val report = PdfSearchScanner.scan(5, "hit", limit = 0) { page ->
            loadCalls++
            page(page, "hit")
        }

        assertEquals(0, report.scannedPages)
        assertEquals(5, report.pageCount)
        assertTrue(report.hits.isEmpty())
        assertEquals(0, loadCalls)
    }

    // ---------------------------------------------------------------- 坏页 / 无文本层

    @Test
    fun failingPageIsSkippedAndScanContinues() = runBlocking {
        val report = PdfSearchScanner.scan(3, "hit") { page ->
            when (page) {
                1 -> throw IllegalStateException("broken text page")
                else -> page(page, "hit")
            }
        }

        assertEquals(listOf(0, 2), report.hits.map { it.page })
        assertEquals(3, report.scannedPages)
        assertEquals(3, report.pageCount)
        assertFalse(report.limitReached)
    }

    @Test
    fun pageWithoutTextLayerIsSkipped() = runBlocking {
        val report = PdfSearchScanner.scan(3, "hit") { page ->
            when (page) {
                0 -> blankPage(page)
                2 -> null
                else -> page(page, "hit")
            }
        }

        assertEquals(listOf(1), report.hits.map { it.page })
        assertEquals(3, report.scannedPages)
    }

    // ---------------------------------------------------------------- 进度

    @Test
    fun progressEndsWithFinalTotals() = runBlocking {
        val progress = ArrayList<Pair<Int, Int>>()
        val report = PdfSearchScanner.scan(3, "hit", onProgress = { scanned, hits ->
            progress.add(scanned to hits)
        }) { page ->
            page(page, if (page == 1) "hit" else "no")
        }

        assertEquals(1, report.hitCount)
        // 每页一次 + 收尾一次。
        assertEquals(4, progress.size)
        assertEquals(1 to 0, progress[0])
        assertEquals(2 to 1, progress[1])
        assertEquals(3 to 1, progress[2])
        assertEquals(3 to 1, progress.last())
        assertEquals(report.hits.size, progress.last().second)
        assertEquals(report.pageCount, progress.last().first)
    }

    @Test
    fun progressAlsoEndsWhenLimitStopsScan() = runBlocking {
        val progress = ArrayList<Pair<Int, Int>>()
        val report = PdfSearchScanner.scan(5, "hit", limit = 1, onProgress = { scanned, hits ->
            progress.add(scanned to hits)
        }) { page ->
            page(page, "hit")
        }

        assertTrue(report.limitReached)
        // 触发上限的页不再单独回调，只有收尾那次，且上报的是真实已扫页数。
        assertEquals(listOf(1 to 1), progress)
        assertEquals(1, report.scannedPages)
    }

    // ---------------------------------------------------------------- 取消

    @Test
    fun cancellationExceptionFromLoadPagePropagates() = runBlocking {
        var caught: CancellationException? = null
        try {
            PdfSearchScanner.scan(3, "hit") { page ->
                if (page == 1) throw CancellationException("user left the screen")
                page(page, "hit")
            }
        } catch (e: CancellationException) {
            caught = e
        }

        assertEquals("user left the screen", caught?.message)
    }

    @Test
    fun cancellationByJobCancelIsNotSwallowed() = runBlocking {
        var loaded = 0
        var caught: CancellationException? = null
        // 用真实取消路径：loadPage 里取消当前协程，下一轮 ensureActive 必须抛出。
        // 取消发生在子协程里，不会污染 runBlocking 自己的 Job。
        val inner = launch {
            // loadPage 是普通 lambda，里面叫不了 suspend 的 currentCoroutineContext()，先在协程体里取 Job。
            val self = currentCoroutineContext()[Job]
            try {
                PdfSearchScanner.scan(5, "hit") { page ->
                    loaded++
                    if (page == 1) self?.cancel(CancellationException("stop"))
                    page(page, "hit")
                }
            } catch (e: CancellationException) {
                caught = e
                throw e
            }
        }
        inner.join()

        assertEquals("stop", caught?.message)
        // 第 0、1 页都取过；第 1 页取完后被取消，第 2 页不再取。
        assertEquals(2, loaded)
    }

    // ---------------------------------------------------------------- 参数校验

    @Test
    fun blankQueryReturnsEmptyReportWithoutLoadingPages() = runBlocking {
        var loadCalls = 0
        val report = PdfSearchScanner.scan(10, "   ") { page ->
            loadCalls++
            page(page, "hit")
        }

        assertEquals(0, loadCalls)
        assertEquals(0, report.scannedPages)
        assertEquals(10, report.pageCount)
        assertTrue(report.hits.isEmpty())
        assertFalse(report.limitReached)
        assertEquals("   ", report.query)
    }

    @Test
    fun nonPositivePageCountReturnsEmptyReport() = runBlocking {
        var loadCalls = 0
        val negative = PdfSearchScanner.scan(-4, "hit") { page ->
            loadCalls++
            page(page, "hit")
        }
        val zero = PdfSearchScanner.scan(0, "hit") { page ->
            loadCalls++
            page(page, "hit")
        }

        assertEquals(0, negative.pageCount)
        assertEquals(0, zero.pageCount)
        assertEquals(0, loadCalls)
        assertEquals(PdfSearchReport.empty("hit", 0), negative)
    }
}