package app.particlebook.android.reader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TtsSleepTimer] 的边界回归：只钉**可观察行为**（durationMinutes / remainingMillis / pollExpired / 文案），
 * 不读实现源码字符串、不 sleep、不碰 Android 与 [TtsPlayback]。
 *
 * 覆盖契约：
 * - 初态无计划；四个预设精确时长；倒计时只随注入的**单调时钟**走，重复读取不挪 deadline；
 * - 到期边界 1ms / 0ms；到期后 [TtsSleepTimer.pollExpired] 只吐一次 true，其余情况 false；
 * - cancel 幂等；start 覆盖旧计划并从**当前**时刻重计；非法 start 抛 IllegalArgumentException 且不毁旧计划；
 * - [TtsSleepTimer] 的 nowMillis 视作单调时钟：**负值一律按 0 处理**（start / remainingMillis / pollExpired
 *   三个入口一致），所以负时钟既不会吃掉计划时长，也不会让定时提前到期；回拨仍夹到总时长；
 *   deadline 加法饱和 Long.MAX_VALUE 不回绕；
 * - 文案秒向上取整、负值当 0、Long.MAX_VALUE 不溢出。
 *
 * 不在本文件内（宿主 / UI 层，本批由弹层实现与生命周期审计覆盖）：暂停时继续走、到时先清自动续读
 * 再停播放、取消不暂停朗读、手动停止或退出书取消计划、段落 / 页面推进不重置——定时器本身没有这些入口，
 * 这里只能证明"只要没人调 start/cancel，倒计时就只受单调时钟影响"。
 */
class TtsSleepTimerTest {

    /** 可拨的假时钟：测试只通过它推进时间，不依赖真实等待。 */
    private class FakeClock(var now: Long = 0L) {
        fun read(): Long = now
        fun advance(millis: Long) {
            now += millis
        }
    }

    private fun timer(clock: FakeClock): TtsSleepTimer = TtsSleepTimer { clock.read() }

    private fun remaining(timer: TtsSleepTimer): Long =
        requireNotNull(timer.remainingMillis()) { "expected a live plan" }

    private val oneMinute = 60_000L
    private val fiveMinutes = 5 * oneMinute
    private val fifteenMinutes = 15 * oneMinute
    private val sixtyMinutes = 60 * oneMinute

    // ---------------------------------------------------------------- 初态与预设

    @Test
    fun freshTimerHasNoPlan() {
        val timer = timer(FakeClock(1_234_567L))
        assertNull(timer.durationMinutes)
        assertNull(timer.remainingMillis())
        assertFalse(timer.pollExpired())
        // 未开计划就 poll，不能凭空造出到期事件，也不能让随后的调用变成 true。
        assertFalse(timer.pollExpired())
        assertNull(timer.durationMinutes)
    }

    @Test
    fun presetMinutesAreExactlyFiveFifteenThirtySixty() {
        assertEquals(listOf(5, 15, 30, 60), TtsSleepTimer.PRESET_MINUTES)
    }

    @Test
    fun eachPresetStartsWithExactFullDuration() {
        for (minutes in TtsSleepTimer.PRESET_MINUTES) {
            val clock = FakeClock(7_000_000L)
            val timer = timer(clock)
            timer.start(minutes)
            assertEquals("start(" + minutes + ") durationMinutes", minutes, timer.durationMinutes)
            assertEquals(
                "start(" + minutes + ") full remaining",
                minutes * oneMinute,
                remaining(timer),
            )
            assertFalse("start(" + minutes + ") must not be born expired", timer.pollExpired())
        }
    }

    // ---------------------------------------------------------------- 倒计时

    @Test
    fun countdownFollowsInjectedClockExactly() {
        val clock = FakeClock(0L)
        val timer = timer(clock)
        timer.start(5)
        assertEquals(fiveMinutes, remaining(timer))

        clock.advance(1L)
        assertEquals(fiveMinutes - 1L, remaining(timer))

        clock.advance(999L)
        assertEquals(fiveMinutes - 1_000L, remaining(timer))

        clock.advance(89_000L)
        assertEquals(fiveMinutes - 90_000L, remaining(timer))

        clock.advance(120_000L)
        assertEquals(fiveMinutes - 210_000L, remaining(timer))
    }

    @Test
    fun repeatedReadsAndNonExpiringPollsNeverShiftDeadline() {
        val clock = FakeClock(0L)
        val timer = timer(clock)
        timer.start(15)

        repeat(25) {
            assertEquals(fifteenMinutes, remaining(timer))
            assertFalse(timer.pollExpired())
        }

        clock.advance(60_000L)
        repeat(5) {
            assertEquals(fifteenMinutes - oneMinute, remaining(timer))
        }

        clock.advance(fifteenMinutes - oneMinute)
        assertEquals(0L, remaining(timer))
        assertTrue(timer.pollExpired())
    }

    // ---------------------------------------------------------------- 到期边界

    @Test
    fun oneMillisBeforeDeadlineIsStillRunning() {
        val clock = FakeClock(42_000L)
        val timer = timer(clock)
        timer.start(5)

        clock.advance(fiveMinutes - 1L)
        assertEquals(1L, remaining(timer))
        assertEquals(5, timer.durationMinutes)
        assertFalse(timer.pollExpired())
        assertEquals(1L, remaining(timer))
    }

    @Test
    fun exactDeadlineReadsZeroButKeepsPlanUntilPolled() {
        val clock = FakeClock(42_000L)
        val timer = timer(clock)
        timer.start(30)

        clock.advance(30 * oneMinute)
        assertEquals(0L, remaining(timer))
        assertEquals(0L, remaining(timer))
        // 到期但未消费：计划还在，宿主这一帧先显示 0:00 再停朗读。
        assertEquals(30, timer.durationMinutes)

        assertTrue(timer.pollExpired())
        assertNull(timer.durationMinutes)
        assertNull(timer.remainingMillis())
    }

    @Test
    fun pastDeadlineKeepsReturningZeroBeforePoll() {
        val clock = FakeClock(0L)
        val timer = timer(clock)
        timer.start(60)

        clock.advance(sixtyMinutes + 90_000L)
        assertEquals(0L, remaining(timer))
        assertEquals(0L, remaining(timer))
        assertTrue(timer.pollExpired())
        assertNull(timer.remainingMillis())
    }

    @Test
    fun pollExpiredReturnsTrueExactlyOnce() {
        val clock = FakeClock(0L)
        val timer = timer(clock)
        timer.start(5)

        clock.advance(fiveMinutes)
        assertTrue("first poll after deadline", timer.pollExpired())
        clock.advance(1L)
        assertFalse("second poll", timer.pollExpired())
        clock.advance(60 * oneMinute)
        assertFalse("third poll much later", timer.pollExpired())
        assertNull(timer.remainingMillis())
        assertNull(timer.durationMinutes)
    }

    @Test
    fun pollExpiredOnlyFiresOnceWhenTickedAcrossDeadline() {
        val clock = FakeClock(0L)
        val timer = timer(clock)
        timer.start(5)
        var fired = 0

        // 每秒 tick 一次，跨过 5 分钟：只有越过 deadline 的那一次是 true。
        repeat(5 * 60 + 3) {
            clock.advance(1_000L)
            if (timer.pollExpired()) fired += 1
        }
        assertEquals(1, fired)
        assertNull(timer.remainingMillis())
    }

    // ---------------------------------------------------------------- 取消

    @Test
    fun cancelClearsPlanAndIsIdempotent() {
        val clock = FakeClock(0L)
        val timer = timer(clock)
        timer.start(15)
        clock.advance(3_000L)

        timer.cancel()
        assertNull(timer.durationMinutes)
        assertNull(timer.remainingMillis())
        assertFalse(timer.pollExpired())

        // 第二次、第三次取消不抛、不复活计划。
        timer.cancel()
        timer.cancel()
        assertNull(timer.durationMinutes)
        assertFalse(timer.pollExpired())
    }

    @Test
    fun cancelWithoutAnyPlanIsNoOp() {
        val timer = timer(FakeClock(99L))
        timer.cancel()
        assertNull(timer.durationMinutes)
        assertNull(timer.remainingMillis())
        assertFalse(timer.pollExpired())
    }

    @Test
    fun cancelAfterDeadlineSilencesPendingExpiry() {
        val clock = FakeClock(0L)
        val timer = timer(clock)
        timer.start(5)
        clock.advance(fiveMinutes + 5L)

        timer.cancel()
        assertFalse("cancel must swallow the pending expiry", timer.pollExpired())
        assertNull(timer.durationMinutes)
    }

    // ---------------------------------------------------------------- 重启覆盖

    @Test
    fun startOverwritesPlanFromCurrentMoment() {
        val clock = FakeClock(0L)
        val timer = timer(clock)
        timer.start(5)
        clock.advance(60_000L)

        timer.start(15)
        assertEquals(15, timer.durationMinutes)
        assertEquals("restart counts from now, not from the old deadline", fifteenMinutes, remaining(timer))

        clock.advance(fifteenMinutes - 1L)
        assertEquals(1L, remaining(timer))
        clock.advance(1L)
        assertTrue(timer.pollExpired())
    }

    @Test
    fun startWithSamePresetRestartsCountdown() {
        val clock = FakeClock(0L)
        val timer = timer(clock)
        timer.start(5)
        clock.advance(4 * oneMinute)

        timer.start(5)
        assertEquals(fiveMinutes, remaining(timer))

        clock.advance(4 * oneMinute)
        assertEquals(oneMinute, remaining(timer))
        assertFalse(timer.pollExpired())
    }

    @Test
    fun startAfterExpiryBeforePollReplacesStalePlan() {
        val clock = FakeClock(0L)
        val timer = timer(clock)
        timer.start(5)
        clock.advance(fiveMinutes + 10L)
        assertEquals(0L, remaining(timer))

        timer.start(30)
        assertEquals(30, timer.durationMinutes)
        assertEquals(30 * oneMinute, remaining(timer))
        assertFalse(timer.pollExpired())
    }

    // ---------------------------------------------------------------- 非法 start

    @Test
    fun invalidStartThrowsAndKeepsRunningPlan() {
        val clock = FakeClock(1_000L)
        val timer = timer(clock)
        timer.start(15)
        clock.advance(1_000L)

        val rejected = intArrayOf(0, 1, 4, 6, 7, 14, 29, 31, 45, 61, 120, -5, -60, Int.MIN_VALUE, Int.MAX_VALUE)
        for (minutes in rejected) {
            assertThrows(
                "start(" + minutes + ") must be rejected",
                IllegalArgumentException::class.java,
            ) { timer.start(minutes) }
            assertEquals("durationMinutes after rejected start(" + minutes + ")", 15, timer.durationMinutes)
            assertEquals(
                "remaining after rejected start(" + minutes + ")",
                fifteenMinutes - 1_000L,
                remaining(timer),
            )
            assertFalse("rejected start(" + minutes + ") must not expire the plan", timer.pollExpired())
        }
    }

    @Test
    fun invalidStartOnFreshTimerLeavesNoPlan() {
        val timer = timer(FakeClock(0L))
        assertThrows(IllegalArgumentException::class.java) { timer.start(0) }
        assertThrows(IllegalArgumentException::class.java) { timer.start(90) }
        assertNull(timer.durationMinutes)
        assertNull(timer.remainingMillis())
        assertFalse(timer.pollExpired())
    }

    // ---------------------------------------------------------------- 时钟异常与饱和

    @Test
    fun clockGoingBackwardsClampsToFullDuration() {
        val clock = FakeClock(1_000_000L)
        val timer = timer(clock)
        timer.start(5)

        clock.now = 0L
        assertEquals("rewound clock must not exceed the planned duration", fiveMinutes, remaining(timer))
        assertFalse(timer.pollExpired())

        clock.now = 1_000_000L + fiveMinutes - 1L
        assertEquals(1L, remaining(timer))
        clock.now = 1_000_000L + fiveMinutes
        assertEquals(0L, remaining(timer))
        assertTrue(timer.pollExpired())
    }

    @Test
    fun negativeStartClockIsClampedToZero() {
        val clock = FakeClock(-1_000L)
        val timer = timer(clock)
        timer.start(5)

        // 契约：nowMillis 是单调时钟，负值按 0 处理；启动时的负刻度不能从 5 分钟里扣掉。
        assertEquals(fiveMinutes, remaining(timer))

        clock.now = 0L
        assertEquals("crossing into non-negative time must keep the whole plan", fiveMinutes, remaining(timer))
        assertFalse(timer.pollExpired())

        clock.advance(fiveMinutes - 1L)
        assertEquals(1L, remaining(timer))
        clock.advance(1L)
        assertEquals(0L, remaining(timer))
        assertTrue(timer.pollExpired())
    }

    @Test
    fun negativeClockAdvanceBelowZeroDoesNotConsumePlan() {
        val clock = FakeClock(-5_000_000L)
        val timer = timer(clock)
        timer.start(5)
        assertEquals(fiveMinutes, remaining(timer))

        // 仍在负区间里推进：三个入口都按 0 计，所以这一步不许扣时长。
        clock.advance(60_000L)
        assertEquals(fiveMinutes, remaining(timer))
        assertFalse(timer.pollExpired())

        clock.now = 60_000L
        assertEquals(fiveMinutes - oneMinute, remaining(timer))

        clock.now = fiveMinutes
        assertEquals(0L, remaining(timer))
        assertTrue(timer.pollExpired())
    }

    @Test
    fun startAtLongMinKeepsFullDurationWhenClockReturnsToZero() {
        val clock = FakeClock(Long.MIN_VALUE)
        val timer = timer(clock)
        timer.start(5)

        assertEquals("start at Long.MIN_VALUE must be read as 0", fiveMinutes, remaining(timer))
        clock.now = 0L
        assertEquals("returning to 0 keeps the full plan", fiveMinutes, remaining(timer))
        assertFalse(timer.pollExpired())

        clock.advance(fiveMinutes)
        assertEquals(0L, remaining(timer))
        assertTrue(timer.pollExpired())
    }

    @Test
    fun rewindingIntoNegativeTimeNeverExpiresEarly() {
        val clock = FakeClock(600_000L)
        val timer = timer(clock)
        timer.start(5)
        clock.advance(100_000L)
        assertEquals(fiveMinutes - 100_000L, remaining(timer))

        // 回拨到负值：按 0 计，剩余回到总时长，但绝不因为负刻度提前到期。
        clock.now = -1L
        assertEquals(fiveMinutes, remaining(timer))
        assertFalse(timer.pollExpired())

        clock.now = Long.MIN_VALUE
        assertEquals(fiveMinutes, remaining(timer))
        assertFalse(timer.pollExpired())
    }

    @Test
    fun extremeNegativeClockDoesNotOverflowRemaining() {
        val clock = FakeClock(0L)
        val live = timer(clock)
        live.start(5)

        clock.now = Long.MIN_VALUE
        assertEquals("deadline - Long.MIN_VALUE must not wrap negative", fiveMinutes, remaining(live))

        clock.now = -1L
        assertEquals(fiveMinutes, remaining(live))

        clock.now = fiveMinutes
        assertEquals(0L, remaining(live))
        assertTrue(live.pollExpired())
    }

    @Test
    fun deadlineAdditionSaturatesNearLongMax() {
        val clock = FakeClock(Long.MAX_VALUE - 1_000L)
        val timer = timer(clock)
        timer.start(5)

        assertEquals("saturated deadline keeps the real remaining time", 1_000L, remaining(timer))
        assertFalse(timer.pollExpired())

        clock.advance(999L)
        assertEquals(1L, remaining(timer))
        clock.advance(1L)
        assertEquals(0L, remaining(timer))
        assertTrue(timer.pollExpired())
    }

    @Test
    fun startAtLongMaxIsImmediatelyDueWithoutOverflow() {
        val clock = FakeClock(Long.MAX_VALUE)
        val timer = timer(clock)
        timer.start(60)

        assertEquals(0L, remaining(timer))
        assertTrue(timer.pollExpired())
        assertFalse(timer.pollExpired())
    }

    @Test
    fun saturatedDeadlineWithRewoundClockStillCapsAtPreset() {
        val clock = FakeClock(Long.MAX_VALUE - 1_000L)
        val timer = timer(clock)
        timer.start(5)
        assertEquals(1_000L, remaining(timer))

        // 饱和 deadline 加上极端回拨会让原始差值 Long 溢出：必须夹到预设总时长，而不是 Long.MAX_VALUE。
        clock.now = Long.MIN_VALUE
        assertEquals(fiveMinutes, remaining(timer))
        assertFalse(timer.pollExpired())
    }

    @Test
    fun remainingStaysWithinZeroAndPresetForAnyClock() {
        val clock = FakeClock(0L)
        val timer = timer(clock)
        timer.start(5)

        val samples = longArrayOf(
            Long.MIN_VALUE, -1_000_000L, -1L, 0L, 1L,
            fiveMinutes - 1L, fiveMinutes, fiveMinutes + 1L, Long.MAX_VALUE,
        )
        for (sample in samples) {
            clock.now = sample
            val value = remaining(timer)
            assertTrue("sample=" + sample + " remaining=" + value, value in 0L..fiveMinutes)
        }
        assertTrue(timer.pollExpired())
    }

    // ---------------------------------------------------------------- 文案

    @Test
    fun formatRemainingRoundsSecondsUp() {
        assertEquals("5:00", TtsSleepTimer.formatRemaining(300_000L))
        assertEquals("5:00", TtsSleepTimer.formatRemaining(299_999L))
        assertEquals("5:00", TtsSleepTimer.formatRemaining(299_001L))
        assertEquals("4:59", TtsSleepTimer.formatRemaining(299_000L))
        assertEquals("0:01", TtsSleepTimer.formatRemaining(1L))
        assertEquals("0:01", TtsSleepTimer.formatRemaining(999L))
        assertEquals("0:01", TtsSleepTimer.formatRemaining(1_000L))
        assertEquals("0:02", TtsSleepTimer.formatRemaining(1_001L))
        assertEquals("1:00", TtsSleepTimer.formatRemaining(59_001L))
        assertEquals("1:00", TtsSleepTimer.formatRemaining(60_000L))
        assertEquals("1:01", TtsSleepTimer.formatRemaining(61_000L))
        assertEquals("59:59", TtsSleepTimer.formatRemaining(3_599_000L))
        assertEquals("60:00", TtsSleepTimer.formatRemaining(3_600_000L))
        assertEquals("60:01", TtsSleepTimer.formatRemaining(3_600_001L))
        assertEquals("100:00", TtsSleepTimer.formatRemaining(5_999_999L))
    }

    @Test
    fun formatRemainingPadsSecondsToTwoDigits() {
        assertEquals("0:00", TtsSleepTimer.formatRemaining(0L))
        assertEquals("0:05", TtsSleepTimer.formatRemaining(5_000L))
        assertEquals("0:09", TtsSleepTimer.formatRemaining(8_001L))
        assertEquals("0:10", TtsSleepTimer.formatRemaining(9_001L))
        assertEquals("1:05", TtsSleepTimer.formatRemaining(65_000L))
        assertEquals("10:00", TtsSleepTimer.formatRemaining(600_000L))
    }

    @Test
    fun formatRemainingTreatsNegativeAsZero() {
        assertEquals("0:00", TtsSleepTimer.formatRemaining(-1L))
        assertEquals("0:00", TtsSleepTimer.formatRemaining(-999L))
        assertEquals("0:00", TtsSleepTimer.formatRemaining(-fiveMinutes))
        assertEquals("0:00", TtsSleepTimer.formatRemaining(Long.MIN_VALUE))
    }

    @Test
    fun formatRemainingHandlesLongMaxWithoutOverflow() {
        // 9223372036854775807ms = 9223372036854776s（向上取整）= 153722867280912:56
        assertEquals("153722867280912:56", TtsSleepTimer.formatRemaining(Long.MAX_VALUE))
    }

    @Test
    fun countdownTextTracksRemainingMillis() {
        val clock = FakeClock(0L)
        val timer = timer(clock)
        timer.start(5)

        assertEquals("5:00", TtsSleepTimer.formatRemaining(remaining(timer)))
        clock.advance(1L)
        assertEquals("5:00", TtsSleepTimer.formatRemaining(remaining(timer)))
        clock.advance(60_000L)
        assertEquals("4:00", TtsSleepTimer.formatRemaining(remaining(timer)))
        clock.advance(4 * oneMinute - 2L)
        assertEquals("0:01", TtsSleepTimer.formatRemaining(remaining(timer)))
        clock.advance(1L)
        assertEquals("0:00", TtsSleepTimer.formatRemaining(remaining(timer)))
    }
}
