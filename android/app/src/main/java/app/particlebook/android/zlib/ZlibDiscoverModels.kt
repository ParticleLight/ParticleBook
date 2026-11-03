package app.particlebook.android.zlib

/**
 * P1.5 发现面数据模型（书单 / 榜单 / 分类），纯数据、无 Android 依赖。
 * 字段与 docs/android-port/zlibrary-p15-spec.md §3 一致。
 */
data class ZBooklist(
    val id: String,
    val href: String,
    val topic: String,
    val author: String,
    val authorProfile: String = "",
    val authorAvatar: String = "",
    val quantity: Int = 0,
    val views: String = "",
    val comments: Int = 0,
    val description: String = "",
    val color: String = "",
    val notLogged: Boolean = false,
    val shortDescription: Boolean = false,
    val editorsChoice: Boolean = false,
    val coverUrls: List<String> = emptyList(),
)

/** 书单列表页（分页信息只有 hasLoadMore：站点没有给出总页数）。 */
data class ZBooklistPage(
    val booklists: List<ZBooklist>,
    val hasLoadMore: Boolean,
)

data class ZBooklistDetail(
    val id: String,
    val title: String,
    val author: String,
    val authorProfile: String = "",
    val description: String = "",
    val booksCount: Int = 0,
    val commentsCount: Int = 0,
    val sort: String = "date_saved",
    val books: List<ZBook> = emptyList(),
    val hasMore: Boolean = false,
)

data class ZPopularItem(val book: ZBook, val coverUrl: String = "")

data class ZCategory(
    val id: String,
    val name: String,
    val slug: String,
    val path: String,
    val booksCount: Int = 0,
    val type: String = "",
)

data class ZCategoryGroup(
    val id: String,
    val name: String,
    val path: String,
    val categories: List<ZCategory> = emptyList(),
)

data class ZCategoryPage(val page: Int, val totalPages: Int, val books: List<ZBook>)
