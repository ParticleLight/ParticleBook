package app.particlebook.android.data

/**
 * schema v1（framework SQLite，minSdk 26 即可用）。
 *
 * 设计约束：
 * - 数字 id 是外部持久引用（旧 books.json id / SharedPreferences 键 / 下载历史），迁移时原样保留；
 * - stable_key 仅用于跨来源认领，唯一索引兜底重复导入；
 * - FTS4 external-content：search_documents.text 存的是应用层 bigram/单词化文本，
 *   search_fts 只建索引不复制正文；raw_text 保留原文供高亮/片段；
 * - WAL / foreign_keys / busy_timeout 在 LibraryDatabase 打开连接时设置。
 */
object LibrarySchema {
    const val DATABASE_NAME = "particlebook.db"
    const val VERSION = 1
    const val BUSY_TIMEOUT_MS = 5000

    val pragmaStatements = listOf(
        "PRAGMA foreign_keys = ON",
        "PRAGMA busy_timeout = " + BUSY_TIMEOUT_MS,
    )

    const val TABLE_SERIES = "series"
    const val TABLE_TAGS = "tags"
    const val TABLE_BOOKS = "books"
    const val TABLE_BOOK_TAGS = "book_tags"
    const val TABLE_PROGRESS = "progress"
    const val TABLE_ANNOTATIONS = "annotations"
    const val TABLE_DOWNLOADS = "downloads"
    const val TABLE_READING_SESSIONS = "reading_sessions"
    const val TABLE_SETTINGS = "settings"
    const val TABLE_SEARCH_DOCUMENTS = "search_documents"
    const val TABLE_SEARCH_FTS = "search_fts"
    const val TABLE_INDEX_STATE = "index_state"
    const val TABLE_MIGRATION_STATE = "migration_state"

    const val CREATE_SERIES = """
        CREATE TABLE IF NOT EXISTS series (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL UNIQUE,
            sort_name TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL DEFAULT 0
        )
    """

    const val CREATE_TAGS = """
        CREATE TABLE IF NOT EXISTS tags (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL UNIQUE,
            color TEXT,
            created_at INTEGER NOT NULL DEFAULT 0
        )
    """

    const val CREATE_BOOKS = """
        CREATE TABLE IF NOT EXISTS books (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            stable_key TEXT NOT NULL UNIQUE,
            legacy_id INTEGER,
            title TEXT NOT NULL DEFAULT '',
            sort_title TEXT NOT NULL DEFAULT '',
            author TEXT NOT NULL DEFAULT '',
            -- 格式由应用层 StableKey.normalizeFormat 规范；不在 CHECK 里锁死，PB-008 新增格式无需迁移。
            format TEXT NOT NULL DEFAULT 'epub',
            file_path TEXT,
            file_name TEXT NOT NULL DEFAULT '',
            file_size INTEGER NOT NULL DEFAULT 0,
            content_hash TEXT,
            file_mtime INTEGER NOT NULL DEFAULT 0,
            file_missing INTEGER NOT NULL DEFAULT 0 CHECK (file_missing IN (0, 1)),
            cover_path TEXT,
            series_id INTEGER REFERENCES series(id) ON DELETE SET NULL,
            series_index REAL,
            rating INTEGER,
            language TEXT,
            publisher TEXT,
            published_at TEXT,
            isbn TEXT,
            description TEXT,
            source TEXT NOT NULL DEFAULT 'local',
            source_id TEXT,
            added_at INTEGER NOT NULL DEFAULT 0,
            updated_at INTEGER NOT NULL DEFAULT 0,
            last_opened_at INTEGER,
            deleted_at INTEGER,
            metadata_json TEXT
        )
    """

    const val CREATE_BOOK_TAGS = """
        CREATE TABLE IF NOT EXISTS book_tags (
            book_id INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE,
            tag_id INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
            PRIMARY KEY (book_id, tag_id)
        )
    """

    const val CREATE_PROGRESS = """
        CREATE TABLE IF NOT EXISTS progress (
            book_id INTEGER PRIMARY KEY REFERENCES books(id) ON DELETE CASCADE,
            locator_json TEXT NOT NULL DEFAULT '',
            page INTEGER,
            total_pages INTEGER,
            percent REAL,
            updated_at INTEGER NOT NULL DEFAULT 0,
            finished_at INTEGER
        )
    """

    const val CREATE_ANNOTATIONS = """
        CREATE TABLE IF NOT EXISTS annotations (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            book_id INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE,
            kind TEXT NOT NULL CHECK (kind IN ('bookmark', 'highlight', 'note')),
            locator_json TEXT NOT NULL DEFAULT '',
            quote TEXT NOT NULL DEFAULT '',
            note TEXT NOT NULL DEFAULT '',
            color TEXT,
            created_at INTEGER NOT NULL DEFAULT 0,
            updated_at INTEGER NOT NULL DEFAULT 0,
            deleted_at INTEGER
        )
    """

    const val CREATE_DOWNLOADS = """
        CREATE TABLE IF NOT EXISTS downloads (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            book_id INTEGER REFERENCES books(id) ON DELETE SET NULL,
            source_book_id INTEGER,
            title TEXT NOT NULL DEFAULT '',
            author TEXT NOT NULL DEFAULT '',
            format TEXT NOT NULL DEFAULT 'epub',
            download_path TEXT NOT NULL DEFAULT '',
            file_name TEXT NOT NULL DEFAULT '',
            dest_path TEXT NOT NULL DEFAULT '',
            storage TEXT NOT NULL DEFAULT 'file',
            size INTEGER NOT NULL DEFAULT 0,
            total INTEGER NOT NULL DEFAULT 0,
            status TEXT NOT NULL DEFAULT 'completed',
            error_kind TEXT,
            error_message TEXT,
            finished_at INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL DEFAULT 0,
            updated_at INTEGER NOT NULL DEFAULT 0,
            UNIQUE (source_book_id, dest_path)
        )
    """

    const val CREATE_READING_SESSIONS = """
        CREATE TABLE IF NOT EXISTS reading_sessions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            book_id INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE,
            started_at INTEGER NOT NULL,
            ended_at INTEGER NOT NULL,
            duration_ms INTEGER NOT NULL,
            pages_delta INTEGER NOT NULL DEFAULT 0,
            start_page INTEGER,
            end_page INTEGER
        )
    """

    const val CREATE_SETTINGS = """
        CREATE TABLE IF NOT EXISTS settings (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL,
            updated_at INTEGER NOT NULL DEFAULT 0
        )
    """

    const val CREATE_SEARCH_DOCUMENTS = """
        CREATE TABLE IF NOT EXISTS search_documents (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            book_id INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE,
            doc_type TEXT NOT NULL,
            locator TEXT NOT NULL DEFAULT '',
            raw_text TEXT NOT NULL DEFAULT '',
            text TEXT NOT NULL DEFAULT '',
            updated_at INTEGER NOT NULL DEFAULT 0,
            UNIQUE (book_id, doc_type, locator)
        )
    """

    const val CREATE_FTS = """
        CREATE VIRTUAL TABLE IF NOT EXISTS search_fts USING fts4(
            text,
            content='search_documents',
            tokenize=unicode61
        )
    """

    const val CREATE_INDEX_STATE = """
        CREATE TABLE IF NOT EXISTS index_state (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL,
            updated_at INTEGER NOT NULL DEFAULT 0
        )
    """

    const val CREATE_MIGRATION_STATE = """
        CREATE TABLE IF NOT EXISTS migration_state (
            id INTEGER PRIMARY KEY CHECK (id = 1),
            status TEXT NOT NULL DEFAULT 'idle',
            started_at INTEGER NOT NULL DEFAULT 0,
            finished_at INTEGER,
            details_json TEXT
        )
    """

    const val CREATE_TRIGGER_SEARCH_AI = """
        CREATE TRIGGER IF NOT EXISTS search_documents_ai
        AFTER INSERT ON search_documents
        BEGIN
            INSERT INTO search_fts(rowid, text) VALUES (new.id, new.text);
        END
    """

    const val CREATE_TRIGGER_SEARCH_AD = """
        CREATE TRIGGER IF NOT EXISTS search_documents_ad
        BEFORE DELETE ON search_documents
        BEGIN
            DELETE FROM search_fts WHERE rowid = old.id;
        END
    """

    const val CREATE_TRIGGER_SEARCH_AU = """
        CREATE TRIGGER IF NOT EXISTS search_documents_au
        BEFORE UPDATE ON search_documents
        BEGIN
            DELETE FROM search_fts WHERE rowid = old.id;
            INSERT INTO search_fts(rowid, text) VALUES (new.id, new.text);
        END
    """

    const val CREATE_INDEX_BOOKS_ADDED = "CREATE INDEX IF NOT EXISTS idx_books_added_at ON books(added_at DESC, id DESC)"
    const val CREATE_INDEX_BOOKS_TITLE = "CREATE INDEX IF NOT EXISTS idx_books_title ON books(title)"
    const val CREATE_INDEX_BOOKS_AUTHOR = "CREATE INDEX IF NOT EXISTS idx_books_author ON books(author)"
    const val CREATE_INDEX_BOOKS_LEGACY = "CREATE INDEX IF NOT EXISTS idx_books_legacy_id ON books(legacy_id)"
    const val CREATE_INDEX_PROGRESS_UPDATED = "CREATE INDEX IF NOT EXISTS idx_progress_updated ON progress(updated_at DESC)"
    const val CREATE_INDEX_ANNOTATIONS_BOOK = "CREATE INDEX IF NOT EXISTS idx_annotations_book ON annotations(book_id, created_at DESC)"
    const val CREATE_INDEX_DOWNLOADS_FINISHED = "CREATE INDEX IF NOT EXISTS idx_downloads_finished ON downloads(finished_at DESC)"
    const val CREATE_INDEX_SESSIONS_BOOK = "CREATE INDEX IF NOT EXISTS idx_sessions_book ON reading_sessions(book_id, started_at DESC)"
    const val CREATE_INDEX_SEARCH_DOCS_BOOK = "CREATE INDEX IF NOT EXISTS idx_search_docs_book ON search_documents(book_id)"

    /** 建库顺序即依赖顺序；外部内容表与触发器必须在 search_documents 之后。 */
    val allStatements: List<String> = listOf(
        CREATE_SERIES,
        CREATE_TAGS,
        CREATE_BOOKS,
        CREATE_BOOK_TAGS,
        CREATE_PROGRESS,
        CREATE_ANNOTATIONS,
        CREATE_DOWNLOADS,
        CREATE_READING_SESSIONS,
        CREATE_SETTINGS,
        CREATE_SEARCH_DOCUMENTS,
        CREATE_INDEX_STATE,
        CREATE_MIGRATION_STATE,
        CREATE_FTS,
        CREATE_TRIGGER_SEARCH_AI,
        CREATE_TRIGGER_SEARCH_AD,
        CREATE_TRIGGER_SEARCH_AU,
        CREATE_INDEX_BOOKS_ADDED,
        CREATE_INDEX_BOOKS_TITLE,
        CREATE_INDEX_BOOKS_AUTHOR,
        CREATE_INDEX_BOOKS_LEGACY,
        CREATE_INDEX_PROGRESS_UPDATED,
        CREATE_INDEX_ANNOTATIONS_BOOK,
        CREATE_INDEX_DOWNLOADS_FINISHED,
        CREATE_INDEX_SESSIONS_BOOK,
        CREATE_INDEX_SEARCH_DOCS_BOOK,
    )

    val requiredTables: List<String> = listOf(
        TABLE_SERIES,
        TABLE_TAGS,
        TABLE_BOOKS,
        TABLE_BOOK_TAGS,
        TABLE_PROGRESS,
        TABLE_ANNOTATIONS,
        TABLE_DOWNLOADS,
        TABLE_READING_SESSIONS,
        TABLE_SETTINGS,
        TABLE_SEARCH_DOCUMENTS,
        TABLE_SEARCH_FTS,
        TABLE_INDEX_STATE,
        TABLE_MIGRATION_STATE,
    )
}
