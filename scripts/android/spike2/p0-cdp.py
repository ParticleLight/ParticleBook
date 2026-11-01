#!/usr/bin/env python
"""p0-cdp.py — P0 基线 WebView CDP 只读驱动（task-6 独立复验产物，bench 可复用）

为什么需要
  adb input tap 在 CPU 饱和的模拟器上会被 P0 书架 WebView 判成长按，弹出
  「打开/分屏阅读/详情/删除」上下文菜单，拿不到 PBPdf 的 pdf_open。
  P0 app（app.particlebook 2.2.1-p0）的 MainActivity 已调用
  setWebContentsDebuggingEnabled(true)，所以可用 WebView 远程调试（CDP）执行
  真实 React onClick —— 只读驱动：不改 APK、不改源码、不注入业务逻辑。

完整配方（bash，可直接抄）
  . scripts/android/spike2/env.sh
  A() { "$ADB" -s "$SERIAL" "$@"; }
  OUT=/f/tmp/pb-p0; mkdir -p "$OUT"
  # 1) 流式抓 logcat（不要用 -c 后短抓：并发场景下会被别人清掉）
  A logcat -v threadtime -s PBPdf:V PBBridge:I > "$OUT/stream.txt" 2>&1 &
  LPID=$!
  # 2) 冷启并等 books
  A shell am start -W -n app.particlebook/app.particlebook.android.MainActivity
  until grep -q 'BENCH.*books' "$OUT/stream.txt"; do sleep 1; done
  # 3) 取 WebView devtools socket 并转发（9222 常被本机占用 → 用 9333）
  SOCK=$(A shell cat /proc/net/unix | grep -o 'webview_devtools_remote_[0-9]*' | head -1 | tr -d '\r')
  echo "socket=$SOCK  pid=$(A shell pidof app.particlebook | tr -d '\r')"
  A forward --remove tcp:9333 2>/dev/null; A forward tcp:9333 localabstract:$SOCK
  # 4) 点击第 2 本书（data-book-id=2 → PDF《统计之美》）
  python "$WT/scripts/android/spike2/p0-cdp.py" 9333 open 2
  # 5) 等 pdf_open / pdf_page
  until grep -q 'pdf_open' "$OUT/stream.txt"; do sleep 1; done
  grep 'PBPdf' "$OUT/stream.txt"
  kill $LPID 2>/dev/null; A forward --remove tcp:9333

子命令
  list                列出 page target（每行一个 JSON）
  eval <js>           在 app 页面执行 JS（Runtime.evaluate, returnByValue, awaitPromise）
  open <book-id>      点击书架卡片 data-book-id=<id>，返回 "CLICKED <id>" 或 "NO_CARD"
  pages               返回 {cards,pages,scrollable,tag,cls,sh,ch,top}（阅读器滚动容器探测）
  scroll <k>          阅读器滚动 k 个可视高度（k 可为小数，如 0.95），返回滚动后 top

注意
  - app 重启后 socket 名（含 pid）变化，必须重新 forward。
  - python 必须用 Windows 路径（如 F:/... 或仓库相对路径），MSYS 的 /f/... 会被
    Windows Python 解释成 F:\f\... 而 FileNotFoundError。
  - 若 /json/list 无 appassets.androidplatform.net 页面，说明 WebView 还没起好：先等 books。
"""
import base64, json, os, socket, struct, sys, urllib.request


def http_json(port):
    last = None
    for path in ("/json/list", "/json"):
        try:
            with urllib.request.urlopen("http://127.0.0.1:%d%s" % (port, path), timeout=5) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception as e:  # noqa: BLE001
            last = e
    raise SystemExit("http_json failed: %r" % last)


class WS:
    def __init__(self, host, port, path):
        self.s = socket.create_connection((host, port), timeout=15)
        key = base64.b64encode(os.urandom(16)).decode()
        req = ("GET %s HTTP/1.1\r\nHost: %s:%d\r\nUpgrade: websocket\r\n"
               "Connection: Upgrade\r\nSec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n"
               % (path, host, port, key))
        self.s.sendall(req.encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            chunk = self.s.recv(4096)
            if not chunk:
                raise SystemExit("ws handshake closed")
            buf += chunk
        head, _, rest = buf.partition(b"\r\n\r\n")
        if b"101" not in head.split(b"\r\n")[0]:
            raise SystemExit("ws handshake failed: " + head.decode("latin1"))
        self.buf = rest

    def send(self, obj):
        data = json.dumps(obj).encode()
        mask = os.urandom(4)
        n = len(data)
        if n < 126:
            hdr = struct.pack("!BB", 0x81, 0x80 | n)
        elif n < 65536:
            hdr = struct.pack("!BBH", 0x81, 0x80 | 126, n)
        else:
            hdr = struct.pack("!BBQ", 0x81, 0x80 | 127, n)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
        self.s.sendall(hdr + mask + masked)

    def _readn(self, n):
        while len(self.buf) < n:
            chunk = self.s.recv(65536)
            if not chunk:
                raise SystemExit("ws closed")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def recv(self):
        while True:
            b1, b2 = self._readn(2)
            op = b1 & 0x0F
            ln = b2 & 0x7F
            if ln == 126:
                ln = struct.unpack("!H", self._readn(2))[0]
            elif ln == 127:
                ln = struct.unpack("!Q", self._readn(8))[0]
            payload = self._readn(ln)
            if op == 8:
                raise SystemExit("ws close frame")
            if op == 9:
                continue
            if op in (1, 2):
                return json.loads(payload.decode("utf-8"))


JS_PAGES = r'''(()=>{
  const pages=[...document.querySelectorAll("[data-page]")];
  let n=pages[0];
  while(n&&n!==document.body){
    const s=getComputedStyle(n);
    if((s.overflowY==="auto"||s.overflowY==="scroll")&&n.scrollHeight>n.clientHeight+10)
      return JSON.stringify({cards:document.querySelectorAll("[data-book-id]").length,pages:pages.length,
        scrollable:true,tag:n.tagName,cls:String(n.className||"").slice(0,80),
        sh:n.scrollHeight,ch:n.clientHeight,top:n.scrollTop});
    n=n.parentElement;
  }
  return JSON.stringify({cards:document.querySelectorAll("[data-book-id]").length,pages:pages.length,
    scrollable:false,bodyH:document.body.scrollHeight,winH:innerHeight});
})()'''

JS_SCROLL = r'''(()=>{
  const k=__K__;
  const pages=[...document.querySelectorAll("[data-page]")];
  let n=pages[0];
  while(n&&n!==document.body){
    const s=getComputedStyle(n);
    if((s.overflowY==="auto"||s.overflowY==="scroll")&&n.scrollHeight>n.clientHeight+10){
      n.scrollTop+=n.clientHeight*k;
      n.dispatchEvent(new Event("scroll",{bubbles:true}));
      return JSON.stringify({top:n.scrollTop,sh:n.scrollHeight,ch:n.clientHeight,pages:pages.length});
    }
    n=n.parentElement;
  }
  return "NO_SCROLLABLE";})()'''


def js_open(book_id):
    return (r'''(()=>{const el=document.querySelector('[data-book-id="''' + str(book_id) + r'''"]');
if(!el)return "NO_CARD";
el.click();return "CLICKED "+el.getAttribute("data-book-id");})()''')


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    port = int(sys.argv[1])
    mode = sys.argv[2]
    targets = http_json(port)
    pages = [t for t in targets if t.get("type") == "page"]
    if mode == "list":
        for t in pages:
            print(json.dumps({k: t.get(k) for k in ("id", "title", "url", "webSocketDebuggerUrl")},
                             ensure_ascii=False))
        return
    page = None
    for t in pages:
        if "appassets.androidplatform.net" in (t.get("url") or ""):
            page = t
            break
    if page is None and pages:
        page = pages[0]
    if page is None:
        raise SystemExit("no page target: " + json.dumps(targets)[:500])

    if mode == "eval":
        expr = sys.argv[3]
    elif mode == "open":
        expr = js_open(sys.argv[3] if len(sys.argv) > 3 else "2")
    elif mode == "pages":
        expr = JS_PAGES
    elif mode == "scroll":
        expr = JS_SCROLL.replace("__K__", sys.argv[3] if len(sys.argv) > 3 else "0.95")
    else:
        raise SystemExit("unknown mode %r\n%s" % (mode, __doc__))

    wsurl = page["webSocketDebuggerUrl"].replace("ws://", "")
    hostport, _, path = wsurl.partition("/")
    host, _, p = hostport.partition(":")
    ws = WS(host, int(p), "/" + path)
    ws.send({"id": 1, "method": "Runtime.evaluate",
             "params": {"expression": expr, "returnByValue": True, "awaitPromise": True, "timeout": 5000}})
    while True:
        msg = ws.recv()
        if msg.get("id") == 1:
            print(json.dumps(msg.get("result", msg), ensure_ascii=False)[:4000])
            return


if __name__ == "__main__":
    main()
