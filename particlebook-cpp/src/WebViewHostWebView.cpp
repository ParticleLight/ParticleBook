#include "WebViewHost.h"
#include "App.h"
#include "WebViewHostInternal.h"
#include <wrl/event.h>
#include <filesystem>
#include <cstdio>
#include <thread>

using namespace Microsoft::WRL;

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

    // Z-Library mirrors redirect to unlabeled transit domains (e.g. msn101.ru)
    // whose certs are self-signed or chain to roots the system doesn't trust, and
    // those hosts can't be enumerated reliably — so a host whitelist isn't viable.
    //
    // 但"不处理敏感数据"这个理由不成立：用户正是在这个 WebView 里输入 Z-Library
    // 账号密码，全局放行等于让中间人可以伪造登录页。因此把放行【限定在 Z-Library
    // 会话期间】（ZLibraryService::Show/Hide 负责开关）：应用自身页面与任何非会话
    // 浏览不再忽略证书错误。残余风险见 MEMORY.md（会话期间的任意主机仍会放行）。
    ComPtr<ICoreWebView2_14> wv14;
    if (SUCCEEDED(m_webview.As(&wv14))) {
        wv14->add_ServerCertificateErrorDetected(
            Callback<ICoreWebView2ServerCertificateErrorDetectedEventHandler>(
                [this](ICoreWebView2*, ICoreWebView2ServerCertificateErrorDetectedEventArgs* args) -> HRESULT {
                    if (m_allowUntrustedCerts) {
                        args->put_Action(COREWEBVIEW2_SERVER_CERTIFICATE_ERROR_ACTION_ALWAYS_ALLOW);
                    }
                    // 非会话期间不设 Action → 交给 WebView2 默认处理（不放行）
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

