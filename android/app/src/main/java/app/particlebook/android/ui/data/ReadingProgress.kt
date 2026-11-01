package app.particlebook.android.ui.data

import android.content.Context

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

    /** 无障碍用：没有进度时补「未读」（屏幕阅读器感知不到「进度条缺失」）。 */
    fun a11yStatus(context: Context, bookId: Long): String =
        percentLabel(context, bookId) ?: "未读"
}
