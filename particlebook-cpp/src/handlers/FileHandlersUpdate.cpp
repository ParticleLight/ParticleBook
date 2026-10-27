// PB-024-A：从 FileHandlers.cpp 拆出的更新检查实现（纯搬移，行为不变）。
#include "FileHandlersUpdate.h"

#include "utils/encoding.h"
#include "utils/update_meta.h"
#include "platform/LogService.h"
#include "pb_version.h"

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <winhttp.h>
#include <bcrypt.h>
#include <cstdlib>
#include <string>

#pragma comment(lib, "winhttp.lib")
#pragma comment(lib, "bcrypt.lib")

using json = nlohmann::json;

// ── SHA-512 of a file (BCrypt) — for update package integrity check ──────
bool ComputeFileSha512(const std::wstring& path, std::string& outHex)
{
    HANDLE hFile = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ,
                               nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (hFile == INVALID_HANDLE_VALUE) return false;

    BCRYPT_ALG_HANDLE hAlg = nullptr;
    BCRYPT_HASH_HANDLE hHash = nullptr;
    bool ok = false;
    do {
        if (!BCRYPT_SUCCESS(BCryptOpenAlgorithmProvider(&hAlg, BCRYPT_SHA512_ALGORITHM, nullptr, 0))) break;
        if (!BCRYPT_SUCCESS(BCryptCreateHash(hAlg, &hHash, nullptr, 0, nullptr, 0, 0))) break;
        char buf[65536];
        DWORD br = 0;
        bool readOk = true;
        while (ReadFile(hFile, buf, sizeof(buf), &br, nullptr) && br > 0) {
            if (!BCRYPT_SUCCESS(BCryptHashData(hHash, (PUCHAR)buf, br, 0))) { readOk = false; break; }
        }
        if (!readOk) break;
        UCHAR hash[64];
        if (!BCRYPT_SUCCESS(BCryptFinishHash(hHash, hash, sizeof(hash), 0))) break;
        static const char* hexc = "0123456789abcdef";
        outHex.clear();
        outHex.reserve(128);
        for (int i = 0; i < 64; i++) {
            outHex += hexc[hash[i] >> 4];
            outHex += hexc[hash[i] & 0xF];
        }
        ok = true;
    } while (false);

    if (hHash) BCryptDestroyHash(hHash);
    if (hAlg) BCryptCloseAlgorithmProvider(hAlg, 0);
    CloseHandle(hFile);
    return ok;
}

// ── Update check (runs on a background thread) ──────────────────────────
// Returns the update info json, or json(nullptr) when no update is available
// or the check failed. Blocking WinHTTP — never call on the UI thread.
nlohmann::json CheckUpdateImpl()
{
    // The VERSION-CHECK hosts are configurable via env vars:
    //   PB_UPDATE_BASE = host that serves releases/latest/download/latest.yml
    //                    (default github.com; only the HOST is configurable,
    //                     the path below is fixed)
    //   PB_UPDATE_API  = JSON API host (default api.github.com)
    // Defaults keep the official GitHub behaviour when unset.
    //
    // NOTE: only the check is configurable. The download URL is always built
    // for github.com by pb::BuildDownloadUrl and then enforced by the
    // kOfficialPrefix whitelist in app:downloadUpdate — so a mirror can
    // report a version, but its installer will be rejected. Widening this
    // requires also relaxing that whitelist (see docs/IMPROVEMENT_PLAN_R2.md
    // §1.2) and is deliberately out of scope.
    std::string baseHost = "github.com";
    std::string apiHost  = "api.github.com";
    if (const char* e = std::getenv("PB_UPDATE_BASE")) { if (*e) baseHost = e; }
    if (const char* e = std::getenv("PB_UPDATE_API"))  { if (*e) apiHost = e; }

    std::wstring wBase = pb::Utf8ToWide(baseHost);
    std::wstring wApi  = pb::Utf8ToWide(apiHost);

    // Fetch latest.yml from GitHub Releases (no API rate limit)
    HINTERNET hS = WinHttpOpen(L"PB/2.0", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY, nullptr, nullptr, 0);
    if (!hS) {
        pb::LogWarn("update", "update check: WinHttpOpen failed");
        return json(nullptr);
    }

    HINTERNET hC = WinHttpConnect(hS, wBase.c_str(), 443, 0);
    if (!hC) {
        WinHttpCloseHandle(hS);
        pb::LogWarn("update", "update check: connect failed (" + baseHost + ")");
        return json(nullptr);
    }

    // Try latest.yml from releases/latest/download/ (redirects handled by WinHTTP)
    HINTERNET hR = WinHttpOpenRequest(hC, L"GET",
        L"/ParticleLight/ParticleBook/releases/latest/download/latest.yml",
        nullptr, nullptr, nullptr, WINHTTP_FLAG_SECURE);
    if (!hR) {
        WinHttpCloseHandle(hC); WinHttpCloseHandle(hS);
        pb::LogWarn("update", "update check: open request failed");
        return json(nullptr);
    }

    WinHttpAddRequestHeaders(hR, L"User-Agent: ParticleBook/2.0\r\n", (DWORD)-1, WINHTTP_ADDREQ_FLAG_ADD);

    if (!WinHttpSendRequest(hR, nullptr, 0, nullptr, 0, 0, 0) || !WinHttpReceiveResponse(hR, nullptr)) {
        // Fallback to API
        WinHttpCloseHandle(hR); WinHttpCloseHandle(hC); WinHttpCloseHandle(hS);
        pb::LogDebug("update", "update check: latest.yml unavailable (" + baseHost +
                                   "), falling back to GitHub API");

        hS = WinHttpOpen(L"PB/2.0", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY, nullptr, nullptr, 0);
        if (!hS) {
            pb::LogWarn("update", "update check: WinHttpOpen failed (api fallback)");
            return json(nullptr);
        }
        hC = WinHttpConnect(hS, wApi.c_str(), 443, 0);
        if (!hC) {
            WinHttpCloseHandle(hS);
            pb::LogWarn("update", "update check: connect failed (" + apiHost + ")");
            return json(nullptr);
        }
        hR = WinHttpOpenRequest(hC, L"GET", L"/repos/ParticleLight/ParticleBook/releases/latest",
            nullptr, nullptr, nullptr, WINHTTP_FLAG_SECURE);
        if (!hR) {
            WinHttpCloseHandle(hC); WinHttpCloseHandle(hS);
            pb::LogWarn("update", "update check: API open request failed");
            return json(nullptr);
        }
        WinHttpAddRequestHeaders(hR, L"User-Agent: ParticleBook/2.0\r\nAccept: application/vnd.github+json\r\n", (DWORD)-1, WINHTTP_ADDREQ_FLAG_ADD);
        if (!WinHttpSendRequest(hR, nullptr, 0, nullptr, 0, 0, 0) || !WinHttpReceiveResponse(hR, nullptr)) {
            WinHttpCloseHandle(hR); WinHttpCloseHandle(hC); WinHttpCloseHandle(hS);
            pb::LogWarn("update", "update check: API network error (" + apiHost + ")");
            return json(nullptr);
        }
    }

    std::string body;
    DWORD br; char buf[8192];
    while (WinHttpReadData(hR, buf, sizeof(buf), &br) && br > 0) body.append(buf, br);
    WinHttpCloseHandle(hR); WinHttpCloseHandle(hC); WinHttpCloseHandle(hS);

    // Parse update metadata. Prefer electron-builder latest.yml (pure,
    // unit-tested parse in utils/update_meta.h); fall back to GitHub API JSON.
    std::string latestVer, dlUrl, fileName, sha512;
    std::size_t size = 0;

    auto y = pb::ParseLatestYaml(body);
    if (y.valid) {
        latestVer = y.version;
        fileName  = y.fileName;
        sha512    = y.sha512;
        size      = y.size;
        dlUrl     = pb::BuildDownloadUrl(latestVer, fileName);
    } else {
        // Try JSON format (GitHub API response)
        try {
            auto release = json::parse(body);
            std::string tag = release.value("tag_name", "");
            if (!tag.empty()) {
                latestVer = (tag[0] == 'v') ? tag.substr(1) : tag;
                for (auto& asset : release["assets"]) {
                    std::string name = asset.value("name", "");
                    if (name.find("-Setup-") != std::string::npos && name.find(".exe") != std::string::npos) {
                        fileName = name;
                        dlUrl = asset.value("browser_download_url", "");
                        size = asset.value("size", 0);
                        break;
                    }
                }
            }
        } catch (...) {}
    }

    if (latestVer.empty() || dlUrl.empty()) {
        // 网络通了但内容不是预期格式（代理注入页面 / API 改版），不当作错误刷日志。
        pb::LogDebug("update", "update check: no usable release metadata in response");
        return json(nullptr);
    }

    // Semantic version comparison (pure, unit-tested in utils/update_meta.h)
    if (pb::VersionGreater(latestVer, PB_VERSION_STRING)) {
        json result;
        result["version"] = latestVer;
        result["fileName"] = fileName;
        result["downloadUrl"] = dlUrl;
        result["size"] = size;
        result["sha512"] = sha512;
        return result;
    }
    return json(nullptr);
}


