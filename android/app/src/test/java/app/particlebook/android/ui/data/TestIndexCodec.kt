package app.particlebook.android.ui.data

/**
 * 单测专用最小 JSON 实现。
 *
 * 本地 JVM 单测里 android.jar 的 org.json 全部是 “not mocked”（实测：
 * `Method getJSONArray in org.json.JSONObject not mocked`），所以生产的
 * [JsonLibraryIndexCodec] 无法在纯 JVM 单测里运行。这里用一个最小 JSON 实现写/读与生产完全相同的
 * books.json 格式，保证单测断言的是磁盘上的真实 JSON 文本，而不是内存对象。
 */
internal object TestJson {

    fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (ch.code < 0x20) {
                    append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    append(ch)
                }
            }
        }
        append('"')
    }

    fun parse(text: String): Any? = Parser(text).parseDocument()

    fun asObject(value: Any?, what: String): Map<String, Any?> =
        value as? Map<String, Any?> ?: throw IllegalArgumentException(what + " 不是 JSON 对象")

    fun asArray(value: Any?, what: String): List<Any?> =
        value as? List<Any?> ?: throw IllegalArgumentException(what + " 不是 JSON 数组")

    private class Parser(private val text: String) {
        private var pos = 0

        fun parseDocument(): Any? {
            skipWhitespace()
            val value = parseValue()
            skipWhitespace()
            if (pos != text.length) fail("JSON 尾部有多余内容 @" + pos)
            return value
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            if (pos >= text.length) fail("JSON 意外结束")
            return when (val ch = text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> if (ch == '-' || ch in '0'..'9') parseNumber() else fail("非法 JSON 字符 '" + ch + "' @" + pos)
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return out
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                out[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return out
                    }
                    else -> fail("对象分隔符非法 @" + pos)
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return out
            }
            while (true) {
                out += parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return out
                    }
                    else -> fail("数组分隔符非法 @" + pos)
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (pos >= text.length) fail("字符串未闭合")
                val ch = text[pos++]
                when {
                    ch == '"' -> return out.toString()
                    ch == '\\' -> {
                        if (pos >= text.length) fail("转义未完成")
                        when (val escaped = text[pos++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) fail("\\\\u 转义不足 4 位")
                                val hex = text.substring(pos, pos + 4)
                                pos += 4
                                out.append(hex.toInt(16).toChar())
                            }
                            else -> fail("非法转义 \\" + escaped)
                        }
                    }
                    ch.code < 0x20 -> fail("字符串内含未转义控制字符")
                    else -> out.append(ch)
                }
            }
        }

        private fun parseNumber(): Any {
            val start = pos
            if (peek() == '-') pos++
            while (pos < text.length && text[pos] in '0'..'9') pos++
            var integral = true
            if (pos < text.length && text[pos] == '.') {
                integral = false
                pos++
                while (pos < text.length && text[pos] in '0'..'9') pos++
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                integral = false
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                while (pos < text.length && text[pos] in '0'..'9') pos++
            }
            val raw = text.substring(start, pos)
            if (raw.isEmpty() || raw == "-") fail("非法数字 @" + start)
            return if (integral) raw.toLong() else raw.toDouble()
        }

        private fun parseLiteral(literal: String, value: Any?): Any? {
            if (!text.startsWith(literal, pos)) fail("非法字面量 @" + pos)
            pos += literal.length
            return value
        }

        private fun expect(expected: Char) {
            skipWhitespace()
            if (pos >= text.length || text[pos] != expected) fail("期望 '" + expected + "' @" + pos)
            pos++
        }

        private fun peek(): Char? = if (pos < text.length) text[pos] else null

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
    }
}

/**
 * 单测专用索引编解码：输出与生产 [JsonLibraryIndexCodec] 相同的 books.json 文本
 * （version=1 + books[]，camelCase 字段），便于断言「条目数正确且 JSON 可解析」。
 */
internal class TestIndexCodec : LibraryIndexCodec {

    override fun encode(books: List<Book>): String = buildString {
        append("{\"version\":1,\"books\":[")
        books.forEachIndexed { index, book ->
            if (index > 0) append(',')
            append("{\"id\":").append(book.id)
            append(",\"title\":").append(TestJson.quote(book.title))
            append(",\"author\":").append(book.author?.let(TestJson::quote) ?: "null")
            append(",\"format\":")
                .append(TestJson.quote(if (book.format == BookFormat.Pdf) "pdf" else "epub"))
            append(",\"filePath\":").append(TestJson.quote(book.filePath))
            append(",\"fileSize\":").append(book.fileSize)
            append(",\"addedAt\":").append(book.addedAt?.let(TestJson::quote) ?: "null")
            append(",\"tags\":[")
            book.tags.forEachIndexed { tagIndex, tag ->
                if (tagIndex > 0) append(',')
                append(TestJson.quote(tag))
            }
            append("]}")
        }
        append("]}")
    }

    override fun decode(json: String): List<Book> {
        val root = TestJson.asObject(TestJson.parse(json), "books.json 根节点")
        val array = TestJson.asArray(root["books"], "books.json.books")
        return array.map { raw ->
            val item = TestJson.asObject(raw, "books[] 条目")
            val filePath = item["filePath"] as? String
                ?: throw IllegalArgumentException("books[] 缺少 filePath")
            Book(
                id = item["id"] as? Long ?: throw IllegalArgumentException("books[] 缺少 id"),
                title = item["title"] as? String ?: "未命名",
                author = item["author"] as? String,
                format = BookFormat.fromRaw(item["format"] as? String),
                filePath = filePath,
                fileSize = item["fileSize"] as? Long ?: 0L,
                addedAt = item["addedAt"] as? String,
                tags = (item["tags"]?.let { TestJson.asArray(it, "tags") } ?: emptyList())
                    .map { it as String },
            )
        }
    }
}
