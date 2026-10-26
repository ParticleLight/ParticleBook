#include "UiDispatcher.h"

#include <cstdio>
#include <exception>

namespace pb {
namespace {

// 任务体抛异常时只记录、不外传：异常穿过 Win32 消息循环会终止进程，
// 而 P0-3 的要求是「异常要进日志」。
void ReportTaskFailure(const char* what) noexcept
{
    char buffer[512];
    std::snprintf(buffer, sizeof buffer, "[UiDispatcher] task threw: %s\n",
                  what ? what : "(unknown)");
    OutputDebugStringA(buffer);
}

} // namespace

UiDispatcher::UiDispatcher(HWND hwnd)
    : m_hwnd(hwnd)
{
}

UiDispatcher::~UiDispatcher()
{
    Stop();
}

UINT UiDispatcher::DispatchMessageId()
{
    static const UINT id = RegisterWindowMessageW(L"ParticleBook.UiDispatcher.v1");
    return id;
}

bool UiDispatcher::ReserveKickLocked(HWND& outHwnd)
{
    // 队列为空时绝不补消息：否则 Drain 收尾的补踢会自己喂自己，
    // 消息循环变成永远跑不完的 WM_APP 乒乓（实测过）。
    if (m_tasks.empty()) return false;
    if (m_hwnd == nullptr || m_kickPending || m_stopped.load(std::memory_order_relaxed)) {
        return false;
    }
    m_kickPending = true;
    outHwnd = m_hwnd;
    return true;
}

void UiDispatcher::Post(std::function<void()> task)
{
    if (!task) return;

    HWND target = nullptr;
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        if (m_stopped.load(std::memory_order_relaxed)) {
            m_dropped.fetch_add(1, std::memory_order_relaxed);
            return;
        }
        m_tasks.push_back(std::move(task));
        if (!ReserveKickLocked(target)) return;
    }

    if (!PostMessageW(target, DispatchMessageId(), 0, 0)) {
        // 窗口已销毁：清掉在途标记，交给 Attach()/下一次 Post 重试。
        std::lock_guard<std::mutex> lock(m_mutex);
        m_kickPending = false;
    }
}

bool UiDispatcher::OnWindowMessage(UINT message)
{
    if (message != DispatchMessageId()) return false;
    Drain();
    return true;
}

std::size_t UiDispatcher::Drain()
{
    HWND kickTarget = nullptr;
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        if (m_stopped.load(std::memory_order_relaxed) || m_draining) {
            // 已关闭，或本次是嵌套消息泵里的重入 —— 外层那层会负责清空队列。
            return 0;
        }
        m_draining = true;
        m_drainThread = std::this_thread::get_id();
        m_kickPending = false;   // 本条消息已经消费掉了唤醒名额
    }

    std::size_t ran = 0;
    for (;;) {
        std::vector<std::function<void()>> batch;
        {
            std::lock_guard<std::mutex> lock(m_mutex);
            if (m_stopped.load(std::memory_order_relaxed) || m_tasks.empty()) break;
            batch.swap(m_tasks);
        }

        for (std::function<void()>& task : batch) {
            if (m_stopped.load(std::memory_order_relaxed)) break;  // 任务里 Stop() 了
            ++ran;
            try {
                task();
            } catch (const std::exception& e) {
                ReportTaskFailure(e.what());
            } catch (...) {
                ReportTaskFailure("unknown exception");
            }
        }

        if (m_stopped.load(std::memory_order_relaxed)) break;
    }

    {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_draining = false;
        m_drainThread = std::thread::id{};
        // 收尾阶段又进队列的任务需要再踢一脚，否则要等下一次 Post 才有人跑。
        ReserveKickLocked(kickTarget);
    }
    m_idleCv.notify_all();

    if (kickTarget != nullptr) {
        PostMessageW(kickTarget, DispatchMessageId(), 0, 0);
    }
    return ran;
}

void UiDispatcher::Stop()
{
    std::unique_lock<std::mutex> lock(m_mutex);
    m_stopped.store(true, std::memory_order_release);

    const std::size_t dropped = m_tasks.size();
    if (dropped > 0) {
        m_dropped.fetch_add(dropped, std::memory_order_relaxed);
        std::vector<std::function<void()>>().swap(m_tasks);
    }
    m_kickPending = false;

    // join 语义：等另一线程上正在跑的 Drain 收尾（它可能在执行
    // 已经在用的回调，此时拆对象会 UAF）。任务体内调用时不自我等待。
    if (m_draining && m_drainThread != std::this_thread::get_id()) {
        m_idleCv.wait(lock, [this] { return !m_draining; });
    }
}

void UiDispatcher::Attach(HWND hwnd)
{
    HWND target = nullptr;
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_hwnd = hwnd;
        if (hwnd != nullptr) {
            // 绑定前积压的任务先跑起来（启动顺序：服务先 Submit，窗口后建）。
            ReserveKickLocked(target);
        } else {
            m_kickPending = false;
        }
    }
    if (target != nullptr) {
        PostMessageW(target, DispatchMessageId(), 0, 0);
    }
}

HWND UiDispatcher::Hwnd() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_hwnd;
}

std::size_t UiDispatcher::PendingCount() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_tasks.size();
}

} // namespace pb
