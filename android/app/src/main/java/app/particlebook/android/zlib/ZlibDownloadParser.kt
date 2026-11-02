package app.particlebook.android.zlib

import java.net.URI
import java.net.URLDecoder

/**
 * /dl 页面解析（P1b 3.3 状态机，纯函数可单测）。
 *
 * 实测（未登录+匿名额度用尽）：body 含「Daily limit reached」。
 * 登录后页面结构未实测 ⇒ 解析必须宽松，设备端用 debug 片段迭代。
 */
object ZlibDownloadParser {

    sealed class PageState {
        data class DailyLimit(val message: String) : PageState()

        data class LoginRequired(val message: String?) : PageState()

        data class Countdown(val seconds: Int) : PageState()

        data class Candidates(val links: List<String>) : PageState()

        object Unknown : PageState()
    }

    private val ANCHOR = Regex("""<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("""([A-Za-z_][-A-Za-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val DATA_URL = Regex("""data-(?:download-)?url\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val DATA_COUNTDOWN = Regex("""data-countdown\s*=\s*["']?(\d{1,4})""", RegexOption.IGNORE_CASE)
    private val META_REFRESH = Regex(
        """<meta[^>]*http-equiv\s*=\s*["']refresh["'][^>]*content\s*=\s*["']\s*(\d{1,3})""",
        RegexOption.IGNORE_CASE,
    )
    private val WAIT_SECONDS = Regex("""([0-9]{1,3})\s*(?:s|sec|seconds)\b""", RegexOption.IGNORE_CASE)
    private val DAILY_PHRASE = "Daily limit reached"
    private val LOGIN_MARKERS = listOf(
        "/login?redirectUrl=",
        "Log In to access",
        "log in to access",
        "sign in to download",
        "Please sign in",
        "请先登录",
    )
    private val COUNTDOWN_MARKERS = listOf("Please wait", "data-countdown", "http-equiv=")

    /** 按 3.3 顺序判定页面类型。 */
    fun classify(html: String, pageUrl: String): PageState {
        dailyLimitMessage(html)?.let { return PageState.DailyLimit(it) }
        loginRequiredMessage(html)?.let { return PageState.LoginRequired(it) }
        countdownSeconds(html)?.let { return PageState.Countdown(it) }
        val links = extractCandidates(html, pageUrl)
        if (links.isNotEmpty()) return PageState.Candidates(links)
        return PageState.Unknown
    }

    /** 匿名额度用尽页面：提取提示原文（登录引导用）。 */
    fun dailyLimitMessage(html: String): String? {
        val index = html.indexOf(DAILY_PHRASE, ignoreCase = true)
        if (index < 0) return null
        val from = (index - 600).coerceAtLeast(0)
        val to = (index + 1_200).coerceAtMost(html.length)
        val text = ZlibHtml.clean(html.substring(from, to))
        return text.takeIf { it.isNotEmpty() }?.take(300)
    }

    /** 登录墙判据（3.3 第 2 条）。 */
    fun loginRequiredMessage(html: String): String? {
        val marker = LOGIN_MARKERS.firstOrNull { html.contains(it, ignoreCase = true) } ?: return null
        val index = html.indexOf(marker, ignoreCase = true).coerceAtLeast(0)
        val from = (index - 400).coerceAtLeast(0)
        val to = (index + 800).coerceAtMost(html.length)
        val text = ZlibHtml.clean(html.substring(from, to))
        return text.takeIf { it.isNotEmpty() }?.take(200)
    }

    /** 倒计时页面（3.3 第 3 条）：返回建议等待秒数。 */
    fun countdownSeconds(html: String): Int? {
        if (COUNTDOWN_MARKERS.none { html.contains(it, ignoreCase = true) }) return null
        DATA_COUNTDOWN.find(html)?.groupValues?.get(1)?.toIntOrNull()?.let { return it.coerceIn(1, 60) }
        META_REFRESH.find(html)?.groupValues?.get(1)?.toIntOrNull()?.let { return it.coerceIn(1, 60) }
        WAIT_SECONDS.find(html)?.groupValues?.get(1)?.toIntOrNull()?.let { return it.coerceIn(1, 60) }
        return 1
    }

    /** 候选直链（3.3 第 4 条）：btn 锚 / data-url / data-download-url。 */
    fun extractCandidates(html: String, pageUrl: String): List<String> {
        val page = runCatching { URI(pageUrl) }.getOrNull()
        val pageHost = page?.host
        val pagePath = page?.path.orEmpty()
        val result = LinkedHashSet<String>()

        DATA_URL.findAll(html).forEach { match ->
            addCandidate(result, match.groupValues[1], "download", pageHost, pagePath)
        }
        ANCHOR.findAll(html).forEach { match ->
            val attrs = parseAttrs(match.groupValues[1])
            val href = attrs["href"]?.trim().orEmpty()
            val classes = attrs["class"].orEmpty()
            if (!classes.contains("btn", ignoreCase = true) && !classes.contains("download", ignoreCase = true)) return@forEach
            addCandidate(result, href, classes, pageHost, pagePath)
        }
        return result.toList()
    }

    /** Content-Disposition 里的文件名（优先 filename*，其次 filename）。 */
    fun filenameFromDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val extended = Regex("""filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)""", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1)
        val plain = Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1)
        val raw = (extended ?: plain)?.trim() ?: return null
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        return decoded.substringAfterLast('/').substringAfterLast('\\').takeIf { it.isNotBlank() }
    }

    private fun addCandidate(
        result: MutableSet<String>,
        rawHref: String,
        classes: String,
        pageHost: String?,
        pagePath: String,
    ) {
        val href = rawHref.trim()
        if (href.isEmpty()) return
        if (href.startsWith("#") || href.startsWith("javascript:", ignoreCase = true)) return
        if (href.contains("/login") || href.contains("/registration")) return
        if (classes.contains("reader-link", ignoreCase = true)) return
        if (href.contains("reader.")) return

        val uri = runCatching { URI(href) }.getOrNull()
        val absolute = uri?.isAbsolute == true
        if (!absolute) {
            val normalized = href.removePrefix("./")
            if (!normalized.startsWith("/dl/") &&
                !normalized.startsWith("/download/") &&
                !normalized.contains("download", ignoreCase = true)
            ) {
                return
            }
            if (normalized == pagePath) return
        } else if (uri != null) {
            val host = uri.host.orEmpty()
            val path = uri.path.orEmpty()
            // 语言子域（zh.z-lib.by/dl/<same hash>）不是直链，跳过。
            if (path == pagePath && host != pageHost) return
            if (path == pagePath && host == pageHost) return
        }
        result.add(href)
    }

    private fun parseAttrs(raw: String): Map<String, String> {
        val result = HashMap<String, String>()
        ATTR.findAll(raw).forEach { match ->
            val key = match.groupValues[1].lowercase()
            val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
            result.putIfAbsent(key, value)
        }
        return result
    }
}

/**
 * Range 断点续传决策（P1c ②）：纯函数、可单测。
 *
 * 规则：.part 存在时先带 `Range: bytes=<len>-` 请求；
 * - 服务端回 206 且 Content-Range 起点一致 → append（续传）；
 * - 回 200 / 起点不一致 / 无 .part → 从头写（truncate），不信任残留文件；
 * - 完成后仍由调用方校验长度并 rename。
 */
internal object ZlibResumePolicy {

    /** append=true 表示追加写；offset 为已有字节数；total 为文件总长（未知时 0）。 */
    data class Decision(val append: Boolean, val offset: Long, val total: Long)

    private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)

    /** Content-Range → (起点, 总长)；总长为 `*` 或解析失败时第二项为 null。 */
    fun contentRange(header: String?): Pair<Long, Long?>? {
        val raw = header?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val match = CONTENT_RANGE.find(raw) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val total = match.groupValues[3].takeIf { it != "*" }?.toLongOrNull()
        return start to total
    }

    /**
     * @param partLength 已有 .part 的字节数（0 = 无）
     * @param status 响应码（200/206）
     * @param contentRange 响应 Content-Range 头
     * @param contentLength 响应 Content-Length（本次响应体长度）
     */
    fun decide(partLength: Long, status: Int, contentRange: String?, contentLength: Long): Decision {
        val range = contentRange(contentRange)
        val totalFromHeader = range?.second
        val base = if (status == 206) partLength else 0L
        val total = totalFromHeader ?: if (contentLength > 0) base + contentLength else 0L
        if (partLength <= 0 || status != 206) return Decision(false, 0L, total)
        val start = range?.first
        if (start != null && start != partLength) {
            // 服务端给了别的区间：不能追加，从头下，避免拼出损坏文件。
            return Decision(false, 0L, totalFromHeader ?: 0L)
        }
        return Decision(true, partLength, total)
    }
}
