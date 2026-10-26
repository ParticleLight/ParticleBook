// ── PB-023: SecretStore tests ───────────────────────────────────────
// Covers the contract the rest of PB-023/PB-002 relies on:
//   * set / get / overwrite / remove / has / clear
//   * persistence across instances ("app restart")
//   * the file on disk contains neither the value nor the key name
//   * corrupt, foreign, truncated and unusable stores fail closed (no crash)
//     and never silently destroy ciphertext they cannot read
//   * thread safety of one shared instance (worker job + UI thread)
//
// Everything runs against temp files under %TEMP%; nothing touches the real
// %LOCALAPPDATA%\ParticleBook\secrets.dat.

#include "platform/SecretStore.h"

#include "utils/encoding.h"
#include "utils/test_assert.h"

#include <windows.h>

#include <atomic>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <string>
#include <thread>
#include <vector>

namespace {

    std::string TempDir()
    {
        wchar_t buf[MAX_PATH] = {};
        const DWORD n = GetTempPathW(MAX_PATH, buf);
        if (n == 0 || n >= MAX_PATH) return std::string(".\\");
        return pb::WideToUtf8(std::wstring(buf));
    }

    int g_seq = 0;

    std::string NewPath(const char* stem)
    {
        return TempDir() + "pb_secretstore_" + stem + "_"
             + std::to_string(GetCurrentProcessId()) + "_"
             + std::to_string(++g_seq) + ".dat";
    }

    bool FileExists(const std::string& path)
    {
        std::error_code ec;
        return std::filesystem::exists(path, ec);
    }

    std::string ReadRaw(const std::string& path)
    {
        std::ifstream f(path, std::ios::binary);
        return std::string(std::istreambuf_iterator<char>(f), std::istreambuf_iterator<char>());
    }

    void WriteRaw(const std::string& path, const std::string& data)
    {
        std::ofstream f(path, std::ios::binary | std::ios::trunc);
        f.write(data.data(), static_cast<std::streamsize>(data.size()));
    }

    int CountNewlines(const std::string& s)
    {
        int n = 0;
        for (char c : s) if (c == '\n') ++n;
        return n;
    }

    // Removes the store file plus any .tmp / .corrupt siblings the tests made.
    void CleanupPrefix(const std::string& dir, const std::string& prefix)
    {
        const std::wstring dirW = pb::Utf8ToWide(dir);
        const std::wstring pattern = dirW + pb::Utf8ToWide(prefix) + L"*";
        WIN32_FIND_DATAW fd{};
        HANDLE h = FindFirstFileW(pattern.c_str(), &fd);
        if (h == INVALID_HANDLE_VALUE) return;
        std::vector<std::wstring> names;
        do {
            names.push_back(fd.cFileName);
        } while (FindNextFileW(h, &fd));
        FindClose(h);
        for (const std::wstring& name : names) {
            const std::wstring full = dirW + name;
            DeleteFileW(full.c_str());
            RemoveDirectoryW(full.c_str());   // harmless no-op for plain files
        }
    }

} // namespace

int main()
{
    // ── 1. missing file: reads are not errors, and don't create the file ──
    {
        const std::string p = NewPath("missing");
        SecretStore s(p);
        std::string out = "untouched";
        CHECK_TRUE(!s.Get("absent", out));
        CHECK_EQ(out, std::string("untouched"));
        CHECK_TRUE(!s.Has("absent"));
        CHECK_TRUE(!s.Remove("absent"));
        CHECK_TRUE(!FileExists(p));
        CHECK_EQ(s.LastError(), std::string(""));   // "absent" is not an error
        CHECK_EQ(s.Path(), p);
    }

    // ── 2. set / get / restart / disk contains no plaintext ──
    {
        const std::string p = NewPath("roundtrip");
        const std::string name = "ai.translation.apiKey";
        const std::string value = "sk-test-DEADBEEF-0123456789";

        {
            SecretStore s(p);
            CHECK_TRUE(s.Set(name, value));
            std::string got;
            CHECK_TRUE(s.Get(name, got));
            CHECK_EQ(got, value);
            CHECK_TRUE(s.Has(name));
            CHECK_TRUE(FileExists(p));
            CHECK_EQ(s.LastError(), std::string(""));
        }
        {
            // New instance == "app restarted".
            SecretStore s(p);
            std::string got;
            CHECK_TRUE(s.Get(name, got));
            CHECK_EQ(got, value);
        }

        const std::string raw = ReadRaw(p);
        CHECK_TRUE(raw.size() > 16);
        CHECK_TRUE(raw.find(value) == std::string::npos);   // no plaintext value
        CHECK_TRUE(raw.find(name) == std::string::npos);    // not even the name
        CHECK_TRUE(raw.find("PBSECRET 1") == 0);            // our header
    }

    // ── 3. overwrite replaces the value, keeps one entry ──
    {
        const std::string p = NewPath("overwrite");
        SecretStore s(p);
        CHECK_TRUE(s.Set("k", "first-value"));
        CHECK_TRUE(s.Set("k", "second-value"));
        std::string got;
        CHECK_TRUE(s.Get("k", got));
        CHECK_EQ(got, std::string("second-value"));
        CHECK_TRUE(ReadRaw(p).find("first-value") == std::string::npos);
        CHECK_EQ(CountNewlines(ReadRaw(p)), 2);             // header + 1 entry

        SecretStore again(p);
        CHECK_TRUE(again.Get("k", got));
        CHECK_EQ(got, std::string("second-value"));
        CHECK_TRUE(!again.Has("first-value"));
    }

    // ── 4. several entries coexist; remove deletes only its own ──
    {
        const std::string p = NewPath("remove");
        SecretStore s(p);
        CHECK_TRUE(s.Set("provider.a", "va"));
        CHECK_TRUE(s.Set("provider.b", "vb"));
        CHECK_TRUE(s.Set("provider.c", "vc"));

        CHECK_TRUE(s.Remove("provider.b"));
        CHECK_TRUE(!s.Remove("provider.b"));      // already gone
        CHECK_TRUE(!s.Has("provider.b"));
        std::string got;
        CHECK_TRUE(!s.Get("provider.b", got));

        {
            SecretStore s2(p);
            CHECK_TRUE(s2.Has("provider.a"));
            CHECK_TRUE(!s2.Has("provider.b"));
            CHECK_TRUE(s2.Has("provider.c"));
        }
    }

    // ── 5. argument validation; empty value is a real value ──
    {
        const std::string p = NewPath("validate");
        SecretStore s(p);
        CHECK_TRUE(!s.Set("", "x"));              // empty name rejected
        CHECK_TRUE(!s.LastError().empty());
        std::string got;
        CHECK_TRUE(!s.Get("", got));
        CHECK_TRUE(!s.Has(""));

        CHECK_TRUE(s.Set("blank", ""));           // empty value round-trips
        got = "x";
        CHECK_TRUE(s.Get("blank", got));
        CHECK_EQ(got, std::string(""));
        CHECK_TRUE(s.Has("blank"));

        CHECK_TRUE(s.Remove("blank"));
        CHECK_TRUE(!s.Has("blank"));
    }

    // ── 6. UTF-8, multi-KB and NUL/newline-bearing values survive restart ──
    {
        const std::string p = NewPath("unicode");
        std::string value = u8"密钥-🔑-пароль-";
        for (int i = 0; i < 200; ++i) value += "0123456789abcdef";
        value += "-tail";
        CHECK_TRUE(value.size() > 3000);
        CHECK_TRUE(value.size() < 64 * 1024);

        // The payload is length-prefixed, not delimited, so any byte is legal.
        std::string binary = "line1\nline2\r\nline3";
        binary.push_back('\0');
        binary += "after-nul";

        SecretStore s(p);
        CHECK_TRUE(s.Set("unicode.key", value));
        CHECK_TRUE(s.Set("binary.key", binary));
        CHECK_TRUE(ReadRaw(p).find(u8"密钥") == std::string::npos);

        SecretStore s2(p);
        std::string got;
        CHECK_TRUE(s2.Get("unicode.key", got));
        CHECK_EQ(got, value);
        CHECK_TRUE(s2.Get("binary.key", got));
        CHECK_EQ(got, binary);
    }

    // ── 7. a file that is not ours: fail closed, back it up, rebuild ──
    {
        const std::string p = NewPath("corrupt");
        WriteRaw(p, "definitely not a secret store\nsecond line\n");

        SecretStore s(p);
        std::string got;
        CHECK_TRUE(!s.Get("any", got));           // no crash, fail closed
        CHECK_TRUE(!s.LastError().empty());       // ...and says why (unknown format)
        CHECK_TRUE(!s.Has("any"));

        // Still usable: a write rebuilds a valid store.
        CHECK_TRUE(s.Set("recovered", "after-corrupt"));
        SecretStore s2(p);
        CHECK_TRUE(s2.Get("recovered", got));
        CHECK_EQ(got, std::string("after-corrupt"));
        CHECK_TRUE(ReadRaw(p).find("PBSECRET 1") == 0);
    }

    // ── 8. unreadable entry (other user / tampering) is preserved verbatim ──
    {
        const std::string p = NewPath("preserve");
        const std::string foreignBlob = "AAAAAAAAAAAAAAAAAAAAAA==";   // valid base64, not our blob
        WriteRaw(p, std::string("PBSECRET 1\n") + foreignBlob + "\n");

        SecretStore s(p);
        std::string got;
        CHECK_TRUE(!s.Get("victim", got));        // cannot decrypt -> "not found"
        CHECK_TRUE(!s.LastError().empty());

        CHECK_TRUE(s.Set("mine", "v2"));          // write must not drop it
        const std::string after = ReadRaw(p);
        CHECK_EQ(CountNewlines(after), 3);        // header + preserved + new entry
        CHECK_TRUE(after.find(foreignBlob) != std::string::npos);

        SecretStore s2(p);
        CHECK_TRUE(s2.Get("mine", got));
        CHECK_EQ(got, std::string("v2"));
    }

    // ── 9. clear: every entry and the file itself go away ──
    {
        const std::string p = NewPath("clear");
        SecretStore s(p);
        CHECK_TRUE(s.Set("k1", "v1"));
        CHECK_TRUE(s.Set("k2", "v2"));
        CHECK_TRUE(s.Clear());
        CHECK_TRUE(!FileExists(p));
        CHECK_TRUE(!s.Has("k1"));
        CHECK_TRUE(!s.Has("k2"));
        CHECK_EQ(s.LastError(), std::string(""));

        CHECK_TRUE(s.Set("k3", "v3"));            // usable again after clear
        SecretStore s2(p);
        std::string got;
        CHECK_TRUE(s2.Get("k3", got));
        CHECK_EQ(got, std::string("v3"));
    }

    // ── 10. Reload observes an external change; stale snapshot is replaced ──
    {
        const std::string p = NewPath("reload");
        SecretStore a(p);
        CHECK_TRUE(a.Set("shared", "one"));

        SecretStore b(p);
        std::string got;
        CHECK_TRUE(b.Get("shared", got));
        CHECK_EQ(got, std::string("one"));

        CHECK_TRUE(a.Set("shared", "two"));       // b's cached snapshot is stale
        CHECK_TRUE(b.Reload());
        CHECK_TRUE(b.Get("shared", got));
        CHECK_EQ(got, std::string("two"));
    }

    // ── 11. unusable target (path is a directory): fail closed, no crash ──
    {
        const std::string p = NewPath("isdir");
        CHECK_TRUE(CreateDirectoryW(pb::Utf8ToWide(p).c_str(), nullptr) != 0);
        {
            SecretStore s(p);
            std::string got;
            CHECK_TRUE(!s.Get("k", got));
            CHECK_TRUE(!s.Set("k", "v"));
            CHECK_TRUE(!s.LastError().empty());
        }
        RemoveDirectoryW(pb::Utf8ToWide(p).c_str());
    }

    // ── 12. DefaultPath stays inside the user profile (never written here) ──
    {
        const std::string def = SecretStore::DefaultPath();
        CHECK_TRUE(!def.empty());
        if (!def.empty()) {
            CHECK_TRUE(def.find("ParticleBook") != std::string::npos);
            CHECK_TRUE(def.find("secrets.dat") != std::string::npos);
        }
    }

    // ── 13. one shared instance across threads (UI + worker job) ──
    {
        const std::string p = NewPath("threads");
        SecretStore s(p);
        CHECK_TRUE(s.Set("shared", "v0"));

        std::atomic<int> failures{0};
        std::vector<std::thread> threads;
        for (int t = 0; t < 4; ++t) {
            threads.emplace_back([&s, &failures, t]() {
                for (int i = 0; i < 12; ++i) {
                    // A concurrent write must never corrupt an unrelated read.
                    std::string got;
                    if (!s.Get("shared", got) || got != "v0") { ++failures; continue; }
                    const std::string k = "k" + std::to_string(t) + "_" + std::to_string(i);
                    const std::string v = "v" + std::to_string(t) + "_" + std::to_string(i);
                    if (!s.Set(k, v)) { ++failures; continue; }
                    std::string back;
                    if (!s.Get(k, back) || back != v) ++failures;
                }
            });
        }
        for (std::thread& th : threads) th.join();
        CHECK_EQ(failures.load(), 0);

        SecretStore check(p);
        std::string got;
        CHECK_TRUE(check.Get("shared", got));
        CHECK_EQ(got, std::string("v0"));
        CHECK_TRUE(check.Get("k3_11", got));
        CHECK_EQ(got, std::string("v3_11"));
        CHECK_EQ(CountNewlines(ReadRaw(p)), 2 + 4 * 12);   // header + shared + 48
    }

    CleanupPrefix(TempDir(), "pb_secretstore_");
    return pb_t::Summary("secret_store_test");
}
