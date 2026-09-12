#pragma once
// Update metadata parsing (pure, testable)
// Extracted from FileHandlers::CheckUpdateImpl so the YAML-free parsing and
// semantic-version comparison are unit-testable (no network / no nlohmann).

#include <string>
#include <cstdio>
#include <cstddef>
#include <tuple>
#include <vector>

namespace pb {

    // True if version a is GREATER than b (numeric semver compare).
    inline bool VersionGreater(const std::string& a, const std::string& b)
    {
        auto split = [](const std::string& v) -> std::tuple<int,int,int> {
            // Tolerate a leading 'v'/'V' (GitHub tags) before the numeric part.
            const char* p = v.c_str();
            while (*p && (*p < '0' || *p > '9')) p++;
            int major = 0, minor = 0, patch = 0;
            std::sscanf(p, "%d.%d.%d", &major, &minor, &patch);
            return {major, minor, patch};
        };
        auto [a1,a2,a3] = split(a);
        auto [b1,b2,b3] = split(b);
        if (a1 != b1) return a1 > b1;
        if (a2 != b2) return a2 > b2;
        return a3 > b3;
    }

    struct UpdateYaml {
        std::string version;
        std::string fileName;
        std::string sha512;
        std::size_t size = 0;
        bool valid = false;   // version AND fileName present
    };

    // Parse an electron-builder style latest.yml.
    //
    // Handles both this project's own single-file yaml (see
    // .github/workflows/release.yml) and electron-builder's multi-file output,
    // where the "files:" list can also contain a ".exe.blockmap" entry next to
    // the installer. The installer entry is selected EXPLICITLY rather than by
    // "first url wins", which would silently pick a blockmap if the order ever
    // changed. Falls back to the first entry when nothing looks like an exe.
    //
    // Assumes each entry puts "url:" before its "sha512:"/"size:" (true for
    // both formats above); values are read from within the chosen entry, with a
    // document-wide fallback so unusual layouts keep working.
    inline UpdateYaml ParseLatestYaml(const std::string& body)
    {
        UpdateYaml out;

        // Value following `key` on the same line, searched from `from`.
        auto readValue = [&](size_t from, const char* key) -> std::string {
            std::string v;
            size_t p = body.find(key, from);
            if (p == std::string::npos) return v;
            p += std::char_traits<char>::length(key);
            while (p < body.size() && body[p] == ' ') p++;
            size_t e = body.find('\n', p);
            if (e != std::string::npos) v = body.substr(p, e - p);
            while (!v.empty() && (v.back() == '\r' || v.back() == ' ')) v.pop_back();
            return v;
        };

        out.version = readValue(0, "version:");

        // Every url: entry, with its offset, in document order.
        struct Hit { size_t pos; std::string value; };
        std::vector<Hit> hits;
        for (size_t p = body.find("url:"); p != std::string::npos;
             p = body.find("url:", p + 4)) {
            hits.push_back({ p, readValue(p, "url:") });
        }
        if (hits.empty()) return out;   // valid stays false

        size_t chosen = 0;
        for (size_t i = 0; i < hits.size(); ++i) {
            const std::string& u = hits[i].value;
            const bool isExe = u.size() >= 4 && u.compare(u.size() - 4, 4, ".exe") == 0;
            const bool isBlockmap = u.find(".blockmap") != std::string::npos;
            if (isExe && !isBlockmap) { chosen = i; break; }
        }
        out.fileName = hits[chosen].value;

        // End of the chosen entry: the next url:, or end of document.
        const size_t winEnd = (chosen + 1 < hits.size()) ? hits[chosen + 1].pos
                                                         : std::string::npos;
        auto readInEntry = [&](const char* key) -> std::string {
            size_t p = body.find(key, hits[chosen].pos);
            if (p == std::string::npos) return std::string();
            if (winEnd != std::string::npos && p >= winEnd) return std::string();
            return readValue(hits[chosen].pos, key);
        };
        auto readWithFallback = [&](const char* key) -> std::string {
            std::string v = readInEntry(key);
            return v.empty() ? readValue(0, key) : v;
        };

        out.sha512 = readWithFallback("sha512:");

        // Strip surrounding quotes from sha512
        std::string& sh = out.sha512;
        while (!sh.empty() && (sh.back() == '\r' || sh.back() == ' ' || sh.back() == '"')) sh.pop_back();
        while (!sh.empty() && sh.front() == '"') sh.erase(sh.begin());

        { std::string sv = readWithFallback("size:");
          if (!sv.empty()) { try { out.size = std::stoull(sv); } catch (...) {} } }

        out.valid = !out.version.empty() && !out.fileName.empty();
        return out;
    }

    // Build the download URL for the release asset. Fixed to the official
    // GitHub release path by design: PB_UPDATE_BASE only redirects the version
    // CHECK, while FileHandlers::app:downloadUpdate enforces a matching URL
    // whitelist (plus SHA-512) before downloading. Do not make this
    // configurable without also revisiting that whitelist.
    inline std::string BuildDownloadUrl(const std::string& version, const std::string& fileName)
    {
        return "https://github.com/ParticleLight/ParticleBook/releases/download/v"
             + version + "/" + fileName;
    }

} // namespace pb