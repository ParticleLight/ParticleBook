package app.particlebook.android.ui.data

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.particlebook.android.Assets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * 书架数据源：assets/fixtures.json（内置样书）+ filesDir/books.json（用户导入的持久化索引）。
 * - 导入：复制文件到 filesDir/_pb_files → 串行写索引（唯一 tmp → 原子 rename）→ 更新内存。
 * - 启动：Assets.seed + fixtures 解析 + books.json 合并，按 filePath 去重；索引里文件已不存在的条目跳过并清理。
 * - 删除：串行重写索引成功后再删文件。
 * - 并发（P1c U1）：adopt/import/remove/load 的索引写路径全部经 [LibraryIndexStore] 的 Mutex 串行化，
 *   锁内基于**最新已提交列表**合并（不再用进入 IO 前的快照）；写失败不提交内存。
 */
class LibraryStore(private val context: Context) {

    var books by mutableStateOf<List<Book>>(emptyList())
        private set

    var loading by mutableStateOf(true)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    private var loadedOnce = false

    private val indexStore = LibraryIndexStore(
        dir = context.filesDir,
        log = { message, throwable -> Log.e(TAG, message, throwable) },
    )

    init {
        // 落盘成功后在锁内同步内存态：并发完成时不会出现「后返回的旧列表覆盖新列表」。
        indexStore.onCommitted = { committed -> books = committed }
    }

    suspend fun ensureLoaded() {
        if (loadedOnce) return
        reload()
    }

    suspend fun reload() {
        loading = true
        error = null
        val began = SystemClock.elapsedRealtime()
        try {
            val loaded = withContext(Dispatchers.IO) { loadFromDisk() }
            Log.i(
                PERF_TAG,
                "PBPERF|phase=library_load|ms=" + (SystemClock.elapsedRealtime() - began) +
                    "|extra=books:" + loaded.size,
            )
        } catch (throwable: Throwable) {
            Log.e(TAG, "library load failed", throwable)
            error = throwable.message ?: "书架加载失败"
        } finally {
            loading = false
            loadedOnce = true
        }
    }

    /** 通过 SAF 导入一本本地文件：复制文件 → 写索引 → 加入书架。 */
    suspend fun importDocument(uri: Uri): Result<Book> {
        val began = SystemClock.elapsedRealtime()
        val result = withContext(Dispatchers.IO) {
            try {
                val copied = copyUriToLibrary(uri, prefix = "import")
                // 索引写失败：回滚刚复制的文件，避免留下无主文件。
                indexStore.registerFile(
                    file = copied.file,
                    title = copied.displayName.substringBeforeLast('.', copied.displayName),
                    author = null,
                    tags = emptyList(),
                ).onFailure { runCatching { copied.file.delete() } }
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                Result.failure(throwable)
            }
        }
        result.onSuccess { book ->
            Log.i(
                PERF_TAG,
                "PBPERF|phase=library_import|ms=" + (SystemClock.elapsedRealtime() - began) +
                    "|extra=books:" + books.size,
            )
            Log.d(TAG, "imported book id=" + book.id + " title=" + book.title)
        }.onFailure { throwable ->
            Log.e(TAG, "import failed uri=" + uri, throwable)
        }
        return result
    }

    /**
     * P1b：把已经落盘的下载文件直接登记进书架（不二次复制，文件留在下载目录）。
     * - 同一路径已在书架：幂等返回已有条目；
     * - 与 [importDocument] 共用串行索引写入逻辑，id 在锁内分配。
     * - 文件不存在/不是文件：失败。
     */
    suspend fun adoptLocalFile(
        file: File,
        title: String?,
        author: String?,
        extraTag: String? = null,
    ): Result<Book> {
        if (!file.isFile) {
            return Result.failure(IllegalArgumentException("下载文件不存在或不可读"))
        }
        val began = SystemClock.elapsedRealtime()
        val tags = buildList {
            add(DOWNLOAD_TAG)
            extraTag?.takeIf { it.isNotBlank() && it != DOWNLOAD_TAG }?.let(::add)
        }
        val result = withContext(Dispatchers.IO) {
            indexStore.registerFile(file = file, title = title, author = author, tags = tags)
        }
        result.onSuccess {
            Log.i(
                PERF_TAG,
                "PBPERF|phase=library_adopt|ms=" + (SystemClock.elapsedRealtime() - began) +
                    "|extra=books:" + books.size,
            )
        }.onFailure { throwable ->
            // 索引写失败不删下载文件（下载目录归用户/记录页所有），只作失败返回。
            Log.e(TAG, "adopt local file failed path=" + file.absolutePath, throwable)
        }
        return result
    }

    /**
     * P1c：下载目录是 SAF 时，下载完成的文档先复制回 filesDir/_pb_files 再登记进书架。
     * 书架索引只接受本地 File（外部 uri 的授权/文档可能在重启后失效），复制后再走 [adoptLocalFile]。
     */
    suspend fun adoptDocument(
        uri: Uri,
        displayName: String?,
        title: String?,
        author: String?,
        extraTag: String? = null,
    ): Result<Book> {
        val local = withContext(Dispatchers.IO) {
            try {
                Result.success(copyUriToLibrary(uri, prefix = "zlib"))
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                Log.e(TAG, "copy saf document failed uri=" + uri, throwable)
                Result.failure(throwable)
            }
        }
        return local.fold(
            onSuccess = { copied ->
                adoptLocalFile(
                    file = copied.file,
                    title = title?.takeIf { it.isNotBlank() }
                        ?: copied.displayName.substringBeforeLast('.', ""),
                    author = author,
                    extraTag = extraTag,
                ).onFailure { throwable ->
                    runCatching { copied.file.delete() }
                    Log.e(TAG, "adopt saf document failed uri=" + uri, throwable)
                }
            },
            onFailure = { throwable -> Result.failure(throwable) },
        )
    }

    /**
     * 删除选中的书：串行重写索引（成功后再删 _pb_files 里的文件）。
     * 返回 [Result]：成功携带真正移除的条数；写索引失败时返回 failure，内存与磁盘都不变，
     * 由 UI 提示「删除失败，请重试」，不能无条件提示删除成功。
     */
    suspend fun removeBooks(ids: Set<Long>): Result<Int> {
        if (ids.isEmpty()) return Result.success(0)
        return withContext(Dispatchers.IO) {
            try {
                // 写失败会抛异常：内存与索引都不变，避免「UI 删了但文件索引还在」或反过来的半状态。
                val removed = indexStore.remove(ids)
                removed.forEach { book ->
                    val file = File(book.filePath)
                    if (file.parentFile?.name == LIBRARY_DIR_NAME) {
                        runCatching { file.delete() }
                    }
                }
                Result.success(removed.size)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                Log.e(TAG, "remove books failed ids=" + ids, throwable)
                Result.failure(throwable)
            }
        }
    }

    private fun libraryDir(): File = File(context.filesDir, LIBRARY_DIR_NAME).apply { mkdirs() }

    private suspend fun loadFromDisk(): List<Book> {
        val dir = libraryDir()
        Assets.seed(context, dir)
        val fixtures = parseBooks(Assets.readText(context, "fixtures.json"))
            .map { resolveLocal(it, dir) }
        val loaded = indexStore.load { persisted -> mergePersisted(fixtures, persisted, dir) }
        return recoverOrphanLibraryFiles(dir, loaded)
    }

    /**
     * 冷启扫描（数据层兜底）：books.json 丢失/损坏重建后，_pb_files 里仍留着用户的书籍文件。
     * 把索引里没有的、文件头确实像 EPUB/PDF 的文件补登记回来，避免「索引没了 = 书架清空、
     * 文件白占空间且再也打不开」。已知路径（fixtures/索引内条目）跳过；重复调用幂等。
     */
    private suspend fun recoverOrphanLibraryFiles(dir: File, loaded: List<Book>): List<Book> {
        val knownPaths = loaded.mapTo(HashSet(loaded.size)) { pathKey(it.filePath) }
        val candidates = dir.listFiles().orEmpty().filter { file ->
            isRecoverableLibraryFile(file) && pathKey(file.absolutePath) !in knownPaths
        }
        if (candidates.isEmpty()) return loaded
        val recovered = indexStore.recoverFiles(candidates, tags = listOf(RECOVERED_TAG))
        if (recovered.adopted.isNotEmpty()) {
            Log.w(TAG, "recovered orphan library files count=" + recovered.adopted.size)
        }
        return recovered.all
    }

    /** fixtures 优先，持久化条目按 filePath 去重、缺失文件跳过；id 冲突时重新分配。 */
    private fun mergePersisted(fixtures: List<Book>, persisted: List<Book>, dir: File): List<Book> {
        val merged = ArrayList<Book>(fixtures.size + persisted.size)
        val seenPaths = HashSet<String>(fixtures.size + persisted.size)
        val seenIds = HashSet<Long>(fixtures.size + persisted.size)
        fixtures.forEach { book ->
            seenPaths += pathKey(book.filePath)
            seenIds += book.id
            merged += book
        }
        persisted.forEach { stored ->
            val book = resolveLocal(stored, dir)
            if (!File(book.filePath).exists()) {
                // 文件已不存在：跳过，由索引层重写时清掉这条。
                Log.w(TAG, "skip missing book id=" + book.id + " path=" + book.filePath)
                return@forEach
            }
            if (!seenPaths.add(pathKey(book.filePath))) return@forEach
            val idFixed = if (seenIds.add(book.id)) book else book.copy(id = freshBookId(seenIds))
            merged += idFixed
        }
        return merged
    }

    /** fixtures 里的 file_path 是设备绝对路径，换成 _pb_files 下的本地文件（存在才换）。 */
    private fun resolveLocal(book: Book, dir: File): Book {
        val declared = File(book.filePath)
        val local = File(dir, declared.name)
        return if (local.exists()) book.copy(filePath = local.absolutePath) else book
    }

    /** 把 SAF 文档复制进 _pb_files；临时文件名带 uuid，并发导入不会互踩。 */
    private fun copyUriToLibrary(uri: Uri, prefix: String): CopiedFile {
        val dir = libraryDir()
        val displayName = queryDisplayName(uri) ?: "未命名"
        val extension = displayName.substringAfterLast('.', "").lowercase()
        // 有的文件选择器不给扩展名，用 MIME 兜底，避免把 PDF 当 EPUB 存下来。
        val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val format = when {
            extension == "pdf" || mimeType == "application/pdf" -> BookFormat.Pdf
            extension == "epub" || mimeType == "application/epub+zip" -> BookFormat.Epub
            else -> BookFormat.Epub
        }
        val safeExtension = sanitizeCopyExtension(extension, format)
        // 唯一文件名带 uuid：同一毫秒并发导入/下载也不会互相覆盖。
        val destination = File(
            dir,
            libraryCopyFileName(prefix, safeExtension, System.currentTimeMillis(), UUID.randomUUID().toString()),
        )
        // 先写 .tmp 再 rename：复制中途失败（SAF 源被删/进程被杀）不会留下半个「书籍文件」被冷启扫描误登记。
        val temp = File(dir, destination.name + TMP_SUFFIX)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
            input.use { source ->
                temp.outputStream().use { sink -> source.copyTo(sink) }
            }
            if (!temp.renameTo(destination)) error("保存导入文件失败")
        } catch (throwable: Throwable) {
            runCatching { temp.delete() }
            throw throwable
        }
        return CopiedFile(destination, displayName)
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()
}

private data class CopiedFile(val file: File, val displayName: String)

/** 复制进书架的本地文件名：prefix-时间戳-uuid 前 8 位.扩展名（uuid 保证并发唯一）。 */
internal fun libraryCopyFileName(prefix: String, extension: String, millis: Long, uuid: String): String =
    prefix + "-" + millis + "-" + uuid.lowercase(Locale.US).take(8) + "." + extension

/** 扩展名只保留字母数字并限长，避免异常/恶意的 SAF DISPLAY_NAME 带出路径分隔符。 */
internal fun sanitizeCopyExtension(rawExtension: String, format: BookFormat): String {
    val safe = rawExtension.lowercase(Locale.US).filter { it.isLetterOrDigit() }.take(8)
    return safe.ifEmpty { if (format == BookFormat.Pdf) "pdf" else "epub" }
}

/** 读文件头判断书籍类型：%PDF → Pdf，PK（ZIP/EPUB）→ Epub；读不到或不像书籍 → null。 */
internal fun sniffBookFormat(file: File): BookFormat? = runCatching {
    file.inputStream().use { input ->
        val header = ByteArray(4)
        val read = input.read(header)
        when {
            read >= 4 &&
                header[0] == 0x25.toByte() && header[1] == 0x50.toByte() &&
                header[2] == 0x44.toByte() && header[3] == 0x46.toByte() -> BookFormat.Pdf
            read >= 2 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() -> BookFormat.Epub
            else -> null
        }
    }
}.getOrNull()

/** 冷启扫描候选：_pb_files 下的普通文件、扩展名是 epub/pdf、且文件头确实像 EPUB/PDF。 */
internal fun isRecoverableLibraryFile(file: File): Boolean {
    if (!file.isFile || file.isHidden) return false
    val name = file.name.lowercase(Locale.US)
    if (!name.endsWith(".epub") && !name.endsWith(".pdf")) return false
    return sniffBookFormat(file) != null
}

/** 冷启恢复条目的标题：复制用的「prefix-时间戳-uuid」没有语义，统一用可读占位。 */
internal fun recoveredBookTitle(file: File): String {
    val base = file.nameWithoutExtension
    val generated = base.matches(Regex("^(import|zlib)-\\d{10,}(-[0-9a-f]{8})?$", RegexOption.IGNORE_CASE))
    return if (generated || base.isBlank()) "恢复的书籍" else base
}

/** 文件 lastModified → fixtures/索引同款的 ISO-8601 UTC 字符串（书架按 addedAt 倒序）。 */
internal fun isoUtcTimestamp(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(millis))

internal data class LibraryRecoveryResult(val adopted: List<Book>, val all: List<Book>)

// -------------------------------------------------------------------- 索引持久化

/** books.json 的编解码；抽成接口便于 JVM 单测注入（本地单测里 android.jar 的 org.json 是 not mocked）。 */
internal interface LibraryIndexCodec {
    fun encode(books: List<Book>): String

    /** 解析索引；格式非法时抛异常（调用方据此判定损坏并留 .bad）。 */
    fun decode(json: String): List<Book>
}

/** 生产实现：JSON 只有 version + books 数组，字段与 [Book] 一一对应；损坏检测依赖 org.json 的严格解析。 */
internal class JsonLibraryIndexCodec : LibraryIndexCodec {

    override fun encode(books: List<Book>): String = JSONObject()
        .put("version", INDEX_VERSION)
        .put("books", JSONArray().apply { books.forEach { put(bookToJson(it)) } })
        .toString()

    override fun decode(json: String): List<Book> {
        // 根节点 / books 数组非法即视为损坏：JSONObject 构造抛 JSONException，缺数组抛 IllegalStateException。
        val root = JSONObject(json)
        val array = root.optJSONArray("books")
            ?: throw IllegalStateException("books.json 缺少 books 数组")
        val out = ArrayList<Book>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            // 兼容 camelCase（本版写法）与 snake_case（万一有人手改）。
            val filePath = item.optString("filePath").ifBlank { item.optString("file_path") }
            if (filePath.isBlank()) continue
            val title = item.optString("title").takeIf { it.isNotBlank() } ?: "未命名"
            val author = item.optString("author").takeUnless { it.isBlank() || it == "null" }
            val tags = buildList {
                item.optJSONArray("tags")?.let { tagArray ->
                    for (tagIndex in 0 until tagArray.length()) {
                        tagArray.optString(tagIndex).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }
            out += Book(
                id = item.optLong("id", 0L),
                title = title,
                author = author,
                format = BookFormat.fromRaw(item.optString("format")),
                filePath = filePath,
                fileSize = item.optLong("fileSize", item.optLong("file_size", 0L)),
                addedAt = item.optString("addedAt").ifBlank { item.optString("added_at") }
                    .takeUnless { it.isBlank() || it == "null" },
                tags = tags,
            )
        }
        return out
    }

    private fun bookToJson(book: Book): JSONObject = JSONObject()
        .put("id", book.id)
        .put("title", book.title)
        .put("author", book.author ?: JSONObject.NULL)
        .put("format", if (book.format == BookFormat.Pdf) "pdf" else "epub")
        .put("filePath", book.filePath)
        .put("fileSize", book.fileSize)
        .put("addedAt", book.addedAt ?: JSONObject.NULL)
        .put("tags", JSONArray(book.tags))
}

/**
 * books.json 的串行化持久层（纯 JVM，可单测）。
 *
 * P1c U1 修复点：
 * 1. 所有写路径共用同一把 [Mutex]，锁内基于 [committed]（最新已提交列表）合并，
 *    不再使用 suspend 进入 IO 前的快照，并发 adopt 不会互相覆盖；
 * 2. 每次写用唯一临时名 books.json.<uuid>.tmp，fsync 后原子 rename，并发写不共用一个 tmp；
 * 3. 解析失败时先把原文件留成 .bad 备份再重建（旧 .bad 用时间戳保留），日志走注入的 [log]；
 *    备份失败则停止重建并抛错，绝不覆盖唯一一份数据；
 * 4. 写失败不提交内存（[committed]/[onCommitted]），避免内存与磁盘不一致；
 * 5. 写 API 自带「先读」保护：首次 registerFile/remove 前在锁内读入磁盘索引（[loadedOnce]），
 *    即使调用方没先 load，也不会把已有的书覆盖成「空索引 + 新书」；
 * 6. 索引损坏/丢失时先尝试从历史 .bad 备份恢复（读失败类损坏可能原内容仍可解析），恢复不了
 *    才重建空索引，配合 LibraryStore 的冷启文件扫描把 _pb_files 里的书补回来；
 * 7. onCommitted 数据锁外串行回调：落盘/内存提交完成后才调用，回调里同步写 API 不再死锁。
 */
internal class LibraryIndexStore(
    private val dir: File,
    private val codec: LibraryIndexCodec = JsonLibraryIndexCodec(),
    private val log: (message: String, throwable: Throwable?) -> Unit = { _, _ -> },
) {
    private val mutex = Mutex()

    /** 回调串行化锁：只保护 [onCommitted]，与数据锁分开；回调里同步写 API 不会死锁。 */
    private val callbackLock = Any()

    /** 是否已在锁内读入过磁盘索引；写 API 据此做「先读后写」保护。只在锁内读写。 */
    private var loadedOnce = false

    @Volatile
    private var committed: List<Book> = emptyList()

    /** 已提交版本号：每次 commitLocked +1；数据锁外据此丢弃「已被更新提交覆盖」的旧回调。 */
    @Volatile
    private var committedVersion = 0L

    /** 最新已提交列表（与最后一次成功落盘的内容一致）。 */
    val books: List<Book>
        get() = committed

    /** 每次提交成功后回调（数据锁外，已串行化），供 UI 内存态与落盘顺序对齐。 */
    @Volatile
    var onCommitted: ((List<Book>) -> Unit)? = null

    /** 仅供单测：回调执行期间数据锁是否仍被持有（回归「锁内回调」）。 */
    internal val isDataLockHeldForTest: Boolean
        get() = mutex.isLocked

    fun indexFile(): File = File(dir, INDEX_FILE_NAME)

    /** 一次提交（或幂等命中）的结果：value 给调用方，books/version 用于数据锁外回调。 */
    private data class Committed<T>(val value: T, val books: List<Book>, val version: Long)

    /**
     * 读索引并交给 [merge] 整理（启动时的 fixtures 合并由调用方提供）；
     * 索引损坏或整理结果与磁盘不一致时回写。返回最终列表。
     * 回调在数据锁释放后执行，拿到的快照若已被更新提交覆盖会被丢弃。
     */
    suspend fun load(merge: (persisted: List<Book>) -> List<Book>): List<Book> {
        val outcome = mutex.withLock {
            cleanupStaleTmpLocked()
            val read = readIndexLocked()
            throwIfQuarantineFailedLocked(read)
            val merged = merge(read.books)
            if (read.corrupted || merged != read.books) {
                // 回写失败直接抛（与写路径一致）：不提交内存，由调用方提示错误并允许重试；
                // 否则会出现「内存有书、磁盘还是旧/损坏内容」的不一致。
                writeIndexLocked(merged)
            }
            val version = commitLocked(merged)
            loadedOnce = true
            Committed(merged, merged, version)
        }
        dispatchCommitted(outcome.books, outcome.version)
        return outcome.value
    }

    /** 登记一个已落盘文件（下载完成 / 导入）：同路径幂等，id 在锁内分配。 */
    suspend fun registerFile(
        file: File,
        title: String?,
        author: String?,
        tags: List<String>,
    ): Result<Book> {
        if (!file.isFile) return Result.failure(IllegalArgumentException("文件不存在或不可读"))
        return try {
            val outcome = mutex.withLock {
                ensureLoadedLocked()
                val targetPath = pathKey(file.absolutePath)
                committed.firstOrNull { pathKey(it.filePath) == targetPath }?.let {
                    return@withLock Committed(it, committed, committedVersion)
                }
                val book = Book(
                    id = freshBookId(committed.mapTo(HashSet(committed.size)) { it.id }),
                    title = title?.takeIf { it.isNotBlank() }
                        ?: file.nameWithoutExtension.takeIf { it.isNotBlank() }
                        ?: "未命名",
                    author = author?.takeIf { it.isNotBlank() },
                    format = detectBookFormat(file),
                    filePath = file.absolutePath,
                    fileSize = file.length(),
                    tags = tags,
                )
                val next = committed + book
                writeIndexLocked(next)
                val version = commitLocked(next)
                Committed(book, next, version)
            }
            dispatchCommitted(outcome.books, outcome.version)
            Result.success(outcome.value)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }
    }

    /** 按 id 删除并回写索引，返回真正被移除的条目（供调用方删文件）。 */
    suspend fun remove(ids: Set<Long>): List<Book> {
        if (ids.isEmpty()) return emptyList()
        val outcome = mutex.withLock {
            ensureLoadedLocked()
            val removed = committed.filter { it.id in ids }
            if (removed.isEmpty()) {
                return@withLock Committed(emptyList<Book>(), committed, committedVersion)
            }
            val next = committed.filterNot { it.id in ids }
            writeIndexLocked(next) // 写失败直接抛给调用方：内存/磁盘都不动。
            val version = commitLocked(next)
            Committed(removed, next, version)
        }
        dispatchCommitted(outcome.books, outcome.version)
        return outcome.value
    }

    /**
     * 冷启扫描：把 _pb_files 里「文件头像 EPUB/PDF 但索引中没有」的文件批量补登记。
     * 只在锁内基于最新已提交列表去重（路径幂等），一次写盘而不是每本写一次。
     */
    suspend fun recoverFiles(
        candidates: List<File>,
        tags: List<String> = emptyList(),
    ): LibraryRecoveryResult {
        val outcome = mutex.withLock {
            ensureLoadedLocked()
            if (candidates.isEmpty()) {
                return@withLock Committed(LibraryRecoveryResult(emptyList(), committed), committed, committedVersion)
            }
            val knownPaths = committed.mapTo(HashSet(committed.size)) { pathKey(it.filePath) }
            val usedIds = committed.mapTo(HashSet(committed.size)) { it.id }
            val adopted = ArrayList<Book>(candidates.size)
            candidates.forEach { file ->
                // 双重过滤：调用方（LibraryStore 冷启扫描）已经筛过一遍，这里再兜一层——
                // recoverFiles 本身也不接受目录、.tmp 半成品和非 epub/pdf 文件。
                if (!isRecoverableLibraryFile(file)) return@forEach
                if (!knownPaths.add(pathKey(file.absolutePath))) return@forEach
                val format = sniffBookFormat(file) ?: return@forEach
                adopted += Book(
                    id = freshBookId(usedIds),
                    title = recoveredBookTitle(file),
                    author = null,
                    format = format,
                    filePath = file.absolutePath,
                    fileSize = file.length(),
                    addedAt = isoUtcTimestamp(file.lastModified()),
                    tags = tags,
                )
            }
            if (adopted.isEmpty()) {
                return@withLock Committed(LibraryRecoveryResult(emptyList(), committed), committed, committedVersion)
            }
            val next = committed + adopted
            writeIndexLocked(next)
            val version = commitLocked(next)
            Committed(LibraryRecoveryResult(adopted, next), next, version)
        }
        dispatchCommitted(outcome.books, outcome.version)
        return outcome.value
    }

    /**
     * 写 API 的「先读」保护：还没 load 时先在锁内读入磁盘索引。
     * 否则 committed 初始为空，第一次 registerFile/remove 会把 books.json 重写成
     * 「空 + 新书」，已导入的其它书从索引消失（文件成孤儿）。
     * 这里只同步磁盘上的原始索引；fixtures 合并仍由 [load] 的 merge 全量负责。
     */
    private fun ensureLoadedLocked() {
        if (loadedOnce) return
        cleanupStaleTmpLocked()
        val read = readIndexLocked()
        throwIfQuarantineFailedLocked(read)
        commitLocked(read.books)
        loadedOnce = true
    }

    /** 损坏索引连备份都没留下时，宁可报错也绝不回写覆盖唯一一份原文件。 */
    private fun throwIfQuarantineFailedLocked(read: ReadResult) {
        if (read.quarantineFailed) {
            throw IOException("索引损坏且无法备份原文件，已停止重建（原文件未覆盖）")
        }
    }

    /**
     * 提交内存态（锁内调用，只做内存赋值 + 版本号，不做任何外部回调）。
     * 返回版本号，调用方出锁后用 [dispatchCommitted] 通知 UI。
     */
    private fun commitLocked(books: List<Book>): Long {
        committed = books
        val version = committedVersion + 1L
        committedVersion = version
        return version
    }

    /**
     * 数据锁外回调 UI：此时落盘与内存提交都已完成。并发提交时只把最新版本交给回调，
     * 已被更新提交覆盖的旧版本直接丢弃（否则 UI 可能被旧快照覆盖）；[callbackLock] 串行化
     * 回调执行，保证最终状态一定是最后提交的列表。回调异常吞掉并记日志，不能让写 API 误判失败。
     * onCommitted 约定：不得同步重入本索引的写 API（会等待 callbackLock 而死锁）。
     */
    private fun dispatchCommitted(books: List<Book>, version: Long) {
        synchronized(callbackLock) {
            if (version != committedVersion) return
            try {
                onCommitted?.invoke(books)
            } catch (throwable: Throwable) {
                log("onCommitted callback failed (ignored after commit)", throwable)
            }
        }
    }

    // ------------------------------------------------------------ 文件读写（锁内调用）

    private data class ReadResult(
        val books: List<Book>,
        val corrupted: Boolean,
        /** 损坏但连 .bad 备份都没能落下：调用方必须停止重建，避免覆盖原文件。 */
        val quarantineFailed: Boolean = false,
    )

    private fun readIndexLocked(): ReadResult {
        val file = indexFile()
        if (!file.exists()) {
            // 上次损坏隔离后没来得及重建（或重建写盘失败）：先试历史 .bad 备份自愈。
            val recovered = recoverFromBadLocked()
            return if (recovered == null) {
                ReadResult(emptyList(), corrupted = false)
            } else {
                log("index missing, recovered from .bad entries=" + recovered.size, null)
                ReadResult(recovered, corrupted = true)
            }
        }
        val text = try {
            file.readText()
        } catch (throwable: Throwable) {
            // 读失败也按损坏处理：绝不把「读不出来」的索引直接当成空索引覆盖掉（用户的书不能丢）。
            log("read index failed path=" + file.absolutePath + ", keep .bad and rebuild", throwable)
            return corruptedReadResultLocked(quarantineCorruptLocked(file))
        }
        return try {
            ReadResult(codec.decode(text), corrupted = false)
        } catch (throwable: Throwable) {
            log("index parse failed path=" + file.absolutePath + ", keep .bad and rebuild", throwable)
            corruptedReadResultLocked(quarantineCorruptLocked(file))
        }
    }

    /**
     * 损坏索引的收尾：隔离失败必须停止重建（原文件还在，不能覆盖唯一一份数据）；
     * 隔离成功后先尝试从历史 .bad 备份恢复条目（读失败类损坏的原内容可能仍可解析），
     * 恢复不了才交给空索引重建 + LibraryStore 冷启文件扫描兜底。
     */
    private fun corruptedReadResultLocked(quarantined: Boolean): ReadResult {
        if (!quarantined) {
            return ReadResult(emptyList(), corrupted = true, quarantineFailed = true)
        }
        val recovered = recoverFromBadLocked()
        if (recovered != null) {
            log("index recovered from .bad backup entries=" + recovered.size, null)
            return ReadResult(recovered, corrupted = true)
        }
        return ReadResult(emptyList(), corrupted = true)
    }

    /**
     * 尝试从 books.json*.bad 备份恢复：按修改时间从新到旧找第一个「可解析且非空」的备份。
     * 只读，不删除/移动任何 .bad（保留损坏现场）；全都解析不了返回 null。
     */
    private fun recoverFromBadLocked(): List<Book>? {
        val backups = dir.listFiles().orEmpty()
            .filter {
                it.isFile && it.name.startsWith(INDEX_FILE_NAME + ".") && it.name.endsWith(BAD_SUFFIX)
            }
            .sortedByDescending { it.lastModified() }
        for (backup in backups) {
            val decoded = runCatching { codec.decode(backup.readText()) }.getOrNull()
            if (!decoded.isNullOrEmpty()) return decoded
        }
        return null
    }

    /**
     * 损坏索引留 .bad 备份后再重建，返回是否已安全备份。
     * - 旧 .bad 不再删除，默认名被占用时改用带时间戳的新备份名，保留全部损坏现场；
     * - 改名失败才退回复制，且只有备份长度与原文件一致后才删原文件；
     * - 返回 false 表示备份没成功，调用方据此停止重建（原文件保持不动）。
     */
    private fun quarantineCorruptLocked(file: File): Boolean {
        val backup = nextBadFileLocked()
        if (runCatching { file.renameTo(backup) }.getOrDefault(false)) return true
        return runCatching {
            file.copyTo(backup, overwrite = false)
            if (backup.length() == file.length()) {
                file.delete()
                true
            } else {
                runCatching { backup.delete() }
                false
            }
        }.onFailure {
            runCatching { backup.delete() }
            log("quarantine corrupt index failed path=" + file.absolutePath, it)
        }.getOrDefault(false)
    }

    /** 旧 .bad 保留：默认名被占用时用时间戳（同一毫秒再加序号）。 */
    private fun nextBadFileLocked(): File {
        val plain = File(dir, INDEX_FILE_NAME + ".bad")
        if (!plain.exists()) return plain
        val stamp = System.currentTimeMillis().toString()
        var candidate = File(dir, INDEX_FILE_NAME + "." + stamp + ".bad")
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(dir, INDEX_FILE_NAME + "." + stamp + "-" + suffix + ".bad")
            suffix += 1
        }
        return candidate
    }

    private fun writeIndexLocked(books: List<Book>) {
        dir.mkdirs()
        val target = indexFile()
        val tmp = File(dir, INDEX_FILE_NAME + "." + UUID.randomUUID() + TMP_SUFFIX)
        try {
            val payload = codec.encode(books)
            FileOutputStream(tmp).use { output ->
                output.write(payload.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            // 唯一临时名 + rename：同目录 rename 在 Android/Linux 上原子，读者只会看到旧或新的完整文件。
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) {
                    // 个别文件系统不支持 rename 覆盖：最后退回直写（此时已无并发写者，锁内串行）。
                    target.writeText(payload)
                    tmp.delete()
                }
            }
        } catch (throwable: Throwable) {
            runCatching { tmp.delete() }
            throw throwable
        }
    }

    /** 上次进程被杀留下的唯一临时文件：加载时（锁内）清掉，避免越积越多。 */
    private fun cleanupStaleTmpLocked() {
        dir.listFiles()?.forEach { file ->
            if (file.name.startsWith(INDEX_FILE_NAME + ".") && file.name.endsWith(TMP_SUFFIX)) {
                runCatching { file.delete() }
            }
        }
    }
}

// -------------------------------------------------------------------- 纯函数工具

internal fun pathKey(path: String): String = runCatching { File(path).absolutePath }.getOrDefault(path)

/** 时间戳 id + 冲突时 +1（比 maxOfOrNull+1 更抗「fixture 重新出现」导致的重复）。 */
internal fun freshBookId(used: MutableSet<Long>): Long {
    var candidate = System.currentTimeMillis()
    while (!used.add(candidate)) candidate += 1L
    return candidate
}

/** 下载文件的格式：先看扩展名，再看文件头（%PDF / PK）。 */
internal fun detectBookFormat(file: File): BookFormat {
    when (file.extension.lowercase()) {
        "pdf" -> return BookFormat.Pdf
        "epub" -> return BookFormat.Epub
    }
    val header = runCatching {
        file.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
    }.getOrNull()
    return if (header != null && header.size >= 4 &&
        header[0] == 0x25.toByte() && header[1] == 0x50.toByte() &&
        header[2] == 0x44.toByte() && header[3] == 0x46.toByte()
    ) {
        BookFormat.Pdf
    } else {
        BookFormat.Epub
    }
}

private const val TAG = "PBLibrary"
private const val PERF_TAG = "PBPERF"
private const val INDEX_FILE_NAME = "books.json"
private const val TMP_SUFFIX = ".tmp"
private const val INDEX_VERSION = 1
private const val LIBRARY_DIR_NAME = "_pb_files"
private const val DOWNLOAD_TAG = "zlib-download"
private const val RECOVERED_TAG = "recovered"
private const val BAD_SUFFIX = ".bad"
