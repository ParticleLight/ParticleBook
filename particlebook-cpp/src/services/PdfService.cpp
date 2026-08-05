#include "PdfService.h"
#include "BridgeServer.h"
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <atomic>
#include <algorithm>
#include <cstdio>
#include <cstring>
#include <sstream>
#include <fstream>
#include <filesystem>
#include <ctime>
#include <vector>

// ── Helpers ─────────────────────────────────────────────────────────

static std::string WideToUtf8(LPCWSTR w)
{
    int len = WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0, nullptr, nullptr);
    if (len <= 0) return "";
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

static std::string Base64Encode(const void* data, size_t len)
{
    static const char* b64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string result;
    const uint8_t* p = static_cast<const uint8_t*>(data);
    int val = 0, valb = -6;
    for (size_t i = 0; i < len; i++) {
        val = (val << 8) + p[i];
        valb += 8;
        while (valb >= 0) {
            result.push_back(b64[(val >> valb) & 0x3F]);
            valb -= 6;
        }
    }
    if (valb > -6) result.push_back(b64[((val << 8) >> (valb + 8)) & 0x3F]);
    while (result.size() % 4) result.push_back('=');
    return result;
}

// ── Constructor / Destructor ─────────────────────────────────────────

PdfService::PdfService() : m_nextId(static_cast<int>(GetTickCount() & 0x7FFFFFFF))
{
    // Randomize the per-session document-id base so rendered PNG files
    // (doc_<id>_page_<n>.png) never collide with a previous run's cache.
}
PdfService::~PdfService()
{
    // Clean up temp dirs
    for (auto& doc : m_docs) {
        std::error_code ec;
        std::string tmpDir = doc.filePath + ".mutool_tmp";
        std::filesystem::remove_all(Utf8ToWide(tmpDir), ec);
    }
    // Clean up patched MOBI temp files
    for (auto& tf : m_tempFiles) {
        DeleteFileW(Utf8ToWide(tf).c_str());
    }
}

// ── Get mutool path ──────────────────────────────────────────────────

std::string PdfService::GetMutoolPath()
{
    wchar_t exePath[MAX_PATH];
    GetModuleFileNameW(nullptr, exePath, MAX_PATH);
    auto p = std::filesystem::path(exePath).parent_path() / "mutool.exe";
    return p.string();
}

// ── MOBI encoding patch ────────────────────────────────────────────────
// MuPDF's mobi.c defaults text_encoding to LATIN_1 (0) when the MOBI header
// is missing or incomplete. Most Chinese MOBI files are UTF-8. Patch the
// encoding field so mutool renders CJK text correctly.

static std::string PatchMobiEncoding(const std::string& filePath)
{
    std::string ext;
    size_t dot = filePath.rfind('.');
    if (dot != std::string::npos) {
        ext = filePath.substr(dot);
        for (auto& c : ext) c = (char)tolower((unsigned char)c);
    }
    if (ext != ".mobi" && ext != ".azw" && ext != ".azw3") return filePath;

    // Read entire file
    HANDLE hFile = CreateFileW(Utf8ToWide(filePath).c_str(), GENERIC_READ,
                                FILE_SHARE_READ, nullptr, OPEN_EXISTING,
                                FILE_ATTRIBUTE_NORMAL, nullptr);
    if (hFile == INVALID_HANDLE_VALUE) return filePath;

    LARGE_INTEGER liSize;
    GetFileSizeEx(hFile, &liSize);
    size_t size = (size_t)liSize.QuadPart;
    if (size == 0 || size > 100 * 1024 * 1024) { CloseHandle(hFile); return filePath; }

    std::vector<uint8_t> data(size);
    DWORD bytesRead = 0;
    ReadFile(hFile, data.data(), (DWORD)size, &bytesRead, nullptr);
    CloseHandle(hFile);
    if (bytesRead != size) return filePath;

    // Search for "MOBI" magic and patch text_encoding (offset +12 from "MOBI")
    bool patched = false;
    for (size_t i = 0; i + 16 < size; i++) {
        if (data[i] == 'M' && data[i+1] == 'O' && data[i+2] == 'B' && data[i+3] == 'I') {
            size_t encOff = i + 12; // skip magic(4) + header_len(4) + mobi_type(4)
            if (encOff + 4 <= size) {
                uint32_t enc = data[encOff] | ((uint32_t)data[encOff+1] << 8)
                             | ((uint32_t)data[encOff+2] << 16) | ((uint32_t)data[encOff+3] << 24);
                // 0 = Latin-1, 1252 = CP1252 → change to 65001 = UTF-8
                if (enc == 0 || enc == 1252) {
                    uint32_t utf8 = 65001;
                    data[encOff]   = (uint8_t)(utf8 & 0xFF);
                    data[encOff+1] = (uint8_t)((utf8 >> 8) & 0xFF);
                    data[encOff+2] = (uint8_t)((utf8 >> 16) & 0xFF);
                    data[encOff+3] = (uint8_t)((utf8 >> 24) & 0xFF);
                    patched = true;
                }
            }
            break; // only first MOBI header matters
        }
    }
    if (!patched) return filePath;

    // Write patched copy
    wchar_t tmpPath[MAX_PATH], tmpFile[MAX_PATH];
    GetTempPathW(MAX_PATH, tmpPath);
    GetTempFileNameW(tmpPath, L"mbp", 0, tmpFile);
    std::string patchedPath = WideToUtf8(tmpFile);

    // Rename to keep original extension so mutool detects format
    DeleteFileW(tmpFile);
    // Unique temp name per patch: a per-process counter prevents two MOBIs
    // patched in the same session from overwriting each other's file
    // (previously a fixed pb_mobi_<pid><ext> collided → wrong book rendered).
    static std::atomic<int> g_mobiSeq{0};
    std::wstring tmpDir = tmpPath;
    std::wstring fixedName = L"pb_mobi_" + std::to_wstring(GetCurrentProcessId())
                           + L"_" + std::to_wstring(g_mobiSeq++) + Utf8ToWide(ext);
    patchedPath = WideToUtf8((tmpDir + fixedName).c_str());

    HANDLE hOut = CreateFileW((tmpDir + fixedName).c_str(), GENERIC_WRITE, 0, nullptr,
                               CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (hOut == INVALID_HANDLE_VALUE) return filePath;

    DWORD written = 0;
    WriteFile(hOut, data.data(), (DWORD)size, &written, nullptr);
    CloseHandle(hOut);
    if (written != size) { DeleteFileW((tmpDir + fixedName).c_str()); return filePath; }

    return patchedPath;
}

// ── Run mutool subprocess ────────────────────────────────────────────

bool PdfService::RunMutool(const std::string& args, std::string& output, int timeoutMs)
{
    // Create temp file for output
    wchar_t tmpPath[MAX_PATH];
    GetTempPathW(MAX_PATH, tmpPath);
    wchar_t tmpFile[MAX_PATH];
    GetTempFileNameW(tmpPath, L"mup", 0, tmpFile);

    // Open temp file handle for child process stdout
    SECURITY_ATTRIBUTES sa = { sizeof(SECURITY_ATTRIBUTES), nullptr, TRUE };
    HANDLE hOutFile = CreateFileW(tmpFile, GENERIC_WRITE, FILE_SHARE_READ, &sa,
                                  CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (hOutFile == INVALID_HANDLE_VALUE) return false;

    PROCESS_INFORMATION pi = {};
    STARTUPINFOW si = { sizeof(STARTUPINFOW) };
    si.dwFlags = STARTF_USESTDHANDLES;
    si.hStdOutput = hOutFile;
    si.hStdError = hOutFile;

    std::string cmdLine = "\"" + GetMutoolPath() + "\" " + args;
    std::wstring wCmdLine = Utf8ToWide(cmdLine);

    BOOL ok = CreateProcessW(nullptr, wCmdLine.data(), nullptr, nullptr, TRUE,
                             CREATE_NO_WINDOW | NORMAL_PRIORITY_CLASS,
                             nullptr, nullptr, &si, &pi);
    CloseHandle(hOutFile);

    if (!ok) {
        DeleteFileW(tmpFile);
        return false;
    }

    if (WaitForSingleObject(pi.hProcess, timeoutMs) == WAIT_TIMEOUT) {
        TerminateProcess(pi.hProcess, 1);
        WaitForSingleObject(pi.hProcess, INFINITE);
    }
    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);

    // Read from temp file
    HANDLE hReadFile = CreateFileW(tmpFile, GENERIC_READ, FILE_SHARE_READ, nullptr,
                                   OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (hReadFile != INVALID_HANDLE_VALUE) {
        DWORD size = GetFileSize(hReadFile, nullptr);
        if (size > 0 && size < 10 * 1024 * 1024) {
            std::vector<char> buf(size + 1);
            DWORD read = 0;
            ReadFile(hReadFile, buf.data(), size, &read, nullptr);
            buf[read] = '\0';
            output = buf.data();
        }
        CloseHandle(hReadFile);
    }

    DeleteFileW(tmpFile);
    return !output.empty();
}

// ── Open PDF ─────────────────────────────────────────────────────────

PdfOpenResult PdfService::Open(const std::string& filePath)
{
    PdfOpenResult result = {};
    result.id = m_nextId++;

    // Patch MOBI encoding before mutool (MuPDF defaults to Latin-1)
    std::string actualPath = PatchMobiEncoding(filePath);
    if (actualPath != filePath) m_tempFiles.push_back(actualPath);

    // Debug log
    wchar_t exePath[MAX_PATH];
    GetModuleFileNameW(nullptr, exePath, MAX_PATH);
    auto logPath = std::filesystem::path(exePath).parent_path() / "debug.log";
    auto logMsg = [&](const char* msg) {
        FILE* lf = _wfopen(logPath.c_str(), L"a");
        if (lf) {
            time_t now = time(nullptr);
            char timeBuf[32];
            tm localTm; localtime_s(&localTm, &now);
            strftime(timeBuf, sizeof(timeBuf), "%H:%M:%S", &localTm);
            fprintf(lf, "[%s] PdfService::Open: %s\n", timeBuf, msg);
            fclose(lf);
        }
    };

    logMsg(("opening " + actualPath).c_str());

    // Check file exists
    if (GetFileAttributesW(Utf8ToWide(actualPath).c_str()) == INVALID_FILE_ATTRIBUTES) {
        logMsg("file not found");
        return result;
    }

    // Get page info with mutool pages
    std::string output;
    std::string args = "pages \"" + actualPath + "\"";
    if (!RunMutool(args, output)) {
        logMsg("mutool failed to run");
        return result;
    }
    logMsg(("mutool output: " + output).c_str());

    // Parse mutool pages XML output
    // Format: <page pagenum="0"><MediaBox l="0" b="0" r="595" t="842"/></page>
    std::vector<PdfPageBounds> bounds;
    size_t pos = 0;
    while (true) {
        size_t pageStart = output.find("<page", pos);
        if (pageStart == std::string::npos) break;
        size_t pageEnd = output.find("</page>", pageStart);
        if (pageEnd == std::string::npos) break;
        std::string pageXml = output.substr(pageStart, pageEnd + 7 - pageStart);

        // Extract MediaBox attributes: r="..." t="..."
        double w = 0, h = 0;
        auto extractAttr = [&](const char* attr) -> double {
            std::string search = std::string(attr) + "=\"";
            size_t p = pageXml.find(search);
            if (p == std::string::npos) return 0;
            p += search.length();
            size_t e = pageXml.find('"', p);
            if (e == std::string::npos) return 0;
            try { return std::stod(pageXml.substr(p, e - p)); }
            catch (...) { return 0; }
        };
        w = extractAttr("r");
        h = extractAttr("t");
        if (w > 0 && h > 0) bounds.push_back({w, h});

        pos = pageEnd + 7;
    }

    result.pageCount = static_cast<uint32_t>(bounds.size());
    result.pageBounds = bounds;

    if (result.pageCount > 0) {
        DocEntry entry;
        entry.id = result.id;
        entry.filePath = actualPath;
        entry.pageCount = result.pageCount;
        entry.pageBounds = bounds;
        m_docs.push_back(std::move(entry));
    }

    return result;
}

// ── Render Page ──────────────────────────────────────────────────────

std::string PdfService::RenderPage(int id, uint32_t pageIndex, int pixelWidth, int pixelHeight)
{
    DocEntry* entry = nullptr;
    for (auto& e : m_docs) {
        if (e.id == id) { entry = &e; break; }
    }
    if (!entry || pageIndex >= entry->pageCount) return "";

    // Get renderer directory (mapped to particlebook.app virtual host)
    wchar_t exePathBuf[MAX_PATH];
    GetModuleFileNameW(nullptr, exePathBuf, MAX_PATH);
    auto rendererDir = (std::filesystem::path(exePathBuf).parent_path() / "renderer" / "_pb_pdf").string();
    std::filesystem::create_directories(Utf8ToWide(rendererDir));

    std::string fileName = "doc_" + std::to_string(id) + "_page_" + std::to_string(pageIndex) + ".png";
    std::string outFile = rendererDir + "\\" + fileName;
    std::string url = "http://particlebook.app/_pb_pdf/" + fileName;

    // Skip if already rendered
    if (GetFileAttributesW(Utf8ToWide(outFile).c_str()) != INVALID_FILE_ATTRIBUTES) {
        return url;
    }

    double pageW = entry->pageBounds[pageIndex].width;
    double pageH = entry->pageBounds[pageIndex].height;

    int dpi = 150;
    if (pixelWidth > 0 && pageW > 0) {
        dpi = static_cast<int>((pixelWidth / pageW) * 72.0);
    }
    if (dpi < 72) dpi = 72;
    if (dpi > 300) dpi = 300;

    std::string args = "draw -o \"" + outFile + "\" -r " + std::to_string(dpi)
                     + " -F png \"" + entry->filePath + "\" "
                     + std::to_string(pageIndex + 1);

    std::string output;
    RunMutool(args, output);

    // Verify file was created
    if (GetFileAttributesW(Utf8ToWide(outFile).c_str()) == INVALID_FILE_ATTRIBUTES) {
        return "";
    }

    // Track for cleanup
    entry->tempFiles.push_back(outFile);

    return url;
}

// ── Get File URL for native PDF viewer ─────────────────────────────────

std::string PdfService::GetFileUrl(const std::string& filePath)
{
    wchar_t exePathBuf[MAX_PATH];
    GetModuleFileNameW(nullptr, exePathBuf, MAX_PATH);
    auto booksDir = (std::filesystem::path(exePathBuf).parent_path() / "renderer" / "_pb_books").wstring();
    std::filesystem::create_directories(booksDir);

    auto srcPath = Utf8ToWide(filePath);
    auto fileName = std::filesystem::path(srcPath).filename().wstring();

    // Use fixed name based on file path hash to avoid collisions
    std::hash<std::string> hasher;
    size_t hash = hasher(filePath);
    std::wstring linkName = L"book_" + std::to_wstring(hash) + L"_" + fileName;
    auto linkPath = booksDir + L"\\" + linkName;

    // Copy file if doesn't exist
    if (GetFileAttributesW(linkPath.c_str()) == INVALID_FILE_ATTRIBUTES) {
        // Clean up old links with same hash but different filename
        std::wstring oldPrefix = L"book_" + std::to_wstring(hash) + L"_";
        for (auto& entry : std::filesystem::directory_iterator(booksDir)) {
            auto name = entry.path().filename().wstring();
            if (name.find(oldPrefix) == 0 && name != linkName) {
                DeleteFileW(entry.path().c_str());
            }
        }
        CopyFileW(srcPath.c_str(), linkPath.c_str(), FALSE);
    }

    return "http://particlebook.app/_pb_books/" + WideToUtf8(linkName.c_str());
}

// ── Extract Text ──────────────────────────────────────────────────────

std::string PdfService::ExtractText(int id)
{
    DocEntry* entry = nullptr;
    for (auto& e : m_docs) {
        if (e.id == id) { entry = &e; break; }
    }
    if (!entry) return "";

    std::string output;
    // Use draw -F text which outputs plain text with form-feed page breaks
    std::string args = "draw -F text -o - \"" + entry->filePath + "\"";
    if (!RunMutool(args, output, 60000)) return "";

    // Split by form feed to get text per page
    json result;
    result["pages"] = json::array();

    size_t start = 0;
    int pageNum = 0;
    while (start < output.size()) {
        size_t end = output.find('\f', start);
        std::string pageText;
        if (end == std::string::npos) {
            pageText = output.substr(start);
        } else {
            pageText = output.substr(start, end - start);
        }

        // Clean up text: remove excessive whitespace but preserve structure
        if (!pageText.empty()) {
            json page;
            page["pageNum"] = pageNum;
            page["text"] = pageText;
            result["pages"].push_back(page);
        }
        pageNum++;

        if (end == std::string::npos) break;
        start = end + 1;
    }

    return result.dump();
}

// ── Close PDF ────────────────────────────────────────────────────────

void PdfService::Close(int id)
{
    for (auto it = m_docs.begin(); it != m_docs.end(); ++it) {
        if (it->id == id) {
            // Clean up temp PNG files
            for (auto& f : it->tempFiles) {
                DeleteFileW(Utf8ToWide(f).c_str());
            }
            // Clean up the MOBI patch temp file if this doc was patched
            // (PatchMobiEncoding wrote pb_mobi_<pid>_<seq><ext>).
            std::wstring fname = std::filesystem::path(Utf8ToWide(it->filePath)).filename().wstring();
            if (fname.rfind(L"pb_mobi_", 0) == 0) {
                DeleteFileW(Utf8ToWide(it->filePath).c_str());
                m_tempFiles.erase(std::remove(m_tempFiles.begin(), m_tempFiles.end(), it->filePath),
                                  m_tempFiles.end());
            }
            // Clean up legacy mutool_tmp dir
            std::error_code ec;
            std::string tmpDir = it->filePath + ".mutool_tmp";
            std::filesystem::remove_all(Utf8ToWide(tmpDir), ec);
            m_docs.erase(it);
            return;
        }
    }
}

// ── Handlers ─────────────────────────────────────────────────────────

void RegisterPdfHandlers(BridgeServer* bridge, PdfService* pdf)
{
    bridge->RegisterMethod("pdf:open", [pdf](const json& p) -> json {
        auto result = pdf->Open(p["filePath"].get<std::string>());
        if (result.pageCount == 0) return json(nullptr);

        json j;
        j["id"] = result.id;
        j["pageCount"] = result.pageCount;
        j["pageBounds"] = json::array();
        for (auto& b : result.pageBounds) {
            json jb;
            jb["width"] = b.width;
            jb["height"] = b.height;
            j["pageBounds"].push_back(jb);
        }
        return j;
    });

    bridge->RegisterMethod("pdf:getFileUrl", [pdf](const json& p) -> json {
        std::string filePath = p["filePath"].get<std::string>();
        std::string url = pdf->GetFileUrl(filePath);
        if (url.empty()) return json(nullptr);
        return json(url);
    });

    bridge->RegisterMethod("pdf:renderPage", [pdf](const json& p) -> json {
        int id = p["id"].get<int>();
        uint32_t pageNum = p["pageNum"].get<uint32_t>();
        int w = p.value("width", 0);
        int h = p.value("height", 0);
        std::string dataUrl = pdf->RenderPage(id, pageNum, w, h);
        if (dataUrl.empty()) return json(nullptr);
        return json(dataUrl);
    });

    bridge->RegisterMethod("pdf:extractText", [pdf](const json& p) -> json {
        std::string textJson = pdf->ExtractText(p["id"].get<int>());
        if (textJson.empty()) return json(nullptr);
        return json::parse(textJson);
    });

    bridge->RegisterMethod("pdf:close", [pdf](const json& p) -> json {
        pdf->Close(p["id"].get<int>());
        return json(nullptr);
    });
}
