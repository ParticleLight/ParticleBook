#pragma once
#include <string>
#include <mutex>
#include <thread>
#include <condition_variable>
#include <chrono>
#include <vector>
#include "nlohmann/json.hpp"

using json = nlohmann::json;

class DatabaseService {
public:
    DatabaseService();
    ~DatabaseService();

    // PB-014: bump when the on-disk shape changes; every bump needs a
    // migration entry in DatabaseService.cpp and a database_test case.
    static constexpr int kCurrentSchemaVersion = 4;

    void Load(const std::string& path);
    int GetSchemaVersion() const;
    std::string Path() const;

    // PB-011: restore needs the 300ms writer stopped while the file is
    // replaced. Suspend() flushes + joins the writer; Resume() reloads the
    // (possibly replaced) file and starts a fresh writer. Both run on the UI
    // thread; no other DB call may run between them.
    void Suspend();
    void Resume();
    void FlushSync();
    // NOTE: there is deliberately no public "reserve an id" method. Ids are
    // allocated inside the Insert* methods via allocNextId(), which advances
    // nextId AND schedules a write in the same locked section. A public
    // NextId() would advance the counter without persisting it, so a crash
    // before the next write would hand out the same ids again.

    // Books
    json GetBooks() const;
    json GetBook(int id) const;
    void UpdateBookLastOpened(int id);
    json GetBookByPath(const std::string& path) const;
    json InsertBook(const json& book);
    void DeleteBook(int id);

    // PB-005 (schema v4): book metadata —— tags / series_name / series_index / rating.
    // 每本新书与每次加载都会补齐默认值（[] / "" / 0 / 0），所以旧行读取兼容：
    //   * UpdateBookMetadata 只接受白名单字段（书名/作者/语言/出版社/简介 +
    //     tags / series_name / series_index / rating），非法类型或空书名返回 null
    //     且不写库；返回更新后的完整 books 行（前端可直接替换本地缓存）。
    //   * GetAllTags / GetAllSeries 返回去重排序后的字符串数组（书库筛选用）。
    json UpdateBookMetadata(int id, const json& patch);
    json GetAllTags() const;
    json GetAllSeries() const;

    // Reading progress
    json GetProgress(int bookId) const;
    std::string DumpProgress() const;
    void UpsertProgress(int bookId, const json& progress);

    // Bookmarks
    json GetBookmarks(int bookId) const;
    void AddBookmark(const json& bm);
    void DeleteBookmark(int id);
    void UpdateBookmarkTitle(int id, const std::string& title);

    // Annotations (PB-021, schema v3)
    //
    // Unified rows in m_data["annotations"] (domain/Annotation.h JSON shape:
    // bookId / type / locator / color / title / note / createdAt / updatedAt /
    // tags / relatedId). The legacy tables (bookmarks / highlights / notes) are
    // still what the current renderer reads, so every write keeps them in sync:
    //   * AddAnnotation  -> insert into the legacy table for its type, and store
    //                       that row's id in annotation["legacyId"] / ["legacyType"].
    //   * UpdateAnnotation -> rebuild the legacy row in place (type change moves
    //                       the row to the new table and updates legacyId).
    //   * DeleteAnnotation -> delete the legacy row too.
    // Locator -> legacy fields is best effort (what the renderer understands):
    //   epub -> cfi; text -> cfi "pos-<offset>" (+ page = percent if present);
    //   html -> cfi = href; pdf -> page; scroll -> page = percent, progress.
    //   highlight text = locator.quote; note content = note; note.highlight_id =
    //   relatedId.
    // A write whose row is invalid (bookId <= 0 / unknown type / empty locator)
    // is rejected with a null return and touches no table.
    // NOTE: the reverse direction is not mirrored yet — writes that go through
    // the old AddBookmark/AddHighlight/AddNote endpoints only touch the old
    // tables until the renderer moves to the annotations API (PB-021-C).
    // bookId <= 0 in GetAnnotations/GetAnnotation means "all books".
    json GetAnnotations(int bookId) const;
    json GetAnnotation(int id) const;
    json AddAnnotation(const json& annotation);
    // patch: subset of {bookId, type, locator, color, title, note, tags,
    // relatedId}; id / createdAt / legacy* / version are ignored. Returns the
    // updated row, or null when the id is unknown or the merged row is invalid.
    json UpdateAnnotation(int id, const json& patch);
    void DeleteAnnotation(int id);

    // Highlights
    json GetHighlights(int bookId) const;
    void AddHighlight(const json& hl);
    void DeleteHighlight(int id);

    // Notes
    json GetNotes(int bookId) const;
    void AddNote(const json& note);
    void UpdateNote(int id, const std::string& content);
    void DeleteNote(int id);

    // Bookshelves
    json GetBookshelves() const;
    json AddBookshelf(const std::string& name);
    void DeleteBookshelf(int id);
    void RenameBookshelf(int id, const std::string& name);
    std::vector<int> GetBooksInShelf(int shelfId) const;
    void AddBookToShelf(int shelfId, int bookId);
    void RemoveBookFromShelf(int shelfId, int bookId);

    // Book sources
    json GetBookSources() const;
    json GetBookSource(int id) const;
    json InsertBookSource(const json& source);
    void UpdateBookSource(int id, const json& updates);
    void DeleteBookSource(int id);
    void ToggleBookSource(int id);
    void ClearAllBookSources();

    // Reading sessions
    int StartReadingSession(int bookId);
    void EndReadingSession(int sessionId);
    void UpdateReadingSessionDuration(int sessionId, int seconds);
    json GetAllReadingTime() const;
    // PB-007: raw reading_sessions rows for daily/weekly heatmap & goals.
    json GetReadingSessions() const;
    json GetAllReadingProgress() const;

    // Settings
    json GetSettings() const;
    void UpdateSettings(const json& settings);
    json GetBookSettings(int bookId) const;
    void UpdateBookSettings(int bookId, const json& settings);

private:
    void ScheduleWrite();
    void WriterThread();
    void EnsureDefaults();
    bool WriteAtomic(const json& data);
    void BackupCorruptFile(const std::string& path);

    mutable std::mutex m_mutex;
    json m_data;
    std::string m_path;

    std::thread m_writeThread;
    std::condition_variable m_cv;
    bool m_dirty = false;
    bool m_running = true;
    std::chrono::milliseconds m_debounce{300};
};
