#include "WebViewHost.h"
#include "App.h"
#include <wrl/event.h>
#include <filesystem>
#include <cstdio>
#include <cstring>
#include <thread>

#include "WebViewHostInternal.h"
#include "platform/LogService.h"
#include <dwmapi.h>

using namespace Microsoft::WRL;

namespace {

// ── 原生标题栏（DWM）─────────────────────────────────────────────────
// 本功能第一次尝试是失败的（docs/memory/pitfalls.md「原生 Windows 标题栏不跟
// M3 暗色主题」）：当时只调 DwmSetWindowAttribute 就以为成功，界面拿到了 true
// 而标题栏依旧纯白。这次的硬约束：
//   1) DWMWA_USE_IMMERSIVE_DARK_MODE 的值必须是 BOOL、cbAttribute=sizeof(BOOL)
//      —— 传 1 字节 bool 会被 DWM 当成非法长度直接忽略；
//   2) caption / text / border 颜色属性只有 Win11（build >= 22000）才有；
//   3) 设完必须 SWP_FRAMECHANGED 强制重画非客户区，再用 DwmGetWindowAttribute
//      读回并把 HRESULT + 读回值写进日志 —— 读回值才是「真的生效」的证据。
constexpr DWORD kDwmUseImmersiveDarkMode    = 20;    // Win10 20H1(19041)+ / Win11
constexpr DWORD kDwmUseImmersiveDarkModeOld = 19;    // Win10 1809–1909 只认这个编号
constexpr DWORD kDwmBorderColor             = 34;    // Win11 22000+
constexpr DWORD kDwmCaptionColor            = 35;
constexpr DWORD kDwmTextColor               = 36;
constexpr DWORD kBuildWin11                 = 22000;
constexpr DWORD kBuildWin10_20H1            = 19041;

struct ThemePalette {
    bool dark;
    unsigned captionRgb;   // 0xRRGGBB —— 与 globals.css 的 --bg 同值
    unsigned textRgb;      // 0xRRGGBB —— 与 globals.css 的 --text-primary 同值
};

ThemePalette PaletteForTheme(const std::string& theme)
{
    if (theme == "light") return ThemePalette{false, 0xFFFFFFu, 0x1A1A1Au};
    if (theme == "sepia") return ThemePalette{true, 0x1A1510u, 0xD4C4A8u};
    return ThemePalette{true, 0x1A1A1Au, 0xE8E8E8u};   // dark（默认）
}

// COLORREF 是 0x00BBGGRR —— 通道顺序与 0xRRGGBB 相反，别直接塞。
COLORREF ToColorRef(unsigned rgb)
{
    return RGB(static_cast<BYTE>((rgb >> 16) & 0xFFu),
               static_cast<BYTE>((rgb >> 8) & 0xFFu),
               static_cast<BYTE>(rgb & 0xFFu));
}

// 读回值同样是 COLORREF，转回 0xRRGGBB 才好跟期望值比。
unsigned RgbFromColorRef(COLORREF color)
{
    return (static_cast<unsigned>(GetRValue(color)) << 16) |
           (static_cast<unsigned>(GetGValue(color)) << 8) |
           static_cast<unsigned>(GetBValue(color));
}

// 本工程没有 manifest：GetVersionEx / VerifyVersionInfo 会按兼容性伪装成 6.2，
// RtlGetVersion 是唯一可信的 build number 来源（与 LogService 同一套做法）。
DWORD WindowsBuildNumber()
{
    struct OsVersionInfoW {
        ULONG dwOSVersionInfoSize;
        ULONG dwMajorVersion;
        ULONG dwMinorVersion;
        ULONG dwBuildNumber;
        ULONG dwPlatformId;
        WCHAR szCSDVersion[128];
    };
    using RtlGetVersionFn = LONG(WINAPI*)(OsVersionInfoW*);
    static const DWORD cached = [] {
        OsVersionInfoW info{};
        info.dwOSVersionInfoSize = sizeof info;
        HMODULE ntdll = GetModuleHandleW(L"ntdll.dll");
        FARPROC proc = ntdll ? GetProcAddress(ntdll, "RtlGetVersion") : nullptr;
        RtlGetVersionFn fn = nullptr;
        if (proc) std::memcpy(&fn, &proc, sizeof fn);   // 避免 /W4 C4191
        if (!fn || fn(&info) != 0) return static_cast<DWORD>(0);
        return static_cast<DWORD>(info.dwBuildNumber);
    }();
    return cached;
}

std::string HexDword(DWORD value)
{
    char buf[16];
    std::snprintf(buf, sizeof buf, "0x%08lX", static_cast<unsigned long>(value));
    return buf;
}

std::string HrText(HRESULT hr) { return HexDword(static_cast<DWORD>(hr)); }

std::string HexRgb(unsigned rgb)
{
    char buf[16];
    std::snprintf(buf, sizeof buf, "#%06lX", static_cast<unsigned long>(rgb & 0xFFFFFFu));
    return buf;
}

} // namespace

bool WebViewHost::ApplyThemeToWindow(const std::string& theme)
{
    if (!m_hwnd || !IsWindow(m_hwnd)) return false;

    const ThemePalette palette = PaletteForTheme(theme);
    const DWORD build = WindowsBuildNumber();

    // 1) 沉浸式暗色标题栏。值必须是 4 字节 BOOL。
    BOOL useDark = palette.dark ? TRUE : FALSE;
    DWORD darkAttr = (build >= kBuildWin10_20H1) ? kDwmUseImmersiveDarkMode
                                                 : kDwmUseImmersiveDarkModeOld;
    HRESULT hrDark = DwmSetWindowAttribute(m_hwnd, static_cast<DWMWINDOWATTRIBUTE>(darkAttr),
                                           &useDark, sizeof useDark);
    if (FAILED(hrDark)) {
        // build 判断可能不准：换另一个编号再试一次（1809–1909 只认 19）。
        const DWORD alt = (darkAttr == kDwmUseImmersiveDarkMode) ? kDwmUseImmersiveDarkModeOld
                                                                 : kDwmUseImmersiveDarkMode;
        const HRESULT hrAlt = DwmSetWindowAttribute(m_hwnd, static_cast<DWMWINDOWATTRIBUTE>(alt),
                                                    &useDark, sizeof useDark);
        if (SUCCEEDED(hrAlt)) { darkAttr = alt; hrDark = hrAlt; }
    }

    // 2) Win11 才有 caption / text / border 颜色（< 22000 传这几个编号只会 E_INVALIDARG）。
    const COLORREF caption = ToColorRef(palette.captionRgb);
    const COLORREF text = ToColorRef(palette.textRgb);
    const COLORREF border = caption;   // 与标题栏同色，免得多一条亮描边
    HRESULT hrCaption = E_NOTIMPL, hrText = E_NOTIMPL, hrBorder = E_NOTIMPL;
    if (build >= kBuildWin11) {
        const DWORD cb = sizeof(COLORREF);
        hrBorder  = DwmSetWindowAttribute(m_hwnd, static_cast<DWMWINDOWATTRIBUTE>(kDwmBorderColor),  &border,  cb);
        hrCaption = DwmSetWindowAttribute(m_hwnd, static_cast<DWMWINDOWATTRIBUTE>(kDwmCaptionColor), &caption, cb);
        hrText    = DwmSetWindowAttribute(m_hwnd, static_cast<DWMWINDOWATTRIBUTE>(kDwmTextColor),    &text,    cb);
    }

    // 3) 强制非客户区重画 —— 少了这步，属性设成功也要等下次 resize 才看见。
    SetWindowPos(m_hwnd, nullptr, 0, 0, 0, 0,
                 SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);
    RedrawWindow(m_hwnd, nullptr, nullptr, RDW_INVALIDATE | RDW_FRAME | RDW_UPDATENOW);

    // 4) 读回：DwmGetWindowAttribute 的值才是「真的生效」的证据。
    BOOL readDark = FALSE;
    const HRESULT hrGetDark = DwmGetWindowAttribute(
        m_hwnd, static_cast<DWMWINDOWATTRIBUTE>(darkAttr), &readDark, sizeof readDark);
    COLORREF readCaption = 0, readText = 0, readBorder = 0;
    HRESULT hrGetCaption = E_NOTIMPL, hrGetText = E_NOTIMPL, hrGetBorder = E_NOTIMPL;
    if (build >= kBuildWin11) {
        const DWORD cb = sizeof(COLORREF);
        hrGetBorder  = DwmGetWindowAttribute(m_hwnd, static_cast<DWMWINDOWATTRIBUTE>(kDwmBorderColor),  &readBorder,  cb);
        hrGetCaption = DwmGetWindowAttribute(m_hwnd, static_cast<DWMWINDOWATTRIBUTE>(kDwmCaptionColor), &readCaption, cb);
        hrGetText    = DwmGetWindowAttribute(m_hwnd, static_cast<DWMWINDOWATTRIBUTE>(kDwmTextColor),    &readText,    cb);
    }

    // 5) 标题栏几何（汇报与截图取样要用）：窗口矩形 + 客户区起点 => 标题栏高度。
    RECT winRect{};
    GetWindowRect(m_hwnd, &winRect);
    POINT clientOrigin{0, 0};
    ClientToScreen(m_hwnd, &clientOrigin);

    const bool darkMatches = SUCCEEDED(hrGetDark) && ((readDark != FALSE) == palette.dark);
    const std::string msg =
        "theme=" + theme + " build=" + std::to_string(static_cast<unsigned long>(build)) +
        " winRect=" + std::to_string(winRect.left) + "," + std::to_string(winRect.top) + " " +
        std::to_string(winRect.right - winRect.left) + "x" +
        std::to_string(winRect.bottom - winRect.top) +
        " captionHeight=" + std::to_string(clientOrigin.y - winRect.top) +
        " | dark(attr=" + std::to_string(static_cast<unsigned long>(darkAttr)) + ")" +
        " set=" + HrText(hrDark) +
        " get=" + HrText(hrGetDark) +
        " read=" + (readDark ? "TRUE" : "FALSE") +
        " want=" + (palette.dark ? "TRUE" : "FALSE") +
        " | caption set=" + HrText(hrCaption) + " get=" + HrText(hrGetCaption) +
        " read=" + (SUCCEEDED(hrGetCaption) ? HexRgb(RgbFromColorRef(readCaption)) : std::string("-")) +
        " want=" + HexRgb(palette.captionRgb) +
        " | text set=" + HrText(hrText) + " get=" + HrText(hrGetText) +
        " read=" + (SUCCEEDED(hrGetText) ? HexRgb(RgbFromColorRef(readText)) : std::string("-")) +
        " want=" + HexRgb(palette.textRgb) +
        " | border set=" + HrText(hrBorder) + " get=" + HrText(hrGetBorder) +
        " read=" + (SUCCEEDED(hrGetBorder) ? HexRgb(RgbFromColorRef(readBorder)) : std::string("-"));

    if (FAILED(hrDark) || (SUCCEEDED(hrGetDark) && !darkMatches)) {
        pb::LogWarn("theme", msg);
    } else {
        pb::LogInfo("theme", msg);
    }
    return SUCCEEDED(hrDark);
}

bool WebViewHost::ApplyTheme(const std::string& theme)
{
    const std::string normalized =
        (theme == "light" || theme == "sepia") ? theme : "dark";
    m_pendingTheme = normalized;
    // 窗口还没建：CreateMainWindow 会在 ShowWindow 之前用 m_pendingTheme 应用。
    if (!m_hwnd || !IsWindow(m_hwnd)) return false;
    // DWM 属性 + SWP_FRAMECHANGED 必须落在窗口线程上。
    if (GetCurrentThreadId() != GetWindowThreadProcessId(m_hwnd, nullptr)) {
        PostToUiThread([this, normalized]() { ApplyThemeToWindow(normalized); });
        return true;
    }
    return ApplyThemeToWindow(normalized);
}

WebViewHost::WebViewHost()
    : m_dispatcher(std::make_shared<pb::UiDispatcher>()) {}

WebViewHost::~WebViewHost()
{
    if (m_dispatcher) { m_dispatcher->Attach(nullptr); m_dispatcher->Stop(); }
    if (m_webview) {
        m_webview->remove_WebMessageReceived(m_msgToken);
    }
    if (m_controller) {
        m_controller->Close();
    }
    if (m_hwnd) {
        DestroyWindow(m_hwnd);
    }
}

void WebViewHost::CreateMainWindow(HINSTANCE hInstance)
{
    const wchar_t CLASS_NAME[] = L"ParticleBook_MainWindow";

    WNDCLASSW wc = {};
    wc.lpfnWndProc   = WndProc;
    wc.hInstance     = hInstance;
    wc.hCursor       = LoadCursor(nullptr, IDC_ARROW);
    wc.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
    wc.lpszClassName = CLASS_NAME;
    RegisterClassW(&wc);

    m_hwnd = CreateWindowExW(
        WS_EX_APPWINDOW,
        CLASS_NAME, L"ParticleBook",
        WS_OVERLAPPEDWINDOW,
        CW_USEDEFAULT, CW_USEDEFAULT, 1400, 900,
        nullptr, nullptr, hInstance, this
    );

    if (m_dispatcher) m_dispatcher->Attach(m_hwnd);

    // Set window icon from embedded resource
    HICON hIcon = LoadIconW(hInstance, MAKEINTRESOURCEW(101));
    if (hIcon) {
        SendMessageW(m_hwnd, WM_SETICON, ICON_BIG, (LPARAM)hIcon);
        SendMessageW(m_hwnd, WM_SETICON, ICON_SMALL, (LPARAM)hIcon);
    }

    // 原生标题栏在第一次 ShowWindow 之前就上色：否则暗色主题启动时窗口会先
    // 亮出一条白标题栏（DWM 默认浅色）再变黑，用户能看到明显闪白。
    ApplyTheme(m_pendingTheme);

    ShowWindow(m_hwnd, SW_SHOW);
    UpdateWindow(m_hwnd);
}

void WebViewHost::RunMessageLoop()
{
    MSG msg = {};
    while (GetMessage(&msg, nullptr, 0, 0)) {
        TranslateMessage(&msg);
        try {
            DispatchMessage(&msg);
        } catch (...) {
            // Silently ignore unhandled exceptions
        }
    }
}

LRESULT CALLBACK WebViewHost::WndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp)
{
    WebViewHost* self = nullptr;

    if (msg == WM_NCCREATE) {
        auto* cs = reinterpret_cast<CREATESTRUCT*>(lp);
        self = static_cast<WebViewHost*>(cs->lpCreateParams);
        SetWindowLongPtr(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(self));
        self->m_hwnd = hwnd;
    } else {
        self = reinterpret_cast<WebViewHost*>(GetWindowLongPtr(hwnd, GWLP_USERDATA));
    }

    // 必须显式用 W 版：本工程未定义 UNICODE 宏时 DefWindowProc 会展开成 ANSI 版，
    // 于是 WM_SETTEXT 等消息里的宽字符串被按 ANSI 解读 —— 窗口标题因此只剩首字符 "P"。
    if (!self) return DefWindowProcW(hwnd, msg, wp, lp);

    if (self->m_dispatcher && self->m_dispatcher->OnWindowMessage(msg)) return 0;

    switch (msg) {
    case WM_CREATE:
        PostMessage(hwnd, WM_INIT_WEBVIEW, 0, 0);
        return 0;

    case WM_INIT_WEBVIEW:
        self->InitWebView();
        return 0;

    case WM_SIZE:
        if (self->m_controller) {
            RECT bounds;
            GetClientRect(hwnd, &bounds);
            self->m_controller->put_Bounds(bounds);
        }
        if (self->m_moveCb) self->m_moveCb();
        return 0;

    case WM_ZLIB_DOWNLOAD_DONE:
        if (wp == 1 && self->m_dlCb) {
            auto* data = reinterpret_cast<std::pair<std::string, std::string>*>(lp);
            self->m_dlCb(data->first, data->second);
            delete data;
        }
        return 0;

    case WM_ZLIB_DOWNLOAD_PROGRESS:
        if (self->m_dlProgressCb) {
            auto* data = reinterpret_cast<DLProgressData*>(lp);
            self->m_dlProgressCb(data->fileName, data->received, data->total);
            delete data;
        }
        return 0;

    case WM_ZLIB_DO_IMPORT:
        if (self->m_importCb) {
            auto* data = reinterpret_cast<std::pair<std::string, std::string>*>(lp);
            self->m_importCb(data->first, data->second);
            delete data;
        }
        return 0;

    case WM_ZLIB_IMPORT_DONE:
        if (self->m_importResultCb) {
            auto* data = reinterpret_cast<ImportResultData*>(lp);
            self->m_importResultCb(data->fileName, data->success, data->error);
            delete data;
        }
        return 0;

    case WM_ZLIB_DOWNLOAD_FAILED:
        if (self->m_dlFailCb) {
            auto* data = reinterpret_cast<DLFailData*>(lp);
            self->m_dlFailCb(data->fileName, data->reason);
            delete data;
        }
        return 0;

    case WM_ZLIB_ENTRY_NAVIGATE:
        if (self->m_zlibEntryNavCb) self->m_zlibEntryNavCb();
        return 0;

    case WM_ZLIB_REFRESH_LIBRARY:
        if (self->m_webview) {
            self->m_webview->ExecuteScript(L"if(window.__refreshLibrary)window.__refreshLibrary();", nullptr);
        }
        return 0;

    case WM_UPDATE_DOWNLOAD_DONE:
        if (self->m_updateDoneCb) {
            auto* str = reinterpret_cast<std::string*>(lp);
            if (wp != 0) {
                std::string path = str ? std::move(*str) : "";
                delete str;
                self->m_updateDoneCb(true, path);
            } else {
                std::string err = str ? std::move(*str) : "unknown";
                delete str;
                self->m_updateDoneCb(false, err);
            }
        }
        return 0;

    case WM_UPDATE_CHECK_DONE:
        if (self->m_updateCheckCb) {
            auto* str = reinterpret_cast<std::string*>(lp);
            std::string result = str ? std::move(*str) : "{}";
            delete str;
            self->m_updateCheckCb(result);
        }
        return 0;

    case WM_UPDATE_DOWNLOAD_PROGRESS:
        if (self->m_updateDownloadProgressCb) {
            self->m_updateDownloadProgressCb(static_cast<int>(wp));
        }
        return 0;

    case WM_APP_OPEN_FILES:
        if (self->m_openFilesCb) self->m_openFilesCb();
        return 0;

    case WM_MOVE:
        if (self->m_moveCb) self->m_moveCb();
        return 0;

    case WM_CLOSE:
        DestroyWindow(hwnd);
        return 0;

    case WM_DESTROY:
        PostQuitMessage(0);
        return 0;

    case WM_NCDESTROY:
        if (self->m_dispatcher) self->m_dispatcher->Attach(nullptr);
        break;

    case WM_RELOAD_PAGE:
        if (self->m_webview) {
            self->m_webview->Navigate(L"http://particlebook.app/index.html");
        }
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

