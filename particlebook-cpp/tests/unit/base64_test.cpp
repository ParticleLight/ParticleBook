#include "utils/base64.h"
#include "utils/test_assert.h"
#include <string>
#include <vector>

using Bytes = std::vector<uint8_t>;

static Bytes B(std::initializer_list<uint8_t> l) { return Bytes(l); }

int main() {
    // ── encode: standard RFC 4648 vectors (incl. padding cases) ──
    CHECK_EQ(pb::Base64Encode(""), std::string(""));
    CHECK_EQ(pb::Base64Encode("A"), std::string("QQ=="));
    CHECK_EQ(pb::Base64Encode("AB"), std::string("QUI="));
    CHECK_EQ(pb::Base64Encode("ABC"), std::string("QUJD"));
    CHECK_EQ(pb::Base64Encode("hello"), std::string("aGVsbG8="));

    // ── encode: both non-alphanumeric alphabet entries (+ and /) ──
    // 0xFB 0xEF 0xBE -> 62,62,62,62 -> "++++"
    std::string ppp; ppp.push_back((char)0xFB); ppp.push_back((char)0xEF); ppp.push_back((char)0xBE);
    CHECK_EQ(pb::Base64Encode(ppp), std::string("++++"));
    // 0xFF 0xFF 0xFF -> 63,63,63,63 -> "////"
    std::string fff; fff.push_back((char)0xFF); fff.push_back((char)0xFF); fff.push_back((char)0xFF);
    CHECK_EQ(pb::Base64Encode(fff), std::string("////"));

    // ── decode: plain ──
    CHECK_EQ(pb::Base64Decode("QUJD"), B({0x41, 0x42, 0x43}));
    CHECK_EQ(pb::Base64Decode("aGVsbG8="), B({0x68, 0x65, 0x6C, 0x6C, 0x6F}));
    CHECK_EQ(pb::Base64Decode(""), Bytes{});

    // ── decode: skips non-alphabet chars (whitespace from MIME wrapping) ──
    // "QUJ D" -> alphabet chars are Q,U,J,D -> "QUJD" -> 3 bytes ABC.
    CHECK_EQ(pb::Base64Decode("QUJ D"), B({0x41, 0x42, 0x43}));
    CHECK_EQ(pb::Base64Decode("aGVs\nbG8="), B({0x68, 0x65, 0x6C, 0x6C, 0x6F}));
    CHECK_EQ(pb::Base64Decode("  QUJD  "), B({0x41, 0x42, 0x43}));

    // ── round-trip: arbitrary binary (incl. NUL and high bytes) ──
    Bytes bin = B({0x00, 0x01, 0x7F, 0x80, 0xFF, 0x00, 0x41});
    std::string binStr(bin.begin(), bin.end());
    CHECK_EQ(pb::Base64Decode(pb::Base64Encode(binStr)), bin);

    return pb_t::Summary("base64_test");
}
