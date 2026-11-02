package app.particlebook.android.zlib

/**
 * 网络/解析层唯一对外异常类型；UI 按 [Kind] 映射文案与重试策略。
 */
class ZlibException(
    message: String,
    val kind: Kind,
    cause: Throwable? = null,
) : Exception(message, cause) {

    enum class Kind {
        /** 所有镜像探测失败。 */
        NO_MIRROR,

        /** 反爬挑战页拿不到常量或多次重试仍被拦。 */
        CHALLENGE_FAILED,

        /** 非预期 HTTP 状态码。 */
        HTTP,

        /** 页面结构与预期不符（既无结果卡片也无空态标记）。 */
        PARSE,

        /** 连接/读取失败（含超时）。 */
        NETWORK,
    }

    override fun toString(): String = "ZlibException(kind=$kind, message=$message)"
}
