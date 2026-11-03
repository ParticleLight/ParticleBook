package app.particlebook.android.zlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * task-8 / B6：线路弹层展示元数据（item 与 mirrorInfo 同序、current/pinned/rank/latency/上次成功）。
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

        assertEquals("顺序必须与 mirrorInfo.mirrors 一致（弹层复用 switchMirror 下标）", info.mirrors, items.map { it.url })
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
}
