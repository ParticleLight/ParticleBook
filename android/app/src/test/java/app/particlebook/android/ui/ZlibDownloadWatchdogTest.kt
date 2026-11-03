package app.particlebook.android.ui

import app.particlebook.android.zlib.ZlibStallDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D4：45s 零增长看门狗（纯 JVM，注入时钟）。 */
class ZlibDownloadWatchdogTest {

    @Test
    fun firesAfterFortyFiveSecondsWithoutGrowth() {
        var now = 0L
        val watchdog = ZlibDownloadStallWatchdog(stallMs = ZlibStallDetector.STALL_TIMEOUT_MS) { now }
        var fired = 0
        watchdog.tick(bytes = 100L, stillCurrent = true) { fired += 1 }
        // 每 2s 采样一次，字节数一直不变，到 44s 仍不判停滞。
        repeat(22) {
            now += ZlibStallDetector.TICK_MS
            watchdog.tick(bytes = 100L, stillCurrent = true) { fired += 1 }
        }
        assertEquals("44s 时不应判停滞", 0, fired)
        assertFalse(watchdog.isFired)
        now += ZlibStallDetector.TICK_MS
        assertTrue(watchdog.tick(bytes = 100L, stillCurrent = true) { fired += 1 })
        assertEquals(1, fired)
        assertTrue(watchdog.isFired)
        // 只触发一次。
        now += ZlibStallDetector.TICK_MS
        assertFalse(watchdog.tick(bytes = 100L, stillCurrent = true) { fired += 1 })
        assertEquals(1, fired)
    }

    @Test
    fun byteGrowthResetsIdleTimer() {
        var now = 0L
        val watchdog = ZlibDownloadStallWatchdog(stallMs = 45_000L) { now }
        var fired = 0
        watchdog.tick(bytes = 100L, stillCurrent = true) { fired += 1 }
        now = 40_000L
        watchdog.tick(bytes = 100L, stillCurrent = true) { fired += 1 }
        now = 40_500L
        assertFalse(watchdog.tick(bytes = 8_192L, stillCurrent = true) { fired += 1 })
        now = 85_000L
        assertFalse(
            "字节增长后从 40.5s 重新计 45s",
            watchdog.tick(bytes = 8_192L, stillCurrent = true) { fired += 1 },
        )
        now = 85_600L
        assertTrue(watchdog.tick(bytes = 8_192L, stillCurrent = true) { fired += 1 })
        assertEquals(1, fired)
    }

    @Test
    fun staleRunStopsWatchdogWithoutCancelling() {
        var now = 0L
        val watchdog = ZlibDownloadStallWatchdog(stallMs = 45_000L) { now }
        var fired = 0
        watchdog.tick(bytes = 0L, stillCurrent = true) { fired += 1 }
        now = 60_000L
        assertFalse(
            "任务已换代：看门狗停表且不触发",
            watchdog.tick(bytes = 0L, stillCurrent = false) { fired += 1 },
        )
        assertTrue(watchdog.isStopped)
        assertFalse(watchdog.isFired)
        assertEquals(0, fired)
        now = 120_000L
        assertFalse(watchdog.tick(bytes = 0L, stillCurrent = true) { fired += 1 })
        assertEquals(0, fired)
    }
}
