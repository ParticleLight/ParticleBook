package app.particlebook.android.reader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第28批：连续朗读会话（方案B，onUnitFinished 同步交接）的回归。
 *
 * 边界：
 * - 只断言 [TtsPlayback] 的公开可观察量（spoken / utteranceIds / stopped / shutDown / state / onSentence），
 *   不读源码字符串、不碰私有字段，也不在单测里伪造 AudioManager 计数。
 * - "整场只申请一次焦点"由真机旧/新对照证明（旧包负控实测 4 申请/4 释放）；这里钉的是"会话何时收口"：
 *   段间绝不调用 speaker.stop()（生产实现会 abandon），只有文件末尾、拒绝接续、接续失败或异常才收口。
 * - [FakeSpeaker] 使用实际 [TtsSpeaker] 签名 speak(text, utteranceId, onDone)，不另建焦点假模型。
 */
class TtsContinuityTest {

    private class FakeSpeaker(var available: Boolean = true) : TtsSpeaker {
        override val ready: Boolean get() = available
        val spoken = ArrayList<String>()
        val utteranceIds = ArrayList<String>()
        var stopped = 0
        var shutDown = false
        var lastDone: ((String) -> Unit)? = null
        var lastId: String = ""

        override fun setRate(rate: Float) = Unit

        override fun speak(text: String, utteranceId: String, onDone: (String) -> Unit) {
            spoken += text
            utteranceIds += utteranceId
            lastDone = onDone
            lastId = utteranceId
        }

        override fun stop() {
            stopped += 1
        }

        override fun shutdown() {
            shutDown = true
        }
    }

    private fun sentences(vararg texts: String): List<TtsSentence> =
        texts.map { text -> TtsSentence(0, text.length, text) }

    private fun FakeSpeaker.completeCurrent() {
        val done = lastDone ?: error("没有待完成的句子")
        done(lastId)
    }

    /**
     * 正控：回调同步 start 下一单元时，段间不 stop、不发布 Idle、onSentence 不出现 null。
     * 负控：
     * - 段末仍走原 stop → stopped 变 1（真机上就是一次 abandon）；
     * - 段间先发 Idle 再异步接续 → states 出现 Idle（控制条闪烁、宿主看到空闲）；
     * - 不递增代次就接续 → 旧 id 仍可能匹配（由 duplicateAndStale 与 reentrant 两条覆盖）。
     */
    @Test
    fun synchronousHandoffKeepsSessionOpenAcrossUnits() {
        val speaker = FakeSpeaker()
        val states = ArrayList<TtsPlaybackState>()
        val seen = ArrayList<String?>()
        val units = listOf(
            sentences("甲一。", "甲二。"),
            sentences("乙一。", "乙二。"),
            sentences("丙一。", "丙二。"),
        )
        var nextUnit = 1
        lateinit var playback: TtsPlayback
        playback = TtsPlayback(
            speaker,
            onStateChanged = { states += it },
            onSentence = { seen += it?.text },
            onUnitFinished = {
                if (nextUnit < units.size) {
                    assertTrue("同步接续必须真的开念", playback.start(units[nextUnit++]))
                }
            },
        )

        playback.start(units[0])
        speaker.completeCurrent() // 甲一 → 甲二
        speaker.completeCurrent() // 甲二 → 同步 start 乙一

        assertEquals(listOf("甲一。", "甲二。", "乙一。"), speaker.spoken)
        assertEquals("段间不得 stop：生产实现会 abandon 焦点", 0, speaker.stopped)
        assertEquals("段间不得发布 Idle：控制条不应闪烁", 0, states.count { it is TtsPlaybackState.Idle })
        assertEquals(listOf("甲一。", "甲二。", "乙一。"), seen)
        assertEquals("每句一个新 utteranceId，旧 id 不得复用", 3, speaker.utteranceIds.distinct().size)

        speaker.completeCurrent() // 乙一 → 乙二
        speaker.completeCurrent() // 乙二 → 同步 start 丙一
        speaker.completeCurrent() // 丙一 → 丙二
        speaker.completeCurrent() // 丙二 → 无下一段 → 收口

        assertEquals("只有文件末尾收口一次", 1, speaker.stopped)
        assertEquals(TtsPlaybackState.Idle, playback.state)
        assertEquals("丙二。", speaker.spoken.last())
        assertNull(seen.last())
        assertNull(playback.current)
    }

    /** 默认构造（EPUB/PDF 不接回调）：自然末句仍照常 stop，行为与 ec74a95 一致。 */
    @Test
    fun defaultConstructorStillStopsAtNaturalEnd() {
        val speaker = FakeSpeaker()
        val playback = TtsPlayback(speaker)
        playback.start(sentences("甲。"))
        speaker.completeCurrent()

        assertEquals(1, speaker.stopped)
        assertEquals(TtsPlaybackState.Idle, playback.state)
        assertNull(playback.current)
    }

    /** 回调拒绝接续（EOF / 空单元）时必须收口：start 返回 false 不能留下持焦的 Playing。 */
    @Test
    fun rejectedContinuationAtEofStillReleases() {
        val speaker = FakeSpeaker()
        lateinit var playback: TtsPlayback
        var continued: Boolean? = null
        playback = TtsPlayback(speaker, onUnitFinished = { continued = playback.start(emptyList()) })
        playback.start(sentences("甲。"))
        speaker.completeCurrent()

        assertFalse(continued!!)
        assertEquals(1, speaker.stopped)
        assertEquals(TtsPlaybackState.Idle, playback.state)
    }

    /** 接续时引擎已不可用：start 返回 false，尾部必须真实 stop 释放。 */
    @Test
    fun readyFailureDuringHandoffStillReleases() {
        val speaker = FakeSpeaker()
        lateinit var playback: TtsPlayback
        var continued: Boolean? = null
        playback = TtsPlayback(
            speaker,
            onUnitFinished = {
                speaker.available = false
                continued = playback.start(sentences("乙。"))
            },
        )
        playback.start(sentences("甲。"))
        speaker.completeCurrent()

        assertFalse(continued!!)
        assertEquals("接续失败不得留下持焦的 Playing", 1, speaker.stopped)
        assertEquals(TtsPlaybackState.Idle, playback.state)
    }

    /** 旧单元的 done 在下一单元已开始后重放：不得再触发完成回调，也不得推进/收口。 */
    @Test
    fun duplicateAndStaleDoneCannotReenterFinishedUnit() {
        val speaker = FakeSpeaker()
        var finished = 0
        lateinit var playback: TtsPlayback
        playback = TtsPlayback(
            speaker,
            onUnitFinished = {
                finished += 1
                if (finished == 1) assertTrue(playback.start(sentences("乙。")))
            },
        )
        playback.start(sentences("甲。"))
        val oldDone = speaker.lastDone!!
        val oldId = speaker.lastId
        oldDone(oldId) // 甲完成 → 同步 start 乙

        assertEquals(1, finished)
        assertEquals(0, speaker.stopped)
        val spokenAfterHandoff = speaker.spoken.size

        oldDone(oldId) // 旧回调重放
        playback.onUtteranceDone(oldId) // 旧 id 直接重放

        assertEquals("重复旧 done 不得再触发完成回调", 1, finished)
        assertEquals(spokenAfterHandoff, speaker.spoken.size)
        assertEquals(0, speaker.stopped)
        assertEquals(TtsPlaybackState.Playing(0, 1), playback.state)
    }

    /**
     * 回调内同步重入旧 done（模拟迟到回调与完成回调同栈）：代次必须先递增作废当前 id。
     * 负控：若完成前不递增 utteranceSeq，重入会再次匹配并递归触发完成回调。
     */
    @Test
    fun reentrantOldDoneInsideHandoffIsIgnored() {
        val speaker = FakeSpeaker()
        var finished = 0
        lateinit var oldId: String
        lateinit var playback: TtsPlayback
        playback = TtsPlayback(
            speaker,
            onUnitFinished = {
                finished += 1
                playback.onUtteranceDone(oldId)
            },
        )
        playback.start(sentences("甲。"))
        oldId = speaker.lastId
        speaker.completeCurrent()

        assertEquals("旧 done 重入不得再触发完成回调", 1, finished)
        assertEquals(1, speaker.stopped)
        assertEquals(TtsPlaybackState.Idle, playback.state)
    }

    /** shutdown 后的迟到回调不得触发完成回调；关闭路径本身不再走 stop。 */
    @Test
    fun doneAfterShutdownDoesNotInvokeUnitCallback() {
        val speaker = FakeSpeaker()
        var finished = 0
        val playback = TtsPlayback(speaker, onUnitFinished = { finished++ })
        playback.start(sentences("甲。"))
        val id = speaker.lastId

        playback.shutdown()
        playback.onUtteranceDone(id)

        assertEquals(0, finished)
        assertEquals(0, speaker.stopped)
        assertTrue(speaker.shutDown)
        assertEquals(TtsPlaybackState.Idle, playback.state)
    }

    /** 回调主动 stop：旧单元的尾部 stop 不得再执行（否则会多一次 publish/onSentence(null)）。 */
    @Test
    fun callbackStopIsNotFollowedByTrailingStop() {
        val speaker = FakeSpeaker()
        val states = ArrayList<TtsPlaybackState>()
        val seen = ArrayList<String?>()
        lateinit var playback: TtsPlayback
        playback = TtsPlayback(
            speaker,
            onStateChanged = { states += it },
            onSentence = { seen += it?.text },
            onUnitFinished = { playback.stop() },
        )
        playback.start(sentences("甲。"))
        speaker.completeCurrent()

        assertEquals(1, speaker.stopped)
        assertEquals(TtsPlaybackState.Idle, playback.state)
        assertEquals("Idle 只发布一次", 1, states.count { it is TtsPlaybackState.Idle })
        assertEquals(listOf("甲。", null), seen)
    }

    /**
     * 回调主动 pause：尾部不得用 stop 把 Paused 覆盖成 Idle。
     * 负控：若只看"代次未变"而不看"仍 Playing"，暂停会被旧尾声收口，焦点状态与 UI 语义都错。
     */
    @Test
    fun callbackPauseIsNotOverriddenByTrailingStop() {
        val speaker = FakeSpeaker()
        val states = ArrayList<TtsPlaybackState>()
        lateinit var playback: TtsPlayback
        playback = TtsPlayback(
            speaker,
            onStateChanged = { states += it },
            onUnitFinished = { playback.pause() },
        )
        playback.start(sentences("甲。"))
        speaker.completeCurrent()

        assertEquals(1, speaker.stopped)
        assertEquals(TtsPlaybackState.Paused(0, 1), playback.state)
        assertTrue("暂停不得被旧尾声收口", states.none { it is TtsPlaybackState.Idle })
    }

    /** 回调主动 shutdown：shutdown 已释放焦点，旧尾声不得再 stop。 */
    @Test
    fun callbackShutdownIsNotFollowedByTrailingStop() {
        val speaker = FakeSpeaker()
        var finished = 0
        lateinit var playback: TtsPlayback
        playback = TtsPlayback(
            speaker,
            onUnitFinished = {
                finished += 1
                playback.shutdown()
            },
        )
        playback.start(sentences("甲。"))
        speaker.completeCurrent()

        assertEquals(1, finished)
        assertTrue(speaker.shutDown)
        assertEquals("shutdown 已释放，旧尾声不得再 stop", 0, speaker.stopped)
        assertEquals(TtsPlaybackState.Idle, playback.state)
    }

    /** 回调抛异常：先 stop 释放，再把原异常抛给调用方。 */
    @Test
    fun callbackExceptionReleasesThenRethrows() {
        val speaker = FakeSpeaker()
        val playback = TtsPlayback(speaker, onUnitFinished = { throw IllegalStateException("boom") })
        playback.start(sentences("甲。"))

        val error = runCatching { speaker.completeCurrent() }.exceptionOrNull()

        assertTrue("回调异常必须原样抛出", error is IllegalStateException)
        assertEquals("异常后必须先 stop 释放", 1, speaker.stopped)
        assertEquals(TtsPlaybackState.Idle, playback.state)
        assertNull(playback.current)
    }

    /** 默认路径不受新回调影响：暂停态 load 只换句不发声，resume 才重新开口。 */
    @Test
    fun pausedLoadStaysSilentAndResumeContinues() {
        val speaker = FakeSpeaker()
        val playback = TtsPlayback(speaker)
        playback.start(sentences("甲一。", "甲二。"))
        playback.pause()

        assertEquals(TtsPlaybackState.Paused(0, 2), playback.state)
        assertEquals(1, speaker.stopped)
        val spokenAtPause = speaker.spoken.size

        assertTrue(playback.load(sentences("乙一。", "乙二。")))
        assertEquals("暂停态 load 不得下发 speak", spokenAtPause, speaker.spoken.size)
        assertEquals("装载路径不得再 stop", 1, speaker.stopped)
        assertEquals(TtsPlaybackState.Paused(0, 2), playback.state)

        playback.resume()
        assertEquals(listOf("甲一。", "乙一。"), speaker.spoken)
        assertEquals(1, speaker.stopped)
        assertEquals(TtsPlaybackState.Playing(0, 2), playback.state)
    }

    /** 自然末尾收口后 stop 仍幂等：重复 stop 不得二次停引擎/二次发布。 */
    @Test
    fun stopAfterNaturalEndStaysIdempotent() {
        val speaker = FakeSpeaker()
        val playback = TtsPlayback(speaker)
        playback.start(sentences("甲。"))
        speaker.completeCurrent()
        assertEquals(1, speaker.stopped)

        playback.stop()
        assertEquals(1, speaker.stopped)
        assertEquals(TtsPlaybackState.Idle, playback.state)
    }

    /**
     * 暂停 → load 另一段 → resume 后，自然结束仍能同步接下一段；全程只有暂停那一次 stop。
     * 负控：load/resume 若没有把暂停标志与代次收干净，末尾交接会漏回调，或交接前多一次 stop。
     */
    @Test
    fun continuationStillWorksAfterPauseLoadResume() {
        val speaker = FakeSpeaker()
        val states = ArrayList<TtsPlaybackState>()
        val next = sentences("丙一。", "丙二。")
        var finished = 0
        lateinit var playback: TtsPlayback
        playback = TtsPlayback(
            speaker,
            onStateChanged = { states += it },
            onUnitFinished = {
                if (finished++ == 0) assertTrue(playback.start(next))
            },
        )

        playback.start(sentences("甲一。", "甲二。"))
        playback.pause()
        assertEquals(1, speaker.stopped)
        assertTrue(playback.load(sentences("乙一。", "乙二。")))
        assertEquals("load 不得再停引擎", 1, speaker.stopped)
        assertEquals(TtsPlaybackState.Paused(0, 2), playback.state)

        playback.resume()
        assertEquals(TtsPlaybackState.Playing(0, 2), playback.state)
        speaker.completeCurrent() // 乙一 → 乙二
        speaker.completeCurrent() // 乙二 → 同步 start 丙一

        assertEquals(1, finished)
        assertEquals("段间只有暂停那一次 stop", 1, speaker.stopped)
        assertEquals(listOf("甲一。", "乙一。", "乙二。", "丙一。"), speaker.spoken)
        assertEquals(TtsPlaybackState.Playing(0, 2), playback.state)
        assertEquals("暂停/装载不算 Idle，交接也不得补发", 0, states.count { it is TtsPlaybackState.Idle })

        speaker.completeCurrent() // 丙一 → 丙二
        speaker.completeCurrent() // 丙二 → 末尾收口
        assertEquals("末尾再收口一次", 2, speaker.stopped)
        assertEquals(TtsPlaybackState.Idle, playback.state)
    }

    /**
     * 回调先同步 start 新单元、再抛出预建异常：必须真实收口并抛出同一对象。
     * 负控：catch 只重抛不 stop（新单元继续念），或把异常包装成新对象，都会红。
     */
    @Test
    fun exceptionAfterHandoffStopsAndRethrowsSameInstance() {
        val speaker = FakeSpeaker()
        val boom = IllegalStateException("handoff boom")
        lateinit var playback: TtsPlayback
        playback = TtsPlayback(
            speaker,
            onUnitFinished = {
                assertTrue(playback.start(sentences("乙一。")))
                throw boom
            },
        )
        playback.start(sentences("甲。"))

        val error = runCatching { speaker.completeCurrent() }.exceptionOrNull()

        assertSame("必须抛出同一异常对象，不得包装", boom, error)
        assertEquals("新单元已 start，仍必须真实 stop 收口", 1, speaker.stopped)
        assertEquals(listOf("甲。", "乙一。"), speaker.spoken)
        assertEquals(TtsPlaybackState.Idle, playback.state)
        assertNull(playback.current)
    }
}
