package app.particlebook.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 聚合口径的 JVM 单测。
 *
 * 全部用固定时区 + 固定 now，不读系统时钟：统计结果不能随跑测试的机器时区变化。
 */
class ReadingStatsTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(date: String, hour: Int, minute: Int = 0): Long =
        LocalDate.parse(date).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun day(date: String): Long = LocalDate.parse(date).toEpochDay()

    private fun session(
        bookId: Long = 1L,
        start: Long,
        end: Long,
        pages: Int = 0,
        startPage: Int? = null,
        endPage: Int? = null,
    ) = ReadingSessionRow(
        bookId = bookId,
        startedAt = start,
        endedAt = end,
        durationMs = end - start,
        pagesDelta = pages,
        startPage = startPage,
        endPage = endPage,
    )

    @Test
    fun emptySessionsProduceZeroedWeek() {
        val stats = ReadingStatsCalculator.compute(emptyList(), at("2026-11-21", 12), zone)

        assertEquals(0L, stats.totalMillis)
        assertEquals(0L, stats.todayMillis)
        assertEquals(0, stats.streakDays)
        assertEquals(0, stats.booksRead)
        assertEquals(0, stats.pagesTurned)
        assertEquals(7, stats.days.size)
        assertEquals(day("2026-11-15"), stats.days.first().epochDay)
        assertEquals(day("2026-11-21"), stats.days.last().epochDay)
        assertTrue(stats.days.all { it.millis == 0L && it.pages == 0 })
    }

    @Test
    fun sessionAcrossMidnightSplitsByNaturalDay() {
        val stats = ReadingStatsCalculator.compute(
            listOf(session(start = at("2026-11-20", 23), end = at("2026-11-21", 1))),
            at("2026-11-21", 12),
            zone,
        )

        val byDay = stats.days.associateBy { it.epochDay }
        assertEquals(3_600_000L, byDay.getValue(day("2026-11-20")).millis)
        assertEquals(3_600_000L, byDay.getValue(day("2026-11-21")).millis)
        assertEquals(7_200_000L, stats.totalMillis)
        assertEquals(3_600_000L, stats.todayMillis)
        // 两天都有阅读：今天起算连续 2 天
        assertEquals(2, stats.streakDays)
    }

    @Test
    fun sessionEndingExactlyAtMidnightStaysOnPreviousDay() {
        val stats = ReadingStatsCalculator.compute(
            listOf(session(start = at("2026-11-20", 23), end = at("2026-11-21", 0))),
            at("2026-11-21", 12),
            zone,
        )

        val byDay = stats.days.associateBy { it.epochDay }
        assertEquals(3_600_000L, byDay.getValue(day("2026-11-20")).millis)
        assertEquals(0L, stats.todayMillis)
        // 今天 0、昨天有 → 连续天数从昨天起算
        assertEquals(1, stats.streakDays)
    }

    @Test
    fun windowPadsMissingDaysAndKeepsTotalsFromAllHistory() {
        val stats = ReadingStatsCalculator.compute(
            listOf(
                session(bookId = 2, start = at("2026-11-05", 10), end = at("2026-11-05", 10, 30)),
                session(bookId = 1, start = at("2026-11-20", 10), end = at("2026-11-20", 11)),
                session(bookId = 1, start = at("2026-11-21", 9), end = at("2026-11-21", 9, 20)),
            ),
            at("2026-11-21", 20),
            zone,
        )

        assertEquals(7, stats.days.size)
        assertEquals(day("2026-11-15"), stats.days.first().epochDay)
        assertEquals(day("2026-11-21"), stats.days.last().epochDay)
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L), stats.days.take(5).map { it.millis })
        assertEquals(3_600_000L, stats.days[5].millis)
        assertEquals(1_200_000L, stats.days[6].millis)
        // 窗口外的 11-05 仍计入总量与书数
        assertEquals(1_800_000L + 3_600_000L + 1_200_000L, stats.totalMillis)
        assertEquals(2, stats.booksRead)
    }

    @Test
    fun streakCountsBackFromTodayWhenTodayHasReading() {
        val stats = ReadingStatsCalculator.compute(
            listOf(
                session(start = at("2026-11-21", 9), end = at("2026-11-21", 10)),
                session(start = at("2026-11-20", 9), end = at("2026-11-20", 10)),
                session(start = at("2026-11-19", 9), end = at("2026-11-19", 10)),
            ),
            at("2026-11-21", 20),
            zone,
        )

        assertEquals(3, stats.streakDays)
    }

    @Test
    fun streakStartsFromYesterdayWhenTodayIsEmpty() {
        val stats = ReadingStatsCalculator.compute(
            listOf(
                session(start = at("2026-11-20", 9), end = at("2026-11-20", 10)),
                session(start = at("2026-11-19", 9), end = at("2026-11-19", 10)),
            ),
            at("2026-11-21", 8),
            zone,
        )

        assertEquals(0L, stats.todayMillis)
        assertEquals(2, stats.streakDays)
    }

    @Test
    fun streakStopsAtFirstGapDay() {
        val stats = ReadingStatsCalculator.compute(
            listOf(
                session(start = at("2026-11-21", 7), end = at("2026-11-21", 7, 30)),
                session(start = at("2026-11-20", 9), end = at("2026-11-20", 10)),
                // 11-19 空白
                session(start = at("2026-11-18", 9), end = at("2026-11-18", 10)),
            ),
            at("2026-11-21", 8),
            zone,
        )

        assertEquals(2, stats.streakDays)
    }

    @Test
    fun streakIsZeroWhenTodayAndYesterdayAreEmpty() {
        val stats = ReadingStatsCalculator.compute(
            listOf(session(start = at("2026-11-18", 9), end = at("2026-11-18", 10))),
            at("2026-11-21", 8),
            zone,
        )

        assertEquals(0, stats.streakDays)
    }

    @Test
    fun pagesAndBooksAggregateAcrossSessions() {
        val stats = ReadingStatsCalculator.compute(
            listOf(
                session(
                    bookId = 1,
                    start = at("2026-11-21", 9),
                    end = at("2026-11-21", 9, 30),
                    pages = 4,
                    startPage = 0,
                    endPage = 4,
                ),
                session(bookId = 2, start = at("2026-11-21", 10), end = at("2026-11-21", 10, 10), pages = 3),
                // 回翻记负：只统计正向翻页，负值不抵减
                session(bookId = 1, start = at("2026-11-20", 9), end = at("2026-11-20", 9, 10), pages = -2),
            ),
            at("2026-11-21", 20),
            zone,
        )

        assertEquals(7, stats.pagesTurned)
        assertEquals(2, stats.booksRead)
        val byDay = stats.days.associateBy { it.epochDay }
        assertEquals(7, byDay.getValue(day("2026-11-21")).pages)
        assertEquals(0, byDay.getValue(day("2026-11-20")).pages)
    }

    @Test
    fun malformedDurationsDoNotLeakNegativeValues() {
        val broken = ReadingSessionRow(
            bookId = 1,
            startedAt = at("2026-11-21", 10),
            endedAt = at("2026-11-21", 9),
            durationMs = -60_000L,
        )

        val stats = ReadingStatsCalculator.compute(listOf(broken), at("2026-11-21", 12), zone)

        assertEquals(0L, stats.totalMillis)
        assertEquals(0L, stats.todayMillis)
        assertEquals(0, stats.streakDays)
    }

    @Test
    fun nonPositiveDayCountYieldsEmptyBucketsButKeepsTotals() {
        val stats = ReadingStatsCalculator.compute(
            listOf(session(start = at("2026-11-21", 10), end = at("2026-11-21", 10, 30))),
            at("2026-11-21", 12),
            zone,
            dayCount = 0,
        )

        assertEquals(0, stats.days.size)
        assertEquals(1_800_000L, stats.totalMillis)
        assertEquals(1_800_000L, stats.todayMillis)
    }

    @Test
    fun dayBoundariesFollowZoneAcrossDstChange() {
        // 2026-03-08 美东夏令时开始：01:00 EST → 03:00 EDT 实际只有 1 小时。
        val newYork = ZoneId.of("America/New_York")
        val start = LocalDate.parse("2026-03-08").atTime(1, 0).atZone(newYork).toInstant().toEpochMilli()
        val end = LocalDate.parse("2026-03-08").atTime(3, 0).atZone(newYork).toInstant().toEpochMilli()

        val stats = ReadingStatsCalculator.compute(
            listOf(ReadingSessionRow(bookId = 1, startedAt = start, endedAt = end, durationMs = end - start)),
            end,
            newYork,
        )

        assertEquals(3_600_000L, end - start)
        assertEquals(3_600_000L, stats.totalMillis)
        assertEquals(3_600_000L, stats.todayMillis)
        assertEquals(1, stats.streakDays)
        assertEquals(1, stats.days.count { it.millis > 0L })
    }

    // ------------------------------------------------------------------
    // 常读的书：排序 / limit / 书名 / 占比 / 窗口
    // ------------------------------------------------------------------

    @Test
    fun topBooksRankByMillisThenBookId() {
        val stats = ReadingStatsCalculator.compute(
            listOf(
                session(bookId = 3, start = at("2026-11-20", 9), end = at("2026-11-20", 9, 20)),
                session(bookId = 1, start = at("2026-11-20", 10), end = at("2026-11-20", 10, 20)),
                session(bookId = 2, start = at("2026-11-21", 9), end = at("2026-11-21", 10)),
            ),
            at("2026-11-21", 20),
            zone,
        )

        // 时长降序；同为 20 分钟时按 bookId 升序（1 在 3 前），与输入顺序无关。
        assertEquals(listOf(2L, 1L, 3L), stats.topBooks.map { it.bookId })
        assertEquals(listOf(3_600_000L, 1_200_000L, 1_200_000L), stats.topBooks.map { it.millis })
    }

    @Test
    fun topBooksRespectLimitAndNonPositiveLimitYieldsEmpty() {
        val sessions = (1L..4L).map { id ->
            session(bookId = id, start = at("2026-11-21", 9), end = at("2026-11-21", 9, 10))
        }

        val limited = ReadingStatsCalculator.compute(sessions, at("2026-11-21", 12), zone, topBookLimit = 2)
        assertEquals(listOf(1L, 2L), limited.topBooks.map { it.bookId })

        // 非正数给空表，而不是「不限」；汇总数字不受 limit 影响。
        val none = ReadingStatsCalculator.compute(sessions, at("2026-11-21", 12), zone, topBookLimit = 0)
        assertTrue(none.topBooks.isEmpty())
        assertEquals(4, none.booksRead)
    }

    @Test
    fun topBooksUseGivenTitlesAndFallBackForMissing() {
        val stats = ReadingStatsCalculator.compute(
            listOf(
                session(bookId = 7, start = at("2026-11-21", 9), end = at("2026-11-21", 9, 30)),
                session(bookId = 8, start = at("2026-11-21", 10), end = at("2026-11-21", 10, 20)),
                session(bookId = 9, start = at("2026-11-21", 11), end = at("2026-11-21", 11, 10)),
            ),
            at("2026-11-21", 12),
            zone,
            bookTitles = mapOf(7L to "三体", 8L to "   "),
        )

        val byId = stats.topBooks.associateBy { it.bookId }
        assertEquals("三体", byId.getValue(7L).title)
        // 只有空白也算没有书名：占位比一行空格子有用。
        assertEquals("未知书籍 #8", byId.getValue(8L).title)
        assertEquals("未知书籍 #9", byId.getValue(9L).title)
    }

    @Test
    fun topBooksShareIsWindowedPortionOfWindowTotal() {
        // 窗口 = 11-15..11-21（含今天）；11-05 的 30 分钟在窗口外。
        val stats = ReadingStatsCalculator.compute(
            listOf(
                session(bookId = 1, start = at("2026-11-21", 9), end = at("2026-11-21", 9, 30)),
                session(bookId = 2, start = at("2026-11-20", 9), end = at("2026-11-20", 9, 10)),
                session(bookId = 3, start = at("2026-11-05", 9), end = at("2026-11-05", 9, 30)),
            ),
            at("2026-11-21", 20),
            zone,
        )

        val byId = stats.topBooks.associateBy { it.bookId }
        // 窗口外的书不进榜（它在窗口里不可见）。
        assertEquals(setOf(1L, 2L), byId.keys)
        // 分母是窗口总时长 40 分钟，不是全部历史（70 分钟）。
        assertEquals(2_400_000L, stats.days.sumOf { it.millis })
        assertEquals(0.75, byId.getValue(1L).share, 1e-9)
        assertEquals(0.25, byId.getValue(2L).share, 1e-9)
        assertEquals(4_200_000L, stats.totalMillis)
    }

    @Test
    fun topBooksShareIsZeroWhenWindowHasNoDuration() {
        // 只有翻页、没有时长（记录器落了 0ms 行）：窗口总时长为 0，share 必须是 0.0 而不是 NaN。
        val zero = ReadingSessionRow(
            bookId = 5,
            startedAt = at("2026-11-21", 9),
            endedAt = at("2026-11-21", 9),
            durationMs = 0L,
            pagesDelta = 8,
        )

        val stats = ReadingStatsCalculator.compute(listOf(zero), at("2026-11-21", 12), zone)

        assertEquals(0L, stats.totalMillis)
        val row = stats.topBooks.single()
        assertEquals(5L, row.bookId)
        assertEquals(0L, row.millis)
        assertEquals(8, row.pages)
        assertEquals(0.0, row.share, 0.0)
    }

    @Test
    fun topBooksAggregateSessionsOfSameBookAcrossMidnight() {
        val stats = ReadingStatsCalculator.compute(
            listOf(
                session(bookId = 4, start = at("2026-11-20", 23), end = at("2026-11-21", 1), pages = 6),
                session(bookId = 4, start = at("2026-11-21", 9), end = at("2026-11-21", 9, 30), pages = 2),
            ),
            at("2026-11-21", 12),
            zone,
        )

        // 两小时跨午夜按墙钟对半切开，两天都在窗口内，所以合计仍是整段 2 小时 30 分。
        val row = stats.topBooks.single()
        assertEquals(9_000_000L, row.millis)
        assertEquals(8, row.pages)
        assertEquals(1.0, row.share, 1e-9)
    }

    @Test
    fun topBooksOnlyCountWindowedShareOfSessionCrossingWindowStart() {
        // 窗口 11-15..11-21；会话 11-14 23:00 → 11-15 01:00：只有落在 11-15 的那 1 小时进窗口。
        val stats = ReadingStatsCalculator.compute(
            listOf(session(bookId = 6, start = at("2026-11-14", 23), end = at("2026-11-15", 1), pages = 5)),
            at("2026-11-21", 12),
            zone,
        )

        val row = stats.topBooks.single()
        assertEquals(3_600_000L, row.millis)
        assertEquals(3_600_000L, stats.days.sumOf { it.millis })
        assertEquals(1.0, row.share, 1e-9)
        // 翻页按会话开始日归属：开始日 11-14 在窗口外，所以这 5 页不进榜（与柱状图同口径）。
        assertEquals(0, row.pages)
        // 汇总仍是整段两小时。
        assertEquals(7_200_000L, stats.totalMillis)
    }

    @Test
    fun topBooksIgnoreSessionsOutsideWindow() {
        val stats = ReadingStatsCalculator.compute(
            listOf(session(bookId = 9, start = at("2026-11-05", 10), end = at("2026-11-05", 11))),
            at("2026-11-21", 12),
            zone,
        )

        assertTrue(stats.topBooks.isEmpty())
        assertEquals(3_600_000L, stats.totalMillis)
    }

    @Test
    fun topBooksEmptyWhenWindowHasNoDays() {
        val stats = ReadingStatsCalculator.compute(
            listOf(session(bookId = 1, start = at("2026-11-21", 10), end = at("2026-11-21", 10, 30))),
            at("2026-11-21", 12),
            zone,
            dayCount = 0,
        )

        // 窗口为空：排行与柱状图一起空，汇总照旧（与 days 同一个推导）。
        assertTrue(stats.topBooks.isEmpty())
        assertEquals(1_800_000L, stats.totalMillis)
    }

    @Test
    fun topBooksIgnoreNegativeDurations() {
        val broken = ReadingSessionRow(
            bookId = 11,
            startedAt = at("2026-11-21", 10),
            endedAt = at("2026-11-21", 9),
            durationMs = -60_000L,
        )

        val stats = ReadingStatsCalculator.compute(listOf(broken), at("2026-11-21", 12), zone)

        // 负时长按 0：既没有时长也没有翻页的书不进榜。
        assertTrue(stats.topBooks.isEmpty())
        assertEquals(0L, stats.totalMillis)
    }
}
