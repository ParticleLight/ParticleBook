package app.particlebook.android.zlib

import java.io.Closeable
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * task-47 / P2-6：用户 pin 线路的稳健性。
 *
 * 覆盖四点：
 * 1) 瞬时网络失败 / 5xx 不清 pin（只记失败次数与时间，失败信息由注册表记录）；
 * 2) 明确非正式站证据（HTTP 4xx、200 的登录页/错误页/网关）才清 pin；合法空结果页 / SPA 壳 /
 *    维护页等软页证据不足，保留 pin（task-66 P2-2）；
 * 3) 用户切线路后，后台探测/搜索的旧结果因 epoch 变化被丢弃，不覆盖用户选择；
 * 4) switchMirror 的 pin 写用同步落盘（commit）。
 */
class ZlibMirrorPinRobustnessTest {

    private val logs: MutableList<String> = Collections.synchronizedList(ArrayList<String>())
    private val now = System.currentTimeMillis()
    private val appBody = "<html><title>Z-Library</title><z-bookcard id=\"1\"></z-bookcard></html>"
    private val emptySearchBody = "<html><body><div class='notfound'>nothing has been found</div></body></html>"

    private class MemoryPinStore(var stored: String? = null) : ZlibMirrorPinStore {
        var syncWrites = 0
        override fun readPin(): String? = stored
        override fun writePin(url: String?) {
            stored = url
        }
        override fun writePinNow(url: String?) {
            syncWrites++
            stored = url
        }
    }

    /** 固定状态码 + body 的可复用回环桩。 */
    private class StubHttpServer(
        private val body: String,
        private val status: Int = 200,
    ) : Closeable {
        private val server = ServerSocket(0)
        val baseUrl: String = "http://127.0.0.1:" + server.localPort
        private val thread = Thread {
            try {
                server.soTimeout = 5_000
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (t: Throwable) {
                        break
                    }
                    socket.use {
                        val reader = it.getInputStream().bufferedReader(Charsets.UTF_8)
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        val head = "HTTP/1.1 " + status + " ERROR" + "\r\n" +
                            "Content-Type: text/html; charset=utf-8" + "\r\n" +
                            "Content-Length: " + bytes.size + "\r\n" +
                            "Connection: close" + "\r\n\r\n"
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

    /**
     * 可闸门回环桩：收到请求后放行 [awaitAccepted]，再等 [release] 才应答。
     * 用来构造「后台请求进行中用户切线路」的确定性时序。
     */
    private class GatedHttpServer(
        private val body: String,
        private val status: Int = 200,
    ) : Closeable {
        private val server = ServerSocket(0)
        val baseUrl: String = "http://127.0.0.1:" + server.localPort
        val hits = AtomicInteger(0)
        private val accepted = CountDownLatch(1)
        private val released = CountDownLatch(1)
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
                    accepted.countDown()
                    released.await(5, TimeUnit.SECONDS)
                    socket.use {
                        val reader = it.getInputStream().bufferedReader(Charsets.UTF_8)
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        val head = "HTTP/1.1 " + status + " OK" + "\r\n" +
                            "Content-Type: text/html; charset=utf-8" + "\r\n" +
                            "Content-Length: " + bytes.size + "\r\n" +
                            "Connection: close" + "\r\n\r\n"
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

        fun awaitAccepted(): Boolean = accepted.await(5, TimeUnit.SECONDS)

        fun release() {
            released.countDown()
        }

        override fun close() {
            released.countDown()
            runCatching { server.close() }
        }
    }

    /** task-33 现场形态：任意深链 301 到站点首页（path 被丢弃）的网关。 */
    private class HomeRedirectGateway(private val homeUrl: String) : Closeable {
        private val server = ServerSocket(0)
        val baseUrl: String = "http://127.0.0.1:" + server.localPort
        private val thread = Thread {
            try {
                server.soTimeout = 5_000
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (t: Throwable) {
                        break
                    }
                    socket.use {
                        val reader = it.getInputStream().bufferedReader(Charsets.UTF_8)
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val head = "HTTP/1.1 301 Moved Permanently\r\n" +
                            "Location: " + homeUrl + "\r\n" +
                            "Content-Length: 0\r\n" +
                            "Connection: close\r\n\r\n"
                        it.getOutputStream().write(head.toByteArray(Charsets.UTF_8))
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

    private fun freshRegistry(official: List<String>, fallback: List<String> = emptyList()) {
        ZlibMirrorRegistry.resetForTest(official, fallback)
        ZlibMirrorRegistry.ensureLoaded(null, now)
        // 导航缓存标新鲜：避免后台抓 wwwnav（单测不联网）。
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

    private fun hasLog(prefix: String): Boolean = logs.any { it.startsWith(prefix) }

    /** 断网/端口不可达属于瞬时网络失败：pin 必须保留，等下次重探。 */
    @Test
    fun transientNetworkFailureKeepsPin() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val deadUrl = "http://127.0.0.1:" + closedPort
        freshRegistry(listOf(deadUrl))
        val store = MemoryPinStore(stored = deadUrl)
        val client = newClient(store)

        try {
            client.probeMirrors()
            throw AssertionError("不可达线路必须抛 NO_MIRROR")
        } catch (e: ZlibException) {
            assertEquals(ZlibException.Kind.NO_MIRROR, e.kind)
        }

        assertEquals("瞬时网络失败不得清 pin", deadUrl, store.stored)
        assertTrue(
            "应记录「保留 pin + 瞬时失败」: " + logs,
            hasLog("mirror_pin_keep") && logs.any { it.contains("reason=transient") },
        )
    }

    /** 5xx 可能只是服务端抖动：同样保留 pin（只有 4xx 才是确定性拒绝）。 */
    @Test
    fun serverError500KeepsPin() {
        StubHttpServer("boom", 500).use { server500 ->
            freshRegistry(listOf(server500.baseUrl))
            val store = MemoryPinStore(stored = server500.baseUrl)
            val client = newClient(store)

            try {
                client.probeMirrors()
                throw AssertionError("500 线路必须抛 NO_MIRROR")
            } catch (e: ZlibException) {
                assertEquals(ZlibException.Kind.NO_MIRROR, e.kind)
            }

            assertEquals("5xx 不得清 pin（可能只是服务端抖动）", server500.baseUrl, store.stored)
            assertTrue(logs.toString(), logs.any { it.startsWith("mirror_pin_keep") })
        }
    }

    /** HTTP 4xx 是线路对请求的确定性拒绝：明确非正式站证据，清 pin。 */
    @Test
    fun http404ClearsPin() {
        StubHttpServer("not found", 404).use { server404 ->
            freshRegistry(listOf(server404.baseUrl))
            val store = MemoryPinStore(stored = server404.baseUrl)
            val client = newClient(store)

            try {
                client.probeMirrors()
                throw AssertionError("404 线路必须抛 NO_MIRROR")
            } catch (e: ZlibException) {
                assertEquals(ZlibException.Kind.NO_MIRROR, e.kind)
            }

            assertNull("HTTP 4xx 是明确非正式站证据，应清 pin", store.stored)
            assertTrue(
                "应记录 probe_non_app 清 pin: " + logs,
                logs.any { it.startsWith("mirror_unpin") && it.contains("reason=probe_non_app") },
            )
        }
    }

    /** P2-2：200 的推广/导航软页（PAGE）不等于线路失效：空结果/改版都可能这样，保留 pin 等重探。 */
    @Test
    fun softNonAppPageKeepsPin() {
        val navBody = "<html><title>Z-Library Project</title><nav>mirrors</nav></html>"
        StubHttpServer(navBody).use { page ->
            freshRegistry(listOf(page.baseUrl))
            val store = MemoryPinStore(stored = page.baseUrl)
            val client = newClient(store)

            try {
                client.probeMirrors()
                throw AssertionError("非正式站必须抛 NO_MIRROR")
            } catch (e: ZlibException) {
                assertEquals(ZlibException.Kind.NO_MIRROR, e.kind)
            }

            assertEquals("软页证据不足，不得清 pin", page.baseUrl, store.stored)
            assertTrue(
                "应记录 soft_page 保留 pin: " + logs,
                logs.any { it.startsWith("mirror_pin_keep") && it.contains("reason=soft_page") },
            )
        }
    }

    /** P2-2：合法空结果页（含站点标记但搜不到内容）只是临时状态，保留 pin。 */
    @Test
    fun emptyResultPageKeepsPin() {
        val emptyBody = "<html><title>Z-Library</title><p>Nothing has been found</p></html>"
        StubHttpServer(emptyBody).use { page ->
            freshRegistry(listOf(page.baseUrl))
            val store = MemoryPinStore(stored = page.baseUrl)
            val client = newClient(store)

            try {
                client.probeMirrors()
                throw AssertionError("软页必须抛 NO_MIRROR")
            } catch (e: ZlibException) {
                assertEquals(ZlibException.Kind.NO_MIRROR, e.kind)
            }

            assertEquals("空结果页不得清 pin", page.baseUrl, store.stored)
            assertTrue(
                "应记录 soft_page: " + logs,
                logs.any { it.startsWith("mirror_pin_keep") && it.contains("reason=soft_page") },
            )
        }
    }

    /** P2-2：SPA 壳（<div id="app">）正文里没有站点标记，属于 OTHER 软页，保留 pin。 */
    @Test
    fun spaShellKeepsPin() {
        val spaBody = "<html><body><div id=\"app\"></div><script src=\"/main.js\"></script></body></html>"
        StubHttpServer(spaBody).use { page ->
            freshRegistry(listOf(page.baseUrl))
            val store = MemoryPinStore(stored = page.baseUrl)
            val client = newClient(store)

            try {
                client.probeMirrors()
                throw AssertionError("软页必须抛 NO_MIRROR")
            } catch (e: ZlibException) {
                assertEquals(ZlibException.Kind.NO_MIRROR, e.kind)
            }

            assertEquals("SPA 壳不得清 pin", page.baseUrl, store.stored)
            assertTrue(
                "应记录 soft_page: " + logs,
                logs.any { it.startsWith("mirror_pin_keep") && it.contains("reason=soft_page") },
            )
        }
    }

    /** P2-2：维护页不是「站点永久失效」，保留 pin 等恢复。 */
    @Test
    fun maintenancePageKeepsPin() {
        val maintenanceBody = "<html><body>网站维护中，请稍后再试</body></html>"
        StubHttpServer(maintenanceBody).use { page ->
            freshRegistry(listOf(page.baseUrl))
            val store = MemoryPinStore(stored = page.baseUrl)
            val client = newClient(store)

            try {
                client.probeMirrors()
                throw AssertionError("软页必须抛 NO_MIRROR")
            } catch (e: ZlibException) {
                assertEquals(ZlibException.Kind.NO_MIRROR, e.kind)
            }

            assertEquals("维护页不得清 pin", page.baseUrl, store.stored)
            assertTrue(
                "应记录 soft_page: " + logs,
                logs.any { it.startsWith("mirror_pin_keep") && it.contains("reason=soft_page") },
            )
        }
    }

    /** P2-2：登录页是确定性非正式站证据（线路已变成登录墙），清 pin。 */
    @Test
    fun loginPageClearsPin() {
        val loginBody =
            "<html><title>Login</title><input type=\"password\" name=\"pw\"><a>z-library</a></html>"
        StubHttpServer(loginBody).use { page ->
            freshRegistry(listOf(page.baseUrl))
            val store = MemoryPinStore(stored = page.baseUrl)
            val client = newClient(store)

            try {
                client.probeMirrors()
                throw AssertionError("登录页必须抛 NO_MIRROR")
            } catch (e: ZlibException) {
                assertEquals(ZlibException.Kind.NO_MIRROR, e.kind)
            }

            assertNull("登录页应清 pin", store.stored)
            assertTrue(
                "应记录 probe_login_page: " + logs,
                logs.any { it.startsWith("mirror_unpin") && it.contains("reason=probe_login_page") },
            )
        }
    }

    /** P2-2：明确错误页（404 Not Found 正文）是确定性证据，清 pin。 */
    @Test
    fun errorPageClearsPin() {
        val errorBody = "<html><title>404 Not Found</title><p>z-lib</p></html>"
        StubHttpServer(errorBody).use { page ->
            freshRegistry(listOf(page.baseUrl))
            val store = MemoryPinStore(stored = page.baseUrl)
            val client = newClient(store)

            try {
                client.probeMirrors()
                throw AssertionError("错误页必须抛 NO_MIRROR")
            } catch (e: ZlibException) {
                assertEquals(ZlibException.Kind.NO_MIRROR, e.kind)
            }

            assertNull("错误页应清 pin", store.stored)
            assertTrue(
                "应记录 probe_error_page: " + logs,
                logs.any { it.startsWith("mirror_unpin") && it.contains("reason=probe_error_page") },
            )
        }
    }

    /** 后台探测进行中用户切线路：旧探测结果必须因 epoch 变化被丢弃。 */
    @Test
    fun userSwitchDuringProbeDiscardsStaleAdopt() {
        GatedHttpServer(appBody).use { slow ->
            StubHttpServer(appBody).use { other ->
                // slow 只放官方档，保证探测先打它（other 放兜底档，切换时仍可从合并名单选中）。
                freshRegistry(listOf(slow.baseUrl), listOf(other.baseUrl))
                val store = MemoryPinStore()
                val client = newClient(store)

                var picked: String? = null
                var failure: Throwable? = null
                val probeThread = Thread {
                    try {
                        picked = client.probeMirrors()
                    } catch (t: Throwable) {
                        failure = t
                    }
                }
                probeThread.isDaemon = true
                probeThread.start()
                assertTrue("探测请求未发出", slow.awaitAccepted())

                assertTrue("用户切换线路应成功", client.switchMirror(other.baseUrl))
                slow.release()
                probeThread.join(10_000L)

                assertNull("探测不应失败: " + failure, failure)
                assertEquals("旧探测结果不得覆盖用户选择", other.baseUrl, client.mirror)
                assertEquals("探测应返回用户当前线路", other.baseUrl, picked)
                assertTrue(
                    "应因 epoch 变化丢弃旧探测结果: " + logs,
                    logs.any { it.startsWith("mirror_epoch_drop") && it.contains("stage=probe") },
                )
                assertEquals(other.baseUrl, store.stored)
            }
        }
    }

    /** 后台搜索进行中用户切线路：搜索成功也不得把旧线路写回 mirror/prefs。 */
    @Test
    fun userSwitchDuringSearchDiscardsStaleAdopt() {
        GatedHttpServer(emptySearchBody).use { slow ->
            StubHttpServer(appBody).use { other ->
                freshRegistry(listOf(slow.baseUrl), listOf(other.baseUrl))
                val store = MemoryPinStore()
                val client = newClient(store)
                client.mirror = slow.baseUrl

                var failure: Throwable? = null
                val searchThread = Thread {
                    try {
                        client.search("android")
                    } catch (t: Throwable) {
                        failure = t
                    }
                }
                searchThread.isDaemon = true
                searchThread.start()
                assertTrue("搜索请求未发出", slow.awaitAccepted())

                assertTrue("用户切换线路应成功", client.switchMirror(other.baseUrl))
                slow.release()
                searchThread.join(10_000L)

                assertNull("搜索不应失败: " + failure, failure)
                assertEquals("旧搜索结果不得覆盖用户选择", other.baseUrl, client.mirror)
                assertTrue(
                    "应因 epoch 变化丢弃旧搜索结果: " + logs,
                    logs.any { it.startsWith("mirror_epoch_drop") && it.contains("stage=search") },
                )
            }
        }
    }

    /** 408 超时 / 429 限流是瞬时状态：只记失败次数，不得清 pin。 */
    @Test
    fun timeoutAndRateLimitKeepPin() {
        for (code in listOf(408, 429)) {
            StubHttpServer("transient", code).use { server ->
                freshRegistry(listOf(server.baseUrl))
                val store = MemoryPinStore(stored = server.baseUrl)
                val client = newClient(store)

                try {
                    client.probeMirrors()
                    throw AssertionError("HTTP " + code + " 线路必须抛 NO_MIRROR")
                } catch (e: ZlibException) {
                    assertEquals(ZlibException.Kind.NO_MIRROR, e.kind)
                }

                assertEquals("HTTP " + code + " 是瞬时状态，不得清 pin", server.baseUrl, store.stored)
                assertTrue(
                    "应记录保留 pin: " + logs,
                    logs.any { it.startsWith("mirror_pin_keep") && it.contains("reason=transient") },
                )
            }
        }
    }

    /** 首页网关是确定性非正式站：清 pin，避免每轮 preferred 阶段白耗 4s 预算。 */
    @Test
    fun gatewayPinClearedToAvoidBudgetWaste() {
        StubHttpServer(appBody).use { home ->
            HomeRedirectGateway(home.baseUrl + "/").use { gateway ->
                freshRegistry(listOf(gateway.baseUrl))
                val store = MemoryPinStore(stored = gateway.baseUrl)
                val client = newClient(store)

                try {
                    client.probeMirrors()
                    throw AssertionError("首页网关必须抛 NO_MIRROR")
                } catch (e: ZlibException) {
                    assertEquals(ZlibException.Kind.NO_MIRROR, e.kind)
                }

                assertNull("网关 pin 应清掉，不再每轮白探", store.stored)
                assertTrue(
                    "应记录 probe_gateway 清 pin: " + logs,
                    logs.any { it.startsWith("mirror_unpin") && it.contains("reason=probe_gateway") },
                )
            }
        }
    }

    /** 用户主动切换的 pin 必须走同步写（commit），不能只 apply。 */
    @Test
    fun switchMirrorWritesPinSynchronously() {
        StubHttpServer(appBody).use { a ->
            StubHttpServer(appBody).use { b ->
                freshRegistry(listOf(a.baseUrl, b.baseUrl))
                val store = MemoryPinStore()
                val client = newClient(store)

                assertTrue(client.switchMirror(b.baseUrl))

                assertEquals("用户切换必须同步落盘 pin", 1, store.syncWrites)
                assertEquals(b.baseUrl, store.stored)
            }
        }
    }
}
