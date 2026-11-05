package app.particlebook.android.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderContractsTest {

    @Test
    fun epubLocatorRoundTripsHrefWithSeparators() {
        val locator = ReaderLocator.Epub("OEBPS/text/ch|01.xhtml", 12, 345)
        assertEquals(locator, ReaderLocator.decode(locator.encode()))
    }

    @Test
    fun pdfLocatorRoundTripsNormalizedCoordinates() {
        val locator = ReaderLocator.Pdf(7, 0.25f, 0.75f)
        assertEquals(locator, ReaderLocator.decode(locator.encode()))
    }

    @Test
    fun textLocatorRoundTrips() {
        assertEquals(ReaderLocator.Text(42), ReaderLocator.decode(ReaderLocator.Text(42).encode()))
    }

    @Test
    fun legacyPdfJsonIsAccepted() {
        assertEquals(
            ReaderLocator.Pdf(9),
            ReaderLocator.decode("""{"kind":"pdf","page":9,"total":20}"""),
        )
    }

    @Test
    fun malformedValuesReturnNull() {
        assertNull(ReaderLocator.decode(null))
        assertNull(ReaderLocator.decode(""))
        assertNull(ReaderLocator.decode("epub|not-base64!|1|2"))
        assertNull(ReaderLocator.decode("pdf|x|0|0"))
        assertNull(ReaderLocator.decode("unknown|1"))
        assertNull(ReaderLocator.decode("""{"kind":"pdf","total":20}"""))
    }

    @Test
    fun negativeOffsetsAreClamped() {
        assertEquals(ReaderLocator.Pdf(0), ReaderLocator.decode("pdf|-3|0|0"))
        assertEquals(ReaderLocator.Text(0), ReaderLocator.decode("text|-3"))
        assertNull(ReaderLocator.decode("epub|Y2gueGh0bWw|-1|0"))
    }

    @Test
    fun formatMappingRejectsUnsupportedFormats() {
        assertEquals(ReaderFormat.PDF, ReaderFormat.fromRaw("PDF"))
        assertEquals(ReaderFormat.TEXT, ReaderFormat.fromRaw("md"))
        assertEquals(ReaderFormat.HTML, ReaderFormat.fromRaw("html"))
        assertEquals(ReaderFormat.EPUB, ReaderFormat.fromRaw("epub"))
        assertEquals(ReaderFormat.UNSUPPORTED, ReaderFormat.fromRaw("azw3"))
        assertEquals(ReaderFormat.UNSUPPORTED, ReaderFormat.fromRaw(null))
    }

    @Test
    fun fiveLocatorKindsRoundTripWithRanges() {
        val values = listOf(
            ReaderLocator.Epub("OEBPS/ch1.xhtml", 2, 3, 4),
            ReaderLocator.Pdf(5, 0.1f, 0.2f, ReaderRect(0.1f, 0.2f, 0.3f, 0.4f)),
            ReaderLocator.Text(6, 7),
            ReaderLocator.Html("notes.xhtml", "sec-2", 8, 9),
            ReaderLocator.Scroll(0.55f),
        )
        values.forEach { locator ->
            val encoded = locator.encode()
            assertTrue(encoded, encoded.startsWith("v1|"))
            assertEquals(locator, ReaderLocator.decode(encoded))
        }
    }

    @Test
    fun invalidV1ValuesAreRejected() {
        assertNull(ReaderLocator.decode("v1|pdf|-1|0|0|-|-|-|-"))
        assertNull(ReaderLocator.decode("v1|pdf|0|NaN|0|-|-|-|-"))
        assertNull(ReaderLocator.decode("v1|pdf|0|0|0|0.5|0.5|0.4|0.6"))
        assertNull(ReaderLocator.decode("v1|epub|" + "a".repeat(12000) + "|0|0|0"))
        assertNull(ReaderLocator.decode("v1|scroll|NaN"))
    }

    @Test
    fun legacyUnversionedLinesStillDecode() {
        assertEquals(ReaderLocator.Pdf(3, 0.25f, 0.75f), ReaderLocator.decode("pdf|3|0.25|0.75"))
        assertEquals(ReaderLocator.Epub("ch.xhtml", 1, 2), ReaderLocator.decode("epub|Y2gueGh0bWw|1|2"))
    }

    @Test
    fun annotationCarriesLocatorAndLegacyFields() {
        val annotation = ReaderAnnotation(
            id = 7,
            bookId = 9,
            type = ReaderAnnotationType.HIGHLIGHT,
            locator = ReaderLocator.Epub("ch.xhtml", 1, 2, 3),
            quote = "quote",
            note = "note",
            tags = listOf("tag"),
            legacyId = 11,
            legacyType = "highlight",
        )
        assertEquals(annotation, annotation.copy())
        assertEquals(ReaderAnnotationType.HIGHLIGHT, annotation.type)
    }

    @Test
    fun sessionProgressIsClamped() {
        val session = ReaderSession(1L, ReaderFormat.EPUB)
            .withProgress(chapter = 2, page = 3, pages = 10, percent = 1.4f)
        assertEquals(2, session.chapterIndex)
        assertEquals(3, session.pageIndex)
        assertEquals(10, session.pageCount)
        assertEquals(1f, session.percent, 0f)
    }
}
