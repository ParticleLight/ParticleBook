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
    fun saveProgress(row: ProgressRow)
    fun upsertProgress(row: ProgressRow) = saveProgress(row)
    fun saveProgressBatch(rows: List<ProgressRow>) = rows.forEach { saveProgress(it) }

    fun annotations(bookId: Long): List<AnnotationRow>
    fun addAnnotation(row: AnnotationRow): Long

    fun downloads(): List<DownloadRow>
    fun addDownload(row: DownloadRow): Long

    fun saveSearchDocument(bookId: Long, docType: String, locator: String, rawText: String)
    fun saveSearchDocuments(bookId: Long, documents: List<SearchDocumentInput>) {
        documents.forEach { saveSearchDocument(bookId, it.docType, it.locator, it.rawText) }
    }
    fun search(query: String, limit: Int = 50): List<SearchHit>

    fun <T> transaction(block: (LibraryRepository) -> T): T
    fun close()
}

/** framework SQLite 生产实现；schema 由 [LibraryDatabase] 建立。 */
class SqliteLibraryRepository(private val db: SQLiteDatabase) : LibraryRepository {
    private val bookDao: BookDao = SqliteBookDao(db)
    private val tagDao: TagDao = SqliteTagDao(db)
    private val progressDao: ProgressDao = SqliteProgressDao(db)
    private val annotationDao: AnnotationDao = SqliteAnnotationDao(db)
    private val downloadDao: DownloadDao = SqliteDownloadDao(db)
    private val searchDao: SearchDao = SqliteSearchDao(db)

    override fun bookCount(): Int = bookDao.count()

    override fun bookById(id: Long): BookRow? = bookDao.byId(id)

    override fun books(): List<BookRow> = bookDao.all()

    override fun saveBook(book: BookRow): Long = bookDao.save(book)

    override fun deleteBook(bookId: Long): Boolean = bookDao.delete(bookId) > 0

    override fun tagsForBook(bookId: Long): List<String> = tagDao.tagsForBook(bookId)

    override fun addTag(bookId: Long, tagName: String) {
        val id = tagDao.ensureTag(tagName)
        tagDao.link(bookId, id)
    }

    override fun progress(bookId: Long): ProgressRow? = progressDao.get(bookId)

    override fun saveProgress(row: ProgressRow) = progressDao.save(row)

    override fun upsertProgress(row: ProgressRow) = progressDao.upsert(row)

    override fun saveProgressBatch(rows: List<ProgressRow>) = progressDao.saveAll(rows)

    override fun annotations(bookId: Long): List<AnnotationRow> = annotationDao.list(bookId)

    override fun addAnnotation(row: AnnotationRow): Long = annotationDao.add(row)

    override fun downloads(): List<DownloadRow> = downloadDao.list()

    override fun addDownload(row: DownloadRow): Long = downloadDao.add(row)

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
