package app.particlebook.android.reader.fb2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class Fb2ParserTest {

    // ------------------------------------------------------------ ① 最小书

    @Test
    fun minimalBookGivesTitleAndOneParagraph() {
        val xml = book(
            titleInfo("小书") +
                "<body><section><title><p>第一章</p></title><p>正文。</p></section></body>"
        )

        val document = Fb2Parser.parse(xml)

        assertEquals("小书", document.title)
        assertNull(document.author)
        assertEquals(
            listOf(Fb2Paragraph("第一章", 1), Fb2Paragraph("正文。", 0)),
            document.paragraphs
        )
    }

    // ------------------------------------------------------------- ② 作者

    @Test
    fun authorWithOnlyLastName() {
        val xml = book(titleInfo("书", authorXml(last = "Tolstoy")) + "<body><p>正文</p></body>")

        assertEquals("Tolstoy", Fb2Parser.parse(xml).author)
    }

    @Test
    fun authorWithThreePartsJoinedBySingleSpace() {
        val xml = book(
            titleInfo("书", authorXml(first = "Lev", middle = "Nikolaevich", last = "Tolstoy")) +
                "<body><p>正文</p></body>"
        )

        assertEquals("Lev Nikolaevich Tolstoy", Fb2Parser.parse(xml).author)
    }

    @Test
    fun authorOnlyFromTitleInfoNotDocumentInfo() {
        val xml = book(
            """
            <description>
              <title-info>
                <book-title>书名</book-title>
                <author><first-name>Anna</first-name></author>
              </title-info>
              <document-info>
                <author><nickname>someone-else</nickname></author>
              </document-info>
            </description>
            <body><p>正文</p></body>
            """.trimIndent()
        )

        assertEquals("Anna", Fb2Parser.parse(xml).author)
    }

    // --------------------------------------------------------- ④⑤ 命名空间

    @Test
    fun documentWithFb2NamespaceParses() {
        val xml = book(
            titleInfo("带命名空间") + "<body><p>命名空间里的正文</p></body>",
            namespace = true
        )

        val document = Fb2Parser.parse(xml)

        assertEquals("带命名空间", document.title)
        assertEquals(listOf(Fb2Paragraph("命名空间里的正文")), document.paragraphs)
    }

    @Test
    fun documentWithoutNamespaceParses() {
        val xml = book(
            titleInfo("无命名空间") + "<body><p>无命名空间正文</p></body>",
            namespace = false
        )

        val document = Fb2Parser.parse(xml)

        assertEquals("无命名空间", document.title)
        assertEquals(listOf(Fb2Paragraph("无命名空间正文")), document.paragraphs)
    }

    @Test
    fun prefixedElementsAreMatchedByLocalName() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <fb2:FictionBook xmlns:fb2="http://www.gribuser.ru/xml/fictionbook/2.0">
              <fb2:description>
                <fb2:title-info><fb2:book-title>前缀书</fb2:book-title></fb2:title-info>
              </fb2:description>
              <fb2:body>
                <fb2:section>
                  <fb2:title><fb2:p>前缀章</fb2:p></fb2:title>
                  <fb2:p>前缀正文</fb2:p>
                </fb2:section>
              </fb2:body>
            </fb2:FictionBook>
        """.trimIndent()

        val document = Fb2Parser.parse(xml)

        assertEquals("前缀书", document.title)
        assertEquals(
            listOf(Fb2Paragraph("前缀章", 1), Fb2Paragraph("前缀正文", 0)),
            document.paragraphs
        )
    }

    // ------------------------------------------------------- ⑥⑦ 章节层级

    @Test
    fun threeNestedLevelsGetHeadingLevelsOneTwoThree() {
        val xml = book(
            titleInfo("书") +
                """
                <body>
                  <section>
                    <title><p>一级</p></title>
                    <p>一级正文</p>
                    <section>
                      <title><p>二级</p></title>
                      <section>
                        <title><p>三级</p></title>
                        <p>三级正文</p>
                      </section>
                    </section>
                  </section>
                </body>
                """.trimIndent()
        )

        val document = Fb2Parser.parse(xml)

        assertEquals(
            listOf(
                Fb2Paragraph("一级", 1),
                Fb2Paragraph("一级正文", 0),
                Fb2Paragraph("二级", 2),
                Fb2Paragraph("三级", 3),
                Fb2Paragraph("三级正文", 0)
            ),
            document.paragraphs
        )
    }

    @Test
    fun sectionsDeeperThanThreeCapAtLevelThree() {
        val xml = book(titleInfo("书") + "<body>" + nestedSections(5) + "</body>")

        val document = Fb2Parser.parse(xml)

        assertEquals(listOf(1, 2, 3, 3, 3), document.paragraphs.map { it.headingLevel })
        assertEquals(listOf("L1", "L2", "L3", "L4", "L5"), document.paragraphs.map { it.text })
    }

    // ------------------------------------------------------- ⑧ title 连接

    @Test
    fun titleWithTwoParagraphsJoinedByMiddleDot() {
        val xml = book(
            titleInfo("书") +
                "<body><section><title><p>上部</p><p>第一章</p></title><p>正文</p></section></body>"
        )

        val document = Fb2Parser.parse(xml)

        assertEquals(Fb2Paragraph("上部 \u00B7 第一章", 1), document.paragraphs[0])
        assertEquals("上部 \u00B7 第一章", document.paragraphs[0].text)
    }

    // --------------------------------------------------------- ⑨ 空白折叠

    @Test
    fun paragraphWhitespaceIsCollapsed() {
        val xml = book(
            titleInfo("书") +
                "<body><section><title><p>标题</p></title>" +
                "<p>\n   第一段   文本\n\n 第二句 \t 结束 \n</p></section></body>"
        )

        val document = Fb2Parser.parse(xml)

        assertEquals("第一段 文本 第二句 结束", document.paragraphs[1].text)
    }

    // ----------------------------------------------------------- ⑩ epigraph

    @Test
    fun epigraphParagraphsBecomeBodyParagraphs() {
        val xml = book(
            titleInfo("书") +
                """
                <body>
                  <section>
                    <title><p>章</p></title>
                    <epigraph><p>题词一</p><p>题词二</p></epigraph>
                    <p>正文</p>
                  </section>
                </body>
                """.trimIndent()
        )

        val document = Fb2Parser.parse(xml)

        assertEquals(
            listOf(
                Fb2Paragraph("章", 1),
                Fb2Paragraph("题词一"),
                Fb2Paragraph("题词二"),
                Fb2Paragraph("正文")
            ),
            document.paragraphs
        )
    }

    // ------------------------------------------------------ ⑪ poem/stanza/v

    @Test
    fun poemVersesBecomeOneParagraphEach() {
        val xml = book(
            titleInfo("书") +
                """
                <body>
                  <section>
                    <title><p>诗</p></title>
                    <poem>
                      <stanza><v>第一行</v><v>第二行</v></stanza>
                      <stanza><v>第三行</v></stanza>
                    </poem>
                  </section>
                </body>
                """.trimIndent()
        )

        val document = Fb2Parser.parse(xml)

        assertEquals(
            listOf(
                Fb2Paragraph("诗", 1),
                Fb2Paragraph("第一行"),
                Fb2Paragraph("第二行"),
                Fb2Paragraph("第三行")
            ),
            document.paragraphs
        )
    }

    // ----------------------------------------------------------- ⑫ subtitle

    @Test
    fun subtitleBecomesBodyParagraph() {
        val xml = book(
            titleInfo("书") +
                "<body><section><title><p>章</p></title>" +
                "<subtitle>副标题文字</subtitle><p>正文</p></section></body>"
        )

        val document = Fb2Parser.parse(xml)

        assertEquals(
            listOf(Fb2Paragraph("章", 1), Fb2Paragraph("副标题文字"), Fb2Paragraph("正文")),
            document.paragraphs
        )
    }

    // ------------------------------------------------------ ⑬ 带 name 的 body

    @Test
    fun bodyWithNameAttributeIsSkipped() {
        val xml = book(
            titleInfo("书") +
                "<body name=\"notes\"><section><title><p>注释</p></title><p>脚注正文</p></section></body>" +
                "<body><p>正文甲</p></body>"
        )

        val document = Fb2Parser.parse(xml)

        assertEquals(listOf(Fb2Paragraph("正文甲")), document.paragraphs)
    }

    @Test
    fun onlyNamedBodiesYieldNoParagraphs() {
        val xml = book(
            titleInfo("书") + "<body name=\"footnotes\"><section><p>脚注</p></section></body>"
        )

        assertTrue(Fb2Parser.parse(xml).paragraphs.isEmpty())
    }

    // -------------------------------------------------------------- ⑭ binary

    @Test
    fun binaryProducesNoParagraphs() {
        val xml = book(
            titleInfo("书") +
                "<body><p>正文</p></body>" +
                "<binary id=\"cover.png\" content-type=\"image/png\">iVBORw0KGgo=</binary>"
        )

        val document = Fb2Parser.parse(xml)

        assertEquals(listOf(Fb2Paragraph("正文")), document.paragraphs)
        assertTrue(document.paragraphs.none { it.text.contains("iVBOR") })
    }

    // ---------------------------------------------------------- ⑮ 空段落

    @Test
    fun blankParagraphsAreDropped() {
        val xml = book(
            titleInfo("书") +
                "<body><section><title><p>   </p></title>" +
                "<p>   </p><p></p><empty-line/><p>有内容</p></section></body>"
        )

        val document = Fb2Parser.parse(xml)

        assertEquals(listOf(Fb2Paragraph("有内容")), document.paragraphs)
    }

    // ---------------------------------------------------------- ⑯ XML 实体

    @Test
    fun xmlEntitiesAndCharacterReferencesAreDecoded() {
        val xml = book(
            titleInfo("Tom &amp; Jerry") +
                "<body><p>Tom &amp; Jerry &#x4E2D;文 &lt;tag&gt;</p></body>"
        )

        val document = Fb2Parser.parse(xml)

        assertEquals("Tom & Jerry", document.title)
        assertEquals(listOf(Fb2Paragraph("Tom & Jerry 中文 <tag>")), document.paragraphs)
    }

    // ------------------------------------------------------- ⑰ 非法 XML

    @Test
    fun invalidXmlThrowsFb2ParseException() {
        val xml = book(titleInfo("书") + "<body><p>未闭合的段落")

        val error = assertThrows(Fb2ParseException::class.java) { Fb2Parser.parse(xml) }

        assertTrue(error.message.orEmpty().startsWith("FB2_PARSE: "))
    }

    // ------------------------------------------------- ⑱ 根元素不是 FictionBook

    @Test
    fun nonFictionBookRootThrows() {
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?><book><body><p>正文</p></body></book>"

        val error = assertThrows(Fb2ParseException::class.java) { Fb2Parser.parse(xml) }

        assertTrue(error.message.orEmpty().startsWith("FB2_PARSE: "))
        assertTrue(error.message.orEmpty().contains("FictionBook"))
    }

    // -------------------------------------------------------------- ⑲ 空串

    @Test
    fun blankInputThrows() {
        assertThrows(Fb2ParseException::class.java) { Fb2Parser.parse("") }
        assertThrows(Fb2ParseException::class.java) { Fb2Parser.parse("   \n\t  \n") }
    }

    // ------------------------------------------------- ⑳ 裸 body 只有 p

    @Test
    fun bodyWithoutSectionsKeepsOnlyPlainParagraphs() {
        val xml = book(titleInfo("书") + "<body><p>一</p><p>二</p></body>")

        val document = Fb2Parser.parse(xml)

        assertEquals(
            listOf(Fb2Paragraph("一", 0), Fb2Paragraph("二", 0)),
            document.paragraphs
        )
    }

    // -------------------------------------------------- 补充：缺失元数据

    @Test
    fun missingTitleAndAuthorFallBack() {
        val xml = book("<body><p>只有正文</p></body>")

        val document = Fb2Parser.parse(xml)

        assertEquals("", document.title)
        assertNull(document.author)
        assertEquals(listOf(Fb2Paragraph("只有正文")), document.paragraphs)
    }

    // -------------------------------------------------- 补充：annotation

    @Test
    fun annotationContentIsIgnored() {
        val xml = book(
            """
            <description>
              <title-info>
                <book-title>书名</book-title>
                <annotation><p>简介不计入正文</p></annotation>
              </title-info>
            </description>
            <body>
              <section>
                <title><p>章</p></title>
                <annotation><p>章前简介</p></annotation>
                <p>正文</p>
              </section>
            </body>
            """.trimIndent()
        )

        val document = Fb2Parser.parse(xml)

        assertEquals(listOf(Fb2Paragraph("章", 1), Fb2Paragraph("正文")), document.paragraphs)
    }

    // -------------------------------------------------- 补充：文档顺序

    @Test
    fun titlePrecedesOwnParagraphsAndFollowingSiblingStaysLast() {
        val xml = book(
            titleInfo("书") +
                """
                <body>
                  <section>
                    <title><p>甲</p></title>
                    <p>甲一</p>
                    <section><title><p>乙</p></title><p>乙一</p></section>
                    <p>甲二</p>
                  </section>
                </body>
                """.trimIndent()
        )

        val document = Fb2Parser.parse(xml)

        assertEquals(
            listOf(
                Fb2Paragraph("甲", 1),
                Fb2Paragraph("甲一", 0),
                Fb2Paragraph("乙", 2),
                Fb2Paragraph("乙一", 0),
                Fb2Paragraph("甲二", 0)
            ),
            document.paragraphs
        )
    }

    // -------------------------------------------------- 补充：body 级 title

    @Test
    fun bodyLevelTitleBecomesLevelOneHeading() {
        val xml = book(
            titleInfo("书") +
                """
                <body>
                  <title><p>卷一</p><p>起风</p></title>
                  <section><title><p>第一章</p></title><p>正文</p></section>
                </body>
                """.trimIndent()
        )

        val document = Fb2Parser.parse(xml)

        assertEquals(
            listOf(
                Fb2Paragraph("卷一 \u00B7 起风", 1),
                Fb2Paragraph("第一章", 1),
                Fb2Paragraph("正文", 0)
            ),
            document.paragraphs
        )
    }

    @Test
    fun bodyLevelTitleIsNotRepeatedAsBodyText() {
        val xml = book(titleInfo("书") + "<body><title><p>卷</p></title><p>正文</p></body>")

        val document = Fb2Parser.parse(xml)

        assertEquals(listOf(Fb2Paragraph("卷", 1), Fb2Paragraph("正文", 0)), document.paragraphs)
    }

    // -------------------------------------------------- 补充：DOCTYPE 硬禁

    @Test
    fun doctypeWithExternalEntityIsRejected() {
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<!DOCTYPE FictionBook [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>" +
            "<FictionBook><body><p>&xxe;</p></body></FictionBook>"

        assertThrows(Fb2ParseException::class.java) { Fb2Parser.parse(xml) }
    }

    // ------------------------------------------- 补充：容器直接挂在非 p 元素下的文本

    @Test
    fun tableCellTextDirectlyUnderTableBecomesParagraph() {
        val xml = book(
            titleInfo("书") +
                """
                <body>
                  <section>
                    <p>甲</p>
                    <table><tr><td>2024</td></tr></table>
                  </section>
                </body>
                """.trimIndent()
        )

        val document = Fb2Parser.parse(xml)

        assertEquals(listOf(Fb2Paragraph("甲"), Fb2Paragraph("2024")), document.paragraphs)
        assertTrue(document.paragraphs.any { it.text.contains("2024") })
    }

    @Test
    fun citeWithoutParagraphChildrenBecomesParagraph() {
        val xml = book(
            titleInfo("书") +
                "<body><section><p>甲</p><cite>引文</cite><p>乙</p></section></body>"
        )

        val document = Fb2Parser.parse(xml)

        assertEquals(
            listOf(Fb2Paragraph("甲"), Fb2Paragraph("引文"), Fb2Paragraph("乙")),
            document.paragraphs
        )
        assertTrue(document.paragraphs.any { it.text.contains("引文") })
    }

    @Test
    fun paragraphTextIsNotRepeatedByContainerRecursion() {
        val xml = book(
            titleInfo("书") +
                "<body><section><p>唯一</p><p>第二段</p>" +
                "<table><tr><td>第三段</td></tr></table></section></body>"
        )

        val document = Fb2Parser.parse(xml)

        assertEquals(1, document.paragraphs.count { it.text.contains("唯一") })
        assertEquals(3, document.paragraphs.size)
    }

    // -------------------------------------------------- 补充：嵌套深度上限

    @Test
    fun nestingDeeperThanLimitParsesWithoutOverflow() {
        val xml = book(titleInfo("书") + "<body>" + nestedBareSections(80, "<p>深</p>") + "</body>")

        val document = Fb2Parser.parse(xml)

        assertTrue(document.paragraphs.isNotEmpty())
        assertTrue(document.paragraphs.any { it.text.contains("深") })
    }

    @Test
    fun twentyNestedSectionsKeepHeadingCapAndInnerBody() {
        val xml = book(
            titleInfo("书") + "<body>" + nestedSectionsWithInner(20, "<p>最内层正文</p>") + "</body>"
        )

        val document = Fb2Parser.parse(xml)

        assertEquals(21, document.paragraphs.size)
        assertEquals(listOf(1, 2, 3), document.paragraphs.take(3).map { it.headingLevel })
        assertTrue(document.paragraphs.drop(3).take(17).all { it.headingLevel == 3 })
        assertEquals(Fb2Paragraph("最内层正文", 0), document.paragraphs.last())
    }

    @Test
    fun containerWithMixedTextAndParagraphKeepsBothInOrder() {
        // 直属文字与子段落混排：按文档顺序各出一段，不能因为容器有元素子节点就把直属文字丢掉。
        val xml = book(
            titleInfo("书") + "<body><section>" +
                "<epigraph>题词<p>引文</p>尾注</epigraph>" +
                "<p>正文</p>" +
                "</section></body>"
        )

        val document = Fb2Parser.parse(xml)

        assertEquals(listOf("题词", "引文", "尾注", "正文"), document.paragraphs.map { it.text })
    }

    // ------------------------------------------------------- ㉑ 内嵌图片

    @Test
    fun binaryReferencedByInlineImageBecomesImageParagraph() {
        val bytes = sampleBytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46)
        val xml = imageBook(
            body = "<section><title><p>图</p></title>" +
                "<p>上</p><image l:href=\"#cover.jpg\"/><p>下</p></section>",
            binaries = binary("cover.jpg", base64(bytes)),
        )

        val paragraphs = Fb2Parser.parse(xml).paragraphs

        // 图片必须落在它在文档里出现的位置：文字 / 图片 / 文字。
        assertEquals(listOf("图", "上", "", "下"), paragraphs.map { it.text })
        assertEquals(listOf(1, 0, 0, 0), paragraphs.map { it.headingLevel })
        val image = requireNotNull(paragraphs[2].image) { "图片段没有 image" }
        assertEquals("cover.jpg", image.key)
        assertEquals("cover.jpg", image.alt)
        assertTrue(image.isEmbedded)
        assertArrayEquals(bytes, image.bytes)
    }

    @Test
    fun imageReferencedTwiceKeepsTwoParagraphsWithSameBytes() {
        val bytes = sampleBytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val xml = imageBook(
            body = "<section><p>甲</p><image l:href=\"#pic.png\"/><p>乙</p>" +
                "<image l:href=\"#pic.png\"/></section>",
            binaries = binary("pic.png", base64(bytes), contentType = "image/png"),
        )

        val paragraphs = Fb2Parser.parse(xml).paragraphs

        assertEquals(listOf("甲", "", "乙", ""), paragraphs.map { it.text })
        val first = requireNotNull(paragraphs[1].image)
        val second = requireNotNull(paragraphs[3].image)
        // 同一张图两处引用：两个段落、缓存键一致、字节一致。
        assertEquals("pic.png", first.key)
        assertEquals(first.key, second.key)
        assertArrayEquals(bytes, first.bytes)
        assertArrayEquals(bytes, second.bytes)
    }

    @Test
    fun imageWithoutHrefProducesNoParagraph() {
        val xml = imageBook(
            body = "<section><p>上</p><image/><p>下</p></section>",
            binaries = binary("cover.jpg", base64(sampleBytes(1, 2, 3))),
        )

        val paragraphs = Fb2Parser.parse(xml).paragraphs

        assertEquals(listOf("上", "下"), paragraphs.map { it.text })
        assertTrue(paragraphs.all { it.image == null })
    }

    @Test
    fun imagePointingToMissingBinaryProducesNoParagraph() {
        val xml = imageBook(
            body = "<section><p>上</p><image l:href=\"#missing.jpg\"/><p>下</p></section>",
            binaries = binary("cover.jpg", base64(sampleBytes(4, 5, 6))),
        )

        val paragraphs = Fb2Parser.parse(xml).paragraphs

        assertEquals(listOf("上", "下"), paragraphs.map { it.text })
        assertTrue(paragraphs.all { it.image == null })
    }

    @Test
    fun brokenBase64BinaryIsSkippedWithoutThrowing() {
        val xml = imageBook(
            body = "<section><p>上</p><image l:href=\"#broken.png\"/><p>下</p></section>",
            // 5 个字符凑不成 4 字符的 base64 单元，MIME 解码器抛 IllegalArgumentException。
            binaries = binary("broken.png", "AAAAA", contentType = "image/png"),
        )

        val paragraphs = Fb2Parser.parse(xml).paragraphs

        // 一张坏图不该毁掉整本书：跳过图片，其余段落照旧，也不抛 Fb2ParseException。
        assertEquals(listOf("上", "下"), paragraphs.map { it.text })
        assertTrue(paragraphs.all { it.image == null })
    }

    @Test
    fun binaryLargerThanEightMegabytesIsSkipped() {
        // 上限是 MAX_BYTES * 4 / 3 + 64 = 11_184_874 字符；全 'A' 是合法 base64（4 的倍数），
        // 所以「跳过」只能是被尺寸规则挡下的，不是解码失败。
        val oversized = "A".repeat(11_185_000)
        val small = sampleBytes(7, 7, 7, 7)
        val xml = imageBook(
            body = "<section><p>上</p><image l:href=\"#huge.jpg\"/>" +
                "<image l:href=\"#small.jpg\"/></section>",
            binaries = binary("huge.jpg", oversized) + binary("small.jpg", base64(small)),
        )

        val paragraphs = Fb2Parser.parse(xml).paragraphs

        // 超限的那张被丢掉，同一本书里的正常图片不受影响。
        assertEquals(listOf("上", ""), paragraphs.map { it.text })
        val image = requireNotNull(paragraphs[1].image)
        assertEquals("small.jpg", image.key)
        assertArrayEquals(small, image.bytes)
    }

    @Test
    fun imageInsideTitleProducesNoParagraph() {
        val xml = imageBook(
            body = "<section><title><p>章</p><image l:href=\"#cover.jpg\"/></title><p>正文</p></section>",
            binaries = binary("cover.jpg", base64(sampleBytes(8, 8, 8))),
        )

        val paragraphs = Fb2Parser.parse(xml).paragraphs

        // title 里的 image 是封面 / 卷头画，不进正文。
        assertEquals(listOf(Fb2Paragraph("章", 1), Fb2Paragraph("正文")), paragraphs)
        assertTrue(paragraphs.none { it.image != null })
    }

    @Test
    fun imageDirectlyUnderBodyAndSectionKeepsDocumentOrder() {
        val xml = imageBook(
            body = "<image l:href=\"#cover.jpg\"/>" +
                "<section><p>一</p><image l:href=\"#cover.jpg\"/><p>二</p></section>",
            binaries = binary("cover.jpg", base64(sampleBytes(3, 1, 4))),
        )

        val paragraphs = Fb2Parser.parse(xml).paragraphs

        assertEquals(listOf("", "一", "", "二"), paragraphs.map { it.text })
        assertEquals(listOf(true, false, true, false), paragraphs.map { it.image != null })
    }

    @Test
    fun binariesWithoutInlineReferencesDoNotChangeTextParagraphs() {
        val body = "<section><title><p>章</p></title><p>一</p><p>二</p></section>"

        val withBinaries = Fb2Parser.parse(imageBook(body, binary("unused.png", base64(sampleBytes(5, 5)))))
        val withoutBinaries = Fb2Parser.parse(imageBook(body))

        assertEquals(listOf("章", "一", "二"), withoutBinaries.paragraphs.map { it.text })
        assertEquals(withoutBinaries.paragraphs, withBinaries.paragraphs)
    }

    @Test
    fun prefixedBinaryIdAndHrefAreMatchedByLocalName() {
        val bytes = sampleBytes(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <fb2:FictionBook xmlns:fb2="http://www.gribuser.ru/xml/fictionbook/2.0"
                             xmlns:l="http://www.w3.org/1999/xlink">
              <fb2:description>
                <fb2:title-info><fb2:book-title>前缀图</fb2:book-title></fb2:title-info>
              </fb2:description>
              <fb2:body>
                <fb2:section>
                  <fb2:p>上</fb2:p>
                  <fb2:image l:href="#cover.jpg"/>
                </fb2:section>
              </fb2:body>
              <fb2:binary fb2:id="cover.jpg" fb2:content-type="image/jpeg">${base64(bytes)}</fb2:binary>
            </fb2:FictionBook>
        """.trimIndent()

        val document = Fb2Parser.parse(xml)

        assertEquals("前缀图", document.title)
        assertEquals(listOf("上", ""), document.paragraphs.map { it.text })
        val image = requireNotNull(document.paragraphs[1].image)
        assertEquals("cover.jpg", image.key)
        assertArrayEquals(bytes, image.bytes)
    }

    @Test
    fun wrappedBase64WithNewlinesAndIndentationIsDecoded() {
        val bytes = ByteArray(180) { (it * 7).toByte() }
        val wrapped = base64(bytes).chunked(76).joinToString("\n        ")
        val xml = imageBook(
            body = "<section><p>上</p><image l:href=\"#wrapped.jpg\"/></section>",
            binaries = "<binary id=\"wrapped.jpg\" content-type=\"image/jpeg\">\n          " + wrapped +
                "\n        </binary>",
        )

        val paragraphs = Fb2Parser.parse(xml).paragraphs

        val image = requireNotNull(paragraphs[1].image)
        assertArrayEquals(bytes, image.bytes)
    }

    // ---------------------------------------------------------------- 帮助器

    private fun book(inner: String, namespace: Boolean = true): String {
        val ns = if (namespace) {
            " xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\""
        } else {
            ""
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<FictionBook" + ns + ">" + inner + "</FictionBook>"
    }

    private fun titleInfo(bookTitle: String?, author: String = ""): String {
        val title = bookTitle?.let { "<book-title>" + it + "</book-title>" } ?: ""
        return "<description><title-info>" + title + author + "</title-info></description>"
    }

    private fun authorXml(first: String = "", middle: String = "", last: String = ""): String {
        val builder = StringBuilder("<author>")
        if (first.isNotEmpty()) builder.append("<first-name>").append(first).append("</first-name>")
        if (middle.isNotEmpty()) builder.append("<middle-name>").append(middle).append("</middle-name>")
        if (last.isNotEmpty()) builder.append("<last-name>").append(last).append("</last-name>")
        return builder.append("</author>").toString()
    }

    /** 生成 depth 层互相嵌套的 section，每层只带自己的 title。 */
    private fun nestedSections(depth: Int): String {
        val open = StringBuilder()
        val close = StringBuilder()
        for (index in 1..depth) {
            open.append("<section><title><p>L").append(index).append("</p></title>")
            close.insert(0, "</section>")
        }
        return open.toString() + close.toString()
    }

    /** 生成 depth 层互相嵌套、不带 title 的 section，最内层追加 [innerXml]。 */
    private fun nestedBareSections(depth: Int, innerXml: String): String {
        val builder = StringBuilder()
        repeat(depth) { builder.append("<section>") }
        builder.append(innerXml)
        repeat(depth) { builder.append("</section>") }
        return builder.toString()
    }

    /** 生成 depth 层互相嵌套、每层只带自己 title 的 section，最内层追加 [innerXml]。 */
    private fun nestedSectionsWithInner(depth: Int, innerXml: String): String {
        val open = StringBuilder()
        val close = StringBuilder()
        for (index in 1..depth) {
            open.append("<section><title><p>L").append(index).append("</p></title>")
            close.insert(0, "</section>")
        }
        return open.toString() + innerXml + close.toString()
    }

    /** 带 FB2 默认命名空间 + XLink（`l:href` 要用）的书；`binaries` 拼在 `</body>` 之后。 */
    private fun imageBook(body: String, binaries: String = ""): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\"" +
            " xmlns:l=\"http://www.w3.org/1999/xlink\">" +
            titleInfo("图册") +
            "<body>" + body + "</body>" +
            binaries +
            "</FictionBook>"

    private fun binary(id: String, payload: String, contentType: String = "image/jpeg"): String =
        "<binary id=\"" + id + "\" content-type=\"" + contentType + "\">" + payload + "</binary>"

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun sampleBytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
