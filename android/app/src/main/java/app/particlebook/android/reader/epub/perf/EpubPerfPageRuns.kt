package app.particlebook.android.reader.epub.perf

import app.particlebook.android.reader.epub.EpubBlock
import app.particlebook.android.reader.epub.layout.EpubChapterLayout
import app.particlebook.android.reader.epub.layout.Page

/** 当前页实际绘制内容的分类，用来判定 text_only；空白页不算有内容。 */
data class EpubPerfPageContent(
    val hasContent: Boolean,
    val textOnly: Boolean,
)

/**
 * 只按页面实际的 slice 判定，不看章节里“存在文本”就推断当前页有文字。
 * 图片、表格、分隔线都算非文本内容；分页符不绘制，忽略。
 */
class EpubPerfPageRuns(layout: EpubChapterLayout) {
    // hidden/零高度块会从 measuredBlocks 移除；slice 保留原始 blockIndex。
    private val blocks = layout.measuredBlocks.associate { it.blockIndex to it.block }

    fun of(page: Page): EpubPerfPageContent {
        var hasContent = false
        var textRuns = 0
        var nonTextRuns = 0
        for (slice in page.slices) {
            if (slice.heightPx <= 0) continue
            val block = blocks[slice.blockIndex] ?: continue
            when (block) {
                is EpubBlock.Paragraph,
                is EpubBlock.Heading,
                is EpubBlock.ListItem,
                -> {
                    if (slice.lineEnd > slice.lineStart) {
                        hasContent = true
                        textRuns += 1
                    }
                }
                is EpubBlock.Image,
                is EpubBlock.Table,
                is EpubBlock.HorizontalRule,
                -> {
                    hasContent = true
                    nonTextRuns += 1
                }
                is EpubBlock.PageBreak -> Unit
            }
        }
        return EpubPerfPageContent(
            hasContent = hasContent,
            textOnly = hasContent && textRuns > 0 && nonTextRuns == 0,
        )
    }

    companion object {
        fun of(layout: EpubChapterLayout, page: Page): EpubPerfPageContent =
            EpubPerfPageRuns(layout).of(page)
    }
}
