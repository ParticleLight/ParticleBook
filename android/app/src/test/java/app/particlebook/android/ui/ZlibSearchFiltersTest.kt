package app.particlebook.android.ui

import app.particlebook.android.zlib.ZBook
import app.particlebook.android.zlib.ZSearchPage
import app.particlebook.android.zlib.ZlibSearchFilters
import app.particlebook.android.zlib.ZlibSortOrder
import app.particlebook.android.zlib.zlibSearchUrl
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A5 搜索筛选/排序（2026-11-04 实测定参）：URL 拼装、归一化、摘要文案与 ViewModel 语义。
 * 全部为 JVM 单测，不联网、不依赖 Compose。
 */
class ZlibSearchFiltersTest {

    @Test
    fun defaultFiltersKeepLegacySearchUrl() {
        assertEquals(
            "https://zh.z-lib.gd/s/%E4%B8%89%E4%BD%93?page=2",
            zlibSearchUrl("https://zh.z-lib.gd/", "三体", 2),
        )
        assertEquals(
            "https://zh.z-lib.gd/s/%E4%B8%89%E4%BD%93?page=2",
            zlibSearchUrl("https://zh.z-lib.gd", "三体", 2, ZlibSearchFilters()),
        )
    }

    @Test
    fun filtersEncodeServerParametersInFixedOrder() {
        val filters = ZlibSearchFilters(
            languages = listOf("chinese", "english"),
            extensions = listOf("PDF", "EPUB"),
            yearFrom = 2020,
            yearTo = 2024,
            order = ZlibSortOrder.NEWEST,
        )
        assertEquals(
            "https://zh.z-lib.gd/s/%E4%B8%89%E4%BD%93?languages%5B%5D=chinese&languages%5B%5D=english" +
                "&extensions%5B%5D=PDF&extensions%5B%5D=EPUB&yearFrom=2020&yearTo=2024" +
                "&order=date&page=1",
            zlibSearchUrl("https://zh.z-lib.gd", "三体", 1, filters),
        )
    }

    @Test
    fun popularOrderAloneAddsNoParameter() {
        val filters = ZlibSearchFilters(order = ZlibSortOrder.POPULAR)
        assertFalse(filters.isActive)
        assertEquals(
            "https://zh.z-lib.gd/s/abc?page=3",
            zlibSearchUrl("https://zh.z-lib.gd", "abc", 3, filters),
        )
    }

    @Test
    fun sanitizedTrimsDedupesAndSwapsYears() {
        val filters = ZlibSearchFilters(
            languages = listOf(" chinese ", "chinese", ""),
            extensions = listOf("PDF", "PDF"),
            yearFrom = 2024,
            yearTo = 2020,
        ).sanitized()
        assertEquals(listOf("chinese"), filters.languages)
        assertEquals(listOf("PDF"), filters.extensions)
        assertEquals(2020, filters.yearFrom)
        assertEquals(2024, filters.yearTo)
        assertTrue(filters.isActive)

        val invalid = ZlibSearchFilters(yearFrom = -1, yearTo = 0).sanitized()
        assertNull(invalid.yearFrom)
        assertNull(invalid.yearTo)
    }

    @Test
    fun summaryUsesChineseLabelsAndOrderLabel() {
        val filters = ZlibSearchFilters(
            languages = listOf("chinese", "english"),
            extensions = listOf("PDF", "EPUB"),
            yearFrom = 2020,
            yearTo = 2024,
            order = ZlibSortOrder.NEWEST,
        )
        assertEquals("中文 · 英语 · PDF/EPUB · 2020–2024 · 最近添加", zlibFilterSummary(filters))
        assertEquals("", zlibFilterSummary(ZlibSearchFilters()))
        assertEquals("俄语 · 2020 年后", zlibFilterSummary(ZlibSearchFilters(languages = listOf("russian"), yearFrom = 2020)))
    }

    @Test
    fun applyFiltersRerunsFirstPageAndNextPageReusesSameFilters() {
        val source = RecordingSearchSource()
        val vm = newViewModel(source)

        vm.search("三体")
        waitUntil("first search did not finish") { vm.state is ZlibSearchState.Success }
        assertEquals(1, source.requests.size)
        assertFalse(source.requests[0].filters!!.isActive)

        val filters = ZlibSearchFilters(languages = listOf("chinese"), extensions = listOf("EPUB"))
        vm.applyFilters(filters)
        waitUntil("filtered search did not finish") {
            vm.state is ZlibSearchState.Success && source.requests.size == 2
        }
        assertEquals(filters, vm.filters)
        assertEquals(1, source.requests[1].page)
        assertEquals(filters, source.requests[1].filters)

        // 翻页必须沿用发起时的筛选快照，而不是可能被改动的 filters。
        vm.loadNextPage()
        waitUntil("page 2 did not finish") { source.requests.size == 3 }
        assertEquals(2, source.requests[2].page)
        assertEquals(filters, source.requests[2].filters)

        vm.clearFilters()
        waitUntil("clear filters did not finish") { source.requests.size == 4 }
        assertFalse(vm.filters.isActive)
        assertFalse(source.requests[3].filters!!.isActive)
    }

    @Test
    fun currentQueryExposesCommittedSearchAndClearsForCategory() {
        val source = RecordingSearchSource()
        val vm = newViewModel(source)

        vm.search("三体")
        waitUntil("first search did not finish") { vm.state is ZlibSearchState.Success }
        assertEquals("三体", vm.currentQuery)

        vm.searchCategory("文学")
        waitUntil("category search did not finish") { vm.isCategoryMode && vm.state is ZlibSearchState.Success }
        assertEquals("", vm.currentQuery)

        vm.resetToIdle()
        assertEquals("", vm.currentQuery)
    }

    /** 换线重跑：必须用上次已提交的查询，并沿用当前筛选（不能读输入框草稿）。 */
    @Test
    fun reloadCurrentRequestUsesCommittedQueryAndKeepsFilters() {
        val source = RecordingSearchSource()
        val vm = newViewModel(source)

        vm.search("三体")
        waitUntil("first search did not finish") { vm.state is ZlibSearchState.Success }
        val filters = ZlibSearchFilters(languages = listOf("chinese"), extensions = listOf("EPUB"))
        vm.applyFilters(filters)
        waitUntil("filtered search did not finish") {
            vm.state is ZlibSearchState.Success && source.requests.size == 2
        }

        vm.reloadCurrentRequest()
        waitUntil("reload did not start") {
            source.requests.size == 3 && vm.state is ZlibSearchState.Success
        }
        assertEquals("换线重搜应使用上次已提交的查询", "三体", source.requests[2].query)
        assertEquals(1, source.requests[2].page)
        assertEquals(filters, source.requests[2].filters)
    }

    @Test
    fun reloadInCategoryModeRepeatsCategoryAndKeepsFilters() {
        val source = RecordingSearchSource()
        val vm = newViewModel(source)

        vm.searchCategory("文学")
        waitUntil("category search did not finish") { source.categoryRequests.size == 1 }
        val filters = ZlibSearchFilters(extensions = listOf("PDF"))
        vm.applyFilters(filters)
        assertEquals("分类模式不应发起关键词搜索", 0, source.requests.size)

        vm.reloadCurrentRequest()
        waitUntil("category reload did not finish") { source.categoryRequests.size == 2 }
        assertTrue(vm.isCategoryMode)
        assertEquals(filters, vm.filters)
        assertEquals(0, source.requests.size)
    }

    /** 用户没改筛选直接点「应用」：不重搜；真的改了才从第 1 页重搜。 */
    @Test
    fun applyingUnchangedFiltersDoesNotRestartSearch() {
        val source = RecordingSearchSource()
        val vm = newViewModel(source)

        vm.search("三体")
        waitUntil("first search did not finish") { vm.state is ZlibSearchState.Success }
        assertEquals(1, source.requests.size)

        vm.applyFilters(ZlibSearchFilters())
        Thread.sleep(100)
        assertEquals("未变更的筛选不应发起新请求", 1, source.requests.size)

        val filters = ZlibSearchFilters(languages = listOf("chinese"))
        vm.applyFilters(filters)
        waitUntil("changed filters did not search") { source.requests.size == 2 }
        assertEquals(filters, source.requests[1].filters)

        vm.applyFilters(filters)
        Thread.sleep(100)
        assertEquals("重复应用同一筛选不应再搜", 2, source.requests.size)
    }

    @Test
    fun pageTwoAfterFilteredSearchKeepsTheSameFilters() {
        val source = RecordingSearchSource()
        val vm = newViewModel(source)

        vm.search("三体")
        waitUntil("first search did not finish") { vm.state is ZlibSearchState.Success }
        val filters = ZlibSearchFilters(
            languages = listOf("chinese"),
            yearFrom = 2020,
            order = ZlibSortOrder.NEWEST,
        )
        vm.applyFilters(filters)
        waitUntil("filtered search did not finish") {
            vm.state is ZlibSearchState.Success && source.requests.size == 2
        }

        vm.loadNextPage()
        waitUntil("page 2 did not finish") {
            vm.state is ZlibSearchState.Success && source.requests.size == 3
        }
        assertEquals("三体", source.requests[2].query)
        assertEquals(2, source.requests[2].page)
        assertEquals(filters, source.requests[2].filters)
    }

    @Test
    fun applyFiltersInCategoryModeOnlyStoresForNextKeywordSearch() {
        val source = RecordingSearchSource()
        val vm = newViewModel(source)

        vm.searchCategory("文学")
        waitUntil("category search did not finish") { vm.state is ZlibSearchState.Success }
        assertEquals(0, source.requests.size)
        assertTrue(vm.isCategoryMode)

        val filters = ZlibSearchFilters(extensions = listOf("PDF"))
        vm.applyFilters(filters)
        assertEquals("分类模式不应发起关键词搜索", 0, source.requests.size)
        assertEquals(filters, vm.filters)

        vm.search("三体")
        waitUntil("keyword search did not finish") { source.requests.size == 1 }
        assertEquals(filters, source.requests[0].filters)
        assertFalse(vm.isCategoryMode)
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

    private data class Request(
        val query: String,
        val page: Int,
        val filters: ZlibSearchFilters?,
    )

    private class RecordingSearchSource : ZlibSearchSource {
        val requests = CopyOnWriteArrayList<Request>()
        val categoryRequests = CopyOnWriteArrayList<String>()

        override fun search(query: String, page: Int, filters: ZlibSearchFilters?): ZSearchPage {
            requests.add(Request(query, page, filters))
            return ZSearchPage(
                query = query,
                page = page,
                totalPages = 2,
                books = listOf(book(query + "-" + page)),
            )
        }

        override fun fetchCategory(path: String): List<ZBook> {
            categoryRequests.add(path)
            return listOf(book("cat-1"))
        }

        override fun resetConnectionState() = Unit

        override fun mirrorUrl(): String? = "https://mirror.example.com"

        private fun book(id: String) = ZBook(
            id = id,
            title = "Book " + id,
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
}
