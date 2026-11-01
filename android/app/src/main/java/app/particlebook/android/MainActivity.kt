package app.particlebook.android

import android.annotation.SuppressLint
import android.net.Uri
import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewClientCompat
import java.io.File

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var bridge: Bridge
    private var t0 = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        t0 = System.nanoTime()

        val assetsRoot = File(filesDir, "_pb_files").apply { mkdirs() }
        val pdfRoot = File(filesDir, "_pb_pdf").apply { mkdirs() }
        val coverRoot = File(filesDir, "_pb_covers").apply { mkdirs() }
        Assets.seed(this, assetsRoot)

        NativeCore.loadOnce()
        Log.i(TAG, String.format("TIMING native_load=%dms", NativeCore.loadMs))

        web = WebView(this)
        setContentView(web, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        WebView.setWebContentsDebuggingEnabled(true)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
        }

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/_pb_files/", WebViewAssetLoader.InternalStoragePathHandler(this, assetsRoot))
            .addPathHandler("/_pb_pdf/", WebViewAssetLoader.InternalStoragePathHandler(this, pdfRoot))
            .addPathHandler("/_pb_covers/", WebViewAssetLoader.InternalStoragePathHandler(this, coverRoot))
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        bridge = Bridge(this, assetsRoot)

        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView?, url: String?) {
                Log.i(TAG, "TIMING page_finished=" + (System.nanoTime() - t0) / 1_000_000 + "ms")
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.i(TAG, "CONSOLE " + m.message())
                return true
            }
        }

        val origin = "https://appassets.androidplatform.net"
        WebViewCompat.addDocumentStartJavaScript(web, Shim.JS, setOf(origin))
        WebViewCompat.addDocumentStartJavaScript(web, Assets.glue(this), setOf(origin))
        WebViewCompat.addDocumentStartJavaScript(web, BenchJS, setOf(origin))

        val listener = object : WebViewCompat.WebMessageListener {
            override fun onPostMessage(
                view: WebView,
                message: WebMessageCompat,
                sourceOrigin: Uri,
                isMainFrame: Boolean,
                replyProxy: JavaScriptReplyProxy
            ) {
                bridge.onMessage(message.data ?: "") { out -> replyProxy.postMessage(out) }
            }
        }
        WebViewCompat.addWebMessageListener(web, "pbhost", setOf(origin), listener)

        Log.i(TAG, "TIMING webview_ready=" + (System.nanoTime() - t0) / 1_000_000 + "ms")
        web.loadUrl(origin + "/index.html")
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ParticleBook"
        private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT

        // Injected before any app script: reports paint/navigation/books-ready
        // timings through the bridge so logcat carries the TTI numbers.
        private val BenchJS = """
(function(){
  var origError = console.error;
  console.error = function(){
    try {
      var parts = [];
      for (var i = 0; i < arguments.length; i++) {
        var a = arguments[i];
        parts.push(a && a.stack ? a.stack : String(a));
      }
      origError.apply(console, arguments);
      origError('[PBSTACK] ' + parts.join(' || '));
    } catch (e) {}
  };
  window.addEventListener('error', function(e){
    console.error('[PBONERROR] ' + (e.error && e.error.stack ? e.error.stack : String(e.message)));
  });
  function send(kind, data){
    try {
      var body = Object.assign({ kind: kind }, data || {});
      window.chrome.webview.postMessage(JSON.stringify({type:'invoke', id: 990000 + Math.floor(Math.random()*1000), method:'bench:mark', params: body}));
    } catch (e) {}
  }
  function nav(){
    var n = performance.getEntriesByType('navigation')[0];
    if (!n) return {};
    return { ttfb: Math.round(n.responseStart), dcl: Math.round(n.domContentLoadedEventEnd), load: Math.round(n.loadEventEnd), transfer: n.transferSize };
  }
  function paints(){
    var out = {};
    performance.getEntriesByType('paint').forEach(function(p){ out[p.name] = Math.round(p.startTime); });
    return out;
  }
  window.addEventListener('load', function(){
    send('load', { nav: nav(), paint: paints() });
    setTimeout(function(){
      var t = performance.now();
      if (window.electronAPI && window.electronAPI.getBooks) {
        window.electronAPI.getBooks().then(function(b){
          send('books', { count: (b||[]).length, took: Math.round(performance.now() - t) });
          setTimeout(function(){ send('tti', { paint: paints(), nav: nav() }); }, 300);
        }).catch(function(e){ send('books_error', { message: String(e) }); });
      }
    }, 50);
  });
})();
""".trimIndent()
    }
}