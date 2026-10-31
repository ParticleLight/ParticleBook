#include "DbHandlers.h"
#include "utils/encoding.h"
#include "BridgeServer.h"
#include "WebViewHost.h"
#include "App.h"
#include "services/DatabaseService.h"
#include "platform/SettingsService.h"
#include <windows.h>
#include <filesystem>
#include <cstdio>
#include <ctime>

void RegisterDbHandlers(BridgeServer* bridge, DatabaseService* db)
{
    using Handler = BridgeServer::MethodHandler;

    // ── Books ───────────────────────────────────────
    bridge->RegisterMethod("db:getBooks", [db](const json&) {
        return db->GetBooks();
    });

    bridge->RegisterMethod("db:getBook", [db](const json& p) {
        auto b = db->GetBook(p["id"].get<int>());
        if (!b.is_null()) db->UpdateBookLastOpened(p["id"].get<int>());
        return b.is_null() ? json(nullptr) : b;
    });

    bridge->RegisterMethod("db:deleteBook", [db](const json& p) {
        int id = p.value("id", -1);
        if (id < 0) return json::object();
        // Also remove the physical file for drag-and-drop imported books (written
        // to %APPDATA%/particle-book/dropped/) — otherwise they orphan forever.
        auto book = db->GetBook(id);
        db->DeleteBook(id);
        if (!book.is_null()) {
            std::string fp = book.value("file_path", "");
            // writeDroppedFile stores UserDataPath() + "/dropped" + "\\" + name —
            // mixed separators. Match either separator right after "/dropped".
            std::string droppedPrefix = App::Instance().UserDataPath() + "/dropped";
            bool inDropped = fp.rfind(droppedPrefix, 0) == 0 &&
                             fp.size() > droppedPrefix.size() &&
                             (fp[droppedPrefix.size()] == '/' || fp[droppedPrefix.size()] == '\\');
            if (inDropped) {
                DeleteFileW(pb::Utf8ToWide(fp).c_str());
            }
            // 书源下载产物落在 UserDataPath()/booksource/：以前删书只清 /dropped，
            // 这里的 txt 会永远残留（review · B-12）。
            std::string sourcePrefix = App::Instance().UserDataPath() + "/booksource";
            bool inBookSource = fp.rfind(sourcePrefix, 0) == 0 &&
                                fp.size() > sourcePrefix.size() &&
                                (fp[sourcePrefix.size()] == '/' || fp[sourcePrefix.size()] == '\\');
            if (inBookSource) {
                DeleteFileW(pb::Utf8ToWide(fp).c_str());
            }
        }
        return json::object();
    });

    // ── Reading Progress ────────────────────────────
    bridge->RegisterMethod("db:updateProgress", [db](const json& p) {
        int bid = p["bookId"].get<int>();
        auto prog = p.value("progress", json::object());
        db->UpsertProgress(bid, prog);
        return json::object();
    });

    bridge->RegisterMethod("db:getProgress", [db](const json& p) {
        int bid = p["bookId"].get<int>();
        auto r = db->GetProgress(bid);
        return r.is_null() ? json::object() : r;
    });

    // ── Bookmarks ───────────────────────────────────
    bridge->RegisterMethod("db:getBookmarks", [db](const json& p) {
        return db->GetBookmarks(p["bookId"].get<int>());
    });
    bridge->RegisterMethod("db:addBookmark", [db](const json& p) {
        db->AddBookmark(p["bm"]);
        return json::object();
    });
    bridge->RegisterMethod("db:deleteBookmark", [db](const json& p) {
        db->DeleteBookmark(p["id"].get<int>());
        return json::object();
    });
    bridge->RegisterMethod("db:updateBookmarkTitle", [db](const json& p) {
        db->UpdateBookmarkTitle(p["id"].get<int>(), p["title"].get<std::string>());
        return json::object();
    });

    // ── Highlights ──────────────────────────────────
    bridge->RegisterMethod("db:getHighlights", [db](const json& p) {
        return db->GetHighlights(p["bookId"].get<int>());
    });
    bridge->RegisterMethod("db:addHighlight", [db](const json& p) {
        db->AddHighlight(p["hl"]);
        return json::object();
    });
    bridge->RegisterMethod("db:deleteHighlight", [db](const json& p) {
        db->DeleteHighlight(p["id"].get<int>());
        return json::object();
    });

    // ── Notes ───────────────────────────────────────
    bridge->RegisterMethod("db:getNotes", [db](const json& p) {
        return db->GetNotes(p["bookId"].get<int>());
    });
    bridge->RegisterMethod("db:addNote", [db](const json& p) {
        db->AddNote(p["note"]);
        return json::object();
    });
    bridge->RegisterMethod("db:updateNote", [db](const json& p) {
        db->UpdateNote(p["id"].get<int>(), p["content"].get<std::string>());
        return json::object();
    });
    bridge->RegisterMethod("db:deleteNote", [db](const json& p) {
        db->DeleteNote(p["id"].get<int>());
        return json::object();
    });

    // ── Settings ────────────────────────────────────
    bridge->RegisterMethod("stats:getReadingSessions", [db](const json&) {
        return db->GetReadingSessions();
    });
    bridge->RegisterMethod("settings:schema", [](const json&) {
        return pb::SettingsService::SchemaJson();
    });
    bridge->RegisterMethod("db:getSettings", [db](const json&) {
        // PB-023: expose a schema-normalized object (defaults filled, legacy
        // types repaired, secret-looking keys dropped). Preserve unknown keys
        // so future/older renderer fields (zlibDownloadPath etc.) survive.
        return pb::SettingsService::Normalize(db->GetSettings()).values;
    });
    bridge->RegisterMethod("db:updateSettings", [db](const json& p) {
        // Normalize the patch, then write only keys the caller actually sent;
        // Normalize fills defaults, and writing those would reset omitted keys.
        const json raw = p.value("settings", json::object());
        const auto normalized = pb::SettingsService::Normalize(raw);
        json patch = json::object();
        for (auto it = raw.begin(); it != raw.end(); ++it) {
            if (normalized.values.contains(it.key())) patch[it.key()] = normalized.values[it.key()];
        }
        db->UpdateSettings(patch);
        // 原生标题栏跟随主题：设置里带了 theme 就立刻应用到窗口（复用既有的
        // db:updateSettings 通道 —— 不新增桥接成员，避免 electron.d.ts 变更）。
        const auto themeIt = patch.find("theme");
        if (themeIt != patch.end() && themeIt->is_string()) {
            App::Instance().ApplyWindowTheme(themeIt->get<std::string>());
        }
        return json::object();
    });
    bridge->RegisterMethod("db:getBookSettings", [db](const json& p) {
        return db->GetBookSettings(p["bookId"].get<int>());
    });
    bridge->RegisterMethod("db:updateBookSettings", [db](const json& p) {
        db->UpdateBookSettings(p["bookId"].get<int>(), p["settings"]);
        return json::object();
    });


    // ── Bookshelves ─────────────────────────────────
    bridge->RegisterMethod("db:getBookshelves", [db](const json&) {
        return db->GetBookshelves();
    });
    bridge->RegisterMethod("db:addBookshelf", [db](const json& p) {
        return db->AddBookshelf(p["name"].get<std::string>());
    });
    bridge->RegisterMethod("db:deleteBookshelf", [db](const json& p) {
        db->DeleteBookshelf(p["id"].get<int>());
        return json::object();
    });
    bridge->RegisterMethod("db:renameBookshelf", [db](const json& p) {
        db->RenameBookshelf(p["id"].get<int>(), p["name"].get<std::string>());
        return json::object();
    });
    bridge->RegisterMethod("db:getBooksInShelf", [db](const json& p) {
        auto ids = db->GetBooksInShelf(p["shelfId"].get<int>());
        return json(ids);
    });
    bridge->RegisterMethod("db:addBookToShelf", [db](const json& p) {
        db->AddBookToShelf(p["shelfId"].get<int>(), p["bookId"].get<int>());
        return json::object();
    });
    bridge->RegisterMethod("db:removeBookFromShelf", [db](const json& p) {
        db->RemoveBookFromShelf(p["shelfId"].get<int>(), p["bookId"].get<int>());
        return json::object();
    });


    // ── Download history (PB-030) ───────────────────
    bridge->RegisterMethod("db:getDownloads", [db](const json&) {
        return db->GetDownloads();
    });
    bridge->RegisterMethod("db:deleteDownload", [db, bridge](const json& p) {
        int id = p.value("id", -1);
        if (id < 0) return json(nullptr);
        json removed = db->DeleteDownload(id);
        if (removed.is_null()) return json(nullptr);
        // 勾了「同时从硬盘删除」才动文件：书库里已导入过的那本书也一并移除，
        // 否则书架上会留一条打不开的死记录。
        if (p.value("deleteFile", false)) {
            std::string fp = removed.value("filePath", std::string());
            if (!fp.empty()) {
                std::error_code ec;
                std::filesystem::remove(pb::Utf8ToWide(fp), ec);
                for (const char* ext : {".jpeg", ".jpg", ".png", ".webp"}) {
                    std::filesystem::remove(pb::Utf8ToWide(fp + ext), ec);
                }
            }
            // 按路径反查书架：同一本书导入过就先删书，避免留下打不开的死记录。
            auto book = db->GetBookByPath(fp);
            if (!book.is_null()) {
                db->DeleteBook(book.value("id", 0));
                bridge->EmitEvent("library:changed", json::object());
            }
        }
        return removed;
    });

    // ── Reading Sessions ────────────────────────────
    bridge->RegisterMethod("db:startReadingSession", [db](const json& p) {
        return db->StartReadingSession(p["bookId"].get<int>());
    });
    bridge->RegisterMethod("db:endReadingSession", [db](const json& p) {
        db->EndReadingSession(p["sessionId"].get<int>());
        return json::object();
    });
    bridge->RegisterMethod("db:updateReadingSessionDuration", [db](const json& p) {
        db->UpdateReadingSessionDuration(p["sessionId"].get<int>(), p["duration"].get<int>());
        return json::object();
    });

    bridge->RegisterMethod("db:getAllReadingTime", [db](const json&) {
        return db->GetAllReadingTime();
    });
    bridge->RegisterMethod("db:getAllReadingProgress", [db](const json&) {
        return db->GetAllReadingProgress();
    });

    // ── Book Sources ────────────────────────────────
    bridge->RegisterMethod("bookSource:getAll", [db](const json&) {
        return db->GetBookSources();
    });
    bridge->RegisterMethod("bookSource:get", [db](const json& p) {
        return db->GetBookSource(p["id"].get<int>());
    });
    bridge->RegisterMethod("bookSource:insert", [db](const json& p) {
        return db->InsertBookSource(p["source"]);
    });
    bridge->RegisterMethod("bookSource:update", [db](const json& p) {
        db->UpdateBookSource(p["id"].get<int>(), p["updates"]);
        return json::object();
    });
    bridge->RegisterMethod("bookSource:delete", [db](const json& p) {
        db->DeleteBookSource(p["id"].get<int>());
        return json::object();
    });
    bridge->RegisterMethod("bookSource:toggle", [db](const json& p) {
        db->ToggleBookSource(p["id"].get<int>());
        return json::object();
    });
    bridge->RegisterMethod("bookSource:clearAll", [db](const json&) {
        db->ClearAllBookSources();
        return json::object();
    });
}
