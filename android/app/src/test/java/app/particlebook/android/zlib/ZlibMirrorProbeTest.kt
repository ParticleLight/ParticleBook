package app.particlebook.android.zlib

import java.io.Closeable
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R7-H 探测分层集成测试（本地桩）：官方优先；官方不可达时降级到兜底并 demote。
 * 不联网：导航源用 navFetcherForTest 短路，镜像用回环 HTTP 桩。
 */
class ZlibMirrorProbeTest {

    private val logs = ArrayList<String>()

    /** 正式站特征：T10 P1-1 起非 APP 页不再软兜底，桩必须返回带 z-bookcard 的正式站页面。 */
    private val appBody = "<html><title>Z-Library</title><z-bookcard id=\"1\"></z-bookcard></html>"

    /** 一次性回环 HTTP 桩：只应答一个 GET，返回 200 + 固定 body。 */
    private class OneShotHttpServer(private val body: String) : Closeable {
        private val server = ServerSocket(0)
        val baseUrl: String = "http://127.0.0.1:" + server.localPort
        private val thread = Thread {
            try {
                server.soTimeout = 5_000
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val head = "HTTP/1.1 200 OK" + "\r\n" +
                        "Content-Type: text/html; charset=utf-8" + "\r\n" +
                        "Content-Length: " + bytes.size + "\r\n" +
                        "Connection: close" + "\r\n\r\n"
                    socket.getOutputStream().write(head.toByteArray(Charsets.UTF_8))
                    socket.getOutputStream().write(bytes)
                    socket.getOutputStream().flush()
                }
            } catch (t: Throwable) {
                // server.close() 中断 accept：测试收尾正常现象。
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

    /** 多请求回环站点桩：任意路径都返回 200 + 固定 body。 */
    private class SiteHttpServer(private val body: String) : Closeable {
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

    /** task-33 现场形态：任意深链 301 到站点首页（path 被丢弃）。 */
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

    private fun freshRegistry(now: Long, official: List<String>? = null, fallback: List<String>? = null) {
        ZlibMirrorRegistry.resetForTest(official, fallback)
        ZlibMirrorRegistry.ensureLoaded(null, now)
        // 导航缓存先标新鲜：避免成功分支触发后台抓 wwwnav。
        ZlibMirrorRegistry.recordNavSuccess(now, ZlibMirrorList.NAV_URL, emptyList(), emptyList())
    }

    private fun newClient(): ZlibClient {
        logs.clear()
        val client = ZlibClient()
        client.testLogger = { line -> logs.add(line) }
        client.navFetcherForTest = { null }
        return client
    }

    @Test
    fun officialWinsWhenReachable() {
        OneShotHttpServer(appBody).use { server ->
            freshRegistry(0L, listOf(server.baseUrl), listOf("http://127.0.0.1:1"))
            val picked = newClient().probeMirrors()

            assertEquals(server.baseUrl, picked)
            assertTrue(logs.toString(), logs.any { it.startsWith("mirror_pick") && it.contains("tier=official") })
            assertTrue(logs.toString(), logs.any { it.startsWith("mirror_probe") && it.contains("kind=http") && it.contains("code=200") })
            assertTrue("官方成功后不应降级", logs.none { it.startsWith("mirror_pick") && it.contains("tier=fallback") })
        }
    }

    @Test
    fun officialUnreachableDegradesToFallback() {
        val closedPort = ServerSocket(0).use { it.localPort }
        OneShotHttpServer(appBody).use { server ->
            freshRegistry(
                0L,
                listOf("http://127.0.0.1:" + closedPort),
                listOf(server.baseUrl),
            )
            val picked = newClient().probeMirrors()

            assertEquals(server.baseUrl, picked)
            assertTrue(logs.toString(), logs.any { it.startsWith("mirror_probe") && it.contains("kind=network") })
            assertTrue(logs.toString(), logs.any { it.startsWith("mirror_demote") && it.contains("reason=network") })
            assertTrue(logs.toString(), logs.any { it.startsWith("mirror_pick") && it.contains("tier=fallback") })
        }
    }

    @Test
    fun soft404NavPageIsRejected() {
        // T24④：导航源返回 200 但内容不是导航页（软 404/SPA 壳）时不能当成功、不能把垃圾链接写进动态名单。
        val now = 0L
        freshRegistry(now, listOf("http://127.0.0.1:1"), emptyList())
        // 旧导航缓存已过期；旧动态名单为空（确保后续不会访问真实镜像）。
        ZlibMirrorRegistry.recordNavSuccess(
            now - ZlibMirrorRegistry.NAV_TTL_MS - 1_000L,
            ZlibMirrorList.NAV_URL,
            emptyList(),
            emptyList(),
        )
        val client = newClient()
        client.navFetcherForTest = { "<html><body><h1>404 Not Found</h1></body></html>" }

        try {
            client.probeMirrors()
            throw AssertionError("没有可用镜像必须抛 NO_MIRROR")
        } catch (e: ZlibException) {
            assertEquals(ZlibException.Kind.NO_MIRROR, e.kind)
        }

        val nav = ZlibMirrorRegistry.snapshot().nav
        assertFalse("软 404 不能记为导航页抓取成功", nav.ok)
        assertEquals("not_nav", nav.error)
        assertTrue(logs.toString(), logs.any {
            it.startsWith("mirror_nav_fail") && it.contains("reason=not_nav")
        })
    }

    @Test
    fun allCandidatesFailingThrowsNoMirror() {
        val closedA = ServerSocket(0).use { it.localPort }
        val closedB = ServerSocket(0).use { it.localPort }
        freshRegistry(
            0L,
            listOf("http://127.0.0.1:" + closedA),
            listOf("http://127.0.0.1:" + closedB),
        )
        try {
            newClient().probeMirrors()
            throw AssertionError("全失败必须抛 NO_MIRROR")
        } catch (e: ZlibException) {
            assertEquals(ZlibException.Kind.NO_MIRROR, e.kind)
        }
        assertTrue(logs.toString(), logs.any { it.startsWith("probe_result") && it.contains("fail_all") })
    }

    /**
     * T37/task-33：zlib.ch / zlib.re 对任意深链 301 到 z-lib.by 首页（path 被丢弃），
     * 最终 200 且首页含 z-bookcard——不能因此判活。探 /s/test 最终落在根路径 → gateway 降权，
     * 继续选真正可达的线路。
     */
    @Test
    fun redirectToHomeGatewayIsDemotedAndNotPicked() {
        SiteHttpServer(appBody).use { home ->
            HomeRedirectGateway(home.baseUrl + "/").use { gateway ->
                SiteHttpServer(appBody).use { real ->
                    freshRegistry(0L, listOf(gateway.baseUrl), listOf(real.baseUrl))

                    val picked = newClient().probeMirrors()

                    assertEquals("首页网关不能当可用线路", real.baseUrl, picked)
                    assertTrue(
                        "必须判定为 gateway: " + logs,
                        logs.any { it.startsWith("mirror_probe") && it.contains("kind=gateway") },
                    )
                    assertTrue(
                        "必须按 redirect_home 降权: " + logs,
                        logs.any { it.startsWith("mirror_demote") && it.contains("reason=redirect_home") },
                    )
                    assertTrue(
                        "应继续选用真正可达的线路: " + logs,
                        logs.any { it.startsWith("mirror_pick") && it.contains("tier=fallback") },
                    )
                }
            }
        }
    }
}
