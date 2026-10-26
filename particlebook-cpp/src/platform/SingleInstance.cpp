#include "SingleInstance.h"
#include "nlohmann/json.hpp"

#include <chrono>
#include <cstdio>
#include <thread>

namespace {
constexpr wchar_t kMutexName[] = L"Local\\ParticleBook.SingleInstance";
constexpr wchar_t kPipeName[]  = L"\\\\.\\pipe\\ParticleBook.SingleInstance";
constexpr DWORD kMaxPayload = 1024 * 1024;
}

SingleInstance::SingleInstance()
{
    m_mutex = CreateMutexW(nullptr, FALSE, kMutexName);
    m_primary = (m_mutex != nullptr) && (GetLastError() != ERROR_ALREADY_EXISTS);
    if (!m_primary && m_mutex) {
        CloseHandle(m_mutex);
        m_mutex = nullptr;
    }
}

SingleInstance::~SingleInstance()
{
    Stop();
    if (m_mutex) {
        CloseHandle(m_mutex);
        m_mutex = nullptr;
    }
}

bool SingleInstance::Acquire()
{
    return m_primary;
}

bool SingleInstance::Forward(const std::vector<std::string>& paths) const
{
    if (paths.empty()) return true;

    nlohmann::json j;
    j["paths"] = paths;
    const std::string payload = j.dump();

    // The primary may not have created its pipe yet (second launch during
    // startup). Retry for ~2 seconds before giving up.
    for (int attempt = 0; attempt < 40; ++attempt) {
        HANDLE pipe = CreateFileW(kPipeName, GENERIC_WRITE, 0, nullptr,
                                  OPEN_EXISTING, 0, nullptr);
        if (pipe != INVALID_HANDLE_VALUE) {
            DWORD written = 0;
            const BOOL ok = WriteFile(pipe, payload.data(),
                                      static_cast<DWORD>(payload.size()),
                                      &written, nullptr);
            CloseHandle(pipe);
            if (ok && written == payload.size()) return true;
            return false;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }
    return false;
}

bool SingleInstance::StartServer(std::function<void(std::vector<std::string>)> onPaths)
{
    if (!m_primary || m_running.exchange(true)) return false;

    {
        std::lock_guard<std::mutex> lock(m_callbackMutex);
        m_callback = std::move(onPaths);
    }
    m_stopEvent = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    m_thread = std::thread(&SingleInstance::ServerLoop, this);
    return true;
}

void SingleInstance::Stop()
{
    if (!m_running.exchange(false)) {
        if (m_thread.joinable()) m_thread.join();
        if (m_stopEvent) { CloseHandle(m_stopEvent); m_stopEvent = nullptr; }
        return;
    }

    if (m_stopEvent) SetEvent(m_stopEvent);

    // Unblock ConnectNamedPipe/ReadFile: the server wakes up with EOF and then
    // observes m_running == false.
    HANDLE dummy = CreateFileW(kPipeName, GENERIC_WRITE, 0, nullptr,
                               OPEN_EXISTING, 0, nullptr);
    if (dummy != INVALID_HANDLE_VALUE) CloseHandle(dummy);

    if (m_thread.joinable()) m_thread.join();
    if (m_stopEvent) { CloseHandle(m_stopEvent); m_stopEvent = nullptr; }
    std::lock_guard<std::mutex> lock(m_callbackMutex);
    m_callback = nullptr;
}

void SingleInstance::ServerLoop()
{
    HANDLE pipe = CreateNamedPipeW(
        kPipeName,
        PIPE_ACCESS_INBOUND,
        PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT,
        1, 64 * 1024, 64 * 1024, 0, nullptr);
    if (pipe == INVALID_HANDLE_VALUE) return;

    while (m_running.load()) {
        const BOOL connected = ConnectNamedPipe(pipe, nullptr);
        const DWORD err = connected ? 0 : GetLastError();
        if (!m_running.load()) break;
        if (!connected && err != ERROR_PIPE_CONNECTED) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
            continue;
        }

        std::string payload;
        char buf[4096];
        for (;;) {
            DWORD read = 0;
            if (!ReadFile(pipe, buf, sizeof(buf), &read, nullptr) || read == 0) break;
            if (payload.size() + read > kMaxPayload) break;
            payload.append(buf, read);
        }
        DisconnectNamedPipe(pipe);

        if (payload.empty()) continue;
        std::vector<std::string> paths;
        if (!DecodePaths(payload, paths) || paths.empty()) continue;

        std::function<void(std::vector<std::string>)> cb;
        {
            std::lock_guard<std::mutex> lock(m_callbackMutex);
            cb = m_callback;
        }
        if (cb) {
            try { cb(std::move(paths)); } catch (...) { /* never kill the server */ }
        }
    }

    CloseHandle(pipe);
}

bool SingleInstance::DecodePaths(const std::string& payload, std::vector<std::string>& out)
{
    try {
        const auto j = nlohmann::json::parse(payload);
        const auto it = j.find("paths");
        if (it == j.end() || !it->is_array()) return false;
        for (const auto& v : *it) {
            if (v.is_string()) out.push_back(v.get<std::string>());
        }
        return true;
    } catch (...) {
        return false;
    }
}
