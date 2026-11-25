package app.particlebook.android.reader.fb2

import app.particlebook.android.reader.text.TextImage
import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 正文段落：headingLevel = 0 是普通段落，1..3 是章节标题（深度封顶 3）。
 *
 * [image] 非空表示这一段是正文里的一张插图，此时 [text] 为空：图片占一个段落下标
 * （滚动、进度、目录跳转都按它算），但只看文字的功能（搜索 / 朗读 / 大纲）会跳过它。
 */
internal data class Fb2Paragraph(
    val text: String,
    val headingLevel: Int = 0,
    val image: TextImage? = null,
)

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
 * - 正文流里的 `<image l:href="#id"/>` 产出空文字的图片段落（占一个段落下标），
 *   字节来自 `<FictionBook>` 直属的 `<binary id="...">`；
 * - `empty-line` / `annotation` / `binary` / `description` / `stylesheet` 不产出段落。
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

    /**
     * 递归下钻的深度上限。
     *
     * FB2 是任意嵌套的 XML：4M 字符的预算里 `<section>` 只占 9 字节，够埋几十万层，
     * 递归到那一步就是 StackOverflowError（捕获 SOE 本身不可靠，栈已经耗尽）。
     * 超过上限就停止下钻、把剩余内容当扁平正文，宁可少一层结构也不要炸。
     */
    private const val MAX_NESTING_DEPTH = 64
    private val AUTHOR_FIELDS = listOf("first-name", "middle-name", "last-name")
    private val WHITESPACE = Regex("\\s+")

    /**
     * 一张图的 base64 正文允许的最大字符数：MAX_BYTES 的 4/3 膨胀 + 64 字符余量（换行 / 缩进 / 填充）。
     *
     * 先按字符数挡一道再解码：几十 MB 的 base64 串拼出来和解码出来各占一份等大的内存，
     * 而这种图本来就过不了 [TextImage.MAX_BYTES]，两份分配全白花。
     */
    private val MAX_BASE64_CHARS = TextImage.MAX_BYTES.toLong() * 4 / 3 + 64

    /**
     * 这些元素（含其全部子孙）不产出段落。
     *
     * `image` 留在表里只是兜底：正文流里的图片由 [appendImage] 在各容器里**先于**静默判断
     * 处理，它要占一个段落下标，不能跟着静默一起丢掉。
     */
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
        return Fb2Document(title, author, bodyParagraphs(root, binaryImages(root)))
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

    private fun bodyParagraphs(root: Element, images: Map<String, TextImage>): List<Fb2Paragraph> {
        val body = childElements(root)
            .firstOrNull { nameOf(it) == "body" && !hasNameAttribute(it) }
            ?: return emptyList()
        val out = ArrayList<Fb2Paragraph>()
        for (child in childElements(body)) {
            when (nameOf(child)) {
                // body 直接挂的 title 是书名页 / 卷名，按一级标题输出，不能混进正文。
                "title" -> appendTitleLine(child, 1, out)
                "section" -> appendSection(child, 1, images, out)
                "image" -> appendImage(child, images, out)
                else -> appendFlow(child, images, out)
            }
        }
        return out
    }

    /** section：先出自己的一行标题，再按文档顺序展开子节点（嵌套 section 递归、层级 +1）。 */
    private fun appendSection(
        section: Element,
        depth: Int,
        images: Map<String, TextImage>,
        out: MutableList<Fb2Paragraph>,
    ) {
        if (depth > MAX_NESTING_DEPTH) {
            appendFlow(section, images, out)
            return
        }
        val level = minOf(depth, MAX_HEADING_LEVEL)
        firstChild(section, "title")?.let { title -> appendTitleLine(title, level, out) }
        for (child in childElements(section)) {
            when (nameOf(child)) {
                // section 的 title 已经合并成上面那一行，不能再把它的 p 当正文。
                "title" -> Unit
                "section" -> appendSection(child, depth + 1, images, out)
                "image" -> appendImage(child, images, out)
                else -> appendFlow(child, images, out)
            }
        }
    }

    /**
     * title 里的多个 p 合并成一行标题（U+00B7 分隔），空串丢弃；level 由调用方按层级给。
     *
     * 只收 `p`：`<title>` 里的 `<image>` 是封面 / 卷头画，不是正文插图，这里不产出图片段。
     */
    private fun appendTitleLine(title: Element, level: Int, out: MutableList<Fb2Paragraph>) {
        val line = childElements(title)
            .filter { nameOf(it) == "p" }
            .mapNotNull { collapsedText(it).takeIf { text -> text.isNotEmpty() } }
            .joinToString(TITLE_LINE_SEPARATOR)
        if (line.isNotEmpty()) out += Fb2Paragraph(line, level)
    }

    /**
     * 正文流：epigraph / poem / stanza / cite / table 等容器逐层下钻，遇到 p / v / subtitle 收一行。
     *
     * 按**文档顺序**遍历全部子节点，而不只是子元素：`<td>2024</td>`、`<cite>引文</cite>`，
     * 以及 `<epigraph>题词<p>x</p></epigraph>` 这种「文字和段落混排」的容器，
     * 直属文字此前会被整块静默丢掉（只遍历 Element 时它连一个节点都不是）。
     * p / v / subtitle 仍整块收起（叶元素不再下钻），所以同一段文字不会输出两次；
     * 因此 `<p>甲<image/>乙</p>` 里的行内图不产出图片段（只留文字），图片只认段落之间的引用。
     */
    private fun appendFlow(
        element: Element,
        images: Map<String, TextImage>,
        out: MutableList<Fb2Paragraph>,
        depth: Int = 0,
    ) {
        val name = nameOf(element)
        // image 在 SILENT_ELEMENTS 里，但它要占一个段落下标（插图是正文的一部分），
        // 所以必须赶在静默判断之前处理掉。
        if (name == "image") {
            appendImage(element, images, out)
            return
        }
        if (name in SILENT_ELEMENTS) return
        if (name in LEAF_TEXT_ELEMENTS) {
            val text = collapsedText(element)
            if (text.isNotEmpty()) out += Fb2Paragraph(text, 0)
            return
        }
        if (depth > MAX_NESTING_DEPTH) return
        val children = element.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index) ?: continue
            if (child is Element) {
                appendFlow(child, images, out, depth + 1)
                continue
            }
            if (child.nodeType == Node.TEXT_NODE || child.nodeType == Node.CDATA_SECTION_NODE) {
                val text = collapsedNodeText(child)
                if (text.isNotEmpty()) out += Fb2Paragraph(text, 0)
            }
        }
    }

    // ------------------------------------------------------------------ 图片

    /**
     * `<FictionBook>` 直属的 `<binary id="..." content-type="...">BASE64</binary>` → id → 图片。
     *
     * - id 按 localName 取，`fb2:id` 这种带前缀的写法也认；
     * - base64 正文常按 76 字符折行、前面还带缩进，所以用 MIME 解码器（它会跳过空白）；
     * - 单张先按字符数挡一道（[MAX_BASE64_CHARS]），超限的不拼接、不解码、不进表；
     * - 解码失败（截断、填充错位）只跳过这一张，不抛 [Fb2ParseException]：一张坏图不该毁掉整本书。
     */
    private fun binaryImages(root: Element): Map<String, TextImage> {
        val images = HashMap<String, TextImage>()
        for (child in childElements(root)) {
            if (nameOf(child) != "binary") continue
            val id = attribute(child, "id")?.trim().orEmpty()
            if (id.isEmpty()) continue
            if (rawTextLength(child) > MAX_BASE64_CHARS) continue
            val bytes = try {
                Base64.getMimeDecoder().decode(rawText(child))
            } catch (e: IllegalArgumentException) {
                continue
            }
            // 空字节 / 解码后仍超过 MAX_BYTES 的由 embedded 拒掉，同样只是跳过这一张。
            val image = TextImage.embedded(id, bytes, alt = id) ?: continue
            images[id] = image
        }
        return images
    }

    /**
     * 正文流里的 `<image l:href="#id"/>`：查到对应的 binary 就产出一个空文字的图片段落。
     *
     * 查不到（没有 href、指向不存在的 id、那张图太大或 base64 坏了）就整段跳过，
     * 不产出空段落 —— 一个悬空引用不该在书里留一个空白位置。
     */
    private fun appendImage(
        element: Element,
        images: Map<String, TextImage>,
        out: MutableList<Fb2Paragraph>,
    ) {
        val id = attribute(element, "href")?.trim()?.removePrefix("#") ?: return
        val image = images[id] ?: return
        out += Fb2Paragraph(text = "", image = image)
    }

    /** 节点下全部文本的字符数，不拼接字符串：解码前判断 base64 正文是否超长就靠它。 */
    private fun rawTextLength(node: Node): Long {
        if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
            return (node.nodeValue?.length ?: 0).toLong()
        }
        var total = 0L
        var child = node.firstChild
        while (child != null) {
            total += rawTextLength(child)
            child = child.nextSibling
        }
        return total
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

    /** body / section 是否带 name 属性（有就算，空值也算）。 */
    private fun hasNameAttribute(element: Element): Boolean = attribute(element, "name") != null

    /**
     * 按 localName 取属性值：namespace-aware 解析后 `nodeName` 带前缀（`l:href`、`fb2:id`），
     * `localName` 才是 `href` / `id`，正文里的图片引用必须靠这一步才认得出来。
     */
    private fun attribute(element: Element, localName: String): String? {
        val attributes = element.attributes
        for (index in 0 until attributes.length) {
            // NamedNodeMap 里只装 Attr，item 的静态类型却是 Node，取 value 要显式转一次。
            val attribute = attributes.item(index) as? Attr ?: continue
            if (nameOf(attribute) == localName) return attribute.value
        }
        return null
    }

    // ---------------------------------------------------------------- 文本

    /** 全部后代文本节点拼接 → 连续空白折叠成单个空格 → trim。 */
    private fun collapsedText(element: Element): String = WHITESPACE.replace(rawText(element), " ").trim()

    /** 单个节点自身的文字（容器直属文本节点用）：折叠空白后 trim，不做后代递归。 */
    private fun collapsedNodeText(node: Node): String =
        WHITESPACE.replace(node.nodeValue ?: "", " ").trim()

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
