package app.particlebook.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.LibraryStore
import app.particlebook.android.zlib.ZBookDetail

/**
 * Z-Library 书籍详情页（P1b §4.3，全屏 route zlib/detail）。
 *
 * 下载由进程级 [ZlibDownloadManager] 驱动；完成时自动导入书架（LibraryStore.adoptLocalFile 幂等）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ZlibDetailScreen(
    viewModel: ZlibDetailViewModel,
    store: LibraryStore,
    hash: String,
    slug: String?,
    onBack: () -> Unit,
    onOpenBook: (Book) -> Unit,
    onRequireLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = viewModel.state
    LaunchedEffect(hash, slug) { viewModel.load(hash, slug) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "书籍详情",
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
        when (val current = state) {
            is ZlibDetailState.Loading -> BoxLoading(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            is ZlibDetailState.Error -> BoxError(
                message = current.message,
                onRetry = { viewModel.retry(hash, slug) },
                onBack = onBack,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            is ZlibDetailState.Ready -> DetailContent(
                detail = current.detail,
                store = store,
                onOpenBook = onOpenBook,
                onRequireLogin = onRequireLogin,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }
}

@Composable
private fun BoxLoading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "正在加载详情…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BoxError(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.BrokenImage,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = "无法加载书籍详情",
            style = AppType.sectionTitle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.lg))
        Button(
            onClick = onRetry,
            shape = RoundedCornerShape(Radius.cover),
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text("重试")
        }
        TextButton(onClick = onBack) {
            Text("返回")
        }
    }
}

@Composable
private fun DetailContent(
    detail: ZBookDetail,
    store: LibraryStore,
    onOpenBook: (Book) -> Unit,
    onRequireLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val coverWidthPx = with(density) { 112.dp.roundToPx() }
    val coverBitmap by produceState<Bitmap?>(initialValue = null, detail.bookId, detail.coverUrl) {
        value = ZlibCoverCache.load(context, detail.bookId, detail.coverUrl, coverWidthPx)
    }

    val tasks by ZlibDownloadManager.tasks.collectAsState()
    val activeTask = tasks.firstOrNull { it.bookId == detail.bookId && it.isActive }
    val failedTask = tasks.firstOrNull {
        it.bookId == detail.bookId && it.status == ZlibDownloadStatus.FAILED
    } ?: ZlibDownloadManager.historyForBook(detail.bookId)
        ?.takeIf { it.status == ZlibDownloadStatus.FAILED }
    val completedTask = tasks.firstOrNull {
        it.bookId == detail.bookId && it.status == ZlibDownloadStatus.COMPLETED
    } ?: ZlibDownloadManager.historyForBook(detail.bookId)
        ?.takeIf { it.status == ZlibDownloadStatus.COMPLETED }
    val completedBook = completedTask?.let { task ->
        store.books.firstOrNull { it.filePath == task.destPath }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = Spacing.screen,
            end = Spacing.screen,
            top = Spacing.sm,
            bottom = Spacing.listBottomInset,
        ),
    ) {
        item {
            Row(verticalAlignment = Alignment.Top) {
                ZlibCoverImage(
                    bitmap = coverBitmap,
                    title = detail.title,
                    modifier = Modifier
                        .width(112.dp)
                        .height(160.dp),
                )
                Spacer(Modifier.width(Spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = detail.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = detail.authors.takeIf { it.isNotEmpty() }?.joinToString(" / ")
                            ?: "未知作者",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        item {
            Spacer(Modifier.height(Spacing.md))
            val chips = buildList {
                detail.language?.takeIf { it.isNotBlank() }?.let { add(it) }
                detail.year?.takeIf { it.isNotBlank() }?.let { add(it) }
                detail.format?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
                detail.fileSize?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            if (chips.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    chips.forEach { label -> PropertyChip(label) }
                }
            }
        }
        if (!detail.isbn.isNullOrBlank()) {
            item {
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = "ISBN " + detail.isbn,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Spacer(Modifier.height(Spacing.lg))
            DownloadAction(
                detail = detail,
                store = store,
                activeTask = activeTask,
                failedTask = failedTask,
                completedTask = completedTask,
                completedBook = completedBook,
                onOpenBook = onOpenBook,
                onRequireLogin = onRequireLogin,
            )
        }
        item {
            Spacer(Modifier.height(Spacing.lg))
            Text(
                text = "下载后会自动加入书架；若账号未登录或今日额度用完，请先登录或稍后重试。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PropertyChip(label: String) {
    Surface(
        shape = RoundedCornerShape(Radius.chip),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun DownloadAction(
    detail: ZBookDetail,
    store: LibraryStore,
    activeTask: ZlibDownloadTask?,
    failedTask: ZlibDownloadTask?,
    completedTask: ZlibDownloadTask?,
    completedBook: Book?,
    onOpenBook: (Book) -> Unit,
    onRequireLogin: () -> Unit,
) {
    when {
        activeTask != null -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                val progress = activeTask.progress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = "下载中 " + (progress * 100).toInt() + "%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = "准备下载…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = activeTask.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        failedTask != null -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = failedTask.errorMessage ?: "下载失败，请重试",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(Spacing.xs))
                // 需要登录才能继续的失败：LOGIN_REQUIRED（未登录）与 DAILY_LIMIT（游客额度用完，
                // 登录后可继续）都给「去登录」主按钮，并保留「重试」次按钮。
                val needsLogin = failedTask.errorKind == "LOGIN_REQUIRED" ||
                    failedTask.errorKind == "DAILY_LIMIT"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            if (needsLogin) {
                                onRequireLogin()
                            } else {
                                // start() 会重置失败任务（含重启后从历史里恢复的失败记录）。
                                ZlibDownloadManager.start(detail, store)
                            }
                        },
                        shape = RoundedCornerShape(Radius.cover),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(if (needsLogin) "去登录" else "重试")
                    }
                    if (needsLogin) {
                        Spacer(Modifier.width(Spacing.xs))
                        TextButton(
                            onClick = { ZlibDownloadManager.start(detail, store) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("重试")
                        }
                    }
                }
            }
        }

        completedTask != null -> {
            val book = completedBook
            Button(
                onClick = { book?.let(onOpenBook) },
                enabled = book != null,
                shape = RoundedCornerShape(Radius.cover),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Text(if (book != null) "已下载 · 打开" else "已下载", fontWeight = FontWeight.Medium)
            }
        }

        else -> {
            Button(
                onClick = { ZlibDownloadManager.start(detail, store) },
                shape = RoundedCornerShape(Radius.cover),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            ) {
                val suffix = detail.fileSize?.takeIf { it.isNotBlank() }
                Text(
                    text = if (suffix != null) "下载 · " + suffix else "下载",
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
