package app.particlebook.android.reader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPlaybackTest {

    private class FakeSpeaker(var available: Boolean = true) : TtsSpeaker {
        val spoken = ArrayList<String>()
        var stopped = 0
        var appliedRate = 1f
        var shutDown = false
        override val ready: Boolean get() = available
        override fun setRate(rate: Float) { appliedRate = rate }
        override fun speak(text: String, utteranceId: String, onDone: (String) -> Unit) {
            spoken += text
            lastDone = onDone
            lastId = utteranceId
        }
        override fun stop() { stopped += 1 }
        override fun shutdown() { shutDown = true }
        var lastDone: ((String) -> Unit)? = null
        var lastId: String = ""
    }

    private fun sentences(vararg texts: String): List<TtsSentence> =
        texts.map { text -> TtsSentence(0, text.length, text) }

    @Test
    fun startSpeaksFirstSentence() {
        val speaker = FakeSpeaker()
        val states = ArrayList<TtsPlaybackState>()
        val playback = TtsPlayback(speaker, onStateChanged = { states += it })
        playback.start(sentences("甲。", "乙。"))
        assertEquals(listOf("甲。"), speaker.spoken)
        assertEquals(TtsPlaybackState.Playing(0, 2), playback.state)
        assertEquals(TtsPlaybackState.Playing(0, 2), states.last())
    }

    @Test
    fun doneAdvancesToNextSentence() {
        val speaker = FakeSpeaker()
        val playback = TtsPlayback(speaker)
        playback.start(sentences("甲。", "乙。"))
        val id = speaker.lastId
        playback.onUtteranceDone(id)
        assertEquals(listOf("甲。", "乙。"), speaker.spoken)
        assertEquals(TtsPlaybackState.Playing(1, 2), playback.state)
    }

    @Test
    fun finishingLastSentenceReturnsToIdle() {
        val speaker = FakeSpeaker()
        val playback = TtsPlayback(speaker)
        playback.start(sentences("甲。"))
        playback.onUtteranceDone(speaker.lastId)
        assertEquals(TtsPlaybackState.Idle, playback.state)
        assertNull(playback.current)
    }

    @Test
    fun staleUtteranceIdIsIgnored() {
        val speaker = FakeSpeaker()
        val playback = TtsPlayback(speaker)
        playback.start(sentences("甲。", "乙。"))
        playback.onUtteranceDone("pb-tts-999")
        assertEquals(TtsPlaybackState.Playing(0, 2), playback.state)
    }

    @Test
    fun nextWhilePausedOnlyMovesCursor() {
        val speaker = FakeSpeaker()
        val states = ArrayList<TtsPlaybackState>()
        val playback = TtsPlayback(speaker, onStateChanged = { states += it })
        playback.start(sentences("甲。", "乙。", "丙。"))
        playback.pause()
        val spokenBefore = speaker.spoken.size
        playback.next()
        assertEquals(spokenBefore, speaker.spoken.size)
        assertEquals(TtsPlaybackState.Paused(1, 3), playback.state)
        assertEquals(TtsPlaybackState.Paused(1, 3), states.last())
        playback.previous()
        assertEquals(TtsPlaybackState.Paused(0, 3), playback.state)
        assertEquals(spokenBefore, speaker.spoken.size)
    }

    @Test
    fun nextWhilePlayingSpeaksImmediately() {
        val speaker = FakeSpeaker()
        val playback = TtsPlayback(speaker)
        playback.start(sentences("甲。", "乙。", "丙。"))
        playback.next()
        assertEquals(listOf("甲。", "乙。"), speaker.spoken)
        assertEquals(TtsPlaybackState.Playing(1, 3), playback.state)
    }

    @Test
    fun previousOnFirstSentenceStaysPutAndRespeaks() {
        val speaker = FakeSpeaker()
        val playback = TtsPlayback(speaker)
        playback.start(sentences("甲。", "乙。"))
        playback.previous()
        assertEquals(listOf("甲。", "甲。"), speaker.spoken)
        assertEquals(TtsPlaybackState.Playing(0, 2), playback.state)
    }

    @Test
    fun nextOnLastSentenceStopsPlayback() {
        val speaker = FakeSpeaker()
        val playback = TtsPlayback(speaker)
        playback.start(sentences("甲。", "乙。"))
        playback.onUtteranceDone(speaker.lastId)
        playback.next()
        assertEquals(TtsPlaybackState.Idle, playback.state)
    }

    @Test
    fun pauseStopsAndResumeRepeatsSentence() {
        val speaker = FakeSpeaker()
        val playback = TtsPlayback(speaker)
        playback.start(sentences("甲。", "乙。"))
        playback.pause()
        assertEquals(TtsPlaybackState.Paused(0, 2), playback.state)
        assertEquals(1, speaker.stopped)
        playback.resume()
        assertEquals(listOf("甲。", "甲。"), speaker.spoken)
        assertEquals(TtsPlaybackState.Playing(0, 2), playback.state)
    }

    @Test
    fun pausedDoneDoesNotAdvance() {
        val speaker = FakeSpeaker()
        val playback = TtsPlayback(speaker)
        playback.start(sentences("甲。", "乙。"))
        val id = speaker.lastId
        playback.pause()
        playback.onUtteranceDone(id)
        assertEquals(TtsPlaybackState.Paused(0, 2), playback.state)
    }

    @Test
    fun nextAndPreviousMoveIndex() {
        val speaker = FakeSpeaker()
        val playback = TtsPlayback(speaker)
        playback.start(sentences("甲。", "乙。", "丙。"))
        playback.next()
        assertEquals(TtsPlaybackState.Playing(1, 3), playback.state)
        playback.previous()
        assertEquals(TtsPlaybackState.Playing(0, 3), playback.state)
    }

    @Test
    fun nextOnLastSentenceStops() {
        val speaker = FakeSpeaker()
        val playback = TtsPlayback(speaker)
        playback.start(sentences("甲。"))
        playback.next()
        assertEquals(TtsPlaybackState.Idle, playback.state)
    }

    @Test
    fun engineNotReadyKeepsIdle() {
        val speaker = FakeSpeaker(available = false)
        val playback = TtsPlayback(speaker)
        playback.start(sentences("甲。"))
        assertEquals(TtsPlaybackState.Idle, playback.state)
        assertTrue(speaker.spoken.isEmpty())
    }

    @Test
    fun stopClearsState() {
        val speaker = FakeSpeaker()
        val playback = TtsPlayback(speaker)
        playback.start(sentences("甲。", "乙。"))
        playback.stop()
        assertEquals(TtsPlaybackState.Idle, playback.state)
        assertTrue(speaker.stopped >= 1)
    }

    @Test
    fun sentenceCallbackReportsCurrentSentence() {
        val speaker = FakeSpeaker()
        val seen = ArrayList<String?>()
        val playback = TtsPlayback(speaker, onSentence = { seen += it?.text })
        playback.start(sentences("甲。", "乙。"))
        playback.onUtteranceDone(speaker.lastId)
        playback.stop()
        assertEquals(listOf("甲。", "乙。", null), seen)
    }

    @Test
    fun rateIsForwarded() {
        val speaker = FakeSpeaker()
        val playback = TtsPlayback(speaker)
        playback.setRate(1.5f)
        assertEquals(1.5f, speaker.appliedRate, 0.0001f)
    }

    @Test
    fun startReportsWhetherItActuallyStarted() {
        // 返回值是调用方提示「引擎不可用」的唯一依据：不能靠"启动后再看 state 是不是 Idle"。
        val ready = TtsPlayback(FakeSpeaker())
        assertTrue(ready.start(sentences("甲。")))
        val missingEngine = TtsPlayback(FakeSpeaker(available = false))
        assertTrue(!missingEngine.start(sentences("甲。")))
        val emptyPage = TtsPlayback(FakeSpeaker())
        assertTrue(!emptyPage.start(emptyList()))
    }

    @Test
    fun doneArrivingAfterStopDoesNotPublishAgain() {
        // stop 之后当前 id 的回调必须作废：否则会多一次 publish + onSentence(null)，
        // 将来给 onSentence(null) 加副作用就会重复触发。
        val speaker = FakeSpeaker()
        val states = ArrayList<TtsPlaybackState>()
        val seen = ArrayList<String?>()
        val playback = TtsPlayback(speaker, onStateChanged = { states += it }, onSentence = { seen += it?.text })
        playback.start(sentences("甲。", "乙。"))
        val id = speaker.lastId
        playback.stop()
        val afterStop = states.size
        playback.onUtteranceDone(id)
        assertEquals(afterStop, states.size)
        assertEquals(listOf("甲。", null), seen)
        assertEquals(TtsPlaybackState.Idle, playback.state)
    }
}
