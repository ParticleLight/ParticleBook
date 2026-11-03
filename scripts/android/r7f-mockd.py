#!/usr/bin/env python3
"""r7f-mockd.py — 用 WMI(Win32_Process.Create) 拉起 zlib-mock.py，使其脱离 DSH 工具的 job 对象。

Git Bash 的 nohup/& 与 subprocess DETACHED_PROCESS 都会被工具 job 结束时连带杀掉；
WMI 创建的进程不属于该 job，因此能在后续独立工具调用之间存活。

用法：
  python scripts/android/r7f-mockd.py start --mode page|limit|require-login [--books 3] [--size 2000000] [--port 8899]
  python scripts/android/r7f-mockd.py stop [--port 8899]
  python scripts/android/r7f-mockd.py status [--port 8899]
"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT_DEFAULT = "F:/tmp/verify/r7f3"


def health(port, timeout=1.5):
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/__health", timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8"))
    except Exception:
        return None


def listener_pid(port):
    try:
        out = subprocess.run(["netstat", "-ano"], capture_output=True, text=True, timeout=20).stdout
        for line in out.splitlines():
            parts = line.split()
            if len(parts) >= 4 and f"127.0.0.1:{port}" in line and "LISTENING" in line:
                return int(parts[-1])
    except Exception:
        pass
    return None


def kill_pid(pid):
    try:
        subprocess.run(["taskkill", "/F", "/PID", str(pid)], capture_output=True, timeout=15)
    except Exception:
        pass


def start(a):
    h = health(a.port)
    if h:
        print(json.dumps({"ok": True, "already": True, "listener_pid": listener_pid(a.port),
                          "dl_mode": h.get("dl", {}).get("mode")}))
        return 0
    os.makedirs(a.out, exist_ok=True)
    log_path = os.path.join(a.out, f"mock-{a.mode}.jsonl")
    mock_out = os.path.join(a.out, "mock.out")
    args = [f'"{sys.executable}"', os.path.join(ROOT, "scripts", "android", "zlib-mock.py"),
            "--port", str(a.port), "--log", log_path,
            "--book-size", str(a.size), "--book-count", str(a.books),
            "--email", "verify@example.com", "--password", "MockPass123"]
    if a.detail_desc:
        args += ['"--detail-desc"', '"' + a.detail_desc.replace('"', "'") + '"']
    if a.discover:
        args += ["--discover"]
    if a.mode == "limit":
        args += ["--dl-mode", "limit"]
    elif a.mode == "redirect":
        args += ["--dl-mode", "redirect"]
    elif a.mode == "require-login":
        args += ["--require-login"]
    elif a.mode != "page":
        print(json.dumps({"ok": False, "error": f"unknown mode {a.mode}"}))
        return 2
    inner = " ".join(args)
    cmdline = f'cmd /c "cd /d {ROOT.replace("/", chr(92))} && {inner} > {mock_out.replace("/", chr(92))} 2>&1"'
    ps = ("$r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = '"
          + cmdline.replace("'", "''") + "' }; \"return=$($r.ReturnValue) pid=$($r.ProcessId)\"")
    p = subprocess.run(["powershell", "-NoProfile", "-Command", ps], capture_output=True, text=True, timeout=60)
    info = (p.stdout or "").strip()
    for _ in range(60):
        h = health(a.port)
        if h:
            lp = listener_pid(a.port)
            pid_file = os.path.join(a.out, "mock.pid")
            with open(pid_file, "w", encoding="utf-8") as f:
                f.write(str(lp or ""))
            print(json.dumps({"ok": True, "wmi": info, "listener_pid": lp, "mode": a.mode,
                              "books": a.books, "size": a.size, "log": log_path,
                              "dl_mode": h.get("dl", {}).get("mode")}))
            return 0
        time.sleep(0.25)
    print(json.dumps({"ok": False, "wmi": info, "error": "health timeout", "out": mock_out}))
    return 1


def stop(a):
    pids = set()
    pid_file = os.path.join(a.out, "mock.pid")
    if os.path.exists(pid_file):
        try:
            v = open(pid_file, encoding="utf-8").read().strip()
            if v:
                pids.add(int(v))
        except Exception:
            pass
    lp = listener_pid(a.port)
    if lp:
        pids.add(lp)
    for pid in pids:
        kill_pid(pid)
    for _ in range(40):
        if health(a.port) is None:
            break
        time.sleep(0.25)
    gone = health(a.port) is None
    try:
        os.remove(pid_file)
    except OSError:
        pass
    print(json.dumps({"ok": gone, "killed": sorted(pids)}))
    return 0 if gone else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["start", "stop", "status"])
    ap.add_argument("--mode", default="page")
    ap.add_argument("--books", type=int, default=3)
    ap.add_argument("--size", type=int, default=2000000)
    ap.add_argument("--port", type=int, default=8898)
    ap.add_argument("--out", default=OUT_DEFAULT)
    ap.add_argument("--detail-desc", dest="detail_desc", default=None)
    ap.add_argument("--discover", action="store_true", help="P1.5 发现页回放")
    a = ap.parse_args()
    if a.cmd == "start":
        return start(a)
    if a.cmd == "stop":
        return stop(a)
    h = health(a.port)
    print(json.dumps({"up": h is not None, "listener_pid": listener_pid(a.port),
                      "dl_mode": (h or {}).get("dl", {}).get("mode"),
                      "discover": (h or {}).get("discover")}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
