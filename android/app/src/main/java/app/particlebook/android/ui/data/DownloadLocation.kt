package app.particlebook.android.ui.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.OutputStream

/**
 * P1c：Z-Library 下载目录。
 *
 * - 默认位置：getExternalFilesDir(DOWNLOADS)/Z-Library（失败退 filesDir/downloads/Z-Library），
 *   走普通 File 写入，行为与 P1b 完全一致；
 * - 用户可在设置里通过 ACTION_OPEN_DOCUMENT_TREE 选一个 SAF 文件夹，takePersistableUriPermission 后
 *   持久化到 SharedPreferences，下载改走 DocumentsContract.createDocument + ContentResolver.openOutputStream。
 *
 * 这里只用 framework 的 DocumentsContract（不引 androidx.documentfile，工程依赖块不在 UI 写作用域内），
 * 语义与 DocumentFile.createFile 等价。
 */
object DownloadLocation {

    private const val TAG = "PBZlib"
    private const val PREFS = "pb_download_location"
    private const val KEY_TREE = "saf_tree_uri"
    private const val KEY_LABEL = "saf_tree_label"
    private const val DEFAULT_DIR_NAME = "Z-Library"
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** 默认下载目录（File 语义）。外部存储不可用时退 filesDir/downloads/Z-Library。 */
    fun defaultDir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val external = File(base ?: context.filesDir, DEFAULT_DIR_NAME)
        if (external.exists() || external.mkdirs()) return external
        val fallback = File(File(context.filesDir, "downloads"), DEFAULT_DIR_NAME)
        fallback.mkdirs()
        return fallback
    }

    /** 已持久化的 SAF 目录；未选择时为 null（即使用默认目录）。 */
    fun treeUri(context: Context): Uri? =
        prefs(context).getString(KEY_TREE, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { Uri.parse(raw) }.getOrNull() }

    fun isCustom(context: Context): Boolean = treeUri(context) != null

    /** 设置页展示文案：自定义显示 SAF 相对路径，否则显示默认绝对路径。 */
    fun summary(context: Context): String {
        val uri = treeUri(context)
        if (uri == null) return defaultDir(context).absolutePath
        val stored = prefs(context).getString(KEY_LABEL, null)?.takeIf { it.isNotBlank() }
        return "自定义：" + (stored ?: labelFor(uri))
    }

    /** 保存用户选择的 SAF 目录；返回是否拿到可写的持久化授权。 */
    fun setTree(context: Context, uri: Uri): Boolean {
        val resolver = context.contentResolver
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val persisted = runCatching {
            resolver.takePersistableUriPermission(uri, readWrite)
            true
        }.getOrDefault(false)
        if (!persisted) {
            // 个别 provider 只给只读授权：退一步持久化读权限并照常记录，写入会在下载时给出明确失败提示。
            runCatching {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val canWrite = runCatching {
            val parent = treeDocumentUri(uri) ?: return@runCatching false
            resolver.query(
                parent,
                arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
                null,
                null,
                null,
            )?.use { cursor ->
                cursor.moveToFirst() &&
                    (cursor.getInt(0) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0
            } ?: false
        }.getOrDefault(true)
        if (!canWrite) {
            Log.w(TAG, "selected SAF tree is not writable: " + uri)
            return false
        }
        prefs(context).edit()
            .putString(KEY_TREE, uri.toString())
            .putString(KEY_LABEL, labelFor(uri))
            .apply()
        Log.i(TAG, "download dir -> saf " + uri)
        return persisted
    }

    /** 恢复默认目录（不清除已下载文件，只改后续下载目标）。 */
    fun reset(context: Context) {
        prefs(context).edit().remove(KEY_TREE).remove(KEY_LABEL).apply()
        Log.i(TAG, "download dir -> default")
    }

    /** SAF 目录里新建文件；失败返回 null。调用方应在 Dispatchers.IO。 */
    fun createDocument(context: Context, displayName: String, mimeType: String): Uri? {
        val tree = treeUri(context) ?: return null
        val parent = treeDocumentUri(tree) ?: return null
        return runCatching {
            DocumentsContract.createDocument(
                context.contentResolver,
                parent,
                mimeType,
                displayName,
            )
        }.onFailure { Log.e(TAG, "create saf document failed name=" + displayName, it) }
            .getOrNull()
    }

    /** 以截断写入方式打开 SAF 文档输出流（新建文档场景）。 */
    fun openOutput(context: Context, uri: Uri): OutputStream? =
        runCatching { context.contentResolver.openOutputStream(uri) }
            .onFailure { Log.e(TAG, "open saf output failed " + uri, it) }
            .getOrNull()

    /** 判断下载记录指向的文件/文档是否仍存在。destPath 可能是 File 绝对路径或 content:// URI。 */
    fun exists(context: Context, destPath: String): Boolean {
        if (destPath.isBlank()) return false
        if (!destPath.startsWith("content://")) return File(destPath).isFile
        val uri = runCatching { Uri.parse(destPath) }.getOrNull() ?: return false
        val resolver = context.contentResolver
        val viaQuery = runCatching {
            resolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)
        if (viaQuery) return true
        return runCatching { resolver.openFileDescriptor(uri, "r")?.use { true } }.getOrNull() ?: false
    }

    /** 查询 SAF 文档显示名（provider 可能自动改名，下载完成后以实际名字为准）。 */
    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            (0 until cursor.columnCount)
                .mapNotNull { index -> cursor.getString(index) }
                .firstOrNull { it.isNotBlank() }
        }
    }.getOrNull()

    /** 书籍格式 → MIME（SAF createDocument 用）。 */
    fun mimeFor(format: String?): String = when (format?.trim()?.lowercase()) {
        "pdf" -> "application/pdf"
        "epub" -> "application/epub+zip"
        else -> "application/octet-stream"
    }

    /**
     * 用系统文件管理器打开「下载所在文件夹」。
     * - 自定义目录：ACTION_VIEW 打开 tree uri；
     * - 默认目录：先尝试 ExternalStorageProvider 的 document uri，再退 storage 根目录与部分文件管理器支持的 file:// 目录 intents。
     * 返回 false 表示所有尝试都没有可用 Activity（调用方显示 Snackbar）。
     */
    fun openFolder(context: Context): Boolean {
        val custom = treeUri(context)
        if (custom != null) {
            if (tryStart(
                    context,
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(custom, DocumentsContract.Document.MIME_TYPE_DIR)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
                )
            ) {
                return true
            }
            if (tryStart(
                    context,
                    Intent(Intent.ACTION_VIEW, custom)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
                )
            ) {
                return true
            }
        }

        val dir = defaultDir(context)
        val documentUri = externalDocumentUri(dir)
        if (documentUri != null && tryStart(
                context,
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(documentUri, DocumentsContract.Document.MIME_TYPE_DIR)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        ) {
            return true
        }
        if (documentUri != null && tryStart(
                context,
                Intent(Intent.ACTION_VIEW, documentUri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        ) {
            return true
        }
        // 部分系统文件管理器只认 storage 根。
        val rootUri = DocumentsContract.buildRootUri(EXTERNAL_STORAGE_AUTHORITY, "primary")
        if (tryStart(
                context,
                Intent(Intent.ACTION_VIEW, rootUri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        ) {
            return true
        }
        // 老牌文件管理器支持 file:// + resource/folder；FileUriExposedException 由 startActivity 抛出，这里吞掉。
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.fromFile(dir), "resource/folder")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
            true
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ helpers

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun treeDocumentUri(tree: Uri): Uri? = runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(tree)
        DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
    }.getOrNull()

    private fun labelFor(tree: Uri): String = runCatching {
        DocumentsContract.getTreeDocumentId(tree)
            .substringAfter(':', "")
            .ifBlank { tree.lastPathSegment ?: "所选文件夹" }
    }.getOrDefault(tree.lastPathSegment ?: "所选文件夹")

    /** 把默认目录映射成 ExternalStorageProvider 的 document uri（primary:Android/data/...）。 */
    private fun externalDocumentUri(dir: File): Uri? = runCatching {
        val primary = Environment.getExternalStorageDirectory()
        val dirPath = dir.canonicalPath
        val primaryPath = primary.canonicalPath
        if (!dirPath.startsWith(primaryPath)) return@runCatching null
        val relative = dirPath.removePrefix(primaryPath).trimStart('/')
        if (relative.isBlank()) return@runCatching null
        DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:" + relative)
    }.getOrNull()

    private fun tryStart(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.onFailure { Log.w(TAG, "open folder intent failed: " + intent.action) }
        .getOrDefault(false)
}
