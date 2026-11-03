package app.particlebook.android.zlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1c 收尾②：45s 零增长看门狗（对齐桌面 ZLibraryDownload.cpp 的 kStallMs=45000）。
 * 纯时间逻辑，用可注入时钟验证，不依赖真实网络。
 */
class ZlibStallDetectorTest {

    @Test
    fun stalledOnlyAfter45sWithoutByteGrowth() {
        var now = 1_000L
        val detector = ZlibStallDetector(clockMs = { now })
        detector.onBytes(0L)

        now += 44_000L
        detector.onBytes(0L)
        assertFalse("44s 零增长还不算停滞", detector.stalled())

        now += 1_000L
        detector.onBytes(0L)
        assertTrue("连续 45s 零增长必须判停滞", detector.stalled())
    }

    @Test
    fun byteGrowthResetsIdleTimer() {
        var now = 10_000L
        val detector = ZlibStallDetector(clockMs = { now })
        detector.onBytes(0L)

        // 30s 时来了 8KB：计时重置，所以 70s 时只空闲 40s。
        now += 30_000L
        detector.onBytes(8L * 1024)
        assertFalse(detector.stalled())

        now += 10_000L // 距上次增长 10s
        detector.onBytes(8L * 1024) // 数值没变，不重置
        assertFalse(detector.stalled())

        now += 35_000L // 距最近一次增长 45s
        detector.onBytes(8L * 1024)
        assertTrue(detector.stalled())
    }

    @Test
    fun idleMsTracksTimeSinceGrowth() {
        var now = 0L
        val detector = ZlibStallDetector(clockMs = { now })
        detector.onBytes(0L)
        now = 12_345L
        detector.onBytes(4L)
        now = 20_000L
        detector.onBytes(4L)
        assertEquals(7_655L, detector.idleMs())
    }

    @Test
    fun defaultsMatchDesktopParity() {
        assertEquals(45_000L, ZlibStallDetector.STALL_TIMEOUT_MS)
        assertEquals(2_000L, ZlibStallDetector.TICK_MS)
    }
}
