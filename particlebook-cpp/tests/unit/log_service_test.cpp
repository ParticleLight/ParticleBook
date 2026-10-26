// ── PB-016（A）LogService 单元测试 ───────────────────────────────────
// 不依赖 Win32 GUI / 消息循环：LogService.cpp 只用 std + kernel32，测试直接
// 链接它即可（与 job_service_test / secret_store_test 同一种做法）。
//
// 覆盖：
//   1. 写入与级别过滤（含运行期 SetLevel、未 Start 的丢弃）
//   2. 滚动：大小上限、备份数量上限、不产生半行、旧数据被滚掉
//   3. 脱敏：Authorization / Cookie / apiKey / token / URL 凭据 /
//      令牌形状 / 用户目录 / 自定义规则 / 关闭开关 / 正常文本不变
//   4. 启动横幅：版本 + 系统信息 + 脱敏状态，且不含用户目录明文
//   5. 并发写：8 线程 × 200 行，行数正确、每行完整、无交叉撕裂
//   6. 导出诊断包：目录与文件存在、日志副本不含明文密钥、重名不覆盖、
//      未 Start 也能导出系统信息
//
// 所有临时目录都在系统 TEMP 下，跑完删除（不写仓库、不写用户文档）。

#include "platform/LogService.h"
#include "utils/test_assert.h"

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <memory>
#include <string>
#include <thread>
#include <vector>

namespace fs = std::filesystem;

namespace {

int g_dirCounter = 0;

// 系统临时目录下建一个唯一子目录；返回 UTF-8 路径（LogService 的路径都用 UTF-8）。
std::string EnsureTempDir(const char* tag)
{
    const fs::path base = fs::temp_directory_path();
    for (;;) {
        const std::string name = std::string("pb-logtest-") + tag + "-" +
                                 std::to_string(static_cast<long long>(
                                     std::chrono::steady_clock::now().time_since_epoch().count())) +
                                 "-" + std::to_string(++g_dirCounter);
        const std::string candidate = (base / name).u8string();
        std::error_code ec;
        if (!fs::exists(fs::u8path(candidate), ec)) {
            fs::create_directories(fs::u8path(candidate), ec);
            if (!ec) return candidate;
        }
    }
}

void RemoveDir(const std::string& dir)
{
    std::error_code ec;
    fs::remove_all(fs::u8path(dir), ec);
}

fs::path TPath(const std::string& utf8) { return fs::u8path(utf8); }

std::string ReadAll(const std::string& path)
{
    std::ifstream in(TPath(path), std::ios::binary);
    if (!in.is_open()) return std::string();
    std::string text((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    return text;
}

std::vector<std::string> ReadLines(const std::string& path)
{
    const std::string text = ReadAll(path);
    std::vector<std::string> lines;
    std::string current;
    for (char c : text) {
        if (c == '\r') continue;
        if (c == '\n') {
            lines.push_back(current);
            current.clear();
        } else {
            current.push_back(c);
        }
    }
    if (!current.empty()) lines.push_back(current);
    return lines;
}

std::uintmax_t FileBytes(const std::string& path)
{
    std::error_code ec;
    const std::uintmax_t size = fs::file_size(TPath(path), ec);
    return ec ? 0 : size;
}

bool Contains(const std::string& haystack, const std::string& needle)
{
    return haystack.find(needle) != std::string::npos;
}

int CountOf(const std::string& haystack, const std::string& needle)
{
    if (needle.empty()) return 0;
    int count = 0;
    size_t pos = 0;
    while ((pos = haystack.find(needle, pos)) != std::string::npos) {
        ++count;
        pos += needle.size();
    }
    return count;
}

// "2026-10-26T18:01:46.123+08:00 [INFO    ] [t12345] [cat] message"
bool LooksLikeLogLine(const std::string& line)
{
    if (line.size() < 40) return false;
    for (int i = 0; i < 4; ++i) {
        if (line[static_cast<size_t>(i)] < '0' || line[static_cast<size_t>(i)] > '9') return false;
    }
    if (line[4] != '-' || line[7] != '-' || line[10] != 'T' || line[13] != ':') return false;
    if (!Contains(line, " [")) return false;
    if (!Contains(line, "] [t")) return false;
    if (!Contains(line, "] [")) return false;
    return true;
}

std::string GetEnvText(const char* name)
{
    char* raw = nullptr;
    size_t size = 0;
    if (_dupenv_s(&raw, &size, name) != 0 || raw == nullptr) return std::string();
    std::string value(raw);
    std::free(raw);
    return value;
}

pb::LogOptions MakeOptions(const std::string& dir)
{
    pb::LogOptions options;
    options.directory = dir;
    options.fileStem = "particlebook";
    options.maxFileBytes = 1024 * 1024;
    options.maxBackupFiles = 2;
    options.level = pb::LogLevel::Info;
    options.echoToDebugger = false;   // 测试不往调试器灌输出
    options.flushEachLine = true;
    options.logStartupBanner = false; // 计数类断言需要干净文件
    options.appVersion = "2.2.1-test";
    return options;
}

// ─────────────────────────────────────────────────────────────────────
void TestLevelAndWrite()
{
    const std::string dir = EnsureTempDir("basic");
    pb::LogOptions options = MakeOptions(dir);
    options.level = pb::LogLevel::Debug;
    pb::LogService log(options);

    CHECK_TRUE(!log.IsStarted());
    std::string error;
    CHECK_TRUE(log.Start(&error));
    CHECK_EQ(error, std::string());
    CHECK_TRUE(log.IsStarted());
    CHECK_TRUE(fs::exists(TPath(log.CurrentLogPath())));
    CHECK_EQ(log.Level() == pb::LogLevel::Debug ? 1 : 0, 1);

    log.Debug("book", "debug-marker-1");
    log.Info("book", "info-marker-2");
    log.Warn("book", "warn-marker-3");
    log.Error("book", "error-marker-4");
    log.Flush();

    std::vector<std::string> lines = ReadLines(log.CurrentLogPath());
    CHECK_EQ(static_cast<int>(lines.size()), 4);
    for (const std::string& line : lines) {
        CHECK_TRUE(LooksLikeLogLine(line));
    }
    const std::string text = ReadAll(log.CurrentLogPath());
    CHECK_TRUE(Contains(text, "debug-marker-1"));
    CHECK_TRUE(Contains(text, " [DEBUG   ] "));
    CHECK_TRUE(Contains(text, " [INFO    ] "));
    CHECK_TRUE(Contains(text, " [WARN    ] "));
    CHECK_TRUE(Contains(text, " [ERROR   ] "));
    CHECK_TRUE(log.CurrentFileBytes() > 0);

    // 运行期降级：低于级别的写入被丢弃且计数。
    log.SetLevel(pb::LogLevel::Warn);
    CHECK_TRUE(!log.IsEnabled(pb::LogLevel::Info));
    CHECK_TRUE(log.IsEnabled(pb::LogLevel::Error));
    const std::uint64_t droppedBefore = log.DroppedCount();
    log.Info("book", "info-dropped-marker");
    log.Warn("book", "warn-kept-marker");
    log.Flush();
    CHECK_TRUE(log.DroppedCount() > droppedBefore);
    lines = ReadLines(log.CurrentLogPath());
    CHECK_EQ(static_cast<int>(lines.size()), 5);
    CHECK_TRUE(!Contains(ReadAll(log.CurrentLogPath()), "info-dropped-marker"));
    CHECK_TRUE(Contains(ReadAll(log.CurrentLogPath()), "warn-kept-marker"));

    // Stop 后写入静默丢弃，不再落盘。
    log.Stop();
    CHECK_TRUE(!log.IsStarted());
    log.Error("book", "after-stop-marker");
    CHECK_EQ(static_cast<int>(ReadLines(log.CurrentLogPath()).size()), 5);
    CHECK_TRUE(!Contains(ReadAll(log.CurrentLogPath()), "after-stop-marker"));

    RemoveDir(dir);
}

// ─────────────────────────────────────────────────────────────────────
void TestRolling()
{
    const std::string dir = EnsureTempDir("roll");
    pb::LogOptions options = MakeOptions(dir);
    options.maxFileBytes = 4096;   // = 构造函数允许的下限
    options.maxBackupFiles = 2;
    pb::LogService log(options);

    std::string error;
    CHECK_TRUE(log.Start(&error));

    const int kLines = 150;
    // 每行约 130 字节；150 行 ≈ 19 KiB，3 个文件的保留容量约 12 KiB，
    // 所以最早的 marker 一定会被滚掉（确定性，不依赖时序）。
    const std::string payload = "abcdefghij abcdefghij abcdefghij abcdefghij abcdefghij abcdefghij";
    for (int i = 0; i < kLines; ++i) {
        log.Info("roll", "marker-" + std::to_string(i) + "-" + payload);
    }
    log.Flush();

    CHECK_TRUE(log.RotationCount() >= 2);
    const std::vector<std::string> files = log.LogFiles();
    CHECK_EQ(static_cast<int>(files.size()), 3);   // 当前 + 2 个备份
    CHECK_TRUE(log.CurrentFileBytes() > 0);
    CHECK_TRUE(log.CurrentFileBytes() <= options.maxFileBytes + 1024);

    bool firstSurvived = false;
    bool lastSurvived = false;
    int totalLines = 0;
    for (const std::string& file : files) {
        // 每个文件都不超过上限（单行超过上限的极端情况留了余量）。
        CHECK_TRUE(FileBytes(file) <= options.maxFileBytes + 1024);
        const std::string text = ReadAll(file);
        CHECK_TRUE(!text.empty());
        CHECK_TRUE(text.back() == '\n');   // 最后一行也是完整行（无半行）
        for (const std::string& line : ReadLines(file)) {
            ++totalLines;
            CHECK_TRUE(LooksLikeLogLine(line));
            if (Contains(line, " [roll] ")) {
                if (Contains(line, "marker-0-")) firstSurvived = true;
                if (Contains(line, "marker-" + std::to_string(kLines - 1) + "-")) lastSurvived = true;
            }
        }
    }
    CHECK_TRUE(lastSurvived);
    CHECK_TRUE(!firstSurvived);                     // 最老的记录已经被滚掉
    CHECK_TRUE(totalLines > 0 && totalLines < kLines);

    log.Stop();
    RemoveDir(dir);
}

// ─────────────────────────────────────────────────────────────────────
void TestRedaction()
{
    const std::string dir = EnsureTempDir("redact");
    pb::LogService log(MakeOptions(dir));

    {
        const std::string out = log.Redact("Authorization: Bearer sk-proj-ABCDEFGHIJKLMNOP1234567890");
        CHECK_TRUE(!Contains(out, "ABCDEFGHIJKLMNOP"));
        CHECK_TRUE(!Contains(out, "sk-proj-"));
        CHECK_TRUE(Contains(out, "Authorization:"));
        CHECK_TRUE(Contains(out, "<redacted>"));
    }
    {
        const std::string out = log.Redact("{\"api_key\": \"super-secret-value\", \"level\": \"info\"}");
        CHECK_TRUE(!Contains(out, "super-secret-value"));
        CHECK_TRUE(Contains(out, "\"api_key\": \"<redacted>\""));
        CHECK_TRUE(Contains(out, "\"level\": \"info\""));
    }
    {
        const std::string out = log.Redact("Cookie: session=abcdef123456; theme=dark");
        CHECK_TRUE(!Contains(out, "abcdef123456"));
        CHECK_TRUE(!Contains(out, "theme=dark"));
        CHECK_TRUE(Contains(out, "Cookie: <redacted>"));
    }
    {
        const std::string out = log.Redact("GET https://alice:hunter2@example.com/path?page=2");
        CHECK_TRUE(!Contains(out, "alice"));
        CHECK_TRUE(!Contains(out, "hunter2"));
        CHECK_TRUE(Contains(out, "https://<redacted>@example.com/path?page=2"));
    }
    {
        const std::string out = log.Redact("token=abcDEF123456&page=2");
        CHECK_TRUE(!Contains(out, "abcDEF123456"));
        CHECK_TRUE(Contains(out, "page=2"));
    }
    {
        const std::string out = log.Redact("key=ghp_0123456789abcdefghijklmnop");
        CHECK_TRUE(!Contains(out, "ghp_"));
        CHECK_TRUE(Contains(out, "<redacted>"));
    }
    {
        const std::string out = log.Redact("jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.c2lnbmF0dXJl");
        CHECK_TRUE(!Contains(out, "eyJhbGciOiJIUzI1NiJ9"));
        CHECK_TRUE(Contains(out, "<redacted>"));
    }
    {
        const std::string out = log.Redact("blob=ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghij0123456789");
        CHECK_TRUE(!Contains(out, "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghij0123456789"));
        CHECK_TRUE(Contains(out, "<redacted>"));
    }

    // 用户目录：进程环境里的真实前缀，以及任意 C:\Users\<name>。
    const std::string profile = GetEnvText("USERPROFILE");
    if (!profile.empty()) {
        const std::string out = log.Redact(profile + "\\Documents\\Dune.epub");
        CHECK_TRUE(!Contains(out, profile));
        CHECK_TRUE(Contains(out, "<user>"));
        CHECK_TRUE(Contains(out, "Dune.epub"));
    }
    {
        const std::string generic = "C:\\Users\\Bob\\Books\\x.epub";
        const std::string out = log.Redact(generic);
        CHECK_TRUE(!Contains(out, "Bob"));
        CHECK_TRUE(Contains(out, "<user>"));
        CHECK_TRUE(Contains(out, "Books"));
    }

    // 普通文本不得被误伤（日志还要能读）。
    const std::string benign = "Loaded chapter 12 of Dune.pdf from F:\\Books\\Dune.epub (42 ms)";
    CHECK_EQ(log.Redact(benign), benign);
    CHECK_EQ(log.Redact("WARN network timeout after 3 retries"), std::string("WARN network timeout after 3 retries"));

    RemoveDir(dir);
}

// ─────────────────────────────────────────────────────────────────────
void TestRedactionOptions()
{
    const std::string dir = EnsureTempDir("redact-custom");

    {
        pb::LogOptions options = MakeOptions(dir);
        options.redaction.extraKeys.push_back("author");
        options.redaction.extraPaths.push_back(std::make_pair(std::string("F:\\Private\\Library"), std::string("<library>")));
        pb::LogService log(options);

        const std::string out = log.Redact("author=Stephen King");
        CHECK_TRUE(!Contains(out, "Stephen King"));
        CHECK_TRUE(Contains(out, "<redacted>"));

        const std::string pathOut = log.Redact("F:\\Private\\Library\\book.epub");
        CHECK_TRUE(!Contains(pathOut, "Private"));
        CHECK_TRUE(Contains(pathOut, "<library>"));
    }
    {
        pb::LogOptions options = MakeOptions(dir);
        options.redaction.enabled = false;
        pb::LogService log(options);
        const std::string out = log.Redact("apiKey=plain-value");
        CHECK_EQ(out, std::string("apiKey=plain-value"));
    }
    {
        pb::LogOptions options = MakeOptions(dir);
        options.redaction.tokenShapes = false;
        pb::LogService log(options);
        CHECK_TRUE(Contains(log.Redact("key sk-abcdefghijklmnopqrstuvwx"), "sk-abcdefghijklmnopqrstuvwx"));
    }

    RemoveDir(dir);
}

// ─────────────────────────────────────────────────────────────────────
void TestStartupBanner()
{
    const std::string dir = EnsureTempDir("banner");
    pb::LogOptions options = MakeOptions(dir);
    options.logStartupBanner = true;
    options.appVersion = "9.9.9-banner";
    options.appChannel = "test-channel";
    pb::LogService log(options);

    std::string error;
    CHECK_TRUE(log.Start(&error));
    log.Flush();

    const std::string text = ReadAll(log.CurrentLogPath());
    CHECK_TRUE(Contains(text, "diagnostics start"));
    CHECK_TRUE(Contains(text, "9.9.9-banner"));
    CHECK_TRUE(Contains(text, "test-channel"));
    CHECK_TRUE(Contains(text, "os: Windows"));
    CHECK_TRUE(Contains(text, "arch: "));
    CHECK_TRUE(Contains(text, "log format: 1"));
    CHECK_TRUE(Contains(text, "redaction: enabled"));
    CHECK_TRUE(Contains(text, "log directory:"));

    const std::string profile = GetEnvText("USERPROFILE");
    if (!profile.empty()) {
        CHECK_TRUE(!Contains(text, profile));   // 横幅里不得出现用户目录明文
    }
    CHECK_TRUE(!Contains(text, "<redacted><redacted>"));

    log.Stop();
    RemoveDir(dir);
}

// ─────────────────────────────────────────────────────────────────────
void TestConcurrentWrites()
{
    const std::string dir = EnsureTempDir("concurrent");
    pb::LogOptions options = MakeOptions(dir);
    options.maxFileBytes = 16 * 1024 * 1024;
    options.maxBackupFiles = 1;
    pb::LogService log(options);

    std::string error;
    CHECK_TRUE(log.Start(&error));

    const int kThreads = 8;
    const int kPerThread = 200;
    std::vector<std::thread> threads;
    threads.reserve(kThreads);
    for (int t = 0; t < kThreads; ++t) {
        threads.emplace_back([&, t]() {
            for (int i = 0; i < kPerThread; ++i) {
                log.Info("conc", "T" + std::to_string(t) + ":L" + std::to_string(i) + ":");
            }
        });
    }
    for (std::thread& thread : threads) thread.join();
    log.Flush();

    const std::string text = ReadAll(log.CurrentLogPath());
    const std::vector<std::string> lines = ReadLines(log.CurrentLogPath());
    CHECK_EQ(static_cast<int>(lines.size()), kThreads * kPerThread);

    int malformed = 0;
    for (const std::string& line : lines) {
        if (!LooksLikeLogLine(line)) { ++malformed; continue; }
        const size_t tagPos = line.find(" [conc] ");
        if (tagPos == std::string::npos) { ++malformed; continue; }
        const std::string message = line.substr(tagPos + 8);
        // 期望形如 "T3:L17:"，多一个字符都说明两个线程的行被撕裂混写了。
        if (message.size() < 5 || message[0] != 'T') { ++malformed; continue; }
        const size_t colon = message.find(':');
        if (colon == std::string::npos || message.back() != ':') { ++malformed; continue; }
        if (message.find(' ', 0) != std::string::npos) { ++malformed; continue; }
    }
    CHECK_EQ(malformed, 0);

    // 每个 marker 恰好出现一次（不丢、不重、不互相污染）。
    for (int t = 0; t < kThreads; ++t) {
        for (int i = 0; i < kPerThread; ++i) {
            const std::string marker = "T" + std::to_string(t) + ":L" + std::to_string(i) + ":";
            if (CountOf(text, marker) != 1) {
                CHECK_TRUE(false);
                std::printf("        duplicate/missing marker: %s\n", marker.c_str());
                t = kThreads;   // 只报第一处，避免刷屏
                break;
            }
        }
    }

    log.Stop();
    RemoveDir(dir);
}

// ─────────────────────────────────────────────────────────────────────
void TestExportDiagnostics()
{
    const std::string dir = EnsureTempDir("export");
    pb::LogOptions options = MakeOptions(dir);
    options.logStartupBanner = true;
    options.appVersion = "2.2.1-export";
    pb::LogService log(options);

    std::string error;
    CHECK_TRUE(log.Start(&error));
    log.Info("test", "export-marker-line");
    log.Error("test", "Authorization: Bearer sk-proj-TOPSECRETTOPSECRET12345678");
    log.Flush();

    // 日志盘上就不得有明文密钥（脱敏在写盘之前）。
    const std::string logText = ReadAll(log.CurrentLogPath());
    CHECK_TRUE(Contains(logText, "export-marker-line"));
    CHECK_TRUE(!Contains(logText, "TOPSECRETTOPSECRET"));
    CHECK_TRUE(Contains(logText, "<redacted>"));

    const std::string target = EnsureTempDir("export-parent") + "\\pb-diag-test";
    pb::DiagnosticsPackage package;
    CHECK_TRUE(log.ExportDiagnostics(target, &package, &error));
    CHECK_EQ(error, std::string());
    CHECK_TRUE(fs::exists(TPath(package.directory)));
    CHECK_EQ(package.directory, target);

    bool hasVersion = false;
    bool hasSystem = false;
    bool hasSettings = false;
    bool hasReadme = false;
    bool hasLog = false;
    for (const std::string& name : package.files) {
        const std::string path = target + "\\" + name;
        CHECK_TRUE(fs::exists(TPath(path)));
        CHECK_TRUE(FileBytes(path) > 0);
        if (name == "version.txt") hasVersion = true;
        if (name == "system.txt") hasSystem = true;
        if (name == "settings.json") hasSettings = true;
        if (name == "README.txt") hasReadme = true;
        if (name.size() > 4 && name.substr(name.size() - 4) == ".log") hasLog = true;
    }
    CHECK_TRUE(hasVersion);
    CHECK_TRUE(hasSystem);
    CHECK_TRUE(hasSettings);
    CHECK_TRUE(hasReadme);
    CHECK_TRUE(hasLog);
    CHECK_TRUE(package.totalBytes > 0);

    const std::string exportedLog = ReadAll(target + "\\particlebook.log");
    CHECK_TRUE(Contains(exportedLog, "export-marker-line"));
    CHECK_TRUE(!Contains(exportedLog, "TOPSECRETTOPSECRET"));
    CHECK_TRUE(Contains(ReadAll(target + "\\version.txt"), "2.2.1-export"));
    CHECK_TRUE(Contains(ReadAll(target + "\\system.txt"), "os: Windows"));
    const std::string settings = ReadAll(target + "\\settings.json");
    CHECK_TRUE(Contains(settings, "\"logFormatVersion\": 1"));
    CHECK_TRUE(Contains(settings, "\"redaction\""));
    CHECK_TRUE(Contains(settings, "\"userPaths\": true"));
    CHECK_TRUE(!Contains(settings, "TOPSECRETTOPSECRET"));

    // 同名目录不被覆盖：第二次导出落到 -2。
    pb::DiagnosticsPackage second;
    CHECK_TRUE(log.ExportDiagnostics(target, &second, &error));
    CHECK_TRUE(second.directory != package.directory);
    CHECK_TRUE(fs::exists(TPath(second.directory)));
    CHECK_TRUE(Contains(second.directory, "-2"));

    log.Stop();
    RemoveDir(dir);
    RemoveDir(fs::path(TPath(target)).parent_path().u8string());
    RemoveDir(fs::path(TPath(second.directory)).parent_path().u8string());
}

// ─────────────────────────────────────────────────────────────────────
void TestNotStartedAndDefaults()
{
    const std::string dir = EnsureTempDir("notstarted");
    pb::LogOptions options = MakeOptions(dir);
    pb::LogService log(options);

    CHECK_TRUE(!log.IsStarted());
    log.Info("test", "no-start-marker");
    CHECK_TRUE(log.DroppedCount() >= 1);
    CHECK_TRUE(log.LogFiles().empty());
    CHECK_EQ(log.CurrentFileBytes(), static_cast<unsigned long long>(0));

    // 没 Start 也应能导出一份「只有系统信息」的诊断包。
    const std::string target = EnsureTempDir("notstarted-pkg") + "\\pkg";
    pb::DiagnosticsPackage package;
    std::string error;
    CHECK_TRUE(log.ExportDiagnostics(target, &package, &error));
    CHECK_TRUE(fs::exists(TPath(target + "\\system.txt")));
    CHECK_TRUE(Contains(ReadAll(target + "\\system.txt"), "log format: 1"));

    // 默认目录：非空、指向 ParticleBook（不实际创建）。
    CHECK_TRUE(!pb::LogService::DefaultLogDirectory().empty());
    CHECK_TRUE(Contains(pb::LogService::DefaultLogDirectory(), "ParticleBook"));
    CHECK_TRUE(Contains(pb::LogService::DefaultDiagnosticsDirectory(), "ParticleBook"));

    RemoveDir(dir);
    RemoveDir(fs::path(TPath(target)).parent_path().u8string());
}

// ─────────────────────────────────────────────────────────────────────
void TestLevelsAndGlobalFacade()
{
    pb::LogLevel level = pb::LogLevel::Info;
    CHECK_TRUE(pb::ParseLogLevel("warn", level));
    CHECK_TRUE(level == pb::LogLevel::Warn);
    CHECK_TRUE(pb::ParseLogLevel("WARNING", level));
    CHECK_TRUE(level == pb::LogLevel::Warn);
    CHECK_TRUE(pb::ParseLogLevel("off", level));
    CHECK_TRUE(level == pb::LogLevel::Off);
    CHECK_TRUE(!pb::ParseLogLevel("bogus", level));
    CHECK_EQ(std::string(pb::LogLevelName(pb::LogLevel::Error)), std::string("ERROR"));
    CHECK_EQ(std::string(pb::LogLevelName(pb::LogLevel::Off)), std::string("OFF"));

    const std::string dir = EnsureTempDir("global");
    pb::LogOptions options = MakeOptions(dir);
    std::shared_ptr<pb::LogService> logger = std::make_shared<pb::LogService>(options);
    std::string error;
    CHECK_TRUE(logger->Start(&error));

    pb::SetGlobalLogger(logger);
    CHECK_TRUE(pb::GetGlobalLogger() != nullptr);
    pb::LogInfo("global", "through-global-facade");
    pb::LogWarn("global", "global-warn");
    logger->Flush();
    CHECK_TRUE(Contains(ReadAll(logger->CurrentLogPath()), "through-global-facade"));
    CHECK_TRUE(Contains(ReadAll(logger->CurrentLogPath()), "global-warn"));

    pb::SetGlobalLogger(nullptr);
    CHECK_TRUE(pb::GetGlobalLogger() == nullptr);
    pb::LogInfo("global", "no-logger-marker");   // 空门面：不得崩溃
    logger->Stop();
    RemoveDir(dir);
}

} // namespace

int main()
{
    TestLevelAndWrite();
    TestRolling();
    TestRedaction();
    TestRedactionOptions();
    TestStartupBanner();
    TestConcurrentWrites();
    TestExportDiagnostics();
    TestNotStartedAndDefaults();
    TestLevelsAndGlobalFacade();
    return pb_t::Summary("log_service_test");
}
