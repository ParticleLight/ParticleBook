#include "ZLibraryService.h"
#include "ZLibraryMirror.h"   // SEC-01：候选过滤 / 证书放行 / 桥接来源共用的 host 判据
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
#include <mutex>
#include <fstream>
#include <sstream>


#pragma comment(lib, "winhttp.lib")
#include <wrl/event.h>
#include <utility>
using namespace Microsoft::WRL;

#include "ZLibraryInternal.h"

// ── 线路的解析 / 过滤 / 探测 ─────────────────────────────────────────────
// 背景（实测，见本次提交说明）：zz.ggonav.com 是一个【导航页】，页面里的 <a href>
// 才是推荐线路；同一页还有 CDN/统计脚本的 href（cdnjs、googletagmanager…），旧解析
// 用 href="..." 把它们也当线路收了进来，而且排在列表最前面 —— 应用会去导航一个
// CSS 文件。这里两道过滤：只认 <a> 的 href，且只认「站点根地址」。

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

// ── 主机信任判据（SEC-01）────────────────────────────────────────────
// 声明在 ZLibraryMirror.h，实现放这里：候选过滤（本文件）、证书错误放行
// （WebViewHostWebView.cpp）、raw 桥接消息来源（BridgeServer.cpp）三处共用一份，
// 避免任何一处被单独放宽而漂移。

namespace pb {
namespace zlib {

std::string HostFromUrl(const std::string& url)
{
    std::string host, path;
    bool https = false;
    if (!SplitUrl(url, host, path, https)) return std::string();
    host = LowerAscii(host);
    // userinfo（https://user:pass@host/…）只取 @ 之后
    const std::size_t at = host.rfind('@');
    if (at != std::string::npos) host = host.substr(at + 1);
    // IPv6 字面量（[::1]:443）原样保留 —— 它永远不该命中线路白名单
    if (!host.empty() && host[0] == '[') {
        const std::size_t close = host.find(']');
        return close == std::string::npos ? host : host.substr(0, close + 1);
    }
    const std::size_t colon = host.rfind(':');
    if (colon != std::string::npos) host = host.substr(0, colon);
    return host;
}

namespace {

// 已归一小写后再比
bool LabelStartsWith(const std::string& label, const char* prefix)
{
    const std::size_t n = std::strlen(prefix);
    return label.size() >= n && label.compare(0, n, prefix) == 0;
}

bool LabelContains(const std::string& label, const char* needle)
{
    return label.find(needle) != std::string::npos;
}

} // namespace

bool IsTrustedHost(const std::string& host)
{
    if (host.empty()) return false;
    std::string h = LowerAscii(host);
    if (!h.empty() && h[0] == '[') return false;          // IPv6 不是线路
    const std::size_t colon = h.find(':');
    if (colon != std::string::npos) h = h.substr(0, colon);
    if (h.empty() || h.find(' ') != std::string::npos) return false;

    // 前缀命中：镜像真实用法 zh.z-lib.gd / zh.z-library.sk / zlib.re / zh.zlib0.ru / zh.1lib.sk
    static const char* const kPrefixTokens[] = { "z-lib", "z-library", "zlibrary", "zlib", "1lib" };
    // 包含命中：zh.singlelogin.rs / zh.101fbiwarning.ru / zh.dfj101.ru / zh.zzz101.ru
    static const char* const kFullTokens[] = { "singlelogin", "bookfi", "fbiwarning", "dfj101", "zzz101" };

    std::size_t begin = 0;
    while (begin <= h.size()) {
        std::size_t end = h.find('.', begin);
        if (end == std::string::npos) end = h.size();
        const std::string label = h.substr(begin, end - begin);
        if (!label.empty()) {
            for (const char* token : kPrefixTokens) if (LabelStartsWith(label, token)) return true;
            for (const char* token : kFullTokens) if (LabelContains(label, token)) return true;
        }
        if (end == h.size()) break;
        begin = end + 1;
    }
    return false;
}

// ── 会话级临时放行主机（只给证书错误用，理由见 ZLibraryMirror.h）─────────
// 用 vector + 线性查表：一次会话只会有个位数到十几个 host，比 unordered_set 更省事
// 且有天然上限保护（超上限直接不再记录，避免异常情况无限增长）。
namespace {

std::mutex& SessionHostsMutex()
{
    static std::mutex mutex;
    return mutex;
}

std::vector<std::string>& SessionHosts()
{
    static std::vector<std::string> hosts;
    return hosts;
}

constexpr std::size_t kMaxSessionHosts = 64;

std::string NormalizeHost(const std::string& host)
{
    std::string h = LowerAscii(host);
    if (!h.empty() && h[0] == '[') {
        const std::size_t close = h.find(']');
        return close == std::string::npos ? h : h.substr(0, close + 1);
    }
    const std::size_t colon = h.find(':');
    if (colon != std::string::npos) h = h.substr(0, colon);
    return h;
}

} // namespace

void ClearSessionHosts()
{
    std::lock_guard<std::mutex> lk(SessionHostsMutex());
    SessionHosts().clear();
}

void AddSessionHostFromUrl(const std::string& url)
{
    const std::string host = HostFromUrl(url);
    if (host.empty()) return;
    std::lock_guard<std::mutex> lk(SessionHostsMutex());
    std::vector<std::string>& hosts = SessionHosts();
    if (hosts.size() >= kMaxSessionHosts) return;
    if (std::find(hosts.begin(), hosts.end(), host) == hosts.end()) hosts.push_back(host);
}

bool IsSessionHost(const std::string& host)
{
    const std::string h = NormalizeHost(host);
    if (h.empty()) return false;
    std::lock_guard<std::mutex> lk(SessionHostsMutex());
    const std::vector<std::string>& hosts = SessionHosts();
    return std::find(hosts.begin(), hosts.end(), h) != hosts.end();
}

bool IsDownloadUri(const std::string& uri)
{
    if (uri.empty()) return false;
    // 只取路径部分（丢掉 scheme/host/query/fragment 的干扰）。
    size_t se = uri.find("://");
    size_t ps = (se == std::string::npos) ? 0 : uri.find('/', se + 3);
    if (ps == std::string::npos) return false;   // 没有路径 → 不可能是下载端点
    std::string path = uri.substr(ps);

    // 实测各镜像的下载前缀：/dl/<id>（主站）、/download/…。
    // 注意不能只看 "/dl" 子串命中，否则 /downloads/xxx 之外的路径（如 /dle）也会误判，
    // 因此统一按「段」匹配。
    static const char* const kSegments[] = { "/dl/", "/download/" };
    for (const char* seg : kSegments) {
        size_t at = path.find(seg);
        while (at != std::string::npos) {
            // 必须是路径里的完整一段：前面是串首或 '/'
            if (at == 0 || path[at - 1] == '/') return true;
            at = path.find(seg, at + 1);
        }
    }
    return false;
}

} // namespace zlib
} // namespace pb

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

// 「能不能直接导航过去」的形态检查（不含 host 白名单）。
// 只用于「后台探测已经跟到重定向尽头」的最终地址：那个地址是我们自己探出来的，
// 不是导航页塞的，所以不套 IsTrustedHost；它的 host 会在导航前记进会话放行表
// （pb::zlib::AddSessionHostFromUrl），证书错误才不会被误杀。
static bool IsNavigableHttpRoot(const std::string& rawUrl) {
    std::string u = TrimAscii(rawUrl);
    if (u.empty()) return false;
    if (u.find('#') != std::string::npos || u.find('?') != std::string::npos) return false;
    std::string host, path; bool https = false;
    if (!SplitUrl(u, host, path, https)) return false;
    if (LowerAscii(host).find(' ') != std::string::npos) return false;
    if (path != "/" && !path.empty()) return false;
    return host.find('.') != std::string::npos;
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
    // SEC-01：host 必须属于 Z-Library 域名族，否则「导航页塞进来的任意地址」也能当线路
    if (!pb::zlib::IsTrustedHost(LowerAscii(host))) return false;
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
    if (!SplitUrl(url, host, path, https)) {
        // 探测失败在镜像探测里是常态（死线路就是靠这里筛掉的），记 Debug 不记 Warn，
        // 免得每次进站都往日志里灌几十行；汇总失败在 ProbeAndRank 记 Warn。
        pb::LogDebug("zlib", "probe: invalid mirror url: " + url);
        st.kind = ZMK_FAIL;
        return st;
    }

    HINTERNET hS = WinHttpOpen(L"PB/2.1 (mirror-probe)", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY, nullptr, nullptr, 0);
    if (!hS) {
        pb::LogDebug("zlib", "probe: WinHttpOpen failed: " + url);
        st.kind = ZMK_FAIL;
        return st;
    }
    // 超时必须显式设：WinHTTP 的 resolve 默认是 0 = 无限等待（下载线程踩过同一个坑）
    WinHttpSetTimeouts(hS, 3000, 4000, 4000, 6000);
    HINTERNET hC = WinHttpConnect(hS, ToWide(host).c_str(), https ? 443 : 80, 0);
    if (!hC) {
        WinHttpCloseHandle(hS);
        pb::LogDebug("zlib", "probe: connect failed: " + url);
        st.kind = ZMK_FAIL;
        return st;
    }
    HINTERNET hR = WinHttpOpenRequest(hC, L"GET", ToWide(path).c_str(), nullptr, nullptr, nullptr,
                                      https ? WINHTTP_FLAG_SECURE : 0);
    if (!hR) {
        WinHttpCloseHandle(hC); WinHttpCloseHandle(hS);
        pb::LogDebug("zlib", "probe: open request failed: " + url);
        st.kind = ZMK_FAIL;
        return st;
    }

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
        pb::LogDebug("zlib", "probe: request timed out / no response (" +
                                 std::to_string(st.ms) + " ms): " + url);
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
    if (!hS) {
        pb::LogWarn("zlib", "mirror fetch: WinHttpOpen failed: " + url);
        return "";
    }
    WinHttpSetTimeouts(hS, 10000, 10000, 10000, 20000);  // 镜像列表抓取同样不能无限等
    HINTERNET hC = WinHttpConnect(hS, toW(host).c_str(), https ? 443 : 80, 0);
    if (!hC) {
        WinHttpCloseHandle(hS);
        pb::LogWarn("zlib", "mirror fetch: connect failed: " + url);
        return "";
    }
    HINTERNET hR = WinHttpOpenRequest(hC, L"GET", toW(path).c_str(), nullptr, nullptr, nullptr, https ? WINHTTP_FLAG_SECURE : 0);
    if (!hR) {
        WinHttpCloseHandle(hC); WinHttpCloseHandle(hS);
        pb::LogWarn("zlib", "mirror fetch: open request failed: " + url);
        return "";
    }
    if (!WinHttpSendRequest(hR, nullptr, 0, nullptr, 0, 0, 0) || !WinHttpReceiveResponse(hR, nullptr))
    {
        WinHttpCloseHandle(hR); WinHttpCloseHandle(hC); WinHttpCloseHandle(hS);
        pb::LogWarn("zlib", "mirror fetch: no response: " + url);
        return "";
    }
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
        pb::LogWarn("zlib", "mirror list unavailable; keeping " +
                                std::to_string(m_mirrors.size()) + " fallback mirror(s)");
        json r; r["mirrors"] = m_mirrors; r["current"] = m_currentMirror; return r;
    }

    std::vector<std::string> found;
    auto add = [&](const std::string& raw) {
        std::string u = TrimAscii(raw);
        if (!IsMirrorCandidate(u)) {
            // SEC-01：导航页给的地址必须过 host 白名单，不合格直接丢弃（旧行为是"先探再说"，
            // 于是一个被投毒的导航页就能把任意站点排进第一顺位）。留一条 DEBUG 便于排障。
            const std::string host = pb::zlib::HostFromUrl(u);
            if (!host.empty()) pb::LogDebug("zlib", "mirror candidate dropped (untrusted host): " + host);
            return;
        }
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
        // 证书错误只对「可信域名族 或 本次会话自己导航过的 host」放行：
        // 名单里的线路 + 探测到的重定向终点都要先登记（见 ZLibraryMirror.h）。
        pb::zlib::AddSessionHostFromUrl(pending);
        pb::zlib::AddSessionHostFromUrl(url);
        if (m_host && m_host->GetWebView())
            m_host->GetWebView()->Navigate(ToWide(url).c_str());
        m_bridge->EmitEvent("zlib:mirrorChanged", GetMirrorInfo());
    }
    return GetMirrorInfo();
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
// 注意：这里【不】套 IsMirrorCandidate 的 host 白名单 —— finalUrl 是我们自己探测
// 出来的重定向终点（常常是无法预先枚举的中转域），导航前会由调用方记进
// pb::zlib::AddSessionHostFromUrl 的会话放行表。
std::string ZLibraryService::MirrorNavigateUrlLocked(int index) {
    if (index < 0 || index >= (int)m_mirrors.size()) return "";
    if (index < (int)m_mirrorStats.size()) {
        const std::string& f = m_mirrorStats[index].finalUrl;
        if (!f.empty() && IsNavigableHttpRoot(f)) return f;
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
        // PB-016-B：逐条探测失败只记 Debug；这里才是用户能感知的结论。
        bool anyApp = false;
        for (const auto& s : m_mirrorStats) {
            if (s.kind == ZMK_ZLIB_APP) { anyApp = true; break; }
        }
        if (!anyApp) {
            pb::LogWarn("zlib", "no working Z-Library mirror after probing " +
                                    std::to_string(m_mirrorStats.size()) + " mirror(s)");
        }
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
                // 下载文档（URL 命中下载端点）不算"进站页面"：它的 200/404 与线路
                // 可用性无关，判定它只会把一次下载误当成"线路挂了"。
                if (m_navIsDownload || pb::zlib::IsDownloadUri(m_docUri)) {
                    m_navIsDownload = false;
                    return S_OK;
                }
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
    pb::LogWarn("zlib", "mirror navigation failed (HTTP " + std::to_string(m_lastDocStatus) +
                            "), advancing to next mirror");
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
        pb::zlib::AddSessionHostFromUrl(pending);
        pb::zlib::AddSessionHostFromUrl(nextUrl);
        wv->Navigate(ToWide(nextUrl).c_str());
        // 这里【不】发 mirrorChanged：遮罩要留到这条线路真的出结果，否则又是一次白屏干等
        return;
    }
    m_entryNav = false;
    m_probePause = false;
    pb::LogError("zlib", "all mirrors failed to load");
    m_bridge->EmitEvent("zlib:allMirrorsFailed", json::object());
    m_bridge->EmitEvent("zlib:mirrorChanged", GetMirrorInfo());
}

