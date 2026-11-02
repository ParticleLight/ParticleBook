package app.particlebook.android.zlib

/**
 * 一页搜索结果。
 *
 * @param totalPages 站方分页总数（来自页面 `pagerOptions.pagesTotal`）；无法识别时为 [page]（有结果）
 *                   或 0（空结果）。
 */
data class ZSearchPage(
    val query: String,
    val page: Int,
    val totalPages: Int,
    val books: List<ZBook>,
)
