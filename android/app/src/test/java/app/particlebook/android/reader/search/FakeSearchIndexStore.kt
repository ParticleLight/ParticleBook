package app.particlebook.android.reader.search

import app.particlebook.android.data.IndexStateRow
import app.particlebook.android.data.SearchDocumentRow
import app.particlebook.android.data.SearchIndexBatch
import app.particlebook.android.data.SearchIndexStore
import app.particlebook.android.data.SearchTokenizer

/**
 * JVM 版 [SearchIndexStore]：模拟 SQLite 实现的可观察契约。
 *
 * 关键点：replaceIndex 先 staging 全部文档，成功后才替换旧行——和真实实现的事务回滚
 * 在外部看来完全一致。这样「中途失败不留半截」可以在 host 上被真正验证，
 * 而不是只测一个 mock 调用次数。
 */
internal class FakeSearchIndexStore : SearchIndexStore {

    private val states = LinkedHashMap<String, IndexStateRow>()
    private val documents = LinkedHashMap<String, SearchDocumentRow>()
    private var nextId = 1L

    var replaceCount = 0
        private set

    var stateWriteCount = 0
        private set

    var indexStateReadCount = 0
        private set

    /** 第 N 条文档开始抛错，模拟「删旧之后、插新途中」的中断；null 表示不失败。 */
    var failReplaceAfterDocuments: Int? = null

    override fun indexState(key: String): IndexStateRow? {
        indexStateReadCount++
        return states[key]
    }

    override fun putIndexState(rows: List<IndexStateRow>) {
        stateWriteCount++
        rows.forEach { states[it.key] = it }
    }

    override fun replaceIndex(batch: SearchIndexBatch) {
        replaceCount++
        val staged = ArrayList<SearchDocumentRow>(batch.documents.size)
        batch.documents.forEachIndexed { index, document ->
            val limit = failReplaceAfterDocuments
            if (limit != null && index >= limit) {
                throw IllegalStateException("simulated write failure at document " + index)
            }
            staged += SearchDocumentRow(
                id = nextId++,
                bookId = batch.bookId,
                docType = document.docType,
                locator = document.locator,
                rawText = document.rawText,
                indexedText = SearchTokenizer.indexText(document.rawText),
                updatedAt = index.toLong(),
            )
        }
        val prefix = prefix(batch.bookId, batch.docType)
        documents.keys.filter { it.startsWith(prefix) }.forEach { documents.remove(it) }
        staged.forEach { row ->
            documents[batch.bookId.toString() + "|" + row.docType + "|" + row.locator] = row
        }
        batch.state.forEach { states[it.key] = it }
    }

    fun rows(bookId: Long, docType: String = "epub"): List<SearchDocumentRow> =
        documents.values
            .filter { it.bookId == bookId && it.docType == docType }
            .sortedBy { it.locator }

    fun snapshot(bookId: Long, docType: String = "epub"): String =
        rows(bookId, docType).joinToString("\n") { row ->
            row.locator + "\u0000" + row.rawText + "\u0000" + row.indexedText
        }

    fun stateValue(key: String): String? = states[key]?.value

    fun stateUpdatedAt(key: String): Long? = states[key]?.updatedAt

    private fun prefix(bookId: Long, docType: String): String = bookId.toString() + "|" + docType + "|"
}
