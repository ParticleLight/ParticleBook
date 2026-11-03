package app.particlebook.android.ui.discover

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.particlebook.android.ui.Radius
import app.particlebook.android.ui.Spacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * P1.5 §7.5 分类浏览（route categories）：/categories 39 组，组默认收起，点击展开子类。
 * 子类为单列 48dp 行（不是 chip）：点击进入分类书籍列表；顶部可选本地过滤（不发请求），过滤时命中组自动展开。
 */
private class CategoriesViewModel(
    private val scope: CoroutineScope,
    private val repository: DiscoverRepository,
) {
    sealed interface State {
        data object Loading : State
        data class Error(val message: String, val kindLabel: String?) : State
        data class Ready(
            val groups: List<CategoryGroupData>,
            val refreshing: Boolean = false,
            val refreshError: String? = null,
        ) : State
    }

    var state by mutableStateOf<State>(State.Loading)
        private set

    private var job: Job? = null

    init {
        load(forceRefresh = false)
    }

    fun load(forceRefresh: Boolean) {
        job?.cancel()
        val existing = state as? State.Ready
        if (existing == null) {
            state = State.Loading
        } else {
            state = existing.copy(refreshing = true, refreshError = null)
        }
        job = scope.launch {
            try {
                val groups = repository.categories(forceRefresh)
                state = State.Ready(groups = groups)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                state = if (existing != null) {
                    existing.copy(
                        refreshing = false,
                        refreshError = discoverErrorMessage(throwable),
                    )
                } else {
                    State.Error(
                        message = discoverErrorMessage(throwable),
                        kindLabel = kindLabelOf(throwable),
                    )
                }
            }
        }
    }

    fun retry() = load(forceRefresh = false)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CategoriesScreen(
    repository: DiscoverRepository,
    onBack: () -> Unit,
    onOpenCategory: (id: String, slug: String, name: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember(scope, repository) { CategoriesViewModel(scope, repository) }
    val state = viewModel.state
    var query by rememberSaveable { mutableStateOf("") }
    // Set 不直接可保存：用 id 列表 + rememberSaveable（旋转后展开状态保留）。
    var expandedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }

    val toggle: (String) -> Unit = { id ->
        expandedIds = if (id in expandedIds) expandedIds - id else expandedIds + id
    }

    DiscoverPageScaffold(
        title = "分类",
        onBack = onBack,
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                placeholder = {
                    Text(
                        text = "筛选分类",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }, modifier = Modifier.size(48.dp)) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "清除筛选",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screen)
                    .heightIn(min = 48.dp),
            )

            when (val current = state) {
                is CategoriesViewModel.State.Loading -> CategoriesSkeleton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )

                is CategoriesViewModel.State.Error -> DiscoverErrorState(
                    message = current.message,
                    kindLabel = current.kindLabel,
                    onRetry = { viewModel.retry() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )

                is CategoriesViewModel.State.Ready -> {
                    val trimmed = query.trim()
                    val visibleGroups = remember(current.groups, trimmed) {
                        if (trimmed.isEmpty()) {
                            current.groups
                        } else {
                            current.groups.mapNotNull { group ->
                                val matched = group.categories.filter {
                                    it.name.contains(trimmed, ignoreCase = true)
                                }
                                if (group.name.contains(trimmed, ignoreCase = true)) {
                                    group
                                } else if (matched.isNotEmpty()) {
                                    group.copy(categories = matched)
                                } else {
                                    null
                                }
                            }
                        }
                    }
                    if (current.refreshError != null) {
                        RefreshFailureHint(
                            message = current.refreshError,
                            onRetry = { viewModel.load(forceRefresh = true) },
                        )
                    }
                    if (visibleGroups.isEmpty()) {
                        DiscoverEmptyState(
                            icon = Icons.Outlined.Search,
                            title = if (trimmed.isEmpty()) "暂无分类数据" else "没有匹配的分类",
                            message = if (trimmed.isEmpty()) {
                                "分类数据暂时为空，请稍后重试。"
                            } else {
                                "换个关键词试试，或清除筛选查看全部分类。"
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentPadding = PaddingValues(
                                bottom = Spacing.md,
                            ),
                        ) {
                            visibleGroups.forEach { group ->
                                val expanded = trimmed.isNotEmpty() || group.id in expandedIds
                                stickyHeader(key = "group-" + group.id) {
                                    CategoryGroupHeader(
                                        group = group,
                                        expanded = expanded,
                                        showDivider = true,
                                        onToggle = {
                                            if (trimmed.isEmpty()) toggle(group.id)
                                        },
                                    )
                                }
                                if (expanded) {
                                    items(
                                        items = group.categories,
                                        key = { entry -> "cat-" + entry.id },
                                    ) { entry ->
                                        CategoryRow(
                                            entry = entry,
                                            onClick = {
                                                if (entry.slug.isNotBlank()) {
                                                    onOpenCategory(entry.id, entry.slug, entry.name)
                                                }
                                            },
                                        )
                                    }
                                    item(key = "gap-" + group.id) {
                                        Spacer(Modifier.height(Spacing.xs))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 粘性分组头：组名 + 子类数 + 展开/收起；默认收起。 */
@Composable
private fun CategoryGroupHeader(
    group: CategoryGroupData,
    expanded: Boolean,
    showDivider: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(onClick = onToggle)
                    .padding(horizontal = Spacing.screen),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = group.categories.size.toString() + " 个子类",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(Spacing.xs))
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (showDivider) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                ) {}
            }
        }
    }
}

/** 子类行：单列 48dp，名称 + 本数 + 右箭头；点击进入分类书籍列表。 */
@Composable
private fun CategoryRow(
    entry: CategoryEntry,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                enabled = entry.slug.isNotBlank(),
                onClick = onClick,
            )
            .padding(horizontal = Spacing.screen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (entry.booksCount > 0) {
            Text(
                text = entry.booksCount.toString() + " 本",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.size(Spacing.xs))
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 缓存可读但刷新失败：顶部提示，不遮挡已有分组。 */
@Composable
private fun RefreshFailureHint(message: String, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screen, vertical = Spacing.xs),
        shape = RoundedCornerShape(Radius.chip),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = Spacing.sm, end = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "刷新失败，展示缓存数据：" + message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "重试",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .clickable(onClick = onRetry)
                    .padding(Spacing.sm),
            )
        }
    }
}

/** 首次加载骨架：8 行分组条。 */
@Composable
private fun CategoriesSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screen),
    ) {
        Spacer(Modifier.height(Spacing.xs))
        repeat(8) {
            PulsePlaceholder(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(Radius.chip),
            )
            Spacer(Modifier.height(Spacing.xs))
        }
    }
}
