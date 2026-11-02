package app.particlebook.android.ui

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import app.particlebook.android.ui.data.DownloadLocation
import app.particlebook.android.ui.data.LibraryStore
import app.particlebook.android.zlib.ZBookDetail
import app.particlebook.android.zlib.ZlibClient
import app.particlebook.android.zlib.ZlibException
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
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** 下载任务状态机（spec §4.4）：Preparing → Downloading → Completed / Failed。 */
enum class ZlibDownloadStatus { PREPARING, DOWNLOADING, COMPLETED, FAILED }

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
) {
    /** 0f..1f；服务器没给 Content-Length 时为 null（UI 显示不确定进度）。 */
    val progress: Float?
        get() = if (total > 0L) (received.toFloat() / total).coerceIn(0f, 1f) else null

    val isActive: Boolean
        get() = status == ZlibDownloadStatus.PREPARING || status == ZlibDownloadStatus.DOWNLOADING
}

/**
 * Z-Library 下载管理器（进程级单例，P1b）：
 * - [tasks]：当前/最近的任务（全局下载卡片订阅它；完成 2.5s 后自动移除）；
 * - [history]：持久化到 filesDir/zlib_downloads.json 的终态记录（下载记录页/详情页"已下载"判断）。
 *
 * 网络调用全部在 Dispatchers.IO；进度回调节流 ≥150ms 再写 StateFlow，避免主线程抖动。
 */
object ZlibDownloadManager {

    private const val TAG = "PBZlib"
    private const val PERF_TAG = "PBPERF"
    private const val HISTORY_NAME = "zlib_downloads.json"
    private const val HISTORY_TMP_NAME = "zlib_downloads.json.tmp"
    private const val HISTORY_VERSION = 1
    private const val PROGRESS_THROTTLE_MS = 150L
    private const val COMPLETED_VISIBLE_MS = 2500L
    private const val DEFAULT_DIR_NAME = "Z-Library"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _tasks = MutableStateFlow<List<ZlibDownloadTask>>(emptyList())
    private val _history = MutableStateFlow<List<ZlibDownloadTask>>(emptyList())
    private val historyWrittenAt = AtomicLong(0L)
    private var appContext: Context? = null

    /** 当前 + 最近完成的任务（卡片展示用）。 */
    val tasks: StateFlow<List<ZlibDownloadTask>> = _tasks.asStateFlow()

    /** 持久化下载记录（最新在前）。 */
    val history: StateFlow<List<ZlibDownloadTask>> = _history.asStateFlow()

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { readHistory(context) }
            _history.value = loaded
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
        if (done != null && done.status == ZlibDownloadStatus.COMPLETED &&
            DownloadLocation.exists(context, done.destPath)
        ) {
            return
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

    /** 失败卡片/详情页的重试：复用原 downloadPath 与目标文件。 */
    fun retry(id: String, store: LibraryStore) {
        val current = _tasks.value.firstOrNull { it.id == id } ?: return
        if (current.isActive) return
        val task = current.copy(
            received = 0L,
            total = -1L,
            status = ZlibDownloadStatus.PREPARING,
            errorKind = null,
            errorMessage = null,
        )
        _tasks.update { list -> list.map { if (it.id == id) task else it } }
        launchDownload(task, store)
    }

    /** 关闭卡片（不影响下载记录）。 */
    fun dismiss(id: String) {
        _tasks.update { list -> list.filterNot { it.id == id } }
    }

    // ------------------------------------------------------------ 内部实现

    private fun taskId(bookId: String): String = "zlib:" + bookId

    private fun launchDownload(task: ZlibDownloadTask, store: LibraryStore) {
        val context = appContext ?: return
        scope.launch {
            val began = SystemClock.elapsedRealtime()
            val client = ZlibClient.get(context)
            val lastNotify = AtomicLong(0L)
            val onProgress: (Long, Long) -> Unit = { received, total ->
                val now = SystemClock.elapsedRealtime()
                val shouldNotify = now - lastNotify.get() >= PROGRESS_THROTTLE_MS ||
                    (total > 0L && received >= total)
                if (shouldNotify && lastNotify.getAndSet(now) != now) {
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
                _tasks.update { list ->
                    list.map {
                        if (it.id == task.id) it.copy(status = ZlibDownloadStatus.DOWNLOADING) else it
                    }
                }
                if (task.saf || task.destPath.startsWith("content://")) {
                    downloadToSaf(context, client, task, store, onProgress, began)
                } else {
                    downloadToFile(client, task, store, onProgress, began)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                val failed = updateTask(task.id) {
                    it.copy(
                        status = ZlibDownloadStatus.FAILED,
                        errorKind = (throwable as? ZlibException)?.kind?.name ?: "UNKNOWN",
                        errorMessage = ZlibUiError.message(throwable),
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
        }
    }

    /** P1b 原有路径：直接下载到外部应用目录 File。 */
    private suspend fun downloadToFile(
        client: ZlibClient,
        task: ZlibDownloadTask,
        store: LibraryStore,
        onProgress: (Long, Long) -> Unit,
        began: Long,
    ) {
        val file = withContext(Dispatchers.IO) {
            client.download(task.downloadPath, File(task.destPath), onProgress)
        }
        val completed = updateTask(task.id) {
            it.copy(
                status = ZlibDownloadStatus.COMPLETED,
                received = maxOf(it.received, file.length()),
                total = file.length().takeIf { length -> length > 0L } ?: it.total,
                fileName = file.name,
                destPath = file.absolutePath,
                finishedAt = System.currentTimeMillis(),
            )
        } ?: task
        addHistory(completed, file.length())
        store.adoptLocalFile(
            file = file,
            title = task.title,
            author = task.author,
            extraTag = bookTag(task.bookId),
        ).onFailure { throwable ->
            Log.e(TAG, "adopt downloaded file failed path=" + file.absolutePath, throwable)
        }
        Log.i(
            PERF_TAG,
            "zlib_download|ms=" + (SystemClock.elapsedRealtime() - began) +
                "|extra=book:" + task.bookId + ",bytes:" + file.length() + ",status:ok,target:file",
        )
        removeCompletedCardLater(task.id)
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
    ) {
        val tempDir = File(context.cacheDir, "zlib-downloads").apply { mkdirs() }
        val temp = File(tempDir, task.id.replace(':', '_') + ".part")
        try {
            val file = withContext(Dispatchers.IO) {
                client.download(task.downloadPath, temp, onProgress)
            }
            val bytes = file.length()
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
            val actualName = DownloadLocation.displayName(context, target) ?: task.fileName
            val completed = updateTask(task.id) {
                it.copy(
                    status = ZlibDownloadStatus.COMPLETED,
                    received = bytes,
                    total = bytes,
                    fileName = actualName,
                    destPath = target.toString(),
                    finishedAt = System.currentTimeMillis(),
                )
            } ?: task
            addHistory(completed, bytes)
            store.adoptDocument(
                uri = target,
                displayName = actualName,
                title = task.title,
                author = task.author,
                extraTag = bookTag(task.bookId),
            ).onFailure { throwable ->
                Log.e(TAG, "adopt saf downloaded document failed uri=" + target, throwable)
            }
            Log.i(
                PERF_TAG,
                "zlib_download|ms=" + (SystemClock.elapsedRealtime() - began) +
                    "|extra=book:" + task.bookId + ",bytes:" + bytes + ",status:ok,target:saf",
            )
            removeCompletedCardLater(task.id)
        } finally {
            withContext(Dispatchers.IO) { runCatching { temp.delete() } }
        }
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
        )
        _history.update { list -> listOf(record) + list.filterNot { it.bookId == record.bookId } }
        historyWrittenAt.set(System.currentTimeMillis())
        val context = appContext ?: return
        scope.launch(Dispatchers.IO) { runCatching { writeHistory(context, _history.value) } }
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

    private fun readHistory(context: Context): List<ZlibDownloadTask> {
        val file = File(context.filesDir, HISTORY_NAME)
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText())
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
            out
        }.onFailure { Log.e(TAG, "read download history failed", it) }.getOrDefault(emptyList())
    }

    private fun writeHistory(context: Context, records: List<ZlibDownloadTask>) {
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
        val payload = JSONObject()
            .put("version", HISTORY_VERSION)
            .put("downloads", array)
            .toString()
        val target = File(context.filesDir, HISTORY_NAME)
        val tmp = File(context.filesDir, HISTORY_TMP_NAME)
        tmp.writeText(payload)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                target.writeText(payload)
                tmp.delete()
            }
        }
    }
}
