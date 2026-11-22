package app.particlebook.android.reader.pdf

import app.particlebook.android.reader.tts.TtsSentence
import app.particlebook.android.reader.tts.TtsText

/**
 * PDF 一页的朗读内容：送 TTS 的句子，以及每句在字符层上的区间。
 *
 * 为什么需要这一层（EPUB 没有）：EPUB 的高亮坐标是 blockIndex + 页内字符偏移，直接交给
 * 渲染层即可；PDF 的文本层只有「字符 + 字符框」，所以句子必须先落到 [PdfChar.index] 区间，
 * 才能用 [PdfTextLayerController.selectionRects] 画出与选区同一套矩形。
 * 句子切分本身复用已测的 [TtsText]，本类不重复实现切句规则。
 *
 * 为什么可能整页没有高亮区间：PDFium 的整页文本 [PdfPageText.text] 会带上逐字符文本里
 * 并不存在的生成字符（行尾换行、补空格）。本类只允许跳过这类「整页文本独有的空白」，
 * 遇到任何非空白差异就整页放弃定位 —— 宁可只显示控制条与句子进度，也不画一个位置
 * 错误的矩形（伪造高亮比没有高亮更糟）。
 */
internal class PdfTtsPage private constructor(
    /** 页码，取自 [PdfPageText.pageIndex]。 */
    val page: Int,
    /**
     * 实际送进切句的文本：整页文本把 '\r' 等长换成 '\n'。
     *
     * 为什么换：PDFium 用 '\r' 表示生成的行尾，而 [TtsText] 的切分器只把 '\n' 当句边界；
     * 不换的话，一页没有句号的英文正文会被当成一句念完。替换前后长度不变，句子偏移仍指向
     * 同一行同一列，不影响字符框对齐。
     */
    val text: String,
    val sentences: List<TtsSentence>,
    private val chars: List<PdfChar>,
    private val charRanges: List<IntRange?>,
) {
    /**
     * 第 [sentenceIndex] 句覆盖的 [PdfChar.index] 半开区间；null 表示本页无法定位
     * （对齐失败，或该句没有字符框），调用方应跳过高亮而不是猜一个范围。
     */
    fun charRange(sentenceIndex: Int): IntRange? = charRanges.getOrNull(sentenceIndex)

    /**
     * 第 [sentenceIndex] 句的高亮矩形，与选区同一套几何；无法定位时返回空列表。
     *
     * 为什么在内存里算而不是回头问 [PdfTextLayerController]：控制器那条路要拿它的锁并在 IO
     * 线程上重读文本页，真机实测这一步在朗读进行中不会返回（控制条照常推进，矩形永远是空），
     * 而本类构造时已经握着同一份 [PdfChar] 列表。纯函数计算没有跨线程等待，也能在 JVM 单测里钉住。
     */
    fun rects(sentenceIndex: Int): List<PdfNormalizedRect> {
        val range = charRange(sentenceIndex) ?: return emptyList()
        return PdfSelectionGeometry.rects(chars, range.first, range.last + 1)
    }

    companion object {
        /** 由既有文本抽取结果构造；不访问 PDFium、不走 IO，可在主线程之外的任意线程调用。 */
        fun from(page: PdfPageText): PdfTtsPage {
            val text = page.text.replace('\r', '\n')
            val sentences = TtsText.sentences(text)
            val starts = alignedCharStarts(text, page.chars)
            return PdfTtsPage(
                page = page.pageIndex,
                text = text,
                sentences = sentences,
                chars = page.chars,
                charRanges = charRangesFor(page.chars, sentences, starts),
            )
        }

        /**
         * 把逐字符文本对齐到整页文本，返回每个字符在 [text] 中的下标（空白字符记 -1）；不可靠时返回 null。
         *
         * 只比较**非空白**字符。真机上这两套文本的空白并不一致：整页文本是按行拼接出来的
         * （行尾补空格或被丢掉），逐字符文本里的空白是独立条目；按字符逐个比会因为这些空白
         * 整页失配，实测就是「控制条在念、屏幕上永远没有高亮」。非空白字符的顺序与内容才是
         * 两套文本共享的事实，一旦它们对不上仍然返回 null —— 宁可不画，也不画错位置。
         */
        private fun alignedCharStarts(text: String, chars: List<PdfChar>): IntArray? {
            if (chars.isEmpty()) return null
            val solid = StringBuilder(text.length)
            val solidPositions = ArrayList<Int>(text.length)
            for (index in text.indices) {
                val value = text[index]
                if (value.isWhitespace()) continue
                solid.append(value)
                solidPositions.add(index)
            }
            val starts = IntArray(chars.size) { -1 }
            var solidCursor = 0
            for (index in chars.indices) {
                val value = chars[index].text
                if (value.length != 1) return null
                val character = value[0]
                if (character.isWhitespace()) continue
                if (solidCursor >= solid.length || solid[solidCursor] != character) return null
                starts[index] = solidPositions[solidCursor]
                solidCursor += 1
            }
            return if (solidCursor == solid.length) starts else null
        }

        /**
         * 句子 → 字符索引区间。句子有序且互不重叠，因此一次线性扫描即可。
         *
         * [chars] 的 index 可能不连续（PdfTextSource 会跳过 '\u0000'），这里取首尾字符的
         * index 组成半开区间；中间的空洞不存在的字符框，selectionRects 会按 chars 过滤。
         */
        private fun charRangesFor(
            chars: List<PdfChar>,
            sentences: List<TtsSentence>,
            starts: IntArray?,
        ): List<IntRange?> {
            if (starts == null) return List(sentences.size) { null }
            val ranges = ArrayList<IntRange?>(sentences.size)
            var cursor = 0
            for (sentence in sentences) {
                // 空白字符没有位置标记（starts 为 -1）：跳过它但不能据此收尾，
                // 否则句尾的空白会把区间撑到没有字符框的下标上。
                while (cursor < starts.size && (starts[cursor] < 0 || starts[cursor] < sentence.startChar)) {
                    cursor += 1
                }
                var last = -1
                var probe = cursor
                while (probe < starts.size) {
                    if (starts[probe] < 0) {
                        probe += 1
                    } else if (starts[probe] < sentence.endChar) {
                        last = probe
                        probe += 1
                    } else {
                        break
                    }
                }
                if (last < cursor) {
                    ranges.add(null)
                    continue
                }
                val firstIndex = chars[cursor].index
                val afterLast = chars[last].index + 1
                ranges.add(if (afterLast > firstIndex) firstIndex until afterLast else null)
                cursor = last + 1
            }
            return ranges
        }
    }
}

/**
 * 朗读与「当前页」的绑定（翻页即停）。
 *
 * 为什么单独抽成纯函数：PDF 是连续滚动，可见页会随滚动反复重算，甚至左右抖动；
 * 判定只能看「会话页是否还是屏幕上那一页」，不能按页码差或滚动方向推断。
 * 抽出来之后单测能直接钉住这条语义，不必启动 Compose 或跑 Robolectric。
 */
internal object PdfTtsFollow {
    /**
     * [reading] 是正在朗读的页（没在朗读时为 null），[visiblePage] 是当前可见页。
     * 返回 true 表示调用方应停止朗读：句子坐标属于另一页，继续念会和屏幕对不上。
     */
    fun shouldStop(reading: PdfTtsPage?, visiblePage: Int): Boolean =
        reading != null && reading.page != visiblePage
}