#include "WebViewHost.h"
#include "App.h"
#include <wrl/event.h>
#include <filesystem>
#include <cstdio>
#include <thread>

#include "WebViewHostInternal.h"

using namespace Microsoft::WRL;

WebViewHost::WebViewHost()
    : m_dispatcher(std::make_shared<pb::UiDispatcher>()) {}

WebViewHost::~WebViewHost()
{
    if (m_dispatcher) { m_dispatcher->Attach(nullptr); m_dispatcher->Stop(); }
    if (m_webview) {
        m_webview->remove_WebMessageReceived(m_msgToken);
    }
    if (m_controller) {
        m_controller->Close();
    }
    if (m_hwnd) {
        DestroyWindow(m_hwnd);
    }
}

void WebViewHost::CreateMainWindow(HINSTANCE hInstance)
{
    const wchar_t CLASS_NAME[] = L"ParticleBook_MainWindow";

    WNDCLASSW wc = {};
    wc.lpfnWndProc   = WndProc;
    wc.hInstance     = hInstance;
    wc.hCursor       = LoadCursor(nullptr, IDC_ARROW);
    wc.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
    wc.lpszClassName = CLASS_NAME;
    RegisterClassW(&wc);

    m_hwnd = CreateWindowExW(
        WS_EX_APPWINDOW,
        CLASS_NAME, L"ParticleBook",
        WS_OVERLAPPEDWINDOW,
        CW_USEDEFAULT, CW_USEDEFAULT, 1400, 900,
        nullptr, nullptr, hInstance, this
    );

    if (m_dispatcher) m_dispatcher->Attach(m_hwnd);

    // Set window icon from embedded resource
    HICON hIcon = LoadIconW(hInstance, MAKEINTRESOURCEW(101));
    if (hIcon) {
        SendMessageW(m_hwnd, WM_SETICON, ICON_BIG, (LPARAM)hIcon);
        SendMessageW(m_hwnd, WM_SETICON, ICON_SMALL, (LPARAM)hIcon);
    }

    ShowWindow(m_hwnd, SW_SHOW);
    UpdateWindow(m_hwnd);
}

void WebViewHost::RunMessageLoop()
{
    MSG msg = {};
    while (GetMessage(&msg, nullptr, 0, 0)) {
        TranslateMessage(&msg);
        try {
            DispatchMessage(&msg);
        } catch (...) {
            // Silently ignore unhandled exceptions
        }
    }
}

LRESULT CALLBACK WebViewHost::WndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp)
{
    WebViewHost* self = nullptr;

    if (msg == WM_NCCREATE) {
        auto* cs = reinterpret_cast<CREATESTRUCT*>(lp);
        self = static_cast<WebViewHost*>(cs->lpCreateParams);
        SetWindowLongPtr(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(self));
        self->m_hwnd = hwnd;
    } else {
        self = reinterpret_cast<WebViewHost*>(GetWindowLongPtr(hwnd, GWLP_USERDATA));
    }

    // 必须显式用 W 版：本工程未定义 UNICODE 宏时 DefWindowProc 会展开成 ANSI 版，
    // 于是 WM_SETTEXT 等消息里的宽字符串被按 ANSI 解读 —— 窗口标题因此只剩首字符 "P"。
    if (!self) return DefWindowProcW(hwnd, msg, wp, lp);

    if (self->m_dispatcher && self->m_dispatcher->OnWindowMessage(msg)) return 0;

    switch (msg) {
    case WM_CREATE:
        PostMessage(hwnd, WM_INIT_WEBVIEW, 0, 0);
        return 0;

    case WM_INIT_WEBVIEW:
        self->InitWebView();
        return 0;

    case WM_SIZE:
        if (self->m_controller) {
            RECT bounds;
            GetClientRect(hwnd, &bounds);
            self->m_controller->put_Bounds(bounds);
        }
        if (self->m_moveCb) self->m_moveCb();
        return 0;

    case WM_ZLIB_DOWNLOAD_DONE:
        if (wp == 1 && self->m_dlCb) {
            auto* data = reinterpret_cast<std::pair<std::string, std::string>*>(lp);
            self->m_dlCb(data->first, data->second);
            delete data;
        }
        return 0;

    case WM_ZLIB_DOWNLOAD_PROGRESS:
        if (self->m_dlProgressCb) {
            auto* data = reinterpret_cast<DLProgressData*>(lp);
            self->m_dlProgressCb(data->fileName, data->received, data->total);
            delete data;
        }
        return 0;

    case WM_ZLIB_DO_IMPORT:
        if (self->m_importCb) {
            auto* data = reinterpret_cast<std::pair<std::string, std::string>*>(lp);
            self->m_importCb(data->first, data->second);
            delete data;
        }
        return 0;

    case WM_ZLIB_IMPORT_DONE:
        if (self->m_importResultCb) {
            auto* data = reinterpret_cast<ImportResultData*>(lp);
            self->m_importResultCb(data->fileName, data->success, data->error);
            delete data;
        }
        return 0;

    case WM_ZLIB_DOWNLOAD_FAILED:
        if (self->m_dlFailCb) {
            auto* data = reinterpret_cast<DLFailData*>(lp);
            self->m_dlFailCb(data->fileName, data->reason);
            delete data;
        }
        return 0;

    case WM_ZLIB_ENTRY_NAVIGATE:
        if (self->m_zlibEntryNavCb) self->m_zlibEntryNavCb();
        return 0;

    case WM_ZLIB_REFRESH_LIBRARY:
        if (self->m_webview) {
            self->m_webview->ExecuteScript(L"if(window.__refreshLibrary)window.__refreshLibrary();", nullptr);
        }
        return 0;

    case WM_UPDATE_DOWNLOAD_DONE:
        if (self->m_updateDoneCb) {
            auto* str = reinterpret_cast<std::string*>(lp);
            if (wp != 0) {
                std::string path = str ? std::move(*str) : "";
                delete str;
                self->m_updateDoneCb(true, path);
            } else {
                std::string err = str ? std::move(*str) : "unknown";
                delete str;
                self->m_updateDoneCb(false, err);
            }
        }
        return 0;

    case WM_UPDATE_CHECK_DONE:
        if (self->m_updateCheckCb) {
            auto* str = reinterpret_cast<std::string*>(lp);
            std::string result = str ? std::move(*str) : "{}";
            delete str;
            self->m_updateCheckCb(result);
        }
        return 0;

    case WM_UPDATE_DOWNLOAD_PROGRESS:
        if (self->m_updateDownloadProgressCb) {
            self->m_updateDownloadProgressCb(static_cast<int>(wp));
        }
        return 0;

    case WM_APP_OPEN_FILES:
        if (self->m_openFilesCb) self->m_openFilesCb();
        return 0;

    case WM_MOVE:
        if (self->m_moveCb) self->m_moveCb();
        return 0;

    case WM_CLOSE:
        DestroyWindow(hwnd);
        return 0;

    case WM_DESTROY:
        PostQuitMessage(0);
        return 0;

    case WM_NCDESTROY:
        if (self->m_dispatcher) self->m_dispatcher->Attach(nullptr);
        break;

    case WM_RELOAD_PAGE:
        if (self->m_webview) {
            self->m_webview->Navigate(L"http://particlebook.app/index.html");
        }
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

