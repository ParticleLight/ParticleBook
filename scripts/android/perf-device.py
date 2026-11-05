#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""真机性能基准采集（P0-7，release 口径；P1 门禁用）。

只依赖 adb，不修改 App。口径见 docs/android-port/parity/research-benchmark.md：
- release 包优先；稳定内存 = TOTAL - Graphics；n>=5 取中位 + P95；同批对照。
- 冷启：am force-stop -> am start -W 取 TotalTime；另取 logcat ActivityTaskManager 的 Displayed。
- PSS：dumpsys meminfo 解析 TOTAL/Java/Native/Graphics/Code/Stack。
- PDF：uiautomator dump 定位书架 PDF -> 点击 -> logcat PBPERF(pdf_open/page_render/page_present/cache_stat)
  -> 阅读 PSS -> 返回 -> 退出 PSS（验证释放）。
- 帧：dumpsys gfxinfo reset/report + framestats。
- Perfetto / batterystats 为可选场景采集。

用法（在 worktree 根目录；设备操作建议经 lock.sh emu 包裹）：
  python scripts/android/perf-device.py --serial 8A9Y0G2XJ state
  python scripts/android/perf-device.py --serial 8A9Y0G2XJ coldstart --n 5 --json-out docs/android-port/p0/evidence/coldstart.json
  python scripts/android/perf-device.py --serial 8A9Y0G2XJ pss --json-out .../pss.json
  python scripts/android/perf-device.py --serial 8A9Y0G2XJ pdf --json-out .../pdf.json
  python scripts/android/perf-device.py --serial 8A9Y0G2XJ jank --json-out .../jank.json
  python scripts/android/perf-device.py --serial 8A9Y0G2XJ battery --seconds 60 --json-out .../battery.json
  python scripts/android/perf-device.py --serial 8A9Y0G2XJ perfetto --seconds 10 --out .../trace.pftrace
  python scripts/android/perf-device.py --serial 8A9Y0G2XJ m0 --epub docs/android-port/fixtures/books/standardebooks/herman-melville_moby-dick.epub --chapter 6
  python scripts/android/perf-device.py --serial 8A9Y0G2XJ libs --apk android/app/build/outputs/apk/debug/app-debug.apk
  python scripts/android/perf-device.py --serial 8A9Y0G2XJ baseline --sections coldstart,pss,pdf,jank --json-out .../baseline.json
"""
import argparse
import hashlib
import json
import math
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile
from xml.etree import ElementTree as ET

PKG = "app.particlebook"
ACT = "app.particlebook/.android.MobileActivity"
DEFAULT_EVIDENCE = os.path.join("docs", "android-port", "p0", "evidence")
DEFAULT_BOOK_CANDIDATES = ["统计之美（第二册）", "统计之美", "故乡的河"]


def _resolve_adb():
    cands = [os.environ.get("ADB", ""), "adb", "adb.exe",
             "G:/Android/sdk/platform-tools/adb.exe",
             "C:/Android/sdk/platform-tools/adb.exe"]
    for c in cands:
        if not c:
            continue
        p = shutil.which(c) if not os.path.isabs(c) else c
        if p and os.path.exists(p):
            return p
    return "adb"


ADB = _resolve_adb()


def adb(serial, *args, timeout=180):
    cmd = [ADB]
    if serial:
        cmd += ["-s", serial]
    cmd += list(args)
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
        return out.stdout or ""
    except subprocess.TimeoutExpired:
        print("[warn] adb timeout: %s" % (" ".join(str(a) for a in args)[:120]), file=sys.stderr)
        return ""


def sh(serial, *args, timeout=180):
    return adb(serial, "shell", *args, timeout=timeout)


def now_iso():
    return time.strftime("%Y-%m-%dT%H:%M:%S")


def pct(values, q):
    """nearest-rank 分位。"""
    if not values:
        return None
    vs = sorted(values)
    idx = max(0, min(len(vs) - 1, int(math.ceil(q * len(vs))) - 1))
    return vs[idx]


def stats(values):
    vs = [v for v in values if isinstance(v, (int, float))]
    if not vs:
        return {"n": 0}
    return {
        "n": len(vs),
        "min": round(min(vs), 1),
        "median": round(pct(vs, 0.5), 1),
        "p95": round(pct(vs, 0.95), 1),
        "max": round(max(vs), 1),
        "samples": [round(v, 1) for v in vs],
    }


def dump_json(path, obj):
    if not path:
        return
    d = os.path.dirname(path)
    if d:
        os.makedirs(d, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)
    print("json ->", path)


def mb(kb):
    return round(int(kb) / 1024.0, 1)


# ---------- device / app facts ----------

def device_info(serial):
    def prop(k):
        return sh(serial, "getprop", k).strip()

    return {
        "model": prop("ro.product.model"),
        "sdk": prop("ro.build.version.sdk"),
        "release": prop("ro.build.version.release"),
        "abi": prop("ro.product.cpu.abi"),
        "page_size": sh(serial, "getconf", "PAGE_SIZE").strip(),
        "mem_total": sh(serial, "cat", "/proc/meminfo").splitlines()[0].strip() if sh(serial, "cat", "/proc/meminfo") else "",
        "device_time": now_iso(),
    }


def app_info(serial):
    out = sh(serial, "dumpsys", "package", PKG)
    def grab(key):
        m = re.search(r"%s=([^\n]+)" % key, out)
        return m.group(1).strip() if m else None
    path = sh(serial, "pm", "path", PKG).strip()
    apk = path.split(":", 1)[1] if path.startswith("package:") else ""
    sha = sh(serial, "sha256sum", apk).strip().split()[0] if apk else None
    vc = grab("versionCode")
    mvc = re.match(r"([0-9]+)", vc or "")
    flags = re.search(r"flags=\[([^\]]*)\]", out)
    return {
        "versionName": grab("versionName"),
        "versionCode": mvc.group(1) if mvc else vc,
        "lastUpdateTime": grab("lastUpdateTime"),
        "debuggable": ("DEBUGGABLE" in (flags.group(1) if flags else "")),
        "apk_path": apk,
        "installed_sha256": sha,
    }


# ---------- memory ----------

def meminfo(serial):
    out = sh(serial, "dumpsys", "meminfo", PKG, timeout=180)
    vals = {}
    for key in ("TOTAL", "Java Heap", "Native Heap", "Graphics", "Code", "Stack", "Private Other", "System"):
        m = re.search(r"^\s*" + re.escape(key) + r":?\s+([0-9]+)", out, re.M)
        if m:
            vals[key] = mb(m.group(1))
    total = vals.get("TOTAL")
    gfx = vals.get("Graphics", 0.0) or 0.0
    vals["stable_mb"] = round(total - gfx, 1) if total is not None else None
    vals["source"] = "dumpsys meminfo (KB/1024)；stable = TOTAL - Graphics"
    return vals


def app_foreground(serial):
    out = sh(serial, "dumpsys", "activity", "activities")
    m = re.search(r"(?:mResumedActivity|topResumedActivity)[^\n]*", out)
    return m.group(0).strip() if m else ""


# ---------- cold start ----------

def parse_displayed(log_text):
    m = re.search(r"Displayed app\.particlebook/[^:]*:\s*\+([0-9]+)s([0-9]+)ms", log_text)
    if m:
        return int(m.group(1)) * 1000 + int(m.group(2))
    m = re.search(r"Displayed app\.particlebook/[^:]*:\s*\+([0-9]+)ms", log_text)
    return int(m.group(1)) if m else None


def cmd_coldstart(serial, n, settle, json_out, apk_note=None):
    vals, waits, displayeds, launches = [], [], [], []
    for i in range(n):
        sh(serial, "am", "force-stop", PKG)
        time.sleep(0.6)
        sh(serial, "logcat", "-c")
        out = sh(serial, "am", "start", "-W", "-n", ACT, timeout=120)
        m = re.search(r"TotalTime:\s*([0-9]+)", out)
        w = re.search(r"WaitTime:\s*([0-9]+)", out)
        ls = re.search(r"LaunchState:\s*(\w+)", out)
        if m:
            vals.append(int(m.group(1)))
        if w:
            waits.append(int(w.group(1)))
        if ls:
            launches.append(ls.group(1))
        time.sleep(0.4)
        log = sh(serial, "logcat", "-d", "-s", "ActivityTaskManager:I")
        displayeds.append(parse_displayed(log))
        print("run %d: TotalTime=%s WaitTime=%s LaunchState=%s Displayed=%s" % (
            i + 1,
            m.group(1) if m else None,
            w.group(1) if w else None,
            ls.group(1) if ls else None,
            displayeds[-1],
        ))
        time.sleep(max(0.0, settle - 0.4))
    res = {
        "cmd": "coldstart",
        "device": device_info(serial),
        "app": app_info(serial),
        "apk_note": apk_note,
        "total_time_ms": stats(vals),
        "wait_time_ms": stats(waits),
        "displayed_ms": stats([d for d in displayeds if d is not None]),
        "launch_states": launches,
        "invalid": len(vals) < n,
        "note": "am start -W 在锁屏/休眠下会返回 LaunchState UNKNOWN 且无 TotalTime，样本作废",
    }
    s = res["total_time_ms"]
    print("cold start TotalTime: n=%s med=%sms p95=%sms min=%s max=%s" % (s.get("n"), s.get("median"), s.get("p95"), s.get("min"), s.get("max")))
    dump_json(json_out, res)
    return res


# ---------- pss ----------

def ensure_app(serial, settle):
    fg = app_foreground(serial)
    if PKG not in fg:
        sh(serial, "am", "start", "-n", ACT)
    time.sleep(settle)


def cmd_pss(serial, settle, json_out, force_stop=True):
    if force_stop:
        sh(serial, "am", "force-stop", PKG)
        time.sleep(0.6)
        sh(serial, "am", "start", "-n", ACT)
    time.sleep(settle)
    fg = app_foreground(serial)
    res = {
        "cmd": "pss",
        "kind": "shelf_cold" if force_stop else "foreground",
        "device": device_info(serial),
        "app": app_info(serial),
        "foreground": fg,
        "meminfo": meminfo(serial),
        "settle_s": settle,
    }
    mi = res["meminfo"]
    print("PSS: TOTAL=%sMB stable(TOTAL-Graphics)=%sMB Java=%s Native=%s Graphics=%s" % (
        mi.get("TOTAL"), mi.get("stable_mb"), mi.get("Java Heap"), mi.get("Native Heap"), mi.get("Graphics")))
    dump_json(json_out, res)
    return res


# ---------- jank ----------

def parse_framestats(out):
    rows, in_data, header = [], False, None
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
                dur = (completed - intended) / 1e6
                rows.append((dur, bool(flags & 1) or dur > 16.67))
            except Exception:
                continue
    return rows


def cmd_jank(serial, mode, scenario, swipes, json_out, settle=1.0, book=""):
    sh(serial, "am", "force-stop", PKG)
    time.sleep(0.6)
    sh(serial, "am", "start", "-n", ACT)
    time.sleep(max(2.0, settle))
    opened = None
    if scenario == "reader":
        opened = open_pdf_book(serial, book, settle=max(2.0, settle))
        if not opened.get("picked"):
            print("jank: reader scenario 打不开 PDF ->", opened.get("error"))
            return {"cmd": "jank", "error": "reader open failed"}
        time.sleep(3.0)
    if mode == "reset":
        sh(serial, "dumpsys", "gfxinfo", PKG, "reset")
        print("gfxinfo reset ok")
        return
    res = {"cmd": "jank", "scenario": scenario, "book": (opened or {}).get("picked"),
           "device": device_info(serial), "app": app_info(serial), "swipes": swipes}
    if mode in ("report", "auto"):
        sh(serial, "dumpsys", "gfxinfo", PKG, "reset")
        for _ in range(swipes):
            sh(serial, "input", "swipe", "720", "2350", "720", "650", "150")
            time.sleep(0.35)
        sh(serial, "input", "swipe", "720", "900", "720", "2400", "150")
        time.sleep(0.8)
        summary = sh(serial, "dumpsys", "gfxinfo", PKG)
        m = re.search(r"Total frames rendered:\s*([0-9]+)", summary)
        j = re.search(r"Janky frames[^:\n]*:\s*([0-9]+)\s*\(([0-9.]+)%\)", summary)
        res["total_frames"] = int(m.group(1)) if m else None
        res["janky_percent_gfxinfo"] = float(j.group(2)) if j else None
        res["gfxinfo_percentiles"] = {}
        for q, rx in (("50th", r"50th percentile:\s*([0-9]+)ms"), ("90th", r"90th percentile:\s*([0-9]+)ms"),
                      ("95th", r"95th percentile:\s*([0-9]+)ms"), ("99th", r"99th percentile:\s*([0-9]+)ms")):
            mm = re.search(rx, summary)
            if mm:
                res["gfxinfo_percentiles"][q] = int(mm.group(1))
        frames = parse_framestats(sh(serial, "dumpsys", "gfxinfo", PKG, "framestats"))
        if frames:
            durs = [f[0] for f in frames]
            res["framestats"] = {
                "frames": len(durs),
                "p50": round(pct(durs, 0.5), 2),
                "p95": round(pct(durs, 0.95), 2),
                "p99": round(pct(durs, 0.99), 2),
                "max": round(max(durs), 2),
                "over_16ms_pct": round(100.0 * sum(1 for d in durs if d > 16.67) / len(durs), 2),
                "over_33ms_pct": round(100.0 * sum(1 for d in durs if d > 33.34) / len(durs), 2),
            }
        if opened:
            evs = perf_events(serial)
            res["page_turns"] = len([e for e in evs if e.get("phase") == "page_present"])
            res["turn_ms"] = stats([int(e["ms"]) for e in evs if e.get("phase") == "turn" and e.get("ms", "").isdigit()])
        print(json.dumps({k: res[k] for k in ("scenario", "book", "total_frames", "janky_percent_gfxinfo", "framestats", "page_turns", "turn_ms") if k in res}, ensure_ascii=False))
    dump_json(json_out, res)
    return res


# ---------- ui automation (PDF scenario) ----------

def ui_dump(serial):
    last = ""
    for _ in range(3):
        out = adb(serial, "exec-out", "uiautomator", "dump", "/dev/tty", timeout=60)
        s = out or ""
        i = s.rfind("</hierarchy>")
        if i > 0:
            last = s[:i + len("</hierarchy>")]
            break
        time.sleep(0.4)
    nodes = []
    try:
        root = ET.fromstring(last)
        for n in root.iter("node"):
            b = n.get("bounds") or ""
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
            nodes.append({
                "text": (n.get("text") or "").strip(),
                "desc": (n.get("content-desc") or "").strip(),
                "cls": n.get("class") or "",
                "clickable": n.get("clickable") == "true",
                "rect": [int(x) for x in m.groups()] if m else None,
            })
    except Exception:
        pass
    return nodes


def find_node(nodes, sub):
    for n in nodes:
        if sub and (sub in n["text"] or sub in n["desc"]):
            return n
    return None


def tap_node(serial, node):
    x = (node["rect"][0] + node["rect"][2]) // 2
    y = (node["rect"][1] + node["rect"][3]) // 2
    sh(serial, "input", "tap", str(x), str(y))
    return x, y


def merge_events(*lists):
    seen, out = set(), []
    for lst in lists:
        for e in lst:
            key = e.get("raw") or json.dumps(e, ensure_ascii=False, sort_keys=True)
            if key in seen:
                continue
            seen.add(key)
            out.append(e)
    return out


def perf_events(serial):
    out = sh(serial, "logcat", "-d", "-s", "PBPERF:I")
    ev = []
    for line in out.splitlines():
        if "PBPERF|" not in line:
            continue
        body = line.split("PBPERF|", 1)[1]
        d = {"raw": line.strip()}
        for part in body.split("|"):
            if "=" in part:
                k, v = part.split("=", 1)
                d[k] = v
        m = re.search(r"\bpage:(\d+)", body)
        if m:
            d["page"] = m.group(1)
        ev.append(d)
    return ev


def open_pdf_book(serial, book, settle=2.0):
    """冷启 App -> 书架上找到 PDF 卡 -> 点开；返回 picked/events/nodes/shelf_pss/error。"""
    nodes = ui_dump(serial)
    shelf_pss = meminfo(serial)
    candidates = [book] if book else []
    candidates += [c for c in DEFAULT_BOOK_CANDIDATES if c not in candidates]
    picked, open_events, err = None, [], None
    for cand in candidates:
        node = find_node(nodes, cand)
        if not node or not node["rect"]:
            continue
        sh(serial, "logcat", "-c")
        tap_node(serial, node)
        t0 = time.time()
        got = []
        while time.time() - t0 < 15:
            time.sleep(0.6)
            ev = perf_events(serial)
            got = [e for e in ev if e.get("phase") in ("pdf_open", "page_render", "page_present", "cache_stat", "reader_close", "turn")]
            if any(e.get("phase") == "pdf_open" for e in got) and any(e.get("phase") == "page_render" for e in got):
                break
        if not any(e.get("phase") == "pdf_open" for e in got):
            sh(serial, "input", "keyevent", "4")
            time.sleep(1.2)
            nodes = ui_dump(serial)
            continue
        picked, open_events = cand, got
        break
    if not picked:
        err = "未找到可用的 PDF 样本（候选：%s）" % ",".join(DEFAULT_BOOK_CANDIDATES)
    return {"picked": picked, "events": open_events, "nodes": nodes,
            "shelf_pss": shelf_pss, "error": err}


def cmd_pdf(serial, book, json_out, settle=2.0, settle_exit=8.0):
    sh(serial, "am", "force-stop", PKG)
    time.sleep(0.6)
    sh(serial, "logcat", "-c")
    sh(serial, "am", "start", "-n", ACT)
    time.sleep(settle)
    op = open_pdf_book(serial, book, settle=settle)
    picked, open_events, shelf_pss, err = op["picked"], op["events"], op["shelf_pss"], op["error"]
    reader_pss = exit_pss = None
    if picked:
        time.sleep(1.5)
        reader_pss = meminfo(serial)
        sh(serial, "input", "keyevent", "4")
        time.sleep(settle_exit)
        exit_pss = meminfo(serial)
        open_events = merge_events(open_events, perf_events(serial))
    def ev_ms(phase):
        return [int(e["ms"]) for e in open_events if e.get("phase") == phase and e.get("ms", "").isdigit()]
    res = {
        "cmd": "pdf",
        "device": device_info(serial),
        "app": app_info(serial),
        "book": picked,
        "error": err,
        "pdf_open_ms": ev_ms("pdf_open"),
        "page_render_ms": ev_ms("page_render"),
        "page_present_ms": ev_ms("page_present"),
        "cache_stat": [e.get("extra") for e in open_events if e.get("phase") == "cache_stat"],
        "reader_close": [e.get("extra") for e in open_events if e.get("phase") == "reader_close"],
        "meminfo_shelf": shelf_pss,
        "meminfo_reader": reader_pss,
        "meminfo_after_exit": exit_pss,
        "exit_settle_s": settle_exit,
        "delta_reader_stable_mb": round((reader_pss or {}).get("stable_mb", 0) - shelf_pss.get("stable_mb", 0), 1) if reader_pss else None,
        "delta_exit_stable_mb": round((exit_pss or {}).get("stable_mb", 0) - shelf_pss.get("stable_mb", 0), 1) if exit_pss else None,
    }
    print(json.dumps({k: res[k] for k in ("book", "error", "pdf_open_ms", "page_render_ms", "page_present_ms", "cache_stat", "reader_close", "delta_reader_stable_mb", "delta_exit_stable_mb")}, ensure_ascii=False))
    dump_json(json_out, res)
    return res
# ---------- perfetto / battery ----------

def cmd_perfetto(serial, seconds, out_path, buffer="64mb"):
    if not out_path:
        out_path = os.path.join(DEFAULT_EVIDENCE, "perfetto-%s.pftrace" % time.strftime("%Y%m%d-%H%M%S"))
    os.makedirs(os.path.dirname(out_path) or ".", exist_ok=True)
    remote = "/data/misc/perfetto-traces/pb-%s.pftrace" % time.strftime("%Y%m%d-%H%M%S")
    ds = "sched freq idle am wm gfx view binder_driver dalvik memory"
    print("perfetto start: %ss, %s" % (seconds, ds))
    sh(serial, "perfetto", "-o", remote, "-t", "%ds" % seconds, "-b", buffer, *ds.split(), timeout=seconds + 60)
    adb(serial, "pull", remote, out_path, timeout=120)
    sh(serial, "rm", "-f", remote)
    size = os.path.getsize(out_path) if os.path.exists(out_path) else 0
    res = {"cmd": "perfetto", "seconds": seconds, "buffer": buffer, "datasources": ds.split(), "trace": out_path, "bytes": size, "device": device_info(serial)}
    print("trace -> %s (%d bytes)" % (out_path, size))
    dump_json(out_path + ".json", res)
    return res


def cmd_battery(serial, mode, seconds, json_out):
    if mode == "reset":
        sh(serial, "dumpsys", "batterystats", "--reset")
        print("batterystats reset ok; run scenario for %ss ..." % seconds)
        if seconds > 0:
            time.sleep(seconds)
        raw = sh(serial, "dumpsys", "batterystats", "--charged", PKG)
        res = {"cmd": "battery", "mode": "reset+window", "window_s": seconds,
               "device": device_info(serial), "app": app_info(serial),
               "note": "短窗仅冒烟；耗电验收需 >=30min 场景", "raw_head": raw[:6000]}
        print("battery: reset+window done（%ss 冒烟，不作验收）" % seconds)
        dump_json(json_out, res)
        return
    out = sh(serial, "dumpsys", "batterystats", "--charged", PKG, timeout=180)
    m = re.search(r"Uid u0a[0-9]+:.*?\n", out)
    res = {"cmd": "battery", "device": device_info(serial), "app": app_info(serial), "raw_head": out[:4000]}
    print(out[:1200])
    dump_json(json_out, res)
    return res


# ---------- ELF / 16KB ----------

def elf_aligns(data):
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return None, []
    ei_class = data[4]
    if ei_class == 2:  # ELF64 LE
        if data[5] != 1:
            return None, []
        phoff = int.from_bytes(data[32:40], "little")
        phentsize = int.from_bytes(data[54:56], "little")
        phnum = int.from_bytes(data[56:58], "little")
        aligns = []
        for i in range(phnum):
            off = phoff + i * phentsize
            if off + 56 > len(data):
                break
            p_type = int.from_bytes(data[off:off + 4], "little")
            p_align = int.from_bytes(data[off + 48:off + 56], "little")
            if p_type == 1:
                aligns.append(p_align)
        return ("ELF64", aligns)
    if ei_class == 1:  # ELF32 LE
        if data[5] != 1:
            return None, []
        phoff = int.from_bytes(data[28:32], "little")
        phentsize = int.from_bytes(data[42:44], "little")
        phnum = int.from_bytes(data[44:46], "little")
        aligns = []
        for i in range(phnum):
            off = phoff + i * phentsize
            if off + 32 > len(data):
                break
            p_type = int.from_bytes(data[off:off + 4], "little")
            p_align = int.from_bytes(data[off + 28:off + 32], "little")
            if p_type == 1:
                aligns.append(p_align)
        return ("ELF32", aligns)
    return None, []


def cmd_libs(serial, apk):
    res = {"cmd": "libs", "apk": apk, "device": device_info(serial), "libs": {}}
    if not apk or not os.path.exists(apk):
        print("apk not found:", apk)
        return res
    res["apk_sha256"] = hashlib.sha256(open(apk, "rb").read()).hexdigest()
    res["apk_bytes"] = os.path.getsize(apk)
    with zipfile.ZipFile(apk) as z:
        names = [n for n in z.namelist() if n.startswith("lib/") and n.endswith(".so")]
        for n in sorted(names):
            data = z.read(n)
            cls, aligns = elf_aligns(data)
            entry = {"bytes": len(data), "elf": cls, "pt_load_aligns": aligns}
            if aligns:
                entry["max_align"] = max(aligns)
                entry["align_16kb_ok"] = all(a >= 16384 for a in aligns)
            res["libs"][n] = entry
            print("%-60s %s aligns=%s 16KB=%s" % (n, cls, [hex(a) for a in aligns], entry.get("align_16kb_ok")))
    dump_json(os.path.join(DEFAULT_EVIDENCE, "libs-%s.json" % time.strftime("%Y%m%d-%H%M%S")), res)
    return res


# ---------- M0 EPUB 探针（debug M0ProbeActivity） ----------

M0_ACT = "app.particlebook/app.particlebook.android.debug.M0ProbeActivity"
M0_TAG = "PBM0"
M0_REMOTE_DIR = "/data/user/0/%s/files/m0" % PKG


def push_m0_epub(serial, host_epub):
    """把宿主 EPUB push 到 App 私有目录（同 scripts/android/m0-probe.sh 路径）。"""
    if not os.path.isfile(host_epub):
        raise FileNotFoundError(host_epub)
    tmp = "/data/local/tmp/m0-%d-%d.epub" % (os.getpid(), int(time.time()))
    dest = M0_REMOTE_DIR + "/book.epub"
    adb(serial, "push", os.path.abspath(host_epub), tmp)
    sh(serial, "run-as", PKG, "mkdir", "-p", "files/m0")
    sh(serial, "run-as", PKG, "cp", tmp, dest)
    sh(serial, "rm", "-f", tmp)
    sh(serial, "run-as", PKG, "rm", "-f", "files/m0-result.json")
    return dest


def run_m0_once(serial, remote_epub, chapter, cold):
    if cold:
        sh(serial, "am", "force-stop", PKG)
        time.sleep(0.6)
    sh(serial, "logcat", "-c")
    t0 = time.time()
    out = sh(serial, "am", "start", "-W", "-n", M0_ACT, "--es", "epub", remote_epub,
             "--ei", "chapter", str(chapter), timeout=120)
    m = re.search(r"TotalTime:\s*([0-9]+)", out)
    am_total = int(m.group(1)) if m else None
    result, err, line = None, None, ""
    while time.time() - t0 < 120:
        log = sh(serial, "logcat", "-d", "-s", M0_TAG + ":I", "*:S")
        for ln in log.splitlines():
            if "RESULT " in ln:
                line = ln
                try:
                    result = json.loads(ln.split("RESULT ", 1)[1])
                except Exception as ex:
                    err = "解析 RESULT JSON 失败: %s" % ex
                break
        if result is not None:
            break
        time.sleep(0.5)
    wall_ms = round((time.time() - t0) * 1000)
    if result is None and err is None:
        err = "等 120s 未拿到 PBM0 RESULT（activity 是否安装/是否锁屏？）"
    mem = None
    try:
        mem = meminfo(serial)
    except Exception:
        pass
    keep = ("openMs", "chapterReadMs", "layoutMs", "totalMs", "memoryBeforeKb",
            "memoryAfterLayoutKb", "memoryAfterCloseKb", "peakMemoryKb",
            "blockCount", "lineCount", "pageCount", "chapterHref", "chapterTitle",
            "spineCount", "tocCount", "archiveBytes", "error", "warnings")
    row = {k: result.get(k) for k in keep} if result else {}
    return {"cold": cold, "chapter": chapter, "am_total_ms": am_total, "wall_ms": wall_ms,
            "result": row, "result_error": row.get("error") if result else None,
            "harness_error": err, "meminfo_end": mem, "logcat_line": line}


def _m0_group(runs):
    def vals(key):
        return [r["result"].get(key) for r in runs
                if r.get("result") and isinstance(r["result"].get(key), (int, float)) and r["result"].get(key) >= 0]

    def kb2mb(v):
        return round(v / 1024.0, 1) if isinstance(v, (int, float)) and v >= 0 else None

    out = {"n": len(runs), "valid": sum(1 for r in runs if r.get("result")),
           "harness_errors": [r["harness_error"] for r in runs if r.get("harness_error")],
           "probe_errors": [r["result_error"] for r in runs if r.get("result_error")]}
    for key in ("openMs", "chapterReadMs", "layoutMs", "totalMs"):
        out[key] = stats(vals(key))
    for key, src in (("memoryBeforeMb", "memoryBeforeKb"), ("memoryAfterLayoutMb", "memoryAfterLayoutKb"),
                     ("memoryAfterCloseMb", "memoryAfterCloseKb"), ("peakMemoryMb", "peakMemoryKb")):
        out[key] = stats([kb2mb(v) for v in vals(src)])
    out["deltaAfterLayoutMb"] = stats([round((r["result"]["memoryAfterLayoutKb"] - r["result"]["memoryBeforeKb"]) / 1024.0, 1)
                                       for r in runs if r.get("result")
                                       and r["result"].get("memoryAfterLayoutKb", -1) >= 0
                                       and r["result"].get("memoryBeforeKb", -1) >= 0])
    out["deltaAfterCloseMb"] = stats([round((r["result"]["memoryAfterCloseKb"] - r["result"]["memoryBeforeKb"]) / 1024.0, 1)
                                      for r in runs if r.get("result")
                                      and r["result"].get("memoryAfterCloseKb", -1) >= 0
                                      and r["result"].get("memoryBeforeKb", -1) >= 0])
    return out


def cmd_m0(serial, epub, chapter, cold_n, warm_n, json_out):
    host = epub
    remote = push_m0_epub(serial, host)
    runs = {"cold": [], "warm": []}
    for i in range(cold_n):
        r = run_m0_once(serial, remote, chapter, cold=True)
        runs["cold"].append(r)
        rr = r["result"] or {}
        print("cold %d/%d: totalMs=%s openMs=%s chapterReadMs=%s layoutMs=%s wall=%sms%s" % (
            i + 1, cold_n, rr.get("totalMs"), rr.get("openMs"), rr.get("chapterReadMs"), rr.get("layoutMs"),
            r["wall_ms"], (" ERR=" + str(r["harness_error"] or rr.get("error"))) if (r["harness_error"] or rr.get("error")) else ""))
    for i in range(warm_n):
        r = run_m0_once(serial, remote, chapter, cold=False)
        runs["warm"].append(r)
        rr = r["result"] or {}
        print("warm %d/%d: totalMs=%s openMs=%s chapterReadMs=%s layoutMs=%s wall=%sms%s" % (
            i + 1, warm_n, rr.get("totalMs"), rr.get("openMs"), rr.get("chapterReadMs"), rr.get("layoutMs"),
            r["wall_ms"], (" ERR=" + str(r["harness_error"] or rr.get("error"))) if (r["harness_error"] or rr.get("error")) else ""))
    res = {"cmd": "m0", "epub_host": os.path.abspath(host), "epub_device": remote,
           "epub_bytes": os.path.getsize(host) if os.path.isfile(host) else None,
           "chapter": chapter, "device": device_info(serial), "app": app_info(serial),
           "cold": _m0_group(runs["cold"]), "warm": _m0_group(runs["warm"]),
           "runs": runs,
           "note": "耗时与内存来自 M0ProbeActivity 内 PBM0 RESULT JSON；am_total_ms 只是 Activity 启动耗时，不代表探针完成。"}
    print("cold: totalMs med=%s p95=%s | layoutMs med=%s p95=%s | Δlayout=%sMB Δclose=%sMB" % (
        res["cold"]["totalMs"].get("median"), res["cold"]["totalMs"].get("p95"),
        res["cold"]["layoutMs"].get("median"), res["cold"]["layoutMs"].get("p95"),
        res["cold"]["deltaAfterLayoutMb"].get("median"), res["cold"]["deltaAfterCloseMb"].get("median")))
    print("warm: totalMs med=%s p95=%s | layoutMs med=%s p95=%s | Δlayout=%sMB Δclose=%sMB" % (
        res["warm"]["totalMs"].get("median"), res["warm"]["totalMs"].get("p95"),
        res["warm"]["layoutMs"].get("median"), res["warm"]["layoutMs"].get("p95"),
        res["warm"]["deltaAfterLayoutMb"].get("median"), res["warm"]["deltaAfterCloseMb"].get("median")))
    dump_json(json_out, res)
    return res


# ---------- state / baseline ----------

def cmd_ui(serial, json_out):
    nodes = ui_dump(serial)
    keep = [n for n in nodes if n["text"] or n["desc"]]
    for n in keep:
        print("%-44s | %-28s | %s" % (n["text"][:44] or "-", n["desc"][:28] or "-", n["rect"]))
    dump_json(json_out, {"nodes": keep, "foreground": app_foreground(serial)})
    return {"nodes": keep}


def cmd_state(serial):
    info = {"device": device_info(serial), "app": app_info(serial), "foreground": app_foreground(serial)}
    print(json.dumps(info, ensure_ascii=False, indent=1))
    return info


def cmd_baseline(serial, sections, json_out, settle, book):
    out = {"cmd": "baseline", "sections": sections, "device": device_info(serial), "app": app_info(serial), "results": {}}
    json_dir = os.path.dirname(json_out) if json_out else DEFAULT_EVIDENCE
    if json_dir:
        os.makedirs(json_dir, exist_ok=True)
    tag = time.strftime("%Y%m%d-%H%M%S")
    if "coldstart" in sections:
        out["results"]["coldstart"] = cmd_coldstart(serial, 5, settle, os.path.join(json_dir, "coldstart-%s.json" % tag))
    if "pss" in sections:
        out["results"]["pss"] = cmd_pss(serial, settle, os.path.join(json_dir, "pss-%s.json" % tag))
    if "pdf" in sections:
        out["results"]["pdf"] = cmd_pdf(serial, book, os.path.join(json_dir, "pdf-%s.json" % tag))
    if "jank" in sections:
        out["results"]["jank"] = cmd_jank(serial, "auto", 10, 300, os.path.join(json_dir, "jank-%s.json" % tag), settle)
    dump_json(json_out, out)
    return out


def main():
    ap = argparse.ArgumentParser(description="ParticleBook 真机性能基准（release 口径）")
    ap.add_argument("--serial", default=None, help="adb serial（默认 8A9Y0G2XJ）")
    sub = ap.add_subparsers(dest="cmd", required=True)

    sub.add_parser("state")

    p = sub.add_parser("ui")
    p.add_argument("--json-out", default="")

    p = sub.add_parser("coldstart")
    p.add_argument("--n", type=int, default=5)
    p.add_argument("--settle", type=float, default=1.0)
    p.add_argument("--json-out", default="")
    p.add_argument("--apk-note", default="")

    p = sub.add_parser("pss")
    p.add_argument("--settle", type=float, default=4.0)
    p.add_argument("--json-out", default="")
    p.add_argument("--no-force-stop", action="store_true")

    p = sub.add_parser("jank")
    p.add_argument("--reset", action="store_true")
    p.add_argument("--report", action="store_true")
    p.add_argument("--scenario", choices=["shelf", "reader"], default="shelf")
    p.add_argument("--book", default="")
    p.add_argument("--swipes", type=int, default=10)
    p.add_argument("--frames-target", type=int, default=300)
    p.add_argument("--json-out", default="")

    p = sub.add_parser("pdf")
    p.add_argument("--book", default="")
    p.add_argument("--settle-exit", type=float, default=8.0)
    p.add_argument("--json-out", default="")

    p = sub.add_parser("perfetto")
    p.add_argument("--seconds", type=int, default=10)
    p.add_argument("--buffer", default="64mb")
    p.add_argument("--out", default="")

    p = sub.add_parser("battery")
    p.add_argument("--reset", action="store_true")
    p.add_argument("--report", action="store_true")
    p.add_argument("--seconds", type=int, default=0)
    p.add_argument("--json-out", default="")

    p = sub.add_parser("libs")
    p.add_argument("--apk", default="android/app/build/outputs/apk/debug/app-debug.apk")

    p = sub.add_parser("m0")
    p.add_argument("--epub", required=True)
    p.add_argument("--chapter", type=int, default=0)
    p.add_argument("--cold", type=int, default=3)
    p.add_argument("--warm", type=int, default=3)
    p.add_argument("--json-out", default="")

    p = sub.add_parser("baseline")
    p.add_argument("--sections", default="coldstart,pss,pdf,jank")
    p.add_argument("--json-out", default=os.path.join(DEFAULT_EVIDENCE, "baseline-%s.json" % time.strftime("%Y%m%d-%H%M%S")))
    p.add_argument("--settle", type=float, default=2.0)
    p.add_argument("--book", default="")

    a = ap.parse_args()
    serial = a.serial or os.environ.get("ANDROID_SERIAL") or None
    if a.cmd == "state":
        cmd_state(serial)
    elif a.cmd == "ui":
        cmd_ui(serial, a.json_out)
    elif a.cmd == "coldstart":
        cmd_coldstart(serial, a.n, a.settle, a.json_out, a.apk_note or None)
    elif a.cmd == "pss":
        cmd_pss(serial, a.settle, a.json_out, force_stop=not a.no_force_stop)
    elif a.cmd == "jank":
        mode = "reset" if a.reset else ("report" if a.report else "auto")
        cmd_jank(serial, mode, a.scenario, a.swipes, a.json_out, book=a.book)
    elif a.cmd == "pdf":
        cmd_pdf(serial, a.book, a.json_out, settle_exit=a.settle_exit)
    elif a.cmd == "perfetto":
        cmd_perfetto(serial, a.seconds, a.out, a.buffer)
    elif a.cmd == "battery":
        cmd_battery(serial, "reset" if a.reset else "report", a.seconds, a.json_out)
    elif a.cmd == "libs":
        cmd_libs(serial, a.apk)
    elif a.cmd == "m0":
        cmd_m0(serial, a.epub, a.chapter, a.cold, a.warm, a.json_out)
    elif a.cmd == "baseline":
        cmd_baseline(serial, [s.strip() for s in a.sections.split(",") if s.strip()], a.json_out, a.settle, a.book)


if __name__ == "__main__":
    main()
