// PB-024-B：从 FileHandlers.cpp 纯搬移的「书库导入」实现（行为不变）。
#include "FileImportService.h"
#include "FileFormatService.h"
#include "BridgeServer.h"
#include "services/DatabaseService.h"
#include "services/LibraryService.h"
#include "platform/JobService.h"
#include "platform/LogService.h"
#include "utils/encoding.h"
#include "utils/fnv1a.h"

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <filesystem>
#include <string>

namespace fileimport {

bool WriteUtf8File(const std::string& path, const std::string& bytes)
{
    const std::wstring wide = pb::Utf8ToWide(path);
    if (wide.empty()) return false;
    HANDLE handle = CreateFileW(wide.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS,
                                FILE_ATTRIBUTE_NORMAL, nullptr);
    if (handle == INVALID_HANDLE_VALUE) return false;
    DWORD written = 0;
    const bool ok = WriteFile(handle, bytes.data(), static_cast<DWORD>(bytes.size()),
                              &written, nullptr) != FALSE && written == bytes.size();
    CloseHandle(handle);
    return ok;
}

std::string ParentDirOf(const std::string& path)
{
    const std::size_t slash = path.find_last_of("\\/");
    return slash == std::string::npos ? std::string(".") : path.substr(0, slash);
}

json ImportBookPaths(DatabaseService* db, const json& paths, pb::JobContext& job,
                        BridgeServer* bridge)
{
    json result = json::array();

    // PB-017-B：批量导入进度 / 逐条错误汇总。
    //   进度：每个文件开始处理时 job.Progress()（JobService 的节流/UI 回投）+
    //         book:importProgress 事件（渲染层进度条用）；
    //   汇总：致命跳过（格式不支持 / 文件读不了 / DOCX 转换失败 / AZW3 无文本 /
    //         异常）按 "文件名: 原因" 记入 errors；非致命 warning 只写日志。
    json errors = json::array();
    const auto emitProgress = [bridge, &job, &errors](long long current, long long total,
                                                      const std::string& message, bool done) {
        if (job.IsCancelled() && !done) return;
        const double percent = total > 0
            ? 100.0 * static_cast<double>(current) / static_cast<double>(total)
            : 0.0;
        job.Progress(percent, message);
        if (bridge) {
            bridge->EmitEvent("book:importProgress", json{
                {"current", current},
                {"total", total},
                {"message", message},
                {"done", done},
                {"errors", errors},
            });
        }
    };
    const auto recordError = [&errors](const std::string& path, const std::string& reason) {
        errors.push_back(filefmt::GetFileName(path) + ": " + reason);
    };

    char debugBuf[1024];
    snprintf(debugBuf, sizeof(debugBuf),
        "book:import called, paths is_array=%d, size=%zu",
        paths.is_array() ? 1 : 0, paths.is_array() ? paths.size() : 0);
    filefmt::DebugLog(debugBuf);

    if (!paths.is_array()) {
        pb::LogWarn("import", "book:import: paths is not an array");
        return result;
    }
    const size_t requested = paths.size();

    for (size_t index = 0; index < requested; ++index) {
        if (job.IsCancelled()) break;
        std::string path;   // 提到 try 外：catch 里要记录是哪个文件失败
        try {
            path = paths[index].get<std::string>();
        } catch (const std::exception&) {
            recordError("?", "invalid path");
            emitProgress(static_cast<long long>(index + 1),
                         static_cast<long long>(requested), "invalid path", false);
            continue;
        }
        emitProgress(static_cast<long long>(index + 1),
                     static_cast<long long>(requested), filefmt::GetFileName(path), false);
        try {
            snprintf(debugBuf, sizeof(debugBuf), "book:import processing: %s", path.c_str());
            filefmt::DebugLog(debugBuf);

            // Check for existing
            json existing = db->GetBookByPath(path);
            if (!existing.is_null()) {
                filefmt::DebugLog("book:import: already exists");
                result.push_back(existing);
                continue;
            }

            std::string format = filefmt::DetectFormat(path);
            snprintf(debugBuf, sizeof(debugBuf), "book:import: format=%s", format.c_str());
            filefmt::DebugLog(debugBuf);
            if (format.empty()) {
                filefmt::DebugLog("book:import: unknown format, skip");
                pb::LogWarn("import", "book:import: unknown format, skipped: " + filefmt::GetFileName(path));
                recordError(path, "unsupported format");
                continue;
            }

            // PB-008-A：DOCX 会转成 HTML，book.file_path 指向转换产物，
            // original_path 保留原 docx；其它格式两者相同。
            std::string storedFormat = format;
            std::string storedPath = path;
            std::string originalPath;

            int64_t fileSize = filefmt::GetFileSizeSafe(path);
            snprintf(debugBuf, sizeof(debugBuf), "book:import: fileSize=%lld", (long long)fileSize);
            filefmt::DebugLog(debugBuf);
            if (fileSize <= 0) {
                filefmt::DebugLog("book:import: file_size failed");
                pb::LogWarn("import", "book:import: cannot read file size (" +
                                          std::to_string((long long)fileSize) + "): " + filefmt::GetFileName(path));
                recordError(path, "cannot read file");
                continue;
            }

            // Extract rich metadata (may crash on corrupt files)
            filefmt::DebugLog("book:import: extracting metadata");
            LibraryService lib;
            ExtractedMetadata em;
            if (format == "epub") {
                filefmt::DebugLog("book:import: calling ExtractEpubMetadata");
                if (!lib.ExtractEpubMetadata(path, em)) {
                    pb::LogWarn("import", "book:import: EPUB metadata extraction failed: " + filefmt::GetFileName(path));
                }
                snprintf(debugBuf, sizeof(debugBuf),
                    "book:import: epub metadata done, title=%s coverFile=%s",
                    em.title.c_str(), em.coverFile.c_str());
                filefmt::DebugLog(debugBuf);
                if (!em.coverFile.empty()) {
                    filefmt::DebugLog("book:import: extracting cover");
                    auto coverPath = lib.ExtractEpubCover(path, em.coverFile);
                    if (!coverPath.empty()) em.coverPath = coverPath;
                    filefmt::DebugLog("book:import: cover done");
                } else {
                    filefmt::DebugLog("book:import: NO COVER FOUND in OPF");
                }
            } else if (format == "pdf") {
                if (!lib.ExtractPdfMetadata(path, em)) {
                    pb::LogWarn("import", "book:import: PDF metadata extraction failed: " + filefmt::GetFileName(path));
                }
                filefmt::DebugLog("book:import: rendering PDF cover");
                em.coverPath = lib.ExtractPdfCover(path);
                if (em.coverPath.empty()) {
                    pb::LogDebug("import", "book:import: PDF cover unavailable: " + filefmt::GetFileName(path));
                }
                filefmt::DebugLog("book:import: PDF cover done");
            } else if (format == "cbz" || format == "cbr") {
                filefmt::DebugLog("book:import: rendering CBZ/CBR cover");
                em.coverPath = lib.ExtractPdfCover(path);
                filefmt::DebugLog("book:import: CBZ/CBR cover done");
            } else if (format == "fb2") {
                if (!lib.ExtractFb2Metadata(path, em)) {
                    pb::LogWarn("import", "book:import: FB2 metadata extraction failed: " + filefmt::GetFileName(path));
                }
            } else if (format == "docx") {
                // PB-008-A：OOXML → 自包含 HTML（图片内联 data URL），
                // 落盘到 <数据目录>/docx/<fnv1a>.html；原 docx 路径记在 original_path。
                DocxConversion converted = lib.ConvertDocxToHtml(path);
                if (!converted.ok) {
                    pb::LogWarn("import", "book:import: DOCX conversion failed (" + converted.error +
                                              "): " + filefmt::GetFileName(path));
                    recordError(path, "docx conversion failed");
                    continue;
                }
                const std::string docxDir = ParentDirOf(db->Path()) + "/docx";
                CreateDirectoryW(pb::Utf8ToWide(docxDir).c_str(), nullptr);
                const std::string hash = pb::Fnv1a64Hex(path);
                const std::string htmlPath = docxDir + "/" + hash + ".html";
                if (!WriteUtf8File(htmlPath, converted.html)) {
                    pb::LogWarn("import", "book:import: cannot write converted DOCX html: " + htmlPath);
                    continue;
                }
                storedPath = htmlPath;
                storedFormat = "docx";
                originalPath = path;
                if (!converted.title.empty()) em.title = converted.title;
                if (!converted.author.empty()) em.author = converted.author;
                if (!converted.language.empty()) em.language = converted.language;
                if (!converted.coverData.empty()) {
                    const std::string coverPath = docxDir + "/" + hash + ".cover." + converted.coverExt;
                    if (WriteUtf8File(coverPath, converted.coverData)) em.coverPath = coverPath;
                }
                pb::LogInfo("import", "book:import: DOCX converted (" +
                                          std::to_string(converted.paragraphCount) + " paragraphs, " +
                                          std::to_string(converted.imageCount) + " images)");
            } else if (format == "azw3") {
                // PB-008-A：AZW3/AZW 复用 MOBI 文本管线（mutool convert -F text）。
                // 先探一次，转不出文字（KF8 不支持 / 有 DRM）就不入库，避免空书。
                const std::string probe = filefmt::ConvertMobiToText(path);
                if (probe.empty()) {
                    pb::LogWarn("import", "book:import: AZW3 text conversion unavailable "
                                              "(MuPDF unsupported or DRM): " + filefmt::GetFileName(path));
                    recordError(path, "azw3 text unavailable (DRM or unsupported)");
                    continue;
                }
            }
            filefmt::DebugLog("book:import: metadata extraction complete");

            std::string title = em.title.empty() ? filefmt::GetFileName(path) : em.title;
            json book;
            book["title"] = title;
            book["author"] = em.author.empty() ? nullptr : json(em.author);
            book["format"] = storedFormat;
            book["file_path"] = storedPath;
            if (!originalPath.empty()) book["original_path"] = originalPath;
            book["file_size"] = fileSize;
            book["description"] = em.description.empty() ? nullptr : json(em.description);
            book["publisher"] = em.publisher.empty() ? nullptr : json(em.publisher);
            book["cover_path"] = em.coverPath.empty() ? nullptr : json(em.coverPath);
            book["language"] = em.language.empty() ? nullptr : json(em.language);
            book["isbn"] = em.isbn.empty() ? nullptr : json(em.isbn);

            auto inserted = db->InsertBook(book);
            snprintf(debugBuf, sizeof(debugBuf), "book:import: inserted id=%d", inserted.value("id", -1));
            filefmt::DebugLog(debugBuf);
            result.push_back(inserted);
        } catch (const std::exception& e) {
            snprintf(debugBuf, sizeof(debugBuf), "book:import: exception: %s", e.what());
            filefmt::DebugLog(debugBuf);
            pb::LogError("import", "book:import: exception for " + filefmt::GetFileName(path) + ": " + e.what());
            recordError(path, std::string("import failed: ") + e.what());
        } catch (...) {
            filefmt::DebugLog("book:import: unknown crash, skipping file");
            pb::LogError("import", "book:import: unknown exception for " + filefmt::GetFileName(path));
            recordError(path, "import failed: unknown exception");
        }
    }
    if (!job.IsCancelled()) {
        emitProgress(static_cast<long long>(requested), static_cast<long long>(requested),
                     std::string(), true);
    }
    snprintf(debugBuf, sizeof(debugBuf), "book:import: returning %zu books", result.size());
    filefmt::DebugLog(debugBuf);
    pb::LogInfo("import", "book:import: imported " + std::to_string(result.size()) + "/" +
                              std::to_string(requested) + " book(s)");
    return result;
}


} // namespace fileimport
