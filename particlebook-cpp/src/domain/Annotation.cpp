// ── PB-021（A）：Annotation 实现 ─────────────────────────────────────
// 见 domain/Annotation.h 的契约说明。只依赖 std + nlohmann/json，
// 单测直接链接 Annotation.cpp（内部再链 Locator.cpp）。
//
// 组成：
//   1. type / 颜色 / ISO-8601 时间戳的校验与规范化；
//   2. JSON 读写（稳定输出，失败走 AnnotationResult，绝不抛异常）；
//   3. Validate / Normalize（可修 vs 修不动）；
//   4. 旧 DB 行迁移（FromLegacyBookmark / Highlight / Note）。

#include "domain/Annotation.h"

#include <cstddef>
#include <ctime>
#include <limits>
#include <string>
#include <utility>
#include <vector>

namespace pb {
namespace {

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

bool IsAsciiDigit(char c) { return c >= '0' && c <= '9'; }
bool IsAsciiAlpha(char c)
{
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
}
bool IsHexDigit(char c)
{
    return IsAsciiDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
}

bool ParseTwoDigits(const std::string& text, std::size_t pos, int& out)
{
    if (pos + 1 >= text.size() || !IsAsciiDigit(text[pos]) || !IsAsciiDigit(text[pos + 1])) {
        return false;
    }
    out = (text[pos] - '0') * 10 + (text[pos + 1] - '0');
    return true;
}

AnnotationIssue MakeIssue(std::string field, std::string code, std::string message,
                          AnnotationSeverity severity)
{
    AnnotationIssue issue;
    issue.field = std::move(field);
    issue.code = std::move(code);
    issue.message = std::move(message);
    issue.severity = severity;
    return issue;
}

// Locator 的问题加 "locator." 前缀后并进 Annotation 的问题列表。
void AppendLocatorIssues(std::vector<AnnotationIssue>& issues,
                         const std::vector<LocatorIssue>& locatorIssues,
                         const char* prefix)
{
    for (const LocatorIssue& locatorIssue : locatorIssues) {
        AnnotationIssue issue;
        issue.field = std::string(prefix) + (locatorIssue.field.empty() ? "" : "." + locatorIssue.field);
        issue.code = locatorIssue.code;
        issue.message = locatorIssue.message;
        issue.severity = locatorIssue.severity == LocatorSeverity::Error
                             ? AnnotationSeverity::Error
                             : AnnotationSeverity::Warning;
        issues.push_back(std::move(issue));
    }
}

bool IsIntegerJson(const json& value)
{
    return value.is_number_integer() || value.is_number_unsigned();
}

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

// ── JSON 取值（类型不对报一次，null 视为缺省）────────────────────────

bool ReadString(const json& value, const char* key, std::string& out,
                std::vector<AnnotationIssue>& issues)
{
    auto it = value.find(key);
    if (it == value.end() || it->is_null()) return false;
    if (!it->is_string()) {
        issues.push_back(MakeIssue(key, "type", "\"" + std::string(key) + "\" must be a string",
                                   AnnotationSeverity::Error));
        return false;
    }
    out = it->get<std::string>();
    return true;
}

bool ReadInteger(const json& value, const char* key, long long& out,
                 std::vector<AnnotationIssue>& issues)
{
    auto it = value.find(key);
    if (it == value.end() || it->is_null()) return false;
    if (!IsIntegerJson(*it)) {
        issues.push_back(MakeIssue(key, "type", "\"" + std::string(key) + "\" must be an integer",
                                   AnnotationSeverity::Error));
        return false;
    }
    out = SafeInt(*it);
    return true;
}

bool ReadTags(const json& value, std::vector<std::string>& out,
              std::vector<AnnotationIssue>& issues)
{
    auto it = value.find("tags");
    if (it == value.end() || it->is_null()) return false;
    if (!it->is_array()) {
        issues.push_back(MakeIssue("tags", "type", "\"tags\" must be an array",
                                   AnnotationSeverity::Error));
        return false;
    }
    std::vector<std::string> tags;
    for (std::size_t i = 0; i < it->size(); ++i) {
        const json& entry = (*it)[i];
        if (!entry.is_string()) {
            issues.push_back(MakeIssue("tags[" + std::to_string(i) + "]", "type",
                                       "tag must be a string", AnnotationSeverity::Error));
            continue;
        }
        tags.push_back(entry.get<std::string>());
    }
    out = std::move(tags);
    return true;
}

// 旧 DB 行：带 snake_case 字段，缺字段 / null 都当没有，不报错（迁移层汇总）。
bool ReadLegacyString(const json& row, const char* key, std::string& out)
{
    auto it = row.find(key);
    if (it == row.end() || it->is_null() || !it->is_string()) return false;
    out = it->get<std::string>();
    return true;
}

bool ReadLegacyInteger(const json& row, const char* key, long long& out)
{
    auto it = row.find(key);
    if (it == row.end() || it->is_null() || !IsIntegerJson(*it)) return false;
    out = SafeInt(*it);
    return true;
}

// ── 命名色（渲染层优先 hex；这里只放常用 CSS 名，避免把乱码当颜色）──
bool IsKnownNamedColor(const std::string& lower)
{
    static const char* const kNames[] = {
        "transparent", "currentcolor", "black", "white", "red", "green", "blue", "yellow",
        "orange", "purple", "pink", "gray", "grey", "brown", "cyan", "magenta", "lime",
        "navy", "teal", "olive", "maroon", "silver", "gold", "indigo", "violet", "beige",
        "coral", "crimson", "khaki", "lavender", "salmon", "tan", "turquoise", "tomato",
        "plum", "orchid", "ivory", "azure", "aliceblue", "amber", "aqua", "fuchsia",
    };
    for (const char* name : kNames) {
        if (lower == name) return true;
    }
    return false;
}

bool IsCssFunctionColor(const std::string& lower)
{
    const char* prefixes[] = { "rgb(", "rgba(", "hsl(", "hsla(" };
    for (const char* prefix : prefixes) {
        const std::string p(prefix);
        if (lower.size() > p.size() && lower.compare(0, p.size(), p) == 0) {
            return lower.back() == ')' && lower.find('(') == p.size() - 1;
        }
    }
    return false;
}

// 校验与规范化共用的核心（Normalize 修完后再跑一遍，保证不会带着 Error 出门）。
void AppendAnnotationChecks(const Annotation& annotation, bool includeLocator,
                            std::vector<AnnotationIssue>& issues)
{
    if (annotation.version > kAnnotationVersion) {
        issues.push_back(MakeIssue("version", "version",
                                   "annotation version is newer than this build",
                                   AnnotationSeverity::Error));
    } else if (annotation.version != kAnnotationVersion) {
        issues.push_back(MakeIssue("version", "version",
                                   "unsupported annotation version", AnnotationSeverity::Error));
    }

    if (annotation.id < 0) {
        issues.push_back(MakeIssue("id", "id", "annotation id must be >= 0",
                                   AnnotationSeverity::Error));
    }
    if (annotation.bookId < 1) {
        issues.push_back(MakeIssue("bookId", "book", "annotation requires a bookId",
                                   AnnotationSeverity::Error));
    }
    if (annotation.type == AnnotationType::Unknown) {
        issues.push_back(MakeIssue("type", "type", "annotation type is required",
                                   AnnotationSeverity::Error));
    }

    const std::string color = Trim(annotation.color);
    if (!color.empty() && !IsValidAnnotationColor(color)) {
        issues.push_back(MakeIssue("color", "color", "color is not a valid css color",
                                   AnnotationSeverity::Error));
    }
    if (annotation.type == AnnotationType::Highlight && color.empty()) {
        issues.push_back(MakeIssue("color", "color", "highlight requires a color",
                                   AnnotationSeverity::Error));
    }

    if (annotation.type == AnnotationType::Note && Trim(annotation.note).empty()) {
        issues.push_back(MakeIssue("note", "note", "note requires non-empty text",
                                   AnnotationSeverity::Error));
    }

    if (!annotation.createdAt.empty() && !IsIso8601Timestamp(annotation.createdAt)) {
        issues.push_back(MakeIssue("createdAt", "timestamp",
                                   "createdAt must be an ISO-8601 timestamp",
                                   AnnotationSeverity::Error));
    }
    if (!annotation.updatedAt.empty() && !IsIso8601Timestamp(annotation.updatedAt)) {
        issues.push_back(MakeIssue("updatedAt", "timestamp",
                                   "updatedAt must be an ISO-8601 timestamp",
                                   AnnotationSeverity::Error));
    }
    if (IsIso8601Timestamp(annotation.createdAt) && IsIso8601Timestamp(annotation.updatedAt)
        && annotation.updatedAt < annotation.createdAt) {
        issues.push_back(MakeIssue("updatedAt", "timestamp-order",
                                   "updatedAt is before createdAt", AnnotationSeverity::Warning));
    }

    if (annotation.tags.size() > kMaxAnnotationTags) {
        issues.push_back(MakeIssue("tags", "tags", "too many tags, extras will be dropped",
                                   AnnotationSeverity::Warning));
    }
    for (std::size_t i = 0; i < annotation.tags.size(); ++i) {
        const std::string tag = Trim(annotation.tags[i]);
        if (tag.empty()) {
            issues.push_back(MakeIssue("tags[" + std::to_string(i) + "]", "tags",
                                       "empty tag will be dropped", AnnotationSeverity::Warning));
        } else if (tag.size() > kMaxAnnotationTagLength) {
            issues.push_back(MakeIssue("tags[" + std::to_string(i) + "]", "tags",
                                       "tag too long, will be dropped",
                                       AnnotationSeverity::Warning));
        }
    }

    if (annotation.relatedId < 0) {
        issues.push_back(MakeIssue("relatedId", "relatedId", "relatedId must be >= 0",
                                   AnnotationSeverity::Error));
    }

    if (includeLocator) {
        if (annotation.locator.kind == LocatorKind::Unknown) {
            issues.push_back(MakeIssue("locator", "locator",
                                       "annotation requires a non-empty locator",
                                       AnnotationSeverity::Error));
        } else {
            AppendLocatorIssues(issues, ValidateLocator(annotation.locator), "locator");
        }
    }
}

// 旧行的公共字段（id / book_id / created_at / updated_at / tags）。
void FillLegacyCommon(const json& row, Annotation& out, std::vector<AnnotationIssue>& issues)
{
    long long id = 0;
    if (ReadLegacyInteger(row, "id", id)) out.id = id < 0 ? 0 : id;
    long long bookId = 0;
    if (ReadLegacyInteger(row, "book_id", bookId)) {
        out.bookId = bookId < 0 ? 0 : bookId;
    } else {
        issues.push_back(MakeIssue("book_id", "legacy", "legacy row has no book_id",
                                   AnnotationSeverity::Warning));
    }
    std::string createdAt;
    if (ReadLegacyString(row, "created_at", createdAt)) out.createdAt = createdAt;
    std::string updatedAt;
    if (ReadLegacyString(row, "updated_at", updatedAt)) out.updatedAt = updatedAt;
    std::vector<std::string> tags;
    if (ReadTags(row, tags, issues)) out.tags = std::move(tags);
}

// 迁移收尾：位置问题并入 + NormalizeAnnotation 统一修一遍。
AnnotationResult FinishLegacy(AnnotationResult result,
                              std::vector<AnnotationIssue> legacyIssues,
                              std::vector<LocatorIssue> locatorIssues)
{
    result.issues.insert(result.issues.end(), legacyIssues.begin(), legacyIssues.end());
    AppendLocatorIssues(result.issues, locatorIssues, "locator");

    AnnotationResult normalized = NormalizeAnnotation(result.annotation);
    // 迁移阶段的 Warning 不能丢，Normalize 的 issues 追加在后面。
    result.issues.insert(result.issues.end(), normalized.issues.begin(), normalized.issues.end());
    result.annotation = normalized.annotation;
    result.ok = CountAnnotationErrors(result.issues) == 0;
    return result;
}

} // namespace

// ── type ─────────────────────────────────────────────────────────────

const char* AnnotationTypeName(AnnotationType type) noexcept
{
    switch (type) {
    case AnnotationType::Bookmark:  return "bookmark";
    case AnnotationType::Highlight: return "highlight";
    case AnnotationType::Note:      return "note";
    case AnnotationType::Unknown:
    default:                        return "unknown";
    }
}

bool ParseAnnotationType(const std::string& text, AnnotationType& out) noexcept
{
    const std::string lowered = LowerAscii(text);
    if (lowered == "bookmark")  { out = AnnotationType::Bookmark;  return true; }
    if (lowered == "highlight") { out = AnnotationType::Highlight; return true; }
    if (lowered == "note")      { out = AnnotationType::Note;      return true; }
    return false;
}

int AnnotationTypeRank(AnnotationType type) noexcept
{
    switch (type) {
    case AnnotationType::Bookmark:  return 1;
    case AnnotationType::Highlight: return 2;
    case AnnotationType::Note:      return 3;
    case AnnotationType::Unknown:
    default:                        return 4;
    }
}

// ── 结果 ─────────────────────────────────────────────────────────────

bool AnnotationResult::HasErrors() const noexcept
{
    return CountAnnotationErrors(issues) > 0;
}

std::size_t CountAnnotationErrors(const std::vector<AnnotationIssue>& issues) noexcept
{
    std::size_t count = 0;
    for (const AnnotationIssue& issue : issues) {
        if (issue.severity == AnnotationSeverity::Error) ++count;
    }
    return count;
}

// ── 构造 ─────────────────────────────────────────────────────────────

Annotation MakeBookmark(long long bookId, Locator locator, std::string title)
{
    Annotation annotation;
    annotation.bookId = bookId;
    annotation.type = AnnotationType::Bookmark;
    annotation.locator = std::move(locator);
    annotation.title = std::move(title);
    return annotation;
}

Annotation MakeHighlight(long long bookId, Locator locator, std::string color, std::string text)
{
    Annotation annotation;
    annotation.bookId = bookId;
    annotation.type = AnnotationType::Highlight;
    if (!text.empty()) locator.quote = std::move(text);
    annotation.locator = std::move(locator);
    annotation.color = std::move(color);
    return annotation;
}

Annotation MakeNote(long long bookId, Locator locator, std::string note, std::string title)
{
    Annotation annotation;
    annotation.bookId = bookId;
    annotation.type = AnnotationType::Note;
    annotation.locator = std::move(locator);
    annotation.note = std::move(note);
    annotation.title = std::move(title);
    return annotation;
}

// ── 颜色 ─────────────────────────────────────────────────────────────

bool IsValidAnnotationColor(const std::string& color)
{
    const std::string trimmed = Trim(color);
    if (trimmed.empty() || trimmed.size() > 64) return false;

    // 会被拼进 CSS / 注入 JS 的值：拒绝能闭合声明或标签的字符。
    for (char c : trimmed) {
        const unsigned char uc = static_cast<unsigned char>(c);
        if (c == ';' || c == '<' || c == '>' || c == '{' || c == '}' || c == '\\'
            || uc < 0x20) {
            return false;
        }
    }

    if (trimmed[0] == '#') {
        if (trimmed.size() != 4 && trimmed.size() != 5 && trimmed.size() != 7
            && trimmed.size() != 9) {
            return false;
        }
        for (std::size_t i = 1; i < trimmed.size(); ++i) {
            if (!IsHexDigit(trimmed[i])) return false;
        }
        return true;
    }

    const std::string lower = LowerAscii(trimmed);
    if (IsCssFunctionColor(lower)) return true;

    for (char c : lower) {
        if (!IsAsciiAlpha(c)) return false;
    }
    return IsKnownNamedColor(lower);
}

std::string NormalizeAnnotationColor(const std::string& color)
{
    // CSS 颜色名 / 函数大小写不敏感；hex 统一小写，便于 DB 与渲染层比对。
    return LowerAscii(Trim(color));
}

// ── 时间戳 ───────────────────────────────────────────────────────────

bool IsIso8601Timestamp(const std::string& text)
{
    const std::string s = Trim(text);
    if (s.size() < 19) return false;

    int month = 0;
    int day = 0;
    int hour = 0;
    int minute = 0;
    int second = 0;
    // 年份只校验四位数字（不限制范围，避免把 <1000 年的合法时间判死）。
    if (!IsAsciiDigit(s[0]) || !IsAsciiDigit(s[1]) || !IsAsciiDigit(s[2])
        || !IsAsciiDigit(s[3])) {
        return false;
    }
    if (s[4] != '-' || !ParseTwoDigits(s, 5, month)) return false;
    if (s[7] != '-' || !ParseTwoDigits(s, 8, day)) return false;
    if (s[10] != 'T' && s[10] != 't') return false;
    if (!ParseTwoDigits(s, 11, hour)) return false;
    if (s[13] != ':' || !ParseTwoDigits(s, 14, minute)) return false;
    if (s[16] != ':' || !ParseTwoDigits(s, 17, second)) return false;

    if (month < 1 || month > 12) return false;
    if (day < 1 || day > 31) return false;
    if (hour > 23 || minute > 59 || second > 60) return false;

    std::size_t i = 19;
    if (i < s.size() && s[i] == '.') {
        ++i;
        const std::size_t digitsBegin = i;
        while (i < s.size() && IsAsciiDigit(s[i])) ++i;
        if (i == digitsBegin || i - digitsBegin > 9) return false;
    }
    if (i == s.size()) return true;  // 本地时间，无时区后缀
    if (s[i] == 'Z' || s[i] == 'z') return i + 1 == s.size();
    if (s[i] == '+' || s[i] == '-') {
        if (i + 6 != s.size()) return false;
        int offsetHour = 0;
        int offsetMinute = 0;
        if (!ParseTwoDigits(s, i + 1, offsetHour)) return false;
        if (s[i + 3] != ':') return false;
        if (!ParseTwoDigits(s, i + 4, offsetMinute)) return false;
        return offsetHour <= 23 && offsetMinute <= 59;
    }
    return false;
}

std::string NowIso8601()
{
    const std::time_t now = std::time(nullptr);
    std::tm utc{};
#ifdef _MSC_VER
    if (gmtime_s(&utc, &now) != 0) return std::string();
#else
    if (gmtime_r(&now, &utc) == nullptr) return std::string();
#endif
    char buffer[32] = {};
    const std::size_t length =
        std::strftime(buffer, sizeof buffer, "%Y-%m-%dT%H:%M:%S.000Z", &utc);
    if (length == 0) return std::string();
    return std::string(buffer, length);
}

// ── 校验 / 规范化 ────────────────────────────────────────────────────

std::vector<AnnotationIssue> ValidateAnnotation(const Annotation& annotation)
{
    std::vector<AnnotationIssue> issues;
    AppendAnnotationChecks(annotation, /*includeLocator=*/true, issues);
    return issues;
}

bool IsValidAnnotation(const Annotation& annotation)
{
    return CountAnnotationErrors(ValidateAnnotation(annotation)) == 0;
}

AnnotationResult NormalizeAnnotation(const Annotation& annotation)
{
    AnnotationResult result;
    result.annotation = annotation;
    Annotation& out = result.annotation;

    if (out.version > kAnnotationVersion) {
        result.issues.push_back(MakeIssue("version", "version",
                                          "annotation version is newer than this build",
                                          AnnotationSeverity::Error));
    } else if (out.version != kAnnotationVersion) {
        result.issues.push_back(MakeIssue("version", "version-migrated",
                                          "annotation version migrated to current",
                                          AnnotationSeverity::Warning));
        out.version = kAnnotationVersion;
    }

    if (out.type == AnnotationType::Unknown) {
        // 不猜类型：猜错会把书签渲染成高亮。
        result.issues.push_back(MakeIssue("type", "type", "annotation type is required",
                                          AnnotationSeverity::Error));
        result.ok = false;
        return result;
    }

    if (out.id < 0) {
        out.id = 0;
        result.issues.push_back(MakeIssue("id", "id", "negative id reset to 0",
                                          AnnotationSeverity::Warning));
    }

    // 颜色：统一小写并 trim；非高亮上的坏颜色直接丢掉（它只是装饰），
    // 高亮上的坏颜色保留为 Error，交给迁移 / 用户修。
    const std::string rawColor = out.color;
    out.color = NormalizeAnnotationColor(out.color);
    if (out.color != rawColor && !Trim(rawColor).empty()) {
        result.issues.push_back(MakeIssue("color", "color-normalized",
                                          "color normalized", AnnotationSeverity::Warning));
    }
    if (!out.color.empty() && !IsValidAnnotationColor(out.color)) {
        if (out.type == AnnotationType::Highlight) {
            // 保留坏值，让 Validate 报 Error（不静默换成默认黄色）。
        } else {
            out.color.clear();
            result.issues.push_back(MakeIssue("color", "color",
                                              "invalid color dropped", AnnotationSeverity::Warning));
        }
    }

    out.title = Trim(out.title);
    out.note = Trim(out.note);

    // 时间戳：补齐、纠正倒序（DB 里 updated_at 早于 created_at 通常是旧代码写错）。
    out.createdAt = Trim(out.createdAt);
    out.updatedAt = Trim(out.updatedAt);
    if (out.createdAt.empty()) {
        out.createdAt = NowIso8601();
        result.issues.push_back(MakeIssue("createdAt", "timestamp-filled",
                                          "createdAt filled with current time",
                                          AnnotationSeverity::Warning));
    }
    if (out.updatedAt.empty()) {
        out.updatedAt = out.createdAt;
        result.issues.push_back(MakeIssue("updatedAt", "timestamp-filled",
                                          "updatedAt filled from createdAt",
                                          AnnotationSeverity::Warning));
    }
    if (IsIso8601Timestamp(out.createdAt) && IsIso8601Timestamp(out.updatedAt)
        && out.updatedAt < out.createdAt) {
        out.updatedAt = out.createdAt;
        result.issues.push_back(MakeIssue("updatedAt", "timestamp-order",
                                          "updatedAt moved to createdAt",
                                          AnnotationSeverity::Warning));
    }

    // tags：trim、丢空、去重（大小写不敏感）、截断。
    std::vector<std::string> tags;
    tags.reserve(out.tags.size());
    std::vector<std::string> seen;
    for (const std::string& raw : out.tags) {
        const std::string tag = Trim(raw);
        if (tag.empty()) {
            result.issues.push_back(MakeIssue("tags", "tags", "empty tag dropped",
                                              AnnotationSeverity::Warning));
            continue;
        }
        if (tag.size() > kMaxAnnotationTagLength) {
            result.issues.push_back(MakeIssue("tags", "tags", "overlong tag dropped",
                                              AnnotationSeverity::Warning));
            continue;
        }
        if (tags.size() >= kMaxAnnotationTags) {
            result.issues.push_back(MakeIssue("tags", "tags", "extra tag dropped",
                                              AnnotationSeverity::Warning));
            continue;
        }
        const std::string lowered = LowerAscii(tag);
        bool duplicate = false;
        for (const std::string& kept : seen) {
            if (kept == lowered) {
                duplicate = true;
                break;
            }
        }
        if (duplicate) {
            result.issues.push_back(MakeIssue("tags", "tags", "duplicate tag dropped",
                                              AnnotationSeverity::Warning));
            continue;
        }
        seen.push_back(lowered);
        tags.push_back(tag);
    }
    out.tags = std::move(tags);

    if (out.relatedId < 0) {
        out.relatedId = 0;
        result.issues.push_back(MakeIssue("relatedId", "relatedId", "negative relatedId reset to 0",
                                          AnnotationSeverity::Warning));
    }

    // locator：结构修复（版本、trim、rect 丢弃…），问题带 locator. 前缀。
    LocatorResult locatorResult = NormalizeLocator(out.locator);
    out.locator = locatorResult.locator;
    AppendLocatorIssues(result.issues, locatorResult.issues, "locator");

    // 最后跑一遍注解自身检查（不含 locator，避免与上面重复）。
    AppendAnnotationChecks(out, /*includeLocator=*/false, result.issues);
    result.ok = CountAnnotationErrors(result.issues) == 0;
    return result;
}

// ── JSON ─────────────────────────────────────────────────────────────

json ToJson(const Annotation& annotation)
{
    json value = json::object();
    value["version"] = annotation.version > 0 ? annotation.version : kAnnotationVersion;
    value["id"] = annotation.id;
    value["bookId"] = annotation.bookId;
    value["type"] = AnnotationTypeName(annotation.type);
    value["locator"] = ToJson(annotation.locator);

    if (!annotation.color.empty()) value["color"] = annotation.color;
    if (!annotation.title.empty()) value["title"] = annotation.title;
    if (!annotation.note.empty()) value["note"] = annotation.note;
    if (!annotation.createdAt.empty()) value["createdAt"] = annotation.createdAt;
    if (!annotation.updatedAt.empty()) value["updatedAt"] = annotation.updatedAt;
    if (!annotation.tags.empty()) value["tags"] = annotation.tags;
    if (annotation.relatedId > 0) value["relatedId"] = annotation.relatedId;
    return value;
}

std::string SerializeAnnotation(const Annotation& annotation)
{
    return ToJson(annotation).dump();
}

AnnotationResult AnnotationFromJson(const json& value)
{
    AnnotationResult result;

    if (!value.is_object()) {
        result.issues.push_back(MakeIssue("root", "root", "annotation must be a json object",
                                          AnnotationSeverity::Error));
        return result;
    }

    Annotation& out = result.annotation;

    long long version = 0;
    if (value.contains("version")) {
        if (!IsIntegerJson(value["version"])) {
            result.issues.push_back(MakeIssue("version", "type", "\"version\" must be an integer",
                                              AnnotationSeverity::Error));
            return result;
        }
        version = SafeInt(value["version"]);
        if (version > kAnnotationVersion) {
            result.issues.push_back(MakeIssue("version", "version",
                                              "annotation version is newer than this build",
                                              AnnotationSeverity::Error));
            out.version = static_cast<int>(version);
            return result;
        }
        if (version <= 0) {
            result.issues.push_back(MakeIssue("version", "version",
                                              "annotation version must be >= 1",
                                              AnnotationSeverity::Error));
            return result;
        }
        if (version != kAnnotationVersion) {
            result.issues.push_back(MakeIssue("version", "version-migrated",
                                              "annotation version migrated to current",
                                              AnnotationSeverity::Warning));
        }
        out.version = kAnnotationVersion;
    } else {
        result.issues.push_back(MakeIssue("version", "version-missing",
                                          "annotation has no version, assuming 1",
                                          AnnotationSeverity::Warning));
        out.version = kAnnotationVersion;
    }

    long long id = 0;
    if (ReadInteger(value, "id", id, result.issues)) out.id = id;
    long long bookId = 0;
    if (ReadInteger(value, "bookId", bookId, result.issues)) out.bookId = bookId;

    std::string typeName;
    if (!ReadString(value, "type", typeName, result.issues)) {
        if (!value.contains("type")) {
            result.issues.push_back(MakeIssue("type", "type", "annotation requires a type",
                                              AnnotationSeverity::Error));
        }
        result.ok = false;
        return result;
    }
    AnnotationType type = AnnotationType::Unknown;
    if (!ParseAnnotationType(typeName, type)) {
        result.issues.push_back(MakeIssue("type", "type", "unsupported annotation type",
                                          AnnotationSeverity::Error));
        result.ok = false;
        return result;
    }
    out.type = type;

    auto locatorIt = value.find("locator");
    if (locatorIt == value.end() || locatorIt->is_null()) {
        result.issues.push_back(MakeIssue("locator", "locator",
                                          "annotation requires a locator",
                                          AnnotationSeverity::Error));
    } else {
        LocatorResult locatorResult = LocatorFromJson(*locatorIt);
        out.locator = locatorResult.locator;
        AppendLocatorIssues(result.issues, locatorResult.issues, "locator");
    }

    std::string color;
    if (ReadString(value, "color", color, result.issues)) out.color = Trim(color);
    std::string title;
    if (ReadString(value, "title", title, result.issues)) out.title = Trim(title);
    std::string note;
    if (ReadString(value, "note", note, result.issues)) out.note = Trim(note);
    std::string createdAt;
    if (ReadString(value, "createdAt", createdAt, result.issues)) out.createdAt = Trim(createdAt);
    std::string updatedAt;
    if (ReadString(value, "updatedAt", updatedAt, result.issues)) out.updatedAt = Trim(updatedAt);
    ReadTags(value, out.tags, result.issues);
    long long relatedId = 0;
    if (ReadInteger(value, "relatedId", relatedId, result.issues)) out.relatedId = relatedId;

    AppendAnnotationChecks(out, /*includeLocator=*/false, result.issues);
    result.ok = CountAnnotationErrors(result.issues) == 0;
    return result;
}

AnnotationResult ParseAnnotation(const std::string& text)
{
    const json value = json::parse(text, nullptr, /*allow_exceptions=*/false);
    if (value.is_discarded()) {
        AnnotationResult result;
        result.issues.push_back(MakeIssue("root", "parse", "annotation text is not valid json",
                                          AnnotationSeverity::Error));
        return result;
    }
    return AnnotationFromJson(value);
}

// ── 相等 / 排序 ──────────────────────────────────────────────────────

bool Annotation::SamePositionAs(const Annotation& other) const
{
    return SamePosition(*this, other);
}

bool Annotation::operator==(const Annotation& other) const noexcept
{
    return version == other.version
        && id == other.id
        && bookId == other.bookId
        && type == other.type
        && locator == other.locator
        && color == other.color
        && title == other.title
        && note == other.note
        && createdAt == other.createdAt
        && updatedAt == other.updatedAt
        && tags == other.tags
        && relatedId == other.relatedId;
}

bool SamePosition(const Annotation& a, const Annotation& b)
{
    return a.bookId == b.bookId && a.type == b.type && SamePosition(a.locator, b.locator);
}

int CompareAnnotation(const Annotation& a, const Annotation& b)
{
    if (a.bookId != b.bookId) return a.bookId < b.bookId ? -1 : 1;
    const int locatorOrder = CompareLocator(a.locator, b.locator);
    if (locatorOrder != 0) return locatorOrder;
    const int rankA = AnnotationTypeRank(a.type);
    const int rankB = AnnotationTypeRank(b.type);
    if (rankA != rankB) return rankA < rankB ? -1 : 1;
    if (a.createdAt != b.createdAt) return a.createdAt < b.createdAt ? -1 : 1;
    if (a.id != b.id) return a.id < b.id ? -1 : 1;
    return 0;
}

// ── 旧数据兼容 ───────────────────────────────────────────────────────

AnnotationResult FromLegacyBookmark(const json& row, LegacyBookFormat format)
{
    AnnotationResult result;
    if (!row.is_object()) {
        result.issues.push_back(MakeIssue("root", "root", "legacy bookmark is not a json object",
                                          AnnotationSeverity::Error));
        return result;
    }

    Annotation& out = result.annotation;
    out.type = AnnotationType::Bookmark;
    std::vector<AnnotationIssue> issues;
    FillLegacyCommon(row, out, issues);

    std::string title;
    if (ReadLegacyString(row, "title", title)) out.title = title;
    std::string note;
    if (ReadLegacyString(row, "note", note)) out.note = note;

    std::vector<LocatorIssue> locatorIssues;
    out.locator = LocatorFromLegacyPosition(row, format, &locatorIssues);
    return FinishLegacy(std::move(result), std::move(issues), std::move(locatorIssues));
}

AnnotationResult FromLegacyHighlight(const json& row, LegacyBookFormat format)
{
    AnnotationResult result;
    if (!row.is_object()) {
        result.issues.push_back(MakeIssue("root", "root", "legacy highlight is not a json object",
                                          AnnotationSeverity::Error));
        return result;
    }

    Annotation& out = result.annotation;
    out.type = AnnotationType::Highlight;
    std::vector<AnnotationIssue> issues;
    FillLegacyCommon(row, out, issues);

    std::string color;
    if (ReadLegacyString(row, "color", color)) out.color = color;
    std::string note;
    if (ReadLegacyString(row, "note", note)) out.note = note;

    // 选区文本由 LocatorFromLegacyPosition 从旧行 text 写进 locator.quote。
    std::vector<LocatorIssue> locatorIssues;
    out.locator = LocatorFromLegacyPosition(row, format, &locatorIssues);
    return FinishLegacy(std::move(result), std::move(issues), std::move(locatorIssues));
}

AnnotationResult FromLegacyNote(const json& row, LegacyBookFormat format,
                                const Annotation* relatedHighlight)
{
    AnnotationResult result;
    if (!row.is_object()) {
        result.issues.push_back(MakeIssue("root", "root", "legacy note is not a json object",
                                          AnnotationSeverity::Error));
        return result;
    }

    Annotation& out = result.annotation;
    out.type = AnnotationType::Note;
    std::vector<AnnotationIssue> issues;
    FillLegacyCommon(row, out, issues);

    std::string content;
    if (ReadLegacyString(row, "content", content)) out.note = content;
    long long highlightId = 0;
    if (ReadLegacyInteger(row, "highlight_id", highlightId) && highlightId > 0) {
        out.relatedId = highlightId;
    }

    std::vector<LocatorIssue> locatorIssues;
    out.locator = LocatorFromLegacyPosition(row, format, &locatorIssues);

    if (out.locator.IsEmpty() && relatedHighlight && !relatedHighlight->locator.IsEmpty()) {
        out.locator = relatedHighlight->locator;
        issues.push_back(MakeIssue("locator", "locator-inherited",
                                   "locator inherited from related highlight",
                                   AnnotationSeverity::Warning));
    }

    return FinishLegacy(std::move(result), std::move(issues), std::move(locatorIssues));
}

} // namespace pb
