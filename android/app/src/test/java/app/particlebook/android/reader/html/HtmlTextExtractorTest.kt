package app.particlebook.android.reader.html

import app.particlebook.android.reader.text.TextParagraph
import org.junit.Assert.assertEquals
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
}