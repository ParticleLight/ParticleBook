#pragma once
// ── PB-016（A）：本地诊断日志核心 ──────────────────────────────────
//
// 目标（docs/BACKLOG.md PB-016）：出问题时能定位，不上传任何数据。
//
//   * 滚动文件日志：单文件大小上限 + 历史文件数量上限；级别可调；
//     多线程安全（一次 Write 落一行，行内不撕裂）；启动时自动记录
//     版本 / 操作系统 / CPU / 内存 / 时区 / 语言，以及脱敏设置。
//   * 自动脱敏：Authorization / Cookie 等请求头、apiKey / token /
//     password 等键值、URL 内嵌凭据、常见令牌形状（sk-… / ghp_… /
//     AIza… / AKIA… / 超长随机串）、用户目录前缀（%USERPROFILE% /
//     %LOCALAPPDATA% / %APPDATA% / %TEMP%）以及任意自定义规则。
//     脱敏在写盘之前完成，磁盘上不存在明文密钥。
//   * 导出诊断包：把已有日志 + 版本 + 系统信息 + 脱敏设置复制到用户
//     指定的目录（默认 %LOCALAPPDATA%\ParticleBook\diagnostics\…）。
//     纯本地文件复制，不联网、不上报、无遥测。
//
// 设计约束：
//   * 本头文件不包含 <windows.h>，只用 std —— 单测可以直接链接
//     LogService.cpp（与 JobService / SecretStore 一致）。
//   * 版本号由调用方传入（Options::appVersion，App 里传 PB_VERSION_STRING），
//     本文件不依赖 CMake 生成的 pb_version.h，测试目标不需要额外 include
//     构建目录下的 src/。
//   * 进程内一个目录只应有一个实例（多实例写同一文件没有跨实例锁，
//     行为未定义）。长时间运行的 App 用一个实例即可。
//   * 不记录任何明文密钥是本模块的硬约定：所有对外文本（磁盘日志、
//     诊断包、系统信息）都先过 Redact()。
//
// 典型用法（App 启动；接线上层是后续任务，不在本文件范围内）：
//   pb::LogOptions o;
//   o.appVersion = PB_VERSION_STRING;
//   o.level = pb::LogLevel::Info;
//   auto log = std::make_shared<pb::LogService>(o);
//   std::string err;
//   if (!log->Start(&err)) { /* 退回到 OutputDebugString */ }
//   pb::SetGlobalLogger(log);                 // 供各 service/handler 直接写
//   pb::LogInfo("bridge", "bridge listening");
//   ...
//   log->ExportDiagnostics("", &pkg);         // 用户点「导出诊断包」
//   log->Stop();                              // App::Shutdown 里调用
//
// 线程模型：
//   Start / Stop / Write / Flush       任意线程（互相串行）
//   Redact / SystemInfoText / 访问器   任意线程（只读快照）
//   ExportDiagnostics                  任意线程（内部会 Flush 并持锁复制）
//   日志文件真正的写入在 m_mutex 内完成，因此并发 Write 的行不会交错。

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

namespace pb {

// ── 级别 ─────────────────────────────────────────────────────────────
// Off 只用于 SetLevel：表示完全不写。IsEnabled(Off) 恒为 false。
enum class LogLevel : int {
    Trace    = 0,
    Debug    = 1,
    Info     = 2,
    Warn     = 3,
    Error    = 4,
    Critical = 5,
    Off      = 6,
};

// "TRACE" / "DEBUG" / … / "OFF"（大写）。
const char* LogLevelName(LogLevel level) noexcept;

// 大小写不敏感的级别解析："info" / "WARN" / "warning" / "err" / "critical" /
// "off" / "none"。成功返回 true 并写入 out。
bool ParseLogLevel(const std::string& text, LogLevel& out) noexcept;

// ── 脱敏规则（构造时传入；实例内保持不变，便于无锁读取）─────────────
struct LogRedactionOptions {
    bool enabled = true;
    // 被替换掉的敏感值统一写成这个占位符。
    std::string placeholder = "<redacted>";

    // 额外需要脱敏的键名（大小写不敏感、按整词匹配）。内置已覆盖
    // apiKey / api_key / x-api-key / access_token / refresh_token /
    // client_secret / password / passwd / pwd / secret / token / key /
    // cookie / set-cookie / authorization / proxy-authorization /
    // session / sessionid / userkey / remix_userkey 等。
    std::vector<std::string> extraKeys;

    // 额外的「字面量 → 占位符」路径替换，与内置用户目录规则一起按
    // 「字面量更长者优先」排序。例如：
    //   { { "D:\\Books\\Private", "<library>" } }
    std::vector<std::pair<std::string, std::string>> extraPaths;

    // 请求头类整行脱敏（Authorization / Cookie 的值往往含空格和分号，
    // 必须整段替换而不是只截到第一个分隔符）。
    bool headers = true;

    // scheme://user:password@host 里的 user:password 会被替换。
    bool urlCredentials = true;

    // 常见令牌形状：sk-… / sk-ant-… / ghp_… / github_pat_… / AIza… /
    // AKIA… / xoxb-… / npm_… / eyJ…（JWT），以及 >= 40 字符且同时含
    // 字母与数字的长随机串（base64 / hex 令牌）。
    bool tokenShapes = true;

    // 用户目录前缀：%TEMP% → <temp>，%LOCALAPPDATA% → <localAppData>，
    // %APPDATA% → <appData>，%USERPROFILE% → <user>，当前工作目录 → <cwd>；
    // 另外把任意 C:\Users\<name>\ 里的 <name> 换成 <user>（其他账户的路径）。
    bool userPaths = true;
};

// ── 日志配置 ─────────────────────────────────────────────────────────
struct LogOptions {
    // 空 = DefaultLogDirectory()（%LOCALAPPDATA%\ParticleBook\logs）。
    std::string directory;

    // 文件名主干：<stem>.log 为当前文件，<stem>.1.log … 为历史文件。
    std::string fileStem = "particlebook";

    // 单文件大小上限（字节）。下限 4 KiB；单行超限时允许超过一次
    // （不可能为了限长把一行拆开，否则日志不可读）。
    std::uint64_t maxFileBytes = 2ull * 1024 * 1024;

    // 保留的历史文件数量（当前文件之外）。0 = 只留当前文件。
    // 上限 64，防止配置事故把磁盘写满。
    unsigned maxBackupFiles = 4;

    // 低于该级别的日志直接丢弃（不做脱敏、不落盘）。
    LogLevel level = LogLevel::Info;

    // 同时输出到调试器（OutputDebugStringA），方便在 VS 输出窗口看。
    bool echoToDebugger = true;

    // 每行写完立即 flush（崩溃时最多丢一行）。测试依赖它的确定性；
    // 关掉能减少磁盘次数，代价是崩溃丢尾部。
    bool flushEachLine = true;

    // Start() 成功后自动写一段版本 / 系统信息横幅。
    bool logStartupBanner = true;

    // 版本串（App 传 PB_VERSION_STRING）。空则横幅里写 "(unknown)"。
    std::string appVersion;

    // 可选：构建渠道 / commit，例如 "2.2.1-dev" 或 "3bbe555"。
    std::string appChannel;

    LogRedactionOptions redaction;
};

// 导出结果：实际目录 + 目录内文件名 + 总字节数（便于 UI 显示与再次打开）。
struct DiagnosticsPackage {
    std::string directory;
    std::vector<std::string> files;   // 仅文件名（不含目录），按写入顺序
    std::uint64_t totalBytes = 0;
};

// ── 服务本体 ─────────────────────────────────────────────────────────
class LogService final {
public:
    explicit LogService(LogOptions options = LogOptions());
    ~LogService();

    LogService(const LogService&) = delete;
    LogService& operator=(const LogService&) = delete;

    // 创建目录、必要时滚动旧文件、打开当前文件，然后（默认）写启动横幅。
    // 幂等：已 Start() 时直接返回 true。失败返回 false，error 非空。
    bool Start(std::string* error = nullptr);

    // flush + close。幂等；之后的 Write 会静默丢弃（计入 DroppedCount）。
    void Stop();
    bool IsStarted() const noexcept;

    // 级别过滤：level == Off 或低于当前级别时返回 false。
    bool IsEnabled(LogLevel level) const noexcept;
    void SetLevel(LogLevel level) noexcept;      // 运行期可调
    LogLevel Level() const noexcept;

    // 写一行（任意线程）。message 里的 CR/LF 会被压平成 " | "，保证
    // 「一次 Write = 文件里恰好一行」，并发写入不会撕裂；超过 32 KiB 的
    // 消息会被截断并标注 "[truncated]"。
    void Write(LogLevel level, const std::string& category, const std::string& message);

    void Trace(const std::string& category, const std::string& message);
    void Debug(const std::string& category, const std::string& message);
    void Info(const std::string& category, const std::string& message);
    void Warn(const std::string& category, const std::string& message);
    void Error(const std::string& category, const std::string& message);
    void Critical(const std::string& category, const std::string& message);

    // 版本 + 系统信息 + 日志设置（整段已脱敏）。Start() 默认自动调用。
    void LogStartupBanner();

    // 纯函数：返回脱敏后的副本。不受 IsStarted / 级别影响，测试可直接用。
    std::string Redact(const std::string& text) const;
    const LogRedactionOptions& RedactionOptions() const noexcept { return m_options.redaction; }

    // 多行文本：OS 版本 / 架构 / CPU / 内存 / 时区 / 语言 / 进程信息。
    std::string SystemInfoText() const;

    // 导出诊断包。targetDirectory 为空时自动建
    // DefaultDiagnosticsDirectory()\pb-diag-YYYYMMDD-HHmmss 且不覆盖已有目录
    // （重名自动加 -2、-3…）。成功返回 true 并填 out；失败 false + error。
    bool ExportDiagnostics(const std::string& targetDirectory,
                           DiagnosticsPackage* out,
                           std::string* error = nullptr);

    // 立即 flush 当前文件。
    void Flush();

    const LogOptions& Options() const noexcept { return m_options; }
    std::string Directory() const;          // 配置的日志目录（绝对路径）
    std::string CurrentLogPath() const;     // 当前写入的文件
    // 磁盘上已存在的日志文件，顺序为 [当前, .1, .2, …]。
    std::vector<std::string> LogFiles() const;
    std::uint64_t CurrentFileBytes() const;
    std::uint64_t RotationCount() const noexcept;   // 本次进程内的滚动次数
    std::uint64_t DroppedCount() const noexcept;    // 未 Start / 被级别过滤的写入数

    // %LOCALAPPDATA%\ParticleBook\logs（无 LOCALAPPDATA 时退回
    // %USERPROFILE%\AppData\Local\ParticleBook\logs；都没有则返回 "logs"）。
    static std::string DefaultLogDirectory();
    // %LOCALAPPDATA%\ParticleBook\diagnostics（同上兜底）。
    static std::string DefaultDiagnosticsDirectory();

    // 日志文件格式版本，写进诊断包，方便以后解析旧包。
    static constexpr int kLogFormatVersion = 1;

private:
    bool OpenLocked(std::string* error);      // 需持 m_mutex
    void RotateLocked();                      // 需持 m_mutex
    bool WriteRawLocked(const std::string& line);  // 需持 m_mutex
    std::string FormatLine(LogLevel level,
                           const std::string& category,
                           const std::string& message);
    std::string BackupPathLocked(unsigned index) const;   // 需持 m_mutex
    std::vector<std::string> LogFilesLocked() const;      // 需持 m_mutex
    std::uint64_t FileSize(const std::string& path) const;
    bool CopyLogsTo(const std::string& targetDir,
                    std::vector<std::string>& copied,
                    std::string* error);
    std::string VersionInfoText() const;
    std::string SettingsJson() const;

    LogOptions m_options;
    mutable std::mutex m_mutex;
    std::string m_directory;      // 绝对路径（m_options.directory 解析后）
    std::string m_currentPath;
    std::uint64_t m_bytes = 0;    // 当前文件已写字节数
    std::uint64_t m_rotations = 0;
    bool m_started = false;

    std::atomic<int> m_level{static_cast<int>(LogLevel::Info)};
    std::atomic<std::uint64_t> m_dropped{0};

    // 每个实例独占一个文件句柄；用不透明指针避免头文件依赖 <fstream>。
    struct FileSink;
    std::unique_ptr<FileSink> m_sink;

    // 构造时展开好的脱敏规则（不再改），因此 Redact() 无需加锁。
    struct Redactor;
    std::unique_ptr<Redactor> m_redactor;
};

// ── 进程级便利门面 ───────────────────────────────────────────────────
// App 启动时 Install 一个实例，各 service / handler 不必层层传 shared_ptr。
// 未安装时所有调用都是空操作（安全，适合早期启动阶段）。
void SetGlobalLogger(std::shared_ptr<LogService> logger) noexcept;
std::shared_ptr<LogService> GetGlobalLogger() noexcept;

void LogMessage(LogLevel level, const std::string& category, const std::string& message);
void LogTrace(const std::string& category, const std::string& message);
void LogDebug(const std::string& category, const std::string& message);
void LogInfo(const std::string& category, const std::string& message);
void LogWarn(const std::string& category, const std::string& message);
void LogError(const std::string& category, const std::string& message);
void LogCritical(const std::string& category, const std::string& message);

} // namespace pb
