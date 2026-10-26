// ── PB-023：SettingsService 实现 ────────────────────────────────────
// 设计 / 边界 / 用法见 SettingsService.h 头注释。本文件刻意只依赖 std 与
// nlohmann/json：不 include <windows.h>、不引用 SecretStore，单测可直接链接
// （与 DatabaseService 同一测试缝）。所有公开方法无状态、不抛异常。

#include "platform/SettingsService.h"

#include <algorithm>
#include <cerrno>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <string>
#include <utility>
#include <vector>

namespace pb {

namespace {

// ── 小工具 ─────────────────────────────────────────────────────────

std::string Lower(std::string text)
{
    for (char& c : text) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return text;
}

bool StartsWith(const std::string& text, const char* prefix)
{
    const std::string p(prefix);
    return text.size() >= p.size() && text.compare(0, p.size(), p) == 0;
}

bool EndsWith(const std::string& text, const char* suffix)
{
    const std::string s(suffix);
    return text.size() >= s.size() && text.compare(text.size() - s.size(), s.size(), s) == 0;
}

std::string NumberText(double value)
{
    char buffer[48] = {};
    std::snprintf(buffer, sizeof buffer, "%g", value);
    return buffer;
}

void AddIssue(std::vector<SettingsIssue>& issues, std::string key, std::string code,
              std::string message, SettingsSeverity severity)
{
    SettingsIssue issue;
    issue.key = std::move(key);
    issue.code = std::move(code);
    issue.message = std::move(message);
    issue.severity = severity;
    issues.push_back(std::move(issue));
}

bool ParseLongLong(const std::string& text, long long& out)
{
    if (text.empty()) return false;
    errno = 0;
    char* end = nullptr;
    const long long value = std::strtoll(text.c_str(), &end, 10);
    if (errno != 0 || end == text.c_str() || end == nullptr || *end != '\0') return false;
    out = value;
    return true;
}

bool ParseDouble(const std::string& text, double& out)
{
    if (text.empty()) return false;
    errno = 0;
    char* end = nullptr;
    const double value = std::strtod(text.c_str(), &end);
    if (errno != 0 || end == text.c_str() || end == nullptr || *end != '\0') return false;
    if (!std::isfinite(value)) return false;
    out = value;
    return true;
}

// JSON 数字 -> long long（接受 18.0 这种整值浮点；范围限制在 2^53 内保证精确）。
bool IntegralJson(const json& value, long long& out)
{
    if (value.is_number_unsigned()) {
        const unsigned long long u = value.get<unsigned long long>();
        if (u > static_cast<unsigned long long>(std::numeric_limits<long long>::max())) return false;
        out = static_cast<long long>(u);
        return true;
    }
    if (value.is_number_integer()) {
        out = value.get<long long>();
        return true;
    }
    if (value.is_number_float()) {
        const double d = value.get<double>();
        if (std::isfinite(d) && d == std::trunc(d) && std::fabs(d) <= 9007199254740992.0) {
            out = static_cast<long long>(d);
            return true;
        }
    }
    return false;
}

bool NumberJson(const json& value, double& out)
{
    if (!value.is_number()) return false;
    const double d = value.get<double>();
    if (!std::isfinite(d)) return false;
    out = d;
    return true;
}

// ── 密钥键识别 ─────────────────────────────────────────────────────
// 分词：以 . _ - / : 空格 @ 分段，camelCase 处再切；每段转小写 alnum。
std::vector<std::string> SplitKey(const std::string& key)
{
    std::vector<std::string> parts;
    std::string current;
    auto flush = [&parts, &current]() {
        if (!current.empty()) {
            parts.push_back(current);
            current.clear();
        }
    };
    for (std::size_t i = 0; i < key.size(); ++i) {
        const unsigned char c = static_cast<unsigned char>(key[i]);
        if (c == '.' || c == '_' || c == '-' || c == '/' || c == ':' || c == ' ' || c == '@') {
            flush();
            continue;
        }
        if (std::isupper(c) && !current.empty()) {
            const unsigned char prev = static_cast<unsigned char>(key[i - 1]);
            if (std::islower(prev) || std::isdigit(prev)) flush();
        }
        if (std::isalnum(c)) current.push_back(static_cast<char>(std::tolower(c)));
    }
    flush();
    return parts;
}

bool SegmentLooksSecret(const std::string& segment)
{
    // 精确命中：单独的 auth / token / secret / password / credential …
    // 注意这里不放单独的 "key"：shortcutKey / keyLength 这类正常设置名不该被
    // 误判；apiKey 一类靠下方整键拼接（translationapikey）或后缀命中。
    static const char* const kExact[] = {
        "auth", "bearer", "token", "secret", "password", "passwd", "passphrase", "credential"
    };
    for (const char* word : kExact) {
        if (segment == word) return true;
    }
    // 后缀命中：openaiToken / mySecret / dbPassword / signingKey …
    static const char* const kSuffix[] = {
        "apikey", "token", "secret", "password", "passwd", "credential", "credentials",
        "privatekey", "accesskey", "sessionkey", "signingkey", "encryptionkey"
    };
    for (const char* word : kSuffix) {
        if (EndsWith(segment, word)) return true;
    }
    // 包含命中：authorization / clientsecret / credentialstore …
    static const char* const kContains[] = {
        "apikey", "privatekey", "accesskey", "clientsecret", "authorization"
    };
    for (const char* word : kContains) {
        if (segment.find(word) != std::string::npos) return true;
    }
    return false;
}

bool IsSecretLikeKeyImpl(const std::string& key)
{
    if (key.empty()) return false;
    const std::vector<std::string> segments = SplitKey(key);
    if (segments.empty()) return false;

    // 整键拼接后再匹配：API_KEY / translation.apiKey 都被拆成
    // ["api","key"] / ["translation","api","key"]，拼回 "apikey" 才能命中。
    std::string joined;
    for (const std::string& segment : segments) joined += segment;
    if (joined == "key") return true;
    static const char* const kJoined[] = {
        "apikey", "privatekey", "accesskey", "clientsecret", "authorization"
    };
    for (const char* word : kJoined) {
        if (joined.find(word) != std::string::npos) return true;
    }

    for (const std::string& segment : segments) {
        if (SegmentLooksSecret(segment)) return true;
    }
    return false;
}

void StripSecretsInPlace(json& value, const std::string& path, std::vector<SettingsIssue>& issues)
{
    if (value.is_object()) {
        for (auto it = value.begin(); it != value.end();) {
            const std::string child = path.empty() ? it.key() : path + "." + it.key();
            if (IsSecretLikeKeyImpl(it.key())) {
                AddIssue(issues, child, "secret",
                         "key name looks like a secret; secrets belong in SecretStore, not settings",
                         SettingsSeverity::Error);
                it = value.erase(it);
            } else {
                StripSecretsInPlace(it.value(), child, issues);
                ++it;
            }
        }
    } else if (value.is_array()) {
        for (std::size_t i = 0; i < value.size(); ++i) {
            StripSecretsInPlace(value[i], path + "[" + std::to_string(i) + "]", issues);
        }
    }
}

bool ContainsSecretKeyIn(const json& value)
{
    if (value.is_object()) {
        for (auto it = value.begin(); it != value.end(); ++it) {
            if (IsSecretLikeKeyImpl(it.key())) return true;
            if (ContainsSecretKeyIn(it.value())) return true;
        }
    } else if (value.is_array()) {
        for (const json& element : value) {
            if (ContainsSecretKeyIn(element)) return true;
        }
    }
    return false;
}

// ── baseUrl 语义检查（Key 不能藏在 URL 里）─────────────────────────
bool IsHttpUrl(const std::string& url)
{
    const std::string lower = Lower(url);
    return StartsWith(lower, "http://") || StartsWith(lower, "https://");
}

bool UrlLooksSensitive(const std::string& url)
{
    const std::string lower = Lower(url);
    const std::size_t scheme = lower.find("://");
    if (scheme != std::string::npos) {
        const std::size_t authorityBegin = scheme + 3;
        const std::size_t authorityEnd = lower.find_first_of("/?#", authorityBegin);
        const std::string authority =
            lower.substr(authorityBegin, authorityEnd == std::string::npos
                                             ? std::string::npos
                                             : authorityEnd - authorityBegin);
        if (authority.find('@') != std::string::npos) return true;
    }
    static const char* const kHints[] = {
        "api_key=", "apikey=", "access_token=", "token=", "secret=", "password=",
        "signature=", "key="
    };
    for (const char* hint : kHints) {
        if (lower.find(hint) != std::string::npos) return true;
    }
    return false;
}

// ── schema 构造辅助 ───────────────────────────────────────────────

SettingSpec MakeBool(const std::string& key, bool defaultValue)
{
    SettingSpec spec;
    spec.key = key;
    spec.type = SettingSpec::Type::Bool;
    spec.defaultValue = defaultValue;
    return spec;
}

SettingSpec MakeInt(const std::string& key, long long defaultValue, long long minValue,
                    long long maxValue, bool bookScoped = false)
{
    SettingSpec spec;
    spec.key = key;
    spec.type = SettingSpec::Type::Int;
    spec.defaultValue = defaultValue;
    spec.bookScoped = bookScoped;
    spec.minValue = static_cast<double>(minValue);
    spec.maxValue = static_cast<double>(maxValue);
    return spec;
}

SettingSpec MakeDouble(const std::string& key, double defaultValue, double minValue,
                       double maxValue, bool bookScoped = false)
{
    SettingSpec spec;
    spec.key = key;
    spec.type = SettingSpec::Type::Double;
    spec.defaultValue = defaultValue;
    spec.bookScoped = bookScoped;
    spec.minValue = minValue;
    spec.maxValue = maxValue;
    return spec;
}

SettingSpec MakeString(const std::string& key, const char* defaultValue, std::size_t maxLength,
                       bool bookScoped = false, bool isUrl = false)
{
    SettingSpec spec;
    spec.key = key;
    spec.type = SettingSpec::Type::String;
    spec.defaultValue = std::string(defaultValue == nullptr ? "" : defaultValue);
    spec.bookScoped = bookScoped;
    spec.isUrl = isUrl;
    spec.maxLength = maxLength;
    return spec;
}

SettingSpec MakeEnum(const std::string& key, const char* defaultValue,
                     std::vector<std::string> allowed, bool bookScoped = false)
{
    SettingSpec spec;
    spec.key = key;
    spec.type = SettingSpec::Type::Enum;
    spec.defaultValue = std::string(defaultValue == nullptr ? "" : defaultValue);
    spec.bookScoped = bookScoped;
    spec.allowed = std::move(allowed);
    return spec;
}

// ── 单值校验 / 迁移 ───────────────────────────────────────────────

// 迁移 + 修复一个已知键。true = out 可用；false = 调用方回退默认值。
bool CoerceValue(const SettingSpec& spec, const json& input, const SettingsOptions& options,
                 json& out, std::vector<SettingsIssue>& issues)
{
    switch (spec.type) {
    case SettingSpec::Type::Bool: {
        if (input.is_boolean()) {
            out = input.get<bool>();
            return true;
        }
        if (options.migrateLegacyTypes) {
            if (input.is_string()) {
                const std::string lower = Lower(input.get<std::string>());
                if (lower == "true" || lower == "1") {
                    out = true;
                    AddIssue(issues, spec.key, "migrated", "legacy string migrated to bool",
                             SettingsSeverity::Warning);
                    return true;
                }
                if (lower == "false" || lower == "0") {
                    out = false;
                    AddIssue(issues, spec.key, "migrated", "legacy string migrated to bool",
                             SettingsSeverity::Warning);
                    return true;
                }
            } else if (input.is_number_integer()) {
                const long long n = input.get<long long>();
                if (n == 0 || n == 1) {
                    out = (n == 1);
                    AddIssue(issues, spec.key, "migrated", "legacy 0/1 migrated to bool",
                             SettingsSeverity::Warning);
                    return true;
                }
            }
        }
        AddIssue(issues, spec.key, "type", "expected boolean", SettingsSeverity::Error);
        return false;
    }

    case SettingSpec::Type::Int: {
        long long value = 0;
        bool migrated = false;
        if (!IntegralJson(input, value)) {
            if (options.migrateLegacyTypes && input.is_string()
                && ParseLongLong(input.get<std::string>(), value)) {
                migrated = true;
            } else {
                AddIssue(issues, spec.key, "type", "expected integer", SettingsSeverity::Error);
                return false;
            }
        }
        if (migrated) {
            AddIssue(issues, spec.key, "migrated", "legacy string migrated to integer",
                     SettingsSeverity::Warning);
        }
        const long long lower = static_cast<long long>(spec.minValue);
        const long long upper = static_cast<long long>(spec.maxValue);
        if (value < lower) {
            AddIssue(issues, spec.key, "range",
                     "below minimum " + std::to_string(lower) + ", clamped", SettingsSeverity::Warning);
            value = lower;
        } else if (value > upper) {
            AddIssue(issues, spec.key, "range",
                     "above maximum " + std::to_string(upper) + ", clamped", SettingsSeverity::Warning);
            value = upper;
        }
        out = value;
        return true;
    }

    case SettingSpec::Type::Double: {
        double value = 0.0;
        bool migrated = false;
        if (!NumberJson(input, value)) {
            if (options.migrateLegacyTypes && input.is_string()
                && ParseDouble(input.get<std::string>(), value)) {
                migrated = true;
            } else {
                AddIssue(issues, spec.key, "type", "expected number", SettingsSeverity::Error);
                return false;
            }
        }
        if (migrated) {
            AddIssue(issues, spec.key, "migrated", "legacy string migrated to number",
                     SettingsSeverity::Warning);
        }
        if (value < spec.minValue) {
            AddIssue(issues, spec.key, "range",
                     "below minimum " + NumberText(spec.minValue) + ", clamped",
                     SettingsSeverity::Warning);
            value = spec.minValue;
        } else if (value > spec.maxValue) {
            AddIssue(issues, spec.key, "range",
                     "above maximum " + NumberText(spec.maxValue) + ", clamped",
                     SettingsSeverity::Warning);
            value = spec.maxValue;
        }
        out = value;
        return true;
    }

    case SettingSpec::Type::String: {
        if (!input.is_string()) {
            AddIssue(issues, spec.key, "type", "expected string", SettingsSeverity::Error);
            return false;
        }
        const std::string text = input.get<std::string>();
        if (spec.maxLength > 0 && text.size() > spec.maxLength) {
            AddIssue(issues, spec.key, "length",
                     "longer than " + std::to_string(spec.maxLength) + " bytes",
                     SettingsSeverity::Error);
            return false;
        }
        if (spec.isUrl) {
            if (!text.empty() && !IsHttpUrl(text)) {
                AddIssue(issues, spec.key, "url", "must be an http(s) URL", SettingsSeverity::Error);
                return false;
            }
            if (UrlLooksSensitive(text)) {
                AddIssue(issues, spec.key, "url",
                         "URL appears to contain credentials; keep secrets in SecretStore",
                         SettingsSeverity::Warning);
            }
        }
        out = text;
        return true;
    }

    case SettingSpec::Type::Enum: {
        if (!input.is_string()) {
            AddIssue(issues, spec.key, "type", "expected string", SettingsSeverity::Error);
            return false;
        }
        const std::string text = input.get<std::string>();
        for (const std::string& allowed : spec.allowed) {
            if (text == allowed) {
                out = text;
                return true;
            }
        }
        AddIssue(issues, spec.key, "enum", "unknown value, using default",
                 SettingsSeverity::Warning);
        return false;
    }
    }
    return false;
}

long long NormalizeVersion(const json& input, const SettingsOptions& options,
                           std::vector<SettingsIssue>& issues)
{
    long long version = SettingsService::kSchemaVersion;
    bool ok = IntegralJson(input, version);
    if (!ok && options.migrateLegacyTypes && input.is_string()) {
        ok = ParseLongLong(input.get<std::string>(), version);
    }
    if (!ok) {
        AddIssue(issues, SettingsService::kVersionKey, "version",
                 "schemaVersion must be an integer", SettingsSeverity::Error);
        return SettingsService::kSchemaVersion;
    }
    if (version < 1) {
        AddIssue(issues, SettingsService::kVersionKey, "version",
                 "invalid schemaVersion, reset to current", SettingsSeverity::Error);
        return SettingsService::kSchemaVersion;
    }
    if (version > SettingsService::kSchemaVersion) {
        AddIssue(issues, SettingsService::kVersionKey, "version",
                 "settings were written by a newer version; unknown keys are preserved",
                 SettingsSeverity::Warning);
        return version; // 不做降级：保留新版本号，避免旧版本把新数据写坏
    }
    return SettingsService::kSchemaVersion;
}

// ── 严格体检（Validate 用，不修值）────────────────────────────────

void ValidateSpec(const SettingSpec& spec, const json& value, std::vector<SettingsIssue>& issues)
{
    switch (spec.type) {
    case SettingSpec::Type::Bool:
        if (!value.is_boolean()) {
            AddIssue(issues, spec.key, "type", "expected boolean", SettingsSeverity::Error);
        }
        break;

    case SettingSpec::Type::Int: {
        long long n = 0;
        if (!IntegralJson(value, n)) {
            AddIssue(issues, spec.key, "type", "expected integer", SettingsSeverity::Error);
            break;
        }
        const long long lower = static_cast<long long>(spec.minValue);
        const long long upper = static_cast<long long>(spec.maxValue);
        if (n < lower || n > upper) {
            AddIssue(issues, spec.key, "range",
                     "outside [" + std::to_string(lower) + ", " + std::to_string(upper) + "]",
                     SettingsSeverity::Error);
        }
        break;
    }

    case SettingSpec::Type::Double: {
        double d = 0.0;
        if (!NumberJson(value, d)) {
            AddIssue(issues, spec.key, "type", "expected number", SettingsSeverity::Error);
            break;
        }
        if (d < spec.minValue || d > spec.maxValue) {
            AddIssue(issues, spec.key, "range",
                     "outside [" + NumberText(spec.minValue) + ", " + NumberText(spec.maxValue) + "]",
                     SettingsSeverity::Error);
        }
        break;
    }

    case SettingSpec::Type::String: {
        if (!value.is_string()) {
            AddIssue(issues, spec.key, "type", "expected string", SettingsSeverity::Error);
            break;
        }
        const std::string text = value.get<std::string>();
        if (spec.maxLength > 0 && text.size() > spec.maxLength) {
            AddIssue(issues, spec.key, "length",
                     "longer than " + std::to_string(spec.maxLength) + " bytes",
                     SettingsSeverity::Error);
        }
        if (spec.isUrl && !text.empty()) {
            if (!IsHttpUrl(text)) {
                AddIssue(issues, spec.key, "url", "must be an http(s) URL", SettingsSeverity::Error);
            } else if (UrlLooksSensitive(text)) {
                AddIssue(issues, spec.key, "url",
                         "URL appears to contain credentials; keep secrets in SecretStore",
                         SettingsSeverity::Warning);
            }
        }
        break;
    }

    case SettingSpec::Type::Enum: {
        if (!value.is_string()) {
            AddIssue(issues, spec.key, "type", "expected string", SettingsSeverity::Error);
            break;
        }
        const std::string text = value.get<std::string>();
        bool found = false;
        for (const std::string& allowed : spec.allowed) {
            if (text == allowed) {
                found = true;
                break;
            }
        }
        if (!found) {
            AddIssue(issues, spec.key, "enum", "value is not allowed", SettingsSeverity::Error);
        }
        break;
    }
    }
}

void ValidateVersion(const json& value, std::vector<SettingsIssue>& issues)
{
    long long version = 0;
    if (!IntegralJson(value, version)) {
        AddIssue(issues, SettingsService::kVersionKey, "version",
                 "schemaVersion must be an integer", SettingsSeverity::Error);
        return;
    }
    if (version < 1) {
        AddIssue(issues, SettingsService::kVersionKey, "version",
                 "invalid schemaVersion", SettingsSeverity::Error);
    } else if (version > SettingsService::kSchemaVersion) {
        AddIssue(issues, SettingsService::kVersionKey, "version",
                 "settings were written by a newer version", SettingsSeverity::Warning);
    }
}

// 未知键统一处理：密钥优先剔除；其余按策略保留 / 丢弃 / 拒绝。
void HandleUnknownKey(const std::string& key, json value, const SettingsOptions& options,
                      json& out, std::vector<SettingsIssue>& issues)
{
    if (IsSecretLikeKeyImpl(key)) {
        AddIssue(issues, key, "secret",
                 "key name looks like a secret; secrets belong in SecretStore, not settings",
                 SettingsSeverity::Error);
        return;
    }
    std::vector<SettingsIssue> nested;
    StripSecretsInPlace(value, key, nested);
    if (!nested.empty()) {
        // 值内部藏着疑似密钥：整条丢弃，别把剥空的壳或残留一起写回。
        for (SettingsIssue& issue : nested) issues.push_back(std::move(issue));
        return;
    }

    switch (options.unknownKeys) {
    case UnknownKeyPolicy::Preserve:
        out[key] = std::move(value);
        AddIssue(issues, key, "unknown", "unknown setting preserved", SettingsSeverity::Warning);
        break;
    case UnknownKeyPolicy::Drop:
        AddIssue(issues, key, "unknown", "unknown setting dropped", SettingsSeverity::Warning);
        break;
    case UnknownKeyPolicy::Reject:
        AddIssue(issues, key, "unknown", "unknown setting rejected", SettingsSeverity::Error);
        break;
    }
}

SettingsResult NormalizeImpl(const json& raw, const SettingsOptions& options, bool bookMode)
{
    SettingsResult result;
    result.values = json::object();

    if (!raw.is_null() && !raw.is_object()) {
        AddIssue(result.issues, "", "root", "settings must be a JSON object", SettingsSeverity::Error);
    }
    const json input = raw.is_object() ? raw : json::object();

    // 已知键
    for (const SettingSpec& spec : SettingsService::Schema()) {
        if (bookMode && !spec.bookScoped) continue;
        if (!input.contains(spec.key)) {
            if (!bookMode) result.values[spec.key] = spec.defaultValue;
            continue;
        }
        json value;
        if (CoerceValue(spec, input.at(spec.key), options, value, result.issues)) {
            result.values[spec.key] = std::move(value);
        } else if (!bookMode) {
            result.values[spec.key] = spec.defaultValue;
        }
    }

    // 版本 + 未知键
    for (auto it = input.begin(); it != input.end(); ++it) {
        const std::string key = it.key();
        if (key == SettingsService::kVersionKey) {
            if (bookMode) {
                AddIssue(result.issues, key, "version",
                         "schemaVersion is global-only and ignored in book settings",
                         SettingsSeverity::Warning);
            } else {
                result.values[key] = NormalizeVersion(it.value(), options, result.issues);
            }
            continue;
        }
        const SettingSpec* spec = SettingsService::Find(key);
        if (spec != nullptr) {
            if (bookMode && !spec->bookScoped) {
                AddIssue(result.issues, key, "scope",
                         "global setting ignored in book settings", SettingsSeverity::Warning);
            }
            continue;
        }
        HandleUnknownKey(key, it.value(), options, result.values, result.issues);
    }

    if (!bookMode && !input.contains(SettingsService::kVersionKey)) {
        result.values[SettingsService::kVersionKey] = SettingsService::kSchemaVersion;
    }
    return result;
}

std::string TypeName(SettingSpec::Type type)
{
    switch (type) {
    case SettingSpec::Type::Bool:   return "bool";
    case SettingSpec::Type::Int:    return "int";
    case SettingSpec::Type::Double: return "double";
    case SettingSpec::Type::String: return "string";
    case SettingSpec::Type::Enum:   return "enum";
    }
    return "string";
}

} // namespace

// ── schema ────────────────────────────────────────────────────────

const std::vector<SettingSpec>& SettingsService::Schema()
{
    static const std::vector<SettingSpec> schema = [] {
        std::vector<SettingSpec> specs;

        // 现有设置（与渲染层 settingsStore 的 SETTINGS_KEYS / 默认值一致）
        specs.push_back(MakeEnum("theme", "dark", {"light", "dark", "sepia"}));
        specs.push_back(MakeEnum("accentColor", "blue", {"blue", "purple", "green", "orange"}));
        specs.push_back(MakeInt("fontSize", 18, 12, 32, /*bookScoped=*/true));
        specs.push_back(MakeString("fontFamily", "Georgia, Noto Serif SC, serif", 200,
                                   /*bookScoped=*/true));
        specs.push_back(MakeDouble("lineHeight", 1.8, 1.2, 3.0, /*bookScoped=*/true));
        specs.push_back(MakeInt("margin", 40, 0, 100, /*bookScoped=*/true));
        specs.push_back(MakeEnum("textAlign", "justify", {"left", "justify"},
                                 /*bookScoped=*/true));
        specs.push_back(MakeBool("autoSaveProgress", true));
        specs.push_back(MakeBool("showReadingTime", true));
        specs.push_back(MakeEnum("defaultViewMode", "grid", {"grid", "list"}));
        specs.push_back(MakeEnum("defaultSortBy", "last_opened",
                                 {"title", "author", "added_at", "last_opened"}));
        // 语言是全局设置（渲染层特意不放进 SETTINGS_KEYS，避免被书级值覆盖）
        specs.push_back(MakeEnum("language", "zh", {"zh", "en"}));

        // 预留：PB-001 TTS（音色 / 语速 / 音量 / 睡眠定时；voice 是系统音色 id，
        // 非敏感，Key 类配置不属于这里）
        specs.push_back(MakeBool("tts.enabled", false));
        specs.push_back(MakeString("tts.voice", "", 128));
        specs.push_back(MakeInt("tts.rate", 0, -10, 10));
        specs.push_back(MakeInt("tts.volume", 100, 0, 100));
        specs.push_back(MakeBool("tts.highlightSentence", true));
        specs.push_back(MakeInt("tts.sleepTimerMinutes", 0, 0, 180));

        // 预留：PB-002 AI 翻译。只存 provider id / base URL / model / 目标语言；
        // API Key 属于 SecretStore（"translation.apiKey" 会被当密钥拒绝）。
        specs.push_back(MakeBool("translation.enabled", false));
        specs.push_back(MakeString("translation.provider", "openai", 64));
        specs.push_back(MakeString("translation.baseUrl", "", 2048,
                                   /*bookScoped=*/false, /*isUrl=*/true));
        specs.push_back(MakeString("translation.model", "", 200));
        specs.push_back(MakeString("translation.targetLanguage", "zh", 32));
        specs.push_back(MakeInt("translation.timeoutSeconds", 60, 5, 300));
        specs.push_back(MakeInt("reading.dailyGoalMinutes", 30, 0, 1440));
        specs.push_back(MakeInt("reading.weeklyGoalMinutes", 180, 0, 10080));

        // 预留：PB-010 本地词典
        specs.push_back(MakeBool("dictionary.enabled", true));
        specs.push_back(MakeString("dictionary.provider", "local", 64));
        specs.push_back(MakeBool("dictionary.autoLookup", false));
        specs.push_back(MakeInt("dictionary.maxResults", 5, 1, 50));

        // 预留：PB-011 备份 / 恢复
        specs.push_back(MakeBool("backup.enabled", true));
        specs.push_back(MakeString("backup.path", "", 1024));
        specs.push_back(MakeInt("backup.intervalDays", 7, 1, 365));
        specs.push_back(MakeInt("backup.keepCount", 5, 1, 50));
        specs.push_back(MakeString("backup.lastBackupAt", "", 64));

        return specs;
    }();
    return schema;
}

const SettingSpec* SettingsService::Find(const std::string& key)
{
    for (const SettingSpec& spec : Schema()) {
        if (spec.key == key) return &spec;
    }
    return nullptr;
}

bool SettingsService::IsBookKey(const std::string& key)
{
    const SettingSpec* spec = Find(key);
    return spec != nullptr && spec->bookScoped;
}

std::vector<std::string> SettingsService::BookKeys()
{
    std::vector<std::string> keys;
    for (const SettingSpec& spec : Schema()) {
        if (spec.bookScoped) keys.push_back(spec.key);
    }
    return keys;
}

json SettingsService::Defaults()
{
    json values = json::object();
    for (const SettingSpec& spec : Schema()) values[spec.key] = spec.defaultValue;
    values[kVersionKey] = kSchemaVersion;
    return values;
}

json SettingsService::BookDefaults()
{
    json values = json::object();
    for (const SettingSpec& spec : Schema()) {
        if (spec.bookScoped) values[spec.key] = spec.defaultValue;
    }
    return values;
}

json SettingsService::SchemaJson()
{
    json specs = json::array();
    for (const SettingSpec& spec : Schema()) {
        json item;
        item["key"] = spec.key;
        item["type"] = TypeName(spec.type);
        item["default"] = spec.defaultValue;
        item["bookScoped"] = spec.bookScoped;
        if (spec.type == SettingSpec::Type::Int || spec.type == SettingSpec::Type::Double) {
            item["min"] = spec.minValue;
            item["max"] = spec.maxValue;
        }
        if (spec.type == SettingSpec::Type::String) {
            item["maxLength"] = spec.maxLength;
            if (spec.isUrl) item["format"] = "url";
        }
        if (spec.type == SettingSpec::Type::Enum) item["allowed"] = spec.allowed;
        specs.push_back(std::move(item));
    }
    return specs;
}

// ── 读：规范化 / 合并 ─────────────────────────────────────────────

SettingsResult SettingsService::Normalize(const json& raw, const SettingsOptions& options)
{
    try {
        return NormalizeImpl(raw, options, /*bookMode=*/false);
    } catch (const std::exception& e) {
        SettingsResult result;
        result.values = Defaults();
        AddIssue(result.issues, "", "internal", std::string("settings normalization failed: ") + e.what(),
                 SettingsSeverity::Error);
        return result;
    } catch (...) {
        SettingsResult result;
        result.values = Defaults();
        AddIssue(result.issues, "", "internal", "settings normalization failed", SettingsSeverity::Error);
        return result;
    }
}

SettingsResult SettingsService::NormalizeBook(const json& raw, const SettingsOptions& options)
{
    try {
        return NormalizeImpl(raw, options, /*bookMode=*/true);
    } catch (const std::exception& e) {
        SettingsResult result;
        result.values = json::object();
        AddIssue(result.issues, "", "internal", std::string("book settings normalization failed: ") + e.what(),
                 SettingsSeverity::Error);
        return result;
    } catch (...) {
        SettingsResult result;
        result.values = json::object();
        AddIssue(result.issues, "", "internal", "book settings normalization failed",
                 SettingsSeverity::Error);
        return result;
    }
}

json SettingsService::MergeDefaults(const json& values)
{
    json merged = values.is_object() ? values : json::object();
    for (const SettingSpec& spec : Schema()) {
        if (!merged.contains(spec.key)) merged[spec.key] = spec.defaultValue;
    }
    if (!merged.contains(kVersionKey)) merged[kVersionKey] = kSchemaVersion;
    return merged;
}

json SettingsService::ResolveEffective(const json& globalValues, const json& bookValues)
{
    json effective = Normalize(globalValues).values;
    if (!bookValues.is_object()) return effective;
    const SettingsResult book = NormalizeBook(bookValues);
    for (const SettingSpec& spec : Schema()) {
        if (!spec.bookScoped) continue;
        if (book.values.contains(spec.key)) effective[spec.key] = book.values.at(spec.key);
    }
    return effective;
}

// ── 严格体检 ──────────────────────────────────────────────────────

std::vector<SettingsIssue> SettingsService::Validate(const json& values)
{
    std::vector<SettingsIssue> issues;
    try {
        if (!values.is_object()) {
            AddIssue(issues, "", "root", "settings must be a JSON object", SettingsSeverity::Error);
            return issues;
        }
        for (auto it = values.begin(); it != values.end(); ++it) {
            const std::string key = it.key();
            if (key == kVersionKey) {
                ValidateVersion(it.value(), issues);
                continue;
            }
            if (const SettingSpec* spec = Find(key)) {
                ValidateSpec(*spec, it.value(), issues);
                continue;
            }
            if (IsSecretLikeKeyImpl(key)) {
                AddIssue(issues, key, "secret",
                         "key name looks like a secret; secrets belong in SecretStore, not settings",
                         SettingsSeverity::Error);
                continue;
            }
            AddIssue(issues, key, "unknown", "unknown setting", SettingsSeverity::Warning);
        }
    } catch (...) {
        AddIssue(issues, "", "internal", "settings validation failed", SettingsSeverity::Error);
    }
    return issues;
}

std::vector<SettingsIssue> SettingsService::ValidateBook(const json& values)
{
    std::vector<SettingsIssue> issues;
    try {
        if (!values.is_object()) {
            AddIssue(issues, "", "root", "book settings must be a JSON object",
                     SettingsSeverity::Error);
            return issues;
        }
        for (auto it = values.begin(); it != values.end(); ++it) {
            const std::string key = it.key();
            if (key == kVersionKey) {
                AddIssue(issues, key, "version",
                         "schemaVersion is global-only and ignored in book settings",
                         SettingsSeverity::Warning);
                continue;
            }
            if (const SettingSpec* spec = Find(key)) {
                if (!spec->bookScoped) {
                    AddIssue(issues, key, "scope", "global setting ignored in book settings",
                             SettingsSeverity::Warning);
                } else {
                    ValidateSpec(*spec, it.value(), issues);
                }
                continue;
            }
            if (IsSecretLikeKeyImpl(key)) {
                AddIssue(issues, key, "secret",
                         "key name looks like a secret; secrets belong in SecretStore, not settings",
                         SettingsSeverity::Error);
                continue;
            }
            AddIssue(issues, key, "unknown", "unknown setting", SettingsSeverity::Warning);
        }
    } catch (...) {
        AddIssue(issues, "", "internal", "book settings validation failed", SettingsSeverity::Error);
    }
    return issues;
}

bool SettingsService::IsValid(const json& values)
{
    for (const SettingsIssue& issue : Validate(values)) {
        if (issue.severity == SettingsSeverity::Error) return false;
    }
    return true;
}

// ── 序列化 ────────────────────────────────────────────────────────

std::string SettingsService::Serialize(const json& values)
{
    try {
        return Normalize(values).values.dump();
    } catch (...) {
        return "{}";
    }
}

SettingsResult SettingsService::Deserialize(const std::string& text, const SettingsOptions& options)
{
    try {
        return Normalize(json::parse(text), options);
    } catch (const std::exception& e) {
        SettingsResult result;
        result.values = Defaults();
        AddIssue(result.issues, "", "parse", std::string("invalid settings JSON: ") + e.what(),
                 SettingsSeverity::Error);
        return result;
    } catch (...) {
        SettingsResult result;
        result.values = Defaults();
        AddIssue(result.issues, "", "parse", "invalid settings JSON", SettingsSeverity::Error);
        return result;
    }
}

// ── 类型化读取 ────────────────────────────────────────────────────

bool SettingsService::GetBool(const json& values, const std::string& key)
{
    const SettingSpec* spec = Find(key);
    if (spec == nullptr || spec->type != SettingSpec::Type::Bool) return false;
    if (!values.is_object() || !values.contains(key)) return spec->defaultValue.get<bool>();
    const json& value = values.at(key);
    return value.is_boolean() ? value.get<bool>() : spec->defaultValue.get<bool>();
}

long long SettingsService::GetInt(const json& values, const std::string& key)
{
    const SettingSpec* spec = Find(key);
    if (spec == nullptr || spec->type != SettingSpec::Type::Int) return 0;
    const long long fallback = spec->defaultValue.get<long long>();
    if (!values.is_object() || !values.contains(key)) return fallback;
    long long result = 0;
    return IntegralJson(values.at(key), result) ? result : fallback;
}

double SettingsService::GetDouble(const json& values, const std::string& key)
{
    const SettingSpec* spec = Find(key);
    if (spec == nullptr || spec->type != SettingSpec::Type::Double) return 0.0;
    const double fallback = spec->defaultValue.get<double>();
    if (!values.is_object() || !values.contains(key)) return fallback;
    double result = 0.0;
    return NumberJson(values.at(key), result) ? result : fallback;
}

std::string SettingsService::GetString(const json& values, const std::string& key)
{
    const SettingSpec* spec = Find(key);
    if (spec == nullptr || spec->type != SettingSpec::Type::String) return std::string();
    const std::string fallback = spec->defaultValue.get<std::string>();
    if (!values.is_object() || !values.contains(key)) return fallback;
    const json& value = values.at(key);
    return value.is_string() ? value.get<std::string>() : fallback;
}

// ── 密钥边界工具 ──────────────────────────────────────────────────

bool SettingsService::IsSecretLikeKey(const std::string& key)
{
    return IsSecretLikeKeyImpl(key);
}

bool SettingsService::ContainsSecretLikeKey(const json& values)
{
    return ContainsSecretKeyIn(values);
}

json SettingsService::StripSecretLikeKeys(const json& values)
{
    json copy = values;
    std::vector<SettingsIssue> ignored;
    StripSecretsInPlace(copy, std::string(), ignored);
    return copy;
}

bool SettingsResult::HasErrors() const
{
    for (const SettingsIssue& issue : issues) {
        if (issue.severity == SettingsSeverity::Error) return true;
    }
    return false;
}

} // namespace pb
