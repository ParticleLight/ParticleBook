#include "WebViewHost.h"
#include "App.h"
#include <wrl/event.h>
#include <filesystem>
#include <cstdio>
#include <thread>

#define WM_INIT_WEBVIEW     (WM_USER + 1)
#define WM_RELOAD_PAGE      (WM_USER + 2)
#define WM_RELOAD_PAGE_STEP2 (WM_USER + 3)
#define WM_ZLIB_DOWNLOAD_DONE (WM_USER + 10)
#define WM_ZLIB_DOWNLOAD_PROGRESS (WM_USER + 11)
#define WM_ZLIB_REFRESH_LIBRARY (WM_USER + 12)
#define WM_ZLIB_DO_IMPORT (WM_USER + 13)
#define WM_ZLIB_DOWNLOAD_FAILED (WM_USER + 14)

struct DLProgressData { std::string fileName; int64_t received; int64_t total; };
struct DLFailData { std::string fileName; std::string reason; };

using namespace Microsoft::WRL;

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

WebViewHost::~WebViewHost()
{
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

    if (!self) return DefWindowProc(hwnd, msg, wp, lp);

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

    case WM_ZLIB_DOWNLOAD_FAILED:
        if (self->m_dlFailCb) {
            auto* data = reinterpret_cast<DLFailData*>(lp);
            self->m_dlFailCb(data->fileName, data->reason);
            delete data;
        }
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
                std::string err = str ? std::move(*str) : "未知错误";
                delete str;
                self->m_updateDoneCb(false, err);
            }
        }
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

    case WM_RELOAD_PAGE:
        if (self->m_webview) {
            self->m_webview->Navigate(L"http://particlebook.app/index.html");
        }
        return 0;
    }
    return DefWindowProc(hwnd, msg, wp, lp);
}

void WebViewHost::InitWebView()
{
    auto userData = std::filesystem::absolute(App::Instance().UserDataPath()).wstring();
    wchar_t exePathBuf[MAX_PATH];
    GetModuleFileNameW(nullptr, exePathBuf, MAX_PATH);
    auto exeDir = std::filesystem::path(exePathBuf).parent_path().wstring();

    CreateCoreWebView2EnvironmentWithOptions(
        nullptr, userData.c_str(), nullptr,
        Callback<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>(
            [this, exeDir](HRESULT hr, ICoreWebView2Environment* env) -> HRESULT {
                if (FAILED(hr)) {
                    char buf[256];
                    snprintf(buf, sizeof(buf), "WebView2 Environment failed: 0x%08X", (unsigned)hr);
                    MessageBoxA(m_hwnd, buf, "ParticleBook Error", MB_OK | MB_ICONERROR);
                    DestroyWindow(m_hwnd);
                    return hr;
                }

                env->QueryInterface(IID_PPV_ARGS(&m_env));

                // Create WebView controller
                m_env->CreateCoreWebView2Controller(
                    m_hwnd,
                    Callback<ICoreWebView2CreateCoreWebView2ControllerCompletedHandler>(
                        [this](HRESULT hr, ICoreWebView2Controller* controller) -> HRESULT {
                            OnWebViewCreated(hr, controller);
                            return S_OK;
                        }).Get());
                return S_OK;
            }).Get());
}

void WebViewHost::OnWebViewCreated(HRESULT hr, ICoreWebView2Controller* controller)
{
    if (FAILED(hr) || !controller) {
        char buf[256];
        snprintf(buf, sizeof(buf), "WebView2 Controller failed: 0x%08X", (unsigned)hr);
        MessageBoxA(m_hwnd, buf, "ParticleBook Error", MB_OK | MB_ICONERROR);
        DestroyWindow(m_hwnd);
        return;
    }

    m_controller = controller;
    m_controller->get_CoreWebView2(&m_webview);

    // Settings
    m_webview->get_Settings(&m_settings);
    m_settings->put_AreDevToolsEnabled(TRUE);
    m_settings->put_IsScriptEnabled(TRUE);

    // Set user agent to match Chrome (avoid blocking by Z-Library)
    ComPtr<ICoreWebView2Settings2> settings2;
    if (SUCCEEDED(m_settings.As(&settings2))) {
        settings2->put_UserAgent(L"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
    }

    // During any navigation, allow SSL certificate errors (Z-Library mirrors
    // redirect to transit domains like msn101.ru whose certs may be self-signed
    // or chain to roots the system doesn't trust; we're loading a known book site,
    // not handling sensitive data). Always-allow is the simplest robust choice.
    ComPtr<ICoreWebView2_14> wv14;
    if (SUCCEEDED(m_webview.As(&wv14))) {
        wv14->add_ServerCertificateErrorDetected(
            Callback<ICoreWebView2ServerCertificateErrorDetectedEventHandler>(
                [](ICoreWebView2*, ICoreWebView2ServerCertificateErrorDetectedEventArgs* args) -> HRESULT {
                    args->put_Action(COREWEBVIEW2_SERVER_CERTIFICATE_ERROR_ACTION_ALWAYS_ALLOW);
                    return S_OK;
                }).Get(), nullptr);
    }

    // Resize to fill window
    RECT bounds;
    GetClientRect(m_hwnd, &bounds);
    m_controller->put_Bounds(bounds);

    // Set up virtual host mapping via ICoreWebView2_3
    wchar_t exePathBuf[MAX_PATH];
    GetModuleFileNameW(nullptr, exePathBuf, MAX_PATH);
    auto rendererPath = (std::filesystem::path(exePathBuf).parent_path() / L"renderer").wstring();
    ComPtr<ICoreWebView2_3> wv3;
    if (SUCCEEDED(m_webview->QueryInterface(IID_PPV_ARGS(&wv3)))) {
        wv3->SetVirtualHostNameToFolderMapping(
            L"particlebook.app", rendererPath.c_str(),
            COREWEBVIEW2_HOST_RESOURCE_ACCESS_KIND_ALLOW);
    }

    // Web message handler
    m_webview->add_WebMessageReceived(
        Callback<ICoreWebView2WebMessageReceivedEventHandler>(
            [this](ICoreWebView2* sender, ICoreWebView2WebMessageReceivedEventArgs* args) -> HRESULT {
                OnWebMessageReceived(sender, args);
                return S_OK;
            }).Get(), &m_msgToken);

    // Inject bridge script BEFORE navigating
    for (auto& s : m_pendingScripts) {
        std::wstring wScript = ToWide(s);
        m_webview->AddScriptToExecuteOnDocumentCreated(wScript.c_str(), nullptr);
    }
    m_pendingScripts.clear();

    // Navigate to frontend via virtual host
    m_webview->Navigate(L"http://particlebook.app/index.html");
}

void WebViewHost::PostMessageToRenderer(const std::string& json)
{
    if (m_webview) {
        std::wstring wJson = ToWide(json);
        m_webview->PostWebMessageAsJson(wJson.c_str());
    }
}

void WebViewHost::ReloadPage()
{
    if (m_hwnd) {
        PostMessage(m_hwnd, WM_RELOAD_PAGE, 0, 0);
    }
}

void WebViewHost::ExecuteScriptOnPage(const std::wstring& script)
{
    if (m_webview) {
        m_webview->ExecuteScript(script.c_str(), nullptr);
    }
}

void WebViewHost::InjectBridgeScript(const std::string& script)
{
    if (m_webview) {
        std::wstring wScript = ToWide(script);
        m_webview->AddScriptToExecuteOnDocumentCreated(wScript.c_str(), nullptr);
    } else {
        m_pendingScripts.push_back(script);
    }
}

void WebViewHost::OnWebMessageReceived(ICoreWebView2* sender, ICoreWebView2WebMessageReceivedEventArgs* args)
{
    LPWSTR raw = nullptr;
    if (FAILED(args->TryGetWebMessageAsString(&raw)) || !raw) return;

    std::string msg = ToUtf8(raw);
    CoTaskMemFree(raw);

    if (m_msgHandler) {
        m_msgHandler(msg);
    }
}
