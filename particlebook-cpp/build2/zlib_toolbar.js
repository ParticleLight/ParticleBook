// Z-Library floating toolbar injected on Z-Lib pages
(function() {
var __zlibFirstUrl = '';
function __initZBar() {
  try {
    var zStyle = document.createElement('style');
    zStyle.textContent = '#zlib-bar{position:fixed;bottom:16px;right:16px;z-index:2147483647;display:none;align-items:center;gap:2px;background:rgba(30,30,30,0.9);backdrop-filter:blur(20px);-webkit-backdrop-filter:blur(20px);border-radius:24px;padding:6px 10px;border:1px solid rgba(255,255,255,0.1);box-shadow:0 4px 24px rgba(0,0,0,0.5);transition:all 0.2s}#zlib-bar:hover{background:rgba(40,40,40,0.95);transform:translateY(-2px);box-shadow:0 6px 32px rgba(0,0,0,0.6)}#zlib-bar button{background:transparent;border:none;color:rgba(255,255,255,0.7);padding:8px;border-radius:50%;cursor:pointer;transition:all 0.15s;width:36px;height:36px;display:flex;align-items:center;justify-content:center}#zlib-bar button:hover{color:#fff;background:rgba(255,255,255,0.1)}#zlib-bar .sep{width:1px;height:20px;background:rgba(255,255,255,0.1);margin:0 4px}#zlib-bar .u{font-size:11px;color:rgba(255,255,255,0.5);margin:0 6px;max-width:160px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;line-height:1}';
    document.head.appendChild(zStyle);
    var zBar = document.createElement('div');
    zBar.id = 'zlib-bar';
    zBar.innerHTML = '<button id="zb-back"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M15 19l-7-7 7-7"/></svg></button><button id="zb-fwd"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 5l7 7-7 7"/></svg></button><button id="zb-reload"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"/></svg></button><span class="sep"></span><span class="u" id="zb-url"></span><span class="sep"></span><button id="zb-mirror" title="换线路" style="font-size:11px;padding:4px 8px;border-radius:6px;width:auto;height:28px">线路</button><span class="sep"></span><button id="zb-close" style="color:rgba(255,100,100,0.8)" title="返回书架"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 18L18 6M6 6l12 12"/></svg></button>';
    document.body.appendChild(zBar);

    function _checkZLib() {
      try {
        var h = window.location.hostname;
        var active = h.indexOf('z-lib')!==-1||h.indexOf('1lib')!==-1||h.indexOf('zzz')!==-1||h.indexOf('singlelogin')!==-1||h.indexOf('fbiwarning')!==-1||h.indexOf('zlibrary')!==-1;
        zBar.style.display = active ? 'flex' : 'none';
        if (active) {
          if (__zlibFirstUrl === '') __zlibFirstUrl = window.location.href;
          var u = window.location.href;
          var el = document.getElementById('zb-url');
          if (el) el.textContent = u.length > 80 ? u.substring(0,80)+'...' : u;
        }
      } catch(e) {}
    }
    _checkZLib();
    setInterval(_checkZLib, 500);

    document.getElementById('zb-back').addEventListener('click',function(){
      if (__zlibFirstUrl && window.location.href !== __zlibFirstUrl) { history.back(); }
    });
    document.getElementById('zb-fwd').addEventListener('click',function(){history.forward()});
    document.getElementById('zb-reload').addEventListener('click',function(){location.reload()});

    // Mirror selection
    var _mp = null;
    document.getElementById('zb-mirror').addEventListener('click',function(){
      if (_mp) { _mp.remove(); _mp = null; return; }
      var rid = Date.now();
      window.chrome.webview.addEventListener('message', function check(e) {
        try {
          var m = JSON.parse(e.data);
          if (m.type==='response'&&m.id===rid) {
            window.chrome.webview.removeEventListener('message', check);
            var info = m.result; if (!info||!info.mirrors) return;
            var popup = document.createElement('div');
            popup.style.cssText = 'position:fixed;bottom:64px;right:16px;z-index:2147483647;background:rgba(30,30,30,0.95);backdrop-filter:blur(20px);border-radius:12px;padding:8px;border:1px solid rgba(255,255,255,0.1);box-shadow:0 4px 24px rgba(0,0,0,0.5);min-width:240px;max-height:300px;overflow-y:auto;font-family:system-ui,sans-serif';
            var title = document.createElement('div');
            title.textContent = '线路 '+(info.current+1)+'/'+info.mirrors.length;
            title.style.cssText = 'font-size:12px;color:#999;padding:6px 10px;border-bottom:1px solid rgba(255,255,255,0.08);margin-bottom:4px';
            popup.appendChild(title);
            for (var i=0;i<info.mirrors.length;i++){(function(idx){
              var item=document.createElement('div');
              var u=info.mirrors[idx];try{var pn=new URL(u).hostname}catch(e){pn=u}
              item.textContent=pn;
              item.style.cssText='padding:8px 12px;cursor:pointer;border-radius:8px;font-size:13px;color:'+(idx===info.current?'#fff':'#ccc')+';background:'+(idx===info.current?'rgba(255,255,255,0.1)':'transparent');
              item.addEventListener('mouseenter',function(){if(idx!==info.current)item.style.background='rgba(255,255,255,0.05)'});
              item.addEventListener('mouseleave',function(){if(idx!==info.current)item.style.background='transparent'});
              item.addEventListener('click',function(){window.chrome.webview.postMessage(JSON.stringify({type:'zlibSwitchTo',index:idx}));popup.remove();_mp=null;});
              popup.appendChild(item);
            })(i);}
            document.body.appendChild(popup); _mp=popup;
            setTimeout(function(){var h=function(e){if(_mp&&!_mp.contains(e.target)){_mp.remove();_mp=null;document.removeEventListener('click',h)}};document.addEventListener('click',h)},100);
          }
        } catch(_) {}
      });
      window.chrome.webview.postMessage(JSON.stringify({type:'invoke',id:rid,method:'zlib:getMirrorInfo',params:{}}));
    });

    document.getElementById('zb-close').addEventListener('click',function(){
      window.chrome.webview.postMessage(JSON.stringify({type:'zlibClose'}));
    });
  } catch(e) {}
}
if (document.body) __initZBar();
else document.addEventListener('DOMContentLoaded', __initZBar);
})();
