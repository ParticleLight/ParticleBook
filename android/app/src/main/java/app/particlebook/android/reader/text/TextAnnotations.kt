package app.particlebook.android.reader.text

import app.particlebook.android.reader.ReaderAnnotation
import app.particlebook.android.reader.ReaderAnnotationType
import app.particlebook.android.reader.ReaderLocator

/** 段落级锚点：段落下标 + 该段在规范化全文里的起始偏移与文本长度，单位是 UTF-16 码元。 */
internal data class TextAnchor(val paragraphIndex: Int, val offset: Int, val length: Int)

/**
 * 段落级标注的纯函数层：把 [ReaderAnnotation] 上的字符偏移锚点映射回 [TextDocument] 的段落下标。
 *
 * 阅读器按段落 LazyColumn 渲染，标注只落在「整段」上（不做字符级跨项选区），所以这一层只需要
 * 一个能力：偏移 ↔ 段落。全部是纯函数、无状态、不依赖 Android framework，可以直接在 JVM 单测里跑。
 *
 * 坐标系与近似性：
 * - offset 一律是 UTF-16 码元下标，与 [String.length] 同口径（不是码点数）；
 * - [TextDocument.charOffsets] 只承诺**单调不减**（见 [TextDocumentParser.parse]），相邻两段可能
 *   拥有相同 offset；因此「偏移 → 段落」的答案是**最后一个** `charOffsets[i] <= offset` 的段落。
 *   这不是实现细节：LazyColumn 的 item 下标就是段落下标，重复 offset 时必须落到靠后的那一项，
 *   否则同一处点击每次都会跳回上面那段。
 */
internal object TextAnnotations {

    /**
     * quote 保留的最大字符数（UTF-16 码元，不含追加的省略号）。
     *
     * 200 字够在标注列表里认出是哪一段，又不至于让标注随正文一起膨胀；超长段（解析器最多切到
     * 2 万码元）靠它收敛。
     */
    const val QUOTE_MAX_CHARS: Int = 200

    /** 截断标记：U+2026 HORIZONTAL ELLIPSIS。 */
    private const val ELLIPSIS: Char = '\u2026'

    /**
     * 字符偏移 → 段落下标：取最后一个 charOffsets[i] <= offset 的段落。
     *
     * 用二分而不是线性扫：TXT 可以到十万段，而这个函数会被滚动时的每一次「按位置恢复」
     * 以及每条标注的归组调用。单调不减的前提下「最后一个 <= 目标」天然是上界二分，
     * 重复 offset 也随之落到最后一处。空文档返回 0（调用方用 lastIndex 判有效），
     * 负 offset 按 0 处理，小于首段起点的偏移同样归 0。
     */
    fun paragraphIndexOfOffset(document: TextDocument, offset: Int): Int {
        if (document.paragraphs.isEmpty()) return 0
        val searchable = minOf(document.paragraphs.size, document.charOffsets.size)
        if (searchable == 0) return 0
        val target = if (offset < 0) 0 else offset
        var low = 0
        var high = searchable - 1
        var found = 0
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (document.charOffsets[middle] <= target) {
                found = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return found
    }

    /**
     * 段落下标 → 锚点（段首偏移 + 段落文本长度）；下标越界或文档为空返回 null。
     *
     * length 可以为 0（纯空白段也占一个可滚动位置），调用方不要把它当成无效值。
     * charOffsets 比 paragraphs 短只可能是手工拼出来的坏文档，此时同样返回 null，
     * 免得把 0 号段的位置当成别段的位置写进 DB。
     */
    fun anchorOf(document: TextDocument, paragraphIndex: Int): TextAnchor? {
        val paragraph = document.paragraphs.getOrNull(paragraphIndex) ?: return null
        if (paragraphIndex >= document.charOffsets.size) return null
        return TextAnchor(paragraphIndex, document.charOffsets[paragraphIndex], paragraph.text.length)
    }

    /**
     * 标注 → 段落下标。位置不可用（locatorError 非空）、非纯文本 locator（EPUB/PDF/HTML/滚动）、
     * 负偏移一律返回 null：这三种情况都不能当作「第 0 段」，否则标注会跳到书首。
     */
    fun paragraphOf(document: TextDocument, annotation: ReaderAnnotation): Int? {
        if (annotation.locatorError != null) return null
        val locator = annotation.locator as? ReaderLocator.Text ?: return null
        if (locator.offset < 0) return null
        return paragraphIndexOfOffset(document, locator.offset)
    }

    /**
     * 按段落下标归组，供 LazyColumn 的 item 直接查表。
     *
     * - 跳过 locatorError 非空、非 [ReaderLocator.Text]、负偏移的条目（与 [paragraphOf] 同一口径）；
     * - 只保留落在 0..lastIndex 的段落：空文档时 paragraphOf 恒为 0，而段落表是空的，
     *   这些条目必须丢弃，不能塞给 UI 的 0 号项；
     * - 返回的 Map 是 LinkedHashMap 语义（键按首次出现顺序），组内保持入参顺序，
     *   这样「先画谁」在重叠标注时是确定的。
     */
    fun byParagraph(document: TextDocument, annotations: List<ReaderAnnotation>): Map<Int, List<ReaderAnnotation>> {
        val lastIndex = document.paragraphs.size - 1
        val grouped = LinkedHashMap<Int, MutableList<ReaderAnnotation>>()
        for (annotation in annotations) {
            val paragraph = paragraphOf(document, annotation) ?: continue
            if (paragraph > lastIndex) continue
            grouped.getOrPut(paragraph) { ArrayList() }.add(annotation)
        }
        return grouped
    }

    /**
     * 该段是否该上底色：有高亮或笔记才涂，只有书签不涂。
     *
     * 书签是位置标记，不是对文字的修饰；给纯书签段铺一块和黄色高亮一模一样的底色，
     * 用户就分不出这段到底是"标了黄"还是"插了书签"。
     */
    fun hasPaint(annotations: List<ReaderAnnotation>): Boolean = annotations.any {
        it.type == ReaderAnnotationType.HIGHLIGHT || it.type == ReaderAnnotationType.NOTE
    }

    /**
     * 该段的底色：**高亮优先**，没有高亮才取笔记的颜色；两者都没有返回 null。
     *
     * 为什么不是「第一条带颜色的标注」：同一段可以既有笔记又有高亮，两条的 createdAt 先后
     * 决定了它们在列表里的顺序。若底色取第一条，先写笔记后点黄色高亮时底色仍是笔记蓝，
     * 而工具条上的选中态是黄色 —— 同一段在两侧显示两种颜色。
     *
     * 高亮优先是稳定的：高亮表达"我把这段涂成了什么颜色"，笔记的颜色只是默认蓝，
     * 用户点色点的意图永远是改高亮。
     *
     * 返回 null 有两种含义（没有高亮/笔记，或那条高亮没设颜色），都由调用方回退默认色：
     * 这里不做「跳过 null 继续找下一条」的兜底，那会让同一段在不同时间画出不同颜色。
     */
    fun paintColorOf(annotations: List<ReaderAnnotation>): String? {
        var noteColor: String? = null
        var sawNote = false
        for (annotation in annotations) {
            when (annotation.type) {
                ReaderAnnotationType.HIGHLIGHT -> return annotation.color
                ReaderAnnotationType.NOTE -> if (!sawNote) {
                    sawNote = true
                    noteColor = annotation.color
                }
                else -> Unit
            }
        }
        return noteColor
    }

    /**
     * 给（书, 段落）造一条新标注；不可标注的位置返回 null。
     *
     * 拒绝的四种情况：PDF 类型（PDF 是页内几何标注，段落锚点表达不了）、段落下标越界、
     * 该段文本为空白（LazyColumn 里那一行没有可读内容，落上去等于丢标注）、负下标。
     * BOOKMARK 的 locator.length 固定 0 —— 书签是单点位置，不是区间；quote 仍然照填，
     * 列表里才能显示「在哪一段」。
     *
     * id 保持默认 0 由 store 落库时补；now 传 0 也原样留着，同样交给 store。
     */
    fun create(
        bookId: Long,
        document: TextDocument,
        paragraphIndex: Int,
        type: ReaderAnnotationType,
        color: String? = null,
        note: String = "",
        now: Long = 0L,
    ): ReaderAnnotation? {
        if (type == ReaderAnnotationType.PDF) return null
        val text = document.paragraphs.getOrNull(paragraphIndex)?.text ?: return null
        if (text.isBlank()) return null
        // 段首偏移与长度的取值口径只有 [anchorOf] 一处：这里另写一套会让"存进去的位置"
        // 与"按位置找回段落"在畸形文档上分叉。
        val anchor = anchorOf(document, paragraphIndex) ?: return null
        val length = if (type == ReaderAnnotationType.BOOKMARK) 0 else anchor.length
        return ReaderAnnotation(
            bookId = bookId,
            type = type,
            locator = ReaderLocator.Text(anchor.offset, length),
            quote = quoteOf(text),
            note = note,
            color = color,
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * 段落文本 → 引用。超长时截到 [QUOTE_MAX_CHARS] 再补一个省略号（截断不劈开代理对）。
     *
     * 切点回退一位的代价是少留一个字，但不回退会让一个 emoji 变成半个代理对：存进 DB 后
     * 要么显示成替换字符，要么在 JSON 序列化时被替换掉，两次读写就对不上了。
     */
    private fun quoteOf(text: String): String {
        if (text.length <= QUOTE_MAX_CHARS) return text
        var end = QUOTE_MAX_CHARS
        if (end > 0 && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) {
            end--
        }
        return text.substring(0, end) + ELLIPSIS
    }
}
