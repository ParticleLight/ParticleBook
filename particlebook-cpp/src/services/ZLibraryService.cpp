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

#define WM_ZLIB_DOWNLOAD_DONE (WM_USER + 10)
#define WM_ZLIB_DOWNLOAD_PROGRESS (WM_USER + 11)
#define WM_ZLIB_REFRESH_LIBRARY (WM_USER + 12)
#define WM_ZLIB_DO_IMPORT (WM_USER + 13)
#define WM_ZLIB_DOWNLOAD_FAILED (WM_USER + 14)
#define WM_ZLIB_IMPORT_DONE (WM_USER + 20)
#define WM_ZLIB_ENTRY_NAVIGATE (WM_USER + 21)  // 必须与 WebViewHost.cpp 一致   // 必须与 WebViewHost.cpp 一致（15-17 被 WM_UPDATE_* 占用）

struct DLProgress { std::string fn; int64_t recv; int64_t tot; };
struct DLFail { std::string fn; std::string reason; };
// 布局必须与 WebViewHost.cpp 里的同名结构完全一致（跨 TU 通过 LPARAM 传递）
struct ImportResult { std::string fileName; bool success; std::string error; };

// 会话过期时 z-lib 会返回 200 的登录页，此前会被当成书存盘并入库。
// FB2 本身是 XML，所以只把 <!doctype html / <html 判为 HTML，避免误杀。
static bool LooksLikeHtml(const char* p, size_t n) {
    size_t i = 0;
    while (i < n && (p[i] == ' ' || p[i] == '\t' || p[i] == '\r' || p[i] == '\n')) i++;
    if (n >= i + 14 && _strnicmp(p + i, "<!doctype html", 14) == 0) return true;
    if (n >= i + 5 && _strnicmp(p + i, "<html", 5) == 0) return true;
    return false;
}

// 失败/中断的下载必须删掉半截文件，否则它会留在下载目录里（且可能被重下覆盖）。
// 这里自己做 UTF-8 → 宽字符转换，不依赖文件后面才定义的 ToWide。
static void RemovePartialFile(const std::string& path) {
    if (path.empty()) return;
    int n = MultiByteToWideChar(CP_UTF8, 0, path.c_str(), (int)path.size(), nullptr, 0);
    if (n <= 0) return;
    std::wstring w(n, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, path.c_str(), (int)path.size(), &w[0], n);
    DeleteFileW(w.c_str());
}

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



// ── 线路的解析 / 过滤 / 探测 ─────────────────────────────────────────────
// 背景（实测，见本次提交说明）：zz.ggonav.com 是一个【导航页】，页面里的 <a href>
// 才是推荐线路；同一页还有 CDN/统计脚本的 href（cdnjs、googletagmanager…），旧解析
// 用 href="..." 把它们也当线路收了进来，而且排在列表最前面 —— 应用会去导航一个
// CSS 文件。这里两道过滤：只认 <a> 的 href，且只认「站点根地址」。

static std::string TrimAscii(const std::string& s) {
    size_t b = s.find_first_not_of(" \t\r\n");
    if (b == std::string::npos) return "";
    size_t e = s.find_last_not_of(" \t\r\n");
    return s.substr(b, e - b + 1);
}

static std::string LowerAscii(std::string s) {
    for (auto& c : s) if (c >= 'A' && c <= 'Z') c = (char)(c - 'A' + 'a');
    return s;
}

// http(s)://host[:port]/path → host / path / 是否 https
static bool SplitUrl(const std::string& url, std::string& host, std::string& path, bool& https) {
    size_t se = url.find("://");
    if (se == std::string::npos) return false;
    std::string scheme = url.substr(0, se);
    https = (scheme == "https");
    if (!https && scheme != "http") return false;
    size_t hs = se + 3;
    size_t ps = url.find('/', hs);
    if (ps == std::string::npos) { host = url.substr(hs); path = "/"; }
    else { host = url.substr(hs, ps - hs); path = url.substr(ps); }
    return !host.empty();
}

static bool HostIsOrSubdomainOf(const std::string& host, const std::string& domain) {
    if (host == domain) return true;
    if (host.size() <= domain.size() + 1) return false;
    return host.compare(host.size() - domain.size() - 1, domain.size() + 1, "." + domain) == 0;
}

// 第三方域名：CDN / 统计 / 文档 / 社交。出现在导航页的 href 里很正常，但绝不是线路。
static bool IsThirdPartyHost(const std::string& hostRaw) {
    std::string host = LowerAscii(hostRaw);
    static const char* const domains[] = {
        "x.com", "t.me", "qq.com", "google.com", "gstatic.com", "googleapis.com",
        "googletagmanager.com", "googleadservices.com", "googlesyndication.com", "doubleclick.net",
        "cloudflare.com", "cdnjs.com", "jsdelivr.net", "unpkg.com", "bootstrapcdn.com",
        "jquery.com", "tailwindcss.com", "fontawesome.com", "github.com", "githubusercontent.com",
        "gitlab.com", "wikipedia.org", "w3.org", "schema.org", "mozilla.org", "microsoft.com",
        "adobe.com", "youtube.com", "facebook.com", "twitter.com", "instagram.com", "discord.com",
        "reddit.com", "medium.com", "wordpress.com", "blogspot.com", "sentry.io", "matomo.org",
        "cnzz.com", "baidu.com", "weibo.com", "zhihu.com", "bilibili.com", "taobao.com", "jd.com",
    };
    for (const char* d : domains) if (HostIsOrSubdomainOf(host, d)) return true;
    static const char* const tokens[] = { "cdnjs", "googletag", "googlead", "analytics", "adservice", "doubleclick" };
    for (const char* t : tokens) if (host.find(t) != std::string::npos) return true;
    return false;
}

// 线路候选：http(s)、无 query/fragment、路径就是根、非第三方域名。实测样板：
//   olib.pages.dev/ ✔
//   cdnjs.cloudflare.com/ajax/libs/font-awesome/…/all.min.css ✘（路径不是根）
//   docs.qq.com/sheet/DVnRqc1V1RXdSUGN4 ✘（同上）
//   www.googletagmanager.com ✘（第三方）
static bool IsMirrorCandidate(const std::string& rawUrl) {
    std::string u = TrimAscii(rawUrl);
    if (u.empty()) return false;
    if (u.find('#') != std::string::npos || u.find('?') != std::string::npos) return false;
    std::string host, path; bool https = false;
    if (!SplitUrl(u, host, path, https)) return false;
    if (LowerAscii(host).find(' ') != std::string::npos) return false;
    if (path != "/" && !path.empty()) return false;
    if (host.find('.') == std::string::npos) return false;
    return !IsThirdPartyHost(host);
}

// Z-Library 正式站的指纹：真正的站（首页/登录页/书页）会带这些；
// 导航页/推广页/资源合集只会提一句 Z-Library 的名字（实测 olib.pages.dev、olibz.wwwnav.com、
// wangpanziyuan.pages.dev 三个都命中不了这里任何一条）。
static bool LooksLikeZlibApp(const std::string& s) {
    std::string low = LowerAscii(s);
    static const char* const marks[] = {
        "z-lib.fm", "z-access", "z-recommend", "singlelogin", "z-lib.io", "z-library since 2009",
    };
    for (const char* m : marks) if (low.find(m) != std::string::npos) return true;
    return false;
}

// 页面上是否出现了 Z-Library 的指纹（含只是提到它的导航页）
static bool LooksLikeZlibContent(const std::string& s) {
    std::string low = LowerAscii(s);
    static const char* const marks[] = { "z-lib", "zlibrary", "z-library", "1lib", "singlelogin", "bookfi" };
    for (const char* m : marks) if (low.find(m) != std::string::npos) return true;
    return false;
}

// "Checking your browser ..." —— 站点 302 之后发的 503【JS 挑战页】。这条判据很关键：
// 实测内置线路几乎全部 302 到同一个后端并在那里返回 503，真浏览器跑完挑战脚本会自己
// 再跳一次、进入真正的 Z-Library（标题 "Z-Library – 世界上最大的电子图书馆…"）。
// 所以 503 绝不等于「线路不可用」——把它当失败会把唯一能用的线路全部判死。
static bool LooksLikeJsChallenge(const std::string& s) {
    std::string low = LowerAscii(s);
    static const char* const marks[] = {
        "checking your browser", "just a moment", "verifying you are human",
        "cf-browser-verification", "challenge-platform", "正在检查您的浏览器",
    };
    for (const char* m : marks) if (low.find(m) != std::string::npos) return true;
    return false;
}

// 导航完成时只有 document.title 可读（正文拿不到），挑战页的标题同样要认
static bool LooksLikeChallengeTitle(const std::string& title) {
    if (LooksLikeJsChallenge(title)) return true;
    return LowerAscii(title).find("请稍候") != std::string::npos;
}

static int MirrorRank(const ZlibMirrorStat& s) {
    switch (s.kind) {
        case ZMK_ZLIB_APP:  return 0;   // 正式站（含挑战页）—— 最想要的就是它
        case ZMK_UNKNOWN:   return 1;   // 还没探到
        case ZMK_ZLIB_PAGE: return 2;   // 只是提到 Z-Library 的页（推广页/导航页）
        case ZMK_OTHER:     return 3;   // 通了但跟 Z-Library 无关
        default:            return 4;   // 不通
    }
}

// 探测一条线路：WinHTTP 直接跑（不占用主 WebView，只为排序）。
// 跟随重定向是刻意的 —— WinHTTP 把最终地址留在 WINHTTP_OPTION_URL 里，导航时直接用
// 它就能省掉入口域名那一跳的 DNS+TCP+TLS（实测每条线路 1.5~2 秒）。
static ZlibMirrorStat ProbeMirrorOnce(const std::string& url) {
    ZlibMirrorStat st; st.url = url;
    std::string host, path; bool https = true;
    if (!SplitUrl(url, host, path, https)) { st.kind = ZMK_FAIL; return st; }

    HINTERNET hS = WinHttpOpen(L"PB/2.1 (mirror-probe)", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY, nullptr, nullptr, 0);
    if (!hS) { st.kind = ZMK_FAIL; return st; }
    // 超时必须显式设：WinHTTP 的 resolve 默认是 0 = 无限等待（下载线程踩过同一个坑）
    WinHttpSetTimeouts(hS, 3000, 4000, 4000, 6000);
    HINTERNET hC = WinHttpConnect(hS, ToWide(host).c_str(), https ? 443 : 80, 0);
    if (!hC) { WinHttpCloseHandle(hS); st.kind = ZMK_FAIL; return st; }
    HINTERNET hR = WinHttpOpenRequest(hC, L"GET", ToWide(path).c_str(), nullptr, nullptr, nullptr,
                                      https ? WINHTTP_FLAG_SECURE : 0);
    if (!hR) { WinHttpCloseHandle(hC); WinHttpCloseHandle(hS); st.kind = ZMK_FAIL; return st; }

    // 与 WebView2 会话保持一致：会话期间应用本来就放行证书错误；探测若严格校验证书，
    // 会出现「浏览器能开、探测判死」的错判。
    DWORD secFlags = SECURITY_FLAG_IGNORE_UNKNOWN_CA | SECURITY_FLAG_IGNORE_CERT_CN_INVALID |
                     SECURITY_FLAG_IGNORE_CERT_DATE_INVALID | SECURITY_FLAG_IGNORE_CERT_WRONG_USAGE;
    WinHttpSetOption(hR, WINHTTP_OPTION_SECURITY_FLAGS, &secFlags, sizeof(secFlags));
    WinHttpAddRequestHeaders(hR, L"User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36", (DWORD)-1, WINHTTP_ADDREQ_FLAG_ADD);
    WinHttpAddRequestHeaders(hR, L"Accept: text/html,application/xhtml+xml,*/*", (DWORD)-1, WINHTTP_ADDREQ_FLAG_ADD);
    WinHttpAddRequestHeaders(hR, L"Accept-Language: zh-CN,zh;q=0.9,en;q=0.8", (DWORD)-1, WINHTTP_ADDREQ_FLAG_ADD);

    auto t0 = std::chrono::steady_clock::now();
    bool ok = WinHttpSendRequest(hR, nullptr, 0, nullptr, 0, 0, 0) && WinHttpReceiveResponse(hR, nullptr);
    st.ms = (int)std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - t0).count();
    if (!ok) {
        WinHttpCloseHandle(hR); WinHttpCloseHandle(hC); WinHttpCloseHandle(hS);
        st.kind = ZMK_FAIL;
        return st;
    }

    DWORD sc = 0, sz = sizeof(sc);
    WinHttpQueryHeaders(hR, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER, nullptr, &sc, &sz, nullptr);
    st.status = (int)sc;
    WCHAR finalUrl[2048] = {};
    DWORD fl = sizeof(finalUrl);
    if (WinHttpQueryOption(hR, WINHTTP_OPTION_URL, finalUrl, &fl)) st.finalUrl = TrimAscii(ToNarrow(finalUrl));

    std::string head; char buf[8192]; DWORD br = 0;
    while (head.size() < 32768 && WinHttpReadData(hR, buf, sizeof(buf), &br) && br > 0) head.append(buf, br);
    WinHttpCloseHandle(hR); WinHttpCloseHandle(hC); WinHttpCloseHandle(hS);

    // 挑战页归"正式站"：站点自己的 503 挑战页过完 JS 就是站（实测所有能用的线路都走这条路）
    if (LooksLikeZlibApp(head) || LooksLikeJsChallenge(head)) st.kind = ZMK_ZLIB_APP;
    else if (LooksLikeZlibContent(head)) st.kind = ZMK_ZLIB_PAGE;
    else if (st.status > 0 && st.status < 400) st.kind = ZMK_OTHER;
    else st.kind = ZMK_FAIL;
    return st;
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

bool ZLibraryService::IsZlibHost(const std::string& host) {
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
    WinHttpSetTimeouts(hS, 10000, 10000, 10000, 20000);  // 镜像列表抓取同样不能无限等
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
    // 抓镜像名单 + 并行探测，全在后台。探测的意义见 ProbeMirrorOnce 上方的说明：
    // 进站时能直接导航到"确实通"的那条，而且用它探测出的最终地址省掉入口那一跳。
    // Capturing self (not this) keeps the service alive until the threads finish, so a
    // shutdown that destroys the service mid-fetch can't use-after-free.
    std::thread([self]() { self->FetchMirrors(); self->ProbeAndRank(); }).detach();
}

json ZLibraryService::FetchMirrors() {
    std::string html = FetchUrl("https://zz.ggonav.com/");

    if (html.empty()) {
        std::lock_guard<std::mutex> lk(m_mirrorMutex);
        json r; r["mirrors"] = m_mirrors; r["current"] = m_currentMirror; return r;
    }

    std::vector<std::string> found;
    auto add = [&](const std::string& raw) {
        std::string u = TrimAscii(raw);
        if (!IsMirrorCandidate(u)) return;
        if (u.back() != '/') u += '/';
        if (std::find(found.begin(), found.end(), u) == found.end()) found.push_back(u);
    };

    // 只认 <a href>：旧写法 href="..." 会把 <link href> 的 CDN 样式表一起收进来
    static const std::regex anchorRe("<a\\s[^>]*href\\s*=\\s*[\"']([^\"']+)[\"']", std::regex::icase);
    for (auto it = std::sregex_iterator(html.begin(), html.end(), anchorRe); it != std::sregex_iterator(); ++it) {
        add((*it)[1]);
    }
    // 正文里直接写出来的裸地址（导航页两种写法都出现过）
    static const std::regex urlRe("(https?://[a-zA-Z0-9.-]+\\.[a-z]{2,}/?)", std::regex::icase);
    for (auto it = std::sregex_iterator(html.begin(), html.end(), urlRe); it != std::sregex_iterator(); ++it) {
        add((*it)[1]);
    }

    {
        std::lock_guard<std::mutex> lk(m_mirrorMutex);
        if (!found.empty()) {
            // 顺序在这里只剩"探测落地之前"的意义 —— 探测一完成就按实测重排（见 ProbeAndRank）。
            // 所以硬编码的 Z-Library 域名放前面、导航页推荐的域名放后面：导航页里混着工具
            // 推广页和资源合集（实测 olibz.wwwnav.com、wangpanziyuan.pages.dev 都是那种），
            // 探测落地前先把用户送进真正的 Z-Library。
            std::vector<std::string> merged = FALLBACK_MIRRORS;
            for (const auto& m : found) {
                if (std::find(merged.begin(), merged.end(), m) == merged.end()) merged.push_back(m);
            }
            // 永久黑名单（singlelogin.re 等现在是成人站）仍然要挡
            merged.erase(std::remove_if(merged.begin(), merged.end(), [](const std::string& u) {
                std::string host, path; bool https = false;
                if (!SplitUrl(u, host, path, https)) return true;
                std::string h = LowerAscii(host);
                for (const auto& b : BLOCKED_DOMAINS) if (h.find(b) != std::string::npos) return true;
                return false;
            }), merged.end());

            // 已经测过的线路保留实测结果：刷新名单不该把刚探到的耗时丢掉
            std::vector<ZlibMirrorStat> stats;
            stats.reserve(merged.size());
            for (const auto& u : merged) {
                auto it = std::find_if(m_mirrorStats.begin(), m_mirrorStats.end(),
                                       [&](const ZlibMirrorStat& s) { return s.url == u; });
                if (it != m_mirrorStats.end()) stats.push_back(*it);
                else { ZlibMirrorStat s; s.url = u; stats.push_back(s); }
            }
            m_mirrors = merged;
            m_mirrorStats = stats;
        }
        SortMirrorsLocked();
        SelectDefaultMirrorLocked();
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
    std::string url, pending;
    bool hasMirror = false;
    {
        std::lock_guard<std::mutex> lk(m_mirrorMutex);
        if (index >= 0 && index < (int)m_mirrors.size()) {
            m_currentMirror = index;
            // 手动选过的线路要钉住：后台探测重排、名单刷新都不该把它顶掉 ——
            // 否则用户选了 A，"下次进站"又被自动排到 B（登录态还绑在域名上）。
            m_mirrorPinned = true;
            m_pinnedUrl = m_mirrors[index];
        }
        if (!m_mirrors.empty()) {
            m_navRetryCount = 0;
            m_retryMirrorCount = (int)m_mirrors.size();
            pending = m_mirrors[m_currentMirror];
            url = MirrorNavigateUrlLocked(m_currentMirror);
            hasMirror = true;
        }
    }
    if (hasMirror) {
        m_entryNavPending = false;   // 手动选线路：放弃挂起的自动进站
        m_probePause = true;
        m_pendingMirrorUrl = pending;
        m_lastDocStatus = 0;
        m_entryNav = true;
        m_challengeWaits = 0;
        if (m_host && m_host->GetWebView())
            m_host->GetWebView()->Navigate(ToWide(url).c_str());
        m_bridge->EmitEvent("zlib:mirrorChanged", GetMirrorInfo());
    }
    return GetMirrorInfo();
}

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
        m_bridge->EmitEvent("zlib:importComplete", {{"fileName", fileName}});
        // 通知渲染层刷新书架（下载完成的书要出现在书架上）
        m_bridge->EmitEvent("library:changed", json::object());
    } else {
        m_bridge->EmitEvent("zlib:importError", {{"fileName", fileName}, {"error", errMsg}});
    }
}

// ── 线路选择 / 导航结果判定 ──────────────────────────────────────────────

// 按「是不是正式站 + 实测耗时」重排（要求已持有 m_mirrorMutex）。m_mirrors 与
// m_mirrorStats 同长同序，排完一起重建。
void ZLibraryService::SortMirrorsLocked() {
    std::stable_sort(m_mirrorStats.begin(), m_mirrorStats.end(),
                     [](const ZlibMirrorStat& a, const ZlibMirrorStat& b) {
        int ra = MirrorRank(a), rb = MirrorRank(b);
        if (ra != rb) return ra < rb;
        int ma = a.ms < 0 ? INT_MAX : a.ms;
        int mb = b.ms < 0 ? INT_MAX : b.ms;
        return ma < mb;
    });
    m_mirrors.clear();
    m_mirrors.reserve(m_mirrorStats.size());
    for (const auto& s : m_mirrorStats) m_mirrors.push_back(s.url);
}

// 单条探测结果落地（要求已持有 m_mirrorMutex）：按 URL 找，不按索引 —— 探测期间
// 名单可能被刷新过，索引早就对不上了。
void ZLibraryService::ApplyProbeResultLocked(const ZlibMirrorStat& st) {
    auto it = std::find_if(m_mirrorStats.begin(), m_mirrorStats.end(),
                           [&](const ZlibMirrorStat& s) { return s.url == st.url; });
    if (it == m_mirrorStats.end()) return;   // 名单已被换掉，这条结果作废
    *it = st;
    if (m_mirrorStats.size() != m_mirrors.size()) return;
    SortMirrorsLocked();
    SelectDefaultMirrorLocked();
}

// 选"当前线路"（要求已持有 m_mirrorMutex）。优先级：
//   ① 用户本次会话手动选过的（钉住，不再被自动排序顶掉）
//   ② 上次真的进去过的（登录 Cookie 绑在域名上，能不动就不动）
//   ③ 探测排在最前面的那条
void ZLibraryService::SelectDefaultMirrorLocked() {
    if (m_mirrors.empty()) { m_currentMirror = 0; return; }
    if (m_mirrorPinned) {
        auto it = std::find(m_mirrors.begin(), m_mirrors.end(), m_pinnedUrl);
        if (it != m_mirrors.end()) { m_currentMirror = (int)(it - m_mirrors.begin()); return; }
        m_mirrorPinned = false;   // 那条线路已经不在名单里了
    }
    if (!m_lastWorkingMirror.empty()) {
        auto it = std::find(m_mirrors.begin(), m_mirrors.end(), m_lastWorkingMirror);
        if (it != m_mirrors.end()) {
            size_t idx = (size_t)(it - m_mirrors.begin());
            bool knownDead = (idx < m_mirrorStats.size() && m_mirrorStats[idx].kind == ZMK_FAIL);
            if (!knownDead) { m_currentMirror = (int)idx; return; }
        }
    }
    m_currentMirror = 0;
}

// 导航用哪个地址：探测已经跟到重定向尽头就直接用最终地址（省掉入口域名那一跳的
// DNS+TCP+TLS，实测 1.5~2 秒），否则用名单里的原地址。
std::string ZLibraryService::MirrorNavigateUrlLocked(int index) {
    if (index < 0 || index >= (int)m_mirrors.size()) return "";
    if (index < (int)m_mirrorStats.size()) {
        const std::string& f = m_mirrorStats[index].finalUrl;
        if (!f.empty() && IsMirrorCandidate(f)) return f;
    }
    return m_mirrors[index];
}

void ZLibraryService::LoadLastMirrorOnce() {
    if (!m_db || m_lastMirrorLoaded) return;
    m_lastMirrorLoaded = true;
    json settings = m_db->GetSettings();
    if (settings.is_null() || !settings.contains("zlibLastMirror")) return;
    if (!settings["zlibLastMirror"].is_string()) return;
    std::string url = settings["zlibLastMirror"].get<std::string>();
    std::lock_guard<std::mutex> lk(m_mirrorMutex);
    if (m_lastWorkingMirror.empty()) m_lastWorkingMirror = url;
}

void ZLibraryService::RememberWorkingMirror(const std::string& url) {
    if (url.empty()) return;
    {
        std::lock_guard<std::mutex> lk(m_mirrorMutex);
        if (url == m_lastWorkingMirror) return;
        m_lastWorkingMirror = url;
    }
    if (!m_db) return;
    json settings = m_db->GetSettings();
    if (settings.is_null()) settings = json::object();
    settings["zlibLastMirror"] = url;
    m_db->UpdateSettings(settings);
}

// 探测结果过期（或还没探过）时后台补探一轮。进站/换线时调用，绝不阻塞这次导航。
void ZLibraryService::RefreshRankingIfStale() {
    const int kMaxAgeSeconds = 600;
    {
        std::lock_guard<std::mutex> lk(m_mirrorMutex);
        if (m_probeRunning) return;
        bool haveResult = !m_mirrors.empty() && m_mirrorStats.size() == m_mirrors.size() &&
                          m_mirrorStats[0].kind != ZMK_UNKNOWN;
        if (haveResult) {
            auto age = std::chrono::duration_cast<std::chrono::seconds>(
                           std::chrono::steady_clock::now() - m_probeFinishedAt).count();
            if (age < kMaxAgeSeconds) return;
        }
    }
    // 服务由 App 用 shared_ptr 持有：这样补探线程不会比服务活得久（同 StartMirrorFetch）
    auto self = App::Instance().Zlib();
    if (self) std::thread([self]() { self->ProbeAndRank(); }).detach();
}

// 并行探测所有线路并按「可达性 + 实测耗时」重排。跑在后台线程里。
void ZLibraryService::ProbeAndRank() {
    if (m_probeRunning.exchange(true)) return;

    std::vector<std::string> urls;
    { std::lock_guard<std::mutex> lk(m_mirrorMutex); urls = m_mirrors; }

    // 8 条并发：单条最坏要等 ~13 秒（各项超时之和），串行探十几条要一两分钟，
    // 那样排序结果永远赶不上用户点进 Z-Library。
    std::atomic<size_t> next{0};
    size_t nThreads = std::min<size_t>(urls.size(), 8);
    std::vector<std::thread> pool;
    pool.reserve(nThreads);
    for (size_t i = 0; i < nThreads; i++) {
        pool.emplace_back([&]() {
            for (size_t k = next.fetch_add(1); k < urls.size(); k = next.fetch_add(1)) {
                // 用户已经点进来了：先别探新线路 —— 这条链路本来就窄，探测跟进站导航
                // 抢带宽只会让用户多等（实测进站那一下能等出十几秒）。在跑的几条探完
                // 就停在这儿，进站出结果后自动继续；最多停 60 秒兜底。
                for (int w = 0; w < 600 && m_probePause.load(); w++)
                    std::this_thread::sleep_for(std::chrono::milliseconds(100));
                ZlibMirrorStat st = ProbeMirrorOnce(urls[k]);

                // 探到一条就落地一条：死线路单条要等满超时（~13 秒），等全部探完再排序
                // 的话用户早进站了 —— 那样这份排序等于没用。
                std::lock_guard<std::mutex> lk(m_mirrorMutex);
                ApplyProbeResultLocked(st);
            }
        });
    }
    for (auto& t : pool) t.join();

    {
        std::lock_guard<std::mutex> lk(m_mirrorMutex);
        m_probeFinishedAt = std::chrono::steady_clock::now();
    }
    m_probeRunning = false;
}

// 导航"成功"之后才敢下结论。三种情况：
//   ① document.title 还是 JS 挑战页 → 挑战脚本跑完会自己再跳一次，遮罩继续留着
//      （此前这里立刻撤遮罩，用户看到的就是一个空白页在干等）；
//   ② 进站导航拿到 4xx/5xx 且不是挑战页 → 这条线路不可用，换下一条；
//   ③ 正常页面 → 发 mirrorChanged 撤遮罩，并记住这条线路。
// 只在【进站导航】上判 4xx/5xx：站内点书点出个 404 不该被当成"线路挂了"而把用户
// 甩到另一个域名去。
void ZLibraryService::ClassifyLoadedDocument() {
    auto* wv = m_host ? m_host->GetWebView() : nullptr;
    if (!wv) {
        m_entryNav = false;
        m_challengeWaits = 0;
        m_probePause = false;
        m_bridge->EmitEvent("zlib:mirrorChanged", GetMirrorInfo());
        return;
    }
    wv->ExecuteScript(L"document.title",
        Callback<ICoreWebView2ExecuteScriptCompletedHandler>(
            [this](HRESULT hr, LPCWSTR resultJson) -> HRESULT {
                if (!m_zlibActive) return S_OK;
                std::string title;
                if (SUCCEEDED(hr) && resultJson) {
                    try {
                        json j = json::parse(ToNarrow(resultJson));
                        if (j.is_string()) title = j.get<std::string>();
                    } catch (...) { /* 拿不到标题就当普通页面处理 */ }
                }
                if (LooksLikeChallengeTitle(title)) return S_OK;   // 等挑战自己跑完
                if (m_entryNav && m_lastDocStatus >= 400) { AdvanceMirrorOnFailure(); return S_OK; }
                m_entryNav = false;
                m_challengeWaits = 0;
                m_probePause = false;   // 进站结束，探测继续把剩下的线路探完
                {
                    std::lock_guard<std::mutex> lk(m_mirrorMutex);
                    m_navRetryCount = 0;
                }
                RememberWorkingMirror(m_pendingMirrorUrl);
                m_bridge->EmitEvent("zlib:mirrorChanged", GetMirrorInfo());
                return S_OK;
            }).Get());
}

// 一条线路不可用 → 换下一条。额度 = Show()/SwitchMirror 时快照的线路数（轮一遍即停）。
void ZLibraryService::AdvanceMirrorOnFailure() {
    std::string nextUrl, pending;
    bool retry = false;
    {
        std::lock_guard<std::mutex> lk(m_mirrorMutex);
        if (m_navRetryCount < m_retryMirrorCount && m_navRetryCount < (int)m_mirrors.size()) {
            m_navRetryCount++;
            // 往后找下一条：【探测已知不通】的直接跳过。名单已按可达性排过序，
            // 正常情况下下一条就是能用的，不必一条条去撞。
            size_t n = m_mirrors.size();
            for (size_t step = 0; step < n; step++) {
                m_currentMirror = (m_currentMirror + 1) % (int)n;
                bool knownDead = m_currentMirror < (int)m_mirrorStats.size() &&
                                 m_mirrorStats[m_currentMirror].kind == ZMK_FAIL;
                if (!knownDead) break;
            }
            pending = m_mirrors[m_currentMirror];
            nextUrl = MirrorNavigateUrlLocked(m_currentMirror);
            retry = true;
        }
        // 挂掉的线路不该继续被"用户手动选择"钉住，否则下次进站还从它开始
        m_mirrorPinned = false;
    }
    auto* wv = m_host ? m_host->GetWebView() : nullptr;
    if (retry && wv) {
        m_pendingMirrorUrl = pending;
        m_lastDocStatus = 0;
        m_entryNav = true;
        m_challengeWaits = 0;
        wv->Navigate(ToWide(nextUrl).c_str());
        // 这里【不】发 mirrorChanged：遮罩要留到这条线路真的出结果，否则又是一次白屏干等
        return;
    }
    m_entryNav = false;
    m_probePause = false;
    m_bridge->EmitEvent("zlib:allMirrorsFailed", json::object());
    m_bridge->EmitEvent("zlib:mirrorChanged", GetMirrorInfo());
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
