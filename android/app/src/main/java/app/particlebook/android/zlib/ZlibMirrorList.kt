package app.particlebook.android.zlib

import java.net.URI

/**
 * 动态镜像名单（P1c ③ / B2）：源站 https://zlib.wwwnav.com/（旧源 zz.ggonav.com 已 NXDOMAIN）。
 *
 * 生产链路：[parseNav] 解析页面 `section#entries` 的三个面板——
 * 「镜像入口」→ 动态线路（T1）、「官网地址」→ 官方线路（T0 尾部）、
 * 「客户端与资源下载」整块丢弃；排除 quark / pages.dev / wwwnav 子域与其他第三方站。
 * 缓存与退避由 [ZlibMirrorRegistry] 负责（6h TTL、失败 30min→2h 指数退避），本对象不联网。
 *
 * 兼容保留：通用 [parse] / [merge] / [cached] / [markAttempt] / [shouldRefresh] 是旧版
 * 「抓页面扫所有 <a href> 与裸地址」的实现，只用于旧单测与 A/B 对照；新代码请用 [parseNav]。
 * 通用解析的 host 白名单：z-lib / z-library / zlibrary / zlib / 1lib 前缀，
 * singlelogin / bookfi / fbiwarning / dfj101 / zzz101 包含；永久黑名单 singlelogin.re。
 */
internal object ZlibMirrorList {

    /**
     * 导航页地址：`zlib.wwwnav.com`（2026-11-03 实测 200/32,003B/直连 0.44s，无 CF/无 JS 挑战）。
     * 旧源 `zz.ggonav.com` 已 NXDOMAIN，勿改回。
     */
    const val NAV_URL: String = "https://zlib.wwwnav.com/"

    /** 内存缓存时长：1h。 */
    const val CACHE_TTL_MS: Long = 3_600_000L

    private val ANCHOR = Regex("""<a\s[^>]*href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val BARE_URL = Regex("""https?://[A-Za-z0-9.-]+\.[a-z]{2,}/?""", RegexOption.IGNORE_CASE)
    private val TAG_RE = Regex("<[^>]+>")
    private val ENTRIES_ID_RE = Regex("""id\s*=\s*["']entries["']""", RegexOption.IGNORE_CASE)

    /** 镜像真实用法：zh.z-lib.gd / zh.z-library.sk / zlib.re / zh.zlib0.ru / zh.1lib.sk。 */
    private val PREFIX_TOKENS = listOf("z-lib", "z-library", "zlibrary", "zlib", "1lib")

    /** 出现在域名标签里的历史别名：zh.singlelogin.rs / zh.101fbiwarning.ru / zh.dfj101.ru / zh.zzz101.ru。 */
    private val CONTAINS_TOKENS = listOf("singlelogin", "bookfi", "fbiwarning", "dfj101", "zzz101")

    /** CDN / 统计 / 文档 / 社交，出现在导航页里很正常但绝不是线路（对齐桌面端 IsThirdPartyHost）。 */
    private val THIRD_PARTY_DOMAINS = setOf(
        "x.com", "t.me", "qq.com", "google.com", "gstatic.com", "googleapis.com",
        "googletagmanager.com", "googleadservices.com", "googlesyndication.com", "doubleclick.net",
        "cloudflare.com", "cdnjs.com", "jsdelivr.net", "unpkg.com", "bootstrapcdn.com",
        "jquery.com", "tailwindcss.com", "fontawesome.com", "github.com", "githubusercontent.com",
        "gitlab.com", "wikipedia.org", "w3.org", "schema.org", "mozilla.org", "microsoft.com",
        "adobe.com", "youtube.com", "facebook.com", "twitter.com", "instagram.com", "discord.com",
        "reddit.com", "medium.com", "wordpress.com", "blogspot.com", "sentry.io", "matomo.org",
        "cnzz.com", "baidu.com", "weibo.com", "zhihu.com", "bilibili.com", "taobao.com", "jd.com",
    )
    private val THIRD_PARTY_TOKENS = listOf("cdnjs", "googletag", "googlead", "analytics", "adservice", "doubleclick")

    /** 永久黑名单：曾是镜像、现为成人站。 */
    private val BLOCKED_DOMAINS = listOf("singlelogin.re")

    @Volatile
    private var discovered: List<String> = emptyList()

    @Volatile
    private var fetchedAtMs: Long = 0L

    /** 解析导航页 HTML → 规范化镜像根地址列表（含尾部 `/`，已剔除黑名单与第三方站）。 */
    fun parse(html: String): List<String> {
        val found = LinkedHashSet<String>()
        ANCHOR.findAll(html).forEach { match -> addCandidate(found, match.groupValues[1]) }
        BARE_URL.findAll(html).forEach { match -> addCandidate(found, match.value) }
        return found.toList()
    }

    /** 导航页解析结果：官网面板 official / 镜像面板 dynamic / 被排除的行数。 */
    data class NavResult(
        val official: List<String>,
        val dynamic: List<String>,
        val skipped: Int,
    )

    /**
     * T24④：导航源判活。只认真正的新版式导航页（section#entries + 镜像入口/官网地址面板标题），
     * 避免软 404 / 广告落地页 / 站点改版后的 200 HTML 被旧 fallback 解析器当成动态线路来源。
     */
    fun isNavPage(html: String): Boolean {
        if (html.isBlank()) return false
        if (!ENTRIES_ID_RE.containsMatchIn(html)) return false
        return html.contains("镜像入口") || html.contains("官网地址")
    }

    /**
     * 解析 wwwnav 导航页（2026-11-03 版式）：section#entries > div.panels > div.panel，
     * 每面板 <h3>标题</h3> + 若干 .erow > a.btn[href]。
     *
     * 面板规则：h3 含「镜像入口」-> dynamic；含「官网地址」-> official；其他面板（客户端/资源下载）
     * 的链接不收，但行仍参与 skipped 计数（research 断言 wwwnav：official=3 / dynamic=3 / skipped=4）。
     * 地址仍走 [normalize]：只认站点根地址 + 信任域，pages.dev / quark / wwwnav 等自然被剔除；
     * 永久黑名单 singlelogin.re 继续排除。
     */
    fun parseNav(html: String): NavResult {
        val sectionStart = html.indexOf("id=\"entries\"")
        val body = if (sectionStart >= 0) html.substring(sectionStart) else html
        val panels = splitPanels(body)
        if (panels.isEmpty()) {
            // 非 wwwnav 版式：退回旧解析器，避免整页结构变化时动态源直接失效。
            return NavResult(official = emptyList(), dynamic = parse(body), skipped = 0)
        }
        val official = LinkedHashSet<String>()
        val dynamic = LinkedHashSet<String>()
        var skipped = 0
        for (panel in panels) {
            val title = panelTitle(panel)
            val isOfficial = title.contains("官网")
            val isMirror = title.contains("镜像")
            for (tag in anchorTags(panel)) {
                if (!hasBtnClass(tag)) continue
                val href = attrOf(tag, "href") ?: continue
                val normalized = normalize(href)?.takeIf { !isBlocked(it) }
                if (normalized == null) {
                    skipped++
                    continue
                }
                if (isOfficial) official.add(normalized)
                else if (isMirror) dynamic.add(normalized)
            }
        }
        return NavResult(official.toList(), dynamic.toList(), skipped)
    }

    private fun splitPanels(html: String): List<String> {
        val out = ArrayList<String>()
        var index = html.indexOf("<div class=\"panel\"")
        while (index >= 0) {
            val next = html.indexOf("<div class=\"panel\"", index + 1)
            out.add(if (next >= 0) html.substring(index, next) else html.substring(index))
            index = next
        }
        return out
    }

    private fun panelTitle(panel: String): String {
        val start = panel.indexOf("<h3")
        if (start < 0) return ""
        val end = panel.indexOf("</h3>", start)
        if (end < 0) return ""
        val open = panel.indexOf('>', start)
        if (open < 0 || open >= end) return ""
        return unescapeHtml(panel.substring(open + 1, end).replace(TAG_RE, "")).trim()
    }

    private fun anchorTags(html: String): List<String> {
        val out = ArrayList<String>()
        var index = html.indexOf("<a ", ignoreCase = true)
        while (index >= 0) {
            val end = html.indexOf('>', index)
            if (end < 0) break
            out.add(html.substring(index, end + 1))
            index = html.indexOf("<a ", end + 1, ignoreCase = true)
        }
        return out
    }

    private fun hasBtnClass(tag: String): Boolean {
        val value = attrOf(tag, "class") ?: return false
        return value.split(' ').any { it.equals("btn", ignoreCase = true) }
    }

    private fun attrOf(tag: String, name: String): String? =
        attrOf(tag, name, '"') ?: attrOf(tag, name, '\'')

    private fun attrOf(tag: String, name: String, quote: Char): String? {
        val marker = name + "=" + quote
        val lower = tag.lowercase()
        var index = lower.indexOf(marker)
        if (index >= 0) {
            val valueStart = index + marker.length
            val end = tag.indexOf(quote, valueStart)
            return if (end > valueStart) tag.substring(valueStart, end) else null
        }
        val spaced = name + " = " + quote
        index = lower.indexOf(spaced)
        if (index < 0) return null
        val valueStart = index + spaced.length
        val end = tag.indexOf(quote, valueStart)
        return if (end > valueStart) tag.substring(valueStart, end) else null
    }

    private fun unescapeHtml(value: String): String = value
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")

    /** 候选过滤 + 规范化：合法则返回 `https://host/`（scheme/host 小写），否则 null。 */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.contains('#') || trimmed.contains('?')) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path.orEmpty()
        if (path.isNotEmpty() && path != "/") return null
        if (!host.contains('.') || host.contains(' ')) return null
        if (!isTrustedHost(host)) return null
        if (isThirdPartyHost(host)) return null
        return scheme + "://" + host + "/"
    }

    /**
     * host 是否属于 Z-Library 域名族（D9 修复）。
     *
     * 只看 TLD 前的主域名标签：z-lib.by / zh.z-library.sk / zlib0.ru / 1lib.sk /
     * zh.101fbiwarning.ru 都命中；zlib.evil.com（主域名 evil.com）必须拒绝 ——
     * 旧实现对任意标签做前缀匹配，任何以 zlib 开头的子域都能过。
     */
    fun isTrustedHost(host: String): Boolean {
        val h = host.trim().lowercase()
        if (h.isEmpty() || h.contains(' ') || h.startsWith("[")) return false
        val bare = h.substringBefore(':').trimEnd('.')
        val labels = bare.split('.')
        if (labels.size < 2) return false
        if (labels.any { it.isEmpty() }) return false
        val main = labels[labels.size - 2]
        return PREFIX_TOKENS.any { main.startsWith(it) } ||
            CONTAINS_TOKENS.any { main.contains(it) }
    }

    /** 永久黑名单判定（singlelogin.re 及其子域）。 */
    fun isBlocked(url: String): Boolean {
        val host = hostOf(url)
        if (host.isEmpty()) return true
        return BLOCKED_DOMAINS.any { host.contains(it) }
    }

    fun hostOf(url: String): String = runCatching { URI(url.trim()).host.orEmpty().lowercase() }.getOrDefault("")

    /**
     * 静态名单在前、动态发现追加在后，按规范化地址去重并剔除黑名单。
     *
     * 顺序保留静态名单的优先级：导航页里混着推广页，探测落地前先把用户送进真正的 Z-Library。
     */
    fun merge(staticMirrors: List<String>, dynamic: List<String>): List<String> {
        val merged = LinkedHashSet<String>()
        staticMirrors.forEach { raw -> normalize(raw)?.let { if (!isBlocked(it)) merged.add(it) } }
        dynamic.forEach { raw -> normalize(raw)?.let { if (!isBlocked(it)) merged.add(it) } }
        return merged.toList()
    }

    fun cached(): List<String> = discovered

    /** 距上次抓取是否已过 1h（含从未抓过）。 */
    fun shouldRefresh(nowMs: Long): Boolean =
        fetchedAtMs == 0L || nowMs - fetchedAtMs >= CACHE_TTL_MS

    /**
     * 记录一次抓取结果（成功或失败都记）。失败时传空列表：静默回退静态名单，1h 内不再重试，
     * 避免弱网下每次搜索都白等一个导航页请求。
     */
    @Synchronized
    fun markAttempt(result: List<String>, nowMs: Long) {
        discovered = result
        fetchedAtMs = nowMs
    }

    /** 单测复位（生产代码不调用）。 */
    @Synchronized
    fun resetForTest() {
        discovered = emptyList()
        fetchedAtMs = 0L
    }

    private fun addCandidate(out: MutableSet<String>, raw: String) {
        val normalized = normalize(raw) ?: return
        if (isBlocked(normalized)) return
        out.add(normalized)
    }

    private fun isThirdPartyHost(host: String): Boolean {
        val h = host.lowercase()
        if (THIRD_PARTY_DOMAINS.any { h == it || h.endsWith("." + it) }) return true
        return THIRD_PARTY_TOKENS.any { h.contains(it) }
    }
}
