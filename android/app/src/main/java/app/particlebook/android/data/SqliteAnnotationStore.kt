package app.particlebook.android.data

import app.particlebook.android.reader.AnnotationStore
import app.particlebook.android.reader.ReaderAnnotation
import app.particlebook.android.reader.ReaderAnnotationType
import app.particlebook.android.reader.ReaderLocator
import java.util.Locale

/**
 * P1-A021 标注持久化实现：AnnotationStore 契约 → AnnotationRow/SQLite。
 *
 * - locator 统一用 [ReaderLocator.encode] 写入 locator_json；decode 失败的行保留为占位行并带 locatorError/rawLocatorJson，不让整本书打不开。
 * - DB 的 kind 只有 bookmark/highlight/note；PDF 标注暂存为 highlight（P2 再拆）。
 * - add 分配数据库 id；update 只允许改 quote/note/color/updatedAt，locator/type/createdAt 不可变。
 * - remove 走软删，重复删除返回 false。
 *
 * 同步 API；调用方负责放到 IO dispatcher。
 */
class SqliteAnnotationStore(
    private val repository: LibraryRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : AnnotationStore {

    override fun list(bookId: Long): List<ReaderAnnotation> =
        repository.annotations(bookId)
            .asSequence()
            .map { row -> toAnnotation(row) }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
            .toList()

    override fun add(annotation: ReaderAnnotation): ReaderAnnotation {
        val now = clock()
        val row = AnnotationRow(
            bookId = annotation.bookId,
            kind = kindFor(annotation.type),
            locatorJson = annotation.locator.encode(),
            quote = annotation.quote,
            note = annotation.note,
            color = annotation.color,
            createdAt = annotation.createdAt.takeIf { it > 0 } ?: now,
            updatedAt = annotation.updatedAt.takeIf { it > 0 } ?: now,
        )
        val id = repository.addAnnotation(row)
        val persisted = repository.annotationById(id) ?: row.copy(id = id)
        return toAnnotation(persisted)
    }

    override fun update(annotation: ReaderAnnotation): ReaderAnnotation? {
        if (annotation.id <= 0) return null
        val existing = repository.annotationById(annotation.id) ?: return null
        val updated = existing.copy(
            quote = annotation.quote,
            note = annotation.note,
            color = annotation.color,
            updatedAt = clock(),
        )
        if (!repository.updateAnnotation(updated)) return null
        val persisted = repository.annotationById(annotation.id) ?: updated
        return toAnnotation(persisted)
    }

    override fun remove(annotationId: Long): Boolean {
        if (annotationId <= 0) return false
        return repository.removeAnnotation(annotationId)
    }

    private fun toAnnotation(row: AnnotationRow): ReaderAnnotation {
        val type = typeFor(row.kind) ?: ReaderAnnotationType.HIGHLIGHT
        val locator = ReaderLocator.decode(row.locatorJson)
        return ReaderAnnotation(
            id = row.id,
            bookId = row.bookId,
            type = type,
            // A021-6：坏 locator 不再被静默丢弃；占位 Text(0) 只用于满足非空模型，
            // UI 依据 locatorError 显示「位置不可用」，并禁止跳转。
            locator = locator ?: ReaderLocator.Text(0),
            quote = row.quote,
            note = row.note,
            color = row.color,
            createdAt = row.createdAt,
            updatedAt = row.updatedAt,
            locatorError = if (locator == null) "位置不可用" else null,
            rawLocatorJson = if (locator == null) row.locatorJson else null,
        )
    }

    private fun kindFor(type: ReaderAnnotationType): String = when (type) {
        ReaderAnnotationType.BOOKMARK -> "bookmark"
        ReaderAnnotationType.HIGHLIGHT -> "highlight"
        ReaderAnnotationType.NOTE -> "note"
        // DB CHECK 只允许三种 kind；PDF 标注暂时落 highlight，读回为 HIGHLIGHT，P2 再拆分。
        ReaderAnnotationType.PDF -> "highlight"
    }

    private fun typeFor(kind: String): ReaderAnnotationType? = when (kind.trim().lowercase(Locale.ROOT)) {
        "bookmark" -> ReaderAnnotationType.BOOKMARK
        "highlight" -> ReaderAnnotationType.HIGHLIGHT
        "note" -> ReaderAnnotationType.NOTE
        else -> null
    }
}
