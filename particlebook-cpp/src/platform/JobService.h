#pragma once
// ── PB-020：后台任务池（进度 / 取消 / 统一回 UI 线程）─────────────
//
// 目标（docs/ARCHITECTURE_REVIEW.md P0-1 / P0-3，docs/BACKLOG.md PB-020）：
//   * 长任务（book:import、file:read、pdf:open/renderPage/extractText、
//     bookSource:search…，以及将来的 TTS / 翻译 / 备份 / 索引）不再占用
//     WebView2 的 UI 线程：handler 立刻返回 jobId，work 在 worker 线程跑；
//   * progress / done 一律经 ITaskDispatcher 投递回 UI 线程，后台线程
//     永远不碰 WebView2 / BridgeServer / HWND；
//   * 取消是协作式的（std::atomic_bool），绝不强杀线程；
//   * Stop() 先给未完成任务置取消标志、再 join 所有 worker，让
//     App::Shutdown 的「先停 job → 再拆 bridge/webview/db」顺序可实现。
//
// 线程模型：
//   Submit()      任意线程
//   work()        某个 worker 线程
//   onProgress()  UI 线程（经 dispatcher 投递）
//   onDone()      UI 线程（经 dispatcher 投递），每个 job 恰好一次
//   Cancel/Stop   任意线程
//
// 典型用法（handler 里立刻返回，事件由 UI 线程消费）：
//   const pb::JobId id = m_jobs->Submit(
//       "book:import",
//       [this, path](pb::JobContext& job) {
//           for (int i = 0; i < steps; ++i) {
//               if (job.IsCancelled()) return;          // 协作式取消
//               DoStep(i);
//               job.Progress(100.0 * (i + 1) / steps, "import");
//           }
//       },
//       [this](const pb::JobProgress& p) { EmitProgress(p); },   // UI 线程
//       [this](const pb::JobResult& r) { EmitDone(r); });        // UI 线程
//   return { { "jobId", id } };    // 前端订阅 job:progress / job:done / job:error
//
// 说明：
//   * 本文件不包含 <windows.h>，JobService.cpp 只用 std —— 单测可以直接链接
//     JobService.cpp，不需要消息循环；
//   * 想让 work 直接拿 (JobId, std::atomic_bool&)（老式回调形状），用
//     pb::MakeWork 包一层；
//   * dispatcher 为空时 progress/done 静默丢弃（只适合单测或很早期的启动阶段）；
//   * 不要在 work 里调用本对象的 Stop()（会 detach 自己，另有断言式行为说明）。

#include "platform/TaskDispatcher.h"

#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

namespace pb {

using JobId = std::uint64_t;
constexpr JobId kInvalidJobId = 0;

// 进度快照（percent 是 0..100 的百分数，不是 0..1；越界会被 clamp）。
struct JobProgress {
    JobId       id = kInvalidJobId;
    std::string name;
    double      percent = 0.0;
    std::string message;
};

// 终止结果。ok == false 时 error 非空；cancelled 表示「被 Cancel/Stop 结束」。
struct JobResult {
    JobId         id = kInvalidJobId;
    std::string   name;
    bool          ok = false;
    bool          cancelled = false;
    std::string   error;        // 机器可读的短原因（前端自己本地化）
    std::uint64_t elapsedMs = 0;
};

class JobService;

// 交给 work 的任务上下文：身份 + 取消标志 + 进度上报。
// 值语义可拷贝（work 可以把副本交给自己的子线程，内部全是 shared_ptr）。
class JobContext {
public:
    JobId Id() const noexcept { return m_id; }
    const std::string& Name() const noexcept { return m_name; }

    // 协作式取消：worker 不会被强杀，长循环必须自己轮询。
    bool IsCancelled() const noexcept
    {
        return m_cancelled && m_cancelled->load(std::memory_order_relaxed);
    }

    // 就是 Submit 规格里的那个 std::atomic_bool cancelled：可以直接 .load()。
    std::atomic_bool& Cancelled() noexcept { return *m_cancelled; }
    const std::atomic_bool& Cancelled() const noexcept { return *m_cancelled; }

    // 线程安全。回调在 UI 线程上执行；服务会对高频调用做节流（见 Options）。
    void Progress(double percent, std::string message = std::string());

private:
    friend class JobService;
    JobContext(JobId id, std::string name,
               std::shared_ptr<std::atomic_bool> cancelled,
               std::function<void(double, std::string)> progress)
        : m_id(id)
        , m_name(std::move(name))
        , m_cancelled(std::move(cancelled))
        , m_progress(std::move(progress))
    {
    }

    JobId       m_id = kInvalidJobId;
    std::string m_name;
    std::shared_ptr<std::atomic_bool> m_cancelled;
    std::function<void(double, std::string)> m_progress;
};

using JobWork = std::function<void(JobContext&)>;
using JobProgressHandler = std::function<void(const JobProgress&)>;
using JobDoneHandler = std::function<void(const JobResult&)>;

// 适配「只关心 (jobId, cancelled)」的老式回调形状：
//   jobs.Submit("x", pb::MakeWork([](pb::JobId id, std::atomic_bool& cancelled) { ... }));
inline JobWork MakeWork(std::function<void(JobId, std::atomic_bool&)> legacy)
{
    if (!legacy) return JobWork();
    return [legacy = std::move(legacy)](JobContext& job) {
        legacy(job.Id(), job.Cancelled());
    };
}

class JobService {
public:
    struct Options {
        // 0 = 按 CPU 核数自动定（见 DefaultWorkerCount）。
        unsigned workerCount = 0;
        // 同一 job 两次 onProgress 之间的最小间隔；0 = 不过滤（单测用它拿
        // 确定性行为）。100% 的那次永远放行。
        unsigned progressIntervalMs = 32;
    };

    // dispatcher 由 JobService 共享持有；为空时 progress/done 全部丢弃。
    explicit JobService(std::shared_ptr<ITaskDispatcher> dispatcher, Options options = Options());
    ~JobService();

    JobService(const JobService&) = delete;
    JobService& operator=(const JobService&) = delete;

    // 任意线程。返回 jobId；work 为空或已 Stop 时返回 kInvalidJobId（不抛）。
    JobId Submit(std::string name, JobWork work,
                 JobProgressHandler onProgress = JobProgressHandler(),
                 JobDoneHandler onDone = JobDoneHandler());

    // 协作式取消：置标志并立刻返回，不等待 work 退出。
    // 返回 true 表示这次请求新生效；未知 id 或已取消过返回 false。
    // 即使 work 已经跑完，它的 onDone 也仍会按原样投递一次。
    bool Cancel(JobId id);

    // 排队中或正在执行（被取消但还没结束也算）。
    bool IsRunning(JobId id) const;

    // 请求取消所有未完成任务、丢弃排队任务（对它们立即给出
    // ok=false / cancelled=true / error="stopped" 的 onDone），再 join
    // 所有 worker。幂等；关停路径必须先调它，再拆 dispatcher / bridge。
    void Stop();

    std::size_t PendingCount() const;   // 排队中（未开始）
    std::size_t ActiveCount() const;    // 正在执行
    std::size_t WorkerCount() const { return m_workers.size(); }

    static unsigned DefaultWorkerCount();

private:
    struct JobRecord {
        JobId       id = kInvalidJobId;
        std::string name;
        std::shared_ptr<std::atomic_bool> cancelled;
        JobWork     work;
        JobProgressHandler onProgress;
        JobDoneHandler     onDone;
    };

    void WorkerLoop();
    void RunJob(const std::shared_ptr<JobRecord>& job);
    void DeliverDone(const std::shared_ptr<JobRecord>& job, JobResult result);

    std::shared_ptr<ITaskDispatcher> m_dispatcher;
    Options m_options;

    mutable std::mutex m_mutex;
    std::condition_variable m_workCv;                       // worker 等任务
    std::deque<std::shared_ptr<JobRecord>> m_queue;         // FIFO 待跑
    std::unordered_map<JobId, std::shared_ptr<JobRecord>> m_jobs;  // 排队 + 执行中
    std::size_t m_active = 0;                               // 正在执行的 job 数
    bool m_stopping = false;

    std::mutex m_stopMutex;                 // 串行化 Stop() 本身
    std::atomic<JobId> m_nextId{1};
    std::vector<std::thread> m_workers;
};

} // namespace pb
