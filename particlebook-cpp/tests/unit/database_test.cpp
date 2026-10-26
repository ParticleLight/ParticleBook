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

    return pb_t::Summary("database_test");
}
