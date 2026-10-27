#include "SearchHandlers.h"

#include "BridgeServer.h"
#include "services/DatabaseService.h"
#include "services/SearchIndexService.h"
#include "platform/JobService.h"

#include <memory>
#include <string>

void RegisterSearchHandlers(BridgeServer* bridge, DatabaseService* db,
                            pb::JobService* jobs, pb::SearchIndexService* index)
{
    bridge->RegisterMethod("search:status", [index](const json&) -> json {
        if (!index) return json(nullptr);
        return json{
            {"books", index->BookCount()},
            {"terms", index->TermCount()},
            {"chars", index->TotalChars()},
        };
    });

    bridge->RegisterAsyncMethod("search:build",
        [db, jobs, index](const json& p,
                          std::function<void(json)> respond,
                          std::function<void(std::string)> reject) {
            if (!jobs || !db || !index) { reject("search_unavailable"); return; }

            auto out = std::make_shared<json>(json{{"ok", false}, {"error", "build did not finish"}});
            const pb::JobId id = jobs->Submit(
                "search:build",
                [db, index, out](pb::JobContext& job) {
                    if (index->BookCount() == 0) {
                        std::string loadError;
                        index->Load(&loadError);  // missing index is fine
                    }
                    pb::SearchBuildOptions options;
                    // PB-006：产品建索引覆盖 PDF（service 默认 false 保持单测语义）。
                    options.indexPdf = true;
                    options.isCancelled = [&job] { return job.IsCancelled(); };
                    const auto result = index->Build(pb::BookTextSourcesFromDb(db->GetBooks()), options);
                    std::string saveError;
                    const bool saved = index->Save(&saveError);
                    *out = json{
                        {"ok", true},
                        {"indexed", result.indexed},
                        {"reused", result.reused},
                        {"removed", result.removed},
                        {"skipped", result.skipped},
                        {"skippedPdf", result.skippedPdf},
                        {"failed", result.failed},
                        {"cancelled", result.cancelled},
                        {"totalChars", result.totalChars},
                        {"termCount", result.termCount},
                        {"elapsedMs", result.elapsedMs},
                        {"errors", result.errors},
                        {"saved", saved},
                        {"saveError", saved ? "" : saveError},
                    };
                },
                nullptr,
                [out, respond, reject](const pb::JobResult& job) {
                    if (!job.ok) { reject(job.cancelled ? "cancelled" : job.error); return; }
                    respond(*out);
                });
            if (id == pb::kInvalidJobId) reject("job_service_unavailable");
        });

    bridge->RegisterAsyncMethod("search:query",
        [jobs, index](const json& p,
                      std::function<void(json)> respond,
                      std::function<void(std::string)> reject) {
            if (!jobs || !index) { reject("search_unavailable"); return; }
            const std::string query = p.value("query", "");
            if (query.empty()) { reject("empty_query"); return; }
            const std::size_t maxHits = static_cast<std::size_t>(p.value("maxHits", 50));

            auto out = std::make_shared<json>(json{{"hits", json::array()}, {"booksMatched", 0}, {"truncated", false}, {"cancelled", false}});
            const pb::JobId id = jobs->Submit(
                "search:query",
                [index, query, maxHits, out](pb::JobContext& job) {
                    if (index->BookCount() == 0) {
                        std::string loadError;
                        index->Load(&loadError);
                    }
                    pb::SearchOptions options;
                    options.maxHits = maxHits;
                    options.isCancelled = [&job] { return job.IsCancelled(); };
                    const auto result = index->Search(query, options);
                    json hits = json::array();
                    for (const auto& hit : result.hits) {
                        hits.push_back({
                            {"bookId", hit.bookId}, {"bookTitle", hit.bookTitle},
                            {"bookFormat", hit.bookFormat}, {"snippet", hit.snippet},
                            {"offset", hit.offset}, {"matchLength", hit.matchLength},
                            {"page", hit.page}, {"score", hit.score}, {"termHits", hit.termHits},
                        });
                    }
                    *out = json{
                        {"hits", std::move(hits)},
                        {"booksMatched", result.booksMatched},
                        {"truncated", result.truncated},
                        {"cancelled", result.cancelled},
                    };
                },
                nullptr,
                [out, respond, reject](const pb::JobResult& job) {
                    if (!job.ok) { reject(job.cancelled ? "cancelled" : job.error); return; }
                    respond(*out);
                });
            if (id == pb::kInvalidJobId) reject("job_service_unavailable");
        });
}
