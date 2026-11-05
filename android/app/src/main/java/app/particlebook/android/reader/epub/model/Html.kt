package app.particlebook.android.reader.epub.model

internal sealed interface HtmlNode

internal data class HtmlText(val text: String) : HtmlNode

internal data class HtmlElement(
    val tag: String,
    val attributes: Map<String, String>,
    val children: MutableList<HtmlNode> = mutableListOf(),
) : HtmlNode {
    fun attr(name: String): String? = attributes[name.lowercase()]
}

internal sealed interface HtmlToken {
    data class StartTag(
        val name: String,
        val attributes: Map<String, String>,
        val selfClosing: Boolean,
    ) : HtmlToken

    data class EndTag(val name: String) : HtmlToken
    data class Text(val text: String) : HtmlToken
}

/**
 * 容错 HTML/XHTML tokenizer：
 * - 不要求 XML 合法；未闭合标签、错配结束标签由 tree builder 兜底。
 * - 支持注释、PI、DOCTYPE、CDATA、自闭合标签、无引号属性。
 * - style/script 内容按 raw text 处理，避免 CSS 里的 < 被当成标签。
 * - 文本与属性值做实体解码；未知实体原样保留。
 */
internal class HtmlTokenizer(private val source: String) {

    private var i = 0

    fun tokenize(): List<HtmlToken> {
        val out = ArrayList<HtmlToken>()
        i = 0
        val n = source.length
        while (i < n) {
            if (source[i] != '<') {
                val start = i
                while (i < n && source[i] != '<') i++
                val text = source.substring(start, i)
                if (text.isNotEmpty()) out += HtmlToken.Text(decodeHtmlEntities(text))
                continue
            }
            if (source.startsWith("<!--", i)) {
                val end = source.indexOf("-->", i + 4)
                i = if (end < 0) n else end + 3
                continue
            }
            if (source.startsWith("<![CDATA[", i, ignoreCase = true)) {
                val start = i + 9
                val end = source.indexOf("]]>", start)
                val text = if (end < 0) source.substring(start) else source.substring(start, end)
                if (text.isNotEmpty()) out += HtmlToken.Text(text)
                i = if (end < 0) n else end + 3
                continue
            }
            if (source.startsWith("<?", i)) {
                val end = source.indexOf("?>", i + 2)
                i = if (end < 0) n else end + 2
                continue
            }
            if (source.startsWith("<!", i)) {
                val end = source.indexOf('>', i + 2)
                i = if (end < 0) n else end + 1
                continue
            }
            if (i + 1 < n && source[i + 1] == '/') {
                i += 2
                val name = readName()
                skipUntilTagEnd()
                if (name.isNotEmpty()) out += HtmlToken.EndTag(name.lowercase())
                continue
            }
            if (i + 1 < n && isNameStart(source[i + 1])) {
                i++
                val name = readName().lowercase()
                val attributes = LinkedHashMap<String, String>()
                var selfClosing = false
                while (i < n) {
                    skipWhitespace()
                    if (i >= n) break
                    val c = source[i]
                    if (c == '>') {
                        i++
                        break
                    }
                    if (c == '/' && i + 1 < n && source[i + 1] == '>') {
                        i += 2
                        selfClosing = true
                        break
                    }
                    val attrStart = i
                    while (i < n && !source[i].isWhitespace() && source[i] != '=' &&
                        source[i] != '>' && !(source[i] == '/' && i + 1 < n && source[i + 1] == '>')
                    ) {
                        i++
                    }
                    if (i == attrStart) {
                        i++
                        continue
                    }
                    val attrName = source.substring(attrStart, i).lowercase()
                    skipWhitespace()
                    var attrValue = ""
                    if (i < n && source[i] == '=') {
                        i++
                        skipWhitespace()
                        if (i < n && (source[i] == '"' || source[i] == '\'')) {
                            val quote = source[i]
                            i++
                            val valueStart = i
                            while (i < n && source[i] != quote) i++
                            attrValue = source.substring(valueStart, i)
                            if (i < n) i++
                        } else {
                            val valueStart = i
                            while (i < n && !source[i].isWhitespace() && source[i] != '>' &&
                                !(source[i] == '/' && i + 1 < n && source[i + 1] == '>')
                            ) {
                                i++
                            }
                            attrValue = source.substring(valueStart, i)
                        }
                    }
                    attributes[attrName] = decodeHtmlEntities(attrValue)
                }
                out += HtmlToken.StartTag(name, attributes, selfClosing)
                if (!selfClosing && (name == "style" || name == "script")) {
                    val closePrefix = "</" + name
                    val closeIndex = source.indexOf(closePrefix, i, ignoreCase = true)
                    if (closeIndex < 0) {
                        val raw = source.substring(i)
                        if (raw.isNotEmpty()) out += HtmlToken.Text(raw)
                        out += HtmlToken.EndTag(name)
                        i = n
                    } else {
                        val raw = source.substring(i, closeIndex)
                        if (raw.isNotEmpty()) out += HtmlToken.Text(raw)
                        val end = source.indexOf('>', closeIndex)
                        i = if (end < 0) n else end + 1
                        out += HtmlToken.EndTag(name)
                    }
                }
                continue
            }
            out += HtmlToken.Text("<")
            i++
        }
        return out
    }

    private fun readName(): String {
        val start = i
        while (i < source.length && isNameChar(source[i])) i++
        return source.substring(start, i)
    }

    private fun skipWhitespace() {
        while (i < source.length && source[i].isWhitespace()) i++
    }

    private fun skipUntilTagEnd() {
        while (i < source.length && source[i] != '>') i++
        if (i < source.length) i++
    }

    private fun isNameStart(c: Char): Boolean = c.isLetter() || c == '_' || c == ':'

    private fun isNameChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' || c == ':'
}

internal object HtmlTags {
    val BLOCK: Set<String> = setOf(
        "address", "article", "aside", "blockquote", "body", "caption", "center",
        "dd", "details", "dialog", "dir", "div", "dl", "dt", "fieldset", "figcaption",
        "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header",
        "hgroup", "hr", "html", "li", "main", "menu", "nav", "ol", "p", "pre",
        "section", "summary", "table", "tbody", "td", "tfoot", "th", "thead", "tr", "ul",
    )

    val VOID: Set<String> = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link",
        "meta", "param", "source", "track", "wbr",
    )

    val INLINE: Set<String> = setOf(
        "a", "abbr", "b", "bdi", "bdo", "big", "br", "cite", "code", "data", "dfn",
        "em", "font", "i", "img", "kbd", "mark", "q", "ruby", "s", "samp", "small",
        "span", "strike", "strong", "sub", "sup", "time", "tt", "u", "var", "wbr",
    )
}

/**
 * 容错 DOM 构建：按 HTML 的可选结束标签规则自动收口。
 * 任何无法匹配的结束标签直接忽略，而不是抛异常。
 */
internal class HtmlTreeBuilder {

    fun build(tokens: List<HtmlToken>): HtmlElement {
        val root = HtmlElement("#document", emptyMap())
        val stack = ArrayDeque<HtmlElement>()
        stack.addLast(root)
        for (token in tokens) {
            when (token) {
                is HtmlToken.Text -> {
                    if (token.text.isNotEmpty()) stack.last().children += HtmlText(token.text)
                }

                is HtmlToken.StartTag -> {
                    autoCloseForStart(token.name, stack)
                    val element = HtmlElement(token.name, token.attributes)
                    stack.last().children += element
                    if (!token.selfClosing && token.name !in HtmlTags.VOID) stack.addLast(element)
                }

                is HtmlToken.EndTag -> closeTag(token.name, stack)
            }
        }
        return root
    }

    private fun autoCloseForStart(name: String, stack: ArrayDeque<HtmlElement>) {
        if (name == "p") {
            closeNearest(setOf("p"), emptySet(), stack)
            return
        }
        if (name in HtmlTags.BLOCK) closeNearest(setOf("p"), emptySet(), stack)
        when (name) {
            "li" -> closeNearest(setOf("li"), setOf("ul", "ol"), stack)
            "dt", "dd" -> closeNearest(setOf("dt", "dd"), setOf("dl"), stack)
            "tr" -> closeNearest(setOf("tr", "td", "th"), setOf("table"), stack)
            "td", "th" -> closeNearest(setOf("td", "th"), setOf("tr", "table"), stack)
            "thead", "tbody", "tfoot" -> closeNearest(
                setOf("thead", "tbody", "tfoot"),
                setOf("table"),
                stack,
            )
            "option" -> closeNearest(setOf("option"), setOf("select", "datalist"), stack)
        }
    }

    private fun closeNearest(
        names: Set<String>,
        stopAt: Set<String>,
        stack: ArrayDeque<HtmlElement>,
    ) {
        var index = stack.size - 1
        while (index > 0) {
            val tag = stack[index].tag
            if (tag in names) {
                while (stack.size > index) stack.removeLast()
                return
            }
            if (tag in stopAt) return
            index--
        }
    }

    private fun closeTag(name: String, stack: ArrayDeque<HtmlElement>) {
        var index = stack.size - 1
        while (index > 0) {
            if (stack[index].tag == name) {
                while (stack.size > index) stack.removeLast()
                return
            }
            index--
        }
    }
}

internal fun decodeHtmlEntities(text: String): String {
    if ('&' !in text) return text
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c != '&') {
            out.append(c)
            i++
            continue
        }
        val semi = text.indexOf(';', i + 1)
        if (semi < 0 || semi - i > 40) {
            out.append(c)
            i++
            continue
        }
        val body = text.substring(i + 1, semi)
        val decoded = decodeEntityBody(body)
        if (decoded == null) {
            out.append(c)
            i++
        } else {
            out.append(decoded)
            i = semi + 1
        }
    }
    return out.toString()
}

private fun decodeEntityBody(body: String): String? {
    if (body.isEmpty()) return null
    if (body.startsWith("#x") || body.startsWith("#X")) {
        val code = body.substring(2).toIntOrNull(16) ?: return null
        return codePointToString(code)
    }
    if (body.startsWith("#")) {
        val code = body.substring(1).toIntOrNull() ?: return null
        return codePointToString(code)
    }
    return NAMED_ENTITIES[body] ?: NAMED_ENTITIES[body.lowercase()]
}

private fun codePointToString(code: Int): String? {
    if (code <= 0 || code > 0x10FFFF) return "\uFFFD"
    if (code in 0xD800..0xDFFF) return "\uFFFD"
    return String(Character.toChars(code))
}

private val NAMED_ENTITIES: Map<String, String> = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to "\u00A0",
    "ensp" to "\u2002",
    "emsp" to "\u2003",
    "thinsp" to "\u2009",
    "shy" to "\u00AD",
    "mdash" to "\u2014",
    "ndash" to "\u2013",
    "hellip" to "\u2026",
    "ldquo" to "\u201C",
    "rdquo" to "\u201D",
    "lsquo" to "\u2018",
    "rsquo" to "\u2019",
    "laquo" to "\u00AB",
    "raquo" to "\u00BB",
    "copy" to "\u00A9",
    "reg" to "\u00AE",
    "trade" to "\u2122",
    "times" to "\u00D7",
    "divide" to "\u00F7",
    "plusmn" to "\u00B1",
    "deg" to "\u00B0",
    "micro" to "\u00B5",
    "para" to "\u00B6",
    "sect" to "\u00A7",
    "middot" to "\u00B7",
    "bull" to "\u2022",
    "euro" to "\u20AC",
    "pound" to "\u00A3",
    "yen" to "\u00A5",
    "cent" to "\u00A2",
    "frac12" to "\u00BD",
    "frac14" to "\u00BC",
    "frac34" to "\u00BE",
    "sup1" to "\u00B9",
    "sup2" to "\u00B2",
    "sup3" to "\u00B3",
    "szlig" to "\u00DF",
    "agrave" to "\u00E0",
    "aacute" to "\u00E1",
    "eacute" to "\u00E9",
    "egrave" to "\u00E8",
    "ccedil" to "\u00E7",
    "ouml" to "\u00F6",
    "uuml" to "\u00FC",
    "auml" to "\u00E4",
)
