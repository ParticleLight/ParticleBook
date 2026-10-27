#include "WebViewHost.h"
#include "App.h"
#include "WebViewHostInternal.h"
#include <wrl/event.h>
#include <filesystem>
#include <cstdio>
#include <thread>

using namespace Microsoft::WRL;

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

    // Capture the message's source (page URL) for origin-based access control:
    // the app page vs. external pages (Z-Library mirrors) must not get the same
    // set of bridge methods.
    LPWSTR src = nullptr;
    std::string source;
    if (SUCCEEDED(args->get_Source(&src)) && src) {
        source = ToUtf8(src);
        CoTaskMemFree(src);
    }

    if (m_msgHandler) {
        m_msgHandler(msg, source);
    }
}
