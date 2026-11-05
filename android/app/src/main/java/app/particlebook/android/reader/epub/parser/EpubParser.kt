package app.particlebook.android.reader.epub.parser

import app.particlebook.android.reader.epub.EpubBlock
import app.particlebook.android.reader.epub.EpubBook
import app.particlebook.android.reader.epub.EpubChapter
import app.particlebook.android.reader.epub.EpubResourceResolver
import app.particlebook.android.reader.epub.EpubTocEntry
import app.particlebook.android.reader.epub.css.CssStylesheet
import app.particlebook.android.reader.epub.model.XhtmlBlockParser
import app.particlebook.android.reader.epub.model.XhtmlParseOptions
import java.io.Closeable
import java.io.File

/**
 * 一个打开的 EPUB：ZIP + OPF 元数据/目录立即解析，章节按需解析。
 *
 * 对外 href 统一为 ZIP 根相对路径。block/CSS 解析委托给 model/css 包，
 * parser 只负责容器/包结构、编码、资源读取和章节装配。
 */
class EpubParser private constructor(
    private val archive: EpubArchive,
    private val parsedPackage: ParsedPackage,
) : Closeable, EpubResourceResolver {

    val book: EpubBook = EpubBook(
        metadata = parsedPackage.metadata,
        manifest = parsedPackage.manifest,
        spine = parsedPackage.spine,
        toc = parsedPackage.toc,
    )

    val warnings: List<String>
        get() = warningList.toList()

    private val warningList = ArrayList<String>()

    fun readChapter(spineIndex: Int): EpubChapter {
        val spineItem = parsedPackage.spine.getOrNull(spineIndex)
            ?: throw EpubParseException("CHAPTER_INDEX: $spineIndex not in 0..${parsedPackage.spine.lastIndex}")
        return readChapterByHref(spineItem.href, spineItem.idref)
    }

    /** 供 TOC/链接跳转使用；href 允许带 fragment。 */
    fun readChapter(href: String): EpubChapter {
        val path = EpubPaths.withoutFragment(href)
        return readChapterByHref(path, null)
    }

    fun readChapterByHref(href: String, idref: String? = null): EpubChapter {
        val path = EpubPaths.normalizePath(href)
        val bytes = archive.read(path)
            ?: throw EpubParseException("CHAPTER_MISSING: $path")
        val xhtml = EpubText.decode(bytes)
        val fallbackTitle = tocTitleFor(path) ?: idref
        val chapter = try {
            XhtmlBlockParser.parse(
                xhtml = xhtml,
                chapterHref = path,
                stylesheet = CssStylesheet.EMPTY,
                resolver = archive,
                options = XhtmlParseOptions(fallbackTitle = fallbackTitle),
            )
        } catch (e: Exception) {
            throw EpubParseException("BAD_CHAPTER: $path: ${e.message}", e)
        }
        val normalized = normalizeChapter(chapter)
        if (normalized.blocks.isEmpty()) warningList += "chapter has no blocks: $path"
        return normalized
    }

    /**
     * block 解析由 detail-pages 负责；parser 只做契约要求的最后一道文本归一：
     * NBSP->空格、去软连字符/BOM。保留段落内换行，不重新折叠空白。
     */
    private fun normalizeChapter(chapter: EpubChapter): EpubChapter =
        chapter.copy(blocks = chapter.blocks.map { block ->
            when (block) {
                is EpubBlock.Paragraph -> block.copy(text = normalizeText(block.text))
                is EpubBlock.Heading -> block.copy(text = normalizeText(block.text))
                is EpubBlock.ListItem -> block.copy(text = normalizeText(block.text))
                is EpubBlock.Image -> block.copy(alt = block.alt?.let(::normalizeText))
                is EpubBlock.Table -> block.copy(
                    rows = block.rows.map { row -> row.map(::normalizeText) },
                )
                EpubBlock.HorizontalRule, EpubBlock.PageBreak -> block
            }
        })

    private fun normalizeText(text: String): String =
        text.replace('\u00A0', ' ')
            .replace("\u00AD", "")
            .replace("\uFEFF", "")

    /** 解析结果里所有 manifest 声明的外部 CSS（诊断/设备探针用）。 */
    fun manifestCssHrefs(): List<String> = parsedPackage.cssHrefs()

    override fun read(href: String): ByteArray? = archive.read(href)

    fun hasEntry(href: String): Boolean = archive.hasEntry(href)

    override fun close() {
        archive.close()
    }

    private fun tocTitleFor(path: String): String? {
        fun visit(entries: List<EpubTocEntry>): String? {
            for (entry in entries) {
                if (EpubPaths.withoutFragment(entry.href) == path) return entry.title
                visit(entry.children)?.let { return it }
            }
            return null
        }
        return visit(book.toc)
    }

    companion object {
        fun open(file: File): EpubParser {
            val archive = EpubArchive.open(file)
            try {
                val warnings = ArrayList<String>()
                val packageData = EpubPackageParser.parse(archive, warnings)
                return EpubParser(archive, packageData).also { it.warningList += warnings }
            } catch (e: Exception) {
                archive.close()
                if (e is EpubParseException) throw e
                throw EpubParseException("PARSE_FAILED: ${e.message}", e)
            }
        }
    }
}
