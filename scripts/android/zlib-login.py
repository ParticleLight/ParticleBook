#!/usr/bin/env python3
"""Z-Library 真机登录助手（Pixel 3 XL / 任意 adb 设备）。

用途：mock E2E 每轮 pm clear 会清掉登录态，重新登录需要「英文 IME + 定位输入框 + 输入」，
手工做容易把密码输进邮箱框。本脚本用 uiautomator dump 定位 EditText，按顺序填邮箱/密码。

用法：
  export ZLIB_EMAIL='you@example.com'
  export ZLIB_PW='...'            # 不要写进命令行参数，避免进 shell history
  python scripts/android/zlib-login.py [--serial 8A9Y0G2XJ] [--verbose]

依赖：adb（ANDROID_SDK 或 PATH），app 已安装（app.particlebook/.android.MobileActivity）。
退出码：0=成功（检测到账号出现在 Z-Library 页），1=失败。
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

FUTO_IME = "org.futo.inputmethod.latin.playstore/org.futo.inputmethod.latin.LatinIME"
PKG = "app.particlebook"
ACT = "app.particlebook/.android.MobileActivity"


def find_adb() -> str:
    for cand in (os.environ.get("ADB"), r"G:\Android\sdk\platform-tools\adb.exe", "adb"):
        if not cand:
            continue
        try:
            out = subprocess.run([cand, "version"], capture_output=True, text=True, timeout=20)
            if out.returncode == 0:
                return cand
        except Exception:
            continue
    raise SystemExit("adb not found (set ADB env var)")


class Dev:
    def __init__(self, adb: str, serial: str | None, verbose: bool = False):
        self.adb = adb
        self.serial = serial
        self.verbose = verbose

    def sh(self, *args: str, timeout: int = 40) -> str:
        cmd = [self.adb] + (["-s", self.serial] if self.serial else []) + list(args)
        if self.verbose:
            print("+", " ".join(cmd), file=sys.stderr)
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        if p.returncode != 0 and self.verbose:
            print("  ! rc=%d %s" % (p.returncode, (p.stderr or "").strip()[:200]), file=sys.stderr)
        return p.stdout

    def shell(self, cmd: str, timeout: int = 40) -> str:
        return self.sh("shell", cmd, timeout=timeout)

    def tap(self, x: int, y: int) -> None:
        self.shell(f"input tap {x} {y}")
        time.sleep(0.6)

    def text(self, value: str) -> None:
        # adb input text 不支持空格与部分符号
        safe = value.replace(" ", "%s")
        self.shell("input text '%s'" % safe)
        time.sleep(0.4)

    def key(self, code: int, times: int = 1) -> None:
        for _ in range(times):
            self.shell(f"input keyevent {code}")
        time.sleep(0.2)

    def dump(self) -> ET.Element:
        for _ in range(6):
            self.shell("uiautomator dump /sdcard/zlg.xml >/dev/null 2>&1")
            raw = self.shell("cat /sdcard/zlg.xml")
            if raw.strip().startswith("<?xml"):
                try:
                    return ET.fromstring(raw)
                except ET.ParseError:
                    pass
            time.sleep(0.8)
        raise RuntimeError("uiautomator dump failed")


BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def center(node: ET.Element) -> tuple[int, int] | None:
    b = node.get("bounds") or ""
    m = BOUNDS.match(b)
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def find_nodes(root: ET.Element, *, cls: str | None = None, text: str | None = None,
               contains: str | None = None, desc: str | None = None) -> list[ET.Element]:
    out = []
    for n in root.iter("node"):
        if cls and n.get("class") != cls:
            continue
        t = n.get("text") or ""
        if text is not None and t != text:
            continue
        if contains is not None and contains not in t:
            continue
        if desc is not None and (n.get("content-desc") or "") != desc:
            continue
        out.append(n)
    return out


def click_first(d: Dev, root: ET.Element, **kw) -> bool:
    nodes = [n for n in find_nodes(root, **kw) if center(n)]
    if not nodes:
        return False
    x, y = center(nodes[0])
    d.tap(x, y)
    return True


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=os.environ.get("SERIAL") or None)
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    email = os.environ.get("ZLIB_EMAIL", "").strip()
    pw = os.environ.get("ZLIB_PW", "")
    if not email or not pw:
        print("ZLIB_EMAIL / ZLIB_PW 未设置", file=sys.stderr)
        return 1

    d = Dev(find_adb(), args.serial, args.verbose)
    st = d.sh("get-state").strip()
    print("device state:", st)
    if st != "device":
        return 1

    # 1) 英文 IME（中文 IME 会把 @ 吞成候选词）
    d.shell(f"ime set {FUTO_IME}")

    # 2) 冷启 app
    d.shell(f"am force-stop {PKG}")
    time.sleep(1)
    d.shell(f"am start -W -n {ACT}")
    time.sleep(3)

    # 3) 打开 Z-Library 页（底栏中间项，按屏宽 3 等分定位）
    size = d.shell("wm size").strip()
    m = re.search(r"(\d+)x(\d+)", size)
    w, h = (int(m.group(1)), int(m.group(2))) if m else (1440, 2960)
    d.tap(w // 2, int(h * 0.885))
    time.sleep(2.5)

    root = d.dump()
    # 已在登录态：右上角显示邮箱
    if find_nodes(root, contains=email):
        print("already logged in")
        d.shell("ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME 2>/dev/null")
        return 0

    # 4) 点「登录」（Z-Library 页右上）
    if not (click_first(d, root, text="登录") or click_first(d, root, contains="登录")):
        print("cannot find login entry; dump text nodes:", file=sys.stderr)
        for n in find_nodes(root):
            t = (n.get("text") or "").strip()
            if t:
                print("  ", t[:40], n.get("bounds"), file=sys.stderr)
        return 1
    time.sleep(2.5)

    # 5) 填表
    root = d.dump()
    edits = [n for n in find_nodes(root, cls="android.widget.EditText") if center(n)]
    if len(edits) < 2:
        print("expect >=2 EditText, got %d" % len(edits), file=sys.stderr)
        return 1
    edits.sort(key=lambda n: center(n)[1])
    for node, value in ((edits[0], email), (edits[1], pw)):
        x, y = center(node)
        d.tap(x, y)
        time.sleep(0.5)
        d.key(123)  # move to end
        d.key(67, 60)  # clear
        d.text(value)
    time.sleep(0.5)
    d.key(4)  # hide IME

    # 6) 提交
    root = d.dump()
    if not (click_first(d, root, text="登录") or click_first(d, root, contains="登录")):
        print("cannot find submit button", file=sys.stderr)
        return 1
    time.sleep(6)

    # 7) 校验：回到 Z-Library 页应显示邮箱
    root = d.dump()
    ok = bool(find_nodes(root, contains=email))
    # 恢复 Gboard
    d.shell("ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME >/dev/null 2>&1")
    print("login:", "OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
