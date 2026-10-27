#pragma once
// ── PB-006（A）：SearchIndexService —— 跨书全文搜索索引核心 ─────────────
//
// 目标（docs/BACKLOG.md PB-006）：在全部书籍里搜一句话，定位到书 / 片段 / 位置。
// 本文件只做「抽取纯文本 → 倒排索引 → 检索 → 持久化」这一段，不碰 UI / 桥接 /
// 数据库；App 把 DatabaseService 的 books 行喂进来，把结果交给渲染层。
//
// 支持格式（MVP）：EPUB（zip + container.xml + OPF spine 的 xhtml）、TXT、
// HTML、Markdown；PDF / FB2 / CBZ 本轮不索引（PDF 计入 skippedPdf，其余 skipped）。
//
// 索引模型（每个词项的倒排）：
//   token → bookId → [位置…]     位置 = 正文中的 Unicode 码点下标（不是字节）。
//   正文只保留一份（书 → 词项 → 位置），全局倒排表存指针，避免位置数组两份。
//   检索时 token 命中 → 按书取位置表 → 多词 AND / 中文短语连续性校验。
//
// 分词（大小写不敏感的统一规则，查询与建索引共用同一套）：
//   * 英文 / 数字 / 下划线：连续段是一个词项，ASCII + 全角字母数字统一小写；
//   * 中文（CJK / 假名 / 谚文）：逐字 unigram + 相邻 bigram；
//   * 查询里的中文串（>= 2 字）用「首 bigram 的命中位置 + 后续 bigram 是否
//     紧邻」验证整串连续，因此「全文搜索」不会命中「全文」与「搜索」分开出现
//     的段落（bigram 位置差 > 1 时判定不连续）；
//   * 标点 / 空白只是分隔符，不进索引。
//   说明：bigram + 位置连续性等价于子串匹配；查询长度 2 时 bigram 本身即整串。
//
// 增量：
//   Build(books) 按 bookId + 文件 mtime + 文件大小 + format + path 判断是否复用
//   旧条目；mtime 未知（0）或读取失败 → 重新抽取。输入里没有的旧书会被删除
//   （含抽取失败 / 文件丢失的旧条目）。单本更新用 AddBook / RemoveBook（导入 /
//   删除书的接线点）。
//   注意：复用条件只看文件戳，不含提取选项（maxCharsPerBook 等）。改了这些
//   选项后需要 Clear() 一次，或等书文件发生变化，否则旧条目按旧口径复用。
//
// 持久化：默认 <dataDir>/search_index.json（JSON：version + books[] + 每本书的
//   terms）；先写 .tmp 再 rename，失败时回退删旧文件再 rename（Windows 上
//   rename 不覆盖已存在文件）。大书库后续可换 SQLite FTS5（BACKLOG 备注）。
//
// 内存 / 取消：
//   * maxCharsPerBook / maxTotalTextChars / maxBooks 控制索引规模；总量到达上限
//     后，当前这本书截断到剩余额度，再后面的书计入 skipped；
//   * BuildOptions::isCancelled 每本书检查一次；取消时不改动已有索引
//     （返回 BuildResult::cancelled=true），调用方可安全重试；
//   * SearchOptions::isCancelled 在逐书检索时检查，取消返回已算出的部分结果。
//
// 线程安全：所有公有方法内部加锁（Search 也只读加锁），可从任意线程调用；
//   Build / AddBook / RemoveBook 会独占索引，期间 Search 阻塞（App 侧应放在
//   JobService 任务里，别在 UI 线程跑大书库重建）。
//
// 与已落地模块的关系：
//   * 位置 → 跳转目标：TXT/HTML/MD 可直接用 offset（码点）；EPUB 需要 offset →
//     CFI 的映射，由 PB-022 ReaderAdapter 做；本服务只负责「哪本书、第几个字」。
//   * 与 domain/Locator.h：本服务返回 offset + length，上层可用
//     pb::MakeTextLocator(offset, length) 生成统一位置，本文件不反向依赖 Locator。
//
// 典型用法：
//   pb::SearchIndexService index;
//   index.SetDataDir("%LOCALAPPDATA%/ParticleBook");
//   index.Load();                                   // 没有索引文件也算成功
//   pb::SearchBuildOptions bo;
//   bo.isCancelled = [&] { return jobCancelled.load(); };
//   auto br = index.Build(pb::BookTextSourcesFromDb(db.GetBooks()), bo);
//   if (br.cancelled) { /* 保留旧索引，下次再试 */ }
//   index.Save();
//   pb::SearchOptions so; so.maxHits = 30;
//   pb::SearchResult sr = index.Search("全文 搜索", so);
//   for (const auto& hit : sr.hits) { /* hit.bookId / bookTitle / snippet / offset */ }

#include "nlohmann/json.hpp"

#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace pb {

using json = nlohmann::json;

// 索引文件格式版本。读到更新的版本直接拒绝（不猜未来语义）。
// v2（PB-006-C）：每本书保存 EPUB spine 章节的正文码点区间（SearchChapterSpan），
// 搜索命中可映射到「章节 + 章节内相对偏移」；v1 索引仍可加载（只是 EPUB 命中
// 没有章节提示，重建索引后生效）。
inline constexpr int kSearchIndexVersion = 2;

// ── 错误码（稳定字符串，便于回前端 / 写日志）──────────────────────────
namespace search_index_codes {
inline constexpr const char* kOk                  = "ok";
inline constexpr const char* kUnsupportedFormat   = "unsupported_format";
inline constexpr const char* kFileMissing         = "file_missing";
inline constexpr const char* kReadError           = "read_error";
inline constexpr const char* kEmptyText           = "empty_text";
inline constexpr const char* kUnsupportedEncoding = "unsupported_encoding";
inline constexpr const char* kEpubContainer       = "epub_container_missing";
inline constexpr const char* kEpubOpf             = "epub_opf_missing";
inline constexpr const char* kEpubNoText          = "epub_no_text";
inline constexpr const char* kNoDataDir           = "no_data_dir";
inline constexpr const char* kVersion             = "version";
inline constexpr const char* kParse               = "parse";
inline constexpr const char* kWrite               = "write_error";
} // namespace search_index_codes

// ── 输入：一本书 ────────────────────────────────────────────────────
// 直接对应 DatabaseService 的 books 行（id / title / format / file_path）。
// mtime / fileSize 传 0 表示「由服务自己 stat 文件」；App 若已 stat 可传进来省一次。
struct BookTextSource {
    long long bookId = 0;
    std::string title;
    std::string format;      // "epub" / "txt" / "html" / "markdown"（大小写、点号都容忍）
    std::string filePath;    // UTF-8 路径（与 ZipReader 一致）
    std::int64_t mtime = 0;  // 文件修改时间（filesystem last_write_time count）；0 = 未知
    std::uint64_t fileSize = 0;

    // 从 books 行映射：id / title / format / file_path（缺字段按空处理）。
    static BookTextSource FromDbRow(const json& row);
};

// books 行数组 → 输入列表（忽略无效行：id <= 0 或 file_path 为空）。
std::vector<BookTextSource> BookTextSourcesFromDb(const json& booksArray);

// ── 建索引选项 ──────────────────────────────────────────────────────
struct SearchBuildOptions {
    std::size_t maxBooks = 0;                 // 0 = 不限
    std::size_t maxCharsPerBook = 4'000'000;  // 单本正文码点上限（超出截断）
    std::size_t maxTotalTextChars = 16'000'000; // 全索引正文总码点上限（0 = 不限）
    bool includeText = true;                  // false = 不保存正文（可命中，无 snippet）
    // PDF 是否入索引（mutool 逐页转 text，page 映射；默认 false —— 保持旧行为，
    // 避免改变 SearchIndexBuildResult::skippedPdf 语义；UI 侧需要 handler 传参后启用）。
    bool indexPdf = false;
    std::function<bool()> isCancelled;        // 返回 true 时停止；取消不提交改动
};

struct SearchIndexBuildResult {
    int indexed = 0;        // 重新抽取并写入
    int reused = 0;         // 命中 bookId+mtime+size 复用旧条目
    int removed = 0;        // 旧索引里有、本次输入里已不存在（含抽取失败的旧条目）
    int skipped = 0;        // 不支持的格式（PDF 单独计数）
    int skippedPdf = 0;     // PDF：未启用 indexPdf 时跳过（单独计数）
    int failed = 0;         // 文件缺失 / 读取失败 / 抽取为空
    bool cancelled = false;
    std::size_t totalChars = 0;   // 建完后的正文总码点
    std::size_t termCount = 0;    // 建完后的唯一词项数
    double elapsedMs = 0.0;       // 本次建索引耗时（毫秒）
    std::vector<std::string> errors;  // "book <id>: <code> (<path>)" 形式，最多保留 50 条
};

// ── 检索选项 / 结果 ─────────────────────────────────────────────────
struct SearchOptions {
    std::size_t maxHits = 50;              // 0 = 不限
    std::size_t snippetContextChars = 60;  // 命中前后各保留多少码点
    std::function<bool()> isCancelled;
};

// 定位区间：EPUB 按 spine 顺序记录每章正文在整本书正文里的码点区间；
// PDF（indexPdf=true）按页顺序记录每页文本区间（href="page:N"）。
// PB-006-C/D 用它把搜索命中的全局 offset 映射到「第几章/第几页 + 区间内偏移」。
struct SearchChapterSpan {
    int start = 0;    // 章节正文起始码点（含）
    int end = 0;      // 结束码点（不含）
    std::string href; // OPF 里的章节 href（调试 / 未来 CFI 映射用）
};

struct SearchHit {
    long long bookId = 0;
    std::string bookTitle;
    std::string bookFormat;
    std::string snippet;       // 命中附近上下文（含 … 省略号）；未存正文时为空
    // 命中起点（码点）。格式相关：
    //   * TXT/MOBI 等：正文全局偏移（可直接转 text locator）；
    //   * EPUB：章节内相对偏移（配合 page=章节百分比×10）；
    //   * HTML/Markdown：正文全局偏移（page 里给百分比提示）；
    long long offset = -1;
    long long matchLength = 0; // 命中长度（码点）
    // 定位提示（0 = 无提示；其余 = 1 + 百分比×10，避免与「无提示」混淆）：
    //   * EPUB：章节起点在 spine 中的百分比 × 10 + 1 → seekTo 近似跳章节，
    //     配合 offset=章节内相对偏移，渲染层再按比例滚到章节内位置；
    //   * HTML/Markdown/FB2：命中位置在整本书正文中的百分比 × 10 + 1；
    //   * PDF（indexPdf=true 时）：命中页码（1-based），offset=页内相对码点；
    //   * TXT/MOBI/AZW3：0（用全局 offset 精确跳）。
    int page = 0;
    double score = 0.0;        // 越高越相关（同分按 bookId 升序）
    int termHits = 0;          // 命中的查询项数（多词 AND 时等于查询项数）
};

struct SearchResult {
    std::vector<SearchHit> hits;
    int booksMatched = 0;   // AND 后候选书数（可能 > hits.size()，被 maxHits 截断）
    bool truncated = false;
    bool cancelled = false;
};

// ── 服务 ────────────────────────────────────────────────────────────
class SearchIndexService {
public:
    SearchIndexService() = default;
    ~SearchIndexService() = default;
    SearchIndexService(const SearchIndexService&) = delete;
    SearchIndexService& operator=(const SearchIndexService&) = delete;

    // ── 位置 / 持久化 ──────────────────────────────────────────────
    void SetDataDir(const std::string& dataDirUtf8);
    std::string DataDir() const;
    // <dataDir>/search_index.json；未设 dataDir 时返回空串。
    std::string IndexFilePath() const;

    bool Save(std::string* error = nullptr);
    bool SaveTo(const std::string& filePathUtf8, std::string* error = nullptr);
    bool Load(std::string* error = nullptr);
    bool LoadFrom(const std::string& filePathUtf8, std::string* error = nullptr);

    // ── 建索引 ─────────────────────────────────────────────────────
    // 全量扫描 + 按 bookId/mtime/size/format/path 增量复用；输入里没有的书删除。
    SearchIndexBuildResult Build(const std::vector<BookTextSource>& books,
                                 const SearchBuildOptions& options = SearchBuildOptions());
    // 单本新增 / 更新（导入完成时调用）；失败时索引不变。
    bool AddBook(const BookTextSource& book,
                 const SearchBuildOptions& options = SearchBuildOptions(),
                 std::string* error = nullptr);
    bool RemoveBook(long long bookId);
    void Clear();

    // ── 检索 ───────────────────────────────────────────────────────
    SearchResult Search(const std::string& query,
                        const SearchOptions& options = SearchOptions()) const;

    // ── 统计 ───────────────────────────────────────────────────────
    std::size_t BookCount() const;
    std::size_t TermCount() const;
    std::size_t TotalChars() const;
    bool HasBook(long long bookId) const;
    std::int64_t BookMtime(long long bookId) const;

    // ── 文本抽取（App 导入 / 单测可直接用；PDF 返回 false + unsupported_format）──
    // format 可传 "epub"/".EPUB"/"txt"/"html"/"md"…；空则按扩展名推断。
    static bool ExtractText(const std::string& filePathUtf8, const std::string& format,
                            std::size_t maxChars, std::string& out, std::string* error = nullptr);
    // 规范化格式名：epub / txt / html / markdown / pdf / fb2 / cbz / ""（未知）。
    static std::string NormalizeFormat(const std::string& formatOrExtension);
    // HTML → 纯文本（去 script/style/注释，解实体，块级标签换行）。
    static std::string HtmlToText(const std::string& html);
    // Markdown → 纯文本（去标记、代码围栏、链接只留文字，再走 HTML 清理）。
    static std::string MarkdownToText(const std::string& markdown);

private:
    struct BookRecord {
        long long bookId = 0;
        std::string title;
        std::string format;
        std::string filePath;
        std::int64_t mtime = 0;
        std::uint64_t fileSize = 0;
        std::size_t textChars = 0;
        std::string text;   // 规范化后的正文（includeText=false 时为空）
        // EPUB spine 章节区间（start/end 是全书正文的码点下标）；其它格式为空。
        std::vector<SearchChapterSpan> chapters;
        // 本书倒排：token → 升序位置数组（码点下标）。正文只在这里存一份。
        std::unordered_map<std::string, std::vector<int>> terms;
    };
    using BookPtr = std::shared_ptr<BookRecord>;
    // token → (bookId → 位置数组指针)，指针指向 BookRecord::terms 里的 vector。
    struct IndexData {
        std::unordered_map<long long, BookPtr> books;
    };

    // 锁内实现（*Locked 后缀表示调用方已持锁）。
    SearchIndexBuildResult BuildLocked(const std::vector<BookTextSource>& books,
                                       const SearchBuildOptions& options);
    bool AddBookLocked(const BookTextSource& book, const SearchBuildOptions& options,
                       std::string* error);
    SearchResult SearchLocked(const std::string& query, const SearchOptions& options) const;
    void RebuildTermIndexLocked();
    void ClearLocked();
    json ToJsonLocked() const;

    mutable std::mutex m_mutex;
    IndexData m_index;
    std::unordered_map<std::string, std::unordered_map<long long, const std::vector<int>*>> m_terms;
    std::string m_dataDir;
};

} // namespace pb
