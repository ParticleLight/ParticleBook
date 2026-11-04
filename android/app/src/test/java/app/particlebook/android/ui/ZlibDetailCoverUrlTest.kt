package app.particlebook.android.ui

import app.particlebook.android.zlib.ZlibDetailParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A5-P3-5：详情页封面绝对 URL 必须同时有 http(s) scheme 和 authority。
 * 只通过公开 parse() 覆盖 absoluteUrl 分支，避免测试直接依赖 private 实现。
 */
class ZlibDetailCoverUrlTest {

    @Test
    fun absoluteHttpCoverWithoutHostIsRejected() {
        assertNull(parseCover("http:///cover.jpg"))
    }

    @Test
    fun absoluteHttpsCoverWithoutHostIsRejected() {
        assertNull(parseCover("https:///cover.jpg"))
    }

    @Test
    fun absoluteHttpCoverWithHostIsUpgradedToHttps() {
        assertEquals("https://covers.example/cover.jpg", parseCover("http://covers.example/cover.jpg"))
    }

    @Test
    fun absoluteHttpsCoverWithHostIsKept() {
        assertEquals("https://covers.example/cover.jpg", parseCover("https://covers.example/cover.jpg"))
    }

    private fun parseCover(src: String): String? {
        val html = """
            <div class="details-book" data-book_id="123">
              <h1 class="book-title">Test Book</h1>
              <i class="authors"><a>Test Author</a></i>
              <z-cover><img src="$src"></z-cover>
              <a class="dlButton addDownloadedBook" href="/dl/abc">pdf, 1 MB</a>
            </div>
        """.trimIndent()
        return ZlibDetailParser.parse(html, "https://z-lib.by/book/hash/slug.html").coverUrl
    }
}
