package app.particlebook.android.reader

import java.io.Closeable
import java.util.Base64

/**
 * P1 统一阅读位置契约（Lead 独占，P1 冻结）。
 *
 * 设计约束：
 * - 纯 JVM，可单测；不依赖 Android framework。
 * - v1 行协议带版本前缀，decode 兼容 v1 / 旧无版本行 / P0 的 PDF JSON。
 * - EPUB 位置精确到「章 + 块 + 字符」；PDF 位置精确到「页 + 归一化矩形」；
 *   纯文本到字符偏移；HTML 保留 href/fragment；滚动容器用百分比。
 * - 存储层列名仍是 locatorJson，但 P1 的编码不是 JSON；后续换格式只改本文件。
 */
sealed interface ReaderLocator {
    data class Epub(
        val chapterHref: String,
        val blockIndex: Int,
        val charOffset: Int = 0,
        /** 选区/高亮长度，UTF-16 code unit；0 表示单点位置。 */
        val length: Int = 0,
    ) : ReaderLocator

    data class Pdf(
        val page: Int,
        val x: Float = 0f,
        val y: Float = 0f,
        /** 归一化矩形，左上原点 0..1；单点位置为 null。 */
        val rect: ReaderRect? = null,
    ) : ReaderLocator

    data class Text(
        val offset: Int,
        val length: Int = 0,
    ) : ReaderLocator

    data class Html(
        val href: String,
        val fragment: String = "",
        val offset: Int = 0,
        val length: Int = 0,
    ) : ReaderLocator

    data class Scroll(val percent: Float) : ReaderLocator

    fun encode(): String = when (this) {
        is Epub -> "v1|epub|" + b64(chapterHref) + "|" + blockIndex + "|" + charOffset + "|" + length
        is Pdf -> "v1|pdf|" + page + "|" + x + "|" + y + "|" + (rect?.let {
            it.left.toString() + "|" + it.top + "|" + it.right + "|" + it.bottom
        } ?: "-|-|-|-")
        is Text -> "v1|text|" + offset + "|" + length
        is Html -> "v1|html|" + b64(href) + "|" + b64(fragment) + "|" + offset + "|" + length
        is Scroll -> "v1|scroll|" + percent.coerceIn(0f, 1f)
    }

    companion object {
        private const val VERSION = "v1"
        private const val MAX_HREF_CHARS = 8 * 1024

        fun decode(raw: String?): ReaderLocator? {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return null
            if (value.startsWith("{")) return decodeLegacyPdfJson(value)
            val parts = value.split('|')
            return if (parts.firstOrNull() == VERSION) decodeV1(parts.drop(1)) else decodeLegacy(parts)
        }

        private fun decodeV1(parts: List<String>): ReaderLocator? {
            if (parts.isEmpty()) return null
            return when (parts[0]) {
                "epub" -> if (parts.size == 5) {
                    val href = unb64(parts[1])?.takeIf { it.length <= MAX_HREF_CHARS } ?: return null
                    val block = parts[2].toIntOrNull() ?: return null
                    val char = parts[3].toIntOrNull() ?: return null
                    val length = parts[4].toIntOrNull() ?: return null
                    if (href.isBlank() || block < 0 || char < 0 || length < 0) null
                    else Epub(href, block, char, length)
                } else null
                "pdf" -> if (parts.size == 8) {
                    val page = parts[1].toIntOrNull() ?: return null
                    val x = parts[2].toFloatOrNull() ?: return null
                    val y = parts[3].toFloatOrNull() ?: return null
                    val rect = if (parts[4] == "-") {
                        null
                    } else {
                        val left = parts[4].toFloatOrNull() ?: return null
                        val top = parts[5].toFloatOrNull() ?: return null
                        val right = parts[6].toFloatOrNull() ?: return null
                        val bottom = parts[7].toFloatOrNull() ?: return null
                        ReaderRect.validated(left, top, right, bottom) ?: return null
                    }
                    if (page < 0 || !x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f) null
                    else Pdf(page, x, y, rect)
                } else null
                "text" -> if (parts.size == 3) {
                    val offset = parts[1].toIntOrNull() ?: return null
                    val length = parts[2].toIntOrNull() ?: return null
                    if (offset < 0 || length < 0) null else Text(offset, length)
                } else null
                "html" -> if (parts.size == 5) {
                    val href = unb64(parts[1])?.takeIf { it.length <= MAX_HREF_CHARS } ?: return null
                    val fragment = unb64(parts[2])?.takeIf { it.length <= MAX_HREF_CHARS } ?: return null
                    val offset = parts[3].toIntOrNull() ?: return null
                    val length = parts[4].toIntOrNull() ?: return null
                    if (href.isBlank() || offset < 0 || length < 0) null
                    else Html(href, fragment, offset, length)
                } else null
                "scroll" -> if (parts.size == 2) {
                    val percent = parts[1].toFloatOrNull() ?: return null
                    if (!percent.isFinite()) null else Scroll(percent.coerceIn(0f, 1f))
                } else null
                else -> null
            }
        }

        /** 旧 P0 行协议：epub|b64|block|char / pdf|page|x|y / text|offset。 */
        private fun decodeLegacy(parts: List<String>): ReaderLocator? = when {
            parts.size == 4 && parts[0] == "epub" -> {
                val href = unb64(parts[1]) ?: return null
                val block = parts[2].toIntOrNull() ?: return null
                val char = parts[3].toIntOrNull() ?: return null
                if (href.isBlank() || block < 0 || char < 0) null else Epub(href, block, char)
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

        /** P0 迁移生成的 {"kind":"pdf","page":N,"total":M}；只取页码，坐标归零。 */
        private fun decodeLegacyPdfJson(value: String): ReaderLocator? {
            val kind = Regex("\"kind\"\\s*:\\s*\"([^\"]+)\"").find(value)
                ?.groupValues?.getOrNull(1)?.lowercase() ?: return null
            if (kind != "pdf") return null
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

/** 归一化矩形，左上原点，0..1；right/bottom 为开区间。 */
data class ReaderRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    companion object {
        fun validated(left: Float, top: Float, right: Float, bottom: Float): ReaderRect? {
            if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return null
            if (left < 0f || top < 0f || right > 1f || bottom > 1f) return null
            if (left >= right || top >= bottom) return null
            return ReaderRect(left, top, right, bottom)
        }
    }
}

enum class ReaderFormat {
    EPUB,
    PDF,
    TEXT,
    HTML,
    UNSUPPORTED;

    companion object {
        fun fromRaw(raw: String?): ReaderFormat = when (raw?.trim()?.lowercase()) {
            "pdf" -> PDF
            "epub" -> EPUB
            "txt", "text", "md", "markdown" -> TEXT
            "html", "htm" -> HTML
            else -> UNSUPPORTED
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
    /** 解析器保留的原始 href（含 fragment），用于 C-10 的小节跳转。 */
    val rawHref: String? = null,
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
    val chapterCount: Int = 0,
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    val percent: Float = 0f,
    val finished: Boolean = false,
) {
    fun withLocator(next: ReaderLocator, chapter: Int = chapterIndex, page: Int = pageIndex): ReaderSession =
        copy(locator = next, chapterIndex = chapter, pageIndex = page)

    fun withProgress(chapter: Int, page: Int, pages: Int, percent: Float): ReaderSession =
        copy(chapterIndex = chapter, pageIndex = page, pageCount = pages, percent = percent.coerceIn(0f, 1f))
}

/** 标注类型；P1 先冻结形状，PDF 标注在 P2 落地。 */
enum class ReaderAnnotationType {
    BOOKMARK,
    HIGHLIGHT,
    NOTE,
    PDF,
}

/**
 * 统一标注模型。legacyId/legacyType 用于旧数据去重与回滚，不参与新业务判断。
 */
data class ReaderAnnotation(
    val id: Long = 0,
    val bookId: Long,
    val type: ReaderAnnotationType,
    val locator: ReaderLocator,
    val quote: String = "",
    val note: String = "",
    val color: String? = null,
    val tags: List<String> = emptyList(),
    val relatedId: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val legacyId: Long? = null,
    val legacyType: String? = null,
)

/** 选区：locator 是起点，text 是选中文本，rects 是渲染层可画的高亮区域。 */
data class ReaderSelection(
    val locator: ReaderLocator,
    val text: String,
    val rects: List<ReaderRect> = emptyList(),
)

/** 文本模型窗口；maxChars 由调用方限制，避免一次取整章。 */
data class ReaderTextModel(
    val locator: ReaderLocator,
    val text: String,
    val truncated: Boolean = false,
)

data class ReaderSearchHit(
    val locator: ReaderLocator,
    val snippet: String,
)

/**
 * PB-022 的 7 方法阅读器接口。EPUB/PDF 在 P1 实现；TXT/HTML 真实现归 P3，
 * P1 只允许 fake adapter 参与契约测试。
 */
interface ReaderAdapter : Closeable {
    val bookId: Long
    val format: ReaderFormat

    fun getLocator(): ReaderLocator

    fun goTo(locator: ReaderLocator): Boolean

    fun getSelection(): ReaderSelection?

    fun getTextModel(locator: ReaderLocator, maxChars: Int = 4096): ReaderTextModel?

    fun search(query: String, limit: Int = 50): List<ReaderSearchHit>

    fun applyAnnotation(annotation: ReaderAnnotation): ReaderAnnotation

    fun removeAnnotation(annotationId: Long): Boolean
}
