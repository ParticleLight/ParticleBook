package app.particlebook.android.ui

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.scale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.particlebook.android.ui.cover.BookCover
import app.particlebook.android.ui.cover.rememberCoverTint
import app.particlebook.android.ui.cover.GeneratedCoverStack
import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.ReadingProgress

/**
 * 多选态的封面覆盖层：选中时整块淡染（secondaryContainer）+ 左上角 24dp 圆形角标（选中打勾）。
 *
 * 书架网格卡与「最近添加」横滑卡共用同一套——两处若各画一遍，迟早一处打勾、一处只剩淡染。
 * 视觉角标 24dp，真正的手势目标是整张卡片（≫48dp），角标本身不可点、不单独朗读。
 */
@Composable
internal fun BoxScope.SelectionOverlay(selected: Boolean) {
    if (selected) {
        // 选中态用 secondaryContainer 承载「已选择」语义（与角标同族，不再用 primary*）。
        Box(
            Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.32f)),
        )
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xs),
        contentAlignment = Alignment.TopStart,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                    },
                )
                .border(
                    width = 2.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    // 装饰：选中状态已写进卡片语义（a11yLabel 追加「已选中」）。
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * 2026 网格卡（§17.3 #5）：3:4 封面（MaterialTheme.shapes.medium 圆角 + 封面彩色投影）+ 卡下 3 行文字。
 * 卡本身无底色、无边框、无 elevation——封面即视觉主体；格式信息并入元数据行。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookGridItem(
    book: Book,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
) {
    val context = LocalContext.current
    // spec §2.1.3 / §7.3：视觉上无进度只显示格式（不写「未读」），TalkBack 末位才补「未读」。
    val progressLabel = ReadingProgress.percentLabel(context, book.id)
    val a11yStatus = progressLabel ?: "未读"
    // 200% 字号：书名/作者/元数据允许多行——宁可卡片变高，也不在放大字号时截断文字。
    val largeFont = LocalDensity.current.fontScale >= 1.3f
    val coverShape = MaterialTheme.shapes.medium
    // 封面色投影跟随封面配色（容器角色），不再是独立调色板。
    val shadowTint = rememberCoverTint(book).container
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = Motion.press<Float>(),
        label = "cardPress",
    )
    Column(
        modifier = modifier
            .scale(pressScale)
            .semantics(mergeDescendants = true) {
                contentDescription = book.a11yLabel(a11yStatus, selectionMode, selected)
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .coverShadow(shape = coverShape, tint = shadowTint)
                .clip(coverShape)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            BookCover(book = book, modifier = Modifier.fillMaxSize(), shape = coverShape)
            if (selectionMode) {
                SelectionOverlay(selected = selected)
            }
        }

        Spacer(Modifier.height(Spacing.cardTitleGap))
        Text(
            text = book.title,
            style = AppType.bookTitle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (largeFont) 3 else 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Spacing.xxs))
        Text(
            text = book.authorLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (largeFont) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Spacing.xxs))
        Text(
            // 一行元数据：格式 · 进度（§17.1.2：不再把格式/进度拆成多行小字）。
            text = if (progressLabel == null) {
                book.format.label
            } else {
                book.format.label + " · " + progressLabel
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (largeFont) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun Book.a11yLabel(progress: String, selectionMode: Boolean, selected: Boolean): String {
    // progress 已保证非空：「已读 45%」或「未读」。
    val builder = StringBuilder("《").append(title).append("》")
    builder.append("，").append(authorLabel)
    builder.append("，").append(format.label)
    builder.append("，").append(progress)
    // 多选态：视觉上是角标，读屏只能靠文字，必须把「已选中/未选中」补进描述。
    if (selectionMode) builder.append(if (selected) "，已选中" else "，未选中")
    return builder.toString()
}

/** 区块标题（§17.3 #8）：Headline 22 Serif + 可选右侧文本按钮。 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Spacing.screen, end = Spacing.xs, top = Spacing.lg, bottom = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = AppType.sectionTitle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * 状态骨架（空态 / 无匹配 / 错误 / 阅读器提示共用）：
 * 可选插画或图标 → 标题 → 说明 → 动作。
 *
 * 排版、字级、间距只在这里定义一次；各状态只差文案与动作，避免三处各写一套留白。
 * 颜色一律由调用方成对传入容器 + on* 角色（或留 Unspecified 继承 Surface 的 contentColor）。
 */
@Composable
private fun StateBlock(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    centered: Boolean = true,
    icon: ImageVector? = null,
    leadingArt: (@Composable () -> Unit)? = null,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
    titleColor: Color = Color.Unspecified,
    messageColor: Color = Color.Unspecified,
    iconTint: Color = Color.Unspecified,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = if (centered) Arrangement.Center else Arrangement.Top,
    ) {
        if (leadingArt != null) {
            leadingArt()
            Spacer(Modifier.height(Spacing.lg))
        }
        if (icon != null) {
            Icon(
                imageVector = icon,
                // 装饰：状态含义由下面的标题与说明承载，图标不单独朗读。
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(Spacing.xxl),
            )
            Spacer(Modifier.height(if (centered) Spacing.md else Spacing.xs))
        }
        Text(text = title, style = titleStyle, color = titleColor)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = messageColor,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        )
        if (action != null) {
            Spacer(Modifier.height(Spacing.lg))
            action()
        }
    }
}

/** 空书架：主动作导入，次动作打开「支持的格式」说明 dialog。 */
@Composable
fun EmptyLibrary(
    onImport: () -> Unit,
    onFormats: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StateBlock(
        title = "书架是空的",
        message = "从手机里导入 EPUB / PDF，就会出现在这里。",
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        titleStyle = MaterialTheme.typography.headlineSmall,
        messageColor = MaterialTheme.colorScheme.onSurfaceVariant,
        leadingArt = { GeneratedCoverStack() },
        action = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilledTonalButton(onClick = onImport) { Text("导入") }
                TextButton(onClick = onFormats) { Text("支持的格式") }
            }
        },
    )
}

/** 有书但被搜索/筛选清空。 */
@Composable
fun NoMatches(onClear: () -> Unit, modifier: Modifier = Modifier) {
    StateBlock(
        title = "没有匹配的书",
        message = "换个关键词，或清除筛选条件",
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        icon = Icons.Outlined.SearchOff,
        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
        messageColor = MaterialTheme.colorScheme.onSurfaceVariant,
        action = { TextButton(onClick = onClear) { Text("清除筛选") } },
    )
}

/** 内联错误块（不是全屏错误页），保留周围上下文；颜色成对走 errorContainer / onErrorContainer。 */
@Composable
fun LibraryError(message: String?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.screen),
    ) {
        StateBlock(
            title = "书架加载失败",
            message = message ?: "未知错误",
            modifier = Modifier.padding(Spacing.md),
            centered = false,
            icon = Icons.Outlined.ErrorOutline,
            titleColor = MaterialTheme.colorScheme.onErrorContainer,
            messageColor = MaterialTheme.colorScheme.onErrorContainer,
            iconTint = MaterialTheme.colorScheme.onErrorContainer,
            action = { FilledTonalButton(onClick = onRetry) { Text("重试") } },
        )
    }
}

/** 骨架屏：按新书架结构（Hero + 最近添加 + 网格）占位，alpha 1.0 ↔ 0.6 脉冲 1100ms；系统关动画时静态。 */
@Composable
fun LibrarySkeleton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val animate = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
    }
    val alpha = if (animate) {
        val transition = rememberInfiniteTransition(label = "skeleton")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.6f,
            animationSpec = infiniteRepeatable(
                animation = Motion.pulse(),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "skeletonAlpha",
        ).value
    } else {
        1f
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(148.dp),
        contentPadding = PaddingValues(
            start = Spacing.screen,
            end = Spacing.screen,
            bottom = Spacing.listBottomInset,
        ),
        horizontalArrangement = Arrangement.spacedBy(Spacing.gridColumn),
        verticalArrangement = Arrangement.spacedBy(Spacing.gridRow),
        userScrollEnabled = false,
        modifier = modifier,
    ) {
        // Hero 占位（与真实 Hero 同高同圆角）。
        item(span = { GridItemSpan(maxLineSpan) }) {
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Spacing.heroHeight),
                shape = MaterialTheme.shapes.extraLarge,
                alpha = alpha,
            )
        }
        // 大标题占位。
        item(span = { GridItemSpan(maxLineSpan) }) {
            SkeletonBlock(
                modifier = Modifier
                    .width(132.dp)
                    .height(30.dp),
                shape = MaterialTheme.shapes.small,
                alpha = alpha,
            )
        }
        // 最近添加横滑占位（3 张，宽度与真实卡一致）。
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                repeat(3) { index ->
                    Column(Modifier.width(Spacing.recentCardWidth)) {
                        SkeletonBlock(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(Spacing.recentCoverHeight),
                            shape = MaterialTheme.shapes.large,
                            alpha = alpha,
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        SkeletonBlock(
                            modifier = Modifier
                                .fillMaxWidth(0.78f)
                                .height(14.dp),
                            shape = MaterialTheme.shapes.extraSmall,
                            alpha = alpha,
                        )
                    }
                }
            }
        }
        // 「全部书籍」段头占位。
        item(span = { GridItemSpan(maxLineSpan) }) {
            SkeletonBlock(
                modifier = Modifier
                    .width(112.dp)
                    .height(28.dp),
                shape = MaterialTheme.shapes.small,
                alpha = alpha,
            )
        }
        items(6) { SkeletonCard(alpha = alpha) }
    }
}

@Composable
private fun SkeletonBlock(
    modifier: Modifier,
    shape: androidx.compose.ui.graphics.Shape,
    alpha: Float,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha)),
    )
}

@Composable
private fun SkeletonCard(alpha: Float) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha)),
        )
        Spacer(Modifier.height(Spacing.coverToTitle))
        Box(
            Modifier
                .fillMaxWidth(0.82f)
                .height(14.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha)),
        )
        Spacer(Modifier.height(Spacing.xxs))
        Box(
            Modifier
                .fillMaxWidth(0.45f)
                .height(12.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha)),
        )
    }
}

/** 阅读器通用状态块（居中图标 + 标题 + 说明 + 动作）；与书架空态/错误态共用 [StateBlock]。 */
@Composable
fun ReaderMessage(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val label = actionLabel
    val callback = onAction
    val action: (@Composable () -> Unit)? = if (label != null && callback != null) {
        { Button(onClick = callback) { Text(label) } }
    } else {
        null
    }
    StateBlock(
        title = title,
        message = message,
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        messageColor = MaterialTheme.colorScheme.onSurfaceVariant,
        action = action,
    )
}
