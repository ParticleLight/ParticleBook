#include "DatabaseService.h"
#include <fstream>
#include <filesystem>
#include <atomic>
#include <chrono>
#include <ctime>
#include <cstdio>
#include <windows.h>

namespace {
    // Helper: json::get<int>() returns by value, can't ++ directly
    int allocNextId(json& data) {
        int id = data["nextId"].get<int>();
        data["nextId"] = id + 1;
        return id;
    }

    std::string NowISO() {
        auto now = std::chrono::system_clock::now();
        auto t = std::chrono::system_clock::to_time_t(now);
        char buf[32];
        auto len = std::strftime(buf, sizeof(buf), "%Y-%m-%dT%H:%M:%S.000Z", std::gmtime(&t));
        return std::string(buf, len);
    }

    std::string DataDir(const std::string& filePath) {
        auto p = std::filesystem::path(filePath).parent_path();
        std::error_code ec;
        std::filesystem::create_directories(p, ec);
        return filePath;
    }

    std::wstring Utf8ToWide(const std::string& s) {
        if (s.empty()) return L"";
        int len = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), nullptr, 0);
        if (len <= 0) return L"";
        std::wstring w(len, L'\0');
        MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), &w[0], len);
        return w;
    }
}

DatabaseService::DatabaseService()
{
    EnsureDefaults();
}

DatabaseService::~DatabaseService()
{
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_running = false;
        m_dirty = true;
    }
    m_cv.notify_one();
    if (m_writeThread.joinable()) {
        m_writeThread.join();
    }
}

void DatabaseService::Load(const std::string& path)
{
    m_path = path;
    DataDir(path);

    std::error_code ec;
    if (std::filesystem::exists(path, ec)) {
        std::ifstream f(path);
        if (f.is_open()) {
            try {
                m_data = json::parse(f);
            } catch (...) {
                // Corrupt file: back it up before resetting so the user's data
                // isn't silently discarded (a crash mid-write used to leave a
                // half-written JSON that this catch swallowed → total data loss).
                BackupCorruptFile(path);
                EnsureDefaults();
            }
        }
    }
    EnsureDefaults();

    // Start background writer
    m_writeThread = std::thread(&DatabaseService::WriterThread, this);
}

// ── Atomic persist ───────────────────────────────────────────────
// Write to a uniquely-named temp file then atomically replace the real one.
// A crash at any point leaves either the previous intact file or a tmp file —
// never a truncated JSON that Load() would treat as empty. The unique tmp name
// lets WriterThread write off-lock while FlushSync writes concurrently without
// corrupting each other (each rename is atomic; last one wins).
bool DatabaseService::WriteAtomic(const json& data)
{
    if (m_path.empty()) return false;
    static std::atomic<int> seq{0};
    std::string tmpPath = m_path + ".tmp." + std::to_string(seq++);

    {
        std::ofstream f(tmpPath, std::ios::binary | std::ios::trunc);
        if (!f.is_open()) return false;
        f << data.dump(2);
        f.flush();
    }

    if (!MoveFileExW(Utf8ToWide(tmpPath).c_str(), Utf8ToWide(m_path).c_str(),
                     MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
        DeleteFileW(Utf8ToWide(tmpPath).c_str());
        return false;
    }
    return true;
}

void DatabaseService::BackupCorruptFile(const std::string& path)
{
    char ts[32];
    time_t now = time(nullptr);
    tm localTm;
    localtime_s(&localTm, &now);
    strftime(ts, sizeof(ts), "%Y%m%d-%H%M%S", &localTm);
    CopyFileW(Utf8ToWide(path).c_str(),
              Utf8ToWide(path + ".corrupt-" + ts).c_str(), FALSE);
}

void DatabaseService::EnsureDefaults()
{
    if (!m_data.contains("books"))            m_data["books"] = json::array();
    if (!m_data.contains("reading_progress")) m_data["reading_progress"] = json::array();
    if (!m_data.contains("bookmarks"))        m_data["bookmarks"] = json::array();
    if (!m_data.contains("highlights"))       m_data["highlights"] = json::array();
    if (!m_data.contains("notes"))            m_data["notes"] = json::array();
    if (!m_data.contains("bookshelves"))      m_data["bookshelves"] = json::array();
    if (!m_data.contains("bookshelf_books"))  m_data["bookshelf_books"] = json::array();
    if (!m_data.contains("reading_sessions")) m_data["reading_sessions"] = json::array();
    if (!m_data.contains("book_sources"))     m_data["book_sources"] = json::array();
    if (!m_data.contains("settings"))         m_data["settings"] = json::object();
    if (!m_data.contains("book_settings"))    m_data["book_settings"] = json::object();
    if (!m_data.contains("nextId"))           m_data["nextId"] = 1;
}

void DatabaseService::FlushSync()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    // Only clear the pending-write flag once the data actually hit disk, so a
    // failed final flush (disk full / file locked) doesn't silently lose data.
    if (WriteAtomic(m_data)) m_dirty = false;
}

void DatabaseService::ScheduleWrite()
{
    // Caller must hold m_mutex — do NOT re-lock here (deadlock)
    m_dirty = true;
    m_cv.notify_one();
}

void DatabaseService::WriterThread()
{
    while (true) {
        json data;
        {
            std::unique_lock<std::mutex> lock(m_mutex);
            m_cv.wait_for(lock, m_debounce, [this]{ return m_dirty; });
            if (!m_running) break;
            if (!m_dirty) continue;
            m_dirty = false;
            data = m_data;  // copy under the lock, write off the lock
        }

        if (!WriteAtomic(data)) {
            // A failed flush must not drop the last update — mark dirty again
            // and retry on the next round.
            {
                std::lock_guard<std::mutex> lk(m_mutex);
                m_dirty = true;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(500));
        }
    }
}

int DatabaseService::NextId()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    int id = m_data["nextId"].get<int>();
    m_data["nextId"] = id + 1;
    return id;
}

// ── Books ────────────────────────────────────────────────────────

json DatabaseService::GetBooks() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_data["books"];
}

json DatabaseService::GetBook(int id) const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    for (const auto& b : m_data["books"]) {
        if (b.value("id", 0) == id) return b;
    }
    return nullptr;
}

void DatabaseService::UpdateBookLastOpened(int id)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    for (auto& b : m_data["books"]) {
        if (b.value("id", 0) == id) {
            b["last_opened"] = NowISO();
            ScheduleWrite();
            return;
        }
    }
}

json DatabaseService::GetBookByPath(const std::string& path) const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    for (const auto& b : m_data["books"]) {
        if (b.value("file_path", "") == path) return b;
    }
    return nullptr;
}

json DatabaseService::InsertBook(const json& book)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    auto b = book;
    if (!b.contains("id") || b["id"].is_null()) {
        b["id"] = allocNextId(m_data);
    }
    if (!b.contains("added_at")) b["added_at"] = NowISO();
    m_data["books"].push_back(b);
    ScheduleWrite();
    return b;
}

void DatabaseService::DeleteBook(int id)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    auto& books = m_data["books"];
    for (auto it = books.begin(); it != books.end(); ++it) {
        if ((*it).value("id", 0) == id) {
            books.erase(it);
            break;
        }
    }
    // Cascade delete
    auto removeByBookId = [&](const char* col) {
        auto& arr = m_data[col];
        arr.erase(std::remove_if(arr.begin(), arr.end(),
            [id](const json& x) { return x.value("book_id", 0) == id; }), arr.end());
    };
    removeByBookId("reading_progress");
    removeByBookId("bookmarks");
    removeByBookId("highlights");
    removeByBookId("notes");
    removeByBookId("reading_sessions");
    auto& sbs = m_data["bookshelf_books"];
    sbs.erase(std::remove_if(sbs.begin(), sbs.end(),
        [id](const json& x) { return x.value("book_id", 0) == id; }), sbs.end());

    ScheduleWrite();
}

// ── Reading Progress ─────────────────────────────────────────────

json DatabaseService::GetProgress(int bookId) const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    const auto& prog = m_data["reading_progress"];
    if (prog.is_object()) {
        auto it = prog.find(std::to_string(bookId));
        if (it != prog.end()) return *it;
        return nullptr;
    }
    // Legacy array format
    if (prog.is_array()) {
        for (const auto& p : prog) {
            if (p.value("book_id", 0) == bookId) return p;
        }
    }
    return nullptr;
}

std::string DatabaseService::DumpProgress() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_data["reading_progress"].dump();
}

void DatabaseService::UpsertProgress(int bookId, const json& progress)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    // Use object keyed by bookId (as string) to avoid JSON array push_back bugs
    auto& progMap = m_data["reading_progress"];
    // Convert legacy array format to object format if needed
    if (progMap.is_array()) {
        json obj = json::object();
        for (auto& p : progMap) {
            if (p.contains("book_id")) {
                obj[std::to_string(p["book_id"].get<int>())] = p;
            }
        }
        progMap = obj;
    }
    if (!progMap.is_object()) {
        progMap = json::object();
    }

    std::string key = std::to_string(bookId);
    auto& entry = progMap[key];
    entry["book_id"] = bookId;
    entry["progress"] = progress.value("progress", entry.value("progress", 0));
    if (progress.contains("cfi")) entry["cfi"] = progress["cfi"];
    if (progress.contains("page")) entry["page"] = progress["page"];
    if (progress.contains("scrollPosition")) entry["scroll_position"] = progress["scrollPosition"];
    entry["updated_at"] = NowISO();
    ScheduleWrite();
}

// ── Bookmarks ────────────────────────────────────────────────────

json DatabaseService::GetBookmarks(int bookId) const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    json result = json::array();
    for (const auto& bm : m_data["bookmarks"]) {
        if (bm.value("book_id", 0) == bookId) result.push_back(bm);
    }
    return result;
}

void DatabaseService::AddBookmark(const json& bm)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    auto b = bm;
    b["id"] = allocNextId(m_data);
    if (!b.contains("created_at")) b["created_at"] = NowISO();
    m_data["bookmarks"].push_back(b);
    ScheduleWrite();
}

void DatabaseService::DeleteBookmark(int id)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    auto& arr = m_data["bookmarks"];
    arr.erase(std::remove_if(arr.begin(), arr.end(),
        [id](const json& x) { return x.value("id", 0) == id; }), arr.end());
    ScheduleWrite();
}

void DatabaseService::UpdateBookmarkTitle(int id, const std::string& title)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    for (auto& bm : m_data["bookmarks"]) {
        if (bm.value("id", 0) == id) {
            bm["title"] = title;
            ScheduleWrite();
            return;
        }
    }
}

// ── Highlights ───────────────────────────────────────────────────

json DatabaseService::GetHighlights(int bookId) const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    json result = json::array();
    for (const auto& hl : m_data["highlights"]) {
        if (hl.value("book_id", 0) == bookId) result.push_back(hl);
    }
    return result;
}

void DatabaseService::AddHighlight(const json& hl)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    auto h = hl;
    h["id"] = allocNextId(m_data);
    if (!h.contains("created_at")) h["created_at"] = NowISO();
    m_data["highlights"].push_back(h);
    ScheduleWrite();
}

void DatabaseService::DeleteHighlight(int id)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    auto& arr = m_data["highlights"];
    arr.erase(std::remove_if(arr.begin(), arr.end(),
        [id](const json& x) { return x.value("id", 0) == id; }), arr.end());
    ScheduleWrite();
}

// ── Notes ────────────────────────────────────────────────────────

json DatabaseService::GetNotes(int bookId) const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    json result = json::array();
    for (const auto& n : m_data["notes"]) {
        if (n.value("book_id", 0) == bookId) result.push_back(n);
    }
    return result;
}

void DatabaseService::AddNote(const json& note)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    auto n = note;
    n["id"] = allocNextId(m_data);
    if (!n.contains("created_at")) n["created_at"] = NowISO();
    n["updated_at"] = NowISO();
    m_data["notes"].push_back(n);
    ScheduleWrite();
}

void DatabaseService::UpdateNote(int id, const std::string& content)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    for (auto& n : m_data["notes"]) {
        if (n.value("id", 0) == id) {
            n["content"] = content;
            n["updated_at"] = NowISO();
            ScheduleWrite();
            return;
        }
    }
}

void DatabaseService::DeleteNote(int id)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    auto& arr = m_data["notes"];
    arr.erase(std::remove_if(arr.begin(), arr.end(),
        [id](const json& x) { return x.value("id", 0) == id; }), arr.end());
    ScheduleWrite();
}

// ── Bookshelves ──────────────────────────────────────────────────

json DatabaseService::GetBookshelves() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_data["bookshelves"];
}

json DatabaseService::AddBookshelf(const std::string& name)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    json shelf;
    shelf["id"] = allocNextId(m_data);
    shelf["name"] = name;
    shelf["created_at"] = NowISO();
    m_data["bookshelves"].push_back(shelf);
    ScheduleWrite();
    return shelf;
}

void DatabaseService::DeleteBookshelf(int id)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    auto& arr = m_data["bookshelves"];
    arr.erase(std::remove_if(arr.begin(), arr.end(),
        [id](const json& x) { return x.value("id", 0) == id; }), arr.end());
    auto& sbs = m_data["bookshelf_books"];
    sbs.erase(std::remove_if(sbs.begin(), sbs.end(),
        [id](const json& x) { return x.value("shelf_id", 0) == id; }), sbs.end());
    ScheduleWrite();
}

void DatabaseService::RenameBookshelf(int id, const std::string& name)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    for (auto& s : m_data["bookshelves"]) {
        if (s.value("id", 0) == id) { s["name"] = name; ScheduleWrite(); return; }
    }
}

std::vector<int> DatabaseService::GetBooksInShelf(int shelfId) const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    std::vector<int> result;
    for (const auto& sb : m_data["bookshelf_books"]) {
        if (sb.value("shelf_id", 0) == shelfId) result.push_back(sb.value("book_id", 0));
    }
    return result;
}

void DatabaseService::AddBookToShelf(int shelfId, int bookId)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    // Avoid duplicates
    for (const auto& sb : m_data["bookshelf_books"]) {
        if (sb.value("shelf_id", 0) == shelfId && sb.value("book_id", 0) == bookId) return;
    }
    json sb;
    sb["id"] = allocNextId(m_data);
    sb["shelf_id"] = shelfId;
    sb["book_id"] = bookId;
    sb["added_at"] = NowISO();
    m_data["bookshelf_books"].push_back(sb);
    ScheduleWrite();
}

void DatabaseService::RemoveBookFromShelf(int shelfId, int bookId)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    auto& arr = m_data["bookshelf_books"];
    arr.erase(std::remove_if(arr.begin(), arr.end(),
        [shelfId, bookId](const json& x) {
            return x.value("shelf_id", 0) == shelfId && x.value("book_id", 0) == bookId;
        }), arr.end());
    ScheduleWrite();
}

std::vector<int> DatabaseService::GetShelvesForBook(int bookId) const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    std::vector<int> result;
    for (const auto& sb : m_data["bookshelf_books"]) {
        if (sb.value("book_id", 0) == bookId) result.push_back(sb.value("shelf_id", 0));
    }
    return result;
}

// ── Book Sources ─────────────────────────────────────────────────

json DatabaseService::GetBookSources() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_data["book_sources"];
}

json DatabaseService::GetBookSource(int id) const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    for (const auto& s : m_data["book_sources"]) {
        if (s.value("id", 0) == id) return s;
    }
    return nullptr;
}

json DatabaseService::InsertBookSource(const json& source)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    auto s = source;
    s["id"] = allocNextId(m_data);
    if (!s.contains("added_at")) s["added_at"] = NowISO();
    m_data["book_sources"].push_back(s);
    ScheduleWrite();
    return s;
}

void DatabaseService::UpdateBookSource(int id, const json& updates)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    for (auto& s : m_data["book_sources"]) {
        if (s.value("id", 0) == id) {
            for (auto& [k, v] : updates.items()) s[k] = v;
            ScheduleWrite();
            return;
        }
    }
}

void DatabaseService::DeleteBookSource(int id)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    auto& arr = m_data["book_sources"];
    arr.erase(std::remove_if(arr.begin(), arr.end(),
        [id](const json& x) { return x.value("id", 0) == id; }), arr.end());
    ScheduleWrite();
}

void DatabaseService::ToggleBookSource(int id)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    for (auto& s : m_data["book_sources"]) {
        if (s.value("id", 0) == id) {
            s["enabled"] = !s.value("enabled", true);
            ScheduleWrite();
            return;
        }
    }
}

void DatabaseService::ClearAllBookSources()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    m_data["book_sources"] = json::array();
    ScheduleWrite();
}

// ── Reading Sessions ─────────────────────────────────────────────

int DatabaseService::StartReadingSession(int bookId)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    json session;
    session["id"] = allocNextId(m_data);
    session["book_id"] = bookId;
    session["started_at"] = NowISO();
    session["ended_at"] = nullptr;
    session["duration_seconds"] = 0;
    m_data["reading_sessions"].push_back(session);
    ScheduleWrite();
    return session["id"].get<int>();
}

void DatabaseService::EndReadingSession(int sessionId)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    for (auto& s : m_data["reading_sessions"]) {
        if (s.value("id", 0) == sessionId) {
            s["ended_at"] = NowISO();
            ScheduleWrite();
            return;
        }
    }
}

void DatabaseService::UpdateReadingSessionDuration(int sessionId, int seconds)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    for (auto& s : m_data["reading_sessions"]) {
        if (s.value("id", 0) == sessionId) {
            s["duration_seconds"] = seconds;
            ScheduleWrite();
            return;
        }
    }
}

int DatabaseService::GetReadingTimeForBook(int bookId) const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    int total = 0;
    for (const auto& s : m_data["reading_sessions"]) {
        if (s.value("book_id", 0) == bookId) total += s.value("duration_seconds", 0);
    }
    return total;
}

json DatabaseService::GetAllReadingTime() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    json result = json::object();
    for (const auto& s : m_data["reading_sessions"]) {
        int bid = s.value("book_id", 0);
        auto key = std::to_string(bid);
        int existing = result.contains(key) ? result[key].get<int>() : 0;
        result[key] = existing + s.value("duration_seconds", 0);
    }
    return result;
}

json DatabaseService::GetAllReadingProgress() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    json result = json::object();
    const auto& prog = m_data["reading_progress"];
    if (prog.is_object()) {
        for (auto it = prog.begin(); it != prog.end(); ++it) {
            result[it.key()] = it.value();
        }
    } else if (prog.is_array()) {
        for (const auto& p : prog) {
            int bid = p.value("book_id", 0);
            json entry;
            entry["progress"] = p.value("progress", 0);
            entry["cfi"] = p.value("cfi", nullptr);
            entry["page"] = p.value("page", nullptr);
            entry["scroll_position"] = p.value("scroll_position", 0);
            result[std::to_string(bid)] = entry;
        }
    }
    return result;
}

// ── Settings ─────────────────────────────────────────────────────

json DatabaseService::GetSettings() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_data["settings"];
}

void DatabaseService::UpdateSettings(const json& settings)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    for (auto& [k, v] : settings.items()) m_data["settings"][k] = v;
    ScheduleWrite();
}

json DatabaseService::GetBookSettings(int bookId) const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    auto& bs = m_data["book_settings"];
    auto key = std::to_string(bookId);
    if (bs.contains(key)) return bs[key];
    return json::object();
}

void DatabaseService::UpdateBookSettings(int bookId, const json& settings)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    auto& bs = m_data["book_settings"];
    auto key = std::to_string(bookId);
    for (auto& [k, v] : settings.items()) bs[key][k] = v;
    ScheduleWrite();
}

void DatabaseService::DeleteBookSettings(int bookId)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    m_data["book_settings"].erase(std::to_string(bookId));
    ScheduleWrite();
}
