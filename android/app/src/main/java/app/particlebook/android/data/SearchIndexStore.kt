package app.particlebook.android.data

/**
 * 跨书全文索引的写入端口（PB-006）。
 *
 * 为什么单独定义窄接口：索引器在 reader/search，只需要「一次原子写 + 一条书级 checkpoint」；
 * 它不该依赖整套 [LibraryRepository]（书架/标注/进度），也不该知道 SQLite 细节。
 * 生产实现是 [SqliteLibraryRepository]（复用同一个连接），JVM 单测用内存实现验证原子性契约。
 */
data class SearchIndexBatch(
    val bookId: Long,
    val docType: String,
    /** 本书该 docType 的完整文档集合；调用方决定「空集合」是否允许提交。 */
    val documents: List<SearchDocumentInput>,
    /** 与 [documents] 同一事务写入的 index_state 行（书级 checkpoint）。 */
    val state: List<IndexStateRow> = emptyList(),
)

interface SearchIndexStore {
    /** 读一条 index_state；不存在返回 null。 */
    fun indexState(key: String): IndexStateRow?

    /** 只写 index_state（文本未变、只刷新 source fingerprint 时用）；单事务。 */
    fun putIndexState(rows: List<IndexStateRow>)

    /**
     * 全书重建，all-or-nothing：
     * 删掉 (bookId, docType) 的旧文档 → 逐条经 [SearchTokenizer.indexText] 预处理后插入 → 写
     * [SearchIndexBatch.state]。任一步抛错整批回滚，搜索侧只会看到旧索引或新索引，不会看到半本书。
     *
     * FTS4 external-content 的增删由 schema 触发器维护，这里只写 search_documents。
     */
    fun replaceIndex(batch: SearchIndexBatch)
}

/**
 * index_state 的键约定。
 *
 * 表本身是通用 key/value（schema v1），键格式集中在这里：书级 checkpoint 形如
 * `index:epub:42`；删书时用同一格式的 LIKE 模式清理，避免遗留让别的书命中的旧 checkpoint。
 */
object SearchIndexKeys {

    /** 书级 checkpoint 主键：`index:<docType>:<bookId>`。 */
    fun bookCheckpoint(docType: String, bookId: Long): String = "index:" + docType + ":" + bookId

    /** [bookCheckpoint] 对应的 LIKE 模式；bookId 为数字，docType 含 `_` 时也不会被误当通配符。 */
    fun bookCheckpointPattern(bookId: Long): String = "index:%:" + bookId
}
