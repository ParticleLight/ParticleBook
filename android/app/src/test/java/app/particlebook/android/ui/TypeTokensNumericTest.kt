package app.particlebook.android.ui

import androidx.compose.ui.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数值档（tabular figures）的 JVM 断言：不拉 Robolectric、不做 UI 测试，
 * 只读 [TextStyle] 的纯属性（fontFeatureSettings / fontSize / lineHeight / 字重 / 字距 / 字体）。
 *
 * 守三件事：
 * 1. 每个 *Number 档都真的开了 tnum——「等宽数字」靠字体特性，不是靠换字号；
 * 2. 每个档与它的 M3 源 slot 逐项同尺寸（字面值 + 整份 [TextStyle] 相等），
 *    迁移只换数字字形，不改柱状图列宽与朗读条那一行的布局；
 * 3. 基础字级自己没有被改成等宽——数值档是增量档，不是把全局字体换掉。
 *
 * 为什么不测「渲染后是否对齐」：那需要设备与字体实际栅格化；这里断言的是 token 层契约，
 * 字体是否真的带 tnum 由 TypeTokens.kt 的注释交代（换品牌字体时人工验）。
 */
class TypeTokensNumericTest {

    /** 数值档 → 源 slot → 声明的字号 / 行高（sp）。一条用例覆盖一个档，失败时能直接定位。 */
    private data class Slot(
        val name: String,
        val numeric: TextStyle,
        val base: TextStyle,
        val fontSizeSp: Float,
        val lineHeightSp: Float,
    )

    private val slots = listOf(
        Slot("heroNumber", AppType.heroNumber, AppTypography.displaySmall, 36f, 44f),
        Slot("summaryNumber", AppType.summaryNumber, AppTypography.titleMedium, 16f, 24f),
        Slot("inlineNumber", AppType.inlineNumber, AppTypography.labelLarge, 14f, 20f),
        Slot("compactNumber", AppType.compactNumber, AppTypography.labelMedium, 12f, 16f),
        Slot("tickNumber", AppType.tickNumber, AppTypography.labelSmall, 11f, 16f),
    )

    @Test
    fun `数值档全部开启 tabular figures`() {
        // 档位数先钉死：新增档位必须同步进 slots，否则这个列表会悄悄漏测。
        assertEquals(5, slots.size)
        for (slot in slots) {
            assertEquals(slot.name + " 的 fontFeatureSettings 必须是 tnum", "tnum", slot.numeric.fontFeatureSettings)
            assertTrue(
                slot.name + " 的 fontFeatureSettings 必须包含 tnum",
                slot.numeric.fontFeatureSettings.orEmpty().contains("tnum"),
            )
        }
    }

    @Test
    fun `hero 主数字与 displaySmall 同尺寸`() {
        assertNumericSlot(slots[0])
    }

    @Test
    fun `汇总数字与 titleMedium 同尺寸`() {
        assertNumericSlot(slots[1])
    }

    @Test
    fun `行内数字与 labelLarge 同尺寸`() {
        assertNumericSlot(slots[2])
    }

    @Test
    fun `紧凑行内数字与 labelMedium 同尺寸`() {
        assertNumericSlot(slots[3])
    }

    @Test
    fun `图表刻度数字与 labelSmall 同尺寸`() {
        assertNumericSlot(slots[4])
    }

    @Test
    fun `基础字级没有被改成等宽`() {
        // 数值档是增量：基础字级保持比例数字，正文的排版语义没被动过。
        assertNull(AppTypography.displaySmall.fontFeatureSettings)
        assertNull(AppTypography.titleMedium.fontFeatureSettings)
        assertNull(AppTypography.labelLarge.fontFeatureSettings)
        assertNull(AppTypography.labelMedium.fontFeatureSettings)
        assertNull(AppTypography.labelSmall.fontFeatureSettings)
    }

    /**
     * 单档断言：字号 / 行高先按字面对声明值，再与源 slot 逐项比；
     * 最后整份 [TextStyle] 相等——把「除 fontFeatureSettings 外零改动」压成一条可执行契约。
     */
    private fun assertNumericSlot(slot: Slot) {
        assertEquals(slot.name + " 字号必须保持声明值", slot.fontSizeSp.toDouble(), slot.numeric.fontSize.value.toDouble(), 0.001)
        assertEquals(slot.name + " 行高必须保持声明值", slot.lineHeightSp.toDouble(), slot.numeric.lineHeight.value.toDouble(), 0.001)
        // 与源 slot 比数值时一律取 .value 比 Float：TextUnit / FontWeight 都是 value class，
        // 走 assertEquals(Object, Object) 要靠装箱后的 equals，断言失败时的报错也不好读。
        assertEquals(slot.name + " 字号必须与源 slot 一致", slot.base.fontSize.value.toDouble(), slot.numeric.fontSize.value.toDouble(), 0.001)
        assertEquals(slot.name + " 行高必须与源 slot 一致", slot.base.lineHeight.value.toDouble(), slot.numeric.lineHeight.value.toDouble(), 0.001)
        assertEquals(slot.name + " 字距必须与源 slot 一致", slot.base.letterSpacing.value.toDouble(), slot.numeric.letterSpacing.value.toDouble(), 0.001)
        assertEquals(slot.name + " 字体必须与源 slot 一致", slot.base.fontFamily, slot.numeric.fontFamily)
        assertEquals(
            slot.name + " 除 tnum 外不得改动任何属性",
            slot.base.copy(fontFeatureSettings = "tnum"),
            slot.numeric,
        )
    }
}
