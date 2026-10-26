// ── PB-004（A）：笔记 / 高亮导出核心实现 ─────────────────────────────
// 见 services/ExportService.h 的契约。本文件只依赖 std + nlohmann/json：
// 不读数据库、不写文件、不碰 Win32，单测直接链接本 .cpp。
//
// 处理流程：
//   DB 行（JSON）→ BuildBook()：解析 / 校验 / 归章节 / 排序 → BookModel
//                → RenderMarkdown / RenderCsv / RenderJson → 纯字符串
// 所有对外文本都先过 MdInline / MdBlock / CsvCell 清洗，坏行进 issues。

#include "services/ExportService.h"

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <initializer_list>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace pb {
namespace {

// ── 基础字符串工具 ───────────────────────────────────────────────────
std::string TrimAscii(const std::string& text)
{
    size_t begin = 0;
    size_t end = text.size();
    while (begin < end) {
        const unsigned char c = static_cast<unsigned char>(text[begin]);
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n') ++begin; else break;
    }
    while (end > begin) {
        const unsigned char c = static_cast<unsigned char>(text[end - 1]);
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n') --end; else break;
    }
    return text.substr(begin, end - begin);
}

char LowerAscii(char c)
{
    return (c >= 'A' && c <= 'Z') ? static_cast<char>(c - 'A' + 'a') : c;
}

std::string ToLowerAscii(std::string text)
{
    for (char& c : text) c = LowerAscii(c);
    return text;
}

std::string ToUpperAscii(std::string text)
{
    for (char& c : text) {
        if (c >= 'a' && c <= 'z') c = static_cast<char>(c - 'a' + 'A');
    }
    return text;
}

bool EqualsNoCase(const std::string& a, const std::string& b)
{
    if (a.size() != b.size()) return false;
    for (size_t i = 0; i < a.size(); ++i) {
        if (LowerAscii(a[i]) != LowerAscii(b[i])) return false;
    }
    return true;
}

bool StartsWithNoCase(const std::string& s, const char* prefix)
{
    const size_t n = std::strlen(prefix);
    if (s.size() < n) return false;
    for (size_t i = 0; i < n; ++i) {
        if (LowerAscii(s[i]) != LowerAscii(prefix[i])) return false;
    }
    return true;
}

bool IsSpaceAscii(char c)
{
    return c == ' ' || c == '\t' || c == '\r' || c == '\n';
}

// 剔除 0x00-0x1F 里的控制字符（保留 \t，按 keepNewlines 决定是否保留 \n），
// 并把 CRLF / 单独 CR 归一为 \n。
std::string CleanText(const std::string& text, bool keepNewlines)
{
    std::string out;
    out.reserve(text.size());
    for (size_t i = 0; i < text.size(); ++i) {
        const unsigned char c = static_cast<unsigned char>(text[i]);
        if (c == '\r') {
            if (i + 1 < text.size() && text[i + 1] == '\n') ++i;
            if (keepNewlines) out.push_back('\n');
            else out.push_back(' ');
        } else if (c == '\n') {
            out.push_back(keepNewlines ? '\n' : ' ');
        } else if (c == '\t') {
            out.push_back(keepNewlines ? '\t' : ' ');
        } else if (c < 0x20 || c == 0x7F) {
            // drop
        } else {
            out.push_back(static_cast<char>(c));
        }
    }
    return out;
}

std::string CollapseWhitespace(const std::string& text)
{
    std::string out;
    out.reserve(text.size());
    bool pendingSpace = false;
    for (char c : text) {
        if (IsSpaceAscii(c)) {
            pendingSpace = !out.empty();
        } else {
            if (pendingSpace) out.push_back(' ');
            pendingSpace = false;
            out.push_back(c);
        }
    }
    return out;
}

// UTF-8 单字符字节数（非法首字节按 1 处理，避免死循环）。
size_t Utf8CharLength(unsigned char lead)
{
    if (lead < 0x80) return 1;
    if ((lead & 0xE0) == 0xC0) return 2;
    if ((lead & 0xF0) == 0xE0) return 3;
    if ((lead & 0xF8) == 0xF0) return 4;
    return 1;
}

// 按 UTF-8 码点截断，不切坏多字节字符。
std::string Utf8SafeTruncate(const std::string& text, size_t maxBytes)
{
    if (maxBytes == 0) return std::string();
    if (text.size() <= maxBytes) return text;
    size_t i = 0;
    while (i < text.size()) {
        const size_t len = Utf8CharLength(static_cast<unsigned char>(text[i]));
        if (i + len > maxBytes) break;
        i += len;
    }
    return text.substr(0, i);
}

// Markdown 行内文本：压平换行、去控制字符、可选转义 HTML 尖括号。
std::string MdInline(const std::string& text, const ExportOptions& options)
{
    std::string out = CleanText(text, false);
    out = TrimAscii(out);
    if (options.markdownEscapeHtml) {
        std::string escaped;
        escaped.reserve(out.size() + 8);
        for (char c : out) {
            if (c == '<') escaped += "&lt;";
            else if (c == '>') escaped += "&gt;";
            else escaped.push_back(c);
        }
        out.swap(escaped);
    }
    return out;
}

// Markdown 块文本：保留换行（引用 / 笔记正文），同样去控制字符与转义。
std::string MdBlock(const std::string& text, const ExportOptions& options)
{
    std::string out = CleanText(text, true);
    out = TrimAscii(out);
    if (options.markdownEscapeHtml) {
        std::string escaped;
        escaped.reserve(out.size() + 8);
        for (char c : out) {
            if (c == '<') escaped += "&lt;";
            else if (c == '>') escaped += "&gt;";
            else escaped.push_back(c);
        }
        out.swap(escaped);
    }
    return out;
}

// 把块文本的每一行加前缀（引用原文用 > ，续行用两个空格）。
std::string PrefixLines(const std::string& text, const std::string& prefix,
                        const std::string& emptyLinePrefix)
{
    std::string out;
    size_t start = 0;
    for (;;) {
        const size_t end = text.find('\n', start);
        const std::string line = text.substr(start, (end == std::string::npos) ? std::string::npos : end - start);
        if (!out.empty()) out.push_back('\n');
        if (line.empty()) out += emptyLinePrefix;
        else out += prefix + line;
        if (end == std::string::npos) break;
        start = end + 1;
    }
    return out;
}

// 笔记正文：首行紧跟标签，续行缩进两格（Markdown 里是续行，不破列表）。
std::string IndentContinuation(const std::string& text)
{
    std::string out;
    size_t start = 0;
    bool first = true;
    for (;;) {
        const size_t end = text.find('\n', start);
        const std::string line = text.substr(start, (end == std::string::npos) ? std::string::npos : end - start);
        if (!first) out += "\n  ";
        out += line;
        first = false;
        if (end == std::string::npos) break;
        start = end + 1;
    }
    return out;
}

void AppendBlock(std::string& out, const std::string& block)
{
    if (block.empty()) return;
    if (!out.empty()) out += "\n\n";
    out += block;
}

std::string JoinLines(const std::vector<std::string>& lines, const char* separator = "\n")
{
    std::string out;
    for (size_t i = 0; i < lines.size(); ++i) {
        if (i) out += separator;
        out += lines[i];
    }
    return out;
}

// ── JSON 取值（永不抛异常）───────────────────────────────────────────
const json* FindKey(const json& object, const char* key)
{
    if (!object.is_object()) return nullptr;
    const json::const_iterator it = object.find(key);
    if (it == object.end()) return nullptr;
    return &(*it);
}

const json* FindAny(const json& object, std::initializer_list<const char*> keys)
{
    for (const char* key : keys) {
        const json* value = FindKey(object, key);
        if (value && !value->is_null()) return value;
    }
    return nullptr;
}

std::string GetStringAny(const json& object, std::initializer_list<const char*> keys)
{
    const json* value = FindAny(object, keys);
    if (!value) return std::string();
    if (value->is_string()) return value->get<std::string>();
    if (value->is_number()) {
        return value->dump();
    }
    return std::string();
}

bool ParseIntegerText(const std::string& text, long long& out)
{
    const std::string trimmed = TrimAscii(text);
    if (trimmed.empty()) return false;
    char* end = nullptr;
    const long long value = std::strtoll(trimmed.c_str(), &end, 10);
    if (end == trimmed.c_str()) return false;
    out = value;
    return true;
}

bool GetIntAny(const json& object, std::initializer_list<const char*> keys, long long& out)
{
    const json* value = FindAny(object, keys);
    if (!value) return false;
    if (value->is_number_integer()) { out = value->get<long long>(); return true; }
    if (value->is_number_unsigned()) { out = static_cast<long long>(value->get<unsigned long long>()); return true; }
    if (value->is_number_float()) { out = static_cast<long long>(value->get<double>()); return true; }
    if (value->is_string()) return ParseIntegerText(value->get<std::string>(), out);
    return false;
}

bool GetDoubleAny(const json& object, std::initializer_list<const char*> keys, double& out)
{
    const json* value = FindAny(object, keys);
    if (!value) return false;
    if (value->is_number()) { out = value->get<double>(); return true; }
    if (value->is_string()) {
        const std::string trimmed = TrimAscii(value->get<std::string>());
        if (trimmed.empty()) return false;
        char* end = nullptr;
        const double parsed = std::strtod(trimmed.c_str(), &end);
        if (end == trimmed.c_str()) return false;
        out = parsed;
        return true;
    }
    return false;
}

// "42.50" → "42.5"，"42.00" → "42"（位置显示用）。
std::string FormatNumber(double value)
{
    char buffer[64];
    std::snprintf(buffer, sizeof buffer, "%.2f", value);
    std::string text(buffer);
    if (text.find('.') != std::string::npos) {
        while (!text.empty() && text.back() == '0') text.pop_back();
        if (!text.empty() && text.back() == '.') text.pop_back();
    }
    return text;
}

bool IsReservedDeviceName(const std::string& base)
{
    const std::string upper = ToUpperAscii(TrimAscii(base));
    if (upper == "CON" || upper == "PRN" || upper == "AUX" || upper == "NUL") return true;
    if (upper.size() == 4 && (upper.compare(0, 3, "COM") == 0 || upper.compare(0, 3, "LPT") == 0)) {
        return upper[3] >= '1' && upper[3] <= '9';
    }
    return false;
}

// ── 位置 ─────────────────────────────────────────────────────────────
struct Position {
    std::string kind;      // epub / text / html / pdf / scroll / unknown
    std::string cfi;
    std::string href;
    long long page = -1;   // PDF 页码（1 起）
    long long offset = -1; // 文本偏移
    double progress = -1.0;
    std::string display;
};

int PositionKindRank(const std::string& kind)
{
    if (kind == "epub") return 0;
    if (kind == "text") return 1;
    if (kind == "html") return 2;
    if (kind == "pdf") return 3;
    if (kind == "scroll") return 4;
    return 5;
}

std::string FormatPositionDisplay(const Position& position)
{
    if (position.kind == "epub") return position.cfi;
    if (position.kind == "text") {
        if (position.offset >= 0) return "偏移 " + std::to_string(position.offset);
        if (position.progress >= 0.0) return FormatNumber(position.progress) + "%";
        return std::string();
    }
    if (position.kind == "html") {
        std::string text = position.href.empty() ? position.cfi : position.href;
        if (position.offset >= 0) text += " @" + std::to_string(position.offset);
        return text;
    }
    if (position.kind == "pdf") {
        if (position.page >= 1) return "第 " + std::to_string(position.page) + " 页";
        return std::string();
    }
    if (position.kind == "scroll") {
        if (position.progress >= 0.0) return FormatNumber(position.progress) + "%";
        return std::string();
    }
    if (!position.cfi.empty()) return position.cfi;
    if (position.page >= 1) return "第 " + std::to_string(position.page) + " 页";
    if (position.progress >= 0.0) return FormatNumber(position.progress) + "%";
    return std::string();
}

// 识别旧 DB 行的位置字段；兼容将来带 locator 对象的行（PB-021）。
Position ParsePosition(const json& row)
{
    Position position;
    const json* locator = FindKey(row, "locator");
    const json& source = (locator && locator->is_object()) ? *locator : row;

    position.kind = ToLowerAscii(TrimAscii(GetStringAny(source, { "kind" })));
    position.cfi = GetStringAny(source, { "cfi" });
    if (position.cfi.empty() && &source != &row) position.cfi = GetStringAny(row, { "cfi" });
    position.href = GetStringAny(source, { "href" });
    if (position.href.empty() && &source != &row) position.href = GetStringAny(row, { "href" });

    long long number = -1;
    if (GetIntAny(source, { "page" }, number)) position.page = number;
    if (position.page < 1 && &source != &row) {
        if (GetIntAny(row, { "page" }, number)) position.page = number;
    }
    number = -1;
    if (GetIntAny(source, { "offset" }, number)) position.offset = number;
    if (position.offset < 0 && &source != &row) {
        if (GetIntAny(row, { "offset" }, number)) position.offset = number;
    }
    double percent = -1.0;
    if (GetDoubleAny(source, { "progress", "percent", "scroll_position", "scrollPosition" }, percent)) {
        position.progress = percent;
    }
    if (position.progress < 0.0 && &source != &row) {
        if (GetDoubleAny(row, { "progress", "percent", "scroll_position", "scrollPosition" }, percent)) {
            position.progress = percent;
        }
    }

    // 老 TEXT 渲染器把 "pos-<offset>" 写进 cfi，百分比写进 page。
    if (position.kind.empty() || PositionKindRank(position.kind) == 5) {
        if (StartsWithNoCase(position.cfi, "epubcfi(")) {
            position.kind = "epub";
        } else if (StartsWithNoCase(position.cfi, "pos-")) {
            position.kind = "text";
            if (position.offset < 0) {
                long long parsed = -1;
                if (ParseIntegerText(position.cfi.substr(4), parsed)) position.offset = parsed;
            }
        } else if (!position.href.empty()) {
            position.kind = "html";
        } else if (!position.cfi.empty()) {
            position.kind = "html";   // ch01.xhtml#sec2 这类
        } else if (position.page >= 1) {
            position.kind = "pdf";
        } else if (position.progress >= 0.0) {
            position.kind = "scroll";
        } else {
            position.kind = "unknown";
        }
    }
    // 老 TEXT 行的 page 是百分比（不是页码）：从页码语义里挪出来。
    if (position.kind == "text" && position.offset < 0 && position.progress < 0.0 && position.page >= 1) {
        position.progress = static_cast<double>(position.page);
        position.page = -1;
    }
    position.display = FormatPositionDisplay(position);
    return position;
}

bool PositionLess(const Position& a, const Position& b)
{
    const int rankA = PositionKindRank(a.kind);
    const int rankB = PositionKindRank(b.kind);
    if (rankA != rankB) return rankA < rankB;

    if (a.kind == "pdf") {
        if (a.page != b.page) return a.page < b.page;
        return a.display < b.display;
    }
    if (a.kind == "text") {
        if (a.offset != b.offset) return a.offset < b.offset;
        if (a.progress != b.progress) return a.progress < b.progress;
        return false;
    }
    if (a.kind == "scroll") {
        if (a.progress != b.progress) return a.progress < b.progress;
        return false;
    }
    if (a.kind == "html") {
        if (a.href != b.href) return a.href < b.href;
        if (a.offset != b.offset) return a.offset < b.offset;
        return a.cfi < b.cfi;
    }
    if (a.kind == "epub") return a.cfi < b.cfi;
    return a.display < b.display;
}

// ── 章节 ─────────────────────────────────────────────────────────────
struct TocNode {
    std::string title;
    std::string cfi;
    std::string href;
    long long page = -1;
};

std::string TocTitleFallback(size_t index)
{
    return "第 " + std::to_string(index + 1) + " 章";
}

void ParseTocInto(const json& value, std::vector<TocNode>& out, ExportIssue* context,
                  int depth)
{
    if (depth > 8) return;
    if (value.is_array()) {
        for (const json& item : value) ParseTocInto(item, out, context, depth + 1);
        return;
    }
    if (value.is_string()) {
        TocNode node;
        node.title = TrimAscii(value.get<std::string>());
        if (!node.title.empty()) out.push_back(node);
        return;
    }
    if (!value.is_object()) {
        if (context) {
            context->code = "toc";
            context->message = "toc entry is not an object or string";
            context->severity = ExportSeverity::Warning;
        }
        return;
    }
    TocNode node;
    node.title = TrimAscii(GetStringAny(value, { "title", "label", "name", "text" }));
    node.cfi = TrimAscii(GetStringAny(value, { "cfi", "cfiBase", "cfi_base" }));
    node.href = TrimAscii(GetStringAny(value, { "href", "src", "url" }));
    long long page = -1;
    if (GetIntAny(value, { "page" }, page) && page >= 1) node.page = page;
    if (!node.title.empty()) {
        out.push_back(node);
    } else if (!node.cfi.empty() || !node.href.empty() || node.page >= 1) {
        TocNode named = node;
        named.title = TocTitleFallback(out.size());
        out.push_back(named);
    }
    const json* children = FindKey(value, "children");
    if (children) ParseTocInto(*children, out, context, depth + 1);
}

std::vector<TocNode> ParseToc(const ExportBook& book, std::vector<ExportIssue>& issues)
{
    std::vector<TocNode> toc;
    const json& source = book.toc.is_array() && !book.toc.empty() ? book.toc : json();
    if (source.is_array()) {
        ParseTocInto(source, toc, nullptr, 0);
    } else if (book.book.is_object()) {
        const json* nested = FindKey(book.book, "toc");
        if (nested) {
            ExportIssue issue;
            ParseTocInto(*nested, toc, &issue, 0);
            if (!issue.code.empty()) {
                issue.scope = "toc";
                issues.push_back(issue);
            }
        }
    }
    return toc;
}

std::string NormalizeCfiPrefix(std::string cfi)
{
    cfi = TrimAscii(cfi);
    while (!cfi.empty()) {
        const char last = cfi.back();
        if (last == ')' || last == '!' || last == ' ') cfi.pop_back();
        else break;
    }
    return cfi;
}

std::string StripFragment(std::string href)
{
    const size_t hash = href.find('#');
    if (hash != std::string::npos) href.resize(hash);
    return TrimAscii(href);
}

// 返回 toc 下标（0..），或 toc.size() + 合成章节下标；-1 = 未分章。
int ResolveChapterIndex(const json& row, const Position& position,
                        const std::vector<TocNode>& toc,
                        std::vector<std::string>& syntheticTitles,
                        std::string& outTitle)
{
    long long chapterIndex = -1;
    if (GetIntAny(row, { "chapter_index", "chapterIndex" }, chapterIndex) &&
        chapterIndex >= 0 && static_cast<size_t>(chapterIndex) < toc.size()) {
        outTitle = toc[static_cast<size_t>(chapterIndex)].title;
        return static_cast<int>(chapterIndex);
    }

    std::string chapterTitle = TrimAscii(GetStringAny(row, { "chapter", "chapter_title", "chapterTitle" }));
    if (!chapterTitle.empty()) {
        for (size_t i = 0; i < toc.size(); ++i) {
            if (EqualsNoCase(toc[i].title, chapterTitle)) {
                outTitle = toc[i].title;
                return static_cast<int>(i);
            }
        }
        for (size_t i = 0; i < syntheticTitles.size(); ++i) {
            if (EqualsNoCase(syntheticTitles[i], chapterTitle)) {
                outTitle = syntheticTitles[i];
                return static_cast<int>(toc.size() + i);
            }
        }
        syntheticTitles.push_back(chapterTitle);
        outTitle = chapterTitle;
        return static_cast<int>(toc.size() + syntheticTitles.size() - 1);
    }

    // CFI 前缀匹配：选匹配得最长（最具体）的章节。
    const std::string annotationCfi = NormalizeCfiPrefix(position.cfi);
    int bestIndex = -1;
    size_t bestLength = 0;
    if (!annotationCfi.empty()) {
        for (size_t i = 0; i < toc.size(); ++i) {
            const std::string chapterCfi = NormalizeCfiPrefix(toc[i].cfi);
            if (chapterCfi.empty() || chapterCfi.size() > annotationCfi.size()) continue;
            if (annotationCfi.compare(0, chapterCfi.size(), chapterCfi) == 0 &&
                chapterCfi.size() > bestLength) {
                bestLength = chapterCfi.size();
                bestIndex = static_cast<int>(i);
            }
        }
    }
    if (bestIndex >= 0) {
        outTitle = toc[static_cast<size_t>(bestIndex)].title;
        return bestIndex;
    }

    // href 匹配（去掉 #fragment 后比较）。
    const std::string annotationHref = StripFragment(position.href.empty() ? position.cfi : position.href);
    if (!annotationHref.empty()) {
        for (size_t i = 0; i < toc.size(); ++i) {
            if (toc[i].href.empty()) continue;
            if (EqualsNoCase(StripFragment(toc[i].href), annotationHref)) {
                outTitle = toc[i].title;
                return static_cast<int>(i);
            }
        }
    }

    // PDF 页码匹配：最后一个 page <= 标注页码的章节。
    if (position.page >= 1) {
        int pageIndex = -1;
        for (size_t i = 0; i < toc.size(); ++i) {
            if (toc[i].page >= 1 && toc[i].page <= position.page) {
                pageIndex = static_cast<int>(i);
            }
        }
        if (pageIndex >= 0) {
            outTitle = toc[static_cast<size_t>(pageIndex)].title;
            return pageIndex;
        }
    }
    return -1;
}

// ── 条目模型 ─────────────────────────────────────────────────────────
struct AttachedNote {
    long long id = 0;
    std::string text;
    std::string createdAt;
    std::string updatedAt;
};

struct Entry {
    std::string type;   // highlight / note / bookmark
    long long id = 0;
    std::string text;   // 高亮原文
    std::string note;   // 高亮附注 / 独立笔记正文 / 书签备注
    std::string title;  // 书签标题
    std::string color;
    std::vector<std::string> tags;
    std::string createdAt;
    std::string updatedAt;
    Position position;
    int chapterIndex = -1;
    std::string chapterTitle;
    std::vector<AttachedNote> attached;
};

struct ChapterGroup {
    int index = -1;
    std::string title;
    std::vector<Entry> entries;
};

struct BookModel {
    long long id = 0;
    std::string title;
    std::string author;
    std::string format;
    std::string language;
    std::string publisher;
    std::string isbn;
    std::string addedAt;
    std::string sourcePath;
    std::string coverPath;
    std::vector<TocNode> toc;
    std::vector<std::string> syntheticChapters;
    std::vector<ChapterGroup> chapters;
    std::vector<Entry> entries;
    ExportStats stats;
};

int EntryTypeRank(const std::string& type)
{
    if (type == "bookmark") return 0;
    if (type == "highlight") return 1;
    if (type == "note") return 2;
    return 3;
}

bool EntryLess(const Entry& a, const Entry& b)
{
    const int orderA = (a.chapterIndex < 0) ? 1000000 : a.chapterIndex;
    const int orderB = (b.chapterIndex < 0) ? 1000000 : b.chapterIndex;
    if (orderA != orderB) return orderA < orderB;
    if (PositionLess(a.position, b.position)) return true;
    if (PositionLess(b.position, a.position)) return false;
    if (a.createdAt != b.createdAt) return a.createdAt < b.createdAt;
    if (a.type != b.type) return EntryTypeRank(a.type) < EntryTypeRank(b.type);
    return a.id < b.id;
}

std::vector<std::string> ParseTags(const json& row)
{
    std::vector<std::string> tags;
    const json* value = FindKey(row, "tags");
    if (!value) return tags;
    if (value->is_array()) {
        for (const json& item : *value) {
            if (!item.is_string()) continue;
            const std::string tag = TrimAscii(item.get<std::string>());
            if (!tag.empty()) tags.push_back(tag);
        }
    } else if (value->is_string()) {
        const std::string raw = value->get<std::string>();
        size_t start = 0;
        for (;;) {
            const size_t end = raw.find_first_of(",;", start);
            const std::string tag = TrimAscii(raw.substr(start, (end == std::string::npos) ? std::string::npos : end - start));
            if (!tag.empty()) tags.push_back(tag);
            if (end == std::string::npos) break;
            start = end + 1;
        }
    }
    // 去重（保持首次出现顺序）
    std::vector<std::string> unique;
    for (const std::string& tag : tags) {
        bool duplicate = false;
        for (const std::string& existing : unique) {
            if (existing == tag) { duplicate = true; break; }
        }
        if (!duplicate) unique.push_back(tag);
    }
    return unique;
}

long long RowId(const json& row)
{
    long long id = 0;
    GetIntAny(row, { "id" }, id);
    return id < 0 ? 0 : id;
}

std::string RowCreatedAt(const json& row)
{
    return TrimAscii(GetStringAny(row, { "created_at", "createdAt", "created", "time", "timestamp" }));
}

std::string RowUpdatedAt(const json& row)
{
    return TrimAscii(GetStringAny(row, { "updated_at", "updatedAt", "updated" }));
}

void AddIssue(std::vector<ExportIssue>& issues, const std::string& scope, const char* code,
              const std::string& message, ExportSeverity severity = ExportSeverity::Warning)
{
    ExportIssue issue;
    issue.scope = scope;
    issue.code = code;
    issue.message = message;
    issue.severity = severity;
    issues.push_back(issue);
}

std::string ScopeOf(const char* table, size_t index)
{
    return std::string(table) + "[" + std::to_string(index) + "]";
}

// 从 book 行取白名单字段（不整包透传，避免把 Key / 路径带出去）。
void FillBookMeta(BookModel& model, const json& book, std::vector<ExportIssue>& issues)
{
    if (!book.is_object()) {
        AddIssue(issues, "book", "row", "book is not an object");
        return;
    }
    long long id = 0;
    if (GetIntAny(book, { "id" }, id)) model.id = id;
    model.title = TrimAscii(GetStringAny(book, { "title", "name" }));
    model.author = TrimAscii(GetStringAny(book, { "author", "creator" }));
    model.format = ToLowerAscii(TrimAscii(GetStringAny(book, { "format", "type", "ext" })));
    model.language = TrimAscii(GetStringAny(book, { "language", "lang" }));
    model.publisher = TrimAscii(GetStringAny(book, { "publisher" }));
    model.isbn = TrimAscii(GetStringAny(book, { "isbn" }));
    model.addedAt = TrimAscii(GetStringAny(book, { "added_at", "addedAt" }));
    model.sourcePath = TrimAscii(GetStringAny(book, { "file_path", "filePath", "path" }));
    model.coverPath = TrimAscii(GetStringAny(book, { "cover_path", "coverPath" }));
    if (model.title.empty()) {
        AddIssue(issues, "book", "title", "book has no title, using fallback");
    }
}

const json& ArrayOf(const json& value)
{
    static const json kEmpty = json::array();
    return value.is_array() ? value : kEmpty;
}

void AssignEntryChapter(Entry& entry, const json& row, BookModel& model)
{
    std::string title;
    entry.chapterIndex = ResolveChapterIndex(row, entry.position, model.toc,
                                             model.syntheticChapters, title);
    entry.chapterTitle = title;
}

void BuildEntries(BookModel& model, const ExportBook& input, const ExportOptions& options,
                  std::vector<ExportIssue>& issues)
{
    const json& highlights = ArrayOf(input.highlights);
    const json& notes = ArrayOf(input.notes);
    const json& bookmarks = ArrayOf(input.bookmarks);

    std::vector<Entry> highlightsEntries;
    highlightsEntries.reserve(highlights.size());

    if (options.includeHighlights) {
        size_t rowIndex = 0;
        for (const json& row : highlights) {
            const std::string scope = ScopeOf("highlights", rowIndex++);
            if (!row.is_object()) { AddIssue(issues, scope, "row", "highlight row is not an object"); continue; }
            const std::string text = CleanText(GetStringAny(row, { "text", "quote", "content" }), true);
            const std::string note = CleanText(GetStringAny(row, { "note" }), true);
            if (TrimAscii(text).empty() && TrimAscii(note).empty()) {
                AddIssue(issues, scope, "skip", "highlight has no text and no note");
                continue;
            }
            Entry entry;
            entry.type = "highlight";
            entry.id = RowId(row);
            entry.text = TrimAscii(text);
            entry.note = TrimAscii(note);
            entry.color = TrimAscii(GetStringAny(row, { "color" }));
            entry.tags = options.includeTags ? ParseTags(row) : std::vector<std::string>();
            entry.createdAt = RowCreatedAt(row);
            entry.updatedAt = RowUpdatedAt(row);
            entry.position = ParsePosition(row);
            AssignEntryChapter(entry, row, model);
            highlightsEntries.push_back(std::move(entry));
        }
    }

    std::vector<Entry>& allEntries = model.entries;
    allEntries = std::move(highlightsEntries);

    // 笔记：highlight_id 命中高亮 → 挂到该高亮上（不单独成条目）；
    // 否则作为独立条目。全部计入 notes 统计。
    if (options.includeNotes) {
        size_t rowIndex = 0;
        for (const json& row : notes) {
            const std::string scope = ScopeOf("notes", rowIndex++);
            if (!row.is_object()) { AddIssue(issues, scope, "row", "note row is not an object"); continue; }
            const std::string content = TrimAscii(CleanText(GetStringAny(row, { "content", "text", "note" }), true));
            if (content.empty()) {
                AddIssue(issues, scope, "skip", "note has no content");
                continue;
            }
            long long highlightId = 0;
            GetIntAny(row, { "highlight_id", "highlightId" }, highlightId);
            Entry* target = nullptr;
            if (highlightId > 0) {
                for (Entry& entry : allEntries) {
                    if (entry.type == "highlight" && entry.id == highlightId) { target = &entry; break; }
                }
            }
            if (target) {
                AttachedNote attached;
                attached.id = RowId(row);
                attached.text = content;
                attached.createdAt = RowCreatedAt(row);
                attached.updatedAt = RowUpdatedAt(row);
                target->attached.push_back(std::move(attached));
                model.stats.notes += 1;
                model.stats.attachedNotes += 1;
                continue;
            }
            Entry entry;
            entry.type = "note";
            entry.id = RowId(row);
            entry.note = content;
            entry.createdAt = RowCreatedAt(row);
            entry.updatedAt = RowUpdatedAt(row);
            entry.position = ParsePosition(row);
            entry.tags = options.includeTags ? ParseTags(row) : std::vector<std::string>();
            AssignEntryChapter(entry, row, model);
            allEntries.push_back(std::move(entry));
            model.stats.notes += 1;
        }
    }

    if (options.includeBookmarks) {
        size_t rowIndex = 0;
        for (const json& row : bookmarks) {
            const std::string scope = ScopeOf("bookmarks", rowIndex++);
            if (!row.is_object()) { AddIssue(issues, scope, "row", "bookmark row is not an object"); continue; }
            Entry entry;
            entry.type = "bookmark";
            entry.id = RowId(row);
            entry.title = TrimAscii(CleanText(GetStringAny(row, { "title" }), false));
            entry.note = TrimAscii(CleanText(GetStringAny(row, { "note", "content" }), true));
            entry.createdAt = RowCreatedAt(row);
            entry.updatedAt = RowUpdatedAt(row);
            entry.position = ParsePosition(row);
            AssignEntryChapter(entry, row, model);
            if (entry.title.empty() && entry.note.empty() && entry.position.display.empty()) {
                AddIssue(issues, scope, "skip", "bookmark has no title, note or position");
                continue;
            }
            if (entry.title.empty() && !entry.position.display.empty()) {
                entry.title = entry.position.display;   // 无标题书签用位置当标题
            }
            allEntries.push_back(std::move(entry));
        }
    }

    // 统计高亮/书签数（笔记已在上面按行统计）。
    for (const Entry& entry : allEntries) {
        if (entry.type == "highlight") model.stats.highlights += 1;
        else if (entry.type == "bookmark") model.stats.bookmarks += 1;
    }
}

void AssignChaptersAndSort(BookModel& model)
{
    std::stable_sort(model.entries.begin(), model.entries.end(), EntryLess);

    // 章节分组：先 TOC 顺序，再合成章节（首次出现顺序），最后未分章。
    std::vector<ChapterGroup> groups;
    groups.reserve(model.toc.size() + model.syntheticChapters.size() + 1);
    for (size_t i = 0; i < model.toc.size(); ++i) {
        ChapterGroup group;
        group.index = static_cast<int>(i);
        group.title = model.toc[i].title;
        groups.push_back(std::move(group));
    }
    for (size_t i = 0; i < model.syntheticChapters.size(); ++i) {
        ChapterGroup group;
        group.index = static_cast<int>(model.toc.size() + i);
        group.title = model.syntheticChapters[i];
        groups.push_back(std::move(group));
    }
    ChapterGroup unassigned;
    unassigned.index = -1;
    for (const Entry& entry : model.entries) {
        if (entry.chapterIndex < 0) {
            unassigned.entries.push_back(entry);
            continue;
        }
        for (ChapterGroup& group : groups) {
            if (group.index == entry.chapterIndex) {
                group.entries.push_back(entry);
                break;
            }
        }
    }
    if (!unassigned.entries.empty()) groups.push_back(std::move(unassigned));
    // 只保留有条目的章节（空 TOC 章节不渲染，目录才不会一堆空标题）。
    model.chapters.clear();
    for (ChapterGroup& group : groups) {
        if (!group.entries.empty()) model.chapters.push_back(std::move(group));
    }
    model.stats.entries = static_cast<int>(model.entries.size());
}

} // namespace

// ═════════════════════════ 模型构建 ═════════════════════════════════
namespace {

std::string BookDisplayTitle(const BookModel& book)
{
    return book.title.empty() ? std::string("未命名书籍") : book.title;
}

std::string CountSummary(const ExportStats& stats)
{
    return std::to_string(stats.highlights) + " 条高亮 · " +
           std::to_string(stats.notes) + " 条笔记 · " +
           std::to_string(stats.bookmarks) + " 个书签";
}

std::string ChapterTitleText(const ChapterGroup& chapter)
{
    return chapter.title.empty() ? std::string("未分章") : chapter.title;
}

BookModel BuildBook(const ExportBook& input, const ExportOptions& options,
                    std::vector<ExportIssue>& issues)
{
    BookModel model;
    FillBookMeta(model, input.book, issues);
    if (!input.bookmarks.is_array() && !input.bookmarks.is_null()) {
        AddIssue(issues, "bookmarks", "row", "bookmarks is not an array");
    }
    if (!input.highlights.is_array() && !input.highlights.is_null()) {
        AddIssue(issues, "highlights", "row", "highlights is not an array");
    }
    if (!input.notes.is_array() && !input.notes.is_null()) {
        AddIssue(issues, "notes", "row", "notes is not an array");
    }
    model.toc = ParseToc(input, issues);
    BuildEntries(model, input, options, issues);
    AssignChaptersAndSort(model);
    model.stats.books = 1;
    return model;
}

// ── Markdown ─────────────────────────────────────────────────────────
std::vector<std::string> EntryMetaLines(const Entry& entry, const ExportOptions& options)
{
    std::vector<std::string> lines;
    if (entry.type == "highlight" && !entry.color.empty()) {
        lines.push_back("- 颜色：" + MdInline(entry.color, options));
    }
    if (!entry.position.display.empty()) {
        lines.push_back("- 位置：" + MdInline(entry.position.display, options));
    }
    if (!entry.createdAt.empty()) {
        lines.push_back("- 时间：" + MdInline(entry.createdAt, options));
    }
    if (options.includeTags && !entry.tags.empty()) {
        std::vector<std::string> tags;
        tags.reserve(entry.tags.size());
        for (const std::string& tag : entry.tags) tags.push_back(MdInline(tag, options));
        lines.push_back("- 标签：" + JoinLines(tags, ", "));
    }
    if (entry.type != "note" && !entry.note.empty()) {
        lines.push_back("- 笔记：" + IndentContinuation(MdBlock(entry.note, options)));
    }
    for (const AttachedNote& note : entry.attached) {
        lines.push_back("- 笔记：" + IndentContinuation(MdBlock(note.text, options)));
    }
    return lines;
}

std::string RenderEntryMarkdown(const Entry& entry, const ExportOptions& options)
{
    std::string head;
    if (entry.type == "highlight") {
        if (options.includeQuote && !entry.text.empty()) {
            head = PrefixLines(MdBlock(entry.text, options), "> ", ">");
        }
    } else if (entry.type == "bookmark") {
        head = "**书签**";
        if (!entry.title.empty()) head += "：" + MdInline(entry.title, options);
    } else {
        head = "**笔记**：" + IndentContinuation(MdBlock(entry.note, options));
    }
    const std::vector<std::string> meta = EntryMetaLines(entry, options);
    std::string block = head;
    if (!meta.empty()) {
        if (!block.empty()) block += "\n\n";
        block += JoinLines(meta, "\n");
    }
    return block;
}

std::string ChapterTocMarkdown(const BookModel& book, const ExportOptions& options,
                               const std::string& anchorPrefix)
{
    if (!options.markdownAnchors || book.chapters.size() < 2) return std::string();
    std::vector<std::string> lines;
    lines.reserve(book.chapters.size());
    for (size_t i = 0; i < book.chapters.size(); ++i) {
        lines.push_back("- [" + MdInline(ChapterTitleText(book.chapters[i]), options) +
                        "](#" + anchorPrefix + std::to_string(i + 1) + ")");
    }
    return "## 目录\n\n" + JoinLines(lines, "\n");
}

void AppendBookBodyMarkdown(std::string& out, const BookModel& book, const ExportOptions& options,
                            int chapterHeadingLevel, const std::string& anchorPrefix)
{
    for (size_t i = 0; i < book.chapters.size(); ++i) {
        const ChapterGroup& chapter = book.chapters[i];
        std::string head;
        if (options.markdownAnchors) {
            head += "<a id=\"" + anchorPrefix + std::to_string(i + 1) + "\"></a>\n";
        }
        head += std::string(static_cast<size_t>(chapterHeadingLevel), '#') + " " +
                MdInline(ChapterTitleText(chapter), options);
        AppendBlock(out, head);
        for (const Entry& entry : chapter.entries) {
            const std::string rendered = RenderEntryMarkdown(entry, options);
            if (!rendered.empty()) AppendBlock(out, rendered);
        }
    }
}

std::string RenderSingleBookMarkdown(const BookModel& book, const ExportOptions& options,
                                     const std::string& exportedAt)
{
    std::string out;
    if (options.includeFrontMatter) {
        AppendBlock(out, "# " + MdInline(BookDisplayTitle(book), options) + " — 标注导出");
        std::vector<std::string> meta;
        if (!book.author.empty()) meta.push_back("- 作者：" + MdInline(book.author, options));
        if (!book.format.empty()) meta.push_back("- 格式：" + ToUpperAscii(book.format));
        if (!book.language.empty()) meta.push_back("- 语言：" + MdInline(book.language, options));
        if (options.includeSourcePath && !book.sourcePath.empty()) {
            meta.push_back("- 源文件：" + MdInline(book.sourcePath, options));
        }
        meta.push_back("- 标注：" + CountSummary(book.stats));
        if (!exportedAt.empty()) meta.push_back("- 导出时间：" + MdInline(exportedAt, options));
        AppendBlock(out, JoinLines(meta, "\n"));

        const std::string toc = ChapterTocMarkdown(book, options, "ch-");
        if (!toc.empty()) AppendBlock(out, toc);
    }

    AppendBookBodyMarkdown(out, book, options, 2, "ch-");
    if (book.chapters.empty()) AppendBlock(out, "（没有可导出的标注）");
    return out + "\n";
}

std::string RenderLibraryMarkdown(const std::vector<BookModel>& books, const ExportOptions& options,
                                  const std::string& exportedAt)
{
    std::string out;
    AppendBlock(out, "# ParticleBook 标注导出");

    if (options.includeFrontMatter) {
        ExportStats total;
        for (const BookModel& book : books) {
            total.bookmarks += book.stats.bookmarks;
            total.highlights += book.stats.highlights;
            total.notes += book.stats.notes;
            total.attachedNotes += book.stats.attachedNotes;
            total.entries += book.stats.entries;
        }
        std::vector<std::string> meta;
        meta.push_back("- 书籍：" + std::to_string(books.size()) + " 本");
        meta.push_back("- 标注：" + CountSummary(total));
        if (!exportedAt.empty()) meta.push_back("- 导出时间：" + MdInline(exportedAt, options));
        AppendBlock(out, JoinLines(meta, "\n"));

        if (books.size() >= 2 && options.markdownAnchors) {
            std::vector<std::string> lines;
            lines.reserve(books.size());
            for (size_t i = 0; i < books.size(); ++i) {
                std::string line = "- [" + MdInline(BookDisplayTitle(books[i]), options) +
                                   "](#book-" + std::to_string(i + 1) + ")";
                if (!books[i].author.empty()) line += " — " + MdInline(books[i].author, options);
                lines.push_back(std::move(line));
            }
            AppendBlock(out, "## 目录\n\n" + JoinLines(lines, "\n"));
        }
    }

    for (size_t i = 0; i < books.size(); ++i) {
        const BookModel& book = books[i];
        const std::string bookNumber = std::to_string(i + 1);
        std::string head;
        if (options.markdownAnchors) head += "<a id=\"book-" + bookNumber + "\"></a>\n";
        head += "## " + MdInline(BookDisplayTitle(book), options);
        AppendBlock(out, head);

        std::vector<std::string> meta;
        if (!book.author.empty()) meta.push_back("- 作者：" + MdInline(book.author, options));
        if (!book.format.empty()) meta.push_back("- 格式：" + ToUpperAscii(book.format));
        if (options.includeSourcePath && !book.sourcePath.empty()) {
            meta.push_back("- 源文件：" + MdInline(book.sourcePath, options));
        }
        meta.push_back("- 标注：" + CountSummary(book.stats));
        AppendBlock(out, JoinLines(meta, "\n"));

        AppendBookBodyMarkdown(out, book, options, 3, "b" + bookNumber + "-ch-");
        if (book.chapters.empty()) AppendBlock(out, "（没有可导出的标注）");
    }
    if (books.empty()) AppendBlock(out, "（没有可导出的标注）");
    return out + "\n";
}

// ── CSV ──────────────────────────────────────────────────────────────
std::string CsvCell(const std::string& raw, const ExportOptions& options)
{
    std::string value = CleanText(raw, true);
    if (options.csvFormulaGuard && !value.empty()) {
        const char first = value.front();
        if (first == '=' || first == '+' || first == '-' || first == '@' ||
            first == '\t' || first == '\r') {
            value.insert(value.begin(), '\'');
        }
    }
    bool needsQuote = false;
    for (char c : value) {
        if (c == ',' || c == '"' || c == '\n' || c == '\r') { needsQuote = true; break; }
    }
    if (!needsQuote && !value.empty()) {
        if (value.front() == ' ' || value.back() == ' ') needsQuote = true;
    }
    if (!needsQuote) return value;

    std::string out;
    out.reserve(value.size() + 2);
    out.push_back('"');
    for (char c : value) {
        if (c == '"') out += "\"\"";
        else out.push_back(c);
    }
    out.push_back('"');
    return out;
}

std::string EntryNoteCell(const Entry& entry)
{
    std::string note = entry.note;
    for (const AttachedNote& attached : entry.attached) {
        if (!note.empty()) note += "\n";
        note += attached.text;
    }
    return note;
}

std::string RenderCsv(const std::vector<BookModel>& books, const ExportOptions& options)
{
    static const char* kHeader =
        "book_id,book_title,author,format,type,chapter,position,page,progress,cfi,color,text,note,tags,created_at,updated_at,id,source";
    std::string out;
    if (options.csvBom) out += "\xEF\xBB\xBF";
    out += kHeader;
    out += "\r\n";

    for (const BookModel& book : books) {
        const std::string bookId = (book.id > 0) ? std::to_string(book.id) : std::string();
        const std::string bookTitle = BookDisplayTitle(book);
        const std::string source = options.includeSourcePath ? book.sourcePath : std::string();
        for (const Entry& entry : book.entries) {
            std::vector<std::string> cells;
            cells.reserve(18);
            cells.push_back(CsvCell(bookId, options));
            cells.push_back(CsvCell(bookTitle, options));
            cells.push_back(CsvCell(book.author, options));
            cells.push_back(CsvCell(book.format, options));
            cells.push_back(CsvCell(entry.type, options));
            cells.push_back(CsvCell(entry.chapterTitle, options));
            cells.push_back(CsvCell(entry.position.display, options));
            cells.push_back(CsvCell(entry.position.page >= 1 ? std::to_string(entry.position.page) : std::string(), options));
            cells.push_back(CsvCell(entry.position.progress >= 0.0 ? FormatNumber(entry.position.progress) : std::string(), options));
            cells.push_back(CsvCell(entry.position.cfi, options));
            cells.push_back(CsvCell(entry.color, options));
            cells.push_back(CsvCell(entry.text, options));
            cells.push_back(CsvCell(EntryNoteCell(entry), options));
            std::string tags;
            for (size_t i = 0; i < entry.tags.size(); ++i) {
                if (i) tags += "; ";
                tags += entry.tags[i];
            }
            cells.push_back(CsvCell(tags, options));
            cells.push_back(CsvCell(entry.createdAt, options));
            cells.push_back(CsvCell(entry.updatedAt, options));
            cells.push_back(CsvCell(entry.id > 0 ? std::to_string(entry.id) : std::string(), options));
            cells.push_back(CsvCell(source, options));
            out += JoinLines(cells, ",");
            out += "\r\n";
        }
    }
    return out;
}

// ── JSON ─────────────────────────────────────────────────────────────
json EntryToJson(const Entry& entry, const ExportOptions& options)
{
    json out;
    out["type"] = entry.type;
    out["id"] = entry.id;
    out["chapterIndex"] = entry.chapterIndex;
    out["chapterTitle"] = entry.chapterTitle;

    json position;
    position["kind"] = entry.position.kind.empty() ? std::string("unknown") : entry.position.kind;
    position["display"] = entry.position.display;
    position["cfi"] = entry.position.cfi;
    position["href"] = entry.position.href;
    position["page"] = (entry.position.page >= 1) ? json(entry.position.page) : json(nullptr);
    position["progress"] = (entry.position.progress >= 0.0) ? json(entry.position.progress) : json(nullptr);
    position["offset"] = (entry.position.offset >= 0) ? json(entry.position.offset) : json(nullptr);
    out["position"] = std::move(position);

    out["text"] = entry.text;
    out["note"] = entry.note;
    out["title"] = entry.title;
    out["color"] = entry.color;
    out["tags"] = options.includeTags ? json(entry.tags) : json::array();
    out["createdAt"] = entry.createdAt;
    out["updatedAt"] = entry.updatedAt;

    json notes = json::array();
    for (const AttachedNote& note : entry.attached) {
        json item;
        item["id"] = note.id;
        item["text"] = note.text;
        item["createdAt"] = note.createdAt;
        item["updatedAt"] = note.updatedAt;
        notes.push_back(std::move(item));
    }
    out["notes"] = std::move(notes);
    return out;
}

json BookToJson(const BookModel& book, const ExportOptions& options)
{
    json out;
    out["id"] = book.id;
    out["title"] = BookDisplayTitle(book);
    out["author"] = book.author;
    out["format"] = book.format;
    out["language"] = book.language;
    out["publisher"] = book.publisher;
    out["isbn"] = book.isbn;
    out["source"] = options.includeSourcePath ? book.sourcePath : std::string();

    json counts;
    counts["bookmarks"] = book.stats.bookmarks;
    counts["highlights"] = book.stats.highlights;
    counts["notes"] = book.stats.notes;
    counts["attachedNotes"] = book.stats.attachedNotes;
    counts["entries"] = book.stats.entries;
    out["counts"] = std::move(counts);

    json chapters = json::array();
    for (const ChapterGroup& chapter : book.chapters) {
        json item;
        item["index"] = chapter.index;
        item["title"] = ChapterTitleText(chapter);
        json entries = json::array();
        for (const Entry& entry : chapter.entries) entries.push_back(EntryToJson(entry, options));
        item["entries"] = std::move(entries);
        chapters.push_back(std::move(item));
    }
    out["chapters"] = std::move(chapters);

    json entries = json::array();
    for (const Entry& entry : book.entries) entries.push_back(EntryToJson(entry, options));
    out["entries"] = std::move(entries);
    return out;
}

std::string RenderJson(const std::vector<BookModel>& books, const ExportOptions& options,
                       const std::string& exportedAt)
{
    json root;
    root["schema"] = "particlebook.export";
    root["version"] = kExportSchemaVersion;
    root["exportedAt"] = exportedAt;

    ExportStats total;
    total.books = static_cast<int>(books.size());
    for (const BookModel& book : books) {
        total.bookmarks += book.stats.bookmarks;
        total.highlights += book.stats.highlights;
        total.notes += book.stats.notes;
        total.attachedNotes += book.stats.attachedNotes;
        total.entries += book.stats.entries;
    }
    json counts;
    counts["books"] = total.books;
    counts["bookmarks"] = total.bookmarks;
    counts["highlights"] = total.highlights;
    counts["notes"] = total.notes;
    counts["attachedNotes"] = total.attachedNotes;
    counts["entries"] = total.entries;
    root["counts"] = std::move(counts);

    json booksJson = json::array();
    for (const BookModel& book : books) booksJson.push_back(BookToJson(book, options));
    root["books"] = std::move(booksJson);
    return root.dump(2) + "\n";
}

// ── 文件名 ───────────────────────────────────────────────────────────
std::string ExtractDateDigits(const std::string& isoTimestamp)
{
    if (isoTimestamp.size() >= 10 && isoTimestamp[4] == '-' && isoTimestamp[7] == '-') {
        std::string digits;
        const int positions[] = { 0, 1, 2, 3, 5, 6, 8, 9 };
        for (int position : positions) {
            const char c = isoTimestamp[static_cast<size_t>(position)];
            if (c < '0' || c > '9') return std::string();
            digits.push_back(c);
        }
        return digits;
    }
    return std::string();
}

std::string FilenameDate(const std::string& exportedAt)
{
    std::string digits = ExtractDateDigits(exportedAt);
    if (!digits.empty()) return digits;
    digits = ExtractDateDigits(ExportService::NowIso8601Utc());
    if (!digits.empty()) return digits;
    return "00000000";
}

} // namespace
// ═════════════════════════ 格式 / 公共方法 ═════════════════════════
namespace {

bool AsciiEqualNoCase(std::string_view text, const char* lower)
{
    const size_t n = std::strlen(lower);
    if (text.size() != n) return false;
    for (size_t i = 0; i < n; ++i) {
        if (LowerAscii(text[i]) != lower[i]) return false;
    }
    return true;
}

std::string_view TrimView(const std::string& text)
{
    size_t begin = 0;
    size_t end = text.size();
    while (begin < end && IsSpaceAscii(text[begin])) ++begin;
    while (end > begin && IsSpaceAscii(text[end - 1])) --end;
    return std::string_view(text.data() + begin, end - begin);
}

} // namespace

const char* ExportFormatName(ExportFormat format) noexcept
{
    switch (format) {
    case ExportFormat::Csv: return "csv";
    case ExportFormat::Json: return "json";
    case ExportFormat::Markdown: return "markdown";
    }
    return "markdown";
}

const char* ExportFileExtension(ExportFormat format) noexcept
{
    switch (format) {
    case ExportFormat::Csv: return ".csv";
    case ExportFormat::Json: return ".json";
    case ExportFormat::Markdown: return ".md";
    }
    return ".md";
}

const char* ExportMimeType(ExportFormat format) noexcept
{
    switch (format) {
    case ExportFormat::Csv: return "text/csv; charset=utf-8";
    case ExportFormat::Json: return "application/json; charset=utf-8";
    case ExportFormat::Markdown: return "text/markdown; charset=utf-8";
    }
    return "text/markdown; charset=utf-8";
}

bool ParseExportFormat(const std::string& text, ExportFormat& out) noexcept
{
    const std::string_view value = TrimView(text);
    if (AsciiEqualNoCase(value, "md") || AsciiEqualNoCase(value, "markdown") ||
        AsciiEqualNoCase(value, "mdown")) {
        out = ExportFormat::Markdown;
        return true;
    }
    if (AsciiEqualNoCase(value, "csv") || AsciiEqualNoCase(value, "text") ||
        AsciiEqualNoCase(value, "txt")) {
        out = ExportFormat::Csv;
        return true;
    }
    if (AsciiEqualNoCase(value, "json")) {
        out = ExportFormat::Json;
        return true;
    }
    return false;
}

bool ExportResult::HasErrors() const
{
    for (const ExportIssue& issue : issues) {
        if (issue.severity == ExportSeverity::Error) return true;
    }
    return false;
}

std::string ExportService::NowIso8601Utc()
{
    const std::time_t now = std::time(nullptr);
    std::tm utc{};
    bool ok = false;
#if defined(_MSC_VER)
    ok = gmtime_s(&utc, &now) == 0;
#else
    ok = gmtime_r(&now, &utc) != nullptr;
#endif
    if (!ok) return std::string();
    char buffer[40];
    std::snprintf(buffer, sizeof buffer, "%04d-%02d-%02dT%02d:%02d:%02d.000Z",
                  utc.tm_year + 1900, utc.tm_mon + 1, utc.tm_mday,
                  utc.tm_hour, utc.tm_min, utc.tm_sec);
    return std::string(buffer);
}

std::string ExportService::SafeFileName(const std::string& name, const std::string& fallback,
                                        std::size_t maxBytes)
{
    const std::string cleaned = CleanText(name, false);
    std::string out;
    out.reserve(cleaned.size());
    for (char c : cleaned) {
        switch (c) {
        case '\\': case '/': case ':': case '*': case '?':
        case '"': case '<': case '>': case '|':
            out.push_back('_');
            break;
        case '\t': case '\r': case '\n':
            out.push_back(' ');
            break;
        default:
            out.push_back(c);
            break;
        }
    }
    out = TrimAscii(out);
    while (!out.empty() && (out.back() == '.' || out.back() == ' ')) out.pop_back();
    while (!out.empty() && (out.front() == '.' || out.front() == ' ')) out.erase(out.begin());
    if (out.empty()) return fallback;

    // Windows 设备名（CON / NUL / COM1 …）不能作为文件名。
    std::string base = out;
    const size_t dot = base.find('.');
    if (dot != std::string::npos) base.resize(dot);
    if (IsReservedDeviceName(base)) out = "_" + out;

    out = Utf8SafeTruncate(out, maxBytes);
    while (!out.empty() && (out.back() == '.' || out.back() == ' ')) out.pop_back();
    if (out.empty()) return fallback;
    return out;
}

std::string ExportService::SuggestedFileName(const pb::ExportBook& book, const ExportOptions& options)
{
    std::string title = options.titleOverride;
    if (title.empty()) title = TrimAscii(GetStringAny(book.book, { "title", "name" }));
    if (title.empty()) title = "未命名书籍";

    const std::string suffix = std::string("-标注-") + FilenameDate(options.exportedAt) +
                               ExportFileExtension(options.format);
    const std::size_t reserve = suffix.size() + 8;
    const std::size_t maxTitleBytes = (options.maxFileNameLength > reserve)
                                          ? options.maxFileNameLength - reserve
                                          : static_cast<std::size_t>(16);
    const std::string safeTitle = SafeFileName(title, "未命名书籍", maxTitleBytes);
    return SafeFileName(safeTitle + suffix, std::string("export") + suffix, options.maxFileNameLength);
}

std::string ExportService::LibraryFileName(const ExportOptions& options)
{
    const std::string name = std::string("ParticleBook-标注导出-") +
                             FilenameDate(options.exportedAt) +
                             ExportFileExtension(options.format);
    return SafeFileName(name, "ParticleBook-export", options.maxFileNameLength);
}

std::vector<pb::ExportBook> ExportService::GroupByBook(const json& books, const json& bookmarks,
                                                   const json& highlights, const json& notes,
                                                   std::vector<ExportIssue>* issues)
{
    std::vector<pb::ExportBook> out;
    std::vector<long long> ids;

    if (books.is_array()) {
        for (size_t i = 0; i < books.size(); ++i) {
            const json& row = books[i];
            if (!row.is_object()) {
                if (issues) AddIssue(*issues, ScopeOf("books", i), "row", "book row is not an object");
                continue;
            }
            long long id = 0;
            GetIntAny(row, { "id" }, id);
            pb::ExportBook book;
            book.book = row;
            out.push_back(std::move(book));
            ids.push_back(id);
        }
    } else if (issues) {
        AddIssue(*issues, "books", "row", "books is not an array");
    }

    auto indexOfBook = [&ids](long long bookId) -> int {
        if (bookId <= 0) return -1;
        for (size_t i = 0; i < ids.size(); ++i) {
            if (ids[i] == bookId) return static_cast<int>(i);
        }
        return -1;
    };
    auto attachRows = [&](const json& rows, const char* table, json pb::ExportBook::*member) {
        if (rows.is_null()) return;
        if (!rows.is_array()) {
            if (issues) AddIssue(*issues, table, "row", std::string(table) + " is not an array");
            return;
        }
        for (size_t i = 0; i < rows.size(); ++i) {
            const json& row = rows[i];
            if (!row.is_object()) {
                if (issues) AddIssue(*issues, ScopeOf(table, i), "row", "annotation row is not an object");
                continue;
            }
            long long bookId = 0;
            GetIntAny(row, { "book_id", "bookId" }, bookId);
            const int index = indexOfBook(bookId);
            if (index < 0) {
                if (issues) {
                    AddIssue(*issues, ScopeOf(table, i), "skip", "annotation references an unknown book_id");
                }
                continue;
            }
            out[static_cast<size_t>(index)].*member += row;
        }
    };

    attachRows(bookmarks, "bookmarks", &pb::ExportBook::bookmarks);
    attachRows(highlights, "highlights", &pb::ExportBook::highlights);
    attachRows(notes, "notes", &pb::ExportBook::notes);
    return out;
}

ExportResult ExportService::ExportBook(const pb::ExportBook& book, const ExportOptions& options)
{
    ExportResult result;
    result.format = options.format;
    result.mimeType = ExportMimeType(options.format);
    const std::string exportedAt = options.exportedAt.empty() ? NowIso8601Utc() : options.exportedAt;

    BookModel model = BuildBook(book, options, result.issues);
    if (!options.titleOverride.empty()) model.title = options.titleOverride;
    model.stats.books = 1;
    result.stats = model.stats;

    std::vector<BookModel> books;
    books.push_back(std::move(model));

    switch (options.format) {
    case ExportFormat::Csv:
        result.content = RenderCsv(books, options);
        break;
    case ExportFormat::Json:
        result.content = RenderJson(books, options, exportedAt);
        break;
    case ExportFormat::Markdown:
    default:
        result.content = RenderSingleBookMarkdown(books.front(), options, exportedAt);
        break;
    }
    result.fileName = SuggestedFileName(book, options);
    result.ok = true;
    return result;
}

ExportResult ExportService::ExportLibrary(const std::vector<pb::ExportBook>& books,
                                          const ExportOptions& options)
{
    ExportResult result;
    result.format = options.format;
    result.mimeType = ExportMimeType(options.format);
    const std::string exportedAt = options.exportedAt.empty() ? NowIso8601Utc() : options.exportedAt;

    std::vector<BookModel> models;
    for (const pb::ExportBook& book : books) {
        BookModel model = BuildBook(book, options, result.issues);
        if (model.entries.empty() && !options.includeEmptyBooks) continue;
        models.push_back(std::move(model));
    }
    std::stable_sort(models.begin(), models.end(), [](const BookModel& a, const BookModel& b) {
        const std::string titleA = ToLowerAscii(BookDisplayTitle(a));
        const std::string titleB = ToLowerAscii(BookDisplayTitle(b));
        if (titleA != titleB) return titleA < titleB;
        return a.id < b.id;
    });

    ExportStats total;
    total.books = static_cast<int>(models.size());
    for (const BookModel& model : models) {
        total.bookmarks += model.stats.bookmarks;
        total.highlights += model.stats.highlights;
        total.notes += model.stats.notes;
        total.attachedNotes += model.stats.attachedNotes;
        total.entries += model.stats.entries;
    }
    result.stats = total;

    switch (options.format) {
    case ExportFormat::Csv:
        result.content = RenderCsv(models, options);
        break;
    case ExportFormat::Json:
        result.content = RenderJson(models, options, exportedAt);
        break;
    case ExportFormat::Markdown:
    default:
        result.content = RenderLibraryMarkdown(models, options, exportedAt);
        break;
    }
    result.fileName = LibraryFileName(options);
    result.ok = true;
    return result;
}

} // namespace pb
