package app.particlebook.android.ui

import app.particlebook.android.zlib.ZMirrorItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书源管理页纯函数（task-41）：分组/排序、延迟与相对时间文案、概览摘要。
 *
 * 不依赖 Android framework：只构造 [ZMirrorItem] 数据并调用同文件内部函数。
 */
class ZlibSourcesScreenTest {

    private fun mirror(
        url: String = "https://a.example",
        host: String = "a.example",
        tier: String = "official",
        rank: Int = 0,
        latencyMs: Long = -1L,
        lastOkAt: Long = 0L,
        successFresh: Boolean = false,
        current: Boolean = false,
        pinned: Boolean = false,
        demoted: Boolean = false,
    ): ZMirrorItem = ZMirrorItem(
        url = url,
        host = host,
        tier = tier,
        rank = rank,
        latencyMs = latencyMs,
        lastOkAt = lastOkAt,
        successFresh = successFresh,
        current = current,
        pinned = pinned,
        demoted = demoted,
    )

    @Test
    fun groupsFollowOfficialDynamicFallbackOrder() {
        val rows = listOf(
            mirror(url = "https://f.example", host = "f.example", tier = "fallback"),
            mirror(url = "https://d.example", host = "d.example", tier = "dynamic"),
            mirror(url = "https://o.example", host = "o.example", tier = "official"),
        )

        val groups = groupMirrorRows(rows)

        assertEquals(listOf("official", "dynamic", "fallback"), groups.map { it.tier })
        assertEquals(listOf("官方", "动态", "回退"), groups.map { it.label })
    }

    @Test
    fun emptyRowsProduceNoGroups() {
        assertTrue(groupMirrorRows(emptyList()).isEmpty())
    }

    @Test
    fun emptyTierIsOmittedFromGroups() {
        val rows = listOf(
            mirror(url = "https://o.example", host = "o.example", tier = "official"),
            mirror(url = "https://f.example", host = "f.example", tier = "fallback"),
        )

        assertEquals(listOf("official", "fallback"), groupMirrorRows(rows).map { it.tier })
    }

    @Test
    fun unknownTierFallsIntoFallbackAndCaseIsNormalized() {
        val rows = listOf(
            mirror(url = "https://nav.example", host = "nav.example", tier = "nav-page"),
            mirror(url = "https://upper.example", host = "upper.example", tier = "OFFICIAL"),
        )

        val groups = groupMirrorRows(rows)

        assertEquals(listOf("official", "fallback"), groups.map { it.tier })
        assertEquals("official", groups[0].tier)
        assertEquals("upper.example", groups[0].rows.single().host)
        assertEquals("nav.example", groups[1].rows.single().host)
    }

    @Test
    fun currentRowComesFirstWithinItsTier() {
        val rows = listOf(
            mirror(url = "https://a.example", host = "a.example", rank = 0, latencyMs = 10L),
            mirror(url = "https://b.example", host = "b.example", rank = 0, latencyMs = 20L, pinned = true),
            mirror(url = "https://c.example", host = "c.example", rank = 4, latencyMs = 5L, current = true),
        )

        assertEquals(
            listOf("c.example", "b.example", "a.example"),
            groupMirrorRows(rows).single().rows.map { it.host },
        )
    }

    @Test
    fun pinnedRowComesBeforeUnpinnedAtSameRank() {
        val rows = listOf(
            mirror(url = "https://a.example", host = "a.example", rank = 0, latencyMs = 10L),
            mirror(url = "https://b.example", host = "b.example", rank = 0, latencyMs = 500L, pinned = true),
        )

        assertEquals(
            listOf("b.example", "a.example"),
            groupMirrorRows(rows).single().rows.map { it.host },
        )
    }

    @Test
    fun unreachableRowsSortAfterReachableOnes() {
        val rows = listOf(
            mirror(url = "https://dead.example", host = "dead.example", rank = 4, latencyMs = 5L),
            mirror(url = "https://other.example", host = "other.example", rank = 3, latencyMs = 900L),
            mirror(url = "https://good.example", host = "good.example", rank = 0, latencyMs = 1_000L),
        )

        assertEquals(
            listOf("good.example", "other.example", "dead.example"),
            groupMirrorRows(rows).single().rows.map { it.host },
        )
    }

    @Test
    fun rowsSortByRankThenLatency() {
        val rows = listOf(
            mirror(url = "https://a.example", host = "a.example", rank = 1, latencyMs = 10L),
            mirror(url = "https://b.example", host = "b.example", rank = 0, latencyMs = 900L),
            mirror(url = "https://c.example", host = "c.example", rank = 0, latencyMs = 200L),
        )

        assertEquals(
            listOf("c.example", "b.example", "a.example"),
            groupMirrorRows(rows).single().rows.map { it.host },
        )
    }

    @Test
    fun unknownLatencySortsAfterMeasuredLatencyAtSameRank() {
        val rows = listOf(
            mirror(url = "https://a.example", host = "a.example", rank = 1, latencyMs = 500L),
            mirror(url = "https://b.example", host = "b.example", rank = 1, latencyMs = -1L),
            mirror(url = "https://c.example", host = "c.example", rank = 1, latencyMs = 20L),
        )

        assertEquals(
            listOf("c.example", "a.example", "b.example"),
            groupMirrorRows(rows).single().rows.map { it.host },
        )
    }

    @Test
    fun groupTitleCarriesRowCount() {
        val group = SourceGroup(
            tier = "official",
            label = "官方",
            rows = listOf(
                mirror(url = "https://a.example", host = "a.example"),
                mirror(url = "https://b.example", host = "b.example"),
                mirror(url = "https://c.example", host = "c.example"),
            ),
        )

        assertEquals("官方 · 3 条", sourceGroupTitle(group))
    }

    @Test
    fun formatLatencyHandlesUnknownAndMilliseconds() {
        assertEquals("未测速", formatLatency(-1L))
        assertEquals("未测速", formatLatency(-999L))
        assertEquals("0 ms", formatLatency(0L))
        assertEquals("1 ms", formatLatency(1L))
        assertEquals("999 ms", formatLatency(999L))
    }

    @Test
    fun formatLatencyUsesOneDecimalSecond() {
        assertEquals("1.0 s", formatLatency(1_000L))
        assertEquals("1.2 s", formatLatency(1_234L))
        assertEquals("1.9 s", formatLatency(1_999L))
        assertEquals("12.3 s", formatLatency(12_345L))
    }

    @Test
    fun formatRelativeTimeJustNow() {
        assertEquals("刚刚", formatRelativeTime(0L))
        assertEquals("刚刚", formatRelativeTime(59_999L))
        assertEquals("刚刚", formatRelativeTime(-500L))
    }

    @Test
    fun formatRelativeTimeMinutes() {
        assertEquals("1 分钟前", formatRelativeTime(60_000L))
        assertEquals("3 分钟前", formatRelativeTime(3 * 60_000L + 59_999L))
        assertEquals("59 分钟前", formatRelativeTime(3_599_999L))
    }

    @Test
    fun formatRelativeTimeHours() {
        assertEquals("1 小时前", formatRelativeTime(3_600_000L))
        assertEquals("23 小时前", formatRelativeTime(86_399_999L))
    }

    @Test
    fun formatRelativeTimeDays() {
        assertEquals("1 天前", formatRelativeTime(86_400_000L))
        assertEquals("3 天前", formatRelativeTime(3 * 86_400_000L))
    }

    @Test
    fun rankLabelsCoverDocumentedRanks() {
        assertEquals(
            listOf("正式站", "未探测", "推广页", "其它", "不通"),
            listOf(0, 1, 2, 3, 4).map(::rankLabel),
        )
    }

    @Test
    fun rankLabelFallsBackToUnknown() {
        assertEquals("未知", rankLabel(5))
        assertEquals("未知", rankLabel(-1))
        assertEquals("未知", rankLabel(99))
    }

    @Test
    fun formatLastSuccessNeverWhenTimestampMissing() {
        val now = 10_000_000L
        assertEquals("从未成功", formatLastSuccess(0L, now))
        assertEquals("从未成功", formatLastSuccess(-5L, now))
    }

    @Test
    fun formatLastSuccessUsesRelativeTime() {
        val now = 10_000_000L
        assertEquals("上次成功 3 分钟前", formatLastSuccess(now - 3 * 60_000L, now))
        assertEquals("上次成功 刚刚", formatLastSuccess(now + 5_000L, now))
    }

    @Test
    fun probeResultSummaryHandlesUrlBlankAndRawMessage() {
        assertEquals(
            "探测完成 · 已选择 z-lib.example",
            probeResultSummary("  https://z-lib.example/path?q=1  "),
        )
        assertEquals("探测完成", probeResultSummary(""))
        assertEquals("探测完成", probeResultSummary("   "))
        assertEquals("探测完成", probeResultSummary(null))
        assertEquals("all failed", probeResultSummary("all failed"))
    }

    @Test
    fun currentSourceHostFallsBackToUrlHostAndNotSelected() {
        assertEquals(
            "z-lib.example",
            currentSourceHost(
                listOf(mirror(url = "https://z-lib.example/", host = "", current = true)),
            ),
        )
        assertEquals(
            "z-lib.gd",
            currentSourceHost(
                listOf(mirror(url = "https://z-lib.gd/", host = "", current = true)),
            ),
        )
        assertEquals(
            "a.example",
            currentSourceHost(
                listOf(mirror(url = "https://a.example/", host = "a.example", current = true)),
            ),
        )
        assertEquals("未选择", currentSourceHost(listOf(mirror(host = "a.example"))))
    }

    @Test
    fun availableSourceCountCountsOnlyRankZero() {
        val rows = listOf(
            mirror(url = "https://a.example", host = "a.example", rank = 0),
            mirror(url = "https://b.example", host = "b.example", rank = 0),
            mirror(url = "https://c.example", host = "c.example", rank = 1),
            mirror(url = "https://d.example", host = "d.example", rank = 2),
            mirror(url = "https://e.example", host = "e.example", rank = 3),
            mirror(url = "https://f.example", host = "f.example", rank = 4),
        )

        assertEquals(2, availableSourceCount(rows))
    }

    @Test
    fun rowMetaCombinesLatencyLastSuccessAndDemoted() {
        val now = 10_000_000L
        assertEquals(
            "延迟 234 ms · 上次成功 3 分钟前 · 暂不可用",
            mirrorRowMeta(
                mirror(latencyMs = 234L, lastOkAt = now - 3 * 60_000L, demoted = true),
                now,
            ),
        )
        assertEquals(
            "未测速 · 从未成功",
            mirrorRowMeta(mirror(latencyMs = -1L, lastOkAt = 0L), now),
        )
    }
}
