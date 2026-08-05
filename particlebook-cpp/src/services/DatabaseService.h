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

    void Load(const std::string& path);
    void FlushSync();
    int NextId();

    // Books
    json GetBooks() const;
    json GetBook(int id) const;
    void UpdateBookLastOpened(int id);
    json GetBookByPath(const std::string& path) const;
    json InsertBook(const json& book);
    void DeleteBook(int id);

    // Reading progress
    json GetProgress(int bookId) const;
    std::string DumpProgress() const;
    void UpsertProgress(int bookId, const json& progress);

    // Bookmarks
    json GetBookmarks(int bookId) const;
    void AddBookmark(const json& bm);
    void DeleteBookmark(int id);
    void UpdateBookmarkTitle(int id, const std::string& title);

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
    std::vector<int> GetShelvesForBook(int bookId) const;

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
    int GetReadingTimeForBook(int bookId) const;
    json GetAllReadingTime() const;
    json GetAllReadingProgress() const;

    // Settings
    json GetSettings() const;
    void UpdateSettings(const json& settings);
    json GetBookSettings(int bookId) const;
    void UpdateBookSettings(int bookId, const json& settings);
    void DeleteBookSettings(int bookId);

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
