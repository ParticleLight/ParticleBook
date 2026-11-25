package app.particlebook.android.reader.text

/**
 * 正文里的一张插图。
 *
 * 两种来源统一在这里表达，渲染层不必知道它是 FB2 的内嵌二进制还是 HTML 的本地文件：
 * - **内嵌**：FB2 的 `<binary>` 与 HTML 的 `data:` URI 都先解码成字节，[bytes] 非空；
 * - **本地文件**：HTML 的 `<img src="pic/01.jpg">`，[path] 是解析后的绝对路径，加载时才读盘。
 *
 * [key] 是渲染层的缓存键，必须**同一个资源每次解析都相同**（内嵌用 binary id / data URI 摘要，
 * 文件用绝对路径）。LazyColumn 会反复重组，键不稳定就等于每帧重新解码一张图。
 *
 * [alt] 是 HTML 的 alt / FB2 的 id 兜底文案，解码失败时显示给用户看。
 */
internal data class TextImage(
    val key: String,
    val bytes: ByteArray? = null,
    val path: String? = null,
    val alt: String = "",
) {
    /** 字节已经在内存里（FB2 binary / data URI），渲染时不需要读盘。 */
    val isEmbedded: Boolean
        get() = bytes != null

    companion object {
        /**
         * 单张图的大小上限。超过就整张丢弃：4M 字符的文本已经吃掉几十 MB，
         * 再给一张 30 MB 的扫描图解码，等于直接把进程送进 OOM。
         */
        const val MAX_BYTES: Int = 8 * 1024 * 1024

        /** 内嵌图：空字节或超限返回 null（调用方按「没有这张图」处理）。 */
        fun embedded(key: String, bytes: ByteArray, alt: String = ""): TextImage? {
            if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
            return TextImage(key = key, bytes = bytes, alt = alt)
        }

        /** 本地文件图：路径为空返回 null。是否越界由解析层（HTML 抽取器）负责。 */
        fun file(path: String, alt: String = ""): TextImage? {
            if (path.isBlank()) return null
            return TextImage(key = "file:" + path, path = path, alt = alt)
        }
    }
}
