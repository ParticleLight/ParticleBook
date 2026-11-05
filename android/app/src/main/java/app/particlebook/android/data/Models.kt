package app.particlebook.android.data

/**
 * P0-3 数据层行模型。
 *
 * 这里刻意不依赖 `ui/data/LibraryStore.kt` 的 [app.particlebook.android.ui.data.Book]：
 * 迁移期要求旧代码可回退，新数据层不能反过来把旧 UI 模型变成数据契约。
 * 字段覆盖 books.json / pb_reading prefs / 下载历史三份现有磁盘数据，并预留 v1 schema 的常用扩展列。
 */

data class BookRow(
    val id: Long = 0,
    val stableKey: String,
    val legacyId: Long? = null,
    val title: String = "",
    val sortTitle: String = "",
    val author: String = "",
    val format: String = "epub",
    val filePath: String = "",
    val fileName: String = "",
    val fileSize: Long = 0,
    val contentHash: String? = null,
    val fileMtime: Long = 0,
    val fileMissing: Boolean = false,
    val coverPath: String? = null,
    val seriesId: Long? = null,
    val seriesIndex: Double? = null,
    val rating: Int? = null,
    val language: String? = null,
    val publisher: String? = null,
    val publishedAt: String? = null,
    val isbn: String? = null,
    val description: String? = null,
    val source: String = "local",
    val sourceId: String? = null,
    val addedAt: Long = 0,
    val updatedAt: Long = 0,
    val lastOpenedAt: Long? = null,
    val deletedAt: Long? = null,
    val metadataJson: String? = null,
)

data class TagRow(
    val id: Long = 0,
    val name: String,
    val color: String? = null,
    val createdAt: Long = 0,
)

data class BookTagRow(
    val bookId: Long,
    val tagId: Long,
)

data class SeriesRow(
    val id: Long = 0,
    val name: String,
    val sortName: String = "",
    val createdAt: Long = 0,
)

data class ProgressRow(
    val bookId: Long,
    /** 阅读位置 JSON；v1 至少含 {"kind":"pdf","page":N,"total":M}。 */
    val locatorJson: String = "",
    /** PDF 页码，沿用旧 prefs 的 0 基约定；epub 可空。 */
    val page: Int? = null,
    val totalPages: Int? = null,
    val percent: Double? = null,
    val updatedAt: Long = 0,
    val finishedAt: Long? = null,
)

data class AnnotationRow(
    val id: Long = 0,
    val bookId: Long,
    /** bookmark / highlight / note。 */
    val kind: String,
    val locatorJson: String,
    val quote: String = "",
    val note: String = "",
    val color: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val deletedAt: Long? = null,
)

data class DownloadRow(
    val id: Long = 0,
    val bookId: Long? = null,
    val sourceBookId: Long? = null,
    val title: String = "",
    val author: String = "",
    val format: String = "epub",
    val downloadPath: String = "",
    val fileName: String = "",
    /** 最终落盘路径；SAF 时为 content:// URI。 */
    val destPath: String = "",
    val storage: String = "file",
    val size: Long = 0,
    val total: Long = 0,
    val status: String = "completed",
    val errorKind: String? = null,
    val errorMessage: String? = null,
    val finishedAt: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

data class ReadingSessionRow(
    val id: Long = 0,
    val bookId: Long,
    val startedAt: Long,
    val endedAt: Long,
    val durationMs: Long,
    val pagesDelta: Int = 0,
    val startPage: Int? = null,
    val endPage: Int? = null,
)

data class SettingRow(
    val key: String,
    val value: String,
    val updatedAt: Long = 0,
)

data class SearchDocumentRow(
    val id: Long = 0,
    val bookId: Long,
    val docType: String,
    val locator: String,
    val rawText: String,
    /** 应用层 bigram/单词化后的 FTS4 文本，不是原文。 */
    val indexedText: String,
    val updatedAt: Long = 0,
)

data class SearchHit(
    val bookId: Long,
    val docType: String,
    val locator: String,
    val snippet: String,
)

data class SearchDocumentInput(
    val docType: String,
    val locator: String,
    val rawText: String,
)

data class MigrationStateRow(
    val id: Long = 1,
    val status: String,
    val startedAt: Long = 0,
    val finishedAt: Long? = null,
    val detailsJson: String? = null,
)

data class IndexStateRow(
    val key: String,
    val value: String,
    val updatedAt: Long = 0,
)

// ---------------------------------------------------------------------------
// 旧磁盘数据模型（迁移输入，字段与现网 books.json / prefs / 下载历史一一对应）
// ---------------------------------------------------------------------------

data class LegacyBook(
    val id: Long,
    val title: String,
    val author: String,
    val format: String,
    val filePath: String,
    val fileSize: Long,
    val addedAtRaw: String?,
    val tags: List<String>,
)

data class LegacyProgress(
    val bookId: Long,
    val page: Int?,
    val totalPages: Int?,
    val lastReadAt: Long?,
)

data class LegacyDownload(
    val bookId: Long?,
    val title: String,
    val author: String,
    val format: String,
    val downloadPath: String,
    val fileName: String,
    val destPath: String,
    val storage: String,
    val size: Long,
    val total: Long,
    val status: String,
    val errorKind: String?,
    val errorMessage: String?,
    val finishedAt: Long,
)

data class LegacySnapshot(
    val books: List<LegacyBook>,
    val progress: List<LegacyProgress>,
    val downloads: List<LegacyDownload>,
)
