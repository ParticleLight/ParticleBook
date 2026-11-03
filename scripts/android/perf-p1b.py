#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""P1b 新页面性能与内存回归采集（R7-D-PERF）。口径与 R6 完全一致。

覆盖页面：书架（基线）/ Z-Library 搜索页 / 搜索结果页 / 书籍详情页（含下载卡区域）/ 下载记录页 /
          设置页 / 返回书架；--reader 追加阅读器 PSS；--font-scale F 在指定系统字号下重跑（如 2.0 = 200%）。
测量项：冷启动 TotalTime x5、PSS（TOTAL + Java/Native/Graphics 分开 + 相对书架增量）、
        滚动 jank（结果页 300 帧、详情页 200 帧）、可选阅读器 PSS；一条命令同时出 Markdown 报告 + JSON + 自动截图。

用法：
  python scripts/android/perf-p1b.py state
  python scripts/android/perf-p1b.py --serial 8A9Y0G2XJ coldstart --n 5
  python scripts/android/perf-p1b.py pss
  python scripts/android/perf-p1b.py jank
  python scripts/android/perf-p1b.py all --out docs/android-port/p1b-perf.md [--json ...] [--shots DIR] [--reader] [--font-scale 2.0] [--ensure-proxy]
  python scripts/android/perf-p1b.py nav detail     # 调试：只导航到某页
  python scripts/android/perf-p1b.py ui             # 调试：打印可点节点
  python scripts/android/perf-p1b.py tap TEXT / tapxy X Y / key 66 / shot NAME

口径（与 docs/android-port/android-ui-verify-v4.md §21 / scripts/android/perf-device.py 一致）：
  - 冷启动 = am force-stop + am start -W 的 TotalTime，重复 N 次取中位；不 pm clear（会丢书和账号）
  - PSS     = dumpsys meminfo <pkg> 的 App Summary（Java/Native/Graphics 分开）+ 主表 TOTAL
  - jank    = dumpsys gfxinfo <pkg> reset → 真实滑动 → 读 Janky frames / percentile / Missed Vsync
  - PSS 序列在同一进程内按 canonical 顺序走（除 coldstart），不中途 force-stop，
    这样「相对书架增量」才是用户真实体验到的增量

只读设备状态。唯一可选的写操作是 --ensure-proxy（调用 p1a-devnet.sh on 配置 reverse + http_proxy），
不传则只检测并报告当前代理状态。
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

# Windows 控制台默认 cp936，日志统一走 UTF-8，便于和 git bash / CI 对齐
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

# ---------------------------------------------------------------- 常量与基线

PKG = "app.particlebook"
ACT = "app.particlebook/.android.MobileActivity"

# 自动截图目录（cmd_all 里按 --shots 或默认时间戳目录赋值）
SHOT_DIR = ""

# R6-B 基线（Pixel 3 XL，release 2.2.1-p0，md5 bdf76d8e...）见 android-ui-verify-v4.md 第 21 节
R6 = {
    "device": "Pixel 3 XL (SDXL 845 / Android 12 / 1440x2960 @560dpi / 3.5GB)",
    "apk_md5": "bdf76d8e33e909be69211514255d6a01",
    "coldstart_ms": [224, 235, 237, 250, 262],
    "coldstart_median": 237,
    "pss": {"shelf": 176.1, "reader_open": 281.5, "scroll10": 270.6, "scroll30": 264.9, "back": 208.1},
    "pss_parts_shelf": {"java": 6.3, "native": 23.1, "graphics": 126.7},
    "jank_pct": 0.00,
    "jank_frames": 478,
    "jank_p50": 6, "jank_p90": 8, "jank_p95": 10, "jank_p99": 14,
}

# architecture-v2 验收指标（§3 L1 / L3 修订版）
L3 = {
    "first_frame_ms": 300,      # L1 Activity 首帧 <= 300ms
    "interactive_ms": 600,      # L1 书架可交互（含数据）<= 600ms
    "resident_pss_mb": 90,      # L1/L3 常驻 PSS（无阅读器）<= 90MB
    "jank_pct_ref": 2.0,        # R6 既有参考口径（L1 书架口径是 0 帧 >16ms）
    "reader_delta_mb": 80,      # L3 阅读时稳定内存增量（Java+Native）<= 80MB
    "frame_budget_ms": 16.7,
}

# 底部导航候选标签（P1 为 3 目的地：书架 / Z-Library / 设置；P0 为 2 项）
NAV_LABELS = {
    "shelf": ["书架"],
    "zlib": ["Z-Library", "Z-library", "找书"],
    "settings": ["设置"],
}

PAGE_ORDER = ["shelf", "zlib", "results", "detail", "downloads", "settings", "shelf_back"]
# 依赖网络（镜像搜索）的页面：失败按 SKIP 记录，不算 FAIL
NET_PAGES = ("results", "detail")
PAGE_TITLE = {
    "shelf": "书架（基线）",
    "reader": "阅读器（打开书）",
    "zlib": "Z-Library 搜索页",
    "results": "搜索结果页（补充）",
    "detail": "书籍详情页",
    "downloads": "下载记录页",
    "settings": "设置页",
    "shelf_back": "返回书架（回落检查）",
}

# App Summary 字段 -> 键
APP_SUMMARY_KEYS = [
    ("Java Heap", "java"),
    ("Native Heap", "native"),
    ("Code", "code"),
    ("Stack", "stack"),
    ("Graphics", "graphics"),
    ("Private Other", "private_other"),
    ("System", "system"),
    ("TOTAL PSS", "total"),
    ("TOTAL RSS", "rss"),
]

# ---------------------------------------------------------------- adb 基础设施


def _resolve_adb():
    """Windows 原生 python 不能直接用 /g/... 这类 MSYS 路径，逐个尝试。"""
    cands = [os.environ.get("ADB", ""), "adb", "adb.exe",
             r"G:\Android\sdk\platform-tools\adb.exe",
             r"C:\Android\sdk\platform-tools\adb.exe"]
    for c in cands:
        if not c:
            continue
        p = c if os.path.isabs(c) else shutil.which(c)
        if p and os.path.exists(p):
            return p
    return "adb"


ADB = _resolve_adb()


def _default_serial():
    for k in ("P1B_SERIAL", "R7_SERIAL", "SERIAL_OVERRIDE"):
        v = os.environ.get(k)
        if v:
            return v
    return "8A9Y0G2XJ"


SERIAL = _default_serial()


def adb(*args, timeout=90, binary=False):
    cmd = [ADB]
    if SERIAL:
        cmd += ["-s", SERIAL]
    cmd += list(args)
    r = subprocess.run(cmd, capture_output=True, timeout=timeout)
    if binary:
        return r.stdout or b""
    return (r.stdout or b"").decode("utf-8", "replace")


def shell(*args, timeout=90):
    return adb("shell", *args, timeout=timeout)


def adb_devices_raw():
    r = subprocess.run([ADB, "devices"], capture_output=True, timeout=30)
    return (r.stdout or b"").decode("utf-8", "replace")


def require_device():
    out = adb_devices_raw()
    if SERIAL and SERIAL in out:
        return True
    print("[perf-p1b] 设备 %r 不在 adb devices 列表里：" % SERIAL)
    print(out.strip())
    print("  提示：--serial 指定；或先 adb wait-for-device（模拟器）/ adb connect（无线真机）")
    return False


# ---------------------------------------------------------------- UI 自动化


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
            "clickable": n.get("clickable") == "true",
            "scrollable": n.get("scrollable") == "true",
            "bounds": (x1, y1, x2, y2),
            "center": ((x1 + x2) // 2, (y1 + y2) // 2),
        })
    return nodes


def label(n):
    return n["text"] or n["desc"]


def visible(n):
    x1, y1, x2, y2 = n["bounds"]
    return x2 > x1 and y2 > y1


def find(nodes, want, exact_first=True):
    if exact_first:
        for n in nodes:
            if n["text"] == want or n["desc"] == want:
                return n
    for n in nodes:
        if want in n["text"] or want in n["desc"]:
            return n
    return None


def find_any(nodes, wants):
    for w in wants:
        n = find(nodes, w)
        if n:
            return n
    return None


def tap_node(n, what=""):
    x, y = n["center"]
    shell("input", "tap", str(x), str(y))
    print("  tap %r at (%d,%d)%s" % (label(n)[:30], x, y, ("  [" + what + "]") if what else ""))
    return True


def tap_text(wants, timeout=8.0):
    if isinstance(wants, str):
        wants = [wants]
    t0 = time.time()
    while time.time() - t0 < timeout:
        n = find_any(parse_nodes(dump_xml()), wants)
        if n and visible(n):
            tap_node(n)
            return True
        time.sleep(0.6)
    return False


def wait_text(wants, timeout=15.0):
    if isinstance(wants, str):
        wants = [wants]
    t0 = time.time()
    while time.time() - t0 < timeout:
        n = find_any(parse_nodes(dump_xml()), wants)
        if n:
            return n
        time.sleep(0.6)
    return None


def has_text(wants, nodes=None):
    if isinstance(wants, str):
        wants = [wants]
    nodes = nodes if nodes is not None else parse_nodes(dump_xml())
    return find_any(nodes, wants) is not None


def screen_size():
    out = shell("wm", "size")
    m = re.search(r"(\d+)x(\d+)", out)
    if not m:
        return (1440, 2960)
    return (int(m.group(1)), int(m.group(2)))


def keyevent(code):
    shell("input", "keyevent", str(code))


def swipe(x1, y1, x2, y2, ms=200):
    shell("input", "swipe", str(x1), str(y1), str(x2), str(y2), str(ms))


def shot(path):
    d = os.path.dirname(path)
    if d:
        os.makedirs(d, exist_ok=True)
    data = adb("exec-out", "screencap", "-p", binary=True, timeout=90)
    with open(path, "wb") as f:
        f.write(data)
    print("  shot -> %s (%d bytes)" % (path, len(data)))


def app_running():
    return bool(shell("pidof", PKG).strip())


def start_app(wait=True):
    args = ["am", "start"]
    if wait:
        args.append("-W")
    args += ["-n", ACT]
    return shell(*args, timeout=120)


def ensure_app():
    if app_running():
        return True
    print("  app 未运行，启动...")
    start_app()
    time.sleep(2.5)
    return app_running()


# ---------------------------------------------------------------- 导航


def has_bottom_nav(nodes):
    """底栏存在 = 「书架」+（「Z-Library」或「设置」）两个标签同时可见。"""
    return find_any(nodes, ["书架"]) is not None and \
        (find_any(nodes, ["Z-Library"]) is not None or find_any(nodes, ["设置"]) is not None)


def ensure_base(size=None, tries=4):
    """回到有底栏的页面（书架 / Z-Library / 设置）。中途页面（详情/下载记录）用返回键退出。

    只在确认底栏存在时才继续，绝不盲点坐标，避免误触「下载」这类按钮。
    """
    for i in range(tries):
        if has_bottom_nav(parse_nodes(dump_xml())):
            return True
        print("  [nav] 不在底栏页，按返回键（第 %d 次）" % (i + 1))
        keyevent(4)
        time.sleep(0.9)
    return has_bottom_nav(parse_nodes(dump_xml()))


def tap_bottom_nav(page, size=None):
    """底部导航按标签点；找不到先回基线页，仍找不到就报失败（不做几何盲点）。"""
    wants = NAV_LABELS.get(page, [])
    for attempt in (0, 1):
        n = find_any(parse_nodes(dump_xml()), wants)
        if n and visible(n):
            tap_node(n, "bottom-nav:" + page)
            return True
        if attempt == 0 and not ensure_base(size):
            print("  [warn] 回不到基线页，放弃底栏导航")
            return False
    print("  [warn] 找不到底栏标签 %r，放弃（不盲点坐标，避免误触按钮）" % (wants,))
    return False


def clear_field():
    """清空焦点输入框（Compose 无 clear 语义，用 MOVE_END + DEL 连打）。"""
    shell("input", "keyevent", "123")  # KEYCODE_MOVE_END
    for _ in range(12):
        shell("input", "keyevent", "67")  # KEYCODE_DEL
    print("  field cleared")


NET_DEAD = {"dead": False}


def net_dead():
    """一旦确认网络/镜像不可用，后续网络依赖页直接跳过，避免逐页超时拖长采集。"""
    return NET_DEAD["dead"]


def ensure_results(query="android", timeout=45.0):
    """到 Z-Library 页并保证有结果列表；返回 (ok, reason)。网络类失败会置 net_dead。"""
    ok, reason = _ensure_results(query, timeout)
    if not ok and is_net_reason(reason):
        NET_DEAD["dead"] = True
        print("  [net] 判定网络/镜像不可用，后续网络依赖页直接跳过（记 SKIP，不算 FAIL）")
    return ok, reason


def _ensure_results(query="android", timeout=45.0):
    nav("zlib")
    nodes = parse_nodes(dump_xml())
    if has_text(["已加载", "找到"], nodes):
        print("  结果列表已在页面上")
        return True, "cached"
    if has_text(["搜索失败", "无法连接", "所有 Z-Library 镜像"], nodes):
        print("  [warn] 页面处于错误态：%r" %
              (label(find_any(nodes, ["搜索失败", "无法连接", "所有 Z-Library 镜像"])) or ""))
        return False, "error-state"
    edit = None
    for n in nodes:
        if n["cls"].endswith("EditText") or "书名 / 作者 / ISBN" in (n["text"] + n["desc"]):
            edit = n
            break
    if not edit:
        print("  [warn] 找不到搜索输入框")
        return False, "no-search-field"
    tap_node(edit, "search-field")
    time.sleep(0.8)
    clear_field()
    shell("input", "text", query.replace(" ", "%s"))
    time.sleep(0.4)
    keyevent(66)
    print("  已提交搜索 %r，等待结果..." % query)
    n = wait_text(["已加载", "找到", "搜索失败", "无法连接"], timeout=timeout)
    if n and has_text(["已加载", "找到"]):
        print("  结果就绪：%r" % label(n)[:40])
        return True, "searched"
    print("  [warn] 搜索未出结果（%r）" % (label(n)[:40] if n else "timeout"))
    return False, "no-results"


def tap_first_result():
    """点搜索结果第一条（书名/作者节点，跳过表头与按钮）。"""
    nodes = parse_nodes(dump_xml())
    skip = ("已加载", "找到", "第 ", "搜索", "登录", "更多操作", "线路", "下载记录", "设置", "书架")
    cands = []
    for n in nodes:
        if not visible(n):
            continue
        t = n["text"] + n["desc"]
        if not t.strip():
            continue
        if any(k in t for k in skip):
            continue
        if "《" in t or (n["clickable"] and len(t) > 6):
            cands.append(n)
    if not cands:
        return False
    cands.sort(key=lambda c: (c["bounds"][1], c["bounds"][0]))
    tap_node(cands[0], "first-result")
    return True


LAST_NAV = {"reason": "ok"}


def nav_reason():
    """最近一次 nav 的失败原因（ok / net 类 / 其他）。"""
    return LAST_NAV.get("reason", "ok")


def is_net_reason(reason):
    """网络类失败：这些行记 SKIP（网络不可达），不计入 PASS/FAIL。"""
    return reason in ("error-state", "no-results", "no-search-field", "zlib-not-shown",
                      "detail-not-shown", "no-result-item", "net-skipped")


def nav(page, size=None):
    """导航到指定页面。返回 True/False；失败原因写在 LAST_NAV['reason']。"""
    ok, reason = _nav(page, size)
    LAST_NAV["reason"] = "ok" if ok else reason
    return ok


def _nav(page, size=None):
    if not ensure_app():
        return False, "app-not-running"
    if page == "shelf":
        if not ensure_base(size):
            return False, "no-base"
        nodes = parse_nodes(dump_xml())
        n = find_any(nodes, ["书架"])
        # 底栏标签在屏幕底部 85% 以下；标题在顶部。已在书架页就不重复点。
        if n and n["bounds"][1] > screen_size()[1] * 0.85:
            return (True, "ok") if tap_bottom_nav("shelf", size) else (False, "no-shelf-tab")
        return True, "ok"
    if page == "zlib":
        if not tap_bottom_nav("zlib", size):
            return False, "no-zlib-tab"
        time.sleep(1.2)
        ok = has_text(["Z-Library", "书名 / 作者 / ISBN", "已加载", "搜索"])
        return (True, "ok") if ok else (False, "zlib-not-shown")
    if page == "settings":
        if not tap_bottom_nav("settings", size):
            return False, "no-settings-tab"
        time.sleep(1.0)
        ok = has_text(["设置"])
        return (True, "ok") if ok else (False, "settings-not-shown")
    if page == "results":
        return ensure_results()
    if page == "detail":
        ok, reason = ensure_results()
        if not ok:
            return False, reason
        if not tap_first_result():
            print("  [warn] 没找到可点的结果条目")
            return False, "no-result-item"
        time.sleep(2.0)
        ok = has_text(["书籍详情", "下载"])
        return (True, "ok") if ok else (False, "detail-not-shown")
    if page == "downloads":
        _nav("settings", size)
        time.sleep(0.8)
        if tap_text(["下载记录"], timeout=5):
            time.sleep(1.2)
            ok = has_text(["下载记录", "还没有下载记录"])
            return (True, "ok") if ok else (False, "downloads-not-shown")
        print("  [warn] 设置页没有「下载记录」入口")
        return False, "no-downloads-entry"
    return False, "unknown-page"


# ---------------------------------------------------------------- 采集


def meminfo():
    """返回 {total, java, native, graphics, code, stack, private_other, system, rss, main_total}（KB）。"""
    out = shell("dumpsys", "meminfo", PKG, timeout=120)
    d = {}
    if "App Summary" in out:
        block = out.split("App Summary", 1)[1]
        if "TOTAL SWAP" in block:
            block = block.split("TOTAL SWAP")[0]
        for line in block.splitlines():
            for key, name in APP_SUMMARY_KEYS:
                m = re.match(r"\s*" + re.escape(key) + r":\s+(\d+)", line)
                if m:
                    d[name] = int(m.group(1))
    m = re.search(r"^\s*TOTAL\s+(\d+)", out, re.M)
    if m:
        d["main_total"] = int(m.group(1))
    if "total" not in d and "main_total" in d:
        d["total"] = d["main_total"]
    return d


def mb(kb):
    return None if kb is None else kb / 1024.0


def frame_stats():
    """读 gfxinfo 汇总（不 reset）。"""
    out = shell("dumpsys", "gfxinfo", PKG, timeout=120)
    d = {}
    pats = (
        ("total_frames", r"Total frames rendered:\s*(\d+)"),
        ("janky_frames", r"Janky frames[^:]*:\s*(\d+)"),
        ("jank_pct", r"Janky frames[^:]*:\s*\d+\s*\(([0-9.]+)%\)"),
        ("p50", r"50th percentile:\s*(\d+)ms"),
        ("p90", r"90th percentile:\s*(\d+)ms"),
        ("p95", r"95th percentile:\s*(\d+)ms"),
        ("p99", r"99th percentile:\s*(\d+)ms"),
        ("missed_vsync", r"Missed Vsync:\s*(\d+)"),
        # API 30+ 有的版本不带 "Number " 前缀，两种都认
        ("slow_ui", r"(?:Number )?Slow UI thread:\s*(\d+)"),
        ("slow_bitmap", r"(?:Number )?Slow bitmap uploads:\s*(\d+)"),
        ("slow_issue", r"(?:Number )?Slow issue draw commands:\s*(\d+)"),
    )
    for key, pat in pats:
        m = re.search(pat, out)
        if m:
            d[key] = float(m.group(1)) if "." in m.group(1) else int(m.group(1))
    return d


def reset_gfx():
    shell("dumpsys", "gfxinfo", PKG, "reset")


def scroll_to_frames(target_frames, max_swipes=24, tag="", size=None):
    """真实滑动直到 gfxinfo 计数 >= target_frames（或滑够 max_swipes 次）。"""
    w, h = size or screen_size()
    x = w // 2
    y_from, y_to = int(h * 0.72), int(h * 0.32)
    swipes = 0
    while swipes < max_swipes:
        if swipes % 2 == 0:
            swipe(x, y_from, x, y_to, 220)
        else:
            swipe(x, y_to, x, y_from, 220)
        swipes += 1
        time.sleep(0.18)
        if swipes % 3 == 0:
            st = frame_stats()
            if st.get("total_frames", 0) >= target_frames:
                break
    st = frame_stats()
    print("  %s: swipes=%d frames=%s (target %d)" %
          (tag or "scroll", swipes, st.get("total_frames"), target_frames))
    return st


def measure_jank(page, target_frames, size=None, skip_net=False):
    print("[jank] %s（目标 %d 帧）" % (PAGE_TITLE.get(page, page), target_frames))
    if (skip_net or net_dead()) and page in NET_PAGES:
        why = "--skip-net" if skip_net else "网络已判定不可用"
        print("  [skip] %s：跳过网络依赖页（记 SKIP，不算 FAIL）" % why)
        return {"page": page, "ok": False, "skip": "net", "reason": "net-skipped"}
    if not nav(page, size):
        reason = nav_reason()
        print("  [skip] 无法到达页面（%s）——记 SKIP，不算 FAIL" % reason)
        return {"page": page, "ok": False, "reason": reason,
                "skip": "net" if is_net_reason(reason) else None}
    time.sleep(1.5)
    reset_gfx()
    time.sleep(0.3)
    st = scroll_to_frames(target_frames, tag=page, size=size)
    st["page"] = page
    st["ok"] = st.get("total_frames", 0) > 0
    if not st["ok"]:
        st["reason"] = "no-frames"
    return st


def measure_pss_sequence(settle=4.0, size=None, skip_net=False):
    """同一进程 canonical 序列：书架 -> Z-Library -> 结果 -> 详情 -> 下载记录 -> 设置 -> 返回书架。

    导航失败（含网络不可达）不再盲采 PSS：直接记一行 SKIP + 原因，判定时排除。
    """
    print("[pss] 冷启动到书架...")
    shell("am", "force-stop", PKG)
    time.sleep(1.0)
    start_app()
    time.sleep(settle + 1.0)
    rows = []
    for idx, page in enumerate(PAGE_ORDER):
        print("[pss] %s" % PAGE_TITLE.get(page, page))
        if (skip_net or net_dead()) and page in NET_PAGES:
            why = "--skip-net" if skip_net else "网络已判定不可用"
            print("  [skip] %s：跳过网络依赖页" % why)
            rows.append({"page": page, "skip": "net", "nav_ok": False})
            continue
        ok = nav("shelf", size) if page == "shelf_back" else nav(page, size)
        if not ok:
            reason = nav_reason()
            print("  [skip] 导航失败（%s）——记 SKIP，不计入判定" % reason)
            rows.append({"page": page, "skip": "net" if is_net_reason(reason) else reason,
                         "nav_ok": False})
            continue
        time.sleep(settle)
        m = meminfo()
        m["page"] = page
        m["nav_ok"] = True
        rows.append(m)
        print("  TOTAL=%.1f MB java=%.1f native=%.1f graphics=%.1f" % (
            mb(m.get("total")) or -1, mb(m.get("java")) or -1,
            mb(m.get("native")) or -1, mb(m.get("graphics")) or -1))
        if SHOT_DIR:
            shot(os.path.join(SHOT_DIR, "%02d-%s.png" % (idx, page)))
    return rows


def measure_reader(settle=4.0, size=None):
    """--reader：从书架打开第一本书，量阅读器 PSS（会话内追加，不打断 canonical 序列的结论）。"""
    print("[pss] 阅读器（打开书架第一本书）")
    if not nav("shelf", size):
        reason = nav_reason()
        print("  [skip] 无法回到书架（%s）" % reason)
        return {"page": "reader", "nav_ok": False, "skip": reason}
    time.sleep(1.2)
    nodes = [n for n in parse_nodes(dump_xml()) if visible(n)]
    w, h = size or screen_size()
    cands = []
    for n in nodes:
        if not n["clickable"]:
            continue
        x1, y1, x2, y2 = n["bounds"]
        if (x2 - x1) * (y2 - y1) < 120000:
            continue
        if y1 < h * 0.08 or y2 > h * 0.88:
            continue
        cands.append(n)
    if not cands:
        print("  [skip] 书架上找不到可点的书卡")
        return {"page": "reader", "nav_ok": False, "skip": "no-shelf-item"}
    cands.sort(key=lambda n: (n["bounds"][1], n["bounds"][0]))
    tap_node(cands[0], "open book")
    time.sleep(settle)
    opened = any(n["desc"] == "更多" for n in parse_nodes(dump_xml()))
    m = meminfo()
    m["page"] = "reader"
    m["nav_ok"] = True
    m["reader_opened"] = opened
    print("  TOTAL=%.1f MB java=%.1f native=%.1f graphics=%.1f opened=%s" % (
        mb(m.get("total")) or -1, mb(m.get("java")) or -1,
        mb(m.get("native")) or -1, mb(m.get("graphics")) or -1, opened))
    if SHOT_DIR:
        shot(os.path.join(SHOT_DIR, "90-reader.png"))
    keyevent(4)
    time.sleep(1.0)
    return m


def _displayed_ms():
    """logcat 里最近一条 ActivityTaskManager Displayed 的 +Nms（系统侧首帧）。"""
    out = adb("logcat", "-d", "-s", "ActivityTaskManager:I", "ActivityManager:I", timeout=60)
    hits = []
    for m in re.finditer(r"Displayed\s+%s/[^\n]*?\+(?:(\d+)s)?(\d+)ms" % re.escape(PKG), out):
        hits.append(int(m.group(1) or 0) * 1000 + int(m.group(2)))
    return hits[-1] if hits else None


def _app_cold_ms():
    """app 自己用 PBPERF/PBTIME 报的冷启动耗时（onCreate -> 首帧）。"""
    out = adb("logcat", "-d", "-s", "PBPERF:V", "PBTIME:V", timeout=60)
    hits = re.findall(r"cold_start[^\n]*?ms=([\d.]+)", out)
    return float(hits[-1]) if hits else None


def measure_coldstart(n=5):
    print("[coldstart] %d 次 force-stop + am start -W" % n)
    shell("logcat", "-c")
    vals = []
    details = []
    for i in range(n):
        shell("am", "force-stop", PKG)
        time.sleep(0.8)
        out = start_app()
        m = re.search(r"TotalTime:\s*(\d+)", out)
        d = {
            "run": i + 1,
            "total_ms": int(m.group(1)) if m else None,
            "wait_ms": int(re.search(r"WaitTime:\s*(\d+)", out).group(1))
            if re.search(r"WaitTime:\s*(\d+)", out) else None,
            "launch_state": (re.search(r"LaunchState:\s*(\w+)", out).group(1)
                             if re.search(r"LaunchState:\s*(\w+)", out) else "?"),
        }
        if m:
            vals.append(int(m.group(1)))
            print("  run %d: TotalTime=%s ms" % (i + 1, m.group(1)))
        else:
            print("  run %d: 没有 TotalTime（%r）" % (i + 1, out.strip()[:120]))
        time.sleep(1.2)
        d["displayed_ms"] = _displayed_ms()
        d["app_cold_ms"] = _app_cold_ms()
        details.append(d)
        print("    breakdown: WaitTime=%s Displayed=+%sms app_cold=%sms LaunchState=%s" % (
            d["wait_ms"], d["displayed_ms"], d["app_cold_ms"], d["launch_state"]))
    return vals, details


def device_meta():
    meta = {"serial": SERIAL}
    meta["model"] = shell("getprop", "ro.product.model").strip()
    meta["sdk"] = shell("getprop", "ro.build.version.sdk").strip()
    meta["release"] = shell("getprop", "ro.build.version.release").strip()
    meta["abi"] = shell("getprop", "ro.product.cpu.abi").strip()
    w, h = screen_size()
    meta["screen"] = "%dx%d" % (w, h)
    meta["density"] = shell("wm", "density").strip().replace("Physical density: ", "")
    mem = shell("cat", "/proc/meminfo")
    m = re.search(r"MemTotal:\s*(\d+)\s*kB", mem)
    meta["memtotal_gb"] = round(int(m.group(1)) / 1024.0 / 1024.0, 2) if m else None
    pkg = shell("dumpsys", "package", PKG)
    for k in ("versionName", "lastUpdateTime"):
        mm = re.search(k + r"=([^\n]+)", pkg)
        meta[k] = mm.group(1).strip() if mm else "?"
    flags = re.search(r"\bflags=\[([^\]]*)\]", pkg)
    meta["debuggable"] = "true" if flags and "DEBUGGABLE" in flags.group(1) else "false"
    meta["proxy"] = shell("settings", "get", "global", "http_proxy").strip()
    meta["reverse"] = adb("reverse", "--list").strip()
    meta["apk_md5"] = "?"
    meta["md5_via"] = "-"
    try:
        apk = shell("pm", "path", PKG).strip().replace("package:", "").splitlines()
        apk = apk[0].strip() if apk else ""
        if apk:
            meta["apk_path"] = apk
            md5, via = apk_md5(apk)
            meta["apk_md5"] = md5
            meta["md5_via"] = via
    except Exception as e:
        meta["apk_md5"] = "? (%s)" % e
    return meta


def apk_md5(apk_path):
    """取已安装 APK 的 md5：debuggable 包走 run-as；release 包 pull 到临时目录在 host 侧算。"""
    out = adb("exec-out", "run-as", PKG, "md5sum", apk_path, timeout=60)
    m = re.search(r"\b([0-9a-f]{32})\b", out)
    if m:
        return m.group(1), "run-as"
    tmpdir = tempfile.mkdtemp(prefix="p1b-apk-")
    dst = os.path.join(tmpdir, "base.apk")
    try:
        adb("pull", apk_path, dst, timeout=180)
        h = hashlib.md5()
        with open(dst, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        size = os.path.getsize(dst)
        return h.hexdigest(), "pull %.1f MB" % (size / 1024.0 / 1024.0)
    except Exception as e:
        return "? (%s)" % e, "failed"
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)


# ---------------------------------------------------------------- 报告


def fmt(v, nd=1):
    return "-" if v is None else ("%.*f" % (nd, v))


def md_table(headers, rows):
    out = ["| " + " | ".join(headers) + " |",
           "| " + " | ".join("---" for _ in headers) + " |"]
    for r in rows:
        out.append("| " + " | ".join(str(c) for c in r) + " |")
    return "\n".join(out)


def verdict(ok):
    return "**PASS**" if ok else "**FAIL**"


def verdict3(valid, ok):
    """三态判定：没有有效样本时记 SKIP，而不是 FAIL。"""
    if not valid:
        return "SKIP"
    return verdict(ok)


def skip_label(r):
    s = r.get("skip") or "nav-failed"
    if s == "net":
        return "网络不可达"
    return str(s)


def build_report(meta, cold, pss_rows, jank_rows, tag="", cold_details=None):
    L = []
    A = L.append
    shelf = next((r for r in pss_rows if r.get("page") == "shelf" and r.get("nav_ok")), None)

    def stable(r):
        return ((r.get("java") or 0) + (r.get("native") or 0)) / 1024.0 if r else 0.0

    cold_med = None
    if cold:
        s = sorted(cold)
        cold_med = s[len(s) // 2]
    cold_ok = cold_med is not None and cold_med <= L3["first_frame_ms"]
    valid_pss = [r for r in pss_rows if r.get("nav_ok")]
    valid_jank = [j for j in jank_rows if j.get("ok")]
    skipped = [r for r in pss_rows if not r.get("nav_ok")]
    pss_ok = bool(valid_pss) and all(stable(r) <= L3["resident_pss_mb"] for r in valid_pss)
    jank_ok = bool(valid_jank) and \
        all((j.get("jank_pct") or 0) <= L3["jank_pct_ref"] for j in valid_jank)

    head = "# P1b 新页面性能与内存回归（R7-D-PERF）"
    if tag:
        head += " · " + tag
    A(head)
    A("")
    A("> 生成：python scripts/android/perf-p1b.py all --out docs/android-port/p1b-perf.md  ")
    A("> 采集时间：%s  " % time.strftime("%Y-%m-%d %H:%M:%S"))
    if tag:
        A("> 轮次标记：**%s**  " % tag)
    A("> 口径：与 R6 一致（scripts/android/perf-device.py + docs/android-port/android-ui-verify-v4.md 第 21 节）；  ")
    A("> 阈值：docs/android-port/architecture-v2.md 第 3 节 L1/L3。")
    A("")
    A("## 0. 结论汇总")
    A("")
    A(md_table(["项", "实测", "阈值（architecture-v2）", "判定"], [
        ["冷启动 TotalTime（中位，n=%d）" % len(cold),
         ("%d ms" % cold_med) if cold_med else "-",
         "<= %d ms（L1 Activity 首帧）" % L3["first_frame_ms"],
         verdict3(bool(cold), cold_ok)],
        ["常驻 PSS（Java+Native，有效页 %d/%d）" % (len(valid_pss), len(pss_rows)),
         ("%.1f MB" % max(stable(r) for r in valid_pss)) if valid_pss else "-",
         "<= %d MB（无阅读器）" % L3["resident_pss_mb"], verdict3(bool(valid_pss), pss_ok)],
        ["滚动 jank（结果页/详情页）",
         (" / ".join("%.2f%%" % (j.get("jank_pct") or 0) for j in valid_jank) or "-"),
         "<= %.1f%%（R6 参考口径）" % L3["jank_pct_ref"], verdict3(bool(valid_jank), jank_ok)],
    ]))
    A("")
    A("> PSS 的 90 MB 门按 **Java+Native（稳定内存）** 判，不含可回收的 Graphics —— 与 architecture-v2 第 3 节")
    A("> L3 修订版一致，也是 R6 第 21.6 节核查后的结论（1440x2960 全屏三缓冲 + GL 纹理 + Modifier.blur")
    A("> 单独就占 126.7 MB，TOTAL 无法与 90 MB 直接比）。TOTAL 与 Graphics 在第 3 节逐页列出。")
    if skipped:
        A("")
        A("> SKIP %d 项（导航未到达，**不计入判定**）：%s" % (len(skipped), "、".join(
            "%s（%s）" % (PAGE_TITLE.get(r.get("page"), r.get("page")), skip_label(r))
            for r in skipped)))
    A("")
    A("## 1. 被测包与设备")
    A("")
    A(md_table(["项", "值"], [
        ["设备", "%s（Android %s / SDK %s / %s / %s GB）" % (
            meta.get("model"), meta.get("release"), meta.get("sdk"),
            meta.get("screen"), meta.get("memtotal_gb"))],
        ["density / abi", "%s / %s" % (meta.get("density"), meta.get("abi"))],
        ["serial", meta.get("serial")],
        ["包", "%s（versionName %s）" % (PKG, meta.get("versionName"))],
        ["APK md5", "%s（%s）" % (meta.get("apk_md5"), meta.get("md5_via", "-"))],
        ["lastUpdateTime / debuggable", "%s / %s" % (meta.get("lastUpdateTime"), meta.get("debuggable"))],
        ["系统字号（font_scale）", meta.get("font_scale") or "?"],
        ["http_proxy / adb reverse", "%s / %s" % (
            meta.get("proxy") or "(空)", (meta.get("reverse") or "(空)").replace("\n", "; "))],
    ]))
    if meta.get("apk_md5") and meta["apk_md5"] != R6["apk_md5"]:
        A("")
        A("> **注意**：本次 APK md5（%s）与 R6 基线（%s）不同，第 5 节的对比是「同机型、不同构建」的对比，"
          % (meta["apk_md5"], R6["apk_md5"]))
        A("> 差异里同时包含构建差异与页面差异，不能全部归因于 P1b 新页面。")
    A("")
    A("## 2. 冷启动（TotalTime x%d，每次先 force-stop）" % len(cold))
    A("")
    if cold:
        A(md_table(["采样"] + ["#%d" % (i + 1) for i in range(len(cold))] + ["中位", "min", "max"],
                   [["TotalTime (ms)"] + [str(v) for v in cold] + [cold_med, min(cold), max(cold)]]))
        A("")
        delta = cold_med - R6["coldstart_median"]
        A("R6 基线（同机型）：%s ms，中位 **%d ms** -> 本次 **%s %d ms**。" % (
            " / ".join(str(v) for v in R6["coldstart_ms"]), R6["coldstart_median"],
            "慢了" if delta > 0 else "快了", abs(delta)))
    else:
        A("_未采集（没有 TotalTime 输出）_")
    if cold_details:
        A("")
        A("**冷启动分解**（系统侧 vs app 侧；debug 包 + 模拟器，与 R6 release 真机不可直接比）：")
        A("")
        drows = []
        for d in cold_details:
            drows.append([
                d.get("run"),
                d.get("total_ms") if d.get("total_ms") is not None else "-",
                d.get("wait_ms") if d.get("wait_ms") is not None else "-",
                ("+%d ms" % d["displayed_ms"]) if d.get("displayed_ms") is not None else "-",
                ("%.1f ms" % d["app_cold_ms"]) if d.get("app_cold_ms") is not None else "-",
                d.get("launch_state") or "-",
            ])
        A(md_table(["运行", "TotalTime (ms)", "WaitTime (ms)", "Displayed", "app cold_start", "LaunchState"], drows))
        disp = [d["displayed_ms"] for d in cold_details if d.get("displayed_ms") is not None]
        appc = [d["app_cold_ms"] for d in cold_details if d.get("app_cold_ms") is not None]
        if disp or appc:
            A("")
            bits = []
            if disp:
                bits.append("System Displayed 中位 **+%d ms**" % sorted(disp)[len(disp) // 2])
            if appc:
                bits.append("app cold_start 中位 **%.1f ms**" % sorted(appc)[len(appc) // 2])
            A("；".join(bits) + "。")
        A("")
        A("> TotalTime 含进程冷启动、Application/Activity 初始化与系统调度；debug 包在模拟器上通常 4–7 s，")
        A("> 与 release 真机 R6 的 237 ms 不具可比性，照实记录，不作为 release 结论。")
    A("")
    A("## 3. PSS（同一进程 canonical 序列，单位 MB）")
    A("")
    if pss_rows:
        base = shelf
        rows = []
        for r in pss_rows:
            if not r.get("nav_ok"):
                rows.append([PAGE_TITLE.get(r.get("page"), r.get("page")),
                             "—", "—", "—", "—", "—", "—",
                             "SKIP（%s）" % skip_label(r)])
                continue
            jn = stable(r)
            rows.append([
                PAGE_TITLE.get(r.get("page"), r.get("page")),
                fmt(mb(r.get("total"))), fmt(mb(r.get("java"))), fmt(mb(r.get("native"))),
                fmt(mb(r.get("graphics"))), fmt(jn),
                ("%+.1f" % (jn - stable(base))) if base and base is not r else "—",
                "✓",
            ])
        A(md_table(["页面", "TOTAL", "Java", "Native", "Graphics", "Java+Native", "Δ vs 书架", "导航确认"], rows))
        A("")
        if base:
            A("书架基线：TOTAL **%.1f MB**、Java+Native **%.1f MB**、Graphics **%.1f MB**"
              "（R6 同机型书架：TOTAL 176.1 / Java 6.3 / Native 23.1 / Graphics 126.7）。" % (
                  mb(base.get("total")) or 0, stable(base), mb(base.get("graphics")) or 0))
    else:
        A("_未采集_")
    A("")
    A("## 4. 滚动 jank（gfxinfo reset -> 真实滑动 -> 读汇总）")
    A("")
    if jank_rows:
        rows = []
        for j in jank_rows:
            if not j.get("ok"):
                why = "网络不可达" if j.get("skip") == "net" else (j.get("reason") or "?")
                rows.append([PAGE_TITLE.get(j["page"], j["page"]), "—", "—", "—", "—", "—",
                             "—", "—", "SKIP（%s）" % why])
                continue
            rows.append([
                PAGE_TITLE.get(j["page"], j["page"]), j.get("total_frames"), j.get("janky_frames"),
                "%.2f%%" % (j.get("jank_pct") or 0), j.get("p50"), j.get("p90"), j.get("p95"),
                j.get("p99"), j.get("missed_vsync"),
            ])
        A(md_table(["页面", "帧数", "Janky", "jank%", "p50", "p90", "p95", "p99", "Missed Vsync"], rows))
        A("")
        A("R6 基线（阅读器滚动，10 次 swipe）：%d 帧、janky 0（%.2f%%）、p50/p90/p95/p99 = "
          "%d/%d/%d/%d ms、Missed Vsync 0。" % (
              R6["jank_frames"], R6["jank_pct"], R6["jank_p50"], R6["jank_p90"],
              R6["jank_p95"], R6["jank_p99"]))
    else:
        A("_未采集_")
    A("")
    A("## 5. 与 R6 基线对比")
    A("")
    A(md_table(["指标", "R6（Pixel 3 XL）", "本次 P1b", "变化"], [
        ["冷启动中位", "%d ms" % R6["coldstart_median"],
         ("%d ms" % cold_med) if cold_med else "-",
         ("%+d ms" % (cold_med - R6["coldstart_median"])) if cold_med else "-"],
        ["书架 PSS TOTAL", "%.1f MB" % R6["pss"]["shelf"],
         ("%.1f MB" % mb(shelf.get("total"))) if shelf and shelf.get("total") else "-",
         ("%+.1f MB" % (mb(shelf.get("total")) - R6["pss"]["shelf"]))
         if shelf and shelf.get("total") else "-"],
        ["书架 PSS Java+Native",
         "%.1f MB" % (R6["pss_parts_shelf"]["java"] + R6["pss_parts_shelf"]["native"]),
         ("%.1f MB" % stable(shelf)) if shelf else "-",
         ("%+.1f MB" % (stable(shelf) - R6["pss_parts_shelf"]["java"] - R6["pss_parts_shelf"]["native"]))
         if shelf else "-"],
        ["书架 Graphics", "%.1f MB" % R6["pss_parts_shelf"]["graphics"],
         ("%.1f MB" % mb(shelf.get("graphics"))) if shelf and shelf.get("graphics") else "-",
         ("%+.1f MB" % (mb(shelf.get("graphics")) - R6["pss_parts_shelf"]["graphics"]))
         if shelf and shelf.get("graphics") else "-"],
        ["滚动 jank%", "%.2f%%" % R6["jank_pct"],
         ("%.2f%%" % (jank_rows[0].get("jank_pct") or 0))
         if jank_rows and jank_rows[0].get("ok") else "-", "—"],
    ]))
    A("")
    A("> R6 的 PSS 场景是「书架 / 打开阅读器 / 滚 10 页 / 滚 30 页 / 返回书架」；本次是 P1b 新页面，")
    A("> 直接可比项只有书架基线与冷启动，阅读器指标仍以 R6 第 21 节为准。")
    A("")
    reader_row = next((r for r in pss_rows if r.get("page") == "reader" and r.get("nav_ok")), None)
    reader_delta = (stable(reader_row) - stable(shelf)) if reader_row and shelf else None
    A("## 6. 是否满足 architecture-v2 L3")
    A("")
    A(md_table(["指标", "L3 目标", "本次", "判定"], [
        ["Activity 首帧 / 冷启动", "<= %d ms" % L3["first_frame_ms"],
         ("%d ms" % cold_med) if cold_med else "-", verdict3(bool(cold), cold_ok)],
        ["书架可交互", "<= %d ms" % L3["interactive_ms"],
         ("%d ms（TotalTime 近似）" % cold_med) if cold_med else "-",
         verdict(cold_med is not None and cold_med <= L3["interactive_ms"])],
        ["常驻 PSS（无阅读器，Java+Native）", "<= %d MB" % L3["resident_pss_mb"],
         ("%.1f MB" % max(stable(r) for r in valid_pss)) if valid_pss else "-",
         verdict3(bool(valid_pss), pss_ok)],
        ["滚动掉帧（P1b 列表页，参考 L1 书架口径）", "<= %.1f%% jank" % L3["jank_pct_ref"],
         ("%.2f%%" % max((j.get("jank_pct") or 0) for j in valid_jank)) if valid_jank else "-",
         verdict3(bool(valid_jank), jank_ok)],
        ["阅读器稳定内存增量（Java+Native，--reader）", "<= %d MB" % L3["reader_delta_mb"],
         ("%+.1f MB" % reader_delta) if reader_delta is not None else "未采（加 --reader）",
         verdict3(reader_delta is not None,
                  (reader_delta is not None and reader_delta <= L3["reader_delta_mb"]))],
    ]))
    A("")
    A("> 阅读器增量行只有加 `--reader` 才采集；不加时记 SKIP，不参与 PASS/FAIL。")
    A("> L3 里「首屏 <= 300 ms P95」等指标针对 PDF 阅读器，P1b 页面不适用，由 R6 第 21 节与 spike2 报告覆盖。")
    A("")
    A("## 7. 复现命令")
    A("")
    A("~~~bash")
    A("export MSYS_NO_PATHCONV=1")
    A("S=%s" % meta.get("serial"))
    A("# 一次性：把电脑代理桥进手机（搜索页/结果页需要网络）")
    A("SERIAL_OVERRIDE=$S bash scripts/android/p1a-devnet.sh on")
    A("# 全部采集 + 出本报告")
    if tag:
        A("python scripts/android/perf-p1b.py --serial $S all --tag-label '%s' "
          "--out docs/android-port/p1b-perf.md" % tag)
    else:
        A("python scripts/android/perf-p1b.py --serial $S all --out docs/android-port/p1b-perf.md")
    A("# 可选：200% 字号重跑 + 阅读器 PSS")
    A("python scripts/android/perf-p1b.py --serial $S all --font-scale 2.0 --reader --tag-label font200 "
      "--out docs/android-port/p1b-perf-font200.md")
    A("# 单项")
    A("python scripts/android/perf-p1b.py --serial $S coldstart --n 5")
    A("python scripts/android/perf-p1b.py --serial $S pss")
    A("python scripts/android/perf-p1b.py --serial $S jank")
    A("~~~")
    A("")
    A("## 8. 未覆盖 / 已知限制")
    A("")
    A("- **滚动帧数不足**：详情页等可滚动内容短的页面，gfxinfo 累计帧数可能到不了目标值；表里会显示")
    A("  实际帧数，此时 jank% 只代表这些帧，不能与 300 帧样本直接比。")
    A("- **搜索结果依赖网络**：代理不通/镜像不可达时结果页与详情页无法到达，相关行标 SKIP。")
    A("- **Graphics 不可回收部分**：TOTAL PSS 含 GPU 缓存，回落幅度受 GPU 高水位影响；按 R6 第 21.6 节")
    A("  单列观测，不作硬门。")
    A("- **字号与阅读器**：`--font-scale F` 可在指定系统字号（如 2.0 = 200%）下重跑全部页面；`--reader`")
    A("  追加阅读器 PSS。横屏、深色等视觉项仍归 ui-verify，不在本性能口径内。")
    A("")
    return "\n".join(L)


# ---------------------------------------------------------------- 命令


def cmd_state(a):
    if not require_device():
        return 1
    print(json.dumps(device_meta(), ensure_ascii=False, indent=1))
    return 0


def cmd_coldstart(a):
    if not require_device():
        return 1
    vals, details = measure_coldstart(a.n)
    if vals:
        s = sorted(vals)
        print("cold start: %s ms | min %d med %d max %d" % (vals, s[0], s[len(s) // 2], s[-1]))
    print(json.dumps(details, ensure_ascii=False, indent=1))
    return 0


def cmd_pss(a):
    if not require_device():
        return 1
    rows = measure_pss_sequence(a.settle, skip_net=a.skip_net)
    for r in rows:
        if not r.get("nav_ok"):
            print("%-24s SKIP（%s）" % (r.get("page"), skip_label(r)))
            continue
        print("%-24s TOTAL=%7.1f java=%6.1f native=%6.1f graphics=%7.1f" % (
            r.get("page"), mb(r.get("total")) or -1, mb(r.get("java")) or -1,
            mb(r.get("native")) or -1, mb(r.get("graphics")) or -1))
    return 0


def cmd_jank(a):
    if not require_device():
        return 1
    for page, frames in (("results", a.results_frames), ("detail", a.detail_frames)):
        print("  -> %s" % json.dumps(measure_jank(page, frames, skip_net=a.skip_net),
                                     ensure_ascii=False))
    return 0


def cmd_all(a):
    global SHOT_DIR
    if not require_device():
        return 1
    SHOT_DIR = a.shots or os.path.join("spikes", "p1b-perf-shots", time.strftime("%Y%m%d-%H%M%S"))
    os.makedirs(SHOT_DIR, exist_ok=True)
    old_font = (shell("settings", "get", "system", "font_scale").strip() or "1.0")
    font_changed = False
    try:
        if a.font_scale:
            shell("settings", "put", "system", "font_scale", str(a.font_scale))
            font_changed = True
            print("[font] 系统字号 -> %s（原 %s）" % (a.font_scale, old_font))
            shell("am", "force-stop", PKG)
            time.sleep(1.0)
        if a.ensure_proxy:
            print("[net] 配置 reverse + http_proxy（p1a-devnet.sh on）")
            env = dict(os.environ, SERIAL_OVERRIDE=SERIAL or "")
            r = subprocess.run(["bash", "scripts/android/p1a-devnet.sh", "on"],
                               capture_output=True, text=True, env=env)
            print("  " + (r.stdout or r.stderr or "").strip().replace("\n", "\n  "))
            shell("am", "force-stop", PKG)
            time.sleep(1.0)
        if a.tag_label:
            print("[tag] 轮次标记：%s" % a.tag_label)
        meta = device_meta()
        meta["font_scale"] = shell("settings", "get", "system", "font_scale").strip()
        meta["shots_dir"] = SHOT_DIR
        print("[meta] %s / %s / md5 %s / font_scale %s" % (
            meta.get("model"), meta.get("versionName"), meta.get("apk_md5"), meta.get("font_scale")))
        cold, cold_details = measure_coldstart(a.n)
        pss_rows = measure_pss_sequence(a.settle, skip_net=a.skip_net)
        if a.reader:
            pss_rows.append(measure_reader(a.settle))
        jank_rows = [measure_jank("results", a.results_frames, skip_net=a.skip_net),
                     measure_jank("detail", a.detail_frames, skip_net=a.skip_net)]
        md = build_report(meta, cold, pss_rows, jank_rows, tag=a.tag_label, cold_details=cold_details)
        if a.out:
            d = os.path.dirname(a.out)
            if d:
                os.makedirs(d, exist_ok=True)
            with open(a.out, "w", encoding="utf-8") as f:
                f.write(md + "\n")
            print("\n[report] -> %s" % a.out)
        else:
            print(md)
        cold_med = sorted(cold)[len(cold) // 2] if cold else None
        payload = {
            "schema": "p1b-perf/1",
            "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
            "tag": a.tag_label or "",
            "l3": dict(L3),
            "meta": meta,
            "cold": cold,
            "cold_details": cold_details,
            "cold_median_ms": cold_med,
            "pss": pss_rows,
            "jank": jank_rows,
            "shots_dir": SHOT_DIR,
            "skips": [r.get("page") for r in pss_rows + jank_rows if not r.get("nav_ok", True)],
        }
        json_path = a.json
        if not json_path:
            json_path = (os.path.splitext(a.out)[0] + ".json") if a.out else os.path.join(
                "spikes", "p1b-perf-%s.json" % time.strftime("%Y%m%d-%H%M%S"))
        jd = os.path.dirname(json_path)
        if jd:
            os.makedirs(jd, exist_ok=True)
        with open(json_path, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=1)
        print("[json] -> %s" % json_path)
        shot(os.path.join(SHOT_DIR, "99-final.png"))
        return 0
    finally:
        if font_changed:
            shell("settings", "put", "system", "font_scale", old_font)
            print("[font] 恢复系统字号 -> %s" % old_font)
            shell("am", "force-stop", PKG)
            time.sleep(0.5)


def cmd_nav(a):
    if not require_device():
        return 1
    ok = nav(a.page)
    print("nav %s -> %s" % (a.page, ok))
    return 0 if ok else 1


def cmd_ui(a):
    if not require_device():
        return 1
    for n in parse_nodes(dump_xml()):
        if not visible(n):
            continue
        if not (n["text"] or n["desc"]) and not a.all:
            continue
        print("%-34s | %-30s | click=%d scroll=%d | %s" % (
            n["text"][:34], n["desc"][:30], n["clickable"], n["scrollable"], n["bounds"]))
    return 0


def cmd_tap(a):
    if not require_device():
        return 1
    if not tap_text([a.text]):
        print("NOT FOUND:", a.text)
        return 2
    return 0


def cmd_tapxy(a):
    if not require_device():
        return 1
    shell("input", "tap", str(a.x), str(a.y))
    print("tap", a.x, a.y)
    return 0


def cmd_key(a):
    if not require_device():
        return 1
    shell("input", "keyevent", str(a.name))
    print("key", a.name)
    return 0


def cmd_shot(a):
    if not require_device():
        return 1
    shot(a.name if a.name.endswith(".png") else a.name + ".png")
    return 0


def main():
    global SERIAL
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--serial", default=None, help="adb serial（默认 8A9Y0G2XJ）")
    ap = argparse.ArgumentParser(description="P1b 新页面性能与内存回归（口径同 R6）", parents=[common])
    sub = ap.add_subparsers(dest="cmd", required=True)

    sub.add_parser("state", parents=[common])
    p = sub.add_parser("coldstart", parents=[common])
    p.add_argument("--n", type=int, default=5)
    p = sub.add_parser("pss", parents=[common])
    p.add_argument("--settle", type=float, default=4.0)
    p.add_argument("--skip-net", action="store_true", help="跳过结果页/详情页（网络不可用时用）")
    p.add_argument("--tag-label", default="", help="轮次标记（写进报告标题）")
    p = sub.add_parser("jank", parents=[common])
    p.add_argument("--results-frames", type=int, default=300)
    p.add_argument("--detail-frames", type=int, default=200)
    p.add_argument("--skip-net", action="store_true", help="跳过结果页/详情页（网络不可用时用）")
    p.add_argument("--tag-label", default="", help="轮次标记（写进报告标题）")
    p = sub.add_parser("all", parents=[common])
    p.add_argument("--n", type=int, default=5)
    p.add_argument("--settle", type=float, default=4.0)
    p.add_argument("--results-frames", type=int, default=300)
    p.add_argument("--detail-frames", type=int, default=200)
    p.add_argument("--skip-net", action="store_true", help="跳过结果页/详情页（网络不可用时用）")
    p.add_argument("--tag-label", default="", help="轮次标记（写进报告标题，如 pre-cookie-fix / post-cookie-fix）")
    p.add_argument("--out", default="docs/android-port/p1b-perf.md")
    p.add_argument("--ensure-proxy", action="store_true", help="先跑 p1a-devnet.sh on")
    p.add_argument("--shots", default="", help="截图输出目录（默认 spikes/p1b-perf-shots/<时间戳>/）")
    p.add_argument("--json", default="", help="JSON 结果输出路径（默认与 --out 同名 .json）")
    p.add_argument("--font-scale", type=float, default=None,
                   help="把系统字号设为该值后测量（如 2.0=200%%），跑完自动恢复")
    p.add_argument("--reader", action="store_true", help="追加阅读器 PSS（打开书架第一本书）")
    p = sub.add_parser("nav", parents=[common])
    p.add_argument("page", choices=["shelf", "zlib", "results", "detail", "downloads", "settings"])
    p = sub.add_parser("ui", parents=[common])
    p.add_argument("--all", action="store_true")
    p = sub.add_parser("tap", parents=[common])
    p.add_argument("text")
    p = sub.add_parser("tapxy", parents=[common])
    p.add_argument("x", type=int)
    p.add_argument("y", type=int)
    p = sub.add_parser("key", parents=[common])
    p.add_argument("name")
    p = sub.add_parser("shot", parents=[common])
    p.add_argument("name")
    a = ap.parse_args()
    if getattr(a, "serial", None):
        SERIAL = a.serial

    fn = {"state": cmd_state, "coldstart": cmd_coldstart, "pss": cmd_pss, "jank": cmd_jank,
          "all": cmd_all, "nav": cmd_nav, "ui": cmd_ui, "tap": cmd_tap, "tapxy": cmd_tapxy,
          "key": cmd_key, "shot": cmd_shot}[a.cmd]
    sys.exit(fn(a) or 0)


if __name__ == "__main__":
    main()
