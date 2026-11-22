package app.particlebook.android.reader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 焦点类型判断是纯逻辑：失焦该停、可压低音量的失焦不该停。 */
class TtsAudioFocusTest {

    @Test
    fun parsesAndroidFocusCodes() {
        assertEquals(AudioFocusChange.LOSS, AudioFocusPolicy.parse(-1))
        assertEquals(AudioFocusChange.LOSS_TRANSIENT, AudioFocusPolicy.parse(-2))
        assertEquals(AudioFocusChange.LOSS_TRANSIENT_CAN_DUCK, AudioFocusPolicy.parse(-3))
        assertEquals(AudioFocusChange.GAIN, AudioFocusPolicy.parse(1))
        assertEquals(AudioFocusChange.UNKNOWN, AudioFocusPolicy.parse(42))
    }

    @Test
    fun pausesOnPermanentAndTransientLoss() {
        assertTrue(AudioFocusPolicy.shouldPause(AudioFocusChange.LOSS))
        assertTrue(AudioFocusPolicy.shouldPause(AudioFocusChange.LOSS_TRANSIENT))
    }

    @Test
    fun keepsReadingWhenFocusLossAllowsDucking() {
        // 「可以压低音量」的失焦（导航播报之类）压音量就够了，停掉反而打断整段朗读。
        assertFalse(AudioFocusPolicy.shouldPause(AudioFocusChange.LOSS_TRANSIENT_CAN_DUCK))
        assertFalse(AudioFocusPolicy.shouldPause(AudioFocusChange.GAIN))
        assertFalse(AudioFocusPolicy.shouldPause(AudioFocusChange.UNKNOWN))
    }
}
