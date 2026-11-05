package app.particlebook.android.reader.epub.css

import app.particlebook.android.reader.epub.EpubAlign
import app.particlebook.android.reader.epub.EpubStyle

/** 一条 CSS 声明；property 已小写化，value 保留原始写法（去掉尾部 !important）。 */
data class CssDeclaration(
    val property: String,
    val value: String,
    val important: Boolean = false,
)

/** CSS 匹配所需的元素信息；XHTML 解析器把标签、class、id、style 属性映射到这里。 */
data class CssElement(
    val tagName: String,
    val id: String? = null,
    val classes: Set<String> = emptySet(),
    val inlineStyle: List<CssDeclaration> = emptyList(),
)

/** M0 只支持简单选择器：tag、.class、#id 及其复合（如 p.lead.note）；不支持组合器/伪类。 */
data class CssSelector(
    val tag: String? = null,
    val id: String? = null,
    val classes: Set<String> = emptySet(),
) {
    val specificity: Int
        get() = (if (id != null) 100 else 0) + classes.size * 10 + (if (tag != null) 1 else 0)

    fun matches(element: CssElement): Boolean {
        if (tag != null && !tag.equals(element.tagName, ignoreCase = true)) return false
        if (id != null && id != element.id) return false
        if (classes.any { it !in element.classes }) return false
        return true
    }
}

data class CssRule(
    val selector: CssSelector,
    val declarations: List<CssDeclaration>,
)

/**
 * 解析后的样式表。rules 保持源码顺序；styleFor 会按 important、specificity、源码顺序级联。
 * unknownProperties 只用于诊断，不参与渲染。
 */
data class CssStylesheet(
    val rules: List<CssRule> = emptyList(),
    val unknownProperties: Set<String> = emptySet(),
    val warnings: List<String> = emptyList(),
) {
    fun styleFor(element: CssElement, parentStyle: EpubStyle = EpubStyle()): EpubStyle =
        CssStyleResolver.resolve(this, element, parentStyle)

    fun mergedWith(other: CssStylesheet): CssStylesheet = CssStylesheet(
        rules = rules + other.rules,
        unknownProperties = unknownProperties + other.unknownProperties,
        warnings = warnings + other.warnings,
    )

    companion object {
        val EMPTY = CssStylesheet()
    }
}

/**
 * 极小 CSS 子集解析器：
 * - 只处理普通规则；@media/@font-face/@import 整段跳过并记 warning。
 * - 所有属性/值原样保存，由 CssStyleResolver 过滤；未知属性记入 unknownProperties。
 * - 坏规则尽量跳过，不抛异常（EPUB 里的 CSS 经常不合法）。
 */
object CssParser {
    val SUPPORTED_PROPERTIES: Set<String> = setOf(
        "font-size",
        "font-weight",
        "font-style",
        "color",
        "text-align",
        "text-indent",
        "line-height",
        "margin-top",
        "margin-bottom",
        "margin",
        "display",
        "page-break-before",
        "break-before",
    )

    fun parse(cssText: String): CssStylesheet {
        val text = stripComments(cssText)
        val rules = ArrayList<CssRule>()
        val unknown = LinkedHashSet<String>()
        val warnings = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            i = skipWhitespace(text, i)
            if (i >= text.length) break
            if (text[i] == '@') {
                val atStart = i
                val after = skipAtRule(text, i)
                if (after <= i) break
                warnings += "unsupported at-rule: " + text.substring(atStart, minOf(after, atStart + 40)).trim()
                i = after
                continue
            }
            val brace = findTopLevel(text, i, '{')
            if (brace < 0) {
                warnings += "trailing css without block"
                break
            }
            val close = findMatchingBrace(text, brace)
            if (close < 0) {
                warnings += "unclosed css block"
                break
            }
            val selectorText = text.substring(i, brace).trim()
            val declarations = parseDeclarations(text.substring(brace + 1, close), unknown, warnings)
            for (part in splitTopLevel(selectorText, ',')) {
                val selector = parseSelector(part)
                if (selector == null) {
                    if (part.isNotBlank()) warnings += "unsupported selector: " + part.trim()
                } else {
                    rules += CssRule(selector, declarations)
                }
            }
            i = close + 1
        }
        return CssStylesheet(rules, unknown, warnings)
    }

    /** 解析内联 style 属性；未知属性直接忽略（没有样式表级别的诊断出口）。 */
    fun parseInlineStyle(styleText: String?): List<CssDeclaration> {
        if (styleText.isNullOrBlank()) return emptyList()
        return parseDeclarations(styleText, null, null)
    }

    private fun parseDeclarations(
        body: String,
        unknown: MutableSet<String>?,
        warnings: MutableList<String>?,
    ): List<CssDeclaration> {
        val out = ArrayList<CssDeclaration>()
        for (raw in splitTopLevel(body, ';')) {
            val item = raw.trim()
            if (item.isEmpty()) continue
            val colon = findTopLevel(item, 0, ':')
            if (colon <= 0) {
                warnings?.add("malformed declaration: " + item.take(60))
                continue
            }
            val property = item.substring(0, colon).trim().lowercase()
            var value = item.substring(colon + 1).trim()
            if (property.isEmpty() || value.isEmpty()) continue
            var important = false
            val bang = value.lastIndexOf('!')
            if (bang >= 0) {
                val tail = value.substring(bang + 1).trim().lowercase()
                if (tail == "important") {
                    important = true
                    value = value.substring(0, bang).trim()
                }
            }
            if (value.isEmpty()) continue
            if (property !in SUPPORTED_PROPERTIES) {
                unknown?.add(property)
                continue
            }
            out += CssDeclaration(property, value, important)
        }
        return out
    }

    private fun parseSelector(raw: String): CssSelector? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        var i = 0
        var tag: String? = null
        var id: String? = null
        val classes = LinkedHashSet<String>()
        val universal = s[i] == '*'
        if (universal) {
            i++
        } else if (s[i] != '.' && s[i] != '#' && isNameChar(s[i])) {
            val start = i
            while (i < s.length && isNameChar(s[i])) i++
            tag = s.substring(start, i).lowercase()
        } else if (s[i] != '.' && s[i] != '#') {
            return null
        }
        while (i < s.length) {
            val c = s[i]
            if (c == '.') {
                i++
                val start = i
                while (i < s.length && isNameChar(s[i])) i++
                if (i == start) return null
                classes += s.substring(start, i)
            } else if (c == '#') {
                i++
                val start = i
                while (i < s.length && isNameChar(s[i])) i++
                if (i == start) return null
                id = s.substring(start, i)
            } else if (c == ' ' || c == '\t' || c == '\n' || c == '>' || c == '+' || c == '~' || c == ':') {
                return null
            } else {
                return null
            }
        }
        if (!universal && tag == null && id == null && classes.isEmpty()) return null
        return CssSelector(tag, id, classes)
    }

    private fun isNameChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '-' || c == '_' || c == '\\'

    private fun stripComments(input: String): String {
        val out = StringBuilder(input.length)
        var i = 0
        var quote = '\u0000'
        while (i < input.length) {
            val c = input[i]
            if (quote != '\u0000') {
                out.append(c)
                if (c == quote) quote = '\u0000'
                i++
                continue
            }
            if (c == '"' || c == '\'') {
                quote = c
                out.append(c)
                i++
                continue
            }
            if (c == '/' && i + 1 < input.length && input[i + 1] == '*') {
                i += 2
                while (i + 1 < input.length && !(input[i] == '*' && input[i + 1] == '/')) i++
                if (i + 1 < input.length) i += 2
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun skipAtRule(text: String, start: Int): Int {
        var i = start + 1
        while (i < text.length) {
            val c = text[i]
            if (c == ';') return i + 1
            if (c == '{') {
                val close = findMatchingBrace(text, i)
                return if (close < 0) text.length else close + 1
            }
            i++
        }
        return text.length
    }

    private fun findMatchingBrace(text: String, open: Int): Int {
        var depth = 0
        var i = open
        var quote = '\u0000'
        while (i < text.length) {
            val c = text[i]
            if (quote != '\u0000') {
                if (c == quote && text.getOrNull(i - 1) != '\\') quote = '\u0000'
                i++
                continue
            }
            if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '{') {
                depth++
            } else if (c == '}') {
                depth--
                if (depth == 0) return i
            }
            i++
        }
        return -1
    }

    /** 找到第一个不在引号/括号/方括号里的目标字符。 */
    private fun findTopLevel(text: String, start: Int, target: Char): Int {
        var i = start
        var quote = '\u0000'
        var paren = 0
        var bracket = 0
        while (i < text.length) {
            val c = text[i]
            if (quote != '\u0000') {
                if (c == quote && text.getOrNull(i - 1) != '\\') quote = '\u0000'
                i++
                continue
            }
            when (c) {
                '"', '\'' -> quote = c
                '(' -> paren++
                ')' -> if (paren > 0) paren--
                '[' -> bracket++
                ']' -> if (bracket > 0) bracket--
                target -> if (paren == 0 && bracket == 0) return i
            }
            i++
        }
        return -1
    }

    private fun splitTopLevel(text: String, delimiter: Char): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        var quote = '\u0000'
        var paren = 0
        var bracket = 0
        while (i < text.length) {
            val c = text[i]
            if (quote != '\u0000') {
                if (c == quote && text.getOrNull(i - 1) != '\\') quote = '\u0000'
                i++
                continue
            }
            when (c) {
                '"', '\'' -> quote = c
                '(' -> paren++
                ')' -> if (paren > 0) paren--
                '[' -> bracket++
                ']' -> if (bracket > 0) bracket--
                delimiter -> if (paren == 0 && bracket == 0) {
                    out += text.substring(start, i)
                    start = i + 1
                }
            }
            i++
        }
        out += text.substring(start)
        return out
    }

    private fun skipWhitespace(text: String, start: Int): Int {
        var i = start
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }
}
