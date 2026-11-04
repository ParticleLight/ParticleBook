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

/**
 * 线路弹层展示项（B6/T10）：在 [ZMirrorInfo] 的纯 URL 列表上补元数据。
 * [ZlibClient.mirrorRows] 单次快照生成，host/meta/current 同源；切换请按 [url] 调
 * [ZlibClient.switchMirror]，不要用下标（后台刷新可能重排）。
 */
data class ZMirrorItem(
    val url: String,
    val host: String,
    /** official / dynamic / fallback。 */
    val tier: String,
    /** B1 探测排名：0 正式站、1 未探测、2 推广页、3 其它、4 不通。 */
    val rank: Int,
    val latencyMs: Long,
    val lastOkAt: Long,
    /** 12h 成功 TTL 内（「上次成功」展示用）。 */
    val successFresh: Boolean,
    val current: Boolean,
    /** 用户手动切换固定的线路（B7 pin）。 */
    val pinned: Boolean,
    /** 降权未到期（「暂不可用」）。 */
    val demoted: Boolean,
)

class ZlibClient(
    private val appContext: Context? = null,
) {

    /** 最近一次可用的镜像（如 `https://z-lib.by`）；未探测前为 null。可被外部覆盖用于固定镜像。 */
    @Volatile
    var mirror: String? = null

    /**
     * B7：用户手动切换（[switchMirror]）固定的线路；T24 起持久化到 prefs 独立 key（[ZlibPrefsMirrorPinStore]），
     * 进程重启后仍保留。探测第 0 阶段优先探它。
     * P2-6：只有明确非正式站证据（200 但 PAGE/OTHER、HTTP 4xx）才清 pin；网络失败/超时/5xx 只记
     * 失败次数与时间，不再因一次瞬时故障丢掉用户选择（与 T17「未探明不丢记忆」一致）。
     */
    @Volatile
    private var pinnedMirror: String? = null

    /** P2-6：mirror / prefs KEY_MIRROR / [mirrorEpoch] 的写锁（后台结果与用户切换互斥）。 */
    private val mirrorLock = Any()

    /**
     * P2-6：用户切换/清缓存的纪元。后台探测/搜索在开始时记下纪元，写回前若纪元变了（用户动过线路）
     * 就丢弃旧结果，避免「用户刚切到 B，后台把 A 写回缓存」。
     */
    private val mirrorEpoch = AtomicLong(0L)

    private fun currentMirrorEpoch(): Long = mirrorEpoch.get()

    /** T24：pin 持久化出口；JVM 单测注入内存实现（生产为 SharedPreferences 独立 key）。 */
    internal var pinStoreForTest: ZlibMirrorPinStore? = null

    private val pinStore: ZlibMirrorPinStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        pinStoreForTest ?: ZlibPrefsMirrorPinStore(prefs)
    }

    @Volatile
    private var pinLoaded: Boolean = false

    /** 懒加载磁盘 pin（冷启/首次展示弹层时才读一次 prefs；空值也算已加载）。 */
    private fun ensurePinLoaded() {
        if (pinLoaded) return
        synchronized(this) {
            if (pinLoaded) return
            pinnedMirror = normalizeMirror(pinStore.readPin()).takeIf { it.isNotEmpty() }
            pinLoaded = true
        }
    }

    /** 当前 pin（含冷启后从 prefs 恢复的值）。P2-6：与 [setPinnedMirror] 同锁，读改写不分裂。 */
    @Synchronized
    private fun pinned(): String? {
        ensurePinLoaded()
        return pinnedMirror
    }

    /**
     * 统一写入口：内存 + 持久化同步更新（null = 取消 pin）。
     * [commit]=true 走同步落盘（用户主动切线路；apply 后立刻杀进程可能丢 pin，P2-6）。
     */
    @Synchronized
    private fun setPinnedMirror(url: String?, commit: Boolean = false) {
        ensurePinLoaded()
        val normalized = normalizeMirror(url).takeIf { it.isNotEmpty() }
        pinnedMirror = normalized
        if (commit) pinStore.writePinNow(normalized) else pinStore.writePin(normalized)
    }

    /**
     * P2-6：CAS 清 pin——只有当前 pin 仍是 [expected] 时才清。
     * 防止「判断当前是旧 pin → 用户切换 → 把用户刚选的新 pin 清掉」的 check-then-act。
     */
    @Synchronized
    private fun clearPinIfMatches(expected: String, reason: String): Boolean {
        ensurePinLoaded()
        val target = normalizeMirror(expected)
        if (target.isEmpty() || normalizeMirror(pinnedMirror) != target) return false
        pinnedMirror = null
        pinStore.writePin(null)
        debugLog("mirror_unpin reason=" + reason + " host=" + hostOf(expected))
        return true
    }

    /**
     * T24③：探测整轮硬上限（默认 [PROBE_BUDGET_MS]=12s，测试/诊断可调）。
     * 运行时低于 [MIN_REQUEST_MS] 会被夹到下限，避免一次请求都不发。
     */
    @Volatile
    var probeBudgetMs: Long = PROBE_BUDGET_MS

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

    /** P1c ③：动态镜像名单后台刷新单飞标记（源站 zlib.wwwnav.com，B2；best-effort，不阻塞探测）。 */
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
        /** 本次响应过程中解过一次挑战（R7-H：mirror_probe kind=pow 判定用）。 */
        val challenged: Boolean = false,
    )

    /** B1：可访问但非正式站的最佳候选（只记 rank / 打日志，不采用）。 */
    private data class SoftPick(
        val url: String,
        val tierLabel: String,
        val source: String,
        val rank: ZlibMirrorRegistry.Rank,
        val latencyMs: Long,
    )

    /** 单候选探测结果：[picked] 可用线路；[soft] 能访问但非正式站（不采用，只记 rank）。 */
    private data class ProbeOutcome(
        val picked: String? = null,
        val soft: SoftPick? = null,
        /**
         * P2-6/P3：明确非正式站证据的清 pin 原因（日志用）；null = 证据不足，pin 保留。
         * 200 但 rank=PAGE/OTHER、HTTP 4xx（408/429 瞬时状态除外）、首页网关 redirect_home 才算；
         * 网络失败/超时/5xx/挑战都不算——瞬时故障不能作为清用户 pin 的依据。
         * 网关也清 pin 是为了不让一条死线路在每轮 preferred 阶段白耗预算。
         */
        val evidenceReason: String? = null,
    )

    // ---------------------------------------------------------------- public API

    /**
     * 搜索一页。阻塞，调用方放在 IO 线程。
     *
     * 首包（page == 1）带自动换线（B3）：当前线路网络/HTTP/挑战失败时，按注册表排序换下一条，
     * 额度=线路数、全轮 20s 兜底上限、全失败抛 NO_MIRROR。站内翻页（page > 1）不换线：
     * 换线会丢分页上下文，且空页/404 是正常结果（对齐桌面 ClassifyLoadedDocument 只判进站导航）。
     *
     * @throws ZlibException kind=NO_MIRROR / CHALLENGE_FAILED / HTTP / PARSE / NETWORK
     */
    @Throws(ZlibException::class)
    fun search(
        query: String,
        page: Int = 1,
        filters: ZlibSearchFilters? = null,
    ): ZSearchPage {
        val q = query.trim()
        require(q.isNotEmpty()) { "query must not be blank" }
        require(page >= 1) { "page must be >= 1" }
        ensureInitialized()
        val startedAt = System.nanoTime()
        // P2-3：在读取 base 之前先取操作纪元快照，避免「读 base → 用户切线路 → 取 epoch」窗口里
        // 旧请求的成功/失败混入新线路的 epoch 语义。
        val opEpoch = currentMirrorEpoch()
        try {
            val base = mirror ?: probeMirrors()
            if (page > 1) return runSearchOnce(base, q, page, filters = filters)
            return searchWithFailover(base, q, startedAt, opEpoch, filters)
        } catch (e: ZlibException) {
            // B1/T10 P1-3/T17：PARSE 可能是推广/导航等非正式站返回的 200 HTML；
            // 只有明确非正式站证据（PAGE/OTHER/FAIL）才清镜像记忆，未探明线路保留 pin/prefs 交给重探。
            // P2-3：传操作纪元；失败期间用户切过线路则释放逻辑自行跳过，不会清掉新选择。
            if (e.kind == ZlibException.Kind.PARSE) releaseMirrorIfNotApp(opEpoch)
            // 失败后清一次性状态：网络恢复后（进程不重启）下一次 search() 自动重新解析代理并重探镜像。
            debugLog("reset_state reason=search_failure kind=${e.kind}")
            resetStateLocked()
            throw e
        }
    }

    /** 单次搜索请求（不换线）。HTTP 非 200 抛带 httpCode 的 [ZlibException.Kind.HTTP]。 */
    private fun runSearchOnce(
        base: String,
        query: String,
        page: Int,
        deadlineNanos: Long? = null,
        filters: ZlibSearchFilters? = null,
    ): ZSearchPage {
        val url = zlibSearchUrl(base, query, page, filters)
        val res = getWithChallenge(URI(url), deadlineNanos = deadlineNanos)
        if (res.status != 200) {
            throw ZlibException(
                "HTTP ${res.status} for $url",
                ZlibException.Kind.HTTP,
                cause = null,
                httpCode = res.status,
            )
        }
        return ZlibSearchParser.parse(res.body, query, page)
    }

    /**
     * B3 首包换线：当前线路失败后按 [ZlibMirrorRegistry.orderedEntries] 排序取下一条，
     * 跳过降权未到期项；额度=线路数、[SEARCH_FAILOVER_BUDGET_MS] 兜底。
     * 网络/HTTP/挑战/PARSE 都换线（PARSE 一律换线，T24②）；按残留 rank 决定是否清 pin（PARSE 保留 pin）。
     * P2-6：本轮开始记录 [currentMirrorEpoch]，成功写回前纪元变了（用户切线路）就丢弃旧结果。
     */
    private fun searchWithFailover(
        firstBase: String,
        query: String,
        startedAt: Long,
        epoch: Long,
        filters: ZlibSearchFilters? = null,
    ): ZSearchPage {
        val now = System.currentTimeMillis()
        // P2-1/P2-3：epoch 由 [search] 在读取 base 前捕获，本函数只用快照，不再现取 current。
        val opEpoch = epoch
        val file = registryFile()
        ZlibMirrorRegistry.ensureLoaded(file, now)
        val budget = maxOf(1, ZlibMirrorRegistry.orderedUrls(now).size)
        val deadlineNanos = startedAt + SEARCH_FAILOVER_BUDGET_MS * 1_000_000L
        val tried = LinkedHashSet<String>()
        var base: String? = firstBase
        var attempts = 0
        var lastFailure: ZlibException? = null
        while (base != null && attempts < budget) {
            val current = normalizeMirror(base)
            if (current.isEmpty() || !tried.add(current)) break
            attempts++
            try {
                val result = runSearchOnce(base, query, 1, deadlineNanos, filters)
                markSearchSuccess(base, now, opEpoch)
                return result
            } catch (e: ZlibException) {
                lastFailure = e
                if (!isFailoverWorthy(e)) {
                    debugLog("mirror_advance_skip host=${hostOf(base)} kind=${e.kind} reason=not_transient")
                    throw e
                }
                val reason = failoverReasonOf(e)
                // T17：先取失败前的 rank 再 recordFailure（后者会把 rank 改成 FAIL，丢掉「未探明」证据）。
                val failedRank = mirrorRankOf(base, now)
                val entry = ZlibMirrorRegistry.recordFailure(base, reason, now)
                debugLog(
                    "mirror_advance from=${hostOf(base)} reason=$reason kind=${e.kind}" +
                        " tried=${tried.size}/$budget demote_until=${entry?.demoteUntil ?: 0L}",
                )
                releaseFailedMirror(
                    base, failedRank,
                    keepPin = e.kind == ZlibException.Kind.PARSE ||
                        (e.kind == ZlibException.Kind.HTTP && isTransientHttpStatus(e.httpCode)),
                    epoch = opEpoch,
                )
                if (remainingMs(deadlineNanos) < MIN_REQUEST_MS) {
                    debugLog("mirror_advance_abort reason=budget remaining_ms=${remainingMs(deadlineNanos)}")
                    break
                }
                base = nextMirrorCandidate(tried, now)
            }
        }
        // P2-3：本轮失败期间用户切过线路（epoch 已变）→ 旧请求的「全线路失败」不代表用户刚选的线；
        // 有当前线路就在新线路上再试一次，结果/异常都反映用户当前选择，不抛 stale NO_MIRROR banner。
        if (currentMirrorEpoch() != opEpoch) {
            val current = mirror ?: pinned()
            if (current != null) {
                debugLog("search_stale_retry current=" + hostOf(current) + " tried=" + tried.size)
                val retryEpoch = currentMirrorEpoch()
                val result = runSearchOnce(current, query, 1, null, filters)
                markSearchSuccess(current, now, retryEpoch)
                return result
            }
        }
        ZlibMirrorRegistry.save(file)
        debugLog("probe_result fail_all tried=" + tried.size + " stage=search_failover")
        val detail = lastFailure?.message ?: "budget"
        throw ZlibException(
            "no available mirror for search (tried=${tried.size}, last: $detail)",
            ZlibException.Kind.NO_MIRROR,
            lastFailure,
        )
    }

    /**
     * 搜索成功：记住该线（prefs/内存 + 续 12h 成功 TTL），并触发一次后台名单刷新（单飞）。
     * P2-6：只有本操作开始后用户没切过线路（[epoch] 未变）才写 mirror/prefs，旧结果不覆盖用户选择；
     * 注册表成功记录照常写（那是这条线路真实可用的证据）。
     */
    private fun markSearchSuccess(base: String, now: Long, epoch: Long) {
        val chosen = base.trimEnd('/')
        val adopted = synchronized(mirrorLock) {
            if (epoch != mirrorEpoch.get()) {
                false
            } else {
                mirror = chosen
                prefs?.edit()?.putString(KEY_MIRROR, chosen)?.apply()
                true
            }
        }
        if (!adopted) debugLog("mirror_epoch_drop stage=search host=" + hostOf(chosen))
        // T17：搜索解析成功就是「正式站」证据；记 APP，避免后续 PARSE 时被当成未探明软页误清。
        ZlibMirrorRegistry.recordSuccess(chosen, null, -1L, now, ZlibMirrorRegistry.Rank.APP)
        ZlibMirrorRegistry.save(registryFile())
        if (adopted) debugLog("mirror_working host=" + hostOf(chosen) + " stage=search")
        maybeRefreshMirrorListInBackground()
    }

    /**
     * 失败线退出记忆（T17）：只有明确「非正式站」证据（PAGE/OTHER/FAIL）才清 pin / 内存 mirror / prefs；
     * UNKNOWN/null 只说明这条线还没被探测定性，单次失败不足以丢用户选择，保留记忆交给换线处理。
     */
    private fun releaseFailedMirror(
        failed: String,
        rank: ZlibMirrorRegistry.Rank?,
        keepPin: Boolean = false,
        epoch: Long,
    ) {
        val target = normalizeMirror(failed)
        if (target.isEmpty()) return
        if (!isNonAppRank(rank)) {
            debugLog(
                "mirror_release_skip reason=rank_unproven rank=" + (rank?.label ?: "none") +
                    " host=" + hostOf(failed),
            )
            return
        }
        // P2-1/P2-6：epoch 校验、pin CAS、mirror/prefs 清理放在同一把锁里。
        // 只对 pin 值做 CAS 不够：A 失败期间用户切走再切回 A，pin 值仍是 A，会把用户刚重新选中的 A 清掉。
        // epoch 在请求发起时快照，只要用户切过任何一次线路就整块跳过，新选择永不被旧失败波及。
        synchronized(mirrorLock) {
            if (epoch != mirrorEpoch.get()) {
                debugLog("mirror_release_skip reason=epoch host=" + hostOf(failed))
                return
            }
            if (keepPin) {
                if (normalizeMirror(pinned()) == target) {
                    // T24②：PARSE 只说明这条线返回的页面结构不对，不等于用户选择的线路已失效 → 不清 pin。
                    debugLog("mirror_keep_pin reason=parse host=" + hostOf(failed))
                }
            } else {
                // P2-6：pin 用 CAS 清（判断与写入同一把锁），不会误清用户刚切换的新 pin。
                clearPinIfMatches(failed, "search_fail")
            }
            if (normalizeMirror(mirror) == target) mirror = null
            if (normalizeMirror(prefs?.getString(KEY_MIRROR, null)) == target) {
                prefs?.edit()?.remove(KEY_MIRROR)?.apply()
            }
        }
    }

    /** 明确的「非正式站/坏站」rank（T17）：只有这些证据才清镜像记忆。 */
    private fun isNonAppRank(rank: ZlibMirrorRegistry.Rank?): Boolean =
        rank == ZlibMirrorRegistry.Rank.PAGE ||
            rank == ZlibMirrorRegistry.Rank.OTHER ||
            rank == ZlibMirrorRegistry.Rank.FAIL

    /**
     * P3：408 请求超时 / 429 限流是瞬时状态（服务端忙/慢），不当作「线路明确非正式站」证据。
     * 这类失败只记失败次数与时间，pin 保留等下次重探；其余 4xx（如 404/403/410）仍按确定性拒绝清 pin。
     */
    private fun isTransientHttpStatus(code: Int?): Boolean = code == 408 || code == 429

    /**
     * 下一条候选：按注册表排序，优先未降权项；全降权时退回全量（最后一搏）。
     * T10 P1-2：过滤 rank >= PAGE（推广/导航/其它页不配当搜索线路，省得换了线还是 200 垃圾 HTML）；
     * 全被过滤（或已试完）→ null，由调用方抛 NO_MIRROR 走 banner。
     */
    private fun nextMirrorCandidate(tried: Set<String>, now: Long): String? {
        val entries = ZlibMirrorRegistry.orderedEntries(now)
        val usable = entries.filterNot { it.isDemoted(now) }
        val pool = if (usable.isEmpty()) entries else usable
        return pool
            .filter { it.sortRank(now) < ZlibMirrorRegistry.Rank.PAGE.value }
            .firstOrNull { !tried.contains(normalizeMirror(it.url)) }
            ?.url
    }

    /**
     * B3/B1：是否值得换线。
     * T24②：PARSE 也一律换线——页面到了但结构不对（软 404/SPA 壳/模板改版）时，下一条线路往往可用；
     * 换线过程不 adopt 失败页（不写 mirror/prefs/lastOk），按残留 rank 决定是否清 pin（PARSE 保留 pin）。
     * 全滤掉后由 NO_MIRROR 走 banner。
     */
    private fun isFailoverWorthy(e: ZlibException): Boolean = when (e.kind) {
        ZlibException.Kind.NETWORK,
        ZlibException.Kind.HTTP,
        ZlibException.Kind.CHALLENGE_FAILED,
        ZlibException.Kind.PARSE -> true
        else -> false
    }

    private fun failoverReasonOf(e: ZlibException): String = when (e.kind) {
        ZlibException.Kind.HTTP -> e.httpCode?.toString() ?: "http"
        ZlibException.Kind.CHALLENGE_FAILED -> "challenge_fail"
        ZlibException.Kind.PARSE -> "parse"
        else -> "network"
    }

    /** 当前线路的探测 rank；不在注册表里返回 null（未探明，T17 起不据此清镜像记忆）。 */
    private fun mirrorRankOf(url: String?, now: Long): ZlibMirrorRegistry.Rank? {
        val target = normalizeMirror(url)
        if (target.isEmpty()) return null
        ZlibMirrorRegistry.ensureLoaded(registryFile(), now)
        return ZlibMirrorRegistry.orderedEntries(now)
            .firstOrNull { normalizeMirror(it.url) == target }
            ?.rank
    }

    /**
     * T10 P1-3 / T17：PARSE 且当前线路有明确非正式站证据（PAGE/OTHER/FAIL）→ 清镜像/pin/prefs；
     * UNKNOWN/null（旧缓存、手动固定但还没探测）只当本次失败，保留记忆交给换线/重探。
     */
    private fun releaseMirrorIfNotApp(epoch: Long) {
        val current = mirror ?: prefs?.getString(KEY_MIRROR, null) ?: return
        val rank = mirrorRankOf(current, System.currentTimeMillis())
        if (isNonAppRank(rank)) {
            // T24②：PARSE 不 adopt 失败页、也不清用户 pin（换线语义只作用于 mirror/prefs 记忆）。
            // P2-1：epoch 用搜索发起时的快照，期间用户切过线路则释放逻辑整体跳过。
            releaseFailedMirror(current, rank, keepPin = true, epoch = epoch)
            debugLog("mirror_release reason=parse rank=" + (rank?.label ?: "none") + " host=" + hostOf(current))
        } else {
            debugLog(
                "mirror_release_skip reason=parse_unproven rank=" + (rank?.label ?: "none") +
                    " host=" + hostOf(current),
            )
        }
    }

    /**
     * 探测可用镜像（R7-H 分层）：上次成功线路 -> T0 官方族（3s/host、合计 5s）->
     * T1 动态导航源（zlib.wwwnav.com，缓存过期才同步抓）-> T2 内置兜底（旧静态表去掉 DiamWall）。
     *
     * 官方不可达时立即降级，用户要求「网络允许时官网优先，但不能卡住」；整轮硬上限 [PROBE_BUDGET_MS]。
     * 每轮结果写 [ZlibMirrorRegistry]（filesDir/zlib_mirrors.json）：成功记 finalUrl/延迟，失败按
     * 10min x 2^n（上限 2h）降权；DiamWall 站（需 JS）探测命中即跳过并 demote。
     *
     * @throws ZlibException kind=NO_MIRROR
     */
    @Throws(ZlibException::class)
    fun probeMirrors(): String {
        ensureInitialized()
        val startedAt = System.nanoTime()
        // T24③：整轮硬上限可配置，默认 12s 不变；不低于一次最小请求预算。
        val probeBudget = probeBudgetMs.coerceAtLeast(MIN_REQUEST_MS)
        val globalDeadline = startedAt + probeBudget * 1_000_000L
        val now = System.currentTimeMillis()
        val file = registryFile()
        ZlibMirrorRegistry.ensureLoaded(file, now)
        val tried = LinkedHashSet<String>()
        var lastError: String? = null
        // P2-6：本轮探测的纪元；用户中途切换线路后，旧探测结果不再写回 mirror/prefs。
        var epoch = currentMirrorEpoch()
        // B1：本轮「可访问但非正式站」的最佳候选（rank 优先、同 rank 比延迟），仅用于日志。
        var softPick: SoftPick? = null

        debugLog(
            "mirror_tier official=" + ZlibMirrorRegistry.orderedOfficial(now).size +
                " dynamic=" + ZlibMirrorRegistry.dynamicCandidates(now).size +
                " fallback=" + ZlibMirrorRegistry.fallbackCandidates(now).size +
                " budget_ms=" + probeBudget +
                " official_budget_ms=" + OFFICIAL_PHASE_BUDGET_MS,
        )

        // 0) B7 记忆上次成功：pin -> 上次选中（prefs/内存）-> 注册表最近成功（12h TTL），优先重探。
        //    预算 4s，避免「记忆线路全挂」时把整轮 12s 提前耗尽。
        val preferredDeadline = minOf(startedAt + PREFERRED_PHASE_BUDGET_MS * 1_000_000L, globalDeadline)
        for ((preferredUrl, preferredSource) in preferredMirrors(now)) {
            if (remainingMs(preferredDeadline) < MIN_REQUEST_MS) break
            if (!tried.add(preferredUrl)) continue
            val tierLabel = ZlibMirrorRegistry.tierOf(preferredUrl).label
            val hostDeadline = minOf(System.nanoTime() + PER_MIRROR_BUDGET_MS * 1_000_000L, preferredDeadline)
            val outcome = probeCandidate(
                preferredUrl, tierLabel, preferredSource, attemptsFor(preferredUrl), hostDeadline, now,
            )
            outcome.picked?.let { picked ->
                if (adoptMirror(picked, epoch)) {
                    ZlibMirrorRegistry.save(file)
                    return picked
                }
                // 用户刚切换/清缓存：优先返回用户当前线路，别让旧探测覆盖用户选择。
                (mirror ?: pinned())?.let { return it }
                epoch = currentMirrorEpoch()
            }
            softPick = betterSoftPick(softPick, outcome.soft)
            lastError = "preferred=" + hostOf(preferredUrl) + " failed"
            // P2-6：只有明确非正式站证据才清 pin；网络失败/超时/5xx 只是本次不可达，
            // 失败次数/时间已由 probeCandidate 写入注册表，pin 保留等下次重探。
            if (normalizeMirror(pinned()) == normalizeMirror(preferredUrl)) {
                val evidence = outcome.evidenceReason
                if (evidence != null) {
                    // 4xx/登录页/错误页/网关：明确非正式站，清 pin 并记下具体原因。
                    // P2-1：epoch 校验与清 pin 同锁——用户在本次探测期间切走再切回时，旧证据不能清新 pin。
                    var staleDrop = false
                    synchronized(mirrorLock) {
                        if (epoch != mirrorEpoch.get()) {
                            staleDrop = true
                        } else {
                            clearPinIfMatches(preferredUrl, evidence)
                        }
                    }
                    if (staleDrop) {
                        debugLog("mirror_epoch_drop stage=probe_pin_clear host=" + hostOf(preferredUrl))
                    }
                } else if (outcome.soft != null) {
                    // P2-2：合法空结果页/SPA 壳/维护页只是结构不像正式站，可能随时恢复，保留 pin。
                    debugLog("mirror_pin_keep reason=soft_page host=" + hostOf(preferredUrl))
                } else {
                    debugLog("mirror_pin_keep reason=transient host=" + hostOf(preferredUrl))
                }
            }
        }

        // 1) T0 官方族：单 host 3s、本阶段 5s；不可达立即降级（官网优先但不卡住）。
        val officialDeadline = minOf(System.nanoTime() + OFFICIAL_PHASE_BUDGET_MS * 1_000_000L, globalDeadline)
        for (entry in probeable(ZlibMirrorRegistry.orderedOfficial(now, tried), now)) {
            if (remainingMs(officialDeadline) < MIN_REQUEST_MS) {
                debugLog("mirror_tier tier=official phase=budget_exhausted tried=" + tried.size)
                break
            }
            tried.add(entry.url)
            val hostDeadline = minOf(System.nanoTime() + PER_MIRROR_BUDGET_MS * 1_000_000L, officialDeadline)
            val outcome = probeCandidate(entry.url, "official", entry.source, attemptsFor(entry.url), hostDeadline, now)
            outcome.picked?.let { picked ->
                if (adoptMirror(picked, epoch)) {
                    ZlibMirrorRegistry.save(file)
                    return picked
                }
                // 用户刚切换/清缓存：优先返回用户当前线路，别让旧探测覆盖用户选择。
                (mirror ?: pinned())?.let { return it }
                epoch = currentMirrorEpoch()
            }
            softPick = betterSoftPick(softPick, outcome.soft)
            lastError = lastProbeError ?: lastError
        }

        // 2) T1 动态导航源：缓存过期/失败退避到期才抓；抓取失败仍复用旧动态名单。
        if (remainingMs(globalDeadline) >= MIN_REQUEST_MS) {
            prepareDynamicPhase(globalDeadline, now, lastError)
            for (entry in probeable(ZlibMirrorRegistry.dynamicCandidates(now, tried), now)) {
                if (remainingMs(globalDeadline) < MIN_REQUEST_MS) break
                tried.add(entry.url)
                val hostDeadline = minOf(System.nanoTime() + PER_MIRROR_BUDGET_MS * 1_000_000L, globalDeadline)
                val outcome = probeCandidate(entry.url, "dynamic", entry.source, 1, hostDeadline, now)
                outcome.picked?.let { picked ->
                    if (adoptMirror(picked, epoch)) {
                        ZlibMirrorRegistry.save(file)
                        return picked
                    }
                    // 用户刚切换/清缓存：优先返回用户当前线路，别让旧探测覆盖用户选择。
                    (mirror ?: pinned())?.let { return it }
                    epoch = currentMirrorEpoch()
                }
                softPick = betterSoftPick(softPick, outcome.soft)
                lastError = lastProbeError ?: lastError
            }
        }

        // 3) T2 内置兜底：继续把 T0 没探完的官方站探完（同一批 URL，靠 tried 去重）。
        for (entry in probeable(ZlibMirrorRegistry.fallbackCandidates(now, tried), now)) {
            if (remainingMs(globalDeadline) < MIN_REQUEST_MS) break
            tried.add(entry.url)
            val hostDeadline = minOf(System.nanoTime() + PER_MIRROR_BUDGET_MS * 1_000_000L, globalDeadline)
            val outcome = probeCandidate(entry.url, "fallback", entry.source, attemptsFor(entry.url), hostDeadline, now)
            outcome.picked?.let { picked ->
                if (adoptMirror(picked, epoch)) {
                    ZlibMirrorRegistry.save(file)
                    return picked
                }
                // 用户刚切换/清缓存：优先返回用户当前线路，别让旧探测覆盖用户选择。
                (mirror ?: pinned())?.let { return it }
                epoch = currentMirrorEpoch()
            }
            softPick = betterSoftPick(softPick, outcome.soft)
            lastError = lastProbeError ?: lastError
        }

        // 4) B1/T10 P1-1：可访问但非正式站（推广/导航/停用站）不采用——只记 rank/延迟（probeCandidate
        //    已写 recordProbeResult），不写 mirror/prefs、不写 lastOkAt。整轮无正式站 → NO_MIRROR，
        //    由 UI 弹 banner 让用户手动切线路（比把推广页当搜索线路安全）。
        softPick?.let { soft ->
            debugLog(
                "mirror_probe_soft host=" + hostOf(soft.url) + " rank=" + soft.rank.label +
                    " soft_only=1 tried=" + tried.size,
            )
        }

        // P2-3：本轮探测期间用户切过线路（epoch 变化）→ 旧探测的「全军覆没」不代表新线路，
        // 已有当前线路就直接返回，不弹 stale NO_MIRROR banner。
        if (currentMirrorEpoch() != epoch) {
            val current = mirror ?: pinned()
            if (current != null) {
                debugLog("mirror_probe_stale return_current=" + hostOf(current) + " tried=" + tried.size)
                ZlibMirrorRegistry.save(file)
                return current
            }
        }

        val totalMs = (System.nanoTime() - startedAt) / 1_000_000L
        debugLog("mirror_probe_abort budget_ms=" + probeBudget + " picked=none tried=" + tried.size)
        debugLog("probe_result fail_all tried=" + tried.size + " elapsed_ms=" + totalMs + " last=" + (lastError ?: "none"))
        ZlibMirrorRegistry.save(file)
        debugLog("reset_state reason=probe_failure")
        resetStateLocked()
        throw ZlibException(
            "no available mirror (tried=" + tried.size + ", " + totalMs + "ms, last: " + (lastError ?: "none") + ")",
            ZlibException.Kind.NO_MIRROR,
        )
    }

    /**
     * 单候选探测：成功/失败都写注册表并打 mirror_probe / mirror_pick / mirror_demote。
     *
     * B1：200 响应先分类 rank——挑战已解或正文含正式站标记 = APP，才直接采用并记成功；
     * 只提到 Z-Library 的推广/导航页 = PAGE、其它 200 页 = OTHER，只记 rank/延迟并继续探，
     * 不采用（避免把推广页当搜索线路；整轮无正式站时由 UI 走 NO_MIRROR banner）。
     */
    private fun probeCandidate(
        candidate: String,
        tierLabel: String,
        source: String,
        maxAttempts: Int,
        deadlineNanos: Long,
        now: Long,
    ): ProbeOutcome {
        val host = hostOf(candidate)
        val started = System.nanoTime()
        var lastThrown: Exception? = null
        for (attempt in 1..maxAttempts) {
            if (remainingMs(deadlineNanos) < MIN_REQUEST_MS) break
            val attemptStarted = System.nanoTime()
            try {
                val res = getWithChallenge(URI(candidate + "/s/test"), deadlineNanos = deadlineNanos)
                val elapsed = (System.nanoTime() - started) / 1_000_000L
                if (res.status == 200 && !looksLikeChallenge(res.body)) {
                    // T37/task-33：zlib.ch / zlib.re 对任意深链 301 到 z-lib.by 首页（path 被丢弃），
                    // 首页也可能含 z-bookcard 等标记，只看最终 200 会把它们误判为健康线路。
                    // 探测 URL 是 /s/test，最终却落在站点根路径 → 判定为首页网关：记失败并降权。
                    val finalPath = res.uri.path.orEmpty()
                    if (finalPath.isEmpty() || finalPath == "/") {
                        lastProbeError = host + " -> redirect home"
                        val entry = ZlibMirrorRegistry.recordFailure(candidate, "redirect_home", now)
                        debugLog(
                            "mirror_probe host=" + host + " kind=gateway code=200 rank=home final=/" +
                                " ms=" + elapsed,
                        )
                        debugLog(
                            "mirror_demote host=" + host + " tier=" + tierLabel + " reason=redirect_home until=" +
                                (entry?.demoteUntil ?: 0L) + " fail_count=" + (entry?.failCount ?: 1),
                        )
                        // P3：首页网关是确定性非正式站；清 pin 免得每轮 preferred 阶段白耗预算。
                        return ProbeOutcome(evidenceReason = "probe_gateway")
                    }
                    val kind = if (res.challenged) "pow" else "http"
                    val rank = probeRankOf(res)
                    val finalRoot = mirrorRootOf(res.uri)
                    val redirected = finalRoot != null && finalRoot != candidate.trimEnd('/')
                    debugLog(
                        "mirror_probe host=" + host + " kind=" + kind + " code=200 rank=" + rank.label +
                            " ms=" + elapsed,
                    )
                    if (rank != ZlibMirrorRegistry.Rank.APP) {
                        ZlibMirrorRegistry.recordProbeResult(candidate, rank, elapsed, now)
                        // P2-2：软页不再一律当「非正式站」证据——合法空结果页 / SPA 壳 / 维护页是常见
                        // 但可能随时恢复的形态，只有登录页 / 明确错误页才清 pin；其余保留等下次重探。
                        val evidence = softPageEvidence(res.body)
                        debugLog(
                            "mirror_probe_soft host=" + host + " rank=" + rank.label + " ms=" + elapsed +
                                " unpin=" + (evidence ?: "none"),
                        )
                        return ProbeOutcome(
                            soft = SoftPick(candidate.trimEnd('/'), tierLabel, source, rank, elapsed),
                            evidenceReason = evidence,
                        )
                    }
                    ZlibMirrorRegistry.recordSuccess(candidate, finalRoot, elapsed, now, rank)
                    // P2-4：跨 host 跳转（如网关 301 到正式站）时采用最终落点，后续请求省一跳；
                    // 跳转后正文已按 final body 判为 APP，落点就是真实正式站。
                    val adoptedUrl = if (redirected && finalRoot != null) finalRoot else candidate.trimEnd('/')
                    debugLog(
                        "mirror_pick host=" + host + " tier=" + tierLabel + " source=" + source +
                            " ms=" + elapsed + " latency_ms=" + elapsed + " rank=" + rank.label +
                            " final=" + (if (redirected) 1 else 0) +
                            " adopt=" + (if (adoptedUrl != candidate.trimEnd('/')) "final" else "self"),
                    )
                    return ProbeOutcome(picked = adoptedUrl)
                }
                if (isDiamWallBlock(res)) {
                    lastProbeError = host + " -> diamwall (" + res.status + ")"
                    val entry = ZlibMirrorRegistry.recordFailure(candidate, "diamwall", now)
                    debugLog("mirror_probe host=" + host + " kind=diamwall code=" + res.status + " ms=" + elapsed)
                    debugLog(
                        "mirror_demote host=" + host + " tier=" + tierLabel + " reason=diamwall until=" + (entry?.demoteUntil ?: 0L) +
                            " fail_count=" + (entry?.failCount ?: 1),
                    )
                    return ProbeOutcome()
                }
                lastProbeError = host + " -> HTTP " + res.status
                val reason = res.status.toString()
                val entry = ZlibMirrorRegistry.recordFailure(candidate, reason, now)
                debugLog("mirror_probe host=" + host + " kind=http code=" + res.status + " ms=" + elapsed)
                debugLog(
                    "mirror_demote host=" + host + " tier=" + tierLabel + " reason=" + reason +
                        " until=" + (entry?.demoteUntil ?: 0L) + " fail_count=" + (entry?.failCount ?: 1),
                )
                // P2-6/P3：4xx 是线路对这本书/站点的确定性拒绝（清 pin）；5xx 可能只是服务端抖动（保留 pin）；
                // 408 超时 / 429 限流是瞬时状态，只记失败次数，不当成「明确非正式站」证据。
                return ProbeOutcome(
                    evidenceReason = if (res.status in 400..499 && !isTransientHttpStatus(res.status)) {
                        "probe_non_app"
                    } else {
                        null
                    },
                )
            } catch (e: Exception) {
                lastThrown = e
                val root = rootCauseOf(e)
                val elapsed = (System.nanoTime() - attemptStarted) / 1_000_000L
                debugLog(
                    "mirror_probe host=" + host + " kind=" + probeKindOf(e) + " code=" + statusOf(e) +
                        " ms=" + elapsed + " err=" + errText(root) + " phase=" + phaseOf(root),
                )
                if (attempt < maxAttempts) {
                    debugLog(
                        "mirror=" + host + " stage=retry attempt=" + attempt + "/" + maxAttempts +
                            " remaining_ms=" + remainingMs(deadlineNanos),
                    )
                }
            }
        }
        val totalMs = (System.nanoTime() - started) / 1_000_000L
        val reason = probeReasonOf(lastThrown)
        val entry = ZlibMirrorRegistry.recordFailure(candidate, reason, now)
        lastProbeError = host + " -> " + (lastThrown?.let { errText(rootCauseOf(it)) } ?: "budget_exhausted")
        debugLog(
            "mirror_demote host=" + host + " tier=" + tierLabel + " reason=" + reason +
                " until=" + (entry?.demoteUntil ?: 0L) + " fail_count=" + (entry?.failCount ?: 1) +
                " ms=" + totalMs,
        )
        return ProbeOutcome()
    }

    /**
     * P2-6：探测命中后的写回。探测开始时记录了 [epoch]；若期间用户切换/清缓存（纪元变化），
     * 丢弃旧结果，避免后台探测把用户刚选的线路覆盖掉。返回是否写回成功。
     */
    private fun adoptMirror(candidate: String, epoch: Long): Boolean {
        val chosen = candidate.trimEnd('/')
        val adopted = synchronized(mirrorLock) {
            if (epoch != mirrorEpoch.get()) {
                false
            } else {
                mirror = chosen
                prefs?.edit()?.putString(KEY_MIRROR, chosen)?.apply()
                true
            }
        }
        if (!adopted) {
            debugLog("mirror_epoch_drop stage=probe host=" + hostOf(chosen))
            return false
        }
        debugLog("probe_result ok=" + hostOf(chosen))
        maybeRefreshMirrorListInBackground()
        return true
    }

    private fun attemptsFor(candidate: String): Int =
        if (ZlibMirrorRegistry.isOfficial(candidate)) MAX_MIRROR_ATTEMPTS else 1

    /**
     * 降权未到期的线路不再探测（规格 §7.5）；若整档都在降权期则退回全量，
     * 避免「全被降权 = 永远 NO_MIRROR」的死锁（最后一搏仍按原顺序）。
     */
    private fun probeable(entries: List<ZlibMirrorRegistry.Entry>, now: Long): List<ZlibMirrorRegistry.Entry> {
        val usable = entries.filterNot { it.isDemoted(now) }
        return if (usable.isEmpty()) entries else usable
    }

    private fun probeKindOf(e: Exception): String = when {
        e is ZlibException && e.kind == ZlibException.Kind.CHALLENGE_FAILED -> "challenge"
        e is ZlibException && e.kind == ZlibException.Kind.HTTP -> "http"
        else -> "network"
    }

    private fun probeReasonOf(e: Exception?): String = when {
        e == null -> "budget"
        e is ZlibException && e.kind == ZlibException.Kind.CHALLENGE_FAILED -> "challenge_fail"
        e is ZlibException && e.kind == ZlibException.Kind.HTTP ->
            statusOf(e).takeIf { it != "-" } ?: "http"
        e is java.net.SocketTimeoutException -> "timeout"
        else -> "network"
    }

    /**
     * B1 探测排名分类（对齐桌面 LooksLikeZlibApp / LooksLikeZlibContent）：
     * 正文含正式站特征 -> APP；只提到 Z-Library -> PAGE；其余 -> OTHER。
     * P2-4：挑战已解（res.challenged）不再直接判 APP——挑战解完也可能落到登录/错误/空壳页，
     * 必须按最终正文重新分类，避免把「解了挑战但网站不可用」的线路当正式站采用。
     */
    private fun probeRankOf(res: HttpResult): ZlibMirrorRegistry.Rank {
        if (APP_MARKERS.any { res.body.contains(it, ignoreCase = true) }) return ZlibMirrorRegistry.Rank.APP
        if (PAGE_MARKERS.any { res.body.contains(it, ignoreCase = true) }) return ZlibMirrorRegistry.Rank.PAGE
        return ZlibMirrorRegistry.Rank.OTHER
    }

    /**
     * P2-2：200 非 APP 页面的「非正式站」证据分级。
     * - 登录页 / 明确错误页（404、403、Access denied）→ 确定性失效证据，可清 pin；
     * - 合法空结果页 / SPA 壳 / 维护页（不匹配任何标记）→ 证据不足，只降权不清 pin。
     * Cloudflare 的 "just a moment" 拦截页属于临时反爬，不计入错误页证据。
     */
    private fun softPageEvidence(body: String): String? {
        if (LOGIN_PAGE_MARKERS.any { body.contains(it, ignoreCase = true) }) return "probe_login_page"
        if (ERROR_PAGE_MARKERS.any { body.contains(it, ignoreCase = true) }) return "probe_error_page"
        return null
    }

    /** 本轮软候选择优：rank 数值小者优先，同 rank 比延迟。 */
    private fun betterSoftPick(current: SoftPick?, candidate: SoftPick?): SoftPick? {
        if (candidate == null) return current
        if (current == null) return candidate
        return if (candidate.rank.value < current.rank.value ||
            (candidate.rank.value == current.rank.value && candidate.latencyMs < current.latencyMs)
        ) {
            candidate
        } else {
            current
        }
    }

    /**
     * B7 第 0 阶段候选（去重、最多 [PREFERRED_MAX_MIRRORS] 条）：
     * pin（用户手动切换）-> 注册表 12h 内最近成功 -> 上次选中（内存/prefs，可能已过期）。
     */
    private fun preferredMirrors(now: Long): List<Pair<String, String>> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<Pair<String, String>>(PREFERRED_MAX_MIRRORS)
        fun add(raw: String?, source: String) {
            val url = normalizeMirror(raw)
            if (url.isEmpty() || !seen.add(url)) return
            if (out.size < PREFERRED_MAX_MIRRORS) out.add(url to source)
        }
        add(pinned(), "pin")
        // T10 P2-5：lastWorking 不看 rank，推广/导航页可能因旧 lastOkAt 排到真站前；客户端只认 APP。
        add(
            ZlibMirrorRegistry.lastWorking(now)
                ?.takeIf { it.rank == ZlibMirrorRegistry.Rank.APP }
                ?.url,
            "last_working",
        )
        add(mirror ?: prefs?.getString(KEY_MIRROR, null), "cached")
        return out
    }

    /** 最终响应 host -> `scheme://host[:port]`（成功 finalUrl 记录用，后续直连省跳转）。 */
    private fun mirrorRootOf(uri: URI): String? {
        val host = uri.host?.lowercase(Locale.US) ?: return null
        val scheme = uri.scheme ?: "https"
        val port = if (uri.port > 0) ":" + uri.port else ""
        return scheme + "://" + host + port
    }

    /** T1：按注册表的导航缓存/退避决定是否同步抓 wwwnav，并解析并入状态。 */
    private fun prepareDynamicPhase(globalDeadlineNanos: Long, now: Long, lastError: String?) {
        val hasDynamic = ZlibMirrorRegistry.dynamicCandidates(now).isNotEmpty()
        val shouldRefresh = ZlibMirrorRegistry.shouldRefreshNav(now)
        if (!hasDynamic && !shouldRefresh) return
        if (!shouldRefresh) {
            debugLog("mirror_nav reuse count=" + ZlibMirrorRegistry.dynamicCandidates(now).size)
            return
        }
        val budget = minOf(NAV_FETCH_BUDGET_MS, remainingMs(globalDeadlineNanos) - MIN_REQUEST_MS)
        if (budget < MIN_REQUEST_MS) {
            debugLog("mirror_nav skip reason=budget remaining_ms=" + remainingMs(globalDeadlineNanos))
            return
        }
        val navStarted = System.nanoTime()
        val html = try {
            fetchNavHtml(budget)
        } catch (e: Exception) {
            null
        }
        val elapsed = (System.nanoTime() - navStarted) / 1_000_000L
        if (html.isNullOrBlank()) {
            ZlibMirrorRegistry.recordNavFailure(now, ZlibMirrorList.NAV_URL, lastError ?: "empty")
            debugLog(
                "mirror_nav_fail url=" + ZlibMirrorList.NAV_URL + " reason=fetch ms=" + elapsed +
                    " reused=" + ZlibMirrorRegistry.dynamicCandidates(now).size,
            )
            return
        }
        // T24④：2xx 不代表是导航页（软 404/广告页/改版）；判活失败按抓取失败退避，继续复用旧缓存。
        if (!ZlibMirrorList.isNavPage(html)) {
            ZlibMirrorRegistry.recordNavFailure(now, ZlibMirrorList.NAV_URL, "not_nav")
            debugLog(
                "mirror_nav_fail url=" + ZlibMirrorList.NAV_URL + " reason=not_nav ms=" + elapsed +
                    " nav_len=" + html.length +
                    " reused=" + ZlibMirrorRegistry.dynamicCandidates(now).size,
            )
            return
        }
        val parsed = ZlibMirrorList.parseNav(html)
        if (parsed.official.isEmpty() && parsed.dynamic.isEmpty()) {
            ZlibMirrorRegistry.recordNavFailure(now, ZlibMirrorList.NAV_URL, "empty")
            debugLog("mirror_nav_empty url=" + ZlibMirrorList.NAV_URL + " reason=parse ms=" + elapsed)
            return
        }
        ZlibMirrorRegistry.recordNavSuccess(now, ZlibMirrorList.NAV_URL, parsed.official, parsed.dynamic)
        debugLog(
            "mirror_nav_parse url=" + ZlibMirrorList.NAV_URL + " ok=1 official=" + parsed.official.size +
                " dynamic=" + parsed.dynamic.size +
                " skipped=" + parsed.skipped +
                " ms=" + elapsed +
                " hosts=" + parsed.dynamic.joinToString(",") { ZlibMirrorList.hostOf(it) },
        )
    }

    private fun registryFile(): File? =
        appContext?.filesDir?.let { File(it, ZlibMirrorRegistry.FILE_NAME) }


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

    /**
     * 书籍详情（P1b 1.3）：先 /book/hash，再带 slug；仍失败时跟 canonical 链接补一次。
     *
     * T37/task-33：204 是书籍级确定性不可用（35 本抽样 4 本 204，跨 7h、同 hash 换 slug 仍 204），
     * 不是网络错误——先换一条镜像重试一次；仍 204 则明确提示「该书当前不可用（可能已下架）」。
     */
    fun fetchDetail(hash: String, slug: String? = null): ZBookDetail {
        val cleanHash = hash.trim().trim('/')
        require(cleanHash.isNotEmpty()) { "hash must not be blank" }
        ensureInitialized()
        var currentBase = mirror ?: probeMirrors()
        var lastStatus = 0
        var lastBody: String? = null
        val tried = ArrayList<String>()
        var retriedOnOtherMirror = false
        while (true) {
            val retryAttempt = retriedOnOtherMirror
            try {
                val candidates = ArrayList<String>()
                candidates.add(currentBase + "/book/" + cleanHash)
                if (!slug.isNullOrBlank()) {
                    candidates.add(currentBase + "/book/" + cleanHash + "/" + normalizeSlug(slug))
                }
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
                        val canonicalUrl = absoluteSiteUrl(currentBase, canonical)
                        if (canonicalUrl !in tried) {
                            tried.add(canonicalUrl)
                            val follow = getWithChallenge(URI(canonicalUrl))
                            // T31：canonical 跟随后也要更新「最后一次响应」，否则 204/空 body 的诊断会误报前一次的 200。
                            lastStatus = follow.status
                            lastBody = follow.body
                            if (follow.status == 200 && looksLikeDetailPage(follow.body)) {
                                return ZlibDetailParser.parse(follow.body, follow.uri.toString(), cleanHash)
                            }
                        }
                    }
                }
            } catch (e: ZlibException) {
                // 204 后的换线重试如果自己失败（网络/HTTP），保留原 204 的「书籍不可用」语义，不再上抛。
                if (!retryAttempt) throw e
                debugLog("detail_204_retry_fail host=" + hostOf(currentBase) + " err=" + e.kind.name)
            } catch (e: Exception) {
                if (!retryAttempt) throw e
                debugLog("detail_204_retry_fail host=" + hostOf(currentBase) + " err=" + e.javaClass.simpleName)
            }
            // T37/task-33：204 先换一条镜像重试一次（同 hash 换 slug 仍 204，说明是书的问题不是线路问题）。
            if (lastStatus == 204 && !retriedOnOtherMirror) {
                val alt = alternateMirrorForDetail(currentBase)
                if (alt != null && normalizeMirror(alt) != normalizeMirror(currentBase)) {
                    retriedOnOtherMirror = true
                    debugLog(
                        "detail_204_retry from=" + hostOf(currentBase) + " to=" + hostOf(alt) +
                            " hash=" + cleanHash,
                    )
                    currentBase = alt
                    continue
                }
            }
            break
        }
        // T31（realnet task-26）+ T37（realnet task-33）：204 是书籍级确定性不可用（已下架/无文件），
        // UI 应给可读提示，不按网络错误重试；2xx 但 body 为空仍归 PARSE。
        val unavailable = lastStatus == 204
        val emptyResponse = lastStatus in 200..299 && lastBody.isNullOrBlank()
        if (unavailable) {
            debugLog("detail_unavailable hash=" + cleanHash + " status=204 tried=" + tried.size)
        } else if (emptyResponse) {
            debugLog("detail_empty hash=" + cleanHash + " status=" + lastStatus + " body_len=0")
        } else {
            debugLog("detail_fail hash=" + cleanHash + " status=" + lastStatus + " body_len=" + (lastBody?.length ?: 0))
        }
        throw ZlibException(
            when {
                unavailable -> ZlibErrorMessages.BOOK_UNAVAILABLE
                emptyResponse -> "详情页返回空响应（HTTP " + lastStatus + "），请切换线路或稍后重试"
                else -> "detail page unavailable (hash=" + cleanHash + ", status=" + lastStatus + ", tried=" + tried.size + ")"
            },
            when {
                // T37：4xx/5xx 都是服务端明确返回的 HTTP 错误 → 统一 HTTP kind + 真实状态码；
                // 不要归 NETWORK（UI 会显示「网络连接失败」）或 PARSE（会显示「页面格式无法识别」）。
                lastStatus == 204 || lastStatus in 400..599 -> ZlibException.Kind.HTTP
                else -> ZlibException.Kind.PARSE
            },
            httpCode = lastStatus.takeIf { it > 0 },
        )
    }

    /**
     * T37/task-33：204 后换线重试的候选（排除当前线路）。生产从注册表候选里取第一条；
     * 无注册表（JVM 单测/appContext 未初始化）返回 null，避免测试联网。
     */
    private fun alternateMirrorForDetail(exclude: String): String? {
        detailRetryMirrorForTest?.let { provider -> return provider() }
        val file = registryFile() ?: return null
        val now = System.currentTimeMillis()
        ZlibMirrorRegistry.ensureLoaded(file, now)
        val excluded = setOf(normalizeMirror(exclude))
        val candidates = ArrayList<ZlibMirrorRegistry.Entry>()
        candidates.addAll(ZlibMirrorRegistry.orderedOfficial(now, excluded))
        candidates.addAll(ZlibMirrorRegistry.dynamicCandidates(now, excluded))
        candidates.addAll(ZlibMirrorRegistry.fallbackCandidates(now, excluded))
        return candidates.firstOrNull { normalizeMirror(it.url).isNotEmpty() }?.url
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
                // T37：4xx/5xx 统一 HTTP kind，带真实状态码；只有 3xx/1xx 等异常状态才归 PARSE。
                if (res.status in 400..599) ZlibException.Kind.HTTP else ZlibException.Kind.PARSE,
                httpCode = res.status,
            )
        }
        val books = ZlibCategoryParser.parse(res.body, sitePath)
        debugLog("category path=" + hostOf(target) + sitePath + " status=" + res.status + " cards=" + books.size)
        return books
    }

    /** 绝对 URL / 协议相对 URL 只保留 path+query，避免 [fetchCategory] 被传入外部主机（D10）。 */
    private fun sitePathOf(raw: String): String = ZlibUrlPolicy.sitePathOf(raw)
    // ------------------------------------------------------------ P1.5 发现面

    /** P1.5：书单列表 /booklists?page=N（第 1 页省略 page 参数）。 */
    @Throws(ZlibException::class)
    fun fetchBooklists(page: Int = 1): ZBooklistPage {
        val path = if (page <= 1) "/booklists" else "/booklists?page=" + page
        return ZlibBooklistParser.parsePage(fetchDiscoverPage(path, "booklists"))
    }

    /** P1.5：书单详情；href 必须是站内 /booklist/ 路径（绝对 URL 只取 path，防 SSRF）。 */
    @Throws(ZlibException::class)
    fun fetchBooklist(href: String): ZBooklistDetail {
        val clean = href.trim()
        val path = sitePathOf(clean)
        if (!path.startsWith("/booklist/")) {
            throw ZlibException("not a booklist href: " + clean, ZlibException.Kind.PARSE)
        }
        return ZlibBooklistDetailParser.parse(fetchDiscoverPage(path, "booklist"))
    }

    /** [fetchBooklist] 的别名（规格里的 fetchBooklistDetail）。 */
    @Throws(ZlibException::class)
    fun fetchBooklistDetail(path: String): ZBooklistDetail = fetchBooklist(path)

    /** P1.5：榜单 /popular?page=N；空页返回空列表（调用方据此停止翻页）。 */
    @Throws(ZlibException::class)
    fun fetchPopular(page: Int = 1): List<ZPopularItem> {
        val path = if (page <= 1) "/popular" else "/popular?page=" + page
        return ZlibPopularParser.parsePage(fetchDiscoverPage(path, "popular"))
    }

    /**
     * P1.5：分类总览 /categories（39 组 / 802 子类，约 343KB）。
     * 文件缓存 24h；刷新失败时回退旧缓存（UI 仍可浏览），没有缓存才抛错。
     */
    @Throws(ZlibException::class)
    fun fetchCategories(forceRefresh: Boolean = false): List<ZCategoryGroup> {
        val cache = categoriesCacheFile()
        if (!forceRefresh && cache != null) {
            val cached = readCategoriesCache(cache, CATEGORIES_CACHE_TTL_MS, strictAge = true)
            if (cached != null) {
                debugLog("categories_cache hit age_ms=" + (System.currentTimeMillis() - cache.lastModified()))
                return cached
            }
        }
        return try {
            val html = fetchDiscoverPage("/categories", "categories")
            val groups = ZlibCategoriesParser.parse(html)
            cache?.let { file -> runCatching { file.writeText(html) } }
            groups
        } catch (e: ZlibException) {
            val stale = cache?.let { readCategoriesCache(it, 0L, strictAge = false) }
            if (stale != null) {
                debugLog("categories_cache stale_fallback")
                stale
            } else {
                throw e
            }
        }
    }

    /** P1.5：分类书籍列表 /category/<id>/<Slug>?page=N（50 本/页，pagesTotal 来自 pagerOptions）。 */
    @Throws(ZlibException::class)
    fun fetchCategoryPage(id: String, slug: String, page: Int = 1): ZCategoryPage {
        val cleanId = id.trim()
        if (cleanId.isEmpty() || !cleanId.all { it.isDigit() }) {
            throw ZlibException("bad category id: " + cleanId, ZlibException.Kind.PARSE)
        }
        val cleanSlug = slug.trim().trim('/')
        if (cleanSlug.contains('/') || cleanSlug.contains('?') || cleanSlug.contains('#')) {
            throw ZlibException("bad category slug: " + cleanSlug, ZlibException.Kind.PARSE)
        }
        val base = "/category/" + cleanId + (if (cleanSlug.isEmpty()) "" else "/" + cleanSlug)
        val path = if (page <= 1) base else base + "?page=" + page
        return ZlibCategoryPageParser.parse(fetchDiscoverPage(path, "category"), page)
    }

    /** 发现面统一 GET：站内 path → 绝对 URL → 现有挑战/镜像/代理/cookie 链路。 */
    private fun fetchDiscoverPage(path: String, what: String): String {
        ensureInitialized()
        val base = mirror ?: probeMirrors()
        val target = absoluteSiteUrl(base, sitePathOf(path))
        val res = getWithChallenge(URI(target))
        if (res.status !in 200..299) {
            debugLog(what + "_fail path=" + sitePathOf(path) + " status=" + res.status + " body_len=" + res.body.length)
            throw ZlibException(
                what + " page unavailable (status=" + res.status + ")",
                // T37：4xx/5xx 统一 HTTP kind，带真实状态码；只有 3xx/1xx 等异常状态才归 PARSE。
                // 之前 5xx 归 NETWORK，真机 500 会显示「网络连接失败…错误类型：NETWORK」。
                if (res.status in 400..599) ZlibException.Kind.HTTP else ZlibException.Kind.PARSE,
                httpCode = res.status,
            )
        }
        debugLog(what + "_ok path=" + sitePathOf(path) + " len=" + res.body.length)
        return res.body
    }

    private fun categoriesCacheFile(): File? =
        appContext?.filesDir?.let { File(it, CATEGORIES_CACHE_FILE) }

    private fun readCategoriesCache(
        file: File,
        maxAgeMs: Long,
        strictAge: Boolean,
    ): List<ZCategoryGroup>? {
        if (!file.isFile) return null
        val age = System.currentTimeMillis() - file.lastModified()
        if (strictAge && (age < 0 || age > maxAgeMs)) return null
        return runCatching { ZlibCategoriesParser.parse(file.readText()) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

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
                            throw ZlibException(
                                "HTTP " + status + " for " + current,
                                ZlibException.Kind.HTTP,
                                httpCode = status,
                            )
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
        val current = normalizeMirror(mirror ?: pinned() ?: prefs?.getString(KEY_MIRROR, null))
        val index = available.indexOfFirst { normalizeMirror(it) == current } + 1
        return ZMirrorInfo(
            index = if (current.isNotEmpty() && index > 0) index else 0,
            total = available.size,
            mirrors = available,
        )
    }

    /**
     * 线路弹层单快照（B6/T10 P2-6）：一次 [ZlibMirrorRegistry.orderedEntries] 同时拿到行序、host、
     * meta、current/pinned；弹层渲染和切换都用同一行的 [ZMirrorItem.url]，避免 mirrorInfo/mirrorItems
     * 两次快照之间被后台导航刷新/探测写入改序导致「点 A 切 B」。
     * 只读内存（注册表首次加载可能读一次文件），零网络；UI 线程可安全调用。
     */
    fun mirrorRows(): List<ZMirrorItem> {
        val now = System.currentTimeMillis()
        ZlibMirrorRegistry.ensureLoaded(registryFile(), now)
        val current = normalizeMirror(mirror ?: pinned() ?: prefs?.getString(KEY_MIRROR, null))
        val pinned = normalizeMirror(pinned())
        return ZlibMirrorRegistry.orderedEntries(now).map { entry ->
            val url = normalizeMirror(entry.url)
            ZMirrorItem(
                url = entry.url,
                host = hostOf(entry.url),
                tier = entry.tier.label,
                rank = entry.rank.value,
                latencyMs = entry.latencyMs,
                lastOkAt = entry.lastOkAt,
                // T10 P2-5：展示层「上次成功」只认正式站，软页的 stale lastOkAt 不算。
                successFresh = entry.rank == ZlibMirrorRegistry.Rank.APP && entry.isFreshOk(now),
                current = current.isNotEmpty() && url == current,
                pinned = pinned.isNotEmpty() && url == pinned,
                demoted = entry.isDemoted(now),
            )
        }
    }

    /** 兼容旧名：与 [mirrorRows] 同一实现（弹层渲染请直接用 mirrorRows）。 */
    fun mirrorItems(): List<ZMirrorItem> = mirrorRows()

    /**
     * 切换线路（P1b 4.7 / B7，T10 P2-6）：按 URL 切（弹层用这个）。URL 不在当前名单里返回 false；
     * 写 prefs mirror、记为 pin 并清探测/代理临时态。按 URL 而不是下标，后台刷新重排也不会切错线。
     */
    fun switchMirror(url: String): Boolean {
        val target = normalizeMirror(url)
        if (target.isEmpty()) return false
        val available = mergedMirrors()
        val resolved = available.firstOrNull { normalizeMirror(it) == target } ?: return false
        return applyMirrorSwitch(resolved, "source=url")
    }

    /** 兼容旧调用：按下标切换（同一次快照内取 URL；新代码请用 [switchMirror] 的 URL 重载）。 */
    fun switchMirror(index: Int): Boolean {
        val available = mergedMirrors()
        if (index !in available.indices) return false
        return applyMirrorSwitch(available[index], "index=" + (index + 1))
    }

    /** 越界/URL 不存在时调用方已返回 false；此处只做写入 + 清状态。 */
    private fun applyMirrorSwitch(target: String, source: String): Boolean {
        // P2-6：先推进纪元（写锁内），后台探测/搜索的旧结果会据此丢弃；pin 用 commit 同步落盘。
        val epoch = synchronized(mirrorLock) {
            val next = mirrorEpoch.incrementAndGet()
            mirror = target
            setPinnedMirror(target, commit = true)
            prefs?.edit()?.putString(KEY_MIRROR, target)?.apply()
            next
        }
        resetStateLocked()
        debugLog("switch_mirror $source mirror=" + hostOf(target) + " pinned=1 epoch=" + epoch)
        return true
    }

    /**
     * 清掉缓存线路（P1c 收尾③）：prefs `KEY_MIRROR` + 内存 [mirror]。
     *
     * debug 下换 mock 服务 / 换镜像后必须调用，否则上一次的地址会「粘」在缓存里，
     * 后续真实流程仍然打到 mock 或已失效的镜像。不动 cookie / 代理 / 动态名单缓存。
     * P3：与用户 pin 解耦——清缓存不等于取消用户选择，pin 保留；要清 pin 用 [clearPinnedMirror]。
     */
    fun clearCachedMirror() {
        // P2-6：清缓存同样推进纪元，进行中的探测/搜索不得把旧线路写回来。
        synchronized(mirrorLock) {
            mirrorEpoch.incrementAndGet()
            mirror = null
            prefs?.edit()?.remove(KEY_MIRROR)?.apply()
        }
        debugLog("clear_mirror prefs=removed memory=null pin=kept")
    }

    /**
     * 显式取消用户 pin（P3：与清缓存拆开）。只清 pin，不动当前缓存线路与 cookie/代理状态。
     * 不推进纪元：在途探测/搜索仍可把可用线路写进缓存，但不会再产生 pin。
     */
    fun clearPinnedMirror() {
        // 用户主动操作同样同步落盘（与 switchMirror 的 commit 一致）：清完立刻杀进程也不复活 pin。
        setPinnedMirror(null, commit = true)
        debugLog("clear_pin done")
    }

    // ---------------------------------------------------------------- P1c: 动态镜像名单

    /**
     * 静态名单 + 动态发现 + 已探测成功的动态主机（首次调用加载注册表文件，之后纯内存）。
     * task-75：跨 host 301 adopt 的 finalRoot 可能不在注册表（注册表只改 candidate 的 finalUrl 字段），
     * 但它才是当前直连使用的线路；把它并入名单末尾（按 normalize 去重、不动既有条目的 rank/官方元数据），
     * 保证 mirrorInfo() 能标出 current、switchMirror(currentUrl) 不会误判「不在名单」而返回 false。
     */
    private fun mergedMirrors(): List<String> {
        val now = System.currentTimeMillis()
        ZlibMirrorRegistry.ensureLoaded(registryFile(), now)
        val listed = ZlibMirrorRegistry.orderedUrls(now)
        val current = mirror ?: pinned() ?: prefs?.getString(KEY_MIRROR, null)
        val currentNorm = normalizeMirror(current)
        if (currentNorm.isEmpty() || listed.any { normalizeMirror(it) == currentNorm }) return listed
        val direct = current?.trim()?.trimEnd('/') ?: return listed
        if (direct.isEmpty()) return listed
        debugLog("mirror_list append_current host=" + hostOf(direct) + " registry=" + listed.size)
        return listed + direct
    }

    /**
     * 导航缓存过期/失败退避到期时在后台线程抓一次 wwwnav（单飞，不阻塞镜像探测）。
     * 成功时「官网地址」补 T0 尾部、「镜像入口」进 T1；解析为空视为失败，失败记退避（30min 起、上限 2h）。
     */
    private fun maybeRefreshMirrorListInBackground() {
        val now = System.currentTimeMillis()
        if (!ZlibMirrorRegistry.shouldRefreshNav(now)) return
        if (!navRefreshRunning.compareAndSet(false, true)) return
        Thread {
            try {
                val html = try {
                    fetchNavHtml()
                } catch (e: Exception) {
                    null
                }
                val nowMs = System.currentTimeMillis()
                if (html.isNullOrBlank()) {
                    ZlibMirrorRegistry.recordNavFailure(nowMs, ZlibMirrorList.NAV_URL, "empty")
                    debugLog("mirror_list nav_len=0 discovered=0 refresh=nok")
                } else if (!ZlibMirrorList.isNavPage(html)) {
                    ZlibMirrorRegistry.recordNavFailure(nowMs, ZlibMirrorList.NAV_URL, "not_nav")
                    debugLog(
                        "mirror_nav_fail url=" + ZlibMirrorList.NAV_URL + " reason=not_nav" +
                            " nav_len=" + html.length + " refresh=nok",
                    )
                } else {
                    val parsed = ZlibMirrorList.parseNav(html)
                    if (parsed.official.isEmpty() && parsed.dynamic.isEmpty()) {
                        ZlibMirrorRegistry.recordNavFailure(nowMs, ZlibMirrorList.NAV_URL, "empty")
                        debugLog("mirror_nav_empty url=" + ZlibMirrorList.NAV_URL + " reason=parse nav_len=" + html.length)
                    } else {
                        ZlibMirrorRegistry.recordNavSuccess(nowMs, ZlibMirrorList.NAV_URL, parsed.official, parsed.dynamic)
                        debugLog(
                            "mirror_list nav_len=" + html.length + " official=" + parsed.official.size +
                                " dynamic=" + parsed.dynamic.size + " skipped=" + parsed.skipped +
                                " hosts=" + parsed.dynamic.joinToString(",") { ZlibMirrorList.hostOf(it) },
                        )
                    }
                }
                ZlibMirrorRegistry.save(registryFile())
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

    /** GET 导航页（绝对 URL，非镜像相对路径）；整轮 <= [maxBudgetMs]，失败返回 null。 */
    private fun fetchNavHtml(maxBudgetMs: Long = NAV_FETCH_BUDGET_MS): String? {
        navFetcherForTest?.let { fetcher -> return fetcher() }
        val deadline = System.nanoTime() + maxBudgetMs * 1_000_000L
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

    // 搜索 URL 由文件末尾的 [zlibSearchUrl] 统一拼装（A5 筛选/排序参数）。

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
        var sawChallenge = false
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
            sawChallenge = true
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
        return if (sawChallenge) res.copy(challenged = true) else res
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

    /** 单测 seam：返回导航页 HTML（null = 模拟抓取失败/超时）；仅测试设置。 */
    internal var navFetcherForTest: (() -> String?)? = null

    /** T37 单测 seam：详情 204 后换线重试的候选镜像；null = 走注册表（JVM 单测无注册表时为 null）。 */
    internal var detailRetryMirrorForTest: (() -> String?)? = null

    /** 最近一次探测失败的描述（NO_MIRROR 消息用）。 */
    @Volatile
    private var lastProbeError: String? = null

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
                        throw ZlibException("HTTP 416 for " + current, ZlibException.Kind.HTTP, httpCode = 416)
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
                    throw ZlibException(
                        "HTTP " + status + " for " + current,
                        ZlibException.Kind.HTTP,
                        httpCode = status,
                    )
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
        looksLikeChallenge(res.body) ||
            // T37：只有「确实可解」的 503 才算挑战页。之前把任意 503 都当挑战，
            // 服务端真实故障 503（空 body / 普通错误页）会变成 CHALLENGE_FAILED，UI 误报「安全校验失败」。
            (res.status == 503 && ZlibChallenge.extractConstant(res.body) != null)

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

        /** P1c/B2：导航页（zlib.wwwnav.com，失败退避、仅 best-effort）后台抓取总预算。 */
        private const val NAV_FETCH_BUDGET_MS = 8_000L

        /** P1.5：/categories 文件缓存（约 343KB HTML，24h 有效）。 */
        private const val CATEGORIES_CACHE_FILE = "zlib_categories.html"
        private const val CATEGORIES_CACHE_TTL_MS = 24L * 60L * 60L * 1_000L

        /** 性能日志 tag（与 app 其他 phase 一致）。 */
        private const val PERF_TAG = "PBPERF"
        private const val TAG = "PBZlib"
        private const val CONNECT_TIMEOUT_MS = 7_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_REDIRECTS = 5
        private const val MAX_CHALLENGE_ROUNDS = 3

        /** 探测预算（R7-H 分层）：单 host 3s、T0 官方合计 5s、整轮硬上限 12s。 */
        private const val PER_MIRROR_BUDGET_MS = 3_000L
        private const val OFFICIAL_PHASE_BUDGET_MS = 5_000L
        private const val PROBE_BUDGET_MS = 12_000L
        private const val MIN_REQUEST_MS = 750L

        /** B7：记忆线路优先重探阶段（pin / 上次选中 / 最近成功）的总预算与候选数。 */
        private const val PREFERRED_PHASE_BUDGET_MS = 4_000L
        private const val PREFERRED_MAX_MIRRORS = 2

        /** B3：首包换线整轮兜底上限（额度仍是线路数，此值防单轮拖太久）。 */
        private const val SEARCH_FAILOVER_BUDGET_MS = 20_000L

        /** B1 rank 分类标记（对齐桌面 LooksLikeZlibApp / LooksLikeZlibContent）。 */
        // T10 P1-4：去掉 z-lib.fm/singlelogin/z-lib.io——这些串在推广/导航页上很常见，用来判 APP
        // 会把软页误认成正式站；只留正式站页面组件/文案特征。
        private val APP_MARKERS = listOf(
            "<z-bookcard",
            "z-access",
            "z-recommend",
            "z-library since 2009",
        )
        private val PAGE_MARKERS = listOf(
            "z-lib",
            "zlibrary",
            "z-library",
            "1lib",
            "singlelogin",
            "bookfi",
        )

        /** P2-2：200 软页里只有登录页/明确错误页是确定性失效证据；空结果/SPA/维护页保留 pin。 */
        private val LOGIN_PAGE_MARKERS = listOf(
            "type=\"password\"",
            "type='password'",
            "<title>login",
            "sign in to",
        )
        private val ERROR_PAGE_MARKERS = listOf(
            "404 not found",
            "page not found",
            "access denied",
            "403 forbidden",
        )

        /** 官方站挑战重试：含首次最多 [MAX_MIRROR_ATTEMPTS] 次。 */
        private const val MAX_MIRROR_ATTEMPTS = 3

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

/** 搜索排序（A5）：值与站点 order 参数一致；顺序即 UI 展示顺序（详见 docs/android-port/a5-search-filters.md）。 */
enum class ZlibSortOrder(val value: String, val label: String) {
    POPULAR("popular", "最受欢迎"),
    BEST_MATCH("bestmatch", "最佳匹配"),
    NEWEST("date", "最近添加"),
    TITLE_ASC("titleA", "书名 (A-Z)"),
    TITLE_DESC("title", "书名 (Z-A)"),
    YEAR("year", "按年份"),
    FILESIZE_DESC("filesize", "文件大小 ↓"),
    FILESIZE_ASC("filesizeA", "文件大小 ↑"),
}

/**
 * A5 搜索筛选（站点 /s/<q> 的 query 参数，2026-11-04 实测）。
 * 默认值 = 无筛选：此时不追加任何参数，URL 与旧行为逐字节一致。
 * - languages：站点英文小写语言名（chinese / english / ...），不是页面上的本地化显示名。
 * - extensions：大写格式名（PDF / EPUB / ...）。
 * - order：默认 POPULAR 时省略参数（等价服务端默认）。
 */
data class ZlibSearchFilters(
    val languages: List<String> = emptyList(),
    val extensions: List<String> = emptyList(),
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val order: ZlibSortOrder = ZlibSortOrder.POPULAR,
) {
    /** 是否真的改变默认查询（UI 用：入口显示「已筛选」）。 */
    val isActive: Boolean
        get() = languages.isNotEmpty() || extensions.isNotEmpty() ||
            yearFrom != null || yearTo != null || order != ZlibSortOrder.POPULAR

    /** 规范化：去空白/去空值/去重；yearFrom > yearTo 时交换，非正年份丢弃。 */
    fun sanitized(): ZlibSearchFilters {
        var from = yearFrom?.takeIf { it > 0 }
        var to = yearTo?.takeIf { it > 0 }
        if (from != null && to != null && from > to) {
            val swap = from
            from = to
            to = swap
        }
        return copy(
            languages = languages.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            extensions = extensions.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            yearFrom = from,
            yearTo = to,
        )
    }

    /** 拼 query 片段（不含 page、不含 ? 与 &）；无筛选返回空串。 */
    fun toQueryParameters(): String {
        val parts = ArrayList<String>()
        languages.forEach { parts.add("languages%5B%5D=" + encode(it)) }
        extensions.forEach { parts.add("extensions%5B%5D=" + encode(it)) }
        yearFrom?.let { parts.add("yearFrom=" + it) }
        yearTo?.let { parts.add("yearTo=" + it) }
        if (order != ZlibSortOrder.POPULAR) parts.add("order=" + order.value)
        return parts.joinToString("&")
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/**
 * 搜索 URL（A5）：无筛选时与旧实现完全一致 /s/<q>?page=N；
 * 有筛选时 /s/<q>?languages%5B%5D=..&...&page=N（参数顺序固定，page 最后）。
 * internal 便于 JVM 单测直接断言 URL。
 */
internal fun zlibSearchUrl(
    mirrorBase: String,
    query: String,
    page: Int,
    filters: ZlibSearchFilters? = null,
): String {
    val base = mirrorBase.trimEnd('/')
    val encodedQuery = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
    val active = filters?.sanitized()?.takeIf { it.isActive }
    if (active == null) return "$base/s/$encodedQuery?page=$page"
    return "$base/s/$encodedQuery?" + active.toQueryParameters() + "&page=$page"
}
