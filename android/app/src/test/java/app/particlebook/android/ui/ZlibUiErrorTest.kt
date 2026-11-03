package app.particlebook.android.ui

import app.particlebook.android.zlib.ZlibErrorMessages
import app.particlebook.android.zlib.ZlibException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * D12：错误文案细分。
 * - HTTP 有真实状态码 → 「服务器返回错误 N」；无码保持原文案；
 * - 重定向超限 / 跳转地址异常按网络层英文 message 细分；
 * - 挑战失败按 message 区分页面结构变化与多轮重试；
 * - 未知异常仍回退到 message。
 */
class ZlibUiErrorTest {

    @Test
    fun httpWithStatusCodeUsesServerCodeText() {
        val error = ZlibException("HTTP 404 for /book/x", ZlibException.Kind.HTTP, httpCode = 404)
        assertEquals("服务器返回错误 404", ZlibUiError.message(error))
    }

    @Test
    fun httpWithoutStatusCodeKeepsGenericText() {
        val error = ZlibException("HTTP failed", ZlibException.Kind.HTTP)
        assertEquals("服务器返回错误，请稍后重试", ZlibUiError.message(error))
    }

    @Test
    fun tooManyRedirectsHasSpecificText() {
        val error = ZlibException("too many redirects", ZlibException.Kind.HTTP)
        assertEquals("重定向次数过多，请切换线路后重试", ZlibUiError.message(error))
    }

    @Test
    fun badRedirectHasSpecificText() {
        val error = ZlibException("bad redirect: javascript:void(0)", ZlibException.Kind.HTTP)
        assertEquals("跳转地址异常，请切换线路后重试", ZlibUiError.message(error))
    }

    @Test
    fun challengeMessagesAreRefined() {
        val changed = ZlibException(
            "challenge page without solvable constant (status 403)",
            ZlibException.Kind.CHALLENGE_FAILED,
        )
        assertEquals("安全校验页面结构变化，请切换线路后重试", ZlibUiError.message(changed))

        val exhausted = ZlibException(
            "challenge failed after 3 rounds (status 403)",
            ZlibException.Kind.CHALLENGE_FAILED,
        )
        assertEquals("安全校验多次重试仍未通过，请稍后重试", ZlibUiError.message(exhausted))
    }

    @Test
    fun unknownThrowableFallsBackToMessage() {
        assertEquals("custom failure", ZlibUiError.message(IllegalStateException("custom failure")))
        assertEquals("操作失败，请稍后重试。", ZlibUiError.message(null))
    }

    @Test
    fun zlibLayerMessagesFollowSameRules() {
        val withCode = ZlibException("HTTP 503 for /dl", ZlibException.Kind.HTTP, httpCode = 503)
        assertEquals("服务器返回错误 503", ZlibErrorMessages.from(withCode))
        assertEquals("服务器返回错误 503", ZlibErrorMessages.of(ZlibException.Kind.HTTP, 503))

        val redirect = ZlibException("too many redirects", ZlibException.Kind.HTTP)
        assertEquals(ZlibErrorMessages.TOO_MANY_REDIRECTS, ZlibErrorMessages.from(redirect))

        assertEquals(

            ZlibErrorMessages.CHALLENGE_PAGE_CHANGED,
            ZlibErrorMessages.from(
                ZlibException("challenge page without solvable constant", ZlibException.Kind.CHALLENGE_FAILED),
            ),
        )
    }

    @Test
    fun legacyThreeArgumentConstructorStillWorks() {
        val cause = java.io.IOException("socket reset")
        val error = ZlibException("network", ZlibException.Kind.NETWORK, cause)
        assertEquals(null, error.httpCode)
        assertEquals("网络连接异常，请检查网络后重试", ZlibUiError.message(error))
    }
}