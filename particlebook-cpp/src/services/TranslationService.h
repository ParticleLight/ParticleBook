#pragma once
// ── PB-002：TranslationService —— AI 翻译核心（OpenAI 兼容 /chat/completions）──
//
// 目标（docs/BACKLOG.md PB-002）：选中文本 → 用用户自己配置的第三方 AI 服务
// 翻译后返回。我们不提供 Key、不中转、不内置免费源；只做「一次批量段落翻译」。
//
// 边界（安全第一，PB-002 验收：Key 只存本机、不写日志、不上传）：
//   * 本类不保存、不缓存、不落盘 Key：Key 由调用方从 SecretStore（DPAPI）读出
//     后放进 TranslationProviderConfig::apiKey，只在本次调用的 Authorization
//     头里出现一次。本类不写日志；错误 / 诊断字符串一律经过脱敏（Redact），
//     绝不会包含 Key，也不会带请求正文。
//   * SecretStore 键名约定：pb::kTranslationApiKeySecretName
//     （= "translation.apiKey"）。settings 只存非敏感项，见
//     platform/SettingsService.h 的 translation.* schema。
//   * 只发送选中的文本 + 必要参数：不发送书名 / 文件路径 / 书号；context
//     只在调用方明确传入（用户勾选的上下文或术语表）时才发送。
//   * 不内置任何公共 API / 中转地址；baseUrl 必须由用户配置且是 http(s)。
//
// 翻译协议：
//   POST <baseUrl>/chat/completions
//   Authorization: Bearer <apiKey>
//   body: { model, messages:[system, user], temperature, stream:false }
//   user 消息体是 JSON：{ sourceLang, targetLang, context?, segments:[{id,text}] }
//   system 提示词要求「只输出 JSON、保持段落边界/顺序/数量、URL/数字/标记不译、
//   不要解释」，并声明段落内容是数据、不是指令（防提示词注入）。
//   响应取 choices[0].message.content，解析其中的 {"segments":[{id,text}]}；
//   单段请求允许模型直接返回纯文本（不少模型会忽略 JSON 指令，这是兜底）。
//
// 批量 / 限流：
//   * 一次 TranslateBatch 最多 Options::maxSegmentsPerCall 个非空段（默认 200）
//     ——「不把整本书一次发」；超过返回 TooLarge，调用方应分章/分页调用。
//   * 内部按 maxSegmentsPerRequest（默认 8）与 maxCharsPerRequest（默认 6000
//     字节）切块，逐块串行请求（并发=1，避免触发第三方限流）；块内单段超过
//     maxCharsPerSegment（默认 8000 字节）直接 TooLarge，不发送。
//   * 空 / 只含空白的段不发送，结果里就是空译文；全是空段时 ok=true 且零请求。
//
// 取消：
//   协作式。TranslationCancelToken 是共享原子标志（拷贝共享）；每次发请求前
//   检查；注入的 transport 每次阻塞调用前后也会检查。WinHTTP 阻塞在单次
//   WinHTTP* 调用里时无法即时打断，最坏延迟 = 一次超时（config.timeoutMs）。
//
// 线程模型：
//   * TranslateBatch 同步阻塞（网络 + 解析），必须在 JobService 的 worker
//     线程调用，不要在 WebView2 UI 线程直接调；
//   * 本类无可变状态，同一个实例可被多个 worker 并发调用（transport 若注入
//     自定义实现，其自身线程安全由调用方保证）。
//   * 不包含 <windows.h>；单测可直接 include 本头文件并注入 mock transport。
//
// 接线（App / handler 侧，本轮不改 App / Bridge / CMake / renderer）：
//   auto settings = pb::SettingsService::Normalize(db->GetSettings()).values;
//   auto cfg = pb::TranslationService::ConfigFromSettings(settings);  // 无 Key
//   SecretStore secrets(SecretStore::DefaultPath());
//   if (!secrets.Get(pb::kTranslationApiKeySecretName, cfg.apiKey)) -> 提示配置 Key
//   pb::TranslationService svc;                       // 默认 WinHTTP transport
//   auto r = svc.TranslateBatch(cfg, segments, "auto", "zh", "", cancel);
//   // r.ok / r.code 映射可读提示；r.segments 按输入顺序回填

#include "nlohmann/json.hpp"

#include <atomic>
#include <cstddef>
#include <functional>
#include <memory>
#include <string>
#include <utility>
#include <vector>

namespace pb {

// SecretStore 里第三方翻译 Key 的键名（settings 里没有 Key）。
inline constexpr const char* kTranslationApiKeySecretName = "translation.apiKey";

// 机器可读错误码；前端负责本地化。ok=true 时恒为 None。
enum class TranslationErrorCode {
    None = 0,
    NotConfigured,  // baseUrl / model / apiKey 缺失，或 baseUrl 不是 http(s)
    InvalidInput,   // targetLang 为空、段 id 为空等调用方错误
    Network,        // DNS / TCP / TLS / 连接被拒（transport 没拿到响应）
    Timeout,        // 请求超时
    Auth,           // 401 / 403：Key 无效或无权限
    Quota,          // 402 / 429：额度不足 / 触发限流
    Http,           // 其它 HTTP 状态（5xx 等）
    Provider,       // provider 明确拒绝（400/404/422，如模型不存在）
    Parse,          // 响应不是合法 JSON / 结构不对 / 译文缺失
    Cancelled,      // 调用方取消
    TooLarge        // 单段或本批段数超过上限，未发送
};

const char* TranslationErrorCodeName(TranslationErrorCode code);

// 协作式取消令牌：拷贝共享同一个原子标志；默认构造的令牌永远不会被取消。
class TranslationCancelToken {
public:
    TranslationCancelToken();
    void Cancel() const;
    bool IsCancelled() const;

private:
    std::shared_ptr<std::atomic<bool>> m_cancelled;
};

// 注入用 HTTP 请求 / 响应（transport 不接触 Key 之外的其他凭据）。
struct TranslationHttpRequest {
    std::string url;
    std::string body;
    std::vector<std::pair<std::string, std::string>> headers;
    int timeoutMs = 60000;
};

struct TranslationHttpResponse {
    int status = 0;          // HTTP 状态码；0 = 没有拿到响应
    std::string body;
    std::string error;       // transport 失败原因（实现方保证不含 Key / 请求正文）
    bool timedOut = false;   // true = 失败原因是超时（而不是网络错误）
    bool cancelled = false;  // true = 失败原因是取消
};

// 可注入 transport：返回 true 表示拿到了 HTTP 响应（无论状态码）；
// 返回 false 表示连接层失败，原因写在 response.error / timedOut / cancelled。
using TranslationHttpTransport = std::function<bool(
    const TranslationHttpRequest&, const TranslationCancelToken&,
    TranslationHttpResponse&)>;

// 用户配置的 provider（Key 只在本次调用内有效，本类不持久化）。
struct TranslationProviderConfig {
    std::string providerId;  // 仅用于错误提示 / 诊断，如 "deepseek"；可为空
    std::string baseUrl;     // 如 https://api.deepseek.com/v1（或带 /chat/completions）
    std::string model;       // 如 deepseek-chat
    std::string apiKey;      // 调用方从 SecretStore 取出传入；绝不落盘/日志
    int timeoutMs = 60000;   // 单次请求超时；<=0 时按默认 60000
};

// 待翻译段。id 只在本地用于回填结果，不会发给 provider；text 是 UTF-8 原文。
struct TranslationSegment {
    std::string id;
    std::string text;
};

struct TranslatedSegment {
    std::string id;    // 原样回填输入 id
    std::string text;  // 译文；空串 = 未翻译（源文本为空，或本批失败前未轮到）
};

struct TranslationResult {
    bool ok = false;
    TranslationErrorCode code = TranslationErrorCode::None;
    std::string error;        // 可读短原因（已脱敏，绝无 Key）
    std::string errorDetail;  // provider / parse 附加信息（已脱敏、截断）
    int httpStatus = 0;       // 最后一次 HTTP 状态码（0 = 未拿到响应）
    std::vector<TranslatedSegment> segments;  // 与输入同序；失败段 text 为空
    int requestsSent = 0;      // 实际发出的 HTTP 请求次数（含失败尝试）
    int segmentsTranslated = 0;
    long long elapsedMs = 0;
};

class TranslationService {
public:
    struct Options {
        int maxSegmentsPerRequest = 8;       // 每块最多几段
        std::size_t maxCharsPerRequest = 6000;  // 每块最多多少 UTF-8 字节
        std::size_t maxCharsPerSegment = 8000;  // 单段上限；超过 TooLarge
        int maxSegmentsPerCall = 200;        // 单次 TranslateBatch 非空段上限
        bool includeContext = true;          // 是否把用户勾选的 context 发给 provider
    };

    // 默认 WinHTTP transport（生产用）；tests 用注入版本。
    TranslationService();
    explicit TranslationService(TranslationHttpTransport transport,
                                Options options = Options());

    // 同步批量翻译；可并发调用。
    // segments 按输入顺序回填；context 为空 = 不发送上下文。
    TranslationResult TranslateBatch(const TranslationProviderConfig& config,
                                     const std::vector<TranslationSegment>& segments,
                                     const std::string& sourceLang,
                                     const std::string& targetLang,
                                     const std::string& context,
                                     const TranslationCancelToken& cancel =
                                         TranslationCancelToken()) const;

    // 只做切块规划（不发送、不涉及 Key）；返回每块在 segments 里的下标。
    // 只含非空段；空段不会出现在结果里。用于测试与 UI 预估请求数。
    std::vector<std::vector<std::size_t>> PlanBatches(
        const std::vector<TranslationSegment>& segments) const;

    const Options& GetOptions() const { return m_options; }

    // 从 settings（PB-023 的 translation.* schema）读非敏感配置。
    // 建议传入 SettingsService::Normalize(db.GetSettings()).values。
    // apiKey 故意留空：调用方自己从 SecretStore 取。
    static TranslationProviderConfig ConfigFromSettings(const nlohmann::json& settings);

    // 可写日志的诊断摘要：provider/baseUrl/model/timeout，绝不含 Key；
    // baseUrl 里的 userinfo 与 query 会被打码。
    static std::string DescribeConfig(const TranslationProviderConfig& config);

    // 把 provider 返回的错误信息 / transport 错误脱敏 + 截断（供诊断/测试）。
    static std::string RedactSecret(const std::string& text, const std::string& secret);

private:
    TranslationHttpTransport m_transport;
    Options m_options;
};

} // namespace pb
