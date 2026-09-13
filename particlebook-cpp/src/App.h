#pragma once
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <memory>
#include <string>

class DatabaseService;
class WebViewHost;
class BridgeServer;
class PdfService;
class BookSourceService;
class ZLibraryService;
class ContentCache;

class App {
public:
    static App& Instance();

    void Init(HINSTANCE hInstance);
    void Run();
    void Shutdown();

    DatabaseService* DB() const { return m_db.get(); }
    BridgeServer* Bridge() const { return m_bridge.get(); }
    WebViewHost* WebView() const { return m_webview.get(); }
    // 供后台任务（Z-Library 线路探测）拿 shared_ptr 保命：线程要多活一会儿时
    // 不能捕获裸 this（服务是 shared_ptr 持有的）。
    std::shared_ptr<ZLibraryService> Zlib() const { return m_zlib; }

    std::string UserDataPath() const;
    std::string GetLanguage() const { return m_language; }
    void SetLanguage(const std::string& lang);

private:
    App() = default;
    HINSTANCE m_hInstance = nullptr;

    std::string m_language = "zh";

    std::shared_ptr<DatabaseService> m_db;
    std::shared_ptr<BridgeServer> m_bridge;
    std::unique_ptr<WebViewHost> m_webview;
    std::unique_ptr<PdfService> m_pdf;
    std::shared_ptr<BookSourceService> m_bookSource;
    std::shared_ptr<ZLibraryService> m_zlib;
    std::unique_ptr<ContentCache> m_cache;
};
