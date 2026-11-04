package app.particlebook.android.ui

import app.particlebook.android.zlib.ZlibException
import java.net.URI

/**
 * P1b 错误文案（spec §3.5/§4）：
 * 用 kind.name 做字符串匹配，不硬编码枚举常量——网络层新增 DAILY_LIMIT / LOGIN_REQUIRED / DOWNLOAD
 * 时 UI 不需要同步发版，未知 kind 回退到异常 message。
 *
 * D12：
 * - Kind.HTTP 且 ZlibException.httpCode 非空 → 「服务器返回错误 N」（精确状态码）；
 * - 无状态码时按异常 message 细分「重定向次数过多」/「跳转地址异常」，仍回退原通用文案；
 * - 挑战失败按 message 区分「挑战页结构变化」与「多轮重试仍被拦」。
 */
object ZlibUiError {

    private const val GENERIC = "操作失败，请稍后重试。"
    private const val HTTP = "服务器返回错误，请稍后重试"

    fun message(throwable: Throwable?): String {
        if (throwable == null) return GENERIC
        val zlib = throwable as? ZlibException
        return when (zlib?.kind?.name) {
            "NO_MIRROR" -> "所有 Z-Library 镜像暂时不可达，请稍后重试或手动切换线路"
            "DAILY_LIMIT" -> "今日下载额度已用完，请登录账号后重试"
            "LOGIN_REQUIRED" -> "请先登录 Z-Library"
            "CHALLENGE_FAILED" -> challengeMessage(throwable.message)
            "HTTP" -> httpMessage(zlib.httpCode, throwable.message)
            "PARSE" -> "页面解析失败：镜像可能已改版"
            "NETWORK" -> "网络连接异常，请检查网络后重试"
            "DOWNLOAD" -> "下载失败，请稍后重试"
            else -> throwable.message?.takeIf { it.isNotBlank() } ?: GENERIC
        }
    }

    /**
     * 错误类型副行的中文标签（spec §11.6：不向用户暴露英文枚举）。
     * 未知/未映射类型返回 null，调用方直接隐藏副行。
     */
    fun kindLabel(kindName: String?): String? = when (kindName) {
        "NO_MIRROR" -> "镜像不可达"
        "CHALLENGE_FAILED" -> "安全校验未通过"
        "HTTP" -> "服务器错误"
        "PARSE" -> "页面解析失败"
        "NETWORK" -> "网络异常"
        "DAILY_LIMIT" -> "下载额度已用尽"
        "LOGIN_REQUIRED" -> "需要登录"
        "DOWNLOAD" -> "下载失败"
        "CANCELLED" -> "已取消"
        else -> null
    }

    /**
     * D12：HTTP 失败文案。有真实状态码时给「服务器返回错误 N」；无码时按 message 细分重定向类；
     * 两者都没有时保持原文案（向后兼容）。[rawMessage] 是网络层异常 message（英文诊断）。
     */
    fun httpMessage(httpCode: Int?, rawMessage: String?): String = when {
        httpCode != null -> "服务器返回错误 " + httpCode
        rawMessage == null -> HTTP
        rawMessage.contains("too many redirect", ignoreCase = true) ||
            rawMessage.contains("redirect limit", ignoreCase = true) ->
            "重定向次数过多，请切换线路后重试"
        rawMessage.contains("bad redirect", ignoreCase = true) -> "跳转地址异常，请切换线路后重试"
        else -> HTTP
    }

    /** D12：挑战失败文案细分（拿不到可解常量 = 站点改版；多轮重试仍被拦 = 风控升级）。 */
    fun challengeMessage(rawMessage: String?): String = when {
        rawMessage == null -> "反爬验证未通过，请稍后重试"
        rawMessage.contains("constant", ignoreCase = true) -> "安全校验页面结构变化，请切换线路后重试"
        rawMessage.contains("rounds", ignoreCase = true) -> "安全校验多次重试仍未通过，请稍后重试"
        else -> "反爬验证未通过，请稍后重试"
    }

    /** 登录失败文案：ZLoginResult.Failed.Kind 同样用 name 匹配。 */
    fun loginFailed(kindName: String?, pageMessage: String?): String = when (kindName) {
        // 站点原文是英文（"Incorrect email or password"），验收要求中文文案。
        "BAD_CREDENTIALS" -> "邮箱或密码不正确"
        "CHALLENGE_FAILED" -> "反爬验证未通过，请稍后重试"
        "HTTP" -> "服务器返回错误，请稍后重试"
        "NETWORK" -> "网络连接异常，请检查网络后重试"
        "PARSE" -> "登录结果解析失败，请稍后重试"
        else -> pageMessage?.takeIf { it.isNotBlank() } ?: "登录失败，请稍后重试"
    }
}

/** 镜像 URL → 展示用主域（去掉 scheme 与路径）。 */
fun zlibMirrorHost(url: String): String {
    val raw = url.trim()
    if (raw.isEmpty()) return raw
    return runCatching { URI(raw).host }.getOrNull() ?: raw
}
