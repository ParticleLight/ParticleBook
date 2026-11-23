package app.particlebook.android.data

import android.database.sqlite.SQLiteDatabase

/**
 * 数据层门面。P0 只给骨架和 JVM 可测契约；不接 UI。
 *
 * 所有方法同步执行；调用方（后续 P1）负责放到 IO dispatcher。
 * [transaction] 内的 block 必须只通过传入的 repository 操作，否则不会自动进入同一事务。
 */
interface LibraryRepository {
    fun bookCount(): Int
    fun bookById(id: Long): BookRow?
    fun books(): List<BookRow>
    fun saveBook(book: BookRow): Long
    fun deleteBook(bookId: Long): Boolean

    fun tagsForBook(bookId: Long): List<String>
    fun addTag(bookId: Long, tagName: String)

    fun progress(bookId: Long): ProgressRow?
    fun progressAll(): List<ProgressRow>
    fun deleteProgress(bookIds: Set<Long>): Int
    fun progressBackfillComplete(): Boolean
    /** Must be committed in the same transaction as the legacy progress rows. */
    fun markProgressBackfillComplete()
    fun saveProgress(row: ProgressRow)
    fun upsertProgress(row: ProgressRow) = saveProgress(row)
    fun saveProgressBatch(rows: List<ProgressRow>) = rows.forEach { saveProgress(it) }

    fun annotations(bookId: Long): List<AnnotationRow>
    fun addAnnotation(row: AnnotationRow): Long
    fun annotationById(annotationId: Long): AnnotationRow?
    fun updateAnnotation(row: AnnotationRow): Boolean
    fun removeAnnotation(annotationId: Long): Boolean

    fun downloads(): List<DownloadRow>
    fun addDownload(row: DownloadRow): Long

    /** 阅读统计：追加一段阅读会话，返回行 id。同步执行，调用方负责 IO dispatcher。 */
    fun saveReadingSession(row: ReadingSessionRow): Long

    /**
     * 阅读统计：读取 ended_at >= [sinceMillis] 的会话，按 started_at、id 升序。
     * 同步执行，调用方负责 IO dispatcher（统计页只读一次近 60 天）。
     */
    fun readingSessionsSince(sinceMillis: Long): List<ReadingSessionRow>

    fun saveSearchDocument(bookId: Long, docType: String, locator: String, rawText: String)
    fun saveSearchDocuments(bookId: Long, documents: List<SearchDocumentInput>) {
        documents.forEach { saveSearchDocument(bookId, it.docType, it.locator, it.rawText) }
    }
    fun search(query: String, limit: Int = 50): List<SearchHit>

    fun <T> transaction(block: (LibraryRepository) -> T): T
    fun close()
}

/**
 * framework SQLite 生产实现；schema 由 [LibraryDatabase] 建立。
 *
 * 同时实现 [SearchIndexStore]：跨书索引的写入必须和书架/标注共用一个连接与事务域，
 * 否则「删旧-插新-checkpoint」会被拆到两个连接上，回滚不再原子。
 */
class SqliteLibraryRepository(private val db: SQLiteDatabase) : LibraryRepository, SearchIndexStore {
    private val bookDao: BookDao = SqliteBookDao(db)
    private val tagDao: TagDao = SqliteTagDao(db)
    private val progressDao: ProgressDao = SqliteProgressDao(db)
    private val annotationDao: AnnotationDao = SqliteAnnotationDao(db)
    private val downloadDao: DownloadDao = SqliteDownloadDao(db)
    private val readingSessionDao: ReadingSessionDao = SqliteReadingSessionDao(db)
    private val searchDao: SearchDao = SqliteSearchDao(db)
    private val indexStateDao: IndexStateDao = SqliteIndexStateDao(db)

    override fun bookCount(): Int = bookDao.count()

    override fun bookById(id: Long): BookRow? = bookDao.byId(id)

    override fun books(): List<BookRow> = bookDao.all()

    override fun saveBook(book: BookRow): Long = bookDao.save(book)

    /**
     * 删书：search_documents 靠 FK ON DELETE CASCADE 清掉，但 index_state 是通用 key/value、
     * 没有外键，必须在同一事务里显式清 checkpoint，避免「书没了 checkpoint 还在」。
     */
    override fun deleteBook(bookId: Long): Boolean {
        db.beginTransaction()
        return try {
            val removed = bookDao.delete(bookId) > 0
            indexStateDao.deleteForBook(bookId)
            db.setTransactionSuccessful()
            removed
        } finally {
            db.endTransaction()
        }
    }

    override fun tagsForBook(bookId: Long): List<String> = tagDao.tagsForBook(bookId)

    override fun addTag(bookId: Long, tagName: String) {
        val id = tagDao.ensureTag(tagName)
        tagDao.link(bookId, id)
    }

    override fun progress(bookId: Long): ProgressRow? = progressDao.get(bookId)

    override fun progressAll(): List<ProgressRow> = progressDao.all()

    override fun deleteProgress(bookIds: Set<Long>): Int = progressDao.delete(bookIds)

    override fun progressBackfillComplete(): Boolean = progressDao.backfillComplete()

    override fun markProgressBackfillComplete() = progressDao.markBackfillComplete()

    override fun saveProgress(row: ProgressRow) = progressDao.save(row)

    override fun upsertProgress(row: ProgressRow) = progressDao.upsert(row)

    override fun saveProgressBatch(rows: List<ProgressRow>) = progressDao.saveAll(rows)

    override fun annotations(bookId: Long): List<AnnotationRow> = annotationDao.list(bookId)

    override fun addAnnotation(row: AnnotationRow): Long = annotationDao.add(row)

    override fun annotationById(annotationId: Long): AnnotationRow? = annotationDao.byId(annotationId)

    override fun updateAnnotation(row: AnnotationRow): Boolean = annotationDao.update(row) > 0

    /** 软删；只有真正从「未删」变为「已删」时返回 true，重复删除幂等返回 false。 */
    override fun removeAnnotation(annotationId: Long): Boolean =
        annotationDao.softDelete(annotationId, System.currentTimeMillis()) > 0

    override fun downloads(): List<DownloadRow> = downloadDao.list()

    override fun addDownload(row: DownloadRow): Long = downloadDao.add(row)

    override fun saveReadingSession(row: ReadingSessionRow): Long = readingSessionDao.add(row)

    override fun readingSessionsSince(sinceMillis: Long): List<ReadingSessionRow> =
        readingSessionDao.since(sinceMillis)

    override fun saveSearchDocument(bookId: Long, docType: String, locator: String, rawText: String) {
        searchDao.replaceDocument(bookId, docType, locator, rawText, SearchTokenizer.indexText(rawText))
    }

    override fun saveSearchDocuments(bookId: Long, documents: List<SearchDocumentInput>) {
        val rows = documents.map { document ->
            SearchDocumentRow(
                bookId = bookId,
                docType = document.docType,
                locator = document.locator,
                rawText = document.rawText,
                indexedText = SearchTokenizer.indexText(document.rawText),
            )
        }
        searchDao.replaceDocuments(bookId, rows)
    }

    override fun search(query: String, limit: Int): List<SearchHit> {
        val match = SearchTokenizer.ftsMatch(query) ?: return emptyList()
        return searchDao.search(match, limit)
    }

    // ---------------------------------------------------------------------
    // SearchIndexStore：跨书全文索引的原子写入
    // ---------------------------------------------------------------------

    override fun indexState(key: String): IndexStateRow? = indexStateDao.get(key)

    override fun putIndexState(rows: List<IndexStateRow>) {
        if (rows.isEmpty()) return
        db.beginTransaction()
        try {
            rows.forEach { indexStateDao.put(it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun replaceIndex(batch: SearchIndexBatch) {
        require(batch.documents.all { it.docType == batch.docType }) {
            "batch docType mismatch: " + batch.docType
        }
        db.beginTransaction()
        try {
            searchDao.deleteBookDocuments(batch.bookId, batch.docType)
            batch.documents.forEach { document ->
                searchDao.replaceDocument(
                    bookId = batch.bookId,
                    docType = document.docType,
                    locator = document.locator,
                    rawText = document.rawText,
                    indexedText = SearchTokenizer.indexText(document.rawText),
                )
            }
            batch.state.forEach { indexStateDao.put(it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun <T> transaction(block: (LibraryRepository) -> T): T {
        db.beginTransaction()
        return try {
            val result = block(this)
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }

    override fun close() {
        db.close()
    }
}
