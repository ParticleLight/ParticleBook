package app.particlebook.android.zlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A12：详情页推荐区解析（真实 fixture + 合成用例）。 */
class ZlibDetailRecommendationsTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("zlib/$name")!!.use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    private val bookHash = "026fde1c4967f41586cccc86df8e0433806d2dd55ed93fb56a8fd9dca508ab28"

    private val baseUrl =
        "https://z-lib.by/book/" + bookHash + "/android%E7%BC%96%E7%A8%8B.html"

    /** 真实详情节：z-masonry[source=recommend] 内 42 张卡（batch=21 只是懒加载批次）。 */
    @Test
    fun parsesRealRecommendationCards() {
        val recs = ZlibDetailParser.parseRecommendations(fixture("book-detail.html"), baseUrl)

        assertEquals("fixture 内 42 张推荐卡都应解析出来", 42, recs.size)
        assertEquals(42, recs.map { it.hash }.toSet().size)

        val first = recs.first()
        assertEquals("poMkmQOvyO", first.hash)
        assertEquals(
            "jetpack-compose%E4%BB%8E%E5%85%A5%E9%97%A8%E5%88%B0%E5%AE%9E%E6%88%98",
            first.slug,
        )
        assertEquals("Jetpack Compose从入门到实战", first.title)
        assertEquals("王鹏", first.author)
        assertEquals("116074975", first.bookId)
        assertTrue(
            "推荐封面应是绝对 URL",
            first.coverUrl != null && first.coverUrl.startsWith("https://") && first.coverUrl.endsWith(".jpg"),
        )
    }

    /** 推荐数据必须经 parse() 流进 ZBookDetail（UI 只能拿到 ZBookDetail）。 */
    @Test
    fun parseCarriesRecommendationsIntoDetail() {
        val detail = ZlibDetailParser.parse(fixture("book-detail.html"), baseUrl, bookHash)

        assertEquals(42, detail.recommendations.size)
        assertEquals("poMkmQOvyO", detail.recommendations.first().hash)
    }

    /** cover-not-exists.png 的卡保留卡片但封面为 null（UI 走占位封面）。 */
    @Test
    fun placeholderCoverBecomesNull() {
        val recs = ZlibDetailParser.parseRecommendations(fixture("book-detail.html"), baseUrl)

        val placeholder = recs.first { it.hash == "6yV0plRZoa" }
        assertEquals("Android项目开发详解", placeholder.title)
        assertNull(placeholder.coverUrl)
    }

    /** 无推荐容器（旧页面/极简页）→ 空列表，不抛异常。 */
    @Test
    fun missingMasonryReturnsEmpty() {
        val html = """
            <div class="details-book cardBooks" data-book_id="1">
              <h1 class="book-title">Only Details</h1>
            </div>
        """.trimIndent()

        assertTrue(ZlibDetailParser.parseRecommendations(html, baseUrl).isEmpty())
    }

    /** 合成：实体解码、协议相对封面、data-src 兜底、同 hash 去重、无标题/非 book 链接跳过。 */
    @Test
    fun syntheticCardsDecodeAndDropInvalid() {
        val html = """
            <div class="books-mosaic">
              <z-masonry source="recommend" columns="5" batch="21">
                <a href="/book/aaa111/one.html?dsource=recommend">
                  <z-cover volume title="One &amp; Only" author="Author A"><img src="//covers.example/x.jpg"></z-cover>
                </a>
                <a href="/book/aaa111/dup.html"><z-cover title="Duplicate"><img src="/c.jpg"></z-cover></a>
                <a href="/book/bbb222/two.html"><z-cover title="Two"><img data-src="/img/two.jpg"></z-cover></a>
                <a href="/login"><z-cover title="Login card"><img src="/c.jpg"></z-cover></a>
                <a href="/book/ccc333/notitle.html"><z-cover><img src="/c.jpg"></z-cover></a>
              </z-masonry>
            </div>
        """.trimIndent()

        val recs = ZlibDetailParser.parseRecommendations(html, "https://z-lib.by/book/abc/real.html")

        assertEquals(2, recs.size)
        assertEquals("aaa111", recs[0].hash)
        assertEquals("one", recs[0].slug)
        assertEquals("One & Only", recs[0].title)
        assertEquals("Author A", recs[0].author)
        assertEquals("https://covers.example/x.jpg", recs[0].coverUrl)

        assertEquals("bbb222", recs[1].hash)
        assertEquals("https://z-lib.by/img/two.jpg", recs[1].coverUrl)
    }
}
