package app.particlebook.android

import android.os.SystemClock
import android.util.Log

object NativeCore {
    private const val PERF_TAG = "PBPERF"

    @Volatile
    var loadMs: Long = -1
        private set

    fun loadOnce() {
        if (loadMs >= 0) return
        val t0 = SystemClock.elapsedRealtime()
        System.loadLibrary("pbcore")
        loadMs = SystemClock.elapsedRealtime() - t0
        // 性能体检：native 库加载耗时（release 也需要，不加 DEBUG 判断）。
        Log.i(PERF_TAG, "PBPERF|phase=native_load|ms=" + loadMs + "|extra=lib:pbcore")
    }

    external fun ping(): Long
    external fun version(): String
}
