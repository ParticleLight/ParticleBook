package app.particlebook.android.ui

import java.net.URI
import java.util.Locale

/**
 * A13：在线阅读（reader.z-lib.by）链接的提取 / 校验，以及抓详情 HTML 时的中间页识别，
 * 纯 Kotlin，JVM 单测可直接覆盖。
 *
 * 详情页主块里的入口是隐藏锚：
 * `<a class="btn btn-primary dlButton reader-link" href=https://reader.z-lib.by/read/...>`。
 * 真实 HTML 的 href **没有引号**，查询串里的 & 也没有转义，所以不能只用带引号的正则；
 * 这里先解析 <a ...> 标签属性，再取 class 含 reader-link 的锚的 href。
 * 兜底：整页没有 reader-link 锚（站点改版）时，扫第一个 reader 域 /read/ 链接
 * （详情页「Create a quote」分享链接实测也带完整票据）。
 *
 * 链接里自带 file_access_token，交给系统浏览器直接打开即可；不要为了取票据而把 URL 拼进日志。
 */
internal object ZlibReaderUrl {

    const val READER_HOST = "reader.z-lib.by"

    /** 解析失败原因；UI 文案映射见 [zlibReaderUrlFailureMessage]。 */
    enum class Failure {
        NOT_FOUND,
        MALFORMED,
        INSECURE_SCHEME,
        UNTRUSTED_HOST,
        NOT_READ_PATH,
        MISSING_TICKET,
    }

    sealed interface ParseResult {
        data class Ok(val url: String) : ParseResult
        data class Failed(val reason: Failure) : ParseResult
    }

    /**
     * task-58：fetchBytes 抓详情 HTML 时可能拿到 200 的中间页。
     * cookie 失效会跟随重定向落到登录页；挑战/网关拦截会落人机验证页。
     * 这两种页面都没有 reader-link，若直接 extract 会误报「未提供入口」。
     */
    enum class InterstitialPage {
        NONE,
        LOGIN,
        CHALLENGE,
    }

    private val TEMPLATE_BLOCK = Regex("""<template[\s\S]*?</template>""", RegexOption.IGNORE_CASE)
    private val ANCHOR = Regex("""<a\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val ATTRIBUTE = Regex(
        """([A-Za-z_][-A-Za-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""",
    )
    private val FALLBACK_URL = Regex(
        """https://reader\.z-lib\.by/read/[^\s"'<>]+""",
        RegexOption.IGNORE_CASE,
    )
    private val ENTITY = Regex("""&(#x?[0-9A-Fa-f]+|[A-Za-z]+);""")
    private val TICKET_KEYS = setOf("file_access_token", "access_token")

    /**
     * 登录页判据：真实 /login 页面含密码输入框，下载墙页面用文案。
     * 特意不用「/login?redirectUrl=」——匿名详情页导航里也带它，会把正常详情页误判成登录页。
     */
    private val LOGIN_PAGE_MARKERS = listOf(
        "type=\"password\"",
        "type='password'",
        "name=\"password\"",
        "name='password'",
        "log in to access",
        "sign in to download",
        "please sign in",
        "请先登录",
    )

    /** 站点挑战页判据（真实 fixture：challenge.html / challenge-rotated.html）。 */
    private val CHALLENGE_PAGE_MARKERS = listOf(
        "checking your browser",
        "wait a moment",
        "just a moment",
        "cf-challenge",
        "cf_chl_opt",
        "challenge-platform",
    )

    /** 识别 200 中间页：先判挑战（挑战页文案也可能含登录字样），再判登录。 */
    fun classifyInterstitial(html: String): InterstitialPage {
        if (html.isEmpty()) return InterstitialPage.NONE
        val lower = html.lowercase(Locale.US)
        if (CHALLENGE_PAGE_MARKERS.any { lower.contains(it) }) return InterstitialPage.CHALLENGE
        if (LOGIN_PAGE_MARKERS.any { lower.contains(it) }) return InterstitialPage.LOGIN
        return InterstitialPage.NONE
    }

    /** 从详情页 HTML 找 reader-link 锚的 href；找不到时兜底扫首个 reader 域 /read/ 链接。 */
    fun extract(html: String): ParseResult {
        val scanText = TEMPLATE_BLOCK.replace(html, " ")
        var anchorFailure: Failure? = null
        for (match in ANCHOR.findAll(scanText)) {
            val attrs = parseAttributes(match.groupValues[1])
            if (!isReaderLink(attrs["class"])) continue
            val href = decodeEntities(attrs["href"].orEmpty()).trim()
            if (href.isEmpty()) {
                anchorFailure = Failure.MALFORMED
                continue
            }
            when (val parsed = validate(href)) {
                is ParseResult.Ok -> return parsed
                is ParseResult.Failed -> anchorFailure = parsed.reason
            }
        }
        val fallback = FALLBACK_URL.find(scanText)?.value?.let(::decodeEntities)?.trim().orEmpty()
        if (fallback.isNotEmpty()) {
            when (val parsed = validate(fallback)) {
                is ParseResult.Ok -> return parsed
                is ParseResult.Failed -> if (anchorFailure == null) anchorFailure = parsed.reason
            }
        }
        return ParseResult.Failed(anchorFailure ?: Failure.NOT_FOUND)
    }

    /**
     * 校验 reader URL：只接受 https://reader.z-lib.by/read/... 且带访问票据。
     * 拒绝 userinfo、非 https、其它 host（含子域/仿冒域）与缺票据链接。
     */
    fun validate(raw: String): ParseResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ParseResult.Failed(Failure.MALFORMED)
        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            return ParseResult.Failed(Failure.MALFORMED)
        }
        if (uri.scheme?.lowercase(Locale.US) != "https") {
            return ParseResult.Failed(Failure.INSECURE_SCHEME)
        }
        if (uri.rawUserInfo != null) return ParseResult.Failed(Failure.MALFORMED)
        val host = uri.host?.lowercase(Locale.US)?.removeSuffix(".")
        if (host != READER_HOST) return ParseResult.Failed(Failure.UNTRUSTED_HOST)
        if (!uri.rawPath.orEmpty().startsWith("/read/")) {
            return ParseResult.Failed(Failure.NOT_READ_PATH)
        }
        if (!hasTicket(uri.rawQuery)) return ParseResult.Failed(Failure.MISSING_TICKET)
        return ParseResult.Ok(trimmed)
    }

    private fun hasTicket(rawQuery: String?): Boolean {
        if (rawQuery.isNullOrBlank()) return false
        return rawQuery.split('&').any { part ->
            val index = part.indexOf('=')
            if (index <= 0) {
                false
            } else {
                part.substring(0, index).lowercase(Locale.US) in TICKET_KEYS &&
                    part.substring(index + 1).isNotBlank()
            }
        }
    }

    private fun isReaderLink(className: String?): Boolean {
        if (className.isNullOrBlank()) return false
        return className.split(' ').any { token -> token.trim().equals("reader-link", ignoreCase = true) }
    }

    private fun parseAttributes(text: String): Map<String, String> {
        if (text.isBlank()) return emptyMap()
        val result = HashMap<String, String>()
        for (match in ATTRIBUTE.findAll(text)) {
            val name = match.groupValues[1].lowercase(Locale.US)
            val value = when {
                match.groupValues[2].isNotEmpty() -> match.groupValues[2]
                match.groupValues[3].isNotEmpty() -> match.groupValues[3]
                else -> match.groupValues[4]
            }
            if (name !in result) result[name] = value
        }
        return result
    }

    private fun decodeEntities(raw: String): String {
        if ('&' !in raw) return raw
        return ENTITY.replace(raw) { match ->
            when (val body = match.groupValues[1]) {
                "amp" -> "&"
                "quot" -> "\""
                "apos" -> "'"
                "lt" -> "<"
                "gt" -> ">"
                "nbsp" -> "\u00A0"
                else -> {
                    val codePoint = when {
                        body.startsWith("#x", ignoreCase = true) -> body.drop(2).toIntOrNull(16)
                        body.startsWith("#") -> body.drop(1).toIntOrNull()
                        else -> null
                    }
                    if (codePoint != null && Character.isValidCodePoint(codePoint)) {
                        String(Character.toChars(codePoint))
                    } else {
                        match.value
                    }
                }
            }
        }
    }
}
