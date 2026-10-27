// ── PB-006（A）：SearchIndexService 实现 ─────────────────────────────
// 见 services/SearchIndexService.h 的契约说明。只依赖 std + nlohmann/json +
// 已有的 utils/ZipReader（EPUB 解压，主 target 已编译）+ utils/encoding.h
// （非 UTF-8 文本回退系统 ANSI 解码），不碰数据库 / GUI / 窗口消息。
//
// 内部结构：
//   1. UTF-8 编解码 / 字符分类 / 归一化（正文与 token 的唯一口径）；
//   2. HTML / Markdown → 纯文本；
//   3. 文本抽取（EPUB zip + OPF spine / TXT / HTML / MD；PDF 明确跳过）；
//   4. 倒排索引（每本书 token→位置，全局 token→书→位置指针）；
//   5. 查询解析（英文词 AND + 中文 bigram 连续性）、打分、片段；
//   6. JSON 持久化（.tmp + rename，失败回退删除再 rename）。

#ifndef NOMINMAX
#define NOMINMAX
#endif

#include "services/SearchIndexService.h"

#include "utils/ZipReader.h"
#include "utils/encoding.h"  // ANSI 回退：MultiByteToWideChar + WideToUtf8

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

namespace pb {
namespace {

namespace fs = std::filesystem;

constexpr std::size_t kMaxErrors = 50;
constexpr std::size_t kMaxSnippetScanPositions = 1000;
constexpr std::size_t kMaxTermLengthBytes = 256;

// ── UTF-8 ────────────────────────────────────────────────────────────

void AppendUtf8(uint32_t cp, std::string& out)
{
    if (cp <= 0x7F) {
        out.push_back(static_cast<char>(cp));
    } else if (cp <= 0x7FF) {
        out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp <= 0xFFFF) {
        out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

std::string EncodeUtf8(const std::vector<uint32_t>& cps, std::size_t begin, std::size_t end)
{
    std::string out;
    out.reserve((end - begin) * 3);
    for (std::size_t i = begin; i < end && i < cps.size(); ++i) AppendUtf8(cps[i], out);
    return out;
}

// 严格校验 + 解码；失败（孤立续字节、超长编码、代理区、超过 U+10FFFF）返回 false。
bool DecodeUtf8(const std::string& text, std::vector<uint32_t>& out)
{
    out.clear();
    out.reserve(text.size());
    std::size_t i = 0;
    const std::size_t n = text.size();
    while (i < n) {
        const unsigned char lead = static_cast<unsigned char>(text[i]);
        uint32_t cp = 0;
        std::size_t extra = 0;
        if (lead < 0x80) {
            cp = lead;
            extra = 0;
        } else if ((lead & 0xE0) == 0xC0) {
            cp = lead & 0x1F;
            extra = 1;
        } else if ((lead & 0xF0) == 0xE0) {
            cp = lead & 0x0F;
            extra = 2;
        } else if ((lead & 0xF8) == 0xF0) {
            cp = lead & 0x07;
            extra = 3;
        } else {
            return false;
        }
        if (i + extra >= n && extra != 0) return false;
        for (std::size_t k = 1; k <= extra; ++k) {
            if (i + k >= n) return false;
            const unsigned char cont = static_cast<unsigned char>(text[i + k]);
            if ((cont & 0xC0) != 0x80) return false;
            cp = (cp << 6) | (cont & 0x3F);
        }
        if (extra == 1 && cp < 0x80) return false;
        if (extra == 2 && cp < 0x800) return false;
        if (extra == 3 && cp < 0x10000) return false;
        if (cp >= 0xD800 && cp <= 0xDFFF) return false;
        if (cp > 0x10FFFF) return false;
        out.push_back(cp);
        i += extra + 1;
    }
    return true;
}

bool IsValidUtf8(const std::string& text)
{
    std::vector<uint32_t> ignored;
    return DecodeUtf8(text, ignored);
}

std::size_t CountCodepoints(const std::string& text)
{
    std::vector<uint32_t> cps;
    if (!DecodeUtf8(text, cps)) return text.size();
    return cps.size();
}

std::string TruncateCodepoints(const std::string& text, std::size_t maxChars)
{
    if (maxChars == 0) return text;
    std::vector<uint32_t> cps;
    if (!DecodeUtf8(text, cps)) return text;
    if (cps.size() <= maxChars) return text;
    return EncodeUtf8(cps, 0, maxChars);
}

std::string DecodeUtf16(const std::string& bytes, bool littleEndian, bool& ok)
{
    ok = false;
    if (bytes.size() % 2 != 0) return std::string();
    std::vector<uint16_t> units;
    units.reserve(bytes.size() / 2);
    for (std::size_t i = 0; i + 1 < bytes.size(); i += 2) {
        const unsigned char a = static_cast<unsigned char>(bytes[i]);
        const unsigned char b = static_cast<unsigned char>(bytes[i + 1]);
        units.push_back(littleEndian ? static_cast<uint16_t>(a | (b << 8))
                                     : static_cast<uint16_t>((a << 8) | b));
    }
    std::string out;
    out.reserve(units.size() * 3);
    for (std::size_t i = 0; i < units.size(); ++i) {
        uint32_t cp = units[i];
        if (cp >= 0xD800 && cp <= 0xDBFF) {
            if (i + 1 >= units.size()) return std::string();
            const uint32_t low = units[++i];
            if (low < 0xDC00 || low > 0xDFFF) return std::string();
            cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
        } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
            return std::string();
        }
        AppendUtf8(cp, out);
    }
    ok = true;
    return out;
}

// 非 UTF-8 的旧中文 TXT 多半是系统 ANSI（GBK）；用 Win32 转宽再转 UTF-8。
std::string AnsiToUtf8(const std::string& bytes, bool& ok)
{
    ok = false;
    if (bytes.empty()) {
        ok = true;
        return std::string();
    }
    const int wlen = MultiByteToWideChar(CP_ACP, 0, bytes.data(),
                                         static_cast<int>(bytes.size()), nullptr, 0);
    if (wlen <= 0) return std::string();
    std::wstring wide(static_cast<std::size_t>(wlen), L'\0');
    MultiByteToWideChar(CP_ACP, 0, bytes.data(), static_cast<int>(bytes.size()), &wide[0], wlen);
    ok = true;
    return pb::WideToUtf8(wide);
}

// 统一解码：UTF-8 / UTF-8 BOM / UTF-16 BOM / ANSI 回退。
bool DecodeTextBytes(const std::string& bytes, std::string& out, std::string* error)
{
    std::string work = bytes;
    if (work.size() >= 3 && static_cast<unsigned char>(work[0]) == 0xEF
        && static_cast<unsigned char>(work[1]) == 0xBB
        && static_cast<unsigned char>(work[2]) == 0xBF) {
        work.erase(0, 3);
    } else if (work.size() >= 2 && static_cast<unsigned char>(work[0]) == 0xFF
               && static_cast<unsigned char>(work[1]) == 0xFE) {
        bool ok = false;
        std::string decoded = DecodeUtf16(work.substr(2), /*littleEndian=*/true, ok);
        if (!ok) {
            if (error) *error = search_index_codes::kUnsupportedEncoding;
            return false;
        }
        out = std::move(decoded);
        return true;
    } else if (work.size() >= 2 && static_cast<unsigned char>(work[0]) == 0xFE
               && static_cast<unsigned char>(work[1]) == 0xFF) {
        bool ok = false;
        std::string decoded = DecodeUtf16(work.substr(2), /*littleEndian=*/false, ok);
        if (!ok) {
            if (error) *error = search_index_codes::kUnsupportedEncoding;
            return false;
        }
        out = std::move(decoded);
        return true;
    }

    if (IsValidUtf8(work)) {
        out = std::move(work);
        return true;
    }

    bool ok = false;
    std::string decoded = AnsiToUtf8(work, ok);
    if (!ok) {
        if (error) *error = search_index_codes::kUnsupportedEncoding;
        return false;
    }
    out = std::move(decoded);
    return true;
}

// ── 字符分类 / token 规范化 ──────────────────────────────────────────

enum class CharClass {
    Separator,
    Word,
    Cjk
};

bool IsCjkCp(uint32_t cp)
{
    if (cp >= 0x3040 && cp <= 0x30FF) return true;   // 平假名 / 片假名
    if (cp >= 0x31F0 && cp <= 0x31FF) return true;   // 片假名扩展
    if (cp >= 0x3400 && cp <= 0x4DBF) return true;   // CJK 扩展 A
    if (cp >= 0x4E00 && cp <= 0x9FFF) return true;   // CJK 基本区
    if (cp >= 0xF900 && cp <= 0xFAFF) return true;   // CJK 兼容
    if (cp >= 0xAC00 && cp <= 0xD7AF) return true;   // 谚文音节
    if (cp >= 0x1100 && cp <= 0x11FF) return true;   // 谚文字母
    if (cp >= 0xFF66 && cp <= 0xFF9F) return true;   // 半角片假名
    return false;
}

bool IsCjkPunctuation(uint32_t cp)
{
    return (cp >= 0x3000 && cp <= 0x303F) || (cp >= 0x2E80 && cp <= 0x2EFF)
        || (cp >= 0xFE30 && cp <= 0xFE4F) || (cp >= 0xFE50 && cp <= 0xFE6F);
}

bool IsGeneralPunctuation(uint32_t cp)
{
    return (cp >= 0x2000 && cp <= 0x206F) || (cp >= 0x2E00 && cp <= 0x2E7F)
        || (cp >= 0x00A0 && cp <= 0x00BF) || (cp >= 0x2190 && cp <= 0x21FF)
        || (cp >= 0x2200 && cp <= 0x22FF) || (cp >= 0x25A0 && cp <= 0x25FF)
        || (cp >= 0x2600 && cp <= 0x27BF);
}

bool IsFullwidthAlnum(uint32_t cp)
{
    return (cp >= 0xFF10 && cp <= 0xFF19) || (cp >= 0xFF21 && cp <= 0xFF3A)
        || (cp >= 0xFF41 && cp <= 0xFF5A);
}

bool IsFullwidthPunct(uint32_t cp)
{
    if (cp < 0xFF00 || cp > 0xFFEF) return false;
    if (IsFullwidthAlnum(cp)) return false;
    if (cp >= 0xFF66 && cp <= 0xFF9F) return false;  // 半角片假名
    return true;
}

CharClass ClassifyChar(uint32_t cp)
{
    if (cp < 0x80) {
        if ((cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z') || (cp >= '0' && cp <= '9')
            || cp == '_') {
            return CharClass::Word;
        }
        return CharClass::Separator;
    }
    if (IsCjkCp(cp)) return CharClass::Cjk;
    if (IsCjkPunctuation(cp) || IsGeneralPunctuation(cp)) return CharClass::Separator;
    if (IsFullwidthPunct(cp)) return CharClass::Separator;
    return CharClass::Word;  // 其余文字（拉丁扩展 / 希腊 / 西里尔 / 阿拉伯…）按词处理
}

// token 内的码点归一：全角字母数字 → 半角，ASCII 大写 → 小写。
uint32_t NormalizeTokenCp(uint32_t cp)
{
    if (cp >= 'A' && cp <= 'Z') return cp + ('a' - 'A');
    if (cp >= 0xFF21 && cp <= 0xFF3A) return cp - 0xFF21 + 'a';
    if (cp >= 0xFF41 && cp <= 0xFF5A) return cp - 0xFF41 + 'a';
    if (cp >= 0xFF10 && cp <= 0xFF19) return cp - 0xFF10 + '0';
    return cp;
}

using EmitToken = std::function<void(const std::string&, int)>;

void TokenizeCodepoints(const std::vector<uint32_t>& cps, const EmitToken& emit)
{
    std::size_t i = 0;
    const std::size_t n = cps.size();
    while (i < n) {
        const CharClass cls = ClassifyChar(cps[i]);
        if (cls == CharClass::Separator) {
            ++i;
            continue;
        }
        const std::size_t runStart = i;
        if (cls == CharClass::Word) {
            std::vector<uint32_t> run;
            while (i < n && ClassifyChar(cps[i]) == CharClass::Word) {
                run.push_back(NormalizeTokenCp(cps[i]));
                ++i;
            }
            std::string token = EncodeUtf8(run, 0, run.size());
            if (!token.empty() && token.size() <= kMaxTermLengthBytes) {
                emit(token, static_cast<int>(runStart));
            }
        } else {
            std::vector<uint32_t> run;
            while (i < n && ClassifyChar(cps[i]) == CharClass::Cjk) {
                run.push_back(cps[i]);
                ++i;
            }
            for (std::size_t k = 0; k < run.size(); ++k) {
                emit(EncodeUtf8(run, k, k + 1), static_cast<int>(runStart + k));
                if (k + 1 < run.size()) {
                    emit(EncodeUtf8(run, k, k + 2), static_cast<int>(runStart + k));
                }
            }
        }
    }
}

std::unordered_map<std::string, std::vector<int>> TokenizeBook(const std::string& text)
{
    std::unordered_map<std::string, std::vector<int>> terms;
    std::vector<uint32_t> cps;
    if (!DecodeUtf8(text, cps)) return terms;
    terms.reserve(cps.size() / 3 + 8);
    TokenizeCodepoints(cps, [&terms](const std::string& token, int position) {
        terms[token].push_back(position);
    });
    return terms;
}

// ── 正文归一化 ───────────────────────────────────────────────────────

bool IsAsciiSpace(char c)
{
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\v';
}

std::string TrimAscii(const std::string& text)
{
    std::size_t begin = 0;
    std::size_t end = text.size();
    while (begin < end && IsAsciiSpace(text[begin])) ++begin;
    while (end > begin && IsAsciiSpace(text[end - 1])) --end;
    return text.substr(begin, end - begin);
}

std::string NormalizeText(const std::string& raw)
{
    std::string out;
    out.reserve(raw.size());
    for (std::size_t i = 0; i < raw.size(); ++i) {
        const char c = raw[i];
        if (c == '\r') {
            if (i + 1 < raw.size() && raw[i + 1] == '\n') ++i;
            out.push_back('\n');
        } else if (c == '\t') {
            out.push_back(' ');
        } else if (static_cast<unsigned char>(c) < 0x20 && c != '\n') {
            out.push_back(' ');
        } else {
            out.push_back(c);
        }
    }
    return TrimAscii(out);
}

std::string LowerAscii(std::string text)
{
    for (char& c : text) {
        if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
    }
    return text;
}

// ── HTML 实体 / 标签 ─────────────────────────────────────────────────

bool StringStartsWithAt(const std::string& text, std::size_t pos, const char* prefix)
{
    const std::size_t len = std::strlen(prefix);
    if (pos + len > text.size()) return false;
    return text.compare(pos, len, prefix) == 0;
}

void AppendDecodedEntity(const std::string& name, std::string& out)
{
    if (name == "amp") { out.push_back('&'); return; }
    if (name == "lt") { out.push_back('<'); return; }
    if (name == "gt") { out.push_back('>'); return; }
    if (name == "quot") { out.push_back('"'); return; }
    if (name == "apos") { out.push_back('\''); return; }
    if (name == "nbsp" || name == "#160") { out.push_back(' '); return; }
    if (name == "mdash") { AppendUtf8(0x2014, out); return; }
    if (name == "ndash") { AppendUtf8(0x2013, out); return; }
    if (name == "hellip") { AppendUtf8(0x2026, out); return; }
    if (name == "lsquo") { AppendUtf8(0x2018, out); return; }
    if (name == "rsquo") { AppendUtf8(0x2019, out); return; }
    if (name == "ldquo") { AppendUtf8(0x201C, out); return; }
    if (name == "rdquo") { AppendUtf8(0x201D, out); return; }
    if (name == "laquo") { AppendUtf8(0x00AB, out); return; }
    if (name == "raquo") { AppendUtf8(0x00BB, out); return; }
    if (name == "middot") { AppendUtf8(0x00B7, out); return; }
    if (name == "copy") { AppendUtf8(0x00A9, out); return; }
    if (name == "reg") { AppendUtf8(0x00AE, out); return; }
    if (name == "trade") { AppendUtf8(0x2122, out); return; }
    if (name == "times") { AppendUtf8(0x00D7, out); return; }
    if (name == "divide") { AppendUtf8(0x00F7, out); return; }
    if (name == "deg") { AppendUtf8(0x00B0, out); return; }

    if (!name.empty() && name[0] == '#') {
        std::string digits = name.substr(1);
        bool hex = false;
        if (!digits.empty() && (digits[0] == 'x' || digits[0] == 'X')) {
            hex = true;
            digits = digits.substr(1);
        }
        if (digits.empty()) return;
        uint32_t value = 0;
        for (char c : digits) {
            int digit = -1;
            if (c >= '0' && c <= '9') digit = c - '0';
            else if (hex && c >= 'a' && c <= 'f') digit = c - 'a' + 10;
            else if (hex && c >= 'A' && c <= 'F') digit = c - 'A' + 10;
            else return;
            value = value * (hex ? 16u : 10u) + static_cast<uint32_t>(digit);
            if (value > 0x10FFFF) return;
        }
        if (value >= 0xD800 && value <= 0xDFFF) return;
        AppendUtf8(value, out);
        return;
    }
    // 未知实体原样保留（&foo;），不做二次解释。
    out.push_back('&');
    out.append(name);
    out.push_back(';');
}

std::string DecodeHtmlEntities(const std::string& text)
{
    std::string out;
    out.reserve(text.size());
    std::size_t i = 0;
    while (i < text.size()) {
        if (text[i] == '&') {
            const std::size_t semi = text.find(';', i + 1);
            if (semi != std::string::npos && semi - i <= 10) {
                const std::string name = text.substr(i + 1, semi - i - 1);
                if (!name.empty()) {
                    AppendDecodedEntity(name, out);
                    i = semi + 1;
                    continue;
                }
            }
            out.push_back('&');
            ++i;
            continue;
        }
        out.push_back(text[i]);
        ++i;
    }
    return out;
}

std::string TagNameFrom(const std::string& tagContent)
{
    std::size_t i = 0;
    while (i < tagContent.size() && (tagContent[i] == '/' || IsAsciiSpace(tagContent[i]))) ++i;
    std::string name;
    while (i < tagContent.size()) {
        const char c = tagContent[i];
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
              || c == ':' || c == '-' || c == '_')) {
            break;
        }
        name.push_back(c);
        ++i;
    }
    return LowerAscii(name);
}

bool IsBlockTag(const std::string& name)
{
    static const char* const kBlocks[] = {
        "p", "div", "br", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6", "section",
        "article", "blockquote", "pre", "table", "ul", "ol", "hr", "header", "footer",
        "nav", "aside", "figcaption", "dd", "dt", "td", "th", "main", "title", "body",
        "html", "head",
    };
    for (const char* block : kBlocks) {
        if (name == block) return true;
    }
    return false;
}

std::string HtmlToTextImpl(const std::string& html)
{
    std::string out;
    out.reserve(html.size());
    std::string skipTag;  // 非空时处于 <script>/<style>/<head> 内部
    std::size_t i = 0;
    while (i < html.size()) {
        if (html[i] == '<') {
            if (StringStartsWithAt(html, i, "<!--")) {
                const std::size_t end = html.find("-->", i + 4);
                i = (end == std::string::npos) ? html.size() : end + 3;
                continue;
            }
            const std::size_t close = html.find('>', i + 1);
            if (close == std::string::npos) break;
            const std::string tagContent = html.substr(i + 1, close - i - 1);
            const std::string name = TagNameFrom(tagContent);
            const bool closing = !tagContent.empty() && tagContent[0] == '/';
            if (!skipTag.empty()) {
                if (closing && name == skipTag) skipTag.clear();
            } else if (!closing) {
                if (name == "script" || name == "style" || name == "head") {
                    skipTag = name;
                } else if (IsBlockTag(name)) {
                    out.push_back('\n');
                }
            } else if (IsBlockTag(name)) {
                out.push_back('\n');
            }
            i = close + 1;
            continue;
        }
        if (!skipTag.empty()) {
            ++i;
            continue;
        }
        const std::size_t next = html.find('<', i);
        const std::size_t end = (next == std::string::npos) ? html.size() : next;
        out.append(DecodeHtmlEntities(html.substr(i, end - i)));
        i = end;
    }
    return NormalizeText(out);
}

std::string MarkdownToTextImpl(const std::string& markdown)
{
    // 逐行去掉块级标记，再做行内标记清理，最后交给 HtmlToTextImpl 处理内嵌 HTML。
    std::string stripped;
    stripped.reserve(markdown.size());
    std::size_t pos = 0;
    bool inFence = false;
    const std::string fence = "\x60\x60\x60";
    while (pos <= markdown.size()) {
        const std::size_t eol = markdown.find('\n', pos);
        const std::size_t end = (eol == std::string::npos) ? markdown.size() : eol;
        std::string line = markdown.substr(pos, end - pos);
        pos = (eol == std::string::npos) ? markdown.size() + 1 : eol + 1;

        std::string trimmed = TrimAscii(line);
        if (StringStartsWithAt(trimmed, 0, fence.c_str()) || StringStartsWithAt(trimmed, 0, "~~~")) {
            inFence = !inFence;
            continue;
        }
        if (inFence) {
            stripped.append(line);
            stripped.push_back('\n');
            continue;
        }
        // 分隔线 / setext 下划线
        bool onlyRule = !trimmed.empty();
        for (char c : trimmed) {
            if (c != '-' && c != '*' && c != '=' && c != '_' && c != ' ') {
                onlyRule = false;
                break;
            }
        }
        if (onlyRule) continue;

        std::size_t i = 0;
        while (i < line.size() && (line[i] == ' ' || line[i] == '\t')) ++i;
        // 标题 / 引用 / 列表前缀
        while (i < line.size() && (line[i] == '#' || line[i] == '>' || line[i] == '+')) ++i;
        if (i < line.size() && (line[i] == '-' || line[i] == '*')) {
            if (i + 1 < line.size() && (line[i + 1] == ' ' || line[i + 1] == '\t')) ++i;
        }
        if (i < line.size() && line[i] == ' ') ++i;
        // 有序列表 "1. "
        std::size_t j = i;
        while (j < line.size() && line[j] >= '0' && line[j] <= '9') ++j;
        if (j > i && j < line.size() && (line[j] == '.' || line[j] == ')')
            && j + 1 < line.size() && (line[j + 1] == ' ' || line[j + 1] == '\t')) {
            i = j + 2;
        }
        stripped.append(line.substr(i));
        stripped.push_back('\n');
    }

    // 行内：图片 ![alt](url) → alt；链接 [text](url) → text；引用链接 [text][id] → text。
    std::string inlineText;
    inlineText.reserve(stripped.size());
    std::size_t i = 0;
    while (i < stripped.size()) {
        const char c = stripped[i];
        if (c == '!' && i + 1 < stripped.size() && stripped[i + 1] == '[') {
            const std::size_t close = stripped.find(']', i + 2);
            if (close != std::string::npos) {
                inlineText.append(stripped.substr(i + 2, close - (i + 2)));
                i = close + 1;
                if (i < stripped.size() && stripped[i] == '(') {
                    const std::size_t paren = stripped.find(')', i);
                    i = (paren == std::string::npos) ? i : paren + 1;
                }
                continue;
            }
        }
        if (c == '[') {
            const std::size_t close = stripped.find(']', i + 1);
            if (close != std::string::npos) {
                const std::string label = stripped.substr(i + 1, close - i - 1);
                if (!label.empty() && label[0] != '^') {  // 脚注引用 [^1] 保留原样
                    inlineText.append(label);
                    i = close + 1;
                    if (i < stripped.size() && stripped[i] == '(') {
                        const std::size_t paren = stripped.find(')', i);
                        i = (paren == std::string::npos) ? i : paren + 1;
                    } else if (i < stripped.size() && stripped[i] == '[') {
                        const std::size_t ref = stripped.find(']', i + 1);
                        i = (ref == std::string::npos) ? i : ref + 1;
                    }
                    continue;
                }
            }
        }
        // 行内标记字符直接去掉（* _ ~ 反引号）
        if (c == '*' || c == '_' || c == '~' || c == 0x60) {
            ++i;
            continue;
        }
        if (c == '<') {
            const std::size_t close = stripped.find('>', i + 1);
            if (close != std::string::npos) {
                const std::string inner = stripped.substr(i + 1, close - i - 1);
                const bool looksUrl = inner.find("://") != std::string::npos
                    || StringStartsWithAt(inner, 0, "mailto:");
                if (looksUrl) {
                    inlineText.append(inner);
                    i = close + 1;
                    continue;
                }
            }
        }
        inlineText.push_back(c);
        ++i;
    }
    return HtmlToTextImpl(inlineText);
}

// ── 文件读取 / stat ──────────────────────────────────────────────────

bool ReadFileBytes(const std::string& utf8Path, std::string& out, std::string* error)
{
    std::error_code ec;
    const fs::path path = fs::u8path(utf8Path);
    if (!fs::exists(path, ec) || ec) {
        if (error) *error = search_index_codes::kFileMissing;
        return false;
    }
    std::ifstream in(path, std::ios::binary);
    if (!in) {
        if (error) *error = search_index_codes::kReadError;
        return false;
    }
    in.seekg(0, std::ios::end);
    const std::streamoff length = in.tellg();
    if (length < 0) {
        if (error) *error = search_index_codes::kReadError;
        return false;
    }
    in.seekg(0, std::ios::beg);
    out.resize(static_cast<std::size_t>(length));
    if (length > 0) {
        in.read(&out[0], length);
        if (!in) {
            if (error) *error = search_index_codes::kReadError;
            return false;
        }
    }
    return true;
}

struct FileStamp {
    bool exists = false;
    std::int64_t mtime = 0;
    std::uint64_t size = 0;
};

FileStamp StatFile(const std::string& utf8Path)
{
    FileStamp stamp;
    std::error_code ec;
    const fs::path path = fs::u8path(utf8Path);
    const fs::file_status status = fs::status(path, ec);
    if (ec || !fs::exists(status)) return stamp;
    stamp.exists = true;
    const std::uintmax_t size = fs::file_size(path, ec);
    if (!ec) stamp.size = static_cast<std::uint64_t>(size);
    const fs::file_time_type mtime = fs::last_write_time(path, ec);
    if (!ec) stamp.mtime = static_cast<std::int64_t>(mtime.time_since_epoch().count());
    return stamp;
}

// ── EPUB：container.xml → OPF → spine 的 xhtml ──────────────────────

std::string PercentDecode(const std::string& text)
{
    std::string out;
    out.reserve(text.size());
    for (std::size_t i = 0; i < text.size(); ++i) {
        if (text[i] == '%' && i + 2 < text.size()) {
            auto hex = [](char c) -> int {
                if (c >= '0' && c <= '9') return c - '0';
                if (c >= 'a' && c <= 'f') return c - 'a' + 10;
                if (c >= 'A' && c <= 'F') return c - 'A' + 10;
                return -1;
            };
            const int h = hex(text[i + 1]);
            const int l = hex(text[i + 2]);
            if (h >= 0 && l >= 0) {
                out.push_back(static_cast<char>(h * 16 + l));
                i += 2;
                continue;
            }
        }
        out.push_back(text[i]);
    }
    return out;
}

std::string ZipPathJoin(const std::string& baseDir, const std::string& relative)
{
    std::string href = relative;
    const std::size_t hash = href.find('#');
    if (hash != std::string::npos) href = href.substr(0, hash);
    href = PercentDecode(href);
    if (href.empty()) return std::string();
    if (href.find("://") != std::string::npos) return std::string();  // 外链不索引

    std::string combined;
    if (!href.empty() && href[0] == '/') {
        combined = href.substr(1);
    } else if (!baseDir.empty()) {
        combined = baseDir + "/" + href;
    } else {
        combined = href;
    }

    std::vector<std::string> parts;
    std::size_t pos = 0;
    while (pos <= combined.size()) {
        const std::size_t slash = combined.find('/', pos);
        const std::size_t end = (slash == std::string::npos) ? combined.size() : slash;
        const std::string part = combined.substr(pos, end - pos);
        if (part == "..") {
            if (!parts.empty()) parts.pop_back();
        } else if (!part.empty() && part != ".") {
            parts.push_back(part);
        }
        pos = (slash == std::string::npos) ? combined.size() + 1 : slash + 1;
    }
    std::string result;
    for (std::size_t i = 0; i < parts.size(); ++i) {
        if (i > 0) result.push_back('/');
        result.append(parts[i]);
    }
    return result;
}

std::string DirectoryOf(const std::string& zipPath)
{
    const std::size_t slash = zipPath.rfind('/');
    return (slash == std::string::npos) ? std::string() : zipPath.substr(0, slash);
}

// 从 "<tag attr=...>" 内容里取属性值；属性名大小写不敏感。
bool FindAttribute(const std::string& tagContent, const std::string& attribute, std::string& value)
{
    const std::string wanted = LowerAscii(attribute);
    std::size_t i = 0;
    while (i < tagContent.size()) {
        while (i < tagContent.size() && IsAsciiSpace(tagContent[i])) ++i;
        const std::size_t nameBegin = i;
        while (i < tagContent.size() && tagContent[i] != '=' && !IsAsciiSpace(tagContent[i])
               && tagContent[i] != '>' && tagContent[i] != '/') {
            ++i;
        }
        const std::string name = LowerAscii(tagContent.substr(nameBegin, i - nameBegin));
        while (i < tagContent.size() && IsAsciiSpace(tagContent[i])) ++i;
        std::string attrValue;
        if (i < tagContent.size() && tagContent[i] == '=') {
            ++i;
            while (i < tagContent.size() && IsAsciiSpace(tagContent[i])) ++i;
            if (i < tagContent.size() && (tagContent[i] == '"' || tagContent[i] == '\'')) {
                const char quote = tagContent[i++];
                const std::size_t end = tagContent.find(quote, i);
                if (end == std::string::npos) return false;
                attrValue = tagContent.substr(i, end - i);
                i = end + 1;
            } else {
                const std::size_t begin = i;
                while (i < tagContent.size() && !IsAsciiSpace(tagContent[i])
                       && tagContent[i] != '>') {
                    ++i;
                }
                attrValue = tagContent.substr(begin, i - begin);
            }
        }
        if (name == wanted) {
            value = attrValue;
            return true;
        }
    }
    return false;
}

struct XmlTag {
    std::string name;
    std::string content;  // 标签内部（不含 < >）
};

std::vector<XmlTag> ScanTags(const std::string& xml, const std::string& wantedLower)
{
    std::vector<XmlTag> tags;
    std::size_t i = 0;
    while (i < xml.size()) {
        const std::size_t open = xml.find('<', i);
        if (open == std::string::npos) break;
        if (StringStartsWithAt(xml, open, "<!--")) {
            const std::size_t commentEnd = xml.find("-->", open + 4);
            i = (commentEnd == std::string::npos) ? xml.size() : commentEnd + 3;
            continue;
        }
        const std::size_t close = xml.find('>', open + 1);
        if (close == std::string::npos) break;
        const std::string content = xml.substr(open + 1, close - open - 1);
        const std::string name = TagNameFrom(content);
        if (!name.empty() && (wantedLower.empty() || name == wantedLower)) {
            XmlTag tag;
            tag.name = name;
            tag.content = content;
            tags.push_back(std::move(tag));
        }
        i = close + 1;
    }
    return tags;
}

bool ExtractEpubText(const std::string& epubPath, std::string& out,
                     std::vector<SearchChapterSpan>* chapters, std::string* error)
{
    std::string container;
    if (!ReadZipEntry(epubPath, "META-INF/container.xml", container)) {
        if (error) *error = search_index_codes::kEpubContainer;
        return false;
    }
    std::string opfPath;
    for (const XmlTag& tag : ScanTags(container, "rootfile")) {
        if (FindAttribute(tag.content, "full-path", opfPath) && !opfPath.empty()) break;
    }
    if (opfPath.empty()) {
        if (error) *error = search_index_codes::kEpubOpf;
        return false;
    }

    std::string opf;
    if (!ReadZipEntry(epubPath, opfPath, opf)) {
        if (error) *error = search_index_codes::kEpubOpf;
        return false;
    }
    const std::string opfDir = DirectoryOf(opfPath);

    struct ManifestItem {
        std::string href;
        std::string mediaType;
    };
    std::unordered_map<std::string, ManifestItem> manifest;
    std::vector<std::string> manifestOrder;
    for (const XmlTag& tag : ScanTags(opf, "item")) {
        std::string id;
        std::string href;
        std::string media;
        FindAttribute(tag.content, "id", id);
        FindAttribute(tag.content, "href", href);
        FindAttribute(tag.content, "media-type", media);
        if (id.empty() || href.empty()) continue;
        manifestOrder.push_back(id);
        manifest.emplace(id, ManifestItem{ href, media });
    }

    std::vector<std::string> spineHrefs;
    for (const XmlTag& tag : ScanTags(opf, "itemref")) {
        std::string idref;
        if (!FindAttribute(tag.content, "idref", idref)) continue;
        auto it = manifest.find(idref);
        if (it == manifest.end()) continue;
        const std::string& media = it->second.mediaType;
        const bool isHtml = media.find("html") != std::string::npos
            || media.find("xml") != std::string::npos || media.empty();
        if (isHtml) spineHrefs.push_back(it->second.href);
    }
    if (spineHrefs.empty()) {
        // 没有 spine（或 itemref 全对不上）：按 manifest 顺序收 xhtml。
        for (const std::string& id : manifestOrder) {
            auto it = manifest.find(id);
            if (it == manifest.end()) continue;
            const std::string& media = it->second.mediaType;
            if (media.find("html") != std::string::npos
                || it->second.href.find(".xhtml") != std::string::npos
                || it->second.href.find(".html") != std::string::npos) {
                spineHrefs.push_back(it->second.href);
            }
        }
    }

    std::string combined;
    std::vector<SearchChapterSpan> pending;
    int totalCps = 0;
    int readCount = 0;
    for (const std::string& href : spineHrefs) {
        const std::string entry = ZipPathJoin(opfDir, href);
        if (entry.empty()) continue;
        std::string xhtml;
        if (!ReadZipEntry(epubPath, entry, xhtml)) continue;
        ++readCount;
        const std::string chapterText = HtmlToTextImpl(xhtml);
        SearchChapterSpan span;
        span.start = totalCps;
        span.href = href;
        combined.append(chapterText);
        totalCps += static_cast<int>(CountCodepoints(chapterText));
        span.end = totalCps;
        combined.push_back('\n');
        totalCps += 1;  // 章节分隔换行（不属于任何章节）
        pending.push_back(std::move(span));
    }

    out = NormalizeText(combined);
    // NormalizeText 会 TrimAscii 掉开头空白：把起始偏移做同样的校正。
    int leadingTrim = 0;
    {
        std::size_t i = 0;
        while (i < combined.size() && IsAsciiSpace(combined[i])) ++i;
        leadingTrim = static_cast<int>(CountCodepoints(combined.substr(0, i)));
    }
    if (chapters) {
        chapters->clear();
        for (const SearchChapterSpan& span : pending) {
            int start = span.start - leadingTrim;
            int end = span.end - leadingTrim;
            if (end <= 0) continue;
            if (start < 0) start = 0;
            if (end > start) chapters->push_back(SearchChapterSpan{ start, end, span.href });
        }
    }
    if (readCount == 0 || out.empty()) {
        if (error) *error = search_index_codes::kEpubNoText;
        return false;
    }
    return true;
}

// 正文被 maxChars / 总预算截断后，把章节区间跟着裁剪（超出部分丢弃）。
void ClampChapters(std::vector<SearchChapterSpan>& chapters, std::size_t textChars) {
    if (chapters.empty()) return;
    const std::size_t limit = textChars > static_cast<std::size_t>(0x7FFFFFFF)
        ? static_cast<std::size_t>(0x7FFFFFFF)
        : textChars;
    std::vector<SearchChapterSpan> kept;
    kept.reserve(chapters.size());
    for (const SearchChapterSpan& chapter : chapters) {
        if (static_cast<std::size_t>(chapter.start) >= limit) break;
        SearchChapterSpan span = chapter;
        const std::size_t end = static_cast<std::size_t>(span.end);
        if (end > limit) span.end = static_cast<int>(limit);
        if (span.end > span.start) kept.push_back(span);
    }
    chapters = std::move(kept);
}

// ── 格式判定 ─────────────────────────────────────────────────────────

std::string ExtensionOf(const std::string& path)
{
    const std::size_t slash = path.find_last_of("/\\");
    const std::size_t dot = path.find_last_of('.');
    if (dot == std::string::npos) return std::string();
    if (slash != std::string::npos && dot < slash) return std::string();
    return path.substr(dot + 1);
}

bool IsSupportedFormat(const std::string& format)
{
    return format == "epub" || format == "txt" || format == "html" || format == "markdown";
}

// 安全读取 JSON 标量（缺字段 / 类型不对都回落默认值，绝不抛）。
std::string JsonString(const json& object, const char* key, const std::string& fallback)
{
    auto it = object.find(key);
    if (it == object.end() || !it->is_string()) return fallback;
    return it->get<std::string>();
}

long long JsonInt(const json& object, const char* key, long long fallback)
{
    auto it = object.find(key);
    if (it == object.end()) return fallback;
    if (it->is_number_integer()) return it->get<long long>();
    if (it->is_number_unsigned()) {
        const unsigned long long raw = it->get<unsigned long long>();
        return raw > 0x7FFFFFFFFFFFFFFFULL ? 0x7FFFFFFFFFFFFFFFLL : static_cast<long long>(raw);
    }
    return fallback;
}

} // namespace

// ── 输入映射 ─────────────────────────────────────────────────────────

BookTextSource BookTextSource::FromDbRow(const json& row)
{
    BookTextSource source;
    if (!row.is_object()) return source;
    source.bookId = JsonInt(row, "id", 0);
    if (source.bookId == 0) source.bookId = JsonInt(row, "book_id", 0);
    source.title = JsonString(row, "title", std::string());
    source.format = JsonString(row, "format", std::string());
    source.filePath = JsonString(row, "file_path", std::string());
    source.mtime = JsonInt(row, "mtime", 0);
    const long long size = JsonInt(row, "file_size", 0);
    source.fileSize = size < 0 ? 0 : static_cast<std::uint64_t>(size);
    return source;
}

std::vector<BookTextSource> BookTextSourcesFromDb(const json& booksArray)
{
    std::vector<BookTextSource> sources;
    if (!booksArray.is_array()) return sources;
    sources.reserve(booksArray.size());
    for (const json& row : booksArray) {
        BookTextSource source = BookTextSource::FromDbRow(row);
        if (source.bookId <= 0 || source.filePath.empty()) continue;
        sources.push_back(std::move(source));
    }
    return sources;
}

// ── 格式 / 抽取（静态工具）───────────────────────────────────────────

std::string SearchIndexService::NormalizeFormat(const std::string& formatOrExtension)
{
    std::string text = LowerAscii(TrimAscii(formatOrExtension));
    if (!text.empty() && text[0] == '.') text.erase(0, 1);
    if (text == "epub") return "epub";
    if (text == "txt" || text == "text" || text == "plain") return "txt";
    if (text == "html" || text == "htm" || text == "xhtml") return "html";
    if (text == "md" || text == "markdown" || text == "mdown") return "markdown";
    if (text == "pdf") return "pdf";
    if (text == "fb2") return "fb2";
    if (text == "cbz" || text == "cbr" || text == "cb7" || text == "cbt") return "cbz";
    return text;
}

std::string SearchIndexService::HtmlToText(const std::string& html)
{
    return HtmlToTextImpl(html);
}

std::string SearchIndexService::MarkdownToText(const std::string& markdown)
{
    return MarkdownToTextImpl(markdown);
}

namespace {

// 内部详细版：除正文外带回 EPUB 的 spine 章节区间（Build / AddBook 用）。
bool ExtractTextDetailed(const std::string& filePathUtf8, const std::string& format,
                         std::size_t maxChars, std::string& out,
                         std::vector<SearchChapterSpan>& chapters, std::string* error)
{
    out.clear();
    chapters.clear();
    if (error) *error = search_index_codes::kOk;

    std::string normalized = SearchIndexService::NormalizeFormat(format);
    if (normalized.empty()) normalized = SearchIndexService::NormalizeFormat(ExtensionOf(filePathUtf8));
    if (normalized.empty() || !IsSupportedFormat(normalized)) {
        if (error) *error = search_index_codes::kUnsupportedFormat;
        return false;
    }

    std::error_code ec;
    if (!fs::exists(fs::u8path(filePathUtf8), ec) || ec) {
        if (error) *error = search_index_codes::kFileMissing;
        return false;
    }

    if (normalized == "epub") {
        if (!ExtractEpubText(filePathUtf8, out, &chapters, error)) return false;
    } else {
        std::string bytes;
        if (!ReadFileBytes(filePathUtf8, bytes, error)) return false;
        std::string decoded;
        if (!DecodeTextBytes(bytes, decoded, error)) return false;
        if (normalized == "html") {
            out = HtmlToTextImpl(decoded);
        } else if (normalized == "markdown") {
            out = MarkdownToTextImpl(decoded);
        } else {
            out = NormalizeText(decoded);
        }
    }

    if (maxChars > 0) out = TruncateCodepoints(out, maxChars);
    ClampChapters(chapters, CountCodepoints(out));
    if (out.empty()) {
        if (error) *error = search_index_codes::kEmptyText;
        return false;
    }
    return true;
}

} // namespace

bool SearchIndexService::ExtractText(const std::string& filePathUtf8, const std::string& format,
                                     std::size_t maxChars, std::string& out,
                                     std::string* error)
{
    std::vector<SearchChapterSpan> ignoredChapters;
    return ExtractTextDetailed(filePathUtf8, format, maxChars, out, ignoredChapters, error);
}

// ── 构造 / 位置 ──────────────────────────────────────────────────────

void SearchIndexService::SetDataDir(const std::string& dataDirUtf8)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    std::string dir = dataDirUtf8;
    while (!dir.empty() && (dir.back() == '/' || dir.back() == '\\')) dir.pop_back();
    m_dataDir = dir;
}

std::string SearchIndexService::DataDir() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_dataDir;
}

std::string SearchIndexService::IndexFilePath() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_dataDir.empty()) return std::string();
    return m_dataDir + "/search_index.json";
}

// ── 索引维护 ─────────────────────────────────────────────────────────

void SearchIndexService::RebuildTermIndexLocked()
{
    m_terms.clear();
    for (auto& bookPair : m_index.books) {
        BookRecord& book = *bookPair.second;
        for (auto& termPair : book.terms) {
            m_terms[termPair.first].emplace(book.bookId, &termPair.second);
        }
    }
}

void SearchIndexService::ClearLocked()
{
    m_index.books.clear();
    m_terms.clear();
}

void SearchIndexService::Clear()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    ClearLocked();
}

std::size_t SearchIndexService::BookCount() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_index.books.size();
}

std::size_t SearchIndexService::TermCount() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_terms.size();
}

std::size_t SearchIndexService::TotalChars() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    std::size_t total = 0;
    for (const auto& bookPair : m_index.books) total += bookPair.second->textChars;
    return total;
}

bool SearchIndexService::HasBook(long long bookId) const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_index.books.find(bookId) != m_index.books.end();
}

std::int64_t SearchIndexService::BookMtime(long long bookId) const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    auto it = m_index.books.find(bookId);
    return it == m_index.books.end() ? 0 : it->second->mtime;
}

namespace {

void PushBuildError(SearchIndexBuildResult& result, long long bookId, const std::string& code,
                    const std::string& path)
{
    if (result.errors.size() >= kMaxErrors) return;
    result.errors.push_back("book " + std::to_string(bookId) + ": " + code + " (" + path + ")");
}

} // namespace

SearchIndexBuildResult SearchIndexService::Build(const std::vector<BookTextSource>& books,
                                                 const SearchBuildOptions& options)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return BuildLocked(books, options);
}

SearchIndexBuildResult SearchIndexService::BuildLocked(const std::vector<BookTextSource>& books,
                                                       const SearchBuildOptions& options)
{
    const auto started = std::chrono::steady_clock::now();
    SearchIndexBuildResult result;

    IndexData staging;
    std::unordered_set<long long> inputIds;
    std::size_t totalChars = 0;

    for (const BookTextSource& source : books) {
        if (options.isCancelled && options.isCancelled()) {
            result.cancelled = true;  // 不提交 staging：旧索引保持原样
            return result;
        }
        if (source.bookId <= 0) {
            ++result.failed;
            PushBuildError(result, source.bookId, "invalid_book_id", source.filePath);
            continue;
        }
        inputIds.insert(source.bookId);

        if (options.maxBooks > 0 && staging.books.size() >= options.maxBooks) {
            ++result.skipped;
            continue;
        }

        const std::string format = NormalizeFormat(
            source.format.empty() ? ExtensionOf(source.filePath) : source.format);
        if (format == "pdf") {
            ++result.skippedPdf;
            continue;
        }
        if (!IsSupportedFormat(format)) {
            ++result.skipped;
            continue;
        }

        const FileStamp stamp = StatFile(source.filePath);
        const std::int64_t mtime = source.mtime != 0 ? source.mtime : stamp.mtime;
        const std::uint64_t fileSize = source.fileSize != 0 ? source.fileSize : stamp.size;

        const auto oldIt = m_index.books.find(source.bookId);
        if (oldIt != m_index.books.end()) {
            const BookRecord& old = *oldIt->second;
            const bool sameStamp = mtime != 0 && old.mtime == mtime && old.fileSize == fileSize
                && old.format == format && old.filePath == source.filePath;
            const bool hasData =
                !old.terms.empty() && (options.includeText ? !old.text.empty() : true);
            if (sameStamp && hasData) {
                staging.books.emplace(source.bookId, oldIt->second);
                totalChars += old.textChars;
                ++result.reused;
                continue;
            }
        }

        std::string text;
        std::vector<SearchChapterSpan> chapters;
        std::string errorCode;
        if (!ExtractTextDetailed(source.filePath, format, options.maxCharsPerBook, text, chapters, &errorCode)) {
            ++result.failed;
            PushBuildError(result, source.bookId, errorCode, source.filePath);
            continue;
        }

        if (options.maxTotalTextChars > 0) {
            const std::size_t remaining =
                totalChars >= options.maxTotalTextChars ? 0 : options.maxTotalTextChars - totalChars;
            if (remaining == 0) {
                ++result.skipped;
                PushBuildError(result, source.bookId, "memory_limit", source.filePath);
                continue;
            }
            text = TruncateCodepoints(text, remaining);
            ClampChapters(chapters, CountCodepoints(text));
        }

        auto record = std::make_shared<BookRecord>();
        record->bookId = source.bookId;
        record->title = source.title;
        record->format = format;
        record->filePath = source.filePath;
        record->mtime = mtime;
        record->fileSize = fileSize;
        record->textChars = CountCodepoints(text);
        record->chapters = std::move(chapters);
        record->terms = TokenizeBook(text);
        if (options.includeText) record->text = std::move(text);

        totalChars += record->textChars;
        staging.books.emplace(source.bookId, std::move(record));
        ++result.indexed;
    }

    // 旧索引里有、本次输入里没有的书 → 删除（导入 / 删除后的收敛）。
    for (const auto& bookPair : m_index.books) {
        if (inputIds.find(bookPair.first) == inputIds.end()) ++result.removed;
    }

    m_index = std::move(staging);
    RebuildTermIndexLocked();

    result.totalChars = 0;
    for (const auto& bookPair : m_index.books) result.totalChars += bookPair.second->textChars;
    result.termCount = m_terms.size();
    result.elapsedMs = std::chrono::duration<double, std::milli>(
                           std::chrono::steady_clock::now() - started).count();
    return result;
}

bool SearchIndexService::AddBook(const BookTextSource& book, const SearchBuildOptions& options,
                                 std::string* error)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return AddBookLocked(book, options, error);
}

bool SearchIndexService::AddBookLocked(const BookTextSource& book,
                                       const SearchBuildOptions& options, std::string* error)
{
    if (error) *error = search_index_codes::kOk;
    if (book.bookId <= 0) {
        if (error) *error = "invalid_book_id";
        return false;
    }
    const std::string format =
        NormalizeFormat(book.format.empty() ? ExtensionOf(book.filePath) : book.format);
    if (!IsSupportedFormat(format)) {
        if (error) *error = search_index_codes::kUnsupportedFormat;
        return false;
    }

    const FileStamp stamp = StatFile(book.filePath);
    const std::int64_t mtime = book.mtime != 0 ? book.mtime : stamp.mtime;
    const std::uint64_t fileSize = book.fileSize != 0 ? book.fileSize : stamp.size;

    const auto oldIt = m_index.books.find(book.bookId);
    if (oldIt != m_index.books.end()) {
        const BookRecord& old = *oldIt->second;
        if (mtime != 0 && old.mtime == mtime && old.fileSize == fileSize && old.format == format
            && old.filePath == book.filePath && !old.terms.empty()
            && (options.includeText ? !old.text.empty() : true)) {
            return true;  // 文件没变，免重复抽取
        }
    }

    std::string text;
    std::vector<SearchChapterSpan> chapters;
    std::string errorCode;
    if (!ExtractTextDetailed(book.filePath, format, options.maxCharsPerBook, text, chapters, &errorCode)) {
        if (error) *error = errorCode;
        return false;
    }

    if (options.maxTotalTextChars > 0) {
        std::size_t otherChars = 0;
        for (const auto& bookPair : m_index.books) {
            if (bookPair.first != book.bookId) otherChars += bookPair.second->textChars;
        }
        const std::size_t remaining =
            otherChars >= options.maxTotalTextChars ? 0 : options.maxTotalTextChars - otherChars;
        if (remaining == 0) {
            if (error) *error = "memory_limit";
            return false;
        }
        text = TruncateCodepoints(text, remaining);
        ClampChapters(chapters, CountCodepoints(text));
    }

    auto record = std::make_shared<BookRecord>();
    record->bookId = book.bookId;
    record->title = book.title;
    record->format = format;
    record->filePath = book.filePath;
    record->mtime = mtime;
    record->fileSize = fileSize;
    record->textChars = CountCodepoints(text);
    record->chapters = std::move(chapters);
    record->terms = TokenizeBook(text);
    if (options.includeText) record->text = std::move(text);

    m_index.books[book.bookId] = std::move(record);
    RebuildTermIndexLocked();
    return true;
}

bool SearchIndexService::RemoveBook(long long bookId)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    const auto it = m_index.books.find(bookId);
    if (it == m_index.books.end()) return false;
    m_index.books.erase(it);
    RebuildTermIndexLocked();
    return true;
}

// ── 检索 ─────────────────────────────────────────────────────────────

namespace {

using TermTable =
    std::unordered_map<std::string, std::unordered_map<long long, const std::vector<int>*>>;

struct QueryConstraint {
    bool phrase = false;               // true = 中文串（>= 2 字用 bigram 连续性验证）
    std::string token;                 // 英文词 / 中文单字
    std::vector<std::string> bigrams;  // 中文串的相邻 bigram
    std::size_t runLength = 0;         // 命中长度（码点）
};

std::string ConstraintKey(const QueryConstraint& constraint)
{
    if (constraint.phrase) {
        std::string key = "p:";
        for (const std::string& bigram : constraint.bigrams) {
            key.append(bigram);
            key.push_back('\x1f');
        }
        return key;
    }
    return "w:" + constraint.token;
}

void AddConstraint(std::vector<QueryConstraint>& constraints,
                   std::unordered_set<std::string>& seen, QueryConstraint constraint)
{
    const std::string key = ConstraintKey(constraint);
    if (!seen.insert(key).second) return;
    constraints.push_back(std::move(constraint));
}

std::vector<QueryConstraint> ParseQuery(const std::vector<uint32_t>& cps)
{
    std::vector<QueryConstraint> constraints;
    std::unordered_set<std::string> seen;
    std::size_t i = 0;
    const std::size_t n = cps.size();
    while (i < n) {
        const CharClass cls = ClassifyChar(cps[i]);
        if (cls == CharClass::Separator) {
            ++i;
            continue;
        }
        if (cls == CharClass::Word) {
            std::vector<uint32_t> run;
            while (i < n && ClassifyChar(cps[i]) == CharClass::Word) {
                run.push_back(NormalizeTokenCp(cps[i]));
                ++i;
            }
            if (run.empty()) continue;
            QueryConstraint constraint;
            constraint.token = EncodeUtf8(run, 0, run.size());
            constraint.runLength = run.size();
            AddConstraint(constraints, seen, std::move(constraint));
        } else {
            std::vector<uint32_t> run;
            while (i < n && ClassifyChar(cps[i]) == CharClass::Cjk) {
                run.push_back(cps[i]);
                ++i;
            }
            if (run.empty()) continue;
            QueryConstraint constraint;
            constraint.phrase = true;
            constraint.runLength = run.size();
            if (run.size() == 1) {
                constraint.token = EncodeUtf8(run, 0, 1);
            } else {
                for (std::size_t k = 0; k + 1 < run.size(); ++k) {
                    constraint.bigrams.push_back(EncodeUtf8(run, k, k + 2));
                }
            }
            AddConstraint(constraints, seen, std::move(constraint));
        }
    }
    return constraints;
}

std::string FirstTermOf(const QueryConstraint& constraint)
{
    if (constraint.phrase && !constraint.bigrams.empty()) return constraint.bigrams.front();
    return constraint.token;
}

const std::vector<int>* FindPositions(const TermTable& terms, const std::string& token,
                                     long long bookId)
{
    const auto termIt = terms.find(token);
    if (termIt == terms.end()) return nullptr;
    const auto bookIt = termIt->second.find(bookId);
    if (bookIt == termIt->second.end()) return nullptr;
    return bookIt->second;
}

bool CollectConstraintPositions(const TermTable& terms, const QueryConstraint& constraint,
                                long long bookId, std::vector<int>& positions)
{
    positions.clear();
    if (!constraint.phrase) {
        const std::vector<int>* found = FindPositions(terms, constraint.token, bookId);
        if (!found) return false;
        positions = *found;
        return !positions.empty();
    }
    if (constraint.bigrams.empty()) {
        const std::vector<int>* found = FindPositions(terms, constraint.token, bookId);
        if (!found) return false;
        positions = *found;
        return !positions.empty();
    }
    const std::vector<int>* head = FindPositions(terms, constraint.bigrams.front(), bookId);
    if (!head) return false;
    std::vector<const std::vector<int>*> tails;
    tails.reserve(constraint.bigrams.size() - 1);
    for (std::size_t k = 1; k < constraint.bigrams.size(); ++k) {
        const std::vector<int>* tail = FindPositions(terms, constraint.bigrams[k], bookId);
        if (!tail) return false;
        tails.push_back(tail);
    }
    for (int position : *head) {
        bool matched = true;
        for (std::size_t k = 0; k < tails.size(); ++k) {
            const int wanted = position + static_cast<int>(k) + 1;
            if (!std::binary_search(tails[k]->begin(), tails[k]->end(), wanted)) {
                matched = false;
                break;
            }
        }
        if (matched) {
            positions.push_back(position);
            if (positions.size() >= kMaxSnippetScanPositions) break;
        }
    }
    return !positions.empty();
}

std::string MakeSnippet(const std::string& text, std::size_t offset, std::size_t matchLength,
                        std::size_t context)
{
    if (text.empty()) return std::string();
    std::vector<uint32_t> cps;
    if (!DecodeUtf8(text, cps)) return std::string();

    const std::size_t begin = offset > context ? offset - context : 0;
    std::size_t end = offset + matchLength + context;
    if (end > cps.size()) end = cps.size();
    std::string snippet;
    if (begin > 0) snippet.append("\xE2\x80\xA6");  // …
    std::string middle = EncodeUtf8(cps, begin, end);
    for (char& c : middle) {
        if (c == '\n' || c == '\t' || c == '\r') c = ' ';
    }
    snippet.append(middle);
    if (end < cps.size()) snippet.append("\xE2\x80\xA6");
    return snippet;
}

} // namespace

SearchResult SearchIndexService::Search(const std::string& query,
                                        const SearchOptions& options) const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return SearchLocked(query, options);
}

SearchResult SearchIndexService::SearchLocked(const std::string& query,
                                              const SearchOptions& options) const
{
    SearchResult result;

    std::vector<uint32_t> queryCps;
    if (!DecodeUtf8(query, queryCps)) return result;
    const std::vector<QueryConstraint> constraints = ParseQuery(queryCps);
    if (constraints.empty()) return result;

    // 1) 候选书 = 每个约束首 token 的倒排表交集（中文串先按首个 bigram 收敛）。
    std::unordered_set<long long> candidates;
    bool firstConstraint = true;
    for (const QueryConstraint& constraint : constraints) {
        const std::string term = FirstTermOf(constraint);
        const auto termIt = m_terms.find(term);
        if (termIt == m_terms.end()) return result;  // 任一必选词不存在 → 无结果
        if (firstConstraint) {
            candidates.reserve(termIt->second.size());
            for (const auto& bookPair : termIt->second) candidates.insert(bookPair.first);
            firstConstraint = false;
        } else {
            for (auto it = candidates.begin(); it != candidates.end();) {
                if (termIt->second.find(*it) == termIt->second.end()) {
                    it = candidates.erase(it);
                } else {
                    ++it;
                }
            }
        }
        if (candidates.empty()) return result;
    }

    result.booksMatched = static_cast<int>(candidates.size());

    std::vector<long long> ordered(candidates.begin(), candidates.end());
    std::sort(ordered.begin(), ordered.end());

    std::vector<SearchHit> hits;
    for (long long bookId : ordered) {
        if (options.isCancelled && options.isCancelled()) {
            result.cancelled = true;
            break;
        }
        const auto bookIt = m_index.books.find(bookId);
        if (bookIt == m_index.books.end()) continue;
        const BookRecord& book = *bookIt->second;

        double score = 0.0;
        std::size_t earliest = static_cast<std::size_t>(-1);
        std::size_t earliestLength = 0;
        bool matched = true;
        for (const QueryConstraint& constraint : constraints) {
            std::vector<int> positions;
            if (!CollectConstraintPositions(m_terms, constraint, bookId, positions)) {
                matched = false;
                break;
            }
            score += 1.0
                + 0.05 * static_cast<double>(std::min<std::size_t>(positions.size(), 20))
                + 0.005 * static_cast<double>(constraint.runLength);
            const std::size_t position = static_cast<std::size_t>(positions.front());
            if (position < earliest) {
                earliest = position;
                earliestLength = constraint.runLength;
            }
        }
        if (!matched || earliest == static_cast<std::size_t>(-1)) continue;

        SearchHit hit;
        hit.bookId = bookId;
        hit.bookTitle = book.title;
        hit.bookFormat = book.format;
        hit.offset = static_cast<long long>(earliest);
        hit.matchLength = static_cast<long long>(earliestLength);
        hit.page = 0;
        // PB-006-C：格式相关定位提示（详见 SearchHit 字段注释）
        if (book.format == "epub" && !book.chapters.empty()) {
            int chapterIndex = -1;
            for (std::size_t i = 0; i < book.chapters.size(); ++i) {
                const SearchChapterSpan& span = book.chapters[i];
                if (earliest >= static_cast<std::size_t>(span.start)
                    && earliest < static_cast<std::size_t>(span.end)) {
                    chapterIndex = static_cast<int>(i);
                    break;
                }
            }
            if (chapterIndex < 0) {
                // 命中落在章节分隔换行 / 截断边界上：取最后一个 start <= offset 的章节。
                for (std::size_t i = 0; i < book.chapters.size(); ++i) {
                    if (static_cast<std::size_t>(book.chapters[i].start) <= earliest) {
                        chapterIndex = static_cast<int>(i);
                    }
                }
            }
            if (chapterIndex >= 0) {
                const SearchChapterSpan& span = book.chapters[chapterIndex];
                const int chapterCount = static_cast<int>(book.chapters.size());
                const double percent = chapterCount > 1
                    ? (static_cast<double>(chapterIndex) / static_cast<double>(chapterCount - 1)) * 100.0
                    : 0.0;
                // +1：让「第 1 章（0%）」与「无提示（0）」可区分
                hit.page = 1 + static_cast<int>(std::lround(percent * 10.0));
                const long long relative = static_cast<long long>(earliest)
                    - static_cast<long long>(span.start);
                hit.offset = relative > 0 ? relative : 0;
            }
        } else if (book.format == "html" || book.format == "markdown") {
            if (book.textChars > 0) {
                const double percent =
                    (static_cast<double>(earliest) / static_cast<double>(book.textChars)) * 100.0;
                hit.page = 1 + static_cast<int>(std::lround(percent * 10.0));
            }
        }
        hit.score = score;
        hit.termHits = static_cast<int>(constraints.size());
        hit.snippet = MakeSnippet(book.text, earliest, earliestLength, options.snippetContextChars);
        hits.push_back(std::move(hit));
    }

    std::sort(hits.begin(), hits.end(), [](const SearchHit& left, const SearchHit& right) {
        if (left.score != right.score) return left.score > right.score;
        return left.bookId < right.bookId;
    });
    if (options.maxHits > 0 && hits.size() > options.maxHits) {
        hits.resize(options.maxHits);
    }
    result.truncated = static_cast<int>(hits.size()) < result.booksMatched;
    result.hits = std::move(hits);
    return result;
}

// ── 持久化 ───────────────────────────────────────────────────────────

json SearchIndexService::ToJsonLocked() const
{
    json root = json::object();
    root["version"] = kSearchIndexVersion;
    json books = json::array();
    for (const auto& bookPair : m_index.books) {
        const BookRecord& book = *bookPair.second;
        json entry = json::object();
        entry["bookId"] = book.bookId;
        entry["title"] = book.title;
        entry["format"] = book.format;
        entry["filePath"] = book.filePath;
        entry["mtime"] = book.mtime;
        entry["fileSize"] = book.fileSize;
        entry["textChars"] = book.textChars;
        if (!book.text.empty()) entry["text"] = book.text;
        if (!book.chapters.empty()) {
            json chapters = json::array();
            for (const SearchChapterSpan& chapter : book.chapters) {
                chapters.push_back({ { "start", chapter.start }, { "end", chapter.end }, { "href", chapter.href } });
            }
            entry["chapters"] = std::move(chapters);
        }
        json terms = json::object();
        for (const auto& termPair : book.terms) {
            terms[termPair.first] = termPair.second;
        }
        entry["terms"] = std::move(terms);
        books.push_back(std::move(entry));
    }
    root["books"] = std::move(books);
    return root;
}

bool SearchIndexService::Save(std::string* error)
{
    const std::string path = IndexFilePath();
    if (path.empty()) {
        if (error) *error = search_index_codes::kNoDataDir;
        return false;
    }
    return SaveTo(path, error);
}

bool SearchIndexService::SaveTo(const std::string& filePathUtf8, std::string* error)
{
    std::string payload;
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        payload = ToJsonLocked().dump();
    }
    if (error) *error = search_index_codes::kOk;

    std::error_code ec;
    const fs::path target = fs::u8path(filePathUtf8);
    const fs::path parent = target.parent_path();
    if (!parent.empty()) fs::create_directories(parent, ec);  // 失败也让后面的写报出来

    const fs::path temp = fs::u8path(filePathUtf8 + ".tmp");
    {
        std::ofstream out(temp, std::ios::binary | std::ios::trunc);
        if (!out) {
            if (error) *error = search_index_codes::kWrite;
            return false;
        }
        out.write(payload.data(), static_cast<std::streamsize>(payload.size()));
        out.flush();
        if (!out) {
            if (error) *error = search_index_codes::kWrite;
            return false;
        }
    }

    fs::rename(temp, target, ec);
    if (ec) {
        // Windows：目标已存在时 rename 失败 → 先删再 rename（非原子，但可恢复）。
        std::error_code removeEc;
        fs::remove(target, removeEc);
        fs::rename(temp, target, ec);
        if (ec) {
            std::error_code cleanupEc;
            fs::remove(temp, cleanupEc);
            if (error) *error = search_index_codes::kWrite;
            return false;
        }
    }
    return true;
}

bool SearchIndexService::Load(std::string* error)
{
    const std::string path = IndexFilePath();
    if (path.empty()) {
        if (error) *error = search_index_codes::kNoDataDir;
        return false;
    }
    std::error_code ec;
    if (!fs::exists(fs::u8path(path), ec) || ec) {
        // 首次启动：没有索引文件是正常状态。
        if (error) *error = search_index_codes::kOk;
        return true;
    }
    return LoadFrom(path, error);
}

bool SearchIndexService::LoadFrom(const std::string& filePathUtf8, std::string* error)
{
    if (error) *error = search_index_codes::kOk;

    std::string payload;
    if (!ReadFileBytes(filePathUtf8, payload, error)) return false;

    const json root = json::parse(payload, nullptr, /*allow_exceptions=*/false);
    if (root.is_discarded() || !root.is_object()) {
        if (error) *error = search_index_codes::kParse;
        return false;
    }
    if (!root.contains("version") || !root["version"].is_number_integer()) {
        if (error) *error = search_index_codes::kVersion;
        return false;
    }
    const int version = root["version"].get<int>();
    if (version <= 0 || version > kSearchIndexVersion) {
        if (error) *error = search_index_codes::kVersion;
        return false;
    }
    if (!root.contains("books") || !root["books"].is_array()) {
        if (error) *error = search_index_codes::kParse;
        return false;
    }

    IndexData staging;
    for (const json& entry : root["books"]) {
        if (!entry.is_object()) continue;
        auto record = std::make_shared<BookRecord>();
        record->bookId = JsonInt(entry, "bookId", 0);
        if (record->bookId <= 0) continue;
        record->title = JsonString(entry, "title", std::string());
        record->format = NormalizeFormat(JsonString(entry, "format", std::string()));
        record->filePath = JsonString(entry, "filePath", std::string());
        record->mtime = JsonInt(entry, "mtime", 0);
        const long long fileSize = JsonInt(entry, "fileSize", 0);
        record->fileSize = fileSize < 0 ? 0 : static_cast<std::uint64_t>(fileSize);
        auto textIt = entry.find("text");
        if (textIt != entry.end() && textIt->is_string()) {
            record->text = textIt->get<std::string>();
            if (!IsValidUtf8(record->text)) record->text.clear();
        }
        const long long textChars = JsonInt(entry, "textChars", 0);
        record->textChars = textChars <= 0
                ? (record->text.empty() ? 0 : CountCodepoints(record->text))
                : static_cast<std::size_t>(textChars);

        auto chaptersIt = entry.find("chapters");
        if (chaptersIt != entry.end() && chaptersIt->is_array()) {
            for (const json& chapter : *chaptersIt) {
                if (!chapter.is_object()) continue;
                SearchChapterSpan span;
                span.start = static_cast<int>(JsonInt(chapter, "start", 0));
                span.end = static_cast<int>(JsonInt(chapter, "end", 0));
                span.href = JsonString(chapter, "href", std::string());
                if (span.start < 0) span.start = 0;
                if (span.end <= span.start) continue;
                if (record->textChars > 0 && static_cast<std::size_t>(span.start) >= record->textChars) break;
                if (record->textChars > 0 && static_cast<std::size_t>(span.end) > record->textChars) {
                    span.end = static_cast<int>(record->textChars);
                }
                record->chapters.push_back(std::move(span));
            }
        }

        auto termsIt = entry.find("terms");
        if (termsIt != entry.end() && termsIt->is_object()) {
            for (auto termIt = termsIt->begin(); termIt != termsIt->end(); ++termIt) {
                if (!termIt.value().is_array()) continue;
                std::vector<int> positions;
                positions.reserve(termIt.value().size());
                for (const json& position : termIt.value()) {
                    if (!position.is_number_integer()) continue;
                    const long long value = position.get<long long>();
                    if (value < 0 || value > 0x7FFFFFFF) continue;
                    positions.push_back(static_cast<int>(value));
                }
                if (positions.empty()) continue;
                std::sort(positions.begin(), positions.end());
                record->terms.emplace(termIt.key(), std::move(positions));
            }
        }
        if (record->terms.empty()) continue;  // 空词项的书没有索引价值
        staging.books[record->bookId] = std::move(record);
    }

    m_index = std::move(staging);
    RebuildTermIndexLocked();
    return true;
}

} // namespace pb
