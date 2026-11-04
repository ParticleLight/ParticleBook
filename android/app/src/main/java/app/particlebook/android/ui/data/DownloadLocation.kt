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

    /** 下载半成品后缀：不能在下载文件夹列表里当成可打开的完整书展示。 */
    private val PARTIAL_SUFFIXES = listOf(".part", ".part.meta", ".part.part", ".tmp")

    /** 默认下载目录（File 语义）。外部存储不可用时退 filesDir/downloads/Z-Library。 */
    fun defaultDir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val external = File(base ?: context.filesDir, DEFAULT_DIR_NAME)
        if (external.exists() || external.mkdirs()) return external
        val fallback = File(File(context.filesDir, "downloads"), DEFAULT_DIR_NAME)
        fallback.mkdirs()
        return fallback
    }

    /**
     * D1 A-lite：把目录列表整理成可展示条目：只保留普通文件，过滤隐藏文件与下载半成品，
     * 按修改时间倒序（最新在前），同一时间按文件名升序，保证列表稳定。
     *
     * 纯 JVM 函数（只做 File 元数据读取），单测直接断言排序/过滤；不要在这里访问 Android API。
     */
    internal fun downloadFolderEntries(files: List<File>): List<DownloadFolderEntry> =
        files.asSequence()
            .filter { it.isFile }
            .filterNot { it.name.startsWith(".") }
            .filterNot { file -> PARTIAL_SUFFIXES.any { suffix -> file.name.endsWith(suffix) } }
            .map { file ->
                DownloadFolderEntry(
                    name = file.name,
                    path = file.absolutePath,
                    sizeBytes = file.length().coerceAtLeast(0L),
                    modifiedAt = file.lastModified(),
                )
            }
            .sortedWith(
                compareByDescending<DownloadFolderEntry> { it.modifiedAt }
                    .thenBy { it.name },
            )
            .toList()

    /** D1 A-lite：读取默认下载目录（调用方应在 Dispatchers.IO）。目录读不到时返回空列表。 */
    internal fun listDefaultFolderEntries(context: Context): List<DownloadFolderEntry> =
        runCatching { downloadFolderEntries(defaultDir(context).listFiles()?.toList().orEmpty()) }
            .getOrDefault(emptyList())

    /** 已持久化且授权仍有效的 SAF 目录；未选择/授权失效时为 null（即使用默认目录）。 */
    fun treeUri(context: Context): Uri? {
        val stored = storedTreeUri(context) ?: return null
        if (!hasPersistedWritePermission(context, stored)) {
            Log.w(TAG, "saf tree permission lost, fallback to default: " + stored)
            return null
        }
        return stored
    }

    fun isCustom(context: Context): Boolean = treeUri(context) != null

    /** P2-2：设置页启动校验：持久授权失效时清掉失效配置并回默认目录，返回是否发生了回退。 */
    fun validateStoredTree(context: Context): Boolean {
        val stored = storedTreeUri(context) ?: return false
        if (hasPersistedWritePermission(context, stored)) return false
        releaseTreePermission(context, stored)
        prefs(context).edit().remove(KEY_TREE).remove(KEY_LABEL).apply()
        Log.w(TAG, "saf tree permission lost, reset download dir: " + stored)
        return true
    }

    /** 设置页展示文案：自定义显示 SAF 相对路径，否则显示默认绝对路径。 */
    fun summary(context: Context): String {
        val uri = treeUri(context)
        if (uri == null) return defaultDir(context).absolutePath
        val stored = prefs(context).getString(KEY_LABEL, null)?.takeIf { it.isNotBlank() }
        return "自定义：" + (stored ?: labelFor(uri))
    }

    /**
     * 保存用户选择的 SAF 目录；只有「拿到持久写授权」且「目录确实支持创建文件」才保存并启用。
     * P2-2：之前授权失败也写 prefs——用户看到「无法使用」，但下载实际切到了只读目录；
     * 现在失败一律保持原目录（返回 false），由设置页提示，不再出现 UI 与真实状态不一致。
     */
    fun setTree(context: Context, uri: Uri): Boolean {
        val writable = isTreeWritable(context, uri)
        val persisted = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            true
        }.getOrDefault(false)
        if (!safTreeAcceptable(persisted = persisted, writable = writable)) {
            Log.w(TAG, "reject saf tree persisted=" + persisted + " writable=" + writable + " uri=" + uri)
            return false
        }
        val previous = storedTreeUri(context)
        if (previous != null && previous != uri) releaseTreePermission(context, previous)
        prefs(context).edit()
            .putString(KEY_TREE, uri.toString())
            .putString(KEY_LABEL, labelFor(uri))
            .apply()
        Log.i(TAG, "download dir -> saf " + uri)
        return true
    }

    /** 恢复默认目录（不清除已下载文件，只改后续下载目标）；顺手释放旧 SAF 持久授权。 */
    fun reset(context: Context) {
        storedTreeUri(context)?.let { releaseTreePermission(context, it) }
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
     * 用系统文件管理器打开「下载所在文件夹」（D1）。
     *
     * - SAF 目录：把 tree uri 转成 DocumentsUI 认的 **document uri**
     *   （[DocumentsContract.buildDocumentUriUsingTree] + tree document id），再 ACTION_VIEW；
     *   直接拿 tree uri 当 data 在 Pixel/Android 12 上没有 Activity 接。
     * - 默认目录：仍先尝试 ExternalStorageProvider 的 document uri；
     * - 都失败时回退 ACTION_OPEN_DOCUMENT_TREE（系统文档选择器），带 INITIAL_URI 尽量直达当前目录；
     * - 返回 false 表示确实没有可用入口，调用方用 [openFolderFailureMessage] 显示包含路径的提示。
     */
    fun openFolder(context: Context): Boolean {
        val customTree = treeUri(context)
        val customDocument = customTree?.let { treeDocumentUri(it) }
        if (customDocument != null) {
            if (tryStart(
                    context,
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(customDocument, DocumentsContract.Document.MIME_TYPE_DIR)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                )
            ) {
                return true
            }
            // 个别 DocumentsUI 版本只认 data 不认 type。
            if (tryStart(
                    context,
                    Intent(Intent.ACTION_VIEW, customDocument)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
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

        // D1 回退：直接 ACTION_VIEW 打不开（Android/data 受 scoped storage 限制）时，
        // 打开系统文档选择器；EXTRA_INITIAL_URI 尽量让用户从当前目录附近开始。
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        (customDocument ?: documentUri)?.let { initial ->
            picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
        }
        if (tryStart(context, picker)) return true

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

    /** D1：所有打开入口都不可用时给用户的提示，附带真实下载位置（默认绝对路径 / SAF tree id）。 */
    fun openFolderFailureMessage(context: Context): String =
        folderOpenFailureText(locationForMessage(context))

    /** 下载位置文案：默认目录绝对路径；SAF 目录用 tree document id（如 primary:Books）。 */
    fun locationForMessage(context: Context): String {
        val tree = treeUri(context)
        if (tree != null) {
            return runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: tree.toString()
        }
        return defaultDir(context).absolutePath
    }

    // ------------------------------------------------------------------ helpers

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** prefs 里保存的原始 tree（不校验授权）；[treeUri] 才是「当前真正可用」的目录。 */
    private fun storedTreeUri(context: Context): Uri? =
        prefs(context).getString(KEY_TREE, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { Uri.parse(raw) }.getOrNull() }

    /** 持久授权是否仍在（P2-2：下载要写文件，读+写都必须在）。 */
    private fun hasPersistedWritePermission(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }

    /** 释放 tree 的持久授权；provider 可能只认读写组合或只读组合，两种都试且吞异常。 */
    private fun releaseTreePermission(context: Context, uri: Uri) {
        val modes = listOf(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        modes.forEach { mode ->
            runCatching { context.contentResolver.releasePersistableUriPermission(uri, mode) }
        }
    }

    /** 目录 flag 是否真的支持创建文件；查询失败按不可写处理（不能默认 true 放行）。 */
    private fun isTreeWritable(context: Context, uri: Uri): Boolean = runCatching {
        val parent = treeDocumentUri(uri) ?: return@runCatching false
        context.contentResolver.query(
            parent,
            arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
            null,
            null,
            null,
        )?.use { cursor ->
            cursor.moveToFirst() &&
                (cursor.getInt(0) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0
        } ?: false
    }.getOrDefault(false)

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

/**
 * D1 A-lite：默认下载目录里的一个可展示文件（不含 .part / .part.meta / .tmp 等半成品）。
 * 默认目录是应用专属目录（Android/data/...），系统文件管理器在 Android 11+ 无法直达，
 * 因此由应用内列表展示；SAF 自定义目录仍走系统文件管理器。
 */
internal data class DownloadFolderEntry(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

/** P2-2：只有持久写授权 + 目录可创建文件才允许保存并启用 SAF 下载目录（纯函数，单测用）。 */
internal fun safTreeAcceptable(persisted: Boolean, writable: Boolean): Boolean = persisted && writable

/** D1 的失败文案（纯函数，JVM 单测可直接断言）。 */
internal fun folderOpenFailureText(location: String): String =
    "无法打开文件管理器，下载位置：" + location
