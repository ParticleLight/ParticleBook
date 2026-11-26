package app.particlebook.android.ui

import org.junit.Assert.*
import org.junit.Test

class ReaderSleepTimerStateTest {
    private var now = 1_000L
    private fun state() = ReaderSleepTimerState { now }

    @Test fun initialStateHasNoCountdownOrOpenSheet() {
        val state = state()
        assertNull(state.selectedMinutes)
        assertNull(state.remainingMillis)
        assertNull(state.label)
        assertFalse(state.sheetOpen)
        assertFalse(state.refresh())
    }

    @Test fun selectingPresetClosesSheetAndPublishesCountdown() {
        val state = state()
        state.open()
        state.select(15)
        assertEquals(15, state.selectedMinutes)
        assertEquals(900_000L, state.remainingMillis)
        assertEquals("15:00", state.label)
        assertFalse(state.sheetOpen)
    }

    @Test fun dismissingSheetKeepsOriginalDeadline() {
        val state = state()
        state.select(5)
        now += 30_000L
        state.open()
        state.dismiss()
        assertFalse(state.refresh())
        assertFalse(state.sheetOpen)
        assertEquals(270_000L, state.remainingMillis)
        assertEquals("4:30", state.label)
    }

    @Test fun selectingOffClearsPlanWithoutExpiryEvent() {
        val state = state()
        state.select(5)
        state.open()
        state.select(null)
        now += 600_000L
        assertNull(state.selectedMinutes)
        assertNull(state.remainingMillis)
        assertFalse(state.sheetOpen)
        assertFalse(state.refresh())
    }

    @Test fun choosingSamePresetStartsFullIntervalAgain() {
        val state = state()
        state.select(5)
        val firstRevision = state.revision
        now += 120_000L
        state.select(5)
        assertEquals(300_000L, state.remainingMillis)
        assertTrue(state.revision > firstRevision)
        now += 180_000L
        assertFalse(state.refresh())
        assertEquals(120_000L, state.remainingMillis)
    }

    @Test fun expiryClosesSheetAndClearsAllVisibleStateExactlyOnce() {
        val state = state()
        state.select(5)
        state.open()
        now += 300_000L
        assertTrue(state.refresh())
        assertNull(state.selectedMinutes)
        assertNull(state.remainingMillis)
        assertNull(state.label)
        assertFalse(state.sheetOpen)
        assertFalse(state.refresh())
    }

    @Test fun cancelClosesUnconfiguredSheetAndIsIdempotent() {
        val state = state()
        state.open()
        state.cancel()
        assertFalse(state.sheetOpen)
        val revision = state.revision
        state.cancel()
        assertEquals(revision, state.revision)
        assertFalse(state.refresh())
    }

    @Test fun invalidSelectionKeepsPlanSheetAndRevision() {
        val state = state()
        state.select(30)
        state.open()
        val revision = state.revision
        try {
            state.select(1)
            fail("unsupported timer duration must be rejected")
        } catch (_: IllegalArgumentException) {
            assertEquals(30, state.selectedMinutes)
            assertEquals(1_800_000L, state.remainingMillis)
            assertTrue(state.sheetOpen)
            assertEquals(revision, state.revision)
        }
    }

    @Test fun startingAgainAfterExpiryArmsNewSingleExpiry() {
        val state = state()
        state.select(5)
        now += 300_000L
        assertTrue(state.refresh())
        state.select(60)
        assertEquals("60:00", state.label)
        now += 3_600_000L
        assertTrue(state.refresh())
        assertFalse(state.refresh())
    }

    // 计时进行中点「关闭定时」：必须清计划并 bump revision（LaunchedEffect 立刻重生），
    // 且之后不再冒到期事件——否则用户关了定时，下一次 tick 仍会停朗读。
    @Test fun cancelActivePlanClearsCountdownAndBumpsRevision() {
        val state = state()
        state.select(5)
        state.open()
        val revision = state.revision
        state.cancel()
        assertTrue(state.revision > revision)
        assertNull(state.selectedMinutes)
        assertNull(state.remainingMillis)
        assertNull(state.label)
        assertFalse(state.sheetOpen)
        now += 300_000L
        assertFalse(state.refresh())
    }

    // 弹层开着的每一秒 tick 只更新剩余：不许关弹层，也不许碰 revision（碰了会让倒计时循环每秒重启）。
    @Test fun tickRefreshKeepsOpenSheetWhileRunning() {
        val state = state()
        state.select(5)
        state.open()
        now += 60_000L
        assertFalse(state.refresh())
        assertTrue(state.sheetOpen)
        assertEquals(240_000L, state.remainingMillis)
        assertEquals("4:00", state.label)
        val revision = state.revision
        state.refresh()
        state.refresh()
        assertEquals(revision, state.revision)
        assertTrue(state.sheetOpen)
    }

    // 5 分钟跑着改选 15 分钟：旧 deadline 作废，跨过旧截止点也不许停朗读（防「延长后第 5 分钟被停」）。
    @Test fun switchingPresetDiscardsOldDeadline() {
        val state = state()
        state.select(5)
        now += 120_000L
        state.select(15)
        assertEquals(15, state.selectedMinutes)
        assertEquals(900_000L, state.remainingMillis)
        now += 180_000L
        assertFalse(state.refresh())
        assertEquals(720_000L, state.remainingMillis)
        assertEquals("12:00", state.label)
        now += 720_000L
        assertTrue(state.refresh())
        assertFalse(state.refresh())
    }

    // 弹层开合是纯展示：open/dismiss/refresh 都不许改 revision，否则打开面板会把到期判定推迟最多一个 tick。
    @Test fun sheetTogglesNeverRestartCountdownEffect() {
        val state = state()
        state.select(5)
        val revision = state.revision
        state.open()
        state.dismiss()
        state.open()
        state.refresh()
        assertEquals(revision, state.revision)
        assertTrue(state.sheetOpen)
        assertEquals(300_000L, state.remainingMillis)
    }
}
