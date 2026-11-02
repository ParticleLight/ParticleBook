package app.particlebook.android

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.particlebook.android.ui.ParticleBookApp

/**
 * Compose + Material 3 新门面。书架 / Z-Library / 设置三屏与 PDF 阅读器都在这里起。
 * 旧 WebView 阅读器（EPUB/HTML）仍由 MainActivity 承担，可从中跳转。
 */
class MobileActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 性能体检：冷启动 t0 取在 onCreate 最早期（进程已起、但 Activity 刚开始创建）。
        val coldStartBegin = SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ParticleBookApp() }
        // 首帧绘制回调只跑一次；release 也需要，所以不加 BuildConfig.DEBUG 判断。
        Choreographer.getInstance().postFrameCallback {
            Log.i(
                PERF_TAG,
                "PBPERF|phase=cold_start|ms=" + (SystemClock.elapsedRealtime() - coldStartBegin) +
                    "|extra=first_frame:1",
            )
        }
    }

    private companion object {
        const val PERF_TAG = "PBPERF"
    }
}
