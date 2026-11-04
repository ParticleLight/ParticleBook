package app.particlebook.android.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * task-112：下载历史可空字段的 JSON null / 空串 / 正常值 round-trip。
 *
 * 回归来源 docs/android-port evidence/history-json.txt（p15 task-110）：删书架条目后
 * errorKind/errorMessage 被写成字符串 "null"。根因是 Android org.json 的
 * JSONObject.optString(key) 对 JSONObject.NULL 返回 "null"，takeUnless { isBlank } 拦不住。
 *
 * 本地 JVM 单测没有可用 org.json（android.jar 是 not mocked），所以这里用一个与
 * encode/decode 同语义的模拟器验证：写盘 null → 读回 isNull=true + optString="null"；
 * 空串 → 读回 ""；正常值 → 原样保留。同时兼容旧版本已经写坏的字符串 "null"。
 */
class ZlibHistoryNullFieldTest {

    @Test
    fun nullFieldRoundTripsToNull() {
        // encode: record.errorKind ?: JSONObject.NULL；decode: item.isNull(...)=true。
        assertNull(roundTrip(null))
    }

    @Test
    fun emptyStringRoundTripsToNull() {
        assertNull(roundTrip(""))
        assertNull(roundTrip("   "))
    }

    @Test
    fun normalValuesArePreserved() {
        assertEquals("NETWORK", roundTrip("NETWORK"))
        assertEquals("网络连接异常，请检查网络后重试", roundTrip("网络连接异常，请检查网络后重试"))
    }

    @Test
    fun alreadyCorruptedLiteralNullIsTreatedAsAbsent() {
        // 旧版本把 JSONObject.NULL 读成字符串 "null" 后又重写盘；修复后必须自愈。
        assertNull(zlibHistoryOptionalText(isJsonNull = false, raw = "null"))
        assertNull(zlibHistoryOptionalText(isJsonNull = true, raw = "null"))
    }

    @Test
    fun historyDecoderChecksIsNullBeforeOptString() {
        val source = sourceFile("src/main/java/app/particlebook/android/ui/ZlibDownloadManager.kt")
        assertTrue(
            "errorKind 必须先 item.isNull",
            source.contains("isJsonNull = item.isNull(\"errorKind\")"),
        )
        assertTrue(
            "errorMessage 必须先 item.isNull",
            source.contains("isJsonNull = item.isNull(\"errorMessage\")"),
        )
        assertTrue(
            "空字段统一走 zlibHistoryOptionalText",
            source.contains("zlibHistoryOptionalText("),
        )
        assertTrue(
            "不能再用只判 blank 的旧写法",
            !source.contains("errorKind = item.optString(\"errorKind\").takeUnless"),
        )
    }

    private fun roundTrip(value: String?): String? {
        // encodeHistory 写入 JSONObject.NULL；Android optString 读回字符串 "null"。
        val isJsonNull = value == null
        val raw = value ?: "null"
        return zlibHistoryOptionalText(isJsonNull = isJsonNull, raw = raw)
    }

    private fun sourceFile(relative: String): String {
        val candidates = ArrayList<File>()
        candidates += File(relative)
        candidates += File("app/" + relative)
        var dir: File? = File(System.getProperty("user.dir") ?: "").absoluteFile
        repeat(6) {
            val current = dir
            if (current != null) {
                candidates += File(current, "android/app/" + relative)
                candidates += File(current, "app/" + relative)
                candidates += File(current, relative)
                dir = current.parentFile
            }
        }
        return requireNotNull(candidates.firstOrNull { it.isFile }) {
            "找不到 " + relative + "（工作目录=" + System.getProperty("user.dir") + "）"
        }.readText()
    }
}
