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
 * 不改 ZlibDetailParser/ZBookDetail（本任务作用域只新增 ZlibReader*.kt），
 * 所以先 fetchDetail 拿 detailPath，再 fetchBytes 抓原始详情 HTML 抽 reader-link。
 */
class ZlibReaderResolverTest {

    @Test
    fun resolvesReaderUrlFromRealDetailPageHtml() = runBlocking {
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

        val state = resolver.resolve("6y7KYwknyQ", "android-programming")

        assertTrue(state is ZlibReaderLaunchState.Ready)
        val ready = state as ZlibReaderLaunchState.Ready
        assertTrue(ready.readerUrl, ready.readerUrl.startsWith("https://reader.z-lib.by/read/"))
        assertTrue(ready.readerUrl, ready.readerUrl.contains("file_access_token="))
    }

    @Test
    fun missingReaderLinkBecomesActionableError() = runBlocking {
        val resolver = resolverWith(
            detailPath = "/book/hash/x.html",
            loadPage = { "<html><body>no reader link</body></html>" },
        )

        val state = resolver.resolve("hash", null)

        assertEquals(ZlibReaderLaunchState.Error(ZLIB_READER_MISSING_LINK_MESSAGE), state)
    }

    @Test
    fun invalidReaderLinkIsRejectedBeforeBrowserOpen() = runBlocking {
        val html = "<a class=\"reader-link\" href=\"http://reader.z-lib.by/read/a/b/c.html?file_access_token=t\">x</a>"
        val resolver = resolverWith(detailPath = "/book/hash/x.html", loadPage = { html })

        val state = resolver.resolve("hash", null)

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

        val state = resolver.resolve("hash", null)

        assertTrue(state is ZlibReaderLaunchState.Ready)
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
            runBlocking { resolver.resolve("hash", null) }
            fail("expected ZlibException from loadPage")
        } catch (expected: ZlibException) {
            assertEquals(ZlibException.Kind.HTTP, expected.kind)
            assertEquals(500, expected.httpCode)
        }
    }

    @Test
    fun urlFailuresMapToActionableChineseMessages() {
        // realnet task-33：所有解析失败都带「当前受限、建议下载」主提示，不承诺可读。
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

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("zlib/" + name)) {
            "fixture not found: " + name
        }.bufferedReader().use { it.readText() }
}
