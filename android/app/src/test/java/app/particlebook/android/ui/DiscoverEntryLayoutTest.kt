package app.particlebook.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P15-V9：200% 字号（fontScale >= 1.5）发现入口切纵向三行；100% 及 150% 以下保持横排。
 * 该函数是与 Compose 解耦的纯函数，这里直接覆盖阈值两侧与边界值。
 */
class DiscoverEntryLayoutTest {

    @Test
    fun defaultAndSmallFontScalesKeepRow() {
        assertEquals(DiscoverEntryLayout.ROW, discoverEntryLayoutFor(0.85f))
        assertEquals(DiscoverEntryLayout.ROW, discoverEntryLayoutFor(1.0f))
        assertEquals(DiscoverEntryLayout.ROW, discoverEntryLayoutFor(1.3f))
    }

    @Test
    fun justBelowThresholdKeepsRow() {
        assertEquals(DiscoverEntryLayout.ROW, discoverEntryLayoutFor(1.49f))
    }

    @Test
    fun thresholdExactlySwitchesToColumn() {
        assertEquals(DiscoverEntryLayout.COLUMN, discoverEntryLayoutFor(1.5f))
    }

    @Test
    fun twoHundredPercentFontScaleUsesColumn() {
        assertEquals(DiscoverEntryLayout.COLUMN, discoverEntryLayoutFor(2.0f))
        assertEquals(DiscoverEntryLayout.COLUMN, discoverEntryLayoutFor(2.5f))
    }
}
