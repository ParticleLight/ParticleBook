package app.particlebook.android.zlib

/**
 * 搜索页 HTML 解析（纯函数，无 Android 依赖，可 JVM 单测）。
 *
 * 结构实测：每页 51 个 `<z-bookcard ...>`；标题/作者在 `<div slot="title|author">`；
 * 分页信息在页面脚本的 `pagerOptions = { pagesTotal: N, pageCurrent: N, ... }`。
 * 见 docs/android-port/zlibrary-p1a-spec.md §1.5。
 */
object ZlibSearchParser {

    private val CARD_START = Regex("<z-bookcard\\b", RegexOption.IGNORE_CASE)
    private val HIDDEN_ATTR = Regex("\\shidden(?=[\\s>])", RegexOption.IGNORE_CASE)
    private val CARD_END = Regex("</z-bookcard\\s*>", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("([A-Za-z_][-A-Za-z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
    private val PAGES_TOTAL = Regex("pagesTotal\\s*:\\s*(\\d+)")
    private val ENTITY = Regex("&(#x[0-9A-Fa-f]+|#[0-9]+|[A-Za-z]{2,10});")
    private val IMG_TAG = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val IMG_SRC = Regex("(data-src|src)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)
    private val DOTALL = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)

    private val EMPTY_MARKERS = listOf(
        "no results",
        "no result",
        "no books",
        "nothing found",
        "nothing has been found",
        "not found",
        "class=\"notfound",
        "booklist-empty",
        "no-books",
        "没有找到",
        "未找到",
    )

    /**
     * 解析一页搜索结果。
     *
     * @throws ZlibException kind=PARSE 当页面既没有任何 `<z-bookcard>`、也没有空态标记时。
     */
    fun parse(html: String, query: String, page: Int): ZSearchPage {
        val books = parseCards(html)
        val totalPages = PAGES_TOTAL.find(html)?.groupValues?.get(1)?.toIntOrNull()
        if (books.isEmpty()) {
            if (totalPages == 0 || containsEmptyMarker(html)) {
                return ZSearchPage(query, page, totalPages ?: 0, emptyList())
            }
            throw ZlibException("no <z-bookcard> and no empty-state marker", ZlibException.Kind.PARSE)
        }
        return ZSearchPage(query, page, totalPages ?: page, books)
    }

    /** 提取所有结果卡片；结构异常的单条会被跳过而不是整页失败。 */
    fun parseCards(html: String): List<ZBook> {
        val books = ArrayList<ZBook>()
        var searchFrom = 0
        while (true) {
            val start = CARD_START.find(html, searchFrom) ?: break
            val tagEnd = html.indexOf('>', start.range.last + 1)
            if (tagEnd < 0) break
            val end = CARD_END.find(html, tagEnd + 1)
            val blockEnd = end?.range?.last?.plus(1) ?: html.length
            val block = html.substring(start.range.first, blockEnd)
            // 详情页/书单页尾部的隐藏模板卡：属性全是 JS 占位符
            // （id="${book['book']['id']}"、download="${book['book']['dl']}"），不是真实书籍。
            if (html.substring(start.range.first, tagEnd + 1).contains("\${")) {
                searchFrom = blockEnd
                continue
            }
            // 空结果页里有一个 <div class="book-item resItemBoxBooks" hidden aria-hidden="true"> 模板卡片
            // （counter 0、publisher=查询词），必须跳过，否则空结果会被当成 1 条结果。
            if (!isHiddenTemplateCard(html, start.range.first)) {
                val book = parseCard(block)
                if (book != null) books.add(book)
            }
            searchFrom = blockEnd
        }
        return books
    }

    /** 判断卡片是否位于隐藏模板容器（\`book-item\` 外层 div 带 hidden 属性）内。 */
    private fun isHiddenTemplateCard(html: String, cardStart: Int): Boolean {
        val wrapper = html.lastIndexOf("resItemBoxBooks", cardStart)
        if (wrapper < 0 || cardStart - wrapper > 2000) return false
        val divStart = html.lastIndexOf("<div", wrapper)
        if (divStart < 0) return false
        val tagEnd = html.indexOf('>', wrapper)
        if (tagEnd < 0) return false
        return HIDDEN_ATTR.containsMatchIn(html.substring(divStart, tagEnd))
    }

    private fun parseCard(block: String): ZBook? {
        val tagEnd = block.indexOf('>')
        if (tagEnd < 0) return null
        val attrs = parseAttributes(block.substring(0, tagEnd))
        val content = block.substring(tagEnd + 1)
        val id = attrs["id"].orEmpty()
        val title = slotText(content, "title")
        if (id.isEmpty() && title.isEmpty()) return null
        return ZBook(
            id = id,
            title = title,
            author = slotText(content, "author"),
            href = attrs["href"].orEmpty(),
            downloadPath = attrs["download"].orEmpty(),
            language = attrs["language"].orEmpty(),
            extension = attrs["extension"].orEmpty(),
            fileSize = attrs["filesize"].orEmpty(),
            year = attrs["year"].orEmpty(),
            publisher = attrs["publisher"].orEmpty(),
            rating = attrs["rating"].orEmpty(),
            isbn = attrs["isbn"].orEmpty(),
            coverUrl = parseCoverUrl(content),
        )
    }

    /**
     * 卡片内首个 img 的原始封面地址：优先 data-src，回退 src。
     * 空值、占位图 cover-not-exists、以及既非绝对 http(s) 也非站内相对路径的脏值一律返回空串。
     */
    private fun parseCoverUrl(content: String): String {
        val img = IMG_TAG.find(content) ?: return ""
        val attrs = HashMap<String, String>()
        for (match in IMG_SRC.findAll(img.value)) {
            val name = match.groupValues[1].lowercase()
            val raw = if (match.groupValues[2].isNotEmpty()) match.groupValues[2] else match.groupValues[3]
            attrs.putIfAbsent(name, decodeEntities(raw).trim())
        }
        val raw = attrs["data-src"]?.takeIf { it.isNotEmpty() } ?: attrs["src"].orEmpty()
        val value = raw.trim()
        if (value.isEmpty()) return ""
        val lower = value.lowercase()
        if (lower.contains("cover-not-exists")) return ""
        val looksLikeUrl = lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            value.startsWith("//") ||
            value.startsWith("/")
        return if (looksLikeUrl) value else ""
    }

    private fun parseAttributes(tagHead: String): Map<String, String> {
        val result = HashMap<String, String>()
        for (match in ATTR.findAll(tagHead)) {
            val name = match.groupValues[1].lowercase()
            val raw = if (match.groupValues[2].isNotEmpty()) match.groupValues[2] else match.groupValues[3]
            result[name] = decodeEntities(raw).trim()
        }
        return result
    }

    private fun slotText(content: String, slot: String): String {
        val regex = Regex(
            "<div[^>]*\\bslot\\s*=\\s*[\"']" + Regex.escape(slot) + "[\"'][^>]*>(.*?)</div>",
            DOTALL,
        )
        val match = regex.find(content) ?: return ""
        return decodeEntities(match.groupValues[1]).trim()
    }

    private fun containsEmptyMarker(html: String): Boolean {
        val lower = html.lowercase()
        return EMPTY_MARKERS.any { lower.contains(it) }
    }

    /** 解码常见 HTML 实体（命名 + 十进制/十六进制数字实体）。 */
    fun decodeEntities(raw: String): String {
        if (!raw.contains('&')) return raw
        return ENTITY.replace(raw) { match ->
            val entity = match.groupValues[1]
            when {
                entity.equals("amp", ignoreCase = true) -> "&"
                entity.equals("lt", ignoreCase = true) -> "<"
                entity.equals("gt", ignoreCase = true) -> ">"
                entity.equals("quot", ignoreCase = true) -> "\""
                entity.equals("apos", ignoreCase = true) -> "'"
                entity.startsWith("#x", ignoreCase = true) ->
                    entity.substring(2).toIntOrNull(16)?.toChar()?.toString() ?: match.value
                entity.startsWith("#") ->
                    entity.substring(1).toIntOrNull()?.toChar()?.toString() ?: match.value
                else -> match.value
            }
        }
    }
}
