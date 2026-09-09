#pragma once
// ── Base64 encode / decode ──────────────────────────────────────────
// Unifies Base64Encode (was in PdfService) and Base64Decode (was in
// FileHandlers) into one header-only implementation. Standard alphabet,
// '=' padding. Decode tolerates whitespace (skips non-alphabet chars).
// Header-only, inline.

#include <string>
#include <vector>
#include <cstdint>

namespace pb {
    inline std::string Base64Encode(const uint8_t* data, size_t len)
    {
        static const char* b64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        std::string result;
        result.reserve(((len + 2) / 3) * 4);
        int val = 0, valb = -6;
        for (size_t i = 0; i < len; i++) {
            val = (val << 8) + data[i];
            valb += 8;
            while (valb >= 0) {
                result.push_back(b64[(val >> valb) & 0x3F]);
                valb -= 6;
            }
        }
        if (valb > -6) result.push_back(b64[((val << 8) >> (valb + 8)) & 0x3F]);
        while (result.size() % 4) result.push_back('=');
        return result;
    }

    inline std::string Base64Encode(const std::string& in)
    {
        return Base64Encode(reinterpret_cast<const uint8_t*>(in.data()), in.size());
    }

    // Returns decoded bytes, skipping any non-alphabet char (whitespace/padding).
    inline std::vector<uint8_t> Base64Decode(const std::string& in)
    {
        std::vector<uint8_t> out;
        out.reserve(in.size() / 4 * 3);
        unsigned int val = 0;
        int valb = -8;
        for (unsigned char c : in) {
            uint8_t d;
            if (c >= 'A' && c <= 'Z') d = static_cast<uint8_t>(c - 'A');
            else if (c >= 'a' && c <= 'z') d = static_cast<uint8_t>(c - 'a' + 26);
            else if (c >= '0' && c <= '9') d = static_cast<uint8_t>(c - '0' + 52);
            else if (c == '+') d = 62;
            else if (c == '/') d = 63;
            else continue; // skip whitespace / padding
            val = (val << 6) | d;
            valb += 6;
            if (valb >= 0) {
                out.push_back(static_cast<uint8_t>((val >> valb) & 0xFF));
                valb -= 8;
            }
        }
        return out;
    }
} // namespace pb
