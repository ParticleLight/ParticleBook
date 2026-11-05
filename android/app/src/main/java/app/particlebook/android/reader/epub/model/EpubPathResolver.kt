package app.particlebook.android.reader.epub.model

/**
 * 把章节内的相对 href 归一化为 EPUB 根相对路径。
 *
 * 例：chapterHref = OEBPS/Text/ch1.xhtml，src = ../Images/a.png
 *     -> OEBPS/Images/a.png
 * 绝对 URL（http/https/data/...）原样返回；fragment/query 在相对路径上会被去掉。
 * 这是 parser/layout 找 zip 条目的统一口径。
 */
object EpubPathResolver {

    fun resolve(chapterHref: String, href: String): String {
        val target = href.trim()
        if (target.isEmpty()) return baseDirectory(chapterHref)
        if (isAbsoluteUrl(target)) return target
        if (target.startsWith("//")) return target
        val path = target.substringBefore('#').substringBefore('?')
        if (path.isEmpty()) return baseDirectory(chapterHref)
        if (path.startsWith("/")) return normalize(path)
        val base = baseDirectory(chapterHref)
        return normalize(if (base.isEmpty()) path else base + "/" + path)
    }

    fun baseDirectory(chapterHref: String): String {
        val clean = chapterHref.substringBefore('#').substringBefore('?').trim()
        val slash = clean.lastIndexOf('/')
        return if (slash < 0) "" else clean.substring(0, slash)
    }

    fun normalize(path: String): String {
        val cleaned = path.replace('\\', '/').substringBefore('#').substringBefore('?')
        val prefix = if (cleaned.startsWith("/")) "/" else ""
        val parts = cleaned.split('/')
        val stack = ArrayList<String>()
        for (part in parts) {
            when (part) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                else -> stack += part
            }
        }
        val joined = stack.joinToString("/")
        return if (prefix.isNotEmpty()) "/" + joined else joined
    }

    private fun isAbsoluteUrl(value: String): Boolean {
        val colon = value.indexOf(':')
        if (colon <= 0) return false
        for (i in 0 until colon) {
            val c = value[i]
            val ok = if (i == 0) c.isLetter() else c.isLetterOrDigit() || c == '+' || c == '-' || c == '.'
            if (!ok) return false
        }
        return true
    }
}
