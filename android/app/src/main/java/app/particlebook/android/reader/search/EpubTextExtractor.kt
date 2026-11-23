package app.particlebook.android.reader.search

import app.particlebook.android.reader.epub.EpubBlock
import app.particlebook.android.reader.epub.parser.EpubParser
import java.io.File

/**
 * 抽取阶段的产物：整本 EPUB 的可索引章节。
 *
 * 这里只做「书 → 纯文本」的确定性转换，不碰数据库、不依赖安卓：同一份输入永远得到同一份输出，
 * 索引器据此算文本哈希决定是否重建。
 */
data class ExtractedEpub(
    /** 按 spine 顺序、已去重、已剔除空章/坏章的章节。 */
    val chapters: List<ExtractedChapter>,
    /** spine 条目总数（含重复 href 与坏章），用于诊断与规模报告。 */
    val spineEntryCount: Int,
    /** 解析成功但没有任何可检索文本的章节数（只有图片/分隔线/空 body）。 */
    val emptyChapterCount: Int,
    /** 解析失败或 ZIP 缺失而被跳过的章节数；坏章不阻塞整本索引。 */
    val brokenChapterCount: Int,
    /** spine 里重复出现的 href 数量；同一 href 只索引一次，与阅读器 chapterIndexForHref 的首个匹配一致。 */
    val duplicateSpineEntryCount: Int,
    /** 解析器警告 + 坏章原因；只进日志，不影响索引结果。 */
    val warnings: List<String>,
)

data class ExtractedChapter(
    /** EPUB 根相对路径，直接作为 [app.particlebook.android.reader.ReaderLocator.Epub.chapterHref]。 */
    val chapterHref: String,
    val chapterTitle: String?,
    /** 第一个贡献文本的 block 下标；locator 的 block 位，用于反查章节内位置。 */
    val firstTextBlockIndex: Int,
    /** 章节纯文本：非空 block 按文档顺序用 '\n' 连接，首尾已 trim。 */
    val text: String,
)

/**
 * EPUB 章节文本抽取器（纯 JVM）。
 *
 * 文本语义与阅读器的可选中文本保持一致（见 EpubReaderController.selectableTextOf）：
 * 段落/标题/列表项取 text，表格逐行「单元格空格连接、行间换行」，图片取 alt；
 * 区别只在于表格会丢掉整行空单元格，避免产生只有空格的「假文本」章节。
 *
 * 刻意不把 TOC 标题拼进正文：搜索命中要能跳回书中真实存在的文字，
 * 拼接目录会让 snippet 出现读者在页面上看不到的句子。
 */
class EpubTextExtractor {

    fun extract(file: File): ExtractedEpub = EpubParser.open(file).use { parser ->
        val spine = parser.book.spine
        val seenHrefs = HashSet<String>(spine.size)
        val chapters = ArrayList<ExtractedChapter>(spine.size)
        val warnings = ArrayList<String>()
        var emptyChapters = 0
        var brokenChapters = 0
        var duplicates = 0

        for (index in spine.indices) {
            val item = spine[index]
            if (!seenHrefs.add(item.href)) {
                duplicates++
                continue
            }
            val chapter = try {
                parser.readChapter(index)
            } catch (error: Exception) {
                brokenChapters++
                warnings += "chapter " + index + " (" + item.href + "): " +
                    (error.message ?: error.javaClass.simpleName)
                continue
            }
            val text = chapterText(chapter.blocks)
            if (text.isEmpty()) {
                emptyChapters++
                continue
            }
            chapters += ExtractedChapter(
                chapterHref = chapter.href,
                chapterTitle = chapter.title,
                firstTextBlockIndex = chapter.blocks.indexOfFirst { blockText(it).isNotEmpty() }
                    .coerceAtLeast(0),
                text = text,
            )
        }
        for (warning in parser.warnings) {
            if (warning !in warnings) warnings += warning
        }
        ExtractedEpub(
            chapters = chapters,
            spineEntryCount = spine.size,
            emptyChapterCount = emptyChapters,
            brokenChapterCount = brokenChapters,
            duplicateSpineEntryCount = duplicates,
            warnings = warnings,
        )
    }

    private fun chapterText(blocks: List<EpubBlock>): String {
        val builder = StringBuilder()
        for (block in blocks) {
            val text = blockText(block)
            if (text.isEmpty()) continue
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(text)
        }
        return builder.toString().trim()
    }

    private fun blockText(block: EpubBlock): String = when (block) {
        is EpubBlock.Paragraph -> block.text.trim()
        is EpubBlock.Heading -> block.text.trim()
        is EpubBlock.ListItem -> block.text.trim()
        is EpubBlock.Image -> block.alt.orEmpty().trim()
        is EpubBlock.Table -> block.rows
            .map { row -> row.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ") }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        EpubBlock.HorizontalRule, EpubBlock.PageBreak -> ""
    }
}
