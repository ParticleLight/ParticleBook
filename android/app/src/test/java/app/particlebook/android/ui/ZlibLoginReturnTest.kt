package app.particlebook.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U5：登录成功回调的返程判定（纯函数）。
 *
 * 场景：登录请求在 AppRoot scope 里跑，用户按返回后回调仍会到达；旧实现直接 pop 会把
 * 下层页面弹掉（用户被踢回书架）。这里锁死「只在自己的会话 + login 仍是栈顶 + 下层仍是
 * 来源页」时才允许弹栈。
 */
class ZlibLoginReturnTest {

    @Test
    fun successPopsWhenLoginStillOnTopOfReturnPage() {
        assertTrue(
            canCompleteLoginReturn(
                openSession = 3L,
                currentSession = 3L,
                stack = listOf("detail", "login"),
                returnTo = "detail",
            ),
        )
        assertTrue(
            "来源是主 tab（空串）时同样合法",
            canCompleteLoginReturn(
                openSession = 1L,
                currentSession = 1L,
                stack = listOf("login"),
                returnTo = "",
            ),
        )
    }

    @Test
    fun lateSuccessDoesNotNavigateAfterUserReturned() {
        // 用户返回：login 已出栈，栈顶回到来源页。
        assertFalse(
            canCompleteLoginReturn(
                openSession = 3L,
                currentSession = 3L,
                stack = listOf("detail"),
                returnTo = "detail",
            ),
        )
        // 用户一路返回到主 tab（空栈）。
        assertFalse(
            canCompleteLoginReturn(
                openSession = 3L,
                currentSession = 3L,
                stack = emptyList(),
                returnTo = "",
            ),
        )
    }

    @Test
    fun staleCallbackFromReopenedLoginDoesNotPopNewLogin() {
        // 第一次登录未完成时用户返回、又从别处重新打开登录页：会话号已换代。
        assertFalse(
            canCompleteLoginReturn(
                openSession = 1L,
                currentSession = 2L,
                stack = listOf("detail", "login"),
                returnTo = "detail",
            ),
        )
    }

    @Test
    fun changedReturnPageBlocksNavigation() {
        // 防御性：会话相同且 login 仍在栈顶，但下层已不是入栈时记录的目标（导航被改写）。
        assertFalse(
            canCompleteLoginReturn(
                openSession = 1L,
                currentSession = 1L,
                stack = listOf("downloads", "login"),
                returnTo = "detail",
            ),
        )
    }
}
