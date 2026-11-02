package app.particlebook.android.zlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ZlibDetailParserTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("zlib/$name")!!.use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    private val bookHash = "026fde1c4967f41586cccc86df8e0433806d2dd55ed93fb56a8fd9dca508ab28"

    private val baseUrl =
        "https://z-lib.by/book/" + bookHash + "/android%E7%BC%96%E7%A8%8B.html"

    @Test
    fun parsesRealDetailPage() {
        val detail = ZlibDetailParser.parse(fixture("book-detail.html"), baseUrl, bookHash)

        assertEquals("15846767", detail.bookId)
        assertEquals(bookHash, detail.hash)
        assertEquals("Android编程权威指南（中文版）", detail.title)
        assertEquals(listOf("Android编程权威指南（中文版）.pdf"), detail.authors)
        assertEquals("Chinese", detail.language)
        assertEquals("PDF", detail.format)
        assertEquals("9.96 MB", detail.fileSize)
        assertEquals("/dl/AX1jDr5bOm", detail.downloadPath)
        assertNull("cover-not-exists.png 必须视为无封面", detail.coverUrl)
        assertNull("空 isbn 属性视为未知", detail.isbn)
        assertTrue(detail.detailPath.startsWith("/book/"))
    }

    @Test
    fun ignoresReaderLinkAndHiddenTemplateDownloadAnchors() {
        val html = """
            <html><body>
            <template id="dl-template">
              <div class="details-book cardBooks" data-book_id="999">
                <a class="btn btn-default dlButton addDownloadedBook" href="/dl/HIDDEN">pdf, 1 MB</a>
              </div>
            </template>
            <div class="details-book cardBooks" data-book_id="15846767">
              <h1 class="book-title">Real Book</h1>
              <a class="btn btn-primary dlButton reader-link"
                 href="https://reader.z-lib.by/read/abc/book/6y7KYwknyQ/real.html">Read Online</a>
              <a class="btn btn-default dlButton addDownloadedBook" href="/dl/REAL123">pdf, 2 MB</a>
            </div>
            </body></html>
        """.trimIndent()

        val detail = ZlibDetailParser.parse(html, "https://z-lib.by/book/abc/real.html")

        assertEquals("/dl/REAL123", detail.downloadPath)
        assertEquals("15846767", detail.bookId)
        assertEquals("Real Book", detail.title)
    }

    @Test
    fun absoluteCoverUrlAndPropertiesParsed() {
        val html = """
            <div class="details-book cardBooks" data-book_id="42">
              <div class="details-book-cover-container">
                <z-cover isbn="9787115348418"><img src="/img/covers/pic.jpg"></z-cover>
              </div>
              <h1 class="book-title">Covered</h1>
              <i class="authors"><a href="/author/x">Author One</a><a href="/author/y">Author Two</a></i>
              <a class="btn btn-default dlButton addDownloadedBook" href="/dl/COVER">pdf, 3 MB</a>
              <div class='bookProperty property_language'>
                <div class="property_value">English</div>
              </div>
              <div class='bookProperty property_file'>
                <div class="property_value">EPUB, 2.5 MB</div>
              </div>
            </div>
        """.trimIndent()

        val detail = ZlibDetailParser.parse(html, "https://z-lib.by/book/cc/covered.html")

        assertEquals("https://z-lib.by/img/covers/pic.jpg", detail.coverUrl)
        assertEquals("9787115348418", detail.isbn)
        assertEquals(listOf("Author One", "Author Two"), detail.authors)
        assertEquals("English", detail.language)
        assertEquals("EPUB", detail.format)
        assertEquals("2.5 MB", detail.fileSize)
    }

    @Test
    fun missingTitleThrowsParse() {
        try {
            ZlibDetailParser.parse("<html><body>nothing here</body></html>", baseUrl)
            fail("expected ZlibException")
        } catch (e: ZlibException) {
            assertEquals(ZlibException.Kind.PARSE, e.kind)
        }
    }

    @Test
    fun missingDownloadLinkThrowsParse() {
        val html = """
            <div class="details-book cardBooks" data-book_id="7">
              <h1 class="book-title">No Download</h1>
            </div>
        """.trimIndent()
        try {
            ZlibDetailParser.parse(html, baseUrl)
            fail("expected ZlibException")
        } catch (e: ZlibException) {
            assertEquals(ZlibException.Kind.PARSE, e.kind)
        }
    }
}
