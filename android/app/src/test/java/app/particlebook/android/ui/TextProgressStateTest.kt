package app.particlebook.android.ui

import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.reader.text.TextDocument
import app.particlebook.android.reader.text.TextDocumentParser
import app.particlebook.android.reader.text.TextImage
import app.particlebook.android.reader.text.TextParagraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextProgressStateTest {
    // Three paragraphs start at 0, 4 and 7; the document ends at 12.
    private fun document(): TextDocument = TextDocumentParser.fromParagraphs(
        listOf(TextParagraph("AAAA"), TextParagraph("bbb"), TextParagraph("ccccc")),
    )

    private fun imageDocument(): TextDocument = TextDocumentParser.fromParagraphs(
        listOf(
            TextParagraph("head"),
            TextParagraph("", image = TextImage(key = "illustration", bytes = byteArrayOf(1))),
            TextParagraph("tail"),
        ),
    )

    private fun restore(
        raw: String? = null,
        paragraph: Int? = null,
        saved: ReaderLocator? = ReaderLocator.decode(raw),
        requested: ReaderLocator? = null,
        source: TextDocument = document(),
    ): TextProgressRestore = TextProgressRestore.from(source, raw, saved, paragraph, requested)

    private fun visible(
        paragraph: Int = 1,
        paragraphs: Int = 3,
        offset: Int = 4,
        length: Int = 3,
    ) = TextVisibleProgress(ReaderLocator.Text(offset, length), paragraph, paragraphs)

    private fun assertRestored(restored: TextProgressRestore, index: Int, preserved: Boolean) {
        assertEquals(index, restored.paragraphIndex)
        assertEquals(preserved, restored.state.preserveSavedRaw)
        assertNull(restored.state.displayed)
        assertNull(restored.state.writable)
    }

    @Test fun freshDocumentWaitsForTheFirstActuallyShownParagraph() {
        val restored = restore()
        assertRestored(restored, index = 0, preserved = false)
        val shown = visible(paragraph = 0, offset = 0, length = 4)
        assertEquals(shown, restored.state.paragraphShown(shown, userInitiated = false).writable)
    }

    @Test fun matchingSavedParagraphAndTextLocatorRestoreNormally() {
        val saved = ReaderLocator.Text(4, 3)
        assertRestored(restore(saved.encode(), paragraph = 1), index = 1, preserved = false)
    }

    @Test fun legacyParagraphWithoutAnyLocatorRemainsWritableAfterDisplay() {
        val restored = restore(paragraph = 2)
        assertRestored(restored, index = 2, preserved = false)
        val shown = visible(paragraph = 2, offset = 7, length = 5)
        assertEquals(shown, restored.state.paragraphShown(shown, false).writable)
    }

    @Test fun emptyOrWhitespaceRawDoesNotInventAnErrorForALegacyParagraph() {
        listOf("", " ", "  \t\n ").forEach { raw ->
            assertRestored(restore(raw, paragraph = 1), index = 1, preserved = false)
        }
    }

    @Test fun legacyTextEncodingCanRestoreWithoutASavedParagraph() {
        assertRestored(restore(raw = "text|5"), index = 1, preserved = false)
    }

    @Test fun validTypedTextDoesNotRequireRawToBePresent() {
        assertRestored(restore(saved = ReaderLocator.Text(7)), index = 2, preserved = false)
    }

    @Test fun malformedRawKeepsTheValidParagraphFallbackProtected() {
        listOf("not-a-locator", "v1|text|bad|0", "v999|text|4|0").forEach { raw ->
            val restored = restore(raw, paragraph = 1)
            assertRestored(restored, index = 1, preserved = true)
            assertNull(raw, restored.state.paragraphShown(visible(), false).writable)
        }
    }

    @Test fun undecodableRawWithoutAParagraphFallsBackToTheStartUnderProtection() {
        assertRestored(restore(raw = "v1|text|4"), index = 0, preserved = true)
    }

    @Test fun otherLocatorFormatsNeverBecomeTextRestores() {
        val locators = listOf(
            ReaderLocator.Pdf(2),
            ReaderLocator.Epub("chapter.xhtml", 0),
            ReaderLocator.Html("chapter.html"),
            ReaderLocator.Scroll(0.5f),
        )
        locators.forEach { saved ->
            assertRestored(restore(saved.encode(), paragraph = 1), index = 1, preserved = true)
        }
    }

    @Test fun mismatchedTypedLocatorIsProtectedEvenWithoutRaw() {
        assertRestored(restore(paragraph = 1, saved = ReaderLocator.Pdf(1)), index = 1, preserved = true)
    }

    @Test fun negativeSavedParagraphsClampToTheStartAndPreserveTheOldPosition() {
        listOf(-1, Int.MIN_VALUE).forEach { paragraph ->
            assertRestored(restore(paragraph = paragraph), index = 0, preserved = true)
        }
    }

    @Test fun savedParagraphAtOrBeyondTheCountClampsToTheLastParagraph() {
        listOf(3, Int.MAX_VALUE).forEach { paragraph ->
            assertRestored(restore(paragraph = paragraph), index = 2, preserved = true)
        }
    }

    @Test fun shortenedLegacyDocumentKeepsTheOldPositionProtected() {
        val shortened = TextDocumentParser.fromParagraphs(List(300) { TextParagraph("x") })
        val restored = restore(paragraph = 500, source = shortened)
        assertRestored(restored, index = 299, preserved = true)
        val fallback = visible(paragraph = 299, paragraphs = 300, offset = 299, length = 1)
        val state = restored.state.paragraphShown(fallback, false)
        assertEquals(fallback, state.displayed)
        repeat(3) { assertNull(state.writable) } // Debounce, ON_STOP and disposal share this gate.
    }

    @Test fun shortenedDocumentAlsoPreservesAnOutOfRangeTypedLocator() {
        val shortened = TextDocumentParser.fromParagraphs(List(300) { TextParagraph("x") })
        val saved = ReaderLocator.Text(500)
        assertRestored(restore(saved.encode(), paragraph = 500, source = shortened), 299, true)
    }

    @Test fun savedParagraphWinsOverAConflictingButInRangeOffset() {
        val saved = ReaderLocator.Text(7)
        assertRestored(restore(saved.encode(), paragraph = 1), index = 1, preserved = true)
    }

    @Test fun invalidSavedParagraphStillHasPriorityOverAValidTextOffset() {
        val nearEnd = ReaderLocator.Text(7)
        assertRestored(restore(nearEnd.encode(), paragraph = -1), 0, true)
        val nearStart = ReaderLocator.Text(0)
        assertRestored(restore(nearStart.encode(), paragraph = Int.MAX_VALUE), 2, true)
    }

    @Test fun offsetInsideTheSavedParagraphStillSignalsAChangedAnchor() {
        val saved = ReaderLocator.Text(5)
        assertRestored(restore(saved.encode(), paragraph = 1), index = 1, preserved = true)
    }

    @Test fun validSavedRangeNeedNotEqualTheParagraphTextLength() {
        val saved = ReaderLocator.Text(4, 8) // The range ends exactly at EOF.
        assertRestored(restore(saved.encode(), paragraph = 1), index = 1, preserved = false)
    }

    @Test fun absentParagraphUsesTheContainingParagraphOfAValidOffset() {
        val saved = ReaderLocator.Text(5, 1)
        assertRestored(restore(saved.encode()), index = 1, preserved = false)
    }

    @Test fun negativeOrOutOfRangeTypedTextFallsBackUnderProtection() {
        listOf(ReaderLocator.Text(-1), ReaderLocator.Text(13), ReaderLocator.Text(4, -1)).forEach { saved ->
            assertRestored(restore(saved = saved), index = 0, preserved = true)
        }
    }

    @Test fun rangePastEofIsProtectedEvenWhenItsStartIsInTheDocument() {
        val saved = ReaderLocator.Text(11, 2)
        assertRestored(restore(saved.encode()), index = 0, preserved = true)
    }

    @Test fun textRangeAdditionUsesLongInsteadOfWrappingInt() {
        val saved = ReaderLocator.Text(1, Int.MAX_VALUE)
        assertRestored(restore(saved.encode()), index = 0, preserved = true)
    }

    @Test fun zeroLengthSavedLocatorAtEofMapsToTheLastParagraph() {
        val saved = ReaderLocator.Text(12)
        assertRestored(restore(saved.encode()), index = 2, preserved = false)
    }

    @Test fun positiveLengthAtEofIsOutsideTheDocument() {
        val saved = ReaderLocator.Text(12, 1)
        assertRestored(restore(saved.encode()), index = 0, preserved = true)
    }

    @Test fun validExplicitRequestOverridesAnUnrecoverableSavedPosition() {
        val restored = restore(
            raw = "broken-saved-locator",
            paragraph = 500,
            requested = ReaderLocator.Text(7, 5),
        )
        assertRestored(restored, index = 2, preserved = false)
        val shown = visible(paragraph = 2, offset = 7, length = 5)
        assertEquals(shown, restored.state.paragraphShown(shown, false).writable)
    }

    @Test fun explicitRequestUsesTheContainingParagraphRatherThanTheOldParagraph() {
        assertRestored(restore(paragraph = 0, requested = ReaderLocator.Text(5, 1)), 1, false)
    }

    @Test fun explicitRequestAtTheStartCanReplaceAProtectedSavedPosition() {
        assertRestored(restore(raw = "broken", paragraph = 2, requested = ReaderLocator.Text(0)), 0, false)
    }

    @Test fun explicitRequestAtEofIsAValidNavigation() {
        assertRestored(restore(raw = "broken", requested = ReaderLocator.Text(12)), 2, false)
    }

    @Test fun invalidExplicitTextRequestsKeepTheSavedProtection() {
        val invalid = listOf(
            ReaderLocator.Text(-1),
            ReaderLocator.Text(4, -1),
            ReaderLocator.Text(13),
            ReaderLocator.Text(11, 2),
            ReaderLocator.Text(1, Int.MAX_VALUE),
        )
        invalid.forEach { requested ->
            assertRestored(restore(raw = "broken", paragraph = 1, requested = requested), 1, true)
        }
    }

    @Test fun invalidExplicitRequestCanStillUseAValidSavedPosition() {
        val saved = ReaderLocator.Text(4, 3)
        assertRestored(restore(saved.encode(), paragraph = 1, requested = ReaderLocator.Text(13)), 1, false)
    }

    @Test fun nonTextExplicitRequestsDoNotTakeOverAProtectedSavedPosition() {
        listOf(ReaderLocator.Pdf(0), ReaderLocator.Epub("chapter.xhtml", 0), ReaderLocator.Scroll(1f))
            .forEach { requested ->
                assertRestored(restore(raw = "broken", paragraph = 1, requested = requested), 1, true)
            }
    }

    @Test fun emptyDocumentHasNoWritableDisplay() {
        val empty = TextDocumentParser.fromParagraphs(emptyList())
        assertRestored(restore(source = empty), index = 0, preserved = false)
        assertNull(TextProgressState().paragraphShown(visible(paragraph = 0, paragraphs = 0), false).writable)
    }

    @Test fun emptyDocumentPreservesEveryFormOfSavedPosition() {
        val empty = TextDocumentParser.fromParagraphs(emptyList())
        assertRestored(restore(raw = "broken", source = empty), 0, true)
        assertRestored(restore(saved = ReaderLocator.Text(0), source = empty), 0, true)
        assertRestored(restore(paragraph = 0, source = empty), 0, true)
    }

    @Test fun explicitRequestDoesNotReleaseSavedProtectionForAnEmptyDocument() {
        val empty = TextDocumentParser.fromParagraphs(emptyList())
        assertRestored(restore(raw = "broken", requested = ReaderLocator.Text(0), source = empty), 0, true)
    }

    @Test fun validSingleParagraphBecomesWritableAfterItIsShown() {
        val single = TextDocumentParser.fromParagraphs(listOf(TextParagraph("only")))
        val restored = restore(paragraph = 0, source = single)
        assertRestored(restored, 0, false)
        val shown = visible(paragraph = 0, paragraphs = 1, offset = 0, length = 4)
        assertEquals(shown, restored.state.paragraphShown(shown, false).writable)
    }

    @Test fun singleParagraphProtectionCanBeReleasedByExplicitAcceptance() {
        val single = TextDocumentParser.fromParagraphs(listOf(TextParagraph("only")))
        val shown = visible(paragraph = 0, paragraphs = 1, offset = 0, length = 4)
        val state = restore(raw = "broken", source = single).state
            .paragraphShown(shown, false).paragraphShown(shown, true)
        assertTrue(state.preserveSavedRaw)
        assertNull(state.writable)
        assertEquals(shown, state.acceptDisplayed().writable)
    }

    @Test fun savedImageParagraphKeepsItsIndexWhenFollowingTextSharesTheOffset() {
        val saved = ReaderLocator.Text(4)
        assertRestored(restore(saved.encode(), paragraph = 1, source = imageDocument()), 1, false)
    }

    @Test fun savedTextAfterAnImageKeepsItsOwnIndexAtTheSharedOffset() {
        val saved = ReaderLocator.Text(4)
        assertRestored(restore(saved.encode(), paragraph = 2, source = imageDocument()), 2, false)
    }

    @Test fun explicitOffsetAtAnImageBoundaryUsesTheLastParagraphAtThatOffset() {
        assertRestored(restore(requested = ReaderLocator.Text(4), source = imageDocument()), 2, false)
    }

    @Test fun imageOnlyDocumentIsNotMistakenForAnEmptyDocument() {
        val imageOnly = TextDocumentParser.fromParagraphs(
            listOf(TextParagraph("", image = TextImage(key = "cover", bytes = byteArrayOf(1)))),
        )
        val saved = ReaderLocator.Text(0)
        val restored = restore(saved.encode(), paragraph = 0, source = imageOnly)
        assertRestored(restored, 0, false)
        val shown = visible(paragraph = 0, paragraphs = 1, offset = 0, length = 0)
        assertEquals(shown, restored.state.paragraphShown(shown, false).writable)
    }

    @Test fun preservedStateRecordsTheShownParagraphWithoutMakingItWritable() {
        val shown = visible()
        val state = TextProgressState(preserveSavedRaw = true).paragraphShown(shown, false)
        assertTrue(state.preserveSavedRaw)
        assertEquals(shown, state.displayed)
        assertNull(state.writable)
    }

    @Test fun writableRejectsInvalidSnapshotsEvenWhenConstructedDirectly() {
        val valid = visible()
        listOf(
            valid.copy(paragraphs = 0),
            valid.copy(paragraphs = -1),
            valid.copy(paragraph = -1),
            valid.copy(paragraph = 3),
            valid.copy(locator = ReaderLocator.Text(-1, 3)),
            valid.copy(locator = ReaderLocator.Text(4, -1)),
        ).forEach { invalid ->
            assertNull(invalid.toString(), TextProgressState(displayed = invalid).writable)
        }
    }

    @Test fun invalidUserSamplesNeitherOverwriteTheDisplayNorReleaseProtection() {
        val shown = visible()
        val state = TextProgressState(preserveSavedRaw = true, displayed = shown)
        listOf(
            visible(paragraph = -1),
            visible(paragraph = 3),
            visible(paragraph = 0, paragraphs = 0),
            visible(paragraph = 2, offset = -1),
            visible(paragraph = 2, length = -1),
        ).forEach { invalid ->
            assertEquals(state, state.paragraphShown(invalid, userInitiated = true))
        }
    }

    @Test fun invalidPassiveSampleDoesNotEraseAnAlreadyWritableSnapshot() {
        val shown = visible()
        val state = TextProgressState(displayed = shown)
        val result = state.paragraphShown(visible(paragraph = 0, paragraphs = 0), false)
        assertEquals(shown, result.displayed)
        assertEquals(shown, result.writable)
    }

    @Test fun passiveParagraphChangesKeepTheOldRawProtectedAndRecordTheLatestDisplay() {
        val first = visible()
        val next = visible(paragraph = 2, offset = 7, length = 5)
        val state = TextProgressState(preserveSavedRaw = true)
            .paragraphShown(first, false).paragraphShown(next, false)
        assertTrue(state.preserveSavedRaw)
        assertEquals(next, state.displayed)
        assertNull(state.writable)
    }

    @Test fun userActionThatLeavesTheSameParagraphVisibleKeepsProtection() {
        val shown = visible()
        val state = TextProgressState(preserveSavedRaw = true, displayed = shown)
            .paragraphShown(shown, true)
        assertTrue(state.preserveSavedRaw)
        assertNull(state.writable)
    }

    @Test fun paragraphCountChangeAloneDoesNotCountAsAUserParagraphTurn() {
        val changedCount = visible(paragraphs = 4)
        val state = TextProgressState(preserveSavedRaw = true, displayed = visible())
            .paragraphShown(changedCount, true)
        assertEquals(changedCount, state.displayed)
        assertTrue(state.preserveSavedRaw)
        assertNull(state.writable)
    }

    @Test fun sameParagraphAnchorChangeDoesNotCountAsAUserParagraphTurn() {
        val changedAnchor = visible(offset = 5, length = 1)
        val state = TextProgressState(preserveSavedRaw = true, displayed = visible())
            .paragraphShown(changedAnchor, true)
        assertEquals(changedAnchor, state.displayed)
        assertTrue(state.preserveSavedRaw)
        assertNull(state.writable)
    }

    @Test fun actualUserParagraphTurnReleasesProtectionAtTheNewDisplay() {
        val next = visible(paragraph = 2, offset = 7, length = 5)
        val original = TextProgressState(preserveSavedRaw = true, displayed = visible())
        val changed = original.paragraphShown(next, true)
        assertFalse(changed.preserveSavedRaw)
        assertEquals(next, changed.writable)
        assertTrue(original.preserveSavedRaw)
        assertEquals(visible(), original.displayed)
    }

    @Test fun firstUserInitiatedDisplayCanEstablishANewPosition() {
        val shown = visible()
        val state = TextProgressState(preserveSavedRaw = true).paragraphShown(shown, true)
        assertFalse(state.preserveSavedRaw)
        assertEquals(shown, state.writable)
    }

    @Test fun passiveMovementAfterUserTakeoverRemainsWritable() {
        val userTarget = visible(paragraph = 2, offset = 7, length = 5)
        val passiveTarget = visible(paragraph = 0, offset = 0, length = 4)
        val state = TextProgressState(preserveSavedRaw = true, displayed = visible())
            .paragraphShown(userTarget, true).paragraphShown(passiveTarget, false)
        assertFalse(state.preserveSavedRaw)
        assertEquals(passiveTarget, state.writable)
    }

    @Test fun explicitAcceptanceReleasesOnlyTheExistingShownSnapshot() {
        val shown = visible()
        val original = TextProgressState(preserveSavedRaw = true, displayed = shown)
        val accepted = original.acceptDisplayed()
        assertFalse(accepted.preserveSavedRaw)
        assertEquals(shown, accepted.displayed)
        assertEquals(shown, accepted.writable)
        assertTrue(original.preserveSavedRaw)
    }

    @Test fun explicitAcceptanceBeforeAnyDisplayKeepsProtection() {
        val state = TextProgressState(preserveSavedRaw = true)
        assertEquals(state, state.acceptDisplayed())
        assertNull(state.acceptDisplayed().writable)
    }

    @Test fun explicitAcceptanceOfAnInvalidDisplayKeepsProtection() {
        val state = TextProgressState(preserveSavedRaw = true, displayed = visible(paragraphs = 0))
        assertEquals(state, state.acceptDisplayed())
        assertNull(state.acceptDisplayed().writable)
    }

    @Test fun visibleSnapshotValidationDoesNotRequireAnUnavailableDocumentLength() {
        val shown = visible(
            paragraph = Int.MAX_VALUE - 1,
            paragraphs = Int.MAX_VALUE,
            offset = Int.MAX_VALUE,
            length = Int.MAX_VALUE,
        )
        assertEquals(shown, TextProgressState(displayed = shown).writable)
    }
}
