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
        assertEquals(BookFormat.Text, BookFormat.fromRaw("txt"))
        assertEquals(BookFormat.Text, BookFormat.fromRaw("TXT"))
        assertEquals(BookFormat.Text, BookFormat.fromRaw("text"))
        assertEquals(BookFormat.Markdown, BookFormat.fromRaw("md"))
        assertEquals(BookFormat.Markdown, BookFormat.fromRaw("Markdown"))
        assertEquals(BookFormat.Fb2, BookFormat.fromRaw("fb2"))
        assertEquals(BookFormat.Fb2, BookFormat.fromRaw("FB2"))
        assertEquals(BookFormat.Html, BookFormat.fromRaw("html"))
        assertEquals(BookFormat.Html, BookFormat.fromRaw("HTM"))
        assertEquals(BookFormat.Html, BookFormat.fromRaw("xhtml"))
    }

    @Test
    fun fromRawDoesNotDefaultUnknownToEpub() {
        assertEquals(BookFormat.Unsupported, BookFormat.fromRaw("azw3"))
        assertEquals(BookFormat.Unsupported, BookFormat.fromRaw(""))
        assertEquals(BookFormat.Unsupported, BookFormat.fromRaw(null))
    }

    @Test
    fun everyFormatExceptUnsupportedIsReadable() {
        assertTrue(BookFormat.Epub.isReadable)
        assertTrue(BookFormat.Pdf.isReadable)
        assertTrue(BookFormat.Text.isReadable)
        assertTrue(BookFormat.Markdown.isReadable)
        assertTrue(BookFormat.Fb2.isReadable)
        assertTrue(BookFormat.Html.isReadable)
        assertFalse(BookFormat.Unsupported.isReadable)
        assertTrue(BookFormat.Text.isTextLike)
        assertTrue(BookFormat.Markdown.isTextLike)
        assertTrue(BookFormat.Fb2.isTextLike)
        assertTrue(BookFormat.Html.isTextLike)
        assertFalse(BookFormat.Epub.isTextLike)
        assertFalse(BookFormat.Pdf.isTextLike)
        assertFalse(BookFormat.Unsupported.isTextLike)
    }

    @Test
    fun rawRoundTripsThroughFromRaw() {
        BookFormat.entries.forEach { format ->
            assertEquals(format, BookFormat.fromRaw(format.raw))
        }
    }
}
