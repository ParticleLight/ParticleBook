package app.particlebook.android.reader.text

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TextImageExportSnapshotTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun payload(bytes: ByteArray = byteArrayOf(1, 2, 3)) =
        TextImageExportPayload("book.png", "image/png", bytes)

    @Test fun recreatedStoreCanReadTokenWithoutTheOriginalImage() {
        val directory = File(temporary.root, "exports")
        val original = byteArrayOf(0, 1, -1, 42)
        val token = TextImageExportSnapshot(directory).stage(payload(original))
        original.fill(0)
        assertArrayEquals(byteArrayOf(0, 1, -1, 42), TextImageExportSnapshot(directory).read(token))
    }

    @Test fun consecutiveSavesKeepIndependentSnapshots() {
        val store = TextImageExportSnapshot(temporary.newFolder())
        val first = store.stage(payload(byteArrayOf(1)))
        val second = store.stage(payload(byteArrayOf(2)))
        assertNotEquals(first, second)
        assertArrayEquals(byteArrayOf(1), store.read(first))
        assertArrayEquals(byteArrayOf(2), store.read(second))
        store.discard(first)
        assertNull(store.read(first))
        assertArrayEquals(byteArrayOf(2), store.read(second))
    }

    @Test fun discardedSnapshotIsMissingAndRepeatedDiscardIsHarmless() {
        val store = TextImageExportSnapshot(temporary.newFolder())
        val token = store.stage(payload())
        store.discard(token)
        store.discard(token)
        assertNull(store.read(token))
    }

    @Test fun traversalTokensNeverReadOrDeleteOutsideFiles() {
        val directory = temporary.newFolder("exports")
        val outside = temporary.newFile("image-outside.bin").apply { writeBytes(byteArrayOf(7)) }
        val store = TextImageExportSnapshot(directory)
        for (token in listOf("../image-outside.bin", outside.absolutePath, "image-../outside.bin", "")) {
            assertNull(store.read(token))
            store.discard(token)
        }
        assertArrayEquals(byteArrayOf(7), outside.readBytes())
    }

    @Test fun expiredSnapshotsAreRemovedButPendingAndUnrelatedFilesArePreserved() {
        val directory = temporary.newFolder()
        val store = TextImageExportSnapshot(directory)
        val expired = store.stage(payload())
        val pending = store.stage(payload())
        val fresh = store.stage(payload())
        val unrelated = File(directory, "notes.txt").apply { writeText("keep") }
        val now = 3L * 24 * 60 * 60 * 1_000
        assertTrue(File(directory, expired).setLastModified(1_000L))
        assertTrue(File(directory, pending).setLastModified(1_000L))
        assertTrue(File(directory, fresh).setLastModified(now))
        assertTrue(unrelated.setLastModified(1_000L))
        store.discardExpired(pending, now)
        assertFalse(File(directory, expired).exists())
        assertNotNull(store.read(pending))
        assertNotNull(store.read(fresh))
        assertTrue(unrelated.exists())
    }

    @Test fun systemCacheEvictionReturnsMissing() {
        val directory = temporary.newFolder()
        val store = TextImageExportSnapshot(directory)
        val token = store.stage(payload())
        assertTrue(File(directory, token).delete())
        assertNull(store.read(token))
    }

    @Test fun oversizedOrEmptySnapshotIsNotReadable() {
        val directory = temporary.newFolder()
        val store = TextImageExportSnapshot(directory)
        File(directory, "image-empty.bin").writeBytes(byteArrayOf())
        File(directory, "image-large.bin").outputStream().use { stream ->
            stream.write(ByteArray(TextImage.MAX_BYTES + 1))
        }
        assertNull(store.read("image-empty.bin"))
        assertNull(store.read("image-large.bin"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun stagingRejectsAnEmptyPayload() {
        TextImageExportSnapshot(temporary.newFolder()).stage(payload(byteArrayOf()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun stagingRejectsAnOversizedPayload() {
        TextImageExportSnapshot(temporary.newFolder()).stage(payload(ByteArray(TextImage.MAX_BYTES + 1)))
    }

    @Test fun stageCommitsBinTokenWithoutPartResidue() {
        val directory = temporary.newFolder()
        val store = TextImageExportSnapshot(directory)
        val token = store.stage(payload(byteArrayOf(9, 8, 7)))
        assertTrue("token 必须落在 .bin 上：" + token, Regex("image-[a-zA-Z0-9-]+\\.bin").matches(token))
        assertFalse("token 不该带临时后缀：" + token, token.endsWith(".part"))
        // 提交后目录里只剩唯一一份 .bin：写入用的 .part 已被 rename 掉，没有残留。
        assertEquals(listOf(token), directory.list()!!.sorted())
        assertArrayEquals(byteArrayOf(9, 8, 7), store.read(token))
    }

    @Test fun expiredLeftoverPartIsReclaimedWhileFreshAndUnrelatedPartsStay() {
        val directory = temporary.newFolder()
        val store = TextImageExportSnapshot(directory)
        val stalePart = File(directory, "image-123.part").apply { writeBytes(byteArrayOf(1)) }
        val freshPart = File(directory, "image-456.part").apply { writeBytes(byteArrayOf(2)) }
        val unrelatedPart = File(directory, "notes.part").apply { writeText("keep") }
        val now = 3L * 24 * 60 * 60 * 1_000
        assertTrue(stalePart.setLastModified(now - 30L * 60 * 60 * 1_000))
        assertTrue(freshPart.setLastModified(now))
        assertTrue(unrelatedPart.setLastModified(now - 30L * 60 * 60 * 1_000))
        store.discardExpired(null, now)
        // 30 小时前的 image-123.part 对应 image-123.bin，是进程被杀留下的半份快照：回收。
        assertFalse("过期的 image-*.part 应被清掉", stalePart.exists())
        // 未过期的 part 与「名字不像快照」的 notes.part 都不在回收范围。
        assertTrue("未过期的 part 应保留", freshPart.exists())
        assertTrue("notes.part 不是快照遗留，应保留", unrelatedPart.exists())
    }

    @Test fun partNameIsNotAReadableToken() {
        val directory = temporary.newFolder()
        val store = TextImageExportSnapshot(directory)
        val part = File(directory, "image-123.part").apply { writeBytes(byteArrayOf(5, 6)) }
        // .part 不在 token 语法内：既读不到，也删不到（不会被误当成合法快照）。
        assertNull(store.read("image-123.part"))
        store.discard("image-123.part")
        assertTrue("part 不是 token，discard 不能动它", part.exists())
        // 对照：同名 .bin 才是 token。
        File(directory, "image-123.bin").apply { writeBytes(byteArrayOf(5, 6)) }
        assertArrayEquals(byteArrayOf(5, 6), store.read("image-123.bin"))
    }

    @Test(expected = java.io.IOException::class)
    fun cachePathBeingAFileFailsWithoutChangingThatFile() {
        val occupied = temporary.newFile().apply { writeText("keep") }
        try {
            TextImageExportSnapshot(occupied).stage(payload())
        } finally {
            assertTrue(occupied.readText() == "keep")
        }
    }
}
