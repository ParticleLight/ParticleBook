#include "LibraryService.h"
#include "platform/LogService.h"
#include "utils/ZipReader.h"
#include "utils/encoding.h"
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <fstream>
#include <cstring>
#include <cstdlib>
#include <algorithm>
#include <filesystem>
#include <cstdio>
#include <ctime>

#include "tinyxml2.h"
#include "utils/base64.h"

#include <cstring>
#include <map>
using namespace tinyxml2;

// ── Format Detection ───────────────────────────────────────────────

std::string DetectFormat(const std::string& path)
{
    size_t dot = path.rfind('.');
    size_t slash = path.rfind('\\');
    if (dot == std::string::npos) return "";
    if (slash != std::string::npos && dot < slash) return "";
    std::string ext = path.substr(dot);
    for (auto& c : ext) c = static_cast<char>(std::tolower(c));
    if (ext == ".epub")   return "epub";
    if (ext == ".pdf")    return "pdf";
    if (ext == ".mobi")   return "mobi";
    if (ext == ".azw3" || ext == ".azw") return "azw3";  // PB-008-A：Kindle，走 mutool 文本转换
    if (ext == ".docx")   return "docx";                 // PB-008-A：导入时转成 HTML
    if (ext == ".txt")    return "txt";
    if (ext == ".fb2")    return "fb2";
    if (ext == ".cbz")    return "cbz";
    if (ext == ".cbr")    return "cbr";
    if (ext == ".html" || ext == ".htm") return "html";
    if (ext == ".md")     return "markdown";
    return "";
}

// ── Helpers ────────────────────────────────────────────────────────

// ── EPUB Metadata ──────────────────────────────────────────────────

std::string LibraryService::FindOpfPath(const std::string& epubPath)
{
    std::string containerXml;
    if (!ReadZipEntry(epubPath, "META-INF/container.xml", containerXml)) {
        return "";
    }

    XMLDocument doc;
    if (doc.Parse(containerXml.c_str()) != XML_SUCCESS) {
        return "";
    }

    auto* container = doc.FirstChildElement("container");
    if (!container) return "";
    auto* rootfiles = container->FirstChildElement("rootfiles");
    if (!rootfiles) return "";

    for (auto* rf = rootfiles->FirstChildElement("rootfile"); rf; rf = rf->NextSiblingElement("rootfile")) {
        const char* fullPath = rf->Attribute("full-path");
        if (fullPath) {
            std::string path(fullPath);
            if (path.size() >= 4) {
                std::string ext = path.substr(path.size() - 4);
                for (auto& c : ext) c = static_cast<char>(std::tolower(c));
                if (ext == ".opf") {
                    return std::string(fullPath);
                }
            }
        }
    }
    return "";
}

bool LibraryService::ParseOpfXml(const std::string& xml, const std::string& opfDir,
                                 ExtractedMetadata& meta)
{
    XMLDocument doc;
    if (doc.Parse(xml.c_str()) != XML_SUCCESS) return false;

    auto* pkg = doc.FirstChildElement("package");
    if (!pkg) return false;

    // Metadata
    auto* metadata = pkg->FirstChildElement("metadata");
    if (metadata) {
        auto getText = [](const XMLElement* el) -> std::string {
            return el && el->GetText() ? std::string(el->GetText()) : "";
        };

        // Dublin Core namespace
        meta.title = getText(metadata->FirstChildElement("dc:title"));
        if (meta.title.empty()) meta.title = getText(metadata->FirstChildElement("title"));

        meta.author = getText(metadata->FirstChildElement("dc:creator"));
        if (meta.author.empty()) meta.author = getText(metadata->FirstChildElement("creator"));

        meta.language = getText(metadata->FirstChildElement("dc:language"));
        if (meta.language.empty()) meta.language = getText(metadata->FirstChildElement("language"));

        meta.description = getText(metadata->FirstChildElement("dc:description"));
        if (meta.description.empty()) meta.description = getText(metadata->FirstChildElement("description"));

        meta.publisher = getText(metadata->FirstChildElement("dc:publisher"));
        if (meta.publisher.empty()) meta.publisher = getText(metadata->FirstChildElement("publisher"));

        // ISBN from dc:identifier
        for (auto* idEl = metadata->FirstChildElement("dc:identifier"); idEl;
             idEl = idEl->NextSiblingElement("dc:identifier")) {
            std::string id = getText(idEl);
            if (id.find("978") == 0 || id.find("979") == 0) {
                meta.isbn = id;
                break;
            }
        }
        if (meta.isbn.empty()) {
            for (auto* idEl = metadata->FirstChildElement("identifier"); idEl;
                 idEl = idEl->NextSiblingElement("identifier")) {
                std::string id = getText(idEl);
                if (id.find("978") == 0 || id.find("979") == 0) {
                    meta.isbn = id;
                    break;
                }
            }
        }
    }

    // Find cover image ID from <meta name="cover" content="xxx"/>
    std::string coverId;
    if (metadata) {
        for (auto* metaEl = metadata->FirstChildElement("meta"); metaEl;
             metaEl = metaEl->NextSiblingElement("meta")) {
            const char* name = metaEl->Attribute("name");
            if (name && strcmp(name, "cover") == 0) {
                const char* content = metaEl->Attribute("content");
                if (content) coverId = content;
                break;
            }
        }
    }

    // Manifest: find cover image
    auto* manifest = pkg->FirstChildElement("manifest");
    if (manifest) {
        for (auto* item = manifest->FirstChildElement("item"); item;
             item = item->NextSiblingElement("item")) {
            const char* id = item->Attribute("id");
            const char* href = item->Attribute("href");
            const char* mediaType = item->Attribute("media-type");
            if (!id || !href) continue;

            // Match by <meta name="cover"> reference
            if (!coverId.empty() && strcmp(id, coverId.c_str()) == 0) {
                meta.coverFile = href;
                if (!opfDir.empty() && href[0] != '/') {
                    meta.coverFile = opfDir + "/" + std::string(href);
                }
                break;
            }

            // Match by id containing "cover"
            std::string idStr(id);
            std::transform(idStr.begin(), idStr.end(), idStr.begin(),
                          [](char c) { return static_cast<char>(std::tolower(c)); });

            if (idStr.find("cover") != std::string::npos && mediaType &&
                strstr(mediaType, "image") && meta.coverFile.empty()) {
                meta.coverFile = href;
                if (!opfDir.empty() && href[0] != '/') {
                    meta.coverFile = opfDir + "/" + std::string(href);
                }
                // Don't break — properties="cover-image" might be more specific
            }

            // properties="cover-image"
            const char* props = item->Attribute("properties");
            if (props && strstr(props, "cover-image") && mediaType && strstr(mediaType, "image")) {
                meta.coverFile = href;
                if (!opfDir.empty() && href[0] != '/') {
                    meta.coverFile = opfDir + "/" + std::string(href);
                }
                break; // This is the definitive cover
            }
        }
    }

    return true;
}

bool LibraryService::ExtractEpubMetadata(const std::string& filePath, ExtractedMetadata& meta)
{
    std::string opfPath = FindOpfPath(filePath);
    if (opfPath.empty()) return false;

    // Get OPF directory
    std::string opfDir;
    size_t slash = opfPath.rfind('/');
    if (slash != std::string::npos) opfDir = opfPath.substr(0, slash);

    std::string opfXml;
    if (!ReadZipEntry(filePath, opfPath, opfXml)) return false;

    bool result = ParseOpfXml(opfXml, opfDir, meta);

    // 统一走 LogService（有滚动/脱敏），不再往 exe 目录追加 debug.log：
    // 那边既无上限增长、又会把本地书路径写到一个随手可读的文件里（review · B-13）。
    pb::LogDebug("epub", "ExtractEpubMetadata: opfPath=" + opfPath +
                             " coverFile=" + meta.coverFile);

    return result;
}

std::string LibraryService::ExtractEpubCover(const std::string& filePath,
                                             const std::string& coverFile)
{
    if (coverFile.empty()) return "";

    // Extract cover to a temp file next to the epub
    std::string outPath = filePath + ".cover";
    size_t dot = outPath.rfind('.');
    size_t coverDot = coverFile.rfind('.');
    if (dot != std::string::npos && coverDot != std::string::npos) {
        outPath = outPath.substr(0, dot) + coverFile.substr(coverDot);
    }

    std::string data;
    if (!ReadZipEntry(filePath, coverFile, data)) return "";

    // Write to file using Win32 API for proper Unicode support
    std::wstring wOutPath = pb::Utf8ToWide(outPath);
    HANDLE hFile = CreateFileW(wOutPath.c_str(), GENERIC_WRITE, 0, nullptr,
                               CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (hFile == INVALID_HANDLE_VALUE) return "";

    DWORD written = 0;
    WriteFile(hFile, data.data(), static_cast<DWORD>(data.size()), &written, nullptr);
    CloseHandle(hFile);

    return outPath;
}

std::string LibraryService::ExtractPdfCover(const std::string& filePath)
{
    // Render first page with mutool as cover thumbnail
    std::string outPath = filePath + ".cover.png";

    // 已经生成过就直接复用：book:cover 每次进书架都会问一次，每次都起 mutool
    // 等于「书架上无封面的 PDF 数量 × 每次渲染」。封面比源文件旧 → 说明书被换过，
    // 重新渲染（与 _pb_files 的 mtime+size 版本化同一思路）。
    WIN32_FILE_ATTRIBUTE_DATA coverInfo = {};
    WIN32_FILE_ATTRIBUTE_DATA bookInfo = {};
    if (GetFileAttributesExW(pb::Utf8ToWide(outPath).c_str(), GetFileExInfoStandard, &coverInfo) &&
        coverInfo.nFileSizeHigh == 0 && coverInfo.nFileSizeLow > 0 &&
        GetFileAttributesExW(pb::Utf8ToWide(filePath).c_str(), GetFileExInfoStandard, &bookInfo) &&
        CompareFileTime(&coverInfo.ftLastWriteTime, &bookInfo.ftLastWriteTime) >= 0) {
        return outPath;
    }

    // Run mutool draw to render page 1
    std::string mutoolPath;
    wchar_t exePath[MAX_PATH];
    GetModuleFileNameW(nullptr, exePath, MAX_PATH);
    mutoolPath = std::filesystem::path(exePath).parent_path().string() + "\\mutool.exe";

    std::string cmdLine = "\"" + mutoolPath + "\" draw -o \"" + outPath + "\" -r 120 -w 300 -h 400 -F png \"" + filePath + "\" 1";

    PROCESS_INFORMATION pi = {};
    STARTUPINFOW si = { sizeof(STARTUPINFOW) };
    std::wstring wCmdLine = pb::Utf8ToWide(cmdLine);

    if (CreateProcessW(nullptr, wCmdLine.data(), nullptr, nullptr, FALSE,
                       CREATE_NO_WINDOW | NORMAL_PRIORITY_CLASS,
                       nullptr, nullptr, &si, &pi)) {
        // 超时后必须 TerminateProcess 并等它退出：否则卡住的 mutool 会一直挂在后台
        // （每次调用泄漏一个进程），而且调用方照样得等到超时才拿得到返回。
        if (WaitForSingleObject(pi.hProcess, 30000) == WAIT_TIMEOUT) {
            TerminateProcess(pi.hProcess, 1);
            WaitForSingleObject(pi.hProcess, INFINITE);
        }
        CloseHandle(pi.hProcess);
        CloseHandle(pi.hThread);
    }

    // 输出必须是「存在且非空」的文件：被 kill 掉的 mutool 会留下 0 字节 PNG，
    // 直接返回它只会让前端拿到一张坏图。
    if (!GetFileAttributesExW(pb::Utf8ToWide(outPath).c_str(), GetFileExInfoStandard, &coverInfo) ||
        (coverInfo.nFileSizeHigh == 0 && coverInfo.nFileSizeLow == 0)) {
        return "";
    }

    return outPath;
}

// ── PDF Metadata ───────────────────────────────────────────────────

bool LibraryService::ExtractPdfMetadata(const std::string& filePath, ExtractedMetadata& meta)
{
    // Basic PDF header check: look for /Title, /Author in raw bytes
    std::string wPath = filePath;
    HANDLE hFile = CreateFileW(pb::Utf8ToWide(filePath).c_str(), GENERIC_READ, FILE_SHARE_READ,
                               nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (hFile == INVALID_HANDLE_VALUE) return false;

    // Read first 8KB to find PDF info
    char buf[8192] = {};
    DWORD bytesRead = 0;
    ReadFile(hFile, buf, sizeof(buf) - 1, &bytesRead, nullptr);
    CloseHandle(hFile);

    // Check PDF header
    if (strncmp(buf, "%PDF", 4) != 0) return false;

    std::string content(buf, bytesRead);

    auto extractField = [&content](const char* field) -> std::string {
        std::string search = std::string("/") + field + " (";
        size_t pos = content.find(search);
        if (pos == std::string::npos) {
            search = std::string("/") + field + "(";
            pos = content.find(search);
        }
        if (pos == std::string::npos) return "";
        pos += search.length();
        size_t end = content.find(')', pos);
        if (end == std::string::npos) return "";
        return content.substr(pos, end - pos);
    };

    meta.title = extractField("Title");
    if (meta.title.empty()) meta.title = extractField("Title");

    meta.author = extractField("Author");

    return !meta.title.empty() || !meta.author.empty();
}

// ── FB2 Metadata ───────────────────────────────────────────────────

bool LibraryService::ExtractFb2Metadata(const std::string& filePath, ExtractedMetadata& meta)
{
    std::ifstream f(filePath, std::ios::binary);
    if (!f.is_open()) return false;

    std::string xml((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());

    XMLDocument doc;
    if (doc.Parse(xml.c_str()) != XML_SUCCESS) return false;

    auto* desc = doc.FirstChildElement("FictionBook") ?
        doc.FirstChildElement("FictionBook")->FirstChildElement("description") : nullptr;
    if (!desc) return false;

    auto* titleInfo = desc->FirstChildElement("title-info");
    if (titleInfo) {
        auto getText = [](const XMLElement* el) -> std::string {
            return el && el->GetText() ? std::string(el->GetText()) : "";
        };

        auto* bookTitle = titleInfo->FirstChildElement("book-title");
        meta.title = getText(bookTitle);

        auto* author = titleInfo->FirstChildElement("author");
        if (author) {
            std::string first = getText(author->FirstChildElement("first-name"));
            std::string last = getText(author->FirstChildElement("last-name"));
            if (!first.empty() || !last.empty()) {
                meta.author = first + (first.empty() || last.empty() ? "" : " ") + last;
            }
        }

        meta.language = getText(titleInfo->FirstChildElement("lang"));
        meta.description = getText(titleInfo->FirstChildElement("annotation"));
    }

    return !meta.title.empty();
}

// ── PB-008-A: DOCX (OOXML) → self-contained HTML ───────────────────
namespace {

std::string LowerAscii(std::string value)
{
    for (char& c : value) {
        if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
    }
    return value;
}

std::string EscapeHtmlText(const std::string& text)
{
    std::string out;
    out.reserve(text.size());
    for (const char c : text) {
        switch (c) {
        case '&': out += "&amp;"; break;
        case '<': out += "&lt;"; break;
        case '>': out += "&gt;"; break;
        case '"': out += "&quot;"; break;
        case '\'': out += "&#39;"; break;
        default: out.push_back(c); break;
        }
    }
    return out;
}

// OOXML 关系目标 → zip 条目名（相对 word/ 目录，处理 ./ 与 ../）。
std::string ResolveDocxTarget(const std::string& target)
{
    if (target.empty()) return "";
    std::string path = target;
    for (char& c : path) {
        if (c == '\\') c = '/';
    }
    if (!path.empty() && path[0] == '/') return path.substr(1);
    std::vector<std::string> parts{ "word" };
    std::size_t start = 0;
    while (start <= path.size()) {
        const std::size_t slash = path.find('/', start);
        const std::string part = path.substr(start, slash == std::string::npos ? std::string::npos : slash - start);
        if (part == "..") {
            if (!parts.empty()) parts.pop_back();
        } else if (!part.empty() && part != ".") {
            parts.push_back(part);
        }
        if (slash == std::string::npos) break;
        start = slash + 1;
    }
    std::string out;
    for (const std::string& part : parts) {
        if (!out.empty()) out += '/';
        out += part;
    }
    return out;
}

std::string ImageMimeFromBytes(const std::string& data, std::string& ext)
{
    const auto starts = [&data](const char* sig, std::size_t len) {
        return data.size() >= len && std::memcmp(data.data(), sig, len) == 0;
    };
    if (starts("\x89PNG\r\n\x1a\n", 8)) { ext = "png"; return "image/png"; }
    if (starts("\xFF\xD8\xFF", 3)) { ext = "jpg"; return "image/jpeg"; }
    if (starts("GIF8", 4)) { ext = "gif"; return "image/gif"; }
    if (data.size() > 12 && data.compare(0, 4, "RIFF") == 0 && data.compare(8, 4, "WEBP") == 0) {
        ext = "webp";
        return "image/webp";
    }
    ext = "bin";
    return "";
}

struct DocxInlineContext {
    const std::string* docxPath = nullptr;
    std::map<std::string, std::string> rels;   // rId → target
    std::string coverData;
    std::string coverExt;
    int imageCount = 0;
};

void FindBlipEmbed(XMLElement* node, std::string& embedId, int depth)
{
    if (!node || depth > 12 || !embedId.empty()) return;
    for (XMLElement* child = node->FirstChildElement(); child; child = child->NextSiblingElement()) {
        if (std::strcmp(child->Name(), "a:blip") == 0) {
            if (const char* value = child->Attribute("r:embed")) {
                embedId = value;
                return;
            }
        }
        FindBlipEmbed(child, embedId, depth + 1);
        if (!embedId.empty()) return;
    }
}

void AppendDocxImage(DocxInlineContext& context, const std::string& relId, std::string& out)
{
    const auto found = context.rels.find(relId);
    if (found == context.rels.end()) return;
    const std::string entry = ResolveDocxTarget(found->second);
    if (entry.empty()) return;
    std::string data;
    if (!ReadZipEntry(*context.docxPath, entry, data)) return;
    if (data.empty() || data.size() > 32ull * 1024ull * 1024ull) return;
    std::string ext;
    const std::string mime = ImageMimeFromBytes(data, ext);
    if (mime.empty()) return;
    out += "<img src=\"data:" + mime + ";base64," + pb::Base64Encode(data) +
           "\" alt=\"\" style=\"max-width:100%;height:auto;display:block;margin:12px 0;\" />";
    if (context.coverData.empty()) {
        context.coverData = data;
        context.coverExt = ext;
    }
    ++context.imageCount;
}

void AppendDocxInline(DocxInlineContext& context, XMLElement* node, std::string& out, int depth)
{
    if (!node || depth > 40) return;
    for (XMLElement* child = node->FirstChildElement(); child; child = child->NextSiblingElement()) {
        const char* name = child->Name() ? child->Name() : "";
        if (std::strcmp(name, "w:t") == 0) {
            const char* text = child->GetText();
            if (text) out += EscapeHtmlText(text);
        } else if (std::strcmp(name, "w:tab") == 0) {
            out += "&nbsp;&nbsp;";
        } else if (std::strcmp(name, "w:br") == 0) {
            out += "<br/>";
        } else if (std::strcmp(name, "w:r") == 0) {
            bool bold = false;
            bool italic = false;
            if (XMLElement* runProps = child->FirstChildElement("w:rPr")) {
                if (XMLElement* flag = runProps->FirstChildElement("w:b")) {
                    const char* value = flag->Attribute("w:val");
                    bold = !(value && (std::strcmp(value, "0") == 0 || LowerAscii(value) == "false"));
                }
                if (XMLElement* flag = runProps->FirstChildElement("w:i")) {
                    const char* value = flag->Attribute("w:val");
                    italic = !(value && (std::strcmp(value, "0") == 0 || LowerAscii(value) == "false"));
                }
            }
            std::string inner;
            AppendDocxInline(context, child, inner, depth + 1);
            if (bold) inner = "<strong>" + inner + "</strong>";
            if (italic) inner = "<em>" + inner + "</em>";
            out += inner;
        } else if (std::strcmp(name, "w:drawing") == 0 || std::strcmp(name, "w:pict") == 0 ||
                   std::strcmp(name, "w:object") == 0) {
            std::string embedId;
            FindBlipEmbed(child, embedId, 0);
            if (!embedId.empty()) AppendDocxImage(context, embedId, out);
        } else if (std::strcmp(name, "w:hyperlink") == 0 || std::strcmp(name, "w:ins") == 0 ||
                   std::strcmp(name, "w:smartTag") == 0 || std::strcmp(name, "w:sdt") == 0 ||
                   std::strcmp(name, "w:sdtContent") == 0 || std::strcmp(name, "w:fldSimple") == 0) {
            AppendDocxInline(context, child, out, depth + 1);
        }
    }
}

std::string ParagraphStyle(XMLElement* paragraph)
{
    XMLElement* props = paragraph ? paragraph->FirstChildElement("w:pPr") : nullptr;
    XMLElement* style = props ? props->FirstChildElement("w:pStyle") : nullptr;
    const char* value = style ? style->Attribute("w:val") : nullptr;
    return value ? value : "";
}

int HeadingLevelFromStyle(const std::string& style)
{
    if (style == "Title") return 1;
    if (style == "Subtitle") return 2;
    const std::string lower = LowerAscii(style);
    std::string digits;
    for (const char c : lower) {
        if (c >= '0' && c <= '9') digits.push_back(c);
    }
    if (!digits.empty()) {
        const int level = digits[0] - '0';
        if (level >= 1 && level <= 6) return level;
    }
    return 0;
}

// 按本地名（忽略命名空间前缀）找第一个非空文本。
std::string FindTextByLocalName(XMLElement* node, const std::string& localName, int depth)
{
    if (!node || depth > 8) return "";
    for (XMLElement* child = node->FirstChildElement(); child; child = child->NextSiblingElement()) {
        const char* rawName = child->Name() ? child->Name() : "";
        std::string name = rawName;
        const std::size_t colon = name.find(':');
        if (colon != std::string::npos) name = name.substr(colon + 1);
        if (name == localName) {
            const char* text = child->GetText();
            if (text && text[0] != '\0') return text;
        }
        const std::string nested = FindTextByLocalName(child, localName, depth + 1);
        if (!nested.empty()) return nested;
    }
    return "";
}

std::string RenderDocxParagraph(DocxInlineContext& context, XMLElement* paragraph, int& paragraphCount)
{
    const std::string style = ParagraphStyle(paragraph);
    const int headingLevel = HeadingLevelFromStyle(style);
    std::string inlineHtml;
    AppendDocxInline(context, paragraph, inlineHtml, 0);
    if (inlineHtml.empty()) return "";
    ++paragraphCount;
    if (headingLevel > 0) {
        const std::string tag = "h" + std::to_string(headingLevel);
        return "<" + tag + ">" + inlineHtml + "</" + tag + ">";
    }
    return "<p>" + inlineHtml + "</p>";
}

std::string RenderDocxTable(DocxInlineContext& context, XMLElement* table, int& paragraphCount)
{
    std::string html = "<table>";
    for (XMLElement* row = table->FirstChildElement("w:tr"); row; row = row->NextSiblingElement("w:tr")) {
        html += "<tr>";
        for (XMLElement* cell = row->FirstChildElement("w:tc"); cell; cell = cell->NextSiblingElement("w:tc")) {
            std::string cellHtml;
            for (XMLElement* paragraph = cell->FirstChildElement("w:p"); paragraph;
                 paragraph = paragraph->NextSiblingElement("w:p")) {
                std::string inlineHtml;
                AppendDocxInline(context, paragraph, inlineHtml, 0);
                if (inlineHtml.empty()) continue;
                if (!cellHtml.empty()) cellHtml += "<br/>";
                cellHtml += inlineHtml;
                ++paragraphCount;
            }
            html += "<td>" + cellHtml + "</td>";
        }
        html += "</tr>";
    }
    return html + "</table>";
}

} // namespace

DocxConversion LibraryService::ConvertDocxToHtml(const std::string& filePath)
{
    DocxConversion result;

    std::string documentXml;
    if (!ReadZipEntry(filePath, "word/document.xml", documentXml)) {
        result.error = "missing_document";
        return result;
    }
    if (documentXml.size() > 64ull * 1024ull * 1024ull) {
        result.error = "document_too_large";
        return result;
    }
    XMLDocument document;
    if (document.Parse(documentXml.c_str(), documentXml.size()) != XML_SUCCESS) {
        result.error = "bad_xml";
        return result;
    }
    XMLElement* root = document.RootElement();
    XMLElement* body = root ? root->FirstChildElement("w:body") : nullptr;
    if (!body) {
        result.error = "missing_body";
        return result;
    }

    DocxInlineContext context;
    context.docxPath = &filePath;

    std::string relsXml;
    if (ReadZipEntry(filePath, "word/_rels/document.xml.rels", relsXml)) {
        XMLDocument relsDocument;
        if (relsDocument.Parse(relsXml.c_str(), relsXml.size()) == XML_SUCCESS) {
            for (XMLElement* rel = relsDocument.RootElement()
                                       ? relsDocument.RootElement()->FirstChildElement("Relationship")
                                       : nullptr;
                 rel; rel = rel->NextSiblingElement("Relationship")) {
                const char* id = rel->Attribute("Id");
                const char* target = rel->Attribute("Target");
                if (id && target) context.rels[id] = target;
            }
        }
    }

    std::string coreXml;
    if (ReadZipEntry(filePath, "docProps/core.xml", coreXml)) {
        XMLDocument coreDocument;
        if (coreDocument.Parse(coreXml.c_str(), coreXml.size()) == XML_SUCCESS) {
            result.title = FindTextByLocalName(coreDocument.RootElement(), "title", 0);
            result.author = FindTextByLocalName(coreDocument.RootElement(), "creator", 0);
            result.language = FindTextByLocalName(coreDocument.RootElement(), "language", 0);
        }
    }

    std::string html = "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\" />\n<title>";
    html += EscapeHtmlText(result.title);
    html += "</title>\n<style>body{font-family:Georgia,'Times New Roman',serif;line-height:1.7;"
            "margin:24px auto;padding:0 18px;max-width:52em;color:#1f2937;}"
            "p{margin:0 0 0.9em;}h1,h2,h3,h4,h5,h6{line-height:1.3;margin:1.2em 0 0.6em;}"
            "img{max-width:100%;height:auto;}table{border-collapse:collapse;margin:0.8em 0;}"
            "td{border:1px solid #ddd;padding:4px 8px;vertical-align:top;}"
            "strong{font-weight:600;}</style>\n</head>\n<body>\n";

    for (XMLElement* child = body->FirstChildElement(); child; child = child->NextSiblingElement()) {
        const char* name = child->Name() ? child->Name() : "";
        if (std::strcmp(name, "w:p") == 0) {
            html += RenderDocxParagraph(context, child, result.paragraphCount);
        } else if (std::strcmp(name, "w:tbl") == 0) {
            html += RenderDocxTable(context, child, result.paragraphCount);
        } else if (std::strcmp(name, "w:sdt") == 0) {
            for (XMLElement* paragraph = child->FirstChildElement("w:sdtContent");
                 paragraph; paragraph = nullptr) {
                for (XMLElement* inner = paragraph->FirstChildElement("w:p"); inner;
                     inner = inner->NextSiblingElement("w:p")) {
                    html += RenderDocxParagraph(context, inner, result.paragraphCount);
                }
            }
        }
    }
    html += "\n</body>\n</html>\n";

    if (result.paragraphCount == 0 && context.imageCount == 0) {
        result.error = "empty_document";
        return result;
    }

    result.html = std::move(html);
    result.imageCount = context.imageCount;
    result.coverData = std::move(context.coverData);
    result.coverExt = std::move(context.coverExt);
    result.ok = true;
    return result;
}

