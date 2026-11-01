package app.particlebook.android.ui.data

import org.json.JSONObject
import java.util.Locale

/** 书架里只有两种格式；EPUB 排版尚未迁移，PDF 走原生 PdfRenderer。 */
enum class BookFormat(val label: String, val mime: String) {
    Epub("EPUB", "application/epub+zip"),
    Pdf("PDF", "application/pdf");

    companion object {
        fun fromRaw(raw: String?): BookFormat =
            if (raw != null && raw.equals("pdf", ignoreCase = true)) Pdf else Epub
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
