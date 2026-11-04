package app.particlebook.android.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.DownloadLocation
import app.particlebook.android.ui.data.LibraryStore
import app.particlebook.android.zlib.ZBookDetail
import app.particlebook.android.zlib.ZRecommendation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** task-55：详情页「已完成记录」在三种书架/文件状态下的动作。 */
internal enum class ZlibDetailCompletedAction {
    /** 书架里有对应条目 → 已下载 · 打开。 */
    OPEN,

    /** 记录是 COMPLETED 但书架里没有条目，目标文件还在 → 重新导入（不重下）。 */
    REIMPORT,

    /** 记录是 COMPLETED、书架没有、目标文件也不在 → 只能重新下载。 */
    REDOWNLOAD,
}

/**
 * task-55：completedBook 为 null 时不能继续显示不可点的「已下载」。
 * 文件仍在走 reimportTask/adopt；文件已丢则回到重新下载，避免用户卡死在假完成态。
 */
internal fun zlibDetailCompletedAction(
    completedBook: Book?,
    targetExists: Boolean,
): ZlibDetailCompletedAction = when {
    completedBook != null -> ZlibDetailCompletedAction.OPEN
    targetExists -> ZlibDetailCompletedAction.REIMPORT
    else -> ZlibDetailCompletedAction.REDOWNLOAD
}

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
    /** task-58：账号状态由 AppRoot 的账号 ViewModel（Observable）显式传入。 */
    loggedIn: Boolean,
    /** task-58：账号标识（邮箱/昵称）；换账号时连同 loggedIn 一起重建阅读入口状态。 */
    accountKey: String?,
    onBack: () -> Unit,
    onOpenBook: (Book) -> Unit,
    onRequireLogin: () -> Unit,
    onOpenDetail: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = viewModel.state
    LaunchedEffect(hash, slug) { viewModel.load(hash, slug) }
    // task-55：Manager 发现书架已有同一本时给一次「已在书架」提示，动作直接打开书。
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
        // task-43 P2-1：详情层被上层压住时 AppRoot 会把本层压成 0 约束；用可见性代替不可靠的
        // 「composable 还在组合里就当作活跃」——解析完成时若本层已不可见，绝不替用户拉起浏览器。
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val layerVisible = maxWidth > 0.dp && maxHeight > 0.dp
            when (val current = state) {
                is ZlibDetailState.Loading -> BoxLoading(modifier = Modifier.fillMaxSize())

                is ZlibDetailState.Error -> BoxError(
                    message = current.message,
                    onRetry = { viewModel.retry(hash, slug) },
                    onBack = onBack,
                    modifier = Modifier.fillMaxSize(),
                )

                is ZlibDetailState.Ready -> DetailContent(
                    detail = current.detail,
                    store = store,
                    onOpenBook = onOpenBook,
                    onRequireLogin = onRequireLogin,
                    onOpenDetail = onOpenDetail,
                    loggedIn = loggedIn,
                    accountKey = accountKey,
                    layerVisible = layerVisible,
                    snackbarHostState = snackbarHostState,
                    modifier = Modifier.fillMaxSize(),
                )
            }
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
    onOpenDetail: (String, String?) -> Unit,
    loggedIn: Boolean,
    accountKey: String?,
    layerVisible: Boolean,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val coverWidthPx = with(density) { 112.dp.roundToPx() }
    val coverBitmap by produceState<Bitmap?>(initialValue = null, detail.bookId, detail.coverUrl) {
        value = ZlibCoverCache.load(context, detail.bookId, detail.coverUrl, coverWidthPx)
    }

    val tasks by ZlibDownloadManager.tasks.collectAsState()
    // U11：下载记录是异步从磁盘恢复的 StateFlow，直接调 historyForBook() 读到的是当时的快照、
    // 也不会订阅重组 —— 冷启动时记录还没加载完，「已下载」就永远不刷新。必须走 collectAsState 链。
    val history by ZlibDownloadManager.history.collectAsState()
    val historyTask = history.firstOrNull { it.bookId == detail.bookId }
    val activeTask = tasks.firstOrNull { it.bookId == detail.bookId && it.isActive }
    // U3：用户取消的下载只留在内存卡片里（不写历史），可续传或删除。
    val cancelledTask = tasks.firstOrNull {
        it.bookId == detail.bookId && it.status == ZlibDownloadStatus.CANCELLED
    }
    val failedTask = tasks.firstOrNull {
        it.bookId == detail.bookId && it.status == ZlibDownloadStatus.FAILED
    } ?: historyTask?.takeIf { it.status == ZlibDownloadStatus.FAILED }
    val completedTask = tasks.firstOrNull {
        it.bookId == detail.bookId && it.status == ZlibDownloadStatus.COMPLETED
    } ?: historyTask?.takeIf { it.status == ZlibDownloadStatus.COMPLETED }
    val completedBook = completedTask?.let { task ->
        // P1c：SAF 下载会复制成本地文件再入书架，filePath 对不上记录，用下载标记兜底。
        store.books.firstOrNull {
            it.filePath == task.destPath || it.tags.contains("zlib:" + task.bookId)
        }
    }
    // task-55：完成记录还在但书架里没有条目时，先看目标文件是否仍在（SAF 查询要挪到 IO）。
    val completedTargetExists by produceState(
        initialValue = false,
        completedTask?.id,
        completedTask?.destPath,
    ) {
        val record = completedTask
        value = record != null && withContext(Dispatchers.IO) {
            DownloadLocation.exists(context, record.destPath)
        }
    }
    val completedAction = zlibDetailCompletedAction(completedBook, completedTargetExists)
    // task-55：Manager 的 onAlreadyInLibrary 回调 —— 提示「已在书架」并可直接打开。
    val onAlreadyInLibrary: (Book) -> Unit = { book ->
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "已在书架",
                actionLabel = "打开",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) onOpenBook(book)
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
                cancelledTask = cancelledTask,
                failedTask = failedTask,
                completedTask = completedTask,
                completedBook = completedBook,
                completedAction = completedAction,
                // U9：未登录时「重试」必然再次 LOGIN_REQUIRED，按钮直接改成「去登录」。
                loggedIn = loggedIn,
                onOpenBook = onOpenBook,
                onRequireLogin = onRequireLogin,
                onAlreadyInLibrary = onAlreadyInLibrary,
            )
            Spacer(Modifier.height(Spacing.sm))
            // A13 转向：reader.z-lib.by 内容接口对当前账号全部不可读，不做内嵌 WebView，
            // 只在外部浏览器打开站点签发的 reader URL。
            ReaderAction(
                bookHash = detail.hash,
                loggedIn = loggedIn,
                accountKey = accountKey,
                layerVisible = layerVisible,
            )
        }
        // A12：站点「You may be interested in」推荐区；无数据时整区不渲染（不造占位）。
        if (detail.recommendations.isNotEmpty()) {
            item {
                Spacer(Modifier.height(Spacing.lg))
                Text(
                    text = "你可能也感兴趣",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Spacing.sm))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    items(items = detail.recommendations, key = { it.hash }) { recommendation ->
                        RecommendationCard(
                            recommendation = recommendation,
                            onOpenDetail = onOpenDetail,
                        )
                    }
                }
            }
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

/**
 * A12：单张推荐卡（104dp 宽 3:4 封面 + 最多两行书名 + 一行作者），点击进入对应书籍详情。
 * 封面走与详情/搜索共用的 ZlibCoverCache；cover-not-exists 等无封面卡自动回退首字占位块。
 */
@Composable
private fun RecommendationCard(
    recommendation: ZRecommendation,
    onOpenDetail: (String, String?) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val coverWidthPx = with(density) { 104.dp.roundToPx() }
    // 推荐卡没有搜索结果那套 id 字段时退回 hash 当缓存 key，保证同一本书多处封面复用。
    val coverKey = recommendation.bookId.ifBlank { recommendation.hash }
    val bitmap by produceState<Bitmap?>(
        initialValue = ZlibCoverCache.peek(coverKey),
        coverKey,
        recommendation.coverUrl,
    ) {
        value = ZlibCoverCache.load(context, coverKey, recommendation.coverUrl, coverWidthPx)
    }
    Column(
        modifier = Modifier
            .width(104.dp)
            .clip(RoundedCornerShape(Radius.cover))
            .clickable { onOpenDetail(recommendation.hash, recommendation.slug) },
    ) {
        ZlibCoverImage(
            bitmap = bitmap,
            title = recommendation.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f),
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = recommendation.title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (recommendation.author.isNotBlank()) {
            Text(
                text = recommendation.author,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DownloadAction(
    detail: ZBookDetail,
    store: LibraryStore,
    activeTask: ZlibDownloadTask?,
    cancelledTask: ZlibDownloadTask?,
    failedTask: ZlibDownloadTask?,
    completedTask: ZlibDownloadTask?,
    completedBook: Book?,
    completedAction: ZlibDetailCompletedAction,
    loggedIn: Boolean,
    onOpenBook: (Book) -> Unit,
    onRequireLogin: () -> Unit,
    onAlreadyInLibrary: (Book) -> Unit,
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
                        // U4：文件已下完、正在复制/登记进书架（不可取消）。
                        text = if (activeTask.importing) {
                            "下载完成 · 正在加入书架"
                        } else {
                            "下载中 " + (progress * 100).toInt() + "%"
                        },
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = activeTask.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!activeTask.importing) {
                        Spacer(Modifier.width(Spacing.xs))
                        // U3：进行中可取消；取消后先显示「正在取消…」，等协程收尾才翻成已取消。
                        TextButton(
                            onClick = { ZlibDownloadManager.cancel(activeTask.id) },
                            enabled = !activeTask.cancelling,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(if (activeTask.cancelling) "正在取消…" else "取消")
                        }
                    }
                }
            }
        }

        // U3：已取消 —— .part 保留，可续传；只有「删除」才清理半成品。
        cancelledTask != null -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (cancelledTask.cancelling) {
                        "正在取消下载…"
                    } else {
                        "下载已取消（已下载的部分已保留，可续传）"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { ZlibDownloadManager.retry(cancelledTask.id, store) },
                        enabled = !cancelledTask.cancelling,
                        shape = RoundedCornerShape(Radius.cover),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(if (cancelledTask.cancelling) "正在取消…" else "续传")
                    }
                    Spacer(Modifier.width(Spacing.xs))
                    TextButton(
                        onClick = { ZlibDownloadManager.discard(cancelledTask.id) },
                        enabled = !cancelledTask.cancelling,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("删除")
                    }
                }
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
                // U9：需要登录才能继续的失败（LOGIN_REQUIRED 未登录 / DAILY_LIMIT 游客额度用完）
                // 在未登录时只给「去登录」，不再提供必然失败的「重试」；已登录则给「重试」。
                // U4：文件已下完、只是导入书架失败（IMPORT_FAILED）→「重新导入」，不重下、不耗额度。
                val importFailed = failedTask.errorKind == ZLIB_IMPORT_FAILED_KIND
                val needsLogin = zlibFailureNeedsLogin(failedTask.errorKind)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        importFailed -> Button(
                            onClick = {
                                // 卡片还在内存里才有任务 id；冷启后只剩历史记录时走 start()（内部判定重导入）。
                                if (
                                    ZlibDownloadManager.taskForBook(failedTask.bookId)?.id ==
                                    failedTask.id
                                ) {
                                    ZlibDownloadManager.reimport(failedTask.id, store)
                                } else {
                                    ZlibDownloadManager.start(detail, store, onAlreadyInLibrary)
                                }
                            },
                            shape = RoundedCornerShape(Radius.cover),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("重新导入")
                        }

                        needsLogin && !loggedIn -> Button(
                            onClick = onRequireLogin,
                            shape = RoundedCornerShape(Radius.cover),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("去登录")
                        }

                        else -> Button(
                            onClick = {
                                // start() 会重置失败任务（含重启后从历史里恢复的失败记录）。
                                ZlibDownloadManager.start(detail, store, onAlreadyInLibrary)
                            },
                            shape = RoundedCornerShape(Radius.cover),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("重试")
                        }
                    }
                }
            }
        }

        completedTask != null -> {
            // task-55：记录 COMPLETED ≠ 书架里一定有书。书架有条目才给「打开」；没有但文件还在
            // 给「重新导入」；文件也没了才允许重新下载，避免停在不可点的假完成态。
            val book = completedBook
            Column(modifier = Modifier.fillMaxWidth()) {
                if (book != null) {
                    Button(
                        onClick = { onOpenBook(book) },
                        shape = RoundedCornerShape(Radius.cover),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    ) {
                        Text("已下载 · 打开", fontWeight = FontWeight.Medium)
                    }
                } else {
                    Text(
                        text = if (completedAction == ZlibDetailCompletedAction.REIMPORT) {
                            "下载记录还在，但书架里没有这本书"
                        } else {
                            "下载记录还在，但下载文件已不存在"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Button(
                        onClick = {
                            // 文件仍在时 start() 会走 reimportTask，不重新下载；文件不在则重新下载。
                            ZlibDownloadManager.start(detail, store, onAlreadyInLibrary)
                        },
                        shape = RoundedCornerShape(Radius.cover),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text(
                            if (completedAction == ZlibDetailCompletedAction.REIMPORT) {
                                "重新导入"
                            } else {
                                "重新下载"
                            },
                        )
                    }
                }
            }
        }

        else -> {
            Button(
                onClick = { ZlibDownloadManager.start(detail, store, onAlreadyInLibrary) },
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

/**
 * A13：在线阅读入口（外部浏览器）。
 *
 * 站点的 reader-link 在详情页 HTML 里动态签发（file_access_token 约 7 天），不能预先拼；
 * 点击时才用 [ZlibReaderResolver] 走 ZlibClient 解析（复用进程内挑战/cookie 会话）。
 * 未登录时禁用；解析失败或无浏览器时在按钮下方给中文提示，不弹 WebView。
 *
 * task-43 P2-1：不再用 LaunchedEffect(state) 自动打开浏览器 —— 解析期间用户可能已离开本层
 * （详情层被上层压住时仍保持组合），迟到的 Ready 会把用户从当前页面拉去浏览器。现在由点击处
 * scope.launch 直接 load→open，打开前同时校验本层仍可见、协程仍活跃。
 * task-43 P2-2：URL 成功交给浏览器后调用 [ZlibReaderViewModel.consumeReady] 复位 Idle，
 * 下次点击重新解析，不复用可能过期或属于旧账号的票据。
 */
@Composable
private fun ReaderAction(
    bookHash: String,
    loggedIn: Boolean,
    accountKey: String?,
    layerVisible: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // task-58 P2-1：登录态/账号变化时重建入口状态。否则退出或换账号后仍会复用旧会话解析出的
    // reader URL（票据属于旧 cookie），点开只会得到浏览器的无权限页。
    val viewModel = remember(bookHash, loggedIn, accountKey) {
        ZlibReaderViewModel(scope = scope, context = context)
    }
    val state = viewModel.state
    var launchError by remember(bookHash, loggedIn, accountKey) { mutableStateOf<String?>(null) }
    var opened by remember(bookHash, loggedIn, accountKey) { mutableStateOf(false) }
    // 解析期间本层可能被上层压住；读最新可见性而不是点击那一刻的快照。
    val visibleNow by rememberUpdatedState(layerVisible)

    fun openFromClick(readerUrl: String) {
        // task-58 P2-1：成功和失败都消费 Ready —— 无浏览器时旧 URL 下次点击仍会失败，
        // 且票据可能属于旧会话；下次点击重新解析，不再复用。
        val outcome = zlibReaderOpenOutcome(openZlibReaderInBrowser(context, readerUrl))
        opened = outcome.opened
        launchError = outcome.launchError
        if (outcome.consumeReady) viewModel.consumeReady()
    }

    val inlineMessage = zlibReaderActionHint(loggedIn, state, launchError, opened)
    val isError = launchError != null || state is ZlibReaderLaunchState.Error

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = {
                when (val current = viewModel.state) {
                    is ZlibReaderLaunchState.Ready -> {
                        // 旧 URL 只给一次机会：成功打开即消费；无浏览器时保留，下次点击重试同一条。
                        openFromClick(current.readerUrl)
                    }
                    is ZlibReaderLaunchState.Loading -> Unit
                    else -> {
                        opened = false
                        launchError = null
                        scope.launch {
                            val result = viewModel.loadNow(bookHash, null) ?: return@launch
                            if (result !is ZlibReaderLaunchState.Ready) return@launch
                            if (!zlibReaderShouldOpenBrowser(visibleNow, result)) {
                                // 本层已不可见/离场：丢弃这次 URL，回 Idle；用户回到本层再点会重新解析。
                                viewModel.consumeReady()
                                return@launch
                            }
                            ensureActive()
                            openFromClick(result.readerUrl)
                        }
                    }
                }
            },
            enabled = zlibReaderActionEnabled(loggedIn, state),
            shape = RoundedCornerShape(Radius.cover),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            if (state is ZlibReaderLaunchState.Loading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(Spacing.xs))
                Text(zlibReaderActionLabel(state))
            } else {
                Text(
                    text = zlibReaderActionLabel(state),
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = inlineMessage.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * 交给系统浏览器；无浏览器/系统拒绝（SecurityException 等）时返回 false，不崩溃。
 * reader URL 已由 [ZlibReaderUrl] 严格校验。
 */
private fun openZlibReaderInBrowser(context: Context, readerUrl: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(readerUrl)))
    true
} catch (_: Exception) {
    false
}
