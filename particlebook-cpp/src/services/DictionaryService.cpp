#include "services/DictionaryService.h"

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <string_view>
#include <utility>
#include <vector>

#include "utils/pb_win.h"

// miniz：只用 gunzip（tinfl），miniz.c / miniz_tinfl.c 已在主 target 里编译。
#define MINIZ_NO_TIME
#define MINIZ_NO_ARCHIVE_APIS
#define MINIZ_NO_ARCHIVE_WRITING_APIS
#define MINIZ_NO_ZLIB_COMPATIBLE_NAMES
#include "miniz.h"

namespace pb {
namespace {

// ── 记录字段顺序（缓存格式与内存布局共用，改这里必须同时 bump 缓存版本）──
enum FieldIndex {
    kWord = 0,
    kPhonetic,
    kTranslation,
    kDefinition,
    kPos,
    kTag,
    kExchange,
    kExample,
    kFieldCount,
};

constexpr std::uint32_t kRecordHeaderBytes =
    static_cast<std::uint32_t>(kFieldCount) * 4u;
constexpr std::uint32_t kCacheVersion = 1;
constexpr std::size_t kCacheHeaderBytes = 32;
constexpr std::size_t kMaxQueryBytes = 200;
const char kCacheMagic[8] = {'P', 'B', 'D', 'I', 'C', 'T', '0', '1'};

// ── 小工具 ─────────────────────────────────────────────────────────
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

char LowerAscii(char value)
{
    return (value >= 'A' && value <= 'Z') ? static_cast<char>(value - 'A' + 'a') : value;
}

std::string ToLowerAscii(const std::string& text)
{
    std::string lowered = text;
    for (char& value : lowered) value = LowerAscii(value);
    return lowered;
}

std::string Trim(const std::string& text)
{
    std::size_t begin = 0;
    std::size_t end = text.size();
    while (begin < end && static_cast<unsigned char>(text[begin]) <= ' ') ++begin;
    while (end > begin && static_cast<unsigned char>(text[end - 1]) <= ' ') --end;
    return text.substr(begin, end - begin);
}

bool Utf8HasNonAscii(const std::string& text)
{
    for (const unsigned char value : text) {
        if (value >= 0x80) return true;
    }
    return false;
}

bool EqualsNoCaseAscii(std::string_view left, std::string_view right)
{
    if (left.size() != right.size()) return false;
    for (std::size_t i = 0; i < left.size(); ++i) {
        if (LowerAscii(left[i]) != LowerAscii(right[i])) return false;
    }
    return true;
}

bool EqualsView(std::string_view left, std::string_view right)
{
    return left.size() == right.size() &&
           (left.empty() || std::memcmp(left.data(), right.data(), left.size()) == 0);
}

// 词形比较：ASCII 小写，按字节序。返回 -1 / 0 / 1。
int CompareLower(const char* left, std::size_t leftSize,
                 const char* right, std::size_t rightSize)
{
    const std::size_t common = leftSize < rightSize ? leftSize : rightSize;
    for (std::size_t i = 0; i < common; ++i) {
        const unsigned char a = static_cast<unsigned char>(LowerAscii(left[i]));
        const unsigned char b = static_cast<unsigned char>(LowerAscii(right[i]));
        if (a != b) return a < b ? -1 : 1;
    }
    if (leftSize == rightSize) return 0;
    return leftSize < rightSize ? -1 : 1;
}

std::string StripMarkup(const std::string& input)
{
    std::string text;
    text.reserve(input.size());
    bool inTag = false;
    for (const char ch : input) {
        if (ch == '<') {
            inTag = true;
            continue;
        }
        if (ch == '>') {
            if (inTag) {
                inTag = false;
                continue;
            }
        }
        if (!inTag) text.push_back(ch);
    }

    struct Entity { const char* from; const char* to; };
    static const Entity kEntities[] = {
        {"&nbsp;", " "}, {"&amp;", "&"}, {"&lt;", "<"},
        {"&gt;", ">"},   {"&quot;", "\""}, {"&apos;", "'"},
    };
    for (const Entity& entity : kEntities) {
        const std::size_t fromLength = std::strlen(entity.from);
        const std::size_t toLength = std::strlen(entity.to);
        std::size_t position = 0;
        while ((position = text.find(entity.from, position)) != std::string::npos) {
            text.replace(position, fromLength, entity.to);
            position += toLength;
        }
    }
    return Trim(text);
}

std::vector<std::pair<std::string, std::string>> SplitExchange(const std::string& exchange)
{
    std::vector<std::pair<std::string, std::string>> forms;
    std::size_t position = 0;
    while (position < exchange.size()) {
        const std::size_t slash = exchange.find('/', position);
        const std::size_t end = (slash == std::string::npos) ? exchange.size() : slash;
        const std::string item = Trim(exchange.substr(position, end - position));
        if (!item.empty()) {
            const std::size_t colon = item.find(':');
            if (colon == std::string::npos) {
                forms.emplace_back(std::string(), item);
            } else {
                forms.emplace_back(item.substr(0, colon), item.substr(colon + 1));
            }
        }
        if (slash == std::string::npos) break;
        position = slash + 1;
    }
    return forms;
}

bool ExchangeContainsForm(const std::string& exchange, const std::string& formLower)
{
    for (const auto& form : SplitExchange(exchange)) {
        if (ToLowerAscii(form.second) == formLower) return true;
    }
    return false;
}

std::string FormatError(const std::string& prefix, const std::string& detail)
{
    if (detail.empty()) return prefix;
    return prefix + ": " + detail;
}

std::int64_t NowUnixSeconds()
{
    return static_cast<std::int64_t>(std::time(nullptr));
}

// ── 记录 / blob 读写 ───────────────────────────────────────────────
void AppendU32Le(std::string& out, std::uint32_t value)
{
    out.push_back(static_cast<char>(value & 0xFFu));
    out.push_back(static_cast<char>((value >> 8) & 0xFFu));
    out.push_back(static_cast<char>((value >> 16) & 0xFFu));
    out.push_back(static_cast<char>((value >> 24) & 0xFFu));
}

void AppendU64Le(std::string& out, std::uint64_t value)
{
    AppendU32Le(out, static_cast<std::uint32_t>(value & 0xFFFFFFFFull));
    AppendU32Le(out, static_cast<std::uint32_t>((value >> 32) & 0xFFFFFFFFull));
}

std::uint32_t ReadU32Le(const char* data)
{
    return static_cast<std::uint32_t>(static_cast<unsigned char>(data[0])) |
           (static_cast<std::uint32_t>(static_cast<unsigned char>(data[1])) << 8) |
           (static_cast<std::uint32_t>(static_cast<unsigned char>(data[2])) << 16) |
           (static_cast<std::uint32_t>(static_cast<unsigned char>(data[3])) << 24);
}

std::uint64_t ReadU64Le(const char* data)
{
    const std::uint64_t low = ReadU32Le(data);
    const std::uint64_t high = ReadU32Le(data + 4);
    return low | (high << 32);
}

std::uint64_t ReadBigEndian(const std::string& data, std::size_t offset, int bytes)
{
    std::uint64_t value = 0;
    for (int i = 0; i < bytes; ++i) {
        value = (value << 8) | static_cast<unsigned char>(data[offset + static_cast<std::size_t>(i)]);
    }
    return value;
}

std::string_view RecordFieldView(const std::string& blob, std::uint32_t offset, int field)
{
    const char* base = blob.data() + offset;
    std::uint32_t fieldOffset = kRecordHeaderBytes;
    for (int i = 0; i < field; ++i) fieldOffset += ReadU32Le(base + i * 4);
    const std::uint32_t length = ReadU32Le(base + field * 4);
    return std::string_view(blob.data() + offset + fieldOffset, length);
}

std::uint32_t RecordLength(const std::string& blob, std::uint32_t offset)
{
    const char* base = blob.data() + offset;
    std::uint32_t length = kRecordHeaderBytes;
    for (int i = 0; i < kFieldCount; ++i) length += ReadU32Le(base + i * 4);
    return length;
}

int CompareOffsetWords(const std::string& blob, std::uint32_t left, std::uint32_t right)
{
    const std::string_view a = RecordFieldView(blob, left, kWord);
    const std::string_view b = RecordFieldView(blob, right, kWord);
    return CompareLower(a.data(), a.size(), b.data(), b.size());
}

int CompareOffsetKeyLower(const std::string& blob, std::uint32_t offset,
                          const std::string& keyLower)
{
    const std::string_view word = RecordFieldView(blob, offset, kWord);
    return CompareLower(word.data(), word.size(), keyLower.data(), keyLower.size());
}

// ── 词典存储（导入时逐步追加，成功后一次性换入）─────────────────────
struct DictionaryStore {
    std::string blob;
    std::vector<std::uint32_t> offsets;

    bool Add(const std::string (&fields)[kFieldCount], std::string& code)
    {
        std::uint64_t recordBytes = kRecordHeaderBytes;
        for (int i = 0; i < kFieldCount; ++i) {
            if (fields[i].size() > 0xFFFFFF00ull) {
                code = dictionary_codes::kTooLarge;
                return false;
            }
            recordBytes += fields[i].size();
        }
        if (static_cast<std::uint64_t>(blob.size()) + recordBytes > 0xFFFFFF00ull) {
            code = dictionary_codes::kTooLarge;
            return false;
        }
        const std::uint32_t offset = static_cast<std::uint32_t>(blob.size());
        for (int i = 0; i < kFieldCount; ++i) {
            AppendU32Le(blob, static_cast<std::uint32_t>(fields[i].size()));
        }
        for (int i = 0; i < kFieldCount; ++i) blob += fields[i];
        offsets.push_back(offset);
        return true;
    }

    void SortByWord()
    {
        const std::string* blobPtr = &blob;
        std::sort(offsets.begin(), offsets.end(),
                  [blobPtr](const std::uint32_t left, const std::uint32_t right) {
                      return CompareOffsetWords(*blobPtr, left, right) < 0;
                  });
    }

    void Clear()
    {
        blob.clear();
        offsets.clear();
    }
};

// 二分：第一个 >= keyLower 的位置 / 第一个 > keyLower 的位置。
std::size_t LowerBoundOffset(const std::string& blob,
                             const std::vector<std::uint32_t>& offsets,
                             const std::string& keyLower)
{
    std::size_t low = 0;
    std::size_t high = offsets.size();
    while (low < high) {
        const std::size_t mid = low + (high - low) / 2;
        if (CompareOffsetKeyLower(blob, offsets[mid], keyLower) < 0) {
            low = mid + 1;
        } else {
            high = mid;
        }
    }
    return low;
}

// 精确命中（按小写比较），最多 maxResults 条，返回记录偏移。
std::vector<std::uint32_t> FindExactOffsets(const std::string& blob,
                                            const std::vector<std::uint32_t>& offsets,
                                            const std::string& keyLower,
                                            std::size_t maxResults)
{
    std::vector<std::uint32_t> matches;
    if (maxResults == 0) return matches;
    std::size_t position = LowerBoundOffset(blob, offsets, keyLower);
    while (position < offsets.size() &&
           CompareOffsetKeyLower(blob, offsets[position], keyLower) == 0) {
        matches.push_back(offsets[position]);
        if (matches.size() >= maxResults) break;
        ++position;
    }
    return matches;
}

// ── Windows 文件（只读 / 原子写）──────────────────────────────────
class FileReader {
public:
    FileReader() = default;
    ~FileReader() { Close(); }
    FileReader(const FileReader&) = delete;
    FileReader& operator=(const FileReader&) = delete;

    bool Open(const std::string& utf8Path, std::string& code)
    {
        Close();
        const std::wstring wide = Widen(utf8Path);
        if (wide.empty()) {
            code = dictionary_codes::kFileUnreadable;
            return false;
        }
        m_handle = CreateFileW(wide.c_str(), GENERIC_READ,
                               FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                               nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (m_handle == INVALID_HANDLE_VALUE) {
            const DWORD error = GetLastError();
            m_handle = nullptr;
            code = (error == ERROR_FILE_NOT_FOUND || error == ERROR_PATH_NOT_FOUND)
                       ? dictionary_codes::kFileMissing
                       : dictionary_codes::kFileUnreadable;
            return false;
        }
        LARGE_INTEGER size = {};
        if (!GetFileSizeEx(m_handle, &size) || size.QuadPart < 0) {
            Close();
            code = dictionary_codes::kFileUnreadable;
            return false;
        }
        m_size = static_cast<std::uint64_t>(size.QuadPart);
        code.clear();
        return true;
    }

    bool ReadAt(std::uint64_t offset, std::size_t length, std::string& out, std::string& code)
    {
        out.clear();
        if (offset > m_size || static_cast<std::uint64_t>(length) > m_size - offset) {
            code = dictionary_codes::kBadDict;
            return false;
        }
        out.resize(length);
        std::size_t done = 0;
        while (done < length) {
            const std::size_t remaining = length - done;
            const DWORD want = static_cast<DWORD>(remaining > (1u << 20) ? (1u << 20) : remaining);
            LARGE_INTEGER position = {};
            position.QuadPart = static_cast<LONGLONG>(offset + done);
            if (!SetFilePointerEx(m_handle, position, nullptr, FILE_BEGIN)) {
                code = dictionary_codes::kFileUnreadable;
                return false;
            }
            DWORD got = 0;
            if (!ReadFile(m_handle, &out[done], want, &got, nullptr) || got == 0) {
                code = dictionary_codes::kFileUnreadable;
                return false;
            }
            done += got;
        }
        code.clear();
        return true;
    }

    std::uint64_t Size() const { return m_size; }

private:
    void Close()
    {
        if (m_handle) CloseHandle(m_handle);
        m_handle = nullptr;
        m_size = 0;
    }

    HANDLE m_handle = nullptr;
    std::uint64_t m_size = 0;
};

bool ReadFileBytesUtf8(const std::string& utf8Path, std::size_t limit,
                       std::string& out, std::string& code)
{
    FileReader reader;
    if (!reader.Open(utf8Path, code)) return false;
    if (reader.Size() > limit) {
        code = dictionary_codes::kTooLarge;
        return false;
    }
    if (reader.Size() > 0xFFFFFFF0ull) {
        code = dictionary_codes::kTooLarge;
        return false;
    }
    return reader.ReadAt(0, static_cast<std::size_t>(reader.Size()), out, code);
}

bool FileExistsUtf8(const std::string& utf8Path)
{
    const std::wstring wide = Widen(utf8Path);
    if (wide.empty()) return false;
    const DWORD attributes = GetFileAttributesW(wide.c_str());
    if (attributes == INVALID_FILE_ATTRIBUTES) return false;
    return (attributes & FILE_ATTRIBUTE_DIRECTORY) == 0;
}

// 临时文件 + MoveFileEx：要么旧缓存完好，要么新缓存完整。
class AtomicFileWriter {
public:
    AtomicFileWriter() = default;
    ~AtomicFileWriter() { Abort(); }
    AtomicFileWriter(const AtomicFileWriter&) = delete;
    AtomicFileWriter& operator=(const AtomicFileWriter&) = delete;

    bool Open(const std::string& utf8Path, std::string& code, const char* failureCode)
    {
        m_target = Widen(utf8Path);
        if (m_target.empty()) {
            code = failureCode;
            return false;
        }
        m_temporary = m_target + L".pb-tmp-" + std::to_wstring(GetCurrentProcessId());
        m_handle = CreateFileW(m_temporary.c_str(), GENERIC_WRITE, 0, nullptr,
                               CREATE_ALWAYS,
                               FILE_ATTRIBUTE_NORMAL | FILE_FLAG_WRITE_THROUGH, nullptr);
        if (m_handle == INVALID_HANDLE_VALUE) {
            m_handle = nullptr;
            code = failureCode;
            return false;
        }
        code.clear();
        return true;
    }

    // 缓冲写：缓存是「每条记录一次 Write」的调用形状，直接 WriteFile 会有
    // 几十万次系统调用（FILE_FLAG_WRITE_THROUGH 下慢得多）。
    bool Write(const void* data, std::size_t length)
    {
        const char* bytes = static_cast<const char*>(data);
        if (m_buffer.size() + length <= kBufferBytes) {
            m_buffer.append(bytes, length);
            return true;
        }
        if (!Flush()) return false;
        if (length >= kBufferBytes) return WriteRaw(bytes, length);
        m_buffer.append(bytes, length);
        return true;
    }

    bool Commit(std::string& code, const char* failureCode)
    {
        if (!m_handle) {
            code = failureCode;
            return false;
        }
        if (!Flush()) {
            Abort();
            code = failureCode;
            return false;
        }
        bool ok = FlushFileBuffers(m_handle) != FALSE;
        CloseHandle(m_handle);
        m_handle = nullptr;
        if (ok) {
            ok = MoveFileExW(m_temporary.c_str(), m_target.c_str(),
                             MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH) != FALSE;
        }
        if (!ok) {
            DeleteFileW(m_temporary.c_str());
            m_temporary.clear();
            code = failureCode;
            return false;
        }
        m_temporary.clear();
        code.clear();
        return true;
    }

    void Abort()
    {
        m_buffer.clear();
        if (m_handle) {
            CloseHandle(m_handle);
            m_handle = nullptr;
        }
        if (!m_temporary.empty()) {
            DeleteFileW(m_temporary.c_str());
            m_temporary.clear();
        }
    }

private:
    bool Flush()
    {
        if (m_buffer.empty()) return true;
        const bool ok = WriteRaw(m_buffer.data(), m_buffer.size());
        if (ok) m_buffer.clear();
        return ok;
    }

    bool WriteRaw(const char* bytes, std::size_t length)
    {
        std::size_t done = 0;
        while (done < length) {
            const std::size_t remaining = length - done;
            const DWORD want = static_cast<DWORD>(remaining > (1u << 20) ? (1u << 20) : remaining);
            DWORD put = 0;
            if (!WriteFile(m_handle, bytes + done, want, &put, nullptr) || put != want) {
                return false;
            }
            done += put;
        }
        return true;
    }

    static constexpr std::size_t kBufferBytes = 1u << 20;

    HANDLE m_handle = nullptr;
    std::wstring m_target;
    std::wstring m_temporary;
    std::string m_buffer;
};

// ── gzip（StarDict 的 .idx.gz / .dict.dz）──────────────────────────
// gzip = 头（可带 FEXTRA/FNAME/FCOMMENT/FHCRC）+ 单个 raw deflate 流 + 8 字节尾。
// dictzip 只是多一个 RA extra field，顺序整包解压不受影响。
bool GunzipBytes(const std::string& input, std::size_t maxOutput,
                 std::string& output, std::string& code, std::string& error)
{
    if (input.size() < 18) {
        code = dictionary_codes::kBadGzip;
        error = "gzip stream is too short";
        return false;
    }
    const unsigned char* bytes = reinterpret_cast<const unsigned char*>(input.data());
    if (bytes[0] != 0x1F || bytes[1] != 0x8B || bytes[2] != 8) {
        code = dictionary_codes::kBadGzip;
        error = "not a gzip stream";
        return false;
    }
    const unsigned char flags = bytes[3];
    std::size_t position = 10;
    if ((flags & 0x04u) != 0) {  // FEXTRA
        if (position + 2 > input.size()) {
            code = dictionary_codes::kBadGzip;
            error = "truncated gzip extra field";
            return false;
        }
        const std::size_t extraLength =
            static_cast<std::size_t>(bytes[position]) |
            (static_cast<std::size_t>(bytes[position + 1]) << 8);
        position += 2 + extraLength;
    }
    if ((flags & 0x08u) != 0) {  // FNAME
        while (position < input.size() && bytes[position] != 0) ++position;
        if (position >= input.size()) {
            code = dictionary_codes::kBadGzip;
            error = "truncated gzip file name";
            return false;
        }
        ++position;
    }
    if ((flags & 0x10u) != 0) {  // FCOMMENT
        while (position < input.size() && bytes[position] != 0) ++position;
        if (position >= input.size()) {
            code = dictionary_codes::kBadGzip;
            error = "truncated gzip comment";
            return false;
        }
        ++position;
    }
    if ((flags & 0x02u) != 0) position += 2;  // FHCRC
    if (position + 8 > input.size()) {
        code = dictionary_codes::kBadGzip;
        error = "gzip payload is missing";
        return false;
    }

    const std::size_t rawLength = input.size() - position - 8;
    if (rawLength == 0) {
        output.clear();
        code.clear();
        return true;
    }
    std::size_t outputLength = 0;
    void* heap = tinfl_decompress_mem_to_heap(input.data() + position, rawLength,
                                              &outputLength, 0);
    if (!heap) {
        code = dictionary_codes::kBadGzip;
        error = "deflate stream is corrupt";
        return false;
    }
    if (outputLength > maxOutput) {
        mz_free(heap);
        code = dictionary_codes::kTooLarge;
        error = "decompressed dictionary exceeds the configured limit";
        return false;
    }
    output.assign(static_cast<const char*>(heap), outputLength);
    mz_free(heap);
    code.clear();
    return true;
}

// 读文件；如果是 gzip 就解压（按 magic 判断，不看扩展名）。
bool ReadMaybeGzip(const std::string& path, std::size_t limit,
                   std::string& out, std::string& code, std::string& error)
{
    std::string raw;
    std::string readCode;
    if (!ReadFileBytesUtf8(path, limit, raw, readCode)) {
        code = readCode;
        error = "cannot read " + path;
        return false;
    }
    if (raw.size() >= 2 && static_cast<unsigned char>(raw[0]) == 0x1F &&
        static_cast<unsigned char>(raw[1]) == 0x8B) {
        return GunzipBytes(raw, limit, out, code, error);
    }
    out.swap(raw);
    code.clear();
    return true;
}

// ── CSV 解析（支持引号、转义 ""、字段内换行、CRLF）──────────────────
class CsvReader {
public:
    explicit CsvReader(std::string text) : m_text(std::move(text))
    {
        if (m_text.size() >= 3 &&
            static_cast<unsigned char>(m_text[0]) == 0xEF &&
            static_cast<unsigned char>(m_text[1]) == 0xBB &&
            static_cast<unsigned char>(m_text[2]) == 0xBF) {
            m_position = 3;
        }
    }

    // 返回 false = 结束或出错；出错时 code 非空（调用方区分）。
    bool NextRow(std::vector<std::string>& row, std::string& code)
    {
        row.clear();
        if (m_position >= m_text.size()) return false;
        std::string field;
        bool inQuotes = false;
        while (m_position < m_text.size()) {
            const char ch = m_text[m_position++];
            if (inQuotes) {
                if (ch == '"') {
                    if (m_position < m_text.size() && m_text[m_position] == '"') {
                        field.push_back('"');
                        ++m_position;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.push_back(ch);
                }
                continue;
            }
            if (ch == '"') {
                inQuotes = true;
            } else if (ch == ',') {
                row.push_back(std::move(field));
                field.clear();
            } else if (ch == '\r' || ch == '\n') {
                if (ch == '\r' && m_position < m_text.size() && m_text[m_position] == '\n') {
                    ++m_position;
                }
                row.push_back(std::move(field));
                return true;
            } else {
                field.push_back(ch);
            }
        }
        if (inQuotes) {
            code = dictionary_codes::kBadCsv;
            return false;
        }
        row.push_back(std::move(field));
        return true;
    }

    std::size_t Position() const { return m_position; }
    std::size_t Size() const { return m_text.size(); }

private:
    std::string m_text;
    std::size_t m_position = 0;
};

// ── ECDICT CSV → store ────────────────────────────────────────────
bool BuildEcdictStore(const std::string& csvText, const DictionaryImportOptions& options,
                      const DictionaryProgressFn& progress, const std::atomic_bool* cancel,
                      DictionaryStore& store, std::size_t& skipped,
                      std::string& code, std::string& error)
{
    CsvReader reader(csvText);
    std::vector<std::string> row;
    std::string rowCode;
    bool firstRow = true;
    std::int64_t processed = 0;

    while (reader.NextRow(row, rowCode)) {
        if (cancel && (processed & 0x1FF) == 0 && cancel->load()) {
            code = dictionary_codes::kCancelled;
            error = "import cancelled";
            return false;
        }
        if (firstRow) {
            firstRow = false;
            if (!row.empty() && EqualsNoCaseAscii(Trim(row[0]), "word")) {
                continue;  // ECDICT 表头
            }
        }
        if (row.empty()) {
            ++skipped;
            continue;
        }
        const std::string word = Trim(row[0]);
        if (word.empty() || word.size() > options.maxWordBytes) {
            ++skipped;
            continue;
        }
        if (store.offsets.size() >= options.maxEntries) {
            code = dictionary_codes::kTooLarge;
            error = "entry count exceeds maxEntries";
            return false;
        }

        const auto column = [&row](std::size_t index) -> std::string {
            return index < row.size() ? Trim(row[index]) : std::string();
        };
        std::string fields[kFieldCount];
        fields[kWord] = word;
        fields[kPhonetic] = column(1);
        fields[kDefinition] = column(2);
        fields[kTranslation] = column(3);
        fields[kPos] = column(4);
        fields[kTag] = column(7);
        fields[kExchange] = column(10);

        if (!store.Add(fields, code)) {
            error = "dictionary exceeds the configured size limit";
            return false;
        }
        if (store.blob.size() > options.maxBytes) {
            code = dictionary_codes::kTooLarge;
            error = "record bytes exceed maxBytes";
            return false;
        }
        ++processed;
        if (progress && (processed & 0x3FF) == 0) {
            DictionaryProgress update;
            update.processed = processed;
            update.bytesProcessed = static_cast<std::int64_t>(reader.Position());
            update.bytesTotal = static_cast<std::int64_t>(reader.Size());
            update.stage = "parsing";
            progress(update);
        }
    }
    if (!rowCode.empty()) {
        code = rowCode;
        error = "malformed CSV (unterminated quote)";
        return false;
    }
    if (progress) {
        DictionaryProgress update;
        update.processed = processed;
        update.bytesProcessed = static_cast<std::int64_t>(reader.Size());
        update.bytesTotal = static_cast<std::int64_t>(reader.Size());
        update.stage = "indexing";
        progress(update);
    }
    code.clear();
    return true;
}

// ── StarDict ──────────────────────────────────────────────────────
struct IfoMeta {
    std::string bookName;
    std::string sameTypeSequence;
    int wordCount = 0;
    bool idxOffset64 = false;
};

bool ParseIfo(const std::string& ifoText, IfoMeta& meta, std::string& error)
{
    if (ifoText.find("StarDict's dict ifo file") == std::string::npos) {
        error = "missing StarDict ifo header";
        return false;
    }
    std::size_t position = 0;
    while (position <= ifoText.size()) {
        const std::size_t end = ifoText.find('\n', position);
        const std::string line =
            Trim(ifoText.substr(position, (end == std::string::npos ? ifoText.size() : end) - position));
        if (!line.empty() && line[0] != '#') {
            const std::size_t equals = line.find('=');
            if (equals != std::string::npos) {
                const std::string key = Trim(line.substr(0, equals));
                const std::string value = Trim(line.substr(equals + 1));
                if (key == "bookname") {
                    meta.bookName = value;
                } else if (key == "wordcount") {
                    meta.wordCount = std::atoi(value.c_str());
                } else if (key == "sametypesequence") {
                    meta.sameTypeSequence = value;
                } else if (key == "idxoffsetbits") {
                    meta.idxOffset64 = (value == "64");
                }
            }
        }
        if (end == std::string::npos) break;
        position = end + 1;
    }
    if (meta.wordCount < 0) {
        error = "negative wordcount";
        return false;
    }
    error.clear();
    return true;
}

struct IdxEntry {
    std::string word;
    std::uint64_t offset = 0;
    std::uint32_t size = 0;
};

bool ParseIdxEntries(const std::string& idxData, bool offset64, std::uint64_t dictSize,
                     std::size_t maxEntries, std::vector<IdxEntry>& entries,
                     std::string& code, std::string& error)
{
    std::size_t position = 0;
    while (position < idxData.size()) {
        const std::size_t zero = idxData.find('\0', position);
        if (zero == std::string::npos) {
            code = dictionary_codes::kBadIdx;
            error = "word is not NUL-terminated";
            return false;
        }
        IdxEntry entry;
        entry.word = idxData.substr(position, zero - position);
        position = zero + 1;
        const std::size_t numberBytes = offset64 ? 12u : 8u;
        if (position + numberBytes > idxData.size()) {
            code = dictionary_codes::kBadIdx;
            error = "truncated index entry";
            return false;
        }
        entry.offset = ReadBigEndian(idxData, position, offset64 ? 8 : 4);
        entry.size = static_cast<std::uint32_t>(
            ReadBigEndian(idxData, position + (offset64 ? 8u : 4u), 4));
        position += numberBytes;
        if (dictSize != 0 &&
            (entry.offset > dictSize ||
             static_cast<std::uint64_t>(entry.size) > dictSize - entry.offset)) {
            code = dictionary_codes::kBadDict;
            error = "index entry points outside the dictionary data";
            return false;
        }
        if (entry.word.empty()) continue;
        entries.push_back(std::move(entry));
        if (entries.size() > maxEntries) {
            code = dictionary_codes::kTooLarge;
            error = "entry count exceeds maxEntries";
            return false;
        }
    }
    code.clear();
    return true;
}

bool IsStarDictTextType(char type)
{
    switch (type) {
        case 'm': case 'l': case 't': case 'x': case 'y':
        case 'k': case 'h': case 'g': case 'w': case 'n': case 'r':
            return true;
        default:
            return false;  // 'W' / 'P' 等二进制类型
    }
}

bool ParseStarDictData(const std::string& data, const std::string& sameTypeSequence,
                       std::string& phonetic, std::string& translation,
                       std::string& definition)
{
    const auto addText = [&](char type, const std::string& raw) {
        std::string text;
        if (type == 'm' || type == 't' || type == 'y' || type == 'k' || type == 'l' ||
            type == 'r') {
            text = Trim(raw);
        } else {
            text = StripMarkup(raw);  // x / h / g / w / n
        }
        if (text.empty()) return;
        if (type == 't' || type == 'y') {
            if (!phonetic.empty()) phonetic += "\n";
            phonetic += text;
            return;
        }
        if (Utf8HasNonAscii(text)) {
            if (!translation.empty()) translation += "\n";
            translation += text;
        } else {
            if (!definition.empty()) definition += "\n";
            definition += text;
        }
    };

    if (!sameTypeSequence.empty()) {
        std::size_t position = 0;
        for (std::size_t i = 0; i < sameTypeSequence.size(); ++i) {
            const char type = sameTypeSequence[i];
            if (!IsStarDictTextType(type)) return false;
            std::size_t end = data.find('\0', position);
            if (i + 1 == sameTypeSequence.size()) {
                // 规范：最后一个字段可以不带 NUL
                if (end == std::string::npos) end = data.size();
            } else if (end == std::string::npos) {
                return false;
            }
            addText(type, data.substr(position, end - position));
            position = (end < data.size()) ? end + 1 : data.size();
            if (position > data.size()) break;
        }
        return true;
    }

    std::size_t position = 0;
    while (position < data.size()) {
        const char type = data[position++];
        if (!IsStarDictTextType(type)) return false;
        std::size_t end = data.find('\0', position);
        if (end == std::string::npos) end = data.size();
        addText(type, data.substr(position, end - position));
        position = (end < data.size()) ? end + 1 : data.size();
    }
    return true;
}

std::string StripIfoSuffix(const std::string& ifoPath)
{
    if (ifoPath.size() >= 4 && EqualsNoCaseAscii(
            std::string_view(ifoPath).substr(ifoPath.size() - 4), ".ifo")) {
        return ifoPath.substr(0, ifoPath.size() - 4);
    }
    return ifoPath;
}

// ── 词形还原 ─────────────────────────────────────────────────────
std::vector<std::string> LemmaCandidates(const std::string& wordLower)
{
    std::vector<std::string> candidates;
    const auto push = [&candidates](std::string candidate) {
        if (candidate.empty() || candidate.size() > kMaxQueryBytes) return;
        for (const std::string& existing : candidates) {
            if (existing == candidate) return;
        }
        candidates.push_back(std::move(candidate));
    };
    const auto endsWith = [&wordLower](const char* suffix) {
        const std::size_t length = std::strlen(suffix);
        return wordLower.size() > length &&
               wordLower.compare(wordLower.size() - length, length, suffix) == 0;
    };
    const auto drop = [&wordLower](std::size_t count) {
        return wordLower.substr(0, wordLower.size() - count);
    };

    if (endsWith("ies")) push(drop(3) + "y");
    if (endsWith("ied")) push(drop(3) + "y");
    if (endsWith("ing")) {
        const std::string stem = drop(3);
        push(stem);
        push(stem + "e");
        if (stem.size() >= 2 && stem[stem.size() - 1] == stem[stem.size() - 2]) {
            push(stem.substr(0, stem.size() - 1));  // running → runn → run
        }
    }
    if (endsWith("est")) {
        const std::string stem = drop(3);
        push(stem);
        push(stem + "e");
    }
    if (endsWith("er")) {
        const std::string stem = drop(2);
        push(stem);
        push(stem + "e");
    }
    if (endsWith("ed")) {
        const std::string stem = drop(2);
        push(stem);
        push(stem + "e");
        if (stem.size() >= 2 && stem[stem.size() - 1] == stem[stem.size() - 2]) {
            push(stem.substr(0, stem.size() - 1));
        }
    }
    if (endsWith("es")) {
        push(drop(2));
        push(drop(1));
    }
    if (endsWith("s") && !endsWith("ss")) push(drop(1));
    return candidates;
}

// ── 缓存 ─────────────────────────────────────────────────────────
std::string MakeCacheHeader(std::uint32_t sourceKind, std::uint32_t entryCount,
                            std::uint64_t createdAt)
{
    std::string header;
    header.reserve(kCacheHeaderBytes);
    header.append(kCacheMagic, sizeof kCacheMagic);
    AppendU32Le(header, kCacheVersion);
    AppendU32Le(header, sourceKind);
    AppendU32Le(header, entryCount);
    AppendU32Le(header, 0);  // reserved
    AppendU64Le(header, createdAt);
    return header;
}

} // namespace

// ── 公开 API ─────────────────────────────────────────────────────
DictionaryService::DictionaryService() = default;
DictionaryService::~DictionaryService() = default;

std::vector<std::pair<std::string, std::string>>
DictionaryService::ParseExchange(const std::string& exchange)
{
    return SplitExchange(exchange);
}

const char* DictionaryService::MatchKindName(DictionaryMatchKind kind)
{
    switch (kind) {
        case DictionaryMatchKind::Exact:
            return "exact";
        case DictionaryMatchKind::Prefix:
            return "prefix";
        case DictionaryMatchKind::Morphology:
            return "morphology";
    }
    return "exact";
}

void DictionaryService::Clear()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    m_blob.clear();
    m_offsets.clear();
    m_source = DictionarySourceKind::None;
    m_cachePath.clear();
    m_loadedAtUnix = 0;
}

bool DictionaryService::Empty() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_offsets.empty();
}

std::size_t DictionaryService::Size() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_offsets.size();
}

DictionaryInfo DictionaryService::Info() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    DictionaryInfo info;
    info.loaded = !m_offsets.empty();
    info.source = m_source;
    info.entries = m_offsets.size();
    info.bytes = m_blob.size();
    info.cachePath = m_cachePath;
    info.loadedAtUnix = m_loadedAtUnix;
    return info;
}

DictionaryImportResult DictionaryService::ImportEcdictCsv(
    const std::string& csvPath, const DictionaryImportOptions& options,
    const DictionaryProgressFn& progress, const std::atomic_bool* cancel)
{
    const auto started = std::chrono::steady_clock::now();
    DictionaryImportResult result;
    result.source = DictionarySourceKind::EcdictCsv;

    if (csvPath.empty()) {
        result.code = dictionary_codes::kFileMissing;
        result.error = "CSV path is empty";
        return result;
    }

    std::string code;
    std::string error;
    std::string csvText;
    if (!ReadFileBytesUtf8(csvPath, options.maxFileBytes, csvText, code)) {
        result.code = code;
        result.error = FormatError("cannot read CSV", csvPath);
        return result;
    }
    if (csvText.empty()) {
        result.code = dictionary_codes::kEmpty;
        result.error = "CSV file is empty";
        return result;
    }
    if (progress) {
        DictionaryProgress update;
        update.bytesTotal = static_cast<std::int64_t>(csvText.size());
        update.stage = "parsing";
        progress(update);
    }

    DictionaryStore store;
    std::size_t skipped = 0;
    if (!BuildEcdictStore(csvText, options, progress, cancel, store, skipped, code, error)) {
        result.code = code;
        result.error = error;
        result.skipped = skipped;
        result.elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                               std::chrono::steady_clock::now() - started)
                               .count();
        return result;
    }
    if (store.offsets.empty()) {
        result.code = dictionary_codes::kEmpty;
        result.error = "no usable entries in CSV";
        result.skipped = skipped;
        return result;
    }
    if (cancel && cancel->load()) {
        result.code = dictionary_codes::kCancelled;
        result.error = "import cancelled";
        result.skipped = skipped;
        return result;
    }

    store.SortByWord();
    const std::size_t entries = store.offsets.size();
    const std::size_t bytes = store.blob.size();
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_blob.swap(store.blob);
        m_offsets.swap(store.offsets);
        m_source = DictionarySourceKind::EcdictCsv;
        m_loadedAtUnix = NowUnixSeconds();
        m_cachePath.clear();
    }

    result.ok = true;
    result.code = dictionary_codes::kOk;
    result.entries = entries;
    result.bytes = bytes;
    result.skipped = skipped;

    if (!options.cachePath.empty()) {
        if (progress) {
            DictionaryProgress update;
            update.processed = static_cast<std::int64_t>(entries);
            update.stage = "saving";
            progress(update);
        }
        std::string cacheCode;
        if (SaveCache(options.cachePath, &cacheCode)) {
            result.cacheSaved = true;
            result.cachePath = options.cachePath;
        } else {
            result.cacheError = cacheCode;
        }
    }
    result.elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                           std::chrono::steady_clock::now() - started)
                           .count();
    return result;
}

DictionaryImportResult DictionaryService::ImportStarDict(
    const std::string& ifoPath, const DictionaryImportOptions& options,
    const DictionaryProgressFn& progress, const std::atomic_bool* cancel)
{
    const auto started = std::chrono::steady_clock::now();
    DictionaryImportResult result;
    result.source = DictionarySourceKind::StarDict;

    if (ifoPath.empty()) {
        result.code = dictionary_codes::kFileMissing;
        result.error = "ifo path is empty";
        return result;
    }

    std::string code;
    std::string error;
    std::string ifoText;
    if (!ReadFileBytesUtf8(ifoPath, options.maxFileBytes, ifoText, code)) {
        result.code = code;
        result.error = FormatError("cannot read ifo", ifoPath);
        return result;
    }
    IfoMeta meta;
    if (!ParseIfo(ifoText, meta, error)) {
        result.code = dictionary_codes::kBadIfo;
        result.error = error;
        return result;
    }

    const std::string base = StripIfoSuffix(ifoPath);

    std::string idxData;
    {
        std::string firstCode;
        std::string firstError;
        if (!ReadMaybeGzip(base + ".idx", options.maxFileBytes, idxData, firstCode, firstError)) {
            std::string gzCode;
            std::string gzError;
            if (!ReadMaybeGzip(base + ".idx.gz", options.maxFileBytes, idxData, gzCode, gzError)) {
                if (firstCode == dictionary_codes::kFileMissing) {
                    result.code = dictionary_codes::kFileMissing;
                    result.error = "StarDict .idx file not found";
                } else {
                    result.code = firstCode;
                    result.error = firstError;
                }
                return result;
            }
        }
    }

    FileReader dictReader;
    std::string dictBytes;
    bool dictInMemory = false;
    std::uint64_t dictSize = 0;
    {
        const std::string plainPath = base + ".dict";
        if (FileExistsUtf8(plainPath)) {
            if (!dictReader.Open(plainPath, code)) {
                result.code = code;
                result.error = FormatError("cannot read dict", plainPath);
                return result;
            }
            dictSize = dictReader.Size();
            if (dictSize > options.maxFileBytes) {
                result.code = dictionary_codes::kTooLarge;
                result.error = "dictionary data exceeds maxFileBytes";
                return result;
            }
        } else {
            std::string dictCode;
            std::string dictError;
            if (!ReadMaybeGzip(base + ".dict.dz", options.maxFileBytes, dictBytes,
                               dictCode, dictError)) {
                if (!ReadMaybeGzip(base + ".dict.gz", options.maxFileBytes, dictBytes,
                                   dictCode, dictError)) {
                    if (dictCode == dictionary_codes::kFileMissing) {
                        result.code = dictionary_codes::kFileMissing;
                        result.error = "StarDict .dict file not found";
                    } else {
                        result.code = dictCode;
                        result.error = dictError;
                    }
                    return result;
                }
            }
            dictInMemory = true;
            dictSize = dictBytes.size();
        }
    }

    std::vector<IdxEntry> entries;
    if (!ParseIdxEntries(idxData, meta.idxOffset64, dictSize, options.maxEntries,
                         entries, code, error)) {
        result.code = code;
        result.error = error;
        return result;
    }
    if (entries.empty()) {
        result.code = dictionary_codes::kEmpty;
        result.error = "no entries in StarDict index";
        return result;
    }

    if (progress) {
        DictionaryProgress update;
        update.bytesTotal = static_cast<std::int64_t>(idxData.size());
        update.stage = "parsing";
        progress(update);
    }

    DictionaryStore store;
    std::size_t skipped = 0;
    for (std::size_t i = 0; i < entries.size(); ++i) {
        if (cancel && (i & 0x1FF) == 0 && cancel->load()) {
            result.code = dictionary_codes::kCancelled;
            result.error = "import cancelled";
            result.skipped = skipped;
            return result;
        }
        std::string data;
        if (dictInMemory) {
            data = dictBytes.substr(static_cast<std::size_t>(entries[i].offset),
                                    entries[i].size);
        } else if (!dictReader.ReadAt(entries[i].offset, entries[i].size, data, code)) {
            ++skipped;
            continue;
        }

        std::string phonetic;
        std::string translation;
        std::string definition;
        if (!ParseStarDictData(data, meta.sameTypeSequence, phonetic, translation, definition)) {
            ++skipped;  // 二进制类型（W/P）等无法转文本的条目
            continue;
        }
        if (store.offsets.size() >= options.maxEntries) {
            result.code = dictionary_codes::kTooLarge;
            result.error = "entry count exceeds maxEntries";
            result.skipped = skipped;
            return result;
        }

        std::string fields[kFieldCount];
        fields[kWord] = entries[i].word;
        fields[kPhonetic] = phonetic;
        fields[kTranslation] = translation;
        fields[kDefinition] = definition;
        if (!store.Add(fields, code)) {
            result.code = code;
            result.error = "dictionary exceeds the configured size limit";
            result.skipped = skipped;
            return result;
        }
        if (store.blob.size() > options.maxBytes) {
            result.code = dictionary_codes::kTooLarge;
            result.error = "record bytes exceed maxBytes";
            result.skipped = skipped;
            return result;
        }
        if (progress && (i & 0x3FF) == 0) {
            DictionaryProgress update;
            update.processed = static_cast<std::int64_t>(i);
            update.bytesProcessed = static_cast<std::int64_t>(i);
            update.bytesTotal = static_cast<std::int64_t>(entries.size());
            update.stage = "parsing";
            progress(update);
        }
    }
    if (store.offsets.empty()) {
        result.code = dictionary_codes::kEmpty;
        result.error = "no usable entries in StarDict dictionary";
        result.skipped = skipped;
        return result;
    }
    if (cancel && cancel->load()) {
        result.code = dictionary_codes::kCancelled;
        result.error = "import cancelled";
        result.skipped = skipped;
        return result;
    }

    store.SortByWord();
    const std::size_t entryCount = store.offsets.size();
    const std::size_t bytes = store.blob.size();
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_blob.swap(store.blob);
        m_offsets.swap(store.offsets);
        m_source = DictionarySourceKind::StarDict;
        m_loadedAtUnix = NowUnixSeconds();
        m_cachePath.clear();
    }

    result.ok = true;
    result.code = dictionary_codes::kOk;
    result.entries = entryCount;
    result.bytes = bytes;
    result.skipped = skipped;

    if (!options.cachePath.empty()) {
        std::string cacheCode;
        if (SaveCache(options.cachePath, &cacheCode)) {
            result.cacheSaved = true;
            result.cachePath = options.cachePath;
        } else {
            result.cacheError = cacheCode;
        }
    }
    result.elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                           std::chrono::steady_clock::now() - started)
                           .count();
    return result;
}

bool DictionaryService::SaveCache(const std::string& cachePath, std::string* errorCode)
{
    const auto fail = [errorCode](const char* code) {
        if (errorCode) *errorCode = code;
        return false;
    };
    if (cachePath.empty()) return fail(dictionary_codes::kIoError);

    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_offsets.empty()) return fail(dictionary_codes::kEmpty);

    std::string code;
    AtomicFileWriter writer;
    if (!writer.Open(cachePath, code, dictionary_codes::kIoError)) {
        return fail(code.empty() ? dictionary_codes::kIoError : code.c_str());
    }

    const std::string header =
        MakeCacheHeader(static_cast<std::uint32_t>(m_source),
                       static_cast<std::uint32_t>(m_offsets.size()),
                       static_cast<std::uint64_t>(m_loadedAtUnix));
    if (!writer.Write(header.data(), header.size())) return fail(dictionary_codes::kIoError);

    for (const std::uint32_t offset : m_offsets) {
        const std::uint32_t length = RecordLength(m_blob, offset);
        if (!writer.Write(m_blob.data() + offset, length)) {
            return fail(dictionary_codes::kIoError);
        }
    }
    if (!writer.Commit(code, dictionary_codes::kIoError)) {
        return fail(code.empty() ? dictionary_codes::kIoError : code.c_str());
    }

    m_cachePath = cachePath;
    if (errorCode) errorCode->clear();
    return true;
}

bool DictionaryService::LoadCache(const std::string& cachePath, std::string* errorCode)
{
    const auto fail = [errorCode](const char* code) {
        if (errorCode) *errorCode = code;
        return false;
    };
    if (cachePath.empty()) return fail(dictionary_codes::kCacheMissing);

    std::string code;
    std::string raw;
    if (!ReadFileBytesUtf8(cachePath, 1ull << 31, raw, code)) {
        return fail(code == dictionary_codes::kFileMissing ? dictionary_codes::kCacheMissing
                                                           : dictionary_codes::kIoError);
    }
    if (raw.size() < kCacheHeaderBytes) return fail(dictionary_codes::kCacheCorrupt);
    if (std::memcmp(raw.data(), kCacheMagic, sizeof kCacheMagic) != 0) {
        return fail(dictionary_codes::kCacheCorrupt);
    }
    const std::uint32_t version = ReadU32Le(raw.data() + 8);
    if (version != kCacheVersion) return fail(dictionary_codes::kCacheVersion);
    const std::uint32_t sourceKind = ReadU32Le(raw.data() + 12);
    if (sourceKind != static_cast<std::uint32_t>(DictionarySourceKind::EcdictCsv) &&
        sourceKind != static_cast<std::uint32_t>(DictionarySourceKind::StarDict)) {
        return fail(dictionary_codes::kCacheCorrupt);
    }
    const std::uint32_t entryCount = ReadU32Le(raw.data() + 16);
    const std::int64_t createdAt = static_cast<std::int64_t>(ReadU64Le(raw.data() + 24));

    std::string blob = std::move(raw);
    blob.erase(0, kCacheHeaderBytes);

    if (static_cast<std::uint64_t>(entryCount) > blob.size() / (kRecordHeaderBytes + 1u)) {
        return fail(dictionary_codes::kCacheCorrupt);
    }
    std::vector<std::uint32_t> offsets;
    offsets.reserve(entryCount);
    std::size_t position = 0;
    for (std::uint32_t i = 0; i < entryCount; ++i) {
        if (position + kRecordHeaderBytes > blob.size()) return fail(dictionary_codes::kCacheCorrupt);
        std::uint64_t recordBytes = kRecordHeaderBytes;
        for (int field = 0; field < kFieldCount; ++field) {
            recordBytes += ReadU32Le(blob.data() + position + field * 4);
        }
        if (recordBytes > blob.size() - position) return fail(dictionary_codes::kCacheCorrupt);
        const std::uint32_t offset = static_cast<std::uint32_t>(position);
        if (!offsets.empty() &&
            CompareOffsetWords(blob, offsets.back(), offset) > 0) {
            return fail(dictionary_codes::kCacheCorrupt);  // 缓存必须已排序
        }
        offsets.push_back(offset);
        position += static_cast<std::size_t>(recordBytes);
    }
    if (position != blob.size()) return fail(dictionary_codes::kCacheCorrupt);

    {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_blob.swap(blob);
        m_offsets.swap(offsets);
        m_source = static_cast<DictionarySourceKind>(sourceKind);
        m_cachePath = cachePath;
        m_loadedAtUnix = createdAt > 0 ? createdAt : NowUnixSeconds();
    }
    if (errorCode) errorCode->clear();
    return true;
}

DictionaryLookupResult DictionaryService::Lookup(
    const std::string& word, const DictionaryLookupOptions& options) const
{
    DictionaryLookupResult result;
    result.query = Trim(word);
    if (result.query.empty()) {
        result.code = dictionary_codes::kEmptyQuery;
        result.error = "query is empty";
        return result;
    }
    if (result.query.size() > kMaxQueryBytes) {
        result.code = dictionary_codes::kQueryTooLong;
        result.error = "query is too long";
        return result;
    }

    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_offsets.empty()) {
        result.code = dictionary_codes::kNotLoaded;
        result.error = "no dictionary loaded";
        return result;
    }
    result.ok = true;

    const std::size_t limit = options.maxResults == 0 ? 1 : options.maxResults;
    // 索引始终按小写词头排序，所以二分查小写键；大小写敏感只在命中后按原串
    // 过滤（否则 "Hello"/"hello" 这种同小写不同大小写的词条会被漏掉）。
    const std::string keyLower = ToLowerAscii(result.query);
    const std::string queryView = result.query;

    const auto addHit = [&](std::uint32_t offset, DictionaryMatchKind kind,
                            std::string matched, bool verified) {
        DictionaryHit hit;
        const std::string_view wordView = RecordFieldView(m_blob, offset, kWord);
        hit.entry.word = std::string(wordView);
        hit.entry.phonetic = std::string(RecordFieldView(m_blob, offset, kPhonetic));
        hit.entry.translation = std::string(RecordFieldView(m_blob, offset, kTranslation));
        hit.entry.definition = std::string(RecordFieldView(m_blob, offset, kDefinition));
        hit.entry.pos = std::string(RecordFieldView(m_blob, offset, kPos));
        hit.entry.tag = std::string(RecordFieldView(m_blob, offset, kTag));
        hit.entry.exchange = std::string(RecordFieldView(m_blob, offset, kExchange));
        hit.entry.example = std::string(RecordFieldView(m_blob, offset, kExample));
        hit.kind = kind;
        hit.matched = std::move(matched);
        hit.verified = verified;
        result.hits.push_back(std::move(hit));
    };

    // 1. 精确（大小写不敏感时按小写，大小写敏感时再过滤一遍原串）
    std::vector<std::uint32_t> exactOffsets;
    {
        const std::size_t begin = LowerBoundOffset(m_blob, m_offsets, keyLower);
        std::size_t position = begin;
        while (position < m_offsets.size() &&
               CompareOffsetKeyLower(m_blob, m_offsets[position], keyLower) == 0) {
            const std::uint32_t offset = m_offsets[position];
            ++position;
            if (!options.caseInsensitive) {
                const std::string_view storedWord = RecordFieldView(m_blob, offset, kWord);
                if (!EqualsView(storedWord, queryView)) continue;
            }
            exactOffsets.push_back(offset);
            addHit(offset, DictionaryMatchKind::Exact, std::string(RecordFieldView(m_blob, offset, kWord)), true);
            if (result.hits.size() >= limit) break;
        }
    }

    // 2. 精确命中里如果带 "0:lemma"，把原形词条也附上（went → go）
    if (options.morphology) {
        for (const std::uint32_t offset : exactOffsets) {
            if (result.hits.size() >= limit) break;
            const std::string exchange(RecordFieldView(m_blob, offset, kExchange));
            if (exchange.empty()) continue;
            for (const auto& form : SplitExchange(exchange)) {
                if (form.first != "0" || form.second.empty()) continue;
                const std::string lemma = form.second;
                const std::string lemmaLower = ToLowerAscii(lemma);
                if (lemmaLower == keyLower) continue;
                const std::vector<std::uint32_t> lemmaOffsets =
                    FindExactOffsets(m_blob, m_offsets, lemmaLower, 1);
                for (const std::uint32_t lemmaOffset : lemmaOffsets) {
                    addHit(lemmaOffset, DictionaryMatchKind::Morphology,
                           std::string(RecordFieldView(m_blob, lemmaOffset, kWord)), true);
                }
            }
        }
    }

    // 3. 没命中 → 词形还原（后缀规则猜原形，再用原形的 exchange 反向验证）
    if (result.hits.empty() && options.morphology) {
        const std::vector<std::string> candidates = LemmaCandidates(keyLower);
        for (const std::string& candidate : candidates) {
            if (result.hits.size() >= limit) break;
            const std::vector<std::uint32_t> candidateOffsets =
                FindExactOffsets(m_blob, m_offsets, candidate, 1);
            for (const std::uint32_t offset : candidateOffsets) {
                const std::string_view storedWord = RecordFieldView(m_blob, offset, kWord);
                if (EqualsView(storedWord, queryView)) continue;  // 与精确重复
                const std::string exchange(RecordFieldView(m_blob, offset, kExchange));
                const bool verified = ExchangeContainsForm(exchange, keyLower);
                if (!verified && !exchange.empty()) continue;  // 有词形信息但对不上 → 不是它
                addHit(offset, DictionaryMatchKind::Morphology,
                       std::string(storedWord), verified);
            }
        }
    }

    // 4. 还没有 → 前缀（"hel" → hello / help）
    if (result.hits.empty() && options.prefix) {
        const std::size_t maxScan = options.maxPrefixScan == 0 ? 1 : options.maxPrefixScan;
        std::size_t scanned = 0;
        std::size_t position = LowerBoundOffset(m_blob, m_offsets, keyLower);
        while (position < m_offsets.size() && scanned < maxScan) {
            const std::uint32_t offset = m_offsets[position];
            const std::string_view storedWord = RecordFieldView(m_blob, offset, kWord);
            if (storedWord.size() < keyLower.size() ||
                CompareLower(storedWord.data(), keyLower.size(),
                             keyLower.data(), keyLower.size()) != 0) {
                break;
            }
            addHit(offset, DictionaryMatchKind::Prefix, std::string(storedWord), true);
            ++position;
            ++scanned;
            if (result.hits.size() >= limit) break;
        }
    }

    result.found = !result.hits.empty();
    result.code = result.found ? dictionary_codes::kOk : dictionary_codes::kNotFound;
    return result;
}

std::vector<std::string> DictionaryService::Suggest(const std::string& prefix,
                                                    std::size_t maxResults) const
{
    std::vector<std::string> suggestions;
    const std::string trimmed = Trim(prefix);
    if (trimmed.empty()) return suggestions;
    const std::string keyLower = ToLowerAscii(trimmed);
    const std::size_t limit = maxResults == 0 ? 1 : maxResults;
    const std::size_t maxScan = 5000;

    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_offsets.empty()) return suggestions;

    std::size_t position = LowerBoundOffset(m_blob, m_offsets, keyLower);
    std::size_t scanned = 0;
    while (position < m_offsets.size() && scanned < maxScan &&
           suggestions.size() < limit) {
        const std::uint32_t offset = m_offsets[position];
        const std::string_view storedWord = RecordFieldView(m_blob, offset, kWord);
        if (storedWord.size() < keyLower.size() ||
            CompareLower(storedWord.data(), keyLower.size(),
                         keyLower.data(), keyLower.size()) != 0) {
            break;
        }
        suggestions.emplace_back(storedWord);
        ++position;
        ++scanned;
    }
    return suggestions;
}

} // namespace pb
