package app.particlebook.android.reader.export

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 标注导出：CSV 渲染（纯 JVM，不依赖 Android framework）。
 *
 * 契约见 [AnnotationExportModels]：本对象只把已经分好组、算好位置文字的
 * [AnnotationExportDocument] 排版成单元格字符串，不回头读 locator，也不猜章节名。
 *
 * 排版约定（对外硬约束，改动会破坏已落盘的 CSV）：
 * - 内容 = BOM + 表头 + CRLF，随后每条一行、每行都以 CRLF 结尾；行结束符只有 CRLF；
 * - 列顺序固定「类型,章节,位置,原文,笔记,颜色,时间」；
 * - 含 `,` `"` 换行的字段整体加双引号，内部 `"` 翻倍；空字段保持空、不加引号；
 * - 只有「笔记」「原文」保留换行，其余列压成单行；
 * - CSV 没有汇总行，[AnnotationExportDocument.exportedAtMillis] 不参与内容。
 */
internal object AnnotationExportCsv {

    const val MIME_CSV: String = "text/csv; charset=utf-8"
    const val BOM: String = "\uFEFF"
    const val HEADER: String = "类型,章节,位置,原文,笔记,颜色,时间"
    const val LINE_END: String = "\r\n"

    private const val FALLBACK_BOOK_TITLE = "未命名书籍"
    private const val FILE_SUFFIX = "-标注.csv"
    private const val MAX_BOOK_TITLE_CHARS = 80
    private const val ILLEGAL_FILE_NAME_CHARS = "\\/:*?\"<>|"

    /** 时间列固定到分钟；用 ROOT locale 避免不同设备把数字或分隔符换成别的样子。 */
    private val TIMESTAMP_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    fun renderCsv(
        document: AnnotationExportDocument,
        options: AnnotationExportOptions = AnnotationExportOptions(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): AnnotationExportResult {
        val content = StringBuilder()
        content.append(BOM).append(HEADER).append(LINE_END)

        var entryCount = 0
        for (section in document.sections) {
            val sectionTitle = flattenInline(section.title)
            for (entry in section.entries) {
                if (!options.includes(entry.kind)) continue
                appendRow(content, sectionTitle, entry, zone)
                entryCount++
            }
        }

        return AnnotationExportResult(
            fileName = fileNameFor(document.bookTitle),
            mimeType = MIME_CSV,
            content = content.toString(),
            entryCount = entryCount,
        )
    }

    // ---- 行 ----------------------------------------------------------------

    private fun appendRow(
        target: StringBuilder,
        sectionTitle: String,
        entry: AnnotationExportEntry,
        zone: ZoneId,
    ) {
        target
            .append(kindLabel(entry.kind))
            .append(',')
            .append(escapeCell(sectionTitle))
            .append(',')
            .append(escapeCell(flattenInline(entry.location)))
            .append(',')
            .append(escapeCell(normalizeMultiline(entry.quote)))
            .append(',')
            .append(escapeCell(normalizeMultiline(entry.note)))
            .append(',')
            .append(escapeCell(flattenInline(entry.color.orEmpty())))
            .append(',')
            .append(escapeCell(timestamp(entry.createdAtMillis, zone)))
            .append(LINE_END)
    }

    private fun kindLabel(kind: AnnotationExportKind): String = when (kind) {
        AnnotationExportKind.HIGHLIGHT -> "高亮"
        AnnotationExportKind.NOTE -> "笔记"
        AnnotationExportKind.BOOKMARK -> "书签"
    }

    private fun AnnotationExportOptions.includes(kind: AnnotationExportKind): Boolean = when (kind) {
        AnnotationExportKind.BOOKMARK -> includeBookmarks
        AnnotationExportKind.HIGHLIGHT -> includeHighlights
        AnnotationExportKind.NOTE -> includeNotes
    }

    private fun timestamp(createdAtMillis: Long, zone: ZoneId): String {
        if (createdAtMillis <= 0L) return ""
        return Instant.ofEpochMilli(createdAtMillis).atZone(zone).format(TIMESTAMP_FORMATTER)
    }

    // ---- 字段清洗 ----------------------------------------------------------

    /**
     * 单行字段：CRLF / CR / LF 一律压成一个空格，其余控制字符直接丢弃，最后 trim。
     * 章节名、位置、颜色走这条路径——它们出现在一行里的固定列，不能带换行。
     */
    private fun flattenInline(raw: String): String {
        if (raw.isEmpty()) return ""
        val out = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val ch = raw[index]
            when {
                ch == '\r' -> {
                    out.append(' ')
                    if (index + 1 < raw.length && raw[index + 1] == '\n') index++
                }
                ch == '\n' -> out.append(' ')
                isDroppedControl(ch) -> Unit
                else -> out.append(ch)
            }
            index++
        }
        return out.toString().trim()
    }

    /**
     * 「笔记」「原文」：换行保留（由引号包裹负责合法性），CRLF/CR 统一成 LF，
     * 除换行和制表以外的控制字符丢弃。不做 trim——原文与笔记的留白由作者决定。
     */
    private fun normalizeMultiline(raw: String): String {
        if (raw.isEmpty()) return ""
        val out = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val ch = raw[index]
            when {
                ch == '\r' -> {
                    out.append('\n')
                    if (index + 1 < raw.length && raw[index + 1] == '\n') index++
                }
                ch == '\n' || ch == '\t' -> out.append(ch)
                isDroppedControl(ch) -> Unit
                else -> out.append(ch)
            }
            index++
        }
        return out.toString()
    }

    private fun isDroppedControl(ch: Char): Boolean = Character.isISOControl(ch)

    /**
     * RFC 4180 子集：含 `,`、`"` 或换行的字段整体加引号，内部引号翻倍。
     * 空字段输出为空串（不加引号），未命中特殊字符的字段原样输出。
     */
    private fun escapeCell(text: String): String {
        if (text.isEmpty()) return ""
        var needsQuotes = false
        for (ch in text) {
            if (ch == ',' || ch == '"' || ch == '\n' || ch == '\r') {
                needsQuotes = true
                break
            }
        }
        if (!needsQuotes) return text

        val out = StringBuilder(text.length + 2)
        out.append('"')
        for (ch in text) {
            if (ch == '"') out.append("\"\"") else out.append(ch)
        }
        out.append('"')
        return out.toString()
    }

    // ---- 文件名 ------------------------------------------------------------

    private fun fileNameFor(bookTitle: String): String = sanitizeBookTitle(bookTitle) + FILE_SUFFIX

    /**
     * 书名清洗（与 Markdown 侧规则一致，但独立实现，避免跨渲染器耦合）：
     * 去掉 [ILLEGAL_FILE_NAME_CHARS] 与控制字符 → 连续空白压一个空格 → trim →
     * 超过 [MAX_BOOK_TITLE_CHARS] 截断 → 空则 [FALLBACK_BOOK_TITLE]。
     */
    private fun sanitizeBookTitle(bookTitle: String): String {
        val kept = StringBuilder(bookTitle.length)
        for (ch in bookTitle) {
            if (ch in ILLEGAL_FILE_NAME_CHARS) continue
            if (isDroppedControl(ch)) continue
            kept.append(ch)
        }

        val collapsed = StringBuilder(kept.length)
        var pendingSpace = false
        for (ch in kept) {
            if (ch.isWhitespace()) {
                if (collapsed.isNotEmpty()) pendingSpace = true
                continue
            }
            if (pendingSpace) {
                collapsed.append(' ')
                pendingSpace = false
            }
            collapsed.append(ch)
        }

        val truncated = truncateToCodePoints(collapsed.toString(), MAX_BOOK_TITLE_CHARS).trim()
        return if (truncated.isEmpty()) FALLBACK_BOOK_TITLE else truncated
    }

    /** 按 code point 截断，避免把代理对劈成半个字符。 */
    private fun truncateToCodePoints(text: String, maxCodePoints: Int): String {
        if (text.codePointCount(0, text.length) <= maxCodePoints) return text
        return text.substring(0, text.offsetByCodePoints(0, maxCodePoints))
    }
}
