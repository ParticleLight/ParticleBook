package app.particlebook.android.zlib

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.provider.Settings
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
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

    /** Cookie 持久化（P1b 3.1）：内存 CookieManager + filesDir/zlib_cookies.json（Set-Cookie 后 200ms 节流落盘）。 */
    private val cookieStore = ZCookieStore(appContext) { message -> debugLog(message) }

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
     * 不用 lazy：断网/切网恢复后 [resetConnectionState] 要清掉缓存重新解析（见 [resolveSystemProxy]）。
     */
    @Volatile
    private var systemProxy: Proxy? = null

    /** 系统代理是否已解析过（一次性；resetConnectionState() 置回 false）。 */
    @Volatile
    private var systemProxyResolved: Boolean = false

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
        val proxy = resolveSystemProxy()
        proxySource = if (proxy != null) "global" else "none"
        cookieStore.ensureLoaded()
        debugLog(
            "proxy=${proxyLabelOrNone(proxy)} source=$proxySource " +
                "selector=${describeSystemProxy()} debuggable=$isDebuggable",
        )
        initialized = true
    }

    /**
     * 解析系统代理并缓存；进程内只解析一次（只读 Settings + 构造 Proxy，无 socket）。
     * [resetConnectionState] 会把 [systemProxyResolved] 置回 false，让下一次调用重新读取。
     */
    @Synchronized
    private fun resolveSystemProxy(): Proxy? {
        if (!systemProxyResolved) {
            systemProxy = readSystemProxy()
            systemProxyResolved = true
        }
        return systemProxy
    }

    /**
     * 断网/切网恢复后的进程内「重试」入口：清掉一次性解析与探测临时态，让下一次
     * search()/probeMirrors() 重新解析系统代理、重新探测镜像（无需杀进程）。
     *
     * 只清内存态，不删 SharedPreferences 里的 mirror 缓存；幂等，可在主线程调用。
     */
    @Synchronized
    fun resetConnectionState() {
        resetStateLocked()
        debugLog("reset_state reason=manual ok")
    }

    /** 清一次性状态（不碰 prefs 里的镜像缓存）。调用方需保证串行；本方法自身带锁。 */
    @Synchronized
    private fun resetStateLocked() {
        initialized = false
        systemProxy = null
        systemProxyResolved = false
        proxySource = "none"
        fallbackProxy = null
        fallbackChecked = false
        challengeCookies.clear()
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
        try {
            val base = mirror ?: probeMirrors()
            val url = searchUrl(base, q, page)
            val res = getWithChallenge(URI(url))
            if (res.status != 200) {
                throw ZlibException("HTTP ${res.status} for $url", ZlibException.Kind.HTTP)
            }
            return ZlibSearchParser.parse(res.body, q, page)
        } catch (e: ZlibException) {
            // 失败后清一次性状态：网络恢复后（进程不重启）下一次 search() 自动重新解析代理并重探镜像。
            debugLog("reset_state reason=search_failure kind=${e.kind}")
            resetStateLocked()
            throw e
        }
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
        // 探测失败同样清一次性状态：下次调用重新解析代理（断网恢复后不杀进程也能恢复）。
        debugLog("reset_state reason=probe_failure")
        resetStateLocked()
        throw ZlibException(
            "no available mirror (tried=$tried, ${totalMs}ms, last: $lastError)",
            ZlibException.Kind.NO_MIRROR,
        )
    }

    // ---------------------------------------------------------------- P1b: 登录 / 详情 / 下载 / 线路

    /** 登录（P1b 3.2）：GET /login 拿 cookie → POST 表单 → form-error / CurrentUser / get-downloads 兜底。 */
    fun login(email: String, password: String): ZLoginResult {
        val startedAt = System.nanoTime()
        val accountEmail = email.trim()
        try {
            ensureInitialized()
            val base = mirror ?: probeMirrors()
            val loginUri = URI(base + "/login")
            val loginPage = getWithChallenge(loginUri)
            if (loginPage.status != 200) {
                return loginFailed(startedAt, ZLoginResult.Failed.Kind.HTTP, "HTTP " + loginPage.status)
            }
            val form = "email=" + URLEncoder.encode(accountEmail, "UTF-8") +
                "&password=" + URLEncoder.encode(password, "UTF-8") +
                "&site_mode=books&action=login&redirectUrl="
            val posted = getWithChallenge(loginUri, formBody = form.toByteArray(Charsets.UTF_8))
            ZlibLoginParser.formError(posted.body)?.let { message ->
                return loginFailed(startedAt, ZLoginResult.Failed.Kind.BAD_CREDENTIALS, message)
            }
            var account = accountFrom(posted.body, accountEmail)
            if (account == null && verifyLoggedInOnServer(base) == true) {
                // 3.2 第 4 条兜底：CurrentUser 判不出时以只读 JSON 接口为准。
                account = ZAccount(accountEmail, null)
            }
            if (account == null) {
                return loginFailed(startedAt, ZLoginResult.Failed.Kind.PARSE, "无法确认登录状态")
            }
            saveAccount(account)
            cookieStore.persistNow()
            perfLog(
                "PBPERF|phase=zlib_login|ms=" + elapsedMs(startedAt) +
                    "|extra=ok,cookies:" + cookieStore.cookieCount(),
            )
            return ZLoginResult.Ok(account)
        } catch (e: ZlibException) {
            val kind = when (e.kind) {
                ZlibException.Kind.CHALLENGE_FAILED -> ZLoginResult.Failed.Kind.CHALLENGE_FAILED
                ZlibException.Kind.NETWORK -> ZLoginResult.Failed.Kind.NETWORK
                ZlibException.Kind.PARSE -> ZLoginResult.Failed.Kind.PARSE
                else -> ZLoginResult.Failed.Kind.HTTP
            }
            return loginFailed(startedAt, kind, e.message)
        } catch (e: Exception) {
            return loginFailed(startedAt, ZLoginResult.Failed.Kind.NETWORK, e.message)
        }
    }

    /**
     * 服务端校验登录态（阻塞，IO 线程）：GET /papi/user/get-downloads。
     * 已登录返回 {"downloads":[...]}，未登录返回 {"success":0,...}；网络失败返回 false 且保留本地缓存。
     * UI 展示用同步的 [isLoggedIn]（只读 prefs，无网络）。
     */
    fun verifyLoggedIn(): Boolean = try {
        ensureInitialized()
        val base = mirror ?: probeMirrors()
        when (verifyLoggedInOnServer(base)) {
            true -> {
                debugLog("verify_login result=logged_in")
                true
            }
            false -> {
                clearAccount()
                debugLog("verify_login result=anonymous")
                false
            }
            null -> {
                debugLog("verify_login result=unknown")
                readAccount(prefs) != null
            }
        }
    } catch (e: Exception) {
        debugLog("verify_login err=" + e.javaClass.simpleName)
        readAccount(prefs) != null
    }

    /** 登出（P1b 3.2）：清内存 cookie + filesDir/zlib_cookies.json + prefs 账号缓存。 */
    fun logout() {
        cookieStore.clear()
        challengeCookies.clear()
        clearAccount()
        debugLog("logout ok")
    }

    /** 本地登录态（同步、无网络、主线程安全）：只看 prefs 账号缓存；服务端校验用 [verifyLoggedIn]。 */
    fun isLoggedIn(context: Context): Boolean = cachedAccount(context) != null

    /** 本地账号缓存（同步、无网络）；未登录返回 null。 */
    fun cachedAccount(context: Context): ZAccount? = readAccount(prefsOf(context))

    /** 书籍详情（P1b 1.3）：先 /book/hash，再带 slug；仍失败时跟 canonical 链接补一次。 */
    fun fetchDetail(hash: String, slug: String? = null): ZBookDetail {
        val cleanHash = hash.trim().trim('/')
        require(cleanHash.isNotEmpty()) { "hash must not be blank" }
        ensureInitialized()
        val base = mirror ?: probeMirrors()
        val candidates = ArrayList<String>()
        candidates.add(base + "/book/" + cleanHash)
        if (!slug.isNullOrBlank()) {
            candidates.add(base + "/book/" + cleanHash + "/" + normalizeSlug(slug))
        }
        var lastStatus = 0
        var lastBody: String? = null
        val tried = ArrayList<String>()
        for (url in candidates) {
            tried.add(url)
            val res = getWithChallenge(URI(url))
            lastStatus = res.status
            lastBody = res.body
            if (res.status == 200 && looksLikeDetailPage(res.body)) {
                return ZlibDetailParser.parse(res.body, res.uri.toString(), cleanHash)
            }
            val canonical = canonicalBookUrl(res.body)
            if (canonical != null) {
                val canonicalUrl = absoluteSiteUrl(base, canonical)
                if (canonicalUrl !in tried) {
                    tried.add(canonicalUrl)
                    val follow = getWithChallenge(URI(canonicalUrl))
                    if (follow.status == 200 && looksLikeDetailPage(follow.body)) {
                        return ZlibDetailParser.parse(follow.body, follow.uri.toString(), cleanHash)
                    }
                }
            }
        }
        debugLog("detail_fail hash=" + cleanHash + " status=" + lastStatus + " body_len=" + (lastBody?.length ?: 0))
        throw ZlibException(
            "detail page unavailable (hash=" + cleanHash + ", status=" + lastStatus + ", tried=" + tried.size + ")",
            if (lastStatus in 400..499) ZlibException.Kind.HTTP else ZlibException.Kind.PARSE,
        )
    }

    /**
     * 解析 /dl 页面为直链（P1b 3.3 状态机）：
     * 限额 → DAILY_LIMIT；登录墙 → LOGIN_REQUIRED；倒计时 → 等待后重取（总等待 <= 20s）；
     * 候选直链 → GET 前 1KB 判定 content-type；最多 4 跳，否则 PARSE（debug 打页面片段）。
     */
    fun resolveDownload(downloadPath: String): ZDownloadHandle {
        ensureInitialized()
        val base = mirror ?: probeMirrors()
        val startUrl = absoluteSiteUrl(base, downloadPath)
        var current = startUrl
        var lastBody: String? = null
        var pageHops = 0
        var countdownRounds = 0
        var waitedSeconds = 0
        while (true) {
            if (pageHops >= MAX_DL_HOPS) break
            val res = getWithChallenge(URI(current))
            lastBody = res.body
            val state = ZlibDownloadParser.classify(res.body, current)
            if (state is ZlibDownloadParser.PageState.Countdown) {
                countdownRounds++
                if (countdownRounds > MAX_COUNTDOWN_ROUNDS) break
                val budget = MAX_COUNTDOWN_WAIT_S - waitedSeconds
                val wait = minOf(state.seconds, budget, 15)
                if (wait <= 0) break
                debugLog("dl_countdown wait=" + wait + " total=" + waitedSeconds + " url=" + hostOf(current))
                Thread.sleep(wait * 1_000L)
                waitedSeconds += wait
                continue
            }
            pageHops++
            when (state) {
                is ZlibDownloadParser.PageState.DailyLimit -> {
                    logDlPage(current, "daily_limit", lastBody)
                    throw ZlibException(state.message, ZlibException.Kind.DAILY_LIMIT)
                }
                is ZlibDownloadParser.PageState.LoginRequired -> {
                    logDlPage(current, "login_required", lastBody)
                    throw ZlibException(state.message ?: "login required", ZlibException.Kind.LOGIN_REQUIRED)
                }
                is ZlibDownloadParser.PageState.Candidates -> {
                    for (link in state.links) {
                        val direct = absoluteSiteUrl(current, link)
                        val handle = probeDirectLink(direct)
                        if (handle != null) {
                            debugLog("dl_resolved host=" + hostOf(handle.directUrl) + " type=" + (handle.contentType ?: "none"))
                            return handle
                        }
                    }
                    logDlPage(current, "candidates_unusable:" + state.links.size, lastBody)
                    throw ZlibException(
                        "download candidates unusable (" + state.links.size + ")",
                        ZlibException.Kind.PARSE,
                    )
                }
                else -> break
            }
        }
        logDlPage(current, "unknown", lastBody)
        throw ZlibException("cannot resolve download link", ZlibException.Kind.PARSE)
    }

    /** 下载到 destFile（P1b 3.4）：.part 流式 + 校验 + rename；失败删半成品并抛 DOWNLOAD/NETWORK/HTTP。 */
    fun download(
        downloadPath: String,
        destFile: File,
        onProgress: (received: Long, total: Long) -> Unit,
    ): File {
        val startedAt = System.nanoTime()
        try {
            val handle = resolveDownload(downloadPath)
            val file = streamToFile(handle, destFile, onProgress)
            perfLog(
                "PBPERF|phase=zlib_download|ms=" + elapsedMs(startedAt) +
                    "|extra=ok,bytes:" + file.length(),
            )
            return file
        } catch (e: ZlibException) {
            perfLog(
                "PBPERF|phase=zlib_download|ms=" + elapsedMs(startedAt) +
                    "|extra=fail,kind:" + e.kind,
            )
            throw e
        } catch (e: Exception) {
            perfLog(
                "PBPERF|phase=zlib_download|ms=" + elapsedMs(startedAt) +
                    "|extra=fail,kind:DOWNLOAD",
            )
            throw ZlibException("download failed: " + e.message, ZlibException.Kind.DOWNLOAD, e)
        }
    }

    /** 抓小文件（封面，P1b 1.3）：带 UA/Cookie、跟随跳转、上限 8MB。 */
    fun fetchBytes(url: String): ByteArray {
        ensureInitialized()
        val base = mirror ?: MIRRORS.first()
        var last: Exception? = null
        for (proxy in proxyCandidates()) {
            var current = absoluteSiteUrl(base, url)
            var hops = 0
            try {
                while (hops < MAX_REDIRECTS) {
                    hops++
                    val conn = openConnection(URI(current), proxy)
                    try {
                        val status = conn.responseCode
                        val location = conn.getHeaderField("Location")
                        if (status in 300..399 && !location.isNullOrBlank()) {
                            current = absoluteSiteUrl(current, location)
                            continue
                        }
                        if (status !in 200..299) {
                            throw ZlibException("HTTP " + status + " for " + current, ZlibException.Kind.HTTP)
                        }
                        val declared = conn.contentLengthLong
                        if (declared > MAX_IMAGE_BYTES) {
                            throw ZlibException("file too large: " + declared, ZlibException.Kind.HTTP)
                        }
                        val stream = try {
                            conn.inputStream
                        } catch (e: IOException) {
                            conn.errorStream
                        } ?: throw ZlibException("empty response", ZlibException.Kind.HTTP)
                        return stream.use { readCappedWithLimit(it, MAX_IMAGE_BYTES) }
                    } finally {
                        conn.disconnect()
                    }
                }
                throw ZlibException("too many redirects", ZlibException.Kind.HTTP)
            } catch (e: Exception) {
                last = e
            }
        }
        throw ZlibException("fetch bytes failed: " + (last?.message ?: "none"), ZlibException.Kind.NETWORK, last)
    }

    /** 线路信息（P1b 线路弹层）：index 从 1 开始，未知为 0。 */
    fun mirrorInfo(): ZMirrorInfo {
        val current = normalizeMirror(mirror ?: prefs?.getString(KEY_MIRROR, null))
        val index = MIRRORS.indexOfFirst { normalizeMirror(it) == current } + 1
        return ZMirrorInfo(
            index = if (current.isNotEmpty() && index > 0) index else 0,
            total = MIRRORS.size,
            mirrors = MIRRORS.toList(),
        )
    }

    /** 切换线路（P1b 4.7）：越界 false；写 prefs mirror 并清探测/代理临时态。 */
    fun switchMirror(index: Int): Boolean {
        if (index !in MIRRORS.indices) return false
        val target = MIRRORS[index]
        mirror = target
        prefs?.edit()?.putString(KEY_MIRROR, target)?.apply()
        resetStateLocked()
        debugLog("switch_mirror index=" + (index + 1) + " mirror=" + hostOf(target))
        return true
    }

    // ---------------------------------------------------------------- HTTP

    private fun searchUrl(mirrorBase: String, query: String, page: Int): String {
        val base = mirrorBase.trimEnd('/')
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        return "$base/s/$encoded?page=$page"
    }

    /** 发请求；遇到挑战页则求解并重放（最多 [MAX_CHALLENGE_ROUNDS] 轮）。 */
    private fun getWithChallenge(uri: URI, deadlineNanos: Long? = null, formBody: ByteArray? = null): HttpResult {
        var res = request(uri, deadlineNanos, formBody)
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
            res = request(uri, deadlineNanos, formBody)
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
    private fun request(uri: URI, deadlineNanos: Long? = null, formBody: ByteArray? = null): HttpResult {
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
                return requestWithProxy(uri, proxy, deadlineNanos, formBody)
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

    private fun requestWithProxy(
        uri: URI,
        proxy: Proxy?,
        deadlineNanos: Long? = null,
        formBody: ByteArray? = null,
    ): HttpResult {
        var current = uri
        var pendingForm = formBody
        var hops = 0
        while (true) {
            if (deadlineNanos != null && remainingMs(deadlineNanos) <= 0L) {
                throw ZlibException("request budget exhausted", ZlibException.Kind.NETWORK)
            }
            val method = if (pendingForm != null) "POST" else "GET"
            val conn = openConnection(current, proxy, deadlineNanos, method, pendingForm)
            try {
                val requestBody = pendingForm
                if (requestBody != null) {
                    conn.outputStream.use { out -> out.write(requestBody) }
                }
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
                    pendingForm = null
                    continue
                }
                return HttpResult(current, status, body, server, setCookieCount)
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun openConnection(
        uri: URI,
        proxy: Proxy?,
        deadlineNanos: Long? = null,
        method: String = "GET",
        formBody: ByteArray? = null,
    ): HttpURLConnection {
        val url = uri.toURL()
        val conn = if (proxy == null) {
            url.openConnection() as HttpURLConnection // 默认走 ProxySelector（系统代理）
        } else {
            url.openConnection(proxy) as HttpURLConnection
        }
        conn.instanceFollowRedirects = false
        conn.connectTimeout = timeoutFor(CONNECT_TIMEOUT_MS, deadlineNanos)
        conn.readTimeout = timeoutFor(READ_TIMEOUT_MS, deadlineNanos)
        conn.requestMethod = method
        conn.useCaches = false
        if (formBody != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setFixedLengthStreamingMode(formBody.size)
        }
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
        val fromJar = cookieStore.cookieHeader(uri)
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
     * 交给 [ZCookieStore]：CookieManager.put 解析 + 200ms 节流落盘；
     * 挑战用的 c_token/c_time 由 [setChallengeCookie] 手动并入 Cookie 头（不持久化）。
     */
    private fun storeResponseCookies(uri: URI, conn: HttpURLConnection): Int = try {
        cookieStore.store(uri, conn.headerFields ?: emptyMap())
    } catch (e: Exception) {
        0
    }

    // ---------------------------------------------------------------- proxy

    /**
     * 代理候选（按优先级）：
     * 1. 系统全局代理 http_proxy（有值时所有请求都显式走它）；
     * 2. DEBUG 包且本地兜底代理端口可连时，走 `127.0.0.1:7897`，失败再直连；
     * 3. 直连（null = 默认 ProxySelector，系统若另有 PAC 仍可能生效）。
     */
    private fun proxyCandidates(): List<Proxy?> {
        resolveSystemProxy()?.let { return listOf(it) }
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

    // ---------------------------------------------------------------- P1b private helpers

    private fun accountFrom(html: String, submittedEmail: String): ZAccount? =
        when (val state = ZlibLoginParser.accountState(html)) {
            is ZlibLoginParser.AccountState.LoggedIn ->
                ZAccount(state.account.email ?: submittedEmail, state.account.name)
            else -> null
        }

    /** 登录态只读校验：/papi/user/get-downloads（200 + downloads 字段 = 已登录）。 */
    private fun verifyLoggedInOnServer(base: String): Boolean? = try {
        val res = getWithChallenge(URI(base + "/papi/user/get-downloads"))
        if (res.status != 200) null else ZlibLoginParser.looksLoggedInByDownloads(res.body)
    } catch (e: Exception) {
        null
    }

    private fun prefsOf(context: Context): SharedPreferences? =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readAccount(sharedPreferences: SharedPreferences?): ZAccount? {
        val email = sharedPreferences?.getString(KEY_ACCOUNT_EMAIL, null)?.takeIf { it.isNotBlank() } ?: return null
        val name = sharedPreferences.getString(KEY_ACCOUNT_NAME, null)?.takeIf { it.isNotBlank() }
        return ZAccount(email, name)
    }

    private fun saveAccount(account: ZAccount) {
        val editor = prefs?.edit() ?: return
        if (!account.email.isNullOrBlank()) editor.putString(KEY_ACCOUNT_EMAIL, account.email)
        if (!account.name.isNullOrBlank()) editor.putString(KEY_ACCOUNT_NAME, account.name)
        editor.apply()
    }

    private fun clearAccount() {
        prefs?.edit()?.remove(KEY_ACCOUNT_EMAIL)?.remove(KEY_ACCOUNT_NAME)?.apply()
    }

    private fun loginFailed(startedAt: Long, kind: ZLoginResult.Failed.Kind, message: String?): ZLoginResult.Failed {
        perfLog("PBPERF|phase=zlib_login|ms=" + elapsedMs(startedAt) + "|extra=fail,kind:" + kind)
        return ZLoginResult.Failed(kind, message)
    }

    private fun perfLog(line: String) {
        Log.i(PERF_TAG, line)
    }

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L

    private fun normalizeSlug(slug: String): String {
        val cleaned = slug.trim().trim('/')
        return if (cleaned.endsWith(".html")) cleaned else cleaned + ".html"
    }

    private fun looksLikeDetailPage(html: String): Boolean =
        html.contains("details-book") && html.contains("book-title")

    private fun canonicalBookUrl(html: String): String? {
        val tag = Regex("""<link[^>]*rel\s*=\s*["']canonical["'][^>]*>""", RegexOption.IGNORE_CASE)
            .find(html)?.value ?: return null
        val href = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1) ?: return null
        return href.takeIf { it.contains("/book/") }
    }

    private fun absoluteSiteUrl(base: String, raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("//") -> "https:" + trimmed
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("/") -> hostBaseOf(base) + trimmed
            else -> base.trimEnd('/') + "/" + trimmed
        }
    }

    private fun hostBaseOf(url: String): String = runCatching {
        val uri = URI(url)
        uri.scheme + "://" + uri.authority
    }.getOrDefault(url.trimEnd('/'))

    private fun normalizeMirror(url: String?): String = url?.trim()?.trimEnd('/')?.lowercase().orEmpty()

    private data class DirectProbe(
        val url: String,
        val status: Int,
        val location: String?,
        val contentType: String?,
        val disposition: String?,
        val prefix: ByteArray,
    )

    /** GET 前 1KB 判定候选直链；最多跟 2 次跳转。 */
    private fun probeDirectLink(url: String): ZDownloadHandle? {
        var current = url
        var hops = 0
        while (hops < 3) {
            hops++
            val probe = probeOnce(current)
            when {
                probe.status in 300..399 && !probe.location.isNullOrBlank() ->
                    current = absoluteSiteUrl(current, probe.location)
                probe.status !in 200..299 -> return null
                looksLikeBookFile(probe.contentType, probe.disposition, probe.prefix) ->
                    return ZDownloadHandle(current, probe.contentType)
                else -> return null
            }
        }
        return null
    }

    private fun probeOnce(url: String): DirectProbe {
        var last: Exception? = null
        for (proxy in proxyCandidates()) {
            var conn: HttpURLConnection? = null
            try {
                conn = openConnection(URI(url), proxy)
                conn.setRequestProperty("Range", "bytes=0-1023")
                val status = conn.responseCode
                val contentType = conn.contentType
                val disposition = conn.getHeaderField("Content-Disposition")
                val location = conn.getHeaderField("Location")
                val prefix = if (status in 200..299) readPrefix(conn, 1_024) else ByteArray(0)
                return DirectProbe(url, status, location, contentType, disposition, prefix)
            } catch (e: Exception) {
                last = e
            } finally {
                conn?.disconnect()
            }
        }
        throw ZlibException("direct probe failed: " + (last?.message ?: "none"), ZlibException.Kind.NETWORK, last)
    }

    private fun readPrefix(conn: HttpURLConnection, maxBytes: Int): ByteArray {
        val stream = try {
            conn.inputStream
        } catch (e: IOException) {
            conn.errorStream
        } ?: return ByteArray(0)
        return stream.use { readCapped(it, maxBytes) }
    }

    /** 候选直链是否像书籍文件：Content-Disposition 优先；HTML 前缀/类型判定不是书。 */
    private fun looksLikeBookFile(contentType: String?, disposition: String?, prefix: ByteArray): Boolean {
        if (!disposition.isNullOrBlank()) return true
        val lower = contentType?.lowercase().orEmpty()
        if (lower.contains("text/html")) return false
        if (looksLikeHtmlStart(prefix, prefix.size)) return false
        return lower.contains("pdf") || lower.contains("octet-stream") || lower.contains("epub") ||
            lower.contains("zip") || lower.contains("mobi") || lower.startsWith("application/") ||
            lower.startsWith("image/") || lower.isEmpty()
    }

    private fun looksLikeHtmlStart(buffer: ByteArray, read: Int): Boolean {
        for (index in 0 until read) {
            val ch = buffer[index].toInt().toChar()
            if (ch.isWhitespace()) continue
            return ch == '<'
        }
        return false
    }

    private data class StreamStats(
        val finalUrl: String,
        val received: Long,
        val total: Long,
        val contentType: String?,
        val disposition: String?,
    )

    /** 逐个代理尝试流式下载；首个成功即 rename 返回。 */
    private fun streamToFile(
        handle: ZDownloadHandle,
        destFile: File,
        onProgress: (received: Long, total: Long) -> Unit,
    ): File {
        val target = destFile.absoluteFile
        val parent = target.parentFile ?: File(".")
        parent.mkdirs()
        var lastError: Exception? = null
        for (proxy in proxyCandidates()) {
            val part = File(parent, target.name + PART_SUFFIX)
            try {
                val stats = streamOnce(handle, part, proxy, onProgress)
                if (stats.received < MIN_DOWNLOAD_BYTES) {
                    throw ZlibException("download too small: " + stats.received, ZlibException.Kind.DOWNLOAD)
                }
                if (stats.total > 0 && stats.received < stats.total) {
                    throw ZlibException(
                        "incomplete download: " + stats.received + "/" + stats.total,
                        ZlibException.Kind.DOWNLOAD,
                    )
                }
                val finalFile = withExtension(target, stats.disposition, stats.finalUrl)
                if (!part.renameTo(finalFile)) {
                    part.copyTo(finalFile, overwrite = true)
                    part.delete()
                }
                return finalFile
            } catch (e: ZlibException) {
                part.delete()
                throw e
            } catch (e: Exception) {
                part.delete()
                lastError = e
            }
        }
        throw ZlibException(
            "download failed: " + (lastError?.message ?: "no proxy"),
            ZlibException.Kind.NETWORK,
            lastError,
        )
    }

    private fun streamOnce(
        handle: ZDownloadHandle,
        partFile: File,
        proxy: Proxy?,
        onProgress: (received: Long, total: Long) -> Unit,
    ): StreamStats {
        var current = handle.directUrl
        var conn: HttpURLConnection? = null
        var hops = 0
        try {
            while (true) {
                conn = openConnection(URI(current), proxy)
                val status = conn.responseCode
                val location = conn.getHeaderField("Location")
                if (status in 300..399 && !location.isNullOrBlank() && hops < MAX_DOWNLOAD_REDIRECTS) {
                    hops++
                    current = absoluteSiteUrl(current, location)
                    conn.disconnect()
                    conn = null
                    continue
                }
                if (status !in 200..299) {
                    throw ZlibException("HTTP " + status + " for " + current, ZlibException.Kind.HTTP)
                }
                break
            }
            val contentType = conn.contentType
            val disposition = conn.getHeaderField("Content-Disposition")
            val total = conn.contentLengthLong
            val stream = try {
                conn.inputStream
            } catch (e: IOException) {
                conn.errorStream
            } ?: throw ZlibException("empty response", ZlibException.Kind.DOWNLOAD)

            var received = 0L
            var lastProgressAt = 0L
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            FileOutputStream(partFile).use { output ->
                stream.use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (received == 0L &&
                            contentType?.contains("text/html", ignoreCase = true) == true &&
                            looksLikeHtmlStart(buffer, read)
                        ) {
                            throw ZlibException("not a book (html response)", ZlibException.Kind.DOWNLOAD)
                        }
                        try {
                            output.write(buffer, 0, read)
                        } catch (e: IOException) {
                            throw ZlibException(
                                "file write failed: " + e.message,
                                ZlibException.Kind.DOWNLOAD,
                                e,
                            )
                        }
                        received += read
                        val now = System.nanoTime()
                        if (now - lastProgressAt > PROGRESS_THROTTLE_MS * 1_000_000L) {
                            lastProgressAt = now
                            onProgress(received, total)
                        }
                    }
                }
            }
            onProgress(received, total)
            return StreamStats(current, received, total, contentType, disposition)
        } finally {
            conn?.disconnect()
        }
    }

    /** 扩展名缺失时按 Content-Disposition（优先）→ URL 末段 → 默认 pdf 补齐。 */
    private fun withExtension(destFile: File, disposition: String?, finalUrl: String): File {
        if (destFile.extension.isNotBlank()) return destFile
        val fromName = ZlibDownloadParser.filenameFromDisposition(disposition)
            ?.substringAfterLast('.', "")
            ?.takeIf { it.isNotBlank() && it.length <= 5 }
        val fromUrl = runCatching { URI(finalUrl).path.substringAfterLast('.', "") }
            .getOrDefault("")
            .takeIf { it.isNotBlank() && it.length <= 5 }
        val extension = (fromName ?: fromUrl ?: "pdf").lowercase().filter { it.isLetterOrDigit() }
        return File(destFile.parentFile, destFile.name + "." + extension)
    }

    private fun readCapped(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(maxBytes, 16 * 1_024))
        val buffer = ByteArray(8 * 1_024)
        var total = 0
        while (total < maxBytes) {
            val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - total))
            if (read < 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    private fun readCappedWithLimit(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1_024))
        val buffer = ByteArray(8 * 1_024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw ZlibException("file exceeds " + maxBytes + " bytes", ZlibException.Kind.HTTP)
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- debug helpers (debug 包才打印)

    private fun debugLog(message: String) {
        if (isDebuggable) Log.i(TAG, message)
    }

    /** /dl 页面结构在登录后尚未实测：debug 包打印状态 + 页面片段（release 不打），供设备端迭代。 */
    private fun logDlPage(url: String, state: String, body: String?) {
        if (!isDebuggable) return
        val snippet = (body ?: "").take(1500).replace("\n", " ").replace("\r", " ")
        Log.i(TAG, "dl_page state=" + state + " url=" + hostOf(url) + " len=" + (body?.length ?: 0) + " snippet=" + snippet)
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

        /** P1b：账号缓存键（本地展示缓存；真实登录态以 cookie + verifyLoggedIn 为准）。 */
        private const val KEY_ACCOUNT_EMAIL = "zlib_account_email"
        private const val KEY_ACCOUNT_NAME = "zlib_account_name"

        /** P1b：下载/图片上限与节流。 */
        private const val PART_SUFFIX = ".part"
        private const val MAX_IMAGE_BYTES = 8 * 1_024 * 1_024
        private const val MIN_DOWNLOAD_BYTES = 1_024L
        private const val DOWNLOAD_BUFFER_BYTES = 8 * 1_024
        private const val PROGRESS_THROTTLE_MS = 150L
        private const val MAX_DOWNLOAD_REDIRECTS = 5

        /** P1b：/dl 状态机跳数与倒计时等待上限。 */
        private const val MAX_DL_HOPS = 4
        private const val MAX_COUNTDOWN_ROUNDS = 8
        private const val MAX_COUNTDOWN_WAIT_S = 20

        /** 性能日志 tag（与 app 其他 phase 一致）。 */
        private const val PERF_TAG = "PBPERF"
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
