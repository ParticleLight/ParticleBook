package app.particlebook.android.zlib

/**
 * P1.5 书单详情页 /booklist/<id>/<hash>/<slug>.html 解析（纯函数）。
 *
 * 实测（spec §2.2）：头部标题/作者/books_count/评论数；正文 `div.readlist-view` 内首屏 21 张
 * `<z-bookcard>`（带 download），页尾 `page-load-more` 可见且 books.size < booksCount 时 hasMore。
 */
object ZlibBooklistDetailParser {

    private val TITLE = Regex("""booklist-header__title-value"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
    private val ACCOUNT = Regex("""<z-account\b([^>]*)>([\s\S]*?)</z-account>""", RegexOption.IGNORE_CASE)
    private val BOOKS_COUNT = Regex("""books_count\b[^>]*>\s*\(?(\d+)\)?""", RegexOption.IGNORE_CASE)
    private val COMMENTS_TAB = Regex("""<tab[^>]*to="comments"[^>]*>([\s\S]*?)</tab>""", RegexOption.IGNORE_CASE)
    private val COUNT_IN_TEXT = Regex("""\((\d+)\)""")
    private val DESCRIPTION = Regex("""booklist-header__description"[^>]*>([\s\S]*?)</div>""", RegexOption.IGNORE_CASE)
    private val DETAIL_ID = Regex("""/booklist/(\d+)/(?:[^"'\s]*)""")
    private val SORT = Regex("""label[^>]*value\s*=\s*['"](date_savedA?)['"][^>]*checked""", RegexOption.IGNORE_CASE)
    private val LOAD_MORE = Regex("""<div[^>]*\bpage-load-more\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val HIDDEN_STYLE = Regex("""display\s*:\s*none""", RegexOption.IGNORE_CASE)
    private val NOT_FOUND = listOf("page not found", "class=\"notfound", "页面不存在", "没有找到")

    /** @throws ZlibException kind=PARSE 当既没有标题也没有书籍卡片（软 404 / 非书单页）。 */
    fun parse(html: String): ZBooklistDetail {
        val title = TITLE.find(html)?.groupValues?.get(1)?.let(ZlibHtml::clean).orEmpty()
        val books = ZlibSearchParser.parseCards(html)
        if (title.isEmpty() && books.isEmpty()) {
            val lower = html.lowercase()
            if (NOT_FOUND.any { lower.contains(it) }) {
                throw ZlibException("booklist page not found", ZlibException.Kind.PARSE)
            }
            throw ZlibException("not a booklist detail page", ZlibException.Kind.PARSE)
        }
        val account = ACCOUNT.find(html)
        val accountAttrs = account?.groupValues?.get(1)?.let(ZlibHtml::parseAttributes).orEmpty()
        val booksCount = BOOKS_COUNT.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: books.size
        val commentsText = COMMENTS_TAB.find(html)?.groupValues?.get(1)?.let(ZlibHtml::clean).orEmpty()
        val comments = COUNT_IN_TEXT.find(commentsText)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(\d+)""").find(commentsText)?.value?.toIntOrNull() ?: 0
        val hasLoadMoreTag = LOAD_MORE.findAll(html).any { !HIDDEN_STYLE.containsMatchIn(it.value) }
        return ZBooklistDetail(
            id = DETAIL_ID.find(html)?.groupValues?.get(1).orEmpty(),
            title = title,
            author = ZlibHtml.clean(account?.groupValues?.get(2).orEmpty()),
            authorProfile = accountAttrs["href"].orEmpty(),
            description = DESCRIPTION.find(html)?.groupValues?.get(1)?.let(ZlibHtml::clean).orEmpty(),
            booksCount = booksCount,
            commentsCount = comments,
            sort = SORT.find(html)?.groupValues?.get(1) ?: "date_saved",
            books = books,
            hasMore = hasLoadMoreTag && books.size < booksCount,
        )
    }
}
