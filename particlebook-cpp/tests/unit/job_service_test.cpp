// ── PB-020 JobService 单元测试 ───────────────────────────────────────
// 不依赖 Win32 / 消息循环：FakeDispatcher 扮演 UI 线程（Pump() 在测试线程上
// 按 FIFO 执行任务，和 UiDispatcher::Drain 的语义一致）。
//
// 覆盖：submit / progress 顺序与线程 / 节流 / 异常转失败 / 协作式取消 /
//       取消排队中的 job / Stop 收尾 / Stop 后拒绝提交 / 并发 / MakeWork 适配。

#include "platform/JobService.h"
#include "utils/test_assert.h"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <functional>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace {

using namespace std::chrono_literals;

// 模拟 UI 线程：Post 入队，Pump() 在调用线程（= 测试主线程）上依序执行。
// 与 UiDispatcher 一致：任务体抛出的异常被吞掉并记录，不会传播。
class FakeDispatcher final : public pb::ITaskDispatcher {
public:
    void Post(std::function<void()> task) override
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_tasks.push_back(std::move(task));
        ++m_posted;
    }

    std::size_t Pump()
    {
        std::vector<std::function<void()>> batch;
        {
            std::lock_guard<std::mutex> lock(m_mutex);
            batch.swap(m_tasks);
        }
        std::size_t ran = 0;
        for (std::function<void()>& task : batch) {
            ++ran;
            try {
                task();
            } catch (const std::exception& e) {
                RememberError(e.what());
            } catch (...) {
                RememberError("unknown exception");
            }
        }
        return ran;
    }

    std::size_t Posted()
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        return m_posted;
    }

    std::string Error()
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        return m_error;
    }

private:
    void RememberError(const char* what)
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        if (m_error.empty()) m_error = what ? what : "";
    }

    std::mutex m_mutex;
    std::vector<std::function<void()>> m_tasks;
    std::size_t m_posted = 0;
    std::string m_error;
};

pb::JobService::Options MakeOptions(unsigned workers, unsigned progressIntervalMs)
{
    pb::JobService::Options options;
    options.workerCount = workers;
    options.progressIntervalMs = progressIntervalMs;
    return options;
}

// 等待工作线程侧的标志（不泵 UI 队列）。
bool SpinUntil(const std::function<bool()>& done, int timeoutMs = 5000)
{
    const std::chrono::steady_clock::time_point deadline =
        std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
    while (std::chrono::steady_clock::now() < deadline) {
        if (done()) return true;
        std::this_thread::sleep_for(1ms);
    }
    return done();
}

// 边泵 UI 队列边等条件成立 —— 终止回调只在 Pump 里执行。
bool PumpUntil(FakeDispatcher& ui, const std::function<bool()>& done, int timeoutMs = 8000)
{
    const std::chrono::steady_clock::time_point deadline =
        std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
    for (;;) {
        if (done()) return true;
        ui.Pump();
        if (std::chrono::steady_clock::now() >= deadline) return done();
        std::this_thread::sleep_for(1ms);
    }
}

// 一次性闸门：让某个 job 卡在 work 里，用来验证「排队中被取消」。
class Gate {
public:
    void Open()
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_open = true;
        m_cv.notify_all();
    }

    bool Wait(int timeoutMs = 5000)
    {
        std::unique_lock<std::mutex> lock(m_mutex);
        return m_cv.wait_for(lock, std::chrono::milliseconds(timeoutMs),
                             [this] { return m_open; });
    }

private:
    std::mutex m_mutex;
    std::condition_variable m_cv;
    bool m_open = false;
};

// ── 1. submit → work 执行一次 → done(ok) 在 UI 线程上恰好一次 ────────
void TestSubmitRunsWorkAndDeliversDoneOnce()
{
    auto ui = std::make_shared<FakeDispatcher>();
    pb::JobService jobs(ui, MakeOptions(2, 0));

    std::atomic<int> workRuns{0};
    std::atomic<pb::JobId> seenId{pb::kInvalidJobId};
    int doneCount = 0;
    pb::JobResult done;
    std::thread::id doneThread;
    const std::thread::id uiThread = std::this_thread::get_id();

    const pb::JobId id = jobs.Submit(
        "hello",
        [&](pb::JobContext& job) {
            ++workRuns;
            seenId.store(job.Id());
        },
        pb::JobProgressHandler(),
        [&](const pb::JobResult& result) {
            ++doneCount;
            done = result;
            doneThread = std::this_thread::get_id();
        });

    CHECK_TRUE(id != pb::kInvalidJobId);
    CHECK_TRUE(PumpUntil(*ui, [&] { return doneCount > 0; }));
    CHECK_EQ(doneCount, 1);
    CHECK_EQ(workRuns.load(), 1);
    CHECK_EQ(seenId.load(), id);
    CHECK_EQ(done.id, id);
    CHECK_TRUE(done.ok);
    CHECK_TRUE(!done.cancelled);
    CHECK_TRUE(done.error.empty());
    CHECK_TRUE(doneThread == uiThread);

    // 收尾：done 后 job 从活跃表里消失。
    CHECK_TRUE(PumpUntil(*ui, [&] { return !jobs.IsRunning(id); }));
    CHECK_EQ(jobs.ActiveCount(), 0u);
}

// ── 2. progress 在 UI 线程上按 FIFO、按序到达，且 done 在 progress 之后 ──
void TestProgressDeliveredInOrderOnUiThread()
{
    auto ui = std::make_shared<FakeDispatcher>();
    pb::JobService jobs(ui, MakeOptions(1, 0));

    struct Event { bool isDone; double percent; std::string message; };
    std::vector<Event> events;
    std::thread::id callbackThread;
    const std::thread::id uiThread = std::this_thread::get_id();

    jobs.Submit(
        "progress",
        [](pb::JobContext& job) {
            job.Progress(10.0, "a");
            job.Progress(50.0, "b");
            job.Progress(100.0, "c");
        },
        [&](const pb::JobProgress& p) {
            callbackThread = std::this_thread::get_id();
            events.push_back(Event{false, p.percent, p.message});
        },
        [&](const pb::JobResult&) {
            callbackThread = std::this_thread::get_id();
            events.push_back(Event{true, 0.0, std::string()});
        });

    CHECK_TRUE(PumpUntil(*ui, [&] {
        return !events.empty() && events.back().isDone;
    }));
    CHECK_EQ(events.size(), static_cast<std::size_t>(4));
    if (events.size() == 4) {
        CHECK_TRUE(!events[0].isDone);
        CHECK_EQ(events[0].percent, 10.0);
        CHECK_EQ(events[0].message, std::string("a"));
        CHECK_EQ(events[1].percent, 50.0);
        CHECK_EQ(events[1].message, std::string("b"));
        CHECK_EQ(events[2].percent, 100.0);
        CHECK_EQ(events[2].message, std::string("c"));
        CHECK_TRUE(events[3].isDone);
    }
    CHECK_TRUE(callbackThread == uiThread);
}

// ── 3. 节流：窗口内只放行第一次，100% 永远放行 ──────────────────────
void TestProgressThrottledKeepsFirstAndFull()
{
    auto ui = std::make_shared<FakeDispatcher>();
    pb::JobService jobs(ui, MakeOptions(1, 5000));

    std::vector<double> percents;
    int doneCount = 0;

    jobs.Submit(
        "throttled",
        [](pb::JobContext& job) {
            job.Progress(0.0, "start");
            job.Progress(10.0, "ten");
            job.Progress(20.0, "twenty");
            job.Progress(100.0, "full");
        },
        [&](const pb::JobProgress& p) { percents.push_back(p.percent); },
        [&](const pb::JobResult&) { ++doneCount; });

    CHECK_TRUE(PumpUntil(*ui, [&] { return doneCount > 0; }));
    CHECK_EQ(percents.size(), static_cast<std::size_t>(2));
    if (percents.size() == 2) {
        CHECK_EQ(percents[0], 0.0);
        CHECK_EQ(percents[1], 100.0);
    }
}

// ── 4. work 抛异常 → onDone(false, error)，且 job 不算跑挂队列 ───────
void TestExceptionBecomesFailure()
{
    auto ui = std::make_shared<FakeDispatcher>();
    pb::JobService jobs(ui, MakeOptions(1, 0));

    pb::JobResult stdFailure;
    pb::JobResult unknownFailure;
    int doneCount = 0;

    jobs.Submit("std-throw",
                [](pb::JobContext&) { throw std::runtime_error("boom-42"); },
                pb::JobProgressHandler(),
                [&](const pb::JobResult& r) { stdFailure = r; ++doneCount; });

    jobs.Submit("unknown-throw",
                [](pb::JobContext&) { throw 7; },
                pb::JobProgressHandler(),
                [&](const pb::JobResult& r) { unknownFailure = r; ++doneCount; });

    CHECK_TRUE(PumpUntil(*ui, [&] { return doneCount == 2; }));
    CHECK_TRUE(!stdFailure.ok);
    CHECK_TRUE(!stdFailure.cancelled);
    CHECK_TRUE(stdFailure.error.find("boom-42") != std::string::npos);
    CHECK_TRUE(!unknownFailure.ok);
    CHECK_TRUE(!unknownFailure.error.empty());
    CHECK_EQ(jobs.ActiveCount(), 0u);

    // 抛异常后 worker 仍然可用。
    std::atomic<int> rerun{0};
    jobs.Submit("after", [&](pb::JobContext&) { ++rerun; });
    CHECK_TRUE(SpinUntil([&] { return rerun.load() == 1; }));
}

// ── 5. 协作式取消：worker 轮询到标志后退出，done 标 cancelled ─────────
void TestCancelIsCooperative()
{
    auto ui = std::make_shared<FakeDispatcher>();
    pb::JobService jobs(ui, MakeOptions(2, 0));

    std::atomic<bool> started{false};
    std::atomic<bool> sawCancel{false};
    std::atomic<int> polls{0};
    int doneCount = 0;
    pb::JobResult done;

    const pb::JobId id = jobs.Submit(
        "cancel-me",
        [&](pb::JobContext& job) {
            started.store(true);
            while (!job.IsCancelled()) {
                ++polls;
                std::this_thread::sleep_for(1ms);
            }
            sawCancel.store(job.Cancelled().load());
        },
        pb::JobProgressHandler(),
        [&](const pb::JobResult& r) { done = r; ++doneCount; });

    CHECK_TRUE(SpinUntil([&] { return started.load(); }));
    CHECK_TRUE(jobs.Cancel(id));
    CHECK_TRUE(!jobs.Cancel(id));            // 第二次请求不再「新生效」
    CHECK_TRUE(!jobs.Cancel(pb::kInvalidJobId));

    CHECK_TRUE(PumpUntil(*ui, [&] { return doneCount > 0; }));
    CHECK_TRUE(sawCancel.load());
    CHECK_TRUE(!done.ok);
    CHECK_TRUE(done.cancelled);
    CHECK_EQ(done.error, std::string("cancelled"));
    CHECK_TRUE(!jobs.IsRunning(id));
}

// ── 6. 排队中被取消：work 根本不会执行 ──────────────────────────────
void TestCancelQueuedJobNeverRuns()
{
    auto ui = std::make_shared<FakeDispatcher>();
    pb::JobService jobs(ui, MakeOptions(1, 0));   // 单 worker：B 一定排在 A 后面

    Gate gate;
    std::atomic<bool> aStarted{false};
    std::atomic<bool> bStarted{false};
    int doneA = 0;
    int doneB = 0;
    pb::JobResult resultA;
    pb::JobResult resultB;

    jobs.Submit("A",
                [&](pb::JobContext&) {
                    aStarted.store(true);
                    gate.Wait();
                },
                pb::JobProgressHandler(),
                [&](const pb::JobResult& r) { resultA = r; ++doneA; });

    CHECK_TRUE(SpinUntil([&] { return aStarted.load(); }));

    const pb::JobId idB = jobs.Submit("B",
                                     [&](pb::JobContext&) { bStarted.store(true); },
                                     pb::JobProgressHandler(),
                                     [&](const pb::JobResult& r) { resultB = r; ++doneB; });
    CHECK_TRUE(jobs.Cancel(idB));
    gate.Open();

    CHECK_TRUE(PumpUntil(*ui, [&] { return doneA > 0 && doneB > 0; }));
    CHECK_TRUE(!bStarted.load());          // 从未执行
    CHECK_TRUE(resultA.ok);
    CHECK_TRUE(!resultB.ok);
    CHECK_TRUE(resultB.cancelled);
}

// ── 7. Stop()：取消在跑的、给排队的发 stopped、join 所有 worker ──────
void TestStopCancelsRunningAndFlushesQueued()
{
    auto ui = std::make_shared<FakeDispatcher>();
    auto jobs = std::make_unique<pb::JobService>(ui, MakeOptions(1, 0));

    std::atomic<bool> runningStarted{false};
    std::atomic<bool> queuedRan{false};
    int runningDone = 0;
    int queuedDone = 0;
    pb::JobResult runningResult;
    pb::JobResult queuedResult;

    jobs->Submit("running",
                 [&](pb::JobContext& job) {
                     runningStarted.store(true);
                     while (!job.IsCancelled()) std::this_thread::sleep_for(1ms);
                 },
                 pb::JobProgressHandler(),
                 [&](const pb::JobResult& r) { runningResult = r; ++runningDone; });

    CHECK_TRUE(SpinUntil([&] { return runningStarted.load(); }));

    jobs->Submit("queued",
                 [&](pb::JobContext&) { queuedRan.store(true); },
                 pb::JobProgressHandler(),
                 [&](const pb::JobResult& r) { queuedResult = r; ++queuedDone; });

    CHECK_EQ(jobs->ActiveCount(), static_cast<std::size_t>(1));
    CHECK_EQ(jobs->PendingCount(), static_cast<std::size_t>(1));

    jobs->Stop();     // 会取消 running、给 queued 发事件、join worker

    CHECK_EQ(jobs->ActiveCount(), 0u);
    CHECK_EQ(jobs->PendingCount(), 0u);
    CHECK_TRUE(!queuedRan.load());

    ui->Pump();       // 终止事件是在 Stop 前后投递的，泵一次拿全
    CHECK_EQ(queuedDone, 1);
    CHECK_EQ(runningDone, 1);
    CHECK_TRUE(!queuedResult.ok);
    CHECK_TRUE(queuedResult.cancelled);
    CHECK_EQ(queuedResult.error, std::string("stopped"));
    CHECK_TRUE(!runningResult.ok);
    CHECK_TRUE(runningResult.cancelled);
    CHECK_TRUE(!queuedResult.ok && queuedResult.id != pb::kInvalidJobId);
    CHECK_TRUE(runningResult.id != queuedResult.id);
}

// ── 8. Stop 之后：Submit 被拒、Stop 幂等、worker 全部退出 ────────────
void TestStopRejectsSubmitAndIsIdempotent()
{
    auto ui = std::make_shared<FakeDispatcher>();
    pb::JobService jobs(ui, MakeOptions(2, 0));

    std::atomic<int> runs{0};
    jobs.Submit("one", [&](pb::JobContext&) { ++runs; });
    CHECK_TRUE(SpinUntil([&] { return runs.load() == 1; }));

    jobs.Stop();
    CHECK_TRUE(jobs.WorkerCount() == 0);

    const pb::JobId rejected = jobs.Submit("after-stop", [&](pb::JobContext&) { ++runs; });
    CHECK_EQ(rejected, pb::kInvalidJobId);
    CHECK_TRUE(!jobs.Cancel(12345));
    jobs.Stop();      // 幂等
    CHECK_TRUE(!jobs.IsRunning(rejected));

    ui->Pump();
    CHECK_EQ(runs.load(), 1);
}

// ── 9. 并发：多 worker 同时跑，全部完成 ──────────────────────────────
void TestConcurrentJobsAllComplete()
{
    auto ui = std::make_shared<FakeDispatcher>();
    pb::JobService jobs(ui, MakeOptions(4, 0));

    constexpr int kJobs = 24;
    std::atomic<int> concurrent{0};
    std::atomic<int> maxConcurrent{0};
    std::vector<pb::JobId> doneIds;
    int doneCount = 0;
    bool allOk = true;

    for (int i = 0; i < kJobs; ++i) {
        jobs.Submit("job-" + std::to_string(i),
                    [&](pb::JobContext&) {
                        const int now = ++concurrent;
                        int previous = maxConcurrent.load();
                        while (now > previous &&
                               !maxConcurrent.compare_exchange_weak(previous, now)) {
                        }
                        std::this_thread::sleep_for(2ms);
                        --concurrent;
                    },
                    pb::JobProgressHandler(),
                    [&](const pb::JobResult& r) {
                        ++doneCount;
                        doneIds.push_back(r.id);
                        if (!r.ok) allOk = false;
                    });
    }

    CHECK_TRUE(PumpUntil(*ui, [&] { return doneCount == kJobs; }));
    CHECK_EQ(doneCount, kJobs);
    CHECK_TRUE(allOk);
    CHECK_TRUE(maxConcurrent.load() >= 2);       // 确实并行了
    CHECK_EQ(jobs.PendingCount(), 0u);
    CHECK_EQ(jobs.ActiveCount(), 0u);
}

// ── 10. MakeWork：(JobId, atomic_bool&) 老式形状共用同一个取消标志 ────
void TestMakeWorkSharesCancelFlag()
{
    auto ui = std::make_shared<FakeDispatcher>();
    pb::JobService jobs(ui, MakeOptions(1, 0));

    std::atomic<bool> started{false};
    std::atomic<bool> sawCancel{false};
    std::atomic<pb::JobId> seenId{pb::kInvalidJobId};
    int doneCount = 0;
    pb::JobResult done;

    const pb::JobId id = jobs.Submit(
        "legacy-shape",
        pb::MakeWork([&](pb::JobId jobId, std::atomic_bool& cancelled) {
            seenId.store(jobId);
            started.store(true);
            while (!cancelled.load()) std::this_thread::sleep_for(1ms);
            sawCancel.store(true);
        }),
        pb::JobProgressHandler(),
        [&](const pb::JobResult& r) { done = r; ++doneCount; });

    CHECK_TRUE(SpinUntil([&] { return started.load(); }));
    CHECK_TRUE(jobs.Cancel(id));
    CHECK_TRUE(PumpUntil(*ui, [&] { return doneCount > 0; }));
    CHECK_EQ(seenId.load(), id);
    CHECK_TRUE(sawCancel.load());
    CHECK_TRUE(done.cancelled);
}

// ── 11. 空 work：不抛，直接返回无效 id ──────────────────────────────
void TestEmptyWorkReturnsInvalidId()
{
    auto ui = std::make_shared<FakeDispatcher>();
    pb::JobService jobs(ui, MakeOptions(1, 0));

    CHECK_EQ(jobs.Submit("nothing", pb::JobWork()), pb::kInvalidJobId);
    CHECK_EQ(jobs.PendingCount(), 0u);
    CHECK_EQ(ui->Posted(), 0u);
}

} // namespace

int main()
{
    TestSubmitRunsWorkAndDeliversDoneOnce();
    TestProgressDeliveredInOrderOnUiThread();
    TestProgressThrottledKeepsFirstAndFull();
    TestExceptionBecomesFailure();
    TestCancelIsCooperative();
    TestCancelQueuedJobNeverRuns();
    TestStopCancelsRunningAndFlushesQueued();
    TestStopRejectsSubmitAndIsIdempotent();
    TestConcurrentJobsAllComplete();
    TestMakeWorkSharesCancelFlag();
    TestEmptyWorkReturnsInvalidId();

    return pb_t::Summary("job_service_test");
}
