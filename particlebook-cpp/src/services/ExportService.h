#pragma once
// ── PB-004（A）：笔记 / 高亮导出核心 ─────────────────────────────────
//
// 背景（docs/BACKLOG.md PB-004、PB-021 的 Annotation 模型）：
//   书签 / 高亮 / 笔记在数据库里是三张独立的 JSON 表（bookmarks /
//   highlights / notes），导出时要么在渲染层再写三遍格式化，要么在这里
//   收敛一次。本文件只做「数据 → 可带走文件」这一件事：Markdown / CSV /
//   JSON 三种格式、单本与全库两种范围，输出是纯字符串（不碰文件系统），
//   复制到剪贴板 / 写文件 / 预览都复用同一份结果。
//
// 输入契约（与现有 DB 行兼容，snake_case / camelCase 都收）：
//   book       : books 一行（id / title / author / format / language /
//                publisher / isbn / added_at / last_opened；file_path 只在
//                includeSourcePath 时才写出）
//   bookmarks  : { id, book_id, cfi, page, progress, title, note, created_at }
//   highlights : { id, book_id, cfi, page, text, color, note, tags, created_at }
//   notes      : { id, book_id, highlight_id, cfi, page, content,
//                  created_at, updated_at }
//   toc        : 可选章节表 [ {title, cfi|href|page, level, children} | "标题" ]
//                （也接受 book["toc"]）；用来把标注归到章节并排出章节顺序。
//                没有 toc 时按「行自带的 chapter / chapter_index」或位置排序。
//
// 输出语义：
//   * Markdown：书名/作者/格式/统计/导出时间 → 目录（显式 a 标签锚点）→
//     按章节分节；高亮输出引用原文（每行加 > 前缀）+ 颜色 + 位置 + 时间 +
//     笔记；书签与独立笔记各自成块；内容里的 < > 默认转义为 &lt; &gt;
//     （防 Markdown 渲染器执行注入的 HTML）。
//   * CSV：RFC 4180（字段含逗号/引号/换行才加引号，内部引号双写），
//     结尾统一 CRLF；UTF-8，可选 BOM（Excel 打开中文不乱码）；
//     可选公式注入防护（= + - @ 开头加单引号）。
//   * JSON：稳定结构 + 版本号（kExportSchemaVersion）。nlohmann::json 的
//     键序确定，同一输入两次导出字节相同；空字段也写出（不省略键）。
//   * 排序：章节 → TOC 顺序；章内 → 位置（pdf 页码 / 文本偏移 / cfi 字典序 /
//     百分比）→ created_at → id。没有章节归属的条目排在最后。
//   * 文件名：SafeFileName 去掉路径分隔符 / Windows 非法字符 / 设备名，
//     按 UTF-8 码点截断（不切坏多字节），空标题回退到 fallback。
//
// 安全边界：
//   * 不读 Key、不碰 SecretStore；book 的任意字段不会整包透传，只取白名单
//     字段（title/author/format/language/publisher/isbn/id/added_at 等）；
//   * 本机路径（file_path / cover_path）默认不写；需要时显式打开
//     includeSourcePath（用于「导出时带上原始文件位置」的可选场景）。
//   * Markdown 里的书名/章节名/引用/笔记都过同一套清洗：控制字符剔除、
//     CRLF 归一、小于号/大于号转义；CSV 走严格引号转义。
//
// 设计约束（与 platform/SettingsService.h、domain/Annotation.h 一致）：
//   * 纯 std + nlohmann/json，不含 windows.h，不读数据库、不写文件：
//     单测直接链接 ExportService.cpp；
//   * 无全局可变状态，任意线程可并发调用；
//   * 永不抛异常：坏行 / 坏字段进 ExportResult.issues，能导出的照样导出；
//     row 不是对象、highlight 无原文、note 无正文 → 跳过该行并记 Warning。
//
// 典型接线（App / Bridge / 渲染层是后续任务，本轮不改）：
//   pb::ExportBook book;
//   book.book = db->GetBook(id);
//   book.bookmarks = db->GetBookmarks(id);
//   book.highlights = db->GetHighlights(id);
//   book.notes = db->GetNotes(id);
//   pb::ExportOptions options;
//   options.format = pb::ExportFormat::Markdown;
//   pb::ExportResult r = pb::ExportService::ExportBook(book, options);
//   if (r.ok) { WriteFile(r.fileName, r.content); }   // 或直接回给前端复制
//
//   全库：
//   auto books = pb::ExportService::GroupByBook(db->GetBooks(), db->GetBookmarks(), ...);
//   pb::ExportResult r = pb::ExportService::ExportLibrary(books, options);

#include "nlohmann/json.hpp"

#include <cstddef>
#include <string>
#include <vector>

namespace pb {

using json = nlohmann::json;

// 导出结构版本：写进 JSON 的 version / 诊断用；不兼容改动时递增。
inline constexpr int kExportSchemaVersion = 1;

// ── 格式 ─────────────────────────────────────────────────────────────
enum class ExportFormat {
    Markdown = 0,
    Csv = 1,
    Json = 2,
};

// "markdown" / "csv" / "json"（小写，稳定，写进文件名与 JSON）。
const char* ExportFormatName(ExportFormat format) noexcept;
// ".md" / ".csv" / ".json"。
const char* ExportFileExtension(ExportFormat format) noexcept;
// "text/markdown; charset=utf-8" / "text/csv; charset=utf-8" / "application/json; charset=utf-8"。
const char* ExportMimeType(ExportFormat format) noexcept;
// 大小写不敏感；接受 md / markdown / csv / json / text / txt。
bool ParseExportFormat(const std::string& text, ExportFormat& out) noexcept;

// ── 问题报告 ─────────────────────────────────────────────────────────
enum class ExportSeverity {
    Warning,  // 行被跳过 / 字段被修正 / 需要关注，导出仍成功
    Error     // 输入里有不能导出的东西（ExportResult.ok 仍可能为 true）
};

struct ExportIssue {
    std::string scope;    // "book" / "bookmarks[3]" / "highlights[0]" / "toc[2]" / ""
    std::string code;     // row|parse|skip|format|title|toc|duplicate|internal
    std::string message;  // 英文短说明，渲染层负责本地化
    ExportSeverity severity = ExportSeverity::Warning;
};

// ── 选项 ─────────────────────────────────────────────────────────────
struct ExportOptions {
    ExportFormat format = ExportFormat::Markdown;

    bool includeBookmarks = true;
    bool includeHighlights = true;
    bool includeNotes = true;

    // 全库导出时：没有任何条目的书是否也输出标题 / 空章节。
    bool includeEmptyBooks = false;

    // Markdown：书名 + 作者/格式/统计/导出时间 + 目录。false 时直接从正文开始。
    bool includeFrontMatter = true;
    // Markdown：高亮是否输出引用原文（关掉只留颜色/笔记）。
    bool includeQuote = true;
    // Markdown / JSON：是否输出标签（highlights.tags）。
    bool includeTags = true;
    // 是否输出原书路径（file_path / cover_path）。默认 false：导出文件不该带
    // 本机目录结构（用户名 / 下载目录都在里面）。
    bool includeSourcePath = false;

    // Markdown：用显式 HTML 锚点（a 标签）给目录用；关掉则只留标题。
    bool markdownAnchors = true;
    // Markdown：把用户/书里的小于号大于号转义，避免渲染器执行 HTML。
    bool markdownEscapeHtml = true;

    // CSV：UTF-8 BOM（Excel 识别中文）。
    bool csvBom = false;
    // CSV：以 = + - @ 开头的单元格前置单引号（防 Excel 公式注入）。默认关：
    // 会改数据内容，需要时显式打开。
    bool csvFormulaGuard = false;

    // ISO-8601；空则用当前 UTC 时间（测试固定传值以获得字节级快照）。
    std::string exportedAt;

    // 覆盖书名（空 = 用 book["title"]；用于「导出选中的笔记」这类改名场景）。
    std::string titleOverride;

    // 文件名（UTF-8 字节）上限；SafeFileName 按码点截断不会切坏汉字。
    std::size_t maxFileNameLength = 96;
};

// ── 统计 ─────────────────────────────────────────────────────────────
struct ExportStats {
    int books = 0;
    int bookmarks = 0;
    int highlights = 0;
    int notes = 0;          // 包含挂在 highlight 上的笔记
    int attachedNotes = 0;  // 其中挂在高亮上的（不单独成条目）
    int entries = 0;        // 实际渲染的条目数（highlight / 独立 note / bookmark）
};

// ── 结果 ─────────────────────────────────────────────────────────────
struct ExportResult {
    bool ok = false;
    ExportFormat format = ExportFormat::Markdown;
    std::string fileName;   // SafeFileName 之后的建议文件名（含扩展名）
    std::string mimeType;
    std::string content;    // 文件内容（UTF-8；CSV 可能带 BOM）
    ExportStats stats;
    std::vector<ExportIssue> issues;

    // 是否存在 Error 级问题（用于 UI 提示「部分条目被跳过」）。
    bool HasErrors() const;
};

// ── 输入 ─────────────────────────────────────────────────────────────
struct ExportBook {
    json book = json::object();        // DB books 一行
    json bookmarks = json::array();    // DB bookmarks 行
    json highlights = json::array();   // DB highlights 行
    json notes = json::array();        // DB notes 行
    json toc = json::array();          // 可选章节表；空则用 book["toc"]
};

// ── 服务（无状态静态函数）────────────────────────────────────────────
class ExportService final {
public:
    // 单本导出。空数据也返回 ok=true（Markdown 写「没有可导出的标注」，
    // CSV 只有表头，JSON 是空数组结构）。
    static ExportResult ExportBook(const pb::ExportBook& book,
                                   const ExportOptions& options = ExportOptions());

    // 全库导出：多本合并。books 按书名（不区分大小写）→ id 排序；
    // 默认跳过没有条目的书（includeEmptyBooks 打开则保留）。
    static ExportResult ExportLibrary(const std::vector<pb::ExportBook>& books,
                                      const ExportOptions& options = ExportOptions());

    // 把 DB 的四个数组按 book_id 分组成 ExportBook（全库导出入口）。
    // 未知 book_id 的行、非对象行 → 跳过并记 issue；books 里同名 id 只保留首个。
    static std::vector<pb::ExportBook> GroupByBook(const json& books,
                                              const json& bookmarks,
                                              const json& highlights,
                                              const json& notes,
                                              std::vector<ExportIssue>* issues = nullptr);

    // 文件名安全化：去路径分隔符 / Windows 非法字符 / 控制字符、折叠空白、
    // 去首尾点与空格、规避 CON/PRN/AUX/NUL/COM1..9/LPT1..9、
    // 按 UTF-8 码点截断到 maxBytes；结果为空时返回 fallback。
    static std::string SafeFileName(const std::string& name,
                                    const std::string& fallback = "export",
                                    std::size_t maxBytes = 96);

    // 单本建议文件名："<书名>-标注-YYYYMMDD.<ext>"；书名缺失用 "未命名书籍"。
    static std::string SuggestedFileName(const pb::ExportBook& book,
                                         const ExportOptions& options = ExportOptions());

    // 全库建议文件名："ParticleBook-标注导出-YYYYMMDD.<ext>"。
    static std::string LibraryFileName(const ExportOptions& options = ExportOptions());

    // 当前 UTC 时间，格式与 DatabaseService::NowISO 一致（"…T…:…:…000Z"）。
    static std::string NowIso8601Utc();
};

} // namespace pb
