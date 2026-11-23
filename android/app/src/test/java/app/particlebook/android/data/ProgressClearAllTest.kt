package app.particlebook.android.data

import app.particlebook.android.reader.ReaderLocator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「清除阅读记录」的全清语义：progress + reading_sessions 一起清，且清除后重启不会被旧 prefs 灌回来。
 *
 * 覆盖点：
 * - repository 的两个 DELETE 返回受影响行数，第二次为 0；
 * - [SqliteProgressStore.clearAll] 在一条事务里删行并把回填标记保持为真（标记未写过时补写成真）；
 * - [ProgressCatalog.clearAll] 走写者队列：先入队的旧位置先落盘、清空最后执行，快照全空；
 * - 失败时内存快照恢复成清除前的样子，标记不被提前写；
 * - 重复清除幂等（第二次 0 行仍返回 true）。
 */
class ProgressClearAllTest {

    /** 把写者卡在半路的闸门，用来证明清空命令排在已入队的 Put 之后。 */
    private class Gate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        suspend fun waitHere() {
            entered.complete(Unit)
            release.await()
        }
    }

    /** 只做进度语义的内存 store；clearAll 可注入失败，save 可被 gate 卡住。 */
    private class GateableStore : ProgressStore {
        val rows = LinkedHashMap<Long, ProgressSnapshot>()
        val operations = mutableListOf<String>()
        var beforeSave: suspend (ProgressSnapshot) -> Unit = {}
        var clearFailure: Exception? = null

        override fun snapshot(bookId: Long): ProgressSnapshot? = rows[bookId]

        override fun snapshots(): Map<Long, ProgressSnapshot> = rows.toMap()

        override suspend fun hydrate(): Map<Long, ProgressSnapshot> = snapshots()

        override suspend fun save(snapshot: ProgressSnapshot) {
            operations += "put:" + snapshot.bookId
            beforeSave(snapshot)
            rows[snapshot.bookId] = snapshot
        }

        override suspend fun saveAll(snapshots: Collection<ProgressSnapshot>) = snapshots.forEach { save(it) }

        override suspend fun delete(bookIds: Set<Long>) {
            operations += "delete:" + bookIds.sorted().joinToString(",")
            rows.keys.removeAll(bookIds)
        }

        override suspend fun clearAll(): Int {
            operations += "clearAll"
            clearFailure?.let { throw it }
            val removed = rows.size
            rows.clear()
            return removed
        }

        override suspend fun flush() {
            operations += "flush"
        }
    }

    /**
     * 可以拦住整表 DELETE 的 repository 包装。
     *
     * [TestLibraryRepository.transaction] 把 `this`（它自己）交给事务块，直接覆写委托对象的
     * `deleteAllProgress` 会被绕过；这里连 `transaction` 一起覆写，让事务块的 receiver 是本包装器，
     * 才能验证「DELETE 抛错 → 事务回滚、标记不提前写」。[deleteBehavior] 为 null 表示模拟 SQLite 报错。
     */
    private class DelegatingRepository(
        private val delegate: LibraryRepository,
        private val deleteBehavior: (() -> Int)? = null,
    ) : LibraryRepository by delegate {
        var deleteCalls = 0
        var rowsRemoved = 0

        override fun deleteAllProgress(): Int {
            deleteCalls += 1
            val removed = deleteBehavior?.invoke() ?: throw IllegalStateException("sqlite busy")
            rowsRemoved += removed
            return removed
        }

        override fun <T> transaction(block: (LibraryRepository) -> T): T = block(this)
    }

    private fun pdf(page: Int, id: Long = 1L) = ProgressSnapshot(
        bookId = id,
        locatorJson = ReaderLocator.Pdf(page).encode(),
        page = page,
        totalPages = 10,
    )

    private fun book(id: Long, format: String = "pdf") =
        BookRow(id = id, stableKey = "legacy:" + id, format = format)

    @Test(timeout = 5_000)
    fun repositoryDeletesEverythingAndReportsRowCounts() {
        val repository = TestLibraryRepository()
        repository.saveBook(book(1))
        repository.saveBook(book(2))
        repository.saveProgress(ProgressRow(1, ReaderLocator.Pdf(1).encode(), 1, 10))
        repository.saveProgress(ProgressRow(2, ReaderLocator.Pdf(3).encode(), 3, 10))
        repository.saveReadingSession(ReadingSessionRow(bookId = 1, startedAt = 10, endedAt = 20, durationMs = 10))
        repository.markProgressBackfillComplete()

        assertEquals(2, repository.deleteAllProgress())
        assertEquals(0, repository.deleteAllProgress())
        assertEquals(1, repository.deleteAllReadingSessions())
        assertEquals(0, repository.deleteAllReadingSessions())

        assertTrue(repository.progressAll().isEmpty())
        assertTrue(repository.readingSessionsSince(0L).isEmpty())
        // DELETE 只删行；标记由 store 的事务负责，这里不该被顺手改掉。
        assertTrue(repository.progressBackfillComplete())
    }

    @Test(timeout = 5_000)
    fun storeClearAllKeepsTheBackfillTombstoneSoARestartCannotRefill() = runBlocking {
        val repository = TestLibraryRepository()
        repository.saveBook(book(7))
        repository.saveProgress(ProgressRow(7, ReaderLocator.Pdf(2).encode(), 2, 10, updatedAt = 5))
        // 首次清除可能早于首次回填：标记还是假的。
        assertFalse(repository.progressBackfillComplete())

        val initial = mapOf(7L to ProgressSnapshot.fromRow(repository.progress(7)!!))
        val store = SqliteProgressStore(repository, initial)

        assertEquals(1, store.clearAll())
        assertEquals(0, store.clearAll())

        assertTrue(store.snapshots().isEmpty())
        assertTrue(repository.progressAll().isEmpty())
        // 同事务补写成真：等价于「旧 prefs 已合并，合并结果为空」。
        assertTrue(repository.progressBackfillComplete())
        // 重启路径：标记为真 → ProgressBootstrap 不该再读 pb_reading。
        assertTrue(ProgressBootstrap.load(repository, { error("标记为真时不该读 pb_reading") }).isEmpty())
    }

    @Test(timeout = 5_000)
    fun failedDeleteRollsBackRowsAndDoesNotWriteTheMarkerEarly() = runBlocking {
        val repository = TestLibraryRepository()
        repository.saveBook(book(7))
        repository.saveProgress(ProgressRow(7, ReaderLocator.Pdf(2).encode(), 2, 10))
        val failing = DelegatingRepository(repository)
        val store = SqliteProgressStore(failing, mapOf(7L to ProgressSnapshot.fromRow(repository.progress(7)!!)))

        val failure = runCatching { store.clearAll() }

        assertTrue(failure.isFailure)
        assertEquals(1, repository.progressAll().size)
        // 标记写在同一事务里、且排在 DELETE 之后，所以这里必须还是假的。
        assertFalse(repository.progressBackfillComplete())
    }

    @Test(timeout = 5_000)
    fun catalogClearAllEmptiesSnapshotsAndSurvivesRestartWithoutRefill() = runBlocking {
        val repository = TestLibraryRepository()
        repository.saveBook(book(7))
        repository.saveBook(book(8))
        repository.saveProgress(ProgressRow(7, ReaderLocator.Pdf(2).encode(), 2, 10, updatedAt = 50))
        val prefs = mapOf("page_8" to 4, "pages_8" to 10, "last_read_8" to 99L)
        val writer = Job()
        try {
            val catalog = ProgressCatalog(CoroutineScope(coroutineContext + writer), Dispatchers.Unconfined)
            catalog.initialize({ repository }, { listOf(book(7), book(8)) }, { prefs })
            // 一本来自库里的行，一本来自旧 prefs 回填。
            assertEquals(2, catalog.snapshots.value.size)
            assertEquals(4, catalog.snapshots.value[8]?.page)
            assertTrue(repository.progressBackfillComplete())

            assertTrue(catalog.clearAll { repository })

            assertTrue(catalog.snapshots.value.isEmpty())
            assertNull(catalog.snapshots.value[7])
            assertNull(catalog.snapshots.value[8])
            assertTrue(repository.progressAll().isEmpty())
            assertTrue(repository.progressBackfillComplete())

            // 「重启」：新 catalog 只能读库，标记为真意味着 prefs 根本不会被打开。
            val restarted = ProgressCatalog(CoroutineScope(coroutineContext + writer), Dispatchers.Unconfined)
            restarted.initialize({ repository }, { listOf(book(7), book(8)) }, { error("标记为真时不该读 pb_reading") })
            assertTrue(restarted.snapshots.value.isEmpty())
            assertTrue(repository.progressAll().isEmpty())
        } finally {
            writer.cancel()
        }
    }

    @Test(timeout = 5_000)
    fun catalogClearAllBeforeFirstHydrationAlsoBlocksTheRefill() = runBlocking {
        val repository = TestLibraryRepository()
        repository.saveBook(book(7))
        repository.saveProgress(ProgressRow(7, ReaderLocator.Pdf(2).encode(), 2, 10))
        assertFalse(repository.progressBackfillComplete())
        val writer = Job()
        try {
            val catalog = ProgressCatalog(CoroutineScope(coroutineContext + writer), Dispatchers.Unconfined)
            // 写者还没建：没有排队中的数据，走临时 store 的同一条事务。
            assertTrue(catalog.clearAll { repository })
            assertTrue(repository.progressAll().isEmpty())
            assertTrue(repository.progressBackfillComplete())

            catalog.initialize({ repository }, { listOf(book(7)) }, { error("标记为真时不该读 pb_reading") })
            assertTrue(catalog.snapshots.value.isEmpty())
        } finally {
            writer.cancel()
        }
    }

    @Test(timeout = 5_000)
    fun catalogClearAllIsIdempotentAndTheSecondCallDeletesZeroRows() = runBlocking {
        val repository = TestLibraryRepository()
        repository.saveBook(book(1))
        repository.saveProgress(ProgressRow(1, ReaderLocator.Pdf(4).encode(), 4, 10))
        repository.markProgressBackfillComplete()
        val counting = DelegatingRepository(repository) { repository.deleteAllProgress() }
        val writer = Job()
        try {
            val catalog = ProgressCatalog(CoroutineScope(coroutineContext + writer), Dispatchers.Unconfined)
            catalog.initialize({ counting }, { listOf(book(1)) }, { emptyMap() })
            assertEquals(4, catalog.snapshots.value[1]?.page)

            assertTrue(catalog.clearAll { counting })
            assertTrue(catalog.clearAll { counting })

            assertEquals(2, counting.deleteCalls)
            assertEquals(1, counting.rowsRemoved)
            assertTrue(catalog.snapshots.value.isEmpty())
            assertTrue(counting.progressBackfillComplete())
        } finally {
            writer.cancel()
        }
    }

    @Test(timeout = 5_000)
    fun clearAllRunsAfterQueuedWritesSoTheStoreEndsUpEmpty() = runBlocking {
        val store = GateableStore()
        val gate = Gate()
        store.beforeSave = { gate.waitHere() }
        val coordinator = ProgressCoordinator(store, emptyMap(), this)

        assertTrue(coordinator.enqueue(pdf(1)))
        gate.entered.await()
        val clearing = async(start = CoroutineStart.UNDISPATCHED) { coordinator.clearAll() }
        // 命令已入队：界面立刻归零，不等落库。
        assertNull(coordinator.snapshot(1))

        gate.release.complete(Unit)
        assertTrue(clearing.await())

        // 先入队的 Put 先落盘，清空最后执行 —— 顺序就是「不复活」的证据。
        assertEquals(listOf("put:1", "clearAll"), store.operations)
        assertTrue(store.rows.isEmpty())
        assertNull(store.snapshot(1))
        assertNull(coordinator.snapshot(1))

        // 清空之后的新位置照常重建进度。
        store.beforeSave = {}
        assertTrue(coordinator.enqueue(pdf(3)))
        assertTrue(coordinator.flush())
        assertEquals(3, coordinator.snapshot(1)?.page)
        assertEquals(3, store.snapshot(1)?.page)
        assertTrue(coordinator.close())
    }

    @Test(timeout = 5_000)
    fun failedClearAllRestoresThePreClearSnapshotUnderEveryAlias() = runBlocking {
        val store = GateableStore().apply {
            rows[70L] = pdf(9, 70)
            clearFailure = IllegalStateException("sqlite busy")
        }
        val errors = mutableListOf<Exception>()
        val coordinator = ProgressCoordinator(
            store,
            store.snapshots(),
            this,
            mapOf(42L to 70L, 43L to 70L),
            onWriteFailure = { errors += it },
        )

        assertFalse(coordinator.clearAll())

        assertEquals(1, errors.size)
        assertEquals(9, coordinator.snapshot(42)?.page)
        assertEquals(9, coordinator.snapshot(43)?.page)
        assertEquals(9, store.snapshot(70)?.page)

        store.clearFailure = null
        assertTrue(coordinator.clearAll())
        assertNull(coordinator.snapshot(42))
        assertNull(coordinator.snapshot(43))
        assertTrue(store.rows.isEmpty())
        assertTrue(coordinator.close())
    }

    @Test(timeout = 5_000)
    fun catalogClearAllBeforeFirstHydrationPropagatesAnOpenFailure() = runBlocking {
        val repository = TestLibraryRepository()
        repository.saveBook(book(7))
        repository.saveProgress(ProgressRow(7, ReaderLocator.Pdf(2).encode(), 2, 10))
        val writer = Job()
        try {
            val catalog = ProgressCatalog(CoroutineScope(coroutineContext + writer), Dispatchers.Unconfined)
            val failure = runCatching { catalog.clearAll { error("database opening failed") } }
            assertTrue(failure.isFailure)
            assertEquals(2, repository.progress(7)?.page)
        } finally {
            writer.cancel()
        }
    }
}
