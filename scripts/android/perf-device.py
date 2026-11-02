#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""真机性能采集辅助（R6-B 用）。只依赖 adb，不修改 app。

用法：
  python scripts/android/perf-device.py --serial 100.67.2.193:35805 state
  python scripts/android/perf-device.py --serial ... pss
  python scripts/android/perf-device.py --serial ... coldstart --n 5
  python scripts/android/perf-device.py --serial ... jank --reset
  python scripts/android/perf-device.py --serial ... jank --report
  python scripts/android/perf-device.py --serial ... logcat --pattern 'page_render' --seconds 20

pss        : am force-stop 后冷启到书架，等 4s，读 TOTAL PSS（MB）
coldstart  : 重复 am force-stop + am start -W，打印每次 TotalTime
jank       : --reset 清 gfxinfo；--report 解析 gfxinfo/framestats 给出 jank% 与 p50/p95/max 帧耗时
state      : 打印前台 activity、包版本、lastUpdateTime
logcat     : 清 logcat → 等待 N 秒并抓取匹配行（采集渲染埋点用）

约定：PSS 统一取 dumpsys meminfo <pkg> 的 "TOTAL" 行；每次测 PSS 前 am force-stop。
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import time


def _resolve_adb():
    """Windows 原生 python 不能直接用 /g/... 这类 MSYS 路径，逐个尝试。"""
    cands = [os.environ.get("ADB", ""), "adb", "adb.exe",
             r"G:\\Android\\sdk\\platform-tools\\adb.exe",
             r"C:\\Android\\sdk\\platform-tools\\adb.exe"]
    for c in cands:
        if not c:
            continue
        p = shutil.which(c) if not os.path.isabs(c) else c
        if p and os.path.exists(p):
            return p
    return "adb"


ADB = _resolve_adb()
PKG = "app.particlebook"
ACT = "app.particlebook/.android.MobileActivity"


def adb(serial, *args, timeout=90):
    cmd = [ADB]
    if serial:
        cmd += ["-s", serial]
    cmd += list(args)
    out = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    return out.stdout or ""


def sh(serial, *cmd, timeout=90):
    return adb(serial, "shell", *cmd, timeout=timeout)


def pss_mb(serial):
    out = sh(serial, "dumpsys", "meminfo", PKG)
    m = re.search(r"^\s*TOTAL\s+([0-9]+)", out, re.M)
    if not m:
        return None
    return int(m.group(1)) / 1024.0


def cmd_pss(serial):
    sh(serial, "am", "force-stop", PKG)
    time.sleep(0.6)
    sh(serial, "am", "start", "-n", ACT)
    time.sleep(4.0)
    total = pss_mb(serial)
    if total is None:
        print("PSS: NOT FOUND (app not running?)")
        return
    print("PSS(now) = %.1f MB" % total)


def cmd_coldstart(serial, n):
    vals = []
    for i in range(n):
        sh(serial, "am", "force-stop", PKG)
        time.sleep(0.5)
        out = sh(serial, "am", "start", "-W", "-n", ACT)
        m = re.search(r"TotalTime:\s*([0-9]+)", out)
        if m:
            vals.append(int(m.group(1)))
            print("run %d: TotalTime=%sms" % (i + 1, m.group(1)))
        else:
            print("run %d: no TotalTime (output: %s)" % (i + 1, out.strip()[:120]))
        time.sleep(1.0)
    if vals:
        vals_sorted = sorted(vals)
        p = lambda q: vals_sorted[min(len(vals_sorted) - 1, int(q * len(vals_sorted)))]
        print("cold start: %s ms | min %d / med %d / max %d" % (vals, vals_sorted[0], p(0.5), vals_sorted[-1]))


def parse_framestats(out):
    """从 gfxinfo framestats 输出解析每帧耗时(ns)与 jank 标志。"""
    rows = []
    in_data = False
    header = None
    for line in out.splitlines():
        line = line.strip()
        if not line:
            continue
        if line.startswith("---PROFILEDATA---"):
            in_data = not in_data
            if in_data:
                header = None
            continue
        if not in_data:
            continue
        parts = [p for p in line.split(",") if p != ""]
        if parts and parts[0] == "Flags":
            header = parts
            continue
        if header and len(parts) >= 15:
            try:
                d = dict(zip(header, parts))
                intended = int(d.get("IntendedVsync", "0"))
                completed = int(d.get("FrameCompleted", "0"))
                flags = int(d.get("Flags", "0"))
                dur = (completed - intended) / 1e6  # ms
                janky = bool(flags & 1) or dur > 16.67
                rows.append((dur, janky))
            except Exception:
                continue
    return rows


def cmd_jank(serial, reset, report, raw):
    if reset:
        sh(serial, "dumpsys", "gfxinfo", PKG, "reset")
        print("gfxinfo reset ok")
        return
    if not report:
        print("need --reset or --report")
        return
    out = sh(serial, "dumpsys", "gfxinfo", PKG, timeout=120)
    if raw:
        print(out)
    m = re.search(r"Janky frames?[^:\n]*:\s*([0-9.]+)%", out)
    total_frames = None
    mf = re.search(r"Total frames rendered:\s*([0-9]+)", out)
    if mf:
        total_frames = int(mf.group(1))
    if m:
        print("gfxinfo Janky frames: %s%% (total frames %s)" % (m.group(1), total_frames))

    # framestats 优先
    out2 = sh(serial, "dumpsys", "gfxinfo", PKG, "framestats", timeout=180)
    rows = parse_framestats(out2)
    if rows:
        durs = sorted(r[0] for r in rows)
        janky = sum(1 for r in rows if r[1])
        n = len(durs)
        p50 = durs[int(0.50 * (n - 1))]
        p95 = durs[int(0.95 * (n - 1))]
        print("framestats: frames=%d janky=%d (%.2f%%) p50=%.2fms p95=%.2fms max=%.2fms" %
              (n, janky, 100.0 * janky / n, p50, p95, durs[-1]))
        print("  (jank = Flags&1 或帧耗时 >16.67ms，60Hz)" )
    elif not m:
        print("no framestats / jank info. first 300 chars:")
        print(out2[:300])


def cmd_state(serial):
    act_out = sh(serial, "dumpsys", "activity", "activities")
    m = re.search(r"(mResumedActivity|topResumedActivity)[^\n]*", act_out)
    print("foreground:", m.group(0).strip() if m else "unknown")
    pkg_out = sh(serial, "dumpsys", "package", PKG)
    for key in ("versionName", "lastUpdateTime", "debuggable"):
        mm = re.search(r"%s=([^\n]+)" % key, pkg_out)
        print("%s: %s" % (key, mm.group(1).strip() if mm else "-"))


def cmd_logcat(serial, pattern, seconds, out_file):
    adb(serial, "logcat", "-c")
    print("logcat cleared; waiting %ss ..." % seconds)
    time.sleep(seconds)
    out = adb(serial, "logcat", "-d", timeout=120)
    lines = [l for l in out.splitlines() if pattern and re.search(pattern, l)]
    print("matched %d lines" % len(lines))
    for l in lines[-40:]:
        print(l)
    if out_file:
        with open(out_file, "w", encoding="utf-8") as f:
            f.write("\n".join(lines))
        print("saved ->", out_file)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("pss")
    p = sub.add_parser("coldstart")
    p.add_argument("--n", type=int, default=5)
    p = sub.add_parser("jank")
    p.add_argument("--reset", action="store_true")
    p.add_argument("--report", action="store_true")
    p.add_argument("--raw", action="store_true")
    sub.add_parser("state")
    p = sub.add_parser("logcat")
    p.add_argument("--pattern", default="")
    p.add_argument("--seconds", type=int, default=15)
    p.add_argument("--out", default="")
    a = ap.parse_args()
    if a.cmd == "pss":
        cmd_pss(a.serial)
    elif a.cmd == "coldstart":
        cmd_coldstart(a.serial, a.n)
    elif a.cmd == "jank":
        cmd_jank(a.serial, a.reset, a.report, a.raw)
    elif a.cmd == "state":
        cmd_state(a.serial)
    elif a.cmd == "logcat":
        cmd_logcat(a.serial, a.pattern, a.seconds, a.out)


if __name__ == "__main__":
    main()
