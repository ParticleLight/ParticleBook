package app.particlebook.android.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.particlebook.android.ui.Spacing
import app.particlebook.android.ui.ZlibResultRow
import app.particlebook.android.ui.parseBookRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * P1.5 §7.6 分类列表（route category/{id}/{slug}?name=）：与搜索结果同一套行样式 + 自动翻页。
 * 分类不存在（站点软 404，kind=NOT_FOUND）显示专用提示，不重试。
 */
private class CategoryListViewModel(
    private val scope: CoroutineScope,
    private val repository: DiscoverRepository,
) {
    sealed interface State {
        data object Loading : State
        data object NotFound : State
        data object Empty : State
        data class Error(val message: String, val kindLabel: String?) : State
        data class Ready(
            val books: List<DiscoverBook>,
            val page: Int,
            val totalPages: Int,
            val loadingMore: Boolean = false,
            val loadMoreError: String? = null,
        ) : State
    }

    var state by mutableStateOf<State>(State.Loading)
        private set

    private var firstJob: Job? = null
    private var nextJob: Job? = null

    fun load(id: String, slug: String) {
        firstJob?.cancel()
        nextJob?.cancel()
        firstJob = scope.launch {
            state = State.Loading
            try {
                val page = repository.categoryPage(id, slug, 1)
                val totalPages = page.totalPages.coerceAtLeast(1)
                state = if (page.books.isEmpty()) {
                    State.Empty
                } else {
                    State.Ready(
                        books = page.books.distinctBy { it.id },
                        page = page.page.coerceAtLeast(1),
                        totalPages = totalPages,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                if (kindLabelOf(throwable) == "NOT_FOUND") {
                    state = State.NotFound
                } else {
                    state = State.Error(
                        message = discoverErrorMessage(throwable),
                        kindLabel = kindLabelOf(throwable),
                    )
                }
            }
        }
    }

    fun retry(id: String, slug: String) = load(id, slug)

    fun loadNext(id: String, slug: String) {
        val current = state as? State.Ready ?: return
        if (current.loadingMore || current.page >= current.totalPages) return
        state = current.copy(loadingMore = true, loadMoreError = null)
        nextJob = scope.launch {
            try {
                val page = repository.categoryPage(id, slug, current.page + 1)
                val merged = (current.books + page.books).distinctBy { it.id }
                state = State.Ready(
                    books = merged,
                    page = page.page.coerceAtLeast(current.page + 1),
                    totalPages = page.totalPages.coerceAtLeast(current.totalPages),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                state = current.copy(
                    loadingMore = false,
                    loadMoreError = discoverErrorMessage(throwable),
                )
            }
        }
    }

    fun retryLoadMore(id: String, slug: String) = loadNext(id, slug)
}

@Composable
fun CategoryListScreen(
    repository: DiscoverRepository,
    categoryId: String,
    slug: String,
    name: String,
    onBack: () -> Unit,
    onOpenBook: (hash: String, slug: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember(scope, repository) { CategoryListViewModel(scope, repository) }
    val state = viewModel.state
    val listState = rememberLazyListState()
    LaunchedEffect(categoryId, slug) { viewModel.load(categoryId, slug) }

    DiscoverPageScaffold(
        title = name.ifBlank { "分类" },
        onBack = onBack,
        modifier = modifier,
    ) { innerPadding ->
        when (val current = state) {
            is CategoryListViewModel.State.Loading -> DiscoverLoading(
                message = "正在加载分类书籍…",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            is CategoryListViewModel.State.NotFound -> CategoryNotFound(
                onBack = onBack,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            is CategoryListViewModel.State.Empty -> DiscoverEmptyState(
                icon = Icons.Outlined.BrokenImage,
                title = "该分类暂无书籍",
                message = "这个分类下暂时没有可展示的书籍。",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            is CategoryListViewModel.State.Error -> DiscoverErrorState(
                message = current.message,
                kindLabel = current.kindLabel,
                onRetry = { viewModel.retry(categoryId, slug) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            is CategoryListViewModel.State.Ready -> {
                AutoLoadMoreEffect(
                    listState = listState,
                    enabled = current.page < current.totalPages && !current.loadingMore,
                    onLoadMore = { viewModel.loadNext(categoryId, slug) },
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(
                        start = Spacing.screen,
                        end = Spacing.screen,
                        bottom = Spacing.md,
                    ),
                ) {
                    item(key = "summary") {
                        Text(
                            text = "第 " + current.page + " / " + current.totalPages +
                                " 页 · 已加载 " + current.books.size + " 本",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = Spacing.sm),
                        )
                    }
                    items(items = current.books, key = { it.id }) { book ->
                        ZlibResultRow(
                            title = book.title,
                            author = book.author,
                            language = book.language,
                            extension = book.extension,
                            fileSize = book.fileSize,
                            onClick = {
                                parseBookRef(book.href)?.let { (hash, detailSlug) ->
                                    onOpenBook(hash, detailSlug)
                                }
                            },
                        )
                    }
                    item(key = "footer") {
                        DiscoverListFooter(
                            loadingMore = current.loadingMore,
                            loadMoreError = current.loadMoreError,
                            end = current.page >= current.totalPages,
                            onRetry = { viewModel.retryLoadMore(categoryId, slug) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryNotFound(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "该分类不存在或已变更",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "Z-Library 的分类结构可能已调整，请返回分类列表重新选择。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.md))
        FilledTonalButton(onClick = onBack) { Text("返回分类") }
    }
}
