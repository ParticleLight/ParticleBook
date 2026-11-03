package app.particlebook.android.zlib

import java.io.Closeable
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * task-8 / B3+B7：首包失败自动换线、翻页不换线、全失败 NO_MIRROR、失败清 pin、记忆线路优先探测。
 * 全部本地回环桩，不联网；导航源用 navFetcherForTest 短路。
 */
class ZlibMirrorFailoverTest {

    private val logs = ArrayList<String>()
    private val now = System.currentTimeMillis()

    private val appBody = "<html><title>Z-Library</title><z-bookcard id=\"1\"></z-bookcard></html>"
    private val emptySearchBody = "<html><body><div class='notfound'>nothing has been found</div></body></html>"

    /** 可复用（多连接）回环 HTTP 桩：固定状态码 + body，记录命中次数。 */
    private class StubHttpServer(
        private val body: String,
        private val status: Int = 200,
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
                        val reason = if (status == 200) "OK" else "ERROR"
                        val head = "HTTP/1.1 " + status + " " + reason + "\r\n" +
                            "Content-Type: text/html; charset=utf-8" + "\r\n" +
                            "Content-Length: " + bytes.size + "\r\n" +
                            "Connection: close" + "\r\n\r\n"
                        it.getOutputStream().write(head.toByteArray(Charsets.UTF_8))
                        it.getOutputStream().write(bytes)
                        it.getOutputStream().flush()
                    }
                }
            } catch (t: Throwable) {
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

    private fun freshRegistry(
        official: List<String>,
        fallback: List<String> = emptyList(),
        at: Long = now,
    ) {
        ZlibMirrorRegistry.resetForTest(official, fallback)
        ZlibMirrorRegistry.ensureLoaded(null, at)
        // 导航缓存标新鲜：避免成功分支/探测阶段后台抓 wwwnav。
        ZlibMirrorRegistry.recordNavSuccess(at, ZlibMirrorList.NAV_URL, emptyList(), emptyList())
    }

    private fun newClient(): ZlibClient {
        logs.clear()
        val client = ZlibClient()
        client.testLogger = { line -> logs.add(line) }
        client.navFetcherForTest = { null }
        return client
    }

    @Test
    fun firstPageFailureFailsOverToNextLine() {
        StubHttpServer("boom", 500).use { dead ->
            StubHttpServer(emptySearchBody).use { alive ->
                freshRegistry(listOf(dead.baseUrl, alive.baseUrl))
                val client = newClient()
                client.mirror = dead.baseUrl

                val page = client.search("android")

                assertEquals(0, page.books.size)
                assertEquals("成功后当前线路切到 B", alive.baseUrl, client.mirror)
                assertTrue(logs.toString(), logs.any { it.startsWith("mirror_advance") && it.contains("from=" + "127.0.0.1") })
                assertTrue(logs.toString(), logs.any { it.startsWith("mirror_advance") && it.contains("tried=1/2") })
                assertTrue(logs.toString(), logs.any { it.startsWith("mirror_working") })
            }
        }
    }

    @Test
    fun secondPageDoesNotFailOver() {
        StubHttpServer("boom", 500).use { dead ->
            StubHttpServer(emptySearchBody).use { alive ->
                freshRegistry(listOf(dead.baseUrl, alive.baseUrl))
                val client = newClient()
                client.mirror = dead.baseUrl

                try {
                    client.search("android", page = 2)
                    throw AssertionError("page=2 必须单次请求失败，不能换线")
                } catch (e: ZlibException) {
                    assertEquals(ZlibException.Kind.HTTP, e.kind)
                    assertEquals(500, e.httpCode)
                }
                assertEquals("翻页失败不改当前线路", dead.baseUrl, client.mirror)
                assertEquals("不得打到下一条线路", 0, alive.hits.get())
            }
        }
    }

    @Test
    fun parseErrorDoesNotFailOver() {
        // 200 但既无结果卡也无空态标记 => PARSE；这是页面结构问题，换线救不了，不走 B3 换线。
        StubHttpServer("<html><body>unexpected page</body></html>").use { broken ->
            StubHttpServer(emptySearchBody).use { alive ->
                freshRegistry(listOf(broken.baseUrl, alive.baseUrl))
                val client = newClient()
                client.mirror = broken.baseUrl

                try {
                    client.search("android")
                    throw AssertionError("结构不对必须抛 PARSE")
                } catch (e: ZlibException) {
                    assertEquals(ZlibException.Kind.PARSE, e.kind)
                }
                assertEquals("PARSE 不换线", 0, alive.hits.get())
                assertEquals(broken.baseUrl, client.mirror)
            }
        }
    }

    @Test
    fun allLinesFailingThrowsNoMirror() {
        StubHttpServer("boom", 500).use { deadA ->
            StubHttpServer("boom", 500).use { deadB ->
                freshRegistry(listOf(deadA.baseUrl, deadB.baseUrl))
                val client = newClient()
                client.mirror = deadA.baseUrl

                try {
                    client.search("android")
                    throw AssertionError("全部线路失败必须抛 NO_MIRROR")
                } catch (e: ZlibException) {
                    assertEquals(ZlibException.Kind.NO_MIRROR, e.kind)
                }
                assertEquals(1, deadA.hits.get())
                assertEquals(1, deadB.hits.get())
                assertTrue(logs.toString(), logs.any {
                    it.startsWith("probe_result") && it.contains("fail_all") && it.contains("stage=search_failover")
                })
            }
        }
    }

    @Test
    fun demotedLineIsSkippedWhenAnotherLineUsable() {
        StubHttpServer("boom", 500).use { dead ->
            StubHttpServer("boom", 500).use { demoted ->
                StubHttpServer(emptySearchBody).use { alive ->
                    freshRegistry(listOf(dead.baseUrl, demoted.baseUrl, alive.baseUrl))
                    // B 先被降权：换线时必须跳过它直接试 C。
                    ZlibMirrorRegistry.recordFailure(demoted.baseUrl, "network", now)
                    val client = newClient()
                    client.mirror = dead.baseUrl

                    client.search("android")

                    assertEquals(alive.baseUrl, client.mirror)
                    assertEquals("降权线路不应被请求", 0, demoted.hits.get())
                }
            }
        }
    }

    @Test
    fun failedLineReleasesPin() {
        StubHttpServer("boom", 500).use { dead ->
            StubHttpServer(emptySearchBody).use { alive ->
                freshRegistry(listOf(dead.baseUrl, alive.baseUrl))
                val client = newClient()
                assertTrue(client.switchMirror(0))
                assertEquals(dead.baseUrl, client.mirror)
                assertTrue(client.mirrorItems().any { it.pinned })

                client.search("android")

                assertEquals(alive.baseUrl, client.mirror)
                assertTrue(logs.toString(), logs.any { it.startsWith("mirror_unpin") && it.contains("reason=search_fail") })
                assertFalse("失败线路不能继续是 pin", client.mirrorItems().any { it.pinned })
            }
        }
    }

    @Test
    fun probePrefersLastWorkingLine() {
        StubHttpServer(appBody).use { alternative ->
            StubHttpServer(appBody).use { lastWorking ->
                freshRegistry(listOf(alternative.baseUrl, lastWorking.baseUrl))
                ZlibMirrorRegistry.recordSuccess(
                    lastWorking.baseUrl, null, 100L, now - 1_000L, ZlibMirrorRegistry.Rank.APP,
                )
                val client = newClient()

                val picked = client.probeMirrors()

                assertEquals(lastWorking.baseUrl, picked)
                assertEquals("记忆线路优先，A 不应被探", 0, alternative.hits.get())
                assertTrue(logs.toString(), logs.any { it.startsWith("mirror_pick") && it.contains("source=last_working") })
            }
        }
    }

    @Test
    fun probePrefersPinnedLine() {
        StubHttpServer(appBody).use { alternative ->
            StubHttpServer(appBody).use { pinned ->
                freshRegistry(listOf(alternative.baseUrl, pinned.baseUrl))
                val client = newClient()
                assertTrue(client.switchMirror(1))
                assertEquals(pinned.baseUrl, client.mirror)

                val picked = client.probeMirrors()

                assertEquals(pinned.baseUrl, picked)
                assertEquals(0, alternative.hits.get())
                assertTrue(logs.toString(), logs.any { it.startsWith("mirror_pick") && it.contains("source=pin") })
            }
        }
    }

    @Test
    fun nonAppPageIsAdoptedAsSoftFallbackWithRank() {
        StubHttpServer("<html><body>Z-Library mirrors guide</body></html>").use { promo ->
            freshRegistry(listOf(promo.baseUrl))
            val client = newClient()

            val picked = client.probeMirrors()

            assertEquals(promo.baseUrl, picked)
            assertTrue(logs.toString(), logs.any {
                it.startsWith("mirror_probe_soft") && it.contains("rank=page")
            })
            assertTrue(logs.toString(), logs.any {
                it.startsWith("mirror_pick") && it.contains("rank=page") && it.contains("soft=1")
            })
            val entry = ZlibMirrorRegistry.snapshot().entries.first { it.url == promo.baseUrl }
            assertEquals(ZlibMirrorRegistry.Rank.PAGE, entry.rank)
            assertTrue("软兜底也记成功 TTL", entry.lastOkAt > 0L)
        }
    }
}
