package app.particlebook.android.ui

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * task-89 A13：在线阅读结构化埋点 zlib_reader_open。
 *
 * p15 task-69 缺口：打开成功/失败/无浏览器无法从日志判定。这里锁定纯函数输出的字段顺序、
 * 脱敏（不含 reader URL/query/票据/JWT 原文）、6 种 outcome 的 wire 值，以及 JWT
 * is_public/limited 的 true/false/unknown 三态。
 */
class ZlibReaderOpenLogTest {

    @Test
    fun logLineContainsAllStructuredFieldsInStableOrder() {
        val line = zlibReaderOpenLogLine(
            ZlibReaderOpenEvent(
                bookHash = "d5eARxJrQ5",
                outcome = ZlibReaderOpenResult.READY,
                isPublic = ZlibReaderAccess.FALSE,
                limited = ZlibReaderAccess.TRUE,
                elapsedMs = 321,
            ),
        )

        assertEquals(
            "zlib_reader_open book_hash=d5eARxJrQ5 outcome=ready " +
                "is_public=false limited=true elapsed_ms=321",
            line,
        )
    }

    @Test
    fun everyOutcomeUsesStableWireValue() {
        val expected = mapOf(
            ZlibReaderOpenResult.READY to "ready",
            ZlibReaderOpenResult.OPEN_FAILED to "open_failed",
            ZlibReaderOpenResult.NO_BROWSER to "no_browser",
            ZlibReaderOpenResult.LOGIN_PAGE to "login_page",
            ZlibReaderOpenResult.CHALLENGE to "challenge",
            ZlibReaderOpenResult.NOT_FOUND to "not_found",
        )
        assertEquals(ZlibReaderOpenResult.values().size, expected.size)
        for ((outcome, wire) in expected) {
            val line = zlibReaderOpenLogLine(event(outcome = outcome))
            assertTrue(line, line.contains(" outcome=" + wire + " "))
        }
    }

    @Test
    fun bookHashIsSanitizedAgainstLogInjection() {
        val line = zlibReaderOpenLogLine(
            event(bookHash = " d5eARxJrQ5\noutcome=ready file_access_token=abc "),
        )

        assertFalse(line, line.contains("\n"))
        assertFalse(line, line.contains("\r"))
        // 换行/空格/等号被过滤；字段结构仍是 6 段，不能借 book_hash 伪造 outcome。
        assertEquals(
            "zlib_reader_open book_hash=d5eARxJrQ5outcomereadyfile_access_tokenabc " +
                "outcome=ready is_public=unknown limited=unknown elapsed_ms=0",
            line,
        )
    }

    @Test
    fun emptyBookHashAndNegativeElapsedAreNormalized() {
        val unknownHash = zlibReaderOpenLogLine(event(bookHash = "   "))
        assertTrue(unknownHash, unknownHash.contains("book_hash=unknown"))

        val clamped = zlibReaderOpenLogLine(event(elapsedMs = -5))
        assertTrue(clamped, clamped.endsWith(" elapsed_ms=0"))
    }

    @Test
    fun longBookHashIsTruncated() {
        assertEquals(64, zlibReaderLogBookHash("a".repeat(100)).length)
        assertEquals(64, zlibReaderLogBookHash("a".repeat(64)).length)
        assertEquals("abc123", zlibReaderLogBookHash(" abc123 "))
    }

    @Test
    fun logLineNeverContainsReaderUrlOrToken() {
        val token = jwt("""{"is_public":true,"access_level":"limited"}""")
        val url = readerUrl(token)
        val access = zlibReaderUrlAccess(url)

        val line = zlibReaderOpenLogLine(
            event(
                bookHash = "6y7KYwknyQ",
                outcome = ZlibReaderOpenResult.READY,
                isPublic = access.isPublic,
                limited = access.limited,
                elapsedMs = 42,
            ),
        )

        assertFalse(line, line.contains("http"))
        assertFalse(line, line.contains("reader.z-lib.by"))
        assertFalse(line, line.contains("file_access_token"))
        assertFalse(line, line.contains("signature"))
        assertFalse(line, line.contains(token))
        assertTrue(line, line.contains("is_public=true"))
        assertTrue(line, line.contains("limited=true"))
    }

    @Test
    fun urlAccessReadsPublicAndLimitedFromJwt() {
        val privateFull = zlibReaderUrlAccess(
            readerUrl(jwt("""{"is_public":false,"access_level":"full"}""")),
        )
        assertEquals(ZlibReaderAccess.FALSE, privateFull.isPublic)
        assertEquals(ZlibReaderAccess.FALSE, privateFull.limited)

        val publicLimited = zlibReaderUrlAccess(
            readerUrl(jwt("""{"is_public":true,"access_level":"limited"}""")),
        )
        assertEquals(ZlibReaderAccess.TRUE, publicLimited.isPublic)
        assertEquals(ZlibReaderAccess.TRUE, publicLimited.limited)
    }

    @Test
    fun urlAccessToleratesSpacesAndQuotedJwtValues() {
        val access = zlibReaderUrlAccess(
            readerUrl(jwt("""{ "is_public": "true", "access_level": "limited" }""")),
        )

        assertEquals(ZlibReaderAccess.TRUE, access.isPublic)
        assertEquals(ZlibReaderAccess.TRUE, access.limited)
    }

    @Test
    fun urlAccessSupportsAccessTokenParameterFallback() {
        val token = jwt("""{"is_public":true,"access_level":"full"}""")
        val access = zlibReaderUrlAccess(readerUrl(token, param = "access_token"))

        assertEquals(ZlibReaderAccess.TRUE, access.isPublic)
        assertEquals(ZlibReaderAccess.FALSE, access.limited)
    }

    @Test
    fun urlAccessIsUnknownWhenClaimsCannotBeRead() {
        val unknown = ZlibReaderUrlAccess()
        assertEquals(ZlibReaderAccess.UNKNOWN, unknown.isPublic)
        assertEquals(ZlibReaderAccess.UNKNOWN, unknown.limited)

        val inputs = listOf(
            null,
            "",
            "not-a-url",
            "https://reader.z-lib.by/read/a/book/hash/x.html",
            readerUrl("abc"),
            readerUrl("abc.def"),
            readerUrl(jwt("""{"foo":"bar"}""")),
        )
        for (url in inputs) {
            assertEquals("url=" + url, unknown, zlibReaderUrlAccess(url))
        }
    }

    @Test
    fun failureOutcomeMappingCoversEveryCategory() {
        assertEquals(ZlibReaderOpenResult.LOGIN_PAGE, zlibReaderFailureOutcome(ZlibReaderFailure.LOGIN_PAGE))
        assertEquals(ZlibReaderOpenResult.CHALLENGE, zlibReaderFailureOutcome(ZlibReaderFailure.CHALLENGE))
        assertEquals(ZlibReaderOpenResult.NOT_FOUND, zlibReaderFailureOutcome(ZlibReaderFailure.NOT_FOUND))
        assertEquals(ZlibReaderOpenResult.OPEN_FAILED, zlibReaderFailureOutcome(ZlibReaderFailure.OTHER))
        // 旧构造（failure=null）或未分类失败也不能丢事件。
        assertEquals(ZlibReaderOpenResult.OPEN_FAILED, zlibReaderFailureOutcome(null))
    }

    @Test
    fun browserLaunchOutcomeMappingCoversEveryResult() {
        assertEquals(ZlibReaderOpenResult.READY, zlibReaderLaunchOutcome(ZlibReaderBrowserLaunch.OPENED))
        assertEquals(
            ZlibReaderOpenResult.NO_BROWSER,
            zlibReaderLaunchOutcome(ZlibReaderBrowserLaunch.NO_BROWSER),
        )
        assertEquals(
            ZlibReaderOpenResult.OPEN_FAILED,
            zlibReaderLaunchOutcome(ZlibReaderBrowserLaunch.FAILED),
        )
    }

    private fun event(
        bookHash: String = "hash",
        outcome: ZlibReaderOpenResult = ZlibReaderOpenResult.READY,
        isPublic: ZlibReaderAccess = ZlibReaderAccess.UNKNOWN,
        limited: ZlibReaderAccess = ZlibReaderAccess.UNKNOWN,
        elapsedMs: Long = 0L,
    ): ZlibReaderOpenEvent = ZlibReaderOpenEvent(
        bookHash = bookHash,
        outcome = outcome,
        isPublic = isPublic,
        limited = limited,
        elapsedMs = elapsedMs,
    )

    private fun readerUrl(token: String, param: String = "file_access_token"): String =
        "https://reader.z-lib.by/read/abc/book/hash/x.html" +
            "?client_key=ck&signature=sig&" + param + "=" + token

    private fun jwt(payloadJson: String): String =
        base64Url("""{"alg":"none","typ":"JWT"}""") + "." + base64Url(payloadJson) + ".signature"

    private fun base64Url(text: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))
}
