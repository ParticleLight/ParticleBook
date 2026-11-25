package app.particlebook.android.ui

import org.junit.Assert.*
import org.junit.Test

/**
 * [annotationArgb] 的 JVM 单测：只覆盖纯字符串 -> ARGB 的换算，不碰 Compose 图形层。
 *
 * 覆盖三条路径：6 位十六进制补不透明 alpha、8 位十六进制原样保留 alpha、
 * 其余全部回退到 [EpubAnnotationPalette.argbFor] 的 32 位无符号值。
 */
class AnnotationArgbTest {

    /** 调色板四个 key 的期望值，与 EpubAnnotations.kt 里的字面量对齐（写死以便调色板漂移时报红）。 */
    private val yellowArgb = 0x55FFD54FL
    private val greenArgb = 0x5566BB6AL
    private val blueArgb = 0x5564B5F6L
    private val pinkArgb = 0x55F48FB1L

    private fun assertFallbackToYellow(input: String?) {
        val label = if (input == null) "null" else "<" + input + ">"
        assertEquals("输入 " + label + " 应回退到黄色", yellowArgb, annotationArgb(input))
    }

    // ---- 6 位十六进制：补 0xFF 前缀 ----

    @Test
    fun sixDigitHex_becomesOpaqueArgb() {
        assertEquals(0xFFFFF59DL, annotationArgb("#FFF59D"))
    }

    @Test
    fun sixDigitHex_lowercase_matchesUppercase() {
        assertEquals(0xFFFFF59DL, annotationArgb("#fff59d"))
        assertEquals(annotationArgb("#FFF59D"), annotationArgb("#fff59d"))
    }

    @Test
    fun sixDigitHex_blackAndRed_stayOpaque() {
        assertEquals(0xFF000000L, annotationArgb("#000000"))
        assertEquals(0xFF123456L, annotationArgb("#123456"))
    }

    // ---- 8 位十六进制：alpha 原样保留，不被覆盖 ----

    @Test
    fun eightDigitHex_keepsAlpha() {
        assertEquals(0x80FFF59DL, annotationArgb("#80FFF59D"))
    }

    @Test
    fun eightDigitHex_lowercaseAnywhere_stillParses() {
        assertEquals(0x80FFF59DL, annotationArgb("#80fff59d"))
        assertEquals(0x80FFF59DL, annotationArgb("#80FfF59d"))
    }

    @Test
    fun eightDigitHex_zeroAlphaIsNotReplacedByOpaque() {
        // 若实现无条件 or 0xFF000000，这里会变成 0xFFFFF59D，必须报红。
        assertEquals(0x00FFF59DL, annotationArgb("#00FFF59D"))
    }

    @Test
    fun eightDigitHex_fullAlphaRoundTrips() {
        assertEquals(0xFFFFFFFFL, annotationArgb("#FFFFFFFF"))
    }

    // ---- trim ----

    @Test
    fun surroundingWhitespace_isTrimmed() {
        assertEquals(0xFFFFF59DL, annotationArgb("  #FFF59D  "))
        assertEquals(0xFFFFF59DL, annotationArgb("\t#FFF59D\n"))
        assertEquals(0x80FFF59DL, annotationArgb(" #80FFF59D "))
    }

    // ---- 调色板 key ----

    @Test
    fun paletteKeys_mapToPaletteArgb() {
        assertEquals(yellowArgb, annotationArgb("yellow"))
        assertEquals(greenArgb, annotationArgb("green"))
        assertEquals(blueArgb, annotationArgb("blue"))
        assertEquals(pinkArgb, annotationArgb("pink"))
    }

    @Test
    fun paletteKeys_agreeWithPaletteLookup() {
        for (key in listOf("yellow", "green", "blue", "pink")) {
            assertEquals(
                "key=$key 应与 EpubAnnotationPalette.argbFor 一致",
                EpubAnnotationPalette.argbFor(key).toLong() and 0xFFFFFFFFL,
                annotationArgb(key),
            )
        }
    }

    @Test
    fun paletteKeys_areTrimmedToo() {
        assertEquals(greenArgb, annotationArgb(" green "))
    }

    // ---- null / 空 / 空白 -> yellow ----

    @Test
    fun null_isYellow() {
        assertFallbackToYellow(null)
    }

    @Test
    fun emptyString_isYellow() {
        assertFallbackToYellow("")
    }

    @Test
    fun blankString_isYellow() {
        assertFallbackToYellow("   ")
        assertFallbackToYellow("\t\n")
    }

    // ---- 未知 key -> yellow ----

    @Test
    fun unknownKey_isYellow() {
        assertFallbackToYellow("purple")
        assertFallbackToYellow("黄")
    }

    @Test
    fun paletteKeysAreCaseSensitive_uppercaseIsUnknown() {
        assertFallbackToYellow("YELLOW")
        assertFallbackToYellow("Green")
    }

    // ---- 非法长度 -> yellow ----

    @Test
    fun wrongLengthHex_isYellow() {
        assertFallbackToYellow("#FFF")
        assertFallbackToYellow("#FFFFFFFFF") // 9 位
        assertFallbackToYellow("#")
        assertFallbackToYellow("#1234567890ABCDEF")
    }

    @Test
    fun hexWithoutHash_isYellow() {
        assertFallbackToYellow("FFF59D")
        assertFallbackToYellow("0xFFF59D")
    }

    // ---- 非法字符 -> yellow ----

    @Test
    fun nonHexCharacters_areYellow() {
        assertFallbackToYellow("#FFF59Z")
        assertFallbackToYellow("#+12345")
        assertFallbackToYellow("#-12345")
        assertFallbackToYellow("# FF F5 9D")
        assertFallbackToYellow("#ＦＦＦ５９Ｄ") // 全角字符不是 ASCII 十六进制
    }

    // ---- 值域 ----

    @Test
    fun resultAlwaysFitsUnsigned32() {
        val samples = listOf(
            null, "", "   ", "#FFF59D", "#80FFF59D", "#00FFF59D", "#FFFFFFFF",
            "#000000", "#fff59d", " #FFF59D ", "#FFF", "#FFFFFFFFF", "#FFF59Z",
            "#+12345", "yellow", "green", "blue", "pink", "purple", "YELLOW", "0x123456",
        )
        for (sample in samples) {
            val argb = annotationArgb(sample)
            assertTrue(
                "annotationArgb($sample) = $argb 越界",
                argb >= 0L && argb <= 0xFFFFFFFFL,
            )
        }
    }
}
