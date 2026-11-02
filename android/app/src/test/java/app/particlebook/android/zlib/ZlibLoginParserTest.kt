package app.particlebook.android.zlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZlibLoginParserTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("zlib/$name")!!.use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    @Test
    fun wrongPasswordPageExposesFormError() {
        val html = fixture("login-error.html")

        val error = ZlibLoginParser.formError(html)
        assertTrue("expected 'Incorrect email or password', got: " + error, error!!.contains("Incorrect email or password"))

        // 错误密码页脚本是 const CurrentUser = new User(null)。
        assertEquals(ZlibLoginParser.AccountState.Anonymous, ZlibLoginParser.accountState(html))
    }

    @Test
    fun loggedInUserIsParsedFromPageScript() {
        val html = """<script>const CurrentUser = new User({"id": 123, "email": "reader@example.com", "name": "Reader"});</script>"""

        val state = ZlibLoginParser.accountState(html)
        assertTrue(state is ZlibLoginParser.AccountState.LoggedIn)
        val account = (state as ZlibLoginParser.AccountState.LoggedIn).account
        assertEquals("reader@example.com", account.email)
        assertEquals("Reader", account.name)
    }

    @Test
    fun missingCurrentUserIsUnknown() {
        assertEquals(ZlibLoginParser.AccountState.Unknown, ZlibLoginParser.accountState("<html>plain page</html>"))
        assertNull(ZlibLoginParser.formError("<html>plain page</html>"))
    }

    @Test
    fun downloadsApiBodyDistinguishesLoginState() {
        assertTrue(ZlibLoginParser.looksLoggedInByDownloads("""{"success":1,"downloads":[{"id":1}]}"""))
        assertFalse(ZlibLoginParser.looksLoggedInByDownloads("""{"success":0,"message":"not logged in"}"""))
    }
}
