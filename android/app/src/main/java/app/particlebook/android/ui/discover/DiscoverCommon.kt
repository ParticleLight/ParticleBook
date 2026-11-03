package app.particlebook.android.ui.discover

import android.graphics.Bitmap
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.particlebook.android.ui.AppType
import app.particlebook.android.ui.Motion
import app.particlebook.android.ui.Radius
import app.particlebook.android.ui.Spacing
import app.particlebook.android.ui.ZlibCoverCache
import app.particlebook.android.ui.bottomBarPadding
import kotlinx.coroutines.flow.collect

/**
 * P1.5 发现面（书单/榜单/分类）共用外壳与状态组件。
 *
 * 这些页面是全屏二级页（route 覆盖在 AppShell 之上，胶囊不可见）：外壳统一
 * [bottomBarPadding]（= navigationBarsPadding），列表 contentPadding.bottom 由各页自留
 * Spacing.md（16dp）——不要复用带胶囊页面的 listBottomInset（128/136dp 会留 112dp 死白）。
 */

/** 数据层错误 kind；未知异常返回 null（错误态里只展示中文说明）。 */
internal fun kindLabelOf(throwable: Throwable?): String? =
    (throwable as? DiscoverException)?.kind

/** 全屏二级页统一外壳：小号 TopAppBar（返回 + 标题），content 自己处理列表内边距。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiscoverPageScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    topActions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = AppType.topBarTitle,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
                actions = topActions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                ),
            )
        },
    ) { innerPadding ->
        // 二级页没有底部胶囊：只避让系统导航条，列表自己再留 16dp（Spacing.md）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .bottomBarPadding(),
        ) {
            content(innerPadding)
        }
    }
}

/** 首次加载：居中 spinner + 文案（分类首屏用全屏骨架，其它页用这个）。 */
@Composable
internal fun DiscoverLoading(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 整页错误态：图标 + 标题 + 原因 + 重试。kindLabel 只在已知 kind 时显示。 */
@Composable
internal fun DiscoverErrorState(
    message: String,
    kindLabel: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(Spacing.sm))
        Text("加载失败", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.xxs))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (kindLabel != null) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "错误类型：" + kindLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.height(Spacing.md))
        FilledTonalButton(onClick = onRetry) { Text("重试") }
    }
}

/** 空态：图标 + 标题 + 说明。 */
@Composable
internal fun DiscoverEmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.xxs))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 分页尾部三态：加载中 / 失败重试 / 已经到底了。都不满足时不占高度。 */
@Composable
internal fun DiscoverListFooter(
    loadingMore: Boolean,
    loadMoreError: String?,
    end: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        loadingMore -> Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }

        loadMoreError != null -> Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = loadMoreError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onRetry) { Text("重试") }
        }

        end -> Text(
            text = "已经到底了",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.md),
        )
    }
}

/** 骨架块：surfaceContainerHigh 脉冲；系统关动画时静态（Motion.isReducedMotion）。 */
@Composable
internal fun PulsePlaceholder(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radius.chip),
) {
    val reduced = Motion.isReducedMotion(LocalContext.current)
    val alpha = if (reduced) {
        0.9f
    } else {
        val transition = rememberInfiniteTransition(label = "discoverSkeleton")
        val value by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.55f,
            animationSpec = infiniteRepeatable(
                animation = Motion.pulse(),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "discoverSkeletonAlpha",
        )
        value
    }
    Box(
        modifier = modifier.background(
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha),
            shape = shape,
        ),
    )
}

/** list 版自动翻页：可见末项距底 < threshold 时触发（VM 内部再防重）。 */
@Composable
internal fun AutoLoadMoreEffect(
    listState: LazyListState,
    enabled: Boolean,
    threshold: Int = 3,
    onLoadMore: () -> Unit,
) {
    LaunchedEffect(listState, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to
                listState.layoutInfo.totalItemsCount
        }.collect { (lastVisible, total) ->
            if (lastVisible != null && total > 0 && lastVisible >= total - threshold) {
                onLoadMore()
            }
        }
    }
}

/** grid 版自动翻页（榜单用）。 */
@Composable
internal fun AutoLoadMoreGridEffect(
    gridState: LazyGridState,
    enabled: Boolean,
    threshold: Int = 4,
    onLoadMore: () -> Unit,
) {
    LaunchedEffect(gridState, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow {
            gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to
                gridState.layoutInfo.totalItemsCount
        }.collect { (lastVisible, total) ->
            if (lastVisible != null && total > 0 && lastVisible >= total - threshold) {
                onLoadMore()
            }
        }
    }
}

/**
 * 封面缩略图（书单 3 连图 / 榜单卡片共用）：ZlibCoverCache 内存+磁盘缓存。
 * 未命中/加载失败时显示主色块，不闪 spinner、不发多次请求。
 */
@Composable
internal fun AsyncZlibCover(
    coverUrl: String?,
    cacheKey: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radius.chip),
    targetWidth: Dp = 96.dp,
) {
    val context = LocalContext.current.applicationContext
    val targetWidthPx = with(LocalDensity.current) { targetWidth.roundToPx() }
    val bitmap by produceState<Bitmap?>(initialValue = null, coverUrl, cacheKey) {
        value = ZlibCoverCache.load(context, cacheKey, coverUrl, targetWidthPx)
    }
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
    ) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize())
        }
    }
}

/** 错误文案统一入口：数据层已是中文；兜底文案不暴露英文异常。 */
internal fun discoverErrorMessage(throwable: Throwable?): String =
    (throwable as? DiscoverException)?.message?.takeIf { it.isNotBlank() }
        ?: throwable?.message?.takeIf { it.isNotBlank() }
        ?: "加载失败，请稍后重试。"
