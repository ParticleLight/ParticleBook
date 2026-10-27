#pragma once
// ── PB-024-C：WebViewHost 跨 TU 共用的窗口消息 / 结构 / 转换工具 ────────
// 原 WebViewHost.cpp（421 行）按职责收缩为：
//   WebViewHost.cpp       —— 窗口创建 / 消息循环 / WndProc 分发
//   WebViewHostWebView.cpp —— WebView2 环境与控制器初始化
//   WebViewHostPage.cpp   —— 页面导航 / 脚本注入 / 与渲染层消息
// 窗口消息号与跨 TU 结构体（布局必须与 ZLibraryService 侧一致）放这里。

#include <string>
#include <cstdint>
#include <windows.h>

#define WM_INIT_WEBVIEW     (WM_USER + 1)
#define WM_RELOAD_PAGE      (WM_USER + 2)
#define WM_RELOAD_PAGE_STEP2 (WM_USER + 3)
#define WM_ZLIB_DOWNLOAD_DONE (WM_USER + 10)
#define WM_ZLIB_DOWNLOAD_PROGRESS (WM_USER + 11)
#define WM_ZLIB_REFRESH_LIBRARY (WM_USER + 12)
#define WM_ZLIB_DO_IMPORT (WM_USER + 13)
#define WM_ZLIB_DOWNLOAD_FAILED (WM_USER + 14)
#define WM_ZLIB_IMPORT_DONE (WM_USER + 20)
#define WM_ZLIB_ENTRY_NAVIGATE (WM_USER + 21)   // 同样定义在 ZLibraryService.cpp   // 15-17 已被 WebViewHost.h 的 WM_UPDATE_* 占用

struct DLProgressData { std::string fileName; int64_t received; int64_t total; };
struct DLFailData { std::string fileName; std::string reason; };
// 布局必须与 ZLibraryService.cpp 里的同名结构完全一致（跨 TU 通过 LPARAM 传递）
struct ImportResultData { std::string fileName; bool success; std::string error; };


// Helper: UTF-8 ↔ wide（原 static，改为 inline 供三个 TU 复用）
// Helper: UTF-8 to wide string
static std::wstring ToWide(const std::string& s) {
    if (s.empty()) return L"";
    int len = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), nullptr, 0);
    if (len <= 0) return L"";
    std::wstring w(len, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), &w[0], len);
    return w;
}

// Helper: wide string to UTF-8
static std::string ToUtf8(const std::wstring& w) {
    if (w.empty()) return "";
    int len = WideCharToMultiByte(CP_UTF8, 0, w.c_str(), (int)w.size(), nullptr, 0, nullptr, nullptr);
    if (len <= 0) return "";
    std::string s(len, '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.c_str(), (int)w.size(), &s[0], len, nullptr, nullptr);
    return s;
}


