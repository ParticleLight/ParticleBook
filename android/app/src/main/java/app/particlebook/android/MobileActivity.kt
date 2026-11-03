package app.particlebook.android

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.particlebook.android.ui.ParticleBookApp
import app.particlebook.android.zlib.ZlibClient

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
        applyDebugMirrorOverride()
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

    /**
     * DEBUG 专用：允许用 intent extra 固定镜像基址（离线 mock 服务或排查某一个镜像）。
     *
     *     adb shell am start -n app.particlebook/.android.MobileActivity \
     *         --es pb_zlib_mirror http://127.0.0.1:8899
     *
     * release 包忽略该 extra（非 debuggable）。mock 为 http 明文，因此 debug 变体的
     * AndroidManifest 允许 cleartext（src/debug/AndroidManifest.xml），release 不受影响。
     */
    private fun applyDebugMirrorOverride() {
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val base = intent?.getStringExtra(EXTRA_ZLIB_MIRROR)?.trim()?.trimEnd('/') ?: return
        if (base.isEmpty()) return
        ZlibClient.get(this).mirror = base
        Log.i("PBZlib", "debug_mirror_override base=$base")
    }

    private companion object {
        const val PERF_TAG = "PBPERF"
        const val EXTRA_ZLIB_MIRROR = "pb_zlib_mirror"
    }
}
