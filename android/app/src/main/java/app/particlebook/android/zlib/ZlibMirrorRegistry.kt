package app.particlebook.android.zlib

import java.io.File
import java.net.URI
import java.util.Locale

/**
 * 镜像分层注册表（R7-H / P1c ③ 演进）。
 *
 * 分层（默认 T0 -> T1 -> T2）：
 * - T0 官方域名族：research 2026-11-03 实测「PoW 可解」的官方站（[OFFICIAL_SEEDS]），按表序优先；
 * - T1 动态导航源：zlib.wwwnav.com 解析出的镜像入口/官网地址（[recordNavSuccess]）；
 * - T2 内置兜底：旧静态名单去掉 DiamWall 项（[FALLBACK_SEEDS]），前两层都不可用时继续探。
 *
 * 排序（对齐 spec §2）：未降权在前 -> 12h 内成功过的在前 -> priority -> latency -> fail_count；
 * 降权条目按 demote_until 升序排在同层末尾。DiamWall 站（需执行 JS，原生不可用）不进候选，
 * 只有探测命中时才 demote（[recordFailure]）。
 *
 * 持久化：filesDir/zlib_mirrors.json（version/updated_at/nav/entries），先写 .tmp 再 rename；
 * 文件损坏/格式未知时静默回退种子，绝不让镜像缓存阻塞网络层。
 *
 * 本对象不联网、不做 Android IO（File 由调用方传入），可直接在 JVM 单测里跑。
 */
internal object ZlibMirrorRegistry {

    const val FILE_NAME: String = "zlib_mirrors.json"
    private const val SCHEMA_VERSION = 1

    /** 成功缓存：12h 内视为「已知可用」，排序靠前（不阻止重探）。 */
    const val SUCCESS_TTL_MS: Long = 12L * 60L * 60L * 1000L

    /** 导航页成功缓存：6h 后重抓。 */
    const val NAV_TTL_MS: Long = 6L * 60L * 60L * 1000L

    /** 导航页失败退避：30min 起步，指数翻倍，上限 2h。 */
    const val NAV_FAIL_BASE_MS: Long = 30L * 60L * 1000L
    const val NAV_FAIL_MAX_MS: Long = 2L * 60L * 60L * 1000L

    /** 单 host 失败降权：10min 起步，指数翻倍，上限 2h。 */
    const val DEMOTE_BASE_MS: Long = 10L * 60L * 1000L
    const val DEMOTE_MAX_MS: Long = 2L * 60L * 60L * 1000L

    /** 动态项淘汰：超过 24h 未在导航页出现则删除。 */
    const val DYNAMIC_PRUNE_MS: Long = 24L * 60L * 60L * 1000L

    const val NAV_OFFICIAL_PRIORITY = 500
    const val DYNAMIC_PRIORITY = 1000
    const val FALLBACK_PRIORITY = 2000

    enum class Tier(val label: String) {
        OFFICIAL("official"),
        DYNAMIC("dynamic"),
        FALLBACK("fallback"),
    }

    data class Entry(
        val url: String,
        val tier: Tier,
        val source: String,
        val priority: Int,
        val lastOkAt: Long = 0L,
        val lastFailAt: Long = 0L,
        val failCount: Int = 0,
        val lastProbeAt: Long = 0L,
        val latencyMs: Long = -1L,
        val finalUrl: String? = null,
        val demoteUntil: Long = 0L,
        val demoteReason: String? = null,
        val seenAt: Long = 0L,
    ) {
        val host: String get() = hostOf(url)

        fun isDemoted(now: Long): Boolean = demoteUntil > now

        fun isFreshOk(now: Long): Boolean = lastOkAt > 0L && now - lastOkAt < SUCCESS_TTL_MS
    }

    data class NavState(
        val url: String = "",
        val fetchedAt: Long = 0L,
        val ok: Boolean = false,
        val error: String? = null,
        val failCount: Int = 0,
        val official: List<String> = emptyList(),
        val dynamic: List<String> = emptyList(),
    ) {
        fun isFresh(now: Long): Boolean = ok && fetchedAt > 0L && now - fetchedAt < NAV_TTL_MS
    }

    data class State(
        val entries: List<Entry> = emptyList(),
        val nav: NavState = NavState(),
        val updatedAt: Long = 0L,
    )

    /** T0 官方族（research 2026-11-03 实测；顺序即优先级）。 */
    val OFFICIAL_SEEDS: List<String> = listOf(
        "https://z-lib.by",
        "https://zlib.re",
        "https://zlib.ch",
        "https://zh.z-lib.gd",
        "https://z-lib.gd",
        "https://zh.zlib0.ru",
        "https://zh.dfj101.ru",
        "https://zh.101fbiwarning.ru",
        "https://zh.zzz101.ru",
        "https://zh.singlelogin.rs",
    )

    /**
     * T2 内置兜底：旧静态名单去掉 DiamWall 项（z-library.sk / 1lib.sk 全系）。
     * 与 T0 有重叠是故意的：T0 阶段只跑 5s 预算，T2 阶段继续把没探完的官方站探完。
     */
    val FALLBACK_SEEDS: List<String> = listOf(
        "https://z-lib.by",
        "https://zlib.re",
        "https://zlib.ch",
        "https://zh.zlib0.ru",
        "https://zh.z-lib.gd",
        "https://zh.dfj101.ru",
        "https://zh.101fbiwarning.ru",
        "https://zh.zzz101.ru",
        "https://zh.singlelogin.rs",
    )

    /**
     * 明确排除的 DiamWall 站：307（Location 指自身 + __diamwall）-> 513/517，需执行 JS。
     * z-lib.sk 虽被 z-lib.by 自声明为 preferredDomain，实测也是 DiamWall，故一并排除。
     */
    private val DIAMWALL_HOSTS: List<String> = listOf(
        "z-library.sk",
        "1lib.sk",
        "z-lib.sk",
        "z-lib.fm",
    )

    @Volatile
    private var officialSeeds: List<String> = OFFICIAL_SEEDS

    @Volatile
    private var fallbackSeeds: List<String> = FALLBACK_SEEDS

    @Volatile
    private var state: State = State()

    @Volatile
    private var loaded: Boolean = false

    // ------------------------------------------------------------ lifecycle

    /** 首次使用时加载磁盘状态并补齐种子；同一进程只读一次文件（调用方在 IO 线程）。 */
    @Synchronized
    fun ensureLoaded(file: File?, now: Long) {
        if (loaded) return
        val parsed = file?.takeIf { it.isFile }?.let { f ->
            runCatching { parseState(f.readText(Charsets.UTF_8)) }.getOrNull()
        }
        state = withSeeds(parsed ?: State(), now)
        loaded = true
    }

    /** 单测复位（生产代码不调用）；可覆盖种子以做本地桩测试。 */
    @Synchronized
    fun resetForTest(official: List<String>? = null, fallback: List<String>? = null) {
        officialSeeds = official ?: OFFICIAL_SEEDS
        fallbackSeeds = fallback ?: FALLBACK_SEEDS
        state = State()
        loaded = false
    }

    // ------------------------------------------------------------ ordering

    /** T0 候选（官方种子 + 导航页官网面板新增），已排除 [exclude]。 */
    @Synchronized
    fun orderedOfficial(now: Long, exclude: Set<String> = emptySet()): List<Entry> =
        order(entries().filter { it.tier == Tier.OFFICIAL }, now, exclude)

    /** T1 候选：导航页「镜像入口」面板（含与 T0 重叠的地址，由调用方按 tried 去重）。 */
    @Synchronized
    fun dynamicCandidates(now: Long, exclude: Set<String> = emptySet()): List<Entry> {
        val byUrl = LinkedHashMap<String, Entry>()
        state.nav.dynamic.forEach { raw ->
            val url = normalizeUrl(raw)
            if (url.isEmpty() || isDiamWall(url)) return@forEach
            byUrl[url] = findEntry(url) ?: Entry(url, Tier.DYNAMIC, "nav", DYNAMIC_PRIORITY)
        }
        entries().filter { it.tier == Tier.DYNAMIC }.forEach { byUrl[it.url] = it }
        return order(byUrl.values.toList(), now, exclude)
    }

    /** T2 候选：内置兜底表（去掉 DiamWall），按降权/可用性排序，调用方按 tried 去重。 */
    @Synchronized
    fun fallbackCandidates(now: Long, exclude: Set<String> = emptySet()): List<Entry> {
        val list = fallbackSeeds.mapNotNull { raw ->
            val url = normalizeUrl(raw)
            if (url.isEmpty() || isDiamWall(url)) null
            else findEntry(url) ?: Entry(url, Tier.FALLBACK, "seed", FALLBACK_PRIORITY)
        }
        return order(list, now, exclude)
    }

    /** 线路弹层/设置页用：T0 -> T1 -> T2 去重后的完整列表（只读内存，零网络零文件 IO）。 */
    @Synchronized
    fun orderedUrls(now: Long): List<String> {
        val merged = LinkedHashMap<String, Entry>()
        orderedOfficial(now).forEach { merged[it.url] = it }
        dynamicCandidates(now).forEach { merged.putIfAbsent(it.url, it) }
        fallbackCandidates(now).forEach { merged.putIfAbsent(it.url, it) }
        return merged.keys.toList()
    }

    private fun order(entries: List<Entry>, now: Long, exclude: Set<String>): List<Entry> =
        entries
            .filter { it.url.isNotEmpty() && !exclude.contains(it.url) }
            .distinctBy { it.url }
            .sortedWith(
                compareBy<Entry> { if (it.isDemoted(now)) 1 else 0 }
                    .thenBy { if (it.isDemoted(now)) it.demoteUntil else 0L }
                    .thenBy { if (it.isFreshOk(now)) 0 else 1 }
                    .thenBy { it.priority }
                    .thenBy { if (it.latencyMs >= 0L) it.latencyMs else Long.MAX_VALUE }
                    .thenBy { it.failCount }
                    .thenBy { it.url },
            )

    // ------------------------------------------------------------ probe results

    /** 探测成功：清零失败/降权，记录 finalUrl 与延迟。 */
    @Synchronized
    fun recordSuccess(url: String, finalUrl: String?, latencyMs: Long, now: Long): Entry? {
        val target = normalizeUrl(url)
        if (target.isEmpty()) return null
        val base = entries()
        val old = base.firstOrNull { it.url == target }
        val finalRoot = finalUrl?.let { normalizeUrl(it) }?.takeIf { it.isNotEmpty() }
        val updated = (old ?: newEntry(target)).copy(
            lastOkAt = now,
            lastProbeAt = now,
            latencyMs = if (latencyMs >= 0L) latencyMs else -1L,
            finalUrl = finalRoot,
            failCount = 0,
            demoteUntil = 0L,
            demoteReason = null,
            seenAt = now,
        )
        state = state.copy(entries = replace(base, updated), updatedAt = now)
        return updated
    }

    /** 探测失败：fail_count+1，按 [demoteBackoffMs] 指数退避降权。 */
    @Synchronized
    fun recordFailure(url: String, reason: String, now: Long): Entry? {
        val target = normalizeUrl(url)
        if (target.isEmpty()) return null
        val base = entries()
        val old = base.firstOrNull { it.url == target }
        val failCount = (old?.failCount ?: 0) + 1
        val updated = (old ?: newEntry(target)).copy(
            lastFailAt = now,
            lastProbeAt = now,
            failCount = failCount,
            demoteUntil = now + demoteBackoffMs(failCount),
            demoteReason = reason,
            seenAt = now,
        )
        state = state.copy(entries = replace(base, updated), updatedAt = now)
        return updated
    }

    /** 导航页抓取成功：更新 nav 缓存 + 把官网/镜像面板地址并入状态，淘汰 24h 未再出现的动态项。 */
    @Synchronized
    fun recordNavSuccess(now: Long, navUrl: String, official: List<String>, dynamic: List<String>) {
        val off = normalizeList(official)
        val dyn = normalizeList(dynamic)
        val byUrl = LinkedHashMap<String, Entry>()
        entries().forEach { byUrl[it.url] = it }
        off.forEachIndexed { index, url ->
            val old = byUrl[url]
            byUrl[url] = if (old == null) {
                Entry(url, Tier.OFFICIAL, "nav", NAV_OFFICIAL_PRIORITY + index, seenAt = now)
            } else if (old.tier == Tier.OFFICIAL) {
                old.copy(seenAt = now)
            } else {
                old.copy(tier = Tier.OFFICIAL, seenAt = now)
            }
        }
        dyn.forEachIndexed { index, url ->
            val old = byUrl[url]
            byUrl[url] = if (old == null) {
                Entry(url, Tier.DYNAMIC, "nav", DYNAMIC_PRIORITY + index, seenAt = now)
            } else {
                old.copy(seenAt = now)
            }
        }
        val pruned = pruneDynamic(byUrl.values.toList(), now)
        state = state.copy(
            entries = pruned,
            nav = NavState(navUrl, now, true, null, 0, off, dyn),
            updatedAt = now,
        )
    }

    /** 导航页抓取失败：记 fail_count，按退避窗口减少无谓重试。 */
    @Synchronized
    fun recordNavFailure(now: Long, navUrl: String, error: String?) {
        val fails = state.nav.failCount + 1
        state = state.copy(
            nav = state.nav.copy(url = navUrl, fetchedAt = now, ok = false, error = error, failCount = fails),
            updatedAt = now,
        )
    }

    /** 是否该抓导航页：从未抓过 / 成功超 6h / 失败按 30min 指数退避。 */
    @Synchronized
    fun shouldRefreshNav(now: Long): Boolean {
        val nav = state.nav
        if (nav.fetchedAt <= 0L) return true
        return if (nav.ok) now - nav.fetchedAt >= NAV_TTL_MS
        else now - nav.fetchedAt >= navBackoffMs(nav.failCount)
    }

    fun navBackoffMs(failCount: Int): Long = backoffMs(failCount, NAV_FAIL_BASE_MS, NAV_FAIL_MAX_MS)

    fun demoteBackoffMs(failCount: Int): Long = backoffMs(failCount, DEMOTE_BASE_MS, DEMOTE_MAX_MS)

    private fun backoffMs(failCount: Int, base: Long, max: Long): Long {
        if (failCount <= 0) return base
        var value = base
        repeat(failCount - 1) { value = minOf(value * 2L, max) }
        return value
    }

    // ------------------------------------------------------------ tier helpers

    /** 按 URL 判定层级：官方种子表内的算 official，导航动态项算 dynamic，其余兜底。 */
    @Synchronized
    fun tierOf(url: String): Tier {
        val target = normalizeUrl(url)
        if (officialSeeds.any { normalizeUrl(it) == target }) return Tier.OFFICIAL
        if (state.nav.dynamic.any { normalizeUrl(it) == target }) return Tier.DYNAMIC
        return Tier.FALLBACK
    }

    fun isOfficial(url: String): Boolean = tierOf(url) == Tier.OFFICIAL

    /** DiamWall host 判定（含子域）。 */
    fun isDiamWall(url: String): Boolean {
        val host = hostOf(url)
        if (host.isEmpty()) return true
        return DIAMWALL_HOSTS.any { d -> host == d || host.endsWith("." + d) }
    }

    fun isDiamWallHost(host: String): Boolean = isDiamWall("https://" + host + "/")

    fun hostOf(url: String): String =
        runCatching { URI(url.trim()).host.orEmpty().lowercase(Locale.US) }.getOrDefault("")

    /** 规范化：小写 scheme/host、保留端口、去掉 path/query/trailing slash。 */
    fun normalizeUrl(raw: String?): String {
        val trimmed = raw?.trim()?.trimEnd('/') ?: return ""
        if (trimmed.isEmpty()) return ""
        return runCatching {
            val uri = URI(if (trimmed.contains("://")) trimmed else "https://" + trimmed)
            val scheme = (uri.scheme ?: "https").lowercase(Locale.US)
            if (scheme != "http" && scheme != "https") return ""
            val host = uri.host?.lowercase(Locale.US) ?: return ""
            if (host.isEmpty() || host.contains(' ')) return ""
            val port = if (uri.port > 0) ":" + uri.port else ""
            scheme + "://" + host + port
        }.getOrDefault("")
    }

    // ------------------------------------------------------------ persistence

    /** 原子落盘：先写 .tmp 再 rename；失败静默（缓存损坏不能影响搜索/下载）。 */
    @Synchronized
    fun save(file: File?, now: Long = System.currentTimeMillis()) {
        if (file == null) return
        runCatching {
            val snapshot = state.copy(updatedAt = if (state.updatedAt > 0L) state.updatedAt else now)
            val text = encode(snapshot)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) {
                    file.writeText(text, Charsets.UTF_8)
                    tmp.delete()
                }
            }
        }
    }

    /** 仅供单测/诊断：当前在内存中的状态快照。 */
    @Synchronized
    fun snapshot(): State = state

    // ------------------------------------------------------------ internals

    private fun entries(): List<Entry> =
        if (state.entries.isEmpty()) withSeeds(State(), System.currentTimeMillis()).entries else state.entries

    private fun findEntry(url: String): Entry? = entries().firstOrNull { it.url == url }

    private fun newEntry(url: String): Entry =
        Entry(url, tierOf(url), "probe", priorityOf(url))

    private fun priorityOf(url: String): Int {
        val index = officialSeeds.indexOfFirst { normalizeUrl(it) == url }
        if (index >= 0) return index
        return FALLBACK_PRIORITY
    }

    private fun replace(base: List<Entry>, updated: Entry): List<Entry> {
        val out = ArrayList<Entry>(base.size + 1)
        var replaced = false
        for (entry in base) {
            if (entry.url == updated.url) {
                out.add(updated)
                replaced = true
            } else {
                out.add(entry)
            }
        }
        if (!replaced) out.add(updated)
        return out
    }

    private fun withSeeds(base: State, now: Long): State {
        val byUrl = LinkedHashMap<String, Entry>()
        base.entries.forEach { byUrl[it.url] = it }
        officialSeeds.forEachIndexed { index, raw ->
            val url = normalizeUrl(raw)
            if (url.isEmpty() || isDiamWall(url)) return@forEachIndexed
            val old = byUrl[url]
            byUrl[url] = if (old == null) Entry(url, Tier.OFFICIAL, "seed", index, seenAt = now)
            else if (old.tier == Tier.OFFICIAL) old
            else old.copy(tier = Tier.OFFICIAL, priority = minOf(old.priority, index))
        }
        fallbackSeeds.forEachIndexed { index, raw ->
            val url = normalizeUrl(raw)
            if (url.isEmpty() || isDiamWall(url) || byUrl.containsKey(url)) return@forEachIndexed
            byUrl[url] = Entry(url, Tier.FALLBACK, "seed", FALLBACK_PRIORITY + index, seenAt = now)
        }
        return base.copy(entries = pruneDynamic(byUrl.values.toList(), now))
    }

    private fun pruneDynamic(list: List<Entry>, now: Long): List<Entry> =
        list.filter { it.tier != Tier.DYNAMIC || it.seenAt <= 0L || now - it.seenAt <= DYNAMIC_PRUNE_MS }

    private fun normalizeList(raw: List<String>): List<String> =
        raw.map { normalizeUrl(it) }.filter { it.isNotEmpty() && !isDiamWall(it) }.distinct()

    // ------------------------------------------------------------ json (无 org.json 依赖)

    private fun encode(s: State): String {
        val sb = StringBuilder(2048)
        sb.append('{')
        sb.append("\"version\":").append(SCHEMA_VERSION)
        sb.append(",\"updated_at\":").append(s.updatedAt)
        sb.append(",\"nav\":")
        appendNav(sb, s.nav)
        sb.append(",\"entries\":[")
        s.entries.forEachIndexed { index, e ->
            if (index > 0) sb.append(',')
            appendEntry(sb, e)
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun appendNav(sb: StringBuilder, nav: NavState) {
        sb.append("{\"url\":").append(quote(nav.url))
        sb.append(",\"fetched_at\":").append(nav.fetchedAt)
        sb.append(",\"ok\":").append(nav.ok)
        sb.append(",\"error\":").append(if (nav.error == null) "null" else quote(nav.error))
        sb.append(",\"fail_count\":").append(nav.failCount)
        sb.append(",\"official\":").append(quoteList(nav.official))
        sb.append(",\"dynamic\":").append(quoteList(nav.dynamic))
        sb.append('}')
    }

    private fun appendEntry(sb: StringBuilder, e: Entry) {
        sb.append("{\"url\":").append(quote(e.url))
        sb.append(",\"tier\":").append(quote(e.tier.label))
        sb.append(",\"source\":").append(quote(e.source))
        sb.append(",\"priority\":").append(e.priority)
        sb.append(",\"last_ok_at\":").append(e.lastOkAt)
        sb.append(",\"last_fail_at\":").append(e.lastFailAt)
        sb.append(",\"fail_count\":").append(e.failCount)
        sb.append(",\"last_probe_at\":").append(e.lastProbeAt)
        sb.append(",\"latency_ms\":").append(e.latencyMs)
        sb.append(",\"final_url\":").append(if (e.finalUrl == null) "null" else quote(e.finalUrl))
        sb.append(",\"demote_until\":").append(e.demoteUntil)
        sb.append(",\"demote_reason\":").append(if (e.demoteReason == null) "null" else quote(e.demoteReason))
        sb.append(",\"seen_at\":").append(e.seenAt)
        sb.append('}')
    }

    private fun quoteList(values: List<String>): String =
        values.joinToString(",", "[", "]") { quote(it) }

    private fun quote(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append(String.format(Locale.US, "\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun parseState(text: String): State? {
        if (text.isBlank()) return null
        val root = JsonParser(text).parse() as? Map<*, *> ?: return null
        val rawEntries = root["entries"] as? List<*> ?: emptyList<Any?>()
        val entries = rawEntries.mapNotNull { raw ->
            val map = raw as? Map<*, *> ?: return@mapNotNull null
            val url = normalizeUrl(asString(map["url"]))
            if (url.isEmpty()) return@mapNotNull null
            Entry(
                url = url,
                tier = tierFromLabel(asString(map["tier"], Tier.FALLBACK.label)),
                source = asString(map["source"], "probe"),
                priority = asInt(map["priority"], 0),
                lastOkAt = asLong(map["last_ok_at"]),
                lastFailAt = asLong(map["last_fail_at"]),
                failCount = asInt(map["fail_count"]),
                lastProbeAt = asLong(map["last_probe_at"]),
                latencyMs = asLong(map["latency_ms"], -1L),
                finalUrl = normalizeUrl(asString(map["final_url"])).takeIf { it.isNotEmpty() },
                demoteUntil = asLong(map["demote_until"]),
                demoteReason = asString(map["demote_reason"]).takeIf { it.isNotEmpty() },
                seenAt = asLong(map["seen_at"]),
            )
        }
        val navMap = root["nav"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val nav = NavState(
            url = asString(navMap["url"]),
            fetchedAt = asLong(navMap["fetched_at"]),
            ok = asBool(navMap["ok"]),
            error = asString(navMap["error"]).takeIf { it.isNotEmpty() },
            failCount = asInt(navMap["fail_count"]),
            official = asStringList(navMap["official"]),
            dynamic = asStringList(navMap["dynamic"]),
        )
        return State(entries, nav, asLong(root["updated_at"]))
    }

    private fun tierFromLabel(label: String): Tier =
        Tier.values().firstOrNull { it.label == label } ?: Tier.FALLBACK

    private fun asString(value: Any?, def: String = ""): String = when (value) {
        is String -> value
        null -> def
        else -> value.toString()
    }

    private fun asLong(value: Any?, def: Long = 0L): Long = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Double -> value.toLong()
        is String -> value.toLongOrNull() ?: def
        else -> def
    }

    private fun asInt(value: Any?, def: Int = 0): Int = when (value) {
        is Long -> value.toInt()
        is Int -> value
        is Double -> value.toInt()
        is String -> value.toIntOrNull() ?: def
        else -> def
    }

    private fun asBool(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true)
        else -> false
    }

    private fun asStringList(value: Any?): List<String> =
        (value as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

    private class JsonParser(private val source: String) {
        private var pos = 0

        fun parse(): Any? {
            skipWs()
            return value()
        }

        private fun value(): Any? = when (val ch = peek()) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't', 'f', 'n' -> literal()
            else -> number()
        }

        private fun obj(): Map<String, Any?> {
            expect('{')
            skipWs()
            val map = LinkedHashMap<String, Any?>()
            if (peek() == '}') {
                pos++
                return map
            }
            while (true) {
                skipWs()
                val key = str()
                skipWs()
                expect(':')
                skipWs()
                map[key] = value()
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw IllegalArgumentException("expected , or } at " + pos)
                }
            }
        }

        private fun arr(): List<Any?> {
            expect('[')
            skipWs()
            val list = ArrayList<Any?>()
            if (peek() == ']') {
                pos++
                return list
            }
            while (true) {
                skipWs()
                list.add(value())
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw IllegalArgumentException("expected , or ] at " + pos)
                }
            }
        }

        private fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (pos >= source.length) throw IllegalArgumentException("unterminated string")
                val ch = source[pos++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= source.length) throw IllegalArgumentException("bad escape")
                        when (val esc = source[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > source.length) throw IllegalArgumentException("bad unicode")
                                sb.append(source.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("bad escape " + esc)
                        }
                    }
                    else -> sb.append(ch)
                }
            }
        }

        private fun number(): Any {
            val start = pos
            while (pos < source.length) {
                val ch = source[pos]
                if (ch.isDigit() || ch == '-' || ch == '+' || ch == '.' || ch == 'e' || ch == 'E') pos++ else break
            }
            if (pos == start) throw IllegalArgumentException("bad number at " + pos)
            val text = source.substring(start, pos)
            return text.toLongOrNull() ?: text.toDouble()
        }

        private fun literal(): Any? {
            return when {
                source.startsWith("true", pos) -> {
                    pos += 4
                    true
                }
                source.startsWith("false", pos) -> {
                    pos += 5
                    false
                }
                source.startsWith("null", pos) -> {
                    pos += 4
                    null
                }
                else -> throw IllegalArgumentException("bad literal at " + pos)
            }
        }

        private fun peek(): Char {
            if (pos >= source.length) throw IllegalArgumentException("unexpected end")
            return source[pos]
        }

        private fun expect(ch: Char) {
            if (peek() != ch) throw IllegalArgumentException("expected " + ch + " at " + pos)
            pos++
        }

        private fun skipWs() {
            while (pos < source.length && source[pos].isWhitespace()) pos++
        }
    }
}
