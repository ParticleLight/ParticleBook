package app.particlebook.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.particlebook.android.zlib.ZAccount
import app.particlebook.android.zlib.ZBook
import app.particlebook.android.zlib.ZlibClient
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
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var mirrorSheetOpen by rememberSaveable { mutableStateOf(false) }
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

    // 滚到底自动翻页：只有还有下一页、且没在加载 / 没失败时才触发（失败由尾部重试行接管）。
    LaunchedEffect(listState, state) {
        val success = state as? ZlibSearchState.Success ?: return@LaunchedEffect
        if (success.page >= success.totalPages) return@LaunchedEffect
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to
                listState.layoutInfo.totalItemsCount
        }.collect { (lastVisible, total) ->
            if (lastVisible != null && total > 0 && lastVisible >= total - 3) {
                viewModel.loadNextPage()
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .navigationBarsPadding()
                    // P0-1：Snackbar 底边要落在胶囊上方（132dp = 胶囊上沿 124 + 8）。
                    .padding(bottom = 132.dp),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.statusBars)
                // 底部预留系统导航条：悬浮导航胶囊叠在内容之上，列表/空态不能钻到它下面。
                .navigationBarsPadding()
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
            val errorState = state as? ZlibSearchState.Error
            if (errorState != null && errorState.kindLabel == "NO_MIRROR") {
                Spacer(Modifier.height(Spacing.sm))
                NoMirrorBanner(onSwitchLines = { mirrorSheetOpen = true })
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

            when (val current = state) {
                is ZlibSearchState.Idle -> IdleContent(
                    category = category,
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
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = Spacing.sm),
                            )
                        }
                        items(items = current.books, key = { book -> book.id }) { book ->
                            ZlibResultRow(
                                book = book,
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
                                    TextButton(onClick = { viewModel.retryLoadNextPage() }) {
                                        Text("重试")
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
                }
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

/** 全线路失败 banner（§4.8）：只在 NO_MIRROR 错误态出现，成功搜索后自然消失。 */
@Composable
private fun NoMirrorBanner(onSwitchLines: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(Radius.card),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = Spacing.md, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            TextButton(onClick = onSwitchLines) {
                Text("切换线路")
            }
        }
    }
}

/** 从结果 href（/book/<hash>/<slug>.html）解析详情页参数。 */
private fun parseBookRef(href: String): Pair<String, String?>? {
    val segments = runCatching {
        android.net.Uri.parse(href.trim()).pathSegments
    }.getOrNull() ?: return null
    val bookIndex = segments.indexOf("book")
    if (bookIndex < 0 || bookIndex + 1 >= segments.size) return null
    val hash = segments[bookIndex + 1].takeIf { it.isNotBlank() } ?: return null
    val slug = segments.getOrNull(bookIndex + 2)
        ?.removeSuffix(".html")
        ?.takeIf { it.isNotBlank() }
    return hash to slug
}

/** 初始态：热门分类 + 最近搜索 + P1a 说明卡（不请求网络）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdleContent(
    category: String?,
    onCategoryClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
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
        Spacer(Modifier.height(Spacing.listBottomInset))
    }
}

/** 结果行：44×60dp 主色占位块（书名首字）+ 标题 2 行 + 元信息 1 行，整行 ≥72dp。 */
@Composable
private fun ZlibResultRow(book: ZBook, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 60.dp)
                .clip(RoundedCornerShape(Spacing.xs))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = book.title.firstOrNull()?.toString() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.size(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = zlibMetaLine(book),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 作者 · 语言 · 格式 · 大小（缺省字段跳过；作者为空显示「未知作者」）。 */
private fun zlibMetaLine(book: ZBook): String {
    val parts = ArrayList<String>(4)
    parts += book.author.takeIf { it.isNotBlank() } ?: "未知作者"
    if (book.language.isNotBlank()) parts += book.language
    if (book.extension.isNotBlank()) parts += book.extension.uppercase()
    if (book.fileSize.isNotBlank()) parts += book.fileSize
    return parts.joinToString(" · ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyResult(
    onSearchCategory: (String) -> Unit,
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
        }
    }
}

@Composable
private fun SearchError(
    message: String,
    kindLabel: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md),
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
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "错误类型：" + kindLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
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
