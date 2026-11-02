package app.particlebook.android.zlib

/**
 * 错误文案映射（§3.5）：zlib 包统一提供，UI 直接引用，不要自己猜文案。
 *
 * 网络层抛 [ZlibException] 时，UI 用 [from] 得到展示文案；下载/登录等业务错误
 * （DAILY_LIMIT / LOGIN_REQUIRED）有专门文案。
 */
object ZlibErrorMessages {

    const val DAILY_LIMIT: String = "今日下载额度已用完，请登录账号后重试"
    const val LOGIN_REQUIRED: String = "请先登录 Z-Library"
    const val NO_MIRROR: String = "所有 Z-Library 线路暂时不可达，请稍后重试或手动切换线路"
    const val CHALLENGE_FAILED: String = "无法通过 Z-Library 的安全校验，请稍后重试"
    const val HTTP: String = "服务器返回异常（HTTP 错误）"
    const val PARSE: String = "页面格式无法识别，请稍后重试"
    const val NETWORK: String = "网络异常，请检查网络后重试"
    const val DOWNLOAD: String = "下载失败，请重试"
    const val CANCELLED: String = "下载已取消"

    /** 按异常类型取用户可读文案；非 [ZlibException] 一律按网络异常。 */
    fun from(error: Throwable): String = when (error) {
        is ZlibException -> of(error.kind)
        else -> NETWORK
    }

    fun of(kind: ZlibException.Kind): String = when (kind) {
        ZlibException.Kind.NO_MIRROR -> NO_MIRROR
        ZlibException.Kind.CHALLENGE_FAILED -> CHALLENGE_FAILED
        ZlibException.Kind.HTTP -> HTTP
        ZlibException.Kind.PARSE -> PARSE
        ZlibException.Kind.NETWORK -> NETWORK
        ZlibException.Kind.DAILY_LIMIT -> DAILY_LIMIT
        ZlibException.Kind.LOGIN_REQUIRED -> LOGIN_REQUIRED
        ZlibException.Kind.DOWNLOAD -> DOWNLOAD
        ZlibException.Kind.CANCELLED -> CANCELLED
    }
}
