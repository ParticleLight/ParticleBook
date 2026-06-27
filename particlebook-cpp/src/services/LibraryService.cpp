#include "LibraryService.h"
#include "utils/ZipReader.h"
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
    if (ext == ".txt")    return "txt";
    if (ext == ".fb2")    return "fb2";
    if (ext == ".cbz")    return "cbz";
    if (ext == ".cbr")    return "cbr";
    if (ext == ".html" || ext == ".htm") return "html";
    if (ext == ".md")     return "markdown";
    return "";
}

// ── Helpers ────────────────────────────────────────────────────────

static std::string WideToUtf8(LPCWSTR w)
{
    int len = WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0, nullptr, nullptr);
    std::string s(len, '\0');
    WideCharToMultiByte(CP_UTF8, 0, w, -1, &s[0], len, nullptr, nullptr);
    while (!s.empty() && s.back() == '\0') s.pop_back();
    return s;
}

static std::wstring Utf8ToWide(const std::string& s)
{
    if (s.empty()) return L"";
    int len = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), nullptr, 0);
    if (len <= 0) return L"";
    std::wstring w(len, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), &w[0], len);
    return w;
}

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

    // Debug log
    wchar_t exePath[MAX_PATH];
    GetModuleFileNameW(nullptr, exePath, MAX_PATH);
    auto logPath = std::filesystem::path(exePath).parent_path() / "debug.log";
    FILE* lf = _wfopen(logPath.c_str(), L"a");
    if (lf) {
        time_t now = time(nullptr);
        char timeBuf[32];
        tm localTm; localtime_s(&localTm, &now);
        strftime(timeBuf, sizeof(timeBuf), "%H:%M:%S", &localTm);
        fprintf(lf, "[%s] ExtractEpubMetadata: opfPath=%s coverFile=%s\n",
                timeBuf, opfPath.c_str(), meta.coverFile.c_str());
        fclose(lf);
    }

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
    std::wstring wOutPath = Utf8ToWide(outPath);
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

    // Run mutool draw to render page 1
    std::string mutoolPath;
    wchar_t exePath[MAX_PATH];
    GetModuleFileNameW(nullptr, exePath, MAX_PATH);
    mutoolPath = std::filesystem::path(exePath).parent_path().string() + "\\mutool.exe";

    std::string cmdLine = "\"" + mutoolPath + "\" draw -o \"" + outPath + "\" -r 120 -w 300 -h 400 -F png \"" + filePath + "\" 1";

    PROCESS_INFORMATION pi = {};
    STARTUPINFOW si = { sizeof(STARTUPINFOW) };
    std::wstring wCmdLine = Utf8ToWide(cmdLine);

    if (CreateProcessW(nullptr, wCmdLine.data(), nullptr, nullptr, FALSE,
                       CREATE_NO_WINDOW | NORMAL_PRIORITY_CLASS,
                       nullptr, nullptr, &si, &pi)) {
        WaitForSingleObject(pi.hProcess, 30000);
        CloseHandle(pi.hProcess);
        CloseHandle(pi.hThread);
    }

    // Check if the output file was created
    DWORD attr = GetFileAttributesW(Utf8ToWide(outPath).c_str());
    if (attr == INVALID_FILE_ATTRIBUTES) return "";

    return outPath;
}

// ── PDF Metadata ───────────────────────────────────────────────────

bool LibraryService::ExtractPdfMetadata(const std::string& filePath, ExtractedMetadata& meta)
{
    // Basic PDF header check: look for /Title, /Author in raw bytes
    std::string wPath = filePath;
    HANDLE hFile = CreateFileW(Utf8ToWide(filePath).c_str(), GENERIC_READ, FILE_SHARE_READ,
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
