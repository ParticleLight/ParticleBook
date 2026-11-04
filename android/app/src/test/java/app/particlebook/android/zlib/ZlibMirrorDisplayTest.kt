package app.particlebook.android.zlib

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * task-8 / B6 + task-10 P2-6：线路弹层展示元数据（单快照 mirrorRows、按 URL 切换、上次成功只看正式站）。
 */
class ZlibMirrorDisplayTest {

    private val now = System.currentTimeMillis()

    @Test
    fun mirrorItemsExposeMetadataInMirrorInfoOrder() {
        ZlibMirrorRegistry.resetForTest(
            official = listOf("https://a.app", "https://b.page"),
            fallback = emptyList(),
        )
        ZlibMirrorRegistry.ensureLoaded(null, now)
        ZlibMirrorRegistry.recordSuccess("https://a.app", null, 88L, now, ZlibMirrorRegistry.Rank.APP)
        ZlibMirrorRegistry.recordProbeResult("https://b.page", ZlibMirrorRegistry.Rank.PAGE, 1_500L, now)

        val client = ZlibClient()
        client.mirror = "https://a.app"

        val info = client.mirrorInfo()
        val items = client.mirrorItems()

        assertEquals("顺序必须与 mirrorInfo.mirrors 一致", info.mirrors, items.map { it.url })
        assertEquals("mirrorItems 委托 mirrorRows，行集合必须一致", items.map { it.url }, client.mirrorRows().map { it.url })
        assertEquals(2, items.size)

        val a = items[0]
        assertEquals("https://a.app", a.url)
        assertEquals("a.app", a.host)
        assertEquals("official", a.tier)
        assertEquals(0, a.rank)
        assertEquals(88L, a.latencyMs)
        assertTrue(a.successFresh)
        assertTrue(a.current)
        assertFalse(a.demoted)

        val b = items[1]
        assertEquals("https://b.page", b.url)
        assertEquals(2, b.rank)
        assertEquals(1_500L, b.latencyMs)
        assertFalse(b.successFresh)
        assertFalse(b.current)

        // 切换后 current/pinned 跟着走（pinned 只在手动切换后为 true）。
        assertTrue(client.switchMirror(1))
        val after = client.mirrorItems()
        assertTrue(after[1].current)
        assertTrue(after[1].pinned)
        assertFalse(after[0].current)
    }

    @Test
    fun switchingByUrlIgnoresIndexReorder() {
        ZlibMirrorRegistry.resetForTest(
            official = listOf("https://a.app", "https://b.page"),
            fallback = emptyList(),
        )
        ZlibMirrorRegistry.ensureLoaded(null, now)
        ZlibMirrorRegistry.recordSuccess("https://a.app", null, 88L, now, ZlibMirrorRegistry.Rank.APP)
        val client = ZlibClient()
        client.mirror = "https://a.app"

        // 弹层拿到行快照后，后台探测把 b 提到前面（等价于「点第 2 行时顺序已变」）。
        val target = client.mirrorRows().first { it.url == "https://b.page" }.url
        ZlibMirrorRegistry.recordSuccess("https://b.page", null, 1L, now, ZlibMirrorRegistry.Rank.APP)

        assertTrue("按 URL 切换必须命中原来的那行", client.switchMirror(target))
        assertEquals(target, client.mirror)
        assertTrue(client.mirrorRows().first { it.url == target }.current)

        assertFalse("不在名单里的 URL 必须拒绝", client.switchMirror("https://nope.app"))
        assertFalse(client.switchMirror(""))
    }

    @Test
    fun mirrorRowsAndSwitchByUrlStayAlignedUnderConcurrentWrites() {
        ZlibMirrorRegistry.resetForTest(
            official = listOf("https://a.app", "https://b.page", "https://c.app"),
            fallback = emptyList(),
        )
        ZlibMirrorRegistry.ensureLoaded(null, now)
        ZlibMirrorRegistry.recordSuccess("https://a.app", null, 88L, now, ZlibMirrorRegistry.Rank.APP)
        val client = ZlibClient()
        client.mirror = "https://a.app"

        val running = AtomicBoolean(true)
        val writer = Thread {
            var i = 0
            while (running.get()) {
                if (i % 2 == 0) {
                    ZlibMirrorRegistry.recordFailure("https://b.page", "network", now)
                } else {
                    ZlibMirrorRegistry.recordProbeResult("https://b.page", ZlibMirrorRegistry.Rank.PAGE, 100L + i, now)
                }
                i++
            }
        }
        writer.isDaemon = true
        writer.start()
        try {
            repeat(200) { round ->
                val rows = client.mirrorRows()
                assertEquals("行 URL 不得重复 round=" + round, rows.size, rows.map { it.url }.distinct().size)
                assertTrue("每行 host 必须与其 URL 对应 round=" + round, rows.all { it.host.isNotEmpty() })
                val target = "https://b.page"
                assertTrue("并发刷新时按 URL 切换必须成功 round=" + round, client.switchMirror(target))
                assertEquals("按 URL 切换必须命中同一行 round=" + round, target, client.mirror)
            }
        } finally {
            running.set(false)
            writer.join(3_000)
        }
    }

    @Test
    fun successFreshOnlyShowsForAppRank() {
        ZlibMirrorRegistry.resetForTest(
            official = listOf("https://a.app", "https://b.page"),
            fallback = emptyList(),
        )
        ZlibMirrorRegistry.ensureLoaded(null, now)
        ZlibMirrorRegistry.recordSuccess("https://b.page", null, 30L, now, ZlibMirrorRegistry.Rank.APP)
        // 探测结论降为推广页：recordProbeResult 清零旧 lastOkAt，展示层也不能声称「上次成功」。
        ZlibMirrorRegistry.recordProbeResult("https://b.page", ZlibMirrorRegistry.Rank.PAGE, 30L, now)

        val client = ZlibClient()
        val row = client.mirrorRows().first { it.url == "https://b.page" }

        assertEquals(ZlibMirrorRegistry.Rank.PAGE.value, row.rank)
        assertFalse("软页即使有 lastOkAt 也不展示上次成功", row.successFresh)
    }
}
