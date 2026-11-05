package app.particlebook.android.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import app.particlebook.android.reader.epub.parser.M0Probe
import java.io.File

/**
 * DEBUG 变体专用 M0 设备探针入口（release 不包含）。
 *
 * 用法：
 *   adb shell am start -W -n app.particlebook/app.particlebook.android.debug.M0ProbeActivity \
 *     --es epub /data/user/0/app.particlebook/files/m0.epub --ei chapter 0
 *
 * 结果同时写 Log.i("PBM0", "RESULT {json}") 与 filesDir/m0-result.json。
 * 探针只读 EPUB，不改书架/数据库。
 */
class M0ProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_EPUB) ?: intent.data?.path
        if (path.isNullOrBlank()) {
            Log.e(TAG, "missing --es epub <path>")
            finish()
            return
        }
        val chapter = intent.getIntExtra(EXTRA_CHAPTER, 0)
        Thread {
            val file = File(path)
            val json = try {
                M0Probe.run(applicationContext, file, chapter).toJson()
            } catch (t: Throwable) {
                Log.e(TAG, "probe crashed", t)
                "{\"error\":\"" + escape(t.javaClass.simpleName + ": " + (t.message ?: "")) + "\"}"
            }
            Log.i("PBM0", "RESULT " + json)
            try {
                val internal = File(filesDir, "m0-result.json")
                internal.writeText(json)
                Log.i(TAG, "result=" + internal.absolutePath)
                getExternalFilesDir(null)?.let { dir ->
                    try {
                        File(dir, "m0-result.json").writeText(json)
                    } catch (t: Throwable) {
                        Log.w(TAG, "write external result failed", t)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "write result failed", t)
            }
            runOnUiThread { finish() }
        }.start()
    }

    private fun escape(value: String): String = buildString(value.length + 8) {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }

    companion object {
        private const val TAG = "PBM0Activity"
        private const val EXTRA_EPUB = "epub"
        private const val EXTRA_CHAPTER = "chapter"
    }
}
