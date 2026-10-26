#pragma once
#include <string>
#include <vector>
#include <cstdint>
#include <mutex>
#include <unordered_map>

struct CachedContent {
    std::vector<uint8_t> data;
    int64_t fileTime; // LastWriteTime as FILETIME
};

class ContentCache {
public:
    // Copies cached data if the file is unchanged; false = needs reload.
    // Returns a copy (not a pointer) so a concurrent Put/Clear can't dangle it.
    bool Get(const std::string& path, std::vector<uint8_t>& out);

    // Store file content in cache
    void Put(const std::string& path, std::vector<uint8_t> data, int64_t fileTime);

    // Clear cache
    void Clear();

private:
    std::mutex m_mutex;
    std::unordered_map<std::string, CachedContent> m_cache;
    static constexpr size_t kMaxEntries = 10;
};
