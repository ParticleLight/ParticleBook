package app.particlebook.android.ui

import app.particlebook.android.zlib.ZBook
import app.particlebook.android.zlib.ZSearchPage
import app.particlebook.android.zlib.ZlibException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索并发语义（T6 / A2 配套）：快速改词、重试、翻页失败时，旧请求的迟到结果
 * 不得覆盖新请求的状态。
 *
 * 用可阻塞的假数据源（CountDownLatch）精确控制「旧请求最后才返回」的顺序；
 * 协程跑在 Unconfined 上，网络阻塞不影响测试线程。日志/时钟全部注入空实现，
 * 避免 JVM 单测里 android.util.Log / SystemClock not mocked。
 */
class ZlibSearchViewModelRaceTest {

    @Test
    fun staleFailureAfterNewSearchDoesNotOverwriteSuccess() {
        val source = FakeSearchSource()
        val vm = newViewModel(source)

        vm.search(SLOW)
        assertTrue("slow request did not start", source.slowStarted.await(5, TimeUnit.SECONDS))

        vm.search(FAST)
        waitUntil("fast search did not succeed") {
            (vm.state as? ZlibSearchState.Success)?.query == FAST
        }

        source.slowGate.countDown()
        Thread.sleep(200)
        val state = vm.state
        assertTrue("旧请求的失败覆盖了新结果: " + state, state is ZlibSearchState.Success)
        assertEquals(FAST, (state as ZlibSearchState.Success).query)
    }

    @Test
    fun staleSuccessAfterNewSearchDoesNotOverwriteSuccess() {
        val source = FakeSearchSource()
        source.slowThrows = false
        val vm = newViewModel(source)

        vm.search(SLOW)
        assertTrue("slow request did not start", source.slowStarted.await(5, TimeUnit.SECONDS))
        vm.search(FAST)
        waitUntil("fast search did not succeed") {
            (vm.state as? ZlibSearchState.Success)?.query == FAST
        }

        source.slowGate.countDown()
        Thread.sleep(200)
        val state = vm.state as ZlibSearchState.Success
        assertEquals(FAST, state.query)
        assertEquals(1, state.books.size)
        assertEquals(FAST + "-1", state.books.first().id)
    }

    @Test
    fun firstPageNoMirrorKeepsKindForBanner() {
        val source = FakeSearchSource()
        val vm = newViewModel(source)

        vm.search(NO_MIRROR)
        waitUntil("no-mirror error did not arrive") { vm.state is ZlibSearchState.Error }
        val error = vm.state as ZlibSearchState.Error
        assertEquals("NO_MIRROR", error.kindLabel)
    }

    @Test
    fun loadNextPageNoMirrorSetsKindLabel() {
        val source = FakeSearchSource()
        val vm = newViewModel(source)

        vm.search(PAGE2_FAIL)
        waitUntil("first page did not succeed") { vm.state is ZlibSearchState.Success }
        vm.loadNextPage()
        waitUntil("page 2 error did not arrive") {
            (vm.state as? ZlibSearchState.Success)?.loadMoreError != null
        }
        val state = vm.state as ZlibSearchState.Success
        assertEquals("NO_MIRROR", state.loadMoreKindLabel)
        assertEquals(false, state.loadingMore)
        // 首页数据仍在，失败只落在列表尾部。
        assertEquals(1, state.books.size)
    }

    @Test
    fun staleLoadMoreFailureAfterNewSearchDoesNotClobberBannerState() {
        val source = FakeSearchSource()
        val vm = newViewModel(source)

        vm.search(PAGE2_FAIL)
        waitUntil("first page did not succeed") { vm.state is ZlibSearchState.Success }
        source.blockPage2 = true
        vm.loadNextPage()
        assertTrue("page 2 did not start", source.page2Started.await(5, TimeUnit.SECONDS))

        vm.search(FAST)
        waitUntil("fast search did not succeed") {
            (vm.state as? ZlibSearchState.Success)?.query == FAST
        }

        source.page2Gate.countDown()
        Thread.sleep(200)
        val state = vm.state as ZlibSearchState.Success
        assertEquals(FAST, state.query)
        assertNull("翻页旧错误不应让新结果出现 NO_MIRROR banner", state.loadMoreError)
        assertNull(state.loadMoreKindLabel)
        assertNotNull(state.books)
    }

    private fun newViewModel(source: ZlibSearchSource): ZlibSearchViewModel =
        ZlibSearchViewModel(
            scope = CoroutineScope(Dispatchers.Unconfined),
            source = source,
            now = { 0L },
            logInfo = { _, _ -> },
            logWarn = { _, _, _ -> },
        )

    private fun waitUntil(message: String, timeoutMs: Long = 5_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(10)
        }
        throw AssertionError(message)
    }

    private class FakeSearchSource : ZlibSearchSource {
        val slowStarted = CountDownLatch(1)
        val slowGate = CountDownLatch(1)
        val page2Started = CountDownLatch(1)
        val page2Gate = CountDownLatch(1)

        @Volatile
        var slowThrows: Boolean = true

        /** true 时第 2 页先阻塞在 page2Gate（用于构造「旧翻页请求迟到」的时序）。 */
        @Volatile
        var blockPage2: Boolean = false

        override fun search(query: String, page: Int): ZSearchPage {
            when {
                query == SLOW -> {
                    slowStarted.countDown()
                    check(slowGate.await(10, TimeUnit.SECONDS)) { "slow gate timeout" }
                    if (slowThrows) throw IOException("stale network failure")
                    return page(query, page, listOf(book("slow-1", "Slow")))
                }
                query == NO_MIRROR -> throw ZlibException("all mirrors down", ZlibException.Kind.NO_MIRROR)
                query == PAGE2_FAIL && page == 2 -> {
                    if (blockPage2) {
                        page2Started.countDown()
                        check(page2Gate.await(10, TimeUnit.SECONDS)) { "page2 gate timeout" }
                    }
                    throw ZlibException("all mirrors down", ZlibException.Kind.NO_MIRROR)
                }
                else -> return page(query, page, listOf(book(query + "-1", "Fast")))
            }
        }

        override fun fetchCategory(path: String): List<ZBook> = emptyList()

        override fun resetConnectionState() = Unit

        override fun mirrorUrl(): String? = "https://mirror.example.com"

        private fun page(query: String, page: Int, books: List<ZBook>) =
            ZSearchPage(query = query, page = page, totalPages = if (query == PAGE2_FAIL) 2 else 1, books = books)

        private fun book(id: String, title: String) = ZBook(
            id = id,
            title = title,
            author = "Author",
            href = "/book/" + id + "/x.html",
            downloadPath = "/dl/" + id,
            language = "English",
            extension = "pdf",
            fileSize = "1 MB",
            year = "2024",
            publisher = "Press",
            rating = "4.5",
            isbn = "",
        )
    }

    private companion object {
        const val SLOW = "slow"
        const val FAST = "fast"
        const val NO_MIRROR = "nomirror"
        const val PAGE2_FAIL = "page2fail"
    }
}
