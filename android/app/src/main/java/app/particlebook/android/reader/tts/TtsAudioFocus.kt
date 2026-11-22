package app.particlebook.android.reader.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * 朗读与其它音频的相处方式：只把「该不该让位」这件事抽成纯函数，Android 那一层留薄。
 *
 * 为什么抽出来：焦点回调的类型判断是纯逻辑（失焦要停、可压低音量的失焦不该停），
 * 放在 AudioManager 监听器里就只能在真机上试；抽成枚举后 JVM 单测能钉住四种分支。
 */
internal enum class AudioFocusChange {
    GAIN,
    LOSS,
    LOSS_TRANSIENT,
    LOSS_TRANSIENT_CAN_DUCK,
    UNKNOWN,
}

internal object AudioFocusPolicy {
    /** AudioManager 的焦点常量值：-1 LOSS、-2 LOSS_TRANSIENT、-3 LOSS_TRANSIENT_CAN_DUCK、1 GAIN。 */
    fun parse(code: Int): AudioFocusChange = when (code) {
        -1 -> AudioFocusChange.LOSS
        -2 -> AudioFocusChange.LOSS_TRANSIENT
        -3 -> AudioFocusChange.LOSS_TRANSIENT_CAN_DUCK
        1 -> AudioFocusChange.GAIN
        else -> AudioFocusChange.UNKNOWN
    }

    /**
     * 该不该因此暂停：永久失焦与短暂失焦都要停（来电、别的应用放语音）；
     * 「可压低音量」的失焦不停 —— 压低音量由引擎自己处理，停掉反而打断阅读。
     */
    fun shouldPause(change: AudioFocusChange): Boolean =
        change == AudioFocusChange.LOSS || change == AudioFocusChange.LOSS_TRANSIENT
}

/**
 * 朗读期间的音频焦点持有者。
 *
 * 申请 AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK（朗读是有明确起止的短时占用，不是常驻播放），
 * 失焦时回调 [onFocusLost] 交给上层暂停；[abandon] 在停止/退出时调用，避免焦点泄漏。
 * 音频属性固定为 USAGE_MEDIA + CONTENT_TYPE_SPEECH：不指定的话音量归到哪条流取决于引擎默认，
 * 用户调「媒体音量」可能对朗读没反应。
 */
internal class TtsAudioFocus(
    context: Context,
    private val onFocusLost: () -> Unit,
) {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        if (AudioFocusPolicy.shouldPause(AudioFocusPolicy.parse(change))) onFocusLost()
    }

    private val request: AudioFocusRequest? = audioManager?.let { manager ->
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(listener)
            .build()
    }

    private var held = false

    /** 拿焦点；拿不到也照常朗读（用户按了朗读就该听到声音，别用焦点当开关）。 */
    fun acquire() {
        if (held) return
        val manager = audioManager ?: return
        val request = request ?: return
        held = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun abandon() {
        if (!held) return
        held = false
        val manager = audioManager ?: return
        val request = request ?: return
        manager.abandonAudioFocusRequest(request)
    }
}
