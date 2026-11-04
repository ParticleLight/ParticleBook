package app.particlebook.android.ui.discover

import android.content.Context
import app.particlebook.android.zlib.ZBook
import app.particlebook.android.zlib.ZBooklist
import app.particlebook.android.zlib.ZCategory
import app.particlebook.android.zlib.ZCategoryGroup
import app.particlebook.android.zlib.ZlibClient
import app.particlebook.android.zlib.ZlibErrorMessages
import app.particlebook.android.zlib.ZlibException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * P1.5 发现面真实数据源：把 zlib.ZlibClient 的阻塞调用映射成 [DiscoverRepository] 的 UI 模型。
 *
 * 约定（与 pdf-render 冻结接口一致）：
 * - 全部网络调用在 [Dispatchers.IO]；
 * - [ZlibException.Kind] 映射成 [DiscoverException.kind] 字符串，UI 按 kind 决定文案/是否显示「不存在」；
 * - 书单详情当前只给首屏 20 本（站点 load-more 走 JS），hasMore 透传但页面不渲染「加载更多」。
 */
internal class ZlibDiscoverRepository(context: Context) : DiscoverRepository {

    private val appContext = context.applicationContext
    private val client: ZlibClient get() = ZlibClient.get(appContext)

    override suspend fun booklists(page: Int): BooklistPage = io {
        val result = client.fetchBooklists(page)
        BooklistPage(
            items = result.booklists.map { it.toCard() },
            hasMore = result.hasLoadMore,
        )
    }

    override suspend fun booklistDetail(href: String): BooklistDetailData = io {
        val detail = client.fetchBooklist(href)
        BooklistDetailData(
            id = detail.id,
            title = detail.title,
            author = detail.author,
            description = detail.description,
            booksCount = detail.booksCount,
            commentsCount = detail.commentsCount,
            books = detail.books.map { it.toDiscoverBook() },
            hasMore = detail.hasMore,
        )
    }

    override suspend fun popular(page: Int): List<PopularBook> = io {
        client.fetchPopular(page).map { item ->
            PopularBook(book = item.book.toDiscoverBook(), coverUrl = item.coverUrl)
        }
    }

    override suspend fun categories(forceRefresh: Boolean): List<CategoryGroupData> = io {
        client.fetchCategories(forceRefresh).map { it.toGroup() }
    }

    override suspend fun categoryPage(id: String, slug: String, page: Int): CategoryPageData = io {
        val result = client.fetchCategoryPage(id = id, slug = slug, page = page)
        CategoryPageData(
            page = result.page,
            totalPages = result.totalPages,
            books = result.books.map { it.toDiscoverBook() },
        )
    }

    // ------------------------------------------------------------ 映射

    private fun ZBook.toDiscoverBook(): DiscoverBook = DiscoverBook(
        id = id,
        title = title,
        author = author,
        href = href,
        language = language,
        extension = extension,
        fileSize = fileSize,
    )

    private fun ZBooklist.toCard(): BooklistCard = BooklistCard(
        id = id,
        href = href,
        topic = topic,
        author = author,
        quantity = quantity,
        views = views,
        comments = comments,
        description = description,
        editorsChoice = editorsChoice,
        notLogged = notLogged,
        coverUrls = coverUrls,
    )

    private fun ZCategory.toEntry(): CategoryEntry = CategoryEntry(
        id = id,
        name = name,
        slug = slug,
        path = path,
        booksCount = booksCount,
    )

    private fun ZCategoryGroup.toGroup(): CategoryGroupData = CategoryGroupData(
        id = id,
        name = name,
        path = path,
        categories = categories.map { it.toEntry() },
    )
}

/**
 * 把 zlib 阻塞调用挪到 IO 线程，并把异常统一成 [DiscoverException]。
 * 软 404（页面不存在）单独标成 NOT_FOUND，分类列表页据此显示专用占位而不是「加载失败」。
 */
private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ZlibException) {
        throw DiscoverException(messageFor(e), kindFor(e))
    } catch (e: Exception) {
        throw DiscoverException(e.message ?: "网络请求失败", "NETWORK")
    }
}

private fun kindFor(e: ZlibException): String {
    val text = e.message.orEmpty()
    if (text.contains("Page not found", ignoreCase = true)) return "NOT_FOUND"
    return e.kind.name
}

private fun messageFor(e: ZlibException): String = when {
    e.message?.contains("Page not found", ignoreCase = true) == true ->
        "内容不存在或已下架"

    else -> when (e.kind) {
        ZlibException.Kind.NO_MIRROR -> "连接不到 Z-Library 镜像，请检查网络后重试"
        ZlibException.Kind.CHALLENGE_FAILED -> "站点校验未通过，请稍后重试"
        // D12：有真实状态码/重定向细分时统一由 ZlibErrorMessages 给文案（与详情页一致）。
        ZlibException.Kind.HTTP -> ZlibErrorMessages.from(e)
        ZlibException.Kind.PARSE -> "页面解析失败，请稍后重试"
        ZlibException.Kind.NETWORK -> "网络连接异常，请检查网络后重试"
        ZlibException.Kind.DAILY_LIMIT -> "今天的下载额度已用尽"
        ZlibException.Kind.LOGIN_REQUIRED -> "需要登录后才能操作"
        ZlibException.Kind.DOWNLOAD -> e.message ?: "下载失败"
        ZlibException.Kind.CANCELLED -> e.message ?: "已取消"
    }
}
