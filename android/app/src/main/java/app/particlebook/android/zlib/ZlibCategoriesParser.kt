package app.particlebook.android.zlib

/**
 * P1.5 分类总览页 /categories 解析（纯函数）。
 *
 * 实测（spec §2.4）：39 组 `div.subcategories-container`，每组
 * `<h3 class="category-name" data-name="arts" data-id="1"><a href="/category/1">Arts</a></h3>`
 * + `<ul>` 内 802 个 `<li class="subcategory-name" data-name="architecture" data-type="non-fiction">
 * <a href="/category/40/Architecture">Architecture&nbsp;<span class="books-count">(23188)</span></a></li>`。
 */
object ZlibCategoriesParser {

    // 组标题要连内容一起抓：<h3 …><a href="/category/1">Arts</a></h3> 才能在下一组前取到组链。
    private val GROUP_HEAD = Regex("""<h3\b[^>]*class\s*=\s*["'][^"']*category-name[^"']*["'][^>]*>[\s\S]*?</h3>""", RegexOption.IGNORE_CASE)
    private val ANCHOR = Regex("""<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
    private val HREF = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val SUBCAT_HEAD = Regex("""<li\b[^>]*class\s*=\s*["'][^"']*subcategory-name[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE)
    private val BOOKS_COUNT = Regex("""\((\d+)\)""")
    private val CATEGORY_PATH = Regex("""/category/(\d+)(?:/([^/?#"']+))?""")
    private val NOT_FOUND = listOf("page not found", "class=\"notfound", "页面不存在", "没有找到")

    /**
     * 解析分类总览；返回空/非分类页时抛 PARSE。
     *
     * @throws ZlibException kind=PARSE
     */
    fun parse(html: String): List<ZCategoryGroup> {
        val heads = GROUP_HEAD.findAll(html).toList()
        if (heads.isEmpty()) {
            val lower = html.lowercase()
            if (NOT_FOUND.any { lower.contains(it) }) {
                throw ZlibException("categories page not found", ZlibException.Kind.PARSE)
            }
            throw ZlibException("not a categories page", ZlibException.Kind.PARSE)
        }
        val groups = ArrayList<ZCategoryGroup>(heads.size)
        heads.forEachIndexed { index, headMatch ->
            val headBlock = headMatch.value
            val headTag = headBlock.substring(0, headBlock.indexOf('>').coerceAtLeast(headBlock.length - 1) + 1)
            val headAttrs = ZlibHtml.parseAttributes(headTag)
            val sectionStart = headMatch.range.last + 1
            val sectionEnd = if (index + 1 < heads.size) heads[index + 1].range.first else html.length
            val section = html.substring(sectionStart, sectionEnd)
            val groupAnchor = ANCHOR.find(headBlock)
            val groupHref = groupAnchor?.groupValues?.get(1)?.let { HREF.find(it)?.groupValues?.get(1) }.orEmpty()
            val groupName = groupAnchor?.groupValues?.get(2)?.let(ZlibHtml::clean)
                ?.ifEmpty { headAttrs["data-name"].orEmpty() }
                ?: headAttrs["data-name"].orEmpty()
            val categories = ArrayList<ZCategory>()
            for (li in SUBCAT_HEAD.findAll(section)) {
                val liEnd = section.indexOf("</li>", li.range.last + 1).takeIf { it >= 0 } ?: section.length
                val block = section.substring(li.range.first, liEnd)
                val liAttrs = ZlibHtml.parseAttributes(li.value)
                val anchor = ANCHOR.find(block.substring(li.value.length)) ?: continue
                val href = HREF.find(anchor.groupValues[1])?.groupValues?.get(1).orEmpty().trim()
                val match = CATEGORY_PATH.find(href) ?: continue
                val id = match.groupValues[1]
                val slug = match.groupValues[2]
                val rawText = ZlibHtml.clean(anchor.groupValues[2])
                val booksCount = BOOKS_COUNT.find(rawText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val name = BOOKS_COUNT.replace(rawText, "").trim()
                categories.add(
                    ZCategory(
                        id = id,
                        name = name.ifEmpty { slug },
                        slug = slug,
                        path = href,
                        booksCount = booksCount,
                        type = liAttrs["data-type"].orEmpty(),
                    ),
                )
            }
            groups.add(
                ZCategoryGroup(
                    id = headAttrs["data-id"].orEmpty(),
                    name = groupName,
                    path = groupHref,
                    categories = categories,
                ),
            )
        }
        return groups
    }
}
