package app.particlebook.android.zlib

/**
 * 下载「零增长看门狗」（P1c 收尾②，对齐桌面 `ZLibraryDownload.cpp` 的 45s 规则）。
 *
 * 桌面版每 2s 看一眼已接收字节数，连续 45s 零增长就把下载判为 `network_stalled`；
 * 安卓原来只有 `readTimeout=15s`——它只覆盖**单次 read 停顿**，一旦服务端持续滴数据
 * （哪怕极慢）就永远不会超时，下载可能挂到天荒地老。
 *
 * 本类只做纯时间判断（可注入时钟，JVM 单测直接跑）；真正的 enforcement 在
 * [ZlibClient.streamOnce] 的守护线程里：超时 → disconnect 连接 → 抛 NETWORK 失败，
 * `.part` 保留供下次续传。
 */
internal class ZlibStallDetector(
    private val stallMs: Long = STALL_TIMEOUT_MS,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {

    private var lastBytes: Long = Long.MIN_VALUE
    private var lastChangeAt: Long = clockMs()

    /** 汇报当前累计字节数；数值有变化就重置空闲计时。 */
    fun onBytes(totalBytes: Long) {
        if (totalBytes != lastBytes) {
            lastBytes = totalBytes
            lastChangeAt = clockMs()
        }
    }

    /** 距上次字节增长的空闲时长（毫秒）。 */
    fun idleMs(): Long = clockMs() - lastChangeAt

    /** 是否已连续 [stallMs] 无字节增长。 */
    fun stalled(): Boolean = idleMs() >= stallMs

    companion object {
        /** 与桌面版一致：45s 零增长判失败。 */
        const val STALL_TIMEOUT_MS: Long = 45_000L

        /** 守护线程检查间隔（桌面版 2s）。 */
        const val TICK_MS: Long = 2_000L
    }
}
