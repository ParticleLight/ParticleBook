package app.particlebook.android.data

import app.particlebook.android.ui.data.TestJson
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryMigrationTest {

    private class TestJsonParser : LegacyJsonParser {
        override fun parseObject(raw: String): Map<String, Any?> =
            TestJson.asObject(TestJson.parse(raw), "json")

        override fun parseArray(raw: String): List<Any?> =
            TestJson.asArray(TestJson.parse(raw), "json")
    }

    private fun loader(): LegacyLibraryLoader = LegacyLibraryLoader(LegacyLibraryReader(TestJsonParser()))

    @Test
    fun thousandBooksRoundTripThroughJsonPlanAndRepository() {
        val books = (1L..1000L).map { id -> legacyBook(id) }
        val json = booksJson(books)
        val progress = mapOf("page_1" to 4, "pages_1" to 200, "last_read_1" to 1_700_000_000_000L)
        val loaded = loader().load(json, downloadsJson(listOf(legacyDownload(4, "1") )), progress)
        assertNull(loaded.fatalError)
        assertEquals(1000, loaded.snapshot.books.size)

        val plan = LibraryMigrator(fileExists = { path -> path != "missing" }).buildPlan(loaded.snapshot)
        assertEquals(1000, plan.stats.inputBooks)
        assertEquals(1000, plan.stats.uniqueBooks)
        assertEquals(0, plan.stats.duplicateBooks)
        assertEquals(1000, plan.books.map { it.stableKey }.distinct().size)

        val repository = TestLibraryRepository()
        val applied = LibraryMigrator(fileExists = { path -> path != "missing" })
            .apply(plan, repository.asSink())
        assertEquals(1000, applied.books)
        assertEquals(1000, repository.bookCount())
        val verification = LibraryMigrator().verify(plan, repository)
        assertTrue(verification.errors.toString(), verification.ok)

        val first = repository.bookById(1)!!
        assertEquals("Book 1", first.title)
        assertEquals("pdf", first.format) // legacyBook(1) 的 format 是 PDF 大写，迁移应收敛
        assertEquals("content://library/1.pdf", first.filePath)
        assertEquals(listOf("tag-1"), repository.tagsForBook(1))
        assertEquals(4, repository.progress(1)?.page ?: -1)
        assertEquals(200, repository.progress(1)?.totalPages ?: -1)
        assertTrue(plan.idRemap.keys.containsAll((1L..1000L).toList()))
    }

    @Test
    fun originalBooksJsonFileIsNeverDeletedOrRewritten() {
        val file = Files.createTempFile("particlebook-books-", ".json").toFile()
        try {
            val books = (1L..20L).map { legacyBook(it) }
            file.writeText(booksJson(books), Charsets.UTF_8)
            val before = file.readText()
            val loaded = loader().load(file.readText())
            val plan = LibraryMigrator().buildPlan(loaded.snapshot)
            val repository = TestLibraryRepository()
            LibraryMigrator().apply(plan, repository.asSink())
            assertTrue(file.exists())
            assertEquals(before, file.readText())
            assertEquals(20, repository.bookCount())
        } finally {
            file.delete()
        }
    }

    @Test
    fun duplicateStableKeyMergesBooksTagsProgressAndDownloads() {
        val books = listOf(
            LegacyBook(1, "First", "A", "epub", "/books/a.epub", 100, "2024-01-01T00:00:00Z", listOf("zlib:42", "tag:a")),
            LegacyBook(2, "Second", "B", "epub", "/books/b.epub", 200, "2024-01-02T00:00:00Z", listOf("zlib:42", "tag:b")),
        )
        val progress = listOf(LegacyProgress(2, 7, 100, 1_700_000_000_000L))
        val downloads = listOf(legacyDownload(42, "1"))
        val loaded = loader().load(booksJson(books), downloadsJson(downloads), emptyMap())
        val plan = LibraryMigrator(fileExists = { true }).buildPlan(loaded.snapshot.copy(progress = progress))

        assertEquals(1, plan.stats.uniqueBooks)
        assertEquals(1, plan.stats.duplicateBooks)
        assertEquals(1L, plan.idRemap[2L])
        assertEquals(1L, plan.books.single().id)
        assertEquals(setOf("tag:a", "tag:b"), plan.tags.toSet())
        assertEquals(1L, plan.progress.single().bookId)
        assertEquals(7, plan.progress.single().page ?: -1)
        assertEquals(1L, plan.downloads.single().bookId)
        assertTrue(plan.warnings.toString(), plan.warnings.any { it.contains("重复书籍 stable_key=zlib:42") })
    }

    @Test
    fun duplicateIdWithDifferentStableKeysGetsSyntheticId() {
        val sha = StableKey.sha256Hex("second")
        val books = listOf(
            LegacyBook(7, "First", "A", "epub", "/a.epub", 1, null, listOf("zlib:1")),
            LegacyBook(7, "Second", "B", "epub", "/b.epub", 2, null, listOf("fp:2-" + sha)),
        )
        val plan = LibraryMigrator().buildPlan(LegacySnapshot(books, emptyList(), emptyList()))
        assertEquals(2, plan.books.size)
        assertEquals(7L, plan.books[0].id)
        assertEquals(8L, plan.books[1].id)
        assertEquals(7L, plan.idRemap[7L])
        assertTrue(plan.warnings.any { it.contains("重复 id") })
    }

    @Test
    fun corruptedJsonIsFatalAndKeepsSnapshotEmpty() {
        val loaded = loader().load("{not-json", null, emptyMap())
        assertNotNull(loaded.fatalError)
        assertTrue(loaded.snapshot.books.isEmpty())
        val plan = LibraryMigrator().buildPlan(loaded.snapshot)
        assertEquals(0, plan.books.size)
        val repository = TestLibraryRepository()
        LibraryMigrator().apply(plan, repository.asSink())
        assertEquals(0, repository.bookCount())
    }

    @Test
    fun emptyAndMissingJsonAreSafeNoOps() {
        listOf(null, "", "   ", "{\"version\":1,\"books\":[]}").forEach { raw ->
            val loaded = loader().load(raw)
            assertNull("空白/空库不应 fatal: " + raw, loaded.fatalError)
            assertEquals(0, loaded.snapshot.books.size)
        }
    }

    @Test
    fun brokenDownloadsAreSkippedWithoutBlockingBooks() {
        val loaded = loader().load(booksJson(listOf(legacyBook(1))), "{broken", emptyMap())
        assertNull(loaded.fatalError)
        assertEquals(1, loaded.snapshot.books.size)
        assertTrue(loaded.warnings.any { it.contains("下载历史") })
    }

    @Test
    fun missingProgressBookAndOrphanDownloadAreWarningsNotCrashes() {
        val snapshot = LegacySnapshot(
            books = listOf(legacyBook(1)),
            progress = listOf(LegacyProgress(999, 3, 10, 1_700_000_000_000L)),
            downloads = listOf(legacyDownload(999, "9")),
        )
        val plan = LibraryMigrator().buildPlan(snapshot)
        assertEquals(0, plan.progress.size)
        assertEquals(1, plan.downloads.size)
        assertNull(plan.downloads.single().bookId)
        assertTrue(plan.warnings.any { it.contains("进度指向不存在的") })
        assertTrue(plan.warnings.any { it.contains("找不到对应书架条目") })
        assertEquals(1, plan.stats.skippedProgress)
    }

    @Test
    fun duplicateDownloadsKeepLatestFinishedAtAndDeduplicate() {
        val snapshot = LegacySnapshot(
            books = listOf(legacyBook(1)),
            progress = emptyList(),
            downloads = listOf(
                legacyDownload(42, "1", status = "failed", finishedAt = 100),
                legacyDownload(42, "1", status = "completed", finishedAt = 200),
            ),
        )
        val plan = LibraryMigrator().buildPlan(snapshot)
        assertEquals(1, plan.downloads.size)
        assertEquals("completed", plan.downloads.single().status)
        assertEquals(200L, plan.downloads.single().finishedAt)
    }

    @Test
    fun downloadsWithSameSourceAndDestButDifferentTitlesKeepOnlyLatest() {
        val snapshot = LegacySnapshot(
            books = listOf(legacyBook(1)),
            progress = emptyList(),
            downloads = listOf(
                legacyDownload(42, "1").copy(title = "Old title", finishedAt = 100),
                legacyDownload(42, "1").copy(title = "New title", finishedAt = 200),
            ),
        )
        val plan = LibraryMigrator().buildPlan(snapshot)
        // 与 downloads UNIQUE(source_book_id, dest_path) 对齐：标题不同也算同一条。
        assertEquals(1, plan.downloads.size)
        assertEquals("New title", plan.downloads.single().title)
        assertEquals(200L, plan.downloads.single().finishedAt)
    }

    @Test
    fun unsupportedFormatIsPreservedAsRawNotPretendedEpub() {
        val books = listOf(legacyBook(1).copy(format = "mobi"))
        val plan = LibraryMigrator().buildPlan(LegacySnapshot(books, emptyList(), emptyList()))
        assertEquals("mobi", plan.books.single().format)
    }

    private fun legacyBook(id: Long): LegacyBook {
        val format = when (id % 9L) {
            0L -> "pdf"
            1L -> "epub"
            2L -> "txt"
            3L -> "html"
            4L -> "md"
            5L -> "fb2"
            6L -> "azw3"
            7L -> "docx"
            else -> "cbz"
        }
        val tags = when (id % 4L) {
            0L -> listOf("zlib:" + id)
            1L -> listOf("saf-uri:" + StableKey.sha256Hex("uri-" + id))
            2L -> listOf("fp:" + (id * 10) + "-" + StableKey.sha256Hex("fp-" + id))
            else -> emptyList()
        }
        val effectiveFormat = if (id == 1L) "pdf" else format
        return LegacyBook(
            id = id,
            title = "Book " + id,
            author = "Author " + (id % 10),
            format = if (id == 1L) "PDF" else format,
            filePath = if (id == 500L) "missing" else "content://library/" + id + "." + effectiveFormat,
            fileSize = id * 10,
            addedAtRaw = "2024-01-01T00:00:00Z",
            tags = tags + listOf("tag-" + (id % 25)),
        )
    }

    private fun legacyDownload(bookId: Long, id: String, status: String = "completed", finishedAt: Long = 1_700_000_000_000L) =
        LegacyDownload(
            bookId = bookId,
            title = "Book " + id,
            author = "Author",
            format = "epub",
            downloadPath = "/downloads/" + id,
            fileName = "book.epub",
            destPath = "/books/" + id + ".epub",
            storage = "file",
            size = 10,
            total = 10,
            status = status,
            errorKind = null,
            errorMessage = null,
            finishedAt = finishedAt,
        )

    private fun booksJson(books: List<LegacyBook>): String = buildString {
        append("{\"version\":1,\"books\":[")
        books.forEachIndexed { index, book ->
            if (index > 0) append(',')
            append('{')
            append("\"id\":").append(book.id).append(',')
            append("\"title\":").append(TestJson.quote(book.title)).append(',')
            append("\"author\":").append(TestJson.quote(book.author)).append(',')
            append("\"format\":").append(TestJson.quote(book.format)).append(',')
            append("\"filePath\":").append(TestJson.quote(book.filePath)).append(',')
            append("\"fileSize\":").append(book.fileSize).append(',')
            append("\"addedAt\":").append(TestJson.quote(book.addedAtRaw ?: "")).append(',')
            append("\"tags\":[").append(book.tags.joinToString(",") { TestJson.quote(it) }).append(']')
            append('}')
        }
        append("]}")
    }

    private fun downloadsJson(downloads: List<LegacyDownload>): String = buildString {
        append("{\"version\":1,\"downloads\":[")
        downloads.forEachIndexed { index, download ->
            if (index > 0) append(',')
            append('{')
            append("\"bookId\":").append(download.bookId ?: 0).append(',')
            append("\"title\":").append(TestJson.quote(download.title)).append(',')
            append("\"author\":").append(TestJson.quote(download.author)).append(',')
            append("\"format\":").append(TestJson.quote(download.format)).append(',')
            append("\"downloadPath\":").append(TestJson.quote(download.downloadPath)).append(',')
            append("\"fileName\":").append(TestJson.quote(download.fileName)).append(',')
            append("\"filePath\":").append(TestJson.quote(download.destPath)).append(',')
            append("\"storage\":").append(TestJson.quote(download.storage)).append(',')
            append("\"size\":").append(download.size).append(',')
            append("\"total\":").append(download.total).append(',')
            append("\"status\":").append(TestJson.quote(download.status)).append(',')
            append("\"finishedAt\":").append(download.finishedAt)
            append('}')
        }
        append("]}")
    }

    private fun TestLibraryRepository.asSink(): MigrationSink = object : MigrationSink {
        override fun insertBook(row: BookRow): Long = saveBook(row)
        override fun insertTag(name: String): Long {
            // 内存 repository 没有独立 tags 表；tag id 仅用于 sink 契约，真实 SQLite sink 会写入 tags 表。
            return name.hashCode().toLong()
        }
        override fun linkBookTag(bookId: Long, tagName: String) = addTag(bookId, tagName)
        override fun insertProgress(row: ProgressRow) = saveProgress(row)
        override fun insertDownload(row: DownloadRow) {
            addDownload(row)
        }
        override fun clearExisting() {
            books().forEach { deleteBook(it.id) }
        }
    }
}
