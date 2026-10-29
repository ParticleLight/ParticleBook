(function(){
// 同 zlib_toolbar.js：只处理顶层 http(s) 页面 —— 书页的 about:srcdoc iframe 不该弹下载卡片。
if (window.top !== window) return;
if (location.protocol !== 'http:' && location.protocol !== 'https:') return;
if (location.hostname === 'particlebook.app') return;
var _q=[],_rdy=false,_hideTimer=null;
function _fmt(b){if(!b||b<0)return '';if(b<1024)return b+' B';
if(b<1048576)return (b/1024).toFixed(1)+' KB';
if(b<1073741824)return (b/1048576).toFixed(1)+' MB';
return (b/1073741824).toFixed(2)+' GB';}
function _ensure(){var c=document.getElementById('_pbdl');if(c)return c;
if(!document.getElementById('_pbdl-style')){var st=document.createElement('style');st.id='_pbdl-style';
st.textContent='@keyframes _pbdl_in{from{opacity:0;transform:translateY(-8px)}to{opacity:1;transform:none}}@keyframes _pbdl_spin{to{transform:rotate(360deg)}}';document.head.appendChild(st);}
c=document.createElement('div');c.id='_pbdl';
c.innerHTML='<div id=_pbdl-icon style="width:18px;height:18px;flex-shrink:0;display:flex;align-items:center;justify-content:center"></div><div style="flex:1;min-width:0"><div id=_pbdl-title style="font-size:12px;color:#e2e8f0;font-weight:500;white-space:nowrap;overflow:hidden;text-overflow:ellipsis"></div><div style="font-size:11px;margin-top:3px;display:flex;justify-content:space-between;gap:8px"><span id=_pbdl-status style="color:rgba(255,255,255,0.6)"></span><span id=_pbdl-bytes style="color:rgba(255,255,255,0.45);flex-shrink:0"></span></div><div style="margin-top:6px;width:100%;height:3px;background:rgba(255,255,255,0.08);border-radius:2px;overflow:hidden"><div id=_pbdl-fill style="height:100%;width:0%;background:linear-gradient(90deg,#60a5fa,#818cf8);border-radius:2px;transition:width .25s ease,background .3s ease"></div></div></div><button id=_pbdl-x style="background:transparent;border:none;color:rgba(255,255,255,0.4);cursor:pointer;width:20px;height:20px;display:flex;align-items:center;justify-content:center;padding:0;flex-shrink:0;font-size:18px;line-height:1">\u00d7</button>';
c.style.cssText='position:fixed;top:16px;right:16px;z-index:2147483647;display:flex;align-items:center;gap:10px;background:rgba(28,28,32,0.94);backdrop-filter:blur(20px);padding:10px 12px;border-radius:14px;border:1px solid rgba(255,255,255,0.08);box-shadow:0 8px 32px rgba(0,0,0,0.5);min-width:280px;max-width:380px;font-family:system-ui,-apple-system,sans-serif;animation:_pbdl_in .25s ease';
document.body.appendChild(c);
document.getElementById('_pbdl-x').onclick=function(){c.remove();if(_hideTimer){clearTimeout(_hideTimer);_hideTimer=null;}};
return c;}
function _icon(html){var i=document.getElementById('_pbdl-icon');if(i)i.innerHTML=html;}
function _spin(){return '<svg width=14 height=14 viewBox="0 0 24 24" style="animation:_pbdl_spin .9s linear infinite"><circle cx=12 cy=12 r=9 fill=none stroke="rgba(255,255,255,0.15)" stroke-width=2.5/><path d="M21 12a9 9 0 0 0-9-9" fill=none stroke="#60a5fa" stroke-width=2.5 stroke-linecap=round/></svg>';}
function _chk(){return '<svg width=14 height=14 viewBox="0 0 24 24" fill=none stroke="#4ade80" stroke-width=3 stroke-linecap=round stroke-linejoin=round><path d="M5 13l4 4L19 7"/></svg>';}
function _err(){return '<svg width=14 height=14 viewBox="0 0 24 24" fill=none stroke="#f87171" stroke-width=3 stroke-linecap=round><path d="M6 6l12 12M18 6L6 18"/></svg>';}
function _setText(id,t,clr){var e=document.getElementById(id);if(e){e.textContent=t||'';if(clr!==undefined)e.style.color=clr;}}
function _setFill(w,bg){var f=document.getElementById('_pbdl-fill');if(f){f.style.width=w;if(bg)f.style.background=bg;}}
function _hideAfter(ms){if(_hideTimer)clearTimeout(_hideTimer);_hideTimer=setTimeout(function(){
var c=document.getElementById('_pbdl');if(c){c.style.transition='opacity .4s ease,transform .4s ease';
c.style.opacity='0';c.style.transform='translateY(-8px)';
setTimeout(function(){if(c.parentNode)c.parentNode.removeChild(c);},400);}_hideTimer=null;},ms);}
function _h(m){var d=m.data||{};var ev=m.event;
if(ev==='zlib:downloadStart'){if(_hideTimer){clearTimeout(_hideTimer);_hideTimer=null;}
_ensure();_icon(_spin());_setText('_pbdl-title',d.fileName||(window.__pbLang==='en'?'Downloading...':'下载中...'));
_setText('_pbdl-status',(window.__pbLang==='en'?'Preparing...':'准备下载...'),'rgba(255,255,255,0.6)');_setText('_pbdl-bytes','');
_setFill('0%','linear-gradient(90deg,#60a5fa,#818cf8)');}
else if(ev==='zlib:downloadProgress'){_ensure();_icon(_spin());
var pct=d.total>0?Math.round(d.received/d.total*100):0;
if(d.fileName)_setText('_pbdl-title',d.fileName);
_setText('_pbdl-status',(d.total>0?pct+'% · ':'')+(window.__pbLang==='en'?'Downloading':'下载中'),'rgba(255,255,255,0.6)');
_setText('_pbdl-bytes',d.total>0?_fmt(d.received)+' / '+_fmt(d.total):_fmt(d.received));
_setFill((d.total>0?pct:8)+'%');}
else if(ev==='zlib:downloadComplete'){_ensure();
_setText('_pbdl-status',(window.__pbLang==='en'?'Download complete':'下载完成'),'rgba(255,255,255,0.6)');_setFill('100%');}
else if(ev==='zlib:importStart'){_ensure();_icon(_spin());
if(d.fileName)_setText('_pbdl-title',d.fileName);
_setText('_pbdl-status',(window.__pbLang==='en'?'Importing to library...':'正在导入到书架...'),'rgba(255,255,255,0.6)');_setText('_pbdl-bytes','');
_setFill('100%','linear-gradient(90deg,#a78bfa,#818cf8)');}
else if(ev==='zlib:importComplete'){_ensure();_icon(_chk());
if(d.fileName)_setText('_pbdl-title',d.fileName);
_setText('_pbdl-status',(window.__pbLang==='en'?'Added to library':'已加入书架'),'#4ade80');_setText('_pbdl-bytes','');
_setFill('100%','#4ade80');_hideAfter(3500);}
else if(ev==='zlib:downloadError'||ev==='zlib:importError'){_ensure();_icon(_err());
if(d.fileName)_setText('_pbdl-title',d.fileName);
var lbl=ev==='zlib:downloadError'?(window.__pbLang==='en'?'Download failed':'下载失败'):(window.__pbLang==='en'?'Import failed':'导入失败');
var emap=(window.__pbLang==='en')?{invalid_url:'Invalid URL',http_open_failed:'Network init failed',connect_failed:'Cannot connect to server',request_failed:'Request failed',network_error:'Network error',file_create_failed:'Cannot write local file',empty_response:'Empty server response',too_many_redirects:'Too many redirects',import_failed:'Import failed',incomplete_download:'Download incomplete',not_a_book:'Not a book file',file_write_failed:'Cannot write file'}:{invalid_url:'链接无效',http_open_failed:'网络初始化失败',connect_failed:'无法连接服务器',request_failed:'请求创建失败',network_error:'网络异常',file_create_failed:'无法写入本地文件',empty_response:'服务器返回空内容',too_many_redirects:'重定向过多',import_failed:'导入失败',incomplete_download:'下载不完整（连接中断）',not_a_book:'不是电子书文件（可能是登录页）',file_write_failed:'写入本地文件失败'};
var msg='';if(d.error){if(emap[d.error])msg=emap[d.error];else if(d.error.indexOf('http_')===0)msg=(window.__pbLang==='en'?'Server error ':'服务器错误 ')+d.error.substring(5);else if(d.error.indexOf('import_exception:')===0)msg=(window.__pbLang==='en'?'Import exception':'导入异常');else msg=d.error;}
_setText('_pbdl-status',msg?lbl+': '+msg:lbl,'#f87171');_setText('_pbdl-bytes','');
_setFill('100%','#f87171');_hideAfter(6000);}
else if(ev==='zlib:allMirrorsFailed'){_ensure();_icon(_err());
_setText('_pbdl-title','Z-Library');
_setText('_pbdl-status',(window.__pbLang==='en'?'All lines failed. Pick another line or close.':'所有线路都连不上，请切换线路或关闭'),'#f87171');
_setText('_pbdl-bytes','');_setFill('100%','#f87171');_hideAfter(10000);}}
function _flush(){_rdy=true;for(var i=0;i<_q.length;i++)_h(_q[i]);_q=[];}
try{window.chrome.webview.addEventListener('message',function(e){try{
var m=typeof e.data==='string'?JSON.parse(e.data):e.data;if(m.type!=='event')return;
if(!_rdy){_q.push(m);return;}_h(m);}catch(e2){console.error('[PB] handler error',e2);}});}catch(e1){console.error('[PB] listener error',e1);}
var _t=setInterval(function(){if(document.body){clearInterval(_t);_flush();}},30);
})();
