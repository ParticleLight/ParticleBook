package app.particlebook.android.reader.fb2

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** 正文段落：headingLevel = 0 是普通段落，1..3 是章节标题（深度封顶 3）。 */
internal data class Fb2Paragraph(val text: String, val headingLevel: Int = 0)

/** 解析结果：书名、作者（可能缺失）与按文档顺序铺平的正文段落。 */
internal data class Fb2Document(val title: String, val author: String?, val paragraphs: List<Fb2Paragraph>)

/** FB2 解析失败；message 统一以 "FB2_PARSE: " 开头并带上原始原因。 */
internal class Fb2ParseException(message: String) : Exception(message)

/**
 * FictionBook 2.0 → [Fb2Document] 的最小投影。
 *
 * - 书名 / 作者只从 `description/title-info` 取；
 * - 正文只读第一个不带 `name` 属性的 `body`，按文档顺序展开；
 * - `section` 可任意嵌套，层级从 1 起、封顶 3，`section/title` 每个 section 只产出一行标题段落；
 * - `p` / `v` / `subtitle` 产出普通段落（`v` 是一条诗句）；
 * - `empty-line` / `annotation` / `image` / `binary` / `description` / `stylesheet` 不产出段落。
 *
 * 解析是 namespace-aware 的，只比较 localName，所以带 / 不带 FB2 命名空间、带前缀的文档都能读。
 * 安全：禁 DOCTYPE 与外部实体——FB2 是外部文件，解析器不碰网络也不读本地文件。
 */
internal object Fb2Parser {
    private const val MESSAGE_PREFIX = "FB2_PARSE: "
    private const val ROOT_ELEMENT = "FictionBook"
    private const val MAX_HEADING_LEVEL = 3

    /** section/title 里多个 p 之间的连接符：U+00B7 两侧各一个空格。 */
    private const val TITLE_LINE_SEPARATOR = " \u00B7 "

    private const val AUTHOR_FIELD_SEPARATOR = " "
    private val AUTHOR_FIELDS = listOf("first-name", "middle-name", "last-name")
    private val WHITESPACE = Regex("\\s+")

    /** 这些元素（含其全部子孙）不产出段落。 */
    private val SILENT_ELEMENTS = setOf(
        "empty-line",
        "annotation",
        "image",
        "binary",
        "stylesheet",
        "description"
    )

    /** p / v / subtitle 都是"一行文本"，不再向下递归。 */
    private val LEAF_TEXT_ELEMENTS = setOf("p", "v", "subtitle")

    private val PREDEFINED_ENTITIES = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'"
    )

    fun parse(xml: String): Fb2Document {
        if (xml.isBlank()) throw Fb2ParseException(MESSAGE_PREFIX + "empty document")
        val document = parseDom(xml)
        val root = document.documentElement
            ?: throw Fb2ParseException(MESSAGE_PREFIX + "document has no root element")
        val rootName = nameOf(root)
        if (rootName != ROOT_ELEMENT) {
            throw Fb2ParseException(
                MESSAGE_PREFIX + "root element is <" + rootName + ">, expected <" + ROOT_ELEMENT + ">"
            )
        }
        val titleInfo = firstChild(firstChild(root, "description"), "title-info")
        val title = firstChild(titleInfo, "book-title")?.let { collapsedText(it) } ?: ""
        val author = firstChild(titleInfo, "author")?.let { authorName(it) }
        return Fb2Document(title, author, bodyParagraphs(root))
    }

    // ---------------------------------------------------------------- 元数据

    /** first-name / middle-name / last-name，跳过空段、单空格连接；全空返回 null。 */
    private fun authorName(author: Element): String? = AUTHOR_FIELDS
        .mapNotNull { field ->
            firstChild(author, field)?.let { collapsedText(it) }?.takeIf { it.isNotEmpty() }
        }
        .joinToString(AUTHOR_FIELD_SEPARATOR)
        .ifEmpty { null }

    // ------------------------------------------------------------------ 正文

    private fun bodyParagraphs(root: Element): List<Fb2Paragraph> {
        val body = childElements(root)
            .firstOrNull { nameOf(it) == "body" && !hasNameAttribute(it) }
            ?: return emptyList()
        val out = ArrayList<Fb2Paragraph>()
        for (child in childElements(body)) {
            when (nameOf(child)) {
                // body 直接挂的 title 是书名页 / 卷名，按一级标题输出，不能混进正文。
                "title" -> appendTitleLine(child, 1, out)
                "section" -> appendSection(child, 1, out)
                else -> appendFlow(child, out)
            }
        }
        return out
    }

    /** section：先出自己的一行标题，再按文档顺序展开子节点（嵌套 section 递归、层级 +1）。 */
    private fun appendSection(section: Element, depth: Int, out: MutableList<Fb2Paragraph>) {
        val level = minOf(depth, MAX_HEADING_LEVEL)
        firstChild(section, "title")?.let { title -> appendTitleLine(title, level, out) }
        for (child in childElements(section)) {
            when (nameOf(child)) {
                // section 的 title 已经合并成上面那一行，不能再把它的 p 当正文。
                "title" -> Unit
                "section" -> appendSection(child, depth + 1, out)
                else -> appendFlow(child, out)
            }
        }
    }

    /** title 里的多个 p 合并成一行标题（U+00B7 分隔），空串丢弃；level 由调用方按层级给。 */
    private fun appendTitleLine(title: Element, level: Int, out: MutableList<Fb2Paragraph>) {
        val line = childElements(title)
            .filter { nameOf(it) == "p" }
            .mapNotNull { collapsedText(it).takeIf { text -> text.isNotEmpty() } }
            .joinToString(TITLE_LINE_SEPARATOR)
        if (line.isNotEmpty()) out += Fb2Paragraph(line, level)
    }

    /** 正文流：epigraph / poem / stanza / cite / table 等容器逐层下钻，遇到 p / v / subtitle 收一行。 */
    private fun appendFlow(element: Element, out: MutableList<Fb2Paragraph>) {
        val name = nameOf(element)
        if (name in SILENT_ELEMENTS) return
        if (name in LEAF_TEXT_ELEMENTS) {
            val text = collapsedText(element)
            if (text.isNotEmpty()) out += Fb2Paragraph(text, 0)
            return
        }
        for (child in childElements(element)) appendFlow(child, out)
    }

    // -------------------------------------------------------------- DOM 工具

    private fun parseDom(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isValidating = false
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        harden(factory)
        val builder = try {
            factory.newDocumentBuilder()
        } catch (e: Exception) {
            throw Fb2ParseException(MESSAGE_PREFIX + reasonOf(e))
        }
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        builder.setErrorHandler(QuietErrorHandler)
        return try {
            builder.parse(InputSource(StringReader(xml)))
        } catch (e: Exception) {
            throw Fb2ParseException(MESSAGE_PREFIX + reasonOf(e))
        }
    }

    /** 关掉 DOCTYPE 与一切外部实体；个别实现不支持某个 feature 时尽量降级而不是直接失败。 */
    private fun harden(factory: DocumentBuilderFactory) {
        val features = listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false
        )
        for ((name, value) in features) {
            runCatching { factory.setFeature(name, value) }
        }
    }

    private fun reasonOf(e: Exception): String =
        e.message?.takeIf { it.isNotBlank() } ?: e.toString()

    private fun nameOf(node: Node): String = node.localName ?: node.nodeName.substringAfterLast(':')

    private fun childElements(parent: Element): List<Element> {
        val out = ArrayList<Element>()
        var child = parent.firstChild
        while (child != null) {
            if (child is Element) out += child
            child = child.nextSibling
        }
        return out
    }

    private fun firstChild(parent: Element?, name: String): Element? {
        if (parent == null) return null
        return childElements(parent).firstOrNull { nameOf(it) == name }
    }

    /** 只认不带前缀的 name 属性（body / section 的 name 都属于无前缀属性）。 */
    private fun hasNameAttribute(element: Element): Boolean {
        if (element.hasAttribute("name")) return true
        val attributes = element.attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            val name = attribute.localName ?: attribute.nodeName.substringAfterLast(':')
            if (name == "name") return true
        }
        return false
    }

    // ---------------------------------------------------------------- 文本

    /** 全部后代文本节点拼接 → 连续空白折叠成单个空格 → trim。 */
    private fun collapsedText(element: Element): String = WHITESPACE.replace(rawText(element), " ").trim()

    private fun rawText(node: Node): String {
        val sink = StringBuilder()
        appendText(node, sink)
        return sink.toString()
    }

    private fun appendText(node: Node, sink: StringBuilder) {
        when (node.nodeType) {
            Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> sink.append(node.nodeValue ?: "")
            Node.ENTITY_REFERENCE_NODE -> {
                // expandEntityReferences 关掉后，预定义 / 内部实体可能以引用节点出现。
                val first = node.firstChild
                if (first == null) {
                    sink.append(PREDEFINED_ENTITIES[node.nodeName] ?: "")
                } else {
                    var child: Node? = first
                    while (child != null) {
                        appendText(child, sink)
                        child = child.nextSibling
                    }
                }
            }
            else -> {
                var child = node.firstChild
                while (child != null) {
                    appendText(child, sink)
                    child = child.nextSibling
                }
            }
        }
    }

    /** 默认 error / fatalError 会抛异常，这里只吞掉 warning，避免污染测试输出。 */
    private object QuietErrorHandler : DefaultHandler() {
        override fun warning(exception: SAXParseException) = Unit
    }
}
