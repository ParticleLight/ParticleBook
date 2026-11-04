package app.particlebook.android.zlib

import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T24③：探测整轮硬上限配置化（默认 12s 不变；测试注入小预算验证整轮被截断）。 */
class ZlibMirrorProbeBudgetTest {

    private val logs = ArrayList<String>()

    /** 悬停回环服务端：接受连接但永不响应，逼客户端等到 deadline 截断。 */
    private class HangingServer : Closeable {
        private val server = ServerSocket(0)
        val baseUrl: String = "http://127.0.0.1:" + server.localPort
        private val sockets = Collections.synchronizedList(ArrayList<Socket>())
        private val thread = Thread {
            try {
                server.soTimeout = 5_000
                while (true) {
                    sockets.add(server.accept())
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
            synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
        }
    }

    private fun freshRegistry(now: Long, official: List<String>? = null) {
        ZlibMirrorRegistry.resetForTest(official, emptyList())
        ZlibMirrorRegistry.ensureLoaded(null, now)
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
    fun defaultProbeBudgetStaysTwelveSeconds() {
        assertEquals("默认硬上限必须保持 12s", 12_000L, ZlibClient().probeBudgetMs)
    }

    @Test
    fun configurableBudgetShortCircuitsWholeProbe() {
        HangingServer().use { hang ->
            freshRegistry(0L, listOf(hang.baseUrl))
            val client = newClient()
            client.probeBudgetMs = 1_500L

            val started = System.nanoTime()
            try {
                client.probeMirrors()
                throw AssertionError("悬停服务端必须探测失败")
            } catch (e: ZlibException) {
                assertEquals(ZlibException.Kind.NO_MIRROR, e.kind)
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L

            assertTrue("整轮探测应被 1.5s 预算截断而不是等满 12s，实际 " + elapsedMs + "ms", elapsedMs < 6_000L)
            assertTrue(logs.toString(), logs.any { it.startsWith("mirror_tier") && it.contains("budget_ms=1500") })
            assertTrue(logs.toString(), logs.any { it.startsWith("mirror_probe_abort") && it.contains("budget_ms=1500") })
        }
    }
}
