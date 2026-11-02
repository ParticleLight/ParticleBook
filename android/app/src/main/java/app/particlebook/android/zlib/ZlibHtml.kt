package app.particlebook.android.zlib

/** 极简 HTML 文本工具（纯 JVM，可单测；不引入解析器依赖）。 */
internal object ZlibHtml {

    private val TAG = Regex("<[^>]*>")
    private val ENTITY = Regex("&(#x[0-9A-Fa-f]+|#[0-9]+|[A-Za-z]{2,10});")
    private val SPACES = Regex("\\s+")

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
