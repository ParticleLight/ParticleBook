package app.particlebook.android.ui

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import android.util.Log
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

    /** 下载目录：getExternalFilesDir(DOWNLOADS)/Z-Library，失败退 filesDir/downloads/Z-Library。 */
    fun downloadDir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val external = File(base ?: context.filesDir, DEFAULT_DIR_NAME)
        if (external.exists() || external.mkdirs()) return external
        val fallback = File(File(context.filesDir, "downloads"), DEFAULT_DIR_NAME)
        fallback.mkdirs()
        return fallback
    }

    fun taskForBook(bookId: String): ZlibDownloadTask? =
        _tasks.value.firstOrNull { it.bookId == bookId }

    fun historyForBook(bookId: String): ZlibDownloadTask? =
        _history.value.firstOrNull { it.bookId == bookId }

    /** 详情页点「下载」：已在下载中则忽略；失败任务会被重置；已完成且有文件则不重复下载。 */
    fun start(detail: ZBookDetail, store: LibraryStore) {
        val context = appContext ?: return
        val running = _tasks.value.firstOrNull {
            it.bookId == detail.bookId && it.isActive
        }
        if (running != null) return
        val dir = downloadDir(context)
        val done = historyForBook(detail.bookId)
        if (done != null && File(done.destPath).isFile) return
        val fileName = uniqueFileName(dir, detail)
        val task = ZlibDownloadTask(
            id = taskId(detail.bookId),
            bookId = detail.bookId,
            title = detail.title,
            author = detail.authors.firstOrNull(),
            format = detail.format,
            downloadPath = detail.downloadPath,
            fileName = fileName,
            destPath = File(dir, fileName).absolutePath,
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
            try {
                _tasks.update { list ->
                    list.map {
                        if (it.id == task.id) it.copy(status = ZlibDownloadStatus.DOWNLOADING) else it
                    }
                }
                val file = withContext(Dispatchers.IO) {
                    client.download(task.downloadPath, File(task.destPath)) { received, total ->
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
                store.adoptLocalFile(file, task.title, task.author).onFailure { throwable ->
                    Log.e(TAG, "adopt downloaded file failed path=" + file.absolutePath, throwable)
                }
                Log.i(
                    PERF_TAG,
                    "zlib_download|ms=" + (SystemClock.elapsedRealtime() - began) +
                        "|extra=book:" + task.bookId + ",bytes:" + file.length() + ",status:ok",
                )
                scope.launch {
                    delay(COMPLETED_VISIBLE_MS)
                    _tasks.update { list ->
                        list.filterNot {
                            it.id == task.id && it.status == ZlibDownloadStatus.COMPLETED
                        }
                    }
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
                addHistory(failed, File(failed.destPath).length())
                Log.i(
                    PERF_TAG,
                    "zlib_download|ms=" + (SystemClock.elapsedRealtime() - began) +
                        "|extra=book:" + task.bookId + ",bytes:" + failed.received +
                        ",status:fail:" + (failed.errorKind ?: "UNKNOWN"),
                )
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
        val extension = detail.format?.trim()?.lowercase()
            ?.takeIf { it == "pdf" || it == "epub" }
            ?: "pdf"
        val base = sanitizeFileName(detail.title).ifBlank { "book" }
        var candidate = base + "." + extension
        var index = 2
        while (File(dir, candidate).exists()) {
            candidate = base + " (" + index + ")." + extension
            index += 1
        }
        return candidate
    }

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
                    fileName = item.optString("fileName").ifBlank { File(destPath).name },
                    destPath = destPath,
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
