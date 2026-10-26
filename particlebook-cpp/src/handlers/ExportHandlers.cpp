#include "ExportHandlers.h"

#include "BridgeServer.h"
#include "services/DatabaseService.h"
#include "services/ExportService.h"
#include "platform/JobService.h"

#include <memory>
#include <string>
#include <vector>

namespace {

json ExportError(const std::string& code, const std::string& error)
{
    return json{
        {"ok", false},
        {"code", code},
        {"error", error},
        {"fileName", ""},
        {"mimeType", ""},
        {"content", ""},
    };
}

pb::ExportBook BuildBookForExport(DatabaseService* db, const json& book)
{
    pb::ExportBook out;
    out.book = book;
    const int id = book.value("id", 0);
    if (id > 0) {
        out.bookmarks = db->GetBookmarks(id);
        out.highlights = db->GetHighlights(id);
        out.notes = db->GetNotes(id);
    }
    return out;
}

} // namespace

void RegisterExportHandlers(BridgeServer* bridge, DatabaseService* db, pb::JobService* jobs)
{
    bridge->RegisterAsyncMethod("export:annotations",
        [db, jobs](const json& p,
                   std::function<void(json)> respond,
                   std::function<void(std::string)> reject) {
            if (!jobs || !db) { reject("job_service_unavailable"); return; }

            const int bookId = p.value("bookId", 0);
            const std::string formatText = p.value("format", "markdown");
            pb::ExportFormat format = pb::ExportFormat::Markdown;
            if (!pb::ParseExportFormat(formatText, format)) { reject("invalid_format"); return; }
            const bool allBooks = bookId <= 0;

            auto out = std::make_shared<json>(ExportError("export_failed", "export did not finish"));
            const pb::JobId id = jobs->Submit(
                "export:annotations",
                [db, bookId, allBooks, format, out](pb::JobContext&) {
                    pb::ExportOptions options;
                    options.format = format;

                    if (allBooks) {
                        std::vector<pb::ExportBook> books;
                        const json rows = db->GetBooks();
                        for (const auto& book : rows) books.push_back(BuildBookForExport(db, book));
                        const auto result = pb::ExportService::ExportLibrary(books, options);
                        if (!result.ok) {
                            *out = ExportError("export_failed",
                                               result.issues.empty() ? "export failed" : result.issues.front().message);
                            return;
                        }
                        *out = json{
                            {"ok", true},
                            {"code", "ok"},
                            {"error", ""},
                            {"fileName", result.fileName},
                            {"mimeType", result.mimeType},
                            {"content", result.content},
                        };
                        return;
                    }

                    const json book = db->GetBook(bookId);
                    if (book.is_null()) {
                        *out = ExportError("book_not_found", "book not found");
                        return;
                    }
                    const auto result = pb::ExportService::ExportBook(BuildBookForExport(db, book), options);
                    if (!result.ok) {
                        *out = ExportError("export_failed",
                                           result.issues.empty() ? "export failed" : result.issues.front().message);
                        return;
                    }
                    *out = json{
                        {"ok", true},
                        {"code", "ok"},
                        {"error", ""},
                        {"fileName", result.fileName},
                        {"mimeType", result.mimeType},
                        {"content", result.content},
                    };
                },
                nullptr,
                [out, respond, reject](const pb::JobResult& job) {
                    if (!job.ok) { reject(job.cancelled ? "cancelled" : job.error); return; }
                    if (!out->value("ok", false)) {
                        reject(out->value("code", std::string("export_failed")));
                        return;
                    }
                    respond(*out);
                });
            if (id == pb::kInvalidJobId) reject("job_service_unavailable");
        });
}
