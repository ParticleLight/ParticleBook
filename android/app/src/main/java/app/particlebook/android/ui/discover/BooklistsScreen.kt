package app.particlebook.android.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.particlebook.android.R
import app.particlebook.android.ui.Radius
import app.particlebook.android.ui.Spacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * P1.5 §7.2 书单列表（route booklists）：每行 3 张封面 + 主题 + 作者 + 本数/浏览/评论 + 两行简介。
 * 距底 3 项自动加载下一页；footer 三态（加载中/失败重试/已经到底了）。
 */
private class BooklistsViewModel(
    private val scope: CoroutineScope,
    private val repository: DiscoverRepository,
) {
    sealed interface State {
        data object Loading : State
        data object Empty : State
        data class Error(val message: String, val kindLabel: String?) : State
        data class Ready(
            val items: List<BooklistCard>,
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
                val page = repository.booklists(1)
                state = if (page.items.isEmpty()) {
                    State.Empty
                } else {
                    State.Ready(
                        items = page.items.distinctBy { it.id },
                        page = 1,
                        hasMore = page.hasMore,
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
        if (!current.hasMore || current.loadingMore) return
        state = current.copy(loadingMore = true, loadMoreError = null)
        nextJob = scope.launch {
            try {
                val page = repository.booklists(current.page + 1)
                val merged = (current.items + page.items).distinctBy { it.id }
                state = State.Ready(
                    items = merged,
                    page = current.page + 1,
                    hasMore = page.hasMore && page.items.isNotEmpty(),
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
}

@Composable
fun BooklistsScreen(
    repository: DiscoverRepository,
    onBack: () -> Unit,
    onOpenBooklist: (href: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember(scope, repository) { BooklistsViewModel(scope, repository) }
    val state = viewModel.state
    val listState = rememberLazyListState()

    DiscoverPageScaffold(
        title = stringResource(R.string.discover_booklists_title),
        onBack = onBack,
        modifier = modifier,
    ) { innerPadding ->
        when (val current = state) {
            is BooklistsViewModel.State.Loading -> BooklistsSkeleton(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            is BooklistsViewModel.State.Empty -> DiscoverEmptyState(
                icon = Icons.AutoMirrored.Outlined.MenuBook,
                title = stringResource(R.string.discover_booklists_empty_title),
                message = stringResource(R.string.discover_booklists_empty_message),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            is BooklistsViewModel.State.Error -> DiscoverErrorState(
                message = current.message,
                kindLabel = current.kindLabel,
                onRetry = { viewModel.loadFirst() },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            is BooklistsViewModel.State.Ready -> {
                AutoLoadMoreEffect(
                    listState = listState,
                    enabled = current.hasMore && !current.loadingMore,
                    onLoadMore = { viewModel.loadNext() },
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
                    items(items = current.items, key = { it.id }) { item ->
                        BooklistRow(item = item, onClick = { onOpenBooklist(item.href) })
                    }
                    item(key = "footer") {
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
private fun BooklistRow(item: BooklistCard, onClick: () -> Unit) {
    // C4：200% 字号时描述收成 1 行，标题/作者/元信息仍完整，卡片高度靠 heightIn 自适应。
    val largeFont = LocalDensity.current.fontScale >= 1.3f
    val unknownAuthor = stringResource(R.string.common_unknown_author)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 132.dp)
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            repeat(3) { index ->
                AsyncZlibCover(
                    coverUrl = item.coverUrls.getOrNull(index),
                    cacheKey = "booklist-" + item.id + "-" + index,
                    modifier = Modifier.size(width = 40.dp, height = 56.dp),
                    targetWidth = 60.dp,
                )
            }
        }
        Spacer(Modifier.size(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.topic,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (item.editorsChoice) {
                    Spacer(Modifier.size(Spacing.xs))
                    EditorChoiceTag()
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.author.ifBlank { unknownAuthor },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val booksMeta = if (item.quantity > 0) {
                stringResource(R.string.discover_books_count_format, item.quantity)
            } else {
                null
            }
            val viewsMeta = if (item.views.isNotBlank()) {
                stringResource(R.string.discover_booklist_meta_views_format, item.views)
            } else {
                null
            }
            val commentsMeta = if (item.comments > 0) {
                stringResource(R.string.discover_booklist_meta_comments_format, item.comments)
            } else {
                null
            }
            val meta = listOfNotNull(booksMeta, viewsMeta, commentsMeta).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.description.isNotBlank()) {
                Spacer(Modifier.height(Spacing.xxs))
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (largeFont) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EditorChoiceTag() {
    Surface(
        shape = RoundedCornerShape(Radius.chip),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(
            text = stringResource(R.string.discover_editor_choice),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** 首次加载骨架：6 行（3 个 40×56 封面块 + 两行文本条）。 */
@Composable
private fun BooklistsSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = Spacing.screen, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Spacer(Modifier.height(Spacing.xs))
        repeat(6) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                repeat(3) { index ->
                    PulsePlaceholder(
                        modifier = Modifier.size(width = 40.dp, height = 56.dp),
                        shape = RoundedCornerShape(Radius.chip),
                    )
                    if (index < 2) Spacer(Modifier.size(Spacing.xxs))
                }
                Spacer(Modifier.size(Spacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    PulsePlaceholder(
                        modifier = Modifier
                            .fillMaxWidth(0.62f)
                            .height(18.dp),
                        shape = RoundedCornerShape(Radius.chip),
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    PulsePlaceholder(
                        modifier = Modifier
                            .fillMaxWidth(0.4f)
                            .height(12.dp),
                        shape = RoundedCornerShape(Radius.chip),
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    PulsePlaceholder(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp),
                        shape = RoundedCornerShape(Radius.chip),
                    )
                }
            }
        }
    }
}
