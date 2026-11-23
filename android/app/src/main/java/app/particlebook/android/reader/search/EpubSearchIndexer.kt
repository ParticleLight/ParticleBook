package app.particlebook.android.reader.search

import app.particlebook.android.data.IndexStateRow
import app.particlebook.android.data.SearchDocumentInput
import app.particlebook.android.data.SearchIndexBatch
import app.particlebook.android.data.SearchIndexStore
import app.particlebook.android.reader.ReaderLocator
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest

/**
 * EPUB → search_documents 的索引器（PB-006）。纯 JVM，不依赖 Compose/Android。
 *
 * 线程与事务：
 * - [index] 是 suspend，但内部是阻塞文件 IO；调用方负责放到 IO dispatcher（见 [EpubSearchIndexing]）；
 * - 抽取与哈希全在内存完成，落库只有 [SearchIndexStore.replaceIndex] 一次原子写：
 *   要么整本提交，要么回滚，搜索侧永远看不到半本书；
 * - 每个章节之间检查协程活跃性，取消发生在写库之前，不会留下部分索引。
 *
 * 幂等（两条快路径，都不写文档行）：
 * 1. 文件 size/mtime 与 checkpoint 一致 → 直接跳过，不解析；
 * 2. 解析后文档集合哈希一致（例如重新保存但内容没变）→ 只刷新指纹。
 *
 * 空结果保护：整本抽不出文本时抛 [EpubIndexException]，不提交空批，
 * 避免把「解析失败」误当成「这本书没内容」而清掉已有索引。
 */
class EpubSearchIndexer(
    private val store: SearchIndexStore,
    private val extractor: EpubTextExtractor = EpubTextExtractor(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun index(bookId: Long, file: File): EpubIndexResult {
        require(bookId > 0L) { "bookId must be positive: " + bookId }
        require(file.isFile) { "epub file not found: " + file.path }

        val sourceSize = file.length()
        val sourceMtime = file.lastModified()
        val stateKey = EpubIndexCheckpoint.key(DOC_TYPE, bookId)
        val stored = store.indexState(stateKey)
            ?.let { EpubIndexCheckpoint.decode(it.value) }
            ?.takeIf { it.algorithm == ALGORITHM_VERSION }

        currentCoroutineContext().ensureActive()
        if (stored != null && stored.matchesSource(sourceSize, sourceMtime)) {
            return EpubIndexResult.Skipped(
                bookId = bookId,
                reason = EpubIndexSkipReason.SOURCE_UNCHANGED,
                textHash = stored.textHash,
                documentCount = stored.documentCount,
                indexedAt = stored.indexedAt,
            )
        }

        val extracted = extractor.extract(file)
        currentCoroutineContext().ensureActive()
        if (extracted.chapters.isEmpty()) {
            throw EpubIndexException(
                "no indexable text in " + file.name + " (book " + bookId + "); existing index kept",
            )
        }

        val documents = extracted.chapters.map { chapter ->
            SearchDocumentInput(
                docType = DOC_TYPE,
                // block 位指向该章第一个有文本的 block，UI 可据此跳转；charOffset 留给命中词细化。
                locator = ReaderLocator.Epub(
                    chapterHref = chapter.chapterHref,
                    blockIndex = chapter.firstTextBlockIndex,
                    charOffset = 0,
                ).encode(),
                rawText = chapter.text,
            )
        }
        val textHash = documentHash(documents)

        if (stored != null && stored.textHash == textHash) {
            val refreshed = stored.copy(sourceSize = sourceSize, sourceMtime = sourceMtime)
            store.putIndexState(listOf(IndexStateRow(stateKey, refreshed.encode(), clock())))
            return EpubIndexResult.Skipped(
                bookId = bookId,
                reason = EpubIndexSkipReason.TEXT_UNCHANGED,
                textHash = refreshed.textHash,
                documentCount = refreshed.documentCount,
                indexedAt = refreshed.indexedAt,
            )
        }

        val indexedAt = clock()
        val checkpoint = EpubIndexCheckpoint(
            algorithm = ALGORITHM_VERSION,
            textHash = textHash,
            indexedAt = indexedAt,
            spineChapterCount = extracted.spineEntryCount,
            documentCount = documents.size,
            sourceSize = sourceSize,
            sourceMtime = sourceMtime,
        )
        // 单事务：删旧 + 插新 + checkpoint。异常在这里抛出时旧索引保持原样。
        store.replaceIndex(
            SearchIndexBatch(
                bookId = bookId,
                docType = DOC_TYPE,
                documents = documents,
                state = listOf(IndexStateRow(stateKey, checkpoint.encode(), indexedAt)),
            ),
        )
        return EpubIndexResult.Indexed(
            bookId = bookId,
            documentCount = documents.size,
            spineEntryCount = extracted.spineEntryCount,
            skippedEmptyChapters = extracted.emptyChapterCount,
            skippedBrokenChapters = extracted.brokenChapterCount,
            duplicateSpineEntries = extracted.duplicateSpineEntryCount,
            textHash = textHash,
            indexedAt = indexedAt,
            warnings = extracted.warnings,
        )
    }

    /**
     * 文档集合哈希：对每条文档的 docType/locator/rawText 做长度前缀 frame 后喂 SHA-256。
     *
     * 长度前缀避免「拼接歧义」——否则 (locator="ab", text="c") 和 (locator="a", text="bc")
     * 会算出同一个哈希，导致改章节目录后错误跳过重建。前缀里带算法版本，
     * 抽取规则变化时即使文本相同也会重建。
     */
    private fun documentHash(documents: List<SearchDocumentInput>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.updateFrame("epub-search-documents-v" + ALGORITHM_VERSION)
        for (document in documents) {
            digest.updateFrame(document.docType)
            digest.updateFrame(document.locator)
            digest.updateFrame(document.rawText)
        }
        return digest.digest().toHex()
    }

    companion object {
        /** search_documents.doc_type；搜索侧按它过滤「书内正文」命中。 */
        const val DOC_TYPE = "epub"

        /** 抽取/分词/locator 规则版本；改动这些规则时 +1，强制全书重建。 */
        const val ALGORITHM_VERSION = 1
    }
}

private fun MessageDigest.updateFrame(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    val length = bytes.size
    update(
        byteArrayOf(
            (length ushr 24).toByte(),
            (length ushr 16).toByte(),
            (length ushr 8).toByte(),
            length.toByte(),
        ),
    )
    update(bytes)
}

private const val HEX_DIGITS = "0123456789abcdef"

private fun ByteArray.toHex(): String = buildString(size * 2) {
    for (byte in this@toHex) {
        val value = byte.toInt() and 0xFF
        append(HEX_DIGITS[value ushr 4])
        append(HEX_DIGITS[value and 0x0F])
    }
}
