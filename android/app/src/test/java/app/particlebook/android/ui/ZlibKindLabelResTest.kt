package app.particlebook.android.ui

import app.particlebook.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P1 回归：搜索结果页错误副行的输入是 ZlibException.Kind.name（如 NO_MIRROR），
 * 不是 ZlibUiError.kindLabel 的中文展示标签；9 类已知 kind 必须映射到资源，未知值隐藏。
 */
class ZlibKindLabelResTest {

    @Test
    fun mapsAllKnownSearchErrorKindsToResources() {
        val expected = mapOf(
            "NO_MIRROR" to R.string.find_error_kind_mirror,
            "CHALLENGE_FAILED" to R.string.find_error_kind_security,
            "HTTP" to R.string.find_error_kind_server,
            "PARSE" to R.string.find_error_kind_parse,
            "NETWORK" to R.string.find_error_kind_network,
            "DAILY_LIMIT" to R.string.find_error_kind_quota,
            "LOGIN_REQUIRED" to R.string.find_error_kind_login,
            "DOWNLOAD" to R.string.find_error_kind_download,
            "CANCELLED" to R.string.find_error_kind_cancelled,
        )

        expected.forEach { (kind, expectedRes) ->
            val actual = zlibKindLabelRes(kind)
            assertEquals("kind=$kind", expectedRes, actual)
            assertNotEquals("kind=$kind 不应映射到 0", 0, actual)
        }
    }

    @Test
    fun hidesUnknownAndEmptyKindNames() {
        assertNull(zlibKindLabelRes(null))
        assertNull(zlibKindLabelRes(""))
        assertNull(zlibKindLabelRes("UNKNOWN"))
        assertNull(zlibKindLabelRes("WHATEVER"))
        assertNull(zlibKindLabelRes("镜像不可达"))
    }
}
