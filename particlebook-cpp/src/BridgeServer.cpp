#include "BridgeServer.h"
#include "WebViewHost.h"
#include <mutex>
#include <thread>
#include <chrono>
#include <fstream>
#include <sstream>
#include <filesystem>
#include <ctime>

void BridgeServer::RegisterMethod(const std::string& name, MethodHandler handler)
{
    m_methods[name] = std::move(handler);
}

void BridgeServer::HandleMessage(const std::string& rawJson)
{
    try {
        auto msg = json::parse(rawJson);
        std::string type = msg.value("type", "");

        if (type == "invoke") {
            int id = msg.value("id", 0);
            std::string method = msg.value("method", "");
            json params = msg.value("params", json::object());
            ProcessInvoke(id, method, params);
        } else if (type == "reload") {
            if (m_webview && m_webview->GetWebView()) {
                m_webview->GetWebView()->ExecuteScript(L"location.reload()", nullptr);
            }
        } else if (type == "zlibNavigate") {
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
            auto it = m_methods.find("zlib:hide");
            if (it != m_methods.end()) {
                try { it->second(json::object()); } catch (...) {}
            }
        } else if (type == "zlibShowMirror") {
            auto it = m_methods.find("zlib:showMirrorMenu");
            if (it != m_methods.end()) {
                try { it->second(json::object()); } catch (...) {}
            }
        } else if (type == "zlibSwitchTo") {
            auto it = m_methods.find("zlib:switchMirror");
            if (it != m_methods.end()) {
                int idx = msg.value("index", 0);
                json params = json::object();
                params["index"] = idx;
                try { it->second(params); } catch (...) {}
            }
        } else if (type == "subscribe") {
            ProcessSubscribe(msg.value("event", ""));
        } else if (type == "unsubscribe") {
            ProcessUnsubscribe(msg.value("event", ""));
        }
    } catch (const std::exception& e) {
        // Malformed message, ignore
    }
}

void BridgeServer::EmitEvent(const std::string& event, const json& data)
{
    if (!m_webview) return;

    json msg = {
        {"type", "event"},
        {"event", event},
        {"data", data}
    };
    m_webview->PostMessageToRenderer(msg.dump());
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
    return json(nullptr);
}

void BridgeServer::ProcessInvoke(int id, const std::string& method, const json& params)
{
    m_pendingReload = false;

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

    if (m_pendingReload && m_webview) {
        std::thread([host = m_webview]() {
            std::this_thread::sleep_for(std::chrono::milliseconds(500));
            host->ReloadPage();
        }).detach();
        m_pendingReload = false;
    }
}

void BridgeServer::ScheduleReload(int delayMs)
{
    m_pendingReload = true;
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
        setTimeout(function(){ window.__refreshLibrary(); }, 50);
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
    openDirectory: function()      { return invoke('dialog:openDirectory'); },
    readFile: async function(path) { var r = await invoke('file:read', {path:path}); if (r && r._pb_url) { var resp = await fetch(r._pb_url); var buf = await resp.arrayBuffer(); return new Uint8Array(buf); } return r; },
    getBookMetadata: function(p)   { return invoke('book:metadata', {path:p}); },
    importBooks: function(p)       { return invoke('book:import', {paths:p}); },
    getCoverImage: function(id)    { return invoke('book:cover', {id:id}); },

    getBooks: function()       { return invoke('db:getBooks'); },
    getBook: function(id)      { return invoke('db:getBook', {id:id}); },
    deleteBook: function(id)   { return invoke('db:deleteBook', {id:id}); },
    updateReadingProgress: function(bid, prog) { return invoke('db:updateProgress', {bookId:bid, progress:prog}); },
    getReadingProgress: function(bid)          { return invoke('db:getProgress', {bookId:bid}); },

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

    getSettings: function()           { return invoke('db:getSettings'); },
    updateSettings: function(s)       { return invoke('db:updateSettings', {settings:s}); },
    getBookSettings: function(bid)    { return invoke('db:getBookSettings', {bookId:bid}); },
    updateBookSettings: function(bid,s){ return invoke('db:updateBookSettings', {bookId:bid, settings:s}); },
    deleteBookSettings: function(bid) { return invoke('db:deleteBookSettings', {bookId:bid}); },

    getBookshelves: function()              { return invoke('db:getBookshelves'); },
    addBookshelf: function(name)            { return invoke('db:addBookshelf', {name:name}); },
    deleteBookshelf: function(id)           { return invoke('db:deleteBookshelf', {id:id}); },
    renameBookshelf: function(id, name)     { return invoke('db:renameBookshelf', {id:id, name:name}); },
    getBooksInShelf: function(sid)          { return invoke('db:getBooksInShelf', {shelfId:sid}); },
    addBookToShelf: function(sid, bid)      { return invoke('db:addBookToShelf', {shelfId:sid, bookId:bid}); },
    removeBookFromShelf: function(sid, bid) { return invoke('db:removeBookFromShelf', {shelfId:sid, bookId:bid}); },
    getShelvesForBook: function(bid)        { return invoke('db:getShelvesForBook', {bookId:bid}); },

    startReadingSession: function(bid)          { return invoke('db:startReadingSession', {bookId:bid}); },
    endReadingSession: function(sid)            { return invoke('db:endReadingSession', {sessionId:sid}); },
    updateReadingSessionDuration: function(sid,d){ return invoke('db:updateReadingSessionDuration', {sessionId:sid, duration:d}); },
    getReadingTime: function(bid)               { return invoke('db:getReadingTime', {bookId:bid}); },
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

    zlibShow: function()      { return invoke('zlib:show'); },
    zlibHide: function()      { return invoke('zlib:hide'); },
    zlibNavigate: function(a)  { return invoke('zlib:navigate', {action:a}); },
    zlibGetURL: function()    { return invoke('zlib:getURL'); },
    zlibSetBounds: function(b){ return invoke('zlib:setBounds', {bounds:b}); },
    zlibLogout: function()    { return invoke('zlib:logout'); },
    zlibSwitchMirror: function(i){ return invoke('zlib:switchMirror', {index:i}); },
    zlibGetMirrorInfo: function(){ return invoke('zlib:getMirrorInfo'); },
    zlibShowMirrorMenu: function(){ return invoke('zlib:showMirrorMenu'); },
    zlibSetDownloadPath: function(p) { return invoke('zlib:setDownloadPath', {path:p}); },
    zlibGetDownloadPath: function()  { return invoke('zlib:getDownloadPath'); },
    zlibPickDownloadFolder: function() { return invoke('zlib:pickDownloadFolder'); },
    onZlibDownloadProgress: function(cb) { return onEvent('zlib:downloadProgress', cb); },
    onZlibDownloadComplete: function(cb) { return onEvent('zlib:downloadComplete', cb); },
    onZlibImportComplete: function(cb)   { return onEvent('zlib:importComplete', cb); },
    onZlibImportError: function(cb)      { return onEvent('zlib:importError', cb); },
    onZlibUrlChanged: function(cb)       { return onEvent('zlib:urlChanged', cb); },
    onZlibTitleChanged: function(cb)     { return onEvent('zlib:titleChanged', cb); },
    onZlibMirrorChanged: function(cb)    { return onEvent('zlib:mirrorChanged', cb); },

    onMenuImportBooks: function(cb) { return onEvent('menu:importBooks', cb); },
    onMenuShowAbout: function(cb)   { return onEvent('menu:showAbout', cb); },

    checkUpdate: function()    { return invoke('app:checkUpdate'); },
    getAppVersion: function()  { return invoke('app:getVersion'); },
    downloadUpdate: function(url) { return invoke('app:downloadUpdate', {url:url||''}); },
    quitAndInstall: function() { return invoke('app:quitAndInstall'); },
    onUpdateAvailable: function(cb)        { return onEvent('app:updateAvailable', cb); },
    onUpdateNotAvailable: function(cb)     { return onEvent('app:updateNotAvailable', cb); },
    onUpdateDownloaded: function(cb)       { return onEvent('app:updateDownloaded', cb); },
    onUpdateError: function(cb)            { return onEvent('app:updateError', cb); },
    onUpdateDownloadProgress: function(cb) { return onEvent('app:downloadProgress', cb); },

    pdfOpen: function(path)    { return invoke('pdf:open', {filePath:path}); },
    pdfRenderPage: function(id,p,w,h) { return invoke('pdf:renderPage', {id:id, pageNum:p, width:w, height:h}); },
    pdfGetFileUrl: function(path) { return invoke('pdf:getFileUrl', {filePath:path}); },
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
