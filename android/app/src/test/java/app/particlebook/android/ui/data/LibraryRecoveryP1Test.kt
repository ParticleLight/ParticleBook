package app.particlebook.android.ui.data

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** task-81：P1-1 记录过滤 + 默认目录文件指纹墓碑；P2-2 finishedAt=0 边界。 */
class LibraryRecoveryP1Test {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("pb-library-recovery-p1-").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun recordlessOrphanIsSkippedWhenSnapshotExists() {
        val dir = newTempDir("downloads")
        epubFile(dir, "orphan.epub")

        val candidates = buildDownloadRecoveryCandidates(
            downloadDir = dir,
            loaded = emptyList(),
            hints = emptyList(),
            minFinishedAt = 1_000L,
        )

        assertTrue("有效快照恢复时不能登记无下载记录的孤儿文件（复活已删书）", candidates.isEmpty())
    }

    @Test
    fun hintAtSnapshotBoundaryIsRecovered() {
        val dir = newTempDir("downloads")
        val file = epubFile(dir, "same-ms.epub")

        val candidates = buildDownloadRecoveryCandidates(
            downloadDir = dir,
            loaded = emptyList(),
            hints = listOf(hint(file, 1_000L)),
            minFinishedAt = 1_000L,
        )

        assertEquals("finishedAt == 快照 mtime 必须允许恢复（>= 边界）", 1, candidates.size)
    }

    @Test
    fun olderHintIsSkipped() {
        val dir = newTempDir("downloads")
        val file = epubFile(dir, "old.epub")

        val candidates = buildDownloadRecoveryCandidates(
            downloadDir = dir,
            loaded = emptyList(),
            hints = listOf(hint(file, 999L)),
            minFinishedAt = 1_000L,
        )

        assertTrue("早于快照的记录不能复活", candidates.isEmpty())
    }

    @Test
    fun tombstonedDefaultDirFileIsSkipped() {
        val dir = newTempDir("downloads")
        val file = epubFile(dir, "deleted.epub")
        val fingerprint = contentFingerprintTag(file)!!
        addDeletedFileTombstones(root, listOf(file), now = 2_000L)
        assertTrue("墓碑应写入持久化文件", fingerprint in readDeletedFileTombstones(root).keys)

        val candidates = buildDownloadRecoveryCandidates(
            downloadDir = dir,
            loaded = emptyList(),
            hints = listOf(hint(file, 2_000L)),
            minFinishedAt = null,
            tombstonedFingerprints = readDeletedFileTombstones(root).keys,
        )

        assertTrue("墓碑命中的默认目录文件不能补登记", candidates.isEmpty())
    }

    @Test
    fun reDownloadClearsTombstone() {
        val dir = newTempDir("downloads")
        val file = epubFile(dir, "downloaded-again.epub")
        addDeletedFileTombstones(root, listOf(file), now = 2_000L)
        assertTrue(forgetDeletedFileTombstones(root, listOf(file)))
        assertFalse("重新导入后墓碑应清掉", contentFingerprintTag(file)!! in readDeletedFileTombstones(root).keys)

        val candidates = buildDownloadRecoveryCandidates(
            downloadDir = dir,
            loaded = emptyList(),
            hints = listOf(hint(file, 2_000L)),
            minFinishedAt = 2_000L,
            tombstonedFingerprints = readDeletedFileTombstones(root).keys,
        )

        assertEquals("清墓碑后应允许补登记", 1, candidates.size)
    }

    @Test
    fun lostIndexStillScansRecordlessButTombstoneWins() {
        val dir = newTempDir("downloads")
        val file = epubFile(dir, "lost.epub")

        val withoutTombstone = buildDownloadRecoveryCandidates(
            downloadDir = dir,
            loaded = emptyList(),
            hints = emptyList(),
            minFinishedAt = null,
        )
        assertEquals("没有快照时保留扫全部文件的兜底", 1, withoutTombstone.size)

        addDeletedFileTombstones(root, listOf(file), now = 2_000L)
        val withTombstone = buildDownloadRecoveryCandidates(
            downloadDir = dir,
            loaded = emptyList(),
            hints = emptyList(),
            minFinishedAt = null,
            tombstonedFingerprints = readDeletedFileTombstones(root).keys,
        )
        assertTrue("即使没有快照，墓碑也必须挡住已删文件", withTombstone.isEmpty())
    }

    @Test
    fun unknownFinishedAtCandidateIsAllowedWhenScanTriggered() {
        val dir = newTempDir("downloads")
        val file = epubFile(dir, "unknown.epub")

        val candidates = buildDownloadRecoveryCandidates(
            downloadDir = dir,
            loaded = emptyList(),
            hints = listOf(hint(file, 0L)),
            minFinishedAt = 1_000L,
        )

        assertEquals("未知 0 不能因快照边界被静默丢掉", 1, candidates.size)
    }

    @Test
    fun finishedAtFallsBackToFileMtime() {
        val dir = newTempDir("downloads")
        val file = epubFile(dir, "legacy.epub")
        assertTrue(file.setLastModified(5_000L))

        assertEquals("旧记录没有 finishedAt 时用文件 mtime", 5_000L, downloadHintFinishedAt(file.absolutePath, 0L))
        assertEquals("已有 finishedAt 不被覆盖", 7_000L, downloadHintFinishedAt(file.absolutePath, 7_000L))
    }

    @Test
    fun tombstoneStoreRoundTripsAndPrunes() {
        val dir = newTempDir("tombstones")
        val files = (1..205).map { index -> epubFile(dir, "book-" + index + ".epub") }
        val fingerprints = addDeletedFileTombstones(root, files, now = 1_000L)

        assertEquals("墓碑数量应有上限", 200, fingerprints.size)
        assertEquals("读回与写入一致", fingerprints, readDeletedFileTombstones(root).keys)
    }

    private fun newTempDir(name: String): File = File(root, name).apply { mkdirs() }

    private fun epubFile(dir: File, name: String): File = File(dir, name).apply {
        writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ("epub task-81 " + name).toByteArray())
    }

    private fun hint(file: File, finishedAt: Long): LibraryDownloadHint = LibraryDownloadHint(
        destPath = file.absolutePath,
        title = file.nameWithoutExtension,
        author = null,
        bookId = "book-" + file.nameWithoutExtension,
        finishedAt = finishedAt,
    )
}
