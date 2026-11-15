package app.particlebook.android.reader.epub.render

/**
 * 一条已持久化标注在当前页上的绘制范围。字符区间相对块文本，end 为 exclusive；
 * colorArgb 已包含期望的 alpha（标注默认约 0x55）。
 */
data class EpubHighlight(
    val blockIndex: Int,
    val startChar: Int,
    val endChar: Int,
    val colorArgb: Int,
) {
    val length: Int
        get() = (endChar - startChar).coerceAtLeast(0)
}
