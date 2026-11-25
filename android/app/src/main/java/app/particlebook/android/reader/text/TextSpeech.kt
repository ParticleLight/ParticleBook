package app.particlebook.android.reader.text

import app.particlebook.android.reader.tts.TtsSentence
import app.particlebook.android.reader.tts.TtsText

/**
 * 文本阅读器的朗读编排：把段落列表切成「一段一批句子」。
 *
 * 为什么不把整本书拼成一张句子表：**[TtsSentence] 的 startChar / endChar 是段内字符偏移**，
 * 拼盘之后偏移会跨段重叠，高亮就标到别的段落上去了（EPUB 侧同理，所以它的坐标是页内的）。
 * 另外一本 4M 字符的合集全量切句会再复制一份等大的字符串，中低端机在朗读启动的瞬间就会抖。
 *
 * 因此朗读以「段」为单位推进：一段念完由调用方在 [app.particlebook.android.reader.tts.TtsPlayback]
 * 回到 Idle 时驱动下一段。本对象只做纯计算，不持有引擎也不持有 UI 状态。
 */
internal object TextSpeech {

    /**
     * 一段能念的句子；空白段返回空表。
     *
     * 直接复用 [TtsText.sentences]：它按句末标点切分并保证区间可切回原文，
     * 与 EPUB / PDF 是同一套切句口径，听感不会因为格式不同而变。
     */
    fun sentences(paragraph: String): List<TtsSentence> = TtsText.sentences(paragraph)

    /**
     * 一段是否**真的**能念。
     *
     * 先用 isNotBlank 挡一道（便宜，绝大多数空段到此为止），再真切一次确认：
     * 只含省略号、破折号、不间断空格这类字符的段落 isBlank 为假，但切句器把它们全当空白，
     * 结果是一条句子都切不出来。只看 isNotBlank 的话朗读会停在这种段上原地空转——
     * 状态机不推进，用户听到的是"念完一句就卡住"。
     */
    private fun isSpeakable(paragraph: TextParagraph): Boolean =
        paragraph.text.isNotBlank() && sentences(paragraph.text).isNotEmpty()

    /**
     * 从 [from] 起（含）向后找第一个能朗读的段落；到末尾没有则返回 null。
     *
     * [from] 为负按 0 处理（"从负数开始往后找"等价于从头找）；超出末尾返回 null。
     */
    fun firstSpeakableAtOrAfter(paragraphs: List<TextParagraph>, from: Int): Int? {
        if (paragraphs.isEmpty()) return null
        var index = from.coerceAtLeast(0)
        while (index < paragraphs.size) {
            if (isSpeakable(paragraphs[index])) return index
            index += 1
        }
        return null
    }

    /**
     * 从 [from] 起（含）向前找第一个能朗读的段落；没有则返回 null。
     *
     * [from] 超出末尾按最后一段处理；**[from] 为负直接返回 null**（"从 -1 往前找"没有任何一段可找），
     * 不要理解成"夹到第 0 段"——那会让「上一句」在首段第一句时莫名其妙地跳回第 0 段。
     */
    fun lastSpeakableAtOrBefore(paragraphs: List<TextParagraph>, from: Int): Int? {
        if (paragraphs.isEmpty() || from < 0) return null
        var index = from.coerceAtMost(paragraphs.lastIndex)
        while (index >= 0) {
            if (isSpeakable(paragraphs[index])) return index
            index -= 1
        }
        return null
    }

    /**
     * [paragraphIndex] 之后（严格）第一个能朗读的段落；没有则 null。
     *
     * 传 -1 表示"从开头找"，这是朗读起点的自然写法，不需要调用方特判。
     */
    fun nextSpeakable(paragraphs: List<TextParagraph>, paragraphIndex: Int): Int? {
        // Int.MAX_VALUE + 1 会溢出成负数，再被 coerceAtLeast(0) 拉回 0，凭空返回首段。
        if (paragraphIndex == Int.MAX_VALUE) return null
        return firstSpeakableAtOrAfter(paragraphs, paragraphIndex + 1)
    }

    /** [paragraphIndex] 之前（严格）第一个能朗读的段落；没有则 null。 */
    fun previousSpeakable(paragraphs: List<TextParagraph>, paragraphIndex: Int): Int? {
        // 与上面同源的溢出边界：Int.MIN_VALUE - 1 折到正数，会返回到末尾段。
        if (paragraphIndex == Int.MIN_VALUE) return null
        return lastSpeakableAtOrBefore(paragraphs, paragraphIndex - 1)
    }
}
