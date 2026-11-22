package app.particlebook.android.ui

import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.reader.epub.EpubBlock
import app.particlebook.android.reader.epub.layout.EpubChapterLayout
import app.particlebook.android.reader.epub.render.EpubPageIndex

/** 已完成排版的可见页快照；切章请求排版期间仍保留上一张，避免保存尚未显示的目标。 */
internal data class EpubVisibleProgress(
    val locator: ReaderLocator.Epub,
    val page: Int,
    val pages: Int,
)

/** 恢复失败时保留数据库原文；仅实际用户位置变化才允许新位置替换它。 */
internal data class EpubProgressState(
    val preserveSavedRaw: Boolean = false,
    val displayed: EpubVisibleProgress? = null,
) {
    val writable: EpubVisibleProgress?
        get() = displayed?.takeUnless { preserveSavedRaw }

    /** pageAt 可近似落到邻块/末行；首次恢复只有精确映射成功才允许自动覆盖原文。 */
    fun initialLayoutApplied(
        layout: EpubChapterLayout,
        requestedLocator: ReaderLocator.Epub?,
        userInitiated: Boolean,
    ): EpubProgressState {
        if (userInitiated || requestedLocator == null) return this
        val block = layout.measuredBlocks.firstOrNull { it.blockIndex == requestedLocator.blockIndex }?.block
        val textLength = when (block) {
            is EpubBlock.Paragraph -> block.text.length
            is EpubBlock.Heading -> block.text.length
            is EpubBlock.ListItem -> block.text.length
            is EpubBlock.Image -> block.alt?.length ?: 0
            is EpubBlock.Table -> block.rows.joinToString("\n") { it.joinToString(" ") }.length
            else -> 0
        }
        val restored = block != null && requestedLocator.charOffset in 0..textLength &&
            EpubPageIndex.pageForLocator(layout, requestedLocator) != null
        return if (restored) this else copy(preserveSavedRaw = true)
    }

    fun pageShown(progress: EpubVisibleProgress, userInitiated: Boolean): EpubProgressState {
        if (progress.pages <= 0 || progress.page !in 0 until progress.pages) return this
        val changed = displayed?.let { previous ->
            previous.locator.chapterHref != progress.locator.chapterHref || previous.page != progress.page
        } ?: true
        return copy(
            preserveSavedRaw = preserveSavedRaw && !(userInitiated && changed),
            displayed = progress,
        )
    }

    companion object {
        fun restored(rawLocator: String?, restoredLocator: ReaderLocator.Epub?): EpubProgressState =
            EpubProgressState(preserveSavedRaw = rawLocator != null && restoredLocator == null)
    }
}

/** 先用有效 href 恢复章节，失败保留原文并继续用原章内页号在章 0 兜底。 */
internal data class EpubProgressRestore(
    val chapterIndex: Int,
    val locator: ReaderLocator.Epub?,
    val page: Int?,
    val state: EpubProgressState,
) {
    companion object {
        fun from(
            rawLocator: String?,
            savedLocator: ReaderLocator?,
            savedPage: Int?,
            chapterIndexForHref: (String) -> Int?,
        ): EpubProgressRestore {
            val epub = savedLocator as? ReaderLocator.Epub
            val chapter = epub?.let { chapterIndexForHref(it.chapterHref) }
            val restored = epub?.takeIf { chapter != null }
            return EpubProgressRestore(
                chapterIndex = chapter ?: 0,
                locator = restored,
                page = savedPage,
                state = EpubProgressState.restored(rawLocator, restored),
            )
        }
    }
}
