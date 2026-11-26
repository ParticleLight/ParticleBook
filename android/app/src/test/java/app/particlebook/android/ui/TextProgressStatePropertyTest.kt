package app.particlebook.android.ui

import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.reader.text.TextDocument
import app.particlebook.android.reader.text.TextImage
import app.particlebook.android.reader.text.TextParagraph
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextProgressStatePropertyTest {
    @Test
    fun passiveValidHistoriesNeverReleaseSavedProtection() {
        val random = Random(0x25A551)
        repeat(128) { sample ->
            var state = TextProgressState(preserveSavedRaw = true)
            repeat(24) { step ->
                val shown = if (step == 0) {
                    TextVisibleProgress(
                        locator = ReaderLocator.Text(Int.MAX_VALUE, Int.MAX_VALUE),
                        paragraph = Int.MAX_VALUE - 1,
                        paragraphs = Int.MAX_VALUE,
                    )
                } else {
                    progressAt(random, random.nextInt(256))
                }
                state = state.paragraphShown(shown, userInitiated = false)

                val context = "sample $sample, passive step $step"
                assertEquals(context, shown, state.displayed)
                assertTrue(context, state.preserveSavedRaw)
                assertNull(context, state.writable)
            }
        }
    }

    @Test
    fun invalidObservationsAreIdentityForEitherIntentAndEitherProtectionState() {
        val random = Random(0x25BAD1)
        repeat(96) { sample ->
            val shown = progressAt(random, random.nextInt(256))
            val states = listOf(
                TextProgressState(preserveSavedRaw = true),
                TextProgressState(preserveSavedRaw = false),
                TextProgressState(preserveSavedRaw = true, displayed = shown),
                TextProgressState(preserveSavedRaw = false, displayed = shown),
            )
            for (before in states) {
                for (invalid in invalidObservations(shown).shuffled(random)) {
                    for (userInitiated in listOf(false, true)) {
                        val after = before.paragraphShown(invalid, userInitiated)
                        val context = "sample $sample, invalid=$invalid, user=$userInitiated, before=$before"
                        assertEquals(context, before, after)
                        assertEquals(context, before.writable, after.writable)
                    }
                }
            }
        }
    }

    @Test
    fun userMetadataChangesNeedAnActualParagraphChangeToReleaseProtection() {
        val random = Random(0x251A7E)
        repeat(128) { sample ->
            var latest = progressAt(random, random.nextInt(256))
            var state = TextProgressState(preserveSavedRaw = true)
                .paragraphShown(latest, userInitiated = false)

            repeat(16) { step ->
                val userInitiated = step == 0 || random.nextBoolean()
                val paragraph = if (userInitiated) latest.paragraph else random.nextInt(256)
                latest = progressAt(random, paragraph)
                state = state.paragraphShown(latest, userInitiated)
                val context = "sample $sample, protected step $step, user=$userInitiated"
                assertEquals(context, latest, state.displayed)
                assertTrue(context, state.preserveSavedRaw)
                assertNull(context, state.writable)
            }

            val paragraph = latest.paragraph
            val changedParagraph = if (paragraph > 0 && random.nextBoolean()) {
                random.nextInt(paragraph)
            } else {
                paragraph + random.nextInt(1, 65)
            }
            val moved = progressAt(random, changedParagraph)
            state = state.paragraphShown(moved, userInitiated = true)
            assertFalse("sample $sample: actual user movement releases protection", state.preserveSavedRaw)
            assertEquals(moved, state.displayed)
            assertEquals(moved, state.writable)

            repeat(8) {
                val passive = progressAt(random, random.nextInt(256))
                state = state.paragraphShown(passive, userInitiated = false)
                assertFalse("sample $sample: passive changes do not re-arm protection", state.preserveSavedRaw)
                assertEquals(passive, state.writable)
            }

            val firstUserDisplay = TextProgressState(preserveSavedRaw = true)
                .paragraphShown(moved, userInitiated = true)
            assertFalse("sample $sample: a first user display counts as movement", firstUserDisplay.preserveSavedRaw)
            assertEquals(moved, firstUserDisplay.writable)
        }
    }

    @Test
    fun explicitAcceptanceRequiresAValidDisplayAndUsesTheLatestPassiveObservation() {
        val random = Random(0x25ACCE)
        repeat(96) { sample ->
            val emptyState = TextProgressState(preserveSavedRaw = true)
            assertEquals("sample $sample: no display to accept", emptyState, emptyState.acceptDisplayed())
            val valid = progressAt(random, random.nextInt(256))
            for (invalid in invalidObservations(valid)) {
                val invalidState = TextProgressState(preserveSavedRaw = true, displayed = invalid)
                assertNull("sample $sample: invalid display is never writable", invalidState.writable)
                assertEquals(invalidState, invalidState.acceptDisplayed())
            }

            var state = emptyState
            var latest = valid
            repeat(random.nextInt(1, 25)) {
                latest = progressAt(random, random.nextInt(256))
                state = state.paragraphShown(latest, userInitiated = false)
            }
            assertTrue("sample $sample: passive history stays protected", state.preserveSavedRaw)
            assertNull(state.writable)
            val accepted = state.acceptDisplayed()
            assertFalse("sample $sample: explicit acceptance releases protection", accepted.preserveSavedRaw)
            assertEquals(latest, accepted.displayed)
            assertEquals(latest, accepted.writable)
            assertEquals("sample $sample: acceptance is idempotent", accepted, accepted.acceptDisplayed())
        }
    }

    @Test
    fun savedParagraphSamplesStayInBoundsAndDoNotBecomeWritableBeforeDisplay() {
        val random = Random(0x25B0A1)
        repeat(128) { sample ->
            val count = when (sample % 8) {
                0 -> 0
                1 -> 1
                else -> random.nextInt(2, 81)
            }
            val document = document(random, count)
            val savedParagraphs = listOf(
                Int.MIN_VALUE, -random.nextInt(1, 1_000), -1, 0,
                count / 2, count - 1, count, count + random.nextInt(1, 1_000), Int.MAX_VALUE,
            ).distinct()
            for (savedParagraph in savedParagraphs) {
                val restored = TextProgressRestore.from(
                    document = document,
                    rawLocator = "v1|text|0|0",
                    savedLocator = ReaderLocator.Text(0),
                    savedParagraph = savedParagraph,
                )
                val expectedIndex = when {
                    count == 0 || savedParagraph < 0 -> 0
                    savedParagraph >= count -> count - 1
                    else -> savedParagraph
                }
                val expectedProtection = when {
                    count == 0 || savedParagraph < 0 || savedParagraph >= count -> true
                    else -> document.charOffsets[savedParagraph] != 0
                }
                val context = "sample $sample, count=$count, savedParagraph=$savedParagraph"
                assertEquals(context, expectedIndex, restored.paragraphIndex)
                assertTrue(context, count == 0 || restored.paragraphIndex in 0 until count)
                assertEquals(context, expectedProtection, restored.state.preserveSavedRaw)
                assertNull(context, restored.state.displayed)
                assertNull(context, restored.state.writable)
            }
        }
    }

    @Test
    fun requestedRangesFollowAnIndependentLinearOracleAndInvalidRequestsKeepProtection() {
        val random = Random(0x25A116)
        repeat(128) { sample ->
            val count = if (sample % 16 == 0) 0 else random.nextInt(1, 81)
            val document = document(random, count)
            val offset = random.nextInt(document.charCount + 1)
            val remaining = document.charCount - offset
            val validRequests = listOf(
                ReaderLocator.Text(0, document.charCount),
                ReaderLocator.Text(document.charCount, 0),
                ReaderLocator.Text(offset, 0),
                ReaderLocator.Text(offset, remaining),
                ReaderLocator.Text(offset, random.nextInt(remaining + 1)),
            )
            val savedParagraph = if (random.nextBoolean()) Int.MIN_VALUE else Int.MAX_VALUE
            val fallback = if (count == 0 || savedParagraph < 0) 0 else count - 1
            for (requested in validRequests) {
                val restored = TextProgressRestore.from(
                    document = document,
                    rawLocator = "unreadable saved locator",
                    savedLocator = ReaderLocator.Text(-1),
                    savedParagraph = savedParagraph,
                    requestedLocator = requested,
                )
                val expected = if (count == 0) 0 else linearParagraphAt(document, requested.offset)
                val context = "sample $sample, requested=$requested, count=$count"
                assertEquals(context, expected, restored.paragraphIndex)
                assertTrue(context, count == 0 || restored.paragraphIndex in 0 until count)
                assertEquals(context, count == 0, restored.state.preserveSavedRaw)
                assertNull(context, restored.state.displayed)
                assertNull(context, restored.state.writable)
            }

            // These ranges are invalid by construction, including sums that overflow Int.
            val invalidRequests: List<ReaderLocator> = listOf(
                ReaderLocator.Text(-1, 0),
                ReaderLocator.Text(0, -1),
                ReaderLocator.Text(document.charCount + 1, 0),
                ReaderLocator.Text(document.charCount, 1),
                ReaderLocator.Text(Int.MAX_VALUE, Int.MAX_VALUE),
                ReaderLocator.Text(1, Int.MAX_VALUE),
                ReaderLocator.Pdf(random.nextInt(20)),
                ReaderLocator.Epub("chapter.xhtml", random.nextInt(20)),
                ReaderLocator.Html("book.html", "anchor"),
                ReaderLocator.Scroll(random.nextFloat()),
            )
            for (requested in invalidRequests.shuffled(random)) {
                val restored = TextProgressRestore.from(
                    document = document,
                    rawLocator = "unreadable saved locator",
                    savedLocator = ReaderLocator.Text(-1),
                    savedParagraph = savedParagraph,
                    requestedLocator = requested,
                )
                val context = "sample $sample, invalid requested=$requested"
                assertEquals(context, fallback, restored.paragraphIndex)
                assertTrue(context, count == 0 || restored.paragraphIndex in 0 until count)
                assertTrue(context, restored.state.preserveSavedRaw)
                assertNull(context, restored.state.displayed)
                assertNull(context, restored.state.writable)
            }
        }
    }

    @Test
    fun ordinaryRestoresKeepExactParagraphsIncludingImagesWithSharedOffsets() {
        val random = Random(0x25E7AC)
        repeat(96) { sample ->
            val document = document(random, random.nextInt(3, 81), sharedOffsets = true)
            assertEquals(document.charOffsets[0], document.charOffsets[1])
            assertEquals(document.charOffsets[1], document.charOffsets[2])
            assertTrue("fixture has an offset mapping distinct from paragraph zero", linearParagraphAt(document, 0) > 0)
            for (paragraph in document.paragraphs.indices.shuffled(random)) {
                val offset = document.charOffsets[paragraph]
                val length = random.nextInt(document.charCount - offset + 1)
                val saved = ReaderLocator.Text(offset, length)
                val restored = TextProgressRestore.from(
                    document = document,
                    rawLocator = "v1|text|$offset|$length",
                    savedLocator = saved,
                    savedParagraph = paragraph,
                )
                val context = "sample $sample, paragraph=$paragraph, offset=$offset"
                assertEquals(context, paragraph, restored.paragraphIndex)
                assertFalse(context, restored.state.preserveSavedRaw)
                assertNull(context, restored.state.displayed)
                assertNull(context, restored.state.writable)
                val shown = TextVisibleProgress(saved, paragraph, document.paragraphs.size)
                assertEquals(context, shown, restored.state.paragraphShown(shown, userInitiated = false).writable)

                val legacy = TextProgressRestore.from(
                    document = document,
                    rawLocator = if (random.nextBoolean()) null else " \t\r\n",
                    savedLocator = null,
                    savedParagraph = paragraph,
                )
                assertEquals("$context, legacy paragraph stays exact", paragraph, legacy.paragraphIndex)
                assertFalse("$context, valid legacy paragraph needs no protection", legacy.state.preserveSavedRaw)
                assertNull(context, legacy.state.displayed)
                assertNull(context, legacy.state.writable)
            }
        }
    }

    private fun progressAt(random: Random, paragraph: Int): TextVisibleProgress = TextVisibleProgress(
        locator = ReaderLocator.Text(nonNegativeInt(random), nonNegativeInt(random)),
        paragraph = paragraph,
        paragraphs = paragraph + random.nextInt(1, 257),
    )

    private fun nonNegativeInt(random: Random): Int = when (random.nextInt(4)) {
        0 -> 0
        1 -> Int.MAX_VALUE
        else -> random.nextInt(1, 100_000)
    }

    private fun invalidObservations(valid: TextVisibleProgress): List<TextVisibleProgress> = listOf(
        valid.copy(paragraph = -1),
        valid.copy(paragraph = Int.MIN_VALUE),
        valid.copy(paragraph = valid.paragraphs),
        valid.copy(paragraph = Int.MAX_VALUE),
        valid.copy(paragraphs = 0),
        valid.copy(paragraphs = -1),
        valid.copy(paragraphs = Int.MIN_VALUE),
        valid.copy(locator = ReaderLocator.Text(-1, valid.locator.length)),
        valid.copy(locator = ReaderLocator.Text(Int.MIN_VALUE, valid.locator.length)),
        valid.copy(locator = ReaderLocator.Text(valid.locator.offset, -1)),
        valid.copy(locator = ReaderLocator.Text(valid.locator.offset, Int.MIN_VALUE)),
    )

    private fun document(random: Random, count: Int, sharedOffsets: Boolean = false): TextDocument {
        val paragraphs = List(count) { index ->
            val isImage = when {
                sharedOffsets && index < 2 -> true
                sharedOffsets && index == 2 -> false
                else -> random.nextInt(4) == 0
            }
            if (isImage) {
                TextParagraph("", image = TextImage(key = "image-$index"))
            } else {
                TextParagraph("x".repeat(random.nextInt(1, 65)))
            }
        }
        var totalChars = 0
        val offsets = IntArray(count) { index ->
            val start = totalChars
            totalChars += paragraphs[index].text.length
            start
        }
        return TextDocument(paragraphs, offsets).apply { bindCharCount(totalChars) }
    }

    // Deliberately linear and independent of the production offset lookup / restore helpers.
    private fun linearParagraphAt(document: TextDocument, offset: Int): Int =
        document.charOffsets.indices.lastOrNull { document.charOffsets[it] <= offset } ?: 0
}
