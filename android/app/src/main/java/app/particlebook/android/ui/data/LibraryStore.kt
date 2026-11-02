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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 书架数据源：assets/fixtures.json（内置样书）+ filesDir/books.json（用户导入的持久化索引）。
 * - 导入：复制文件到 filesDir/_pb_files → 原子写索引（books.json.tmp → books.json）→ 更新内存。
 * - 启动：Assets.seed + fixtures 解析 + books.json 合并，按 filePath 去重；索引里文件已不存在的条目跳过并清理。
 * - 删除：删文件 + 重写索引。
 */
class LibraryStore(private val context: Context) {

    var books by mutableStateOf<List<Book>>(emptyList())
        private set

    var loading by mutableStateOf(true)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    private var loadedOnce = false

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
            books = loaded
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
        // id 用时间戳（并避开当前书架里已有 id）：内置 fixtures 的 id 是小整数，
        // 持久化后 maxOfOrNull+1 会与「删掉高 id 的 fixture 后重新出现」撞车。
        val usedIds = books.mapTo(HashSet()) { it.id }
        val nextId = freshId(usedIds)
        val current = books
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val book = copyToLibrary(uri, nextId)
                try {
                    writeIndex(current + book)
                } catch (throwable: Throwable) {
                    // 索引写失败：回滚刚复制的文件，避免留下无主文件。
                    runCatching { File(book.filePath).delete() }
                    throw throwable
                }
                book
            }
        }
        result.onSuccess { book ->
            books = books.filterNot { it.id == book.id } + book
            Log.i(
                PERF_TAG,
                "PBPERF|phase=library_import|ms=" + (SystemClock.elapsedRealtime() - began) +
                    "|extra=books:" + books.size,
            )
        }.onFailure { throwable ->
            Log.e(TAG, "import failed uri=" + uri, throwable)
        }
        return result
    }

    /** 删除选中的书：从书架移除、删掉 _pb_files 里的文件、重写索引。 */
    suspend fun removeBooks(ids: Set<Long>) {
        if (ids.isEmpty()) return
        val removed = books.filter { it.id in ids }
        books = books.filterNot { it.id in ids }
        val remaining = books
        withContext(Dispatchers.IO) {
            removed.forEach { book ->
                val file = File(book.filePath)
                if (file.parentFile?.name == "_pb_files") {
                    runCatching { file.delete() }
                }
            }
            runCatching { writeIndex(remaining) }
                .onFailure { Log.e(TAG, "write index after delete failed", it) }
        }
    }

    private fun libraryDir(): File = File(context.filesDir, "_pb_files").apply { mkdirs() }

    private fun indexFile(): File = File(context.filesDir, INDEX_NAME)

    private fun loadFromDisk(): List<Book> {
        val dir = libraryDir()
        Assets.seed(context, dir)
        val fixtures = parseBooks(Assets.readText(context, "fixtures.json"))
            .map { resolveLocal(it, dir) }
        val persisted = readIndex()

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
                // 文件已不存在：跳过，并在下面重写索引时清掉这条。
                Log.w(TAG, "skip missing book id=" + book.id + " path=" + book.filePath)
                return@forEach
            }
            if (!seenPaths.add(pathKey(book.filePath))) return@forEach
            val idFixed = if (seenIds.add(book.id)) book else book.copy(id = freshId(seenIds))
            merged += idFixed
        }

        // 索引缺失 / 有清理或 id 修正时重写，保证下次启动一致。
        if (merged != persisted) {
            runCatching { writeIndex(merged) }
                .onFailure { Log.e(TAG, "rewrite index on load failed", it) }
        }
        return merged
    }

    /** fixtures 里的 file_path 是设备绝对路径，换成 _pb_files 下的本地文件（存在才换）。 */
    private fun resolveLocal(book: Book, dir: File): Book {
        val declared = File(book.filePath)
        val local = File(dir, declared.name)
        return if (local.exists()) book.copy(filePath = local.absolutePath) else book
    }

    private fun pathKey(path: String): String = runCatching { File(path).absolutePath }.getOrDefault(path)

    /** 时间戳 id + 冲突时 +1（比 maxOfOrNull+1 更抗「fixture 重新出现」导致的重复）。 */
    private fun freshId(used: MutableSet<Long>): Long {
        var candidate = System.currentTimeMillis()
        while (!used.add(candidate)) candidate += 1L
        return candidate
    }

    // ---------------------------------------------------------------- 索引读写

    private fun readIndex(): List<Book> {
        val file = indexFile()
        if (!file.exists()) return emptyList()
        return runCatching { parseIndex(file.readText()) }
            .onFailure { throwable ->
                Log.e(TAG, "index parse failed path=" + file.absolutePath, throwable)
                // 索引损坏：先留一份 .bad 再重新生成，避免把用户导入静默清空。
                runCatching {
                    val backup = File(context.filesDir, INDEX_NAME + ".bad")
                    backup.delete()
                    file.renameTo(backup)
                }
            }
            .getOrDefault(emptyList())
    }

    private fun writeIndex(books: List<Book>) {
        val target = indexFile()
        val tmp = File(context.filesDir, INDEX_TMP_NAME)
        val payload = JSONObject()
            .put("version", INDEX_VERSION)
            .put("books", JSONArray().apply { books.forEach { put(bookToJson(it)) } })
            .toString()
        tmp.writeText(payload)
        // 先 rename（Android 上 rename 覆盖是原子的）；个别文件系统不支持时退回删旧再 rename。
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                target.writeText(payload)
                tmp.delete()
            }
        }
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

    private fun parseIndex(json: String): List<Book> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val array = root.optJSONArray("books") ?: return emptyList()
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

    // ---------------------------------------------------------------- 导入

    private fun copyToLibrary(uri: Uri, id: Long): Book {
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
        val destination = File(dir, "import-" + System.currentTimeMillis() + "." + safeExtension)

        val input = context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
        input.use { source ->
            destination.outputStream().use { sink -> source.copyTo(sink) }
        }

        return Book(
            id = id,
            title = displayName.substringBeforeLast('.', displayName).ifBlank { "未命名" },
            author = null,
            format = format,
            filePath = destination.absolutePath,
            fileSize = destination.length(),
        )
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    private companion object {
        const val TAG = "PBLibrary"
        const val PERF_TAG = "PBPERF"
        const val INDEX_NAME = "books.json"
        const val INDEX_TMP_NAME = "books.json.tmp"
        const val INDEX_VERSION = 1
    }
}
