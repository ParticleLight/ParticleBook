#pragma once
// ── MOBI text_encoding patch (pure, testable) ──────────────────────
// MuPDF's mobi.c defaults text_encoding to LATIN_1 (0) when the MOBI header
// is missing or incomplete. Most Chinese MOBI files are actually UTF-8, so
// mutool renders them as mojibake. We rewrite that one field before handing
// the file to mutool.
//
// Extracted out of PdfService so the byte-level logic is unit-testable
// without touching the filesystem. The scan mirrors the original
// implementation exactly:
//   - only the FIRST "MOBI" magic in the buffer is considered
//   - the encoding field sits at magic+12 (skip magic/header_len/mobi_type)
//   - only 0 (Latin-1) and 1252 (CP1252) are rewritten, to 65001 (UTF-8)
//   - a buffer without the magic, or already UTF-8, is left untouched
// Header-only, inline; no Windows dependency.

#include <cstdint>
#include <cctype>
#include <string>
#include <vector>

namespace pb {

    // Lowercased extension of path INCLUDING the leading dot, or "" when the
    // path has no dot. The original computed this inline and reused it both
    // for the family check and for the patched temp file name.
    inline std::string MobiExtLower(const std::string& path)
    {
        std::string ext;
        size_t dot = path.rfind('.');
        if (dot != std::string::npos) {
            ext = path.substr(dot);
            for (auto& c : ext) c = (char)std::tolower((unsigned char)c);
        }
        return ext;
    }

    // Extensions whose text_encoding we patch.
    inline bool IsMobiFamilyExt(const std::string& ext)
    {
        return ext == ".mobi" || ext == ".azw" || ext == ".azw3";
    }

    inline bool IsMobiFamilyPath(const std::string& path)
    {
        return IsMobiFamilyExt(MobiExtLower(path));
    }

    // Patch the encoding field in place. Returns true iff a patch was applied.
    inline bool PatchMobiEncodingBuffer(std::vector<uint8_t>& data)
    {
        const size_t size = data.size();
        bool patched = false;
        for (size_t i = 0; i + 16 < size; i++) {
            if (data[i] == 'M' && data[i+1] == 'O' && data[i+2] == 'B' && data[i+3] == 'I') {
                size_t encOff = i + 12; // skip magic(4) + header_len(4) + mobi_type(4)
                if (encOff + 4 <= size) {
                    uint32_t enc = data[encOff] | ((uint32_t)data[encOff+1] << 8)
                                 | ((uint32_t)data[encOff+2] << 16) | ((uint32_t)data[encOff+3] << 24);
                    // 0 = Latin-1, 1252 = CP1252 -> 65001 = UTF-8
                    if (enc == 0 || enc == 1252) {
                        uint32_t utf8 = 65001;
                        data[encOff]   = (uint8_t)(utf8 & 0xFF);
                        data[encOff+1] = (uint8_t)((utf8 >> 8) & 0xFF);
                        data[encOff+2] = (uint8_t)((utf8 >> 16) & 0xFF);
                        data[encOff+3] = (uint8_t)((utf8 >> 24) & 0xFF);
                        patched = true;
                    }
                }
                break; // only first MOBI header matters
            }
        }
        return patched;
    }

    // Read back the encoding field at the first MOBI magic (for diagnostics
    // and tests). Returns false when the magic/field is not present.
    inline bool ReadMobiEncodingField(const std::vector<uint8_t>& data, uint32_t& outEnc)
    {
        const size_t size = data.size();
        for (size_t i = 0; i + 16 < size; i++) {
            if (data[i] == 'M' && data[i+1] == 'O' && data[i+2] == 'B' && data[i+3] == 'I') {
                size_t encOff = i + 12;
                if (encOff + 4 > size) return false;
                outEnc = data[encOff] | ((uint32_t)data[encOff+1] << 8)
                       | ((uint32_t)data[encOff+2] << 16) | ((uint32_t)data[encOff+3] << 24);
                return true;
            }
        }
        return false;
    }

} // namespace pb