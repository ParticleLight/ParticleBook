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
 * task-66 P2-1/P2-3：请求期间用户切换线路后的 stale 结果处理。
 *
 * 覆盖：
 * 1) 旧请求失败时用户已切走再切回：epoch 变化 → 失败清理整体跳过，不清新 pin；
 * 2) 旧探测的非正式站证据不得清新 pin（epoch 校验与清 pin 同锁）；
 * 3) 探测全失败但期间用户换过线路：返回用户当前线路，不抛 stale NO_MIRROR；
 * 4) 搜索全失败但期间用户换过线路：在新线路上重试一次，不弹 stale NO_MIRROR banner。
 */
class ZlibMirrorPinStaleTest {

    private val logs: MutableList<String> = Collections.synchronizedList(ArrayList<String>())
    private val now = System.currentTimeMillis()
    private val appBody = "<html><title>Z-Library</title><z-bookcard id=\"1\"></z-bookcard></html>"
    private val emptySearchBody = "<html><body><div class='notfound'>nothing has been found</div></body></html>"

    private class MemoryPinStore(var stored: String? = null) : ZlibMirrorPinStore {
        override fun readPin(): String? = stored
        override fun writePin(url: String?) {
            stored = url
        }
        override fun writePinNow(url: String?) {
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

    /** 可闸门回环桩：收到请求后放行 [awaitAccepted]，再等 [release] 才应答。 */
    private class GatedHttpServer(
        private val body: String,
        private val status: Int = 200,
    ) : Closeable {
        private val server = ServerSocket(0)
        val baseUrl: String = "http://127.0.0.1:" + server.localPort
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
                    accepted.countDown()
                    released.await(5, TimeUnit.SECONDS)
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

    /** P2-1：A 请求失败期间用户切到 B 又切回 A，旧失败不得清新 pin A。 */
    @Test
    fun staleFailureAfterSwitchBackKeepsNewPin() {
        GatedHttpServer("boom", status = 500).use { a ->
            StubHttpServer(emptySearchBody).use { b ->
                freshRegistry(listOf(a.baseUrl), listOf(b.baseUrl))
                // A 残留 PAGE 证据：让失败清理会尝试清 pin（否则 rank_unproven 分支测不到 epoch 保护）。
                ZlibMirrorRegistry.recordProbeResult(a.baseUrl, ZlibMirrorRegistry.Rank.PAGE, 10L, now)
                val store = MemoryPinStore()
                val client = newClient(store)
                client.mirror = a.baseUrl

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
                assertTrue("搜索请求未发出", a.awaitAccepted())

                assertTrue("切到 B 应成功", client.switchMirror(b.baseUrl))
                assertTrue("切回 A 应成功", client.switchMirror(a.baseUrl))
                a.release()
                searchThread.join(10_000L)

                assertNull("搜索不应失败: " + failure, failure)
                assertEquals("旧失败不得清用户重新选中的 pin", a.baseUrl, store.stored)
                assertEquals("用户选择不得被旧失败覆盖", a.baseUrl, client.mirror)
                assertTrue(
                    "应记录 epoch stale 跳过: " + logs,
                    logs.any { it.startsWith("mirror_release_skip") && it.contains("reason=epoch") },
                )
            }
        }
    }

    /** P2-1：探测拿到 A 的非正式站证据时用户已切走又切回，旧证据不得清 A 的新 pin。 */
    @Test
    fun staleProbeEvidenceAfterSwitchBackKeepsNewPin() {
        GatedHttpServer("not found", status = 404).use { a ->
            StubHttpServer(appBody).use { b ->
                freshRegistry(listOf(a.baseUrl), listOf(b.baseUrl))
                val store = MemoryPinStore(stored = a.baseUrl)
                val client = newClient(store)

                var failure: Throwable? = null
                val probeThread = Thread {
                    try {
                        client.probeMirrors()
                    } catch (t: Throwable) {
                        failure = t
                    }
                }
                probeThread.isDaemon = true
                probeThread.start()
                assertTrue("探测请求未发出", a.awaitAccepted())

                assertTrue("切到 B 应成功", client.switchMirror(b.baseUrl))
                assertTrue("切回 A 应成功", client.switchMirror(a.baseUrl))
                a.release()
                probeThread.join(10_000L)

                assertEquals("旧探测证据不得清新 pin", a.baseUrl, store.stored)
                assertTrue(
                    "应记录 epoch stale 放弃清 pin: " + logs,
                    logs.any {
                        it.startsWith("mirror_epoch_drop") && it.contains("stage=probe_pin_clear")
                    },
                )
            }
        }
    }

    /** P2-3：探测全失败但期间用户切过线路：返回当前线路，不抛 stale NO_MIRROR。 */
    @Test
    fun staleProbeAllFailReturnsCurrentMirror() {
        GatedHttpServer("boom", status = 500).use { a ->
            StubHttpServer("boom", status = 500).use { b ->
                freshRegistry(listOf(a.baseUrl), listOf(b.baseUrl))
                val store = MemoryPinStore(stored = a.baseUrl)
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
                assertTrue("探测请求未发出", a.awaitAccepted())

                assertTrue("切到 B 应成功", client.switchMirror(b.baseUrl))
                a.release()
                probeThread.join(10_000L)

                assertNull("不得抛 stale NO_MIRROR: " + failure, failure)
                assertEquals("应返回用户当前线路", b.baseUrl, picked)
                assertEquals(b.baseUrl, store.stored)
                assertTrue(
                    "应记录 stale 返回当前线路: " + logs,
                    logs.any { it.startsWith("mirror_probe_stale") },
                )
            }
        }
    }

    /** P2-3：搜索全失败但期间用户切过线路：在新线路上重试成功，不弹 stale NO_MIRROR。 */
    @Test
    fun staleSearchRetriesCurrentMirror() {
        GatedHttpServer("boom", status = 500).use { a ->
            StubHttpServer(emptySearchBody).use { b ->
                freshRegistry(listOf(a.baseUrl), listOf(b.baseUrl))
                // B 残留 PAGE 证据：自动换线不会主动选它，确保走到「stale 后在新线路上重试」分支。
                ZlibMirrorRegistry.recordProbeResult(b.baseUrl, ZlibMirrorRegistry.Rank.PAGE, 10L, now)
                val store = MemoryPinStore()
                val client = newClient(store)
                client.mirror = a.baseUrl

                var failure: Throwable? = null
                var page: ZSearchPage? = null
                val searchThread = Thread {
                    try {
                        page = client.search("android")
                    } catch (t: Throwable) {
                        failure = t
                    }
                }
                searchThread.isDaemon = true
                searchThread.start()
                assertTrue("搜索请求未发出", a.awaitAccepted())

                assertTrue("切到 B 应成功", client.switchMirror(b.baseUrl))
                a.release()
                searchThread.join(10_000L)

                assertNull("不得抛 stale NO_MIRROR: " + failure, failure)
                assertTrue("应返回搜索结果", page != null)
                assertEquals("应停留在用户选择的线路", b.baseUrl, client.mirror)
                assertTrue(
                    "应记录 stale 后在新线路重试: " + logs,
                    logs.any { it.startsWith("search_stale_retry") },
                )
            }
        }
    }

    /** task-75：跨 host adopt 的 finalRoot 不在注册表时，也要能 switchMirror(current) 并标成 current。 */
    @Test
    fun adoptedFinalRootIsSwitchableAndListedOnce() {
        StubHttpServer(appBody).use { home ->
            HomeRedirectGateway(home.baseUrl + "/s/test").use { gateway ->
                freshRegistry(listOf(gateway.baseUrl))
                val store = MemoryPinStore()
                val client = newClient(store)

                val picked = client.probeMirrors()
                assertEquals(home.baseUrl, picked)
                assertTrue("finalRoot 应能直接切换（不能因不在注册表返回 false）", client.switchMirror(picked))
                assertEquals(home.baseUrl, client.mirror)
                assertEquals("pin 应指向 finalRoot", home.baseUrl, store.stored)

                val info = client.mirrorInfo()
                val count = info.mirrors.count {
                    it.trim().trimEnd('/').lowercase() == home.baseUrl.lowercase()
                }
                assertEquals("名单里 finalRoot 只能出现一次: " + info.mirrors, 1, count)
                assertTrue("mirrorInfo 应把 finalRoot 标成 current: " + info, info.index > 0)
                assertEquals(home.baseUrl, info.mirrors[info.index - 1].trimEnd('/'))

                // pin 到 finalRoot 后新客户端冷启应优先直接用它，不因不在注册表而失效。
                val coldClient = newClient(store)
                assertEquals("pin finalRoot 应可作为优选线路", home.baseUrl, coldClient.probeMirrors())
            }
        }
    }

    /** task-75：finalRoot 本身是官方 seed 时，并入名单不得产生重复，官方元数据/rank 保持原样。 */
    @Test
    fun adoptedFinalRootKeepsOfficialSeedMetadata() {
        StubHttpServer(appBody).use { home ->
            HomeRedirectGateway(home.baseUrl + "/s/test").use { gateway ->
                freshRegistry(listOf(gateway.baseUrl, home.baseUrl))
                val store = MemoryPinStore()
                val client = newClient(store)

                assertEquals("跨 host 跳转应采用官方站 finalRoot", home.baseUrl, client.probeMirrors())
                val homeRows = client.mirrorRows().filter {
                    it.url.trim().trimEnd('/').lowercase() == home.baseUrl.lowercase()
                }
                assertEquals("官方 seed 在名单里只能有一行: " + client.mirrorRows(), 1, homeRows.size)
                assertEquals("官方 seed 的 tier 不得被覆盖", "official", homeRows.single().tier)
                assertTrue("finalRoot 行应标成 current", homeRows.single().current)
                assertTrue(client.switchMirror(home.baseUrl))
                assertEquals(home.baseUrl, store.stored)
            }
        }
    }

    /** P2-4：跨 host 跳转（网关 -> 正式站）时采用最终落点，后续请求省一跳。 */
    @Test
    fun crossHostRedirectAdoptsFinalRoot() {
        StubHttpServer(appBody).use { home ->
            HomeRedirectGateway(home.baseUrl + "/s/test").use { gateway ->
                freshRegistry(listOf(gateway.baseUrl))
                val store = MemoryPinStore()
                val client = newClient(store)

                val picked = client.probeMirrors()

                assertEquals("应采用跳转后的最终站，而不是网关", home.baseUrl, picked)
                assertEquals(home.baseUrl, client.mirror)
                assertTrue(
                    "应记录 adopt=final: " + logs,
                    logs.any { it.startsWith("mirror_pick") && it.contains("adopt=final") },
                )
            }
        }
    }
}
