package app.particlebook.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.BookFormat
import app.particlebook.android.ui.shelf.ShelfList
import kotlinx.coroutines.flow.collect

/** 排序方式；评分排序需要书评数据，P0 只占位。 */
enum class LibrarySort(val label: String) {
    RecentRead("最近阅读"),
    AddedAt("添加时间"),
    Title("书名"),
    Author("作者"),
    Rating("评分"),
}

/** 格式筛选（单项）。 */
enum class FormatFilter(val label: String) {
    All("全部"),
    Epub("EPUB"),
    Pdf("PDF"),
}

/**
 * 书架：MediumTopAppBar + 自适应网格 + Extended FAB。
 * 排序/筛选收进 ModalBottomSheet，只有筛选生效时才出现一行可关闭的 FilterChip。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    books: List<Book>,
    loading: Boolean,
    error: String?,
    selectionMode: Boolean,
    selectedIds: Set<Long>,
    onOpenBook: (Book) -> Unit,
    onEnterSelection: (Book) -> Unit,
    onToggleSelection: (Book) -> Unit,
    onSelectAll: () -> Unit,
    onExitSelection: () -> Unit,
    onStartSelectionMode: () -> Unit,
    onImportClick: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    // 设置里的「默认排序」；书架自己仍可在排序 sheet 里临时改。
    defaultSort: LibrarySort = LibrarySort.RecentRead,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(defaultSort) }
    var formatFilter by rememberSaveable { mutableStateOf(FormatFilter.All) }
    var sortSheetOpen by rememberSaveable { mutableStateOf(false) }
    var formatDialogOpen by rememberSaveable { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    // 手机横屏（宽 ≥600dp 且高 <480dp）：可用高度只剩 400dp 出头，2:3 封面网格放不下完整首行，
    // 改走「列表 + 左侧 NavigationRail」（spec §2.1.8 C 方案）。竖屏/平板高度够，仍用网格。
    val compactLandscape =
        configuration.screenWidthDp >= 600 && configuration.screenHeightDp < 480
    val gridCells = GridCells.Adaptive(148.dp)

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val haptics = LocalHapticFeedback.current
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(searchActive) {
        if (searchActive) focusRequester.requestFocus()
    }

    val visible = remember(books, query, sort, formatFilter) {
        filterBooks(books = books, query = query, sort = sort, filter = formatFilter)
    }
    val filterActive = formatFilter != FormatFilter.All

    // §9.2 D1（首版复测 FAIL 后按 verify 定位重做）：两个布局各存一份索引，切换布局时只把
    // 「另一个布局」的已存索引恢复过来；恢复的那几帧冻结快照记录 —— 否则新布局首帧的
    // firstVisibleItemIndex = 0 会把目标索引冲掉，scrollToItem 的恢复值等于没生效。
    // 两套布局 item 0 都是段头、书籍顺序同源（visible），索引 1:1 互用。
    val gridState: LazyGridState = rememberLazyGridState()
    val listState: LazyListState = rememberLazyListState()
    var portraitIndex by rememberSaveable { mutableIntStateOf(0) }
    var landscapeIndex by rememberSaveable { mutableIntStateOf(0) }
    // true = 正在恢复位置，此期间 snapshotFlow 不写回（避免首帧 0 覆盖）。
    var restoring by remember { mutableStateOf(false) }

    LaunchedEffect(compactLandscape) {
        // 恢复的是「刚离开的那个布局」记下的位置：进横屏用竖屏索引，回竖屏用横屏索引，
        // 这样两种旋转都停在读者刚才看的那本书附近。
        val target = if (compactLandscape) portraitIndex else landscapeIndex
        restoring = true
        try {
            if (compactLandscape) {
                listState.scrollToItem(target)
            } else {
                gridState.scrollToItem(target)
            }
            // 等新布局至少完成一次测量，再解除冻结。
            withFrameNanos { }
            withFrameNanos { }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (ignored: Exception) {
            // 索引超出当前列表（例如刚删过书）时忽略，从当前位置开始即可。
        } finally {
            restoring = false
        }
    }

    LaunchedEffect(compactLandscape) {
        snapshotFlow {
            if (compactLandscape) listState.firstVisibleItemIndex else gridState.firstVisibleItemIndex
        }.collect { index ->
            if (restoring) return@collect
            if (compactLandscape) {
                landscapeIndex = index
            } else {
                portraitIndex = index
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            when {
                selectionMode -> ContextualTopBar(
                    selectedCount = selectedIds.size,
                    onClose = onExitSelection,
                    onSelectAll = onSelectAll,
                )

                searchActive -> SearchTopBar(
                    query = query,
                    onQueryChange = { query = it },
                    onClose = {
                        searchActive = false
                        query = ""
                    },
                    focusRequester = focusRequester,
                    onSearch = { keyboard?.hide() },
                )

                compactLandscape -> TopAppBar(
                    // 横屏高度有限：单行 64dp 顶栏，不折叠（spec §2.1.8）。
                    title = { Text("书架", style = MaterialTheme.typography.titleLarge) },
                    actions = {
                        LibraryTopBarActions(
                            onSearchClick = { searchActive = true },
                            onStartSelectionMode = onStartSelectionMode,
                            onRetry = onRetry,
                        )
                    },
                )

                else -> MediumTopAppBar(
                    title = {
                        val collapsed = scrollBehavior.state.collapsedFraction > 0.5f
                        Text(
                            text = "书架",
                            style = if (collapsed) {
                                MaterialTheme.typography.titleLarge
                            } else {
                                MaterialTheme.typography.headlineSmall
                            },
                        )
                    },
                    actions = {
                        LibraryTopBarActions(
                            onSearchClick = { searchActive = true },
                            onStartSelectionMode = onStartSelectionMode,
                            onRetry = onRetry,
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        floatingActionButton = {
            // 空态/骨架/错误态里已有自己的动作入口，避免「导入」重复出现（§14.2 S2-④）。
            if (!selectionMode && books.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onImportClick,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("导入") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // spec §5.3：insets 只消费一次（外层 Scaffold 已置 0），避免 sheet/dialog 开合时内容跳动。
                .consumeWindowInsets(innerPadding),
        ) {
            when {
                loading && books.isEmpty() -> LibrarySkeleton()

                error != null && books.isEmpty() -> LibraryError(error, onRetry)

                books.isEmpty() -> EmptyLibrary(
                    onImport = onImportClick,
                    onFormats = { formatDialogOpen = true },
                )

                visible.isEmpty() -> NoMatches(
                    onClear = {
                        query = ""
                        formatFilter = FormatFilter.All
                    },
                )

                compactLandscape -> ShelfList(
                    books = visible,
                    headerTitle = when {
                        query.isNotBlank() -> "搜索结果"
                        filterActive -> "筛选结果"
                        else -> "全部书籍"
                    },
                    headerCount = if (query.isNotBlank() || filterActive) visible.size else books.size,
                    filterLabel = if (filterActive) formatFilter.label else null,
                    selectionMode = selectionMode,
                    selectedIds = selectedIds,
                    onOpenBook = onOpenBook,
                    onEnterSelection = onEnterSelection,
                    onToggleSelection = onToggleSelection,
                    onSortClick = { sortSheetOpen = true },
                    onEditFilter = { sortSheetOpen = true },
                    onClearFilter = { formatFilter = FormatFilter.All },
                    state = listState,
                )

                else -> LazyVerticalGrid(
                    columns = gridCells,
                    state = gridState,
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(
                        start = Spacing.screen,
                        end = Spacing.screen,
                        bottom = Spacing.listBottomInset,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.gridColumn),
                    verticalArrangement = Arrangement.spacedBy(Spacing.gridRow),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = Spacing.sectionTop, bottom = Spacing.sectionBottom),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = when {
                                        query.isNotBlank() -> "搜索结果 · " + visible.size + " 本"
                                        filterActive -> "筛选结果 · " + visible.size + " 本"
                                        else -> "全部书籍 · " + books.size + " 本书"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { sortSheetOpen = true }) {
                                    Icon(Icons.Outlined.Sort, contentDescription = "排序与筛选")
                                }
                            }
                            if (filterActive) {
                                FilterActiveChip(
                                    label = "格式：" + formatFilter.label,
                                    onEdit = { sortSheetOpen = true },
                                    onClear = { formatFilter = FormatFilter.All },
                                )
                            }
                        }
                    }
                    items(visible, key = { book -> book.id }) { book ->
                        BookGridItem(
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
        }
    }

    if (sortSheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { sortSheetOpen = false },
            sheetState = sheetState,
            // spec §5.3：sheet 不接管 insets，底部由内容自己 navigationBarsPadding()。
            contentWindowInsets = { WindowInsets(0) },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(bottom = Spacing.md),
            ) {
                Text(
                    text = "排序与筛选",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.xs),
                )
                Text(
                    text = "排序",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.xs),
                )
                LibrarySort.entries.forEach { option ->
                    val enabled = option != LibrarySort.Rating
                    ListItem(
                        headlineContent = {
                            Text(
                                text = option.label + if (enabled) "" else "（后续版本）",
                                color = if (enabled) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                },
                            )
                        },
                        leadingContent = {
                            RadioButton(
                                selected = sort == option,
                                onClick = null,
                                enabled = enabled,
                            )
                        },
                        modifier = Modifier.clickable(enabled = enabled) { sort = option },
                    )
                }
                Text(
                    text = "格式",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.xs),
                )
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.screen),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    FormatFilter.entries.forEach { option ->
                        FilterChip(
                            selected = formatFilter == option,
                            onClick = { formatFilter = option },
                            label = { Text(option.label) },
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.screen),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            sort = LibrarySort.RecentRead
                            formatFilter = FormatFilter.All
                        },
                    ) { Text("重置") }
                    Spacer(Modifier.width(Spacing.xs))
                    FilledTonalButton(onClick = { sortSheetOpen = false }) { Text("完成") }
                }
            }
        }
    }

    if (formatDialogOpen) {
        AlertDialog(
            onDismissRequest = { formatDialogOpen = false },
            title = { Text("支持的格式") },
            text = {
                Text(
                    "EPUB、PDF、MOBI、TXT、FB2、CBZ、CBR、HTML、Markdown。" +
                        "P0 原生可读 PDF；EPUB 等通过「兼容模式」打开。",
                )
            },
            confirmButton = {
                TextButton(onClick = { formatDialogOpen = false }) { Text("知道了") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextualTopBar(
    selectedCount: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "退出选择")
            }
        },
        title = {
            Text(
                text = "已选 " + selectedCount + " 项",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        },
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Outlined.SelectAll, contentDescription = "全选")
            }
        },
    )
}

@Composable
private fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    focusRequester: FocusRequester,
    onSearch: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(64.dp)
                .padding(end = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出搜索")
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                shape = CircleShape,
                placeholder = {
                    Text(
                        text = "搜索书名、作者、标签",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
            )
        }
    }
}

@Composable
internal fun FilterActiveChip(
    label: String,
    onEdit: () -> Unit,
    onClear: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(bottom = Spacing.xs)) {
        FilterChip(
            selected = true,
            onClick = onEdit,
            label = { Text(label) },
            leadingIcon = {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "清除筛选",
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(onClick = onClear),
                )
            },
        )
    }
}

/** 顶栏右侧两个动作（搜索 / 更多）；竖屏 MediumTopAppBar 与横屏 64dp TopAppBar 共用。 */
@Composable
private fun LibraryTopBarActions(
    onSearchClick: () -> Unit,
    onStartSelectionMode: () -> Unit,
    onRetry: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    IconButton(onClick = onSearchClick) {
        Icon(Icons.Outlined.Search, contentDescription = "搜索")
    }
    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作")
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text("选择书籍") },
                onClick = {
                    menuOpen = false
                    onStartSelectionMode()
                },
            )
            DropdownMenuItem(
                text = { Text("刷新书架") },
                onClick = {
                    menuOpen = false
                    onRetry()
                },
            )
            DropdownMenuItem(
                text = { Text("阅读统计") },
                onClick = {},
                enabled = false,
            )
            DropdownMenuItem(
                text = { Text("下载历史") },
                onClick = {},
                enabled = false,
            )
        }
    }
}

/** 过滤 + 排序；空查询返回全部。 */
private fun filterBooks(
    books: List<Book>,
    query: String,
    sort: LibrarySort,
    filter: FormatFilter,
): List<Book> {
    val keyword = query.trim().lowercase()
    val filtered = books.filter { book ->
        val formatOk = when (filter) {
            FormatFilter.All -> true
            FormatFilter.Epub -> book.format == BookFormat.Epub
            FormatFilter.Pdf -> book.format == BookFormat.Pdf
        }
        val queryOk = keyword.isEmpty() ||
            book.title.lowercase().contains(keyword) ||
            book.authorLabel.lowercase().contains(keyword) ||
            book.tags.any { it.lowercase().contains(keyword) }
        formatOk && queryOk
    }
    return when (sort) {
        LibrarySort.RecentRead, LibrarySort.AddedAt -> filtered.sortedByDescending { it.id }
        LibrarySort.Title -> filtered.sortedBy { it.title }
        LibrarySort.Author -> filtered.sortedBy { it.authorLabel }
        LibrarySort.Rating -> filtered
    }
}
