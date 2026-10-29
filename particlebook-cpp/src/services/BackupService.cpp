#include "services/BackupService.h"

#include "services/DatabaseService.h"   // kCurrentSchemaVersion（只读常量，不接线）
#include "utils/ZipReader.h"            // 读 zip 条目（EPUB 已在用）

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <ctime>
#include <string>
#include <utility>
#include <vector>

#include "utils/pb_win.h"

#include <bcrypt.h>
#pragma comment(lib, "bcrypt.lib")

// miniz：只用到 raw deflate（miniz_tdef.c）与 crc32（miniz.c），两者都已在主
// target 里编译。刻意不 include 任何 ZIP 归档 API 的使用，避免依赖 miniz_zip.c。
#define MINIZ_NO_TIME
#define MINIZ_NO_ARCHIVE_APIS
#define MINIZ_NO_ARCHIVE_WRITING_APIS
// 关键：miniz 默认打开 zlib 兼容名（#define compress mz_compress、
// #define crc32 mz_crc32 …），会把 BackupOptions::compress 这类成员名顶掉。
#define MINIZ_NO_ZLIB_COMPATIBLE_NAMES
#include "miniz.h"

namespace pb {
namespace {

// ── UTF-8 ↔ UTF-16（Win32 全用 W 后缀；对外统一 UTF-8）─────────────
std::wstring Widen(const std::string& utf8)
{
    if (utf8.empty()) return std::wstring();
    const int need = MultiByteToWideChar(CP_UTF8, 0, utf8.c_str(),
                                         static_cast<int>(utf8.size()), nullptr, 0);
    if (need <= 0) return std::wstring();
    std::wstring wide(static_cast<std::size_t>(need), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, utf8.c_str(), static_cast<int>(utf8.size()),
                        &wide[0], need);
    return wide;
}

std::string Narrow(const std::wstring& wide)
{
    if (wide.empty()) return std::string();
    const int need = WideCharToMultiByte(CP_UTF8, 0, wide.c_str(),
                                         static_cast<int>(wide.size()), nullptr, 0,
                                         nullptr, nullptr);
    if (need <= 0) return std::string();
    std::string utf8(static_cast<std::size_t>(need), '\0');
    WideCharToMultiByte(CP_UTF8, 0, wide.c_str(), static_cast<int>(wide.size()),
                        &utf8[0], need, nullptr, nullptr);
    return utf8;
}

// ZIP32 单条目 / 单归档上限（不含 ZIP64）。
constexpr std::uint64_t kMaxZip32Bytes = 0xFFFFFFFEull;
constexpr std::uint32_t kLocalHeaderSig = 0x04034b50u;
constexpr std::uint32_t kCentralDirSig = 0x02014b50u;
constexpr std::uint32_t kEndOfCentralSig = 0x06054b50u;

std::string BaseName(const std::string& utf8Path)
{
    const std::size_t slash = utf8Path.find_last_of("\\/");
    return (slash == std::string::npos) ? utf8Path : utf8Path.substr(slash + 1);
}

// ── 时间（清单 / zip 条目都用 UTC，避免时区与夏令时影响可复现性）────
std::string FormatIso8601Utc(const std::tm& t)
{
    char buf[32];
    std::snprintf(buf, sizeof buf, "%04d-%02d-%02dT%02d:%02d:%02dZ",
                  t.tm_year + 1900, t.tm_mon + 1, t.tm_mday,
                  t.tm_hour, t.tm_min, t.tm_sec);
    return std::string(buf);
}

bool ParseIso8601Utc(const std::string& text, std::tm& out)
{
    int year = 0;
    int month = 0;
    int day = 0;
    int hour = 0;
    int minute = 0;
    int second = 0;
    if (std::sscanf(text.c_str(), "%4d-%2d-%2dT%2d:%2d:%2d",
                    &year, &month, &day, &hour, &minute, &second) != 6) {
        return false;
    }
    if (year < 1980 || year > 2107) return false;  // ZIP 的 DOS 时间可表示范围
    if (month < 1 || month > 12) return false;
    if (day < 1 || day > 31) return false;
    if (hour < 0 || hour > 23) return false;
    if (minute < 0 || minute > 59) return false;
    if (second < 0 || second > 60) return false;

    std::tm value = {};
    value.tm_year = year - 1900;
    value.tm_mon = month - 1;
    value.tm_mday = day;
    value.tm_hour = hour;
    value.tm_min = minute;
    value.tm_sec = second;
    out = value;
    return true;
}

// "YYYY-MM-DDTHH:MM:SSZ" → "YYYYMMDD-HHMMSS"（文件名用）。
std::string CompactStamp(const std::string& iso8601)
{
    std::tm value = {};
    if (!ParseIso8601Utc(iso8601, value)) return std::string();
    char buf[32];
    std::snprintf(buf, sizeof buf, "%04d%02d%02d-%02d%02d%02d",
                  value.tm_year + 1900, value.tm_mon + 1, value.tm_mday,
                  value.tm_hour, value.tm_min, value.tm_sec);
    return std::string(buf);
}

void DosFromTm(const std::tm& t, std::uint16_t& dosDate, std::uint16_t& dosTime)
{
    int year = t.tm_year + 1900;
    if (year < 1980) year = 1980;
    if (year > 2107) year = 2107;
    dosDate = static_cast<std::uint16_t>(((year - 1980) << 9) |
                                         ((t.tm_mon + 1) << 5) | t.tm_mday);
    dosTime = static_cast<std::uint16_t>((t.tm_hour << 11) |
                                         (t.tm_min << 5) | (t.tm_sec / 2));
}

void ReportProgress(const BackupProgressFn& progress, const char* stage,
                    std::int64_t processed, std::int64_t total,
                    std::int64_t bytesProcessed, std::int64_t bytesTotal,
                    const std::string& message)
{
    if (!progress) return;
    BackupProgress update;
    update.stage = stage;
    update.processed = processed;
    update.total = total;
    update.bytesProcessed = bytesProcessed;
    update.bytesTotal = bytesTotal;
    update.message = message;
    progress(update);
}

// 封面条目名里的序号前缀："0001"。
std::string CoverEntryPrefix(std::size_t index)
{
    char buffer[16];
    std::snprintf(buffer, sizeof buffer, "%04u", static_cast<unsigned>(index));
    return std::string(buffer);
}

std::int64_t FileTimeToUnixSeconds(const FILETIME& ft)
{
    ULARGE_INTEGER value;
    value.LowPart = ft.dwLowDateTime;
    value.HighPart = ft.dwHighDateTime;
    const std::uint64_t kEpochDifference = 116444736000000000ull;  // 1601 → 1970
    if (value.QuadPart <= kEpochDifference) return 0;
    return static_cast<std::int64_t>((value.QuadPart - kEpochDifference) / 10000000ull);
}

// ── 文件 IO（宽字符路径；失败一律返回稳定错误码）────────────────────
bool FileExistsUtf8(const std::string& utf8Path)
{
    const std::wstring wide = Widen(utf8Path);
    if (wide.empty()) return false;
    const DWORD attributes = GetFileAttributesW(wide.c_str());
    if (attributes == INVALID_FILE_ATTRIBUTES) return false;
    return (attributes & FILE_ATTRIBUTE_DIRECTORY) == 0;
}

bool ReadFileBytesUtf8(const std::string& utf8Path, std::string& out, std::string& code,
                       std::size_t limit = kMaxZip32Bytes)
{
    const std::wstring wide = Widen(utf8Path);
    if (wide.empty()) {
        code = backup_codes::kDbUnreadable;
        return false;
    }
    HANDLE handle = CreateFileW(wide.c_str(), GENERIC_READ,
                                FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                                nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (handle == INVALID_HANDLE_VALUE) {
        const DWORD error = GetLastError();
        const bool missing = (error == ERROR_FILE_NOT_FOUND || error == ERROR_PATH_NOT_FOUND);
        code = missing ? backup_codes::kDbMissing : backup_codes::kDbUnreadable;
        return false;
    }

    LARGE_INTEGER size = {};
    if (!GetFileSizeEx(handle, &size) || size.QuadPart < 0) {
        CloseHandle(handle);
        code = backup_codes::kDbUnreadable;
        return false;
    }
    if (static_cast<std::uint64_t>(size.QuadPart) > static_cast<std::uint64_t>(limit)) {
        CloseHandle(handle);
        code = backup_codes::kDbTooLarge;
        return false;
    }

    std::string bytes;
    bytes.resize(static_cast<std::size_t>(size.QuadPart));
    std::size_t offset = 0;
    while (offset < bytes.size()) {
        const std::size_t remaining = bytes.size() - offset;
        const DWORD want = static_cast<DWORD>(remaining > (1u << 20) ? (1u << 20) : remaining);
        DWORD got = 0;
        if (!ReadFile(handle, &bytes[offset], want, &got, nullptr) || got == 0) {
            CloseHandle(handle);
            code = backup_codes::kDbUnreadable;
            return false;
        }
        offset += got;
    }
    CloseHandle(handle);
    out.swap(bytes);
    code.clear();
    return true;
}

// 同目录临时文件 + MoveFileEx(REPLACE_EXISTING)：要么旧文件完整，要么新文件完整。
bool WriteFileAtomicallyUtf8(const std::string& utf8Path, const std::string& bytes,
                             std::string& code, const char* failureCode)
{
    const std::wstring target = Widen(utf8Path);
    if (target.empty()) {
        code = failureCode;
        return false;
    }
    const std::wstring temporary =
        target + L".pb-tmp-" + std::to_wstring(GetCurrentProcessId());

    HANDLE handle = CreateFileW(temporary.c_str(), GENERIC_WRITE, 0, nullptr,
                                CREATE_ALWAYS,
                                FILE_ATTRIBUTE_NORMAL | FILE_FLAG_WRITE_THROUGH,
                                nullptr);
    if (handle == INVALID_HANDLE_VALUE) {
        code = failureCode;
        return false;
    }

    bool wrote = true;
    std::size_t offset = 0;
    while (offset < bytes.size()) {
        const std::size_t remaining = bytes.size() - offset;
        const DWORD want = static_cast<DWORD>(remaining > (1u << 20) ? (1u << 20) : remaining);
        DWORD put = 0;
        if (!WriteFile(handle, bytes.data() + offset, want, &put, nullptr) || put != want) {
            wrote = false;
            break;
        }
        offset += put;
    }
    if (wrote && !FlushFileBuffers(handle)) wrote = false;
    CloseHandle(handle);

    if (wrote) {
        wrote = MoveFileExW(temporary.c_str(), target.c_str(),
                            MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH) != FALSE;
    }
    if (!wrote) {
        DeleteFileW(temporary.c_str());
        code = failureCode;
        return false;
    }
    code.clear();
    return true;
}

// 在扩展名前插入 "-n"：reader.json → reader-2.json
std::wstring SuffixFileName(const std::wstring& wide, int suffix)
{
    const std::size_t slash = wide.find_last_of(L"\\/");
    const std::size_t dot = wide.find_last_of(L'.');
    const bool hasExtension = (dot != std::wstring::npos) &&
                              (slash == std::wstring::npos || dot > slash);
    const std::wstring tail = L"-" + std::to_wstring(suffix);
    if (!hasExtension) return wide + tail;
    return wide.substr(0, dot) + tail + wide.substr(dot);
}

// 复制到「不存在」的路径（不覆盖已有备份）；已存在则加 -2 / -3 …
bool CopyFileUniqueUtf8(const std::string& sourcePath, const std::string& preferredTarget,
                        std::string& actualTarget, std::string& code)
{
    const std::wstring source = Widen(sourcePath);
    const std::wstring target = Widen(preferredTarget);
    if (source.empty() || target.empty()) {
        code = backup_codes::kPreRestoreFailed;
        return false;
    }
    for (int attempt = 1; attempt <= 1000; ++attempt) {
        const std::wstring candidate = (attempt == 1) ? target : SuffixFileName(target, attempt);
        if (CopyFileW(source.c_str(), candidate.c_str(), TRUE)) {
            actualTarget = Narrow(candidate);
            code.clear();
            return true;
        }
        const DWORD error = GetLastError();
        if (error != ERROR_FILE_EXISTS && error != ERROR_ALREADY_EXISTS) {
            code = backup_codes::kPreRestoreFailed;
            return false;
        }
    }
    code = backup_codes::kPreRestoreFailed;
    return false;
}

// ── 封面路径 / 条目名（PB-011-B）────────────────────────────────────
char LowerAsciiChar(char value)
{
    return (value >= 'A' && value <= 'Z') ? static_cast<char>(value - 'A' + 'a') : value;
}

bool EqualsPathNoCase(const std::string& left, const std::string& right)
{
    if (left.size() != right.size()) return false;
    for (std::size_t i = 0; i < left.size(); ++i) {
        if (LowerAsciiChar(left[i]) != LowerAsciiChar(right[i])) return false;
    }
    return true;
}

// 忽略大小写的前缀判定（Windows 路径比较口径；只用于「是否在某个目录下」这类
// 快速裁决，不做完整规范化）。
bool StartsWithNoCase(const std::string& text, const std::string& prefix)
{
    if (prefix.size() > text.size()) return false;
    for (std::size_t i = 0; i < prefix.size(); ++i) {
        if (LowerAsciiChar(text[i]) != LowerAsciiChar(prefix[i])) return false;
    }
    return true;
}

std::string ParentDirectoryOf(const std::string& path)
{
    const std::size_t slash = path.find_last_of("\\/");
    if (slash == std::string::npos) return std::string();
    return path.substr(0, slash);
}

std::string JoinPath(const std::string& directory, const std::string& fileName)
{
    if (directory.empty()) return fileName;
    const char last = directory.back();
    if (last == '\\' || last == '/') return directory + fileName;
    return directory + "\\" + fileName;
}

// 只处理「纯文件名」，在扩展名前插 -n：cover.png → cover-2.png
std::string SuffixFileNameUtf8(const std::string& fileName, int suffix)
{
    const std::size_t dot = fileName.find_last_of('.');
    const std::string tail = "-" + std::to_string(suffix);
    if (dot == std::string::npos || dot == 0) return fileName + tail;
    return fileName.substr(0, dot) + tail + fileName.substr(dot);
}

bool EnsureDirectoryUtf8(const std::string& utf8Directory)
{
    const std::wstring wide = Widen(utf8Directory);
    if (wide.empty()) return false;
    const DWORD attributes = GetFileAttributesW(wide.c_str());
    if (attributes != INVALID_FILE_ATTRIBUTES) {
        return (attributes & FILE_ATTRIBUTE_DIRECTORY) != 0;
    }
    return CreateDirectoryW(wide.c_str(), nullptr) != FALSE;
}

// 把路径最后一段变成可以安全落盘的 Windows 文件名。
std::string SanitizeCoverFileName(const std::string& raw)
{
    std::string name;
    name.reserve(raw.size());
    for (const char ch : raw) {
        const unsigned char value = static_cast<unsigned char>(ch);
        if (ch == '<' || ch == '>' || ch == ':' || ch == '"' || ch == '|' ||
            ch == '?' || ch == '*' || ch == '\\' || ch == '/' || value < 0x20) {
            name.push_back('_');
        } else {
            name.push_back(ch);
        }
    }
    while (!name.empty() && (name.back() == '.' || name.back() == ' ')) name.pop_back();
    if (name.empty()) name = "cover";
    if (name.size() > 120) name = name.substr(name.size() - 120);  // 保留扩展名
    return name;
}

// 目标文件已存在就加 -2 / -3 …，避免把用户别的封面顶掉。
std::string UniqueCoverTarget(const std::string& directory, const std::string& fileName)
{
    const std::string base = fileName.empty() ? std::string("cover") : fileName;
    std::string candidate = JoinPath(directory, base);
    for (int attempt = 2; attempt <= 200 && FileExistsUtf8(candidate); ++attempt) {
        candidate = JoinPath(directory, SuffixFileNameUtf8(base, attempt));
    }
    return candidate;
}

// 只接受「盘符:绝对路径」，且任何一段都不是 ..（UNC / 相对路径一律走 coverDir）。
bool IsSafeAbsoluteCoverPath(const std::string& path)
{
    if (path.size() < 3 || path.size() > 30000) return false;
    if (path.find('\0') != std::string::npos) return false;
    if (!std::isalpha(static_cast<unsigned char>(path[0])) || path[1] != ':' ||
        (path[2] != '\\' && path[2] != '/')) {
        return false;
    }
    std::string normalized = path;
    for (char& ch : normalized) {
        if (ch == '/') ch = '\\';
    }
    std::size_t position = 3;
    while (position <= normalized.size()) {
        const std::size_t slash = normalized.find('\\', position);
        const std::size_t end = (slash == std::string::npos) ? normalized.size() : slash;
        if (normalized.compare(position, end - position, "..") == 0) return false;
        if (slash == std::string::npos) break;
        position = slash + 1;
    }
    return true;
}

// path 是否严格位于 directory 之内（同级或更浅都不算）。两边都做「斜杠归一 + 忽略
// 大小写」的 Windows 口径比较；不解析 . 与符号链接 —— 这里要的是「明显不是外部
// 路径」的快速判定，不是完整的安全沙箱。
bool IsWithinDirectory(const std::string& path, const std::string& directory)
{
    if (path.empty() || directory.empty()) return false;
    std::string candidate = path;
    std::string root = directory;
    for (char& ch : candidate) {
        if (ch == '/') ch = '\\';
    }
    for (char& ch : root) {
        if (ch == '/') ch = '\\';
    }
    while (root.size() > 2 && root.back() == '\\') root.pop_back();   // "C:\\" → "C:"
    if (root.size() + 1 >= candidate.size()) return false;   // 必须还有一段子路径
    if (!StartsWithNoCase(candidate, root)) return false;
    return candidate[root.size()] == '\\';
}

// 恢复封面的目标路径裁决。备份包能决定 sourcePath 这个绝对路径，所以只允许写进
// **应用自己的数据目录**：
//   * coverDir（默认 <数据库目录>\covers）
//   * 目标数据库所在目录（<UserData>\data）
//   * 目标数据库目录的上一级（<UserData>，兼容封面直接放在数据根下的旧包）
// 其余一律回落到 coverDir —— 否则一个伪造的「备份」就能往 Startup 目录丢文件。
bool IsAllowedCoverRestorePath(const std::string& path, const std::string& targetDbPath,
                               const std::string& coverDirectory)
{
    if (!IsSafeAbsoluteCoverPath(path)) return false;
    if (!coverDirectory.empty() && IsWithinDirectory(path, coverDirectory)) return true;
    const std::string dbDirectory = ParentDirectoryOf(targetDbPath);
    if (dbDirectory.empty()) return false;
    if (IsWithinDirectory(path, dbDirectory)) return true;
    const std::string dataRoot = ParentDirectoryOf(dbDirectory);
    if (!dataRoot.empty() && IsWithinDirectory(path, dataRoot)) return true;
    return false;
}

// books[].cover_path 去重后的绝对路径列表（book 里可能是 null / 非字符串）。
std::vector<std::string> CollectCoverPaths(const nlohmann::json& database)
{
    std::vector<std::string> paths;
    const auto books = database.find("books");
    if (books == database.end() || !books->is_array()) return paths;
    for (const auto& book : *books) {
        if (!book.is_object()) continue;
        const auto cover = book.find("cover_path");
        if (cover == book.end() || !cover->is_string()) continue;
        const std::string path = cover->get<std::string>();
        if (path.empty()) continue;
        bool duplicate = false;
        for (const std::string& existing : paths) {
            if (EqualsPathNoCase(existing, path)) {
                duplicate = true;
                break;
            }
        }
        if (!duplicate) paths.push_back(path);
    }
    return paths;
}

// 恢复前统一校验：所有 covers[].entry 必须是 covers/ 下的安全相对路径。
bool ValidateCoverEntries(const std::vector<BackupCoverEntry>& covers,
                          std::string* badEntry)
{
    for (const BackupCoverEntry& cover : covers) {
        if (!BackupService::IsSafeCoverEntryName(cover.entry)) {
            if (badEntry) *badEntry = cover.entry;
            return false;
        }
    }
    return true;
}

// ── ZIP 写入（store / raw deflate；无 ZIP64、无数据描述符）─────────
void AppendU16(std::string& buffer, std::uint16_t value)
{
    buffer.push_back(static_cast<char>(value & 0xFFu));
    buffer.push_back(static_cast<char>((value >> 8) & 0xFFu));
}

void AppendU32(std::string& buffer, std::uint32_t value)
{
    buffer.push_back(static_cast<char>(value & 0xFFu));
    buffer.push_back(static_cast<char>((value >> 8) & 0xFFu));
    buffer.push_back(static_cast<char>((value >> 16) & 0xFFu));
    buffer.push_back(static_cast<char>((value >> 24) & 0xFFu));
}

bool DeflateRaw(const std::string& source, std::string& compressed)
{
    if (source.empty()) {
        compressed.clear();
        return true;
    }
    std::size_t compressedSize = 0;
    const int flags = static_cast<int>(
        tdefl_create_comp_flags_from_zip_params(6, -15, MZ_DEFAULT_STRATEGY));
    void* heap = tdefl_compress_mem_to_heap(source.data(), source.size(),
                                            &compressedSize, flags);
    if (!heap) return false;
    compressed.assign(static_cast<const char*>(heap), compressedSize);
    mz_free(heap);
    return true;
}

class ZipWriter {
public:
    void SetModifiedUtc(const std::tm& utc) { DosFromTm(utc, m_dosDate, m_dosTime); }

    bool Add(const std::string& name, const std::string& data, bool allowCompress)
    {
        if (m_entries.size() >= 0xFFFEu) return false;
        if (data.size() > kMaxZip32Bytes) return false;
        if (m_buffer.size() > kMaxZip32Bytes) return false;

        Entry entry;
        entry.name = name;
        entry.localHeaderOffset = static_cast<std::uint32_t>(m_buffer.size());
        entry.dosDate = m_dosDate;
        entry.dosTime = m_dosTime;
        entry.uncompressedSize = static_cast<std::uint32_t>(data.size());
        entry.crc32 = static_cast<std::uint32_t>(
            mz_crc32(MZ_CRC32_INIT,
                     reinterpret_cast<const unsigned char*>(data.data()),
                     data.size()));

        std::string compressed;
        const bool useDeflate = allowCompress && data.size() >= 64 &&
                                DeflateRaw(data, compressed) &&
                                compressed.size() + 16 < data.size();
        const std::string& payload = useDeflate ? compressed : data;
        if (payload.size() > kMaxZip32Bytes) return false;

        entry.method = useDeflate ? 8 : 0;
        entry.compressedSize = static_cast<std::uint32_t>(payload.size());

        AppendU32(m_buffer, kLocalHeaderSig);
        AppendU16(m_buffer, 20);  // version needed (2.0)
        AppendU16(m_buffer, 0);   // flags: 无加密 / 无数据描述符
        AppendU16(m_buffer, entry.method);
        AppendU16(m_buffer, entry.dosTime);
        AppendU16(m_buffer, entry.dosDate);
        AppendU32(m_buffer, entry.crc32);
        AppendU32(m_buffer, entry.compressedSize);
        AppendU32(m_buffer, entry.uncompressedSize);
        AppendU16(m_buffer, static_cast<std::uint16_t>(entry.name.size()));
        AppendU16(m_buffer, 0);   // extra field length
        m_buffer += entry.name;
        m_buffer += payload;

        m_entries.push_back(std::move(entry));
        return m_buffer.size() <= kMaxZip32Bytes;
    }

    bool Finish(std::string& archive)
    {
        if (m_buffer.size() > kMaxZip32Bytes) return false;
        const std::uint32_t centralOffset = static_cast<std::uint32_t>(m_buffer.size());

        for (const Entry& entry : m_entries) {
            AppendU32(m_buffer, kCentralDirSig);
            AppendU16(m_buffer, 20);  // version made by (MS-DOS 2.0)
            AppendU16(m_buffer, 20);  // version needed
            AppendU16(m_buffer, 0);   // flags
            AppendU16(m_buffer, entry.method);
            AppendU16(m_buffer, entry.dosTime);
            AppendU16(m_buffer, entry.dosDate);
            AppendU32(m_buffer, entry.crc32);
            AppendU32(m_buffer, entry.compressedSize);
            AppendU32(m_buffer, entry.uncompressedSize);
            AppendU16(m_buffer, static_cast<std::uint16_t>(entry.name.size()));
            AppendU16(m_buffer, 0);   // extra field length
            AppendU16(m_buffer, 0);   // comment length
            AppendU16(m_buffer, 0);   // disk number start
            AppendU16(m_buffer, 0);   // internal attributes
            AppendU32(m_buffer, 0);   // external attributes
            AppendU32(m_buffer, entry.localHeaderOffset);
            m_buffer += entry.name;
        }

        const std::uint32_t centralSize =
            static_cast<std::uint32_t>(m_buffer.size()) - centralOffset;
        AppendU32(m_buffer, kEndOfCentralSig);
        AppendU16(m_buffer, 0);  // this disk
        AppendU16(m_buffer, 0);  // disk with central directory
        AppendU16(m_buffer, static_cast<std::uint16_t>(m_entries.size()));
        AppendU16(m_buffer, static_cast<std::uint16_t>(m_entries.size()));
        AppendU32(m_buffer, centralSize);
        AppendU32(m_buffer, centralOffset);
        AppendU16(m_buffer, 0);  // archive comment length

        if (m_buffer.size() > kMaxZip32Bytes) return false;
        archive = std::move(m_buffer);
        m_buffer.clear();
        m_entries.clear();
        return true;
    }

private:
    struct Entry {
        std::string   name;
        std::uint16_t method = 0;
        std::uint16_t dosTime = 0;
        std::uint16_t dosDate = 0;
        std::uint32_t crc32 = 0;
        std::uint32_t compressedSize = 0;
        std::uint32_t uncompressedSize = 0;
        std::uint32_t localHeaderOffset = 0;
    };

    std::string m_buffer;
    std::vector<Entry> m_entries;
    std::uint16_t m_dosDate = 0;
    std::uint16_t m_dosTime = 0;
};

// ── reader.json 的小读取器 ─────────────────────────────────────────
int CountBooks(const nlohmann::json& database)
{
    const auto books = database.find("books");
    if (books == database.end() || !books->is_array()) return 0;
    return static_cast<int>(books->size());
}

// PB-014：v1 = 没有 schema_version 的旧库；有就用它的值。
int DetectSchemaVersion(const nlohmann::json& database)
{
    const auto version = database.find("schema_version");
    if (version != database.end() && version->is_number_integer()) {
        const int value = version->get<int>();
        if (value >= 0) return value;
    }
    return 1;
}

bool ReadStringField(const nlohmann::json& object, const char* key, std::string& out)
{
    const auto found = object.find(key);
    if (found == object.end() || !found->is_string()) return false;
    out = found->get<std::string>();
    return true;
}

} // namespace

// ── BackupManifest / BackupCoverEntry ──────────────────────────────
nlohmann::json BackupCoverEntry::ToJson() const
{
    nlohmann::json cover;
    cover["entry"] = entry;
    cover["sourcePath"] = sourcePath;
    cover["sha256"] = sha256;
    cover["bytes"] = bytes;
    return cover;
}

bool BackupCoverEntry::FromJson(const nlohmann::json& manifest, BackupCoverEntry& out,
                                std::string* error)
{
    const auto fail = [error](const char* why) {
        if (error) *error = why;
        return false;
    };
    if (!manifest.is_object()) return fail("cover entry is not a JSON object");

    std::string entry;
    if (!ReadStringField(manifest, "entry", entry) || entry.empty()) {
        return fail("cover entry name missing");
    }

    std::string sha256;
    if (!ReadStringField(manifest, "sha256", sha256) || sha256.size() != 64) {
        return fail("cover sha256 missing or not 64 characters");
    }
    for (char& digit : sha256) {
        if (!std::isxdigit(static_cast<unsigned char>(digit))) {
            return fail("cover sha256 is not hexadecimal");
        }
        digit = static_cast<char>(std::tolower(static_cast<unsigned char>(digit)));
    }

    std::uint64_t bytes = 0;
    const auto bytesField = manifest.find("bytes");
    if (bytesField == manifest.end()) return fail("cover bytes missing");
    if (bytesField->is_number_unsigned()) {
        bytes = bytesField->get<std::uint64_t>();
    } else if (bytesField->is_number_integer()) {
        const long long value = bytesField->get<long long>();
        if (value < 0) return fail("cover bytes < 0");
        bytes = static_cast<std::uint64_t>(value);
    } else {
        return fail("cover bytes missing");
    }

    std::string sourcePath;
    ReadStringField(manifest, "sourcePath", sourcePath);  // v2 以后可选

    out.entry = entry;
    out.sourcePath = sourcePath;
    out.sha256 = sha256;
    out.bytes = bytes;
    if (error) error->clear();
    return true;
}

nlohmann::json BackupManifest::ToJson() const
{
    nlohmann::json manifest;
    manifest["format"] = format;
    manifest["formatVersion"] = formatVersion;
    manifest["appVersion"] = appVersion;
    manifest["schemaVersion"] = schemaVersion;
    manifest["createdAt"] = createdAt;
    manifest["bookCount"] = bookCount;
    manifest["sourceFileName"] = sourceFileName;
    manifest["dbBytes"] = dbBytes;
    manifest["sha256"] = sha256;
    nlohmann::json coverArray = nlohmann::json::array();
    for (const BackupCoverEntry& cover : covers) coverArray.push_back(cover.ToJson());
    manifest["covers"] = std::move(coverArray);
    return manifest;
}

bool BackupManifest::FromJson(const nlohmann::json& manifest, BackupManifest& out,
                              std::string* error)
{
    const auto fail = [error](const char* why) {
        if (error) *error = why;
        return false;
    };

    if (!manifest.is_object()) return fail("manifest is not a JSON object");

    std::string format;
    if (!ReadStringField(manifest, "format", format)) return fail("format missing");
    if (format != "particlebook-backup") return fail("format is not particlebook-backup");

    const auto formatVersion = manifest.find("formatVersion");
    if (formatVersion == manifest.end() || !formatVersion->is_number_integer()) {
        return fail("formatVersion missing");
    }
    if (formatVersion->get<int>() < 1) return fail("formatVersion < 1");

    const auto schemaVersion = manifest.find("schemaVersion");
    if (schemaVersion == manifest.end() || !schemaVersion->is_number_integer()) {
        return fail("schemaVersion missing");
    }
    if (schemaVersion->get<int>() < 0) return fail("schemaVersion < 0");

    std::string createdAt;
    if (!ReadStringField(manifest, "createdAt", createdAt) || createdAt.empty()) {
        return fail("createdAt missing");
    }

    const auto bookCount = manifest.find("bookCount");
    if (bookCount == manifest.end() || !bookCount->is_number_integer()) {
        return fail("bookCount missing");
    }
    if (bookCount->get<int>() < 0) return fail("bookCount < 0");

    std::string sha256;
    if (!ReadStringField(manifest, "sha256", sha256) || sha256.size() != 64) {
        return fail("sha256 missing or not 64 characters");
    }
    for (char& digit : sha256) {
        if (!std::isxdigit(static_cast<unsigned char>(digit))) {
            return fail("sha256 is not hexadecimal");
        }
        digit = static_cast<char>(std::tolower(static_cast<unsigned char>(digit)));
    }

    std::string appVersion = "unknown";
    ReadStringField(manifest, "appVersion", appVersion);  // 可选：老包没有就 unknown

    std::string sourceFileName = kBackupPayloadEntry;
    std::string recordedSource;
    if (ReadStringField(manifest, "sourceFileName", recordedSource) &&
        !recordedSource.empty()) {
        sourceFileName = recordedSource;
    }

    std::uint64_t dbBytes = 0;
    const auto bytes = manifest.find("dbBytes");
    if (bytes != manifest.end()) {
        if (bytes->is_number_unsigned()) {
            dbBytes = bytes->get<std::uint64_t>();
        } else if (bytes->is_number_integer()) {
            const long long value = bytes->get<long long>();
            if (value > 0) dbBytes = static_cast<std::uint64_t>(value);
        }
    }

    // v2：covers 可选；缺失 = 没有封面（v1 包就是这种形状）。
    std::vector<BackupCoverEntry> covers;
    const auto coversField = manifest.find("covers");
    if (coversField != manifest.end() && !coversField->is_null()) {
        if (!coversField->is_array()) return fail("covers is not an array");
        for (const auto& item : *coversField) {
            BackupCoverEntry cover;
            if (!BackupCoverEntry::FromJson(item, cover, nullptr)) {
                return fail("invalid cover entry");
            }
            covers.push_back(std::move(cover));
        }
    }

    out.format = format;
    out.formatVersion = formatVersion->get<int>();
    out.appVersion = appVersion;
    out.schemaVersion = schemaVersion->get<int>();
    out.createdAt = createdAt;
    out.bookCount = bookCount->get<int>();
    out.sourceFileName = sourceFileName;
    out.dbBytes = dbBytes;
    out.sha256 = sha256;
    out.covers = std::move(covers);
    if (error) error->clear();
    return true;
}

// ── BackupService ──────────────────────────────────────────────────
std::string BackupService::Sha256Hex(const void* data, std::size_t len)
{
    BCRYPT_ALG_HANDLE algorithm = nullptr;
    BCRYPT_HASH_HANDLE hash = nullptr;
    unsigned char digest[32] = {};

    bool ok = BCRYPT_SUCCESS(
        BCryptOpenAlgorithmProvider(&algorithm, BCRYPT_SHA256_ALGORITHM, nullptr, 0));
    if (ok) {
        ok = BCRYPT_SUCCESS(
            BCryptCreateHash(algorithm, &hash, nullptr, 0, nullptr, 0, 0));
    }
    if (ok) {
        const unsigned char* cursor = static_cast<const unsigned char*>(data);
        std::size_t remaining = len;
        while (remaining > 0) {
            const ULONG chunk = static_cast<ULONG>(
                remaining > 0x40000000ull ? 0x40000000ull : remaining);
            if (!BCRYPT_SUCCESS(BCryptHashData(hash, const_cast<PUCHAR>(cursor),
                                              chunk, 0))) {
                ok = false;
                break;
            }
            cursor += chunk;
            remaining -= chunk;
        }
    }
    if (ok) {
        ok = BCRYPT_SUCCESS(BCryptFinishHash(hash, digest,
                                             static_cast<ULONG>(sizeof digest), 0));
    }
    if (hash) BCryptDestroyHash(hash);
    if (algorithm) BCryptCloseAlgorithmProvider(algorithm, 0);
    if (!ok) return std::string();  // 调用方把空串当作「算不出来」→ 拒绝

    static const char kHexDigits[] = "0123456789abcdef";
    std::string hex(64, '0');
    for (int i = 0; i < 32; ++i) {
        hex[static_cast<std::size_t>(i) * 2] = kHexDigits[digest[i] >> 4];
        hex[static_cast<std::size_t>(i) * 2 + 1] = kHexDigits[digest[i] & 0x0F];
    }
    return hex;
}

std::string BackupService::Sha256Hex(const std::string& data)
{
    return Sha256Hex(data.data(), data.size());
}

bool BackupService::IsSafeCoverEntryName(const std::string& entryName)
{
    const std::string prefix = kBackupCoverPrefix;
    if (entryName.size() <= prefix.size() || entryName.size() > 200) return false;
    if (entryName.compare(0, prefix.size(), prefix) != 0) return false;
    if (entryName.find('\\') != std::string::npos) return false;   // ZIP 只用 '/'
    if (entryName.find('\0') != std::string::npos) return false;

    const std::string rest = entryName.substr(prefix.size());
    std::size_t position = 0;
    while (position <= rest.size()) {
        const std::size_t slash = rest.find('/', position);
        const std::size_t end = (slash == std::string::npos) ? rest.size() : slash;
        const std::string segment = rest.substr(position, end - position);
        if (segment.empty() || segment == "." || segment == "..") return false;
        if (segment.find(':') != std::string::npos) return false;
        for (const char ch : segment) {
            if (ch == '<' || ch == '>' || ch == '"' || ch == '|' || ch == '?' ||
                ch == '*') {
                return false;
            }
        }
        if (slash == std::string::npos) break;
        position = slash + 1;
    }
    return true;
}

std::string BackupService::NowUtcIso8601()
{
    const std::time_t now = std::time(nullptr);
    std::tm utc = {};
    if (gmtime_s(&utc, &now) != 0) return std::string();
    return FormatIso8601Utc(utc);
}

std::string BackupService::DefaultBackupFileName(const std::string& createdAtUtc)
{
    const std::string stamp =
        CompactStamp(createdAtUtc.empty() ? NowUtcIso8601() : createdAtUtc);
    return "particlebook-backup-" + (stamp.empty() ? std::string("unknown-time") : stamp) +
           ".zip";
}

std::string BackupService::PreRestoreNameFor(const std::string& dbPath,
                                             const std::string& createdAtUtc)
{
    if (dbPath.empty()) return std::string();
    const std::string stamp =
        CompactStamp(createdAtUtc.empty() ? NowUtcIso8601() : createdAtUtc);
    const std::string safeStamp = stamp.empty() ? std::string("unknown-time") : stamp;

    const std::size_t slash = dbPath.find_last_of("\\/");
    const std::string directory =
        (slash == std::string::npos) ? std::string() : dbPath.substr(0, slash + 1);
    const std::string fileName =
        (slash == std::string::npos) ? dbPath : dbPath.substr(slash + 1);

    std::string stem = fileName;
    std::string extension;
    const std::size_t dot = fileName.find_last_of('.');
    if (dot != std::string::npos && dot > 0) {
        stem = fileName.substr(0, dot);
        extension = fileName.substr(dot);
    }
    if (extension.empty()) extension = ".json";
    return directory + stem + ".pre-restore-" + safeStamp + extension;
}

BackupResult BackupService::CreateBackup(const std::string& dbPath,
                                         const std::string& outputZipPath,
                                         const BackupOptions& options,
                                         const BackupProgressFn& progress,
                                         const std::atomic_bool* cancel)
{
    BackupResult result;
    result.outputPath = outputZipPath;

    const auto cancelled = [cancel]() { return cancel != nullptr && cancel->load(); };
    if (cancelled()) {
        result.code = backup_codes::kCancelled;
        result.error = "backup cancelled";
        return result;
    }

    if (dbPath.empty() || outputZipPath.empty()) {
        result.code = backup_codes::kInvalidOptions;
        result.error = "database path or output path is empty";
        return result;
    }

    ReportProgress(progress, "reading_db", 0, 0, 0, 0, dbPath);
    std::string readCode;
    std::string databaseText;
    if (!ReadFileBytesUtf8(dbPath, databaseText, readCode)) {
        result.code = readCode;
        result.error = "cannot read database: " + dbPath;
        return result;
    }
    if (databaseText.empty()) {
        result.code = backup_codes::kDbEmpty;
        result.error = "database file is empty";
        return result;
    }

    nlohmann::json database;
    bool databaseParsed = false;
    {
        nlohmann::json parsed = nlohmann::json::parse(databaseText, nullptr, false);
        if (!parsed.is_discarded() && parsed.is_object()) {
            database = std::move(parsed);
            databaseParsed = true;
        }
    }
    if (options.validateDb && !databaseParsed) {
        result.code = backup_codes::kInvalidDb;
        result.error = "database is not a valid JSON object";
        return result;
    }

    const std::string createdAt =
        options.createdAt.empty() ? NowUtcIso8601() : options.createdAt;
    std::tm createdAtTm = {};
    if (!ParseIso8601Utc(createdAt, createdAtTm)) {
        result.code = backup_codes::kInvalidOptions;
        result.error = "createdAt must be UTC ISO-8601 (YYYY-MM-DDTHH:MM:SSZ)";
        return result;
    }

    BackupManifest manifest;
    manifest.appVersion = options.appVersion.empty() ? std::string("unknown")
                                                     : options.appVersion;
    manifest.schemaVersion =
        options.schemaVersionOverride >= 0
            ? options.schemaVersionOverride
            : (databaseParsed ? DetectSchemaVersion(database) : 1);
    manifest.createdAt = createdAt;
    manifest.bookCount = databaseParsed ? CountBooks(database) : 0;
    manifest.sourceFileName = BaseName(dbPath);
    if (manifest.sourceFileName.empty()) manifest.sourceFileName = kBackupPayloadEntry;
    manifest.dbBytes = static_cast<std::uint64_t>(databaseText.size());
    manifest.sha256 = Sha256Hex(databaseText);
    if (manifest.sha256.empty()) {
        result.code = backup_codes::kIoError;
        result.error = "sha256 computation failed";
        return result;
    }

    ZipWriter writer;
    writer.SetModifiedUtc(createdAtTm);
    if (!writer.Add(kBackupPayloadEntry, databaseText, options.compress)) {
        result.code = backup_codes::kTooLarge;
        result.error = "database too large for a ZIP32 archive";
        return result;
    }

    // ── 封面（PB-011-B）：缺失 / 不可读 / 超限只记 issue，不阻塞备份 ──
    if (options.includeCovers && databaseParsed) {
        const std::vector<std::string> coverPaths = CollectCoverPaths(database);
        std::int64_t processed = 0;
        for (const std::string& coverPath : coverPaths) {
            if (cancelled()) {
                result.code = backup_codes::kCancelled;
                result.error = "backup cancelled";
                return result;
            }
            if (manifest.covers.size() >= options.maxCovers) {
                result.issues.push_back("cover_limit_reached: " +
                                        std::to_string(options.maxCovers));
                break;
            }
            std::string coverCode;
            std::string coverBytes;
            if (!ReadFileBytesUtf8(coverPath, coverBytes, coverCode,
                                   options.maxCoverBytes)) {
                result.issues.push_back("cover_skipped: " + coverCode + ": " + coverPath);
                continue;
            }
            BackupCoverEntry cover;
            cover.sourcePath = coverPath;
            cover.bytes = static_cast<std::uint64_t>(coverBytes.size());
            cover.sha256 = Sha256Hex(coverBytes);
            if (cover.sha256.empty()) {
                result.issues.push_back("cover_sha256_failed: " + coverPath);
                continue;
            }
            cover.entry = std::string(kBackupCoverPrefix) +
                          CoverEntryPrefix(manifest.covers.size() + 1) + "-" +
                          SanitizeCoverFileName(BaseName(coverPath));
            if (!writer.Add(cover.entry, coverBytes, options.compress)) {
                result.code = backup_codes::kTooLarge;
                result.error = "archive too large for ZIP32";
                return result;
            }
            manifest.covers.push_back(std::move(cover));
            ++processed;
            ReportProgress(progress, "reading_covers", processed,
                           static_cast<std::int64_t>(coverPaths.size()), 0, 0, coverPath);
        }
    }

    if (!writer.Add(kBackupManifestEntry, manifest.ToJson().dump(2), false)) {
        result.code = backup_codes::kTooLarge;
        result.error = "archive too large for ZIP32";
        return result;
    }
    if (cancelled()) {
        result.code = backup_codes::kCancelled;
        result.error = "backup cancelled";
        return result;
    }

    ReportProgress(progress, "writing_archive", 0, 0, 0, 0, outputZipPath);
    std::string archive;
    if (!writer.Finish(archive)) {
        result.code = backup_codes::kTooLarge;
        result.error = "archive too large for ZIP32";
        return result;
    }

    std::string writeCode;
    if (!WriteFileAtomicallyUtf8(outputZipPath, archive, writeCode,
                                 backup_codes::kIoError)) {
        result.code = writeCode;
        result.error = "cannot write backup archive: " + outputZipPath;
        return result;
    }

    result.ok = true;
    result.code = backup_codes::kOk;
    result.manifest = manifest;
    result.archiveBytes = static_cast<std::uint64_t>(archive.size());
    result.coversIncluded = static_cast<int>(manifest.covers.size());
    ReportProgress(progress, "done", result.coversIncluded, result.coversIncluded,
                   static_cast<std::int64_t>(archive.size()),
                   static_cast<std::int64_t>(archive.size()), outputZipPath);
    return result;
}

bool BackupService::InspectBackup(const std::string& zipPath, BackupManifest& out,
                                  std::string* errorCode)
{
    const auto fail = [errorCode](const char* code) {
        if (errorCode) *errorCode = code;
        return false;
    };

    if (zipPath.empty() || !FileExistsUtf8(zipPath)) {
        return fail(backup_codes::kBackupMissing);
    }

    std::string manifestText;
    if (!ReadZipEntry(zipPath, kBackupManifestEntry, manifestText)) {
        return fail(backup_codes::kManifestMissing);
    }

    nlohmann::json manifestJson = nlohmann::json::parse(manifestText, nullptr, false);
    if (manifestJson.is_discarded()) return fail(backup_codes::kManifestInvalid);

    BackupManifest manifest;
    std::string parseError;
    if (!BackupManifest::FromJson(manifestJson, manifest, &parseError)) {
        return fail(backup_codes::kManifestInvalid);
    }
    if (manifest.formatVersion > kBackupFormatVersion) {
        return fail(backup_codes::kFormatTooNew);
    }

    // 封面条目名必须是 covers/ 下的安全相对路径；穿越 / 盘符 / 反斜杠一律拒绝。
    std::string badCoverEntry;
    if (!ValidateCoverEntries(manifest.covers, &badCoverEntry)) {
        return fail(backup_codes::kPathTraversal);
    }

    out = manifest;
    if (errorCode) errorCode->clear();
    return true;
}

RestoreResult BackupService::RestoreBackup(const std::string& zipPath,
                                           const std::string& targetDbPath,
                                           const RestoreOptions& options,
                                           const BackupProgressFn& progress,
                                           const std::atomic_bool* cancel)
{
    RestoreResult result;

    const auto cancelled = [cancel]() { return cancel != nullptr && cancel->load(); };
    if (cancelled()) {
        result.code = backup_codes::kCancelled;
        result.error = "restore cancelled";
        return result;
    }

    if (zipPath.empty() || targetDbPath.empty()) {
        result.code = backup_codes::kInvalidOptions;
        result.error = "backup path or target path is empty";
        return result;
    }

    ReportProgress(progress, "inspecting", 0, 0, 0, 0, zipPath);

    // 1~3. 清单 + 包格式
    BackupManifest manifest;
    std::string inspectCode;
    if (!InspectBackup(zipPath, manifest, &inspectCode)) {
        result.code = inspectCode;
        result.error = (inspectCode == backup_codes::kManifestMissing)
                           ? "not a ParticleBook backup (manifest missing; zip may be corrupt)"
                           : "backup archive rejected";
        return result;
    }
    result.manifest = manifest;

    // 4. 降级保护：备份的库版本比本应用新 → 拒绝（PB-014 同款语义）
    const int supportedSchema = (options.currentSchemaVersion >= 0)
                                    ? options.currentSchemaVersion
                                    : DatabaseService::kCurrentSchemaVersion;
    if (manifest.schemaVersion > supportedSchema) {
        result.code = backup_codes::kSchemaTooNew;
        result.error = "backup schema " + std::to_string(manifest.schemaVersion) +
                       " is newer than supported schema " +
                       std::to_string(supportedSchema);
        return result;
    }

    // 5. 取出 payload 并校验
    std::string databaseText;
    if (!ReadZipEntry(zipPath, kBackupPayloadEntry, databaseText)) {
        result.code = backup_codes::kPayloadMissing;
        result.error = "payload entry is missing from the backup";
        return result;
    }
    if (options.verifyChecksum) {
        if (manifest.dbBytes > 0 &&
            manifest.dbBytes != static_cast<std::uint64_t>(databaseText.size())) {
            result.code = backup_codes::kChecksumMismatch;
            result.error = "payload size does not match the manifest";
            return result;
        }
        const std::string actual = Sha256Hex(databaseText);
        if (actual.empty() || actual != manifest.sha256) {
            result.code = backup_codes::kChecksumMismatch;
            result.error = "payload sha256 does not match the manifest";
            return result;
        }
    }
    // 解析 payload：既做校验，也留作封面回填（books[].cover_path）。
    nlohmann::json payloadJson;
    bool payloadParsed = false;
    {
        nlohmann::json parsed = nlohmann::json::parse(databaseText, nullptr, false);
        if (!parsed.is_discarded() && parsed.is_object()) {
            payloadJson = std::move(parsed);
            payloadParsed = true;
        }
    }
    if (options.validatePayload && !payloadParsed) {
        result.code = backup_codes::kPayloadInvalid;
        result.error = "payload is not a valid JSON object";
        return result;
    }
    if (payloadParsed) result.bookCount = CountBooks(payloadJson);

    // 6. 封面（PB-011-B）：缺失 / 校验不符 / 写失败只记 issue，不阻塞整库恢复。
    //    先写封面、再写数据库，保证回填后的 cover_path 指向已经落盘的文件。
    std::vector<std::pair<std::string, std::string>> relocated;  // 原路径 → 新路径
    if (options.restoreCovers && !manifest.covers.empty()) {
        const std::string coverDirectory =
            options.coverDir.empty()
                ? JoinPath(ParentDirectoryOf(targetDbPath), "covers")
                : options.coverDir;
        bool coverDirectoryChecked = false;
        bool coverDirectoryReady = false;
        const std::int64_t coverTotal = static_cast<std::int64_t>(manifest.covers.size());
        std::int64_t coverProcessed = 0;

        for (const BackupCoverEntry& cover : manifest.covers) {
            if (cancelled()) {
                result.code = backup_codes::kCancelled;
                result.error = "restore cancelled";
                return result;
            }
            ++coverProcessed;
            if (options.maxCoverBytes != 0 &&
                cover.bytes > static_cast<std::uint64_t>(options.maxCoverBytes)) {
                result.issues.push_back("cover_too_large: " + cover.entry);
                continue;
            }
            std::string coverBytes;
            if (!ReadZipEntry(zipPath, cover.entry, coverBytes)) {
                result.issues.push_back("cover_missing_in_archive: " + cover.entry);
                continue;
            }
            if (options.verifyChecksum) {
                if (cover.bytes != static_cast<std::uint64_t>(coverBytes.size()) ||
                    Sha256Hex(coverBytes) != cover.sha256) {
                    result.issues.push_back("cover_checksum_mismatch: " + cover.entry);
                    continue;
                }
            }

            bool placed = false;
            std::string finalPath;
            // 写回原路径默认关闭；即使调用方打开，也只允许落在应用数据目录内
            // （见 IsAllowedCoverRestorePath）。备份包能决定 sourcePath 这个绝对
            // 路径，不能让它往任意目录写文件。
            const bool wantsOriginalPath = options.restoreCoversToOriginalPath;
            if (wantsOriginalPath &&
                IsAllowedCoverRestorePath(cover.sourcePath, targetDbPath, coverDirectory)) {
                std::string coverWriteCode;
                if (WriteFileAtomicallyUtf8(cover.sourcePath, coverBytes, coverWriteCode,
                                            backup_codes::kIoError)) {
                    placed = true;
                    finalPath = cover.sourcePath;
                }
            } else if (wantsOriginalPath && !cover.sourcePath.empty()) {
                // 记录一次（不阻塞恢复）：调用方能看到「这张封面为什么被搬走了」。
                result.issues.push_back("cover_path_not_allowed: " + cover.sourcePath);
            }
            if (!placed) {
                if (!coverDirectoryChecked) {
                    coverDirectoryChecked = true;
                    coverDirectoryReady = EnsureDirectoryUtf8(coverDirectory);
                    if (!coverDirectoryReady) {
                        result.issues.push_back("cover_dir_unavailable: " + coverDirectory);
                    }
                }
                if (coverDirectoryReady) {
                    const std::string candidate =
                        UniqueCoverTarget(coverDirectory,
                                          SanitizeCoverFileName(BaseName(cover.sourcePath)));
                    std::string coverWriteCode;
                    if (WriteFileAtomicallyUtf8(candidate, coverBytes, coverWriteCode,
                                                backup_codes::kIoError)) {
                        placed = true;
                        finalPath = candidate;
                        relocated.emplace_back(cover.sourcePath, candidate);
                        ++result.coversRelocated;
                    }
                }
            }
            if (placed) {
                ++result.coversRestored;
            } else {
                result.issues.push_back("cover_write_failed: " + cover.sourcePath);
            }
            ReportProgress(progress, "restoring_covers", coverProcessed, coverTotal, 0, 0,
                           finalPath);
        }
    }

    // 7. 有封面被搬到数据目录时回填 books[].cover_path；没有搬动就原样写回，
    //    保证「无封面 / 原路径可写」的恢复仍然逐字节一致。
    std::string databaseToWrite = databaseText;
    if (!relocated.empty() && options.backfillCoverPaths && payloadParsed) {
        bool changed = false;
        auto books = payloadJson.find("books");
        if (books != payloadJson.end() && books->is_array()) {
            for (auto& book : *books) {
                if (!book.is_object()) continue;
                auto coverPath = book.find("cover_path");
                if (coverPath == book.end() || !coverPath->is_string()) continue;
                const std::string current = coverPath->get<std::string>();
                for (const auto& pair : relocated) {
                    if (EqualsPathNoCase(current, pair.first)) {
                        *coverPath = pair.second;
                        changed = true;
                        break;
                    }
                }
            }
        }
        if (changed) databaseToWrite = payloadJson.dump(2);
    } else if (!relocated.empty() && !options.backfillCoverPaths) {
        result.issues.push_back("cover_paths_not_backfilled");
    }

    // 8. 恢复前备份当前数据库（失败则中止，绝不带着风险覆盖）
    ReportProgress(progress, "backup_current", 0, 0, 0, 0, targetDbPath);
    if (options.makePreRestoreBackup && FileExistsUtf8(targetDbPath)) {
        const std::string preferred =
            PreRestoreNameFor(targetDbPath, NowUtcIso8601());
        std::string actual;
        std::string copyCode;
        if (!CopyFileUniqueUtf8(targetDbPath, preferred, actual, copyCode)) {
            result.code = backup_codes::kPreRestoreFailed;
            result.error = "cannot copy the current database before restore";
            return result;
        }
        result.preRestoreBackupPath = actual;
    }

    // 9. 原子替换
    ReportProgress(progress, "writing_db", 0, 0,
                   static_cast<std::int64_t>(databaseToWrite.size()),
                   static_cast<std::int64_t>(databaseToWrite.size()), targetDbPath);
    std::string writeCode;
    if (!WriteFileAtomicallyUtf8(targetDbPath, databaseToWrite, writeCode,
                                 backup_codes::kWriteFailed)) {
        result.code = writeCode;
        result.error = "cannot write the restored database: " + targetDbPath;
        return result;
    }

    result.ok = true;
    result.code = backup_codes::kOk;
    result.restoredPath = targetDbPath;
    result.restoredBytes = static_cast<std::uint64_t>(databaseToWrite.size());
    ReportProgress(progress, "done", result.coversRestored, result.coversRestored,
                   static_cast<std::int64_t>(databaseToWrite.size()),
                   static_cast<std::int64_t>(databaseToWrite.size()), targetDbPath);
    return result;
}

std::vector<BackupFileInfo> BackupService::ListBackups(const std::string& directory)
{
    std::vector<BackupFileInfo> backups;
    if (directory.empty()) return backups;

    std::wstring pattern = Widen(directory);
    if (pattern.empty()) return backups;
    if (pattern.back() != L'\\' && pattern.back() != L'/') pattern += L'\\';
    pattern += L"*.zip";

    WIN32_FIND_DATAW found = {};
    HANDLE search = FindFirstFileW(pattern.c_str(), &found);
    if (search == INVALID_HANDLE_VALUE) return backups;

    std::string prefix = directory;
    if (!prefix.empty() && prefix.back() != '\\' && prefix.back() != '/') prefix += '\\';

    do {
        if ((found.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0) continue;
        BackupFileInfo info;
        info.fileName = Narrow(found.cFileName);
        if (info.fileName.empty()) continue;
        info.path = prefix + info.fileName;
        info.bytes = (static_cast<std::uint64_t>(found.nFileSizeHigh) << 32) |
                     static_cast<std::uint64_t>(found.nFileSizeLow);
        info.modifiedEpoch = FileTimeToUnixSeconds(found.ftLastWriteTime);
        backups.push_back(std::move(info));
    } while (FindNextFileW(search, &found) != FALSE);
    FindClose(search);

    std::sort(backups.begin(), backups.end(),
              [](const BackupFileInfo& left, const BackupFileInfo& right) {
                  if (left.modifiedEpoch != right.modifiedEpoch) {
                      return left.modifiedEpoch > right.modifiedEpoch;
                  }
                  return left.fileName > right.fileName;
              });
    return backups;
}

} // namespace pb
