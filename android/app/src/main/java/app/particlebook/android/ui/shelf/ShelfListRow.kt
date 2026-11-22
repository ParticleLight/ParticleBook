package app.particlebook.android.ui.shelf

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.particlebook.android.ui.FilterActiveChip
import app.particlebook.android.ui.Spacing
import app.particlebook.android.ui.cover.BookCover
import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.ReadingProgress

/**
 * 横屏书架列表（spec §2.1.8 C 方案）。
 *
 * 手机横屏可用高度只有 400dp 出头，2:3 封面网格放不下一整行；改成「缩略图 + 两行文本」
 * 的紧凑行，行高 92dp，配左侧 NavigationRail（见 AppRoot）。
 */

/** 行高：spec §2.1.8 定死 92dp（411 - 24 - 64 - 48 - 24 ≈ 251dp 可视 → 约 2.7 行完整可见）。 */
val ShelfRowHeight = 92.dp

/** 缩略图 48×72dp（2:3），圆角取 MaterialTheme.shapes.small（8dp）。 */
val ShelfThumbWidth = 48.dp
val ShelfThumbHeight = 72.dp

/** 段头单行 48dp：标题 + 计数 + 排序入口（竖屏网格的段头是 24/8 两段留白，横屏压扁成一行）。 */
@Composable
fun ShelfListHeader(
    title: String,
    count: Int,
    onSortClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.minTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = MaterialTheme.typography.labelLarge)
        Text(
            text = " · " + count + " 本书",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onSortClick) {
            Icon(Icons.Outlined.Sort, contentDescription = "排序与筛选")
        }
    }
}

/**
 * 单行（M3 [ListItem]）：48×72 缩略图（leading）+ 书名（headline）+「作者 · 格式 · 已读 xx%」（supporting）。
 *
 * 选中态用 secondaryContainer / onSecondaryContainer 成对落地；行本身没有 Card 底色，
 * 只有被选中时铺一层二级容器——列表行不是独立卡片，不给每行套 Card。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShelfListRow(
    book: Book,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    // 200% 字号：行高改为下限，书名/元数据允许两行，宁可撑高也不截断。
    val largeFont = LocalDensity.current.fontScale >= 1.3f
    val thumbShape = MaterialTheme.shapes.small
    val meta = listOfNotNull(
        book.authorLabel,
        book.format.label,
        // 视觉上无进度不写「未读」（spec §2.1.3），只拼「已读 xx%」。
        ReadingProgress.percentLabel(context, book.id),
    ).joinToString(" · ")
    // 选中时文本用配对内容色；未选中用常规正文色（不依赖 ListItem 默认色，它不读 contentColor）。
    val headlineColor = if (selected) colors.onSecondaryContainer else colors.onSurface
    val supportingColor = if (selected) {
        colors.onSecondaryContainer.copy(alpha = 0.80f)
    } else {
        colors.onSurfaceVariant
    }
    val readAloud = "《" + book.title + "》" +
        (if (meta.isEmpty()) "" else "，" + meta) +
        (if (selectionMode) (if (selected) "，已选中" else "，未选中") else "")

    Surface(
        color = if (selected) colors.secondaryContainer else Color.Transparent,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (largeFont) {
                    Modifier.heightIn(min = ShelfRowHeight)
                } else {
                    Modifier.height(ShelfRowHeight)
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            // 整行合并成一个语义节点：TalkBack 一次读完书名/元数据，多选时补状态。
            .semantics(mergeDescendants = true) { contentDescription = readAloud },
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = book.title,
                    color = headlineColor,
                    maxLines = if (largeFont) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                Text(
                    text = meta,
                    color = supportingColor,
                    maxLines = if (largeFont) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = {
                Box(
                    modifier = Modifier
                        .width(ShelfThumbWidth)
                        .height(ShelfThumbHeight),
                ) {
                    // 横屏列表也用真实封面（PDF 首页 / EPUB OPF 封面），无封面时 GradientCover 兜底。
                    // 装饰图：书名由整行语义朗读，封面图本身 contentDescription = null。
                    BookCover(
                        book = book,
                        modifier = Modifier.fillMaxSize(),
                        shape = thumbShape,
                        compact = true,
                    )
                }
            },
            trailingContent = if (selectionMode) {
                {
                    Icon(
                        imageVector = if (selected) {
                            Icons.Filled.CheckCircle
                        } else {
                            Icons.Outlined.RadioButtonUnchecked
                        },
                        // 装饰：选中状态已在整行语义里（readAloud 追加「已选中/未选中」）。
                        contentDescription = null,
                        tint = if (selected) colors.onSecondaryContainer else colors.outline,
                        modifier = Modifier.size(Spacing.md + Spacing.xs),
                    )
                }
            } else {
                null
            },
        )
    }
}

/** 横屏书架主体：段头（+生效中的筛选 chip）+ 书籍行；底部按胶囊上沿留白（128dp / 200% 136dp）。 */
@Composable
fun ShelfList(
    books: List<Book>,
    headerTitle: String,
    headerCount: Int,
    filterLabel: String?,
    selectionMode: Boolean,
    selectedIds: Set<Long>,
    onOpenBook: (Book) -> Unit,
    onEnterSelection: (Book) -> Unit,
    onToggleSelection: (Book) -> Unit,
    onSortClick: () -> Unit,
    onEditFilter: () -> Unit,
    onClearFilter: () -> Unit,
    modifier: Modifier = Modifier,
    // 滚动状态由调用方持有：横屏/竖屏两条分支共用同一份「保存索引」，旋转后能回到原位置（§9.2 D1）。
    state: LazyListState = rememberLazyListState(),
) {
    val haptics = LocalHapticFeedback.current
    val bottomInset = if (LocalDensity.current.fontScale >= 1.3f) {
        Spacing.listBottomInsetLarge
    } else {
        Spacing.listBottomInset
    }
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            // 行内缩进由 ListItem 自带的 16dp 内容边距承担（与竖屏网格的 screen 边距对齐）；
            // 这里不再叠加横向 padding，否则行缩进 32dp、选中底色也会缩在半空。
            bottom = bottomInset,
        ),
    ) {
        item(key = "shelf-header") {
            Column(Modifier.padding(horizontal = Spacing.screen)) {
                ShelfListHeader(
                    title = headerTitle,
                    count = headerCount,
                    onSortClick = onSortClick,
                )
                if (filterLabel != null) {
                    FilterActiveChip(
                        label = "格式：" + filterLabel,
                        onEdit = onEditFilter,
                        onClear = onClearFilter,
                    )
                }
            }
        }
        items(books, key = { book -> book.id }) { book ->
            ShelfListRow(
                book = book,
                onClick = {
                    if (selectionMode) onToggleSelection(book) else onOpenBook(book)
                },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (selectionMode) onToggleSelection(book) else onEnterSelection(book)
                },
                selectionMode = selectionMode,
                selected = book.id in selectedIds,
            )
        }
    }
}
