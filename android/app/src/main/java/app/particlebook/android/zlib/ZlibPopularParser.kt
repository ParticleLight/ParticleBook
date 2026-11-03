package app.particlebook.android.zlib

/**
 * P1.5 榜单页 /popular 解析（纯函数）。
 *
 * 实测（spec §2.3）：`<z-masonry source="mostpopular">` 内每页 100 条：
 * `<a href="/book/<hash>/<slug>.html?dsource=mostpopular"><z-cover volume id … title><img src="…covers150/…"></z-cover></a>`。
 * 封面是 150px 缩略图，直接给 UI 的 2 列网格做原图。
 */
object ZlibPopularParser {

    private val ANCHOR = Regex("""<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
    private val HREF = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val Z_COVER = Regex("""<z-cover\b([^>]*)>([\s\S]*?)</z-cover>""", RegexOption.IGNORE_CASE)
    private val IMG_SRC = Regex("""<img[^>]+\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    /**
     * 解析一页榜单；空页（翻到底）返回空列表，客户端据此停止翻页。
     * 同一 href 只保留第一条（防站点重复插入）。
     */
    fun parsePage(html: String): List<ZPopularItem> {
        val items = LinkedHashMap<String, ZPopularItem>()
        for (anchor in ANCHOR.findAll(html)) {
            val href = HREF.find(anchor.groupValues[1])?.groupValues?.get(1)?.trim().orEmpty()
            if (!href.startsWith("/book/")) continue
            val cover = Z_COVER.find(anchor.groupValues[2]) ?: continue
            val attrs = ZlibHtml.parseAttributes(cover.groupValues[1])
            val title = attrs["title"].orEmpty()
            val id = attrs["volume"].orEmpty().ifEmpty { attrs["id"].orEmpty() }
            if (title.isEmpty() && id.isEmpty()) continue
            val cleanHref = href.substringBefore('?')
            val coverUrl = IMG_SRC.find(cover.groupValues[2])?.groupValues?.get(1).orEmpty().trim()
            val book = ZBook(
                id = id,
                title = title,
                author = attrs["author"].orEmpty(),
                href = cleanHref,
                downloadPath = "",
                language = "",
                extension = "",
                fileSize = "",
                year = "",
                publisher = "",
                rating = "",
                isbn = attrs["isbn"].orEmpty(),
            )
            items.putIfAbsent(cleanHref, ZPopularItem(book, coverUrl))
        }
        return items.values.toList()
    }
}
