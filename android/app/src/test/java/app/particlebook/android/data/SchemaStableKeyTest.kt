package app.particlebook.android.data

import app.particlebook.android.ui.data.TestJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaStableKeyTest {

    @Test
    fun schemaCreatesEveryRequiredTableAndIndex() {
        val sql = LibrarySchema.allStatements.joinToString("\n")
        LibrarySchema.requiredTables.forEach { table ->
            assertTrue("缺少建表语句: " + table, sql.contains("CREATE TABLE IF NOT EXISTS " + table) ||
                sql.contains("CREATE VIRTUAL TABLE IF NOT EXISTS " + table))
            assertTrue("requiredTables 与 DDL 不一致: " + table, LibrarySchema.allStatements.any { it.contains(table) })
        }
        assertTrue(sql.contains("CREATE INDEX IF NOT EXISTS idx_books_added_at"))
        assertTrue(sql.contains("CREATE INDEX IF NOT EXISTS idx_downloads_finished"))
        assertTrue(sql.contains("CREATE INDEX IF NOT EXISTS idx_search_docs_book"))
        assertFalse("v1 不允许破坏性 DROP", sql.contains("DROP TABLE"))
    }

    @Test
    fun ftsIsExternalContentWithBigramTextAndSyncTriggers() {
        val sql = LibrarySchema.CREATE_FTS
        assertTrue(sql.contains("USING fts4"))
        assertTrue(sql.contains("content='search_documents'"))
        assertFalse("FTS4 没有 content_rowid 参数（FTS5 才有）", sql.contains("content_rowid"))
        assertTrue(sql.contains("tokenize=unicode61"))
        assertTrue(LibrarySchema.CREATE_TRIGGER_SEARCH_AI.contains("AFTER INSERT ON search_documents"))
        assertTrue(LibrarySchema.CREATE_TRIGGER_SEARCH_AD.contains("BEFORE DELETE ON search_documents"))
        assertTrue(LibrarySchema.CREATE_TRIGGER_SEARCH_AU.contains("BEFORE UPDATE ON search_documents"))
        assertTrue(LibrarySchema.CREATE_TRIGGER_SEARCH_AD.contains("DELETE FROM search_fts WHERE rowid = old.id"))
    }

    @Test
    fun foreignKeysCascadeOrNullOnBookDelete() {
        listOf(
            LibrarySchema.CREATE_BOOK_TAGS,
            LibrarySchema.CREATE_PROGRESS,
            LibrarySchema.CREATE_ANNOTATIONS,
            LibrarySchema.CREATE_READING_SESSIONS,
            LibrarySchema.CREATE_SEARCH_DOCUMENTS,
        ).forEach { ddl ->
            assertTrue("缺少 ON DELETE CASCADE: " + ddl.take(120), ddl.contains("ON DELETE CASCADE"))
        }
        assertTrue(LibrarySchema.CREATE_DOWNLOADS.contains("ON DELETE SET NULL"))
        assertTrue(LibrarySchema.pragmaStatements.any { it.contains("foreign_keys = ON") })
        assertTrue(LibrarySchema.pragmaStatements.any { it.contains("busy_timeout") })
    }

    @Test
    fun formatIsNotLockedByCheckConstraintButApplicationLayerIsCanonical() {
        assertFalse(
            "PB-008 新增格式不应要求迁移，format 不锁 CHECK",
            LibrarySchema.CREATE_BOOKS.contains("CHECK (format IN"),
        )
        val formats = listOf("epub", "PDF", "txt", "text/html", "markdown", "fb2", "AZW3", "docx", "cbz")
        assertEquals(
            listOf("epub", "pdf", "txt", "html", "md", "fb2", "azw3", "docx", "cbz"),
            formats.map(StableKey::normalizeFormat),
        )
        assertEquals("epub", StableKey.normalizeFormat("application/epub+zip"))
        assertEquals("pdf", StableKey.normalizeFormat("application/pdf"))
        assertEquals("未知格式保留原值", "mobi", StableKey.normalizeFormat("MOBI"))
        assertEquals("空格式不冒充 epub", "unknown", StableKey.normalizeFormat("  "))
        assertEquals(9, StableKey.SUPPORTED_FORMATS.size)
    }

    @Test
    fun stableKeyPriorityMatchesMigrationContract() {
        val sha = "a".repeat(64)
        assertEquals("zlib:42", StableKey.stableKeyFor(7, listOf("zlib:42", "saf-uri:" + sha, "fp:1-" + sha)))
        assertEquals("sha256:" + sha, StableKey.stableKeyFor(7, listOf("tag", "saf-uri:" + "b".repeat(64)), sha))
        assertEquals("saf-uri:" + sha, StableKey.stableKeyFor(7, listOf("saf-uri:" + sha, "fp:1-" + "c".repeat(64))))
        assertEquals("fp:1-" + sha, StableKey.stableKeyFor(7, listOf("fp:1-" + sha, "tag")))
        assertEquals("legacy:7", StableKey.stableKeyFor(7, listOf("tag")))
        assertEquals(42L, StableKey.zlibId(listOf("zlib:42")))
        assertEquals(null, StableKey.zlibId(listOf("zlib:abc")))
        assertEquals(listOf("tag", "读书"), StableKey.userTags(listOf("zlib:1", "tag", "saf-uri:x", "读书", "missing")))
        assertEquals(sha, StableKey.contentHashFromTags(listOf("fp:123-" + sha)))
        assertFalse(StableKey.isSystemTag("tag"))
        assertTrue(StableKey.isSystemTag("recovered"))
    }

    @Test
    fun epochAndJsonHelpersHandleLegacyShapes() {
        assertEquals(0L, StableKey.parseEpochMillis(null))
        assertEquals(1_700_000_000_000L, StableKey.parseEpochMillis("1700000000000"))
        assertEquals(1_704_067_200_000L, StableKey.parseEpochMillis("2024-01-01T00:00:00Z"))
        assertEquals(1_704_067_200_000L, StableKey.parseEpochMillis("2024-01-01T08:00:00+08:00"))
        assertEquals(0L, StableKey.parseEpochMillis("not-a-date"))
        assertEquals("book.epub", StableKey.fileNameFromPath("content://docs/1/book.epub?x=1"))
        val text = "a\"b\\c\n中文"
        val json = "{\"x\":" + StableKey.jsonString(text) + "}"
        assertEquals(text, TestJson.asObject(TestJson.parse(json), "json")["x"])
    }
}
