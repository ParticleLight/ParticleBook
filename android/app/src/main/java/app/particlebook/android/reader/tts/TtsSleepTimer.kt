package app.particlebook.android.reader.tts

/**
 * 朗读定时停止的截止时间状态机（纯 Kotlin，不依赖 Android / Compose / 协程，也不 sleep）。
 *
 * 只回答一个问题：**这一刻离自动停止还有多久**。真正的停止动作由宿主做（先清自动续读，
 * 再停 [TtsPlayback]），本类不认识朗读状态，也不认识页与段。
 *
 * 边界约定：
 * - 只有 [start] / [cancel] / [pollExpired] 会改变计划；段落或页面自动推进、UI 打开弹层、
 *   暂停朗读都不碰它，所以倒计时只受注入的单调时钟影响（暂停时继续走，是产品要求）。
 * - 计划不进持久化：进程死亡后朗读与定时一起结束，不留残余。
 * - 单线程使用（UI 主线程），内部不加锁；[nowMillis] 由调用方注入**单调时钟**
 *   （同 `SystemClock.elapsedRealtime` 语义），负读数按 0 处理，便于测试拨时钟。
 */
internal class TtsSleepTimer(private val nowMillis: () -> Long) {

    private var plannedMinutes: Int? = null
    private var deadlineMillis: Long = 0L

    /** 当前计划的时长（分钟）；没有计划时为 null。0 分钟不是"不定时"，它根本不在预设里。 */
    val durationMinutes: Int?
        get() = plannedMinutes

    /**
     * 从**当前时刻**起算一个新的计划，覆盖旧计划（重新选择 15 分钟就是再给 15 分钟，不叠加）。
     *
     * 只接受 [PRESET_MINUTES]；非法值直接 [IllegalArgumentException]，并且**先校验后写状态**，
     * 所以抛异常时旧计划原样保留——不会出现"选错了反而把正在跑的定时清掉"。
     */
    fun start(minutes: Int) {
        require(minutes in PRESET_MINUTES) {
            "unsupported sleep timer minutes: " + minutes + ", expected one of " + PRESET_MINUTES
        }
        plannedMinutes = minutes
        deadlineMillis = saturatedAdd(clockMillis(), minutes * MILLIS_PER_MINUTE)
    }

    /** 取消计划；没有计划时是空操作（幂等）。取消不暂停朗读，停不停由宿主决定。 */
    fun cancel() {
        plannedMinutes = null
        deadlineMillis = 0L
    }

    /**
     * 剩余毫秒：没有计划返回 null；到期返回 0；时钟回拨导致"剩余比总时长还多"时夹到总时长。
     *
     * 到期只返回 0、**不**清计划：清理由 [pollExpired] 独占，这样 UI 能先看到 0 再决定停止，
     * 不会在这一帧里既读到 null 又来不及显示"已到时"。
     */
    fun remainingMillis(): Long? {
        val minutes = plannedMinutes ?: return null
        val now = clockMillis()
        if (now >= deadlineMillis) return 0L
        // now 已夹到非负、deadline 非负，差值不可能溢出；仍按总时长封顶防时钟回拨。
        return (deadlineMillis - now).coerceAtMost(minutes * MILLIS_PER_MINUTE)
    }

    /**
     * 到期消费：真到期时清掉计划并返回 true，且**只返回一次** true；其余情况（未到期、本来就没计划）返回 false。
     *
     * 宿主的周期 tick 调它即可，不必自己比对 [remainingMillis] == 0：后者在到期后会一直是 0，
     * 拿来当"该停了"的判据会每秒停一次。
     */
    fun pollExpired(): Boolean {
        if (plannedMinutes == null) return false
        if (clockMillis() < deadlineMillis) return false
        cancel()
        return true
    }

    companion object {
        /** 弹层里可选的预设分钟数；[start] 只认这几个值。 */
        val PRESET_MINUTES: List<Int> = listOf(5, 15, 30, 60)

        private const val MILLIS_PER_MINUTE: Long = 60_000L
        private const val MILLIS_PER_SECOND: Long = 1_000L

        /**
         * 倒计时文案 m:ss（如 4:59）。
         *
         * 秒向上取整：剩 299999ms 显示 5:00 而不是 4:59，避免"显示 0:00 却还在念"；
         * 负数当 0；用除法与取余而不是 (millis + 999) / 1000，Long.MAX_VALUE 也不会溢出。
         */
        fun formatRemaining(millis: Long): String {
            val clamped = if (millis < 0L) 0L else millis
            val seconds = clamped / MILLIS_PER_SECOND +
                if (clamped % MILLIS_PER_SECOND == 0L) 0L else 1L
            val minutesPart = seconds / 60L
            val secondsPart = seconds % 60L
            return minutesPart.toString() + ":" +
                (if (secondsPart < 10L) "0" else "") + secondsPart
        }

        /** deadline 加法饱和到 [Long.MAX_VALUE]；时钟接近上限时不能让 deadline 回绕成过去。 */
        private fun saturatedAdd(now: Long, durationMillis: Long): Long =
            if (now > Long.MAX_VALUE - durationMillis) Long.MAX_VALUE else now + durationMillis
    }

    /**
     * 注入时钟的读数，负值夹到 0。
     *
     * 负读数只可能来自测试假时钟或未初始化的注入；若不夹，-1000ms 起算会让 deadline 比
     * 真实时刻早 1 秒（5 分钟计划只剩 299000ms），而"负时钟夹 0"是契约的一部分。
     */
    private fun clockMillis(): Long = nowMillis().coerceAtLeast(0L)
}
