package app.particlebook.android.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        // P1c：SAF 下载会复制成本地文件再入书架，filePath 对不上记录，用下载标记兜底。
        store.books.firstOrNull {
            it.filePath == task.destPath || it.tags.contains("zlib:" + task.bookId)
        }
    }

    LazyColumn(
        // P1c P2-1b：详情页是无胶囊的覆盖层 → 底部只避让系统导航条（bottomBarPadding）+ 16dp，
        // 不再叠加 listBottomInset（旧实现 176dp，多 112dp 死白）。
        modifier = modifier.bottomBarPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = Spacing.screen,
            end = Spacing.screen,
            top = Spacing.sm,
            bottom = Spacing.md,
        ),
    ) {
        item {
            Row(verticalAlignment = Alignment.Top) {
                ZlibCoverImage(
                    bitmap = coverBitmap,
                    title = detail.title,
                    // 3:4 封面比例（与书架卡一致，2026 观感 §17）。
                    modifier = Modifier
                        .width(112.dp)
                        .aspectRatio(3f / 4f),
                )
                Spacer(Modifier.width(Spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = detail.title,
                        // 与登录页大标题同一层级（34sp Serif 体系），详情页书名不再用 20sp Sans。
                        style = AppType.pageTitle,
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
        // P1c 简介区块占位：task-28 未确认数据源，ZBookDetail 目前也没有简介字段（不改 zlib/ 契约）。
        // 传 null → 区块完全不渲染，避免出现任何占位/假内容；数据源落地后把真实简介传进来即可。
        item {
            ZlibDetailDescription(text = detail.description)
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

/**
 * 详情页简介区块（站点 https://…/book/<hash>/… 的 div#bookDescriptionBox，无 JS）。
 * text 为 null/空白时不渲染任何内容（zlib 解析落地前保持零高度）。
 * 样式：小标「简介」+ 15sp/行高 1.7 正文 + 最多 8 行折叠（展开/收起），放在 ISBN 之后、下载按钮之前。
 */
@Composable
private fun ZlibDetailDescription(text: String?) {
    if (text.isNullOrBlank()) return
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var hasOverflow by remember(text) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = "简介",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 25.5.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 8,
            overflow = TextOverflow.Ellipsis,
            // task-38②：展开/收起高度动画。
            modifier = Modifier.animateContentSize(animationSpec = Motion.enterSpatial()),
            onTextLayout = { layout ->
                if (!expanded) hasOverflow = layout.hasVisualOverflow
            },
        )
        if (hasOverflow || expanded) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(if (expanded) "收起" else "更多")
            }
        }
    }
}

@Composable
private fun PropertyChip(label: String) {
    // P2-6：属性 chip 高度 ≥28dp，触控/读数都更稳。
    Surface(
        modifier = Modifier.heightIn(min = 28.dp),
        shape = RoundedCornerShape(Radius.chip),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
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
