package app.particlebook.android.ui

import app.particlebook.android.zlib.ZlibException
import java.net.URI

/**
 * P1b 错误文案（spec §3.5/§4）：
 * 用 kind.name 做字符串匹配，不硬编码枚举常量——网络层新增 DAILY_LIMIT / LOGIN_REQUIRED / DOWNLOAD
 * 时 UI 不需要同步发版，未知 kind 回退到异常 message。
 */
object ZlibUiError {

    fun message(throwable: Throwable?): String {
        if (throwable == null) return "操作失败，请稍后重试。"
        val kind = (throwable as? ZlibException)?.kind?.name
        return when (kind) {
            "NO_MIRROR" -> "所有 Z-Library 镜像暂时不可达，请稍后重试或手动切换线路"
            "DAILY_LIMIT" -> "今日下载额度已用完，请登录账号后重试"
            "LOGIN_REQUIRED" -> "请先登录 Z-Library"
            "CHALLENGE_FAILED" -> "反爬验证未通过，请稍后重试"
            "HTTP" -> "服务器返回错误，请稍后重试"
            "PARSE" -> "页面解析失败：镜像可能已改版"
            "NETWORK" -> "网络连接异常，请检查网络后重试"
            "DOWNLOAD" -> "下载失败，请稍后重试"
            else -> throwable.message?.takeIf { it.isNotBlank() } ?: "操作失败，请稍后重试。"
        }
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
