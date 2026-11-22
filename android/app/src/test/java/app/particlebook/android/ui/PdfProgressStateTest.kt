package app.particlebook.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfProgressStateTest {
    @Test fun validRestoreSavesImmediately() {
        assertTrue(PdfProgressState(protectRestore = false).visiblePage(4))
    }

    @Test fun automaticFillOfShortPagesKeepsInvalidRawProtected() {
        val state = PdfProgressState(protectRestore = true)
        assertFalse(state.visiblePage(9))
        assertFalse(state.visiblePage(6))
        assertFalse(state.visiblePage(5))
        assertFalse(state.visiblePage(5)) // Back/ON_STOP only sample the same visible page.
    }

    @Test fun scrollingWithinFallbackPageDoesNotUnlock() {
        val state = PdfProgressState(protectRestore = true)
        state.visiblePage(6)
        state.navigationStarted(6)
        assertFalse(state.visiblePage(6))
    }

    @Test fun actualPageChangeAfterNavigationUnlocksAndStaysUnlocked() {
        val state = PdfProgressState(protectRestore = true)
        state.visiblePage(6)
        state.navigationStarted(6)
        assertTrue(state.visiblePage(7))
        assertTrue(state.visiblePage(6))
    }

    @Test fun explicitJumpUsesVisibleBaselineRatherThanRequestedRestorePage() {
        val state = PdfProgressState(protectRestore = true)
        state.visiblePage(9)
        state.visiblePage(6)
        state.navigationStarted(6)
        assertFalse(state.visiblePage(6))
        assertTrue(state.visiblePage(4))
    }

    @Test fun navigationBeforeAnyLayoutDoesNotInventAChangedPage() {
        val state = PdfProgressState(protectRestore = true)
        state.navigationStarted(null)
        assertFalse(state.visiblePage(3))
        state.navigationStarted(3)
        assertTrue(state.visiblePage(4))
    }
}
