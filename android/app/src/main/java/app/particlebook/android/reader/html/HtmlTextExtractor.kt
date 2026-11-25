package app.particlebook.android.reader.html

import app.particlebook.android.reader.text.TextImage
import app.particlebook.android.reader.text.TextParagraph
import java.io.File
import java.io.IOException
import java.util.Base64

/**
 * HTML 正文 → [TextParagraph]：滚动式文本阅读器用的最小投影。
 *
 * 阅读器把 HTML 电子书当纯文本流显示（与 TXT / Markdown / FB2 同一条渲染链路），
 * 这里只回答两件事：哪些文字是正文、哪几段是标题。不建 DOM、不做布局、不做 CSS，
 * 因此可以在单遍字符扫描里完成；超长切分与进度偏移留给
 * [app.particlebook.android.reader.text.TextDocumentParser.fromParagraphs]。
 *
 * 扫描规则：
 * - 注释、doctype、处理指令整段忽略；CDATA 的内容当普通文本（里面的 `<b>` 不解标签）；
 * - 标签名大小写不敏感，属性值里（单引号 / 双引号）的 `>` 不会提前结束标签；
 * - [RAW_TEXT_DROPPED_ELEMENTS] 与 [CONTAINER_DROPPED_ELEMENTS] 连同内部文本整棵丢弃；
 * - [BLOCK_ELEMENTS] 的每个开始与结束标签都切段，`h1..h6` 的内容带标题层级 1..6；
 * - [PRE_ELEMENT] 内换行即切段，行内空白照样折叠；
 * - `<img>` 例外：产出一个「无文字、带 [TextParagraph.image]」的图片段（src 见 [imageOf]），
 *   只有真解出一张图时才切段，被丢弃的图不留空段；
 * - 其余标签（a / b / span / input 以及所有未知标签）一律按行内处理，只剥标签留文本，
 *   所以不需要单独维护行内标签表；
 * - 文本先做字符引用解码（[HtmlEntities.decode]）再折叠空白：段首尾 trim，空段落丢弃。
 *
 * 容错：`<` 后面不是字母 / `/` / `!` / `?` 时把 `<` 当普通字符；
 * 没有 `>` 的标签连同其后的内容一起舍弃；多余的结束标签忽略。
 */
internal object HtmlTextExtractor {

    /**
     * 抽取入口：按文档顺序返回段落；空输入与「只有标签没有文本」都返回空列表。
     *
     * [baseDir] 是相对 src 的基准目录（这本书所在的目录）：给了才解析相对路径，
     * 同时充当目录逃逸检查的边界；不给时相对路径一律跳过，绝对路径照常接受。
     */
    fun extract(html: String, baseDir: File? = null): List<TextParagraph> = Scanner(html, baseDir).scan()

    /** 块级元素：开始与结束都切段；h1..h6 同时给段落带上标题层级。 */
    private val BLOCK_ELEMENTS = setOf(
        "address", "article", "aside", "blockquote", "br", "caption", "center", "dd",
        "details", "div", "dl", "dt", "fieldset", "figcaption", "figure", "footer",
        "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "legend", "li",
        "main", "nav", "ol", "p", "pre", "section", "summary", "table", "tbody", "td",
        "tfoot", "th", "thead", "tr", "ul", "body", "html",
    )

    /**
     * 原始文本类丢弃元素：内容不解析标签，遇到第一个 `</name` 就结束。
     * 浏览器对 script / style 也按 raw text 处理，JS 与 CSS 里的 `<`、引号才不会干扰标签扫描。
     */
    private val RAW_TEXT_DROPPED_ELEMENTS = setOf("script", "style", "title", "noscript")

    /** 容器类丢弃元素：按同名标签的嵌套深度找配对的结束标签。 */
    private val CONTAINER_DROPPED_ELEMENTS = setOf(
        "head", "svg", "template", "iframe", "object", "math", "canvas",
        "audio", "video", "map", "select", "button",
    )

    /**
     * 无内容又被要求丢弃的元素：embed 在 HTML 里是 void 元素，不会出现 `</embed>`。
     * 按容器处理会把后面整篇正文一起吞掉，所以只丢弃标签本身。
     */
    private val VOID_DROPPED_ELEMENTS = setOf("embed")

    private const val PRE_ELEMENT = "pre"

    /** 唯一会产出段落的行内元素：图片段 text 为空、[TextParagraph.image] 非空。 */
    private const val IMAGE_ELEMENT = "img"

    private const val ATTRIBUTE_SRC = "src"
    private const val ATTRIBUTE_ALT = "alt"

    private const val DATA_URI_PREFIX = "data:"
    private const val BASE64_MARKER = ";base64"
    private const val FILE_SCHEME = "file:"

    /** `//host/x`：协议相对 URL，阅读器不联网，与 http(s) 一样跳过。 */
    private const val PROTOCOL_RELATIVE_PREFIX = "//"

    /** base64 长度预判的余量：换行、缩进与结尾填充都算在这里。 */
    private const val BASE64_LENGTH_SLACK = 64L

    private const val TAG_END_CHAR = '>'
    private const val SELF_CLOSING_CHAR = '/'
    private const val ATTRIBUTE_ASSIGNMENT = '='
    private const val BASE64_HEADER_SEPARATOR = ','

/** 唯一允许待在标题里、不该把标题隐式关掉的块级标签：`<h2>标题<br>副标题</h2>` 很常见。 */
private const val LINE_BREAK_ELEMENT = "br"
    private const val HEAD_ELEMENT = "head"
    private const val BODY_ELEMENT = "body"

    private const val COMMENT_OPEN = "<!--"
    private const val COMMENT_CLOSE = "-->"
    private const val CDATA_OPEN = "<![CDATA["
    private const val CDATA_CLOSE = "]]>"
    private const val PROCESSING_INSTRUCTION_OPEN = "<?"
    private const val PROCESSING_INSTRUCTION_CLOSE = "?>"
    private const val DECLARATION_OPEN = "<!"
    private const val END_TAG_OPEN = "</"

    private const val DOUBLE_QUOTE = '"'
    private const val SINGLE_QUOTE = '\''

    private const val SPACE = ' '
    private const val TAB = '\t'
    private const val LINE_FEED = '\n'
    private const val CARRIAGE_RETURN = '\r'
    private const val FORM_FEED = '\u000C'
    private const val NO_BREAK_SPACE = '\u00A0'

    /**
     * 段落边界 = 一次 flush；标题层级取「当前打开的 h1..h6」，所以一切状态都在这里。
     *
     * [baseDir] 只服务 `<img src="相对路径">`：相对它解析，越界（`../` 或指向目录外的绝对路径）就丢图。
     */
    private class Scanner(private val source: String, private val baseDir: File?) {

        private val out = ArrayList<TextParagraph>()

        /** 当前段落的原始文本（已解码实体，未折叠空白）。 */
        private val pending = StringBuilder()

        /** 当前打开的标题层级栈：嵌套标题取最内层，结束后弹回外层。 */
        private val openHeadings = ArrayList<Int>()

        /** `<pre>` 嵌套深度：> 0 时换行符切段。 */
        private var preDepth = 0

        /** 正在丢弃的容器元素名；null 表示正常抽取。 */
        private var droppedName: String? = null

        /** 丢弃区里同名标签的嵌套深度，回到 0 才恢复抽取。 */
        private var droppedDepth = 0

        private var index = 0

        fun scan(): List<TextParagraph> {
            val length = source.length
            while (index < length) {
                if (source[index] != '<') {
                    val start = index
                    while (index < length && source[index] != '<') index++
                    if (droppedName == null) appendText(source.substring(start, index))
                    continue
                }
                consumeMarkup(length)
            }
            flush()
            return out
        }

        // ------------------------------------------------------------ 标记扫描

        /** 从 [index] 处的 `<` 起吃掉一段标记，并把 [index] 推到它之后。 */
        private fun consumeMarkup(length: Int) {
            if (source.startsWith(COMMENT_OPEN, index)) {
                val end = source.indexOf(COMMENT_CLOSE, index + COMMENT_OPEN.length)
                index = if (end < 0) length else end + COMMENT_CLOSE.length
                return
            }
            if (source.regionMatches(index, CDATA_OPEN, 0, CDATA_OPEN.length, ignoreCase = true)) {
                val contentStart = index + CDATA_OPEN.length
                val end = source.indexOf(CDATA_CLOSE, contentStart)
                val contentEnd = if (end < 0) length else end
                if (droppedName == null) appendText(source.substring(contentStart, contentEnd))
                index = if (end < 0) length else end + CDATA_CLOSE.length
                return
            }
            if (source.startsWith(PROCESSING_INSTRUCTION_OPEN, index)) {
                val end = source.indexOf(PROCESSING_INSTRUCTION_CLOSE, index + PROCESSING_INSTRUCTION_OPEN.length)
                index = if (end < 0) length else end + PROCESSING_INSTRUCTION_CLOSE.length
                return
            }
            if (source.startsWith(DECLARATION_OPEN, index)) {
                // <!doctype ...> 与其它 <! ...> 声明：整段忽略。
                val end = source.indexOf('>', index + DECLARATION_OPEN.length)
                index = if (end < 0) length else end + 1
                return
            }
            if (source.startsWith(END_TAG_OPEN, index)) {
                consumeEndTag(length)
                return
            }
            if (index + 1 >= length || !source[index + 1].isLetter()) {
                // 规则 12：`<` 后面既不是字母也不是 / ! ?，当普通字符。
                if (droppedName == null) appendText("<")
                index += 1
                return
            }
            consumeStartTag(length)
        }

        private fun consumeStartTag(length: Int) {
            var cursor = index + 1
            val nameStart = cursor
            while (cursor < length && isNameChar(source[cursor])) cursor++
            val name = source.substring(nameStart, cursor).lowercase()
            val tagEnd = findTagEnd(cursor)
            if (tagEnd < 0) {
                // 规则 12：没有配套 `>` 的标签直接舍弃，其后的内容一并丢掉。
                index = length
                return
            }
            index = tagEnd
            val selfClosing = isSelfClosing(tagEnd, cursor)

            if (droppedName != null) {
                if (name == droppedName) {
                    if (!selfClosing) droppedDepth++
                } else if (droppedName == HEAD_ELEMENT && name == BODY_ELEMENT) {
                    // HTML5 允许省略 </head>：body 一开始就说明 head 结束，否则整篇正文会被吞掉。
                    closeDropped()
                    openElement(name, selfClosing)
                }
                return
            }
            if (name in RAW_TEXT_DROPPED_ELEMENTS) {
                if (!selfClosing) skipRawText(name, length)
                return
            }
            if (name in CONTAINER_DROPPED_ELEMENTS) {
                if (!selfClosing) openDropped(name)
                return
            }
            if (name in VOID_DROPPED_ELEMENTS) return
            if (name == IMAGE_ELEMENT) {
                appendImage(cursor, tagEnd)
                return
            }
            openElement(name, selfClosing)
        }

        private fun consumeEndTag(length: Int) {
            var cursor = index + END_TAG_OPEN.length
            val nameStart = cursor
            while (cursor < length && isNameChar(source[cursor])) cursor++
            if (cursor == nameStart) {
                // `</` 后面没有名字：不是一个标签，丢掉这两个字符继续扫。
                index += END_TAG_OPEN.length
                return
            }
            val name = source.substring(nameStart, cursor).lowercase()
            val tagEnd = findTagEnd(cursor)
            if (tagEnd < 0) {
                index = length
                return
            }
            index = tagEnd
            if (droppedName != null) {
                if (name == droppedName) {
                    droppedDepth -= 1
                    if (droppedDepth <= 0) closeDropped()
                }
                return
            }
            closeElement(name)
        }

        /** 原始文本元素的内容直接跳到第一个 `</name`（大小写不敏感），其中的标签一律不解析。 */
        private fun skipRawText(name: String, length: Int) {
            val terminator = END_TAG_OPEN + name
            var cursor = index
            while (cursor < length) {
                val close = source.indexOf(terminator, cursor, ignoreCase = true)
                if (close < 0) {
                    index = length
                    return
                }
                val afterName = close + terminator.length
                // `</scriptfoo>` 不是 `</script>`：标签名必须在此结束。
                if (afterName < length && isNameChar(source[afterName])) {
                    cursor = afterName
                    continue
                }
                val end = findTagEnd(afterName)
                index = if (end < 0) length else end
                return
            }
            index = length
        }

        /** 从标签名之后扫到 `>` 的后一位；跳过引号里的 `>`；没有闭合返回 -1。 */
        private fun findTagEnd(from: Int): Int {
            var cursor = from
            while (cursor < source.length) {
                when (source[cursor]) {
                    DOUBLE_QUOTE, SINGLE_QUOTE -> {
                        val close = source.indexOf(source[cursor], cursor + 1)
                        if (close < 0) return -1
                        cursor = close + 1
                    }

                    '>' -> return cursor + 1
                    else -> cursor++
                }
            }
            return -1
        }

        /** `/>` 才算自闭合；`<br/>` 与 `<br />` 都认。 */
        private fun isSelfClosing(tagEnd: Int, nameEnd: Int): Boolean {
            var cursor = tagEnd - 2
            while (cursor > nameEnd && source[cursor].isWhitespace()) cursor--
            return cursor >= nameEnd && source[cursor] == '/'
        }

        private fun isNameChar(char: Char): Boolean =
            char.isLetterOrDigit() || char == '-' || char == '_' || char == ':' || char == '.'

        // ------------------------------------------------------------ 元素语义

        private fun openDropped(name: String) {
            droppedName = name
            droppedDepth = 1
        }

        private fun closeDropped() {
            droppedName = null
            droppedDepth = 0
        }

        /**
         * 开始标签：块级切段；h1..h6 压栈；pre 深度 +1；其余（行内 / 未知 / 无内容）只剥标签。
         *
         * 块级开始标签会**隐式结束**仍然开着的标题（HTML5 允许 `<h1>第一章<p>正文…` 不写 `</h1>`，
         * txt→html 的转换器产物里极常见）。不这么做，标题层级会一路挂到后面所有段落上：
         * 正文全部按大字号加粗渲染，而且整段灌进目录。`<br>` 是唯一的例外（标题内合法）。
         */
        private fun openElement(name: String, selfClosing: Boolean) {
            if (name !in BLOCK_ELEMENTS) return
            flush()
            if (selfClosing) return
            if (name != LINE_BREAK_ELEMENT && openHeadings.isNotEmpty()) openHeadings.clear()
            val level = headingLevelOf(name)
            if (level > 0) openHeadings.add(level)
            if (name == PRE_ELEMENT) preDepth++
        }

        /**
         * 结束标签：块级切段（先切段，用当前标题层级定层级，再弹栈）；多余的非块级结束标签忽略。
         *
         * 标题按**同名层**弹栈。注意标题栈实际最多只有一层（任何标题开始标签都会先清空已开的标题，
         * 因为 HTML 里标题不能嵌套），所以 `</h1>` 遇上 `<h1>甲<h3>乙</h1>` 这种错位写法时找不到 1，
         * 直接忽略 —— 此时 h3 开始时 h1 就已经关了，与浏览器行为一致；孤立 `</h3>` 同样忽略。
         */
        private fun closeElement(name: String) {
            if (name !in BLOCK_ELEMENTS) return
            flush()
            if (name == PRE_ELEMENT && preDepth > 0) preDepth--
            val level = headingLevelOf(name)
            if (level <= 0) return
            val index = openHeadings.lastIndexOf(level)
            if (index < 0) return
            while (openHeadings.size > index) openHeadings.removeAt(openHeadings.size - 1)
        }

        /** h1..h6 → 1..6，其它一律 0。 */
        private fun headingLevelOf(name: String): Int =
            if (name.length == 2 && name[0] == 'h' && name[1] in '1'..'6') name[1] - '0' else 0

        // -------------------------------------------------------------- 插图

        /**
         * `<img>` → 图片段。
         *
         * 只有真解出一张图时才切段：`<img src="http://…">`、越界的相对路径这类「被丢弃的图」
         * 不该把一段连续文字劈成两段。切段发生在产出之前，图因此独占一个段落下标。
         */
        private fun appendImage(nameEnd: Int, tagEnd: Int) {
            val attributes = parseAttributes(nameEnd, tagEnd)
            val src = attributes[ATTRIBUTE_SRC] ?: return
            val image = imageOf(src, attributes[ATTRIBUTE_ALT].orEmpty()) ?: return
            flush()
            out.add(TextParagraph(text = "", image = image))
        }

        /**
         * src → 图片。四种形态：
         * - `data:image/…;base64,…`：解码进内存，见 [dataImage]；
         * - `file:`：当成路径，见 [fileUriPath] 与 [fileImage]；
         * - 绝对 / 相对路径：转成绝对路径，见 [fileImage]；
         * - `http:` / `https:` / `//host/x` 以及其它 scheme：跳过（阅读器不联网）。
         */
        private fun imageOf(rawSource: String, alt: String): TextImage? {
            val src = rawSource.trim()
            if (src.isEmpty()) return null
            if (src.startsWith(DATA_URI_PREFIX, ignoreCase = true)) return dataImage(src, alt)
            if (src.startsWith(PROTOCOL_RELATIVE_PREFIX)) return null
            if (src.startsWith(FILE_SCHEME, ignoreCase = true)) {
                return fileImage(fileUriPath(src.substring(FILE_SCHEME.length)), alt)
            }
            // 绝对路径先认下来：Windows 上的 `C:\…` 也长得像 scheme，不先判会被当成未知协议丢掉。
            if (File(src).isAbsolute) return fileImage(src, alt)
            if (hasScheme(src)) return null
            return fileImage(src, alt)
        }

        /**
         * `file:` 之后的写法 → 路径：`file:///sdcard/01.jpg` → `/sdcard/01.jpg`，
         * `file://host/share/01.jpg` → `/share/01.jpg`（丢掉 authority），`file:01.jpg` → `01.jpg`。
         */
        private fun fileUriPath(rest: String): String {
            if (!rest.startsWith(PROTOCOL_RELATIVE_PREFIX)) return rest
            val slash = rest.indexOf('/', PROTOCOL_RELATIVE_PREFIX.length)
            return if (slash < 0) "" else rest.substring(slash)
        }

        /** 带 scheme 的 src（http / https / mailto / cid …）：只认 data: 与 file:，其余一律跳过。 */
        private fun hasScheme(src: String): Boolean {
            val colon = src.indexOf(':')
            if (colon <= 0 || !src[0].isLetter()) return false
            for (position in 1 until colon) {
                val char = src[position]
                if (!char.isLetterOrDigit() && char != '+' && char != '-' && char != '.') return false
            }
            return true
        }

        /**
         * 路径 → 文件图。先 canonical 化（吃掉 `..` 与符号链接），再确认没有跑出 [baseDir]：
         * 电子书里的 `../../data/…` 不该让阅读器读到书架目录之外的文件。
         *
         * 没有 baseDir 时只有绝对路径能用——相对路径无从解析，直接跳过。
         */
        private fun fileImage(raw: String, alt: String): TextImage? {
            if (raw.isBlank()) return null
            val base = baseDir
            val file = File(raw)
            val resolved = try {
                if (file.isAbsolute) {
                    file.canonicalFile
                } else {
                    // 相对路径没有基准目录就无从解析：宁可没有这张图，也不要按进程工作目录瞎猜。
                    val directory = base ?: return null
                    File(directory, raw).canonicalFile
                }
            } catch (ignored: IOException) {
                return null
            }
            if (base != null) {
                val root = try {
                    base.canonicalFile
                } catch (ignored: IOException) {
                    return null
                }
                if (!isInside(resolved, root)) return null
            }
            return TextImage.file(resolved.path, alt)
        }

        /** 目录逃逸检查：相等，或严格在基准目录之下（按分隔符比较，`/books2` 不算在 `/books` 里）。 */
        private fun isInside(target: File, base: File): Boolean {
            val targetPath = target.path
            val basePath = base.path
            if (targetPath == basePath) return true
            val prefix = if (basePath.endsWith(File.separator)) basePath else basePath + File.separator
            return targetPath.startsWith(prefix)
        }

        /**
         * `data:image/png;base64,XXXX` → 内嵌图。
         *
         * 先按 base64 长度预判：一张 30 MB 的扫描图编成 base64 是 40 MB 字符串，先解码再按
         * [TextImage.MAX_BYTES] 丢掉等于白白分配一次；解码失败与超限一样静默跳过——
         * 一张坏图不该让整章正文消失。
         */
        private fun dataImage(src: String, alt: String): TextImage? {
            val comma = src.indexOf(BASE64_HEADER_SEPARATOR)
            if (comma < 0) return null
            val header = src.substring(DATA_URI_PREFIX.length, comma)
            if (!header.endsWith(BASE64_MARKER, ignoreCase = true)) return null
            val payload = src.substring(comma + 1)
            // 4 个 base64 字符 = 3 字节；余量留给换行、缩进与结尾填充。
            if (payload.length > TextImage.MAX_BYTES.toLong() * 4 / 3 + BASE64_LENGTH_SLACK) return null
            val bytes = try {
                Base64.getMimeDecoder().decode(payload)
            } catch (invalid: IllegalArgumentException) {
                return null
            }
            // key 只依赖 src 与字节数：同一张图每次解析都拿到同一个缓存键。
            val key = DATA_URI_PREFIX + src.hashCode().toUInt().toString(16) + ":" + bytes.size
            return TextImage.embedded(key, bytes, alt)
        }

        /**
         * 开始标签里的属性 → 小写名字到值的映射。
         *
         * 单引号、双引号、无引号三种写法都认；属性名大小写不敏感（HTML 规定）；
         * 值里的字符引用先解码（`src="a&amp;b.jpg"` 要按 `a&b.jpg` 找文件）；
         * 同名属性保留第一个——与浏览器一致。
         */
        private fun parseAttributes(from: Int, to: Int): Map<String, String> {
            val attributes = HashMap<String, String>()
            var cursor = from
            while (cursor < to) {
                val char = source[cursor]
                if (char == TAG_END_CHAR) break
                if (char.isWhitespace() || char == SELF_CLOSING_CHAR) {
                    cursor++
                    continue
                }
                val nameStart = cursor
                while (cursor < to && !isAttributeNameBoundary(source[cursor])) cursor++
                if (cursor == nameStart) {
                    // `=` 之类开头的残渣：吃掉一个字符继续，别在这里打转。
                    cursor++
                    continue
                }
                val name = source.substring(nameStart, cursor).lowercase()
                while (cursor < to && source[cursor].isWhitespace()) cursor++
                if (cursor >= to || source[cursor] != ATTRIBUTE_ASSIGNMENT) {
                    attributes.putIfAbsent(name, "")
                    continue
                }
                cursor++
                while (cursor < to && source[cursor].isWhitespace()) cursor++
                val value: String
                if (cursor < to && (source[cursor] == DOUBLE_QUOTE || source[cursor] == SINGLE_QUOTE)) {
                    val quote = source[cursor]
                    val close = source.indexOf(quote, cursor + 1)
                    // findTagEnd 只在引号闭合的前提下返回标签边界，所以 close 必定落在标签里。
                    if (close < 0 || close > to) {
                        value = ""
                        cursor = to
                    } else {
                        value = source.substring(cursor + 1, close)
                        cursor = close + 1
                    }
                } else {
                    val valueStart = cursor
                    while (cursor < to && !source[cursor].isWhitespace() && source[cursor] != TAG_END_CHAR) cursor++
                    value = source.substring(valueStart, cursor)
                }
                attributes.putIfAbsent(name, HtmlEntities.decode(value))
            }
            return attributes
        }

        /** 属性名的结束字符：空白、`=`、`>`，以及自闭合的 `/`。 */
        private fun isAttributeNameBoundary(char: Char): Boolean =
            char.isWhitespace() || char == ATTRIBUTE_ASSIGNMENT || char == TAG_END_CHAR ||
                char == SELF_CLOSING_CHAR

        // -------------------------------------------------------------- 文本


        /** 文本先解码字符引用再入缓冲；pre 内换行即切段。 */
        private fun appendText(raw: String) {
            if (raw.isEmpty()) return
            val text = HtmlEntities.decode(raw)
            if (preDepth == 0) {
                pending.append(text)
                return
            }
            var cursor = 0
            while (cursor < text.length) {
                when (val char = text[cursor]) {
                    LINE_FEED, CARRIAGE_RETURN -> {
                        flush()
                        // CRLF 算一个换行。
                        if (char == CARRIAGE_RETURN && cursor + 1 < text.length && text[cursor + 1] == LINE_FEED) {
                            cursor++
                        }
                    }

                    else -> pending.append(char)
                }
                cursor++
            }
        }

        /** 结束当前段落：折叠空白 → 丢弃空段 → 带上当前标题层级。 */
        private fun flush() {
            if (pending.isEmpty()) return
            val text = collapseWhitespace(pending.toString())
            pending.setLength(0)
            if (text.isEmpty()) return
            out.add(TextParagraph(text, openHeadings.lastOrNull() ?: 0))
        }

        /**
         * 连续空白（空格 / 制表 / CR / LF / 换页 / 不换行空格）折叠成单个空格，
         * 段首尾的空白直接丢掉——一次扫描同时完成 trim 与段内折叠。
         */
        private fun collapseWhitespace(raw: String): String {
            val folded = StringBuilder(raw.length)
            var pendingSpace = false
            for (char in raw) {
                if (isCollapsibleSpace(char)) {
                    pendingSpace = folded.isNotEmpty()
                } else {
                    if (pendingSpace) {
                        folded.append(SPACE)
                        pendingSpace = false
                    }
                    folded.append(char)
                }
            }
            return folded.toString()
        }

        private fun isCollapsibleSpace(char: Char): Boolean = when (char) {
            SPACE, TAB, LINE_FEED, CARRIAGE_RETURN, FORM_FEED, NO_BREAK_SPACE -> true
            else -> false
        }
    }
}
