#include "App.h"
#include "services/DatabaseService.h"
#include "services/PdfService.h"
#include "services/BookSourceService.h"
#include "services/ZLibraryService.h"
#include "services/ContentCache.h"
#include "WebViewHost.h"
#include "BridgeServer.h"
#include "handlers/DbHandlers.h"
#include "handlers/FileHandlers.h"
#include <shlobj.h>
#include <fstream>
#include <sstream>

// Forward declaration from BookSourceService.cpp
class BridgeServer;
class BookSourceService;
class ZLibraryService;
void RegisterBookSourceHandlers(BridgeServer* bridge, BookSourceService* svc);
void RegisterZlibHandlers(BridgeServer* bridge, ZLibraryService* zlib);

App& App::Instance() { static App app; return app; }

void App::Init(HINSTANCE hInstance)
{
    m_hInstance = hInstance;

    // 1. Database
    m_db = std::make_unique<DatabaseService>();
    m_db->Load(UserDataPath() + "/data/reader.json");

    // 2. Bridge (message dispatch)
    m_bridge = std::make_unique<BridgeServer>();

    // 3. WebView host (window + browser)
    m_webview = std::make_unique<WebViewHost>();

    // Wire bridge <-> WebView
    m_bridge->SetWebView(m_webview.get());
    m_webview->SetMessageHandler([this](const std::string& msg) {
        m_bridge->HandleMessage(msg);
    });
    m_webview->SetUpdateDoneCallback([this](bool success, const std::string& path_or_error) {
        if (success) {
            m_bridge->EmitEvent("app:updateDownloaded", {{"success", true}, {"path", path_or_error}});
        } else {
            m_bridge->EmitEvent("app:updateError", {{"error", path_or_error}});
        }
    });

    // 4. Inject bridge script BEFORE WebView2 navigates
    m_webview->InjectBridgeScript(BridgeServer::GenerateBridgeScript());
    // Z-Library floating toolbar
    m_webview->InjectBridgeScript(
        "(function(){"
        "var _t=setInterval(function(){var root=document.documentElement||document.body;if(root){clearInterval(_t);"
        // CSS
        "var s=document.createElement('style');s.textContent='#zlib-bar{position:fixed;bottom:16px;right:16px;z-index:2147483647;align-items:center;gap:2px;background:rgba(30,30,30,0.9);backdrop-filter:blur(20px);border-radius:24px;padding:6px 10px;border:1px solid rgba(255,255,255,0.1);box-shadow:0 4px 24px rgba(0,0,0,0.5)}#zlib-bar button{background:transparent;border:none;color:rgba(255,255,255,0.7);padding:8px;border-radius:50%;cursor:pointer;width:36px;height:36px;display:flex;align-items:center;justify-content:center}#zlib-bar button:hover{color:#fff;background:rgba(255,255,255,0.1)}#zlib-bar .sep{width:1px;height:20px;background:rgba(255,255,255,0.1);margin:0 4px}#zlib-bar .u{font-size:11px;color:rgba(255,255,255,0.5);margin:0 6px;max-width:160px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}';"
        "document.head.appendChild(s);"
        // HTML
        "function _svgs(){return '<button id=zb-back><svg width=16 height=16 viewBox=\"0 0 24 24\" fill=none stroke=currentColor stroke-width=2><path d=\"M15 19l-7-7 7-7\"/></svg></button>'"
        "+'<button id=zb-fwd><svg width=16 height=16 viewBox=\"0 0 24 24\" fill=none stroke=currentColor stroke-width=2><path d=\"M9 5l7 7-7 7\"/></svg></button>'"
        "+'<button id=zb-reload><svg width=16 height=16 viewBox=\"0 0 24 24\" fill=none stroke=currentColor stroke-width=2><path d=\"M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15\"/></svg></button>'"
        "+'<span class=sep></span><span class=u id=zb-url></span><span class=sep></span>'"
        "+'<button id=zb-mirror style=font-size:11px;padding:4px 8px;border-radius:6px;width:auto;height:28px>线路</button>'"
        "+'<span class=sep></span><button id=zb-close style=color:rgba(255,100,100,0.8)>'"
        "+'<svg width=16 height=16 viewBox=\"0 0 24 24\" fill=none stroke=currentColor stroke-width=2><path d=\"M6 18L18 6M6 6l12 12\"/></svg></button>'}"
        "var b=document.createElement('div');b.id='zlib-bar';b.style.display='flex';b.innerHTML=_svgs();"
        "root.appendChild(b);"
        // Event handlers
        "var _fu='';"
        "function _chk(){var onPB=location.hostname==='particlebook.app';b.style.display=onPB?'none':'flex';if(!onPB&&!_fu)_fu=location.href;var el=document.getElementById('zb-url');if(el&&!onPB)el.textContent=location.href.substring(0,80)}"
        "_chk();setInterval(_chk,500);"
        "document.getElementById('zb-back').onclick=function(){window.chrome.webview.postMessage(JSON.stringify({type:'zlibNavigate',action:'back'}))};"
        "document.getElementById('zb-fwd').onclick=function(){window.chrome.webview.postMessage(JSON.stringify({type:'zlibNavigate',action:'forward'}))};"
        "document.getElementById('zb-reload').onclick=function(){window.chrome.webview.postMessage(JSON.stringify({type:'zlibNavigate',action:'reload'}))};"
        "document.getElementById('zb-close').onclick=function(){window.chrome.webview.postMessage(JSON.stringify({type:'zlibClose'}))};"
        // Mirror selection popup
        "var _mp=null;"
        "document.getElementById('zb-mirror').onclick=function(){"
        "console.log('[PB] mirror btn clicked, electronAPI=',!!window.electronAPI,'getMirrorInfo=',!!(window.electronAPI&&window.electronAPI.zlibGetMirrorInfo));"
        "if(_mp){_mp.remove();_mp=null;return;}"
        "if(!window.electronAPI||!window.electronAPI.zlibGetMirrorInfo)return;"
        "window.electronAPI.zlibGetMirrorInfo().then(function(info){"
        "if(!info||!info.mirrors||!info.mirrors.length)return;"
        "var p=document.createElement('div');p.style.cssText='position:fixed;bottom:64px;right:16px;z-index:2147483647;background:rgba(30,30,30,0.95);border-radius:12px;padding:8px;border:1px solid rgba(255,255,255,0.1);box-shadow:0 4px 24px rgba(0,0,0,0.5);min-width:240px;max-height:300px;overflow-y:auto;font-family:system-ui';"
        "var hd=document.createElement('div');hd.textContent='线路 '+(info.index+1)+'/'+info.mirrors.length;hd.style.cssText='font-size:12px;color:#999;padding:6px 10px;border-bottom:1px solid rgba(255,255,255,0.08);margin-bottom:4px';p.appendChild(hd);"
        "for(var i=0;i<info.mirrors.length;i++){(function(idx,cur){"
        "var it=document.createElement('div');var u=info.mirrors[idx];try{var pn=new URL(u).hostname}catch(e){pn=u}"
        "it.textContent=pn;it.style.cssText='padding:8px 12px;cursor:pointer;border-radius:8px;font-size:13px;color:'+(idx===cur?'#fff':'#ccc')+';background:'+(idx===cur?'rgba(255,255,255,0.1)':'transparent');"
        "it.onmouseenter=function(){if(idx!==cur)it.style.background='rgba(255,255,255,0.05)'};"
        "it.onmouseleave=function(){if(idx!==cur)it.style.background='transparent'};"
        "it.onclick=function(){window.chrome.webview.postMessage(JSON.stringify({type:'zlibSwitchTo',index:idx}));p.remove();_mp=null;};"
        "p.appendChild(it);})(i,info.index)}"
        "root.appendChild(p);_mp=p;"
        "setTimeout(function(){var h=function(e){if(_mp&&!_mp.contains(e.target)){_mp.remove();_mp=null;document.removeEventListener('click',h)}};document.addEventListener('click',h)},100);"
        "}).catch(function(){});};"
        "}},100)}"
        ")()"
    );

    // ── Unified download/import progress card (4 stages) ──
    m_webview->InjectBridgeScript(
        "(function(){"
        "var _q=[],_rdy=false,_hideTimer=null;"
        "function _fmt(b){if(!b||b<0)return '';if(b<1024)return b+' B';"
        "if(b<1048576)return (b/1024).toFixed(1)+' KB';"
        "if(b<1073741824)return (b/1048576).toFixed(1)+' MB';"
        "return (b/1073741824).toFixed(2)+' GB';}"
        "function _ensure(){var c=document.getElementById('_pbdl');if(c)return c;"
        "if(!document.getElementById('_pbdl-style')){var st=document.createElement('style');st.id='_pbdl-style';"
        "st.textContent='@keyframes _pbdl_in{from{opacity:0;transform:translateY(-8px)}to{opacity:1;transform:none}}"
        "@keyframes _pbdl_spin{to{transform:rotate(360deg)}}';document.head.appendChild(st);}"
        "c=document.createElement('div');c.id='_pbdl';"
        "c.innerHTML='<div id=_pbdl-icon style=\"width:18px;height:18px;flex-shrink:0;display:flex;align-items:center;justify-content:center\"></div>"
        "<div style=\"flex:1;min-width:0\">"
        "<div id=_pbdl-title style=\"font-size:12px;color:#e2e8f0;font-weight:500;white-space:nowrap;overflow:hidden;text-overflow:ellipsis\"></div>"
        "<div style=\"font-size:11px;margin-top:3px;display:flex;justify-content:space-between;gap:8px\">"
        "<span id=_pbdl-status style=\"color:rgba(255,255,255,0.6)\"></span>"
        "<span id=_pbdl-bytes style=\"color:rgba(255,255,255,0.45);flex-shrink:0\"></span></div>"
        "<div style=\"margin-top:6px;width:100%;height:3px;background:rgba(255,255,255,0.08);border-radius:2px;overflow:hidden\">"
        "<div id=_pbdl-fill style=\"height:100%;width:0%;background:linear-gradient(90deg,#60a5fa,#818cf8);border-radius:2px;transition:width .25s ease,background .3s ease\"></div></div></div>"
        "<button id=_pbdl-x style=\"background:transparent;border:none;color:rgba(255,255,255,0.4);cursor:pointer;width:20px;height:20px;display:flex;align-items:center;justify-content:center;padding:0;flex-shrink:0;font-size:18px;line-height:1\">\\u00d7</button>';"
        "c.style.cssText='position:fixed;top:16px;right:16px;z-index:2147483647;display:flex;align-items:center;gap:10px;"
        "background:rgba(28,28,32,0.94);backdrop-filter:blur(20px);padding:10px 12px;border-radius:14px;"
        "border:1px solid rgba(255,255,255,0.08);box-shadow:0 8px 32px rgba(0,0,0,0.5);"
        "min-width:280px;max-width:380px;font-family:system-ui,-apple-system,sans-serif;animation:_pbdl_in .25s ease';"
        "document.body.appendChild(c);"
        "document.getElementById('_pbdl-x').onclick=function(){c.remove();if(_hideTimer){clearTimeout(_hideTimer);_hideTimer=null;}};"
        "return c;}"
        "function _icon(html){var i=document.getElementById('_pbdl-icon');if(i)i.innerHTML=html;}"
        "function _spin(){return '<svg width=14 height=14 viewBox=\"0 0 24 24\" style=\"animation:_pbdl_spin .9s linear infinite\"><circle cx=12 cy=12 r=9 fill=none stroke=\"rgba(255,255,255,0.15)\" stroke-width=2.5/><path d=\"M21 12a9 9 0 0 0-9-9\" fill=none stroke=\"#60a5fa\" stroke-width=2.5 stroke-linecap=round/></svg>';}"
        "function _chk(){return '<svg width=14 height=14 viewBox=\"0 0 24 24\" fill=none stroke=\"#4ade80\" stroke-width=3 stroke-linecap=round stroke-linejoin=round><path d=\"M5 13l4 4L19 7\"/></svg>';}"
        "function _err(){return '<svg width=14 height=14 viewBox=\"0 0 24 24\" fill=none stroke=\"#f87171\" stroke-width=3 stroke-linecap=round><path d=\"M6 6l12 12M18 6L6 18\"/></svg>';}"
        "function _setText(id,t,clr){var e=document.getElementById(id);if(e){e.textContent=t||'';if(clr!==undefined)e.style.color=clr;}}"
        "function _setFill(w,bg){var f=document.getElementById('_pbdl-fill');if(f){f.style.width=w;if(bg)f.style.background=bg;}}"
        "function _hideAfter(ms){if(_hideTimer)clearTimeout(_hideTimer);_hideTimer=setTimeout(function(){"
        "var c=document.getElementById('_pbdl');if(c){c.style.transition='opacity .4s ease,transform .4s ease';"
        "c.style.opacity='0';c.style.transform='translateY(-8px)';"
        "setTimeout(function(){if(c.parentNode)c.parentNode.removeChild(c);},400);}_hideTimer=null;},ms);}"
        "function _h(m){var d=m.data||{};var ev=m.event;"
        "if(ev==='zlib:downloadStart'){if(_hideTimer){clearTimeout(_hideTimer);_hideTimer=null;}"
        "_ensure();_icon(_spin());_setText('_pbdl-title',d.fileName||'下载中...');"
        "_setText('_pbdl-status','准备下载...','rgba(255,255,255,0.6)');_setText('_pbdl-bytes','');"
        "_setFill('0%','linear-gradient(90deg,#60a5fa,#818cf8)');}"
        "else if(ev==='zlib:downloadProgress'){_ensure();_icon(_spin());"
        "var pct=d.total>0?Math.round(d.received/d.total*100):0;"
        "if(d.fileName)_setText('_pbdl-title',d.fileName);"
        "_setText('_pbdl-status',(d.total>0?pct+'% · ':'')+'下载中','rgba(255,255,255,0.6)');"
        "_setText('_pbdl-bytes',d.total>0?_fmt(d.received)+' / '+_fmt(d.total):_fmt(d.received));"
        "_setFill((d.total>0?pct:8)+'%');}"
        "else if(ev==='zlib:downloadComplete'){_ensure();"
        "_setText('_pbdl-status','下载完成','rgba(255,255,255,0.6)');_setFill('100%');}"
        "else if(ev==='zlib:importStart'){_ensure();_icon(_spin());"
        "if(d.fileName)_setText('_pbdl-title',d.fileName);"
        "_setText('_pbdl-status','正在导入到书架...','rgba(255,255,255,0.6)');_setText('_pbdl-bytes','');"
        "_setFill('100%','linear-gradient(90deg,#a78bfa,#818cf8)');}"
        "else if(ev==='zlib:importComplete'){_ensure();_icon(_chk());"
        "if(d.fileName)_setText('_pbdl-title',d.fileName);"
        "_setText('_pbdl-status','已加入书架','#4ade80');_setText('_pbdl-bytes','');"
        "_setFill('100%','#4ade80');_hideAfter(3500);}"
        "else if(ev==='zlib:downloadError'||ev==='zlib:importError'){_ensure();_icon(_err());"
        "if(d.fileName)_setText('_pbdl-title',d.fileName);"
        "var lbl=ev==='zlib:downloadError'?'下载失败':'导入失败';"
        "var emap={invalid_url:'链接无效',http_open_failed:'网络初始化失败',connect_failed:'无法连接服务器',request_failed:'请求创建失败',network_error:'网络异常',file_create_failed:'无法写入本地文件',empty_response:'服务器返回空内容',too_many_redirects:'重定向过多',import_failed:'导入失败'};"
        "var msg='';if(d.error){if(emap[d.error])msg=emap[d.error];else if(d.error.indexOf('http_')===0)msg='服务器错误 '+d.error.substring(5);else if(d.error.indexOf('import_exception:')===0)msg='导入异常';else msg=d.error;}"
        "_setText('_pbdl-status',msg?lbl+': '+msg:lbl,'#f87171');_setText('_pbdl-bytes','');"
        "_setFill('100%','#f87171');_hideAfter(6000);}}"
        "function _flush(){_rdy=true;for(var i=0;i<_q.length;i++)_h(_q[i]);_q=[];}"
        "try{window.chrome.webview.addEventListener('message',function(e){try{"
        "var m=typeof e.data==='string'?JSON.parse(e.data):e.data;if(m.type!=='event')return;"
        "if(!_rdy){_q.push(m);return;}_h(m);}catch(e2){console.error('[PB] handler error',e2);}});}catch(e1){console.error('[PB] listener error',e1);}"
        "var _t=setInterval(function(){if(document.body){clearInterval(_t);_flush();}},30);"
        "})()"
    );

    // 5. Services
    m_pdf = std::make_unique<PdfService>();
    m_bookSource = std::make_shared<BookSourceService>(m_db.get(), m_bridge.get());
    m_zlib = std::make_unique<ZLibraryService>(m_bridge.get());
    m_zlib->SetHost(m_webview.get());
    m_zlib->SetDatabase(m_db.get());
    m_cache = std::make_unique<ContentCache>();

    // 6. Register IPC handlers
    RegisterDbHandlers(m_bridge.get(), m_db.get());
    RegisterFileHandlers(m_bridge.get(), m_db.get(), m_cache.get());
    RegisterPdfHandlers(m_bridge.get(), m_pdf.get());
    RegisterBookSourceHandlers(m_bridge.get(), m_bookSource.get());
    RegisterZlibHandlers(m_bridge.get(), m_zlib.get());
}

void App::Run()
{
    m_webview->CreateMainWindow(m_hInstance);
    m_webview->RunMessageLoop();
}

void App::Shutdown()
{
    if (m_db) m_db->FlushSync();
    m_webview.reset();
    m_bridge.reset();
    m_db.reset();
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
