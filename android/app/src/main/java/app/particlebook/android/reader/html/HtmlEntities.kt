package app.particlebook.android.reader.html

/**
 * HTML 字符引用解码：命名实体、十进制与十六进制数字引用。
 *
 * 只做「文本抽取」需要的那一小步：把 `&amp;` 这类引用还原成字符，供正文渲染与搜索。
 * 三条容错规则：
 * - 未知实体、超长引用、缺分号的引用一律原样保留（宁可显示 `&foo;`，不吃掉作者的字）；
 * - 数字引用非法（越界、代理区、0）也原样保留；
 * - 引用之间不递归解码，`&amp;lt;` 还原成字面量 `&lt;`，与浏览器的单遍解码一致。
 */
internal object HtmlEntities {
    /** 引用体的长度上限（`&#x1F600;` 这类最多这么长），超长说明是正文里的普通 `&` 而不是引用。 */
    private const val MAX_REFERENCE_LENGTH = 32

    private const val DECIMAL_PREFIX = "#"
    private const val HEX_MARKER = "x"

    private val NAMED: Map<String, String> = mapOf(
        "amp" to "&", "AMP" to "&",
        "lt" to "<", "LT" to "<",
        "gt" to ">", "GT" to ">",
        "quot" to "\"", "QUOT" to "\"",
        "apos" to "'",
        "nbsp" to "\u00A0", "ensp" to "\u2002", "emsp" to "\u2003", "thinsp" to "\u2009",
        "shy" to "\u00AD", "zwj" to "\u200D", "zwnj" to "\u200C",
        "ndash" to "\u2013", "mdash" to "\u2014", "hellip" to "\u2026",
        "lsquo" to "\u2018", "rsquo" to "\u2019", "sbquo" to "\u201A",
        "ldquo" to "\u201C", "rdquo" to "\u201D", "bdquo" to "\u201E",
        "laquo" to "\u00AB", "raquo" to "\u00BB", "bull" to "\u2022",
        "middot" to "\u00B7", "prime" to "\u2032", "Prime" to "\u2033",
        "copy" to "\u00A9", "reg" to "\u00AE", "trade" to "\u2122",
        "deg" to "\u00B0", "plusmn" to "\u00B1", "times" to "\u00D7", "divide" to "\u00F7",
        "frac12" to "\u00BD", "frac14" to "\u00BC", "frac34" to "\u00BE",
        "sup1" to "\u00B9", "sup2" to "\u00B2", "sup3" to "\u00B3",
        "micro" to "\u00B5", "para" to "\u00B6", "sect" to "\u00A7",
        "dagger" to "\u2020", "Dagger" to "\u2021", "permil" to "\u2030",
        "euro" to "\u20AC", "pound" to "\u00A3", "yen" to "\u00A5", "cent" to "\u00A2", "curren" to "\u00A4",
        "iexcl" to "\u00A1", "iquest" to "\u00BF", "not" to "\u00AC", "macr" to "\u00AF",
        "acute" to "\u00B4", "cedil" to "\u00B8", "ordf" to "\u00AA", "ordm" to "\u00BA",
        "larr" to "\u2190", "uarr" to "\u2191", "rarr" to "\u2192", "darr" to "\u2193", "harr" to "\u2194",
        "lArr" to "\u21D0", "rArr" to "\u21D2", "hArr" to "\u21D4",
        "forall" to "\u2200", "part" to "\u2202", "exist" to "\u2203", "empty" to "\u2205",
        "nabla" to "\u2207", "isin" to "\u2208", "notin" to "\u2209", "ni" to "\u220B",
        "prod" to "\u220F", "sum" to "\u2211", "minus" to "\u2212", "lowast" to "\u2217",
        "radic" to "\u221A", "prop" to "\u221D", "infin" to "\u221E", "ang" to "\u2220",
        "and" to "\u2227", "or" to "\u2228", "cap" to "\u2229", "cup" to "\u222A",
        "int" to "\u222B", "there4" to "\u2234", "sim" to "\u223C", "cong" to "\u2245",
        "asymp" to "\u2248", "ne" to "\u2260", "equiv" to "\u2261", "le" to "\u2264", "ge" to "\u2265",
        "sub" to "\u2282", "sup" to "\u2283", "nsub" to "\u2284", "sube" to "\u2286", "supe" to "\u2287",
        "oplus" to "\u2295", "otimes" to "\u2297", "perp" to "\u22A5", "sdot" to "\u22C5",
        "lceil" to "\u2308", "rceil" to "\u2309", "lfloor" to "\u230A", "rfloor" to "\u230B",
        "lang" to "\u2329", "rang" to "\u232A", "loz" to "\u25CA", "spades" to "\u2660",
        "clubs" to "\u2663", "hearts" to "\u2665", "diams" to "\u2666",
    )

    fun decode(raw: String): String {
        val firstAmp = raw.indexOf('&')
        if (firstAmp < 0) return raw
        val out = StringBuilder(raw.length)
        out.append(raw, 0, firstAmp)
        var index = firstAmp
        while (index < raw.length) {
            val ch = raw[index]
            if (ch != '&') {
                out.append(ch)
                index += 1
                continue
            }
            // 引用体长度 = 分号位置 - & 位置 - 1；超长说明这是正文里的普通 & 而不是引用。
            val semicolon = raw.indexOf(';', index + 1)
            if (semicolon < 0 || semicolon - index - 1 > MAX_REFERENCE_LENGTH) {
                out.append(ch)
                index += 1
                continue
            }
            val decoded = decodeReference(raw.substring(index + 1, semicolon))
            if (decoded == null) {
                out.append(ch)
                index += 1
            } else {
                out.append(decoded)
                index = semicolon + 1
            }
        }
        return out.toString()
    }

    /** 引用体 → 字符；认不出返回 null（调用方保留原文）。 */
    private fun decodeReference(body: String): String? {
        if (body.isEmpty()) return null
        if (body.startsWith(DECIMAL_PREFIX)) {
            val spec = body.substring(1)
            val hexadecimal = spec.startsWith(HEX_MARKER, ignoreCase = true)
            val digits = if (hexadecimal) spec.substring(1) else spec
            if (digits.isEmpty()) return null
            // 数字引用只认纯数字：&#+65; / &# 65; 这类宽松解析在 HTML 里不是合法引用，原样保留。
            val digitsValid = if (hexadecimal) {
                digits.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
            } else {
                digits.all { it in '0'..'9' }
            }
            if (!digitsValid) return null
            val codePoint = if (hexadecimal) digits.toIntOrNull(radix = 16) else digits.toIntOrNull(radix = 10)
            return codePointToString(codePoint ?: return null)
        }
        NAMED[body]?.let { return it }
        return null
    }

    /** 合法码点才转成字符串：排除越界、0 与 UTF-16 代理区（单独的代理不是字符）。 */
    private fun codePointToString(codePoint: Int): String? {
        if (codePoint <= 0) return null
        if (codePoint > Character.MAX_CODE_POINT) return null
        if (codePoint in 0xD800..0xDFFF) return null
        return String(Character.toChars(codePoint))
    }
}
