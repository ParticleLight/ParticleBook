package app.particlebook.android.reader.epub.parser

/**
 * EPUB 内部路径工具。
 *
 * 约定：解析器对外暴露的所有 href 都是 ZIP 根相对路径（不带开头的 /），
 * 例如 `OEBPS/Text/ch1.xhtml`、`OEBPS/Images/cover.jpg`。
 * TOC 的 href 允许保留 fragment：`OEBPS/Text/ch1.xhtml#sec1`。
 * 解析器内部对链接做百分号解码（href 里常见 %20），ZIP 条目名按原样匹配；
 * 两者不一致时 EpubArchive 会再尝试一次未解码的名字。
 */
internal object EpubPaths {

    fun splitFragment(href: String): Pair<String, String?> {
        val hash = href.indexOf('#')
        if (hash < 0) return href to null
        return href.substring(0, hash) to href.substring(hash + 1).ifEmpty { null }
    }

    /** 去掉 query/fragment，按需百分号解码，并做路径归一化。 */
    fun normalizePath(raw: String, decodePercent: Boolean = true): String {
        var value = raw.trim()
        val hash = value.indexOf('#')
        if (hash >= 0) value = value.substring(0, hash)
        val query = value.indexOf('?')
        if (query >= 0) value = value.substring(0, query)
        if (decodePercent) value = percentDecode(value)
        value = value.replace('\\', '/')
        if (value.startsWith("/")) value = value.removePrefix("/")
        val out = ArrayList<String>()
        for (segment in value.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> {
                    if (out.isEmpty()) throw EpubParseException("PATH_ESCAPE: $raw")
                    out.removeAt(out.lastIndex)
                }
                else -> out += segment
            }
        }
        return out.joinToString("/")
    }

    /** 把引用解析成 ZIP 根相对路径；baseDocHref 是当前文档的根相对路径。 */
    fun resolve(baseDocHref: String, reference: String): String {
        val (rawPath, _) = splitFragment(reference.trim())
        if (rawPath.isEmpty()) return normalizePath(baseDocHref)
        if (isExternal(rawPath)) return rawPath
        val decoded = percentDecode(rawPath).replace('\\', '/')
        if (decoded.startsWith("/")) return normalizePath(decoded)
        val baseDir = baseDocHref.substringBeforeLast('/', "")
        return normalizePath(if (baseDir.isEmpty()) decoded else "$baseDir/$decoded")
    }

    /** 解析并保留 fragment，用于 TOC。 */
    fun resolveWithFragment(baseDocHref: String, reference: String): String {
        val (rawPath, fragment) = splitFragment(reference.trim())
        val path = if (rawPath.isEmpty()) normalizePath(baseDocHref) else resolve(baseDocHref, rawPath)
        return if (fragment.isNullOrEmpty()) path else "$path#$fragment"
    }

    fun withoutFragment(href: String): String = splitFragment(href).first

    fun isExternal(href: String): Boolean {
        val lower = href.trim().lowercase()
        return lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("data:") ||
            lower.startsWith("mailto:") ||
            lower.startsWith("tel:") ||
            lower.startsWith("javascript:")
    }

    fun percentDecode(input: String): String {
        if ('%' !in input) return input
        val out = StringBuilder(input.length)
        val pending = java.io.ByteArrayOutputStream()
        fun flushBytes() {
            if (pending.size() == 0) return
            out.append(String(pending.toByteArray(), Charsets.UTF_8))
            pending.reset()
        }
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '%' && i + 2 < input.length) {
                val hi = hexValue(input[i + 1])
                val lo = hexValue(input[i + 2])
                if (hi >= 0 && lo >= 0) {
                    pending.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            flushBytes()
            out.append(c)
            i++
        }
        flushBytes()
        return out.toString()
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
