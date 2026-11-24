package app.particlebook.android.reader.export

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 把 [AnnotationExportDocument] 排版成 Markdown。
 *
 * 纯 JVM：只依赖 [java.time] 与 Kotlin 标准库，不碰 Android framework，
 * 因此可以直接被 JVM 单测钉住（见 AnnotationExportRendererTest）。
 *
 * 职责边界（见 AnnotationExportModels 的 KDoc）：
 * - 分组、排序、位置文字由 Planner 定，这里不再回头解析 locator；
 * - 这里只负责三件事：文本清洗（换行压平 / 控制字符 / 文件名非法字符）、
 *   块之间的空行与结尾换行、三类条目的固定行序。
 *
 * 排版不变量：
 * - 任意两个块之间恰好一个空行（`\n\n`）；
 * - 文件末尾恰好一个 `\n`；
 * - 任何一行都不以空格或制表符结尾。
 */
internal object AnnotationExportRenderer {

    const val MIME_MARKDOWN: String = "text/markdown; charset=utf-8"

    const val EMPTY_PLACEHOLDER: String = "（没有可导出的标注）"

    const val MAX_FILE_STEM_CHARS: Int = 80

    /** 书名清洗后为空时的兜底名，正文标题与文件名共用。 */
    private const val UNTITLED_BOOK = "未命名书籍"

    private const val FILE_NAME_SUFFIX = "-标注.md"

    /** Windows / macOS / Linux 文件名里都不该出现的字符（固定清单，不随平台变化）。 */
    private const val ILLEGAL_FILE_NAME_CHARS = "$\\/:*?\"<>|"

    private const val BLOCK_SEPARATOR = "\n\n"

    private val TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    private val SPACE_RUN = Regex(" +")

    /**
     * 由书名推导导出文件名：清洗后的书名 + `-标注.md`。
     *
     * 清洗顺序：空白（含 tab / 换行）统一成空格，同时丢掉文件名非法字符与其余控制字符；
     * 连续空格压成一个；首尾 trim；截断到 [MAX_FILE_STEM_CHARS] 个字符（按码点截断，
     * 不会切碎代理对）；仍为空则用 [UNTITLED_BOOK]。
     */
    fun suggestedFileName(bookTitle: String): String = sanitizeFileStem(bookTitle) + FILE_NAME_SUFFIX

    /**
     * 渲染整份导出。
     *
     * @param zone 时间行使用的时区；调用方按用户所在时区传入，默认系统时区。
     */
    fun renderMarkdown(
        document: AnnotationExportDocument,
        options: AnnotationExportOptions = AnnotationExportOptions(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): AnnotationExportResult {
        val groups = collectGroups(document, options)
        val entryCount = groups.sumOf { it.entries.size }
        val highlightCount = groups.countEntries(AnnotationExportKind.HIGHLIGHT)
        val noteCount = groups.countEntries(AnnotationExportKind.NOTE)
        val bookmarkCount = groups.countEntries(AnnotationExportKind.BOOKMARK)
        val exportedAtMillis =
            if (document.exportedAtMillis > 0L) document.exportedAtMillis else System.currentTimeMillis()

        val blocks = mutableListOf<String>()
        if (options.includeFrontMatter) {
            blocks += "# ${displayBookTitle(document.bookTitle)} — 标注导出"
            blocks += buildFrontMatterLines(
                document = document,
                exportedAtMillis = exportedAtMillis,
                zone = zone,
                highlightCount = highlightCount,
                noteCount = noteCount,
                bookmarkCount = bookmarkCount,
            ).joinToString("\n")
        }

        if (entryCount == 0) {
            blocks += EMPTY_PLACEHOLDER
        } else {
            // 目录有两个前提：front matter 打开（关掉时按契约连目录一起去掉），
            // 且实际输出 >= 2 组（单组没有跳转价值）。
            if (options.includeFrontMatter && groups.size >= 2) {
                blocks += "## 目录"
                blocks += groups.mapIndexed { index, group ->
                    "- [${escapeLinkText(cleanInline(group.title))}](#ch-${index + 1})"
                }.joinToString("\n")
            }
            groups.forEachIndexed { index, group ->
                // 锚点与标题同属一个块：中间只有一个换行，不是一个空行。
                blocks += "<a id=\"ch-${index + 1}\"></a>\n## ${cleanInline(group.title)}"
                group.entries.forEach { entry -> blocks += renderEntry(entry, options, zone) }
            }
        }

        return AnnotationExportResult(
            fileName = suggestedFileName(document.bookTitle),
            mimeType = MIME_MARKDOWN,
            content = blocks.joinToString(BLOCK_SEPARATOR) + "\n",
            entryCount = entryCount,
        )
    }

    // ------------------------------------------------------------------ 过滤与计数

    private class ExportGroup(
        val title: String,
        val entries: List<AnnotationExportEntry>,
    )

    private fun List<ExportGroup>.countEntries(kind: AnnotationExportKind): Int =
        sumOf { group -> group.entries.count { it.kind == kind } }

    /**
     * 按选项过滤条目，并丢掉过滤后为空的分组。
     *
     * 过滤发生在分组层面：整组被过滤空就不产生标题，也不计入 entryCount；
     * 于是「只剩一组」时目录自然也不会输出，空结果直接走占位符分支。
     */
    private fun collectGroups(
        document: AnnotationExportDocument,
        options: AnnotationExportOptions,
    ): List<ExportGroup> = document.sections.mapNotNull { section ->
        val entries = section.entries.filter { isIncluded(it.kind, options) }
        if (entries.isEmpty()) null else ExportGroup(section.title, entries)
    }

    private fun isIncluded(kind: AnnotationExportKind, options: AnnotationExportOptions): Boolean =
        when (kind) {
            AnnotationExportKind.HIGHLIGHT -> options.includeHighlights
            AnnotationExportKind.NOTE -> options.includeNotes
            AnnotationExportKind.BOOKMARK -> options.includeBookmarks
        }

    // ------------------------------------------------------------------ front matter

    private fun buildFrontMatterLines(
        document: AnnotationExportDocument,
        exportedAtMillis: Long,
        zone: ZoneId,
        highlightCount: Int,
        noteCount: Int,
        bookmarkCount: Int,
    ): List<String> {
        val lines = mutableListOf<String>()
        val author = cleanInline(document.author.orEmpty())
        if (author.isNotEmpty()) lines += "- 作者：${author}"
        val format = cleanInline(document.formatLabel)
        if (format.isNotEmpty()) lines += "- 格式：${format}"
        val summary = buildSummary(highlightCount, noteCount, bookmarkCount)
        if (summary.isNotEmpty()) lines += "- 标注：${summary}"
        lines += "- 导出时间：${formatTime(exportedAtMillis, zone)}"
        return lines
    }

    /** `高亮 3 · 笔记 2 · 书签 1`：只列非零项，顺序固定 HIGHLIGHT → NOTE → BOOKMARK。 */
    private fun buildSummary(highlightCount: Int, noteCount: Int, bookmarkCount: Int): String {
        val parts = mutableListOf<String>()
        if (highlightCount > 0) parts += "高亮 ${highlightCount}"
        if (noteCount > 0) parts += "笔记 ${noteCount}"
        if (bookmarkCount > 0) parts += "书签 ${bookmarkCount}"
        return parts.joinToString(" · ")
    }

    // ------------------------------------------------------------------ 条目

    /**
     * 一条条目 = 若干块，块之间一个空行：
     * - HIGHLIGHT：引用块（可选）+ 元数据块；
     * - BOOKMARK：`**书签**` 行 + 元数据块；
     * - NOTE：`**笔记**` 正文块 + 元数据块（不重复 - 笔记）。
     */
    private fun renderEntry(
        entry: AnnotationExportEntry,
        options: AnnotationExportOptions,
        zone: ZoneId,
    ): String {
        val parts = mutableListOf<String>()
        when (entry.kind) {
            AnnotationExportKind.HIGHLIGHT -> {
                val quote = cleanBlock(entry.quote)
                if (options.includeQuotes && quote.isNotEmpty()) {
                    parts += quote.split('\n').joinToString("\n") { line ->
                        // 空行只写 ">"：Markdown 里 ">" 与 "> " 等价，且不留行尾空格。
                        if (line.isEmpty()) ">" else "> ${line}"
                    }
                }
                parts += buildMetaLines(entry, includeNote = true, zone = zone).joinToString("\n")
            }

            AnnotationExportKind.BOOKMARK -> {
                val quote = cleanInline(entry.quote)
                parts += if (quote.isEmpty()) "**书签**" else "**书签**：${quote}"
                parts += buildMetaLines(entry, includeNote = true, zone = zone).joinToString("\n")
            }

            AnnotationExportKind.NOTE -> {
                // 正文自成一块：首行带 "**笔记**：" 前缀，续行缩进两格；正文为空就只留 "**笔记**"。
                val note = cleanBlock(entry.note)
                if (note.isEmpty()) {
                    parts += "**笔记**"
                } else {
                    val noteBlock = mutableListOf<String>()
                    appendNoteLines(noteBlock, "**笔记**：", note)
                    parts += noteBlock.joinToString("\n")
                }
                // 正文已经在上一行，NOTE 不再重复 "- 笔记："。
                parts += buildMetaLines(entry, includeNote = false, zone = zone).joinToString("\n")
            }
        }
        return parts.joinToString(BLOCK_SEPARATOR)
    }

    /**
     * 元数据行，顺序固定：颜色 → 位置 → 时间 → 笔记。
     * 位置恒输出（Planner 保证有值），颜色 / 时间 / 笔记为空就整行省略。
     */
    private fun buildMetaLines(
        entry: AnnotationExportEntry,
        includeNote: Boolean,
        zone: ZoneId,
    ): List<String> {
        val lines = mutableListOf<String>()
        val color = cleanInline(entry.color.orEmpty())
        if (color.isNotEmpty()) lines += "- 颜色：${color}"
        lines += "- 位置：${cleanInline(entry.location)}"
        if (entry.createdAtMillis > 0L) {
            lines += "- 时间：${formatTime(entry.createdAtMillis, zone)}"
        }
        if (includeNote) {
            val note = cleanBlock(entry.note)
            if (note.isNotEmpty()) appendNoteLines(lines, "- 笔记：", note)
        }
        return lines
    }

    /**
     * 把多行笔记贴到一行前缀后面：首行接在 [firstLinePrefix]（已含冒号，如 `- 笔记：`）后面，
     * 续行缩进两个空格（与桌面版一致）。[note] 必须非空：空笔记的省略 / 退化由调用方决定。
     */
    private fun appendNoteLines(target: MutableList<String>, firstLinePrefix: String, note: String) {
        val lines = note.split('\n')
        target += firstLinePrefix + lines.first()
        lines.drop(1).forEach { line ->
            // 空续行不写两个空格，避免行尾空白。
            target += if (line.isEmpty()) "" else "  " + line
        }
    }

    // ------------------------------------------------------------------ 清洗

    /**
     * 目录链接文本里的方括号必须转义：`- [第一章 [附注]](#ch-1)` 少一层转义就会把链接
     * 语法截断，整行变成乱码。反斜杠先转，避免把转义出来的反斜杠再转一遍。
     */
    private fun escapeLinkText(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace("[", "\\[")
        .replace("]", "\\]")

    /** 行内文本清洗：换行压成单个空格，去掉控制字符，首尾 trim。 */
    private fun cleanInline(raw: String): String {
        if (raw.isEmpty()) return ""
        val flattened = raw.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ')
        val builder = StringBuilder(flattened.length)
        for (ch in flattened) {
            if (!Character.isISOControl(ch)) builder.append(ch)
        }
        return builder.toString().trim()
    }

    /**
     * 块文本清洗：`\r\n` 与 `\r` 统一成 `\n`，保留换行与制表符、去掉其余控制字符，
     * 每行右端 trim，去掉首尾空行但保留行内空行。
     */
    private fun cleanBlock(raw: String): String {
        if (raw.isEmpty()) return ""
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
        val builder = StringBuilder(normalized.length)
        for (ch in normalized) {
            val keep = ch == '\n' || ch == '\t' || !Character.isISOControl(ch)
            if (keep) builder.append(ch)
        }
        val lines = builder.toString().split('\n').map { it.trimEnd() }
        var start = 0
        var end = lines.size
        while (start < end && lines[start].isEmpty()) start++
        while (end > start && lines[end - 1].isEmpty()) end--
        return lines.subList(start, end).joinToString("\n")
    }

    /** 正文标题用：与行内清洗一致，清洗后为空则退回 [UNTITLED_BOOK]。 */
    private fun displayBookTitle(bookTitle: String): String =
        cleanInline(bookTitle).ifEmpty { UNTITLED_BOOK }

    /**
     * 文件名主干清洗。空白（含 tab / 换行）先归一成一个空格占位，非法字符与其余控制字符直接丢弃，
     * 再把连续空格压成一个、首尾 trim，最后按码点截断到 [MAX_FILE_STEM_CHARS]。
     */
    private fun sanitizeFileStem(bookTitle: String): String {
        if (bookTitle.isEmpty()) return UNTITLED_BOOK
        val builder = StringBuilder(bookTitle.length)
        for (ch in bookTitle) {
            when {
                Character.isWhitespace(ch) || Character.isSpaceChar(ch) -> builder.append(' ')
                Character.isISOControl(ch) -> Unit
                ILLEGAL_FILE_NAME_CHARS.indexOf(ch) >= 0 -> Unit
                else -> builder.append(ch)
            }
        }
        val collapsed = SPACE_RUN.replace(builder.toString().trim(), " ")
        val limited = if (collapsed.codePointCount(0, collapsed.length) > MAX_FILE_STEM_CHARS) {
            collapsed.substring(0, collapsed.offsetByCodePoints(0, MAX_FILE_STEM_CHARS))
        } else {
            collapsed
        }
        return limited.trimEnd().ifEmpty { UNTITLED_BOOK }
    }

    // ------------------------------------------------------------------ 时间

    /** 固定 `yyyy-MM-dd HH:mm`（24 小时制，按传入 zone 换算）。 */
    private fun formatTime(millis: Long, zone: ZoneId): String =
        TIME_FORMATTER.format(Instant.ofEpochMilli(millis).atZone(zone))
}
