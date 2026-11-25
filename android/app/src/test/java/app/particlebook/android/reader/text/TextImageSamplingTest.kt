package app.particlebook.android.reader.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TextImageSampling] 的纯 JVM 单测。
 *
 * 断言口径以当前实现为准：
 * - sampleSizeFor 只看长边 = max(width, height)，返回最小的 2 的幂 n 使 ceil(长边 / n) <= 上限；
 * - 宽高非正或 maxEdgePx 非正一律返回 1（不采样）；
 * - decodedBytes 用 ceil 逐轴取整后再相乘，sampleSize <= 0 按 1，结果不会为负。
 *
 * 期望值一律用 Long 独立验算（[ceilDiv] 与实现里的除法分开写），避免「实现和断言抄同一份错」。
 */
class TextImageSamplingTest {

    /** 独立于实现的 ceil 除法，防止把同一个公式抄两遍。 */
    private fun ceilDiv(value: Int, sample: Int): Long {
        var quotient = value.toLong() / sample
        if (value.toLong() % sample != 0L) quotient += 1
        return quotient
    }

    private fun isPowerOfTwo(value: Int): Boolean = value > 0 && (value and (value - 1)) == 0

    // ------------------------------------------------------------------
    // sampleSizeFor
    // ------------------------------------------------------------------

    // 1
    @Test
    fun maxEdgeConstantIs1600() {
        assertEquals(1600, TextImageSampling.MAX_EDGE_PX)
        // 默认参数就是 MAX_EDGE_PX：显式传 1600 与走默认值必须一致。
        assertEquals(
            TextImageSampling.sampleSizeFor(3201, 900, 1600),
            TextImageSampling.sampleSizeFor(3201, 900),
        )
    }

    // 2
    @Test
    fun tinyImageNeedsNoSampling() {
        assertEquals(1, TextImageSampling.sampleSizeFor(1, 1))
        assertEquals(1, TextImageSampling.sampleSizeFor(800, 600))
        assertEquals(1, TextImageSampling.sampleSizeFor(600, 800))
        assertEquals(1, TextImageSampling.sampleSizeFor(1599, 900))
    }

    // 3
    @Test
    fun exactlyAtLimitNeedsNoSampling() {
        assertEquals(1, TextImageSampling.sampleSizeFor(1600, 900))
        assertEquals(1, TextImageSampling.sampleSizeFor(900, 1600))
        assertEquals(1, TextImageSampling.sampleSizeFor(1600, 1600))
    }

    // 4
    @Test
    fun onePixelOverLimitNeedsTwo() {
        assertEquals(2, TextImageSampling.sampleSizeFor(1601, 900))
        assertEquals(2, TextImageSampling.sampleSizeFor(900, 1601))
        assertEquals(2, TextImageSampling.sampleSizeFor(1601, 1601))
        // 采样后正好落在上限：ceil(1601 / 2) = 801 <= 1600。
        assertEquals(801L, ceilDiv(1601, 2))
    }

    // 5
    @Test
    fun doubleLimitStillNeedsTwo() {
        assertEquals(2, TextImageSampling.sampleSizeFor(3200, 1000))
        assertEquals(2, TextImageSampling.sampleSizeFor(1000, 3200))
        assertEquals(2, TextImageSampling.sampleSizeFor(3200, 3200))
        // 3199 也还是 2：ceil(3199 / 2) = 1600 够用。
        assertEquals(2, TextImageSampling.sampleSizeFor(3199, 3199))
    }

    // 6
    @Test
    fun justOverDoubleLimitNeedsFour() {
        assertEquals(4, TextImageSampling.sampleSizeFor(3201, 900))
        assertEquals(4, TextImageSampling.sampleSizeFor(900, 3201))
        assertEquals(4, TextImageSampling.sampleSizeFor(3201, 3201))
        // 2 不够：ceil(3201 / 2) = 1601 > 1600；4 够：801。
        assertEquals(1601L, ceilDiv(3201, 2))
    }

    // 7
    @Test
    fun squareAndPortraitImagesUseLongEdge() {
        assertEquals(2, TextImageSampling.sampleSizeFor(2000, 2000))
        assertEquals(4, TextImageSampling.sampleSizeFor(1000, 4000))
        assertEquals(4, TextImageSampling.sampleSizeFor(4000, 1000))
        // 宽高互换必须同解：实现只取 max，不看谁长。
        for (edge in listOf(1601, 2000, 3200, 3201, 6400)) {
            assertEquals(
                "宽高互换后采样步长应相同：edge=$edge",
                TextImageSampling.sampleSizeFor(edge, 777),
                TextImageSampling.sampleSizeFor(777, edge),
            )
        }
    }

    // 8
    @Test
    fun extremeAspectRatioUsesLongEdge() {
        // 100000 的长边：32 不够（3125 > 1600），64 够（1563）。
        assertEquals(64, TextImageSampling.sampleSizeFor(1, 100000))
        assertEquals(64, TextImageSampling.sampleSizeFor(100000, 1))
        assertEquals(64, TextImageSampling.sampleSizeFor(2, 100000))
        assertEquals(1563L, ceilDiv(100000, 64))
        assertTrue(ceilDiv(100000, 32) > 1600)
    }

    // 9
    @Test
    fun nonPositiveDimensionsNeedNoSampling() {
        assertEquals(1, TextImageSampling.sampleSizeFor(0, 5000))
        assertEquals(1, TextImageSampling.sampleSizeFor(5000, 0))
        assertEquals(1, TextImageSampling.sampleSizeFor(0, 0))
        assertEquals(1, TextImageSampling.sampleSizeFor(-1, 5000))
        assertEquals(1, TextImageSampling.sampleSizeFor(5000, -1))
        assertEquals(1, TextImageSampling.sampleSizeFor(Int.MIN_VALUE, 5000))
    }

    // 10
    @Test
    fun nonPositiveMaxEdgeNeedsNoSampling() {
        assertEquals(1, TextImageSampling.sampleSizeFor(5000, 5000, 0))
        assertEquals(1, TextImageSampling.sampleSizeFor(5000, 5000, -1))
        assertEquals(1, TextImageSampling.sampleSizeFor(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE))
    }

    // 11
    @Test
    fun customMaxEdgeIsHonoured() {
        assertEquals(2, TextImageSampling.sampleSizeFor(4000, 4000, 2000))
        assertEquals(4, TextImageSampling.sampleSizeFor(4000, 4000, 1000))
        assertEquals(2, TextImageSampling.sampleSizeFor(3000, 100, 2500))
        assertEquals(2, TextImageSampling.sampleSizeFor(5000, 5000, 4999))
        assertEquals(1, TextImageSampling.sampleSizeFor(100, 100, 100))
    }

    // 12
    @Test
    fun intMaxLongEdgeIsSafeAndSatisfiesBound() {
        val edge = Int.MAX_VALUE
        val sample = TextImageSampling.sampleSizeFor(edge, 1)

        // 结果必须是正的 2 的幂，且没有翻倍到 0 / 负数。
        assertTrue("sample 应为正数：$sample", sample > 0)
        assertTrue("sample 应是 2 的幂：$sample", isPowerOfTwo(sample))
        // 最小的 2 的幂：1600 * 2^21 = 3355443200 >= Int.MAX_VALUE > 1600 * 2^20。
        assertEquals(2097152, sample)

        // 用 Long 验算约束与最小性。
        assertTrue("ceil(Int.MAX / sample) 必须 <= 1600", ceilDiv(edge, sample) <= 1600L)
        assertTrue("再小一半就不够", ceilDiv(edge, sample / 2) > 1600L)

        // 竖图同理。
        assertEquals(sample, TextImageSampling.sampleSizeFor(1, edge))
        assertEquals(sample, TextImageSampling.sampleSizeFor(edge, edge))
    }

    // 13
    @Test
    fun pathologicalMaxEdgeStillReturnsPositivePowerOfTwo() {
        // 长边 Int.MAX_VALUE 配上限 1：理论最优是 2^31，Int 装不下。实现封顶 2^30，
        // 宁可多解一个像素也不能返回负数（inSampleSize 为负会直接抛异常）。
        val sample = TextImageSampling.sampleSizeFor(Int.MAX_VALUE, Int.MAX_VALUE, 1)
        assertEquals(1 shl 30, sample)
        assertTrue(sample > 0)
        assertTrue(isPowerOfTwo(sample))
    }

    // 14
    @Test
    fun sampleSizeIsAlwaysMinimalForSweep() {
        val edges = listOf(1601, 1602, 2000, 2399, 2400, 3199, 3200, 3201, 4000, 6400, 9999, 12345, 100000, 1048577)
        for (edge in edges) {
            val sample = TextImageSampling.sampleSizeFor(edge, edge)
            assertTrue("edge=$edge sample=$sample 应为 2 的幂", isPowerOfTwo(sample))
            assertTrue(
                "edge=$edge sample=$sample 采样后必须不超上限",
                ceilDiv(edge, sample) <= TextImageSampling.MAX_EDGE_PX.toLong(),
            )
            if (sample > 1) {
                assertTrue(
                    "edge=$edge sample=$sample 不是最小解：一半就够",
                    ceilDiv(edge, sample / 2) > TextImageSampling.MAX_EDGE_PX.toLong(),
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // decodedBytes
    // ------------------------------------------------------------------

    // 15
    @Test
    fun decodedBytesRoundsUpPerAxis() {
        // 1601x900 按 2 采样：801 * 450 * 4 = 1441800，而不是 800 * 450 * 4。
        assertEquals(1_441_800L, TextImageSampling.decodedBytes(1601, 900, 2))
        assertEquals(801L * 450L * 4L, TextImageSampling.decodedBytes(1601, 900, 2))
        // 1 像素宽的图无论怎么采样都还剩 1 个像素。
        assertEquals(4L, TextImageSampling.decodedBytes(1, 1, 1600))
        assertEquals(801L * 1L * 4L, TextImageSampling.decodedBytes(1601, 1, 2))
        // 整除时不额外加一。
        assertEquals(1600L * 900L * 4L, TextImageSampling.decodedBytes(3200, 1800, 2))
    }

    // 16
    @Test
    fun decodedBytesTreatsNonPositiveSampleAsOne() {
        assertEquals(40_000L, TextImageSampling.decodedBytes(100, 100, 0))
        assertEquals(40_000L, TextImageSampling.decodedBytes(100, 100, -7))
        assertEquals(2_244L, TextImageSampling.decodedBytes(17, 33, Int.MIN_VALUE))
        assertEquals(
            TextImageSampling.decodedBytes(17, 33, 1),
            TextImageSampling.decodedBytes(17, 33, 0),
        )
    }

    // 17
    @Test
    fun decodedBytesHonoursBytesPerPixel() {
        assertEquals(15_000L, TextImageSampling.decodedBytes(100, 50, 1, 3))
        assertEquals(5_000L, TextImageSampling.decodedBytes(100, 50, 1, 1))
        assertEquals(0L, TextImageSampling.decodedBytes(100, 50, 1, 0))
        assertEquals(0L, TextImageSampling.decodedBytes(100, 50, 1, -4))
        // RGB_565 = 2 字节/像素。
        assertEquals(50_000L, TextImageSampling.decodedBytes(100, 250, 1, 2))
    }

    // 18
    @Test
    fun decodedBytesForNonPositiveDimensionIsZero() {
        assertEquals(0L, TextImageSampling.decodedBytes(0, 100, 1))
        assertEquals(0L, TextImageSampling.decodedBytes(100, 0, 1))
        assertEquals(0L, TextImageSampling.decodedBytes(-5, 100, 1))
        assertEquals(0L, TextImageSampling.decodedBytes(Int.MIN_VALUE, Int.MIN_VALUE, 1))
    }

    // 19
    @Test
    fun decodedBytesSaturatesRatherThanWrappingNegative() {
        // (2^31-1)^2 * 4 约 1.8e19，超出 Long；饱和到 Long.MAX_VALUE，保持正数。
        val worst = TextImageSampling.decodedBytes(Int.MAX_VALUE, Int.MAX_VALUE, 1)
        assertEquals(Long.MAX_VALUE, worst)
        assertTrue(worst > 0)

        // 尚未越界的大值仍要精确：2147483647 * 1 * 4。
        assertEquals(8_589_934_588L, TextImageSampling.decodedBytes(Int.MAX_VALUE, 1, 1))
        assertEquals(8_589_934_588L, TextImageSampling.decodedBytes(1, Int.MAX_VALUE, 1))
    }

    // 20
    @Test
    fun sampledDecodedSizeStaysWithinMemoryBudget() {
        val dims = listOf(
            1601 to 900, 3200 to 3200, 3201 to 900, 4000 to 6000,
            1 to 100000, 100000 to 1, Int.MAX_VALUE to 1, 1080 to 1920,
        )
        val budget = TextImageSampling.MAX_EDGE_PX.toLong() * TextImageSampling.MAX_EDGE_PX.toLong() * 4L
        for ((width, height) in dims) {
            val sample = TextImageSampling.sampleSizeFor(width, height)
            val bytes = TextImageSampling.decodedBytes(width, height, sample)
            assertTrue("width=$width height=$height sample=$sample bytes=$bytes 应为正", bytes > 0L)
            assertTrue("采样后不应超过长边上限对应的预算：sample=$sample bytes=$bytes", bytes <= budget)
            assertTrue("采样步长应为 2 的幂：$sample", isPowerOfTwo(sample))
        }
    }
}
