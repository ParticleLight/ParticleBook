package app.particlebook.android.ui

import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.reader.text.TextAnnotations
import app.particlebook.android.reader.text.TextDocument

/** 已确认实际可见的段落快照；恢复或跳转请求本身不构成可写进度。 */
internal data class TextVisibleProgress(
    val locator: ReaderLocator.Text,
    val paragraph: Int,
    val paragraphs: Int,
)

/** 恢复不精确时保留已存原文，直到用户实际换段或明确接受当前可见位置。 */
internal data class TextProgressState(
    val preserveSavedRaw: Boolean = false,
    val displayed: TextVisibleProgress? = null,
) {
    val writable: TextVisibleProgress?
        get() = displayed?.takeIf { !preserveSavedRaw && it.isValid() }

    fun paragraphShown(progress: TextVisibleProgress, userInitiated: Boolean): TextProgressState {
        if (!progress.isValid()) return this
        // 同段的字符偏移或总段数变化可能来自重排，不算用户主动换段。
        val changed = displayed?.paragraph != progress.paragraph
        return copy(
            preserveSavedRaw = preserveSavedRaw && !(userInitiated && changed),
            displayed = progress,
        )
    }

    /** “使用当前阅读位置”允许单段文档显式解除保护，但仍要求已实际显示有效位置。 */
    fun acceptDisplayed(): TextProgressState =
        if (displayed?.isValid() == true) copy(preserveSavedRaw = false) else this
}

/** 普通恢复优先保留段号，避免图片与后文共享字符偏移时跳到另一段。 */
internal data class TextProgressRestore(
    val paragraphIndex: Int,
    val state: TextProgressState,
) {
    companion object {
        fun from(
            document: TextDocument,
            rawLocator: String?,
            savedLocator: ReaderLocator?,
            savedParagraph: Int?,
            requestedLocator: ReaderLocator? = null,
        ): TextProgressRestore {
            if (document.isEmpty) {
                return TextProgressRestore(
                    paragraphIndex = 0,
                    state = TextProgressState(
                        preserveSavedRaw = !rawLocator.isNullOrBlank() ||
                            savedLocator != null || savedParagraph != null,
                    ),
                )
            }

            val requested = (requestedLocator as? ReaderLocator.Text)?.takeIf { it.fitsIn(document) }
            if (requested != null) {
                return TextProgressRestore(
                    paragraphIndex = TextAnnotations.paragraphIndexOfOffset(document, requested.offset),
                    state = TextProgressState(),
                )
            }

            val saved = (savedLocator as? ReaderLocator.Text)?.takeIf { it.fitsIn(document) }
            val paragraphIndex = savedParagraph?.coerceIn(0, document.paragraphs.lastIndex)
                ?: saved?.let { TextAnnotations.paragraphIndexOfOffset(document, it.offset) }
                ?: 0
            val invalidParagraph = savedParagraph != null && savedParagraph !in document.paragraphs.indices
            val invalidLocator = (savedLocator != null || !rawLocator.isNullOrBlank()) && saved == null
            val mismatchedOffset = savedParagraph != null &&
                savedParagraph in document.paragraphs.indices && saved != null &&
                document.charOffsets.getOrNull(savedParagraph) != saved.offset
            return TextProgressRestore(
                paragraphIndex = paragraphIndex,
                state = TextProgressState(
                    preserveSavedRaw = invalidParagraph || invalidLocator || mismatchedOffset,
                ),
            )
        }
    }
}

private fun TextVisibleProgress.isValid(): Boolean =
    paragraphs > 0 && paragraph in 0 until paragraphs && locator.offset >= 0 && locator.length >= 0

/** EOF 单点有效；先转 Long 再求和，避免两个非负 Int 的和溢出后被误当成合法区间。 */
private fun ReaderLocator.Text.fitsIn(document: TextDocument): Boolean =
    offset >= 0 && length >= 0 && offset.toLong() + length.toLong() <= document.charCount.toLong()
