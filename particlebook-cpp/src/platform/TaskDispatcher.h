#pragma once
// ── PB-020：把「在 UI 线程上跑」收敛成唯一接口 ─────────────────────
//
// 背景（docs/ARCHITECTURE_REVIEW.md P0-1 / P0-3）：
//   * 长任务 handler 目前直接在 WebView2 的 UI 线程里同步跑；
//   * 后台线程（Z-Library 下载、书源搜索、mutool 渲染……）直接调
//     bridge->EmitEvent / WebView2，属于跨线程碰 COM。
//
// 统一的缝：服务只拿到 ITaskDispatcher，不拿 HWND、不拿 WebView2。
// 生产实现是 pb::UiDispatcher（HWND + 单条 WM_APP 消息），
// 单测里用 FakeDispatcher（直接泵队列），服务逻辑因此可以脱离 Win32 测试。
//
// 实现侧契约：
//   * Post() 可从任意线程调用，包括 UI 线程自己；
//   * 任务在 UI 线程上按 FIFO、串行执行，不会并发；
//   * 任务体里逃出来的异常必须被实现吞掉并记录 —— 让它穿过 Win32
//     消息循环会直接终止进程；
//   * Post() 不等待任务体执行（fire-and-forget），也不许长时间阻塞；
//   * 已关闭（Stop）的 dispatcher 可以静默丢弃任务，不许抛。

#include <functional>

namespace pb {

class ITaskDispatcher {
public:
    virtual ~ITaskDispatcher() = default;

    // 投递一个任务到 UI 线程。关闭后投递允许被静默丢弃。
    virtual void Post(std::function<void()> task) = 0;
};

} // namespace pb
