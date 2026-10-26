// ── PB-006（A）：SearchIndexService 单元测试 ─────────────────────────
// 不依赖 GUI / 数据库：直接链接 SearchIndexService.cpp + ZipReader.cpp +
// third_party/miniz*.c（EPUB 解压）。测试自己在临时目录造样本文件：
//   * TXT / HTML / Markdown / EPUB（手写 store 方式的 zip，ZipReader 可读）
//   * PDF（不建文件：建索引阶段就按格式跳过，不 stat）
//
// 覆盖（docs/BACKLOG.md PB-006 MVP）：
//   * 建索引：四种格式、PDF 跳过、未知格式跳过、缺文件失败、词项数/正文统计
//   * 检索：单/多词 AND、大小写不敏感、中文整串 / 单字、缺失词、跨格式、
//     maxHits 截断、空查询、snippet / offset / page、按书去重
//   * 中文 bigram 连续性：「全文」「搜索」分开出现时不能被「全文搜索」命中
//   * 增量：mtime+size 不变复用；文件改动后只重建那一本；书删除后条目消失
//   * 持久化：Save/Load round-trip、版本过新拒绝、坏 JSON 拒绝、未设 dataDir
//   * 取消 / 内存上限：cancel 不提交改动；maxBooks / maxCharsPerBook /
//     maxTotalTextChars 生效
//   * AddBook / RemoveBook 单本增删
//   * 静态抽取工具：ExtractText（TXT/HTML/MD/EPUB/UTF-16 BOM/缺文件/PDF）、
//     HtmlToText（标签/script/style/实体）、MarkdownToText、NormalizeFormat

#include "services/SearchIndexService.h"

#include "utils/test_assert.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <string>
#include <utility>
#include <vector>

namespace fs = std::filesystem;
using pb::BookTextSource;
using pb::SearchBuildOptions;
using pb::SearchIndexBuildResult;
using pb::SearchIndexService;
using pb::SearchOptions;
using pb::SearchResult;

namespace {

// ── 临时目录 ─────────────────────────────────────────────────────────

struct TempDir {
    fs::path path;

    TempDir()
    {
        const auto stamp = std::chrono::steady_clock::now().time_since_epoch().count();
        path = fs::temp_directory_path() / ("pb_search_index_test_" + std::to_string(stamp));
        std::error_code ec;
        fs::create_directories(path, ec);
    }

    ~TempDir()
    {
        std::error_code ec;
        fs::remove_all(path, ec);
    }

    std::string File(const std::string& name) const
    {
        return (path / fs::u8path(name)).u8string();
    }
};

bool WriteTextFile(const std::string& path, const std::string& content, bool utf16leBom = false)
{
    std::ofstream out(fs::u8path(path), std::ios::binary | std::ios::trunc);
    if (!out) return false;
    if (utf16leBom) {
        out.put(static_cast<char>(0xFF));
        out.put(static_cast<char>(0xFE));
        for (char c : content) {
            out.put(c);
            out.put('\0');
        }
    } else {
        out.write(content.data(), static_cast<std::streamsize>(content.size()));
    }
    return static_cast<bool>(out);
}

std::string ReadTextFile(const std::string& path)
{
    std::ifstream in(fs::u8path(path), std::ios::binary);
    return std::string((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
}

// ── 手写 store 方式 zip（ZipReader 支持 store / deflate；这里用 store 免压缩）──

void PutU16(std::string& bytes, unsigned value)
{
    bytes.push_back(static_cast<char>(value & 0xFF));
    bytes.push_back(static_cast<char>((value >> 8) & 0xFF));
}

void PutU32(std::string& bytes, unsigned value)
{
    bytes.push_back(static_cast<char>(value & 0xFF));
    bytes.push_back(static_cast<char>((value >> 8) & 0xFF));
    bytes.push_back(static_cast<char>((value >> 16) & 0xFF));
    bytes.push_back(static_cast<char>((value >> 24) & 0xFF));
}

bool WriteStoredZip(const std::string& path,
                    const std::vector<std::pair<std::string, std::string>>& entries)
{
    struct CentralEntry {
        std::string name;
        std::string data;
        unsigned offset = 0;
    };

    std::string bytes;
    std::vector<CentralEntry> central;

    for (const auto& entry : entries) {
        CentralEntry record;
        record.name = entry.first;
        record.data = entry.second;
        record.offset = static_cast<unsigned>(bytes.size());

        PutU32(bytes, 0x04034b50);                     // local file header
        PutU16(bytes, 20);                             // version needed
        PutU16(bytes, 0);                              // flags
        PutU16(bytes, 0);                              // compression = store
        PutU16(bytes, 0);                              // mod time
        PutU16(bytes, 0);                              // mod date
        PutU32(bytes, 0);                              // crc32（ZipReader 不校验）
        PutU32(bytes, static_cast<unsigned>(record.data.size()));
        PutU32(bytes, static_cast<unsigned>(record.data.size()));
        PutU16(bytes, static_cast<unsigned>(record.name.size()));
        PutU16(bytes, 0);                              // extra len
        bytes.append(record.name);
        bytes.append(record.data);
        central.push_back(std::move(record));
    }

    const unsigned centralOffset = static_cast<unsigned>(bytes.size());
    for (const CentralEntry& entry : central) {
        PutU32(bytes, 0x02014b50);                     // central directory header
        PutU16(bytes, 20);                             // version made by
        PutU16(bytes, 20);                             // version needed
        PutU16(bytes, 0);                              // flags
        PutU16(bytes, 0);                              // compression
        PutU16(bytes, 0);                              // mod time
        PutU16(bytes, 0);                              // mod date
        PutU32(bytes, 0);                              // crc32
        PutU32(bytes, static_cast<unsigned>(entry.data.size()));
        PutU32(bytes, static_cast<unsigned>(entry.data.size()));
        PutU16(bytes, static_cast<unsigned>(entry.name.size()));
        PutU16(bytes, 0);                              // extra len
        PutU16(bytes, 0);                              // comment len
        PutU16(bytes, 0);                              // disk number start
        PutU16(bytes, 0);                              // internal attrs
        PutU32(bytes, 0);                              // external attrs
        PutU32(bytes, entry.offset);
        bytes.append(entry.name);
    }
    const unsigned centralSize = static_cast<unsigned>(bytes.size()) - centralOffset;

    PutU32(bytes, 0x06054b50);                         // EOCD
    PutU16(bytes, 0);                                  // disk number
    PutU16(bytes, 0);                                  // central dir disk
    PutU16(bytes, static_cast<unsigned>(central.size()));
    PutU16(bytes, static_cast<unsigned>(central.size()));
    PutU32(bytes, centralSize);
    PutU32(bytes, centralOffset);
    PutU16(bytes, 0);                                  // comment len

    std::ofstream out(fs::u8path(path), std::ios::binary | std::ios::trunc);
    if (!out) return false;
    out.write(bytes.data(), static_cast<std::streamsize>(bytes.size()));
    return static_cast<bool>(out);
}

// ── 断言辅助 ─────────────────────────────────────────────────────────

bool HasBookId(const SearchResult& result, long long bookId)
{
    for (const pb::SearchHit& hit : result.hits) {
        if (hit.bookId == bookId) return true;
    }
    return false;
}

const pb::SearchHit* FindHit(const SearchResult& result, long long bookId)
{
    for (const pb::SearchHit& hit : result.hits) {
        if (hit.bookId == bookId) return &hit;
    }
    return nullptr;
}

bool HasErrorEntry(const SearchIndexBuildResult& result, const std::string& code)
{
    for (const std::string& entry : result.errors) {
        if (entry.find(code) != std::string::npos) return true;
    }
    return false;
}

bool Contains(const std::string& text, const std::string& needle)
{
    return text.find(needle) != std::string::npos;
}

// 索引里的 4 本可索引书：TXT / HTML / Markdown / EPUB；外加 PDF / CBZ / 缺文件。
struct SampleLibrary {
    TempDir dir;
    std::string txtPath;
    std::string htmlPath;
    std::string mdPath;
    std::string epubPath;

    SampleLibrary()
    {
        txtPath = dir.File("sample.txt");
        htmlPath = dir.File("sample.html");
        mdPath = dir.File("sample.md");
        epubPath = dir.File("sample.epub");

        WriteTextFile(txtPath,
                      "The quick brown needle jumps over the lazy dog.\n"
                      "这是全文搜索的测试文本。\n"
                      "alpha beta 组合出现。\n");

        WriteTextFile(htmlPath,
                      "<html><head><title>HTML 标题</title>"
                      "<style>.hidden{color:red}</style>"
                      "<script>var secret = \"todaysneedle\";</script></head>"
                      "<body><h1>跨书检索</h1>"
                      "<p>HTML 里的 needle 也应当被索引 &amp; 实体 &lt;ok&gt;。</p>"
                      "<p>这里 全文 和 搜索 分开出现。</p></body></html>");

        const std::string fence = "\x60\x60\x60";
        WriteTextFile(mdPath,
                      "# Markdown 标题\n\n"
                      "这是 **Markdown** 书，含行内代码 \x60mapreduce\x60 与 [链接](https://example.com)。\n"
                      "machine learning 与 全文搜索。\n"
                      + fence + "\ncode block word: zookeeper\n" + fence + "\n");

        WriteStoredZip(epubPath,
                       {
                           { "META-INF/container.xml",
                             "<container version=\"1.0\"><rootfiles>"
                             "<rootfile full-path=\"OEBPS/content.opf\" "
                             "media-type=\"application/oebps-package+xml\"/>"
                             "</rootfiles></container>" },
                           { "OEBPS/content.opf",
                             "<package xmlns=\"http://www.idpf.org/2007/opf\">"
                             "<metadata><dc:title>EPUB Book</dc:title></metadata>"
                             "<manifest>"
                             "<item id=\"ch1\" href=\"ch1.xhtml\" media-type=\"application/xhtml+xml\"/>"
                             "<item id=\"ch2\" href=\"ch2.xhtml\" media-type=\"application/xhtml+xml\"/>"
                             "</manifest>"
                             "<spine><itemref idref=\"ch1\"/><itemref idref=\"ch2\"/></spine>"
                             "</package>" },
                           { "OEBPS/ch1.xhtml",
                             "<html><head><title>Chapter One</title></head><body>"
                             "<h1>第一章</h1><p>这是跨书全文搜索的样本段落，needle 出现在这里。</p>"
                             "</body></html>" },
                           { "OEBPS/ch2.xhtml",
                             "<html><head><style>.x{}</style></head><body>"
                             "<p>第二段：中文短语“全文搜索”再次出现。</p>"
                             "<script>var shouldNotIndex = \"epubsecret\";</script>"
                             "</body></html>" },
                       });
    }

    std::vector<BookTextSource> Sources(bool includeHtml = true) const
    {
        std::vector<BookTextSource> sources;
        sources.push_back(BookTextSource{ 1, "TXT Book", "txt", txtPath, 0, 0 });
        if (includeHtml) {
            sources.push_back(BookTextSource{ 2, "HTML Book", "html", htmlPath, 0, 0 });
        }
        sources.push_back(BookTextSource{ 3, "Markdown Book", "md", mdPath, 0, 0 });
        sources.push_back(BookTextSource{ 4, "EPUB Book", "epub", epubPath, 0, 0 });
        sources.push_back(BookTextSource{ 5, "PDF Book", "pdf", dir.File("nope.pdf"), 0, 0 });
        sources.push_back(BookTextSource{ 6, "CBZ Book", "cbz", dir.File("nope.cbz"), 0, 0 });
        sources.push_back(BookTextSource{ 7, "Missing Book", "txt", dir.File("missing.txt"), 0, 0 });
        return sources;
    }
};

void BumpMtime(const std::string& path)
{
    std::error_code ec;
    const fs::path p = fs::u8path(path);
    const auto now = fs::file_time_type::clock::now();
    fs::last_write_time(p, now + std::chrono::seconds(5), ec);
}

// ── 1) 建索引 + 搜索 ─────────────────────────────────────────────────

void TestBuildAndSearch(const SampleLibrary& lib)
{
    SearchIndexService index;
    const SearchIndexBuildResult build = index.Build(lib.Sources());
    CHECK_EQ(build.indexed, 4);
    CHECK_EQ(build.reused, 0);
    CHECK_EQ(build.skippedPdf, 1);
    CHECK_EQ(build.skipped, 1);   // cbz
    CHECK_EQ(build.failed, 1);    // missing.txt
    CHECK_TRUE(build.removed == 0);
    CHECK_TRUE(!build.cancelled);
    CHECK_TRUE(HasErrorEntry(build, "file_missing"));
    CHECK_EQ(static_cast<int>(index.BookCount()), 4);
    CHECK_TRUE(index.TermCount() > 0);
    CHECK_TRUE(index.TotalChars() > 0);
    CHECK_TRUE(index.HasBook(4));
    CHECK_TRUE(!index.HasBook(5));  // PDF 不入索引
    CHECK_TRUE(index.BookMtime(1) != 0);

    // 单英文词 + 大小写不敏感
    const SearchResult needle = index.Search("needle");
    CHECK_EQ(needle.booksMatched, 3);  // txt / html / epub
    CHECK_EQ(static_cast<int>(needle.hits.size()), 3);
    CHECK_TRUE(HasBookId(needle, 1));
    CHECK_TRUE(HasBookId(needle, 2));
    CHECK_TRUE(HasBookId(needle, 4));
    CHECK_EQ(static_cast<int>(index.Search("NEEDLE").hits.size()), 3);
    CHECK_EQ(static_cast<int>(index.Search("NeEdLe").hits.size()), 3);

    const pb::SearchHit* txtHit = FindHit(needle, 1);
    CHECK_TRUE(txtHit != nullptr);
    if (txtHit) {
        CHECK_EQ(txtHit->bookTitle, std::string("TXT Book"));
        CHECK_EQ(txtHit->bookFormat, std::string("txt"));
        CHECK_EQ(txtHit->offset, 16);  // "The quick brown " 之后
        CHECK_EQ(txtHit->matchLength, 6);
        CHECK_EQ(txtHit->page, 0);
        CHECK_TRUE(Contains(txtHit->snippet, "needle"));
        CHECK_TRUE(Contains(txtHit->snippet, "quick brown"));
    }

    // 多词 AND：只有 txt 同时有 alpha + beta
    const SearchResult pair = index.Search("alpha beta");
    CHECK_EQ(static_cast<int>(pair.hits.size()), 1);
    CHECK_TRUE(HasBookId(pair, 1));
    CHECK_EQ(static_cast<int>(index.Search("alpha").hits.size()), 1);  // 只有 txt
    CHECK_EQ(static_cast<int>(index.Search("alpha gamma").hits.size()), 0);

    // 缺失词 / 空查询
    CHECK_EQ(static_cast<int>(index.Search("zzzznotfound").hits.size()), 0);
    CHECK_EQ(static_cast<int>(index.Search("   ").hits.size()), 0);
    CHECK_EQ(index.Search("").booksMatched, 0);

    // 中文整串 / 单字
    const SearchResult phrase = index.Search("全文搜索");
    CHECK_EQ(static_cast<int>(phrase.hits.size()), 3);  // txt / md / epub
    CHECK_TRUE(HasBookId(phrase, 1));
    CHECK_TRUE(HasBookId(phrase, 3));
    CHECK_TRUE(HasBookId(phrase, 4));
    CHECK_TRUE(!HasBookId(phrase, 2));  // html 里「全文」「搜索」是分开的
    const SearchResult fullText = index.Search("全文");
    CHECK_TRUE(HasBookId(fullText, 2));  // 分开出现也能单词命中
    const SearchResult single = index.Search("书");
    CHECK_TRUE(single.hits.size() >= 2);  // html「跨书检索」/ md「Markdown 书」/ epub「跨书」

    // 多词 AND + 中文混合
    const SearchResult mixed = index.Search("needle 全文搜索");
    CHECK_EQ(static_cast<int>(mixed.hits.size()), 2);  // txt / epub
    CHECK_TRUE(HasBookId(mixed, 1));
    CHECK_TRUE(HasBookId(mixed, 4));

    // 多个词与重复词（去重后等价）
    CHECK_EQ(static_cast<int>(index.Search("needle needle needle").hits.size()), 3);

    // maxHits 截断
    SearchOptions limited;
    limited.maxHits = 2;
    const SearchResult truncated = index.Search("needle", limited);
    CHECK_EQ(static_cast<int>(truncated.hits.size()), 2);
    CHECK_TRUE(truncated.truncated);
    CHECK_EQ(truncated.booksMatched, 3);

    // snippet / offset 落在正文内
    const SearchResult htmlHit = index.Search("跨书检索");
    CHECK_EQ(static_cast<int>(htmlHit.hits.size()), 1);
    if (!htmlHit.hits.empty()) {
        CHECK_TRUE(htmlHit.hits[0].offset >= 0);
        CHECK_TRUE(!htmlHit.hits[0].snippet.empty());
    }

    // EPUB：正文顺序（ch1 在 ch2 前）与 script/style 不索引
    const SearchResult epubOnly = index.Search("样本段落");
    CHECK_EQ(static_cast<int>(epubOnly.hits.size()), 1);
    if (!epubOnly.hits.empty()) {
        CHECK_EQ(epubOnly.hits[0].bookId, 4);
        CHECK_TRUE(epubOnly.hits[0].offset < 60);  // 第一章开头附近
    }
    CHECK_EQ(static_cast<int>(index.Search("epubsecret").hits.size()), 0);
    CHECK_EQ(static_cast<int>(index.Search("todaysneedle").hits.size()), 0);  // script 内容

    // HTML 实体解码后可搜到
    CHECK_EQ(static_cast<int>(index.Search("ok").hits.size()), 1);
}

// ── 2) 增量重建 / 删除书 ─────────────────────────────────────────────

void TestIncremental(const SampleLibrary& lib)
{
    SearchIndexService index;
    SearchIndexBuildResult build = index.Build(lib.Sources());
    CHECK_EQ(build.indexed, 4);

    // 文件没变：全部复用
    build = index.Build(lib.Sources());
    CHECK_EQ(build.indexed, 0);
    CHECK_EQ(build.reused, 4);
    CHECK_EQ(static_cast<int>(index.BookCount()), 4);

    // 只改 TXT：只有它重建
    WriteTextFile(lib.txtPath,
                  "The quick brown zebra jumps.\n"
                  "这是新的全文搜索文本。\n");
    BumpMtime(lib.txtPath);
    build = index.Build(lib.Sources());
    CHECK_EQ(build.indexed, 1);
    CHECK_EQ(build.reused, 3);
    CHECK_EQ(static_cast<int>(index.Search("zebra").hits.size()), 1);
    CHECK_EQ(static_cast<int>(index.Search("needle").hits.size()), 2);  // html + epub

    // 删除书：HTML 从输入里去掉 → removed=1 且旧词项消失
    build = index.Build(lib.Sources(/*includeHtml=*/false));
    CHECK_EQ(build.removed, 1);
    CHECK_EQ(static_cast<int>(index.BookCount()), 3);
    CHECK_EQ(static_cast<int>(index.Search("跨书检索").hits.size()), 0);
    CHECK_TRUE(!index.HasBook(2));
}

// ── 3) 复用判定口径（bookId+mtime+size）─────────────────────────────

void TestReuseStampSemantics(const SampleLibrary& lib)
{
    SearchIndexService index;
    SearchIndexBuildResult build = index.Build(lib.Sources());
    CHECK_EQ(build.indexed, 4);

    // 覆盖写入后 size / mtime 变了 → 重建（MVP 不做内容哈希）
    WriteTextFile(lib.txtPath, "The quick brown zebra jumps.\n短了很多。\n");
    std::error_code ec;
    fs::last_write_time(fs::u8path(lib.txtPath), fs::file_time_type::clock::now(), ec);
    build = index.Build(lib.Sources());
    CHECK_TRUE(build.indexed >= 1);
}

// ── 4) 取消 / 内存上限 ───────────────────────────────────────────────

void TestCancelAndLimits(const SampleLibrary& lib)
{
    // 取消：不提交 staging，旧索引保持不变
    {
        SearchIndexService index;
        const SearchIndexBuildResult first = index.Build(lib.Sources());
        CHECK_EQ(first.indexed, 4);

        int calls = 0;
        SearchBuildOptions options;
        options.isCancelled = [&calls]() { return ++calls > 1; };
        const SearchIndexBuildResult cancelled = index.Build(lib.Sources(false), options);
        CHECK_TRUE(cancelled.cancelled);
        CHECK_EQ(static_cast<int>(index.BookCount()), 4);  // 旧索引原样
        CHECK_EQ(static_cast<int>(index.Search("跨书检索").hits.size()), 1);
    }

    // maxBooks：只索引 1 本
    {
        SearchIndexService index;
        SearchBuildOptions options;
        options.maxBooks = 1;
        const SearchIndexBuildResult build = index.Build(lib.Sources(), options);
        CHECK_EQ(build.indexed, 1);
        CHECK_TRUE(build.skipped >= 1);
        CHECK_EQ(static_cast<int>(index.BookCount()), 1);
    }

    // maxCharsPerBook：超出部分不索引
    {
        TempDir dir;
        const std::string path = dir.File("limited.txt");
        WriteTextFile(path, "alphafirst beta second zetafinal");
        std::vector<BookTextSource> sources;
        sources.push_back(BookTextSource{ 1, "Limited", "txt", path, 0, 0 });

        SearchIndexService index;
        SearchBuildOptions options;
        options.maxCharsPerBook = 20;
        const SearchIndexBuildResult build = index.Build(sources, options);
        CHECK_EQ(build.indexed, 1);
        CHECK_TRUE(index.TotalChars() <= 20);
        CHECK_EQ(static_cast<int>(index.Search("alphafirst").hits.size()), 1);
        CHECK_EQ(static_cast<int>(index.Search("zetafinal").hits.size()), 0);  // 被截掉
    }

    // maxTotalTextChars：第二本因额度用完被跳过
    {
        TempDir dir;
        const std::string path1 = dir.File("one.txt");
        const std::string path2 = dir.File("two.txt");
        WriteTextFile(path1, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");  // 30 个码点
        WriteTextFile(path2, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        std::vector<BookTextSource> sources;
        sources.push_back(BookTextSource{ 1, "One", "txt", path1, 0, 0 });
        sources.push_back(BookTextSource{ 2, "Two", "txt", path2, 0, 0 });

        SearchIndexService index;
        SearchBuildOptions options;
        options.maxTotalTextChars = 30;
        const SearchIndexBuildResult build = index.Build(sources, options);
        CHECK_EQ(build.indexed, 1);
        CHECK_EQ(build.skipped, 1);
        CHECK_EQ(static_cast<int>(index.BookCount()), 1);
        CHECK_TRUE(HasErrorEntry(build, "memory_limit"));
    }

    // SearchOptions::isCancelled：立即取消返回部分结果
    {
        SearchIndexService index;
        index.Build(lib.Sources());
        SearchOptions options;
        options.isCancelled = []() { return true; };
        const SearchResult result = index.Search("needle", options);
        CHECK_TRUE(result.cancelled);
        CHECK_EQ(static_cast<int>(result.hits.size()), 0);
    }
}

// ── 5) 持久化 round-trip ─────────────────────────────────────────────

void TestPersistence(const SampleLibrary& lib)
{
    TempDir dataDir;
    SearchIndexService index;
    index.SetDataDir(dataDir.path.u8string());
    CHECK_EQ(index.IndexFilePath(), dataDir.path.u8string() + "/search_index.json");

    const SearchIndexBuildResult build = index.Build(lib.Sources());
    CHECK_EQ(build.indexed, 4);
    const std::size_t termCount = index.TermCount();
    const std::size_t bookCount = index.BookCount();
    const std::size_t totalChars = index.TotalChars();
    const std::int64_t mtime = index.BookMtime(1);

    std::string error;
    CHECK_TRUE(index.Save(&error));
    CHECK_EQ(error, std::string(pb::search_index_codes::kOk));
    CHECK_TRUE(fs::exists(fs::u8path(index.IndexFilePath())));
    CHECK_TRUE(!fs::exists(fs::u8path(index.IndexFilePath() + ".tmp")));
    CHECK_TRUE(Contains(ReadTextFile(index.IndexFilePath()), "\"version\""));

    SearchIndexService loaded;
    loaded.SetDataDir(dataDir.path.u8string());
    CHECK_TRUE(loaded.Load(&error));
    CHECK_EQ(static_cast<int>(loaded.BookCount()), static_cast<int>(bookCount));
    CHECK_EQ(static_cast<int>(loaded.TermCount()), static_cast<int>(termCount));
    CHECK_EQ(static_cast<int>(loaded.TotalChars()), static_cast<int>(totalChars));
    CHECK_EQ(loaded.BookMtime(1), mtime);
    CHECK_EQ(static_cast<int>(loaded.Search("needle").hits.size()), 3);
    CHECK_EQ(static_cast<int>(loaded.Search("全文搜索").hits.size()), 3);
    CHECK_EQ(static_cast<int>(loaded.Search("alpha beta").hits.size()), 1);
    const SearchResult reloadedNeedle = loaded.Search("needle");
    const pb::SearchHit* hit = FindHit(reloadedNeedle, 1);
    CHECK_TRUE(hit != nullptr && Contains(hit->snippet, "needle"));

    // Load 没有索引文件：首次启动视为成功（空索引）
    {
        TempDir emptyDir;
        SearchIndexService fresh;
        fresh.SetDataDir(emptyDir.path.u8string());
        CHECK_TRUE(fresh.Load(&error));
        CHECK_EQ(static_cast<int>(fresh.BookCount()), 0);
    }

    // 未设 dataDir：Save / Load 都返回 no_data_dir
    {
        SearchIndexService noDir;
        CHECK_TRUE(!noDir.Save(&error));
        CHECK_EQ(error, std::string(pb::search_index_codes::kNoDataDir));
        CHECK_TRUE(!noDir.Load(&error));
        CHECK_EQ(error, std::string(pb::search_index_codes::kNoDataDir));
    }

    // 版本过新：拒绝且不清空现有索引
    {
        const std::string indexJson = ReadTextFile(index.IndexFilePath());
        const std::size_t versionPos = indexJson.find("\"version\"");
        CHECK_TRUE(versionPos != std::string::npos);
        std::string patched = indexJson;
        const std::size_t colon = patched.find(':', versionPos);
        const std::size_t comma = patched.find_first_of(",}", colon);
        patched.replace(colon + 1, comma - colon - 1, " 99");
        WriteTextFile(index.IndexFilePath(), patched);

        SearchIndexService worker;
        worker.SetDataDir(dataDir.path.u8string());
        CHECK_EQ(worker.Build(lib.Sources()).indexed, 4);  // 先塞点数据
        CHECK_TRUE(!worker.LoadFrom(index.IndexFilePath(), &error));
        CHECK_EQ(error, std::string(pb::search_index_codes::kVersion));
        CHECK_EQ(static_cast<int>(worker.BookCount()), 4);  // 旧索引保留
    }

    // 坏 JSON：拒绝
    {
        const std::string badPath = dataDir.File("bad.json");
        WriteTextFile(badPath, "{not json");
        SearchIndexService worker;
        CHECK_TRUE(!worker.LoadFrom(badPath, &error));
        CHECK_EQ(error, std::string(pb::search_index_codes::kParse));
    }
}

// ── 6) AddBook / RemoveBook ──────────────────────────────────────────

void TestAddRemove(const SampleLibrary& lib)
{
    SearchIndexService index;
    std::string error;

    BookTextSource book{ 42, "Added Book", "txt", lib.txtPath, 0, 0 };
    CHECK_TRUE(index.AddBook(book, SearchBuildOptions(), &error));
    CHECK_EQ(error, std::string(pb::search_index_codes::kOk));
    CHECK_EQ(static_cast<int>(index.BookCount()), 1);
    CHECK_EQ(static_cast<int>(index.Search("needle").hits.size()), 1);

    // 同一本书再 Add：文件未变 → 幂等成功，不重复
    CHECK_TRUE(index.AddBook(book, SearchBuildOptions(), &error));
    CHECK_EQ(static_cast<int>(index.BookCount()), 1);

    // PDF 明确不支持
    BookTextSource pdf{ 43, "PDF", "pdf", lib.dir.File("x.pdf"), 0, 0 };
    CHECK_TRUE(!index.AddBook(pdf, SearchBuildOptions(), &error));
    CHECK_EQ(error, std::string(pb::search_index_codes::kUnsupportedFormat));

    // 缺文件
    BookTextSource missing{ 44, "Missing", "txt", lib.dir.File("missing.txt"), 0, 0 };
    CHECK_TRUE(!index.AddBook(missing, SearchBuildOptions(), &error));
    CHECK_EQ(error, std::string(pb::search_index_codes::kFileMissing));

    // 删除
    CHECK_TRUE(index.RemoveBook(42));
    CHECK_EQ(static_cast<int>(index.BookCount()), 0);
    CHECK_EQ(static_cast<int>(index.Search("needle").hits.size()), 0);
    CHECK_TRUE(!index.RemoveBook(42));
}

// ── 7) 静态抽取工具 ──────────────────────────────────────────────────

void TestStaticHelpers(const SampleLibrary& lib)
{
    CHECK_EQ(SearchIndexService::NormalizeFormat("EPUB"), std::string("epub"));
    CHECK_EQ(SearchIndexService::NormalizeFormat(".Epub"), std::string("epub"));
    CHECK_EQ(SearchIndexService::NormalizeFormat("md"), std::string("markdown"));
    CHECK_EQ(SearchIndexService::NormalizeFormat("markdown"), std::string("markdown"));
    CHECK_EQ(SearchIndexService::NormalizeFormat("htm"), std::string("html"));
    CHECK_EQ(SearchIndexService::NormalizeFormat("pdf"), std::string("pdf"));
    CHECK_EQ(SearchIndexService::NormalizeFormat("cbz"), std::string("cbz"));
    CHECK_EQ(SearchIndexService::NormalizeFormat("weird"), std::string("weird"));

    // HtmlToText：标签 / script / style / 实体
    const std::string html = SearchIndexService::HtmlToText(
        "<html><head><style>.x{color:red}</style><script>var leak=1;</script></head>"
        "<body><h1>标题</h1><p>A &amp; B &lt;ok&gt; &nbsp; &#20320;&#22909;</p></body></html>");
    CHECK_TRUE(Contains(html, "标题"));
    CHECK_TRUE(Contains(html, "A & B <ok>"));
    CHECK_TRUE(Contains(html, "你好"));
    CHECK_TRUE(!Contains(html, "color:red"));
    CHECK_TRUE(!Contains(html, "var leak"));

    // MarkdownToText：标题 / 强调 / 链接 / 行内代码
    const std::string fence = "\x60\x60\x60";
    const std::string md = SearchIndexService::MarkdownToText(
        "# 标题\n\n**粗体** 与 \x60" "code" "\x60 与 [链接文字](https://example.com/x)。\n"
        + fence + "\nfenced content\n" + fence + "\n");
    CHECK_TRUE(Contains(md, "标题"));
    CHECK_TRUE(Contains(md, "粗体"));
    CHECK_TRUE(Contains(md, "链接文字"));
    CHECK_TRUE(Contains(md, "fenced content"));
    CHECK_TRUE(!Contains(md, "https://example.com"));

    // ExtractText：TXT / HTML / MD / EPUB
    std::string text;
    std::string error;
    CHECK_TRUE(SearchIndexService::ExtractText(lib.txtPath, "txt", 0, text, &error));
    CHECK_TRUE(Contains(text, "全文搜索"));
    CHECK_TRUE(Contains(text, "needle"));

    CHECK_TRUE(SearchIndexService::ExtractText(lib.htmlPath, "", 0, text, &error));
    CHECK_TRUE(Contains(text, "跨书检索"));
    CHECK_TRUE(!Contains(text, "color:red"));

    CHECK_TRUE(SearchIndexService::ExtractText(lib.mdPath, "markdown", 0, text, &error));
    CHECK_TRUE(Contains(text, "machine learning"));
    CHECK_TRUE(Contains(text, "zookeeper"));

    CHECK_TRUE(SearchIndexService::ExtractText(lib.epubPath, "epub", 0, text, &error));
    CHECK_TRUE(Contains(text, "第一章"));
    CHECK_TRUE(Contains(text, "中文短语"));
    CHECK_TRUE(Contains(text, "needle"));
    CHECK_TRUE(!Contains(text, "shouldNotIndex"));

    // maxChars 截断（码点）
    CHECK_TRUE(SearchIndexService::ExtractText(lib.txtPath, "txt", 10, text, &error));
    CHECK_TRUE(text.size() <= 10 * 4);

    // 缺文件 / 不支持格式
    CHECK_TRUE(!SearchIndexService::ExtractText(lib.dir.File("nope.txt"), "txt", 0, text, &error));
    CHECK_EQ(error, std::string(pb::search_index_codes::kFileMissing));
    CHECK_TRUE(!SearchIndexService::ExtractText(lib.dir.File("x.pdf"), "pdf", 0, text, &error));
    CHECK_EQ(error, std::string(pb::search_index_codes::kUnsupportedFormat));

    // UTF-16 LE BOM 的 TXT 也要能读
    const std::string utf16Path = lib.dir.File("utf16.txt");
    WriteTextFile(utf16Path, "UTF16 内容 needle", /*utf16leBom=*/true);
    CHECK_TRUE(SearchIndexService::ExtractText(utf16Path, "txt", 0, text, &error));
    CHECK_TRUE(Contains(text, "UTF16"));
    CHECK_TRUE(Contains(text, "needle"));

    // 坏 EPUB（普通文件冒充 zip）：报 epub_container_missing
    const std::string fakeEpub = lib.dir.File("fake.epub");
    WriteTextFile(fakeEpub, "not a zip");
    CHECK_TRUE(!SearchIndexService::ExtractText(fakeEpub, "epub", 0, text, &error));
    CHECK_EQ(error, std::string(pb::search_index_codes::kEpubContainer));

    // 非 UTF-8 文件走 ANSI 回退（只要求不失败；内容取决于系统代码页）
    const std::string ansiPath = lib.dir.File("ansi.txt");
    WriteTextFile(ansiPath, std::string("\xC4\xE3\xBA\xC3 ANSI", 10));
    (void)SearchIndexService::ExtractText(ansiPath, "txt", 0, text, &error);
}

// ── 8) 中文 bigram 连续性（专测）─────────────────────────────────────

void TestChinesePhraseContinuity()
{
    TempDir dir;
    const std::string joined = dir.File("joined.txt");
    const std::string split = dir.File("split.txt");
    WriteTextFile(joined, "前面一句 全文搜索 后面一句");
    WriteTextFile(split, "全文和搜索是分开的两处");

    std::vector<BookTextSource> sources;
    sources.push_back(BookTextSource{ 1, "Joined", "txt", joined, 0, 0 });
    sources.push_back(BookTextSource{ 2, "Split", "txt", split, 0, 0 });

    SearchIndexService index;
    index.Build(sources);

    const SearchResult phrase = index.Search("全文搜索");
    CHECK_EQ(static_cast<int>(phrase.hits.size()), 1);
    CHECK_TRUE(HasBookId(phrase, 1));

    const SearchResult full = index.Search("全文");
    CHECK_EQ(static_cast<int>(full.hits.size()), 2);
    const SearchResult searchWord = index.Search("搜索");
    CHECK_EQ(static_cast<int>(searchWord.hits.size()), 2);
}

} // namespace

int main()
{
    // 每个用例独立造样本：增量 / 取消 / 复用等用例会改写文件，共享样本会串味。
    { SampleLibrary library; TestBuildAndSearch(library); }
    { SampleLibrary library; TestIncremental(library); }
    { SampleLibrary library; TestReuseStampSemantics(library); }
    { SampleLibrary library; TestCancelAndLimits(library); }
    { SampleLibrary library; TestPersistence(library); }
    { SampleLibrary library; TestAddRemove(library); }
    { SampleLibrary library; TestStaticHelpers(library); }
    TestChinesePhraseContinuity();
    return pb_t::Summary("search_index_test");
}
