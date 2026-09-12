#include "services/DatabaseService.h"
#include "utils/test_assert.h"

#include <filesystem>
#include <fstream>
#include <string>

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

    return pb_t::Summary("database_test");
}
