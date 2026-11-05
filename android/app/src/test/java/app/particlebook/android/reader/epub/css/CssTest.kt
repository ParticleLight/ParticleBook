package app.particlebook.android.reader.epub.css

import app.particlebook.android.reader.epub.EpubAlign
import app.particlebook.android.reader.epub.EpubStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CssTest {

    private fun assertEquals(expected: Float, actual: Float, delta: Float = 0.001f) {
        org.junit.Assert.assertEquals(expected.toDouble(), actual.toDouble(), delta.toDouble())
    }

    private fun style(css: String, element: CssElement, parent: EpubStyle = EpubStyle()): EpubStyle =
        CssParser.parse(css).styleFor(element, parent)

    @Test
    fun parsesSimpleSelectorsAndCascadeOrder() {
        val sheet = CssParser.parse(
            """
            p { color: red; font-size: 18px; }
            p.lead { font-weight: bold; text-indent: 0; }
            #intro { text-align: center; }
            p { color: #0000ff; }
            """.trimIndent(),
        )
        assertTrue(sheet.warnings.isEmpty())
        assertEquals(4, sheet.rules.size)

        val plain = sheet.styleFor(CssElement("p"))
        assertEquals(0xFF0000FF.toInt(), plain.color)
        assertEquals(18f, plain.fontSizeSp ?: 0f, 0.001f)

        val lead = sheet.styleFor(CssElement("p", classes = setOf("lead")))
        assertTrue(lead.bold)
        assertEquals(0f, lead.indentEm, 0.001f)

        val intro = sheet.styleFor(CssElement("p", id = "intro", classes = setOf("lead")))
        assertEquals(EpubAlign.CENTER, intro.align)
        assertTrue(intro.bold)
    }

    @Test
    fun importantBeatsSpecificityButInlineNormalLoses() {
        val important = style(
            "p { color: blue !important; } #x { color: red; }",
            CssElement("p", id = "x", inlineStyle = CssParser.parseInlineStyle("color: green")),
        )
        assertEquals(0xFF0000FF.toInt(), important.color)

        val inlineWins = style(
            "#x { color: red; }",
            CssElement("p", id = "x", inlineStyle = CssParser.parseInlineStyle("color: green")),
        )
        assertEquals(0xFF008000.toInt(), inlineWins.color)
    }

    @Test
    fun unknownPropertiesAndUnsupportedAtRulesAreRecordedNotFatal() {
        val sheet = CssParser.parse(
            """
            p { widows: 2; color: red; }
            @media screen { p { color: blue; } }
            p { font-variant: small-caps; }
            """.trimIndent(),
        )
        assertTrue("widows" in sheet.unknownProperties)
        assertTrue("font-variant" in sheet.unknownProperties)
        assertTrue(sheet.warnings.any { it.contains("@media") || it.contains("at-rule") })
        assertEquals(0xFFFF0000.toInt(), sheet.styleFor(CssElement("p")).color)
    }

    @Test
    fun inheritedPropertiesPropagateAndBoxPropertiesDoNot() {
        val body = style(
            "body { font-size: 20px; color: #112233; text-align: justify; }",
            CssElement("body"),
        )
        assertEquals(20f, body.fontSizeSp ?: 0f, 0.001f)
        assertEquals(EpubAlign.JUSTIFY, body.align)

        val heading = style(
            "body { font-size: 20px; color: #112233; text-align: justify; } h1 { font-size: 2em; margin-top: 1em; }",
            CssElement("h1"),
            body,
        )
        assertEquals(40f, heading.fontSizeSp ?: 0f, 0.001f)
        assertEquals(40f, heading.marginTopDp, 0.001f)
        assertEquals(0xFF112233.toInt(), heading.color)
        assertEquals(EpubAlign.JUSTIFY, heading.align)

        val paragraph = style(
            "body { font-size: 20px; } p { text-indent: 2em; margin-bottom: 1em; display: none; }",
            CssElement("p"),
            body,
        )
        assertEquals(2f, paragraph.indentEm, 0.001f)
        assertEquals(20f, paragraph.marginBottomDp, 0.001f)
        assertTrue(paragraph.hidden)
        // margin/display 不继承：h1 不应拿到 p 的 hidden。
        val headingFromParagraphParent = style(
            "p { display: none; margin-top: 3em; }",
            CssElement("h1"),
            paragraph,
        )
        assertFalse(headingFromParagraphParent.hidden)
        assertEquals(0f, headingFromParagraphParent.marginTopDp, 0.001f)
    }

    @Test
    fun unitsAndColors() {
        val pt = style("p { font-size: 12pt; }", CssElement("p"))
        assertEquals(16f, pt.fontSizeSp ?: 0f, 0.001f)

        val rem = style("p { font-size: 1.5rem; }", CssElement("p"))
        assertEquals(24f, rem.fontSizeSp ?: 0f, 0.001f)

        val percent = style(
            "body { font-size: 20px; } p { font-size: 150%; }",
            CssElement("p"),
            EpubStyle(fontSizeSp = 20f),
        )
        assertEquals(30f, percent.fontSizeSp ?: 0f, 0.001f)

        val shortHex = style("p { color: #abc; }", CssElement("p"))
        assertEquals(0xFFAABBCC.toInt(), shortHex.color)

        val rgba = style("p { color: rgba(1, 2, 3, 0.5); }", CssElement("p"))
        assertEquals(0x7F010203, rgba.color)

        val lineHeight = style("p { line-height: 1.5; }", CssElement("p"))
        assertEquals(1.5f, lineHeight.lineHeightMultiplier ?: 0f, 0.001f)

        val lineHeightPx = style("body { font-size: 20px; } p { line-height: 30px; }", CssElement("p"), EpubStyle(fontSizeSp = 20f))
        assertEquals(1.5f, lineHeightPx.lineHeightMultiplier ?: 0f, 0.001f)
    }

    @Test
    fun pageBreakAndDisplayNone() {
        val sheet = CssParser.parse(
            """
            .hidden { display: none; }
            .pb { page-break-before: always; }
            p { page-break-before: avoid; }
            """.trimIndent(),
        )
        assertTrue(sheet.styleFor(CssElement("p", classes = setOf("hidden"))).hidden)
        assertTrue(sheet.styleFor(CssElement("div", classes = setOf("pb"))).pageBreakBefore)
        assertFalse(sheet.styleFor(CssElement("p")).pageBreakBefore)
    }

    @Test
    fun invalidCssDoesNotThrow() {
        val sheet = CssParser.parse("p { color: red; /* open comment")
        assertTrue(sheet.rules.isEmpty())
        assertTrue(sheet.warnings.isNotEmpty())
        val empty = CssParser.parse("")
        assertTrue(empty.rules.isEmpty())
        assertTrue(empty.unknownProperties.isEmpty())
        assertNull(CssParser.parse("").styleFor(CssElement("p")).color)

        val emptySelector = CssParser.parse(". { color: red; }")
        assertTrue(emptySelector.rules.isEmpty())
        assertTrue(emptySelector.warnings.isNotEmpty())
    }
}
