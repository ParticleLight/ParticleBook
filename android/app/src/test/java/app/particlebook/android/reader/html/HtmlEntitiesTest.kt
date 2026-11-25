package app.particlebook.android.reader.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [HtmlEntities.decode] 的行为测试：命名实体、十进制 / 十六进制数字引用，以及三条容错规则
 * （未知实体、超长引用、缺分号一律原样保留；数字引用非法码点也原样保留）。
 *
 * 所有断言都对着 HtmlEntities.kt 的真实实现写，不按 HTML5 规范「应该」怎样写：
 * 实现只认 NAMED 表里那批名字、只做单遍解码、引用体长度按 `semicolon - ampIndex <= 32` 判定。
 */
class HtmlEntitiesTest {

    // ---------- 早退与平凡输入 ----------

    @Test
    fun emptyString_decodesToEmptyString() {
        assertEquals("", HtmlEntities.decode(""))
    }

    @Test
    fun textWithoutAmpersand_isReturnedAsIs() {
        val raw = "Hello, ParticleBook 阅读器 2.2.1"
        assertEquals(raw, HtmlEntities.decode(raw))
        // 没有 & 时实现直接 return raw（同一次调用返回同一实例），保留这条快速路径。
        assertSame(raw, HtmlEntities.decode(raw))
    }

    @Test
    fun loneAmpersand_isPreserved() {
        assertEquals("&", HtmlEntities.decode("&"))
        assertEquals("a&b", HtmlEntities.decode("a&b"))
        assertEquals("结尾的&", HtmlEntities.decode("结尾的&"))
    }

    // ---------- 基础命名实体 ----------

    @Test
    fun basicNamedEntities_decodeToAsciiSymbols() {
        assertEquals("&", HtmlEntities.decode("&amp;"))
        assertEquals("<", HtmlEntities.decode("&lt;"))
        assertEquals(">", HtmlEntities.decode("&gt;"))
        assertEquals("\"", HtmlEntities.decode("&quot;"))
        assertEquals("'", HtmlEntities.decode("&apos;"))
        assertEquals("&<>\"'", HtmlEntities.decode("&amp;&lt;&gt;&quot;&apos;"))
    }

    @Test
    fun nbsp_decodesToNoBreakSpace() {
        val decoded = HtmlEntities.decode("&nbsp;")
        assertEquals(1, decoded.length)
        assertEquals(0x00A0, decoded[0].code)
        assertNotEquals(" ", decoded)
    }

    @Test
    fun dashAndEllipsis_decodeToTypographyCharacters() {
        assertEquals("\u2014", HtmlEntities.decode("&mdash;"))
        assertEquals("\u2013", HtmlEntities.decode("&ndash;"))
        assertEquals("\u2026", HtmlEntities.decode("&hellip;"))
        assertEquals("\u2014\u2026", HtmlEntities.decode("&mdash;&hellip;"))
    }

    @Test
    fun edgeSpacesAndPunctuation_decodeToExpectedScalars() {
        assertEquals("\u2002\u2003\u2009", HtmlEntities.decode("&ensp;&emsp;&thinsp;"))
        assertEquals("\u00AB读\u00BB", HtmlEntities.decode("&laquo;读&raquo;"))
        assertEquals("\u201C引\u201D", HtmlEntities.decode("&ldquo;引&rdquo;"))
    }

    // ---------- 数字引用 ----------

    @Test
    fun decimalReference_65_decodesToUppercaseA() {
        assertEquals("A", HtmlEntities.decode("&#65;"))
        assertEquals("ABC", HtmlEntities.decode("&#65;&#66;&#67;"))
    }

    @Test
    fun decimalReference_chineseCodepoint() {
        assertEquals("\u4E2D", HtmlEntities.decode("&#20013;"))
        assertEquals("中文", HtmlEntities.decode("&#20013;&#25991;"))
    }

    @Test
    fun hexadecimalReference_chineseCodepoint() {
        assertEquals("\u4E2D", HtmlEntities.decode("&#x4E2D;"))
        assertEquals("\u4E2D", HtmlEntities.decode("&#x4e2d;"))
        assertEquals("\u4E2D", HtmlEntities.decode("&#x04E2D;"))
    }

    @Test
    fun uppercaseHexPrefix_emojiBecomesSurrogatePair() {
        val decoded = HtmlEntities.decode("&#X1F600;")
        assertEquals(2, decoded.length)
        assertEquals("\uD83D\uDE00", decoded)
        // 十进制写法得到同一个字符，length 依旧是 2。
        assertEquals(decoded, HtmlEntities.decode("&#128512;"))
        assertEquals(2, HtmlEntities.decode("&#128512;").length)
    }

    @Test
    fun zeroCodepoint_isPreservedVerbatim() {
        assertEquals("&#0;", HtmlEntities.decode("&#0;"))
        assertEquals("&#x0;", HtmlEntities.decode("&#x0;"))
        assertEquals("a&#0;b", HtmlEntities.decode("a&#0;b"))
    }

    @Test
    fun surrogateCodepoints_arePreservedVerbatim() {
        assertEquals("&#xD800;", HtmlEntities.decode("&#xD800;"))
        assertEquals("&#xDFFF;", HtmlEntities.decode("&#xDFFF;"))
        assertEquals("&#55296;", HtmlEntities.decode("&#55296;"))
        assertEquals("&#xDBFF;", HtmlEntities.decode("&#xDBFF;"))
    }

    @Test
    fun codepointBeyondUnicodeMax_isPreservedVerbatim() {
        assertEquals("&#x110000;", HtmlEntities.decode("&#x110000;"))
        assertEquals("&#1114112;", HtmlEntities.decode("&#1114112;"))
        // 上限本身合法：U+10FFFF 解成一个代理对。
        assertEquals(2, HtmlEntities.decode("&#x10FFFF;").length)
    }

    @Test
    fun negativeCodepoint_isPreservedVerbatim() {
        assertEquals("&#-1;", HtmlEntities.decode("&#-1;"))
        assertEquals("&#x-1;", HtmlEntities.decode("&#x-1;"))
    }

    @Test
    fun signedNumericBodies_areRejected() {
        // 数字引用只认纯数字：前导 '+' / '-' / 空白都不算合法引用，整段原样保留。
        assertEquals("&#+65;", HtmlEntities.decode("&#+65;"))
        assertEquals("&#x+41;", HtmlEntities.decode("&#x+41;"))
        assertEquals("&#-65;", HtmlEntities.decode("&#-65;"))
        assertEquals("&# 65;", HtmlEntities.decode("&# 65;"))
        assertEquals("&#x 41;", HtmlEntities.decode("&#x 41;"))
    }

    // ---------- 容错规则：未知、缺分号、超长 ----------

    @Test
    fun unknownNamedEntity_isPreservedVerbatim() {
        assertEquals("&foo;", HtmlEntities.decode("&foo;"))
        assertEquals("&nbspx;", HtmlEntities.decode("&nbspx;"))
        assertEquals("前&unknown;后", HtmlEntities.decode("前&unknown;后"))
    }

    @Test
    fun referenceWithoutSemicolon_isPreservedVerbatim() {
        assertEquals("&amp", HtmlEntities.decode("&amp"))
        assertEquals("&amp 后面没有分号", HtmlEntities.decode("&amp 后面没有分号"))
        assertEquals("&#65", HtmlEntities.decode("&#65"))
        assertEquals("&#x4E2D", HtmlEntities.decode("&#x4E2D"))
    }

    @Test
    fun overlongReferenceBody_isPreservedVerbatim() {
        // 32 个字符的未知名字，远超出正常实体长度。
        val longName = "&" + "x".repeat(32) + ";"
        assertEquals(longName, HtmlEntities.decode(longName))
        // 分号离 & 太远（这里相距 41），整段按普通文本保留。
        val farSemicolon = "&amp" + "z".repeat(40) + ";"
        assertEquals(farSemicolon, HtmlEntities.decode(farSemicolon))
    }

    @Test
    fun referenceBodyLengthBoundary_matchesImplementationLimit() {
        // 引用体长度上限是 MAX_REFERENCE_LENGTH（32）个字符，前导零不改变数值。
        val body32 = "&#" + "0".repeat(29) + "65;"
        assertEquals(32, body32.indexOf(';') - body32.indexOf('&') - 1)
        assertEquals("A", HtmlEntities.decode(body32))
        // 33 个字符已经超出上限，整段原样保留。
        val body33 = "&#" + "0".repeat(30) + "65;"
        assertEquals(33, body33.indexOf(';') - body33.indexOf('&') - 1)
        assertEquals(body33, HtmlEntities.decode(body33))
    }

    @Test
    fun malformedReferenceBodies_arePreservedVerbatim() {
        assertEquals("&;", HtmlEntities.decode("&;"))
        assertEquals("&#;", HtmlEntities.decode("&#;"))
        assertEquals("&#x;", HtmlEntities.decode("&#x;"))
        assertEquals("&#xyz;", HtmlEntities.decode("&#xyz;"))
    }

    // ---------- 单遍解码、不递归 ----------

    @Test
    fun decodingIsSinglePass_andDoesNotRecurse() {
        assertEquals("&lt;", HtmlEntities.decode("&amp;lt;"))
        assertEquals(4, HtmlEntities.decode("&amp;lt;").length)
        assertEquals("&amp;", HtmlEntities.decode("&amp;amp;"))
        assertEquals("&#38;lt;", HtmlEntities.decode("&amp;#38;lt;"))
    }

    @Test
    fun ampersandsInReferenceBody_areHandledLeftToRight() {
        // 第一个 & 的引用体是 "&amp"（未知），落回字面量后再从第二个 & 解出 "&"。
        assertEquals("&&", HtmlEntities.decode("&&amp;"))
        assertEquals("&amp&", HtmlEntities.decode("&amp&amp;"))
    }

    // ---------- 大小写与别名 ----------

    @Test
    fun uppercaseAliases_areRecognised() {
        assertEquals("&", HtmlEntities.decode("&AMP;"))
        assertEquals("<", HtmlEntities.decode("&LT;"))
        assertEquals(">", HtmlEntities.decode("&GT;"))
        assertEquals("\"", HtmlEntities.decode("&QUOT;"))
    }

    @Test
    fun entityNames_areCaseSensitive() {
        assertEquals("&Amp;", HtmlEntities.decode("&Amp;"))
        assertEquals("&NBSP;", HtmlEntities.decode("&NBSP;"))
        assertEquals("&Mdash;", HtmlEntities.decode("&Mdash;"))
        // Prime / prime 是两个不同的码点，两个都在表里。
        assertEquals("\u2033", HtmlEntities.decode("&Prime;"))
        assertEquals("\u2032", HtmlEntities.decode("&prime;"))
    }

    @Test
    fun rareNamedEntities_areDecoded() {
        assertEquals("\u2030", HtmlEntities.decode("&permil;"))
        assertEquals("\u2295", HtmlEntities.decode("&oplus;"))
        assertEquals("\u2297", HtmlEntities.decode("&otimes;"))
        assertEquals("\u2234", HtmlEntities.decode("&there4;"))
        assertEquals("\u2286", HtmlEntities.decode("&sube;"))
        assertEquals("\u2666", HtmlEntities.decode("&diams;"))
        assertEquals("\u20AC", HtmlEntities.decode("&euro;"))
    }

    // ---------- 组合与上下文 ----------

    @Test
    fun inlineMixedReferences_inOneSentence() {
        assertEquals("a&b<c", HtmlEntities.decode("a&amp;b&lt;c"))
        assertEquals("if (a && b) x < y", HtmlEntities.decode("if (a &amp;&amp; b) x &lt; y"))
    }

    @Test
    fun referencesAtBothEnds_areDecoded() {
        assertEquals("&abc&", HtmlEntities.decode("&amp;abc&amp;"))
        assertEquals("<中>", HtmlEntities.decode("&lt;&#20013;&gt;"))
    }

    @Test
    fun nonAsciiContext_keepsSurroundingTextIntact() {
        assertEquals("第一章\u2014序\u2026", HtmlEntities.decode("第一章&mdash;序&hellip;"))
        assertEquals("粒子书\u00A0阅读", HtmlEntities.decode("粒子书&nbsp;阅读"))
    }

    @Test
    fun invalidReference_doesNotAbortLaterDecoding() {
        assertEquals("&#xD800;&A", HtmlEntities.decode("&#xD800;&amp;&#65;"))
        assertEquals("&foo;A", HtmlEntities.decode("&foo;&#65;"))
        assertEquals("&;中", HtmlEntities.decode("&;&#x4E2D;"))
    }
}
