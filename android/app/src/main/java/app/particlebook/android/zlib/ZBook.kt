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
)
