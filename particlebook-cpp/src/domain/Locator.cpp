// ── PB-021（A）：Locator 实现 ────────────────────────────────────────
// 见 domain/Locator.h 的契约说明。本文件只依赖 std + nlohmann/json，
// 单测直接链接 Locator.cpp 即可（与 SettingsService / JobService 同一测试缝）。
//
// 组成：
//   1. kind / 版本 / rect 的基础校验与命名；
//   2. JSON 读写（稳定输出、失败走 LocatorResult，绝不抛异常）；
//   3. 相等 / 全序 / 可信度分级（CompareLocator / SamePosition / GetStability）；
//   4. 旧 DB 行迁移（LocatorFromLegacyPosition / LocatorFromLegacyProgress）。
//
// 两个容易踩的旧数据语义：
//   * 旧 TextRenderer 把「百分比」写进 highlight.page，并把字符偏移写成
//     cfi = "pos-<offset>"；所以 page 不能一律当页码，cfi 也要先认 pos- 前缀。
//   * 旧 HtmlRenderer 的 cfi 实际是元素 id，迁移时按 format=Html 映射到 href。

#include "domain/Locator.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <string>
#include <utility>

namespace pb {
namespace {

constexpr double kRectEpsilon = 1e-6;
constexpr double kNumEpsilon = 1e-9;

// ── 小工具 ───────────────────────────────────────────────────────────

bool IsAsciiSpace(char c)
{
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\v';
}

std::string Trim(const std::string& text)
{
    std::size_t begin = 0;
    std::size_t end = text.size();
    while (begin < end && IsAsciiSpace(text[begin])) ++begin;
    while (end > begin && IsAsciiSpace(text[end - 1])) --end;
    return text.substr(begin, end - begin);
}

std::string LowerAscii(std::string text)
{
    for (char& c : text) {
        if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
    }
    return text;
}

bool StartsWith(const std::string& text, const char* prefix)
{
    const std::string p(prefix);
    return text.size() >= p.size() && text.compare(0, p.size(), p) == 0;
}

// 稳定的 double → 字符串（去重 key 用；22 位足够往返，又不会把 0.1 写成
// 0.10000000000000001 这种噪音——这里要的是「同一位置同一个 key」）。
std::string FormatNumber(double value)
{
    char buffer[40];
    std::snprintf(buffer, sizeof buffer, "%.12g", value);
    return std::string(buffer);
}

// "123"（可带正号，不含空白）→ true。旧 cfi 里的 pos-<offset> 用它解析。
bool ParseNonNegativeInt(const std::string& text, long long& out)
{
    if (text.empty()) return false;
    std::size_t i = 0;
    if (text[0] == '+') i = 1;
    if (i >= text.size()) return false;
    long long value = 0;
    for (; i < text.size(); ++i) {
        const char c = text[i];
        if (c < '0' || c > '9') return false;
        if (value > (std::numeric_limits<long long>::max() - (c - '0')) / 10) return false;
        value = value * 10 + (c - '0');
    }
    out = value;
    return true;
}

bool WithinUnit(double value)
{
    return std::isfinite(value) && value >= -kRectEpsilon && value <= 1.0 + kRectEpsilon;
}

bool RectInsideUnit(const NormalizedRect& rect)
{
    return WithinUnit(rect.x) && WithinUnit(rect.y) && WithinUnit(rect.w) && WithinUnit(rect.h)
        && rect.x + rect.w <= 1.0 + kRectEpsilon
        && rect.y + rect.h <= 1.0 + kRectEpsilon;
}

LocatorIssue MakeIssue(std::string field, std::string code, std::string message,
                       LocatorSeverity severity)
{
    LocatorIssue issue;
    issue.field = std::move(field);
    issue.code = std::move(code);
    issue.message = std::move(message);
    issue.severity = severity;
    return issue;
}

// ── JSON 取值（类型不对只报一次，不抛）──────────────────────────────

bool ReadString(const json& value, const char* key, std::string& out,
                std::vector<LocatorIssue>& issues)
{
    auto it = value.find(key);
    if (it == value.end() || it->is_null()) return false;
    if (!it->is_string()) {
        issues.push_back(MakeIssue(key, "type", "\"" + std::string(key) + "\" must be a string",
                                   LocatorSeverity::Error));
        return false;
    }
    out = it->get<std::string>();
    return true;
}

bool IsIntegerJson(const json& value)
{
    return value.is_number_integer() || value.is_number_unsigned();
}

// json 的整数可能是 unsigned 且超过 long long：get<long long> 会抛，这里饱和。
long long SafeInt(const json& value)
{
    if (value.is_number_unsigned()) {
        const unsigned long long raw = value.get<unsigned long long>();
        if (raw > static_cast<unsigned long long>(std::numeric_limits<long long>::max())) {
            return std::numeric_limits<long long>::max();
        }
        return static_cast<long long>(raw);
    }
    return value.get<long long>();
}

int ToIntClamped(long long value)
{
    if (value < std::numeric_limits<int>::min()) return std::numeric_limits<int>::min();
    if (value > std::numeric_limits<int>::max()) return std::numeric_limits<int>::max();
    return static_cast<int>(value);
}

bool ReadInteger(const json& parent, const char* key, long long& out,
                 std::vector<LocatorIssue>& issues)
{
    auto it = parent.find(key);
    if (it == parent.end() || it->is_null()) return false;
    if (!IsIntegerJson(*it)) {
        issues.push_back(MakeIssue(key, "type", "\"" + std::string(key) + "\" must be an integer",
                                   LocatorSeverity::Error));
        return false;
    }
    out = SafeInt(*it);
    return true;
}

bool ReadNumber(const json& parent, const char* key, double& out,
                std::vector<LocatorIssue>& issues)
{
    auto it = parent.find(key);
    if (it == parent.end() || it->is_null()) return false;
    if (!it->is_number()) {
        issues.push_back(MakeIssue(key, "type", "\"" + std::string(key) + "\" must be a number",
                                   LocatorSeverity::Error));
        return false;
    }
    out = it->get<double>();
    return true;
}

// 归一化矩形：接受 {"x","y","w","h"} 或 [x,y,w,h]。返回 false = 结构不认识。
bool ReadNormalizedRect(const json& value, NormalizedRect& out)
{
    if (value.is_object()) {
        const char* keys[4] = { "x", "y", "w", "h" };
        double* targets[4] = { &out.x, &out.y, &out.w, &out.h };
        for (int i = 0; i < 4; ++i) {
            auto it = value.find(keys[i]);
            if (it == value.end() || !it->is_number()) return false;
            *targets[i] = it->get<double>();
        }
        return true;
    }
    if (value.is_array() && value.size() == 4) {
        for (std::size_t i = 0; i < 4; ++i) {
            if (!value[i].is_number()) return false;
        }
        out.x = value[0].get<double>();
        out.y = value[1].get<double>();
        out.w = value[2].get<double>();
        out.h = value[3].get<double>();
        return true;
    }
    return false;
}

// 旧行的数值字段：page / progress / scroll_position 等，可能是整数或浮点。
bool ReadLegacyNumber(const json& row, const char* key, double& out)
{
    auto it = row.find(key);
    if (it == row.end() || it->is_null() || !it->is_number()) return false;
    out = it->get<double>();
    return true;
}

bool ReadLegacyString(const json& row, const char* key, std::string& out)
{
    auto it = row.find(key);
    if (it == row.end() || it->is_null() || !it->is_string()) return false;
    out = it->get<std::string>();
    return true;
}

bool IsIntegerValue(double value)
{
    return std::isfinite(value) && std::fabs(value - std::floor(value)) < 1e-9;
}

} // namespace

// ── kind ─────────────────────────────────────────────────────────────

const char* LocatorKindName(LocatorKind kind) noexcept
{
    switch (kind) {
    case LocatorKind::Epub:   return "epub";
    case LocatorKind::Text:   return "text";
    case LocatorKind::Html:   return "html";
    case LocatorKind::Pdf:    return "pdf";
    case LocatorKind::Scroll: return "scroll";
    case LocatorKind::Unknown:
    default:                  return "unknown";
    }
}

bool ParseLocatorKind(const std::string& text, LocatorKind& out) noexcept
{
    const std::string lowered = LowerAscii(text);
    if (lowered == "epub")   { out = LocatorKind::Epub;   return true; }
    if (lowered == "text")   { out = LocatorKind::Text;   return true; }
    if (lowered == "html")   { out = LocatorKind::Html;   return true; }
    if (lowered == "pdf")    { out = LocatorKind::Pdf;    return true; }
    if (lowered == "scroll") { out = LocatorKind::Scroll; return true; }
    return false;
}

int LocatorKindRank(LocatorKind kind) noexcept
{
    switch (kind) {
    case LocatorKind::Epub:   return 1;
    case LocatorKind::Text:   return 2;
    case LocatorKind::Html:   return 3;
    case LocatorKind::Pdf:    return 4;
    case LocatorKind::Scroll: return 5;
    case LocatorKind::Unknown:
    default:                  return 6;
    }
}

// ── rect / 浮点 ──────────────────────────────────────────────────────

bool NearlyEqual(double a, double b) noexcept
{
    if (a == b) return true;  // 含 +0 / -0
    if (!std::isfinite(a) || !std::isfinite(b)) return false;
    return std::fabs(a - b) <= kNumEpsilon * (1.0 + std::fabs(a) + std::fabs(b));
}

bool NormalizedRect::operator==(const NormalizedRect& other) const noexcept
{
    return NearlyEqual(x, other.x) && NearlyEqual(y, other.y)
        && NearlyEqual(w, other.w) && NearlyEqual(h, other.h);
}

// ── Locator ──────────────────────────────────────────────────────────

bool Locator::IsEmpty() const noexcept
{
    switch (kind) {
    case LocatorKind::Epub:   return cfi.empty();
    case LocatorKind::Text:   return offset < 0;
    case LocatorKind::Html:   return href.empty();
    case LocatorKind::Pdf:    return page < 1;
    case LocatorKind::Scroll: return percent < 0.0 || !std::isfinite(percent);
    case LocatorKind::Unknown:
    default:                  return true;
    }
}

bool Locator::HasQuote() const noexcept
{
    return !quote.empty() || !contextBefore.empty() || !contextAfter.empty();
}

std::string Locator::AnchorKey() const
{
    switch (kind) {
    case LocatorKind::Epub:
        return std::string("epub:") + cfi;
    case LocatorKind::Text:
        return "text:" + std::to_string(offset) + ":" + std::to_string(length);
    case LocatorKind::Html:
        return "html:" + href + ":" + std::to_string(offset);
    case LocatorKind::Pdf: {
        std::string key = "pdf:" + std::to_string(page);
        if (hasRect && !rect.IsEmpty()) {
            key += ":" + FormatNumber(rect.x) + "," + FormatNumber(rect.y)
                 + "," + FormatNumber(rect.w) + "," + FormatNumber(rect.h);
        }
        return key;
    }
    case LocatorKind::Scroll:
        return "scroll:" + FormatNumber(percent);
    case LocatorKind::Unknown:
    default:
        return "unknown";
    }
}

bool Locator::operator==(const Locator& other) const noexcept
{
    return version == other.version
        && kind == other.kind
        && cfi == other.cfi
        && href == other.href
        && offset == other.offset
        && length == other.length
        && page == other.page
        && hasRect == other.hasRect
        && rect == other.rect
        && NearlyEqual(percent, other.percent)
        && quote == other.quote
        && contextBefore == other.contextBefore
        && contextAfter == other.contextAfter;
}

// ── 构造 ─────────────────────────────────────────────────────────────

Locator MakeEpubLocator(std::string cfi)
{
    Locator locator;
    locator.kind = LocatorKind::Epub;
    locator.cfi = std::move(cfi);
    return locator;
}

Locator MakeTextLocator(long long offset, long long length)
{
    Locator locator;
    locator.kind = LocatorKind::Text;
    locator.offset = offset;
    locator.length = length;
    return locator;
}

Locator MakeHtmlLocator(std::string href, long long offset)
{
    Locator locator;
    locator.kind = LocatorKind::Html;
    locator.href = std::move(href);
    locator.offset = offset;
    return locator;
}

Locator MakePdfLocator(int page, const NormalizedRect* rect)
{
    Locator locator;
    locator.kind = LocatorKind::Pdf;
    locator.page = page;
    if (rect) {
        locator.hasRect = true;
        locator.rect = *rect;
    }
    return locator;
}

Locator MakeScrollLocator(double percent)
{
    Locator locator;
    locator.kind = LocatorKind::Scroll;
    locator.percent = percent;
    return locator;
}

// ── 校验 / 规范化 ────────────────────────────────────────────────────

std::size_t CountErrors(const std::vector<LocatorIssue>& issues) noexcept
{
    std::size_t count = 0;
    for (const LocatorIssue& issue : issues) {
        if (issue.severity == LocatorSeverity::Error) ++count;
    }
    return count;
}

bool LocatorResult::HasErrors() const noexcept
{
    return CountErrors(issues) > 0;
}

std::vector<LocatorIssue> ValidateLocator(const Locator& locator)
{
    std::vector<LocatorIssue> issues;

    if (locator.version > kLocatorVersion) {
        issues.push_back(MakeIssue("version", "version",
                                   "locator version is newer than this build",
                                   LocatorSeverity::Error));
    } else if (locator.version != kLocatorVersion) {
        issues.push_back(MakeIssue("version", "version",
                                   "locator version is not supported", LocatorSeverity::Error));
    }

    if (locator.kind == LocatorKind::Unknown) {
        issues.push_back(MakeIssue("kind", "kind", "missing or unsupported locator kind",
                                   LocatorSeverity::Error));
        return issues;
    }

    switch (locator.kind) {
    case LocatorKind::Epub:
        if (Trim(locator.cfi).empty()) {
            issues.push_back(MakeIssue("cfi", "cfi", "epub locator requires a cfi",
                                       LocatorSeverity::Error));
        }
        break;
    case LocatorKind::Text:
        if (locator.offset < 0) {
            issues.push_back(MakeIssue("offset", "offset",
                                       "text locator requires a non-negative offset",
                                       LocatorSeverity::Error));
        }
        if (locator.length < -1) {
            issues.push_back(MakeIssue("length", "length",
                                       "text locator length must be -1 or >= 0",
                                       LocatorSeverity::Error));
        }
        break;
    case LocatorKind::Html:
        if (Trim(locator.href).empty()) {
            issues.push_back(MakeIssue("href", "href", "html locator requires an href",
                                       LocatorSeverity::Error));
        }
        if (locator.offset < -1) {
            issues.push_back(MakeIssue("offset", "offset",
                                       "html locator offset must be -1 or >= 0",
                                       LocatorSeverity::Error));
        }
        break;
    case LocatorKind::Pdf:
        if (locator.page < 1) {
            issues.push_back(MakeIssue("page", "page", "pdf locator requires a 1-based page",
                                       LocatorSeverity::Error));
        }
        if (locator.hasRect) {
            if (locator.rect.IsEmpty()) {
                issues.push_back(MakeIssue("rect", "rect",
                                           "pdf rect requires positive width and height",
                                           LocatorSeverity::Error));
            } else if (!RectInsideUnit(locator.rect)) {
                issues.push_back(MakeIssue("rect", "rect",
                                           "pdf rect must be normalized to [0,1]",
                                           LocatorSeverity::Error));
            }
        }
        break;
    case LocatorKind::Scroll:
        if (!std::isfinite(locator.percent) || locator.percent < 0.0
            || locator.percent > 100.0) {
            issues.push_back(MakeIssue("percent", "percent",
                                       "scroll locator requires percent in [0,100]",
                                       LocatorSeverity::Error));
        }
        break;
    case LocatorKind::Unknown:
    default:
        break;
    }

    // rect 只属于 pdf；其他 kind 带着它通常意味着迁移代码放错了字段。
    if (locator.kind != LocatorKind::Pdf && locator.hasRect) {
        issues.push_back(MakeIssue("rect", "rect", "rect is ignored for non-pdf locators",
                                   LocatorSeverity::Warning));
    }

    return issues;
}

bool IsValidLocator(const Locator& locator)
{
    return CountErrors(ValidateLocator(locator)) == 0;
}

LocatorResult NormalizeLocator(const Locator& locator)
{
    LocatorResult result;
    result.locator = locator;
    Locator& out = result.locator;

    if (out.version > kLocatorVersion) {
        result.issues.push_back(MakeIssue("version", "version",
                                          "locator version is newer than this build",
                                          LocatorSeverity::Error));
    } else if (out.version != kLocatorVersion) {
        result.issues.push_back(MakeIssue("version", "version-migrated",
                                          "locator version migrated to current",
                                          LocatorSeverity::Warning));
        out.version = kLocatorVersion;
    }

    if (out.kind == LocatorKind::Unknown) {
        // 不猜 kind：猜错比报错更糟。
        result.issues.push_back(MakeIssue("kind", "kind", "missing or unsupported locator kind",
                                          LocatorSeverity::Error));
        result.ok = false;
        return result;
    }

    // 去首尾空白：CFI / href / 选区文本都可能从 DOM 或旧行里带进来空白。
    // Trim 会忽略前导 / 尾随空白，不改变中间内容。
    if (Trim(out.cfi) != out.cfi) {
        out.cfi = Trim(out.cfi);
        result.issues.push_back(MakeIssue("cfi", "trimmed", "cfi trimmed", LocatorSeverity::Warning));
    }
    if (Trim(out.href) != out.href) {
        out.href = Trim(out.href);
        result.issues.push_back(
            MakeIssue("href", "trimmed", "href trimmed", LocatorSeverity::Warning));
    }
    if (Trim(out.quote) != out.quote) {
        out.quote = Trim(out.quote);
        result.issues.push_back(
            MakeIssue("quote", "trimmed", "quote trimmed", LocatorSeverity::Warning));
    }
    if (Trim(out.contextBefore) != out.contextBefore) {
        out.contextBefore = Trim(out.contextBefore);
        result.issues.push_back(MakeIssue("contextBefore", "trimmed", "contextBefore trimmed",
                                          LocatorSeverity::Warning));
    }
    if (Trim(out.contextAfter) != out.contextAfter) {
        out.contextAfter = Trim(out.contextAfter);
        result.issues.push_back(MakeIssue("contextAfter", "trimmed", "contextAfter trimmed",
                                          LocatorSeverity::Warning));
    }

    // 旧约定：length = 0 与 -1 都表示「只定位到点」，统一成 -1。
    if (out.kind == LocatorKind::Text && out.length == 0) {
        out.length = -1;
        result.issues.push_back(MakeIssue("length", "length", "length 0 canonicalized to -1",
                                          LocatorSeverity::Warning));
    }

    // rect：只对 pdf 有意义；空矩形或越界矩形丢掉（保留页码这个粗锚点），
    // 而不是夹紧——夹紧会悄悄把标注挪到页面边缘。
    if (out.hasRect && out.kind != LocatorKind::Pdf) {
        out.hasRect = false;
        out.rect = NormalizedRect{};
        result.issues.push_back(MakeIssue("rect", "rect", "rect dropped for non-pdf locator",
                                          LocatorSeverity::Warning));
    }
    if (out.hasRect) {
        if (out.rect.IsEmpty()) {
            out.hasRect = false;
            out.rect = NormalizedRect{};
            result.issues.push_back(MakeIssue("rect", "rect", "empty rect dropped",
                                              LocatorSeverity::Warning));
        } else if (!RectInsideUnit(out.rect)) {
            out.hasRect = false;
            out.rect = NormalizedRect{};
            result.issues.push_back(MakeIssue("rect", "rect",
                                              "rect outside [0,1] dropped",
                                              LocatorSeverity::Warning));
        }
    }

    const std::vector<LocatorIssue> validation = ValidateLocator(out);
    result.issues.insert(result.issues.end(), validation.begin(), validation.end());
    result.ok = CountErrors(result.issues) == 0;
    return result;
}

// ── JSON ─────────────────────────────────────────────────────────────

json ToJson(const Locator& locator)
{
    json value = json::object();
    value["version"] = locator.version > 0 ? locator.version : kLocatorVersion;
    value["kind"] = LocatorKindName(locator.kind);

    switch (locator.kind) {
    case LocatorKind::Epub:
        if (!locator.cfi.empty()) value["cfi"] = locator.cfi;
        break;
    case LocatorKind::Text:
        if (locator.offset >= 0) value["offset"] = locator.offset;
        if (locator.length > 0) value["length"] = locator.length;
        break;
    case LocatorKind::Html:
        if (!locator.href.empty()) value["href"] = locator.href;
        if (locator.offset >= 0) value["offset"] = locator.offset;
        break;
    case LocatorKind::Pdf:
        if (locator.page >= 1) value["page"] = locator.page;
        if (locator.hasRect && !locator.rect.IsEmpty()) {
            value["rect"] = json{ { "x", locator.rect.x },
                                  { "y", locator.rect.y },
                                  { "w", locator.rect.w },
                                  { "h", locator.rect.h } };
        }
        break;
    case LocatorKind::Scroll:
        if (locator.percent >= 0.0) value["percent"] = locator.percent;
        break;
    case LocatorKind::Unknown:
    default:
        break;
    }

    if (!locator.quote.empty()) value["quote"] = locator.quote;
    if (!locator.contextBefore.empty()) value["contextBefore"] = locator.contextBefore;
    if (!locator.contextAfter.empty()) value["contextAfter"] = locator.contextAfter;
    return value;
}

std::string SerializeLocator(const Locator& locator)
{
    return ToJson(locator).dump();
}

LocatorResult LocatorFromJson(const json& value)
{
    LocatorResult result;

    if (!value.is_object()) {
        result.issues.push_back(MakeIssue("root", "root", "locator must be a json object",
                                          LocatorSeverity::Error));
        return result;
    }

    Locator& out = result.locator;

    long long version = 0;
    bool hasVersion = false;
    if (value.contains("version")) {
        hasVersion = true;
        if (!IsIntegerJson(value["version"])) {
            result.issues.push_back(MakeIssue("version", "type", "\"version\" must be an integer",
                                              LocatorSeverity::Error));
            return result;
        }
        version = SafeInt(value["version"]);
    }
    if (!hasVersion) {
        result.issues.push_back(MakeIssue("version", "version-missing",
                                          "locator has no version, assuming 1",
                                          LocatorSeverity::Warning));
        out.version = kLocatorVersion;
    } else if (version > kLocatorVersion) {
        result.issues.push_back(MakeIssue("version", "version",
                                          "locator version is newer than this build",
                                          LocatorSeverity::Error));
        out.version = static_cast<int>(version);
        return result;
    } else if (version <= 0) {
        result.issues.push_back(MakeIssue("version", "version", "locator version must be >= 1",
                                          LocatorSeverity::Error));
        return result;
    } else {
        if (version != kLocatorVersion) {
            result.issues.push_back(MakeIssue("version", "version-migrated",
                                              "locator version migrated to current",
                                              LocatorSeverity::Warning));
        }
        out.version = kLocatorVersion;
    }

    std::string kindName;
    if (!ReadString(value, "kind", kindName, result.issues)) {
        if (!value.contains("kind")) {
            result.issues.push_back(MakeIssue("kind", "kind", "locator requires a kind",
                                              LocatorSeverity::Error));
        }
        result.locator.kind = LocatorKind::Unknown;
        result.ok = false;
        return result;
    }
    LocatorKind kind = LocatorKind::Unknown;
    if (!ParseLocatorKind(kindName, kind)) {
        result.issues.push_back(MakeIssue("kind", "kind", "unsupported locator kind",
                                          LocatorSeverity::Error));
        result.locator.kind = LocatorKind::Unknown;
        result.ok = false;
        return result;
    }
    out.kind = kind;

    // kind 专属字段：只读当前 kind 需要的键，多余的键忽略（向前兼容留给版本号）。
    switch (kind) {
    case LocatorKind::Epub: {
        std::string cfi;
        if (ReadString(value, "cfi", cfi, result.issues)) out.cfi = Trim(cfi);
        break;
    }
    case LocatorKind::Text: {
        long long offset = -1;
        if (ReadInteger(value, "offset", offset, result.issues)) out.offset = offset;
        long long length = -1;
        if (ReadInteger(value, "length", length, result.issues)) {
            if (length < 0) {
                result.issues.push_back(MakeIssue("length", "length",
                                                  "length must be >= 0",
                                                  LocatorSeverity::Error));
            } else {
                out.length = length == 0 ? -1 : length;
            }
        }
        break;
    }
    case LocatorKind::Html: {
        std::string href;
        if (ReadString(value, "href", href, result.issues)) out.href = Trim(href);
        long long offset = -1;
        if (ReadInteger(value, "offset", offset, result.issues)) out.offset = offset;
        break;
    }
    case LocatorKind::Pdf: {
        long long page = -1;
        if (ReadInteger(value, "page", page, result.issues)) out.page = ToIntClamped(page);
        auto rectIt = value.find("rect");
        if (rectIt != value.end()) {
            NormalizedRect rect;
            if (ReadNormalizedRect(*rectIt, rect)) {
                out.hasRect = true;
                out.rect = rect;
            } else {
                result.issues.push_back(MakeIssue("rect", "rect",
                                                  "rect must be {x,y,w,h} or [x,y,w,h]",
                                                  LocatorSeverity::Warning));
            }
        }
        break;
    }
    case LocatorKind::Scroll: {
        double percent = -1.0;
        if (ReadNumber(value, "percent", percent, result.issues)) out.percent = percent;
        break;
    }
    case LocatorKind::Unknown:
    default:
        break;
    }

    std::string quote;
    if (ReadString(value, "quote", quote, result.issues)) out.quote = Trim(quote);
    std::string contextBefore;
    if (ReadString(value, "contextBefore", contextBefore, result.issues)) {
        out.contextBefore = Trim(contextBefore);
    }
    std::string contextAfter;
    if (ReadString(value, "contextAfter", contextAfter, result.issues)) {
        out.contextAfter = Trim(contextAfter);
    }

    const std::vector<LocatorIssue> validation = ValidateLocator(out);
    result.issues.insert(result.issues.end(), validation.begin(), validation.end());
    result.ok = CountErrors(result.issues) == 0;
    return result;
}

LocatorResult ParseLocator(const std::string& text)
{
    const json value = json::parse(text, nullptr, /*allow_exceptions=*/false);
    if (value.is_discarded()) {
        LocatorResult result;
        result.issues.push_back(MakeIssue("root", "parse", "locator text is not valid json",
                                          LocatorSeverity::Error));
        return result;
    }
    return LocatorFromJson(value);
}

// ── 相等 / 排序 / 稳定性 ─────────────────────────────────────────────

bool SamePosition(const Locator& a, const Locator& b)
{
    return CompareLocator(a, b) == 0;
}

int CompareLocator(const Locator& a, const Locator& b)
{
    const int rankA = LocatorKindRank(a.kind);
    const int rankB = LocatorKindRank(b.kind);
    if (rankA != rankB) return rankA < rankB ? -1 : 1;

    auto cmp = [](auto left, auto right) { return left < right ? -1 : (right < left ? 1 : 0); };

    switch (a.kind) {
    case LocatorKind::Epub:
        return cmp(a.cfi, b.cfi);
    case LocatorKind::Text:
        if (a.offset != b.offset) return cmp(a.offset, b.offset);
        return cmp(a.length, b.length);
    case LocatorKind::Html:
        if (a.href != b.href) return cmp(a.href, b.href);
        return cmp(a.offset, b.offset);
    case LocatorKind::Pdf: {
        if (a.page != b.page) return cmp(a.page, b.page);
        if (a.hasRect != b.hasRect) return a.hasRect ? 1 : -1;
        if (!a.hasRect) return 0;
        if (!NearlyEqual(a.rect.x, b.rect.x)) return cmp(a.rect.x, b.rect.x);
        if (!NearlyEqual(a.rect.y, b.rect.y)) return cmp(a.rect.y, b.rect.y);
        if (!NearlyEqual(a.rect.w, b.rect.w)) return cmp(a.rect.w, b.rect.w);
        if (!NearlyEqual(a.rect.h, b.rect.h)) return cmp(a.rect.h, b.rect.h);
        return 0;
    }
    case LocatorKind::Scroll:
        if (!NearlyEqual(a.percent, b.percent)) return cmp(a.percent, b.percent);
        return 0;
    case LocatorKind::Unknown:
    default:
        return 0;
    }
}

const char* LocatorStabilityName(LocatorStability stability) noexcept
{
    switch (stability) {
    case LocatorStability::Weak:   return "weak";
    case LocatorStability::Medium: return "medium";
    case LocatorStability::Strong: return "strong";
    case LocatorStability::None:
    default:                       return "none";
    }
}

LocatorStability GetStability(const Locator& locator)
{
    if (!IsValidLocator(locator) || locator.IsEmpty()) return LocatorStability::None;

    switch (locator.kind) {
    case LocatorKind::Epub:
        return locator.HasQuote() ? LocatorStability::Strong : LocatorStability::Medium;
    case LocatorKind::Text:
        return locator.HasQuote() ? LocatorStability::Strong : LocatorStability::Medium;
    case LocatorKind::Html:
        return (locator.HasQuote() && locator.offset >= 0) ? LocatorStability::Strong
                                                           : LocatorStability::Medium;
    case LocatorKind::Pdf:
        return locator.hasRect ? LocatorStability::Strong : LocatorStability::Medium;
    case LocatorKind::Scroll:
        return locator.HasQuote() ? LocatorStability::Medium : LocatorStability::Weak;
    case LocatorKind::Unknown:
    default:
        return LocatorStability::None;
    }
}

bool IsStable(const Locator& locator)
{
    return static_cast<int>(GetStability(locator))
        >= static_cast<int>(LocatorStability::Medium);
}

int CompareStability(const Locator& a, const Locator& b)
{
    const int rankA = static_cast<int>(GetStability(a));
    const int rankB = static_cast<int>(GetStability(b));
    return rankA < rankB ? -1 : (rankA > rankB ? 1 : 0);
}

// ── 旧数据兼容 ───────────────────────────────────────────────────────

const char* LegacyBookFormatName(LegacyBookFormat format) noexcept
{
    switch (format) {
    case LegacyBookFormat::Epub: return "epub";
    case LegacyBookFormat::Text: return "text";
    case LegacyBookFormat::Html: return "html";
    case LegacyBookFormat::Pdf:  return "pdf";
    case LegacyBookFormat::Unknown:
    default:                     return "unknown";
    }
}

namespace {

void AddLegacyIssue(std::vector<LocatorIssue>* issues, const char* field, const char* code,
                    const char* message, LocatorSeverity severity)
{
    if (!issues) return;
    issues->push_back(MakeIssue(field, code, message, severity));
}

// cfi 里的旧约定 "pos-<offset>"（TextRenderer）→ 返回 true 并写 offset。
bool ParseLegacyPosOffset(const std::string& cfi, long long& offset)
{
    if (!StartsWith(cfi, "pos-") && !StartsWith(cfi, "pos_")) return false;
    std::string digits = cfi.substr(4);
    return ParseNonNegativeInt(Trim(digits), offset);
}

// 把旧行的 rect 字段挂到 pdf locator 上；坏 rect 只 Warn 并丢弃。
void ApplyLegacyRect(const json& row, Locator& locator, std::vector<LocatorIssue>* issues)
{
    auto it = row.find("rect");
    if (it == row.end()) return;

    NormalizedRect rect;
    if (!ReadNormalizedRect(*it, rect)) {
        AddLegacyIssue(issues, "rect", "legacy-rect", "legacy rect is not {x,y,w,h} or [x,y,w,h]",
                       LocatorSeverity::Warning);
        return;
    }
    if (locator.kind != LocatorKind::Pdf) {
        AddLegacyIssue(issues, "rect", "legacy-rect", "legacy rect ignored for non-pdf position",
                       LocatorSeverity::Warning);
        return;
    }
    if (rect.IsEmpty() || !RectInsideUnit(rect)) {
        AddLegacyIssue(issues, "rect", "legacy-rect",
                       "legacy rect is empty or outside [0,1], dropped",
                       LocatorSeverity::Warning);
        return;
    }
    locator.hasRect = true;
    locator.rect = rect;
}

Locator FromLegacyRow(const json& row, LegacyBookFormat format,
                      std::vector<LocatorIssue>* issues)
{
    Locator locator;
    if (!row.is_object()) {
        AddLegacyIssue(issues, "root", "legacy-root", "legacy row is not a json object",
                       LocatorSeverity::Warning);
        return locator;
    }

    bool resolved = false;

    // 1) cfi：pos-<offset> → Text；否则按格式是 Epub CFI 或 Html 元素 id。
    std::string cfi;
    if (ReadLegacyString(row, "cfi", cfi)) {
        cfi = Trim(cfi);
        long long posOffset = 0;
        if (ParseLegacyPosOffset(cfi, posOffset)) {
            locator = MakeTextLocator(posOffset);
            resolved = true;
        } else if (!cfi.empty()) {
            if (format == LegacyBookFormat::Html) {
                locator = MakeHtmlLocator(cfi);
                resolved = true;
            } else if (format == LegacyBookFormat::Text) {
                // TXT 行里出现非 pos- 的 cfi 说明旧数据写错了字段，不猜。
                AddLegacyIssue(issues, "cfi", "legacy-cfi",
                               "text row has a non pos- cfi, ignored", LocatorSeverity::Warning);
            } else {
                locator = MakeEpubLocator(cfi);
                resolved = true;
            }
        }
    }

    // 2) 显式 offset（任务里提到的旧字段；没有 cfi 时才用）。
    if (!resolved) {
        long long offset = -1;
        auto it = row.find("offset");
        if (it != row.end() && IsIntegerJson(*it) && SafeInt(*it) >= 0) {
            offset = SafeInt(*it);
            locator = MakeTextLocator(offset);
            resolved = true;
        }
    }

    // 3) page：格式决定它到底是页码还是百分比。
    //    page 在旧数据里同时是「未定位」哨兵（readerStore 里明确排除 0），
    //    所以 0 / 负数一律当没有，继续往滚动兜底走。
    if (!resolved) {
        double page = 0.0;
        if (ReadLegacyNumber(row, "page", page) && std::isfinite(page) && page > 0.0) {
            switch (format) {
            case LegacyBookFormat::Pdf:
                if (!IsIntegerValue(page)) {
                    AddLegacyIssue(issues, "page", "legacy-page",
                                   "legacy page is fractional, rounded", LocatorSeverity::Warning);
                }
                locator = MakePdfLocator(static_cast<int>(std::lround(page)));
                resolved = true;
                break;
            case LegacyBookFormat::Text:
            case LegacyBookFormat::Html:
                if (page <= 100.0) {
                    locator = MakeScrollLocator(page);
                } else {
                    AddLegacyIssue(issues, "page", "legacy-page",
                                   "legacy percent page clamped to 100", LocatorSeverity::Warning);
                    locator = MakeScrollLocator(100.0);
                }
                resolved = true;
                break;
            case LegacyBookFormat::Epub:
                AddLegacyIssue(issues, "page", "legacy-page",
                               "epub row has a page but no cfi, page ignored",
                               LocatorSeverity::Warning);
                break;
            case LegacyBookFormat::Unknown:
            default:
                // 旧数据里 page 的语义按格式而变：整数且 >= 1 最可能是页码，
                // 否则按百分比处理；两种都只是猜测，给出 Warning 让迁移层复核。
                if (IsIntegerValue(page) && page >= 1.0) {
                    AddLegacyIssue(issues, "page", "legacy-page",
                                   "legacy page without format, assumed pdf page",
                                   LocatorSeverity::Warning);
                    locator = MakePdfLocator(static_cast<int>(std::lround(page)));
                } else {
                    AddLegacyIssue(issues, "page", "legacy-page",
                                   "legacy page without format, assumed percent",
                                   LocatorSeverity::Warning);
                    locator = MakeScrollLocator(page);
                }
                resolved = !locator.IsEmpty();
                break;
            }
        }
    }

    // 4) 滚动兜底：progress（0..100 百分比）/ scrollPosition（像素）。
    //    像素本身换不了百分比，只有同一行的 progress 才是语义锚点。
    if (!resolved) {
        double progress = 0.0;
        if (ReadLegacyNumber(row, "progress", progress) && progress >= 0.0 && progress <= 100.0) {
            locator = MakeScrollLocator(progress);
            resolved = true;
        } else {
            double scroll = 0.0;
            const bool hasScroll = ReadLegacyNumber(row, "scrollPosition", scroll)
                || ReadLegacyNumber(row, "scroll_position", scroll);
            if (hasScroll) {
                AddLegacyIssue(issues, "scrollPosition", "legacy-scroll",
                               "scroll offset without progress percent, recorded as 0%",
                               LocatorSeverity::Warning);
                locator = MakeScrollLocator(0.0);
                resolved = true;
            }
        }
    }

    if (!resolved) {
        AddLegacyIssue(issues, "position", "legacy-position",
                       "legacy row has no recognizable position", LocatorSeverity::Warning);
        return locator;
    }

    if (locator.kind == LocatorKind::Pdf) ApplyLegacyRect(row, locator, issues);

    // 选区文本 → quote（重定位线索）。
    std::string text;
    if (ReadLegacyString(row, "text", text)) {
        locator.quote = Trim(text);
    }

    if (!IsValidLocator(locator)) {
        AddLegacyIssue(issues, "position", "legacy-position", "legacy position is not valid",
                       LocatorSeverity::Warning);
    }
    return locator;
}

} // namespace

Locator LocatorFromLegacyPosition(const json& row, LegacyBookFormat format,
                                  std::vector<LocatorIssue>* issues)
{
    return FromLegacyRow(row, format, issues);
}

Locator LocatorFromLegacyProgress(const json& row, LegacyBookFormat format,
                                  std::vector<LocatorIssue>* issues)
{
    // 进度行与标注行的字段是同一套（progress 行多一个 progress 百分比）；
    // 用同一个映射，避免两处行为漂移。
    return FromLegacyRow(row, format, issues);
}

} // namespace pb
