#include "utils/update_meta.h"
#include "utils/test_assert.h"
#include <string>

int main() {
    // ── VersionGreater ──
    CHECK_TRUE(pb::VersionGreater("2.2.0", "2.1.0"));
    CHECK_TRUE(pb::VersionGreater("2.10.0", "2.9.9"));
    CHECK_TRUE(pb::VersionGreater("3.0.0", "2.99.99"));
    CHECK_TRUE(!pb::VersionGreater("2.1.0", "2.1.0"));   // equal -> not greater
    CHECK_TRUE(!pb::VersionGreater("2.0.0", "2.0.1"));
    CHECK_TRUE(!pb::VersionGreater("1.9.9", "2.0.0"));
    // leading 'v' tolerated
    CHECK_TRUE(pb::VersionGreater("v2.2.0", "2.1.0"));

    // ── ParseLatestYaml ──
    const std::string yaml =
        "version: 2.1.0\n"
        "files:\n"
        "  - url: ParticleBook-Setup-2.1.0.exe\n"
        "    sha512: \"abc123def456\"\n"
        "    size: 123456\n";
    auto m = pb::ParseLatestYaml(yaml);
    CHECK_TRUE(m.valid);
    CHECK_TRUE(m.version == "2.1.0");
    CHECK_TRUE(m.fileName == "ParticleBook-Setup-2.1.0.exe");
    CHECK_TRUE(m.sha512 == "abc123def456");   // quotes stripped
    CHECK_TRUE(m.size == 123456);

    // CRLF tolerated
    const std::string crlf = "version: 9.9.9\r\nfiles:\r\n  - url: x.exe\r\n";
    auto m2 = pb::ParseLatestYaml(crlf);
    CHECK_TRUE(m2.valid);
    CHECK_TRUE(m2.version == "9.9.9");

    // Missing required field -> invalid
    auto m3 = pb::ParseLatestYaml("version: 1.0.0\n");  // no url
    CHECK_TRUE(!m3.valid);

    // ── Regression lock: the EXACT format release.yml writes ──────────
    // If .github/workflows/release.yml ever changes the yaml shape, this test
    // is what tells us the updater would stop understanding it.
    {
        const std::string real =
            "version: 2.2.0\n"
            "files:\n"
            "  - url: ParticleBook-Setup-v2.2.0.exe\n"
            "    sha512: DEADBEEF\n"
            "    size: 4242\n"
            "    path: ParticleBook-Setup-v2.2.0.exe\n";
        auto r = pb::ParseLatestYaml(real);
        CHECK_TRUE(r.valid);
        CHECK_EQ(r.version, std::string("2.2.0"));
        CHECK_EQ(r.fileName, std::string("ParticleBook-Setup-v2.2.0.exe"));
        CHECK_EQ(r.sha512, std::string("DEADBEEF"));
        CHECK_EQ((int)r.size, 4242);
    }

    // ── Leading UTF-8 BOM (PowerShell 5 Set-Content writes one) ───────
    {
        const std::string withBom =
            std::string("\xEF\xBB\xBF") +
            "version: 3.0.0\nfiles:\n  - url: a.exe\n    sha512: h\n    size: 5\n";
        auto r = pb::ParseLatestYaml(withBom);
        CHECK_TRUE(r.valid);
        CHECK_EQ(r.version, std::string("3.0.0"));
        CHECK_EQ((int)r.size, 5);
    }

    // ── Multi-file: blockmap FIRST — must not pick the blockmap ───────
    {
        const std::string multi =
            "version: 2.2.0\n"
            "files:\n"
            "  - url: ParticleBook-Setup-v2.2.0.exe.blockmap\n"
            "    sha512: BLOCKMAPHASH\n"
            "    size: 111\n"
            "  - url: ParticleBook-Setup-v2.2.0.exe\n"
            "    sha512: EXEHASH\n"
            "    size: 222\n"
            "path: ParticleBook-Setup-v2.2.0.exe\n";
        auto r = pb::ParseLatestYaml(multi);
        CHECK_TRUE(r.valid);
        CHECK_EQ(r.fileName, std::string("ParticleBook-Setup-v2.2.0.exe"));
        CHECK_EQ(r.sha512, std::string("EXEHASH"));
        CHECK_EQ((int)r.size, 222);
    }

    // ── Multi-file: installer first — values must come from ITS entry ──
    {
        const std::string multi =
            "version: 2.2.1\n"
            "files:\n"
            "  - url: ParticleBook-Setup-v2.2.1.exe\n"
            "    sha512: EXEHASH2\n"
            "    size: 333\n"
            "  - url: ParticleBook-Setup-v2.2.1.exe.blockmap\n"
            "    sha512: BMBM\n"
            "    size: 444\n";
        auto r = pb::ParseLatestYaml(multi);
        CHECK_TRUE(r.valid);
        CHECK_EQ(r.fileName, std::string("ParticleBook-Setup-v2.2.1.exe"));
        CHECK_EQ(r.sha512, std::string("EXEHASH2"));
        CHECK_EQ((int)r.size, 333);
    }

    // ── Only a blockmap present: falls back to the first entry ────────
    {
        const std::string onlyBm =
            "version: 2.2.2\nfiles:\n  - url: only.exe.blockmap\n    sha512: X\n    size: 7\n";
        auto r = pb::ParseLatestYaml(onlyBm);
        CHECK_TRUE(r.valid);
        CHECK_EQ(r.fileName, std::string("only.exe.blockmap"));
    }

    // BuildDownloadUrl
    CHECK_TRUE(pb::BuildDownloadUrl("2.1.0", "a.exe") ==
               "https://github.com/ParticleLight/ParticleBook/releases/download/v2.1.0/a.exe");

    return pb_t::Summary("update_meta_test");
}
