package app.particlebook.android.reader.export

import app.particlebook.android.reader.ReaderAnnotation
import app.particlebook.android.reader.ReaderAnnotationType
import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.ui.data.Book

/**
 * 把「书 + 这本书的全部标注」整理成可排版的 [AnnotationExportDocument]。
 *
 * 这里只做分组、排序与位置文字，不生成任何字符串格式：Markdown 的形状归
 * [AnnotationExportRenderer]，两边都能各自被单测钉住。
 *
 * 分组口径（和桌面版的观感对齐，但按移动端更少噪音的读法）：
 * - EPUB：一章一组，组标题取 TOC 标题；TOC 里没有这一章时退回 href 去掉扩展名的末段，
 *   不编造「第 N 章」——假序号一旦和正文对不上，用户会以为丢了一章。
 * - PDF：一页一组，组标题「第 N 页」，N 从 1 起（用户看到的是页号，不是数组下标）。
 * - 纯文本 / HTML / 滚动：同进「正文」一组（三种都没有章页可对位），组内按
 *   字符偏移 → 文档顺序 → 滚动百分比。
 *
 * 丢弃口径：
 * - locatorError 非空的标注不导出：它的 locator 只是占位，导出的位置会是假的；
 *   用户仍能在标注侧栏看到这条并单独处理。
 * - 三种类型以外（理论上不存在）一律跳过。
 */
internal object AnnotationExportPlanner {

    /** PDF 分组标题；页号对外从 1 起。 */
    const val PDF_SECTION_PREFIX: String = "第 "
    const val PDF_SECTION_SUFFIX: String = " 页"

    /** 没有章节/页码可用时的分组标题。 */
    const val FALLBACK_SECTION_TITLE: String = "正文"

    /** 纯文本 / HTML / 滚动共用的分组键，只在本类内部使用。 */
    private const val BODY_SECTION_KEY: String = "body"

    /**
     * @param chapterTitleOf 给出章节 href，返回该章的显示标题；null/空白表示 TOC 里没有这一章。
     *   调用方通常传 `controller.tocTargets()` 的映射；导不出来源时传默认实现即可。
     */
    fun plan(
        book: Book,
        annotations: List<ReaderAnnotation>,
        chapterTitleOf: (chapterHref: String) -> String? = { null },
    ): AnnotationExportDocument {
        val sections = ArrayList<AnnotationExportSection>()
        // 分组键到 sections 下标的映射：同一章/同一页的标注必须落进同一组，且组按首次出现排序。
        val sectionIndex = LinkedHashMap<String, Int>()

        val ordered = annotations
            .filter { it.locatorError == null }
            .mapNotNull { annotation -> toEntry(annotation)?.let { it to annotation } }
            .sortedWith { left, right -> compareOrder(left.second.locator, right.second.locator) }

        for ((entry, annotation) in ordered) {
            val key = sectionKey(annotation.locator)
            val title = sectionTitle(annotation.locator, chapterTitleOf)
            val index = sectionIndex.getOrPut(key) {
                sections.add(AnnotationExportSection(title = title, entries = emptyList()))
                sections.size - 1
            }
            val existing = sections[index]
            sections[index] = existing.copy(entries = existing.entries + entry)
        }

        return AnnotationExportDocument(
            bookTitle = book.title,
            author = book.author?.takeIf { it.isNotBlank() },
            formatLabel = book.format.label,
            sections = sections,
            // 0 = 让 Renderer 用当前时间；这里不取时钟，plan 保持纯函数、可在单测里复现。
            exportedAtMillis = 0L,
        )
    }

    /** 类型映射：PDF 几何标注在 P1 以 HIGHLIGHT 落库，导出时同样按高亮处理。 */
    private fun toEntry(annotation: ReaderAnnotation): AnnotationExportEntry? {
        val kind = when (annotation.type) {
            ReaderAnnotationType.BOOKMARK -> AnnotationExportKind.BOOKMARK
            ReaderAnnotationType.HIGHLIGHT, ReaderAnnotationType.PDF -> AnnotationExportKind.HIGHLIGHT
            ReaderAnnotationType.NOTE -> AnnotationExportKind.NOTE
        }
        return AnnotationExportEntry(
            kind = kind,
            location = locationOf(annotation.locator),
            // 笔记正文单独成行；书签可能带名字，只有高亮与书签把 quote 当正文用。
            quote = if (kind == AnnotationExportKind.NOTE) "" else annotation.quote,
            note = annotation.note,
            color = annotation.color,
            createdAtMillis = annotation.createdAt,
        )
    }

    /**
     * 分组键：EPUB 用章 href，PDF 用页，纯文本 / HTML / 滚动**共用一组**。
     *
     * 三者没有章页可对位，标题都是「正文」；按 locator 类型再拆组只会导出三个同名
     * 「## 正文」，用户看不出区别。合成一组后组内由 [orderKey] 的档位排好。
     */
    private fun sectionKey(locator: ReaderLocator): String = when (locator) {
        is ReaderLocator.Epub -> "epub:" + locator.chapterHref
        is ReaderLocator.Pdf -> "pdf:" + locator.page
        else -> BODY_SECTION_KEY
    }

    private fun sectionTitle(
        locator: ReaderLocator,
        chapterTitleOf: (String) -> String?,
    ): String = when (locator) {
        is ReaderLocator.Epub -> chapterTitleOf(locator.chapterHref)?.takeIf { it.isNotBlank() }
            ?: hrefFallbackTitle(locator.chapterHref)
        is ReaderLocator.Pdf -> PDF_SECTION_PREFIX + (locator.page + 1) + PDF_SECTION_SUFFIX
        else -> FALLBACK_SECTION_TITLE
    }

    /** 条目位置：组标题已经给了章/页，这里只补组内更细的坐标，避免同一行重复两遍。 */
    private fun locationOf(locator: ReaderLocator): String = when (locator) {
        is ReaderLocator.Epub -> "第 " + (locator.blockIndex + 1) + " 段"
        is ReaderLocator.Pdf -> PDF_SECTION_PREFIX + (locator.page + 1) + PDF_SECTION_SUFFIX
        is ReaderLocator.Text -> "第 " + (locator.offset + 1) + " 字符"
        is ReaderLocator.Html -> locator.fragment.takeIf { it.isNotBlank() } ?: "文档开头"
        is ReaderLocator.Scroll -> "约 " + (locator.percent * 100).toInt() + "%"
    }

    /**
     * TOC 缺这一章时的兜底标题：取 href 去掉目录与扩展名后的末段。
     * 空 href 退回 [FALLBACK_SECTION_TITLE]，绝不返回空串——空标题会让目录项变成 `- [](#ch-1)`。
     */
    private fun hrefFallbackTitle(href: String): String {
        val withoutQuery = href.substringBefore("?").substringBefore("#")
        val name = withoutQuery.substringAfterLast("/")
        val stem = name.substringBeforeLast(".", name)
        return stem.trim().takeIf { it.isNotEmpty() } ?: FALLBACK_SECTION_TITLE
    }

    /**
     * 排序：同组内按 locator 的组内坐标升序；跨组顺序由分组的首次出现决定。
     *
     * 五种 locator 全部给出 key —— 早先对 Html/Scroll 返回 null 是错的：那种比较器
     * 左右都判「大」，违反对称性，混排条数一多 TimSort 会抛
     * IllegalArgumentException: Comparison method violates its general contract。
     */
    private fun compareOrder(left: ReaderLocator, right: ReaderLocator): Int {
        val leftKey = orderKey(left)
        val rightKey = orderKey(right)
        return compareValuesBy(
            leftKey,
            rightKey,
            { it.first },
            { it.second },
            { it.third },
        )
    }

    /**
     * (类型档, 组内主坐标, 次坐标)。
     * 档位只影响「正文」组里不同 locator 的先后：字符偏移 → 文档顺序 → 滚动百分比。
     * Html/Scroll 没有更细的坐标，同档内全部相等，靠稳定排序保持传入顺序。
     */
    private fun orderKey(locator: ReaderLocator): Triple<Int, Int, Int> = when (locator) {
        is ReaderLocator.Epub -> Triple(0, locator.blockIndex, locator.charOffset)
        is ReaderLocator.Pdf -> Triple(1, locator.page, (locator.y * 10_000f).toInt())
        is ReaderLocator.Text -> Triple(2, locator.offset, 0)
        is ReaderLocator.Html -> Triple(3, 0, 0)
        is ReaderLocator.Scroll -> Triple(4, (locator.percent * 10_000f).toInt(), 0)
    }
}
