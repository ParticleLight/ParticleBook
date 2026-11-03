package app.particlebook.android.ui.discover

import android.content.Context

/**
 * P1.5 发现面的 UI 模型与数据源接口。
 *
 * 为什么不直接用 zlib 模型：zlib/ 的发现面模型由 pdf-render 并行实现，UI 先按 spec §3 定义展示模型，
 * 数据落地后只需在 [DiscoverRepositoryProvider] 里换成 ZlibDiscoverRepository 做映射；页面与 ViewModel 不动。
 */

/** 书籍行展示字段（书单详情 / 分类列表 / 榜单卡片都从它渲染，不依赖 zlib.ZBook）。 */
data class DiscoverBook(
    val id: String,
    val title: String,
    val author: String,
    val href: String,
    val language: String = "",
    val extension: String = "",
    val fileSize: String = "",
)

/** 书单列表卡（spec §3 ZBooklist 的展示子集）。 */
data class BooklistCard(
    val id: String,
    val href: String,
    val topic: String,
    val author: String,
    val quantity: Int = 0,
    val views: String = "",
    val comments: Int = 0,
    val description: String = "",
    val editorsChoice: Boolean = false,
    val notLogged: Boolean = false,
    val coverUrls: List<String> = emptyList(),
)

data class BooklistPage(
    val items: List<BooklistCard>,
    val hasMore: Boolean = false,
)

data class BooklistDetailData(
    val id: String,
    val title: String,
    val author: String,
    val description: String = "",
    val booksCount: Int = 0,
    val commentsCount: Int = 0,
    val books: List<DiscoverBook> = emptyList(),
    val hasMore: Boolean = false,
)

data class PopularBook(
    val book: DiscoverBook,
    val coverUrl: String = "",
)

data class CategoryEntry(
    val id: String,
    val name: String,
    val slug: String,
    val path: String,
    val booksCount: Int = 0,
)

data class CategoryGroupData(
    val id: String,
    val name: String,
    val path: String,
    val categories: List<CategoryEntry> = emptyList(),
)

data class CategoryPageData(
    val page: Int,
    val totalPages: Int,
    val books: List<DiscoverBook> = emptyList(),
)

/** 数据层错误：kind 用字符串传递（PENDING/NO_MIRROR/NETWORK/PARSE…），UI 只负责展示中文。 */
class DiscoverException(
    message: String,
    val kind: String? = null,
) : Exception(message)

/** 发现面数据源：阻塞调用约定，调用方放 Dispatchers.IO。 */
interface DiscoverRepository {
    suspend fun booklists(page: Int): BooklistPage
    suspend fun booklistDetail(href: String): BooklistDetailData
    suspend fun popular(page: Int): List<PopularBook>
    suspend fun categories(forceRefresh: Boolean = false): List<CategoryGroupData>
    suspend fun categoryPage(id: String, slug: String, page: Int): CategoryPageData
}

object DiscoverRepositoryProvider {
    /** 真实实现：ZlibClient 阻塞调用 + UI 模型映射（详见 ZlibDiscoverRepository）。 */
    fun create(context: Context): DiscoverRepository = ZlibDiscoverRepository(context)
}
