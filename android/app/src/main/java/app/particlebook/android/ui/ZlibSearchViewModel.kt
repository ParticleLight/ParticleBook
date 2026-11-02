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
        /** 非空表示分类榜单（/s/&lt;中文&gt;）而不是关键词搜索。 */
        val category: String? = null,
    ) : ZlibSearchState
}

/**
 * Z-Library 搜索状态持有者。
 * 项目没有 androidx.lifecycle:viewmodel 依赖（离线环境不能加），所以这里是一个普通类：
 * 由 Composable 用 remember 持有，协程跑在传入的 composition scope 上，离开页面自动取消。
 * 网络 + HTML 解析一律 withContext(Dispatchers.IO)，主线程只读状态和打日志。
 */
class ZlibSearchViewModel(
    private val scope: CoroutineScope,
    private val client: ZlibClient,
) {
    var state by mutableStateOf<ZlibSearchState>(ZlibSearchState.Idle)
        private set

    private var currentQuery: String = ""
    private var currentCategory: String? = null
    private var firstPageJob: Job? = null
    private var nextPageJob: Job? = null

    /** 回车 / 点搜索：从第 1 页重新搜。 */
    fun search(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        currentQuery = trimmed
        currentCategory = null
        firstPageJob?.cancel()
        nextPageJob?.cancel()
        state = ZlibSearchState.Loading
        firstPageJob = scope.launch {
            val began = SystemClock.elapsedRealtime()
            try {
                val result = withContext(Dispatchers.IO) { client.search(trimmed, 1) }
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
        currentQuery = ""
        currentCategory = trimmed
        firstPageJob?.cancel()
        nextPageJob?.cancel()
        state = ZlibSearchState.Loading
        firstPageJob = scope.launch {
            val began = SystemClock.elapsedRealtime()
            try {
                val books = withContext(Dispatchers.IO) {
                    client.fetchCategory(categoryPath(trimmed, 1))
                }.distinctBy { it.id }
                Log.i(
                    PERF_TAG,
                    "zlib_category|ms=" + (SystemClock.elapsedRealtime() - began) +
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
                state = toErrorState(throwable)
            }
        }
    }

    /** 列表滑到底：totalPages > page 时追加下一页；失败只在尾部显示重试行。 */
    fun loadNextPage() {
        val current = state as? ZlibSearchState.Success ?: return
        if (current.loadingMore || current.loadMoreError != null) return
        if (current.page >= current.totalPages) return
        state = current.copy(loadingMore = true, loadMoreError = null)
        nextPageJob = scope.launch {
            val began = SystemClock.elapsedRealtime()
            try {
                if (current.category != null) {
                    val nextPage = current.page + 1
                    val more = withContext(Dispatchers.IO) {
                        client.fetchCategory(categoryPath(current.category, nextPage))
                    }
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
                    )
                } else {
                    val next = withContext(Dispatchers.IO) {
                        client.search(current.query, current.page + 1)
                    }
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
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                val latest = state as? ZlibSearchState.Success ?: return@launch
                if (latest.query != current.query || latest.category != current.category) return@launch
                recordError(throwable)
                state = latest.copy(loadingMore = false, loadMoreError = errorMessage(throwable))
            }
        }
    }

    /** 尾部「重试」：重跑下一页。 */
    fun retryLoadNextPage() {
        val current = state as? ZlibSearchState.Success ?: return
        if (current.loadMoreError == null) return
        state = current.copy(loadMoreError = null)
        loadNextPage()
    }

    /** 错误态「重试」：重置连接状态（重新探测代理/镜像）后重搜当前关键词或分类。 */
    fun retry() {
        val category = currentCategory
        if (category != null) {
            client.resetConnectionState()
            searchCategory(category)
            return
        }
        if (currentQuery.isEmpty()) return
        client.resetConnectionState()
        search(currentQuery)
    }

    /** 空结果页「看看热门分类」：回到初始态。 */
    fun resetToIdle() {
        firstPageJob?.cancel()
        nextPageJob?.cancel()
        currentQuery = ""
        currentCategory = null
        state = ZlibSearchState.Idle
    }

    private fun toErrorState(throwable: Throwable): ZlibSearchState.Error {
        recordError(throwable)
        return ZlibSearchState.Error(
            message = errorMessage(throwable),
            kindLabel = (throwable as? ZlibException)?.kind?.name ?: "UNKNOWN",
        )
    }

    private fun recordError(throwable: Throwable) {
        val kind = (throwable as? ZlibException)?.kind?.name ?: "UNKNOWN"
        Log.i(PERF_TAG, "zlib_error|extra=kind:" + kind)
        Log.w(TAG, "zlib search failed kind=" + kind, throwable)
    }

    private fun logSearch(began: Long, result: ZSearchPage) {
        Log.i(
            PERF_TAG,
            "zlib_search|ms=" + (SystemClock.elapsedRealtime() - began) +
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
        val raw = runCatching { client.mirror }.getOrNull() ?: return "unknown"
        return runCatching { URI(raw).host }.getOrNull() ?: raw
    }

    private companion object {
        const val TAG = "PBZlib"
        const val PERF_TAG = "PBPERF"

        /** research §8 实测：分类页每页 51 条。 */
        const val CATEGORY_PAGE_SIZE = 51

        fun errorMessage(throwable: Throwable): String {
            val kind = (throwable as? ZlibException)?.kind
            return when (kind) {
                ZlibException.Kind.NO_MIRROR -> "无法连接 Z-Library：所有镜像都不可用，请检查网络后重试。"
                ZlibException.Kind.CHALLENGE_FAILED -> "反爬验证未通过，请稍后重试。"
                ZlibException.Kind.HTTP -> "服务器返回错误，请稍后重试。"
                ZlibException.Kind.PARSE -> "页面解析失败：镜像可能已改版。"
                ZlibException.Kind.NETWORK -> "网络连接失败，请检查网络后重试。"
                ZlibException.Kind.DAILY_LIMIT -> ZlibErrorMessages.DAILY_LIMIT
                ZlibException.Kind.LOGIN_REQUIRED -> ZlibErrorMessages.LOGIN_REQUIRED
                ZlibException.Kind.DOWNLOAD -> ZlibErrorMessages.DOWNLOAD
                else -> throwable.message ?: "搜索失败，请稍后重试。"
            }
        }
    }
}
