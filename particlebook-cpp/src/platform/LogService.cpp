// ── PB-016（A）：本地诊断日志实现 ────────────────────────────────────
// 见 platform/LogService.h 的契约说明。本文件只依赖 std + Win32（kernel32），
// 单测直接链接本 .cpp 即可（和 JobService / SecretStore 一样）。
//
// 组成：
//   1. 脱敏引擎（Redactor）：路径规则 → 键值/请求头规则 → URL 凭据 →
//      令牌形状，四道串联，全部在写盘之前完成。
//   2. 滚动写入（FileSink + RotateLocked）：单文件达到上限就
//      current.log → current.1.log → … → current.N.log，超出 N 的删掉。
//   3. 系统信息（SystemInfoText）：OS/架构/CPU/内存/时区/语言/进程，
//      整段再过一遍 Redact()（双保险：环境变量本身可能含用户名）。
//   4. 诊断包导出（ExportDiagnostics）：复制日志 + 版本/系统/设置/README，
//      纯本地文件操作，不联网、不加任何统计埋点。

#ifndef NOMINMAX
#define NOMINMAX
#endif

#include "platform/LogService.h"

#include "utils/encoding.h"   // Utf8ToWide / WideToUtf8（内部经 pb_win.h 引 windows.h）
#include "utils/pb_win.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <string>
#include <utility>
#include <vector>

namespace pb {
namespace {

namespace fs = std::filesystem;

// 单条消息的硬上限：防止某处把整个 HTML / 书内容塞进日志。
constexpr size_t kMaxMessageBytes = 32u * 1024u;
// 键有多长就扫多远的兜底（防御性，避免病态输入把一次 Redact 拖成 O(n²)）。
constexpr size_t kMaxKeyScanBytes = 8192u;

inline size_t MinSize(size_t a, size_t b) { return a < b ? a : b; }

inline char LowerAscii(char c)
{
    return (c >= 'A' && c <= 'Z') ? static_cast<char>(c - 'A' + 'a') : c;
}

inline bool IsAsciiDigit(char c) { return c >= '0' && c <= '9'; }
inline bool IsAsciiLetter(char c) { return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'); }
inline bool IsAsciiAlnum(char c) { return IsAsciiDigit(c) || IsAsciiLetter(c); }

inline bool IsSep(char c) { return c == '\\' || c == '/'; }
inline bool IsKeyNameChar(char c) { return IsAsciiAlnum(c) || c == '_' || c == '-'; }

inline bool IsKeyStartBoundary(const std::string& s, size_t i)
{
    return i == 0 || !IsKeyNameChar(s[i - 1]);
}

// 非引号值的结束边界。':' 不算边界（值里可能有端口/时间）。
inline bool IsValueDelim(char c)
{
    switch (c) {
    case ' ': case '\t': case '\r': case '\n': case ',':
    case ';': case '&': case '"': case '\'': case '}':
    case ']': case ')': case '<': case '>':
        return true;
    default:
        return false;
    }
}

inline bool IsSecretRunChar(char c)
{
    return IsAsciiAlnum(c) || c == '_' || c == '-' || c == '+' || c == '=';
}

inline bool IsKnownTokenRunChar(char c)
{
    return IsSecretRunChar(c) || c == '.' || c == '/';
}

bool StartsWithNoCase(const std::string& s, size_t pos, const char* needle)
{
    const size_t n = std::strlen(needle);
    if (pos + n > s.size()) return false;
    for (size_t k = 0; k < n; ++k) {
        if (LowerAscii(s[pos + k]) != LowerAscii(needle[k])) return false;
    }
    return true;
}

bool StartsWith(const std::string& s, size_t pos, const char* needle)
{
    const size_t n = std::strlen(needle);
    if (pos + n > s.size()) return false;
    return std::memcmp(s.data() + pos, needle, n) == 0;
}

bool EqualsNoCase(const std::string& a, const std::string& b)
{
    if (a.size() != b.size()) return false;
    for (size_t i = 0; i < a.size(); ++i) {
        if (LowerAscii(a[i]) != LowerAscii(b[i])) return false;
    }
    return true;
}

std::string ReplaceAllNoCase(const std::string& text, const std::string& needle,
                             const std::string& replacement)
{
    if (needle.empty() || needle.size() > text.size()) return text;
    std::string out;
    out.reserve(text.size());
    size_t i = 0;
    while (i < text.size()) {
        if (StartsWithNoCase(text, i, needle.c_str())) {
            out += replacement;
            i += needle.size();
        } else {
            out.push_back(text[i]);
            ++i;
        }
    }
    return out;
}

std::string TrimAscii(const std::string& text)
{
    size_t begin = 0;
    size_t end = text.size();
    while (begin < end && (text[begin] == ' ' || text[begin] == '\t' || text[begin] == '\r' || text[begin] == '\n')) ++begin;
    while (end > begin && (text[end - 1] == ' ' || text[end - 1] == '\t' || text[end - 1] == '\r' || text[end - 1] == '\n')) --end;
    return text.substr(begin, end - begin);
}

std::string TrimTrailingSeps(std::string path)
{
    while (path.size() > 3 && IsSep(path.back())) path.pop_back();
    return path;
}

fs::path ToFsPath(const std::string& utf8)
{
    return fs::path(Utf8ToWide(utf8));
}

std::string ResolveAbsolute(const std::string& path)
{
    if (path.empty()) return path;
    std::error_code ec;
    const fs::path abs = fs::absolute(ToFsPath(path), ec);
    if (ec) return path;
    return WideToUtf8(abs.wstring());
}

std::string JoinPath(const std::string& dir, const std::string& name)
{
    if (dir.empty()) return name;
    if (name.empty()) return dir;
    const char last = dir.back();
    if (last == '\\' || last == '/') return dir + name;
    return dir + "\\" + name;
}

std::string BaseName(const std::string& path)
{
    const size_t pos = path.find_last_of("\\/");
    return (pos == std::string::npos) ? path : path.substr(pos + 1);
}

std::string GetEnvUtf8(const wchar_t* name)
{
    const DWORD size = GetEnvironmentVariableW(name, nullptr, 0);
    if (size == 0) return std::string();
    std::wstring buffer(size, L'\0');
    const DWORD written = GetEnvironmentVariableW(name, &buffer[0], size);
    if (written == 0) return std::string();
    buffer.resize(written);
    return WideToUtf8(buffer);
}

std::string GetCurrentDirUtf8()
{
    const DWORD size = GetCurrentDirectoryW(0, nullptr);
    if (size == 0) return std::string();
    std::wstring buffer(size, L'\0');
    const DWORD written = GetCurrentDirectoryW(size, &buffer[0]);
    if (written == 0) return std::string();
    buffer.resize(written);
    return WideToUtf8(buffer);
}

std::string GetModulePathUtf8()
{
    std::wstring buffer(1024, L'\0');
    for (;;) {
        const DWORD written = GetModuleFileNameW(nullptr, &buffer[0], static_cast<DWORD>(buffer.size()));
        if (written == 0) return std::string();
        if (written < buffer.size()) {
            buffer.resize(written);
            return WideToUtf8(buffer);
        }
        buffer.resize(buffer.size() * 2);
        if (buffer.size() > 32u * 1024u) return std::string();
    }
}

std::vector<std::string> SplitLines(const std::string& text)
{
    std::vector<std::string> lines;
    std::string current;
    for (char c : text) {
        if (c == '\r') continue;
        if (c == '\n') {
            lines.push_back(current);
            current.clear();
        } else {
            current.push_back(c);
        }
    }
    if (!current.empty()) lines.push_back(current);
    return lines;
}

std::string SanitizeCategory(const std::string& category)
{
    std::string out;
    out.reserve(16);
    for (char c : category) {
        if (out.size() >= 32) break;
        const unsigned char uc = static_cast<unsigned char>(c);
        if (c == '\r' || c == '\n' || c == '\t' || c == ']' || c == '[' || uc < 0x20) {
            out.push_back('_');
        } else {
            out.push_back(c);
        }
    }
    if (out.empty()) out = "-";
    return out;
}

// CR/LF 压平成 " | "：一次 Write 恰好对应文件里的一行，并发写不可能撕裂。
std::string SanitizeMessage(const std::string& message)
{
    std::string out;
    out.reserve(message.size() + 8);
    for (size_t i = 0; i < message.size(); ++i) {
        const char c = message[i];
        if (out.size() >= kMaxMessageBytes) {
            out += " [truncated]";
            return out;
        }
        if (c == '\r') {
            if (i + 1 < message.size() && message[i + 1] == '\n') ++i;
            out += " | ";
        } else if (c == '\n') {
            out += " | ";
        } else if (c == '\0') {
            out.push_back(' ');
        } else {
            out.push_back(c);
        }
    }
    while (!out.empty() && out.back() == ' ') out.pop_back();
    return out;
}

std::string JsonEscape(const std::string& text)
{
    std::string out;
    out.reserve(text.size() + 8);
    for (char c : text) {
        switch (c) {
        case '"':  out += "\\\""; break;
        case '\\': out += "\\\\"; break;
        case '\n': out += "\\n"; break;
        case '\r': out += "\\r"; break;
        case '\t': out += "\\t"; break;
        default: {
            const unsigned char uc = static_cast<unsigned char>(c);
            if (uc < 0x20) {
                char buf[8];
                std::snprintf(buf, sizeof buf, "\\u%04x", static_cast<unsigned>(uc));
                out += buf;
            } else {
                out.push_back(c);
            }
            break;
        }
        }
    }
    return out;
}

bool WriteTextFileUtf8(const std::string& path, const std::string& text)
{
    std::ofstream out(ToFsPath(path), std::ios::binary | std::ios::out | std::ios::trunc);
    if (!out.is_open()) return false;
    out.write(text.data(), static_cast<std::streamsize>(text.size()));
    out.flush();
    return out.good();
}

// ── 时间 ─────────────────────────────────────────────────────────────
int UtcOffsetMinutes()
{
    SYSTEMTIME local{};
    SYSTEMTIME utc{};
    GetLocalTime(&local);
    GetSystemTime(&utc);
    FILETIME fileLocal{};
    FILETIME fileUtc{};
    if (!SystemTimeToFileTime(&local, &fileLocal) || !SystemTimeToFileTime(&utc, &fileUtc)) {
        return 0;
    }
    ULARGE_INTEGER a{};
    ULARGE_INTEGER b{};
    a.LowPart = fileLocal.dwLowDateTime;
    a.HighPart = fileLocal.dwHighDateTime;
    b.LowPart = fileUtc.dwLowDateTime;
    b.HighPart = fileUtc.dwHighDateTime;
    const long long diff100ns = static_cast<long long>(a.QuadPart) - static_cast<long long>(b.QuadPart);
    // 时区偏移总是 15 分钟的整数倍，采样抖动只有毫秒级，整除即可。
    return static_cast<int>(diff100ns / 600000000LL);
}

void FormatUtcOffset(char* buf, size_t size)
{
    const int minutes = UtcOffsetMinutes();
    const char sign = (minutes < 0) ? '-' : '+';
    const int absolute = (minutes < 0) ? -minutes : minutes;
    std::snprintf(buf, size, "%c%02d:%02d", sign, absolute / 60, absolute % 60);
}

// 2026-10-26T18:01:46.123+08:00
std::string LocalTimestamp()
{
    SYSTEMTIME st{};
    GetLocalTime(&st);
    char offset[8];
    FormatUtcOffset(offset, sizeof offset);
    char buf[64];
    std::snprintf(buf, sizeof buf, "%04u-%02u-%02uT%02u:%02u:%02u.%03u%s",
                  static_cast<unsigned>(st.wYear), static_cast<unsigned>(st.wMonth),
                  static_cast<unsigned>(st.wDay), static_cast<unsigned>(st.wHour),
                  static_cast<unsigned>(st.wMinute), static_cast<unsigned>(st.wSecond),
                  static_cast<unsigned>(st.wMilliseconds), offset);
    return std::string(buf);
}

// 20261026-180146（用于诊断包目录名）
std::string CompactTimestamp()
{
    SYSTEMTIME st{};
    GetLocalTime(&st);
    char buf[32];
    std::snprintf(buf, sizeof buf, "%04u%02u%02u-%02u%02u%02u",
                  static_cast<unsigned>(st.wYear), static_cast<unsigned>(st.wMonth),
                  static_cast<unsigned>(st.wDay), static_cast<unsigned>(st.wHour),
                  static_cast<unsigned>(st.wMinute), static_cast<unsigned>(st.wSecond));
    return std::string(buf);
}

// ── 系统信息 ─────────────────────────────────────────────────────────
struct OsVersionInfoW {
    ULONG dwOSVersionInfoSize;
    ULONG dwMajorVersion;
    ULONG dwMinorVersion;
    ULONG dwBuildNumber;
    ULONG dwPlatformId;
    WCHAR szCSDVersion[128];
};

using RtlGetVersionFn = LONG(WINAPI*)(OsVersionInfoW*);

// RtlGetVersion 是唯一不受 manifest 兼容性伪造影响的版本来源。
// 用 GetProcAddress + memcpy 取函数指针，避免额外链接 ntdll.lib，
// 也避免 /W4 下的 C4191（FARPROC 直接转函数指针）。
bool QueryOsVersion(OsVersionInfoW& info)
{
    std::memset(&info, 0, sizeof info);
    info.dwOSVersionInfoSize = sizeof info;
    HMODULE ntdll = GetModuleHandleW(L"ntdll.dll");
    if (!ntdll) ntdll = LoadLibraryW(L"ntdll.dll");
    if (!ntdll) return false;
    FARPROC proc = GetProcAddress(ntdll, "RtlGetVersion");
    if (!proc) return false;
    RtlGetVersionFn fn = nullptr;
    static_assert(sizeof(fn) == sizeof(proc), "unexpected FARPROC size");
    std::memcpy(&fn, &proc, sizeof fn);
    if (!fn) return false;
    return fn(&info) == 0;
}

const char* WindowsProductLabel(const OsVersionInfoW& info)
{
    if (info.dwMajorVersion == 10) return info.dwBuildNumber >= 22000 ? "11" : "10";
    if (info.dwMajorVersion == 6 && info.dwMinorVersion == 3) return "8.1";
    if (info.dwMajorVersion == 6 && info.dwMinorVersion == 2) return "8";
    if (info.dwMajorVersion == 6 && info.dwMinorVersion == 1) return "7";
    return "unknown";
}

std::string OsVersionText()
{
    OsVersionInfoW info{};
    if (!QueryOsVersion(info)) return "Windows (version unavailable)";
    char build[64];
    std::snprintf(build, sizeof build, "%lu.%lu.%lu",
                  static_cast<unsigned long>(info.dwMajorVersion),
                  static_cast<unsigned long>(info.dwMinorVersion),
                  static_cast<unsigned long>(info.dwBuildNumber));
    std::string text = "Windows ";
    text += WindowsProductLabel(info);
    text += " (";
    text += build;
    text += ")";
    return text;
}

std::string ArchText()
{
    SYSTEM_INFO si{};
    GetNativeSystemInfo(&si);
    switch (si.wProcessorArchitecture) {
    case PROCESSOR_ARCHITECTURE_AMD64: return "x64";
    case PROCESSOR_ARCHITECTURE_ARM64: return "ARM64";
    case PROCESSOR_ARCHITECTURE_INTEL: return "x86";
    case PROCESSOR_ARCHITECTURE_ARM:   return "ARM";
    default:                           return "unknown";
    }
}

std::string CpuText()
{
    SYSTEM_INFO si{};
    GetNativeSystemInfo(&si);
    char buf[96];
    std::snprintf(buf, sizeof buf, "%lu logical processor(s), page %lu B",
                  static_cast<unsigned long>(si.dwNumberOfProcessors),
                  static_cast<unsigned long>(si.dwPageSize));
    return std::string(buf);
}

std::string MemoryText()
{
    MEMORYSTATUSEX status{};
    status.dwLength = sizeof status;
    if (!GlobalMemoryStatusEx(&status)) return "unavailable";
    const double gib = 1024.0 * 1024.0 * 1024.0;
    char buf[96];
    std::snprintf(buf, sizeof buf, "%.1f GiB total, %.1f GiB available",
                  static_cast<double>(status.ullTotalPhys) / gib,
                  static_cast<double>(status.ullAvailPhys) / gib);
    return std::string(buf);
}

std::string TimezoneText()
{
    TIME_ZONE_INFORMATION tz{};
    const DWORD id = GetTimeZoneInformation(&tz);
    const wchar_t* name = (id == TIME_ZONE_ID_DAYLIGHT) ? tz.DaylightName : tz.StandardName;
    std::string label = name ? WideToUtf8(name) : std::string();
    if (label.empty()) label = "unknown";
    char offset[8];
    FormatUtcOffset(offset, sizeof offset);
    return label + " (UTC" + offset + ")";
}

std::string LocaleText()
{
    wchar_t name[LOCALE_NAME_MAX_LENGTH]{};
    std::string text = "unknown";
    if (GetUserDefaultLocaleName(name, LOCALE_NAME_MAX_LENGTH) > 0) text = WideToUtf8(name);
    char buf[64];
    std::snprintf(buf, sizeof buf, " (ACP %u, OEM %u)",
                  static_cast<unsigned>(GetACP()), static_cast<unsigned>(GetOEMCP()));
    return text + buf;
}

// ── 令牌形状 ─────────────────────────────────────────────────────────
const char* const kTokenPrefixes[] = {
    "sk-ant-", "sk-proj-", "sk-", "sk_", "ghp_", "gho_", "ghu_", "ghs_",
    "github_pat_", "AIza", "AKIA", "ASIA", "xoxb-", "xoxp-", "npm_", "eyJ",
};

bool MatchKnownToken(const std::string& s, size_t i, size_t& end)
{
    if (i > 0 && IsKnownTokenRunChar(s[i - 1])) return false;
    for (const char* prefix : kTokenPrefixes) {
        if (!StartsWith(s, i, prefix)) continue;
        const size_t bodyStart = i + std::strlen(prefix);
        size_t k = bodyStart;
        while (k < s.size() && IsKnownTokenRunChar(s[k])) ++k;
        const size_t minBody = (std::strcmp(prefix, "eyJ") == 0) ? 20u : 16u;
        if (k - bodyStart >= minBody) {
            end = k;
            return true;
        }
    }
    return false;
}

// >= 40 字符、字母+数字混合的长串（base64 / hex 令牌）。普通英文、路径、
// 时间戳都过不了这条：路径有分隔符，时间戳含 ':'。
bool MatchLongToken(const std::string& s, size_t i, size_t& end)
{
    if (!IsSecretRunChar(s[i])) return false;
    if (i > 0 && IsSecretRunChar(s[i - 1])) return false;
    bool hasDigit = false;
    bool hasLetter = false;
    size_t k = i;
    while (k < s.size() && IsSecretRunChar(s[k])) {
        const char c = s[k];
        if (IsAsciiDigit(c)) hasDigit = true;
        else if (IsAsciiLetter(c)) hasLetter = true;
        ++k;
    }
    if (k - i >= 40 && hasDigit && hasLetter) {
        end = k;
        return true;
    }
    return false;
}

} // namespace

// ── 脱敏引擎 ─────────────────────────────────────────────────────────
struct LogService::Redactor {
    struct KeyRule {
        std::string name;
        bool header = false;
    };

    bool enabled = true;
    std::string placeholder = "<redacted>";
    bool headers = true;
    bool urlCredentials = true;
    bool tokenShapes = true;
    bool userPaths = true;
    std::vector<KeyRule> keys;
    std::vector<std::pair<std::string, std::string>> paths;

    explicit Redactor(const LogRedactionOptions& options);

    std::string Apply(const std::string& text) const;
    std::string RedactPaths(const std::string& text) const;
    std::string RedactPairs(const std::string& text) const;
    std::string RedactUrlCredentials(const std::string& text) const;
    std::string RedactTokenShapes(const std::string& text) const;
};

namespace {

// 内置敏感键名。header=true 的按「整行/整段」脱敏（Cookie 值里含 ';'、'='，
// Authorization 值里含空格，不能用普通的「到第一个分隔符」规则）。
const struct { const char* name; bool header; } kBuiltinKeys[] = {
    { "proxy-authorization", true },
    { "authorization",       true },
    { "set-cookie",          true },
    { "cookie",              true },
    { "x-api-key",           false },
    { "x-auth-token",        false },
    { "api-key",             false },
    { "api_key",             false },
    { "apikey",              false },
    { "access_token",        false },
    { "refresh_token",       false },
    { "id_token",            false },
    { "auth_token",          false },
    { "client_secret",       false },
    { "clientsecret",        false },
    { "remix_userkey",       false },
    { "userkey",             false },
    { "session_id",          false },
    { "sessionid",           false },
    { "session",             false },
    { "password",            false },
    { "passwd",              false },
    { "secret",              false },
    { "token",               false },
    { "pwd",                 false },
    { "key",                 false },
};

void AddPathRule(std::vector<std::pair<std::string, std::string>>& rules,
                 const std::string& literal, const std::string& placeholder)
{
    const std::string trimmed = TrimTrailingSeps(TrimAscii(literal));
    if (trimmed.size() < 3) return;
    for (const auto& existing : rules) {
        if (EqualsNoCase(existing.first, trimmed)) return;
    }
    rules.emplace_back(trimmed, placeholder);
}

void AppendLiteralVariants(const std::string& literal, std::vector<std::string>& out)
{
    out.push_back(literal);
    bool hasBackslash = false;
    for (char c : literal) {
        if (c == '\\') { hasBackslash = true; break; }
    }
    if (!hasBackslash) return;
    std::string forward;
    forward.reserve(literal.size());
    for (char c : literal) forward.push_back(c == '\\' ? '/' : c);
    out.push_back(forward);
    std::string escaped;
    escaped.reserve(literal.size() + 4);
    for (char c : literal) {
        if (c == '\\') escaped += "\\\\";
        else escaped.push_back(c);
    }
    out.push_back(escaped);
}

// C:\Users\<name>\... → C:\Users\<user>\...（避免泄漏其他账户名，也覆盖
// 上面环境变量没覆盖到的路径）。
std::string RedactUserSegments(const std::string& s, const std::string& placeholder)
{
    if (s.size() < 8) return s;
    std::string out;
    out.reserve(s.size());
    const size_t n = s.size();
    size_t copied = 0;
    size_t i = 0;
    while (i + 5 <= n) {
        if (!StartsWithNoCase(s, i, "users")) { ++i; continue; }
        if (i == 0 || !IsSep(s[i - 1])) { ++i; continue; }
        size_t p = i + 5;
        if (p >= n || !IsSep(s[p])) { ++i; continue; }
        size_t segStart = p;
        while (segStart < n && IsSep(s[segStart])) ++segStart;
        if (segStart >= n) break;
        if (s[segStart] == '<') { i = segStart + 1; continue; }  // 已是占位符
        size_t segEnd = segStart;
        while (segEnd < n) {
            const char c = s[segEnd];
            if (IsSep(c) || c == ' ' || c == '\t' || c == '\r' || c == '\n' ||
                c == '"' || c == '\'' || c == ',' || c == ';' || c == ')' ||
                c == '>' || c == '?' || c == '<') {
                break;
            }
            ++segEnd;
        }
        if (segEnd <= segStart) { ++i; continue; }
        out.append(s, copied, segStart - copied);
        out.append(placeholder);
        copied = segEnd;
        i = segEnd;
    }
    out.append(s, copied, n - copied);
    return out;
}

} // namespace

LogService::Redactor::Redactor(const LogRedactionOptions& options)
{
    enabled = options.enabled;
    placeholder = options.placeholder.empty() ? std::string("<redacted>") : options.placeholder;
    headers = options.headers;
    urlCredentials = options.urlCredentials;
    tokenShapes = options.tokenShapes;
    userPaths = options.userPaths;

    for (const auto& builtin : kBuiltinKeys) {
        if (builtin.header && !headers) continue;
        keys.push_back(KeyRule{ builtin.name, builtin.header });
    }
    for (const std::string& extra : options.extraKeys) {
        const std::string name = TrimAscii(extra);
        if (name.empty()) continue;
        bool duplicate = false;
        for (const KeyRule& rule : keys) {
            if (EqualsNoCase(rule.name, name)) { duplicate = true; break; }
        }
        if (!duplicate) keys.push_back(KeyRule{ name, false });
    }
    // 名字长的先匹配："x-api-key" 必须赢过 "key"。
    std::stable_sort(keys.begin(), keys.end(), [](const KeyRule& a, const KeyRule& b) {
        return a.name.size() > b.name.size();
    });

    for (const auto& extra : options.extraPaths) {
        if (extra.first.empty() || extra.second.empty()) continue;
        std::string literal = TrimTrailingSeps(TrimAscii(extra.first));
        if (literal.empty()) continue;
        AddPathRule(paths, literal, extra.second);
    }
    if (userPaths) {
        struct EnvRule { const wchar_t* name; const char* placeholder; };
        const EnvRule envRules[] = {
            { L"TEMP",         "<temp>" },
            { L"TMP",          "<temp>" },
            { L"LOCALAPPDATA", "<localAppData>" },
            { L"APPDATA",      "<appData>" },
            { L"USERPROFILE",  "<user>" },
        };
        for (const EnvRule& rule : envRules) {
            AddPathRule(paths, GetEnvUtf8(rule.name), rule.placeholder);
        }
        const std::string homeDrive = GetEnvUtf8(L"HOMEDRIVE");
        const std::string homePath = GetEnvUtf8(L"HOMEPATH");
        if (!homeDrive.empty() && !homePath.empty()) {
            AddPathRule(paths, homeDrive + homePath, "<user>");
        }
        AddPathRule(paths, GetCurrentDirUtf8(), "<cwd>");
    }
    // 更长（更具体）的字面量先替换。
    std::stable_sort(paths.begin(), paths.end(),
                     [](const std::pair<std::string, std::string>& a,
                        const std::pair<std::string, std::string>& b) {
                         return a.first.size() > b.first.size();
                     });
}

std::string LogService::Redactor::Apply(const std::string& text) const
{
    if (!enabled || text.empty()) return text;
    std::string s = RedactPaths(text);
    s = RedactPairs(s);
    if (urlCredentials) s = RedactUrlCredentials(s);
    if (tokenShapes) s = RedactTokenShapes(s);
    return s;
}

std::string LogService::Redactor::RedactPaths(const std::string& text) const
{
    std::string s = text;
    std::vector<std::string> variants;
    for (const auto& rule : paths) {
        variants.clear();
        AppendLiteralVariants(rule.first, variants);
        for (const std::string& variant : variants) {
            s = ReplaceAllNoCase(s, variant, rule.second);
        }
    }
    if (userPaths) s = RedactUserSegments(s, "<user>");
    return s;
}

std::string LogService::Redactor::RedactPairs(const std::string& text) const
{
    if (keys.empty() || text.empty()) return text;
    const size_t n = text.size();
    std::string out;
    out.reserve(n);
    size_t copied = 0;
    size_t i = 0;
    while (i < n) {
        if (!IsKeyStartBoundary(text, i)) { ++i; continue; }
        const KeyRule* matched = nullptr;
        for (const KeyRule& rule : keys) {
            if (StartsWithNoCase(text, i, rule.name.c_str())) { matched = &rule; break; }
        }
        if (!matched) { ++i; continue; }
        const size_t afterKey = i + matched->name.size();
        if (afterKey < n && IsKeyNameChar(text[afterKey])) { ++i; continue; }
        size_t p = afterKey;
        // JSON 形式 "api_key": "value" —— 键名后面可能先跟一个收尾引号。
        if (p < n && (text[p] == '"' || text[p] == '\'')) ++p;
        while (p < n && (text[p] == ' ' || text[p] == '\t')) ++p;
        if (p >= n) { ++i; continue; }
        if (text[p] != ':' && text[p] != '=') { ++i; continue; }
        ++p;
        while (p < n && (text[p] == ' ' || text[p] == '\t')) ++p;
        if (p >= n) { ++i; continue; }

        size_t valueStart = p;
        size_t valueEnd = p;
        if (text[p] == '"' || text[p] == '\'') {
            const char quote = text[p];
            valueStart = p + 1;
            size_t k = valueStart;
            const size_t limit = MinSize(n, valueStart + kMaxKeyScanBytes);
            while (k < limit) {
                const char c = text[k];
                if (c == '\\' && k + 1 < n) { k += 2; continue; }
                if (c == quote || c == '\r' || c == '\n') break;
                ++k;
            }
            valueEnd = k;
        } else if (matched->header) {
            size_t k = p;
            const size_t limit = MinSize(n, p + kMaxKeyScanBytes);
            while (k < limit && text[k] != '\r' && text[k] != '\n') ++k;
            valueEnd = k;
        } else {
            size_t k = p;
            const size_t limit = MinSize(n, p + 4096);
            while (k < limit && !IsValueDelim(text[k])) ++k;
            valueEnd = k;
        }
        if (valueEnd > valueStart) {
            out.append(text, copied, valueStart - copied);
            out.append(placeholder);
            copied = valueEnd;
            i = valueEnd;
            continue;
        }
        ++i;
    }
    out.append(text, copied, n - copied);
    return out;
}

std::string LogService::Redactor::RedactUrlCredentials(const std::string& text) const
{
    const size_t n = text.size();
    std::string out;
    out.reserve(n);
    size_t copied = 0;
    size_t i = 0;
    while (i + 3 < n) {
        const size_t pos = text.find("://", i);
        if (pos == std::string::npos) break;
        const size_t start = pos + 3;
        size_t k = start;
        size_t at = std::string::npos;
        const size_t limit = MinSize(n, start + 2048);
        while (k < limit) {
            const char c = text[k];
            if (IsSep(c) || c == '?' || c == '#' || c == ' ' || c == '\t' ||
                c == '\r' || c == '\n' || c == '"' || c == '\'' || c == ')' ||
                c == ',' || c == ';' || c == '<' || c == '>') {
                break;
            }
            if (c == '@') at = k;
            ++k;
        }
        if (at != std::string::npos && at > start) {
            out.append(text, copied, start - copied);
            out.append(placeholder);
            copied = at;
            i = k;
            continue;
        }
        i = (k > pos) ? k : pos + 3;
    }
    out.append(text, copied, n - copied);
    return out;
}

std::string LogService::Redactor::RedactTokenShapes(const std::string& text) const
{
    const size_t n = text.size();
    std::string out;
    out.reserve(n);
    size_t i = 0;
    while (i < n) {
        size_t end = 0;
        if (MatchKnownToken(text, i, end) || MatchLongToken(text, i, end)) {
            out += placeholder;
            i = end;
            continue;
        }
        out.push_back(text[i]);
        ++i;
    }
    return out;
}

// ── LogService ───────────────────────────────────────────────────────
struct LogService::FileSink {
    std::ofstream stream;
};

namespace {

const char* const kReadmeText =
    "ParticleBook diagnostic package\r\n"
    "================================\r\n"
    "\r\n"
    "This folder was created locally by ParticleBook's \"Export diagnostics\"\r\n"
    "action. Nothing here was uploaded, and ParticleBook does not send usage\r\n"
    "telemetry.\r\n"
    "\r\n"
    "Contents\r\n"
    "--------\r\n"
    "  *.log         Rolling application logs (oldest last: .1.log is the\r\n"
    "                previous file, .2.log older still).\r\n"
    "  system.txt    OS / architecture / CPU / memory / timezone / locale /\r\n"
    "                process information.\r\n"
    "  version.txt   App version, log format version, export time.\r\n"
    "  settings.json Effective log settings, including the redaction rules.\r\n"
    "  README.txt    This file.\r\n"
    "\r\n"
    "Privacy / redaction\r\n"
    "--------------------\r\n"
    "Before anything is written to disk, ParticleBook redacts:\r\n"
    "  * Authorization / Cookie / Set-Cookie header values;\r\n"
    "  * values of keys such as apiKey, api_key, token, secret, password,\r\n"
    "    x-api-key, access_token, refresh_token, session, userkey, key;\r\n"
    "  * URL-embedded credentials (https://user:password@host);\r\n"
    "  * well-known token shapes (sk-..., ghp_..., AIza..., AKIA..., JWTs)\r\n"
    "    and long random strings;\r\n"
    "  * your user directory prefixes (%USERPROFILE%, %LOCALAPPDATA%,\r\n"
    "    %APPDATA%, %TEMP%) and other C:\\Users\\<name> paths.\r\n"
    "Redacted values appear as <redacted> (or as path placeholders such as\r\n"
    "<user>, <appData>, <localAppData>, <temp>, <cwd>).\r\n"
    "\r\n"
    "Not included: book files, reading positions, highlights, notes, the\r\n"
    "library database, provider API keys, cookies or saved passwords.\r\n"
    "\r\n"
    "Review the files before sharing them.\r\n";

} // namespace

// ── 生命周期 ─────────────────────────────────────────────────────────
LogService::LogService(LogOptions options)
    : m_options(std::move(options))
{
    if (m_options.maxFileBytes < 4096) m_options.maxFileBytes = 4096;
    if (m_options.maxBackupFiles > 64) m_options.maxBackupFiles = 64;
    if (m_options.fileStem.empty()) m_options.fileStem = "particlebook";
    if (m_options.redaction.placeholder.empty()) m_options.redaction.placeholder = "<redacted>";
    m_level.store(static_cast<int>(m_options.level), std::memory_order_relaxed);
    m_redactor = std::make_unique<Redactor>(m_options.redaction);
}

LogService::~LogService()
{
    Stop();
}

bool LogService::Start(std::string* error)
{
    bool started = false;
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        if (m_started) return true;
        if (m_options.maxFileBytes < 4096) m_options.maxFileBytes = 4096;
        if (m_options.maxBackupFiles > 64) m_options.maxBackupFiles = 64;
        if (m_options.fileStem.empty()) m_options.fileStem = "particlebook";
        m_level.store(static_cast<int>(m_options.level), std::memory_order_relaxed);

        const std::string configured = m_options.directory.empty()
                                           ? DefaultLogDirectory()
                                           : m_options.directory;
        m_directory = ResolveAbsolute(configured);
        std::error_code ec;
        fs::create_directories(ToFsPath(m_directory), ec);
        if (!fs::is_directory(ToFsPath(m_directory), ec)) {
            if (error) *error = "cannot create log directory: " + m_directory;
            return false;
        }
        m_currentPath = JoinPath(m_directory, m_options.fileStem + ".log");

        // 上一次运行留下的文件先滚动出去，本次从空文件开始（日志按运行分段，
        // 也避免无限追加）。滚动本身在 RotateLocked 里做级联重命名。
        if (FileSize(m_currentPath) > 0 && m_options.maxBackupFiles > 0) {
            RotateLocked();
        }
        if (!m_sink) {
            if (!OpenLocked(error)) return false;
        }
        m_started = true;
        started = true;
    }
    if (started && m_options.logStartupBanner) LogStartupBanner();
    return started;
}

void LogService::Stop()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_sink) {
        if (m_sink->stream.is_open()) {
            m_sink->stream.flush();
            m_sink->stream.close();
        }
        m_sink.reset();
    }
    m_started = false;
    m_bytes = 0;
}

bool LogService::IsStarted() const noexcept
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_started;
}

// ── 文件 ─────────────────────────────────────────────────────────────
bool LogService::OpenLocked(std::string* error)
{
    auto sink = std::make_unique<FileSink>();
    const std::wstring widePath = Utf8ToWide(m_currentPath);
    if (widePath.empty()) {
        if (error) *error = "invalid log path: " + m_currentPath;
        return false;
    }
    sink->stream.open(fs::path(widePath),
                      std::ios::binary | std::ios::out | std::ios::trunc);
    if (!sink->stream.is_open()) {
        if (error) *error = "cannot open log file: " + m_currentPath;
        return false;
    }
    m_sink = std::move(sink);
    m_bytes = 0;
    return true;
}

std::string LogService::BackupPathLocked(unsigned index) const
{
    return JoinPath(m_directory, m_options.fileStem + "." + std::to_string(index) + ".log");
}

void LogService::RotateLocked()
{
    if (m_sink) {
        if (m_sink->stream.is_open()) {
            m_sink->stream.flush();
            m_sink->stream.close();
        }
        m_sink.reset();
    }
    m_bytes = 0;
    ++m_rotations;

    const unsigned keep = m_options.maxBackupFiles;
    std::error_code ec;
    if (keep == 0) {
        fs::remove(ToFsPath(m_currentPath), ec);
        OpenLocked(nullptr);
        return;
    }

    // .N 扔掉，.N-1 → .N … .1 → .2，current → .1
    fs::remove(ToFsPath(BackupPathLocked(keep)), ec);
    for (unsigned i = keep; i >= 2; --i) {
        const std::string from = BackupPathLocked(i - 1);
        const std::string to = BackupPathLocked(i);
        fs::remove(ToFsPath(to), ec);
        if (fs::exists(ToFsPath(from), ec)) {
            fs::rename(ToFsPath(from), ToFsPath(to), ec);
        }
    }
    const std::string first = BackupPathLocked(1);
    fs::remove(ToFsPath(first), ec);
    if (fs::exists(ToFsPath(m_currentPath), ec)) {
        fs::rename(ToFsPath(m_currentPath), ToFsPath(first), ec);
    }
    OpenLocked(nullptr);
}

bool LogService::WriteRawLocked(const std::string& line)
{
    if (!m_started) return false;
    if (!m_sink || !m_sink->stream.is_open()) {
        if (!OpenLocked(nullptr)) return false;
    }
    const std::uint64_t maxBytes = m_options.maxFileBytes;
    if (maxBytes > 0 && m_bytes > 0 &&
        m_bytes + static_cast<std::uint64_t>(line.size()) > maxBytes) {
        RotateLocked();
        if (!m_sink) {
            if (!OpenLocked(nullptr)) return false;
        }
    }
    m_sink->stream.write(line.data(), static_cast<std::streamsize>(line.size()));
    if (!m_sink->stream.good()) return false;
    m_bytes += static_cast<std::uint64_t>(line.size());
    if (m_options.flushEachLine) m_sink->stream.flush();
    return true;
}

std::uint64_t LogService::FileSize(const std::string& path) const
{
    std::error_code ec;
    const std::uintmax_t size = fs::file_size(ToFsPath(path), ec);
    if (ec) return 0;
    return static_cast<std::uint64_t>(size);
}

// ── 级别 ─────────────────────────────────────────────────────────────
bool LogService::IsEnabled(LogLevel level) const noexcept
{
    const int current = m_level.load(std::memory_order_relaxed);
    if (level == LogLevel::Off) return false;
    if (current == static_cast<int>(LogLevel::Off)) return false;
    return static_cast<int>(level) >= current;
}

void LogService::SetLevel(LogLevel level) noexcept
{
    m_level.store(static_cast<int>(level), std::memory_order_relaxed);
}

LogLevel LogService::Level() const noexcept
{
    return static_cast<LogLevel>(m_level.load(std::memory_order_relaxed));
}

const char* LogLevelName(LogLevel level) noexcept
{
    switch (level) {
    case LogLevel::Trace:    return "TRACE";
    case LogLevel::Debug:    return "DEBUG";
    case LogLevel::Info:     return "INFO";
    case LogLevel::Warn:     return "WARN";
    case LogLevel::Error:    return "ERROR";
    case LogLevel::Critical: return "CRITICAL";
    case LogLevel::Off:      return "OFF";
    }
    return "INFO";
}

bool ParseLogLevel(const std::string& text, LogLevel& out) noexcept
{
    const std::string value = TrimAscii(text);
    struct Mapping { const char* name; LogLevel level; };
    const Mapping mappings[] = {
        { "trace",    LogLevel::Trace },
        { "debug",    LogLevel::Debug },
        { "verbose",  LogLevel::Debug },
        { "info",     LogLevel::Info },
        { "notice",   LogLevel::Info },
        { "warn",     LogLevel::Warn },
        { "warning",  LogLevel::Warn },
        { "err",      LogLevel::Error },
        { "error",    LogLevel::Error },
        { "critical", LogLevel::Critical },
        { "fatal",    LogLevel::Critical },
        { "off",      LogLevel::Off },
        { "none",     LogLevel::Off },
        { "silent",   LogLevel::Off },
    };
    for (const Mapping& mapping : mappings) {
        if (EqualsNoCase(value, mapping.name)) {
            out = mapping.level;
            return true;
        }
    }
    return false;
}

// ── 写入 ─────────────────────────────────────────────────────────────
std::string LogService::FormatLine(LogLevel level, const std::string& category,
                                   const std::string& message)
{
    const std::string cleanCategory = SanitizeCategory(category);
    const std::string cleanMessage = SanitizeMessage(message);
    char head[96];
    std::snprintf(head, sizeof head, " [%-8s] [t%lu] [%s] ",
                  LogLevelName(level),
                  static_cast<unsigned long>(GetCurrentThreadId()),
                  cleanCategory.c_str());
    std::string line = LocalTimestamp();
    line += head;
    line += cleanMessage;
    line = Redact(line);
    line += "\r\n";
    return line;
}

void LogService::Write(LogLevel level, const std::string& category,
                       const std::string& message)
{
    if (!IsEnabled(level)) {
        m_dropped.fetch_add(1, std::memory_order_relaxed);
        return;
    }
    const std::string line = FormatLine(level, category, message);
    if (m_options.echoToDebugger) {
        // OutputDebugString 的单条上限约 4 KB，截断后再送。
        OutputDebugStringA(line.substr(0, 4000).c_str());
    }
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!WriteRawLocked(line)) {
        m_dropped.fetch_add(1, std::memory_order_relaxed);
    }
}

void LogService::Trace(const std::string& category, const std::string& message)
{
    Write(LogLevel::Trace, category, message);
}
void LogService::Debug(const std::string& category, const std::string& message)
{
    Write(LogLevel::Debug, category, message);
}
void LogService::Info(const std::string& category, const std::string& message)
{
    Write(LogLevel::Info, category, message);
}
void LogService::Warn(const std::string& category, const std::string& message)
{
    Write(LogLevel::Warn, category, message);
}
void LogService::Error(const std::string& category, const std::string& message)
{
    Write(LogLevel::Error, category, message);
}
void LogService::Critical(const std::string& category, const std::string& message)
{
    Write(LogLevel::Critical, category, message);
}

void LogService::Flush()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_sink && m_sink->stream.is_open()) m_sink->stream.flush();
}

// ── 访问器 ───────────────────────────────────────────────────────────
std::string LogService::Directory() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!m_directory.empty()) return m_directory;
    const std::string configured = m_options.directory.empty()
                                       ? DefaultLogDirectory()
                                       : m_options.directory;
    return ResolveAbsolute(configured);
}

std::string LogService::CurrentLogPath() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!m_currentPath.empty()) return m_currentPath;
    const std::string configured = m_options.directory.empty()
                                       ? DefaultLogDirectory()
                                       : m_options.directory;
    return JoinPath(ResolveAbsolute(configured), m_options.fileStem + ".log");
}

std::uint64_t LogService::CurrentFileBytes() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_bytes;
}

std::uint64_t LogService::RotationCount() const noexcept
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_rotations;
}

std::uint64_t LogService::DroppedCount() const noexcept
{
    return m_dropped.load(std::memory_order_relaxed);
}

std::vector<std::string> LogService::LogFilesLocked() const
{
    std::vector<std::string> files;
    std::error_code ec;
    if (!m_currentPath.empty() && fs::exists(ToFsPath(m_currentPath), ec)) {
        files.push_back(m_currentPath);
    }
    for (unsigned i = 1; i <= m_options.maxBackupFiles; ++i) {
        const std::string backup = BackupPathLocked(i);
        if (fs::exists(ToFsPath(backup), ec)) files.push_back(backup);
    }
    return files;
}

std::vector<std::string> LogService::LogFiles() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return LogFilesLocked();
}

std::string LogService::Redact(const std::string& text) const
{
    if (!m_redactor) return text;
    return m_redactor->Apply(text);
}

// ── 系统信息 ─────────────────────────────────────────────────────────
std::string LogService::SystemInfoText() const
{
    std::string text;
    auto add = [&text](const char* key, const std::string& value) {
        text += key;
        text += ": ";
        text += value;
        text += "\r\n";
    };

    add("app version", m_options.appVersion.empty() ? std::string("(unknown)") : m_options.appVersion);
    if (!m_options.appChannel.empty()) add("app channel", m_options.appChannel);
    add("log format", std::to_string(kLogFormatVersion));
    add("os", OsVersionText());
    add("arch", ArchText());
    add("cpu", CpuText());
    add("memory", MemoryText());
    add("timezone", TimezoneText());
    add("locale", LocaleText());
    add("pid", std::to_string(static_cast<unsigned long>(GetCurrentProcessId())));
    add("module", GetModulePathUtf8());
    add("cwd", GetCurrentDirUtf8());
    add("log directory", Directory());
    add("log level", LogLevelName(Level()));
    add("log rotation", std::to_string(m_options.maxFileBytes) + " B x " +
                            std::to_string(m_options.maxBackupFiles) + " backup(s)");
    add("redaction", m_options.redaction.enabled
                         ? std::string("enabled (placeholder ") + m_options.redaction.placeholder + ")"
                         : std::string("disabled"));
    return Redact(text);
}

void LogService::LogStartupBanner()
{
    if (!IsEnabled(LogLevel::Info)) return;
    Write(LogLevel::Info, "log", "===== ParticleBook diagnostics start =====");
    for (const std::string& line : SplitLines(SystemInfoText())) {
        if (!line.empty()) Write(LogLevel::Info, "startup", line);
    }
    Write(LogLevel::Info, "log", "===== ParticleBook diagnostics end =====");
}

std::string LogService::VersionInfoText() const
{
    std::string text;
    auto add = [&text](const char* key, const std::string& value) {
        text += key;
        text += ": ";
        text += value;
        text += "\r\n";
    };
    add("package", "ParticleBook diagnostic package");
    add("app version", m_options.appVersion.empty() ? std::string("(unknown)") : m_options.appVersion);
    add("app channel", m_options.appChannel.empty() ? std::string("(none)") : m_options.appChannel);
    add("log format", std::to_string(kLogFormatVersion));
    add("exported at", LocalTimestamp());
    add("compiler", std::string(__DATE__) + " " + __TIME__);
    add("os", OsVersionText());
    add("arch", ArchText());
    return Redact(text);
}

std::string LogService::SettingsJson() const
{
    const LogRedactionOptions& redaction = m_options.redaction;
    std::vector<std::string> fields;
    fields.push_back("  \"logFormatVersion\": " + std::to_string(kLogFormatVersion));
    fields.push_back("  \"appVersion\": \"" + JsonEscape(m_options.appVersion) + "\"");
    fields.push_back("  \"appChannel\": \"" + JsonEscape(m_options.appChannel) + "\"");
    fields.push_back("  \"logDirectory\": \"" + JsonEscape(Directory()) + "\"");
    fields.push_back("  \"level\": \"" + std::string(LogLevelName(Level())) + "\"");
    fields.push_back("  \"maxFileBytes\": " + std::to_string(m_options.maxFileBytes));
    fields.push_back("  \"maxBackupFiles\": " + std::to_string(m_options.maxBackupFiles));
    fields.push_back("  \"flushEachLine\": " + std::string(m_options.flushEachLine ? "true" : "false"));
    fields.push_back("  \"echoToDebugger\": " + std::string(m_options.echoToDebugger ? "true" : "false"));

    std::vector<std::string> keys;
    for (const std::string& key : redaction.extraKeys) {
        keys.push_back("\"" + JsonEscape(Redact(key)) + "\"");
    }
    std::string keysJson = "[";
    for (size_t i = 0; i < keys.size(); ++i) {
        if (i) keysJson += ", ";
        keysJson += keys[i];
    }
    keysJson += "]";

    std::vector<std::string> paths;
    for (const auto& entry : redaction.extraPaths) {
        paths.push_back("[\"" + JsonEscape(Redact(entry.first)) + "\", \"" +
                        JsonEscape(Redact(entry.second)) + "\"]");
    }
    std::string pathsJson = "[";
    for (size_t i = 0; i < paths.size(); ++i) {
        if (i) pathsJson += ", ";
        pathsJson += paths[i];
    }
    pathsJson += "]";

    fields.push_back("  \"redaction\": {\n"
                     "    \"enabled\": " + std::string(redaction.enabled ? "true" : "false") + ",\n"
                     "    \"placeholder\": \"" + JsonEscape(redaction.placeholder) + "\",\n"
                     "    \"headers\": " + std::string(redaction.headers ? "true" : "false") + ",\n"
                     "    \"urlCredentials\": " + std::string(redaction.urlCredentials ? "true" : "false") + ",\n"
                     "    \"tokenShapes\": " + std::string(redaction.tokenShapes ? "true" : "false") + ",\n"
                     "    \"userPaths\": " + std::string(redaction.userPaths ? "true" : "false") + ",\n"
                     "    \"extraKeys\": " + keysJson + ",\n"
                     "    \"extraPaths\": " + pathsJson + "\n"
                     "  }");

    std::string json = "{\n";
    for (size_t i = 0; i < fields.size(); ++i) {
        if (i) json += ",\n";
        json += fields[i];
    }
    json += "\n}\n";
    return Redact(json);
}

// ── 诊断包导出 ───────────────────────────────────────────────────────
bool LogService::CopyLogsTo(const std::string& targetDir,
                            std::vector<std::string>& copied,
                            std::string* error)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_sink && m_sink->stream.is_open()) m_sink->stream.flush();
    const std::vector<std::string> sources = LogFilesLocked();
    for (const std::string& source : sources) {
        const std::string name = BaseName(source);
        const std::string destination = JoinPath(targetDir, name);
        std::error_code ec;
        fs::copy_file(ToFsPath(source), ToFsPath(destination),
                      fs::copy_options::overwrite_existing, ec);
        if (ec) {
            if (error) *error = "cannot copy " + source + " (" + ec.message() + ")";
            return false;
        }
        copied.push_back(name);
    }
    return true;
}

bool LogService::ExportDiagnostics(const std::string& targetDirectory,
                                   DiagnosticsPackage* out,
                                   std::string* error)
{
    Flush();

    std::string target = targetDirectory;
    if (target.empty()) {
        target = JoinPath(DefaultDiagnosticsDirectory(), "pb-diag-" + CompactTimestamp());
    }
    target = ResolveAbsolute(target);
    // 不覆盖已有目录：重名时加 -2、-3…
    {
        std::error_code ec;
        if (fs::exists(ToFsPath(target), ec)) {
            std::string candidate;
            for (int i = 2; i < 1000; ++i) {
                candidate = target + "-" + std::to_string(i);
                if (!fs::exists(ToFsPath(candidate), ec)) { target = candidate; break; }
            }
        }
    }
    std::error_code ec;
    fs::create_directories(ToFsPath(target), ec);
    if (!fs::is_directory(ToFsPath(target), ec)) {
        if (error) *error = "cannot create diagnostics directory: " + target;
        return false;
    }

    DiagnosticsPackage package;
    package.directory = target;

    std::string copyError;
    const bool logsOk = CopyLogsTo(target, package.files, &copyError);

    struct MetaFile {
        const char* name;
        std::string text;
    };
    std::vector<MetaFile> metaFiles;
    metaFiles.push_back({ "version.txt", VersionInfoText() });
    metaFiles.push_back({ "system.txt", SystemInfoText() });
    metaFiles.push_back({ "settings.json", SettingsJson() });
    metaFiles.push_back({ "README.txt", std::string(kReadmeText) });

    bool metaOk = true;
    std::string metaError;
    for (const MetaFile& meta : metaFiles) {
        const std::string path = JoinPath(target, meta.name);
        if (!WriteTextFileUtf8(path, meta.text)) {
            metaOk = false;
            if (metaError.empty()) metaError = "cannot write " + path;
        } else {
            package.files.push_back(meta.name);
        }
    }

    package.totalBytes = 0;
    for (const std::string& name : package.files) {
        package.totalBytes += FileSize(JoinPath(target, name));
    }
    if (out) *out = package;
    if (!logsOk || !metaOk) {
        if (error) *error = logsOk ? metaError : copyError;
        return false;
    }
    return true;
}

// ── 目录默认值 ───────────────────────────────────────────────────────
std::string LogService::DefaultLogDirectory()
{
    std::string base = GetEnvUtf8(L"LOCALAPPDATA");
    if (base.empty()) {
        const std::string profile = GetEnvUtf8(L"USERPROFILE");
        if (!profile.empty()) base = JoinPath(profile, "AppData\\Local");
    }
    if (base.empty()) return "logs";
    return JoinPath(JoinPath(base, "ParticleBook"), "logs");
}

std::string LogService::DefaultDiagnosticsDirectory()
{
    std::string base = GetEnvUtf8(L"LOCALAPPDATA");
    if (base.empty()) {
        const std::string profile = GetEnvUtf8(L"USERPROFILE");
        if (!profile.empty()) base = JoinPath(profile, "AppData\\Local");
    }
    if (base.empty()) return "diagnostics";
    return JoinPath(JoinPath(base, "ParticleBook"), "diagnostics");
}

// ── 进程级便利门面 ───────────────────────────────────────────────────
namespace {
std::mutex g_loggerMutex;
std::shared_ptr<LogService> g_logger;
} // namespace

void SetGlobalLogger(std::shared_ptr<LogService> logger) noexcept
{
    try {
        std::lock_guard<std::mutex> lock(g_loggerMutex);
        g_logger = std::move(logger);
    } catch (...) {
    }
}

std::shared_ptr<LogService> GetGlobalLogger() noexcept
{
    try {
        std::lock_guard<std::mutex> lock(g_loggerMutex);
        return g_logger;
    } catch (...) {
        return nullptr;
    }
}

void LogMessage(LogLevel level, const std::string& category, const std::string& message)
{
    std::shared_ptr<LogService> logger = GetGlobalLogger();
    if (logger) logger->Write(level, category, message);
}

void LogTrace(const std::string& category, const std::string& message)
{
    LogMessage(LogLevel::Trace, category, message);
}
void LogDebug(const std::string& category, const std::string& message)
{
    LogMessage(LogLevel::Debug, category, message);
}
void LogInfo(const std::string& category, const std::string& message)
{
    LogMessage(LogLevel::Info, category, message);
}
void LogWarn(const std::string& category, const std::string& message)
{
    LogMessage(LogLevel::Warn, category, message);
}
void LogError(const std::string& category, const std::string& message)
{
    LogMessage(LogLevel::Error, category, message);
}
void LogCritical(const std::string& category, const std::string& message)
{
    LogMessage(LogLevel::Critical, category, message);
}

} // namespace pb
