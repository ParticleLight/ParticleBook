package app.particlebook.android.ui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** U13：进度节流（严格时间窗 + CAS 单发）。 */
class ZlibProgressThrottleTest {

    @Test
    fun throttlesWithinInterval() {
        var now = 1_000L
        val throttle = ZlibProgressThrottle(intervalMs = 150L) { now }
        assertTrue(throttle.shouldNotify(received = 100L, total = 10_000L))
        assertFalse(throttle.shouldNotify(received = 200L, total = 10_000L))
        now = 1_149L
        assertFalse(throttle.shouldNotify(received = 300L, total = 10_000L))
        now = 1_150L
        assertTrue(throttle.shouldNotify(received = 400L, total = 10_000L))
    }

    @Test
    fun lastChunkAlwaysNotifies() {
        var now = 1_000L
        val throttle = ZlibProgressThrottle(intervalMs = 150L) { now }
        assertTrue(throttle.shouldNotify(received = 100L, total = 1_000L))
        assertTrue("完成包不能被节流吞掉", throttle.shouldNotify(received = 1_000L, total = 1_000L))
    }

    @Test
    fun onlyOneThreadWinsWhenIntervalElapsed() {
        var now = 1_000L
        val throttle = ZlibProgressThrottle(intervalMs = 0L) { now }
        throttle.shouldNotify(received = 1L, total = 100L)
        now = 2_000L
        val winners = AtomicInteger(0)
        val start = CountDownLatch(1)
        val threads = (1..8).map {
            Thread {
                start.await()
                if (throttle.shouldNotify(received = 2L, total = 100L, nowMs = 2_000L)) {
                    winners.incrementAndGet()
                }
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals("同一时刻只应有一次通知", 1, winners.get())
    }
}
