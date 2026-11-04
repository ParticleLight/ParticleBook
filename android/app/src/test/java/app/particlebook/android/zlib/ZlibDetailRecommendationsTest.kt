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

    /** 封面相对路径按详情页 URL 做 URI.resolve，而不是字符串拼在 page.html 后面。 */
    @Test
    fun relativeCoverResolvesAgainstDetailPageUrl() {
        val html = """
            <z-masonry source="recommend">
              <a href="/book/rel111/one.html">
                <z-cover title="Relative"><img src="covers/x.jpg"></z-cover>
              </a>
              <a href="/book/rel222/two.html">
                <z-cover title="Parent Relative"><img src="../img/y.jpg"></z-cover>
              </a>
            </z-masonry>
        """.trimIndent()

        val recs = ZlibDetailParser.parseRecommendations(html, baseUrl)

        assertEquals(2, recs.size)
        assertEquals("https://z-lib.by/book/" + bookHash + "/covers/x.jpg", recs[0].coverUrl)
        assertEquals("https://z-lib.by/book/img/y.jpg", recs[1].coverUrl)
    }

    /** data:/blob: 内联封面不允许进入图片加载；返回 null 交给占位封面。 */
    @Test
    fun dataAndBlobCoversAreRejected() {
        val html = """
            <z-masonry source="recommend">
              <a href="/book/data1/one.html">
                <z-cover title="Data"><img src="data:image/png;base64,AAAA"></z-cover>
              </a>
              <a href="/book/blob1/two.html">
                <z-cover title="Blob"><img src="blob:https://z-lib.by/1234"></z-cover>
              </a>
              <a href="/book/js1/three.html">
                <z-cover title="Js"><img src="javascript:alert(1)"></z-cover>
              </a>
            </z-masonry>
        """.trimIndent()

        val recs = ZlibDetailParser.parseRecommendations(html, baseUrl)

        assertEquals(3, recs.size)
        assertNull(recs[0].coverUrl)
        assertNull(recs[1].coverUrl)
        assertNull(recs[2].coverUrl)
    }

    /** 畸形 HTML（容器未闭合/锚未闭合）不能抛异常；结构不可信时按无推荐处理。 */
    @Test
    fun malformedMarkupReturnsEmptyInsteadOfThrowing() {
        val unclosedContainer = """
            <div class="books-mosaic">
              <z-masonry source="recommend">
                <a href="/book/aaa111/one.html"><z-cover title="One"><img src="/c.jpg"></a>
        """.trimIndent()

        assertTrue(ZlibDetailParser.parseRecommendations(unclosedContainer, baseUrl).isEmpty())
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

    /** P3：推荐封面绝对 http 升 https；baseUrl 非法时相对封面返回 null（不能拼 null 协议）。 */
    @Test
    fun recommendationCoverUpgradesHttpAndDropsInvalidBase() {
        val html = """
            <div class="books-mosaic">
              <z-masonry source="recommend">
                <a href="/book/aaa111/one.html">
                  <z-cover title="One"><img src="http://covers.example/abs.jpg"></z-cover>
                </a>
                <a href="/book/bbb222/two.html">
                  <z-cover title="Two"><img src="/img/two.jpg"></z-cover>
                </a>
              </z-masonry>
            </div>
        """.trimIndent()

        val withValidBase = ZlibDetailParser.parseRecommendations(
            html,
            "https://z-lib.by/book/abc/real.html",
        )
        assertEquals("https://covers.example/abs.jpg", withValidBase[0].coverUrl)
        assertEquals("https://z-lib.by/img/two.jpg", withValidBase[1].coverUrl)

        val withInvalidBase = ZlibDetailParser.parseRecommendations(html, "not a valid base url")
        assertEquals("绝对 http 封面不依赖 baseUrl", "https://covers.example/abs.jpg", withInvalidBase[0].coverUrl)
        assertNull("baseUrl 无 scheme/authority 时相对封面应放弃", withInvalidBase[1].coverUrl)
    }
}
