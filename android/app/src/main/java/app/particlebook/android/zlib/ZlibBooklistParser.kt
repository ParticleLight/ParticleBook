package app.particlebook.android.zlib

/**
 * P1.5 书单列表页 /booklists 解析（纯函数，可 JVM 单测）。
 *
 * 结构实测（docs/android-port/zlibrary-p15-spec.md §2.1）：
 * 每页 13–14 条 `<z-booklist id href topic author … notlogged short_description editorschoice>`，
 * 每条内含 16 个封面（列表只取前 3）；页尾 `div.page-load-more`（有 `display:none` 表示到底）。
 * 页面里还有一个 id 为模板占位符（${booklist.id}）的隐藏行，必须跳过。
 */
object ZlibBooklistParser {

    private val BLOCK_START = Regex("<z-booklist\\b", RegexOption.IGNORE_CASE)
    private val BLOCK_END = Regex("</z-booklist\\s*>", RegexOption.IGNORE_CASE)
    private val IMG_SRC = Regex("<img[^>]+?(?:data-)?src\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val LOAD_MORE = Regex("<div[^>]*\\bpage-load-more\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val HIDDEN_STYLE = Regex("display\\s*:\\s*none", RegexOption.IGNORE_CASE)
    private val NOT_FOUND = listOf("page not found", "class=\"notfound", "页面不存在", "没有找到")
    private val LIST_MARKERS = listOf("public booklists", "公开专栏")

    /**
     * 解析一页书单列表。
     *
     * @throws ZlibException kind=PARSE 当页面既没有书单、也不是合法空列表（软 404）。
     */
    fun parsePage(html: String): ZBooklistPage {
        val items = ArrayList<ZBooklist>()
        var searchFrom = 0
        while (true) {
            val start = BLOCK_START.find(html, searchFrom) ?: break
            val tagEnd = html.indexOf('>', start.range.last + 1)
            if (tagEnd < 0) break
            val end = BLOCK_END.find(html, tagEnd + 1)
            val blockEnd = end?.range?.last?.plus(1) ?: html.length
            parseBooklist(html.substring(start.range.first, blockEnd))?.let { items.add(it) }
            searchFrom = blockEnd
        }
        val hasLoadMore = LOAD_MORE.findAll(html).any { !HIDDEN_STYLE.containsMatchIn(it.value) }
        if (items.isEmpty()) {
            val lower = html.lowercase()
            if (NOT_FOUND.any { lower.contains(it) }) {
                throw ZlibException("booklists page not found", ZlibException.Kind.PARSE)
            }
            if (LIST_MARKERS.none { lower.contains(it) } && !hasLoadMore) {
                throw ZlibException("not a booklists page", ZlibException.Kind.PARSE)
            }
        }
        return ZBooklistPage(items, hasLoadMore)
    }

    /** 解析单条 `<z-booklist …>…</z-booklist>`；隐藏模板行返回 null。 */
    fun parseBooklist(block: String): ZBooklist? {
        val tagEnd = block.indexOf('>')
        if (tagEnd < 0) return null
        val head = block.substring(0, tagEnd)
        val attrs = ZlibHtml.parseAttributes(head)
        val id = attrs["id"].orEmpty().trim()
        // 页内隐藏模板行：id="${booklist.id}"（JS 模板字符串），不是真实书单。
        if (id.isEmpty() || id.contains("\${")) return null
        val topic = attrs["topic"].orEmpty().trim()
        if (topic.isEmpty() && attrs["href"].isNullOrBlank()) return null
        val covers = ArrayList<String>(3)
        for (img in IMG_SRC.findAll(block)) {
            val url = img.groupValues[1].trim()
            if (url.isEmpty() || covers.contains(url)) continue
            covers.add(url)
            if (covers.size >= 3) break
        }
        return ZBooklist(
            id = id,
            href = attrs["href"].orEmpty(),
            topic = topic,
            author = attrs["author"].orEmpty(),
            authorProfile = attrs["authorprofile"].orEmpty(),
            authorAvatar = attrs["authoravatar"].orEmpty(),
            quantity = attrs["quantity"]?.toIntOrNull() ?: 0,
            views = attrs["views"].orEmpty(),
            comments = attrs["comments"]?.toIntOrNull() ?: 0,
            description = ZlibHtml.clean(attrs["description"].orEmpty()),
            color = attrs["color"].orEmpty(),
            notLogged = ZlibHtml.hasBareAttribute(head, "notlogged"),
            shortDescription = ZlibHtml.hasBareAttribute(head, "short_description"),
            editorsChoice = ZlibHtml.hasBareAttribute(head, "editorschoice") ||
                ZlibHtml.hasBareAttribute(head, "editors_choice"),
            coverUrls = covers,
        )
    }
}
