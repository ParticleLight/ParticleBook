package app.particlebook.android.reader.text

import kotlin.math.roundToInt

/**
 * 「跳到位置」面板（TextProgressSheet）要显示的全部状态：滑块落点 + 两行文案 + 当前章节。
 *
 * [fraction] 是**已读段落口径**的比例（1 基）：第 i 段 = (i + 1) / count，与阅读器顶栏
 * （`((firstVisibleItemIndex + 1) * 100 / total)`）、书架（`((page + 1) * 100 / totalPages)`）
 * 同一口径 —— 同一屏上的三个「已读 x%」必须字面一致。口径不统一的直接后果：4 段等长的书里
 * 面板显示 0% / 顶栏显示 25%、面板 75% / 顶栏 100%，用户看到的进度自相矛盾。
 *
 * 这里**不再**用 [TextDocumentParser.fractionForParagraph]（段首字符偏移 / 全文字符数，0 基）：
 * 首段起点恒为 0，任何书的第一段都显示 0%，末段也永远到不了 100%；而面板回答的问题是
 * 「读到第几段」，不是「读了多少字符」。
 *
 * [percentLabel] 由 [fraction] 四舍五入而来，所以它永远描述滑块的落点、不描述手指的轨迹；
 * 同一个 [paragraphIndex] 无论经由 [TextProgressJump.targetOf] 还是
 * [TextProgressJump.targetOfParagraph] 计算，得到的 target 完全相同（拖动预览与松手落地不会抖）。
 */
internal data class TextProgressTarget(
    val paragraphIndex: Int,
    val fraction: Float,
    val percentLabel: String,
    val positionLabel: String,
    val chapterTitle: String?,
    val chapterIndex: Int,
    val chapterCount: Int,
)

/**
 * 进度跳转的纯函数层：比例 / 段落下标 / 上一章下一章 → [TextProgressTarget]。
 *
 * 比例一律用**已读段数口径**（1 基，(index + 1) / count），理由见 [TextProgressTarget]；
 * 段落下标与文案的口径来自 [TextDocument]，章节归属来自 [TextOutline]。不碰 Android、不持状态。
 *
 * [chapters] 一律按 [TextOutline.build] 的顺序传入（[TextChapter.paragraphIndex] 递增），
 * 否则 [TextOutline.chapterAt] 的二分前提不成立，章节归属会错位。
 */
internal object TextProgressJump {

    /** 空文档没有「第几段」可言：[TextProgressTarget.positionLabel] 落成 0/0 而不是 1/0。 */
    private const val EMPTY_POSITION_LABEL = "第 0 段 / 共 0 段"

    /**
     * 比例 → 目标：按「1 基已读段数」反解最近的分段，index = round(fraction * count) - 1，
     * 再钳进 0..count-1。
     *
     * 反解的误差不超过 1 / (2 * count)，也就是滑块松手后停在手指附近的那一段上，
     * 而不是一律向上或一律向下取整（那会让滑块整体偏半格）。NaN、负数、超过 1 的值先归一到
     * 0..1，空文档没有分段可吸附，直接交给 [targetOfParagraph] 的 0 号兜底。
     */
    fun targetOf(document: TextDocument, chapters: List<TextChapter>, fraction: Float): TextProgressTarget {
        val count = document.paragraphs.size
        if (count == 0) return targetOfParagraph(document, chapters, 0)
        val safe = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
        val index = ((safe * count).roundToInt() - 1).coerceIn(0, count - 1)
        return targetOfParagraph(document, chapters, index)
    }

    /**
     * 段落下标 → 目标；越界按 coerce 收进 [0, paragraphs.size - 1]，空文档归 0。
     *
     * [TextProgressTarget.fraction] = (index + 1) / count（1 基），空文档为 0。
     */
    fun targetOfParagraph(
        document: TextDocument,
        chapters: List<TextChapter>,
        paragraphIndex: Int,
    ): TextProgressTarget {
        val paragraphCount = document.paragraphs.size
        val index = if (paragraphCount == 0) 0 else paragraphIndex.coerceIn(0, paragraphCount - 1)
        val fraction = if (paragraphCount == 0) 0f else (index + 1).toFloat() / paragraphCount
        val chapterPosition = chapterPositionOf(chapters, index)
        val chapter = if (chapterPosition >= 0) chapters[chapterPosition] else null
        return TextProgressTarget(
            paragraphIndex = index,
            fraction = fraction,
            percentLabel = percentLabelOf(fraction),
            positionLabel = if (paragraphCount == 0) {
                EMPTY_POSITION_LABEL
            } else {
                "第 " + (index + 1) + " 段 / 共 " + paragraphCount + " 段"
            },
            chapterTitle = chapter?.title,
            chapterIndex = chapterPosition + 1,
            chapterCount = chapters.size,
        )
    }

    /**
     * 上一章 / 下一章：返回那一章的**第一条**（[TextChapter.paragraphIndex]），不是「当前段挪一段」。
     *
     * - [direction] > 0：下一章。基准是「当前段所属章」，所以当前段正好停在某章第一条时也会离开
     *   这一章 —— 否则「下一章」原地返回同一个 target，用户点多少下都停在原地，一按「上一章」
     *   又弹回来；
     * - [direction] < 0：上一章；
     * - [direction] == 0、没有目录、已经是末章 / 首章 → null；
     * - 当前段在首章之前（前言、书名页，[TextOutline.chapterAt] 返回 null）：前进落到第一章，
     *   后退仍是 null —— 前面确实没有可跳的章。
     */
    fun chapterStep(
        document: TextDocument,
        chapters: List<TextChapter>,
        currentParagraphIndex: Int,
        direction: Int,
    ): TextProgressTarget? {
        if (direction == 0 || chapters.isEmpty()) return null
        val currentPosition = chapterPositionOf(chapters, currentParagraphIndex)
        val targetPosition = if (direction > 0) {
            if (currentPosition < 0) 0 else currentPosition + 1
        } else {
            if (currentPosition < 1) return null
            currentPosition - 1
        }
        if (targetPosition !in chapters.indices) return null
        return targetOfParagraph(document, chapters, chapters[targetPosition].paragraphIndex)
    }

    /**
     * [paragraphIndex] 所属章在 [chapters] 里的下标，没有则 -1。
     *
     * [TextOutline.chapterAt] 返回的就是列表里的原对象，按引用找下标即可 —— 目录可能几百条，
     * 不另抄一遍二分，也不依赖 [TextChapter] 的 ==（同标题同锚点的重复条目会认错）。
     */
    private fun chapterPositionOf(chapters: List<TextChapter>, paragraphIndex: Int): Int {
        val chapter = TextOutline.chapterAt(chapters, paragraphIndex) ?: return -1
        return chapters.indexOfFirst { it === chapter }
    }

    /** 契约文案：`((fraction * 100).roundToInt()).coerceIn(0, 100)` + `%`。 */
    private fun percentLabelOf(fraction: Float): String {
        val safe = if (fraction.isFinite()) fraction else 0f
        val percent = (safe * 100f).roundToInt().coerceIn(0, 100)
        return percent.toString() + "%"
    }
}
