package app.particlebook.android.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    }

    @Test
    fun formatMappingCoversTextFormats() {
        assertEquals(ReaderFormat.PDF, ReaderFormat.fromRaw("PDF"))
        assertEquals(ReaderFormat.TEXT, ReaderFormat.fromRaw("md"))
        assertEquals(ReaderFormat.EPUB, ReaderFormat.fromRaw("epub"))
        assertEquals(ReaderFormat.EPUB, ReaderFormat.fromRaw(null))
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
