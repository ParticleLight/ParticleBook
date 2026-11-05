package app.particlebook.android.ui.data

import android.content.Context
import app.particlebook.android.reader.ReaderLocator

/**
 * 阅读进度：ReaderScreen 把 `page_<bookId>`（当前页，0 基）与 `pages_<bookId>`（总页数）
 * 写在 SharedPreferences("pb_reading") 里。书架卡片 / 横屏列表行都从这里取进度文案。
 *
 * 设计口径（spec §2.1.3 / §7.3）：视觉上「未读」**不显示**（元数据行只写格式），
 * 但 TalkBack 的 contentDescription 必须补「未读」，所以分开给了两个方法。
 */
object ReadingProgress {
    private const val PREFS_NAME = "pb_reading"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 「已读 45%」；没有进度（没读过、或只有 1 页）返回 null。 */
    fun percentLabel(context: Context, bookId: Long): String? {
        val store = prefs(context)
        val pages = store.getInt("pages_" + bookId, 0)
        if (pages <= 1) return null
        val current = store.getInt("page_" + bookId, 0).coerceIn(0, pages - 1)
        val percent = ((current + 1) * 100 / pages).coerceIn(0, 100)
        return "已读 " + percent + "%"
    }

    /** 是否有真实阅读进度（Hero「继续阅读」只看有进度的书）。 */
    fun hasProgress(context: Context, bookId: Long): Boolean =
        prefs(context).getInt("pages_" + bookId, 0) > 1

    /** 最近一次保存的 EPUB 位置；缺失或损坏返回 null。 */
    fun locator(context: Context, bookId: Long): ReaderLocator? =
        ReaderLocator.decode(prefs(context).getString("locator_" + bookId, null))

    /**
     * EPUB 翻页/换章后写回统一 locator，并同步书架用的 page/pages/last_read。
     * pages <= 0 时不写，避免布局未完成把进度清成 0。
     */
    fun saveEpubProgress(
        context: Context,
        bookId: Long,
        locator: ReaderLocator,
        page: Int,
        pages: Int,
    ) {
        if (pages <= 0) return
        prefs(context).edit()
            .putString("locator_" + bookId, locator.encode())
            .putInt("page_" + bookId, page.coerceIn(0, pages - 1))
            .putInt("pages_" + bookId, pages)
            .putLong("last_read_" + bookId, System.currentTimeMillis())
            .apply()
    }

    /** 最近一次阅读时间（毫秒，0 = 未知）；Hero 用它挑「最近读的那本」。 */
    fun lastReadAt(context: Context, bookId: Long): Long =
        prefs(context).getLong("last_read_" + bookId, 0L)

    /** 封面底部细进度条的填充比例 0..1；没有进度返回 null。 */
    fun fraction(context: Context, bookId: Long): Float? {
        val store = prefs(context)
        val pages = store.getInt("pages_" + bookId, 0)
        if (pages <= 1) return null
        val current = store.getInt("page_" + bookId, 0).coerceIn(0, pages - 1)
        return ((current + 1).toFloat() / pages.toFloat()).coerceIn(0f, 1f)
    }

    /** 无障碍用：没有进度时补「未读」（屏幕阅读器感知不到「进度条缺失」）。 */
    fun a11yStatus(context: Context, bookId: Long): String =
        percentLabel(context, bookId) ?: "未读"

    /**
     * P3-4：删书级联清掉进度（page/pages/last_read）。返回实际删掉的键数。
     * 清理失败不应让删书失败，调用方负责吞异常/记日志。
     */
    fun clear(context: Context, bookIds: Collection<Long>): Int {
        if (bookIds.isEmpty()) return 0
        val store = prefs(context)
        val editor = store.edit()
        var removed = 0
        bookIds.forEach { bookId ->
            progressKeys(bookId).forEach { key ->
                if (store.contains(key)) {
                    editor.remove(key)
                    removed += 1
                }
            }
            val locatorKey = "locator_" + bookId
            if (store.contains(locatorKey)) {
                editor.remove(locatorKey)
                removed += 1
            }
        }
        editor.apply()
        return removed
    }
}

/** 一本书在 pb_reading 里的页码类键（locator_ 由 clear 单独清理；纯函数，单测用）。 */
internal fun progressKeys(bookId: Long): List<String> =
    listOf("page_" + bookId, "pages_" + bookId, "last_read_" + bookId)
