// ── PB-001: SapiTts 实现 ───────────────────────────────────────────────
//
// 本机实测结论（Windows 11 + SDK 10.0.26100，写代码前用独立 probe 程序验证过）：
//
//   1. SAPI5 desktop 分类（HKLM\SOFTWARE\Microsoft\Speech\Voices）只有
//      en-US David / en-US Zira / zh-CN Huihui；BACKLOG 里点名的 Yaoyao /
//      Kangkang 只存在于 OneCore 分类（HKLM\SOFTWARE\Microsoft\Speech_OneCore\
//      Voices）。所以「枚举音色」必须合并两个分类，否则中文只有 Huihui。
//   2. OneCore token 可以直接喂给 ISpVoice::SetVoice（返回 S_OK 且能正常朗读，
//      状态机跑到 SPRS_IS_SPEAKING → SPRS_DONE），不需要上 WinRT。
//   3. Speak(SPF_ASYNC) 立刻返回（首次约 100~350ms，之后几毫秒），朗读在
//      SAPI 内部线程；音量 0 时仍然完整走时间轴（可用于静音单测）。
//   4. ISpVoice::SetInterest 必须用 SPFEI(SPEI_xxx) 宏 —— 宏里带了
//      SPFEI_FLAGCHECK（bit30 + bit33）标志位，手工写 (1<<id) 会得到
//      E_INVALIDARG，而且拿不到任何事件。
//   5. SetNotifyCallbackFunction 在本机 SAPI 上「返回 S_OK 但从不回调」，
//      SetNotifySink 需要消息泵；真正可靠的是
//        SetNotifyWin32Event() + GetNotifyEventHandle() + 独立线程 WaitForSingleObject
//        + ISpEventSource::GetEvents()。
//      所以本实现用「通知线程 + Win32 event」，不碰窗口消息，也不依赖 UI 线程。
//   6. 事件偏移：SPEI_SENTENCE_BOUNDARY / SPEI_WORD_BOUNDARY 的
//      wParam = 长度、lParam = 偏移，单位是 UTF-16 code unit（对 "你好。世界。中文测试。"
//      实测 sentence = (0,3)(6,3)(11,5)）。本实现再换算 UTF-8 字节偏移。
//   7. Pause 后 RunningState 仍是 SPRS_IS_SPEAKING（不是独立状态），所以
//      IsPaused() 由本类自己维护；Stop 用 Speak(NULL, SPF_PURGEBEFORESPEAK)。
//
// 线程模型：
//   worker 线程  —— CoInitializeEx(MTA) + CoCreateInstance(SpVoice)，所有
//                   ISpVoice 调用都在这条线程上执行（命令队列串行化）；
//   notifier 线程 —— CoInitializeEx(MTA) + WaitForSingleObject(notifyEvent)
//                   + GetEvents，事件回调和状态机推进都在这里；
//   调用方线程    —— 只做入队/短等待（RunSync），不需要 CoInitialize。
//
// 为什么用两条线程：SAPI 的朗读线程会在阻塞 Speak 时卡住控制调用（同步朗读
// 会一直阻塞到读完），而事件必须及时消费；分开以后 Speak 永远异步，
// Pause/Stop 也不会被朗读阻塞。

#include "platform/SapiTts.h"

#include "utils/encoding.h"
#include "utils/pb_win.h"

#include <sapi.h>

#include <algorithm>
#include <atomic>
#include <cctype>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <utility>
#include <vector>

namespace pb {
namespace {

// SAPI 语音固定分类（SPCAT_VOICES 是 desktop 那一支）。
constexpr wchar_t kSapi5Voices[]  = L"HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Speech\\Voices";
constexpr wchar_t kOneCoreVoices[] = L"HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Speech_OneCore\\Voices";

constexpr int kMinRate = -10;
constexpr int kMaxRate = 10;
constexpr int kMinVolume = 0;
constexpr int kMaxVolume = 100;
constexpr int kInitTimeoutMs = 15000;    // 首次 CoCreate + 枚举，慢盘上留足余量
constexpr int kStopTimeoutMs = 1500;     // Stop 等 SAPI 回到空闲的上限
constexpr int kSyncTimeoutMs = 8000;

// 事件回调里禁止再调阻塞接口（会造成自我死锁），用 thread_local 标记。
thread_local bool t_inEventHandler = false;

struct Utterance {
    std::uint64_t id = 0;
    std::string   tag;
    std::string   utf8;    // 原文（UTF-8），用于把 SAPI 的 UTF-16 偏移换算成字节偏移
    std::wstring  utf16;   // 喂给 SAPI 的实际文本
};

std::string HResultText(const char* what, HRESULT hr)
{
    char buf[96] = {};
    std::snprintf(buf, sizeof buf, "%s (0x%08X)", what, static_cast<unsigned>(hr));
    return buf;
}

bool IsAsciiSpace(char c)
{
    return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f' || c == '\v';
}

std::string TrimAscii(const std::string& s)
{
    std::size_t b = 0;
    std::size_t e = s.size();
    while (b < e && IsAsciiSpace(s[b])) ++b;
    while (e > b && IsAsciiSpace(s[e - 1])) --e;
    return s.substr(b, e - b);
}

std::string LowerAscii(std::string s)
{
    for (char& c : s) {
        c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    }
    return s;
}

// 去重用的规范化名称：小写 → 去掉 "microsoft " 前缀 → 去掉 " desktop" 后缀
// → 折叠连续空格。目的是让 "Microsoft Huihui Desktop"（SAPI5）和
// "Microsoft Huihui"（OneCore）被认成同一个音色。
std::string NormalizeVoiceName(const std::string& name)
{
    std::string out = LowerAscii(name);
    const std::string prefix = "microsoft ";
    if (out.compare(0, prefix.size(), prefix) == 0) out.erase(0, prefix.size());
    const std::string suffix = " desktop";
    if (out.size() > suffix.size()
        && out.compare(out.size() - suffix.size(), suffix.size(), suffix) == 0) {
        out.erase(out.size() - suffix.size());
    }
    std::string collapsed;
    collapsed.reserve(out.size());
    bool lastSpace = false;
    for (char c : out) {
        const bool space = (c == ' ' || c == '\t');
        if (space && lastSpace) continue;
        collapsed.push_back(space ? ' ' : c);
        lastSpace = space;
    }
    return TrimAscii(collapsed);
}

// UTF-8 字符串里前 units 个 UTF-16 code unit 占多少字节。非法字节按 1 unit
// 处理，保证永远不会越界。用于把 SAPI 的 UTF-16 偏移映射回原文的字节偏移。
std::uint32_t Utf8OffsetForUtf16(const std::string& s, std::uint32_t units)
{
    std::uint32_t seen = 0;
    std::size_t i = 0;
    while (i < s.size() && seen < units) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        std::size_t len = 1;
        std::uint32_t unit = 1;
        if (c < 0x80) {
            len = 1;
        } else if ((c & 0xE0) == 0xC0) {
            len = 2;
        } else if ((c & 0xF0) == 0xE0) {
            len = 3;
        } else if ((c & 0xF8) == 0xF0) {
            len = 4;
            unit = 2;          // 增补面字符在 UTF-16 里是代理对
        } else {
            len = 1;           // 非法首字节：按单字节吞掉
        }
        if (i + len > s.size()) len = 1;
        i += len;
        seen += unit;
    }
    return static_cast<std::uint32_t>(i);
}

// SAPI token 的 Attributes 键（Name / Language / Gender ...）。失败返回空串。
std::wstring TokenAttribute(ISpObjectToken* token, const wchar_t* name)
{
    if (!token || !name) return std::wstring();
    ISpDataKey* key = nullptr;
    std::wstring value;
    if (FAILED(token->OpenKey(L"Attributes", &key)) || !key) return value;
    WCHAR* raw = nullptr;
    if (SUCCEEDED(key->GetStringValue(name, &raw)) && raw) {
        value.assign(raw);
        CoTaskMemFree(raw);
    }
    key->Release();
    return value;
}

// SAPI 的 Language 属性是 LANGID 的十六进制字符串（"804" = zh-CN），
// 这里转成 BCP-47 风格的 "zh-CN" / "en-US"。解析不出来就返回空串。
std::string LanguageTagFromAttribute(const std::wstring& attr)
{
    if (attr.empty()) return std::string();
    wchar_t* end = nullptr;
    const unsigned long raw = wcstoul(attr.c_str(), &end, 16);
    if (end == attr.c_str() || raw == 0 || raw > 0xFFFFul) return std::string();
    const LANGID langid = static_cast<LANGID>(raw);
    const LCID lcid = MAKELCID(langid, SORT_DEFAULT);

    wchar_t buf[32] = {};
    std::string tag;
    if (GetLocaleInfoW(lcid, LOCALE_SISO639LANGNAME, buf, _countof(buf)) > 0) {
        tag = WideToUtf8(buf);
    }
    if (tag.empty()) return std::string();
    if (GetLocaleInfoW(lcid, LOCALE_SISO3166CTRYNAME, buf, _countof(buf)) > 0) {
        const std::string country = WideToUtf8(buf);
        if (!country.empty()) tag += "-" + country;
    }
    return tag;
}

HRESULT OpenVoiceCategory(const wchar_t* categoryId, ISpObjectTokenCategory** out)
{
    if (!out) return E_POINTER;
    *out = nullptr;
    ISpObjectTokenCategory* category = nullptr;
    HRESULT hr = CoCreateInstance(CLSID_SpObjectTokenCategory, nullptr, CLSCTX_ALL,
                                  IID_ISpObjectTokenCategory,
                                  reinterpret_cast<void**>(&category));
    if (FAILED(hr) || !category) return hr;
    hr = category->SetId(categoryId, FALSE);
    if (FAILED(hr)) {
        category->Release();
        return hr;
    }
    *out = category;
    return S_OK;
}

void EnumerateCategory(const wchar_t* categoryId, bool oneCore, std::vector<TtsVoiceInfo>& out)
{
    ISpObjectTokenCategory* category = nullptr;
    if (FAILED(OpenVoiceCategory(categoryId, &category)) || !category) return;

    IEnumSpObjectTokens* enumTokens = nullptr;
    if (SUCCEEDED(category->EnumTokens(nullptr, nullptr, &enumTokens)) && enumTokens) {
        ULONG count = 0;
        enumTokens->GetCount(&count);
        for (ULONG i = 0; i < count; ++i) {
            ISpObjectToken* token = nullptr;
            if (FAILED(enumTokens->Item(i, &token)) || !token) continue;

            TtsVoiceInfo info;
            info.oneCore = oneCore;
            info.name = WideToUtf8(TokenAttribute(token, L"Name"));
            info.language = LanguageTagFromAttribute(TokenAttribute(token, L"Language"));
            info.gender = WideToUtf8(TokenAttribute(token, L"Gender"));
            WCHAR* id = nullptr;
            if (SUCCEEDED(token->GetId(&id)) && id) {
                info.id = WideToUtf8(id);
                CoTaskMemFree(id);
            }
            token->Release();

            if (info.id.empty() || info.name.empty()) continue;
            out.push_back(std::move(info));
        }
        enumTokens->Release();
    }
    category->Release();
}

// SAPI5 desktop 优先保留（与 ISpVoice 的兼容性最好），OneCore 只补它没有的。
std::vector<TtsVoiceInfo> MergeAndDedupe(std::vector<TtsVoiceInfo> desktop,
                                         std::vector<TtsVoiceInfo> oneCore)
{
    std::vector<TtsVoiceInfo> merged;
    std::unordered_map<std::string, std::size_t> seen;
    auto add = [&merged, &seen](const TtsVoiceInfo& voice) {
        const std::string key = NormalizeVoiceName(voice.name) + "|" + voice.language;
        if (seen.find(key) != seen.end()) return;
        seen.emplace(key, merged.size());
        merged.push_back(voice);
    };
    for (const TtsVoiceInfo& voice : desktop) add(voice);
    for (const TtsVoiceInfo& voice : oneCore) add(voice);
    return merged;
}

} // namespace

// ── Impl ───────────────────────────────────────────────────────────────
struct SapiTts::Impl {
    // ── 同步命令（RunSync）：调用方在 worker 线程上执行并等待结果 ──
    struct SyncTask {
        std::mutex mutex;
        std::condition_variable cv;
        bool done = false;
        bool ok = false;
        std::function<bool()> body;
    };

    struct Command {
        std::shared_ptr<SyncTask> sync;   // 非空 = 同步
        std::function<void()> fire;       // 非空 = 异步（Speak）
    };

    mutable std::mutex mutex;                                  // 保护下面所有状态
    std::condition_variable workerCv;                          // 唤醒 worker
    std::condition_variable stateCv;                           // IsSpeaking / WaitIdle / Stop
    std::condition_variable readyCv;                           // Initialize 等 worker 就绪
    std::mutex initMutex;                                      // Initialize / Shutdown 互斥

    std::deque<Command> commands;
    std::deque<std::shared_ptr<Utterance>> pending;            // 已交给 SAPI、还没 START 的
    std::shared_ptr<Utterance> current;                        // START 事件对应的那一段

    ISpVoice* voice = nullptr;
    ISpEventSource* events = nullptr;
    HANDLE notifyEvent = nullptr;

    bool started = false;      // 线程已拉起
    bool ready = false;        // worker 完成握手
    bool initOk = false;       // CoCreate 成功
    bool stopping = false;

    std::vector<TtsVoiceInfo> voices;
    std::string currentVoiceId;
    std::string currentVoiceName;
    std::string lastError;

    std::uint64_t nextUtteranceId = 1;
    int rate = 0;
    int volume = 100;

    std::atomic<bool> speaking{false};
    std::atomic<bool> paused{false};
    std::atomic<bool> initialized{false};

    EventHandler handler;
    std::thread worker;
    std::thread notifier;
    bool dispatching = false;   // notifier 正在回调（WaitIdle 要把回调也等到）

    // 失败事件先攒起来，统一由 notifier 线程派发（保证「回调只在一个内部线程」）。
    std::deque<TtsEvent> pendingEvents;

    // ── 错误 ──
    void SetErrorLocked(const std::string& error) { lastError = error; }

    void SetError(const std::string& error)
    {
        std::lock_guard<std::mutex> lock(mutex);
        lastError = error;
    }

    void ClearError()
    {
        std::lock_guard<std::mutex> lock(mutex);
        lastError.clear();
    }

    // ── 命令投递 ──
    bool RunSync(std::function<bool()> body, int timeoutMs = kSyncTimeoutMs)
    {
        // 事件回调里再调阻塞接口会与 notifier 线程互相等待 —— 直接拒绝。
        if (t_inEventHandler) {
            SetError("reentrant_call");
            return false;
        }
        // 万一有人从 worker 线程自己调进来（比如以后加的内部组合操作），
        // 直接内联执行，避免自我等待。
        if (std::this_thread::get_id() == worker.get_id()) {
            bool ok = false;
            try { ok = body ? body() : false; } catch (...) { ok = false; }
            return ok;
        }

        auto task = std::make_shared<SyncTask>();
        task->body = std::move(body);
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (!started || stopping) {
                lastError = "not_initialized";
                return false;
            }
            Command command;
            command.sync = task;
            commands.push_back(std::move(command));
        }
        workerCv.notify_one();

        std::unique_lock<std::mutex> lock(task->mutex);
        if (!task->cv.wait_for(lock, std::chrono::milliseconds(timeoutMs),
                               [&task] { return task->done; })) {
            SetError("timeout");
            return false;
        }
        return task->ok;
    }

    void PostAsync(std::function<void()> body)
    {
        if (!body) return;
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (!started || stopping) {
                lastError = "not_initialized";
                return;
            }
            Command command;
            command.fire = std::move(body);
            commands.push_back(std::move(command));
        }
        workerCv.notify_one();
        stateCv.notify_all();   // 让正在 WaitIdle 的线程重新判断「还没空闲」
    }

    void QueueEvent(TtsEvent event)
    {
        HANDLE kick = nullptr;
        {
            std::lock_guard<std::mutex> lock(mutex);
            pendingEvents.push_back(std::move(event));
            kick = notifyEvent;            // 在锁内取句柄，避免与 Shutdown 抢占后悬空
        }
        if (kick) SetEvent(kick);          // 借 SAPI 的 event 把 notifier 叫醒
    }

    int RateOrDefault() const
    {
        std::lock_guard<std::mutex> lock(mutex);
        return rate;
    }

    int VolumeOrDefault() const
    {
        std::lock_guard<std::mutex> lock(mutex);
        return volume;
    }

    // ── worker ──
    void WorkerLoop()
    {
        const HRESULT coInit = CoInitializeEx(nullptr, COINIT_MULTITHREADED);

        ISpVoice* createdVoice = nullptr;
        ISpEventSource* createdEvents = nullptr;
        HANDLE createdEvent = nullptr;
        bool ok = false;

        if (SUCCEEDED(coInit)) {
            HRESULT hr = CoCreateInstance(CLSID_SpVoice, nullptr, CLSCTX_ALL, IID_ISpVoice,
                                          reinterpret_cast<void**>(&createdVoice));
            if (SUCCEEDED(hr) && createdVoice) {
                hr = createdVoice->QueryInterface(IID_ISpEventSource,
                                                  reinterpret_cast<void**>(&createdEvents));
                if (SUCCEEDED(hr) && createdEvents) {
                    // ⚠ 必须用 SPFEI()（含 SPFEI_FLAGCHECK 标志位），
                    // 否则 SetInterest 返回 E_INVALIDARG 且永远拿不到事件。
                    const ULONGLONG interest = SPFEI(SPEI_START_INPUT_STREAM)
                                             | SPFEI(SPEI_END_INPUT_STREAM)
                                             | SPFEI(SPEI_WORD_BOUNDARY)
                                             | SPFEI(SPEI_SENTENCE_BOUNDARY);
                    hr = createdEvents->SetInterest(interest, interest);
                    if (SUCCEEDED(hr)) {
                        hr = createdVoice->SetNotifyWin32Event();
                        if (SUCCEEDED(hr)) {
                            createdEvent = createdVoice->GetNotifyEventHandle();
                            ok = (createdEvent != nullptr);
                        }
                    }
                }
            }
        }

        {
            std::lock_guard<std::mutex> lock(mutex);
            voice = createdVoice;
            events = createdEvents;
            notifyEvent = createdEvent;
            initOk = ok;
            ready = true;
            if (!ok) {
                lastError = HResultText("sapi:init", FAILED(coInit) ? coInit : E_FAIL);
            }
        }
        readyCv.notify_all();

        if (ok) {
            // 事件通知线程只在这个 worker 就绪后才拉起来。
            notifier = std::thread([this] { NotifyLoop(); });

            for (;;) {
                Command command;
                {
                    std::unique_lock<std::mutex> lock(mutex);
                    workerCv.wait(lock, [this] { return stopping || !commands.empty(); });
                    if (stopping) break;
                    command = std::move(commands.front());
                    commands.pop_front();
                }
                RunCommand(command);
                stateCv.notify_all();   // 命令执行完，WaitIdle 需要重新判断
            }
        } else {
            std::unique_lock<std::mutex> lock(mutex);
            workerCv.wait(lock, [this] { return stopping; });
        }

        // 收尾：notifier 已在 Shutdown 里 join 过，这里只释放 COM 对象。
        // 先打断在播的内容，保证「退出应用即停」（BACKLOG PB-001 验收项），
        // 而不是依赖 Release 之后 SAPI 自己什么时候收尾。
        if (createdVoice) createdVoice->Speak(nullptr, SPF_PURGEBEFORESPEAK, nullptr);
        if (createdEvents) createdEvents->Release();
        if (createdVoice) createdVoice->Release();
        if (SUCCEEDED(coInit)) CoUninitialize();
        {
            std::lock_guard<std::mutex> lock(mutex);
            voice = nullptr;
            events = nullptr;
            notifyEvent = nullptr;
        }
    }

    void RunCommand(Command& command)
    {
        if (command.sync) {
            bool ok = false;
            try {
                ok = command.sync->body ? command.sync->body() : false;
            } catch (...) {
                ok = false;
            }
            {
                std::lock_guard<std::mutex> lock(command.sync->mutex);
                command.sync->ok = ok;
                command.sync->done = true;
            }
            command.sync->cv.notify_all();
        } else if (command.fire) {
            try {
                command.fire();
            } catch (...) {
                // 后台线程里不能让异常逃出去
            }
        }
    }

    // ── notifier ──
    void NotifyLoop()
    {
        const HRESULT coInit = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        for (;;) {
            HANDLE handle = nullptr;
            bool stop = false;
            {
                std::lock_guard<std::mutex> lock(mutex);
                handle = notifyEvent;
                stop = stopping;
            }
            if (stop) break;
            if (!handle) {
                std::unique_lock<std::mutex> lock(mutex);
                stateCv.wait_for(lock, std::chrono::milliseconds(50));
                continue;
            }
            const DWORD wait = WaitForSingleObject(handle, 100);
            {
                std::lock_guard<std::mutex> lock(mutex);
                stop = stopping;
            }
            if (stop) break;
            if (wait == WAIT_OBJECT_0) ConsumeEvents();
        }
        if (SUCCEEDED(coInit)) CoUninitialize();
    }

    void ConsumeEvents()
    {
        std::vector<TtsEvent> out;
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (events) {
                SPEVENT raw[16] = {};
                for (;;) {
                    ULONG fetched = 0;
                    const HRESULT hr = events->GetEvents(_countof(raw), raw, &fetched);
                    if (FAILED(hr) || fetched == 0) break;
                    for (ULONG i = 0; i < fetched; ++i) HandleEventLocked(raw[i], out);
                    if (fetched < _countof(raw)) break;
                }
            }
            for (TtsEvent& event : pendingEvents) out.push_back(std::move(event));
            pendingEvents.clear();
            dispatching = !out.empty();
        }
        if (out.empty()) return;
        DispatchAll(out);
        {
            std::lock_guard<std::mutex> lock(mutex);
            dispatching = false;
        }
        stateCv.notify_all();   // WaitIdle 可能在等这一步
    }

    TtsEvent MakeEventLocked(TtsEventType type, const std::shared_ptr<Utterance>& utterance) const
    {
        TtsEvent event;
        event.type = type;
        if (utterance) {
            event.utterance = utterance->id;
            event.tag = utterance->tag;
        }
        return event;
    }

    void HandleEventLocked(const SPEVENT& raw, std::vector<TtsEvent>& out)
    {
        switch (raw.eEventId) {
        case SPEI_START_INPUT_STREAM: {
            if (!pending.empty()) {
                current = pending.front();
                pending.pop_front();
            } else {
                current.reset();
            }
            // Do NOT clear paused here: a Pause() that lands before the first
            // START event must survive (UI can pause immediately after Speak).
            if (current) out.push_back(MakeEventLocked(TtsEventType::Started, current));
            break;
        }
        case SPEI_END_INPUT_STREAM: {
            if (current) out.push_back(MakeEventLocked(TtsEventType::Ended, current));
            current.reset();
            paused.store(false, std::memory_order_relaxed);
            if (pending.empty()) {
                speaking.store(false, std::memory_order_release);
                stateCv.notify_all();
            }
            break;
        }
        case SPEI_WORD_BOUNDARY:
        case SPEI_SENTENCE_BOUNDARY: {
            if (!current) break;
            TtsEvent event = MakeEventLocked(raw.eEventId == SPEI_WORD_BOUNDARY
                                                 ? TtsEventType::WordBoundary
                                                 : TtsEventType::SentenceBoundary,
                                             current);
            event.charOffset = static_cast<std::uint32_t>(raw.lParam);
            event.charLength = static_cast<std::uint32_t>(raw.wParam);
            // SAPI 给的是 UTF-16 code unit；前端要的是原文（UTF-8）字节偏移。
            const std::uint32_t begin = Utf8OffsetForUtf16(current->utf8, event.charOffset);
            const std::uint32_t end = Utf8OffsetForUtf16(current->utf8,
                                                         event.charOffset + event.charLength);
            event.byteOffset = begin;
            event.byteLength = (end > begin) ? (end - begin) : 0;
            out.push_back(std::move(event));
            break;
        }
        default:
            break;   // VOICE_CHANGE / AUDIO_LEVEL 等不关心
        }
    }

    void DispatchAll(const std::vector<TtsEvent>& eventsToSend)
    {
        if (eventsToSend.empty()) return;
        EventHandler callback;
        {
            std::lock_guard<std::mutex> lock(mutex);
            callback = handler;
        }
        if (!callback) return;
        t_inEventHandler = true;
        for (const TtsEvent& event : eventsToSend) {
            try {
                callback(event);
            } catch (...) {
                // 回调抛异常不能穿过 Win32/COM 边界
            }
        }
        t_inEventHandler = false;
    }

    // ── 音色 ──
    bool EnumerateOnWorker()
    {
        std::vector<TtsVoiceInfo> desktop;
        std::vector<TtsVoiceInfo> oneCore;
        EnumerateCategory(kSapi5Voices, false, desktop);
        EnumerateCategory(kOneCoreVoices, true, oneCore);

        std::vector<TtsVoiceInfo> merged = MergeAndDedupe(std::move(desktop), std::move(oneCore));
        {
            std::lock_guard<std::mutex> lock(mutex);
            voices = std::move(merged);
        }
        return true;
    }

    void RefreshCurrentVoiceOnWorker()
    {
        std::string id;
        std::string name;
        if (voice) {
            ISpObjectToken* token = nullptr;
            if (SUCCEEDED(voice->GetVoice(&token)) && token) {
                WCHAR* raw = nullptr;
                if (SUCCEEDED(token->GetId(&raw)) && raw) {
                    id = WideToUtf8(raw);
                    CoTaskMemFree(raw);
                }
                name = WideToUtf8(TokenAttribute(token, L"Name"));
                token->Release();
            }
        }
        std::lock_guard<std::mutex> lock(mutex);
        currentVoiceId = id;
        currentVoiceName = name;
    }

    ISpObjectToken* FindTokenByUtf8Id(const std::string& id) const
    {
        const std::wstring wanted = Utf8ToWide(id);
        if (wanted.empty()) return nullptr;
        const wchar_t* categories[] = { kSapi5Voices, kOneCoreVoices };
        for (const wchar_t* categoryId : categories) {
            ISpObjectTokenCategory* category = nullptr;
            if (FAILED(OpenVoiceCategory(categoryId, &category)) || !category) continue;
            IEnumSpObjectTokens* enumTokens = nullptr;
            if (SUCCEEDED(category->EnumTokens(nullptr, nullptr, &enumTokens)) && enumTokens) {
                ULONG count = 0;
                enumTokens->GetCount(&count);
                for (ULONG i = 0; i < count; ++i) {
                    ISpObjectToken* token = nullptr;
                    if (FAILED(enumTokens->Item(i, &token)) || !token) continue;
                    WCHAR* raw = nullptr;
                    bool match = false;
                    if (SUCCEEDED(token->GetId(&raw)) && raw) {
                        match = (wanted == raw);
                        CoTaskMemFree(raw);
                    }
                    if (match) {
                        enumTokens->Release();
                        category->Release();
                        return token;
                    }
                    token->Release();
                }
                enumTokens->Release();
            }
            category->Release();
        }
        return nullptr;
    }

    bool SelectVoiceOnWorker(const std::string& id)
    {
        if (!voice) {
            SetError("not_initialized");
            return false;
        }
        if (id.empty()) {
            const HRESULT hr = voice->SetVoice(nullptr);
            if (FAILED(hr)) {
                SetError(HResultText("sapi:set_voice", hr));
                return false;
            }
            RefreshCurrentVoiceOnWorker();
            return true;
        }
        bool known = false;
        {
            std::lock_guard<std::mutex> lock(mutex);
            for (const TtsVoiceInfo& info : voices) {
                if (info.id == id) {
                    known = true;
                    break;
                }
            }
        }
        if (!known) {
            SetError("voice_not_found");
            return false;
        }
        ISpObjectToken* token = FindTokenByUtf8Id(id);
        if (!token) {
            SetError("voice_not_found");
            return false;
        }
        const HRESULT hr = voice->SetVoice(token);
        token->Release();
        if (FAILED(hr)) {
            SetError(HResultText("sapi:set_voice", hr));
            return false;
        }
        RefreshCurrentVoiceOnWorker();
        return true;
    }

    bool ApplySettingsOnWorker(int newRate, int newVolume)
    {
        if (voice) {
            const HRESULT rateHr = voice->SetRate(newRate);
            if (FAILED(rateHr)) {
                SetError(HResultText("sapi:set_rate", rateHr));
                return false;
            }
            const HRESULT volumeHr = voice->SetVolume(static_cast<USHORT>(newVolume));
            if (FAILED(volumeHr)) {
                SetError(HResultText("sapi:set_volume", volumeHr));
                return false;
            }
        }
        std::lock_guard<std::mutex> lock(mutex);
        rate = newRate;
        volume = newVolume;
        return true;
    }

    bool PrepareOnWorker()
    {
        if (!voice) {
            SetError("not_initialized");
            return false;
        }
        int cachedRate = 0;
        int cachedVolume = 100;
        {
            std::lock_guard<std::mutex> lock(mutex);
            cachedRate = rate;
            cachedVolume = volume;
        }
        if (!ApplySettingsOnWorker(cachedRate, cachedVolume)) return false;
        RefreshCurrentVoiceOnWorker();
        return true;
    }

    // ── 朗读 ──
    void FailUtterance(const std::shared_ptr<Utterance>& utterance, const std::string& error)
    {
        SetError(error);
        TtsEvent event;
        event.type = TtsEventType::Error;
        event.error = error;
        if (utterance) {
            event.utterance = utterance->id;
            event.tag = utterance->tag;
        }
        QueueEvent(std::move(event));
    }

    void SpeakOnWorker(const std::shared_ptr<Utterance>& utterance, bool flush)
    {
        if (!voice) {
            FailUtterance(utterance, "not_initialized");
            return;
        }
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (flush) pending.clear();
            // 先入队再 Speak：SAPI 可能在 Speak 返回前就发出 START 事件，
            // 顺序反了会把这一段错配给上一段。
            pending.push_back(utterance);
        }

        const DWORD flags = SPF_ASYNC | (flush ? SPF_PURGEBEFORESPEAK : 0);
        const HRESULT hr = voice->Speak(utterance->utf16.c_str(), flags, nullptr);
        if (FAILED(hr)) {
            bool wasPending = false;
            {
                std::lock_guard<std::mutex> lock(mutex);
                for (auto it = pending.begin(); it != pending.end(); ++it) {
                    if (*it == utterance) {
                        pending.erase(it);
                        wasPending = true;
                        break;
                    }
                }
            }
            if (wasPending) FailUtterance(utterance, HResultText("sapi:speak", hr));
            return;
        }
        speaking.store(true, std::memory_order_release);
    }

    bool PauseOnWorker()
    {
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (!current && pending.empty()) {
                lastError = "not_speaking";
                return false;
            }
        }
        if (!voice) {
            SetError("not_initialized");
            return false;
        }
        const HRESULT hr = voice->Pause();
        if (FAILED(hr)) {
            SetError(HResultText("sapi:pause", hr));
            return false;
        }
        paused.store(true, std::memory_order_relaxed);
        return true;
    }

    bool ResumeOnWorker()
    {
        if (!voice) {
            SetError("not_initialized");
            return false;
        }
        if (!paused.load(std::memory_order_relaxed)) {
            SetError("not_paused");
            return false;
        }
        const HRESULT hr = voice->Resume();
        if (FAILED(hr)) {
            SetError(HResultText("sapi:resume", hr));
            return false;
        }
        paused.store(false, std::memory_order_relaxed);
        return true;
    }

    bool StopOnWorker()
    {
        {
            std::lock_guard<std::mutex> lock(mutex);
            pending.clear();
        }
        if (voice) {
            // SPF_PURGEBEFORESPEAK + 空文本 = 丢弃当前与所有排队。
            voice->Speak(nullptr, SPF_PURGEBEFORESPEAK, nullptr);
        }
        std::unique_lock<std::mutex> lock(mutex);
        paused.store(false, std::memory_order_relaxed);
        const bool idle = stateCv.wait_for(lock, std::chrono::milliseconds(kStopTimeoutMs),
                                           [this] { return current == nullptr; });
        if (!idle) current.reset();
        speaking.store(false, std::memory_order_release);
        return true;
    }

    // 「空闲」= 没有排队命令（Speak 已交给 SAPI）、SAPI 没有在朗读、
    // 也没有正在派发的事件回调。少任何一条都会让 WaitIdle 早返回。
    bool IsIdleLocked() const
    {
        return !speaking.load(std::memory_order_acquire) && current == nullptr
               && pending.empty() && commands.empty() && !dispatching;
    }

    bool WaitIdleLocked(std::unique_lock<std::mutex>& lock, int timeoutMs)
    {
        return stateCv.wait_for(lock, std::chrono::milliseconds(timeoutMs),
                                [this] { return IsIdleLocked(); });
    }

    // Shutdown 的实际实现。调用方必须已持有 initMutex（析构 / 公开 Shutdown /
    // Initialize 的半失败回滚都走这里，避免对 initMutex 二次加锁死锁）。
    void ShutdownLocked()
    {
        if (!worker.joinable() && !started) {
            initialized.store(false, std::memory_order_release);
            return;
        }
        {
            std::lock_guard<std::mutex> lock(mutex);
            stopping = true;
            pending.clear();
            if (notifyEvent) SetEvent(notifyEvent);
        }
        workerCv.notify_all();

        if (notifier.joinable()) notifier.join();
        if (worker.joinable()) worker.join();

        {
            std::lock_guard<std::mutex> lock(mutex);
            started = false;
            ready = false;
            initOk = false;
            voice = nullptr;
            events = nullptr;
            notifyEvent = nullptr;
            current.reset();
            commands.clear();
            pendingEvents.clear();
            speaking.store(false, std::memory_order_release);
            paused.store(false, std::memory_order_relaxed);
        }
        stateCv.notify_all();
        initialized.store(false, std::memory_order_release);
    }
};

// ── 公开接口 ───────────────────────────────────────────────────────────
SapiTts::SapiTts()
    : m_impl(new Impl())
{
}

SapiTts::~SapiTts()
{
    Shutdown();
}

bool SapiTts::Initialize()
{
    std::lock_guard<std::mutex> initLock(m_impl->initMutex);
    if (m_impl->initialized.load(std::memory_order_acquire)) return true;
    m_impl->ClearError();

    {
        std::lock_guard<std::mutex> lock(m_impl->mutex);
        if (m_impl->started) return false;   // 上一次失败留下的半成品，先 Shutdown
        m_impl->started = true;
        m_impl->stopping = false;
        m_impl->ready = false;
        m_impl->initOk = false;
    }

    try {
        m_impl->worker = std::thread([impl = m_impl.get()] { impl->WorkerLoop(); });
    } catch (...) {
        std::lock_guard<std::mutex> lock(m_impl->mutex);
        m_impl->started = false;
        m_impl->lastError = "thread_create_failed";
        return false;
    }

    bool ready = false;
    bool ok = false;
    {
        std::unique_lock<std::mutex> lock(m_impl->mutex);
        ready = m_impl->readyCv.wait_for(lock, std::chrono::milliseconds(kInitTimeoutMs),
                                         [this] { return m_impl->ready; });
        ok = ready && m_impl->initOk;
    }
    if (!ok) {
        // 把半启动的线程收掉
        {
            std::lock_guard<std::mutex> lock(m_impl->mutex);
            m_impl->stopping = true;
        }
        m_impl->workerCv.notify_all();
        if (m_impl->worker.joinable()) m_impl->worker.join();
        {
            std::lock_guard<std::mutex> lock(m_impl->mutex);
            m_impl->started = false;
            m_impl->ready = false;
            if (m_impl->lastError.empty()) m_impl->lastError = ready ? "sapi:init_failed" : "init_timeout";
        }
        return false;
    }

    // 枚举音色（best effort：失败也允许继续，只是 Speak 会明确拒绝）+
    // 应用缓存的 rate/volume + 读回系统默认音色。
    if (!m_impl->RunSync([impl = m_impl.get()] { return impl->EnumerateOnWorker(); })) {
        // 枚举失败不阻断初始化，但保留 LastError 供上层提示
    }
    if (!m_impl->RunSync([impl = m_impl.get()] { return impl->PrepareOnWorker(); })) {
        m_impl->ShutdownLocked();   // 已持 initMutex，不能走公开 Shutdown()
        return false;
    }

    m_impl->initialized.store(true, std::memory_order_release);
    return true;
}

void SapiTts::Shutdown()
{
    if (!m_impl) return;
    std::lock_guard<std::mutex> initLock(m_impl->initMutex);
    m_impl->ShutdownLocked();
}

bool SapiTts::IsInitialized() const
{
    return m_impl && m_impl->initialized.load(std::memory_order_acquire);
}

bool SapiTts::EnumerateVoices(std::vector<TtsVoiceInfo>& out)
{
    out.clear();
    if (!Initialize()) return false;
    bool ok = m_impl->RunSync([impl = m_impl.get()] { return impl->EnumerateOnWorker(); });
    if (!ok) return false;
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    out = m_impl->voices;
    return true;
}

bool SapiTts::SelectVoice(const std::string& voiceId)
{
    if (!Initialize()) return false;
    m_impl->ClearError();
    return m_impl->RunSync([impl = m_impl.get(), voiceId] {
        return impl->SelectVoiceOnWorker(voiceId);
    });
}

bool SapiTts::SelectVoiceByName(const std::string& name)
{
    if (!Initialize()) return false;
    const std::string trimmed = TrimAscii(name);
    if (trimmed.empty()) {
        m_impl->SetError("voice_not_found");
        return false;
    }
    std::vector<TtsVoiceInfo> candidates;
    {
        std::lock_guard<std::mutex> lock(m_impl->mutex);
        candidates = m_impl->voices;
    }
    const std::string lowerWanted = LowerAscii(trimmed);
    const std::string normalizedWanted = NormalizeVoiceName(trimmed);
    std::string foundId;
    for (const TtsVoiceInfo& voice : candidates) {
        if (LowerAscii(voice.name) == lowerWanted) {
            foundId = voice.id;
            break;
        }
    }
    if (foundId.empty()) {
        for (const TtsVoiceInfo& voice : candidates) {
            if (!normalizedWanted.empty() && NormalizeVoiceName(voice.name) == normalizedWanted) {
                foundId = voice.id;
                break;
            }
        }
    }
    if (foundId.empty()) {
        m_impl->SetError("voice_not_found");
        return false;
    }
    return SelectVoice(foundId);
}

std::string SapiTts::CurrentVoiceId() const
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    return m_impl->currentVoiceId;
}

std::string SapiTts::CurrentVoiceName() const
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    return m_impl->currentVoiceName;
}

bool SapiTts::SetRate(int rate)
{
    if (rate < kMinRate || rate > kMaxRate) {
        m_impl->SetError("rate_out_of_range");
        return false;
    }
    if (!Initialize()) return false;
    m_impl->ClearError();
    return m_impl->RunSync([impl = m_impl.get(), rate] {
        return impl->ApplySettingsOnWorker(rate, impl->VolumeOrDefault());
    });
}

int SapiTts::Rate() const
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    return m_impl->rate;
}

bool SapiTts::SetVolume(int volume)
{
    if (volume < kMinVolume || volume > kMaxVolume) {
        m_impl->SetError("volume_out_of_range");
        return false;
    }
    if (!Initialize()) return false;
    m_impl->ClearError();
    return m_impl->RunSync([impl = m_impl.get(), volume] {
        return impl->ApplySettingsOnWorker(impl->RateOrDefault(), volume);
    });
}

int SapiTts::Volume() const
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    return m_impl->volume;
}

std::uint64_t SapiTts::Speak(const std::string& utf8, const TtsSpeakOptions& options)
{
    if (!Initialize()) return 0;
    if (TrimAscii(utf8).empty()) {
        m_impl->SetError("empty_text");
        return 0;
    }
    {
        std::lock_guard<std::mutex> lock(m_impl->mutex);
        if (m_impl->voices.empty()) {
            m_impl->lastError = "no_voice_installed";
            return 0;
        }
    }

    auto utterance = std::make_shared<Utterance>();
    utterance->utf8 = utf8;
    utterance->utf16 = Utf8ToWide(utf8);
    utterance->tag = options.tag;
    if (utterance->utf16.empty()) {
        m_impl->SetError("empty_text");
        return 0;
    }
    {
        std::lock_guard<std::mutex> lock(m_impl->mutex);
        utterance->id = m_impl->nextUtteranceId++;
    }
    const bool flush = options.flush;
    m_impl->PostAsync([impl = m_impl.get(), utterance, flush] {
        impl->SpeakOnWorker(utterance, flush);
    });
    return utterance->id;
}

bool SapiTts::Pause()
{
    if (!Initialize()) return false;
    m_impl->ClearError();
    return m_impl->RunSync([impl = m_impl.get()] { return impl->PauseOnWorker(); });
}

bool SapiTts::Resume()
{
    if (!Initialize()) return false;
    m_impl->ClearError();
    return m_impl->RunSync([impl = m_impl.get()] { return impl->ResumeOnWorker(); });
}

bool SapiTts::Stop()
{
    if (!Initialize()) return false;
    m_impl->ClearError();
    return m_impl->RunSync([impl = m_impl.get()] { return impl->StopOnWorker(); });
}

bool SapiTts::IsSpeaking() const
{
    return m_impl && m_impl->speaking.load(std::memory_order_acquire);
}

bool SapiTts::IsPaused() const
{
    return m_impl && m_impl->paused.load(std::memory_order_relaxed);
}

bool SapiTts::WaitIdle(int timeoutMs)
{
    if (!m_impl) return true;
    std::unique_lock<std::mutex> lock(m_impl->mutex);
    if (timeoutMs <= 0) return m_impl->IsIdleLocked();
    return m_impl->WaitIdleLocked(lock, timeoutMs);
}

void SapiTts::SetEventHandler(EventHandler handler)
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    m_impl->handler = std::move(handler);
}

std::string SapiTts::LastError() const
{
    std::lock_guard<std::mutex> lock(m_impl->mutex);
    return m_impl->lastError;
}

} // namespace pb
