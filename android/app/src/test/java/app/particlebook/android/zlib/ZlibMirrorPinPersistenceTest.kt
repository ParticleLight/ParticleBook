package app.particlebook.android.zlib

import java.io.Closeable
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T24①：pin 持久化（进程重启后仍保留）。
 *
 * 纯 JVM：用 [ZlibMirrorPinStore] 内存实现替代 SharedPreferences；不联网（回环 HTTP 桩）。
 */
class ZlibMirrorPinPersistenceTest {

    private val logs = ArrayList<String>()

    /** 正式站特征：探测只采纳含 z-bookcard 的页面。 */
    private val appBody = "<html><title>Z-Library</title><z-bookcard id=\"1\"></z-bookcard></html>"

    private class MemoryPinStore(var stored: String? = null) : ZlibMirrorPinStore {
        override fun readPin(): String? = stored

        override fun writePin(url: String?) {
            stored = url
        }
    }

    /** 一次性回环 HTTP 桩：只应答一个 GET，返回 200 + 固定 body。 */
    private class OneShotHttpServer(private val body: String) : Closeable {
        private val server = ServerSocket(0)
        val baseUrl: String = "http://127.0.0.1:" + server.localPort
        val hits = AtomicInteger()
        private val thread = Thread {
            try {
                server.soTimeout = 5_000
                server.accept().use { socket ->
                    hits.incrementAndGet()
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

    private fun freshRegistry(now: Long, official: List<String>? = null) {
        ZlibMirrorRegistry.resetForTest(official, emptyList())
        ZlibMirrorRegistry.ensureLoaded(null, now)
        // 导航缓存先标新鲜：避免触发 wwwnav 后台抓取（单测不联网）。
        ZlibMirrorRegistry.recordNavSuccess(now, ZlibMirrorList.NAV_URL, emptyList(), emptyList())
    }

    private fun newClient(store: ZlibMirrorPinStore?): ZlibClient {
        logs.clear()
        val client = ZlibClient()
        client.testLogger = { line -> logs.add(line) }
        client.navFetcherForTest = { null }
        if (store != null) client.pinStoreForTest = store
        return client
    }

    @Test
    fun pinSurvivesClientRecreation() {
        freshRegistry(0L, listOf("https://a.page", "https://b.page"))
        val store = MemoryPinStore()
        val first = newClient(store)

        assertTrue(first.switchMirror("https://b.page"))
        assertEquals("switchMirror 必须把 pin 写盘", "https://b.page", store.stored)

        // 模拟进程重启：新 client 只拿到同一份持久化 pin。
        val restarted = newClient(store)
        assertNull("新 client 内存里没有 mirror", restarted.mirror)
        val rows = restarted.mirrorItems()
        assertTrue("重启后弹层应标出 pin", rows.any { it.url == "https://b.page" && it.pinned })
        val info = restarted.mirrorInfo()
        assertTrue("current 应回落到持久化 pin", info.index > 0)
        assertEquals("https://b.page", info.mirrors[info.index - 1])
    }

    @Test
    fun clearCachedMirrorClearsPersistedPin() {
        freshRegistry(0L, listOf("https://a.page", "https://b.page"))
        val store = MemoryPinStore()
        val first = newClient(store)
        assertTrue(first.switchMirror("https://b.page"))
        assertEquals("https://b.page", store.stored)

        first.clearCachedMirror()

        assertNull("清缓存线路必须同时清磁盘 pin", store.stored)
        val restarted = newClient(store)
        assertFalse("重启后不应再有 pin", restarted.mirrorItems().any { it.pinned })
        assertNull(restarted.mirror)
    }

    @Test
    fun probePrefersPersistedPinAfterRestart() {
        OneShotHttpServer(appBody).use { a ->
            OneShotHttpServer(appBody).use { b ->
                freshRegistry(0L, listOf(a.baseUrl, b.baseUrl))
                val store = MemoryPinStore()
                val first = newClient(store)
                assertTrue(first.switchMirror(b.baseUrl))

                // 重启后探测：第 0 阶段应优先 pin，A 一次都不该被探。
                val restarted = newClient(store)
                val picked = restarted.probeMirrors()

                assertEquals("重启后探测必须优先持久化 pin", b.baseUrl, picked)
                assertEquals("pin 可用时不应再探 A", 0, a.hits.get())
                assertTrue("pin 不应因探测成功被清", store.stored == b.baseUrl)
            }
        }
    }
}
