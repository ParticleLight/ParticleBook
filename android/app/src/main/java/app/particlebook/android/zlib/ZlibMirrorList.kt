package app.particlebook.android.zlib

import java.net.URI

/**
 * 动态镜像名单（P1c ③）：GET https://zz.ggonav.com/ 是第三方导航页，页面里的 `<a href>`
 * 与正文裸地址才是推荐线路。解析规则对齐桌面端
 * `particlebook-cpp/src/services/ZLibraryMirror.cpp`（SEC-01）：
 *
 * 1. 只认 `<a href>` 与裸 `http(s)://` 地址（旧写法 `href="..."` 会把 CDN 样式表收进来）；
 * 2. 必须是站点根地址（path 为空或 `/`，无 query/fragment）；
 * 3. host 必须属于 Z-Library 域名族（z-lib / z-library / zlibrary / zlib / 1lib 前缀，
 *    singlelogin / bookfi / fbiwarning / dfj101 / zzz101 包含），且不是 CDN/统计/文档站；
 * 4. 永久黑名单 singlelogin.re（现为成人站）在合并阶段剔除；
 * 5. 导航页域名排在静态名单之后（导航页混有推广页，静态域名更可靠）。
 *
 * 缓存语义：进程内缓存 1h；失败（html 为空）静默回退静态名单，1h 内不再重试。
 * 本对象不联网，网络抓取由 [ZlibClient] 负责（避免 UI 线程做 IO）。
 */
internal object ZlibMirrorList {

    /** 导航页地址（桌面端 ZLibraryMirror.cpp:498 同款）。 */
    const val NAV_URL: String = "https://zz.ggonav.com/"

    /** 内存缓存时长：1h。 */
    const val CACHE_TTL_MS: Long = 3_600_000L

    private val ANCHOR = Regex("""<a\s[^>]*href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val BARE_URL = Regex("""https?://[A-Za-z0-9.-]+\.[a-z]{2,}/?""", RegexOption.IGNORE_CASE)

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

    /** host 是否属于 Z-Library 域名族（按域名标签匹配，避免 zlib.evil.com 之外还放过 evil.com）。 */
    fun isTrustedHost(host: String): Boolean {
        val h = host.trim().lowercase()
        if (h.isEmpty() || h.contains(' ') || h.startsWith("[")) return false
        val bare = h.substringBefore(':')
        return bare.split('.').any { label ->
            label.isNotEmpty() && (
                PREFIX_TOKENS.any { label.startsWith(it) } ||
                    CONTAINS_TOKENS.any { label.contains(it) }
                )
        }
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
