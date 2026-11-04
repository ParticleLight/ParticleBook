package app.particlebook.android.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.particlebook.android.zlib.ZBookDetail
import app.particlebook.android.zlib.ZlibClient
import app.particlebook.android.zlib.ZlibException
import app.particlebook.android.zlib.ZlibLoginParser
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
 * 双校验，只有当前书的响应能写 state；失败后再次 [load] 即重新解析。
 *
 * task-43：详情页不再用 LaunchedEffect 自动打开浏览器，改为点击处协程调用 [loadNow] 拿到 URL
 * 直接打开（避免迟到的 Ready 把用户从当前页拉走）；URL 成功交给浏览器后调用 [consumeReady]
 * 复位 Idle，下次点击重新解析，不复用可能过期或属于旧账号的票据。
 *
 * task-77：p15 真机实测匿名详情页的 reader-link 也能读到正文，在线阅读不再需要登录；
 * loggedIn 只用于「页面没有 reader-link 时」区分会话失效（已登录却拿到匿名页）与登录提示。
 *
 * [loadReader] 可注入（JVM 单测用假实现构造慢/快响应）；生产环境 secondary constructor
 * 在 Dispatchers.IO 上用 [ZlibReaderResolver] 走 ZlibClient（复用进程内挑战/cookie 会话）。
 */
internal class ZlibReaderViewModel(
    private val scope: CoroutineScope,
    private val loadReader: suspend (bookHash: String, bookSlug: String?) -> ZlibReaderLaunchState,
) {
    constructor(scope: CoroutineScope, context: Context, loggedIn: Boolean) : this(
        scope = scope,
        loadReader = { hash, slug ->
            withContext(Dispatchers.IO) {
                val client = ZlibClient.get(context.applicationContext)
                ZlibReaderResolver(
                    loadDetail = { bookHash, bookSlug -> client.fetchDetail(bookHash, bookSlug) },
                    loadPage = { path -> client.fetchBytes(path).toString(Charsets.UTF_8) },
                ).resolve(hash, slug, loggedIn)
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

    /**
     * 点击路径：在调用方协程里解析并返回结果（详情页拿到 Ready 后立即打开浏览器）。
     * 失败映射为 [ZlibReaderLaunchState.Error] 并写入 state；期间换书/新请求使响应过期时返回 null。
     */
    suspend fun loadNow(bookHash: String, bookSlug: String?): ZlibReaderLaunchState? {
        val key = keyOf(bookHash, bookSlug)
        val token = beginLoad(key)
        return try {
            val result = loadReader(bookHash, bookSlug)
            if (!isCurrent(token, key)) return null
            state = result
            result
        } catch (cancellation: CancellationException) {
            // 页面离场/换书取消时不保留 Loading，避免下次进入一直转圈。
            if (isCurrent(token, key)) state = ZlibReaderLaunchState.Idle
            throw cancellation
        } catch (throwable: Throwable) {
            if (!isCurrent(token, key)) return null
            val error = ZlibReaderLaunchState.Error(ZlibUiError.message(throwable))
            state = error
            error
        }
    }

    /**
     * task-43 P2-2：reader URL 已成功交给系统浏览器后复位 Idle；下次点击重新解析，
     * 不复用可能已过期（票据约 7 天）或属于旧账号的 URL。
     */
    fun consumeReady() {
        if (state !is ZlibReaderLaunchState.Ready) return
        state = ZlibReaderLaunchState.Idle
        loadedKey = null
        loadJob?.cancel()
        loadJob = null
    }

    private fun loadInternal(bookHash: String, bookSlug: String?) {
        val key = keyOf(bookHash, bookSlug)
        val token = beginLoad(key)
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

    /** 开启一次请求：递增令牌、记录当前书、取消上一个 scope 任务、置 Loading。 */
    private fun beginLoad(key: String): Long {
        val token = ++requestSeq
        loadedKey = key
        loadJob?.cancel()
        loadJob = null
        state = ZlibReaderLaunchState.Loading
        return token
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
 * 若取 HTML 时挑战恰好过期（HTTP 503），重取一次详情页解挑战后再试一次；第二次仍 503 时
 * 给「站点要求人机验证」文案，而不是让用户看到「服务器返回错误 503」。
 * task-58：若取回的 HTML 是登录页/人机验证页（cookie 失效或挑战拦截），映射为可操作的文案。
 * task-77（p15 实测匿名可读）：先找 reader-link —— 匿名详情页带 limited 票据也能打开正文，
 *   所以 loggedIn=true 却拿到匿名页不再直接判会话失效；只有「匿名页 + 确实没有 reader-link」
 *   才按登录态区分：已登录 → 会话失效，未登录 → 登录提示。登录页同理。
 */
internal class ZlibReaderResolver(
    private val loadDetail: suspend (bookHash: String, bookSlug: String?) -> ZBookDetail,
    private val loadPage: suspend (path: String) -> String,
) {

    suspend fun resolve(
        bookHash: String,
        bookSlug: String?,
        loggedIn: Boolean,
    ): ZlibReaderLaunchState {
        val detail = loadDetail(bookHash, bookSlug)
        val html = try {
            loadPageWithChallengeRetry(bookHash, bookSlug, detail.detailPath)
        } catch (failure: ZlibException) {
            // fetchBytes 不自己解挑战；重试后仍 503 说明挑战没过，给可操作文案。
            if (failure.httpCode == 503) {
                return ZlibReaderLaunchState.Error(ZLIB_READER_CHALLENGE_PAGE_MESSAGE)
            }
            throw failure
        }
        // 200 登录页/人机验证页：登录页按登录态区分（已登录=会话失效，匿名=去登录）。
        when (ZlibReaderUrl.classifyInterstitial(html)) {
            ZlibReaderUrl.InterstitialPage.LOGIN ->
                return ZlibReaderLaunchState.Error(
                    if (loggedIn) {
                        ZLIB_READER_SESSION_EXPIRED_MESSAGE
                    } else {
                        ZLIB_READER_LOGIN_REQUIRED_MESSAGE
                    },
                )
            ZlibReaderUrl.InterstitialPage.CHALLENGE ->
                return ZlibReaderLaunchState.Error(ZLIB_READER_CHALLENGE_PAGE_MESSAGE)
            ZlibReaderUrl.InterstitialPage.NONE -> Unit
        }
        val anonymousDetail =
            ZlibLoginParser.accountState(html) == ZlibLoginParser.AccountState.Anonymous
        return when (val parsed = ZlibReaderUrl.extract(html)) {
            is ZlibReaderUrl.ParseResult.Ok ->
                ZlibReaderLaunchState.Ready(readerUrl = parsed.url)
            is ZlibReaderUrl.ParseResult.Failed -> {
                val message = when {
                    // task-77：能读到 reader-link 就走 Ready（上一条分支）；没有入口且页面是匿名时，
                    // 已登录用户多半是 cookie 失效，匿名用户则提示先登录。
                    parsed.reason == ZlibReaderUrl.Failure.NOT_FOUND && anonymousDetail && loggedIn ->
                        ZLIB_READER_SESSION_EXPIRED_MESSAGE
                    parsed.reason == ZlibReaderUrl.Failure.NOT_FOUND && anonymousDetail ->
                        ZLIB_READER_LOGIN_REQUIRED_MESSAGE
                    else -> zlibReaderUrlFailureMessage(parsed.reason)
                }
                ZlibReaderLaunchState.Error(message)
            }
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
