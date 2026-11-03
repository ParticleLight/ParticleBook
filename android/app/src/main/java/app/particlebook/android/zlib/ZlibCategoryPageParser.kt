package app.particlebook.android.zlib

/**
 * P1.5 分类书籍列表页 /category/<id>/<Slug>?page=N 解析（薄包装）。
 *
 * 结构与搜索结果页同构（spec §2.5）：50 张 `<z-bookcard>`/页 + `pagerOptions.pagesTotal`。
 * 软 404 返回 200 但标题是 Page not found → 抛 PARSE，UI 提示「该分类不存在或已变更」。
 */
object ZlibCategoryPageParser {

    private val PAGES_TOTAL = Regex("""pagesTotal\s*:\s*(\d+)""")
    private val NOT_FOUND = listOf("page not found", "class=\"notfound", "页面不存在", "没有找到")

    /** @throws ZlibException kind=PARSE 软 404。 */
    fun parse(html: String, page: Int): ZCategoryPage {
        val lower = html.lowercase()
        val books = ZlibSearchParser.parseCards(html)
        if (books.isEmpty() && NOT_FOUND.any { lower.contains(it) }) {
            throw ZlibException("category page not found", ZlibException.Kind.PARSE)
        }
        val totalPages = PAGES_TOTAL.find(html)?.groupValues?.get(1)?.toIntOrNull()
            ?: if (books.isNotEmpty()) page else 0
        return ZCategoryPage(page, totalPages, books)
    }
}
