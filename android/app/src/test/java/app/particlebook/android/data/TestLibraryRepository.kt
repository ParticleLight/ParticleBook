package app.particlebook.android.data

/**
 * JVM 单测用的内存 repository。
 *
 * 不是 SQLite 的替代品：它验证「同一套行模型 + 同一套级联/事务语义」在数据层边界上的行为；
 * 真实 SQLite 的 DDL/触发器/外键/回滚由设备 p15 复测，见 docs/android-port/p0/sqlite.md。
 */
internal class TestLibraryRepository : LibraryRepository {

    private val bookMap = LinkedHashMap<Long, BookRow>()
    private val tagMap = LinkedHashMap<Long, MutableSet<String>>()
    private val progressMap = LinkedHashMap<Long, ProgressRow>()
    private val annotationMap = LinkedHashMap<Long, AnnotationRow>()
    private val downloadMap = LinkedHashMap<Long, DownloadRow>()
    private val searchMap = LinkedHashMap<String, Pair<Long, SearchDocumentRow>>()
    private var nextBookId = 1L
    private var nextAnnotationId = 1L
    private var nextDownloadId = 1L

    override fun bookCount(): Int = bookMap.size

    override fun bookById(id: Long): BookRow? = bookMap[id]

    override fun books(): List<BookRow> = bookMap.values.toList()

    override fun saveBook(book: BookRow): Long {
        val existing = when {
            book.id > 0 -> bookMap[book.id]
            else -> bookMap.values.firstOrNull { it.stableKey == book.stableKey }
        }
        val id = existing?.id ?: if (book.id > 0) book.id else nextBookId++
        nextBookId = maxOf(nextBookId, id + 1)
        bookMap[id] = book.copy(id = id)
        return id
    }

    override fun deleteBook(bookId: Long): Boolean {
        if (bookMap.remove(bookId) == null) return false
        tagMap.remove(bookId)
        progressMap.remove(bookId)
        annotationMap.entries.removeAll { it.value.bookId == bookId }
        downloadMap.replaceAll { _, row -> if (row.bookId == bookId) row.copy(bookId = null) else row }
        searchMap.entries.removeAll { it.value.first == bookId }
        return true
    }

    override fun tagsForBook(bookId: Long): List<String> = tagMap[bookId].orEmpty().sorted()

    override fun addTag(bookId: Long, tagName: String) {
        require(bookMap.containsKey(bookId)) { "book 不存在: " + bookId }
        val name = tagName.trim()
        if (name.isEmpty()) return
        tagMap.getOrPut(bookId) { linkedSetOf() } += name
    }

    override fun progress(bookId: Long): ProgressRow? = progressMap[bookId]

    override fun saveProgress(row: ProgressRow) {
        require(bookMap.containsKey(row.bookId)) { "book 不存在: " + row.bookId }
        progressMap[row.bookId] = row
    }

    override fun annotations(bookId: Long): List<AnnotationRow> =
        annotationMap.values.filter { it.bookId == bookId && it.deletedAt == null }
            .sortedByDescending { it.createdAt }

    override fun addAnnotation(row: AnnotationRow): Long {
        require(bookMap.containsKey(row.bookId)) { "book 不存在: " + row.bookId }
        val id = if (row.id > 0) row.id else nextAnnotationId++
        nextAnnotationId = maxOf(nextAnnotationId, id + 1)
        annotationMap[id] = row.copy(id = id)
        return id
    }

    override fun downloads(): List<DownloadRow> = downloadMap.values.toList()

    override fun addDownload(row: DownloadRow): Long {
        val id = if (row.id > 0) row.id else nextDownloadId++
        nextDownloadId = maxOf(nextDownloadId, id + 1)
        downloadMap[id] = row.copy(id = id)
        return id
    }

    override fun saveSearchDocument(bookId: Long, docType: String, locator: String, rawText: String) {
        require(bookMap.containsKey(bookId)) { "book 不存在: " + bookId }
        val key = bookId.toString() + "|" + docType + "|" + locator
        val id = searchMap[key]?.second?.id ?: (searchMap.size + 1L)
        searchMap[key] = bookId to SearchDocumentRow(
            id = id,
            bookId = bookId,
            docType = docType,
            locator = locator,
            rawText = rawText,
            indexedText = SearchTokenizer.indexText(rawText),
        )
    }

    override fun search(query: String, limit: Int): List<SearchHit> = searchMap.values
        .asSequence()
        .filter { (_, document) -> TestFts.matches(document.indexedText, query) }
        .take(limit)
        .map { (_, document) ->
            SearchHit(document.bookId, document.docType, document.locator, document.rawText.take(160))
        }
        .toList()

    override fun <T> transaction(block: (LibraryRepository) -> T): T {
        val bookSnapshot = LinkedHashMap(bookMap)
        val tagSnapshot = tagMap.mapValues { (_, value) -> LinkedHashSet(value) }.toMutableMap()
        val progressSnapshot = LinkedHashMap(progressMap)
        val annotationSnapshot = LinkedHashMap(annotationMap)
        val downloadSnapshot = LinkedHashMap(downloadMap)
        val searchSnapshot = LinkedHashMap(searchMap)
        return try {
            block(this)
        } catch (error: Throwable) {
            bookMap.clear(); bookMap.putAll(bookSnapshot)
            tagMap.clear(); tagMap.putAll(tagSnapshot)
            progressMap.clear(); progressMap.putAll(progressSnapshot)
            annotationMap.clear(); annotationMap.putAll(annotationSnapshot)
            downloadMap.clear(); downloadMap.putAll(downloadSnapshot)
            searchMap.clear(); searchMap.putAll(searchSnapshot)
            throw error
        }
    }

    override fun close() = Unit
}

/** 单测内的最小 FTS4 语义：空格 token + 连续 phrase 匹配，与生产 MATCH 串形状一致。 */
internal object TestFts {
    fun matches(indexedText: String, query: String): Boolean {
        val index = indexedText.split(' ').filter { it.isNotEmpty() }
        val tokens = SearchTokenizer.queryTokens(query)
        if (tokens.isEmpty() || tokens.size > index.size) return false
        for (start in 0..(index.size - tokens.size)) {
            if (tokens.indices.all { offset -> index[start + offset] == tokens[offset] }) return true
        }
        return false
    }
}
