#include "utils/mobi_patch.h"
#include "utils/test_assert.h"

#include <string>
#include <vector>

using Bytes = std::vector<uint8_t>;

// Build a buffer with a "MOBI" magic at magicOffset and text_encoding = enc
// at magicOffset+12. totalSize is padded well past the magic.
static Bytes MakeMobi(size_t magicOffset, uint32_t enc, size_t totalSize)
{
    Bytes d(totalSize, 0x00);
    d[magicOffset]     = 'M';
    d[magicOffset + 1] = 'O';
    d[magicOffset + 2] = 'B';
    d[magicOffset + 3] = 'I';
    const size_t o = magicOffset + 12;
    d[o]     = (uint8_t)(enc & 0xFF);
    d[o + 1] = (uint8_t)((enc >> 8) & 0xFF);
    d[o + 2] = (uint8_t)((enc >> 16) & 0xFF);
    d[o + 3] = (uint8_t)((enc >> 24) & 0xFF);
    return d;
}

int main()
{
    // ── extension helpers ─────────────────────────────────────────────
    CHECK_EQ(pb::MobiExtLower("book.mobi"), std::string(".mobi"));
    CHECK_EQ(pb::MobiExtLower("book.MOBI"), std::string(".mobi"));
    CHECK_EQ(pb::MobiExtLower("a.b.AZW3"), std::string(".azw3"));
    CHECK_EQ(pb::MobiExtLower("book.epub"), std::string(".epub"));
    CHECK_EQ(pb::MobiExtLower("noext"), std::string(""));
    // Path with a dot in a directory: the original used a plain rfind('.'),
    // so the "extension" becomes ".b/c" — preserved behaviour, documented
    // here so a future change to smarter parsing is a deliberate one.
    CHECK_EQ(pb::MobiExtLower("C:/a.b/c"), std::string(".b/c"));

    CHECK_TRUE(pb::IsMobiFamilyExt(".mobi"));
    CHECK_TRUE(pb::IsMobiFamilyExt(".azw"));
    CHECK_TRUE(pb::IsMobiFamilyExt(".azw3"));
    CHECK_TRUE(!pb::IsMobiFamilyExt(".epub"));
    CHECK_TRUE(!pb::IsMobiFamilyExt(""));
    CHECK_TRUE(pb::IsMobiFamilyPath("dir/Book.AZW"));
    CHECK_TRUE(!pb::IsMobiFamilyPath("dir/Book.pdf"));

    // ── encoding 0 (Latin-1) -> 65001 ─────────────────────────────────
    {
        Bytes d = MakeMobi(0, 0, 64);
        CHECK_TRUE(pb::PatchMobiEncodingBuffer(d));
        uint32_t enc = 999;
        CHECK_TRUE(pb::ReadMobiEncodingField(d, enc));
        CHECK_EQ((int)enc, 65001);
    }

    // ── encoding 1252 (CP1252) -> 65001 ──────────────────────────────
    {
        Bytes d = MakeMobi(0, 1252, 64);
        CHECK_TRUE(pb::PatchMobiEncodingBuffer(d));
        uint32_t enc = 0;
        CHECK_TRUE(pb::ReadMobiEncodingField(d, enc));
        CHECK_EQ((int)enc, 65001);
    }

    // ── already UTF-8 / other values: untouched, not "patched" ────────
    {
        Bytes d = MakeMobi(0, 65001, 64);
        Bytes before = d;
        CHECK_TRUE(!pb::PatchMobiEncodingBuffer(d));
        CHECK_TRUE(d == before);
    }
    {
        Bytes d = MakeMobi(0, 65000, 64);
        Bytes before = d;
        CHECK_TRUE(!pb::PatchMobiEncodingBuffer(d));
        CHECK_TRUE(d == before);
    }

    // ── no magic anywhere: untouched ──────────────────────────────────
    {
        Bytes d(64, 0x00);
        Bytes before = d;
        CHECK_TRUE(!pb::PatchMobiEncodingBuffer(d));
        CHECK_TRUE(d == before);
        uint32_t enc = 0;
        CHECK_TRUE(!pb::ReadMobiEncodingField(d, enc));
    }

    // ── magic not at offset 0 (real MOBI has a PalmDB header first) ───
    {
        Bytes d = MakeMobi(40, 0, 128);
        CHECK_TRUE(pb::PatchMobiEncodingBuffer(d));
        uint32_t enc = 0;
        CHECK_TRUE(pb::ReadMobiEncodingField(d, enc));
        CHECK_EQ((int)enc, 65001);
    }

    // ── ONLY the first magic is considered ────────────────────────────
    // First header already UTF-8 (no patch needed) => the later header with
    // encoding 0 must be left ALONE (original loop breaks on first match).
    {
        Bytes d = MakeMobi(0, 65001, 256);
        const size_t second = 100;
        d[second] = 'M'; d[second + 1] = 'O'; d[second + 2] = 'B'; d[second + 3] = 'I';
        // second header encoding stays 0 from the zero-filled buffer
        CHECK_TRUE(!pb::PatchMobiEncodingBuffer(d));
        const size_t o = second + 12;
        const uint32_t secondEnc = d[o] | ((uint32_t)d[o + 1] << 8)
                                 | ((uint32_t)d[o + 2] << 16) | ((uint32_t)d[o + 3] << 24);
        CHECK_EQ((int)secondEnc, 0);
    }

    // ── buffer too small for the scan to run ──────────────────────────
    // Loop guard is i + 16 < size, so a 16-byte "MOBI..." buffer is skipped.
    {
        Bytes d(16, 0x00);
        d[0] = 'M'; d[1] = 'O'; d[2] = 'B'; d[3] = 'I';
        Bytes before = d;
        CHECK_TRUE(!pb::PatchMobiEncodingBuffer(d));
        CHECK_TRUE(d == before);
    }

    // ── big-endian sanity: field is read little-endian ────────────────
    {
        Bytes d = MakeMobi(0, 0, 64);
        pb::PatchMobiEncodingBuffer(d);
        // 65001 = 0x0000FDE9 -> bytes E9 FD 00 00
        CHECK_EQ((int)d[12], 0xE9);
        CHECK_EQ((int)d[13], 0xFD);
        CHECK_EQ((int)d[14], 0x00);
        CHECK_EQ((int)d[15], 0x00);
    }

    return pb_t::Summary("mobi_patch_test");
}
