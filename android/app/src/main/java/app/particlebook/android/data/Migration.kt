package app.particlebook.android.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.util.Locale

/**
 * books.json / prefs / 下载历史 → schema v1 的迁移计划。
 *
 * P0 只做「可验证骨架」：
 * - [buildPlan] 是纯函数，1000 本模拟数据可以在 JVM 单测里完整跑；
 * - [apply] 面向 [MigrationSink]，测试用内存实现，生产用 [SqliteMigrationSink]；
 * - 真正 SQLite 的建表/写入/外键/回滚在设备 p15 复测，JVM 侧验证同一套计划与行数据。
 * 迁移绝不删除 books.json；调用方只有在 verify 通过后才决定是否改名 .migrated。
 */
data class BookTagRef(
    val bookId: Long,
    val tagName: String,
)

data class MigrationStats(
    val inputBooks: Int,
    val uniqueBooks: Int,
    val duplicateBooks: Int,
    val userTags: Int,
    val bookTagLinks: Int,
    val progressRows: Int,
    val downloadRows: Int,
    val skippedProgress: Int,
    val skippedDownloads: Int,
)

data class MigrationPlan(
    val books: List<BookRow>,
    val tags: List<String>,
    val bookTags: List<BookTagRef>,
    val progress: List<ProgressRow>,
    val downloads: List<DownloadRow>,
    val idRemap: Map<Long, Long>,
    val warnings: List<String>,
    val stats: MigrationStats,
)

interface MigrationSink {
    fun insertBook(row: BookRow): Long
    fun insertTag(name: String): Long
    fun linkBookTag(bookId: Long, tagName: String)
    fun insertProgress(row: ProgressRow)
    fun insertDownload(row: DownloadRow)
    fun clearExisting()
}

data class MigrationApplyResult(
    val books: Int,
    val tags: Int,
    val bookTags: Int,
    val progress: Int,
    val downloads: Int,
)

data class MigrationVerification(
    val ok: Boolean,
    val errors: List<String>,
)

class LibraryMigrator(
    private val fileExists: (String) -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
) {

    fun buildPlan(snapshot: LegacySnapshot): MigrationPlan {
        val warnings = WarningSink()
        val stats = StatsCounter()
        var nextSyntheticId = (snapshot.books.asSequence().map { it.id }.filter { it > 0 }.maxOrNull() ?: 0L) + 1L
        val usedIds = mutableSetOf<Long>()
        val byStableKey = LinkedHashMap<String, BookAcc>()
        val idRemap = LinkedHashMap<Long, Long>()

        for (legacy in snapshot.books) {
            stats.inputBooks++
            val requestedId = legacy.id
            val proposedId = if (requestedId > 0) requestedId else nextSyntheticId++
            val stableKey = StableKey.stableKeyFor(proposedId, legacy.tags, StableKey.contentHashFromTags(legacy.tags))
            val existing = byStableKey[stableKey]
            if (existing != null) {
                stats.duplicateBooks++
                warnings.add("重复书籍 stable_key=" + stableKey + "，合并 id " + requestedId + " -> " + existing.row.id)
                idRemap.putIfAbsent(requestedId, existing.row.id)
                if (proposedId != requestedId) idRemap.putIfAbsent(proposedId, existing.row.id)
                mergeBook(existing, legacy, warnings)
                continue
            }

            var canonicalId = proposedId
            if (!usedIds.add(canonicalId)) {
                canonicalId = nextSyntheticId++
                usedIds.add(canonicalId)
                warnings.add(
                    "重复 id " + requestedId + " 且 stable_key 不同，后一条改用新 id " + canonicalId,
                )
            }
            idRemap.putIfAbsent(requestedId, canonicalId)
            if (proposedId != canonicalId) idRemap.putIfAbsent(proposedId, canonicalId)
            byStableKey[stableKey] = newBook(legacy, canonicalId, stableKey, warnings)
        }

        val finalBooks = byStableKey.values.map { acc ->
            val missing = acc.row.filePath.isBlank() || !safeExists(acc.row.filePath)
            acc.row.copy(fileMissing = missing)
        }.sortedBy { it.id }

        val tags = sortedSetOf<String>()
        finalBooks.forEach { book ->
            val acc = byStableKey.values.first { it.row.id == book.id }
            StableKey.userTags(acc.tags.toList()).forEach { tags += it }
        }
        val tagRefs = finalBooks.flatMap { book ->
            val acc = byStableKey.values.first { it.row.id == book.id }
            StableKey.userTags(acc.tags.toList()).sorted().map { BookTagRef(book.id, it) }
        }

        val booksById = finalBooks.associateBy { it.id }
        val progressById = LinkedHashMap<Long, ProgressRow>()
        for (legacy in snapshot.progress) {
            val canonicalId = idRemap[legacy.bookId]
            if (canonicalId == null || booksById[canonicalId] == null) {
                stats.skippedProgress++
                warnings.add("进度指向不存在的 book id=" + legacy.bookId + "，已跳过")
                continue
            }
            val page = legacy.page?.takeIf { it >= 0 }
            val total = legacy.totalPages?.takeIf { it > 0 }
            if (page == null && total == null) {
                stats.skippedProgress++
                continue
            }
            val book = booksById.getValue(canonicalId)
            val updatedAt = legacy.lastReadAt?.takeIf { it > 0 } ?: now()
            val candidate = ProgressRow(
                bookId = canonicalId,
                locatorJson = locatorJson(book.format, page, total),
                page = page,
                totalPages = total,
                percent = if (page != null && total != null && total > 0) {
                    (page.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
                } else {
                    null
                },
                updatedAt = updatedAt,
            )
            val previous = progressById[canonicalId]
            progressById[canonicalId] = if (previous == null) candidate else mergeProgress(previous, candidate)
        }

        val zlibKeyToBookId = finalBooks.mapNotNull { book ->
            book.stableKey.removePrefix(StableKey.ZLIB_PREFIX)
                .takeIf { book.stableKey.startsWith(StableKey.ZLIB_PREFIX) }
                ?.toLongOrNull()
                ?.let { it to book.id }
        }.toMap()
        val downloadsByKey = LinkedHashMap<String, DownloadRow>()
        for (legacy in snapshot.downloads) {
            val sourceId = legacy.bookId
            val bookId = sourceId?.let { zlibKeyToBookId[it] }
            if (sourceId != null && bookId == null) {
                warnings.add("下载记录 zlib book=" + sourceId + " 找不到对应书架条目，保留为孤立下载")
            }
            val format = StableKey.normalizeFormat(legacy.format)
            if (StableKey.formatNeedsNormalization(legacy.format)) {
                warnings.add("下载记录 format=" + legacy.format + " 已收敛为 " + format)
            }
            val dest = legacy.destPath.ifBlank { legacy.downloadPath }
            val row = DownloadRow(
                bookId = bookId,
                sourceBookId = sourceId,
                title = legacy.title,
                author = legacy.author,
                format = format,
                downloadPath = legacy.downloadPath,
                fileName = legacy.fileName.ifBlank { StableKey.fileNameFromPath(dest) },
                destPath = dest,
                storage = legacy.storage.ifBlank { "file" },
                size = legacy.size,
                total = legacy.total,
                status = legacy.status.ifBlank { "completed" },
                errorKind = legacy.errorKind,
                errorMessage = legacy.errorMessage,
                finishedAt = legacy.finishedAt,
                createdAt = legacy.finishedAt,
                updatedAt = legacy.finishedAt,
            )
            // 与 downloads 的 UNIQUE(source_book_id, dest_path) 对齐；标题不同也不能产生两条计划，
            // 否则 apply 时 CONFLICT_REPLACE 会丢掉其中一条，verify 却可能仍然通过。
            val key = (sourceId?.toString() ?: "-") + "|" + dest
            val previous = downloadsByKey[key]
            if (previous == null || previous.finishedAt <= row.finishedAt) {
                downloadsByKey[key] = row
            } else {
                stats.skippedDownloads++
            }
        }

        stats.uniqueBooks = finalBooks.size
        stats.userTags = tags.size
        stats.bookTagLinks = tagRefs.size
        stats.progressRows = progressById.size
        stats.downloadRows = downloadsByKey.size
        return MigrationPlan(
            books = finalBooks,
            tags = tags.toList(),
            bookTags = tagRefs,
            progress = progressById.values.sortedBy { it.bookId },
            downloads = downloadsByKey.values.sortedWith(compareBy({ it.sourceBookId ?: Long.MAX_VALUE }, { it.destPath })),
            idRemap = idRemap,
            warnings = warnings.snapshot(),
            stats = stats.snapshot(),
        )
    }

    fun apply(plan: MigrationPlan, sink: MigrationSink, clearExisting: Boolean = false): MigrationApplyResult {
        if (clearExisting) sink.clearExisting()
        plan.books.forEach { sink.insertBook(it) }
        plan.tags.forEach { sink.insertTag(it) }
        plan.bookTags.forEach { sink.linkBookTag(it.bookId, it.tagName) }
        plan.progress.forEach { sink.insertProgress(it) }
        plan.downloads.forEach { sink.insertDownload(it) }
        return MigrationApplyResult(
            books = plan.books.size,
            tags = plan.tags.size,
            bookTags = plan.bookTags.size,
            progress = plan.progress.size,
            downloads = plan.downloads.size,
        )
    }

    fun verify(plan: MigrationPlan, repository: LibraryRepository): MigrationVerification {
        val errors = mutableListOf<String>()
        val books = repository.books()
        val byId = books.associateBy { it.id }
        if (books.size != plan.books.size || plan.books.any { byId[it.id] == null }) {
            errors += "书籍数量/主键不一致：计划 " + plan.books.size + " 本，读回 " + books.size + " 本"
        }
        for (expected in plan.books) {
            val actual = byId[expected.id] ?: continue
            if (actual.stableKey != expected.stableKey) {
                errors += "book " + expected.id + " stable_key 不一致: " + actual.stableKey
            }
            if (actual.title != expected.title || actual.fileSize != expected.fileSize) {
                errors += "book " + expected.id + " 标题/大小不一致"
            }
            val actualTags = repository.tagsForBook(expected.id).toSet()
            val expectedTags = plan.bookTags.filter { it.bookId == expected.id }.map { it.tagName }.toSet()
            if (actualTags != expectedTags) {
                errors += "book " + expected.id + " 标签不一致: " + actualTags + " != " + expectedTags
            }
        }
        for (expected in plan.progress) {
            val actual = repository.progress(expected.bookId)
            if (actual == null || actual.page != expected.page || actual.totalPages != expected.totalPages) {
                errors += "book " + expected.bookId + " 进度往返不一致"
            }
        }
        val actualDownloads = repository.downloads()
        for (expected in plan.downloads) {
            val found = actualDownloads.any { actual ->
                actual.sourceBookId == expected.sourceBookId &&
                    actual.destPath == expected.destPath &&
                    actual.status == expected.status
            }
            if (!found) errors += "下载缺失: source=" + expected.sourceBookId + " dest=" + expected.destPath
        }
        return MigrationVerification(errors.isEmpty(), errors.take(50))
    }

    private fun newBook(
        legacy: LegacyBook,
        id: Long,
        stableKey: String,
        warnings: WarningSink,
    ): BookAcc {
        if (StableKey.formatNeedsNormalization(legacy.format)) {
            warnings.add("book " + legacy.id + " format=" + legacy.format + " 已收敛为 " + StableKey.normalizeFormat(legacy.format))
        }
        val addedAt = StableKey.parseEpochMillis(legacy.addedAtRaw)
        val tags = legacy.tags.map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
        val row = BookRow(
            id = id,
            stableKey = stableKey,
            legacyId = legacy.id.takeIf { it > 0 },
            title = legacy.title,
            sortTitle = legacy.title.lowercase(Locale.ROOT),
            author = legacy.author,
            format = StableKey.normalizeFormat(legacy.format),
            filePath = legacy.filePath,
            fileName = StableKey.fileNameFromPath(legacy.filePath),
            fileSize = legacy.fileSize,
            contentHash = StableKey.contentHashFromTags(tags.toList()),
            source = if (StableKey.zlibId(tags.toList()) != null) "zlib" else "local",
            sourceId = StableKey.zlibId(tags.toList())?.toString(),
            addedAt = addedAt,
            updatedAt = now(),
        )
        return BookAcc(row, tags)
    }

    private fun mergeBook(acc: BookAcc, legacy: LegacyBook, warnings: WarningSink) {
        acc.tags += legacy.tags.map { it.trim() }.filter { it.isNotEmpty() }
        val old = acc.row
        val oldPath = old.filePath
        val newPath = legacy.filePath
        val oldExists = oldPath.isNotBlank() && safeExists(oldPath)
        val newExists = newPath.isNotBlank() && safeExists(newPath)
        val chosenPath = when {
            oldPath.isBlank() -> newPath
            newPath.isBlank() -> oldPath
            !oldExists && newExists -> newPath
            else -> oldPath
        }
        if (oldPath.isNotBlank() && newPath.isNotBlank() && oldPath != newPath && !oldExists && !newExists) {
            warnings.add("重复书籍 " + old.id + " 的路径均不存在，保留先到的 " + oldPath)
        }
        acc.row = old.copy(
            legacyId = old.legacyId ?: legacy.id.takeIf { it > 0 },
            title = old.title.ifBlank { legacy.title },
            sortTitle = old.sortTitle.ifBlank { legacy.title.lowercase(Locale.ROOT) },
            author = old.author.ifBlank { legacy.author },
            format = if (old.format.isNotBlank()) old.format else StableKey.normalizeFormat(legacy.format),
            filePath = chosenPath,
            fileName = old.fileName.ifBlank { StableKey.fileNameFromPath(chosenPath) },
            fileSize = maxOf(old.fileSize, legacy.fileSize),
            contentHash = old.contentHash ?: StableKey.contentHashFromTags(acc.tags.toList()),
            source = if (old.source == "zlib" || StableKey.zlibId(acc.tags.toList()) != null) "zlib" else old.source,
            sourceId = old.sourceId ?: StableKey.zlibId(acc.tags.toList())?.toString(),
            addedAt = minPositive(old.addedAt, StableKey.parseEpochMillis(legacy.addedAtRaw)),
            updatedAt = now(),
        )
    }

    private fun mergeProgress(previous: ProgressRow, candidate: ProgressRow): ProgressRow {
        val newer = if (candidate.updatedAt >= previous.updatedAt) candidate else previous
        val page = maxOf(previous.page ?: -1, candidate.page ?: -1).takeIf { it >= 0 }
        val total = newer.totalPages ?: previous.totalPages
        return newer.copy(
            page = page,
            totalPages = total,
            locatorJson = locatorJson("pdf", page, total),
            percent = if (page != null && total != null && total > 0) {
                (page.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
            } else {
                newer.percent
            },
        )
    }

    private fun locatorJson(format: String, page: Int?, total: Int?): String {
        val kind = if (format == "pdf") "pdf" else format.ifBlank { "epub" }
        val fields = mutableListOf<String>()
        fields += "\"kind\":" + StableKey.jsonString(kind)
        if (page != null) fields += "\"page\":" + page
        if (total != null) fields += "\"total\":" + total
        return "{" + fields.joinToString(",") + "}"
    }

    private fun safeExists(path: String): Boolean = try {
        fileExists(path)
    } catch (_: Exception) {
        false
    }

    private fun minPositive(a: Long, b: Long): Long = when {
        a <= 0 -> b
        b <= 0 -> a
        else -> minOf(a, b)
    }
}

private class BookAcc(
    var row: BookRow,
    val tags: MutableSet<String> = LinkedHashSet(),
)

private class WarningSink {
    private val values = mutableListOf<String>()
    fun add(message: String) {
        if (values.size < 200) values += message
        else if (values.size == 200) values += "...更多告警已截断"
    }
    fun snapshot(): List<String> = values.toList()
}

private class StatsCounter {
    var inputBooks = 0
    var uniqueBooks = 0
    var duplicateBooks = 0
    var userTags = 0
    var bookTagLinks = 0
    var progressRows = 0
    var downloadRows = 0
    var skippedProgress = 0
    var skippedDownloads = 0
    fun snapshot(): MigrationStats = MigrationStats(
        inputBooks, uniqueBooks, duplicateBooks, userTags, bookTagLinks,
        progressRows, downloadRows, skippedProgress, skippedDownloads,
    )
}

/**
 * 生产迁移 sink：显式写 id（数字 id 永不变），所有操作由 [migrateInTransaction] 包在一个事务里。
 * 仅编译验证；真实 SQLite 行为在设备 p15 复测（JVM 单测不加载 android.database）。
 */
class SqliteMigrationSink(private val db: SQLiteDatabase) : MigrationSink {

    override fun insertBook(row: BookRow): Long {
        val values = ContentValues()
        if (row.id > 0) values.put("id", row.id)
        values.put("stable_key", row.stableKey)
        row.legacyId?.let { values.put("legacy_id", it) }
        values.put("title", row.title)
        values.put("sort_title", row.sortTitle)
        values.put("author", row.author)
        values.put("format", row.format)
        values.put("file_path", row.filePath)
        values.put("file_name", row.fileName)
        values.put("file_size", row.fileSize)
        values.put("content_hash", row.contentHash)
        values.put("file_mtime", row.fileMtime)
        values.put("file_missing", if (row.fileMissing) 1 else 0)
        values.put("cover_path", row.coverPath)
        values.put("series_id", row.seriesId)
        values.put("series_index", row.seriesIndex)
        values.put("rating", row.rating)
        values.put("language", row.language)
        values.put("publisher", row.publisher)
        values.put("published_at", row.publishedAt)
        values.put("isbn", row.isbn)
        values.put("description", row.description)
        values.put("source", row.source)
        values.put("source_id", row.sourceId)
        values.put("added_at", row.addedAt)
        values.put("updated_at", row.updatedAt)
        values.put("last_opened_at", row.lastOpenedAt)
        values.put("deleted_at", row.deletedAt)
        values.put("metadata_json", row.metadataJson)
        return db.insertOrThrow(LibrarySchema.TABLE_BOOKS, null, values)
    }

    override fun insertTag(name: String): Long {
        val values = ContentValues()
        values.put("name", name)
        values.put("created_at", System.currentTimeMillis())
        db.insertWithOnConflict(LibrarySchema.TABLE_TAGS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
        db.rawQuery("SELECT id FROM tags WHERE name = ?", arrayOf(name)).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        error("tag 写入后读不到: " + name)
    }

    override fun linkBookTag(bookId: Long, tagName: String) {
        val tagId = queryLong("SELECT id FROM tags WHERE name = ?", arrayOf(tagName))
            ?: return
        val values = ContentValues()
        values.put("book_id", bookId)
        values.put("tag_id", tagId)
        db.insertWithOnConflict(LibrarySchema.TABLE_BOOK_TAGS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    override fun insertProgress(row: ProgressRow) {
        val values = ContentValues()
        values.put("book_id", row.bookId)
        values.put("locator_json", row.locatorJson)
        values.put("page", row.page)
        values.put("total_pages", row.totalPages)
        values.put("percent", row.percent)
        values.put("updated_at", row.updatedAt)
        values.put("finished_at", row.finishedAt)
        db.insertWithOnConflict(LibrarySchema.TABLE_PROGRESS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun insertDownload(row: DownloadRow) {
        val values = ContentValues()
        values.put("book_id", row.bookId)
        values.put("source_book_id", row.sourceBookId)
        values.put("title", row.title)
        values.put("author", row.author)
        values.put("format", row.format)
        values.put("download_path", row.downloadPath)
        values.put("file_name", row.fileName)
        values.put("dest_path", row.destPath)
        values.put("storage", row.storage)
        values.put("size", row.size)
        values.put("total", row.total)
        values.put("status", row.status)
        values.put("error_kind", row.errorKind)
        values.put("error_message", row.errorMessage)
        values.put("finished_at", row.finishedAt)
        values.put("created_at", row.createdAt)
        values.put("updated_at", row.updatedAt)
        db.insertWithOnConflict(LibrarySchema.TABLE_DOWNLOADS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun clearExisting() {
        db.execSQL("DELETE FROM search_documents")
        db.execSQL("DELETE FROM book_tags")
        db.execSQL("DELETE FROM annotations")
        db.execSQL("DELETE FROM progress")
        db.execSQL("DELETE FROM reading_sessions")
        db.execSQL("DELETE FROM downloads")
        db.execSQL("DELETE FROM books")
        db.execSQL("DELETE FROM tags")
        db.execSQL("DELETE FROM series")
    }

    private fun queryLong(sql: String, args: Array<String>): Long? =
        db.rawQuery(sql, args).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
}

/** 迁移入口：失败回滚，books.json 由调用方保留。 */
fun migrateInTransaction(
    db: SQLiteDatabase,
    plan: MigrationPlan,
    clearExisting: Boolean = false,
): MigrationApplyResult {
    db.beginTransaction()
    return try {
        val result = LibraryMigrator().apply(plan, SqliteMigrationSink(db), clearExisting)
        db.setTransactionSuccessful()
        result
    } finally {
        db.endTransaction()
    }
}
