package app.particlebook.android.settings

/**
 * 翻译 mock：P1 阶段不联网，只验证「Key 已配置 → 翻译入口可用」的完整链路。
 *
 * 真实翻译服务接入后，只需替换本对象的实现；[TranslationKeyController] 和设置 UI 不需要改。
 * 该对象不读取 SecretStore、不依赖 Android，JVM 单测可直接调用。
 */
object TranslationMock {

    /** 设置页测试按钮使用的固定样例；不把用户输入写进日志或设置。 */
    const val DEFAULT_SAMPLE_TEXT = "Hello, ParticleBook."

    const val SUCCESS_MESSAGE = "Mock 翻译成功（未联网）"
    const val NOT_CONFIGURED_MESSAGE = "未配置 API Key，请先设置"

    /**
     * 输入 API Key 与文本，返回 mock 结果。
     *
     * 规则（A023-3/4）：
     * - key 为空 / 全空白 → 未配置，不执行翻译；
     * - key 非空、文本为空 → 提示输入文本；
     * - key 非空、文本非空 → 返回固定 mock 结果，不发生任何网络请求。
     */
    fun translate(apiKey: String?, text: String): TranslationMockResult {
        if (apiKey.isNullOrBlank()) {
            return TranslationMockResult(
                ok = false,
                message = NOT_CONFIGURED_MESSAGE,
                translatedText = null,
            )
        }
        val source = text.trim()
        if (source.isEmpty()) {
            return TranslationMockResult(
                ok = false,
                message = "请输入要翻译的文本",
                translatedText = null,
            )
        }
        return TranslationMockResult(
            ok = true,
            message = SUCCESS_MESSAGE,
            translatedText = "[Mock 翻译] " + source,
        )
    }
}

/** UI/测试共用的 mock 翻译返回值；[translatedText] 只含 mock 文本，不含 Key。 */
data class TranslationMockResult(
    val ok: Boolean,
    val message: String,
    val translatedText: String? = null,
)
