package app.particlebook.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Leaderboard
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.particlebook.android.zlib.ZAccount
import app.particlebook.android.zlib.ZBook
import app.particlebook.android.zlib.ZlibClient
import app.particlebook.android.zlib.ZlibSearchFilters
import app.particlebook.android.zlib.ZlibSortOrder
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Z-Library 页（P1a）：搜索 → 结果列表。
 *
 * 网络层由 zlib 包提供（ZlibClient.search 阻塞，ViewModel 内部放 IO 线程）；
 * 本页只做输入/状态机/列表，不造假书籍、不加载网络封面（结果用主色占位块 + 书名首字）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiscoverScreen(
    account: ZAccount?,
    onLoginClick: () -> Unit,
    onLogout: () -> Unit,
    onDownloadsClick: () -> Unit,
    onOpenDetail: (hash: String, slug: String?) -> Unit,
    onBooklistsClick: () -> Unit,
    onPopularClick: () -> Unit,
    onCategoriesClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var mirrorSheetOpen by rememberSaveable { mutableStateOf(false) }
    var filterSheetOpen by rememberSaveable { mutableStateOf(false) }
    var logoutDialogOpen by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    // ZlibClient 单例带 Application Context：镜像缓存（SharedPreferences）与 DEBUG 本地代理兜底才生效。
    val appContext = LocalContext.current.applicationContext
    val viewModel = remember(scope, appContext) {
        ZlibSearchViewModel(scope, ZlibClient.get(appContext))
    }
    val state = viewModel.state
    val listState = rememberLazyListState()
    // P0-1：列表底边留白要盖过胶囊上沿（124dp；200% 字号胶囊 72dp → 132dp）。
    val bottomInset = if (LocalDensity.current.fontScale >= 1.3f) {
        Spacing.listBottomInsetLarge
    } else {
        Spacing.listBottomInset
    }

    val runSearch: () -> Unit = {
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) {
            keyboard?.hide()
            viewModel.search(trimmed)
        }
    }

    // 滚到底自动翻页：effect 只在搜索词/分类变化时重启（翻页本身只改 page，不重启避免 layoutInfo 首帧读到旧值多发一页）。
    val autoPageQuery = (state as? ZlibSearchState.Success)?.query
    val autoPageCategory = (state as? ZlibSearchState.Success)?.category
    LaunchedEffect(listState, autoPageQuery, autoPageCategory) {
        if (autoPageQuery == null && autoPageCategory == null) return@LaunchedEffect
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to
                listState.layoutInfo.totalItemsCount
        }.collect { (lastVisible, total) ->
            if (lastVisible == null || total <= 0) return@collect
            val latest = viewModel.state as? ZlibSearchState.Success ?: return@collect
            if (latest.query != autoPageQuery || latest.category != autoPageCategory) return@collect
            // 列表 = 顶部说明项 + 书 + 尾部状态项；对不上说明是上一帧旧布局（刚翻页/刚换搜索词），跳过等下一帧。
            if (total != latest.books.size + 2) return@collect
            if (latest.page >= latest.totalPages) return@collect
            if (lastVisible >= total - 3) viewModel.loadNextPage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        // P1c：统一 Snackbar 底距（胶囊上沿 + 8dp = 132/140dp，已含系统导航条）。
        snackbarHost = { AppSnackbarHost(snackbarHostState, aboveCapsule = true) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.statusBars)
                // P1c：底部不再叠加 navigationBarsPadding()——各状态用自己的 bottomInset
                // （128/136dp，语义是距屏底总量）或 Snackbar 统一 helper 避让胶囊。
                .padding(horizontal = Spacing.screen),
        ) {
            Spacer(Modifier.height(Spacing.lg))
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Z-Library", style = AppType.pageTitle)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "内置书源 · 检索 · 下载",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AccountArea(
                    account = account,
                    onLoginClick = onLoginClick,
                    onDownloadsClick = onDownloadsClick,
                    onLogoutRequest = { logoutDialogOpen = true },
                )
                TopOverflowMenu(
                    onMirrorsClick = { mirrorSheetOpen = true },
                    onDownloadsClick = onDownloadsClick,
                )
            }
            // B5：首屏 NO_MIRROR 与「翻页时全线路失败」都算整体不可达，共用同一 banner。
            val errorState = state as? ZlibSearchState.Error
            val loadMoreNoMirror = (state as? ZlibSearchState.Success)
                ?.takeIf { it.loadMoreError != null && it.loadMoreKindLabel == "NO_MIRROR" }
            if (errorState != null && errorState.kindLabel == "NO_MIRROR") {
                Spacer(Modifier.height(Spacing.sm))
                NoMirrorBanner(
                    onRetry = { viewModel.retry() },
                    onSwitchLines = { mirrorSheetOpen = true },
                )
            } else if (loadMoreNoMirror != null) {
                Spacer(Modifier.height(Spacing.sm))
                NoMirrorBanner(
                    onRetry = { viewModel.retryLoadNextPage() },
                    onSwitchLines = { mirrorSheetOpen = true },
                )
            }
            Spacer(Modifier.height(Spacing.lg))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                placeholder = {
                    Text(
                        text = "书名 / 作者 / ISBN",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                // 用户 23:43 指令：不再用整宽按钮，搜索动作收进搜索框最右侧（48dp 触控区）。
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (query.isNotEmpty() && state !is ZlibSearchState.Loading) {
                            IconButton(
                                onClick = { query = "" },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "清除",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        val loading = state is ZlibSearchState.Loading
                        val enabled = query.isNotBlank() && !loading
                        IconButton(
                            onClick = runSearch,
                            enabled = enabled,
                            modifier = Modifier.size(48.dp),
                        ) {
                            if (loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Outlined.Search,
                                    contentDescription = "搜索",
                                    modifier = Modifier.size(24.dp),
                                    tint = if (enabled) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
                                    },
                                )
                            }
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            )
            // 删掉整宽按钮后，搜索框与「热门分类」之间只留这一个自然间距。
            Spacer(Modifier.height(Spacing.sm))

            // A5：筛选/排序入口（分类榜单模式隐藏——/s/<分类> 不参与筛选）。
            if (!viewModel.isCategoryMode) {
                val filters = viewModel.filters
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    FilterChip(
                        selected = filters.isActive,
                        onClick = { filterSheetOpen = true },
                        label = {
                            Text(
                                text = if (filters.isActive) "筛选 · 已启用" else "筛选",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Tune,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        modifier = Modifier.heightIn(min = 32.dp),
                    )
                    Text(
                        text = if (filters.isActive) {
                            zlibFilterSummary(filters)
                        } else {
                            "语言 / 格式 / 年份 / 排序"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (filters.isActive) {
                        TextButton(
                            onClick = { viewModel.clearFilters() },
                            contentPadding = PaddingValues(horizontal = Spacing.xs),
                            modifier = Modifier.heightIn(min = 32.dp),
                        ) {
                            Text("清除", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.xs))
            }

            when (val current = state) {
                is ZlibSearchState.Idle -> IdleContent(
                    category = category,
                    bottomInset = bottomInset,
                    onBooklistsClick = onBooklistsClick,
                    onPopularClick = onPopularClick,
                    onCategoriesClick = onCategoriesClick,
                    onCategoryClick = { term ->
                        // task-38：热门分类直接走 /s/<中文> 真实分类页，不再塞进关键词搜索。
                        category = term
                        query = ""
                        keyboard?.hide()
                        viewModel.searchCategory(term)
                    },
                    modifier = Modifier.weight(1f),
                )

                is ZlibSearchState.Loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            text = "正在搜索…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Spacing.xxs))
                        Text(
                            text = "正在连接镜像，首次可能需要几秒…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = Spacing.lg),
                        )
                    }
                }

                is ZlibSearchState.Empty -> EmptyResult(
                    categoryHint = current.category,
                    bottomInset = bottomInset,
                    onSearchCategory = { term ->
                        category = term
                        query = ""
                        keyboard?.hide()
                        viewModel.searchCategory(term)
                    },
                    modifier = Modifier.weight(1f),
                )

                is ZlibSearchState.Error -> SearchError(
                    message = current.message,
                    bottomInset = bottomInset,
                    kindLabel = current.kindLabel,
                    onRetry = { viewModel.retry() },
                    modifier = Modifier.weight(1f),
                )

                is ZlibSearchState.Success -> {
                    // 新查询/新分类的结果回来时回到列表顶部；翻页（同 query+category）不会重触发。
                    LaunchedEffect(current.query, current.category) { listState.scrollToItem(0) }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(bottom = bottomInset),
                    ) {
                        item(key = "summary") {
                            Text(
                                text = buildString {
                                    // task-38：分类搜素表头标明「分类：文学」，与关键词搜索区分。
                                    current.category?.let { append("分类：").append(it).append(" · ") }
                                    append("已加载 ").append(current.books.size).append(" 本")
                                    if (current.totalPages >= 1) {
                                        append(" · 第 ").append(current.page)
                                            .append(" / ").append(current.totalPages).append(" 页")
                                    }
                                    val activeFilters = viewModel.filters
                                    if (activeFilters.isActive) {
                                        append(" · 筛选：").append(zlibFilterSummary(activeFilters))
                                    }
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = Spacing.sm),
                            )
                        }
                        items(items = current.books, key = { book -> book.id }) { book ->
                            ZlibResultRow(
                                title = book.title,
                                author = book.author,
                                language = book.language,
                                extension = book.extension,
                                fileSize = book.fileSize,
                                coverUrl = resolveZlibCoverUrl(book.coverUrl, viewModel.mirrorBase),
                                coverKey = book.id,
                                rating = book.rating,
                                publisher = book.publisher,
                                onClick = {
                                    val ref = parseBookRef(book.href)
                                    if (ref != null) {
                                        onOpenDetail(ref.first, ref.second)
                                    } else {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("无法打开详情：链接格式异常")
                                        }
                                    }
                                },
                            )
                        }
                        item(key = "footer") {
                            when {
                                current.loadingMore -> Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(Spacing.md),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
    
                                current.loadMoreError != null -> Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = Spacing.sm),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        text = current.loadMoreError,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                    )
                                    // NO_MIRROR 的失败在顶部 banner 已给「切换线路」入口，尾部不重复放重试按钮。
                                    if (current.loadMoreKindLabel != "NO_MIRROR") {
                                        TextButton(onClick = { viewModel.retryLoadNextPage() }) {
                                            Text("重试")
                                        }
                                    }
                                }
    
                                current.page >= current.totalPages -> Text(
                                    text = "已经到底了",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = Spacing.md),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (mirrorSheetOpen) {
        ZlibMirrorSheet(
            client = ZlibClient.get(appContext),
            onDismiss = { mirrorSheetOpen = false },
            onSwitched = { host ->
                mirrorSheetOpen = false
                scope.launch { snackbarHostState.showSnackbar("已切换到 " + host) }
                val term = query.trim()
                if (term.isNotEmpty() && (
                        state is ZlibSearchState.Success ||
                            state is ZlibSearchState.Empty ||
                            state is ZlibSearchState.Error
                        )
                ) {
                    viewModel.search(term)
                } else if (term.isEmpty() && (state as? ZlibSearchState.Success)?.category != null) {
                    // 分类模式没有搜索词（query 为空）：切线路后重跑当前分类第一页，避免继续展示旧线路结果/旧封面基址。
                    viewModel.retry()
                }
            },
        )
    }

    if (filterSheetOpen) {
        SearchFilterSheet(
            initial = viewModel.filters,
            onDismiss = { filterSheetOpen = false },
            onApply = { filters ->
                filterSheetOpen = false
                keyboard?.hide()
                viewModel.applyFilters(filters)
            },
        )
    }

    if (logoutDialogOpen) {
        AlertDialog(
            onDismissRequest = { logoutDialogOpen = false },
            title = { Text("退出登录？") },
            text = { Text("退出后可以随时重新登录；已下载的书仍保留在书架。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        logoutDialogOpen = false
                        onLogout()
                        scope.launch { snackbarHostState.showSnackbar("已退出登录") }
                    },
                ) {
                    Text("退出登录")
                }
            },
            dismissButton = {
                TextButton(onClick = { logoutDialogOpen = false }) {
                    Text("取消")
                }
            },
        )
    }
}

/** 账号区（§4.1）：未登录 → 「登录」按钮；已登录 → 邮箱前缀 + 32dp 圆头像 + 菜单。 */
@Composable
private fun AccountArea(
    account: ZAccount?,
    onLoginClick: () -> Unit,
    onDownloadsClick: () -> Unit,
    onLogoutRequest: () -> Unit,
) {
    if (account == null) {
        TextButton(
            onClick = onLoginClick,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text("登录")
        }
        return
    }
    var menuOpen by remember { mutableStateOf(false) }
    val prefix = account.email?.substringBefore('@')?.take(24)?.takeIf { it.isNotBlank() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        if (prefix != null) {
            Text(
                text = prefix,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = Spacing.xs),
            )
        }
        Box {
            Surface(
                onClick = { menuOpen = true },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(32.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = prefix?.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("下载记录") },
                    onClick = {
                        menuOpen = false
                        onDownloadsClick()
                    },
                )
                DropdownMenuItem(
                    text = { Text("退出登录") },
                    onClick = {
                        menuOpen = false
                        onLogoutRequest()
                    },
                )
            }
        }
    }
}

/** 顶部溢出菜单（§4.7）：线路 / 下载记录（P2-4：避免菜单只有一项显得空）。 */
@Composable
private fun TopOverflowMenu(
    onMirrorsClick: () -> Unit,
    onDownloadsClick: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { open = true },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = "更多操作",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("线路") },
                onClick = {
                    open = false
                    onMirrorsClick()
                },
            )
            DropdownMenuItem(
                text = { Text("下载记录") },
                onClick = {
                    open = false
                    onDownloadsClick()
                },
            )
        }
    }
}

/**
 * 全线路失败 banner（§4.8）：首屏 NO_MIRROR 或翻页 NO_MIRROR 时出现，成功搜索后自然消失。
 * 两个动作竖排一行（重试 / 切换线路），避免 200% 字号时文案与按钮互相挤压。
 */
@Composable
private fun NoMirrorBanner(onRetry: () -> Unit, onSwitchLines: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(Radius.card),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(
                start = Spacing.md,
                end = Spacing.xs,
                top = Spacing.xs,
                bottom = Spacing.xs,
            ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(Spacing.xs))
                Text(
                    text = "所有 Z-Library 镜像暂时不可达，请稍后重试或手动切换线路",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onRetry) {
                    Text("重试")
                }
                TextButton(onClick = onSwitchLines) {
                    Text("切换线路")
                }
            }
        }
    }
}

/** 发现入口排布：<1.5 横排三列；>=1.5（200% 字号）纵排三行。 */
internal enum class DiscoverEntryLayout { ROW, COLUMN }

/** 纯函数（阈值 1.5，p15 §V9）；抽出来便于 JVM 单测，不依赖 Compose。 */
internal fun discoverEntryLayoutFor(fontScale: Float): DiscoverEntryLayout =
    if (fontScale >= 1.5f) DiscoverEntryLayout.COLUMN else DiscoverEntryLayout.ROW

/** 发现入口三张卡的静态内容；横排/纵排共用同一份点击动作。 */
private data class DiscoverEntry(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/**
 * 发现入口（P1.5 §7.1）：三张卡，图标 + 标题 + 说明。
 * V9：fontScale >= 1.5（200% 字号）时改为纵向三行——每卡整宽、高度随内容撑开、
 * 说明文字不截断、整卡可点且最小高度 48dp；100% 字号保持横排等高（IntrinsicSize.Min）。
 */
@Composable
private fun DiscoverEntryRow(
    onBooklistsClick: () -> Unit,
    onPopularClick: () -> Unit,
    onCategoriesClick: () -> Unit,
) {
    val entries = listOf(
        DiscoverEntry(
            title = "书单",
            subtitle = "编辑精选 / 用户书单",
            icon = Icons.AutoMirrored.Outlined.MenuBook,
            onClick = onBooklistsClick,
        ),
        DiscoverEntry(
            title = "榜单",
            subtitle = "Most Popular 100",
            icon = Icons.Outlined.Leaderboard,
            onClick = onPopularClick,
        ),
        DiscoverEntry(
            title = "分类",
            subtitle = "39 大类 / 802 子类",
            icon = Icons.Outlined.Category,
            onClick = onCategoriesClick,
        ),
    )
    when (discoverEntryLayoutFor(LocalDensity.current.fontScale)) {
        DiscoverEntryLayout.ROW -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            entries.forEach { entry ->
                DiscoverEntryCard(
                    entry = entry,
                    subtitleMaxLines = 2,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
        DiscoverEntryLayout.COLUMN -> Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            entries.forEach { entry ->
                DiscoverEntryCard(
                    entry = entry,
                    subtitleMaxLines = Int.MAX_VALUE,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                )
            }
        }
    }
}

@Composable
private fun DiscoverEntryCard(
    entry: DiscoverEntry,
    subtitleMaxLines: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = entry.onClick,
        modifier = modifier,
        shape = RoundedCornerShape(Radius.card),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(Spacing.sm)) {
            Icon(
                imageVector = entry.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = entry.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = subtitleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 初始态：发现入口 + 热门分类 + 最近搜索 + P1a 说明卡（不请求网络）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdleContent(
    category: String?,
    onCategoryClick: (String) -> Unit,
    onBooklistsClick: () -> Unit,
    onPopularClick: () -> Unit,
    onCategoriesClick: () -> Unit,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        // P1.5 §7.1：搜索框下方「发现」入口（书单 / 榜单 / 分类），在热门分类 chips 之上。
        Spacer(Modifier.height(Spacing.md))
        Text("发现", style = AppType.sectionTitle)
        Spacer(Modifier.height(Spacing.xs))
        DiscoverEntryRow(
            onBooklistsClick = onBooklistsClick,
            onPopularClick = onPopularClick,
            onCategoriesClick = onCategoriesClick,
        )
        Spacer(Modifier.height(Spacing.xl))
        Text("热门分类", style = AppType.sectionTitle)
        Spacer(Modifier.height(Spacing.xs))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            HotCategories.forEach { item ->
                FilterChip(
                    selected = category == item,
                    onClick = { onCategoryClick(item) },
                    label = {
                        Text(
                            text = item,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    },
                    modifier = Modifier.heightIn(min = 32.dp),
                )
            }
        }

        Spacer(Modifier.height(Spacing.xl))
        Text("最近搜索", style = AppType.sectionTitle)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "还没有搜索记录。搜索历史将在后续版本提供。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(Spacing.lg))
        Surface(
            shape = RoundedCornerShape(Radius.card),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(Spacing.md),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(Spacing.xs))
                Column {
                    Text("检索与下载已接入", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(Spacing.xxs))
                    Text(
                        text = "搜索结果来自 Z-Library 镜像；下载完成后会自动加入书架。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // P1c：底边留白覆盖胶囊上沿（128/136dp，距屏底总量）。
        Spacer(Modifier.height(bottomInset))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyResult(
    onSearchCategory: (String) -> Unit,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
    categoryHint: String? = null,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Outlined.SearchOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = categoryHint?.let { "分类「" + it + "」暂无结果" } ?: "没有找到相关书籍",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(Spacing.xxs))
            Text(
                text = "换个书名或作者再试试，也可以直接点热门分类：",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.md))
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(
                    space = Spacing.xs,
                    alignment = Alignment.CenterHorizontally,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                HotCategories.forEach { category ->
                    FilterChip(
                        selected = false,
                        onClick = { onSearchCategory(category) },
                        label = {
                            Text(
                                text = category,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        },
                        modifier = Modifier.heightIn(min = 32.dp),
                    )
                }
            }
            // P1c：空结果页可滚动，底部留白保证 chips 不被胶囊遮挡。
            Spacer(Modifier.height(bottomInset))
        }
    }
}

@Composable
private fun SearchError(
    message: String,
    kindLabel: String,
    bottomInset: Dp,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        // P1c：底部留白覆盖胶囊上沿（错误态无 bottomBar，视图延伸到屏底）。
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Spacing.md, end = Spacing.md, bottom = bottomInset),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(Spacing.sm))
        Text("搜索失败", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.xxs))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        // spec §11.6：副行不能暴露英文枚举；未知类型直接隐藏。
        val kindText = ZlibUiError.kindLabel(kindLabel)
        if (kindText != null) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = kindText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        FilledTonalButton(onClick = onRetry) { Text("重试") }
    }
}

/** 热门分类：静态标签，只做筛选高亮，不发任何请求。 */
private val HotCategories = listOf(
    "文学",
    "科幻",
    "历史",
    "计算机",
    "哲学",
    "艺术",
    "科学",
    "心理学",
)

// ---- A5 搜索筛选/排序 ----

/** 常用语言：value 必须用站点英文小写名（2026-11-04 实测；本地化显示名不能当请求值）。 */
private val TopLanguageOptions = listOf(
    "chinese" to "中文",
    "english" to "英语",
    "russian" to "俄语",
    "japanese" to "日语",
    "german" to "德语",
    "french" to "法语",
    "spanish" to "西班牙语",
    "italian" to "意大利语",
    "portuguese" to "葡萄牙语",
    "korean" to "韩语",
    "dutch" to "荷兰语",
    "turkish" to "土耳其语",
)

/** 常用格式：站点 extensions[] 用大写格式名。 */
private val FormatOptions = listOf(
    "PDF", "EPUB", "MOBI", "AZW3", "TXT", "FB2",
    "DJVU", "DJV", "LIT", "RTF", "AZW", "CBZ",
)

/** 语言值 → 中文标签（未知值原样显示，便于以后扩展取值清单）。 */
internal fun zlibLanguageLabel(value: String): String =
    TopLanguageOptions.firstOrNull { it.first == value }?.second ?: value

/** A5：筛选摘要（如「中文 · PDF · 2020–2024 · 最近添加」）；无筛选返回空串。 */
internal fun zlibFilterSummary(filters: ZlibSearchFilters): String {
    val parts = ArrayList<String>()
    filters.languages.forEach { parts.add(zlibLanguageLabel(it)) }
    if (filters.extensions.isNotEmpty()) parts.add(filters.extensions.joinToString("/"))
    val from = filters.yearFrom
    val to = filters.yearTo
    when {
        from != null && to != null -> parts.add("" + from + "–" + to)
        from != null -> parts.add("" + from + " 年后")
        to != null -> parts.add("" + to + " 年前")
    }
    if (filters.order != ZlibSortOrder.POPULAR) parts.add(filters.order.label)
    return parts.joinToString(" · ")
}

/** 多选 chip 组（语言/格式/排序共用）；选中值集合由调用方持有。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterChipGroup(
    options: List<Pair<String, String>>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value in selected,
                onClick = { onToggle(value) },
                label = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                },
                modifier = Modifier.heightIn(min = 32.dp),
            )
        }
    }
}

/** A5 筛选/排序弹层：语言、格式、年份区间、排序。应用后由 ViewModel 从第 1 页重搜。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SearchFilterSheet(
    initial: ZlibSearchFilters,
    onDismiss: () -> Unit,
    onApply: (ZlibSearchFilters) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var languages by remember(initial) { mutableStateOf(initial.languages.toSet()) }
    var extensions by remember(initial) { mutableStateOf(initial.extensions.toSet()) }
    var yearFrom by remember(initial) { mutableStateOf(initial.yearFrom?.toString() ?: "") }
    var yearTo by remember(initial) { mutableStateOf(initial.yearTo?.toString() ?: "") }
    var order by remember(initial) { mutableStateOf(initial.order) }
    val draft = ZlibSearchFilters(
        languages = languages.toList(),
        extensions = extensions.toList(),
        yearFrom = yearFrom.toIntOrNull(),
        yearTo = yearTo.toIntOrNull(),
        order = order,
    ).sanitized()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.lg),
        ) {
            Text("筛选与排序", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                text = "筛选由 Z-Library 服务端执行；年份留空表示不限。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.md))

            Text("语言", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(Spacing.xs))
            FilterChipGroup(
                options = TopLanguageOptions,
                selected = languages,
                onToggle = { value ->
                    languages = if (value in languages) languages - value else languages + value
                },
            )
            Spacer(Modifier.height(Spacing.md))

            Text("格式", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(Spacing.xs))
            FilterChipGroup(
                options = FormatOptions.map { it to it },
                selected = extensions,
                onToggle = { value ->
                    extensions = if (value in extensions) extensions - value else extensions + value
                },
            )
            Spacer(Modifier.height(Spacing.md))

            Text("出版年份", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(Spacing.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                OutlinedTextField(
                    value = yearFrom,
                    onValueChange = { value ->
                        yearFrom = value.filter { it.isDigit() }.take(4)
                    },
                    label = { Text("起始年") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = yearTo,
                    onValueChange = { value ->
                        yearTo = value.filter { it.isDigit() }.take(4)
                    },
                    label = { Text("截止年") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(Spacing.md))

            Text("排序", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(Spacing.xs))
            FilterChipGroup(
                options = ZlibSortOrder.values().map { it.value to it.label },
                selected = setOf(order.value),
                onToggle = { value ->
                    order = ZlibSortOrder.values().first { it.value == value }
                },
            )
            Spacer(Modifier.height(Spacing.lg))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        languages = emptySet()
                        extensions = emptySet()
                        yearFrom = ""
                        yearTo = ""
                        order = ZlibSortOrder.POPULAR
                    },
                ) {
                    Text("重置")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(Spacing.xs))
                Button(onClick = { onApply(draft) }) { Text("应用") }
            }
        }
    }
}
