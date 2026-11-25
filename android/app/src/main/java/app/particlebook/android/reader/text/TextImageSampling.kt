package app.particlebook.android.reader.text

/**
 * 正文插图的解码采样参数。
 *
 * 插图按原尺寸解码会把进程打爆：一卷扫描版漫画的单页可能 4000x6000，ARGB_8888 解出来就是
 * 96 MB，两三张就够让低端机把阅读器杀掉。这里只回答两个纯算术问题——「按多大步长采样」和
 * 「解出来大概多少字节」——不碰 BitmapFactory，因此可以在 JVM 单测里穷举边界。
 *
 * 采样语义与 BitmapFactory.Options.inSampleSize 一致：每 n 个像素取一个，解码后的边长是
 * ceil(原边长 / n)，n 必须是 2 的幂。用 1 表示「不采样」。
 */
internal object TextImageSampling {

    /** 解码后长边上限（px）。1600 在 3x 屏上约等于 533dp，一屏放得下且不吃内存。 */
    const val MAX_EDGE_PX: Int = 1600

    /**
     * 采样步长上限 2^30。
     *
     * inSampleSize 是 Int，2^31 会溢出成负数；而「长边 Int.MAX_VALUE 配 maxEdgePx = 1」的
     * 理论最优值恰好是 2^31。只有这种病态输入才会触顶：触顶后宁可多解出两个像素，也不能
     * 返回负数让 BitmapFactory 抛 IllegalArgumentException。
     */
    private const val MAX_SAMPLE_SIZE: Long = 1L shl 30

    /**
     * 返回给 inSampleSize 的最小 2 的幂。
     *
     * - 宽或高非正（未测量、脏数据）、maxEdgePx 非正：返回 1，交给调用方按原图处理；
     * - 长边 <= maxEdgePx：返回 1；
     * - 否则返回最小的 2 的幂 n，使 ceil(长边 / n) <= maxEdgePx。
     */
    fun sampleSizeFor(width: Int, height: Int, maxEdgePx: Int = MAX_EDGE_PX): Int {
        if (width <= 0 || height <= 0 || maxEdgePx <= 0) return 1
        val longEdge = maxOf(width, height).toLong()
        if (longEdge <= maxEdgePx) return 1
        // 整数除法下 ceil(longEdge / n) <= maxEdgePx 等价于 longEdge <= maxEdgePx * n，
        // 用乘法判定就不必先算除法，也不会在 longEdge 接近 Int.MAX_VALUE 时溢出。
        val budget = maxEdgePx.toLong()
        var sample = 1L
        while (sample < MAX_SAMPLE_SIZE && longEdge > budget * sample) {
            sample = sample shl 1
        }
        return sample.toInt()
    }

    /**
     * 估算采样解码后的字节数：ceil(width / sample) * ceil(height / sample) * bytesPerPixel。
     *
     * 全程 64 位：像素数最多约 4.6e18，再乘 bytesPerPixel 可能越界，越界时饱和到
     * [Long.MAX_VALUE]（用于日志与「这张图太大了」的判定，正数即可），绝不回绕成负数。
     * 维度非正、bytesPerPixel 非正时返回 0；sampleSize <= 0 按 1（不采样）处理。
     */
    fun decodedBytes(width: Int, height: Int, sampleSize: Int, bytesPerPixel: Int = 4): Long {
        if (width <= 0 || height <= 0 || bytesPerPixel <= 0) return 0L
        val sample = if (sampleSize <= 0) 1 else sampleSize
        val sampledWidth = ceilDiv(width, sample)
        val sampledHeight = ceilDiv(height, sample)
        val pixels = sampledWidth * sampledHeight
        val perPixel = bytesPerPixel.toLong()
        if (pixels > Long.MAX_VALUE / perPixel) return Long.MAX_VALUE
        return pixels * perPixel
    }

    /**
     * 非负整数除法向上取整，[sample] 由调用方保证 >= 1。
     *
     * 逐像素采样时最后不满一组的那一段仍会产出一个像素，所以 1px 宽的图无论 sample 多大都
     * 算 1 个像素，而不是 0。
     */
    private fun ceilDiv(value: Int, sample: Int): Long =
        (value.toLong() + sample - 1L) / sample
}
