package app.particlebook.android.ui

import app.particlebook.android.zlib.ZBookDetail
import app.particlebook.android.zlib.ZlibException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A13：详情页 → reader URL 解析器（解析成功后交给外部浏览器，不再内嵌 WebView）。
 *
 * task-77：p15 真机实测匿名详情页的 reader-link 也能读到正文，所以
 *  - reader-link 存在时，登录/匿名都返回 Ready；
 *  - 页面是匿名详情（CurrentUser == null）却取不到 reader-link 时才按登录态给提示；
 *  - 最终 503（挑战没过）给 CHALLENGE 文案，而不是「服务器返回错误 503」。
 */
class ZlibReaderResolverTest {

    @Test
    fun resolvesReaderUrlFromRealAnonymousDetailPageHtml() = runBlocking {
        val html = fixture("book-detail.html")
        val resolver = ZlibReaderResolver(
            loadDetail = { hash, slug ->
                assertEquals("6y7KYwknyQ", hash)
                assertEquals("android-programming", slug)
                detail("Android 编程权威指南", "/book/6y7KYwknyQ/android-programming.html")
            },
            loadPage = { path ->
                assertEquals("/book/6y7KYwknyQ/android-programming.html", path)
                html
            },
        )

        val state = resolver.resolve("6y7KYwknyQ", "android-programming", loggedIn = false)

        assertTrue(state is ZlibReaderLaunchState.Ready)
        val ready = state as ZlibReaderLaunchState.Ready
        assertTrue(ready.readerUrl, ready.readerUrl.startsWith("https://reader.z-lib.by/read/"))
        assertTrue(ready.readerUrl, ready.readerUrl.contains("file_access_token="))
    }

    /**
     * realnet t68 P1-1 的旧口径是「loggedIn + 匿名详情 → SESSION_EXPIRED」；task-77 依据 p15 真机实测
     * 推翻：匿名 limited 票据能读到正文，所以已登录用户拿到匿名详情也应直接 Ready，而不是误报。
     */
    @Test
    fun loggedInUserStillGetsAnonymousReaderLink() = runBlocking {
        val resolver = resolverWith(
            detailPath = "/book/hash/x.html",
            loadPage = { fixture("book-detail.html") },
        )

        val state = resolver.resolve("hash", null, loggedIn = true)

        assertTrue(state is ZlibReaderLaunchState.Ready)
        assertTrue(
            (state as ZlibReaderLaunchState.Ready).readerUrl.contains("file_access_token="),
        )
    }

    @Test
    fun missingReaderLinkBecomesActionableError() = runBlocking {
        // 页面里没有 CurrentUser 片段 → 登录态 Unknown，不是「匿名详情」，保留「未提供入口」。
        val resolver = resolverWith(
            detailPath = "/book/hash/x.html",
            loadPage = { "<html><body>no reader link</body></html>" },
        )

        val state = resolver.resolve("hash", null, loggedIn = true)

        assertEquals(ZlibReaderLaunchState.Error(ZLIB_READER_MISSING_LINK_MESSAGE), state)
    }

    @Test
    fun anonymousDetailWithoutReaderLinkIsSessionExpiredForLoggedInUser() = runBlocking {
        val resolver = resolverWith(
            detailPath = "/book/hash/x.html",
            loadPage = { anonymousPage("<div>no reader link</div>") },
        )

        val state = resolver.resolve("hash", null, loggedIn = true)

        assertEquals(ZlibReaderLaunchState.Error(ZLIB_READER_SESSION_EXPIRED_MESSAGE), state)
    }

    @Test
    fun anonymousDetailWithoutReaderLinkAsksAnonymousUserToLogIn() = runBlocking {
        val resolver = resolverWith(
            detailPath = "/book/hash/x.html",
            loadPage = { anonymousPage("<div>no reader link</div>") },
        )

        val state = resolver.resolve("hash", null, loggedIn = false)

        assertEquals(ZlibReaderLaunchState.Error(ZLIB_READER_LOGIN_REQUIRED_MESSAGE), state)
        val message = (state as ZlibReaderLaunchState.Error).message
        assertTrue(message, message.contains("登录后阅读"))
        assertTrue(message, message.contains("下载到书架"))
    }

    // task-58：cookie 失效时 fetchDetail 成功、抓 HTML 却 200 落登录页；不能误报「未提供入口」。

    @Test
    fun loginPageHtmlBecomesSessionExpiredForLoggedInUser() = runBlocking {
        val resolver = resolverWith(
            detailPath = "/book/hash/x.html",
            loadPage = { fixture("login-error.html") },
        )

        val state = resolver.resolve("hash", null, loggedIn = true)

        assertEquals(ZlibReaderLaunchState.Error(ZLIB_READER_SESSION_EXPIRED_MESSAGE), state)
        val message = (state as ZlibReaderLaunchState.Error).message
        assertTrue(message, message.contains("登录可能已失效"))
    }

    @Test
    fun loginPageHtmlAsksAnonymousUserToLogIn() = runBlocking {
        val resolver = resolverWith(
            detailPath = "/book/hash/x.html",
            loadPage = { fixture("login-error.html") },
        )

        val state = resolver.resolve("hash", null, loggedIn = false)

        assertEquals(ZlibReaderLaunchState.Error(ZLIB_READER_LOGIN_REQUIRED_MESSAGE), state)
    }

    @Test
    fun challengePageHtmlBecomesVerificationError() = runBlocking {
        val resolver = resolverWith(
            detailPath = "/book/hash/x.html",
            loadPage = { fixture("challenge.html") },
        )

        val state = resolver.resolve("hash", null, loggedIn = true)

        assertEquals(ZlibReaderLaunchState.Error(ZLIB_READER_CHALLENGE_PAGE_MESSAGE), state)
        val message = (state as ZlibReaderLaunchState.Error).message
        assertTrue(message, message.contains("人机验证"))
    }

    @Test
    fun invalidReaderLinkIsRejectedBeforeBrowserOpen() = runBlocking {
        val html = "<a class=\"reader-link\" href=\"http://reader.z-lib.by/read/a/b/c.html?file_access_token=t\">x</a>"
        val resolver = resolverWith(detailPath = "/book/hash/x.html", loadPage = { html })

        val state = resolver.resolve("hash", null, loggedIn = true)

        assertTrue(state is ZlibReaderLaunchState.Error)
        assertTrue((state as ZlibReaderLaunchState.Error).message.contains("HTTPS"))
    }

    @Test
    fun expiredChallengeIsRetriedOnceAfterFetchingDetailAgain() = runBlocking {
        val html = "<a class=\"reader-link\" " +
            "href=\"https://reader.z-lib.by/read/a/book/hash/x.html?file_access_token=t\">x</a>"
        var detailCalls = 0
        var pageCalls = 0
        val resolver = ZlibReaderResolver(
            loadDetail = { _, _ ->
                detailCalls += 1
                detail("书", "/book/hash/x.html")
            },
            loadPage = { path ->
                pageCalls += 1
                if (pageCalls == 1) {
                    // fetchBytes 不会自己解挑战；挑战 cookie 过期时返回 503，需重取详情页。
                    throw ZlibException("HTTP 503 for " + path, ZlibException.Kind.HTTP, httpCode = 503)
                }
                html
            },
        )

        val state = resolver.resolve("hash", null, loggedIn = false)

        assertTrue(state is ZlibReaderLaunchState.Ready)
        assertEquals(2, detailCalls)
        assertEquals(2, pageCalls)
    }

    @Test
    fun persistent503BecomesChallengeMessageInsteadOfRawHttpError() = runBlocking {
        var detailCalls = 0
        var pageCalls = 0
        val resolver = ZlibReaderResolver(
            loadDetail = { _, _ ->
                detailCalls += 1
                detail("书", "/book/hash/x.html")
            },
            loadPage = { path ->
                pageCalls += 1
                throw ZlibException("HTTP 503 for " + path, ZlibException.Kind.HTTP, httpCode = 503)
            },
        )

        val state = resolver.resolve("hash", null, loggedIn = true)

        assertEquals(ZlibReaderLaunchState.Error(ZLIB_READER_CHALLENGE_PAGE_MESSAGE), state)
        assertEquals(2, detailCalls)
        assertEquals(2, pageCalls)
    }

    @Test
    fun non503FailurePropagatesToViewModel() {
        val resolver = ZlibReaderResolver(
            loadDetail = { _, _ -> detail("书", "/book/hash/x.html") },
            loadPage = { throw ZlibException("HTTP 500", ZlibException.Kind.HTTP, httpCode = 500) },
        )

        try {
            runBlocking { resolver.resolve("hash", null, loggedIn = true) }
            fail("expected ZlibException from loadPage")
        } catch (expected: ZlibException) {
            assertEquals(ZlibException.Kind.HTTP, expected.kind)
            assertEquals(500, expected.httpCode)
        }
    }

    @Test
    fun urlFailuresMapToActionableChineseMessages() {
        // 所有解析失败都带「当前受限、建议下载」主提示，不承诺可读。
        for (reason in ZlibReaderUrl.Failure.values()) {
            val message = zlibReaderUrlFailureMessage(reason)
            assertTrue(
                reason.name + " 文案缺少受限提示: " + message,
                message.contains(ZLIB_READER_LIMITED_MESSAGE),
            )
        }
        assertTrue(
            zlibReaderUrlFailureMessage(ZlibReaderUrl.Failure.MISSING_TICKET)
                .contains("缺少访问票据"),
        )
        assertTrue(
            zlibReaderUrlFailureMessage(ZlibReaderUrl.Failure.UNTRUSTED_HOST)
                .contains("域名不受信任"),
        )
        assertTrue(
            zlibReaderUrlFailureMessage(ZlibReaderUrl.Failure.INSECURE_SCHEME)
                .contains("HTTPS"),
        )
    }

    private fun resolverWith(
        detailPath: String,
        loadPage: suspend (String) -> String,
    ): ZlibReaderResolver = ZlibReaderResolver(
        loadDetail = { _, _ -> detail("书", detailPath) },
        loadPage = loadPage,
    )

    private fun detail(title: String, detailPath: String): ZBookDetail = ZBookDetail(
        bookId = "id",
        hash = "hash",
        title = title,
        authors = emptyList(),
        language = null,
        year = null,
        format = "pdf",
        fileSize = null,
        isbn = null,
        coverUrl = null,
        detailPath = detailPath,
        downloadPath = "/dl/x",
    )

    /** 真实匿名详情页的最小形态：站点脚本里的 CurrentUser = new User(null)。 */
    private fun anonymousPage(body: String): String =
        "<html><head><script>const CurrentUser = new User(null);</script></head>" +
            "<body>" + body + "</body></html>"

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("zlib/" + name)) {
            "fixture not found: " + name
        }.bufferedReader().use { it.readText() }
}
