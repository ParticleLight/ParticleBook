#pragma once
#include <string>
#include <vector>
#include <mutex>
#include <memory>
#include <chrono>
#include <atomic>
#include "nlohmann/json.hpp"
#include <wrl/client.h>
#include <WebView2.h>

using json = nlohmann::json;

class BridgeServer;
class WebViewHost;
class DatabaseService;

// 一条 Z-Library 线路的实测结果（后台探测线程写、UI 线程读，读写都在 m_mirrorMutex 内）
enum ZlibMirrorKind {
    ZMK_UNKNOWN   = 0,   // 还没探过 / 结果不明确
    ZMK_ZLIB_APP  = 1,   // Z-Library 正式站（含 "Checking your browser" 的 JS 挑战页：过完就是站）
    ZMK_ZLIB_PAGE = 2,   // 只是提到 Z-Library 的导航页 / 推广页 / 资源合集（实测最"快"的几条都是这类）
    ZMK_OTHER     = 3,   // 通了，但跟 Z-Library 无关
    ZMK_FAIL      = 4,   // 网络层或 HTTP 层不可用
};

struct ZlibMirrorStat {
    std::string url;        // 名单里的入口地址（对外展示的就是它）
    std::string finalUrl;   // 跟随重定向后的最终地址：直接导航它可省掉入口域名的 DNS+握手
    int ms = -1;            // 首字节耗时（毫秒）；-1 = 没测到
    int status = 0;         // HTTP 状态码；0 = 网络层失败
    int kind = ZMK_UNKNOWN;
};

class ZLibraryService {
public:
    ZLibraryService(BridgeServer* bridge);
    ~ZLibraryService();

    json GetMirrorInfo();
    json SwitchMirror(int index);
    json FetchMirrors();

    // 启动时在后台线程里：抓镜像名单 → 并行探测各线路 → 按可达性/耗时重排。
    // Called from App::Init with the service's own shared_ptr so the object stays alive for
    // the (detached) threads' lifetime — avoids use-after-free on shutdown.
    void StartMirrorFetch(std::shared_ptr<ZLibraryService> self);

    // 探测结果过期（或还没探过）时后台补探一轮。进站/换线时调用，不阻塞这次导航。
    void RefreshRankingIfStale();
    // 并行探测 + 重排（只应该在后台线程里跑）
    void ProbeAndRank();

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

    // ── 线路选择（*Locked 结尾的要求调用方已持有 m_mirrorMutex）──
    void SelectDefaultMirrorLocked();
    void SortMirrorsLocked();
    void ApplyProbeResultLocked(const ZlibMirrorStat& stat);
    std::string MirrorNavigateUrlLocked(int index);
    void LoadLastMirrorOnce();
    void RememberWorkingMirror(const std::string& url);

    // ── 导航结果的判定（都在 UI 线程跑）──
    void NavigateToCurrentMirror();  // UI 线程：按当前排序发起进站导航
    bool ProbeReadyForEntry();       // 是否已有可用的"正式站"探测结果
    void StartEntryWait();           // 后台等探测结果，拿到后回投 WM_ZLIB_ENTRY_NAVIGATE
    void ClassifyLoadedDocument();   // 看 document.title + 主文档状态码
    void AdvanceMirrorOnFailure();   // 换下一条线路，轮完则报 allMirrorsFailed

    BridgeServer* m_bridge;
    WebViewHost* m_host = nullptr;
    DatabaseService* m_db = nullptr;
    std::vector<std::string> m_mirrors;
    std::vector<ZlibMirrorStat> m_mirrorStats;   // 与 m_mirrors 同长、同序
    int m_currentMirror = 0;
    std::string m_currentUrl;
    bool m_mirrorPinned = false;                 // 用户本次会话手动选过线路
    std::string m_pinnedUrl;
    std::string m_lastWorkingMirror;             // 上次真的进去过的线路（持久化到设置里）
    bool m_lastMirrorLoaded = false;
    std::atomic<bool> m_probeRunning{false};
    std::chrono::steady_clock::time_point m_probeFinishedAt{};
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
    // ── 以下四个只在 UI 线程读写（WebView2 的事件回调都在 UI 线程）──
    std::string m_docUri;            // 最近一次「主文档」导航的 URI
    int m_lastDocStatus = 0;         // 主文档的 HTTP 状态码（0 = 未知）
    std::string m_pendingMirrorUrl;  // 当前正在尝试的线路（入口 URL）
    bool m_entryNav = false;         // 这次导航是"进站导航"（只有它才把 4xx/5xx 判为线路失败）
    int m_challengeWaits = 0;
    std::atomic<bool> m_entryNavPending{false};   // 正在"等探测结果再进站"
    std::atomic<bool> m_probePause{false};        // 进站导航期间暂停探测，别抢带宽        // 本次进站已为 JS 挑战"让路"几次（见 NavigationCompleted）
    std::mutex m_mirrorMutex;
    EventRegistrationToken m_downloadToken = {};
    EventRegistrationToken m_navToken = {};
};

void RegisterZlibHandlers(BridgeServer* bridge, ZLibraryService* zlib);
