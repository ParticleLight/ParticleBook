#pragma once
// Update metadata parsing (pure, testable)
// Extracted from FileHandlers::CheckUpdateImpl so the YAML-free parsing and
// semantic-version comparison are unit-testable (no network / no nlohmann).

#include <string>
#include <cstdio>
#include <cstddef>
#include <tuple>

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

    // Parse electron-builder latest.yml text block.
    inline UpdateYaml ParseLatestYaml(const std::string& body)
    {
        UpdateYaml out;
        auto field = [&](const char* key) -> std::string {
            std::string v;
            size_t p = body.find(key);
            if (p == std::string::npos) return v;
            p += std::char_traits<char>::length(key);
            while (p < body.size() && body[p] == ' ') p++;
            size_t e = body.find('\n', p);
            if (e != std::string::npos) v = body.substr(p, e - p);
            while (!v.empty() && (v.back() == '\r' || v.back() == ' ')) v.pop_back();
            return v;
        };

        out.version  = field("version:");
        out.fileName = field("url:");
        out.sha512   = field("sha512:");

        // Strip surrounding quotes from sha512
        std::string& sh = out.sha512;
        while (!sh.empty() && (sh.back() == '\r' || sh.back() == ' ' || sh.back() == '"')) sh.pop_back();
        while (!sh.empty() && sh.front() == '"') sh.erase(sh.begin());

        { std::string sv = field("size:"); if (!sv.empty()) { try { out.size = std::stoull(sv); } catch (...) {} } }

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
