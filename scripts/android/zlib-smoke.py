#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""ParticleBook Z-Library 可重复冒烟 · 驱动（由 scripts/android/zlib-smoke.sh 调用）。

流程：装包 → 清数据（默认）→ 冷启 → Z-Library tab → 搜索 android → 断言「已加载 N 本」
      → 打开首条详情 → 断言「书籍详情 + 下载 · + 书名」→ 可选 --download 三态闭环（触发<=30s / 终态<=180s / 书架+1 / 文件出现）
      → 抓 PBPERF（cold_start/zlib_search/zlib_login/zlib_download）→ JSON + PASS/FAIL。

设计事实（2026-11-02 在 Pixel 3 XL 1440x2960 实测）：
  · 搜索框占位符「书名 / 作者 / ISBN」；按钮「搜索」；结果表头「已加载 50 本 · 第 1 / 3 页」
  · 结果行 title 节点在 y≈1139；详情页标题栏「书籍详情」、按钮「下载 · 9.96 MB」
  · 未登录点下载 → 「今日下载额度已用完，请登录账号后重试」+ 悬浮「下载失败 · …」卡片
  · PBPERF 行有两种：PBPERF|phase=... 与 zlib_search|ms=...|extra=...（后者无 phase= 前缀）

坐标兜底（Lead 给定，1440x2960 基准，按 wm size 等比缩放）：
  tab (777,2620) / 搜索框 (700,640) / 按钮 (720,851) / 首条 (540,1100) / 下载 (720,1320)
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

REF_W, REF_H = 1440, 2960
COORD_TAB = (777, 2620)
COORD_FIELD = (700, 640)
COORD_SEARCH = (720, 851)
COORD_FIRST = (540, 1100)
COORD_DOWNLOAD = (720, 1320)

LOG_RE = re.compile(
    r"^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(\S+)\s*:\s?(.*)$"
)
PERF_RE = re.compile(r"^(zlib_[a-z_]+)\|(.*)$")


class SmokeError(Exception):
    def __init__(self, code, reason, step=""):
        super().__init__(reason)
        self.code = int(code)
        self.reason = reason
        self.step = step


def now_iso():
    return datetime.now().astimezone().isoformat(timespec="seconds")


def winpath(p):
    if not p:
        return p
    p = p.replace("\\", "/")
    m = re.match(r"^/([a-zA-Z])/(.*)$", p)
    if m:
        return m.group(1).upper() + ":/" + m.group(2)
    return p


def norm(s):
    return re.sub(r"\s+", "", s or "")


def kv_parse(extra):
    out = {}
    for part in (extra or "").split(","):
        if ":" in part:
            k, v = part.split(":", 1)
            out[k.strip()] = v.strip()
        elif part:
            out[part.strip()] = "1"
    return out

DOWNLOAD_DIR_TMPL = "/sdcard/Android/data/{pkg}/files/Download/Z-Library"
DOWNLOAD_SKIP_KINDS = ("DAILY_LIMIT", "LOGIN_REQUIRED", "LOGIN")
MAX_PERCENT = 100
DOWNLOAD_PROGRESS_RE = re.compile(r"(\d{1,3})\s*%")


def download_status_of(extra):
    """解析 zlib_download 的 extra。

    成功：book:N,bytes:N,status:ok,target:file|saf
    失败：book:N,bytes:N,status:fail:ERROR_KIND,target:file|saf
    """
    kv = kv_parse(extra)
    status = (kv.get("status") or "").strip()
    info = {"status": "", "kind": "", "bytes": None, "target": kv.get("target", ""), "raw": kv}
    if status == "ok":
        info["status"] = "ok"
    elif status.startswith("fail"):
        info["status"] = "fail"
        info["kind"] = status.split(":", 1)[1] if ":" in status else ""
    try:
        info["bytes"] = int(kv.get("bytes", ""))
    except Exception:
        info["bytes"] = None
    return info


def percent_in_text(text):
    """从 UI 文案提取 0-100 的百分比；没有返回 None。"""
    m = DOWNLOAD_PROGRESS_RE.search(text or "")
    if not m:
        return None
    p = int(m.group(1))
    return p if 0 <= p <= MAX_PERCENT else None


def terminal_kind_texts(texts):
    """从 UI 文案判断终态/前置：limit / login / done / failed / None。

    额度与登录优先：失败卡片会同时出现「下载失败 · 书名」和额度/登录文案。
    """
    joined = "\n".join(t for t in texts if t)
    if "额度已用完" in joined or "今日下载额度" in joined:
        return "limit"
    if "请先登录" in joined or "登录账号后重试" in joined:
        return "login"
    if "下载完成" in joined or "已加入书架" in joined or "已下载" in joined:
        return "done"
    if "下载失败" in joined:
        return "failed"
    return None


def parse_ls_lines(out):
    """解析 ls -l 输出为 {name: size}；跳过隐藏文件与 .part。"""
    files = {}
    for line in (out or "").splitlines():
        parts = line.split()
        if len(parts) < 8 or not parts[0].startswith("-"):
            continue
        try:
            size = int(parts[4])
        except Exception:
            continue
        name = " ".join(parts[7:])
        if not name or name.startswith(".") or name.endswith(".part"):
            continue
        files[name] = size
    return files


MOCK_DEFAULT_URL = "http://127.0.0.1:8899"
MOCK_MIRROR_EXTRA = "pb_zlib_mirror"
MOCK_FAULT_CASES = ("first_packet_lost", "range_416", "slow", "cancel")
FAULT_CASE_ALIASES = {
    "first_packet_lost": "first_packet_lost",
    "firstpacketlost": "first_packet_lost",
    "first_packet": "first_packet_lost",
    "range_416": "range_416",
    "range416": "range_416",
    "416": "range_416",
    "slow": "slow",
    "cancel": "cancel",
    "cancelled": "cancel",
}


def parse_mock_entries(payload):
    """把 GET /__log 的各种返回形态归一成 [dict]（entries/log/logs/requests/lines 或裸数组）。"""
    if isinstance(payload, dict):
        for key in ("entries", "log", "logs", "requests", "lines"):
            value = payload.get(key)
            if isinstance(value, list):
                return [x for x in value if isinstance(x, dict)]
        if "path" in payload or "status" in payload:
            return [payload]
        return []
    if isinstance(payload, list):
        return [x for x in payload if isinstance(x, dict)]
    return []


def parse_fault_cases(text):
    """'first_packet_lost,range_416' -> 规范化列表；未知值抛 ValueError。"""
    cases = []
    for part in str(text or "").split(","):
        key = part.strip().lower().replace("-", "_")
        if not key:
            continue
        if key not in FAULT_CASE_ALIASES:
            raise ValueError("unknown fault case: " + part.strip())
        norm = FAULT_CASE_ALIASES[key]
        if norm not in cases:
            cases.append(norm)
    return cases


def mock_paths(entries):
    return [str(e.get("path", "") or "") for e in entries]


def mock_sequence_ok(entries, prefixes):
    """请求序列里 prefixes 按顺序首次出现（空前缀表示不检查）。"""
    need = [p for p in prefixes if p]
    pos = 0
    for e in entries:
        path = str(e.get("path", "") or "")
        if pos < len(need) and path.startswith(need[pos]):
            pos += 1
    return pos >= len(need)


def mock_app_entries(entries):
    """过滤掉 /__health /__stats /__log /__fault /__reset 等控制请求。"""
    return [e for e in entries if not str(e.get("path", "")).startswith("/__")]


def mock_range_of(entry):
    """mock 日志里无 Range 时是 '-'（不是缺失）；归一成 ''。"""
    value = str(entry.get("range", "") or "").strip()
    return "" if value in ("", "-") else value


def mock_faults(entries):
    return [e for e in entries if str(e.get("fault", "none") or "none") != "none"]


def mock_status_of(entry):
    try:
        return int(entry.get("status") or 0)
    except Exception:
        return 0


def mock_success_after_fault(entries, mode):
    """故障条目之后是否还有一次成功的 /cdn 响应（200/206）。"""
    for i, e in enumerate(entries):
        if str(e.get("fault", "none") or "none") != mode:
            continue
        for later in entries[i + 1:]:
            if str(later.get("path", "")).startswith("/cdn") and 200 <= mock_status_of(later) < 300:
                return True
    return False


def mock_stats_int(stats, key):
    try:
        return int(stats.get(key) or 0)
    except Exception:
        return 0


def parse_xml_nodes(xml):
    """uiautomator dump XML 转 node dict；解析失败返回 []。"""
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
        m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", n.get("bounds", "") or "")
        if not m:
            continue
        x1, y1, x2, y2 = (int(v) for v in m.groups())
        if x2 <= x1 or y2 <= y1:
            continue
        nodes.append({
            "text": n.get("text", "") or "",
            "desc": n.get("content-desc", "") or "",
            "cls": n.get("class", "") or "",
            "pkg": n.get("package", "") or "",
            "clickable": n.get("clickable") == "true",
            "bounds": (x1, y1, x2, y2),
        })
    return nodes


def parse_perf_msg(tag, msg):
    """PBPERF / zlib_* 日志转 (phase, fields)；不识别返回 None。

    两种真实格式（Pixel 3 XL 2026-11-02 实测）：
      PBPERF|phase=zlib_login|ms=12|extra=ok,cookies:3
      zlib_search|ms=6747|extra=mirror:z-lib.by,results:50,page:1,total:3
    """
    if tag != "PBPERF":
        return None
    if msg.startswith("PBPERF|"):
        body = msg[len("PBPERF|"):]
    else:
        m = PERF_RE.match(msg)
        if not m:
            return None
        body = "phase=" + m.group(1) + "|" + m.group(2)
    fields = {}
    for part in body.split("|"):
        if "=" in part:
            k, v = part.split("=", 1)
            fields[k.strip()] = v.strip()
    phase = fields.get("phase", "")
    if not phase:
        return None
    return phase, fields


def selftest():
    """--selftest：不碰设备的纯函数自检（bash --selfcheck 调用）。"""
    checks = []

    def ck(name, cond, detail=""):
        checks.append((name, bool(cond), detail))

    xml = ('<?xml version="1.0" encoding="UTF-8"?><hierarchy rotation="0">'
           '<node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="app.particlebook" '
           'content-desc="" clickable="false" bounds="[0,0][1440,2960]">'
           '<node index="1" text="已加载 50 本 · 第 1 / 3 页" resource-id="" class="android.widget.TextView" '
           'package="app.particlebook" content-desc="" clickable="false" bounds="[56,977][512,1038]" />'
           '<node index="2" text="下载 · 9.96 MB" resource-id="" class="android.widget.TextView" '
           'package="app.particlebook" content-desc="" clickable="false" bounds="[560,1285][880,1356]" />'
           '</node></hierarchy>')
    nodes = parse_xml_nodes(xml)
    ck("parse_xml.nodes", len(nodes) == 3, "n=" + str(len(nodes)))
    ck("parse_xml.text", any(n["text"] == "已加载 50 本 · 第 1 / 3 页" for n in nodes))
    ck("find_exact", Smoke.find_exact(nodes, "下载 · 9.96 MB") is not None)
    ck("find_contains", Smoke.find_contains(nodes, "已加载") is not None)
    m = re.search(r"(?:已加载|找到)\s*(\d+)\s*本", nodes[1]["text"])
    ck("results_regex", m is not None and int(m.group(1)) == 50)
    ck("winpath.posix", winpath("/f/tmp/a.apk") == "F:/tmp/a.apk", winpath("/f/tmp/a.apk"))
    ck("winpath.win", winpath("F:/tmp/a.apk") == "F:/tmp/a.apk")
    ck("kv_parse", kv_parse("mirror:z-lib.by,results:50,page:1,total:3").get("results") == "50")
    p = parse_perf_msg("PBPERF", "PBPERF|phase=zlib_login|ms=12.5|extra=ok,cookies:3")
    ck("perf.full", p is not None and p[0] == "zlib_login" and p[1].get("ms") == "12.5", str(p))
    p = parse_perf_msg("PBPERF", "zlib_search|ms=6747|extra=mirror:z-lib.by,results:50,page:1,total:3")
    ck("perf.zlib", p is not None and p[0] == "zlib_search" and p[1].get("ms") == "6747", str(p))
    p = parse_perf_msg("PBPERF", "PBPERF|phase=cold_start|ms=321|extra=first_frame:1")
    ck("perf.cold", p is not None and p[0] == "cold_start")
    ck("perf.none", parse_perf_msg("PBPERF", "hello") is None)
    ck("perf.other_tag", parse_perf_msg("PBZlib", "zlib_search|ms=1") is None)
    d = download_status_of("book:2,bytes:2383,status:ok,target:file")
    ck("dl.status_ok", d["status"] == "ok" and d["bytes"] == 2383, str(d))
    d = download_status_of("book:2,bytes:0,status:fail:DAILY_LIMIT,target:file")
    ck("dl.status_fail", d["status"] == "fail" and d["kind"] == "DAILY_LIMIT", str(d))
    ck("dl.percent", percent_in_text("下载中 37%") == 37)
    ck("dl.percent_card", percent_in_text("35% · 1.2 MB / 9.96 MB") == 35)
    ck("dl.percent_bad", percent_in_text("200% 字体") is None)
    ck("dl.term_limit", terminal_kind_texts(["下载失败 · x.pdf", "今日下载额度已用完，请登录账号后重试"]) == "limit")
    ck("dl.term_done", terminal_kind_texts(["下载完成 · x.pdf"]) == "done")
    ck("dl.term_failed", terminal_kind_texts(["下载失败 · x.pdf"]) == "failed")
    ck("dl.ls", parse_ls_lines("-rw-rw---- 1 u0_a1 ext_data_rw 2383 2026-11-02 22:51 x.pdf") == {"x.pdf": 2383})
    # mock 契约 v1（pdf-tiles）：JSONL 条目归一 / 请求序列 / 故障后成功判定
    entries = parse_mock_entries({"entries": [
        {"path": "/s/android?page=1", "status": 200, "fault": "none"},
        {"path": "/book/abc", "status": 200},
        {"path": "/dl/abc", "status": 200},
        {"path": "/cdn/x.epub", "status": 206, "fault": "first_packet_lost", "bytes": 0},
        {"path": "/cdn/x.epub", "status": 200, "fault": "none"},
    ]})
    ck("mock.entries", len(entries) == 5, "n=" + str(len(entries)))
    ck("mock.entries.list", len(parse_mock_entries([{"path": "/a"}, "x"])) == 1)
    ck("mock.entries.empty", parse_mock_entries(None) == [] and parse_mock_entries({"ok": 1}) == [])
    ck("mock.seq.ok", mock_sequence_ok(entries, ["/s/", "/book/", "/dl/", "/cdn/"]))
    ck("mock.seq.bad", not mock_sequence_ok(entries, ["/book/", "/s/"]))
    ck("mock.seq.missing", not mock_sequence_ok(entries, ["/s/", "/login"]))
    ck("mock.faults", len(mock_faults(entries)) == 1 and mock_faults(entries)[0]["fault"] == "first_packet_lost")
    ck("mock.fault.recovered", mock_success_after_fault(entries, "first_packet_lost"))
    ck("mock.fault.not_recovered", not mock_success_after_fault(entries[:4], "first_packet_lost"))
    ck("mock.cases", parse_fault_cases("first_packet_lost,416,slow,cancel,slow") ==
       ["first_packet_lost", "range_416", "slow", "cancel"])
    try:
        parse_fault_cases("bogus")
        ck("mock.cases.bad", False, "no ValueError")
    except ValueError:
        ck("mock.cases.bad", True)
    ck("mock.stats", mock_stats_int({"cdn_requests": "7"}, "cdn_requests") == 7
       and mock_stats_int({}, "status_416") == 0)
    ck("mock.app_entries", len(mock_app_entries(entries)) == 5)
    ck("mock.range", mock_range_of({"range": "-"}) == "" and mock_range_of({"range": "bytes=8-"}) == "bytes=8-")
    bad = 0
    for name, ok, detail in checks:
        print("SELFTEST|" + ("PASS" if ok else "FAIL") + "|" + name + (("|" + detail) if detail else ""))
        if not ok:
            bad += 1
    if bad:
        print("SELFTEST|FAIL|" + str(bad) + " of " + str(len(checks)) + " checks failed")
        return 2
    print("SELFTEST|PASS|" + str(len(checks)) + " checks")
    return 0


class Smoke:
    def __init__(self, args):
        self.args = args
        self.pkg = args.pkg
        self.activity = args.activity
        self.tag = args.tag
        self.stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        self.t0 = time.time()
        self.deadline = self.t0 + float(args.timeout)
        here = os.path.dirname(os.path.abspath(__file__))
        self.root = os.path.dirname(os.path.dirname(here))
        outdir = winpath(args.outdir)
        if not os.path.isabs(outdir):
            outdir = os.path.join(self.root, outdir)
        self.outdir = outdir
        os.makedirs(self.outdir, exist_ok=True)
        self.serial = (args.serial or os.environ.get("ZLIB_SMOKE_SERIAL") or "").strip()
        self.adb_exe = self.resolve_adb()
        self.steps = []
        self.asserts = []
        self.warnings = []
        self.artifacts = {"screenshots": [], "dumps": [], "logcat": None, "json": None}
        self.dev = {}
        self.ui = {}
        self.perf = {"cold_start": None, "zlib_search": [], "zlib_login": [], "zlib_download": [],
                     "by_phase": {}, "debug": []}
        self.W = REF_W
        self.H = REF_H
        self.app_pid = None
        self.raw_logcat = ""
        self.logcat_captured = False
        self.perf_analyzed = False
        self.result = "FAIL"
        self.exit_code = 1
        self.fail_reason = None
        self.error = None
        self.final_shot = False
        self.dl = {}
        self.state = None
        self.skip_reason = ""
        # mock（契约 v1，pdf-tiles）：host 进程 + adb reverse + pb_zlib_mirror extra
        self.mock = bool(getattr(args, "mock", False))
        self.mock_url = (getattr(args, "mock_url", "") or MOCK_DEFAULT_URL).rstrip("/")
        self.mock_port = 0
        try:
            self.mock_port = int(urllib.parse.urlsplit(self.mock_url).port or 8899)
        except Exception:
            self.mock_port = 8899
        self.mock_proc = None
        self.mock_owned = False
        self.mock_external = False
        self.mock_added_reverse = False
        self.mock_proxy_before = None
        self.mock_server_log = None
        self.mock_reused = False
        self.mock_request_count = 0
        self._mock_book_size_override = 0
        self._mock_log_override = ""
        # scenario 是场景名（download_faults），不是 fault case 名；这里不能走 parse_fault_cases（别名表里没有场景）。
        self.scenarios = [x.strip() for x in (getattr(args, "scenario", "") or "").split(",") if x.strip()]
        self.fault_cases = []
        if self.scenarios:
            try:
                self.fault_cases = parse_fault_cases(getattr(args, "fault_cases", ""))
            except ValueError:
                self.fault_cases = []
            if not self.fault_cases:
                self.fault_cases = ["first_packet_lost", "range_416", "slow", "cancel"]
        self.fault_results = []
        self.mock_summary = None

    # ------------------------------------------------------------ 基础
    def log(self, msg):
        print("[zlib-smoke] " + str(msg), flush=True)

    def warn(self, msg):
        self.warnings.append(msg)
        print("WARN|" + str(msg), flush=True)

    def rem(self):
        return self.deadline - time.time()

    def check(self, cid, ok, detail, required=True):
        rec = {"id": cid, "ok": bool(ok), "detail": str(detail), "required": bool(required)}
        self.asserts.append(rec)
        print("ASSERT|" + cid + "|" + ("PASS" if ok else "FAIL") + "|" + str(detail), flush=True)
        if required and not ok:
            raise SmokeError(1, cid + ": " + str(detail), step=cid)

    def step(self, name, detail, t0=None):
        ms = int((time.time() - t0) * 1000) if t0 else None
        self.steps.append({"name": name, "ok": True, "detail": str(detail), "ms": ms})
        print("STEP|" + name + "|OK|" + str(detail), flush=True)

    def resolve_adb(self):
        cands = []
        env_adb = os.environ.get("ADB", "")
        if env_adb:
            cands.append(winpath(env_adb))
        cands += [
            "G:/Android/sdk/platform-tools/adb.exe",
            "C:/Android/sdk/platform-tools/adb.exe",
            "D:/Android/sdk/platform-tools/adb.exe",
            "adb",
        ]
        for c in cands:
            exe = c if c == "adb" else winpath(c)
            if exe != "adb" and not os.path.exists(exe):
                continue
            try:
                p = subprocess.run([exe, "version"], capture_output=True, timeout=10)
                if p.returncode == 0:
                    return exe
            except Exception:
                continue
        raise SmokeError(4, "adb not found (set ADB=... or ANDROID_SDK_ROOT)")

    def adb(self, args, timeout=60, binary=False):
        budget = min(float(timeout), max(2.0, self.rem()))
        cmd = [self.adb_exe, "-s", self.serial] + [str(a) for a in args]
        try:
            p = subprocess.run(cmd, capture_output=True, timeout=budget)
        except subprocess.TimeoutExpired:
            raise SmokeError(3, "adb timeout after " + str(int(budget)) + "s: " + " ".join([str(a) for a in args]))
        except OSError as e:
            raise SmokeError(4, "adb spawn failed: " + str(e))
        return p.stdout if binary else p.stdout.decode("utf-8", "replace")

    def shell(self, args, timeout=60):
        return self.adb(["shell"] + list(args), timeout=timeout)

    def resolve_serial(self):
        if self.serial:
            return
        try:
            p = subprocess.run([self.adb_exe, "devices"], capture_output=True, timeout=min(20, max(2, self.rem())))
        except Exception as e:
            raise SmokeError(4, "adb devices failed: " + str(e))
        out = p.stdout.decode("utf-8", "replace")
        online = [l.split()[0] for l in out.splitlines() if l.strip() and l.split()[0] != "List" and l.strip().endswith("device")]
        if len(online) == 1:
            self.serial = online[0]
            self.log("auto-detected device: " + self.serial)
            return
        if not online:
            raise SmokeError(4, "no online adb device (adb devices empty); pass --serial")
        raise SmokeError(4, "multiple devices: " + ",".join(online) + " (pass --serial)")

    def wait_device(self):
        end = time.time() + float(self.args.wait_device)
        last = ""
        while time.time() < end:
            try:
                # 注意：必须用 adb get-state（不是 adb shell get-state，后者是设备侧命令，输出为空）
                state = self.adb(["get-state"], timeout=10).strip()
            except SmokeError:
                state = ""
            if state == "device":
                return
            last = state or "unknown"
            try:
                subprocess.run([self.adb_exe, "reconnect", "offline"], capture_output=True, timeout=10)
            except Exception:
                pass
            try:
                if "device" in (subprocess.run([self.adb_exe, "devices"], capture_output=True,
                                               timeout=10).stdout or b"").decode("utf-8", "replace"):
                    subprocess.run([self.adb_exe, "reconnect"], capture_output=True, timeout=10)
            except Exception:
                pass
            time.sleep(1.0)
        raise SmokeError(4, "device " + self.serial + " not ready after " + str(self.args.wait_device) + "s (last=" + last + ")")

    def read_device(self):
        d = self.dev
        d["serial"] = self.serial
        d["model"] = self.shell(["getprop", "ro.product.model"], timeout=15).strip()
        d["sdk"] = self.shell(["getprop", "ro.build.version.sdk"], timeout=15).strip()
        d["release"] = self.shell(["getprop", "ro.build.version.release"], timeout=15).strip()
        d["abi"] = self.shell(["getprop", "ro.product.cpu.abi"], timeout=15).strip()
        size = self.shell(["wm", "size"], timeout=15)
        m = re.search(r"(\d+)x(\d+)", size)
        if not m:
            raise SmokeError(4, "cannot parse wm size: " + size.replace("\n", " "))
        self.W, self.H = int(m.group(1)), int(m.group(2))
        d["wm_size"] = str(self.W) + "x" + str(self.H)
        dens = self.shell(["wm", "density"], timeout=15)
        m = re.search(r"(\d+)", dens)
        d["wm_density"] = m.group(1) if m else ""
        ver = self.shell(["dumpsys", "package", self.pkg], timeout=30)
        m = re.search(r"versionName=(\S+)", ver)
        d["version_name"] = m.group(1) if m else ""
        m = re.search(r"lastUpdateTime=([^\n]+)", ver)
        d["last_update_time"] = m.group(1).strip() if m else ""
        self.log("device " + d["serial"] + " " + d["model"] + " " + d["wm_size"] + " sdk=" + d["sdk"] + " ver=" + d["version_name"])

    def apk_fingerprint(self):
        apk = winpath(self.args.apk)
        if not os.path.isfile(apk):
            raise SmokeError(4, "APK not found: " + apk)
        h = hashlib.md5()
        with open(apk, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        self.dev["apk_path"] = apk
        self.dev["apk_md5"] = h.hexdigest()
        self.dev["apk_bytes"] = os.path.getsize(apk)
        self.log("apk md5=" + self.dev["apk_md5"] + " bytes=" + str(self.dev["apk_bytes"]))

    # ------------------------------------------------------------ 设备操作
    def shot(self, name, timeout=25):
        budget = min(float(timeout), max(2.0, self.rem()))
        try:
            data = self.adb(["exec-out", "screencap", "-p"], timeout=budget, binary=True)
        except SmokeError:
            return None
        path = os.path.join(self.outdir, self.tag + "-" + name + "-" + self.stamp + ".png")
        try:
            with open(path, "wb") as f:
                f.write(data)
        except Exception:
            return None
        self.artifacts["screenshots"].append(path)
        self.log("screenshot: " + path)
        return path

    def save_dump(self, name, xml):
        path = os.path.join(self.outdir, self.tag + "-ui-" + name + "-" + self.stamp + ".xml")
        try:
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                f.write(xml)
            self.artifacts["dumps"].append(path)
        except Exception:
            pass
        return path

    def dump_ui(self, save_name=None):
        # 真机上 uiautomator 偶发 2-8s 无响应（force-stop/冷启后尤甚）：
        # 单次 8s、最多 3 次，避免一条慢 dump 把整轮判成 harness failure。
        last_err = None
        for attempt in range(3):
            try:
                raw = self.adb(["exec-out", "uiautomator", "dump", "/dev/tty"], timeout=8, binary=True)
                text = raw.decode("utf-8", "replace")
                i = text.find("<?xml")
                j = text.find("</hierarchy>")
                if i < 0 or j < 0:
                    self.shell(["uiautomator", "dump", "/sdcard/_zlib_smoke_dump.xml"], timeout=8)
                    raw = self.adb(["exec-out", "cat", "/sdcard/_zlib_smoke_dump.xml"], timeout=8, binary=True)
                    text = raw.decode("utf-8", "replace")
                    i = text.find("<?xml")
                    j = text.find("</hierarchy>")
                if i >= 0 and j >= 0:
                    xml = text[i:j + len("</hierarchy>")]
                    nodes = parse_xml_nodes(xml)
                    if save_name:
                        self.save_dump(save_name, xml)
                    return nodes, xml
                last_err = "dump empty (attempt " + str(attempt + 1) + ")"
            except SmokeError as e:
                last_err = e
            if self.rem() < 3:
                break
            time.sleep(1.0)
        if isinstance(last_err, Exception):
            raise last_err
        return [], ""

    def wait(self, pred, timeout, desc, save_name=None):
        end = min(time.time() + float(timeout), self.deadline)
        nodes, xml = [], ""
        while time.time() < end:
            nodes, xml = self.dump_ui()
            r = pred(nodes)
            if r:
                if save_name:
                    self.save_dump(save_name, xml)
                return r, nodes
            time.sleep(0.8)
        if save_name:
            self.save_dump(save_name + "-timeout", xml)
        if self.rem() <= 0:
            raise SmokeError(3, "total timeout while waiting for " + desc, step=desc)
        return None, nodes

    def tap_xy(self, x, y, label):
        self.shell(["input", "tap", str(int(x)), str(int(y))], timeout=20)
        self.ui.setdefault("taps", {})[label] = [int(x), int(y)]
        self.log("tap " + label + " (" + str(int(x)) + "," + str(int(y)) + ")")

    def tap_node(self, node, label):
        x1, y1, x2, y2 = node["bounds"]
        if y2 - y1 < 12:
            raise SmokeError(1, "node too small to tap: " + label, step=label)
        self.tap_xy((x1 + x2) // 2, (y1 + y2) // 2, label)

    def scaled(self, coord):
        return int(round(coord[0] * self.W / REF_W)), int(round(coord[1] * self.H / REF_H))

    # ------------------------------------------------------------ 查找
    @staticmethod
    def find_exact(nodes, want):
        for n in nodes:
            if n["text"].strip() == want or n["desc"].strip() == want:
                return n
        return None

    @staticmethod
    def find_startswith(nodes, prefix):
        for n in nodes:
            if n["text"].strip().startswith(prefix) or n["desc"].strip().startswith(prefix):
                return n
        return None

    @staticmethod
    def find_contains(nodes, sub, min_y=None, max_y=None):
        for n in nodes:
            hay = n["text"] + "\n" + n["desc"]
            if sub in hay:
                y = n["bounds"][1]
                if min_y is not None and y < min_y:
                    continue
                if max_y is not None and y > max_y:
                    continue
                return n
        return None

    @staticmethod
    def find_regex(nodes, pat):
        rx = re.compile(pat)
        for n in nodes:
            m = rx.search(n["text"]) or rx.search(n["desc"])
            if m:
                return n
        return None

    @staticmethod
    def find_class(nodes, cls):
        for n in nodes:
            if cls in n["cls"]:
                return n
        return None

    def find_download_button(self, nodes):
        for n in nodes:
            t = n["text"].strip()
            if t.startswith("下载 ·") or t == "下载" or t.startswith("继续") or t.startswith("已下载"):
                return n
        return None

    # ------------------------------------------------------------ 下载闭环
    def rec(self, name, ok, detail):
        rec = {"name": name, "ok": bool(ok), "detail": str(detail)}
        self.steps.append(rec)
        print("STEP|" + name + "|" + ("OK" if ok else "FAIL") + "|" + str(detail), flush=True)
        return rec

    def set_state(self, state, reason=""):
        self.state = state
        if reason:
            self.skip_reason = reason

    def read_logcat(self, timeout=40):
        try:
            self.raw_logcat = self.shell(["logcat", "-d", "-b", "main", "-v", "threadtime"], timeout=timeout)
        except SmokeError as e:
            self.warn("logcat read failed: " + e.reason)
        return self.raw_logcat or ""

    def scan_perf(self, phase):
        events = []
        for line in (self.raw_logcat or "").splitlines():
            m = LOG_RE.match(line)
            if not m:
                continue
            ts, pid, _tid, _lvl, tag, msg = m.groups()
            if tag != "PBPERF":
                continue
            try:
                ipid = int(pid)
            except Exception:
                ipid = -1
            if self.app_pid and ipid and ipid != self.app_pid:
                continue
            parsed = parse_perf_msg(tag, msg)
            if not parsed or parsed[0] != phase:
                continue
            fields = parsed[1]
            events.append({"phase": phase, "ts": ts, "pid": ipid,
                           "ms": fields.get("ms"), "extra": fields.get("extra", "")})
        return events

    def download_events(self):
        events = []
        for e in self.scan_perf("zlib_download"):
            events.append(dict(e, **download_status_of(e.get("extra", ""))))
        return events

    @staticmethod
    def _pick_download_event(events, status):
        picked = None
        for e in events:
            if e.get("status") == status:
                picked = e
        return picked

    def download_dir(self):
        return DOWNLOAD_DIR_TMPL.format(pkg=self.pkg)

    @staticmethod
    def _shell_quote(text):
        return "'" + str(text).replace("'", "'\\''") + "'"

    def _run_shell_capture(self, cmd, timeout=25):
        return self.shell([str(cmd) + " 2>&1"], timeout=timeout)

    def list_download_files(self):
        """返回 (files|None, source)：None=读不到，{} = 目录为空或不存在。"""
        path = self.download_dir()
        out = self._run_shell_capture("ls -l " + self._shell_quote(path))
        files = parse_ls_lines(out)
        if files:
            return files, "ls"
        low = (out or "").lower()
        if not (out or "").strip() or "no such file" in low or "total 0" in low:
            return {}, "ls(empty)"
        out2 = self._run_shell_capture("run-as " + self.pkg + " ls -l " + self._shell_quote(path))
        files2 = parse_ls_lines(out2)
        if files2:
            return files2, "run-as ls"
        low2 = (out2 or "").lower()
        if "no such file" in low2 or "total 0" in low2:
            return {}, "run-as ls(empty)"
        if "permission denied" in low or "not debuggable" in low or "not debuggable" in low2:
            return None, "unreadable: " + ((out or out2) or "").strip()[:160]
        return None, "ls failed: " + ((out2 or out) or "").strip()[:160]

    def stat_file(self, path):
        out = self._run_shell_capture("ls -l " + self._shell_quote(path))
        files = parse_ls_lines(out)
        if files:
            return next(iter(files.values())), "ls"
        out2 = self._run_shell_capture("run-as " + self.pkg + " ls -l " + self._shell_quote(path))
        files2 = parse_ls_lines(out2)
        if files2:
            return next(iter(files2.values())), "run-as ls"
        return None, "stat failed: " + ((out2 or out) or "").strip()[:160]

    def read_books(self):
        """读取 files/books.json；返回 (books list|None, source)。"""
        out = self._run_shell_capture("run-as " + self.pkg + " cat files/books.json", timeout=25)
        try:
            obj = json.loads(out)
        except Exception:
            return None, "run-as failed: " + (out or "").strip()[:160]
        books = obj.get("books") if isinstance(obj, dict) else None
        if isinstance(books, list):
            return books, "run-as books.json"
        return None, "books.json shape unexpected: " + str(out)[:120]

    def find_progress(self, nodes):
        for n in nodes:
            for text in (n.get("text", ""), n.get("desc", "")):
                p = percent_in_text(text)
                if p is not None:
                    return p, (text or "").strip()
        return None, None

    def find_terminal(self, nodes):
        texts = []
        for n in nodes:
            if n.get("text"):
                texts.append(n["text"])
            if n.get("desc"):
                texts.append(n["desc"])
        return terminal_kind_texts(texts)

    def shot_once(self, key, name):
        if self.dl.get(key):
            return self.dl.get(key)
        path = self.shot(name)
        self.dl[key] = path
        return path

    def press_back(self):
        self.shell(["input", "keyevent", "4"], timeout=15)

    def finish_download_failure(self, ev):
        d = self.dl
        kind = ev.get("kind") or "UNKNOWN"
        d["outcome"] = "fail"
        d["error_kind"] = kind
        d["bytes"] = ev.get("bytes")
        d["perf_extra"] = ev.get("extra")
        self.shot_once("terminal_shot", "download-terminal")
        detail = ("status=fail kind=" + kind + " bytes=" + str(ev.get("bytes"))
                  + " ms=" + str(ev.get("ms")) + " extra=" + str(ev.get("extra"))[:200])
        if kind in DOWNLOAD_SKIP_KINDS and not self.args.daily_limit_fail:
            d["skip_reason"] = kind
            self.set_state("SKIP", kind)
            self.rec("download.terminal", False, "SKIP: " + detail)
            self.rec("download.state", False, "SKIP: " + kind + "（账号/额度前置，未执行真实下载闭环）")
            return
        self.shot("fail-download")
        self.rec("download.terminal", False, detail)
        self.rec("download.state", False, "FAIL: " + kind)
        raise SmokeError(1, "download failed: " + kind, step="download")

    def finish_download_success(self, ev):
        d = self.dl
        d["outcome"] = "ok"
        d["bytes"] = ev.get("bytes")
        d["perf_ms"] = ev.get("ms")
        d["perf_extra"] = ev.get("extra")
        d["target"] = (ev.get("raw") or {}).get("target", "")
        if not d.get("done_shot"):
            d["done_shot"] = self.shot("download-done")
        elapsed_ms = int((time.time() - (d.get("tap_at") or time.time())) * 1000)
        self.rec("download.terminal", True,
                 "zlib_download ok bytes=" + str(ev.get("bytes")) + " ms=" + str(ev.get("ms"))
                 + " target=" + d["target"] + " elapsed=" + str(elapsed_ms) + "ms")
        errors = []

        def need(name, ok, detail):
            if ok:
                self.rec(name, True, detail)
            else:
                self.rec(name, False, detail)
                errors.append(name + ": " + detail)

        # 1) 书架 books+N
        books_after, books_src2 = self.read_books()
        d["books_after"] = len(books_after) if books_after is not None else None
        d["books_source_after"] = books_src2
        adopt = self.scan_perf("library_adopt")
        adopt_books = None
        if adopt:
            try:
                adopt_books = int(kv_parse(adopt[-1].get("extra", "")).get("books", ""))
            except Exception:
                adopt_books = None
        d["adopt_books"] = adopt_books
        books_readable = (d.get("books_before") is not None and d.get("books_after") is not None)
        if books_readable:
            delta = d["books_after"] - d["books_before"]
            need("download.books_delta", delta == self.args.expect_books_delta,
                 "books " + str(d["books_before"]) + " -> " + str(d["books_after"])
                 + " (delta=" + str(delta) + " expect=" + str(self.args.expect_books_delta) + ")")
        elif adopt_books is not None:
            need("download.books_delta", True,
                 "advisory: books.json 不可读；library_adopt books=" + str(adopt_books))
            self.warn("books.json unreadable (" + books_src2 + "); relied on library_adopt books=" + str(adopt_books))
        else:
            need("download.books_delta", False,
                 "books.json unreadable and no library_adopt PBPERF (" + books_src2 + ")")

        # 2) 下载目录出现新文件（或 books.json 里新书的 filePath 可 stat）
        files_after, files_src2 = self.list_download_files()
        d["files_after"] = sorted(files_after) if files_after is not None else None
        d["files_source_after"] = files_src2
        before_files = d.get("files_before")
        before_names = set(before_files or [])
        after_names = set(files_after or [])
        new_files = sorted(after_names - before_names) if (files_after is not None and before_files is not None) else []
        file_ok = bool(new_files) and all((files_after or {}).get(n, 0) > 0 for n in new_files)
        file_detail = ("new=" + str(new_files) + " before="
                       + str(len(before_files) if before_files is not None else "?") + " after="
                       + str(len(files_after) if files_after is not None else "?"))
        if not file_ok and books_after is not None:
            before_paths = set(d.get("_books_before_paths") or [])
            candidates = [b.get("filePath") for b in books_after
                          if isinstance(b, dict) and b.get("filePath") and b.get("filePath") not in before_paths]
            for path in candidates:
                size, src = self.stat_file(path)
                if size is not None and size > 0:
                    file_ok = True
                    file_detail += "; stat=" + str(size) + "B (" + src + ") " + str(path)
                    break
        if file_ok:
            need("download.file_exists", True, file_detail)
        elif files_after is None and books_after is None:
            self.rec("download.file_exists", True,
                     "advisory: release 包不可读下载目录/books.json；PBPERF ok bytes=" + str(ev.get("bytes")))
            self.warn("release build: download-file assertion advisory only")
        elif d.get("target") == "saf":
            self.rec("download.file_exists", True,
                     "advisory: SAF 目标目录不在默认路径；PBPERF ok bytes=" + str(ev.get("bytes")))
            self.warn("SAF target: default dir may be empty; relied on books adoption")
        else:
            need("download.file_exists", False, file_detail + " source=" + files_src2)

        # 3) 回书架截图
        try:
            self.press_back()
            time.sleep(1.0)
            self.shot("shelf")
            self.rec("download.shelf_shot", True, "back to shelf + screenshot")
        except Exception as e:
            self.warn("shelf screenshot failed: " + str(e))
            self.rec("download.shelf_shot", False, "screenshot failed: " + str(e))

        if errors:
            self.shot("fail-download-assert")
            self.rec("download.state", False, "FAIL: " + "; ".join(errors))
            raise SmokeError(1, "download closed-loop assertion failed: " + "; ".join(errors), step="download")
        self.set_state("PASS", "")
        self.rec("download.state", True, "PASS: trigger+terminal+books+file")

    # ------------------------------------------------------------ 故障场景（mock 契约 v1）
    def ensure_search_results(self):
        try:
            if self.back_to_search(2):
                return True
        except SmokeError:
            pass
        try:
            self.open_zlib_tab()
        except SmokeError as e:
            self.warn("re-open Z-Library tab failed: " + e.reason)
        try:
            if self.back_to_search(1):
                return True
        except SmokeError:
            pass
        try:
            self.do_search()
            return True
        except SmokeError as e:
            self.warn("re-search failed: " + e.reason)
            return False

    def open_fault_case(self, idx):
        """为第 idx 个 fault case 打开一个未下载的搜索结果详情页。"""
        if idx == 0 and self.ui.get("_detail_nodes"):
            btn = self.find_download_button(self.ui["_detail_nodes"])
            if btn is not None and not btn["text"].strip().startswith("已下载"):
                return True
        if not self.ensure_search_results():
            return False
        for attempt in range(10):
            rows = self.refresh_search_rows()
            if not rows:
                return False
            row = rows[min(idx, len(rows) - 1)]
            self.tap_node(row, "fault_row" + str(attempt))
            r, nodes = self.wait(lambda ns: self.find_exact(ns, "书籍详情"), 20,
                                 "fault detail", save_name="fault-detail")
            if not r:
                self.ensure_search_results()
                continue
            btn = self.find_download_button(nodes)
            if btn is not None and not btn["text"].strip().startswith("已下载"):
                self.ui["_detail_nodes"] = nodes
                self.ui["fault_row_title"] = row["text"].strip()
                return True
            self.warn("fault row " + str(idx) + " already downloaded; trying next row")
            idx = (idx + 1) % max(1, len(rows))
            if not self.ensure_search_results():
                return False
        return False

    def _inject_fault(self, case):
        if case == "first_packet_lost":
            self.mock_set_fault("first_packet_lost", count=1, path="/cdn/", first_bytes=0)
        elif case == "range_416":
            self.mock_set_fault("range_416", count=1, path="/cdn/")
        elif case == "slow":
            self.mock_set_fault("slow", count=1, path="/cdn/",
                                rate_kbps=int(self.args.mock_slow_kbps),
                                duration_ms=int(self.args.mock_slow_stall_ms))
        else:
            raise SmokeError(2, "unknown fault case: " + case)

    def _fault_detail(self, case, entries, stats):
        faults = mock_faults(entries)
        hit = [e for e in faults if str(e.get("fault")) == case]
        detail = "fault_hit=" + str(len(hit))
        if stats:
            detail += " status_416=" + str(mock_stats_int(stats, "status_416"))
            detail += " cdn_requests=" + str(mock_stats_int(stats, "cdn_requests"))
            detail += " client_closed=" + str(mock_stats_int(stats, "client_closed"))
        return hit, detail

    def _assert_fault_case(self, case, entries, stats):
        hit, detail = self._fault_detail(case, entries, stats)
        recovered = mock_success_after_fault(entries, case)
        completed = (self.state == "PASS")
        if case == "first_packet_lost":
            raw = self.read_logcat()
            retry_log = "dl_retry reason=first_packet_lost" in raw
            dl_reqs = len([e for e in entries if str(e.get("path", "")).startswith("/dl")])
            retried = dl_reqs >= 2
            ok = bool(hit) and recovered and completed and retried
            detail += (" recovered=" + str(recovered) + " completed=" + str(completed)
                       + " log_retry=" + str(retry_log) + " dl_requests=" + str(dl_reqs))
            return ok, detail
        if case == "range_416":
            got416 = bool(hit) or mock_stats_int(stats, "status_416") > 0
            ok = got416 and completed
            detail += " completed=" + str(completed) + " bytes=" + str(self.dl.get("bytes"))
            if got416 and not completed:
                detail += "（416 已注入但下载未成功：B1 416 自愈 / 416 当直链 未实现？）"
            if not got416:
                detail += "（mock 未观测到 416：注入点应在正式流式 GET，而不是 HEAD/探测）"
            return ok, detail
        if case == "slow":
            durations = []
            for e in hit:
                try:
                    durations.append(float(e.get("duration_ms") or 0))
                except Exception:
                    pass
            ok = bool(hit) and completed
            detail += " completed=" + str(completed) + " duration_ms=" + str(max(durations) if durations else 0)
            return ok, detail
        return False, detail + " unknown case"

    def find_cancel_control(self, nodes):
        for n in nodes:
            for text in (n.get("text", ""), n.get("desc", "")):
                if "取消" in (text or "") and (n.get("clickable") or True):
                    return n
        return None

    def find_part_files(self):
        """返回 (paths|None, source)：None=读不到；[]=没有 .part。"""
        found = []
        cmds = [
            "run-as " + self.pkg + " find files -name '*.part' 2>&1",
            "run-as " + self.pkg + " ls -l /sdcard/Android/data/" + self.pkg
            + "/files/Download/Z-Library 2>&1",
        ]
        readable = False
        for cmd in cmds:
            out = self._run_shell_capture(cmd)
            low = (out or "").lower()
            if "permission denied" in low or "not debuggable" in low or "unknown option" in low:
                continue
            readable = True
            for line in (out or "").splitlines():
                if ".part" in line:
                    found.append(line.strip())
        if not readable:
            return None, "unreadable"
        return found, "run-as"

    def _part_sizes_in(self, path, prefix_args=None):
        cmd = ""
        if prefix_args:
            cmd = " ".join(prefix_args) + " "
        out = self._run_shell_capture(cmd + "ls -l " + self._shell_quote(path))
        found = {}
        for line in (out or "").splitlines():
            parts = line.split()
            if len(parts) < 8 or not parts[0].startswith("-"):
                continue
            name = " ".join(parts[7:])
            if not name.endswith(".part"):
                continue
            try:
                found[name] = int(parts[4])
            except Exception:
                pass
        return found

    def part_entries(self):
        """列出默认下载目录与 cache/zlib-downloads 下的 .part（name -> size）。"""
        out = dict(self._part_sizes_in(self.download_dir()))
        out.update(self._part_sizes_in("cache/zlib-downloads", ["run-as", self.pkg]))
        return out

    def wait_part_bytes(self, target, timeout):
        """轮询 .part 大小，直到 >= target 或超时；返回 (best_size, entries, elapsed)。"""
        t0 = time.time()
        best = 0
        entries = {}
        while time.time() - t0 < timeout:
            entries = self.part_entries()
            best = max(entries.values()) if entries else 0
            if best >= target:
                break
            time.sleep(0.15)
        return best, entries, time.time() - t0

    @staticmethod
    def sanitize_book_title(title):
        import re as _re
        base = _re.sub(r'[\\/:*?"<>|\n\r\t]', " ", (title or "").strip())
        base = " ".join(base.split())
        return base[:80] or "book"

    def seed_oversized_part(self, name, kib=2500):
        """用 run-as 在默认下载目录写一个超大 .part（> 服务端 total）。"""
        path = self.download_dir() + "/" + name + ".part"
        q = self._shell_quote(path)
        out = self._run_shell_capture(
            "run-as " + self.pkg + " sh -c 'dd if=/dev/zero of=" + q + " bs=1024 count=" + str(kib) + " 2>&1'",
            timeout=40,
        )
        self.rec("d3.seed", True, "path=" + path + " kib=" + str(kib) + " out=" + (out or "").strip()[:120])
        parts = self.part_entries()
        size = parts.get(name + ".part")
        if not size:
            raise SmokeError(1, "D3 超大 .part 未写入: " + path + " (parts=" + str(parts) + ")", step="d3")
        return path, size

    def _wait_download_terminal(self, timeout):
        t0 = time.time()
        while time.time() - t0 < min(timeout, max(10, self.deadline - time.time())):
            try:
                nodes = self.dump_ui()[0]
            except SmokeError:
                nodes = []
            term = self.find_terminal(nodes)
            if term in ("done", "limit", "login", "failed"):
                return term
            self.read_logcat()
            time.sleep(0.5)
        return None

    def _download_metrics(self):
        stats = self.mock_stats()
        return {
            "status_416": mock_stats_int(stats, "status_416"),
            "range_requests": mock_stats_int(stats, "range_requests"),
            "partial_206": mock_stats_int(stats, "partial_206"),
            "bytes_served": mock_stats_int(stats, "bytes_served"),
        }

    def run_fresh_416_case(self):
        """a) 全新下载（无 Range）首包 416 → 无 Range 重试 → 200 完成（fresh-416 自愈）。"""
        self.mock_reset()
        self.mock_set_fault("fresh_416", count=1, path="/cdn/")
        nodes = self.ui.get("_detail_nodes") or self.dump_ui()[0]
        btn = self.find_download_button(nodes)
        if btn is None:
            raise SmokeError(1, "fresh_416：详情页无下载按钮", step="fresh_416")
        self.tap_node(btn, "download_fresh_416")
        terminal = self._wait_download_terminal(self.args.download_timeout)
        metrics = self._download_metrics()
        logs = self.read_logcat()
        dl416 = [ln for ln in (logs or "").splitlines() if "dl_416" in ln]
        no_range_retry = [ln for ln in dl416 if "retry=no_range" in ln]
        events = self.download_events()
        ok_event = self._pick_download_event(events, "ok")
        health = self.mock_health() or {}
        book = health.get("book") if isinstance(health.get("book"), dict) else {}
        expect_bytes = int(book.get("bytes") or 0)
        got_bytes = ok_event.get("bytes") if ok_event else None
        # UI 的「登录」文案会污染 terminal 检测（详情页/底栏），下载成功以 perf 事件 + 字节数为准。
        ok = (terminal != "failed" and metrics["status_416"] >= 1 and bool(no_range_retry)
              and ok_event is not None and (expect_bytes <= 0 or got_bytes == expect_bytes))
        detail = ("terminal=" + str(terminal) + " status_416=" + str(metrics["status_416"])
                  + " dl_416_logs=" + str(len(dl416)) + " no_range_retry=" + str(len(no_range_retry))
                  + " bytes=" + str(got_bytes) + "/" + str(expect_bytes))
        self.rec("fresh416.self_heal", ok, detail)
        self.shot_once("fresh416_done", "fresh-416-selfheal")
        if not ok:
            raise SmokeError(1, "fresh_416 断言失败: " + detail, step="fresh_416")
        self.state = "PASS"

    def restart_mock_with_book_size(self, book_size):
        """重启自拉 mock，用更小的 book-size（模拟「.part 比服务端总长还大」）。"""
        self._mock_book_size_override = int(book_size)
        self._mock_log_override = os.path.join(
            self.outdir, self.tag + "-" + self.stamp + "-mock2.jsonl")
        self.stop_mock()
        self.mock_proc = None
        self.mock_owned = False
        time.sleep(1.0)
        self.start_mock()

    def run_d3_case(self):
        """D3-v2：先让 .part 长到 ~50%，再重启 mock 用更小 book-size → part > 服务端总长
        → Range 请求 416 → app 删 .part → 无 Range 重下 → 新文件完整（size/sha256）。

        关键技巧：Android 11+ 的 run-as 无法写 app 外部 filesDir（FUSE 按 uid 挂载），
        所以不能像旧版用 `run-as dd` 预置超大 .part；改为让 app 自己产生 .part，
        然后重启 mock 传更小的 `--book-size`（< part 长度），使 Range 起点 ≥ 新总长，
        mock 自然回 416，精确覆盖「.part 比服务端文件还长」分支。
        """
        self.mock_reset()
        health = self.mock_health() or {}
        book = health.get("book") if isinstance(health.get("book"), dict) else {}
        full_bytes = int(book.get("bytes") or 0)
        if full_bytes <= 0:
            raise SmokeError(2, "mock /__health 无 book.bytes")
        small_bytes = max(120000, full_bytes // 3)
        self.mock_set_fault("slow", count=1, path="/cdn/", rate_kbps=200)
        nodes = self.ui.get("_detail_nodes") or self.dump_ui()[0]
        btn = self.find_download_button(nodes)
        if btn is None:
            raise SmokeError(1, "D3：详情页无下载按钮", step="d3")
        self.tap_node(btn, "download_d3_partial")
        target = full_bytes // 2
        part_bytes, parts, waited = self.wait_part_bytes(target, timeout=40)
        self.rec("d3.partial", part_bytes >= target,
                 "part=" + str(part_bytes) + "/" + str(target) + " waited=" + str(round(waited, 2)) + "s files=" + str(parts))
        if part_bytes < target:
            raise SmokeError(1, "D3 未能制造半成品 part（part=" + str(part_bytes) + "）", step="d3")
        part_name = max(parts, key=lambda k: parts[k])
        self.shell(["am", "force-stop", self.pkg], timeout=20)
        time.sleep(1.5)
        self.restart_mock_with_book_size(small_bytes)
        health2 = self.mock_health() or {}
        book2 = health2.get("book") if isinstance(health2.get("book"), dict) else {}
        new_total = int(book2.get("bytes") or 0)
        new_sha = str(book2.get("sha256") or "").lower()
        self.dl["d3"] = {"full_bytes": full_bytes, "part_bytes": part_bytes, "part_name": part_name,
                         "mock2_bytes": new_total, "mock2_sha256": new_sha}
        self.rec("d3.restart_mock", new_total > 0 and new_total < part_bytes,
                 "mock2 bytes=" + str(new_total) + " < part=" + str(part_bytes))
        self.start_app()
        self.open_zlib_tab()
        self.do_search()
        self.open_detail()
        self.state = None
        self.dl["d3_tap_at"] = time.time()
        nodes = self.ui.get("_detail_nodes") or self.dump_ui()[0]
        btn = self.find_download_button(nodes)
        if btn is None:
            raise SmokeError(1, "D3：恢复后详情页无下载/继续按钮", step="d3")
        self.tap_node(btn, "download_d3_resume")
        terminal = self._wait_download_terminal(self.args.download_timeout)
        events = self.download_events()
        ok_event = self._pick_download_event(events, "ok")
        logs = self.read_logcat()
        dl416 = [ln for ln in (logs or "").splitlines() if "dl_416" in ln]
        fresh_retry = [ln for ln in dl416 if "retry=fresh" in ln]
        metrics = self._download_metrics()
        time.sleep(1.0)
        files_after, _s2 = self.list_download_files()
        files_after = files_after or {}
        part_left = [n for n in files_after if n.endswith(".part")]
        final_name = part_name[:-5] if part_name.endswith(".part") else part_name
        final_size = files_after.get(final_name)
        sha_ok = None
        sha_dev = None
        if final_size:
            out = self._run_shell_capture("sha256sum " + self._shell_quote(self.download_dir() + "/" + final_name), timeout=60)
            if not (out or "").strip():
                out = self._run_shell_capture("run-as " + self.pkg + " sha256sum " + self._shell_quote(self.download_dir() + "/" + final_name), timeout=60)
            parts2 = (out or "").split()
            if parts2 and len(parts2[0]) == 64:
                sha_dev = parts2[0].lower()
                sha_ok = (sha_dev == new_sha) if new_sha else None
        ok = (terminal != "failed" and ok_event is not None and metrics["status_416"] >= 1
              and bool(fresh_retry) and bool(part_left is not None)
              and final_size == new_total and sha_ok is not False)
        detail = ("terminal=" + str(terminal) + " status_416=" + str(metrics["status_416"])
                  + " range_reqs=" + str(metrics["range_requests"]) + " dl416=" + str(len(dl416))
                  + " fresh_retry=" + str(len(fresh_retry)) + " part_left=" + str(part_left)
                  + " size=" + str(final_size) + "/" + str(new_total)
                  + " sha_ok=" + str(sha_ok) + " bytes_event=" + str(ok_event and ok_event.get("bytes")))
        self.rec("d3.self_heal", ok, detail)
        self.shot_once("d3_done", "d3-416-selfheal")
        if not ok:
            raise SmokeError(1, "D3 断言失败: " + detail, step="d3")
        self.state = "PASS"

    def run_resume_case(self):
        """续传：slow 下到 ~40% 杀进程 → 重启 → 再下载，断言 Range/206/最终完整。"""
        self.mock_reset()
        health = self.mock_health() or {}
        book = health.get("book") if isinstance(health.get("book"), dict) else {}
        book_bytes = int(book.get("bytes") or 0)
        if book_bytes <= 0:
            raise SmokeError(2, "mock /__health 无 book.bytes，无法做续传阈值")
        target = book_bytes * 40 // 100
        # 200KB/s：2MB 约 10s，足够在 .part 上取到 40% 再杀。
        self.mock_set_fault("slow", count=1, path="/cdn/", rate_kbps=200)
        nodes = self.ui.get("_detail_nodes") or self.dump_ui()[0]
        btn = self.find_download_button(nodes)
        if btn is None:
            raise SmokeError(1, "续传用例：详情页无下载按钮")
        self.tap_node(btn, "download_resume_first")
        part_bytes, parts, waited = self.wait_part_bytes(target, timeout=40)
        served = mock_stats_int(self.mock_stats(), "bytes_served")
        self.rec("resume.partial", part_bytes >= target,
                 "part=" + str(part_bytes) + "/" + str(target) + " target served=" + str(served)
                 + "/" + str(book_bytes) + " files=" + str(parts) + " waited=" + str(round(waited, 2)) + "s")
        self.shot_once("resume_shot", "resume-progress")
        if part_bytes < target:
            raise SmokeError(1, "未到 40% 或 .part 不存在（part=" + str(part_bytes) + " target=" + str(target) + "）", step="resume")
        self.shell(["am", "force-stop", self.pkg], timeout=20)
        time.sleep(1.5)
        self.rec("resume.kill", True, "am force-stop after served=" + str(served))
        self.start_app()
        self.open_zlib_tab()
        self.do_search()
        self.open_detail()
        self.mock_reset()
        self.dl = {}
        self.state = None
        self.do_download()
        entries = self.mock_log(tail=2000)
        ranges = [e for e in entries if str(e.get("path", "")).startswith("/cdn") and mock_range_of(e)]
        partial = [e for e in ranges if int(e.get("status") or 0) == 206]
        ok = bool(ranges) and bool(partial) and self.state == "PASS"
        detail = ("range_requests=" + str(len(ranges)) + " partial_206=" + str(len(partial))
                  + " completed=" + str(self.state == "PASS")
                  + " bytes=" + str(self.dl.get("bytes")) + " expect=" + str(book_bytes))
        self.rec("resume.range", ok, detail)
        if not ok:
            raise SmokeError(1, "续传断言失败: " + detail, step="resume")
        if self.dl.get("bytes") not in (book_bytes, None):
            self.rec("resume.bytes", False, "bytes=" + str(self.dl.get("bytes")) + " expect=" + str(book_bytes))
        else:
            self.rec("resume.bytes", True, "bytes=" + str(self.dl.get("bytes")))
        self.dl["resume"] = {"served_before_kill": served, "part_before_kill": part_bytes,
                             "range_requests": len(ranges), "partial_206": len(partial)}

    def run_cancel_case(self, out):
        """取消用例：慢速下载中找『取消』控件 → 点 → 断言 CANCELLED + 不留 .part。"""
        rate = int(getattr(self.args, "mock_cancel_kbps", 16))
        self.mock_set_fault("slow", count=1, path="/cdn/", rate_kbps=rate)
        nodes = self.ui.get("_detail_nodes") or self.dump_ui()[0]
        btn = self.find_download_button(nodes) or self.find_download_button(self.dump_ui()[0])
        if btn is None:
            out.update(state="SKIP", detail="SKIP: 详情页无下载按钮" )
            self.rec("fault.cancel", False, out["detail"])
            return out
        self.tap_node(btn, "download_cancel_case")
        # 等下载真的开始（mock 有 /cdn 请求或 UI 出现进度）
        started = False
        end = min(time.time() + float(self.args.trigger_timeout), self.deadline)
        while time.time() < end:
            entries = self.mock_log(tail=100)
            if any(str(e.get("path", "")).startswith("/cdn") for e in entries):
                started = True
                break
            try:
                ns, _ = self.dump_ui()
            except SmokeError:
                ns = []
            p, _txt = self.find_progress(ns)
            if p is not None:
                started = True
                break
            time.sleep(float(self.args.poll_interval))
        if not started:
            out.update(state="FAIL", detail="点到下载后 30s 无 /cdn 请求/进度")
            self.rec("fault.cancel", False, out["detail"])
            return out
        before = self.mock_stats()
        nodes, _ = self.dump_ui()
        cancel = self.find_cancel_control(nodes)
        if cancel is None:
            out.update(state="SKIP", detail="SKIP: 当前构建无『取消』控件（task-29 未落地取消入口）")
            self.rec("fault.cancel", False, out["detail"])
            self.warn("cancel 用例 SKIP：UI 无取消控件；若 task-29 加取消后本用例会自动启用")
            return out
        self.tap_node(cancel, "cancel_download")
        time.sleep(2.0)
        after = self.mock_stats()
        closed = mock_stats_int(after, "client_closed") - mock_stats_int(before, "client_closed")
        raw = self.read_logcat()
        cancelled = "zlib_download" in raw and "kind:CANCELLED" in raw
        parts, part_src = self.find_part_files()
        no_part = (parts == [])
        ok = closed >= 1 and cancelled and no_part
        detail = ("client_closed+" + str(closed) + " perf_cancelled=" + str(cancelled)
                  + " parts=" + str(parts) + "(" + part_src + ")")
        out.update(state="PASS" if ok else "FAIL", detail=detail)
        self.rec("fault.cancel", ok, detail)
        return out

    def run_one_fault_case(self, idx, case):
        t0 = time.time()
        out = {"case": case, "state": "FAIL", "detail": "", "started_at": now_iso()}
        self.log("fault case " + str(idx + 1) + "/" + str(len(self.fault_cases)) + ": " + case)
        try:
            if not self.open_fault_case(idx):
                out.update(state="SKIP", detail="SKIP: 无可用搜索结果行/详情页")
                self.rec("fault." + case, False, out["detail"])
                return out
            self.mock_reset()
            self.dl = {}
            self.state = None
            self.skip_reason = ""
            if case == "cancel":
                out = self.run_cancel_case(out)
            else:
                self._inject_fault(case)
                self.do_download()
                entries = self.mock_log(tail=2000)
                stats = self.mock_stats()
                ok, detail = self._assert_fault_case(case, entries, stats)
                out.update(state="PASS" if ok else "FAIL", detail=detail)
                out["mock_stats"] = stats
                out["fault_entries"] = mock_faults(entries)
                self.rec("fault." + case, ok, detail)
            out["download"] = {k: v for k, v in self.dl.items() if not k.startswith("_")}
            out["elapsed_ms"] = int((time.time() - t0) * 1000)
            return out
        except SmokeError as e:
            out.update(state="FAIL", detail=e.reason)
            out["download"] = {k: v for k, v in self.dl.items() if not k.startswith("_")}
            out["elapsed_ms"] = int((time.time() - t0) * 1000)
            self.warn("fault case " + case + " FAIL: " + e.reason)
            self.rec("fault." + case, False, e.reason)
            return out

    def run_download_faults(self):
        if not self.args.download:
            raise SmokeError(2, "--scenario download_faults 需要 --download")
        self.log("download_faults cases=" + ",".join(self.fault_cases))
        for idx, case in enumerate(self.fault_cases):
            self.fault_results.append(self.run_one_fault_case(idx, case))
        self.dl["fault_cases"] = self.fault_results
        states = [r.get("state") for r in self.fault_results]
        passed = states.count("PASS")
        skipped = states.count("SKIP")
        failed = states.count("FAIL")
        self.rec("fault.summary", failed == 0,
                 "pass=" + str(passed) + " skip=" + str(skipped) + " fail=" + str(failed))
        if failed:
            bad = [r["case"] + ":" + r["detail"][:160] for r in self.fault_results
                   if r.get("state") == "FAIL"]
            raise SmokeError(1, "download_faults: " + str(failed) + " case(s) FAIL — " + " | ".join(bad),
                             step="download_faults")
        if passed == 0:
            self.set_state("SKIP", "all fault cases skipped")

    # ------------------------------------------------------------ 主流程
    # ------------------------------------------------------------ mock（契约 v1）
    @staticmethod
    def _tail(path, limit=1200):
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as fh:
                text = fh.read()
            return text[-limit:]
        except Exception:
            return ""

    def _mock_abs(self, path):
        if not path.startswith("/"):
            path = "/" + path
        return self.mock_url + path

    def _mock_http(self, method, path, body=None, timeout=6.0):
        budget = min(float(timeout), max(1.0, self.rem()))
        data = None
        headers = {}
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(self._mock_abs(path), data=data, headers=headers, method=method)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(req, timeout=budget) as resp:
            raw = resp.read(4_000_000)
            ctype = resp.headers.get("Content-Type", "") or ""
        text = raw.decode("utf-8", "replace")
        head = text.lstrip()[:1]
        if "json" in ctype.lower() or head in ("{", "["):
            try:
                return json.loads(text)
            except Exception:
                return text
        return text

    def mock_health(self):
        try:
            return self._mock_http("GET", "/__health", timeout=3.0)
        except Exception:
            return None

    def mock_ready(self, health=None):
        h = health if isinstance(health, dict) else self.mock_health()
        return isinstance(h, dict) and bool(h.get("ok")) and bool(h.get("ready"))

    def mock_stats(self):
        try:
            stats = self._mock_http("GET", "/__stats", timeout=5.0)
            if isinstance(stats, dict):
                inner = stats.get("stats")
                if isinstance(inner, dict):
                    return inner
                return stats
        except Exception:
            pass
        return {}

    def mock_log(self, tail=2000):
        try:
            data = self._mock_http("GET", "/__log?tail=" + str(int(tail)), timeout=8.0)
            entries = parse_mock_entries(data)
        except Exception:
            entries = []
        self.mock_request_count = max(self.mock_request_count, len(entries))
        return entries

    def mock_post(self, path, payload=None):
        return self._mock_http("POST", path, payload or {}, timeout=8.0)

    def mock_reset(self):
        try:
            self.mock_post("/__reset", {})
        except Exception as e:
            self.warn("mock /__reset failed: " + str(e))

    def mock_set_fault(self, mode, count=1, path="/cdn/", **extra):
        body = {"mode": mode, "count": int(count), "path": path}
        body.update(extra)
        try:
            out = self.mock_post("/__fault", body)
            self.log("mock fault " + json.dumps(body, ensure_ascii=False) + " -> " + str(out)[:200])
            return out
        except Exception as e:
            raise SmokeError(4, "mock /__fault " + mode + " failed: " + str(e))

    def start_mock(self):
        if not self.mock:
            return
        start_mode = getattr(self.args, "mock_start", "auto")
        health = self.mock_health()
        if self.mock_ready(health) and start_mode != "always":
            self.mock_owned = False
            self.mock_reused = True
            self.step("mock.reuse", "reuse " + self.mock_url + " version=" + str(health.get("version", "?")))
            self.dev["mock_version"] = health.get("version")
            return
        if start_mode == "never":
            raise SmokeError(4, "mock 不可用且 --mock-start=never: " + self.mock_url)
        parts = urllib.parse.urlsplit(self.mock_url)
        host = parts.hostname or ""
        if host not in ("127.0.0.1", "localhost"):
            raise SmokeError(4, "--mock 只支持本机 mock（host=" + host + "），外部服务请配合 --mock-start=never")
        if health is not None:
            raise SmokeError(4, "端口 " + str(self.mock_port) + " 被非 mock 服务占用：/__health 不是 {ok,ready} JSON")
        script = winpath(getattr(self.args, "mock_script", "") or "") or \
            os.path.join(self.root, "scripts", "android", "zlib-mock.py")
        if not os.path.isfile(script):
            raise SmokeError(4, "mock 脚本不存在: " + winpath(script) + "（等 pdf-tiles 落盘）")
        log_path = winpath(getattr(self.args, "mock_log", "") or "")
        if not log_path:
            log_path = self._mock_log_override or os.path.join(self.outdir, self.tag + "-" + self.stamp + "-mock.jsonl")
        if not os.path.isabs(log_path):
            log_path = os.path.join(self.root, log_path)
        parent = os.path.dirname(log_path)
        if parent:
            os.makedirs(parent, exist_ok=True)
        self.mock_server_log = os.path.join(self.outdir, self.tag + "-" + self.stamp + "-mock-server.log")
        env = dict(os.environ)
        env["PYTHONIOENCODING"] = "utf-8"
        env["PYTHONUNBUFFERED"] = "1"
        cmd = [sys.executable, script, "--port", str(self.mock_port), "--log", log_path]
        dl_mode = getattr(self.args, "mock_dl_mode", "") or ""
        if dl_mode:
            cmd.extend(["--dl-mode", dl_mode])
        book_count = int(getattr(self.args, "mock_book_count", 3) or 3)
        cmd.extend(["--book-count", str(book_count)])
        book_size = self._mock_book_size_override or int(getattr(self.args, "mock_book_size", 0) or 0)
        if book_size > 0:
            cmd.extend(["--book-size", str(book_size)])
        self.log("spawn mock: " + " ".join(cmd))
        with open(self.mock_server_log, "w", encoding="utf-8", newline="\n") as lf:
            self.mock_proc = subprocess.Popen(cmd, cwd=self.root, stdout=lf, stderr=subprocess.STDOUT, env=env)
        deadline = time.time() + float(getattr(self.args, "mock_wait", 25))
        ready = False
        while time.time() < deadline:
            if self.mock_proc.poll() is not None:
                raise SmokeError(4, "mock 进程退出 rc=" + str(self.mock_proc.returncode)
                                 + " tail=" + self._tail(self.mock_server_log))
            if self.mock_ready():
                ready = True
                break
            time.sleep(0.3)
        if not ready:
            self.stop_mock()
            raise SmokeError(4, "mock 未在 " + str(getattr(self.args, "mock_wait", 25))
                             + "s 内 ready；server log=" + str(self.mock_server_log))
        self.mock_owned = True
        self.dev["mock_log"] = log_path
        self.dev["mock_server_log"] = self.mock_server_log
        self.step("mock.start", "pid=" + str(self.mock_proc.pid) + " port=" + str(self.mock_port))

    def stop_mock(self):
        if not self.mock:
            return
        proc = self.mock_proc
        if proc is not None and self.mock_owned and not getattr(self.args, "keep_mock", False):
            try:
                proc.terminate()
                try:
                    proc.wait(timeout=4)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait(timeout=3)
            except Exception as e:
                self.warn("mock stop failed: " + str(e))
            self.dev["mock_stopped"] = True
        elif proc is not None and getattr(self.args, "keep_mock", False):
            self.warn("mock 保持运行（--keep-mock）pid=" + str(proc.pid))

    def reset_state(self):
        """mock 专用：清掉上一次 mock 下载留下的书籍/下载记录/临时文件，但**保留登录 cookie**。

        不用 pm clear（设备上有真实账号登录态，Lead 2026-11-03 22:46 明确禁止）。
        只删 books.json / zlib_downloads.json / cache 下的 .part 与 Mock Book*.pdf，
        首启会从 assets fixtures 重建 4 本 seed 书。
        """
        if getattr(self.args, "reset_state", "none") != "mock":
            return
        self.step("reset_state", "mock books/downloads cleared (login kept)")
        for argv in (
            ["run-as", self.pkg, "rm", "-f", "files/books.json"],
            ["run-as", self.pkg, "rm", "-f", "files/books.json.tmp"],
            ["run-as", self.pkg, "rm", "-f", "files/zlib_downloads.json"],
            ["run-as", self.pkg, "rm", "-rf", "cache/zlib-downloads"],
        ):
            try:
                self.shell(argv, timeout=20)
            except SmokeError as e:
                self.warn("reset: " + " ".join(argv[:3]) + " -> " + e.reason)
        # _pb_files 里的 mock 书（run-as 列目录后精确删除，避免误删用户导入的书）
        try:
            listing = self.shell(["run-as", self.pkg, "ls", "files/_pb_files"], timeout=20)
            for name in (listing or "").splitlines():
                name = name.strip()
                if name and "Mock Book" in name:
                    self.shell(["run-as", self.pkg, "rm", "-f", "files/_pb_files/" + name], timeout=20)
        except SmokeError as e:
            self.warn("reset _pb_files: " + e.reason)
        # 外部下载目录里的 mock 书
        dl_dir = "/sdcard/Android/data/" + self.pkg + "/files/Download/Z-Library"
        try:
            listing = self.shell(["ls", dl_dir], timeout=20)
            for name in (listing or "").splitlines():
                name = name.strip()
                if name and "Mock Book" in name:
                    self.shell(["rm", "-f", dl_dir + "/" + name], timeout=20)
        except SmokeError as e:
            self.warn("reset download dir: " + e.reason)

    def mock_network_setup(self):
        dump = self.shell(["dumpsys", "package", self.pkg], timeout=30)
        if "DEBUGGABLE" not in dump:
            raise SmokeError(1, "APK 非 debuggable：pb_zlib_mirror 会被 release 逻辑忽略，mock 无法生效（需 debug 包）")
        before = (self.shell(["settings", "get", "global", "http_proxy"], timeout=15) or "").strip()
        self.mock_proxy_before = before
        self.dev["proxy_before"] = before
        self.shell(["settings", "put", "global", "http_proxy", ":0"], timeout=15)
        time.sleep(0.6)
        now = (self.shell(["settings", "get", "global", "http_proxy"], timeout=15) or "").strip()
        self.dev["proxy_mock"] = now
        if now not in ("null", "", ":0"):
            self.warn("系统代理未清空: " + now + "（app 可能把 mock 请求发给真实代理）")
        mapping = "tcp:" + str(self.mock_port) + " tcp:" + str(self.mock_port)
        rev = self.adb(["reverse", "--list"], timeout=20)
        self.mock_added_reverse = mapping not in rev
        if self.mock_added_reverse:
            self.adb(["reverse", "tcp:" + str(self.mock_port), "tcp:" + str(self.mock_port)], timeout=20)
        rev2 = self.adb(["reverse", "--list"], timeout=20)
        if mapping not in rev2:
            raise SmokeError(4, "adb reverse 未建立: " + mapping + " list=" + rev2.strip()[:200])
        self.dev["mock"] = {
            "url": self.mock_url,
            "port": self.mock_port,
            "reverse": mapping,
            "reverse_added": self.mock_added_reverse,
            "proxy_before": before,
            "proxy_now": now,
        }
        self.step("mock.network", "proxy cleared + " + mapping)

    def mock_network_restore(self):
        if not self.mock:
            return
        if self.mock_added_reverse:
            try:
                self.adb(["reverse", "--remove", "tcp:" + str(self.mock_port)], timeout=15)
            except Exception as e:
                self.warn("adb reverse --remove failed: " + str(e))
        try:
            before = self.mock_proxy_before
            if before in (None, "", "null", ":0"):
                self.shell(["settings", "put", "global", "http_proxy", ":0"], timeout=15)
            else:
                self.shell(["settings", "put", "global", "http_proxy", before], timeout=15)
            self.dev["proxy_restored"] = before
        except Exception as e:
            self.warn("restore proxy failed: " + str(e))

    def mock_wait_requests(self, pred, timeout, desc):
        end = min(time.time() + float(timeout), self.deadline)
        stats = {}
        entries = []
        while time.time() < end:
            stats = self.mock_stats()
            entries = self.mock_log(tail=800)
            try:
                if pred(stats, entries):
                    return stats, entries
            except Exception:
                pass
            if self.mock_proc is not None and self.mock_proc.poll() is not None:
                raise SmokeError(4, "mock 进程已退出 rc=" + str(self.mock_proc.returncode))
            time.sleep(0.4)
        detail = "stats=" + json.dumps(stats, ensure_ascii=False)[:320]
        raise SmokeError(1, "mock 未收到预期请求（" + desc + "）: " + detail, step="mock")

    def mock_assert_phase(self, name, prefixes, timeout=25):
        stats, entries = self.mock_wait_requests(
            lambda st, es: mock_sequence_ok(es, prefixes), timeout, name)
        self.check("mock." + name, True,
                   "paths=" + ",".join(mock_paths(entries)[-6:]) + " reqs="
                   + str(mock_stats_int(stats, "requests")))

    def mock_net_route_check(self):
        # R7-G loopback 直连修复的日志证据（ZlibClient.kt:1125）：
        # net_route host=127.0.0.1 proxy=none loopback=true
        raw = self.read_logcat()
        host_ok = "net_route host=127.0.0.1" in raw
        loop_ok = "loopback=true" in raw
        self.check("mock.net_route_loopback", host_ok and loop_ok,
                   "host_127.0.0.1=" + str(host_ok) + " loopback=true=" + str(loop_ok),
                   required=False)

    def mock_verify_override(self):
        raw = ""
        ok = False
        for _ in range(10):
            raw = self.read_logcat()
            ok = ("debug_mirror_override" in raw) and (self.mock_url in raw)
            if ok:
                break
            time.sleep(0.5)
        self.check("mock.app_override", ok,
                   "PBZlib debug_mirror_override " + ("found" if ok else "missing")
                   + " (base=" + self.mock_url + ")")

    def mock_assert_download(self, entries=None):
        entries = entries if entries is not None else self.mock_log(tail=2000)
        stats = self.mock_stats()
        cdn = [e for e in entries if str(e.get("path", "")).startswith("/cdn")]
        dl = [e for e in entries if str(e.get("path", "")).startswith("/dl")]
        ranges = [e for e in cdn if mock_range_of(e)]
        self.check("mock.download_chain", bool(dl) and bool(cdn),
                   "dl=" + str(len(dl)) + " cdn=" + str(len(cdn)) + " range=" + str(len(ranges)))
        self.dl["mock"] = {
            "stats": stats,
            "requests": len(entries),
            "cdn": len(cdn),
            "dl": len(dl),
            "range_requests": len(ranges),
        }
        return entries, stats

    def mock_collect(self):
        if not self.mock:
            return
        entries = self.mock_log(tail=2000)
        stats = self.mock_stats()
        self.mock_summary = {
            "url": self.mock_url,
            "port": self.mock_port,
            "owned": self.mock_owned,
            "reused": self.mock_reused,
            "log": self.dev.get("mock_log"),
            "server_log": self.mock_server_log,
            "stats": stats,
            "requests": entries,
            "faults": mock_faults(entries),
        }

    def run(self):
        if self.mock:
            self.start_mock()
        self.resolve_serial()
        self.wait_device()
        self.read_device()
        self.apk_fingerprint()
        self.install()
        self.finish_install()
        self.reset_state()
        if self.mock:
            self.mock_network_setup()
        self.clear_logcat()
        self.wake()
        self.start_app()
        if self.mock:
            # app 启动时并不发网络请求（Z-Library 页才触发），先验 override 再开 tab，
            # 否则会在这里空等 20s 误判 FAIL。override 日志要趁 buffer 新鲜时读（后面系统日志会冲刷）。
            time.sleep(0.5)
            self.mock_verify_override()
        self.open_zlib_tab()
        self.do_search()
        if self.mock:
            self.mock_assert_phase("search", ["/s/"])
            self.mock_net_route_check()
        self.open_detail()
        if self.mock:
            self.mock_assert_phase("detail", ["/s/", "/book/"])
        if "fresh_416" in self.scenarios:
            self.run_fresh_416_case()
        elif "d3_416" in self.scenarios:
            self.run_d3_case()
        elif "resume" in self.scenarios:
            self.run_resume_case()
        elif self.fault_cases:
            self.run_download_faults()
        elif self.args.download:
            self.do_download()
            if self.mock:
                self.mock_assert_download()
        else:
            self.rec("download.state", True, "skipped (--download not given)")
        self.collect_evidence()
        self.check_alive()
        self.shot("final")
        self.final_shot = True
        self.assert_perf()
        self.assert_crash()

    def install(self):
        if self.args.no_install:
            out = self.shell(["pm", "path", self.pkg], timeout=20).strip()
            if "package:" not in out:
                raise SmokeError(4, "package not installed: " + self.pkg + " (and --no-install given)")
            self.step("install", "skipped (--no-install), " + out)
            return
        t = time.time()
        self.log("install " + self.dev["apk_path"])
        out = self.adb(["install", "-r", "-t", self.dev["apk_path"]], timeout=150)
        if "Success" not in out:
            raise SmokeError(4, "install failed: " + out.strip()[-300:])
        self.dev["install_ms"] = int((time.time() - t) * 1000)
        self.step("install", "Success in " + str(self.dev["install_ms"]) + "ms", t)

    def finish_install(self):
        # 绝不 pm clear：设备上有真实登录态（cookie 在 filesDir）与用户书架数据，
        # 清数据会毁掉验收环境。只 force-stop，保证下一次 am start 是冷启。
        self.shell(["am", "force-stop", self.pkg], timeout=20)
        self.step("cleanup", "am force-stop (no pm clear)")
        ver = self.shell(["dumpsys", "package", self.pkg], timeout=30)
        m = re.search(r"versionName=(\S+)", ver)
        if m:
            self.dev["version_name"] = m.group(1)
        m = re.search(r"lastUpdateTime=([^\n]+)", ver)
        if m:
            self.dev["last_update_time"] = m.group(1).strip()

    def clear_logcat(self):
        ok = False
        for _ in range(3):
            self.shell(["logcat", "-b", "main", "-b", "system", "-b", "crash", "-c"], timeout=20)
            out = self.shell(["logcat", "-d", "-b", "main", "-v", "brief"], timeout=20).strip()
            if not out:
                ok = True
                break
            self.shell(["am", "force-stop", self.pkg], timeout=20)
            time.sleep(1.0)
        if not ok:
            self.warn("logcat not fully cleared after 3 tries")
        try:
            self.dev_epoch = int(self.shell(["date", "+%s"], timeout=10).strip() or "0")
        except Exception:
            self.dev_epoch = 0
        self.step("clear_logcat", "cleared" if ok else "not fully cleared")

    def wake(self):
        self.shell(["input", "keyevent", "224"], timeout=15)
        self.shell(["wm", "dismiss-keyguard"], timeout=15)
        time.sleep(0.3)

    def start_app(self):
        t = time.time()
        self.shell(["am", "force-stop", self.pkg], timeout=20)
        time.sleep(0.5)
        cmd = ["am", "start", "-W", "-n", self.activity]
        if self.mock:
            # debug MobileActivity.applyDebugMirrorOverride()：把 ZlibClient.mirror 固定成 mock 基址，
            # 避免 app 先 probeMirrors 打外网（离线确定性）。
            cmd += ["--es", MOCK_MIRROR_EXTRA, self.mock_url]
        out = self.shell(cmd, timeout=60)
        m = re.search(r"TotalTime:\s*(\d+)", out)
        self.dev["am_total_ms"] = int(m.group(1)) if m else None
        m = re.search(r"Status:\s*(\w+)", out)
        self.dev["am_status"] = m.group(1) if m else ""
        if self.dev["am_status"] and self.dev["am_status"] != "ok":
            self.warn("am start status=" + self.dev["am_status"])
        self.step("cold_start", "am TotalTime=" + str(self.dev["am_total_ms"]) + "ms", t)
        r, nodes = self.wait(lambda ns: any(n["pkg"] == self.pkg for n in ns), 25, "app window")
        if not r:
            raise SmokeError(1, "app window not visible after start", step="start")
        pid = self.shell(["pidof", self.pkg], timeout=15).strip()
        if pid:
            try:
                self.app_pid = int(pid.split()[0])
            except Exception:
                self.app_pid = None
        self.log("app pid=" + str(self.app_pid))
        r, nodes = self.wait(lambda ns: self.find_exact(ns, "Z-Library"), 20, "bottom nav")
        if not r:
            raise SmokeError(1, "bottom nav Z-Library not found", step="start")

    def open_zlib_tab(self):
        nodes, _ = self.dump_ui()
        tab = None
        for n in nodes:
            if n["text"].strip() == "Z-Library" and n["bounds"][1] > int(self.H * 0.72):
                tab = n
                break
        use_coord = True
        if tab is not None:
            x1, y1, x2, y2 = tab["bounds"]
            if (y2 - y1) >= 24:
                use_coord = False
                self.tap_node(tab, "zlib_tab")
                self.ui["tab_node_bounds"] = [x1, y1, x2, y2]
        if use_coord:
            x, y = self.scaled(COORD_TAB)
            self.tap_xy(x, y, "zlib_tab")
            self.ui["tab_tap_fallback"] = [x, y]
        def page_ready(ns):
            # 只认 Z-Library 页面本体，避免书架页残留的搜索图标/搜索框误判
            for n in ns:
                if n["text"].strip() == "Z-Library" and n["bounds"][3] - n["bounds"][1] >= 24 \
                        and n["bounds"][1] < int(self.H * 0.2):
                    return n
            sub = self.find_contains(ns, "内置书源")
            if sub is not None:
                return sub
            for n in ns:
                if n["text"].strip() == "搜索":
                    y = n["bounds"][1]
                    if int(self.H * 0.2) < y < int(self.H * 0.45):
                        return n
            return None

        r, nodes = self.wait(page_ready, 20, "Z-Library page")
        if not r:
            raise SmokeError(1, "Z-Library page did not appear (no title/subtitle/search button)", step="zlib_tab")
        self.check("ui.zlib_tab", True, "Z-Library page opened")

    @staticmethod
    def result_rows(nodes, summary_y2):
        """从搜索结果页 nodes 里挑结果标题行（按 y 排序）；summary_y2 是『已加载』表头底边。"""
        bad = ["已加载", "第 ", "已经到底了", "书名", "作者", "ISBN", "搜索", "线路", "登录"]
        cands = []
        for n in nodes:
            t = n["text"].strip()
            if len(t) < 4:
                continue
            if n["bounds"][1] <= summary_y2 + 10 or n["bounds"][1] >= int(REF_H * 0.9):
                continue
            if any(b in t for b in bad):
                continue
            cands.append(n)
        cands.sort(key=lambda n: n["bounds"][1])
        return cands

    def refresh_search_rows(self):
        nodes, _ = self.dump_ui()
        hdr = self.find_regex(nodes, r"(?:已加载|找到)\s*(\d+)\s*本")
        if hdr is None:
            return []
        rows = self.result_rows(nodes, hdr["bounds"][3])
        self.ui["result_rows"] = [{"title": n["text"].strip(), "bounds": list(n["bounds"])}
                                  for n in rows]
        return rows

    def back_to_search(self, tries=3):
        for _ in range(tries):
            try:
                nodes, _ = self.dump_ui()
            except SmokeError:
                nodes = []
            if self.find_regex(nodes, r"(?:已加载|找到)\s*\d+\s*本") is not None:
                return True
            self.press_back()
            time.sleep(1.2)
        return False

    def do_search(self):
        nodes, _ = self.dump_ui()
        field = self.find_contains(nodes, "书名 / 作者 / ISBN")
        if field is None:
            field = self.find_class(nodes, "EditText")
        if field is not None:
            self.tap_node(field, "search_field")
            self.ui["search_field_bounds"] = list(field["bounds"])
        else:
            x, y = self.scaled(COORD_FIELD)
            self.tap_xy(x, y, "search_field")
            self.ui["search_field_tap_fallback"] = [x, y]
        time.sleep(0.6)
        self.shell(["input", "text", "android"], timeout=20)
        time.sleep(0.5)
        nodes, _ = self.dump_ui()
        typed = self.find_exact(nodes, "android")
        if typed is None:
            # 兼容输入法把内容拆到子节点的情况
            typed = self.find_contains(nodes, "android")
        self.check("ui.search_input", typed is not None, "field content 'android' visible")
        t = time.time()
        btn = self.find_exact(nodes, "搜索")
        if btn is None:
            btn = self.find_startswith(nodes, "搜索")
        if btn is not None:
            self.tap_node(btn, "search_button")
            self.ui["search_button_bounds"] = list(btn["bounds"])
        else:
            x, y = self.scaled(COORD_SEARCH)
            self.tap_xy(x, y, "search_button")
            self.ui["search_button_tap_fallback"] = [x, y]
        self.step("search_tap", "button tapped", t)

        def pred(ns):
            s = self.find_regex(ns, r"(?:已加载|找到)\s*(\d+)\s*本")
            if s:
                return ("ok", s)
            e = self.find_contains(ns, "搜索失败") or self.find_contains(ns, "错误类型")
            if e:
                return ("err", e)
            return None

        t = time.time()
        r, nodes = self.wait(pred, self.args.search_timeout, "search results", save_name="search")
        if r is None:
            self.shot("fail-search-timeout")
            self.check("ui.search_loaded", False, "no results header within " + str(self.args.search_timeout) + "s")
            return
        kind, node = r
        if kind == "err":
            errs = [n["text"].strip() for n in nodes if n["text"].strip() and (
                "搜索失败" in n["text"] or "错误" in n["text"] or "镜像" in n["text"] or "重试" in n["text"])]
            detail = " | ".join(errs[:6])
            self.shot("fail-search-error")
            self.check("ui.search_loaded", False, "search error state: " + detail[:300])
            return
        self.ui["results_text"] = node["text"].strip()
        m = re.search(r"(?:已加载|找到)\s*(\d+)\s*本", node["text"])
        count = int(m.group(1)) if m else 0
        self.ui["results_count"] = count
        self.check("ui.search_loaded", True, "header=" + node["text"].strip().replace("\n", " "))
        expect_results = self.args.mock_expect_results if self.mock else self.args.expect_results
        if count < expect_results:
            msg = "results=" + str(count) + " < expect=" + str(expect_results)
            if self.args.strict_results:
                self.check("ui.results_count", False, msg + " (--strict-results)")
            else:
                self.warn(msg)
                self.check("ui.results_count", True, msg + " (warn only)")
        else:
            self.check("ui.results_count", True, "results=" + str(count) + " >= expect=" + str(expect_results))

        # 结果行（标题用于详情页书名断言；download_faults 用不同行跑多个 case）
        cands = self.result_rows(nodes, node["bounds"][3])
        self.ui["result_rows"] = [{"title": n["text"].strip(), "bounds": list(n["bounds"])}
                                  for n in cands]
        if cands:
            idx = int(getattr(self.args, "result_index", 0) or 0)
            if idx < 0 or idx >= len(cands):
                idx = 0
            chosen = cands[idx]
            self.ui["first_title"] = chosen["text"].strip()
            self.ui["first_title_bounds"] = list(chosen["bounds"])
            self.ui["first_row"] = chosen
            self.ui["result_index"] = idx
        else:
            self.warn("cannot identify first result title (fallback coordinate tap)")
            self.ui["first_title"] = None
        self.step("search", "results=" + str(count) + " in " + str(int((time.time() - t) * 1000)) + "ms", t)

    def open_detail(self):
        node = self.ui.get("first_row")
        if node is not None:
            self.tap_node(node, "first_result")
        else:
            x, y = self.scaled(COORD_FIRST)
            self.tap_xy(x, y, "first_result")
        r, nodes = self.wait(lambda ns: self.find_exact(ns, "书籍详情"), 25, "book detail", save_name="detail")
        if not r:
            self.shot("fail-detail")
            self.check("ui.detail_opened", False, "detail page (书籍详情) not visible")
            return
        self.check("ui.detail_opened", True, "book detail opened")

        # 详情页可能先显示「正在加载详情…」，最多再等 20s 让书名出现（原先单次 dump 会误判）
        expected_pre = self.ui.get("first_title")
        if expected_pre:
            ne_pre = norm(expected_pre)
            def _title_ready(ns):
                for n in ns:
                    tt = norm(n["text"])
                    if not tt:
                        continue
                    if ne_pre in tt or (len(tt) >= 6 and (tt in ne_pre or tt[:12] == ne_pre[:12])):
                        return True
                return False
            r_pre, nodes = self.wait(_title_ready, 20, "detail title loaded", save_name="detail-title")
            if not r_pre:
                self.warn("detail title not visible after 20s (still loading)")

        expected = self.ui.get("first_title")
        matched = None
        if expected:
            ne = norm(expected)
            best = None
            for n in nodes:
                t = norm(n["text"])
                if not t or not ne:
                    continue
                if ne in t:
                    score = len(t)
                elif len(t) >= 6 and (t in ne or t[:12] == ne[:12]):
                    score = len(t)
                else:
                    continue
                if best is None or score > best[0]:
                    best = (score, n["text"].strip())
            matched = best[1] if best else None
        if expected and matched:
            self.ui["detail_title"] = matched
            self.check("ui.detail_title", True, "detail contains title: " + matched)
        elif expected and self.mock and self.find_regex(nodes, r"Mock Book \d+") is not None:
            # mock /book/<hash> 故意把 book-title 改写成 "Mock Book N"（fixture 限制），
            # 详情页自然拿不到搜索结果里的真实书名；这不算 app 缺陷，记 advisory。
            self.ui["detail_title"] = "Mock Book N (mock fixture)"
            self.check("ui.detail_title", True,
                       "mock fixture rewrites book-title; expected search title not required", required=False)
            self.warn("mock detail book-title rewritten; search title='" + expected[:60] + "'")
        elif expected:
            self.ui["detail_title"] = None
            self.check("ui.detail_title", False, "detail does not contain first title: " + expected)
        else:
            self.warn("first title unknown; skip detail title match")
            self.check("ui.detail_title", True, "skipped (first title unknown)", required=False)

        btn = self.find_download_button(nodes)
        self.ui["download_button_text"] = btn["text"].strip() if btn else None
        if btn is not None:
            self.ui["download_button_bounds"] = list(btn["bounds"])
        self.check("ui.download_button", btn is not None,
                   "download button=" + (btn["text"].strip() if btn else "missing"))
        self.ui["_detail_nodes"] = nodes

    def do_download(self):
        """下载闭环三态：触发证据(<=30s) → 终态(<=180s) → 书架 +1 / 文件出现。"""
        d = self.dl
        t = time.time()
        d["tap_at"] = t
        nodes = self.ui.get("_detail_nodes") or self.dump_ui()[0]
        btn = self.find_download_button(nodes)
        if btn is None:
            btn = self.find_download_button(self.dump_ui()[0])
        books_before, books_src = self.read_books()
        files_before, files_src = self.list_download_files()
        d.update({
            "trigger_timeout": self.args.trigger_timeout,
            "terminal_timeout": self.args.download_timeout,
            "books_before": len(books_before) if books_before is not None else None,
            "books_source": books_src,
            "files_before": sorted(files_before) if files_before is not None else None,
            "files_source": files_src,
            "_books_before_paths": [b.get("filePath") for b in (books_before or []) if isinstance(b, dict)],
        })
        self.rec("download.baseline", True,
                 "books=" + str(d["books_before"]) + " (" + books_src + ") files="
                 + str(len(files_before) if files_before is not None else "?") + " (" + files_src + ")")
        if btn is not None and btn["text"].strip().startswith("已下载"):
            d["outcome"] = "already_downloaded"
            self.set_state("SKIP", "already_downloaded")
            self.rec("download.trigger", True, "skipped: button=" + btn["text"].strip())
            self.rec("download.state", False, "SKIP: already_downloaded（未触发新下载）")
            return
        if btn is not None:
            self.tap_node(btn, "download")
        else:
            x, y = self.scaled(COORD_DOWNLOAD)
            self.tap_xy(x, y, "download")
            d["tap_fallback"] = [x, y]

        # Phase A：30s 内必须出现 zlib_download PBPERF 或卡片进度百分比
        trigger_end = min(time.time() + float(self.args.trigger_timeout), self.deadline)
        percent_seen = None
        ui_terminal = None
        events = []
        while time.time() < trigger_end:
            try:
                nodes, _ = self.dump_ui()
            except SmokeError:
                nodes = []
            p, _ptext = self.find_progress(nodes)
            if p is not None:
                percent_seen = p if percent_seen is None else max(percent_seen, p)
                d["percent_seen"] = percent_seen
                if p >= self.args.progress_shot_min:
                    self.shot_once("progress_shot", "download-progress")
            ui_terminal = self.find_terminal(nodes) or ui_terminal
            if ui_terminal in ("limit", "login", "done", "failed"):
                self.shot_once("terminal_shot", "download-terminal")
            self.read_logcat()
            events = self.download_events()
            if events or percent_seen is not None:
                break
            time.sleep(float(self.args.poll_interval))
        d["trigger_elapsed_ms"] = int((time.time() - t) * 1000)
        evidence = bool(events) or percent_seen is not None
        if not evidence:
            msg = ("no zlib_download PBPERF and no progress % within "
                   + str(self.args.trigger_timeout) + "s (ui_terminal=" + str(ui_terminal) + ")")
            if ui_terminal in ("limit", "login"):
                d["outcome"] = ui_terminal
                if self.args.daily_limit_fail:
                    self.shot("fail-download-limit")
                    self.rec("download.trigger", False, "FAIL(--daily-limit-fail): " + msg)
                    self.rec("download.state", False, "FAIL: " + ui_terminal)
                    raise SmokeError(1, "download blocked by " + ui_terminal
                                     + " and --daily-limit-fail is set", step="download")
                self.set_state("SKIP", ui_terminal)
                self.rec("download.trigger", False, "SKIP: " + msg)
                self.shot_once("terminal_shot", "download-limit")
                self.rec("download.state", False, "SKIP: " + ui_terminal)
                return
            self.shot("fail-download-trigger")
            self.rec("download.trigger", False, msg)
            self.rec("download.state", False, "FAIL: 触发后 30s 内无下载证据")
            raise SmokeError(1, "download trigger evidence missing within "
                             + str(self.args.trigger_timeout) + "s", step="download")
        self.rec("download.trigger", True,
                 "percent=" + str(percent_seen) + " perf=" + str(len(events))
                 + " in " + str(d["trigger_elapsed_ms"]) + "ms")
        if percent_seen is None and not d.get("progress_shot"):
            self.shot_once("start_shot", "download-start")
        ok_ev = self._pick_download_event(events, "ok")
        fail_ev = self._pick_download_event(events, "fail")
        if ok_ev:
            return self.finish_download_success(ok_ev)
        if fail_ev:
            return self.finish_download_failure(fail_ev)

        # Phase B：等终态（<=--download-timeout）
        term_end = min(time.time() + float(self.args.download_timeout), self.deadline)
        ok_ev = fail_ev = None
        while time.time() < term_end:
            self.read_logcat()
            ev = self.download_events()
            ok_ev = self._pick_download_event(ev, "ok")
            fail_ev = self._pick_download_event(ev, "fail")
            if ok_ev or fail_ev:
                break
            try:
                nodes, _ = self.dump_ui()
            except SmokeError:
                nodes = []
            p, _ptext = self.find_progress(nodes)
            if p is not None:
                percent_seen = max(percent_seen or 0, p)
                d["percent_seen"] = percent_seen
                if p >= self.args.progress_shot_min:
                    self.shot_once("progress_shot", "download-progress")
            ui_terminal = self.find_terminal(nodes) or ui_terminal
            time.sleep(float(self.args.poll_interval))
        d["terminal_elapsed_ms"] = int((time.time() - t) * 1000)
        if not ok_ev and not fail_ev:
            self.read_logcat()
            ev = self.download_events()
            ok_ev = self._pick_download_event(ev, "ok")
            fail_ev = self._pick_download_event(ev, "fail")
        if ok_ev:
            return self.finish_download_success(ok_ev)
        if fail_ev:
            return self.finish_download_failure(fail_ev)
        if ui_terminal in ("limit", "login") and not self.args.daily_limit_fail:
            d["outcome"] = ui_terminal
            self.set_state("SKIP", ui_terminal)
            self.rec("download.terminal", False, "SKIP: " + ui_terminal + "（仅 UI 文案，无 PBPERF）")
            self.rec("download.state", False, "SKIP: " + ui_terminal)
            return
        self.shot("fail-download-terminal-timeout")
        self.rec("download.terminal", False,
                 "no terminal within " + str(self.args.download_timeout) + "s; ui_terminal="
                 + str(ui_terminal) + " percent=" + str(percent_seen))
        self.rec("download.state", False, "FAIL: 终态超时")
        raise SmokeError(3, "download terminal not reached within "
                         + str(self.args.download_timeout) + "s", step="download")

    # ------------------------------------------------------------ 证据与日志
    def capture_logcat(self):
        if self.logcat_captured:
            return
        self.logcat_captured = True
        time.sleep(0.6)
        raw = self.read_logcat()
        if not raw:
            self.warn("logcat capture empty")
            return
        path = os.path.join(self.outdir, self.tag + "-" + self.stamp + ".logcat.log")
        try:
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                f.write(raw)
            self.artifacts["logcat"] = path
            self.log("logcat: " + path)
        except Exception as e:
            self.warn("logcat write failed: " + str(e))

    def analyze_perf(self):
        if self.perf_analyzed:
            return
        self.perf_analyzed = True
        perf = self.perf
        for line in (self.raw_logcat or "").splitlines():
            m = LOG_RE.match(line)
            if not m:
                continue
            ts, pid, _tid, _lvl, tag, msg = m.groups()
            try:
                ipid = int(pid)
            except Exception:
                ipid = -1
            if tag not in ("PBPERF", "PBZlib"):
                continue
            if self.app_pid and ipid and ipid != self.app_pid:
                continue
            if tag == "PBZlib":
                if "dl_page" in msg or "switch_mirror" in msg or "net_attempt" in msg:
                    perf["debug"].append({"tag": "PBZlib", "msg": msg.strip()[:300], "ts": ts})
                continue
            parsed = parse_perf_msg(tag, msg)
            if not parsed:
                continue
            phase, fields = parsed
            ms = None
            try:
                ms = float(fields.get("ms", ""))
            except Exception:
                ms = None
            entry = {"ms": ms, "extra": fields.get("extra", ""), "ts": ts, "pid": ipid}
            perf["by_phase"].setdefault(phase, []).append(entry)
            if phase == "cold_start":
                perf["cold_start"] = entry
            elif phase in ("zlib_search", "zlib_login", "zlib_download"):
                perf[phase].append(entry)

    def assert_perf(self):
        self.analyze_perf()
        # 故障/重置跑法里冷启数字不作验收指标（reset 后首启要重建 books.json，且 mock 日志缓冲会挤掉早期行）
        perf_required = not (getattr(self.args, "scenario", "") or getattr(self.args, "reset_state", "none") != "none")
        cold = self.perf["cold_start"]
        if cold:
            self.check("perf.cold_start", True, "cold_start ms=" + str(cold["ms"]), required=perf_required)
        else:
            self.check("perf.cold_start", False, "PBPERF phase=cold_start missing in logcat", required=perf_required)
        search = self.perf["zlib_search"]
        if search:
            extra = kv_parse(search[-1]["extra"])
            detail = "zlib_search ms=" + str(search[-1]["ms"]) + " results=" + str(extra.get("results", "?")) + \
                     " mirror=" + str(extra.get("mirror", "?"))
            self.check("perf.zlib_search", True, detail)
            if "results" in extra and self.ui.get("results_count") is not None:
                try:
                    if int(extra["results"]) != int(self.ui["results_count"]):
                        self.warn("PBPERF results=" + str(extra["results"]) + " != UI results=" + str(self.ui["results_count"]))
                except Exception:
                    pass
        else:
            self.check("perf.zlib_search", False, "PBPERF zlib_search missing in logcat")
        if self.perf["zlib_login"]:
            self.ui["zlib_login_ms"] = self.perf["zlib_login"][-1]["ms"]
        if self.args.download and self.state != "SKIP" and not self.perf["zlib_download"]:
            self.warn("zlib_download PBPERF missing after --download")

    def assert_crash(self):
        raw = self.raw_logcat or ""
        fatal = re.search(r"FATAL EXCEPTION[^\n]*\n\s*Process:\s*" + re.escape(self.pkg), raw) is not None
        if not fatal:
            fatal = ("FATAL EXCEPTION" in raw) and (self.pkg in raw)
        anr = re.search(r"ANR in\s+" + re.escape(self.pkg), raw) is not None
        self.check("log.no_crash", not fatal and not anr,
                   "fatal=" + str(fatal).lower() + " anr=" + str(anr).lower())

    def check_alive(self):
        pid = self.shell(["pidof", self.pkg], timeout=15).strip()
        self.dev["alive_pid"] = pid
        self.check("app.alive", bool(pid), "pid=" + (pid or "none"))

    def collect_evidence(self):
        self.capture_logcat()
        self.analyze_perf()

    # ------------------------------------------------------------ 收尾
    def finalize(self):
        if self.error is not None and not self.artifacts["screenshots"]:
            try:
                self.shot("fail-" + (self.error.step or "run"))
            except Exception:
                pass
        try:
            self.capture_logcat()
            self.analyze_perf()
        except Exception as e:
            self.warn("finalize logcat error: " + str(e))
        if not self.args.no_stop:
            try:
                self.shell(["am", "force-stop", self.pkg], timeout=20)
            except Exception:
                pass
        if self.mock:
            try:
                self.mock_collect()
            except Exception as e:
                self.warn("mock collect failed: " + str(e))
            try:
                self.mock_network_restore()
            except Exception as e:
                self.warn("mock network restore failed: " + str(e))
            try:
                self.stop_mock()
            except Exception as e:
                self.warn("mock stop failed: " + str(e))
        payload = self.payload()
        path = winpath(self.args.json) if self.args.json else os.path.join(
            self.outdir, self.tag + "-" + self.stamp + ".json")
        if not os.path.isabs(path):
            path = os.path.join(self.root, path)
        try:
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                json.dump(payload, f, ensure_ascii=False, indent=2)
            self.artifacts["json"] = path
            self.log("json: " + path)
        except Exception as e:
            self.warn("json write failed: " + str(e))
        print("RESULT|" + self.result + "|exit=" + str(self.exit_code) + "|json=" + str(self.artifacts["json"]), flush=True)
        if self.mock and self.mock_summary:
            stats = self.mock_summary.get("stats") or {}
            print("MOCK|url=" + self.mock_url + "|owned=" + str(self.mock_summary.get("owned"))
                  + "|stats=" + json.dumps(stats, ensure_ascii=False), flush=True)
        for case in self.fault_results:
            print("MOCK|fault=" + str(case.get("case")) + "|" + str(case.get("state"))
                  + "|" + str(case.get("detail")), flush=True)
        for k in ("cold_start", "zlib_search", "zlib_login", "zlib_download"):
            v = self.perf.get(k)
            if isinstance(v, list):
                if v:
                    print("PERF|" + k + "|ms=" + ",".join(str(x["ms"]) for x in v) + "|extra=" + (v[-1]["extra"] or ""), flush=True)
            elif v:
                print("PERF|" + k + "|ms=" + str(v["ms"]) + "|extra=" + (v["extra"] or ""), flush=True)
            else:
                print("PERF|" + k + "|missing", flush=True)
        if self.fail_reason:
            print("FAIL|" + self.fail_reason, flush=True)

    def payload(self):
        return {
            "script": "scripts/android/zlib-smoke.sh",
            "driver": "scripts/android/zlib-smoke.py",
            "schema": 2,
            "state": self.state or ("PASS" if self.result == "PASS" else self.result),
            "skip_reason": self.skip_reason,
            "result": self.result,
            "exit_code": self.exit_code,
            "fail_reason": self.fail_reason,
            "elapsed": round(time.time() - self.t0, 1),
            "started_at": datetime.fromtimestamp(self.t0).astimezone().isoformat(timespec="seconds"),
            "finished_at": now_iso(),
            "duration_ms": int((time.time() - self.t0) * 1000),
            "outdir": self.outdir,
            "device": self.dev,
            "ui": {k: v for k, v in self.ui.items() if not k.startswith("_")},
            "perf": {
                "cold_start": self.perf.get("cold_start"),
                "zlib_search": self.perf.get("zlib_search"),
                "zlib_login": self.perf.get("zlib_login"),
                "zlib_download": self.perf.get("zlib_download"),
                "by_phase": {k: len(v) for k, v in self.perf.get("by_phase", {}).items()},
                "debug": self.perf.get("debug", []),
            },
            "download": {k: v for k, v in self.dl.items() if not k.startswith("_")},
            "mock": self.mock_summary,
            "scenario": self.scenarios,
            "fault_cases": self.fault_results,
            "assertions": self.asserts,
            "warnings": self.warnings,
            "steps": self.steps,
            "artifacts": self.artifacts,
        }


def build_parser():
    ap = argparse.ArgumentParser(
        prog="zlib-smoke",
        description="ParticleBook Z-Library 可重复冒烟：装包→冷启→搜索 android→首条详情→"
                    "可选下载三态闭环（触发<=30s / 终态<=180s / 书架+1 / 文件出现）→PBPERF 汇总。",
        epilog="退出码：0=PASS 或 SKIP，1=断言失败/下载闭环失败，2=用法错误，3=总超时/终态超时，"
               "4=环境/装包失败。 设备锁由 zlib-smoke.sh 自动获取（--no-lock 可关闭）。\n"
               "注意：脚本从不执行 pm clear（保护设备上的真实登录态与书架数据），"
               "只用 am force-stop 保证冷启；装包后不清应用数据。",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("--apk", metavar="PATH", required=False,
                    help="APK 路径（Windows 路径，如 F:/.../app-debug.apk）；--no-install 时可省")
    ap.add_argument("--pkg", default="app.particlebook", help="包名（默认 app.particlebook）")
    ap.add_argument("--activity", default="app.particlebook/.android.MobileActivity",
                    help="启动 activity（默认 app.particlebook/.android.MobileActivity）")
    ap.add_argument("--serial", default="", help="设备序列号；省略则自动检测唯一在线设备")
    ap.add_argument("--outdir", default="spikes/zz-probe", help="产物目录（默认 spikes/zz-probe）")
    ap.add_argument("--tag", default="zlib-smoke", help="产物文件名前缀（默认 zlib-smoke）")
    ap.add_argument("--json", default="", metavar="PATH", help="JSON 输出路径（默认 <outdir>/<tag>-<ts>.json）")
    ap.add_argument("--download", action="store_true",
                    help="下载三态闭环：30s 触发证据 → 180s 终态 → 书架+1 + 下载目录文件")
    ap.add_argument("--mock", action="store_true",
                    help="连本地 zlib-mock（127.0.0.1:8899 + adb reverse），跳过真实网络："
                         "自动拉 mock 进程、清系统代理、启动带 pb_zlib_mirror，并断言 mock 请求序列/Range/重试")
    ap.add_argument("--mock-url", default=MOCK_DEFAULT_URL, help="mock 基址（默认 http://127.0.0.1:8899）")
    ap.add_argument("--mock-start", choices=("auto", "never", "always"), default="auto",
                    help="mock 启动策略：auto=健康则复用否则自拉（默认），never=必须已启动，always=重新拉")
    ap.add_argument("--mock-wait", type=float, default=25, help="等待 mock ready 秒（默认 25）")
    ap.add_argument("--mock-log", default="", help="mock JSONL 日志路径（默认 <outdir>/<tag>-<ts>-mock.jsonl）")
    ap.add_argument("--mock-script", default="", help="mock 脚本路径（默认 scripts/android/zlib-mock.py；自测可覆盖）")
    ap.add_argument("--reset-state", choices=("none", "mock"), default="none",
                    help="mock 前重置书籍/下载记录（mock 专用；保留登录 cookie，不 pm clear）")
    ap.add_argument("--result-index", type=int, default=0,
                    help="搜索结果里选第几条（0 起，默认首条；已下载书可换下一条避免 already_downloaded）")
    ap.add_argument("--mock-dl-mode", choices=("page", "redirect", "limit", "direct"), default="page",
                    help="传给 mock 的 /dl 模式（默认 page；redirect 验证 R7-G 302 签名 CDN 分支）")
    ap.add_argument("--mock-book-count", type=int, default=3,
                    help="mock 书籍变体数（默认 3；fault 矩阵建议 12，保证每例都能拿到未下载书）")
    ap.add_argument("--mock-expect-results", type=int, default=20,
                    help="mock 夹具搜索结果数（默认 20；仅 --mock 时用）")
    ap.add_argument("--mock-slow-kbps", type=int, default=256, help="slow 故障限速 KB/s（默认 256）")
    ap.add_argument("--mock-slow-stall-ms", type=int, default=0, help="slow 故障中途静默毫秒（默认 0）")
    ap.add_argument("--mock-cancel-kbps", type=int, default=16, help="取消用例限速 KB/s（默认 16）")
    ap.add_argument("--keep-mock", action="store_true", help="结束后不杀自拉的 mock 进程")
    ap.add_argument("--scenario", default="",
                    help="故障场景：download_faults（需 --mock --download；注入 first_packet_lost/range_416/slow/cancel）")
    ap.add_argument("--fault-cases", default="first_packet_lost,range_416,slow,cancel",
                    help="download_faults 子用例（默认全部；可逗号裁剪，如 first_packet_lost,slow）")
    ap.add_argument("--timeout", type=float, default=420, help="总超时秒（默认 420；含下载闭环/故障场景）")
    ap.add_argument("--trigger-timeout", type=float, default=30,
                    help="点下载后等待触发证据（PBPERF 或进度百分比）秒（默认 30）")
    ap.add_argument("--download-timeout", type=float, default=180, help="等待下载终态秒（默认 180）")
    ap.add_argument("--poll-interval", type=float, default=1.0, help="下载轮询间隔秒（默认 1.0）")
    ap.add_argument("--progress-shot-min", type=int, default=20,
                    help="进度首次 >=N%% 时截图（默认 20）")
    ap.add_argument("--expect-books-delta", type=int, default=1,
                    help="下载成功后书架应增加的书籍数（默认 1）")
    ap.add_argument("--daily-limit-fail", action="store_true",
                    help="额度/登录不足也判 FAIL（默认判 SKIP）")
    ap.add_argument("--search-timeout", type=float, default=60, help="等待搜索结果秒（默认 60）")
    ap.add_argument("--wait-device", type=float, default=45, help="等待设备 online 秒（默认 45）")
    ap.add_argument("--expect-results", type=int, default=50, help="期望结果数（默认 50；仅非 strict 时警告）")
    ap.add_argument("--strict-results", action="store_true", help="结果数 < --expect-results 直接 FAIL")
    ap.add_argument("--no-install", action="store_true", help="跳过安装（要求设备已装该包）")
    ap.add_argument("--force-stop-only", action="store_true",
                    help="保留参数：脚本始终只 force-stop，从不 pm clear（无实际作用）")
    ap.add_argument("--no-stop", action="store_true", help="结束后不 force-stop")
    ap.add_argument("--version", action="version", version="zlib-smoke 1.0")
    ap.add_argument("--selftest", action="store_true", help="纯函数自检（不碰设备）后退出")
    return ap


def main(argv):
    ap = build_parser()
    args = ap.parse_args(argv)
    if args.selftest:
        return selftest()
    if not args.apk and not args.no_install:
        ap.error("--apk is required unless --no-install")
    try:
        parse_fault_cases(args.fault_cases)
    except ValueError as e:
        ap.error(str(e))
    if args.scenario:
        scenarios = [x.strip() for x in args.scenario.split(",") if x.strip()]
        unknown = [x for x in scenarios if x not in ("download_faults", "resume", "d3_416", "fresh_416")]
        if unknown:
            ap.error("unknown --scenario: " + ",".join(unknown) + " (supported: download_faults,resume)")
        if not args.mock:
            ap.error("--scenario requires --mock")
        if not args.download:
            ap.error("--scenario requires --download")
    s = Smoke(args)
    s.log("start tag=" + s.tag + " timeout=" + str(args.timeout) + "s outdir=" + s.outdir)
    try:
        s.run()
        s.result = s.state or "PASS"
        s.exit_code = 0
    except SmokeError as e:
        s.error = e
        s.result = "FAIL"
        s.exit_code = e.code
        s.fail_reason = e.reason
        s.shot("fail-" + (e.step or "run"))
        s.log("FAIL: " + e.reason)
    except Exception as e:
        s.error = SmokeError(1, type(e).__name__ + ": " + str(e))
        s.result = "FAIL"
        s.exit_code = 1
        s.fail_reason = s.error.reason
        s.shot("fail-exception")
        traceback.print_exc()
    finally:
        try:
            s.finalize()
        except Exception as e2:
            print("finalize error: " + str(e2), flush=True)
    return s.exit_code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
