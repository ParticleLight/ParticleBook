package app.particlebook.android.ui

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.DownloadLocation
import app.particlebook.android.ui.data.LibraryStore
import app.particlebook.android.zlib.ZBookDetail
import app.particlebook.android.zlib.ZlibClient
import app.particlebook.android.zlib.ZlibException
import app.particlebook.android.zlib.ZlibPartMetaStore
import app.particlebook.android.zlib.ZlibStallDetector
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 下载任务状态机（spec §4.4）：Preparing → Downloading → Completed / Failed / Cancelled。 */
enum class ZlibDownloadStatus { PREPARING, DOWNLOADING, COMPLETED, FAILED, CANCELLED }

/** U9：这两类失败重试必然再失败，卡片主操作给「去登录」而不是「重试」。 */
internal fun zlibFailureNeedsLogin(errorKind: String?): Boolean =
    errorKind == "LOGIN_REQUIRED" || errorKind == "DAILY_LIMIT"

/** U4：下载成功但导入书架失败（文件已完整落盘，可重新导入，不用重下）。 */
internal const val ZLIB_IMPORT_FAILED_KIND = "IMPORT_FAILED"

/** 看门狗判停滞时的收尾文案（errorKind = NETWORK，与桌面 network_stalled 对齐）。 */
internal const val ZLIB_STALL_MESSAGE = "下载停滞超过 45 秒，请检查网络后重试"

/**
 * 一条 Z-Library 下载任务/记录。Completed/Failed 的终态会额外写进 filesDir/zlib_downloads.json，
 * 供「下载记录」页跨进程展示；task 本身只在进程内生命周期里用于进度卡片。
 */
data class ZlibDownloadTask(
    val id: String,
    val bookId: String,
    val title: String,
    val author: String?,
    val format: String?,
    val downloadPath: String,
    val fileName: String,
    val destPath: String,
    /** true = 目标为 SAF 目录（destPath 是 content:// URI）；false = 普通 File 路径。 */
    val saf: Boolean = false,
    val received: Long = 0L,
    val total: Long = -1L,
    val status: ZlibDownloadStatus = ZlibDownloadStatus.PREPARING,
    val errorKind: String? = null,
    val errorMessage: String? = null,
    val finishedAt: Long = 0L,
    /** U4：文件已下完、正在复制/登记进书架（卡片文案「下载完成 · 正在加入书架」）。 */
    val importing: Boolean = false,
    /** U3：用户已点取消、等待在途 IO 收尾（卡片文案「正在取消…」）。 */
    val cancelling: Boolean = false,
) {
    /** 0f..1f；服务器没给 Content-Length 时为 null（UI 显示不确定进度）。 */
    val progress: Float?
        get() = if (total > 0L) (received.toFloat() / total).coerceIn(0f, 1f) else null

    val isActive: Boolean
        get() = status == ZlibDownloadStatus.PREPARING || status == ZlibDownloadStatus.DOWNLOADING
}

/** 取消来源：用户主动取消（U3）与看门狗判停滞（D4）的收尾语义不同。 */
internal enum class ZlibCancelReason { USER, STALL }

/**
 * 一次下载运行的控制块（U3/D4）：不在 UI 状态里，随任务重试换代。
 * - cancelFlag 直接交给 ZlibClient.download，置位后在下一个 8KB/请求检查点停下并**保留 .part**；
 * - cancelReason 区分「用户取消」与「看门狗停滞」：后者以 network_error 收尾；
 * - 世代号由 ZlibDownloadRunRegistry 分配，迟到的旧协程/旧看门狗据此丢弃，避免误杀重试后的新任务。
 */
internal class ZlibDownloadRun(val taskId: String, val generation: Long) {
    val cancelFlag = AtomicBoolean(false)

    @Volatile
    var cancelReason: ZlibCancelReason? = null
        private set

    /** 第一次取消生效（后续取消不覆盖原因，避免用户取消被看门狗改写）。 */
    fun cancel(reason: ZlibCancelReason): Boolean {
        val first = synchronized(this) {
            if (cancelReason == null) {
                cancelReason = reason
                true
            } else {
                false
            }
        }
        cancelFlag.set(true)
        return first
    }
}

/** 每个 taskId 当前世代的运行控制块（单写者：start/retry/reimport 换代）。 */
internal class ZlibDownloadRunRegistry {
    private val lock = Any()
    private val runs = HashMap<String, ZlibDownloadRun>()
    private var generation = 0L

    fun begin(taskId: String): ZlibDownloadRun = synchronized(lock) {
        generation += 1L
        ZlibDownloadRun(taskId, generation).also { runs[taskId] = it }
    }

    fun current(taskId: String): ZlibDownloadRun? = synchronized(lock) { runs[taskId] }

    fun isCurrent(run: ZlibDownloadRun): Boolean =
        synchronized(lock) { runs[run.taskId] === run }

    /** 给当前世代的运行置取消位；没有运行任务时返回 false。 */
    fun cancel(taskId: String, reason: ZlibCancelReason): Boolean {
        val run = synchronized(lock) { runs[taskId] } ?: return false
        return run.cancel(reason)
    }

    fun release(run: ZlibDownloadRun) {
        synchronized(lock) {
            if (runs[run.taskId] === run) runs.remove(run.taskId)
        }
    }
}

/**
 * D4：零增长看门狗核心（纯时间逻辑，可注入时钟）。
 *
 * 每 ZlibStallDetector.TICK_MS 采样一次已收字节数；连续 45s 零增长 → 触发一次 onStall
 * （调用方置 cancelFlag 并以 network_error 收尾）。stillCurrent 为世代/生命周期校验：
 * 任务已换代（重试/重新导入）或已不在进行中时直接停表，绝不误杀新任务。
 */
internal class ZlibDownloadStallWatchdog(
    stallMs: Long = ZlibStallDetector.STALL_TIMEOUT_MS,
    clockMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val detector = ZlibStallDetector(stallMs, clockMs)

    private var fired = false
    private var stopped = false

    /** 已判定停滞并取消（只触发一次）。 */
    val isFired: Boolean get() = fired

    /** 已停表：任务换代/结束，后续 tick 不再触发。 */
    val isStopped: Boolean get() = stopped

    /** @return true = 本次判定停滞（调用方负责取消）。 */
    fun tick(bytes: Long, stillCurrent: Boolean, onStall: () -> Unit): Boolean {
        if (fired || stopped) return false
        if (!stillCurrent) {
            stopped = true
            return false
        }
        detector.onBytes(bytes)
        if (detector.stalled()) {
            fired = true
            onStall()
            return true
        }
        return false
    }
}

/**
 * U13：进度节流（严格 CAS）。进度回调可能来自多条线程：
 * - 距上次通知 ≥150ms 才发；多个线程同时到期时只有一个 CAS 成功，其余丢弃；
 * - 收到 total 的最后一包无条件通知（完成态不能被节流吞掉）。
 */
internal class ZlibProgressThrottle(
    private val intervalMs: Long = 150L,
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val lastNotifyAt = AtomicLong(-1L)

    fun shouldNotify(received: Long, total: Long, nowMs: Long = clockMs()): Boolean {
        if (total > 0L && received >= total) {
            lastNotifyAt.set(nowMs)
            return true
        }
        val last = lastNotifyAt.get()
        // 同一采样时刻只允许一个线程通知（也是 CAS 的 ABA 防护：last == nowMs 时换个线程
        // 再 compareAndSet(last, nowMs) 也会成功，必须显式拒绝）。
        if (nowMs <= last) return false
        if (last >= 0L && nowMs - last < intervalMs) return false
        return lastNotifyAt.compareAndSet(last, nowMs)
    }
}

/**
 * U1/D4：zlib_downloads.json 的单写者队列。
 *
 * submit() 只登记「最新快照」；拿到 true 的那个调用者负责执行 drain()。排空循环每轮写盘前
 * 重新取 pending，所以并发的终态事件不会用旧快照覆盖新快照，也不会出现两个协程同时写同一个
 * 临时文件。写盘用唯一 tmp + 原子 rename（rename 失败才退回直写）。
 * read() 遇到损坏内容先留 .bad 再返回空表（与 LibraryStore.readIndex 的处理对齐）。
 */
internal class ZlibDownloadHistoryStore(
    private val file: File,
    private val encode: (List<ZlibDownloadTask>) -> String,
    private val decode: (String) -> List<ZlibDownloadTask>,
) {
    private val lock = Any()
    private var pending: List<ZlibDownloadTask>? = null
    private var draining = false

    fun read(): List<ZlibDownloadTask> {
        if (!file.isFile) return emptyList()
        return try {
            decode(file.readText())
        } catch (throwable: Throwable) {
            quarantine()
            emptyList()
        }
    }

    /** 登记最新快照；返回 true = 调用方必须成为单写者执行 drain。 */
    fun submit(records: List<ZlibDownloadTask>): Boolean = synchronized(lock) {
        pending = records
        if (draining) {
            false
        } else {
            draining = true
            true
        }
    }

    /** 单写者排空循环：写盘期间 submit 的新快照会合并到下一轮，旧快照不落盘。 */
    fun drain() {
        while (true) {
            val snapshot = synchronized(lock) {
                val next = pending
                pending = null
                if (next == null) draining = false
                next
            } ?: return
            try {
                writeAtomically(encode(snapshot))
            } catch (throwable: Throwable) {
                Log.e(TAG, "write download history failed path=" + file.absolutePath, throwable)
            }
        }
    }

    private fun writeAtomically(payload: String) {
        val parent = file.absoluteFile.parentFile ?: File(".")
        parent.mkdirs()
        val tmp = File(parent, file.name + "." + UUID.randomUUID() + HISTORY_TMP_SUFFIX)
        tmp.writeText(payload)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) {
                file.writeText(payload)
                tmp.delete()
            }
        }
    }

    /** 损坏历史：先留一份 .bad 再重建（旧 .bad 覆盖，避免无限堆文件）。 */
    private fun quarantine() {
        runCatching {
            val bad = File(file.absoluteFile.parentFile ?: File("."), file.name + ".bad")
            bad.delete()
            file.renameTo(bad)
        }
    }

    private companion object {
        const val TAG = "PBZlib"
        const val HISTORY_TMP_SUFFIX = ".tmp"
    }
}

/**
 * U4：下载完成后的落库/导入状态迁移（真正决定「导入成功才 COMPLETED + 历史」）。
 * 下载路径与 SAF 路径共用，保证「下载完成 · 正在加入书架」→「已加入书架」/「重新导入」一致。
 */
internal object ZlibDownloadCompletion {

    /** 文件已下完、正在复制/登记进书架：保持进行中（active），卡片显示「下载完成 · 正在加入书架」。 */
    fun importing(
        task: ZlibDownloadTask,
        size: Long,
        fileName: String? = null,
        destPath: String? = null,
    ): ZlibDownloadTask = task.copy(
        status = ZlibDownloadStatus.DOWNLOADING,
        received = size.coerceAtLeast(0L),
        total = size.takeIf { it > 0L } ?: task.total,
        fileName = fileName?.takeIf { it.isNotBlank() } ?: task.fileName,
        destPath = destPath?.takeIf { it.isNotBlank() } ?: task.destPath,
        importing = true,
        cancelling = false,
        errorKind = null,
        errorMessage = null,
    )

    /** 导入成功：此时才标 COMPLETED（调用方随后写历史并安排 2.5s 收起卡片）。 */
    fun imported(
        task: ZlibDownloadTask,
        size: Long,
        fileName: String? = null,
        destPath: String? = null,
    ): ZlibDownloadTask = task.copy(
        status = ZlibDownloadStatus.COMPLETED,
        received = size.coerceAtLeast(task.received),
        total = size.takeIf { it > 0L } ?: task.total,
        fileName = fileName?.takeIf { it.isNotBlank() } ?: task.fileName,
        destPath = destPath?.takeIf { it.isNotBlank() } ?: task.destPath,
        importing = false,
        cancelling = false,
        errorKind = null,
        errorMessage = null,
        finishedAt = System.currentTimeMillis(),
    )

    /** 导入失败：文件仍在，标 IMPORT_FAILED 供卡片/详情页「重新导入」。 */
    fun importFailed(task: ZlibDownloadTask, cause: Throwable?): ZlibDownloadTask = task.copy(
        status = ZlibDownloadStatus.FAILED,
        importing = false,
        cancelling = false,
        errorKind = ZLIB_IMPORT_FAILED_KIND,
        errorMessage = "下载完成，但加入书架失败，可点「重新导入」重试" +
            (cause?.message?.takeIf { it.isNotBlank() }?.let { "（" + it.take(40) + "）" } ?: ""),
        finishedAt = System.currentTimeMillis(),
    )
}

/** U3：.part 文件语义——取消保留（可续传），只有「删除」才清理。 */
internal object ZlibDownloadFiles {
    fun partFileOf(target: File): File =
        File(target.absoluteFile.parentFile ?: File("."), target.name + ".part")

    fun hasPartial(target: File): Boolean = partFileOf(target).isFile

    /** 删半成品（.part + .part.meta）；取消路径**不**调用。 */
    fun deletePartial(target: File) {
        val part = partFileOf(target)
        runCatching { part.delete() }
        runCatching { ZlibPartMetaStore.delete(part) }
    }
}

/**
 * Z-Library 下载管理器（进程级单例，P1b）：
 * - tasks：当前/最近的任务（全局下载卡片订阅它；完成 2.5s 后自动移除）；
 * - history：持久化到 filesDir/zlib_downloads.json 的终态记录（下载记录页/详情页"已下载"判断）。
 *
 * 网络调用全部在 Dispatchers.IO；进度回调节流 ≥150ms 再写 StateFlow，避免主线程抖动。
 * P1d 加固：每任务 cancelFlag（取消保留 .part）、45s 零增长看门狗、历史单写者 + 唯一 tmp、
 * 导入成功才 COMPLETED（失败可重新导入）。
 */
object ZlibDownloadManager {

    private const val TAG = "PBZlib"
    private const val PERF_TAG = "PBPERF"
    private const val HISTORY_NAME = "zlib_downloads.json"
    private const val HISTORY_VERSION = 1
    private const val COMPLETED_VISIBLE_MS = 2500L
    private const val DEFAULT_DIR_NAME = "Z-Library"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _tasks = MutableStateFlow<List<ZlibDownloadTask>>(emptyList())
    private val _history = MutableStateFlow<List<ZlibDownloadTask>>(emptyList())
    private val runs = ZlibDownloadRunRegistry()
    private var appContext: Context? = null
    private var historyStore: ZlibDownloadHistoryStore? = null

    /** 当前 + 最近完成的任务（卡片展示用）。 */
    val tasks: StateFlow<List<ZlibDownloadTask>> = _tasks.asStateFlow()

    /** 持久化下载记录（最新在前）。 */
    val history: StateFlow<List<ZlibDownloadTask>> = _history.asStateFlow()

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val store = ZlibDownloadHistoryStore(
            file = File(app.filesDir, HISTORY_NAME),
            encode = ::encodeHistory,
            decode = ::decodeHistory,
        )
        historyStore = store
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { store.read() }
            // 加载期间若已有新终态写入（极端冷启并发），保留内存里的新记录。
            _history.update { current ->
                if (current.isEmpty()) {
                    loaded
                } else {
                    loaded.filterNot { old -> current.any { it.bookId == old.bookId } } + current
                }
            }
        }
    }

    /** 默认下载目录（未选择 SAF 时使用）：getExternalFilesDir(DOWNLOADS)/Z-Library。 */
    fun downloadDir(context: Context): File = DownloadLocation.defaultDir(context)

    fun taskForBook(bookId: String): ZlibDownloadTask? =
        _tasks.value.firstOrNull { it.bookId == bookId }

    fun historyForBook(bookId: String): ZlibDownloadTask? =
        _history.value.firstOrNull { it.bookId == bookId }

    /** 详情页点「下载」：已在下载中则忽略；失败任务会被重置；已完成且文件仍存在则不重复下载。 */
    fun start(detail: ZBookDetail, store: LibraryStore) {
        val context = appContext ?: return
        val running = _tasks.value.firstOrNull {
            it.bookId == detail.bookId && it.isActive
        }
        if (running != null) return
        val done = historyForBook(detail.bookId)
        if (done != null) {
            if (done.status == ZlibDownloadStatus.COMPLETED &&
                DownloadLocation.exists(context, done.destPath)
            ) {
                return
            }
            // U4：上次下载完成但导入失败 → 文件还在，只需重新导入（不再消耗下载额度）。
            if (done.status == ZlibDownloadStatus.FAILED &&
                done.errorKind == ZLIB_IMPORT_FAILED_KIND &&
                DownloadLocation.exists(context, done.destPath)
            ) {
                reimportTask(
                    done.copy(
                        title = detail.title.ifBlank { done.title },
                        author = done.author ?: detail.authors.firstOrNull(),
                    ),
                    store,
                )
                return
            }
        }
        // P1c：自定义 SAF 目录 → 下载完成后 createDocument；默认仍是 File 路径（行为不变）。
        val saf = DownloadLocation.isCustom(context)
        val fileName: String
        val destPath: String
        if (saf) {
            fileName = suggestedFileName(detail)
            // SAF 目标 uri 在真正下载时创建（主线程不做 IO），完成后再回填。
            destPath = ""
        } else {
            val dir = downloadDir(context)
            fileName = uniqueFileName(dir, detail)
            destPath = File(dir, fileName).absolutePath
        }
        val task = ZlibDownloadTask(
            id = taskId(detail.bookId),
            bookId = detail.bookId,
            title = detail.title,
            author = detail.authors.firstOrNull(),
            format = detail.format,
            downloadPath = detail.downloadPath,
            fileName = fileName,
            destPath = destPath,
            saf = saf,
            status = ZlibDownloadStatus.PREPARING,
        )
        _tasks.update { list -> list.filterNot { it.id == task.id } + task }
        launchDownload(task, store)
    }

    /**
     * U3：取消一次进行中的下载。
     * - 立即置 cancelFlag → ZlibClient 在下一个检查点停下（最多一个 8KB/请求周期）；
     * - **保留 .part**（可续传），不落失败历史；
     * - 卡片先显示「正在取消…」，等协程收尾后才翻成 CANCELLED（避免旧协程还持有 .part 时
     *   用户点续传造成两个协程写同一文件）。
     */
    fun cancel(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (!task.isActive || task.cancelling || task.importing) return
        // 字节已下完（正在复制进 SAF 目录 / 即将进入导入阶段）时取消没有意义：
        // 此时 IO 已不可中断，再翻 CANCELLED 会让用户以为下载停了，实际会继续完成导入。
        if (task.total > 0L && task.received >= task.total) return
        if (!runs.cancel(id, ZlibCancelReason.USER)) return
        updateTask(id) { it.copy(cancelling = true) }
        Log.i(TAG, "zlib_download_cancel task=" + id)
    }

    /** 失败卡片/详情页的重试（含 CANCELLED 的「续传」）：复用原 downloadPath 与目标文件。 */
    fun retry(id: String, store: LibraryStore) {
        val current = _tasks.value.firstOrNull { it.id == id } ?: return
        if (current.isActive) return
        // 上一轮取消还在收尾（.part 仍被写）：等协程退出后再允许续传。
        if (runs.current(id) != null) return
        val task = current.copy(
            received = 0L,
            total = -1L,
            status = ZlibDownloadStatus.PREPARING,
            errorKind = null,
            errorMessage = null,
            importing = false,
            cancelling = false,
            finishedAt = 0L,
        )
        _tasks.update { list -> list.map { if (it.id == id) task else it } }
        launchDownload(task, store)
    }

    /**
     * U4：下载完成但导入书架失败（IMPORT_FAILED）时的「重新导入」：
     * 文件已完整落盘，不再走网络，只重做复制/登记书架。
     */
    fun reimport(id: String, store: LibraryStore) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (task.isActive) return
        if (task.status != ZlibDownloadStatus.FAILED) return
        if (task.errorKind != ZLIB_IMPORT_FAILED_KIND) return
        reimportTask(task, store)
    }

    /** 关闭卡片（不影响下载记录）；U12：进行中的任务不允许「消失但继续下载」。 */
    fun dismiss(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (task.isActive) return
        _tasks.update { list -> list.filterNot { it.id == id } }
    }

    /**
     * U3：已取消卡片的「删除」——移除卡片并清掉半成品（普通路径的 .part/.part.meta，
     * SAF 路径的 cacheDir 临时文件与可能已创建的半截文档）。取消本身不删文件，只有这里删。
     */
    fun discard(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (task.isActive) return
        _tasks.update { list -> list.filterNot { it.id == id } }
        val context = appContext ?: return
        scope.launch(Dispatchers.IO) {
            if (task.saf || task.destPath.startsWith("content://")) {
                val temp = safTempFile(context, task.id)
                runCatching { temp.delete() }
                runCatching { ZlibDownloadFiles.deletePartial(temp) }
                if (task.destPath.startsWith("content://")) {
                    runCatching {
                        DocumentsContract.deleteDocument(
                            context.contentResolver,
                            Uri.parse(task.destPath),
                        )
                    }.onFailure { Log.w(TAG, "delete saf partial document failed", it) }
                }
            } else if (task.destPath.isNotBlank()) {
                ZlibDownloadFiles.deletePartial(File(task.destPath))
            }
        }
    }

    /**
     * T4（D8）：删除一条下载记录。
     * - deleteFile = true 时同时删下载文件：普通路径删目标文件 + 半成品；content:// 走
     *   DocumentsContract.deleteDocument（失败只记日志，不影响记录删除）；
     * - 写盘走与终态记录相同的单写者队列（scheduleHistoryWrite），不另起一套 JSON 写；
     * - 返回 true 表示确实删掉了一条记录；文件是否真正删掉只能尽力而为。
     */
    fun removeHistory(bookId: String, deleteFile: Boolean): Boolean {
        val removed = _history.value.firstOrNull { it.bookId == bookId } ?: return false
        _history.update { list -> list.filterNot { it.bookId == bookId } }
        scheduleHistoryWrite()
        // 同一本书的终态卡片一并移除，避免记录页删了而书架还挂着卡片。
        _tasks.update { list -> list.filterNot { it.bookId == bookId && !it.isActive } }
        if (deleteFile) {
            val context = appContext
            scope.launch(Dispatchers.IO) { deleteDownloadedFile(context, removed) }
        }
        return true
    }

    // ------------------------------------------------------------ 内部实现

    private fun taskId(bookId: String): String = "zlib:" + bookId

    private fun launchDownload(task: ZlibDownloadTask, store: LibraryStore) {
        val context = appContext ?: return
        val run = runs.begin(task.id)
        val throttle = ZlibProgressThrottle()
        val watchdog = ZlibDownloadStallWatchdog()
        val watchdogJob = scope.launch(Dispatchers.Default) {
            runStallWatchdog(task.id, run, watchdog)
        }
        scope.launch {
            val began = SystemClock.elapsedRealtime()
            val client = ZlibClient.get(context)
            val onProgress: (Long, Long) -> Unit = { received, total ->
                if (throttle.shouldNotify(received, total) && runs.isCurrent(run)) {
                    updateTask(task.id) {
                        it.copy(
                            received = received,
                            total = total,
                            status = ZlibDownloadStatus.DOWNLOADING,
                        )
                    }
                }
            }
            try {
                if (!runs.isCurrent(run)) return@launch
                _tasks.update { list ->
                    list.map {
                        if (it.id == task.id) it.copy(status = ZlibDownloadStatus.DOWNLOADING) else it
                    }
                }
                if (task.saf || task.destPath.startsWith("content://")) {
                    downloadToSaf(context, client, task, store, onProgress, began, run)
                } else {
                    downloadToFile(client, task, store, onProgress, began, run)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                handleDownloadFailure(run, task, throwable, began)
            } finally {
                watchdogJob.cancel()
                runs.release(run)
            }
        }
    }

    /** D4：detached 协程每 2s 采样一次「已收字节」；任务换代/收尾即停表（防误杀重试任务）。 */
    private suspend fun runStallWatchdog(
        taskId: String,
        run: ZlibDownloadRun,
        watchdog: ZlibDownloadStallWatchdog,
    ) {
        while (true) {
            delay(ZlibStallDetector.TICK_MS)
            if (!runs.isCurrent(run)) return
            val task = _tasks.value.firstOrNull { it.id == taskId } ?: return
            if (!task.isActive || task.cancelling || task.importing) return
            val fired = watchdog.tick(bytes = task.received, stillCurrent = true) {
                if (runs.cancel(taskId, ZlibCancelReason.STALL)) {
                    Log.i(
                        TAG,
                        "zlib_download_stall task=" + taskId + " received=" + task.received,
                    )
                }
            }
            if (fired) return
        }
    }

    /** P1b 原有路径：直接下载到外部应用目录 File。 */
    private suspend fun downloadToFile(
        client: ZlibClient,
        task: ZlibDownloadTask,
        store: LibraryStore,
        onProgress: (Long, Long) -> Unit,
        began: Long,
        run: ZlibDownloadRun,
    ) {
        val file = withContext(Dispatchers.IO) {
            client.download(task.downloadPath, File(task.destPath), onProgress, run.cancelFlag)
        }
        if (!runs.isCurrent(run)) return
        val size = withContext(Dispatchers.IO) { file.length() }
        val importing = updateTask(task.id) {
            ZlibDownloadCompletion.importing(it, size, file.name, file.absolutePath)
        } ?: return
        // U4：导入成功才算下载完成；失败留在 FAILED(IMPORT_FAILED)，可「重新导入」。
        val adopt = store.adoptLocalFile(
            file = file,
            title = task.title,
            author = task.author,
            extraTag = bookTag(task.bookId),
        )
        if (!runs.isCurrent(run)) return
        if (adopt.isSuccess) {
            completeImport(task.id, importing, size, file.name, file.absolutePath)
            Log.i(
                PERF_TAG,
                "zlib_download|ms=" + (SystemClock.elapsedRealtime() - began) +
                    "|extra=book:" + task.bookId + ",bytes:" + size + ",status:ok,target:file",
            )
        } else {
            failImport(task.id, importing, size, adopt.exceptionOrNull())
        }
    }

    /**
     * P1c：SAF 目录。先下到 cacheDir 临时文件（网络层契约只接受 File），
     * 再用 DocumentsContract.createDocument + ContentResolver.openOutputStream 写入用户选的文件夹；
     * 成功后把 SAF 文档复制回本地书架（LibraryStore.adoptDocument）。
     */
    private suspend fun downloadToSaf(
        context: Context,
        client: ZlibClient,
        task: ZlibDownloadTask,
        store: LibraryStore,
        onProgress: (Long, Long) -> Unit,
        began: Long,
        run: ZlibDownloadRun,
    ) {
        val temp = safTempFile(context, task.id)
        temp.parentFile?.mkdirs()
        try {
            val file = withContext(Dispatchers.IO) {
                client.download(task.downloadPath, temp, onProgress, run.cancelFlag)
            }
            if (!runs.isCurrent(run)) return
            val bytes = withContext(Dispatchers.IO) { file.length() }
            // 失败重试时若已创建过文档则复用（openOutputStream 截断重写），避免重复文件。
            val reused = task.destPath.takeIf { it.startsWith("content://") }
                ?.let { raw -> runCatching { Uri.parse(raw) }.getOrNull() }
            val target = reused ?: DownloadLocation.createDocument(
                context = context,
                displayName = task.fileName,
                mimeType = DownloadLocation.mimeFor(task.format),
            ) ?: error("无法在所选文件夹中创建文件")
            // 先把目标 uri 记进任务：复制失败后重试会复用同一文档（截断重写），不会在用户目录留下孤儿文件。
            if (task.destPath != target.toString()) {
                updateTask(task.id) { it.copy(destPath = target.toString()) }
            }
            withContext(Dispatchers.IO) {
                val output = DownloadLocation.openOutput(context, target)
                    ?: error("无法写入所选文件夹")
                output.use { sink ->
                    file.inputStream().use { input -> input.copyTo(sink) }
                }
            }
            if (!runs.isCurrent(run)) return
            val actualName = DownloadLocation.displayName(context, target) ?: task.fileName
            val importing = updateTask(task.id) {
                ZlibDownloadCompletion.importing(it, bytes, actualName, target.toString())
            } ?: return
            val adopt = store.adoptDocument(
                uri = target,
                displayName = actualName,
                title = task.title,
                author = task.author,
                extraTag = bookTag(task.bookId),
            )
            if (!runs.isCurrent(run)) return
            if (adopt.isSuccess) {
                completeImport(task.id, importing, bytes, actualName, target.toString())
                Log.i(
                    PERF_TAG,
                    "zlib_download|ms=" + (SystemClock.elapsedRealtime() - began) +
                        "|extra=book:" + task.bookId + ",bytes:" + bytes + ",status:ok,target:saf",
                )
            } else {
                failImport(task.id, importing, bytes, adopt.exceptionOrNull())
            }
        } finally {
            // 成功时 temp 是已落盘的完整文件，删掉；失败/取消时保留 temp.part 供续传（B2）。
            withContext(Dispatchers.IO) { runCatching { temp.delete() } }
        }
    }

    /** 下载失败/取消收尾：CANCELLED 区分用户取消（不写历史）与看门狗停滞（network_error + 历史）。 */
    private fun handleDownloadFailure(
        run: ZlibDownloadRun,
        task: ZlibDownloadTask,
        throwable: Throwable,
        began: Long,
    ) {
        // 旧世代（重试/新任务已接管）的迟到异常：丢弃，绝不覆盖新状态。
        if (!runs.isCurrent(run)) return
        val kindName = (throwable as? ZlibException)?.kind?.name
        if (kindName == ZlibException.Kind.CANCELLED.name) {
            when (run.cancelReason) {
                ZlibCancelReason.STALL -> {
                    val failed = updateTask(task.id) {
                        it.copy(
                            status = ZlibDownloadStatus.FAILED,
                            errorKind = ZlibException.Kind.NETWORK.name,
                            errorMessage = ZLIB_STALL_MESSAGE,
                            importing = false,
                            cancelling = false,
                            finishedAt = System.currentTimeMillis(),
                        )
                    } ?: task
                    addHistory(failed, failed.received)
                    Log.i(
                        PERF_TAG,
                        "zlib_download|ms=" + (SystemClock.elapsedRealtime() - began) +
                            "|extra=book:" + task.bookId + ",bytes:" + failed.received +
                            ",status:fail:NETWORK_STALL,target:" + if (task.saf) "saf" else "file",
                    )
                }

                else -> {
                    // U3：用户取消 —— 保留 .part，不落失败历史，卡片给「续传/删除」。
                    updateTask(task.id) {
                        it.copy(
                            status = ZlibDownloadStatus.CANCELLED,
                            importing = false,
                            cancelling = false,
                            errorKind = null,
                            errorMessage = null,
                            finishedAt = System.currentTimeMillis(),
                        )
                    }
                    Log.i(
                        PERF_TAG,
                        "zlib_download|ms=" + (SystemClock.elapsedRealtime() - began) +
                            "|extra=book:" + task.bookId + ",bytes:" + task.received +
                            ",status:cancelled,target:" + if (task.saf) "saf" else "file",
                    )
                }
            }
            return
        }
        val failed = updateTask(task.id) {
            it.copy(
                status = ZlibDownloadStatus.FAILED,
                errorKind = kindName ?: "UNKNOWN",
                errorMessage = ZlibUiError.message(throwable),
                importing = false,
                cancelling = false,
                finishedAt = System.currentTimeMillis(),
            )
        } ?: task
        val size = if (failed.saf) failed.received else File(failed.destPath).length()
        addHistory(failed, size)
        Log.i(
            PERF_TAG,
            "zlib_download|ms=" + (SystemClock.elapsedRealtime() - began) +
                "|extra=book:" + task.bookId + ",bytes:" + failed.received +
                ",status:fail:" + (failed.errorKind ?: "UNKNOWN") +
                ",target:" + if (failed.saf) "saf" else "file",
        )
    }

    private fun completeImport(
        id: String,
        importing: ZlibDownloadTask,
        size: Long,
        fileName: String?,
        destPath: String?,
    ) {
        updateTask(id) { ZlibDownloadCompletion.imported(it, size, fileName, destPath) } ?: return
        addHistory(importing, size)
        removeCompletedCardLater(id)
    }

    private fun failImport(
        id: String,
        importing: ZlibDownloadTask,
        size: Long,
        cause: Throwable?,
    ) {
        val failed = updateTask(id) { ZlibDownloadCompletion.importFailed(it, cause) } ?: return
        addHistory(failed, size)
        Log.e(TAG, "adopt downloaded file failed book=" + failed.bookId, cause)
    }

    /** U4：文件已下完但导入失败的「重新导入」实现（不重下，只重做复制/登记）。 */
    private fun reimportTask(task: ZlibDownloadTask, store: LibraryStore) {
        val context = appContext ?: return
        if (task.isActive) return
        if (runs.current(task.id) != null) return
        val run = runs.begin(task.id)
        _tasks.update { list ->
            list.filterNot { it.id == task.id } + task.copy(
                status = ZlibDownloadStatus.PREPARING,
                importing = false,
                cancelling = false,
                errorKind = null,
                errorMessage = null,
            )
        }
        scope.launch {
            try {
                val importing = updateTask(task.id) {
                    ZlibDownloadCompletion.importing(it, task.received, task.fileName, task.destPath)
                } ?: return@launch
                if (!runs.isCurrent(run)) return@launch
                val adopt = withContext(Dispatchers.IO) { adoptExisting(context, task, store) }
                if (!runs.isCurrent(run)) return@launch
                if (adopt.isSuccess) {
                    completeImport(task.id, importing, task.received, task.fileName, task.destPath)
                } else {
                    failImport(task.id, importing, task.received, adopt.exceptionOrNull())
                }
            } finally {
                runs.release(run)
            }
        }
    }

    /** 重新导入时按存储类型选择复制/登记方式。 */
    private suspend fun adoptExisting(
        context: Context,
        task: ZlibDownloadTask,
        store: LibraryStore,
    ): Result<Book> {
        val dest = task.destPath
        if (dest.startsWith("content://")) {
            return store.adoptDocument(
                uri = Uri.parse(dest),
                displayName = task.fileName,
                title = task.title,
                author = task.author,
                extraTag = bookTag(task.bookId),
            )
        }
        return store.adoptLocalFile(
            file = File(dest),
            title = task.title,
            author = task.author,
            extraTag = bookTag(task.bookId),
        )
    }

    private fun safTempFile(context: Context, id: String): File {
        val dir = File(context.cacheDir, "zlib-downloads").apply { mkdirs() }
        return File(dir, id.replace(':', '_') + ".part")
    }

    private fun bookTag(bookId: String): String = "zlib:" + bookId

    private fun removeCompletedCardLater(id: String) {
        scope.launch {
            delay(COMPLETED_VISIBLE_MS)
            _tasks.update { list ->
                list.filterNot { it.id == id && it.status == ZlibDownloadStatus.COMPLETED }
            }
        }
    }

    /** 更新单个任务并返回更新后的副本（任务被移除时返回 null）。 */
    private fun updateTask(id: String, transform: (ZlibDownloadTask) -> ZlibDownloadTask): ZlibDownloadTask? {
        var updated: ZlibDownloadTask? = null
        _tasks.update { list ->
            list.map { task ->
                if (task.id == id) transform(task).also { updated = it } else task
            }
        }
        return updated
    }

    private fun addHistory(task: ZlibDownloadTask, actualSize: Long) {
        val record = task.copy(
            received = actualSize.coerceAtLeast(task.received),
            total = actualSize.takeIf { it > 0L } ?: task.total,
            importing = false,
            cancelling = false,
        )
        _history.update { list -> listOf(record) + list.filterNot { it.bookId == record.bookId } }
        scheduleHistoryWrite()
    }

    /** 单写者队列：只有 submit 返回 true 的调用者启动排空循环（写盘前重取最新快照）。 */
    private fun scheduleHistoryWrite() {
        val store = historyStore ?: return
        if (store.submit(_history.value)) {
            scope.launch(Dispatchers.IO) { store.drain() }
        }
    }

    /** T4（D8）：删除下载文件（普通路径/SAF）。失败只记日志——记录删除不依赖文件删除结果。 */
    private fun deleteDownloadedFile(context: Context?, record: ZlibDownloadTask) {
        if (record.destPath.startsWith("content://")) {
            val resolver = context?.contentResolver ?: return
            runCatching {
                DocumentsContract.deleteDocument(resolver, Uri.parse(record.destPath))
            }.onFailure { Log.w(TAG, "delete saf download failed uri=" + record.destPath, it) }
            return
        }
        if (record.destPath.isBlank()) return
        val file = File(record.destPath)
        runCatching { file.delete() }
        runCatching { ZlibDownloadFiles.deletePartial(file) }
    }

    // ------------------------------------------------------------ 历史持久化

    private fun uniqueFileName(dir: File, detail: ZBookDetail): String {
        val extension = extensionOf(detail)
        val base = sanitizeFileName(detail.title).ifBlank { "book" }
        var candidate = base + "." + extension
        var index = 2
        while (File(dir, candidate).exists()) {
            candidate = base + " (" + index + ")." + extension
            index += 1
        }
        return candidate
    }

    /** SAF 目录的文件名（同一目录内同名时由 provider 自动改名，完成后回读真实名字）。 */
    private fun suggestedFileName(detail: ZBookDetail): String =
        sanitizeFileName(detail.title).ifBlank { "book" } + "." + extensionOf(detail)

    private fun extensionOf(detail: ZBookDetail): String =
        detail.format?.trim()?.lowercase()?.takeIf { it == "pdf" || it == "epub" } ?: "pdf"

    private fun sanitizeFileName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|\n\r\t]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(80)

    /** 解析历史（损坏由 ZlibDownloadHistoryStore.read 负责留 .bad）。 */
    private fun decodeHistory(payload: String): List<ZlibDownloadTask> {
        val root = JSONObject(payload)
        val array = root.optJSONArray("downloads") ?: JSONArray()
        val out = ArrayList<ZlibDownloadTask>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val bookId = item.optString("bookId")
            val destPath = item.optString("filePath")
            if (bookId.isBlank() || destPath.isBlank()) continue
            out += ZlibDownloadTask(
                id = taskId(bookId),
                bookId = bookId,
                title = item.optString("title").ifBlank { "未命名" },
                author = item.optString("author").takeUnless { it.isBlank() || it == "null" },
                format = item.optString("format").takeUnless { it.isBlank() || it == "null" },
                downloadPath = item.optString("downloadPath"),
                fileName = item.optString("fileName").ifBlank {
                    if (destPath.startsWith("content://")) "下载文件" else File(destPath).name
                },
                destPath = destPath,
                saf = item.optString("storage") == "saf" || destPath.startsWith("content://"),
                received = item.optLong("size", 0L),
                total = item.optLong("size", 0L),
                status = if (item.optString("status") == "FAILED") {
                    ZlibDownloadStatus.FAILED
                } else {
                    ZlibDownloadStatus.COMPLETED
                },
                errorKind = item.optString("errorKind").takeUnless { it.isBlank() },
                errorMessage = item.optString("errorMessage").takeUnless { it.isBlank() },
                finishedAt = item.optLong("finishedAt", 0L),
            )
        }
        return out
    }

    private fun encodeHistory(records: List<ZlibDownloadTask>): String {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("bookId", record.bookId)
                    .put("title", record.title)
                    .put("author", record.author ?: JSONObject.NULL)
                    .put("format", record.format ?: JSONObject.NULL)
                    .put("downloadPath", record.downloadPath)
                    .put("fileName", record.fileName)
                    .put("filePath", record.destPath)
                    .put(
                        "storage",
                        if (record.saf || record.destPath.startsWith("content://")) "saf" else "file",
                    )
                    .put("size", record.received)
                    .put("status", record.status.name)
                    .put("errorKind", record.errorKind ?: JSONObject.NULL)
                    .put("errorMessage", record.errorMessage ?: JSONObject.NULL)
                    .put("finishedAt", record.finishedAt),
            )
        }
        return JSONObject()
            .put("version", HISTORY_VERSION)
            .put("downloads", array)
            .toString()
    }
}
