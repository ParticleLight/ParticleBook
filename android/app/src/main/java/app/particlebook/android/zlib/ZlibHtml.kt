package app.particlebook.android.zlib

/** 极简 HTML 文本工具（纯 JVM，可单测；不引入解析器依赖）。 */
internal object ZlibHtml {

    private val TAG = Regex("<[^>]*>")
    private val ENTITY = Regex("&(#x[0-9A-Fa-f]+|#[0-9]+|[A-Za-z]{2,10});")
    private val SPACES = Regex("\\s+")
    private val ATTR = Regex("""([A-Za-z_][-A-Za-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    /** 标签头（<tag ...>）→ 属性 Map；值做实体解码并 trim（P1.5 各解析器共用）。 */
    fun parseAttributes(tagHead: String): Map<String, String> {
        val result = HashMap<String, String>()
        for (match in ATTR.findAll(tagHead)) {
            val name = match.groupValues[1].lowercase()
            val raw = if (match.groupValues[2].isNotEmpty()) match.groupValues[2] else match.groupValues[3]
            result[name] = decodeEntities(raw).trim()
        }
        return result
    }

    /** 裸属性判定（notlogged / short_description / editorschoice 等，无 =）。 */
    fun hasBareAttribute(tagHead: String, name: String): Boolean =
        // 注意要允许「属性正好在标签头末尾」：调用方传的 tagHead 可能不含 `>`，
        // 否则最后一个裸属性（如 editorschoice）永远判不到。
        Regex("\\s" + Regex.escape(name) + "(?=[\\s>/]|$)", RegexOption.IGNORE_CASE)
            .containsMatchIn(tagHead)

    /** 去掉标签（保留文本）。 */
    fun stripTags(html: String): String = html.replace(TAG, " ")

    /** 实体解码（&amp; / &#39; / &#x27; / &nbsp; 等常用项）。 */
    fun decodeEntities(text: String): String = ENTITY.replace(text) { match ->
        val entity = match.groupValues[1]
        when {
            entity.startsWith("#x", ignoreCase = true) ->
                entity.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: match.value
            entity.startsWith("#") ->
                entity.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: match.value
            else -> when (entity.lowercase()) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                "nbsp" -> " "
                else -> match.value
            }
        }
    }

    /** 去标签 + 解码 + 折叠空白。 */
    fun clean(html: String): String = decodeEntities(stripTags(html)).replace(SPACES, " ").trim()
}
