// Minimal ZIP reader — handles stored (uncompressed) and deflate entries.
// Uses miniz for deflate decompression.
//
// 安全前提：这里读取的 zip 来自**不可信输入**（Z-Library 下载的 EPUB、用户拖入的
// 任意文件），所以包内所有长度/偏移字段都当成攻击者可控的数值处理：
//   * 结构体一律用 memcpy 取出（不对齐的 reinterpret_cast 在 x64 上侥幸能跑，在
//     ARM64 上是 UB），且取出前先确认这段字节确实落在已读取的缓冲区里；
//   * 中央目录 / 条目大小先夹紧到「真实文件大小」与固定上限，再拿去分配；
//   * 名字长度不信任，超过上限直接判失败。
#include "ZipReader.h"
#include <windows.h>
#include <cstring>
#include <algorithm>

#define MINIZ_NO_TIME
#define MINIZ_NO_ARCHIVE_APIS
#define MINIZ_NO_ARCHIVE_WRITING_APIS
#include "miniz.h"
#pragma pack(push, 1)
struct ZipLocalHeader {
    uint32_t signature;      // 0x04034b50
    uint16_t version;
    uint16_t flags;
    uint16_t compression;
    uint16_t modTime;
    uint16_t modDate;
    uint32_t crc32;
    uint32_t compSize;
    uint32_t uncompSize;
    uint16_t nameLen;
    uint16_t extraLen;
};
#pragma pack(pop)

#pragma pack(push, 1)
struct ZipCentralDirEntry {
    uint32_t signature;      // 0x02014b50
    uint16_t versionMadeBy;
    uint16_t versionNeeded;
    uint16_t flags;
    uint16_t compression;
    uint16_t modTime;
    uint16_t modDate;
    uint32_t crc32;
    uint32_t compSize;
    uint32_t uncompSize;
    uint16_t nameLen;
    uint16_t extraLen;
    uint16_t commentLen;
    uint16_t diskStart;
    uint16_t internalAttr;
    uint32_t externalAttr;
    uint32_t localHeaderOffset;
};

struct ZipEOCD {
    uint32_t signature;      // 0x06054b50
    uint16_t diskNum;
    uint16_t diskStart;
    uint16_t entriesOnDisk;
    uint16_t totalEntries;
    uint32_t centralDirSize;
    uint32_t centralDirOffset;
    uint16_t commentLen;
};
#pragma pack(pop)

namespace {

// 单个容器字段的上限。EPUB/DOCX 的 manifest / 封面都远小于这些值；超过就是
// 畸形包（或压缩炸弹），直接拒绝比尝试分配更安全。
constexpr uint32_t kMaxCentralDirBytes = 256u * 1024u * 1024u;
constexpr uint32_t kMaxEntryBytes      = 256u * 1024u * 1024u;
constexpr uint16_t kMaxEntryNameBytes  = 4096;

constexpr uint32_t kLocalHeaderSig  = 0x04034b50u;
constexpr uint32_t kCentralDirSig   = 0x02014b50u;
constexpr uint32_t kEndOfCentralSig = 0x06054b50u;

// 定位 + 读；只有「读满了请求的字节数」才算成功（短读一律当失败，别拿未初始化
// 的缓冲区继续解析）。
bool ReadAt(HANDLE file, uint64_t offset, void* buffer, size_t bytes)
{
    if (bytes == 0) return true;
    LARGE_INTEGER position;
    position.QuadPart = static_cast<LONGLONG>(offset);
    if (!SetFilePointerEx(file, position, nullptr, FILE_BEGIN)) return false;
    DWORD read = 0;
    if (!ReadFile(file, buffer, static_cast<DWORD>(bytes), &read, nullptr)) return false;
    return read == bytes;
}

} // namespace

bool ReadZipEntry(const std::string& zipPath, const std::string& entryName, std::string& output)
{
    // Open file with wide path support
    int wlen = MultiByteToWideChar(CP_UTF8, 0, zipPath.c_str(), -1, nullptr, 0);
    if (wlen <= 0) return false;
    std::wstring wPath(wlen, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, zipPath.c_str(), -1, &wPath[0], wlen);

    HANDLE hFile = CreateFileW(wPath.c_str(), GENERIC_READ, FILE_SHARE_READ,
                               nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (hFile == INVALID_HANDLE_VALUE) return false;

    // Get file size
    LARGE_INTEGER liSize;
    if (!GetFileSizeEx(hFile, &liSize) ||
        liSize.QuadPart < static_cast<LONGLONG>(sizeof(ZipEOCD))) {
        CloseHandle(hFile);
        return false;
    }
    const uint64_t fileSize = static_cast<uint64_t>(liSize.QuadPart);

    // Find EOCD (scan from end, max 65535 + sizeof(EOCD) bytes back)
    const uint64_t tailSpan = 65535ull + static_cast<uint64_t>(sizeof(ZipEOCD));
    const uint64_t searchStart = (fileSize > tailSpan) ? (fileSize - tailSpan) : 0;
    const size_t searchLen = static_cast<size_t>(fileSize - searchStart);
    std::vector<uint8_t> buf(searchLen);
    if (!ReadAt(hFile, searchStart, buf.data(), buf.size())) {
        CloseHandle(hFile);
        return false;
    }

    // Scan for EOCD signature
    ZipEOCD eocd = {};
    bool haveEocd = false;
    for (size_t i = 0; i + sizeof(ZipEOCD) <= buf.size(); i++) {
        ZipEOCD candidate = {};
        std::memcpy(&candidate, buf.data() + i, sizeof(candidate));  // 不对齐读是 UB，先拷出来
        if (candidate.signature == kEndOfCentralSig) {
            eocd = candidate;
            haveEocd = true;
            break;
        }
    }
    if (!haveEocd) { CloseHandle(hFile); return false; }

    // ── 中央目录：偏移/大小都来自包内字段，先做三次夹紧 ─────────────────
    // 1) 偏移必须落在文件内；2) 大小不超过固定上限（防 4GB 分配）；
    // 3) 大小不超过「从该偏移到文件尾」的真实字节数。
    if (eocd.centralDirSize == 0 ||
        static_cast<uint64_t>(eocd.centralDirOffset) >= fileSize ||
        eocd.centralDirSize > kMaxCentralDirBytes) {
        CloseHandle(hFile);
        return false;
    }
    uint64_t centralBytes = eocd.centralDirSize;
    const uint64_t centralAvailable = fileSize - static_cast<uint64_t>(eocd.centralDirOffset);
    if (centralBytes > centralAvailable) centralBytes = centralAvailable;

    std::vector<uint8_t> cdBuf(static_cast<size_t>(centralBytes));
    if (!ReadAt(hFile, eocd.centralDirOffset, cdBuf.data(), cdBuf.size())) {
        CloseHandle(hFile);
        return false;
    }

    // ── 遍历中央目录 ───────────────────────────────────────────────────
    // 每轮先确认「定长结构体 + 名字 + extra + comment」整条记录都在 cdBuf 内：
    // 旧实现只判 pos < cdBuf.size()，于是畸形包可以让这里读越界的 46 字节结构体、
    // 并按 nameLen(≤64KB) 从越界地址构造 std::string（堆越界读 → 崩溃）。
    ZipCentralDirEntry foundEntry = {};
    bool found = false;
    size_t pos = 0;
    for (uint16_t i = 0; i < eocd.totalEntries; i++) {
        if (pos > cdBuf.size() || cdBuf.size() - pos < sizeof(ZipCentralDirEntry)) break;
        ZipCentralDirEntry entry = {};
        std::memcpy(&entry, cdBuf.data() + pos, sizeof(entry));
        if (entry.signature != kCentralDirSig) break;
        if (entry.nameLen == 0 || entry.nameLen > kMaxEntryNameBytes) break;

        const size_t nameOffset = pos + sizeof(ZipCentralDirEntry);
        const size_t recordEnd = nameOffset + static_cast<size_t>(entry.nameLen)
                               + static_cast<size_t>(entry.extraLen)
                               + static_cast<size_t>(entry.commentLen);
        if (recordEnd > cdBuf.size()) break;   // 记录越过中央目录末尾 → 畸形包

        const std::string name(reinterpret_cast<const char*>(cdBuf.data() + nameOffset),
                               entry.nameLen);
        if (name == entryName) {
            foundEntry = entry;
            found = true;
            break;
        }
        pos = recordEnd;
    }
    if (!found) { CloseHandle(hFile); return false; }

    // Validate local header offset
    const uint64_t localOffset = foundEntry.localHeaderOffset;
    if (localOffset >= fileSize ||
        fileSize - localOffset < static_cast<uint64_t>(sizeof(ZipLocalHeader))) {
        CloseHandle(hFile);
        return false;
    }

    // Read local file header first to get actual field values
    ZipLocalHeader lh = {};
    if (!ReadAt(hFile, localOffset, &lh, sizeof(lh)) || lh.signature != kLocalHeaderSig) {
        CloseHandle(hFile);
        return false;
    }

    // Use central directory sizes (more reliable), but validate against file
    const uint64_t dataOffset = localOffset + static_cast<uint64_t>(sizeof(ZipLocalHeader))
                              + static_cast<uint64_t>(lh.nameLen)
                              + static_cast<uint64_t>(lh.extraLen);
    if (dataOffset > fileSize) { CloseHandle(hFile); return false; }

    uint64_t compSize = foundEntry.compSize;
    const uint64_t dataAvailable = fileSize - dataOffset;
    if (compSize > dataAvailable) compSize = dataAvailable;   // Clamp to available file data
    if (compSize > kMaxEntryBytes) { CloseHandle(hFile); return false; }
    if (compSize == 0 && foundEntry.uncompSize > 0) { CloseHandle(hFile); return false; }

    // Read compressed data
    std::vector<uint8_t> compBuf(static_cast<size_t>(compSize));
    if (!ReadAt(hFile, dataOffset, compBuf.data(), compBuf.size())) {
        CloseHandle(hFile);
        return false;
    }
    CloseHandle(hFile);

    if (foundEntry.compression == 0) {
        // Stored (uncompressed)
        output.assign(compBuf.begin(), compBuf.end());
        return true;
    }
    if (foundEntry.compression == 8) {
        // DEFLATE (raw) — decompress with miniz tinfl
        // NOTE: ZIP uses raw deflate (RFC 1951), NOT zlib-wrapped (RFC 1950).
        // tinfl_decompress handles raw deflate; mz_uncompress expects zlib header.
        //
        // uncompSize 同样是包内字段：先夹紧到上限，否则一个几十 KB 的包就能让
        // 这里分配（并零初始化）最多 4GB。
        const uint64_t destLen = foundEntry.uncompSize;
        if (destLen == 0 || destLen > kMaxEntryBytes) return false;

        std::vector<uint8_t> destBuf(static_cast<size_t>(destLen));
        const size_t written = tinfl_decompress_mem_to_mem(
            destBuf.data(), destBuf.size(),
            compBuf.data(), compBuf.size(),
            0);
        if (written == TINFL_DECOMPRESS_MEM_TO_MEM_FAILED) {
            return false;
        }
        output.assign(reinterpret_cast<const char*>(destBuf.data()), written);
        return true;
    }

    return false;
}
