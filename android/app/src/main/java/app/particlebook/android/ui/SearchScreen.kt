package app.particlebook.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import app.particlebook.android.data.SearchHit

/**
 * 跨书全文搜索页（task-31 的 UI 契约）。
 *
 * 纯渲染：无副作用、不持有 ViewModel、不 remember 业务状态——输入框文本、结果、错误全部来自
 * [SearchUiState]（每个状态都带 query 回显）。唯一的本地状态是列表滚动位置（LazyListState），
 * 这是契约里明确允许的一项。
 *
 * 为什么不用 M3 的 SearchBar：本页已经是独立的全屏搜索路由，SearchBar 的 expanded 形态是
 * 「在当前页面上展开一个全屏搜索视图」，两者叠起来会出双层返回栈与两套结果列表。这里用公开的
 * OutlinedTextField（与 LibraryScreen 的页内搜索栏同款组件），没有 opt-in，签名稳定。
 *
 * 布局（自上而下，单列）：
 * 1. 返回键 + 页面标题「全文搜索」；
 * 2. 搜索框：左侧放大镜、右侧清除（有输入才出现，走 Motion.enter/exit）；
 * 3. 状态区：Idle/Editing 说明卡 → Searching 进度 → Results 分组列表 / Empty / Failed。
 *
 * 200% 字号：标题与正文都不锁 maxLines、不写死高度；结果行只给 heightIn(min) 保证触控目标，
 * 长文本自然换行。字号放大时唯一变化的是行数，不是截断。
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onOpenHit: (SearchHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val results = state as? SearchUiState.Results
    // 新查询的结果到达时回到顶部；这是滚动位置，不是业务状态。
    LaunchedEffect(results?.query) {
        if (results != null) listState.scrollToItem(0)
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .navigationBarsPadding()
            .padding(horizontal = Spacing.screen),
    ) {
        Spacer(Modifier.height(Spacing.lg))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(Spacing.minTouchTarget),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                )
            }
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = "全文搜索",
                style = AppType.sectionTitle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(Spacing.md))
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
            placeholder = {
                Text(
                    text = "搜索已打开书籍的正文",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingIcon = {
                // 装饰图标：搜索语义由输入框本身与占位文案承载，读屏不单独朗读。
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    modifier = Modifier.size(Spacing.lg),
                )
            },
            trailingIcon = {
                AnimatedVisibility(
                    visible = state.query.isNotEmpty(),
                    enter = Motion.enter(),
                    exit = Motion.exit(),
                ) {
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.size(Spacing.minTouchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "清除搜索词",
                            modifier = Modifier.size(Spacing.lg),
                        )
                    }
                }
            },
            // 搜索是防抖自动触发的，IME 的搜索键只负责收键盘，不再触发第二次查询。
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.xxl),
        )
        Spacer(Modifier.height(Spacing.sm))
        when (state) {
            is SearchUiState.Idle -> IdlePanel(modifier = Modifier.weight(1f))

            // 防抖窗口内与 Idle 同一套说明：此时还没有可展示的结果，闪一下「搜索中」只会干扰输入。
            is SearchUiState.Editing -> IdlePanel(modifier = Modifier.weight(1f))

            is SearchUiState.Searching -> SearchingBlock(
                query = state.query.trim(),
                modifier = Modifier.weight(1f),
            )

            is SearchUiState.Empty -> EmptyBlock(
                query = state.query.trim(),
                modifier = Modifier.weight(1f),
            )

            is SearchUiState.Failed -> FailedBlock(
                message = state.message,
                retryQuery = state.query.trim(),
                onRetry = onQueryChange,
                modifier = Modifier.weight(1f),
            )

            is SearchUiState.Results -> ResultsBlock(
                state = state,
                listState = listState,
                onOpenHit = onOpenHit,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Idle / Editing 共用：搜索说明 + 索引边界说明。 */
@Composable
private fun IdlePanel(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "输入书名里的词、人物名或任意片段",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.xxs))
        Text(
            text = "停止输入约 0.25 秒后自动搜索，也可以按键盘上的搜索键。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.lg))
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(Spacing.md),
                verticalAlignment = Alignment.Top,
            ) {
                // 装饰图标：含义由右侧标题与正文承载。
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Spacing.lg),
                )
                Spacer(Modifier.width(Spacing.xs))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "索引在打开书籍时建立",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(Spacing.xxs))
                    Text(
                        text = "没打开过的书还搜不到：打开一次后，正文才会加入本机索引。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(Spacing.lg))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SearchingBlock(query: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LoadingIndicator()
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = if (query.isEmpty()) "正在搜索…" else "正在搜索「" + query + "」",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Spacing.md),
            )
        }
    }
}

@Composable
private fun EmptyBlock(query: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 状态图标：含义由下面的标题与说明承载。
            Icon(
                imageVector = Icons.Outlined.SearchOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Spacing.xxl),
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = if (query.isEmpty()) "没有找到匹配内容" else "没有找到「" + query + "」",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "换个关键词试试。只有打开过的书会建立索引，没打开过的书还搜不到。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Spacing.xs),
            )
        }
    }
}

/**
 * 错误态。契约里 SearchScreen 没有 onRetry 参数，所以「重试」重发当前查询：
 * [onQueryChange] 会走 [SearchViewModel.updateQuery]，等于重新发起同一词条的搜索。
 * 若 Lead 想在页面外（如 snackbar）触发，可直接调 [SearchViewModel.retry]。
 */
@Composable
private fun FailedBlock(
    message: String,
    retryQuery: String,
    onRetry: (String) -> Unit,
    modifier: Modifier = Modifier,
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
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(Spacing.xxl),
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = "搜索失败",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Spacing.xs),
            )
            Spacer(Modifier.height(Spacing.md))
            FilledTonalButton(
                onClick = { onRetry(retryQuery) },
                enabled = retryQuery.isNotEmpty(),
            ) {
                Text("重试")
            }
        }
    }
}

/** 结果列表：摘要行 → （按书分组的组头 + 命中行）*。 */
@Composable
private fun ResultsBlock(
    state: SearchUiState.Results,
    listState: LazyListState,
    onOpenHit: (SearchHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val query = state.query.trim()
    val highlight = SpanStyle(
        background = MaterialTheme.colorScheme.primaryContainer,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = Spacing.xl),
    ) {
        item(key = "summary") {
            Column(modifier = Modifier.padding(bottom = Spacing.xs)) {
                Text(
                    text = if (query.isEmpty()) {
                        "在 " + state.groups.size + " 本书中找到 " + state.hitCount + " 条命中"
                    } else {
                        "「" + query + "」在 " + state.groups.size + " 本书中找到 " + state.hitCount + " 条命中"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.truncated) {
                    Spacer(Modifier.height(Spacing.xs))
                    TruncationNotice()
                }
            }
        }
        for (group in state.groups) {
            item(key = "book-" + group.bookId) {
                GroupHeader(group)
            }
            items(items = group.hits) { row ->
                HitRow(
                    row = row,
                    query = query,
                    highlight = highlight,
                    onOpenHit = onOpenHit,
                )
            }
        }
    }
}

@Composable
private fun GroupHeader(group: SearchBookGroup) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.lg, bottom = Spacing.xxs),
    ) {
        Text(
            text = group.title,
            style = AppType.bookTitle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.xxs))
        Text(
            text = group.hits.size.toString() + " 条命中",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HitRow(
    row: SearchHitRow,
    query: String,
    highlight: SpanStyle,
    onOpenHit: (SearchHit) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.minTouchTarget)
            .clip(MaterialTheme.shapes.medium)
            .clickable { onOpenHit(row.hit) }
            .padding(horizontal = Spacing.xs, vertical = Spacing.sm),
    ) {
        Text(
            text = highlightedSnippet(row.hit.snippet, query, highlight),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.xxs))
        Text(
            text = row.positionLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TruncationNotice() {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Spacing.md),
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = "结果较多，只显示前 " + SearchViewModel.SEARCH_LIMIT + " 条。输入更精确的关键词可缩小范围。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * snippet 高亮：查询词区间取 [highlightSpans]（与 ViewModel 同一份纯函数），
 * 颜色只从 MaterialTheme.colorScheme 取（primaryContainer / onPrimaryContainer 成对）。
 */
private fun highlightedSnippet(snippet: String, query: String, highlight: SpanStyle): AnnotatedString =
    buildAnnotatedString {
        append(snippet)
        for (span in highlightSpans(snippet, query)) {
            addStyle(highlight, span.start, span.endExclusive)
        }
    }
