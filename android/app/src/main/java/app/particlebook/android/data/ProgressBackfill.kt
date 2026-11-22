package app.particlebook.android.data

import app.particlebook.android.reader.ReaderLocator
import java.util.Locale

data class ProgressBackfillResult(
    val merged: Map<Long, ProgressSnapshot>,
    val toSave: List<ProgressSnapshot>,
    val conflicts: Int,
    val missingBooks: Int,
)

/** 只在 SQLite 的一次性迁移标记提交前使用旧 prefs。 */
object ProgressBackfill {
    fun merge(
        db: Map<Long, ProgressSnapshot>,
        legacy: Collection<LegacyProgress>,
        formatOf: (Long) -> String?,
    ): ProgressBackfillResult {
        val merged = LinkedHashMap(db)
        val toSave = LinkedHashMap<Long, ProgressSnapshot>()
        var conflicts = 0
        var missingBooks = 0
        val selected = LinkedHashMap<Long, LegacyProgress>()
        legacy.forEach { item ->
            selected[item.bookId] = selected[item.bookId]?.let { selectLegacyProgress(it, item) } ?: item
        }
        for (item in selected.values) {
            if (item.bookId <= 0) continue
            val format = formatOf(item.bookId)?.lowercase(Locale.ROOT)
            if (format == null) {
                missingBooks += 1
                continue
            }
            val existing = merged[item.bookId]
            val updatedAt = item.lastReadAt?.takeIf { it > 0 } ?: 0L
            val page = item.page?.takeIf { it >= 0 }
            val total = item.totalPages?.takeIf { it > 0 }
            val candidate = if (existing != null && updatedAt <= existing.updatedAt) {
                // 旧影子迁移曾以相同时间（或 now）写合成 JSON。只补其 raw locator，
                // 不覆盖已有真实 locator，不回退 DB 的页码、时间与完成状态。
                val matchingTime = updatedAt == existing.updatedAt || updatedAt == 0L
                if (item.locatorRaw != null && matchingTime && isGeneratedLegacy(existing, format, page, total)) {
                    existing.copy(locatorJson = item.locatorRaw)
                } else {
                    conflicts += 1
                    continue
                }
            } else {
                ProgressSnapshot(
                    bookId = item.bookId,
                    locatorJson = item.locatorRaw ?: if (format == "pdf" && page != null) {
                        ReaderLocator.Pdf(page).encode()
                    } else "",
                    page = page,
                    totalPages = total,
                    percent = if (page != null && total != null) (page.toDouble() / total).coerceIn(0.0, 1.0) else null,
                    updatedAt = updatedAt,
                    finishedAt = existing?.finishedAt,
                )
            }
            if (candidate == existing) {
                conflicts += 1
                continue
            }
            merged[item.bookId] = candidate
            toSave[item.bookId] = candidate
        }
        return ProgressBackfillResult(merged, toSave.values.toList(), conflicts, missingBooks)
    }

    private fun isGeneratedLegacy(existing: ProgressSnapshot, format: String, page: Int?, total: Int?): Boolean {
        if (existing.page != page || existing.totalPages != total) return false
        fun generated(kind: String): String {
            val fields = mutableListOf("\"kind\":" + StableKey.jsonString(kind))
            if (page != null) fields += "\"page\":" + page
            if (total != null) fields += "\"total\":" + total
            return "{" + fields.joinToString(",") + "}"
        }
        // The old duplicate-alias merger mislabeled EPUB rows as PDF. Only its exact
        // compact output with matching page/total/time is repairable here.
        return existing.locatorJson == generated(format) ||
            (format == "epub" && existing.locatorJson == generated("pdf"))
    }
}

/** 原子回填 + 标记 + 一次 hydrate；异常向调用方传播，允许再次 initialize 重试。 */
object ProgressBootstrap {
    fun load(
        repository: LibraryRepository,
        readPreferences: () -> Map<String, Any?>,
        databaseIdOf: (Long) -> Long? = { it },
        eligibleBookIds: Set<Long>? = null,
        deletedBookIds: Set<Long> = emptySet(),
    ): Map<Long, ProgressSnapshot> = repository.transaction { tx ->
        // No writer exists yet. Delete before hydrate and in the same transaction as backfill.
        tx.deleteProgress(deletedBookIds)
        val loaded = tx.progressAll().associate { it.bookId to ProgressSnapshot.fromRow(it) }
        if (tx.progressBackfillComplete()) return@transaction loaded
        val formats = tx.books().associate { it.id to it.format }
        val legacy = LegacyLibraryReader().readProgress(readPreferences()).mapNotNull { item ->
            val dbId = databaseIdOf(item.bookId) ?: return@mapNotNull null
            if (dbId in deletedBookIds || (eligibleBookIds != null && dbId !in eligibleBookIds)) return@mapNotNull null
            item.copy(bookId = dbId)
        }
        val backfill = ProgressBackfill.merge(loaded, legacy, formats::get)
        tx.saveProgressBatch(backfill.toSave.map { it.toRow() })
        tx.markProgressBackfillComplete()
        backfill.merged
    }
}
