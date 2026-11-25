package app.particlebook.android.reader.html

import app.particlebook.android.reader.text.TextImage
import app.particlebook.android.reader.text.TextParagraph
import java.io.File
import java.nio.file.Files
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HtmlTextExtractor] 的行为契约：段落边界、标题层级、丢弃元素、畸形容错与空白折叠。
 * 期望值全部来自当前实现的实际输出（先跑通再固化为断言），不是照抄需求文字。
 */
class HtmlTextExtractorTest {

    private fun texts(html: String): List<String> = HtmlTextExtractor.extract(html).map { it.text }

    private fun levels(html: String): List<Int> = HtmlTextExtractor.extract(html).map { it.headingLevel }

    // ------------------------------------------------------------ ① 空文档

    @Test
    fun emptyDocumentGivesEmptyList() {
        assertTrue(HtmlTextExtractor.extract("").isEmpty())
    }

    @Test
    fun documentWithOnlyTagsGivesEmptyList() {
        val html = "<div><script>var a = 1;</script><img src=\"x.png\"><br></div>"

        assertTrue(HtmlTextExtractor.extract(html).isEmpty())
    }

    // ------------------------------------------------------- ②③ 标题与顺序

    @Test
    fun minimalDocumentGivesHeadingAndBody() {
        val html = "<html><body><h1>第一章</h1><p>正文一。</p><p>正文二。</p></body></html>"

        assertEquals(
            listOf(
                TextParagraph("第一章", 1),
                TextParagraph("正文一。", 0),
                TextParagraph("正文二。", 0),
            ),
            HtmlTextExtractor.extract(html)
        )
    }

    @Test
    fun everyHeadingLevelIsCarried() {
        val html = "<h1>甲</h1><h2>乙</h2><h3>丙</h3><h4>丁</h4><h5>戊</h5><h6>己</h6>"

        assertEquals(listOf("甲", "乙", "丙", "丁", "戊", "己"), texts(html))
        assertEquals(listOf(1, 2, 3, 4, 5, 6), levels(html))
    }

    @Test
    fun headingKeepsLevelAfterInlineTagsAreStripped() {
        assertEquals(listOf("第一章"), texts("<h3>第一<b>章</b></h3>"))
        assertEquals(listOf(3), levels("<h3>第一<b>章</b></h3>"))
    }

    @Test
    fun headingLevelAppliesToEveryLineOfTheHeading() {
        assertEquals(listOf("甲", "乙"), texts("<h2>甲<br>乙</h2>"))
        assertEquals(listOf(2, 2), levels("<h2>甲<br>乙</h2>"))
    }

    @Test
    fun headingsAndBodyKeepDocumentOrder() {
        val html = "<h2>二</h2><p>甲</p><h3>三</h3><p>乙</p>"

        assertEquals(listOf("二", "甲", "三", "乙"), texts(html))
        assertEquals(listOf(2, 0, 3, 0), levels(html))
    }

    @Test
    fun selfClosingHeadingDoesNotLeakItsLevel() {
        assertEquals(listOf("甲"), texts("<h1/>甲"))
        assertEquals(listOf(0), levels("<h1/>甲"))
    }

    // ------------------------------------------------------------- ④ 块级切段

    @Test
    fun blockTagsSplitParagraphs() {
        assertEquals(listOf("甲", "乙", "丙"), texts("<div>甲<p>乙</p>丙</div>"))
    }

    @Test
    fun brSplitsParagraphIncludingSelfClosingForm() {
        assertEquals(listOf("甲", "乙", "丙"), texts("<p>甲<br>乙<br/>丙</p>"))
        assertEquals(listOf(0, 0, 0), levels("<p>甲<br>乙<br/>丙</p>"))
    }

    @Test
    fun listItemsAreOneParagraphEach() {
        assertEquals(listOf("一", "二"), texts("<ul><li>一</li><li>二</li></ul>"))
    }

    @Test
    fun nestedListKeepsItemBoundaries() {
        val html = "<ul><li>一<ul><li>一甲</li></ul>一尾</li><li>二</li></ul>"

        assertEquals(listOf("一", "一甲", "一尾", "二"), texts(html))
    }

    @Test
    fun tableCellsAreOneParagraphEach() {
        val html = "<table><thead><tr><th>表头</th></tr></thead>" +
            "<tbody><tr><td>甲</td><td>乙</td></tr></tbody></table>"

        assertEquals(listOf("表头", "甲", "乙"), texts(html))
    }

    @Test
    fun strayBlockEndTagAlsoSplits() {
        assertEquals(listOf("甲", "乙"), texts("甲</div>乙"))
    }

    // ------------------------------------------------------------- ⑤ pre 分段

    @Test
    fun preSplitsOnLineBreaks() {
        assertEquals(listOf("甲", "乙", "丙"), texts("<pre>甲\n乙\n\n丙</pre>"))
    }

    @Test
    fun preStillFoldsInlineWhitespace() {
        assertEquals(listOf("甲 乙"), texts("<pre>  甲\t\t乙  </pre>"))
    }

    @Test
    fun preBrAlsoSplits() {
        assertEquals(listOf("甲", "乙"), texts("<pre>甲<br>乙</pre>"))
    }

    // ------------------------------------------------------------- ⑥ 整棵丢弃

    @Test
    fun scriptAndStyleAreDroppedWithoutSplittingText() {
        val html = "<p>前<script>if (a < b) { x = '<b>'; }</script>" +
            "中<style>.a::before { content: '<>'; }</style>后</p>"

        assertEquals(listOf("前中后"), texts(html))
    }

    @Test
    fun headAndTitleAreDropped() {
        val html = "<!doctype html><html><head><title>书名</title><meta charset=\"utf-8\"></head>" +
            "<body><p>正文</p></body></html>"

        assertEquals(listOf("正文"), texts(html))
    }

    @Test
    fun unclosedHeadEndsAtBody() {
        val html = "<html><head><title>书名</title><body><p>正文</p></body></html>"

        assertEquals(listOf("正文"), texts(html))
    }

    @Test
    fun loneTitleElementIsDropped() {
        assertEquals(listOf("正文"), texts("<title>书名</title><p>正文</p>"))
    }

    @Test
    fun selectButtonAndSvgAreDropped() {
        val html = "<p>甲<select><option>已选</option></select><button>按下</button>乙</p>"

        assertEquals(listOf("甲乙"), texts(html))
    }

    @Test
    fun droppedElementTracksNestedSameTag() {
        assertEquals(listOf("甲乙"), texts("<p>甲<svg><svg></svg>视觉</svg>乙</p>"))
    }

    @Test
    fun voidEmbedDoesNotSwallowFollowingText() {
        assertEquals(listOf("甲乙"), texts("<p>甲<embed src=\"a.swf\">乙</p>"))
    }

    // ------------------------------------------------------------- ⑦ 注释与声明

    @Test
    fun commentsAreDropped() {
        assertEquals(listOf("甲乙"), texts("<p>甲<!-- 注 释 -->乙</p>"))
    }

    @Test
    fun unterminatedCommentSwallowsRest() {
        assertEquals(listOf("甲"), texts("甲<!-- 未闭合"))
    }

    @Test
    fun processingInstructionAndDoctypeAreIgnored() {
        val html = "<?xml version=\"1.0\" encoding=\"utf-8\"?><!DOCTYPE html><p>正文</p>"

        assertEquals(listOf("正文"), texts(html))
    }

    // ------------------------------------------------------------- ⑧ 字符引用

    @Test
    fun namedAndNumericReferencesAreDecoded() {
        assertEquals(listOf("& < 中 中"), texts("<p>&amp; &lt; &#20013; &#x4E2D;</p>"))
    }

    @Test
    fun nbspIsDecodedThenCollapsed() {
        assertEquals(listOf("甲 乙"), texts("<p>甲&nbsp;&nbsp;乙</p>"))
    }

    @Test
    fun numericSpaceReferencesAreCollapsedToo() {
        assertEquals(listOf("甲 乙"), texts("<p>甲&#32;&#32;乙</p>"))
    }

    // ------------------------------------------------------------- ⑨ 属性与畸形

    @Test
    fun attributeValueWithGreaterThanStaysInsideTheTag() {
        assertEquals(listOf("甲乙丙"), texts("<p>甲<a title=\"a>b\">乙</a>丙</p>"))
    }

    @Test
    fun singleQuotedAttributeWithGreaterThanStaysInsideTheTag() {
        assertEquals(listOf("甲链接丙"), texts("<p>甲<a href='x>y' title='1>2'>链接</a>丙</p>"))
    }

    @Test
    fun loneLessThanIsLiteralText() {
        assertEquals(listOf("1 < 2 且 3<4"), texts("<p>1 < 2 且 3<4</p>"))
    }

    @Test
    fun unterminatedTagIsDiscardedButKeepsEarlierText() {
        assertEquals(listOf("甲", "乙"), texts("<p>甲</p>乙<b"))
    }

    @Test
    fun unknownTagsBehaveAsInline() {
        val html = "<p>甲<foo>乙</foo><app:bar data-x=\"1\">丙</app:bar></p>"

        assertEquals(listOf("甲乙丙"), texts(html))
    }

    @Test
    fun imgProducesNoTextAndNoBoundary() {
        // 没有 baseDir，相对路径的图无从解析 → 跳过；被跳过的图不该把一段文字劈成两段。
        assertEquals(listOf("甲乙"), texts("<p>甲<img src=\"x.png\" alt=\"图\">乙</p>"))
    }

    // ------------------------------------------------------------- ⑩ CDATA 与空白

    @Test
    fun cdataContentIsTextWithoutTagParsing() {
        assertEquals(listOf("甲<b>乙</b>丙"), texts("<p>甲<![CDATA[<b>乙</b>]]>丙</p>"))
    }

    @Test
    fun cdataWhitespaceIsFoldedLikePlainText() {
        assertEquals(listOf("甲 乙 丙"), texts("<p>甲<![CDATA[  乙  ]]>丙</p>"))
    }

    @Test
    fun consecutiveWhitespaceCollapsesIntoOneSpace() {
        assertEquals(listOf("甲 乙"), texts("<p>甲 \t\n\u000C 乙</p>"))
    }

    @Test
    fun emptyParagraphsAreDropped() {
        assertEquals(listOf("甲"), texts("<p>   </p><p>甲</p><p>&nbsp;</p>"))
    }

    @Test
    fun plainTextWithoutTagsIsOneParagraph() {
        assertEquals(listOf("甲 乙"), texts("甲\n\n  乙  "))
    }

    // ------------------------------------------------------------- ⑪ 大小写

    @Test
    fun tagNamesAreCaseInsensitive() {
        val html = "<DIV><H2>标题</H2><P>正文</P></DIV>"

        assertEquals(listOf("标题", "正文"), texts(html))
        assertEquals(listOf(2, 0), levels(html))
    }

    // ------------------------------------------- ⑫ 标题栈：隐式结束与按层弹栈

    @Test
    fun closedHeadingReturnsFollowingBodyToLevelZero() {
        val html = "<h1>第一章</h1><p>正文一</p>"

        assertEquals(listOf("第一章", "正文一"), texts(html))
        assertEquals(listOf(1, 0), levels(html))
    }

    @Test
    fun unclosedHeadingIsEndedImplicitlyByFollowingBlockTag() {
        // HTML5 允许省略 </h1>：<p> 一开始就说明标题结束，正文不能带上标题层级。
        val html = "<h1>第一章<p>正文一</p><p>正文二</p>"

        assertEquals(listOf("第一章", "正文一", "正文二"), texts(html))
        assertEquals(listOf(1, 0, 0), levels(html))
    }

    @Test
    fun brInsideHeadingKeepsHeadingLevelForFollowingLine() {
        // br 是唯一不结束标题的块级标签，所以「副标题」仍是 level 2，而不是掉成正文。
        val html = "<h2>标题<br>副标题</h2><p>正文</p>"

        assertEquals(listOf("标题", "副标题", "正文"), texts(html))
        assertEquals(listOf(2, 2, 0), levels(html))
    }

    @Test
    fun implicitCloseAlsoAppliesToNonHeadingBlockTags() {
        val html = "<h1>章<div>块</div>尾</h1>"

        assertEquals(listOf("章", "块", "尾"), texts(html))
        assertEquals(listOf(1, 0, 0), levels(html))
    }

    @Test
    fun closingOuterHeadingAlsoEndsInnerHeadingLevel() {
        // <h1> 里嵌 <h3>：遇到 </h1> 后文字按正文走，不能继续挂着 3 级标题。
        val html = "<h1>甲<h3>乙</h1><p>丙</p>"

        assertEquals(listOf("甲", "乙", "丙"), texts(html))
        assertEquals(listOf(1, 3, 0), levels(html))
    }

    @Test
    fun strayHeadingEndTagWithoutStartIsIgnored() {
        val html = "<p>甲</p></h3><p>乙</p>"

        assertEquals(listOf("甲", "乙"), texts(html))
        assertEquals(listOf(0, 0), levels(html))
    }

    @Test
    fun strayHeadingEndTagDoesNotAffectFollowingParagraphs() {
        val html = "<h2>标题</h2><p>正文</p></h2><p>后续</p>"

        assertEquals(listOf("标题", "正文", "后续"), texts(html))
        assertEquals(listOf(2, 0, 0), levels(html))
    }

    // ------------------------------------------------------------- ⑬ img 插图

    /** 一份最小的 PNG 头：只用来验证字节原样进出，不需要真的能解码。 */
    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun dataUri(bytes: ByteArray, mime: String = "image/png"): String =
        "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes)

    private fun paragraphsOf(html: String, baseDir: File? = null): List<TextParagraph> =
        HtmlTextExtractor.extract(html, baseDir)

    private fun imagesOf(html: String, baseDir: File? = null): List<TextImage> =
        HtmlTextExtractor.extract(html, baseDir).mapNotNull { it.image }

    @Test
    fun dataUriBecomesOneImageParagraphWithDecodedBytes() {
        val paragraphs = paragraphsOf("<p>前</p><img src=\"" + dataUri(pngBytes) + "\"><p>后</p>")

        assertEquals(listOf("前", "", "后"), paragraphs.map { it.text })
        val image = paragraphs[1].image!!
        assertTrue(image.isEmbedded)
        assertArrayEquals(pngBytes, image.bytes)
        assertEquals("", image.alt)
        assertNull(image.path)
        assertEquals(0, paragraphs[1].headingLevel)
    }

    @Test
    fun dataUriKeyIsStableForIdenticalSource() {
        val html = "<img src=\"" + dataUri(pngBytes) + "\">"

        val first = imagesOf(html).single().key
        assertEquals(first, imagesOf(html).single().key)
        assertTrue(first.startsWith("data:"))
        assertTrue(first.endsWith(":" + pngBytes.size))
    }

    @Test
    fun relativeSourceResolvesAgainstBaseDirectory() {
        val root = Files.createTempDirectory("pb-html-image-").toFile()
        try {
            val image = imagesOf("<img src=\"pic/01.jpg\">", root).single()

            assertEquals(File(root, "pic/01.jpg").canonicalPath, image.path)
            assertEquals("file:" + image.path, image.key)
            assertFalse(image.isEmbedded)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun relativeSourceWithoutBaseDirectoryIsSkipped() {
        assertEquals(listOf("甲乙"), texts("<p>甲<img src=\"pic/01.jpg\">乙</p>"))
        assertTrue(imagesOf("<img src=\"pic/01.jpg\">").isEmpty())
    }

    @Test
    fun parentDirectoryEscapeIsRejectedButTextSurvives() {
        val root = Files.createTempDirectory("pb-html-escape-").toFile()
        val book = File(root, "book").apply { mkdirs() }
        try {
            val paragraphs = paragraphsOf("<p>甲<img src=\"../secret.jpg\">乙</p>", book)

            assertEquals(listOf("甲乙"), paragraphs.map { it.text })
            assertTrue(paragraphs.mapNotNull { it.image }.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun absoluteSourceOutsideBaseDirectoryIsRejected() {
        val root = Files.createTempDirectory("pb-html-outside-").toFile()
        val book = File(root, "book").apply { mkdirs() }
        try {
            val src = File(root, "outside.jpg").absolutePath

            assertTrue(imagesOf("<img src=\"" + src + "\">", book).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun absoluteSourceInsideBaseDirectoryIsAccepted() {
        val root = Files.createTempDirectory("pb-html-inside-").toFile()
        try {
            val file = File(root, "pic/01.jpg")
            val image = imagesOf("<img src=\"" + file.absolutePath + "\">", root).single()

            assertEquals(file.canonicalPath, image.path)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun absoluteSourceIsAcceptedWithoutBaseDirectory() {
        val file = Files.createTempFile("pb-html-absolute-", ".jpg").toFile()
        try {
            val image = imagesOf("<img src=\"" + file.absolutePath + "\">").single()

            assertEquals(file.canonicalPath, image.path)
        } finally {
            file.delete()
        }
    }

    @Test
    fun remoteSourcesAreSkipped() {
        assertTrue(imagesOf("<img src=\"http://example.com/a.jpg\">").isEmpty())
        assertTrue(imagesOf("<img src=\"https://example.com/a.jpg\">").isEmpty())
        assertTrue(imagesOf("<img src=\"//example.com/a.jpg\">").isEmpty())
    }

    @Test
    fun imageWithoutSourceIsSkipped() {
        assertTrue(imagesOf("<img alt=\"无源\">").isEmpty())
        assertTrue(imagesOf("<img src=\"\">").isEmpty())
        assertTrue(imagesOf("<img src=\"   \">").isEmpty())
    }

    @Test
    fun imageBetweenTextParagraphsKeepsDocumentOrder() {
        val paragraphs = paragraphsOf("<p>上</p><img src=\"" + dataUri(pngBytes) + "\"><p>下</p>")

        assertEquals(listOf("上", "", "下"), paragraphs.map { it.text })
        assertEquals(listOf(false, true, false), paragraphs.map { it.image != null })
    }

    @Test
    fun imageWithoutSelfClosingSlashDoesNotSwallowFollowingText() {
        val root = Files.createTempDirectory("pb-html-unclosed-").toFile()
        try {
            val paragraphs = paragraphsOf("<p>甲<img src=x.png alt=图>乙</p>", root)

            assertEquals(listOf("甲", "", "乙"), paragraphs.map { it.text })
            assertEquals(File(root, "x.png").canonicalPath, paragraphs[1].image?.path)
            assertEquals("图", paragraphs[1].image?.alt)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun altIsPassedThroughWithEntitiesDecoded() {
        val image = imagesOf("<img src=\"" + dataUri(pngBytes) + "\" alt=\"图 &amp; 说明\">").single()

        assertEquals("图 & 说明", image.alt)
    }

    @Test
    fun uppercaseAttributeNamesAreAccepted() {
        val image = imagesOf("<IMG SRC=\"" + dataUri(pngBytes) + "\" ALT=\"大写\">").single()

        assertTrue(image.isEmbedded)
        assertEquals("大写", image.alt)
    }

    @Test
    fun sourceEntitiesAreDecodedBeforePathResolution() {
        val root = Files.createTempDirectory("pb-html-entity-").toFile()
        try {
            val image = imagesOf("<img src=\"a&amp;b.jpg\">", root).single()

            assertEquals(File(root, "a&b.jpg").canonicalPath, image.path)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun fileUriAbsolutePathIsAccepted() {
        val file = Files.createTempFile("pb-html-uri-", ".jpg").toFile()
        try {
            val image = imagesOf("<img src=\"file:" + file.absolutePath + "\">").single()

            assertEquals(file.canonicalPath, image.path)
        } finally {
            file.delete()
        }
    }

    @Test
    fun imageInsideDroppedContainerProducesNothing() {
        assertTrue(imagesOf("<svg><img src=\"" + dataUri(pngBytes) + "\"></svg>").isEmpty())
    }

    @Test
    fun nonBase64DataUriIsSkipped() {
        assertTrue(imagesOf("<img src=\"data:image/png,abc\">").isEmpty())
        assertTrue(imagesOf("<img src=\"data:image/png;base64,\">").isEmpty())
    }

    @Test
    fun imageParagraphInsideHeadingCarriesNoHeadingLevel() {
        val paragraphs = paragraphsOf("<h2>甲<img src=\"" + dataUri(pngBytes) + "\">乙</h2>")

        assertEquals(listOf("甲", "", "乙"), paragraphs.map { it.text })
        assertEquals(listOf(2, 0, 2), paragraphs.map { it.headingLevel })
    }
}