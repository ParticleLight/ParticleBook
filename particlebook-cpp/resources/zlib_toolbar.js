(function(){
// 只在【顶层 http(s) 页面】画这条工具条（2026-10-29 用户反馈「打开 EPUB 右下角会冒出线路条」）：
// 注入走 AddScriptToExecuteOnDocumentCreated，对 iframe 也生效；EPUB/HTML 阅读器把正文放在
// about:srcdoc 的 iframe 里，那里 location.hostname 是空字符串，下面的 hostname 守卫判定成
// 「非应用页面」于是把工具条画进了书页。顶帧判断 + 协议判断能一次性挡住所有 srcdoc/blob 帧。
if (window.top !== window) return;
if (location.protocol !== 'http:' && location.protocol !== 'https:') return;
// 应用自身页面（particlebook.app）根本不需要这条工具条：直接不创建，省得留一个
// display:none 的隐藏节点（此前只是创建后隐藏）。
if (location.hostname === 'particlebook.app') return;
var _t=setInterval(function(){var root=document.documentElement||document.body;if(root){clearInterval(_t);
var s=document.createElement('style');s.textContent='#zlib-bar{position:fixed;bottom:16px;right:16px;z-index:2147483647;display:flex;align-items:center;gap:2px;background:rgba(30,30,30,0.9);backdrop-filter:blur(20px);border-radius:24px;padding:6px 10px;border:1px solid rgba(255,255,255,0.1);box-shadow:0 4px 24px rgba(0,0,0,0.5)}#zlib-bar button{background:transparent;border:none;color:rgba(255,255,255,0.7);padding:8px;border-radius:50%;cursor:pointer;width:36px;height:36px;display:flex;align-items:center;justify-content:center}#zlib-bar button:hover{color:#fff;background:rgba(255,255,255,0.1)}#zlib-bar .sep{width:1px;height:20px;background:rgba(255,255,255,0.1);margin:0 4px}#zlib-bar .u{font-size:11px;color:rgba(255,255,255,0.5);margin:0 6px;max-width:160px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}';
document.head.appendChild(s);
function _svgs(){return '<button id=zb-back><svg width=16 height=16 viewBox="0 0 24 24" fill=none stroke=currentColor stroke-width=2><path d="M15 19l-7-7 7-7"/></svg></button>'
+'<button id=zb-fwd><svg width=16 height=16 viewBox="0 0 24 24" fill=none stroke=currentColor stroke-width=2><path d="M9 5l7 7-7 7"/></svg></button>'
+'<button id=zb-reload><svg width=16 height=16 viewBox="0 0 24 24" fill=none stroke=currentColor stroke-width=2><path d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"/></svg></button>'
+'<span class=sep></span><span class=u id=zb-url></span><span class=sep></span>'
+'<button id=zb-mirror style=font-size:11px;padding:4px 8px;border-radius:6px;width:auto;height:28px>'+(window.__pbLang==='en'?'Line':'线路')+'</button>'
+'<button id=zb-logout title='+(window.__pbLang==='en'?'Sign out':'退出登录')+'>'
+'<svg width=16 height=16 viewBox="0 0 24 24" fill=none stroke=currentColor stroke-width=2><path d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1"/></svg></button>'
+'<span class=sep></span><button id=zb-close style=color:rgba(255,100,100,0.8)>'
+'<svg width=16 height=16 viewBox="0 0 24 24" fill=none stroke=currentColor stroke-width=2><path d="M6 18L18 6M6 6l12 12"/></svg></button>'}
var b=document.createElement('div');b.id='zlib-bar';b.style.display='flex';b.innerHTML=_svgs();
root.appendChild(b);
var _fu='';
function _chk(){var onPB=location.hostname==='particlebook.app';b.style.display=onPB?'none':'flex';if(!onPB&&!_fu)_fu=location.href;var el=document.getElementById('zb-url');if(el&&!onPB)el.textContent=location.href.substring(0,80)}
_chk();setInterval(_chk,500);
document.getElementById('zb-back').onclick=function(){window.chrome.webview.postMessage(JSON.stringify({type:'zlibNavigate',action:'back'}))};
document.getElementById('zb-fwd').onclick=function(){window.chrome.webview.postMessage(JSON.stringify({type:'zlibNavigate',action:'forward'}))};
document.getElementById('zb-reload').onclick=function(){window.chrome.webview.postMessage(JSON.stringify({type:'zlibNavigate',action:'reload'}))};
document.getElementById('zb-close').onclick=function(){window.chrome.webview.postMessage(JSON.stringify({type:'zlibClose'}))};
var _lo=document.getElementById('zb-logout');if(_lo)_lo.onclick=function(){window.chrome.webview.postMessage(JSON.stringify({type:'zlibLogout'}))};
var _mp=null;
document.getElementById('zb-mirror').onclick=function(){
console.log('[PB] mirror btn clicked, electronAPI=',!!window.electronAPI,'getMirrorInfo=',!!(window.electronAPI&&window.electronAPI.zlibGetMirrorInfo));
if(_mp){_mp.remove();_mp=null;return;}
if(!window.electronAPI||!window.electronAPI.zlibGetMirrorInfo)return;
window.electronAPI.zlibGetMirrorInfo().then(function(info){
if(!info||!info.mirrors||!info.mirrors.length)return;
var p=document.createElement('div');p.style.cssText='position:fixed;bottom:64px;right:16px;z-index:2147483647;background:rgba(30,30,30,0.95);border-radius:12px;padding:8px;border:1px solid rgba(255,255,255,0.1);box-shadow:0 4px 24px rgba(0,0,0,0.5);min-width:240px;max-height:300px;overflow-y:auto;font-family:system-ui';
var hd=document.createElement('div');hd.textContent='线路 '+(info.index+1)+'/'+info.mirrors.length;hd.style.cssText='font-size:12px;color:#999;padding:6px 10px;border-bottom:1px solid rgba(255,255,255,0.08);margin-bottom:4px';p.appendChild(hd);
for(var i=0;i<info.mirrors.length;i++){(function(idx,cur){
var it=document.createElement('div');var u=info.mirrors[idx];try{var pn=new URL(u).hostname}catch(e){pn=u}
it.textContent=pn;it.style.cssText='padding:8px 12px;cursor:pointer;border-radius:8px;font-size:13px;color:'+(idx===cur?'#fff':'#ccc')+';background:'+(idx===cur?'rgba(255,255,255,0.1)':'transparent');
it.onmouseenter=function(){if(idx!==cur)it.style.background='rgba(255,255,255,0.05)'};
it.onmouseleave=function(){if(idx!==cur)it.style.background='transparent'};
it.onclick=function(){window.chrome.webview.postMessage(JSON.stringify({type:'zlibSwitchTo',index:idx}));p.remove();_mp=null;};
p.appendChild(it);})(i,info.index)}
root.appendChild(p);_mp=p;
setTimeout(function(){var h=function(e){if(_mp&&!_mp.contains(e.target)){_mp.remove();_mp=null;document.removeEventListener('click',h)}};document.addEventListener('click',h)},100);
}).catch(function(){});};
window.__pbRefreshToolbar=function(){var b=document.getElementById('zb-mirror');if(b)b.textContent=(window.__pbLang==='en'?'Line':'线路');};
}},100)}
)();
