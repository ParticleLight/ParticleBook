#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shellapi.h>
#include "App.h"
#include "platform/LogService.h"
#include "platform/SingleInstance.h"
#include "utils/encoding.h"

#include <string>
#include <vector>

namespace {

// CommandLineToArgvW handles quoted paths (spaces, trailing backslashes)
// exactly as Windows does. argv[0] is the exe; the rest are ebook paths.
std::vector<std::string> GetStartupPaths()
{
    std::vector<std::string> paths;
    int argc = 0;
    LPWSTR* argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    if (!argv) return paths;
    for (int i = 1; i < argc; ++i) {
        const std::string utf8 = pb::WideToUtf8(argv[i]);
        if (!utf8.empty()) paths.push_back(utf8);
    }
    LocalFree(argv);
    return paths;
}

} // namespace

int WINAPI wWinMain(HINSTANCE hInstance, HINSTANCE, LPWSTR, int nCmdShow)
{
    (void)nCmdShow;
    SetEnvironmentVariableW(L"ELECTRON_RUN_AS_NODE", nullptr);
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);

    SingleInstance single;
    if (!single.Acquire()) {
        // Another process owns the DB. Forward paths and exit; it is the
        // primary's job to create the window and import/open them.
        single.Forward(GetStartupPaths());
        return 0;
    }

    try {
        App::Instance().Init(hInstance, GetStartupPaths(), &single);
        App::Instance().Run();
        App::Instance().Shutdown();
        return 0;
    } catch (const std::exception& e) {
        // PB-016-B: 未捕获的 C++ 异常要进本地诊断日志（无上传）。LogService 的
        // 全局实例可能已经随 Init 装好，也可能还没装 — 未装时 LogError 是空操作，
        // 这里绝不能因为日志失败而改变退出行为。
        pb::LogError("app", std::string("fatal: uncaught std::exception: ") + e.what());
        MessageBoxA(nullptr, e.what(), "ParticleBook Fatal Error", MB_OK | MB_ICONERROR);
        return 1;
    } catch (...) {
        // 非 std::exception 的未捕获异常：记录后原样重抛，保持「未捕获即终止」
        // 的语义不变（只是多了一行日志）。
        pb::LogError("app", "fatal: uncaught non-std exception");
        throw;
    }
}
