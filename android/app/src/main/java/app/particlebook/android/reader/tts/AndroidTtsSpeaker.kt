package app.particlebook.android.reader.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 系统 TTS 引擎适配器。
 *
 * 职责边界：
 * - 只负责"把一句文本交给引擎"和"把完成回调转成 utteranceId"，不关心句子从哪来；
 * - 初始化失败不抛异常，[ready] 保持 false，由 UI 决定提示文案；
 * - 语速在引擎未就绪时先记下来，初始化成功后补设，避免首次朗读丢设置；
 * - 按句内容选朗读语言：中文句走系统默认语言，拉丁句走 en-US。书架里既有中文也有英文书，
 *   用固定 locale 会出现"英文书用中文音库念"或反之的错配；
 * - 引擎对某句报错时按"念完"处理继续下一句：系统语音偶发 ERROR 会让整页永远停在那一句，
 *   界面看起来像卡死；跳过并向调用方回报是更可用的降级。
 */
internal class AndroidTtsSpeaker(
    context: Context,
    private val locale: Locale = Locale.getDefault(),
    /** 焦点被别人拿走时要暂停朗读；默认空实现，便于单测与非音频场景复用。 */
    onFocusLost: () -> Unit = {},
) : TtsSpeaker {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioFocus = TtsAudioFocus(context, onFocusLost)

    private val callbacks = HashMap<String, (String) -> Unit>()
    private var pendingRate: Float? = null
    private var activeLocale: Locale? = null

    @Volatile
    private var engine: TextToSpeech? = null

    /** 引擎是否初始化成功；与"某个语言有没有音库"分开记，缺音库不该让整个朗读不可用。 */
    @Volatile
    private var engineReady: Boolean = false

    /** 连续 speak 直接失败（返回 ERROR）的次数，超过阈值认定引擎已不可用。 */
    @Volatile
    private var speakFailures: Int = 0

    @Volatile
    override var ready: Boolean = false
        private set

    init {
        // 初始化回调投到主线程：①engine 的赋值一定已经发生（早先回调里 engine ?: return 会吞掉
        // 极早到达的回调，ready 永远 false 且没有提示）；②ready/语言等都是主线程写状态。
        val created = TextToSpeech(context.applicationContext) { status ->
            mainHandler.post { onEngineInit(status) }
        }
        engine = created
        applyAudioAttributes(created)
        created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) = dispatch(utteranceId)

            @Deprecated("旧版本仍会回调")
            override fun onError(utteranceId: String?) = dispatch(utteranceId)

            override fun onError(utteranceId: String?, errorCode: Int) = dispatch(utteranceId)

            /** 被 QUEUE_FLUSH 打断的旧句子只会走到这里；不接这一条它的回调就会永久留在表里。 */
            override fun onStop(utteranceId: String?, interrupted: Boolean) = dispatch(utteranceId)
        })
    }

    override fun setRate(rate: Float) {
        val sanitized = rate.coerceIn(MIN_RATE, MAX_RATE)
        val target = engine
        if (ready && target != null) target.setSpeechRate(sanitized) else pendingRate = sanitized
    }

    override fun speak(text: String, utteranceId: String, onDone: (String) -> Unit) {
        // QUEUE_FLUSH：同一时刻只有当前这一句会回调，旧条目不可能再被触发，留着只会累积。
        synchronized(callbacks) {
            callbacks.clear()
            callbacks[utteranceId] = onDone
        }
        val target = engine
        if (!ready || target == null) {
            // 引擎不可用时不能静默 return：回调没注册过就永远不会有人推进状态机，
            // 界面会永久停在"正在念第 n 句"（复核 N1，症状与 B2 相同，只是入口换了）。
            // 先注册再按"这句没念成"回报一次，让上层继续/收尾；是否提示由调用方看 [ready] 决定。
            dispatch(utteranceId)
            return
        }
        applyLanguage(target, TtsLanguages.localeFor(text, locale))
        audioFocus.acquire()
        val result = target.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR) {
            // speak() 直接失败时不会有任何回调：不清掉这一条，状态机会永远停在"正在念第 n 句"。
            // 先按"这一句念完了"回报一次跳过它；连续失败说明引擎已经坏了，再让上层看到不可用。
            speakFailures += 1
            if (speakFailures >= MAX_SPEAK_FAILURES) {
                engineReady = false
                ready = false
            }
            dispatch(utteranceId)
        } else {
            speakFailures = 0
        }
    }

    override fun stop() {
        synchronized(callbacks) { callbacks.clear() }
        engine?.stop()
        audioFocus.abandon()
    }

    override fun shutdown() {
        synchronized(callbacks) { callbacks.clear() }
        engine?.shutdown()
        engine = null
        ready = false
        audioFocus.abandon()
    }

    /**
     * 同一 utteranceId 的回调只投递一次：onDone 与 onError 可能先后到达。
     *
     * 投到主线程：监听器的回调在 binder 线程执行，直接往下走会无锁地改上层的 Compose 状态；
     * 收敛到主线程后「界面状态只在主线程变」这条不变式重新成立，暂停/切句的竞争窗口一并消失。
     */
    private fun dispatch(utteranceId: String?) {
        if (utteranceId == null) return
        mainHandler.post {
            val callback = synchronized(callbacks) { callbacks.remove(utteranceId) }
            callback?.invoke(utteranceId)
        }
    }

    private fun onEngineInit(status: Int) {
        val target = engine ?: return
        if (status != TextToSpeech.SUCCESS) return
        engineReady = true
        ready = true
        // 构造后立刻 setAudioAttributes 可能被未初始化的引擎忽略，初始化成功后再设一次，
        // 否则音量键作用在别的流上（复核 S9/C31：朗读时按音量键没反应）。
        applyAudioAttributes(target)
        applyLanguage(target, locale)
        pendingRate?.let { target.setSpeechRate(it) }
        pendingRate = null
    }

    /**
     * 切到 [wanted] 音库；不可用时退回默认语言。
     *
     * 语言可用性不参与 [ready]：真正决定"能不能念"的是引擎本身，缺一个音库只影响这一句的音色。
     * 早先把两者合成一个标志，默认语言缺数据时整条朗读链就永久不可用，连按句切到 en-US 的机会都没有。
     */
    private fun applyLanguage(target: TextToSpeech, wanted: Locale) {
        if (activeLocale == wanted) return
        if (isUsable(target.setLanguage(wanted))) {
            activeLocale = wanted
            return
        }
        val fallback = Locale.getDefault()
        if (wanted != fallback && isUsable(target.setLanguage(fallback))) activeLocale = fallback
    }

    /** 朗读走媒体流 + 语音内容类型：音量键、耳机/蓝牙路由、暂停恢复都由系统按这个属性处理。 */
    private fun applyAudioAttributes(target: TextToSpeech) {
        target.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
    }

    private fun isUsable(result: Int): Boolean =
        result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED

    private companion object {
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 2.0f
        const val MAX_SPEAK_FAILURES = 3
    }
}