#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""P1 阅读器真机验收骨架（只依赖 adb，不改 App）。

用途：P1 阅读会话（ReaderSession/Locator）落地后，按同一口径重复采集
「打开 → 翻页 → 退出」的设备指标；当前 P1 只有 PDF 原生链路可跑，
EPUB 仍是兼容模式（ReaderScreen.kt 注释: EPUB 暂未迁移），所以 EPUB 入口
只做文件投放与 NOT_WIRED 标记，等渲染器接线后再启用。

口径（与 docs/android-port/parity/benchmark 一致）：
- 打开耗时取 PBPERF|phase=pdf_open|ms=...（release 也打点）。
- 翻页取 PBPERF|phase=turn|page:N|ms=...（滚动开始到空闲帧）。
- 上屏取 PBPERF|phase=page_present|page:N|ms=...（首帧真正绘制）。
- 退出取 reader_close（pages 数）+ 退出前后 PSS（recommend settle >= 8s）。
- 所有设备操作外部用 scripts/android/spike2/lock.sh emu 包裹；本脚本不抢锁。

用法（worktree 根目录）：
  python scripts/android/p1-reader-device.py state
  python scripts/android/p1-reader-device.py install --apk android/app/build/outputs/apk/release/app-release-signed.apk
  python scripts/android/p1-reader-device.py open --book "统计之美（第二册）"
  python scripts/android/p1-reader-device.py run --book "统计之美（第二册）" --turns 3 \
      --json-out docs/android-port/p1/device/evidence/reader-smoke.json
  python scripts/android/p1-reader-device.py epub --epub F:/path/book.epub   # 仅投放，返回 NOT_WIRED

输出：stdout 一行摘要 + --json-out 完整 JSON（含 events 原始 PBPERF 行）。
退出码：0 成功；2 找不到书/未进入阅读器；3 设备/包不可用。
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time

PKG = "app.particlebook"
ACT = "app.particlebook/.android.MobileActivity"
PERF_TAG = "PBPERF"


def _resolve_adb():
    for c in (os.environ.get("ADB", ""), "adb", "adb.exe",
              "G:/Android/sdk/platform-tools/adb.exe", "C:/Android/sdk/platform-tools/adb.exe"):
        if not c:
            continue
        p = shutil.which(c) if not os.path.isabs(c) else c
        if p and os.path.exists(p):
            return p
    return "adb"


ADB = _resolve_adb()


def adb(serial, *args, timeout=180):
    cmd = [ADB] + (["-s", serial] if serial else []) + list(args)
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                           errors="replace", timeout=timeout)
        return r.stdout or ""
    except subprocess.TimeoutExpired:
        print("[warn] adb timeout: %s" % " ".join(args)[:120], file=sys.stderr)
        return ""


def sh(serial, *args, timeout=180):
    return adb(serial, "shell", *args, timeout=timeout)


def dump_json(path, obj):
    if not path:
        return
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)


def stats(vals):
    vals = sorted(v for v in vals if v is not None)
    if not vals:
        return {"n": 0}
    def pct(p):
        k = (len(vals) - 1) * p
        lo, hi = int(k), min(int(k) + 1, len(vals) - 1)
        return round(vals[lo] + (vals[hi] - vals[lo]) * (k - lo), 1)
    return {"n": len(vals), "median": pct(0.5), "p95": pct(0.95),
            "min": vals[0], "max": vals[-1], "values": vals}


def device_info(serial):
    return {
        "model": sh(serial, "getprop", "ro.product.model").strip(),
        "sdk": sh(serial, "getprop", "ro.build.version.sdk").strip(),
        "page_size": sh(serial, "getconf", "PAGE_SIZE").strip(),
    }


def app_info(serial):
    out = sh(serial, "dumpsys", "package", PKG)
    ver = re.search(r"versionName=(\S+)", out)
    flags = re.search(r"flags=\[([^\]]*)\]", out)
    return {"versionName": ver.group(1) if ver else None,
            "debuggable": bool(flags and "DEBUGGABLE" in flags.group(1))}


def ui_dump(serial):
    """uiautomator dump -> 原始 XML 文本（adb exec-out 直出 /dev/tty，避免落临时文件）。"""
    return adb(serial, "exec-out", "uiautomator", "dump", "/dev/tty") or ""


def node_rects(xml):
    """返回 [(text, desc, (x1,y1,x2,y2))]，只保留有文字的节点。"""
    out = []
    for m in re.finditer(r'<node[^>]*>', xml):
        tag = m.group(0)
        def attr(k):
            mm = re.search(k + r'="([^"]*)"', tag)
            return mm.group(1) if mm else ""
        text, desc = attr("text"), attr("content-desc")
        b = attr("bounds")
        mm = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
        if not (text or desc) or not mm:
            continue
        x1, y1, x2, y2 = map(int, mm.groups())
        out.append((text, desc, (x1, y1, x2, y2)))
    return out


def find_node(xml, needle):
    for text, desc, rect in node_rects(xml):
        if needle in text or needle in desc:
            return {"text": text, "desc": desc, "rect": rect}
    return None


def tap(serial, x, y):
    sh(serial, "input", "tap", str(x), str(y))


def tap_node(serial, node):
    x1, y1, x2, y2 = node["rect"]
    tap(serial, (x1 + x2) // 2, (y1 + y2) // 2)


def logcat_perf(serial):
    out = sh(serial, "logcat", "-d", "-s", PERF_TAG + ":I")
    events = []
    for line in out.splitlines():
        if "PBPERF|" not in line:
            continue
        body = line.split("PBPERF|", 1)[1]
        d = {"raw": line.strip()}
        for part in body.split("|"):
            if "=" in part:
                k, v = part.split("=", 1)
                d[k] = v
        m = re.search(r"page:(\d+)", body)
        if m:
            d["page"] = int(m.group(1))
        events.append(d)
    return events


def phase_ms(events, phase):
    vals = []
    for e in events:
        if e.get("phase") == phase and str(e.get("ms", "")).isdigit():
            vals.append(int(e["ms"]))
    return vals


def return_home(serial):
    sh(serial, "input", "keyevent", "4")


def open_book(serial, book, settle=3.5, timeout=15.0):
    """冷启 App -> 书架上点开指定书 -> 等阅读器 PBPERF；返回 dict。"""
    sh(serial, "am", "force-stop", PKG)
    time.sleep(0.6)
    sh(serial, "logcat", "-c")
    sh(serial, "am", "start", "-n", ACT)
    time.sleep(settle)
    node = None
    # 书架首帧受数据加载影响：最多补 3 次 dump（每次 +1.5s），避免把加载态判成 shelf_miss。
    for _ in range(3):
        node = find_node(ui_dump(serial), book)
        if node:
            break
        time.sleep(1.5)
    if not node:
        return {"opened": False, "error": "shelf_miss", "book": book}
    tap_node(serial, node)
    t0 = time.time()
    events = []
    while time.time() - t0 < timeout:
        time.sleep(0.7)
        events = logcat_perf(serial)
        if any(e.get("phase") == "pdf_open" for e in events):
            break
    return {"opened": any(e.get("phase") == "pdf_open" for e in events),
            "book": book, "events": events}


def turns(serial, count=3, axis="vertical", settle=1.2, ms=260):
    """翻页：PDF 为纵向连续滚动；EPUB 接线后可用 --axis horizontal。"""
    w, h = 1440, 2960
    got = []
    for _ in range(count):
        if axis == "vertical":
            sh(serial, "input", "swipe", str(w // 2), str(int(h * 0.78)),
               str(w // 2), str(int(h * 0.28)), str(ms))
        else:
            sh(serial, "input", "swipe", str(int(w * 0.8)), str(h // 2),
               str(int(w * 0.2)), str(h // 2), str(ms))
        time.sleep(settle)
        got = logcat_perf(serial)
    return got


def cmd_state(args):
    res = {"device": device_info(args.serial), "app": app_info(args.serial)}
    print(json.dumps(res, ensure_ascii=False))
    dump_json(args.json_out, res)


def cmd_install(args):
    out = adb(args.serial, "install", "-r", "-t", args.apk, timeout=300)
    info = app_info(args.serial)
    res = {"install": out.strip().splitlines()[-1] if out.strip() else "", "app": info}
    print(json.dumps(res, ensure_ascii=False))
    dump_json(args.json_out, res)


def cmd_run(args):
    res = {"cmd": "reader-run", "device": device_info(args.serial),
           "app": app_info(args.serial), "book": args.book,
           "axis": args.axis, "turns_requested": args.turns}
    opened = open_book(args.serial, args.book, settle=args.settle)
    res.update({k: opened[k] for k in ("opened", "error") if k in opened})
    if not opened.get("opened"):
        res["verdict"] = "FAIL"
        print(json.dumps(res, ensure_ascii=False))
        dump_json(args.json_out, res)
        return 2
    t_events = turns(args.serial, args.turns, args.axis)
    events = opened.get("events", []) + t_events
    # 去重
    seen, uniq = set(), []
    for e in events:
        key = e.get("raw") or json.dumps(e, sort_keys=True)
        if key not in seen:
            seen.add(key)
            uniq.append(e)
    res["events"] = uniq
    res["pdf_open_ms"] = phase_ms(uniq, "pdf_open")
    res["turn_ms"] = phase_ms(uniq, "turn")
    res["page_present_ms"] = phase_ms(uniq, "page_present")
    res["reader_close"] = [e.get("extra") for e in uniq if e.get("phase") == "reader_close"]
    # 退出：返回书架并等待释放
    return_home(args.serial)
    time.sleep(args.settle_exit)
    res["pss_after_exit"] = sh(args.serial, "dumpsys", "meminfo", PKG)
    res["verdict"] = "PASS" if res["pdf_open_ms"] and res["page_present_ms"] else "PARTIAL"
    print(json.dumps({k: res[k] for k in ("book", "opened", "pdf_open_ms", "turn_ms",
                                          "page_present_ms", "reader_close", "verdict")},
                     ensure_ascii=False))
    dump_json(args.json_out, res)
    return 0


def cmd_epub(args):
    """EPUB 投放占位：P1 尚未接线渲染器，明确返回 NOT_WIRED。"""
    remote = "/sdcard/Download/" + os.path.basename(args.epub)
    out = adb(args.serial, "push", args.epub, remote)
    res = {"epub": args.epub, "remote": remote, "push": out.strip(),
           "verdict": "NOT_WIRED",
           "note": "ReaderScreen 当前 EPUB 走兼容模式；等 P1 渲染器接线后改为 open --book。"}
    print(json.dumps(res, ensure_ascii=False))
    dump_json(args.json_out, res)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL", "8A9Y0G2XJ"))
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("state"); p.add_argument("--json-out", default="")
    p.set_defaults(func=cmd_state)

    p = sub.add_parser("install")
    p.add_argument("--apk", required=True); p.add_argument("--json-out", default="")
    p.set_defaults(func=cmd_install)

    p = sub.add_parser("open")
    p.add_argument("--book", required=True); p.add_argument("--json-out", default="")
    p.add_argument("--settle", type=float, default=3.5)
    p.set_defaults(func=lambda a: cmd_run(_with(a, turns=0)))

    p = sub.add_parser("run")
    p.add_argument("--book", required=True)
    p.add_argument("--turns", type=int, default=3)
    p.add_argument("--axis", choices=["vertical", "horizontal"], default="vertical")
    p.add_argument("--settle", type=float, default=3.5)
    p.add_argument("--settle-exit", dest="settle_exit", type=float, default=8.0)
    p.add_argument("--json-out", default="")
    p.set_defaults(func=cmd_run)

    p = sub.add_parser("epub")
    p.add_argument("--epub", required=True); p.add_argument("--json-out", default="")
    p.set_defaults(func=cmd_epub)

    args = ap.parse_args()
    sys.exit(args.func(args))


def _with(args, **kw):
    d = vars(args).copy(); d.update(kw)
    return type("A", (), d)


if __name__ == "__main__":
    main()
