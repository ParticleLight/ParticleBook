package app.particlebook.android.data

import app.particlebook.android.reader.ReaderLocator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serializes shelf refresh and progress removal; the coordinator owns writes after hydration. */
internal class ProgressCatalog(
    private val writerScope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val onWriteFailure: (Exception) -> Unit = {},
) {
    private val mutex = Mutex()
    private val published = MutableStateFlow<Map<Long, ProgressSnapshot>>(emptyMap())
    private val retired = mutableSetOf<Long>()
    private val pendingRemovals = LinkedHashMap<Long, BookRow>()
    @Volatile private var coordinator: ProgressCoordinator? = null

    val snapshots = published.asStateFlow()

    suspend fun initialize(
        repository: () -> LibraryRepository,
        latestRows: () -> List<BookRow>,
        readPreferences: () -> Map<String, Any?>,
    ) = withContext(io) {
        mutex.withLock {
            val repo = repository()
            val rows = latestRows().filterNot { it.id in retired }
            val writer = coordinator
            applyRemovals(repo, rows, writer)
            val ids = repo.transaction { CatalogSync.syncBookIds(it, rows) }
            check(ids.keys.containsAll(rows.map { it.id })) { "progress catalog sync omitted a book" }
            if (writer != null) {
                writer.replaceBookIds(ids)
            } else {
                val initial = ProgressBootstrap.load(repo, readPreferences, ids::get)
                coordinator = ProgressCoordinator(
                    store = SqliteProgressStore(repo, initial),
                    initial = initial,
                    scope = writerScope,
                    initialBookIds = ids,
                    snapshotSink = published,
                    onWriteFailure = onWriteFailure,
                    initiallyRetiredIds = retired,
                )
            }
        }
    }

    /** Once the shelf commits, finish the deletion even if the caller's UI scope is cancelled. */
    suspend fun clear(
        removed: List<BookRow>,
        repository: () -> LibraryRepository,
        latestRows: () -> List<BookRow>,
    ): Int = withContext(NonCancellable + io) {
        mutex.withLock {
            val count = removed.count { it.id in published.value }
            for (row in removed) {
                if (retired.add(row.id)) pendingRemovals[row.id] = row
            }
            if (pendingRemovals.isNotEmpty()) {
                val repo = repository()
                val remaining = latestRows().filterNot { it.id in retired }
                applyRemovals(repo, remaining, coordinator)
            }
            count
        }
    }

    /**
     * 全清所有书的进度（「清除阅读记录」），返回是否成功。
     *
     * 与 [clear] 的分工：[clear] 是书架提交后的**定向**删除——按 stable_key 解析别名、只删被移除的
     * 书、还要和 pendingRemovals / retired 对齐；这里是用户主动的**全清**，不关心书架、不动 retired。
     *
     * 为什么走写者队列而不是直接开事务：进度写入是单写者模型，只有排在队列里才能保证
     * 「先入队的 Put 先落盘、清空最后执行」，最终库里一行不剩。写者还没建（首次 [initialize]
     * 之前）时没有排队中的数据，用一个临时 [SqliteProgressStore] 做同一条事务即可。
     *
     * 失败时（打不开库、DELETE 抛错）异常向上抛；若有写者，[ProgressCoordinator.clearAll] 会把
     * 内存快照恢复成清除前的样子。
     */
    suspend fun clearAll(repository: () -> LibraryRepository): Boolean = withContext(NonCancellable + io) {
        mutex.withLock {
            val writer = coordinator
            if (writer != null) {
                writer.clearAll()
            } else {
                SqliteProgressStore(repository()).clearAll()
                true
            }
        }
    }

    private suspend fun applyRemovals(repo: LibraryRepository, remaining: List<BookRow>, writer: ProgressCoordinator?) {
        if (pendingRemovals.isEmpty()) return
        val deleted = pendingRemovals.keys.toSet()
        // A Book may have disappeared from the active catalog before it ever had an alias there.
        val (resolved, survivors) = repo.transaction { tx ->
            val ids = CatalogSync.resolveBookIds(tx, pendingRemovals.values + remaining)
            val removedIds = ids.filterKeys { it in deleted }
            val survivingIds = ids.filterKeys { it !in deleted }.values.toSet()
            if (writer == null) tx.deleteProgress(removedIds.values.toSet() - survivingIds)
            removedIds to survivingIds
        }
        if (writer != null) {
            // Use the current shelf to protect aliases, but never replace the active catalog from clear().
            writer.forgetBooks(deleted, resolved, survivors)
            check(writer.flush()) { "SQLite progress deletion still pending" }
        }
        pendingRemovals.clear()
    }

    fun enqueue(bookId: Long, locator: ReaderLocator, page: Int, total: Int) {
        coordinator?.enqueueLocation(bookId, locator, page, total)
    }

    suspend fun flush(): Boolean = coordinator?.flush() ?: true
}
