package app.particlebook.android.reader.epub.parser

/** EPUB 结构/内容解析失败。message 带稳定前缀，便于测试和 M0 探针归类。 */
class EpubParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
