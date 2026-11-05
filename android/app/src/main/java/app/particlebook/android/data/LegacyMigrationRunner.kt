package app.particlebook.android.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.UUID

/**
 * books.json → schema v1 的一次性迁移执行器。
 *
 * 顺序（任何一步失败都不改名原文件）：
 * 1. 读 filesDir/books.json、filesDir/zlib_downloads.json、SharedPreferences("pb_reading")；
 * 2. LegacyLibraryLoader 解析 → LibraryMigrator.buildPlan；
 * 3. 在一个 SQLite 事务内：确认 books 为空 → apply → verify → 写 migration_state=completed；
 * 4. 事务提交且 verify 通过后，才把 books.json 改名为 books.json.migrated。
 *
 * 幂等：migration_state.status=completed 时直接返回 [LegacyMigrationResult.AlreadyCompleted]，
 * 不再导入、不再改名。进程在「事务提交」与「改名」之间被杀时，下一次启动同样不会重复导入，
 * 原 books.json 会保留（这是有意的只读回退副本）。
 *
 * 注意：这是可注入 [LegacyMigrationStore] 的纯 JVM 核心；SQLite 生产实现见
 * [SqliteLegacyMigrationStore]，单测用内存 store 验证回退/幂等。
 */
class LegacyMigrationRunner(
    private val dataDir: File,
    private val readPreferences: () -> Map<String, Any?>,
    private val store: LegacyMigrationStore,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val loader: LegacyLibraryLoader = LegacyLibraryLoader(),
) {

    fun run(): LegacyMigrationResult {
        val source = File(dataDir, BOOKS_FILE_NAME)
        val completedState = try {
            store.readState()
        } catch (error: Throwable) {
            return LegacyMigrationResult.Failed(
                stage = LegacyMigrationFailureStage.READ_STATE,
                message = "读取 migration_state 失败: " + error.message,
                sourcePreserved = source.isFile,
            )
        }
        if (completedState?.status == STATUS_COMPLETED) {
            return LegacyMigrationResult.AlreadyCompleted(completedState)
        }
        if (!source.isFile) {
            return LegacyMigrationResult.NoSource(completed = false)
        }

        val sourceText = try {
            source.readText(Charsets.UTF_8)
        } catch (error: Throwable) {
            return LegacyMigrationResult.Failed(
                stage = LegacyMigrationFailureStage.READ_SOURCE,
                message = "读取 books.json 失败: " + error.message,
                sourcePreserved = source.isFile,
            )
        }

        val warnings = mutableListOf<String>()
        val downloadsText = readOptional(
            file = File(dataDir, DOWNLOADS_FILE_NAME),
            label = "zlib_downloads.json",
            warnings = warnings,
        )
        val preferences = try {
            readPreferences()
        } catch (error: Throwable) {
            warnings += "读取 pb_reading 失败，进度跳过: " + (error.message ?: error.javaClass.simpleName)
            emptyMap()
        }

        val loaded = try {
            loader.load(sourceText, downloadsText, preferences)
        } catch (error: Throwable) {
            return LegacyMigrationResult.Failed(
                stage = LegacyMigrationFailureStage.PARSE_SOURCE,
                message = "解析旧数据失败: " + (error.message ?: error.javaClass.simpleName),
                warnings = warnings,
                sourcePreserved = source.isFile,
            )
        }
        loaded.fatalError?.let { fatal ->
            return LegacyMigrationResult.Failed(
                stage = LegacyMigrationFailureStage.PARSE_SOURCE,
                message = fatal,
                warnings = warnings + loaded.warnings,
                sourcePreserved = source.isFile,
            )
        }
        warnings += loaded.warnings

        val plan = try {
            LibraryMigrator(fileExists = ::defaultFileExists).buildPlan(loaded.snapshot)
        } catch (error: Throwable) {
            return LegacyMigrationResult.Failed(
                stage = LegacyMigrationFailureStage.PLAN,
                message = "生成迁移计划失败: " + (error.message ?: error.javaClass.simpleName),
                warnings = warnings,
                sourcePreserved = source.isFile,
            )
        }
        warnings += plan.warnings

        val applied: MigrationApplyResult
        val verification: MigrationVerification
        try {
            val pair = store.runInTransaction {
                if (store.bookCount() > 0) {
                    throw DatabaseNotEmptyException()
                }
                val result = store.apply(plan)
                val check = store.verify(plan)
                if (!check.ok) {
                    throw VerifyFailedException(check)
                }
                store.markCompleted(detailsJson(result, warnings))
                result to check
            }
            applied = pair.first
            verification = pair.second
        } catch (error: VerifyFailedException) {
            return LegacyMigrationResult.Failed(
                stage = LegacyMigrationFailureStage.VERIFY,
                message = "迁移校验失败，事务已回滚",
                errors = error.verification.errors,
                warnings = warnings,
                sourcePreserved = source.isFile,
            )
        } catch (error: DatabaseNotEmptyException) {
            return LegacyMigrationResult.Failed(
                stage = LegacyMigrationFailureStage.DATABASE,
                message = "数据库已有书籍，拒绝覆盖；请先确认 migration_state 与备份",
                warnings = warnings,
                sourcePreserved = source.isFile,
            )
        } catch (error: Throwable) {
            return LegacyMigrationResult.Failed(
                stage = LegacyMigrationFailureStage.DATABASE,
                message = "写入 SQLite 失败，事务已回滚: " + (error.message ?: error.javaClass.simpleName),
                warnings = warnings,
                sourcePreserved = source.isFile,
            )
        }

        val migratedTo = nextMigratedFile()
        val renamed = try {
            source.renameTo(migratedTo)
        } catch (error: Throwable) {
            false
        }
        if (!renamed) {
            return LegacyMigrationResult.Failed(
                stage = LegacyMigrationFailureStage.RENAME,
                message = "数据库已迁移且校验通过，但 books.json 改名失败；原文件保留",
                warnings = warnings,
                databaseCommitted = true,
                sourcePreserved = source.isFile,
            )
        }
        return LegacyMigrationResult.Completed(
            applied = applied,
            verification = verification,
            migratedPath = migratedTo.absolutePath,
            warnings = warnings,
        )
    }

    /**
     * 真实文件存在性：空路径视为缺失；SAF content:// 无法用 File 探测，按存在处理，
     * 避免把云端/授权文档误标 missing。异常一律按缺失，不让迁移因此失败。
     */
    private fun defaultFileExists(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.startsWith("content://")) return true
        return try {
            File(path).isFile
        } catch (_: Exception) {
            false
        }
    }

    private fun readOptional(file: File, label: String, warnings: MutableList<String>): String? {
        if (!file.isFile) return null
        return try {
            file.readText(Charsets.UTF_8)
        } catch (error: Throwable) {
            warnings += "读取 " + label + " 失败，已跳过: " + (error.message ?: error.javaClass.simpleName)
            null
        }
    }

    /** 不覆盖已有 .migrated；极端重名时加时间/随机后缀保留两份证据。 */
    private fun nextMigratedFile(): File {
        val preferred = File(dataDir, BOOKS_MIGRATED_NAME)
        if (!preferred.exists()) return preferred
        var candidate: File
        do {
            candidate = File(dataDir, BOOKS_MIGRATED_NAME + "." + now() + "." + UUID.randomUUID().toString().take(8))
        } while (candidate.exists())
        return candidate
    }

    private fun detailsJson(applied: MigrationApplyResult, warnings: List<String>): String =
        "{\"books\":" + applied.books +
            ",\"tags\":" + applied.tags +
            ",\"progress\":" + applied.progress +
            ",\"downloads\":" + applied.downloads +
            ",\"warnings\":" + warnings.size + "}"

    private class DatabaseNotEmptyException : IllegalStateException("books 表非空")

    private class VerifyFailedException(val verification: MigrationVerification) :
        IllegalStateException("迁移校验失败")

    companion object {
        const val BOOKS_FILE_NAME = "books.json"
        const val BOOKS_MIGRATED_NAME = "books.json.migrated"
        const val DOWNLOADS_FILE_NAME = "zlib_downloads.json"
        const val PREFS_NAME = "pb_reading"
        const val STATUS_COMPLETED = "completed"

        /** 生产工厂：读取真实 filesDir/SharedPreferences，复用传入的 LibraryDatabase。 */
        fun forContext(context: Context, database: LibraryDatabase): LegacyMigrationRunner {
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return LegacyMigrationRunner(
                dataDir = appContext.filesDir,
                readPreferences = { HashMap(prefs.all) },
                store = SqliteLegacyMigrationStore(database.writableDatabase),
            )
        }
    }
}

sealed class LegacyMigrationResult {
    data class Completed(
        val applied: MigrationApplyResult,
        val verification: MigrationVerification,
        val migratedPath: String,
        val warnings: List<String>,
    ) : LegacyMigrationResult()

    data class AlreadyCompleted(val state: MigrationStateRow) : LegacyMigrationResult()

    data class NoSource(val completed: Boolean = false) : LegacyMigrationResult()

    data class Failed(
        val stage: LegacyMigrationFailureStage,
        val message: String,
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
        val databaseCommitted: Boolean = false,
        val sourcePreserved: Boolean = true,
    ) : LegacyMigrationResult()
}

enum class LegacyMigrationFailureStage {
    READ_STATE,
    READ_SOURCE,
    PARSE_SOURCE,
    PLAN,
    DATABASE,
    VERIFY,
    RENAME,
}

/**
 * 迁移执行器依赖的 DB 边界；JVM 测试用内存实现，生产用 [SqliteLegacyMigrationStore]。
 * [runInTransaction] 必须保证 block 抛异常时 apply/verify/markCompleted 全部回滚。
 */
interface LegacyMigrationStore {
    fun readState(): MigrationStateRow?
    fun bookCount(): Int
    fun apply(plan: MigrationPlan): MigrationApplyResult
    fun verify(plan: MigrationPlan): MigrationVerification
    fun markCompleted(detailsJson: String)
    fun <T> runInTransaction(block: () -> T): T
}

/** 生产实现：所有迁移写入与 verify 在同一 SQLite 事务里。 */
class SqliteLegacyMigrationStore(private val db: SQLiteDatabase) : LegacyMigrationStore {

    override fun readState(): MigrationStateRow? = db.rawQuery(
        "SELECT id, status, started_at, finished_at, details_json FROM migration_state WHERE id = 1",
        null,
    ).use { cursor ->
        if (!cursor.moveToFirst()) {
            null
        } else {
            MigrationStateRow(
                id = cursor.getLong(0),
                status = cursor.getString(1).orEmpty(),
                startedAt = cursor.getLong(2),
                finishedAt = if (cursor.isNull(3)) null else cursor.getLong(3),
                detailsJson = if (cursor.isNull(4)) null else cursor.getString(4),
            )
        }
    }

    override fun bookCount(): Int = db.rawQuery("SELECT COUNT(*) FROM books", null).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }

    override fun apply(plan: MigrationPlan): MigrationApplyResult =
        LibraryMigrator().apply(plan, SqliteMigrationSink(db), clearExisting = false)

    override fun verify(plan: MigrationPlan): MigrationVerification =
        LibraryMigrator().verify(plan, SqliteLibraryRepository(db))

    override fun markCompleted(detailsJson: String) {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("id", 1)
            put("status", LegacyMigrationRunner.STATUS_COMPLETED)
            put("started_at", now)
            put("finished_at", now)
            put("details_json", detailsJson)
        }
        db.insertWithOnConflict(
            LibrarySchema.TABLE_MIGRATION_STATE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun <T> runInTransaction(block: () -> T): T {
        db.beginTransaction()
        return try {
            val result = block()
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }
}
