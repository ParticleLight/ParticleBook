#pragma once
#define WIN32_LEAN_AND_MEAN
#include <windows.h>

// Custom messages for cross-thread communication
#define WM_UPDATE_DOWNLOAD_DONE (WM_USER + 15)
#include <string>
#include <vector>
#include <functional>
#include <memory>
#include <wrl/client.h>  // Microsoft::WRL::ComPtr
#include <WebView2.h>

namespace wrl = Microsoft::WRL;

class WebViewHost {
public:
    WebViewHost() = default;
    ~WebViewHost();

    void CreateMainWindow(HINSTANCE hInstance);
    void RunMessageLoop();

    void PostMessageToRenderer(const std::string& json);
    void InjectBridgeScript(const std::string& script);
    void ReloadPage();
    void ExecuteScriptOnPage(const std::wstring& script);

    HWND GetHwnd() const { return m_hwnd; }
    ICoreWebView2Controller* GetController() const { return m_controller.Get(); }
    ICoreWebView2* GetWebView() const { return m_webview.Get(); }
    ICoreWebView2Environment* GetEnv() const { return m_env.Get(); }

    using MoveCallback = std::function<void()>;
    void SetMoveCallback(MoveCallback cb) { m_moveCb = std::move(cb); }

    using DownloadCallback = std::function<void(const std::string& path, const std::string& fileName)>;
    void SetDownloadCallback(DownloadCallback cb) { m_dlCb = std::move(cb); }

    using DownloadProgressCb = std::function<void(const std::string& fileName, int64_t received, int64_t total)>;
    void SetDownloadProgressCallback(DownloadProgressCb cb) { m_dlProgressCb = std::move(cb); }

    using ImportCallback = std::function<void(const std::string& path, const std::string& fileName)>;
    void SetImportCallback(ImportCallback cb) { m_importCb = std::move(cb); }

    using DownloadFailCb = std::function<void(const std::string& fileName, const std::string& reason)>;
    void SetDownloadFailCallback(DownloadFailCb cb) { m_dlFailCb = std::move(cb); }

    using UpdateDoneCb = std::function<void(bool success, const std::string& path_or_error)>;
    void SetUpdateDoneCallback(UpdateDoneCb cb) { m_updateDoneCb = std::move(cb); }

    using MessageHandler = std::function<void(const std::string&)>;
    void SetMessageHandler(MessageHandler handler) { m_msgHandler = std::move(handler); }

    void PostMessageToMainThread(UINT msg, WPARAM wp, LPARAM lp) {
        if (m_hwnd) PostMessage(m_hwnd, msg, wp, lp);
    }

private:
    static LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp);
    void InitWebView();
    void OnWebViewCreated(HRESULT hr, ICoreWebView2Controller* controller);
    void OnWebMessageReceived(ICoreWebView2* sender, ICoreWebView2WebMessageReceivedEventArgs* args);

    HWND m_hwnd = nullptr;
    wrl::ComPtr<ICoreWebView2Environment> m_env;
    wrl::ComPtr<ICoreWebView2Controller> m_controller;
    wrl::ComPtr<ICoreWebView2> m_webview;
    wrl::ComPtr<ICoreWebView2Settings> m_settings;
    EventRegistrationToken m_msgToken = {};

    MessageHandler m_msgHandler;
    MoveCallback m_moveCb;
    DownloadCallback m_dlCb;
    DownloadProgressCb m_dlProgressCb;
    ImportCallback m_importCb;
    DownloadFailCb m_dlFailCb;
    UpdateDoneCb m_updateDoneCb;
    std::vector<std::string> m_pendingScripts;
};
