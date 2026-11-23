package app.particlebook.android.data

import android.content.Context
import java.io.File

/**
 * 进程内 SQLite 单例。
 *
 * 用法（P1 接入方）：
 * 1. 在 Application/Activity 启动时调用 [initialize]（内部只打开一次）；
 * 2. 所有读写通过 [repository] 或 [migrateLegacy]；
 * 3. 测试/进程退出时调用 [close]。
 *
 * 约束：
 * - 所有方法同步；repository 的读写由调用方放到 IO dispatcher（主线程零 IO）；
 * - SQLite 仍是单写者模型，写操作应由接入层的单写者队列串行化；
 * - [close] 后再次 [initialize] 会重新打开数据库，但不应在 App 运行期反复 close/open。
 */
object LibraryDatabaseProvider {

    private val lock = Any()

    private var database: LibraryDatabase? = null
    private var repository: SqliteLibraryRepository? = null

    /** 打开数据库并返回单例仓库；重复调用返回同一实例。 */
    fun initialize(context: Context): LibraryRepository = synchronized(lock) {
        openLocked(context.applicationContext)
    }

    /**
     * 跨书全文索引的写入端口：与书架/标注共用同一连接和单例，打开成本仍只有一次。
     * 返回类型收窄到 [SearchIndexStore]，索引器因此看不到无关的写方法。
     */
    fun searchIndexStore(context: Context): SearchIndexStore = synchronized(lock) {
        openLocked(context.applicationContext)
    }

    private fun openLocked(appContext: Context): SqliteLibraryRepository {
        repository?.let { return it }
        val helper = LibraryDatabase(appContext)
        try {
            val repo = helper.repository()
            database = helper
            repository = repo
            return repo
        } catch (error: Throwable) {
            helper.close()
            throw error
        }
    }

    /** 未初始化时直接失败，避免调用方静默拿到空库。 */
    fun repository(): LibraryRepository = synchronized(lock) {
        repository ?: error("LibraryDatabaseProvider 未 initialize")
    }

    fun isInitialized(): Boolean = synchronized(lock) { repository != null }

    /** 迁移执行器；复用同一个数据库连接，不单独 open。 */
    fun migrationRunner(
        context: Context,
        preserveSource: Boolean = false,
    ): LegacyMigrationRunner = synchronized(lock) {
        val helper = database ?: error("LibraryDatabaseProvider 未 initialize")
        LegacyMigrationRunner.forContext(context.applicationContext, helper, preserveSource)
    }

    /**
     * A014-3：迁移前把 SQLite 主库复制成 `particlebook.db.pre-migration.bak`。
     *
     * - 只在 books.json 仍存在时调用（没有旧数据就没有迁移风险）；
     * - 先 WAL checkpoint(TRUNCATE)，避免只复制主库而漏掉 -wal 里的已提交页；
     * - 已存在备份不覆盖：保留最早一次「迁移前」快照，重复启动不把好库覆盖成半迁移库。
     * 返回备份文件；库尚未打开或主库不存在时返回 null。
     */
    fun backupBeforeMigration(context: Context): File? = synchronized(lock) {
        val helper = database ?: return@synchronized null
        val appContext = context.applicationContext
        val source = appContext.getDatabasePath(LibrarySchema.DATABASE_NAME)
        if (!source.isFile) return@synchronized null
        runCatching {
            helper.writableDatabase
                .rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)
                .use { cursor -> cursor.moveToFirst() }
        }
        val backup = File(source.parentFile, source.name + ".pre-migration.bak")
        if (backup.isFile) return@synchronized backup
        return@synchronized runCatching {
            source.copyTo(backup, overwrite = false)
            backup
        }.getOrNull()
    }

    /** 一次性迁移的便捷入口；语义见 [LegacyMigrationRunner.run]。 */
    fun migrateLegacy(
        context: Context,
        preserveSource: Boolean = false,
    ): LegacyMigrationResult = migrationRunner(context, preserveSource).run()

    /** 关闭连接并清空单例；可安全重复调用。 */
    fun close() = synchronized(lock) {
        database?.close()
        database = null
        repository = null
    }
}
