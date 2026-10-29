#include "WebViewHost.h"
#include "App.h"
#include "WebViewHostInternal.h"
#include "services/ZLibraryMirror.h"   // SEC-01：证书错误只对可信 zlib 主机放行
#include "platform/LogService.h"
#include <wrl/event.h>
#include <filesystem>
#include <cstdio>
#include <thread>

using namespace Microsoft::WRL;

namespace {

// ── SEC-05 缓解（纵深防御）────────────────────────────────────────────
// `renderer/_pb_files/` 是 file:read 复制出来的书本副本目录，它和 app 页面**同源**
// （都在虚拟主机 particlebook.app 下）。这些副本只应当作为【子资源】被读（<img src>、
// fetch(_pb_url)）；一旦以【文档】形式打开（顶层导航 / 重定向 / 新窗口），它就继承
// app 源的全部桥接能力（file:read、downloadUpdate、quitAndInstall…），恶意书/HTML
// 可以直接接管本机。
//
// 这里挡掉「文档级导航进入 _pb_files」这一条路径。NavigationStarting 不覆盖子资源
// 请求，所以图片/fetch 不受影响。
// 残余风险（未消除）：子资源仍是同源可读（虚拟主机是 ALLOW），彻底解决要把
// _pb_files 挪到独立虚拟主机/一次性 token URL —— 那要改 handlers 侧的 URL 拼接，
// 本轮不做（见 docs/review/2026-10-29/security.md）。
bool IsPbFilesDocumentUri(const std::wstring& uri)
{
    static const wchar_t kPrefix[] = L"http://particlebook.app/_pb_files";
    const std::size_t prefixLen = (sizeof(kPrefix) / sizeof(kPrefix[0])) - 1;
    if (uri.size() < prefixLen) return false;
    if (uri.compare(0, prefixLen, kPrefix) != 0) return false;
    if (uri.size() == prefixLen) return true;
    const wchar_t next = uri[prefixLen];
    return next == L'/' || next == L'?' || next == L'#';
}

} // namespace

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
    // DevTools 只在调试构建里默认打开：Release 打开等于把「调用全部桥接方法」
    // （file:read / downloadUpdate / quitAndInstall）做成一个可交互后门。
    // 排障需要时用环境变量 PB_DEVTOOLS=1 临时打开。
    {
        BOOL devTools = FALSE;
#ifdef _DEBUG
        devTools = TRUE;
#else
        wchar_t flag[8] = {};
        const DWORD flagLen = GetEnvironmentVariableW(L"PB_DEVTOOLS", flag, 8);
        if (flagLen > 0 && flagLen < 8) {
            devTools = (flag[0] == L'1' || flag[0] == L't' || flag[0] == L'T') ? TRUE : FALSE;
        }
#endif
        m_settings->put_AreDevToolsEnabled(devTools);
    }
    m_settings->put_IsScriptEnabled(TRUE);

    // Set user agent to match Chrome (avoid blocking by Z-Library)
    ComPtr<ICoreWebView2Settings2> settings2;
    if (SUCCEEDED(m_settings.As(&settings2))) {
        settings2->put_UserAgent(L"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
    }

    // Z-Library 的镜像会跳到无法预先枚举的中转域（如 msn101.ru），其证书可能自签。
    //
    // 2026-10-29 review（SEC-01）之后：放行要同时满足两个条件 ——
    //   1) 处于 Z-Library 会话期间（ZLibraryService::Show/Hide 开关 m_allowUntrustedCerts）；
    //   2) 出错的主机属于「Z-Library 域名族」（IsTrustedHost）或「本次会话应用自己
    //      导航过的 host」（IsSessionHost，由镜像表/探测结果登记，页面无法注入）。
    // 不满足就显式 CANCEL 并写日志：宁可让这条线路失败、让上层换线，也不对任意主机
    // 放行（否则被投毒的导航页/被劫持的跳转就能用一个自签证书冒充登录页）。
    ComPtr<ICoreWebView2_14> wv14;
    if (SUCCEEDED(m_webview.As(&wv14))) {
        wv14->add_ServerCertificateErrorDetected(
            Callback<ICoreWebView2ServerCertificateErrorDetectedEventHandler>(
                [this](ICoreWebView2*, ICoreWebView2ServerCertificateErrorDetectedEventArgs* args) -> HRESULT {
                    if (!m_allowUntrustedCerts) {
                        // 非会话期间不设 Action → 交给 WebView2 默认处理（不放行）
                        return S_OK;
                    }
                    // SEC-01：旧行为是会话期对【任意主机】ALWAYS_ALLOW —— 一旦页面把我们
                    // 带到别处（或导航页被投毒），伪造站点的自签证书会被静默接受，而用户
                    // 正要在上面输入账号密码。现在只认下面两类 host。
                    std::string host;
                    LPWSTR uriRaw = nullptr;
                    if (SUCCEEDED(args->get_RequestUri(&uriRaw)) && uriRaw) {
                        host = pb::zlib::HostFromUrl(ToUtf8(uriRaw));
                        CoTaskMemFree(uriRaw);
                    }
                    // 两类 host 才放行：Z-Library 域名族本身，以及本次会话由应用
                    // 自己导航过去的中转域（Show/Navigate/SwitchMirror/换线时登记，
                    // 见 ZLibraryService.cpp 的 AddSessionHostFromUrl 调用点）。
                    const bool trustedHost = pb::zlib::IsTrustedHost(host);
                    const bool sessionHost = !trustedHost && pb::zlib::IsSessionHost(host);
                    if (trustedHost || sessionHost) {
                        args->put_Action(COREWEBVIEW2_SERVER_CERTIFICATE_ERROR_ACTION_ALWAYS_ALLOW);
                        pb::LogInfo("zlib", std::string("certificate error allowed (")
                                               + (trustedHost ? "zlib host" : "session host") + "): " + host);
                    } else {
                        args->put_Action(COREWEBVIEW2_SERVER_CERTIFICATE_ERROR_ACTION_CANCEL);
                        pb::LogWarn("zlib", std::string("certificate error blocked (untrusted host): ")
                                               + (host.empty() ? std::string("(unparsable uri)") : host));
                    }
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

    // SEC-05 缓解：文档级导航进入 _pb_files 一律取消（含重定向）；子资源不受影响。
    m_webview->add_NavigationStarting(
        Callback<ICoreWebView2NavigationStartingEventHandler>(
            [](ICoreWebView2*, ICoreWebView2NavigationStartingEventArgs* args) -> HRESULT {
                LPWSTR uriRaw = nullptr;
                if (FAILED(args->get_Uri(&uriRaw)) || !uriRaw) return S_OK;
                const std::wstring uri(uriRaw);
                CoTaskMemFree(uriRaw);
                if (!IsPbFilesDocumentUri(uri)) return S_OK;
                BOOL redirected = FALSE;
                args->get_IsRedirected(&redirected);
                args->put_Cancel(TRUE);
                pb::LogWarn("webview", std::string("cancelled document navigation into _pb_files")
                                        + (redirected ? " (redirect)" : ""));
                return S_OK;
            }).Get(), nullptr);

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

