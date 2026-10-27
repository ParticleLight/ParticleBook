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

namespace {
// PB-020: book-source search hits the network. Run it on a JobService worker
// and resolve the original bridge promise when the job finishes.
void SubmitJsonJob(pb::JobService* jobs, const char* name,
                   std::function<json()> work,
                   std::function<void(json)> respond,
                   std::function<void(std::string)> reject)
{
    if (!jobs) { reject("job_service_unavailable"); return; }
    auto out = std::make_shared<json>(nullptr);
    const pb::JobId id = jobs->Submit(
        name,
        [work = std::move(work), out](pb::JobContext&) { *out = work(); },
        nullptr,
        [out, respond, reject](const pb::JobResult& r) {
            if (!r.ok) { reject(r.cancelled ? "cancelled" : r.error); return; }
            respond(*out);
        });
    if (id == pb::kInvalidJobId) reject("job_service_unavailable");
}
} // namespace

void RegisterBookSourceHandlers(BridgeServer* bridge, std::shared_ptr<BookSourceService> svc, pb::JobService* jobs)
{
    bridge->RegisterAsyncMethod("bookSource:search",
        [svc, jobs](const json& p, std::function<void(json)> respond, std::function<void(std::string)> reject) {
            const std::string keyword = p.value("keyword", "");
            const int page = p.value("page", 1);
            SubmitJsonJob(jobs, "bookSource:search",
                [svc, keyword, page] { return svc->SearchAll(keyword, page); },
                respond, reject);
        });
    bridge->RegisterAsyncMethod("bookSource:searchOne",
        [svc, jobs](const json& p, std::function<void(json)> respond, std::function<void(std::string)> reject) {
            const int sourceId = p.value("sourceId", 0);
            const std::string keyword = p.value("keyword", "");
            const int page = p.value("page", 1);
            SubmitJsonJob(jobs, "bookSource:searchOne",
                [svc, sourceId, keyword, page] { return svc->SearchOne(sourceId, keyword, page); },
                respond, reject);
        });
    bridge->RegisterAsyncMethod("bookSource:getBookInfo",
        [svc, jobs](const json& p, std::function<void(json)> respond, std::function<void(std::string)> reject) {
            const int sourceId = p.value("sourceId", 0);
            const std::string bookUrl = p.value("bookUrl", "");
            SubmitJsonJob(jobs, "bookSource:getBookInfo",
                [svc, sourceId, bookUrl] { return svc->GetBookInfo(sourceId, bookUrl); },
                respond, reject);
        });
    bridge->RegisterAsyncMethod("bookSource:getChapterList",
        [svc, jobs](const json& p, std::function<void(json)> respond, std::function<void(std::string)> reject) {
            const int sourceId = p.value("sourceId", 0);
            const std::string tocUrl = p.value("tocUrl", "");
            SubmitJsonJob(jobs, "bookSource:getChapterList",
                [svc, sourceId, tocUrl] { return svc->GetChapterList(sourceId, tocUrl); },
                respond, reject);
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
            std::string err;
            svc->DownloadBook(sourceId, bookUrl, bookName, format, err);
        }).detach();
        return json("started");
    });
}
