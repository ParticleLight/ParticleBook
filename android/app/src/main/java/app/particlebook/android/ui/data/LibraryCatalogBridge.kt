package app.particlebook.android.ui.data

import app.particlebook.android.data.BookRow
import app.particlebook.android.data.StableKey
import java.util.Locale

/**
 * P1 旧书架模型 → SQLite 行模型的桥。
 *
 * 这里只做字段搬运，不读写 LibraryStore，也不改变旧 UI 的 books.json 路径；
 * 是否启用由启动接线方决定（可以随时回退到旧链路）。
 */
object LibraryCatalogBridge {

    /**
     * 把旧书架条目映射为可 [app.particlebook.android.data.CatalogSync] 的行。
     * [now] 仅用于测试可复现；生产调用方不传时取当前时间。
     */
    fun bookRows(
        books: List<Book>,
        now: Long = System.currentTimeMillis(),
    ): List<BookRow> = books.map { book ->
        BookRow(
            id = book.id,
            stableKey = StableKey.stableKeyFor(book.id, book.tags),
            legacyId = book.id.takeIf { it > 0 },
            title = book.title,
            sortTitle = book.title.trim().lowercase(Locale.ROOT),
            author = book.author?.trim().orEmpty(),
            // BookFormat.raw 是 canonical 小写值；label 是展示文案（Unsupported 为中文）。
            format = StableKey.normalizeFormat(book.format.raw),
            filePath = book.filePath,
            fileName = StableKey.fileNameFromPath(book.filePath),
            fileSize = book.fileSize,
            source = "local",
            addedAt = StableKey.parseEpochMillis(book.addedAt),
            updatedAt = now,
        )
    }
}
