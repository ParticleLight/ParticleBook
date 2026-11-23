package app.particlebook.android.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 统计页的一根柱子：某个自然日的阅读时长与翻页数。 */
data class ReadingDay(
    /** 自 1970-01-01 起的自然日序号（[LocalDate.toEpochDay]）；与时区无关，UI 用它反查星期。 */
    val epochDay: Long,
    val millis: Long,
    val pages: Int,
)

/** 阅读统计快照；完全由 [ReadingStatsCalculator] 从会话行聚合，不落库。 */
data class ReadingStats(
    /** 全部历史会话的时长之和。 */
    val totalMillis: Long,
    /** 今天（按调用方 zone 的自然日）的时长。 */
    val todayMillis: Long,
    /** 最近 dayCount 天，旧 → 新；没有阅读的日期补 0。 */
    val days: List<ReadingDay>,
    /** 从今天往前连续有阅读的天数；今天还没读但昨天读过时从昨天起算。 */
    val streakDays: Int,
    /** 出现过会话的不同书籍数。 */
    val booksRead: Int,
    /** 会话里正向页码变化之和（回翻不计负数）。 */
    val pagesTurned: Int,
)

/**
 * 纯聚合：不碰 Android、不碰数据库，输入 [ReadingSessionRow] 列表即可，JVM 单测直接调用。
 *
 * 口径（统计页 UI 依赖这些约定）：
 * - 自然日按调用方传入的 [ZoneId] 切分，不用系统默认时区，避免设备改时区把历史数据切乱；
 * - 跨午夜的会话按墙钟重叠比例分到两天，余数归到最后一天，保证每日之和恒等于该行的 durationMs；
 * - 页码变化记在会话开始那天——会话行没有逐页时间点，无法更细地还原；
 * - 负的 durationMs / pagesDelta 视为脏数据按 0 计，统计不出现负值；
 * - [ReadingStats.totalMillis] / [ReadingStats.booksRead] / [ReadingStats.pagesTurned]
 *   覆盖全部历史，不受 dayCount 窗口限制。
 */
object ReadingStatsCalculator {

    fun compute(
        sessions: List<ReadingSessionRow>,
        now: Long,
        zone: ZoneId,
        dayCount: Int = 7,
    ): ReadingStats {
        val today = epochDay(now, zone)
        val millisByDay = HashMap<Long, Long>()
        val pagesByDay = HashMap<Long, Int>()
        val books = HashSet<Long>()
        var totalMillis = 0L
        var pagesTurned = 0

        sessions.forEach { session ->
            books += session.bookId
            val duration = session.durationMs.coerceAtLeast(0L)
            totalMillis += duration
            val pages = session.pagesDelta.coerceAtLeast(0)
            pagesTurned += pages
            if (duration > 0L) splitAcrossDays(session, duration, zone, millisByDay)
            if (pages > 0) {
                val day = epochDay(session.startedAt, zone)
                pagesByDay[day] = (pagesByDay[day] ?: 0) + pages
            }
        }

        // dayCount 非正数时不给桶（仍然算汇总）；UI 正常只传 7。
        val days = if (dayCount <= 0) {
            emptyList()
        } else {
            (today - dayCount + 1..today).map { day ->
                ReadingDay(epochDay = day, millis = millisByDay[day] ?: 0L, pages = pagesByDay[day] ?: 0)
            }
        }

        return ReadingStats(
            totalMillis = totalMillis,
            todayMillis = millisByDay[today] ?: 0L,
            days = days,
            streakDays = streakDays(today, millisByDay),
            booksRead = books.size,
            pagesTurned = pagesTurned,
        )
    }

    /**
     * 今天有阅读就从今天数；今天还没有阅读允许从昨天起算一次，
     * 再往前遇到空白天立刻停——不把「歇了一天」当连续。
     */
    private fun streakDays(today: Long, millisByDay: Map<Long, Long>): Int {
        var day = today
        if ((millisByDay[day] ?: 0L) <= 0L) day -= 1
        var count = 0
        while ((millisByDay[day] ?: 0L) > 0L) {
            count += 1
            day -= 1
        }
        return count
    }

    /**
     * 把一段会话按时区自然日切开。
     *
     * 区间按半开 [start, end) 处理：正好结束在午夜的会话全部算前一天。
     * 每日份额按墙钟重叠比例向下取整，残差统一给最后一天，因此 sum(days) == duration 恒成立。
     */
    private fun splitAcrossDays(
        session: ReadingSessionRow,
        duration: Long,
        zone: ZoneId,
        out: MutableMap<Long, Long>,
    ) {
        val start = minOf(session.startedAt, session.endedAt)
        val end = maxOf(session.startedAt, session.endedAt)
        val span = end - start
        if (span <= 0L) {
            add(out, epochDay(start, zone), duration)
            return
        }
        val lastDay = epochDay(end - 1, zone)
        var day = epochDay(start, zone)
        var remaining = duration
        while (day <= lastDay) {
            val dayStart = dayStart(day, zone)
            val dayEnd = dayStart(day + 1, zone)
            val overlap = (minOf(dayEnd, end) - maxOf(dayStart, start)).coerceAtLeast(0L)
            val share = if (day == lastDay) {
                remaining
            } else {
                proportional(duration, overlap, span).coerceAtMost(remaining)
            }
            if (share > 0L) add(out, day, share)
            remaining -= share
            day += 1
        }
        // 防御：理论上前面的取整不会多分，若有残差补到最后一天，保证总时长不丢。
        if (remaining > 0L) add(out, lastDay, remaining)
    }

    /** 先除后乘，避免 duration * overlap 在超长会话上溢出 Long。 */
    private fun proportional(duration: Long, overlap: Long, span: Long): Long =
        (duration / span) * overlap + (duration % span) * overlap / span

    private fun add(out: MutableMap<Long, Long>, day: Long, millis: Long) {
        out[day] = (out[day] ?: 0L) + millis
    }

    private fun epochDay(millis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

    /** 该自然日在该时区的第一毫秒；夏令时切换日由 [ZoneId] 自己纠正。 */
    private fun dayStart(day: Long, zone: ZoneId): Long =
        LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()
}
