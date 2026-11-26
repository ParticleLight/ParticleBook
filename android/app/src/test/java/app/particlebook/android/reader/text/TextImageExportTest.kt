package app.particlebook.android.reader.text

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [TextImageExport] 的纯 JVM 单测：魔数识别、文件名 stem 清洗与编号、快照语义。
 *
 * 契约（task-10）：
 * - 魔数：PNG 8 字节 / JPEG FF D8 FF / GIF87a、GIF89a / RIFF????WEBP / BM；其余 application/octet-stream + img；
 * - 文件名 `stem-插图-NNN.ext`，编号 1 基、<= 0 夹到 1、>= 1000 保留全部数位；
 * - stem 把 Windows 非法字符与 ISO control 换成 '_'，连续空白压单空格，首尾去空格 / 点 / 下划线，
 *   按 Unicode code point 截 40，空则「书籍」；扩展名只由字节内容决定，不看 path / alt / 标题后缀；
 * - 失败（读不到字节）一律 null，原字节不转码。
 *
 * 断言假设：
 * - PNG 必须完整 8 字节签名才算识别（只给 7 字节前缀按未知处理）；
 * - GIF 的版本标识必须整 6 字节命中，只给 `GIF` 三字节按未知处理；
 * - RIFF 第 5–8 字节是文件长度字段，内容任意都不影响识别；
 * - 「先清洗再截断」：非法字符替换后的下划线会占掉 40 码点的名额。
 */
internal class TextImageExportTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun body(size: Int = 16, seed: Int = 1): ByteArray = ByteArray(size) { (it * 17 + seed).toByte() }

    private val pngSignature: ByteArray = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private val png: ByteArray = pngSignature + body(seed = 1)
    private val jpeg: ByteArray = bytes(0xFF, 0xD8, 0xFF, 0xE0) + body(seed = 2)
    private val gif87a: ByteArray = ascii("GIF87a") + body(seed = 3)
    private val gif89a: ByteArray = ascii("GIF89a") + body(seed = 4)
    private val webp: ByteArray = ascii("RIFF") + bytes(0xEF, 0xBE, 0xAD, 0xDE) + ascii("WEBP") + body(seed = 5)
    private val bmp: ByteArray = bytes(0x42, 0x4D) + body(seed = 6)

    private fun export(
        source: ByteArray,
        title: String = "书名",
        number: Int = 1,
        path: String? = null,
        alt: String = "",
    ): TextImageExportPayload =
        TextImageExport.prepare(
            TextImage(key = "test:image", bytes = source, path = path, alt = alt),
            title,
            number,
        ) ?: throw AssertionError("expected export payload, title=" + title + ", number=" + number)

    // 1
    @Test
    fun maxTitleCodePointsIsForty() {
        assertEquals(40, TextImageExport.MAX_TITLE_CODE_POINTS)
    }

    // 2
    @Test
    fun pngIsDetectedFromFullSignature() {
        val payload = export(png)
        assertEquals("image/png", payload.mimeType)
        assertEquals("书名-插图-001.png", payload.fileName)
        // 原字节不转码。
        assertArrayEquals(png, payload.bytes)
    }

    // 3
    @Test
    fun jpegIsDetectedFromThreeBytePrefix() {
        val payload = export(jpeg)
        assertEquals("image/jpeg", payload.mimeType)
        assertEquals("书名-插图-001.jpg", payload.fileName)
        assertArrayEquals(jpeg, payload.bytes)
    }

    // 4
    @Test
    fun bothGifVersionsAreDetected() {
        val gif87 = export(gif87a)
        assertEquals("image/gif", gif87.mimeType)
        assertEquals("书名-插图-001.gif", gif87.fileName)
        val gif89 = export(gif89a)
        assertEquals("image/gif", gif89.mimeType)
        assertEquals("书名-插图-001.gif", gif89.fileName)
    }

    // 5
    @Test
    fun webpIsDetectedFromRiffContainer() {
        val payload = export(webp)
        assertEquals("image/webp", payload.mimeType)
        assertEquals("书名-插图-001.webp", payload.fileName)
        // RIFF 但 chunk 不是 WEBP（这里是 WAVE）→ 未知。
        val wave = ascii("RIFF") + bytes(0x24, 0x00, 0x00, 0x00) + ascii("WAVE") + body(seed = 7)
        val unknown = export(wave)
        assertEquals("application/octet-stream", unknown.mimeType)
        assertEquals("书名-插图-001.img", unknown.fileName)
    }

    // 6
    @Test
    fun bmpIsDetectedFromTwoByteMagic() {
        val payload = export(bmp)
        assertEquals("image/bmp", payload.mimeType)
        assertEquals("书名-插图-001.bmp", payload.fileName)
        assertArrayEquals(bmp, payload.bytes)
    }

    // 7
    @Test
    fun unknownBytesFallBackToOctetStream() {
        val zip = ascii("PK") + bytes(0x03, 0x04) + body(seed = 8)
        val payload = export(zip)
        assertEquals("application/octet-stream", payload.mimeType)
        assertEquals("书名-插图-001.img", payload.fileName)
        assertArrayEquals(zip, payload.bytes)
    }

    // 8
    @Test
    fun truncatedMagicPrefixIsUnknown() {
        // PNG 少的正是尾部 0A；GIF 少的是版本号；JPEG 少了第三个 FF。
        val cases = listOf(
            bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A),
            ascii("GIF"),
            bytes(0xFF, 0xD8),
            bytes(0x42),
        )
        for (candidate in cases) {
            val payload = export(candidate)
            assertEquals("application/octet-stream", payload.mimeType)
            assertEquals("书名-插图-001.img", payload.fileName)
        }
    }

    // 9
    @Test
    fun extensionIgnoresPathAndAltSuffix() {
        val payload = export(jpeg, path = "cover.png", alt = "封面.png")
        assertEquals("image/jpeg", payload.mimeType)
        assertEquals("书名-插图-001.jpg", payload.fileName)
    }

    // 10
    @Test
    fun titleSuffixDoesNotChangeExtension() {
        val payload = export(png, title = "封面.PNG")
        assertEquals("封面.PNG-插图-001.png", payload.fileName)
    }

    // 11
    @Test
    fun imageNumberIsOneBasedAndZeroPaddedToThreeDigits() {
        assertEquals("书名-插图-001.jpg", export(jpeg, number = 1).fileName)
        assertEquals("书名-插图-042.jpg", export(jpeg, number = 42).fileName)
        assertEquals("书名-插图-999.jpg", export(jpeg, number = 999).fileName)
    }

    // 12
    @Test
    fun nonPositiveImageNumberClampsToOne() {
        assertEquals("书名-插图-001.jpg", export(jpeg, number = 0).fileName)
        assertEquals("书名-插图-001.jpg", export(jpeg, number = -7).fileName)
    }

    // 13
    @Test
    fun fourDigitImageNumberKeepsAllDigits() {
        assertEquals("书名-插图-1000.jpg", export(jpeg, number = 1000).fileName)
        assertEquals("书名-插图-12345.jpg", export(jpeg, number = 12345).fileName)
    }

    // 14
    @Test
    fun illegalPathCharactersBecomeUnderscore() {
        val payload = export(png, title = "a/b\\c:d*e?f\"g<h>i|j")
        assertEquals("a_b_c_d_e_f_g_h_i_j-插图-001.png", payload.fileName)
    }

    // 15
    @Test
    fun isoControlCharactersBecomeUnderscore() {
        // 只用不含空白语义的控制符（NUL / US / DEL），避开「tab 算控制符还是空白」的歧义。
        // 控制符先换 '_'，随后首尾剥离：**尾部**由控制符换来的 '_' 与真下划线一样被剥掉，
        // 与用例 22（"///" → "书籍"）同一条口径；因此这里必须用中间位置的 '_' 做主语。
        assertEquals("书_名-插图-001.png", export(png, title = "书\u0000名\u001F\u007F").fileName)
        assertEquals("书_名_书-插图-001.png", export(png, title = "书\u0000名\u001F书").fileName)
    }

    // 16
    @Test
    fun whitespaceRunsCollapseToSingleSpace() {
        val payload = export(png, title = "  我的   书  ")
        assertEquals("我的 书-插图-001.png", payload.fileName)
    }

    // 17
    @Test
    fun leadingAndTrailingSpacesDotsUnderscoresAreTrimmed() {
        assertEquals("书-插图-001.png", export(png, title = "  ..书__.. ").fileName)
        assertEquals("书-插图-001.png", export(png, title = "_书_").fileName)
        assertEquals("我的 书-插图-001.png", export(png, title = "__我的 书__. ").fileName)
    }

    // 18
    @Test
    fun titleIsTruncatedByCodePointNotUtf16Unit() {
        // 39 个 'a' + 一个 emoji（代理对）刚好 40 码点：不能被劈成半个代理对。
        val paired = "a".repeat(39) + "\uD83D\uDE00" + "b"
        assertEquals("a".repeat(39) + "\uD83D\uDE00-插图-001.png", export(png, title = paired).fileName)
        // 40 个 emoji = 80 个 UTF-16 code unit，按码点算不该截。
        val fortyEmoji = "\uD83D\uDE00".repeat(40)
        assertEquals(fortyEmoji + "-插图-001.png", export(png, title = fortyEmoji).fileName)
        // 41 个 emoji 截到 40 个码点。
        assertEquals("\uD83D\uDE00".repeat(40) + "-插图-001.png", export(png, title = "\uD83D\uDE00".repeat(41)).fileName)
    }

    // 19
    @Test
    fun titleOfExactlyFortyCodePointsIsNotTruncated() {
        assertEquals("书".repeat(40) + "-插图-001.png", export(png, title = "书".repeat(40)).fileName)
        assertEquals("书".repeat(40) + "-插图-001.png", export(png, title = "书".repeat(41)).fileName)
        assertEquals("书".repeat(39) + "-插图-001.png", export(png, title = "书".repeat(39)).fileName)
    }

    // 20
    @Test
    fun sanitizationHappensBeforeTruncation() {
        // 第 21 位原本是 '/'：替换成 '_' 后才进入截断计数，落在中间的 _ 不会被首尾剥离吃掉。
        val title = "a".repeat(20) + "/" + "b".repeat(30)
        assertEquals("a".repeat(20) + "_" + "b".repeat(19) + "-插图-001.png", export(png, title = title).fileName)
    }

    // 21
    @Test
    fun truncationEdgeJunkIsTrimmedAgain() {
        // 截断正好切在 '_' / 空格上：末尾的垃圾要再剥一次，文件名不能以 '_' 或空格收尾。
        val underscoreEdge = "a".repeat(39) + "/" + "b".repeat(10)
        assertEquals("a".repeat(39) + "-插图-001.png", export(png, title = underscoreEdge).fileName)
        val spaceEdge = "a".repeat(39) + " " + "b".repeat(10)
        assertEquals("a".repeat(39) + "-插图-001.png", export(png, title = spaceEdge).fileName)
    }

    // 22
    @Test
    fun emptyOrBlankTitleFallsBackToBook() {
        for (title in listOf("", "   ", "///", "...")) {
            assertEquals("书籍-插图-001.png", export(png, title = title).fileName)
        }
    }

    // 23
    @Test
    fun unreachableBytesYieldNull() {
        assertNull(TextImageExport.prepare(TextImage(key = "test:empty", bytes = ByteArray(0)), "书名", 1))
        val missing = File(temporaryFolder.root, "missing.png")
        assertNull(TextImageExport.prepare(TextImage(key = "test:missing", path = missing.absolutePath), "书名", 1))
    }

    // 24
    @Test
    fun payloadBytesAreSnapshotNotAliasedToSource() {
        val source = png.copyOf()
        val originalLast = source.last().toInt()
        val payload = export(source)
        assertNotSame(source, payload.bytes)
        assertArrayEquals(source, payload.bytes)
        payload.bytes[payload.bytes.size - 1] = (originalLast + 1).toByte()
        assertEquals("改 payload 不应影响源数组", originalLast, source.last().toInt())
    }

    // 25
    @Test
    fun pendingSpaceBeforeIllegalCharacterIsKept() {
        // 非法字符前的空白要先落成半角空格，再写 '_'：'A : B' → "A _ B"。
        // 丢掉 pendingSpace 的实现会得到 "A_B"，把「书名里的停顿」吞掉。
        assertEquals("A _ B-插图-001.png", export(png, title = "A : B").fileName)
        assertEquals("A _ B _ C-插图-001.png", export(png, title = "A : B : C").fileName)
    }

    // 26
    @Test
    fun fileBackedImageIsExportedFromDisk() {
        val file = temporaryFolder.newFile("cover.png")
        file.writeBytes(png)
        val payload = TextImageExport.prepare(TextImage(key = "file:cover", path = file.absolutePath), "书名", 2)
            ?: throw AssertionError("expected file-backed export payload")
        assertEquals("image/png", payload.mimeType)
        assertEquals("书名-插图-002.png", payload.fileName)
        assertArrayEquals(png, payload.bytes)
        // 导出不改写源文件。
        assertArrayEquals(png, file.readBytes())
    }

    // 27
    @Test
    fun nbspAndIdeographicSpaceCollapseToSingleSpace() {
        // Kotlin 2.4.10 的 Char.isWhitespace() 含 isSpaceChar 分支：NBSP(U+00A0) 与
        // 全角空格(U+3000) 都算空白（已用 kotlin-stdlib 直接探针确认），应压成半角单空格。
        assertEquals("A B-插图-001.png", export(png, title = "A\u00A0B").fileName)
        assertEquals("A B-插图-001.png", export(png, title = "A\u3000B").fileName)
        // 混排、连续、首尾一起：只留一个半角空格。
        assertEquals("A B-插图-001.png", export(png, title = "A\u00A0\u3000 \u00A0B").fileName)
        assertEquals("我的 书-插图-001.png", export(png, title = "  我的\u00A0\u3000书  ").fileName)
    }

    // 28
    @Test
    fun nbspAndIdeographicSpaceKeepPendingSpaceBeforeIllegalCharacter() {
        // 压空白与替换非法字符互不吞并：NBSP 先落成空格，':' 再变 '_'，得 "A _ B"。
        assertEquals("A _ B-插图-001.png", export(png, title = "A\u00A0:\u3000B").fileName)
    }
}
