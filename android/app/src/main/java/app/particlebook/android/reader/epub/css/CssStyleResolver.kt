package app.particlebook.android.reader.epub.css

import app.particlebook.android.reader.epub.EpubAlign
import app.particlebook.android.reader.epub.EpubStyle

/**
 * 把 CSS 声明级联成 EpubStyle。
 *
 * 继承规则：font-size / font-weight / font-style / color / text-align / text-indent /
 * line-height 继承；margin、display、page-break-before 不继承。
 * 长度单位映射：px 与 dp 1:1；pt = 96/72 px；em 取当前字号；rem 取 16sp。
 * 这些是 M0 的逻辑单位约定，最终像素换算由 layout 层处理。
 */
internal object CssStyleResolver {

    private data class CascadeItem(
        val specificity: Int,
        val order: Int,
        val declaration: CssDeclaration,
    )

    fun resolve(
        sheet: CssStylesheet,
        element: CssElement,
        parent: EpubStyle,
    ): EpubStyle {
        val items = ArrayList<CascadeItem>()
        for ((index, rule) in sheet.rules.withIndex()) {
            if (!rule.selector.matches(element)) continue
            for (declaration in rule.declarations) {
                items += CascadeItem(rule.selector.specificity, index, declaration)
            }
        }
        for ((index, declaration) in element.inlineStyle.withIndex()) {
            items += CascadeItem(1000, 1_000_000 + index, declaration)
        }
        items.sortWith(
            compareBy<CascadeItem>(
                { if (it.declaration.important) 1 else 0 },
                { it.specificity },
                { it.order },
            ),
        )

        var style = inherited(parent)
        for (item in items) {
            if (item.declaration.property == "font-size") {
                style = apply(style, item.declaration, parent)
            }
        }
        for (item in items) {
            if (item.declaration.property != "font-size") {
                style = apply(style, item.declaration, parent)
            }
        }
        return style
    }

    private fun inherited(parent: EpubStyle): EpubStyle = EpubStyle(
        fontSizeSp = parent.fontSizeSp,
        bold = parent.bold,
        italic = parent.italic,
        color = parent.color,
        align = parent.align,
        indentEm = parent.indentEm,
        lineHeightMultiplier = parent.lineHeightMultiplier,
        marginTopDp = 0f,
        marginBottomDp = 0f,
        hidden = false,
        pageBreakBefore = false,
    )

    private fun apply(style: EpubStyle, declaration: CssDeclaration, parent: EpubStyle): EpubStyle {
        val value = declaration.value.trim()
        if (value.isEmpty()) return style
        return when (declaration.property) {
            "font-size" -> {
                when (value.lowercase()) {
                    "inherit", "unset" -> style
                    "initial" -> style.copy(fontSizeSp = null)
                    else -> {
                        val resolved = resolveFontSize(value, style.fontSizeSp ?: parent.fontSizeSp)
                        if (resolved == null) style else style.copy(fontSizeSp = resolved)
                    }
                }
            }

            "font-weight" -> when (value.lowercase()) {
                "inherit", "unset" -> style
                "bold", "bolder" -> style.copy(bold = true)
                "normal", "lighter" -> style.copy(bold = false)
                else -> {
                    val weight = value.toIntOrNull()
                    if (weight == null) style else style.copy(bold = weight >= 600)
                }
            }

            "font-style" -> when (value.lowercase()) {
                "italic", "oblique" -> style.copy(italic = true)
                "normal" -> style.copy(italic = false)
                else -> style
            }

            "color" -> {
                if (value.equals("inherit", ignoreCase = true) ||
                    value.equals("currentcolor", ignoreCase = true)
                ) {
                    style
                } else {
                    parseColor(value)?.let { style.copy(color = it) } ?: style
                }
            }

            "text-align" -> {
                val align = when (value.lowercase()) {
                    "left", "start" -> EpubAlign.START
                    "center" -> EpubAlign.CENTER
                    "right", "end" -> EpubAlign.END
                    "justify" -> EpubAlign.JUSTIFY
                    else -> null
                }
                if (align == null) style else style.copy(align = align)
            }

            "text-indent" -> {
                if (value.equals("inherit", ignoreCase = true)) {
                    style
                } else {
                    val fontSize = style.fontSizeSp ?: 16f
                    val px = parseLengthPx(value, fontSize)
                    if (px == null) style else style.copy(indentEm = px / fontSize)
                }
            }

            "line-height" -> when {
                value.equals("inherit", ignoreCase = true) -> style
                value.equals("normal", ignoreCase = true) -> style.copy(lineHeightMultiplier = null)
                value.equals("initial", ignoreCase = true) -> style.copy(lineHeightMultiplier = null)
                value.endsWith("%") -> {
                    val n = value.dropLast(1).toFloatOrNull()
                    if (n == null) style else style.copy(lineHeightMultiplier = n / 100f)
                }
                value.endsWith("rem") -> {
                    val n = value.dropLast(3).toFloatOrNull()
                    if (n == null) style else style.copy(lineHeightMultiplier = n)
                }
                value.endsWith("em") -> {
                    val n = value.dropLast(2).toFloatOrNull()
                    if (n == null) style else style.copy(lineHeightMultiplier = n)
                }
                else -> {
                    val n = value.toFloatOrNull()
                    if (n != null) {
                        style.copy(lineHeightMultiplier = n)
                    } else {
                        val fontSize = style.fontSizeSp ?: 16f
                        val px = parseLengthPx(value, fontSize)
                        if (px == null) style else style.copy(lineHeightMultiplier = px / fontSize)
                    }
                }
            }

            "margin-top" -> {
                val fontSize = style.fontSizeSp ?: 16f
                val px = parseLengthPx(value, fontSize)
                if (px == null) style else style.copy(marginTopDp = px)
            }

            "margin-bottom" -> {
                val fontSize = style.fontSizeSp ?: 16f
                val px = parseLengthPx(value, fontSize)
                if (px == null) style else style.copy(marginBottomDp = px)
            }

            "margin" -> applyMarginShorthand(style, value)

            "display" -> when (value.lowercase()) {
                "none" -> style.copy(hidden = true)
                "block", "inline", "inline-block", "list-item", "table", "table-row", "table-cell" ->
                    style.copy(hidden = false)
                else -> style
            }

            "page-break-before", "break-before" -> when (value.lowercase()) {
                "always", "page", "left", "right" -> style.copy(pageBreakBefore = true)
                "auto", "avoid", "avoid-page" -> style.copy(pageBreakBefore = false)
                else -> style
            }

            else -> style
        }
    }

    private fun applyMarginShorthand(style: EpubStyle, value: String): EpubStyle {
        val parts = value.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return style
        val fontSize = style.fontSizeSp ?: 16f
        val topValue = parts[0]
        val bottomValue = when (parts.size) {
            1, 2 -> parts[0]
            3 -> parts[2]
            else -> parts.getOrElse(2) { parts[0] }
        }
        val top = parseLengthPx(topValue, fontSize) ?: style.marginTopDp
        val bottom = parseLengthPx(bottomValue, fontSize) ?: style.marginBottomDp
        return style.copy(marginTopDp = top, marginBottomDp = bottom)
    }

    private fun resolveFontSize(value: String, base: Float?): Float? {
        val v = value.trim().lowercase()
        when (v) {
            "xx-small" -> return 9f
            "x-small" -> return 10f
            "small" -> return 13f
            "medium" -> return 16f
            "large" -> return 18f
            "x-large" -> return 24f
            "xx-large" -> return 32f
            "smaller" -> return (base ?: 16f) * 0.833f
            "larger" -> return (base ?: 16f) * 1.2f
        }
        val current = base ?: 16f
        if (v.endsWith("%")) {
            val n = v.dropLast(1).toFloatOrNull() ?: return null
            return current * n / 100f
        }
        if (v.endsWith("rem")) {
            val n = v.dropLast(3).toFloatOrNull() ?: return null
            return n * 16f
        }
        if (v.endsWith("em")) {
            val n = v.dropLast(2).toFloatOrNull() ?: return null
            return current * n
        }
        return parseAbsoluteLengthPx(v) ?: v.toFloatOrNull()
    }

    private fun parseLengthPx(value: String, fontSize: Float): Float? {
        val v = value.trim().lowercase()
        if (v == "0") return 0f
        if (v.endsWith("%")) {
            val n = v.dropLast(1).toFloatOrNull() ?: return null
            return fontSize * n / 100f
        }
        if (v.endsWith("rem")) {
            val n = v.dropLast(3).toFloatOrNull() ?: return null
            return n * 16f
        }
        if (v.endsWith("em")) {
            val n = v.dropLast(2).toFloatOrNull() ?: return null
            return n * fontSize
        }
        if (v.endsWith("ex")) {
            val n = v.dropLast(2).toFloatOrNull() ?: return null
            return n * fontSize / 2f
        }
        if (v.endsWith("ch")) {
            val n = v.dropLast(2).toFloatOrNull() ?: return null
            return n * fontSize / 2f
        }
        return parseAbsoluteLengthPx(v)
    }

    private fun parseAbsoluteLengthPx(v: String): Float? = when {
        v.endsWith("px") -> v.dropLast(2).toFloatOrNull()
        v.endsWith("pt") -> v.dropLast(2).toFloatOrNull()?.times(96f / 72f)
        v.endsWith("pc") -> v.dropLast(2).toFloatOrNull()?.times(16f)
        v.endsWith("in") -> v.dropLast(2).toFloatOrNull()?.times(96f)
        v.endsWith("cm") -> v.dropLast(2).toFloatOrNull()?.times(96f / 2.54f)
        v.endsWith("mm") -> v.dropLast(2).toFloatOrNull()?.times(96f / 25.4f)
        v.toFloatOrNull() != null -> v.toFloatOrNull()
        else -> null
    }

    private fun parseColor(value: String): Int? {
        val v = value.trim().lowercase()
        when (v) {
            "transparent" -> return 0x00000000
            "black" -> return 0xFF000000.toInt()
            "white" -> return 0xFFFFFFFF.toInt()
            "red" -> return 0xFFFF0000.toInt()
            "green" -> return 0xFF008000.toInt()
            "blue" -> return 0xFF0000FF.toInt()
            "lime" -> return 0xFF00FF00.toInt()
            "yellow" -> return 0xFFFFFF00.toInt()
            "orange" -> return 0xFFFFA500.toInt()
            "purple" -> return 0xFF800080.toInt()
            "gray", "grey" -> return 0xFF808080.toInt()
            "silver" -> return 0xFFC0C0C0.toInt()
            "maroon" -> return 0xFF800000.toInt()
            "navy" -> return 0xFF000080.toInt()
            "teal" -> return 0xFF008080.toInt()
            "olive" -> return 0xFF808000.toInt()
            "aqua" -> return 0xFF00FFFF.toInt()
            "fuchsia" -> return 0xFFFF00FF.toInt()
        }
        if (v.startsWith("#")) {
            val hex = v.substring(1)
            return when (hex.length) {
                3 -> parseHexRgb(hex[0], hex[0], hex[1], hex[1], hex[2], hex[2], 'f', 'f')
                4 -> parseHexRgb(hex[0], hex[0], hex[1], hex[1], hex[2], hex[2], hex[3], hex[3])
                6 -> parseHexRgb(hex[0], hex[1], hex[2], hex[3], hex[4], hex[5], 'f', 'f')
                8 -> parseHexRgb(hex[0], hex[1], hex[2], hex[3], hex[4], hex[5], hex[6], hex[7])
                else -> null
            }
        }
        if (v.startsWith("rgb(") || v.startsWith("rgba(")) {
            val open = v.indexOf('(')
            val close = v.lastIndexOf(')')
            if (open < 0 || close <= open) return null
            val parts = v.substring(open + 1, close).split(',').map { it.trim() }
            if (parts.size < 3) return null
            val r = parseChannel(parts[0]) ?: return null
            val g = parseChannel(parts[1]) ?: return null
            val b = parseChannel(parts[2]) ?: return null
            val a = if (parts.size >= 4) parseAlpha(parts[3]) ?: 255 else 255
            return ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
        }
        return null
    }

    private fun parseHexRgb(
        rHigh: Char,
        rLow: Char,
        gHigh: Char,
        gLow: Char,
        bHigh: Char,
        bLow: Char,
        aHigh: Char,
        aLow: Char,
    ): Int? {
        val r = hexPair(rHigh, rLow) ?: return null
        val g = hexPair(gHigh, gLow) ?: return null
        val b = hexPair(bHigh, bLow) ?: return null
        val a = hexPair(aHigh, aLow) ?: return null
        return ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
    }

    private fun hexPair(high: Char, low: Char): Int? {
        val h = high.digitToIntOrNull(16) ?: return null
        val l = low.digitToIntOrNull(16) ?: return null
        return (h shl 4) or l
    }

    private fun parseChannel(raw: String): Int? {
        if (raw.endsWith("%")) {
            val n = raw.dropLast(1).toFloatOrNull() ?: return null
            return (n * 255f / 100f).toInt().coerceIn(0, 255)
        }
        return raw.toFloatOrNull()?.toInt()?.coerceIn(0, 255)
    }

    private fun parseAlpha(raw: String): Int? {
        if (raw.endsWith("%")) {
            val n = raw.dropLast(1).toFloatOrNull() ?: return null
            return (n * 255f / 100f).toInt().coerceIn(0, 255)
        }
        val n = raw.toFloatOrNull() ?: return null
        return (n * 255f).toInt().coerceIn(0, 255)
    }
}
