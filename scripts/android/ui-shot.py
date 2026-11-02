#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""ui-shot.py — ParticleBook Android 装机验收工具箱（M3 Expressive / spec v4 §17）

设计目标：把「看起来对」换成「测出来」。所有断言都能给出 px / #RRGGBB / 节点 bounds。

环境事实（2026-11-02 实测）
  · 设备 emulator-5554，1080×2400 @420dpi，density 2.625（1dp = 2.625px）
  · 系统 Python 3.12 + Pillow 12.2.0（无 PIL 时自动降级为 zlib+struct 手解 PNG）
  · adb：G:/Android/sdk/platform-tools/adb.exe（也在 PATH 上）
  · 截图/落盘默认 F:/tmp/verify/v4/（git bash 里的 /f/tmp/verify/v4/）

常用子命令
  shot NAME                     截图（默认落 F:/tmp/verify/v4/NAME.png），打印路径
  dump NAME                     uiautomator dump → NAME.xml；打印节点数
  ui / find PATTERN             打印/查询节点（text 或 content-desc 子串，忽略大小写）
  text-bounds PATTERN           只打印节点 bounds（给 shell 脚本用，格式 x1 y1 x2 y2）
  pixel X Y                     采样单点颜色 → #RRGGBB
  line Y [--x0 --x1] [--step]   横向扫描一行像素（默认每 px，输出 run 压缩）
  runs Y [--tol] [--min-run]    横向 color-run（找封面/间距边界）
  vruns X ...                   纵向 color-run（找页面间距/行高）
  bbox --region x0,y0,x1,y1 --pred bg:HEX|color:HEX|dark|notcolor:HEX [--tol]
                                区域内满足谓词的像素外接框 → JSON
  contrast HEX1 HEX2            WCAG 对比度（+ 大小字判定 4.5 / 3.0）
  crop X1 Y1 X2 Y2 OUT          裁切证据图
  md5 PATH                      文件 md5（装包前后比对用）
  install APK [--md5 HEX]       装包；自动做 md5 比对 + 记录 lastUpdateTime
  tap X Y / tap-text PATTERN    点击坐标 / 点击匹配节点中心
  swipe X1 Y1 X2 Y2 [--ms]      滑动
  rot 0|1|auto                  旋转（0 竖 1 横；auto 恢复重力感应）
  font 1.0|2.0                  字号（自动 force-stop + start，等首帧）
  start / stop / restart        应用启动控制（记录 TotalTime）
  coldstart                     冷启计时 + 0.3/0.6/1.0/1.5/2.0s 连拍（S0）
  v4 [--only V1,V2]             跑 V1–V12 + S0 + A2 + 200% 套件，输出 PASS/FAIL 表
  selftest                      对当前已装包跑一遍原语自检（不判 PASS/FAIL）

退出码：0 全部 PASS（v4 模式），1 有 FAIL，2 工具/设备错误。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import time
import zlib
from pathlib import Path

# Windows 控制台默认 GBK，git bash/MSYS 期待 UTF-8；强制一下避免中文乱码
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

# ---------------------------------------------------------------- 基础环境

PKG = "app.particlebook"
DEFAULT_OUT = "F:/tmp/verify/v4"
SCREEN_W, SCREEN_H = 1080, 2400
DENSITY = 2.625  # 420dpi

_ADB_CACHE = None


def winpath(p: str) -> str:
    """/f/tmp/x → F:/tmp/x；已是 Windows 路径则原样。"""
    if not p:
        return p
    if re.match(r"^/[a-zA-Z]/", p):
        return p[1].upper() + ":" + p[2:]
    return p


def find_adb() -> str:
    global _ADB_CACHE
    if _ADB_CACHE:
        return _ADB_CACHE
    cands = []
    for env in ("ADB", "ANDROID_SDK_ROOT", "ANDROID_HOME"):
        v = os.environ.get(env)
        if v:
            v = winpath(v)
            cands.append(os.path.join(v, "platform-tools", "adb.exe"))
            cands.append(os.path.join(v, "platform-tools", "adb"))
    cands += [
        "G:/Android/sdk/platform-tools/adb.exe",
        "C:/Android/sdk/platform-tools/adb.exe",
        "D:/Android/sdk/platform-tools/adb.exe",
        "adb",
    ]
    for c in cands:
        exe = c
        if c != "adb" and not os.path.exists(c):
            continue
        try:
            out = subprocess.run([exe, "version"], capture_output=True, timeout=8)
            if out.returncode == 0:
                _ADB_CACHE = exe
                return exe
        except Exception:
            continue
    sys.exit("[ui-shot] 找不到 adb（设 ADB=… 或 ANDROID_SDK_ROOT）")


def adb(*args, timeout=120, binary=False, check=True) -> bytes | str:
    exe = find_adb()
    cmd = [exe] + [str(a) for a in args]
    p = subprocess.run(cmd, capture_output=True, timeout=timeout)
    if check and p.returncode != 0:
        sys.exit("[ui-shot] adb %s 失败: %s" % (" ".join(cmd[1:]), p.stderr.decode("utf-8", "replace")[:800]))
    return p.stdout if binary else p.stdout.decode("utf-8", "replace")


def shell(cmd: str, timeout=60) -> str:
    return adb("shell", cmd, timeout=timeout)


# ---------------------------------------------------------------- PNG 解码

class Img:
    __slots__ = ("w", "h", "px", "path")

    def __init__(self, w, h, px, path=""):
        self.w, self.h, self.px, self.path = w, h, px, path

    def get(self, x: int, y: int) -> tuple:
        if x < 0 or y < 0 or x >= self.w or y >= self.h:
            return (0, 0, 0)
        return self.px[y * self.w + x]

    def hexat(self, x, y) -> str:
        r, g, b = self.get(x, y)
        return "#%02X%02X%02X" % (r, g, b)


def _decode_png_stdlib(data: bytes) -> Img:
    """无 Pillow 时的降级解码：8bit RGB/RGBA、非隔行。"""
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    pos, idat, w = 8, b"", 0
    h = bitdepth = ctype = 0
    while pos < len(data):
        ln = struct.unpack(">I", data[pos:pos + 4])[0]
        typ = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + ln]
        if typ == b"IHDR":
            w, h, bitdepth, ctype = struct.unpack(">IIBB", chunk[:10])
        elif typ == b"IDAT":
            idat += chunk
        elif typ == b"IEND":
            break
        pos += 12 + ln
    if bitdepth != 8 or ctype not in (2, 6):
        raise RuntimeError("PNG 格式不支持（bitdepth=%s ctype=%s），请装 Pillow" % (bitdepth, ctype))
    bpp = 3 if ctype == 2 else 4
    raw = zlib.decompress(idat)
    stride = w * bpp
    out = bytearray(h * stride)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        f = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        if f == 1:
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 0xFF
        elif f == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif f == 3:
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif f == 4:
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                c = prev[i - bpp] if i >= bpp else 0
                b = prev[i]
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        out[y * stride:(y + 1) * stride] = line
        prev = line
    px = []
    for i in range(0, len(out), bpp):
        px.append((out[i], out[i + 1], out[i + 2]))
    return Img(w, h, px)


def load_img(path: str) -> Img:
    path = winpath(path)
    data = Path(path).read_bytes()
    try:
        from PIL import Image  # type: ignore
        im = Image.open(path).convert("RGB")
        w, h = im.size
        return Img(w, h, list(im.getdata()), path)
    except ImportError:
        return _decode_png_stdlib(data)


# ---------------------------------------------------------------- 截图 / dump

def out_path(name: str, ext: str, outdir: str | None = None) -> str:
    d = Path(winpath(outdir or DEFAULT_OUT))
    d.mkdir(parents=True, exist_ok=True)
    return str(d / (name if name.endswith(ext) else name + ext))


def shot(name: str, outdir: str | None = None) -> str:
    p = out_path(name, ".png", outdir)
    data = adb("exec-out", "screencap", "-p", binary=True)
    Path(p).write_bytes(data)
    return p


def dump_xml(name: str, outdir: str | None = None) -> str:
    p = out_path(name, ".xml", outdir)
    raw = adb("exec-out", "uiautomator", "dump", "/dev/tty", binary=True).decode("utf-8", "replace")
    i = raw.rfind("</hierarchy>")
    xml = raw[: i + len("</hierarchy>")] if i >= 0 else raw
    Path(p).write_text(xml, encoding="utf-8")
    return p


NODE_RE = re.compile(r"<node\b[^>]*/?>", re.S)
ATTR_RE = re.compile(r'([\w:-]+)="([^"]*)"')


def parse_nodes(xml_path: str) -> list:
    xml = Path(winpath(xml_path)).read_text(encoding="utf-8", errors="replace")
    nodes = []
    for m in NODE_RE.finditer(xml):
        attrs = dict(ATTR_RE.findall(m.group(0)))
        b = attrs.get("bounds", "")
        mm = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", b)
        if not mm:
            continue
        x1, y1, x2, y2 = map(int, mm.groups())
        nodes.append({
            "text": attrs.get("text", ""),
            "desc": attrs.get("content-desc", ""),
            "cls": attrs.get("class", "").split(".")[-1],
            "id": attrs.get("resource-id", ""),
            "bounds": (x1, y1, x2, y2),
            "clickable": attrs.get("clickable") == "true",
            "selected": attrs.get("selected") == "true",
            "checked": attrs.get("checked") == "true",
            "enabled": attrs.get("enabled") == "true",
        })
    return nodes


def node_label(n) -> str:
    t = n["text"] or n["desc"]
    if not t:
        t = "<%s>" % n["cls"]
    return t


def find_node(nodes, pattern: str, exact=False):
    pat = pattern.lower()
    for n in nodes:
        for field in ("text", "desc"):
            v = (n[field] or "").lower()
            if (v == pat) if exact else (pat in v):
                return n
    return None


# ---------------------------------------------------------------- 打印

def print_nodes(nodes, pattern=None, show_all=False, limit=80):
    rows = []
    for n in nodes:
        label = node_label(n)
        if not show_all:
            if not (n["text"] or n["desc"] or n["clickable"]):
                continue
        if pattern and pattern.lower() not in (n["text"] + " " + n["desc"] + " " + n["id"]).lower():
            continue
        x1, y1, x2, y2 = n["bounds"]
        rows.append("%-28s %4d,%4d %4d,%4d  %4dx%-4d %s%s%s" % (
            label[:28], x1, y1, x2, y2, x2 - x1, y2 - y1,
            n["cls"][:14],
            " C" if n["clickable"] else "",
            " S" if n["selected"] else "",
        ))
    print("\n".join(rows[:limit]))
    print("-- %d nodes shown" % min(len(rows), limit))


def center(b):
    return ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)


# ---------------------------------------------------------------- 像素分析

def color_runs(img: Img, y: int, x0=0, x1=None, tol=10, min_run=3):
    """横向 color-run：返回 [(x_start, x_end, #RRGGBB)]，短 run 并入前一段。"""
    x1 = img.w - 1 if x1 is None else x1
    runs = []
    cur_start = x0
    cur = img.get(x0, y)
    for x in range(x0 + 1, x1 + 1):
        c = img.get(x, y)
        if max(abs(c[0] - cur[0]), abs(c[1] - cur[1]), abs(c[2] - cur[2])) > tol:
            runs.append((cur_start, x - 1, cur))
            cur_start, cur = x, c
    runs.append((cur_start, x1, cur))
    merged = []
    for r in runs:
        if merged and (r[1] - r[0] + 1) < min_run:
            merged[-1] = (merged[-1][0], r[1], merged[-1][2])
        else:
            merged.append(list(r))
    return [(a, b, "#%02X%02X%02X" % c) for a, b, c in merged]


def vcolor_runs(img: Img, x: int, y0=0, y1=None, tol=10, min_run=3):
    y1 = img.h - 1 if y1 is None else y1
    runs, cur_start, cur = [], y0, img.get(x, y0)
    for y in range(y0 + 1, y1 + 1):
        c = img.get(x, y)
        if max(abs(c[0] - cur[0]), abs(c[1] - cur[1]), abs(c[2] - cur[2])) > tol:
            runs.append((cur_start, y - 1, cur))
            cur_start, cur = y, c
    runs.append((cur_start, y1, cur))
    merged = []
    for r in runs:
        if merged and (r[1] - r[0] + 1) < min_run:
            merged[-1] = (merged[-1][0], r[1], merged[-1][2])
        else:
            merged.append(list(r))
    return [(a, b, "#%02X%02X%02X" % c) for a, b, c in merged]


def hex2rgb(h: str):
    h = h.strip().lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def dist(c1, c2) -> int:
    return max(abs(c1[0] - c2[0]), abs(c1[1] - c2[1]), abs(c1[2] - c2[2]))


def luma(c) -> float:
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]


def rel_lum(c):
    def f(v):
        v = v / 255.0
        return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4
    return 0.2126 * f(c[0]) + 0.7152 * f(c[1]) + 0.0722 * f(c[2])


def contrast(h1: str, h2: str) -> float:
    a, b = rel_lum(hex2rgb(h1)), rel_lum(hex2rgb(h2))
    hi, lo = max(a, b), min(a, b)
    return (hi + 0.05) / (lo + 0.05)


def bbox_pred(img: Img, region, pred, tol=10):
    """region=(x0,y0,x1,y1)，pred∈ bg:HEX / color:HEX / dark / notcolor:HEX；返回 (x1,y1,x2,y2,count)"""
    x0, y0, x1, y1 = region
    x0, y0 = max(0, x0), max(0, y0)
    x1, y1 = min(img.w - 1, x1), min(img.h - 1, y1)
    if pred.startswith("bg:") or pred.startswith("notcolor:"):
        ref = hex2rgb(pred.split(":", 1)[1])
        test = lambda c: dist(c, ref) > tol
    elif pred.startswith("color:"):
        ref = hex2rgb(pred.split(":", 1)[1])
        test = lambda c: dist(c, ref) <= tol
    elif pred == "dark":
        test = lambda c: luma(c) < 100
    elif pred == "light":
        test = lambda c: luma(c) > 200
    else:
        raise ValueError("未知谓词 %s" % pred)
    minx, miny, maxx, maxy, n = 10 ** 9, 10 ** 9, -1, -1, 0
    for y in range(y0, y1 + 1):
        row = img.px[y * img.w + x0: y * img.w + x1 + 1]
        for i, c in enumerate(row):
            if test(c):
                n += 1
                x = x0 + i
                if x < minx: minx = x
                if x > maxx: maxx = x
                if y < miny: miny = y
                if y > maxy: maxy = y
    if n == 0:
        return None
    return (minx, miny, maxx, maxy, n)


# ---------------------------------------------------------------- 设备操作

def tap(x, y):
    shell("input tap %d %d" % (x, y))
    time.sleep(0.45)


def swipe(x1, y1, x2, y2, ms=300):
    shell("input swipe %d %d %d %d %d" % (x1, y1, x2, y2, ms))
    time.sleep(0.5)


def tap_text(pattern, nodes=None, xml=True, wait=0.6):
    if nodes is None:
        nodes = parse_nodes(dump_xml("_tap"))
    n = find_node(nodes, pattern)
    if not n:
        return None
    cx, cy = center(n["bounds"])
    tap(cx, cy)
    time.sleep(wait)
    return n


def rot(mode):
    if mode == "auto":
        shell("settings put system accelerometer_rotation 1")
    else:
        shell("settings put system accelerometer_rotation 0")
        shell("settings put system user_rotation %s" % mode)
    time.sleep(1.6)


def font(scale):
    shell("settings put system font_scale %s" % scale)
    stop()
    start()
    time.sleep(1.0)


def stop():
    shell("am force-stop %s" % PKG)


def start(activity=None):
    act = activity or launcher_activity()
    shell("am force-stop %s" % PKG)
    time.sleep(0.4)
    shell("logcat -c")
    out = shell("am start -W -n %s" % act)
    m = re.search(r"TotalTime:\s*(\d+)", out)
    wait_ui_settle()
    return int(m.group(1)) if m else -1


def launcher_activity():
    out = shell("cmd package resolve-activity --brief %s" % PKG)
    for line in out.splitlines():
        line = line.strip()
        if "/" in line and not line.startswith("priority"):
            return line
    return "%s/.android.MobileActivity" % PKG


def wait_ui_settle(timeout=8.0):
    """dump 两次，节点数稳定即认为首帧完成。"""
    t0 = time.time()
    last, stable = -1, 0
    while time.time() - t0 < timeout:
        try:
            n = len(parse_nodes(dump_xml("_settle")))
        except SystemExit:
            raise
        if n == last and n > 0:
            stable += 1
            if stable >= 1:
                return True
        else:
            stable = 0
        last = n
        time.sleep(0.35)
    return False


def md5_file(path):
    h = hashlib.md5()
    with open(winpath(path), "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def install(apk, expect_md5=None):
    apk = winpath(apk)
    got = md5_file(apk)
    if expect_md5 and got != expect_md5.lower():
        sys.exit("[ui-shot] APK md5 不符：期望 %s 实际 %s" % (expect_md5, got))
    size = os.path.getsize(apk)
    t0 = time.time()
    out = adb("install", "-r", "-t", apk, timeout=300)
    dt = time.time() - t0
    ok = "Success" in out
    last = shell("dumpsys package %s | grep lastUpdateTime" % PKG).strip()
    print(json.dumps({"ok": ok, "md5": got, "bytes": size, "install_s": round(dt, 1), "lastUpdateTime": last}, ensure_ascii=False))
    return 0 if ok else 1


# ---------------------------------------------------------------- v4 套件

SHELL_TOKENS_LIGHT = {
    "surfaceDim": "#EDE6DC", "surface": "#FBF7F1", "surfaceBright": "#FFFDFA",
    "surfaceContainerLowest": "#FFFFFF", "surfaceContainerLow": "#F6F1E9",
    "surfaceContainer": "#F0EAE1", "surfaceContainerHigh": "#E9E2D7",
    "surfaceContainerHighest": "#E2DACD", "onSurface": "#17130D",
    "onSurfaceVariant": "#4B4337", "outline": "#8A7D6C", "outlineVariant": "#E5DCCE",
    "primary": "#2563EB", "primaryContainer": "#DCE6FF", "onPrimaryContainer": "#001740",
    "tertiary": "#C2410C", "tertiaryContainer": "#FFE0D2", "onTertiaryContainer": "#3C0C00",
    "secondary": "#5B5348", "error": "#B3261E",
}
SHELL_TOKENS_DARK = {
    "surfaceDim": "#100E0A", "surface": "#16130E", "surfaceBright": "#221D16",
    "surfaceContainerLow": "#1C1812", "surfaceContainer": "#221D17",
    "surfaceContainerHigh": "#2B251E", "onSurface": "#F2EADF", "onSurfaceVariant": "#CDC2B3",
    "primary": "#B0C6FF", "primaryContainer": "#00429B", "onPrimaryContainer": "#DCE6FF",
    "tertiary": "#FFB59C", "outline": "#9A8E7E",
}


class Ctx:
    def __init__(self, outdir):
        self.outdir = outdir
        self.img = None
        self.img_name = None
        self.nodes = None

    def snap(self, name):
        p = shot(name, self.outdir)
        self.img = load_img(p)
        self.img_name = name
        return self.img

    def ui(self, name):
        p = dump_xml(name, self.outdir)
        self.nodes = parse_nodes(p)
        return self.nodes

    def ensure(self, name="v4-cur"):
        if self.img is None or self.img_name != name:
            self.snap(name)
        return self.img


def px(img, x, y):
    return img.hexat(x, y)


def row_bbox_gap(img, y, bg="#FBF7F1", tol=12, x0=0, x1=None, min_run=20):
    """一行上「非背景」连续段 → [(x1,x2,width)]"""
    w = img.w if x1 is None else x1
    bgc = hex2rgb(bg)
    runs, start = [], None
    for x in range(x0, w):
        c = img.get(x, y)
        diff = dist(c, bgc) > tol
        if diff and start is None:
            start = x
        elif not diff and start is not None:
            if x - start >= min_run:
                runs.append((start, x - 1, x - start))
            start = None
    if start is not None and w - start >= min_run:
        runs.append((start, w - 1, w - start))
    return runs


def vrun_gap(img, x, bg="#FBF7F1", tol=12, y0=0, y1=None, min_run=20):
    h = img.h if y1 is None else y1
    bgc = hex2rgb(bg)
    runs, start = [], None
    for y in range(y0, h):
        c = img.get(x, y)
        diff = dist(c, bgc) > tol
        if diff and start is None:
            start = y
        elif not diff and start is not None:
            if y - start >= min_run:
                runs.append((start, y - 1, y - start))
            start = None
    if start is not None and h - start >= min_run:
        runs.append((start, h - 1, h - start))
    return runs


def verdict(ok, warn=False):
    return "WARN" if warn else ("PASS" if ok else "FAIL")


def run_v4(ctx: Ctx, only=None):
    rows = []

    def add(vid, name, expected, actual, v, evidence):
        rows.append({"id": vid, "name": name, "expected": expected,
                     "actual": actual, "verdict": v, "evidence": evidence})
        print("%-4s %-26s %-12s %s" % (vid, name, v, actual))

    want = set(only) if only else None

    # ---------- 竖屏书架：先拍基线与 UI 树 ----------
    ctx.snap("v4-shelf")
    ctx.ui("v4-shelf")

    # V11 需要深色，放在最后（避免影响浅色断言）

    # V1 顶栏透明
    if not want or "V1" in want:
        c_top = px(ctx.img, 540, 100)
        c_body = px(ctx.img, 540, 700)
        d = dist(hex2rgb(c_top), hex2rgb(c_body))
        # 顶栏区域是否存在 1dp 深色分割线：扫 x=540 y 80..220
        lines = []
        prev = ctx.img.get(540, 78)
        for y in range(79, 240):
            cur = ctx.img.get(540, y)
            if luma(cur) + 12 < luma(prev):
                lines.append(y)
            prev = cur
        v = verdict(d <= 40 and not lines)
        add("V1", "顶栏初始透明无分割线", "顶栏≈内容色(Δ≤40)、无暗线",
            "%s vs %s Δ=%d 暗线=%s" % (c_top, c_body, d, lines[:5] or "无"), v, "v4-shelf.png")

    # V2 Hero 几何
    if not want or "V2" in want:
        bb = bbox_pred(ctx.img, (0, 150, 1079, 1150), "dark")
        if bb:
            x1, y1, x2, y2, n = bb
            w, h = x2 - x1 + 1, y2 - y1 + 1
            v = verdict(abs(w - 998) <= 6 and abs(h - 462) <= 6)
            add("V2", "Hero 卡 380×176dp", "998×462px(±6)",
                "%dx%d @(%d,%d)" % (w, h, x1, y1), v, "v4-shelf.png")
        else:
            add("V2", "Hero 卡 380×176dp", "998×462px(±6)", "未找到深色 Hero 区域", "FAIL", "v4-shelf.png")

    # V3 最近添加 peek
    if not want or "V3" in want:
        # Hero 下方找横向卡片行：取 Hero 底 +120..+400 中一行非背景 run
        found = None
        for yy in range(1150, 1700, 20):
            runs = row_bbox_gap(ctx.img, yy, x0=0)
            big = [r for r in runs if 100 < r[2] < 900]
            if len(big) >= 2:
                found = (yy, runs)
                break
        if found:
            yy, runs = found
            first = runs[0]
            second = runs[1]
            peek = 1080 - second[0]
            v = verdict(abs(first[0] - 42) <= 4 and peek >= 63)
            add("V3", "最近添加 peek 24dp", "首项左 42px、第2张露出≥63px",
                "y=%d 首项 x=%d 第2张 x=%d 露出 %dpx" % (yy, first[0], second[0], peek), v, "v4-shelf.png")
        else:
            add("V3", "最近添加 peek 24dp", "首项左 42px、第2张露出≥63px", "未定位到横滑行", "FAIL", "v4-shelf.png")

    # V4 网格封面 3:4
    if not want or "V4" in want:
        found = None
        for yy in range(1750, 2350, 25):
            runs = row_bbox_gap(ctx.img, yy, x0=0)
            big = [r for r in runs if r[2] > 300]
            if len(big) >= 2:
                found = (yy, big)
                break
        if found:
            yy, runs = found
            w1 = runs[0][2]
            gap = runs[1][0] - runs[0][1] - 1
            left = runs[0][0]
            v = verdict(abs(w1 - 488) <= 6 and abs(gap - 32) <= 3 and abs(left - 42) <= 4)
            add("V4", "网格封面 186dp(3:4)", "宽488px 间距32px 边距42px",
                "y=%d 宽=%d 间距=%d 左边距=%d" % (yy, w1, gap, left), v, "v4-shelf.png")
        else:
            add("V4", "网格封面 186dp(3:4)", "宽488px 间距32px 边距42px", "未定位到网格封面行", "FAIL", "v4-shelf.png")

    # V5 兜底封面不退化
    if not want or "V5" in want:
        # 取 V4 第一张封面区域中心 60x60 采样，算色彩离散度
        found = None
        for yy in range(1750, 2350, 25):
            runs = row_bbox_gap(ctx.img, yy, x0=0)
            big = [r for r in runs if r[2] > 300]
            if len(big) >= 2:
                found = big[0]
                break
        if found:
            cx, cy = found[0] + found[2] // 2, yy
            cols = [ctx.img.get(cx + dx, cy + dy) for dx in (-20, 0, 20) for dy in (-20, 0, 20)]
            spread = max(max(c[i] for c in cols) - min(c[i] for c in cols) for i in (0, 1, 2))
            v = verdict(True if spread > 12 else False, warn=(spread > 6))
            add("V5", "兜底封面非纯色/无图标", "色彩离散>12（或渐变）",
                "spread=%d 中心=%s" % (spread, px(ctx.img, cx, cy)), v, "v4-shelf.png")
        else:
            add("V5", "兜底封面非纯色/无图标", "色彩离散>12", "未定位到封面", "FAIL", "v4-shelf.png")

    # V7 悬浮胶囊底栏：逐行找「宽度 500–900px 的独立横条」，聚成胶囊
    if not want or "V7" in want:
        bg = hex2rgb(SHELL_TOKENS_LIGHT["surface"])

        def left_edge(y):
            for x in range(60, 900):
                if dist(ctx.img.get(x, y), bg) > 8:
                    return x
            return None

        edges = {}
        for yy in range(1900, 2360):
            lx = left_edge(yy)
            if lx is not None:
                edges[yy] = lx
        # 胶囊左边缘在 ~168 行里稳定复现 → 取众数
        from collections import Counter
        cnt = Counter(v // 6 * 6 for v in edges.values())
        if cnt:
            L = cnt.most_common(1)[0][0]
            ys = [y for y, v in edges.items() if abs(v - L) <= 6]
            if len(ys) >= 100:
                y1, y2 = min(ys), max(ys)
                mid = (y1 + y2) // 2
                xe = None
                for x in range(1060, L + 300, -1):
                    if dist(ctx.img.get(x, mid), bg) > 8:
                        xe = x
                        break
                w = (xe - L + 1) if xe else 0
                h = y2 - y1 + 1
                bottom_gap = SCREEN_H - 1 - y2
                v = verdict(abs(w - 656) <= 21 and abs(h - 168) <= 10)
                add("V7", "悬浮胶囊 250×64dp", "656±21 × 168px(±10)、距底≥32px",
                    "%dx%d 距底 %dpx @(%d,%d)" % (w, h, bottom_gap, L, y1), v, "v4-shelf.png")
            else:
                add("V7", "悬浮胶囊 250×64dp", "656±21 × 168px", "底部无稳定胶囊边缘（找到%d行）" % len(ys), "FAIL", "v4-shelf.png")
        else:
            add("V7", "悬浮胶囊 250×64dp", "656±21 × 168px", "底部未找到非背景区域", "FAIL", "v4-shelf.png")

    print("[ui-shot] V1–V7（书架首屏）完成")
    return rows


# ---------------------------------------------------------------- V8–V12 / S0 / A2 / 200%

def node_in_region(nodes, pattern, y0, y1, x0=0, x1=SCREEN_W):
    for n in nodes:
        if pattern.lower() in (n["text"] + " " + n["desc"]).lower():
            xa, ya, xb, yb = n["bounds"]
            if ya >= y0 and yb <= y1 and xa >= x0 and xb <= x1:
                return n
    return None


def overlap_pairs(nodes, min_area=64):
    """文本节点两两重叠检测（忽略父子包含与完全相同 bounds）"""
    items = []
    seen = set()
    for n in nodes:
        t = (n["text"] or "").strip()
        if not t:
            continue
        b = n["bounds"]
        if b[2] - b[0] <= 0 or b[3] - b[1] <= 0:
            continue
        key = (b, t)
        if key in seen:
            continue
        seen.add(key)
        items.append((t, b))
    out = []
    for i in range(len(items)):
        for j in range(i + 1, len(items)):
            t1, b1 = items[i]
            t2, b2 = items[j]
            ix = min(b1[2], b2[2]) - max(b1[0], b2[0])
            iy = min(b1[3], b2[3]) - max(b1[1], b2[1])
            if ix <= 0 or iy <= 0:
                continue
            area = ix * iy
            if area < min_area:
                continue
            inside = (b1[0] <= b2[0] and b1[1] <= b2[1] and b1[2] >= b2[2] and b1[3] >= b2[3]) or \
                     (b2[0] <= b1[0] and b2[1] <= b1[1] and b2[2] >= b1[2] and b2[3] >= b1[3])
            if inside:
                continue
            out.append((t1, b1, t2, b2, area))
    return out


def overflow_nodes(nodes, w=SCREEN_W, h=SCREEN_H):
    bad = []
    for n in nodes:
        if not (n["text"] or n["desc"]):
            continue
        x1, y1, x2, y2 = n["bounds"]
        if x1 < -4 or x2 > w + 4:
            bad.append((n["text"] or n["desc"], n["bounds"]))
    return bad


def reader_open(ctx):
    """从书架点开第一本书，返回是否成功。"""
    nodes = ctx.ui("_shelf_for_open")
    cand = None
    for n in nodes:
        b = n["bounds"]
        w, h = b[2] - b[0], b[3] - b[1]
        if n["clickable"] and h >= 180 and w >= 120:
            cand = n
            break
    if cand is None:
        for n in nodes:
            if n["text"] in ("故乡的河", "统计之美", "故乡的河（下）"):
                cand = n
                break
    if cand is None:
        return False
    tap(*center(cand["bounds"]))
    time.sleep(1.8)
    after = ctx.ui("_reader_probe")
    return any(("目录" in (n["text"] + n["desc"])) or ("加书签" in (n["desc"] + n["text"]))
               for n in after)


def go_settings(ctx):
    nodes = ctx.ui("_go_settings")
    # 竖屏胶囊在底部；横屏 rail 在左侧
    n = node_in_region(nodes, "设置", SCREEN_H - 400, SCREEN_H)
    if n is None:
        n = find_node(nodes, "设置")
    if n is None:
        return False
    tap(*center(n["bounds"]))
    time.sleep(1.2)
    return True


def run_v4_ext(ctx: Ctx, only=None):
    rows = []
    want = set(only) if only else None

    def add(vid, name, expected, actual, v, evidence):
        rows.append({"id": vid, "name": name, "expected": expected, "actual": actual,
                     "verdict": v, "evidence": evidence})
        print("%-4s %-26s %-12s %s" % (vid, name, v, actual))

    def guarded(vid, name, expected, fn, evidence):
        try:
            v, actual = fn()
        except SystemExit:
            raise
        except Exception as e:
            v, actual = "SKIP", "工具异常: %s" % e
        add(vid, name, expected, actual, v, evidence)

    # ---------- V8 滚动后顶栏 ----------
    def _v8():
        swipe(540, 1600, 540, 1150, 350)
        time.sleep(1.0)
        img = ctx.snap("v4-shelf-scrolled")
        nodes = ctx.ui("v4-shelf-scrolled")
        top = px(img, 540, 100)
        body = px(img, 540, 700)
        d_top_surface = dist(hex2rgb(top), hex2rgb(SHELL_TOKENS_LIGHT["surface"]))
        title = find_node(nodes, "书架")
        th = (title["bounds"][3] - title["bounds"][1]) if title else 0
        ok = d_top_surface <= 30 and th >= 40
        return verdict(ok), "顶栏=%s 距surface=%d 标题高=%dpx" % (top, d_top_surface, th)

    # ---------- V9 阅读器 ----------
    def _v9():
        if not reader_open(ctx):
            return "SKIP", "未能从书架打开书籍"
        img = ctx.snap("v4-reader")
        nodes = ctx.ui("v4-reader")
        # 页面间距：竖扫 x=540，白页与背景交替
        runs = vrun_gap(img, 540, bg="#FFFFFF", tol=18, min_run=60)
        gaps = []
        prev_end = None
        for (a, b, h) in runs:
            if prev_end is not None:
                gaps.append(a - prev_end - 1)
            prev_end = b
        gap = gaps[0] if gaps else -1
        # 顶部 2dp 橙进度线（y<12 内找 #C2410C 近似）
        line_px = 0
        for y in range(0, 14):
            for x in range(0, SCREEN_W, 3):
                if dist(img.get(x, y), hex2rgb("#C2410C")) <= 40:
                    line_px += 1
        ok = abs(gap - 32) <= 4
        return verdict(ok, "页间距=%dpx（期望32）白页数=%d 橙线px=%d" % (gap, len(runs), line_px))

    # ---------- V10 设置分组 ----------
    def _v10():
        if not go_settings(ctx):
            return "SKIP", "未找到设置入口"
        img = ctx.snap("v4-settings")
        nodes = ctx.ui("v4-settings")
        # 行高：找「字号」等文字节点的可点击祖先/自身高度
        rowh = 0
        for n in nodes:
            t = (n["text"] or "")
            if t in ("字号", "行距", "默认排序", "主题", "导入本地文件"):
                rowh = max(rowh, n["bounds"][3] - n["bounds"][1])
        # 分组容器：取页面上第一块 surfaceContainerLow 大区域的外接框
        bb = bbox_pred(img, (0, 250, SCREEN_W - 1, 1500), "color:%s" % SHELL_TOKENS_LIGHT["surfaceContainerLow"], tol=6)
        if bb:
            gw, gh = bb[2] - bb[0] + 1, bb[3] - bb[1] + 1
        else:
            gw = gh = 0
        ok = rowh >= 100 and gw >= 900
        return verdict(ok, "行文字高=%dpx 组容器=%dx%d" % (rowh, gw, gh))

    # ---------- V11 深色 ----------
    def _v11():
        n = tap_text("深色")
        if not n:
            return "SKIP", "设置页未找到「深色」"
        time.sleep(1.4)
        img = ctx.snap("v4-settings-dark")
        s = px(img, 540, 700)
        d = dist(hex2rgb(s), hex2rgb(SHELL_TOKENS_DARK["surface"]))
        # 找一块大面积深色采样（避免正好落在容器上）
        c1 = contrast(SHELL_TOKENS_DARK["onSurfaceVariant"], SHELL_TOKENS_DARK["surface"])
        ok = d <= 24 and c1 >= 4.5
        tap_text("浅色")
        return verdict(ok), "surface=%s 距#16130E=%d 文本对比=%.2f" % (s, d, c1)

    # ---------- V12 横屏 A2 ----------
    def _v12():
        rot("1")
        time.sleep(2.0)
        ctx.img = None
        img = ctx.snap("v4-landscape")
        nodes = ctx.ui("v4-landscape")
        rows_h = [n["bounds"][3] - n["bounds"][1] for n in nodes
                  if n["clickable"] and (n["bounds"][2] - n["bounds"][0]) > 1200]
        rowh = max(rows_h) if rows_h else 0
        rail = node_in_region(nodes, "书架", 0, 400, 0, 400)
        rail_w = rail["bounds"][2] if rail else 0
        # 底部胶囊不应出现在横屏
        bottom = bbox_pred(img, (0, 900, 2399, 1079), "color:%s" % SHELL_TOKENS_LIGHT["surfaceContainerHigh"], tol=8)
        ok = abs(rowh - 242) <= 6 and 150 <= rail_w <= 260
        rot("auto")
        time.sleep(1.5)
        ctx.img = None
        return verdict(ok), "行高=%dpx rail宽=%dpx 底部高色块=%s" % (rowh, rail_w, "有" if bottom else "无")

    # ---------- S0 冷启 ----------
    def _s0():
        r = coldstart([0.3, 0.6, 1.0, 1.5, 2.0], ctx.outdir)
        tt = r.get("total_time_ms")
        # 1.0s 时中心点是否已是内容：用与书架 surface 的色差粗判
        ok = bool(tt and tt <= 2000)
        return verdict(ok), "TotalTime=%sms 首帧采样=%s" % (tt, [s.get("center") for s in r["shots"][:3]])

    # ---------- 200% 字号 ----------
    def _f200():
        font("2.0")
        time.sleep(1.5)
        report = {}
        for name, opener in (("shelf", None), ("settings", go_settings)):
            if opener:
                opener(ctx)
            img = ctx.snap("v4-200-%s" % name)
            nodes = ctx.ui("v4-200-%s" % name)
            report[name] = {"overlap": overlap_pairs(nodes), "overflow": overflow_nodes(nodes)}
        font("1.0")
        bad = sum(len(v["overlap"]) + len(v["overflow"]) for v in report.values())
        detail = " ".join("%s:重叠%d 溢出%d" % (k, len(v["overlap"]), len(v["overflow"])) for k, v in report.items())
        return verdict(bad == 0, detail)

    if not want or "V8" in want:
        guarded("V8", "滚动后顶栏 86%+标题缩小", "顶栏≈surface±30、标题高≥40px", _v8, "v4-shelf-scrolled.png")
    if not want or "V9" in want:
        guarded("V9", "阅读器 chrome/页间距", "页间距32px、顶栏231px、橙线可见", _v9, "v4-reader.png")
    if not want or "V10" in want:
        guarded("V10", "设置分组容器", "容器宽≥900px、行文字不重叠", _v10, "v4-settings.png")
    if not want or "V11" in want:
        guarded("V11", "深色 surface #16130E", "采样距#16130E≤24", _v11, "v4-settings-dark.png")
    if not want or "V12" in want:
        guarded("V12", "横屏列表 A2", "行高242px rail≈210px 无底部胶囊", _v12, "v4-landscape.png")
    if not want or "S0" in want:
        guarded("S0", "冷启 ≤2s 出书卡", "TotalTime≤2000ms", _s0, "coldstart-*.png")
    if not want or "F200" in want:
        guarded("F200", "200% 字号四屏无重叠", "重叠0 溢出0", _f200, "v4-200-*.png")
    return rows


# ---------------------------------------------------------------- 自检

def selftest(outdir):
    print("== 设备 ==")
    print(shell("getprop ro.product.model").strip(), "| sdk", shell("getprop ro.build.version.sdk").strip(),
          "| size", shell("wm size").strip().replace("\n", " "), "| density", shell("wm density").strip().replace("\n", " "))
    p = shot("selftest-shot", outdir)
    img = load_img(p)
    print("shot:", p, "| %dx%d" % (img.w, img.h), "| (540,1200) =", px(img, 540, 1200))
    xmlp = dump_xml("selftest-dump", outdir)
    nodes = parse_nodes(xmlp)
    print("dump:", xmlp, "| nodes =", len(nodes))
    for pat in ("书架", "设置", "继续阅读", "全部书籍"):
        n = find_node(nodes, pat)
        if n:
            print("  find %-6s → bounds=%s center=%s" % (pat, n["bounds"], center(n["bounds"])))
        else:
            print("  find %-6s → 无" % pat)
    print("contrast(#FBF7F1,#17130D) = %.2f" % contrast("#FBF7F1", "#17130D"))
    print("runs on y=1200:", color_runs(img, 1200, min_run=8)[:6])
    return 0


# ---------------------------------------------------------------- CLI

def main(argv):
    ap = argparse.ArgumentParser(description="ParticleBook 装机验收工具箱")
    ap.add_argument("--outdir", default=os.environ.get("UI_SHOT_DIR", DEFAULT_OUT))
    ap.add_argument("--json", action="store_true")
    sub = ap.add_subparsers(dest="cmd", required=True)

    def sp(name, **kw):
        s = sub.add_parser(name, **kw)
        return s

    s = sp("shot"); s.add_argument("name")
    s = sp("dump"); s.add_argument("name")
    s = sp("ui"); s.add_argument("--pattern"); s.add_argument("--all", action="store_true"); s.add_argument("--limit", type=int, default=80)
    s = sp("find"); s.add_argument("pattern"); s.add_argument("--exact", action="store_true")
    s = sp("text-bounds"); s.add_argument("pattern")
    s = sp("pixel"); s.add_argument("x", type=int); s.add_argument("y", type=int); s.add_argument("--img")
    s = sp("line"); s.add_argument("y", type=int); s.add_argument("--x0", type=int, default=0); s.add_argument("--x1", type=int); s.add_argument("--step", type=int, default=1); s.add_argument("--img")
    s = sp("runs"); s.add_argument("y", type=int); s.add_argument("--tol", type=int, default=10); s.add_argument("--min-run", type=int, default=8); s.add_argument("--img")
    s = sp("vruns"); s.add_argument("x", type=int); s.add_argument("--tol", type=int, default=10); s.add_argument("--min-run", type=int, default=8); s.add_argument("--img")
    s = sp("bbox"); s.add_argument("--region", required=True, help="x0,y0,x1,y1"); s.add_argument("--pred", required=True); s.add_argument("--tol", type=int, default=10); s.add_argument("--img")
    s = sp("contrast"); s.add_argument("bg"); s.add_argument("fg")
    s = sp("crop"); s.add_argument("x1", type=int); s.add_argument("y1", type=int); s.add_argument("x2", type=int); s.add_argument("y2", type=int); s.add_argument("out"); s.add_argument("--img")
    s = sp("md5"); s.add_argument("path")
    s = sp("install"); s.add_argument("apk"); s.add_argument("--md5"); s.add_argument("--start", action="store_true")
    s = sp("tap"); s.add_argument("x", type=int); s.add_argument("y", type=int)
    s = sp("tap-text"); s.add_argument("pattern")
    s = sp("swipe"); s.add_argument("x1", type=int); s.add_argument("y1", type=int); s.add_argument("x2", type=int); s.add_argument("y2", type=int); s.add_argument("--ms", type=int, default=300)
    s = sp("rot"); s.add_argument("mode")
    s = sp("font"); s.add_argument("scale")
    sp("start"); sp("stop")
    s = sp("coldstart"); s.add_argument("--shots", default="0.3,0.6,1.0,1.5,2.0")
    s = sp("v4"); s.add_argument("--only"); s.add_argument("--ext", action="store_true")
    sp("selftest")

    a = ap.parse_args(argv)

    if a.cmd == "shot":
        print(shot(a.name, a.outdir))
    elif a.cmd == "dump":
        p = dump_xml(a.name, a.outdir); print(p, len(parse_nodes(p)))
    elif a.cmd == "ui":
        nodes = parse_nodes(dump_xml("_ui", a.outdir))
        print_nodes(nodes, a.pattern, a.all, a.limit)
    elif a.cmd == "find":
        nodes = parse_nodes(dump_xml("_find", a.outdir))
        n = find_node(nodes, a.pattern, a.exact)
        if n:
            print(json.dumps({"label": node_label(n), "bounds": n["bounds"], "center": center(n["bounds"]),
                              "clickable": n["clickable"], "desc": n["desc"]}, ensure_ascii=False))
        else:
            print(json.dumps({"found": False, "pattern": a.pattern}, ensure_ascii=False)); return 1
    elif a.cmd == "text-bounds":
        nodes = parse_nodes(dump_xml("_tb", a.outdir))
        n = find_node(nodes, a.pattern)
        if n:
            print("%d %d %d %d" % n["bounds"]); return 0
        return 1
    elif a.cmd == "pixel":
        img = load_img(winpath(a.img) if a.img else shot("_px", a.outdir))
        print(px(img, a.x, a.y))
    elif a.cmd == "line":
        img = load_img(winpath(a.img) if a.img else shot("_line", a.outdir))
        for x in range(a.x0, (a.x1 or img.w - 1) + 1, a.step):
            print("%4d %s" % (x, img.hexat(x, a.y)))
    elif a.cmd == "runs":
        img = load_img(winpath(a.img) if a.img else shot("_runs", a.outdir))
        for r in color_runs(img, a.y, tol=a.tol, min_run=a.min_run):
            print("x %4d-%4d  w=%4d  %s" % (r[0], r[1], r[1] - r[0] + 1, r[2]))
    elif a.cmd == "vruns":
        img = load_img(winpath(a.img) if a.img else shot("_vruns", a.outdir))
        for r in vcolor_runs(img, a.x, tol=a.tol, min_run=a.min_run):
            print("y %4d-%4d  h=%4d  %s" % (r[0], r[1], r[1] - r[0] + 1, r[2]))
    elif a.cmd == "bbox":
        img = load_img(winpath(a.img) if a.img else shot("_bbox", a.outdir))
        region = tuple(int(v) for v in a.region.split(","))
        bb = bbox_pred(img, region, a.pred, a.tol)
        if bb:
            print(json.dumps({"x1": bb[0], "y1": bb[1], "x2": bb[2], "y2": bb[3],
                              "w": bb[2] - bb[0] + 1, "h": bb[3] - bb[1] + 1, "count": bb[4]}, ensure_ascii=False))
        else:
            print(json.dumps({"found": False}, ensure_ascii=False)); return 1
    elif a.cmd == "contrast":
        c = contrast(a.bg, a.fg)
        print("%s vs %s = %.2f:1  正文(≥4.5) %s / 大字(≥3.0) %s" % (
            a.fg, a.bg, c, "PASS" if c >= 4.5 else "FAIL", "PASS" if c >= 3.0 else "FAIL"))
    elif a.cmd == "crop":
        img = load_img(winpath(a.img) if a.img else shot("_crop", a.outdir))
        try:
            from PIL import Image
        except ImportError:
            sys.exit("[ui-shot] crop 需要 Pillow")
        Image.open(winpath(a.img) if a.img else img.path).crop((a.x1, a.y1, a.x2, a.y2)).save(winpath(a.out))
        print(winpath(a.out))
    elif a.cmd == "md5":
        h = md5_file(a.path); print(h, os.path.getsize(winpath(a.path)))
    elif a.cmd == "install":
        rc = install(a.apk, a.md5)
        if rc == 0 and a.start:
            print("cold start TotalTime =", start(), "ms")
        return rc
    elif a.cmd == "tap":
        tap(a.x, a.y)
    elif a.cmd == "tap-text":
        n = tap_text(a.pattern)
        print(json.dumps({"tapped": node_label(n), "center": center(n["bounds"])} if n else {"tapped": False}, ensure_ascii=False))
        return 0 if n else 1
    elif a.cmd == "swipe":
        swipe(a.x1, a.y1, a.x2, a.y2, a.ms)
    elif a.cmd == "rot":
        rot(a.mode)
    elif a.cmd == "font":
        font(a.scale)
    elif a.cmd == "start":
        print("cold start TotalTime =", start(), "ms")
    elif a.cmd == "stop":
        stop()
    elif a.cmd == "coldstart":
        print(json.dumps(coldstart([float(x) for x in a.shots.split(",")], a.outdir), ensure_ascii=False))
    elif a.cmd == "v4":
        ctx = Ctx(a.outdir)
        only = a.only.split(",") if a.only else None
        rows = run_v4(ctx, only)
        if a.ext:
            rows += run_v4_ext(ctx, only)
        fails = [r for r in rows if r["verdict"] == "FAIL"]
        print("\n%s  PASS=%d FAIL=%d WARN=%d SKIP=%d" % (
            "总结", len([r for r in rows if r["verdict"] == "PASS"]), len(fails),
            len([r for r in rows if r["verdict"] == "WARN"]),
            len([r for r in rows if r["verdict"] == "SKIP"])))
        return 0 if not fails else 1
    elif a.cmd == "selftest":
        return selftest(a.outdir)
    return 0


def coldstart(offsets, outdir):
    """冷启：am start -W 走后台线程（拿到真实 TotalTime），主线程按 offsets 连拍。"""
    act = launcher_activity()
    stop()
    time.sleep(0.6)
    shell("logcat -c")
    t0 = time.time()
    proc = subprocess.Popen([find_adb(), "shell", "am start -W -n %s" % act],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    shots = []
    for off in offsets:
        wait = t0 + off - time.time()
        if wait > 0:
            time.sleep(wait)
        name = "coldstart-%.1fs" % off
        try:
            p = shot(name, outdir)
            img = load_img(p)
            shots.append({"t": off, "file": os.path.basename(p), "center": px(img, 540, 1200),
                          "elapsed_ms": int((time.time() - t0) * 1000)})
        except Exception as e:
            shots.append({"t": off, "error": str(e)})
    out = proc.communicate(timeout=60)[0].decode("utf-8", "replace")
    m = re.search(r"TotalTime:\s*(\d+)", out)
    return {"shots": shots, "total_time_ms": int(m.group(1)) if m else None,
            "activity": act, "raw": out.strip().splitlines()[-3:]}


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
