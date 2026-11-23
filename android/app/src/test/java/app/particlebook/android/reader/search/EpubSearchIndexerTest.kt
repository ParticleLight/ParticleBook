package app.particlebook.android.reader.search

import app.particlebook.android.reader.ReaderLocator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

class EpubSearchIndexerTest {

    private val tempFiles = ArrayList<File>()

    @After
    fun cleanup() {
        tempFiles.forEach { it.delete() }
    }

    @Test
    fun indexesEachSpineChapterWithDecodableLocator() = runBlocking {
        val store = FakeSearchIndexStore()
        val file = epub(
            TestEpubFactory.Chapter("ch1.xhtml", "<h1>第一章</h1><p>河从雾里来。</p>", "第一章"),
            TestEpubFactory.Chapter("ch2.xhtml", "<p>Second chapter, plain latin text.</p>", "第二章"),
        )
        val result = EpubSearchIndexer(store) { 1_767_000_000_000L }.index(7L, file)
        val indexed = result as EpubIndexResult.Indexed

        assertEquals(2, indexed.documentCount)
        assertEquals(2, indexed.spineEntryCount)
        assertEquals(0, indexed.skippedEmptyChapters)
        assertEquals(0, indexed.skippedBrokenChapters)
        assertEquals(7L, indexed.bookId)
        assertEquals(1_767_000_000_000L, indexed.indexedAt)

        val rows = store.rows(7L)
        assertEquals(2, rows.size)
        val locators = rows.map { ReaderLocator.decode(it.locator) as ReaderLocator.Epub }
        assertEquals(
            listOf("OEBPS/Text/ch1.xhtml", "OEBPS/Text/ch2.xhtml"),
            locators.map { it.chapterHref }.sorted(),
        )
        assertTrue(locators.all { it.blockIndex == 0 })
        val first = rows.first { (ReaderLocator.decode(it.locator) as ReaderLocator.Epub).chapterHref.endsWith("ch1.xhtml") }
        assertTrue(first.rawText.contains("河从雾里来"))
        // 落库文本必须是分词后的版本，索引器不重复实现分词规则。
        assertEquals(app.particlebook.android.data.SearchTokenizer.indexText(first.rawText), first.indexedText)

        val checkpoint = EpubIndexCheckpoint.decode(store.stateValue(EpubIndexCheckpoint.key("epub", 7L)))
        assertNotNull(checkpoint)
        assertEquals(1, checkpoint!!.algorithm)
        assertEquals(2, checkpoint.documentCount)
        assertEquals(file.length(), checkpoint.sourceSize)
        assertEquals(file.lastModified(), checkpoint.sourceMtime)
        assertEquals(64, indexed.textHash.length)
    }

    @Test
    fun repeatedIndexSkipsWhileSourceIsUnchanged() = runBlocking {
        val store = FakeSearchIndexStore()
        val file = epub(TestEpubFactory.Chapter("a.xhtml", "<p>内容一</p>"))
        val indexer = EpubSearchIndexer(store) { 42L }

        val first = indexer.index(1L, file) as EpubIndexResult.Indexed
        val snapshot = store.snapshot(1L)
        val stateKey = EpubIndexCheckpoint.key("epub", 1L)
        val stateValue = store.stateValue(stateKey)

        val second = indexer.index(1L, file) as EpubIndexResult.Skipped
        assertEquals(EpubIndexSkipReason.SOURCE_UNCHANGED, second.reason)
        assertEquals(first.textHash, second.textHash)
        assertEquals(1, second.documentCount)
        assertEquals(1, store.replaceCount)
        assertEquals(0, store.stateWriteCount)
        assertEquals(snapshot, store.snapshot(1L))
        assertEquals(stateValue, store.stateValue(stateKey))
    }

    @Test
    fun contentChangeRebuildsAndDropsStaleChapters() = runBlocking {
        val store = FakeSearchIndexStore()
        val indexer = EpubSearchIndexer(store)
        val v1 = epub(
            TestEpubFactory.Chapter("a.xhtml", "<p>旧内容甲</p>"),
            TestEpubFactory.Chapter("b.xhtml", "<p>会被删掉的一章</p>"),
        )
        indexer.index(2L, v1)

        val v2 = epub(
            TestEpubFactory.Chapter("a.xhtml", "<p>新内容甲，长度明显不同</p>"),
            TestEpubFactory.Chapter("c.xhtml", "<p>新章节丙</p>"),
        )
        val result = indexer.index(2L, v2) as EpubIndexResult.Indexed

        assertEquals(2, result.documentCount)
        val hrefs = store.rows(2L).map { (ReaderLocator.decode(it.locator) as ReaderLocator.Epub).chapterHref.substringAfterLast('/') }
        assertEquals(listOf("a.xhtml", "c.xhtml"), hrefs)
        assertTrue(store.rows(2L).any { it.rawText.contains("新内容甲") })
        assertEquals(2, store.replaceCount)
    }

    @Test
    fun sameTextWithNewMtimeOnlyRefreshesFingerprint() = runBlocking {
        val store = FakeSearchIndexStore()
        val indexer = EpubSearchIndexer(store) { 100L }
        val original = epub(TestEpubFactory.Chapter("a.xhtml", "<p>内容不变</p>"))
        indexer.index(3L, original)

        val copy = File.createTempFile("pb-index-copy-", ".epub")
        tempFiles += copy
        copy.writeBytes(original.readBytes())
        check(copy.setLastModified(original.lastModified() + 60_000L)) { "cannot set mtime on " + copy }

        val stateKey = EpubIndexCheckpoint.key("epub", 3L)
        val previous = store.stateValue(stateKey)
        val result = indexer.index(3L, copy) as EpubIndexResult.Skipped

        assertEquals(EpubIndexSkipReason.TEXT_UNCHANGED, result.reason)
        assertEquals(1, store.replaceCount)
        assertEquals(1, store.stateWriteCount)
        val refreshed = EpubIndexCheckpoint.decode(store.stateValue(stateKey))!!
        assertEquals(copy.length(), refreshed.sourceSize)
        assertEquals(copy.lastModified(), refreshed.sourceMtime)
        assertEquals(previous!!.substringBefore(";size="), refreshed.encode().substringBefore(";size="))
    }

    @Test
    fun failedWriteKeepsPreviousIndexIntact() = runBlocking {
        val store = FakeSearchIndexStore()
        val indexer = EpubSearchIndexer(store)
        val v1 = epub(TestEpubFactory.Chapter("a.xhtml", "<p>第一版</p>"))
        indexer.index(4L, v1)
        val snapshot = store.snapshot(4L)
        val stateKey = EpubIndexCheckpoint.key("epub", 4L)
        val stateValue = store.stateValue(stateKey)

        val v2 = epub(
            TestEpubFactory.Chapter("a.xhtml", "<p>第二版内容更长，确保文件大小不同</p>"),
            TestEpubFactory.Chapter("b.xhtml", "<p>新增章节</p>"),
        )
        store.failReplaceAfterDocuments = 1
        val failure = runCatching { indexer.index(4L, v2) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(snapshot, store.snapshot(4L))
        assertEquals(stateValue, store.stateValue(stateKey))
        assertEquals(2, store.replaceCount)

        store.failReplaceAfterDocuments = null
        val retried = indexer.index(4L, v2) as EpubIndexResult.Indexed
        assertEquals(2, retried.documentCount)
        assertNotEquals(snapshot, store.snapshot(4L))
    }

    @Test
    fun corruptFileKeepsPreviousIndexIntact() = runBlocking {
        val store = FakeSearchIndexStore()
        val indexer = EpubSearchIndexer(store)
        indexer.index(5L, epub(TestEpubFactory.Chapter("a.xhtml", "<p>正常内容</p>")))
        val snapshot = store.snapshot(5L)

        val bogus = File.createTempFile("pb-index-bogus-", ".epub")
        tempFiles += bogus
        bogus.writeText("this is not a zip archive")
        val failure = runCatching { indexer.index(5L, bogus) }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(1, store.replaceCount)
        assertEquals(snapshot, store.snapshot(5L))
    }

    @Test
    fun refusesToCommitWhenBookHasNoIndexableText() = runBlocking {
        val store = FakeSearchIndexStore()
        val indexer = EpubSearchIndexer(store)
        val onlyImages = epub(
            TestEpubFactory.Chapter("img.xhtml", """<img src="../Images/pic.png" alt=""/>"""),
        )
        val failure = runCatching { indexer.index(6L, onlyImages) }.exceptionOrNull()

        assertTrue(failure is EpubIndexException)
        assertEquals(0, store.replaceCount)
        assertTrue(store.rows(6L).isEmpty())
        assertNull(store.stateValue(EpubIndexCheckpoint.key("epub", 6L)))
    }

    @Test
    fun reportsEmptyAndBrokenChaptersWithoutAborting() = runBlocking {
        val store = FakeSearchIndexStore()
        val file = epub(
            TestEpubFactory.Chapter("a.xhtml", "<p>有字甲</p>"),
            TestEpubFactory.Chapter("img.xhtml", """<img src="../Images/pic.png" alt=""/>"""),
            TestEpubFactory.Chapter("b.xhtml", "<p>有字乙</p>"),
            TestEpubFactory.Chapter("gone.xhtml", "<p>此章在 ZIP 中缺失</p>"),
            missingEntries = setOf("gone.xhtml"),
        )
        val result = EpubSearchIndexer(store).index(8L, file) as EpubIndexResult.Indexed

        assertEquals(4, result.spineEntryCount)
        assertEquals(2, result.documentCount)
        assertEquals(1, result.skippedEmptyChapters)
        assertEquals(1, result.skippedBrokenChapters)
        assertTrue(result.warnings.any { it.contains("CHAPTER_MISSING") })
        assertEquals(2, store.rows(8L).size)
    }

    @Test
    fun deduplicatesSpineEntries() = runBlocking {
        val store = FakeSearchIndexStore()
        val file = epub(
            TestEpubFactory.Chapter("a.xhtml", "<p>A</p>"),
            TestEpubFactory.Chapter("b.xhtml", "<p>B</p>"),
            spine = listOf("a.xhtml", "a.xhtml", "b.xhtml"),
        )
        val result = EpubSearchIndexer(store).index(9L, file) as EpubIndexResult.Indexed
        assertEquals(3, result.spineEntryCount)
        assertEquals(1, result.duplicateSpineEntries)
        assertEquals(2, result.documentCount)
        assertEquals(2, store.rows(9L).size)
    }

    @Test
    fun writesScaleEvidenceForBundledSampleBook() = runBlocking {
        val seed = File("src/main/assets/seed/seed-01.epub")
        Assume.assumeTrue("seed book missing: " + seed.absolutePath, seed.isFile)
        val store = FakeSearchIndexStore()
        val result = EpubSearchIndexer(store).index(101L, seed) as EpubIndexResult.Indexed

        assertEquals(3, result.spineEntryCount)
        assertEquals(3, result.documentCount)
        assertEquals(0, result.skippedEmptyChapters)
        assertEquals(0, result.skippedBrokenChapters)
        assertEquals(3, store.rows(101L).size)
        writeEvidence("seed-01", result, store)
    }

    @Test
    fun writesScaleEvidenceForRealNovel() = runBlocking {
        val url = javaClass.getResource("/p1/books/moby-dick.epub")
        Assume.assumeNotNull(url)
        val file = File(url!!.toURI())
        val store = FakeSearchIndexStore()
        val result = EpubSearchIndexer(store).index(102L, file) as EpubIndexResult.Indexed

        assertTrue("expected many chapters, got " + result.spineEntryCount, result.spineEntryCount > 100)
        assertTrue("expected documents", result.documentCount > 100)
        assertEquals(result.documentCount, store.rows(102L).size)
        assertTrue(store.rows(102L).all { ReaderLocator.decode(it.locator) is ReaderLocator.Epub })
        writeEvidence("moby-dick", result, store)
    }

    private fun writeEvidence(label: String, result: EpubIndexResult.Indexed, store: FakeSearchIndexStore) {
        val dir = File("build/search-index")
        dir.mkdirs()
        val rows = store.rows(result.bookId)
        val lines = ArrayList<String>(rows.size + 1)
        lines += "BOOK " + label + " | spine=" + result.spineEntryCount + " docs=" + result.documentCount +
            " empty=" + result.skippedEmptyChapters + " broken=" + result.skippedBrokenChapters +
            " dup=" + result.duplicateSpineEntries + " hash=" + result.textHash
        rows.forEach { row ->
            val locator = ReaderLocator.decode(row.locator) as? ReaderLocator.Epub
            lines += "  CHAPTER href=" + (locator?.chapterHref ?: "?") +
                " block=" + (locator?.blockIndex ?: -1) +
                " chars=" + row.rawText.length +
                " indexedChars=" + row.indexedText.length
        }
        File(dir, "index-" + label + ".txt").writeText(lines.joinToString("\n") + "\n")
    }

    private fun epub(
        vararg chapters: TestEpubFactory.Chapter,
        spine: List<String> = chapters.map { it.fileName },
        missingEntries: Set<String> = emptySet(),
    ): File {
        val file = File.createTempFile("pb-index-", ".epub")
        tempFiles += file
        return TestEpubFactory.write(file, chapters.toList(), spine, missingEntries)
    }
}
