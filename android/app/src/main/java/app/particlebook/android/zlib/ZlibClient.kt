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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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

    /** P1c ③：动态镜像名单后台刷新单飞标记（源站 zz.ggonav.com 已 NXDOMAIN，仅 best-effort，不阻塞探测）。 */
    private val navRefreshRunning = AtomicBoolean(false)

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
        /** 最终响应 Content-Type（/dl 判定 HTML vs 二进制用；不跟随重定向时是 302 自身的）。 */
        val contentType: String? = null,
        /** 3xx 的 Location（/dl 探测拿 CDN 直链用）。 */
        val location: String? = null,
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
     * 顺序：缓存（上次成功）→ 静态 [MIRRORS] + 已缓存的动态名单（zz.ggonav.com 源站已 NXDOMAIN，
     * 仅 best-effort：命中镜像后再后台刷新，1h 缓存，失败静默回退静态，绝不拖慢首次搜索）；
     * 已知 DiamWall 站点（307 + __diamwall → 513 空 body，需要执行 JS）排到候选末尾，只在候选不足时才占名额。最多 [MAX_PROBE_MIRRORS] 个、
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
        mergedMirrors().forEach { candidate ->
            val normalized = candidate.trimEnd('/')
            if (normalized !in candidates) candidates.add(normalized)
        }

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
                        // P1c ③：命中镜像后再后台刷新名单（不占用户等待时间；源站已 NXDOMAIN 时静默失败）。
                        maybeRefreshMirrorListInBackground()
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
     * 分类 / 榜单 / 作者页（P1c ④）：拉一页站内 HTML，解析成与搜索同构的 [ZBook] 列表。
     *
     * @param path 站内路径（如 `/popular`、`/s/thinking%20fast?page=2`）；传绝对 URL 时只取 path+query，
     *   仍拼到当前镜像上执行（防止外部 URL 把请求带去任意主机）。
     * @throws ZlibException HTTP/PARSE
     */
    @Throws(ZlibException::class)
    fun fetchCategory(path: String): List<ZBook> {
        val clean = path.trim()
        require(clean.isNotEmpty()) { "path must not be blank" }
        ensureInitialized()
        val base = mirror ?: probeMirrors()
        val sitePath = sitePathOf(clean)
        val target = absoluteSiteUrl(base, sitePath)
        val res = getWithChallenge(URI(target))
        if (res.status !in 200..299) {
            debugLog("category_fail path=" + sitePath + " status=" + res.status + " body_len=" + res.body.length)
            throw ZlibException(
                "category page unavailable (path=" + sitePath + ", status=" + res.status + ")",
                if (res.status in 400..499) ZlibException.Kind.HTTP else ZlibException.Kind.PARSE,
            )
        }
        val books = ZlibCategoryParser.parse(res.body, sitePath)
        debugLog("category path=" + hostOf(target) + sitePath + " status=" + res.status + " cards=" + books.size)
        return books
    }

    /** 绝对 URL / 协议相对 URL 只保留 path+query，避免 [fetchCategory] 被传入外部主机（D10）。 */
    private fun sitePathOf(raw: String): String = ZlibUrlPolicy.sitePathOf(raw)

    /**
     * 解析 /dl 页面为直链（P1b 3.3 状态机）：
     * 限额 → DAILY_LIMIT；登录墙 → LOGIN_REQUIRED；倒计时 → 等待后重取（总等待 <= 20s）；
     * 候选直链 → GET 前 1KB 判定 content-type；最多 4 跳，否则 PARSE（debug 打页面片段）。
     */
    fun resolveDownload(downloadPath: String): ZDownloadHandle = resolveDownloadInternal(downloadPath, null)

    /** [resolveDownload] 内部版：`cancelFlag` 在每次请求 / 倒计时等待 / 候选探测前检查。 */
    private fun resolveDownloadInternal(downloadPath: String, cancelFlag: AtomicBoolean?): ZDownloadHandle {
        checkCancelled(cancelFlag)
        ensureInitialized()
        val base = mirror ?: probeMirrors()
        val startUrl = absoluteSiteUrl(base, downloadPath)
        // D5：downloadPath 可能是详情页里的任意绝对 URL（远程 HTML 可注入），入口先做白名单校验。
        if (!isAllowedDownloadTarget(startUrl)) {
            debugLog("dl_blocked_url host=" + hostOf(startUrl) + " stage=entry")
            throw ZlibException("blocked download url: " + hostOf(startUrl), ZlibException.Kind.DOWNLOAD)
        }
        var current = startUrl
        var lastBody: String? = null
        var pageHops = 0
        var countdownRounds = 0
        var waitedSeconds = 0
        while (true) {
            checkCancelled(cancelFlag)
            if (pageHops >= MAX_DL_HOPS) break
            // R7-F 诊断：每次 /dl 状态机请求前打印 URL + 实际会带上的 cookie 名。
            val dlTarget = URI(current)
            debugLog(
                "dl_req url=${hostOf(current)} path=${dlTarget.path ?: "-"} " +
                    "cookies=${cookieNames(dlTarget)}",
            )
            // R7-G：/dl 探测阶段不跟随重定向 —— 302 的 Location 就是 CDN 直链；
            // 跟随会把整本书（PDF/EPUB，最大 18MB）当 HTML 读进 String 再 classify=unknown（下载卡死真因）。
            val res = getWithChallenge(dlTarget, followRedirects = false, htmlOnlyBody = true)
            val location = res.location
            if (res.status in 300..399 && !location.isNullOrBlank()) {
                pageHops++
                val direct = resolveDownloadLocation(current, location)
                debugLog("dl_redirect host=" + hostOf(current) + " status=" + res.status + " to=" + hostOf(direct))
                // R7-G②：CDN 签名直链（跨 host / 带 filename|expires|md5 参数）只能取一次。
                // 任何额外探测（Range/HEAD/GET）都会消耗它，正式流式时被 CDN 断连（实测 connection closed）。
                // 因此这里不再探测，直接把 Location 当 directUrl 交给流式一次读完。
                if (!sameHost(current, direct) || isSignedDownloadUrl(direct) || isSignedDownloadUrl(location)) {
                    debugLog("dl_resolved host=" + hostOf(direct) + " via=redirect_signed")
                    return ZDownloadHandle(direct, null)
                }
                val handle = probeDirectLink(direct)
                if (handle != null) {
                    debugLog(
                        "dl_resolved host=" + hostOf(handle.directUrl) +
                            " type=" + (handle.contentType ?: "none") + " via=redirect",
                    )
                    return handle
                }
                if (pageHops >= MAX_DL_HOPS) break
                // 直链不像文件（可能仍是 HTML 页面）：当普通页面继续解析，但不再跟随重定向。
                debugLog("dl_redirect_not_file host=" + hostOf(direct))
                current = direct
                continue
            }
            if (res.status in 200..299 && !isHtmlContentType(res.contentType)) {
                // 少数镜像把 /dl 做成二进制直出：当前 URL 即直链，body 未读（htmlOnlyBody）。
                debugLog(
                    "dl_resolved host=" + hostOf(current) +
                        " type=" + (res.contentType ?: "none") + " via=direct",
                )
                return ZDownloadHandle(current, res.contentType)
            }
            lastBody = res.body
            val state = ZlibDownloadParser.classify(res.body, current)
            if (state is ZlibDownloadParser.PageState.Countdown) {
                countdownRounds++
                if (countdownRounds > MAX_COUNTDOWN_ROUNDS) break
                val budget = MAX_COUNTDOWN_WAIT_S - waitedSeconds
                val wait = minOf(state.seconds, budget, 15)
                if (wait <= 0) break
                debugLog("dl_countdown wait=" + wait + " total=" + waitedSeconds + " url=" + hostOf(current))
                sleepCancellable(wait * 1_000L, cancelFlag)
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
                    var probed = 0
                    var skipped = 0
                    for (link in state.links) {
                        checkCancelled(cancelFlag)
                        // D21：候选数量上限 + D5：外部/私网地址不探测。
                        if (probed >= MAX_DL_CANDIDATES) break
                        val direct = absoluteSiteUrl(current, link)
                        if (!isAllowedDownloadTarget(direct)) {
                            skipped++
                            debugLog("dl_candidate_skip host=" + hostOf(direct))
                            continue
                        }
                        probed++
                        val handle = probeDirectLink(direct)
                        if (handle != null) {
                            debugLog("dl_resolved host=" + hostOf(handle.directUrl) + " type=" + (handle.contentType ?: "none"))
                            return handle
                        }
                    }
                    logDlPage(current, "candidates_unusable:" + state.links.size, lastBody)
                    throw ZlibException(
                        "download candidates unusable (" + state.links.size +
                            ", probed=" + probed + ", skipped=" + skipped + ")",
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
    ): File = download(downloadPath, destFile, onProgress, null)

    /**
     * 可取消下载（P1c ①）：`cancelFlag` 置 true 后最多一个 8KB buffer 内停下，
     * 抛 [ZlibException.Kind.CANCELLED]（msg=cancelled）。
     *
     * 取消**保留** `.part`（与续传语义一致，P1c 收尾①）：用户重试时从已下字节续传；
     * 只有显式「删除下载/清理记录」才删文件（由调用方删目标文件与其 .part）。
     *
     * P1c ②：若 `destFile.part` 已存在且服务端支持 `Range`（206），自动续传；
     * 服务端回 200 或 Content-Range 起点不一致时从头下（不拼坏文件）。
     *
     * @param cancelFlag 可空；null 等价于不可取消（保持 P1b 行为）。
     */
    fun download(
        downloadPath: String,
        destFile: File,
        onProgress: (received: Long, total: Long) -> Unit,
        cancelFlag: AtomicBoolean?,
    ): File {
        // 早退：已取消时不建立网络连接，也不留 .part。
        checkCancelled(cancelFlag)
        val startedAt = System.nanoTime()
        try {
            var handle = resolveDownloadInternal(downloadPath, cancelFlag)
            // R7-G④：CDN 直链可能一次有效。首包未到就断连（streamToFile 抛 NETWORK 且 .part 为空）时，
            // 重新走一次 /dl 拿新签名 URL 再试一次，而不是直接失败。
            var freshRetried = false
            var file: File? = null
            while (file == null) {
                try {
                    file = streamToFile(handle, destFile, onProgress, cancelFlag)
                } catch (e: ZlibException) {
                    if (!freshRetried && e.kind == ZlibException.Kind.NETWORK && partIsEmpty(destFile)) {
                        freshRetried = true
                        debugLog("dl_retry reason=first_packet_lost")
                        handle = resolveDownloadInternal(downloadPath, cancelFlag)
                    } else {
                        throw e
                    }
                }
            }
            val downloaded = file
            perfLog(
                "PBPERF|phase=zlib_dl_done|ms=" + elapsedMs(startedAt) +
                    "|extra=host:" + hostOf(handle.directUrl) + ",bytes:" + downloaded.length(),
            )
            perfLog(
                "PBPERF|phase=zlib_download|ms=" + elapsedMs(startedAt) +
                    "|extra=ok,bytes:" + downloaded.length(),
            )
            return downloaded
        } catch (e: ZlibException) {
            // P1c 收尾①：取消不再删 .part —— 保留可续传；.part 的清理由“删除下载”负责。
            if (e.kind == ZlibException.Kind.CANCELLED) {
                debugLog("dl_cancel keep_part=" + partLength(destFile) + " ms=" + elapsedMs(startedAt))
            }
            debugLog(
                "dl_fail kind=" + e.kind + " ms=" + elapsedMs(startedAt) +
                    " msg=" + (e.message?.take(120) ?: "none"),
            )
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
        for (proxy in proxyCandidates(URI(absoluteSiteUrl(base, url)))) {
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
        // 只读内存缓存（零网络）：UI 线程可安全调用；动态名单由 probeMirrors() 在 IO 线程刷新。
        val available = mergedMirrors()
        val current = normalizeMirror(mirror ?: prefs?.getString(KEY_MIRROR, null))
        val index = available.indexOfFirst { normalizeMirror(it) == current } + 1
        return ZMirrorInfo(
            index = if (current.isNotEmpty() && index > 0) index else 0,
            total = available.size,
            mirrors = available,
        )
    }

    /** 切换线路（P1b 4.7）：越界 false；写 prefs mirror 并清探测/代理临时态。 */
    fun switchMirror(index: Int): Boolean {
        val available = mergedMirrors()
        if (index !in available.indices) return false
        val target = available[index]
        mirror = target
        prefs?.edit()?.putString(KEY_MIRROR, target)?.apply()
        resetStateLocked()
        debugLog("switch_mirror index=" + (index + 1) + " mirror=" + hostOf(target))
        return true
    }

    /**
     * 清掉缓存线路（P1c 收尾③）：prefs `KEY_MIRROR` + 内存 [mirror]。
     *
     * debug 下换 mock 服务 / 换镜像后必须调用，否则上一次的地址会「粘」在缓存里，
     * 后续真实流程仍然打到 mock 或已失效的镜像。不动 cookie / 代理 / 动态名单缓存。
     */
    fun clearCachedMirror() {
        mirror = null
        prefs?.edit()?.remove(KEY_MIRROR)?.apply()
        debugLog("clear_mirror prefs=removed memory=null")
    }

    // ---------------------------------------------------------------- P1c: 动态镜像名单

    /** 静态名单 + 动态发现合并（只读内存缓存，零网络）。 */
    private fun mergedMirrors(): List<String> =
        ZlibMirrorList.merge(MIRRORS, ZlibMirrorList.cached()).map { it.trimEnd('/') }

    /**
     * 名单缓存过期时在后台线程抓一次导航页（源站 zz.ggonav.com 已 NXDOMAIN，仅 best-effort）：
     * 不阻塞镜像探测/首次搜索；失败静默回退静态名单并记录本次尝试（1h 内不重试）。
     */
    private fun maybeRefreshMirrorListInBackground() {
        val now = System.currentTimeMillis()
        if (!ZlibMirrorList.shouldRefresh(now)) return
        if (!navRefreshRunning.compareAndSet(false, true)) return
        Thread {
            try {
                val html = try {
                    fetchNavHtml()
                } catch (e: Exception) {
                    null
                }
                val parsed = if (html.isNullOrBlank()) emptyList() else ZlibMirrorList.parse(html)
                ZlibMirrorList.markAttempt(parsed, System.currentTimeMillis())
                debugLog(
                    "mirror_list nav_len=" + (html?.length ?: 0) + " discovered=" + parsed.size +
                        " hosts=" + parsed.joinToString(",") { ZlibMirrorList.hostOf(it) },
                )
            } catch (t: Throwable) {
                debugLog("mirror_list refresh_err=" + t.javaClass.simpleName + ":" + (t.message ?: "").take(80))
            } finally {
                navRefreshRunning.set(false)
            }
        }.apply {
            name = "zlib-mirror-refresh"
            isDaemon = true
        }.start()
    }

    /** GET 导航页（绝对 URL，非镜像相对路径）；整轮 <= [NAV_FETCH_BUDGET_MS]，失败返回 null。 */
    private fun fetchNavHtml(): String? {
        val deadline = System.nanoTime() + NAV_FETCH_BUDGET_MS * 1_000_000L
        for (proxy in proxyCandidates(URI(ZlibMirrorList.NAV_URL))) {
            var conn: HttpURLConnection? = null
            try {
                conn = openConnection(URI(ZlibMirrorList.NAV_URL), proxy, deadline)
                val status = conn.responseCode
                if (status !in 200..299) {
                    debugLog("mirror_list nav_status=$status")
                    continue
                }
                val body = readBody(conn)
                if (body.isNotEmpty()) return body
            } catch (e: Exception) {
                debugLog("mirror_list nav_err=" + e.javaClass.simpleName + ":" + (e.message ?: "").take(80))
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }

    // ---------------------------------------------------------------- HTTP

    private fun searchUrl(mirrorBase: String, query: String, page: Int): String {
        val base = mirrorBase.trimEnd('/')
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        return "$base/s/$encoded?page=$page"
    }

    /** 发请求；遇到挑战页则求解并重放（最多 [MAX_CHALLENGE_ROUNDS] 轮）。 */
    private fun getWithChallenge(
        uri: URI,
        deadlineNanos: Long? = null,
        formBody: ByteArray? = null,
        followRedirects: Boolean = true,
        htmlOnlyBody: Boolean = false,
    ): HttpResult {
        var target = uri
        var res = request(uri, deadlineNanos, formBody, followRedirects, htmlOnlyBody)
        var round = 0
        val startedAt = System.nanoTime()
        while (isChallengeResponse(res)) {
            // 挑战只对「最终 host」有效：request() 内部可能已经跟随 301/302（如 zlib.re → zlib.bz）。
            // cookie 不跨 host，若仍在发起 host 上求解，重放必然 503。这里改用响应实际地址求解并重放。
            if (res.uri.host != target.host) {
                debugLog(
                    "challenge_follow from=" + (target.host ?: "-") +
                        " to=" + (res.uri.host ?: "-") + " path=" + (res.uri.path ?: "-"),
                )
            }
            target = res.uri
            val host = target.host ?: "unknown"
            // 挑战页原文诊断（R7-A）：hash + 前 300 字符 + 原文落盘，供与桌面 9592B 页面逐字节对比。
            val bodySha1 = sha1Of(res.body)
            debugLog(
                "challenge host=$host status=${res.status} body_len=${res.body.length} round=$round " +
                    "sha1=$bodySha1 file=${saveChallengeBody(res.body)} head=${headOf(res.body, 300)}",
            )
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
            setChallengeCookie(target, "c_token", solution.token)
            setChallengeCookie(target, "c_time", String.format(Locale.US, "%.3f", elapsedSec))
            debugLog(
                "challenge_cookie host=$host cookie_names=${cookieNames(target)} " +
                    "bsrv=${cookieValue(target, "bsrv")} c_time=" +
                    String.format(Locale.US, "%.3f", elapsedSec) +
                    " header=${ZlibCookieHeader.redact(cookieHeader(target))}",
            )
            val replayStarted = System.nanoTime()
            res = request(target, deadlineNanos, formBody, followRedirects, htmlOnlyBody)
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
    private fun request(
        uri: URI,
        deadlineNanos: Long? = null,
        formBody: ByteArray? = null,
        followRedirects: Boolean = true,
        htmlOnlyBody: Boolean = false,
    ): HttpResult {
        var last: Exception? = null
        var attempt = 0
        for (proxy in proxyCandidates(uri)) {
            if (deadlineNanos != null && remainingMs(deadlineNanos) < MIN_REQUEST_MS) {
                debugLog("net_skip host=${uri.host} proxy=${proxyLabel(proxy)} remaining_ms=${remainingMs(deadlineNanos)}")
                break
            }
            attempt++
            debugLog("net_attempt host=${uri.host} proxy=${proxyLabel(proxy)} attempt=$attempt")
            try {
                return requestWithProxy(uri, proxy, deadlineNanos, formBody, followRedirects, htmlOnlyBody)
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
        followRedirects: Boolean = true,
        htmlOnlyBody: Boolean = false,
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
                val contentType = conn.contentType
                val location = conn.getHeaderField("Location")
                // R7-G：/dl 探测只关心 HTML 状态页（限额/登录/倒计时/候选）与 302 Location；
                // 二进制响应（CDN 直出的整本书）绝不读 body，否则整包进 String（2MB~18MB）拖死内存。
                val shouldReadBody = !htmlOnlyBody || isHtmlContentType(contentType) || status == 503
                val body = if (shouldReadBody) readBody(conn, MAX_HTML_BYTES) else ""
                val server = conn.getHeaderField("Server")
                val setCookieCount = storeResponseCookies(current, conn)
                // 503 挑战页也会下发新 bsrv：确认它真的落进 jar（含挑战响应）。
                if (setCookieCount > 0) {
                    debugLog(
                        "cookie_store host=${current.host ?: "-"} status=$status " +
                            "set_cookie=$setCookieCount now=${cookieNames(current)}",
                    )
                }

                if (followRedirects && status in 300..399 && !location.isNullOrBlank() && hops < MAX_REDIRECTS) {
                    hops++
                    val next = resolveLocation(current, location)
                    debugLog("net_redirect host=${current.host} status=$status to=${next.host} set_cookie=$setCookieCount")
                    current = next
                    pendingForm = null
                    continue
                }
                return HttpResult(current, status, body, server, setCookieCount, contentType, location)
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Content-Type 是否可当 HTML 状态页解析（text/html、xhtml、空值保守当作非 HTML）。 */
    private fun isHtmlContentType(contentType: String?): Boolean {
        val lower = contentType?.lowercase().orEmpty()
        return lower.contains("text/html") || lower.contains("application/xhtml")
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
        debugLog("net_route host=${uri.host ?: "-"} proxy=" +
            if (proxy == null || proxy == Proxy.NO_PROXY) "none" else proxyLabelOrNone(proxy))
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
        val outgoingCookie = cookieHeader(uri)
        outgoingCookie?.let { conn.setRequestProperty("Cookie", it) }
        // R7-F 诊断：/dl 请求必须带登录 cookie（remix_userkey）；打实际发送的头，值截 6 位。
        if (uri.path?.startsWith("/dl") == true) {
            debugLog(
                "dl_cookie_hdr host=${uri.host ?: "-"} path=${uri.path ?: "-"} " +
                    "hdr=${ZlibCookieHeader.redact(outgoingCookie)}",
            )
        }
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

    /** 有上限读响应体（D11）：HTML/JSON 走 [MAX_HTML_BYTES]，图片等小文件走 [MAX_RESPONSE_BYTES]。 */
    private fun readBody(conn: HttpURLConnection, maxBytes: Long = MAX_RESPONSE_BYTES): String {
        val stream = try {
            conn.inputStream
        } catch (e: IOException) {
            conn.errorStream
        } ?: return ""
        return stream.use { input ->
            ZlibBodyReader.read(input, maxBytes, charsetOf(conn.contentType))
        }
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

    /**
     * 发送用 Cookie 头：CookieManager 保存的 Set-Cookie（remix_* / bsrv 等）+ 手动挑战 cookie。
     *
     * 注意：CookieManager.get() 对 Version=1 的 cookie 会返回 RFC 2965 格式的**单条拼接串**
     * （Android 12 AOSP：`$Version="1"; remix_userkey="…";$Path="/";$Domain=".z-lib.by"; …`，
     * JDK 可能拆成多个 header 值）；直接拼接会被服务端识别失败（R7-F 根因：/dl 拿不到登录态）。
     * 统一由 [ZlibCookieHeader] 解析成 version-0 的 `name=value; ...` 后再发送。
     */
    private fun cookieHeader(uri: URI): String? =
        ZlibCookieHeader.merge(cookieStore.cookieHeader(uri), uri.host?.let { challengeCookies[it] })

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
    private fun proxyCandidates(uri: URI? = null): List<Proxy?> {
        // mock/localhost 场景（E2E 用 --es pb_zlib_mirror http://127.0.0.1:8899 + adb reverse）：
        // 目标本身是 loopback 或直指 debug 兜底端口时必须直连，任何代理都会把请求带离本机。
        if (isLoopbackTarget(uri)) {
            debugLog("net_route host=${uri?.host ?: "-"} proxy=none loopback=true")
            return listOf(Proxy.NO_PROXY)
        }
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

    /**
     * 目标是否 loopback / debug 兜底端口（E2E mock 与 adb reverse 场景）：
     * 127.0.0.0/8、localhost、::1，或任何端口 == LOCAL_PROXY 端口的 URI。
     */
    private fun isLoopbackTarget(uri: URI?): Boolean {
        if (uri == null) return false
        val host = uri.host?.trim()?.trim('[', ']')?.lowercase().orEmpty()
        val loopbackHost = host == "localhost" || host == "::1" || host == "0.0.0.0" ||
            host.startsWith("127.")
        if (loopbackHost) return true
        val fallbackPort = LOCAL_PROXY.substringAfter(':', "").toIntOrNull()
        return fallbackPort != null && uri.port == fallbackPort
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

    /**
     * JVM 单测可注入的日志出口（null = 走 android.util.Log）。
     * 单测没有 Android 运行时，Log.i 会抛 "not mocked"；日志绝不该影响下载/搜索本身。
     */
    internal var testLogger: ((String) -> Unit)? = null

    private fun perfLog(line: String) {
        testLogger?.let { logger ->
            logger(line)
            return
        }
        try {
            Log.i(PERF_TAG, line)
        } catch (t: Throwable) {
            // 极端情况（JVM 单测/无 Android 运行时）：日志失败不能影响业务流程。
        }
    }

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L

    /** PBPERF extra 值清洗：去空格/竖线（日志契约不许出现空格或竖线）。 */
    private fun metaValue(raw: String?): String =
        (raw ?: "none").replace(' ', '_').replace('|', '/')

    /** 平均吞吐（KB/s），用于 dl_progress 心跳区分「慢」与「卡」。 */
    private fun kbpsOf(bytes: Long, startedAtNanos: Long): Long {
        val ms = (System.nanoTime() - startedAtNanos) / 1_000_000L
        if (ms <= 0L) return 0L
        return bytes * 1_000L / ms / 1_024L
    }

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

    /** 相对引用解析（D6/D8）：统一 RFC 3986 语义，`../x`、`?q=1`、`//host/x` 都不再靠字符串拼接。 */
    private fun absoluteSiteUrl(base: String, raw: String): String =
        ZlibUrlPolicy.resolveLocation(base, raw)

    private fun hostBaseOf(url: String): String = runCatching {
        val uri = URI(url)
        uri.scheme + "://" + uri.authority
    }.getOrDefault(url.trimEnd('/'))

    private fun normalizeMirror(url: String?): String = url?.trim()?.trimEnd('/')?.lowercase().orEmpty()

    private data class DirectProbe(
        val url: String,
        val method: String,
        val status: Int,
        val location: String?,
        val contentType: String?,
        val disposition: String?,
        val prefix: ByteArray,
        val contentRange: String? = null,
        val contentLength: Long = -1L,
    )

    /**
     * 探测候选直链是否可下载（R7-G）：`Range: bytes=0-0` 只看响应头 + 最多 1KB 前缀，**不读整包**。
     * Content-Range 或 Content-Type/Content-Disposition 像文件即返回 handle；HTML/4xx 返回 null。
     */
    private fun probeDirectLink(url: String): ZDownloadHandle? {
        if (!isAllowedDownloadTarget(url)) {
            debugLog("dl_probe_blocked host=" + hostOf(url) + " stage=entry")
            return null
        }
        var current = url
        var hops = 0
        while (hops < 3) {
            hops++
            // R7-G③：优先 HEAD（不消耗 body、不写 Range），HEAD 不可用/不被支持时才退回 GET + Range。
            val head = try {
                probeOnce(current, "HEAD")
            } catch (e: ZlibException) {
                debugLog("dl_probe host=" + hostOf(current) + " method=HEAD err=" + e.kind)
                null
            }
            val probe = if (head != null && head.status in 200..399) head else try {
                probeOnce(current)
            } catch (e: ZlibException) {
                debugLog("dl_probe host=" + hostOf(current) + " method=GET err=" + e.kind)
                return null
            }
            debugLog(
                "dl_probe host=" + hostOf(current) + " method=" + probe.method +
                    " status=" + probe.status +
                    " ct=" + (probe.contentType ?: "none") +
                    " cl=" + probe.contentLength +
                    " cr=" + (probe.contentRange ?: "none"),
            )
            when {
                probe.status in 300..399 && !probe.location.isNullOrBlank() -> {
                    val next = try {
                        resolveDownloadLocation(current, probe.location)
                    } catch (e: ZlibException) {
                        return null
                    }
                    // 跨 host 或签名直链：只能取一次，探测到此为止，把 URL 原样交给流式。
                    if (!sameHost(current, next) || isSignedDownloadUrl(next)) {
                        return ZDownloadHandle(next, probe.contentType)
                    }
                    current = next
                }
                // 416 = 该 URL 存在但不支持 Range：不判「不是文件」，直接当直链流式。
                probe.status == 416 -> return ZDownloadHandle(current, probe.contentType)
                probe.status !in 200..299 -> return null
                probe.status == 206 && !probe.contentRange.isNullOrBlank() &&
                    !isHtmlContentType(probe.contentType) ->
                    return ZDownloadHandle(current, probe.contentType)
                looksLikeBookFile(probe.contentType, probe.disposition, probe.prefix) ->
                    return ZDownloadHandle(current, probe.contentType)
                else -> return null
            }
        }
        return null
    }

    private fun probeOnce(url: String, method: String = "GET"): DirectProbe {
        var last: Exception? = null
        for (proxy in proxyCandidates(URI(url))) {
            var conn: HttpURLConnection? = null
            try {
                conn = openConnection(URI(url), proxy, method = method)
                // R7-G：HEAD 只看响应头；GET 只做首字节范围探测，服务端忽略 Range 时也只读 1KB 就断开。
                if (method != "HEAD") conn.setRequestProperty("Range", "bytes=0-0")
                val status = conn.responseCode
                val contentType = conn.contentType
                val disposition = conn.getHeaderField("Content-Disposition")
                val location = conn.getHeaderField("Location")
                val contentRange = conn.getHeaderField("Content-Range")
                val contentLength = conn.contentLengthLong
                // 只在需要嗅探 HTML 时才读前缀；明确的二进制响应（pdf/epub/octet-stream）
                // 直接返回，避免服务器忽略 Range 时 close 引发隐性整包读取。
                val needPrefix = method != "HEAD" &&
                    (isHtmlContentType(contentType) || contentType.isNullOrBlank())
                val prefix = if (status in 200..299 && needPrefix) readPrefix(conn, 1_024) else ByteArray(0)
                return DirectProbe(
                    url = url,
                    method = method,
                    status = status,
                    location = location,
                    contentType = contentType,
                    disposition = disposition,
                    prefix = prefix,
                    contentRange = contentRange,
                    contentLength = contentLength,
                )
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

    /**
     * 逐个代理尝试流式下载；首个成功即 rename 返回。
     *
     * P1c ②：保留 .part 供下次续传——代理级网络失败/长度不足时不删半成品；
     * 只有取消（CANCELLED）与「不是书/太小」这类不可续传错误才删。
     */
    private fun streamToFile(
        handle: ZDownloadHandle,
        destFile: File,
        onProgress: (received: Long, total: Long) -> Unit,
        cancelFlag: AtomicBoolean?,
    ): File {
        val target = destFile.absoluteFile
        val parent = target.parentFile ?: File(".")
        parent.mkdirs()
        var lastError: Exception? = null
        for (proxy in proxyCandidates(URI(handle.directUrl))) {
            checkCancelled(cancelFlag)
            val part = File(parent, target.name + PART_SUFFIX)
            try {
                val stats = streamOnce(handle, part, proxy, onProgress, cancelFlag)
                if (stats.received < MIN_DOWNLOAD_BYTES) {
                    part.delete()
                    ZlibPartMetaStore.delete(part)
                    throw ZlibException("download too small: " + stats.received, ZlibException.Kind.DOWNLOAD)
                }
                if (stats.total > 0 && stats.received < stats.total) {
                    // 半成品保留：下次调用会带 Range 续传。
                    throw ZlibException(
                        "incomplete download: " + stats.received + "/" + stats.total,
                        ZlibException.Kind.NETWORK,
                    )
                }
                var finalFile = withExtension(target, stats.disposition, stats.finalUrl)
                if (!part.renameTo(finalFile)) {
                    part.copyTo(finalFile, overwrite = true)
                    part.delete()
                }
                // 完成：.part 已 rename 成正式文件，续传元数据随之作废。
                ZlibPartMetaStore.delete(part)
                // D14：扩展名可能来自 detail.format（不可信）或服务器文件名（不可达）→ 按文件头纠正。
                finalFile = correctExtensionByContent(finalFile)
                return finalFile
            } catch (e: ZlibException) {
                // P1c 收尾①：CANCELLED 不再删 .part（保留续传）；只有“这不是书/太小/写盘失败”才清。
                if (e.kind == ZlibException.Kind.DOWNLOAD) {
                    part.delete()
                    ZlibPartMetaStore.delete(part)
                }
                throw e
            } catch (e: Exception) {
                // 网络中断：保留 .part，下一个代理/下次重试可续传。
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
        cancelFlag: AtomicBoolean?,
        ignorePart: Boolean = false,
        retried416: Boolean = false,
    ): StreamStats {
        val resumeFrom = if (!ignorePart && partFile.isFile) partFile.length() else 0L
        // P1c 收尾④（审计 A1）：读上次落盘的 ETag/Last-Modified，续传带 If-Range。
        val partMeta = if (resumeFrom > 0L) ZlibPartMetaStore.read(partFile) else null
        val startedAt = System.nanoTime()
        // D5：流式入口同样校验（handle 可能来自远程 HTML 解析出的绝对 URL）。
        if (!isAllowedDownloadTarget(handle.directUrl)) {
            debugLog("dl_stream_blocked host=" + hostOf(handle.directUrl))
            throw ZlibException(
                "blocked download host: " + hostOf(handle.directUrl),
                ZlibException.Kind.DOWNLOAD,
            )
        }
        var current = handle.directUrl
        var conn: HttpURLConnection? = null
        var hops = 0
        try {
            var append = false
            var total = 0L
            var statusUsed = 0
            while (true) {
                checkCancelled(cancelFlag)
                conn = openConnection(URI(current), proxy)
                // P1c ②：有 .part 就请求续传；服务端不支持会回 200，届时从头写。
                // P1c 收尾④（审计 A1）：同时带 If-Range；版本变了服务端回 200，decide() 会截断重下。
                // D2（If-Range）：用上次响应的 ETag/Last-Modified 做版本校验。
                // mock E2E 只能覆盖「.part 存在 → 206 起点一致」；真实 CDN 的 ETag 语义
                // （弱 ETag、签名 URL 每次换 host/过期）等真实网络恢复后再补测（审计 D2/D16）。
                if (resumeFrom > 0L) {
                    conn.setRequestProperty("Range", "bytes=" + resumeFrom + "-")
                    ZlibResumePolicy.ifRangeHeader(resumeFrom, partMeta?.validator)?.let { validator ->
                        conn.setRequestProperty("If-Range", validator)
                    }
                }
                val status = conn.responseCode
                statusUsed = status
                val location = conn.getHeaderField("Location")
                if (status in 300..399 && !location.isNullOrBlank() && hops < MAX_DOWNLOAD_REDIRECTS) {
                    hops++
                    current = resolveDownloadLocation(current, location)
                    conn.disconnect()
                    conn = null
                    continue
                }
                // 审计 B1：.part 比服务端文件还长/版本变了 → 416；删半成品后不带 Range 自愈一次。
                // Lead m02083：即使本次**没带 Range**（全新下载），真实 CDN/中间层偶尔也会回 416 ——
                // 这是服务端违规，但会变成「用户点下载永远失败且重试无效」，所以同样无 Range 重发一次。
                // 只重试一次（retried416），避免死循环；仍然 416 才判 HTTP 失败。
                if (status == 416) {
                    if (retried416) {
                        debugLog("dl_416 host=" + hostOf(current) + " retried=true failed=true")
                        throw ZlibException("HTTP 416 for " + current, ZlibException.Kind.HTTP)
                    }
                    if (resumeFrom > 0L && !ignorePart) {
                        debugLog("dl_416 host=" + hostOf(current) + " part=" + resumeFrom + " retry=fresh")
                        // 半成品不可信（可能比服务端文件还长或版本已变）：删掉后从头下。
                        partFile.delete()
                        ZlibPartMetaStore.delete(partFile)
                    } else {
                        debugLog("dl_416 host=" + hostOf(current) + " retry=no_range")
                    }
                    conn.disconnect()
                    conn = null
                    return streamOnce(
                        handle, partFile, proxy, onProgress, cancelFlag,
                        ignorePart = true, retried416 = true,
                    )
                }
                if (status !in 200..299) {
                    throw ZlibException("HTTP " + status + " for " + current, ZlibException.Kind.HTTP)
                }
                val decision = ZlibResumePolicy.decide(
                    partLength = resumeFrom,
                    status = status,
                    contentRange = conn.getHeaderField("Content-Range"),
                    contentLength = conn.contentLengthLong,
                )
                append = decision.append
                total = decision.total
                // P1c 收尾④：落盘本次响应的 ETag/Last-Modified/总长，供下次续传 If-Range。
                val responseEtag = conn.getHeaderField("ETag")
                val responseLastModified = conn.getHeaderField("Last-Modified")
                val freshMeta = if (append) {
                    ZlibPartMeta(
                        etag = responseEtag ?: partMeta?.etag,
                        lastModified = responseLastModified ?: partMeta?.lastModified,
                        total = if (total > 0L) total else (partMeta?.total ?: -1L),
                        host = hostOf(current),
                    )
                } else {
                    ZlibPartMeta(responseEtag, responseLastModified, total, hostOf(current))
                }
                ZlibPartMetaStore.write(partFile, freshMeta)
                debugLog(
                    "dl_meta host=" + hostOf(current) +
                        " etag=" + (if (responseEtag != null) "yes" else "no") +
                        " lm=" + (if (responseLastModified != null) "yes" else "no") +
                        " if_range=" + (if (partMeta?.validator != null) "yes" else "no"),
                )
                break
            }
            val contentType = conn.contentType
            val disposition = conn.getHeaderField("Content-Disposition")
            val contentRange = conn.getHeaderField("Content-Range")
            val contentLength = conn.contentLengthLong
            val transferEncoding = conn.getHeaderField("Transfer-Encoding")
            // R7-G：改 PBPERF 打点，release 包也能看到 total/Content-Range 等关键信息（验收用）。
            perfLog(
                "PBPERF|phase=zlib_dl_stream|ms=" + elapsedMs(startedAt) +
                    "|extra=host:" + hostOf(current) +
                    ",status:" + statusUsed +
                    ",resume_from:" + resumeFrom +
                    ",append:" + append +
                    ",total:" + (if (total > 0L) total else 0L) +
                    ",cl:" + contentLength +
                    ",cr:" + metaValue(contentRange) +
                    ",chunked:" + (transferEncoding?.contains("chunked", ignoreCase = true) == true) +
                    ",ct:" + metaValue(contentType),
            )
            val stream = try {
                conn.inputStream
            } catch (e: IOException) {
                conn.errorStream
            } ?: throw ZlibException("empty response", ZlibException.Kind.DOWNLOAD)

            // P1c 收尾②：45s 零增长看门狗（对齐桌面 ZLibraryDownload.cpp 的 kStallMs=45000）。
            // readTimeout=15s 只覆盖「单次 read 停顿」；服务端若持续滴数据（极慢但不断），
            // 那个超时永远不会触发，下载可以挂到天荒地老 —— 这里按「received 字节零增长」兜底。
            val receivedRef = AtomicLong(0L)
            val stalledFlag = AtomicBoolean(false)
            val detector = ZlibStallDetector()
            val watchdog = Thread({
                try {
                    var running = true
                    while (running) {
                        Thread.sleep(ZlibStallDetector.TICK_MS)
                        detector.onBytes(receivedRef.get())
                        if (detector.stalled()) {
                            running = false
                            stalledFlag.set(true)
                            debugLog(
                                "dl_stall received=" + receivedRef.get() +
                                    " idle_ms=" + detector.idleMs(),
                            )
                            try {
                                conn?.disconnect()
                            } catch (ignored: Exception) {
                                // 连接可能已关闭
                            }
                        }
                    }
                } catch (e: InterruptedException) {
                    // 下载正常结束 / 失败收尾：守护线程退出。
                }
            }, "zlib-stall-watchdog")
            watchdog.isDaemon = true
            watchdog.start()

            var received = 0L
            var lastProgressAt = 0L
            var lastProgressLogAt = 0L
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            try {
                FileOutputStream(partFile, append).use { output ->
                    stream.use { input ->
                        while (true) {
                            checkCancelled(cancelFlag)
                            if (stalledFlag.get()) {
                                throw ZlibException(
                                    "network stalled (no progress for " +
                                        (ZlibStallDetector.STALL_TIMEOUT_MS / 1000) + "s)",
                                    ZlibException.Kind.NETWORK,
                                )
                            }
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (received == 0L && !append &&
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
                            receivedRef.set(received)
                            val written = (if (append) resumeFrom else 0L) + received
                            val now = System.nanoTime()
                            if (now - lastProgressAt > PROGRESS_THROTTLE_MS * 1_000_000L) {
                                lastProgressAt = now
                                onProgress(written, total)
                            }
                            // 每 2s 一条心跳：total=0（chunked/无 Content-Length）也照打，
                            // 用 received 增长 + kbps 区分「慢」与「卡」。
                            if (now - lastProgressLogAt > PROGRESS_LOG_INTERVAL_MS * 1_000_000L) {
                                lastProgressLogAt = now
                                // PBPERF 心跳（release 可见）：received/total/pct/kbps，用来区分「慢」与「卡」。
                                perfLog(
                                    "PBPERF|phase=zlib_dl_progress|ms=" + elapsedMs(startedAt) +
                                        "|extra=host:" + hostOf(current) +
                                        ",received:" + written +
                                        ",total:" + (if (total > 0L) total else 0L) +
                                        ",pct:" + (if (total > 0L) (written * 100 / total) else -1) +
                                        ",kbps:" + kbpsOf(written, startedAt) +
                                        ",final:false",
                                )
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                if (stalledFlag.get()) {
                    throw ZlibException(
                        "network stalled (no progress for " +
                            (ZlibStallDetector.STALL_TIMEOUT_MS / 1000) + "s)",
                        ZlibException.Kind.NETWORK,
                        e,
                    )
                }
                throw e
            } finally {
                watchdog.interrupt()
            }
            if (stalledFlag.get()) {
                throw ZlibException(
                    "network stalled (no progress for " + (ZlibStallDetector.STALL_TIMEOUT_MS / 1000) + "s)",
                    ZlibException.Kind.NETWORK,
                )
            }
            val finalSize = (if (append) resumeFrom else 0L) + received
            perfLog(
                "PBPERF|phase=zlib_dl_progress|ms=" + elapsedMs(startedAt) +
                    "|extra=host:" + hostOf(current) +
                    ",received:" + finalSize +
                    ",total:" + (if (total > 0L) total else 0L) +
                    ",pct:" + (if (total > 0L) (finalSize * 100 / total) else -1) +
                    ",kbps:" + kbpsOf(finalSize, startedAt) +
                    ",final:true",
            )
            onProgress(finalSize, total)
            return StreamStats(current, finalSize, total, contentType, disposition)
        } finally {
            conn?.disconnect()
        }
    }

    // ---------------------------------------------------------------- P1c helpers

    /** 取消检查：置位即抛 CANCELLED（调用方负责清 .part）。 */
    private fun checkCancelled(cancelFlag: AtomicBoolean?) {
        if (cancelFlag?.get() == true) {
            throw ZlibException("cancelled", ZlibException.Kind.CANCELLED)
        }
    }

    /** 可取消 sleep：倒计时等待期间每 250ms 检查一次，取消后立即返回（抛 CANCELLED）。 */
    private fun sleepCancellable(totalMs: Long, cancelFlag: AtomicBoolean?) {
        var remaining = totalMs
        while (remaining > 0L) {
            checkCancelled(cancelFlag)
            val step = minOf(remaining, 250L)
            Thread.sleep(step)
            remaining -= step
        }
    }

    private fun deletePartFile(destFile: File) {
        val parent = destFile.absoluteFile.parentFile ?: File(".")
        val part = File(parent, destFile.name + PART_SUFFIX)
        runCatching { part.delete() }
        ZlibPartMetaStore.delete(part)
    }

    /** 当前 `.part` 长度（0 = 不存在/空），诊断取消保留用。 */
    private fun partLength(destFile: File): Long {
        val parent = destFile.absoluteFile.parentFile ?: File(".")
        val part = File(parent, destFile.name + PART_SUFFIX)
        return if (part.isFile) part.length() else 0L
    }

    /** 首包是否未到：`.part` 不存在或长度为 0（用于「断连后重取一次直链」判定）。 */
    private fun partIsEmpty(destFile: File): Boolean {
        val parent = destFile.absoluteFile.parentFile ?: File(".")
        val part = File(parent, destFile.name + PART_SUFFIX)
        return !part.isFile || part.length() == 0L
    }

    /**
     * 下载层跳转解析 + 白名单校验（D5/D6/D8）：相对引用按 RFC 3986 解析，
     * 结果必须是 http(s) + 镜像域名族 / CDN 白名单（loopback 仅 debug mock 放行）。
     */
    private fun resolveDownloadLocation(current: String, location: String): String {
        val next = ZlibUrlPolicy.resolveLocation(current, location)
        if (!isAllowedDownloadTarget(next)) {
            debugLog("dl_redirect_blocked from=" + hostOf(current) + " to=" + hostOf(next))
            throw ZlibException(
                "blocked download redirect: " + hostOf(next),
                ZlibException.Kind.HTTP,
            )
        }
        return next
    }

    /** 下载目标是否在白名单内（D5）：当前镜像 / 镜像域名族 / CDN；debug 下允许本机 mock。 */
    private fun isAllowedDownloadTarget(url: String): Boolean =
        ZlibUrlPolicy.isAllowedDownloadUrl(url, mirror)

    /** host 归一比较（忽略大小写）。 */
    private fun sameHost(a: String, b: String): Boolean =
        hostOf(a).equals(hostOf(b), ignoreCase = true)

    /** CDN 签名直链判定（D9）：按 query key 解析（token/sig/X-Amz-*，filename+md5 组合），不再整串 contains。 */
    private fun isSignedDownloadUrl(raw: String?): Boolean =
        ZlibUrlPolicy.isSignedDownloadUrl(raw)

    /**
     * 从签名直链的 filename= 参数解出文件名（D22）：百分号解码保留 '+'（不按表单语义转空格），
     * 去掉路径分隔符；文件名再交给 [withExtension] 取扩展名。
     */
    private fun downloadFileNameOf(url: String): String? {
        val raw = ZlibUrlPolicy.rawQueryParam(url, "filename") ?: return null
        val decoded = if (raw.contains('%')) ZlibUrlPolicy.percentDecode(raw) ?: raw else raw
        return decoded.substringAfterLast('/').substringAfterLast('\\').trim()
            .takeIf { it.isNotBlank() }
    }
    /**
     * D14：下载完成后按文件头纠正扩展名（%PDF → .pdf、PK → .epub），
     * 避免 detail.format 缺失/错误时存成 .pdf 再交给 PDF 解析器必失败。
     */
    private fun correctExtensionByContent(file: File): File {
        val sniffed = ZlibBookFormat.sniff(file) ?: return file
        val corrected = ZlibBookFormat.correctedName(file.name, sniffed) ?: return file
        val target = File(file.parentFile, corrected)
        if (target.exists()) {
            debugLog("dl_format keep=" + file.extension + " sniff=" + sniffed + " reason=target_exists")
            return file
        }
        return if (file.renameTo(target)) {
            debugLog("dl_format from=" + file.extension + " to=" + sniffed)
            target
        } else {
            file
        }
    }

    /** 扩展名缺失时按 Content-Disposition（优先）→ URL 末段 → 默认 pdf 补齐。 */
    private fun withExtension(destFile: File, disposition: String?, finalUrl: String): File {
        if (destFile.extension.isNotBlank()) return destFile
        val fromName = ZlibDownloadParser.filenameFromDisposition(disposition)
            ?.substringAfterLast('.', "")
            ?.takeIf { it.isNotBlank() && it.length <= 5 }
        // R7-G：签名直链把真实文件名放在 filename= 查询参数里（URLEncode），优先于 URL path 末段。
        val fromParam = downloadFileNameOf(finalUrl)
            ?.substringAfterLast('.', "")
            ?.takeIf { it.isNotBlank() && it.length <= 5 }
        val fromUrl = runCatching { URI(finalUrl).path.substringAfterLast('.', "") }
            .getOrDefault("")
            .takeIf { it.isNotBlank() && it.length <= 5 }
        val extension = (fromName ?: fromParam ?: fromUrl ?: "pdf")
            .lowercase()
            .filter { it.isLetterOrDigit() }
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
        // JVM 单测：debugLog 也走注入出口，才能断言 dl_416/重试等关键分支。
        testLogger?.let { logger ->
            logger(message)
            return
        }
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

    /** SHA-1（十六进制）——挑战页原文指纹，用于与桌面样本对比是否为同一版页面。 */
    private fun sha1Of(text: String): String = try {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
            .digest(text.toByteArray(Charsets.UTF_8))
        digest.joinToString("") { String.format(Locale.US, "%02x", it) }
    } catch (e: Exception) {
        "error"
    }

    /** 挑战页前 N 字符（空白压成单空格、去掉换行，避免 logcat 多行）。 */
    private fun headOf(text: String, max: Int): String {
        val cleaned = text.replace(Regex("[\\s\\u00A0]+"), " ").trim()
        return if (cleaned.length <= max) cleaned else cleaned.substring(0, max)
    }

    /** debug 包把挑战页原文落盘（files/zlib_challenge_last.html），供桌面端逐字节对比。 */
    private fun saveChallengeBody(body: String): String {
        if (!isDebuggable) return "none"
        val dir = appContext?.filesDir ?: return "none"
        return try {
            File(dir, "zlib_challenge_last.html").writeText(body, Charsets.UTF_8)
            "files/zlib_challenge_last.html"
        } catch (e: Exception) {
            "none"
        }
    }

    /** 取某 host 当前 Cookie 头里单个 cookie 的短值（诊断 bsrv 是否已更新，值截 8 位）。 */
    private fun cookieValue(uri: URI, name: String): String {
        val header = cookieHeader(uri) ?: return "none"
        val value = ZlibCookieHeader.parse(header).entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value ?: return "none"
        return if (value.length <= 8) value else value.substring(0, 8)
    }

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

        /** 单个响应体上限（HTML/JSON 状态页远小于此；二进制下载走流式，不经过 readBody）。 */
        private const val MAX_RESPONSE_BYTES = 8L * 1_024 * 1_024

        /** 页面/状态页（HTML）上限（D11）：正常 Z-Library 页面 < 250KB，4MB 已远超。 */
        private const val MAX_HTML_BYTES = 4L * 1_024 * 1_024

        /** 候选直链最多探测数（D21）：远程 /dl 可塞海量 class=btn 链接，必须有上限。 */
        private const val MAX_DL_CANDIDATES = 5
        private const val MIN_DOWNLOAD_BYTES = 1_024L
        private const val DOWNLOAD_BUFFER_BYTES = 8 * 1_024
        private const val PROGRESS_THROTTLE_MS = 150L

        /** 下载心跳日志间隔（Lead 要求每 2s 一条 dl_progress，卡住时也能看出最后进度）。 */
        private const val PROGRESS_LOG_INTERVAL_MS = 2_000L
        private const val MAX_DOWNLOAD_REDIRECTS = 5

        /** P1b：/dl 状态机跳数与倒计时等待上限。 */
        private const val MAX_DL_HOPS = 4
        private const val MAX_COUNTDOWN_ROUNDS = 8
        private const val MAX_COUNTDOWN_WAIT_S = 20

        /** P1c：导航页（zz.ggonav.com 已 NXDOMAIN，仅 best-effort）后台抓取总预算。 */
        private const val NAV_FETCH_BUDGET_MS = 8_000L

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
