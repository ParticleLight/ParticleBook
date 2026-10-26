#include "DictionaryHandlers.h"

#include "BridgeServer.h"
#include "WebViewHost.h"
#include "platform/JobService.h"
#include "services/DictionaryService.h"
#include "utils/encoding.h"

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <commdlg.h>

#include <atomic>
#include <filesystem>
#include <memory>
#include <string>

namespace {

std::atomic_bool g_dictionaryEnabled{true};

std::string PickDictionaryFile(BridgeServer* bridge)
{
    wchar_t fileBuf[MAX_PATH * 4] = {};
    OPENFILENAMEW dialog = {};
    dialog.lStructSize = sizeof(dialog);
    dialog.hwndOwner = bridge && bridge->GetWebViewHost() ? bridge->GetWebViewHost()->GetHwnd() : nullptr;
    dialog.lpstrFilter =
        L"Dictionary files\0*.csv;*.ifo\0"
        L"ECDICT CSV (*.csv)\0*.csv\0"
        L"StarDict ifo (*.ifo)\0*.ifo\0"
        L"All files\0*.*\0";
    dialog.lpstrFile = fileBuf;
    dialog.nMaxFile = static_cast<DWORD>(sizeof(fileBuf) / sizeof(wchar_t));
    dialog.Flags = OFN_FILEMUSTEXIST | OFN_PATHMUSTEXIST | OFN_EXPLORER;
    dialog.lpstrTitle = L"Import local dictionary";
    if (!GetOpenFileNameW(&dialog)) return "";
    return pb::WideToUtf8(fileBuf);
}

bool EndsWithNoCase(const std::string& text, const std::string& suffix)
{
    if (text.size() < suffix.size()) return false;
    const std::size_t offset = text.size() - suffix.size();
    for (std::size_t i = 0; i < suffix.size(); ++i) {
        const char left = text[offset + i];
        const char right = suffix[i];
        const char lowerLeft = (left >= 'A' && left <= 'Z') ? static_cast<char>(left - 'A' + 'a') : left;
        const char lowerRight = (right >= 'A' && right <= 'Z') ? static_cast<char>(right - 'A' + 'a') : right;
        if (lowerLeft != lowerRight) return false;
    }
    return true;
}

const char* SourceName(pb::DictionarySourceKind kind)
{
    switch (kind) {
    case pb::DictionarySourceKind::EcdictCsv: return "ecdict_csv";
    case pb::DictionarySourceKind::StarDict: return "stardict";
    default: return "none";
    }
}

json EntryToJson(const pb::DictionaryEntry& entry)
{
    return json{
        {"word", entry.word},
        {"phonetic", entry.phonetic},
        {"translation", entry.translation},
        {"definition", entry.definition},
        {"pos", entry.pos},
        {"tag", entry.tag},
        {"exchange", entry.exchange},
        {"example", entry.example},
    };
}

json HitToJson(const pb::DictionaryHit& hit)
{
    json out = EntryToJson(hit.entry);
    out["kind"] = pb::DictionaryService::MatchKindName(hit.kind);
    out["matched"] = hit.matched;
    out["verified"] = hit.verified;
    return out;
}

} // namespace

void RegisterDictionaryHandlers(BridgeServer* bridge,
                                pb::DictionaryService* dictionary,
                                pb::JobService* jobs,
                                const std::string& cachePath)
{
    bridge->RegisterMethod("dictionary:status",
        [dictionary](const json&) {
            if (!dictionary) {
                return json{{"ok", false}, {"loaded", false}, {"source", "none"},
                            {"entries", 0}, {"bytes", 0}};
            }
            const pb::DictionaryInfo info = dictionary->Info();
            return json{
                {"ok", true},
                {"loaded", info.loaded},
                {"enabled", g_dictionaryEnabled.load(std::memory_order_relaxed)},
                {"source", SourceName(info.source)},
                {"entries", info.entries},
                {"bytes", info.bytes},
            };
        });

    bridge->RegisterMethod("dictionary:lookup",
        [dictionary](const json& p) {
            if (!dictionary) {
                return json{{"ok", false}, {"found", false}, {"code", "dictionary_unavailable"},
                            {"error", "dictionary is not available"}, {"query", ""},
                            {"hits", json::array()}};
            }
            if (!g_dictionaryEnabled.load(std::memory_order_relaxed)) {
                return json{{"ok", false}, {"found", false}, {"code", "dictionary_disabled"},
                            {"error", "dictionary is disabled"}, {"query", p.value("word", "")},
                            {"hits", json::array()}};
            }
            pb::DictionaryLookupOptions options;
            options.prefix = p.value("prefix", false);
            options.caseInsensitive = p.value("caseInsensitive", true);
            options.morphology = p.value("morphology", true);
            const int requested = p.value("maxResults", 10);
            options.maxResults = requested < 1 ? 1 : (requested > 50 ? 50 : static_cast<std::size_t>(requested));

            const pb::DictionaryLookupResult result = dictionary->Lookup(p.value("word", ""), options);
            json hits = json::array();
            for (const pb::DictionaryHit& hit : result.hits) hits.push_back(HitToJson(hit));
            return json{
                {"ok", result.ok},
                {"found", result.found},
                {"code", result.code},
                {"error", result.error},
                {"query", result.query},
                {"hits", hits},
            };
        });

    bridge->RegisterMethod("dictionary:setEnabled",
        [](const json& p) {
            const bool enabled = p.value("enabled", true);
            g_dictionaryEnabled.store(enabled, std::memory_order_relaxed);
            return json{{"ok", true}, {"enabled", enabled}};
        });

    bridge->RegisterMethod("dictionary:clear",
        [dictionary, cachePath](const json&) {
            if (dictionary) dictionary->Clear();
            std::error_code ec;
            std::filesystem::remove(pb::Utf8ToWide(cachePath), ec);
            return json{{"ok", true}, {"entries", 0}};
        });

    bridge->RegisterAsyncMethod("dictionary:import",
        [bridge, dictionary, jobs, cachePath](const json& p,
                                              std::function<void(json)> respond,
                                              std::function<void(std::string)> reject) {
            if (!dictionary || !jobs) { reject("dictionary_unavailable"); return; }

            // path 可选：给测试/脚本用；正常 UI 走系统文件框。
            std::string path = p.value("path", "");
            if (path.empty()) path = PickDictionaryFile(bridge);
            if (path.empty()) { reject("cancelled"); return; }

            const bool starDict = EndsWithNoCase(path, ".ifo");
            auto out = std::make_shared<json>(json{
                {"ok", false}, {"code", "import_failed"}, {"error", "import did not finish"},
                {"entries", 0}, {"bytes", 0}, {"skipped", 0}, {"elapsedMs", 0},
                {"source", "none"}, {"cachePath", cachePath},
            });

            const pb::JobId id = jobs->Submit(
                "dictionary:import",
                [dictionary, path, cachePath, starDict, out](pb::JobContext& job) {
                    pb::DictionaryImportOptions options;
                    options.cachePath = cachePath;
                    auto progress = [&job](const pb::DictionaryProgress& p2) {
                        double percent = 5.0;
                        if (p2.stage == "reading") percent = 5.0;
                        else if (p2.stage == "indexing") percent = 90.0;
                        else if (p2.stage == "saving") percent = 95.0;
                        else if (p2.stage == "parsing") {
                            percent = 10.0;
                            if (p2.bytesTotal > 0) {
                                percent = 10.0 + 70.0 * (static_cast<double>(p2.bytesProcessed) /
                                                         static_cast<double>(p2.bytesTotal));
                            }
                        }
                        job.Progress(percent, p2.stage);
                    };
                    const pb::DictionaryImportResult result =
                        starDict ? dictionary->ImportStarDict(path, options, progress, &job.Cancelled())
                                 : dictionary->ImportEcdictCsv(path, options, progress, &job.Cancelled());
                    *out = json{
                        {"ok", result.ok},
                        {"code", result.code},
                        {"error", result.error},
                        {"entries", result.entries},
                        {"bytes", result.bytes},
                        {"skipped", result.skipped},
                        {"elapsedMs", result.elapsedMs},
                        {"source", SourceName(result.source)},
                        {"cachePath", cachePath},
                    };
                },
                [bridge](const pb::JobProgress& progress) {
                    bridge->EmitEvent("dictionary:progress", {
                        {"percent", progress.percent},
                        {"message", progress.message},
                        {"name", progress.name},
                    });
                },
                [out, respond, reject](const pb::JobResult& job) {
                    if (!job.ok) { reject(job.cancelled ? "cancelled" : job.error); return; }
                    if (!out->value("ok", false)) {
                        reject(out->value("error", std::string("import_failed")));
                        return;
                    }
                    respond(*out);
                });
            if (id == pb::kInvalidJobId) reject("job_service_unavailable");
        });
}
