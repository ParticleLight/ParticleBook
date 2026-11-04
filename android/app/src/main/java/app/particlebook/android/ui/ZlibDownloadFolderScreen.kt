package app.particlebook.android.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.DownloadFolderEntry
import app.particlebook.android.ui.data.DownloadLocation
import app.particlebook.android.ui.data.LibraryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * D1 A-lite：默认下载目录的应用内文件列表。
 *
 * Android 11+ 的系统文件管理器受 scoped storage 限制，无法直接打开
 * Android/data/app.particlebook/files/Download/Z-Library；这里用应用自己的读权限列出文件，
 * 用户点文件即可打开书架里对应的书。未导入的文件提示先去下载记录页导入。
 *
 * SAF 自定义下载目录不经过本页，仍由 DownloadLocation.openFolder 打开系统文件管理器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZlibDownloadFolderScreen(
    store: LibraryStore,
    onBack: () -> Unit,
    onOpenBook: (Book) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    // 记录页是唯一入口（pushRoute("downloadFolder")），默认返回上一页即下载记录。
    // 保留显式回调，后续若从别的路由进入可由调用方覆盖。
    onOpenDownloads: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val openDownloads = onOpenDownloads ?: onBack

    // P2-2：页面会被 AppRoot 保留在组合里，produceState 无 key 时列表永远是首次快照。
    // refreshTick 由「顶部刷新」和 Activity ON_RESUME 递增；下载历史变化也作为刷新 key，
    // 这样下载完成后即使页面隐藏也会在回到前台时重新读目录。
    var refreshTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val downloadHistory by ZlibDownloadManager.history.collectAsState()
    // P2-1：destPath + bookId -> zlib:<bookId> tag；书架文件可能已被 SAF 复制/repoint 到 _pb_files，
    // 不能只比较 filePath。
    val historyHints = remember(downloadHistory) {
        DownloadLocation.downloadFolderHistoryHints(
            downloadHistory.map { DownloadLocation.DownloadFolderBookHint(it.destPath, it.bookId) },
        )
    }
    LaunchedEffect(Unit) { store.ensureLoaded() }

    // 列表状态：null = 首次加载；刷新时保留旧列表，避免每次历史变化都闪一下加载圈。
    var entries by remember { mutableStateOf<List<DownloadFolderEntry>?>(null) }
    LaunchedEffect(refreshTick, downloadHistory) {
        entries = withContext(Dispatchers.IO) {
            runCatching { DownloadLocation.listDefaultFolderEntries(context) }
                .getOrDefault(emptyList())
        }
    }
    val locationSummary = remember(refreshTick) { DownloadLocation.summary(context) }
    val libraryLoading = store.loading

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { AppSnackbarHost(snackbarHostState, aboveCapsule = false) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "下载文件夹",
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
                        onClick = { refreshTick++ },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "刷新文件列表",
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val loaded = entries
            when {
                // P2-3：冷启时书架还没读完，不能让所有文件先显示「未导入」。
                loaded == null || libraryLoading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp,
                    )
                }

                loaded.isEmpty() -> EmptyDownloadFolder(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(
                        start = Spacing.screen,
                        end = Spacing.screen,
                        top = Spacing.xs,
                        bottom = Spacing.xs,
                    ),
                ) {
                    items(loaded, key = { it.path }) { entry ->
                        // P2-1：pathKey 全等优先；未命中再用下载历史 destPath -> zlib tag 兜底。
                        val book = DownloadLocation.findBookForFolderEntry(
                            entry = entry,
                            books = store.books,
                            historyHints = historyHints,
                        )
                        DownloadFolderRow(
                            entry = entry,
                            inLibrary = book != null,
                            onClick = {
                                if (book != null) {
                                    onOpenBook(book)
                                } else {
                                    scope.launch {
                                        val result = snackbarHostState.showSnackbar(
                                            message = "该文件尚未导入书架，请先在下载记录页导入",
                                            actionLabel = "去下载记录",
                                            withDismissAction = true,
                                            duration = SnackbarDuration.Long,
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            openDownloads()
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
            DownloadFolderFooter(
                locationSummary = locationSummary,
                onOpenSettings = onOpenSettings,
            )
        }
    }
}

@Composable
private fun EmptyDownloadFolder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = "下载文件夹为空",
            style = AppType.sectionTitle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "在 Z-Library 下载的书籍会保存在这里，下载完成后可回到下载记录导入书架",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DownloadFolderRow(
    entry: DownloadFolderEntry,
    inLibrary: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = folderEntryMeta(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = if (inLibrary) "已在书架" else "未导入",
            style = MaterialTheme.typography.labelSmall,
            color = if (inLibrary) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun DownloadFolderFooter(
    locationSummary: String,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.screen, vertical = Spacing.sm),
    ) {
        Text(
            text = "应用专用目录，其他应用不可见",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "下载目录：" + locationSummary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("切换下载目录")
            }
        }
    }
}

/** 文件行副标题：大小 + 修改时间（与下载记录页的相对时间风格一致）。 */
private fun folderEntryMeta(entry: DownloadFolderEntry): String {
    val time = if (entry.modifiedAt > 0L) {
        DateUtils.getRelativeTimeSpanString(
            entry.modifiedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
    } else {
        "未知时间"
    }
    return formatDownloadBytes(entry.sizeBytes) + " · " + time
}
