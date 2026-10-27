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
    if (!m_host || !m_host->GetWebView()) return;
    // "等探测结果再进站"的回投通道：每次 Show 都要确保挂着（不能放进下面那个
    // 一次性注册的守卫里 —— 它只注册一回）
    m_host->SetZlibEntryNavCallback([this]() {
        if (!m_entryNavPending.exchange(false)) return;   // 已被取消/已处理
        if (m_zlibActive) NavigateToCurrentMirror();
    });
    if (m_downloadRegistered) return;
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
    m_host->SetImportResultCallback([this](const std::string& fileName, bool success, const std::string& error) {
        OnImportDone(fileName, success, error);
    });
    m_host->SetDownloadFailCallback([this](const std::string& fileName, const std::string& reason) {
        m_zlibDlInProgress = false;
        pb::LogError("zlib", "download failed: " + fileName + " (" + reason + ")");
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
                    // 这里 Handled(TRUE) 即取消弹窗（NewWindowRequestedEventArgs 没有
                    // Cancel），所以被吞掉的那次点击不会偷偷下载。
                    if (m_zlibDlInProgress) { args->put_Handled(TRUE); return S_OK; }
                    LPWSTR uriRaw = nullptr;
                    if (FAILED(args->get_Uri(&uriRaw)) || !uriRaw) return S_OK;
                    args->put_Handled(TRUE); // cancel popup
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

                    // 这里原本有一个"一次性放行"：NewWindowRequested 会把待下载 URL 记下来，
                    // 命中就 return S_OK。但它排在下面这行协议检查【之前】，而该 URL 完全由
                    // 页面决定（抄自 window.open），于是 blob:/about:blank 之类可以借此绕过
                    // 拦截。而 http/https 本来就被允许，这个机制如今只剩绕过作用，故删除。
                    // （若将来恢复"仅限镜像域"的白名单，下载 URL 的放行必须重新加回，
                    //  且必须放在协议检查之后。）
                    //
                    // 记下主文档导航的 URI：WebResourceResponseReceived 靠它把主文档的
                    // 响应从一堆子资源里挑出来（该事件对每个 web 资源都会触发）
                    m_docUri = uri;
                    m_lastDocStatus = 0;

                    // Allow http/https (including mirror redirects to transit domains).
                    // Block everything else (file:, javascript:, data:, ...) as a safety net.
                    bool safe = (uri.rfind("http://", 0) == 0) || (uri.rfind("https://", 0) == 0);
                    if (!safe) {
                        m_navCancelledByGuard = true;   // 见头部说明：这不是镜像失败
                        args->put_Cancel(TRUE);
                    }
                    return S_OK;
                }).Get(), &m_navToken);
    }

    // ── WebResourceResponseReceived — 记下主文档的 HTTP 状态码 ──
    // NavigationCompleted 报的"成功"只表示拿到了一个文档：503/404 同样算成功。这里把
    // 【主文档】的响应状态存下来，供 ClassifyLoadedDocument 判断线路是不是真的坏了。
    // 主文档的识别：请求 URI 与 NavigationStarting 记下的 URI 相同（子资源不参与）。
    {
        ComPtr<ICoreWebView2_2> wv2resp;
        if (SUCCEEDED(wv->QueryInterface(IID_PPV_ARGS(&wv2resp)))) {
            wv2resp->add_WebResourceResponseReceived(
                Callback<ICoreWebView2WebResourceResponseReceivedEventHandler>(
                    [this](ICoreWebView2*, ICoreWebView2WebResourceResponseReceivedEventArgs* args) -> HRESULT {
                        if (!m_zlibActive || m_docUri.empty()) return S_OK;
                        ComPtr<ICoreWebView2WebResourceRequest> req;
                        if (FAILED(args->get_Request(&req)) || !req) return S_OK;
                        LPWSTR u = nullptr;
                        if (FAILED(req->get_Uri(&u)) || !u) return S_OK;
                        bool isDoc = (TrimAscii(ToNarrow(u)) == m_docUri);
                        CoTaskMemFree(u);
                        if (!isDoc) return S_OK;
                        ComPtr<ICoreWebView2WebResourceResponseView> resp;
                        if (FAILED(args->get_Response(&resp)) || !resp) return S_OK;
                        int status = 0;
                        if (SUCCEEDED(resp->get_StatusCode(&status))) m_lastDocStatus = status;
                        return S_OK;
                    }).Get(), nullptr);
        }
    }

    // ── NavigationCompleted — 判定这次导航的结果，坏页/失败自动换线路 ──
    {
        ComPtr<ICoreWebView2_2> wv2comp;
        if (SUCCEEDED(wv->QueryInterface(IID_PPV_ARGS(&wv2comp)))) {
            wv2comp->add_NavigationCompleted(
                Callback<ICoreWebView2NavigationCompletedEventHandler>(
                    [this](ICoreWebView2*, ICoreWebView2NavigationCompletedEventArgs* args) -> HRESULT {
                        if (!m_zlibActive) return S_OK;
                        BOOL success = TRUE;
                        args->get_IsSuccess(&success);


                        // 被我们自己的协议守卫取消的导航也会报 IsSuccess=FALSE，
                        // 但那不是镜像故障 —— 此前它会照样消耗一次换线路的重试额度，
                        // 几个危险协议请求就能把额度用光并误报"所有线路都连不上"。
                        if (m_navCancelledByGuard) {
                            m_navCancelledByGuard = false;
                            return S_OK;
                        }

                        if (!success) {
                            // "已经收到 HTTP 错误响应之后才失败"不算线路故障：站点自己的 JS 挑战页
                            // 过完挑战会 reload，那次 reload 会把当前导航打断，WebView2 就报
                            // IsSuccess=FALSE（而主文档状态码已经有了 503）。此前一律当线路失败
                            // → 立刻换线路、把挑战打断，实测要重试同一个地址两三次才进得去
                            // （进站 19 秒；让路的话 4~5 秒）。让路次数有上限，避免真卡住时干等。
                            if (m_lastDocStatus >= 400 && m_challengeWaits < 3) {
                                m_challengeWaits++;

                                return S_OK;
                            }
                            // 真正的网络层失败（没拿到任何响应）：换下一条线路
                            AdvanceMirrorOnFailure();
                            return S_OK;
                        }

                        // "成功"不等于"进去了"：503 的 JS 挑战页、404 错误页都是成功导航。
                        // 标题/状态码的判定要读 document.title，是异步的，见该方法。
                        ClassifyLoadedDocument();
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
                    if (m_zlibDlInProgress) {
                        // 看门狗：超过 10 分钟仍未归，说明上一个下载线程没跑完（旧路径里
                        // DNS 可无限等待）。继续早退会让用户点下载一直"零反应"。
                        if (std::chrono::steady_clock::now() - m_dlStartedAt > std::chrono::minutes(10)) {
                            m_zlibDlInProgress = false;
                        }
                    }
                    if (m_zlibDlInProgress) { args->put_Handled(TRUE); args->put_Cancel(TRUE); return S_OK; }
                    m_zlibDlInProgress = true;
                    m_dlStartedAt = std::chrono::steady_clock::now();
                    // （m_pendingDownloadUri 已随"一次性放行"一并移除）

                    ComPtr<ICoreWebView2DownloadOperation> op;
                    if (FAILED(args->get_DownloadOperation(&op))) { m_zlibDlInProgress = false; return S_OK; }
                    LPWSTR uriRaw = nullptr;
                    std::string uri;
                    if (SUCCEEDED(op->get_Uri(&uriRaw)) && uriRaw) { uri = ToNarrow(uriRaw); CoTaskMemFree(uriRaw); }

                    // 文件名优先取 WebView2 给出的建议落盘名：它已按服务端
                    // Content-Disposition 定名并做过净化。此前只认 URL 里的 filename=
                    // 参数，取不到就落成无扩展名的 "download" → 导入时格式判空 →
                    // 必然失败（相对已删除的 Electron 版是回归）。
                    std::string fileName;
                    {
                        LPWSTR rpRaw = nullptr;
                        if (SUCCEEDED(op->get_ResultFilePath(&rpRaw)) && rpRaw) {
                            std::string full = ToNarrow(rpRaw);
                            CoTaskMemFree(rpRaw);
                            size_t sl = full.find_last_of("\\/");
                            std::string base = (sl == std::string::npos) ? full : full.substr(sl + 1);
                            if (!base.empty() && base.find('.') != std::string::npos) fileName = base;
                        }
                    }
                    if (fileName.empty()) fileName = "download";
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
                        // 只有在上面没拿到可用名时才用 URL 参数（它常常没有扩展名）
                        if (!dec.empty() && fileName == "download") fileName = dec;
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
                    // 必须同时 Cancel：Handled 只表示「不显示默认 UI」，下载仍会照常进行
                    // （WebView2 文档原话），结果每本书被完整下载两遍、用户 Downloads 里
                    // 还会多出一份未管理的重复文件。我们的 WinHTTP 接管此时已拿到所需
                    // 信息（URI/文件名/Cookie 随后单独取），可以安全取消 WebView2 那份。
                    args->put_Cancel(TRUE);

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
            // PB-016-B：失败原因（错误码）与文件名进本地诊断日志；URL 不在这里写，
            // 下载链接通常带一次性 token、且对排障帮助有限。
            pb::LogError("zlib", "download failed: " + reason + " (file=" + fn + ")");
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
            // 必须显式设超时：WinHTTP 的 resolve 超时默认是【0 = 无限等待】，
            // DNS 无响应时这个下载线程会永远不返回 —— 下载卡片永远停在「准备下载…」，
            // 而且标志位 m_zlibDlInProgress 卡在 true，此后所有下载都会被静默吞掉。
            WinHttpSetTimeouts(hS, 10000, 10000, 10000, 30000);
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

            // 走到这里还看到 3xx，说明这个重定向没能被跟随（上面只处理了 301/302）；
            // 绝不能再把跳转页的正文当成书收下。
            if (sc >= 300) {
                WinHttpCloseHandle(hR); WinHttpCloseHandle(hC); WinHttpCloseHandle(hS);
                fail("http_" + std::to_string(sc));
                return;
            }

            DWORD cl = 0; sz = sizeof(cl);
            WinHttpQueryHeaders(hR, WINHTTP_QUERY_CONTENT_LENGTH | WINHTTP_QUERY_FLAG_NUMBER, nullptr, &cl, &sz, nullptr);
            int64_t total = cl;

            // 先写 .part，全部校验通过后再改名到最终路径：此前直接以最终名
            // CREATE_ALWAYS 落盘，重下同名书会先把旧文件截断，一旦这次失败，
            // 原来那本好书也跟着毁了。
            const std::string tmpPath = downloadPath + ".part";
            HANDLE hFile = CreateFileW(ToWide(tmpPath).c_str(), GENERIC_WRITE, 0, nullptr,
                CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
            if (hFile == INVALID_HANDLE_VALUE) {
                WinHttpCloseHandle(hR); WinHttpCloseHandle(hC); WinHttpCloseHandle(hS);
                fail("file_create_failed");
                return;
            }

            char buf[65536]; int64_t tr = 0, lp = 0;
            char head[64] = {}; size_t headLen = 0;
            bool readFailed = false, writeFailed = false;
            for (;;) {
                DWORD br = 0;
                // 读失败与"正常读完"在旧写法里无法区分（都是循环结束），必须分开判
                if (!WinHttpReadData(hR, buf, sizeof(buf), &br)) { readFailed = true; break; }
                if (br == 0) break;
                if (headLen < sizeof(head)) {
                    size_t take = (sizeof(head) - headLen < br) ? (sizeof(head) - headLen) : (size_t)br;
                    std::memcpy(head + headLen, buf, take);
                    headLen += take;
                }
                DWORD wr = 0;
                if (!WriteFile(hFile, buf, br, &wr, nullptr) || wr != br) { writeFailed = true; break; }
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

            // 四道校验，任何一道不过都删掉半截文件并报错 —— 此前只看 tr > 0，
            // 于是"截断的文件""3xx 跳转页""会话过期的登录 HTML"都会被当成功导入，
            // 用户看到绿勾却得到一本打不开的书。
            if (readFailed || writeFailed) {
                RemovePartialFile(tmpPath);
                fail(writeFailed ? "file_write_failed" : "network_error");
                return;
            }
            if (tr <= 0) { RemovePartialFile(tmpPath); fail("empty_response"); return; }
            if (total > 0 && tr < total) {
                RemovePartialFile(tmpPath);
                fail("incomplete_download");
                return;
            }
            if (LooksLikeHtml(head, headLen)) {
                RemovePartialFile(tmpPath);
                fail("not_a_book");
                return;
            }

            // 全部校验通过 —— 这才动最终文件
            if (!MoveFileExW(ToWide(tmpPath).c_str(), ToWide(downloadPath).c_str(),
                             MOVEFILE_REPLACE_EXISTING)) {
                RemovePartialFile(tmpPath);
                fail("file_write_failed");
                return;
            }

            if (hwnd) {
                auto* d = new std::pair<std::string, std::string>(downloadPath, fn);
                PostMessage(hwnd, WM_ZLIB_DOWNLOAD_DONE, 1, reinterpret_cast<LPARAM>(d));
            } else {
                RemovePartialFile(downloadPath);
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

    // 导入会在 C++ 侧同步跑 mutool 抽元数据/封面（LibraryService 里
    // WaitForSingleObject 上限 30 秒），而这条路径此前跑在窗口过程里 —— 导入一本
    // PDF 就足以让窗口"未响应"。这里把耗时部分挪到工作线程，结果再用 PostMessage
    // 回投到 UI 线程发事件（WebView2 的方法只能在 UI 线程调用）。
    // 不捕获 this：线程可能比服务活得更久，与下载线程保持一致的做法。
    BridgeServer* bridge = m_bridge;
    HWND hwnd = m_hwnd;
    std::thread([bridge, hwnd, downloadPath, fileName]() {
        bool success = false;
        std::string errMsg;
        try {
            json params;
            params["paths"] = json::array({downloadPath});
            auto result = bridge->InvokeMethod("book:import", params);
            success = result.is_array() && result.size() > 0;
            if (!success) errMsg = "import_failed";
        } catch (const std::exception& e) {
            errMsg = std::string("import_exception:") + e.what();
            pb::LogError("zlib", "import exception for " + fileName + ": " + e.what());
        }
        if (hwnd) {
            auto* r = new ImportResult{fileName, success, errMsg};
            PostMessage(hwnd, WM_ZLIB_IMPORT_DONE, 0, reinterpret_cast<LPARAM>(r));
        }
    }).detach();
}

// 在 UI 线程上收尾（由 WebViewHost 的窗口过程回调）
void ZLibraryService::OnImportDone(const std::string& fileName, bool success, const std::string& errMsg)
{
    if (success) {
        pb::LogInfo("zlib", "imported " + fileName);
        m_bridge->EmitEvent("zlib:importComplete", {{"fileName", fileName}});
        // 通知渲染层刷新书架（下载完成的书要出现在书架上）
        m_bridge->EmitEvent("library:changed", json::object());
    } else {
        pb::LogError("zlib", "import failed: " + fileName + " (" + errMsg + ")");
        m_bridge->EmitEvent("zlib:importError", {{"fileName", fileName}, {"error", errMsg}});
    }
}

