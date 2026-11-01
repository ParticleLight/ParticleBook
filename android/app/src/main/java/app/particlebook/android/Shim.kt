package app.particlebook.android

/** Maps the WebView2 transport the unmodified renderer expects onto the Android
 *  WebMessageListener object injected as window.pbhost. */
object Shim {
    val JS: String = """
(function(){
  if (window.chrome && window.chrome.webview) return;
  var listeners = [];
  window.chrome = window.chrome || {};
  window.chrome.webview = {
    postMessage: function(s){ window.pbhost.postMessage(String(s)); },
    addEventListener: function(t, fn){ if (t === 'message') listeners.push(fn); },
    removeEventListener: function(t, fn){ var i = listeners.indexOf(fn); if (i >= 0) listeners.splice(i, 1); }
  };
  window.pbhost.onmessage = function(e){
    var d = (e && e.data !== undefined) ? e.data : '';
    listeners.slice().forEach(function(fn){
      try { fn({ data: d }); } catch (err) { console.error('bridge listener: ' + err); }
    });
  };
})();
""".trimIndent()
}
