package app.particlebook.android.reader.search

import app.particlebook.android.data.SearchIndexKeys

/**
 * 书级索引 checkpoint，存在 index_state 的 value 里。
 *
 * 为什么需要它：[SearchDocumentInput] 只存文本，无法回答「这本书现在的索引是不是还对应磁盘上这个文件」。
 * checkpoint 同时记两件独立的事：
 * - [sourceSize] / [sourceMtime]：文件指纹，命中就完全跳过解析（最便宜的路径）；
 * - [textHash]：文档集合哈希，文件被重新保存但内容没变时，跳过写库、只刷新指纹。
 *
 * 格式是分号分隔的 key=value，带版本前缀。解码一律容错：坏值当作没有 checkpoint 重建，
 * 宁可多索引一次，也不要信任一个半截/异版本的记录。
 */
internal data class EpubIndexCheckpoint(
    /** 抽取 + 分词 + locator 规则的整体版本；变化即强制全书重建。 */
    val algorithm: Int,
    /** 文档集合 SHA-256（小写 hex）。 */
    val textHash: String,
    val indexedAt: Long,
    /** spine 条目数（诊断用，不参与判等）。 */
    val spineChapterCount: Int,
    /** 写入的文档行数（诊断用，不参与判等）。 */
    val documentCount: Int,
    val sourceSize: Long,
    val sourceMtime: Long,
) {

    /** 文件 stat 是否与 checkpoint 一致；mtime<=0（文件系统不提供）时永不认为未变，宁可重建。 */
    fun matchesSource(size: Long, mtime: Long): Boolean =
        mtime > 0L && sourceMtime == mtime && sourceSize == size

    fun encode(): String = buildString {
        append(VERSION)
        append(";algorithm=").append(algorithm)
        append(";hash=").append(textHash)
        append(";indexed_at=").append(indexedAt)
        append(";spine=").append(spineChapterCount)
        append(";docs=").append(documentCount)
        append(";size=").append(sourceSize)
        append(";mtime=").append(sourceMtime)
    }

    companion object {
        const val VERSION = "v1"
        private const val HASH_LENGTH = 64

        fun key(docType: String, bookId: Long): String = SearchIndexKeys.bookCheckpoint(docType, bookId)

        fun decode(raw: String?): EpubIndexCheckpoint? {
            val value = raw?.trim().orEmpty()
            val prefix = VERSION + ";"
            if (!value.startsWith(prefix)) return null
            val fields = HashMap<String, String>()
            for (part in value.substring(prefix.length).split(';')) {
                if (part.isEmpty()) continue
                val separator = part.indexOf('=')
                if (separator <= 0 || separator == part.lastIndex) return null
                fields[part.substring(0, separator)] = part.substring(separator + 1)
            }
            val algorithm = fields["algorithm"]?.toIntOrNull() ?: return null
            val hash = fields["hash"]?.takeIf(::isSha256Hex) ?: return null
            val indexedAt = fields["indexed_at"]?.toLongOrNull() ?: return null
            val spine = fields["spine"]?.toIntOrNull() ?: return null
            val documents = fields["docs"]?.toIntOrNull() ?: return null
            val size = fields["size"]?.toLongOrNull() ?: return null
            val mtime = fields["mtime"]?.toLongOrNull() ?: return null
            if (algorithm < 1 || indexedAt < 0 || spine < 0 || documents < 0 || size < 0) return null
            return EpubIndexCheckpoint(
                algorithm = algorithm,
                textHash = hash,
                indexedAt = indexedAt,
                spineChapterCount = spine,
                documentCount = documents,
                sourceSize = size,
                sourceMtime = mtime,
            )
        }

        private fun isSha256Hex(value: String): Boolean =
            value.length == HASH_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }
    }
}
