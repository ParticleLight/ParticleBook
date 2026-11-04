package app.particlebook.android.zlib

import java.io.Closeable
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * task-84 P1-1：真实抓包的 200 软页（登录 / 错误 / 下载限额）在探测阶段不得被判成 APP 采用。
 *
 * 旧 APP_MARKERS 含页脚链接 z-access 与 CSS 前缀 z-recommend，所有真实页面都带，导致
 * login-error / page-not-found / dl-daily-limit 全被判 APP：坏线路被 adopt，且 PARSE 后不清 pin。
 * 夹具直接复用 test/resources/zlib/ 的真实页面，不再用极简合成 body。
 */
class ZlibRealSoftPageProbeTest {

    private val logs: MutableList<String> = Collections.synchronizedList(ArrayList<String>())
    private val now = System.currentTimeMillis()

    private class MemoryPinStore(var stored: String? = null) : ZlibMirrorPinStore {
        override fun readPin(): String? = stored
        override fun writePin(url: String?) {
            stored = url
        }

        override fun writePinNow(url: String?) {
            stored = url
        }
    }

    /** 固定 200 + 真实夹具 body 的回环桩。 */
    private class StubHttpServer(
        private val body: String,
    ) : Closeable {
        private val server = ServerSocket(0)
        val baseUrl: String = "http://127.0.0.1:" + server.localPort
        val hits = AtomicInteger(0)
        private val thread = Thread {
            try {
                server.soTimeout = 5_000
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (t: Throwable) {
                        break
                    }
                    hits.incrementAndGet()
                    socket.use {
                        val reader = it.getInputStream().bufferedReader(Charsets.UTF_8)
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        val head = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: " + bytes.size + "\r\n" +
                            "Connection: close\r\n\r\n"
                        it.getOutputStream().write(head.toByteArray(Charsets.UTF_8))
                        it.getOutputStream().write(bytes)
                        it.getOutputStream().flush()
                    }
                }
            } catch (_: Throwable) {
                // close() 中断 accept：正常收尾。
            }
        }

        init {
            thread.isDaemon = true
            thread.start()
        }

        override fun close() {
            runCatching { server.close() }
        }
    }

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("zlib/" + name)!!.use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    private fun freshRegistry(official: List<String>) {
        ZlibMirrorRegistry.resetForTest(official, emptyList())
        ZlibMirrorRegistry.ensureLoaded(null, now)
        // 导航缓存标新鲜：避免探测阶段后台抓 wwwnav。
        ZlibMirrorRegistry.recordNavSuccess(now, ZlibMirrorList.NAV_URL, emptyList(), emptyList())
    }

    private fun newClient(store: ZlibMirrorPinStore? = null): ZlibClient {
        logs.clear()
        val client = ZlibClient()
        client.testLogger = { line -> logs.add(line) }
        client.navFetcherForTest = { null }
        if (store != null) client.pinStoreForTest = store
        return client
    }

    private fun assertNoMirror(failure: Throwable?) {
        val error = failure
        assertTrue(
            "整轮应判无可采用线路，而不是 adopt 软页: " + error,
            error is ZlibException && error.kind == ZlibException.Kind.NO_MIRROR,
        )
    }

    private fun probeFailure(client: ZlibClient): Throwable? {
        var failure: Throwable? = null
        try {
            client.probeMirrors()
        } catch (t: Throwable) {
            failure = t
        }
        return failure
    }

    @Test
    fun loginErrorPageIsPagedAndClearsPin() {
        StubHttpServer(fixture("login-error.html")).use { server ->
            freshRegistry(listOf(server.baseUrl))
            val store = MemoryPinStore(server.baseUrl)
            val client = newClient(store)

            assertNoMirror(probeFailure(client))

            assertNull("登录页是确定性非正式站证据，应清 pin", store.stored)
            assertNull("不得 adopt 登录页作为当前线路", client.mirror)
            assertTrue(
                "应先判软页证据再降级，而不是 APP: " + logs,
                logs.any {
                    it.startsWith("mirror_probe_soft") && it.contains("rank=page") &&
                        it.contains("unpin=probe_login_page")
                },
            )
        }
    }

    @Test
    fun pageNotFoundIsPagedAndClearsPin() {
        StubHttpServer(fixture("page-not-found.html")).use { server ->
            freshRegistry(listOf(server.baseUrl))
            val store = MemoryPinStore(server.baseUrl)
            val client = newClient(store)

            assertNoMirror(probeFailure(client))

            assertNull("错误页是确定性非正式站证据，应清 pin", store.stored)
            assertNull("不得 adopt 错误页作为当前线路", client.mirror)
            assertTrue(
                "错误页应判 PAGE 并清 pin: " + logs,
                logs.any {
                    it.startsWith("mirror_probe_soft") && it.contains("rank=page") &&
                        it.contains("unpin=probe_error_page")
                },
            )
        }
    }

    @Test
    fun dailyLimitPageIsNotAdoptedButKeepsPin() {
        StubHttpServer(fixture("dl-daily-limit.html")).use { server ->
            freshRegistry(listOf(server.baseUrl))
            val store = MemoryPinStore(server.baseUrl)
            val client = newClient(store)

            assertNoMirror(probeFailure(client))

            assertEquals("限额页是站点正常、账号额度受限：不得清 pin", server.baseUrl, store.stored)
            assertNull("限额页也不能被 adopt 成搜索线路", client.mirror)
            assertTrue(
                "限额页应标 limit=1 且不产生清 pin 证据: " + logs,
                logs.any {
                    it.startsWith("mirror_probe_soft") && it.contains("limit=1") &&
                        it.contains("unpin=none") && it.contains("rank=page")
                },
            )
            assertTrue(
                "保留 pin 的日志应为 soft_page: " + logs,
                logs.any { it.startsWith("mirror_pin_keep") && it.contains("reason=soft_page") },
            )
        }
    }

    @Test
    fun searchEmptyPageIsStillAdoptedAsApp() {
        StubHttpServer(fixture("search-empty.html")).use { server ->
            freshRegistry(listOf(server.baseUrl))
            val store = MemoryPinStore()
            val client = newClient(store)

            assertEquals("真实空搜索结果页仍必须判 APP 并采用", server.baseUrl, client.probeMirrors())
            assertEquals(server.baseUrl, client.mirror)
            assertTrue(
                "应采用正式站: " + logs,
                logs.any { it.startsWith("mirror_pick") && it.contains("rank=app") },
            )
        }
    }

    /** 页脚 z-access + CSS 前缀 z-recommended-search 的普通页：不再因这些常见串被判 APP。 */
    @Test
    fun footerAndCssPrefixAloneIsNotApp() {
        val body = "<html><head><style>z-recommended-search:not(.ready){opacity:0}</style></head>" +
            "<body><a href=\"/z-access#desktop_app_tab\">Z-Library</a></body></html>"
        StubHttpServer(body).use { server ->
            freshRegistry(listOf(server.baseUrl))
            val store = MemoryPinStore(server.baseUrl)
            val client = newClient(store)

            assertNoMirror(probeFailure(client))

            assertNull("只有页脚/样式前缀的页面不得被 adopt", client.mirror)
            assertEquals("无确定性失效证据时保留 pin", server.baseUrl, store.stored)
            assertTrue(
                "应判 PAGE 且无清 pin 证据: " + logs,
                logs.any {
                    it.startsWith("mirror_probe_soft") && it.contains("rank=page") &&
                        it.contains("unpin=none")
                },
            )
        }
    }

    /** task-95：正常搜索页有 <z-bookcard> + 登录 CTA，不得因 “sign in to” 被降级清 pin。 */
    @Test
    fun appPageWithWeakLoginCtaIsStillAdopted() {
        val body = "<html><title>Z-Library</title><z-bookcard id=\"1\"></z-bookcard>" +
            "<a href=\"/login\">Sign in to your account</a></html>"
        StubHttpServer(body).use { server ->
            freshRegistry(listOf(server.baseUrl))
            val client = newClient()

            assertEquals("APP 页面上的登录 CTA 不应让线路降级", server.baseUrl, client.probeMirrors())
            assertEquals(server.baseUrl, client.mirror)
            assertTrue(
                "应采用 APP 线路: " + logs,
                logs.any { it.startsWith("mirror_pick") && it.contains("rank=app") },
            )
            assertTrue(
                "不得产生登录页清 pin 证据: " + logs,
                logs.none { it.contains("unpin=probe_login_page") },
            )
        }
    }

    /** task-95：没有 APP 组件时，弱证据 “sign in to” 仍按登录页清 pin（不能收得过头）。 */
    @Test
    fun weakLoginCtaWithoutAppStillClearsPin() {
        val body = "<html><title>Z-Library</title><a href=\"/login\">Sign in to your account</a></html>"
        StubHttpServer(body).use { server ->
            freshRegistry(listOf(server.baseUrl))
            val store = MemoryPinStore(server.baseUrl)
            val client = newClient(store)

            assertNoMirror(probeFailure(client))

            assertNull("无 APP 组件的登录 CTA 仍应清 pin", store.stored)
            assertNull("不得 adopt 登录 CTA 页", client.mirror)
            assertTrue(
                "应记录 probe_login_page: " + logs,
                logs.any {
                    it.startsWith("mirror_unpin") && it.contains("reason=probe_login_page")
                },
            )
        }
    }

    /** task-95：password input / 登录标题是强证据，即使页面还带 APP 组件也不能 adopt。 */
    @Test
    fun appPageWithStrongLoginEvidenceIsPagedAndClearsPin() {
        val body = "<html><title>Login</title><z-bookcard id=\"1\"></z-bookcard>" +
            "<input type=\"password\" name=\"pw\"></html>"
        StubHttpServer(body).use { server ->
            freshRegistry(listOf(server.baseUrl))
            val store = MemoryPinStore(server.baseUrl)
            val client = newClient(store)

            assertNoMirror(probeFailure(client))

            assertNull("强登录证据应清 pin", store.stored)
            assertNull("不得 adopt 登录墙", client.mirror)
            assertTrue(
                "应判 PAGE 且 reason=probe_login_page: " + logs,
                logs.any {
                    it.startsWith("mirror_probe_soft") && it.contains("rank=page") &&
                        it.contains("unpin=probe_login_page")
                },
            )
        }
    }

    /**
     * task-109 P1 回归：真实登录态搜索页头有 <span>Daily limit</span> 额度组件，它不是错误页；
     * 旧裸词 daily limit 会把正常 APP 页降成 PAGE（实测整轮 NO_MIRROR）。
     */
    @Test
    fun loginSearchPageWithDailyLimitWidgetIsAdopted() {
        val body = "<html><title>Z-Library</title><z-bookcard id=\"1\"></z-bookcard>" +
            "<div class=\"caret-scroll__desc\"><i class=\"zlibicon-download\"></i>" +
            "<span>Daily limit</span></div>" +
            "<div class=\"caret-scroll__desc\"><span>daily limit</span></div></html>"
        assertAdoptedAsApp(body, "登录态搜索页额度组件")
    }

    /**
     * task-109 P1 回归：真实匿名 zh.z-lib.gd 的 Z-Alerts 弹窗有“以提高限额”，
     * 旧裸词“限额”会把正常中文搜索页降成 PAGE。
     */
    @Test
    fun zhSearchPageWithDonationLimitHintIsAdopted() {
        val body = "<html><title>Z-Library</title><z-bookcard id=\"1\"></z-bookcard>" +
            "<div class=\"z-modal-content\"><span>请停用部分或" +
            "<a href=\"/how-to-donate\">进行捐款</a>以提高限额</span></div></html>"
        assertAdoptedAsApp(body, "中文搜索页 Z-Alerts 限额提示")
    }

    /**
     * task-109 P1 回归：真实详情页的升级权益文案 “Increased download limit” 不是限额错误页；
     * 旧裸词 download limit 会让它带 limit 标记（正常详情页本来也不是 APP 探测目标）。
     */
    @Test
    fun normalDetailUpgradeCopyIsNotLimitPage() {
        StubHttpServer(fixture("book-detail.html")).use { server ->
            freshRegistry(listOf(server.baseUrl))
            val store = MemoryPinStore(server.baseUrl)
            val client = newClient(store)

            assertNoMirror(probeFailure(client))

            assertEquals("详情页升级文案不代表限额错误，应保留 pin", server.baseUrl, store.stored)
            assertTrue(
                "详情页不能被标成限额页: " + logs,
                logs.none { it.contains("limit=1") },
            )
        }
    }

    private fun assertAdoptedAsApp(body: String, label: String) {
        StubHttpServer(body).use { server ->
            freshRegistry(listOf(server.baseUrl))
            val client = newClient()

            assertEquals(label + " 应判 APP 并采用", server.baseUrl, client.probeMirrors())
            assertEquals(server.baseUrl, client.mirror)
            assertTrue(
                label + " 应采用: " + logs,
                logs.any { it.startsWith("mirror_pick") && it.contains("rank=app") },
            )
            assertTrue(
                label + " 不应被当成限额页: " + logs,
                logs.none { it.contains("limit=1") },
            )
        }
    }

    /** task-95：限额页的英文/中文变体都必须先于登录证据，且不清 pin。 */
    @Test
    fun limitPageVariantsKeepPinAndDoNotAdopt() {
        val variants = listOf(
            "Daily limit reached",
            "Download limit reached",
            "今日下载限额已用尽",
        )
        for (marker in variants) {
            val body = "<html><title>Z-Library</title><z-bookcard id=\"1\"></z-bookcard>" +
                "<p>" + marker + "</p><a>Sign in to your account</a></html>"
            StubHttpServer(body).use { server ->
                freshRegistry(listOf(server.baseUrl))
                val store = MemoryPinStore(server.baseUrl)
                val client = newClient(store)

                assertNoMirror(probeFailure(client))

                assertEquals("限额变体不得清 pin: " + marker, server.baseUrl, store.stored)
                assertNull("限额变体不得被 adopt: " + marker, client.mirror)
                assertTrue(
                    "限额变体应标 limit=1 且无清 pin 证据: " + marker + " / " + logs,
                    logs.any {
                        it.startsWith("mirror_probe_soft") && it.contains("limit=1") &&
                            it.contains("unpin=none")
                    },
                )
            }
        }
    }
}
