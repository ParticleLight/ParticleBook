package app.particlebook.android.zlib

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R7-H 镜像分层（task-53）：T0 官方优先、TTL、降权退避、JSON 往返、DiamWall 排除。 */
class ZlibMirrorRegistryTest {

    private fun tempRegistryFile(): File {
        val dir = Files.createTempDirectory("zlib-mirror-registry").toFile()
        dir.deleteOnExit()
        return File(dir, ZlibMirrorRegistry.FILE_NAME)
    }

    private fun fresh(now: Long = 0L) {
        ZlibMirrorRegistry.resetForTest()
        ZlibMirrorRegistry.ensureLoaded(null, now)
    }

    @Test
    fun officialSeedsComeFirstInResearchOrder() {
        fresh()
        val urls = ZlibMirrorRegistry.orderedUrls(0L)

        assertEquals(ZlibMirrorRegistry.OFFICIAL_SEEDS, urls.take(ZlibMirrorRegistry.OFFICIAL_SEEDS.size))
        assertEquals("https://z-lib.by", urls.first())
        // 官方 10 条之后才是兜底（重叠项已去重，只有一个 z-lib.by）。
        assertEquals(1, urls.count { it == "https://z-lib.by" })
        assertTrue(urls.size >= ZlibMirrorRegistry.OFFICIAL_SEEDS.size)
    }

    @Test
    fun demotedOfficialMovesToEndAndBacksOffExponentially() {
        fresh()
        assertEquals("https://zlib.ch", ZlibMirrorRegistry.orderedOfficial(0L)[2].url)

        val first = ZlibMirrorRegistry.recordFailure("https://zlib.ch", "diamwall", 0L)!!
        assertEquals(ZlibMirrorRegistry.DEMOTE_BASE_MS, first.demoteUntil)
        assertEquals(1, first.failCount)
        val second = ZlibMirrorRegistry.recordFailure("https://zlib.ch", "diamwall", 0L)!!
        assertEquals(ZlibMirrorRegistry.DEMOTE_BASE_MS * 2, second.demoteUntil)

        repeat(6) { ZlibMirrorRegistry.recordFailure("https://zlib.ch", "network", 0L) }
        val capped = ZlibMirrorRegistry.recordFailure("https://zlib.ch", "network", 0L)!!
        assertEquals("降权上限 2h", ZlibMirrorRegistry.DEMOTE_MAX_MS, capped.demoteUntil)
        assertEquals("https://zlib.ch", ZlibMirrorRegistry.orderedOfficial(0L).last().url)

        // 降权到期后重新排回原优先级附近（不再算 demoted）。
        assertFalse(ZlibMirrorRegistry.orderedOfficial(ZlibMirrorRegistry.DEMOTE_MAX_MS).last().url == "https://zlib.ch")
    }

    @Test
    fun navRefreshFollowsTtlThenFailureBackoff() {
        fresh()
        assertTrue("从未抓过 -> 刷新", ZlibMirrorRegistry.shouldRefreshNav(0L))

        // fetchedAt=0 是「从未抓过」哨兵，测试从非 0 时间轴开始。
        val base = 1_000_000L
        ZlibMirrorRegistry.recordNavSuccess(
            base,
            ZlibMirrorList.NAV_URL,
            listOf("https://zh.z-lib.gd"),
            listOf("https://zlib.bz"),
        )
        assertFalse(ZlibMirrorRegistry.shouldRefreshNav(base + ZlibMirrorRegistry.NAV_TTL_MS - 1))
        assertTrue(ZlibMirrorRegistry.shouldRefreshNav(base + ZlibMirrorRegistry.NAV_TTL_MS))

        val failAt = base + ZlibMirrorRegistry.NAV_TTL_MS
        ZlibMirrorRegistry.recordNavFailure(failAt, ZlibMirrorList.NAV_URL, "timeout")
        assertFalse(ZlibMirrorRegistry.shouldRefreshNav(failAt + ZlibMirrorRegistry.NAV_FAIL_BASE_MS - 1))
        assertTrue(ZlibMirrorRegistry.shouldRefreshNav(failAt + ZlibMirrorRegistry.NAV_FAIL_BASE_MS))
        assertEquals(1, ZlibMirrorRegistry.snapshot().nav.failCount)
    }

    @Test
    fun jsonRoundTripKeepsSuccessFailureAndNavState() {
        val file = tempRegistryFile()
        fresh(1_000L)
        ZlibMirrorRegistry.recordSuccess("https://zlib.ch/", "https://zlib.ch", 120L, 1_000L)
        ZlibMirrorRegistry.recordFailure("https://zlib.re", "network", 1_000L)
        ZlibMirrorRegistry.recordFailure("https://zlib.re", "network", 1_001L)
        ZlibMirrorRegistry.recordNavSuccess(
            1_000L,
            ZlibMirrorList.NAV_URL,
            listOf("https://zh.z-lib.gd"),
            listOf("https://zlib.bz"),
        )
        ZlibMirrorRegistry.save(file, 1_002L)
        assertTrue("JSON 必须落盘", file.isFile)
        val raw = file.readText(Charsets.UTF_8)
        assertTrue(raw.contains("\"version\""))
        assertTrue(raw.contains("\"entries\""))
        assertTrue(raw.contains("\"nav\""))

        ZlibMirrorRegistry.resetForTest()
        ZlibMirrorRegistry.ensureLoaded(file, 1_003L)
        val snap = ZlibMirrorRegistry.snapshot()
        val ok = snap.entries.first { it.url == "https://zlib.ch" }
        assertEquals(1_000L, ok.lastOkAt)
        assertEquals(120L, ok.latencyMs)
        assertEquals("https://zlib.ch", ok.finalUrl)
        assertEquals(0, ok.failCount)
        val failed = snap.entries.first { it.url == "https://zlib.re" }
        assertEquals(2, failed.failCount)
        assertEquals(1_001L + ZlibMirrorRegistry.DEMOTE_BASE_MS * 2, failed.demoteUntil)
        assertTrue(snap.nav.ok)
        assertEquals(listOf("https://zlib.bz"), snap.nav.dynamic)
        assertTrue(snap.nav.official.contains("https://zh.z-lib.gd"))
    }

    @Test
    fun failedHostIsDemotedAndSkippedInFreshProbe() {
        fresh(0L)
        ZlibMirrorRegistry.recordFailure("https://zlib.ch", "diamwall", 0L)
        val first = ZlibMirrorRegistry.orderedOfficial(0L).first()
        assertFalse("降权项不能排在首位", first.url == "https://zlib.ch")
    }

    @Test
    fun dynamicEntryIsPrunedAfter24h() {
        val base = 1_000_000L
        fresh(base)
        ZlibMirrorRegistry.recordNavSuccess(
            base,
            ZlibMirrorList.NAV_URL,
            emptyList(),
            listOf("https://zlib.bz"),
        )
        assertTrue(ZlibMirrorRegistry.snapshot().entries.any { it.url == "https://zlib.bz" })
        // 24h 后导航页不再推荐：并入时清掉过期动态项。
        ZlibMirrorRegistry.recordNavSuccess(
            base + ZlibMirrorRegistry.DYNAMIC_PRUNE_MS + 1,
            ZlibMirrorList.NAV_URL,
            emptyList(),
            emptyList(),
        )
        assertFalse(ZlibMirrorRegistry.snapshot().entries.any { it.url == "https://zlib.bz" })
    }

    @Test
    fun diamWallHostsAreExcludedFromFallbackAndRecognized() {
        assertTrue(ZlibMirrorRegistry.isDiamWall("https://z-lib.fm/"))
        assertTrue(ZlibMirrorRegistry.isDiamWall("https://zh.z-library.sk/"))
        assertTrue(ZlibMirrorRegistry.isDiamWall("https://zh.1lib.sk/"))
        assertFalse(ZlibMirrorRegistry.isDiamWall("https://zh.singlelogin.rs/"))
        assertFalse(ZlibMirrorRegistry.isDiamWall("https://z-lib.by/"))

        ZlibMirrorRegistry.resetForTest(
            official = emptyList(),
            fallback = listOf("https://z-lib.fm", "https://zlib.ch"),
        )
        ZlibMirrorRegistry.ensureLoaded(null, 0L)
        assertEquals(listOf("https://zlib.ch"), ZlibMirrorRegistry.fallbackCandidates(0L).map { it.url })
    }

    @Test
    fun tierHelpersMatchResearchClassification() {
        fresh()
        assertTrue(ZlibMirrorRegistry.isOfficial("https://zh.singlelogin.rs"))
        assertFalse(ZlibMirrorRegistry.isOfficial("https://z-lib.fm"))
        assertEquals(ZlibMirrorRegistry.Tier.OFFICIAL, ZlibMirrorRegistry.tierOf("https://zlib.re/"))
        assertEquals(
            "normalizeUrl 去尾部斜杠、保留端口",
            "http://127.0.0.1:18080",
            ZlibMirrorRegistry.normalizeUrl("http://127.0.0.1:18080/s/test?x=1"),
        )
    }
}
