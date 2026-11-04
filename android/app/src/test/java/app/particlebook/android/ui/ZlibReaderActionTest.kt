package app.particlebook.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A13：详情页「在线阅读」按钮的可用性/文案（纯函数，不依赖 Compose/设备）。 */
class ZlibReaderActionTest {

    private val ready = ZlibReaderLaunchState.Ready(
        readerUrl = "https://reader.z-lib.by/read/a/book/hash/x.html?file_access_token=t",
    )

    @Test
    fun actionIsDisabledWhenLoggedOutOrResolving() {
        assertFalse(zlibReaderActionEnabled(loggedIn = false, state = ZlibReaderLaunchState.Idle))
        assertTrue(zlibReaderActionEnabled(loggedIn = true, state = ZlibReaderLaunchState.Idle))
        assertFalse(zlibReaderActionEnabled(loggedIn = true, state = ZlibReaderLaunchState.Loading))
        assertTrue(zlibReaderActionEnabled(loggedIn = true, state = ready))
        assertTrue(
            zlibReaderActionEnabled(
                loggedIn = true,
                state = ZlibReaderLaunchState.Error("没有在线阅读链接"),
            ),
        )
    }

    @Test
    fun hintPrioritizesLaunchFailureThenResolveErrorThenLogin() {
        val missing = ZlibReaderLaunchState.Error(ZLIB_READER_MISSING_LINK_MESSAGE)

        assertEquals(
            ZLIB_READER_NO_BROWSER_MESSAGE,
            zlibReaderActionHint(true, ready, ZLIB_READER_NO_BROWSER_MESSAGE),
        )
        assertEquals(ZLIB_READER_MISSING_LINK_MESSAGE, zlibReaderActionHint(true, missing))
        assertEquals(
            ZLIB_READER_LOGIN_REQUIRED_MESSAGE,
            zlibReaderActionHint(false, ZlibReaderLaunchState.Idle),
        )
        assertEquals(
            ZLIB_READER_BROWSER_HINT,
            zlibReaderActionHint(true, ZlibReaderLaunchState.Idle),
        )
        // realnet task-33：点击前的副文案和未登录提示都必须带「当前受限」主提示。
        assertTrue(
            zlibReaderActionHint(true, ZlibReaderLaunchState.Idle)
                .contains(ZLIB_READER_LIMITED_MESSAGE),
        )
        assertTrue(
            zlibReaderActionHint(false, ZlibReaderLaunchState.Idle)
                .contains(ZLIB_READER_LIMITED_MESSAGE),
        )
    }

    @Test
    fun openedHintReportsSuccessInsteadOfLimitedWarning() {
        assertEquals(
            ZLIB_READER_OPENED_MESSAGE,
            zlibReaderActionHint(
                loggedIn = true,
                state = ZlibReaderLaunchState.Idle,
                opened = true,
            ),
        )
        // 打开失败仍优先显示失败原因，不能因为「刚成功过」就压掉无浏览器提示。
        assertEquals(
            ZLIB_READER_NO_BROWSER_MESSAGE,
            zlibReaderActionHint(
                loggedIn = true,
                state = ZlibReaderLaunchState.Idle,
                launchError = ZLIB_READER_NO_BROWSER_MESSAGE,
                opened = true,
            ),
        )
        assertFalse(ZLIB_READER_OPENED_MESSAGE.contains(ZLIB_READER_LIMITED_MESSAGE))
    }

    @Test
    fun missingReaderLinkHasOwnMessageButKeepsLimitedCaveat() {
        assertEquals(
            ZLIB_READER_MISSING_LINK_MESSAGE,
            zlibReaderUrlFailureMessage(ZlibReaderUrl.Failure.NOT_FOUND),
        )
        assertNotEquals(ZLIB_READER_LIMITED_MESSAGE, ZLIB_READER_MISSING_LINK_MESSAGE)
        assertTrue(ZLIB_READER_MISSING_LINK_MESSAGE.contains(ZLIB_READER_LIMITED_MESSAGE))
    }

    // task-43 P2-1：只有本层仍可见且拿到 Ready 才拉起浏览器（详情层被压住时不得抢焦点）。
    @Test
    fun opensBrowserOnlyWhenLayerVisibleAndStateReady() {
        assertTrue(zlibReaderShouldOpenBrowser(layerVisible = true, state = ready))
        assertFalse(zlibReaderShouldOpenBrowser(layerVisible = false, state = ready))
        assertFalse(zlibReaderShouldOpenBrowser(layerVisible = true, state = ZlibReaderLaunchState.Idle))
        assertFalse(
            zlibReaderShouldOpenBrowser(
                layerVisible = true,
                state = ZlibReaderLaunchState.Error("x"),
            ),
        )
    }

    // task-58 P2-1：打开失败（无浏览器/系统拒绝）也必须消费 Ready，下次点击重新解析；
    // 否则旧 URL 会一直留在 state 里被重复复用，而它可能属于已失效的登录会话。
    @Test
    fun openAttemptAlwaysConsumesReadyEvenWhenBrowserMissing() {
        val success = zlibReaderOpenOutcome(browserLaunched = true)
        assertTrue(success.opened)
        assertNull(success.launchError)
        assertTrue(success.consumeReady)

        val failure = zlibReaderOpenOutcome(browserLaunched = false)
        assertFalse(failure.opened)
        assertEquals(ZLIB_READER_NO_BROWSER_MESSAGE, failure.launchError)
        assertTrue(failure.consumeReady)
    }

    @Test
    fun labelReflectsIdleResolvingAndRetryStates() {
        assertEquals("在线阅读", zlibReaderActionLabel(ZlibReaderLaunchState.Idle))
        assertEquals("在线阅读", zlibReaderActionLabel(ready))
        assertEquals("正在获取链接…", zlibReaderActionLabel(ZlibReaderLaunchState.Loading))
        assertEquals(
            "重新尝试在线阅读",
            zlibReaderActionLabel(ZlibReaderLaunchState.Error("x")),
        )
    }
}
