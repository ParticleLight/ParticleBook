#include "DatabaseService.h"
#include "utils/encoding.h"
#include <algorithm>
#include <fstream>
#include <filesystem>
#include <atomic>
#include <chrono>
#include <ctime>
#include <cstdio>
#include <unordered_map>
#include <windows.h>
#include <stdexcept>

namespace {
    constexpr int kCurrentSchemaVersion = DatabaseService::kCurrentSchemaVersion;

    // Migration registry: each entry upgrades from `from` to `to`. Keep the
    // functions small and idempotent; Load() backs up the file before running
    // any of them and writes the migrated object atomically afterwards.
    struct Migration {
        int from;
        int to;
        const char* description;
        bool (*run)(json& data, std::string& error);
    };

    bool MigrateV1ToV2(json& data, std::string& error) {
        (void)data;
        (void)error;
        // v1 = unversioned. v2 only adds schema_version; future shape changes
        // must append a new migration instead of editing this one.
        return true;
    }

    // PB-021: keep the legacy tables (renderer still reads them) and add one
    // unified "annotations" array. Old ids are preserved in legacy_id; the new
    // id comes from nextAnnotationId so bookmark/highlight/note ids can't clash.
    json LegacyLocator(const json& row, const std::string& bookFormat) {
        const std::string cfi = row.value("cfi", "");
        const int page = row.value("page", 0);
        const double progress = row.value("progress", 0.0);
        json loc = { {"version", 1}, {"kind", "scroll"}, {"percent", 0.0} };
        if (!cfi.empty()) {
            loc["kind"] = "epub";
            loc["cfi"] = cfi;
        } else if (page > 0) {
            const bool pdf = bookFormat == "pdf" || bookFormat == "cbz" || bookFormat == "cbr";
            loc["kind"] = pdf ? "pdf" : "text";
            if (pdf) loc["page"] = page;
            else loc["offset"] = 0;
        } else if (progress > 0.0) {
            loc["kind"] = "scroll";
            loc["percent"] = progress;
        }
        return loc;
    }

    bool MigrateV2ToV3(json& data, std::string& error) {
        (void)error;
        json annotations = json::array();
        if (!data.contains("annotations") || !data["annotations"].is_array()) {
            data["annotations"] = json::array();
        } else {
            annotations = data["annotations"];
        }
        int nextId = data.value("nextAnnotationId", 1);
        if (!data.contains("nextAnnotationId")) data["nextAnnotationId"] = 1;

        std::unordered_map<int, std::string> formats;
        if (data.contains("books") && data["books"].is_array()) {
            for (const auto& book : data["books"]) {
                if (book.is_object()) formats[book.value("id", 0)] = book.value("format", "");
            }
        }

        auto add = [&](const json& row, const char* type, const std::string& title,
                       const std::string& content, const std::string& color,
                       const std::string& quote, int related) {
            if (!row.is_object()) return;
            const int bookId = row.value("book_id", 0);
            if (bookId <= 0) return;
            json loc = LegacyLocator(row, formats.count(bookId) ? formats[bookId] : "");
            if (!quote.empty()) loc["quote"] = quote;
            json ann = {
                {"version", 1},
                {"id", nextId++},
                {"bookId", bookId},
                {"type", type},
                {"locator", loc},
                {"title", title},
                {"note", content},
                {"color", color},
                {"createdAt", row.value("created_at", "")},
                {"updatedAt", row.value("updated_at", row.value("created_at", ""))},
                {"legacyId", row.value("id", 0)},
                {"legacyType", type},
            };
            if (related > 0) ann["relatedId"] = related;
            annotations.push_back(std::move(ann));
        };

        if (data.contains("bookmarks") && data["bookmarks"].is_array()) {
            for (const auto& row : data["bookmarks"]) {
                add(row, "bookmark", row.value("title", ""), row.value("note", ""), "", "", 0);
            }
        }
        if (data.contains("highlights") && data["highlights"].is_array()) {
            for (const auto& row : data["highlights"]) {
                add(row, "highlight", "", row.value("note", ""), row.value("color", ""),
                    row.value("text", ""), 0);
            }
        }
        if (data.contains("notes") && data["notes"].is_array()) {
            for (const auto& row : data["notes"]) {
                add(row, "note", "", row.value("content", ""), "", "",
                    row.value("highlight_id", 0));
            }
        }
        data["annotations"] = std::move(annotations);
        data["nextAnnotationId"] = nextId;
        return true;
    }


    // ── PB-005：books 行元数据（tags / series_name / series_index / rating）──
    std::string TrimBookText(const std::string& text) {
        std::size_t begin = 0;
        std::size_t end = text.size();
        while (begin < end && (text[begin] == ' ' || text[begin] == '\t'
                               || text[begin] == '\n' || text[begin] == '\r')) ++begin;
        while (end > begin && (text[end - 1] == ' ' || text[end - 1] == '\t'
                               || text[end - 1] == '\n' || text[end - 1] == '\r')) --end;
        return text.substr(begin, end - begin);
    }

    // 缺字段补默认、坏类型就地修复；幂等，迁移 / 加载 / 新增 / 更新都走它。
    void NormalizeBookMetadata(json& book) {
        if (!book.is_object()) return;

        json tags = json::array();
        if (book.contains("tags") && book["tags"].is_array()) {
            for (const auto& tag : book["tags"]) {
                if (!tag.is_string()) continue;
                std::string text = TrimBookText(tag.get<std::string>());
                if (text.empty()) continue;
                if (text.size() > 64) text.resize(64);
                bool duplicate = false;
                for (const auto& existing : tags) {
                    if (existing.get<std::string>() == text) { duplicate = true; break; }
                }
                if (duplicate) continue;
                if (tags.size() >= 32) break;
                tags.push_back(text);
            }
        }
        book["tags"] = std::move(tags);

        if (!book.contains("series_name") || !book["series_name"].is_string()) {
            book["series_name"] = "";
        } else {
            book["series_name"] = TrimBookText(book["series_name"].get<std::string>());
        }

        double seriesIndex = 0.0;
        if (book.contains("series_index") && book["series_index"].is_number()) {
            seriesIndex = book["series_index"].get<double>();
            if (seriesIndex < 0.0) seriesIndex = 0.0;
            if (seriesIndex > 10000.0) seriesIndex = 10000.0;
        }
        book["series_index"] = seriesIndex;

        int rating = 0;
        if (book.contains("rating") && book["rating"].is_number_integer()) {
            rating = book["rating"].get<int>();
            if (rating < 0) rating = 0;
            if (rating > 5) rating = 5;
        }
        book["rating"] = rating;
    }

    // PB-005 (schema v4): old rows keep working —— add defaults to every book.
    bool MigrateV3ToV4(json& data, std::string& error) {
        (void)error;
        if (!data.contains("books") || !data["books"].is_array()) {
            data["books"] = json::array();
            return true;
        }
        for (auto& book : data["books"]) NormalizeBookMetadata(book);
        return true;
    }

    const std::vector<Migration>& Migrations() {
        static const std::vector<Migration> migrations = {
            { 1, 2, "add schema_version", &MigrateV1ToV2 },
            { 2, 3, "unified annotations", &MigrateV2ToV3 },
            { 3, 4, "book metadata (tags / series / rating)", &MigrateV3ToV4 },
        };
        return migrations;
    }

    std::string MigrationTimestamp() {
        char ts[32];
        time_t now = time(nullptr);
        tm localTm;
        localtime_s(&localTm, &now);
        strftime(ts, sizeof(ts), "%Y%m%d-%H%M%S", &localTm);
        return ts;
    }

    bool CopyForMigrationBackup(const std::string& path, int fromVersion,
                                std::string& backupPath) {
        backupPath = path + ".pre-migrate-v" + std::to_string(fromVersion)
                   + "-" + MigrationTimestamp();
        return CopyFileW(pb::Utf8ToWide(path).c_str(),
                         pb::Utf8ToWide(backupPath).c_str(), FALSE) != 0;
    }

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

    // ── PB-021 annotation helpers（统一表 + 旧表双写）─────────────────
    bool IsAnnotationType(const std::string& type) {
        return type == "bookmark" || type == "highlight" || type == "note";
    }

    const char* LegacyTableForType(const std::string& type) {
        if (type == "bookmark")  return "bookmarks";
        if (type == "highlight") return "highlights";
        if (type == "note")      return "notes";
        return nullptr;
    }

    int allocNextAnnotationId(json& data) {
        int id = data.value("nextAnnotationId", 1);
        data["nextAnnotationId"] = id + 1;
        return id;
    }

    // 与 domain/Locator::IsEmpty 同一口径（这里不链 domain，避免测试目标多依赖）：
    // 每个 kind 的必备字段缺失即视为空 locator。
    bool IsEmptyLocator(const json& locator) {
        if (!locator.is_object()) return true;
        const std::string kind = locator.value("kind", std::string());
        if (kind == "epub") {
            return locator.value("cfi", std::string()).empty();
        }
        if (kind == "text") {
            return !locator.contains("offset") || !locator["offset"].is_number_integer()
                || locator["offset"].get<long long>() < 0;
        }
        if (kind == "html") {
            return locator.value("href", std::string()).empty();
        }
        if (kind == "pdf") {
            return !locator.contains("page") || !locator["page"].is_number_integer()
                || locator["page"].get<int>() < 1;
        }
        if (kind == "scroll") {
            if (!locator.contains("percent") || !locator["percent"].is_number()) return true;
            const double percent = locator["percent"].get<double>();
            return percent < 0.0 || percent > 100.0;
        }
        return true;
    }

    bool IsValidAnnotationRow(const json& row) {
        if (!row.is_object()) return false;
        if (row.value("bookId", 0) <= 0) return false;
        const std::string type = row.value("type", std::string());
        if (!IsAnnotationType(type)) return false;
        if (!row.contains("locator")) return false;
        return !IsEmptyLocator(row["locator"]);
    }

    // Locator → 旧表的 cfi / page / progress（现有渲染层认识的就是这些字段）。
    void ApplyLocatorToLegacyRow(const json& locator, json& row) {
        if (!locator.is_object()) return;
        const std::string kind = locator.value("kind", std::string());
        if (kind == "epub") {
            row["cfi"] = locator.value("cfi", std::string());
        } else if (kind == "text") {
            const long long offset = locator.value("offset", static_cast<long long>(-1));
            if (offset >= 0) row["cfi"] = "pos-" + std::to_string(offset);
            if (locator.contains("percent") && locator["percent"].is_number()) {
                row["page"] = locator["percent"].get<double>();
            }
        } else if (kind == "html") {
            // 旧 HtmlRenderer 把元素 id / href 存在 cfi 里
            row["cfi"] = locator.value("href", std::string());
        } else if (kind == "pdf") {
            row["page"] = locator.value("page", 0);
        } else if (kind == "scroll") {
            // 旧 TextRenderer 把百分比写进 page；progress 也一样
            const double percent = locator.value("percent", 0.0);
            row["page"] = percent;
            row["progress"] = percent;
        }
    }

    // 由统一行重建旧表行（保留 legacyId 不变；type 决定表结构）。
    json BuildLegacyRow(const json& annotation, int legacyId) {
        json row = json::object();
        row["id"] = legacyId;
        row["book_id"] = annotation.value("bookId", 0);
        const std::string createdAt = annotation.value("createdAt", NowISO());
        row["created_at"] = createdAt;
        const json locator = annotation.value("locator", json::object());
        ApplyLocatorToLegacyRow(locator, row);

        const std::string type = annotation.value("type", std::string());
        if (type == "bookmark") {
            row["title"] = annotation.value("title", std::string());
            row["note"] = annotation.value("note", std::string());
        } else if (type == "highlight") {
            row["text"] = locator.is_object() ? locator.value("quote", std::string())
                                              : std::string();
            row["color"] = annotation.value("color", std::string());
            row["note"] = annotation.value("note", std::string());
        } else if (type == "note") {
            row["content"] = annotation.value("note", std::string());
            row["updated_at"] = annotation.value("updatedAt", createdAt);
            const long long relatedId =
                annotation.value("relatedId", static_cast<long long>(0));
            if (relatedId > 0) row["highlight_id"] = static_cast<int>(relatedId);
        }
        return row;
    }

    int InsertLegacyRow(json& data, const json& annotation) {
        const std::string type = annotation.value("type", std::string());
        const char* table = LegacyTableForType(type);
        if (!table) return 0;
        if (!data.contains(table) || !data[table].is_array()) data[table] = json::array();
        const int legacyId = allocNextId(data);
        data[table].push_back(BuildLegacyRow(annotation, legacyId));
        return legacyId;
    }

    bool ReplaceLegacyRow(json& data, const std::string& type, int legacyId,
                          const json& annotation) {
        const char* table = LegacyTableForType(type);
        if (!table || legacyId <= 0) return false;
        if (!data.contains(table) || !data[table].is_array()) return false;
        for (auto& row : data[table]) {
            if (row.value("id", 0) == legacyId) {
                row = BuildLegacyRow(annotation, legacyId);
                return true;
            }
        }
        return false;
    }

    void DeleteLegacyRow(json& data, const std::string& type, int legacyId) {
        const char* table = LegacyTableForType(type);
        if (!table || legacyId <= 0) return;
        if (!data.contains(table) || !data[table].is_array()) return;
        auto& arr = data[table];
        arr.erase(std::remove_if(arr.begin(), arr.end(),
            [legacyId](const json& row) { return row.value("id", 0) == legacyId; }), arr.end());
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
    const bool fileExists = std::filesystem::exists(path, ec);
    bool validObject = false;

    if (fileExists) {
        std::ifstream f(path);
        if (f.is_open()) {
            try {
                m_data = json::parse(f);
                validObject = m_data.is_object();
                if (!validObject) {
                    // Valid JSON but not the object shape we expect (e.g. an
                    // array) — backup + reset instead of crashing on the
                    // string-key operator[] below.
                    BackupCorruptFile(path);
                    m_data = json::object();
                }
            } catch (...) {
                // Corrupt file: back it up before resetting so the user's data
                // isn't silently discarded.
                BackupCorruptFile(path);
                m_data = json::object();
            }
        }
    }
    if (!validObject) m_data = json::object();

    int fromVersion = 1;
    if (validObject && m_data.contains("schema_version")) {
        if (m_data["schema_version"].is_number_integer()) {
            fromVersion = m_data["schema_version"].get<int>();
        } else {
            // malformed version marker: treat as corrupt metadata, keep data
            BackupCorruptFile(path);
            m_data = json::object();
            validObject = false;
            fromVersion = 1;
        }
    }
    if (fromVersion < 1) fromVersion = 1;
    if (fromVersion > kCurrentSchemaVersion) {
        throw std::runtime_error(
            "database schema v" + std::to_string(fromVersion) +
            " is newer than this app (v" + std::to_string(kCurrentSchemaVersion) +
            "); refusing to open to avoid downgrading data");
    }

    if (fromVersion < kCurrentSchemaVersion) {
        std::string backupPath;
        if (fileExists && !CopyForMigrationBackup(path, fromVersion, backupPath)) {
            throw std::runtime_error("failed to back up database before migration");
        }

        json migrated = m_data;
        int version = fromVersion;
        while (version < kCurrentSchemaVersion) {
            const Migration* match = nullptr;
            for (const auto& m : Migrations()) {
                if (m.from == version) { match = &m; break; }
            }
            if (!match) {
                throw std::runtime_error(
                    "no migration registered from database schema v" +
                    std::to_string(version));
            }
            std::string error;
            if (!match->run(migrated, error)) {
                throw std::runtime_error(
                    "database migration v" + std::to_string(match->from) + "->v" +
                    std::to_string(match->to) + " failed: " + error);
            }
            version = match->to;
        }
        migrated["schema_version"] = kCurrentSchemaVersion;
        if (!WriteAtomic(migrated)) {
            throw std::runtime_error(
                "failed to write migrated database (original + pre-migrate backup kept)");
        }
        m_data = std::move(migrated);
    }

    EnsureDefaults();
    if (!m_data.contains("schema_version")) {
        m_data["schema_version"] = kCurrentSchemaVersion;
    }

    // Start background writer (also applies after Suspend()/Resume()).
    m_running = true;
    m_writeThread = std::thread(&DatabaseService::WriterThread, this);
}

int DatabaseService::GetSchemaVersion() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_data.value("schema_version", 0);
}

std::string DatabaseService::Path() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_path;
}

void DatabaseService::Suspend()
{
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        if (!m_running) return;
        m_dirty = true;   // force a final flush before stopping
        m_running = false;
    }
    m_cv.notify_one();
    if (m_writeThread.joinable()) m_writeThread.join();
}

void DatabaseService::Resume()
{
    std::string path;
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        path = m_path;
    }
    if (!path.empty()) Load(path);
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
        if (f.fail()) {
            // Disk full / I/O error — never replace the good file with a
            // truncated one.
            f.close();
            DeleteFileW(pb::Utf8ToWide(tmpPath).c_str());
            return false;
        }
    }

    if (!MoveFileExW(pb::Utf8ToWide(tmpPath).c_str(), pb::Utf8ToWide(m_path).c_str(),
                     MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
        DeleteFileW(pb::Utf8ToWide(tmpPath).c_str());
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
    CopyFileW(pb::Utf8ToWide(path).c_str(),
              pb::Utf8ToWide(path + ".corrupt-" + ts).c_str(), FALSE);
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
    if (!m_data.contains("annotations"))      m_data["annotations"] = json::array();
    if (!m_data.contains("nextAnnotationId")) m_data["nextAnnotationId"] = 1;
    if (!m_data.contains("nextId"))           m_data["nextId"] = 1;
    // PB-005：books 行元数据默认值（迁移前的老库 / 手改库 / 旧 Insert 路径都补齐）
    if (m_data["books"].is_array()) {
        for (auto& book : m_data["books"]) NormalizeBookMetadata(book);
    }
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
        std::unique_lock<std::mutex> lock(m_mutex);
        m_cv.wait_for(lock, m_debounce, [this]{ return m_dirty; });
        if (!m_running) {
            // Final flush on shutdown: a pending write must not be dropped.
            if (m_dirty) WriteAtomic(m_data);
            break;
        }
        if (!m_dirty) continue;
        // Write while holding the lock — serializes with FlushSync so a stale
        // snapshot can never overwrite a newer one (off-lock writes raced:
        // WriterThread's old snapshot could rename over FlushSync's new data).
        if (WriteAtomic(m_data)) {
            m_dirty = false;
        } else {
            // Keep dirty so we retry on the next round (disk full / locked).
            m_dirty = true;
            std::this_thread::sleep_for(std::chrono::milliseconds(500));
        }
    }
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
    // PB-005：新导入的书也要带 tags / series / rating 默认值（渲染层不必判 undefined）
    NormalizeBookMetadata(b);
    m_data["books"].push_back(b);
    ScheduleWrite();
    return b;
}

json DatabaseService::UpdateBookMetadata(int id, const json& patch)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!patch.is_object()) return nullptr;

    for (auto& book : m_data["books"]) {
        if (book.value("id", 0) != id) continue;

        json candidate = book;

        // 文本字段：允许 string 或 null（null 与空串等价，沿用 books 行的既有表示）。
        // cover_path 也走文本白名单：封面按需生成后（book:cover）需要把它写回，
        // 否则每次进书架都会重新抽封面/起 mutool（2026-10-29 review · B-04）。
        const char* textKeys[] = { "title", "author", "language", "publisher", "description", "cover_path" };
        for (const char* key : textKeys) {
            if (!patch.contains(key)) continue;
            if (!patch[key].is_string() && !patch[key].is_null()) return nullptr;
            candidate[key] = patch[key];
        }
        if (!candidate.contains("title") || !candidate["title"].is_string()) return nullptr;
        candidate["title"] = TrimBookText(candidate["title"].get<std::string>());
        if (candidate["title"].get<std::string>().empty()) return nullptr;  // 书名必填
        const char* trimKeys[] = { "author", "language", "publisher" };
        for (const char* key : trimKeys) {
            if (candidate.contains(key) && candidate[key].is_string()) {
                candidate[key] = TrimBookText(candidate[key].get<std::string>());
            }
        }

        if (patch.contains("tags")) {
            if (!patch["tags"].is_array()) return nullptr;
            candidate["tags"] = patch["tags"];      // 逐项清洗由 NormalizeBookMetadata 做
        }
        if (patch.contains("series_name")) {
            if (!patch["series_name"].is_string()) return nullptr;
            candidate["series_name"] = patch["series_name"];
        }
        if (patch.contains("series_index")) {
            if (!patch["series_index"].is_number()) return nullptr;
            candidate["series_index"] = patch["series_index"];
        }
        if (patch.contains("rating")) {
            if (!patch["rating"].is_number_integer()) return nullptr;
            candidate["rating"] = patch["rating"];
        }

        NormalizeBookMetadata(candidate);   // 去重 / 截断 / 夹紧 0..5 与 0..10000
        book = std::move(candidate);
        ScheduleWrite();
        return book;
    }
    return nullptr;
}

json DatabaseService::GetAllTags() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    std::vector<std::string> tags;
    for (const auto& book : m_data["books"]) {
        if (!book.contains("tags") || !book["tags"].is_array()) continue;
        for (const auto& tag : book["tags"]) {
            if (tag.is_string()) tags.push_back(tag.get<std::string>());
        }
    }
    std::sort(tags.begin(), tags.end());
    tags.erase(std::unique(tags.begin(), tags.end()), tags.end());
    json result = json::array();
    for (const std::string& tag : tags) result.push_back(tag);
    return result;
}

json DatabaseService::GetAllSeries() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    std::vector<std::string> series;
    for (const auto& book : m_data["books"]) {
        if (!book.contains("series_name") || !book["series_name"].is_string()) continue;
        const std::string name = book["series_name"].get<std::string>();
        if (!name.empty()) series.push_back(name);
    }
    std::sort(series.begin(), series.end());
    series.erase(std::unique(series.begin(), series.end()), series.end());
    json result = json::array();
    for (const std::string& name : series) result.push_back(name);
    return result;
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
    // reading_progress 存的是【以 bookId 为键的 object】（见 UpsertProgress），不是数组：
    // 对它用上面的 std::remove_if 会在 json object 上"只搬值、不搬键"——其它书的进度值
    // 被搬进别人的键位，随后按范围 erase 又会把末尾若干条整条删掉，表现为"删一本书顺带
    // 清掉另一本的阅读进度"。必须按键删除。
    m_data["reading_progress"].erase(std::to_string(id));
    removeByBookId("bookmarks");
    removeByBookId("highlights");
    removeByBookId("notes");
    removeByBookId("reading_sessions");
    // 书级设置此前无人清理（DeleteBookSettings 从未被调用），删书后会永久残留，
    // 故在此随其它关联数据一并清除。
    m_data["book_settings"].erase(std::to_string(id));
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

// ── Annotations（PB-021：统一表 + 旧表双写）──────────────────────

json DatabaseService::GetAnnotations(int bookId) const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    json out = json::array();
    for (const auto& a : m_data["annotations"]) {
        if (bookId <= 0 || a.value("bookId", 0) == bookId) out.push_back(a);
    }
    return out;
}

json DatabaseService::GetAnnotation(int id) const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    for (const auto& a : m_data["annotations"]) {
        if (a.value("id", 0) == id) return a;
    }
    return nullptr;
}

json DatabaseService::AddAnnotation(const json& annotation)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!IsValidAnnotationRow(annotation)) return nullptr;

    json row = annotation;
    row["version"] = 1;
    row["id"] = allocNextAnnotationId(m_data);
    if (row.value("createdAt", std::string()).empty()) row["createdAt"] = NowISO();
    row["updatedAt"] = NowISO();

    const std::string type = row.value("type", std::string());
    row["legacyType"] = type;
    row["legacyId"] = InsertLegacyRow(m_data, row);

    m_data["annotations"].push_back(row);
    ScheduleWrite();
    return row;
}

json DatabaseService::UpdateAnnotation(int id, const json& patch)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!patch.is_object()) return nullptr;

    for (auto& row : m_data["annotations"]) {
        if (row.value("id", 0) != id) continue;

        // 只允许改展示 / 位置字段：id / version / createdAt / legacy* 由这里维护。
        static const char* const kPatchable[] = {
            "bookId", "type", "locator", "color", "title", "note", "tags", "relatedId",
        };
        json candidate = row;
        for (const char* key : kPatchable) {
            if (patch.contains(key)) candidate[key] = patch[key];
        }
        if (!IsValidAnnotationRow(candidate)) return nullptr;

        const std::string oldType = row.value("type", std::string());
        const std::string newType = candidate.value("type", std::string());
        const int oldLegacyId = row.value("legacyId", 0);

        candidate["updatedAt"] = NowISO();
        candidate["legacyType"] = newType;
        if (oldLegacyId > 0 && oldType == newType
            && ReplaceLegacyRow(m_data, newType, oldLegacyId, candidate)) {
            candidate["legacyId"] = oldLegacyId;
        } else {
            // 首次双写（迁移前旧行已被删）或 type 变了：换表重建。
            if (oldLegacyId > 0) DeleteLegacyRow(m_data, oldType, oldLegacyId);
            candidate["legacyId"] = InsertLegacyRow(m_data, candidate);
        }

        row = std::move(candidate);
        ScheduleWrite();
        return row;
    }
    return nullptr;
}

void DatabaseService::DeleteAnnotation(int id)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    auto& arr = m_data["annotations"];
    for (auto it = arr.begin(); it != arr.end(); ++it) {
        if (it->value("id", 0) != id) continue;
        const std::string legacyType =
            it->value("legacyType", it->value("type", std::string()));
        const int legacyId = it->value("legacyId", 0);
        DeleteLegacyRow(m_data, legacyType, legacyId);
        arr.erase(it);
        ScheduleWrite();
        return;
    }
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



json DatabaseService::GetReadingSessions() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_data["reading_sessions"];
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


