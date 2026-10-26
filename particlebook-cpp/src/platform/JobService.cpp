#include "JobService.h"

#include <chrono>
#include <exception>
#include <utility>

namespace pb {
namespace {

using SteadyClock = std::chrono::steady_clock;

// 进度节流：一个 job 一个实例。Options::progressIntervalMs == 0 时全放行
// （单测靠它拿确定性时序）；否则同一时间窗内只放行一次，但 100% 永远放行，
// 这样 UI 不会停在 99%。
class ProgressGate {
public:
    explicit ProgressGate(unsigned intervalMs)
        : m_intervalMs(intervalMs)
    {
    }

    bool ShouldPass(double percent)
    {
        if (m_intervalMs == 0) return true;

        std::lock_guard<std::mutex> lock(m_mutex);
        const SteadyClock::time_point now = SteadyClock::now();
        if (percent >= 100.0 && percent > m_lastPercent) {
            m_lastPercent = percent;
            m_lastSent = now;
            m_sent = true;
            return true;
        }
        if (m_sent && (now - m_lastSent) < std::chrono::milliseconds(m_intervalMs)) {
            return false;
        }
        m_lastPercent = percent;
        m_lastSent = now;
        m_sent = true;
        return true;
    }

private:
    const unsigned m_intervalMs;
    std::mutex m_mutex;
    SteadyClock::time_point m_lastSent{};
    double m_lastPercent = -1.0;
    bool m_sent = false;
};

} // namespace

// ── JobContext ──────────────────────────────────────────────────────

void JobContext::Progress(double percent, std::string message)
{
    if (m_progress) {
        m_progress(percent, std::move(message));
    }
}

// ── JobService ──────────────────────────────────────────────────────

unsigned JobService::DefaultWorkerCount()
{
    unsigned cores = std::thread::hardware_concurrency();
    if (cores == 0) cores = 4;      // hardware_concurrency 允许返回 0
    if (cores < 2) cores = 2;
    if (cores > 8) cores = 8;       // 再多也只是抢同一个磁盘 / 网络 / GPU
    return cores;
}

JobService::JobService(std::shared_ptr<ITaskDispatcher> dispatcher, Options options)
    : m_dispatcher(std::move(dispatcher))
    , m_options(options)
{
    unsigned count = m_options.workerCount ? m_options.workerCount : DefaultWorkerCount();
    if (count == 0) count = 1;

    m_workers.reserve(count);
    try {
        for (unsigned i = 0; i < count; ++i) {
            m_workers.emplace_back(&JobService::WorkerLoop, this);
        }
    } catch (...) {
        // 线程创建失败时已经起来的 worker 必须正常退出，否则 std::thread
        // 析构会 std::terminate。
        Stop();
        throw;
    }
}

JobService::~JobService()
{
    Stop();
}

JobId JobService::Submit(std::string name, JobWork work,
                         JobProgressHandler onProgress, JobDoneHandler onDone)
{
    if (!work) return kInvalidJobId;

    std::shared_ptr<JobRecord> job = std::make_shared<JobRecord>();
    job->id = m_nextId.fetch_add(1, std::memory_order_relaxed);
    job->name = std::move(name);
    job->cancelled = std::make_shared<std::atomic_bool>(false);
    job->work = std::move(work);
    job->onProgress = std::move(onProgress);
    job->onDone = std::move(onDone);

    {
        std::lock_guard<std::mutex> lock(m_mutex);
        if (m_stopping) return kInvalidJobId;
        m_jobs.emplace(job->id, job);
        m_queue.push_back(job);
    }
    m_workCv.notify_one();
    return job->id;
}

bool JobService::Cancel(JobId id)
{
    if (id == kInvalidJobId) return false;

    std::lock_guard<std::mutex> lock(m_mutex);
    const auto it = m_jobs.find(id);
    if (it == m_jobs.end()) return false;
    return !it->second->cancelled->exchange(true, std::memory_order_acq_rel);
}

bool JobService::IsRunning(JobId id) const
{
    if (id == kInvalidJobId) return false;
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_jobs.find(id) != m_jobs.end();
}

std::size_t JobService::PendingCount() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_queue.size();
}

std::size_t JobService::ActiveCount() const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_active;
}

void JobService::Stop()
{
    std::lock_guard<std::mutex> stopLock(m_stopMutex);

    std::vector<std::shared_ptr<JobRecord>> queued;
    std::vector<std::shared_ptr<JobRecord>> running;
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_stopping = true;
        queued.assign(m_queue.begin(), m_queue.end());
        m_queue.clear();
        for (const std::shared_ptr<JobRecord>& job : queued) {
            m_jobs.erase(job->id);
        }
        running.reserve(m_jobs.size());
        for (const auto& entry : m_jobs) {
            running.push_back(entry.second);
        }
    }
    m_workCv.notify_all();

    // 1) 正在跑的 job：置取消标志（协作式，worker 自己会退出 work）。
    for (const std::shared_ptr<JobRecord>& job : running) {
        job->cancelled->store(true, std::memory_order_release);
    }
    // 2) 从未开始的 job：直接给出终止事件，UI 不会永远等一个不会来的回调。
    for (const std::shared_ptr<JobRecord>& job : queued) {
        JobResult result;
        result.id = job->id;
        result.name = job->name;
        result.ok = false;
        result.cancelled = true;
        result.error = "stopped";
        DeliverDone(job, std::move(result));
    }
    // 3) join（Stop 若被 worker 自己调用，不 join 自己）。
    for (std::thread& worker : m_workers) {
        if (!worker.joinable()) continue;
        if (worker.get_id() == std::this_thread::get_id()) {
            worker.detach();
        } else {
            worker.join();
        }
    }
    m_workers.clear();

    std::lock_guard<std::mutex> lock(m_mutex);
    m_jobs.clear();
    m_queue.clear();
}

void JobService::WorkerLoop()
{
    for (;;) {
        std::shared_ptr<JobRecord> job;
        {
            std::unique_lock<std::mutex> lock(m_mutex);
            m_workCv.wait(lock, [this] { return m_stopping || !m_queue.empty(); });
            if (m_stopping) return;     // Stop() 已经把队列收走了
            job = m_queue.front();
            m_queue.pop_front();
            ++m_active;
        }

        RunJob(job);

        {
            std::lock_guard<std::mutex> lock(m_mutex);
            --m_active;
            m_jobs.erase(job->id);
        }
    }
}

void JobService::RunJob(const std::shared_ptr<JobRecord>& job)
{
    const SteadyClock::time_point began = SteadyClock::now();

    JobResult result;
    result.id = job->id;
    result.name = job->name;

    if (job->cancelled->load(std::memory_order_acquire)) {
        // 排队期间就被取消了：不执行 work，直接收尾。
        result.ok = false;
        result.cancelled = true;
        result.error = "cancelled";
    } else {
        const std::shared_ptr<ITaskDispatcher> dispatcher = m_dispatcher;
        const std::shared_ptr<JobRecord> record = job;
        const std::shared_ptr<ProgressGate> gate =
            std::make_shared<ProgressGate>(m_options.progressIntervalMs);

        JobContext context(
            job->id, job->name, job->cancelled,
            [dispatcher, record, gate](double percent, std::string message) {
                if (!dispatcher || !record->onProgress) return;
                if (record->cancelled->load(std::memory_order_relaxed)) return;  // 取消后不再刷进度
                if (percent < 0.0) {
                    percent = 0.0;
                } else if (percent > 100.0) {
                    percent = 100.0;
                }
                if (!gate->ShouldPass(percent)) return;

                JobProgress progress;
                progress.id = record->id;
                progress.name = record->name;
                progress.percent = percent;
                progress.message = std::move(message);

                const JobProgressHandler handler = record->onProgress;
                dispatcher->Post([handler, progress] { handler(progress); });
            });

        try {
            job->work(context);
            result.ok = true;
        } catch (const std::exception& e) {
            result.ok = false;
            result.error = e.what() ? e.what() : "exception";
        } catch (...) {
            result.ok = false;
            result.error = "unknown exception";
        }
    }

    // 取消优先：被取消的 job 一律 ok=false / cancelled=true —— 即使 work
    // 正常返回，中途退出多半也只做了一半，不能当成成功。
    if (job->cancelled->load(std::memory_order_acquire)) {
        result.ok = false;
        result.cancelled = true;
        if (result.error.empty()) result.error = "cancelled";
    }

    result.elapsedMs = static_cast<std::uint64_t>(
        std::chrono::duration_cast<std::chrono::milliseconds>(SteadyClock::now() - began).count());

    DeliverDone(job, std::move(result));
}

void JobService::DeliverDone(const std::shared_ptr<JobRecord>& job, JobResult result)
{
    const std::shared_ptr<ITaskDispatcher> dispatcher = m_dispatcher;
    if (!dispatcher || !job->onDone) return;

    const JobDoneHandler handler = job->onDone;
    dispatcher->Post([handler, result] { handler(result); });
}

} // namespace pb
