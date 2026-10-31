#include "ZLibraryService.h"
#include "BridgeServer.h"
#include "WebViewHost.h"
#include "services/DatabaseService.h"
#include "services/ZLibraryMirror.h"
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

    m_host->SetImportCallback([this](const std::string& path, const std::string& fileName) {
        DoImport(path, fileName);
    });
    m_host->SetImportResultCallback([this](const std::string& fileName, bool success, const std::string& error) {
        OnImportDone(fileName, success, error);
    });
    m_host->SetDownloadFailCallback([this](const std::string& fileName, const std::string& reason) {
        // 看门狗超时：只把挂住的下载掐掉，收尾交给 StateChanged(INTERRUPTED) —
        // 由它删半截 .part 并报 network_error；这里再报一次会连弹两个错误。
        static const std::string kStallTag = "network_stalled#";
        if (reason.compare(0, kStallTag.size(), kStallTag) == 0) {
            unsigned long long gen = 0;
            try { gen = std::stoull(reason.substr(kStallTag.size())); } catch (...) { gen = 0; }
            // 世代不符 = 这条消息属于已经收尾的旧下载，不能拿去取消新的这一条
            if (gen != 0 && gen != m_dlGen) return;
            m_dlStalled = true;
            if (m_dlOp) m_dlOp->Cancel();
            return;
        }
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
                    // 弹窗地址转到主窗口之前登记到会话放行表：证书错误策略只对
                    // 「可信域名族 ‖ 应用自己导航过的 host」放行（SEC-01），
                    // 不登记的话中转域会在证书错误处被 Cancel，下载链整条断掉。
                    pb::zlib::AddSessionHostFromUrl(ToNarrow(uriRaw));
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
                    // 每次新导航先清掉下载豁免标记：DownloadStarting 与
                    // NavigationCompleted 的**先后顺序不确定**（实测两者都会出现），
                    // 若标记在"导航完成先到"的情况下被置位却没人消费，下一次无关
                    // 导航失败就会被错误豁免，或反过来漏掉本次下载。清在这里，
                    // 让标记的生命周期不超过一次导航。
                    m_navIsDownload = false;

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

                        // 下载触发的导航：WebView2 把它变成下载后，主文档并不会真的被
                        // 替换，但 NavigationCompleted 仍会以 IsSuccess=FALSE 收尾。
                        // 这不是线路故障 —— 放行的话点一次下载就会触发换线路，用户的
                        // 书籍详情页当场消失、被甩到下一个镜像（2026-10-30 实测）。
                        // 这里只清标记，不动线路、不动遮罩。
                        // 判据除了 DownloadStarting 落的标记，还要看 URI 本身：
                        // 实测点下载时 NavigationCompleted(IsSuccess=FALSE) 可能**早于**
                        // DownloadStarting 触发，那一刻标记还是 false，只看标记就会
                        // 把这次下载导航当成线路故障、当场换线路（书籍详情页消失）。
                        // m_docUri 是本次 NavigationStarting 记下的地址，正指着 /dl/<id>。
                        if (m_navIsDownload || pb::zlib::IsDownloadUri(m_docUri)) {
                            m_navIsDownload = false;
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
                            // 真正的网络层失败（没拿到任何响应）：换下一条线路。
                            // 【只对进站导航换线】站内导航失败（网络抖动、点下载后
                            // 导航被 WebView2 转成下载再以失败收尾等）换线的代价是把
                            // 用户正在看的页面整个丢掉。2026-10-30 实测：点一次下载
                            // 就跳回镜像首页、书籍详情页消失，日志里正是这一行。
                            // 线索：此刻 m_docUri 是 CDN 下载地址，DownloadStarting
                            // 要晚十几毫秒才到，任何"等下载标记"的判据都会漏。
                            if (m_entryNav) {
                                AdvanceMirrorOnFailure();
                            } else {
                                pb::LogInfo("zlib", "navigation failed (not entry, ignored): " + m_docUri);
                            }
                            return S_OK;
                        }

                        // "成功"不等于"进去了"：503 的 JS 挑战页、404 错误页都是成功导航。
                        // 标题/状态码的判定要读 document.title，是异步的，见该方法。
                        ClassifyLoadedDocument();
                        return S_OK;
                    }).Get(), nullptr);
        }
    }

    // ── DownloadStarting — 交给 WebView2 / Chromium 自己下载 ────────────────
    // 【为什么不再自己发 HTTP】Z-Library 的 /dl/<id> 只是第一跳：站点前面有 JS 挑战
    // （c_token cookie）、每日限额，真正的文件在 302 之后的 CDN（如 dln1.ncdn.ec），
    // 那条响应还可能是 chunked。用 WinHTTP 重放等于要自己复现 Cookie 集合、跨域
    // 重定向与分块传输 —— 任何一处没复现，症状就是「读满一个 64KB 缓冲后卡住，
    // 30 秒接收超时后报 network_error」（2026-10-30 实测就是这个）。浏览器早已把
    // 这些做对了，所以这里只接管【落盘位置】与【进度/结果回调】，网络语义全部交回
    // Chromium。
    //
    // 与旧实现的另一个关键差别：只 put_Handled(TRUE)（隐藏默认下载 UI），**不再**
    // put_Cancel(TRUE)。Cancel 会把这条下载整个掐掉，旧代码因此必须自己补一份 HTTP，
    // 于是任何 Cookie/重定向差异都会变成下载失败。
    ComPtr<ICoreWebView2_4> wv4;
    if (SUCCEEDED(wv->QueryInterface(IID_PPV_ARGS(&wv4)))) {
        wv4->add_DownloadStarting(
            Callback<ICoreWebView2DownloadStartingEventHandler>(
                [this](ICoreWebView2*, ICoreWebView2DownloadStartingEventArgs* args) -> HRESULT {
                    if (!m_zlibActive) return S_OK;

                    // 由下载触发的导航在 WebView2 里会以 NavigationCompleted(IsSuccess=
                    // FALSE) 收尾（文档没有真的被替换）。那不是线路故障 —— 不标记的话
                    // 镜像判定会把这次导航当作"线路挂了"直接换线，用户看到的就是点下载
                    // 后书籍详情页消失、被甩到另一条线路甚至错误页。
                    m_navIsDownload = true;

                    if (m_zlibDlInProgress) {
                        // 看门狗：超过 10 分钟仍未归，说明上一个下载没有正常收尾。
                        // 继续早退会让用户点下载一直"零反应"。
                        if (std::chrono::steady_clock::now() - m_dlStartedAt > std::chrono::minutes(10)) {
                            m_zlibDlInProgress = false;
                        }
                    }
                    // 上一次下载还没收尾时点第二次：直接取消这一次（不弹默认下载框）。
                    // 两个 DownloadOperation 写同一个 .part 会互相删文件，任意一方
                    // file_write_failed，还会留下半截文件（2026-10-30 23:41 真机日志）。
                    if (m_zlibDlInProgress) {
                        pb::LogInfo("zlib", "download ignored: another download is still in progress");
                        args->put_Handled(TRUE);
                        args->put_Cancel(TRUE);
                        return S_OK;
                    }

                    ComPtr<ICoreWebView2DownloadOperation> op;
                    if (FAILED(args->get_DownloadOperation(&op)) || !op) return S_OK;
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
                    // 路径穿越防护：文件名来自服务端 Content-Disposition，属于不可信输入。
                    // 旧实现（WinHTTP 版）在 StartDownloadThread 里做这件事；改成原生
                    // 下载后由这里接手 —— 去掉分隔符 / 保留字符并抹掉 ".." 段，
                    // 否则 filename=..\\..\\evil 能把文件写到下载目录之外。
                    {
                        std::string safe;
                        for (char c : fileName) {
                            if (c == '\\' || c == '/' || c == ':' || c == '*' || c == '?' ||
                                c == '"' || c == '<' || c == '>' || c == '|') continue;
                            safe += c;
                        }
                        size_t p;
                        while ((p = safe.find("..")) != std::string::npos) safe.erase(p, 2);
                        if (safe.empty()) safe = "download";
                        fileName = safe;
                    }

                    // 落盘位置：固定到用户设定的下载目录，避免文件散落到系统 Downloads。
                    // 用 .part 后缀写，成功后再改成最终名 —— 与旧行为一致，重下同名书
                    // 不会先把旧的好文件截断。
                    const std::string finalPath = GetDownloadPath() + "\\" + fileName;
                    const std::string tmpPath = finalPath + ".part";
                    RemovePartialFile(tmpPath);
                    if (FAILED(args->put_ResultFilePath(ToWide(tmpPath).c_str()))) {
                        // 指定不了落盘路径就退回默认位置，仍然让 Chromium 下载完
                        // —— 至少比直接失败强，收尾时按 get_ResultFilePath 真实路径处理。
                    }

                    m_zlibDlInProgress = true;
                    m_dlStartedAt = std::chrono::steady_clock::now();
                    m_dlFileName = fileName;
                    m_dlFinalPath = finalPath;
                    m_dlOp = op;   // 卡死时由看门狗 Cancel 这一个
                    m_bridge->EmitEvent("zlib:downloadStart", {{"fileName", fileName}});
                    pb::LogInfo("zlib", "download start: " + fileName + " <- " + uri);

                    // 只隐藏默认下载 UI，不取消下载（见上面的说明）。
                    args->put_Handled(TRUE);

                    AttachDownloadOperation(op, fileName, finalPath);
                    return S_OK;
                }).Get(), &m_downloadToken);
    }
}

// 下载看门狗：CDN 掉链子时 Chromium 的下载可以永远挂着 —— 不进终态、不推进度，
// 用户看到的就是"点了下载没反应"，而且 m_zlibDlInProgress 一直是 true，之后每次
// 点下载都被"上一次还没收尾"静默吞掉。这里每 2 秒看一眼已接收字节数，连续 45 秒
// 零增长就把这个 operation Cancel 掉：Cancel 会让 StateChanged 以 INTERRUPTED 收尾，
// 于是删半截 .part、报 network_error、复位下载标志 —— 用户重试即可。
void ZLibraryService::StartDownloadWatchdog(std::shared_ptr<DlWatch> watch,
                                            unsigned long long gen,
                                            const std::string& fileName, HWND hwnd)
{
    if (!watch) return;
    // 线程是 detached 的，所以只按值捕获 watch/hwnd/fileName/gen，不碰 this：
    // 即使服务对象先没了，线程醒来也只是读一块共享状态、发一条窗口消息。
    std::thread([watch, gen, fileName, hwnd]() {
        constexpr int kTickMs = 2000;
        constexpr int kStallMs = 45000;
        long long last = -1;
        int idle = 0;
        while (watch->active.load()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(kTickMs));
            if (!watch->active.load()) return;
            const long long recv = watch->recv.load();
            if (recv != last) { last = recv; idle = 0; continue; }
            idle += kTickMs;
            if (idle < kStallMs) continue;
            watch->active = false;
            pb::LogWarn("zlib", "download stalled (" + std::to_string(recv) +
                                " bytes, no progress for 45s): " + fileName);
            if (hwnd) {
                auto* d = new DLFail{fileName, "network_stalled#" + std::to_string(gen)};
                PostMessage(hwnd, WM_ZLIB_DOWNLOAD_FAILED, 0, reinterpret_cast<LPARAM>(d));
            }
            return;
        }
    }).detach();
}

void ZLibraryService::AttachDownloadOperation(
    ComPtr<ICoreWebView2DownloadOperation> op, const std::string& fileName, const std::string& finalPath)
{
    if (!op) return;

    // 看门狗：本世代下载的字节数写进 watch，监测线程据此判断"还有没有在动"。
    auto watch = std::make_shared<DlWatch>();
    watch->active = true;
    m_dlWatch = watch;
    const unsigned long long gen = ++m_dlGen;
    StartDownloadWatchdog(watch, gen, fileName, m_hwnd);

    // 进度：首字节立刻上报，之后按「每 64KB 或每 300ms」节流，避免每个数据块都往
    // 渲染层发事件。注意别用「攒满 256KB 才发」：小文件（<256KB）和慢速下载会全程
    // 收不到任何进度，下载卡片就一直停在「准备下载...」（2026-10-31 真机复现：
    // 132KB 的书下了 84 秒，卡片一动不动）。
    auto lastReported = std::make_shared<int64_t>(-1);
    auto lastEmitAt = std::make_shared<std::chrono::steady_clock::time_point>(
        std::chrono::steady_clock::now());
    EventRegistrationToken progressToken = {};
    op->add_BytesReceivedChanged(
        Callback<ICoreWebView2BytesReceivedChangedEventHandler>(
            [this, fileName, lastReported, lastEmitAt, watch](ICoreWebView2DownloadOperation* sender,
                                           IUnknown*) -> HRESULT {
                INT64 recv = 0, total = 0;
                sender->get_BytesReceived(&recv);
                sender->get_TotalBytesToReceive(&total);
                if (recv < 0) recv = 0;
                watch->recv = (long long)recv;   // 喂看门狗：有新字节就不算卡
                const auto now = std::chrono::steady_clock::now();
                const bool first = *lastReported < 0;
                const bool grown = recv - *lastReported >= 65536;
                const bool due = first || grown ||
                                 std::chrono::duration_cast<std::chrono::milliseconds>(
                                     now - *lastEmitAt).count() >= 300 ||
                                 (total > 0 && recv >= total);
                if (due) {
                    *lastReported = recv;
                    *lastEmitAt = now;
                    m_bridge->EmitEvent("zlib:downloadProgress",
                        {{"fileName", fileName},
                         {"received", recv},
                         {"total", total < 0 ? 0 : total}});
                }
                return S_OK;
            }).Get(), &progressToken);

    // 状态：Completed / Interrupted 收尾。回调里立刻退订，避免同一 operation 重复触发。
    auto doneToken = std::make_shared<EventRegistrationToken>();
    op->add_StateChanged(
        Callback<ICoreWebView2StateChangedEventHandler>(
            [this, fileName, finalPath, doneToken, watch](ICoreWebView2DownloadOperation* sender,
                                                   IUnknown*) -> HRESULT {
                COREWEBVIEW2_DOWNLOAD_STATE state = COREWEBVIEW2_DOWNLOAD_STATE_IN_PROGRESS;
                if (FAILED(sender->get_State(&state))) return S_OK;

                if (state == COREWEBVIEW2_DOWNLOAD_STATE_IN_PROGRESS) return S_OK;
                watch->active = false;   // 进终态了，看门狗可以收工

                if (doneToken->value != 0) {
                    sender->remove_StateChanged(*doneToken);
                    doneToken->value = 0;
                }

                if (state == COREWEBVIEW2_DOWNLOAD_STATE_COMPLETED) {
                    FinishDownload(sender, fileName, finalPath);
                    return S_OK;
                }

                // INTERRUPTED：把 WebView2 的 InterruptReason 翻译成同一套错误串，
                // 让渲染层 / 日志的错误分类保持不变。
                COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON reason =
                    COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_NONE;
                sender->get_InterruptReason(&reason);
                // 中断时把半截 .part 删掉：失败不该在用户下载目录里留垃圾文件
                // （2026-10-30 真机：network_error 后留下 4,220 字节的 .part）。
                {
                    LPWSTR rp = nullptr;
                    if (SUCCEEDED(sender->get_ResultFilePath(&rp)) && rp) {
                        std::string partial = ToNarrow(rp);
                        CoTaskMemFree(rp);
                        if (!partial.empty()) RemovePartialFile(partial);
                    }
                }
                std::string reasonStr = DownloadInterruptReasonToString(reason);
                if (m_dlStalled) {
                    // 是我们自己（看门狗）取消的：统一报成网络错误，
                    // 免得渲染层弹出"已取消"这种跟用户操作无关的文案。
                    m_dlStalled = false;
                    reasonStr = "network_error";
                }
                FailDownload(fileName, reasonStr);
                return S_OK;
            }).Get(), doneToken.get());
}

// 下载完成：把 .part 改成最终名，并做与旧实现同样的「不是书就别收下」校验。
void ZLibraryService::FinishDownload(ICoreWebView2DownloadOperation* op,
                                     const std::string& fileName,
                                     const std::string& finalPath)
{
    std::string tmpPath = finalPath + ".part";
    // put_ResultFilePath 失败时会落在 WebView2 的默认位置，以真实路径为准。
    {
        LPWSTR rpRaw = nullptr;
        if (op && SUCCEEDED(op->get_ResultFilePath(&rpRaw)) && rpRaw) {
            std::string actual = ToNarrow(rpRaw);
            CoTaskMemFree(rpRaw);
            if (!actual.empty()) tmpPath = actual;
        }
    }

    if (m_dlWatch) m_dlWatch->active = false;

    std::error_code ec;
    auto size = std::filesystem::file_size(ToWide(tmpPath), ec);
    if (ec || size == 0) {
        RemovePartialFile(tmpPath);
        FailDownload(fileName, "empty_response");
        return;
    }

    // 会话过期时站点回的是 200 的登录/限额 HTML —— 那不是书，不能收下。
    {
        std::ifstream probe(ToWide(tmpPath), std::ios::binary);
        char head[64] = {};
        probe.read(head, sizeof(head));
        if (LooksLikeHtml(head, (size_t)probe.gcount())) {
            probe.close();
            RemovePartialFile(tmpPath);
            FailDownload(fileName, "not_a_book");
            return;
        }
    }

    std::filesystem::rename(ToWide(tmpPath), ToWide(finalPath), ec);
    if (ec) {
        // rename 跨卷/被占用会失败：退回复制 + 删源。
        std::error_code copyEc;
        std::filesystem::copy_file(ToWide(tmpPath), ToWide(finalPath),
                                   std::filesystem::copy_options::overwrite_existing, copyEc);
        if (copyEc) { RemovePartialFile(tmpPath); FailDownload(fileName, "file_write_failed"); return; }
        RemovePartialFile(tmpPath);
    }

    m_zlibDlInProgress = false;

    // PB-030: 下载历史的唯一真实来源 —— 只有真正落盘成功的才算一条。
    if (m_db) {
        json row;
        row["fileName"] = fileName;
        row["filePath"] = finalPath;
        row["size"] = static_cast<long long>(size);
        m_db->AddDownload(row);
    }

    pb::LogInfo("zlib", "downloaded " + fileName + " (" + std::to_string((long long)size) + " bytes)");
    m_bridge->EmitEvent("zlib:downloadComplete", {{"fileName", fileName}, {"path", finalPath}});
    if (m_hwnd) {
        auto* d = new std::pair<std::string, std::string>(finalPath, fileName);
        PostMessage(m_hwnd, WM_ZLIB_DO_IMPORT, 0, reinterpret_cast<LPARAM>(d));
    }
}

void ZLibraryService::FailDownload(const std::string& fileName, const std::string& reason)
{
    m_zlibDlInProgress = false;
    if (m_dlWatch) m_dlWatch->active = false;
    m_dlStalled = false;
    pb::LogError("zlib", "download failed: " + reason + " (file=" + fileName + ")");
    m_bridge->EmitEvent("zlib:downloadError", {{"fileName", fileName}, {"error", reason}});
}

std::string ZLibraryService::DownloadInterruptReasonToString(int reason)
{
    using R = COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON;
    switch (static_cast<R>(reason)) {
        // 网络层
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_NETWORK_FAILED:
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_NETWORK_TIMEOUT:
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_NETWORK_DISCONNECTED:
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_NETWORK_SERVER_DOWN:
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_NETWORK_INVALID_REQUEST:
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_SERVER_FAILED:
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_SERVER_NO_RANGE:
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_SERVER_CERTIFICATE_PROBLEM:
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_SERVER_CROSS_ORIGIN_REDIRECT:
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_SERVER_UNEXPECTED_RESPONSE:
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_SERVER_CONTENT_LENGTH_MISMATCH:
            return "network_error";

        // 拿到的不是书（会话过期 → 登录页/限额页；或内容一致性校验不过）
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_SERVER_BAD_CONTENT:
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_FILE_HASH_MISMATCH:
            return "not_a_book";

        // HTTP 鉴权/权限：站点侧拒绝，与"线路挂了"不是一回事
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_SERVER_UNAUTHORIZED:
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_SERVER_FORBIDDEN:
            return "http_403";

        // 本地文件
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_FILE_FAILED:
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_FILE_ACCESS_DENIED:
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_FILE_NO_SPACE:
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_FILE_NAME_TOO_LONG:
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_FILE_TOO_LARGE:
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_FILE_MALICIOUS:
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_FILE_TRANSIENT_ERROR:
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_FILE_BLOCKED_BY_POLICY:
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_FILE_SECURITY_CHECK_FAILED:
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_FILE_TOO_SHORT:
            return "file_write_failed";

        // 用户/进程侧
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_USER_CANCELED:
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_USER_SHUTDOWN:
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_USER_PAUSED:
        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_DOWNLOAD_PROCESS_CRASHED:
            return "cancelled";

        case COREWEBVIEW2_DOWNLOAD_INTERRUPT_REASON_NONE:
        default:
            return "network_error";
    }
}

// ── 旧实现：WinHTTP 重放下载（已删除，2026-10-30）─────────────────────
// 原 StartDownloadThread 从 WebView2 的 DownloadStarting 里取 URI + Cookie，再用
// WinHttpOpen 自己发一遍 GET，把响应写进 .part 文件。
//
// 为什么必须删掉：Z-Library 的下载链是「/dl/<id> --302--> CDN(另一域)」，且站点前面
// 有 JS 挑战（c_token cookie）与每日限额。自己重放要同时复现：
//   * 挑战页种下的 cookie 集合（还是跨到 CDN 域之后仍生效的那部分）
//   * 跨域 302 的跟随（旧实现只处理 301/302，漏 303/307/308 就会把跳转页当书）
//   * chunked 传输（CDN 那条响应可能是 Transfer-Encoding: chunked，没有
//     Content-Length，于是「tr < total」这类完整校验失效）
// 实测任何一处没复现，症状都一样：读满一个 64KB 缓冲后 WinHttpReadData 一直阻塞，
// 到 30 秒接收超时返回 FALSE，最终报 network_error，且 .part 恰好停在 65536 字节。
//
// 现在的做法见 SetupDownloadHandler 里的 DownloadStarting：只接管落盘位置与进度，
// 网络语义全部交回 Chromium（它本来就带着正确的 cookie、重定向与传输解码）。

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

