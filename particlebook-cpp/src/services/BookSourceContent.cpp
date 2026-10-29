#include "BookSourceService.h"
#include "utils/encoding.h"
#include "utils/fnv1a.h"
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
#include <chrono>
#include <sstream>
#include <filesystem>
#include <memory>

#pragma comment(lib, "winhttp.lib")

#include "BookSourceInternal.h"

json BookSourceService::GetBookInfo(int sourceId, const std::string& bookUrl)
{
    json source = m_db->GetBookSource(sourceId);
    if (source.is_null()) {
        pb::LogWarn("booksource", "bookInfo: source " + std::to_string(sourceId) + " not found");
        return json::object();
    }

    std::string html = FetchUrl(bookUrl);
    if (html.empty()) {
        pb::LogWarn("booksource", "bookInfo: no response: " + LogUrl(bookUrl));
        return json::object();
    }

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
    if (source.is_null()) {
        pb::LogWarn("booksource", "toc: source " + std::to_string(sourceId) + " not found");
        return json::array();
    }

    std::string html = FetchUrl(tocUrl);
    if (html.empty()) {
        pb::LogWarn("booksource", "toc: no response: " + LogUrl(tocUrl));
        return json::array();
    }

    auto rules = source.value("ruleToc", json::object());
    std::string listSelector = rules.value("chapterList", "");
    std::string nameRule = rules.value("chapterName", "");
    std::string urlRule = rules.value("chapterUrl", "");

    if (listSelector.empty() || nameRule.empty() || urlRule.empty()) {
        pb::LogWarn("booksource", "toc: source " + std::to_string(sourceId) +
                                      " has incomplete ruleToc (chapterList/chapterName/chapterUrl)");
        return json::array();
    }

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
                                     const std::string& bookName, const std::string& format,
                                     std::string& outErrorCode)
{
    (void)format;  // 见头文件：仅实现 .txt 组装
    outErrorCode.clear();

    // 所有失败都必须发出终止事件，否则 UI 会一直停在「下载中」
    // （此前五处提前 return 都不发事件，error 分支永远不会触发）。
    // error 是机器可读码，由前端映射成本地化文案。
    auto fail = [&](const char* code, int current, int total) -> int {
        outErrorCode = code;
        // PB-016-B：下载失败是用户最常报的「为什么没下来」，错误码 + 书源 +
        // 书名进本地日志；URL 由 LogService 脱敏后写入，不上传。
        pb::LogError("booksource",
                     std::string("download failed: ") + code +
                         " (source=" + std::to_string(sourceId) +
                         ", book=" + bookName + ", url=" + LogUrl(bookUrl) + ")");
        m_bridge->EmitEvent("bookSource:downloadProgress",
                            {{"status", "error"},
                             {"error", code},
                             {"bookId", -1},
                             {"current", current},
                             {"total", total}});
        return -1;
    };
    // 阶段状态：目录获取 / 组装 / 导入都可能耗时数十秒，用户需要反馈。
    auto stage = [&](const char* st, int current, int total) {
        m_bridge->EmitEvent("bookSource:downloadProgress",
                            {{"status", st}, {"current", current}, {"total", total}});
    };

    // Get chapter list
    json source = m_db->GetBookSource(sourceId);
    if (source.is_null()) return fail("source_not_found", 0, 0);

    stage("fetching_toc", 0, 0);
    json info = GetBookInfo(sourceId, bookUrl);
    std::string tocUrl = info.value("tocUrl", bookUrl);
    json chapters = GetChapterList(sourceId, tocUrl);

    if (chapters.empty()) return fail("no_chapters", 0, 0);

    // ── 章节并发下载 ───────────────────────────────────────────────────
    // 共享状态放堆上（shared_ptr）：下载有总超时，超时后本函数会提前返回，而后台
    // 任务可能还在跑 —— 若继续用「栈上变量 + 引用捕获」，那些任务就会写进已销毁的
    // 栈帧（UAF）。contents/names 按下标写，互不重叠。
    struct ChapterFetchState {
        std::mutex mutex;
        std::condition_variable cv;
        std::vector<std::string> contents;
        std::vector<std::string> names;
        int completed = 0;
        bool cancelled = false;
    };
    const int totalChapters = static_cast<int>(chapters.size());
    auto state = std::make_shared<ChapterFetchState>();
    state->contents.resize(static_cast<size_t>(totalChapters));
    state->names.resize(static_cast<size_t>(totalChapters));

    // 规则只取一次（原来每个章节都从 json 里重取一遍 ruleContent）。
    const std::string contentSelector =
        source.value("ruleContent", json::object()).value("content", "body");

    const auto fetchChapter = [this, state, contentSelector, totalChapters](
                                  int idx, const std::string& url) {
        {
            std::lock_guard<std::mutex> lock(state->mutex);
            if (state->cancelled) return;
        }
        const std::string chapterHtml = FetchUrl(url);
        const std::string chapterText = ExtractText(chapterHtml, contentSelector);

        int done = 0;
        std::string chapterName;
        {
            std::lock_guard<std::mutex> lock(state->mutex);
            if (state->cancelled) return;
            state->contents[static_cast<size_t>(idx)] = chapterText;
            done = ++state->completed;
            chapterName = state->names[static_cast<size_t>(idx)];
        }
        state->cv.notify_all();

        json progress;
        progress["status"] = "downloading";
        progress["current"] = done;
        progress["total"] = totalChapters;
        progress["chapterName"] = chapterName.empty() ? std::string("Unknown") : chapterName;
        m_bridge->EmitEvent("bookSource:downloadProgress", progress);
    };

    // Submit download jobs to thread pool
    for (int i = 0; i < totalChapters; i++) {
        const std::string url = chapters[i].value("url", "");
        state->names[static_cast<size_t>(i)] = chapters[i].value("name", "Unknown");
        {
            std::lock_guard<std::mutex> lock(m_jobMutex);
            m_jobs.push({[fetchChapter, i, url]() { fetchChapter(i, url); }});
        }
        m_jobCV.notify_one();
    }

    // ── 等待完成：有总超时，绝不无限忙等 ─────────────────────────────
    // 原来 `while (completed < totalChapters) Sleep(100);`：一条章节的请求卡住
    // （书源侧 HTTP 以前还没有超时）就永远退出不了，还会把线程池的 2~4 个 worker
    // 全占死 —— 此后再也下载不了任何书。预算 = 30s 基础 + 每章 15s，上限 10 分钟。
    const long long budgetMs = std::min<long long>(
        600000, 30000 + static_cast<long long>(totalChapters) * 15000);
    bool timedOut = false;
    {
        std::unique_lock<std::mutex> lock(state->mutex);
        timedOut = !state->cv.wait_for(
            lock, std::chrono::milliseconds(budgetMs),
            [&state, totalChapters] { return state->completed >= totalChapters; });
        if (timedOut) state->cancelled = true;   // 让还在跑的章节尽早退出
    }
    if (timedOut) {
        pb::LogError("booksource", "download timed out after " + std::to_string(budgetMs) +
                                       "ms (" + std::to_string(state->completed) + "/" +
                                       std::to_string(totalChapters) + " chapters): " +
                                       bookName);
        return fail("network_error", state->completed, totalChapters);
    }

    // ── Assemble into a text file & import to the bookshelf ─────────
    // Chapters live only in memory here — without persisting + importing, a
    // "download" finishes but nothing ever appears in the library.
    stage("assembling", totalChapters, totalChapters);
    std::string text;
    text.reserve(64 * 1024);
    for (int i = 0; i < totalChapters; i++) {
        // Fallback chapter title — localized per UI language
        std::string fallbackName = App::Instance().GetLanguage() == "en"
            ? "Chapter " + std::to_string(i + 1)
            : "第" + std::to_string(i + 1) + "章";
        std::string name = chapters[i].value("name", fallbackName);
        text += "# " + name + "\n\n" + state->contents[static_cast<size_t>(i)] + "\n\n";
    }
    if (text.empty()) return fail("empty_content", totalChapters, totalChapters);

    // Sanitize file name
    std::string safeName;
    for (char c : bookName) {
        if (c == '\\' || c == '/' || c == ':' || c == '*' || c == '?' ||
            c == '"' || c == '<' || c == '>' || c == '|') continue;
        safeName += c;
    }
    if (safeName.empty()) safeName = "book";
    // 同名不同书（不同书源/不同站点）会互相覆盖：库里 A 书的 file_path 内容
    // 被 B 书替换（review · B-12）。文件名拼上 bookUrl 的 FNV-1a 短哈希，
    // 与 _pb_files / docx 缓存同一套做法，保证「同源同名稳定、不同源不撞」。
    if (safeName.size() > 100) safeName.resize(100);
    safeName += "_" + std::to_string(pb::Fnv1a64(bookUrl.data(), bookUrl.size())) + ".txt";

    std::string dir = App::Instance().UserDataPath() + "/booksource";
    std::filesystem::create_directories(pb::Utf8ToWide(dir));

    std::string filePath = dir + "\\" + safeName;
    HANDLE hFile = CreateFileW(pb::Utf8ToWide(filePath).c_str(), GENERIC_WRITE, 0, nullptr,
                               CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (hFile == INVALID_HANDLE_VALUE) return fail("write_failed", totalChapters, totalChapters);
    DWORD written = 0;
    WriteFile(hFile, text.data(), (DWORD)text.size(), &written, nullptr);
    CloseHandle(hFile);
    if (written != text.size()) return fail("write_failed", totalChapters, totalChapters);

    // Import into the library (reuses the normal import path & dedup)
    stage("importing", totalChapters, totalChapters);
    json params;
    params["paths"] = json::array({filePath});
    json imported = m_bridge->InvokeMethod("book:import", params);
    int bookId = -1;
    if (imported.is_array() && !imported.empty()) {
        bookId = imported[0].value("id", -1);
    }

    if (bookId > 0) {
        pb::LogInfo("booksource", "download complete: source=" + std::to_string(sourceId) +
                                      " book=" + bookName + " id=" + std::to_string(bookId));
        m_bridge->EmitEvent("bookSource:downloadProgress",
                            {{"status", "done"},
                             {"bookId", bookId},
                             {"current", totalChapters},
                             {"total", totalChapters}});
        // 导入是 C++ 内部用 InvokeMethod 发起的，不会经过 BridgeServer 里那个
        // "JS 发起 book:import 后刷新"的钩子，因此必须自己通知渲染层，否则下载完成
        // 的书要等手动刷新或重启才出现在书架上。
        m_bridge->EmitEvent("library:changed", json::object());
    } else {
        return fail("import_failed", totalChapters, totalChapters);
    }
    return bookId;
}

// ── Thread Pool ───────────────────────────────────────────────────

