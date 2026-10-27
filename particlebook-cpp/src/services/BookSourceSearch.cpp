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

static std::string PercentEncode(const std::string& s)
{
    static const char hex[] = "0123456789ABCDEF";
    std::string out;
    out.reserve(s.size() * 3);
    for (unsigned char c : s) {
        if (isalnum(c) || c == '-' || c == '_' || c == '.' || c == '~') {
            out += (char)c;                 // RFC 3986 unreserved
        } else if (c == ' ') {
            out += "%20";
        } else {
            out += '%'; out += hex[c >> 4]; out += hex[c & 0xF];
        }
    }
    return out;
}

std::string BookSourceService::BuildSearchUrl(const json& source, const std::string& keyword, int page)
{
    std::string url = source.value("searchUrl", "");
    if (url.empty()) {
        url = source.value("bookSourceUrl", "");
    }

    // Replace {keyword} and {page} placeholders
    std::string kw = PercentEncode(keyword);

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
    if (source.is_null()) {
        pb::LogWarn("booksource", "search: source " + std::to_string(sourceId) + " not found");
        return json::array();
    }

    std::string url = BuildSearchUrl(source, keyword, page);
    std::string html = FetchUrl(url);
    if (html.empty()) {
        // 关键词本身不写日志（可能是用户的私人搜索词），只记长度。
        pb::LogWarn("booksource", "search: no response from source " + std::to_string(sourceId) +
                                      " (keyword " + std::to_string(keyword.size()) + " bytes)");
        return json::array();
    }

    auto rules = source.value("ruleSearch", json::object());
    std::string listSelector = rules.value("bookList", "");
    if (listSelector.empty()) {
        pb::LogWarn("booksource", "search: source " + std::to_string(sourceId) +
                                      " has no ruleSearch.bookList");
        return json::array();
    }

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

