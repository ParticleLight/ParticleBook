package app.particlebook.android.reader.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubIndexCheckpointTest {

    private val sample = EpubIndexCheckpoint(
        algorithm = 1,
        textHash = "a".repeat(64),
        indexedAt = 1_767_000_000_000L,
        spineChapterCount = 3,
        documentCount = 2,
        sourceSize = 9_999L,
        sourceMtime = 1_700_000_000_000L,
    )

    @Test
    fun roundTripsEveryField() {
        assertEquals(sample, EpubIndexCheckpoint.decode(sample.encode()))
    }

    @Test
    fun rejectsForeignOrCorruptValues() {
        assertNull(EpubIndexCheckpoint.decode(null))
        assertNull(EpubIndexCheckpoint.decode(""))
        assertNull(EpubIndexCheckpoint.decode("v2;algorithm=1;hash=" + "a".repeat(64)))
        assertNull(EpubIndexCheckpoint.decode("algorithm=1;hash=" + "a".repeat(64)))
        assertNull(EpubIndexCheckpoint.decode(sample.encode().replace("hash=" + "a".repeat(64), "hash=abc")))
        assertNull(EpubIndexCheckpoint.decode(sample.encode().replace(";algorithm=1", "")))
        assertNull(EpubIndexCheckpoint.decode(sample.encode().replace("docs=2", "docs=x")))
        assertNull(EpubIndexCheckpoint.decode(sample.encode() + ";junk"))
        assertNull(EpubIndexCheckpoint.decode(sample.encode().replace("size=9999", "size=-1")))
    }

    @Test
    fun matchesSourceOnlyWhenSizeAndMtimeAgree() {
        assertTrue(sample.matchesSource(9_999L, 1_700_000_000_000L))
        assertFalse(sample.matchesSource(9_998L, 1_700_000_000_000L))
        assertFalse(sample.matchesSource(9_999L, 1_700_000_000_001L))
        assertFalse(sample.matchesSource(9_999L, 0L))
        assertFalse(sample.copy(sourceMtime = 0L).matchesSource(9_999L, 0L))
    }

    @Test
    fun checkpointKeyIsScopedByDocTypeAndBook() {
        assertEquals("index:epub:42", EpubIndexCheckpoint.key("epub", 42L))
        assertEquals("index:pdf:42", EpubIndexCheckpoint.key("pdf", 42L))
    }
}
