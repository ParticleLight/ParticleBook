package app.particlebook.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.particlebook.android.reader.text.TextImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 缩放下限：再小就是「画框比画还大」，没有可看的内容。 */
private const val MinImageScale = 1f

/** 缩放上限 4 倍：插图的解码上限只有一屏像素，再放大只是把马赛克放大。 */
private const val MaxImageScale = 4f

/** 双击落在 1 倍与 2.5 倍之间：2.5 倍够看清整栏插图里的细节，又不至于一屏只剩几个笔画。 */
private const val DoubleTapImageScale = 2.5f

/** 超过这个缩放就不再响应「左右滑切换」：那时的水平拖动是在平移图片，不是翻页。 */
private const val SwipeSwitchMaxScale = 1.05f

/** 水平快滑的切换阈值：低于 96dp 多半是想点按或者误触。 */
private val SwipeSwitchThreshold = 96.dp

/**
 * 全屏图片查看器：从正文里点开一张插图，按「书内插图的顺序」前后翻。
 *
 * 为什么是 Dialog 而不是二级页：查看器是临时浮层，关掉就回到原来的滚动位置；走 AppRoot 的
 * 返回栈会把阅读器压成 0 约束，回来还要恢复位置，收益为零。
 *
 * 三处刻意的取舍：
 * - 背景纯黑、不套 M3 surface：图片查看器的黑是画框，跟随主题会在深色模式之外出现色偏，
 *   也会把扫描件的底色和界面底色混在一起。
 * - 解码上限取屏幕最长边，而不是正文的 1600px：正文限宽是为了内存；全屏时 1600px 放大两倍
 *   就是像素块，这里按屏幕取，一张图最多占一屏像素。
 * - 缩放/平移直接写 float 状态，不做 animate*AsState：手势回调每帧都在写新值，动画会让
 *   图像位置落后手指一到两帧，松手后还得再追一次。
 *
 * @param images 书内所有插图（与正文同一次遍历的结果），查看器按这个顺序前后翻。
 * @param initialIndex 打开时停在哪一张（0 起算）；越界会夹到最近的一张。
 * @param onDismiss 关闭回调；返回键与关闭按钮都走它，查看器自己不收尾别的东西。
 */
@Composable
internal fun TextImageViewer(
    images: List<TextImage>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    if (images.isEmpty()) return
    var index by remember(images) { mutableIntStateOf(initialIndex.coerceIn(images.indices)) }

    // 返回键挂在宿主组合上：Dialog 的内容跑在独立窗口的子组合里，不保证拿得到
    // OnBackPressedDispatcherOwner；dialog 窗口自己那次返回由 dismissOnBackPress 兜住。
    // 两条路都只调 onDismiss —— 查看器没有需要在这里收尾的播放/计时任务。
    BackHandler(onBack = onDismiss)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            // 默认宽度是给对话框用的窗口边距，图片查看器要整块屏幕。
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            // 全屏覆盖，没有「点外面」可点；开着它反而会在点黑边时误关。
            dismissOnClickOutside = false,
        ),
    ) {
        // dialog 是独立 window：活动窗口的沉浸设置不会带过去，这里在 dialog 窗口里再隐藏一次；
        // 退出时不动活动窗口，阅读器自己的沉浸状态原样保留。
        ImmersiveDialogWindow()
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            val density = LocalDensity.current
            // 解码上限 = 屏幕最长边（px）。传 0 会让 sampleSizeFor 退化成「按原图解码」。
            val maxEdgePx = with(density) { maxOf(maxWidth, maxHeight).roundToPx() }.coerceAtLeast(1)
            val current = images[index]
            // 换图就重建整页：缩放、平移、解码结果都属于「这张图」，用 key 一起归零，
            // 比每个状态各写一次重置少一处漏写的可能。
            key(current.key) {
                ImageViewerPage(
                    image = current,
                    position = index,
                    total = images.size,
                    maxEdgePx = maxEdgePx,
                    onDismiss = onDismiss,
                    onPrevious = { if (index > 0) index -= 1 },
                    onNext = { if (index < images.lastIndex) index += 1 },
                )
            }
        }
    }
}

/**
 * 一张图 + 它自己的手势与顶栏。
 *
 * 解码、缩放、平移都只服务当前这张图，所以整页由 [TextImageViewer] 用 key 重建，
 * 这里不必再关心「换图时哪个状态忘了重置」。
 */
@Composable
private fun ImageViewerPage(
    image: TextImage,
    position: Int,
    total: Int,
    maxEdgePx: Int,
    onDismiss: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    var loadState by remember { mutableStateOf(TextImageLoadState()) }
    // IO 线程：读盘 + BitmapFactory 都是同步阻塞的，放主线程会卡掉一次转场。
    LaunchedEffect(image.key, maxEdgePx) {
        loadState = withContext(Dispatchers.IO) { decodeTextImage(image, maxEdgePx) }
    }

    var scale by remember { mutableFloatStateOf(MinImageScale) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    // 手势检测器只在「张数」变化时重启：缩放值用 rememberUpdatedState 读当前值，
    // 否则每帧缩放都会重挂 pointerInput，正在进行的那串手势会被直接取消。
    val currentScale = rememberUpdatedState(scale)
    // Fit 之后图片实际占的尺寸：平移余量按它算，按整个容器算会把竖图拖进上下黑边。
    val drawnSize = rememberUpdatedState(
        loadState.bitmap?.let { bitmap ->
            fittedImageSize(IntSize(bitmap.width, bitmap.height), viewSize)
        } ?: IntSize.Zero,
    )

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        // 还没解出图时缩放没有意义，却会把 scale 顶过 1.05、顺手废掉左右滑切换。
        if (loadState.bitmap != null) {
            val next = (scale * zoomChange).coerceIn(MinImageScale, MaxImageScale)
            scale = next
            offset = if (next <= MinImageScale) {
                // 回到 1 倍就没有可平移的余量：不归零的话，图片会偏在一边且拖不回来。
                Offset.Zero
            } else {
                clampImagePan(offset + panChange, next, viewSize, drawnSize.value)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(image.key) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (loadState.bitmap != null) {
                                // 只看「现在是不是 1 倍」：捏合停在 1.7 倍时双击先回 1 倍，
                                // 再双击才放大，和相册的习惯一致。
                                scale = if (scale <= MinImageScale) DoubleTapImageScale else MinImageScale
                                offset = Offset.Zero
                            }
                        },
                    )
                }
                .pointerInput(total) {
                    detectSwipeSwitch(
                        enabled = { total > 1 && currentScale.value <= SwipeSwitchMaxScale },
                        thresholdPx = SwipeSwitchThreshold.toPx(),
                        onSwipeLeft = onNext,
                        onSwipeRight = onPrevious,
                    )
                }
                .transformable(transformState),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = loadState.bitmap
            when {
                bitmap != null -> Image(
                    bitmap = bitmap,
                    contentDescription = image.alt.takeIf { it.isNotBlank() },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )

                loadState.failed -> ImageUnavailable(alt = image.alt)
                else -> ImageLoading()
            }
        }

        ViewerTopBar(
            position = position,
            total = total,
            onDismiss = onDismiss,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // 切换按钮压在图片左右边缘：单张图时不出现，边界那一侧置灰而不是消失——
        // 位置固定，连点两次不会因为按钮消失而点到图上。
        if (total > 1) {
            ViewerNavButton(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "上一张",
                enabled = position > 0,
                onClick = onPrevious,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = Spacing.xs),
            )
            ViewerNavButton(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "下一张",
                enabled = position < total - 1,
                onClick = onNext,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = Spacing.xs),
            )
        }
    }
}

/** 顶栏：左关闭、中间计数（只有一张时不显示计数）。 */
@Composable
private fun ViewerTopBar(
    position: Int,
    total: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            // 白图标压在浅色扫描件上会看不见：顶部一道很淡的黑色渐变只为可读性，不做装饰。
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent),
                ),
            )
            .statusBarsPadding()
            .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
    ) {
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "关闭",
                tint = Color.White,
            )
        }
        if (total > 1) {
            Text(
                text = (position + 1).toString() + " / 共 " + total + " 张",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** 上一张 / 下一张：半透明底圈保证箭头在任何底图上都看得见。 */
@Composable
private fun ViewerNavButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.background(Color.Black.copy(alpha = 0.32f), CircleShape),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) Color.White else Color.White.copy(alpha = 0.38f),
        )
    }
}

/** 解码中：沿用统一进度组件的 indeterminate 形态，而不是旧的两套进度条 token。 */
@Composable
private fun ImageLoading() {
    LinearWavyProgressIndicator(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xxl)
            .semantics { contentDescription = "正在加载图片" },
        color = Color.White,
        trackColor = Color.White.copy(alpha = 0.24f),
    )
}

/** 解码失败：占位文案 + alt，至少告诉用户「这一页本来有张图」。 */
@Composable
private fun ImageUnavailable(alt: String) {
    Column(
        modifier = Modifier.padding(horizontal = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text = "这张图片无法显示",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        if (alt.isNotBlank()) {
            Text(
                text = alt,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 单指水平快滑切换：抬手时位移超过 [thresholdPx] 才算，双指参与过就整段作废。
 *
 * 没用 detectHorizontalDragGestures：它在 change 被消费后立即取消，而 transformable 一过
 * 触摸斜率就会消费拖动（哪怕当前是 1 倍、画面并不动），两者叠在一起会一次都收不到。
 * 这里只读 position、从不消费事件，因此和缩放、双击互不干扰。
 */
private suspend fun PointerInputScope.detectSwipeSwitch(
    enabled: () -> Boolean,
    thresholdPx: Float,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } > 1) continue
            val down = event.changes.firstOrNull { it.changedToDown() } ?: continue
            var latestX = down.position.x
            var multiTouch = false
            var pressed = true
            while (pressed) {
                val next = awaitPointerEvent()
                if (next.changes.count { it.pressed } > 1) multiTouch = true
                val change = next.changes.firstOrNull { it.id == down.id } ?: break
                latestX = change.position.x
                pressed = change.pressed
            }
            if (multiTouch || !enabled()) continue
            val dx = latestX - down.position.x
            when {
                // 手指往左划 = 看下一张，与翻页的直觉一致。
                dx <= -thresholdPx -> onSwipeLeft()
                dx >= thresholdPx -> onSwipeRight()
            }
        }
    }
}

/**
 * ContentScale.Fit 之后图片在容器里实际占的尺寸。
 *
 * 任一边未知（还没量到 / 还没解码）时返回 [IntSize.Zero]，此时平移余量为 0。
 */
private fun fittedImageSize(content: IntSize, container: IntSize): IntSize {
    if (content.width <= 0 || content.height <= 0 || container.width <= 0 || container.height <= 0) {
        return IntSize.Zero
    }
    val fit = minOf(
        container.width.toFloat() / content.width,
        container.height.toFloat() / content.height,
    )
    return IntSize((content.width * fit).roundToInt(), (content.height * fit).roundToInt())
}

/**
 * 把平移夹在「放大后多出来的那一半」里。
 *
 * 内容铺满容器时正好是 (scale - 1) * 尺寸 / 2；竖图上下有留白，那部分不算可拖动区域，
 * 所以按 [drawn] 而不是整个容器算——上限只会更小，不会出现「拖到只剩黑边」。
 */
private fun clampImagePan(
    candidate: Offset,
    scale: Float,
    container: IntSize,
    drawn: IntSize,
): Offset {
    if (scale <= MinImageScale || drawn.width <= 0 || drawn.height <= 0) return Offset.Zero
    val maxX = ((scale * drawn.width - container.width) / 2f).coerceAtLeast(0f)
    val maxY = ((scale * drawn.height - container.height) / 2f).coerceAtLeast(0f)
    return Offset(
        candidate.x.coerceIn(-maxX, maxX),
        candidate.y.coerceIn(-maxY, maxY),
    )
}
