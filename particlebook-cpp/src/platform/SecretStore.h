#pragma once
// ── PB-023: SecretStore — per-user encrypted key/value store ─────────
//
// Provider API keys (PB-002 AI translation, later TTS / dictionary keys) must
// not live in settings.json: that file is exported, backed up, rewritten by the
// renderer and echoed into diagnostics. SecretStore keeps them in one small
// file whose every entry is encrypted with Windows DPAPI in *current-user*
// scope (CryptProtectData / CryptUnprotectData + CRYPTPROTECT_UI_FORBIDDEN),
// so a copied file cannot be decrypted under another account or machine.
//
// Contract:
//   * Set / Get / Has / Remove / Clear. No exception escapes; failures return
//     false and LastError() explains why. LastError() never contains a value.
//   * Disk format (text, written atomically):
//
//         PBSECRET 1
//         <base64(DPAPI blob)>
//         ...
//
//     Each blob is CryptProtectData() over: u32le nameLen | name | value.
//     The *name* is inside the blob too, so the file does not even reveal
//     which providers are configured. No plaintext is ever written.
//   * An entry that cannot be decrypted (other Windows user, tampered file,
//     newer format) is kept verbatim and rewritten as-is, and reads back as
//     "not found" — a partly unreadable store never loses data and never
//     crashes. A file that is not ours at all is copied to
//     <path>.corrupt-<timestamp> before being rebuilt.
//   * The instance caches one snapshot on first use. Construct a new instance
//     (or call Reload) to observe external changes.
//
// Thread safety: every public method locks an internal mutex, so one instance
// may be shared by the UI thread and worker jobs.
//
// Linker: needs crypt32.lib (DPAPI).

#include <mutex>
#include <string>
#include <vector>

class SecretStore {
public:
    // %LOCALAPPDATA%\ParticleBook\secrets.dat — per user, per machine.
    // Empty when no per-user directory can be resolved (store then fails
    // closed instead of writing somewhere arbitrary).
    static std::string DefaultPath();

    explicit SecretStore(std::string path);
    ~SecretStore() = default;
    SecretStore(const SecretStore&) = delete;
    SecretStore& operator=(const SecretStore&) = delete;

    // Creates or overwrites one secret. Empty names, oversize data and I/O
    // errors return false. An empty value is a real value (distinct from
    // "absent") — use Remove() to delete an entry.
    bool Set(const std::string& name, const std::string& value);

    // false = not present OR the store could not be read; LastError() tells
    // which (it stays empty when the name is simply absent). "out" is left
    // untouched on failure.
    bool Get(const std::string& name, std::string& out) const;
    bool Has(const std::string& name) const;

    // false when the name was not present (nothing is written in that case).
    bool Remove(const std::string& name);

    // Drops every entry and deletes the file (including preserved-but-
    // unreadable ciphertext) — the "clear all provider keys" path.
    bool Clear();

    // Re-reads the file, discarding the cached snapshot. true when the file
    // was absent or readable.
    bool Reload() const;

    const std::string& Path() const { return m_path; }

    // Diagnostic for the most recent call. Never contains secret material
    // (only names of failures, counts and Win32 error codes).
    std::string LastError() const;

private:
    struct Entry {
        std::string name;    // empty when raw is set
        std::string value;
        std::string raw;     // undecryptable ciphertext line, written back verbatim
    };

    // Both require m_mutex to be held by the caller.
    bool LoadLocked() const;   // false = file existed but was unusable
    bool SaveLocked();

    std::string m_path;
    mutable std::mutex m_mutex;
    mutable bool m_loaded = false;
    mutable std::vector<Entry> m_entries;
    mutable std::string m_lastError;
};
