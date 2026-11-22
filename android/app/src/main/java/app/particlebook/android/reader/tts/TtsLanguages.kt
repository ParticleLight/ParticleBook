package app.particlebook.android.reader.tts

import java.util.Locale

/**
 * 按文本内容选朗读音库：中文书走系统默认语言，拉丁文本走 en-US。
 *
 * 书架里中文书和英文书混排，用固定 locale 会让英文书用中文音库念（或反过来），
 * 听感上是"念不对"而不是"念得差"。判定只看字符构成，不依赖书名或 locale 设置，
 * 这样同一本书里中英夹杂的段落也能各按各的念。
 */
internal object TtsLanguages {

    /** 中文（含中文标点）字符占比达到该阈值就按中文处理。 */
    private const val CJK_RATIO = 0.3f

    fun localeFor(text: String, fallback: Locale = Locale.getDefault()): Locale {
        if (text.isEmpty()) return fallback
        var cjk = 0
        var letters = 0
        for (ch in text) {
            val block = Character.UnicodeBlock.of(ch)
            when {
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                    block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
                    block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS -> cjk += 1
                ch.isLetter() -> letters += 1
            }
        }
        val total = cjk + letters
        if (total == 0) return fallback
        return if (cjk.toFloat() / total >= CJK_RATIO) fallback else Locale.US
    }
}
