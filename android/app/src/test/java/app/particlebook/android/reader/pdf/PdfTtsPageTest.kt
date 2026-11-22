package app.particlebook.android.reader.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** task-13：PDF 页文本 → 句子列表 → 字符索引区间。 */
class PdfTtsPageTest {

    @Test
    fun splitsPageTextIntoSentencesWithCharacterIndexRanges() {
        val text = "第一句。第二句！"
        val page = PdfTtsPage.from(
            PdfTtsFixtures.page(text = text, chars = PdfTtsFixtures.chars(text), pageIndex = 7),
        )

        assertEquals(7, page.page)
        assertEquals(listOf("第一句。", "第二句！"), page.sentences.map { it.text })
        assertEquals(0..3, page.charRange(0))
        assertEquals(4..7, page.charRange(1))
        // 越界下标不抛异常，只表示"这里没有可高亮的区间"。
        assertNull(page.charRange(2))
    }

    @Test
    fun generatedWhitespaceInPageTextKeepsCharacterAlignment() {
        // PDFium 的整页文本会补出逐字符层里不存在的空白；这类差异必须被跳过而不是让整页失去高亮。
        val page = PdfTtsPage.from(
            PdfTtsFixtures.page(text = "a  b", chars = PdfTtsFixtures.chars("ab")),
        )

        assertEquals(listOf("a  b"), page.sentences.map { it.text })
        assertEquals(0..1, page.charRange(0))
    }

    @Test
    fun generatedCarriageReturnBecomesSentenceBoundaryWithoutShiftingOffsets() {
        // 整页文本用 '\r' 表示生成的行尾；切分器只认 '\n'，所以等长替换后再切句。
        val page = PdfTtsPage.from(
            PdfTtsFixtures.page(text = "a\rb", chars = PdfTtsFixtures.chars("ab")),
        )

        assertEquals("a\nb", page.text)
        assertEquals(listOf("a", "b"), page.sentences.map { it.text })
        assertEquals(0..0, page.charRange(0))
        assertEquals(1..1, page.charRange(1))
    }

    @Test
    fun nonWhitespaceTextDifferenceDisablesHighlightForWholePage() {
        // 两套文本不是同一字符序列（这里多出一个 'x'）时宁可不高亮，也不画错位置的矩形。
        val page = PdfTtsPage.from(
            PdfTtsFixtures.page(text = "axb", chars = PdfTtsFixtures.chars("ab")),
        )

        assertTrue(page.sentences.isNotEmpty())
        assertNull(page.charRange(0))
    }

    @Test
    fun skippedCharactersLeaveIndexGapsInsideRange() {
        // PdfTextSource 跳过 '\u0000' 后索引可能是 0,1,3；区间要覆盖空洞，由选区按 chars 过滤。
        val page = PdfTtsPage.from(
            PdfTtsFixtures.page(
                text = "abc",
                chars = PdfTtsFixtures.chars("abc", indices = listOf(0, 1, 3)),
            ),
        )

        assertEquals(0..3, page.charRange(0))
    }

    @Test
    fun emptyTextLayerHasNoSentencesOrRanges() {
        val page = PdfTtsPage.from(PdfTtsFixtures.page(text = "", chars = emptyList()))

        assertTrue(page.sentences.isEmpty())
        assertNull(page.charRange(0))
    }
    @Test
    fun whiteSpaceOnlyDifferenceInEitherDirectionKeepsAlignment() {
        // 真机两套文本的空白并不一致：整页文本可能整段丢空格（"Line1"），逐字符文本却带空格
        // （"Line 1"），反之亦然。只比非空白字符，两边都能对齐，且区间仍落在正确字符上。
        val spaced = PdfTtsFixtures.page(text = "Line1: text", chars = PdfTtsFixtures.chars("Line 1: text"))
        assertEquals(0..11, PdfTtsPage.from(spaced).charRange(0))

        val tight = PdfTtsFixtures.page(text = "a b c", chars = PdfTtsFixtures.chars("abc"))
        assertEquals(0..2, PdfTtsPage.from(tight).charRange(0))
    }

    @Test
    fun whitespaceCharactersInsideSentenceAreNotUsedAsRangeEndpoints() {
        // 句尾空白没有字符框，区间必须收在最后一个可见字符上，否则高亮会多画一段。
        val page = PdfTtsPage.from(
            PdfTtsFixtures.page(text = "ab  ", chars = PdfTtsFixtures.chars("ab  ")),
        )

        assertEquals(listOf("ab"), page.sentences.map { it.text })
        assertEquals(0..1, page.charRange(0))
    }

    @Test
    fun sentenceRectsCoverItsCharacters() {
        // 高亮矩形来自句内字符框；空白字符没有框，不参与机械合并。
        val page = PdfTtsPage.from(
            PdfTtsFixtures.page(text = "ab. cd.", chars = PdfTtsFixtures.chars("ab. cd.")),
        )

        assertEquals(1, page.rects(0).size)
        assertEquals(1, page.rects(1).size)
        assertTrue(page.rects(9).isEmpty())
    }
}