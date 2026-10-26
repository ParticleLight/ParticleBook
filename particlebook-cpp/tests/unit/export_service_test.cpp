// ── PB-004（A）：笔记 / 高亮导出核心单元测试 ─────────────────────────
// 不依赖 Win32 / 数据库 / 渲染层：链接 ExportService.cpp 即可（纯 std +
// nlohmann/json）。
//
// 覆盖（docs/BACKLOG.md PB-004 验收）：
//   1. Markdown / CSV / JSON 三格式快照（逐字节相等，exportedAt 固定）
//   2. 空数据（无标注的书、空库）三格式的输出形状
//   3. 特殊字符：逗号 / 双引号 / 换行 / 中文 / HTML 标签 / 控制字符
//   4. 排序：章节（TOC cfi 前缀 / href / 页码）→ 章内位置（偏移 / 页码 /
//      cfi）→ 时间 → id；未分章排最后
//   5. 全库合并：多本排序、空书过滤、目录锚点、统计累加
//   6. GroupByBook：按 book_id 分组、未知 book_id 记 issue
//   7. 文件名安全化：路径分隔符 / Windows 非法字符 / 设备名 / 前导点 /
//      UTF-8 截断；建议文件名带日期与扩展名
//   8. 选项：只导高亮/笔记/书签、不输出引用、无锚点、无 front matter、
//      BOM、公式注入防护、includeSourcePath（默认不泄漏本机路径）
//   9. 稳定性：同一输入两次导出字节相同；JSON 空字段也写出

#include "services/ExportService.h"

#include "nlohmann/json.hpp"
#include "utils/test_assert.h"

#include <cstdio>
#include <string>
#include <vector>

using nlohmann::json;
using pb::ExportBook;
using pb::ExportFormat;
using pb::ExportIssue;
using pb::ExportOptions;
using pb::ExportResult;
using pb::ExportService;
using pb::ExportStats;

namespace {

// ── 断言辅助 ─────────────────────────────────────────────────────────
bool Contains(const std::string& haystack, const std::string& needle)
{
    return haystack.find(needle) != std::string::npos;
}

int CountOf(const std::string& haystack, const std::string& needle)
{
    if (needle.empty()) return 0;
    int count = 0;
    size_t pos = 0;
    while ((pos = haystack.find(needle, pos)) != std::string::npos) {
        ++count;
        pos += needle.size();
    }
    return count;
}

bool HasIssue(const std::vector<ExportIssue>& issues, const std::string& code)
{
    for (const ExportIssue& issue : issues) {
        if (issue.code == code) return true;
    }
    return false;
}

// 严格 UTF-8：每个首字节声明的字节数都必须真的在串里。
bool IsWellFormedUtf8(const std::string& text)
{
    size_t i = 0;
    while (i < text.size()) {
        const unsigned char lead = static_cast<unsigned char>(text[i]);
        size_t length = 1;
        if (lead < 0x80) length = 1;
        else if ((lead & 0xE0) == 0xC0) length = 2;
        else if ((lead & 0xF0) == 0xE0) length = 3;
        else if ((lead & 0xF8) == 0xF0) length = 4;
        else return false;
        if (i + length > text.size()) return false;
        for (size_t k = 1; k < length; ++k) {
            const unsigned char continuation = static_cast<unsigned char>(text[i + k]);
            if ((continuation & 0xC0) != 0x80) return false;
        }
        i += length;
    }
    return true;
}

int CountLines(const std::string& text)
{
    if (text.empty()) return 0;
    int lines = 0;
    for (char c : text) {
        if (c == '\n') ++lines;
    }
    if (text.back() != '\n') ++lines;
    return lines;
}

// ── 夹具：与快照一致的书 ─────────────────────────────────────────────
ExportOptions SnapshotOptions(ExportFormat format)
{
    ExportOptions options;
    options.format = format;
    options.exportedAt = "2026-10-26T18:00:00.000Z";
    return options;
}

ExportBook DuneBook()
{
    ExportBook book;
    book.book = {
        { "id", 7 }, { "title", "Dune" }, { "author", "Frank Herbert" },
        { "format", "epub" }, { "language", "en" }
    };
    book.toc = json::array({
        { { "title", "Chapter One" }, { "cfi", "epubcfi(/6/4)" } },
        { { "title", "Chapter Two" }, { "cfi", "epubcfi(/6/10)" } },
    });
    book.highlights = json::array({
        { { "id", 1 }, { "book_id", 7 }, { "cfi", "epubcfi(/6/4!/4/2/1:0)" },
          { "text", "Fear is the mind-killer." }, { "color", "#fbbf24" },
          { "created_at", "2026-10-20T09:12:00.000Z" } },
        { { "id", 2 }, { "book_id", 7 }, { "cfi", "epubcfi(/6/10!/4/2/1:5)" },
          { "text", "The spice must flow." }, { "color", "#60a5fa" },
          { "note", "核心主题" }, { "created_at", "2026-10-21T10:00:00.000Z" } },
    });
    book.notes = json::array({
        { { "id", 3 }, { "book_id", 7 }, { "highlight_id", 1 },
          { "content", "Keep this line for TTS." },
          { "created_at", "2026-10-22T08:00:00.000Z" } },
    });
    book.bookmarks = json::array({
        { { "id", 4 }, { "book_id", 7 }, { "cfi", "epubcfi(/6/10!/4/2)" },
          { "title", "Spice" }, { "created_at", "2026-10-19T07:00:00.000Z" } },
    });
    return book;
}

// ── 1. Markdown 快照 ─────────────────────────────────────────────────
void TestMarkdownSnapshot()
{
    const ExportResult result = ExportService::ExportBook(DuneBook(), SnapshotOptions(ExportFormat::Markdown));

    const std::string expected = R"MDDOC(# Dune — 标注导出

- 作者：Frank Herbert
- 格式：EPUB
- 语言：en
- 标注：2 条高亮 · 1 条笔记 · 1 个书签
- 导出时间：2026-10-26T18:00:00.000Z

## 目录

- [Chapter One](#ch-1)
- [Chapter Two](#ch-2)

<a id="ch-1"></a>
## Chapter One

> Fear is the mind-killer.

- 颜色：#fbbf24
- 位置：epubcfi(/6/4!/4/2/1:0)
- 时间：2026-10-20T09:12:00.000Z
- 笔记：Keep this line for TTS.

<a id="ch-2"></a>
## Chapter Two

**书签**：Spice

- 位置：epubcfi(/6/10!/4/2)
- 时间：2026-10-19T07:00:00.000Z

> The spice must flow.

- 颜色：#60a5fa
- 位置：epubcfi(/6/10!/4/2/1:5)
- 时间：2026-10-21T10:00:00.000Z
- 笔记：核心主题
)MDDOC";

    CHECK_TRUE(result.ok);
    CHECK_TRUE(result.issues.empty());
    CHECK_TRUE(!result.HasErrors());
    CHECK_EQ(result.content, expected);
    CHECK_EQ(result.fileName, std::string("Dune-标注-20261026.md"));
    CHECK_EQ(result.mimeType, std::string("text/markdown; charset=utf-8"));
    CHECK_EQ(static_cast<int>(result.stats.highlights), 2);
    CHECK_EQ(static_cast<int>(result.stats.notes), 1);
    CHECK_EQ(static_cast<int>(result.stats.attachedNotes), 1);
    CHECK_EQ(static_cast<int>(result.stats.bookmarks), 1);
    CHECK_EQ(static_cast<int>(result.stats.entries), 3);
}

// ── 2. CSV 快照 ──────────────────────────────────────────────────────
void TestCsvSnapshot()
{
    const ExportResult result = ExportService::ExportBook(DuneBook(), SnapshotOptions(ExportFormat::Csv));

    const std::string expected =
        "book_id,book_title,author,format,type,chapter,position,page,progress,cfi,color,text,note,tags,created_at,updated_at,id,source\r\n"
        "7,Dune,Frank Herbert,epub,highlight,Chapter One,epubcfi(/6/4!/4/2/1:0),,,epubcfi(/6/4!/4/2/1:0),#fbbf24,Fear is the mind-killer.,Keep this line for TTS.,,2026-10-20T09:12:00.000Z,,1,\r\n"
        "7,Dune,Frank Herbert,epub,bookmark,Chapter Two,epubcfi(/6/10!/4/2),,,epubcfi(/6/10!/4/2),,,,,2026-10-19T07:00:00.000Z,,4,\r\n"
        "7,Dune,Frank Herbert,epub,highlight,Chapter Two,epubcfi(/6/10!/4/2/1:5),,,epubcfi(/6/10!/4/2/1:5),#60a5fa,The spice must flow.,核心主题,,2026-10-21T10:00:00.000Z,,2,\r\n";

    CHECK_TRUE(result.ok);
    CHECK_EQ(result.content, expected);
    CHECK_EQ(result.fileName, std::string("Dune-标注-20261026.csv"));
    CHECK_EQ(result.mimeType, std::string("text/csv; charset=utf-8"));
    CHECK_TRUE(result.content.find('\xEF') == std::string::npos);   // 默认无 BOM
}

// ── 3. JSON 快照（最小夹具，逐字节）──────────────────────────────────
void TestJsonSnapshot()
{
    ExportBook book;
    book.book = { { "id", 1 }, { "title", "T" }, { "format", "txt" } };
    book.highlights = json::array({
        { { "id", 2 }, { "cfi", "pos-100" }, { "text", "hello" }, { "color", "#eee" },
          { "created_at", "2026-01-02T03:04:05Z" } },
    });

    const ExportResult result = ExportService::ExportBook(book, SnapshotOptions(ExportFormat::Json));

    const std::string expected = R"JSONDOC({
  "books": [
    {
      "author": "",
      "chapters": [
        {
          "entries": [
            {
              "chapterIndex": -1,
              "chapterTitle": "",
              "color": "#eee",
              "createdAt": "2026-01-02T03:04:05Z",
              "id": 2,
              "note": "",
              "notes": [],
              "position": {
                "cfi": "pos-100",
                "display": "偏移 100",
                "href": "",
                "kind": "text",
                "offset": 100,
                "page": null,
                "progress": null
              },
              "tags": [],
              "text": "hello",
              "title": "",
              "type": "highlight",
              "updatedAt": ""
            }
          ],
          "index": -1,
          "title": "未分章"
        }
      ],
      "counts": {
        "attachedNotes": 0,
        "bookmarks": 0,
        "entries": 1,
        "highlights": 1,
        "notes": 0
      },
      "entries": [
        {
          "chapterIndex": -1,
          "chapterTitle": "",
          "color": "#eee",
          "createdAt": "2026-01-02T03:04:05Z",
          "id": 2,
          "note": "",
          "notes": [],
          "position": {
            "cfi": "pos-100",
            "display": "偏移 100",
            "href": "",
            "kind": "text",
            "offset": 100,
            "page": null,
            "progress": null
          },
          "tags": [],
          "text": "hello",
          "title": "",
          "type": "highlight",
          "updatedAt": ""
        }
      ],
      "format": "txt",
      "id": 1,
      "isbn": "",
      "language": "",
      "publisher": "",
      "source": "",
      "title": "T"
    }
  ],
  "counts": {
    "attachedNotes": 0,
    "bookmarks": 0,
    "books": 1,
    "entries": 1,
    "highlights": 1,
    "notes": 0
  },
  "exportedAt": "2026-10-26T18:00:00.000Z",
  "schema": "particlebook.export",
  "version": 1
}
)JSONDOC";

    CHECK_TRUE(result.ok);
    CHECK_EQ(result.content, expected);
    CHECK_EQ(result.fileName, std::string("T-标注-20261026.json"));
    CHECK_EQ(result.mimeType, std::string("application/json; charset=utf-8"));

    // 结构断言（与快照互为兜底）：能解析、键齐全。
    const json parsed = json::parse(result.content);
    CHECK_EQ(parsed["schema"].get<std::string>(), std::string("particlebook.export"));
    CHECK_EQ(parsed["version"].get<int>(), 1);
    CHECK_EQ(parsed["books"].size(), static_cast<size_t>(1));
    CHECK_EQ(parsed["books"][0]["title"].get<std::string>(), std::string("T"));
    CHECK_TRUE(parsed["books"][0]["entries"][0].contains("position"));
    CHECK_TRUE(parsed["books"][0]["entries"][0]["position"]["page"].is_null());
}

// ── 4. 空数据 ────────────────────────────────────────────────────────
void TestEmptyData()
{
    ExportBook empty;
    empty.book = { { "id", 42 }, { "title", "Empty Book" } };

    {
        const ExportResult md = ExportService::ExportBook(empty, SnapshotOptions(ExportFormat::Markdown));
        CHECK_TRUE(md.ok);
        CHECK_TRUE(Contains(md.content, "# Empty Book — 标注导出"));
        CHECK_TRUE(Contains(md.content, "（没有可导出的标注）"));
        CHECK_TRUE(!Contains(md.content, "## 目录"));
        CHECK_EQ(static_cast<int>(md.stats.entries), 0);
    }
    {
        const ExportResult csv = ExportService::ExportBook(empty, SnapshotOptions(ExportFormat::Csv));
        CHECK_TRUE(csv.ok);
        CHECK_EQ(CountLines(csv.content), 1);   // 只有表头
        CHECK_TRUE(Contains(csv.content, "book_id,book_title,author,format,type,chapter"));
    }
    {
        const ExportResult js = ExportService::ExportBook(empty, SnapshotOptions(ExportFormat::Json));
        CHECK_TRUE(js.ok);
        const json parsed = json::parse(js.content);
        CHECK_EQ(parsed["counts"]["entries"].get<int>(), 0);
        CHECK_EQ(parsed["counts"]["books"].get<int>(), 1);
        CHECK_TRUE(parsed["books"][0]["chapters"].empty());
        CHECK_TRUE(parsed["books"][0]["entries"].empty());
    }
    {
        const ExportResult md = ExportService::ExportLibrary({}, SnapshotOptions(ExportFormat::Markdown));
        CHECK_TRUE(md.ok);
        CHECK_TRUE(Contains(md.content, "# ParticleBook 标注导出"));
        CHECK_TRUE(Contains(md.content, "（没有可导出的标注）"));
        CHECK_EQ(static_cast<int>(md.stats.books), 0);
    }
    {
        const ExportResult csv = ExportService::ExportLibrary({}, SnapshotOptions(ExportFormat::Csv));
        CHECK_EQ(CountLines(csv.content), 1);
    }
    {
        const ExportResult js = ExportService::ExportLibrary({}, SnapshotOptions(ExportFormat::Json));
        const json parsed = json::parse(js.content);
        CHECK_EQ(parsed["counts"]["books"].get<int>(), 0);
        CHECK_TRUE(parsed["books"].empty());
    }
}

// ── 5. 特殊字符 / 转义 ───────────────────────────────────────────────
void TestSpecialCharacters()
{
    ExportBook book;
    book.book = { { "id", 9 }, { "title", "A, \"B\" <C>" }, { "author", "作者：某人" }, { "format", "pdf" } };
    book.highlights = json::array({
        { { "id", 11 }, { "page", 12 },
          { "text", "a,b \"quoted\"\nsecond line" },
          { "color", "#fff" },
          { "note", "<script>alert(1)</script>" },
          { "created_at", "2026-10-20T09:12:00Z" } },
    });

    // CSV：逗号/引号/换行 → 加引号 + 内部引号双写；换行保留在引号内。
    {
        ExportOptions options = SnapshotOptions(ExportFormat::Csv);
        const ExportResult csv = ExportService::ExportBook(book, options);
        CHECK_TRUE(Contains(csv.content, "\"A, \"\"B\"\" <C>\""));
        CHECK_TRUE(Contains(csv.content, "\"a,b \"\"quoted\"\"\nsecond line\""));
        CHECK_TRUE(Contains(csv.content, "<script>alert(1)</script>"));
        // 表头 + 1 条数据，但数据里的 \n 不算新行（在引号内）。
        CHECK_EQ(CountOf(csv.content, "\r\n"), 2);
    }

    // Markdown：小于号/大于号转义；引用逐行加 > 前缀。
    {
        ExportOptions options = SnapshotOptions(ExportFormat::Markdown);
        const ExportResult md = ExportService::ExportBook(book, options);
        CHECK_TRUE(Contains(md.content, "&lt;script&gt;alert(1)&lt;/script&gt;"));
        CHECK_TRUE(!Contains(md.content, "<script>"));
        CHECK_TRUE(Contains(md.content, "> a,b \"quoted\"\n> second line"));
        CHECK_TRUE(Contains(md.content, "- 笔记：&lt;script&gt;alert(1)&lt;/script&gt;"));
        // 标题里的非法字符不破坏 Markdown 结构：仍然只有一行标题。
        CHECK_TRUE(Contains(md.content, "# A, \"B\" &lt;C&gt; — 标注导出"));
    }
    // escape 关掉时保留原样。
    {
        ExportOptions options = SnapshotOptions(ExportFormat::Markdown);
        options.markdownEscapeHtml = false;
        const ExportResult md = ExportService::ExportBook(book, options);
        CHECK_TRUE(Contains(md.content, "<script>alert(1)</script>"));
    }
    // JSON：数据保真（由 JSON 序列化器负责转义）。
    {
        ExportOptions options = SnapshotOptions(ExportFormat::Json);
        const ExportResult js = ExportService::ExportBook(book, options);
        const json parsed = json::parse(js.content);
        CHECK_EQ(parsed["books"][0]["entries"][0]["text"].get<std::string>(),
                 std::string("a,b \"quoted\"\nsecond line"));
        CHECK_EQ(parsed["books"][0]["entries"][0]["note"].get<std::string>(),
                 std::string("<script>alert(1)</script>"));
        CHECK_EQ(parsed["books"][0]["title"].get<std::string>(), std::string("A, \"B\" <C>"));
    }

    // 控制字符被剔除，CRLF 归一。
    {
        ExportBook dirty;
        dirty.book = { { "id", 1 }, { "title", "Dirty" } };
        dirty.highlights = json::array({
            { { "id", 5 }, { "text", std::string("a\x01") + "b\r\nc" } },
        });
        const ExportResult js = ExportService::ExportBook(dirty, SnapshotOptions(ExportFormat::Json));
        const json parsed = json::parse(js.content);
        CHECK_EQ(parsed["books"][0]["entries"][0]["text"].get<std::string>(), std::string("ab\nc"));
    }
}

// ── 6. 排序与章节 ────────────────────────────────────────────────────
void TestSortingAndChapters()
{
    // 文本偏移：乱序输入 → 20 / 99 / 500。
    {
        ExportBook book;
        book.book = { { "id", 2 }, { "title", "Text" }, { "format", "txt" } };
        book.highlights = json::array({
            { { "id", 10 }, { "cfi", "pos-500" }, { "text", "later" } },
            { { "id", 11 }, { "cfi", "pos-20" }, { "text", "earlier" } },
            { { "id", 12 }, { "cfi", "pos-99" }, { "text", "middle" } },
        });
        const ExportResult md = ExportService::ExportBook(book, SnapshotOptions(ExportFormat::Markdown));
        const size_t earlier = md.content.find("earlier");
        const size_t middle = md.content.find("middle");
        const size_t later = md.content.find("later");
        CHECK_TRUE(earlier != std::string::npos && middle != std::string::npos && later != std::string::npos);
        CHECK_TRUE(earlier < middle && middle < later);
        CHECK_TRUE(Contains(md.content, "偏移 20"));
    }
    // PDF：TOC 页码范围 + 章内按页码排序。
    {
        ExportBook book;
        book.book = { { "id", 3 }, { "title", "PDF Book" }, { "format", "pdf" } };
        book.toc = json::array({
            { { "title", "Front" }, { "page", 1 } },
            { { "title", "Body" }, { "page", 10 } },
        });
        book.highlights = json::array({
            { { "id", 20 }, { "page", 30 }, { "text", "thirty" } },
            { { "id", 21 }, { "page", 5 }, { "text", "five" } },
            { { "id", 22 }, { "page", 12 }, { "text", "twelve" } },
        });
        const ExportResult md = ExportService::ExportBook(book, SnapshotOptions(ExportFormat::Markdown));
        const size_t front = md.content.find("## Front");
        const size_t body = md.content.find("## Body");
        CHECK_TRUE(front != std::string::npos && body != std::string::npos);
        CHECK_TRUE(front < body);
        CHECK_TRUE(md.content.find("five") < body);            // page 5 → Front
        CHECK_TRUE(md.content.find("twelve") > body);          // page 12/30 → Body
        CHECK_TRUE(md.content.find("twelve") < md.content.find("thirty"));
        CHECK_TRUE(Contains(md.content, "第 5 页"));
    }
    // EPUB：TOC cfi 前缀最长匹配。
    {
        ExportBook book;
        book.book = { { "id", 4 }, { "title", "Epub Book" }, { "format", "epub" } };
        book.toc = json::array({
            { { "title", "Part A" }, { "cfi", "epubcfi(/6/4)" } },
            { { "title", "Part B" }, { "cfi", "epubcfi(/6/10)" } },
        });
        book.highlights = json::array({
            { { "id", 31 }, { "cfi", "epubcfi(/6/10!/4/2)" }, { "text", "in b" } },
            { { "id", 32 }, { "cfi", "epubcfi(/6/4!/4/2)" }, { "text", "in a" } },
            { { "id", 33 }, { "cfi", "epubcfi(/6/99!/4/2)" }, { "text", "orphan" } },
        });
        const ExportResult md = ExportService::ExportBook(book, SnapshotOptions(ExportFormat::Markdown));
        const size_t partA = md.content.find("## Part A");
        const size_t partB = md.content.find("## Part B");
        const size_t orphan = md.content.find("## 未分章");
        CHECK_TRUE(partA < md.content.find("in a"));
        CHECK_TRUE(md.content.find("in a") < partB);
        CHECK_TRUE(partB < md.content.find("in b"));
        CHECK_TRUE(orphan != std::string::npos);
        CHECK_TRUE(md.content.find("in b") < orphan);
    }
    // HTML：TOC href 匹配（去掉 #fragment）。
    {
        ExportBook book;
        book.book = { { "id", 5 }, { "title", "HTML Book" }, { "format", "html" } };
        book.toc = json::array({
            { { "title", "Sec 1" }, { "href", "ch01.xhtml" } },
            { { "title", "Sec 2" }, { "href", "ch02.xhtml#top" } },
        });
        book.highlights = json::array({
            { { "id", 30 }, { "cfi", "ch02.xhtml#sec3" }, { "text", "html two" } },
            { { "id", 31 }, { "cfi", "ch01.xhtml#sec1" }, { "text", "html one" } },
        });
        const ExportResult md = ExportService::ExportBook(book, SnapshotOptions(ExportFormat::Markdown));
        CHECK_TRUE(md.content.find("html one") < md.content.find("## Sec 2"));
        CHECK_TRUE(md.content.find("## Sec 1") < md.content.find("html one"));
        CHECK_TRUE(Contains(md.content, "ch02.xhtml#sec3"));
    }
    // 行自带 chapter / chapter_index 优先。
    {
        ExportBook book;
        book.book = { { "id", 6 }, { "title", "Custom" } };
        book.toc = json::array({ { { "title", "第一章" }, { "page", 1 } }, { { "title", "第二章" }, { "page", 20 } } });
        book.highlights = json::array({
            { { "id", 1 }, { "chapter_index", 1 }, { "page", 2 }, { "text", "forced second" } },
            { { "id", 2 }, { "chapter", "第一章" }, { "page", 3 }, { "text", "forced first" } },
        });
        const ExportResult md = ExportService::ExportBook(book, SnapshotOptions(ExportFormat::Markdown));
        CHECK_TRUE(md.content.find("forced first") < md.content.find("forced second"));
    }
}

// ── 7. 全库合并 ──────────────────────────────────────────────────────
void TestLibraryMerge()
{
    ExportBook alpha;
    alpha.book = { { "id", 5 }, { "title", "Alpha" }, { "author", "Ann" } };
    alpha.highlights = json::array({ { { "id", 40 }, { "page", 2 }, { "text", "alpha line" } } });

    ExportBook beta;   // 无标注
    beta.book = { { "id", 6 }, { "title", "Beta" } };

    ExportBook zeta;
    zeta.book = { { "id", 7 }, { "title", "zeta" } };
    zeta.highlights = json::array({ { { "id", 41 }, { "page", 3 }, { "text", "zeta line" } } });

    const std::vector<ExportBook> books = { zeta, alpha, beta };

    {
        const ExportResult md = ExportService::ExportLibrary(books, SnapshotOptions(ExportFormat::Markdown));
        CHECK_TRUE(md.ok);
        CHECK_EQ(static_cast<int>(md.stats.books), 2);   // Beta 被跳过
        CHECK_EQ(static_cast<int>(md.stats.highlights), 2);
        CHECK_EQ(md.fileName, std::string("ParticleBook-标注导出-20261026.md"));
        CHECK_TRUE(md.content.find("Alpha") < md.content.find("zeta"));
        CHECK_TRUE(!Contains(md.content, "Beta"));
        CHECK_TRUE(Contains(md.content, "<a id=\"book-1\"></a>"));
        CHECK_TRUE(Contains(md.content, "<a id=\"b1-ch-1\"></a>"));
        CHECK_TRUE(Contains(md.content, "## 目录"));
        CHECK_TRUE(Contains(md.content, "- [Alpha](#book-1) — Ann"));
    }
    {
        ExportOptions options = SnapshotOptions(ExportFormat::Markdown);
        options.includeEmptyBooks = true;
        const ExportResult md = ExportService::ExportLibrary(books, options);
        CHECK_EQ(static_cast<int>(md.stats.books), 3);
        CHECK_TRUE(Contains(md.content, "## Beta"));
        CHECK_TRUE(Contains(md.content, "（没有可导出的标注）"));
    }
    {
        const ExportResult csv = ExportService::ExportLibrary(books, SnapshotOptions(ExportFormat::Csv));
        CHECK_EQ(CountLines(csv.content), 3);   // 表头 + 2 行
        CHECK_TRUE(Contains(csv.content, "Alpha"));
        CHECK_TRUE(Contains(csv.content, "zeta"));
        CHECK_TRUE(!Contains(csv.content, "Beta"));
    }
    {
        const ExportResult js = ExportService::ExportLibrary(books, SnapshotOptions(ExportFormat::Json));
        const json parsed = json::parse(js.content);
        CHECK_EQ(parsed["counts"]["books"].get<int>(), 2);
        CHECK_EQ(parsed["counts"]["highlights"].get<int>(), 2);
        CHECK_EQ(parsed["books"].size(), static_cast<size_t>(2));
        CHECK_EQ(parsed["books"][0]["title"].get<std::string>(), std::string("Alpha"));
        CHECK_EQ(parsed["books"][1]["title"].get<std::string>(), std::string("zeta"));
    }
}

// ── 8. GroupByBook ───────────────────────────────────────────────────
void TestGroupByBook()
{
    const json books = json::array({
        { { "id", 1 }, { "title", "One" }, { "toc", json::array({ { { "title", "Ch" }, { "page", 1 } } }) } },
        { { "id", 2 }, { "title", "Two" } },
    });
    const json highlights = json::array({
        { { "id", 10 }, { "book_id", 1 }, { "page", 3 }, { "text", "one hl" } },
        { { "id", 11 }, { "book_id", 2 }, { "page", 4 }, { "text", "two hl" } },
        { { "id", 12 }, { "book_id", 99 }, { "page", 5 }, { "text", "orphan" } },
        "not-an-object",
    });
    const json notes = json::array({
        { { "id", 20 }, { "book_id", 1 }, { "content", "one note" } },
    });
    const json bookmarks = json::array({
        { { "id", 30 }, { "book_id", 2 }, { "page", 6 }, { "title", "two bm" } },
    });

    std::vector<ExportIssue> issues;
    const std::vector<ExportBook> grouped =
        ExportService::GroupByBook(books, bookmarks, highlights, notes, &issues);

    CHECK_EQ(static_cast<int>(grouped.size()), 2);
    CHECK_EQ(static_cast<int>(grouped[0].highlights.size()), 1);
    CHECK_EQ(static_cast<int>(grouped[0].notes.size()), 1);
    CHECK_EQ(static_cast<int>(grouped[1].highlights.size()), 1);
    CHECK_EQ(static_cast<int>(grouped[1].bookmarks.size()), 1);
    CHECK_TRUE(HasIssue(issues, "skip"));   // book_id 99
    CHECK_TRUE(HasIssue(issues, "row"));    // 非对象行

    // 分组结果可以直接进全库导出。
    const ExportResult md = ExportService::ExportLibrary(grouped, SnapshotOptions(ExportFormat::Markdown));
    CHECK_EQ(static_cast<int>(md.stats.books), 2);
    CHECK_TRUE(Contains(md.content, "one hl"));
    CHECK_TRUE(Contains(md.content, "two bm"));
    CHECK_TRUE(!Contains(md.content, "orphan"));
}

// ── 9. 文件名安全化 ──────────────────────────────────────────────────
void TestFileNames()
{
    CHECK_EQ(ExportService::SafeFileName("Dune"), std::string("Dune"));
    CHECK_EQ(ExportService::SafeFileName("a/b\\c:d*e?f\"g<h>i|j"), std::string("a_b_c_d_e_f_g_h_i_j"));
    CHECK_EQ(ExportService::SafeFileName("  ..  "), std::string("export"));
    CHECK_EQ(ExportService::SafeFileName(""), std::string("export"));
    CHECK_EQ(ExportService::SafeFileName("CON"), std::string("_CON"));
    CHECK_EQ(ExportService::SafeFileName("nul.txt"), std::string("_nul.txt"));
    CHECK_EQ(ExportService::SafeFileName("com7"), std::string("_com7"));
    CHECK_EQ(ExportService::SafeFileName(".hidden"), std::string("hidden"));
    CHECK_EQ(ExportService::SafeFileName("trailing."), std::string("trailing"));

    const std::string traversal = ExportService::SafeFileName("../../etc/passwd");
    CHECK_TRUE(traversal.find('/') == std::string::npos);
    CHECK_TRUE(traversal.find('\\') == std::string::npos);
    CHECK_TRUE(traversal.front() != '.');

    // 长中文按码点截断：不超过上限，且不切坏 UTF-8。
    std::string longCjk;
    for (int i = 0; i < 40; ++i) longCjk += "中文书名";
    const std::string truncated = ExportService::SafeFileName(longCjk, "fallback", 24);
    CHECK_TRUE(truncated.size() <= 24);
    CHECK_TRUE(IsWellFormedUtf8(truncated));
    CHECK_TRUE(!truncated.empty());

    // 建议文件名：日期 + 扩展名；标题里的非法字符被替换。
    ExportBook book;
    book.book = { { "id", 1 }, { "title", "../../etc/passwd" } };
    const std::string name = ExportService::SuggestedFileName(book, SnapshotOptions(ExportFormat::Markdown));
    CHECK_TRUE(name.size() > 8);
    CHECK_EQ(name.substr(name.size() - 3), std::string(".md"));
    CHECK_TRUE(name.find('/') == std::string::npos);
    CHECK_TRUE(Contains(name, "20261026"));
    CHECK_TRUE(IsWellFormedUtf8(name));

    ExportBook untitled;
    untitled.book = { { "id", 2 } };
    const std::string untitledName = ExportService::SuggestedFileName(untitled, SnapshotOptions(ExportFormat::Csv));
    CHECK_TRUE(Contains(untitledName, "未命名书籍"));
    CHECK_EQ(untitledName.substr(untitledName.size() - 4), std::string(".csv"));

    const std::string libraryName = ExportService::LibraryFileName(SnapshotOptions(ExportFormat::Json));
    CHECK_EQ(libraryName, std::string("ParticleBook-标注导出-20261026.json"));
}

// ── 10. 选项 ─────────────────────────────────────────────────────────
void TestOptions()
{
    ExportBook book = DuneBook();
    book.book["file_path"] = "C:\\Users\\secret\\Books\\Dune.epub";
    book.book["cover_path"] = "C:\\Users\\secret\\covers\\dune.jpg";

    // 默认不泄漏本机路径。
    {
        ExportOptions options = SnapshotOptions(ExportFormat::Json);
        const ExportResult js = ExportService::ExportBook(book, options);
        CHECK_TRUE(!Contains(js.content, "secret"));
        CHECK_TRUE(!Contains(js.content, "C:\\\\Users"));
        const json parsed = json::parse(js.content);
        CHECK_EQ(parsed["books"][0]["source"].get<std::string>(), std::string());
    }
    {
        ExportOptions options = SnapshotOptions(ExportFormat::Markdown);
        const ExportResult md = ExportService::ExportBook(book, options);
        CHECK_TRUE(!Contains(md.content, "secret"));
    }
    // 显式打开后才写出来。
    {
        ExportOptions options = SnapshotOptions(ExportFormat::Markdown);
        options.includeSourcePath = true;
        const ExportResult md = ExportService::ExportBook(book, options);
        CHECK_TRUE(Contains(md.content, "- 源文件：C:\\Users\\secret\\Books\\Dune.epub"));
    }

    // 只导书签：高亮与笔记都不输出。
    {
        ExportOptions options = SnapshotOptions(ExportFormat::Markdown);
        options.includeHighlights = false;
        options.includeNotes = false;
        const ExportResult md = ExportService::ExportBook(DuneBook(), options);
        CHECK_TRUE(!Contains(md.content, "Fear is the mind-killer"));
        CHECK_TRUE(!Contains(md.content, "核心主题"));
        CHECK_TRUE(Contains(md.content, "**书签**：Spice"));
        CHECK_EQ(static_cast<int>(md.stats.highlights), 0);
        CHECK_EQ(static_cast<int>(md.stats.bookmarks), 1);
    }
    // 不输出引用原文，但保留颜色/笔记。
    {
        ExportOptions options = SnapshotOptions(ExportFormat::Markdown);
        options.includeQuote = false;
        const ExportResult md = ExportService::ExportBook(DuneBook(), options);
        CHECK_TRUE(!Contains(md.content, "Fear is the mind-killer"));
        CHECK_TRUE(Contains(md.content, "- 颜色：#fbbf24"));
        CHECK_TRUE(Contains(md.content, "- 笔记：Keep this line for TTS."));
    }
    // 关锚点 / 关 front matter。
    {
        ExportOptions options = SnapshotOptions(ExportFormat::Markdown);
        options.markdownAnchors = false;
        const ExportResult md = ExportService::ExportBook(DuneBook(), options);
        CHECK_TRUE(!Contains(md.content, "<a id="));
        CHECK_TRUE(!Contains(md.content, "## 目录"));
        CHECK_TRUE(Contains(md.content, "## Chapter One"));
    }
    {
        ExportOptions options = SnapshotOptions(ExportFormat::Markdown);
        options.includeFrontMatter = false;
        const ExportResult md = ExportService::ExportBook(DuneBook(), options);
        CHECK_TRUE(!Contains(md.content, "标注导出"));
        CHECK_TRUE(!Contains(md.content, "导出时间"));
        CHECK_TRUE(Contains(md.content, "## Chapter One"));
    }
    // BOM + 公式注入防护。
    {
        ExportOptions options = SnapshotOptions(ExportFormat::Csv);
        options.csvBom = true;
        const ExportResult csv = ExportService::ExportBook(DuneBook(), options);
        CHECK_TRUE(csv.content.size() > 3);
        CHECK_EQ(static_cast<unsigned char>(csv.content[0]), static_cast<unsigned char>(0xEF));
        CHECK_EQ(static_cast<unsigned char>(csv.content[1]), static_cast<unsigned char>(0xBB));
        CHECK_EQ(static_cast<unsigned char>(csv.content[2]), static_cast<unsigned char>(0xBF));
    }
    {
        ExportBook formula;
        formula.book = { { "id", 8 }, { "title", "F" } };
        formula.highlights = json::array({ { { "id", 50 }, { "page", 1 }, { "text", "=SUM(A1)" } } });
        ExportOptions options = SnapshotOptions(ExportFormat::Csv);
        const ExportResult plain = ExportService::ExportBook(formula, options);
        CHECK_TRUE(Contains(plain.content, "=SUM(A1)"));
        CHECK_TRUE(!Contains(plain.content, "'=SUM(A1)"));
        options.csvFormulaGuard = true;
        const ExportResult guarded = ExportService::ExportBook(formula, options);
        CHECK_TRUE(Contains(guarded.content, "'=SUM(A1)"));
    }
    // titleOverride 影响标题与文件名。
    {
        ExportOptions options = SnapshotOptions(ExportFormat::Markdown);
        options.titleOverride = "自定义书名";
        const ExportResult md = ExportService::ExportBook(DuneBook(), options);
        CHECK_TRUE(Contains(md.content, "# 自定义书名 — 标注导出"));
        CHECK_TRUE(Contains(md.fileName, "自定义书名"));
    }
    // includeTags 开关。
    {
        ExportBook tagged = DuneBook();
        tagged.highlights[0]["tags"] = json::array({ "theme", "opening" });
        ExportOptions options = SnapshotOptions(ExportFormat::Markdown);
        const ExportResult withTags = ExportService::ExportBook(tagged, options);
        CHECK_TRUE(Contains(withTags.content, "- 标签：theme, opening"));
        options.includeTags = false;
        const ExportResult noTags = ExportService::ExportBook(tagged, options);
        CHECK_TRUE(!Contains(noTags.content, "- 标签："));
    }
}

// ── 11. 格式解析 / 时间 / 稳定性 ─────────────────────────────────────
void TestFormatsAndStability()
{
    ExportFormat format = ExportFormat::Json;
    CHECK_TRUE(pb::ParseExportFormat("md", format));
    CHECK_TRUE(format == ExportFormat::Markdown);
    CHECK_TRUE(pb::ParseExportFormat("MARKDOWN", format));
    CHECK_TRUE(format == ExportFormat::Markdown);
    CHECK_TRUE(pb::ParseExportFormat(" csv ", format));
    CHECK_TRUE(format == ExportFormat::Csv);
    CHECK_TRUE(pb::ParseExportFormat("txt", format));
    CHECK_TRUE(format == ExportFormat::Csv);
    CHECK_TRUE(pb::ParseExportFormat("json", format));
    CHECK_TRUE(format == ExportFormat::Json);
    CHECK_TRUE(!pb::ParseExportFormat("pdf", format));

    CHECK_EQ(std::string(pb::ExportFormatName(ExportFormat::Csv)), std::string("csv"));
    CHECK_EQ(std::string(pb::ExportFileExtension(ExportFormat::Json)), std::string(".json"));
    CHECK_EQ(std::string(pb::ExportMimeType(ExportFormat::Markdown)), std::string("text/markdown; charset=utf-8"));

    const std::string now = ExportService::NowIso8601Utc();
    CHECK_EQ(now.size(), static_cast<size_t>(24));
    CHECK_EQ(now.substr(now.size() - 5), std::string(".000Z"));
    CHECK_TRUE(now[4] == '-' && now[7] == '-' && now[10] == 'T');

    // 同一输入两次导出字节相同（nlohmann::json 键序 / 排序都确定）。
    for (ExportFormat f : { ExportFormat::Markdown, ExportFormat::Csv, ExportFormat::Json }) {
        const ExportOptions options = SnapshotOptions(f);
        const ExportResult first = ExportService::ExportBook(DuneBook(), options);
        const ExportResult second = ExportService::ExportBook(DuneBook(), options);
        CHECK_EQ(first.content, second.content);
        CHECK_EQ(first.fileName, second.fileName);
    }
    // JSON 空字段不省略。
    {
        const ExportResult js = ExportService::ExportBook(DuneBook(), SnapshotOptions(ExportFormat::Json));
        CHECK_TRUE(Contains(js.content, "\"tags\": []"));
        CHECK_TRUE(Contains(js.content, "\"isbn\": \"\""));
        CHECK_TRUE(Contains(js.content, "\"source\": \"\""));
        CHECK_TRUE(Contains(js.content, "\"notes\": []"));
    }
}

} // namespace

int main()
{
    TestMarkdownSnapshot();
    TestCsvSnapshot();
    TestJsonSnapshot();
    TestEmptyData();
    TestSpecialCharacters();
    TestSortingAndChapters();
    TestLibraryMerge();
    TestGroupByBook();
    TestFileNames();
    TestOptions();
    TestFormatsAndStability();
    return pb_t::Summary("export_service_test");
}
