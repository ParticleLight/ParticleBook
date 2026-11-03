#!/usr/bin/env python
"""p15-run.py - P1.5 真机验收的 adb 快捷封装（Windows python）。

子命令: setup / state / shot NAME / ui NAME / tap X Y / swipe x1 y1 x2 y2 ms /
        key N / text S / font F / dark on|off / log [N] / md5 / install PATH / start / stop
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

ADB = os.environ.get("ADB", r"G:\Android\sdk\platform-tools\adb.exe")
SER = os.environ.get("SER", "8A9Y0G2XJ")
MIRROR = os.environ.get("MIRROR", "http://127.0.0.1:8898")
OUT = Path(os.environ.get("OUT", r"F:/tmp/verify/p15"))


def sh(*args: str) -> str:
    p = subprocess.run([ADB, "-s", SER, *args], capture_output=True)
    return p.stdout.decode("utf-8", "ignore")


def shell(cmd: str) -> str:
    return sh("shell", cmd)


def dump_xml() -> str:
    s = sh("exec-out", "uiautomator", "dump", "/dev/tty")
    i = s.find("</hierarchy>")
    return s[: i + 12] if i > 0 else s


def nodes(xml: str, all_nodes: bool = False) -> list[dict]:
    rx = re.compile(r"<node\b[^>]*>")
    attr = re.compile(r'(text|content-desc|bounds|clickable|class|scrollable)="([^"]*)"')
    out = []
    for tag in rx.findall(xml):
        d = dict(attr.findall(tag))
        d["label"] = d.get("text") or d.get("content-desc") or ""
        if d["label"] or all_nodes:
            out.append(d)
    return out


def cmd_setup() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    shell("am force-stop app.particlebook")
    sh("reverse", "--remove", "tcp:8898")
    sh("reverse", "tcp:8898", "tcp:8898")
    shell("settings put global http_proxy :0")
    shell("settings put system font_scale 1.0")
    shell("settings put system accelerometer_rotation 0")
    shell("settings put system user_rotation 0")
    shell("logcat -c")
    apk = shell("pm path app.particlebook").strip().replace("package:", "")
    md5 = shell("run-as app.particlebook md5sum " + apk).strip().split()[0] if apk else "-"
    shell("am start -n app.particlebook/.android.MobileActivity --es pb_zlib_mirror " + MIRROR)
    time.sleep(4)
    print(json.dumps({"pid": shell("pidof app.particlebook").strip(), "installed_md5": md5,
                      "mirror": MIRROR, "font": shell("settings get system font_scale").strip()}))


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    a = sys.argv[1:]
    OUT.mkdir(parents=True, exist_ok=True)
    if a[0] == "setup":
        cmd_setup()
    elif a[0] == "state":
        print(json.dumps({
            "focus": re.sub(r"\s+", " ", shell("dumpsys window | grep -E 'mCurrentFocus'").strip()),
            "pid": shell("pidof app.particlebook").strip(),
            "font": shell("settings get system font_scale").strip(),
            "rotation": shell("settings get system user_rotation").strip(),
            "proxy": shell("settings get global http_proxy").strip(),
        }, ensure_ascii=False))
    elif a[0] == "shot":
        name = a[1] if len(a) > 1 else "shot"
        target = OUT / f"{name}.png"
        p = subprocess.run([ADB, "-s", SER, "exec-out", "screencap", "-p"], capture_output=True)
        target.write_bytes(p.stdout)
        print(str(target), len(p.stdout), "bytes")
    elif a[0] == "ui":
        name = a[1] if len(a) > 1 else "ui"
        xml = dump_xml()
        (OUT / f"{name}.xml").write_text(xml, encoding="utf-8")
        allf = "--all" in a
        for d in nodes(xml, allf):
            print(f"{d['label'][:70]} | {d.get('bounds','')} | clk={d.get('clickable')} scroll={d.get('scrollable')}")
    elif a[0] == "tap":
        shell(f"input tap {a[1]} {a[2]}")
        print("tap", a[1], a[2])
    elif a[0] == "swipe":
        shell(f"input swipe {a[1]} {a[2]} {a[3]} {a[4]} {a[5]}")
        print("swipe", a[1:6])
    elif a[0] == "key":
        shell(f"input keyevent {a[1]}")
        print("key", a[1])
    elif a[0] == "text":
        shell("input text " + a[1])
        shell("input keyevent 66")
        print("text", a[1])
    elif a[0] == "font":
        shell("settings put system font_scale " + a[1])
        shell("am force-stop app.particlebook")
        shell("am start -n app.particlebook/.android.MobileActivity --es pb_zlib_mirror " + MIRROR)
        time.sleep(4)
        print("font", a[1], "pid", shell("pidof app.particlebook").strip())
    elif a[0] == "dark":
        shell("cmd uimode night " + ("yes" if a[1] == "on" else "no"))
        shell("am force-stop app.particlebook")
        shell("am start -n app.particlebook/.android.MobileActivity --es pb_zlib_mirror " + MIRROR)
        time.sleep(4)
        print("night", a[1], "pid", shell("pidof app.particlebook").strip())
    elif a[0] == "log":
        n = int(a[1]) if len(a) > 1 else 400
        pid = shell("pidof app.particlebook").strip()
        out = sh("logcat", "-d", "--pid", pid, "-v", "brief")
        lines = [ln for ln in out.splitlines() if ("zlib" in ln or "PBPERF" in ln or "library" in ln or "exception" in ln.lower())]
        print("\n".join(lines[-n:]))
    elif a[0] == "md5":
        apk = shell("pm path app.particlebook").strip().replace("package:", "")
        print(shell("run-as app.particlebook md5sum " + apk).strip())
    elif a[0] == "install":
        p = subprocess.run([ADB, "-s", SER, "install", "-r", a[1]], capture_output=True)
        print(p.stdout.decode("utf-8", "ignore").strip(), p.stderr.decode("utf-8", "ignore").strip())
    elif a[0] == "start":
        shell("am start -n app.particlebook/.android.MobileActivity --es pb_zlib_mirror " + MIRROR)
        time.sleep(3)
        print("started", shell("pidof app.particlebook").strip())
    elif a[0] == "stop":
        shell("am force-stop app.particlebook")
        print("stopped")
    else:
        print("unknown", a[0])
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
