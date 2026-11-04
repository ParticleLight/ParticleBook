package app.particlebook.android.ui

import app.particlebook.android.zlib.ZlibException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * spec §11.6：错误类型副行必须是可读中文，不能暴露英文枚举；未知类型隐藏（返回 null）。
 * P15-V9/P2：NETWORK 首行文案统一为「网络连接异常，请检查网络后重试」。
 */
class ZlibUiErrorKindLabelTest {

    @Test
    fun networkKindLabelIsReadableChinese() {
        assertEquals("网络异常", ZlibUiError.kindLabel("NETWORK"))
    }

    @Test
    fun allKnownKindsHaveNonAsciiLabels() {
        val kinds = listOf(
            "NO_MIRROR",
            "CHALLENGE_FAILED",
            "HTTP",
            "PARSE",
            "NETWORK",
            "DAILY_LIMIT",
            "LOGIN_REQUIRED",
            "DOWNLOAD",
            "CANCELLED",
        )
        kinds.forEach { kind ->
            val label = ZlibUiError.kindLabel(kind)
            assertTrue("缺少中文标签: " + kind, label != null)
            assertTrue(
                "标签不能含英文枚举: " + kind + " -> " + label,
                label!!.none { it in 'A'..'Z' || it in 'a'..'z' },
            )
        }
    }

    @Test
    fun unknownOrNullKindIsHidden() {
        assertNull(ZlibUiError.kindLabel("UNKNOWN"))
        assertNull(ZlibUiError.kindLabel(null))
        assertNull(ZlibUiError.kindLabel("WHATEVER"))
        assertNull(ZlibUiError.kindLabel(""))
    }

    @Test
    fun networkMessageMatchesSpec() {
        val error = ZlibException("network reset", ZlibException.Kind.NETWORK)
        assertEquals("网络连接异常，请检查网络后重试", ZlibUiError.message(error))
        // 登录失败分支保持同一文案（不出现「网络连接失败」）。
        assertEquals("网络连接异常，请检查网络后重试", ZlibUiError.loginFailed("NETWORK", null))
    }
}
