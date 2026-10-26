// ── PB-001: SapiTts tests ───────────────────────────────────────────
// 覆盖 docs/BACKLOG.md PB-001 的 MVP 验收里 C++ 侧能测的部分：
//   * 音色枚举：名称 / 语言 / 性别、去重；本机（zh-CN 环境）应能同时看到
//     SAPI5 desktop 的 Huihui 和 OneCore 独有 的 Yaoyao / Kangkang；
//   * 空文本 / 纯空白文本被明确拒绝（"empty_text"），不产生朗读；
//   * rate / volume 越界不写入；
//   * 非法音色被拒绝且不影响当前音色；
//   * 状态机：Speak → Pause → Resume → Stop，Stop 后 IsSpeaking()==false，
//     且能再次朗读；
//   * 事件：Started / Ended / 句边界（偏移必须落在原文范围内）；
//   * 静音：所有朗读前都 SetVolume(0)，测试不会真的发出声音；
//   * Shutdown 幂等、析构不挂死。
//
// 环境差异：CI（GitHub Windows runner）通常只有 en-US 音色，没有中文音色，
// 因此「必须有 Huihui/Yaoyao/Kangkang」只在枚举到 zh-CN 音色时才断言；
// 没有音频输出设备时 Speak 起不来，按 SKIP 处理（不是代码缺陷）。

#include "platform/SapiTts.h"

#include "utils/test_assert.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace {

bool WaitUntil(const std::function<bool()>& predicate, int timeoutMs)
{
    const auto deadline = std::chrono::steady_clock::now()
                        + std::chrono::milliseconds(timeoutMs);
    while (std::chrono::steady_clock::now() < deadline) {
        if (predicate()) return true;
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }
    return predicate();
}

std::string Lower(std::string s)
{
    for (char& c : s) {
        if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
    }
    return s;
}

bool NameContains(const pb::TtsVoiceInfo& voice, const char* needle)
{
    return Lower(voice.name).find(Lower(needle)) != std::string::npos;
}

bool HasLanguagePrefix(const pb::TtsVoiceInfo& voice, const char* prefix)
{
    return Lower(voice.language).rfind(Lower(prefix), 0) == 0;
}

// 语言字段要么为空，要么是 "zh-CN" 这种 BCP-47 形态（主语言 2~3 个小写字母，
// 可选 "-" + 2 个大写字母）。
bool LooksLikeLanguageTag(const std::string& tag)
{
    if (tag.empty()) return true;
    std::size_t i = 0;
    while (i < tag.size() && tag[i] >= 'a' && tag[i] <= 'z') ++i;
    if (i < 2 || i > 3) return false;
    if (i == tag.size()) return true;
    if (tag[i] != '-') return false;
    ++i;
    if (i + 2 != tag.size()) return false;
    return tag[i] >= 'A' && tag[i] <= 'Z' && tag[i + 1] >= 'A' && tag[i + 1] <= 'Z';
}

std::string VoiceDedupeKey(const pb::TtsVoiceInfo& voice)
{
    std::string name = Lower(voice.name);
    const std::string prefix = "microsoft ";
    if (name.rfind(prefix, 0) == 0) name.erase(0, prefix.size());
    const std::string suffix = " desktop";
    if (name.size() > suffix.size()
        && name.compare(name.size() - suffix.size(), suffix.size(), suffix) == 0) {
        name.erase(name.size() - suffix.size());
    }
    return name + "|" + Lower(voice.language);
}

const pb::TtsVoiceInfo* PickChineseVoice(const std::vector<pb::TtsVoiceInfo>& voices)
{
    for (const pb::TtsVoiceInfo& voice : voices) {
        if (HasLanguagePrefix(voice, "zh")) return &voice;
    }
    return nullptr;
}

} // namespace

int main()
{
    // ── 1. 初始化 + 音量/语速参数校验（先静音，后面所有朗读都不出声） ──
    pb::SapiTts tts;
    if (!tts.Initialize()) {
        std::printf("FAIL  Initialize(): %s\n", tts.LastError().c_str());
        return 1;
    }
    CHECK_TRUE(tts.IsInitialized());
    CHECK_TRUE(tts.SetVolume(0));            // 静音：单测不发出声音
    CHECK_EQ(tts.Volume(), 0);
    CHECK_TRUE(tts.SetRate(0));
    CHECK_EQ(tts.Rate(), 0);

    CHECK_TRUE(!tts.SetRate(11));
    CHECK_EQ(tts.LastError(), std::string("rate_out_of_range"));
    CHECK_TRUE(!tts.SetRate(-11));
    CHECK_EQ(tts.LastError(), std::string("rate_out_of_range"));
    CHECK_TRUE(!tts.SetVolume(-1));
    CHECK_EQ(tts.LastError(), std::string("volume_out_of_range"));
    CHECK_TRUE(!tts.SetVolume(101));
    CHECK_EQ(tts.LastError(), std::string("volume_out_of_range"));
    CHECK_EQ(tts.Rate(), 0);                 // 越界不写入
    CHECK_EQ(tts.Volume(), 0);
    CHECK_TRUE(tts.SetVolume(0));            // 恢复静音 + 清 LastError
    CHECK_EQ(tts.LastError(), std::string(""));

    // ── 2. 音色枚举：结构合法、去重、中文音色齐全 ──
    std::vector<pb::TtsVoiceInfo> voices;
    CHECK_TRUE(tts.EnumerateVoices(voices));
    CHECK_TRUE(!voices.empty());             // 本机 / CI 都应至少有一个系统音色

    std::vector<std::string> keys;
    for (const pb::TtsVoiceInfo& voice : voices) {
        CHECK_TRUE(!voice.id.empty());
        CHECK_TRUE(!voice.name.empty());
        CHECK_TRUE(LooksLikeLanguageTag(voice.language));
        if (!voice.gender.empty()) {
            const std::string gender = Lower(voice.gender);
            CHECK_TRUE(gender == "male" || gender == "female");
        }
        keys.push_back(VoiceDedupeKey(voice));
    }
    std::sort(keys.begin(), keys.end());
    CHECK_TRUE(std::adjacent_find(keys.begin(), keys.end()) == keys.end());   // 已去重

    int zhCount = 0;
    int enCount = 0;
    for (const pb::TtsVoiceInfo& voice : voices) {
        if (HasLanguagePrefix(voice, "zh")) ++zhCount;
        if (HasLanguagePrefix(voice, "en")) ++enCount;
    }
    std::printf("voices: %zu (zh=%d, en=%d)\n",
                static_cast<std::size_t>(voices.size()), zhCount, enCount);
    for (const pb::TtsVoiceInfo& voice : voices) {
        std::printf("  %s [%s/%s]%s\n", voice.name.c_str(), voice.language.c_str(),
                    voice.gender.c_str(), voice.oneCore ? " (OneCore)" : "");
    }

    if (zhCount > 0) {
        // 本机是 zh-CN 环境：BACKLOG 点名的三个中文音色必须都在。
        // Huihui 在 SAPI5 desktop，Yaoyao / Kangkang 只在 OneCore —— 这条断言
        // 同时验证了「合并两个分类」这一步。
        const char* required[] = { "Huihui", "Yaoyao", "Kangkang" };
        for (const char* name : required) {
            bool found = false;
            for (const pb::TtsVoiceInfo& voice : voices) {
                if (NameContains(voice, name)) { found = true; break; }
            }
            if (!found) std::printf("FAIL  missing required zh voice: %s\n", name);
            CHECK_TRUE(found);
        }
    } else {
        std::printf("SKIP  no zh-CN voices installed (non-Chinese Windows) — "
                    "Huihui/Yaoyao/Kangkang assertion skipped\n");
    }

    // ── 3. 非法音色：拒绝 + 当前音色不变 ──
    const std::string voiceBefore = tts.CurrentVoiceId();
    CHECK_TRUE(!tts.CurrentVoiceName().empty());   // 初始化后就该知道默认音色
    CHECK_TRUE(!tts.SelectVoice(
        "HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Speech\\Voices\\Tokens\\NoSuchVoice_9.9"));
    CHECK_EQ(tts.LastError(), std::string("voice_not_found"));
    CHECK_EQ(tts.CurrentVoiceId(), voiceBefore);
    CHECK_TRUE(!tts.SelectVoiceByName("No Such Voice XYZ"));
    CHECK_EQ(tts.LastError(), std::string("voice_not_found"));
    CHECK_TRUE(tts.SelectVoice(""));              // 空 id = 恢复系统默认音色
    CHECK_TRUE(!tts.CurrentVoiceName().empty());

    // ── 4. 空文本 / 纯空白文本：明确拒绝，不进队列 ──
    CHECK_EQ(tts.Speak(""), std::uint64_t(0));
    CHECK_EQ(tts.LastError(), std::string("empty_text"));
    CHECK_EQ(tts.Speak(" \t\r\n "), std::uint64_t(0));
    CHECK_EQ(tts.LastError(), std::string("empty_text"));
    CHECK_TRUE(!tts.IsSpeaking());
    CHECK_TRUE(tts.WaitIdle(0));

    // ── 5. 空闲时的 Pause/Resume 明确失败 ──
    CHECK_TRUE(!tts.Pause());
    CHECK_EQ(tts.LastError(), std::string("not_speaking"));
    CHECK_TRUE(!tts.Resume());
    CHECK_EQ(tts.LastError(), std::string("not_paused"));

    // ── 6. 状态机：Speak → Pause → Resume → Stop → 再 Speak ──
    const pb::TtsVoiceInfo* chinese = PickChineseVoice(voices);
    if (chinese) {
        CHECK_TRUE(tts.SelectVoice(chinese->id));
        CHECK_EQ(tts.CurrentVoiceId(), chinese->id);
    } else {
        CHECK_TRUE(tts.SelectVoice(voices.front().id));
        CHECK_EQ(tts.CurrentVoiceId(), voices.front().id);
    }
    CHECK_TRUE(tts.SetVolume(0));

    const std::uint64_t first = tts.Speak("\u4f60\u597d\uff0c\u4e16\u754c\u3002\u4e00\u4e8c\u4e09\u56db\u4e94\u516d\u4e03\u516b\u4e5d\u5341\u3002");
    CHECK_TRUE(first != 0);
    const bool started = WaitUntil([&tts] { return tts.IsSpeaking(); }, 10000);
    if (!started) {
        std::printf("SKIP  speech never started (no audio output device?): %s\n",
                    tts.LastError().c_str());
    } else {
        CHECK_TRUE(tts.IsSpeaking());
        CHECK_TRUE(!tts.IsPaused());

        CHECK_TRUE(tts.Pause());
        CHECK_TRUE(tts.IsPaused());
        CHECK_TRUE(tts.IsSpeaking());           // 暂停中仍算在朗读
        CHECK_TRUE(tts.Resume());
        CHECK_TRUE(!tts.IsPaused());

        CHECK_TRUE(tts.Stop());
        CHECK_TRUE(!tts.IsSpeaking());
        CHECK_TRUE(!tts.IsPaused());
        CHECK_TRUE(tts.WaitIdle(2000));

        const std::uint64_t second = tts.Speak("\u518d\u89c1\u3002");
        CHECK_TRUE(second > first);
        CHECK_TRUE(tts.Stop());                 // Stop 幂等：没在朗读也能停
        CHECK_TRUE(!tts.IsSpeaking());
    }

    // ── 7. 事件：Started / Ended / 句边界偏移 ──
    std::mutex eventsMutex;
    std::vector<pb::TtsEvent> events;
    tts.SetEventHandler([&eventsMutex, &events](const pb::TtsEvent& event) {
        std::lock_guard<std::mutex> lock(eventsMutex);
        events.push_back(event);
    });

    const std::string text = "\u4f60\u597d\u3002\u4e16\u754c\u3002\u4e2d\u6587\u6d4b\u8bd5\u3002";
    const std::uint64_t spoken = tts.Speak(text);
    CHECK_TRUE(spoken != 0);
    const bool ended = WaitUntil([&eventsMutex, &events, spoken] {
        std::lock_guard<std::mutex> lock(eventsMutex);
        for (const pb::TtsEvent& event : events) {
            if (event.type == pb::TtsEventType::Ended && event.utterance == spoken) return true;
        }
        return false;
    }, 20000);

    if (!ended) {
        std::printf("SKIP  no Ended event within 20s (no audio output device?) — %s\n",
                    tts.LastError().c_str());
    } else {
        bool sawStart = false;
        bool sawBoundary = false;
        {
            std::lock_guard<std::mutex> lock(eventsMutex);
            for (const pb::TtsEvent& event : events) {
                if (event.type == pb::TtsEventType::Error) {
                    std::printf("FAIL  unexpected TTS error event: %s\n", event.error.c_str());
                    CHECK_TRUE(false);
                    continue;
                }
                if (event.utterance != spoken) continue;   // 只核对这一段
                if (event.type == pb::TtsEventType::Started) sawStart = true;
                if (event.type == pb::TtsEventType::WordBoundary
                    || event.type == pb::TtsEventType::SentenceBoundary) {
                    sawBoundary = true;
                    CHECK_TRUE(event.byteOffset <= text.size());
                    CHECK_TRUE(event.byteOffset + event.byteLength <= text.size());
                    CHECK_TRUE(event.charOffset <= text.size());   // UTF-16 偏移不会超过字节数
                }
            }
        }
        CHECK_TRUE(sawStart);
        if (!sawBoundary) {
            std::printf("SKIP  no sentence/word boundary events for this voice\n");
        }
        CHECK_TRUE(tts.WaitIdle(5000));
    }
    tts.SetEventHandler(nullptr);

    // ── 8. Shutdown 幂等；析构（未显式 Shutdown）不挂死 ──
    tts.Shutdown();
    CHECK_TRUE(!tts.IsInitialized());
    tts.Shutdown();
    CHECK_TRUE(!tts.IsInitialized());
    {
        pb::SapiTts transient;
        CHECK_TRUE(transient.Initialize());
        CHECK_TRUE(transient.SetVolume(0));
        CHECK_TRUE(transient.Speak("\u4e34\u65f6\u5b9e\u4f8b\u3002") != 0);   // 播放中析构
    }

    return pb_t::Summary("sapi_tts_test");
}
