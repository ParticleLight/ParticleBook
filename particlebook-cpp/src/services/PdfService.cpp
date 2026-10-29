#include "PdfService.h"
#include "utils/encoding.h"
#include "utils/fnv1a.h"
#include "utils/mobi_patch.h"
#include "BridgeServer.h"
#include "utils/win_cmd.h"
#include "platform/JobService.h"
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <atomic>
#include <algorithm>
#include <cstdio>
#include <cstring>
#include <sstream>
#include <fstream>
#include <filesystem>
#include <ctime>
#include <vector>

// ── Helpers ─────────────────────────────────────────────────────────

// ── Constructor / Destructor ─────────────────────────────────────────

PdfService::PdfService() : m_nextId(static_cast<int>(GetTickCount() & 0x7FFFFFFF))
{
    // Randomize the per-session document-id base so rendered PNG files
    // (doc_<id>_page_<n>.png) never collide with a previous run's cache.
}
PdfService::~PdfService()
{
    // Clean up temp dirs
    for (auto& doc : m_docs) {
        std::error_code ec;
        std::string tmpDir = doc.filePath + ".mutool_tmp";
        std::filesystem::remove_all(pb::Utf8ToWide(tmpDir), ec);
    }
    // Clean up patched MOBI temp files
    for (auto& tf : m_tempFiles) {
        DeleteFileW(pb::Utf8ToWide(tf).c_str());
    }
}

// ── Get mutool path ──────────────────────────────────────────────────

std::string PdfService::GetMutoolPath()
{
    wchar_t exePath[MAX_PATH];
    GetModuleFileNameW(nullptr, exePath, MAX_PATH);
    auto p = std::filesystem::path(exePath).parent_path() / "mutool.exe";
    return p.string();
}

// ── MOBI encoding patch ────────────────────────────────────────────────
// MuPDF's mobi.c defaults text_encoding to LATIN_1 (0) when the MOBI header
// is missing or incomplete. Most Chinese MOBI files are UTF-8. Patch the
// encoding field so mutool renders CJK text correctly.

static std::string PatchMobiEncoding(const std::string& filePath)
{
    // Extension check + byte-level patch both live in utils/mobi_patch.h so
    // they can be unit-tested; this function keeps only the filesystem side.
    const std::string ext = pb::MobiExtLower(filePath);
    if (!pb::IsMobiFamilyExt(ext)) return filePath;

    // Read entire file
    HANDLE hFile = CreateFileW(pb::Utf8ToWide(filePath).c_str(), GENERIC_READ,
                                FILE_SHARE_READ, nullptr, OPEN_EXISTING,
                                FILE_ATTRIBUTE_NORMAL, nullptr);
    if (hFile == INVALID_HANDLE_VALUE) return filePath;

    LARGE_INTEGER liSize;
    GetFileSizeEx(hFile, &liSize);
    size_t size = (size_t)liSize.QuadPart;
    if (size == 0 || size > 100 * 1024 * 1024) { CloseHandle(hFile); return filePath; }

    std::vector<uint8_t> data(size);
    DWORD bytesRead = 0;
    ReadFile(hFile, data.data(), (DWORD)size, &bytesRead, nullptr);
    CloseHandle(hFile);
    if (bytesRead != size) return filePath;

    // Byte-level scan + patch (unit-tested in tests/unit/mobi_patch_test.cpp).
    if (!pb::PatchMobiEncodingBuffer(data)) return filePath;

    // Write patched copy
    wchar_t tmpPath[MAX_PATH], tmpFile[MAX_PATH];
    GetTempPathW(MAX_PATH, tmpPath);
    GetTempFileNameW(tmpPath, L"mbp", 0, tmpFile);
    std::string patchedPath = pb::WideToUtf8(tmpFile);

    // Rename to keep original extension so mutool detects format
    DeleteFileW(tmpFile);
    // Unique temp name per patch: a per-process counter prevents two MOBIs
    // patched in the same session from overwriting each other's file
    // (previously a fixed pb_mobi_<pid><ext> collided → wrong book rendered).
    static std::atomic<int> g_mobiSeq{0};
    std::wstring tmpDir = tmpPath;
    std::wstring fixedName = L"pb_mobi_" + std::to_wstring(GetCurrentProcessId())
                           + L"_" + std::to_wstring(g_mobiSeq++) + pb::Utf8ToWide(ext);
    patchedPath = pb::WideToUtf8((tmpDir + fixedName).c_str());

    HANDLE hOut = CreateFileW((tmpDir + fixedName).c_str(), GENERIC_WRITE, 0, nullptr,
                               CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (hOut == INVALID_HANDLE_VALUE) return filePath;

    DWORD written = 0;
    WriteFile(hOut, data.data(), (DWORD)size, &written, nullptr);
    CloseHandle(hOut);
    if (written != size) { DeleteFileW((tmpDir + fixedName).c_str()); return filePath; }

    return patchedPath;
}

// ── Run mutool subprocess ────────────────────────────────────────────

bool PdfService::RunMutool(const std::string& args, std::string& output, int timeoutMs)
{
    // Create temp file for output
    wchar_t tmpPath[MAX_PATH];
    GetTempPathW(MAX_PATH, tmpPath);
    wchar_t tmpFile[MAX_PATH];
    GetTempFileNameW(tmpPath, L"mup", 0, tmpFile);

    // Open temp file handle for child process stdout
    SECURITY_ATTRIBUTES sa = { sizeof(SECURITY_ATTRIBUTES), nullptr, TRUE };
    HANDLE hOutFile = CreateFileW(tmpFile, GENERIC_WRITE, FILE_SHARE_READ, &sa,
                                  CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (hOutFile == INVALID_HANDLE_VALUE) return false;

    PROCESS_INFORMATION pi = {};
    STARTUPINFOW si = { sizeof(STARTUPINFOW) };
    si.dwFlags = STARTF_USESTDHANDLES;
    si.hStdOutput = hOutFile;
    si.hStdError = hOutFile;

    // Correctly quote the mutool path (may contain spaces / special chars).
    std::wstring mutoolArg = pb::QuoteCmdArg(pb::Utf8ToWide(GetMutoolPath()));
    std::string cmdLine = pb::WideToUtf8(mutoolArg.c_str()) + " " + args;
    std::wstring wCmdLine = pb::Utf8ToWide(cmdLine);

    BOOL ok = CreateProcessW(nullptr, wCmdLine.data(), nullptr, nullptr, TRUE,
                             CREATE_NO_WINDOW | NORMAL_PRIORITY_CLASS,
                             nullptr, nullptr, &si, &pi);
    CloseHandle(hOutFile);

    if (!ok) {
        DeleteFileW(tmpFile);
        return false;
    }

    if (WaitForSingleObject(pi.hProcess, timeoutMs) == WAIT_TIMEOUT) {
        TerminateProcess(pi.hProcess, 1);
        WaitForSingleObject(pi.hProcess, INFINITE);
    }
    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);

    // Read from temp file
    HANDLE hReadFile = CreateFileW(tmpFile, GENERIC_READ, FILE_SHARE_READ, nullptr,
                                   OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (hReadFile != INVALID_HANDLE_VALUE) {
        DWORD size = GetFileSize(hReadFile, nullptr);
        if (size > 0 && size < 10 * 1024 * 1024) {
            std::vector<char> buf(size + 1);
            DWORD read = 0;
            ReadFile(hReadFile, buf.data(), size, &read, nullptr);
            buf[read] = '\0';
            output = buf.data();
        }
        CloseHandle(hReadFile);
    }

    DeleteFileW(tmpFile);
    return !output.empty();
}

// ── Open PDF ─────────────────────────────────────────────────────────

PdfOpenResult PdfService::Open(const std::string& filePath)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    PdfOpenResult result = {};
    result.id = m_nextId++;

    // Patch MOBI encoding before mutool (MuPDF defaults to Latin-1)
    std::string actualPath = PatchMobiEncoding(filePath);
    if (actualPath != filePath) m_tempFiles.push_back(actualPath);

    // Debug log
    wchar_t exePath[MAX_PATH];
    GetModuleFileNameW(nullptr, exePath, MAX_PATH);
    auto logPath = std::filesystem::path(exePath).parent_path() / "debug.log";
    auto logMsg = [&](const char* msg) {
        FILE* lf = _wfopen(logPath.c_str(), L"a");
        if (lf) {
            time_t now = time(nullptr);
            char timeBuf[32];
            tm localTm; localtime_s(&localTm, &now);
            strftime(timeBuf, sizeof(timeBuf), "%H:%M:%S", &localTm);
            fprintf(lf, "[%s] PdfService::Open: %s\n", timeBuf, msg);
            fclose(lf);
        }
    };

    logMsg(("opening " + actualPath).c_str());

    // Check file exists
    if (GetFileAttributesW(pb::Utf8ToWide(actualPath).c_str()) == INVALID_FILE_ATTRIBUTES) {
        logMsg("file not found");
        return result;
    }

    // Get page info with mutool pages
    std::string output;
    std::string args = "pages " + pb::WideToUtf8(pb::QuoteCmdArg(pb::Utf8ToWide(actualPath)).c_str());
    if (!RunMutool(args, output)) {
        logMsg("mutool failed to run");
        return result;
    }
    logMsg(("mutool output: " + output).c_str());

    // Parse mutool pages XML output
    // Format: <page pagenum="0"><MediaBox l="0" b="0" r="595" t="842"/></page>
    std::vector<PdfPageBounds> bounds;
    size_t pos = 0;
    while (true) {
        size_t pageStart = output.find("<page", pos);
        if (pageStart == std::string::npos) break;
        size_t pageEnd = output.find("</page>", pageStart);
        if (pageEnd == std::string::npos) break;
        std::string pageXml = output.substr(pageStart, pageEnd + 7 - pageStart);

        // Extract MediaBox attributes: r="..." t="..."
        double w = 0, h = 0;
        auto extractAttr = [&](const char* attr) -> double {
            std::string search = std::string(attr) + "=\"";
            size_t p = pageXml.find(search);
            if (p == std::string::npos) return 0;
            p += search.length();
            size_t e = pageXml.find('"', p);
            if (e == std::string::npos) return 0;
            try { return std::stod(pageXml.substr(p, e - p)); }
            catch (...) { return 0; }
        };
        w = extractAttr("r");
        h = extractAttr("t");
        if (w > 0 && h > 0) bounds.push_back({w, h});

        pos = pageEnd + 7;
    }

    result.pageCount = static_cast<uint32_t>(bounds.size());
    result.pageBounds = bounds;

    if (result.pageCount > 0) {
        DocEntry entry;
        entry.id = result.id;
        entry.filePath = actualPath;
        // 版本号：修改时间 ^ 大小；渲染缓存名与 URL 都带上它。
        {
            ULARGE_INTEGER stamp{};
            WIN32_FILE_ATTRIBUTE_DATA attrs{};
            if (GetFileAttributesExW(pb::Utf8ToWide(actualPath).c_str(), GetFileExInfoStandard, &attrs)) {
                stamp.LowPart = attrs.ftLastWriteTime.dwLowDateTime;
                stamp.HighPart = attrs.ftLastWriteTime.dwHighDateTime;
                stamp.QuadPart ^= static_cast<unsigned long long>(attrs.nFileSizeLow)
                    | (static_cast<unsigned long long>(attrs.nFileSizeHigh) << 32);
            }
            entry.version = std::to_string(stamp.QuadPart);
        }
        entry.pageCount = result.pageCount;
        entry.pageBounds = bounds;
        m_docs.push_back(std::move(entry));
    }

    return result;
}

// ── Render Page ──────────────────────────────────────────────────────

std::string PdfService::RenderPage(int id, uint32_t pageIndex, int pixelWidth, int pixelHeight)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    DocEntry* entry = nullptr;
    for (auto& e : m_docs) {
        if (e.id == id) { entry = &e; break; }
    }
    if (!entry || pageIndex >= entry->pageCount) return "";

    // Get renderer directory (mapped to particlebook.app virtual host)
    wchar_t exePathBuf[MAX_PATH];
    GetModuleFileNameW(nullptr, exePathBuf, MAX_PATH);
    auto rendererDir = (std::filesystem::path(exePathBuf).parent_path() / "renderer" / "_pb_pdf").string();
    std::filesystem::create_directories(pb::Utf8ToWide(rendererDir));

    const std::string version = entry->version.empty() ? "0" : entry->version;
    std::string fileName = "doc_" + std::to_string(id) + "_v" + version + "_page_"
        + std::to_string(pageIndex) + ".png";
    std::string outFile = rendererDir + "\\" + fileName;
    std::string url = "http://particlebook.app/_pb_pdf/" + fileName;

    // Skip if already rendered
    if (GetFileAttributesW(pb::Utf8ToWide(outFile).c_str()) != INVALID_FILE_ATTRIBUTES) {
        return url;
    }

    double pageW = entry->pageBounds[pageIndex].width;
    double pageH = entry->pageBounds[pageIndex].height;

    int dpi = 150;
    if (pixelWidth > 0 && pageW > 0) {
        dpi = static_cast<int>((pixelWidth / pageW) * 72.0);
    }
    if (dpi < 72) dpi = 72;
    if (dpi > 300) dpi = 300;

    std::string args = "draw -o " + pb::WideToUtf8(pb::QuoteCmdArg(pb::Utf8ToWide(outFile)).c_str())
                     + " -r " + std::to_string(dpi)
                     + " -F png " + pb::WideToUtf8(pb::QuoteCmdArg(pb::Utf8ToWide(entry->filePath)).c_str())
                     + " " + std::to_string(pageIndex + 1);

    std::string output;
    RunMutool(args, output);

    // Verify file was created
    if (GetFileAttributesW(pb::Utf8ToWide(outFile).c_str()) == INVALID_FILE_ATTRIBUTES) {
        return "";
    }

    // Track for cleanup
    entry->tempFiles.push_back(outFile);

    return url;
}

// ── Get File URL for native PDF viewer ─────────────────────────────────



// ── Extract Text ──────────────────────────────────────────────────────

std::string PdfService::ExtractText(int id)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    DocEntry* entry = nullptr;
    for (auto& e : m_docs) {
        if (e.id == id) { entry = &e; break; }
    }
    if (!entry) return "";

    std::string output;
    // Use draw -F text which outputs plain text with form-feed page breaks
    std::string args = "draw -F text -o - " + pb::WideToUtf8(pb::QuoteCmdArg(pb::Utf8ToWide(entry->filePath)).c_str());
    if (!RunMutool(args, output, 60000)) return "";

    // Split by form feed to get text per page
    json result;
    result["pages"] = json::array();

    size_t start = 0;
    int pageNum = 0;
    while (start < output.size()) {
        size_t end = output.find('\f', start);
        std::string pageText;
        if (end == std::string::npos) {
            pageText = output.substr(start);
        } else {
            pageText = output.substr(start, end - start);
        }

        // Clean up text: remove excessive whitespace but preserve structure
        if (!pageText.empty()) {
            json page;
            page["pageNum"] = pageNum;
            page["text"] = pageText;
            result["pages"].push_back(page);
        }
        pageNum++;

        if (end == std::string::npos) break;
        start = end + 1;
    }

    return result.dump();
}

static std::string DecimalToUtf8(unsigned int cp)
{
    std::string out;
    if (cp <= 0x7F) out.push_back(static_cast<char>(cp));
    else if (cp <= 0x7FF) {
        out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp <= 0xFFFF) {
        out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
    return out;
}

static std::string DecodeXmlText(const std::string& text)
{
    std::string out;
    for (std::size_t i = 0; i < text.size();) {
        if (text[i] == '&') {
            const std::size_t semi = text.find(';', i);
            if (semi != std::string::npos) {
                const std::string entity = text.substr(i + 1, semi - i - 1);
                if (!entity.empty() && entity[0] == '#') {
                    const bool hex = entity.size() > 1 && (entity[1] == 'x' || entity[1] == 'X');
                    unsigned int cp = 0;
                    try {
                        cp = hex ? std::stoul(entity.substr(2), nullptr, 16)
                                 : std::stoul(entity.substr(1), nullptr, 10);
                    } catch (...) { cp = 0; }
                    if (cp) out += DecimalToUtf8(cp);
                } else if (entity == "amp") out += '&';
                else if (entity == "lt") out += '<';
                else if (entity == "gt") out += '>';
                else if (entity == "quot") out += '"';
                else if (entity == "apos") out += '\'';
                i = semi + 1;
                continue;
            }
        }
        out.push_back(text[i++]);
    }
    return out;
}

static double AttrDouble(const std::string& tag, const char* name)
{
    const std::string needle = std::string(name) + "=\"";
    const std::size_t p = tag.find(needle);
    if (p == std::string::npos) return 0.0;
    const std::size_t start = p + needle.size();
    const std::size_t end = tag.find('"', start);
    if (end == std::string::npos) return 0.0;
    try { return std::stod(tag.substr(start, end - start)); } catch (...) { return 0.0; }
}

static std::string AttrString(const std::string& tag, const char* name)
{
    const std::string needle = std::string(name) + "=\"";
    const std::size_t p = tag.find(needle);
    if (p == std::string::npos) return "";
    const std::size_t start = p + needle.size();
    const std::size_t end = tag.find('"', start);
    if (end == std::string::npos) return "";
    return tag.substr(start, end - start);
}

std::string PdfService::ExtractTextLayer(int id, uint32_t pageIndex)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    DocEntry* entry = nullptr;
    for (auto& e : m_docs) {
        if (e.id == id) { entry = &e; break; }
    }
    if (!entry || pageIndex >= entry->pageCount) return "";

    std::string output;
    const std::string args = "draw -F stext -o - "
        + pb::WideToUtf8(pb::QuoteCmdArg(pb::Utf8ToWide(entry->filePath)).c_str())
        + " " + std::to_string(pageIndex + 1);
    if (!RunMutool(args, output, 30000) || output.empty()) return "";

    json result;
    result["page"] = pageIndex;
    double pageW = 0.0, pageH = 0.0;
    const std::size_t pageTag = output.find("<page");
    if (pageTag != std::string::npos) {
        const std::size_t end = output.find('>', pageTag);
        const std::string tag = output.substr(pageTag, end == std::string::npos ? std::string::npos : end - pageTag + 1);
        pageW = AttrDouble(tag, "width");
        pageH = AttrDouble(tag, "height");
    }
    if (pageW <= 0.0 && pageIndex < entry->pageBounds.size()) pageW = entry->pageBounds[pageIndex].width;
    if (pageH <= 0.0 && pageIndex < entry->pageBounds.size()) pageH = entry->pageBounds[pageIndex].height;
    result["width"] = pageW;
    result["height"] = pageH;
    result["chars"] = json::array();

    std::size_t pos = 0;
    int count = 0;
    while (count < 20000) {
        const std::size_t start = output.find("<char", pos);
        if (start == std::string::npos) break;
        const std::size_t end = output.find("/>", start);
        if (end == std::string::npos) break;
        const std::string tag = output.substr(start, end - start + 2);
        pos = end + 2;
        const std::string quad = AttrString(tag, "quad");
        if (quad.empty()) continue;
        double q[8] = {};
        std::istringstream qs(quad);
        bool ok = true;
        for (double& v : q) { if (!(qs >> v)) { ok = false; break; } }
        if (!ok) continue;
        const double x = q[0], y = q[1];
        const double w = q[2] - q[0];
        const double h = q[5] - q[1];
        if (w <= 0.0 || h <= 0.0) continue;
        const std::string raw = AttrString(tag, "c");
        if (raw.empty()) continue;
        result["chars"].push_back({
            {"x", x}, {"y", y}, {"w", w}, {"h", h}, {"c", DecodeXmlText(raw)},
        });
        count += 1;
    }
    return result.dump();
}

// ── Close PDF ────────────────────────────────────────────────────────

void PdfService::Close(int id)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    for (auto it = m_docs.begin(); it != m_docs.end(); ++it) {
        if (it->id == id) {
            // Clean up temp PNG files
            for (auto& f : it->tempFiles) {
                DeleteFileW(pb::Utf8ToWide(f).c_str());
            }
            // Clean up the MOBI patch temp file if this doc was patched
            // (PatchMobiEncoding wrote pb_mobi_<pid>_<seq><ext>).
            std::wstring fname = std::filesystem::path(pb::Utf8ToWide(it->filePath)).filename().wstring();
            if (fname.rfind(L"pb_mobi_", 0) == 0) {
                DeleteFileW(pb::Utf8ToWide(it->filePath).c_str());
                m_tempFiles.erase(std::remove(m_tempFiles.begin(), m_tempFiles.end(), it->filePath),
                                  m_tempFiles.end());
            }
            // Clean up legacy mutool_tmp dir
            std::error_code ec;
            std::string tmpDir = it->filePath + ".mutool_tmp";
            std::filesystem::remove_all(pb::Utf8ToWide(tmpDir), ec);
            m_docs.erase(it);
            return;
        }
    }
}

// ── Handlers ─────────────────────────────────────────────────────────

void RegisterPdfHandlers(BridgeServer* bridge, PdfService* pdf, pb::JobService* jobs)
{
    bridge->RegisterAsyncMethod("pdf:open", [pdf, jobs](const json& p,
            std::function<void(json)> respond,
            std::function<void(std::string)> reject) {
        const std::string filePath = p.value("filePath", "");
        if (filePath.empty() || !jobs) { reject("invalid_params"); return; }

        auto result = std::make_shared<json>(nullptr);
        const pb::JobId id = jobs->Submit(
            "pdf:open",
            [pdf, filePath, result](pb::JobContext& job) {
                auto opened = pdf->Open(filePath);
                if (job.IsCancelled()) return;
                if (opened.pageCount == 0) { *result = nullptr; return; }
                json j;
                j["id"] = opened.id;
                j["pageCount"] = opened.pageCount;
                j["pageBounds"] = json::array();
                for (auto& b : opened.pageBounds) {
                    json jb;
                    jb["width"] = b.width;
                    jb["height"] = b.height;
                    j["pageBounds"].push_back(jb);
                }
                *result = std::move(j);
            },
            nullptr,
            [result, respond, reject](const pb::JobResult& r) {
                if (!r.ok) { reject(r.cancelled ? "cancelled" : r.error); return; }
                respond(*result);
            });
        if (id == pb::kInvalidJobId) reject("job_service_unavailable");
    });



    bridge->RegisterAsyncMethod("pdf:renderPage",
        [pdf, jobs](const json& p,
                    std::function<void(json)> respond,
                    std::function<void(std::string)> reject) {
            const int id = p.value("id", 0);
            const uint32_t pageNum = p.value("pageNum", 0u);
            const int w = p.value("width", 0);
            const int h = p.value("height", 0);
            if (id <= 0 || !jobs) { reject("invalid_params"); return; }
            auto out = std::make_shared<std::string>();
            const pb::JobId jid = jobs->Submit(
                "pdf:renderPage",
                [pdf, id, pageNum, w, h, out](pb::JobContext&) {
                    *out = pdf->RenderPage(id, pageNum, w, h);
                },
                nullptr,
                [out, respond, reject](const pb::JobResult& r) {
                    if (!r.ok) { reject(r.cancelled ? "cancelled" : r.error); return; }
                    if (out->empty()) { respond(json(nullptr)); return; }
                    respond(json(*out));
                });
            if (jid == pb::kInvalidJobId) reject("job_service_unavailable");
        });

    bridge->RegisterAsyncMethod("pdf:extractText",
        [pdf, jobs](const json& p,
                    std::function<void(json)> respond,
                    std::function<void(std::string)> reject) {
            const int id = p.value("id", 0);
            if (id <= 0 || !jobs) { reject("invalid_params"); return; }
            auto out = std::make_shared<std::string>();
            const pb::JobId jid = jobs->Submit(
                "pdf:extractText",
                [pdf, id, out](pb::JobContext&) { *out = pdf->ExtractText(id); },
                nullptr,
                [out, respond, reject](const pb::JobResult& r) {
                    if (!r.ok) { reject(r.cancelled ? "cancelled" : r.error); return; }
                    if (out->empty()) { respond(json(nullptr)); return; }
                    try { respond(json::parse(*out)); }
                    catch (...) { reject("parse_error"); }
                });
            if (jid == pb::kInvalidJobId) reject("job_service_unavailable");
        });

    bridge->RegisterAsyncMethod("pdf:textLayer",
        [pdf, jobs](const json& p,
                    std::function<void(json)> respond,
                    std::function<void(std::string)> reject) {
            const int id = p.value("id", 0);
            const uint32_t pageNum = p.value("pageNum", 0u);
            if (id <= 0 || !jobs) { reject("invalid_params"); return; }
            auto out = std::make_shared<std::string>();
            const pb::JobId jid = jobs->Submit(
                "pdf:textLayer",
                [pdf, id, pageNum, out](pb::JobContext&) {
                    *out = pdf->ExtractTextLayer(id, pageNum);
                },
                nullptr,
                [out, respond, reject](const pb::JobResult& r) {
                    if (!r.ok) { reject(r.cancelled ? "cancelled" : r.error); return; }
                    if (out->empty()) { respond(json(nullptr)); return; }
                    try { respond(json::parse(*out)); }
                    catch (...) { reject("parse_error"); }
                });
            if (jid == pb::kInvalidJobId) reject("job_service_unavailable");
        });

    bridge->RegisterMethod("pdf:close", [pdf](const json& p) -> json {
        pdf->Close(p["id"].get<int>());
        return json(nullptr);
    });
}
