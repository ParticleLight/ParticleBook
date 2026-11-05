package app.particlebook.android.reader.epub

/**
 * EPUB 原生引擎的稳定数据契约（Lead 维护，P0 冻结）。
 *
 * 所有权：
 * - 本文件由 Lead 独占，parser/layout/css 只能 import，不要修改。
 * - 具体解析在 reader/epub/parser/（search-ux），block/CSS 在 reader/epub/model、css（detail-pages）。
 *
 * 设计约束：
 * - 纯 JVM 可构造，不依赖 Android framework，便于单测。
 * - 所有位置/尺寸使用与渲染无关的逻辑单位；像素换算由 layout 层负责。
 * - 文本保持原始 Unicode；实体解码/空白归一化在 parser 完成。
 */
data class EpubMetadata(
    val title: String,
    val author: String? = null,
    val language: String? = null,
    val identifier: String? = null,
)

data class EpubManifestItem(
    val id: String,
    val href: String,
    val mediaType: String,
    val properties: Set<String> = emptySet(),
)

data class EpubSpineItem(
    val idref: String,
    val href: String,
    val linear: Boolean = true,
)

data class EpubTocEntry(
    val title: String,
    val href: String,
    val children: List<EpubTocEntry> = emptyList(),
)

data class EpubBook(
    val metadata: EpubMetadata,
    val manifest: List<EpubManifestItem>,
    val spine: List<EpubSpineItem>,
    val toc: List<EpubTocEntry> = emptyList(),
)

/** 一章的解析结果；blocks 已按文档顺序排列。 */
data class EpubChapter(
    val href: String,
    val title: String? = null,
    val blocks: List<EpubBlock> = emptyList(),
)

enum class EpubAlign { START, CENTER, END, JUSTIFY }

/**
 * 渲染所需的样式子集。null 表示“继承/未设置”，由 layout 层回退到主题默认值。
 * color 为 ARGB；字号单位 sp；边距单位 dp；行高为倍数。
 */
data class EpubStyle(
    val fontSizeSp: Float? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val color: Int? = null,
    val align: EpubAlign = EpubAlign.START,
    val indentEm: Float = 0f,
    val lineHeightMultiplier: Float? = null,
    val marginTopDp: Float = 0f,
    val marginBottomDp: Float = 0f,
    val hidden: Boolean = false,
    val pageBreakBefore: Boolean = false,
)

/** 归一化后的 block 模型；parser 负责把 XHTML 映射到这里。 */
sealed interface EpubBlock {
    val style: EpubStyle

    data class Paragraph(
        val text: String,
        override val style: EpubStyle = EpubStyle(),
    ) : EpubBlock

    data class Heading(
        val level: Int,
        val text: String,
        override val style: EpubStyle = EpubStyle(),
    ) : EpubBlock

    data class ListItem(
        val ordered: Boolean,
        val index: Int,
        val text: String,
        override val style: EpubStyle = EpubStyle(),
    ) : EpubBlock

    data class Image(
        val src: String,
        val alt: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        override val style: EpubStyle = EpubStyle(),
    ) : EpubBlock

    data class Table(
        val rows: List<List<String>>,
        override val style: EpubStyle = EpubStyle(),
    ) : EpubBlock

    data object HorizontalRule : EpubBlock {
        override val style: EpubStyle = EpubStyle()
    }

    data object PageBreak : EpubBlock {
        override val style: EpubStyle = EpubStyle()
    }
}

/** 解析器读取 EPUB 内部资源（图片/CSS/章节）的入口；实现由 parser 提供。 */
fun interface EpubResourceResolver {
    fun read(href: String): ByteArray?
}
