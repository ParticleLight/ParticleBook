#pragma once
// ── PB-001: SapiTts — 本地离线 TTS（Windows SAPI5 / OneCore）──────────────
//
// 目标（docs/BACKLOG.md PB-001）：在本地离线条件下朗读 EPUB / TXT / HTML /
// PDF，不依赖云端、不默认捆绑模型。本文件是 C++ 侧的唯一入口，前端通过
// Bridge / JobService 接入。
//
// 实现要点（均已在本机实测，见 cpp 顶部注释）：
//   * 用 SAPI5 ISpVoice + SPF_ASYNC 朗读：Speak() 立刻返回，合成/播放在
//     SAPI 内部线程进行，绝不阻塞调用线程；
//   * 音色枚举合并两个注册表分类并去重：
//       1. HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Speech\Voices        （SAPI5 desktop）
//       2. HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Speech_OneCore\Voices （OneCore，音色更新；
//          本机的 zh-CN Yaoyao / Kangkang 只存在于这里，SAPI5 分类里没有）
//     去重键 = 规范化名称（去掉 "Microsoft " 前缀与 " desktop" 后缀）+ 语言；
//     SAPI5 desktop 优先保留，OneCore 独有的音色（Yaoyao / Kangkang …）追加；
//   * ISpVoice 与 OneCore token 兼容（SetVoice 后可正常朗读），因此不必上 WinRT；
//   * 事件：SetNotifyWin32Event + 独立线程 WaitForSingleObject + GetEvents。
//     ⚠ 实测 SetNotifyCallbackFunction 在本机 SAPI 上「返回 S_OK 但从不回调」，
//     所以不要退回去用它；
//   * 句/词边界事件（SPEI_SENTENCE_BOUNDARY / SPEI_WORD_BOUNDARY）的偏移是
//     UTF-16 code unit，本类额外换算成原文的 UTF-8 字节偏移，便于前端高亮。
//
// 线程模型（重要，接 Bridge/JobService 时按这个来）：
//   * Initialize / Shutdown / EnumerateVoices / SelectVoice / SetRate /
//     SetVolume / Pause / Resume / Stop / WaitIdle：任意线程，内部串行化，
//     可能短暂阻塞（毫秒级；Initialize 首次枚举约几十毫秒）。
//   * Speak：任意线程、非阻塞（只入队后返回 utteranceId），真正的合成在
//     内部线程；失败通过事件（TtsEventType::Error）+ LastError() 报告。
//   * IsSpeaking / IsPaused / Rate / Volume / LastError：任意线程、无阻塞。
//   * 事件回调 跑在内部通知线程（不是 UI 线程，也不是调用 Speak 的线程）：
//       - 回调里不要碰 WebView2 / Bridge / HWND，用 pb::UiDispatcher::Post() 回 UI；
//       - 回调里不要调用本类的阻塞方法（Initialize/Stop/SelectVoice…），
//         会被拒绝并返回 "reentrant_call"（避免自我死锁）；
//       - 回调要快；内部先释放锁再回调，所以延长处理时间只会拖慢事件消费。
//   * 本类内部在专用线程上做 CoInitializeEx(MTA)，调用方不需要 CoInitialize；
//     也不能把本类的方法要求成「必须在 UI 线程调用」。
//   * 同一个 SapiTts 实例全局只应有一个（SAPI 语音是进程级单例式资源；
//     多实例各自建 ISpVoice 会互相抢占音频设备）。生命周期：App 启动时
//     Initialize()，关停时在拆 Bridge 之前 Shutdown()（内部会先 Stop）。
//
// 依赖：sapi.lib、ole32.lib（CMake 里给 ParticleBook 与 sapi_tts_test 都加）。
// 本头文件不包含 <windows.h>，因此单测可以直接包含它。

#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

namespace pb {

// 一个可选音色。id 是 SAPI token 的注册表 id（跨进程稳定，适合持久化到
// 设置里）；name 是给用户看的友好名（UTF-8）。
struct TtsVoiceInfo {
    std::string id;          // 例：HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Speech\Voices\Tokens\TTS_MS_ZH-CN_HUIHUI_11.0
    std::string name;        // 例：Microsoft Huihui Desktop（UTF-8）
    std::string language;    // 例：zh-CN / en-US；系统未给出时可空
    std::string gender;      // 例：Female / Male；系统未给出时可空
    bool        oneCore = false;   // true = 来自 OneCore 分类（较新的音色）
};

// 朗读事件。句/词边界事件带两个偏移视图：
//   charOffset/charLength —— UTF-16 code unit（SAPI 原生单位）
//   byteOffset/byteLength —— 原文（Speak 传入的 UTF-8 字符串）的字节偏移
// 中文一个汉字 = 1 个 UTF-16 unit / 3 个 UTF-8 字节，所以两个视图不通用。
enum class TtsEventType {
    Started,           // 一段文本开始朗读（utterance 有效）
    Ended,             // 一段文本朗读结束（被 Stop/新 Speak 打断也会来一次）
    WordBoundary,      // 词边界（中文也可能是词组；不保证每个词都有）
    SentenceBoundary,  // 句子边界
    Error,             // 合成/播放失败（error 非空）
};

struct TtsEvent {
    TtsEventType  type = TtsEventType::Started;
    std::uint64_t utterance = 0;      // Speak() 的返回值
    std::string   tag;                // Speak 时传入的 tag（原样带回）
    std::uint32_t charOffset = 0;     // UTF-16 code unit 偏移（边界事件）
    std::uint32_t charLength = 0;
    std::uint32_t byteOffset = 0;     // UTF-8 字节偏移（边界事件）
    std::uint32_t byteLength = 0;
    std::string   error;              // 机器可读短原因，仅 Error 事件
};

struct TtsSpeakOptions {
    // true（默认）= 先打断当前朗读并清空排队，再读这段（UI 上「点下一句」）；
    // false = 追加到队列尾部（连续朗读整章）。
    bool        flush = true;
    // 调用方标记（比如句子 id / 段落号），事件里原样带回；可为空。
    std::string tag;
};

class SapiTts {
public:
    using EventHandler = std::function<void(const TtsEvent&)>;

    // 默认 rate=0（SAPI 正常语速，范围 -10..10）、volume=100（0..100）。
    SapiTts();
    ~SapiTts();

    SapiTts(const SapiTts&) = delete;
    SapiTts& operator=(const SapiTts&) = delete;

    // 建内部线程 + CoInitializeEx(MTA) + CoCreateInstance(SpVoice)。
    // 幂等；失败返回 false，LastError() 说明原因（"sapi:co_create (0x...)"）。
    // 没有安装任何音色时仍返回 true（枚举为空），由 Speak() 明确拒绝。
    bool Initialize();

    // 先 Stop()（打断朗读），再拆两个内部线程并释放 COM 对象。幂等；
    // 从任意线程调用都安全（事件回调里不要调，见文件头线程模型）。
    void Shutdown();

    bool IsInitialized() const;

    // 枚举音色（两个分类合并 + 去重，按「SAPI5 desktop 优先、OneCore 追加」排序）。
    // 阻塞（首次会触发 Initialize）；成功返回 true，out 可为空（系统无音色）。
    bool EnumerateVoices(std::vector<TtsVoiceInfo>& out);

    // 选择音色：voiceId 为空 = 恢复系统默认音色。未知 id 返回 false 且
    // LastError() = "voice_not_found"（当前音色保持不变）。
    bool SelectVoice(const std::string& voiceId);
    // 按友好名选择（先精确、再大小写不敏感精确）；找不到返回 false。
    bool SelectVoiceByName(const std::string& name);

    // 当前音色（初始化后即为系统默认音色）；未初始化/查询失败时为空。
    std::string CurrentVoiceId() const;
    std::string CurrentVoiceName() const;

    // 语速 -10..10、音量 0..100。越界不写入、返回 false（"rate_out_of_range" /
    // "volume_out_of_range"）。设置会立即作用到正在进行的朗读（SAPI 语义）。
    bool SetRate(int rate);
    int  Rate() const;
    bool SetVolume(int volume);
    int  Volume() const;

    // 朗读一段 UTF-8 文本，非阻塞。返回 utteranceId（> 0）；失败返回 0 并
    // 置 LastError()：
    //   "empty_text"          —— 文本为空或只有空白
    //   "not_initialized"     —— Initialize 失败
    //   "no_voice_installed"  —— 系统一个音色都没有（引导用户去 Windows 安装）
    // 真正的合成失败以 TtsEventType::Error 事件给出，lastError 同步更新。
    // 注意：SAPI 只理解 UTF-16，本类内部做 UTF-8 → UTF-16 转换。
    std::uint64_t Speak(const std::string& utf8, const TtsSpeakOptions& options = TtsSpeakOptions());

    // 暂停/恢复。没在朗读时返回 false（"not_speaking"）。
    bool Pause();
    bool Resume();

    // 停止：清空队列 + SPF_PURGEBEFORESPEAK 打断当前朗读，最多等 1.5s 让
    // SAPI 回到空闲（通常 < 50ms）。返回 true 表示已停止（超时也强制置空闲，
    // 因为打断请求已经交给 SAPI，不会出现「停不下来」）。
    bool Stop();

    // 正在朗读（含暂停中）。Pause 时仍为 true，与 SAPI 的 RunningState 一致。
    bool IsSpeaking() const;
    bool IsPaused() const;

    // 等队列跑完（全部 Ended 且无排队）；timeoutMs<=0 表示只查一次。
    // 返回 true = 已空闲。给「关书前确认已停」「想读完再切章」这类场景用。
    bool WaitIdle(int timeoutMs);

    // 事件回调（句/词边界 + 起止 + 错误）。传空 = 取消回调。
    // 回调线程见文件头；内部持锁拷贝后调用，回调里抛异常会被吞掉（不终止进程）。
    void SetEventHandler(EventHandler handler);

    // 最近一次失败的可读原因；成功后保持为空。不含用户文本。
    std::string LastError() const;

private:
    struct Impl;
    std::unique_ptr<Impl> m_impl;
};

} // namespace pb
