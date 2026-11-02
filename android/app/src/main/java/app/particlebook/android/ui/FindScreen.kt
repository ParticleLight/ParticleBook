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
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
fun DiscoverScreen(modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
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
                modifier = Modifier.padding(bottom = 96.dp),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = Spacing.screen),
        ) {
            Spacer(Modifier.height(Spacing.lg))
            Text("Z-Library", style = AppType.pageTitle)
            Spacer(Modifier.height(2.dp))
            Text(
                text = "内置书源 · P1a 检索",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "清除")
                        }
                    }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            )
            Spacer(Modifier.height(Spacing.xs))
            FilledTonalButton(
                onClick = runSearch,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                shape = CircleShape,
                colors = ButtonDefaults.filledTonalButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                if (state is ZlibSearchState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text("搜索中…", maxLines = 1)
                } else {
                    Text("搜索", maxLines = 1)
                }
            }

            when (val current = state) {
                is ZlibSearchState.Idle -> IdleContent(
                    category = category,
                    onCategoryClick = { category = if (category == it) null else it },
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
                    }
                }

                is ZlibSearchState.Empty -> EmptyResult(
                    onReset = { viewModel.resetToIdle() },
                    modifier = Modifier.weight(1f),
                )

                is ZlibSearchState.Error -> SearchError(
                    message = current.message,
                    kindLabel = current.kindLabel,
                    onRetry = { viewModel.retry() },
                    modifier = Modifier.weight(1f),
                )

                is ZlibSearchState.Success -> {
                    // 新查询的结果回来时回到列表顶部；翻页（同 query）不会重触发。
                    LaunchedEffect(current.query) { listState.scrollToItem(0) }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(bottom = Spacing.listBottomInset),
                    ) {
                        item(key = "summary") {
                            Text(
                                text = "找到 " + current.books.size + " 本 · 第 " +
                                    current.page + " / " + current.totalPages + " 页",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = Spacing.sm),
                            )
                        }
                        items(items = current.books, key = { book -> book.id }) { book ->
                            ZlibResultRow(
                                book = book,
                                onClick = {
                                    scope.launch { snackbarHostState.showSnackbar("P1b 接入后可下载") }
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
            text = "还没有搜索记录。搜索记录将在 P1b 接入后显示。",
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
                    Text("P1a 检索已接入", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(Spacing.xxs))
                    Text(
                        text = "搜索结果来自 Z-Library 镜像；下载将在 P1b 接入。",
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

@Composable
private fun EmptyResult(onReset: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.SearchOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(Spacing.sm))
        Text("没有找到相关书籍", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.xxs))
        Text(
            text = "换个书名或作者再试试。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.sm))
        TextButton(onClick = onReset) { Text("看看热门分类") }
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
