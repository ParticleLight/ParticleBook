package app.particlebook.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.particlebook.android.data.BookRow
import app.particlebook.android.data.CatalogSync
import app.particlebook.android.data.LibraryRepository
import app.particlebook.android.data.SearchHit
import app.particlebook.android.reader.ReaderLocator
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 当前书架的跨书全文搜索状态机（消费 [LibraryRepository.searchInBooks]）。
 *
 * 设计约束（与 task-31 的接线契约一一对应）：
 * - 普通类，不是 androidx ViewModel：项目没有 androidx.lifecycle:viewmodel 依赖（离线环境不能加），
 *   由 Composable 用 remember 持有、协程跑在传入的 scope 上，离开页面随 scope 取消。
 *   这与 [ZlibSearchViewModel] 的既有做法一致。
 * - state 用 Compose `mutableStateOf` 暴露，不引 StateFlow：页面只需要读快照。
 * - 输入回显：每个状态都带 [SearchUiState.query]（输入框的当前文本），所以 [SearchScreen] 不需要
 *   remember 任何业务状态——输入框内容完全由 state 驱动。空白输入归一成空串 + [SearchUiState.Idle]。
 *
 * ## 防抖与并发
 * [updateQuery] 每次取消上一个 Job 并把 [SearchViewModel] 内部的 requestId 加一；延迟
 * [DEFAULT_DEBOUNCE_MS] 后才查库。查询完成后先比对 requestId 再写 state，因此「快速改词」时
 * 旧请求（哪怕已经跑进 IO 并成功返回）也不会覆盖新查询的结果。仓库同步执行（契约如此），
 * 一律放到 [ioDispatcher] 上跑，主线程只读状态。
 *
 * ## 分组与排序（纯函数 [groupSearchHits]，可单测）
 * - 组顺序 = 命中在仓库结果里第一次出现的顺序（FTS 按文档写入顺序返回）。
 * - 组内顺序 = 阅读位置（EPUB：章节 href → blockIndex → charOffset；PDF：页 → y → x）。
 *   locator 解不出来时排在组尾并保持仓库原顺序（稳定排序）。
 * - 查询域按当前书架的 stable_key 解析为 SQLite id；命中回映射当前首个 UI 别名，书名取同一份书架快照。
 *
 * ## 截断
 * 一次最多取 [SEARCH_LIMIT] 条。命中数达到上限就把 [SearchUiState.Results.truncated] 置真，
 * 由页面提示「只显示前 N 条」——宁可提示可能还有更多，也不让用户以为这就是全部。
 */
sealed interface SearchUiState {
    /** 输入框当前文本（原样回显；空白输入后为 ""）。 */
    val query: String

    /** 未输入：显示索引说明。 */
    data object Idle : SearchUiState {
        override val query: String get() = ""
    }

    /** 已输入、还在防抖窗口里：输入回显，但不算「搜索中」（避免每敲一个字就闪一次进度圈）。 */
    data class Editing(override val query: String) : SearchUiState

    /** 查询已发出，等待仓库返回。 */
    data class Searching(override val query: String) : SearchUiState

    /** 有结果：按书分组。 */
    data class Results(
        override val query: String,
        val groups: List<SearchBookGroup>,
        val truncated: Boolean = false,
    ) : SearchUiState {
        val hitCount: Int get() = groups.sumOf { it.hits.size }
    }

    /** 查询成功但没有任何命中。 */
    data class Empty(override val query: String) : SearchUiState

    /** 仓库/索引抛异常；message 已转成中文并保留原始异常文本便于排查。 */
    data class Failed(override val query: String, val message: String) : SearchUiState
}

/** 一本书的命中分组：组头书名 + 组内命中（已按阅读位置排序）。 */
data class SearchBookGroup(
    val bookId: Long,
    val title: String,
    val hits: List<SearchHitRow>,
)

/** 一条命中 + 预渲染的位置文案（ViewModel 里算好，页面只画）。 */
data class SearchHitRow(
    val hit: SearchHit,
    val positionLabel: String,
)

/** 高亮区间：UTF-16 下标，[start] 闭、[endExclusive] 开，直接喂给 AnnotatedString.addStyle。 */
data class HighlightSpan(val start: Int, val endExclusive: Int)

/**
 * 一次搜索的持有者。
 *
 * @param scope 页面级协程作用域（生产是 composition scope，测试用 Unconfined）。
 * @param repositoryProvider 每次查询时解析仓库（生产传 `{ LibraryRuntime.repository(context) }`）；
 *   返回同步实现的 [LibraryRepository]，调用点负责切到 IO。
 * @param shelfRows 当前书架行；每次查询在切到 IO 前复制快照，空书架不解析仓库。
 * @param debounceMillis 防抖窗口，默认 [DEFAULT_DEBOUNCE_MS]；测试注入更小的值。
 * @param ioDispatcher 查库用的 dispatcher，默认 [Dispatchers.IO]。
 */
class SearchViewModel(
    private val scope: CoroutineScope,
    private val repositoryProvider: () -> LibraryRepository,
    private val shelfRows: () -> List<BookRow>,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** 输入框文本（原样；空白输入归一为 ""）。 */
    var query by mutableStateOf("")
        private set

    /** 当前状态；页面直接渲染它，不做二次推导。 */
    var state by mutableStateOf<SearchUiState>(SearchUiState.Idle)
        private set

    /** 最近一次真正发出去的查询词（已 trim）。[retry] 用它，避免重试用户还没提交的草稿。 */
    var lastCommittedQuery: String = ""
        private set

    private var searchJob: Job? = null

    /**
     * 请求令牌：发起/取消/重试时自增，写 state 前比对。用 @Volatile 是因为 IO 线程返回后
     * 会在哪个线程恢复取决于 scope 的 dispatcher（测试里是 Unconfined）。
     */
    @Volatile
    private var requestId: Int = 0

    /** 输入变更：内置防抖 + 取消旧任务；空白输入立即回 Idle。 */
    fun updateQuery(value: String) {
        val trimmed = value.trim()
        query = if (trimmed.isEmpty()) "" else value
        searchJob?.cancel()
        searchJob = null
        requestId += 1
        if (trimmed.isEmpty()) {
            lastCommittedQuery = ""
            state = SearchUiState.Idle
            return
        }
        state = SearchUiState.Editing(query)
        launchSearch(request = requestId, trimmed = trimmed, input = query, debounced = true)
    }

    /** 错误态「重试」：立刻重跑最近一次查询（不再等防抖窗口）。 */
    fun retry() {
        val trimmed = lastCommittedQuery.ifBlank { query.trim() }
        if (trimmed.isEmpty()) return
        searchJob?.cancel()
        searchJob = null
        requestId += 1
        launchSearch(request = requestId, trimmed = trimmed, input = query, debounced = false)
    }

    /** 离开页面：取消在途任务，迟到结果不再写 state。 */
    fun dispose() {
        searchJob?.cancel()
        searchJob = null
        requestId += 1
    }

    private fun launchSearch(request: Int, trimmed: String, input: String, debounced: Boolean) {
        searchJob = scope.launch {
            if (debounced && debounceMillis > 0L) delay(debounceMillis)
            if (request != requestId) return@launch
            lastCommittedQuery = trimmed
            state = SearchUiState.Searching(input)
            val outcome = load(trimmed)
            if (request != requestId) return@launch
            when (outcome) {
                is SearchOutcome.Found -> {
                    val groups = groupSearchHits(outcome.hits, outcome.titles)
                    state = if (groups.isEmpty()) {
                        SearchUiState.Empty(input)
                    } else {
                        SearchUiState.Results(
                            query = input,
                            groups = groups,
                            truncated = outcome.hits.size >= SEARCH_LIMIT,
                        )
                    }
                }

                is SearchOutcome.Failed -> state = SearchUiState.Failed(input, searchErrorMessage(outcome.error))
            }
        }
    }

    private suspend fun load(trimmed: String): SearchOutcome = try {
        val rows = shelfRows().toList()
        if (rows.isEmpty()) {
            SearchOutcome.Found(emptyList(), emptyMap())
        } else {
            withContext(ioDispatcher) {
                val repository = repositoryProvider()
                val resolvedIds = CatalogSync.resolveBookIds(repository, rows)
                val canonicalBooks = linkedMapOf<Long, BookRow>()
                for (row in rows) {
                    if (row.stableKey.isBlank()) continue
                    val dbId = resolvedIds[row.id] ?: continue
                    // 同一 canonical id 的多个 UI 别名沿用书架首次出现者。
                    canonicalBooks.getOrPut(dbId) { row }
                }
                if (canonicalBooks.isEmpty()) {
                    return@withContext SearchOutcome.Found(emptyList(), emptyMap())
                }
                val hits = repository.searchInBooks(trimmed, canonicalBooks.keys, SEARCH_LIMIT)
                    .mapNotNull { hit ->
                        val row = canonicalBooks[hit.bookId] ?: return@mapNotNull null
                        hit.copy(bookId = row.id)
                    }
                val titles = canonicalBooks.values.associate { it.id to it.title }
                SearchOutcome.Found(hits, titles)
            }
        }
    } catch (cancellation: CancellationException) {
        // 取消不是失败：必须原样抛给协程，不能落成 Failed。
        throw cancellation
    } catch (throwable: Throwable) {
        SearchOutcome.Failed(throwable)
    }

    private sealed interface SearchOutcome {
        data class Found(val hits: List<SearchHit>, val titles: Map<Long, String>) : SearchOutcome
        data class Failed(val error: Throwable) : SearchOutcome
    }

    companion object {
        /** 防抖窗口：250ms（用户停手再查，避免每个字符都打一次 FTS）。 */
        const val DEFAULT_DEBOUNCE_MS: Long = 250L

        /** 单次查询上限；命中数达到它即认为「可能还有更多」。 */
        const val SEARCH_LIMIT: Int = 200

        /** 缺失或空白标题的通用分组兜底。 */
        const val UNKNOWN_BOOK_TITLE: String = "未命名书籍"
    }
}

/** 异常 → 中文失败文案；保留原始 message（本机 SQLite 失败时是唯一线索）。 */
internal fun searchErrorMessage(error: Throwable): String {
    val detail = error.message?.trim().orEmpty()
    return if (detail.isEmpty()) "搜索失败，请稍后重试。" else "搜索失败：" + detail
}

/**
 * 按 bookId 分组并排序（纯函数，JVM 可测）。
 *
 * 组顺序 = 首次出现顺序（见文件头「分组与排序」）；组内按阅读位置升序，解不出的 locator 排组尾。
 */
internal fun groupSearchHits(
    hits: List<SearchHit>,
    titles: Map<Long, String>,
): List<SearchBookGroup> {
    if (hits.isEmpty()) return emptyList()
    val byBook = LinkedHashMap<Long, MutableList<Pair<SearchHit, ReaderLocator?>>>()
    for (hit in hits) {
        byBook.getOrPut(hit.bookId) { mutableListOf() } += hit to ReaderLocator.decode(hit.locator)
    }
    return byBook.map { (bookId, entries) ->
        val sorted = entries.sortedWith { left, right -> comparePositions(left.second, right.second) }
        SearchBookGroup(
            bookId = bookId,
            title = titles[bookId]?.takeIf { it.isNotBlank() } ?: SearchViewModel.UNKNOWN_BOOK_TITLE,
            hits = sorted.map { (hit, locator) ->
                SearchHitRow(hit = hit, positionLabel = positionLabel(locator, hit.locator))
            },
        )
    }
}

/**
 * locator → 人类可读位置。
 *
 * 索引侧的 locator 是 [ReaderLocator.encode] 的行协议（EPUB 每章一条文档，block 指向该章第一个
 * 有文本的 block）。存储值都是 0 基：EPUB 的 blockIndex / PDF 的 page / Text 的 offset，
 * 展示时统一 +1，用户看到的是「第 1 段 / 第 1 页 / 第 1 字」。
 * 解不出来时按「原样短显」处理：截 24 个字符加省略号，不把编码串整条甩给用户。
 */
internal fun locatorPositionLabel(rawLocator: String): String =
    positionLabel(ReaderLocator.decode(rawLocator), rawLocator)

private fun positionLabel(locator: ReaderLocator?, rawLocator: String): String = when (locator) {
    is ReaderLocator.Epub -> chapterName(locator.chapterHref) + " · 第 " + (locator.blockIndex + 1) + " 段"
    is ReaderLocator.Pdf -> "第 " + (locator.page + 1) + " 页"
    is ReaderLocator.Text -> "第 " + (locator.offset + 1) + " 字"
    is ReaderLocator.Html -> chapterName(locator.href) + " · 第 " + (locator.offset + 1) + " 字"
    is ReaderLocator.Scroll -> "全书 " + (locator.percent * 100f).roundToInt() + "%"
    null -> {
        val raw = rawLocator.trim()
        when {
            raw.isEmpty() -> "位置未知"
            raw.length > RAW_LOCATOR_MAX_CHARS -> raw.take(RAW_LOCATOR_MAX_CHARS) + "…"
            else -> raw
        }
    }
}

/** 章节 href → 文件名（EPUB 的 href 是包内相对路径，组内展示只用文件名）。 */
private fun chapterName(href: String): String {
    val name = href.substringAfterLast('/').substringAfterLast('\\').trim()
    return name.ifBlank { "未命名章节" }
}

/** 位置比较：先按类型分档（同书同格式，实际不会混），再按各自字段；解不出的排最后。 */
private fun comparePositions(left: ReaderLocator?, right: ReaderLocator?): Int {
    if (left == null && right == null) return 0
    if (left == null) return 1
    if (right == null) return -1
    val rankDelta = rankOf(left).compareTo(rankOf(right))
    if (rankDelta != 0) return rankDelta
    return when {
        left is ReaderLocator.Epub && right is ReaderLocator.Epub ->
            compareValuesBy(left, right, { it.chapterHref }, { it.blockIndex }, { it.charOffset })

        left is ReaderLocator.Pdf && right is ReaderLocator.Pdf ->
            compareValuesBy(left, right, { it.page }, { it.y }, { it.x })

        left is ReaderLocator.Text && right is ReaderLocator.Text -> left.offset.compareTo(right.offset)
        left is ReaderLocator.Html && right is ReaderLocator.Html ->
            compareValuesBy(left, right, { it.href }, { it.offset })

        left is ReaderLocator.Scroll && right is ReaderLocator.Scroll ->
            left.percent.compareTo(right.percent)

        else -> 0
    }
}

private fun rankOf(locator: ReaderLocator): Int = when (locator) {
    is ReaderLocator.Epub -> 0
    is ReaderLocator.Pdf -> 1
    is ReaderLocator.Text -> 2
    is ReaderLocator.Html -> 3
    is ReaderLocator.Scroll -> 4
}

private const val RAW_LOCATOR_MAX_CHARS = 24

/**
 * 高亮区间（纯函数，JVM 可测）。
 *
 * 规则：
 * - 查询按空白拆词（与 FTS 查询词同粒度）；中文不分词，整段子串匹配，因此「记忆宫殿」里的
 *   「记忆」也能命中。
 * - 大小写不敏感；同一词的多处出现都高亮；重叠/相邻的区间合并，避免 addStyle 叠加出深浅不一的色块
 *   （中文 bigram 查询尤其容易重叠）。
 * - 区间是 UTF-16 code unit 下标，和 AnnotatedString / String.indexOf 一致。
 */
internal fun highlightSpans(text: String, query: String): List<HighlightSpan> {
    if (text.isEmpty()) return emptyList()
    val terms = query.split(WHITESPACE).filter { it.isNotEmpty() }
    if (terms.isEmpty()) return emptyList()
    val spans = ArrayList<HighlightSpan>()
    for (term in terms) {
        var from = 0
        while (from <= text.length - term.length) {
            val index = text.indexOf(term, from, ignoreCase = true)
            if (index < 0) break
            spans += HighlightSpan(index, index + term.length)
            // 从下一个字符继续找：允许同一词的重叠出现（如 "aa" in "aaa"）。
            from = index + 1
        }
    }
    return mergeSpans(spans)
}

private fun mergeSpans(spans: List<HighlightSpan>): List<HighlightSpan> {
    if (spans.isEmpty()) return emptyList()
    val sorted = spans.sortedWith(compareBy({ it.start }, { it.endExclusive }))
    val merged = ArrayList<HighlightSpan>(sorted.size)
    for (span in sorted) {
        val last = merged.lastOrNull()
        if (last != null && span.start <= last.endExclusive) {
            merged[merged.size - 1] = HighlightSpan(last.start, maxOf(last.endExclusive, span.endExclusive))
        } else {
            merged += span
        }
    }
    return merged
}

private val WHITESPACE = Regex("\\s+")
