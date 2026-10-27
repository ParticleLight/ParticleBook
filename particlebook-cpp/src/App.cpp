#include "App.h"
#include "utils/encoding.h"
#include "services/DatabaseService.h"
#include "services/PdfService.h"
#include "services/BookSourceService.h"
#include "services/ZLibraryService.h"
#include "services/ContentCache.h"
#include "WebViewHost.h"
#include "BridgeServer.h"
#include "handlers/DbHandlers.h"
#include "handlers/AnnotationHandlers.h"
#include "handlers/BookMetadataHandlers.h"
#include "handlers/FileHandlers.h"
#include "platform/SingleInstance.h"
#include "platform/JobService.h"
#include "platform/LogService.h"
#include "platform/SapiTts.h"
#include "platform/SecretStore.h"
#include "services/TranslationService.h"
#include "services/SearchIndexService.h"
#include "services/DictionaryService.h"
#include "handlers/DictionaryHandlers.h"
#include "handlers/TtsHandlers.h"
#include "handlers/TranslationHandlers.h"
#include "handlers/SearchHandlers.h"
#include "pb_version.h"
#include <shlobj.h>
#include <shellapi.h>
#include <filesystem>
#include <fstream>
#include <memory>
#include <sstream>

// Forward declaration from BookSourceService.cpp
class BridgeServer;
class BookSourceService;
class ZLibraryService;
void RegisterBookSourceHandlers(BridgeServer* bridge, std::shared_ptr<BookSourceService> svc, pb::JobService* jobs);
void RegisterZlibHandlers(BridgeServer* bridge, ZLibraryService* zlib);
void RegisterBackupHandlers(BridgeServer* bridge, DatabaseService* db, pb::JobService* jobs);
void RegisterExportHandlers(BridgeServer* bridge, DatabaseService* db, pb::JobService* jobs);
void RegisterTranslationHandlers(BridgeServer* bridge, DatabaseService* db, pb::JobService* jobs, SecretStore* secrets, pb::TranslationService* translation);
void RegisterSearchHandlers(BridgeServer* bridge, DatabaseService* db, pb::JobService* jobs, pb::SearchIndexService* index);

// PB-010-B：词典服务由 App 持有。App.h 不在本轮写范围，所以用文件内单例
// 保存它（生命周期与进程一致，进程退出时释放），缓存落在
// <UserData>/data/dictionary/dictionary.pbdict。
namespace {
struct DictionaryRuntime {
    std::unique_ptr<pb::DictionaryService> service;
    std::string cachePath;
};
DictionaryRuntime& GetDictionaryRuntime()
{
    static DictionaryRuntime runtime;
    return runtime;
}
} // namespace

// Utf8ToWide moved to utils/encoding.h (pb::Utf8ToWide)

namespace {

// ── PB-024-A：Z-Library 注入脚本外置 ────────────────────────────────
// 工具条 / 下载卡的 JS 从 exe 同目录的 resources/*.js 读取（CMake POST_BUILD
// 拷贝，安装包一起分发），改 UI 不必重编译。文件缺失时退回内置最小脚本并记日志。
std::string ReadResourceScript(const char* fileName)
{
    wchar_t exePath[MAX_PATH] = {};
    if (!GetModuleFileNameW(nullptr, exePath, MAX_PATH)) return "";
    const std::filesystem::path path =
        std::filesystem::path(exePath).parent_path() / "resources" / fileName;
    std::ifstream input(path, std::ios::binary);
    if (!input) return "";
    std::ostringstream buffer;
    buffer << input.rdbuf();
    return buffer.str();
}

constexpr const char* kFallbackZlibToolbar = R"JS((function(){var t=setInterval(function(){if(!document.body)return;clearInterval(t);var b=document.createElement('div');b.id='zlib-bar';b.style.cssText='position:fixed;bottom:16px;right:16px;z-index:2147483647;display:flex;gap:6px;background:rgba(30,30,30,.9);border-radius:20px;padding:8px 12px';b.innerHTML='<button id=zb-back>\u25c0</button><button id=zb-reload>\u21bb</button><button id=zb-close>\u2715</button>';document.body.appendChild(b);document.getElementById('zb-back').onclick=function(){window.chrome.webview.postMessage(JSON.stringify({type:'zlibNavigate',action:'back'}))};document.getElementById('zb-reload').onclick=function(){window.chrome.webview.postMessage(JSON.stringify({type:'zlibNavigate',action:'reload'}))};document.getElementById('zb-close').onclick=function(){window.chrome.webview.postMessage(JSON.stringify({type:'zlibClose'}))};window.__pbRefreshToolbar=function(){};},100)})();)JS";

constexpr const char* kFallbackDownloadCard = R"JS((function(){var box=null;function ensure(){if(box&&box.parentNode)return box;box=document.createElement('div');box.id='_pbdl';box.style.cssText='position:fixed;top:16px;right:16px;z-index:2147483647;background:rgba(28,28,32,.94);color:#e2e8f0;padding:10px 12px;border-radius:12px;font:12px system-ui;max-width:320px';document.body.appendChild(box);return box}try{window.chrome.webview.addEventListener('message',function(e){try{var m=typeof e.data==='string'?JSON.parse(e.data):e.data;if(!m||m.type!=='event')return;var d=m.data||{};var el=ensure();if(m.event==='zlib:downloadStart'||m.event==='zlib:downloadProgress')el.textContent='\u2b07 '+(d.fileName||'');else if(m.event==='zlib:importStart'||m.event==='zlib:importComplete')el.textContent='\ud83d\udcda '+(d.fileName||'');else el.textContent=(m.event||'')+': '+((d&&d.error)||(d&&d.fileName)||'');if(m.event==='zlib:importComplete'||m.event==='zlib:importError'||m.event==='zlib:downloadError')setTimeout(function(){if(el.parentNode)el.parentNode.removeChild(el);box=null},4000)}catch(e2){}})})();)JS";

void InjectScriptResource(WebViewHost* host, const char* fileName, const char* fallback)
{
    if (!host) return;
    std::string script = ReadResourceScript(fileName);
    if (script.empty()) {
        pb::LogWarn("app", std::string("resources/") + fileName +
                               " missing or unreadable; using minimal built-in fallback");
        script = fallback;
    }
    host->InjectBridgeScript(script);
}

} // namespace

App& App::Instance() { static App app; return app; }

void App::Init(HINSTANCE hInstance, std::vector<std::string> startupPaths, SingleInstance* single)
{
    m_hInstance = hInstance;
    m_single = single;

    // PB-016: start the local diagnostic log before touching the database, so
    // startup / migration / WebView failures are captured. No telemetry.
    {
        pb::LogOptions opts;
        opts.appVersion = PB_VERSION_STRING;
        opts.appChannel = "2.2.1-dev";
        m_log = std::make_shared<pb::LogService>(std::move(opts));
        std::string err;
        if (m_log->Start(&err)) {
            pb::SetGlobalLogger(m_log);
            pb::LogInfo("app", "ParticleBook " PB_VERSION_STRING " starting");
        } else {
            m_log.reset();
            OutputDebugStringA(("LogService start failed: " + err + "\n").c_str());
        }
    }
    if (!startupPaths.empty()) {
        std::lock_guard<std::mutex> lock(m_openMutex);
        m_pendingOpenFiles = std::move(startupPaths);
    }

    // 1. Database
    m_db = std::make_shared<DatabaseService>();
    m_db->Load(UserDataPath() + "/data/reader.json");
    // Restore persisted UI language (must be set before toolbar scripts are injected)
    m_language = m_db->GetSettings().value("language", "zh") == "en" ? "en" : "zh";

    // 2. Bridge (message dispatch)
    m_bridge = std::make_shared<BridgeServer>();

    // PB-023/PB-002: DPAPI-backed provider keys + translation client.
    m_secrets = std::make_unique<SecretStore>(SecretStore::DefaultPath());
    m_translation = std::make_unique<pb::TranslationService>();
    m_search = std::make_unique<pb::SearchIndexService>();
    m_search->SetDataDir(UserDataPath() + "/data");

    // PB-001: one in-process SAPI voice instance (SAPI audio is process-wide).
    m_tts = std::make_unique<pb::SapiTts>();
    if (!m_tts->Initialize()) {
        pb::LogWarn("tts", "SAPI initialize failed: " + m_tts->LastError());
    }

    // 3. WebView host (window + browser) + background jobs
    m_webview = std::make_unique<WebViewHost>();
    m_jobs = std::make_shared<pb::JobService>(m_webview->Dispatcher());

    // Wire bridge <-> WebView
    m_bridge->SetWebView(m_webview.get());

    // PB-001: TTS events arrive on SapiTts' notifier thread; EmitEvent marshals
    // to the UI thread through UiDispatcher before touching WebView2.
    if (m_tts) {
        m_tts->SetEventHandler([this](const pb::TtsEvent& e) {
            const char* type = "started";
            switch (e.type) {
                case pb::TtsEventType::Started: type = "started"; break;
                case pb::TtsEventType::Ended: type = "ended"; break;
                case pb::TtsEventType::WordBoundary: type = "word"; break;
                case pb::TtsEventType::SentenceBoundary: type = "sentence"; break;
                case pb::TtsEventType::Error: type = "error"; break;
            }
            m_bridge->EmitEvent("tts:event", {
                {"type", type},
                {"utteranceId", e.utterance},
                {"tag", e.tag},
                {"charOffset", e.charOffset},
                {"charLength", e.charLength},
                {"byteOffset", e.byteOffset},
                {"byteLength", e.byteLength},
                {"error", e.error},
            });
        });
    }
    m_webview->SetOpenFilesCallback([this]() { FlushOpenPaths(); });
    m_webview->SetMessageHandler([this](const std::string& msg, const std::string& source) {
        m_bridge->HandleMessage(msg, source);
    });
    m_webview->SetUpdateDoneCallback([this](bool success, const std::string& path_or_error) {
        if (success) {
            m_bridge->EmitEvent("app:updateDownloaded", {{"success", true}, {"path", path_or_error}});
        } else {
            m_bridge->EmitEvent("app:updateError", {{"error", path_or_error}});
        }
    });
    m_webview->SetUpdateCheckCallback([this](const std::string& resultJson) {
        try {
            json result = json::parse(resultJson);
            m_bridge->EmitEvent("app:updateChecked", result);
        } catch (...) {
            m_bridge->EmitEvent("app:updateChecked", json(nullptr));
        }
    });
    m_webview->SetUpdateDownloadProgressCallback([this](int percent) {
        m_bridge->EmitEvent("app:downloadProgress", {{"percent", percent}});
    });

    // 4. Inject bridge script BEFORE WebView2 navigates
    m_webview->InjectBridgeScript(BridgeServer::GenerateBridgeScript());
    // UI language anchor — toolbar / download-card scripts read window.__pbLang
    m_webview->InjectBridgeScript("window.__pbLang='" + m_language + "';");
    // PB-024-A: Z-Library 注入脚本外置到 resources/*.js（见 CMake POST_BUILD）；
    // 文件缺失时回退到内置最小脚本并记录 WARN。
    InjectScriptResource(m_webview.get(), "zlib_toolbar.js", kFallbackZlibToolbar);
    InjectScriptResource(m_webview.get(), "download_card.js", kFallbackDownloadCard);

    // 5. Services
    m_pdf = std::make_unique<PdfService>();
    m_bookSource = std::make_shared<BookSourceService>(m_db, m_bridge);
    m_zlib = std::make_shared<ZLibraryService>(m_bridge.get());
    m_zlib->SetHost(m_webview.get());
    m_zlib->SetDatabase(m_db.get());
    // Background mirror prefetch (shared_ptr keeps the service alive)
    m_zlib->StartMirrorFetch(m_zlib);
    m_cache = std::make_unique<ContentCache>();

    // 6. Register IPC handlers
    RegisterDbHandlers(m_bridge.get(), m_db.get());
    RegisterAnnotationHandlers(m_bridge.get(), m_db.get());
    RegisterBookMetadataHandlers(m_bridge.get(), m_db.get());
    RegisterFileHandlers(m_bridge.get(), m_db.get(), m_cache.get(), m_jobs.get());
    RegisterBackupHandlers(m_bridge.get(), m_db.get(), m_jobs.get());
    RegisterExportHandlers(m_bridge.get(), m_db.get(), m_jobs.get());
    RegisterPdfHandlers(m_bridge.get(), m_pdf.get(), m_jobs.get());
    RegisterBookSourceHandlers(m_bridge.get(), m_bookSource, m_jobs.get());
    RegisterTtsHandlers(m_bridge.get(), m_tts.get());
    RegisterTranslationHandlers(m_bridge.get(), m_db.get(), m_jobs.get(), m_secrets.get(), m_translation.get());
    RegisterSearchHandlers(m_bridge.get(), m_db.get(), m_jobs.get(), m_search.get());

    // PB-010-B：本地词典 —— 启动优先加载磁盘缓存（解析 ECDICT 很慢）。
    {
        DictionaryRuntime& runtime = GetDictionaryRuntime();
        const std::string dictionaryDir = UserDataPath() + "/data/dictionary";
        const std::wstring wideDir = pb::Utf8ToWide(dictionaryDir);
        if (!wideDir.empty()) {
            std::error_code dirError;
            std::filesystem::create_directories(wideDir, dirError);
        }
        runtime.cachePath = dictionaryDir + "/dictionary.pbdict";
        runtime.service = std::make_unique<pb::DictionaryService>();
        std::string cacheError;
        runtime.service->LoadCache(runtime.cachePath, &cacheError);
        RegisterDictionaryHandlers(m_bridge.get(), runtime.service.get(), m_jobs.get(),
                                   runtime.cachePath);
    }

    RegisterZlibHandlers(m_bridge.get(), m_zlib.get());

    // UI language switch — called from frontend when the user changes language.
    // Prefix "app:" is NOT in the External whitelist, so external Z-Library
    // pages cannot invoke it (only the in-app settings page can).
    m_bridge->RegisterMethod("app:setLanguage", [](const json& p) {
        App::Instance().SetLanguage(p.value("lang", "zh"));
        return json(true);
    });

    // PB-019: renderer asks once on mount for paths that arrived before it was
    // ready; later paths are pushed via the app:openFiles event.
    m_bridge->RegisterMethod("app:getPendingOpenFiles", [](const json&) {
        return json(App::Instance().TakePendingOpenFiles());
    });

    // PB-016: local diagnostics export (logs + version + system info + settings).
    // No network/telemetry; returns the created directory or null on failure.
    // PB-012: open Settings > Default apps; we never write UserChoice ourselves.
    m_bridge->RegisterMethod("app:openDefaultApps", [](const json&) -> json {
        HINSTANCE r = ShellExecuteW(nullptr, L"open",
            L"ms-settings:defaultapps?registeredAppUser=ParticleBook", nullptr, nullptr, SW_SHOWNORMAL);
        if (reinterpret_cast<INT_PTR>(r) <= 32) {
            ShellExecuteW(nullptr, L"open", L"ms-settings:defaultapps", nullptr, nullptr, SW_SHOWNORMAL);
        }
        return json(nullptr);
    });

    m_bridge->RegisterMethod("app:exportDiagnostics", [this](const json&) -> json {
        if (!m_log) return json(nullptr);
        pb::DiagnosticsPackage pkg;
        std::string error;
        if (!m_log->ExportDiagnostics("", &pkg, &error)) {
            pb::LogError("diagnostics", "export failed: " + error);
            return json(nullptr);
        }
        pb::LogInfo("diagnostics", "exported to " + pkg.directory);
        return json(pkg.directory);
    });

    if (m_single) {
        m_single->StartServer([this](std::vector<std::string> paths) {
            OnExternalOpen(std::move(paths));
        });
    }
}

void App::Run()
{
    m_webview->CreateMainWindow(m_hInstance);
    m_webview->RunMessageLoop();
}

void App::Shutdown()
{
    pb::LogInfo("app", "shutdown begin");
    // Stop jobs first, then the UI queue, then the pipe server.
    if (m_tts) { m_tts->Shutdown(); m_tts.reset(); }
    if (m_jobs) m_jobs->Stop();
    if (m_webview) m_webview->StopDispatcher();
    if (m_single) m_single->Stop();
    if (m_db) m_db->FlushSync();
    m_webview.reset();
    m_bridge.reset();
    m_db.reset();
    pb::SetGlobalLogger(nullptr);
    if (m_log) { m_log->Stop(); m_log.reset(); }
}

std::string App::UserDataPath() const
{
    wchar_t appData[MAX_PATH];
    if (SUCCEEDED(SHGetFolderPathW(nullptr, CSIDL_APPDATA, nullptr, 0, appData))) {
        char buf[MAX_PATH * 3];
        WideCharToMultiByte(CP_UTF8, 0, appData, -1, buf, sizeof(buf), nullptr, nullptr);
        return std::string(buf) + "/particle-book";
    }
    return ".";
}

void App::OnExternalOpen(std::vector<std::string> paths)
{
    if (paths.empty()) return;
    {
        std::lock_guard<std::mutex> lock(m_openMutex);
        for (auto& p : paths) m_pendingOpenFiles.push_back(std::move(p));
    }
    // Pipe thread -> UI thread. If the window isn't up yet the paths stay
    // pending and the renderer picks them up via app:getPendingOpenFiles.
    if (m_webview) m_webview->PostOpenFilesNotification();
}

std::vector<std::string> App::TakePendingOpenFiles()
{
    std::lock_guard<std::mutex> lock(m_openMutex);
    m_rendererReady = true;
    auto paths = std::move(m_pendingOpenFiles);
    m_pendingOpenFiles.clear();
    return paths;
}

void App::FlushOpenPaths()
{
    std::vector<std::string> paths;
    {
        std::lock_guard<std::mutex> lock(m_openMutex);
        if (!m_rendererReady || m_pendingOpenFiles.empty()) return;
        paths.swap(m_pendingOpenFiles);
    }
    if (m_bridge) m_bridge->EmitEvent("app:openFiles", json(paths));
}

void App::SetLanguage(const std::string& lang)
{
    m_language = (lang == "en") ? "en" : "zh";
    if (!m_webview) return;
    // New injection only applies to documents created AFTER this call
    // (AddScriptToExecuteOnDocumentCreated semantics), so also refresh the
    // already-loaded Z-Library page's toolbar label.
    m_webview->InjectBridgeScript("window.__pbLang='" + m_language + "';");
    std::wstring js = L"if(window.__pbLang)window.__pbLang='" + pb::Utf8ToWide(m_language)
                    + L"';if(window.__pbRefreshToolbar)window.__pbRefreshToolbar();";
    m_webview->ExecuteScriptOnPage(js);
}
