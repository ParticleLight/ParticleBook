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

/**
 * LegacyMigrationRunner 的 JVM 测试：真实 books.json 文件 + 内存 repository/store。
 *
 * 覆盖：成功迁移并改名、verify 失败回滚、写入失败回滚、重复/坏数据、数据库非空拒绝、
 * completed 幂等、.migrated 重名保留、prefs 读取失败降级。
 */
class LegacyMigrationRunnerTest {

    private class TestJsonParser : LegacyJsonParser {
        override fun parseObject(raw: String): Map<String, Any?> =
            TestJson.asObject(TestJson.parse(raw), "json")

        override fun parseArray(raw: String): List<Any?> =
            TestJson.asArray(TestJson.parse(raw), "json")
    }

    private fun loader(): LegacyLibraryLoader =
        LegacyLibraryLoader(LegacyLibraryReader(TestJsonParser()))

    private class FakeMigrationStore(
        val repository: TestLibraryRepository = TestLibraryRepository(),
    ) : LegacyMigrationStore {
        var state: MigrationStateRow? = null
        var failApply = false
        var failVerify = false
        var applyCalls = 0
        var markCompletedCalls = 0

        override fun readState(): MigrationStateRow? = state

        override fun bookCount(): Int = repository.bookCount()

        override fun apply(plan: MigrationPlan): MigrationApplyResult {
            applyCalls++
            if (failApply) error("disk full")
            return LibraryMigrator().apply(plan, repository.asMigrationSink())
        }

        override fun verify(plan: MigrationPlan): MigrationVerification =
            if (failVerify) {
                MigrationVerification(ok = false, errors = listOf("forced verify failure"))
            } else {
                LibraryMigrator().verify(plan, repository)
            }

        override fun markCompleted(detailsJson: String) {
            markCompletedCalls++
            state = MigrationStateRow(
                status = LegacyMigrationRunner.STATUS_COMPLETED,
                startedAt = 1,
                finishedAt = 2,
                detailsJson = detailsJson,
            )
        }

        override fun <T> runInTransaction(block: () -> T): T {
            val previousState = state
            return try {
                repository.transaction { _ -> block() }
            } catch (error: Throwable) {
                state = previousState
                throw error
            }
        }
    }

    private fun tempDir(): File = Files.createTempDirectory("pb-migration-runner-").toFile()

    private fun sampleBooksJson(): String = buildString {
        append("{\"version\":1,\"books\":[")
        append("{\"id\":1,\"title\":\"第一本\",\"author\":\"作者甲\",")
        append("\"format\":\"epub\",\"filePath\":\"/books/1.epub\",\"fileSize\":123,")
        append("\"addedAt\":\"2026-01-02T03:04:05Z\",\"tags\":[\"zlib:1\",\"读书\"]},")
        append("{\"id\":2,\"title\":\"第二本\",\"author\":\"作者乙\",")
        append("\"format\":\"PDF\",\"file_path\":\"/books/2.pdf\",\"file_size\":456,")
        append("\"added_at\":\"2026-02-03T04:05:06Z\",\"tags\":[\"zlib:2\"]}")
        append("]}")
    }

    private fun sampleDownloadsJson(): String =
        "{\"version\":1,\"downloads\":[{\"bookId\":1,\"title\":\"第一本\",\"format\":\"epub\"," +
            "\"downloadPath\":\"/dl/1.epub\",\"fileName\":\"1.epub\",\"filePath\":\"/books/1.epub\"," +
            "\"storage\":\"file\",\"size\":123,\"total\":123,\"status\":\"completed\"," +
            "\"finishedAt\":1700000000000}]}"

    private fun writeSource(dir: File, json: String = sampleBooksJson()): File =
        File(dir, LegacyMigrationRunner.BOOKS_FILE_NAME).apply { writeText(json) }

    private fun runner(
        dir: File,
        store: FakeMigrationStore,
        prefs: () -> Map<String, Any?> = {
            mapOf("page_1" to 3, "pages_1" to 100, "last_read_1" to 1700000000000L)
        },
        now: () -> Long = { 123L },
    ): LegacyMigrationRunner = LegacyMigrationRunner(
        dataDir = dir,
        readPreferences = prefs,
        store = store,
        now = now,
        loader = loader(),
    )

    @Test
    fun migratesRealBooksPrefsAndDownloadsThenRenamesSource() {
        val dir = tempDir()
        val source = writeSource(dir)
        val original = source.readText()
        File(dir, LegacyMigrationRunner.DOWNLOADS_FILE_NAME).writeText(sampleDownloadsJson())
        val store = FakeMigrationStore()

        val result = runner(dir, store).run()

        assertTrue(result.toString(), result is LegacyMigrationResult.Completed)
        result as LegacyMigrationResult.Completed
        assertTrue(result.verification.errors.toString(), result.verification.ok)
        assertEquals(2, result.applied.books)
        assertEquals(2, store.repository.bookCount())
        assertEquals(3, store.repository.progress(1)?.page)
        assertEquals(100, store.repository.progress(1)?.totalPages)
        assertEquals(1, store.repository.downloads().size)
        assertEquals("completed", store.state?.status)
        assertNotNull(store.state?.detailsJson)
        assertFalse("成功后原文件必须改名", source.exists())
        val migrated = File(result.migratedPath)
        assertTrue(migrated.isFile)
        assertEquals(original, migrated.readText())
    }

    @Test
    fun duplicateStableKeyMergesToOneBookBeforeVerify() {
        val dir = tempDir()
        val json = buildString {
            append("{\"version\":1,\"books\":[")
            append("{\"id\":1,\"title\":\"合并前\",\"format\":\"epub\",\"filePath\":\"/a.epub\",")
            append("\"fileSize\":10,\"tags\":[\"zlib:42\"]},")
            append("{\"id\":2,\"title\":\"合并后\",\"format\":\"epub\",\"filePath\":\"/b.epub\",")
            append("\"fileSize\":20,\"tags\":[\"zlib:42\",\"读书\"]}]}")
        }
        val source = writeSource(dir, json)
        val store = FakeMigrationStore()

        val result = runner(dir, store).run()

        assertTrue(result.toString(), result is LegacyMigrationResult.Completed)
        assertEquals(1, store.repository.bookCount())
        assertEquals(1L, store.repository.books().single().id)
        assertEquals(listOf("读书"), store.repository.tagsForBook(1))
        assertFalse(source.exists())
    }

    @Test
    fun malformedBookEntryIsSkippedButValidOnesMigrate() {
        val dir = tempDir()
        val json = buildString {
            append("{\"version\":1,\"books\":[")
            append("{\"id\":0,\"title\":\"坏条目\",\"format\":\"epub\",\"filePath\":\"/bad.epub\"},")
            append("{\"id\":5,\"title\":\"好条目\",\"format\":\"epub\",\"filePath\":\"/good.epub\",")
            append("\"fileSize\":5,\"tags\":[\"zlib:5\"]}]}")
        }
        val source = writeSource(dir, json)
        val store = FakeMigrationStore()

        val result = runner(dir, store).run()

        assertTrue(result.toString(), result is LegacyMigrationResult.Completed)
        assertEquals(1, store.repository.bookCount())
        assertEquals("好条目", store.repository.bookById(5)?.title)
        assertFalse(source.exists())
    }

    @Test
    fun verifyFailureRollsBackDatabaseAndKeepsSource() {
        val dir = tempDir()
        val source = writeSource(dir)
        val original = source.readText()
        val store = FakeMigrationStore().also { it.failVerify = true }

        val result = runner(dir, store).run()

        assertTrue(result.toString(), result is LegacyMigrationResult.Failed)
        result as LegacyMigrationResult.Failed
        assertEquals(LegacyMigrationFailureStage.VERIFY, result.stage)
        assertTrue(result.errors.toString(), result.errors.any { it.contains("forced") })
        assertEquals(0, store.repository.bookCount())
        assertNull("verify 失败不能留下 completed 状态", store.state)
        assertEquals(0, store.markCompletedCalls)
        assertTrue(source.isFile)
        assertEquals(original, source.readText())
        assertFalse(File(dir, LegacyMigrationRunner.BOOKS_MIGRATED_NAME).exists())
    }

    @Test
    fun applyFailureRollsBackDatabaseAndKeepsSource() {
        val dir = tempDir()
        val source = writeSource(dir)
        val store = FakeMigrationStore().also { it.failApply = true }

        val result = runner(dir, store).run()

        assertTrue(result.toString(), result is LegacyMigrationResult.Failed)
        result as LegacyMigrationResult.Failed
        assertEquals(LegacyMigrationFailureStage.DATABASE, result.stage)
        assertTrue(result.message.contains("disk full"))
        assertEquals(0, store.repository.bookCount())
        assertNull(store.state)
        assertTrue(source.isFile)
        assertFalse(File(dir, LegacyMigrationRunner.BOOKS_MIGRATED_NAME).exists())
    }

    @Test
    fun malformedBooksJsonFailsBeforeTouchingDatabase() {
        val dir = tempDir()
        val source = writeSource(dir, "{\"version\":1,\"books\":[")
        val original = source.readText()
        val store = FakeMigrationStore()

        val result = runner(dir, store).run()

        assertTrue(result.toString(), result is LegacyMigrationResult.Failed)
        result as LegacyMigrationResult.Failed
        assertEquals(LegacyMigrationFailureStage.PARSE_SOURCE, result.stage)
        assertEquals(0, store.applyCalls)
        assertEquals(0, store.repository.bookCount())
        assertEquals(original, source.readText())
    }

    @Test
    fun nonEmptyDatabaseIsRefusedRatherThanOverwritten() {
        val dir = tempDir()
        val source = writeSource(dir)
        val store = FakeMigrationStore().also {
            it.repository.saveBook(BookRow(id = 99, stableKey = "zlib:99", title = "existing"))
        }

        val result = runner(dir, store).run()

        assertTrue(result.toString(), result is LegacyMigrationResult.Failed)
        result as LegacyMigrationResult.Failed
        assertEquals(LegacyMigrationFailureStage.DATABASE, result.stage)
        assertEquals(1, store.repository.bookCount())
        assertEquals("existing", store.repository.bookById(99)?.title)
        assertTrue(source.isFile)
        assertNull(store.state)
    }

    @Test
    fun completedMigrationIsIdempotentAndNeverImportsTwice() {
        val dir = tempDir()
        val source = writeSource(dir)
        val store = FakeMigrationStore()
        val first = runner(dir, store).run()
        assertTrue(first is LegacyMigrationResult.Completed)
        val migratedBytes = File((first as LegacyMigrationResult.Completed).migratedPath).readText()

        // 模拟旧文件又被恢复/同步回来；completed 状态必须阻止第二次导入。
        source.writeText(migratedBytes)
        val second = runner(dir, store).run()

        assertTrue(second.toString(), second is LegacyMigrationResult.AlreadyCompleted)
        assertEquals(1, store.applyCalls)
        assertEquals(2, store.repository.bookCount())
        assertTrue("completed 后不再改名，保留只读副本", source.isFile)
        assertEquals(migratedBytes, source.readText())
    }

    @Test
    fun existingMigratedFileIsNeverOverwritten() {
        val dir = tempDir()
        val source = writeSource(dir)
        val original = source.readText()
        val existing = File(dir, LegacyMigrationRunner.BOOKS_MIGRATED_NAME)
        existing.writeText("older backup")
        val store = FakeMigrationStore()

        val result = runner(dir, store).run()

        assertTrue(result.toString(), result is LegacyMigrationResult.Completed)
        result as LegacyMigrationResult.Completed
        assertTrue(result.migratedPath, result.migratedPath.contains("books.json.migrated.123."))
        assertEquals("older backup", existing.readText())
        assertEquals(original, File(result.migratedPath).readText())
    }

    @Test
    fun missingSourceIsNoOp() {
        val dir = tempDir()
        val store = FakeMigrationStore()

        val result = runner(dir, store).run()

        assertTrue(result.toString(), result is LegacyMigrationResult.NoSource)
        assertEquals(0, store.applyCalls)
        assertEquals(0, store.repository.bookCount())
    }

    @Test
    fun marksMissingFilesButKeepsSafPaths() {
        val dir = tempDir()
        val real = File(dir, "real.epub").apply { writeText("epub") }
        val jsonPath = real.absolutePath.replace('\\', '/')
        val json = "{\"version\":1,\"books\":[" +
            "{\"id\":1,\"title\":\"真实文件\",\"format\":\"epub\",\"filePath\":\"" + jsonPath +
            "\",\"fileSize\":4,\"tags\":[\"zlib:1\"]}," +
            "{\"id\":2,\"title\":\"SAF\",\"format\":\"pdf\",\"filePath\":\"content://docs/2\"," +
            "\"fileSize\":10,\"tags\":[\"zlib:2\"]}," +
            "{\"id\":3,\"title\":\"缺失\",\"format\":\"epub\",\"filePath\":\"/missing/3.epub\"," +
            "\"fileSize\":10,\"tags\":[\"zlib:3\"]}]}"
        val source = writeSource(dir, json)
        val store = FakeMigrationStore()

        val result = runner(dir, store).run()

        assertTrue(result.toString(), result is LegacyMigrationResult.Completed)
        assertFalse(store.repository.bookById(1)!!.fileMissing)
        assertFalse(store.repository.bookById(2)!!.fileMissing)
        assertTrue(store.repository.bookById(3)!!.fileMissing)
        assertFalse(source.exists())
    }

    @Test
    fun preferenceReadFailureWarnsButStillMigratesBooks() {
        val dir = tempDir()
        val source = writeSource(dir)
        val store = FakeMigrationStore()

        val result = runner(
            dir,
            store,
            prefs = { error("prefs unavailable") },
        ).run()

        assertTrue(result.toString(), result is LegacyMigrationResult.Completed)
        result as LegacyMigrationResult.Completed
        assertTrue(result.warnings.toString(), result.warnings.any { it.contains("pb_reading") })
        assertEquals(2, store.repository.bookCount())
        assertNull(store.repository.progress(1))
        assertFalse(source.exists())
    }
}

private fun TestLibraryRepository.asMigrationSink(): MigrationSink = object : MigrationSink {
    override fun insertBook(row: BookRow): Long = saveBook(row)
    override fun insertTag(name: String): Long = name.hashCode().toLong()
    override fun linkBookTag(bookId: Long, tagName: String) = addTag(bookId, tagName)
    override fun insertProgress(row: ProgressRow) = saveProgress(row)
    override fun insertDownload(row: DownloadRow) {
        addDownload(row)
    }
    override fun clearExisting() {
        books().forEach { deleteBook(it.id) }
    }
}