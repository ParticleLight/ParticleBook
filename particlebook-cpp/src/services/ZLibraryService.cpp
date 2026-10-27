#include "ZLibraryService.h"
#include "BridgeServer.h"
#include "WebViewHost.h"
#include "services/DatabaseService.h"
#include "App.h"
#include "platform/LogService.h"
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shellapi.h>
#include <winhttp.h>
#include <shlobj.h>
#include <shobjidl.h>
#include <regex>
#include <cstring>
#include <algorithm>
#include <filesystem>
#include <thread>
#include <chrono>
#include <atomic>
#include <climits>
#include <fstream>
#include <sstream>


#pragma comment(lib, "winhttp.lib")
#include <wrl/event.h>
#include <utility>
using namespace Microsoft::WRL;

#include "ZLibraryInternal.h"

// ── Navigate main WebView2 + inject floating toolbar ───────────────────────

json ZLibraryService::Show() {
    m_zlibActive = true;
    // 证书错误的放行只在 Z-Library 会话期间开启（镜像会重定向到无法预先枚举的
    // 中转域，其证书自签/链不受信任）；退出会话即关闭，应用自身页面不再忽略证书。
    if (m_host) m_host->SetAllowUntrustedCerts(true);
    m_zlibDlInProgress = false;
    // （m_pendingDownloadUri 已随"一次性放行"一并移除）
    SetupDownloadHandler();

    // Load saved download path from database settings
    if (m_db && m_downloadPath.empty()) {
        json settings = m_db->GetSettings();
        if (!settings.is_null() && settings.contains("zlibDownloadPath")) {
            m_downloadPath = settings["zlibDownloadPath"].get<std::string>();
        }
    }

    // 上次真的进去过的线路（登录 Cookie 绑在域名上，能不动就不动）
    LoadLastMirrorOnce();

    auto* wv = m_host ? m_host->GetWebView() : nullptr;
    std::string url, pending;
    bool hasMirror = false;
    {
        std::lock_guard<std::mutex> lk(m_mirrorMutex);
        SelectDefaultMirrorLocked();
        if (!m_mirrors.empty()) {
            m_navRetryCount = 0;
            m_retryMirrorCount = (int)m_mirrors.size();
            pending = m_mirrors[m_currentMirror];
            url = MirrorNavigateUrlLocked(m_currentMirror);
            hasMirror = true;
        }
    }
    if (!hasMirror) return json(nullptr);

    m_entryNavPending = false;
    if (!wv) {
        ShellExecuteW(nullptr, L"open", ToWide(url).c_str(),
                      nullptr, nullptr, SW_SHOWNORMAL);
        return json(nullptr);
    }

    // 探测结果会随时间失效（线路会烂）：进站时顺手后台补探一轮，不阻塞这次导航
    RefreshRankingIfStale();

    // 【探测还没给出任何"正式站"结果就点进来】：先等一会儿（最多 5 秒）再导航。
    // 别拿第 0 条硬编码线路去撞 —— 实测那条现在经常是死的，撞上去要等十几秒才轮到
    // 换线路。等待期间探测正在跑，结果一到就导航；前端"正在连接线路…"遮罩本来就
    // 还挂着，用户看不出停顿。
    if (!ProbeReadyForEntry()) {
        StartEntryWait();
        return json(nullptr);
    }

    NavigateToCurrentMirror();
    return json(nullptr);
}

// 按当前排序发起进站导航（UI 线程）。Show() 与"等探测结果"的回投都走这里。
void ZLibraryService::NavigateToCurrentMirror() {
    auto* wv = m_host ? m_host->GetWebView() : nullptr;
    if (!wv) return;
    std::string url, pending;
    bool hasMirror = false;
    {
        std::lock_guard<std::mutex> lk(m_mirrorMutex);
        SelectDefaultMirrorLocked();
        if (!m_mirrors.empty()) {
            m_navRetryCount = 0;
            m_retryMirrorCount = (int)m_mirrors.size();
            pending = m_mirrors[m_currentMirror];
            url = MirrorNavigateUrlLocked(m_currentMirror);
            hasMirror = true;
        }
    }
    if (!hasMirror) return;

    m_pendingMirrorUrl = pending;
    m_lastDocStatus = 0;
    m_entryNav = true;
    m_challengeWaits = 0;
    m_probePause = true;    // 进站期间暂停探测（ClassifyLoadedDocument 里恢复）
    // 注意：这里【不】发 mirrorChanged —— 那条事件会让前端撤掉"正在连接线路…"
    // 遮罩，而线路要好几秒才出结果，屏幕就变成白屏干等。改为等导航结果判定之后
    // 再发（见 ClassifyLoadedDocument）。
    wv->Navigate(ToWide(url).c_str());
}

// 是否已经有可用的探测结论（有"正式站"结果，或者探测已经结束）。
bool ZLibraryService::ProbeReadyForEntry() {
    std::lock_guard<std::mutex> lk(m_mirrorMutex);
    if (!m_probeRunning) return true;   // 探测已结束：手上就是全部信息
    for (const auto& s : m_mirrorStats) if (s.kind == ZMK_ZLIB_APP) return true;
    return false;
}

// 后台等探测出结果（最多 5 秒），然后回投到 UI 线程发起导航。
void ZLibraryService::StartEntryWait() {
    HWND hwnd = m_hwnd ? m_hwnd : (m_host ? m_host->GetHwnd() : nullptr);
    auto self = App::Instance().Zlib();
    if (!hwnd || !self) { NavigateToCurrentMirror(); return; }   // 没有回投通道就照旧直接走
    if (m_entryNavPending.exchange(true)) return;                 // 已经在等了
    // 服务由 App 用 shared_ptr 持有：等待线程不会比服务活得久（同 StartMirrorFetch）
    std::thread([self, hwnd]() {
        for (int i = 0; i < 25 && self->m_entryNavPending.load(); i++) {
            if (self->ProbeReadyForEntry()) break;
            std::this_thread::sleep_for(std::chrono::milliseconds(200));
        }
        // 还在等 → 回 UI 线程导航（被关掉/被手动换线的话标志已清空，这次就不发了）
        if (self->m_entryNavPending.load()) PostMessage(hwnd, WM_ZLIB_ENTRY_NAVIGATE, 0, 0);
    }).detach();
}

json ZLibraryService::Hide() {
    m_zlibActive = false;
    m_entryNavPending = false;
    m_probePause = false;
    m_entryNav = false;
    if (m_host) {
        m_host->SetAllowUntrustedCerts(false);
        m_host->ReloadPage();
    }
    return json(nullptr);
}

json ZLibraryService::Navigate(const std::string& a) {
    auto* wv = m_host ? m_host->GetWebView() : nullptr;
    if (!wv) return json(nullptr);
    if (a == "back")      { BOOL c = FALSE; wv->get_CanGoBack(&c); if (c) wv->GoBack(); }
    else if (a == "forward") { BOOL c = FALSE; wv->get_CanGoForward(&c); if (c) wv->GoForward(); }
    else if (a == "reload")   wv->Reload();
    return json(nullptr);
}

json ZLibraryService::GetURL() {
    if (m_host && m_host->GetWebView()) {
        LPWSTR u = nullptr; m_host->GetWebView()->get_Source(&u);
        if (u) { m_currentUrl = ToNarrow(u); CoTaskMemFree(u); }
    }
    return json(m_currentUrl);
}

json ZLibraryService::SetBounds(int, int, int, int) { return json(nullptr); }
json ZLibraryService::Logout() {
    // 真正的退出登录：清掉 WebView2 里保存的会话 Cookie。此前只是导航回镜像首页，
    // Cookie 原封不动 —— 共享电脑上等于没退出（而"登录状态持久保存"是刻意设计，
    // 所以更需要一个能主动清除的入口）。
    auto* wv = m_host ? m_host->GetWebView() : nullptr;
    if (wv) {
        ComPtr<ICoreWebView2_2> wv2;
        if (SUCCEEDED(wv->QueryInterface(IID_PPV_ARGS(&wv2)))) {
            ComPtr<ICoreWebView2CookieManager> cm;
            if (SUCCEEDED(wv2->get_CookieManager(&cm)) && cm) {
                cm->DeleteAllCookies();
            }
        }
    }
    return Hide();   // 顺带关闭会话与证书放行，并回到书架
}


void RegisterZlibHandlers(BridgeServer* bridge, ZLibraryService* zlib) {
    bridge->RegisterMethod("zlib:getMirrorInfo", [zlib](const json&)    { return zlib->GetMirrorInfo(); });
    bridge->RegisterMethod("zlib:switchMirror",   [zlib](const json& p) { return zlib->SwitchMirror(p["index"].get<int>()); });
    bridge->RegisterMethod("zlib:fetchMirrors",   [zlib](const json&)   { return zlib->FetchMirrors(); });
    bridge->RegisterMethod("zlib:show",           [zlib](const json&)   { return zlib->Show(); });
    bridge->RegisterMethod("zlib:hide",           [zlib](const json&)   { return zlib->Hide(); });
    bridge->RegisterMethod("zlib:navigate",       [zlib](const json& p) { return zlib->Navigate(p.value("action","")); });
    bridge->RegisterMethod("zlib:getURL",         [zlib](const json&)   { return zlib->GetURL(); });
    bridge->RegisterMethod("zlib:setBounds",      [zlib](const json& p) {
        auto b = p["bounds"]; return zlib->SetBounds(b.value("x",0),b.value("y",0),b.value("width",0),b.value("height",0));
    });
    bridge->RegisterMethod("zlib:logout",         [zlib](const json&)   { return zlib->Logout(); });
    bridge->RegisterMethod("zlib:setDownloadPath", [zlib](const json& p) {
        return zlib->SetDownloadPath(p.value("path", ""));
    });
    bridge->RegisterMethod("zlib:getDownloadPath", [zlib](const json&) {
        return zlib->GetDownloadPathStr();
    });
    bridge->RegisterMethod("zlib:pickDownloadFolder", [zlib](const json&) {
        return zlib->PickDownloadFolder();
    });
}
