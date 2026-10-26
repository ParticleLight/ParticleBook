#pragma once
// ── PB-023：SettingsService —— settings 的类型化 schema ─────────────
//
// 背景（docs/ARCHITECTURE_REVIEW.md P1-4、docs/BACKLOG.md PB-023）：
//   settings 此前是「C++ 存任意 JSON、渲染层按字符串数组读写」：没有默认值、
//   没有校验、没有版本；一个字段写错类型，错误值会静默进 UI / 备份 / 导出。
//   本文件把 settings 变成有 schema 的对象：键、类型、默认值、取值范围、
//   枚举、全局 / 书级归属只在这一处声明。App / handler / 渲染层后续按 schema
//   读写，新增 TTS / 翻译 / 词典 / 备份设置只加一行 schema，不再散落字段。
//
// 设计约束：
//   * 纯 std + nlohmann/json，不含 <windows.h>：单测可以直接链接
//     SettingsService.cpp（与 DatabaseService 同一测试缝，见 P1-8）；
//   * 无全局可变状态（只有函数内 static const 的 schema 表，C++11 起线程安全
//     初始化），任意线程可并发调用；
//   * Normalize 永不抛异常；默认保留「本版本不认识的、非敏感的」未知键
//     （前向兼容，见下），但一定剔除疑似密钥的键。
//
// 与 SecretStore 的边界（PB-023 验收：settings / 备份 / 日志不含 Key）：
//   本文件不保存、不读取、不转发任何密钥。第三方 provider 的 API Key / token /
//   密码一律走 SecretStore（DPAPI 加密，见 platform/SecretStore.h）：
//
//       // 非敏感配置进 settings：
//       json s = SettingsService::Normalize(db.GetSettings()).values;
//       s["translation.provider"] = "deepseek";
//       s["translation.baseUrl"]  = "https://api.deepseek.com/v1";
//       s["translation.model"]    = "deepseek-chat";
//       // 密钥进 SecretStore，永远不进 settings：
//       secrets.Set("translation.apiKey", key);
//
//   为了让这条边界可执行、可测试，本文件提供 IsSecretLikeKey() /
//   ContainsSecretLikeKey() / StripSecretLikeKeys()：Normalize 把「名字像密钥」
//   的键（apiKey / token / password / secret …）按 Error 丢弃；备份（PB-011）
//   与诊断日志（PB-016）在导出前可再调 StripSecretLikeKeys() 做最后一道脱敏。
//   translation.baseUrl 里若带 userinfo（https://user:pass@host）或 ?api_key=…
//   会被标记——Key 不应该藏在 URL 里。
//
// 全局 vs 书级：
//   * 全局设置 = m_data["settings"]；书级设置 = m_data["book_settings"][id]。
//   * 只有排版类（fontSize / fontFamily / lineHeight / margin / textAlign）可
//     按书覆盖（与渲染层 BOOK_SETTINGS_KEYS 一致）。NormalizeBook() 只保留
//     书级键、且不填默认值（否则会用默认值把全局值顶掉）；读取某本书的有效
//     设置用 ResolveEffective(global, book)。
//
// 未知键策略（SettingsOptions::unknownKeys）：
//   Preserve（默认） 保留 + Warning —— 新版本渲染层写入、旧 C++ 不认识的键
//                    读一遍写回不会丢；zlibDownloadPath 等 handler 私有键也靠它。
//   Drop             丢弃 + Warning —— 需要严格收紧的读路径。
//   Reject           丢弃 + Error   —— 需要拒绝脏输入的写路径。
//   无论哪种策略，疑似密钥的键都是 Error 并丢弃；未知值内部若藏着疑似密钥
//   的键（{"future":{"apiKey":...}}），整条未知值一起丢弃，不给 Key 留容身之处。
//
// 两个诊断入口的分工（都返回 SettingsIssue 列表）：
//   * Validate(values)：严格体检，不修值。类型 / 范围 / 枚举 / 长度 / URL /
//     密钥 → Error，未知键 → Warning。用于「存储里的东西到底哪里不对」。
//   * Normalize(raw)：迁移 + 修复。类型错误 / 超长 / 坏 URL / 密钥 / Reject →
//     Error；夹紧范围、枚举回退、legacy 字符串迁移、未知键保留 → Warning。
//     用于「读出来能安全用」。
//
// 线程安全：所有方法都是无状态静态函数，可任意线程并发调用。
//
// 典型接线（App / DbHandlers 只需这两行；本轮不改 App/CMake）：
//   auto loaded = pb::SettingsService::Normalize(db->GetSettings());
//   db->UpdateSettings(pb::SettingsService::Normalize(patch, {Reject}).values);

#include "nlohmann/json.hpp"

#include <cstddef>
#include <string>
#include <vector>

namespace pb {

using json = nlohmann::json;

enum class SettingsSeverity {
    Warning,   // 可继续使用；值被修复 / 被保留 / 需要关注
    Error      // 输入里有不可原样保留的东西（坏类型、密钥、被拒绝的键…）
};

struct SettingsIssue {
    std::string key;       // 出问题的键；根对象问题为空串，嵌套问题用 a.b 路径
    std::string code;      // 机器可读：type|range|enum|length|url|unknown|secret|version|root|parse|migrated|internal
    std::string message;   // 英文短说明，渲染层负责本地化
    SettingsSeverity severity = SettingsSeverity::Warning;
};

// 未知键策略，见文件头注释。
enum class UnknownKeyPolicy {
    Preserve,
    Drop,
    Reject
};

struct SettingsOptions {
    UnknownKeyPolicy unknownKeys = UnknownKeyPolicy::Preserve;
    // 兼容老数据 / 渲染层字符串化的值："18" -> 18、"false" -> false。
    // 关掉后这些按类型错误处理（严格模式）。
    bool migrateLegacyTypes = true;
};

struct SettingsResult {
    json values;                       // Normalize 后一定是「每个已知键都存在且合法」
    std::vector<SettingsIssue> issues; // 输入里发现的问题（顺序不保证）
    bool HasErrors() const;
};

// 一条 schema。表由 SettingsService::Schema() 返回，只读。
struct SettingSpec {
    enum class Type { Bool, Int, Double, String, Enum };

    std::string key;
    Type type = Type::String;
    json defaultValue;
    bool bookScoped = false;   // true = 可写进 book_settings 并按书覆盖
    bool isUrl = false;        // 仅 String：要求 http(s)://，并检查 URL 里的凭证
    double minValue = 0.0;     // Int / Double 的闭区间
    double maxValue = 0.0;
    std::size_t maxLength = 0; // String 最大字节长度（0 = 不限）
    std::vector<std::string> allowed; // Enum 允许值
};

class SettingsService {
public:
    // 当前 schema 版本。写入 settings 的 "schemaVersion"；读旧数据自动升级到它，
    // 读到更新版本时保留原值并给 Warning（不做降级破坏）。
    static constexpr int kSchemaVersion = 1;
    static constexpr const char* kVersionKey = "schemaVersion";

    // ── schema ──────────────────────────────────────────────────────
    static const std::vector<SettingSpec>& Schema();
    static const SettingSpec* Find(const std::string& key);       // 未知 / schemaVersion → nullptr
    static bool IsBookKey(const std::string& key);
    static std::vector<std::string> BookKeys();
    static json Defaults();       // 全部键的默认值 + schemaVersion
    static json BookDefaults();   // 只有书级键的默认值（不含 schemaVersion）
    static json SchemaJson();     // schema 自描述（渲染层将来据此生成表单）

    // ── 读：规范化 / 合并 ───────────────────────────────────────────
    // 任意原始 JSON -> 合法 values + issues。永不抛异常。
    static SettingsResult Normalize(const json& raw, const SettingsOptions& options = SettingsOptions());
    // 书级行：只保留出现的书级键（不填默认值），未知键按策略处理，
    // 历史遗留的全局键（如旧版误写的 autoSaveProgress）丢弃并给 Warning。
    static SettingsResult NormalizeBook(const json& raw, const SettingsOptions& options = SettingsOptions());
    // 只补缺失的已知键默认值，不校验、不删未知键（纯迁移用）。
    static json MergeDefaults(const json& values);
    // 某本书最终生效的设置 = 规范化后的全局值，再用书级行覆盖书级键。
    static json ResolveEffective(const json& globalValues, const json& bookValues);

    // ── 诊断：严格体检（不修值）─────────────────────────────────────
    static std::vector<SettingsIssue> Validate(const json& values);
    static std::vector<SettingsIssue> ValidateBook(const json& values);
    static bool IsValid(const json& values); // 无 Error 即 true

    // ── 序列化 ──────────────────────────────────────────────────────
    // 先 Normalize 再 dump：稳定（键有序）、带版本、已去密钥。永不抛异常。
    static std::string Serialize(const json& values);
    static SettingsResult Deserialize(const std::string& text,
                                      const SettingsOptions& options = SettingsOptions());

    // ── 类型化读取（值缺失 / 类型不对时回落到 schema 默认值）─────────
    static bool        GetBool(const json& values, const std::string& key);
    static long long   GetInt(const json& values, const std::string& key);
    static double      GetDouble(const json& values, const std::string& key);
    static std::string GetString(const json& values, const std::string& key);

    // ── 密钥边界工具（见文件头）─────────────────────────────────────
    // 键名（按 . _ - 与 camelCase 分词）是否像密钥：apiKey / API_KEY /
    // translation.apiKey / openaiToken / password / secret / auth …
    // 注意 "author" / "defaultSortBy" 不是密钥。
    static bool IsSecretLikeKey(const std::string& key);
    // 递归检查任意嵌套 JSON 里是否存在疑似密钥的键。
    static bool ContainsSecretLikeKey(const json& values);
    // 递归剔除疑似密钥的键，返回副本（备份 / 日志导出前的兜底脱敏）。
    static json StripSecretLikeKeys(const json& values);
};

} // namespace pb
