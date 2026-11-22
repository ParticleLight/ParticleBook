package app.particlebook.android.reader.tts

/**
 * 一句待朗读文本。[startChar]/[endChar] 是"这一页可选中文本"的字符偏移，end 为 exclusive；
 * 与 [app.particlebook.android.reader.epub.EpubReaderController.pageText] 同一坐标系，
 * 因此同一份区间既能送去合成语音，也能交给渲染层画成高亮。
 */
internal data class TtsSentence(
    val startChar: Int,
    val endChar: Int,
    val text: String,
) {
    val length: Int get() = endChar - startChar
}

/** 文本 → 句子序列（纯 JVM）。空文本返回空表，不做任何兜底朗读。 */
internal object TtsText {

    fun sentences(pageText: String): List<TtsSentence> {
        if (pageText.isBlank()) return emptyList()
        return TtsSentenceSplitter.split(pageText).map { span ->
            TtsSentence(span.startChar, span.endChar, pageText.substring(span.startChar, span.endChar))
        }
    }
}
