package app.particlebook.android.ui

import app.particlebook.android.zlib.ZBook
import app.particlebook.android.zlib.ZSearchPage
import app.particlebook.android.zlib.ZlibSearchFilters
import app.particlebook.android.zlib.ZlibException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
    fun mirrorSwitchWhileLoadingReloadsAgainstNewMirror() {
        val source = FakeSearchSource()
        source.slowThrows = false
        source.mirror = MIRROR_A
        val vm = newViewModel(source)

        vm.search(SLOW)
        assertTrue("slow request did not start", source.slowStarted.await(5, TimeUnit.SECONDS))

        // 用户在第一页请求在途时切线路：当前请求仍会以旧线路跑完，完成后必须自动用新线路重跑。
        source.mirror = MIRROR_B
        vm.onMirrorSwitched()
        source.slowGate.countDown()

        waitUntil("mirror switch while loading did not reload") {
            source.slowCalls.get() >= 2 &&
                (vm.state as? ZlibSearchState.Success)
                    ?.books
                    ?.firstOrNull()
                    ?.id == "slow-" + hostOf(MIRROR_B)
        }
        val state = vm.state as ZlibSearchState.Success
        assertEquals(SLOW, state.query)
        assertEquals("旧线路结果不应留在新线路标签下", 2, source.slowCalls.get())
    }

    @Test
    fun mirrorSwitchWhileLoadingButSameMirrorDoesNotReload() {
        val source = FakeSearchSource()
        source.slowThrows = false
        source.mirror = MIRROR_A
        val vm = newViewModel(source)

        vm.search(SLOW)
        assertTrue("slow request did not start", source.slowStarted.await(5, TimeUnit.SECONDS))

        // 切换未生效（mirror 还是原值）时，不应产生第二次请求。
        vm.onMirrorSwitched()
        source.slowGate.countDown()
        waitUntil("initial request did not finish") { vm.state is ZlibSearchState.Success }
        Thread.sleep(200)

        assertEquals(1, source.slowCalls.get())
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

    @Test
    fun mirrorSwitchWhileFirstPageFailsReloadsAgainstNewMirror() {
        val source = FakeSearchSource()
        source.slowThrows = false
        source.slowThrowsOnFirst = true
        val vm = newViewModel(source)

        vm.search(SLOW)
        assertTrue("slow request did not start", source.slowStarted.await(5, TimeUnit.SECONDS))

        source.mirror = MIRROR_B
        vm.onMirrorSwitched()
        source.slowGate.countDown()

        // 第一页失败落 Error 后也必须按切换目标重跑，而不是停在旧线路的错误页。
        waitUntil("error state did not reload against new mirror") {
            (vm.state as? ZlibSearchState.Success)
                ?.books
                ?.firstOrNull()
                ?.id == "slow-" + hostOf(MIRROR_B)
        }
        assertEquals("切换只应触发一次重跑", 2, source.slowCalls.get())
    }

    @Test
    fun mirrorSwitchWhileFirstPageEmptyReloadsAgainstNewMirror() {
        val source = FakeSearchSource()
        source.slowThrows = false
        source.slowEmptyOnFirst = true
        val vm = newViewModel(source)

        vm.search(SLOW)
        assertTrue("slow request did not start", source.slowStarted.await(5, TimeUnit.SECONDS))

        source.mirror = MIRROR_B
        vm.onMirrorSwitched()
        source.slowGate.countDown()

        // 第一页空结果落 Empty 后同样要重跑；重跑后拿到新线路的真实结果。
        waitUntil("empty state did not reload against new mirror") {
            (vm.state as? ZlibSearchState.Success)
                ?.books
                ?.firstOrNull()
                ?.id == "slow-" + hostOf(MIRROR_B)
        }
        assertEquals(2, source.slowCalls.get())
    }

    @Test
    fun mirrorSwitchWhileCategoryLoadingReloadsAgainstNewMirror() {
        val source = FakeSearchSource()
        source.blockCategory = true
        val vm = newViewModel(source)

        vm.searchCategory(CATEGORY)
        assertTrue("category request did not start", source.categoryStarted.await(5, TimeUnit.SECONDS))

        source.mirror = MIRROR_B
        vm.onMirrorSwitched()
        source.categoryGate.countDown()

        // 分类空页落 Empty 后走 retry 重跑分类；第二次 fetch 使用新线路。
        waitUntil("category did not reload against new mirror") {
            val state = vm.state as? ZlibSearchState.Success
            state != null && state.category == CATEGORY &&
                state.books.firstOrNull()?.id == "category-" + hostOf(MIRROR_B)
        }
        assertEquals(2, source.categoryCalls.get())
        assertEquals("分类 retry 应重置连接状态", 1, source.resetCalls.get())
    }

    @Test
    fun mirrorSwitchWhileFilteredSearchKeepsFilterSnapshot() {
        val source = FakeSearchSource()
        source.slowThrows = false
        val vm = newViewModel(source)
        val filters = ZlibSearchFilters(languages = listOf("english"), yearFrom = 2000)
        vm.applyFilters(filters)

        vm.search(SLOW)
        assertTrue("slow request did not start", source.slowStarted.await(5, TimeUnit.SECONDS))

        source.mirror = MIRROR_B
        vm.onMirrorSwitched()
        source.slowGate.countDown()

        waitUntil("filtered search did not reload against new mirror") {
            (vm.state as? ZlibSearchState.Success)
                ?.books
                ?.firstOrNull()
                ?.id == "slow-" + hostOf(MIRROR_B)
        }
        assertEquals(2, source.slowCalls.get())
        assertEquals("换线重跑必须沿用请求发起时的筛选快照", filters, source.lastSlowFilters)
    }

    @Test
    fun multipleMirrorSwitchesWhileLoadingReloadOnlyOnceWithLatestMirror() {
        val source = FakeSearchSource()
        source.slowThrows = false
        val vm = newViewModel(source)

        vm.search(SLOW)
        assertTrue("slow request did not start", source.slowStarted.await(5, TimeUnit.SECONDS))

        // 连续切 B、C：只保留最后目标，旧请求结束后至多重跑一次。
        source.mirror = MIRROR_B
        vm.onMirrorSwitched()
        source.mirror = MIRROR_C
        vm.onMirrorSwitched()
        source.slowGate.countDown()

        waitUntil("latest mirror was not used for reload") {
            (vm.state as? ZlibSearchState.Success)
                ?.books
                ?.firstOrNull()
                ?.id == "slow-" + hostOf(MIRROR_C)
        }
        assertEquals("连续切换不得产生多次重跑", 2, source.slowCalls.get())
    }

    @Test
    fun mirrorSnapshotAtSwitchSurvivesMirrorRevertBeforeCompletion() {
        val source = FakeSearchSource()
        source.slowThrows = false
        val vm = newViewModel(source)

        vm.search(SLOW)
        assertTrue("slow request did not start", source.slowStarted.await(5, TimeUnit.SECONDS))

        // 切换回调时抓目标快照 B；请求结束前全局 mirror 又回到 A（异步/后台写回）。
        // 若结束时重新读 mirrorBase 会误判为「没换线」而漏重跑；快照比较必须仍重跑。
        source.mirror = MIRROR_B
        vm.onMirrorSwitched()
        source.mirror = MIRROR_A
        source.slowGate.countDown()

        waitUntil("switch target snapshot was not used") { source.slowCalls.get() >= 2 }
        assertEquals(2, source.slowCalls.get())
    }

    @Test
    fun mirrorSwitchWhileLoadingMoreResetsToFirstPageAndDropsOldPage() {
        val source = FakeSearchSource()
        val vm = newViewModel(source)

        vm.search(PAGE2_FAIL)
        waitUntil("first page did not succeed") { vm.state is ZlibSearchState.Success }
        source.blockPage2 = true
        vm.loadNextPage()
        assertTrue("page 2 did not start", source.page2Started.await(5, TimeUnit.SECONDS))

        // 产品口径：翻页中换线 = 取消下一页、回到第 1 页重搜；旧页结果不得追加。
        source.mirror = MIRROR_B
        vm.onMirrorSwitched()
        waitUntil("page 1 was not restarted after mirror switch") {
            source.pageOneCalls.get() >= 2 &&
                (vm.state as? ZlibSearchState.Success)?.page == 1
        }

        source.page2Gate.countDown()
        Thread.sleep(100)

        val state = vm.state as ZlibSearchState.Success
        assertEquals(PAGE2_FAIL, state.query)
        assertEquals("换线后必须回到第 1 页", 1, state.page)
        assertEquals(false, state.loadingMore)
        assertNull("旧第 2 页结果不得污染新列表", state.loadMoreError)
        assertEquals("首页请求次数 = 初始 1 次 + 换线重跑 1 次", 2, source.pageOneCalls.get())
        assertEquals("旧第 2 页结果不得追加", 1, state.books.size)
    }

    private fun hostOf(url: String): String = url.substringAfter("://", url).substringBefore('/')

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
        val slowCalls = AtomicInteger()

        /** 测试可改：模拟 ZlibClient.mirror 在请求中途被切换。 */
        @Volatile
        var mirror: String = MIRROR_A
        val page2Started = CountDownLatch(1)
        val page2Gate = CountDownLatch(1)

        @Volatile
        var slowThrows: Boolean = true

        /** 仅第 1 次 SLOW 调用抛错/返回空（用于验证 Error/Empty 落定后的换线重跑）。 */
        @Volatile
        var slowThrowsOnFirst: Boolean = false
        @Volatile
        var slowEmptyOnFirst: Boolean = false

        /** 最近一次 SLOW 搜索收到的筛选快照。 */
        @Volatile
        var lastSlowFilters: ZlibSearchFilters? = null

        /** 第 1 页请求次数（含换线后的重跑），用于断言没有重复请求。 */
        val pageOneCalls = AtomicInteger()

        /** true 时第 2 页先阻塞在 page2Gate（用于构造「旧翻页请求迟到」的时序）。 */
        @Volatile
        var blockPage2: Boolean = false

        val categoryStarted = CountDownLatch(1)
        val categoryGate = CountDownLatch(1)
        val categoryCalls = AtomicInteger()
        val resetCalls = AtomicInteger()

        /** true 时第 1 次分类请求阻塞，且该次返回空页（触发 Empty → retry 重跑）。 */
        @Volatile
        var blockCategory: Boolean = false

        override fun search(query: String, page: Int, filters: ZlibSearchFilters?): ZSearchPage {
            if (page == 1) pageOneCalls.incrementAndGet()
            when {
                query == SLOW -> {
                    val startedOn = mirror
                    val call = slowCalls.incrementAndGet()
                    lastSlowFilters = filters
                    slowStarted.countDown()
                    check(slowGate.await(10, TimeUnit.SECONDS)) { "slow gate timeout" }
                    if (call == 1 && slowThrowsOnFirst) throw IOException("first page failure")
                    if (slowThrows) throw IOException("stale network failure")
                    if (call == 1 && slowEmptyOnFirst) return page(query, page, emptyList())
                    // 结果带发起时线路，测试可断言最终展示的是新线路的重跑结果。
                    val host = startedOn.substringAfter("://", startedOn).substringBefore('/')
                    return page(query, page, listOf(book("slow-" + host, "Slow")))
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

        override fun fetchCategory(path: String): List<ZBook> {
            val call = categoryCalls.incrementAndGet()
            val startedOn = mirror
            if (blockCategory && call == 1) {
                categoryStarted.countDown()
                check(categoryGate.await(10, TimeUnit.SECONDS)) { "category gate timeout" }
            }
            return if (call == 1) {
                emptyList()
            } else {
                val host = startedOn.substringAfter("://", startedOn).substringBefore('/')
                listOf(book("category-" + host, "Category"))
            }
        }

        override fun resetConnectionState() {
            resetCalls.incrementAndGet()
        }

        override fun mirrorUrl(): String? = mirror

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
        const val MIRROR_A = "https://mirror-a.example.com"
        const val MIRROR_B = "https://mirror-b.example.com"
        const val MIRROR_C = "https://mirror-c.example.com"
        const val SLOW = "slow"
        const val FAST = "fast"
        const val NO_MIRROR = "nomirror"
        const val PAGE2_FAIL = "page2fail"
        const val CATEGORY = "Android"
    }
}