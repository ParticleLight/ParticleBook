package app.particlebook.android.reader.search

/**
 * 一次索引调用的结果。
 *
 * 失败不用返回值表达：扫描/写库异常原样抛给调用方（[EpubSearchIndexing] 只记日志），
 * 这样「失败回滚」的事务语义不会被包装层悄悄吞掉。
 */
sealed interface EpubIndexResult {
    val bookId: Long

    /** 真正重建了索引。 */
    data class Indexed(
        override val bookId: Long,
        val documentCount: Int,
        /** spine 条目数，含被跳过的空章/坏章/重复项。 */
        val spineEntryCount: Int,
        val skippedEmptyChapters: Int,
        val skippedBrokenChapters: Int,
        val duplicateSpineEntries: Int,
        val textHash: String,
        val indexedAt: Long,
        /** 解析器警告（坏章原因等）；只用于日志。 */
        val warnings: List<String> = emptyList(),
    ) : EpubIndexResult

    /** 命中 checkpoint，没有写库。 */
    data class Skipped(
        override val bookId: Long,
        val reason: EpubIndexSkipReason,
        val textHash: String,
        val documentCount: Int,
        val indexedAt: Long,
    ) : EpubIndexResult
}

enum class EpubIndexSkipReason {
    /** 文件 size/mtime 与 checkpoint 一致，连解析都省了。 */
    SOURCE_UNCHANGED,

    /** 解析后文档哈希一致，只刷新文件指纹，没重写文档。 */
    TEXT_UNCHANGED,
}

/** 无法产出可索引文本（例如整本只有图片）——此时不写库，保留旧索引。 */
class EpubIndexException(message: String, cause: Throwable? = null) : Exception(message, cause)
