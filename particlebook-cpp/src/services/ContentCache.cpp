#include "ContentCache.h"
#define WIN32_LEAN_AND_MEAN
#include <windows.h>

const std::vector<uint8_t>* ContentCache::Get(const std::string& path)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    auto it = m_cache.find(path);
    if (it == m_cache.end()) return nullptr;

    // Check if file has been modified since cache
    WIN32_FILE_ATTRIBUTE_DATA attrs;
    int wlen = MultiByteToWideChar(CP_UTF8, 0, path.c_str(), (int)path.size(), nullptr, 0);
    if (wlen <= 0) return nullptr;
    std::wstring wpath(wlen, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, path.c_str(), (int)path.size(), &wpath[0], wlen);

    if (!GetFileAttributesExW(wpath.c_str(), GetFileExInfoStandard, &attrs))
        return nullptr;

    int64_t ft = (static_cast<int64_t>(attrs.ftLastWriteTime.dwHighDateTime) << 32)
               | attrs.ftLastWriteTime.dwLowDateTime;

    if (ft != it->second.fileTime) {
        m_cache.erase(it);
        return nullptr;
    }

    return &it->second.data;
}

void ContentCache::Put(const std::string& path, std::vector<uint8_t> data, int64_t fileTime)
{
    std::lock_guard<std::mutex> lock(m_mutex);

    // Evict oldest if full
    if (m_cache.size() >= kMaxEntries) {
        m_cache.clear(); // Simple: clear all
    }

    CachedContent cc;
    cc.data = std::move(data);
    cc.fileTime = fileTime;
    m_cache[path] = std::move(cc);
}

void ContentCache::Clear()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    m_cache.clear();
}
