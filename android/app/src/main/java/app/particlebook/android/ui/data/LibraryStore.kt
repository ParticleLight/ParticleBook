package app.particlebook.android.ui.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.particlebook.android.Assets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 书架数据源：assets/fixtures.json + filesDir/_pb_files 下的样书。
 * 当前没有数据库，导入的书籍只存在于本进程内存（进程重启后回到 fixtures）。
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
        try {
            books = withContext(Dispatchers.IO) { loadFromDisk() }
        } catch (throwable: Throwable) {
            error = throwable.message ?: "书架加载失败"
        } finally {
            loading = false
            loadedOnce = true
        }
    }

    /** 通过 SAF 导入一本本地文件；成功后就地加入书架（仅本会话）。 */
    suspend fun importDocument(uri: Uri): Result<Book> {
        val nextId = (books.maxOfOrNull { it.id } ?: 0L) + 1L
        val result = withContext(Dispatchers.IO) { runCatching { copyToLibrary(uri, nextId) } }
        result.onSuccess { book -> books = books + book }
        return result
    }

    /**
     * 删除选中的书：从书架上移除并尽量删掉 _pb_files 里的文件。
     * fixtures.json 是静态的，重启进程后内置样书会重新出现（P0 已知限制）。
     */
    suspend fun removeBooks(ids: Set<Long>) {
        if (ids.isEmpty()) return
        val removed = books.filter { it.id in ids }
        books = books.filterNot { it.id in ids }
        withContext(Dispatchers.IO) {
            removed.forEach { book ->
                val file = File(book.filePath)
                if (file.parentFile?.name == "_pb_files") {
                    runCatching { file.delete() }
                }
            }
        }
    }

    private fun libraryDir(): File = File(context.filesDir, "_pb_files").apply { mkdirs() }

    private fun loadFromDisk(): List<Book> {
        val dir = libraryDir()
        Assets.seed(context, dir)
        val parsed = parseBooks(Assets.readText(context, "fixtures.json"))
        return parsed.map { book ->
            val declared = File(book.filePath)
            val local = File(dir, declared.name)
            if (local.exists()) book.copy(filePath = local.absolutePath) else book
        }
    }

    private fun copyToLibrary(uri: Uri, id: Long): Book {
        val dir = libraryDir()
        val displayName = queryDisplayName(uri) ?: "未命名"
        val extension = displayName.substringAfterLast('.', "").lowercase()
        val format = when (extension) {
            "pdf" -> BookFormat.Pdf
            "epub" -> BookFormat.Epub
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
}
