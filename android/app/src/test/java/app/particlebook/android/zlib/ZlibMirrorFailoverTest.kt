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
                // T17：搜索解析成功要记 rank=APP（后续 PARSE 时不再被当成未探明软页误清）。
                val aliveEntry = ZlibMirrorRegistry.snapshot().entries.first { it.url == alive.baseUrl }
                assertEquals(ZlibMirrorRegistry.Rank.APP, aliveEntry.rank)
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
                // 该线已被探测确认为正式站（rank=APP）：PARSE 视为页面结构问题，不换线（T10 P1-2 的例外）。
                ZlibMirrorRegistry.recordSuccess(
                    broken.baseUrl, null, 10L, now, ZlibMirrorRegistry.Rank.APP,
                )
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
                // T17：清 pin 只对「明确非正式站」生效；这里给 dead 一个 OTHER 证据，保留清 pin 路径。
                ZlibMirrorRegistry.recordProbeResult(dead.baseUrl, ZlibMirrorRegistry.Rank.OTHER, 5L, now)
                val client = newClient()
                assertTrue(client.switchMirror(dead.baseUrl))
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
    fun nonAppPageIsNotAdoptedAndRaisesNoMirror() {
        // T10 P1-1：200 推广/导航页只记 rank，不 adopt、不写 lastOkAt；整轮无正式站 → NO_MIRROR。
        // 正文刻意带 z-lib.fm / singlelogin（旧 APP_MARKERS 会误判成正式站）。
        StubHttpServer(
            "<html><body>Welcome! Visit z-lib.fm or singlelogin.re - Z-Library mirrors guide</body></html>",
        ).use { promo ->
            freshRegistry(listOf(promo.baseUrl))
            val client = newClient()

            try {
                client.probeMirrors()
                throw AssertionError("只有软页时必须 NO_MIRROR")
            } catch (e: ZlibException) {
                assertEquals(ZlibException.Kind.NO_MIRROR, e.kind)
            }

            assertEquals("软页不得被 adopt", null, client.mirror)
            assertTrue(logs.toString(), logs.any { it.startsWith("mirror_probe_soft") && it.contains("rank=page") })
            assertTrue(logs.toString(), logs.any { it.startsWith("mirror_probe_soft") && it.contains("soft_only=1") })
            assertFalse(logs.toString(), logs.any { it.startsWith("mirror_pick") })
            val entry = ZlibMirrorRegistry.snapshot().entries.first { it.url == promo.baseUrl }
            assertEquals(ZlibMirrorRegistry.Rank.PAGE, entry.rank)
            assertEquals("软页不得写 lastOkAt", 0L, entry.lastOkAt)
            assertFalse(client.mirrorRows().first { it.url == promo.baseUrl }.successFresh)
        }
    }

    @Test
    fun softLineGarbageSearchFailsOverThenNoMirror() {
        // T10 P1-2：已判推广页的线路搜索返回 200 SPA 壳 → PARSE 按 transient 换线；
        // 下一条 500 也失败 → NO_MIRROR。软页（PAGE）清记忆；UNKNOWN 死线只换线，不清记忆（T17）。
        StubHttpServer("<html><body>just a SPA shell</body></html>").use { soft ->
            StubHttpServer("boom", 500).use { dead ->
                freshRegistry(listOf(soft.baseUrl, dead.baseUrl))
                ZlibMirrorRegistry.recordProbeResult(soft.baseUrl, ZlibMirrorRegistry.Rank.PAGE, 120L, now)
                val client = newClient()
                client.mirror = soft.baseUrl

                try {
                    client.search("android")
                    throw AssertionError("软页+死线必须抛 NO_MIRROR")
                } catch (e: ZlibException) {
                    assertEquals(ZlibException.Kind.NO_MIRROR, e.kind)
                }

                assertEquals("必须换到下一条候选", 1, dead.hits.get())
                assertEquals("软页只被搜一次", 1, soft.hits.get())
                assertEquals("失败线不能留在 mirror", null, client.mirror)
                assertTrue(logs.toString(), logs.any {
                    it.startsWith("mirror_advance") && it.contains("reason=parse_non_app")
                })
            }
        }
    }

    @Test
    fun secondPageParseOnNonAppLineReleasesMirror() {
        // T10 P1-3：翻页不换线，但 PARSE 且当前线不是正式站 → 清 mirror/pin/prefs，下次重探。
        StubHttpServer("<html><body>SPA shell</body></html>").use { soft ->
            freshRegistry(listOf(soft.baseUrl))
            ZlibMirrorRegistry.recordProbeResult(soft.baseUrl, ZlibMirrorRegistry.Rank.PAGE, 10L, now)
            val client = newClient()
            client.mirror = soft.baseUrl

            try {
                client.search("android", page = 2)
                throw AssertionError("翻页 PARSE 必须抛出")
            } catch (e: ZlibException) {
                assertEquals(ZlibException.Kind.PARSE, e.kind)
            }

            assertEquals("非正式站的镜像记忆必须清掉", null, client.mirror)
            assertTrue(logs.toString(), logs.any {
                it.startsWith("mirror_release") && it.contains("reason=parse_non_app")
            })
        }
    }

    @Test
    fun probeDoesNotPreferNonAppLastWorking() {
        // T10 P2-5（客户端侧）：lastWorking 只取 rank==APP；软页的旧 lastOkAt 不得走 last_working 优先通道。
        StubHttpServer(appBody).use { app ->
            StubHttpServer("<html><body>Z-Library mirrors guide</body></html>").use { soft ->
                freshRegistry(listOf(soft.baseUrl, app.baseUrl))
                ZlibMirrorRegistry.recordSuccess(
                    soft.baseUrl, null, 50L, now - 1_000L, ZlibMirrorRegistry.Rank.APP,
                )
                ZlibMirrorRegistry.recordProbeResult(soft.baseUrl, ZlibMirrorRegistry.Rank.PAGE, 50L, now)
                val client = newClient()

                val picked = client.probeMirrors()

                assertEquals("最终必须选正式站", app.baseUrl, picked)
                assertFalse(logs.toString(), logs.any { it.contains("source=last_working") })
            }
        }
    }

    @Test
    fun unknownLineParseKeepsMirrorAndPin() {
        // T17 P2：UNKNOWN 线路 PARSE 不能当成「非正式站」证据；pin/mirror 必须保留，交给换线/重探。
        StubHttpServer("<html><body>unexpected page</body></html>").use { unknown ->
            freshRegistry(listOf(unknown.baseUrl))
            val client = newClient()
            assertTrue(client.switchMirror(unknown.baseUrl))

            try {
                client.search("android")
                throw AssertionError("PARSE 且无其它线路必须 NO_MIRROR")
            } catch (e: ZlibException) {
                assertEquals(ZlibException.Kind.NO_MIRROR, e.kind)
            }

            assertEquals("UNKNOWN 线路 PARSE 不得清 mirror", unknown.baseUrl, client.mirror)
            assertTrue("UNKNOWN 线路 PARSE 不得清 pin", client.mirrorItems().any { it.pinned })
            assertTrue(logs.toString(), logs.any {
                it.startsWith("mirror_release_skip") && it.contains("reason=rank_unproven") && it.contains("rank=unknown")
            })
        }
    }

    @Test
    fun unknownSecondPageParseKeepsMirrorAndPin() {
        // T17 P2：翻页 PARSE 走 releaseMirrorIfNotApp，UNKNOWN 同样只算本次失败，不清 pin/mirror。
        StubHttpServer("<html><body>SPA shell</body></html>").use { unknown ->
            freshRegistry(listOf(unknown.baseUrl))
            val client = newClient()
            assertTrue(client.switchMirror(unknown.baseUrl))

            try {
                client.search("android", page = 2)
                throw AssertionError("翻页 PARSE 必须抛出")
            } catch (e: ZlibException) {
                assertEquals(ZlibException.Kind.PARSE, e.kind)
            }

            assertEquals(unknown.baseUrl, client.mirror)
            assertTrue("UNKNOWN 线路翻页 PARSE 不得清 pin", client.mirrorItems().any { it.pinned })
            assertTrue(logs.toString(), logs.any {
                it.startsWith("mirror_release_skip") && it.contains("reason=parse_unproven")
            })
        }
    }
}
