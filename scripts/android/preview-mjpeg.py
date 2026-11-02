#!/usr/bin/env python
"""Android 设备实时画面（MJPEG）服务器，用于在 VSCode Simple Browser / 浏览器里预览模拟器。

用法：
  python scripts/android/preview-mjpeg.py [--port 8765] [--serial emulator-5554] [--width 540] [--fps 6]

然后 VSCode 命令面板 -> "Simple Browser: Show" -> http://127.0.0.1:8765/
"""
import argparse, io, subprocess, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from PIL import Image

ADB = r"G:\\Android\\sdk\\platform-tools\\adb.exe"
_state = {"jpeg": None, "w": 0, "h": 0, "fps": 0.0, "err": ""}
_lock = threading.Lock()


def capture_loop(serial, width, fps):
    import os
    adb = os.environ.get("PB_ADB", ADB)
    interval = 1.0 / max(fps, 1)
    frames = 0
    t0 = time.time()
    while True:
        start = time.time()
        try:
            raw = subprocess.run([adb, "-s", serial, "exec-out", "screencap", "-p"],
                                 capture_output=True, timeout=15).stdout
            if raw[:8] != b"\x89PNG\r\n\x1a\n":
                _state["err"] = "screencap 返回非 PNG（设备离线？）"
                time.sleep(1.0)
                continue
            im = Image.open(io.BytesIO(raw)).convert("RGB")
            if im.width > width:
                im = im.resize((width, int(im.height * width / im.width)), Image.LANCZOS)
            buf = io.BytesIO()
            im.save(buf, format="JPEG", quality=72)
            with _lock:
                _state["jpeg"] = buf.getvalue()
                _state["w"], _state["h"] = im.size
                _state["err"] = ""
            frames += 1
            if frames % 10 == 0:
                _state["fps"] = 10.0 / max(time.time() - t0, 1e-6)
                t0 = time.time()
        except Exception as exc:  # noqa: BLE001
            _state["err"] = str(exc)[:200]
            time.sleep(1.0)
        time.sleep(max(0.0, interval - (time.time() - start)))


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # 静音
        pass

    def do_GET(self):
        if self.path.startswith("/stream"):
            self.send_response(200)
            self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=frame")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            try:
                while True:
                    with _lock:
                        jpeg = _state["jpeg"]
                    if jpeg:
                        self.wfile.write(b"--frame\r\nContent-Type: image/jpeg\r\nContent-Length: "
                                         + str(len(jpeg)).encode() + b"\r\n\r\n")
                        self.wfile.write(jpeg)
                        self.wfile.write(b"\r\n")
                    time.sleep(1 / 30)
            except (BrokenPipeError, ConnectionResetError):
                return
        else:
            body = ("""<!doctype html><meta charset=utf-8>
<title>ParticleBook Android 预览</title>
<style>body{margin:0;background:#111;color:#ddd;font:13px system-ui;display:flex;flex-direction:column;height:100vh}
header{padding:6px 12px;background:#1b1b1b;display:flex;gap:16px;align-items:center}
img{flex:1;object-fit:contain;min-height:0;width:100%}</style>
<header><b>ParticleBook · Android 实时画面</b><span id=s>连接中…</span></header>
<img src="/stream" alt="device screen">
<script>const i=document.querySelector('img');setInterval(async()=>{try{const r=await fetch('/stat');const j=await r.json();document.getElementById('s').textContent=j.w+'x'+j.h+' · '+j.fps.toFixed(1)+' fps'+(j.err?(' · '+j.err):'')}catch(e){}},1500)</script>""")
            data = body.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    def do_GET_stat(self):
        pass


class StatHandler(Handler):
    def do_GET(self):
        if self.path.startswith("/stat"):
            import json
            with _lock:
                payload = json.dumps({"w": _state["w"], "h": _state["h"], "fps": _state["fps"], "err": _state["err"]}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        else:
            super().do_GET()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--serial", default="emulator-5554")
    ap.add_argument("--width", type=int, default=540)
    ap.add_argument("--fps", type=float, default=6.0)
    args = ap.parse_args()
    threading.Thread(target=capture_loop, args=(args.serial, args.width, args.fps), daemon=True).start()
    srv = ThreadingHTTPServer(("127.0.0.1", args.port), StatHandler)
    print(f"http://127.0.0.1:{args.port}/  (serial={args.serial}, width={args.width}, fps={args.fps})", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
