#include "services/DatabaseService.h"
#include "utils/test_assert.h"

#include <filesystem>
#include <fstream>
#include <string>
#include <stdexcept>

namespace fs = std::filesystem;

// Each case runs in its own scratch dir so cases cannot interfere and the
// real %APPDATA% database is never touched.
static fs::path MakeScratch(const char* name)
{
    fs::path d = fs::temp_directory_path() / "pb_db_test" / name;
    std::error_code ec;
    fs::remove_all(d, ec);
    fs::create_directories(d, ec);
    return d;
}

static int CountPrefixed(const fs::path& dir, const std::string& prefix)
{
    int n = 0;
    std::error_code ec;
    for (auto& e : fs::directory_iterator(dir, ec)) {
        const std::string name = e.path().filename().string();
        if (name.rfind(prefix, 0) == 0) ++n;
    }
    return n;
}

static void WriteText(const fs::path& p, const std::string& s)
{
    std::ofstream f(p, std::ios::binary | std::ios::trunc);
    f << s;
}

int main()
{
    // ── 1. Missing file -> defaults, no crash ──────────────────────────
    {
        auto dir = MakeScratch("defaults");
        {
            DatabaseService db;
            db.Load((dir / "db.json").string());
            CHECK_EQ((int)db.GetBooks().size(), 0);
            CHECK_TRUE(db.GetBooks().is_array());
            CHECK_TRUE(db.GetSettings().is_object());
            CHECK_EQ((int)db.GetBookshelves().size(), 0);
            CHECK_EQ((int)db.GetBookSources().size(), 0);
            CHECK_TRUE(db.GetDownloads().is_array());
            CHECK_EQ((int)db.GetDownloads().size(), 0);
            CHECK_EQ(db.GetSchemaVersion(), DatabaseService::kCurrentSchemaVersion);
        }
        fs::remove_all(dir);
    }

    // ── 2. Corrupt JSON -> backup created + data reset ─────────────────
    {
        auto dir = MakeScratch("corrupt");
        auto dbPath = dir / "db.json";
        WriteText(dbPath, "{ this is not valid json ");
        {
            DatabaseService db;
            db.Load(dbPath.string());
            CHECK_EQ((int)db.GetBooks().size(), 0);
        }
        CHECK_EQ(CountPrefixed(dir, "db.json.corrupt-"), 1);
        fs::remove_all(dir);
    }

    // ── 3. Valid JSON that is not an object -> backup + reset ──────────
    // Guards the string-key operator[] path from crashing on an array.
    {
        auto dir = MakeScratch("nonobject");
        auto dbPath = dir / "db.json";
        WriteText(dbPath, "[1, 2, 3]");
        {
            DatabaseService db;
            db.Load(dbPath.string());
            CHECK_EQ((int)db.GetBooks().size(), 0);
        }
        CHECK_EQ(CountPrefixed(dir, "db.json.corrupt-"), 1);
        fs::remove_all(dir);
    }

    // ── 4. Persist + reload + no temp leftovers ────────────────────────
    {
        auto dir = MakeScratch("persist");
        auto dbPath = dir / "db.json";
        {
            DatabaseService db;
            db.Load(dbPath.string());
            json b; b["title"] = "Test Book"; b["file_path"] = "C:/x.epub";
            db.InsertBook(b);
            db.FlushSync();

            CHECK_TRUE(fs::exists(dbPath));
            std::ifstream f(dbPath);
            json parsed = json::parse(f);
            CHECK_TRUE(parsed.is_object());
            CHECK_EQ((int)parsed["books"].size(), 1);
            CHECK_EQ(CountPrefixed(dir, "db.json.tmp."), 0);
        }
        {
            DatabaseService db;
            db.Load(dbPath.string());
            CHECK_EQ((int)db.GetBooks().size(), 1);
            CHECK_TRUE(db.GetBooks()[0]["title"] == "Test Book");
        }
        fs::remove_all(dir);
    }

    // ── 5. Settings merge persists across reload ───────────────────────
    {
        auto dir = MakeScratch("settings");
        auto dbPath = dir / "db.json";
        {
            DatabaseService db;
            db.Load(dbPath.string());
            json s; s["theme"] = "dark"; s["fontSize"] = 18;
            db.UpdateSettings(s);
            db.FlushSync();
        }
        {
            DatabaseService db;
            db.Load(dbPath.string());
            CHECK_EQ(db.GetSettings()["theme"].get<std::string>(), "dark");
            CHECK_EQ(db.GetSettings()["fontSize"].get<int>(), 18);
        }
        fs::remove_all(dir);
    }

    // ── 6. ID allocation: unique, and never reused after reload ────────
    // Regression guard: ids must keep advancing across restarts.
    {
        auto dir = MakeScratch("ids");
        auto dbPath = dir / "db.json";
        int lastId = 0;
        {
            DatabaseService db;
            db.Load(dbPath.string());
            for (int i = 0; i < 5; ++i) {
                json b; b["title"] = "B" + std::to_string(i);
                json r = db.InsertBook(b);
                lastId = r["id"].get<int>();
            }
            db.FlushSync();
            CHECK_EQ((int)db.GetBooks().size(), 5);
        }
        {
            DatabaseService db;
            db.Load(dbPath.string());
            json b; b["title"] = "after-reload";
            json r = db.InsertBook(b);
            CHECK_TRUE(r["id"].get<int>() > lastId);
        }
        fs::remove_all(dir);
    }

    // ── 7. Bulk round-trip: no truncation on large writes ──────────────
    {
        auto dir = MakeScratch("bulk");
        auto dbPath = dir / "db.json";
        const int N = 300;
        {
            DatabaseService db;
            db.Load(dbPath.string());
            for (int i = 0; i < N; ++i) {
                json b; b["title"] = "Bulk " + std::to_string(i);
                b["file_path"] = "C:/books/" + std::to_string(i) + ".epub";
                db.InsertBook(b);
            }
            db.FlushSync();
        }
        {
            DatabaseService db;
            db.Load(dbPath.string());
            CHECK_EQ((int)db.GetBooks().size(), N);
        }
        fs::remove_all(dir);
    }

    // ── 删一本书不得动到其它书的阅读进度 ──────────────────────────────
    // reading_progress 存的是【以 bookId 为键的 object】，对它用 std::remove_if
    // 会"只搬值、不搬键"，随后按范围 erase 会整条删掉末尾若干条 —— 表现为删 A 书
    // 顺带清掉 C 书的进度。此用例专门守住这个回归。
    {
        auto dir = MakeScratch("cascade-progress");
        auto dbPath = dir / "db.json";
        {
            DatabaseService db;
            db.Load(dbPath.string());

            json a; a["title"] = "A"; a["file_path"] = "C:/a.epub";
            json b; b["title"] = "B"; b["file_path"] = "C:/b.epub";
            json c; c["title"] = "C"; c["file_path"] = "C:/c.epub";
            const int idA = db.InsertBook(a).value("id", -1);
            const int idB = db.InsertBook(b).value("id", -1);
            const int idC = db.InsertBook(c).value("id", -1);
            CHECK_TRUE(idA > 0 && idB > 0 && idC > 0);

            json pa; pa["progress"] = 11.0;
            json pb; pb["progress"] = 22.0;
            json pc; pc["progress"] = 33.0;
            db.UpsertProgress(idA, pa);
            db.UpsertProgress(idB, pb);
            db.UpsertProgress(idC, pc);

            db.DeleteBook(idB);

            // 三本书的 id 递增，删中间那本 —— 正是旧实现会串位/误删的场景
            CHECK_EQ((int)db.GetProgress(idA).value("progress", -1.0), 11);
            CHECK_EQ((int)db.GetProgress(idC).value("progress", -1.0), 33);
            CHECK_TRUE(db.GetProgress(idB).is_null());
            CHECK_EQ((int)db.GetBooks().size(), 2);
        }
        fs::remove_all(dir);
    }

    // ── 8. v1 (unversioned) DB migrates to current + pre-migrate backup ──
    {
        auto dir = MakeScratch("schema-v1");
        auto dbPath = dir / "db.json";
        WriteText(dbPath, R"({"books":[{"id":1,"title":"Legacy"}],"nextId":2,"settings":{"theme":"dark"}})");
        {
            DatabaseService db;
            db.Load(dbPath.string());
            CHECK_EQ(db.GetSchemaVersion(), DatabaseService::kCurrentSchemaVersion);
            CHECK_EQ((int)db.GetBooks().size(), 1);
            CHECK_EQ(db.GetSettings()["theme"].get<std::string>(), "dark");
            db.FlushSync();
        }
        CHECK_EQ(CountPrefixed(dir, "db.json.pre-migrate-v1-"), 1);
        {
            std::ifstream f(dbPath);
            json parsed = json::parse(f);
            CHECK_EQ(parsed["schema_version"].get<int>(), DatabaseService::kCurrentSchemaVersion);
        }
        fs::remove_all(dir);
    }

    // ── 9. DB from a newer app is refused, file untouched ───────────────
    {
        auto dir = MakeScratch("schema-newer");
        auto dbPath = dir / "db.json";
        WriteText(dbPath, R"({"schema_version":999,"books":[]})");
        bool threw = false;
        try {
            DatabaseService db;
            db.Load(dbPath.string());
        } catch (const std::exception&) {
            threw = true;
        }
        CHECK_TRUE(threw);
        {
            std::ifstream f(dbPath);
            json parsed = json::parse(f);
            CHECK_EQ(parsed["schema_version"].get<int>(), 999);
        }
        fs::remove_all(dir);
    }

    // ── 10. v2 -> v3 unified annotations migration ─────────────────────
    {
        auto dir = MakeScratch("schema-v3");
        auto dbPath = dir / "db.json";
        WriteText(dbPath, R"json({
            "schema_version": 2,
            "books": [{"id": 1, "title": "Unified", "format": "epub"}],
            "bookmarks": [{"id": 10, "book_id": 1, "cfi": "epubcfi(/6/2)", "title": "BM", "created_at": "2026-01-01T00:00:00Z"}],
            "highlights": [{"id": 11, "book_id": 1, "cfi": "epubcfi(/6/4)", "text": "hello", "color": "#ff0", "note": "hl note", "created_at": "2026-01-02T00:00:00Z"}],
            "notes": [{"id": 12, "book_id": 1, "cfi": "epubcfi(/6/6)", "content": "note body", "highlight_id": 11, "created_at": "2026-01-03T00:00:00Z"}],
            "nextId": 2
        })json");
        {
            DatabaseService db;
            db.Load(dbPath.string());
            CHECK_EQ(db.GetSchemaVersion(), DatabaseService::kCurrentSchemaVersion);
            const json annotations = db.GetAnnotations(1);
            CHECK_EQ((int)annotations.size(), 3);
            CHECK_TRUE(annotations[0]["type"] == "bookmark");
            CHECK_TRUE(annotations[1]["type"] == "highlight");
            CHECK_TRUE(annotations[2]["type"] == "note");
            CHECK_EQ(annotations[1]["locator"]["kind"].get<std::string>(), "epub");
            CHECK_EQ(annotations[2]["relatedId"].get<int>(), 11);
            db.FlushSync();
        }
        CHECK_EQ(CountPrefixed(dir, "db.json.pre-migrate-v2-"), 1);
        fs::remove_all(dir);
    }

    // ── 11. PB-021 annotations: CRUD + legacy table dual-write ─────────
    // 统一表（annotations[]）是新增量，但现有渲染层仍读 bookmarks /
    // highlights / notes —— 每次写都必须同时维护两边。
    {
        auto dir = MakeScratch("annotations-crud");
        auto dbPath = dir / "db.json";
        DatabaseService db;
        db.Load(dbPath.string());

        const json bookmark = {
            { "version", 1 },
            { "bookId", 1 },
            { "type", "bookmark" },
            { "locator", { { "version", 1 }, { "kind", "epub" }, { "cfi", "epubcfi(/6/2)" } } },
            { "title", "书签 1" },
            { "createdAt", "2026-10-01T00:00:00.000Z" },
            { "updatedAt", "2026-10-01T00:00:00.000Z" },
        };
        const json bookmarkRow = db.AddAnnotation(bookmark);
        CHECK_TRUE(!bookmarkRow.is_null());
        CHECK_EQ(bookmarkRow["id"].get<int>(), 1);
        CHECK_EQ(bookmarkRow["legacyId"].get<int>(), 1);
        CHECK_EQ(bookmarkRow["legacyType"].get<std::string>(), std::string("bookmark"));
        CHECK_EQ(bookmarkRow["createdAt"].get<std::string>(),
                 std::string("2026-10-01T00:00:00.000Z"));
        CHECK_TRUE(!bookmarkRow["updatedAt"].get<std::string>().empty());
        CHECK_EQ((int)db.GetAnnotations(1).size(), 1);
        CHECK_EQ((int)db.GetAnnotations(0).size(), 1);  // 0 = 全库
        CHECK_EQ((int)db.GetAnnotations(2).size(), 0);
        CHECK_EQ((int)db.GetBookmarks(1).size(), 1);
        CHECK_EQ(db.GetBookmarks(1)[0]["cfi"].get<std::string>(),
                 std::string("epubcfi(/6/2)"));
        CHECK_EQ(db.GetBookmarks(1)[0]["title"].get<std::string>(), std::string("书签 1"));
        CHECK_TRUE(!db.GetAnnotation(1).is_null());
        CHECK_TRUE(db.GetAnnotation(999).is_null());

        // highlight：locator=text → 旧行 cfi=pos-<offset>；quote → text
        const json highlight = {
            { "version", 1 },
            { "bookId", 1 },
            { "type", "highlight" },
            { "locator", { { "version", 1 }, { "kind", "text" }, { "offset", 123 },
                           { "length", 5 }, { "quote", "hello" } } },
            { "color", "#fbbf24" },
            { "note", "批注" },
        };
        const json highlightRow = db.AddAnnotation(highlight);
        CHECK_TRUE(!highlightRow.is_null());
        CHECK_EQ(highlightRow["id"].get<int>(), 2);
        CHECK_EQ((int)db.GetHighlights(1).size(), 1);
        CHECK_EQ(db.GetHighlights(1)[0]["cfi"].get<std::string>(), std::string("pos-123"));
        CHECK_EQ(db.GetHighlights(1)[0]["text"].get<std::string>(), std::string("hello"));
        CHECK_EQ(db.GetHighlights(1)[0]["color"].get<std::string>(), std::string("#fbbf24"));

        // note：pdf 页码 → 旧 notes.page；relatedId → 旧 highlight_id
        const json note = {
            { "version", 1 },
            { "bookId", 1 },
            { "type", "note" },
            { "locator", { { "version", 1 }, { "kind", "pdf" }, { "page", 7 } } },
            { "note", "笔记正文" },
            { "relatedId", 2 },
        };
        const json noteRow = db.AddAnnotation(note);
        CHECK_TRUE(!noteRow.is_null());
        CHECK_EQ(noteRow["id"].get<int>(), 3);
        CHECK_EQ((int)db.GetNotes(1).size(), 1);
        CHECK_EQ(db.GetNotes(1)[0]["page"].get<int>(), 7);
        CHECK_EQ(db.GetNotes(1)[0]["content"].get<std::string>(), std::string("笔记正文"));
        CHECK_EQ(db.GetNotes(1)[0]["highlight_id"].get<int>(), 2);

        // 另一本书 + scroll locator（旧行把百分比写进 page/progress）
        const json scrollBookmark = {
            { "version", 1 }, { "bookId", 3 }, { "type", "bookmark" },
            { "locator", { { "version", 1 }, { "kind", "scroll" }, { "percent", 42.5 } } },
        };
        CHECK_TRUE(!db.AddAnnotation(scrollBookmark).is_null());
        CHECK_EQ((int)db.GetAnnotations(1).size(), 3);
        CHECK_EQ((int)db.GetAnnotations(3).size(), 1);
        CHECK_EQ((int)db.GetAnnotations(0).size(), 4);
        CHECK_EQ(db.GetBookmarks(3)[0]["page"].get<double>(), 42.5);
        CHECK_EQ(db.GetBookmarks(3)[0]["progress"].get<double>(), 42.5);

        // 更新：title + locator（不换 type）→ 同一旧行原地重建，createdAt 不变
        const json patch = {
            { "title", "改名" },
            { "locator", { { "version", 1 }, { "kind", "html" }, { "href", "ch1.xhtml" } } },
        };
        const json updated = db.UpdateAnnotation(1, patch);
        CHECK_TRUE(!updated.is_null());
        CHECK_EQ(updated["title"].get<std::string>(), std::string("改名"));
        CHECK_EQ(updated["locator"]["kind"].get<std::string>(), std::string("html"));
        CHECK_EQ(updated["createdAt"].get<std::string>(),
                 std::string("2026-10-01T00:00:00.000Z"));
        CHECK_EQ(updated["legacyId"].get<int>(), 1);  // id 不变
        CHECK_EQ(db.GetBookmarks(1)[0]["title"].get<std::string>(), std::string("改名"));
        CHECK_EQ(db.GetBookmarks(1)[0]["cfi"].get<std::string>(), std::string("ch1.xhtml"));

        // type 变更：bookmark → highlight，旧行换表 + legacyId 换新
        const json typeChanged =
            db.UpdateAnnotation(1, json{ { "type", "highlight" }, { "color", "#60a5fa" } });
        CHECK_TRUE(!typeChanged.is_null());
        CHECK_EQ(typeChanged["legacyType"].get<std::string>(), std::string("highlight"));
        CHECK_TRUE(typeChanged["legacyId"].get<int>() != 1);
        CHECK_EQ((int)db.GetBookmarks(1).size(), 0);
        CHECK_EQ((int)db.GetHighlights(1).size(), 2);  // 原 highlight + 转换来的

        // 非法写入：一律 null，且不落任何表
        CHECK_TRUE(db.AddAnnotation(json{ { "bookId", 0 }, { "type", "highlight" },
                                          { "locator", { { "kind", "epub" }, { "cfi", "x" } } } })
                       .is_null());
        CHECK_TRUE(db.AddAnnotation(json{ { "bookId", 1 }, { "type", "pdf-annot" },
                                          { "locator", { { "kind", "epub" }, { "cfi", "x" } } } })
                       .is_null());
        CHECK_TRUE(db.AddAnnotation(json{ { "bookId", 1 }, { "type", "bookmark" },
                                          { "locator", { { "kind", "epub" } } } })
                       .is_null());
        CHECK_TRUE(db.UpdateAnnotation(999, json::object()).is_null());
        CHECK_TRUE(db.UpdateAnnotation(1, json{ { "type", "nope" } }).is_null());
        CHECK_EQ((int)db.GetAnnotations(0).size(), 4);

        // 删除：统一行 + 旧表行一起删
        db.DeleteAnnotation(2);
        CHECK_TRUE(db.GetAnnotation(2).is_null());
        CHECK_EQ((int)db.GetAnnotations(0).size(), 3);
        CHECK_EQ((int)db.GetHighlights(1).size(), 1);  // 只剩 type 变更后的那行
        CHECK_EQ((int)db.GetNotes(1).size(), 1);       // note 不受影响

        db.FlushSync();
        fs::remove_all(dir);
    }

    // ── 12. PB-021 annotations: persist across reload + ids keep advancing ──
    {
        auto dir = MakeScratch("annotations-persist");
        auto dbPath = dir / "db.json";
        {
            DatabaseService db;
            db.Load(dbPath.string());
            CHECK_TRUE(!db.AddAnnotation(json{
                { "version", 1 }, { "bookId", 1 }, { "type", "bookmark" },
                { "locator", { { "version", 1 }, { "kind", "epub" }, { "cfi", "epubcfi(/6/8)" } } },
            }).is_null());
            CHECK_TRUE(!db.AddAnnotation(json{
                { "version", 1 }, { "bookId", 1 }, { "type", "highlight" },
                { "locator", { { "version", 1 }, { "kind", "text" }, { "offset", 9 }, { "quote", "q" } } },
                { "color", "#34d399" },
            }).is_null());
            db.FlushSync();
        }
        {
            DatabaseService db;
            db.Load(dbPath.string());
            CHECK_EQ((int)db.GetAnnotations(0).size(), 2);
            CHECK_EQ(db.GetAnnotations(0)[1]["type"].get<std::string>(), std::string("highlight"));
            CHECK_EQ((int)db.GetHighlights(1).size(), 1);
            CHECK_EQ(db.GetHighlights(1)[0]["cfi"].get<std::string>(), std::string("pos-9"));

            // 重启后 id 继续递增（nextAnnotationId / nextId 都已持久化）
            const json third = db.AddAnnotation(json{
                { "version", 1 }, { "bookId", 1 }, { "type", "note" },
                { "locator", { { "version", 1 }, { "kind", "pdf" }, { "page", 2 } } },
                { "note", "第三个" },
            });
            CHECK_TRUE(!third.is_null());
            CHECK_EQ(third["id"].get<int>(), 3);
            CHECK_EQ(third["legacyId"].get<int>(), 3);
            db.FlushSync();
        }
        fs::remove_all(dir);
    }

    // ── 13. PB-030 downloads: AddDownload 最新在前 + 行字段原样返回 ────────
    // downloads[] 行形状 { id, fileName, filePath, size, createdAt }；没有 bookId
    // （UI 用 filePath 关联书架），数组约定【最新在前】。
    {
        auto dir = MakeScratch("downloads-add");
        auto dbPath = dir / "db.json";
        {
            DatabaseService db;
            db.Load(dbPath.string());
            CHECK_TRUE(db.GetDownloads().is_array());
            CHECK_EQ((int)db.GetDownloads().size(), 0);

            const json a = db.AddDownload(json{
                { "fileName", "A.epub" }, { "filePath", "C:/dl/A.epub" }, { "size", 2048 } });
            const json b = db.AddDownload(json{
                { "fileName", "B.epub" }, { "filePath", "C:/dl/B.epub" }, { "size", 4096 } });
            CHECK_TRUE(!a.is_null());
            CHECK_TRUE(!b.is_null());
            CHECK_EQ(a["id"].get<int>(), 1);
            CHECK_EQ(b["id"].get<int>(), 2);
            CHECK_EQ(a["fileName"].get<std::string>(), std::string("A.epub"));
            CHECK_TRUE(!a["createdAt"].get<std::string>().empty());
            CHECK_TRUE(!b["createdAt"].get<std::string>().empty());

            const json downloads = db.GetDownloads();
            CHECK_EQ((int)downloads.size(), 2);
            CHECK_EQ(downloads[0]["id"].get<int>(), 2);  // B 后写 → 排最前
            CHECK_EQ(downloads[0]["filePath"].get<std::string>(), std::string("C:/dl/B.epub"));
            CHECK_EQ(downloads[0]["fileName"].get<std::string>(), std::string("B.epub"));
            CHECK_EQ(downloads[0]["size"].get<int>(), 4096);
            CHECK_EQ(downloads[1]["id"].get<int>(), 1);
            CHECK_EQ(downloads[1]["filePath"].get<std::string>(), std::string("C:/dl/A.epub"));
            CHECK_EQ(downloads[1]["size"].get<int>(), 2048);
            db.FlushSync();
        }
        fs::remove_all(dir);
    }

    // ── 14. PB-030 downloads: 同一 filePath 去重（原地合并 + 保留 id）─────
    // 反复下载同一本书不该让历史膨胀：命中旧行 → 合并字段、保留原 id、
    // 刷新 createdAt、挪到数组最前；新书仍按 nextDownloadId 递增拿号。
    {
        auto dir = MakeScratch("downloads-dedupe");
        auto dbPath = dir / "db.json";
        {
            DatabaseService db;
            db.Load(dbPath.string());

            const json first = db.AddDownload(json{
                { "fileName", "A.epub" }, { "filePath", "C:/dl/A.epub" }, { "size", 2048 } });
            CHECK_TRUE(!db.AddDownload(json{
                { "fileName", "B.epub" }, { "filePath", "C:/dl/B.epub" }, { "size", 4096 } }).is_null());
            const int idA = first["id"].get<int>();

            const json again = db.AddDownload(json{
                { "fileName", "A-v2.epub" }, { "filePath", "C:/dl/A.epub" }, { "size", 8192 } });
            CHECK_TRUE(!again.is_null());
            CHECK_EQ(again["id"].get<int>(), idA);             // id 复用，不新分配
            CHECK_EQ(again["size"].get<int>(), 8192);          // 新值覆盖旧值
            CHECK_EQ(again["fileName"].get<std::string>(), std::string("A-v2.epub"));
            CHECK_TRUE(!again["createdAt"].get<std::string>().empty());

            const json downloads = db.GetDownloads();
            CHECK_EQ((int)downloads.size(), 2);                // 没有多出第三行
            CHECK_EQ(downloads[0]["id"].get<int>(), idA);      // 去重后挪到最前
            CHECK_EQ(downloads[0]["filePath"].get<std::string>(), std::string("C:/dl/A.epub"));
            CHECK_EQ(downloads[0]["size"].get<int>(), 8192);
            CHECK_EQ(downloads[1]["filePath"].get<std::string>(), std::string("C:/dl/B.epub"));

            // 去重不消耗 id：下一本全新的书拿到 3
            const json c = db.AddDownload(json{
                { "fileName", "C.epub" }, { "filePath", "C:/dl/C.epub" }, { "size", 1 } });
            CHECK_EQ(c["id"].get<int>(), 3);
            db.FlushSync();
        }
        fs::remove_all(dir);
    }

    // ── 15. PB-030 downloads: 非法入参一律 null 且不写库 ─────────────────
    // 只有 filePath 非空的 object 才算一条下载；其余（缺 filePath / 空路径 /
    // 非 object）返回 null，且 GetDownloads() 保持空。
    {
        auto dir = MakeScratch("downloads-invalid");
        auto dbPath = dir / "db.json";
        {
            DatabaseService db;
            db.Load(dbPath.string());
            CHECK_TRUE(db.AddDownload(json::object()).is_null());             // 缺 filePath
            CHECK_TRUE(db.AddDownload(json{ { "filePath", "" } }).is_null());  // 空 filePath
            CHECK_TRUE(db.AddDownload(json{ { "fileName", "X.epub" } }).is_null());
            CHECK_TRUE(db.AddDownload(json::array()).is_null());               // 非 object
            CHECK_EQ((int)db.GetDownloads().size(), 0);
            db.FlushSync();
        }
        fs::remove_all(dir);
    }

    // ── 16. PB-030 downloads: DeleteDownload 返回被删行 ──────────────────
    // 返回被删行（调用方要据此删磁盘文件）；同 id 二次删除 / 不存在的 id 返回 null。
    {
        auto dir = MakeScratch("downloads-delete");
        auto dbPath = dir / "db.json";
        {
            DatabaseService db;
            db.Load(dbPath.string());
            const json a = db.AddDownload(json{
                { "fileName", "A.epub" }, { "filePath", "C:/dl/A.epub" }, { "size", 2048 } });
            CHECK_TRUE(!db.AddDownload(json{
                { "fileName", "B.epub" }, { "filePath", "C:/dl/B.epub" }, { "size", 4096 } }).is_null());
            const int idA = a["id"].get<int>();

            const json removed = db.DeleteDownload(idA);
            CHECK_TRUE(!removed.is_null());
            CHECK_EQ(removed["id"].get<int>(), idA);
            CHECK_EQ(removed["filePath"].get<std::string>(), std::string("C:/dl/A.epub"));

            CHECK_TRUE(db.DeleteDownload(idA).is_null());   // 已经删过一次
            CHECK_TRUE(db.DeleteDownload(999).is_null());   // 不存在的 id

            const json downloads = db.GetDownloads();
            CHECK_EQ((int)downloads.size(), 1);
            CHECK_EQ(downloads[0]["filePath"].get<std::string>(), std::string("C:/dl/B.epub"));
            db.FlushSync();
        }
        fs::remove_all(dir);
    }

    // ── 17. PB-030 downloads: 下载历史跨重启落盘 ─────────────────────────
    // FlushSync 后 Another instance Load 同一路径：行内容完整，且
    // nextDownloadId 也已持久化（重启后 id 从 2 继续）。
    {
        auto dir = MakeScratch("downloads-persist");
        auto dbPath = dir / "db.json";
        {
            DatabaseService db;
            db.Load(dbPath.string());
            CHECK_TRUE(!db.AddDownload(json{
                { "fileName", "Keep.epub" }, { "filePath", "C:/dl/Keep.epub" },
                { "size", 12345 } }).is_null());
            db.FlushSync();

            std::ifstream f(dbPath);
            json parsed = json::parse(f);
            CHECK_TRUE(parsed["downloads"].is_array());
            CHECK_EQ((int)parsed["downloads"].size(), 1);
        }
        {
            DatabaseService db;
            db.Load(dbPath.string());
            const json downloads = db.GetDownloads();
            CHECK_EQ((int)downloads.size(), 1);
            CHECK_EQ(downloads[0]["fileName"].get<std::string>(), std::string("Keep.epub"));
            CHECK_EQ(downloads[0]["filePath"].get<std::string>(), std::string("C:/dl/Keep.epub"));
            CHECK_EQ(downloads[0]["size"].get<int>(), 12345);
            CHECK_TRUE(!downloads[0]["createdAt"].get<std::string>().empty());

            const json second = db.AddDownload(json{
                { "fileName", "Second.epub" }, { "filePath", "C:/dl/Second.epub" }, { "size", 2 } });
            CHECK_EQ(second["id"].get<int>(), 2);  // nextDownloadId 已持久化
            db.FlushSync();
        }
        fs::remove_all(dir);
    }

    // ── 18. v4 -> v5 download history migration + pre-migrate backup ────
    // 备份名由 CopyForMigrationBackup 拼成 path + ".pre-migrate-v" +
    // 【来源版本】+ "-" + 时间戳 —— v4 库的前缀是 pre-migrate-v4-，不是 v5。
    // 迁移只补 downloads / nextDownloadId 容器，已有 books / settings 不动。
    {
        auto dir = MakeScratch("schema-v5");
        auto dbPath = dir / "db.json";
        WriteText(dbPath, R"json({
            "schema_version": 4,
            "books": [{"id": 1, "title": "V4 Book", "file_path": "C:/books/v4.epub"}],
            "nextId": 2,
            "settings": {"theme": "dark"}
        })json");
        {
            DatabaseService db;
            db.Load(dbPath.string());
            CHECK_EQ(db.GetSchemaVersion(), DatabaseService::kCurrentSchemaVersion);
            CHECK_EQ(db.GetSchemaVersion(), 5);
            CHECK_TRUE(db.GetDownloads().is_array());
            CHECK_EQ((int)db.GetDownloads().size(), 0);
            CHECK_EQ((int)db.GetBooks().size(), 1);  // 老数据不丢
            CHECK_EQ(db.GetBooks()[0]["title"].get<std::string>(), std::string("V4 Book"));
            CHECK_EQ(db.GetSettings()["theme"].get<std::string>(), std::string("dark"));
            db.FlushSync();
        }
        CHECK_EQ(CountPrefixed(dir, "db.json.pre-migrate-v4-"), 1);
        CHECK_EQ(CountPrefixed(dir, "db.json.pre-migrate-v5-"), 0);  // 别按目标版本猜
        {
            std::ifstream f(dbPath);
            json parsed = json::parse(f);
            CHECK_EQ(parsed["schema_version"].get<int>(), 5);
            CHECK_TRUE(parsed["downloads"].is_array());
            CHECK_EQ(parsed["nextDownloadId"].get<int>(), 1);
        }
        fs::remove_all(dir);
    }

    return pb_t::Summary("database_test");
}
