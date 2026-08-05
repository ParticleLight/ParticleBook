#include "BookSourceService.h"
#include "services/DatabaseService.h"
#include "BridgeServer.h"
#include "App.h"
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <winhttp.h>
#include <algorithm>
#include <cctype>
#include <sstream>
#include <filesystem>
#include <memory>

#pragma comment(lib, "winhttp.lib")

static std::wstring Utf8ToWide(const std::string& s) {
    if (s.empty()) return L"";
    int len = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), nullptr, 0);
    if (len <= 0) return L"";
    std::wstring w(len, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), &w[0], len);
    return w;
}

BookSourceService::BookSourceService(std::shared_ptr<DatabaseService> db, std::shared_ptr<BridgeServer> bridge)
    : m_db(std::move(db)), m_bridge(std::move(bridge))
{
    // Start worker threads for downloads
    unsigned n = std::thread::hardware_concurrency();
    if (n < 2) n = 2;
    if (n > 4) n = 4;
    for (unsigned i = 0; i < n; i++)
        m_threads.emplace_back(&BookSourceService::WorkerThread, this);
}

BookSourceService::~BookSourceService()
{
    {
        std::lock_guard<std::mutex> lock(m_jobMutex);
        m_running = false;
    }
    m_jobCV.notify_all();
    for (auto& t : m_threads) {
        if (t.joinable()) t.join();
    }
}

// ── HTTP ──────────────────────────────────────────────────────────

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
    if (wlen <= 0) return "";
    std::wstring whost(wlen, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, host.c_str(), (int)host.size(), &whost[0], wlen);

    wlen = MultiByteToWideChar(CP_UTF8, 0, path.c_str(), (int)path.size(), nullptr, 0);
    if (wlen <= 0) return "";
    std::wstring wpath(wlen, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, path.c_str(), (int)path.size(), &wpath[0], wlen);

    HINTERNET hSession = WinHttpOpen(L"ParticleBook/1.9",
        WINHTTP_ACCESS_TYPE_DEFAULT_PROXY, nullptr, nullptr, 0);
    if (!hSession) return "";

    HINTERNET hConnect = WinHttpConnect(hSession, whost.c_str(),
        https ? INTERNET_DEFAULT_HTTPS_PORT : INTERNET_DEFAULT_HTTP_PORT, 0);
    if (!hConnect) { WinHttpCloseHandle(hSession); return ""; }

    HINTERNET hRequest = WinHttpOpenRequest(hConnect, L"GET", wpath.c_str(),
        nullptr, nullptr, nullptr, https ? WINHTTP_FLAG_SECURE : 0);
    if (!hRequest) { WinHttpCloseHandle(hConnect); WinHttpCloseHandle(hSession); return ""; }

    if (!WinHttpSendRequest(hRequest, nullptr, 0, nullptr, 0, 0, 0)) {
        WinHttpCloseHandle(hRequest); WinHttpCloseHandle(hConnect); WinHttpCloseHandle(hSession);
        return "";
    }
    if (!WinHttpReceiveResponse(hRequest, nullptr)) {
        WinHttpCloseHandle(hRequest); WinHttpCloseHandle(hConnect); WinHttpCloseHandle(hSession);
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

std::string BookSourceService::BuildSearchUrl(const json& source, const std::string& keyword, int page)
{
    std::string url = source.value("searchUrl", "");
    if (url.empty()) {
        url = source.value("bookSourceUrl", "");
    }

    // Replace {keyword} and {page} placeholders
    std::string kw = keyword;
    // URL-encode keyword (basic)
    for (size_t i = 0; i < kw.size(); i++) {
        if (kw[i] == ' ') { kw.replace(i, 1, "%20"); i += 2; }
    }

    auto replaceAll = [](std::string s, const std::string& from, const std::string& to) {
        size_t pos = 0;
        while ((pos = s.find(from, pos)) != std::string::npos) {
            s.replace(pos, from.length(), to);
            pos += to.length();
        }
        return s;
    };

    url = replaceAll(url, "{keyword}", kw);
    url = replaceAll(url, "{page}", std::to_string(page));
    url = replaceAll(url, "{Key}", kw);

    return url;
}

json BookSourceService::SearchOne(int sourceId, const std::string& keyword, int page)
{
    json source = m_db->GetBookSource(sourceId);
    if (source.is_null()) return json::array();

    std::string url = BuildSearchUrl(source, keyword, page);
    std::string html = FetchUrl(url);
    if (html.empty()) return json::array();

    auto rules = source.value("ruleSearch", json::object());
    std::string listSelector = rules.value("bookList", "");
    if (listSelector.empty()) return json::array();

    // Extract book list items
    auto ps = ParseSelector(listSelector);
    std::string tag = ps.tag.empty() ? "div" : ps.tag;
    std::string cls = ps.cls;

    json results = json::array();
    size_t pos = 0;
    std::string openTag = "<" + tag;

    while ((pos = html.find(openTag, pos)) != std::string::npos) {
        size_t tagEnd = html.find('>', pos);
        if (tagEnd == std::string::npos) break;
        std::string tagStr = html.substr(pos, tagEnd - pos + 1);

        // Check if class matches
        if (!cls.empty() && tagStr.find(cls) == std::string::npos) {
            pos = tagEnd + 1;
            continue;
        }

        // Find closing tag
        std::string closeTag = "</" + tag + ">";
        size_t contentEnd = html.find(closeTag, tagEnd);
        if (contentEnd == std::string::npos) { pos = tagEnd + 1; continue; }

        std::string elementHtml = html.substr(pos, contentEnd - pos + closeTag.size());

        json item;
        std::string nameRule = rules.value("name", "");
        std::string authorRule = rules.value("author", "");
        std::string urlRule = rules.value("bookUrl", "");
        std::string coverRule = rules.value("coverUrl", "");

        if (!nameRule.empty()) item["bookName"] = ExtractText(elementHtml, nameRule);
        if (!authorRule.empty()) item["author"] = ExtractText(elementHtml, authorRule);
        if (!urlRule.empty()) {
            std::string bookUrl = ExtractAttr(elementHtml, urlRule);
            item["bookUrl"] = ResolveUrl(url, bookUrl);
        }
        if (!coverRule.empty()) {
            std::string coverUrl = ExtractAttr(elementHtml, coverRule);
            item["coverUrl"] = coverUrl.empty() ? "" : ResolveUrl(url, coverUrl);
        }

        if (!item.value("bookName", "").empty()) {
            results.push_back(item);
        }

        pos = contentEnd + closeTag.size();
    }

    return results;
}

json BookSourceService::SearchAll(const std::string& keyword, int page)
{
    json results = json::array();
    auto sources = m_db->GetBookSources();
    for (const auto& src : sources) {
        if (!src.value("enabled", true)) continue;
        auto srcResults = SearchOne(src.value("id", 0), keyword, page);
        for (const auto& r : srcResults) {
            json item = r;
            item["sourceId"] = src.value("id", 0);
            item["sourceName"] = src.value("bookSourceName", "Unknown");
            results.push_back(item);
        }
    }
    return results;
}

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

json BookSourceService::GetBookInfo(int sourceId, const std::string& bookUrl)
{
    json source = m_db->GetBookSource(sourceId);
    if (source.is_null()) return json::object();

    std::string html = FetchUrl(bookUrl);
    if (html.empty()) return json::object();

    auto rules = source.value("ruleBookInfo", json::object());
    json info;
    info["bookUrl"] = bookUrl;

    if (!rules.value("name", "").empty()) info["bookName"] = ExtractText(html, rules["name"]);
    if (!rules.value("author", "").empty()) info["author"] = ExtractText(html, rules["author"]);
    if (!rules.value("coverUrl", "").empty()) {
        std::string cu = ExtractAttr(html, rules["coverUrl"]);
        if (!cu.empty()) info["coverUrl"] = ResolveUrl(bookUrl, cu);
    }
    if (!rules.value("intro", "").empty()) info["intro"] = ExtractText(html, rules["intro"]);
    if (!rules.value("tocUrl", "").empty()) {
        std::string toc = ExtractAttr(html, rules["tocUrl"]);
        if (!toc.empty()) info["tocUrl"] = ResolveUrl(bookUrl, toc);
    }

    return info;
}

json BookSourceService::GetChapterList(int sourceId, const std::string& tocUrl)
{
    json source = m_db->GetBookSource(sourceId);
    if (source.is_null()) return json::array();

    std::string html = FetchUrl(tocUrl);
    if (html.empty()) return json::array();

    auto rules = source.value("ruleToc", json::object());
    std::string listSelector = rules.value("chapterList", "");
    std::string nameRule = rules.value("chapterName", "");
    std::string urlRule = rules.value("chapterUrl", "");

    if (listSelector.empty() || nameRule.empty() || urlRule.empty()) return json::array();

    auto ps = ParseSelector(listSelector);
    std::string tag = ps.tag.empty() ? "li" : ps.tag;

    json chapters = json::array();
    size_t pos = 0;
    std::string openTag = "<" + tag;

    while ((pos = html.find(openTag, pos)) != std::string::npos) {
        size_t tagEnd = html.find('>', pos);
        if (tagEnd == std::string::npos) break;

        if (!ps.cls.empty()) {
            std::string tagStr = html.substr(pos, tagEnd - pos + 1);
            if (tagStr.find(ps.cls) == std::string::npos) { pos = tagEnd + 1; continue; }
        }

        std::string closeTag = "</" + tag + ">";
        size_t contentEnd = html.find(closeTag, tagEnd);
        if (contentEnd == std::string::npos) { pos = tagEnd + 1; continue; }

        std::string elementHtml = html.substr(pos, contentEnd - pos + closeTag.size());

        json chapter;
        chapter["name"] = ExtractText(elementHtml, nameRule);
        std::string chUrl = ExtractAttr(elementHtml, urlRule);
        chapter["url"] = chUrl.empty() ? "" : ResolveUrl(tocUrl, chUrl);

        if (!chapter["name"].empty()) chapters.push_back(chapter);
        pos = contentEnd + closeTag.size();
    }

    return chapters;
}

// ── Download ──────────────────────────────────────────────────────

int BookSourceService::DownloadBook(int sourceId, const std::string& bookUrl,
                                     const std::string& bookName, const std::string& format)
{
    // Get chapter list
    json source = m_db->GetBookSource(sourceId);
    if (source.is_null()) return -1;

    json info = GetBookInfo(sourceId, bookUrl);
    std::string tocUrl = info.value("tocUrl", bookUrl);
    json chapters = GetChapterList(sourceId, tocUrl);

    if (chapters.empty()) return -1;

    // Download chapters with progress
    int totalChapters = static_cast<int>(chapters.size());
    std::vector<std::string> chapterContents(totalChapters);
    std::atomic<int> completed{0};
    std::atomic<bool> cancelled{false};

    auto downloadChapter = [&](int idx, const std::string& url) {
        if (cancelled) return;
        std::string chHtml = FetchUrl(url);
        auto contentRule = source.value("ruleContent", json::object());
        std::string contentSelector = contentRule.value("content", "body");
        chapterContents[idx] = ExtractText(chHtml, contentSelector);

        int done = ++completed;
        json progress;
        progress["status"] = "downloading";
        progress["current"] = done;
        progress["total"] = totalChapters;
        progress["chapterName"] = chapters[idx].value("name", "Unknown");
        m_bridge->EmitEvent("bookSource:downloadProgress", progress);
    };

    // Submit download jobs to thread pool
    for (int i = 0; i < totalChapters; i++) {
        std::string url = chapters[i].value("url", "");
        {
            std::lock_guard<std::mutex> lock(m_jobMutex);
            m_jobs.push({[i, url, downloadChapter]() { downloadChapter(i, url); }});
        }
        m_jobCV.notify_one();
    }

    // Wait for completion
    while (completed < totalChapters) {
        Sleep(100);
    }

    // ── Assemble into a text file & import to the bookshelf ─────────
    // Chapters live only in memory here — without persisting + importing, a
    // "download" finishes but nothing ever appears in the library.
    std::string text;
    text.reserve(64 * 1024);
    for (int i = 0; i < totalChapters; i++) {
        std::string name = chapters[i].value("name", "第" + std::to_string(i + 1) + "章");
        text += "# " + name + "\n\n" + chapterContents[i] + "\n\n";
    }
    if (text.empty()) return -1;

    // Sanitize file name
    std::string safeName;
    for (char c : bookName) {
        if (c == '\\' || c == '/' || c == ':' || c == '*' || c == '?' ||
            c == '"' || c == '<' || c == '>' || c == '|') continue;
        safeName += c;
    }
    if (safeName.empty()) safeName = "book";
    safeName += ".txt";

    std::string dir = App::Instance().UserDataPath() + "/booksource";
    std::filesystem::create_directories(Utf8ToWide(dir));

    std::string filePath = dir + "\\" + safeName;
    HANDLE hFile = CreateFileW(Utf8ToWide(filePath).c_str(), GENERIC_WRITE, 0, nullptr,
                               CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (hFile == INVALID_HANDLE_VALUE) return -1;
    DWORD written = 0;
    WriteFile(hFile, text.data(), (DWORD)text.size(), &written, nullptr);
    CloseHandle(hFile);
    if (written != text.size()) return -1;

    // Import into the library (reuses the normal import path & dedup)
    json params;
    params["paths"] = json::array({filePath});
    json imported = m_bridge->InvokeMethod("book:import", params);
    int bookId = -1;
    if (imported.is_array() && !imported.empty()) {
        bookId = imported[0].value("id", -1);
    }

    m_bridge->EmitEvent("bookSource:downloadProgress",
                        {{"status", bookId > 0 ? "done" : "error"},
                         {"bookId", bookId},
                         {"current", totalChapters},
                         {"total", totalChapters}});
    return bookId;
}

// ── Thread Pool ───────────────────────────────────────────────────

void BookSourceService::WorkerThread()
{
    while (true) {
        Job job;
        {
            std::unique_lock<std::mutex> lock(m_jobMutex);
            m_jobCV.wait(lock, [this]{ return !m_jobs.empty() || !m_running; });
            if (!m_running) return;
            job = std::move(m_jobs.front());
            m_jobs.pop();
        }
        job.work();
    }
}

// ── Handler Registration ─────────────────────────────────────────

class BridgeServer;
void RegisterBookSourceHandlers(BridgeServer* bridge, std::shared_ptr<BookSourceService> svc)
{
    bridge->RegisterMethod("bookSource:search", [svc](const json& p) -> json {
        return svc->SearchAll(p.value("keyword", ""), p.value("page", 1));
    });
    bridge->RegisterMethod("bookSource:searchOne", [svc](const json& p) -> json {
        return svc->SearchOne(p["sourceId"].get<int>(), p.value("keyword", ""), p.value("page", 1));
    });
    bridge->RegisterMethod("bookSource:getBookInfo", [svc](const json& p) -> json {
        return svc->GetBookInfo(p["sourceId"].get<int>(), p["bookUrl"].get<std::string>());
    });
    bridge->RegisterMethod("bookSource:getChapterList", [svc](const json& p) -> json {
        return svc->GetChapterList(p["sourceId"].get<int>(), p["tocUrl"].get<std::string>());
    });
    bridge->RegisterMethod("bookSource:download", [svc](const json& p) -> json {
        int sourceId = p["sourceId"].get<int>();
        std::string bookUrl = p["bookUrl"].get<std::string>();
        std::string bookName = p["bookName"].get<std::string>();
        std::string format = p.value("format", "txt");
        // Run the whole download off the UI thread — a large book takes minutes
        // and the previous synchronous handler froze the window. Capturing svc
        // (shared_ptr) keeps the service alive until the download finishes.
        std::thread([svc, sourceId, bookUrl, bookName, format]() {
            svc->DownloadBook(sourceId, bookUrl, bookName, format);
        }).detach();
        return json("started");
    });
}
