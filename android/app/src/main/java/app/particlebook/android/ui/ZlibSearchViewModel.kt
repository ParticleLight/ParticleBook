package app.particlebook.android.ui

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.particlebook.android.zlib.ZBook
import app.particlebook.android.zlib.ZSearchPage
import app.particlebook.android.zlib.ZlibClient
import app.particlebook.android.zlib.ZlibErrorMessages
import app.particlebook.android.zlib.ZlibException
import app.particlebook.android.zlib.ZlibSearchFilters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

/** Z-Library 搜索结果页状态机（spec §3）。 */
sealed interface ZlibSearchState {
    /** 初始：热门分类 / 最近搜索 / P1 说明卡。 */
    data object Idle : ZlibSearchState

    /** 首屏搜索中。 */
    data object Loading : ZlibSearchState

    /** 搜索成功但没有结果（category 非空表示这是热门分类结果）。 */
    data class Empty(val query: String, val category: String? = null) : ZlibSearchState

    /** 搜索失败：message 已按 kind 转成中文，kindLabel 保留原始枚举便于排查。 */
    data class Error(val message: String, val kindLabel: String) : ZlibSearchState

    /** 有结果；loadingMore / loadMoreError 只影响列表尾部。 */
    data class Success(
        val query: String,
        val books: List<ZBook>,
        val page: Int,
        val totalPages: Int,
        val loadingMore: Boolean = false,
        val loadMoreError: String? = null,
        /** 非空表示分类榜单（/s/中文）而不是关键词搜索。 */
        val category: String? = null,
        /** 翻页失败的原始错误 kind（B5：NO_MIRROR 时也要触发全失败 banner）；空/成功时为 null。 */
        val loadMoreKindLabel: String? = null,
    ) : ZlibSearchState
}

/** 搜索 ViewModel 依赖的最小数据源（唯一实现是 ZlibClient 适配器；单测可注入假实现）。 */
internal interface ZlibSearchSource {
    fun search(query: String, page: Int, filters: ZlibSearchFilters? = null): ZSearchPage
    fun fetchCategory(path: String): List<ZBook>
    fun resetConnectionState()
    fun mirrorUrl(): String?
}

/** ZlibClient -> 数据源适配器（保持 ViewModel 不直接依赖具体客户端，便于 JVM 单测）。 */
internal class ZlibClientSearchSource(private val client: ZlibClient) : ZlibSearchSource {
    override fun search(query: String, page: Int, filters: ZlibSearchFilters?): ZSearchPage =
        client.search(query, page, filters)
    override fun fetchCategory(path: String): List<ZBook> = client.fetchCategory(path)
    override fun resetConnectionState() {
        client.resetConnectionState()
    }

    override fun mirrorUrl(): String? = client.mirror
}

/**
 * Z-Library 搜索状态持有者。
 * 项目没有 androidx.lifecycle:viewmodel 依赖（离线环境不能加），所以这里是一个普通类：
 * 由 Composable 用 remember 持有，协程跑在传入的 composition scope 上，离开页面自动取消。
 * 网络 + HTML 解析一律 withContext(Dispatchers.IO)，主线程只读状态和打日志。
 *
 * 并发语义（T6）：每次 search/searchCategory/resetToIdle 都自增 [requestId]，网络返回后
 * 先校验令牌再写 state；配合 Job.cancel 保证「快速改词/重试」时旧请求（包括其失败分支
 * 与翻页结果）不会覆盖新请求的界面状态。
 */
class ZlibSearchViewModel internal constructor(
    private val scope: CoroutineScope,
    private val source: ZlibSearchSource,
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
    private val logInfo: (tag: String, message: String) -> Unit = { tag, message -> Log.i(tag, message) },
    private val logWarn: (tag: String, message: String, error: Throwable?) -> Unit = { tag, message, error ->
        Log.w(tag, message, error)
    },
) {
    /** 生产构造：FindScreen 调用点保持不变。 */
    constructor(scope: CoroutineScope, client: ZlibClient) : this(scope, ZlibClientSearchSource(client))

    var state by mutableStateOf<ZlibSearchState>(ZlibSearchState.Idle)
        private set

    /** 当前线路（封面相对路径解析用）；未探测到线路时为 null。 */
    val mirrorBase: String?
        get() = runCatching { source.mirrorUrl() }.getOrNull()

    /** A5：当前筛选/排序（UI 直接读；改完要调 [applyFilters] 才会重搜）。 */
    var filters by mutableStateOf(ZlibSearchFilters())
        private set

    /** 当前在途请求使用的筛选快照：翻页必须沿用同一份，不能读取可能已被改动的 [filters]。 */
    private var activeFilters: ZlibSearchFilters = ZlibSearchFilters()

    /** A5：当前是否为分类榜单模式（/s/<分类> 不参与筛选/排序，入口隐藏）。 */
    val isCategoryMode: Boolean
        get() = currentCategory != null

    /** 最近一次已提交的关键词（分类模式为空）；换线重搜用这个，不能用输入框草稿。 */
    var currentQuery: String = ""
        private set
    private var currentCategory: String? = null
    private var firstPageJob: Job? = null
    private var nextPageJob: Job? = null

    /** 请求令牌：只在主线程自增；写状态前比对，旧请求的迟到结果直接丢弃。 */
    @Volatile
    private var requestId: Int = 0

    /** 回车 / 点搜索：从第 1 页重新搜。 */
    fun search(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val requestedFilters = filters
        val request = beginRequest(trimmed, null, requestedFilters)
        state = ZlibSearchState.Loading
        firstPageJob = scope.launch {
            val began = now()
            try {
                val result = withContext(Dispatchers.IO) { source.search(trimmed, 1, requestedFilters) }
                if (!isCurrent(request)) return@launch
                logSearch(began, result)
                // LazyColumn 的 key = book.id：单页内若站方返回重复 id 会直接崩，这里先去重。
                val books = result.books.distinctBy { it.id }
                state = if (books.isEmpty()) {
                    ZlibSearchState.Empty(trimmed)
                } else {
                    ZlibSearchState.Success(
                        query = trimmed,
                        books = books,
                        page = result.page,
                        totalPages = result.totalPages,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                // 旧请求失败（超时/连接断开）不能把新请求的 Loading/Success 覆盖成错误态。
                if (!isCurrent(request)) return@launch
                state = toErrorState(throwable)
            }
        }
    }

    /**
     * 热门分类 / 榜单：直接拉 /s/<中文>?page=1（research §8：8 个分类全部可用，51 条/页，解析同搜索页）。
     * 站点分类页不返回总页数，用「一页满 51 条就认为还有下一页」推进；返回空页即到底。
     */
    fun searchCategory(category: String) {
        val trimmed = category.trim()
        if (trimmed.isEmpty()) return
        val request = beginRequest("", trimmed, filters)
        state = ZlibSearchState.Loading
        firstPageJob = scope.launch {
            val began = now()
            try {
                val books = withContext(Dispatchers.IO) {
                    source.fetchCategory(categoryPath(trimmed, 1))
                }.distinctBy { it.id }
                if (!isCurrent(request)) return@launch
                logInfo(
                    PERF_TAG,
                    "zlib_category|ms=" + (now() - began) +
                        "|extra=mirror:" + mirrorHost() + ",results:" + books.size +
                        ",category:" + trimmed,
                )
                state = if (books.isEmpty()) {
                    ZlibSearchState.Empty(query = "", category = trimmed)
                } else {
                    ZlibSearchState.Success(
                        query = "",
                        books = books,
                        page = 1,
                        totalPages = if (books.size >= CATEGORY_PAGE_SIZE) 2 else 1,
                        category = trimmed,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                if (!isCurrent(request)) return@launch
                state = toErrorState(throwable)
            }
        }
    }

    /** 列表滑到底：totalPages > page 时追加下一页；失败只在尾部显示重试行。 */
    fun loadNextPage() {
        val current = state as? ZlibSearchState.Success ?: return
        if (current.loadingMore || current.loadMoreError != null) return
        if (current.page >= current.totalPages) return
        val request = requestId
        val requestFilters = activeFilters
        state = current.copy(loadingMore = true, loadMoreError = null, loadMoreKindLabel = null)
        nextPageJob?.cancel()
        nextPageJob = scope.launch {
            val began = now()
            try {
                if (current.category != null) {
                    val nextPage = current.page + 1
                    val more = withContext(Dispatchers.IO) {
                        source.fetchCategory(categoryPath(current.category, nextPage))
                    }
                    if (!isCurrent(request)) return@launch
                    val latest = state as? ZlibSearchState.Success ?: return@launch
                    if (latest.category != current.category) return@launch
                    if (more.isEmpty()) {
                        // 空页 = 分类到底；页码不前进，footer 显示「已经到底了」。
                        state = latest.copy(loadingMore = false, totalPages = latest.page)
                        return@launch
                    }
                    val knownIds = latest.books.mapTo(HashSet()) { it.id }
                    state = latest.copy(
                        books = latest.books + more.filter { knownIds.add(it.id) },
                        page = nextPage,
                        totalPages = if (more.size >= CATEGORY_PAGE_SIZE) {
                            nextPage + 1
                        } else {
                            nextPage
                        },
                        loadingMore = false,
                        loadMoreError = null,
                        loadMoreKindLabel = null,
                    )
                } else {
                    val next = withContext(Dispatchers.IO) {
                        source.search(current.query, current.page + 1, requestFilters)
                    }
                    if (!isCurrent(request)) return@launch
                    logSearch(began, next)
                    val latest = state as? ZlibSearchState.Success ?: return@launch
                    if (latest.query != current.query || latest.category != null) return@launch
                    val knownIds = latest.books.mapTo(HashSet()) { it.id }
                    state = latest.copy(
                        books = latest.books + next.books.filter { knownIds.add(it.id) },
                        page = next.page,
                        totalPages = next.totalPages,
                        loadingMore = false,
                        loadMoreError = null,
                        loadMoreKindLabel = null,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                if (!isCurrent(request)) return@launch
                val latest = state as? ZlibSearchState.Success ?: return@launch
                if (latest.query != current.query || latest.category != current.category) return@launch
                recordError(throwable)
                state = latest.copy(
                    loadingMore = false,
                    loadMoreError = errorMessage(throwable),
                    loadMoreKindLabel = kindLabel(throwable),
                )
            }
        }
    }

    /** 尾部「重试」：重跑下一页。 */
    fun retryLoadNextPage() {
        val current = state as? ZlibSearchState.Success ?: return
        if (current.loadMoreError == null) return
        state = current.copy(loadMoreError = null, loadMoreKindLabel = null)
        loadNextPage()
    }

    /** 错误态「重试」：重置连接状态（重新探测代理/镜像）后重搜当前关键词或分类。 */
    fun retry() {
        val category = currentCategory
        if (category != null) {
            source.resetConnectionState()
            searchCategory(category)
            return
        }
        if (currentQuery.isEmpty()) return
        source.resetConnectionState()
        search(currentQuery)
    }

    /** 空结果页「看看热门分类」：回到初始态，并作废所有在途请求。 */
    fun resetToIdle() {
        firstPageJob?.cancel()
        nextPageJob?.cancel()
        firstPageJob = null
        nextPageJob = null
        requestId += 1
        currentQuery = ""
        currentCategory = null
        state = ZlibSearchState.Idle
    }

    /** A5：应用筛选/排序（已 sanitized）。关键词结果在页时从第 1 页重搜；分类榜单不受筛选影响。 */
    fun applyFilters(newFilters: ZlibSearchFilters) {
        val sanitized = newFilters.sanitized()
        // 无变更不重搜：用户打开筛选弹层直接点「应用」不应无条件重新请求第 1 页。
        if (sanitized == filters) return
        filters = sanitized
        if (currentCategory != null) return
        if (currentQuery.isEmpty()) return
        search(currentQuery)
    }

    /**
     * 切线路后重跑当前已提交的请求：关键词用 [currentQuery]（忽略输入框里可能未提交的草稿），
     * 分类榜单重跑当前分类。保留 [filters]；无已提交请求时 no-op。
     */
    fun reloadCurrentRequest() {
        val category = currentCategory
        if (category != null) {
            retry()
            return
        }
        if (currentQuery.isEmpty()) return
        search(currentQuery)
    }

    /** A5：清除所有筛选（等价应用默认值）。 */
    fun clearFilters() {
        applyFilters(ZlibSearchFilters())
    }

    /** 开始新请求：作废旧令牌 + 取消旧 Job；返回本次令牌。 */
    private fun beginRequest(query: String, category: String?, requestFilters: ZlibSearchFilters): Int {
        currentQuery = query
        currentCategory = category
        activeFilters = requestFilters
        firstPageJob?.cancel()
        nextPageJob?.cancel()
        firstPageJob = null
        nextPageJob = null
        requestId += 1
        return requestId
    }

    private fun isCurrent(request: Int): Boolean = request == requestId

    private fun toErrorState(throwable: Throwable): ZlibSearchState.Error {
        recordError(throwable)
        return ZlibSearchState.Error(
            message = errorMessage(throwable),
            kindLabel = kindLabel(throwable),
        )
    }

    private fun recordError(throwable: Throwable) {
        logInfo(PERF_TAG, "zlib_error|extra=kind:" + kindLabel(throwable))
        logWarn(TAG, "zlib search failed kind=" + kindLabel(throwable), throwable)
    }

    private fun logSearch(began: Long, result: ZSearchPage) {
        logInfo(
            PERF_TAG,
            "zlib_search|ms=" + (now() - began) +
                "|extra=mirror:" + mirrorHost() +
                ",results:" + result.books.size +
                ",page:" + result.page +
                ",total:" + result.totalPages,
        )
    }

    /** /s/<URL-encoded 分类>?page=N：分类名是中文，必须转义后再拼站内路径。 */
    private fun categoryPath(category: String, page: Int): String {
        val encoded = java.net.URLEncoder.encode(category, "UTF-8")
        return "/s/" + encoded + "?page=" + page
    }

    private fun mirrorHost(): String {
        val raw = runCatching { source.mirrorUrl() }.getOrNull() ?: return "unknown"
        return runCatching { URI(raw).host }.getOrNull() ?: raw
    }

    private companion object {
        const val TAG = "PBZlib"
        const val PERF_TAG = "PBPERF"

        /** research §8 实测：分类页每页 51 条。 */
        const val CATEGORY_PAGE_SIZE = 51

        fun kindLabel(throwable: Throwable): String =
            (throwable as? ZlibException)?.kind?.name ?: "UNKNOWN"

        fun errorMessage(throwable: Throwable): String {
            val kind = (throwable as? ZlibException)?.kind
            return when (kind) {
                ZlibException.Kind.NO_MIRROR -> "无法连接 Z-Library：所有镜像都不可用，请检查网络后重试。"
                ZlibException.Kind.CHALLENGE_FAILED -> "反爬验证未通过，请稍后重试。"
                // D12：有真实状态码/重定向细分时统一由 ZlibErrorMessages 给文案（与详情页一致）。
                ZlibException.Kind.HTTP -> ZlibErrorMessages.from(throwable)
                ZlibException.Kind.PARSE -> "页面解析失败：镜像可能已改版。"
                ZlibException.Kind.NETWORK -> "网络连接异常，请检查网络后重试。"
                ZlibException.Kind.DAILY_LIMIT -> ZlibErrorMessages.DAILY_LIMIT
                ZlibException.Kind.LOGIN_REQUIRED -> ZlibErrorMessages.LOGIN_REQUIRED
                ZlibException.Kind.DOWNLOAD -> ZlibErrorMessages.DOWNLOAD
                else -> throwable.message ?: "搜索失败，请稍后重试。"
            }
        }
    }
}
