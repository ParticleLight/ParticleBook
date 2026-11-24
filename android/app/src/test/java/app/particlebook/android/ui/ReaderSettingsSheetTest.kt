package app.particlebook.android.ui

import app.particlebook.android.settings.SettingsSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读设置取值规则的契约测试。
 *
 * 直接打真实实现（[normalizeFontSize] / [normalizeLineHeight] / 文案 / 预览行高），
 * 不再复制一份规则到测试里——复制出来的「契约」改一边就漂了。
 */
class ReaderSettingsSheetTest {

    // ── 与设置 schema 的一致性：两边改一边就会红 ─────────────────────────
    @Test
    fun fontRangeMatchesSettingsSchema() {
        val spec = requireNotNull(SettingsSchema.find("fontSize"))
        assertEquals(ReaderTypographySpec.MIN_FONT_SIZE_SP, spec.minValue!!.toFloat(), 0.001f)
        assertEquals(ReaderTypographySpec.MAX_FONT_SIZE_SP, spec.maxValue!!.toFloat(), 0.001f)
        assertEquals(ReaderTypographySpec.DEFAULT_FONT_SIZE_SP, (spec.defaultValue as Int).toFloat(), 0.001f)
        assertTrue("fontSize 必须是书级可覆盖键", spec.bookScoped)
    }

    @Test
    fun lineHeightRangeMatchesSettingsSchema() {
        val spec = requireNotNull(SettingsSchema.find("lineHeight"))
        assertEquals(ReaderTypographySpec.MIN_LINE_HEIGHT, spec.minValue!!.toFloat(), 0.001f)
        assertEquals(ReaderTypographySpec.MAX_LINE_HEIGHT, spec.maxValue!!.toFloat(), 0.001f)
        assertEquals(ReaderTypographySpec.DEFAULT_LINE_HEIGHT, (spec.defaultValue as Double).toFloat(), 0.001f)
        assertTrue("lineHeight 必须是书级可覆盖键", spec.bookScoped)
    }

    // ── 字号夹取 / 吸附 ──────────────────────────────────────────────
    @Test
    fun fontSizeClampsToSchemaRange() {
        assertEquals(12f, normalizeFontSize(-40f))
        assertEquals(12f, normalizeFontSize(0f))
        assertEquals(32f, normalizeFontSize(99f))
        assertEquals(32f, normalizeFontSize(Float.MAX_VALUE))
    }

    @Test
    fun fontSizeSnapsToWholeSp() {
        assertEquals(16f, normalizeFontSize(16.2f))
        assertEquals(17f, normalizeFontSize(16.5f))
        assertEquals(17f, normalizeFontSize(17.000001f))
    }

    @Test
    fun fontSizeRejectsDirtyFloats() {
        assertEquals(ReaderTypographySpec.DEFAULT_FONT_SIZE_SP, normalizeFontSize(Float.NaN))
        assertEquals(ReaderTypographySpec.DEFAULT_FONT_SIZE_SP, normalizeFontSize(Float.POSITIVE_INFINITY))
        assertEquals(ReaderTypographySpec.DEFAULT_FONT_SIZE_SP, normalizeFontSize(Float.NEGATIVE_INFINITY))
    }

    // ── 行高夹取 / 吸附 ──────────────────────────────────────────────
    @Test
    fun lineHeightClampsToSchemaRange() {
        assertEquals(1.2f, normalizeLineHeight(0.5f), 0.0001f)
        assertEquals(1.2f, normalizeLineHeight(-3f), 0.0001f)
        assertEquals(3.0f, normalizeLineHeight(9f), 0.0001f)
    }

    @Test
    fun lineHeightSnapsToOneDecimal() {
        assertEquals(1.4f, normalizeLineHeight(1.44f), 0.0001f)
        assertEquals(1.5f, normalizeLineHeight(1.455f), 0.0001f)
        assertEquals(2.0f, normalizeLineHeight(1.9999999f), 0.0001f)
    }

    @Test
    fun lineHeightRejectsDirtyFloats() {
        assertEquals(ReaderTypographySpec.DEFAULT_LINE_HEIGHT, normalizeLineHeight(Float.NaN), 0.0001f)
        assertEquals(ReaderTypographySpec.DEFAULT_LINE_HEIGHT, normalizeLineHeight(Float.POSITIVE_INFINITY), 0.0001f)
    }

    @Test
    fun normalizeIsIdempotent() {
        val size = normalizeFontSize(19.4f)
        assertEquals(size, normalizeFontSize(size))
        val height = normalizeLineHeight(2.26f)
        assertEquals(height, normalizeLineHeight(height), 0.0001f)
    }

    // ── 文案 ────────────────────────────────────────────────────────
    @Test
    fun fontSizeLabelHasNoTrailingZero() {
        assertEquals("18 sp", fontSizeLabel(18f))
        assertEquals("18 sp", fontSizeLabel(17.9999f))
        assertEquals("12 sp", fontSizeLabel(0f))
        assertEquals("32 sp", fontSizeLabel(99f))
    }

    @Test
    fun lineHeightLabelAlwaysOneDecimal() {
        assertEquals("1.8 倍", lineHeightLabel(1.8f))
        assertEquals("2.0 倍", lineHeightLabel(2f))
        assertEquals("1.2 倍", lineHeightLabel(0.1f))
        assertEquals("3.0 倍", lineHeightLabel(7f))
    }

    // ── 预览行高 ─────────────────────────────────────────────────────
    @Test
    fun previewLineHeightMultipliesAndClamps() {
        assertEquals(32.4f, previewLineHeightSp(18f, 1.8f), 0.001f)
        // 脏输入先各自夹取，再相乘：96f 是 32 sp × 3.0× 的上限。
        assertEquals(96f, previewLineHeightSp(999f, 999f), 0.001f)
        // 1 sp 被夹到 12 sp、11× 被夹到 3.0×：预览永远不显示排不出来的组合。
        assertEquals(36f, previewLineHeightSp(1f, 11f), 0.001f)
    }

    @Test
    fun previewTextIsNotBlank() {
        assertTrue(READER_PREVIEW_TEXT.isNotBlank())
    }
}
