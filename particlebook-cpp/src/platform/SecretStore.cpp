#include "SecretStore.h"

// utils/encoding.h pulls utils/pb_win.h, which defines WIN32_LEAN_AND_MEAN
// before <windows.h> is seen — keep this include first so the Win32 surface
// stays lean (see pb_win.h). <dpapi.h> then adds CryptProtectData & co.
#include "utils/encoding.h"
#include "utils/base64.h"

#include <windows.h>
#include <dpapi.h>

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <string>
#include <utility>
#include <vector>

namespace {

    constexpr char kMagic[] = "PBSECRET";
    constexpr char kFormatVersion[] = "1";

    // Sanity caps. A secret is a provider key (tens of bytes); anything near
    // these limits means the file or the call is damaged, not that a legit
    // value got big.
    constexpr std::uintmax_t kMaxFileBytes = 4u * 1024u * 1024u;
    constexpr size_t kMaxNameBytes = 512;
    constexpr size_t kMaxValueBytes = 64u * 1024u;

    // DPAPI optional entropy: a constant string mixed into the key. It is not a
    // password (it ships inside the binary), but it binds the ciphertext to
    // this application, so another program that finds the file cannot decrypt
    // it with a bare CryptUnprotectData call. Changing it makes old files
    // unreadable — bump only with a migration.
    const char kEntropy[] = "ParticleBook.SecretStore.v1";

    std::string Win32Err(const char* what, DWORD code)
    {
        char buf[32];
        std::snprintf(buf, sizeof buf, " (error %lu)", static_cast<unsigned long>(code));
        return std::string(what) + buf;
    }

    // ── DPAPI wrappers ───────────────────────────────────────────────
    // CRYPTPROTECT_UI_FORBIDDEN on both directions: this app has no UI on the
    // crypto path (and must never pop a prompt from a worker thread). The
    // protection is per-user by default (no CRYPTPROTECT_LOCAL_MACHINE), i.e.
    // only the Windows account that wrote the entry can read it.

    bool Protect(const std::string& plain, std::string& cipherOut, std::string& err)
    {
        DATA_BLOB in{};
        in.cbData = static_cast<DWORD>(plain.size());
        in.pbData = plain.empty() ? nullptr
                                  : reinterpret_cast<BYTE*>(const_cast<char*>(plain.data()));

        DATA_BLOB entropy{};
        entropy.cbData = static_cast<DWORD>(sizeof(kEntropy) - 1);
        entropy.pbData = reinterpret_cast<BYTE*>(const_cast<char*>(kEntropy));

        DATA_BLOB out{};
        if (!CryptProtectData(&in, L"ParticleBook secret", &entropy, nullptr, nullptr,
                              CRYPTPROTECT_UI_FORBIDDEN, &out)) {
            err = Win32Err("CryptProtectData failed", GetLastError());
            return false;
        }
        cipherOut.assign(reinterpret_cast<const char*>(out.pbData), out.cbData);
        if (out.pbData) LocalFree(out.pbData);
        return true;
    }

    bool Unprotect(const std::string& cipher, std::string& plainOut, std::string& err)
    {
        if (cipher.empty()) {
            err = "entry ciphertext is empty";
            return false;
        }
        DATA_BLOB in{};
        in.cbData = static_cast<DWORD>(cipher.size());
        in.pbData = reinterpret_cast<BYTE*>(const_cast<char*>(cipher.data()));

        DATA_BLOB entropy{};
        entropy.cbData = static_cast<DWORD>(sizeof(kEntropy) - 1);
        entropy.pbData = reinterpret_cast<BYTE*>(const_cast<char*>(kEntropy));

        DATA_BLOB out{};
        if (!CryptUnprotectData(&in, nullptr, &entropy, nullptr, nullptr,
                                CRYPTPROTECT_UI_FORBIDDEN, &out)) {
            err = Win32Err("CryptUnprotectData failed", GetLastError());
            return false;
        }
        plainOut.assign(reinterpret_cast<const char*>(out.pbData), out.cbData);
        if (out.pbData) {
            SecureZeroMemory(out.pbData, out.cbData);
            LocalFree(out.pbData);
        }
        return true;
    }

    // ── entry payload: u32le nameLen | name | value ──────────────────
    // Length-prefixed instead of "name\0value" so keys/values containing any
    // byte (including NUL and newlines) round-trip unchanged.

    std::string PackEntry(const std::string& name, const std::string& value)
    {
        std::string payload;
        payload.reserve(4 + name.size() + value.size());
        const std::uint32_t n = static_cast<std::uint32_t>(name.size());
        payload.push_back(static_cast<char>(n & 0xFF));
        payload.push_back(static_cast<char>((n >> 8) & 0xFF));
        payload.push_back(static_cast<char>((n >> 16) & 0xFF));
        payload.push_back(static_cast<char>((n >> 24) & 0xFF));
        payload += name;
        payload += value;
        return payload;
    }

    bool UnpackEntry(const std::string& payload, std::string& name, std::string& value)
    {
        if (payload.size() < 4) return false;
        const unsigned char* b = reinterpret_cast<const unsigned char*>(payload.data());
        const std::uint32_t n = static_cast<std::uint32_t>(b[0])
                              | (static_cast<std::uint32_t>(b[1]) << 8)
                              | (static_cast<std::uint32_t>(b[2]) << 16)
                              | (static_cast<std::uint32_t>(b[3]) << 24);
        if (n == 0 || n > kMaxNameBytes) return false;
        if (static_cast<size_t>(n) > payload.size() - 4) return false;
        name.assign(payload, 4, static_cast<size_t>(n));
        value.assign(payload, 4 + static_cast<size_t>(n),
                     payload.size() - 4 - static_cast<size_t>(n));
        return true;
    }

    bool EncryptEntry(const std::string& name, const std::string& value,
                      std::string& line, std::string& err)
    {
        std::string payload = PackEntry(name, value);
        std::string cipher;
        if (!Protect(payload, cipher, err)) {
            SecureZeroMemory(&payload[0], payload.size());
            return false;
        }
        SecureZeroMemory(&payload[0], payload.size());
        line = pb::Base64Encode(cipher);
        return true;
    }

    bool DecryptEntry(const std::string& line, std::string& name, std::string& value,
                      std::string& err)
    {
        const std::vector<uint8_t> cipher = pb::Base64Decode(line);
        if (cipher.empty()) {
            err = "entry is not valid base64";
            return false;
        }
        std::string payload;
        if (!Unprotect(std::string(reinterpret_cast<const char*>(cipher.data()), cipher.size()),
                       payload, err)) {
            return false;
        }
        const bool ok = UnpackEntry(payload, name, value);
        if (!ok) err = "decrypted entry has an unexpected layout";
        if (!payload.empty()) SecureZeroMemory(&payload[0], payload.size());
        return ok;
    }

    // ── small file helpers ───────────────────────────────────────────

    std::vector<std::string> SplitLines(const std::string& data)
    {
        std::vector<std::string> lines;
        std::string cur;
        for (char c : data) {
            if (c == '\n') {
                lines.push_back(cur);
                cur.clear();
            } else if (c != '\r') {
                cur.push_back(c);
            }
        }
        if (!cur.empty()) lines.push_back(cur);
        return lines;
    }

    // Missing file is not an error (fresh store). false = present but unreadable.
    bool ReadWholeFile(const std::string& path, std::string& out, bool& exists, std::string& err)
    {
        exists = false;
        std::error_code ec;
        if (!std::filesystem::exists(path, ec)) return true;
        if (std::filesystem::is_directory(path, ec)) {
            err = "secret store path is a directory";
            return false;
        }
        const std::uintmax_t size = std::filesystem::file_size(path, ec);
        if (ec) {
            err = "secret store file size cannot be read";
            return false;
        }
        if (size > kMaxFileBytes) {
            err = "secret store file is implausibly large";
            return false;
        }
        std::ifstream f(path, std::ios::binary);
        if (!f.is_open()) {
            err = "secret store file cannot be opened";
            return false;
        }
        out.assign(std::istreambuf_iterator<char>(f), std::istreambuf_iterator<char>());
        if (f.bad()) {
            err = "secret store file read failed";
            return false;
        }
        exists = true;
        return true;
    }

    // Copies a file we cannot interpret aside before it is rebuilt. Ciphertext
    // only — never plaintext — so the backup is as safe as the original.
    void BackupCorruptFile(const std::string& path)
    {
        char ts[32] = {};
        const time_t now = std::time(nullptr);
        tm local{};
        localtime_s(&local, &now);
        std::strftime(ts, sizeof ts, "%Y%m%d-%H%M%S", &local);
        CopyFileW(pb::Utf8ToWide(path).c_str(),
                  pb::Utf8ToWide(path + ".corrupt-" + ts).c_str(), FALSE);
    }

} // namespace

// ── ctor / diagnostics ──────────────────────────────────────────────

SecretStore::SecretStore(std::string path) : m_path(std::move(path)) {}

std::string SecretStore::LastError() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_lastError;
}

std::string SecretStore::DefaultPath()
{
    constexpr DWORD kBuf = 1024;
    wchar_t buf[kBuf] = {};
    DWORD n = GetEnvironmentVariableW(L"LOCALAPPDATA", buf, kBuf);
    std::string base;
    if (n > 0 && n < kBuf) {
        base = pb::WideToUtf8(std::wstring(buf, n));
    } else {
        // Fallback for stripped environments (services / odd shells).
        DWORD m = GetEnvironmentVariableW(L"USERPROFILE", buf, kBuf);
        if (m > 0 && m < kBuf) {
            base = pb::WideToUtf8(std::wstring(buf, m)) + "\\AppData\\Local";
        }
    }
    if (base.empty()) return std::string();
    if (base.back() != '\\') base += '\\';
    return base + "ParticleBook\\secrets.dat";
}

// ── load / save (m_mutex held) ──────────────────────────────────────

bool SecretStore::LoadLocked() const
{
    m_loaded = true;
    m_entries.clear();

    std::string data;
    bool exists = false;
    std::string err;
    if (!ReadWholeFile(m_path, data, exists, err)) {
        m_lastError = err.empty() ? "secret store is unreadable" : err;
        return false;
    }
    if (!exists || data.empty()) return true;   // fresh store

    const std::vector<std::string> lines = SplitLines(data);

    // Header "PBSECRET 1". Anything else means the file was not written by
    // this class — keep a copy instead of silently destroying it.
    size_t i = 0;
    while (i < lines.size() && lines[i].empty()) ++i;
    if (i >= lines.size()) return true;         // whitespace only: empty store
    if (lines[i] != std::string(kMagic) + " " + kFormatVersion) {
        m_lastError = "secret store file has an unknown format (copied to .corrupt-*)";
        BackupCorruptFile(m_path);
        return false;
    }
    ++i;

    size_t unreadable = 0;
    for (; i < lines.size(); ++i) {
        if (lines[i].empty()) continue;
        Entry e;
        std::string entryErr;
        if (DecryptEntry(lines[i], e.name, e.value, entryErr)) {
            m_entries.push_back(std::move(e));
        } else {
            // Written by another Windows user, tampered with, or a newer
            // format. Hold the ciphertext so the next save rewrites it
            // unchanged; it simply reads back as "not found" here.
            Entry raw;
            raw.raw = lines[i];
            m_entries.push_back(std::move(raw));
            ++unreadable;
        }
    }
    if (unreadable > 0) {
        m_lastError = std::to_string(unreadable)
                    + (unreadable == 1 ? " secret entry could not be decrypted"
                                       : " secret entries could not be decrypted");
    }
    return true;
}

bool SecretStore::SaveLocked()
{
    if (m_path.empty()) {
        m_lastError = "secret store has no path";
        return false;
    }

    std::string text;
    text.reserve(64 + m_entries.size() * 160);
    text += kMagic;
    text += ' ';
    text += kFormatVersion;
    text += '\n';
    for (const Entry& e : m_entries) {
        if (!e.raw.empty()) {        // preserved undecryptable ciphertext
            text += e.raw;
            text += '\n';
            continue;
        }
        std::string line;
        if (!EncryptEntry(e.name, e.value, line, m_lastError)) return false;
        text += line;
        text += '\n';
    }

    std::error_code ec;
    const std::filesystem::path parent = std::filesystem::path(m_path).parent_path();
    if (!parent.empty()) std::filesystem::create_directories(parent, ec);

    // Atomic replace: a crash leaves either the previous file or a temp file,
    // never a half-written store (same pattern as DatabaseService::WriteAtomic).
    static std::atomic<unsigned> seq{0};
    const std::string tmpPath = m_path + ".tmp." + std::to_string(seq++);
    {
        std::ofstream f(tmpPath, std::ios::binary | std::ios::trunc);
        if (!f.is_open()) {
            m_lastError = "secret store temp file cannot be opened";
            return false;
        }
        f.write(text.data(), static_cast<std::streamsize>(text.size()));
        f.flush();
        if (f.fail()) {
            f.close();
            DeleteFileW(pb::Utf8ToWide(tmpPath).c_str());
            m_lastError = "writing the secret store failed (disk full or locked)";
            return false;
        }
    }
    if (!MoveFileExW(pb::Utf8ToWide(tmpPath).c_str(), pb::Utf8ToWide(m_path).c_str(),
                     MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
        const DWORD code = GetLastError();
        DeleteFileW(pb::Utf8ToWide(tmpPath).c_str());
        m_lastError = Win32Err("cannot replace the secret store file", code);
        return false;
    }
    return true;
}

// ── public API ──────────────────────────────────────────────────────

bool SecretStore::Set(const std::string& name, const std::string& value)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    m_lastError.clear();

    if (name.empty()) {
        m_lastError = "secret name is empty";
        return false;
    }
    if (name.size() > kMaxNameBytes) {
        m_lastError = "secret name is too long";
        return false;
    }
    if (value.size() > kMaxValueBytes) {
        m_lastError = "secret value is too long";
        return false;
    }
    if (!m_loaded) LoadLocked();

    for (Entry& e : m_entries) {
        if (e.raw.empty() && e.name == name) {
            e.value = value;
            return SaveLocked();
        }
    }
    Entry e;
    e.name = name;
    e.value = value;
    m_entries.push_back(std::move(e));
    return SaveLocked();
}

bool SecretStore::Get(const std::string& name, std::string& out) const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    m_lastError.clear();

    if (name.empty()) {
        m_lastError = "secret name is empty";
        return false;
    }
    if (!m_loaded) LoadLocked();

    for (const Entry& e : m_entries) {
        if (e.raw.empty() && e.name == name) {
            out = e.value;
            return true;
        }
    }
    return false;   // absent: LastError() stays empty
}

bool SecretStore::Has(const std::string& name) const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    m_lastError.clear();

    if (name.empty()) {
        m_lastError = "secret name is empty";
        return false;
    }
    if (!m_loaded) LoadLocked();

    for (const Entry& e : m_entries) {
        if (e.raw.empty() && e.name == name) return true;
    }
    return false;
}

bool SecretStore::Remove(const std::string& name)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    m_lastError.clear();

    if (name.empty()) {
        m_lastError = "secret name is empty";
        return false;
    }
    if (!m_loaded) LoadLocked();

    for (auto it = m_entries.begin(); it != m_entries.end(); ++it) {
        if (it->raw.empty() && it->name == name) {
            m_entries.erase(it);
            return SaveLocked();
        }
    }
    return false;   // absent: nothing written, LastError() stays empty
}

bool SecretStore::Clear()
{
    std::lock_guard<std::mutex> lock(m_mutex);
    m_lastError.clear();

    if (m_path.empty()) {
        m_lastError = "secret store has no path";
        return false;
    }
    if (!m_loaded) LoadLocked();

    // The file itself goes away: no old ciphertext (including preserved
    // undecryptable lines) survives "clear all".
    m_entries.clear();
    if (!DeleteFileW(pb::Utf8ToWide(m_path).c_str())) {
        const DWORD code = GetLastError();
        if (code != ERROR_FILE_NOT_FOUND && code != ERROR_PATH_NOT_FOUND) {
            m_lastError = Win32Err("cannot delete the secret store file", code);
            return false;
        }
    }
    return true;
}

bool SecretStore::Reload() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    m_lastError.clear();
    return LoadLocked();
}
