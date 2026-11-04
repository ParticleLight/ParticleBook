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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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

/** T15/D8：删除下载记录的真实结果（文件删除是异步的，UI 需要能提示「文件删除失败/文件占用」）。 */
enum class ZlibHistoryDeleteResult {
    NOT_FOUND,
    RECORD_ONLY,
    RECORD_AND_FILE,
    FILE_DELETE_FAILED,
    FILE_IN_USE_RECORD_ONLY,
}

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

    /**
     * T15：只取消「指定的那一代」——身份校验与置位在同一把锁内完成。
     * 供看门狗使用：旧 tick 即使越过了 isCurrent 检查，也绝不会误杀 retry 后的新 run。
     */
    fun cancelIfCurrent(run: ZlibDownloadRun, reason: ZlibCancelReason): Boolean =
        synchronized(lock) {
            if (runs[run.taskId] !== run) {
                false
            } else {
                run.cancel(reason)
            }
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

/**
 * T15(P0)：导入成功写历史的唯一入口。
 *
 * 必须基于 updateTask 返回的最新任务生成 COMPLETED 记录；若用「导入前快照」写历史，
 * 记录会停留在 DOWNLOADING/finishedAt=0，导致记录页「未完成 · 未知时间」、详情页失去
 * 「已下载 · 打开」、再点下载重复下载，进程重启还会被恢复成 FAILED(INTERRUPTED)。
 */
internal fun zlibCompletedHistoryRecord(
    task: ZlibDownloadTask,
    size: Long,
    fileName: String? = null,
    destPath: String? = null,
): ZlibDownloadTask = ZlibDownloadCompletion.imported(task, size, fileName, destPath)

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
    private const val SAF_TEMP_DIR = "zlib-downloads"

    /** U10：进程被杀/崩溃后，未完成下载恢复出来的错误类型（可重试，不算登录失败）。 */
    const val ZLIB_INTERRUPTED_KIND = "INTERRUPTED"
    const val ZLIB_INTERRUPTED_MESSAGE = "上次下载被中断，可重试"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _tasks = MutableStateFlow<List<ZlibDownloadTask>>(emptyList())
    private val _history = MutableStateFlow<List<ZlibDownloadTask>>(emptyList())
    /** T15：冷启是否已读完磁盘历史（空历史也会置 true），记录页用来区分「加载中」与真·空态。 */
    private val _historyLoaded = MutableStateFlow(false)
    private val runs = ZlibDownloadRunRegistry()
    private var appContext: Context? = null
    private var historyStore: ZlibDownloadHistoryStore? = null

    /**
     * U10：非终态快照（进行中的下载）。只用于写盘恢复，**不发布**到 [history]，
     * 所以下载记录页不会提前出现一条「下载中」记录；进程被杀后下次启动把它恢复成
     * FAILED(INTERRUPTED) 记录（可重试/可删除），SAF 半截文档的 uri 也在记录里（不产生孤儿）。
     */
    private val pendingRecords = LinkedHashMap<String, ZlibDownloadTask>()

    /** T15：start() 异步判定窗口内按 bookId 去重（只在主线程读写），防连点开出两个同书任务。 */
    private val startGuards = HashSet<String>()

    /** T15：冷启读盘完成前被 gate 掉的写盘请求，读完后补一次合并快照。 */
    private var historyWritePending = false

    /** 当前 + 最近完成的任务（卡片展示用）。 */
    val tasks: StateFlow<List<ZlibDownloadTask>> = _tasks.asStateFlow()

    /** 持久化下载记录（最新在前）。 */
    val history: StateFlow<List<ZlibDownloadTask>> = _history.asStateFlow()

    /** T15/D8：冷启历史加载完成信号（空历史也为 true），供记录页空态占位。 */
    val historyLoaded: StateFlow<Boolean> = _historyLoaded.asStateFlow()

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
            // U10：上次进程被杀时留在磁盘上的非终态记录 → 恢复成可重试的中断失败态。
            val recovered = zlibRecoverInterrupted(loaded, System.currentTimeMillis())
            // 加载期间若已有新终态写入（极端冷启并发），保留内存里的新记录。
            _history.update { current ->
                if (current.isEmpty()) {
                    recovered
                } else {
                    recovered.filterNot { old -> current.any { it.bookId == old.bookId } } + current
                }
            }
            _historyLoaded.value = true
            // 规范化结果写回磁盘；读盘完成前被 gate 的写盘请求在这里补一次（合并后的快照）。
            val needsWrite = historyWritePending || recovered != loaded
            historyWritePending = false
            if (needsWrite) scheduleHistoryWrite()
            // U10：清掉上次进程被杀留下的 cacheDir 半成品（本地临时文件无法跨进程续传）。
            withContext(Dispatchers.IO) {
                zlibCleanTempDownloads(File(app.cacheDir, SAF_TEMP_DIR))
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
        // T15(U8)：start 在 Compose 主线程调用；DownloadLocation.exists 在 SAF 下会走
        // resolver.query/openFileDescriptor，目录选择要读 SharedPreferences，uniqueFileName 要扫目录，
        // 一律挪到 Dispatchers.IO，避免详情页首帧/冷启卡顿。全部判定/去重都放在主线程协程里，
        // startGuards 覆盖异步判定窗口，防连点开出两个同书任务。
        scope.launch {
            if (_tasks.value.any { it.bookId == detail.bookId && it.isActive }) return@launch
            if (!startGuards.add(detail.bookId)) return@launch
            try {
                val saf = withContext(Dispatchers.IO) { DownloadLocation.isCustom(context) }
                // 判定期间历史可能刚加载完（冷启）→ 在 IO hop 之后再读一次，避免重复下载已完成的书。
                val done = historyForBook(detail.bookId)
                val needsFileCheck = done != null && (
                    done.status == ZlibDownloadStatus.COMPLETED ||
                        (done.status == ZlibDownloadStatus.FAILED &&
                            done.errorKind == ZLIB_IMPORT_FAILED_KIND) ||
                        (saf && done.status == ZlibDownloadStatus.FAILED &&
                            done.errorKind == ZLIB_INTERRUPTED_KIND &&
                            done.destPath.startsWith("content://"))
                    )
                val fileExists = done != null && needsFileCheck &&
                    withContext(Dispatchers.IO) { DownloadLocation.exists(context, done.destPath) }
                if (done != null && done.status == ZlibDownloadStatus.COMPLETED && fileExists) return@launch
                // U4：上次下载完成但导入失败 → 文件还在，只需重新导入（不再消耗下载额度）。
                if (done != null && done.status == ZlibDownloadStatus.FAILED &&
                    done.errorKind == ZLIB_IMPORT_FAILED_KIND && fileExists
                ) {
                    reimportTask(
                        done.copy(
                            title = detail.title.ifBlank { done.title },
                            author = done.author ?: detail.authors.firstOrNull(),
                        ),
                        store,
                    )
                    return@launch
                }
                // P1c：自定义 SAF 目录 → 下载完成后 createDocument；默认仍是 File 路径（行为不变）。
                // U10：上次进程被杀前已经创建好的 SAF 文档 → 复用（下载时截断重写），不在用户目录留孤儿文件。
                val reusedSafDoc = done?.takeIf {
                    saf &&
                        fileExists &&
                        it.status == ZlibDownloadStatus.FAILED &&
                        it.errorKind == ZLIB_INTERRUPTED_KIND &&
                        it.destPath.startsWith("content://")
                }
                val fileName: String
                val destPath: String
                if (saf) {
                    fileName = reusedSafDoc?.fileName?.takeIf { it.isNotBlank() } ?: suggestedFileName(detail)
                    // 目标 uri 在真正下载时创建，完成后再回填；可复用时直接用旧 uri。
                    destPath = reusedSafDoc?.destPath.orEmpty()
                } else {
                    val target = withContext(Dispatchers.IO) {
                        val dir = downloadDir(context)
                        val name = uniqueFileName(dir, detail)
                        name to File(dir, name).absolutePath
                    }
                    fileName = target.first
                    destPath = target.second
                }
                // 异步判定期间用户可能已从别的入口开始下载 → 再确认一次。
                if (_tasks.value.any { it.bookId == detail.bookId && it.isActive }) return@launch
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
                persistActive(task)
                launchDownload(task, store)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                Log.e(TAG, "zlib_download_start_failed book=" + detail.bookId, throwable)
            } finally {
                startGuards.remove(detail.bookId)
            }
        }
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
        persistActive(task)
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
        clearActive(task.bookId)
    }

    /**
     * U3：已取消卡片的「删除」——移除卡片并清掉半成品（普通路径的 .part/.part.meta，
     * SAF 路径的 cacheDir 临时文件与可能已创建的半截文档）。取消本身不删文件，只有这里删。
     */
    fun discard(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (task.isActive) return
        _tasks.update { list -> list.filterNot { it.id == id } }
        clearActive(task.bookId)
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
     * - 返回 true 表示确实删掉了一条记录；文件删除结果通过 [onResult] 告知（T15/D8）。
     * - [onResult] 线程：NOT_FOUND / RECORD_ONLY / FILE_IN_USE_RECORD_ONLY 在调用线程同步回调；
     *   RECORD_AND_FILE / FILE_DELETE_FAILED 在 IO 删除结束后切回主线程回调。
     */
    fun removeHistory(
        bookId: String,
        deleteFile: Boolean,
        onResult: ((ZlibHistoryDeleteResult) -> Unit)? = null,
    ): Boolean {
        val removed = _history.value.firstOrNull { it.bookId == bookId }
        if (removed == null) {
            onResult?.invoke(ZlibHistoryDeleteResult.NOT_FOUND)
            return false
        }
        // T15：同书/同目标仍有进行中的下载时文件正在被写，只删记录、不删文件（防删掉新 .part）。
        val inUse = _tasks.value.any { task ->
            task.isActive && (task.bookId == bookId ||
                (removed.destPath.isNotBlank() && task.destPath == removed.destPath))
        }
        _history.update { list -> list.filterNot { it.bookId == bookId } }
        // 同书仍在下载时保留非终态快照（进程被杀还能恢复），不能因删记录丢掉正在跑的恢复线索。
        if (!inUse) clearActive(bookId)
        scheduleHistoryWrite()
        // 同一本书的终态卡片一并移除，避免记录页删了而书架还挂着卡片。
        _tasks.update { list -> list.filterNot { it.bookId == bookId && !it.isActive } }
        if (!deleteFile) {
            onResult?.invoke(ZlibHistoryDeleteResult.RECORD_ONLY)
            return true
        }
        if (inUse) {
            onResult?.invoke(ZlibHistoryDeleteResult.FILE_IN_USE_RECORD_ONLY)
            return true
        }
        val context = appContext
        scope.launch(Dispatchers.IO) {
            val deleted = deleteDownloadedFile(context, removed)
            val result = if (deleted) {
                ZlibHistoryDeleteResult.RECORD_AND_FILE
            } else {
                ZlibHistoryDeleteResult.FILE_DELETE_FAILED
            }
            if (onResult != null) {
                withContext(Dispatchers.Main.immediate) { onResult(result) }
            }
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
            // T15：runs.begin/watchdog 启动之后的任何同步异常（含 ZlibClient.get 初始化失败）
            // 都必须走 finally 释放 run 并停表，否则任务会永远卡在 PREPARING，retry/cancel 全失效。
            try {
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
                // T15：cancelAndJoin 确保旧看门狗彻底停表后才 release；NonCancellable 让取消路径也能等到。
                withContext(NonCancellable) { watchdogJob.cancelAndJoin() }
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
                // T15：按 run 身份原子取消（同一把锁内校验世代），旧 tick 不会误杀 retry 后的新下载。
                if (runs.cancelIfCurrent(run, ZlibCancelReason.STALL)) {
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
        // T15：网络阶段已结束——先置 importing 封口，堵住「未知 Content-Length 时取消被接受、
        // 随后又完成导入」的竞态（cancel 在 importing/cancelling 状态下会被忽略）。
        updateTask(task.id) { it.copy(importing = true, cancelling = false) } ?: return
        val size = withContext(Dispatchers.IO) { file.length() }
        updateTask(task.id) {
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
            completeImport(task.id, size, file.name, file.absolutePath)
            Log.i(
                PERF_TAG,
                "zlib_download|ms=" + (SystemClock.elapsedRealtime() - began) +
                    "|extra=book:" + task.bookId + ",bytes:" + size + ",status:ok,target:file",
            )
        } else {
            failImport(task.id, size, adopt.exceptionOrNull())
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
            // T15：同 downloadToFile——网络阶段结束后立即封口，SAF 拷贝/查询期间不再接受取消。
            updateTask(task.id) { it.copy(importing = true, cancelling = false) } ?: return
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
            // U10：同时落盘非终态快照 —— 进程此时被杀，下次启动能凭这条记录找到并复用/删除半截文档。
            if (task.destPath != target.toString()) {
                updateTask(task.id) { it.copy(destPath = target.toString()) }?.let { persistActive(it) }
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
            updateTask(task.id) {
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
                completeImport(task.id, bytes, actualName, target.toString())
                Log.i(
                    PERF_TAG,
                    "zlib_download|ms=" + (SystemClock.elapsedRealtime() - began) +
                        "|extra=book:" + task.bookId + ",bytes:" + bytes + ",status:ok,target:saf",
                )
            } else {
                failImport(task.id, bytes, adopt.exceptionOrNull())
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
                    // U10：用户取消是终态，删掉非终态快照（半成品文件按 U3 保留，卡片上可续传/删除）。
                    clearActive(task.bookId)
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
        size: Long,
        fileName: String?,
        destPath: String?,
    ) {
        // T15(P0)：用 updateTask 返回的 COMPLETED 副本写历史，不能用导入前快照。
        val completed = updateTask(id) {
            zlibCompletedHistoryRecord(it, size, fileName, destPath)
        } ?: return
        addHistory(completed, size)
        removeCompletedCardLater(id)
    }

    private fun failImport(
        id: String,
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
        persistActive(_tasks.value.firstOrNull { it.id == task.id } ?: task)
        scope.launch {
            try {
                updateTask(task.id) {
                    ZlibDownloadCompletion.importing(it, task.received, task.fileName, task.destPath)
                } ?: return@launch
                if (!runs.isCurrent(run)) return@launch
                val adopt = withContext(Dispatchers.IO) { adoptExisting(context, task, store) }
                if (!runs.isCurrent(run)) return@launch
                if (adopt.isSuccess) {
                    completeImport(task.id, task.received, task.fileName, task.destPath)
                } else {
                    failImport(task.id, task.received, adopt.exceptionOrNull())
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
        val dir = File(context.cacheDir, SAF_TEMP_DIR).apply { mkdirs() }
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
        // U10：进入终态 → 非终态快照退场（随后统一写一次盘）。
        pendingRecords.remove(task.bookId)
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
        // T15：冷启读盘完成前禁止写盘，否则 pending-only 快照可能覆盖磁盘上的旧历史。
        if (!_historyLoaded.value) {
            historyWritePending = true
            return
        }
        val store = historyStore ?: return
        if (store.submit(snapshotForDisk())) {
            scope.launch(Dispatchers.IO) { store.drain() }
        }
    }

    /**
     * U10：磁盘快照 = 非终态快照 + 终态历史（同书以非终态为准，避免旧终态覆盖进行中）。
     * 进行中记录只写盘、不进入 [_history]，所以记录页/详情页看不到它。
     */
    private fun snapshotForDisk(): List<ZlibDownloadTask> {
        if (pendingRecords.isEmpty()) return _history.value
        val pendingIds = pendingRecords.keys
        return pendingRecords.values.toList() + _history.value.filterNot { it.bookId in pendingIds }
    }

    /** U10：记录一条进行中的下载快照并落盘（失败/取消/完成时由 [clearActive] 移除）。 */
    private fun persistActive(task: ZlibDownloadTask) {
        pendingRecords[task.bookId] = task.copy(importing = false, cancelling = false)
        scheduleHistoryWrite()
    }

    /** U10：进行中快照退场（终态写入走 [addHistory] 里同一次 scheduleHistoryWrite）。 */
    private fun clearActive(bookId: String) {
        if (pendingRecords.remove(bookId) != null) scheduleHistoryWrite()
    }

    /**
     * T4（D8）：删除下载文件（普通路径/SAF）。
     * @return true = 文件确实不存在了（已删或本来就不存在）；false = 删除失败（T15：记录页要提示）。
     */
    private fun deleteDownloadedFile(context: Context?, record: ZlibDownloadTask): Boolean {
        if (record.destPath.startsWith("content://")) {
            val resolver = context?.contentResolver ?: return false
            val deleted = runCatching {
                DocumentsContract.deleteDocument(resolver, Uri.parse(record.destPath))
            }.onFailure {
                Log.w(TAG, "delete saf download failed uri=" + record.destPath, it)
            }.getOrDefault(false)
            if (deleted) return true
            // deleteDocument 对「文档已不存在」通常返回 false/抛错：再查一次存在性，避免误报删除失败。
            return context != null && !DownloadLocation.exists(context, record.destPath)
        }
        if (record.destPath.isBlank()) return false
        val file = File(record.destPath)
        val deleted = runCatching { file.delete() }.getOrDefault(false) || !file.exists()
        runCatching { ZlibDownloadFiles.deletePartial(file) }
        return deleted
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
            // U10：非终态快照可能还没有目标（SAF 目录里文档尚未创建），也要恢复成中断记录；
            // 终态记录没有文件路径则视为脏数据丢弃。
            val rawStatus = item.optString("status")
            val pending = rawStatus == ZlibDownloadStatus.PREPARING.name ||
                rawStatus == ZlibDownloadStatus.DOWNLOADING.name
            if (bookId.isBlank()) continue
            if (destPath.isBlank() && !pending) continue
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
                // U10：保留非终态（进程被杀前写下的快照），启动时由 zlibRecoverInterrupted 改写。
                status = when (rawStatus) {
                    ZlibDownloadStatus.PREPARING.name -> ZlibDownloadStatus.PREPARING
                    ZlibDownloadStatus.DOWNLOADING.name -> ZlibDownloadStatus.DOWNLOADING
                    ZlibDownloadStatus.CANCELLED.name -> ZlibDownloadStatus.CANCELLED
                    ZlibDownloadStatus.FAILED.name -> ZlibDownloadStatus.FAILED
                    else -> ZlibDownloadStatus.COMPLETED
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

/**
 * U10：启动时把磁盘上读回的非终态记录（PREPARING/DOWNLOADING）改写成可重试的失败态。
 *
 * 进程被杀/崩溃时不会有人写终态，这些记录是唯一线索：改写成 FAILED(INTERRUPTED) 后
 * 详情页/记录页可以正常「重试」，SAF 的半截文档 uri 也留在 destPath 里供重试复用或删除，
 * 不会出现「文件在用户目录里但应用完全不知道」的孤儿。终态记录原样返回（幂等）。
 */
internal fun zlibRecoverInterrupted(
    loaded: List<ZlibDownloadTask>,
    now: Long,
): List<ZlibDownloadTask> = loaded.map { record ->
    if (record.status == ZlibDownloadStatus.PREPARING ||
        record.status == ZlibDownloadStatus.DOWNLOADING
    ) {
        record.copy(
            status = ZlibDownloadStatus.FAILED,
            errorKind = ZlibDownloadManager.ZLIB_INTERRUPTED_KIND,
            errorMessage = ZlibDownloadManager.ZLIB_INTERRUPTED_MESSAGE,
            importing = false,
            cancelling = false,
            finishedAt = now,
        )
    } else {
        record
    }
}

/**
 * U10：清 cacheDir/zlib-downloads 下进程被杀留下的半成品（.part / .part.meta）。
 * 这些临时文件跨进程无法可靠续传，留着只会占空间；返回删除的文件数。
 */
internal fun zlibCleanTempDownloads(dir: File): Int {
    if (!dir.isDirectory) return 0
    var removed = 0
    dir.walkBottomUp().forEach { file ->
        if (file == dir) return@forEach
        if (file.isFile && file.delete()) removed++
    }
    dir.delete()
    return removed
}
