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
        it !is ReaderLocator.Pdf ||
            (it.page >= 0 && it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f)
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
}
