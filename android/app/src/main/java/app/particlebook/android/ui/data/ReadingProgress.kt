package app.particlebook.android.ui.data

import android.content.Context
import android.util.Log
import app.particlebook.android.data.LegacyMigrationRunner
import app.particlebook.android.data.LibraryRepository
import app.particlebook.android.data.LibraryRuntime
import app.particlebook.android.data.ProgressCatalog
import app.particlebook.android.data.ProgressSnapshot
import app.particlebook.android.reader.ReaderLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Process-wide progress cache. Only initialization and the writer touch disk. */
@Suppress("UNUSED_PARAMETER") // Keep the existing Context-based reader and shelf call sites.
object ReadingProgress {
    private const val TAG = "PBReadingProgress"
    private val catalog = ProgressCatalog(CoroutineScope(SupervisorJob() + Dispatchers.IO), Dispatchers.IO) {
        Log.w(TAG, "SQLite progress write failed; will retry on flush", it)
    }

    /** Snapshot keys are shelf ids, not SQLite ids. */
    val snapshots: StateFlow<Map<Long, ProgressSnapshot>> = catalog.snapshots

    /** Read the latest bookshelf on IO after serializing against initialization and removal. */
    suspend fun initialize(context: Context, latestBooks: () -> List<Book>): Boolean {
        val appContext = context.applicationContext
        return try {
            catalog.initialize(
                repository = { openCatalog(appContext) },
                latestRows = { LibraryCatalogBridge.bookRows(latestBooks()) },
                readPreferences = {
                    appContext.getSharedPreferences(LegacyMigrationRunner.PREFS_NAME, Context.MODE_PRIVATE).all
                },
            )
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "SQLite progress initialization failed; retry on next initialize", error)
            false
        }
    }

    fun snapshot(bookId: Long): ProgressSnapshot? = snapshots.value[bookId]

    fun percentLabel(context: Context, bookId: Long): String? = snapshot(bookId)?.percentLabel()

    fun hasProgress(context: Context, bookId: Long): Boolean = snapshot(bookId)?.hasProgress() == true

    fun locator(context: Context, bookId: Long): ReaderLocator? = snapshot(bookId)?.locator()

    fun savedPage(context: Context, bookId: Long): Int? = snapshot(bookId)?.savedPage()

    fun lastReadAt(context: Context, bookId: Long): Long = snapshot(bookId)?.updatedAt ?: 0L

    fun fraction(context: Context, bookId: Long): Float? = snapshot(bookId)?.fraction()

    fun a11yStatus(context: Context, bookId: Long): String = percentLabel(context, bookId) ?: "未读"

    /**
     * 页码观察者：每次进度落库前回调一次 (bookId, page)，page 为 0 基。
     *
     * 阅读统计需要「翻了几页」，而页码只在这里汇总（EPUB 章节内页、PDF 文档页都走
     * [saveEpubProgress] / [savePdfProgress]）。与其在三个阅读器里各加一个回调，
     * 不如在这一条收口；记录器自己做去重（同一页重复保存不算翻页）。
     * 只允许一个订阅者（AppRoot 的会话记录器），置 null 即注销。
     */
    @Volatile
    var pageObserver: ((bookId: Long, page: Int) -> Unit)? = null

    fun saveEpubProgress(context: Context, bookId: Long, locator: ReaderLocator, page: Int, pages: Int) {
        if (locator is ReaderLocator.Epub) {
            pageObserver?.invoke(bookId, page)
            save(bookId, locator, page, pages)
        }
    }

    /**
     * 文本阅读（TXT / Markdown）：段落下标当页码、段落总数当页数。
     *
     * 这样 [ProgressSnapshot.percentLabel] / fraction / 书架 Hero「继续阅读」全部沿用既有页码口径，
     * 不需要为文本新开一条百分比通道；恢复时 [savedPage] 直接就是段落下标。
     *
     * 不调 [pageObserver]：段落不是「页」，段落级变化会让阅读统计把一次滚动记成几十次翻页。
     * 文本的阅读时长由会话记录器按打开/关闭时间统计，与翻页数无关。
     *
     * 单段文档照样落库（`totalPages = 1`）：段落数不等于屏数，一篇没有空行的 1.9 万字 txt 只有
     * 一段、却要滚几十屏。以前这里 `paragraphs <= 1` 早退，结果是「读了半本，重开回到顶部、
     * 书架也不记这本」；显示层按 `hasProgress()`（totalPages > 1）自己决定要不要露百分比即可。
     */
    fun saveTextProgress(
        context: Context,
        bookId: Long,
        locator: ReaderLocator.Text,
        paragraph: Int,
        paragraphs: Int,
    ) {
        if (paragraphs <= 0) return
        val index = paragraph.coerceIn(0, paragraphs - 1)
        save(bookId, locator, index, paragraphs)
    }

    fun savePdfProgress(context: Context, bookId: Long, page: Int, pages: Int) {
        if (pages > 0) {
            pageObserver?.invoke(bookId, page)
            save(bookId, ReaderLocator.Pdf(page.coerceIn(0, pages - 1)), page, pages)
        }
    }

    private fun save(bookId: Long, locator: ReaderLocator, page: Int, pages: Int) {
        catalog.enqueue(bookId, locator, page, pages)
    }

    /** Called after the shelf commits. Clear only rows with no remaining shelf alias. */
    suspend fun clear(context: Context, removed: List<Book>, latestBooks: () -> List<Book>): Int = catalog.clear(
        removed = LibraryCatalogBridge.bookRows(removed),
        repository = { openCatalog(context.applicationContext) },
        latestRows = { LibraryCatalogBridge.bookRows(latestBooks()) },
    )

    /**
     * 「清除阅读记录」：清空所有书的阅读进度与阅读统计（设置页确认弹窗的实现）。
     *
     * 和 [clear] 的区别：[clear] 是书架提交后删掉**已移除那几本**的残留进度（按 stable_key 解析别名，
     * 只影响被移除的书）；这里是用户主动的**全清**，与书架无关，也不会把书从书架移除。
     *
     * 顺序：
     * 1. 先清 `reading_sessions`——统计是追加型旁路数据，没有内存队列，一条 DELETE 自带事务；
     * 2. 再清 `progress`——经 [ProgressCatalog.clearAll] 排在进度写者队列之后，
     *    `deleteAllProgress` 与「回填标记保持为真」在同一个 SQLite 事务里完成
     *    （[app.particlebook.android.data.SqliteProgressStore.clearAll]）。
     *
     * **回填标记为什么必须保持为真**：`progress.prefs_backfilled.v1` 是「旧 pb_reading 里的页码
     * 已经合并过一次」的墓碑（Dao.kt 的 `backfillComplete()`，存在 settings 表里，跟着库文件活过重启）。
     * 冷启时 `ProgressBootstrap.load` 只有标记为假才读旧 prefs 并回填（ProgressBackfill.kt），
     * `LegacyMigrationRunner` 也只在标记为假时才把 prefs 页码放进迁移计划。标记一旦被清掉，
     * 用户刚清空的进度会在下一次启动被旧 prefs 原样灌回来。所以这里只删行、不碰标记；
     * 标记若还没写过（首次清除早于首次回填），同事务补写成真——语义是「已合并，合并结果为空」。
     *
     * 失败语义：任一步抛错都返回 false。progress 失败时内存快照保持清除前的样子
     * （[app.particlebook.android.data.ProgressCoordinator.clearAll] 负责恢复），统计可能已清空——
     * 再点一次即可，操作幂等。
     *
     * 不清 [app.particlebook.android.data.ReadingSessionRecorder] 的在途片段：清空之后若还从阅读器退出来，
     * 那次 flush 落的是「清空之后」发生的真实阅读，不是被清掉的历史。
     */
    suspend fun clearAll(context: Context): Boolean {
        val appContext = context.applicationContext
        return try {
            withContext(NonCancellable + Dispatchers.IO) {
                val repository = openCatalog(appContext)
                repository.transaction { it.deleteAllReadingSessions() }
                catalog.clearAll { repository }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "clear all reading records failed", error)
            false
        }
    }

    /** True means all accepted writes before this call reached SQLite. */
    suspend fun flush(): Boolean = catalog.flush()

    private fun openCatalog(context: Context): LibraryRepository {
        LibraryRuntime.ensureCatalogReady(context)
        return LibraryRuntime.repository(context)
    }
}

/** Legacy fixture keys; runtime never accesses them outside migration. */
internal fun progressKeys(bookId: Long): List<String> =
    listOf("page_" + bookId, "pages_" + bookId, "last_read_" + bookId)
