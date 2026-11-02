package app.particlebook.android.zlib

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.provider.Settings
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Locale

/**
 * Z-Library 只读搜索客户端（阻塞 API，调用方必须放到 IO 线程）。
 *
 * 能力：
 * - 镜像探测（10 个候选 + SharedPreferences 缓存优先）；
 * - 反爬挑战自动求解（[ZlibChallenge]）并带 cookie 重放；
 * - 手动跟随重定向（最多 5 跳），Cookie 跨请求保留（bsrv / c_token / c_time / siteLanguage）；
 * - 代理：显式读取系统全局 `http_proxy`（Settings.Global 优先，android.net.Proxy 兜底）并
 *   传给 `openConnection(proxy)`；DEBUG 包额外回退到 `127.0.0.1:7897`
 *   （配合电脑侧 `adb reverse tcp:7897 tcp:7897`）。release 不会硬编码代理。
 *
 * 用法（Compose/UI 侧）：
 * ```
 * val client = ZlibClient.get(context.applicationContext)   // 进程内单例；传 null context 也能跑，只是不缓存镜像
 * val page = withContext(Dispatchers.IO) { client.search("android") }
 * ```
 * 见 docs/android-port/zlibrary-p1a-spec.md §2。
 */
class ZlibClient(
    private val appContext: Context? = null,
) {

    /** 最近一次可用的镜像（如 `https://z-lib.by`）；未探测前为 null。可被外部覆盖用于固定镜像。 */
    var mirror: String? = null

    /**
     * DEBUG 兜底代理（`host:port` 或 `http://host:port`）；仅在应用 debuggable 时生效，release 忽略。
     * 为空时默认尝试 `127.0.0.1:7897`（见 [LOCAL_PROXY]）。
     */
    var forceProxy: String? = null

    private val cookieJar = CookieManager(null, CookiePolicy.ACCEPT_ALL)

    /**
     * 挑战 cookie（c_token / c_time）按 host 手动维护。
     * java.net.CookieManager 对没有 domain/path 的 HttpCookie 只存不回传（与 JDK 行为一致），
     * 重放时必须把它们并进 Cookie 头，否则服务端会一直判挑战未通过（503）。
     */
    private val challengeCookies =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, String>>()

    @Volatile
    private var uaLogged: Boolean = false

    /**
     * 构造函数禁止网络/DNS/IO：本类可能由 Compose 在主线程 `ZlibClient.get(context)` 创建。
     * 因此 prefs / debuggable / 系统代理解析全部延迟到首次 search()/probeMirrors()（IO 线程）执行。
     */
    private val prefs: SharedPreferences? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** AGP 8 默认 buildConfig=false，因此用 ApplicationInfo 判 debuggable，不引用 BuildConfig。 */
    private val isDebuggable: Boolean by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ((appContext?.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    /**
     * 系统全局代理（Settings.Global["http_proxy"] 优先，其次已废弃的 android.net.Proxy 默认值）。
     *
     * Android 的 HttpURLConnection 不保证读取 global http_proxy，因此不能依赖默认 ProxySelector；
     * 有值时必须显式传给 URL.openConnection(proxy)（见 [openConnection]）。
     * 延迟解析：构造发生在主线程，读 Settings / 建 InetSocketAddress 都可能触发 binder 或 DNS。
     */
    private val systemProxy: Proxy? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { readSystemProxy() }

    /** 代理来源：global（系统设置）/ fallback（debug 本地代理）/ none（直连）。诊断日志用。 */
    @Volatile
    private var proxySource: String = "none"

    /** DEBUG 兜底代理；首次网络请求时在 IO 线程解析（主线程禁止做连接探测）。 */
    @Volatile
    private var fallbackProxy: Proxy? = null

    @Volatile
    private var fallbackChecked: Boolean = false

    /** 首次网络请求（IO 线程）才解析系统代理并打诊断日志。 */
    @Volatile
    private var initialized: Boolean = false

    @Synchronized
    private fun ensureInitialized() {
        if (initialized) return
        val proxy = systemProxy // 触发延迟解析（只读设置 + 构造 Proxy，无 socket）
        proxySource = if (proxy != null) "global" else "none"
        debugLog(
            "proxy=${proxyLabelOrNone(proxy)} source=$proxySource " +
                "selector=${describeSystemProxy()} debuggable=$isDebuggable",
        )
        initialized = true
    }

    private data class HttpResult(
        val uri: URI,
        val status: Int,
        val body: String,
        val server: String? = null,
        val setCookieCount: Int = 0,
    )

    // ---------------------------------------------------------------- public API

    /**
     * 搜索一页。阻塞，调用方放在 IO 线程。
     *
     * @throws ZlibException kind=NO_MIRROR / CHALLENGE_FAILED / HTTP / PARSE / NETWORK
     */
    @Throws(ZlibException::class)
    fun search(query: String, page: Int = 1): ZSearchPage {
        val q = query.trim()
        require(q.isNotEmpty()) { "query must not be blank" }
        require(page >= 1) { "page must be >= 1" }
        ensureInitialized()

        val base = mirror ?: probeMirrors()
        val url = searchUrl(base, q, page)
        val res = getWithChallenge(URI(url))
        if (res.status != 200) {
            throw ZlibException("HTTP ${res.status} for $url", ZlibException.Kind.HTTP)
        }
        return ZlibSearchParser.parse(res.body, q, page)
    }

    /**
     * 探测可用镜像。
     *
     * 顺序：缓存（上次成功）→ [MIRRORS]；已知 DiamWall 站点（307 + __diamwall → 513 空 body，
     * 需要执行 JS）排到候选末尾，只在候选不足时才占名额。最多 [MAX_PROBE_MIRRORS] 个、
     * 整轮 <= [PROBE_BUDGET_MS]、单镜像 <= [PER_MIRROR_BUDGET_MS]；只对 SHA1 挑战站点
     * （[SHA1_CHALLENGE_HOSTS]，即 z-lib.by 类）重试，最多 [MAX_MIRROR_ATTEMPTS] 次（含首次）。
     *
     * @throws ZlibException kind=NO_MIRROR
     */
    @Throws(ZlibException::class)
    fun probeMirrors(): String {
        ensureInitialized()
        val candidates = ArrayList<String>()
        val cached = mirror ?: prefs?.getString(KEY_MIRROR, null)
        cached?.trim()?.takeIf { it.isNotEmpty() }?.let { candidates.add(it.trimEnd('/')) }
        MIRRORS.forEach { if (it !in candidates) candidates.add(it) }

        // z-lib.by 优先；已知 DiamWall 站点排后面，避免占掉仅有的 3 个探测名额。
        val preferred = candidates.filterNot { hostOf(it) in KNOWN_UNSUPPORTED_HOSTS }
        val deferred = candidates.filter { hostOf(it) in KNOWN_UNSUPPORTED_HOSTS }
        val plan = (preferred + deferred).take(MAX_PROBE_MIRRORS)

        val startedAt = System.nanoTime()
        val deadline = startedAt + PROBE_BUDGET_MS * 1_000_000L
        debugLog(
            "probe_start mirrors=${plan.size} budget_ms=$PROBE_BUDGET_MS " +
                "mirror_budget_ms=$PER_MIRROR_BUDGET_MS " +
                "plan=${plan.joinToString(",") { hostOf(it) }}",
        )

        var lastError: String? = null
        var tried = 0
        for (candidate in plan) {
            if (remainingMs(deadline) <= 0L) {
                debugLog("probe_budget_exhausted tried=$tried")
                break
            }
            tried++
            val host = hostOf(candidate)
            val maxAttempts = if (host in SHA1_CHALLENGE_HOSTS) MAX_MIRROR_ATTEMPTS else 1
            val mirrorStart = System.nanoTime()
            // 单镜像预算（重试共享）<= PER_MIRROR_BUDGET_MS，且不越过整轮 deadline。
            val mirrorDeadline = mirrorStart + minOf(PER_MIRROR_BUDGET_MS, remainingMs(deadline)) * 1_000_000L

            for (attempt in 1..maxAttempts) {
                if (remainingMs(mirrorDeadline) < MIN_REQUEST_MS) {
                    debugLog("mirror=$host stage=budget_exhausted attempt=$attempt/$maxAttempts")
                    break
                }
                val attemptStarted = System.nanoTime()
                val attemptDeadline = minOf(mirrorDeadline, deadline)
                try {
                    val res = getWithChallenge(URI("$candidate/s/test"), deadlineNanos = attemptDeadline)
                    val elapsed = (System.nanoTime() - attemptStarted) / 1_000_000L
                    if (res.status == 200 && !looksLikeChallenge(res.body)) {
                        mirror = candidate
                        prefs?.edit()?.putString(KEY_MIRROR, candidate)?.apply()
                        debugLog("mirror=$host stage=ok status=${res.status} attempt=$attempt/$maxAttempts ms=$elapsed")
                        debugLog("probe_result ok=$host")
                        return candidate
                    }
                    // DiamWall：307 后 513 空 body + Server: DiamWall，需执行 JS，原生客户端不可用。
                    if (isDiamWallBlock(res)) {
                        lastError = "$host -> unsupported (HTTP ${res.status}, server=${res.server ?: "-"})"
                        debugLog(
                            "mirror=$host stage=unsupported reason=diamwall status=${res.status} " +
                                "server=${res.server ?: "-"} body_len=${res.body.length} " +
                                "attempt=$attempt/$maxAttempts ms=$elapsed",
                        )
                        break
                    }
                    lastError = "$host -> HTTP ${res.status}"
                    debugLog(
                        "mirror=$host stage=status status=${res.status} err=none " +
                            "server=${res.server ?: "-"} body_len=${res.body.length} " +
                            "attempt=$attempt/$maxAttempts ms=$elapsed",
                    )
                    break
                } catch (e: Exception) {
                    val elapsed = (System.nanoTime() - attemptStarted) / 1_000_000L
                    val root = rootCauseOf(e)
                    lastError = "$host -> ${root.javaClass.simpleName}: ${root.message}"
                    debugLog(
                        "mirror=$host err=${errText(root)} chain=${chainOf(e)} " +
                            "phase=${phaseOf(root)} status=${statusOf(e)} " +
                            "attempt=$attempt/$maxAttempts ms=$elapsed",
                    )
                    if (attempt < maxAttempts) {
                        debugLog("mirror=$host stage=retry attempt=$attempt/$maxAttempts remaining_ms=${remainingMs(mirrorDeadline)}")
                    }
                }
            }
        }
        val totalMs = (System.nanoTime() - startedAt) / 1_000_000L
        debugLog("probe_result fail_all tried=$tried elapsed_ms=$totalMs last=${lastError ?: "none"}")
        throw ZlibException(
            "no available mirror (tried=$tried, ${totalMs}ms, last: $lastError)",
            ZlibException.Kind.NO_MIRROR,
        )
    }

    // ---------------------------------------------------------------- HTTP

    private fun searchUrl(mirrorBase: String, query: String, page: Int): String {
        val base = mirrorBase.trimEnd('/')
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        return "$base/s/$encoded?page=$page"
    }

    /** 发请求；遇到挑战页则求解并重放（最多 [MAX_CHALLENGE_ROUNDS] 轮）。 */
    private fun getWithChallenge(uri: URI, deadlineNanos: Long? = null): HttpResult {
        var res = request(uri, deadlineNanos)
        var round = 0
        val startedAt = System.nanoTime()
        val host = uri.host ?: "unknown"
        while (isChallengeResponse(res)) {
            // 挑战页诊断：503 响应体长度、常量提取、nonce 计算耗时、重放状态码。
            debugLog("challenge host=$host status=${res.status} body_len=${res.body.length} round=$round")
            val solveStarted = System.nanoTime()
            val solution = ZlibChallenge.solve(res.body)
            val solveMs = (System.nanoTime() - solveStarted) / 1_000_000L
            if (solution == null) {
                debugLog("challenge host=$host constant=none solve_ms=$solveMs")
                throw ZlibException(
                    "challenge page without solvable constant (status ${res.status})",
                    ZlibException.Kind.CHALLENGE_FAILED,
                )
            }
            debugLog(
                "challenge host=$host constant=${solution.constant} nonce=${solution.nonce} " +
                    "solve_ms=$solveMs token_len=${solution.token.length}",
            )
            val elapsedSec = (System.nanoTime() - startedAt) / 1_000_000_000.0
            // CookieManager 不会回传无 domain/path 的 cookie，挑战 cookie 必须手动并入 Cookie 头。
            setChallengeCookie(uri, "c_token", solution.token)
            setChallengeCookie(uri, "c_time", String.format(Locale.US, "%.3f", elapsedSec))
            debugLog("challenge_cookie host=$host cookie_names=${cookieNames(uri)}")
            val replayStarted = System.nanoTime()
            res = request(uri, deadlineNanos)
            round++
            debugLog(
                "challenge_replay host=$host round=$round status=${res.status} " +
                    "body_len=${res.body.length} ms=${(System.nanoTime() - replayStarted) / 1_000_000L}",
            )
            if (round > MAX_CHALLENGE_ROUNDS) break
        }
        if (isChallengeResponse(res)) {
            throw ZlibException(
                "challenge failed after $round rounds (status ${res.status})",
                ZlibException.Kind.CHALLENGE_FAILED,
            )
        }
        return res
    }

    /** 逐个尝试代理候选（系统代理 / debug 兜底 / 默认 ProxySelector），首个成功即用。 */
    private fun request(uri: URI, deadlineNanos: Long? = null): HttpResult {
        var last: Exception? = null
        var attempt = 0
        for (proxy in proxyCandidates()) {
            if (deadlineNanos != null && remainingMs(deadlineNanos) < MIN_REQUEST_MS) {
                debugLog("net_skip host=${uri.host} proxy=${proxyLabel(proxy)} remaining_ms=${remainingMs(deadlineNanos)}")
                break
            }
            attempt++
            debugLog("net_attempt host=${uri.host} proxy=${proxyLabel(proxy)} attempt=$attempt")
            try {
                return requestWithProxy(uri, proxy, deadlineNanos)
            } catch (e: Exception) {
                // 挑战/HTTP 类 ZlibException 原样上抛，避免被包成 "network error" 丢信息。
                if (e is ZlibException && e.kind != ZlibException.Kind.NETWORK) throw e
                last = e
                val root = rootCauseOf(e)
                debugLog(
                    "net_fail host=${uri.host} proxy=${proxyLabel(proxy)} attempt=$attempt " +
                        "phase=${phaseOf(root)} err=${errText(root)} chain=${chainOf(e)}",
                )
            }
        }
        val root = last?.let { rootCauseOf(it) }
        val detail = if (root != null) errText(root) else "deadline exhausted before attempt"
        throw ZlibException("network error: $detail", ZlibException.Kind.NETWORK, last)
    }

    private fun requestWithProxy(uri: URI, proxy: Proxy?, deadlineNanos: Long? = null): HttpResult {
        var current = uri
        var hops = 0
        while (true) {
            if (deadlineNanos != null && remainingMs(deadlineNanos) <= 0L) {
                throw ZlibException("request budget exhausted", ZlibException.Kind.NETWORK)
            }
            val conn = openConnection(current, proxy, deadlineNanos)
            try {
                val status = conn.responseCode
                val body = readBody(conn)
                val location = conn.getHeaderField("Location")
                val server = conn.getHeaderField("Server")
                val setCookieCount = storeResponseCookies(current, conn)

                if (status in 300..399 && !location.isNullOrBlank() && hops < MAX_REDIRECTS) {
                    hops++
                    val next = resolveLocation(current, location)
                    debugLog("net_redirect host=${current.host} status=$status to=${next.host} set_cookie=$setCookieCount")
                    current = next
                    continue
                }
                return HttpResult(current, status, body, server, setCookieCount)
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun openConnection(uri: URI, proxy: Proxy?, deadlineNanos: Long? = null): HttpURLConnection {
        val url = uri.toURL()
        val conn = if (proxy == null) {
            url.openConnection() as HttpURLConnection // 默认走 ProxySelector（系统代理）
        } else {
            url.openConnection(proxy) as HttpURLConnection
        }
        conn.instanceFollowRedirects = false
        conn.connectTimeout = timeoutFor(CONNECT_TIMEOUT_MS, deadlineNanos)
        conn.readTimeout = timeoutFor(READ_TIMEOUT_MS, deadlineNanos)
        conn.requestMethod = "GET"
        conn.useCaches = false
        // 挑战首页 / 重放 / 重定向后续 / 搜索页都走本函数：每个 HttpURLConnection 都重新设置头。
        conn.setRequestProperty("User-Agent", DESKTOP_UA)
        conn.setRequestProperty("Accept-Language", ACCEPT_LANGUAGE)
        conn.setRequestProperty("Accept", ACCEPT_HTML)
        conn.setRequestProperty("Accept-Encoding", "identity")
        cookieHeader(uri)?.let { conn.setRequestProperty("Cookie", it) }
        if (!uaLogged) {
            uaLogged = true
            debugLog(
                "ua_sent=${conn.getRequestProperty("User-Agent")?.take(40) ?: "none"} " +
                    "accept=${conn.getRequestProperty("Accept")} lang=${conn.getRequestProperty("Accept-Language")}",
            )
        }
        return conn
    }

    private fun resolveLocation(base: URI, location: String): URI {
        val cleaned = location.trim().replace(" ", "%20")
        return try {
            base.resolve(cleaned)
        } catch (e: IllegalArgumentException) {
            throw ZlibException("bad redirect: $cleaned", ZlibException.Kind.HTTP)
        }
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = try {
            conn.inputStream
        } catch (e: IOException) {
            conn.errorStream
        } ?: return ""
        val bytes = stream.use { input ->
            val out = ByteArrayOutputStream(64 * 1024)
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
        return String(bytes, charsetOf(conn.contentType))
    }

    private fun charsetOf(contentType: String?): Charset {
        val name = contentType?.let { CHARSET_RE.find(it)?.groupValues?.get(1) } ?: return Charsets.UTF_8
        return try {
            Charset.forName(name)
        } catch (e: Exception) {
            Charsets.UTF_8
        }
    }

    // ---------------------------------------------------------------- cookies

    /** 发送用 Cookie 头：CookieManager 保存的 Set-Cookie（bsrv 等）+ 手动维护的挑战 cookie。 */
    private fun cookieHeader(uri: URI): String? {
        val fromJar = try {
            cookieJar.get(uri, emptyMap<String, List<String>>())?.get("Cookie")?.firstOrNull()
        } catch (e: Exception) {
            null
        }
        val host = uri.host ?: return fromJar
        val manual = challengeCookies[host]
        if (manual.isNullOrEmpty()) return fromJar
        val merged = java.util.LinkedHashMap<String, String>()
        fromJar?.split(";")?.forEach { part ->
            val index = part.indexOf('=')
            if (index > 0) merged[part.substring(0, index).trim()] = part.substring(index + 1).trim()
        }
        manual.forEach { (name, value) -> merged[name] = value }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }.ifEmpty { null }
    }

    /** 记录某 host 的挑战 cookie（重放/后续搜索都会带上）。 */
    private fun setChallengeCookie(uri: URI, name: String, value: String) {
        val host = uri.host ?: return
        challengeCookies.getOrPut(host) { java.util.concurrent.ConcurrentHashMap() }[name] = value
    }

    /**
     * 保存 Set-Cookie（返回本次响应里的 Set-Cookie 条数，诊断用）。
     *
     * 用 CookieManager.put 解析（带 Domain/Path 的标准 cookie 能正常回传，如 bsrv）；
     * 挑战用的 c_token/c_time 由 [setChallengeCookie] 手动并入 Cookie 头。
     */
    private fun storeResponseCookies(uri: URI, conn: HttpURLConnection): Int {
        var count = 0
        try {
            val fields = conn.headerFields ?: return 0
            cookieJar.put(uri, fields)
            count = fields["Set-Cookie"]?.size ?: 0
        } catch (e: Exception) {
            // 单个 Set-Cookie 解析失败不应让整次请求失败。
        }
        return count
    }

    // ---------------------------------------------------------------- proxy

    /**
     * 代理候选（按优先级）：
     * 1. 系统全局代理 http_proxy（有值时所有请求都显式走它）；
     * 2. DEBUG 包且本地兜底代理端口可连时，走 `127.0.0.1:7897`，失败再直连；
     * 3. 直连（null = 默认 ProxySelector，系统若另有 PAC 仍可能生效）。
     */
    private fun proxyCandidates(): List<Proxy?> {
        systemProxy?.let { return listOf(it) }
        if (isDebuggable) {
            if (!fallbackChecked) {
                fallbackChecked = true
                val spec = forceProxy?.takeIf { it.isNotBlank() } ?: LOCAL_PROXY
                val candidate = parseProxy(spec)
                if (candidate != null && canConnect(candidate)) {
                    fallbackProxy = candidate
                    proxySource = "fallback"
                    debugLog("proxy=${proxyLabelOrNone(candidate)} source=fallback")
                } else {
                    debugLog("proxy=none source=none fallback_unreachable spec=${clip(spec)}")
                }
            }
            fallbackProxy?.let { return listOf(it, null) }
        }
        return listOf(null)
    }

    /** 读系统全局代理：Settings.Global["http_proxy"] 优先，其次 android.net.Proxy 默认值。 */
    private fun readSystemProxy(): Proxy? {
        readGlobalProxySetting()?.let { spec -> parseProxy(spec)?.let { return it } }
        readLegacyProxySetting()?.let { spec -> parseProxy(spec)?.let { return it } }
        return null
    }

    private fun readGlobalProxySetting(): String? = try {
        val resolver = appContext?.contentResolver
        if (resolver == null) {
            null
        } else {
            val value = Settings.Global.getString(resolver, "http_proxy")?.trim()
            if (value.isNullOrEmpty() || value == "null" || value == "none" || value == ":0") {
                null
            } else {
                value
            }
        }
    } catch (e: Exception) {
        null
    }

    /** 已废弃的 android.net.Proxy（部分 ROM 只写这里）；仅作兜底。 */
    @Suppress("DEPRECATION")
    private fun readLegacyProxySetting(): String? = try {
        val host = android.net.Proxy.getDefaultHost()?.trim()
        val port = android.net.Proxy.getDefaultPort()
        if (host.isNullOrEmpty() || port !in 1..65535) null else "$host:$port"
    } catch (e: Exception) {
        null
    }

    /** 本地兜底代理端口是否真的可连（超时很短；只在 IO 线程调用）。 */
    private fun canConnect(proxy: Proxy): Boolean {
        val address = proxy.address() as? InetSocketAddress ?: return false
        return try {
            Socket().use { socket -> socket.connect(address, FALLBACK_CONNECT_TIMEOUT_MS) }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun parseProxy(spec: String): Proxy? {
        val value = spec.trim()
            .removePrefix("http://")
            .removePrefix("https://")
        if (value.isEmpty()) return null
        val index = value.lastIndexOf(':')
        if (index <= 0) return null
        val host = value.substring(0, index).trim()
        val port = value.substring(index + 1).trim().toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
    }

    // ---------------------------------------------------------------- debug helpers (debug 包才打印)

    private fun debugLog(message: String) {
        if (isDebuggable) Log.i(TAG, message)
    }

    private fun describeSystemProxy(): String = try {
        val uri = URI(MIRRORS.first() + "/s/test")
        val selected = ProxySelector.getDefault()?.select(uri) ?: return "none"
        selected.joinToString(",") { proxyLabel(it) }
    } catch (e: Exception) {
        "error:${e.javaClass.simpleName}"
    }

    /** 网络日志用：null/direct 记 "system"（交给 ProxySelector），否则 `HTTP@host:port`。 */
    private fun proxyLabel(proxy: Proxy?): String {
        if (proxy == null || proxy == Proxy.NO_PROXY || proxy.type() == Proxy.Type.DIRECT) return "system"
        val address = proxy.address() as? InetSocketAddress ?: return "system"
        return "${proxy.type()}@${address.hostString}:${address.port}"
    }

    /**
     * 初始化日志用：`host:port` 或 `none`。
     * 必须用 hostString（构造时的原始字符串）；getHostName() 会做反向 DNS，
     * 在主线程调用会直接抛 NetworkOnMainThreadException。
     */
    private fun proxyLabelOrNone(proxy: Proxy?): String {
        if (proxy == null) return "none"
        val address = proxy.address() as? InetSocketAddress ?: return "none"
        return "${address.hostString}:${address.port}"
    }

    private fun hostOf(mirrorUrl: String): String =
        runCatching { URI(mirrorUrl).host }.getOrNull() ?: mirrorUrl

    /** 当前 Cookie 头里的 cookie 名（诊断 bsrv / c_token / c_time 是否带全）。 */
    private fun cookieNames(uri: URI): String = try {
        val header = cookieHeader(uri) ?: return "none"
        header.split(";")
            .map { it.substringBefore('=').trim() }
            .filter { it.isNotEmpty() }
            .joinToString(",")
            .ifEmpty { "none" }
    } catch (e: Exception) {
        "none"
    }

    /** 最深层 cause；没有 cause 时返回自身。 */
    private fun rootCauseOf(t: Throwable): Throwable {
        var current = t
        var guard = 0
        while (current.cause != null && guard++ < 8) {
            current = current.cause!!
        }
        return current
    }

    /** 异常因果链（A<-B<-C），供日志定位真实原因。 */
    private fun chainOf(t: Throwable): String {
        val names = ArrayList<String>(4)
        var current: Throwable? = t
        var guard = 0
        while (current != null && guard++ < 8) {
            names.add(current.javaClass.simpleName)
            current = current.cause
        }
        return names.joinToString("<-")
    }

    private fun errText(t: Throwable): String = "${t.javaClass.simpleName}:${clip(t.message)}"

    /** 失败阶段：connect|tls|read|challenge|status（设备日志定位用）。 */
    private fun phaseOf(t: Throwable): String = when (t) {
        is javax.net.ssl.SSLException -> "tls"
        is java.net.SocketTimeoutException ->
            if ((t.message ?: "").contains("connect", ignoreCase = true)) "connect" else "read"
        is java.net.UnknownHostException -> "connect"
        is java.net.ConnectException -> "connect"
        is java.net.NoRouteToHostException -> "connect"
        is java.net.SocketException -> "read"
        is ZlibException -> when (t.kind) {
            ZlibException.Kind.CHALLENGE_FAILED -> "challenge"
            ZlibException.Kind.HTTP -> "status"
            else -> "connect"
        }
        else -> "other"
    }

    /** DiamWall 反爬（307 后 513 / Server: DiamWall）：需要执行 JS，原生客户端走不通。 */
    private fun isDiamWallBlock(res: HttpResult): Boolean =
        res.status == 513 || (res.server?.contains("diamwall", ignoreCase = true) == true)

    private fun statusOf(e: Exception): String =
        STATUS_RE.find(e.message ?: "")?.groupValues?.get(1) ?: "-"

    private fun clip(message: String?): String =
        (message ?: "null").replace('\n', ' ').take(120)

    private fun remainingMs(deadlineNanos: Long): Long = (deadlineNanos - System.nanoTime()) / 1_000_000L

    private fun timeoutFor(defaultMs: Int, deadlineNanos: Long?): Int {
        if (deadlineNanos == null) return defaultMs
        val remaining = remainingMs(deadlineNanos)
        if (remaining <= 0L) return MIN_REQUEST_MS.toInt()
        return minOf(defaultMs.toLong(), remaining).toInt().coerceAtLeast(MIN_REQUEST_MS.toInt())
    }

    // ---------------------------------------------------------------- challenge detection

    private fun isChallengeResponse(res: HttpResult): Boolean =
        res.status == 503 || looksLikeChallenge(res.body)

    private fun looksLikeChallenge(body: String): Boolean {
        if (body.contains("Checking your browser", ignoreCase = true)) return true
        if (body.contains("Just a moment", ignoreCase = true)) return true
        // 挑战页内联脚本一定含 c_token=；正常搜索页有结果卡片，二者互斥
        return body.contains("c_token=", ignoreCase = true) && !body.contains("<z-bookcard", ignoreCase = true)
    }

    companion object {
        private const val PREFS_NAME = "zlib"
        private const val KEY_MIRROR = "zlib_mirror"
        private const val TAG = "PBZlib"
        private const val CONNECT_TIMEOUT_MS = 7_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_REDIRECTS = 5
        private const val MAX_CHALLENGE_ROUNDS = 3

        /** 探测预算：最多 3 个镜像、单镜像 20s（首次 TLS via proxy 慢）、整轮 40s。 */
        private const val MAX_PROBE_MIRRORS = 3
        private const val PER_MIRROR_BUDGET_MS = 20_000L
        private const val PROBE_BUDGET_MS = 40_000L
        private const val MIN_REQUEST_MS = 750L

        /** SHA1 挑战站点（z-lib.by 类）：仅这些站点失败时重试，含首次最多 [MAX_MIRROR_ATTEMPTS] 次。 */
        private val SHA1_CHALLENGE_HOSTS = setOf("z-lib.by")
        private const val MAX_MIRROR_ATTEMPTS = 3

        /** 已知 DiamWall 站点（307 + __diamwall → 513 空 body，需执行 JS）：排到候选末尾。 */
        private val KNOWN_UNSUPPORTED_HOSTS = setOf("zh.z-library.sk", "zh.1lib.sk")

        /** DEBUG 兜底代理端口连通性检查的超时。 */
        private const val FALLBACK_CONNECT_TIMEOUT_MS = 300
        private val STATUS_RE = Regex("HTTP (\\d{3})")

        /** 电脑侧 `adb reverse tcp:7897 tcp:7897` 暴露的本机代理。 */
        private const val LOCAL_PROXY = "127.0.0.1:7897"

        private val CHARSET_RE = Regex("charset\\s*=\\s*[\"']?([\\w-]+)", RegexOption.IGNORE_CASE)

        /**
         * 站方挑战对 UA 做判定：只有桌面 Windows Chrome 能过（移动 UA / Dalvik 默认 UA 重放会继续 503，
         * Lead 桌面 A/B 已验证）。所有 HttpURLConnection 必须显式设置本常量。
         */
        const val DESKTOP_UA: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        const val ACCEPT_LANGUAGE: String = "zh-CN,zh;q=0.9,en;q=0.8"
        const val ACCEPT_HTML: String = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

        /** 镜像候选（顺序即优先级）。 */
        val MIRRORS: List<String> = listOf(
            "https://z-lib.by",
            "https://zh.z-library.sk",
            "https://zh.1lib.sk",
            "https://zlib.re",
            "https://zh.zlib0.ru",
            "https://zh.z-lib.gd",
            "https://zh.dfj101.ru",
            "https://zh.101fbiwarning.ru",
            "https://zh.zzz101.ru",
            "https://zh.singlelogin.rs",
        )

        @Volatile
        private var shared: ZlibClient? = null

        /** 进程内单例；传 Application Context 才会启用镜像缓存与 DEBUG 代理兜底。 */
        fun get(context: Context): ZlibClient = shared ?: synchronized(this) {
            shared ?: ZlibClient(context.applicationContext).also { shared = it }
        }
    }
}
