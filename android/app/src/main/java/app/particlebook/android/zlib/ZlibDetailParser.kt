package app.particlebook.android.zlib

import java.net.URI

/**
 * A12：详情页「You may be interested in」推荐卡。
 * [slug] 已去掉结尾 .html（可直接传给 ZlibClient.fetchDetail）；链接里没有 slug 时为 null。
 */
data class ZRecommendation(
    val hash: String,
    val slug: String?,
    val title: String,
    val author: String,
    val coverUrl: String?,
    /** 站点 book id（z-cover id 属性，封面缓存 key 用）；缺失时为空串。 */
    val bookId: String = "",
)

/**
 * 书籍详情页解析（P1b 1.3 节，实测 190,845B / 195,979B；站点无 JSON API，只能解析 HTML）。
 *
 * DOM：
 * - 主块「div.details-book cardBooks」带 data-book_id
 * - 标题「h1.book-title」；作者「i.authors > a」
 * - 封面「z-cover」内第一张 img（无封面 = /img/cover-not-exists.png）
 * - 下载锚「a.dlButton.addDownloadedBook href=/dl/...」；
 *   同一主块还有「a.dlButton.reader-link」指向 reader 子域，必须排除；hidden template 锚要剥掉
 * - 属性「div.bookProperty.property_language / property_file」内的 property_value
 * - A12 推荐区「div.books-mosaic > z-masonry[source=recommend]」内每张卡一个 a 锚 + z-cover
 */
object ZlibDetailParser {

    private val TEMPLATE_BLOCK = Regex("""<template[\s\S]*?</template>""", RegexOption.IGNORE_CASE)
    private val DETAILS_BOOK = Regex(
        """<div[^>]*class\s*=\s*["'][^"']*details-book[^"']*["'][^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val BOOK_ID = Regex("""data-book_id\s*=\s*["'](\d+)["']""", RegexOption.IGNORE_CASE)
    private val TITLE = Regex(
        """<h1[^>]*class\s*=\s*["'][^"']*book-title[^"']*["'][^>]*>([\s\S]*?)</h1>""",
        RegexOption.IGNORE_CASE,
    )
    private val AUTHORS_BLOCK = Regex(
        """<i[^>]*class\s*=\s*["'][^"']*\bauthors\b[^"']*["'][^>]*>([\s\S]*?)</i>""",
        RegexOption.IGNORE_CASE,
    )
    private val ANCHOR = Regex("""<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
    private val IMG_SRC = Regex("""<img\b[^>]*?\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("""([A-Za-z_][-A-Za-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val CANONICAL = Regex(
        """<link[^>]*rel\s*=\s*["']canonical["'][^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val READER_BOOK_PATH = Regex("""/book/([A-Za-z0-9]+)/([^/?'"\s]+)""", RegexOption.IGNORE_CASE)
    private val PROPERTY_VALUE = Regex(
        """class\s*=\s*["'][^"']*property_value[^"']*["'][^>]*>([\s\S]*?)</""",
        RegexOption.IGNORE_CASE,
    )
    private val Z_COVER = Regex("""<z-cover\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val NO_COVER_SUFFIX = "/img/cover-not-exists.png"

    /** A12：推荐区容器（真实页面 42 张卡，batch=21；无该容器 = 无推荐）。 */
    private val RECOMMEND_MASONRY = Regex(
        """<z-masonry\b[^>]*\bsource\s*=\s*["']recommend["'][^>]*>([\s\S]*?)</z-masonry>""",
        RegexOption.IGNORE_CASE,
    )
    private val BOOK_HREF = Regex("""/book/([A-Za-z0-9]+)(?:/([^/?#"'\s]+))?""", RegexOption.IGNORE_CASE)
    private val IMG_DATA_SRC = Regex("""<img\b[^>]*?\bdata-src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    /** P1c：简介块（实测在 div.details-book 内的静态 HTML，无 JS/papi；没有简介的书整个 div 不存在）。 */
    private val DESCRIPTION_BOX = Regex(
        """<div\b[^>]*\bid\s*=\s*["']bookDescriptionBox["'][^>]*>([\s\S]*?)</div>""",
        RegexOption.IGNORE_CASE,
    )
    private val BR_TAG = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val TAB_OR_SPACE = Regex("[ \\t\\u00A0]+")
    private val BLANK_LINES = Regex("\\n{3,}")

    /**
     * @param baseUrl 详情页 URL（把相对封面/路径转绝对）
     * @param hash 调用方已知的 book hash（可空；解析不出时从 reader 链接补）
     */
    @Throws(ZlibException::class)
    fun parse(html: String, baseUrl: String, hash: String? = null): ZBookDetail {
        // 先剥掉 template 模板（其中含 hidden 的 /dl/ 占位锚，不能当成真实下载路径）。
        val cleaned = TEMPLATE_BLOCK.replace(html, " ")
        val mainStart = DETAILS_BOOK.find(cleaned)?.range?.first ?: 0
        val main = cleaned.substring(mainStart)

        val title = TITLE.find(main)?.groupValues?.get(1)
            ?.let(ZlibHtml::clean)
            ?.takeIf { it.isNotEmpty() }
            ?: throw ZlibException("book title not found in detail page", ZlibException.Kind.PARSE)

        val authors = parseAuthors(main)
        val bookId = BOOK_ID.find(main)?.groupValues?.get(1)
            ?: BOOK_ID.find(cleaned)?.groupValues?.get(1)
            ?: ""
        val isbn = zCoverIsbn(main)
        val coverUrl = coverUrl(main, baseUrl)
        val downloadPath = downloadPath(main)
            ?: throw ZlibException("download link not found in detail page", ZlibException.Kind.PARSE)

        val fileParts = splitFileProperty(propertyValue(main, "file"))
        val anchorParts = splitFileProperty(downloadAnchorText(main))
        val language = propertyValue(main, "language")?.takeIf { it.isNotEmpty() }
        val year = propertyValue(main, "year")?.takeIf { it.isNotEmpty() }

        val reader = READER_BOOK_PATH.find(main)
        val derivedHash = reader?.groupValues?.get(1)
        val derivedSlug = reader?.groupValues?.get(2)
        val finalHash = hash?.takeIf { it.isNotBlank() } ?: derivedHash ?: ""
        val detailPath = canonicalPath(cleaned) ?: buildString {
            append("/book/")
            append(finalHash)
            if (!derivedSlug.isNullOrBlank()) append("/").append(derivedSlug)
        }

        return ZBookDetail(
            bookId = bookId,
            hash = finalHash,
            title = title,
            authors = authors,
            language = language,
            year = year,
            format = fileParts.first ?: anchorParts.first,
            fileSize = fileParts.second ?: anchorParts.second,
            isbn = isbn,
            coverUrl = coverUrl,
            detailPath = detailPath,
            downloadPath = downloadPath,
            description = description(main),
            recommendations = parseRecommendations(main, baseUrl),
        )
    }

    /**
     * A12：解析推荐区卡片。
     *
     * 结构：`div.books-mosaic > z-masonry[source=recommend] > a[href^=/book/] > z-cover[title,author] > img[src]`。
     * 规则：标题缺失/链接不可解析的卡跳过；同 hash 只保留首张；封面 cover-not-exists 视为无封面；
     * 无推荐容器时返回空列表（UI 整区不显示，不造占位）。
     */
    fun parseRecommendations(html: String, baseUrl: String): List<ZRecommendation> {
        val cleaned = TEMPLATE_BLOCK.replace(html, " ")
        val block = RECOMMEND_MASONRY.find(cleaned)?.groupValues?.get(1) ?: return emptyList()
        val result = ArrayList<ZRecommendation>()
        val seenHashes = HashSet<String>()
        for (match in ANCHOR.findAll(block)) {
            val href = parseAttrs(match.groupValues[1])["href"]?.trim().orEmpty()
            val path = BOOK_HREF.find(href) ?: continue
            val hash = path.groupValues[1]
            val card = match.groupValues[2]
            val coverAttrs = Z_COVER.find(card)?.groupValues?.get(1)
                ?.let(ZlibHtml::parseAttributes)
                .orEmpty()
            val title = coverAttrs["title"]?.trim().orEmpty()
            if (title.isEmpty()) continue
            if (!seenHashes.add(hash)) continue
            val rawCover = IMG_SRC.find(card)?.groupValues?.get(1)?.trim()
                ?: IMG_DATA_SRC.find(card)?.groupValues?.get(1)?.trim()
            val coverUrl = rawCover
                ?.takeUnless { it.endsWith(NO_COVER_SUFFIX, ignoreCase = true) }
                ?.let { absoluteUrl(baseUrl, it) }
            val slug = path.groupValues[2].removeSuffix(".html").trim().takeIf { it.isNotEmpty() }
            result += ZRecommendation(
                hash = hash,
                slug = slug,
                title = title,
                author = coverAttrs["author"].orEmpty().trim(),
                coverUrl = coverUrl,
                bookId = coverAttrs["id"].orEmpty().trim(),
            )
        }
        return result
    }

    /**
     * 解析简介：`<br>` → 换行，剥剩余标签，实体解码，按行 trim、去空行。
     * 块不存在 / 只有空白时返回 null（UI 整块不显示，不造占位文案）。
     */
    private fun description(main: String): String? {
        val raw = DESCRIPTION_BOX.find(main)?.groupValues?.get(1) ?: return null
        val withBreaks = BR_TAG.replace(raw, "\n")
        val decoded = ZlibHtml.decodeEntities(ZlibHtml.stripTags(withBreaks))
        val normalized = decoded
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .map { TAB_OR_SPACE.replace(it, " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        val collapsed = BLANK_LINES.replace(normalized, "\n\n").trim()
        return collapsed.takeIf { it.isNotEmpty() }
    }

    private fun parseAuthors(main: String): List<String> {
        val block = AUTHORS_BLOCK.find(main)?.groupValues?.get(1).orEmpty()
        if (block.isEmpty()) return emptyList()
        val fromAnchors = ANCHOR.findAll(block)
            .map { ZlibHtml.clean(it.groupValues[2]) }
            .filter { it.isNotEmpty() }
            .toList()
        if (fromAnchors.isNotEmpty()) return fromAnchors
        val text = ZlibHtml.clean(block)
        if (text.isEmpty()) return emptyList()
        return text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun zCoverIsbn(main: String): String? {
        val attrs = Z_COVER.find(main)?.groupValues?.get(1) ?: return null
        return parseAttrs(attrs)["isbn"]?.takeIf { it.isNotBlank() }
    }

    private fun coverUrl(main: String, baseUrl: String): String? {
        val src = IMG_SRC.find(main)?.groupValues?.get(1)?.trim() ?: return null
        if (src.endsWith(NO_COVER_SUFFIX, ignoreCase = true)) return null
        return absoluteUrl(baseUrl, src)
    }

    private fun downloadPath(main: String): String? {
        var fallback: String? = null
        for (match in ANCHOR.findAll(main)) {
            val attrs = parseAttrs(match.groupValues[1])
            val href = attrs["href"]?.trim().orEmpty()
            if (href.isEmpty() || href.startsWith("#") || href.startsWith("javascript:", ignoreCase = true)) continue
            val classes = attrs["class"].orEmpty().lowercase()
            if (classes.contains("reader-link")) continue
            if (href.contains("reader.")) continue
            if (href.contains("/login") || href.contains("/registration")) continue
            val isDownloadAnchor = classes.contains("dlbutton") && !classes.contains("reader")
            if (isDownloadAnchor && href.startsWith("/dl/")) return href
            if (fallback == null && href.startsWith("/dl/")) fallback = href
        }
        return fallback
    }

    /** 下载锚文本（如 pdf, 9.96 MB），property_file 缺失时兜底 format/fileSize。 */
    private fun downloadAnchorText(main: String): String? {
        for (match in ANCHOR.findAll(main)) {
            val attrs = parseAttrs(match.groupValues[1])
            val classes = attrs["class"].orEmpty().lowercase()
            val href = attrs["href"]?.trim().orEmpty()
            if (!classes.contains("dlbutton") || classes.contains("reader-link") || !href.startsWith("/dl/")) continue
            val text = ZlibHtml.clean(match.groupValues[2])
            if (text.isNotEmpty()) return text
        }
        return null
    }

    private fun splitFileProperty(value: String?): Pair<String?, String?> {
        val cleaned = value?.trim().orEmpty()
        if (cleaned.isEmpty()) return null to null
        val parts = cleaned.split(',', limit = 2).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null to null
        return parts.firstOrNull()?.uppercase() to parts.getOrNull(1)
    }

    /** 在 bookProperty.property_name 块后取 property_value 文本。 */
    private fun propertyValue(html: String, name: String): String? {
        val marker = Regex("property_" + Regex.escape(name) + """\b""", RegexOption.IGNORE_CASE)
        val match = marker.find(html) ?: return null
        val end = minOf(html.length, match.range.last + 1 + 1_200)
        val chunk = html.substring(match.range.last + 1, end)
        val value = PROPERTY_VALUE.find(chunk)?.groupValues?.get(1) ?: return null
        return ZlibHtml.clean(value).takeIf { it.isNotEmpty() }
    }

    private fun canonicalPath(html: String): String? {
        val tag = CANONICAL.find(html)?.value ?: return null
        val href = parseAttrs(tag.substringAfter('<'))["href"]?.trim().orEmpty()
        if (href.isEmpty()) return null
        return runCatching { URI(href) }.getOrNull()?.path?.takeIf { it.isNotBlank() }
    }

    private fun parseAttrs(raw: String): Map<String, String> {
        val result = HashMap<String, String>()
        ATTR.findAll(raw).forEach { match ->
            val key = match.groupValues[1].lowercase()
            val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
            result.putIfAbsent(key, value)
        }
        return result
    }

    /**
     * 把详情页里的封面地址转绝对 URL：按 URI.resolve 语义处理相对路径（相对的是详情页 URL，
     * 不是目录字符串），只接受 http/https；data:/blob: 等内联/本地 scheme 返回 null，交给占位封面。
     */
    private fun absoluteUrl(baseUrl: String, raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()
        if (lower.startsWith("data:") || lower.startsWith("blob:")) return null
        // 带 scheme 的绝对 URI：只放行 http(s)，javascript:/file:/intent: 等一律拒绝。
        val rawUri = runCatching { URI(trimmed) }.getOrNull()
        if (rawUri != null && rawUri.isAbsolute) {
            val httpCover = rawUri.scheme.equals("http", true) && !rawUri.host.isNullOrBlank()
            return when {
                // 绝对 http 封面升 https（CDN 均支持 TLS；避免明文封面被网络策略拦掉）。
                httpCover && lower.startsWith("http://") -> "https" + trimmed.substring(4)
                rawUri.scheme.equals("http", true) || rawUri.scheme.equals("https", true) ->
                    rawUri.toString()
                else -> null
            }
        }
        val baseUri = runCatching { URI(baseUrl) }.getOrNull()
        if (baseUri != null) {
            val resolved = runCatching { baseUri.resolve(trimmed) }.getOrNull()
            if (resolved != null) {
                return resolved.takeIf { it.scheme.equals("http", true) || it.scheme.equals("https", true) }
                    ?.toString()
            }
        }
        // baseUrl 非法时的保守兜底（正常详情页不会走到这里）：先校验 base 的 scheme/authority，
        // 否则会拼出 "null://null/..." 这种协议无效的封面 URL，宁可返回 null 交给占位封面。
        if (trimmed.startsWith("//")) {
            return runCatching { URI("https:" + trimmed) }.getOrNull()
                ?.takeIf { !it.host.isNullOrBlank() }
                ?.toString()
        }
        val origin = baseOrigin(baseUrl) ?: return null
        return if (trimmed.startsWith("/")) origin + trimmed else origin + "/" + trimmed
    }

    /** baseUrl 的 http(s) origin；scheme/authority 缺失（相对路径/垃圾串）时返回 null。 */
    private fun baseOrigin(baseUrl: String): String? {
        val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val authority = uri.authority?.takeIf { it.isNotBlank() } ?: return null
        return scheme + "://" + authority
    }
}
