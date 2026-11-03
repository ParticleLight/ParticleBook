package app.particlebook.android.zlib

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * task-8 / B1+B7：探测 rank 排序、降权到期重试、rank 持久化、last-working 记忆。
 * 纯 JVM（注册表不联网），时间轴全部显式传入。
 */
class ZlibMirrorRankTest {

    private fun fresh(
        now: Long = 0L,
        official: List<String> = listOf("https://a.app", "https://b.app"),
        fallback: List<String> = emptyList(),
    ) {
        ZlibMirrorRegistry.resetForTest(official, fallback)
        ZlibMirrorRegistry.ensureLoaded(null, now)
    }

    private fun tempRegistryFile(): File {
        val dir = Files.createTempDirectory("zlib-mirror-rank").toFile()
        dir.deleteOnExit()
        return File(dir, ZlibMirrorRegistry.FILE_NAME)
    }

    @Test
    fun rankOrdersAppThenUnknownThenPageThenOtherThenFail() {
        fresh(
            1_000L,
            official = listOf(
                "https://a.app",
                "https://b.other",
                "https://c.unknown",
                "https://d.page",
                "https://e.fail",
            ),
        )
        ZlibMirrorRegistry.recordSuccess("https://a.app", null, 900L, 1_000L)
        ZlibMirrorRegistry.recordProbeResult("https://b.other", ZlibMirrorRegistry.Rank.OTHER, 100L, 1_000L)
        ZlibMirrorRegistry.recordProbeResult("https://d.page", ZlibMirrorRegistry.Rank.PAGE, 500L, 1_000L)
        ZlibMirrorRegistry.recordFailure("https://e.fail", "network", 1_000L)

        val order = ZlibMirrorRegistry.orderedOfficial(1_000L).map { it.url }
        assertEquals(
            listOf("https://a.app", "https://c.unknown", "https://d.page", "https://b.other", "https://e.fail"),
            order,
        )
    }

    @Test
    fun sameRankPrefersLowerLatency() {
        fresh(1_000L, official = listOf("https://slow.app", "https://fast.app"))
        ZlibMirrorRegistry.recordSuccess("https://slow.app", null, 900L, 1_000L)
        ZlibMirrorRegistry.recordSuccess("https://fast.app", null, 120L, 1_000L)

        // 同 rank(APP) 时延迟小的在前（延迟优先于种子 priority）。
        assertEquals(
            listOf("https://fast.app", "https://slow.app"),
            ZlibMirrorRegistry.orderedOfficial(1_000L).map { it.url },
        )
    }

    @Test
    fun failedRankIsRetriedAfterDemoteExpiry() {
        fresh(0L, official = listOf("https://a.fail", "https://b.ok"))
        ZlibMirrorRegistry.recordFailure("https://a.fail", "network", 0L)
        val demotedUntil = ZlibMirrorRegistry.DEMOTE_BASE_MS

        assertEquals(
            listOf("https://b.ok", "https://a.fail"),
            ZlibMirrorRegistry.orderedOfficial(0L).map { it.url },
        )
        // 降权到期后 FAIL 视作 UNKNOWN 重新参与排序（否则一次失败会永久垫底）。
        assertEquals(
            listOf("https://a.fail", "https://b.ok"),
            ZlibMirrorRegistry.orderedOfficial(demotedUntil).map { it.url },
        )
    }

    @Test
    fun rankIsPersistedAndOldCacheDefaultsToUnknown() {
        val file = tempRegistryFile()
        fresh(1_000L, official = listOf("https://a.page"), fallback = emptyList())
        ZlibMirrorRegistry.recordProbeResult("https://a.page", ZlibMirrorRegistry.Rank.PAGE, 321L, 1_000L)
        ZlibMirrorRegistry.save(file, 1_000L)
        val raw = file.readText(Charsets.UTF_8)
        assertTrue(raw, raw.contains("\"rank\":\"page\""))

        ZlibMirrorRegistry.resetForTest()
        ZlibMirrorRegistry.ensureLoaded(file, 1_000L)
        val persisted = ZlibMirrorRegistry.snapshot().entries.first { it.url == "https://a.page" }
        assertEquals(ZlibMirrorRegistry.Rank.PAGE, persisted.rank)
        assertEquals(321L, persisted.latencyMs)

        // v1 旧缓存没有 rank 字段：必须回退 UNKNOWN，不能丢线路。
        val legacy = tempRegistryFile()
        legacy.writeText(
            "{\"version\":1,\"entries\":[{\"url\":\"https://old.app\",\"tier\":\"official\",\"priority\":0}]}",
            Charsets.UTF_8,
        )
        ZlibMirrorRegistry.resetForTest()
        ZlibMirrorRegistry.ensureLoaded(legacy, 1_000L)
        val old = ZlibMirrorRegistry.snapshot().entries.first { it.url == "https://old.app" }
        assertEquals(ZlibMirrorRegistry.Rank.UNKNOWN, old.rank)
    }

    @Test
    fun lastWorkingReturnsMostRecentHealthyLine() {
        fresh(10_000L, official = listOf("https://a.app", "https://b.app"))
        ZlibMirrorRegistry.recordSuccess("https://a.app", null, 50L, 2_000L)
        ZlibMirrorRegistry.recordSuccess("https://b.app", null, 60L, 9_000L)

        assertEquals("https://b.app", ZlibMirrorRegistry.lastWorking(10_000L)!!.url)
        // 12h 成功 TTL 之外不再算「近期成功」。
        assertNull(ZlibMirrorRegistry.lastWorking(9_000L + ZlibMirrorRegistry.SUCCESS_TTL_MS))
        // 降权中的线路即使 lastOkAt 较新也不能当选。
        ZlibMirrorRegistry.recordFailure("https://b.app", "network", 10_000L)
        assertEquals("https://a.app", ZlibMirrorRegistry.lastWorking(10_000L)!!.url)
    }

    @Test
    fun navSourceIsWwwnavWithSixHourTtl() {
        assertEquals("https://zlib.wwwnav.com/", ZlibMirrorList.NAV_URL)
        assertEquals(6L * 60L * 60L * 1000L, ZlibMirrorRegistry.NAV_TTL_MS)
        assertFalse(ZlibMirrorList.NAV_URL.contains("ggonav"))
    }
}
