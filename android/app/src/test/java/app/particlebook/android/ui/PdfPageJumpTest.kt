package app.particlebook.android.ui

import app.particlebook.android.reader.ReaderLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** PDF 页级跳转的纯逻辑：输入解析/夹取 + 起始页优先级。Composable 不在单测范围内。 */
class PdfPageJumpTest {

    // ---------------------------------------------------------------- 页码解析

    @Test
    fun blankInputHasNoTarget() {
        assertNull(parsePdfPageInput("", 10))
        assertNull(parsePdfPageInput("   ", 10))
    }

    @Test
    fun nonNumericInputHasNoTarget() {
        assertNull(parsePdfPageInput("abc", 10))
        assertNull(parsePdfPageInput("12a", 10))
        assertNull(parsePdfPageInput("3.5", 10))
        assertNull(parsePdfPageInput("1 2", 10))
    }

    @Test
    fun negativeInputHasNoTarget() {
        assertNull(parsePdfPageInput("-3", 10))
        assertNull(parsePdfPageInput("-0", 10))
    }

    @Test
    fun zeroClampsToFirstPage() {
        assertEquals(1, parsePdfPageInput("0", 10))
        assertEquals(1, parsePdfPageInput("000", 10))
    }

    @Test
    fun inRangePageIsKeptAsIs() {
        assertEquals(1, parsePdfPageInput("1", 10))
        assertEquals(7, parsePdfPageInput("7", 10))
        assertEquals(10, parsePdfPageInput("10", 10))
    }

    @Test
    fun overRangeClampsToLastPage() {
        assertEquals(10, parsePdfPageInput("11", 10))
        // 超长输入也只会夹到最后一页，不会溢出成负数。
        assertEquals(10, parsePdfPageInput("99999999999999999999", 10))
        assertNull(parsePdfPageInput("9999999999x", 10))
    }

    @Test
    fun surroundingSpacesAreTrimmed() {
        assertEquals(7, parsePdfPageInput(" 7 ", 500))
        assertEquals(12, parsePdfPageInput("\t12\n", 500))
    }

    @Test
    fun fullWidthDigitsAreAccepted() {
        assertEquals(7, parsePdfPageInput("７", 500))
        assertEquals(123, parsePdfPageInput("１２３", 500))
        assertEquals(12, parsePdfPageInput("1２", 500))
        assertEquals(10, parsePdfPageInput("１２３４５", 10))
    }

    @Test
    fun documentWithoutPagesHasNoTarget() {
        assertNull(parsePdfPageInput("1", 0))
        assertNull(parsePdfPageInput("1", -3))
    }

    // ---------------------------------------------------------------- 起始页优先级

    @Test
    fun searchHitWinsOverRestoreAndSavedPage() {
        assertEquals(
            120,
            initialPdfPage(
                jump = ReaderLocator.Pdf(120),
                restored = ReaderLocator.Pdf(4),
                savedPage = 7,
                pageCount = 300,
            ),
        )
    }

    @Test
    fun searchHitOnBoundariesIsAccepted() {
        assertEquals(0, initialPdfPage(ReaderLocator.Pdf(0), ReaderLocator.Pdf(5), 9, 10))
        assertEquals(9, initialPdfPage(ReaderLocator.Pdf(9), ReaderLocator.Pdf(5), 9, 10))
    }

    @Test
    fun outOfRangeSearchHitFallsBackToRestore() {
        assertEquals(
            4,
            initialPdfPage(
                jump = ReaderLocator.Pdf(900),
                restored = ReaderLocator.Pdf(4),
                savedPage = 7,
                pageCount = 300,
            ),
        )
        // 恢复位置也越界时沿用旧行为夹到最后一页（是否落盘交给 PdfProgressState）。
        assertEquals(9, initialPdfPage(ReaderLocator.Pdf(900), ReaderLocator.Pdf(400), null, 10))
    }

    @Test
    fun negativeSearchHitIsIgnored() {
        assertEquals(3, initialPdfPage(ReaderLocator.Pdf(-1), null, 3, 10))
    }

    @Test
    fun restoreBeatsSavedPage() {
        assertEquals(6, initialPdfPage(null, ReaderLocator.Pdf(6), 2, 10))
    }

    @Test
    fun outOfRangeRestoreKeepsExistingClampBehaviour() {
        // 恢复位置越界时沿用旧行为：夹到最后一页，是否落盘交给 PdfProgressState 的失效保护。
        assertEquals(9, initialPdfPage(null, ReaderLocator.Pdf(42), 2, 10))
    }

    @Test
    fun savedPageIsClamped() {
        assertEquals(9, initialPdfPage(null, null, 42, 10))
        assertEquals(0, initialPdfPage(null, null, -5, 10))
    }

    @Test
    fun noHintsStartsAtFirstPage() {
        assertEquals(0, initialPdfPage(null, null, null, 10))
        assertEquals(0, initialPdfPage(ReaderLocator.Pdf(3), ReaderLocator.Pdf(3), 3, 0))
    }
}
