package app.particlebook.android.reader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [TtsPlaybackState] 的进度文案契约。
 *
 * progressLabel 从属性改成带默认参数的单位函数后：EPUB / PDF 仍走默认「本页」，
 * 文本阅读器按段落朗读传「本段」。这里锁住默认单位、1-based 展示、整串格式与 Idle 的空文案。
 */
class TtsPlaybackStateTest {

    // ---------- 默认单位 ----------

    @Test
    fun defaultProgressUnitIsExactlyTheTwoCharacterWordBenYe() {
        assertEquals("本页", TtsPlaybackState.DEFAULT_PROGRESS_UNIT)
        assertEquals(2, TtsPlaybackState.DEFAULT_PROGRESS_UNIT.length)
    }

    // ---------- Idle ----------

    @Test
    fun idleProgressLabelIsNull() {
        assertNull(TtsPlaybackState.Idle.progressLabel())
    }

    @Test
    fun idleProgressLabelStaysNullEvenWhenParagraphUnitIsPassed() {
        assertNull(TtsPlaybackState.Idle.progressLabel("本段"))
        assertNull(TtsPlaybackState.Idle.progressLabel(""))
    }

    @Test
    fun idleSentencePositionUsesMinusOneAndZeroSentinels() {
        assertEquals(-1, TtsPlaybackState.Idle.sentenceIndex)
        assertEquals(0, TtsPlaybackState.Idle.sentenceTotal)
    }

    // ---------- Playing ----------

    @Test
    fun playingFirstOfThreeRendersFullLabelWithSpacesAndSlash() {
        assertEquals("本页 1 / 3", TtsPlaybackState.Playing(0, 3).progressLabel())
    }

    @Test
    fun playingLastOfThreeRendersThreeOfThree() {
        assertEquals("本页 3 / 3", TtsPlaybackState.Playing(2, 3).progressLabel())
    }

    @Test
    fun singleSentenceUnitRendersOneOfOne() {
        assertEquals("本页 1 / 1", TtsPlaybackState.Playing(0, 1).progressLabel())
    }

    @Test
    fun omittingUnitEqualsPassingDefaultUnitExplicitly() {
        val state: TtsPlaybackState = TtsPlaybackState.Playing(1, 4)
        assertEquals(
            state.progressLabel(TtsPlaybackState.DEFAULT_PROGRESS_UNIT),
            state.progressLabel(),
        )
    }

    // ---------- Paused ----------

    @Test
    fun pausedLabelFollowsSameRenderingRuleAsPlaying() {
        assertEquals("本页 2 / 5", TtsPlaybackState.Paused(1, 5).progressLabel())
        assertEquals(
            TtsPlaybackState.Playing(1, 5).progressLabel(),
            TtsPlaybackState.Paused(1, 5).progressLabel(),
        )
    }

    // ---------- 显式单位 ----------

    @Test
    fun explicitParagraphUnitReplacesPageUnit() {
        assertEquals("本段 1 / 3", TtsPlaybackState.Playing(0, 3).progressLabel("本段"))
    }

    @Test
    fun explicitParagraphUnitAlsoAppliesToPaused() {
        assertEquals("本段 4 / 6", TtsPlaybackState.Paused(3, 6).progressLabel("本段"))
    }

    // ---------- 位置访问器 ----------

    @Test
    fun sentenceIndexAndTotalReturnConstructorArguments() {
        val playing = TtsPlaybackState.Playing(4, 9)
        assertEquals(4, playing.sentenceIndex)
        assertEquals(9, playing.sentenceTotal)

        val paused = TtsPlaybackState.Paused(2, 7)
        assertEquals(2, paused.sentenceIndex)
        assertEquals(7, paused.sentenceTotal)
    }

    // ---------- 1-based 展示 ----------

    @Test
    fun lastSentenceIndexAlwaysDisplaysOneBasedTotal() {
        var total = 1
        while (total <= 6) {
            val last = TtsPlaybackState.Playing(total - 1, total)
            assertEquals("本页 " + total + " / " + total, last.progressLabel())
            total += 1
        }
    }

    // ---------- 值语义 ----------

    @Test
    fun statesWithSameCoordinatesAreEqual() {
        assertEquals(TtsPlaybackState.Playing(1, 2), TtsPlaybackState.Playing(1, 2))
        assertEquals(TtsPlaybackState.Paused(1, 2), TtsPlaybackState.Paused(1, 2))
        assertEquals(
            TtsPlaybackState.Playing(1, 2).hashCode(),
            TtsPlaybackState.Playing(1, 2).hashCode(),
        )
    }

    @Test
    fun playingAndPausedWithSameCoordinatesAreNotEqual() {
        assertNotEquals(TtsPlaybackState.Playing(1, 2), TtsPlaybackState.Paused(1, 2))
        assertNotEquals(TtsPlaybackState.Playing(1, 2), TtsPlaybackState.Playing(2, 1))
    }

    @Test
    fun idleIsASingleTonAcrossReads() {
        assertSame(TtsPlaybackState.Idle, TtsPlaybackState.Idle)
        assertEquals(TtsPlaybackState.Idle, TtsPlaybackState.Idle)
    }

    // ---------- 边界 ----------

    @Test
    fun emptyUnitDoesNotThrowAndStillReturnsALabel() {
        val label = TtsPlaybackState.Playing(0, 3).progressLabel("")
        assertNotNull(label)
    }

    @Test
    fun largeTotalsRenderWithoutThousandsSeparatorsOrOverflow() {
        assertEquals("本页 1000 / 1000", TtsPlaybackState.Playing(999, 1000).progressLabel())
        assertEquals(
            "本页 1 / " + Int.MAX_VALUE,
            TtsPlaybackState.Playing(0, Int.MAX_VALUE).progressLabel(),
        )
    }
}
