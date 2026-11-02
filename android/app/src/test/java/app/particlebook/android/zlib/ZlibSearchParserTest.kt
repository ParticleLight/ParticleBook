package app.particlebook.android.zlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ZlibSearchParserTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("zlib/$name")!!.use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    @Test
    fun parsesRealSearchPage() {
        val page = ZlibSearchParser.parse(fixture("search-android.html"), "android", 1)

        // 真实页 51 个 <z-bookcard>，其中第 3 个是 hidden aria-hidden 模板卡（id 42858211，title=查询词）；
        // 站方每页展示 50 条，解析时必须剔除模板卡（否则空结果页会被当成 1 条）。
        assertEquals(50, page.books.size)
        assertEquals(3, page.totalPages)
        assertTrue("hidden template card must be skipped", page.books.none { it.id == "42858211" })
        assertEquals("android", page.query)
        assertEquals(1, page.page)

        val first = page.books.first()
        assertEquals("15846767", first.id)
        assertEquals("Android编程权威指南（中文版）", first.title)
        assertEquals("pdf", first.extension)
        assertEquals("9.96 MB", first.fileSize)
        assertTrue(first.href.startsWith("/book/"))
        assertTrue(first.downloadPath.startsWith("/dl/"))
    }

    /**
     * 真实空结果页（2026-11-02 抓取 /s/zzzzqqqxxx）结构：`#searchResultBox` 内先有一个
     * `hidden aria-hidden="true"` 的模板卡片（counter 0、publisher=查询词），再是 `div.notFound`。
     * 必须跳过隐藏卡片并识别空态，否则会被当成 1 条结果。
     */
    @Test
    fun emptyStateWithHiddenTemplateCardReturnsEmptyList() {
        val html = """
            <!DOCTYPE html><html><head><title>zzzzqqqxxx: search on Z-Library</title></head><body>
            <div id="searchResultBox">
              <div class="book-item resItemBoxBooks" hidden aria-hidden="true">
                <div class="counter">0</div>
                <z-bookcard id="49972421" isbn="" href="/book/xy0rMdYk8o/zzzzqqqxxx.html"
                    download="/dl/vXJql7e1pM" deleted="0" publisher="zzzzqqqxxx" language="english"
                    year="2022" extension="mobi" filesize="9.04 MB" rating="4.9" quality="5.0" nofollow>
                  <img data-src=""/>
                  <div slot="title">zzzzqqqxxx</div>
                  <div slot="author">zzzzqqqxxx</div>
                  <div slot="note"></div>
                  <div slot="extend"></div>
                </z-bookcard>
              </div>
              <div class="notFound color3">On your request nothing has been found. Do you want to
                <a class="plink zalertLink" data-toggle="modal" data-target="#zalertDialog">create a Z-Alert</a>
                on this query?</div>
            </div>
            </body></html>
        """.trimIndent()

        val page = ZlibSearchParser.parse(html, "zzzzqqqxxx", 1)

        assertEquals("zzzzqqqxxx", page.query)
        assertEquals(0, page.books.size)
        assertEquals(0, page.totalPages)
    }

    @Test
    fun hiddenTemplateCardIsSkippedOnNormalPage() {
        val html = """
            <div id="searchResultBox">
              <div class="book-item resItemBoxBooks" hidden aria-hidden="true">
                <div class="counter">0</div>
                <z-bookcard id="0" href="/book/template.html" download="/dl/template">
                  <div slot="title">template</div>
                  <div slot="author">template</div>
                </z-bookcard>
              </div>
              <div class="book-item resItemBoxBooks ">
                <div class="counter">1</div>
                <z-bookcard id="7" href="/book/real.html" download="/dl/real">
                  <div slot="title">real book</div>
                  <div slot="author">real author</div>
                </z-bookcard>
              </div>
              <script>var pagerOptions = { pagesTotal: 1, pagesSpan: 10, pageCurrent: 1 };</script>
            </div>
        """.trimIndent()

        val books = ZlibSearchParser.parseCards(html)

        assertEquals(1, books.size)
        assertEquals("7", books[0].id)
        assertEquals("real book", books[0].title)
    }

    /** 真实空结果页夹具（2026-11-02 抓取 /s/zzzzqqqxxx，91,845B）→ 0 条、totalPages 0。 */
    @Test
    fun parsesRealEmptySearchPage() {
        val page = ZlibSearchParser.parse(fixture("search-empty.html"), "zzzzqqqxxx", 1)

        assertEquals(0, page.books.size)
        assertEquals(0, page.totalPages)
    }

    @Test
    fun garbageHtmlThrowsParse() {
        val html = "<html><body><h1>hello world</h1><p>maintenance</p></body></html>"
        try {
            ZlibSearchParser.parse(html, "android", 1)
            fail("expected ZlibException(PARSE)")
        } catch (e: ZlibException) {
            assertEquals(ZlibException.Kind.PARSE, e.kind)
        }
    }

    @Test
    fun decodesHtmlEntitiesInTitle() {
        val html = """
            <z-bookcard id="42" href="/book/x.html" download="/dl/y" extension="epub">
              <div slot="title">A &amp; B &lt;test&gt; &#39;quoted&#39;</div>
              <div slot="author">Me &amp; You</div>
            </z-bookcard>
        """.trimIndent()

        val books = ZlibSearchParser.parseCards(html)

        assertEquals(1, books.size)
        assertEquals("A & B <test> 'quoted'", books[0].title)
        assertEquals("Me & You", books[0].author)
    }
}
