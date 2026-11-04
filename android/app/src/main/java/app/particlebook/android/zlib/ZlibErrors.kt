package app.particlebook.android.zlib

/**
 * 错误文案映射（§3.5 / D12）：zlib 包统一提供，UI 直接引用，不要自己猜文案。
 *
 * 网络层抛 [ZlibException] 时，UI 用 [from] 得到展示文案；下载/登录等业务错误
 * （DAILY_LIMIT / LOGIN_REQUIRED）有专门文案。
 *
 * D12：HTTP 类错误按真实状态码给「服务器返回错误 N」；没有码时按异常 message 细分
 * 重定向超限 / 跳转地址异常；挑战失败按 message 区分「页面结构变化」与「多轮重试仍被拦」。
 */
object ZlibErrorMessages {

    const val DAILY_LIMIT: String = "今日下载额度已用完，请登录账号后重试"
    const val LOGIN_REQUIRED: String = "请先登录 Z-Library"
    const val NO_MIRROR: String = "所有 Z-Library 线路暂时不可达，请稍后重试或手动切换线路"
    const val CHALLENGE_FAILED: String = "无法通过 Z-Library 的安全校验，请稍后重试"
    const val HTTP: String = "服务器返回异常（HTTP 错误）"

    /**
     * T37/task-33：HTTP 204 是书籍级确定性不可用（已下架/无文件），不是网络错误。
     * realnet 抽样 35 本 4 本 204，跨 7h、同 hash 换 slug 仍 204。
     */
    const val BOOK_UNAVAILABLE: String = "该书当前不可用（可能已下架）"
    const val PARSE: String = "页面格式无法识别，请稍后重试"
    const val NETWORK: String = "网络异常，请检查网络后重试"
    const val DOWNLOAD: String = "下载失败，请重试"
    const val CANCELLED: String = "下载已取消"

    /** D12：重定向上限 / 跳转地址异常（[ZlibException.Kind.HTTP] 无状态码时细分）。 */
    const val TOO_MANY_REDIRECTS: String = "重定向次数过多，线路可能异常，请切换线路后重试"
    const val BAD_REDIRECT: String = "跳转地址异常，请切换线路后重试"

    /** D12：挑战页结构变化（拿不到可解常量）与多轮重试仍被拦，文案比通用挑战失败更具体。 */
    const val CHALLENGE_PAGE_CHANGED: String = "安全校验页面结构变化，请切换线路或稍后重试"
    const val CHALLENGE_RETRY_EXHAUSTED: String = "安全校验多次重试仍未通过，请稍后重试或切换线路"

    /** D12：带真实 HTTP 状态码时优先用「服务器返回错误 N」；无码保持原文案。204 特判为书籍不可用。 */
    fun http(code: Int?): String = when {
        code == 204 -> BOOK_UNAVAILABLE
        code != null -> "服务器返回错误 " + code
        else -> HTTP
    }

    /** 按异常类型取用户可读文案；非 [ZlibException] 一律按网络异常。 */
    fun from(error: Throwable): String = when (error) {
        is ZlibException -> of(error.kind, error.httpCode, error.message)
        else -> NETWORK
    }

    /**
     * 按 kind 取文案。[httpCode] / [rawMessage] 可选：旧的 [of] 单参调用保持兼容，
     * 传了它们才能得到 D12 的细分文案。
     */
    fun of(
        kind: ZlibException.Kind,
        httpCode: Int? = null,
        rawMessage: String? = null,
    ): String = when (kind) {
        ZlibException.Kind.NO_MIRROR -> NO_MIRROR
        ZlibException.Kind.CHALLENGE_FAILED -> challenge(rawMessage)
        ZlibException.Kind.HTTP -> httpOrRedirect(httpCode, rawMessage)
        ZlibException.Kind.PARSE -> PARSE
        ZlibException.Kind.NETWORK -> NETWORK
        ZlibException.Kind.DAILY_LIMIT -> DAILY_LIMIT
        ZlibException.Kind.LOGIN_REQUIRED -> LOGIN_REQUIRED
        ZlibException.Kind.DOWNLOAD -> DOWNLOAD
        ZlibException.Kind.CANCELLED -> CANCELLED
    }

    private fun httpOrRedirect(httpCode: Int?, rawMessage: String?): String = when {
        httpCode != null -> http(httpCode)
        mentionsTooManyRedirects(rawMessage) -> TOO_MANY_REDIRECTS
        mentionsBadRedirect(rawMessage) -> BAD_REDIRECT
        else -> HTTP
    }

    private fun challenge(rawMessage: String?): String = when {
        rawMessage == null -> CHALLENGE_FAILED
        rawMessage.contains("constant", ignoreCase = true) -> CHALLENGE_PAGE_CHANGED
        rawMessage.contains("rounds", ignoreCase = true) -> CHALLENGE_RETRY_EXHAUSTED
        else -> CHALLENGE_FAILED
    }

    private fun mentionsTooManyRedirects(rawMessage: String?): Boolean =
        rawMessage != null &&
            (rawMessage.contains("too many redirect", ignoreCase = true) ||
                rawMessage.contains("redirect limit", ignoreCase = true))

    private fun mentionsBadRedirect(rawMessage: String?): Boolean =
        rawMessage != null && rawMessage.contains("bad redirect", ignoreCase = true)
}
