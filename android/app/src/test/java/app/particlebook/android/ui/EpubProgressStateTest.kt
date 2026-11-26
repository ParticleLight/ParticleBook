package app.particlebook.android.ui

import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.reader.epub.EpubBlock
import app.particlebook.android.reader.epub.EpubCharLineMetrics
import app.particlebook.android.reader.epub.layout.EpubChapterLayout
import app.particlebook.android.reader.epub.layout.EpubLayoutConfig
import app.particlebook.android.reader.epub.layout.MeasuredBlock
import app.particlebook.android.reader.epub.layout.Page
import app.particlebook.android.reader.epub.layout.PageSlice
import app.particlebook.android.reader.epub.render.EpubPageIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubProgressStateTest {
    private fun visible(chapter: String = "chapter0.xhtml", page: Int = 2, pages: Int = 12) =
        EpubVisibleProgress(ReaderLocator.Epub(chapter, page * 3, page * 40), page, pages)

    private fun restore(raw: String?, page: Int? = 2, chapter: Int? = 0): EpubProgressRestore =
        EpubProgressRestore.from(raw, ReaderLocator.decode(raw), page) { chapter }

    private fun restoreLayout(): EpubChapterLayout {
        val lines = object : EpubCharLineMetrics {
            override val lineCount = 2
            override fun lineTop(line: Int) = line * 20
            override fun lineBottom(line: Int) = (line + 1) * 20
            override fun lineBaseline(line: Int) = line * 20 + 15
            override fun lineStart(line: Int) = line * 4
            override fun lineEnd(line: Int) = (line + 1) * 4
        }
        return EpubChapterLayout(
            chapterHref = "chapter0.xhtml",
            chapterTitle = null,
            config = EpubLayoutConfig(320, 640),
            measuredBlocks = listOf(MeasuredBlock(7, EpubBlock.Paragraph("abcdefgh"), 40, lines = lines)),
            pages = (0..1).map { page ->
                Page(page, listOf(PageSlice(7, page, page + 1, 0, 20)), 20)
            },
        )
    }

    private fun missingBlockFallback(): EpubProgressState {
        val saved = ReaderLocator.Epub("chapter0.xhtml", Int.MAX_VALUE)
        val restored = restore(saved.encode(), page = 0)
        assertFalse(restored.state.preserveSavedRaw) // href 已解析，须等真实布局作第二阶段核验。
        return restored.state.initialLayoutApplied(restoreLayout(), restored.locator, false)
            .pageShown(visible(page = 0, pages = 2), false)
    }

    @Test fun noSavedRowAllowsFirstLaidOutPage() {
        val restored = EpubProgressRestore.from(null, null, null) { error("no href to resolve") }
        assertEquals(0, restored.chapterIndex)
        assertNull(restored.page)
        assertNull(restored.locator)
        assertNull(restored.state.writable)
        val shown = visible(page = 0)
        assertEquals(shown, restored.state.pageShown(shown, false).writable)
    }

    @Test fun explicitlyEmptyRawSurvivesFallbackAndRepeatedExitReads() {
        val restored = restore("")
        assertEquals(0, restored.chapterIndex)
        assertEquals(2, restored.page)
        assertNull(restored.locator)
        val state = restored.state.pageShown(visible(), false)
        assertTrue(state.preserveSavedRaw)
        repeat(3) { assertNull(state.writable) } // 自动保存、Back 与 ON_STOP 共用此门禁。
    }

    @Test fun malformedRawFallsBackToSavedPageWithoutBecomingWritable() {
        listOf("epub|***|1|2", "pdf|nan|0|0", "pdf|NaN|0|0", "v999|epub|bad", "   ").forEach { raw ->
            val restored = restore(raw, page = 4)
            assertEquals(raw, 0, restored.chapterIndex)
            assertEquals(raw, 4, restored.page)
            assertNull(raw, restored.locator)
            assertNull(raw, restored.state.pageShown(visible(page = 4), false).writable)
        }
    }

    @Test fun savedOtherFormatIsNotAcceptedAsEpubRestoration() {
        val raw = ReaderLocator.Pdf(8).encode()
        val restored = restore(raw, page = 8)
        assertNull(restored.locator)
        assertEquals(0, restored.chapterIndex)
        assertEquals(8, restored.page)
        assertTrue(restored.state.preserveSavedRaw)
    }

    @Test fun missingHrefPreservesValidRawAndUsesChapterZeroSavedPage() {
        val saved = ReaderLocator.Epub("removed.xhtml", 9, 14)
        val restored = restore(saved.encode(), page = 3, chapter = null)
        assertEquals(0, restored.chapterIndex)
        assertEquals(3, restored.page)
        assertNull(restored.locator)
        assertNull(restored.state.pageShown(visible(page = 3), false).writable)
    }

    @Test fun externalLocatorAfterMissingHrefTakesOverOnlyAfterFirstTargetPageIsShown() {
        val layout = restoreLayout()
        val saved = ReaderLocator.Epub("removed.xhtml", 9, 14)
        val restored = restore(saved.encode(), page = 1, chapter = null)
        val target = ReaderLocator.Epub(layout.chapterHref, 7, 2)
        val page = requireNotNull(EpubPageIndex.pageForLocator(layout, target))
        val shown = EpubVisibleProgress(
            requireNotNull(EpubPageIndex.locatorForPage(layout, page)), page, layout.pageCount,
        )
        assertNull(restored.locator)
        assertTrue(restored.state.preserveSavedRaw)
        assertNull(restored.state.writable)

        val laidOut = restored.state.initialLayoutApplied(layout, target, true)
        assertTrue(laidOut.preserveSavedRaw)
        assertNull(laidOut.displayed)
        assertNull(laidOut.writable)

        val displayed = laidOut.pageShown(shown, true)
        assertFalse(displayed.preserveSavedRaw)
        assertEquals(shown, displayed.writable)
        assertEquals(target.copy(charOffset = 0), displayed.writable?.locator)
    }

    @Test fun passiveLayoutAfterMissingHrefKeepsRawEvenForResolvableTarget() {
        val layout = restoreLayout()
        val saved = ReaderLocator.Epub("removed.xhtml", 9, 14)
        val restored = restore(saved.encode(), page = 1, chapter = null)
        val target = ReaderLocator.Epub(layout.chapterHref, 7, 2)
        val page = requireNotNull(EpubPageIndex.pageForLocator(layout, target))
        val shown = EpubVisibleProgress(
            requireNotNull(EpubPageIndex.locatorForPage(layout, page)), page, layout.pageCount,
        )

        val state = restored.state.initialLayoutApplied(layout, target, false).pageShown(shown, false)
        assertEquals(shown, state.displayed)
        assertTrue(state.preserveSavedRaw)
        assertNull(state.writable)
    }

    @Test fun validHrefRestoresResolvedChapterAndLocator() {
        val saved = ReaderLocator.Epub("chapter7.xhtml#anchor", 9, 14)
        val restored = EpubProgressRestore.from(saved.encode(), saved, 4) { href ->
            assertEquals(saved.chapterHref, href)
            7
        }
        assertEquals(7, restored.chapterIndex)
        assertEquals(4, restored.page)
        assertEquals(saved, restored.locator)
        assertFalse(restored.state.preserveSavedRaw)
        val shown = visible("chapter7.xhtml", 4)
        assertEquals(shown, restored.state.pageShown(shown, false).writable)
    }

    @Test fun automaticRepaginationDoesNotReleaseFailedRestoration() {
        val state = restore("").state.pageShown(visible(page = 2), false)
            .pageShown(visible(page = 1, pages = 9), false)
        assertEquals(1, state.displayed?.page)
        assertTrue(state.preserveSavedRaw)
        assertNull(state.writable)
    }

    @Test fun userNoOpAtSamePageKeepsDiagnosticRaw() {
        val shown = visible()
        val state = restore("").state.pageShown(shown, false).pageShown(shown, true)
        assertTrue(state.preserveSavedRaw)
        assertNull(state.writable)
    }

    @Test fun userAnchorWithinSameVisiblePageKeepsDiagnosticRaw() {
        val shown = visible()
        val samePage = shown.copy(locator = shown.locator.copy(charOffset = 120))
        val state = restore("").state.pageShown(shown, false).pageShown(samePage, true)
        assertNull(state.writable)
    }

    @Test fun actualUserTurnReleasesGuardAtNewLaidOutPage() {
        val next = visible(page = 3)
        val state = restore("").state.pageShown(visible(), false).pageShown(next, true)
        assertFalse(state.preserveSavedRaw)
        assertEquals(next, state.writable)
    }

    @Test fun actualUserChapterNavigationReleasesGuard() {
        val target = visible("chapter1.xhtml", page = 0)
        val state = restore("").state.pageShown(visible(), false).pageShown(target, true)
        assertEquals(target, state.writable)
    }

    @Test fun explicitUserNavigationBeforeFirstLayoutCanEstablishPosition() {
        val target = visible("chapter1.xhtml", page = 0)
        assertEquals(target, restore("").state.pageShown(target, true).writable)
    }

    @Test fun pendingOrEmptyTargetDoesNotReplaceVisibleBackSnapshot() {
        val shown = visible()
        val state = EpubProgressState().pageShown(shown, false)
        val notLaidOut = visible("chapter1.xhtml", page = 0, pages = 0)
        assertSame(state, state.pageShown(notLaidOut, true))
        assertEquals(shown, state.writable)
        val applied = state.pageShown(notLaidOut.copy(pages = 6), true)
        assertEquals("chapter1.xhtml", applied.writable?.locator?.chapterHref)
        assertEquals(shown, state.writable)
    }

    @Test fun pendingOrInvalidTargetDoesNotReleaseDiagnosticGuard() {
        val state = restore("").state.pageShown(visible(), false)
        listOf(visible(page = -1), visible(page = 12), visible(page = 0, pages = 0)).forEach { target ->
            assertSame(state, state.pageShown(target, true))
            assertNull(state.writable)
        }
    }

    @Test fun validHrefButMissingBlockPreservesRawAfterFallbackAndExitReads() {
        val saved = ReaderLocator.Epub("chapter0.xhtml", Int.MAX_VALUE)
        assertNull(EpubPageIndex.pageForLocator(restoreLayout(), saved))
        val state = missingBlockFallback()
        assertTrue(state.preserveSavedRaw)
        assertEquals(0, state.displayed?.page)
        repeat(3) { assertNull(state.writable) } // 自动保存、Back、ON_STOP 均沿用同一门禁。
    }

    @Test fun userNoOpAfterMissingBlockFallbackDoesNotReleaseRaw() {
        val state = missingBlockFallback().pageShown(visible(page = 0, pages = 2), true)
        assertTrue(state.preserveSavedRaw)
        assertNull(state.writable)
    }

    @Test fun automaticReflowAfterMissingBlockFallbackDoesNotReleaseRaw() {
        val state = missingBlockFallback().initialLayoutApplied(restoreLayout(), null, false)
            .pageShown(visible(page = 1, pages = 2), false)
        assertTrue(state.preserveSavedRaw)
        assertEquals(1, state.displayed?.page)
        assertNull(state.writable)
    }

    @Test fun actualUserTurnAfterMissingBlockFallbackReleasesRaw() {
        val next = visible(page = 1, pages = 2)
        val state = missingBlockFallback().pageShown(next, true)
        assertFalse(state.preserveSavedRaw)
        assertEquals(next, state.writable)
    }

    @Test fun validMappedBlockUsesOriginalIndexAndNormalAutomaticSave() {
        val saved = ReaderLocator.Epub("chapter0.xhtml", 7, 4)
        val restored = restore(saved.encode(), page = 0)
        val layout = restoreLayout()
        assertEquals(1, EpubPageIndex.pageForLocator(layout, saved))
        val state = restored.state.initialLayoutApplied(layout, restored.locator, false)
        assertSame(restored.state, state)
        val shown = visible(page = 1, pages = 2)
        assertEquals(shown, state.pageShown(shown, false).writable)
    }

    @Test fun oversizedOffsetCanDisplayLastLineButPreservesRaw() {
        val saved = ReaderLocator.Epub("chapter0.xhtml", 7, Int.MAX_VALUE)
        val restored = restore(saved.encode(), page = 0)
        val layout = restoreLayout()
        assertEquals(1, EpubPageIndex.pageForLocator(layout, saved)) // 原显示策略仍可近似到末行。
        val state = restored.state.initialLayoutApplied(layout, restored.locator, false)
            .pageShown(visible(page = 1, pages = 2), false)
        assertTrue(state.preserveSavedRaw)
        assertNull(state.writable)
        assertNull(state.pageShown(visible(page = 1, pages = 2), true).writable)
        assertEquals(0, state.pageShown(visible(page = 0, pages = 2), true).writable?.page)
    }

    @Test fun offsetAtTextEndRemainsAValidRestoration() {
        val saved = ReaderLocator.Epub("chapter0.xhtml", 7, 8)
        val restored = restore(saved.encode(), page = 0)
        val state = restored.state.initialLayoutApplied(restoreLayout(), restored.locator, false)
        assertSame(restored.state, state)
        assertFalse(state.preserveSavedRaw)
    }

    @Test fun explicitUserRequestRetainsExistingApproximateNavigationRules() {
        val saved = ReaderLocator.Epub("chapter0.xhtml", Int.MAX_VALUE)
        val restored = restore(saved.encode(), page = 0)
        val state = restored.state.initialLayoutApplied(restoreLayout(), restored.locator, true)
        assertSame(restored.state, state)
        assertFalse(state.preserveSavedRaw)
    }

    @Test fun measuredBlockWithoutVisibleSliceStillFailsInitialRestoration() {
        val saved = ReaderLocator.Epub("chapter0.xhtml", 7)
        val restored = restore(saved.encode(), page = 0)
        val layout = restoreLayout().let { it.copy(pages = listOf(it.pages[0].copy(slices = emptyList()))) }
        val state = restored.state.initialLayoutApplied(layout, restored.locator, false)
            .pageShown(visible(page = 0, pages = 1), false)
        assertTrue(state.preserveSavedRaw)
        assertNull(state.writable)
    }

    @Test fun laterAutomaticLayoutDoesNotReinstateReleasedGuard() {
        val moved = restore("").state.pageShown(visible(), false).pageShown(visible(page = 3), true)
        val repaginated = visible(page = 4, pages = 15)
        assertEquals(repaginated, moved.pageShown(repaginated, false).writable)
    }
}
