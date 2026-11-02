package app.particlebook.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.particlebook.android.ui.cover.BookCover
import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.BookFormat
import app.particlebook.android.ui.data.ReadingProgress
import app.particlebook.android.ui.shelf.ShelfList
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
 * 书架（2026 观感版，§17.2.1）：
 * 透明顶栏 + 34sp Serif 大标题 + 继续阅读 Hero + 最近添加横滑 + 2 列 3:4 网格。
 * 排序/筛选收进 ModalBottomSheet；多选/搜索沿用 v3.1 交互，底部导航在 AppRoot（悬浮胶囊）。
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
    // 从阅读器返回时递增，用来重算 Hero 的「最近阅读」。
    progressEpoch: Int = 0,
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(defaultSort) }
    var formatFilter by rememberSaveable { mutableStateOf(FormatFilter.All) }
    var sortSheetOpen by rememberSaveable { mutableStateOf(false) }
    var formatDialogOpen by rememberSaveable { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    // 手机横屏（宽 ≥600dp 且高 <480dp）：A2 几何不变 —— 列表 + 左侧 NavigationRail（AppRoot）。
    val compactLandscape =
        configuration.screenWidthDp >= 600 && configuration.screenHeightDp < 480
    // 竖屏 2 列（411dp 宽 → 186dp 卡片）；宽屏（平板）自适应 186dp。
    val gridColumns = if (configuration.screenWidthDp >= 600) {
        GridCells.Adaptive(186.dp)
    } else {
        GridCells.Fixed(2)
    }

    val haptics = LocalHapticFeedback.current
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(searchActive) {
        if (searchActive) focusRequester.requestFocus()
    }

    val visible = remember(books, query, sort, formatFilter) {
        filterBooks(books = books, query = query, sort = sort, filter = formatFilter)
    }
    val filterActive = formatFilter != FormatFilter.All

    // Hero：有进度的书里挑最近读过的（last_read_<id>）；老数据没有时间戳时取第一本有进度的。
    val heroBook = remember(books, progressEpoch) {
        books.filter { ReadingProgress.hasProgress(context, it.id) }
            .maxByOrNull { ReadingProgress.lastReadAt(context, it.id) }
            ?: books.firstOrNull { ReadingProgress.hasProgress(context, it.id) }
    }
    val readingCount = remember(books, progressEpoch) {
        books.count { ReadingProgress.hasProgress(context, it.id) }
    }
    val recentBooks = remember(books) {
        books.sortedWith(
            compareByDescending<Book> { it.addedAt ?: "" }.thenByDescending { it.id },
        ).take(8)
    }

    // D1：滚动位置记忆。两套布局各存一份（竖屏网格的 item 结构与横屏列表不同，不能共用索引）。
    val gridState: LazyGridState = rememberLazyGridState()
    val listState: LazyListState = rememberLazyListState()
    var restoring by remember { mutableStateOf(false) }

    LaunchedEffect(compactLandscape) {
        val target = if (compactLandscape) ShelfScrollMemory.listIndex else ShelfScrollMemory.gridIndex
        val targetOffset = if (compactLandscape) {
            ShelfScrollMemory.listOffset
        } else {
            ShelfScrollMemory.gridOffset
        }
        restoring = true
        try {
            if (compactLandscape) {
                listState.requestScrollToItem(target.coerceAtLeast(0), targetOffset)
            } else {
                gridState.requestScrollToItem(target.coerceAtLeast(0), targetOffset)
            }
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
            if (compactLandscape) {
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            } else {
                gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
            }
        }.collect { (index, offset) ->
            if (restoring) return@collect
            if (compactLandscape) {
                ShelfScrollMemory.listIndex = index
                ShelfScrollMemory.listOffset = offset
            } else {
                ShelfScrollMemory.gridIndex = index
                ShelfScrollMemory.gridOffset = offset
            }
        }
    }

    // 顶栏滚动状态：>24dp 后容器变 surface@86% 并淡入 16sp 标题（§17.2.1）。
    val scrolled = remember { derivedStateOf { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 24 } }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            // §17.1.4：顶部 160dp 微渐变（surfaceDim → surface）画在 Scaffold 外层，顶栏透明叠在上面，
            // 避免顶栏与内容之间出现一条硬边（R2 / V1）。
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surfaceDim,
                        MaterialTheme.colorScheme.surface,
                    ),
                ),
            ),
        containerColor = Color.Transparent,
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
                    // 横屏高度有限：单行 64dp 顶栏，不折叠（spec §2.1.8 / A2）。
                    title = { Text("书架", style = MaterialTheme.typography.titleLarge) },
                    actions = {
                        LibraryTopBarActions(
                            onSearchClick = { searchActive = true },
                            onStartSelectionMode = onStartSelectionMode,
                            onRetry = onRetry,
                        )
                    },
                )

                else -> ShelfTopBar(
                    collapsed = scrolled.value,
                    onSearchClick = { searchActive = true },
                    onStartSelectionMode = onStartSelectionMode,
                    onRetry = onRetry,
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

                else -> {
                    // 「全部书籍」段头在网格里的下标（用于「全部 →」跳转）。
                    val allBooksHeaderIndex = 1 +
                        (if (heroBook != null) 1 else 0) +
                        (if (recentBooks.isNotEmpty()) 2 else 0)
                    LazyVerticalGrid(
                        columns = gridColumns,
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = Spacing.screen,
                            end = Spacing.screen,
                            top = Spacing.xxs,
                            bottom = Spacing.listBottomInset,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.gridColumn),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            ShelfTitleBlock(bookCount = books.size, readingCount = readingCount)
                        }
                        if (heroBook != null) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                ShelfHeroCard(
                                    book = heroBook,
                                    onOpen = { onOpenBook(heroBook) },
                                    modifier = Modifier.padding(bottom = Spacing.xs),
                                )
                            }
                        }
                        if (recentBooks.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                SectionHeader(
                                    title = "最近添加",
                                    actionLabel = "全部",
                                    onAction = {
                                        scope.launch {
                                            gridState.animateScrollToItem(allBooksHeaderIndex)
                                        }
                                    },
                                )
                            }
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                RecentBookRow(books = recentBooks, onOpenBook = onOpenBook)
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(
                                title = if (query.isNotBlank()) {
                                    "搜索结果 · " + visible.size + " 本"
                                } else {
                                    "全部书籍"
                                },
                                modifier = Modifier.padding(top = Spacing.xs),
                            )
                        }
                        if (filterActive) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                FilterActiveChip(
                                    label = "格式：" + formatFilter.label,
                                    onEdit = { sortSheetOpen = true },
                                    onClear = { formatFilter = FormatFilter.All },
                                )
                            }
                        }
                        gridItemsIndexed(visible, key = { _, book -> book.id }) { index, book ->
                            StaggeredAppear(index = index) {
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

// ---------------------------------------------------------------- 首页三段

/** 首屏前 8 项的 stagger 进入（§17.1.5）：延迟 35ms×index，淡入 + 上移 14dp；滚回不重播。 */
@Composable
private fun StaggeredAppear(index: Int, content: @Composable () -> Unit) {
    val animate = index < 8
    var appeared by remember { mutableStateOf(!animate) }
    LaunchedEffect(Unit) {
        if (animate) {
            delay(Motion.staggerDelay(index))
            appeared = true
        }
    }
    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = Motion.enterFade<Float>(),
        label = "staggerAlpha",
    )
    val shift by animateFloatAsState(
        targetValue = if (appeared) 0f else 14f,
        animationSpec = Motion.enterSpatial<Float>(),
        label = "staggerShift",
    )
    Box(
        Modifier.graphicsLayer {
            this.alpha = alpha
            translationY = shift * density
        },
    ) {
        content()
    }
}

/** 大标题区块：34sp Serif「书架」+「N 本书 · M 本在读」。 */
@Composable
private fun ShelfTitleBlock(bookCount: Int, readingCount: Int) {
    Column(
        Modifier.padding(top = Spacing.xs, bottom = Spacing.xxs),
    ) {
        Text("书架", style = AppType.pageTitle)
        Spacer(Modifier.height(2.dp))
        Text(
            text = bookCount.toString() + " 本书 · " + readingCount + " 本在读",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Hero「继续阅读」卡（§17.2.1 / §17.3 #1）：380×176dp、圆角 24、
 * 模糊封面底 + 60% 深色渐变、延续进度条（tertiary 橙）与右侧 92×123 封面。
 * API < 31 时 blur 自动降级（仍是封面 + 深色遮罩，可读性不变）。
 */
@Composable
private fun ShelfHeroCard(
    book: Book,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val fraction = ReadingProgress.fraction(context, book.id) ?: 0f
    val percentLabel = ReadingProgress.percentLabel(context, book.id) ?: ""
    val shape = RoundedCornerShape(Spacing.heroCorner)
    // V2：默认字号严格 176dp（462px），大字号才允许撑到 240dp，避免文字被裁同时又不能超验收高度。
    val largeFont = LocalDensity.current.fontScale >= 1.3f
    val heroHeight = if (largeFont) {
        // 大字号下内容更高：允许撑到 260dp，保证 48dp 按钮 + 单行标题都能放下。
        Modifier.heightIn(min = Spacing.heroHeight, max = 260.dp)
    } else {
        Modifier.height(Spacing.heroHeight)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(heroHeight)
            .clip(shape)
            .floatingShadow(shape)
            .clickable(onClick = onOpen)
            .semantics(mergeDescendants = true) {
                contentDescription = "继续阅读《" + book.title + "》，" + percentLabel + "，打开"
            },
    ) {
        // 模糊封面底（真实封面 / 程序化渐变都会作为底图）。
        Box(
            Modifier
                .matchParentSize()
                .adaptiveBlur(28.dp),
        ) {
            BookCover(
                book = book,
                modifier = Modifier.fillMaxSize(),
                shape = RectangleShape,
                compact = true,
            )
        }
        // 深色主题下模糊封面会发灰，再叠一层 40% 黑，保证 Hero 文字区仍是深底（V 验收 B3）。
        if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.40f)),
            )
        }
        // 60% 深色渐变遮罩（保证白字对比度）。
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.30f),
                            Color.Black.copy(alpha = 0.68f),
                        ),
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.md),
        ) {
            Column(Modifier.weight(1f)) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Text(
                        text = "继续阅读",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = book.title,
                    style = AppType.heroTitle,
                    color = Color.White,
                    // 固定高度（176dp，大字号最长 240dp）下只放得下一行标题；两行会把「继续阅读」按钮挤出可视区。
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(CircleShape),
                        color = MaterialTheme.colorScheme.tertiary,
                        trackColor = Color.White.copy(alpha = 0.24f),
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = percentLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.9f),
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(Spacing.xs))
                FilledTonalButton(
                    onClick = onOpen,
                    // 触控与可读性下限：大字号下也不允许被挤成细条。
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text("继续阅读 →", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(Spacing.sm))
            BookCover(
                book = book,
                modifier = Modifier
                    .width(Spacing.heroCoverWidth)
                    .height(Spacing.heroCoverHeight),
                shape = RoundedCornerShape(Radius.thumb),
            )
        }
    }
}

/** 「最近添加」横滑（§17.3 #2）：卡 132dp 宽、封面 132×176、圆角 20，右侧自然露出下一张。 */
@Composable
private fun RecentBookRow(
    books: List<Book>,
    onOpenBook: (Book) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        lazyItems(books, key = { it.id }) { book ->
            Column(
                Modifier
                    .width(Spacing.recentCardWidth)
                    .clickable { onOpenBook(book) }
                    .semantics(mergeDescendants = true) {
                        contentDescription = "《" + book.title + "》，" + book.authorLabel
                    },
            ) {
                BookCover(
                    book = book,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Spacing.recentCoverHeight),
                    shape = RoundedCornerShape(Radius.card),
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = book.title,
                    style = AppType.bookTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = book.authorLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 顶部透明栏：初始完全透明（大标题在内容里），滚动 >24dp 后 surface@86% + 16sp 标题淡入。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShelfTopBar(
    collapsed: Boolean,
    onSearchClick: () -> Unit,
    onStartSelectionMode: () -> Unit,
    onRetry: () -> Unit,
) {
    val container = if (collapsed) {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.86f)
    } else {
        Color.Transparent
    }
    val titleAlpha by animateFloatAsState(
        targetValue = if (collapsed) 1f else 0f,
        animationSpec = Motion.enterFade<Float>(),
        label = "shelfTopBarTitle",
    )
    TopAppBar(
        title = {
            Text(
                text = "书架",
                style = AppType.topBarTitle,
                modifier = Modifier.alpha(titleAlpha),
            )
        },
        actions = {
            LibraryTopBarActions(
                onSearchClick = onSearchClick,
                onStartSelectionMode = onStartSelectionMode,
                onRetry = onRetry,
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = container),
    )
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

/** 顶栏右侧两个动作（搜索 / 更多）；竖屏透明顶栏与横屏 64dp TopAppBar 共用。 */
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

/**
 * 书架滚动位置记忆（进程级）。
 *
 * 两套布局（竖屏网格 / 横屏列表）的 item 结构不同，各存一份 index+offset；
 * 旋转时恢复「目标布局上次停留的位置」。
 */
private object ShelfScrollMemory {
    var gridIndex: Int = 0
    var gridOffset: Int = 0
    var listIndex: Int = 0
    var listOffset: Int = 0
}
