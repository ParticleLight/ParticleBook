package app.particlebook.android.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

@OptIn(ExperimentalMaterial3Api::class)
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
    // C11 / P15-V11：200% 字号下 SegmentedButton 会挤压 → 退化为「排序：新→旧」单行 + 底部 sheet。
    val largeFont = LocalDensity.current.fontScale >= 1.3f
    var sortSheetOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(href) { viewModel.load(href) }

    DiscoverPageScaffold(
        title = (state as? BooklistDetailViewModel.State.Ready)?.detail?.title ?: "书单",
        onBack = onBack,
        modifier = modifier,
    ) { innerPadding ->
        when (val current = state) {
            is BooklistDetailViewModel.State.Loading -> DiscoverLoading(
                message = "正在加载书单…",
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
                                text = detail.author.ifBlank { "未知作者" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "共 " + detail.booksCount + " 本 · 评论 " + detail.commentsCount,
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
                                        Text(if (descExpanded) "收起" else "展开")
                                    }
                                }
                            }
                            Spacer(Modifier.height(Spacing.sm))
                            if (largeFont) {
                                // 单行触发 + sheet：控件高度 ≥48dp，文字不裁切、不与「书籍」抢宽度。
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "书籍",
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(
                                        onClick = { sortSheetOpen = true },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) {
                                        Text(
                                            text = "排序：" + if (current.reversed) "旧→新" else "新→旧",
                                            maxLines = 1,
                                        )
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = "书籍",
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    SingleChoiceSegmentedButtonRow {
                                        SegmentedButton(
                                            selected = !current.reversed,
                                            onClick = { if (current.reversed) viewModel.toggleSort() },
                                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                                        ) { Text("新→旧", maxLines = 1) }
                                        SegmentedButton(
                                            selected = current.reversed,
                                            onClick = { if (!current.reversed) viewModel.toggleSort() },
                                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                                        ) { Text("旧→新", maxLines = 1) }
                                    }
                                }
                            }
                        }
                    }

                    if (current.displayBooks.isEmpty()) {
                        item(key = "empty") {
                            DiscoverEmptyState(
                                icon = Icons.AutoMirrored.Outlined.MenuBook,
                                title = "书单里还没有书",
                                message = "这个书单暂时没有可展示的书籍。",
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

                // C11：200% 字号下用底部 sheet 承担排序，避免 SegmentedButton 在窄行里挤压。
                if (sortSheetOpen) {
                    val sheetState = rememberModalBottomSheetState()
                    ModalBottomSheet(
                        onDismissRequest = { sortSheetOpen = false },
                        sheetState = sheetState,
                        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(bottom = Spacing.sm),
                        ) {
                            Text(
                                text = "排序",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(
                                    horizontal = Spacing.screen,
                                    vertical = Spacing.xs,
                                ),
                            )
                            SortOptionRow(
                                label = "新→旧",
                                selected = !current.reversed,
                                onClick = {
                                    if (current.reversed) viewModel.toggleSort()
                                    sortSheetOpen = false
                                },
                            )
                            SortOptionRow(
                                label = "旧→新",
                                selected = current.reversed,
                                onClick = {
                                    if (!current.reversed) viewModel.toggleSort()
                                    sortSheetOpen = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 200% 字号排序 sheet 的单选项：整行 ≥48dp 可点，左侧单选圆点。 */
@Composable
private fun SortOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.screen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(Spacing.sm))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}
