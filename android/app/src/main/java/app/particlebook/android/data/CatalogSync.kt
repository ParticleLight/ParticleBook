package app.particlebook.android.data

/**
 * 书架 → SQLite 的启动同步。
 *
 * 以 stable_key 为身份做 upsert：已存在则沿用原数字 id 更新，不存在则插入。
 * 旧 UI 的 [app.particlebook.android.ui.data.Book] 只带基础字段，因此这里只覆盖
 * 非空/非零的同步字段；SQLite 侧已有的封面、系列、评分等扩展字段不会被清空。
 */
object CatalogSync {

    /**
     * 返回本次实际处理的非空 stable_key 行数（含新增与更新）。
     * 空 stable_key 行跳过，避免写入无法认领的脏数据。
     */
    fun syncBooks(repository: LibraryRepository, rows: List<BookRow>): Int {
        if (rows.isEmpty()) return 0
        val existingByKey = repository.books()
            .filter { it.stableKey.isNotBlank() }
            .associateBy { it.stableKey }
            .toMutableMap()
        var synced = 0
        rows.forEach { incoming ->
            val stableKey = incoming.stableKey.trim()
            if (stableKey.isEmpty()) return@forEach
            val normalized = if (incoming.stableKey == stableKey) incoming else incoming.copy(stableKey = stableKey)
            val existing = existingByKey[stableKey]
            val merged = merge(existing, normalized)
            val id = repository.saveBook(merged)
            if (id > 0) {
                synced += 1
                existingByKey[stableKey] = merged.copy(id = id)
            }
        }
        return synced
    }

    private fun merge(existing: BookRow?, incoming: BookRow): BookRow {
        if (existing == null) return incoming
        return incoming.copy(
            // 数字 id 永不变：同 stable_key 已有行时沿用已持久化 id。
            id = existing.id,
            legacyId = incoming.legacyId ?: existing.legacyId,
            title = incoming.title.takeIf { it.isNotBlank() } ?: existing.title,
            sortTitle = incoming.sortTitle.takeIf { it.isNotBlank() } ?: existing.sortTitle,
            author = incoming.author.takeIf { it.isNotBlank() } ?: existing.author,
            format = incoming.format.takeIf { it.isNotBlank() && it != "unknown" } ?: existing.format,
            filePath = incoming.filePath.takeIf { it.isNotBlank() } ?: existing.filePath,
            fileName = incoming.fileName.takeIf { it.isNotBlank() } ?: existing.fileName,
            fileSize = incoming.fileSize.takeIf { it > 0 } ?: existing.fileSize,
            contentHash = incoming.contentHash ?: existing.contentHash,
            fileMtime = incoming.fileMtime.takeIf { it > 0 } ?: existing.fileMtime,
            coverPath = incoming.coverPath ?: existing.coverPath,
            seriesId = incoming.seriesId ?: existing.seriesId,
            seriesIndex = incoming.seriesIndex ?: existing.seriesIndex,
            rating = incoming.rating ?: existing.rating,
            language = incoming.language ?: existing.language,
            publisher = incoming.publisher ?: existing.publisher,
            publishedAt = incoming.publishedAt ?: existing.publishedAt,
            isbn = incoming.isbn ?: existing.isbn,
            description = incoming.description ?: existing.description,
            source = incoming.source.takeIf { it.isNotBlank() } ?: existing.source,
            sourceId = incoming.sourceId ?: existing.sourceId,
            addedAt = incoming.addedAt.takeIf { it > 0 } ?: existing.addedAt,
            updatedAt = incoming.updatedAt.takeIf { it > 0 } ?: existing.updatedAt,
            lastOpenedAt = incoming.lastOpenedAt ?: existing.lastOpenedAt,
            metadataJson = incoming.metadataJson ?: existing.metadataJson,
        )
    }
}
