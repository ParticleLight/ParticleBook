package app.particlebook.android.reader

import java.util.Base64

/**
 * P1 统一阅读位置契约（Lead 独占，P1 冻结）。
 *
 * 设计约束：
 * - 纯 JVM，可单测；不依赖 Android framework。
 * - encode/decode 使用稳定的行协议，不依赖 JSON 库；P0 迁移留下的 PDF JSON 位置由 decode 兼容。
 * - EPUB 位置精确到「章 + 块 + 字符」；PDF 位置精确到「页 + 归一化坐标」；纯文本到字符偏移。
 * - 存储层列名仍是 locatorJson，但 P1 的编码不是 JSON；后续若要换格式，只改本文件。
 */
sealed interface ReaderLocator {
    data class Epub(
        val chapterHref: String,
        val blockIndex: Int,
        val charOffset: Int = 0,
    ) : ReaderLocator

    data class Pdf(
        val page: Int,
        val x: Float = 0f,
        val y: Float = 0f,
    ) : ReaderLocator

    data class Text(
        val offset: Int,
    ) : ReaderLocator

    fun encode(): String = when (this) {
        is Epub -> "epub|" + b64(chapterHref) + "|" + blockIndex + "|" + charOffset
        is Pdf -> "pdf|" + page + "|" + x + "|" + y
        is Text -> "text|" + offset
    }

    companion object {
        fun decode(raw: String?): ReaderLocator? {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return null
            if (value.startsWith("{")) return decodeLegacyPdfJson(value)
            val parts = value.split('|')
            return when {
                parts.size == 4 && parts[0] == "epub" -> {
                    val href = unb64(parts[1]) ?: return null
                    val block = parts[2].toIntOrNull() ?: return null
                    val char = parts[3].toIntOrNull() ?: return null
                    Epub(href, block, char)
                }
                parts.size == 4 && parts[0] == "pdf" -> {
                    val page = parts[1].toIntOrNull() ?: return null
                    val x = parts[2].toFloatOrNull() ?: return null
                    val y = parts[3].toFloatOrNull() ?: return null
                    Pdf(page.coerceAtLeast(0), x, y)
                }
                parts.size == 2 && parts[0] == "text" -> {
                    val offset = parts[1].toIntOrNull() ?: return null
                    Text(offset.coerceAtLeast(0))
                }
                else -> null
            }
        }

        /** P0 迁移生成的 {"kind":"pdf","page":N,"total":M}；只取页码，坐标归零。 */
        private fun decodeLegacyPdfJson(value: String): ReaderLocator? {
            if (!value.contains("\"kind\"") || !value.contains("pdf")) return null
            val page = Regex("\"page\"\\s*:\\s*(\\d+)").find(value)?.groupValues?.getOrNull(1)
                ?.toIntOrNull() ?: return null
            return Pdf(page.coerceAtLeast(0))
        }

        private fun b64(value: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

        private fun unb64(value: String): String? = runCatching {
            String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
        }.getOrNull()
    }
}

enum class ReaderFormat {
    EPUB,
    PDF,
    TEXT;

    companion object {
        fun fromRaw(raw: String?): ReaderFormat = when (raw?.trim()?.lowercase()) {
            "pdf" -> PDF
            "txt", "text", "md", "markdown", "html", "htm", "fb2", "docx", "cbz" -> TEXT
            else -> EPUB
        }
    }
}

/** 阅读主题；P1 只落地字号/行高/深色，字体族留给 P2。 */
data class ReaderTheme(
    val fontSizeSp: Float = 17f,
    val lineHeightMultiplier: Float = 1.5f,
    val dark: Boolean = false,
    val serif: Boolean = false,
)

data class ReaderTocEntry(
    val title: String,
    val locator: ReaderLocator,
    val children: List<ReaderTocEntry> = emptyList(),
)

/**
 * 一次阅读会话的不可变快照。UI/进度/恢复都只依赖它，不直接依赖 parser 内部状态。
 * pageIndex 是当前章内的页下标；pageCount 是当前章总页数。跨章进度用 percent。
 */
data class ReaderSession(
    val bookId: Long,
    val format: ReaderFormat,
    val locator: ReaderLocator? = null,
    val chapterIndex: Int = 0,
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    val percent: Float = 0f,
) {
    fun withLocator(next: ReaderLocator, chapter: Int = chapterIndex, page: Int = pageIndex): ReaderSession =
        copy(locator = next, chapterIndex = chapter, pageIndex = page)

    fun withProgress(chapter: Int, page: Int, pages: Int, percent: Float): ReaderSession =
        copy(chapterIndex = chapter, pageIndex = page, pageCount = pages, percent = percent.coerceIn(0f, 1f))
}
