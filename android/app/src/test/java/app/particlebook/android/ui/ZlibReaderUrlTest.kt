package app.particlebook.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A13：reader.z-lib.by 链接提取/校验；task-58 补 200 登录页/挑战页识别。
 *
 * 真实夹具 book-detail.html 的 reader-link href **没有引号**，且 query 里的 & 未转义；
 * 用带引号的正则会漏掉，这里用真实夹具锁住该形态。
 */
class ZlibReaderUrlTest {

    @Test
    fun extractsUnquotedHrefFromRealDetailFixture() {
        val parsed = ZlibReaderUrl.extract(fixture("book-detail.html"))

        assertTrue(parsed is ZlibReaderUrl.ParseResult.Ok)
        val url = (parsed as ZlibReaderUrl.ParseResult.Ok).url
        assertTrue(url, url.startsWith("https://reader.z-lib.by/read/026fde1c4967f41586cccc86df8e0433806d2dd55ed93fb56a8fd9dca508ab28/"))
        assertTrue(url, url.contains("file_access_token="))
        assertFalse(url, url.contains("&amp;"))
    }

    @Test
    fun extractsQuotedEscapedHrefAndDecodesEntities() {
        val html = "<a class='btn dlButton reader-link' " +
            "href='https://reader.z-lib.by/read/hash/book/h/s.html?client_key=k&amp;extension=epub" +
            "&amp;signature=sig&amp;file_access_token=tok'>Read</a>"

        val parsed = ZlibReaderUrl.extract(html)

        assertTrue(parsed is ZlibReaderUrl.ParseResult.Ok)
        val url = (parsed as ZlibReaderUrl.ParseResult.Ok).url
        assertTrue(url, url.contains("&extension=epub"))
        assertTrue(url, url.contains("&file_access_token=tok"))
        assertFalse(url, url.contains("&amp;"))
    }

    @Test
    fun prefersReaderLinkAnchorOverEarlierShareLink() {
        val html = "<a href=\"https://reader.z-lib.by/read/share/book/h/s.html?file_access_token=share\">quote</a>" +
            "<a class=\"reader-link\" href=\"https://reader.z-lib.by/read/real/book/h/s.html?file_access_token=real\">Read</a>"

        val parsed = ZlibReaderUrl.extract(html)

        assertTrue(parsed is ZlibReaderUrl.ParseResult.Ok)
        val url = (parsed as ZlibReaderUrl.ParseResult.Ok).url
        assertTrue(url, url.contains("file_access_token=real"))
    }

    @Test
    fun fallsBackToReaderHostWhenAnchorMissing() {
        val html = "<div>Found something worth sharing? " +
            "<a href=\"https://reader.z-lib.by/read/abc/book/h/s.html?signature=s&file_access_token=fallback\">" +
            "Create a quote</a></div>"

        val parsed = ZlibReaderUrl.extract(html)

        assertTrue(parsed is ZlibReaderUrl.ParseResult.Ok)
        val url = (parsed as ZlibReaderUrl.ParseResult.Ok).url
        assertTrue(url, url.contains("file_access_token=fallback"))
    }

    @Test
    fun templateReaderLinkIsIgnored() {
        val html = "<template><a class=\"reader-link\" " +
            "href=\"https://reader.z-lib.by/read/t/book/h/s.html?file_access_token=tpl\">x</a></template>" +
            "<a class=\"reader-link\" href=\"https://reader.z-lib.by/read/real/book/h/s.html?file_access_token=real\">x</a>"

        val parsed = ZlibReaderUrl.extract(html)

        assertTrue(parsed is ZlibReaderUrl.ParseResult.Ok)
        assertTrue((parsed as ZlibReaderUrl.ParseResult.Ok).url.contains("file_access_token=real"))
    }

    @Test
    fun missingReaderLinkReturnsNotFound() {
        val parsed = ZlibReaderUrl.extract("<html><body>nothing here</body></html>")
        assertEquals(ZlibReaderUrl.Failure.NOT_FOUND, (parsed as ZlibReaderUrl.ParseResult.Failed).reason)
    }

    @Test
    fun rejectsUntrustedHosts() {
        val evil = "https://evil.example/read/a/book/h/s.html?file_access_token=t"
        val suffix = "https://reader.z-lib.by.evil.example/read/a/book/h/s.html?file_access_token=t"
        val bare = "https://z-lib.by/read/a/book/h/s.html?file_access_token=t"

        assertEquals(ZlibReaderUrl.Failure.UNTRUSTED_HOST, failure(evil))
        assertEquals(ZlibReaderUrl.Failure.UNTRUSTED_HOST, failure(suffix))
        assertEquals(ZlibReaderUrl.Failure.UNTRUSTED_HOST, failure(bare))
    }

    @Test
    fun rejectsNonHttpsAndUserInfo() {
        assertEquals(
            ZlibReaderUrl.Failure.INSECURE_SCHEME,
            failure("http://reader.z-lib.by/read/a/book/h/s.html?file_access_token=t"),
        )
        assertEquals(
            ZlibReaderUrl.Failure.INSECURE_SCHEME,
            failure("javascript:alert(1)"),
        )
        assertEquals(
            ZlibReaderUrl.Failure.MALFORMED,
            failure("https://user@reader.z-lib.by/read/a/book/h/s.html?file_access_token=t"),
        )
    }

    @Test
    fun rejectsNonReadPathAndMissingTicket() {
        assertEquals(
            ZlibReaderUrl.Failure.NOT_READ_PATH,
            failure("https://reader.z-lib.by/api/auth?file_access_token=t"),
        )
        assertEquals(
            ZlibReaderUrl.Failure.MISSING_TICKET,
            failure("https://reader.z-lib.by/read/a/book/h/s.html"),
        )
        assertEquals(
            ZlibReaderUrl.Failure.MISSING_TICKET,
            failure("https://reader.z-lib.by/read/a/book/h/s.html?signature=sig&extension=epub"),
        )
    }

    @Test
    fun acceptsAccessTokenAliasAndTrailingDotHost() {
        assertTrue(
            ZlibReaderUrl.validate("https://reader.z-lib.by./read/a/book/h/s.html?access_token=t") is
                ZlibReaderUrl.ParseResult.Ok,
        )
        assertTrue(
            ZlibReaderUrl.validate("https://reader.z-lib.by/read/a/book/h/s.html?access_token=t") is
                ZlibReaderUrl.ParseResult.Ok,
        )
    }

    // task-58：200 中间页识别（cookie 失效会跟随重定向落登录页；挑战/网关拦截落验证页）。

    @Test
    fun realLoginPageIsClassifiedAsLogin() {
        assertEquals(
            ZlibReaderUrl.InterstitialPage.LOGIN,
            ZlibReaderUrl.classifyInterstitial(fixture("login-error.html")),
        )
    }

    @Test
    fun realChallengePagesAreClassifiedAsChallenge() {
        assertEquals(
            ZlibReaderUrl.InterstitialPage.CHALLENGE,
            ZlibReaderUrl.classifyInterstitial(fixture("challenge.html")),
        )
        assertEquals(
            ZlibReaderUrl.InterstitialPage.CHALLENGE,
            ZlibReaderUrl.classifyInterstitial(fixture("challenge-rotated.html")),
        )
    }

    @Test
    fun realDetailAndNotFoundPagesAreNotInterstitials() {
        // 详情页导航里也有「/login?redirectUrl=」，不能把它当登录页判据（否则正常详情必误判）。
        assertEquals(
            ZlibReaderUrl.InterstitialPage.NONE,
            ZlibReaderUrl.classifyInterstitial(fixture("book-detail.html")),
        )
        assertEquals(
            ZlibReaderUrl.InterstitialPage.NONE,
            ZlibReaderUrl.classifyInterstitial(fixture("page-not-found.html")),
        )
        assertEquals(ZlibReaderUrl.InterstitialPage.NONE, ZlibReaderUrl.classifyInterstitial(""))
        assertEquals(
            ZlibReaderUrl.InterstitialPage.NONE,
            ZlibReaderUrl.classifyInterstitial("<html><body>no reader link</body></html>"),
        )
    }

    private fun failure(url: String): ZlibReaderUrl.Failure =
        (ZlibReaderUrl.validate(url) as ZlibReaderUrl.ParseResult.Failed).reason

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("zlib/" + name)) {
            "fixture not found: " + name
        }.bufferedReader().use { it.readText() }
}
