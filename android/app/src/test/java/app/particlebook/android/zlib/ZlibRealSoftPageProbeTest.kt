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
}
