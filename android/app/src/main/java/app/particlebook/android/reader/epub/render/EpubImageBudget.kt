package app.particlebook.android.reader.epub.render

/**
 * 单章图片内存预算（ARGB_8888，宽×高×4）。
 *
 * 渲染端只按最终显示尺寸解码；同一章内累计解码字节超过 [maxBytes] 时后续图片退化为占位。
 * 纯 JVM 类，便于单测；不持有 Bitmap。
 */
class EpubImageBudget(val maxBytes: Long = DEFAULT_MAX_BYTES) {

    var usedBytes: Long = 0L
        private set

    val availableBytes: Long
        get() = (maxBytes - usedBytes).coerceAtLeast(0L)

    fun bytesFor(width: Int, height: Int): Long {
        if (width <= 0 || height <= 0) return 0L
        return width.toLong() * height.toLong() * 4L
    }

    /** 预留一块解码后位图的内存；放不下返回 false，不改变 usedBytes。 */
    fun tryReserve(width: Int, height: Int): Boolean {
        val bytes = bytesFor(width, height)
        if (bytes <= 0L) return false
        return tryReserveBytes(bytes)
    }

    fun tryReserveBytes(bytes: Long): Boolean {
        if (bytes <= 0L) return false
        if (bytes > maxBytes || usedBytes > maxBytes - bytes) return false
        usedBytes += bytes
        return true
    }

    fun release(bytes: Long) {
        if (bytes <= 0L) return
        usedBytes = (usedBytes - bytes).coerceAtLeast(0L)
    }

    fun reset() {
        usedBytes = 0L
    }

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 24L * 1024L * 1024L
    }
}
