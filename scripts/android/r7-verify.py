#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""R7-A 导入持久化复验辅助（Pixel 3 XL，USB adb）。

子命令：
  state / ui / find TEXT / tap TEXT / tapxy X Y / key NAME / shot NAME
  books                     打印书架书卡（desc 含《》且 bounds 有效）
  wait TEXT [--timeout 15]
  coldstart --n 5           force-stop + am start -W 循环
  import --name X.pdf       走 SAF：更多操作 → 导入本地文件 → DocumentsUI 显示根目录 → 下载 → 选文件
  longpress X Y / swipe x1 y1 x2 y2 [--ms 300]
  delete-book NAME          长按书卡 → 底部删除 → 确认
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

def _resolve_adb():
    cands = [os.environ.get("ADB", ""), "adb", "adb.exe",
             r"G:\Android\sdk\platform-tools\adb.exe",
             r"C:\Android\sdk\platform-tools\adb.exe"]
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
SERIAL = os.environ.get("R7_SERIAL", "8A9Y0G2XJ")
OUTDIR = r"F:/tmp/verify/r7"

def adb(*args, timeout=90):
    cmd = [ADB, "-s", SERIAL] + list(args)
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, encoding="utf-8", errors="replace")
    return r.stdout or ""

def shell(*args, timeout=90):
    return adb("shell", *args, timeout=timeout)

def dump_xml():
    out = adb("exec-out", "uiautomator", "dump", "/dev/tty", timeout=60)
    i = out.find("<?xml")
    j = out.find("</hierarchy>")
    if i < 0 or j < 0:
        return ""
    return out[i:j + len("</hierarchy>")]

def parse_nodes(xml):
    nodes = []
    if not xml:
        return nodes
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        try:
            root = ET.fromstring(xml[:xml.rfind(">") + 1])
        except Exception:
            return nodes
    for n in root.iter("node"):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if not m:
            continue
        x1, y1, x2, y2 = (int(v) for v in m.groups())
        nodes.append({
            "text": n.get("text", "") or "",
            "desc": n.get("content-desc", "") or "",
            "cls": n.get("class", "") or "",
            "pkg": n.get("package", "") or "",
            "clickable": n.get("clickable") == "true",
            "bounds": (x1, y1, x2, y2),
            "center": ((x1 + x2) // 2, (y1 + y2) // 2),
        })
    return nodes

def label(n):
    return n["text"] or n["desc"]

def find(nodes, want, exact_first=True):
    if exact_first:
        for n in nodes:
            if n["text"] == want or n["desc"] == want:
                return n
    for n in nodes:
        if want in n["text"] or want in n["desc"]:
            return n
    return None

def visible(n):
    x1, y1, x2, y2 = n["bounds"]
    return x2 > x1 and y2 > y1

def print_nodes(nodes, only_actionable=True):
    for n in nodes:
        if only_actionable and not (n["text"] or n["desc"]):
            continue
        if not visible(n):
            continue
        print("%-30s | %-30s | click=%d | %s" % (n["text"][:30], n["desc"][:30], n["clickable"], n["bounds"]))

def wait_text(want, timeout=15):
    t0 = time.time()
    while time.time() - t0 < timeout:
        n = find(parse_nodes(dump_xml()), want)
        if n:
            return n
        time.sleep(0.7)
    return None

def tap_node(n):
    x, y = n["center"]
    shell("input", "tap", str(x), str(y))
    print("tap %r at (%d,%d)" % (label(n), x, y))

def start_app():
    shell("am", "start", "-W", "-n", ACT)
    time.sleep(2.5)

def ensure_app():
    nodes = parse_nodes(dump_xml())
    if any(n["pkg"] == PKG for n in nodes):
        return True
    start_app()
    nodes = parse_nodes(dump_xml())
    return any(n["pkg"] == PKG for n in nodes)

def ensure_shelf():
    if not ensure_app():
        return False
    for _ in range(4):
        nodes = parse_nodes(dump_xml())
        if find(nodes, "更多操作") or find(nodes, "书架是空的"):
            return True
        shell("input", "keyevent", "4")
        time.sleep(1.2)
        nodes = parse_nodes(dump_xml())
        if not any(n["pkg"] == PKG for n in nodes):
            start_app()
    return False

def cmd_state(a):
    out = shell("dumpsys", "activity", "activities")
    m = re.search(r"topResumedActivity[^\n]*|mResumedActivity[^\n]*", out)
    print(m.group(0).strip() if m else "unknown")
    pkg_out = shell("dumpsys", "package", PKG)
    for k in ("versionName", "lastUpdateTime"):
        mm = re.search(k + r"=([^\n]+)", pkg_out)
        print(k, "=", mm.group(1).strip() if mm else "?")

def cmd_ui(a):
    print_nodes(parse_nodes(dump_xml()), only_actionable=not a.all)

def cmd_find(a):
    for n in parse_nodes(dump_xml()):
        if a.text in n["text"] or a.text in n["desc"]:
            print("%-30s | %-30s | click=%d | %s" % (n["text"][:30], n["desc"][:30], n["clickable"], n["bounds"]))

def cmd_tap(a):
    n = find(parse_nodes(dump_xml()), a.text)
    if not n:
        print("NOT FOUND:", a.text)
        sys.exit(2)
    tap_node(n)

def cmd_tapxy(a):
    shell("input", "tap", str(a.x), str(a.y))
    print("tap", a.x, a.y)

def cmd_key(a):
    shell("input", "keyevent", a.name)
    print("key", a.name)

def cmd_shot(a):
    os.makedirs(OUTDIR, exist_ok=True)
    p = OUTDIR + "/" + a.name + ".png"
    data = subprocess.run([ADB, "-s", SERIAL, "exec-out", "screencap", "-p"], capture_output=True, timeout=90).stdout
    with open(p, "wb") as f:
        f.write(data)
    print("saved", p, len(data), "bytes")

def books_list(nodes=None):
    nodes = nodes if nodes is not None else parse_nodes(dump_xml())
    out = []
    for n in nodes:
        d = n["desc"]
        if not visible(n):
            continue
        if "《" in d and "》" in d and not d.startswith("继续阅读"):
            out.append(n)
    return out

def titles_from(nodes):
    out = []
    for n in books_list(nodes):
        m = re.search(r"《(.+?)》", n["desc"])
        if m and m.group(1) not in out:
            out.append(m.group(1))
    return out


def last_load_count():
    out = adb("logcat", "-d", "-s", "PBPERF")
    hits = re.findall(r"library_load\|ms=\d+\|extra=books:(\d+)", out)
    return int(hits[-1]) if hits else None


def cmd_books(a):
    nodes = parse_nodes(dump_xml())
    bs = books_list(nodes)
    for n in bs:
        print(n["desc"][:120], "|", n["bounds"])
    ts = titles_from(nodes)
    print("cards_visible:", len(bs), "| unique_titles_visible:", len(ts), ts)
    print("library_load books:", last_load_count())

def cmd_wait(a):
    n = wait_text(a.text, a.timeout)
    if n:
        print("found %r at %s" % (label(n), n["bounds"]))
    else:
        print("TIMEOUT waiting:", a.text)
        sys.exit(3)

def cmd_longpress(a):
    shell("input", "swipe", str(a.x), str(a.y), str(a.x), str(a.y), "900")
    print("longpress", a.x, a.y)

def cmd_swipe(a):
    shell("input", "swipe", str(a.x1), str(a.y1), str(a.x2), str(a.y2), str(a.ms))
    print("swipe", a.x1, a.y1, a.x2, a.y2, a.ms)

def cmd_coldstart(a):
    vals = []
    for i in range(a.n):
        shell("am", "force-stop", PKG)
        time.sleep(0.6)
        out = shell("am", "start", "-W", "-n", ACT)
        m = re.search(r"TotalTime:\s*([0-9]+)", out)
        if m:
            vals.append(int(m.group(1)))
            print("run %d: TotalTime=%sms" % (i + 1, m.group(1)))
        else:
            print("run %d: no TotalTime %r" % (i + 1, out[:120]))
        time.sleep(1.2)
    if vals:
        s = sorted(vals)
        print("cold start: %s ms | min %d med %d max %d" % (vals, s[0], s[len(s) // 2], s[-1]))

def open_downloads_in_picker():
    """在 DocumentsUI 里导航到「下载」根目录。"""
    for attempt in range(3):
        nodes = parse_nodes(dump_xml())
        if find(nodes, "下载") or find(nodes, "realbook"):
            return True
        root = find(nodes, "显示根目录") or find(nodes, "Show roots")
        if root:
            tap_node(root)
            time.sleep(1.2)
            nodes = parse_nodes(dump_xml())
            dl = find(nodes, "下载") or find(nodes, "Downloads")
            if dl:
                tap_node(dl)
                time.sleep(1.5)
                return True
        time.sleep(1.0)
    return False

def cmd_import(a):
    os.makedirs(OUTDIR, exist_ok=True)
    if not ensure_shelf():
        print("FAIL: 不在书架页")
        sys.exit(1)
    nodes = parse_nodes(dump_xml())
    more = find(nodes, "更多操作") or find(nodes, "更多")
    if not more:
        print("FAIL: 找不到 更多 按钮"); print_nodes(nodes); sys.exit(1)
    tap_node(more); time.sleep(0.9)
    nodes = parse_nodes(dump_xml())
    item = find(nodes, "导入本地文件")
    if not item:
        print("FAIL: 菜单没有 导入本地文件"); print_nodes(nodes); sys.exit(1)
    tap_node(item); time.sleep(1.8)

    target = None
    for attempt in range(4):
        nodes = parse_nodes(dump_xml())
        target = find(nodes, a.name)
        if target:
            break
        if not open_downloads_in_picker():
            time.sleep(1.0)
            continue
        nodes = parse_nodes(dump_xml())
        target = find(nodes, a.name)
        if target:
            break
        # 列表视图切网格/滚动找
        lv = find(nodes, "列表视图") or find(nodes, "网格视图")
        if lv:
            tap_node(lv); time.sleep(1.0)
        shell("input", "swipe", "720", "2200", "720", "1200", "300")
        time.sleep(1.0)
    if not target:
        print("FAIL: 选择器里找不到", a.name)
        print_nodes(parse_nodes(dump_xml()))
        sys.exit(1)
    tap_node(target)
    time.sleep(2.5)
    # 可能的确认
    for cand in ("打开", "允许", "确定", "导入"):
        nodes = parse_nodes(dump_xml())
        b = find(nodes, cand)
        if b and b["clickable"]:
            tap_node(b); time.sleep(1.0)
    time.sleep(2.0)
    cmd_books(a)

def cmd_has_book(a):
    nodes = parse_nodes(dump_xml())
    hits = [n for n in nodes if a.title in n["desc"] or a.title in n["text"]]
    for n in hits:
        print("HIT", n["desc"][:100] or n["text"][:100], "|", n["bounds"])
    print("hits:", len(hits))
    sys.exit(0 if hits else 4)


def cmd_delete(a):
    if not ensure_shelf():
        print("FAIL: 不在书架页"); sys.exit(1)
    nodes = parse_nodes(dump_xml())
    card = None
    for n in books_list(nodes):
        if a.name in n["desc"]:
            card = n; break
    if not card:
        print("FAIL: 书架上找不到", a.name); sys.exit(1)
    x, y = card["center"]
    shell("input", "swipe", str(x), str(y), str(x), str(y), "900")
    time.sleep(1.5)
    print_nodes(parse_nodes(dump_xml()))

def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("state", "books"):
        sub.add_parser(name)
    p = sub.add_parser("import"); p.add_argument("--name", default="realbook-60p.pdf")
    p = sub.add_parser("delete"); p.add_argument("name")
    p = sub.add_parser("has-book"); p.add_argument("title")
    p = sub.add_parser("ui"); p.add_argument("--all", action="store_true")
    p = sub.add_parser("find"); p.add_argument("text")
    p = sub.add_parser("tap"); p.add_argument("text")
    p = sub.add_parser("tapxy"); p.add_argument("x", type=int); p.add_argument("y", type=int)
    p = sub.add_parser("key"); p.add_argument("name")
    p = sub.add_parser("shot"); p.add_argument("name")
    p = sub.add_parser("wait"); p.add_argument("text"); p.add_argument("--timeout", type=float, default=15)
    p = sub.add_parser("longpress"); p.add_argument("x", type=int); p.add_argument("y", type=int)
    p = sub.add_parser("swipe"); p.add_argument("x1", type=int); p.add_argument("y1", type=int); p.add_argument("x2", type=int); p.add_argument("y2", type=int); p.add_argument("--ms", type=int, default=300)
    p = sub.add_parser("coldstart"); p.add_argument("--n", type=int, default=5)
    a = ap.parse_args()
    fn = {"state": cmd_state, "ui": cmd_ui, "find": cmd_find, "tap": cmd_tap, "tapxy": cmd_tapxy,
          "key": cmd_key, "shot": cmd_shot, "books": cmd_books, "wait": cmd_wait,
          "longpress": cmd_longpress, "swipe": cmd_swipe, "import": cmd_import,
          "coldstart": cmd_coldstart, "delete": cmd_delete, "has-book": cmd_has_book}[a.cmd]
    fn(a)

if __name__ == "__main__":
    main()
