#pragma once
#include <string>
#include <vector>
#include <mutex>
#include <memory>
#include "nlohmann/json.hpp"
#include <wrl/client.h>
#include <WebView2.h>

using json = nlohmann::json;

class BridgeServer;
class WebViewHost;
class DatabaseService;

class ZLibraryService {
public:
    ZLibraryService(BridgeServer* bridge);
    ~ZLibraryService();

    json GetMirrorInfo();
    json SwitchMirror(int index);
    json FetchMirrors();

    // Start the mirror-prefetch worker thread. Called from App::Init with the
    // service's own shared_ptr so the object stays alive for the (detached)
    // thread's lifetime — avoids use-after-free on shutdown.
    void StartMirrorFetch(std::shared_ptr<ZLibraryService> self);

    // Browser methods (use main WebView2)
    json Show();
    json Hide();
    json Navigate(const std::string& action);
    json GetURL();
    json SetBounds(int x, int y, int width, int height);
    json Logout();

    void SetHost(WebViewHost* host) { m_host = host; }
    void SetDatabase(DatabaseService* db) { m_db = db; }

    // Heuristic: is this host a Z-Library mirror / transit domain?
    // Used when parsing mirror lists. NOTE: the WebView2 certificate callback
    // deliberately does NOT scope by host — transit domains cannot be
    // enumerated reliably, so it allows certificate errors unconditionally
    // (see WebViewHost::OnWebViewCreated for the trade-off).
    static bool IsZlibHost(const std::string& host);

    json SetDownloadPath(const std::string& path);
    json GetDownloadPathStr() const;
    json PickDownloadFolder();

private:
    void SetupDownloadHandler();
    void StartDownloadThread(const std::string& url, const std::string& fileName = "", const std::string& cookies = "");
    std::string GetDownloadPath() const;
    void OnDownloadDone(const std::string& path, const std::string& fileName);
    void DoImport(const std::string& path, const std::string& fileName);
    // 导入在工作线程完成，结果回投到 UI 线程后在这里发事件
    void OnImportDone(const std::string& fileName, bool success, const std::string& error);

    BridgeServer* m_bridge;
    WebViewHost* m_host = nullptr;
    DatabaseService* m_db = nullptr;
    std::vector<std::string> m_mirrors;
    int m_currentMirror = 0;
    std::string m_currentUrl;
    std::string m_downloadPath;
    HWND m_hwnd = nullptr;
    bool m_zlibActive = false;
    bool m_downloadRegistered = false;
    bool m_zlibDlInProgress = false;
    // 下载开始的时刻，用作看门狗：该标志位只在 Show/失败回调/DoImport 复位，
    // 一旦下载线程异常未归就会永久卡住 → 之后所有下载都被静默吞掉。
    std::chrono::steady_clock::time_point m_dlStartedAt{};
    int m_navRetryCount = 0;
    // 本次导航是否被我们自己的守卫取消（危险协议）。取消也会让 NavigationCompleted
    // 报 IsSuccess=FALSE，但那不是镜像失败，不能拿它去消耗换线路的重试额度。
    bool m_navCancelledByGuard = false;
    int m_retryMirrorCount = 0;   // snapshot of mirror count at Show() — bounds retry to one full cycle
    std::mutex m_mirrorMutex;
    EventRegistrationToken m_downloadToken = {};
    EventRegistrationToken m_navToken = {};
};

void RegisterZlibHandlers(BridgeServer* bridge, ZLibraryService* zlib);
