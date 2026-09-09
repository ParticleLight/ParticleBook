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

    // BuildDownloadUrl
    CHECK_TRUE(pb::BuildDownloadUrl("2.1.0", "a.exe") ==
               "https://github.com/ParticleLight/ParticleBook/releases/download/v2.1.0/a.exe");

    return pb_t::Summary("update_meta_test");
}
