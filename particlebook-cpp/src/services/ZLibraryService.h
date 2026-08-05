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

    json SetDownloadPath(const std::string& path);
    json GetDownloadPathStr() const;
    json PickDownloadFolder();

private:
    void SetupDownloadHandler();
    void StartDownloadThread(const std::string& url, const std::string& fileName = "", const std::string& cookies = "");
    std::string GetDownloadPath() const;
    void OnDownloadDone(const std::string& path, const std::string& fileName);
    void DoImport(const std::string& path, const std::string& fileName);

    BridgeServer* m_bridge;
    WebViewHost* m_host = nullptr;
    DatabaseService* m_db = nullptr;
    std::vector<std::string> m_mirrors;
    int m_currentMirror = 0;
    std::string m_pendingDownloadUri;
    std::string m_currentUrl;
    std::string m_downloadPath;
    HWND m_hwnd = nullptr;
    bool m_zlibActive = false;
    bool m_downloadRegistered = false;
    bool m_zlibDlInProgress = false;
    int m_navRetryCount = 0;
    int m_retryMirrorCount = 0;   // snapshot of mirror count at Show() — bounds retry to one full cycle
    std::mutex m_mirrorMutex;
    EventRegistrationToken m_downloadToken = {};
    EventRegistrationToken m_navToken = {};
};

void RegisterZlibHandlers(BridgeServer* bridge, ZLibraryService* zlib);
