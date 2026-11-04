package app.particlebook.android.ui

import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.DownloadLocation
import app.particlebook.android.ui.data.LibraryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 下载记录页（P1b §4.5，全屏 route settings/downloads）。
 *
 * 记录来自 [ZlibDownloadManager.history]（跨进程持久化）；点击已导入书架的书直接打开阅读器，
 * 未导入则提供「导入书架」（adoptLocalFile 幂等）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZlibDownloadsScreen(
    store: LibraryStore,
    onBack: () -> Unit,
    onOpenBook: (Book) -> Unit,
    onOpenDownloadFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val records by ZlibDownloadManager.history.collectAsState()
    // T15：历史是异步读盘的，冷启时 records 为空不代表「没有记录」——先给加载占位，别闪假空态。
    val historyLoaded by ZlibDownloadManager.historyLoaded.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var importingBookId by remember { mutableStateOf<String?>(null) }
    // D8：行的「更多」菜单与「删除记录」二次确认（只删记录 / 连文件一起删）。
    var menuBookId by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<ZlibDownloadTask?>(null) }
    // D8①：默认下载目录导入走 adoptLocalFile 原地登记（book.filePath == 记录 destPath），
    // 「连文件一起删」会把书架里的书本体删掉、下次启动该书被静默丢弃（LibraryStore.mergePersisted），
    // 所以文件被书架引用时只允许删记录。SAF 记录的书架副本在 _pb_files，路径不同 → 可安全删原文件。
    val deleteTargetInLibrary = deleteTarget?.let { target ->
        bookReferencesDownloadFile(store.books, target.destPath)
    } ?: false
    // 书架还在读盘时 books 为空，无法判断文件是否被引用——此时也只允许删记录，宁可少删不可删错。
    val libraryLoading = store.loading
    val deleteTargetRecordOnly = deleteTargetInLibrary || libraryLoading
    // D1 A-lite：默认目录在 Android 11+ 无法用系统文件管理器直达，改开应用内文件列表；
    // SAF 自定义目录仍由系统文件管理器打开（p15 t42 D1b 已验证可用）。
    val openFolder: () -> Unit = {
        if (DownloadLocation.isCustom(context)) {
            if (!DownloadLocation.openFolder(context)) {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        DownloadLocation.openFolderFailureMessage(context),
                    )
                }
            }
        } else {
            onOpenDownloadFolder()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        // P1c：下载记录页无悬浮胶囊，Snackbar 只避让系统导航条 + 16dp。
        snackbarHost = { AppSnackbarHost(snackbarHostState, aboveCapsule = false) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "下载记录",
                        style = AppType.topBarTitle,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = openFolder,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FolderOpen,
                            contentDescription = "打开所在文件夹",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                ),
            )
        },
    ) { innerPadding ->
        if (!historyLoaded) {
            LoadingDownloads(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .bottomBarPadding(),
            )
        } else if (records.isEmpty()) {
            EmptyDownloads(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .bottomBarPadding(),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .bottomBarPadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = Spacing.screen,
                    end = Spacing.screen,
                    top = Spacing.xs,
                    // P1c P2-1b：无胶囊页 → 导航条（bottomBarPadding）+ 16dp，不再留 128dp 死白。
                    bottom = Spacing.md,
                ),
            ) {
                items(records, key = { it.bookId }) { record ->
                    val imported = store.books.firstOrNull {
                        it.filePath == record.destPath ||
                            it.tags.contains(zlibBookTag(record.bookId))
                    }
                    // task-93：记录里「已导入」的条目也可能指向 0 字节/截断文件，不能直接给
                    // 「打开」；先异步确认书架副本完整。
                    val importedBookComplete by produceState<Boolean?>(
                        initialValue = null,
                        imported?.filePath,
                    ) {
                        val book = imported
                        value = if (book == null) {
                            false
                        } else {
                            withContext(Dispatchers.IO) {
                                zlibInspectBookFile(File(book.filePath)) ==
                                    ZlibDownloadTargetState.COMPLETE
                            }
                        }
                    }
                    // P1 task-93：三态（完整/缺失/损坏）统一走 Manager 的完整性检查；不能只 exists，
                    // 也不能用记录里可能过期的 total 当长度判据。
                    val targetCheck by produceState<ZlibImportTarget?>(
                        initialValue = null,
                        record.destPath,
                        record.total,
                    ) {
                        value = ZlibDownloadManager.inspectImportTarget(context, record)
                    }
                    DownloadRecordRow(
                        record = record,
                        imported = imported,
                        importedBookComplete = importedBookComplete,
                        targetCheck = targetCheck,
                        importing = importingBookId == record.bookId,
                        importEnabled = !libraryLoading,
                        menuExpanded = menuBookId == record.bookId,
                        onOpen = { imported?.let(onOpenBook) },
                        onMenuClick = { menuBookId = record.bookId },
                        onDismissMenu = { if (menuBookId == record.bookId) menuBookId = null },
                        onOpenFolder = openFolder,
                        onDeleteClick = { deleteTarget = record },
                        // T23/D3：CANCELLED（续传）与 FAILED(INTERRUPTED)（重试）都从历史恢复下载；
                        // 半成品被系统清掉时如实提示从头下载。记录页暂无进行中区块（已知限制），
                        // 进度要到详情页/主 tab 卡片查看。
                        onResume = {
                            val interrupted = record.status == ZlibDownloadStatus.FAILED &&
                                record.errorKind == ZlibDownloadManager.ZLIB_INTERRUPTED_KIND
                            val source = ZlibDownloadManager.resumeCancelled(record.bookId, store)
                            val message = when (source) {
                                ZlibResumeSource.PARTIAL ->
                                    if (interrupted) "已从中断处继续下载" else "已从上次进度续传"
                                ZlibResumeSource.RESTART -> "临时文件已被清理，将从头重新下载"
                                ZlibResumeSource.UNAVAILABLE, null ->
                                    if (interrupted) "无法重试，请稍后重试" else "无法续传，请稍后重试"
                            }
                            scope.launch { snackbarHostState.showSnackbar(message) }
                        },
                        onImport = {
                            if (importingBookId != null) return@DownloadRecordRow
                            importingBookId = record.bookId
                            scope.launch {
                                // P2-3：冷启时书架可能还在读盘，books 为空会让「已导入」判断失真、
                                // 也可能重复登记；先等书架就绪，仍加载中就禁用式提示，不硬导。
                                if (store.loading) store.ensureLoaded()
                                if (store.loading) {
                                    importingBookId = null
                                    snackbarHostState.showSnackbar("书架加载中，请稍后再导入")
                                    return@launch
                                }
                                // task-93：手工导入前必须重新做完整性校验，不能凭列表里的缓存状态；
                                // 0 字节/截断/HTML 文件直接拒绝并说明「缺失/不完整」，不再当作成功。
                                val check = ZlibDownloadManager.inspectImportTarget(context, record)
                                if (check.state != ZlibDownloadTargetState.COMPLETE) {
                                    importingBookId = null
                                    snackbarHostState.showSnackbar(
                                        zlibReimportFailureMessage(check.state),
                                    )
                                    return@launch
                                }
                                val validator = zlibExistingFileValidator(check.size)
                                val result = if (
                                    record.saf || record.destPath.startsWith("content://")
                                ) {
                                    store.adoptDocument(
                                        uri = Uri.parse(record.destPath),
                                        displayName = record.fileName,
                                        title = record.title,
                                        author = record.author,
                                        extraTag = zlibBookTag(record.bookId),
                                        existingFileValidator = validator,
                                    )
                                } else {
                                    store.adoptLocalFile(
                                        file = check.file ?: File(record.destPath),
                                        title = record.title,
                                        author = record.author,
                                        extraTag = zlibBookTag(record.bookId),
                                        existingFileValidator = validator,
                                    )
                                }
                                importingBookId = null
                                result.fold(
                                    onSuccess = { book ->
                                        snackbarHostState.showSnackbar("已加入书架")
                                        onOpenBook(book)
                                    },
                                    onFailure = {
                                        // 目标已完整但登记失败（权限/存储等）→ 不能再说「文件不完整」。
                                        snackbarHostState.showSnackbar("导入失败，请检查文件是否仍在")
                                    },
                                )
                            }
                        },
                    )
                }
                item {
                    Spacer(Modifier.height(Spacing.lg))
                    Text(
                        text = "下载目录：" + DownloadLocation.summary(context) + "；可在设置中更改。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // D8：删除记录二次确认 —— 「只删记录」保留磁盘文件；「连文件一起删」同时清理下载的文件。
    // 文件被书架引用时（默认下载目录原地登记）只提供「删除记录」，避免删掉书架书本体。
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除下载记录") },
            text = {
                Text(
                    if (libraryLoading) {
                        "书架数据还在加载，为避免误删书架里的书，只能删除下载记录，文件保留在下载目录。"
                    } else if (deleteTargetInLibrary) {
                        "这本书的下载文件已被书架引用（书架里的书就指向这个文件）。" +
                            "删除文件会让书架里的书失效，因此只能删除下载记录，文件保留在下载目录。"
                    } else {
                        "「只删记录」只从列表移除，下载的文件保留在下载目录；" +
                            "「连文件一起删」会同时删除已下载的文件。"
                    },
                )
            },
            confirmButton = {
                if (deleteTargetRecordOnly) {
                    TextButton(
                        onClick = {
                            deleteTarget = null
                            ZlibDownloadManager.removeHistory(
                                target.bookId,
                                deleteFile = false,
                            ) { result ->
                                // 书架引用/加载中导致只删记录时说明原因；其余结果用统一文案。
                                val message = if (result == ZlibHistoryDeleteResult.RECORD_ONLY) {
                                    if (libraryLoading) "书架加载中，仅删除记录" else "文件被书架引用，仅删除记录"
                                } else {
                                    deleteHistoryMessage(result)
                                }
                                scope.launch { snackbarHostState.showSnackbar(message) }
                            }
                        },
                    ) {
                        Text("删除记录")
                    }
                } else {
                    TextButton(
                        onClick = {
                            deleteTarget = null
                            ZlibDownloadManager.removeHistory(
                                target.bookId,
                                deleteFile = true,
                            ) { result ->
                                scope.launch { snackbarHostState.showSnackbar(deleteHistoryMessage(result)) }
                            }
                        },
                    ) {
                        Text("连文件一起删", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                Row {
                    if (!deleteTargetRecordOnly) {
                        TextButton(
                            onClick = {
                                deleteTarget = null
                                ZlibDownloadManager.removeHistory(
                                    target.bookId,
                                    deleteFile = false,
                                ) { result ->
                                    scope.launch { snackbarHostState.showSnackbar(deleteHistoryMessage(result)) }
                                }
                            },
                        ) {
                            Text("只删记录")
                        }
                    }
                    TextButton(onClick = { deleteTarget = null }) {
                        Text("取消")
                    }
                }
            },
        )
    }
}

@Composable
private fun EmptyDownloads(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.Download,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = "还没有下载记录",
            style = AppType.sectionTitle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "在 Z-Library 搜索并下载的书籍会出现在这里",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 冷启读盘中的占位：历史还没加载完时不要显示「还没有下载记录」（会让人以为记录丢了）。 */
@Composable
private fun LoadingDownloads(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            strokeWidth = 3.dp,
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = "正在加载下载记录…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** T15/D8：把 removeHistory 的真实删除结果翻成用户文案（文件删除失败不能谎报成功）。 */
internal fun deleteHistoryMessage(result: ZlibHistoryDeleteResult): String = when (result) {
    ZlibHistoryDeleteResult.NOT_FOUND -> "记录已不存在"
    ZlibHistoryDeleteResult.RECORD_ONLY -> "已删除记录"
    ZlibHistoryDeleteResult.RECORD_AND_FILE -> "已删除记录与文件"
    ZlibHistoryDeleteResult.FILE_DELETE_FAILED -> "记录已删除，文件删除失败"
    ZlibHistoryDeleteResult.FILE_IN_USE_RECORD_ONLY -> "该书的下载仍在进行，仅删除记录"
}

@Composable
private fun DownloadRecordRow(
    record: ZlibDownloadTask,
    imported: Book?,
    // task-93：书架副本是否完整（null = 还在查）；坏了不能给「打开」。
    importedBookComplete: Boolean?,
    // task-93：下载目标三态（null = 还在查）；完整才允许「导入书架/重新导入」。
    targetCheck: ZlibImportTarget?,
    importing: Boolean,
    // P2-3：书架加载完成前禁用导入，避免把书登记两次/误判未导入。
    importEnabled: Boolean,
    menuExpanded: Boolean,
    onOpen: () -> Unit,
    onMenuClick: () -> Unit,
    onDismissMenu: () -> Unit,
    onOpenFolder: () -> Unit,
    onDeleteClick: () -> Unit,
    onImport: () -> Unit,
    onResume: () -> Unit,
) {
    // 记录本身是否下载完成（文件是否还在另算）；SAF 目录下原文件可能被系统清理，
    // 但书已导入书架时「打开」仍应可用（task-38③）。
    val recordCompleted = record.status == ZlibDownloadStatus.COMPLETED
    // T23：用户取消的半成品保留在磁盘上（可续传），记录页要显示「已取消」而不是「未完成/失败」。
    val recordCancelled = record.status == ZlibDownloadStatus.CANCELLED
    // D3：进程被杀后恢复出来的中断记录 —— 记录页要有「重试」入口，且 .part 仍保留可续传。
    val recordInterrupted = record.status == ZlibDownloadStatus.FAILED &&
        record.errorKind == ZlibDownloadManager.ZLIB_INTERRUPTED_KIND
    val clickable = recordCompleted && imported != null
    // P1-6：200% 字号下三行都只有省略号 → 书名放到 2 行、隐藏文件名行（标题已足够识别）。
    val largeFont = LocalDensity.current.fontScale >= 1.3f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .then(if (clickable) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when {
                recordCompleted -> Icons.Outlined.CheckCircle
                recordCancelled -> Icons.Outlined.Cancel
                else -> Icons.Outlined.ErrorOutline
            },
            contentDescription = when {
                recordCompleted -> "下载完成"
                recordCancelled -> "已取消"
                else -> "下载失败"
            },
            modifier = Modifier.size(24.dp),
            tint = when {
                recordCompleted -> MaterialTheme.colorScheme.tertiary
                recordCancelled -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.error
            },
        )
        Spacer(Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (largeFont) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!largeFont) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = record.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = recordMetaLine(record, recordCompleted),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (largeFont) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            importing -> CircularProgressIndicator(
                modifier = Modifier
                    .padding(horizontal = Spacing.sm)
                    .size(20.dp),
                strokeWidth = 2.dp,
            )

            // T23/D3：取消态给「续传」；中断记录给「重试」——两者都走 resumeCancelled 从历史恢复。
            recordCancelled -> TextButton(onClick = onResume) {
                Text("续传")
            }

            recordInterrupted -> TextButton(onClick = onResume) {
                Text("重试")
            }

            !recordCompleted -> Text(
                text = "未完成",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.xs),
            )

            // task-93：书架条目必须指向完整文件才给「打开」；条目坏了要让用户重新导入修复。
            imported != null && importedBookComplete == true -> TextButton(onClick = onOpen) {
                Text("打开")
            }

            // 书架副本状态还在查：先留空，避免「打开」闪一下又变成「导入书架」。
            imported != null && importedBookComplete == null -> Unit

            targetCheck == null -> Unit

            targetCheck.state == ZlibDownloadTargetState.COMPLETE -> TextButton(
                onClick = onImport,
                enabled = importEnabled,
            ) {
                Text(
                    when {
                        !importEnabled -> "书架加载中"
                        imported != null -> "重新导入"
                        else -> "导入书架"
                    },
                )
            }

            // task-93：三态文案——目标存在但 0 字节/截断/HTML 不再谎报「可导入」。
            else -> Text(
                text = zlibImportStateLabel(targetCheck.state) ?: "文件损坏",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.xs),
            )
        }
        // D8：单行「在文件夹中打开」+「删除记录」。
        Box {
            IconButton(onClick = onMenuClick, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "更多操作",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
                DropdownMenuItem(
                    text = { Text("在文件夹中打开") },
                    onClick = {
                        onDismissMenu()
                        onOpenFolder()
                    },
                )
                DropdownMenuItem(
                    text = { Text("删除记录") },
                    onClick = {
                        onDismissMenu()
                        onDeleteClick()
                    },
                )
            }
        }
    }
}

/** 下载完成后给书架书籍打的标记（SAF 目录会复制成本地文件，filePath 无法与记录直接对齐）。 */
private fun zlibBookTag(bookId: String): String = "zlib:" + bookId

/**
 * D8①：下载记录的文件是否就是书架里某本书的本体。
 *
 * 默认下载目录导入走 [LibraryStore.adoptLocalFile] 原地登记（不复制），book.filePath 与记录
 * destPath 相同 —— 删掉这个文件等于删掉书架里的书（下次启动 mergePersisted 会静默丢弃该书）。
 * SAF 目录走 adoptDocument：书架副本在 filesDir/_pb_files，路径不同，删原文件安全。
 */
internal fun bookReferencesDownloadFile(books: List<Book>, destPath: String): Boolean {
    if (destPath.isBlank()) return false
    return books.any { it.filePath == destPath }
}

private fun recordMetaLine(record: ZlibDownloadTask, completed: Boolean): String {
    val size = formatDownloadBytes(if (record.received > 0) record.received else record.total)
    val time = record.finishedAt.takeIf { it > 0L }?.let { at ->
        DateUtils.getRelativeTimeSpanString(
            at,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
    } ?: "未知时间"
    val status = when {
        completed -> "已完成"
        record.status == ZlibDownloadStatus.CANCELLED -> "已取消"
        // D3：区分「进程中断、可重试」与普通下载失败。
        record.status == ZlibDownloadStatus.FAILED &&
            record.errorKind == ZlibDownloadManager.ZLIB_INTERRUPTED_KIND -> "已中断"
        record.status == ZlibDownloadStatus.FAILED -> "失败"
        else -> "未完成"
    }
    // P2-2：空值不产生前导「— ·」。
    return listOfNotNull(size.takeIf { it != "—" && it.isNotBlank() }, time, status)
        .joinToString(" · ")
}
