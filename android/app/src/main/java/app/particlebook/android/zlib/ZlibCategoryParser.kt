package app.particlebook.android.zlib

/**
 * P1c ④：分类 / 榜单 / 作者页解析（纯函数，可 JVM 单测）。
 *
 * 实测两种结构（docs/android-port/zlibrary-p1c-research.md §3）：
 * - 搜索页 / 作者页（以及未来的分类列表页）：`<div class="book-item resItemBoxBooks">` 内是
 *   `<z-bookcard ...>`，与 [ZlibSearchParser] 完全同构（属性里已带 `download="/dl/<hash>"`），直接复用。
 * - 榜单 `/popular`：`<z-masonry source="mostpopular">` 内 100 个 `<a href="/book/...">` + `<z-cover volume id author title>`；
 *   没有 `download` 属性（需再进详情页），也没有分页。
 *
 * 分类页 `/collections/<slug>` 的正确 slug 尚未确认（本次实测 404），解析器按结构识别，不写死 URL。
 */
object ZlibCategoryParser {

    private val ANCHOR = Regex("""<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
    private val Z_COVER = Regex("""<z-cover\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("""([A-Za-z_][-A-Za-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    private val NOT_FOUND_MARKERS = listOf(
        "<title>page not found",
        "page not found - z-library",
        "class=\"notfound",
        "没有找到",
    )

    /**
     * 把一页分类/榜单/作者页解析为与搜索同构的 [ZBook] 列表。
     *
     * @param path 请求的站内路径（仅用于错误信息）
     * @throws ZlibException kind=PARSE 当页面既没有卡片、也没有榜单结构、且明确是 404 页时
     */
    @Throws(ZlibException::class)
    fun parse(html: String, path: String = ""): List<ZBook> {
        val cards = ZlibSearchParser.parseCards(html)
        if (cards.isNotEmpty()) return cards
        val popular = parseMasonry(html)
        if (popular.isNotEmpty()) return popular
        if (looksLikeNotFound(html)) {
            throw ZlibException("category page not found: " + path, ZlibException.Kind.PARSE)
        }
        return emptyList()
    }

    /** /popular：每个 a[href=/book/...] 内的 z-cover 是一个条目（无 download，需进详情页）。 */
    private fun parseMasonry(html: String): List<ZBook> {
        val books = ArrayList<ZBook>()
        for (anchor in ANCHOR.findAll(html)) {
            val attrs = parseAttrs(anchor.groupValues[1])
            val href = attrs["href"]?.trim().orEmpty()
            if (!href.startsWith("/book/")) continue
            val coverHead = Z_COVER.find(anchor.groupValues[2])?.groupValues?.get(1) ?: continue
            val cover = parseAttrs(coverHead)
            val title = decode(cover["title"]).orEmpty()
            val id = decode(cover["volume"]).orEmpty().ifEmpty { decode(cover["id"]).orEmpty() }
            if (title.isEmpty() && id.isEmpty()) continue
            books.add(
                ZBook(
                    id = id,
                    title = title,
                    author = decode(cover["author"]).orEmpty(),
                    href = normalizeHref(href),
                    downloadPath = "",
                    language = "",
                    extension = "",
                    fileSize = "",
                    year = "",
                    publisher = "",
                    rating = "",
                    isbn = decode(cover["isbn"]).orEmpty(),
                ),
            )
        }
        return books
    }

    private fun looksLikeNotFound(html: String): Boolean {
        val lower = html.lowercase()
        return NOT_FOUND_MARKERS.any { lower.contains(it) }
    }

    /** 去掉 query / fragment（榜单链接带 ?dsource=mostpopular），保留 URL 编码原样。 */
    private fun normalizeHref(href: String): String = href.substringBefore('?').substringBefore('#')

    private fun decode(raw: String?): String? = raw?.let { ZlibSearchParser.decodeEntities(it).trim() }

    private fun parseAttrs(tagHead: String): Map<String, String> {
        val result = HashMap<String, String>()
        for (match in ATTR.findAll(tagHead)) {
            val name = match.groupValues[1].lowercase()
            val value = if (match.groupValues[2].isNotEmpty()) match.groupValues[2] else match.groupValues[3]
            result.putIfAbsent(name, value)
        }
        return result
    }
}
