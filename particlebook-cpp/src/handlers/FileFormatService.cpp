// PB-024-B：从 FileHandlers.cpp 纯搬移的「格式 / 小工具」实现（行为不变）。
#include "FileFormatService.h"

#include "utils/encoding.h"
#include "utils/win_cmd.h"

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdio>
#include <ctime>
#include <filesystem>
#include <mutex>
#include <string>

namespace filefmt {

// ── MOBI → text via mutool convert ────────────────────────────────────

std::string ConvertMobiToText(const std::string& filePath)
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

    std::wstring cmdLine = pb::JoinCmdLine({ mutoolPath, L"convert", L"-F", L"text", L"-o", tmpFileExt, pb::Utf8ToWide(filePath) });

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

std::string DetectFormat(const std::string& path)
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
    // PB-008-A：Keep in sync with LibraryService::DetectFormat (this local copy
    // shadows the global one for book:import).
    if (ext == ".azw3" || ext == ".azw") return "azw3";
    if (ext == ".docx")   return "docx";
    if (ext == ".txt")    return "txt";
    if (ext == ".fb2")    return "fb2";
    if (ext == ".cbz")    return "cbz";
    if (ext == ".cbr")    return "cbr";
    if (ext == ".html" || ext == ".htm") return "html";
    if (ext == ".md")     return "markdown";
    return "";
}

std::string GetFileName(const std::string& path)
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

int64_t GetFileSizeSafe(const std::string& path)
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

void DebugLog(const char* msg)
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


} // namespace filefmt
