package app.particlebook.android.data

import android.content.Context
import android.util.Log
import app.particlebook.android.reader.AnnotationStore
import app.particlebook.android.reader.ReaderAnnotation

/**
 * P1-b 数据层启动入口：SQLite 单例 + 标注存储的进程级装配。
 *
 * - [prewarm] 在首帧后由 Activity 调一次，后台线程打开数据库；不阻塞冷启动。
 * - [lazyAnnotationStore] 返回给 Compose 的稳定对象；真正读写时才解析单例。
 * - 读写必须由调用方放到 IO dispatcher（AnnotationStore 契约为同步）。
 *
 * 这样即使 prewarm 还没跑完，第一次标注操作也会走 [ensure] 完成初始化；
 * 正常路径下用户在打开阅读器/标注时 prewarm 早已结束，不会在主线程开库。
 */
object LibraryRuntime {
    private const val TAG = "PBLibraryRuntime"

    private val lock = Any()

    @Volatile
    private var annotationStore: AnnotationStore? = null

    @Volatile
    private var prewarmStarted = false

    @Volatile
    private var catalogReady = false

    /** 首帧后调用：后台打开 SQLite，失败只记日志，不阻塞 UI。 */
    fun prewarm(context: Context) {
        if (prewarmStarted) return
        synchronized(lock) {
            if (prewarmStarted) return
            prewarmStarted = true
        }
        val appContext = context.applicationContext
        Thread({
            runCatching { ensure(appContext) }
                .onFailure { Log.w(TAG, "SQLite prewarm failed; will retry on first use", it) }
        }, "pb-sqlite-init").apply { isDaemon = true }.start()
    }

    /**
     * 书架同步前的数据层准备：开库 + 执行一次旧数据迁移（保留 books.json 原文件）。
     * 迁移失败只记日志，随后仍允许 CatalogSync 把当前书架写入 SQLite；
     * 失败结果由 P1 验收/诊断读取日志，不阻断阅读。
     */
    fun ensureCatalogReady(context: Context) {
        if (catalogReady) return
        synchronized(lock) {
            if (catalogReady) return
            val appContext = context.applicationContext
            ensure(appContext)
            // A014-3：只有确实存在旧 books.json 时才备份，避免每次冷启都复制一个空库。
            val legacyBooks = java.io.File(appContext.filesDir, "books.json")
            if (legacyBooks.isFile) {
                runCatching { LibraryDatabaseProvider.backupBeforeMigration(appContext) }
                    .onSuccess { backup ->
                        if (backup != null) Log.i(TAG, "pre-migration db backup: " + backup.absolutePath)
                    }
                    .onFailure { Log.w(TAG, "pre-migration db backup failed; migration will still run", it) }
            }
            runCatching { LibraryDatabaseProvider.migrateLegacy(appContext, preserveSource = true) }
                .onSuccess { Log.i(TAG, "legacy migration: " + it.javaClass.simpleName) }
                .onFailure { Log.w(TAG, "legacy migration failed; catalog sync will continue", it) }
            catalogReady = true
        }
    }

    /** 打开/复用 SQLite 并返回标注存储；可由任意线程调用（内部串行）。 */
    fun ensure(context: Context): AnnotationStore = annotationStore ?: synchronized(lock) {
        annotationStore ?: SqliteAnnotationStore(
            LibraryDatabaseProvider.initialize(context.applicationContext),
        ).also { annotationStore = it }
    }

    /** 给 Compose 的稳定句柄；方法调用时才解析单例。 */
    fun lazyAnnotationStore(context: Context): AnnotationStore =
        LazyAnnotationStore(context.applicationContext)

    /** 已初始化时返回单例，未初始化返回 null；供只读诊断使用。 */
    fun annotationStoreOrNull(): AnnotationStore? = annotationStore

    fun repository(context: Context): LibraryRepository =
        LibraryDatabaseProvider.initialize(context.applicationContext)

    /**
     * 跨书全文索引的写入端口；与标注/书架共享同一个 SQLite 单例。
     * 方法同步，调用方负责放到 IO dispatcher（触发点见 reader/search/EpubSearchIndexing）。
     */
    fun searchIndexStore(context: Context): SearchIndexStore =
        LibraryDatabaseProvider.searchIndexStore(context.applicationContext)

    fun close() {
        synchronized(lock) {
            annotationStore = null
            prewarmStarted = false
            catalogReady = false
        }
        LibraryDatabaseProvider.close()
    }
}

/** 轻量代理：把「第一次真正标注」之前的开库成本从组合期挪到调用期。 */
private class LazyAnnotationStore(private val context: Context) : AnnotationStore {
    private val delegate: AnnotationStore get() = LibraryRuntime.ensure(context)

    override fun list(bookId: Long): List<ReaderAnnotation> = delegate.list(bookId)

    override fun add(annotation: ReaderAnnotation): ReaderAnnotation = delegate.add(annotation)

    override fun update(annotation: ReaderAnnotation): ReaderAnnotation? = delegate.update(annotation)

    override fun remove(annotationId: Long): Boolean = delegate.remove(annotationId)
}
