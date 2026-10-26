#pragma once
// ── PB-020：UI 线程投递通道（单条 WM_APP 消息 + std::function 队列）──
//
// 替代散落的 WM_USER+N + 裸 LPARAM 指针：全工程只用一条消息
// （kDispatchMessage = WM_APP + 1），任务体放在堆上的队列里，因此
//   * LPARAM 永远不需要携带指针（不再有栈变量地址被跨消息使用的隐患）；
//   * PostMessage 天然合并 —— N 次 Post 不会把消息队列灌爆；
//   * 队列由 std::function 组成，事件不需要先序列化成整数类型。
//
// 线程契约：
//   Post()                 任意线程（含 UI 线程）
//   Drain()/OnWindowMessage UI 线程（WndProc 里）
//   Stop()                 任意线程；任务体内调用也安全（不自我等待）
//
// WndProc 接法：
//     case WM_APP + 1:  // 或 if (dispatcher->OnWindowMessage(message)) return 0;
//         dispatcher->Drain();
//         return 0;
//
// 生命周期：WindowProc 收到 WM_NCDESTROY 时应调用 Attach(nullptr)，
// 让之后所有 Post 只入队不再投递到已销毁的窗口；App 关停顺序为
//   jobService->Stop()  →  dispatcher->Stop()  →  再拆 bridge / webview。
//
// 任务体要求：不许长时间阻塞（会卡住 UI 线程）；抛出的异常会被吞掉并
// 记录到 OutputDebugString，但那是兜底，不是约定。

#include "platform/TaskDispatcher.h"
#include "utils/pb_win.h"   // 唯一允许直接包含 <windows.h> 的入口（HWND / UINT）

#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <mutex>
#include <thread>
#include <vector>

namespace pb {

class UiDispatcher final : public ITaskDispatcher {
public:
    // 全工程唯一的 UI 投递消息（RegisterWindowMessage 分配）。
    // 用 RegisterWindowMessage 而不是 WM_APP+1：WebView2 自己的消息泵也在
    // WM_APP 区间活动，固定编号会撞车（表现为桥接响应收不到）。注册消息是
    // 系统分配的全局唯一 ID。
    static UINT DispatchMessageId();

    // hwnd 允许为空（服务先于窗口创建时用 Attach() 补上）。
    explicit UiDispatcher(HWND hwnd = nullptr);
    ~UiDispatcher() override;

    UiDispatcher(const UiDispatcher&) = delete;
    UiDispatcher& operator=(const UiDispatcher&) = delete;

    // 任意线程。已 Stop() 时静默丢弃并计入 DroppedCount()。
    void Post(std::function<void()> task) override;

    // 在调用线程（必须是 UI 线程）上执行队列里的全部任务，
    // 返回本次执行的任务数。可被嵌套的消息泵重入，但只会有一层真正执行。
    std::size_t Drain();

    // WndProc 的便捷写法：是投递消息就 Drain() 并返回 true。
    bool OnWindowMessage(UINT message);

    // 拒绝新任务、丢弃尚未执行的任务、等待另一线程上正在执行的 Drain 退出。
    // 幂等。在任务体内调用时不会自我等待。
    void Stop();

    // 绑定/解绑目标窗口。绑定后若有积压任务会立刻补一条唤醒消息，
    // 因此「先 Submit 后建窗口」的启动顺序也不会丢事件。
    void Attach(HWND hwnd);

    bool IsStopped() const { return m_stopped.load(std::memory_order_acquire); }
    HWND Hwnd() const;
    std::size_t PendingCount() const;
    std::uint64_t DroppedCount() const { return m_dropped.load(std::memory_order_relaxed); }

private:
    // 在持锁状态下判断是否需要补一条唤醒消息；需要时返回 true 并置位
    // m_kickPending，调用方在解锁后 PostMessage。
    bool ReserveKickLocked(HWND& outHwnd);

    mutable std::mutex m_mutex;
    std::condition_variable m_idleCv;              // Stop() 等待「Drain 结束」
    std::vector<std::function<void()>> m_tasks;    // 待执行任务（FIFO）
    HWND m_hwnd = nullptr;
    std::atomic<bool> m_stopped{false};
    std::atomic<std::uint64_t> m_dropped{0};
    bool m_draining = false;                       // 是否有 Drain 正在执行
    std::thread::id m_drainThread{};               // 执行 Drain 的线程
    bool m_kickPending = false;                    // 是否已有一条消息在路上
};

} // namespace pb
