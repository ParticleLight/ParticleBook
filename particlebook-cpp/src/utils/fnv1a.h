#pragma once
// ── FNV-1a 64-bit hash ─────────────────────────────────────────────
// Deterministic, stable across processes & compilers (unlike std::hash,
// which is implementation-defined). Used for cache keys / virtual-host
// file names so the same path always yields the same key.
// Output: 16 lowercase hex chars (pure ASCII, URL-safe).
// Header-only, inline.

#include <cstdint>
#include <string>

namespace pb {
    inline uint64_t Fnv1a64(const char* data, size_t len)
    {
        uint64_t h = 14695981039346656037ULL; // FNV offset basis
        const uint64_t prime = 1099511628211ULL;   // FNV prime
        for (size_t i = 0; i < len; i++) {
            h ^= static_cast<uint8_t>(data[i]);
            h *= prime;
        }
        return h;
    }

    inline uint64_t Fnv1a64(const std::string& s)
    {
        return Fnv1a64(s.data(), s.size());
    }

    // 16 lowercase hex chars.
    inline std::string Fnv1a64Hex(const std::string& s)
    {
        static const char* hexc = "0123456789abcdef";
        uint64_t h = Fnv1a64(s);
        std::string out;
        out.reserve(16);
        for (int i = 0; i < 16; i++) {
            out += hexc[(h >> (60 - i * 4)) & 0xF];
        }
        return out;
    }
} // namespace pb
