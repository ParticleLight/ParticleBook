#include "BookSourceService.h"
#include "utils/encoding.h"
#include "services/DatabaseService.h"
#include "BridgeServer.h"
#include "App.h"
#include "platform/JobService.h"
#include "platform/LogService.h"
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <winhttp.h>
#include <algorithm>
#include <cctype>
#include <sstream>
#include <filesystem>
#include <memory>

#pragma comment(lib, "winhttp.lib")

#include "BookSourceInternal.h"

std::string BookSourceService::FetchUrl(const std::string& url, const std::string& headers)
{
    // Parse URL
    std::string host, path;
    bool https = false;
    size_t schemeEnd = url.find("://");
    if (schemeEnd != std::string::npos) {
        https = (url.substr(0, schemeEnd) == "https");
        size_t hostStart = schemeEnd + 3;
        size_t pathStart = url.find('/', hostStart);
        if (pathStart != std::string::npos) {
            host = url.substr(hostStart, pathStart - hostStart);
            path = url.substr(pathStart);
        } else {
            host = url.substr(hostStart);
            path = "/";
        }
    } else {
        host = url;
        path = "/";
    }

    int wlen = MultiByteToWideChar(CP_UTF8, 0, host.c_str(), (int)host.size(), nullptr, 0);
    if (wlen <= 0) {
        pb::LogWarn("booksource", "fetch: host UTF-8 decode failed: " + LogUrl(url));
        return "";
    }
    std::wstring whost(wlen, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, host.c_str(), (int)host.size(), &whost[0], wlen);

    wlen = MultiByteToWideChar(CP_UTF8, 0, path.c_str(), (int)path.size(), nullptr, 0);
    if (wlen <= 0) {
        pb::LogWarn("booksource", "fetch: path UTF-8 decode failed: " + LogUrl(url));
        return "";
    }
    std::wstring wpath(wlen, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, path.c_str(), (int)path.size(), &wpath[0], wlen);

    HINTERNET hSession = WinHttpOpen(L"ParticleBook/1.9",
        WINHTTP_ACCESS_TYPE_DEFAULT_PROXY, nullptr, nullptr, 0);
    if (!hSession) {
        pb::LogWarn("booksource", "fetch: WinHttpOpen failed: " + LogUrl(url));
        return "";
    }
    // 必须显式设超时：WinHTTP 的 resolve 超时默认是 0 = 无限等待。书源是用户自己
    // 配的第三方站点（死域名、黑洞 IP 很常见），没有超时就是「这条章节能把下载线程
    // 永久挂住」—— 参数与 Z-Library 下载侧一致（ZLibraryDownload.cpp）。
    WinHttpSetTimeouts(hSession, 10000, 10000, 10000, 30000);

    HINTERNET hConnect = WinHttpConnect(hSession, whost.c_str(),
        https ? INTERNET_DEFAULT_HTTPS_PORT : INTERNET_DEFAULT_HTTP_PORT, 0);
    if (!hConnect) {
        WinHttpCloseHandle(hSession);
        pb::LogWarn("booksource", "fetch: connect failed: " + LogUrl(url));
        return "";
    }

    HINTERNET hRequest = WinHttpOpenRequest(hConnect, L"GET", wpath.c_str(),
        nullptr, nullptr, nullptr, https ? WINHTTP_FLAG_SECURE : 0);
    if (!hRequest) {
        WinHttpCloseHandle(hConnect);
        WinHttpCloseHandle(hSession);
        pb::LogWarn("booksource", "fetch: WinHttpOpenRequest failed: " + LogUrl(url));
        return "";
    }

    if (!WinHttpSendRequest(hRequest, nullptr, 0, nullptr, 0, 0, 0)) {
        WinHttpCloseHandle(hRequest); WinHttpCloseHandle(hConnect); WinHttpCloseHandle(hSession);
        pb::LogWarn("booksource", "fetch: send failed: " + LogUrl(url));
        return "";
    }
    if (!WinHttpReceiveResponse(hRequest, nullptr)) {
        WinHttpCloseHandle(hRequest); WinHttpCloseHandle(hConnect); WinHttpCloseHandle(hSession);
        pb::LogWarn("booksource", "fetch: no response: " + LogUrl(url));
        return "";
    }

    std::string result;
    DWORD bytesRead = 0;
    char buf[8192];
    while (WinHttpReadData(hRequest, buf, sizeof(buf), &bytesRead) && bytesRead > 0) {
        result.append(buf, bytesRead);
    }

    WinHttpCloseHandle(hRequest);
    WinHttpCloseHandle(hConnect);
    WinHttpCloseHandle(hSession);
    return result;
}

// ── HTML Extraction ───────────────────────────────────────────────

// Find element matching selector and extract its text content
std::string BookSourceService::ExtractText(const std::string& html, const std::string& selector)
{
    auto ps = ParseSelector(selector);
    if (ps.tag.empty() && ps.cls.empty() && ps.id.empty()) return "";

    // Build search pattern
    std::string pattern;
    if (!ps.tag.empty()) {
        pattern = "<" + ps.tag;
        // Find this tag with matching class or id
        size_t pos = 0;
        while ((pos = html.find(pattern, pos)) != std::string::npos) {
            size_t tagEnd = html.find('>', pos);
            if (tagEnd == std::string::npos) break;
            std::string tagContent = html.substr(pos, tagEnd - pos + 1);
            bool match = true;
            if (!ps.cls.empty() && tagContent.find("class=") == std::string::npos) match = false;
            if (!ps.cls.empty() && tagContent.find(ps.cls) == std::string::npos) match = false;
            if (!ps.id.empty() && tagContent.find("id=") == std::string::npos) match = false;
            if (!ps.id.empty() && tagContent.find("\"" + ps.id + "\"") == std::string::npos) match = false;

            if (match) {
                size_t contentStart = tagEnd + 1;
                std::string closeTag = "</" + ps.tag + ">";
                size_t contentEnd = html.find(closeTag, contentStart);
                if (contentEnd != std::string::npos) {
                    std::string content = html.substr(contentStart, contentEnd - contentStart);
                    // Strip HTML tags from content
                    std::string text;
                    bool inTag = false;
                    for (char c : content) {
                        if (c == '<') inTag = true;
                        else if (c == '>') inTag = false;
                        else if (!inTag) text += c;
                    }
                    return text;
                }
            }
            pos = tagEnd;
        }
    }

    return "";
}

BookSourceService::ParsedSelector BookSourceService::ParseSelector(const std::string& sel)
{
    ParsedSelector ps;
    std::string s = sel;
    // Remove leading . or #
    // Extract @attr suffix
    size_t atPos = s.rfind('@');
    if (atPos != std::string::npos) {
        ps.attr = s.substr(atPos + 1);
        s = s.substr(0, atPos);
    }

    // Parse tag.class#id
    size_t dotPos = s.find('.');
    size_t hashPos = s.find('#');

    if (dotPos != std::string::npos) {
        if (hashPos != std::string::npos && hashPos > dotPos) {
            ps.tag = s.substr(0, dotPos);
            ps.cls = s.substr(dotPos + 1, hashPos - dotPos - 1);
            ps.id = s.substr(hashPos + 1);
        } else {
            if (dotPos == 0) { ps.cls = s.substr(1); }
            else { ps.tag = s.substr(0, dotPos); ps.cls = s.substr(dotPos + 1); }
        }
    } else if (hashPos != std::string::npos) {
        if (hashPos == 0) { ps.id = s.substr(1); }
        else { ps.tag = s.substr(0, hashPos); ps.id = s.substr(hashPos + 1); }
    } else {
        ps.tag = s;
    }

    return ps;
}

std::string BookSourceService::ResolveUrl(const std::string& base, const std::string& relative)
{
    if (relative.empty()) return base;
    if (relative.find("://") != std::string::npos) return relative;
    if (relative[0] == '/') {
        size_t schemeEnd = base.find("://");
        if (schemeEnd == std::string::npos) return relative;
        size_t hostEnd = base.find('/', schemeEnd + 3);
        if (hostEnd == std::string::npos) return base + relative;
        return base.substr(0, hostEnd) + relative;
    }
    size_t lastSlash = base.rfind('/');
    if (lastSlash == std::string::npos || lastSlash < 8) return base + "/" + relative;
    return base.substr(0, lastSlash + 1) + relative;
}

// ── Search ────────────────────────────────────────────────────────

// RFC 3986 percent-encoding over the UTF-8 byte stream. keyword arrives as
// UTF-8 from the frontend, so encoding each non-unreserved byte as %XX is a
// correct UTF-8 percent-encoding (Chinese book names become fully usable).
// This is the standard algorithm — no third-party dependency is warranted.
std::string BookSourceService::ExtractAttr(const std::string& html, const std::string& selector)
{
    auto ps = ParseSelector(selector);
    std::string attr = ps.attr.empty() ? "href" : ps.attr;

    if (ps.tag.empty()) {
        // Search for any element with this class/id
        std::string searchStr = ps.cls.empty() ? "" : "class=\"" + ps.cls + "\"";
        if (searchStr.empty() && !ps.id.empty()) searchStr = "id=\"" + ps.id + "\"";
        if (searchStr.empty()) return "";

        size_t pos = html.find(searchStr);
        if (pos == std::string::npos) return "";
        // Find containing tag
        size_t tagStart = html.rfind('<', pos);
        if (tagStart == std::string::npos) return "";
        size_t tagEnd = html.find('>', tagStart);
        if (tagEnd == std::string::npos) return "";
        std::string tagContent = html.substr(tagStart, tagEnd - tagStart);

        // Extract attribute
        std::string attrPattern = attr + "=\"";
        size_t attrPos = tagContent.find(attrPattern);
        if (attrPos == std::string::npos) {
            attrPattern = attr + "='";
            attrPos = tagContent.find(attrPattern);
        }
        if (attrPos != std::string::npos) {
            attrPos += attrPattern.length();
            char quote = tagContent[attrPos - 1];
            size_t attrEnd = tagContent.find(quote, attrPos);
            if (attrEnd != std::string::npos) return tagContent.substr(attrPos, attrEnd - attrPos);
        }
    }

    return "";
}

// ── Book Info & Chapters ──────────────────────────────────────────

