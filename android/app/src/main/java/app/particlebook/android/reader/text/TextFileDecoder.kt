package app.particlebook.android.reader.text

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 解码结果：正文与最终采用的字符集名（诊断 / 日志用）。 */
internal data class TextDecodeResult(val text: String, val charsetName: String)

/**
 * txt 字节 → 正文的纯 JVM 解码器（不依赖 Android，可直接跑 JVM 单测）。
 *
 * 判定顺序固定：
 * 1. BOM 优先（UTF-8 / UTF-16LE / UTF-16BE，BOM 字节不参与解码；
 *    FF FE 00 00 这种 UTF-32LE 前缀按 UTF-16LE 处理）；
 * 2. 无 BOM 时先严格 UTF-8（REPORT），一旦出现非法字节就整段退 GB18030；
 * 3. 去残留 U+FEFF、CRLF/CR → LF、按 code point 安全截断到 maxChars。
 *
 * 截断在归一化之后做，计数口径是 UTF-16 码元（即 String.length），
 * 与 [TextDocumentParser] 的 charOffsets / charCount 口径一致。
 */
internal object TextFileDecoder {

    /** 单次解码的字符上限：超出截断，避免把几百 MB 的 txt 读进内存。 */
    const val MAX_TEXT_CHARS: Int = 4_000_000

    private const val BOM_CODE_POINT = 0xFEFF
    private const val CARRIAGE_RETURN = '\r'.code
    private const val LINE_FEED = '\n'.code

    private val UTF8: Charset = Charsets.UTF_8
    private val GB18030: Charset = Charset.forName("GB18030")

    private class Bom(val size: Int, val charset: Charset) {
        val charsetName: String = charset.name()
    }

    fun decode(bytes: ByteArray, maxChars: Int = MAX_TEXT_CHARS): TextDecodeResult {
        if (bytes.isEmpty()) return TextDecodeResult("", UTF8.name())
        val limit = maxChars.coerceAtLeast(0)
        val bom = detectBom(bytes)
        val charsetName: String
        val raw: String
        if (bom != null) {
            // BOM 命中就按它解码，不再做 UTF-8 试探。
            charsetName = bom.charsetName
            raw = String(bytes, bom.size, bytes.size - bom.size, bom.charset)
        } else {
            val strictUtf8 = decodeStrict(bytes, UTF8)
            if (strictUtf8 != null) {
                charsetName = UTF8.name()
                raw = strictUtf8
            } else {
                charsetName = GB18030.name()
                raw = String(bytes, GB18030)
            }
        }
        return TextDecodeResult(normalize(raw, limit), charsetName)
    }

    /** BOM 探测；返回值里的 size 是 BOM 字节数，解码时跳过。 */
    private fun detectBom(bytes: ByteArray): Bom? {
        if (bytes.size >= 3 &&
            bytes[0].unsigned() == 0xEF &&
            bytes[1].unsigned() == 0xBB &&
            bytes[2].unsigned() == 0xBF
        ) {
            return Bom(3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0].unsigned() == 0xFF && bytes[1].unsigned() == 0xFE) {
            // FF FE 00 00（UTF-32LE BOM）也落在这里，按 UTF-16LE 解码。
            return Bom(2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0].unsigned() == 0xFE && bytes[1].unsigned() == 0xFF) {
            return Bom(2, Charsets.UTF_16BE)
        }
        return null
    }

    /** 严格解码：任何 malformed / unmappable 都返回 null，调用方据此退回 GB18030。 */
    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? =
        try {
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: CharacterCodingException) {
            null
        }

    /**
     * 残留 BOM 字符剥离 + 换行归一 + 按 code point 截断，一遍扫描完成。
     * 截断点落在代理对中间时整对丢弃，不写半个字符。
     */
    private fun normalize(raw: String, maxChars: Int): String {
        val capacity = if (raw.length <= maxChars) raw.length else maxChars + 1
        val out = StringBuilder(capacity)
        var index = 0
        while (index < raw.length) {
            var codePoint = raw.codePointAt(index)
            index += Character.charCount(codePoint)
            if (codePoint == BOM_CODE_POINT) continue
            if (codePoint == CARRIAGE_RETURN) {
                codePoint = LINE_FEED
                if (index < raw.length && raw[index] == '\n') index++
            }
            val width = Character.charCount(codePoint)
            if (out.length + width > maxChars) break
            out.appendCodePoint(codePoint)
        }
        return out.toString()
    }

    private fun Byte.unsigned(): Int = toInt() and 0xFF
}
