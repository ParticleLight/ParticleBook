package app.particlebook.android.ui.shelf

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.particlebook.android.ui.FilterActiveChip
import app.particlebook.android.ui.Radius
import app.particlebook.android.ui.Spacing
import app.particlebook.android.ui.cover.BookCover
import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.ReadingProgress

/**
 * 横屏书架列表（spec §2.1.8 C 方案）。
 *
 * 手机横屏可用高度只有 400dp 出头，2:3 封面网格放不下一整行；改成「缩略图 + 两行文本」
 * 的紧凑行，行高固定 92dp，配左侧 NavigationRail（见 AppRoot）。
 */

/** 行高：spec §2.1.8 定死 92dp（411 - 24 - 64 - 48 - 24 ≈ 251dp 可视 → 约 2.7 行完整可见）。 */
val ShelfRowHeight = 92.dp

/** 缩略图 48×72dp（2:3），4dp 圆角。 */
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
            .heightIn(min = 48.dp),
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

/** 单行：48×72 缩略图 + 书名（1 行）+「作者 · 格式 · 已读 xx%」（1 行）。无卡片、无分割线。 */
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
    val meta = listOfNotNull(
        book.authorLabel,
        book.format.label,
        // 视觉上无进度不写「未读」（spec §2.1.3），只拼「已读 xx%」。
        ReadingProgress.percentLabel(context, book.id),
    ).joinToString(" · ")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ShelfRowHeight)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            // 整行合并成一个语义节点：TalkBack 一次读出「书名，作者，格式」。
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(ShelfThumbWidth)
                .height(ShelfThumbHeight)
                .clip(RoundedCornerShape(Radius.thumb)),
        ) {
            // 横屏列表也用真实封面（PDF 首页 / EPUB OPF 封面），无封面时 GradientCover 兜底。
            BookCover(
                book = book,
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(Radius.thumb),
                compact = true,
            )
            if (selected) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)),
                )
            }
        }
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = meta,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selectionMode) {
            Spacer(Modifier.width(Spacing.xs))
            Icon(
                imageVector = if (selected) {
                    Icons.Filled.CheckCircle
                } else {
                    Icons.Outlined.RadioButtonUnchecked
                },
                contentDescription = if (selected) "已选中" else "未选中",
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** 横屏书架主体：段头（+生效中的筛选 chip）+ 书籍行；底部留 96dp 给 FAB。 */
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
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.screen,
            end = Spacing.screen,
            bottom = Spacing.listBottomInset,
        ),
    ) {
        item(key = "shelf-header") {
            Column {
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
