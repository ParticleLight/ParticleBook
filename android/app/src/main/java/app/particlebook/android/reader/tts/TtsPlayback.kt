package app.particlebook.android.reader.tts

/**
 * 朗读引擎抽象：把"要念什么"和"怎么念"分开。
 * 生产实现是 [AndroidTtsSpeaker]；单测用 fake，避免依赖 android.speech.tts。
 */
internal interface TtsSpeaker {
    /** 引擎是否已就绪；未就绪时调用 [speak] 不应崩溃，由调用方决定提示。 */
    val ready: Boolean

    /** 语速倍率，1.0 为常速。 */
    fun setRate(rate: Float)

    /** 覆盖式朗读一句；同一 utteranceId 只会回调一次 [onDone]。 */
    fun speak(text: String, utteranceId: String, onDone: (String) -> Unit)

    /** 立即停止并丢弃队列。 */
    fun stop()

    fun shutdown()
}

/** 播放状态：Idle 表示没有朗读任务；Playing/Paused 携带当前句下标。 */
internal sealed interface TtsPlaybackState {
    data object Idle : TtsPlaybackState
    data class Playing(val index: Int, val total: Int) : TtsPlaybackState
    data class Paused(val index: Int, val total: Int) : TtsPlaybackState

    val sentenceIndex: Int
        get() = when (this) {
            Idle -> -1
            is Playing -> index
            is Paused -> index
        }

    /** 本页句子总数；Idle 时为 0。UI 不再自己从状态里掰。 */
    val sentenceTotal: Int
        get() = when (this) {
            Idle -> 0
            is Playing -> total
            is Paused -> total
        }

    /**
     * 控制条上的进度文案「本页 n / total」；Idle 时为 null。
     *
     * total 是**当前朗读单元**的句数，不是全书句数：早先的「朗读 n / total」被读成
     * "全书第 n 句"，用户会以为翻了半天才念到个位数。
     *
     * 单位由调用方给（[unit]）：EPUB / PDF 按页朗读是「本页」，文本阅读器按段落朗读是「本段」。
     * 状态机只认"第几句"，不认识页与段这类排版概念，所以单位不下沉到这里。
     */
    fun progressLabel(unit: String = DEFAULT_PROGRESS_UNIT): String? =
        if (this is Idle) null else unit + " " + (sentenceIndex + 1) + " / " + sentenceTotal

    companion object {
        /** 默认进度单位：按页朗读的阅读器（EPUB / PDF）。 */
        const val DEFAULT_PROGRESS_UNIT: String = "本页"
    }
}

/**
 * 逐句推进的状态机（纯 JVM，可单测）。
 *
 * 语义：
 * - [start] 从头（或指定下标）开始，立刻朗读该句；
 * - 一句念完（[onUtteranceDone]）自动推进到下一句，末句念完回到 Idle；
 * - [pause] 只停当前句，恢复时从当前句重新朗读（系统 TTS 不支持续播，重念整句是常见做法）；
 * - 暂停期间的 [next] / [previous] 只移动光标，停在暂停状态，不自动开念；
 * - [stop] 清空并回到 Idle。
 *
 * 引擎不可用时不改变状态，避免 UI 出现"在播但没声音"的假象。
 */
internal class TtsPlayback(
    private val speaker: TtsSpeaker,
    private val onStateChanged: (TtsPlaybackState) -> Unit = {},
    private val onSentence: (TtsSentence?) -> Unit = {},
) {
    private var sentences: List<TtsSentence> = emptyList()
    private var index = -1
    private var paused = false
    private var utteranceSeq = 0

    val state: TtsPlaybackState
        get() = when {
            index < 0 || index >= sentences.size -> TtsPlaybackState.Idle
            paused -> TtsPlaybackState.Paused(index, sentences.size)
            else -> TtsPlaybackState.Playing(index, sentences.size)
        }

    val current: TtsSentence?
        get() = sentences.getOrNull(index)

    /**
     * 开始朗读；返回 false 表示**没有开始**（引擎未就绪或没有句子）。
     *
     * 返回值是契约的一部分：[state] 与 [onStateChanged] 都是同步写回的，调用方靠这个返回值
     * 决定要不要提示「系统朗读引擎不可用」。早先调用方只能"start 之后再看 state 是不是 Idle"，
     * 那是一条隐式契约：一旦 start 变成挂起函数或引擎初始化改成异步，提示会静默失效。
     */
    fun start(sentences: List<TtsSentence>, fromIndex: Int = 0): Boolean {
        if (sentences.isEmpty() || !speaker.ready) return false
        this.sentences = sentences
        index = fromIndex.coerceIn(0, sentences.lastIndex)
        paused = false
        speakCurrent()
        return true
    }

    /**
     * 只装载不发声：把光标放到指定句子并发布状态，等用户按「继续」才开口。
     *
     * 存在的理由：调用方在暂停态跨段、或暂停态重新定位时，需要的只是"换一批句子"。
     * 早先的做法是 start() 再 pause()，那会真的向引擎下发一次 speak 再掐掉——
     * 用户能听到半个音节，后台音乐也会被闪一次音频焦点（见 AndroidTtsSpeaker.stop 会 abandon）。
     *
     * 返回 false 与 [start] 同义：没装载成功（引擎未就绪或没有句子），状态不变。
     */
    fun load(sentences: List<TtsSentence>, fromIndex: Int = 0, paused: Boolean = true): Boolean {
        if (sentences.isEmpty() || !speaker.ready) return false
        this.sentences = sentences
        index = fromIndex.coerceIn(0, sentences.lastIndex)
        this.paused = paused
        publish()
        onSentence(current)
        return true
    }

    fun pause() {
        if (state !is TtsPlaybackState.Playing) return
        // 先落暂停标志再停引擎：引擎回调可能在 binder 线程上来，反序会有一个"界面已暂停、
        // 回调仍推进一步"的窗口。
        paused = true
        speaker.stop()
        publish()
    }

    fun resume() {
        if (state !is TtsPlaybackState.Paused) return
        paused = false
        speakCurrent()
    }

    fun stop() {
        // 已经空闲时再 stop 直接返回：否则重复调用会多一次 publish + onSentence(null)，
        // 与「stop 之后迟到回调不该再推一次」是同一类问题的另一个入口。
        if (state is TtsPlaybackState.Idle && sentences.isEmpty()) return
        // 让 stop 之后到达的回调一律作废：否则刚停下的那一句回调仍会匹配当前 id，
        // 再走一遍 stop → 多一次 publish + onSentence(null)。将来若给 onSentence(null)
        // 加副作用（埋点、清缓存），重复触发就会变成真问题。
        utteranceSeq += 1
        speaker.stop()
        sentences = emptyList()
        index = -1
        paused = false
        publish()
        onSentence(null)
    }

    /** 暂停状态下切句只移动光标，不自动开口：用户可以先挑句子再按继续。 */
    fun next() {
        if (sentences.isEmpty()) return
        if (index >= sentences.lastIndex) {
            stop()
            return
        }
        index += 1
        moveToCurrent()
    }

    fun previous() {
        if (sentences.isEmpty()) return
        if (index > 0) index -= 1
        moveToCurrent()
    }

    fun setRate(rate: Float) = speaker.setRate(rate)

    fun shutdown() {
        sentences = emptyList()
        index = -1
        paused = false
        speaker.shutdown()
    }

    /** 由引擎回调驱动：只有当前这一句的回调才推进，避免快速暂停/切句时的迟到回调打乱状态。 */
    fun onUtteranceDone(utteranceId: String) {
        if (paused) return
        if (utteranceId != currentUtteranceId()) return
        if (index >= sentences.lastIndex) {
            stop()
            return
        }
        index += 1
        speakCurrent()
    }

    private fun moveToCurrent() {
        if (paused) {
            publish()
            onSentence(current)
        } else {
            speakCurrent()
        }
    }

    private fun speakCurrent() {
        val sentence = sentences.getOrNull(index)
        if (sentence == null) {
            stop()
            return
        }
        utteranceSeq += 1
        val id = currentUtteranceId()
        publish()
        onSentence(sentence)
        speaker.speak(sentence.text, id) { done -> onUtteranceDone(done) }
    }

    private fun currentUtteranceId(): String = "pb-tts-" + utteranceSeq

    private fun publish() {
        onStateChanged(state)
    }
}