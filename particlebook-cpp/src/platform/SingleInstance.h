#pragma once
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <string>
#include <vector>
#include <functional>
#include <thread>
#include <atomic>
#include <mutex>

// PB-019: one ParticleBook process per user session. A second launch forwards
// its command-line paths to the primary instance over a named pipe and exits.
// The mutex/pipe live in the per-session "Local\" namespace, so different
// Windows users never share a pipe or DB writer.
class SingleInstance {
public:
    SingleInstance();
    ~SingleInstance();

    // Returns true when this process owns the mutex (primary). A secondary
    // instance must call Forward() and exit.
    bool Acquire();
    bool IsPrimary() const { return m_primary; }

    // Delivers paths to the primary instance. Retries briefly because the
    // primary may still be starting its pipe server.
    bool Forward(const std::vector<std::string>& paths) const;

    // Starts the pipe server. onPaths is invoked on a background thread; the
    // caller must marshal to the UI thread itself.
    bool StartServer(std::function<void(std::vector<std::string>)> onPaths);
    void Stop();

private:
    void ServerLoop();
    static bool DecodePaths(const std::string& payload, std::vector<std::string>& out);

    HANDLE m_mutex = nullptr;
    HANDLE m_stopEvent = nullptr;
    bool m_primary = false;
    std::atomic<bool> m_running{false};
    std::thread m_thread;
    std::mutex m_callbackMutex;
    std::function<void(std::vector<std::string>)> m_callback;
};
