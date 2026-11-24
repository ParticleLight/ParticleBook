package app.particlebook.android.reader.export

/**
 * 标注导出契约（Lead 独占，改动需评审）。
 *
 * 分层：
 * - 本文件只放数据形状，纯 JVM，不依赖 Android framework；
 * - [AnnotationExportPlanner]（Android 层）负责把 Book + List<ReaderAnnotation> 拆成
 *   [AnnotationExportDocument]：章节/页分组、排序、位置文字、类型映射都在那里定；
 * - [AnnotationExportRenderer]（纯 JVM）只负责把 document 排版成字符串，不再回头读
 *   locator 或 Android API —— 因此它可以被 JVM 单测直接钉住。
 *
 * 这样切分的理由：分组规则会随 EPUB/PDF 的 locator 语义变化，排版规则只会随格式
 * 约定变化。两件事分开，改一个不必动另一个，也不必为了测排版去造 locator。
 */

/** 导出条目类型。只保留能排版的三种；PDF 几何标注在 P1 以 HIGHLIGHT 落库，因此不会出现第四种。 */
internal enum class AnnotationExportKind {
    BOOKMARK,
    HIGHLIGHT,
    NOTE,
}

/**
 * 一条已经解析好「在哪儿」的标注。
 *
 * [location] 是给人看的位置文字（如「第三章 · 第 12 段」「第 37 页」），由 Planner 生成；
 * Renderer 不得再尝试从它解析章节/页号。
 *
 * [quote] 只对 HIGHLIGHT 有意义（高亮原文）；BOOKMARK 可以把书签名放这里；NOTE 不用。
 * [note] 是笔记正文，HIGHLIGHT/BOOKMARK 上的附注也放这里。
 * [createdAtMillis] 为 0 表示没有时间（旧数据/导入数据），排版时整行省略。
 */
internal data class AnnotationExportEntry(
    val kind: AnnotationExportKind,
    val location: String,
    val quote: String = "",
    val note: String = "",
    val color: String? = null,
    val createdAtMillis: Long = 0L,
)

/** 一组条目：EPUB 是一章，PDF 是一页。[title] 已是可显示文字（章节名 / 「第 N 页」）。 */
internal data class AnnotationExportSection(
    val title: String,
    val entries: List<AnnotationExportEntry>,
)

/**
 * 一次导出的完整输入。
 *
 * [bookTitle] 不在这里清洗：Renderer 负责压平换行、去控制字符与文件名非法字符，
 * 因为清洗规则属于「排版/落盘约定」，Planner 只管取字段。
 * [exportedAtMillis] 为 0 时 Renderer 用当前时间。
 */
internal data class AnnotationExportDocument(
    val bookTitle: String,
    val author: String? = null,
    val formatLabel: String = "",
    val sections: List<AnnotationExportSection> = emptyList(),
    val exportedAtMillis: Long = 0L,
)

/**
 * 导出内容选项。三类条目各自开关；[includeFrontMatter] 关掉时不要书名/作者/统计/目录，
 * 只留分组与条目，便于用户把内容粘进自己的笔记；[includeQuotes] 关掉时高亮只留位置与附注。
 */
internal data class AnnotationExportOptions(
    val includeBookmarks: Boolean = true,
    val includeHighlights: Boolean = true,
    val includeNotes: Boolean = true,
    val includeFrontMatter: Boolean = true,
    val includeQuotes: Boolean = true,
)

/** 渲染结果：直接交给 SAF 写盘或 ACTION_SEND 分享，调用方不需要再拼文件名或 MIME。 */
internal data class AnnotationExportResult(
    val fileName: String,
    val mimeType: String,
    val content: String,
    val entryCount: Int,
) {
    /** 一条都没导出（全被选项过滤掉，或这本书本来就没有标注）。 */
    val isEmpty: Boolean get() = entryCount == 0
}
