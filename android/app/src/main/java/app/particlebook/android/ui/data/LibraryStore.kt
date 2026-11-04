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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** [LibraryStore.importDocument] 的结果：区分「新导入」与「已在书架」的重复导入，供 UI 分文案/跳转。 */
data class ImportOutcome(
    val book: Book,
    /** true：命中已有书架条目（同 SAF URI 或同内容指纹），本次没有新增条目。 */
    val alreadyInLibrary: Boolean,
)

/**
 * 书架数据源：assets/fixtures.json（内置样书）+ filesDir/books.json（用户导入的持久化索引）。
 * - 导入：复制文件到 filesDir/_pb_files → 串行写索引（唯一 tmp → 原子 rename）→ 更新内存。
 * - 启动：Assets.seed + fixtures 解析 + books.json 合并，按 filePath 去重；索引里文件已不存在的条目跳过并清理。
 * - 删除：串行重写索引成功后再删文件。
 * - 并发（P1c U1）：adopt/import/remove/load 的索引写路径全部经 [LibraryIndexStore] 的 Mutex 串行化，
 *   锁内基于**最新已提交列表**合并（不再用进入 IO 前的快照）；写失败不提交内存。
 */
class LibraryStore(
    private val context: Context,
    /**
     * P2-1：下载历史线索（destPath → 书名/作者/zlib tag）。仅在索引缺失/损坏重建时用来回填
     * 默认下载目录里的书；只读 filesDir/zlib_downloads.json，不反向依赖 UI 层的
     * ZlibDownloadManager（便于单测注入空实现）。读不到时返回空列表。
     */
    private val downloadHints: () -> List<LibraryDownloadHint> = {
        readDownloadHistoryHints(File(context.filesDir, DOWNLOAD_HISTORY_FILE_NAME))
    },
) {

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

    /**
     * 通过 SAF 导入一本本地文件：复制文件 → 写索引 → 加入书架。
     *
     * P1 task-70：重复导入同一文件不再生成第二份副本。
     * 1. 先按持久化的 SAF URI tag 认领：同一文档再次导入且文件还在，直接返回已有条目；
     * 2. 复制后按内容指纹 tag 认领（不同 URI 但大小+首尾内容相同），命中则删掉刚复制的副本；
     * 3. 老版本导入的条目还没有指纹 tag，按大小筛一遍现场算指纹兜底；
     * 4. 并发导入的兜底在 registerFile：identity tag 幂等命中已有条目，多复制出来的副本会删掉。
     * 返回 [ImportOutcome]，[ImportOutcome.alreadyInLibrary] 供 UI 提示「已在书架」并跳转。
     */
    suspend fun importDocument(uri: Uri): Result<ImportOutcome> {
        val began = SystemClock.elapsedRealtime()
        val uriTag = safUriTag(uri.toString())
        val result = withContext(Dispatchers.IO) {
            try {
                // ① 同一 SAF URI 再次导入：文件还在就直接认领，不复制。
                indexStore.findByTag(uriTag)?.let { existing ->
                    if (File(existing.filePath).isFile) {
                        Log.i(TAG, "import skipped, already in library by uri id=" + existing.id)
                        return@withContext Result.success(ImportOutcome(existing, alreadyInLibrary = true))
                    }
                    Log.i(TAG, "import uri tag hit but file missing, re-copy and re-point id=" + existing.id)
                }
                val copied = copyUriToLibrary(uri, prefix = "import")
                val fingerprintTag = contentFingerprintTag(copied.file)
                // ② 不同 URI 但内容相同：命中已有条目时删掉刚复制出来的多余副本，并补记 URI tag。
                val duplicate = findDuplicateImport(indexStore.books, uriTag, fingerprintTag)
                if (duplicate != null) {
                    runCatching { copied.file.delete() }
                    mergeImportTags(duplicate.book, listOfNotNull(uriTag, fingerprintTag))
                    Log.i(
                        TAG,
                        "import skipped, already in library by " + duplicate.kind + " id=" + duplicate.book.id,
                    )
                    return@withContext Result.success(
                        ImportOutcome(duplicate.book, alreadyInLibrary = true),
                    )
                }
                // ③ 兼容老版本导入、条目还没有指纹 tag：按大小 + 指纹现场比对一次（最多读 128KiB/本）。
                val legacyDuplicate = if (fingerprintTag != null) {
                    indexStore.books.firstOrNull { book ->
                        book.fileSize == copied.file.length() &&
                            File(book.filePath).isFile &&
                            contentFingerprintTag(File(book.filePath)) == fingerprintTag
                    }
                } else {
                    null
                }
                if (legacyDuplicate != null) {
                    runCatching { copied.file.delete() }
                    mergeImportTags(legacyDuplicate, listOfNotNull(uriTag, fingerprintTag))
                    Log.i(TAG, "import skipped, legacy entry content matched id=" + legacyDuplicate.id)
                    return@withContext Result.success(
                        ImportOutcome(legacyDuplicate, alreadyInLibrary = true),
                    )
                }
                val tags = buildList {
                    add(uriTag)
                    fingerprintTag?.let(::add)
                }
                indexStore.registerFile(
                    file = copied.file,
                    title = copied.displayName.substringBeforeLast('.', copied.displayName),
                    author = null,
                    tags = tags,
                ).map { book ->
                    // 并发导入时可能命中已有条目（identity tag 幂等）：刚复制出来的副本是多余的，删掉。
                    val deduped = pathKey(book.filePath) != pathKey(copied.file.absolutePath)
                    if (deduped) runCatching { copied.file.delete() }
                    ImportOutcome(book, alreadyInLibrary = deduped)
                }.onFailure { runCatching { copied.file.delete() } }
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                Result.failure(throwable)
            }
        }
        result.onSuccess { outcome ->
            Log.i(
                PERF_TAG,
                "PBPERF|phase=library_import|ms=" + (SystemClock.elapsedRealtime() - began) +
                    "|extra=books:" + books.size + "|duplicate:" + outcome.alreadyInLibrary,
            )
            if (outcome.alreadyInLibrary) {
                Log.d(TAG, "import deduped id=" + outcome.book.id + " title=" + outcome.book.title)
            } else {
                Log.d(TAG, "imported book id=" + outcome.book.id + " title=" + outcome.book.title)
            }
        }.onFailure { throwable ->
            Log.e(TAG, "import failed uri=" + uri, throwable)
        }
        return result
    }

    /** 重复导入命中已有条目后补记新的 URI/指纹 tag（best-effort，失败只记日志不影响返回）。 */
    private suspend fun mergeImportTags(book: Book, tags: List<String>) {
        if (tags.isEmpty()) return
        val file = File(book.filePath)
        if (!file.isFile) return
        indexStore.registerFile(file = file, title = book.title, author = book.author, tags = tags)
            .onFailure { Log.w(TAG, "merge import tags failed id=" + book.id, it) }
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
        /**
         * task-79/task-73：可选校验已有书架条目的文件是否完整（例如 zlibInspectBookFile == COMPLETE）。
         * 返回 false 时不会把坏文件当作幂等命中，改为把条目路径修到这次的新文件；默认 null 不改行为。
         */
        existingFileValidator: ((File) -> Boolean)? = null,
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
            indexStore.registerFile(
                file = file,
                title = title,
                author = author,
                tags = tags,
                existingFileValidator = existingFileValidator,
            )
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
        /**
         * task-79/task-73：可选校验已有书架条目的文件是否完整；返回 false 时继续复制并重指路径，
         * 不能把 0 字节/截断的旧副本当作「已在书架」。默认 null 不改行为。
         */
        existingFileValidator: ((File) -> Boolean)? = null,
    ): Result<Book> {
        // P2-3：同一本 zlib 书已经在书架（tag 命中）且文件确实还在时直接返回，不再复制第二份。
        // P1-1 task-63：条目文件已缺失时不能提前返回，否则书架继续指向缺失路径、新副本成孤儿；
        // 继续复制并让 registerFile 在锁内把路径改到新文件。
        extraTag?.takeIf { it.startsWith(ZLIB_TAG_PREFIX) }?.let { tag ->
            indexStore.findByTag(tag)?.let { existing ->
                val existingFile = File(existing.filePath)
                val existingUsable = existingFile.isFile &&
                    (existingFileValidator == null || existingFileValidator(existingFile))
                if (existingUsable) {
                    Log.i(TAG, "adopt saf document skipped, already in library id=" + existing.id)
                    return Result.success(existing)
                }
                Log.i(
                    TAG,
                    "adopt saf document: existing entry unusable (missing/incomplete), re-point id=" + existing.id,
                )
            }
        }
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
                    existingFileValidator = existingFileValidator,
                ).onSuccess { book ->
                    // 并发导入时可能命中已有条目（tag 幂等）：刚复制出来的副本是多余的，删掉防重复占空间。
                    if (pathKey(book.filePath) != pathKey(copied.file.absolutePath)) {
                        runCatching { copied.file.delete() }
                    }
                }.onFailure { throwable ->
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
     *
     * task-79：级联通知 [LibraryRemovalBus] 统一切到主线程（并且不可取消）——下载管理器在回调里
     * 读写 history/pending 队列，必须与它的主线程协程串行；否则「先查 historyLoaded=false 入队、
     * 紧接着初始化读盘完成清空队列」会丢删除事件，完成回调还会把刚删的书/记录复活。
     */
    suspend fun removeBooks(ids: Set<Long>): Result<Int> =
        removeBooksInternal(ids, notifyRemovalBus = true)

    /**
     * task-79：墓碑命中后的静默回收——删除下载管理器在删书竞态窗口里补 adopt 的书架条目
     * （索引/文件/进度/封面/样书记录），但**不**再通知 [LibraryRemovalBus]，避免回调递归。
     * 供下载管理器在「删书后完成回调发现墓碑」时调用；返回真正移除的条数。
     */
    suspend fun removeBooksSilently(ids: Set<Long>): Result<Int> =
        removeBooksInternal(ids, notifyRemovalBus = false)

    private suspend fun removeBooksInternal(ids: Set<Long>, notifyRemovalBus: Boolean): Result<Int> {
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
                // P3-4：删书级联清进度/封面。清理失败只记日志，不能把「书已删除」误报成失败。
                if (removed.isNotEmpty()) {
                    val removedIds = removed.mapTo(ArrayList(removed.size)) { it.id }
                    runCatching { ReadingProgress.clear(context, removedIds) }
                        .onFailure { Log.w(TAG, "clear reading progress failed ids=" + removedIds, it) }
                    runCatching { BookCoverLoader.evict(context, removedIds) }
                        .onFailure { Log.w(TAG, "evict cover cache failed ids=" + removedIds, it) }
                    markDeletedSeeds(removed)
                    if (notifyRemovalBus) {
                        // 下载记录按 filePath/zlib tag 关联清理（RECORD_ONLY）；data 层不反向依赖下载管理器。
                        // NonCancellable：索引已经删了，不能因为调用方协程在这一步被取消而漏发级联事件。
                        withContext(NonCancellable) {
                            withContext(Dispatchers.Main.immediate) {
                                runCatching { LibraryRemovalBus.notifyRemoved(removed) }
                                    .onFailure { Log.w(TAG, "notify removed books failed", it) }
                            }
                        }
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

    /** P3-5：删掉内置样书时记下文件名，之后启动不再复制、fixtures 也不再合并回书架。 */
    private fun markDeletedSeeds(removed: List<Book>) {
        runCatching {
            val seedNames = Assets.seedNames(context)
            if (seedNames.isEmpty()) return
            val deleted = removed.mapNotNull { book ->
                val file = File(book.filePath)
                file.name.takeIf { it in seedNames && file.parentFile?.name == LIBRARY_DIR_NAME }
            }.toSet()
            Assets.markSeedDeleted(context, deleted)
        }.onFailure { Log.w(TAG, "mark deleted seed failed", it) }
    }

    private fun libraryDir(): File = File(context.filesDir, LIBRARY_DIR_NAME).apply { mkdirs() }

    private suspend fun loadFromDisk(): List<Book> {
        val dir = libraryDir()
        Assets.seed(context, dir)
        val deletedSeeds = Assets.deletedSeedNames(context)
        val fixtures = filterDeletedSeedBooks(
            parseBooks(Assets.readText(context, "fixtures.json")),
            deletedSeeds,
        ).map { resolveLocal(it, dir) }
        val loaded = indexStore.load { persisted -> mergePersisted(fixtures, persisted, dir) }
        val recovered = recoverOrphanLibraryFiles(dir, loaded, deletedSeeds)
        // P2-1/P1 task-63：默认下载目录（File 模式）的书不在 _pb_files，冷启文件扫描找不回来，
        // 只能靠下载历史补登记。只有「索引被重建，且下载记录比恢复出的快照更新（或根本没有快照）」
        // 才扫：既补回最近一次提交没进快照的新条目，也不复活用户早先删掉的书。
        val hints = if (indexStore.lastLoadWasRebuilt) readDownloadHintsOrEmpty() else emptyList()
        val newestHint = hints.maxOfOrNull { it.finishedAt } ?: 0L
        val scanDownloads = shouldRecoverDownloadedBooks(
            rebuilt = indexStore.lastLoadWasRebuilt,
            recoveredSnapshotModified = indexStore.lastRecoveredSnapshotModified,
            newestDownloadFinishedAt = newestHint,
        )
        return if (scanDownloads) recoverDownloadedFiles(recovered, hints) else recovered
    }

    private fun readDownloadHintsOrEmpty(): List<LibraryDownloadHint> =
        runCatching { downloadHints() }.getOrElse { throwable ->
            Log.e(TAG, "read download history hints failed", throwable)
            emptyList()
        }

    /**
     * P2-1：索引重建后补登记默认下载目录里的书。
     *
     * 默认目录的书由 [adoptLocalFile] 原地登记，文件不在 _pb_files，冷启扫描 _pb_files 找不回来；
     * 这里按下载历史 destPath 回填 title/author/zlib tag（同一书目的一条记录），
     * 再交给 [LibraryIndexStore.recoverCandidates] 一次写盘批量登记（路径幂等）。
     */
    private suspend fun recoverDownloadedFiles(
        loaded: List<Book>,
        hints: List<LibraryDownloadHint>,
    ): List<Book> {
        val downloadDir = DownloadLocation.defaultDir(context)
        val candidates = buildDownloadRecoveryCandidates(downloadDir, loaded, hints)
        if (candidates.isEmpty()) return loaded
        val recovered = indexStore.recoverCandidates(candidates)
        if (recovered.adopted.isNotEmpty()) {
            Log.w(TAG, "recovered downloaded books count=" + recovered.adopted.size)
        }
        return recovered.all
    }

    /**
     * 冷启扫描（数据层兜底）：books.json 丢失/损坏重建后，_pb_files 里仍留着用户的书籍文件。
     * 把索引里没有的、文件头确实像 EPUB/PDF 的文件补登记回来，避免「索引没了 = 书架清空、
     * 文件白占空间且再也打不开」。已知路径（fixtures/索引内条目）跳过；重复调用幂等。
     */
    private suspend fun recoverOrphanLibraryFiles(
        dir: File,
        loaded: List<Book>,
        deletedSeeds: Set<String>,
    ): List<Book> {
        val knownPaths = loaded.mapTo(HashSet(loaded.size)) { pathKey(it.filePath) }
        val candidates = dir.listFiles().orEmpty().filter { file ->
            file.name !in deletedSeeds &&
                isRecoverableLibraryFile(file) &&
                pathKey(file.absolutePath) !in knownPaths
        }
        if (candidates.isEmpty()) return loaded
        val recovered = indexStore.recoverFiles(candidates, tags = listOf(RECOVERED_TAG))
        if (recovered.adopted.isNotEmpty()) {
            Log.w(TAG, "recovered orphan library files count=" + recovered.adopted.size)
        }
        return recovered.all
    }

    /** fixtures 优先，持久化条目按 filePath 去重；缺失文件保留并打 missing 标记（P2-1）。 */
    private fun mergePersisted(fixtures: List<Book>, persisted: List<Book>, dir: File): List<Book> =
        mergePersistedBooks(fixtures, persisted, dir)

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

/**
 * P3-5：用户删掉的内置样书不再从 fixtures 合并回书架（纯函数，单测用）。
 * 只按文件名匹配：fixtures 里的路径是设备绝对路径，样本文件名本身唯一。
 */
internal fun filterDeletedSeedBooks(fixtures: List<Book>, deletedSeedNames: Set<String>): List<Book> {
    if (deletedSeedNames.isEmpty()) return fixtures
    return fixtures.filterNot { book -> File(book.filePath).name in deletedSeedNames }
}

/**
 * P1 task-63：索引重建后是否要扫默认下载目录补登记（纯函数，单测用）。
 * - 没重建：正常启动不扫，避免复活用户删掉的书；
 * - 重建且没有可解析快照（[recoveredSnapshotModified] <= 0）：索引真的丢了，扫；
 * - 重建且从快照恢复：只有下载记录比快照更新时才扫——最近一次没进快照的登记要补回来，
 *   更早的记录对应快照里已有/已被用户删除，不能重新复活。
 */
internal fun shouldRecoverDownloadedBooks(
    rebuilt: Boolean,
    recoveredSnapshotModified: Long,
    newestDownloadFinishedAt: Long,
): Boolean {
    if (!rebuilt) return false
    if (recoveredSnapshotModified <= 0L) return true
    return newestDownloadFinishedAt > recoveredSnapshotModified
}

/**
 * P3-4：删书成功事件总线。数据层不能反向依赖 UI 层的 ZlibDownloadManager，
 * 由下载管理器在 init 时注册监听，把与已删书关联的下载记录按 RECORD_ONLY 语义移除。
 * 回调在删除的 IO 线程上同步执行，监听方需自行保证线程安全。
 */
internal object LibraryRemovalBus {
    @Volatile
    var listener: ((List<Book>) -> Unit)? = null

    fun notifyRemoved(books: List<Book>) {
        if (books.isEmpty()) return
        listener?.invoke(books)
    }
}

/**
 * P2-1：fixtures + 持久化索引的合并规则（纯函数，可单测）。
 *
 * - fixtures 优先；持久化条目按 filePath 去重、id 冲突时重新分配（沿用原行为）；
 * - **文件已不存在的持久化条目不丢**：保留并打上 [MISSING_TAG]，等文件回来时再清掉标记。
 *   之前直接跳过会让索引回写时永久清掉该条目（默认下载目录的书没有 _pb_files 冷启扫描兜底，
 *   重导又会换新 id → 阅读进度/封面全丢）。
 */
internal fun mergePersistedBooks(fixtures: List<Book>, persisted: List<Book>, dir: File): List<Book> {
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
        val present = File(book.filePath).exists()
        val normalized = when {
            present && MISSING_TAG in book.tags -> book.copy(tags = book.tags - MISSING_TAG)
            !present && MISSING_TAG !in book.tags -> book.copy(tags = book.tags + MISSING_TAG)
            else -> book
        }
        if (!seenPaths.add(pathKey(normalized.filePath))) return@forEach
        val idFixed = if (seenIds.add(normalized.id)) {
            normalized
        } else {
            normalized.copy(id = freshBookId(seenIds))
        }
        merged += idFixed
    }
    return merged
}

/** fixtures 里的 file_path 是设备绝对路径，换成 _pb_files 下的本地文件（存在才换）。 */
internal fun resolveLocal(book: Book, dir: File): Book {
    val declared = File(book.filePath)
    val local = File(dir, declared.name)
    return if (local.exists()) book.copy(filePath = local.absolutePath) else book
}

/** P2-3：书架里按 zlib tag 找已有条目（下载记录重复导入时不再复制/登记第二条）。 */
internal fun findBookByZlibTag(books: List<Book>, tag: String): Book? =
    books.firstOrNull { tag in it.tags }

/** 重复导入识别 tag：zlib 书号 / SAF URI 哈希 / 内容指纹。 */
internal fun isImportIdentityTag(tag: String): Boolean =
    tag.startsWith(ZLIB_TAG_PREFIX) ||
        tag.startsWith(SAF_URI_TAG_PREFIX) ||
        tag.startsWith(CONTENT_FINGERPRINT_TAG_PREFIX)

/**
 * 登记前的幂等命中：先同路径，再按 identity tag。
 * task-70 起 identity tag 覆盖 zlib:<bookId>、saf-uri:<sha256>、fp:<size>-<sha256>：
 * 同一 SAF 文档换路径 / 不同 URI 导入同一内容都能认回已有条目，不再登记第二条。
 */
internal fun findIdempotentBook(books: List<Book>, filePath: String, tags: List<String>): Book? {
    val target = pathKey(filePath)
    books.firstOrNull { pathKey(it.filePath) == target }?.let { return it }
    val identityTags = tags.filterTo(HashSet()) { isImportIdentityTag(it) }
    if (identityTags.isEmpty()) return null
    return books.firstOrNull { book -> book.tags.any { it in identityTags } }
}

/** task-70：一次重复导入命中：URI 优先于内容指纹。 */
internal enum class DuplicateImportKind { SAF_URI, CONTENT }

internal data class DuplicateImport(val book: Book, val kind: DuplicateImportKind)

/**
 * task-70：导入前的重复判定（纯函数，单测用）。
 * - 只认「书架条目指向的文件还在」的条目：文件被删了要允许重新导入并由 registerFile 修路径；
 * - URI 命中优先于内容指纹：同一个 SAF 文档再次导入无需读文件；
 * - [fileExists] 可注入，便于 JVM 单测不依赖真实文件。
 */
internal fun findDuplicateImport(
    books: List<Book>,
    uriTag: String,
    fingerprintTag: String?,
    fileExists: (Book) -> Boolean = { File(it.filePath).isFile },
): DuplicateImport? {
    if (uriTag.isNotBlank()) {
        books.firstOrNull { uriTag in it.tags && fileExists(it) }?.let {
            return DuplicateImport(it, DuplicateImportKind.SAF_URI)
        }
    }
    if (fingerprintTag != null) {
        books.firstOrNull { fingerprintTag in it.tags && fileExists(it) }?.let {
            return DuplicateImport(it, DuplicateImportKind.CONTENT)
        }
    }
    return null
}

/**
 * task-70：SAF 文档身份 tag = "saf-uri:<sha256(uriString)>"。
 * 只收 String（不依赖 android.net.Uri），JVM 单测可直接验证；URI 字符串由系统在授权有效期内保持稳定。
 */
internal fun safUriTag(uriString: String): String =
    SAF_URI_TAG_PREFIX + sha256Hex(uriString.toByteArray(Charsets.UTF_8))

/**
 * task-70：内部副本内容指纹 tag = "fp:<size>-<sha256(size + 内容)>"。
 * <=8MiB 全量哈希（常见书籍，避免首尾相同中间不同被误判）；更大的文件只读首尾 64KiB，
 * 导入时不必再全量读一遍。同内容不同 SAF URI 的重复导入靠它判重。
 */
internal fun contentFingerprintTag(file: File): String? {
    if (!file.isFile) return null
    return runCatching {
        val size = file.length()
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(size.toString().toByteArray(Charsets.UTF_8))
        if (size <= FULL_FINGERPRINT_LIMIT_BYTES) {
            // 常见书籍（<=8MiB）全量算，杜绝「大小+首尾相同但中间不同」的误判。
            file.inputStream().use { input ->
                val buffer = ByteArray(FINGERPRINT_CHUNK_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
        } else {
            // 大文件只读首尾块，导入时不必再全量读一遍。
            file.inputStream().use { input ->
                val head = ByteArray(FINGERPRINT_CHUNK_BYTES)
                val read = input.read(head)
                if (read > 0) digest.update(head, 0, read)
            }
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(size - FINGERPRINT_CHUNK_BYTES)
                val tail = ByteArray(FINGERPRINT_CHUNK_BYTES)
                raf.readFully(tail)
                digest.update(tail)
            }
        }
        CONTENT_FINGERPRINT_TAG_PREFIX + size + "-" + sha256Hex(digest.digest())
    }.getOrNull()
}

internal fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val out = StringBuilder(digest.size * 2)
    for (byte in digest) {
        val value = byte.toInt() and 0xFF
        out.append(HEX_DIGITS[value ushr 4])
        out.append(HEX_DIGITS[value and 0x0F])
    }
    return out.toString()
}

/** 文件回到磁盘后清掉 missing 标记（索引会被回写，避免书架永远显示「文件缺失」）。 */
internal fun clearMissingTag(book: Book): Book =
    if (MISSING_TAG in book.tags) book.copy(tags = book.tags - MISSING_TAG) else book

/** 下载完成时给书架条目打的标记，与下载记录页的 zlibBookTag 保持一致。 */
internal const val ZLIB_TAG_PREFIX = "zlib:"

/** task-70：SAF 文档身份 tag 前缀（值为 safUriTag 生成，见 [safUriTag]）。 */
internal const val SAF_URI_TAG_PREFIX = "saf-uri:"

/** task-70：内容指纹 tag 前缀（值为 contentFingerprintTag 生成，见 [contentFingerprintTag]）。 */
internal const val CONTENT_FINGERPRINT_TAG_PREFIX = "fp:"

private const val FINGERPRINT_CHUNK_BYTES = 64 * 1024

/** 指纹全量哈希的上限：常见 EPUB/PDF 都在此范围内；更大的书才退回首尾块。 */
private const val FULL_FINGERPRINT_LIMIT_BYTES = 8L * 1024 * 1024
private const val HEX_DIGITS = "0123456789abcdef"

/** P2-1：一条下载历史线索（只关心能直接对应的本地 File 记录）。public：作为 LibraryStore 构造参数类型。 */
data class LibraryDownloadHint(
    val destPath: String,
    val title: String?,
    val author: String?,
    val bookId: String,
    val finishedAt: Long = 0L,
)

/**
 * 读 ZlibDownloadManager 持久化的下载历史，只取「COMPLETED 且目标是本地 File」的记录。
 *
 * 只读文件、不依赖 DownloadManager 单例：LibraryStore 在冷启重建索引时需要它，
 * 而 DownloadManager 的 init/history 是异步的，拿不到可靠快照。格式由
 * ZlibDownloadManager.HISTORY_NAME/HISTORY_VERSION 固定（version + downloads[]）。
 */
internal fun readDownloadHistoryHints(filesDir: File): List<LibraryDownloadHint> {
    val file = File(filesDir, DOWNLOAD_HISTORY_FILE_NAME)
    if (!file.isFile) return emptyList()
    return runCatching {
        val array = JSONObject(file.readText()).optJSONArray("downloads") ?: return emptyList()
        val out = ArrayList<LibraryDownloadHint>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val destPath = item.optString("filePath")
            val bookId = item.optString("bookId")
            val status = item.optString("status")
            if (destPath.isBlank() || bookId.isBlank()) continue
            if (destPath.startsWith("content://")) continue // SAF 目标不是本地 File
            if (status.isNotBlank() && status != "COMPLETED") continue
            out += LibraryDownloadHint(
                destPath = destPath,
                title = item.optString("title").takeIf { it.isNotBlank() },
                author = item.optString("author").takeUnless { it.isBlank() || it == "null" },
                bookId = bookId,
                finishedAt = item.optLong("finishedAt", 0L),
            )
        }
        out
    }.getOrElse { emptyList() }
}

/**
 * P2-1：把默认下载目录里「文件头像书籍、但书架里没有」的文件整理成待登记候选，
 * 并用下载历史按 destPath 回填书名/作者/zlib tag/完成时间（纯函数，可单测）。
 */
internal fun buildDownloadRecoveryCandidates(
    downloadDir: File,
    loaded: List<Book>,
    hints: List<LibraryDownloadHint>,
): List<LibraryFileCandidate> {
    if (!downloadDir.isDirectory) return emptyList()
    val knownPaths = loaded.mapTo(HashSet(loaded.size)) { pathKey(it.filePath) }
    val hintsByPath = hints
        .filter { it.destPath.isNotBlank() && !it.destPath.startsWith("content://") }
        .associateBy { pathKey(it.destPath) }
    return downloadDir.listFiles().orEmpty()
        .filter { file -> isRecoverableLibraryFile(file) && pathKey(file.absolutePath) !in knownPaths }
        .sortedBy { it.name }
        .map { file ->
            val hint = hintsByPath[pathKey(file.absolutePath)]
            val tags = buildList {
                add(DOWNLOAD_TAG)
                hint?.bookId?.takeIf { it.isNotBlank() }?.let { add(zlibBookTag(it)) }
            }
            LibraryFileCandidate(
                file = file,
                title = hint?.title,
                author = hint?.author,
                tags = tags,
                addedAt = hint?.finishedAt?.takeIf { it > 0L }?.let(::isoUtcTimestamp),
            )
        }
}

/** 下载记录的 zlib:<bookId> tag（与下载页 UI 的拼法一致）。 */
internal fun zlibBookTag(bookId: String): String = ZLIB_TAG_PREFIX + bookId

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

/**
 * 待补登记的文件候选（P2-1）：冷启扫描 _pb_files / 默认下载目录时携带元数据，
 * 由 [LibraryIndexStore.recoverCandidates] 在锁内一次写盘。
 */
internal data class LibraryFileCandidate(
    val file: File,
    val title: String? = null,
    val author: String? = null,
    val tags: List<String> = emptyList(),
    val addedAt: String? = null,
)

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
 * 6. 索引损坏/丢失时按快照文件 mtime 从新到旧挑第一个可解析的 .bad/.bak1/.bak2（允许空快照），
 *    恢复不了才重建空索引，配合 LibraryStore 的冷启文件扫描把 _pb_files 里的书补回来；
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

    /** 本次 load 的索引是否来自「缺失/损坏后的重建」（供 LibraryStore 决定是否扫下载目录）。 */
    @Volatile
    var lastLoadWasRebuilt: Boolean = false
        private set

    /**
     * P1 task-63：索引被重建，而且连一个可解析的快照都没有（等同于索引丢了）。
     * 从备份恢复出（哪怕为空）快照时以快照为准；是否再扫下载目录由
     * [shouldRecoverDownloadedBooks] 结合「下载记录是否比快照新」决定，避免复活已删书。
     */
    @Volatile
    var lastLoadLostIndex: Boolean = false
        private set

    /** 本次重建实际选中的快照 mtime（0 = 没有快照）；供上层比较下载记录是否比快照更新。 */
    @Volatile
    var lastRecoveredSnapshotModified: Long = 0L
        private set

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
            lastLoadWasRebuilt = read.rebuilt
            // P1 task-63：只有「一个可解析快照都没有」才算索引真丢了；从备份恢复出（可能是空的）
            // 快照时以快照为准，不能因为它是空书架就回头扫下载目录复活已删书。
            lastLoadLostIndex = read.rebuilt && read.snapshotModified == null
            lastRecoveredSnapshotModified = read.snapshotModified ?: 0L
            Committed(merged, merged, version)
        }
        dispatchCommitted(outcome.books, outcome.version)
        return outcome.value
    }

    /**
     * 登记一个已落盘文件（下载完成 / 导入）：同路径或同 identity tag（zlib/SAF URI/内容指纹）幂等，
     * 幂等命中时把本次的新 tag 合并进已有条目（重复导入不同 URI 时补记新 URI，下次直接认领）。
     * id 在锁内分配。
     */
    suspend fun registerFile(
        file: File,
        title: String?,
        author: String?,
        tags: List<String>,
        /**
         * task-79/task-73：可选校验 idempotent 命中的已有条目文件是否仍可用。
         * 返回 false 时按「文件缺失」处理：把条目路径/大小修到本次落盘的文件，而不是继续指向坏文件。
         * 默认 null 保持原行为。
         */
        existingFileValidator: ((File) -> Boolean)? = null,
    ): Result<Book> {
        if (!file.isFile) return Result.failure(IllegalArgumentException("文件不存在或不可读"))
        return try {
            val outcome = mutex.withLock {
                ensureLoadedLocked()
                // 幂等命中：同路径优先；否则按 identity tag（zlib:<bookId> / SAF URI / 内容指纹）。
                val existing = findIdempotentBook(committed, file.absolutePath, tags)
                if (existing != null) {
                    // P1-1 task-63：tag 命中但书架条目指向的文件已不存在（旧下载文件被移走/清掉）时，
                    // 把路径更新到这次真正落盘的文件并清 missing；否则书架会永远指向缺失路径，新文件成孤儿。
                    val existingFile = File(existing.filePath)
                    val missingFile = !existingFile.isFile ||
                        (existingFileValidator != null && !existingFileValidator(existingFile))
                    val repointed = if (missingFile) {
                        existing.copy(filePath = file.absolutePath, fileSize = file.length())
                    } else {
                        existing
                    }
                    // task-70：重复导入命中时合并新的识别 tag（例如第二次用不同 SAF URI 导入同一内容），
                    // 以后再选这个 URI 就能在第一道判重直接命中。
                    val addedTags = tags.filterNot { it in repointed.tags }
                    val tagged = if (addedTags.isEmpty()) {
                        repointed
                    } else {
                        repointed.copy(tags = repointed.tags + addedTags)
                    }
                    // 文件回来了：清掉 missing 标记并回写；否则原样返回已有条目。
                    val healed = clearMissingTag(tagged)
                    if (healed != existing) {
                        val next = committed.map { if (it.id == existing.id) healed else it }
                        writeIndexLocked(next)
                        val version = commitLocked(next)
                        return@withLock Committed(healed, next, version)
                    }
                    return@withLock Committed(existing, committed, committedVersion)
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
    ): LibraryRecoveryResult = recoverCandidates(
        candidates.map { LibraryFileCandidate(file = it, tags = tags) },
    )

    /**
     * 批量补登记文件候选（P2-1）：可在候选里带 title/author/tags/addedAt（下载历史回填），
     * 锁内按最新 committed 去重，一次写盘；返回本次真正新增的条目。
     */
    suspend fun recoverCandidates(candidates: List<LibraryFileCandidate>): LibraryRecoveryResult {
        val outcome = mutex.withLock {
            ensureLoadedLocked()
            if (candidates.isEmpty()) {
                return@withLock Committed(LibraryRecoveryResult(emptyList(), committed), committed, committedVersion)
            }
            val knownPaths = committed.mapTo(HashSet(committed.size)) { pathKey(it.filePath) }
            val usedIds = committed.mapTo(HashSet(committed.size)) { it.id }
            val adopted = ArrayList<Book>(candidates.size)
            candidates.forEach { candidate ->
                val file = candidate.file
                // 双重过滤：调用方已经筛过一遍，这里再兜一层——不接受目录、.tmp 半成品和非 epub/pdf 文件。
                if (!isRecoverableLibraryFile(file)) return@forEach
                if (!knownPaths.add(pathKey(file.absolutePath))) return@forEach
                val format = sniffBookFormat(file) ?: return@forEach
                adopted += Book(
                    id = freshBookId(usedIds),
                    title = candidate.title?.takeIf { it.isNotBlank() } ?: recoveredBookTitle(file),
                    author = candidate.author?.takeIf { it.isNotBlank() },
                    format = format,
                    filePath = file.absolutePath,
                    fileSize = file.length(),
                    addedAt = candidate.addedAt ?: isoUtcTimestamp(file.lastModified()),
                    tags = candidate.tags,
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
     * 按 tag 查已有书架条目（P2-3 下载记录的 zlib tag；task-70 SAF URI/内容指纹 tag 也走这里）。
     * 命中不代表文件还在，调用方仍需自行检查 filePath（缺失时继续复制并由 registerFile 修路径）。
     */
    suspend fun findByTag(tag: String): Book? {
        if (tag.isBlank()) return null
        return mutex.withLock {
            ensureLoadedLocked()
            committed.firstOrNull { tag in it.tags }
        }
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
        lastLoadWasRebuilt = read.rebuilt
        lastLoadLostIndex = read.rebuilt && read.snapshotModified == null
        lastRecoveredSnapshotModified = read.snapshotModified ?: 0L
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
        /** 索引来自重建（文件缺失/损坏），不是一次正常读取。 */
        val rebuilt: Boolean = false,
        /** 重建时实际选中的快照 mtime；null 表示没有任何可解析的快照（索引真的丢了）。 */
        val snapshotModified: Long? = null,
    )

    private fun readIndexLocked(): ReadResult {
        val file = indexFile()
        if (!file.exists()) {
            // 上次损坏隔离后没来得及重建（或重建写盘失败）：先按快照 mtime 选最新可解析的备份。
            val snapshot = recoverFromSnapshotLocked()
            return if (snapshot == null) {
                ReadResult(emptyList(), corrupted = false, rebuilt = true)
            } else {
                ReadResult(snapshot.books, corrupted = true, rebuilt = true, snapshotModified = snapshot.modified)
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
            return ReadResult(emptyList(), corrupted = true, quarantineFailed = true, rebuilt = true)
        }
        val snapshot = recoverFromSnapshotLocked()
        if (snapshot != null) {
            return ReadResult(snapshot.books, corrupted = true, rebuilt = true, snapshotModified = snapshot.modified)
        }
        return ReadResult(emptyList(), corrupted = true, rebuilt = true)
    }

    /** 从备份恢复出来的一份索引快照。 */
    private data class SnapshotRecovery(val books: List<Book>, val source: String, val modified: Long)

    /**
     * P1 task-63：统一按「快照文件 mtime 从新到旧」选恢复源，而不是固定 .bad → bak1 → bak2。
     * - 损坏现场 .bad 的 mtime 来自被隔离的原文件；陈旧 .bad 不再压过更新的 bak1，避免复活已删书；
     * - 允许最新快照是空书架（用户确实删光了书时不能拿旧的非空快照复活）；
     * - 解析不了的快照（截断/0 字节）跳过，继续看下一旧的有效快照；
     * - 只读，不移动/删除任何快照（保留损坏现场）。
     */
    private fun recoverFromSnapshotLocked(): SnapshotRecovery? {
        val candidates = dir.listFiles().orEmpty()
            .filter { it.isFile && isIndexSnapshotName(it.name) }
            .sortedByDescending { it.lastModified() }
        for (file in candidates) {
            val decoded = runCatching { codec.decode(file.readText()) }.getOrNull() ?: continue
            val label = if (file.name.endsWith(BAD_SUFFIX)) ".bad" else file.name
            log("index recovered from " + label + " backup entries=" + decoded.size, null)
            return SnapshotRecovery(decoded, file.name, file.lastModified())
        }
        return null
    }

    /** books.json.bad(含时间戳版) / books.json.bak1 / books.json.bak2 都是候选快照。 */
    private fun isIndexSnapshotName(name: String): Boolean =
        name == BAK1_NAME || name == BAK2_NAME ||
            (name.startsWith(INDEX_FILE_NAME + ".") && name.endsWith(BAD_SUFFIX))

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
        val payload = codec.encode(books)
        writePayloadLocked(indexFile(), payload)
        // P2-1：A/B 轮换备份 = 本次成功提交（bak1）与上一次（bak2）。
        // 主索引被删除/损坏时优先恢复备份，避免「索引没了 = 默认下载目录的书永久丢条目」。
        writeLastGoodBackupLocked(payload)
    }

    /** 唯一临时名 + fsync + rename：同目录 rename 原子，读者只会看到旧/新的完整文件。 */
    private fun writePayloadLocked(target: File, payload: String) {
        val tmp = File(dir, target.name + "." + UUID.randomUUID() + TMP_SUFFIX)
        try {
            FileOutputStream(tmp).use { output ->
                output.write(payload.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
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

    /** 备份是 best-effort：主索引已写成功，备份失败只记日志，不能让写 API 误报失败。 */
    private fun writeLastGoodBackupLocked(payload: String) {
        runCatching {
            val bak1 = File(dir, BAK1_NAME)
            val bak2 = File(dir, BAK2_NAME)
            if (bak1.isFile) {
                bak2.delete()
                if (!bak1.renameTo(bak2)) {
                    bak1.copyTo(bak2, overwrite = true)
                    bak1.delete()
                }
            }
            writePayloadLocked(bak1, payload)
        }.onFailure { log("write index backup failed", it) }
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
private const val BAK1_NAME = "books.json.bak1"
private const val BAK2_NAME = "books.json.bak2"
private const val MISSING_TAG = "missing"
private const val DOWNLOAD_HISTORY_FILE_NAME = "zlib_downloads.json"
