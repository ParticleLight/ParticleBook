#include "FileHandlers.h"
#include "BridgeServer.h"
#include "WebViewHost.h"
#include "services/DatabaseService.h"
#include "services/LibraryService.h"
#include "services/ContentCache.h"
#include <fstream>
#include <vector>
#include <filesystem>
#include <shobjidl.h>
#include <commdlg.h>
#include <urlmon.h>
#include <winhttp.h>
#include <shellapi.h>
#include <bcrypt.h>
#include <cstring>
#include <cstdio>
#include <thread>
#include <ctime>
#include <mutex>

#pragma comment(lib, "urlmon.lib")
#pragma comment(lib, "winhttp.lib")
#pragma comment(lib, "bcrypt.lib")

static std::wstring Utf8ToWide(const std::string& s)
{
    if (s.empty()) return L"";
    int len = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), nullptr, 0);
    if (len <= 0) return L"";
    std::wstring w(len, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), &w[0], len);
    return w;
}

static std::string WideToUtf8(LPCWSTR w)
{
    int len = WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0, nullptr, nullptr);
    std::string s(len, '\0');
    WideCharToMultiByte(CP_UTF8, 0, w, -1, &s[0], len, nullptr, nullptr);
    while (!s.empty() && s.back() == '\0') s.pop_back();
    return s;
}

// ── MOBI → text via mutool convert ────────────────────────────────────

static std::string ConvertMobiToText(const std::string& filePath)
{
    std::string ext;
    size_t dot = filePath.rfind('.');
    if (dot != std::string::npos) {
        ext = filePath.substr(dot);
        for (auto& c : ext) c = (char)tolower((unsigned char)c);
    }
    if (ext != ".mobi" && ext != ".azw" && ext != ".azw3") return "";

    // Find mutool.exe next to our exe
    wchar_t exePathBuf[MAX_PATH];
    GetModuleFileNameW(nullptr, exePathBuf, MAX_PATH);
    auto mutoolPath = (std::filesystem::path(exePathBuf).parent_path() / "mutool.exe").wstring();

    // Create temp file for text output
    wchar_t tmpPath[MAX_PATH], tmpFile[MAX_PATH];
    GetTempPathW(MAX_PATH, tmpPath);
    GetTempFileNameW(tmpPath, L"mbt", 0, tmpFile);
    DeleteFileW(tmpFile);
    std::wstring tmpFileExt = std::wstring(tmpFile) + L".txt";

    std::wstring cmdLine = L"\"" + mutoolPath + L"\" convert -F text -o \"" + tmpFileExt + L"\" \"" + Utf8ToWide(filePath) + L"\"";

    PROCESS_INFORMATION pi = {};
    STARTUPINFOW si = { sizeof(STARTUPINFOW) };
    si.dwFlags = STARTF_USESHOWWINDOW;
    si.wShowWindow = SW_HIDE;

    if (!CreateProcessW(nullptr, cmdLine.data(), nullptr, nullptr, FALSE,
                         CREATE_NO_WINDOW | NORMAL_PRIORITY_CLASS,
                         nullptr, nullptr, &si, &pi))
        return "";

    if (WaitForSingleObject(pi.hProcess, 30000) == WAIT_TIMEOUT) {
        TerminateProcess(pi.hProcess, 1);
        WaitForSingleObject(pi.hProcess, INFINITE);
    }
    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);

    // Read text output
    HANDLE hFile = CreateFileW(tmpFileExt.c_str(), GENERIC_READ, FILE_SHARE_READ,
                                nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (hFile == INVALID_HANDLE_VALUE) return "";

    LARGE_INTEGER liSize;
    GetFileSizeEx(hFile, &liSize);
    size_t size = (size_t)liSize.QuadPart;
    if (size == 0 || size > 100 * 1024 * 1024) { CloseHandle(hFile); DeleteFileW(tmpFileExt.c_str()); return ""; }

    std::string text(size, '\0');
    DWORD bytesRead = 0;
    ReadFile(hFile, &text[0], (DWORD)size, &bytesRead, nullptr);
    CloseHandle(hFile);
    DeleteFileW(tmpFileExt.c_str());

    if (bytesRead == 0) return "";
    while (!text.empty() && text.back() == '\0') text.pop_back();

    // Normalize line breaks: merge single newlines within paragraphs into spaces,
    // keep double newlines as paragraph separators. This allows text to reflow.
    // First, normalize \r\n → \n
    std::string normalized;
    normalized.reserve(text.size());
    for (size_t i = 0; i < text.size(); i++) {
        if (text[i] == '\r' && i + 1 < text.size() && text[i + 1] == '\n') {
            normalized += '\n'; i++; // skip \n
        } else if (text[i] == '\r') {
            normalized += '\n';
        } else {
            normalized += text[i];
        }
    }
    // Merge single \n to space, keep \n\n as paragraph break
    std::string result;
    result.reserve(normalized.size());
    for (size_t i = 0; i < normalized.size(); i++) {
        if (normalized[i] == '\n') {
            if (i + 1 < normalized.size() && normalized[i + 1] == '\n') {
                result += "\n\n"; i++; // paragraph break
            } else {
                result += ' '; // single newline → space
            }
        } else {
            result += normalized[i];
        }
    }
    return result;
}

static std::string DetectFormat(const std::string& path)
{
    // Use string ops to avoid std::filesystem::path issues with CJK/parens in path
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

static std::string GetFileName(const std::string& path)
{
    size_t bs = path.rfind('\\');
    size_t fs = path.rfind('/');
    size_t slash = bs;
    if (fs != std::string::npos && (bs == std::string::npos || fs > bs)) slash = fs;
    std::string name = (slash != std::string::npos) ? path.substr(slash + 1) : path;
    // Strip extension
    size_t dot = name.rfind('.');
    if (dot != std::string::npos) name = name.substr(0, dot);
    return name;
}

static int64_t GetFileSizeSafe(const std::string& path)
{
    // Use Win32 API directly to avoid codepage issues with std::filesystem
    int len = MultiByteToWideChar(CP_UTF8, 0, path.c_str(), (int)path.size(), nullptr, 0);
    if (len <= 0) return 0;
    std::wstring wpath(len, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, path.c_str(), (int)path.size(), &wpath[0], len);

    WIN32_FILE_ATTRIBUTE_DATA attrs;
    if (!GetFileAttributesExW(wpath.c_str(), GetFileExInfoStandard, &attrs)) return 0;

    LARGE_INTEGER size;
    size.HighPart = attrs.nFileSizeHigh;
    size.LowPart = attrs.nFileSizeLow;
    return static_cast<int64_t>(size.QuadPart);
}

static json ExtractBasicMetadata(const std::string& path, const std::string&)
{
    json meta;
    std::string filename = GetFileName(path);
    size_t dot = filename.rfind('.');
    meta["title"] = (dot != std::string::npos) ? filename.substr(0, dot) : filename;
    return meta;
}

static void DebugLog(const char* msg)
{
    OutputDebugStringA(msg);
    // Also write to a log file in the exe directory
    static std::mutex logMutex;
    std::lock_guard<std::mutex> lock(logMutex);
    wchar_t exePath[MAX_PATH];
    GetModuleFileNameW(nullptr, exePath, MAX_PATH);
    auto logPath = std::filesystem::path(exePath).parent_path() / "debug.log";
    FILE* f = _wfopen(logPath.c_str(), L"a");
    if (f) {
        time_t now = time(nullptr);
        char timeBuf[32];
        tm localTm; localtime_s(&localTm, &now);
        strftime(timeBuf, sizeof(timeBuf), "%H:%M:%S", &localTm);
        fprintf(f, "[%s] %s\n", timeBuf, msg);
        fclose(f);
    }
}

// ── SHA-512 of a file (BCrypt) — for update package integrity check ──────
static bool ComputeFileSha512(const std::wstring& path, std::string& outHex)
{
    HANDLE hFile = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ,
                               nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (hFile == INVALID_HANDLE_VALUE) return false;

    BCRYPT_ALG_HANDLE hAlg = nullptr;
    BCRYPT_HASH_HANDLE hHash = nullptr;
    bool ok = false;
    do {
        if (!BCRYPT_SUCCESS(BCryptOpenAlgorithmProvider(&hAlg, BCRYPT_SHA512_ALGORITHM, nullptr, 0))) break;
        if (!BCRYPT_SUCCESS(BCryptCreateHash(hAlg, &hHash, nullptr, 0, nullptr, 0, 0))) break;
        char buf[65536];
        DWORD br = 0;
        bool readOk = true;
        while (ReadFile(hFile, buf, sizeof(buf), &br, nullptr) && br > 0) {
            if (!BCRYPT_SUCCESS(BCryptHashData(hHash, (PUCHAR)buf, br, 0))) { readOk = false; break; }
        }
        if (!readOk) break;
        UCHAR hash[64];
        if (!BCRYPT_SUCCESS(BCryptFinishHash(hHash, hash, sizeof(hash), 0))) break;
        static const char* hexc = "0123456789abcdef";
        outHex.clear();
        outHex.reserve(128);
        for (int i = 0; i < 64; i++) {
            outHex += hexc[hash[i] >> 4];
            outHex += hexc[hash[i] & 0xF];
        }
        ok = true;
    } while (false);

    if (hHash) BCryptDestroyHash(hHash);
    if (hAlg) BCryptCloseAlgorithmProvider(hAlg, 0);
    CloseHandle(hFile);
    return ok;
}


void RegisterFileHandlers(BridgeServer* bridge, DatabaseService* db, ContentCache* cache)
{
    // ── dialog:openFile ───────────────────────────────────────
    bridge->RegisterMethod("dialog:openFile", [](const json&) -> json {
        wchar_t fileBuf[MAX_PATH * 10] = {};  // Large buffer for safety

        OPENFILENAMEW ofn = {};
        ofn.lStructSize = sizeof(ofn);
        ofn.lpstrFilter = L"E-Books\0*.epub;*.pdf;*.mobi;*.txt;*.fb2;*.cbz;*.cbr;*.html;*.md\0All Files\0*.*\0";
        ofn.lpstrFile = fileBuf;
        ofn.nMaxFile = sizeof(fileBuf) / sizeof(wchar_t);
        ofn.Flags = OFN_FILEMUSTEXIST | OFN_PATHMUSTEXIST | OFN_EXPLORER;
        ofn.lpstrTitle = L"Select E-Book File";

        if (!GetOpenFileNameW(&ofn)) return json(nullptr);

        return json(WideToUtf8(fileBuf));
    });

    // ── dialog:openDirectory ──────────────────────────────────
    bridge->RegisterMethod("dialog:openDirectory", [](const json&) -> json {
        IFileOpenDialog* pDlg = nullptr;
        if (FAILED(CoCreateInstance(CLSID_FileOpenDialog, nullptr, CLSCTX_ALL,
                                    IID_PPV_ARGS(&pDlg)))) {
            return json(nullptr);
        }
        DWORD flags;
        pDlg->GetOptions(&flags);
        pDlg->SetOptions(flags | FOS_PICKFOLDERS | FOS_FORCEFILESYSTEM);

        if (FAILED(pDlg->Show(nullptr))) { pDlg->Release(); return json(nullptr); }

        IShellItem* pItem = nullptr;
        if (FAILED(pDlg->GetResult(&pItem))) { pDlg->Release(); return json(nullptr); }
        pDlg->Release();

        std::string result;
        LPWSTR pszPath = nullptr;
        if (SUCCEEDED(pItem->GetDisplayName(SIGDN_FILESYSPATH, &pszPath))) {
            result = WideToUtf8(pszPath);
            CoTaskMemFree(pszPath);
        }
        pItem->Release();
        return result.empty() ? json(nullptr) : json(result);
    });

    // ── file:read ─ Return file content as byte array (cached) ───
    bridge->RegisterMethod("file:read", [cache](const json& p) -> json {
        std::string path = p["path"].get<std::string>();

        // Check cache first
        if (cache) {
            auto* cached = cache->Get(path);
            if (cached) return json(*cached);
        }

        // For large / comic files, serve via virtual host URL instead of JSON byte array
        std::string format = DetectFormat(path);
        bool isLarge = false;
        {
            int wl = MultiByteToWideChar(CP_UTF8, 0, path.c_str(), (int)path.size(), nullptr, 0);
            if (wl > 0) {
                std::wstring wp(wl, L'\0');
                MultiByteToWideChar(CP_UTF8, 0, path.c_str(), (int)path.size(), &wp[0], wl);
                WIN32_FILE_ATTRIBUTE_DATA attrs;
                if (GetFileAttributesExW(wp.c_str(), GetFileExInfoStandard, &attrs)) {
                    ULONGLONG sz = ((ULONGLONG)attrs.nFileSizeHigh << 32) | attrs.nFileSizeLow;
                    if (sz > 5 * 1024 * 1024) isLarge = true;
                }
            }
        }
        if (isLarge || format == "cbz" || format == "cbr") {
            // Copy to renderer temp dir
            wchar_t exePath[MAX_PATH];
            GetModuleFileNameW(nullptr, exePath, MAX_PATH);
            auto rendererDir = (std::filesystem::path(exePath).parent_path() / "renderer" / "_pb_files").wstring();
            CreateDirectoryW(rendererDir.c_str(), nullptr);

            std::string fn;
            size_t bs = path.rfind('\\');
            size_t fs = path.rfind('/');
            size_t slash = (fs != std::string::npos && (bs == std::string::npos || fs > bs)) ? fs : bs;
            fn = (slash != std::string::npos) ? path.substr(slash + 1) : "file";

            std::wstring dest = rendererDir + L"\\" + Utf8ToWide(fn);
            if (!CopyFileW(Utf8ToWide(path).c_str(), dest.c_str(), FALSE)) {
                // Fall through to normal read
            } else {
                std::string url = "http://particlebook.app/_pb_files/" + fn;
                json r;
                r["_pb_url"] = url;
                return r;
            }
        }

        // For MOBI files, extract text via mutool (cached)
        std::string mobiText = ConvertMobiToText(path);
        if (!mobiText.empty()) {
            int64_t ft = 0;
            int wl = MultiByteToWideChar(CP_UTF8, 0, path.c_str(), (int)path.size(), nullptr, 0);
            if (wl > 0) {
                std::wstring wp(wl, L'\0');
                MultiByteToWideChar(CP_UTF8, 0, path.c_str(), (int)path.size(), &wp[0], wl);
                WIN32_FILE_ATTRIBUTE_DATA attrs;
                if (GetFileAttributesExW(wp.c_str(), GetFileExInfoStandard, &attrs)) {
                    ft = (static_cast<int64_t>(attrs.ftLastWriteTime.dwHighDateTime) << 32)
                       | attrs.ftLastWriteTime.dwLowDateTime;
                }
            }
            std::vector<uint8_t> data(mobiText.begin(), mobiText.end());
            if (cache) cache->Put(path, data, ft);
            return json(data);
        }

        // Convert UTF-8 path to wide for Windows API
        int wlen = MultiByteToWideChar(CP_UTF8, 0, path.c_str(), (int)path.size(), nullptr, 0);
        if (wlen <= 0) return json(nullptr);
        std::wstring wpath(wlen, L'\0');
        MultiByteToWideChar(CP_UTF8, 0, path.c_str(), (int)path.size(), &wpath[0], wlen);

        HANDLE hFile = CreateFileW(wpath.c_str(), GENERIC_READ, FILE_SHARE_READ,
                                   nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (hFile == INVALID_HANDLE_VALUE) return json(nullptr);

        FILETIME ftWrite;
        GetFileTime(hFile, nullptr, nullptr, &ftWrite);
        int64_t fileTime = (static_cast<int64_t>(ftWrite.dwHighDateTime) << 32)
                         | ftWrite.dwLowDateTime;

        LARGE_INTEGER liSize;
        GetFileSizeEx(hFile, &liSize);
        size_t size = static_cast<size_t>(liSize.QuadPart);

        std::vector<uint8_t> data(size);
        DWORD bytesRead = 0;
        ReadFile(hFile, data.data(), static_cast<DWORD>(size), &bytesRead, nullptr);
        CloseHandle(hFile);

        data.resize(bytesRead);
        if (cache) cache->Put(path, data, fileTime);
        return json(data);
    });

    // ── book:import ────────────────────────────────────────────
    bridge->RegisterMethod("book:import", [db, bridge](const json& p) -> json {
        json result = json::array();
        auto paths = p["paths"];

        char debugBuf[1024];
        snprintf(debugBuf, sizeof(debugBuf),
            "book:import called, paths is_array=%d, size=%zu",
            paths.is_array() ? 1 : 0, paths.is_array() ? paths.size() : 0);
        DebugLog(debugBuf);

        if (!paths.is_array()) return result;

        for (const auto& pathJson : paths) {
            try {
                std::string path = pathJson.get<std::string>();

                snprintf(debugBuf, sizeof(debugBuf), "book:import processing: %s", path.c_str());
                DebugLog(debugBuf);

                // Check for existing
                json existing = db->GetBookByPath(path);
                if (!existing.is_null()) {
                    DebugLog("book:import: already exists");
                    result.push_back(existing);
                    continue;
                }

                std::string format = DetectFormat(path);
                snprintf(debugBuf, sizeof(debugBuf), "book:import: format=%s", format.c_str());
                DebugLog(debugBuf);
                if (format.empty()) { DebugLog("book:import: unknown format, skip"); continue; }

                int64_t fileSize = GetFileSizeSafe(path);
                snprintf(debugBuf, sizeof(debugBuf), "book:import: fileSize=%lld", (long long)fileSize);
                DebugLog(debugBuf);
                if (fileSize <= 0) { DebugLog("book:import: file_size failed"); continue; }

                // Extract rich metadata (may crash on corrupt files)
                DebugLog("book:import: extracting metadata");
                LibraryService lib;
                ExtractedMetadata em;
                if (format == "epub") {
                    DebugLog("book:import: calling ExtractEpubMetadata");
                    lib.ExtractEpubMetadata(path, em);
                    snprintf(debugBuf, sizeof(debugBuf),
                        "book:import: epub metadata done, title=%s coverFile=%s",
                        em.title.c_str(), em.coverFile.c_str());
                    DebugLog(debugBuf);
                    if (!em.coverFile.empty()) {
                        DebugLog("book:import: extracting cover");
                        auto coverPath = lib.ExtractEpubCover(path, em.coverFile);
                        if (!coverPath.empty()) em.coverPath = coverPath;
                        DebugLog("book:import: cover done");
                    } else {
                        DebugLog("book:import: NO COVER FOUND in OPF");
                    }
                } else if (format == "pdf") {
                    lib.ExtractPdfMetadata(path, em);
                    DebugLog("book:import: rendering PDF cover");
                    em.coverPath = lib.ExtractPdfCover(path);
                    DebugLog("book:import: PDF cover done");
                } else if (format == "cbz" || format == "cbr") {
                    DebugLog("book:import: rendering CBZ/CBR cover");
                    em.coverPath = lib.ExtractPdfCover(path);
                    DebugLog("book:import: CBZ/CBR cover done");
                } else if (format == "fb2") {
                    lib.ExtractFb2Metadata(path, em);
                }
                DebugLog("book:import: metadata extraction complete");

                std::string title = em.title.empty() ? GetFileName(path) : em.title;
                json book;
                book["title"] = title;
                book["author"] = em.author.empty() ? nullptr : json(em.author);
                book["format"] = format;
                book["file_path"] = path;
                book["file_size"] = fileSize;
                book["description"] = em.description.empty() ? nullptr : json(em.description);
                book["publisher"] = em.publisher.empty() ? nullptr : json(em.publisher);
                book["cover_path"] = em.coverPath.empty() ? nullptr : json(em.coverPath);
                book["language"] = em.language.empty() ? nullptr : json(em.language);
                book["isbn"] = em.isbn.empty() ? nullptr : json(em.isbn);

                auto inserted = db->InsertBook(book);
                snprintf(debugBuf, sizeof(debugBuf), "book:import: inserted id=%d", inserted.value("id", -1));
                DebugLog(debugBuf);
                result.push_back(inserted);
            } catch (const std::exception& e) {
                snprintf(debugBuf, sizeof(debugBuf), "book:import: exception: %s", e.what());
                DebugLog(debugBuf);
            } catch (...) {
                DebugLog("book:import: unknown crash, skipping file");
            }
        }
        snprintf(debugBuf, sizeof(debugBuf), "book:import: returning %zu books", result.size());
        DebugLog(debugBuf);
        return result;
    });

    // ── book:metadata ──────────────────────────────────────────
    bridge->RegisterMethod("book:metadata", [](const json& p) -> json {
        std::string path = p["path"].get<std::string>();
        std::string format = DetectFormat(path);
        if (format.empty()) return json::object();

        LibraryService lib;
        ExtractedMetadata em;
        if (format == "epub")   lib.ExtractEpubMetadata(path, em);
        else if (format == "pdf")   lib.ExtractPdfMetadata(path, em);
        else if (format == "fb2")   lib.ExtractFb2Metadata(path, em);

        json result;
        result["title"] = em.title.empty() ? GetFileName(path) : em.title;
        result["author"] = em.author;
        result["language"] = em.language;
        result["format"] = format;
        return result;
    });

    // ── book:cover ─────────────────────────────────────────────
    bridge->RegisterMethod("book:cover", [db](const json& p) -> json {
        int id = p["id"].get<int>();
        auto book = db->GetBook(id);
        if (book.is_null()) return json(nullptr);

        std::string coverPath = book.value("cover_path", "");

        // Try cached cover first
        if (!coverPath.empty()) {
            int wlen = MultiByteToWideChar(CP_UTF8, 0, coverPath.c_str(), (int)coverPath.size(), nullptr, 0);
            if (wlen > 0) {
                std::wstring wpath(wlen, L'\0');
                MultiByteToWideChar(CP_UTF8, 0, coverPath.c_str(), (int)coverPath.size(), &wpath[0], wlen);
                HANDLE hFile = CreateFileW(wpath.c_str(), GENERIC_READ, FILE_SHARE_READ,
                                           nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
                if (hFile != INVALID_HANDLE_VALUE) {
                    LARGE_INTEGER liSize;
                    GetFileSizeEx(hFile, &liSize);
                    size_t size = static_cast<size_t>(liSize.QuadPart);
                    std::vector<unsigned char> data(size);
                    DWORD bytesRead = 0;
                    ReadFile(hFile, data.data(), static_cast<DWORD>(size), &bytesRead, nullptr);
                    CloseHandle(hFile);
                    data.resize(bytesRead);
                    static const char* b64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
                    std::string ext;
                    size_t dot = coverPath.rfind('.');
                    if (dot != std::string::npos) { ext = coverPath.substr(dot); for (auto& c : ext) c = (char)tolower((unsigned char)c); }
                    std::string prefix = "data:image/" + std::string(ext == ".png" ? "png" : "jpeg") + ";base64,";
                    std::string b64data;
                    int val = 0, valb = -6;
                    for (unsigned char c : data) { val = (val << 8) + c; valb += 8; while (valb >= 0) { b64data.push_back(b64[(val >> valb) & 0x3F]); valb -= 6; } }
                    if (valb > -6) b64data.push_back(b64[((val << 8) >> (valb + 8)) & 0x3F]);
                    while (b64data.size() % 4) b64data.push_back('=');
                    return json(prefix + b64data);
                }
            }
        }

        // On-demand cover generation
        std::string filePath = book.value("file_path", "");
        std::string format = book.value("format", "");
        if (!filePath.empty()) {
            LibraryService lib;
            ExtractedMetadata em;
            std::string newCover;
            if (format == "epub") {
                lib.ExtractEpubMetadata(filePath, em);
                if (!em.coverFile.empty()) newCover = lib.ExtractEpubCover(filePath, em.coverFile);
            } else if (format == "pdf") {
                newCover = lib.ExtractPdfCover(filePath);
            }
            if (!newCover.empty()) {
                int wl = MultiByteToWideChar(CP_UTF8, 0, newCover.c_str(), (int)newCover.size(), nullptr, 0);
                if (wl > 0) {
                    std::wstring wp(wl, L'\0');
                    MultiByteToWideChar(CP_UTF8, 0, newCover.c_str(), (int)newCover.size(), &wp[0], wl);
                    HANDLE hf = CreateFileW(wp.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
                    if (hf != INVALID_HANDLE_VALUE) {
                        LARGE_INTEGER sz; GetFileSizeEx(hf, &sz);
                        std::vector<unsigned char> d((size_t)sz.QuadPart);
                        DWORD br; ReadFile(hf, d.data(), (DWORD)sz.QuadPart, &br, nullptr); CloseHandle(hf);
                        d.resize(br);
                        static const char* b64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
                        std::string pfx = "data:image/jpeg;base64,", bd;
                        int v = 0, vb = -6;
                        for (unsigned char c : d) { v = (v << 8) + c; vb += 8; while (vb >= 0) { bd.push_back(b64[(v >> vb) & 0x3F]); vb -= 6; } }
                        if (vb > -6) bd.push_back(b64[((v << 8) >> (vb + 8)) & 0x3F]);
                        while (bd.size() % 4) bd.push_back('=');
                        return json(pfx + bd);
                    }
                }
            }
        }
        return json(nullptr);
    });

    // ── Update checker ─────────────────────────────────────────
    bridge->RegisterMethod("app:getVersion", [](const json&) -> json { return json("2.0.3"); });

    bridge->RegisterMethod("app:checkUpdate", [](const json&) -> json {
        // Fetch latest.yml from GitHub Releases (no API rate limit)
        HINTERNET hS = WinHttpOpen(L"PB/2.0", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY, nullptr, nullptr, 0);
        if (!hS) return json(nullptr);

        HINTERNET hC = WinHttpConnect(hS, L"github.com", 443, 0);
        if (!hC) { WinHttpCloseHandle(hS); return json(nullptr); }

        // Try latest.yml from releases/latest/download/ (redirects handled by WinHTTP)
        HINTERNET hR = WinHttpOpenRequest(hC, L"GET",
            L"/ParticleLight/ParticleBook/releases/latest/download/latest.yml",
            nullptr, nullptr, nullptr, WINHTTP_FLAG_SECURE);
        if (!hR) { WinHttpCloseHandle(hC); WinHttpCloseHandle(hS); return json(nullptr); }

        WinHttpAddRequestHeaders(hR, L"User-Agent: ParticleBook/2.0\r\n", (DWORD)-1, WINHTTP_ADDREQ_FLAG_ADD);

        if (!WinHttpSendRequest(hR, nullptr, 0, nullptr, 0, 0, 0) || !WinHttpReceiveResponse(hR, nullptr)) {
            // Fallback to API
            WinHttpCloseHandle(hR); WinHttpCloseHandle(hC); WinHttpCloseHandle(hS);

            hS = WinHttpOpen(L"PB/2.0", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY, nullptr, nullptr, 0);
            if (!hS) return json(nullptr);
            hC = WinHttpConnect(hS, L"api.github.com", 443, 0);
            if (!hC) { WinHttpCloseHandle(hS); return json(nullptr); }
            hR = WinHttpOpenRequest(hC, L"GET", L"/repos/ParticleLight/ParticleBook/releases/latest",
                nullptr, nullptr, nullptr, WINHTTP_FLAG_SECURE);
            if (!hR) { WinHttpCloseHandle(hC); WinHttpCloseHandle(hS); return json(nullptr); }
            WinHttpAddRequestHeaders(hR, L"User-Agent: ParticleBook/2.0\r\nAccept: application/vnd.github+json\r\n", (DWORD)-1, WINHTTP_ADDREQ_FLAG_ADD);
            if (!WinHttpSendRequest(hR, nullptr, 0, nullptr, 0, 0, 0) || !WinHttpReceiveResponse(hR, nullptr)) {
                WinHttpCloseHandle(hR); WinHttpCloseHandle(hC); WinHttpCloseHandle(hS);
                return json(nullptr);
            }
        }

        std::string body;
        DWORD br; char buf[8192];
        while (WinHttpReadData(hR, buf, sizeof(buf), &br) && br > 0) body.append(buf, br);
        WinHttpCloseHandle(hR); WinHttpCloseHandle(hC); WinHttpCloseHandle(hS);

        // Try YAML format first (latest.yml)
        std::string latestVer;
        std::string dlUrl;
        std::string fileName;
        size_t size = 0;
        std::string sha512;

        // Parse latest.yml: "version: X.Y.Z\nfiles:\n  - url: ...\n    sha512: ...\n    size: N"
        size_t vp = body.find("version:");
        if (vp != std::string::npos) {
            vp += 8;
            while (vp < body.size() && body[vp] == ' ') vp++;
            size_t ve = body.find('\n', vp);
            if (ve != std::string::npos) latestVer = body.substr(vp, ve - vp);
            while (!latestVer.empty() && (latestVer.back() == '\r' || latestVer.back() == ' ')) latestVer.pop_back();

            // Find url
            size_t up = body.find("url:");
            if (up != std::string::npos) {
                up += 4;
                while (up < body.size() && body[up] == ' ') up++;
                size_t ue = body.find('\n', up);
                if (ue != std::string::npos) fileName = body.substr(up, ue - up);
                while (!fileName.empty() && (fileName.back() == '\r' || fileName.back() == ' ')) fileName.pop_back();
            }

            // Find size
            size_t sp = body.find("size:");
            if (sp != std::string::npos) {
                sp += 5;
                while (sp < body.size() && body[sp] == ' ') sp++;
                size_t se = body.find('\n', sp);
                if (se != std::string::npos) {
                    try { size = std::stoull(body.substr(sp, se - sp)); } catch (...) {}
                }
            }

            // Find sha512 (for update package integrity verification)
            size_t hp = body.find("sha512:");
            if (hp != std::string::npos) {
                hp += 7;
                while (hp < body.size() && body[hp] == ' ') hp++;
                size_t he = body.find('\n', hp);
                if (he != std::string::npos) sha512 = body.substr(hp, he - hp);
                while (!sha512.empty() &&
                       (sha512.back() == '\r' || sha512.back() == ' ' || sha512.back() == '"'))
                    sha512.pop_back();
                while (!sha512.empty() && sha512.front() == '"') sha512.erase(sha512.begin());
            }

            // Build download URL from file name
            if (!latestVer.empty() && !fileName.empty()) {
                dlUrl = "https://github.com/ParticleLight/ParticleBook/releases/download/v"
                      + latestVer + "/" + fileName;
            }
        } else {
            // Try JSON format (GitHub API response)
            try {
                auto release = json::parse(body);
                std::string tag = release.value("tag_name", "");
                if (!tag.empty()) {
                    latestVer = (tag[0] == 'v') ? tag.substr(1) : tag;
                    for (auto& asset : release["assets"]) {
                        std::string name = asset.value("name", "");
                        if (name.find("-Setup-") != std::string::npos && name.find(".exe") != std::string::npos) {
                            fileName = name;
                            dlUrl = asset.value("browser_download_url", "");
                            size = asset.value("size", 0);
                            break;
                        }
                    }
                }
            } catch (...) {}
        }

        if (latestVer.empty() || dlUrl.empty()) return json(nullptr);

        // Semantic version comparison
        auto versionGreater = [](const std::string& a, const std::string& b) -> bool {
            auto split = [](const std::string& v) -> std::tuple<int,int,int> {
                int major = 0, minor = 0, patch = 0;
                sscanf(v.c_str(), "%d.%d.%d", &major, &minor, &patch);
                return {major, minor, patch};
            };
            auto [a1,a2,a3] = split(a);
            auto [b1,b2,b3] = split(b);
            if (a1 != b1) return a1 > b1;
            if (a2 != b2) return a2 > b2;
            return a3 > b3;
        };

        if (versionGreater(latestVer, "2.0.3")) {
            json result;
            result["version"] = latestVer;
            result["fileName"] = fileName;
            result["downloadUrl"] = dlUrl;
            result["size"] = size;
            result["sha512"] = sha512;
            return result;
        }
        return json(nullptr);
    });

    bridge->RegisterMethod("app:downloadUpdate", [bridge](const json& p) -> json {
        std::string downloadUrl = p.value("url", "");
        std::string sha512 = p.value("sha512", "");
        if (downloadUrl.empty()) return json(nullptr);

        // Integrity + trust: only accept official GitHub release URLs. Combined
        // with the origin gate in HandleMessage, this prevents any page from
        // making us download an arbitrary binary.
        const char* kOfficialPrefix = "https://github.com/ParticleLight/ParticleBook/releases/download/";
        if (downloadUrl.rfind(kOfficialPrefix, 0) != 0) return json(nullptr);

        // Download in background to avoid blocking UI thread
        HWND hwnd = bridge->GetWebViewHost() ? bridge->GetWebViewHost()->GetHwnd() : nullptr;
        if (!hwnd) {
            // Fallback: open in browser
            int wlen = MultiByteToWideChar(CP_UTF8, 0, downloadUrl.c_str(), -1, nullptr, 0);
            std::wstring wUrl(wlen, L'\0');
            MultiByteToWideChar(CP_UTF8, 0, downloadUrl.c_str(), -1, &wUrl[0], wlen);
            while (!wUrl.empty() && wUrl.back() == L'\0') wUrl.pop_back();
            ShellExecuteW(nullptr, L"open", wUrl.c_str(), nullptr, nullptr, SW_SHOWNORMAL);
            return json(true);
        }

        std::thread([hwnd, downloadUrl, sha512]() {
            int wlen = MultiByteToWideChar(CP_UTF8, 0, downloadUrl.c_str(), -1, nullptr, 0);
            std::wstring wUrl(wlen, L'\0');
            MultiByteToWideChar(CP_UTF8, 0, downloadUrl.c_str(), -1, &wUrl[0], wlen);
            while (!wUrl.empty() && wUrl.back() == L'\0') wUrl.pop_back();

            wchar_t tmpPath[MAX_PATH];
            GetTempPathW(MAX_PATH, tmpPath);
            std::wstring exePath = std::wstring(tmpPath) + L"ParticleBook-Update.exe";
            DeleteFileW(exePath.c_str());

            // WinHTTP fetch with up to 8 redirects (GitHub release → objects.githubusercontent.com)
            auto fetchWithWinHttp = [](const std::wstring& url, const std::wstring& out) -> bool {
                std::wstring cur = url;
                for (int redir = 0; redir < 8; redir++) {
                    size_t se = cur.find(L"://"); if (se == std::wstring::npos) return false;
                    bool https = (cur.substr(0, se) == L"https");
                    size_t hs = se + 3;
                    size_t ps = cur.find(L'/', hs);
                    std::wstring host, path;
                    if (ps != std::wstring::npos) { host = cur.substr(hs, ps - hs); path = cur.substr(ps); }
                    else { host = cur.substr(hs); path = L"/"; }
                    HINTERNET hS = WinHttpOpen(L"ParticleBook/2.0 Updater", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY, nullptr, nullptr, 0);
                    if (!hS) return false;
                    HINTERNET hC = WinHttpConnect(hS, host.c_str(), https ? 443 : 80, 0);
                    if (!hC) { WinHttpCloseHandle(hS); return false; }
                    HINTERNET hR = WinHttpOpenRequest(hC, L"GET", path.c_str(), nullptr, nullptr, nullptr, https ? WINHTTP_FLAG_SECURE : 0);
                    if (!hR) { WinHttpCloseHandle(hC); WinHttpCloseHandle(hS); return false; }
                    BOOL sent = WinHttpSendRequest(hR, nullptr, 0, nullptr, 0, 0, 0) && WinHttpReceiveResponse(hR, nullptr);
                    if (!sent) { WinHttpCloseHandle(hR); WinHttpCloseHandle(hC); WinHttpCloseHandle(hS); return false; }
                    DWORD sc = 0, sz = sizeof(sc);
                    WinHttpQueryHeaders(hR, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER, nullptr, &sc, &sz, nullptr);
                    if (sc == 301 || sc == 302 || sc == 303 || sc == 307) {
                        WCHAR loc[2048] = {}; DWORD ls = sizeof(loc);
                        if (WinHttpQueryHeaders(hR, WINHTTP_QUERY_LOCATION, nullptr, loc, &ls, nullptr)) {
                            cur = loc;
                            WinHttpCloseHandle(hR); WinHttpCloseHandle(hC); WinHttpCloseHandle(hS);
                            continue;
                        }
                        WinHttpCloseHandle(hR); WinHttpCloseHandle(hC); WinHttpCloseHandle(hS);
                        return false;
                    }
                    if (sc >= 400) { WinHttpCloseHandle(hR); WinHttpCloseHandle(hC); WinHttpCloseHandle(hS); return false; }
                    HANDLE hFile = CreateFileW(out.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
                    if (hFile == INVALID_HANDLE_VALUE) { WinHttpCloseHandle(hR); WinHttpCloseHandle(hC); WinHttpCloseHandle(hS); return false; }
                    char buf[65536]; DWORD br;
                    while (WinHttpReadData(hR, buf, sizeof(buf), &br) && br > 0) { DWORD wr; WriteFile(hFile, buf, br, &wr, nullptr); }
                    CloseHandle(hFile);
                    WinHttpCloseHandle(hR); WinHttpCloseHandle(hC); WinHttpCloseHandle(hS);
                    return true;
                }
                return false;
            };

            bool ok = fetchWithWinHttp(wUrl, exePath);
            HRESULT hr = ok ? S_OK : E_FAIL;
            if (FAILED(hr)) {
                // Fallback: urlmon (some networks handle it better)
                hr = URLDownloadToFileW(nullptr, wUrl.c_str(), exePath.c_str(), 0, nullptr);
            }
            if (FAILED(hr)) {
                auto* errStr = new std::string("下载失败（请检查网络后重试）");
                PostMessage(hwnd, WM_UPDATE_DOWNLOAD_DONE, 0, (LPARAM)errStr);
                return;
            }

            // Integrity check: verify SHA-512 against the value published in
            // latest.yml. Skip when the published value is missing or still the
            // placeholder "dummy" (legacy releases); real values are added from
            // the next release onward.
            if (!sha512.empty() && sha512 != "dummy") {
                std::string actual;
                if (!ComputeFileSha512(exePath, actual) || _stricmp(actual.c_str(), sha512.c_str()) != 0) {
                    DeleteFileW(exePath.c_str());
                    auto* errStr = new std::string("更新包校验失败（哈希不匹配），已中止安装");
                    PostMessage(hwnd, WM_UPDATE_DOWNLOAD_DONE, 0, (LPARAM)errStr);
                    return;
                }
            }

            // Pass path back to main thread (will be stored for quitAndInstall)
            int pathLen = WideCharToMultiByte(CP_UTF8, 0, exePath.c_str(), -1, nullptr, 0, nullptr, nullptr);
            auto* pathStr = new std::string(pathLen, '\0');
            WideCharToMultiByte(CP_UTF8, 0, exePath.c_str(), -1, &(*pathStr)[0], pathLen, nullptr, nullptr);
            while (!pathStr->empty() && pathStr->back() == '\0') pathStr->pop_back();
            PostMessage(hwnd, WM_UPDATE_DOWNLOAD_DONE, 1, (LPARAM)pathStr);
        }).detach();

        return json("started");
    });

    bridge->RegisterMethod("app:quitAndInstall", [](const json&) -> json {
        wchar_t tmpPath[MAX_PATH];
        GetTempPathW(MAX_PATH, tmpPath);
        std::wstring batPath = std::wstring(tmpPath) + L"ParticleBook-Update.bat";
        std::wstring exePath = std::wstring(tmpPath) + L"ParticleBook-Update.exe";

        DWORD pid = GetCurrentProcessId();
        char pidStr[32];
        snprintf(pidStr, sizeof(pidStr), "%lu", pid);

        // Build batch content: wait for this PID to exit, then install silently & clean up
        std::string bat;
        bat += "@echo off\r\n";
        bat += ":loop\r\n";
        bat += "tasklist /fi \"PID eq " + std::string(pidStr) + "\" 2>nul | find \"" + std::string(pidStr) + "\" >nul\r\n";
        bat += "if not errorlevel 1 (\r\n";
        bat += "    ping -n 2 127.0.0.1 >nul\r\n";
        bat += "    goto loop\r\n";
        bat += ")\r\n";

        // Convert exePath to narrow string for batch
        int nlen = WideCharToMultiByte(CP_ACP, 0, exePath.c_str(), -1, nullptr, 0, nullptr, nullptr);
        std::string exePathNarrow(nlen, '\0');
        WideCharToMultiByte(CP_ACP, 0, exePath.c_str(), -1, &exePathNarrow[0], nlen, nullptr, nullptr);
        while (!exePathNarrow.empty() && exePathNarrow.back() == '\0') exePathNarrow.pop_back();

        bat += "start \"\" /wait \"" + exePathNarrow + "\" /S\r\n";
        bat += "del \"" + exePathNarrow + "\"\r\n";
        bat += "del \"%~f0\"\r\n";

        // Write batch file
        HANDLE hFile = CreateFileW(batPath.c_str(), GENERIC_WRITE, 0, nullptr,
            CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (hFile != INVALID_HANDLE_VALUE) {
            DWORD written;
            WriteFile(hFile, bat.c_str(), (DWORD)bat.size(), &written, nullptr);
            CloseHandle(hFile);

            // Run batch file detached, hidden
            STARTUPINFOW si = { sizeof(si) };
            si.dwFlags = STARTF_USESHOWWINDOW;
            si.wShowWindow = SW_HIDE;
            PROCESS_INFORMATION pi = {};
            std::wstring cmdLine = L"cmd.exe /c \"" + batPath + L"\"";
            CreateProcessW(nullptr, cmdLine.data(), nullptr, nullptr, FALSE,
                CREATE_NO_WINDOW, nullptr, nullptr, &si, &pi);
            if (pi.hProcess) CloseHandle(pi.hProcess);
            if (pi.hThread) CloseHandle(pi.hThread);
        }

        PostQuitMessage(0);
        return json(true);
    });

    bridge->RegisterMethod("bookSource:importFile", [db](const json&) -> json {
        // Open file dialog for JSON book source files
        IFileOpenDialog* pDialog = nullptr;
        HRESULT hr = CoCreateInstance(CLSID_FileOpenDialog, nullptr, CLSCTX_ALL,
                                      IID_PPV_ARGS(&pDialog));
        if (FAILED(hr) || !pDialog) return json(nullptr);

        COMDLG_FILTERSPEC filters[] = {
            { L"Legado 书源文件 (*.json)", L"*.json" },
            { L"所有文件 (*.*)", L"*.*" }
        };
        pDialog->SetFileTypes(2, filters);
        pDialog->SetTitle(L"导入 Legado 书源文件");

        hr = pDialog->Show(nullptr);
        if (FAILED(hr)) { pDialog->Release(); return json(nullptr); }

        IShellItem* pItem = nullptr;
        hr = pDialog->GetResult(&pItem);
        pDialog->Release();
        if (FAILED(hr) || !pItem) return json(nullptr);

        LPWSTR pwPath = nullptr;
        pItem->GetDisplayName(SIGDN_FILESYSPATH, &pwPath);
        pItem->Release();
        if (!pwPath) return json(nullptr);

        int len = WideCharToMultiByte(CP_UTF8, 0, pwPath, -1, nullptr, 0, nullptr, nullptr);
        std::string filePath(len, '\0');
        WideCharToMultiByte(CP_UTF8, 0, pwPath, -1, &filePath[0], len, nullptr, nullptr);
        while (!filePath.empty() && filePath.back() == '\0') filePath.pop_back();
        CoTaskMemFree(pwPath);

        // Read and parse JSON
        std::ifstream f(filePath, std::ios::binary);
        if (!f.is_open()) return json(nullptr);

        std::string content((std::istreambuf_iterator<char>(f)),
                            std::istreambuf_iterator<char>());
        f.close();

        try {
            auto j = json::parse(content);
            int imported = 0;

            // Legado format: array of source objects
            if (j.is_array()) {
                for (auto& src : j) {
                    json entry;
                    std::string name = src.value("bookSourceName", "");
                    std::string url = src.value("bookSourceUrl", "");
                    if (name.empty() && url.empty()) continue;

                    entry["bookSourceName"] = name;
                    entry["bookSourceUrl"] = url;
                    entry["enabled"] = true;

                    // Copy relevant fields
                    if (src.contains("bookSourceGroup")) entry["bookSourceGroup"] = src["bookSourceGroup"];
                    if (src.contains("bookSourceType")) entry["bookSourceType"] = src["bookSourceType"];
                    if (src.contains("searchUrl")) entry["searchUrl"] = src["searchUrl"];
                    if (src.contains("ruleSearch")) entry["ruleSearch"] = src["ruleSearch"];
                    if (src.contains("ruleBookInfo")) entry["ruleBookInfo"] = src["ruleBookInfo"];
                    if (src.contains("ruleToc")) entry["ruleToc"] = src["ruleToc"];
                    if (src.contains("ruleContent")) entry["ruleContent"] = src["ruleContent"];
                    if (src.contains("httpUserAgent")) entry["httpUserAgent"] = src["httpUserAgent"];

                    db->InsertBookSource(entry);
                    imported++;
                }
            }

            json result;
            result["imported"] = imported;
            return result;
        } catch (...) {
            return json(nullptr);
        }
    });
}
