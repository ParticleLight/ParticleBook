package app.particlebook.android.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.particlebook.android.R
import app.particlebook.android.ui.Radius
import app.particlebook.android.ui.Spacing
import app.particlebook.android.ui.parseBookRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * P1.5 §7.4 热门榜单（route popular）：/popular 100 条/页，最高 10 页（客户端去重后停止）。
 * 2 列网格，封面 3:4 复用 ZlibCoverCache；距底 4 格自动加载下一页。
 */
private class PopularViewModel(
    private val scope: CoroutineScope,
    private val repository: DiscoverRepository,
) {
    sealed interface State {
        data object Loading : State
        data object Empty : State
        data class Error(val message: String, val kindLabel: String?) : State
        data class Ready(
            val items: List<PopularBook>,
            val page: Int,
            val hasMore: Boolean,
            val loadingMore: Boolean = false,
            val loadMoreError: String? = null,
        ) : State
    }

    var state by mutableStateOf<State>(State.Loading)
        private set

    private var firstJob: Job? = null
    private var nextJob: Job? = null

    init {
        loadFirst()
    }

    fun loadFirst() {
        firstJob?.cancel()
        nextJob?.cancel()
        firstJob = scope.launch {
            state = State.Loading
            try {
                val page = repository.popular(1)
                state = if (page.isEmpty()) {
                    State.Empty
                } else {
                    State.Ready(
                        items = page.distinctBy { it.book.id },
                        page = 1,
                        hasMore = page.size >= SHORT_PAGE_THRESHOLD,
                    )
                }
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

    fun loadNext() {
        val current = state as? State.Ready ?: return
        if (!current.hasMore || current.loadingMore || current.page >= MAX_PAGES) return
        state = current.copy(loadingMore = true, loadMoreError = null)
        nextJob = scope.launch {
            try {
                val page = repository.popular(current.page + 1)
                val merged = (current.items + page).distinctBy { it.book.id }
                state = State.Ready(
                    items = merged,
                    page = current.page + 1,
                    hasMore = page.size >= SHORT_PAGE_THRESHOLD && current.page + 1 < MAX_PAGES,
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

    fun retryLoadMore() = loadNext()

    private companion object {
        const val MAX_PAGES = 10

        /** 不足半页视为最后一页（站点每页 100 条；空页同样停止）。 */
        const val SHORT_PAGE_THRESHOLD = 50
    }
}

@Composable
fun PopularScreen(
    repository: DiscoverRepository,
    onBack: () -> Unit,
    onOpenBook: (hash: String, slug: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember(scope, repository) { PopularViewModel(scope, repository) }
    val state = viewModel.state
    val gridState = rememberLazyGridState()

    DiscoverPageScaffold(
        title = stringResource(R.string.discover_popular_heading),
        onBack = onBack,
        modifier = modifier,
    ) { innerPadding ->
        when (val current = state) {
            is PopularViewModel.State.Loading -> PopularSkeleton(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            is PopularViewModel.State.Empty -> DiscoverEmptyState(
                icon = Icons.Outlined.TrendingUp,
                title = stringResource(R.string.discover_popular_empty_title),
                message = stringResource(R.string.discover_popular_empty_message),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            is PopularViewModel.State.Error -> DiscoverErrorState(
                message = current.message,
                kindLabel = current.kindLabel,
                onRetry = { viewModel.loadFirst() },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            is PopularViewModel.State.Ready -> {
                AutoLoadMoreGridEffect(
                    gridState = gridState,
                    enabled = current.hasMore && !current.loadingMore,
                    onLoadMore = { viewModel.loadNext() },
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(
                        start = Spacing.screen,
                        end = Spacing.screen,
                        top = Spacing.xs,
                        bottom = Spacing.md,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                ) {
                    items(items = current.items, key = { it.book.id }) { item ->
                        PopularCard(
                            item = item,
                            onClick = {
                                parseBookRef(item.book.href)?.let { (hash, slug) ->
                                    onOpenBook(hash, slug)
                                }
                            },
                        )
                    }
                    item(
                        key = "footer",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        DiscoverListFooter(
                            loadingMore = current.loadingMore,
                            loadMoreError = current.loadMoreError,
                            end = !current.hasMore,
                            onRetry = { viewModel.retryLoadMore() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PopularCard(item: PopularBook, onClick: () -> Unit) {
    val unknownAuthor = stringResource(R.string.common_unknown_author)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        AsyncZlibCover(
            coverUrl = item.coverUrl,
            cacheKey = item.book.id,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f),
            shape = RoundedCornerShape(Radius.cover),
            targetWidth = 180.dp,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = item.book.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = item.book.author.ifBlank { unknownAuthor },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 首次加载骨架：2 列 × 3 行（封面 3:4 + 两行文本条）。 */
@Composable
private fun PopularSkeleton(modifier: Modifier = Modifier) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier,
        contentPadding = PaddingValues(
            start = Spacing.screen,
            end = Spacing.screen,
            top = Spacing.xs,
        ),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        userScrollEnabled = false,
    ) {
        items(count = 6) {
            Column {
                PulsePlaceholder(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.75f),
                    shape = RoundedCornerShape(Radius.cover),
                )
                Spacer(Modifier.height(Spacing.xs))
                PulsePlaceholder(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(14.dp),
                    shape = RoundedCornerShape(Radius.chip),
                )
                Spacer(Modifier.height(Spacing.xs))
                PulsePlaceholder(
                    modifier = Modifier
                        .fillMaxWidth(0.45f)
                        .height(12.dp),
                    shape = RoundedCornerShape(Radius.chip),
                )
            }
        }
    }
}
