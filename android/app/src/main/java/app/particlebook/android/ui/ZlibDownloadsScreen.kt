package app.particlebook.android.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.LibraryStore
import kotlinx.coroutines.launch
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
    modifier: Modifier = Modifier,
) {
    val records by ZlibDownloadManager.history.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var importingBookId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                ),
            )
        },
    ) { innerPadding ->
        if (records.isEmpty()) {
            EmptyDownloads(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .navigationBarsPadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = Spacing.screen,
                    end = Spacing.screen,
                    top = Spacing.xs,
                    bottom = Spacing.listBottomInset,
                ),
            ) {
                items(records, key = { it.bookId }) { record ->
                    val imported = store.books.firstOrNull { it.filePath == record.destPath }
                    val fileExists = remember(record.destPath) { File(record.destPath).isFile }
                    DownloadRecordRow(
                        record = record,
                        imported = imported,
                        fileExists = fileExists,
                        importing = importingBookId == record.bookId,
                        onOpen = { imported?.let(onOpenBook) },
                        onImport = {
                            if (importingBookId != null) return@DownloadRecordRow
                            importingBookId = record.bookId
                            scope.launch {
                                val result = store.adoptLocalFile(
                                    file = File(record.destPath),
                                    title = record.title,
                                    author = record.author,
                                )
                                importingBookId = null
                                result.fold(
                                    onSuccess = { book ->
                                        snackbarHostState.showSnackbar("已加入书架")
                                        onOpenBook(book)
                                    },
                                    onFailure = {
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
                        text = "P1c 将提供下载目录选择；当前文件保存在应用专属下载目录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
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

@Composable
private fun DownloadRecordRow(
    record: ZlibDownloadTask,
    imported: Book?,
    fileExists: Boolean,
    importing: Boolean,
    onOpen: () -> Unit,
    onImport: () -> Unit,
) {
    val completed = record.status == ZlibDownloadStatus.COMPLETED && fileExists
    val clickable = completed && imported != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .then(if (clickable) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (completed) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
            contentDescription = if (completed) "下载完成" else "下载失败",
            modifier = Modifier.size(24.dp),
            tint = if (completed) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Spacer(Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = record.fileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = recordMetaLine(record, completed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
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

            !completed -> Text(
                text = if (!fileExists) "文件缺失" else "未完成",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.xs),
            )

            imported != null -> TextButton(onClick = onOpen) {
                Text("打开")
            }

            else -> TextButton(onClick = onImport) {
                Text("导入书架")
            }
        }
    }
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
        record.status == ZlibDownloadStatus.FAILED -> "失败"
        else -> "未完成"
    }
    return "$size · $time · $status"
}
