#include "BackupHandlers.h"

#include "BridgeServer.h"
#include "App.h"
#include "WebViewHost.h"
#include "services/DatabaseService.h"
#include "services/BackupService.h"
#include "platform/JobService.h"
#include "platform/LogService.h"
#include "utils/encoding.h"
#include "pb_version.h"

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <commdlg.h>
#include <filesystem>
#include <memory>
#include <string>

namespace {

json BackupError(const std::string& code, const std::string& error)
{
    return json{
        {"ok", false},
        {"code", code},
        {"error", error},
        {"path", ""},
        {"bookCount", 0},
        {"bytes", 0},
    };
}

json RestoreError(const std::string& code, const std::string& error)
{
    return json{
        {"ok", false},
        {"code", code},
        {"error", error},
        {"bookCount", 0},
        {"preRestorePath", ""},
    };
}

std::string PickBackupFile(BridgeServer* bridge)
{
    wchar_t fileBuf[MAX_PATH * 4] = {};
    OPENFILENAMEW ofn = {};
    ofn.lStructSize = sizeof(ofn);
    ofn.hwndOwner = bridge && bridge->GetWebViewHost() ? bridge->GetWebViewHost()->GetHwnd() : nullptr;
    ofn.lpstrFilter = L"ParticleBook backups\0*.zip;*.pbbackup\0All files\0*.*\0";
    ofn.lpstrFile = fileBuf;
    ofn.nMaxFile = static_cast<DWORD>(sizeof(fileBuf) / sizeof(wchar_t));
    ofn.Flags = OFN_FILEMUSTEXIST | OFN_PATHMUSTEXIST | OFN_EXPLORER;
    ofn.lpstrTitle = L"Restore ParticleBook backup";
    if (!GetOpenFileNameW(&ofn)) return "";
    return pb::WideToUtf8(fileBuf);
}

} // namespace

void RegisterBackupHandlers(BridgeServer* bridge, DatabaseService* db, pb::JobService* jobs)
{
    bridge->RegisterAsyncMethod("backup:create",
        [db, jobs](const json&,
                   std::function<void(json)> respond,
                   std::function<void(std::string)> reject) {
            if (!jobs || !db) { reject("job_service_unavailable"); return; }

            const std::string dbPath = db->Path();
            if (dbPath.empty()) { reject("db_path_unavailable"); return; }

            std::string backupDir = App::Instance().UserDataPath() + "/backups";
            std::error_code ec;
            std::filesystem::create_directories(pb::Utf8ToWide(backupDir), ec);
            const std::string zipPath = backupDir + "/" + pb::BackupService::DefaultBackupFileName();

            auto out = std::make_shared<json>(BackupError("backup_failed", "backup did not finish"));
            const pb::JobId id = jobs->Submit(
                "backup:create",
                [db, dbPath, zipPath, out](pb::JobContext&) {
                    db->FlushSync();
                    pb::BackupOptions options;
                    options.appVersion = PB_VERSION_STRING;
                    const auto result = pb::BackupService::CreateBackup(dbPath, zipPath, options);
                    if (!result.ok) {
                        *out = BackupError(result.code, result.error);
                        return;
                    }
                    *out = json{
                        {"ok", true},
                        {"code", result.code},
                        {"error", ""},
                        {"path", result.outputPath},
                        {"bookCount", result.manifest.bookCount},
                        {"bytes", result.archiveBytes},
                    };
                },
                nullptr,
                [out, respond, reject](const pb::JobResult& job) {
                    if (!job.ok) { reject(job.cancelled ? "cancelled" : job.error); return; }
                    if (!out->value("ok", false)) {
                        reject(out->value("error", std::string("backup_failed")));
                        return;
                    }
                    respond(*out);
                });
            if (id == pb::kInvalidJobId) reject("job_service_unavailable");
        });

    bridge->RegisterAsyncMethod("backup:restore",
        [bridge, db, jobs](const json& p,
                           std::function<void(json)> respond,
                           std::function<void(std::string)> reject) {
            if (!jobs || !db) { reject("job_service_unavailable"); return; }

            std::string zipPath = p.value("path", "");
            if (zipPath.empty()) zipPath = PickBackupFile(bridge);
            if (zipPath.empty()) { reject("cancelled"); return; }

            const std::string dbPath = db->Path();
            if (dbPath.empty()) { reject("db_path_unavailable"); return; }

            // The 300ms writer must be stopped before the file is replaced;
            // Resume() reloads the restored file and starts a fresh writer.
            db->Suspend();

            auto out = std::make_shared<json>(RestoreError("restore_failed", "restore did not finish"));
            const pb::JobId id = jobs->Submit(
                "backup:restore",
                [zipPath, dbPath, out](pb::JobContext&) {
                    const auto result = pb::BackupService::RestoreBackup(zipPath, dbPath);
                    if (!result.ok) {
                        *out = RestoreError(result.code, result.error);
                        return;
                    }
                    *out = json{
                        {"ok", true},
                        {"code", result.code},
                        {"error", ""},
                        {"bookCount", result.bookCount},
                        {"preRestorePath", result.preRestoreBackupPath},
                    };
                },
                nullptr,
                [db, out, respond, reject](const pb::JobResult& job) {
                    try {
                        db->Resume();
                    } catch (const std::exception& e) {
                        pb::LogError("backup", std::string("reload after restore failed: ") + e.what());
                        reject("reload_failed");
                        return;
                    }
                    if (!job.ok) { reject(job.cancelled ? "cancelled" : job.error); return; }
                    if (!out->value("ok", false)) {
                        reject(out->value("error", std::string("restore_failed")));
                        return;
                    }
                    pb::LogInfo("backup", "restored " + std::to_string(out->value("bookCount", 0)) + " books");
                    respond(*out);
                });
            if (id == pb::kInvalidJobId) {
                db->Resume();
                reject("job_service_unavailable");
            }
        });
}
