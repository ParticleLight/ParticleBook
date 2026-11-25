package app.particlebook.android.reader.text

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class TextFileDecoderTest {

    private val gb18030: Charset = Charset.forName("GB18030")

    private fun concat(vararg parts: ByteArray): ByteArray {
        var size = 0
        for (part in parts) size += part.size
        val out = ByteArray(size)
        var position = 0
        for (part in parts) {
            part.copyInto(out, position)
            position += part.size
        }
        return out
    }

    private val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val utf16LeBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val utf16BeBom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    @Test
    fun maxCharsContractIsFourMillion() {
        assertEquals(4_000_000, TextFileDecoder.MAX_TEXT_CHARS)
    }

    @Test
    fun decodeStripsUtf8Bom() {
        val payload = "中文 reader".toByteArray(Charsets.UTF_8)
        val result = TextFileDecoder.decode(concat(utf8Bom, payload))
        assertEquals("中文 reader", result.text)
        assertEquals("UTF-8", result.charsetName)
        assertFalse(result.text.contains('\uFEFF'))
    }

    @Test
    fun decodeUtf16LeBom() {
        val payload = "中文 A".toByteArray(Charsets.UTF_16LE)
        val result = TextFileDecoder.decode(concat(utf16LeBom, payload))
        assertEquals("中文 A", result.text)
        assertEquals("UTF-16LE", result.charsetName)
    }

    @Test
    fun decodeUtf16BeBom() {
        val payload = "中文 B".toByteArray(Charsets.UTF_16BE)
        val result = TextFileDecoder.decode(concat(utf16BeBom, payload))
        assertEquals("中文 B", result.text)
        assertEquals("UTF-16BE", result.charsetName)
    }

    @Test
    fun decodeTreatsUtf32LeBomPrefixAsUtf16Le() {
        // FF FE 00 00 是 UTF-32LE 的 BOM，但契约要求按 UTF-16LE 处理：
        // 跳过 2 字节 BOM 后，剩余 00 00 41 00 解成 U+0000 U+0041。
        val result = TextFileDecoder.decode(
            concat(utf16LeBom, byteArrayOf(0x00, 0x00), "A".toByteArray(Charsets.UTF_16LE)),
        )
        assertEquals("UTF-16LE", result.charsetName)
        assertEquals("\u0000A", result.text)
    }

    @Test
    fun decodeUsesStrictUtf8WhenNoBom() {
        val result = TextFileDecoder.decode("无 BOM 的 UTF-8 文本".toByteArray(Charsets.UTF_8))
        assertEquals("无 BOM 的 UTF-8 文本", result.text)
        assertEquals("UTF-8", result.charsetName)
    }

    @Test
    fun decodeFallsBackToGb18030OnInvalidUtf8() {
        // "中文测试" 的 GB18030 字节里 D6 D0 不构成合法 UTF-8 序列。
        val payload = "中文测试".toByteArray(gb18030)
        val result = TextFileDecoder.decode(payload)
        assertEquals("GB18030", result.charsetName)
        assertEquals("中文测试", result.text)
    }

    @Test
    fun decodeGb18030KeepsAsciiRuns() {
        val result = TextFileDecoder.decode("abc 中文 123".toByteArray(gb18030))
        assertEquals("GB18030", result.charsetName)
        assertEquals("abc 中文 123", result.text)
    }

    @Test
    fun decodeTruncatedUtf8SequenceFallsBackToGb18030() {
        // 截断的 UTF-8 多字节序列同样算非法字节，整段退 GB18030。
        val truncated = "中".toByteArray(Charsets.UTF_8).copyOf(2)
        val result = TextFileDecoder.decode(truncated)
        assertEquals("GB18030", result.charsetName)
        assertEquals(1, result.text.length)
        assertNotEquals("中", result.text)
    }

    @Test
    fun decodeNormalizesCrlfAndLoneCr() {
        val result = TextFileDecoder.decode("a\r\nb\rc\nd".toByteArray(Charsets.UTF_8))
        assertEquals("a\nb\nc\nd", result.text)
        assertEquals("UTF-8", result.charsetName)
    }

    @Test
    fun decodeStripsResidualBomCodePoint() {
        // 位置 1 的 EF BB BF 不是文件头 BOM，但解码后仍是 U+FEFF，必须清掉。
        val result = TextFileDecoder.decode("a\uFEFFb".toByteArray(Charsets.UTF_8))
        assertEquals("ab", result.text)
    }

    @Test
    fun decodeStripsRepeatedBom() {
        val result = TextFileDecoder.decode(concat(utf8Bom, utf8Bom, "x".toByteArray(Charsets.UTF_8)))
        assertEquals("x", result.text)
        assertEquals("UTF-8", result.charsetName)
    }

    @Test
    fun decodeTruncatesAtMaxChars() {
        val result = TextFileDecoder.decode("abcdef".toByteArray(Charsets.UTF_8), maxChars = 3)
        assertEquals("abc", result.text)
        assertEquals(3, result.text.length)
    }

    @Test
    fun decodeKeepsTextThatFitsExactly() {
        val result = TextFileDecoder.decode("abcdef".toByteArray(Charsets.UTF_8), maxChars = 6)
        assertEquals("abcdef", result.text)
    }

    @Test
    fun decodeTruncationHappensAfterNewlineNormalization() {
        // "a\r\nb" 归一化后是 3 个字符，因此 maxChars=3 不截断、maxChars=2 截到 "a\n"。
        val bytes = "a\r\nb".toByteArray(Charsets.UTF_8)
        assertEquals("a\nb", TextFileDecoder.decode(bytes, maxChars = 3).text)
        assertEquals("a\n", TextFileDecoder.decode(bytes, maxChars = 2).text)
    }

    @Test
    fun decodeTruncationKeepsSurrogatePairWhole() {
        val bytes = "a\uD83D\uDE00b".toByteArray(Charsets.UTF_8)
        assertEquals("a", TextFileDecoder.decode(bytes, maxChars = 1).text)
        // 只剩 1 个码元空位时整对丢弃，不写半个代理对。
        val dropped = TextFileDecoder.decode(bytes, maxChars = 2).text
        assertEquals("a", dropped)
        assertFalse(Character.isHighSurrogate(dropped.last()))
        assertEquals("a\uD83D\uDE00", TextFileDecoder.decode(bytes, maxChars = 3).text)
        assertEquals("a\uD83D\uDE00b", TextFileDecoder.decode(bytes, maxChars = 4).text)
    }

    @Test
    fun decodeZeroMaxCharsYieldsEmptyText() {
        val result = TextFileDecoder.decode("abc".toByteArray(Charsets.UTF_8), maxChars = 0)
        assertEquals("", result.text)
        assertEquals("UTF-8", result.charsetName)
    }

    @Test
    fun decodeEmptyBytesReturnsUtf8AndEmptyText() {
        val result = TextFileDecoder.decode(ByteArray(0))
        assertEquals("", result.text)
        assertEquals("UTF-8", result.charsetName)
    }

    @Test
    fun decodeBomOnlyYieldsEmptyText() {
        val result = TextFileDecoder.decode(utf8Bom)
        assertEquals("", result.text)
        assertEquals("UTF-8", result.charsetName)
        assertTrue(result.text.isEmpty())
    }

    @Test
    fun decodeUtf16LeBomOnlyYieldsEmptyText() {
        val result = TextFileDecoder.decode(utf16LeBom)
        assertEquals("", result.text)
        assertEquals("UTF-16LE", result.charsetName)
    }

    // ---- maxChars 极端值：capacity 预估不能因 maxChars + 1 溢出而抛 NegativeArraySizeException ----

    /** 容量分支（raw.length <= maxChars）走 raw.length，极小输入配 Int.MAX_VALUE 不抛。 */
    @Test
    fun decodeWithIntMaxValueMaxCharsKeepsSmallInput() {
        val result = TextFileDecoder.decode("hello".toByteArray(Charsets.UTF_8), maxChars = Int.MAX_VALUE)
        assertEquals("hello", result.text)
        assertEquals("UTF-8", result.charsetName)
    }

    /** 5000 个字符在 Int.MAX_VALUE 上限下完整保留，容量预估与最终长度一致。 */
    @Test
    fun decodeWithIntMaxValueMaxCharsKeepsFiveThousandChars() {
        val payload = "a".repeat(5_000)
        val result = TextFileDecoder.decode(payload.toByteArray(Charsets.UTF_8), maxChars = Int.MAX_VALUE)
        assertEquals(5_000, result.text.length)
        assertEquals(payload, result.text)
        assertEquals("UTF-8", result.charsetName)
    }

    /** 负数 maxChars 先 coerceAtLeast(0)，与 maxChars = 0 一样得到空串（既有行为不能改坏）。 */
    @Test
    fun decodeWithNegativeMaxCharsYieldsEmptyText() {
        val result = TextFileDecoder.decode("abc".toByteArray(Charsets.UTF_8), maxChars = -5)
        assertEquals("", result.text)
        assertEquals("UTF-8", result.charsetName)
    }

    /** Int.MAX_VALUE 上限下仍然照常剥离 BOM、归一换行，不做任何截断。 */
    @Test
    fun decodeWithIntMaxValueMaxCharsStillNormalizesBomAndNewlines() {
        val bytes = concat(utf8Bom, "a\r\nb".toByteArray(Charsets.UTF_8))
        val result = TextFileDecoder.decode(bytes, maxChars = Int.MAX_VALUE)
        assertEquals("a\nb", result.text)
        assertEquals("UTF-8", result.charsetName)
    }
}
