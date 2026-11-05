package app.particlebook.android.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/**
 * DAO 骨架：接口在 JVM 侧可用内存实现替换，Sqlite* 实现只负责 SQL/ContentValues 映射。
 * P0 只实现迁移和读路径需要的最小方法；搜索分页、批量写、WAL 竞争压测留 P1/p15。
 */

interface BookDao {
    fun count(): Int
    fun byId(id: Long): BookRow?
    fun all(): List<BookRow>
    fun save(book: BookRow): Long
    fun delete(id: Long): Int
}

interface TagDao {
    fun ensureTag(name: String): Long
    fun link(bookId: Long, tagId: Long)
    fun tagsForBook(bookId: Long): List<String>
}

interface ProgressDao {
    fun get(bookId: Long): ProgressRow?
    fun save(row: ProgressRow)

    /** 显式 upsert 语义；默认等价于 save（SQLite 实现用 INSERT OR REPLACE）。 */
    fun upsert(row: ProgressRow) = save(row)

    /** 批量写必须处于调用方事务内；默认逐条写，Sqlite 实现覆盖为单事务。 */
    fun saveAll(rows: List<ProgressRow>) = rows.forEach { save(it) }
}

interface AnnotationDao {
    fun add(row: AnnotationRow): Long
    fun list(bookId: Long): List<AnnotationRow>
}

interface DownloadDao {
    fun add(row: DownloadRow): Long
    fun list(): List<DownloadRow>
}

interface SearchDao {
    fun replaceDocument(bookId: Long, docType: String, locator: String, rawText: String, indexedText: String)
    fun search(matchExpression: String, limit: Int): List<SearchHit>

    /** 批量替换；默认逐条调用，Sqlite 实现覆盖为单事务，避免每条文档各自开事务。 */
    fun replaceDocuments(bookId: Long, documents: List<SearchDocumentRow>) {
        documents.forEach { document ->
            replaceDocument(bookId, document.docType, document.locator, document.rawText, document.indexedText)
        }
    }
}

class SqliteBookDao(private val db: SQLiteDatabase) : BookDao {

    override fun count(): Int = db.rawQuery("SELECT COUNT(*) FROM books", null).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }

    override fun byId(id: Long): BookRow? = db.rawQuery(
        "SELECT * FROM books WHERE id = ?",
        arrayOf(id.toString()),
    ).use { cursor -> if (cursor.moveToFirst()) mapBook(cursor) else null }

    override fun all(): List<BookRow> = db.rawQuery(
        "SELECT * FROM books ORDER BY added_at DESC, id DESC",
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(mapBook(cursor))
        }
    }

    override fun save(book: BookRow): Long {
        val values = bookValues(book)
        // 先按显式 id，再按 stable_key 认领；显式 id 不存在的旧迁移行也不会
        // 因 stable_key 已存在而撞 UNIQUE(stable_key) 约束。
        val byId = if (book.id > 0) {
            queryLong("SELECT id FROM books WHERE id = ?", arrayOf(book.id.toString()))
        } else {
            null
        }
        val byStableKey = queryLong("SELECT id FROM books WHERE stable_key = ?", arrayOf(book.stableKey))
        val existingId = byId ?: byStableKey
        if (existingId != null) {
            values.remove("id")
            db.update(LibrarySchema.TABLE_BOOKS, values, "id = ?", arrayOf(existingId.toString()))
            return existingId
        }
        if (book.id > 0) values.put("id", book.id)
        return db.insertOrThrow(LibrarySchema.TABLE_BOOKS, null, values)
    }

    override fun delete(id: Long): Int = db.delete(
        LibrarySchema.TABLE_BOOKS,
        "id = ?",
        arrayOf(id.toString()),
    )

    private fun queryLong(sql: String, args: Array<String>): Long? =
        db.rawQuery(sql, args).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

    private fun bookValues(book: BookRow): ContentValues = ContentValues().apply {
        put("stable_key", book.stableKey)
        putLongOrNull("legacy_id", book.legacyId)
        put("title", book.title)
        put("sort_title", book.sortTitle)
        put("author", book.author)
        put("format", book.format)
        put("file_path", book.filePath)
        put("file_name", book.fileName)
        put("file_size", book.fileSize)
        putStringOrNull("content_hash", book.contentHash)
        put("file_mtime", book.fileMtime)
        put("file_missing", if (book.fileMissing) 1 else 0)
        putStringOrNull("cover_path", book.coverPath)
        putLongOrNull("series_id", book.seriesId)
        putDoubleOrNull("series_index", book.seriesIndex)
        putIntOrNull("rating", book.rating)
        putStringOrNull("language", book.language)
        putStringOrNull("publisher", book.publisher)
        putStringOrNull("published_at", book.publishedAt)
        putStringOrNull("isbn", book.isbn)
        putStringOrNull("description", book.description)
        put("source", book.source)
        putStringOrNull("source_id", book.sourceId)
        put("added_at", book.addedAt)
        put("updated_at", book.updatedAt)
        putLongOrNull("last_opened_at", book.lastOpenedAt)
        putLongOrNull("deleted_at", book.deletedAt)
        putStringOrNull("metadata_json", book.metadataJson)
    }

    private fun mapBook(cursor: Cursor): BookRow = BookRow(
        id = cursor.long("id"),
        stableKey = cursor.string("stable_key").orEmpty(),
        legacyId = cursor.longOrNull("legacy_id"),
        title = cursor.string("title").orEmpty(),
        sortTitle = cursor.string("sort_title").orEmpty(),
        author = cursor.string("author").orEmpty(),
        format = cursor.string("format").orEmpty().ifBlank { "epub" },
        filePath = cursor.string("file_path").orEmpty(),
        fileName = cursor.string("file_name").orEmpty(),
        fileSize = cursor.long("file_size"),
        contentHash = cursor.stringOrNull("content_hash"),
        fileMtime = cursor.long("file_mtime"),
        fileMissing = cursor.long("file_missing") != 0L,
        coverPath = cursor.stringOrNull("cover_path"),
        seriesId = cursor.longOrNull("series_id"),
        seriesIndex = cursor.doubleOrNull("series_index"),
        rating = cursor.intOrNull("rating"),
        language = cursor.stringOrNull("language"),
        publisher = cursor.stringOrNull("publisher"),
        publishedAt = cursor.stringOrNull("published_at"),
        isbn = cursor.stringOrNull("isbn"),
        description = cursor.stringOrNull("description"),
        source = cursor.string("source").orEmpty().ifBlank { "local" },
        sourceId = cursor.stringOrNull("source_id"),
        addedAt = cursor.long("added_at"),
        updatedAt = cursor.long("updated_at"),
        lastOpenedAt = cursor.longOrNull("last_opened_at"),
        deletedAt = cursor.longOrNull("deleted_at"),
        metadataJson = cursor.stringOrNull("metadata_json"),
    )
}

class SqliteTagDao(private val db: SQLiteDatabase) : TagDao {
    override fun ensureTag(name: String): Long {
        val values = ContentValues().apply {
            put("name", name)
            put("created_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict(LibrarySchema.TABLE_TAGS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
        return db.rawQuery("SELECT id FROM tags WHERE name = ?", arrayOf(name)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else error("tag 写入失败: " + name)
        }
    }

    override fun link(bookId: Long, tagId: Long) {
        val values = ContentValues().apply {
            put("book_id", bookId)
            put("tag_id", tagId)
        }
        db.insertWithOnConflict(LibrarySchema.TABLE_BOOK_TAGS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    override fun tagsForBook(bookId: Long): List<String> = db.rawQuery(
        "SELECT t.name FROM tags t JOIN book_tags bt ON bt.tag_id = t.id WHERE bt.book_id = ? ORDER BY t.name",
        arrayOf(bookId.toString()),
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }
}

class SqliteProgressDao(private val db: SQLiteDatabase) : ProgressDao {
    override fun get(bookId: Long): ProgressRow? {
        return db.rawQuery(
            "SELECT * FROM progress WHERE book_id = ?",
            arrayOf(bookId.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                ProgressRow(
                    bookId = bookId,
                    locatorJson = cursor.string("locator_json").orEmpty(),
                    page = cursor.intOrNull("page"),
                    totalPages = cursor.intOrNull("total_pages"),
                    percent = cursor.doubleOrNull("percent"),
                    updatedAt = cursor.long("updated_at"),
                    finishedAt = cursor.longOrNull("finished_at"),
                )
            }
        }
    }

    override fun save(row: ProgressRow) {
        val values = ContentValues().apply {
            put("book_id", row.bookId)
            put("locator_json", row.locatorJson)
            putIntOrNull("page", row.page)
            putIntOrNull("total_pages", row.totalPages)
            putDoubleOrNull("percent", row.percent)
            put("updated_at", row.updatedAt)
            putLongOrNull("finished_at", row.finishedAt)
        }
        db.insertWithOnConflict(LibrarySchema.TABLE_PROGRESS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun saveAll(rows: List<ProgressRow>) {
        if (rows.isEmpty()) return
        db.beginTransaction()
        try {
            rows.forEach { save(it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

class SqliteAnnotationDao(private val db: SQLiteDatabase) : AnnotationDao {
    override fun add(row: AnnotationRow): Long {
        val values = ContentValues().apply {
            put("book_id", row.bookId)
            put("kind", row.kind)
            put("locator_json", row.locatorJson)
            put("quote", row.quote)
            put("note", row.note)
            putStringOrNull("color", row.color)
            put("created_at", row.createdAt)
            put("updated_at", row.updatedAt)
            putLongOrNull("deleted_at", row.deletedAt)
        }
        return db.insertOrThrow(LibrarySchema.TABLE_ANNOTATIONS, null, values)
    }

    override fun list(bookId: Long): List<AnnotationRow> = db.rawQuery(
        "SELECT * FROM annotations WHERE book_id = ? AND deleted_at IS NULL ORDER BY created_at DESC",
        arrayOf(bookId.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    AnnotationRow(
                        id = cursor.long("id"),
                        bookId = bookId,
                        kind = cursor.string("kind").orEmpty(),
                        locatorJson = cursor.string("locator_json").orEmpty(),
                        quote = cursor.string("quote").orEmpty(),
                        note = cursor.string("note").orEmpty(),
                        color = cursor.stringOrNull("color"),
                        createdAt = cursor.long("created_at"),
                        updatedAt = cursor.long("updated_at"),
                        deletedAt = cursor.longOrNull("deleted_at"),
                    ),
                )
            }
        }
    }
}

class SqliteDownloadDao(private val db: SQLiteDatabase) : DownloadDao {
    override fun add(row: DownloadRow): Long {
        val values = ContentValues().apply {
            putLongOrNull("book_id", row.bookId)
            putLongOrNull("source_book_id", row.sourceBookId)
            put("title", row.title)
            put("author", row.author)
            put("format", row.format)
            put("download_path", row.downloadPath)
            put("file_name", row.fileName)
            put("dest_path", row.destPath)
            put("storage", row.storage)
            put("size", row.size)
            put("total", row.total)
            put("status", row.status)
            putStringOrNull("error_kind", row.errorKind)
            putStringOrNull("error_message", row.errorMessage)
            put("finished_at", row.finishedAt)
            put("created_at", row.createdAt)
            put("updated_at", row.updatedAt)
        }
        return db.insertWithOnConflict(
            LibrarySchema.TABLE_DOWNLOADS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun list(): List<DownloadRow> = db.rawQuery(
        "SELECT * FROM downloads ORDER BY finished_at DESC, id DESC",
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    DownloadRow(
                        id = cursor.long("id"),
                        bookId = cursor.longOrNull("book_id"),
                        sourceBookId = cursor.longOrNull("source_book_id"),
                        title = cursor.string("title").orEmpty(),
                        author = cursor.string("author").orEmpty(),
                        format = cursor.string("format").orEmpty().ifBlank { "epub" },
                        downloadPath = cursor.string("download_path").orEmpty(),
                        fileName = cursor.string("file_name").orEmpty(),
                        destPath = cursor.string("dest_path").orEmpty(),
                        storage = cursor.string("storage").orEmpty().ifBlank { "file" },
                        size = cursor.long("size"),
                        total = cursor.long("total"),
                        status = cursor.string("status").orEmpty().ifBlank { "completed" },
                        errorKind = cursor.stringOrNull("error_kind"),
                        errorMessage = cursor.stringOrNull("error_message"),
                        finishedAt = cursor.long("finished_at"),
                        createdAt = cursor.long("created_at"),
                        updatedAt = cursor.long("updated_at"),
                    ),
                )
            }
        }
    }
}

class SqliteSearchDao(private val db: SQLiteDatabase) : SearchDao {
    override fun replaceDocument(
        bookId: Long,
        docType: String,
        locator: String,
        rawText: String,
        indexedText: String,
    ) {
        val values = ContentValues().apply {
            put("book_id", bookId)
            put("doc_type", docType)
            put("locator", locator)
            put("raw_text", rawText)
            put("text", indexedText)
            put("updated_at", System.currentTimeMillis())
        }
        // UNIQUE(book_id, doc_type, locator) + AFTER DELETE/INSERT 触发器负责同步 external-content FTS。
        db.insertWithOnConflict(
            LibrarySchema.TABLE_SEARCH_DOCUMENTS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun replaceDocuments(bookId: Long, documents: List<SearchDocumentRow>) {
        if (documents.isEmpty()) return
        db.beginTransaction()
        try {
            documents.forEach { document ->
                replaceDocument(bookId, document.docType, document.locator, document.rawText, document.indexedText)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun search(matchExpression: String, limit: Int): List<SearchHit> = db.rawQuery(
        "SELECT d.book_id, d.doc_type, d.locator, d.raw_text " +
            "FROM search_fts f JOIN search_documents d ON d.id = f.rowid " +
            "WHERE search_fts MATCH ? ORDER BY d.id LIMIT ?",
        arrayOf(matchExpression, limit.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val raw = cursor.string("raw_text").orEmpty()
                add(
                    SearchHit(
                        bookId = cursor.long("book_id"),
                        docType = cursor.string("doc_type").orEmpty(),
                        locator = cursor.string("locator").orEmpty(),
                        snippet = raw.replace('\n', ' ').take(160),
                    ),
                )
            }
        }
    }
}

private fun ContentValues.putLongOrNull(key: String, value: Long?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun ContentValues.putIntOrNull(key: String, value: Int?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun ContentValues.putDoubleOrNull(key: String, value: Double?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun ContentValues.putStringOrNull(key: String, value: String?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun Cursor.column(name: String): Int = getColumnIndexOrThrow(name)

private fun Cursor.string(name: String): String? = if (isNull(column(name))) null else getString(column(name))

private fun Cursor.stringOrNull(name: String): String? = string(name)

private fun Cursor.long(name: String): Long = getLong(column(name))

private fun Cursor.longOrNull(name: String): Long? = if (isNull(column(name))) null else getLong(column(name))

private fun Cursor.intOrNull(name: String): Int? = if (isNull(column(name))) null else getInt(column(name))

private fun Cursor.doubleOrNull(name: String): Double? = if (isNull(column(name))) null else getDouble(column(name))
