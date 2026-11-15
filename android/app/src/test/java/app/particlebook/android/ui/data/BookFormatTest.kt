package app.particlebook.android.ui.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookFormatTest {
    @Test
    fun fromRawRecognizesSupportedFormats() {
        assertEquals(BookFormat.Pdf, BookFormat.fromRaw("pdf"))
        assertEquals(BookFormat.Pdf, BookFormat.fromRaw("PDF"))
        assertEquals(BookFormat.Epub, BookFormat.fromRaw("epub"))
        assertEquals(BookFormat.Epub, BookFormat.fromRaw("EPUB"))
    }

    @Test
    fun fromRawDoesNotDefaultUnknownToEpub() {
        assertEquals(BookFormat.Unsupported, BookFormat.fromRaw("txt"))
        assertEquals(BookFormat.Unsupported, BookFormat.fromRaw("azw3"))
        assertEquals(BookFormat.Unsupported, BookFormat.fromRaw(""))
        assertEquals(BookFormat.Unsupported, BookFormat.fromRaw(null))
    }

    @Test
    fun onlyEpubAndPdfAreReadable() {
        assertTrue(BookFormat.Epub.isReadable)
        assertTrue(BookFormat.Pdf.isReadable)
        assertFalse(BookFormat.Unsupported.isReadable)
    }

    @Test
    fun rawRoundTripsThroughFromRaw() {
        BookFormat.entries.forEach { format ->
            assertEquals(format, BookFormat.fromRaw(format.raw))
        }
    }
}
