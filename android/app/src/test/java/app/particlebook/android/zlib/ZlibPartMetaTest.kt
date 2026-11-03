package app.particlebook.android.zlib

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1c 收尾④（审计 A1）：.part.meta 保存 ETag/Last-Modified，续传带 If-Range；
 * 服务端版本变了会回 200，此时必须截断重下而不是追加。
 */
class ZlibPartMetaTest {

    @Test
    fun serializeParseRoundTrip() {
        val meta = ZlibPartMeta(
            etag = "\"abc123def456\"",
            lastModified = "Wed, 01 Nov 2026 10:20:30 GMT",
            total = 10_441_971L,
            host = "dln1.ncdn.ec",
        )
        assertEquals(meta, ZlibPartMetaStore.parse(ZlibPartMetaStore.serialize(meta)))
    }

    @Test
    fun validatorPrefersEtagThenLastModified() {
        assertEquals(
            "\"abc\"",
            ZlibPartMeta(etag = "\"abc\"", lastModified = "Wed, 01 Nov 2026").validator,
        )
        assertEquals(
            "Wed, 01 Nov 2026",
            ZlibPartMeta(etag = null, lastModified = "Wed, 01 Nov 2026").validator,
        )
        assertNull(ZlibPartMeta(etag = "  ", lastModified = "").validator)
        assertNull(ZlibPartMeta().validator)
    }

    @Test
    fun writeReadDeleteOnDisk() {
        val dir = Files.createTempDirectory("zlib-meta").toFile()
        val part = File(dir, "book.pdf.part")
        part.writeText("partial")
        try {
            ZlibPartMetaStore.write(part, ZlibPartMeta(etag = "\"v1\"", total = 500L, host = "dln1.ncdn.ec"))
            assertTrue(ZlibPartMetaStore.metaFile(part).name.endsWith(".part.meta"))
            assertEquals(ZlibPartMeta(etag = "\"v1\"", total = 500L, host = "dln1.ncdn.ec"), ZlibPartMetaStore.read(part))

            ZlibPartMetaStore.delete(part)
            assertFalse(ZlibPartMetaStore.metaFile(part).exists())
            assertNull(ZlibPartMetaStore.read(part))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun missingOrBrokenMetaReadsAsNull() {
        val dir = Files.createTempDirectory("zlib-meta-bad").toFile()
        val part = File(dir, "book.pdf.part")
        try {
            assertNull("没有 .meta 时返回 null", ZlibPartMetaStore.read(part))
            val metaFile = ZlibPartMetaStore.metaFile(part)
            metaFile.writeText("garbage-without-pairs")
            val parsed = ZlibPartMetaStore.read(part)
            assertNull(parsed?.validator)
            assertEquals(-1L, parsed?.total)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun ifRangeHeaderOnlyWhenResumingWithValidator() {
        assertNull("没有 .part 不带 If-Range", ZlibResumePolicy.ifRangeHeader(0L, "\"v1\""))
        assertNull("没有校验值不带 If-Range", ZlibResumePolicy.ifRangeHeader(500L, null))
        assertNull(ZlibResumePolicy.ifRangeHeader(500L, "  "))
        assertEquals(
            "\"v1\"",
            ZlibResumePolicy.ifRangeHeader(500L, "\"v1\""),
        )
    }

    @Test
    fun ifRangeMismatchReturns200MeansTruncate() {
        // If-Range 不匹配时服务端必须回 200（全量），此时不能追加，要从头写。
        val decision = ZlibResumePolicy.decide(
            partLength = 500L,
            status = 200,
            contentRange = null,
            contentLength = 1_000L,
        )
        assertEquals(ZlibResumePolicy.Decision(append = false, offset = 0L, total = 1_000L), decision)
    }

    @Test
    fun valuesWithSpecialCharactersRoundTrip() {
        val meta = ZlibPartMeta(
            etag = "W/\"hash\\with\\slash\"",
            lastModified = null,
            total = -1L,
            host = null,
        )
        assertEquals(meta, ZlibPartMetaStore.parse(ZlibPartMetaStore.serialize(meta)))
    }
}
