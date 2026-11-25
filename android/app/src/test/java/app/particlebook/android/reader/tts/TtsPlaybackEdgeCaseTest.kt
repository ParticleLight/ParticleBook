package app.particlebook.android.reader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TtsPlayback] 的边界组合测试：只钉**可观察行为**（spoken 序列 / state / onSentence 序列），
 * 不去碰系统 TTS（FakeSpeaker 从 TtsPlaybackTest 复刻一份，原文件保持不动）。
 *
 * 基线语义（见 TtsPlayback 类注释）：逐句推进；一句念完自动推进，末句念完回 Idle；
 * 暂停态 next/previous 只移光标不开念；pause 先置 paused 再 stop；stop 作废 utterance id；
 * start 返回 Boolean，fromIndex 夹进合法区间；rate 只透传给 speaker。
 */
class TtsPlaybackEdgeCaseTest {

    private class FakeSpeaker(var available: Boolean = true) : TtsSpeaker {
        val spoken = ArrayList<String>()
        val utteranceIds = ArrayList<String>()
        var stopped = 0
        var appliedRate = 1f
        var shutDown = false
        var lastDone: ((String) -> Unit)? = null

        val lastId: String get() = utteranceIds.lastOrNull() ?: ""

        override val ready: Boolean get() = available
        override fun setRate(rate: Float) { appliedRate = rate }
        override fun speak(text: String, utteranceId: String, onDone: (String) -> Unit) {
            spoken += text
            utteranceIds += utteranceId
            lastDone = onDone
        }
        override fun stop() { stopped += 1 }
        override fun shutdown() { shutDown = true }
    }

    private fun sentences(vararg texts: String): List<TtsSentence> =
        texts.map { text -> TtsSentence(0, text.length, text) }

    // ① 防的回归：暂停中挑句后继续，必须从**新句**开念且只念一次；若 next 在暂停态顺手开了口，
    // 或者 resume 拿旧句下标开会变成"念回上一句 + 新句重复念"。
    @Test
    fun resumeAfterPausedNextSpeaksNewSentenceExactlyOnce() {
        val speaker = FakeSpeaker()
        val states = ArrayList<TtsPlaybackState>()
        val seen = ArrayList<String?>()
        val playback = TtsPlayback(speaker, onStateChanged = { states += it }, onSentence = { seen += it?.text })

        playback.start(sentences("甲。", "乙。", "丙。"))
        playback.pause()
        playback.next()

        // 暂停态 next 只移光标：不开念，但要把新句子报给 UI（高亮跟着光标走）。
        assertEquals(listOf("甲。"), speaker.spoken)
        assertEquals(TtsPlaybackState.Paused(1, 3), playback.state)

        playback.resume()

        // 从新句开念，且只念一次。
        assertEquals(listOf("甲。", "乙。"), speaker.spoken)
        assertEquals(1, speaker.spoken.count { it == "乙。" })
        assertEquals(TtsPlaybackState.Playing(1, 3), playback.state)
        assertEquals(
            listOf(
                TtsPlaybackState.Playing(0, 3),
                TtsPlaybackState.Paused(0, 3),
                TtsPlaybackState.Paused(1, 3),
                TtsPlaybackState.Playing(1, 3),
            ),
            states,
        )
        // 甲报一次（start）、乙报两次（暂停态移光标 + 继续时开念）。
        assertEquals(listOf("甲。", "乙。", "乙。"), seen)

        // 暂停前那句的迟到回调不能把光标推到下一句（否则继续后立刻跳句）。
        playback.onUtteranceDone(speaker.utteranceIds.first())
        assertEquals(TtsPlaybackState.Playing(1, 3), playback.state)
        assertEquals(2, speaker.spoken.size)
    }

    // ② 防的回归：暂停态一路 next 到末句再 next 必须收敛成 stop→Idle；若 next 只在 Playing 分支处理，
    // 末句会卡在 Paused(末句) 里永远按不动，或者越界后 index 越出 lastIndex 再 start 异常。
    @Test
    fun pausedNextAtLastSentenceStopsIntoIdle() {
        val speaker = FakeSpeaker()
        val states = ArrayList<TtsPlaybackState>()
        val seen = ArrayList<String?>()
        val playback = TtsPlayback(speaker, onStateChanged = { states += it }, onSentence = { seen += it?.text })

        playback.start(sentences("甲。", "乙。", "丙。"))
        playback.pause()
        playback.next()
        playback.next()
        assertEquals(TtsPlaybackState.Paused(2, 3), playback.state)
        assertEquals(listOf("甲。"), speaker.spoken)

        val stoppedBefore = speaker.stopped
        playback.next()

        assertEquals(TtsPlaybackState.Idle, playback.state)
        assertNull(playback.current)
        assertEquals(listOf("甲。"), speaker.spoken)
        assertEquals("越过末句必须走真正的 stop（引擎队列也要清）", stoppedBefore + 1, speaker.stopped)
        assertEquals(
            listOf(
                TtsPlaybackState.Playing(0, 3),
                TtsPlaybackState.Paused(0, 3),
                TtsPlaybackState.Paused(1, 3),
                TtsPlaybackState.Paused(2, 3),
                TtsPlaybackState.Idle,
            ),
            states,
        )
        assertEquals(listOf("甲。", "乙。", "丙。", null), seen)
    }

    // ③ 防的回归：连点两次停止不允许开口、不允许回到 Playing、不允许回调成串放大。
    // stop() 现在对重复调用短路（已空闲且无句子直接返回），所以这里钉绝对幂等：
    // 第二次 stop 不得再发 onStateChanged，也不得再发 onSentence(null)。
    @Test
    fun doubleStopStaysIdleAndSilent() {
        val speaker = FakeSpeaker()
        val states = ArrayList<TtsPlaybackState>()
        val seen = ArrayList<String?>()
        val playback = TtsPlayback(speaker, onStateChanged = { states += it }, onSentence = { seen += it?.text })

        playback.start(sentences("甲。", "乙。"))
        playback.stop()
        val statesAfterFirstStop = states.size
        val seenAfterFirstStop = seen.size

        playback.stop()

        assertEquals(TtsPlaybackState.Idle, playback.state)
        assertNull(playback.current)
        assertEquals(listOf("甲。"), speaker.spoken)
        // 第二次 stop 不允许把状态机带回 Playing/Paused，也不允许重新开念。
        assertTrue(states.drop(statesAfterFirstStop).all { it == TtsPlaybackState.Idle })
        assertTrue(seen.drop(seenAfterFirstStop).all { it == null })
        assertEquals("重复 stop 不得再发 onStateChanged", 0, states.size - statesAfterFirstStop)
        assertEquals("重复 stop 不得再发 onSentence(null)", 0, seen.size - seenAfterFirstStop)
    }

    // ④ 防的回归：从中间下标开念 / 越界下标被夹回合法区间；夹取写错的表现是首句从 0 开始念，
    // 或者越界直接空指针/静默不念。
    @Test
    fun startFromIndexClampsIntoValidRange() {
        val speaker = FakeSpeaker()
        val seen = ArrayList<String?>()
        val playback = TtsPlayback(speaker, onSentence = { seen += it?.text })
        val page = sentences("甲。", "乙。", "丙。")

        assertTrue(playback.start(page, fromIndex = 1))
        assertEquals(listOf("乙。"), speaker.spoken)
        assertEquals(TtsPlaybackState.Playing(1, 3), playback.state)

        assertTrue("越界上界应夹到末句而不是拒绝启动", playback.start(page, fromIndex = 99))
        assertEquals(listOf("乙。", "丙。"), speaker.spoken)
        assertEquals(TtsPlaybackState.Playing(2, 3), playback.state)

        assertTrue("负下标应夹到首句", playback.start(page, fromIndex = -7))
        assertEquals(listOf("乙。", "丙。", "甲。"), speaker.spoken)
        assertEquals(TtsPlaybackState.Playing(0, 3), playback.state)

        assertEquals(listOf("乙。", "丙。", "甲。"), seen)

        // 单句页：任何 fromIndex 都只能落在 0，且 total 为 1。
        val single = sentences("独。")
        assertTrue(playback.start(single, fromIndex = 5))
        assertEquals(TtsPlaybackState.Playing(0, 1), playback.state)
        assertEquals("本页 1 / 1", playback.state.progressLabel())
    }

    // ⑤ 防的回归：playback 不得私自夹 rate（夹取/取整属于 speaker 与引擎）；一旦这里加了 coerceIn，
    // 低速率与高速率设置会被静默改成 0.5/2.0，用户设置看起来"存了但不生效"。
    @Test
    fun rateIsPassedThroughWithoutClamping() {
        val speaker = FakeSpeaker()
        val playback = TtsPlayback(speaker)

        listOf(0.1f, 0.5f, 1.0f, 2.0f, 4.5f).forEach { rate ->
            playback.setRate(rate)
            assertEquals("rate $rate 必须原样透传给 speaker", rate, speaker.appliedRate, 0f)
        }
        // 只透传，不触发朗读副作用。
        assertTrue(speaker.spoken.isEmpty())
        assertEquals(TtsPlaybackState.Idle, playback.state)
        assertEquals(0, speaker.stopped)
    }

    // ⑥ 防的回归：进度文案必须是"本页 n / total"且 n 从 1 起（下标 +1），Idle 必须给 null——
    // 早先 UI 自己掰下标，出现过 Idle 下显示"本页 0 / 0"、以及把 total 当全书句数的问题。
    @Test
    fun progressLabelFollowsState() {
        assertNull("Idle 不显示进度", TtsPlaybackState.Idle.progressLabel())
        assertEquals(-1, TtsPlaybackState.Idle.sentenceIndex)
        assertEquals(0, TtsPlaybackState.Idle.sentenceTotal)

        assertEquals("本页 1 / 3", TtsPlaybackState.Playing(0, 3).progressLabel())
        assertEquals("本页 3 / 3", TtsPlaybackState.Playing(2, 3).progressLabel())
        assertEquals("本页 2 / 3", TtsPlaybackState.Paused(1, 3).progressLabel())

        // 真实状态机上的取值：Playing 与 Paused 同名同值（暂停不改变"第几句"），stop 后必须回到 null。
        val speaker = FakeSpeaker()
        val playback = TtsPlayback(speaker)
        assertNull(playback.state.progressLabel())
        playback.start(sentences("甲。", "乙。", "丙。"), fromIndex = 1)
        assertEquals("本页 2 / 3", playback.state.progressLabel())
        playback.pause()
        assertEquals("本页 2 / 3", playback.state.progressLabel())
        playback.stop()
        assertNull(playback.state.progressLabel())
    }

    // ⑦ 防的回归：系统 TTS 没有续播，resume 必须重念整句并换新 utteranceId；若复用旧 id，
    // 旧句的迟到回调会被当成"当前句念完"直接跳句，表现为继续后少念一句。
    @Test
    fun resumeRepeatsSameSentenceWithFreshUtteranceId() {
        val speaker = FakeSpeaker()
        val playback = TtsPlayback(speaker)

        playback.start(sentences("甲。", "乙。"))
        playback.pause()
        playback.resume()

        assertEquals("同一句必须重念（无续播能力）", listOf("甲。", "甲。"), speaker.spoken)
        assertEquals("重念必须换新 utteranceId", 2, speaker.utteranceIds.distinct().size)
        assertEquals(TtsPlaybackState.Playing(0, 2), playback.state)

        // 旧 id 的迟到回调按 stale 丢弃：既不推进下标，也不重念。
        playback.onUtteranceDone(speaker.utteranceIds.first())
        assertEquals(TtsPlaybackState.Playing(0, 2), playback.state)
        assertEquals(2, speaker.spoken.size)

        // 新 id 的正常回调仍能推进，证明上面的丢弃没有把状态机弄哑。
        playback.onUtteranceDone(speaker.lastId)
        assertEquals(listOf("甲。", "甲。", "乙。"), speaker.spoken)
        assertEquals(TtsPlaybackState.Playing(1, 2), playback.state)
    }
}