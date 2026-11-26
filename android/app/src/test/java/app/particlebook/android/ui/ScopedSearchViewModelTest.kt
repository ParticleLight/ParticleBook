package app.particlebook.android.ui

import app.particlebook.android.data.BookRow
import app.particlebook.android.data.LibraryRepository
import app.particlebook.android.data.SearchHit
import app.particlebook.android.data.TestLibraryRepository
import app.particlebook.android.reader.ReaderLocator
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 当前书架搜索域的 JVM 回归：身份解析和命中均走真实 TestLibraryRepository。
 * delegation 只记录调用、注入失败或暂停已算出的结果；SQLite SQL 由设备验收另证。
 * 在途测试用 latch 控制返回顺序，并 join 旧 Job 后断言，避免固定睡眠掩盖迟到结果。
 */
class ScopedSearchViewModelTest {

    @Test
    fun `历史索引保留但已离开书架的书不再出现`() {
        val repository = TestLibraryRepository()
        val removed = indexedBook(repository, 701L, "hash:removed", "历史书")
        val current = indexedBook(repository, 702L, "hash:current", "有效书")
        val rows = listOf(current.copy(id = 42L))
        val model = scopedModel({ repository }, { rows })
        try {
            assertEquals(listOf(removed.id, current.id), repository.search(WORD).map { it.bookId })
            model.updateQuery(WORD)
            val state = model.state as SearchUiState.Results
            assertEquals(listOf(42L), state.groups.map { it.bookId })
            assertEquals(listOf(42L), state.groups.single().hits.map { it.hit.bookId })
            assertEquals(1, state.hitCount)
            assertEquals(listOf(removed.id, current.id), repository.search(WORD).map { it.bookId })
            assertEquals(listOf(removed, current), repository.books())
        } finally {
            model.dispose()
        }
    }

    @Test
    fun `canonical命中映射成当前UI身份后可按书架找到原书`() {
        val repository = TestLibraryRepository()
        val canonical = indexedBook(repository, 701L, "hash:shared", "原书")
        val shelfBook = canonical.copy(id = 7L, filePath = "/current/original.epub")
        val rows = listOf(shelfBook)
        val rawHit = repository.search(WORD).single()
        val model = scopedModel({ repository }, { rows })
        try {
            model.updateQuery(WORD)
            val group = (model.state as SearchUiState.Results).groups.single()
            val hit = group.hits.single().hit
            assertEquals(7L, group.bookId)
            assertEquals(rawHit.copy(bookId = shelfBook.id), hit)
            // 与 AppRoot.onOpenHit 相同的当前书架查找，locator 和正文仍属于 canonical。
            assertEquals(shelfBook, rows.single { it.id == hit.bookId })
        } finally {
            model.dispose()
        }
    }

    @Test
    fun `数字ID相撞时只按stable身份检索有效原书`() {
        val repository = TestLibraryRepository()
        indexedBook(repository, 42L, "hash:unrelated", "同号历史书", "$WORD unrelated")
        val canonical = indexedBook(repository, 701L, "hash:original", "有效原书", "$WORD original")
        val recorded = RecordingScopedRepository(repository)
        val model = scopedModel({ recorded }, { listOf(canonical.copy(id = 42L)) })
        try {
            model.updateQuery(WORD)
            val state = model.state as SearchUiState.Results
            assertEquals(listOf(42L), state.groups.map { it.bookId })
            assertEquals("$WORD original", state.groups.single().hits.single().hit.snippet)
            assertEquals(listOf(setOf(canonical.id)), recorded.calls().map { it.bookIds })
            assertEquals(0, recorded.rawCalls.get())
            assertEquals(2, repository.bookCount())
        } finally {
            model.dispose()
        }
    }

    @Test
    fun `未解析身份即使与历史书同号也不回退全库`() {
        val repository = TestLibraryRepository()
        val historical = indexedBook(repository, 42L, "hash:historical", "历史书")
        val unresolved = historical.copy(stableKey = "hash:not-synced", title = "尚未同步")
        val recorded = RecordingScopedRepository(repository)
        val model = scopedModel({ recorded }, { listOf(unresolved) })
        try {
            model.updateQuery(WORD)
            assertEquals(SearchUiState.Empty(WORD), model.state)
            assertEquals(0, recorded.rawCalls.get())
            assertTrue(recorded.calls().all { it.bookIds.isEmpty() })
            assertEquals(listOf(historical.id), repository.search(WORD).map { it.bookId })
            assertEquals(listOf(historical), repository.books())
        } finally {
            model.dispose()
        }
    }

    @Test
    fun `空stable身份跳过且同域有效书仍能搜索`() {
        val repository = TestLibraryRepository()
        val invalid = indexedBook(repository, 42L, "", "缺身份的旧行")
        val canonical = indexedBook(repository, 701L, "hash:valid", "有效书")
        val recorded = RecordingScopedRepository(repository)
        val rows = listOf(invalid.copy(stableKey = "  "), canonical.copy(id = 7L))
        val model = scopedModel({ recorded }, { rows })
        try {
            model.updateQuery(WORD)
            val state = model.state as SearchUiState.Results
            assertEquals(listOf(7L), state.groups.map { it.bookId })
            assertEquals(listOf(setOf(canonical.id)), recorded.calls().map { it.bookIds })
            assertEquals(0, recorded.rawCalls.get())
            assertEquals(2, repository.search(WORD).size)
        } finally {
            model.dispose()
        }
    }

    @Test
    fun `同canonical只选首个UI别名且删除首个后第二个仍可用`() {
        val repository = TestLibraryRepository()
        val canonical = indexedBook(repository, 701L, "hash:shared", "数据库书名")
        val first = canonical.copy(id = 7L, title = "第一别名")
        val second = canonical.copy(id = 8L, title = "第二别名")
        var rows = listOf(first, second)
        val recorded = RecordingScopedRepository(repository)
        val model = scopedModel({ recorded }, { rows })
        try {
            model.updateQuery(WORD)
            val initial = model.state as SearchUiState.Results
            assertEquals(1, initial.hitCount)
            assertEquals(7L, initial.groups.single().bookId)
            assertEquals("第一别名", initial.groups.single().title)
            assertEquals(7L, initial.groups.single().hits.single().hit.bookId)

            rows = listOf(second)
            model.updateQuery(model.query)
            val remaining = model.state as SearchUiState.Results
            assertEquals(1, remaining.hitCount)
            assertEquals(8L, remaining.groups.single().bookId)
            assertEquals("第二别名", remaining.groups.single().title)
            assertEquals(second, rows.single { it.id == remaining.groups.single().hits.single().hit.bookId })
            assertEquals(listOf(setOf(701L), setOf(701L)), recorded.calls().map { it.bookIds })
            assertEquals(listOf(canonical), repository.books())
            assertEquals(1, repository.search(WORD).size)
        } finally {
            model.dispose()
        }
    }

    @Test
    fun `书架改名后同词重查显示最新标题而不改数据库行`() {
        val repository = TestLibraryRepository()
        val canonical = indexedBook(repository, 701L, "hash:renamed", "旧数据库标题")
        var rows = listOf(canonical.copy(id = 7L, title = "当前书架标题"))
        val model = scopedModel({ repository }, { rows })
        try {
            model.updateQuery(WORD)
            assertEquals("当前书架标题", (model.state as SearchUiState.Results).groups.single().title)
            rows = listOf(rows.single().copy(title = "再次改名"))
            model.updateQuery(model.query)
            assertEquals("再次改名", (model.state as SearchUiState.Results).groups.single().title)
            assertEquals(canonical, repository.bookById(canonical.id))
        } finally {
            model.dispose()
        }
    }

    @Test
    fun `当前书架为空时直接Empty且不打开仓库`() {
        val providerCalls = AtomicInteger()
        val model = scopedModel(
            repositoryProvider = {
                providerCalls.incrementAndGet()
                error("empty shelf must not open repository")
            },
            shelfRows = { emptyList() },
        )
        try {
            model.updateQuery(WORD)
            assertEquals(SearchUiState.Empty(WORD), model.state)
            assertEquals(WORD, model.lastCommittedQuery)
            assertEquals(0, providerCalls.get())
        } finally {
            model.dispose()
        }
    }

    @Test
    fun `书架行在进入IO前复制快照而不随可变列表漂移`() {
        val repository = TestLibraryRepository()
        val first = indexedBook(repository, 701L, "hash:first", "第一本").copy(id = 7L)
        val second = indexedBook(repository, 702L, "hash:second", "第二本").copy(id = 8L)
        val rows = mutableListOf(first)
        val dispatcher = QueuedIoDispatcher()
        val recorded = RecordingScopedRepository(repository)
        val model = scopedModel({ recorded }, { rows }, ioDispatcher = dispatcher)
        try {
            model.updateQuery(WORD)
            assertEquals(SearchUiState.Searching(WORD), model.state)
            assertTrue(recorded.calls().isEmpty())
            rows.clear()
            rows += second
            dispatcher.runQueued()
            val state = model.state as SearchUiState.Results
            assertEquals(listOf(7L), state.groups.map { it.bookId })
            assertEquals("第一本", state.groups.single().title)
            assertEquals(listOf(setOf(701L)), recorded.calls().map { it.bookIds })
        } finally {
            model.dispose()
            dispatcher.runQueued()
        }
    }

    @Test
    fun `相同query换书架后旧scope迟到结果不覆盖新scope`() {
        val repository = TestLibraryRepository()
        val first = indexedBook(repository, 701L, "hash:first", "旧范围").copy(id = 7L)
        val second = indexedBook(repository, 702L, "hash:second", "新范围").copy(id = 8L)
        var rows = listOf(first)
        val gate = SearchGate { it.bookIds == setOf(701L) }
        val recorded = RecordingScopedRepository(repository, gate::pause)
        val owner = SupervisorJob()
        val model = scopedModel({ recorded }, { rows }, CoroutineScope(owner + Dispatchers.Unconfined), Dispatchers.Default)
        try {
            model.updateQuery(WORD)
            gate.awaitStarted()
            val oldJob = owner.children.single()
            rows = listOf(second)
            model.updateQuery(model.query)
            owner.children.filter { it !== oldJob }.toList().forEach(::awaitCompleted)
            val expected = model.state as SearchUiState.Results
            assertEquals(WORD, expected.query)
            assertEquals(listOf(8L), expected.groups.map { it.bookId })
            assertEquals("新范围", expected.groups.single().title)
            gate.release()
            awaitCompleted(oldJob)
            assertEquals(expected, model.state)
            assertEquals(listOf(setOf(701L), setOf(702L)), recorded.calls().map { it.bookIds })
            assertEquals(0, recorded.rawCalls.get())
        } finally {
            gate.release()
            model.dispose()
            owner.cancel()
            awaitCompleted(owner)
        }
    }

    @Test
    fun `书架变空并重发同词后在途旧命中不会复活`() {
        val repository = TestLibraryRepository()
        val canonical = indexedBook(repository, 701L, "hash:removed", "已退出书架")
        var rows = listOf(canonical.copy(id = 7L))
        val gate = SearchGate { true }
        val recorded = RecordingScopedRepository(repository, gate::pause)
        val owner = SupervisorJob()
        val model = scopedModel({ recorded }, { rows }, CoroutineScope(owner + Dispatchers.Unconfined), Dispatchers.Default)
        try {
            model.updateQuery(WORD)
            gate.awaitStarted()
            val oldJob = owner.children.single()
            rows = emptyList()
            model.updateQuery(model.query)
            assertEquals(SearchUiState.Empty(WORD), model.state)
            gate.release()
            awaitCompleted(oldJob)
            assertEquals(SearchUiState.Empty(WORD), model.state)
            assertEquals(1, recorded.calls().size)
            assertEquals(0, recorded.rawCalls.get())
            assertEquals(listOf(canonical.id), repository.search(WORD).map { it.bookId })
        } finally {
            gate.release()
            model.dispose()
            owner.cancel()
            awaitCompleted(owner)
        }
    }

    @Test
    fun `同scope快速改词时旧query返回也不覆盖新词命中`() {
        val repository = TestLibraryRepository()
        val canonical = indexedBook(repository, 701L, "hash:current", "当前书", "$WORD old")
        val locator = ReaderLocator.Epub(chapterHref = "OEBPS/new.xhtml", blockIndex = 2).encode()
        repository.saveSearchDocument(canonical.id, "epub", locator, "tulip recent")
        val rows = listOf(canonical.copy(id = 7L))
        val gate = SearchGate { it.query == WORD }
        val recorded = RecordingScopedRepository(repository, gate::pause)
        val owner = SupervisorJob()
        val model = scopedModel({ recorded }, { rows }, CoroutineScope(owner + Dispatchers.Unconfined), Dispatchers.Default)
        try {
            model.updateQuery(WORD)
            gate.awaitStarted()
            val oldJob = owner.children.single()
            model.updateQuery("tulip")
            owner.children.filter { it !== oldJob }.toList().forEach(::awaitCompleted)
            val expected = model.state as SearchUiState.Results
            assertEquals("tulip", expected.query)
            assertEquals(SearchHit(7L, "epub", locator, "tulip recent"), expected.groups.single().hits.single().hit)
            gate.release()
            awaitCompleted(oldJob)
            assertEquals(expected, model.state)
            assertEquals(listOf(WORD, "tulip"), recorded.calls().map { it.query })
            assertEquals(0, recorded.rawCalls.get())
        } finally {
            gate.release()
            model.dispose()
            owner.cancel()
            awaitCompleted(owner)
        }
    }

    @Test
    fun `失败后重试重新读取当前书架而不保留失败时旧域`() {
        val repository = TestLibraryRepository()
        val first = indexedBook(repository, 701L, "hash:first", "失败时范围").copy(id = 7L)
        val second = indexedBook(repository, 702L, "hash:second", "重试时范围").copy(id = 8L)
        var rows = listOf(first)
        val failOnce = AtomicBoolean(true)
        val recorded = RecordingScopedRepository(repository) {
            if (failOnce.getAndSet(false)) error("index temporarily unavailable")
        }
        val model = scopedModel({ recorded }, { rows })
        try {
            model.updateQuery(WORD)
            val failed = model.state as SearchUiState.Failed
            assertTrue(failed.message.contains("index temporarily unavailable"))
            rows = listOf(second)
            model.retry()
            val recovered = model.state as SearchUiState.Results
            assertEquals(WORD, recovered.query)
            assertEquals(listOf(8L), recovered.groups.map { it.bookId })
            assertEquals("重试时范围", recovered.groups.single().title)
            assertEquals(listOf(setOf(701L), setOf(702L)), recorded.calls().map { it.bookIds })
            assertEquals(0, recorded.rawCalls.get())
            assertEquals(2, repository.bookCount())
        } finally {
            model.dispose()
        }
    }

    private fun scopedModel(
        repositoryProvider: () -> LibraryRepository,
        shelfRows: () -> List<BookRow>,
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
        ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): SearchViewModel = SearchViewModel(
        scope = scope,
        repositoryProvider = repositoryProvider,
        shelfRows = shelfRows,
        debounceMillis = 0L,
        ioDispatcher = ioDispatcher,
    )

    private fun indexedBook(
        repository: TestLibraryRepository,
        id: Long,
        stableKey: String,
        title: String,
        text: String = WORD,
    ): BookRow {
        val row = BookRow(id = id, stableKey = stableKey, title = title, filePath = "/books/$id.epub")
        repository.saveBook(row)
        val locator = ReaderLocator.Epub(chapterHref = "OEBPS/ch01.xhtml", blockIndex = 0).encode()
        repository.saveSearchDocument(id, "epub", locator, text)
        return row
    }

    private data class ScopedCall(val query: String, val bookIds: Set<Long>, val limit: Int)

    private class RecordingScopedRepository(
        private val delegate: TestLibraryRepository,
        private val afterQuery: (ScopedCall) -> Unit = {},
    ) : LibraryRepository by delegate {
        private val recorded = mutableListOf<ScopedCall>()
        val rawCalls = AtomicInteger()

        fun calls(): List<ScopedCall> = synchronized(recorded) { recorded.toList() }

        override fun search(query: String, limit: Int): List<SearchHit> {
            rawCalls.incrementAndGet()
            return delegate.search(query, limit)
        }

        override fun searchInBooks(query: String, bookIds: Set<Long>, limit: Int): List<SearchHit> {
            val call = ScopedCall(query, bookIds.toSet(), limit)
            synchronized(recorded) { recorded += call }
            val hits = delegate.searchInBooks(query, bookIds, limit)
            afterQuery(call)
            return hits
        }
    }

    private class SearchGate(private val matches: (ScopedCall) -> Boolean) {
        private val first = AtomicBoolean(true)
        private val started = CountDownLatch(1)
        private val released = CountDownLatch(1)

        fun pause(call: ScopedCall) {
            if (matches(call) && first.compareAndSet(true, false)) {
                started.countDown()
                check(released.await(5, TimeUnit.SECONDS)) { "test did not release paused search" }
            }
        }

        fun awaitStarted() {
            assertTrue("旧查询应先算出真实命中并进入暂停点", started.await(5, TimeUnit.SECONDS))
        }

        fun release() { released.countDown() }
    }

    private class QueuedIoDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }

        fun runQueued() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
    }

    private fun awaitCompleted(job: Job) = runBlocking {
        withTimeout(5_000L) { job.join() }
    }

    private companion object {
        const val WORD = "orchid"
    }
}
