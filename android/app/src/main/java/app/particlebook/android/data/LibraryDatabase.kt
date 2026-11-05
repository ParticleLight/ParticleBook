package app.particlebook.android.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * framework SQLite 连接管理。
 *
 * - WAL：构造时 setWriteAheadLoggingEnabled(true)，不再手写 journal_mode（避免和 framework 打架）；
 * - foreign_keys：onConfigure 中开启，必须在任何事务/语句之前；
 * - busy_timeout：5s，降低下载完成、索引写入与 UI 读取并发时的 SQLITE_BUSY；
 * - onCreate 只跑 [LibrarySchema.allStatements]，升级路径保留空位，P0 不做破坏性重建。
 */
class LibraryDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    LibrarySchema.DATABASE_NAME,
    null,
    LibrarySchema.VERSION,
) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.rawQuery("PRAGMA busy_timeout = " + LibrarySchema.BUSY_TIMEOUT_MS, null).use { cursor ->
            cursor.moveToFirst()
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        LibrarySchema.allStatements.forEach { sql -> db.execSQL(sql) }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < LibrarySchema.VERSION) {
            // v1 是首版；后续升级必须在 migration_state 中落审计记录，不能直接 DROP 用户数据。
            throw IllegalStateException("未实现的数据库升级: " + oldVersion + " -> " + newVersion)
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("拒绝降级数据库: " + oldVersion + " -> " + newVersion)
    }

    fun repository(): SqliteLibraryRepository = SqliteLibraryRepository(writableDatabase)

    fun deleteDatabase(context: Context) {
        context.applicationContext.deleteDatabase(LibrarySchema.DATABASE_NAME)
    }
}
