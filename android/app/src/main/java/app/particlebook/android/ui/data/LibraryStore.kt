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

    /** 删除选中的书：串行重写索引（成功后再删 _pb_files 里的文件）。 */
    suspend fun removeBooks(ids: Set<Long>) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) {
            try {
                // 写失败会抛异常：内存与索引都不变，避免「UI 删了但文件索引还在」或反过来的半状态。
                val removed = indexStore.remove(ids)
                removed.forEach { book ->
                    val file = File(book.filePath)
                    if (file.parentFile?.name == LIBRARY_DIR_NAME) {
                        runCatching { file.delete() }
                    }
                }
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                Log.e(TAG, "remove books failed ids=" + ids, throwable)
            }
        }
    }

    private fun libraryDir(): File = File(context.filesDir, LIBRARY_DIR_NAME).apply { mkdirs() }

    private suspend fun loadFromDisk(): List<Book> {
        val dir = libraryDir()
        Assets.seed(context, dir)
        val fixtures = parseBooks(Assets.readText(context, "fixtures.json"))
            .map { resolveLocal(it, dir) }
        return indexStore.load { persisted -> mergePersisted(fixtures, persisted, dir) }
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
        val safeExtension = extension.ifEmpty { if (format == BookFormat.Pdf) "pdf" else "epub" }
        val destination = File(
            dir,
            prefix + "-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().take(8) +
                "." + safeExtension,
        )
        val input = context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
        input.use { source ->
            destination.outputStream().use { sink -> source.copyTo(sink) }
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
 * 3. 解析失败时先把原文件留成 books.json.bad 再重建，日志走注入的 [log]；
 * 4. 写失败不提交内存（[committed]/[onCommitted]），避免内存与磁盘不一致。
 */
internal class LibraryIndexStore(
    private val dir: File,
    private val codec: LibraryIndexCodec = JsonLibraryIndexCodec(),
    private val log: (message: String, throwable: Throwable?) -> Unit = { _, _ -> },
) {
    private val mutex = Mutex()

    @Volatile
    private var committed: List<Book> = emptyList()

    /** 最新已提交列表（与最后一次成功落盘的内容一致）。 */
    val books: List<Book>
        get() = committed

    /** 每次提交成功后、持锁期间回调，供 UI 内存态与落盘顺序对齐。 */
    var onCommitted: ((List<Book>) -> Unit)? = null

    fun indexFile(): File = File(dir, INDEX_FILE_NAME)

    /**
     * 读索引并交给 [merge] 整理（启动时的 fixtures 合并由调用方提供）；
     * 索引损坏或整理结果与磁盘不一致时回写。返回最终列表。
     */
    suspend fun load(merge: (persisted: List<Book>) -> List<Book>): List<Book> = mutex.withLock {
        cleanupStaleTmpLocked()
        val read = readIndexLocked()
        val merged = merge(read.books)
        if (read.corrupted || merged != read.books) {
            runCatching { writeIndexLocked(merged) }
                .onFailure { log("rewrite index on load failed", it) }
        }
        commitLocked(merged)
        merged
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
            val book = mutex.withLock {
                val targetPath = pathKey(file.absolutePath)
                committed.firstOrNull { pathKey(it.filePath) == targetPath }?.let { return@withLock it }
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
                commitLocked(next)
                book
            }
            Result.success(book)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }
    }

    /** 按 id 删除并回写索引，返回真正被移除的条目（供调用方删文件）。 */
    suspend fun remove(ids: Set<Long>): List<Book> = mutex.withLock {
        if (ids.isEmpty()) return@withLock emptyList()
        val removed = committed.filter { it.id in ids }
        if (removed.isEmpty()) return@withLock emptyList()
        val next = committed.filterNot { it.id in ids }
        writeIndexLocked(next) // 写失败直接抛给调用方：内存/磁盘都不动。
        commitLocked(next)
        removed
    }

    private fun commitLocked(books: List<Book>) {
        committed = books
        onCommitted?.invoke(books)
    }

    // ------------------------------------------------------------ 文件读写（锁内调用）

    private data class ReadResult(val books: List<Book>, val corrupted: Boolean)

    private fun readIndexLocked(): ReadResult {
        val file = indexFile()
        if (!file.exists()) return ReadResult(emptyList(), corrupted = false)
        val text = try {
            file.readText()
        } catch (throwable: Throwable) {
            // 读失败也按损坏处理：绝不把「读不出来」的索引直接当成空索引覆盖掉（用户的书不能丢）。
            log("read index failed path=" + file.absolutePath + ", keep .bad and rebuild", throwable)
            quarantineCorruptLocked(file)
            return ReadResult(emptyList(), corrupted = true)
        }
        return try {
            ReadResult(codec.decode(text), corrupted = false)
        } catch (throwable: Throwable) {
            log("index parse failed path=" + file.absolutePath + ", keep .bad and rebuild", throwable)
            quarantineCorruptLocked(file)
            ReadResult(emptyList(), corrupted = true)
        }
    }

    /** 损坏索引留 .bad 备份后再重建；改名失败时退回复制，绝不静默覆盖用户原文件。 */
    private fun quarantineCorruptLocked(file: File) {
        val backup = File(dir, INDEX_FILE_NAME + ".bad")
        runCatching {
            backup.delete()
            if (!file.renameTo(backup)) {
                file.copyTo(backup, overwrite = true)
                file.delete()
            }
        }.onFailure { log("quarantine corrupt index failed path=" + file.absolutePath, it) }
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
