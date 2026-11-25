package app.particlebook.android.ui.data

import org.json.JSONObject
import java.util.Locale

/**
 * 书架条目格式。原生阅读支持 EPUB / PDF / TXT / Markdown / FB2 / HTML；其他格式保留条目但入口
 * 明确提示不支持，不能再像 P0 那样把未知格式默认当 EPUB（否则 AZW3 会被送进 EPUB 解析器）。
 *
 * 文本类（TXT / Markdown / FB2 / HTML）共用同一个滚动式阅读器：它们没有固定版式，进度按段落位置记。
 */
enum class BookFormat(val label: String, val mime: String, val raw: String) {
    Epub("EPUB", "application/epub+zip", "epub"),
    Pdf("PDF", "application/pdf", "pdf"),
    Text("TXT", "text/plain", "txt"),

    /** Markdown 目前按纯文本呈现（标题加大加粗），不做完整富文本渲染。 */
    Markdown("MD", "text/markdown", "md"),

    /**
     * FB2（FictionBook 2.0）：XML，由 [app.particlebook.android.reader.fb2.Fb2Parser] 解析出
     * 章节标题与正文段落，再走同一个滚动式阅读器。内嵌图片暂不显示。
     */
    Fb2("FB2", "application/x-fictionbook+xml", "fb2"),

    /**
     * HTML：由 [app.particlebook.android.reader.html.HtmlTextExtractor] 抽出标题与正文段落，
     * 与文本类共用滚动式阅读器。只读正文，不执行脚本、不加载外部资源。
     */
    Html("HTML", "text/html", "html"),
    Unsupported("不支持", "application/octet-stream", "unsupported");

    /** 是否有原生阅读器实现；UI 入口据此禁用或提示。 */
    val isReadable: Boolean
        get() = this != Unsupported

    /** 走滚动式文本阅读器的格式（TXT / Markdown / FB2 / HTML）：没有固定版式，进度按段落位置记。 */
    val isTextLike: Boolean get() = this == Text || this == Markdown || this == Fb2 || this == Html

    companion object {
        fun fromRaw(raw: String?): BookFormat = when (raw?.lowercase(Locale.US)) {
            "pdf" -> Pdf
            "epub" -> Epub
            "txt", "text" -> Text
            "md", "markdown" -> Markdown
            "fb2" -> Fb2
            "html", "htm", "xhtml" -> Html
            else -> Unsupported
        }
    }
}

/** 书架条目，字段与 assets/fixtures.json 的 books[] 对齐。 */
data class Book(
    val id: Long,
    val title: String,
    val author: String?,
    val format: BookFormat,
    val filePath: String,
    val fileSize: Long,
    val addedAt: String? = null,
    val tags: List<String> = emptyList(),
    /**
     * 用户评分 1..5；null = 未评分。
     *
     * 旧索引没有该字段时按 null 读入（向后兼容）；解码时越界值归 null，
     * 写入侧由 [LibraryStore.setRating] 负责 clamp。
     */
    val rating: Int? = null,
) {
    val authorLabel: String
        get() = author?.takeIf { it.isNotBlank() } ?: "未知作者"

    val sizeLabel: String
        get() {
            if (fileSize <= 0L) return "—"
            val kb = fileSize / 1024.0
            return if (kb < 1024.0) {
                String.format(Locale.US, "%.0f KB", kb)
            } else {
                String.format(Locale.US, "%.1f MB", kb / 1024.0)
            }
        }
}

/** 解析 fixtures.json（只关心 books 数组；坏条目跳过而不是整包失败）。 */
fun parseBooks(json: String): List<Book> {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
    val array = root.optJSONArray("books") ?: return emptyList()
    val out = ArrayList<Book>(array.length())
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val title = item.optString("title").takeIf { it.isNotBlank() } ?: "未命名"
        val author = item.optString("author").takeUnless { it.isBlank() || it == "null" }
        val tags = buildList {
            item.optJSONArray("tags")?.let { tagArray ->
                for (tagIndex in 0 until tagArray.length()) {
                    tagArray.optString(tagIndex).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }
        out += Book(
            id = item.optLong("id", (index + 1).toLong()),
            title = title,
            author = author,
            format = BookFormat.fromRaw(item.optString("format")),
            filePath = item.optString("file_path"),
            fileSize = item.optLong("file_size", 0L),
            addedAt = item.optString("added_at").takeUnless { it.isBlank() || it == "null" },
            tags = tags,
        )
    }
    return out
}
