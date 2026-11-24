package app.particlebook.android.data

import app.particlebook.android.reader.ReaderLocator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** page 为 0 基；EPUB totalPages 是章内总页数，PDF 是文档总页数。 */
data class ProgressSnapshot(
    val bookId: Long,
    val locatorJson: String = "",
    val page: Int? = null,
    val totalPages: Int? = null,
    val percent: Double? = null,
    val updatedAt: Long = 0L,
    val finishedAt: Long? = null,
) {
    fun toRow(): ProgressRow = ProgressRow(bookId, locatorJson, page, totalPages, percent, updatedAt, finishedAt)

    /** 解码失败只影响恢复，不修改用于诊断的原始字符串。 */
    fun locator(): ReaderLocator? = ReaderLocator.decode(locatorJson)?.takeIf {
        when (it) {
            is ReaderLocator.Pdf ->
                it.page >= 0 && it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f
            // 文本定位只有字符区间：负偏移会让阅读器滚到不存在的段落，按坏 locator 丢弃。
            is ReaderLocator.Text -> it.offset >= 0 && it.length >= 0
            else -> true
        }
    }

    fun percentLabel(): String? {
        val total = totalPages ?: 0
        if (total <= 1) return null
        val current = (page ?: 0).coerceIn(0, total - 1)
        return "已读 " + ((current.toLong() + 1) * 100 / total) + "%"
    }

    fun hasProgress(): Boolean = (totalPages ?: 0) > 1

    fun savedPage(): Int? {
        val total = totalPages ?: 0
        val current = page ?: return null
        return if (total > 0) current.coerceIn(0, total - 1) else null
    }

    fun fraction(): Float? {
        val total = totalPages ?: 0
        if (total <= 1) return null
        val current = (page ?: 0).coerceIn(0, total - 1)
        return ((current.toLong() + 1).toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    companion object {
        fun fromRow(row: ProgressRow): ProgressSnapshot = ProgressSnapshot(
            row.bookId, row.locatorJson, row.page, row.totalPages, row.percent, row.updatedAt, row.finishedAt,
        )
    }
}

/** 数据库方法在调用线程执行，调用方使用 IO dispatcher。 */
interface ProgressStore {
    fun snapshot(bookId: Long): ProgressSnapshot?
    fun snapshots(): Map<Long, ProgressSnapshot>
    suspend fun hydrate(): Map<Long, ProgressSnapshot>
    suspend fun save(snapshot: ProgressSnapshot)
    suspend fun saveAll(snapshots: Collection<ProgressSnapshot>)
    suspend fun delete(bookIds: Set<Long>)
    suspend fun flush()

    /**
     * 删除全部进度行，返回删除行数（「清除阅读记录」的全清语义，见 [ProgressCatalog.clearAll]）。
     *
     * 与 [delete] 的区别：[delete] 是按 id 的定向删除；这里是整表清空，且实现方必须保证
     * 「回填标记仍为真」，否则用户清空后重启会被旧 prefs 灌回来。
     * 默认实现按 [snapshots] 的键逐批删，只给不落 SQLite 的测试替身兜底；
     * 返回值按「快照条数」估算（替身看不到真实受影响行数），生产实现必须覆写成 DELETE 的返回值。
     */
    suspend fun clearAll(): Int {
        val ids = snapshots().keys
        if (ids.isEmpty()) return 0
        delete(ids)
        return ids.size
    }
}

/** SQLite 缓存只记已持久化结果；UI 的乐观状态由 ProgressCoordinator 单独管理。 */
class SqliteProgressStore(
    private val repository: LibraryRepository,
    initial: Map<Long, ProgressSnapshot>? = null,
    bookIds: Map<Long, Long>? = null,
) : ProgressStore {
    private val writeMutex = Mutex()
    private val mappingLock = Any()
    private var hydrated = initial != null

    @Volatile private var cache: Map<Long, ProgressSnapshot> = initial?.toMap().orEmpty()
    @Volatile private var uiToDatabase: Map<Long, Long>? = bookIds?.toMap()

    /** 旧映射保留至进程退出，让已经入队的删除仍能找到 SQLite 主键。 */
    fun registerBookIds(bookIds: Map<Long, Long>) = synchronized(mappingLock) {
        val previous = uiToDatabase.orEmpty()
        bookIds.forEach { (uiId, dbId) ->
            check(previous[uiId] == null || previous[uiId] == dbId) { "progress book identity changed: " + uiId }
        }
        uiToDatabase = previous + bookIds
    }

    override fun snapshot(bookId: Long): ProgressSnapshot? {
        val mapping = uiToDatabase
        val dbId = if (mapping == null) bookId else mapping[bookId] ?: return null
        return cache[dbId]?.copy(bookId = bookId)
    }

    override fun snapshots(): Map<Long, ProgressSnapshot> {
        val current = cache
        val mapping = uiToDatabase ?: return current
        return buildMap {
            mapping.forEach { (uiId, dbId) -> current[dbId]?.let { put(uiId, it.copy(bookId = uiId)) } }
        }
    }

    override suspend fun hydrate(): Map<Long, ProgressSnapshot> = writeMutex.withLock {
        if (!hydrated) {
            cache = repository.progressAll().associate { it.bookId to ProgressSnapshot.fromRow(it) }
            hydrated = true
        }
        snapshots()
    }

    private fun databaseId(bookId: Long): Long = uiToDatabase?.let {
        it[bookId] ?: error("progress book is not registered: " + bookId)
    } ?: bookId

    override suspend fun save(snapshot: ProgressSnapshot) {
        writeMutex.withLock {
            val persisted = snapshot.copy(bookId = databaseId(snapshot.bookId))
            repository.saveProgress(persisted.toRow())
            cache = cache + (persisted.bookId to persisted)
        }
    }

    override suspend fun saveAll(snapshots: Collection<ProgressSnapshot>) {
        if (snapshots.isEmpty()) return
        writeMutex.withLock {
            val persisted = snapshots.map { it.copy(bookId = databaseId(it.bookId)) }
            repository.saveProgressBatch(persisted.map { it.toRow() })
            cache = cache + persisted.associateBy { it.bookId }
        }
    }

    override suspend fun delete(bookIds: Set<Long>) {
        if (bookIds.isEmpty()) return
        writeMutex.withLock {
            val mapping = uiToDatabase
            val dbIds = if (mapping == null) bookIds else bookIds.mapNotNull { mapping[it] }.toSet()
            repository.deleteProgress(dbIds)
            cache = cache - dbIds
        }
    }

    override suspend fun flush() {
        writeMutex.withLock { }
    }

    /**
     * 全清：一次事务里删掉 progress 的全部行，并把 `progress.prefs_backfilled.v1` 标记保持为真。
     *
     * 标记为什么必须为真：冷启时 [ProgressBootstrap.load] 只在标记为假时才读旧 `pb_reading`
     * 并把页码回填进 progress（ProgressBackfill.kt），`LegacyMigrationRunner` 也只在标记为假时
     * 才把 prefs 里的页码放进迁移计划。清掉标记等于允许旧数据把用户刚清空的进度原样灌回来。
     *
     * 标记若还没写过（首次清除早于首次回填），在同一事务里补写成真 —— 语义等价于
     * 「旧 prefs 已合并，合并结果为空」。删除和标记必须同事务：否则删完崩在写标记之前，
     * 下次启动照样会被回填。
     *
     * [hydrated] 不重置：清空后库就是空的，再 hydrate 也只是读到空表。
     */
    override suspend fun clearAll(): Int = writeMutex.withLock {
        val removed = repository.transaction { tx ->
            val count = tx.deleteAllProgress()
            if (!tx.progressBackfillComplete()) tx.markProgressBackfillComplete()
            count
        }
        cache = emptyMap()
        removed
    }
}
