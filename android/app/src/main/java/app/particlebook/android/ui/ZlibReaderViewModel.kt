package app.particlebook.android.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.particlebook.android.zlib.ZBookDetail
import app.particlebook.android.zlib.ZlibClient
import app.particlebook.android.zlib.ZlibException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A13 详情页「在线阅读」按钮的 ViewModel：解析详情页拿到带票据的 reader URL，再交给外部浏览器。
 *
 * 与详情页同款 U2 防护：连续点击/换书时旧请求可能乱序返回，故 requestSeq 请求令牌 + loadedKey
 * 双校验，只有当前书的响应能写 state；load 失败可 retry。
 *
 * [loadReader] 可注入（JVM 单测用假实现构造慢/快响应）；生产环境 secondary constructor
 * 在 Dispatchers.IO 上用 [ZlibReaderResolver] 走 ZlibClient（复用进程内挑战/cookie 会话）。
 */
internal class ZlibReaderViewModel(
    private val scope: CoroutineScope,
    private val loadReader: suspend (bookHash: String, bookSlug: String?) -> ZlibReaderLaunchState,
) {
    constructor(scope: CoroutineScope, context: Context) : this(
        scope = scope,
        loadReader = { hash, slug ->
            withContext(Dispatchers.IO) {
                val client = ZlibClient.get(context.applicationContext)
                ZlibReaderResolver(
                    loadDetail = { bookHash, bookSlug -> client.fetchDetail(bookHash, bookSlug) },
                    loadPage = { path -> client.fetchBytes(path).toString(Charsets.UTF_8) },
                ).resolve(hash, slug)
            }
        },
    )

    var state by mutableStateOf<ZlibReaderLaunchState>(ZlibReaderLaunchState.Idle)
        private set

    private var loadedKey: String? = null
    private var requestSeq: Long = 0L
    private var loadJob: Job? = null

    fun load(bookHash: String, bookSlug: String?) {
        val key = keyOf(bookHash, bookSlug)
        if (loadedKey == key && state is ZlibReaderLaunchState.Ready) return
        loadInternal(bookHash, bookSlug)
    }

    fun retry(bookHash: String, bookSlug: String?): Unit = loadInternal(bookHash, bookSlug)

    private fun loadInternal(bookHash: String, bookSlug: String?) {
        val key = keyOf(bookHash, bookSlug)
        val token = ++requestSeq
        loadedKey = key
        loadJob?.cancel()
        state = ZlibReaderLaunchState.Loading
        loadJob = scope.launch {
            try {
                val result = loadReader(bookHash, bookSlug)
                if (!isCurrent(token, key)) return@launch
                state = result
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                if (!isCurrent(token, key)) return@launch
                state = ZlibReaderLaunchState.Error(ZlibUiError.message(throwable))
            }
        }
    }

    private fun isCurrent(token: Long, key: String): Boolean =
        token == requestSeq && loadedKey == key

    private fun keyOf(bookHash: String, bookSlug: String?): String =
        bookHash.trim() + "|" + bookSlug.orEmpty().trim()
}

/**
 * 详情页 → 在线阅读链接的解析器（纯逻辑，两段 IO 可注入，JVM 单测覆盖）。
 * 解析成功的 URL 由详情页交给系统浏览器打开，不再内嵌 WebView。
 *
 * 不修改 ZlibDetailParser / ZBookDetail（A13 作用域只新增 ZlibReader*.kt）：先 fetchDetail
 * 拿到 detailPath（顺带解掉站点挑战），再 fetchBytes 取原始详情 HTML 抽 reader-link。
 * 若取 HTML 时挑战恰好过期（HTTP 503），重取一次详情页解挑战后再试一次。
 */
internal class ZlibReaderResolver(
    private val loadDetail: suspend (bookHash: String, bookSlug: String?) -> ZBookDetail,
    private val loadPage: suspend (path: String) -> String,
) {

    suspend fun resolve(bookHash: String, bookSlug: String?): ZlibReaderLaunchState {
        val detail = loadDetail(bookHash, bookSlug)
        val html = loadPageWithChallengeRetry(bookHash, bookSlug, detail.detailPath)
        return when (val parsed = ZlibReaderUrl.extract(html)) {
            is ZlibReaderUrl.ParseResult.Ok ->
                ZlibReaderLaunchState.Ready(readerUrl = parsed.url)
            is ZlibReaderUrl.ParseResult.Failed ->
                ZlibReaderLaunchState.Error(zlibReaderUrlFailureMessage(parsed.reason))
        }
    }

    private suspend fun loadPageWithChallengeRetry(
        bookHash: String,
        bookSlug: String?,
        detailPath: String,
    ): String {
        return try {
            loadPage(detailPath)
        } catch (failure: ZlibException) {
            if (failure.httpCode != 503) throw failure
            // 挑战 cookie 过期：重取详情页会重新解挑战并写入进程内 cookie，再抓一次详情 HTML。
            val refreshed = loadDetail(bookHash, bookSlug)
            loadPage(refreshed.detailPath)
        }
    }
}
