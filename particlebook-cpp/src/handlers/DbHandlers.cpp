#include "DbHandlers.h"
#include "BridgeServer.h"
#include "WebViewHost.h"
#include "App.h"
#include "services/DatabaseService.h"
#include <windows.h>
#include <filesystem>
#include <cstdio>
#include <ctime>

static std::wstring Utf8ToWide(const std::string& s) {
    if (s.empty()) return L"";
    int len = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), nullptr, 0);
    if (len <= 0) return L"";
    std::wstring w(len, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), &w[0], len);
    return w;
}

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
                DeleteFileW(Utf8ToWide(fp).c_str());
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
    bridge->RegisterMethod("db:getSettings", [db](const json&) {
        return db->GetSettings();
    });
    bridge->RegisterMethod("db:updateSettings", [db](const json& p) {
        db->UpdateSettings(p["settings"]);
        return json::object();
    });
    bridge->RegisterMethod("db:getBookSettings", [db](const json& p) {
        return db->GetBookSettings(p["bookId"].get<int>());
    });
    bridge->RegisterMethod("db:updateBookSettings", [db](const json& p) {
        db->UpdateBookSettings(p["bookId"].get<int>(), p["settings"]);
        return json::object();
    });
    bridge->RegisterMethod("db:deleteBookSettings", [db](const json& p) {
        db->DeleteBookSettings(p["bookId"].get<int>());
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
    bridge->RegisterMethod("db:getShelvesForBook", [db](const json& p) {
        auto ids = db->GetShelvesForBook(p["bookId"].get<int>());
        return json(ids);
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
    bridge->RegisterMethod("db:getReadingTime", [db](const json& p) {
        return db->GetReadingTimeForBook(p["bookId"].get<int>());
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
