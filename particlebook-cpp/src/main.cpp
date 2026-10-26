#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shellapi.h>
#include "App.h"
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
        MessageBoxA(nullptr, e.what(), "ParticleBook Fatal Error", MB_OK | MB_ICONERROR);
        return 1;
    }
}
