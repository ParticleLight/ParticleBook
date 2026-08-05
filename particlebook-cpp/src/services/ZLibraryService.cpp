#include "ZLibraryService.h"
#include "BridgeServer.h"
#include "WebViewHost.h"
#include "services/DatabaseService.h"
#include "App.h"
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shellapi.h>
#include <winhttp.h>
#include <shlobj.h>
#include <shobjidl.h>
#include <regex>
#include <algorithm>
#include <filesystem>
#include <thread>
#include <chrono>
#include <fstream>
#include <sstream>

#pragma comment(lib, "winhttp.lib")
#include <wrl/event.h>
#include <utility>
using namespace Microsoft::WRL;

#define WM_ZLIB_DOWNLOAD_DONE (WM_USER + 10)
#define WM_ZLIB_DOWNLOAD_PROGRESS (WM_USER + 11)
#define WM_ZLIB_REFRESH_LIBRARY (WM_USER + 12)
#define WM_ZLIB_DO_IMPORT (WM_USER + 13)
#define WM_ZLIB_DOWNLOAD_FAILED (WM_USER + 14)

struct DLProgress { std::string fn; int64_t recv; int64_t tot; };
struct DLFail { std::string fn; std::string reason; };

static std::wstring ToWide(const std::string& s) {
    if (s.empty()) return L"";
    int len = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), nullptr, 0);
    if (len <= 0) return L"";
    std::wstring w(len, L'\0'); MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), &w[0], len); return w;
}
static std::string ToNarrow(LPCWSTR w) {
    if (!w) return "";
    int len = WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0, nullptr, nullptr);
    if (len <= 0) return ""; std::string s(len, '\0');
    WideCharToMultiByte(CP_UTF8, 0, w, -1, &s[0], len, nullptr, nullptr);
    while (!s.empty() && s.back() == '\0') s.pop_back(); return s;
}



static const std::vector<std::string> FALLBACK_MIRRORS = {
    "https://zh.dfj101.ru/",
    "https://zlib.re/",
    "https://z-lib.by/",
    "https://zh.zlib0.ru/",
    "https://zh.z-library.sk/",
    "https://zh.z-lib.gd/",
    "https://zh.101fbiwarning.ru/",
    "https://zh.zzz101.ru/",
    "https://zh.1lib.sk/",
    "https://zh.singlelogin.rs/",
};

// Permanent blocklist: domains that were Z-Library mirrors but are now adult sites
static const std::vector<std::string> BLOCKED_DOMAINS = {
    "singlelogin.re",
};

static bool IsZlibHost(const std::string& host) {
    if (host.empty()) return false;
    for (const auto& bd : BLOCKED_DOMAINS) {
        if (host.find(bd) != std::string::npos) return false;
    }
    // Blocklist keywords: known non-ZLibrary domains that might slip through
    if (host.find("porn") != std::string::npos) return false;
    if (host.find("xxx") != std::string::npos) return false;
    if (host.find("sex") != std::string::npos) return false;
    if (host.find("av") == 0 || host.find(".av") != std::string::npos) return false;
    // Allowlist: Z-Library related domain patterns
    if (host.find("z-lib") != std::string::npos) return true;
    if (host.find("z-library") != std::string::npos) return true;
    if (host.find("zlibrary") != std::string::npos) return true;
    if (host.find("zlib") != std::string::npos) return true;
    if (host.find("1lib") != std::string::npos) return true;
    if (host.find("singlelogin") != std::string::npos) return true;
    if (host.find("fbiwarning") != std::string::npos) return true;
    if (host.find("dfj101") != std::string::npos) return true;
    if (host.find("bookfi") != std::string::npos) return true;
    if (host.find("zzz") != std::string::npos) return true;
    if (host.find("jiaoyuan") != std::string::npos) return true;
    return false;
}

static std::string FetchUrl(const std::string& url) {
    size_t se = url.find("://"); if (se == std::string::npos) return "";
    bool https = (url.substr(0, se) == "https"); size_t hs = se + 3;
    size_t ps = url.find('/', hs);
    std::string host, path;
    ps != std::string::npos
        ? (host = url.substr(hs, ps - hs), path = url.substr(ps))
        : (host = url.substr(hs), path = "/");
    auto toW = [](const std::string& s) { return ToWide(s); };
    HINTERNET hS = WinHttpOpen(L"PB/1.9", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY, nullptr, nullptr, 0);
    if (!hS) return "";
    HINTERNET hC = WinHttpConnect(hS, toW(host).c_str(), https ? 443 : 80, 0);
    if (!hC) { WinHttpCloseHandle(hS); return ""; }
    HINTERNET hR = WinHttpOpenRequest(hC, L"GET", toW(path).c_str(), nullptr, nullptr, nullptr, https ? WINHTTP_FLAG_SECURE : 0);
    if (!hR) { WinHttpCloseHandle(hC); WinHttpCloseHandle(hS); return ""; }
    if (!WinHttpSendRequest(hR, nullptr, 0, nullptr, 0, 0, 0) || !WinHttpReceiveResponse(hR, nullptr))
    { WinHttpCloseHandle(hR); WinHttpCloseHandle(hC); WinHttpCloseHandle(hS); return ""; }
    std::string r; DWORD br; char b[8192];
    while (WinHttpReadData(hR, b, sizeof(b), &br) && br > 0) r.append(b, br);
    WinHttpCloseHandle(hR); WinHttpCloseHandle(hC); WinHttpCloseHandle(hS); return r;
}

ZLibraryService::ZLibraryService(BridgeServer* bridge) : m_bridge(bridge), m_mirrors(FALLBACK_MIRRORS) {
}
ZLibraryService::~ZLibraryService() {}

void ZLibraryService::StartMirrorFetch(std::shared_ptr<ZLibraryService> self)
{
    // Fetch mirrors in background without blocking the UI thread. Capturing
    // `self` (not `this`) keeps the service alive until the fetch finishes,
    // so a shutdown that destroys the service mid-fetch can't use-after-free.
    std::thread([self]() { self->FetchMirrors(); }).detach();
}

json ZLibraryService::FetchMirrors() {
    std::string html = FetchUrl("https://zz.ggonav.com/");

    if (html.empty()) {
        std::lock_guard<std::mutex> lk(m_mirrorMutex);
        json r; r["mirrors"] = m_mirrors; r["current"] = m_currentMirror; return r;
    }

    std::vector<std::string> found;

    // Parse href links
    static const std::regex linkRe("href=\"(https?://[^\"]+)\"", std::regex::icase);
    for (auto it = std::sregex_iterator(html.begin(), html.end(), linkRe); it != std::sregex_iterator(); ++it) {
        std::string u = (*it)[1];
        size_t ss = u.find("://");
        size_t hs = ss != std::string::npos ? ss + 3 : 0;
        size_t ps = u.find('/', hs);
        std::string host = ps != std::string::npos ? u.substr(hs, ps - hs) : u.substr(hs);
        if (IsZlibHost(host)) {
            if (u.back() != '/') u += '/';
            if (std::find(found.begin(), found.end(), u) == found.end()) found.push_back(u);
        }
    }

    // Also search for Z-Library URLs in text content (not just href)
    static const std::regex urlRe("(https?://[a-zA-Z0-9.-]+\\.[a-z]{2,}[/])", std::regex::icase);
    for (auto it = std::sregex_iterator(html.begin(), html.end(), urlRe); it != std::sregex_iterator(); ++it) {
        std::string u = (*it)[1];
        size_t ss = u.find("://");
        size_t hs = ss != std::string::npos ? ss + 3 : 0;
        size_t ps = u.find('/', hs);
        std::string host = ps != std::string::npos ? u.substr(hs, ps - hs) : u.substr(hs);
        if (IsZlibHost(host)) {
            if (u.back() != '/') u += '/';
            if (std::find(found.begin(), found.end(), u) == found.end()) found.push_back(u);
        }
    }

    if (!found.empty()) {
        // Start with fallback list, append any new mirrors from parsed list
        std::vector<std::string> merged = FALLBACK_MIRRORS;
        for (const auto& m : found) {
            if (std::find(merged.begin(), merged.end(), m) == merged.end()) {
                merged.push_back(m);
            }
        }
        std::lock_guard<std::mutex> lk(m_mirrorMutex);
        // Preserve current selection if that URL still exists in new list
        std::string oldUrl = m_mirrors[m_currentMirror];
        m_mirrors = merged;
        auto it = std::find(m_mirrors.begin(), m_mirrors.end(), oldUrl);
        m_currentMirror = (it != m_mirrors.end()) ? (int)(it - m_mirrors.begin()) : 0;
    }

    std::lock_guard<std::mutex> lk(m_mirrorMutex);
    json r; r["mirrors"] = m_mirrors; r["current"] = m_currentMirror; return r;
}

json ZLibraryService::GetMirrorInfo() {
    std::lock_guard<std::mutex> lk(m_mirrorMutex);
    json r; r["index"] = m_currentMirror;
    r["url"] = m_currentMirror < (int)m_mirrors.size() ? m_mirrors[m_currentMirror] : "";
    r["mirrors"] = m_mirrors; return r;
}

json ZLibraryService::SwitchMirror(int index) {
    std::string url;
    {
        std::lock_guard<std::mutex> lk(m_mirrorMutex);
        if (index >= 0 && index < (int)m_mirrors.size()) m_currentMirror = index;
        m_navRetryCount = 0;
        url = m_mirrors[m_currentMirror];
    }
    if (m_host && m_host->GetWebView())
        m_host->GetWebView()->Navigate(ToWide(url).c_str());
    m_bridge->EmitEvent("zlib:mirrorChanged", GetMirrorInfo());
    return GetMirrorInfo();
}
// ── Navigate main WebView2 + inject floating toolbar ───────────────────────

json ZLibraryService::Show() {
    m_zlibActive = true;
    m_zlibDlInProgress = false;
    m_pendingDownloadUri.clear();
    SetupDownloadHandler();

    // Load saved download path from database settings
    if (m_db && m_downloadPath.empty()) {
        json settings = m_db->GetSettings();
        if (!settings.is_null() && settings.contains("zlibDownloadPath")) {
            m_downloadPath = settings["zlibDownloadPath"].get<std::string>();
        }
    }

    auto* wv = m_host ? m_host->GetWebView() : nullptr;
    std::string url;
    {
        std::lock_guard<std::mutex> lk(m_mirrorMutex);
        m_navRetryCount = 0;
        m_retryMirrorCount = (int)m_mirrors.size();
        url = m_mirrors[m_currentMirror];
    }
    if (!wv) {
        ShellExecuteW(nullptr, L"open", ToWide(url).c_str(),
                      nullptr, nullptr, SW_SHOWNORMAL);
        return json(nullptr);
    }

    wv->Navigate(ToWide(url).c_str());
    m_bridge->EmitEvent("zlib:mirrorChanged", GetMirrorInfo());
    return json(nullptr);
}

json ZLibraryService::Hide() {
    m_zlibActive = false;
    if (m_host) m_host->ReloadPage();
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
    m_zlibActive = false;
    if (m_host && m_host->GetWebView()) {
        std::string url;
        { std::lock_guard<std::mutex> lk(m_mirrorMutex); url = m_mirrors[m_currentMirror]; }
        m_host->GetWebView()->Navigate(ToWide(url).c_str());
    }
    return json(nullptr);
}

std::string ZLibraryService::GetDownloadPath() const
{
    if (!m_downloadPath.empty()) {
        std::string path = m_downloadPath;
        for (auto& c : path) if (c == '/') c = '\\';
        std::error_code ec;
        std::filesystem::create_directories(path, ec);
        return path;
    }
    wchar_t profile[MAX_PATH];
    if (SUCCEEDED(SHGetFolderPathW(nullptr, CSIDL_PROFILE, nullptr, 0, profile))) {
        char buf[MAX_PATH * 3];
        WideCharToMultiByte(CP_UTF8, 0, profile, -1, buf, sizeof(buf), nullptr, nullptr);
        std::string path = std::string(buf) + "\\Downloads\\ParticleBook";
        for (auto& c : path) if (c == '/') c = '\\';
        std::error_code ec;
        std::filesystem::create_directories(path, ec);
        return path;
    }
    return App::Instance().UserDataPath() + "/downloads";
}

json ZLibraryService::SetDownloadPath(const std::string& path)
{
    m_downloadPath = path;
    for (auto& c : m_downloadPath) if (c == '/') c = '\\';
    // Persist to database settings
    if (m_db) {
        json settings = m_db->GetSettings();
        if (settings.is_null()) settings = json::object();
        settings["zlibDownloadPath"] = m_downloadPath;
        m_db->UpdateSettings(settings);
    }
    return json({{"path", m_downloadPath}});
}

json ZLibraryService::GetDownloadPathStr() const
{
    std::string path = m_downloadPath;
    // If no custom path set, return default
    if (path.empty()) {
        wchar_t profile[MAX_PATH];
        if (SUCCEEDED(SHGetFolderPathW(nullptr, CSIDL_PROFILE, nullptr, 0, profile))) {
            char buf[MAX_PATH * 3];
            WideCharToMultiByte(CP_UTF8, 0, profile, -1, buf, sizeof(buf), nullptr, nullptr);
            path = std::string(buf) + "\\Downloads\\ParticleBook";
        }
    }
    for (auto& c : path) if (c == '\\') c = '/';
    return json({{"path", path}});
}

json ZLibraryService::PickDownloadFolder()
{
    std::string result;

    IFileOpenDialog* pDialog = nullptr;
    HRESULT hr = CoCreateInstance(CLSID_FileOpenDialog, nullptr, CLSCTX_ALL,
                                  IID_PPV_ARGS(&pDialog));
    if (SUCCEEDED(hr) && pDialog) {
        DWORD flags;
        pDialog->GetOptions(&flags);
        pDialog->SetOptions(flags | FOS_PICKFOLDERS | FOS_PATHMUSTEXIST);

        hr = pDialog->Show(nullptr);
        if (SUCCEEDED(hr)) {
            IShellItem* pItem = nullptr;
            hr = pDialog->GetResult(&pItem);
            if (SUCCEEDED(hr) && pItem) {
                LPWSTR pwPath = nullptr;
                hr = pItem->GetDisplayName(SIGDN_FILESYSPATH, &pwPath);
                if (SUCCEEDED(hr) && pwPath) {
                    result = ToNarrow(pwPath);
                    CoTaskMemFree(pwPath);
                }
                pItem->Release();
            }
        }
        pDialog->Release();
    }

    if (!result.empty()) {
        SetDownloadPath(result);
        return json({{"path", result}});
    }
    return json({{"path", m_downloadPath}});
}

void ZLibraryService::SetupDownloadHandler()
{
    if (m_downloadRegistered || !m_host || !m_host->GetWebView()) return;
    m_downloadRegistered = true;
    m_hwnd = m_host->GetHwnd();

    m_host->SetDownloadCallback([this](const std::string& path, const std::string& fileName) {
        OnDownloadDone(path, fileName);
    });
    m_host->SetDownloadProgressCallback([this](const std::string& fileName, int64_t recv, int64_t tot) {
        m_bridge->EmitEvent("zlib:downloadProgress", {{"fileName", fileName},{"received", recv},{"total", tot}});
    });
    m_host->SetImportCallback([this](const std::string& path, const std::string& fileName) {
        DoImport(path, fileName);
    });
    m_host->SetDownloadFailCallback([this](const std::string& fileName, const std::string& reason) {
        m_zlibDlInProgress = false;
        m_bridge->EmitEvent("zlib:downloadError", {{"fileName", fileName}, {"error", reason}});
    });

    auto* wv = m_host->GetWebView();

    // ── NewWindowRequested — redirect popup to main window for DownloadStarting interception ──
    ComPtr<ICoreWebView2_2> wv2nw;
    if (SUCCEEDED(wv->QueryInterface(IID_PPV_ARGS(&wv2nw)))) {
        wv2nw->add_NewWindowRequested(
            Callback<ICoreWebView2NewWindowRequestedEventHandler>(
                [this](ICoreWebView2*, ICoreWebView2NewWindowRequestedEventArgs* args) -> HRESULT {
                    if (!m_zlibActive) return S_OK;
                    if (m_zlibDlInProgress) { args->put_Handled(TRUE); return S_OK; }
                    LPWSTR uriRaw = nullptr;
                    if (FAILED(args->get_Uri(&uriRaw)) || !uriRaw) return S_OK;
                    args->put_Handled(TRUE); // cancel popup
                    // Store pending URL for one-shot pass through NavigationStarting
                    m_pendingDownloadUri = ToNarrow(uriRaw);
                    // Navigate main window — DownloadStarting will intercept
                    if (m_host && m_host->GetWebView())
                        m_host->GetWebView()->Navigate(uriRaw);
                    CoTaskMemFree(uriRaw);
                    return S_OK;
                }).Get(), nullptr);
    }

    // ── NavigationStarting — during a Z-Library session, follow all redirects ──
    // (mirrors redirect to unlabeled transit domains like msn101.ru; allow them.
    //  Only block dangerous schemes (file:, javascript:, etc.) to prevent injection.)
    ComPtr<ICoreWebView2_2> wv2nav;
    if (SUCCEEDED(wv->QueryInterface(IID_PPV_ARGS(&wv2nav)))) {
        wv2nav->add_NavigationStarting(
            Callback<ICoreWebView2NavigationStartingEventHandler>(
                [this](ICoreWebView2*, ICoreWebView2NavigationStartingEventArgs* args) -> HRESULT {
                    if (!m_zlibActive) return S_OK;
                    LPWSTR uriRaw = nullptr;
                    if (FAILED(args->get_Uri(&uriRaw)) || !uriRaw) return S_OK;
                    std::string uri = ToNarrow(uriRaw);
                    CoTaskMemFree(uriRaw);

                    // One-shot pass for pending download URL from NewWindowRequested
                    if (!m_pendingDownloadUri.empty()) {
                        if (uri == m_pendingDownloadUri) {
                            m_pendingDownloadUri.clear();
                            return S_OK;
                        }
                    }

                    // Allow http/https (including mirror redirects to transit domains).
                    // Block everything else (file:, javascript:, data:, ...) as a safety net.
                    bool safe = (uri.rfind("http://", 0) == 0) || (uri.rfind("https://", 0) == 0);
                    if (!safe) {
                        args->put_Cancel(TRUE);
                    }
                    return S_OK;
                }).Get(), &m_navToken);
    }

    // ── NavigationCompleted — auto-retry on failure, stop after one full cycle ──
    {
        ComPtr<ICoreWebView2_2> wv2comp;
        if (SUCCEEDED(wv->QueryInterface(IID_PPV_ARGS(&wv2comp)))) {
            wv2comp->add_NavigationCompleted(
                Callback<ICoreWebView2NavigationCompletedEventHandler>(
                    [this](ICoreWebView2*, ICoreWebView2NavigationCompletedEventArgs* args) -> HRESULT {
                        if (!m_zlibActive) return S_OK;
                        BOOL success = TRUE;
                        args->get_IsSuccess(&success);


                        if (!success) {
                            std::string nextUrl;
                            bool retry = false;
                            {
                                std::lock_guard<std::mutex> lk(m_mirrorMutex);
                                if (m_navRetryCount < m_retryMirrorCount && m_navRetryCount < (int)m_mirrors.size()) {
                                    m_navRetryCount++;
                                    m_currentMirror = (m_currentMirror + 1) % (int)m_mirrors.size();
                                    nextUrl = m_mirrors[m_currentMirror];
                                    retry = true;
                                }
                            }
                            if (retry) {
                                auto* wvSelf = m_host ? m_host->GetWebView() : nullptr;
                                if (wvSelf) {
                                    wvSelf->Navigate(ToWide(nextUrl).c_str());
                                    m_bridge->EmitEvent("zlib:mirrorChanged", GetMirrorInfo());
                                }
                            } else {
                                // All mirrors exhausted — notify frontend
                                m_bridge->EmitEvent("zlib:allMirrorsFailed", json::object());
                            }
                            return S_OK;
                        }
                        {
                            std::lock_guard<std::mutex> lk(m_mirrorMutex);
                            m_navRetryCount = 0;
                        }
                        return S_OK;
                    }).Get(), nullptr);
        }
    }

    // ── DownloadStarting — intercept download, use WinHTTP ──
    ComPtr<ICoreWebView2_4> wv4;
    if (SUCCEEDED(wv->QueryInterface(IID_PPV_ARGS(&wv4)))) {
        wv4->add_DownloadStarting(
            Callback<ICoreWebView2DownloadStartingEventHandler>(
                [this](ICoreWebView2*, ICoreWebView2DownloadStartingEventArgs* args) -> HRESULT {
                    if (!m_zlibActive) return S_OK;
                    if (m_zlibDlInProgress) { args->put_Handled(TRUE); return S_OK; }
                    m_zlibDlInProgress = true;
                    m_pendingDownloadUri.clear();

                    ComPtr<ICoreWebView2DownloadOperation> op;
                    if (FAILED(args->get_DownloadOperation(&op))) { m_zlibDlInProgress = false; return S_OK; }
                    LPWSTR uriRaw = nullptr;
                    std::string uri;
                    if (SUCCEEDED(op->get_Uri(&uriRaw)) && uriRaw) { uri = ToNarrow(uriRaw); CoTaskMemFree(uriRaw); }

                    std::string fileName = "download";
                    size_t fnp = uri.find("filename=");
                    if (fnp != std::string::npos) {
                        std::string fn = uri.substr(fnp + 9);
                        size_t amp = fn.find('&');
                        if (amp != std::string::npos) fn = fn.substr(0, amp);
                        std::string dec;
                        for (size_t i = 0; i < fn.size(); ++i) {
                            if (fn[i] == '%' && i + 2 < fn.size()) {
                                char hex[3] = {fn[i+1], fn[i+2], 0};
                                dec += (char)strtol(hex, nullptr, 16);
                                i += 2;
                            } else if (fn[i] == '+') dec += ' ';
                            else dec += fn[i];
                        }
                        if (!dec.empty()) fileName = dec;
                    }
                    if (fileName.size() > 150) {
                        size_t maxLen = 140;
                        // Back up to UTF-8 boundary
                        while (maxLen > 0 && (fileName[maxLen] & 0xC0) == 0x80) maxLen--;
                        size_t dot = fileName.rfind('.');
                        if (dot != std::string::npos && dot > 120 && dot < 250)
                            fileName = fileName.substr(0, maxLen) + fileName.substr(dot);
                        else
                            fileName = fileName.substr(0, maxLen);
                    }

                    args->put_Handled(TRUE);

                    // Get cookies, then download via WinHTTP
                    ComPtr<ICoreWebView2_2> wv2;
                    if (m_host && m_host->GetWebView() && SUCCEEDED(m_host->GetWebView()->QueryInterface(IID_PPV_ARGS(&wv2)))) {
                        ComPtr<ICoreWebView2CookieManager> cm;
                        if (SUCCEEDED(wv2->get_CookieManager(&cm))) {
                            cm->GetCookies(ToWide(uri).c_str(),
                                Callback<ICoreWebView2GetCookiesCompletedHandler>(
                                    [this, uri, fileName](HRESULT, ICoreWebView2CookieList* cl) -> HRESULT {
                                        std::string ck;
                                        if (cl) {
                                            UINT n = 0; cl->get_Count(&n);
                                            for (UINT i = 0; i < n; i++) {
                                                ComPtr<ICoreWebView2Cookie> c;
                                                if (SUCCEEDED(cl->GetValueAtIndex(i, &c)) && c) {
                                                    LPWSTR nm = nullptr, vl = nullptr;
                                                    c->get_Name(&nm); c->get_Value(&vl);
                                                    if (nm && vl) {
                                                        if (!ck.empty()) ck += "; ";
                                                        ck += ToNarrow(nm) + "=" + ToNarrow(vl);
                                                    }
                                                    if (nm) CoTaskMemFree(nm);
                                                    if (vl) CoTaskMemFree(vl);
                                                }
                                            }
                                        }
                                        StartDownloadThread(uri, fileName, ck);
                                        return S_OK;
                                    }).Get());
                            return S_OK;
                        }
                    }
                    StartDownloadThread(uri, fileName, "");
                    return S_OK;
                }).Get(), &m_downloadToken);
    }
}

void ZLibraryService::StartDownloadThread(const std::string& startUrl, const std::string& fileName, const std::string& cookies)
{
    // Sanitize the server-provided file name: strip separators / reserved chars
    // and any ".." segments so a hostile filename= can't write outside the
    // download directory (path traversal).
    std::string fn;
    for (char c : fileName.empty() ? std::string("download") : fileName) {
        if (c == '\\' || c == '/' || c == ':' || c == '*' || c == '?' ||
            c == '"' || c == '<' || c == '>' || c == '|') continue;
        fn += c;
    }
    if (fn.empty()) fn = "download";
    size_t pos;
    while ((pos = fn.find("..")) != std::string::npos) fn.erase(pos, 2);

    std::string downloadPath = GetDownloadPath() + "\\" + fn;
    std::filesystem::create_directories(GetDownloadPath(), std::error_code{});

    m_bridge->EmitEvent("zlib:downloadStart", {{"fileName", fn}});

    HWND hwnd = m_hwnd;
    std::thread([startUrl, fn, downloadPath, cookies, hwnd]() {
        auto fail = [&](const std::string& reason) {
            if (hwnd) {
                auto* d = new DLFail{fn, reason};
                PostMessage(hwnd, WM_ZLIB_DOWNLOAD_FAILED, 0, reinterpret_cast<LPARAM>(d));
            }
        };

        std::string url = startUrl;
        for (int redir = 0; redir < 5; redir++) {
            size_t se = url.find("://");
            if (se == std::string::npos) { fail("invalid_url"); return; }
            bool https = (url.substr(0, se) == "https");
            size_t hs = se + 3;
            size_t ps = url.find('/', hs);
            std::string host, path;
            if (ps != std::string::npos) { host = url.substr(hs, ps - hs); path = url.substr(ps); }
            else { host = url.substr(hs); path = "/"; }

            HINTERNET hS = WinHttpOpen(L"PB/1.9", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY, nullptr, nullptr, 0);
            if (!hS) { fail("http_open_failed"); return; }
            HINTERNET hC = WinHttpConnect(hS, ToWide(host).c_str(), https ? 443 : 0, 0);
            if (!hC) { WinHttpCloseHandle(hS); fail("connect_failed"); return; }
            HINTERNET hR = WinHttpOpenRequest(hC, L"GET", ToWide(path).c_str(), nullptr, nullptr, nullptr,
                https ? WINHTTP_FLAG_SECURE : 0);
            if (!hR) { WinHttpCloseHandle(hC); WinHttpCloseHandle(hS); fail("request_failed"); return; }

            if (!cookies.empty()) {
                std::wstring ch = L"Cookie: " + ToWide(cookies);
                WinHttpAddRequestHeaders(hR, ch.c_str(), (DWORD)-1, WINHTTP_ADDREQ_FLAG_ADD);
            }
            WinHttpAddRequestHeaders(hR, L"User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36", (DWORD)-1, WINHTTP_ADDREQ_FLAG_ADD);
            WinHttpAddRequestHeaders(hR, L"Accept: */*", (DWORD)-1, WINHTTP_ADDREQ_FLAG_ADD);
            WinHttpAddRequestHeaders(hR, L"Accept-Language: zh-CN,zh;q=0.9,en;q=0.8", (DWORD)-1, WINHTTP_ADDREQ_FLAG_ADD);
            {
                std::string referer = url.substr(0, url.find('/', url.find("://") + 3));
                WinHttpAddRequestHeaders(hR, (L"Referer: " + ToWide(referer)).c_str(), (DWORD)-1, WINHTTP_ADDREQ_FLAG_ADD);
            }

            if (!WinHttpSendRequest(hR, nullptr, 0, nullptr, 0, 0, 0) || !WinHttpReceiveResponse(hR, nullptr)) {
                WinHttpCloseHandle(hR); WinHttpCloseHandle(hC); WinHttpCloseHandle(hS);
                fail("network_error");
                return;
            }

            DWORD sc = 0, sz = sizeof(sc);
            WinHttpQueryHeaders(hR, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER, nullptr, &sc, &sz, nullptr);

            if (sc == 301 || sc == 302) {
                WCHAR loc[2048] = {}; DWORD ls = sizeof(loc);
                if (WinHttpQueryHeaders(hR, WINHTTP_QUERY_LOCATION, nullptr, loc, &ls, nullptr)) {
                    url = ToNarrow(loc);
                    WinHttpCloseHandle(hR); WinHttpCloseHandle(hC); WinHttpCloseHandle(hS);
                    continue;
                }
            }

            if (sc >= 400) {
                WinHttpCloseHandle(hR); WinHttpCloseHandle(hC); WinHttpCloseHandle(hS);
                fail("http_" + std::to_string(sc));
                return;
            }

            DWORD cl = 0; sz = sizeof(cl);
            WinHttpQueryHeaders(hR, WINHTTP_QUERY_CONTENT_LENGTH | WINHTTP_QUERY_FLAG_NUMBER, nullptr, &cl, &sz, nullptr);
            int64_t total = cl;

            HANDLE hFile = CreateFileW(ToWide(downloadPath).c_str(), GENERIC_WRITE, 0, nullptr,
                CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
            if (hFile == INVALID_HANDLE_VALUE) {
                WinHttpCloseHandle(hR); WinHttpCloseHandle(hC); WinHttpCloseHandle(hS);
                fail("file_create_failed");
                return;
            }

            char buf[65536]; DWORD br; int64_t tr = 0, lp = 0;
            while (WinHttpReadData(hR, buf, sizeof(buf), &br) && br > 0) {
                DWORD wr; WriteFile(hFile, buf, br, &wr, nullptr);
                tr += br;
                if (hwnd && tr - lp >= 262144) {
                    auto* pd = new DLProgress{fn, tr, total};
                    PostMessage(hwnd, WM_ZLIB_DOWNLOAD_PROGRESS, 0, reinterpret_cast<LPARAM>(pd));
                    lp = tr;
                }
            }
            if (hwnd && tr > lp) {
                auto* pd = new DLProgress{fn, tr, total};
                PostMessage(hwnd, WM_ZLIB_DOWNLOAD_PROGRESS, 0, reinterpret_cast<LPARAM>(pd));
            }
            CloseHandle(hFile);
            WinHttpCloseHandle(hR); WinHttpCloseHandle(hC); WinHttpCloseHandle(hS);

            if (tr > 0 && hwnd) {
                auto* d = new std::pair<std::string, std::string>(downloadPath, fn);
                PostMessage(hwnd, WM_ZLIB_DOWNLOAD_DONE, 1, reinterpret_cast<LPARAM>(d));
            } else {
                fail("empty_response");
            }
            return;
        }
        fail("too_many_redirects");
    }).detach();
}

void ZLibraryService::OnDownloadDone(const std::string& downloadPath, const std::string& fileName)
{
    m_bridge->EmitEvent("zlib:downloadComplete", {{"fileName", fileName}, {"path", downloadPath}});

    if (m_hwnd) {
        auto* d = new std::pair<std::string, std::string>(downloadPath, fileName);
        PostMessage(m_hwnd, WM_ZLIB_DO_IMPORT, 0, reinterpret_cast<LPARAM>(d));
    }
}

void ZLibraryService::DoImport(const std::string& downloadPath, const std::string& fileName)
{
    m_zlibDlInProgress = false;
    m_bridge->EmitEvent("zlib:importStart", {{"fileName", fileName}});

    bool success = false;
    std::string errMsg;
    try {
        json params;
        params["paths"] = json::array({downloadPath});
        auto result = m_bridge->InvokeMethod("book:import", params);
        success = result.is_array() && result.size() > 0;
        if (!success) errMsg = "import_failed";
    } catch (const std::exception& e) {
        errMsg = std::string("import_exception:") + e.what();
    }

    if (success) {
        m_bridge->EmitEvent("zlib:importComplete", {{"fileName", fileName}});
        m_bridge->EmitEvent("menu:importBooks", json::object());
    } else {
        m_bridge->EmitEvent("zlib:importError", {{"fileName", fileName}, {"error", errMsg}});
    }
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
