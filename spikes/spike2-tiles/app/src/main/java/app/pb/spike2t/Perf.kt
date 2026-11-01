package app.pb.spike2t

import android.util.Log
import java.util.Locale

/** Logcat contract from docs/android-port/spike2-protocol.md §3 — do not change the format. */
object Perf {
    const val TAG = "PBPERF"

    fun event(sc: String, phase: String, page: Int = -1, w: Int = -1, h: Int = -1, ms: Double = 0.0, extra: String = "-") {
        Log.i(TAG, String.format(Locale.US, "PBPERF|scenario=%s|phase=%s|page=%d|w=%d|h=%d|ms=%.3f|extra=%s", sc, phase, page, w, h, ms, extra))
    }

    fun mem(sc: String, phase: String, pssKb: Long, nativeKb: Long = -1, javaKb: Long = -1) {
        Log.i(TAG, String.format(Locale.US, "PBPERF|scenario=%s|phase=pss|page=-1|w=-1|h=-1|ms=0|extra=pss_kb:%d,native_kb:%d,java_kb:%d", sc, pssKb, nativeKb, javaKb))
    }

    fun done(sc: String, runs: Int, extra: String = "ok") {
        Log.i(TAG, "PBDONE|scenario=" + sc + "|runs=" + runs + "|extra=" + extra)
    }

    fun time(app: String, phase: String, ms: Double) {
        Log.i(TAG, String.format(Locale.US, "PBTIME|app=%s|phase=%s|ms=%.3f", app, phase, ms))
    }
}

object Stats {
    fun pct(v: List<Double>, p: Double): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        var idx = Math.ceil(p / 100.0 * s.size).toInt() - 1
        if (idx < 0) idx = 0
        if (idx > s.size - 1) idx = s.size - 1
        return s[idx]
    }

    fun mean(v: List<Double>): Double = if (v.isEmpty()) 0.0 else v.sum() / v.size

    fun max(v: List<Double>): Double = if (v.isEmpty()) 0.0 else v.max()

    /** avg/p50/p95/max/over16_ratio + jank_ratio (relative to one vsync period) + fps_avg. */
    fun line(v: List<Double>, refreshHz: Double = 60.0): String {
        if (v.isEmpty()) return "n:0"
        val avg = mean(v)
        val over16 = v.count { it > 16.0 }.toDouble() / v.size
        val vsync = 1000.0 / refreshHz
        val jank = v.count { it > vsync * 1.5 }.toDouble() / v.size
        val fps = if (avg > 0) 1000.0 / avg else 0.0
        return String.format(
            Locale.US,
            "n:%d,avg_ms:%.2f,p50_ms:%.2f,p95_ms:%.2f,max_ms:%.2f,over16_ratio:%.3f,jank_ratio:%.3f,fps_avg:%.1f",
            v.size, avg, pct(v, 50.0), pct(v, 95.0), max(v), over16, jank, fps
        )
    }
}
