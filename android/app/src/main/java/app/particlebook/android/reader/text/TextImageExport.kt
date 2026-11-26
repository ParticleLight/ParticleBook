package app.particlebook.android.reader.text

/**
 * 一次「另存插图」的全部输入：落盘用的文件名、MIME、以及**未转码**的原始字节。
 *
 * [bytes] 是原图字节的快照（见 [TextImageBytes.read]），与 [TextImage] 里的源数组没有别名；
 * [mimeType] 与 [fileName] 的后缀同源，都由字节魔数判定，不采信 [TextImage.path] 的扩展名，
 * 也不采信 alt 文案——HTML 里 `pic/01.jpg` 装着一张 PNG 的情况真实存在，按路径猜只会写出
 * 一个扩展名与内容不符的文件。
 */
internal data class TextImageExportPayload(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
)

/**
 * 把一张 [TextImage] 组装成可直接交给 SAF（`ACTION_CREATE_DOCUMENT` / `MediaStore`）的导出载荷。
 *
 * 三件事在这里一次性定死，UI 层不再各算一遍：
 * 1. **字节**：[TextImageBytes.read] 拿原图字节，拿不到直接 null（入口禁用 / 提示失败）；
 * 2. **格式**：按魔数判定 MIME 与后缀，PNG / JPEG / GIF / WebP / BMP 各有唯一答案，
 *    其余一律 `application/octet-stream` + `.img`，不按 path / alt 的伪扩展名猜；
 * 3. **文件名**：`<书名 stem>-插图-<NNN>.<ext>`，stem 去掉 Windows 与文件系统不接受的字符，
 *    按 Unicode code point 截到 [MAX_TITLE_CODE_POINTS]，空则退成「书籍」。
 *
 * 纯 JVM API：不碰 Android API、不新建临时文件；但路径型插图（[TextImage.path]）确实要读盘，
 * 所以整体按阻塞调用对待，必须在 `Dispatchers.IO` 上执行，不要在组合（composition）期间直接调，
 * 具体缓存与 null 口径见 [TextImageBytes]。
 */
internal object TextImageExport {

    /**
     * 书名 stem 的最大长度，单位是 **Unicode code point**（不是 UTF-16 char）。
     *
     * 用 code point 而不是 `String.length`：emoji 与生僻字是代理对，按 char 截会把它们劈成
     * 半个字符，文件名里留下一枚 U+FFFD 或直接乱码。
     */
    const val MAX_TITLE_CODE_POINTS: Int = 40

    /** stem 全被清空时的兜底名字；不空着，否则文件名会变成「-插图-001.png」。 */
    private const val FALLBACK_STEM = "书籍"

    /** 文件名里第几段是「插图」。 */
    private const val IMAGE_LABEL = "插图"

    /** 魔数认不出的图：给一个中性的 MIME 与后缀，仍然允许用户保存，不假装知道它是什么。 */
    private const val FALLBACK_MIME_TYPE = "application/octet-stream"
    private const val FALLBACK_EXTENSION = "img"

    /**
     * Windows 文件名里不允许出现的字符（同时也是 FAT/exFAT 与多数云盘的禁区）：
     * 反斜杠、斜杠、冒号、星号、问号、双引号、尖括号、竖线。ISO control（含 \t \n \r 与
     * 0x00–0x1F / 0x7F–0x9F）另算，见 [isIllegalFileNameChar]。
     */
    private val ILLEGAL_FILE_NAME_CHARS = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

    /** 首尾要剥掉的字符：空格、点、下划线（Windows 会吞掉结尾的点与空格，早剥早稳定）。 */
    private val EDGE_JUNK_CHARS = charArrayOf(' ', '.', '_')

    /** PNG 8 字节签名 \x89PNG\r\n\x1A\n。 */
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    private val PNG = ImageFormat("image/png", "png")
    private val JPEG = ImageFormat("image/jpeg", "jpg")
    private val GIF = ImageFormat("image/gif", "gif")
    private val WEBP = ImageFormat("image/webp", "webp")
    private val BMP = ImageFormat("image/bmp", "bmp")
    private val UNKNOWN = ImageFormat(FALLBACK_MIME_TYPE, FALLBACK_EXTENSION)

    /**
     * 这张图能不能导出、导出成什么名字；拿不到原图返回 null。
     *
     * [bookTitle] 只用来生成文件名 stem，不影响字节与格式；[imageNumber] 是**1 基**序号，
     * `<= 0` 夹到 1，`>= 1000` 保留全部数位（不截断、不补零），其余补零到 3 位。
     *
     * 返回的 [TextImageExportPayload.bytes] 是快照，调用方可直接写盘；本函数不改 [image]、
     * 不改 [bookTitle]，也不产生任何临时文件。
     */
    fun prepare(image: TextImage, bookTitle: String, imageNumber: Int): TextImageExportPayload? {
        val bytes = TextImageBytes.read(image) ?: return null
        val format = formatOf(bytes)
        return TextImageExportPayload(
            fileName = fileNameOf(bookTitle, imageNumber, format.extension),
            mimeType = format.mimeType,
            bytes = bytes,
        )
    }

    /** 按魔数判格式；判不出就是 [UNKNOWN]，绝不按 [TextImage.path] 的后缀猜。 */
    private fun formatOf(bytes: ByteArray): ImageFormat = when {
        startsWith(bytes, PNG_SIGNATURE) -> PNG
        // JPEG：SOI(FF D8) + 后面至少还有一个 FF（紧跟的是 APPn/DQT 之类的段标记）。
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte() -> JPEG
        asciiAt(bytes, 0, "GIF87a") || asciiAt(bytes, 0, "GIF89a") -> GIF
        // WebP：RIFF 容器，第 4–7 字节是长度（任意），第 8–11 字节必须是 "WEBP"。
        asciiAt(bytes, 0, "RIFF") && asciiAt(bytes, 8, "WEBP") -> WEBP
        bytes.size >= 2 && bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte() -> BMP
        else -> UNKNOWN
    }

    private fun fileNameOf(bookTitle: String, imageNumber: Int, extension: String): String =
        stemOf(bookTitle) + "-" + IMAGE_LABEL + "-" + ordinalOf(imageNumber) + "." + extension

    /**
     * 序号段：1 基，3 位补零；`<= 0` 当 1；`>= 1000` 原样输出（`1000`、`12345`），
     * 不为了对齐而把大序号截回三位——那会让第 1000 张与第 0 张撞名。
     */
    private fun ordinalOf(imageNumber: Int): String {
        val number = if (imageNumber < 1) 1 else imageNumber
        val digits = number.toString()
        if (digits.length >= 3) return digits
        return "0".repeat(3 - digits.length) + digits
    }

    /**
     * 从书名生成文件名 stem：
     * 1. 非法字符（[ILLEGAL_FILE_NAME_CHARS] 与 ISO control）→ `_`；
     * 2. 其余连续空白（含全角空格、NBSP）压成单个半角空格；
     * 3. 截到 [MAX_TITLE_CODE_POINTS] 个 code point；
     * 4. 首尾的空格 / 点 / 下划线剥掉；剥完为空退成「书籍」。
     *
     * 替换与压空白是**互不吞并**的两条规则：非法字符一律变 `_`，但它两侧原有的空白仍然算空白，
     * 所以 `"A : B"` → `"A _ B"`、`"A \t B"` → `"A _ B"`；只有首尾与连续空白会被收拾。
     * 若在这里把 `'_'` 前后的空格一并吞掉，`"A : B"` 会变成 `"A_B"`，书名里的分隔感就丢了。
     * 第 4 步在第 3 步之后再走一遍，因为截断可能正好切在一个 `_` 或空格上。
     */
    private fun stemOf(bookTitle: String): String {
        val builder = StringBuilder(bookTitle.length)
        var pendingSpace = false
        for (ch in bookTitle) {
            when {
                isIllegalFileNameChar(ch) -> {
                    // 先替换、再压空白：'A : B' 里 ':' 前面的空格是真实空白，不能因为
                    // 替换了一个非法字符就顺手吞掉，结果应是 'A _ B'。
                    if (pendingSpace) {
                        builder.append(' ')
                        pendingSpace = false
                    }
                    builder.append('_')
                }
                ch.isWhitespace() -> pendingSpace = true
                else -> {
                    if (pendingSpace) {
                        builder.append(' ')
                        pendingSpace = false
                    }
                    builder.append(ch)
                }
            }
        }
        val trimmed = builder.toString().trim(*EDGE_JUNK_CHARS)
        val truncated = truncateCodePoints(trimmed, MAX_TITLE_CODE_POINTS).trim(*EDGE_JUNK_CHARS)
        return truncated.ifEmpty { FALLBACK_STEM }
    }

    private fun isIllegalFileNameChar(ch: Char): Boolean =
        ch in ILLEGAL_FILE_NAME_CHARS || Character.isISOControl(ch)

    /** 按 code point 截断：超出才截，且永不劈开代理对。 */
    private fun truncateCodePoints(text: String, maxCodePoints: Int): String {
        if (maxCodePoints <= 0 || text.isEmpty()) return ""
        if (text.codePointCount(0, text.length) <= maxCodePoints) return text
        return text.substring(0, text.offsetByCodePoints(0, maxCodePoints))
    }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean {
        if (bytes.size < prefix.size) return false
        for (index in prefix.indices) {
            if (bytes[index] != prefix[index]) return false
        }
        return true
    }

    /** [offset] 起是否正好是 ASCII 串 [text]（越界即 false）。 */
    private fun asciiAt(bytes: ByteArray, offset: Int, text: String): Boolean {
        if (offset < 0 || bytes.size - offset < text.length) return false
        for (index in text.indices) {
            if (bytes[offset + index] != text[index].code.toByte()) return false
        }
        return true
    }

    /** 魔数判定结果；扩展名与 MIME 只在这里成对出现，避免两处各写一份对不上。 */
    private data class ImageFormat(val mimeType: String, val extension: String)
}
