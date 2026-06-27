#pragma once
#include <string>
#include <vector>
#include <functional>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <queue>
#include "nlohmann/json.hpp"

using json = nlohmann::json;

class DatabaseService;
class BridgeServer;

class BookSourceService {
public:
    BookSourceService(DatabaseService* db, BridgeServer* bridge);
    ~BookSourceService();

    // Search
    json SearchAll(const std::string& keyword, int page = 1);
    json SearchOne(int sourceId, const std::string& keyword, int page = 1);

    // Book info & chapters
    json GetBookInfo(int sourceId, const std::string& bookUrl);
    json GetChapterList(int sourceId, const std::string& tocUrl);

    // Download with progress
    int DownloadBook(int sourceId, const std::string& bookUrl,
                     const std::string& bookName, const std::string& format);

private:
    // HTTP
    std::string FetchUrl(const std::string& url, const std::string& headers = "");

    // HTML extraction helpers (string-based, lightweight)
    std::string ExtractText(const std::string& html, const std::string& selector);
    std::string ExtractAttr(const std::string& html, const std::string& selector);
    std::vector<std::string> ExtractList(const std::string& html, const std::string& selector, const json& rules);
    std::string BuildSearchUrl(const json& source, const std::string& keyword, int page);
    std::string ResolveUrl(const std::string& base, const std::string& relative);

    // Parse CSS selector into (tag, class, id) components
    struct ParsedSelector { std::string tag; std::string cls; std::string id; std::string attr; };
    ParsedSelector ParseSelector(const std::string& sel);

    DatabaseService* m_db;
    BridgeServer* m_bridge;

    // Thread pool
    struct Job { std::function<void()> work; };
    std::vector<std::thread> m_threads;
    std::queue<Job> m_jobs;
    std::mutex m_jobMutex;
    std::condition_variable m_jobCV;
    bool m_running = true;
    void WorkerThread();
};
