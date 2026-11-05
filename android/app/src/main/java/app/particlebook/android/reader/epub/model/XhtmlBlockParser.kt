package app.particlebook.android.reader.epub.model

import app.particlebook.android.reader.epub.EpubBlock
import app.particlebook.android.reader.epub.EpubChapter
import app.particlebook.android.reader.epub.EpubResourceResolver
import app.particlebook.android.reader.epub.EpubStyle
import app.particlebook.android.reader.epub.css.CssElement
import app.particlebook.android.reader.epub.css.CssParser
import app.particlebook.android.reader.epub.css.CssStylesheet

/**
 * XHTML -> EpubBlock 的最小稳定入口。
 *
 * 纯 JVM：只依赖 Kotlin/Java 标准库，不引用 Android framework。
 * 调用方（EpubParser）负责 ZIP/OPF/spine 与外部 CSS 读取；本类只负责把一章 XHTML
 * 映射成按文档顺序排列的 block，并把图片路径归一化为 EPUB 根相对路径。
 */
object XhtmlBlockParser {

    /**
     * @param xhtml 章节 XHTML 文本（可以不是合法 XML）。
     * @param chapterHref ZIP 根内章节路径，如 OEBPS/Text/ch1.xhtml。
     * @param stylesheet 已按顺序拼接好的外部 CSS；章节内 style/link 会追加在后面。
     * @param resolver 可选：用于读取章节内 link rel=stylesheet；传 null 时忽略外链。
     * @param options 标题/图片等解析开关。
     */
    fun parse(
        xhtml: String,
        chapterHref: String,
        stylesheet: CssStylesheet = CssStylesheet.EMPTY,
        resolver: EpubResourceResolver? = null,
        options: XhtmlParseOptions = XhtmlParseOptions(),
    ): EpubChapter {
        val root = HtmlTreeBuilder().build(HtmlTokenizer(xhtml).tokenize())
        val documentCss = collectDocumentCss(root, chapterHref, resolver)
        val css = stylesheet.mergedWith(documentCss)
        val body = findFirst(root, "body") ?: root
        val bodyElement = cssElementOf(body)
        val bodyStyle = css.styleFor(bodyElement, EpubStyle())
        val builder = BlockBuilder(css, chapterHref, options)
        if (!bodyStyle.hidden) builder.walkContainer(body, bodyStyle)
        val documentTitle = findFirst(root, "title")?.let { collectRawText(it) }?.trim()
            ?.takeIf { it.isNotEmpty() }
        return EpubChapter(
            href = chapterHref,
            title = builder.title ?: options.fallbackTitle ?: documentTitle,
            blocks = builder.blocks,
        )
    }

    private fun collectDocumentCss(
        root: HtmlElement,
        chapterHref: String,
        resolver: EpubResourceResolver?,
    ): CssStylesheet {
        val styles = ArrayList<CssStylesheet>()
        fun visit(node: HtmlNode) {
            if (node !is HtmlElement) return
            when (node.tag) {
                "style" -> {
                    val raw = collectRawText(node)
                    if (raw.isNotBlank()) styles += CssParser.parse(raw)
                }

                "link" -> {
                    if (resolver == null) return
                    val rel = node.attr("rel")?.lowercase().orEmpty()
                    if (rel.split(Regex("\\s+")).none { it == "stylesheet" }) return
                    val href = node.attr("href")?.trim().orEmpty()
                    if (href.isEmpty()) return
                    val path = EpubPathResolver.resolve(chapterHref, href)
                    val bytes = try {
                        resolver.read(path)
                    } catch (_: Exception) {
                        null
                    } ?: return
                    val text = try {
                        String(bytes, Charsets.UTF_8)
                    } catch (_: Exception) {
                        return
                    }
                    if (text.isNotBlank()) styles += CssParser.parse(text)
                }
            }
            for (child in node.children) visit(child)
        }
        visit(root)
        var merged = CssStylesheet.EMPTY
        for (style in styles) merged = merged.mergedWith(style)
        return merged
    }

    private fun findFirst(root: HtmlElement, tag: String): HtmlElement? {
        if (root.tag == tag) return root
        for (child in root.children) {
            if (child is HtmlElement) {
                val found = findFirst(child, tag)
                if (found != null) return found
            }
        }
        return null
    }

    private fun collectRawText(element: HtmlElement): String {
        val out = StringBuilder()
        fun visit(node: HtmlNode) {
            when (node) {
                is HtmlText -> out.append(node.text)
                is HtmlElement -> for (child in node.children) visit(child)
            }
        }
        for (child in element.children) visit(child)
        return out.toString()
    }
}

private fun cssElementOf(element: HtmlElement): CssElement = CssElement(
    tagName = element.tag.lowercase(),
    id = element.attr("id"),
    classes = element.attr("class")
        ?.split(Regex("\\s+"))
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        .orEmpty(),
    inlineStyle = CssParser.parseInlineStyle(element.attr("style")),
)

data class XhtmlParseOptions(
    val fallbackTitle: String? = null,
    val resolveImagePaths: Boolean = true,
    val includeAltText: Boolean = true,
)

private class BlockBuilder(
    private val css: CssStylesheet,
    private val chapterHref: String,
    private val options: XhtmlParseOptions,
) {
    val blocks = ArrayList<EpubBlock>()
    var title: String? = null

    fun walkContainer(container: HtmlElement, inherited: EpubStyle) {
        val pendingText = StringBuilder()
        fun flushPending() {
            val text = normalizeText(pendingText.toString())
            pendingText.setLength(0)
            if (text.isNotBlank()) blocks += EpubBlock.Paragraph(text, inherited)
        }
        for (child in container.children) {
            when (child) {
                is HtmlText -> pendingText.append(child.text)
                is HtmlElement -> {
                    if (isBlockElement(child.tag)) {
                        flushPending()
                        handleBlock(child, inherited)
                    } else {
                        collectInlineText(child, inherited, pendingText)
                    }
                }
            }
        }
        flushPending()
    }

    private fun handleBlock(element: HtmlElement, inherited: EpubStyle) {
        val style = css.styleFor(cssElementOf(element), inherited)
        if (style.hidden) return
        val tag = element.tag.lowercase()
        val pageBreak = style.pageBreakBefore || isExplicitPageBreak(element)
        if (pageBreak) blocks += EpubBlock.PageBreak
        when (tag) {
            "p" -> emitInlineParagraphs(element, style)
            "h1", "h2", "h3", "h4", "h5", "h6" -> emitHeading(element, style, tag[1].digitToInt())
            "img" -> emitImage(element, style)
            "hr" -> if (!pageBreak) blocks += EpubBlock.HorizontalRule
            "ul" -> emitList(element, style, ordered = false)
            "ol" -> emitList(element, style, ordered = true)
            "li" -> emitListItem(element, style, ordered = false, index = 1)
            "table" -> emitTable(element, style)
            "pre" -> emitPre(element, style)
            else -> {
                if (tag == "br") return
                if (tag == "script" || tag == "style") return
                walkContainer(element, style)
            }
        }
    }

    private fun emitInlineParagraphs(element: HtmlElement, style: EpubStyle) {
        val buffer = StringBuilder()
        fun flush() {
            val text = normalizeText(buffer.toString())
            buffer.setLength(0)
            if (text.isNotBlank()) blocks += EpubBlock.Paragraph(text, style)
        }
        fun visit(node: HtmlNode, parentStyle: EpubStyle) {
            when (node) {
                is HtmlText -> buffer.append(node.text)
                is HtmlElement -> {
                    val tag = node.tag.lowercase()
                    if (tag == "script" || tag == "style") return
                    if (tag == "br") {
                        buffer.append('\n')
                        return
                    }
                    val childStyle = css.styleFor(cssElementOf(node), parentStyle)
                    if (childStyle.hidden) return
                    if (tag == "img") {
                        flush()
                        emitImage(node, childStyle)
                        return
                    }
                    for (child in node.children) visit(child, childStyle)
                }
            }
        }
        for (child in element.children) visit(child, style)
        flush()
    }

    private fun emitHeading(element: HtmlElement, style: EpubStyle, level: Int) {
        val text = collectText(element, style)
        if (text.isBlank()) return
        if (title == null) title = text
        blocks += EpubBlock.Heading(level, text, style)
        emitImages(element, style)
    }

    private fun emitPre(element: HtmlElement, style: EpubStyle) {
        val text = collectText(element, style, preserveWhitespace = true).trim('\n', '\r')
        if (text.isNotBlank()) blocks += EpubBlock.Paragraph(text, style)
    }

    private fun emitList(element: HtmlElement, style: EpubStyle, ordered: Boolean) {
        var index = 1
        for (child in element.children) {
            if (child !is HtmlElement) continue
            if (child.tag == "li") {
                emitListItem(child, style, ordered, index)
                index++
            } else if (isBlockElement(child.tag)) {
                handleBlock(child, style)
            } else {
                val text = collectText(child, style)
                if (text.isNotBlank()) blocks += EpubBlock.ListItem(ordered, index, text, style)
            }
        }
    }

    private fun emitListItem(
        element: HtmlElement,
        inherited: EpubStyle,
        ordered: Boolean,
        index: Int,
    ) {
        val style = css.styleFor(cssElementOf(element), inherited)
        if (style.hidden) return
        val pageBreak = style.pageBreakBefore || isExplicitPageBreak(element)
        if (pageBreak) blocks += EpubBlock.PageBreak
        val text = collectText(element, style, excludedTags = setOf("ul", "ol"))
        if (text.isNotBlank()) blocks += EpubBlock.ListItem(ordered, index, text, style)
        emitImages(element, style, skipNestedLists = true)
        for (child in element.children) {
            if (child is HtmlElement && (child.tag == "ul" || child.tag == "ol")) {
                emitList(child, style, child.tag == "ol")
            }
        }
    }

    private fun emitTable(element: HtmlElement, style: EpubStyle) {
        val rows = ArrayList<List<String>>()
        fun collectRows(node: HtmlElement) {
            for (child in node.children) {
                if (child !is HtmlElement) continue
                when (child.tag) {
                    "tr" -> {
                        val row = ArrayList<String>()
                        for (cell in child.children) {
                            if (cell !is HtmlElement) continue
                            if (cell.tag == "td" || cell.tag == "th") {
                                val cellStyle = css.styleFor(cssElementOf(cell), style)
                                if (cellStyle.hidden) {
                                    row += ""
                                } else {
                                    row += collectText(cell, cellStyle)
                                }
                            }
                        }
                        if (row.isNotEmpty()) rows += row
                    }

                    "thead", "tbody", "tfoot" -> collectRows(child)
                }
            }
        }
        collectRows(element)
        if (rows.isNotEmpty()) {
            blocks += EpubBlock.Table(rows, style)
        } else {
            val text = collectText(element, style)
            if (text.isNotBlank()) blocks += EpubBlock.Paragraph(text, style)
        }
    }

    private fun emitImages(
        element: HtmlElement,
        parentStyle: EpubStyle,
        skipNestedLists: Boolean = false,
    ) {
        fun visit(node: HtmlNode, style: EpubStyle) {
            if (node !is HtmlElement) return
            if (skipNestedLists && (node.tag == "ul" || node.tag == "ol")) return
            val childStyle = css.styleFor(cssElementOf(node), style)
            if (childStyle.hidden) return
            if (node.tag == "img") {
                emitImage(node, childStyle)
                return
            }
            for (child in node.children) visit(child, childStyle)
        }
        for (child in element.children) visit(child, parentStyle)
    }

    private fun emitImage(element: HtmlElement, style: EpubStyle) {
        val raw = element.attr("src")?.trim().orEmpty()
        if (raw.isEmpty()) return
        val src = if (options.resolveImagePaths) {
            EpubPathResolver.resolve(chapterHref, raw)
        } else {
            raw
        }
        val alt = element.attr("alt")
            ?.let { normalizeText(it) }
            ?.takeIf { options.includeAltText && it.isNotBlank() }
        val width = parseDimension(element.attr("width"))
        val height = parseDimension(element.attr("height"))
        blocks += EpubBlock.Image(src, alt, width, height, style)
    }

    private fun collectText(
        element: HtmlElement,
        parentStyle: EpubStyle,
        preserveWhitespace: Boolean = false,
        excludedTags: Set<String> = emptySet(),
    ): String {
        val out = StringBuilder()
        fun visit(node: HtmlNode, style: EpubStyle) {
            when (node) {
                is HtmlText -> out.append(node.text)
                is HtmlElement -> {
                    val tag = node.tag.lowercase()
                    if (tag in excludedTags) return
                    if (tag == "script" || tag == "style") return
                    val childStyle = css.styleFor(cssElementOf(node), style)
                    if (childStyle.hidden) return
                    if (tag == "br") {
                        out.append('\n')
                        return
                    }
                    if (tag == "img") return
                    for (child in node.children) visit(child, childStyle)
                }
            }
        }
        for (child in element.children) visit(child, parentStyle)
        return normalizeText(out.toString(), preserveWhitespace)
    }

    private fun collectInlineText(
        element: HtmlElement,
        parentStyle: EpubStyle,
        out: StringBuilder,
    ) {
        val style = css.styleFor(cssElementOf(element), parentStyle)
        if (style.hidden) return
        when (element.tag) {
            "script", "style" -> return
            "br" -> {
                out.append('\n')
                return
            }

            "img" -> {
                emitImage(element, style)
                return
            }
        }
        for (child in element.children) {
            when (child) {
                is HtmlText -> out.append(child.text)
                is HtmlElement -> collectInlineText(child, style, out)
            }
        }
    }

    private fun isExplicitPageBreak(element: HtmlElement): Boolean {
        val epubType = element.attr("epub:type")?.lowercase()
        if (epubType == "pagebreak") return true
        if (element.attr("role")?.lowercase() == "doc-pagebreak") return true
        val classes = element.attr("class")?.lowercase()?.split(Regex("\\s+")).orEmpty()
        return classes.any { it == "pagebreak" || it == "page-break" }
    }

    private fun isBlockElement(tag: String): Boolean =
        tag in HtmlTags.BLOCK || tag == "img" || tag == "hr"


    private fun normalizeText(text: String, preserveWhitespace: Boolean = false): String {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        if (preserveWhitespace) return normalized
        var value = normalized.replace(Regex("[ \\t\\f\\u000B]+"), " ")
        value = value.replace(Regex(" *\\n *"), "\n")
        value = value.replace(Regex("\\n{2,}"), "\n")
        return value.trim()
    }

    private fun parseDimension(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val match = Regex("^\\s*(\\d+)").find(raw) ?: return null
        return match.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
    }
}
