#include "BridgeServer.h"
#include "WebViewHost.h"
#include "services/ZLibraryMirror.h"   // SEC-01/02：zlib 主机判据（与镜像过滤、证书放行同源）
#include <mutex>
#include <condition_variable>
#include <thread>
#include <chrono>
#include <fstream>
#include <sstream>
#include <filesystem>
#include <ctime>
#include <unordered_set>

BridgeServer::~BridgeServer()
{
    // B-09：先让令牌失效，再让成员销毁 —— 排队中的投递 lambda 看到 false 就直接返回，
    // 不会去解引用已经析构的 this（App::Shutdown 的顺序是 StopDispatcher → reset bridge，
    // 队列里仍可能压着未执行的 EmitEvent/SendResponse 回调）。
    if (m_alive) m_alive->store(false, std::memory_order_release);
}

void BridgeServer::RegisterMethod(const std::string& name, MethodHandler handler)
{
    m_methods[name] = std::move(handler);
}

void BridgeServer::RegisterAsyncMethod(const std::string& name, AsyncMethodHandler handler)
{
    m_asyncMethods[name] = std::move(handler);
}

namespace {
    // Origin classification for incoming web messages.
    //
    // The bridge script (window.electronAPI) is injected into EVERY document via
    // AddScriptToExecuteOnDocumentCreated — including external Z-Library mirror
    // pages and untrusted in-app documents (e.g. EPUB iframes). Without source
    // checks, any such page could call privileged methods (file:read, db:*,
    // app:downloadUpdate + quitAndInstall → arbitrary code execution).
    //
    // 2026-10-29 review 之后的规则（SEC-02）：来源分三档 ——
    //   App      应用页（虚拟主机 http://particlebook.app）；
    //   External 其它 http(s) 页面，递归再分「host 属于 Z-Library 域名族」与否
    //            （pb::zlib::IsTrustedHost，与镜像过滤/证书放行同一份判据）；
    //   Unknown  blob:/data:/about:/file:（EPUB iframe 等）→ 一律拒绝。
    // 只有 App 与「可信 External」能调用工具栏白名单方法、以及 raw 消息
    // （zlibNavigate/Close/Logout/SwitchTo）。下载与自动导入不走这一层
    // （WebView2 下载事件 + C++ 侧 InvokeMethod），不受影响。
    enum class MsgOrigin { App, External, Unknown };

    MsgOrigin ClassifyOrigin(const std::string& source) {
        // App page (virtual host) — exact prefix so particlebook.app.evil.com
        // doesn't match.
        if (source == "http://particlebook.app" ||
            source.rfind("http://particlebook.app/", 0) == 0) return MsgOrigin::App;
        // Any other http(s) origin (Z-Library mirrors / transit domains).
        if (source.rfind("http://", 0) == 0 || source.rfind("https://", 0) == 0)
            return MsgOrigin::External;
        // blob: / data: / about: / file: etc. (e.g. injected EPUB iframe) → reject.
        return MsgOrigin::Unknown;
    }

    // Methods an external (Z-Library mirror) page may invoke. Kept minimal: the
    // injected floating toolbar needs mirror info + navigation only. Deliberately
    // excludes zlib:getURL / zlib:getDownloadPath (leak the current URL and the
    // local download directory to any untrusted mirror page).
    //
    // zlib:fetchMirrors 与 zlib:logout 也一并移除：工具栏根本不会调用它们（只调
    // getMirrorInfo / switchMirror），而 fetchMirrors 是【同步网络请求】且跑在
    // WebView2 UI 线程上 —— 任何被加载的页面循环 invoke 就能让窗口"未响应"并反复
    // 重写镜像表。logout 需要时再作为显式功能加回来（当前没有 UI 入口）。
    const std::unordered_set<std::string>& ExternalAllowedMethods() {
        static const std::unordered_set<std::string> s = {
            "zlib:getMirrorInfo", "zlib:switchMirror", "zlib:navigate",
        };
        return s;
    }

    bool IsAllowedForExternal(const std::string& method) {
        return ExternalAllowedMethods().count(method) > 0;
    }

    // ── SEC-02 / SEC-03 共用的「文档是否可信」判据 ─────────────────────
    // 只有两种文档可信：应用页（虚拟主机）与 Z-Library 域名族（镜像/中转页）。
    bool IsTrustedZlibSource(const std::string& source) {
        return pb::zlib::IsTrustedHost(pb::zlib::HostFromUrl(source));
    }

    // 只带本地信息、或只对应用有意义的事件：外部页面（镜像/任意站点）都不该收到。
    const std::unordered_set<std::string>& AppOnlyEvents() {
        static const std::unordered_set<std::string> s = {
            "app:openFiles",              // 本地绝对路径列表
            "app:updateChecked",
            "app:updateDownloaded",       // 安装包落地路径
            "app:updateError",
            "app:downloadProgress",
            "book:importProgress",        // 导入进度 / 文件名
            "bookSource:downloadProgress",
        };
        return s;
    }

    bool IsAppOnlyEvent(const std::string& event) {
        return AppOnlyEvents().count(event) > 0;
    }

    // 投给外部页面时把「本地路径」字段剥掉（例：zlib:downloadComplete 的 path）。
    // 注入的下载卡片只用 fileName，剥掉 path 不影响它的显示。
    void StripLocalPathFields(json& value) {
        static const char* const kLocalPathKeys[] = {
            "path", "filePath", "file_path", "dir", "directory", "folder",
            "sourcePath", "downloadPath",
        };
        if (value.is_object()) {
            for (const char* key : kLocalPathKeys) value.erase(key);
            for (auto it = value.begin(); it != value.end(); ++it) StripLocalPathFields(it.value());
        } else if (value.is_array()) {
            for (auto& element : value) StripLocalPathFields(element);
        }
    }

    // 应用页 URI 判定（宽字符，事件投递时不必再转 UTF-8）。口径与 ClassifyOrigin 一致。
    bool IsAppDocumentUri(const std::wstring& uri) {
        static const wchar_t kPrefix[] = L"http://particlebook.app";
        const std::size_t prefixLen = (sizeof(kPrefix) / sizeof(kPrefix[0])) - 1;
        if (uri.size() < prefixLen) return false;
        if (uri.compare(0, prefixLen, kPrefix) != 0) return false;
        return uri.size() == prefixLen || uri[prefixLen] == L'/';
    }

    // 主 WebView 当前顶层文档 URI（必须在 UI 线程调用）；取不到返回空串。
    std::wstring CurrentDocumentUri(WebViewHost* host) {
        if (!host) return std::wstring();
        ICoreWebView2* webview = host->GetWebView();
        if (!webview) return std::wstring();
        LPWSTR raw = nullptr;
        std::wstring uri;
        if (SUCCEEDED(webview->get_Source(&raw)) && raw) {
            uri.assign(raw);
            CoTaskMemFree(raw);
        }
        return uri;
    }
}

void BridgeServer::HandleMessage(const std::string& rawJson, const std::string& source)
{
    try {
        auto msg = json::parse(rawJson);
        std::string type = msg.value("type", "");
        MsgOrigin origin = ClassifyOrigin(source);
        // SEC-02：能操作 Z-Library 会话的文档只有两种 —— 应用页，或 host 命中
        // Z-Library 域名族的页面。旧行为只挡 Unknown，于是任何被加载的 http(s)
        // 页面（镜像里的第三方 iframe、被劫持的跳转页）都能 zlibSwitchTo / zlibLogout。
        const bool appOrigin = (origin == MsgOrigin::App);
        const bool zlibOrigin = (origin == MsgOrigin::External) && IsTrustedZlibSource(source);
        const bool mayControlZlib = appOrigin || zlibOrigin;

        if (type == "invoke") {
            int id = msg.value("id", 0);
            std::string method = msg.value("method", "");
            json params = msg.value("params", json::object());

            // Unknown-scheme sources (untrusted iframes) get nothing —— 但要回一个
            // 错误包：静默丢弃会让注入脚本的 invoke() promise 永久挂起（渲染层每次
            // 被拒都留一条永不回收的 pending 记录），"被拒"与"方法不存在"也无法区分。
            if (origin == MsgOrigin::Unknown) {
                SendResponse(id, json(nullptr), "forbidden_origin");
                return;
            }
            // 外部页面只能调工具栏白名单，且其 host 必须属于 Z-Library 域名族。
            if (origin == MsgOrigin::External && !(zlibOrigin && IsAllowedForExternal(method))) {
                SendResponse(id, json(nullptr), "forbidden_origin");
                return;
            }

            ProcessInvoke(id, method, params);
        } else if (type == "reload") {
            // Only the app page may reload the whole WebView.
            if (origin != MsgOrigin::App) return;
            if (m_webview && m_webview->GetWebView()) {
                m_webview->GetWebView()->ExecuteScript(L"location.reload()", nullptr);
            }
        } else if (type == "zlibNavigate") {
            if (!mayControlZlib) return;   // SEC-02
            std::string action = msg.value("action", "");
            if (action == "back" && m_webview && m_webview->GetWebView()) {
                BOOL can = FALSE; m_webview->GetWebView()->get_CanGoBack(&can);
                if (can) m_webview->GetWebView()->GoBack();
            } else if (action == "forward" && m_webview && m_webview->GetWebView()) {
                BOOL can = FALSE; m_webview->GetWebView()->get_CanGoForward(&can);
                if (can) m_webview->GetWebView()->GoForward();
            } else if (action == "reload" && m_webview && m_webview->GetWebView()) {
                m_webview->GetWebView()->Reload();
            }
        } else if (type == "zlibClose") {
            if (!mayControlZlib) return;   // SEC-02
            auto it = m_methods.find("zlib:hide");
            if (it != m_methods.end()) {
                try { it->second(json::object()); } catch (...) {}
            }
        } else if (type == "zlibLogout") {
            if (!mayControlZlib) return;   // SEC-02
            auto it = m_methods.find("zlib:logout");
            if (it != m_methods.end()) {
                try { it->second(json::object()); } catch (...) {}
            }
        } else if (type == "zlibSwitchTo") {
            if (!mayControlZlib) return;   // SEC-02
            auto it = m_methods.find("zlib:switchMirror");
            if (it != m_methods.end()) {
                int idx = msg.value("index", 0);
                json params = json::object();
                params["index"] = idx;
                try { it->second(params); } catch (...) {}
            }
        } else if (type == "subscribe") {
            if (origin != MsgOrigin::App) return;
            ProcessSubscribe(msg.value("event", ""));
        } else if (type == "unsubscribe") {
            if (origin != MsgOrigin::App) return;
            ProcessUnsubscribe(msg.value("event", ""));
        }
    } catch (const std::exception& e) {
        // Malformed message, ignore
    }
}

void BridgeServer::EmitEvent(const std::string& event, const json& data)
{
    if (!m_webview) return;

    // SEC-03：事件不再无条件投给「当前文档」。Z-Library 会话期间当前文档是外部
    // 镜像页，旧实现会把 app:openFiles（本地绝对路径）、zlib:downloadComplete 的
    // 落地 path 一起送给它。现在：
    //   * AppOnlyEvents → 只投应用页；
    //   * 其余（注入的下载卡片要用的 zlib:* 等）→ 外部页面也投，但剥掉本地路径字段。
    // "当前文档是不是应用页"只能读主 WebView 的 Source，必须在 UI 线程问，
    // 所以判断放进 PostToUiThread 的 lambda 里（不再预先 dump 成字符串）。
    // B-09：lambda 里不要裸用 this —— 捕获活体令牌 + 当下的 host 指针，令牌失效即丢弃。
    const std::shared_ptr<std::atomic<bool>> alive = m_alive;
    WebViewHost* const host = m_webview;
    if (!alive) return;
    host->PostToUiThread([alive, host, event, data]() {
        if (!alive->load(std::memory_order_acquire) || host == nullptr) return;
        const std::wstring uri = CurrentDocumentUri(host);
        // 读不到 URI（WebView 已销毁/尚未创建）时保持旧行为，避免启动早期丢事件。
        const bool appDocument = uri.empty() || IsAppDocumentUri(uri);
        if (!appDocument && IsAppOnlyEvent(event)) return;
        json payload = data;
        if (!appDocument) StripLocalPathFields(payload);
        json msg = {
            {"type", "event"},
            {"event", event},
            {"data", payload}
        };
        host->PostMessageToRenderer(msg.dump());
    });
}

json BridgeServer::InvokeMethod(const std::string& method, const json& params)
{
    auto it = m_methods.find(method);
    if (it != m_methods.end()) {
        try {
            return it->second(params);
        } catch (const std::exception&) {
            return json(nullptr);
        }
    }

    // PB-018-A BUGFIX：同步调用方（Z-Library / 书源下载线程）会请求只注册了 async
    // 的方法（book:import 自 PB-020 起是 RegisterAsyncMethod）。此前这里只查同步表
    // → 直接返回 nullptr，导致下载成功但导入永远 import_failed。
    // 现在把 async handler 以「等完成」方式跑一遍：respond/reject 由 JobService
    // 在 UI 线程回调，而调用点都在下载 worker 线程（UI 线程仍在跑消息循环），
    // 所以不会死锁。注意：本方法不要在 UI 线程上调用 async 方法。
    auto asyncIt = m_asyncMethods.find(method);
    if (asyncIt == m_asyncMethods.end()) return json(nullptr);

    std::mutex mutex;
    std::condition_variable cv;
    bool done = false;
    json result = nullptr;
    std::string error;
    try {
        asyncIt->second(params,
            [&](json value) {
                {
                    std::lock_guard<std::mutex> lk(mutex);
                    result = std::move(value);
                    done = true;
                }
                cv.notify_one();
            },
            [&](std::string message) {
                {
                    std::lock_guard<std::mutex> lk(mutex);
                    error = std::move(message);
                    done = true;
                }
                cv.notify_one();
            });
    } catch (const std::exception&) {
        return json(nullptr);
    }

    std::unique_lock<std::mutex> lk(mutex);
    const bool finished = cv.wait_for(lk, std::chrono::minutes(5), [&] { return done; });
    if (!finished || !error.empty()) return json(nullptr);
    return result;
}

void BridgeServer::ProcessInvoke(int id, const std::string& method, const json& params)
{

    auto asyncIt = m_asyncMethods.find(method);
    if (asyncIt != m_asyncMethods.end()) {
        // B-09：异步 handler 的 respond/reject 可能在被 JobService 延迟调用时对象已经析构
        const std::shared_ptr<std::atomic<bool>> alive = m_alive;
        auto respond = [this, alive, id](json result) {
            if (!alive || !alive->load(std::memory_order_acquire)) return;
            SendResponse(id, result, "");
        };
        auto reject = [this, alive, id](std::string error) {
            if (!alive || !alive->load(std::memory_order_acquire)) return;
            SendResponse(id, json(nullptr), error);
        };
        try { asyncIt->second(params, respond, reject); }
        catch (const std::exception& e) { reject(e.what()); }
        catch (...) { reject("unknown error"); }
        return;
    }

    json response = {
        {"type", "response"},
        {"id", id}
    };

    auto it = m_methods.find(method);
    if (it != m_methods.end()) {
        try {
            response["result"] = it->second(params);
        } catch (const std::exception& e) {
            response["error"] = e.what();
        }
    } else {
        response["error"] = "Method not found: " + method;
    }

    if (m_webview) {
        m_webview->PostMessageToRenderer(response.dump());
    }
}

void BridgeServer::SendResponse(int id, const json& result, const std::string& error)
{
    if (!m_webview) return;
    json response = {{ "type", "response" }, { "id", id }};
    if (error.empty()) response["result"] = result;
    else response["error"] = error;
    const std::string payload = response.dump();
    // B-09：同 EmitEvent —— 令牌 + host 快照，令牌失效就不投递。
    const std::shared_ptr<std::atomic<bool>> alive = m_alive;
    WebViewHost* const host = m_webview;
    if (!alive) return;
    host->PostToUiThread([alive, host, payload]() {
        if (!alive->load(std::memory_order_acquire) || host == nullptr) return;
        host->PostMessageToRenderer(payload);
    });
}

void BridgeServer::ProcessSubscribe(const std::string& event) {}
void BridgeServer::ProcessUnsubscribe(const std::string& event) {}

// ── Bridge Injection Script ─────────────────────────────────────

std::string BridgeServer::GenerateBridgeScript()
{
    return R"SCRIPT(
(function() {
  const pending = new Map();
  let reqId = 0;
  const eventListeners = new Map();

  window.chrome.webview.addEventListener('message', function(e) {
    try {
      var msg = typeof e.data === 'string' ? JSON.parse(e.data) : e.data;
    } catch (_) { return; }

    if (msg.type === 'response') {
      var p = pending.get(msg.id);
      if (!p) return;
      pending.delete(msg.id);
      var method = p.method;
      var resolve = p.resolve;
      var reject = p.reject;
      var result = msg.result;
      var error = msg.error;
      setTimeout(function() {
        if (error) { reject(new Error(error)); }
        else { resolve(result); }
      }, 0);
      if ((method === 'db:deleteBook' || method === 'book:import') && window.__refreshLibrary) {
        setTimeout(function(){ if (typeof window.__refreshLibrary === 'function') window.__refreshLibrary(); }, 50);
      }
    } else if (msg.type === 'event') {
      var listeners = eventListeners.get(msg.event);
      if (listeners) { listeners.forEach(function(fn) { fn(msg.data); }); }
    }
  });

  function invoke(method, params) {
    return new Promise(function(resolve, reject) {
      var id = ++reqId;
      pending.set(id, {resolve: resolve, reject: reject, method: method});
      window.chrome.webview.postMessage(JSON.stringify({
        type: 'invoke', id: id, method: method, params: params
      }));
    });
  }

  function onEvent(event, callback) {
    if (!eventListeners.has(event)) eventListeners.set(event, new Set());
    eventListeners.get(event).add(callback);
    window.chrome.webview.postMessage(JSON.stringify({type:'subscribe',event:event}));
    return function() {
      var s = eventListeners.get(event);
      if (s) { s.delete(callback); if (s.size === 0) eventListeners.delete(event); }
      window.chrome.webview.postMessage(JSON.stringify({type:'unsubscribe',event:event}));
    };
  }

  function noop() { return Promise.resolve(null); }

  window.electronAPI = {
    openFile: function()           { return invoke('dialog:openFile'); },
    readFile: async function(path) { var r = await invoke('file:read', {path:path}); if (r && r._pb_url) { var resp = await fetch(r._pb_url); var buf = await resp.arrayBuffer(); return new Uint8Array(buf); } return r; },
    importBooks: function(p)       { return invoke('book:import', {paths:p}); },
    writeDroppedFile: function(name, dataB64) { return invoke('book:writeDroppedFile', {name:name, data:dataB64}); },
    getCoverImage: function(id)    { return invoke('book:cover', {id:id}); },

    getBooks: function()       { return invoke('db:getBooks'); },
    getBook: function(id)      { return invoke('db:getBook', {id:id}); },
    deleteBook: function(id)   { return invoke('db:deleteBook', {id:id}); },
    updateBookMetadata: function(id, patch) { return invoke('book:updateMetadata', {id:id, patch:patch||{}}); },
    getLibraryTags: function()   { return invoke('library:getTags'); },
    getLibrarySeries: function() { return invoke('library:getSeries'); },
    updateReadingProgress: function(bid, prog) { return invoke('db:updateProgress', {bookId:bid, progress:prog}); },
    getReadingProgress: function(bid)          { return invoke('db:getProgress', {bookId:bid}); },

    getDownloads: function()   { return invoke('db:getDownloads'); },
    deleteDownload: function(id, delFile) { return invoke('db:deleteDownload', {id:id, deleteFile:!!delFile}); },
    revealPath: function(path) { return invoke('app:revealPath', {path:path}); },

    getBookmarks: function(bid)      { return invoke('db:getBookmarks', {bookId:bid}); },
    addBookmark: function(bm)        { return invoke('db:addBookmark', {bm:bm}); },
    deleteBookmark: function(id)     { return invoke('db:deleteBookmark', {id:id}); },
    updateBookmarkTitle: function(id,t){ return invoke('db:updateBookmarkTitle', {id:id, title:t}); },

    getHighlights: function(bid)   { return invoke('db:getHighlights', {bookId:bid}); },
    addHighlight: function(hl)     { return invoke('db:addHighlight', {hl:hl}); },
    deleteHighlight: function(id)  { return invoke('db:deleteHighlight', {id:id}); },

    getNotes: function(bid)    { return invoke('db:getNotes', {bookId:bid}); },
    addNote: function(n)       { return invoke('db:addNote', {note:n}); },
    updateNote: function(id,c) { return invoke('db:updateNote', {id:id, content:c}); },
    deleteNote: function(id)   { return invoke('db:deleteNote', {id:id}); },

    getAnnotations: function(bid) { return invoke('annotations:list', {bookId:bid===undefined?0:bid}); },
    addAnnotation: function(a)    { return invoke('annotations:add', {annotation:a}); },
    updateAnnotation: function(id, patch) { return invoke('annotations:update', {id:id, patch:patch||{}}); },
    deleteAnnotation: function(id) { return invoke('annotations:delete', {id:id}); },

    getSettings: function()           { return invoke('db:getSettings'); },
    updateSettings: function(s)       { return invoke('db:updateSettings', {settings:s}); },
    getBookSettings: function(bid)    { return invoke('db:getBookSettings', {bookId:bid}); },
    updateBookSettings: function(bid,s){ return invoke('db:updateBookSettings', {bookId:bid, settings:s}); },

    getBookshelves: function()              { return invoke('db:getBookshelves'); },
    addBookshelf: function(name)            { return invoke('db:addBookshelf', {name:name}); },
    deleteBookshelf: function(id)           { return invoke('db:deleteBookshelf', {id:id}); },
    renameBookshelf: function(id, name)     { return invoke('db:renameBookshelf', {id:id, name:name}); },
    getBooksInShelf: function(sid)          { return invoke('db:getBooksInShelf', {shelfId:sid}); },
    addBookToShelf: function(sid, bid)      { return invoke('db:addBookToShelf', {shelfId:sid, bookId:bid}); },
    removeBookFromShelf: function(sid, bid) { return invoke('db:removeBookFromShelf', {shelfId:sid, bookId:bid}); },

    startReadingSession: function(bid)          { return invoke('db:startReadingSession', {bookId:bid}); },
    endReadingSession: function(sid)            { return invoke('db:endReadingSession', {sessionId:sid}); },
    updateReadingSessionDuration: function(sid,d){ return invoke('db:updateReadingSessionDuration', {sessionId:sid, duration:d}); },
    getAllReadingTime: function()               { return invoke('db:getAllReadingTime'); },
    getAllReadingProgress: function()           { return invoke('db:getAllReadingProgress'); },

    getBookSources: function()       { return invoke('bookSource:getAll'); },
    getBookSource: function(id)      { return invoke('bookSource:get', {id:id}); },
    insertBookSource: function(s)    { return invoke('bookSource:insert', {source:s}); },
    updateBookSource: function(id,u) { return invoke('bookSource:update', {id:id, updates:u}); },
    deleteBookSource: function(id)   { return invoke('bookSource:delete', {id:id}); },
    toggleBookSource: function(id)   { return invoke('bookSource:toggle', {id:id}); },
    clearAllBookSources: function()  { return invoke('bookSource:clearAll'); },
    importBookSources: function()    { return invoke('bookSource:importFile'); },
    searchBooks: function(kw,p)      { return invoke('bookSource:search', {keyword:kw, page:p}); },
    searchBooksFromSource: function(sid,kw,p) { return invoke('bookSource:searchOne', {sourceId:sid, keyword:kw, page:p}); },
    getBookInfoFromSource: function(sid,u)    { return invoke('bookSource:getBookInfo', {sourceId:sid, bookUrl:u}); },
    getChapterListFromSource: function(sid,u) { return invoke('bookSource:getChapterList', {sourceId:sid, tocUrl:u}); },
    downloadBook: function(sid,u,n,f)         { return invoke('bookSource:download', {sourceId:sid, bookUrl:u, bookName:n, format:f}); },
    onDownloadProgress: function(cb) { return onEvent('bookSource:downloadProgress', cb); },

    zlibHide: function()      { return invoke('zlib:hide'); },
    zlibShow: function()      { return invoke('zlib:show'); },
    zlibGetMirrorInfo: function(){ return invoke('zlib:getMirrorInfo'); },
    zlibGetDownloadPath: function()  { return invoke('zlib:getDownloadPath'); },
    zlibPickDownloadFolder: function() { return invoke('zlib:pickDownloadFolder'); },
    onZlibMirrorChanged: function(cb)    { return onEvent('zlib:mirrorChanged', cb); },
    onZlibAllMirrorsFailed: function(cb) { return onEvent('zlib:allMirrorsFailed', cb); },
    onZlibDownloadError: function(cb)   { return onEvent('zlib:downloadError', cb); },

    onLibraryChanged: function(cb) { return onEvent('library:changed', cb); },
    onImportProgress: function(cb) { return onEvent('book:importProgress', cb); },
    getPendingOpenFiles: function() { return invoke('app:getPendingOpenFiles'); },
    exportDiagnostics: function() { return invoke('app:exportDiagnostics'); },
    exportAnnotations: function(bookId, format, options) { return invoke('export:annotations', {bookId:bookId||0, format:format||'markdown', options:options||{}}); },
    openDefaultApps: function() { return invoke('app:openDefaultApps'); },
    getReadingSessions: function() { return invoke('stats:getReadingSessions'); },
    getSettingsSchema: function() { return invoke('settings:schema'); },
    searchBuild: function() { return invoke('search:build'); },
    searchQuery: function(query, maxHits) { return invoke('search:query', {query:query||'', maxHits:maxHits||50}); },
    searchStatus: function() { return invoke('search:status'); },
    translationSetKey: function(key) { return invoke('translation:setKey', {key:key||''}); },
    translationHasKey: function() { return invoke('translation:hasKey'); },
    translationListModels: function() { return invoke('translation:listModels'); },
    translationClearKey: function() { return invoke('translation:clearKey'); },
    translationTranslate: function(segments, sourceLang, targetLang, context) {
      return invoke('translation:translate', {segments:segments||[], sourceLang:sourceLang||'auto', targetLang:targetLang||'zh', context:context||''});
    },
    getTtsVoices: function() { return invoke('tts:voices'); },
    ttsSpeak: function(text, tag, flush) { return invoke('tts:speak', {text:text||'', tag:tag||'', flush:flush===undefined?true:!!flush}); },
    ttsPause: function() { return invoke('tts:pause'); },
    ttsResume: function() { return invoke('tts:resume'); },
    ttsStop: function() { return invoke('tts:stop'); },
    ttsStatus: function() { return invoke('tts:status'); },
    ttsSetVoice: function(voiceId) { return invoke('tts:setVoice', {voiceId:voiceId||''}); },
    ttsSetRate: function(rate) { return invoke('tts:setRate', {rate:rate||0}); },
    ttsSetVolume: function(volume) { return invoke('tts:setVolume', {volume:volume===undefined?100:volume}); },
    onTtsEvent: function(cb) { return onEvent('tts:event', cb); },
    createBackup: function() { return invoke('backup:create'); },
    onBackupProgress: function(cb) { return onEvent('backup:progress', cb); },
    restoreBackup: function(path) { return invoke('backup:restore', {path:path||''}); },
    dictionaryImport: function(path) { return invoke('dictionary:import', {path:path||''}); },
    onDictionaryProgress: function(cb) { return onEvent('dictionary:progress', cb); },
    dictionaryLookup: function(word, options) { return invoke('dictionary:lookup', Object.assign({word:word}, options||{})); },
    dictionaryStatus: function() { return invoke('dictionary:status'); },
    dictionarySetEnabled: function(enabled) { return invoke('dictionary:setEnabled', {enabled:!!enabled}); },
    dictionaryClear: function() { return invoke('dictionary:clear'); },
    onOpenFiles: function(cb) { return onEvent('app:openFiles', cb); },

    checkUpdate: function()    { return invoke('app:checkUpdate'); },
    getAppVersion: function()  { return invoke('app:getVersion'); },
    setLanguage: function(lang){ return invoke('app:setLanguage', {lang:lang||'zh'}); },
    downloadUpdate: function(url, sha512) { return invoke('app:downloadUpdate', {url:url||'', sha512:sha512||''}); },
    quitAndInstall: function() { return invoke('app:quitAndInstall'); },
    onUpdateChecked: function(cb)          { return onEvent('app:updateChecked', cb); },
    onUpdateDownloaded: function(cb)       { return onEvent('app:updateDownloaded', cb); },
    onUpdateError: function(cb)            { return onEvent('app:updateError', cb); },
    onUpdateDownloadProgress: function(cb) { return onEvent('app:downloadProgress', cb); },

    pdfOpen: function(path)    { return invoke('pdf:open', {filePath:path}); },
    pdfTextLayer: function(id,pageNum) { return invoke('pdf:textLayer', {id:id, pageNum:pageNum||0}); },
    pdfRenderPage: function(id,p,w,h) { return invoke('pdf:renderPage', {id:id, pageNum:p, width:w, height:h}); },
    pdfExtractText: function(id) { return invoke('pdf:extractText', {id:id}); },
    pdfClose: function(id)    { return invoke('pdf:close', {id:id}); },

    getFilePath: function(file) {
      if (file && file.path) return file.path;
      if (file && file.name && window._droppedFiles) {
        var found = window._droppedFiles.find(function(f) { return f.name === file.name; });
        if (found) return found.path;
      }
      return '';
    }
  };
})();
)SCRIPT";
}
