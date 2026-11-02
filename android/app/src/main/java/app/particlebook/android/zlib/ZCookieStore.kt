package app.particlebook.android.zlib

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cookie 持久化（P1b §3.1）：
 * - 内存 `java.net.CookieManager`，磁盘 `filesDir/zlib_cookies.json`（name/value/domain/path/expiry）；
 *   **session cookie 也落盘**（z-lib 的会话 cookie 不一定带 Expires）。
 * - 构造不读盘（ZlibClient 可能在主线程创建）；首次 [ensureLoaded] 在 IO 线程加载。
 * - Set-Cookie 后 200ms 节流写盘；[clear] 清内存 + 删文件（logout 用）。
 * - 挑战 cookie（c_token/c_time）不经过本类，也不持久化（每次进程内重算）。
 */
internal class ZCookieStore(
    private val appContext: Context?,
    private val log: (String) -> Unit,
) {

    private val manager = CookieManager(null, CookiePolicy.ACCEPT_ALL)

    @Volatile
    private var loaded: Boolean = false

    private val pendingPersist = AtomicBoolean(false)

    private val scheduler: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "zlib-cookie-persist").apply { isDaemon = true }
        }
    }

    private fun file(): File? = appContext?.let { File(it.filesDir, FILE_NAME) }

    /** 首次使用时从磁盘加载（调用方保证在 IO 线程）。 */
    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        loadFromDisk()
    }

    /**
     * 与 URI 匹配的 Cookie 原始头（不含手动挑战 cookie，由 ZlibClient 合并）。
     *
     * 注意：CookieManager 对 Version=1 的 cookie 会按 RFC 2965 输出（含 `$Version/$Path/$Domain`）。
     * Android 12 实测返回**单条拼接串**（AOSP InMemoryCookieStore.sortByPath 用 `; ` 拼接），
     * JDK 实现则可能是多个 header 值；这里全部拼接（防御两种形态），
     * 由 [ZlibCookieHeader] 统一规范化成 version-0 后再发。
     */
    fun cookieHeader(uri: URI): String? = try {
        manager.get(uri, emptyMap<String, List<String>>())?.get("Cookie")
            ?.filter { it.isNotBlank() }
            ?.joinToString("; ")
            ?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    /** 保存一次响应的 Set-Cookie（返回条数，诊断用），并按 200ms 节流落盘。 */
    fun store(uri: URI, headers: Map<String, List<String>>): Int {
        var count = 0
        try {
            // HttpURLConnection.headerFields 的 key 大小写不保证；CookieManager.put 只认
            // "Set-Cookie"/"Set-Cookie2"，这里统一归一后再喂，避免任何实现差异导致丢 cookie。
            val responseHeaders = LinkedHashMap<String, List<String>>()
            headers.entries.forEach { entry ->
                val key = entry.key as String?
                if (key != null && (key.equals("Set-Cookie", ignoreCase = true) ||
                        key.equals("Set-Cookie2", ignoreCase = true))) {
                    responseHeaders["Set-Cookie"] =
                        (responseHeaders["Set-Cookie"] ?: emptyList()) + entry.value
                }
            }
            count = responseHeaders["Set-Cookie"]?.size ?: 0
            manager.put(uri, responseHeaders)
            // 关键：把 Version=1 的 cookie 归一到 version-0，否则 CookieManager.get() 输出
            // RFC 2965 格式（$Version/$Path/$Domain + 引号值），服务端识别不到登录 cookie。
            normalizeVersions()
        } catch (e: Exception) {
            log("cookie_put_err=${e.javaClass.simpleName}")
        }
        if (count > 0) schedulePersist()
        return count
    }

    /** 把 jar 里所有 Version=1 cookie 归一到 version-0（RFC 6265 发送格式）。 */
    private fun normalizeVersions() {
        try {
            manager.cookieStore.cookies.forEach { cookie ->
                if (cookie.version != 0) {
                    try {
                        cookie.version = 0
                    } catch (e: Exception) {
                        // 单条失败不影响其他 cookie；发送前 [ZlibCookieHeader] 仍会兜底规范化。
                    }
                }
            }
        } catch (e: Exception) {
            log("cookie_version_err=${e.javaClass.simpleName}")
        }
    }

    /** 当前 cookie 条数（日志用）。 */
    fun cookieCount(): Int = try {
        manager.cookieStore.cookies.size
    } catch (e: Exception) {
        0
    }

    /** 立即落盘（登录成功后调用，确保杀进程仍保持登录）。 */
    fun persistNow() {
        try {
            writeToDisk()
        } catch (e: Exception) {
            log("cookie_persist_err=${e.javaClass.simpleName}")
        }
    }

    /** 清空内存 cookie 并删除磁盘文件（logout 用）。 */
    fun clear() {
        pendingPersist.set(false)
        try {
            manager.cookieStore.removeAll()
        } catch (e: Exception) {
            log("cookie_clear_err=${e.javaClass.simpleName}")
        }
        try {
            file()?.delete()
        } catch (e: Exception) {
            // 删除失败不影响登出（内存已清）
        }
    }

    private fun schedulePersist() {
        if (appContext == null) return
        if (!pendingPersist.compareAndSet(false, true)) return
        try {
            scheduler.schedule(
                {
                    pendingPersist.set(false)
                    persistNow()
                },
                PERSIST_DELAY_MS,
                TimeUnit.MILLISECONDS,
            )
        } catch (e: Exception) {
            pendingPersist.set(false)
        }
    }

    private fun writeToDisk() {
        val target = file() ?: return
        val cookies = try {
            manager.cookieStore.cookies
        } catch (e: Exception) {
            emptyList()
        }
        val array = JSONArray()
        val now = System.currentTimeMillis()
        cookies.forEach { cookie ->
            val item = JSONObject()
            item.put("name", cookie.name ?: return@forEach)
            item.put("value", cookie.value ?: "")
            item.put("domain", cookie.domain ?: "")
            item.put("path", cookie.path ?: "/")
            val maxAge = cookie.maxAge
            if (maxAge < 0) {
                item.put("session", true)
                item.put("expiry", 0L)
            } else {
                item.put("session", false)
                item.put("expiry", now + maxAge * 1000L)
            }
            array.put(item)
        }
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                log("cookie_persist_rename_failed")
            }
        }
    }

    private fun loadFromDisk() {
        val source = file() ?: return
        if (!source.isFile) return
        val array = try {
            JSONArray(source.readText())
        } catch (e: Exception) {
            log("cookie_load_err=${e.javaClass.simpleName}")
            return
        }
        val now = System.currentTimeMillis()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("name").takeIf { it.isNotEmpty() } ?: continue
            val value = item.optString("value")
            val domain = item.optString("domain").removePrefix(".")
            if (domain.isEmpty()) continue
            val session = item.optBoolean("session", true)
            val expiry = item.optLong("expiry", 0L)
            val maxAge = if (session) -1L else ((expiry - now) / 1000L).coerceAtLeast(0L)
            if (!session && maxAge <= 0L) continue
            try {
                val cookie = HttpCookie(name, value)
                cookie.domain = domain
                cookie.path = item.optString("path", "/").ifEmpty { "/" }
                cookie.maxAge = maxAge
                manager.cookieStore.add(URI("https://$domain/"), cookie)
            } catch (e: Exception) {
                // 单条损坏不影响其他 cookie
            }
        }
        normalizeVersions()
        log("cookie_loaded count=${cookieCount()}")
    }

    companion object {
        const val FILE_NAME: String = "zlib_cookies.json"
        private const val PERSIST_DELAY_MS: Long = 200L
    }
}
