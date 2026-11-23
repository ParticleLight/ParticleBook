package app.particlebook.android.reader.search

import android.content.Context
import android.util.Log
import app.particlebook.android.data.LibraryRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 索引触发点：打开书（或导入完成后）调用一次 [request]。
 *
 * 设计取舍：
 * - 进程级 SupervisorJob + Dispatchers.IO：不占主线程，也不随某个阅读界面退出而被取消；
 *   索引是「书的属性」，不属于某次 UI 会话；
 * - 同一本书的并发请求合并（书架快速点开两次只跑一遍）；是否真的重建由
 *   [EpubSearchIndexer] 的 checkpoint 决定，重复调用是廉价的；
 * - 失败只写日志，绝不向上抛：索引是可重建的派生数据，不能影响阅读。
 *
 * 调用方（UI 层）只需一行：
 * ```
 * EpubSearchIndexing.request(context, book.id, File(book.filePath))
 * ```
 * 放在书架同步完成后或阅读器打开时的启动副作用里即可。
 */
object EpubSearchIndexing {
    private const val TAG = "PBIndexer"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** bookId -> 运行中标记；只用于去重，不放任何索引状态。 */
    private val inFlight = ConcurrentHashMap<Long, Boolean>()

    /**
     * 串行闸门：一次只跑一本。
     *
     * 索引写入是「删旧行 + 写正文 + 写 checkpoint」的整本事务，多本并发时每个事务都要
     * 抢 SQLite 的写锁；批量导入/书架连点会同时排进几十本，靠 WAL + busy_timeout 兜底
     * 只会把失败推迟。排队比并发更便宜：索引是后台派生数据，晚几秒不可感知。
     */
    private val gate = Semaphore(permits = 1)

    fun request(context: Context, bookId: Long, file: File) {
        if (bookId <= 0L) {
            Log.d(TAG, "skip index request for invalid book id " + bookId)
            return
        }
        if (inFlight.putIfAbsent(bookId, true) != null) {
            Log.d(TAG, "index already running for book " + bookId)
            return
        }
        val appContext = context.applicationContext
        scope.launch {
            gate.withPermit {
                try {
                    val indexer = EpubSearchIndexer(LibraryRuntime.searchIndexStore(appContext))
                    Log.i(TAG, "epub index " + bookId + ": " + describe(indexer.index(bookId, file)))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Log.w(TAG, "epub index failed for book " + bookId + "; reading is unaffected", error)
                } finally {
                    inFlight.remove(bookId)
                }
            }
        }
    }

    private fun describe(result: EpubIndexResult): String = when (result) {
        is EpubIndexResult.Indexed -> "indexed docs=" + result.documentCount +
            " spine=" + result.spineEntryCount +
            " empty=" + result.skippedEmptyChapters +
            " broken=" + result.skippedBrokenChapters +
            " dup=" + result.duplicateSpineEntries +
            " hash=" + result.textHash.take(12)
        is EpubIndexResult.Skipped -> "skipped reason=" + result.reason +
            " docs=" + result.documentCount +
            " hash=" + result.textHash.take(12)
    }
}
