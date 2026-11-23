package app.particlebook.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 记录器的 JVM 单测：假时钟 + 内存 repository，不依赖 Android 与真实 SQLite。
 *
 * 真实 SQLite 的 DDL/FK/排序由 Lead 的设备复测覆盖；这里锁的是状态机语义
 * （短会话丢弃、flush 分段、翻页计数、stop 复位）。
 */
class ReadingSessionRecorderTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(hour: Int, minute: Int = 0): Long =
        LocalDate.parse("2026-11-21").atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private var clock = at(9)

    private val repository = TestLibraryRepository().apply {
        saveBook(BookRow(id = 1, stableKey = "zlib:1", title = "书一"))
        saveBook(BookRow(id = 2, stableKey = "zlib:2", title = "书二"))
    }

    private fun recorder(
        provider: () -> LibraryRepository? = { repository },
        minSessionMillis: Long = 5_000L,
        flushIntervalMillis: Long = 60_000L,
    ) = ReadingSessionRecorder(
        repositoryProvider = provider,
        now = { clock },
        zone = zone,
        minSessionMillis = minSessionMillis,
        flushIntervalMillis = flushIntervalMillis,
    )

    private fun rows(): List<ReadingSessionRow> = repository.readingSessionsSince(0L)

    @Test
    fun shortSessionIsDropped() {
        val recorder = recorder()
        recorder.start(1, 0)
        clock += 4_999

        assertNull(recorder.stop())
        assertTrue(rows().isEmpty())
    }

    @Test
    fun stopPersistsRowAfterMinimumDuration() {
        val recorder = recorder()
        recorder.start(1, 3)
        clock += 5_000

        val row = requireNotNull(recorder.stop())

        assertEquals(1L, row.bookId)
        assertEquals(at(9), row.startedAt)
        assertEquals(at(9) + 5_000, row.endedAt)
        assertEquals(5_000L, row.durationMs)
        assertEquals(3, row.startPage)
        assertEquals(3, row.endPage)
        val stored = rows()
        assertEquals(1, stored.size)
        assertEquals(row.id, stored.single().id)
    }

    @Test
    fun pageChangesCountOnlyRealTurns() {
        val recorder = recorder()
        recorder.start(1, 10)
        recorder.onPageChanged(11)
        recorder.onPageChanged(11)
        recorder.onPageChanged(null)
        recorder.onPageChanged(12)
        clock += 10_000

        val row = requireNotNull(recorder.stop())

        assertEquals(2, row.pagesDelta)
        assertEquals(10, row.startPage)
        assertEquals(12, row.endPage)
    }

    @Test
    fun firstKnownPageBecomesStartPageWithoutCountingTurn() {
        val recorder = recorder()
        recorder.start(1, null)
        recorder.onPageChanged(7)
        recorder.onPageChanged(8)
        clock += 10_000

        val row = requireNotNull(recorder.stop())

        assertEquals(1, row.pagesDelta)
        assertEquals(7, row.startPage)
        assertEquals(8, row.endPage)
    }

    @Test
    fun flushSplitsLongSessionAndMovesAnchor() {
        val recorder = recorder()
        recorder.start(1, 3)
        recorder.onPageChanged(4)
        clock += 60_000

        val first = requireNotNull(recorder.flushIfDue())

        assertEquals(60_000L, first.durationMs)
        assertEquals(1, first.pagesDelta)
        assertEquals(3, first.startPage)
        assertEquals(4, first.endPage)

        // 落盘后同一次阅读继续：下一段从上一段的末页接着算
        recorder.onPageChanged(5)
        clock += 30_000
        val second = requireNotNull(recorder.stop())

        assertEquals(30_000L, second.durationMs)
        assertEquals(1, second.pagesDelta)
        assertEquals(4, second.startPage)
        assertEquals(5, second.endPage)

        val stored = rows()
        assertEquals(2, stored.size)
        assertEquals(first.id, stored[0].id)
        assertEquals(at(9) + 60_000, stored[1].startedAt)
        assertEquals(90_000L, stored.sumOf { it.durationMs })
    }

    @Test
    fun flushIsNullBeforeInterval() {
        val recorder = recorder()
        recorder.start(1, 0)
        clock += 59_999

        assertNull(recorder.flushIfDue())
        assertTrue(rows().isEmpty())

        clock += 1
        requireNotNull(recorder.flushIfDue())
        assertEquals(60_000L, rows().single().durationMs)
    }

    @Test
    fun flushDoesNotWriteFragmentsBelowMinimum() {
        val recorder = recorder(minSessionMillis = 5_000L, flushIntervalMillis = 1_000L)
        recorder.start(1, 0)
        clock += 2_000

        assertNull(recorder.flushIfDue())
        assertTrue(rows().isEmpty())

        clock += 4_000
        requireNotNull(recorder.flushIfDue())
        assertEquals(6_000L, rows().single().durationMs)
    }

    @Test
    fun stopResetsStateForNextSession() {
        val recorder = recorder()
        recorder.start(1, 0)
        clock += 10_000
        requireNotNull(recorder.stop())

        assertNull(recorder.stop())
        assertNull(recorder.flushIfDue())
        assertNull(recorder.stop())

        recorder.start(1, 0)
        clock += 10_000
        requireNotNull(recorder.stop())
        assertEquals(2, rows().size)
    }

    @Test
    fun cancelDiscardsWithoutWriting() {
        val recorder = recorder()
        recorder.start(1, 0)
        recorder.onPageChanged(1)
        clock += 10_000

        recorder.cancel()

        assertNull(recorder.stop())
        assertNull(recorder.flushIfDue())
        assertTrue(rows().isEmpty())
    }

    @Test
    fun switchingBookFinishesPreviousSession() {
        val recorder = recorder()
        recorder.start(1, 0)
        clock += 10_000

        recorder.start(2, 0)

        val stored = rows()
        assertEquals(1, stored.size)
        assertEquals(1L, stored.single().bookId)
        assertEquals(10_000L, stored.single().durationMs)

        clock += 5_000
        val second = requireNotNull(recorder.stop())
        assertEquals(2L, second.bookId)
        assertEquals(5_000L, second.durationMs)
        assertEquals(2, rows().size)
    }

    @Test
    fun repeatedStartForSameBookKeepsAccumulatedTime() {
        val recorder = recorder()
        recorder.start(1, 5)
        clock += 10_000
        recorder.start(1, 5)
        clock += 10_000

        val row = requireNotNull(recorder.stop())

        assertEquals(20_000L, row.durationMs)
        assertEquals(1, rows().size)
    }

    @Test
    fun missingRepositoryDropsSessionWithoutThrowing() {
        val recorder = recorder(provider = { null })
        recorder.start(1, 0)
        clock += 10_000

        assertNull(recorder.stop())
        assertTrue(rows().isEmpty())
    }

    @Test
    fun repositoryFailureDoesNotBreakTheReadingFlow() {
        val recorder = recorder(provider = { throw IllegalStateException("db closed") })
        recorder.start(1, 0)
        clock += 60_000

        assertNull(recorder.flushIfDue())
        assertNull(recorder.stop())
    }
}
