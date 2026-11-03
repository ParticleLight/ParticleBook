package app.particlebook.android.zlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZlibDownloadParserTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("zlib/$name")!!.use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    private val dlUrl = "https://z-lib.by/dl/AX1jDr5bOm"

    @Test
    fun realDailyLimitPageIsClassified() {
        val state = ZlibDownloadParser.classify(fixture("dl-daily-limit.html"), dlUrl)

        assertTrue("expected DailyLimit, got: " + state, state is ZlibDownloadParser.PageState.DailyLimit)
        val message = (state as ZlibDownloadParser.PageState.DailyLimit).message
        assertTrue("message should quote the page: " + message, message.contains("Daily limit reached", ignoreCase = true))
    }

    @Test
    fun candidateLinksAreExtractedAndUnsafeAnchorsSkipped() {
        val html = """
            <html><body>
            <a class="btn btn-primary dlButton" href="/dl/abc123">Download</a>
            <a class="btn btn-default" href="https://mirror.example.org/dl/xyz789">slow mirror</a>
            <a class="btn btn-default reader-link" href="https://reader.z-lib.by/read/a/book/b/x.html">Read</a>
            <a class="btn btn-link" href="/login?redirectUrl=/dl/abc123">Log in</a>
            <a href="/some/page">plain</a>
            </body></html>
        """.trimIndent()

        val links = ZlibDownloadParser.extractCandidates(html, dlUrl)

        assertTrue("expected /dl/abc123 in " + links, links.contains("/dl/abc123"))
        assertTrue("expected mirror link in " + links, links.contains("https://mirror.example.org/dl/xyz789"))
        assertTrue("reader/login/plain anchors must be skipped: " + links, links.size == 2)
    }

    @Test
    fun loginWallAndCountdownAreClassified() {
        val loginHtml = """<html><body><p>Please sign in to download</p><a href="/login?redirectUrl=/dl/x">Log In</a></body></html>"""
        val loginState = ZlibDownloadParser.classify(loginHtml, dlUrl)
        assertTrue(loginState is ZlibDownloadParser.PageState.LoginRequired)

        val countdownHtml = """<html><body><div id="countdown" data-countdown="7">Please wait</div></body></html>"""
        val countdownState = ZlibDownloadParser.classify(countdownHtml, dlUrl)
        assertEquals(ZlibDownloadParser.PageState.Countdown(7), countdownState)
    }

    @Test
    fun filenameFromContentDisposition() {
        assertEquals(
            "book.pdf",
            ZlibDownloadParser.filenameFromDisposition("""attachment; filename="book.pdf""""),
        )
        assertEquals(
            "café.pdf",
            ZlibDownloadParser.filenameFromDisposition("attachment; filename*=UTF-8''caf%C3%A9.pdf"),
        )
    }

    @Test
    fun filenameDispositionHandlesLanguageTagCharsetAndPlus() {
        // D22：filename* 带 language tag + ISO-8859-1 charset。
        assertEquals(
            "café.pdf",
            ZlibDownloadParser.filenameFromDisposition("attachment; filename*=ISO-8859-1'en'caf%E9.pdf"),
        )
        // D22：带引号的 plain filename 允许分号。
        assertEquals(
            "a;b.pdf",
            ZlibDownloadParser.filenameFromDisposition("attachment; filename=\"a;b.pdf\""),
        )
        // D22：未编码的 + 不得被 URLDecoder 变成空格。
        assertEquals(
            "a+b.pdf",
            ZlibDownloadParser.filenameFromDisposition("attachment; filename=a+b.pdf"),
        )
    }
}
