package app.particlebook.android.ui

import app.particlebook.android.data.BookRow
import app.particlebook.android.data.LibraryRepository
import app.particlebook.android.data.SearchHit
import app.particlebook.android.data.TestLibraryRepository
import app.particlebook.android.reader.ReaderLocator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨书搜索的 JVM 单测（无 Robolectric、无 kotlinx-coroutines-test，与 Zlib*Test 同款做法）。
 *
 * 覆盖三层：
 * 1. 纯函数：分组/排序、位置文案、高亮区间、失败文案——不碰协程，最稳；
 * 2. 状态机：防抖合并、空白归一、Empty/Results/Failed、重试、dispose、旧请求不覆盖新结果；
 * 3. 契约常量：[SearchViewModel.SEARCH_LIMIT]，页面文案（「只显示前 N 条」）直接引用它，
 *    值变了测试必须跟着红。
 *
 * 计时策略：[viewModel] 把 scope 与 ioDispatcher 都设成 Dispatchers.Unconfined、防抖设 0，
 * 于是「输入 → 查库 → 写 state」在 updateQuery 返回前同步跑完，断言不需要假睡。
 * 只有真的验证「等待」与「并发」的用例才用非零防抖 / Dispatchers.Default + [waitUntil]
 * （轮询，不固定时长）。
 */
class SearchViewModelTest {

    // ------------------------------------------------------------------
    // 初始状态 / 输入归一
    // ------------------------------------------------------------------

    @Test
    fun `初始状态是 Idle 且按需才触库`() {
        val repository = RecordingRepository()
        val model = viewModel { repository }
        try {
            assertEquals(SearchUiState.Idle, model.state)
            assertEquals("", model.query)
            assertEquals("", model.lastCommittedQuery)
            assertTrue("没输入就不该查库", repository.searchedQueries().isEmpty())
        } finally {
            model.dispose()
        }
    }

    @Test
    fun `纯空白输入归一回 Idle 且不触库`() {
        val repository = RecordingRepository()
        val model = viewModel { repository }
        try {
            model.updateQuery("   \t  ")
            assertEquals(SearchUiState.Idle, model.state)
            assertEquals("", model.query)
            assertEquals("", model.lastCommittedQuery)
            assertTrue(repository.searchedQueries().isEmpty())
        } finally {
            model.dispose()
        }
    }

    @Test
    fun `输入回显保留原样但检索用 trim 后的词`() {
        val repository = RecordingRepository(
            hits = listOf(hit(1L, pdfLocator(0))),
            titles = listOf(book(1L, "深空")),
        )
        val model = viewModel { repository }
        try {
            model.updateQuery("  记忆  ")
            assertEquals("  记忆  ", model.query)
            val state = model.state as SearchUiState.Results
            assertEquals("  记忆  ", state.query)
            assertEquals(listOf("记忆"), repository.searchedQueries())
            assertEquals("记忆", model.lastCommittedQuery)
        } finally {
            model.dispose()
        }
    }

    // ------------------------------------------------------------------
    // 防抖与并发
    // ------------------------------------------------------------------

    @Test
    fun `防抖窗口内只回显不查询`() {
        val repository = RecordingRepository(
            hits = listOf(hit(1L, pdfLocator(0))),
            titles = listOf(book(1L, "深空")),
        )
        val model = viewModel(repositoryProvider = { repository }, debounceMillis = 60L)
        try {
            model.updateQuery("记忆")
            assertEquals(SearchUiState.Editing("记忆"), model.state)
            assertTrue("防抖窗口内不该已经查库", repository.searchedQueries().isEmpty())
            waitUntil("防抖结束后应出结果") { model.state is SearchUiState.Results }
            assertEquals(listOf("记忆"), repository.searchedQueries())
        } finally {
            model.dispose()
        }
    }

    @Test
    fun `连续输入只保留最后一次查询`() {
        val repository = RecordingRepository(
            hits = listOf(hit(1L, pdfLocator(0))),
            titles = listOf(book(1L, "深空")),
        )
        val model = viewModel(repositoryProvider = { repository }, debounceMillis = 60L)
        try {
            model.updateQuery("记")
            model.updateQuery("记忆")
            model.updateQuery("记忆宫")
            waitUntil("最后一次输入应出结果") { model.state is SearchUiState.Results }
            assertEquals(listOf("记忆宫"), repository.searchedQueries())
        } finally {
            model.dispose()
        }
    }

    @Test
    fun `dispose 会取消还没发起的防抖任务`() {
        val repository = RecordingRepository(
            hits = listOf(hit(1L, pdfLocator(0))),
            titles = listOf(book(1L, "深空")),
        )
        val model = viewModel(repositoryProvider = { repository }, debounceMillis = 80L)
        model.updateQuery("记忆")
        assertEquals(SearchUiState.Editing("记忆"), model.state)
        model.dispose()
        Thread.sleep(300L)
        assertEquals("取消后应停在输入态", SearchUiState.Editing("记忆"), model.state)
        assertTrue("取消后不该再查库", repository.searchedQueries().isEmpty())
    }

    @Test
    fun `旧请求返回后不会覆盖新查询的结果`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val repository = RecordingRepository(
            hitsByQuery = mapOf(
                "first" to listOf(hit(1L, pdfLocator(0))),
                "second" to listOf(hit(2L, pdfLocator(0))),
            ),
            titles = listOf(book(1L, "旧书"), book(2L, "新书")),
        )
        repository.onSearch = { query ->
            if (query == "first") {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        // 这条用例故意让查库落在别的线程上，才能制造「旧请求先发出、新请求先返回」的窗口。
        val model = viewModel(repositoryProvider = { repository }, ioDispatcher = Dispatchers.Default)
        try {
            model.updateQuery("first")
            assertTrue("首个查询应已进入仓库", started.await(5, TimeUnit.SECONDS))
            model.updateQuery("second")
            waitUntil("第二个查询应出结果") {
                model.state is SearchUiState.Results && model.state.query == "second"
            }
            release.countDown()
            Thread.sleep(150L)
            val state = model.state as SearchUiState.Results
            assertEquals("second", state.query)
            assertEquals(listOf(2L), state.groups.map { it.bookId })
        } finally {
            release.countDown()
            model.dispose()
        }
    }

    // ------------------------------------------------------------------
    // 分组 / 排序 / 位置文案
    // ------------------------------------------------------------------

    @Test
    fun `结果按书分组且组内按阅读位置排序`() {
        val repository = RecordingRepository(
            hits = listOf(
                hit(7L, epubLocator("OEBPS/ch01.xhtml", 4), "深夜的宫殿"),
                hit(7L, pdfLocator(9), "第九页"),
                hit(3L, textLocator(100), "长夜"),
                hit(7L, pdfLocator(2), "第二页"),
            ),
            titles = listOf(book(7L, "深空"), book(3L, "长夜")),
        )
        val model = viewModel { repository }
        try {
            model.updateQuery("记忆")
            val state = model.state as SearchUiState.Results
            assertEquals(4, state.hitCount)
            assertEquals(listOf(7L, 3L), state.groups.map { it.bookId })
            assertEquals(listOf("深空", "长夜"), state.groups.map { it.title })
            assertEquals(
                listOf("ch01.xhtml · 第 5 段", "第 3 页", "第 10 页"),
                state.groups[0].hits.map { it.positionLabel },
            )
            assertEquals(listOf("第 101 字"), state.groups[1].hits.map { it.positionLabel })
        } finally {
            model.dispose()
        }
    }

    @Test
    fun `解不出的位置排在组尾并短显原串`() {
        val repository = RecordingRepository(
            hits = listOf(
                hit(1L, pdfLocator(1)),
                hit(1L, "not-a-locator"),
                hit(1L, pdfLocator(0)),
            ),
            titles = listOf(book(1L, "深空")),
        )
        val model = viewModel { repository }
        try {
            model.updateQuery("记忆")
            val rows = (model.state as SearchUiState.Results).groups.single().hits
            assertEquals(listOf("第 1 页", "第 2 页", "not-a-locator"), rows.map { it.positionLabel })
        } finally {
            model.dispose()
        }
    }

    @Test
    fun `当前书架为空时历史命中不出现且不发起搜索`() {
        val repository = RecordingRepository(hits = listOf(hit(42L, pdfLocator(0))))
        val model = viewModel { repository }
        try {
            model.updateQuery("记忆")
            assertEquals(SearchUiState.Empty("记忆"), model.state)
            assertEquals("记忆", model.lastCommittedQuery)
            assertTrue("空书架应短路历史索引查询", repository.searchedQueries().isEmpty())
        } finally {
            model.dispose()
        }
    }

    @Test
    fun `没有命中时进入 Empty`() {
        val repository = RecordingRepository(hits = emptyList(), titles = listOf(book(1L, "深空")))
        val model = viewModel { repository }
        try {
            model.updateQuery("不存在的词")
            assertEquals(SearchUiState.Empty("不存在的词"), model.state)
            assertEquals(listOf("不存在的词"), repository.searchedQueries())
        } finally {
            model.dispose()
        }
    }

    // ------------------------------------------------------------------
    // 上限 / 截断
    // ------------------------------------------------------------------

    @Test
    fun `查询上限固定为 200 条并在触顶时标记可能截断`() {
        assertEquals(200, SearchViewModel.SEARCH_LIMIT)
        val atLimit = RecordingRepository(
            hits = List(SearchViewModel.SEARCH_LIMIT) { index -> hit(1L, pdfLocator(index)) },
            titles = listOf(book(1L, "深空")),
        )
        val model = viewModel { atLimit }
        try {
            model.updateQuery("记忆")
            val state = model.state as SearchUiState.Results
            assertEquals(SearchViewModel.SEARCH_LIMIT, state.hitCount)
            assertTrue("命中数触顶应提示可能还有更多", state.truncated)
            assertEquals(listOf(SearchViewModel.SEARCH_LIMIT), atLimit.searchedLimits())
        } finally {
            model.dispose()
        }
        val underLimit = RecordingRepository(
            hits = List(SearchViewModel.SEARCH_LIMIT - 1) { index -> hit(1L, pdfLocator(index)) },
            titles = listOf(book(1L, "深空")),
        )
        val other = viewModel { underLimit }
        try {
            other.updateQuery("记忆")
            val state = other.state as SearchUiState.Results
            assertEquals(SearchViewModel.SEARCH_LIMIT - 1, state.hitCount)
            assertTrue("没触顶就不该提示截断", !state.truncated)
        } finally {
            other.dispose()
        }
    }

    // ------------------------------------------------------------------
    // 失败与重试
    // ------------------------------------------------------------------

    @Test
    fun `仓库抛错进入 Failed 并保留原始原因`() {
        val repository = RecordingRepository(
            titles = listOf(book(1L, "深空")),
            failure = IllegalStateException("database is locked"),
        )
        val model = viewModel { repository }
        try {
            model.updateQuery("记忆")
            val state = model.state as SearchUiState.Failed
            assertEquals("记忆", state.query)
            assertTrue("文案应带前缀: " + state.message, state.message.startsWith("搜索失败"))
            assertTrue("文案应保留原始异常: " + state.message, state.message.contains("database is locked"))
            assertEquals("失败也算已提交查询，重试用它", "记忆", model.lastCommittedQuery)
        } finally {
            model.dispose()
        }
    }

    @Test
    fun `没有异常信息的失败给兜底文案`() {
        val repository = RecordingRepository(
            titles = listOf(book(1L, "深空")),
            failure = RuntimeException(),
        )
        val model = viewModel { repository }
        try {
            model.updateQuery("记忆")
            assertEquals("搜索失败，请稍后重试。", (model.state as SearchUiState.Failed).message)
        } finally {
            model.dispose()
        }
    }

    @Test
    fun `解析不出仓库时进入 Failed 而不是崩溃`() {
        val model = SearchViewModel(
            scope = CoroutineScope(Dispatchers.Unconfined),
            repositoryProvider = { throw IllegalStateException("db not ready") },
            shelfRows = { listOf(book(1L, "深空")) },
            debounceMillis = 0L,
            ioDispatcher = Dispatchers.Unconfined,
        )
        try {
            model.updateQuery("记忆")
            val state = model.state as SearchUiState.Failed
            assertTrue("文案应带原因: " + state.message, state.message.contains("db not ready"))
        } finally {
            model.dispose()
        }
    }

    @Test
    fun `失败后重试用上次提交的词并重新出结果`() {
        val repository = RecordingRepository(
            titles = listOf(book(1L, "深空")),
            failure = IllegalStateException("disk I/O error"),
        )
        val model = viewModel { repository }
        try {
            model.updateQuery("记忆")
            assertTrue(model.state is SearchUiState.Failed)
            repository.failure = null
            repository.hits = listOf(hit(1L, pdfLocator(0)))
            repository.titles = listOf(book(1L, "深空"))
            model.retry()
            assertEquals(listOf("记忆", "记忆"), repository.searchedQueries())
            assertEquals("记忆", (model.state as SearchUiState.Results).query)
        } finally {
            model.dispose()
        }
    }

    @Test
    fun `重试不再等防抖窗口`() {
        val repository = RecordingRepository(
            hits = listOf(hit(1L, pdfLocator(0))),
            titles = listOf(book(1L, "深空")),
        )
        val model = viewModel(repositoryProvider = { repository }, debounceMillis = 5_000L)
        try {
            model.updateQuery("记忆")
            assertEquals(SearchUiState.Editing("记忆"), model.state)
            model.retry()
            assertEquals("重试应立刻查库，不等 5 秒防抖", listOf("记忆"), repository.searchedQueries())
            assertTrue(model.state is SearchUiState.Results)
        } finally {
            model.dispose()
        }
    }

    @Test
    fun `没有查询词时重试是空操作`() {
        val repository = RecordingRepository()
        val model = viewModel { repository }
        try {
            model.retry()
            assertEquals(SearchUiState.Idle, model.state)
            assertTrue(repository.searchedQueries().isEmpty())
        } finally {
            model.dispose()
        }
    }

    // ------------------------------------------------------------------
    // 纯函数：位置文案
    // ------------------------------------------------------------------

    @Test
    fun `位置文案把 0 基下标翻成 1 基`() {
        assertEquals(
            "ch01.xhtml · 第 5 段",
            locatorPositionLabel(ReaderLocator.Epub(chapterHref = "OEBPS/ch01.xhtml", blockIndex = 4).encode()),
        )
        assertEquals("第 13 页", locatorPositionLabel(ReaderLocator.Pdf(page = 12).encode()))
        assertEquals("第 1 字", locatorPositionLabel(ReaderLocator.Text(offset = 0).encode()))
        assertEquals(
            "ch02.xhtml · 第 4 字",
            locatorPositionLabel(ReaderLocator.Html("OEBPS/ch02.xhtml", "frag", offset = 3).encode()),
        )
        assertEquals("全书 43%", locatorPositionLabel(ReaderLocator.Scroll(0.428f).encode()))
    }

    @Test
    fun `老格式 locator 也能解出位置`() {
        assertEquals("第 4 页", locatorPositionLabel("""{"kind":"pdf","page":3}"""))
        assertEquals("第 101 字", locatorPositionLabel("text|100"))
    }

    @Test
    fun `解不出的位置短显原串且截断到 24 字`() {
        assertEquals("位置未知", locatorPositionLabel(""))
        assertEquals("位置未知", locatorPositionLabel("   "))
        val label = locatorPositionLabel("x".repeat(40))
        assertEquals(25, label.length)
        assertTrue(label.endsWith("…"))
    }

    // ------------------------------------------------------------------
    // 纯函数：分组
    // ------------------------------------------------------------------

    @Test
    fun `分组函数对空结果返回空列表`() {
        assertTrue(groupSearchHits(emptyList(), emptyMap()).isEmpty())
    }

    @Test
    fun `分组函数把缺失书名换成占位名`() {
        val groups = groupSearchHits(listOf(hit(5L, pdfLocator(0))), emptyMap())
        assertEquals(5L, groups.single().bookId)
        assertEquals(SearchViewModel.UNKNOWN_BOOK_TITLE, groups.single().title)
    }

    @Test
    fun `分组函数把空白书名换成占位名`() {
        val groups = groupSearchHits(listOf(hit(5L, pdfLocator(0))), mapOf(5L to "   "))
        assertEquals(SearchViewModel.UNKNOWN_BOOK_TITLE, groups.single().title)
    }

    // ------------------------------------------------------------------
    // 纯函数：高亮
    // ------------------------------------------------------------------

    @Test
    fun `高亮标出片段里所有出现的中文词`() {
        assertEquals(
            listOf(HighlightSpan(0, 2), HighlightSpan(6, 8)),
            highlightSpans("记忆宫殿里的记忆", "记忆"),
        )
    }

    @Test
    fun `高亮忽略大小写并支持多词`() {
        assertEquals(
            listOf(HighlightSpan(0, 5), HighlightSpan(10, 15)),
            highlightSpans("Magic and MAGIC", "magic"),
        )
        assertEquals(
            listOf(HighlightSpan(0, 3), HighlightSpan(11, 17)),
            highlightSpans("the memory palace", "the palace"),
        )
    }

    @Test
    fun `相邻命中合并成一个区间`() {
        assertEquals(listOf(HighlightSpan(0, 4)), highlightSpans("aaaa", "aa"))
    }

    @Test
    fun `空文本或空查询没有高亮`() {
        assertTrue(highlightSpans("", "记忆").isEmpty())
        assertTrue(highlightSpans("有内容", "   ").isEmpty())
    }

    // ------------------------------------------------------------------
    // 纯函数：失败文案
    // ------------------------------------------------------------------

    @Test
    fun `失败文案保留原始异常信息`() {
        assertEquals("搜索失败：database is locked", searchErrorMessage(IllegalStateException("database is locked")))
        assertEquals("搜索失败，请稍后重试。", searchErrorMessage(RuntimeException()))
        assertEquals("搜索失败，请稍后重试。", searchErrorMessage(RuntimeException("   ")))
    }
}

/**
 * 测试用的 [SearchViewModel] 工厂：默认 Unconfined + 0 防抖，让整条链路同步完成。
 * 需要验证「等待/并发」的用例再显式传 debounceMillis / ioDispatcher 覆盖。
 */
private fun viewModel(
    debounceMillis: Long = 0L,
    ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    // 放最后：这样 `viewModel { repository }` 的尾随 lambda 能直接绑到它。
    repositoryProvider: () -> LibraryRepository,
): SearchViewModel = SearchViewModel(
    scope = scope,
    repositoryProvider = repositoryProvider,
    shelfRows = { repositoryProvider().books() },
    debounceMillis = debounceMillis,
    ioDispatcher = ioDispatcher,
)

/** 命中构造器：docType 与线上 EPUB 索引一致（本测试只关心 locator/snippet）。 */
private fun hit(bookId: Long, locator: String, snippet: String = "片段"): SearchHit =
    SearchHit(bookId = bookId, docType = "epub", locator = locator, snippet = snippet)

private fun book(id: Long, title: String): BookRow =
    BookRow(id = id, stableKey = "key-" + id, title = title)

private fun epubLocator(href: String = "OEBPS/ch01.xhtml", blockIndex: Int = 0): String =
    ReaderLocator.Epub(chapterHref = href, blockIndex = blockIndex).encode()

private fun pdfLocator(page: Int): String = ReaderLocator.Pdf(page = page).encode()

private fun textLocator(offset: Int): String = ReaderLocator.Text(offset = offset).encode()

/** 轮询等待，不假睡固定时长；超时后由 assertTrue 报出带上下文的失败信息。 */
private fun waitUntil(message: String, timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (predicate()) return
        Thread.sleep(10L)
    }
    assertTrue(message, predicate())
}

/**
 * 记录调用并允许注入结果的假仓库。
 *
 * 覆盖两种搜索入口与 [LibraryRepository.books]，其余方法（书架、标注、下载、
 * 事务）全部委托给 [TestLibraryRepository]，避免为测试重抄一遍接口。
 */
private class RecordingRepository(
    private val delegate: TestLibraryRepository = TestLibraryRepository(),
    var hits: List<SearchHit> = emptyList(),
    var hitsByQuery: Map<String, List<SearchHit>> = emptyMap(),
    var titles: List<BookRow> = emptyList(),
    var failure: Throwable? = null,
) : LibraryRepository by delegate {

    private val lock = Any()
    private val queries = ArrayList<String>()
    private val limits = ArrayList<Int>()

    /** 在真正返回结果前执行（用来制造阻塞/并发窗口）。 */
    var onSearch: ((String) -> Unit)? = null

    fun searchedQueries(): List<String> = synchronized(lock) { queries.toList() }

    fun searchedLimits(): List<Int> = synchronized(lock) { limits.toList() }

    override fun search(query: String, limit: Int): List<SearchHit> = recordedHits(query, limit).take(limit)

    override fun searchInBooks(query: String, bookIds: Set<Long>, limit: Int): List<SearchHit> {
        if (bookIds.isEmpty() || limit <= 0) return emptyList()
        return recordedHits(query, limit)
            .asSequence()
            .filter { it.bookId in bookIds }
            .take(limit)
            .toList()
    }

    private fun recordedHits(query: String, limit: Int): List<SearchHit> {
        synchronized(lock) {
            queries.add(query)
            limits.add(limit)
        }
        onSearch?.invoke(query)
        failure?.let { throw it }
        return hitsByQuery[query] ?: hits
    }

    override fun books(): List<BookRow> = titles
}
