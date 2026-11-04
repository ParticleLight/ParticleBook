package app.particlebook.android.ui

import java.net.URI
import java.util.Locale

/** A13：在线阅读入口解析状态（详情页 → reader 链接 → WebView）。 */
internal sealed interface ZlibReaderLaunchState {
    data object Loading : ZlibReaderLaunchState
    data class Ready(val readerUrl: String, val title: String) : ZlibReaderLaunchState
    data class Error(val message: String) : ZlibReaderLaunchState
}

/** 没有在线阅读入口时的统一文案（书未上架 / 已下架 / 站点改版）。 */
internal const val ZLIB_READER_MISSING_LINK_MESSAGE =
    "这本书没有在线阅读链接（可能未上架或已下架），可下载后本地阅读。"

/** 链接校验失败原因 → 用户文案。 */
internal fun zlibReaderUrlFailureMessage(reason: ZlibReaderUrl.Failure): String = when (reason) {
    ZlibReaderUrl.Failure.NOT_FOUND -> ZLIB_READER_MISSING_LINK_MESSAGE
    ZlibReaderUrl.Failure.MALFORMED -> "在线阅读链接格式异常，请返回详情页重试。"
    ZlibReaderUrl.Failure.INSECURE_SCHEME -> "在线阅读链接不是 HTTPS，为安全起见已阻止打开。"
    ZlibReaderUrl.Failure.UNTRUSTED_HOST -> "在线阅读链接的域名不受信任，已阻止打开。"
    ZlibReaderUrl.Failure.NOT_READ_PATH -> "在线阅读链接无效（不是阅读页面），已阻止打开。"
    ZlibReaderUrl.Failure.MISSING_TICKET -> "在线阅读链接缺少访问票据，请返回详情页重试。"
}

/**
 * WebView 容器状态（纯数据 + 纯函数迁移，JVM 单测覆盖；不引用 android.webkit）。
 *
 * [loading] 首次加载为 true；主帧失败置 false 并写入 [failedMessage]；
 * 重试（[zlibReaderOnRetry]）会清错误并回到 loading。
 */
internal data class ZlibReaderWebState(
    val loading: Boolean = true,
    val progress: Int = 0,
    val failedMessage: String? = null,
    val canGoBack: Boolean = false,
)

/** WebViewClient.ERROR_* 的数值（JVM 单测不加载 android.jar，故在此镜像；与 SDK 常量一致）。 */
internal object ZlibReaderWebErrorCodes {
    const val UNKNOWN = -1
    const val HOST_LOOKUP = -2
    const val CONNECT = -6
    const val IO = -7
    const val TIMEOUT = -8
    const val REDIRECT_LOOP = -9
    const val UNSUPPORTED_SCHEME = -10
    const val FAILED_SSL_HANDSHAKE = -11
    const val BAD_URL = -12
}

/** 主帧加载错误 → 中文文案（不把 WebView 的英文 description 直接给用户）。 */
internal fun zlibReaderWebErrorMessage(errorCode: Int): String = when (errorCode) {
    ZlibReaderWebErrorCodes.HOST_LOOKUP -> "无法解析在线阅读服务器地址，请检查网络后重试。"
    ZlibReaderWebErrorCodes.CONNECT, ZlibReaderWebErrorCodes.IO -> "连接在线阅读服务器失败，请检查网络后重试。"
    ZlibReaderWebErrorCodes.TIMEOUT -> "在线阅读页面加载超时，请稍后重试。"
    ZlibReaderWebErrorCodes.REDIRECT_LOOP -> "在线阅读页面重定向异常，请返回详情页重试。"
    ZlibReaderWebErrorCodes.UNSUPPORTED_SCHEME, ZlibReaderWebErrorCodes.BAD_URL ->
        "在线阅读链接无效，请返回详情页重新打开。"
    ZlibReaderWebErrorCodes.FAILED_SSL_HANDSHAKE -> "在线阅读安全连接失败，请检查网络后重试。"
    else -> "在线阅读页面加载失败，请稍后重试。"
}

/** 主帧 HTTP 错误 → 文案；非 4xx/5xx 返回 null（调用方忽略）。 */
internal fun zlibReaderHttpErrorMessage(statusCode: Int): String? = when {
    statusCode == 401 || statusCode == 403 -> "在线阅读凭证已失效，请返回详情页重新打开。"
    statusCode == 404 || statusCode == 410 -> "在线阅读页面不存在（可能已下架）。"
    statusCode in 400..599 -> "在线阅读页面加载失败（服务器返回 " + statusCode + "），请稍后重试。"
    else -> null
}

/**
 * 是否拦截某个导航 URL：仅放行 http/https，其余（javascript: / intent: / file: / content: 等）
 * 一律拦截，避免第三方页面把读者带出容器。相对 URL 交给 WebView 自行解析。
 */
internal fun zlibReaderShouldBlockUrl(raw: String): Boolean {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return false
    val scheme = try {
        URI(trimmed).scheme?.lowercase(Locale.US)
    } catch (e: Exception) {
        null
    } ?: return false
    return scheme != "https" && scheme != "http"
}

/**
 * 401/403 后同一 URL 重载必然继续 401：需要回详情页重新取票据（票据约 7 天有效）。
 */
internal fun zlibReaderNeedsFreshLink(message: String?): Boolean =
    message != null && message.startsWith("在线阅读凭证已失效")

internal fun zlibReaderOnPageStarted(state: ZlibReaderWebState): ZlibReaderWebState =
    state.copy(loading = true, progress = 0, failedMessage = null)

internal fun zlibReaderOnProgress(state: ZlibReaderWebState, progress: Int): ZlibReaderWebState {
    val clamped = progress.coerceIn(0, 100)
    return state.copy(
        loading = state.failedMessage == null && clamped < 100,
        progress = clamped,
    )
}

/**
 * 主帧加载完成：保留 [ZlibReaderWebState.failedMessage]（WebView 在 onReceivedError 之后
 * 通常仍会回调 onPageFinished，不能把错误遮罩清掉变成白屏）。
 */
internal fun zlibReaderOnPageFinished(state: ZlibReaderWebState, canGoBack: Boolean): ZlibReaderWebState =
    state.copy(loading = false, progress = 100, canGoBack = canGoBack)

internal fun zlibReaderOnMainFrameError(state: ZlibReaderWebState, errorCode: Int): ZlibReaderWebState =
    state.copy(loading = false, failedMessage = zlibReaderWebErrorMessage(errorCode))

internal fun zlibReaderOnHttpError(state: ZlibReaderWebState, statusCode: Int): ZlibReaderWebState {
    val message = zlibReaderHttpErrorMessage(statusCode) ?: return state
    return state.copy(loading = false, failedMessage = message)
}

internal fun zlibReaderOnHistoryChanged(state: ZlibReaderWebState, canGoBack: Boolean): ZlibReaderWebState =
    state.copy(canGoBack = canGoBack)

internal fun zlibReaderOnRetry(state: ZlibReaderWebState): ZlibReaderWebState =
    state.copy(loading = true, progress = 0, failedMessage = null)
