#include "FileHandlers.h"
#include "FileHandlersUpdate.h"
#include "BridgeServer.h"
#include "utils/encoding.h"
#include "utils/base64.h"
#include "utils/fnv1a.h"
#include "WebViewHost.h"
#include "utils/update_meta.h"
#include "utils/win_cmd.h"
#include "App.h"
#include "pb_version.h"  // generated from CMake project VERSION
#include "services/DatabaseService.h"
#include "services/LibraryService.h"
#include "services/ContentCache.h"
#include "platform/JobService.h"
#include "platform/LogService.h"
#include <fstream>
#include <vector>
#include <cstdlib>
#include <functional>
#include <filesystem>
#include <shobjidl.h>
#include <commdlg.h>
#include <urlmon.h>
#include <winhttp.h>
#include <shellapi.h>
#include <bcrypt.h>
#include <cstring>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <atomic>
#include <thread>
#include <ctime>
#include <mutex>

#pragma comment(lib, "urlmon.lib")
#pragma comment(lib, "winhttp.lib")
#pragma comment(lib, "bcrypt.lib")

// PB-024-B: 从本文件拆出的两个职责（纯搬移）
#include "FileFormatService.h"
#include "FileImportService.h"
using filefmt::ConvertMobiToText;
using filefmt::DebugLog;
using filefmt::GetFileName;
using filefmt::GetFileSizeSafe;

// ── Base64 decode — for drag & drop file transfer ────────────────────────
// (moved to utils/base64.h: pb::Base64Decode)

// file:read 的上限：单本书 2GiB 封顶（正常电子书远小于此）；走 JSON 字节数组的
// 兜底路径更严格（512MiB），否则一次序列化就是 GB 级字符串。
constexpr std::uint64_t kMaxReadFileBytes = 2ull * 1024ull * 1024ull * 1024ull;
constexpr std::size_t kMaxJsonReadBytes = 512ull * 1024ull * 1024ull;

// file:read 的入参校验（fail-closed）。渲染层只该读「书库里那本书」的本地文件；
// UNC / 设备命名空间 / 相对路径一律拒绝 —— 桥接脚本对每个文档都会注入，书页 iframe
// 与外部页面都拿得到 window.electronAPI，不能让它变成一个任意文件读取器。
static bool ValidateReadablePath(const std::string& path, std::string& error)
{
    if (path.empty()) { error = "empty_path"; return false; }
    if (path.find('\0') != std::string::npos) { error = "invalid_path"; return false; }
    if (path.size() > 32767) { error = "invalid_path"; return false; }

    // 网络路径（\\server\share\...）放行 —— 书库可能放在 NAS 上；
    // 但设备命名空间（\\.\、\\?\、\??\）一律拒绝：那是绕过文件系统语义的后门。
    const bool isNetworkPath = path.size() >= 2 &&
        (path[0] == '\\' || path[0] == '/') &&
        (path[1] == '\\' || path[1] == '/');
    if (isNetworkPath) {
        char third = path.size() >= 3 ? path[2] : '\0';
        if (third == '.' || third == '?') {
            error = "device_path_not_allowed";
            return false;
        }
    } else {
        // 其余必须是「盘符:绝对路径」
        const bool hasDriveLetter = path.size() >= 3 &&
            std::isalpha(static_cast<unsigned char>(path[0])) != 0 &&
            path[1] == ':' && (path[2] == '\\' || path[2] == '/');
        if (!hasDriveLetter) { error = "path_not_absolute"; return false; }
    }

    const std::wstring wide = pb::Utf8ToWide(path);
    if (wide.empty()) { error = "invalid_path"; return false; }

    WIN32_FILE_ATTRIBUTE_DATA info = {};
    if (!GetFileAttributesExW(wide.c_str(), GetFileExInfoStandard, &info)) {
        error = "file_not_found";
        return false;
    }
    if ((info.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0) {
        error = "not_a_file";
        return false;
    }
    const std::uint64_t size = (static_cast<std::uint64_t>(info.nFileSizeHigh) << 32)
                             | static_cast<std::uint64_t>(info.nFileSizeLow);
    if (size > kMaxReadFileBytes) { error = "file_too_large"; return false; }
    return true;
}

static json ReadFileForRenderer(ContentCache* cache, const std::string& path)
{
    std::vector<uint8_t> cached;
    if (cache && cache->Get(path, cached)) return json(cached);

    // Serve every format via the virtual-host URL. The old ≤5MB path
    // serialized the file as a JSON byte array (≈4x expansion + UTF-16
    // doubling) and froze the UI for multi-MB books; the virtual host lets
    // the frontend fetch the bytes directly. MOBI stays on the JSON path
    // below because it must go through ConvertMobiToText (encoding fix).
    std::string format = filefmt::DetectFormat(path);
    if (format != "mobi" && format != "azw3") {
        // Copy to renderer temp dir (bounded — evict oldest beyond a cap;
        // _pb_files are re-copied on demand, so eviction is always safe)
        wchar_t exePath[MAX_PATH];
        GetModuleFileNameW(nullptr, exePath, MAX_PATH);
        auto rendererDir = (std::filesystem::path(exePath).parent_path() / "renderer" / "_pb_files").wstring();
        CreateDirectoryW(rendererDir.c_str(), nullptr);

        std::error_code ec;
        std::vector<std::filesystem::directory_entry> entries;
        for (auto& de : std::filesystem::directory_iterator(rendererDir, ec)) {
            if (!de.is_directory()) entries.push_back(de);
        }
        // 上限从 64 提到 128：一本 EPUB 的正文/封面/多个渲染器页面图都会各占一个副本，
        // 64 太容易在「连开几本书」时互相挤掉（表现为 ERR_FILE_NOT_FOUND）。
        if (entries.size() > 128) {
            std::sort(entries.begin(), entries.end(), [](const auto& a, const auto& b) {
                return a.last_write_time() < b.last_write_time();
            });
            for (size_t i = 0; i < entries.size() - 128; i++) {
                std::filesystem::remove(entries[i].path(), ec);
            }
        }

        // Key = stable FNV-1a hash of the full path: pure ASCII (no URL-encoding
        // issues for names containing '#'/'%'/spaces) and unique per source
        // path (no same-basename overwrite between different folders). FNV-1a
        // is deterministic (std::hash is not stable across runs/compilers).
        std::string fn = pb::Fnv1a64Hex(path);

        std::wstring dest = rendererDir + L"\\" + pb::Utf8ToWide(fn);
        if (CopyFileW(pb::Utf8ToWide(path).c_str(), dest.c_str(), FALSE)) {
            // 复制默认继承源文件的 mtime，而清理是按 mtime 的 LRU —— 刚复制出来的副本会
            // 被当成"最旧"的，下一次 read 触发清理就可能把它删掉，紧接着渲染层 fetch
            // 这个 URL 就 ERR_FILE_NOT_FOUND（长期被误认为"打开第二本书偶发失败"）。
            // 复制后把 mtime 改成当前时间，LRU 才真正表示"最近使用"。
            HANDLE attrHandle = CreateFileW(dest.c_str(), FILE_WRITE_ATTRIBUTES,
                                            FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr,
                                            OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
            if (attrHandle != INVALID_HANDLE_VALUE) {
                FILETIME now{};
                GetSystemTimeAsFileTime(&now);
                SetFileTime(attrHandle, nullptr, nullptr, &now);
                CloseHandle(attrHandle);
            }
            // 同一个源路径换了文件（用户替换 / 重新导入同名书）时，文件名不变 →
            // WebView2 的 HTTP 缓存会把旧内容再发一次（实测：PDF 换封面后阅读器仍显示
            // 旧版）。URL 带「修改时间 ^ 大小」做版本号，文件一改就是新 URL。
            ULARGE_INTEGER stamp{};
            WIN32_FILE_ATTRIBUTE_DATA attrs{};
            if (GetFileAttributesExW(pb::Utf8ToWide(path).c_str(), GetFileExInfoStandard, &attrs)) {
                stamp.LowPart = attrs.ftLastWriteTime.dwLowDateTime;
                stamp.HighPart = attrs.ftLastWriteTime.dwHighDateTime;
                stamp.QuadPart ^= static_cast<unsigned long long>(attrs.nFileSizeLow)
                    | (static_cast<unsigned long long>(attrs.nFileSizeHigh) << 32);
            }
            std::string url = "http://particlebook.app/_pb_files/" + fn
                + "?v=" + std::to_string(stamp.QuadPart);
            json r;
            r["_pb_url"] = url;
            return r;
        }
        // Copy failed → fall through to the JSON byte-array read below
    }

    // For MOBI files, extract text via mutool (cached)
    std::string mobiText = ConvertMobiToText(path);
    if (!mobiText.empty()) {
        int64_t ft = 0;
        int wl = MultiByteToWideChar(CP_UTF8, 0, path.c_str(), (int)path.size(), nullptr, 0);
        if (wl > 0) {
            std::wstring wp(wl, L'\0');
            MultiByteToWideChar(CP_UTF8, 0, path.c_str(), (int)path.size(), &wp[0], wl);
            WIN32_FILE_ATTRIBUTE_DATA attrs;
            if (GetFileAttributesExW(wp.c_str(), GetFileExInfoStandard, &attrs)) {
                ft = (static_cast<int64_t>(attrs.ftLastWriteTime.dwHighDateTime) << 32)
                   | attrs.ftLastWriteTime.dwLowDateTime;
            }
        }
        std::vector<uint8_t> data(mobiText.begin(), mobiText.end());
        if (cache) cache->Put(path, data, ft);
        return json(data);
    }

    // Convert UTF-8 path to wide for Windows API
    int wlen = MultiByteToWideChar(CP_UTF8, 0, path.c_str(), (int)path.size(), nullptr, 0);
    if (wlen <= 0) return json(nullptr);
    std::wstring wpath(wlen, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, path.c_str(), (int)path.size(), &wpath[0], wlen);

    HANDLE hFile = CreateFileW(wpath.c_str(), GENERIC_READ, FILE_SHARE_READ,
                               nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (hFile == INVALID_HANDLE_VALUE) return json(nullptr);

    FILETIME ftWrite;
    GetFileTime(hFile, nullptr, nullptr, &ftWrite);
    int64_t fileTime = (static_cast<int64_t>(ftWrite.dwHighDateTime) << 32)
                     | ftWrite.dwLowDateTime;

    LARGE_INTEGER liSize;
    GetFileSizeEx(hFile, &liSize);
    size_t size = static_cast<size_t>(liSize.QuadPart);

    // 兜底路径：整本读进内存再以 JSON 字节数组过桥（体积 4 倍以上膨胀）。
    // 超大文件宁可失败也不能把主进程/渲染进程一起打爆（正常书都走虚拟主机 URL）。
    if (size > kMaxJsonReadBytes) {
        CloseHandle(hFile);
        pb::LogWarn("file", "file:read JSON fallback refused (too large: " +
                                std::to_string(size) + " bytes): " + GetFileName(path));
        return json(nullptr);
    }

    std::vector<uint8_t> data(size);
    DWORD bytesRead = 0;
    ReadFile(hFile, data.data(), static_cast<DWORD>(size), &bytesRead, nullptr);
    CloseHandle(hFile);

    data.resize(bytesRead);
    if (cache) cache->Put(path, data, fileTime);
    return json(data);
}


// ── 封面：book:cover 的实现（跑在 JobService worker 上）────────────────
// 为什么不用同步 handler：EPUB 抽封面是 zip 读 + 写盘，PDF 现场生成要起 mutool
// 子进程（最长 30 秒）—— 同步 handler 会把这些全压到 WebView2 的 UI 线程上。

static std::string Base64EncodeBytes(const std::vector<unsigned char>& data)
{
    static const char* b64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string out;
    out.reserve((data.size() + 2) / 3 * 4);
    int val = 0;
    int valb = -6;
    for (unsigned char c : data) {
        val = (val << 8) + c;
        valb += 8;
        while (valb >= 0) { out.push_back(b64[(val >> valb) & 0x3F]); valb -= 6; }
    }
    if (valb > -6) out.push_back(b64[((val << 8) >> (valb + 8)) & 0x3F]);
    while (out.size() % 4) out.push_back('=');
    return out;
}

// 读一个小文件（封面）：32MiB 封顶，避免被超大/伪造的 cover_path 拉爆内存。
static bool ReadSmallFileBytes(const std::string& path, std::vector<unsigned char>& out)
{
    out.clear();
    const std::wstring wide = pb::Utf8ToWide(path);
    if (wide.empty()) return false;
    HANDLE hFile = CreateFileW(wide.c_str(), GENERIC_READ, FILE_SHARE_READ,
                               nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (hFile == INVALID_HANDLE_VALUE) return false;
    LARGE_INTEGER liSize = {};
    if (!GetFileSizeEx(hFile, &liSize) || liSize.QuadPart <= 0 ||
        liSize.QuadPart > 32ll * 1024 * 1024) {
        CloseHandle(hFile);
        return false;
    }
    out.assign(static_cast<size_t>(liSize.QuadPart), 0);
    DWORD read = 0;
    const BOOL ok = ReadFile(hFile, out.data(), static_cast<DWORD>(out.size()), &read, nullptr);
    CloseHandle(hFile);
    if (!ok) { out.clear(); return false; }
    out.resize(read);
    return !out.empty();
}

static std::string CoverMimeOf(const std::string& path)
{
    std::string ext;
    const size_t dot = path.rfind('.');
    if (dot != std::string::npos) {
        ext = path.substr(dot);
        for (auto& c : ext) c = static_cast<char>(tolower(static_cast<unsigned char>(c)));
    }
    // 按扩展名给出正确的图片 MIME（2026-10-29 review · B-10）：
    // 以前只区分 png 与"其它一律 jpeg"，EPUB 里的 gif/webp/svg 封面会被标成
    // image/jpeg —— data URL 的 MIME 不会被浏览器嗅探纠正，卡片就变破图。
    if (ext == ".png") return "png";
    if (ext == ".gif") return "gif";
    if (ext == ".webp") return "webp";
    if (ext == ".svg") return "svg+xml";
    if (ext == ".jpg" || ext == ".jpeg" || ext == ".bmp") return "jpeg";
    return "jpeg";
}

// ── 拖放导入落盘（跑在 JobService worker 上，review · B-11）─────────────
// 以前是同步 handler：在 UI 线程上 Base64Decode 整本书再一次性 WriteFile，
// 拖一本 500MB 的 CBZ 会让窗口直接未响应（还因为 DWORD 截断在 4GiB 处静默截短）。
static json WriteDroppedFile(const std::string& name, const std::string& b64)
{
    // Sanitize to a plain file name (no separators / reserved chars)
    std::string safeName;
    for (char c : name) {
        if (c == '\\' || c == '/' || c == ':' || c == '*' || c == '?' ||
            c == '"' || c == '<' || c == '>' || c == '|') continue;
        safeName += c;
    }
    if (safeName.empty()) safeName = "dropped-book";
    if (safeName.size() > 120) safeName.resize(120);

    std::vector<uint8_t> bytes = pb::Base64Decode(b64);
    if (bytes.empty()) return json(nullptr);
    const std::uint64_t total = static_cast<std::uint64_t>(bytes.size());

    std::string dir = App::Instance().UserDataPath() + "/dropped";
    std::filesystem::create_directories(pb::Utf8ToWide(dir));

    std::string filePath = dir + "\\" + safeName;
    // Avoid silently overwriting a previously-dropped file with the same
    // name: append a numeric suffix so two different books that happen to
    // share a filename each get their own file.
    if (GetFileAttributesW(pb::Utf8ToWide(filePath).c_str()) != INVALID_FILE_ATTRIBUTES) {
        size_t dot = safeName.rfind('.');
        std::string stem = (dot != std::string::npos) ? safeName.substr(0, dot) : safeName;
        std::string ext = (dot != std::string::npos) ? safeName.substr(dot) : "";
        for (int i = 1; i < 1000; i++) {
            std::string cand = dir + "\\" + stem + " (" + std::to_string(i) + ")" + ext;
            if (GetFileAttributesW(pb::Utf8ToWide(cand).c_str()) == INVALID_FILE_ATTRIBUTES) {
                filePath = cand;
                break;
            }
        }
    }

    HANDLE hFile = CreateFileW(pb::Utf8ToWide(filePath).c_str(), GENERIC_WRITE, 0, nullptr,
                               CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (hFile == INVALID_HANDLE_VALUE) return json(nullptr);
    // 分块写，单次 WriteFile 仍在 DWORD 范围内；用 uint64 累计判断完整性。
    std::uint64_t writtenTotal = 0;
    bool ok = true;
    const std::size_t kChunk = 8ull * 1024ull * 1024ull;
    for (std::size_t offset = 0; offset < bytes.size(); offset += kChunk) {
        const DWORD chunk = static_cast<DWORD>(
            std::min<std::size_t>(kChunk, bytes.size() - offset));
        DWORD written = 0;
        if (!WriteFile(hFile, bytes.data() + offset, chunk, &written, nullptr) ||
            written != chunk) {
            ok = false;
            break;
        }
        writtenTotal += written;
    }
    CloseHandle(hFile);
    if (!ok || writtenTotal != total) {
        DeleteFileW(pb::Utf8ToWide(filePath).c_str());
        return json(nullptr);
    }

    json result;
    result["path"] = filePath;
    result["size"] = total;
    return result;
}

static json BuildCoverDataUrl(DatabaseService* db, int id)
{
    if (!db) return json(nullptr);
    const json book = db->GetBook(id);
    if (book.is_null()) return json(nullptr);

    // 1) 库里记着的封面文件
    const std::string coverPath = book.value("cover_path", "");
    if (!coverPath.empty()) {
        std::vector<unsigned char> data;
        if (ReadSmallFileBytes(coverPath, data)) {
            return json("data:image/" + CoverMimeOf(coverPath) + ";base64," +
                        Base64EncodeBytes(data));
        }
    }

    // 2) 现场生成：EPUB 从 zip 里抽封面，PDF 用 mutool 渲染首页。
    //    ExtractPdfCover 自己会先查「已生成且不旧于源文件」就直接复用，因此这里
    //    不会每次进书架都重起子进程（cover_path 仍为空时尤其重要）。
    const std::string filePath = book.value("file_path", "");
    const std::string format = book.value("format", "");
    if (filePath.empty()) return json(nullptr);

    LibraryService lib;
    ExtractedMetadata em;
    std::string generated;
    if (format == "epub") {
        lib.ExtractEpubMetadata(filePath, em);
        if (!em.coverFile.empty()) generated = lib.ExtractEpubCover(filePath, em.coverFile);
    } else if (format == "pdf") {
        generated = lib.ExtractPdfCover(filePath);
    }
    if (generated.empty()) return json(nullptr);

    // 生成成功后把 cover_path 写回：下次进书架直接命中第 1 步，
    // 不再为这本无封面书重跑抽封面（EPUB）或起 mutool（PDF）。
    if (db) {
        json patch = json::object();
        patch["cover_path"] = generated;
        db->UpdateBookMetadata(id, patch);
    }

    std::vector<unsigned char> data;
    if (!ReadSmallFileBytes(generated, data)) return json(nullptr);
    return json("data:image/jpeg;base64," + Base64EncodeBytes(data));
}

// PB-008-A：DOCX 转换产物的落盘 / 路径小工具（UTF-8 进出，内部走 W API）。
void RegisterFileHandlers(BridgeServer* bridge, DatabaseService* db, ContentCache* cache, pb::JobService* jobs)
{
    // ── dialog:openFile ───────────────────────────────────────
    bridge->RegisterMethod("dialog:openFile", [](const json&) -> json {
        wchar_t fileBuf[MAX_PATH * 10] = {};  // Large buffer for safety

        OPENFILENAMEW ofn = {};
        ofn.lStructSize = sizeof(ofn);
        ofn.lpstrFilter = L"E-Books\0*.epub;*.pdf;*.mobi;*.azw3;*.azw;*.docx;*.txt;*.fb2;*.cbz;*.cbr;*.html;*.md\0All Files\0*.*\0";
        ofn.lpstrFile = fileBuf;
        ofn.nMaxFile = sizeof(fileBuf) / sizeof(wchar_t);
        ofn.Flags = OFN_FILEMUSTEXIST | OFN_PATHMUSTEXIST | OFN_EXPLORER;
        ofn.lpstrTitle = L"Select E-Book File";

        if (!GetOpenFileNameW(&ofn)) return json(nullptr);

        return json(pb::WideToUtf8(fileBuf));
    });



    bridge->RegisterAsyncMethod("file:read",
        [cache, jobs](const json& p, std::function<void(json)> respond, std::function<void(std::string)> reject) {
            const std::string path = p.value("path", "");
            if (path.empty() || !jobs) { reject("invalid_params"); return; }
            // 入参校验放在提交 job 之前（只查文件属性，两次 syscall）：非法路径
            // 立刻回错误码，前端能区分「没这本书 / 太大 / 是目录」。
            std::string pathError;
            if (!ValidateReadablePath(path, pathError)) {
                pb::LogWarn("file", "file:read rejected (" + pathError + "): " +
                                        GetFileName(path));
                reject(pathError);
                return;
            }
            auto out = std::make_shared<json>(nullptr);
            const pb::JobId id = jobs->Submit("file:read",
                [cache, path, out](pb::JobContext&) { *out = ReadFileForRenderer(cache, path); },
                nullptr,
                [out, respond, reject](const pb::JobResult& r) {
                    if (!r.ok) { reject(r.cancelled ? "cancelled" : r.error); return; }
                    respond(*out);
                });
            if (id == pb::kInvalidJobId) reject("job_service_unavailable");
        });

    bridge->RegisterAsyncMethod("book:import",
        [db, jobs, bridge](const json& p, std::function<void(json)> respond, std::function<void(std::string)> reject) {
            if (!jobs) { reject("job_service_unavailable"); return; }
            const json paths = p.value("paths", json::array());
            auto out = std::make_shared<json>(json::array());
            const pb::JobId id = jobs->Submit("book:import",
                [db, bridge, paths, out](pb::JobContext& job) {
                    *out = fileimport::ImportBookPaths(db, paths, job, bridge);
                },
                nullptr,
                [out, respond, reject](const pb::JobResult& r) {
                    if (!r.ok) { reject(r.cancelled ? "cancelled" : r.error); return; }
                    respond(*out);
                });
            if (id == pb::kInvalidJobId) reject("job_service_unavailable");
        });

    // ── book:cover ─────────────────────────────────────────────
    // PB-020：改成异步。封面可能得「现场生成」—— PDF/CBZ 要走 mutool 子进程
    // （最长 30 秒），而同步 handler 跑在 WebView2 UI 线程上：书架每张无封面的
    // 卡片都会把窗口卡住一次。
    bridge->RegisterAsyncMethod("book:cover",
        [db, jobs](const json& p, std::function<void(json)> respond,
                   std::function<void(std::string)> reject) {
            if (!jobs) { reject("job_service_unavailable"); return; }
            const int id = p.value("id", 0);
            if (id <= 0) { reject("invalid_params"); return; }
            auto out = std::make_shared<json>(nullptr);
            const pb::JobId jobId = jobs->Submit("book:cover",
                [db, id, out](pb::JobContext&) { *out = BuildCoverDataUrl(db, id); },
                nullptr,
                [out, respond, reject](const pb::JobResult& r) {
                    if (!r.ok) { reject(r.cancelled ? "cancelled" : r.error); return; }
                    respond(*out);
                });
            if (jobId == pb::kInvalidJobId) reject("job_service_unavailable");
        });

    // ── Update checker ─────────────────────────────────────────
    bridge->RegisterMethod("app:getVersion", [](const json&) -> json { return json(PB_VERSION_STRING); });

    bridge->RegisterMethod("app:checkUpdate", [bridge](const json&) -> json {
        // Only one in-flight check at a time — the startup auto-check and the
        // manual Settings button would otherwise each spawn a WinHTTP thread
        // and emit duplicate app:updateChecked events.
        static std::atomic<bool> checkInFlight{false};
        if (checkInFlight.exchange(true)) return json(nullptr);

        // Run the blocking WinHTTP check on a background thread so startup
        // never freezes when GitHub is slow/unreachable. The result is surfaced
        // to the frontend via the 'app:updateChecked' event.
        HWND hwnd = bridge->GetWebViewHost() ? bridge->GetWebViewHost()->GetHwnd() : nullptr;
        if (!hwnd) { checkInFlight = false; return json(nullptr); }
        std::thread([hwnd]() {
            json result = CheckUpdateImpl();
            checkInFlight = false;  // allow the next check before delivering
            auto* resultStr = new std::string(result.dump());
            PostMessage(hwnd, WM_UPDATE_CHECK_DONE, 0, reinterpret_cast<LPARAM>(resultStr));
        }).detach();
        return json(nullptr);
    });

    bridge->RegisterMethod("app:downloadUpdate", [bridge](const json& p) -> json {
        std::string downloadUrl = p.value("url", "");
        std::string sha512 = p.value("sha512", "");
        if (downloadUrl.empty()) {
            pb::LogWarn("update", "downloadUpdate: empty download url");
            return json(nullptr);
        }

        // Integrity + trust: only accept official GitHub release URLs. Combined
        // with the origin gate in HandleMessage, this prevents any page from
        // making us download an arbitrary binary.
        const char* kOfficialPrefix = "https://github.com/ParticleLight/ParticleBook/releases/download/";
        if (downloadUrl.rfind(kOfficialPrefix, 0) != 0) {
            pb::LogWarn("update", "downloadUpdate: rejected non-official url");
            return json(nullptr);
        }

        // Integrity: sha512 必须由调用方给出且是完整哈希（128 位 hex）。
        // 旧实现在 sha512 为空 / "dummy" 时**跳过**校验 —— 而这个值完全由渲染层
        // 传入，等于把「下载并执行一个 exe」的能力留给任何能在应用页面里执行脚本的
        // 东西（旧版 latest.yml 不带哈希时也一样）。缺哈希就拒绝。
        if (sha512.size() != 128 || sha512 == "dummy") {
            pb::LogWarn("update", "downloadUpdate: rejected update without a usable sha512");
            return json(nullptr);
        }

        // Download in background to avoid blocking UI thread
        HWND hwnd = bridge->GetWebViewHost() ? bridge->GetWebViewHost()->GetHwnd() : nullptr;
        if (!hwnd) {
            // Fallback: open in browser
            int wlen = MultiByteToWideChar(CP_UTF8, 0, downloadUrl.c_str(), -1, nullptr, 0);
            std::wstring wUrl(wlen, L'\0');
            MultiByteToWideChar(CP_UTF8, 0, downloadUrl.c_str(), -1, &wUrl[0], wlen);
            while (!wUrl.empty() && wUrl.back() == L'\0') wUrl.pop_back();
            ShellExecuteW(nullptr, L"open", wUrl.c_str(), nullptr, nullptr, SW_SHOWNORMAL);
            return json(true);
        }

        std::thread([hwnd, downloadUrl, sha512]() {
            int wlen = MultiByteToWideChar(CP_UTF8, 0, downloadUrl.c_str(), -1, nullptr, 0);
            std::wstring wUrl(wlen, L'\0');
            MultiByteToWideChar(CP_UTF8, 0, downloadUrl.c_str(), -1, &wUrl[0], wlen);
            while (!wUrl.empty() && wUrl.back() == L'\0') wUrl.pop_back();

            wchar_t tmpPath[MAX_PATH];
            GetTempPathW(MAX_PATH, tmpPath);
            std::wstring exePath = std::wstring(tmpPath) + L"ParticleBook-Update.exe";
            DeleteFileW(exePath.c_str());

            // WinHTTP fetch with up to 8 redirects (GitHub release → objects.githubusercontent.com)
            auto fetchWithWinHttp = [hwnd](const std::wstring& url, const std::wstring& out) -> bool {
                std::wstring cur = url;
                for (int redir = 0; redir < 8; redir++) {
                    size_t se = cur.find(L"://");
                    if (se == std::wstring::npos) {
                        pb::LogWarn("update", "update download: invalid url (no scheme)");
                        return false;
                    }
                    bool https = (cur.substr(0, se) == L"https");
                    size_t hs = se + 3;
                    size_t ps = cur.find(L'/', hs);
                    std::wstring host, path;
                    if (ps != std::wstring::npos) { host = cur.substr(hs, ps - hs); path = cur.substr(ps); }
                    else { host = cur.substr(hs); path = L"/"; }
                    HINTERNET hS = WinHttpOpen(L"ParticleBook/2.0 Updater", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY, nullptr, nullptr, 0);
                    if (!hS) {
                        pb::LogWarn("update", "update download: WinHttpOpen failed");
                        return false;
                    }
                    HINTERNET hC = WinHttpConnect(hS, host.c_str(), https ? 443 : 80, 0);
                    if (!hC) {
                        WinHttpCloseHandle(hS);
                        pb::LogWarn("update", "update download: connect failed (" + pb::WideToUtf8(host) + ")");
                        return false;
                    }
                    HINTERNET hR = WinHttpOpenRequest(hC, L"GET", path.c_str(), nullptr, nullptr, nullptr, https ? WINHTTP_FLAG_SECURE : 0);
                    if (!hR) {
                        WinHttpCloseHandle(hC); WinHttpCloseHandle(hS);
                        pb::LogWarn("update", "update download: open request failed");
                        return false;
                    }
                    BOOL sent = WinHttpSendRequest(hR, nullptr, 0, nullptr, 0, 0, 0) && WinHttpReceiveResponse(hR, nullptr);
                    if (!sent) {
                        WinHttpCloseHandle(hR); WinHttpCloseHandle(hC); WinHttpCloseHandle(hS);
                        pb::LogWarn("update", "update download: network error (" + pb::WideToUtf8(host) + ")");
                        return false;
                    }
                    DWORD sc = 0, sz = sizeof(sc);
                    WinHttpQueryHeaders(hR, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER, nullptr, &sc, &sz, nullptr);
                    if (sc == 301 || sc == 302 || sc == 303 || sc == 307) {
                        WCHAR loc[2048] = {}; DWORD ls = sizeof(loc);
                        if (WinHttpQueryHeaders(hR, WINHTTP_QUERY_LOCATION, nullptr, loc, &ls, nullptr)) {
                            cur = loc;
                            WinHttpCloseHandle(hR); WinHttpCloseHandle(hC); WinHttpCloseHandle(hS);
                            continue;
                        }
                        WinHttpCloseHandle(hR); WinHttpCloseHandle(hC); WinHttpCloseHandle(hS);
                        pb::LogWarn("update", "update download: redirect without Location header");
                        return false;
                    }
                    if (sc >= 400) {
                        WinHttpCloseHandle(hR); WinHttpCloseHandle(hC); WinHttpCloseHandle(hS);
                        pb::LogWarn("update", "update download: HTTP " + std::to_string((unsigned long)sc));
                        return false;
                    }
                    HANDLE hFile = CreateFileW(out.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
                    if (hFile == INVALID_HANDLE_VALUE) {
                        WinHttpCloseHandle(hR); WinHttpCloseHandle(hC); WinHttpCloseHandle(hS);
                        pb::LogWarn("update", "update download: cannot write temp file");
                        return false;
                    }
                    // Report download progress to the UI thread (~1MB steps)
                    DWORD total = 0; DWORD tsz = sizeof(total);
                    WinHttpQueryHeaders(hR, WINHTTP_QUERY_CONTENT_LENGTH | WINHTTP_QUERY_FLAG_NUMBER, nullptr, &total, &tsz, nullptr);
                    char buf[65536]; DWORD br; DWORD received = 0;
                    DWORD nextReport = 1024 * 1024;
                    while (WinHttpReadData(hR, buf, sizeof(buf), &br) && br > 0) {
                        DWORD wr; WriteFile(hFile, buf, br, &wr, nullptr);
                        received += br;
                        if (total > 0 && received >= nextReport) {
                            int percent = (int)((int64_t)received * 100 / total);
                            PostMessage(hwnd, WM_UPDATE_DOWNLOAD_PROGRESS, (WPARAM)percent, 0);
                            nextReport += 1024 * 1024;
                        }
                    }
                    CloseHandle(hFile);
                    WinHttpCloseHandle(hR); WinHttpCloseHandle(hC); WinHttpCloseHandle(hS);
                    return true;
                }
                return false;
            };

            bool ok = fetchWithWinHttp(wUrl, exePath);
            HRESULT hr = ok ? S_OK : E_FAIL;
            if (FAILED(hr)) {
                // Fallback: urlmon (some networks handle it better)
                hr = URLDownloadToFileW(nullptr, wUrl.c_str(), exePath.c_str(), 0, nullptr);
            }
            if (FAILED(hr)) {
                // Error code (localized by the frontend) — not a user-facing string
                pb::LogError("update", "update package download failed (WinHTTP + urlmon fallback)");
                auto* errStr = new std::string("download_failed_network");
                PostMessage(hwnd, WM_UPDATE_DOWNLOAD_DONE, 0, (LPARAM)errStr);
                return;
            }

            // Integrity check: SHA-512 一律校验（入口已拒绝空值 / "dummy" /
            // 长度不对的哈希，所以这里不存在「跳过校验」的分支）。
            {
                std::string actual;
                if (!ComputeFileSha512(exePath, actual) || _stricmp(actual.c_str(), sha512.c_str()) != 0) {
                    DeleteFileW(exePath.c_str());
                    // Error code (localized by the frontend)
                    pb::LogError("update", "update package SHA-512 mismatch; temp file removed");
                    auto* errStr = new std::string("hash_mismatch");
                    PostMessage(hwnd, WM_UPDATE_DOWNLOAD_DONE, 0, (LPARAM)errStr);
                    return;
                }
            }

            // Pass path back to main thread (will be stored for quitAndInstall)
            int pathLen = WideCharToMultiByte(CP_UTF8, 0, exePath.c_str(), -1, nullptr, 0, nullptr, nullptr);
            auto* pathStr = new std::string(pathLen, '\0');
            WideCharToMultiByte(CP_UTF8, 0, exePath.c_str(), -1, &(*pathStr)[0], pathLen, nullptr, nullptr);
            while (!pathStr->empty() && pathStr->back() == '\0') pathStr->pop_back();
            PostMessage(hwnd, WM_UPDATE_DOWNLOAD_DONE, 1, (LPARAM)pathStr);
        }).detach();

        return json("started");
    });

    bridge->RegisterMethod("app:quitAndInstall", [](const json&) -> json {
        wchar_t tmpPath[MAX_PATH];
        GetTempPathW(MAX_PATH, tmpPath);
        std::wstring batPath = std::wstring(tmpPath) + L"ParticleBook-Update.bat";
        std::wstring exePath = std::wstring(tmpPath) + L"ParticleBook-Update.exe";

        DWORD pid = GetCurrentProcessId();
        char pidStr[32];
        snprintf(pidStr, sizeof(pidStr), "%lu", pid);

        // Build batch content: wait for this PID to exit, then install silently & clean up
        std::string bat;
        bat += "@echo off\r\n";
        bat += ":loop\r\n";
        bat += "tasklist /fi \"PID eq " + std::string(pidStr) + "\" 2>nul | find \"" + std::string(pidStr) + "\" >nul\r\n";
        bat += "if not errorlevel 1 (\r\n";
        bat += "    ping -n 2 127.0.0.1 >nul\r\n";
        bat += "    goto loop\r\n";
        bat += ")\r\n";

        // Convert exePath to narrow string for batch
        int nlen = WideCharToMultiByte(CP_ACP, 0, exePath.c_str(), -1, nullptr, 0, nullptr, nullptr);
        std::string exePathNarrow(nlen, '\0');
        WideCharToMultiByte(CP_ACP, 0, exePath.c_str(), -1, &exePathNarrow[0], nlen, nullptr, nullptr);
        while (!exePathNarrow.empty() && exePathNarrow.back() == '\0') exePathNarrow.pop_back();

        bat += "start \"\" /wait \"" + exePathNarrow + "\" /S\r\n";
        bat += "del \"" + exePathNarrow + "\"\r\n";
        bat += "del \"%~f0\"\r\n";

        // Write batch file
        HANDLE hFile = CreateFileW(batPath.c_str(), GENERIC_WRITE, 0, nullptr,
            CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (hFile != INVALID_HANDLE_VALUE) {
            DWORD written;
            WriteFile(hFile, bat.c_str(), (DWORD)bat.size(), &written, nullptr);
            CloseHandle(hFile);

            // Run batch file detached, hidden
            STARTUPINFOW si = { sizeof(si) };
            si.dwFlags = STARTF_USESHOWWINDOW;
            si.wShowWindow = SW_HIDE;
            PROCESS_INFORMATION pi = {};
            std::wstring cmdLine = L"cmd.exe /c \"" + batPath + L"\"";
            CreateProcessW(nullptr, cmdLine.data(), nullptr, nullptr, FALSE,
                CREATE_NO_WINDOW, nullptr, nullptr, &si, &pi);
            if (pi.hProcess) CloseHandle(pi.hProcess);
            if (pi.hThread) CloseHandle(pi.hThread);
        }

        PostQuitMessage(0);
        return json(true);
    });

    bridge->RegisterMethod("bookSource:importFile", [db](const json&) -> json {
        // Open file dialog for JSON book source files
        IFileOpenDialog* pDialog = nullptr;
        HRESULT hr = CoCreateInstance(CLSID_FileOpenDialog, nullptr, CLSCTX_ALL,
                                      IID_PPV_ARGS(&pDialog));
        if (FAILED(hr) || !pDialog) return json(nullptr);

        const bool en = App::Instance().GetLanguage() == "en";
        COMDLG_FILTERSPEC filters[] = {
            { en ? L"Legado source file (*.json)" : L"Legado 书源文件 (*.json)", L"*.json" },
            { en ? L"All files (*.*)" : L"所有文件 (*.*)", L"*.*" }
        };
        pDialog->SetFileTypes(2, filters);
        pDialog->SetTitle(en ? L"Import Legado source file" : L"导入 Legado 书源文件");

        hr = pDialog->Show(nullptr);
        if (FAILED(hr)) { pDialog->Release(); return json(nullptr); }

        IShellItem* pItem = nullptr;
        hr = pDialog->GetResult(&pItem);
        pDialog->Release();
        if (FAILED(hr) || !pItem) return json(nullptr);

        LPWSTR pwPath = nullptr;
        pItem->GetDisplayName(SIGDN_FILESYSPATH, &pwPath);
        pItem->Release();
        if (!pwPath) return json(nullptr);

        int len = WideCharToMultiByte(CP_UTF8, 0, pwPath, -1, nullptr, 0, nullptr, nullptr);
        std::string filePath(len, '\0');
        WideCharToMultiByte(CP_UTF8, 0, pwPath, -1, &filePath[0], len, nullptr, nullptr);
        while (!filePath.empty() && filePath.back() == '\0') filePath.pop_back();
        CoTaskMemFree(pwPath);

        // Read and parse JSON
        std::ifstream f(filePath, std::ios::binary);
        if (!f.is_open()) return json(nullptr);

        std::string content((std::istreambuf_iterator<char>(f)),
                            std::istreambuf_iterator<char>());
        f.close();

        try {
            auto j = json::parse(content);
            int imported = 0;

            // Legado format: array of source objects
            if (j.is_array()) {
                for (auto& src : j) {
                    json entry;
                    std::string name = src.value("bookSourceName", "");
                    std::string url = src.value("bookSourceUrl", "");
                    if (name.empty() && url.empty()) continue;

                    entry["bookSourceName"] = name;
                    entry["bookSourceUrl"] = url;
                    entry["enabled"] = true;

                    // Copy relevant fields
                    if (src.contains("bookSourceGroup")) entry["bookSourceGroup"] = src["bookSourceGroup"];
                    if (src.contains("bookSourceType")) entry["bookSourceType"] = src["bookSourceType"];
                    if (src.contains("searchUrl")) entry["searchUrl"] = src["searchUrl"];
                    if (src.contains("ruleSearch")) entry["ruleSearch"] = src["ruleSearch"];
                    if (src.contains("ruleBookInfo")) entry["ruleBookInfo"] = src["ruleBookInfo"];
                    if (src.contains("ruleToc")) entry["ruleToc"] = src["ruleToc"];
                    if (src.contains("ruleContent")) entry["ruleContent"] = src["ruleContent"];
                    if (src.contains("httpUserAgent")) entry["httpUserAgent"] = src["httpUserAgent"];

                    db->InsertBookSource(entry);
                    imported++;
                }
            }

            json result;
            result["imported"] = imported;
            return result;
        } catch (...) {
            return json(nullptr);
        }
    });

    // ── Drag & drop import ──────────────────────────────────────────
    // WebView2 File objects carry no filesystem path, so the renderer reads the
    // file bytes and hands them over as base64. We persist to a user-data folder
    // and return the path; the frontend then runs the normal import flow.
    bridge->RegisterAsyncMethod("book:writeDroppedFile",
        [jobs](const json& p, std::function<void(json)> respond, std::function<void(std::string)> reject) {
            const std::string name = p.value("name", "");
            const std::string b64 = p.value("data", "");
            if (name.empty() || b64.empty()) { reject("invalid_params"); return; }
            if (!jobs) { reject("job_service_unavailable"); return; }
            // 500MiB 的 base64 上限：拖一本超大书进来时在 UI 线程之外直接拒绝，
            // 不要先解码出一份 600MB+ 的缓冲再失败（review · B-11）。
            if (b64.size() > 700ull * 1024ull * 1024ull) { reject("file_too_large"); return; }
            auto out = std::make_shared<json>(nullptr);
            const pb::JobId jobId = jobs->Submit("book:writeDroppedFile",
                [name, b64, out](pb::JobContext&) { *out = WriteDroppedFile(name, b64); },
                nullptr,
                [out, respond, reject](const pb::JobResult& r) {
                    if (!r.ok) { reject(r.cancelled ? "cancelled" : r.error); return; }
                    respond(*out);
                });
            if (jobId == pb::kInvalidJobId) reject("job_service_unavailable");
        });
}
