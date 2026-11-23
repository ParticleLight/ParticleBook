package app.particlebook.android.ui.about

/**
 * 本地 JVM 单测用的最小 JSON 读取实现。
 *
 * 生产实现 [OrgJsonAboutCodec] 依赖 Android 的 org.json，而本地单测里 android.jar 的 org.json
 * 是 "not mocked"：调用即抛 RuntimeException（与 test 里 `TestIndexCodec` 记录的同一个坑，
 * 那个文件用同名字段给 `LibraryIndexCodec` 做了同样的注入）。
 * 所以单测注入这个同接口实现：解析管线、模型映射、分组过滤都走生产代码，
 * 只有「把文本变成 Map」这一步换成纯 Kotlin 的小解析器。
 *
 * 只支持本页 JSON 需要的子集（对象 / 数组 / 字符串 / 数字 / 布尔 / null），坏输入直接抛异常——
 * 这样 [AboutJson] 的「失败返回空表」才有可测的失败来源。
 */
internal class TestAboutJsonCodec : AboutJsonCodec {

    override fun readObjectArray(json: String, arrayKey: String): List<Map<String, Any?>> {
        val root = MiniJson(json).readDocument() as? Map<*, *>
            ?: throw IllegalArgumentException("根节点不是对象")
        val rows = root[arrayKey] as? List<*> ?: return emptyList()
        return rows.mapNotNull { row ->
            val fields = row as? Map<*, *> ?: return@mapNotNull null
            fields.entries.associate { (key, value) -> key.toString() to value }
        }
    }
}

/** 递归下降的最小 JSON 读取器；定位信息够用来读测试失败信息即可。 */
private class MiniJson(private val text: String) {
    private var index = 0

    fun readDocument(): Any? {
        val value = readValue()
        skipWhitespace()
        if (index != text.length) throw IllegalArgumentException("JSON 末尾有多余内容（位置 " + index + "）")
        return value
    }

    private fun readValue(): Any? {
        skipWhitespace()
        return when (current()) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            't' -> readLiteral("true", true)
            'f' -> readLiteral("false", false)
            'n' -> readLiteral("null", null)
            else -> readNumber()
        }
    }

    private fun readObject(): Map<String, Any?> {
        expect('{')
        val out = LinkedHashMap<String, Any?>()
        skipWhitespace()
        if (current() == '}') {
            index += 1
            return out
        }
        while (true) {
            skipWhitespace()
            val key = readString()
            expect(':')
            out[key] = readValue()
            skipWhitespace()
            when (current()) {
                ',' -> index += 1
                '}' -> {
                    index += 1
                    return out
                }
                else -> throw IllegalArgumentException("对象里缺少 , 或 }（位置 " + index + "）")
            }
        }
    }

    private fun readArray(): List<Any?> {
        expect('[')
        val out = ArrayList<Any?>()
        skipWhitespace()
        if (current() == ']') {
            index += 1
            return out
        }
        while (true) {
            out.add(readValue())
            skipWhitespace()
            when (current()) {
                ',' -> index += 1
                ']' -> {
                    index += 1
                    return out
                }
                else -> throw IllegalArgumentException("数组里缺少 , 或 ]（位置 " + index + "）")
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            if (index >= text.length) throw IllegalArgumentException("字符串没有闭合")
            when (val char = text[index++]) {
                '"' -> return out.toString()
                '\\' -> {
                    if (index >= text.length) throw IllegalArgumentException("转义没有闭合")
                    when (val escape = text[index++]) {
                        '"', '\\', '/' -> out.append(escape)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (index + 4 > text.length) throw IllegalArgumentException("\\u 转义不完整")
                            out.append(text.substring(index, index + 4).toInt(16).toChar())
                            index += 4
                        }
                        else -> throw IllegalArgumentException("未知转义 \\" + escape)
                    }
                }
                else -> out.append(char)
            }
        }
    }

    private fun readNumber(): Double {
        val start = index
        while (index < text.length && (text[index].isDigit() || text[index] in "-+.eE")) index += 1
        if (start == index) throw IllegalArgumentException("不是合法的数字（位置 " + start + "）")
        return text.substring(start, index).toDouble()
    }

    private fun readLiteral(literal: String, value: Any?): Any? {
        if (!text.startsWith(literal, index)) throw IllegalArgumentException("未知字面量（位置 " + index + "）")
        index += literal.length
        return value
    }

    private fun expect(char: Char) {
        skipWhitespace()
        if (index >= text.length || text[index] != char) {
            throw IllegalArgumentException("期望 '" + char + "'（位置 " + index + "）")
        }
        index += 1
    }

    private fun current(): Char {
        if (index >= text.length) throw IllegalArgumentException("JSON 意外结束")
        return text[index]
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) index += 1
    }
}
