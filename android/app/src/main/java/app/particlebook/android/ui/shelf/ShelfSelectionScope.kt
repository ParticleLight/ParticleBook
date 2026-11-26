package app.particlebook.android.ui.shelf

import app.particlebook.android.ui.data.Book

/** 当前书架结果对应的选择域，不附加格式或 ID 合法性筛选。 */
internal object ShelfSelectionScope {
    /** 按书籍首次出现顺序去重，保留所有 Long ID。 */
    fun idsOf(books: List<Book>): Set<Long> =
        books.mapTo(linkedSetOf<Long>()) { it.id }

    /** 返回选择集与当前作用域的交集，不修改任一输入集合。 */
    fun retain(selectedIds: Set<Long>, scopeIds: Set<Long>): Set<Long> =
        selectedIds.intersect(scopeIds)
}
