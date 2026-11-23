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
}
