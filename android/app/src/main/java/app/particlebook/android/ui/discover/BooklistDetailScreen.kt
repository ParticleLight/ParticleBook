package app.particlebook.android.ui.discover

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.particlebook.android.R
import app.particlebook.android.ui.Spacing
import app.particlebook.android.ui.ZlibResultRow
import app.particlebook.android.ui.parseBookRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * P1.5 §7.3 书单详情（route booklist/{id}/{hash}/{slug}）：首屏 21 本 + 头部元信息 + 本地排序。
 *
 * 降级说明（spec §6）：站点“加载更多”接口本阶段未抓包，[BooklistDetailData.hasMore] 只用于
 * 控制按钮位置——这里直接隐藏“加载更多”，只展示首屏 21 本；头部仍保留「共 N 本」真实总数。
 */
private class BooklistDetailViewModel(
    private val scope: CoroutineScope,
    private val repository: DiscoverRepository,
) {
    sealed interface State {
        data object Loading : State
        data class Error(val message: String, val kindLabel: String?) : State
        data class Ready(
            val detail: BooklistDetailData,
            val reversed: Boolean = false,
        ) : State {
            val displayBooks get() = if (reversed) detail.books.reversed() else detail.books
        }
    }

    var state by mutableStateOf<State>(State.Loading)
        private set

    private var job: Job? = null
    private var loadedHref: String? = null

    fun load(href: String) {
        val current = state
        if (loadedHref == href && current is State.Ready) return
        loadedHref = href
        job?.cancel()
        job = scope.launch {
            state = State.Loading
            try {
                state = State.Ready(repository.booklistDetail(href))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                state = State.Error(
                    message = discoverErrorMessage(throwable),
                    kindLabel = kindLabelOf(throwable),
                )
            }
        }
    }

    fun retry(href: String) {
        loadedHref = null
        load(href)
    }

    fun toggleSort() {
        val current = state as? State.Ready ?: return
        state = current.copy(reversed = !current.reversed)
    }
}

@Composable
fun BooklistDetailScreen(
    repository: DiscoverRepository,
    href: String,
    onBack: () -> Unit,
    onOpenBook: (hash: String, slug: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember(scope, repository) { BooklistDetailViewModel(scope, repository) }
    val state = viewModel.state
    // C11 / P15-V11：字号放大后「书籍」与排序触发件不再挤同一行——触发件换到下一行独占宽度，
    // 文案只换行、不截断（旧实现把整组控件换成底部 sheet，现已由下拉菜单取代）。
    val largeFont = LocalDensity.current.fontScale >= 1.3f
    LaunchedEffect(href) { viewModel.load(href) }
    val fallbackTitle = stringResource(R.string.discover_booklists_title)
    val unknownAuthor = stringResource(R.string.common_unknown_author)
    val newestFirst = stringResource(R.string.discover_sort_newest)
    val oldestFirst = stringResource(R.string.discover_sort_oldest)

    DiscoverPageScaffold(
        title = (state as? BooklistDetailViewModel.State.Ready)?.detail?.title ?: fallbackTitle,
        onBack = onBack,
        modifier = modifier,
    ) { innerPadding ->
        when (val current = state) {
            is BooklistDetailViewModel.State.Loading -> DiscoverLoading(
                message = stringResource(R.string.discover_booklist_loading),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            is BooklistDetailViewModel.State.Error -> DiscoverErrorState(
                message = current.message,
                kindLabel = current.kindLabel,
                onRetry = { viewModel.retry(href) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            is BooklistDetailViewModel.State.Ready -> {
                val detail = current.detail
                var descExpanded by rememberSaveable(detail.id) { mutableStateOf(false) }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(
                        start = Spacing.screen,
                        end = Spacing.screen,
                        bottom = Spacing.md,
                    ),
                ) {
                    item(key = "header") {
                        Column(Modifier.padding(top = Spacing.md, bottom = Spacing.sm)) {
                            Text(
                                text = detail.title,
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                text = detail.author.ifBlank { unknownAuthor },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(Spacing.xxs))
                            Text(
                                text = stringResource(
                                    R.string.discover_booklist_meta_format,
                                    detail.booksCount,
                                    detail.commentsCount,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (detail.description.isNotBlank()) {
                                Spacer(Modifier.height(Spacing.sm))
                                Text(
                                    text = detail.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = if (descExpanded) Int.MAX_VALUE else 4,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (detail.description.length > 120) {
                                    TextButton(onClick = { descExpanded = !descExpanded }) {
                                        Text(
                                            stringResource(
                                                if (descExpanded) {
                                                    R.string.common_collapse
                                                } else {
                                                    R.string.common_expand
                                                },
                                            ),
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(Spacing.sm))
                            // 2026-11 组件基线：Segmented button 已废弃（component-selection.md 的
                            // Deprecated map / m3-expressive.md 的 Deprecated baselines），替代品
                            // connected button group 在 material3 1.4.0 没有公开组件。书单排序是
                            // 「命令 + 两个方向」，用文本按钮 + 下拉菜单表达（选中项带 check）。
                            val onSortSelected: (Boolean) -> Unit = { reversed ->
                                if (reversed != current.reversed) viewModel.toggleSort()
                            }
                            if (largeFont) {
                                // 大字号：标题与触发件上下排，触发件独占一行宽度，
                                // 「排序：新→旧」不与「书籍」抢位置，也不靠 ellipsis 收尾。
                                Column(Modifier.fillMaxWidth()) {
                                    Text(
                                        text = stringResource(R.string.discover_booklist_books_header),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    BooklistSortMenu(
                                        reversed = current.reversed,
                                        newestLabel = newestFirst,
                                        oldestLabel = oldestFirst,
                                        onSelect = onSortSelected,
                                    )
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(R.string.discover_booklist_books_header),
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    BooklistSortMenu(
                                        reversed = current.reversed,
                                        newestLabel = newestFirst,
                                        oldestLabel = oldestFirst,
                                        onSelect = onSortSelected,
                                    )
                                }
                            }
                        }
                    }

                    if (current.displayBooks.isEmpty()) {
                        item(key = "empty") {
                            DiscoverEmptyState(
                                icon = Icons.AutoMirrored.Outlined.MenuBook,
                                title = stringResource(R.string.discover_booklist_empty_title),
                                message = stringResource(R.string.discover_booklist_empty_message),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(320.dp),
                            )
                        }
                    } else {
                        items(items = current.displayBooks, key = { it.id }) { book ->
                            ZlibResultRow(
                                title = book.title,
                                author = book.author,
                                language = book.language,
                                extension = book.extension,
                                fileSize = book.fileSize,
                                onClick = {
                                    parseBookRef(book.href)?.let { (hash, slug) ->
                                        onOpenBook(hash, slug)
                                    }
                                },
                            )
                        }
                        // 降级（spec §6）：加载更多接口未抓包 → 只展示首屏 21 本，不渲染“加载更多”。
                    }
                }
            }
        }
    }
}

/**
 * 书单排序触发件（2026-11 基线）：「排序：新→旧 ▾」文本按钮 + 两项下拉菜单。
 *
 * 弃用理由：Segmented button 已在 component-selection.md 的 Deprecated map 与 m3-expressive.md 的
 * Deprecated baselines 里标为「不再推荐」，替代品 connected button group 在 material3 1.4.0 里
 * 没有公开组件（Kotlin 侧不可引用）。这里只有两个方向，且它是一次「命令」而不是筛选条件，
 * 菜单比按钮组更贴语义；选中项用 check + selected 语义标出，点当前项只收起菜单、不翻转。
 */
@Composable
private fun BooklistSortMenu(
    reversed: Boolean,
    newestLabel: String,
    oldestLabel: String,
    onSelect: (reversed: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val triggerLabel = stringResource(
        R.string.discover_booklist_sort_format,
        if (reversed) oldestLabel else newestLabel,
    )
    Box(modifier) {
        TextButton(
            onClick = { menuOpen = true },
            modifier = Modifier.heightIn(min = Spacing.minTouchTarget),
        ) {
            Text(text = triggerLabel)
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                // 装饰：按钮文案已经是「排序：新→旧」，读屏不必再念一遍箭头。
                contentDescription = null,
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            SortMenuItem(
                label = newestLabel,
                selected = !reversed,
                onClick = {
                    menuOpen = false
                    onSelect(false)
                },
            )
            SortMenuItem(
                label = oldestLabel,
                selected = reversed,
                onClick = {
                    menuOpen = false
                    onSelect(true)
                },
            )
        }
    }
}

/**
 * 菜单里的一个排序方向。
 *
 * - 选中态视觉：前置 check 图标（M3 单选菜单的惯例）；未选中项用同宽占位，两行文案左缘才对齐。
 * - 选中态读屏：selected = true 语义，TalkBack 会念「已选择」，不必为它新增字符串资源。
 */
@Composable
private fun SortMenuItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text = label) },
        onClick = onClick,
        modifier = if (selected) Modifier.semantics { this.selected = true } else Modifier,
        leadingIcon = {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    // 装饰：选中语义已由 selected 语义承担，图标不重复播报。
                    contentDescription = null,
                )
            } else {
                // 占位：24dp 是 M3 菜单前置图标尺寸，只为对齐，不是间距节奏，故不进 Spacing。
                Spacer(Modifier.size(SortMenuIconSlot))
            }
        },
    )
}

/** 菜单前置图标占位（M3 菜单图标 24dp）。 */
private val SortMenuIconSlot = 24.dp
