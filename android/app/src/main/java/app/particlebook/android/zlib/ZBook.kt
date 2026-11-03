package app.particlebook.android.zlib

/**
 * 一条 Z-Library 搜索结果（对应搜索页里的一个 `<z-bookcard>`）。
 *
 * 字段与 `docs/android-port/zlibrary-p1a-spec.md` §2 一致；缺省属性一律空串，不抛异常。
 */
data class ZBook(
    val id: String,
    val title: String,
    val author: String,
    val href: String,
    val downloadPath: String,
    val language: String,
    val extension: String,
    val fileSize: String,
    val year: String,
    val publisher: String,
    val rating: String,
    val isbn: String,
    /**
     * 封面原始地址（搜索页卡片内首个 img 的 data-src，回退 src）。
     *
     * 可能是绝对 CDN、协议相对（//host/...）或站内相对路径（/img/...）；
     * 空串表示无封面（占位图 cover-not-exists / 空 data-src / 脏值）。
     * 由 UI 侧 resolveZlibCoverUrl(raw, mirrorBase) 解析成可下载的绝对 URL。
     * 默认值保证旧构造与旧测试代码兼容。
     */
    val coverUrl: String = "",
)
