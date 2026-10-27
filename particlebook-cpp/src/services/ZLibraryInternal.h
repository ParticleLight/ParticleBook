#pragma once
// ── PB-024-A：ZLibraryService 跨 TU 共用的常量 / 结构 / 小工具 ──────────
// 原 ZLibraryService.cpp 是 1500 行的 god file，按职责拆成三个 TU：
//   ZLibraryService.cpp  —— 服务本体 / 书库导航 / 桥接注册
//   ZLibraryMirror.cpp   —— 线路解析 / 探测 / 排序 / 失败切换
//   ZLibraryDownload.cpp —— 下载目录 / WebView2 下载处理 / 导入回调
// 三者共享窗口消息、跨 TU 结构体（布局必须与 WebViewHost.cpp 一致）与字符串
// 小工具；放这里避免各 TU 各复制一份后悄悄漂移。

#include <string>
#include <cctype>
#include <windows.h>

#define WM_ZLIB_DOWNLOAD_DONE (WM_USER + 10)
#define WM_ZLIB_DOWNLOAD_PROGRESS (WM_USER + 11)
#define WM_ZLIB_REFRESH_LIBRARY (WM_USER + 12)
#define WM_ZLIB_DO_IMPORT (WM_USER + 13)
#define WM_ZLIB_DOWNLOAD_FAILED (WM_USER + 14)
#define WM_ZLIB_IMPORT_DONE (WM_USER + 20)
#define WM_ZLIB_ENTRY_NAVIGATE (WM_USER + 21)  // 必须与 WebViewHost.cpp 一致   // 必须与 WebViewHost.cpp 一致（15-17 被 WM_UPDATE_* 占用）

struct DLProgress { std::string fn; int64_t recv; int64_t tot; };
struct DLFail { std::string fn; std::string reason; };
// 布局必须与 WebViewHost.cpp 里的同名结构完全一致（跨 TU 通过 LPARAM 传递）
struct ImportResult { std::string fileName; bool success; std::string error; };


// ── 小工具（原为文件内 static，改为 inline 供三个 TU 复用）──────────
// 会话过期时 z-lib 会返回 200 的登录页，此前会被当成书存盘并入库。
// FB2 本身是 XML，所以只把 <!doctype html / <html 判为 HTML，避免误杀。
static bool LooksLikeHtml(const char* p, size_t n) {
    size_t i = 0;
    while (i < n && (p[i] == ' ' || p[i] == '\t' || p[i] == '\r' || p[i] == '\n')) i++;
    if (n >= i + 14 && _strnicmp(p + i, "<!doctype html", 14) == 0) return true;
    if (n >= i + 5 && _strnicmp(p + i, "<html", 5) == 0) return true;
    return false;
}

// 失败/中断的下载必须删掉半截文件，否则它会留在下载目录里（且可能被重下覆盖）。
// 这里自己做 UTF-8 → 宽字符转换，不依赖文件后面才定义的 ToWide。
static void RemovePartialFile(const std::string& path) {
    if (path.empty()) return;
    int n = MultiByteToWideChar(CP_UTF8, 0, path.c_str(), (int)path.size(), nullptr, 0);
    if (n <= 0) return;
    std::wstring w(n, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, path.c_str(), (int)path.size(), &w[0], n);
    DeleteFileW(w.c_str());
}

static std::wstring ToWide(const std::string& s) {
    if (s.empty()) return L"";
    int len = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), nullptr, 0);
    if (len <= 0) return L"";
    std::wstring w(len, L'\0'); MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), &w[0], len); return w;
}
static std::string ToNarrow(LPCWSTR w) {
    if (!w) return "";
    int len = WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0, nullptr, nullptr);
    if (len <= 0) return ""; std::string s(len, '\0');
    WideCharToMultiByte(CP_UTF8, 0, w, -1, &s[0], len, nullptr, nullptr);
    while (!s.empty() && s.back() == '\0') s.pop_back(); return s;
}

static std::string TrimAscii(const std::string& s) {
    size_t b = s.find_first_not_of(" \t\r\n");
    if (b == std::string::npos) return "";
    size_t e = s.find_last_not_of(" \t\r\n");
    return s.substr(b, e - b + 1);
}




