package app.particlebook.android.data

import android.content.Context

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
        repository?.let { return@synchronized it }
        val helper = LibraryDatabase(context.applicationContext)
        try {
            val repo = helper.repository()
            database = helper
            repository = repo
            repo
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
    fun migrationRunner(context: Context): LegacyMigrationRunner = synchronized(lock) {
        val helper = database ?: error("LibraryDatabaseProvider 未 initialize")
        LegacyMigrationRunner.forContext(context.applicationContext, helper)
    }

    /** 一次性迁移的便捷入口；语义见 [LegacyMigrationRunner.run]。 */
    fun migrateLegacy(context: Context): LegacyMigrationResult =
        migrationRunner(context).run()

    /** 关闭连接并清空单例；可安全重复调用。 */
    fun close() = synchronized(lock) {
        database?.close()
        database = null
        repository = null
    }
}
