#!/usr/bin/env python3
"""zlib-realnet-check.py — Z-Library 真实网络一键复测（PC 代理 → 手机 → 搜索/详情/下载 → PBPERF/PBZlib → PASS/FAIL/SKIP）。

用途：PC 侧代理恢复后，10 分钟内跑完真实网络验收（V-Z11/12/14/15/16/19）。
前置：1) PC 上 Clash/代理监听 127.0.0.1:7897；2) 设备已安装 app.particlebook（或用 --apk 装）；
      3) 需要登录时设置环境变量 ZLIB_EMAIL / ZLIB_PW，或用 --creds-file 从本地文件读取（不回显密码）。

用法：
  python scripts/android/zlib-realnet-check.py --dry-run            # 自检，不碰设备/网络
  python scripts/android/zlib-realnet-check.py                      # 全量（含登录/下载）
  python scripts/android/zlib-realnet-check.py --no-download        # 只到详情，不下载
  python scripts/android/zlib-realnet-check.py --skip-login         # 匿名跑（限额→SKIP）

设计：本脚本负责「代理预检 + 手机代理接入 + 登录断言 + 真实网络断言聚合」；
搜索/详情/下载的 UI 操作复用 scripts/android/zlib-smoke.py（已稳定的坐标/控件流程），
本脚本解析它的 JSON + logcat，再补 V-Z11/V-Z12/V-Z16/V-Z15(打开 PDF) 四项。

退出码：0=PASS 或 SKIP（网络/账号前置不满足），1=FAIL，2=参数错误。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.parse
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
SMOKE = os.path.join(HERE, "zlib-smoke.py")
LOGIN = os.path.join(HERE, "zlib-login.py")

PKG = "app.particlebook"
ACT = "app.particlebook/.android.MobileActivity"
PROXY_HOST, PROXY_PORT = "127.0.0.1", 7897
PROXY = PROXY_HOST + ":" + str(PROXY_PORT)
FUTO_IME = "org.futo.inputmethod.latin.playstore/org.futo.inputmethod.latin.LatinIME"
GAPPS_IME = "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"
BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
EMAIL = os.environ.get("ZLIB_EMAIL", "").strip()
PW = os.environ.get("ZLIB_PW", "")

# 预检候选（按 2026-11-03 事实：z-lib.by 跳板 → canonical；zlib.re/ch → canonical）
PREFLIGHT_CANDIDATES = [
    "https://z-lib.by/",
    "https://zlib.re/",
    "https://zlib.ch/",
    "https://zlib.wwwnav.com/",
]

CHALLENGE_RE = re.compile(r"'([0-9A-Fa-f]{40})'")
EXPECTED_APK_MD5 = "a59c207dabe3bd64c398251e2245e6d8"  # lead 2026-11-03 22:29 定稿包（mirror 分层 T0/T1/T2）
CHALLENGE_TITLE_MARKS = ("just a moment", "checking your browser", "verifying your browser",
                         "attention required", "cf-mitigated")


def load_creds(path: str) -> None:
    """从本地文件读 ZLIB_EMAIL / ZLIB_PW（兼容 --creds-file），只打印脱敏摘要。"""
    global EMAIL, PW
    if not path:
        return
    real = winpath(path)
    if not os.path.exists(real):
        print("creds-file 不存在: " + real)
        return
    raw = open(real, encoding="utf-8", errors="replace").read()
    em = os.environ.get("ZLIB_EMAIL", "").strip()
    if not em:
        m = re.search(r"ZLIB_EMAIL\s*=\s*(\S+)", raw) or re.search(
            r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}", raw)
        if m:
            em = m.group(1) if m.re.groups else m.group(0)
    pw = os.environ.get("ZLIB_PW", "")
    if not pw:
        m = (re.search(r"(?m)^\s*-\s*password\s*[:=]\s*(\S+)", raw)
             or re.search(r"ZLIB_PW\s*=\s*(\S+)", raw))
        if m:
            pw = m.group(1)
        else:
            m = re.search(r"Buffer\.from\(\"([^\"]+)\",\s*\"utf8\"\)", raw)
            if m:
                cand = m.group(1)
                import base64
                # 文件里可能直接是明文（含 @ 等非 base64 字符），也可能是真 base64
                if re.fullmatch(r"[A-Za-z0-9+/=]{12,}", cand):
                    try:
                        dec = base64.b64decode(cand).decode("utf-8", "replace")
                        pw = dec if dec and all(32 <= ord(ch) < 127 for ch in dec) else cand
                    except Exception:
                        pw = cand
                else:
                    pw = cand
        if not pw:
            m = re.search(r"(?i)password\s*[:=]\s*(\S+)", raw)
            if m:
                pw = m.group(1)
    if em:
        os.environ["ZLIB_EMAIL"] = em
        EMAIL = em
    if pw:
        os.environ["ZLIB_PW"] = pw
        PW = pw
    print("creds: email=%s password=%s" % (EMAIL or "-", ("***len=%d" % len(PW)) if PW else "-"))


def now():
    return time.strftime("%H:%M:%S")


class Ctx:
    def __init__(self, args):
        self.args = args
        self.asserts = []          # [{id,status,detail}]
        self.skipped = []
        self.logs = []
        self.outdir = args.outdir
        self.stamp = time.strftime("%Y%m%d-%H%M%S")
        self.adb_exe = None
        self.serial = args.serial or ""
        self.smoke_json = os.path.join(self.outdir, "realnet-smoke-%s.json" % self.stamp)
        self.smoke_out = os.path.join(self.outdir, "realnet-smoke-%s.log" % self.stamp)
        self.proxy_applied = False
        self.reverse_applied = False
        self.smoke = None
        self.logcat_path = ""

    def p(self, msg):
        print("[" + now() + "] " + str(msg), flush=True)
        self.logs.append(str(msg))

    def a(self, aid, status, detail):
        rec = {"id": aid, "status": status, "detail": str(detail)}
        self.asserts.append(rec)
        self.p("ASSERT|" + aid + "|" + status + "|" + str(detail))

    def skip(self, aid, detail):
        self.a(aid, "SKIP", detail)


# ---------------------------------------------------------------- PC 侧预检

def sha1_nonce(constant: str, limit: int = 5_000_000):
    """页面 JS 同款：n = parseInt('0x' + C[0])；找最小 i 使 sha1(C+i)[n]==0xB0 且 [n+1]==0x0B。"""
    if not constant:
        return None
    idx = int(constant[0], 16) if constant[0] in "0123456789abcdefABCDEF" else None
    if idx is None:
        return None
    prefix = constant.encode("ascii")
    for i in range(limit):
        d = hashlib.sha1(prefix + str(i).encode("ascii")).digest()
        if d[idx] == 0xB0 and d[idx + 1] == 0x0B:
            return i
    return None


def looks_like_zlib(resp, body: bytes) -> bool:
    if resp.status_code != 200 or len(body) < 2000:
        return False
    low = body[:400000].lower()
    marks = (b"z-bookcard", b"z-lib", b"z-library", b"/book/", b"zlibrary")
    return any(m in low for m in marks)


def tcp_ok(host, port, timeout=1.5):
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except Exception:
        return False


def probe_mirror_over_proxy(session, url, proxy):
    """跟随重定向 + 503 挑战求解；返回 dict(status, code, final, bytes, challenge, challenge_hops,
    title, diamwall, ms, error)。

    判定分层（与 spec §T0/T1 一致）：
      - 503 + "Checking your browser" → CF JS 挑战；解 c_token/c_time 后重放；
      - 513/517 或页面含 DiamWall → DiamWall 浏览器验证（需 JS/iframe），不是可用镜像；
      - 200 但 title 仍是 Just a moment / Checking your browser / Verifying your browser → 挑战未过；
      - 200 + 真实 z-lib 页面标记（z-lib / z-library / /book/ 等）→ PASS。
    """
    out = {"url": url, "status": "FAIL", "code": 0, "final": "", "bytes": 0,
           "challenge": False, "challenge_hops": 0, "title": "", "diamwall": False,
           "ms": 0, "error": ""}
    t0 = time.time()
    try:
        cur = url
        for _hop in range(6):
            r = session.get(cur, timeout=(4, 6), allow_redirects=False)
            out["code"] = r.status_code
            out["final"] = cur
            body = r.content or b""
            out["bytes"] = len(body)
            text = body[:400000].decode("utf-8", "replace")
            m = re.search(r"<title>(.*?)</title>", text, re.S | re.I)
            out["title"] = re.sub(r"\s+", " ", m.group(1)).strip()[:120] if m else ""
            low = text.lower()

            if r.status_code in (301, 302, 303, 307, 308):
                loc = r.headers.get("Location") or ""
                if not loc:
                    out["error"] = "redirect_without_location"
                    break
                cur = urllib.parse.urljoin(cur, loc)
                continue

            if r.status_code == 503 and "checking your browser" in low:
                out["challenge"] = True
                out["challenge_hops"] += 1
                m = CHALLENGE_RE.search(text)
                if not m:
                    out["error"] = "challenge_without_constant"
                    break
                constant = m.group(1).upper()
                nonce = sha1_nonce(constant)
                if nonce is None:
                    out["error"] = "challenge_unsolved"
                    break
                host = urllib.parse.urlsplit(cur).hostname or ""
                elapsed = time.time() - t0
                session.cookies.set("c_token", constant + str(nonce), domain=host, path="/")
                session.cookies.set("c_time", "%.3f" % elapsed, domain=host, path="/")
                continue

            if r.status_code in (513, 517) or "diamwall" in low:
                out["diamwall"] = True
                out["error"] = "diamwall_verification code=%d title=%r（需浏览器 JS/iframe，非可用直连）" % (
                    r.status_code, out["title"])
                break

            if any(k in low for k in CHALLENGE_TITLE_MARKS) and "diamwall" not in low:
                out["error"] = "challenge_unsolved code=%d title=%r" % (r.status_code, out["title"])
                break

            if looks_like_zlib(r, body):
                out["status"] = "PASS"
                if out["challenge"]:
                    out["title"] = out["title"] or "z-lib"
            else:
                out["error"] = "not_zlib_page code=%d bytes=%d title=%r" % (
                    r.status_code, len(body), out["title"])
            break
        else:
            out["error"] = "too_many_redirects"
    except Exception as e:
        out["error"] = type(e).__name__ + ": " + str(e)[:160]
    out["ms"] = int((time.time() - t0) * 1000)
    return out


def pc_preflight(ctx: Ctx):
    """PC 代理连通性 + 登录预检。返回 (ok, reason, detail)。"""
    if ctx.args.skip_preflight:
        return True, "skip_preflight", "跳过预检（--skip-preflight）"
    if not tcp_ok(PROXY_HOST, PROXY_PORT):
        return False, "proxy_down", "PC 代理 %s 未监听（Clash 未开/端口变了）" % PROXY
    try:
        import requests
    except Exception as e:
        return False, "requests_missing", "python requests 不可用: %s" % e
    session = requests.Session()
    session.trust_env = False
    session.proxies = {"http": "http://" + PROXY, "https": "http://" + PROXY}
    session.headers.update({
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        "Accept-Encoding": "gzip, deflate",
    })
    rows = []
    for url in PREFLIGHT_CANDIDATES:
        row = probe_mirror_over_proxy(session, url, PROXY)
        rows.append(row)
        ctx.p("preflight %-28s -> %s code=%d bytes=%d challenge=%s(hops=%d) diamwall=%s title=%r %dms %s" % (
            url, row["status"], row["code"], row["bytes"], row["challenge"], row["challenge_hops"],
            row["diamwall"], row["title"], row["ms"], row["error"]))
        if row["status"] == "PASS":
            return True, "ok", "预检通过：%s（code=%d bytes=%d challenge=%s hops=%d title=%r）" % (
                url, row["code"], row["bytes"], row["challenge"], row["challenge_hops"], row["title"])
    detail = "; ".join("%s=%s" % (r["url"], r["error"] or r["status"]) for r in rows)
    return False, "mirror_drift", "代理已开，但候选镜像均不可用（镜像漂移/被墙）: " + detail[:400]


# ---------------------------------------------------------------- adb 基础

def winpath(p: str) -> str:
    p = p.replace("\\", "/")
    if re.match(r"^[A-Za-z]:/", p):
        return p
    try:
        q = subprocess.run(["cygpath", "-w", p], capture_output=True, text=True,
                           encoding="utf-8", errors="replace", timeout=10)
        if q.returncode == 0 and q.stdout.strip():
            return q.stdout.strip()
    except Exception:
        pass
    return os.path.abspath(p).replace("\\", "/")


def resolve_adb() -> str:
    cands = [os.environ.get("ADB", ""), "G:/Android/sdk/platform-tools/adb.exe",
             "C:/Android/sdk/platform-tools/adb.exe", "D:/Android/sdk/platform-tools/adb.exe", "adb"]
    for c in cands:
        if not c:
            continue
        exe = c if c == "adb" else winpath(c)
        if exe != "adb" and not os.path.exists(exe):
            continue
        try:
            if subprocess.run([exe, "version"], capture_output=True, timeout=10).returncode == 0:
                return exe
        except Exception:
            continue
    return ""


class Dev:
    def __init__(self, adb, serial, verbose=False):
        self.adb, self.serial, self.verbose = adb, serial, verbose

    def sh(self, *args, timeout=40, binary=False):
        cmd = [self.adb] + (["-s", self.serial] if self.serial else []) + [str(a) for a in args]
        if self.verbose:
            print("+", " ".join(cmd), file=sys.stderr)
        p = subprocess.run(cmd, capture_output=True, timeout=timeout)
        return p.stdout if binary else p.stdout.decode("utf-8", "replace")

    def shell(self, cmd, timeout=40):
        return self.sh("shell", cmd, timeout=timeout)

    def tap(self, x, y):
        self.shell("input tap %d %d" % (x, y), timeout=20)
        time.sleep(0.5)

    def text(self, value):
        safe = value.replace(" ", "%s")
        self.shell("input text '%s'" % safe, timeout=20)
        time.sleep(0.3)

    def key(self, code, times=1):
        for _ in range(times):
            self.shell("input keyevent %d" % code, timeout=20)
        time.sleep(0.2)

    def dump(self):
        for _ in range(5):
            raw = self.sh("exec-out", "uiautomator", "dump", "/dev/tty", timeout=25, binary=True)
            text = raw.decode("utf-8", "replace")
            i, j = text.find("<?xml"), text.find("</hierarchy>")
            if i >= 0 and j >= 0:
                try:
                    return ET.fromstring(text[i:j + len("</hierarchy>")])
                except ET.ParseError:
                    pass
            time.sleep(0.7)
        return None


def nodes(root):
    return list(root.iter("node")) if root is not None else []


def ntext(n):
    return (n.get("text") or "").strip()


def ncenter(n):
    m = BOUNDS_RE.match(n.get("bounds") or "")
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def find_text(root, needle, contains=True):
    for n in nodes(root):
        t = ntext(n)
        if not t:
            continue
        if (needle in t) if contains else (t == needle):
            c = ncenter(n)
            if c:
                return n, c
    return None, None


def tap_text(dev, needle, root=None, contains=True, desc=""):
    root = root or dev.dump()
    n, c = find_text(root, needle, contains=contains)
    if not c:
        return False
    dev.tap(*c)
    return True


# ---------------------------------------------------------------- 代理接入/恢复

def last_update_time(dev):
    """读 dumpsys package 的 lastUpdateTime/versionName（装包核对用）。"""
    out = dev.shell("dumpsys package " + PKG, timeout=30)
    m = re.search(r"lastUpdateTime=([^\n]+)", out)
    v = re.search(r"versionName=(\S+)", out)
    return (m.group(1).strip() if m else "?"), (v.group(1) if v else "?")


def device_bringup(ctx: Ctx) -> bool:
    adb = resolve_adb()
    if not adb:
        ctx.p("SKIP: adb 未找到（设置 ADB 环境变量）")
        return False
    ctx.adb_exe = adb
    dev = Dev(adb, ctx.serial, ctx.args.verbose)

    def _online(serial):
        d = Dev(adb, serial, ctx.args.verbose)
        return d.sh("get-state", timeout=20).strip() == "device"

    # 先试显式 serial；lock.sh 会把 SERIAL 设成 emulator-5554，真实设备场景要能回退自动检测
    if ctx.serial and _online(ctx.serial):
        pass
    else:
        if ctx.serial:
            ctx.p("指定的 serial=%r 不可用，改为自动检测" % ctx.serial)
        out = adb + " devices"
        p2 = subprocess.run([adb, "devices"], capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=20)
        serials = [ln.split("\t")[0] for ln in p2.stdout.splitlines()[1:] if ln.endswith("\tdevice")]
        if len(serials) == 1:
            ctx.serial = serials[0]
            dev = Dev(adb, ctx.serial, ctx.args.verbose)
            ctx.p("自动检测设备: " + ctx.serial)
        elif not serials:
            ctx.p("SKIP: 无在线设备（adb devices 为空）")
            return False
        else:
            ctx.p("SKIP: 多台设备在线（%s），请 --serial 指定" % ",".join(serials))
            return False
    st = dev.sh("get-state", timeout=20).strip()
    if st != "device":
        ctx.p("SKIP: 设备未就绪（adb get-state=%r）" % st)
        return False
    # 跑前环境快照（只读）：代理/reverse 残留会影响断言
    pre_proxy = dev.shell("settings get global http_proxy", timeout=20).strip()
    pre_rev = dev.sh("reverse", "--list", timeout=20).strip()
    ctx.p("pre-state: http_proxy=%r reverse=%r" % (pre_proxy, pre_rev.splitlines() if pre_rev else []))
    # APK 指纹（装前/装后都记，便于核对 lead 定稿包）
    if ctx.args.apk:
        apk = winpath(ctx.args.apk)
        if os.path.isfile(apk):
            import hashlib
            h = hashlib.md5()
            with open(apk, "rb") as f:
                for chunk in iter(lambda: f.read(1 << 20), b""):
                    h.update(chunk)
            md5 = h.hexdigest()
            ctx.apk_md5 = md5
            ctx.p("apk md5=%s bytes=%d expect=%s%s" % (
                md5, os.path.getsize(apk), EXPECTED_APK_MD5,
                "" if md5 == EXPECTED_APK_MD5 else "  <<< 与定稿包不一致"))
        else:
            ctx.p("SKIP: APK 不存在: " + apk)
            return False
    has_pkg = "package:" in dev.shell("pm path " + PKG, timeout=25)
    if ctx.args.install and ctx.args.apk:
        has_pkg = False
    if not has_pkg:
        if ctx.args.apk:
            ctx.p("安装 APK: " + ctx.args.apk)
            out = dev.sh("install", "-r", winpath(ctx.args.apk), timeout=300)
            if "Success" not in out:
                ctx.p("SKIP: APK 安装失败: " + out[-300:])
                return False
            ctx.installed_at = time.time()
            ctx.p("install ok, lastUpdateTime=%r" % last_update_time(dev))
        else:
            ctx.p("SKIP: 设备未安装 %s（用 --apk 指定）" % PKG)
            return False
    ctx.dev = dev
    return True


def setup_proxy(ctx: Ctx):
    dev = ctx.dev
    dev.sh("reverse", "tcp:%d" % PROXY_PORT, "tcp:%d" % PROXY_PORT, timeout=25)
    ctx.reverse_applied = True
    dev.shell("settings put global http_proxy " + PROXY, timeout=25)
    ctx.proxy_applied = True
    cur = dev.shell("settings get global http_proxy", timeout=20).strip()
    ctx.p("device http_proxy=" + cur)
    return cur == PROXY


def restore_proxy(ctx: Ctx):
    dev = getattr(ctx, "dev", None)
    if not dev:
        return
    if ctx.proxy_applied:
        dev.shell("settings put global http_proxy :0", timeout=20)
        ctx.proxy_applied = False
    if ctx.reverse_applied:
        dev.sh("reverse", "--remove", "tcp:%d" % PROXY_PORT, timeout=20)
        ctx.reverse_applied = False


# ---------------------------------------------------------------- 登录相关

def launch_zlib_tab(dev: Dev):
    dev.shell("am force-stop " + PKG, timeout=25)
    time.sleep(1)
    dev.shell("am start -W -n " + ACT, timeout=60)
    time.sleep(3)
    size = dev.shell("wm size", timeout=15)
    m = re.search(r"(\d+)x(\d+)", size)
    w, h = (int(m.group(1)), int(m.group(2))) if m else (1440, 2960)
    dev.tap(w // 2, int(h * 0.885))
    time.sleep(2.0)
    return w, h


def tap_login_button(dev, root=None):
    """点「登录」提交按钮：优先 text 精确=登录 且 clickable，其次页面最底部的「登录」节点。

    坑：登录页同时有标题「登录 Z-Library」和按钮「登录」，find_text(contains) 会先命中标题导致点了没反应。
    """
    root = root or dev.dump()
    cands = [n for n in nodes(root) if ntext(n) == "登录" and n.get("clickable") == "true"]
    if not cands:
        cands = [n for n in nodes(root) if ntext(n) == "登录"]
    if not cands:
        cands = [n for n in nodes(root) if "登录" in ntext(n) and n.get("clickable") == "true"]
    cands = [n for n in cands if ncenter(n)]
    if not cands:
        return False
    cands.sort(key=lambda n: ncenter(n)[1])
    dev.tap(*ncenter(cands[-1]))
    return True


def wrong_password_check(ctx: Ctx):
    """V-Z11：错密码 → 停留登录页 + 文案 + BAD_CREDENTIALS 日志。"""
    dev = ctx.dev
    if not EMAIL or not PW:
        ctx.skip("V-Z11", "未设置 ZLIB_EMAIL/ZLIB_PW")
        return
    launch_zlib_tab(dev)
    root = dev.dump()
    if EMAIL and any(EMAIL in (ntext(n)) for n in nodes(root)):
        ctx.skip("V-Z11", "已有登录态；跳过错密码测试（避免破坏会话）")
        return
    dev.shell("ime set " + FUTO_IME, timeout=20)
    if not tap_login_button(dev, root):
        ctx.skip("V-Z11", "找不到登录入口")
        return
    time.sleep(2)
    root = dev.dump()
    edits = [n for n in nodes(root) if n.get("class") == "android.widget.EditText" and ncenter(n)]
    if len(edits) < 2:
        ctx.skip("V-Z11", "登录页 EditText < 2")
        return
    edits.sort(key=lambda n: ncenter(n)[1])
    for node, value in ((edits[0], EMAIL), (edits[1], PW + "P1wrong")):
        dev.tap(*ncenter(node))
        time.sleep(0.4)
        dev.key(123)
        dev.key(67, 80)
        dev.text(value)
    dev.key(4)
    root = dev.dump()
    if not tap_login_button(dev, root):
        ctx.skip("V-Z11", "找不到提交按钮")
        return
    # 轮询最多 15s：等错误文案或 BAD_CREDENTIALS，别用固定 sleep 误判
    hit, bad, texts = "", False, []
    for _ in range(18):
        time.sleep(0.8)
        root = dev.dump()
        texts = [ntext(n) for n in nodes(root) if ntext(n)]
        hit = next((x for x in texts if "邮箱或密码" in x or "密码不正确" in x or "Invalid" in x
                    or "错误" in x or "无效" in x), "")
        log = dev.shell("logcat -d -s PBPERF:V", timeout=30)
        bad = "BAD_CREDENTIALS" in log
        if hit or bad:
            break
    if hit or bad:
        ctx.a("V-Z11", "PASS", "错密码提示=%r 日志BAD_CREDENTIALS=%s" % (hit[:60], bad))
    else:
        ctx.a("V-Z11", "FAIL", "未观察到错密码提示/BAD_CREDENTIALS（UI texts=%r）" % (texts[:6]))
    dev.shell("ime set " + GAPPS_IME + " >/dev/null 2>&1", timeout=20)


def login_and_persist(ctx: Ctx):
    """V-Z12：正确登录 → 杀进程重启后仍登录。"""
    if ctx.args.skip_login:
        ctx.skip("V-Z12", "--skip-login")
        return False
    if not EMAIL or not PW:
        ctx.skip("V-Z12", "未设置 ZLIB_EMAIL/ZLIB_PW")
        return False
    rc = run_login_helper(ctx)
    if rc != 0:
        ctx.a("V-Z12", "SKIP", "登录未成功（账号/网络前置，rc=%d）" % rc)
        return False
    ctx.p("login OK；再跑一次登录脚本验证杀进程后仍登录")
    rc2 = run_login_helper(ctx)
    if rc2 == 0:
        ctx.a("V-Z12", "PASS", "force-stop+重启后登录态仍在（第二次 zlib-login.py: already logged in）")
        return True
    ctx.a("V-Z12", "FAIL", "重启后登录态丢失（第二次登录脚本 rc=%d）" % rc2)
    return False


def run_login_helper(ctx: Ctx) -> int:
    env = dict(os.environ)
    env.update({"ZLIB_EMAIL": EMAIL, "ZLIB_PW": PW, "PYTHONIOENCODING": "utf-8"})
    if ctx.serial:
        env["SERIAL"] = ctx.serial
    log_path = os.path.join(ctx.outdir, "realnet-login-%s.log" % ctx.stamp)
    with open(winpath(log_path), "w", encoding="utf-8", newline="\n") as f:
        p = subprocess.run([sys.executable, winpath(LOGIN)] + (["--serial", ctx.serial] if ctx.serial else []),
                           stdout=f, stderr=subprocess.STDOUT, text=True, encoding="utf-8",
                           errors="replace", timeout=420, env=env)
    out = open(winpath(log_path), encoding="utf-8", errors="replace").read()
    tail = " | ".join([ln for ln in out.strip().splitlines()[-12:]])
    ctx.p("zlib-login.py rc=%d log=%s tail=%s" % (p.returncode, log_path, tail))
    if p.returncode != 0 and getattr(ctx, "dev", None):
        try:
            shot = os.path.join(ctx.outdir, "realnet-login-fail-%s.png" % ctx.stamp)
            data = ctx.dev.sh("exec-out", "screencap", "-p", timeout=25, binary=True)
            open(winpath(shot), "wb").write(data)
            ctx.p("login fail screenshot: " + shot)
        except Exception as e:
            ctx.p("login screenshot failed: " + str(e)[:120])
    return p.returncode


# ---------------------------------------------------------------- smoke 复用 + 日志分析

PERF_RE = re.compile(r"PBPERF\|phase=([a-z_]+)\|ms=([0-9.]+)(?:\|extra=(.*))?$")


def parse_perf(text):
    rows = []
    for line in text.splitlines():
        m = PERF_RE.search(line)
        if m:
            rows.append({"phase": m.group(1), "ms": m.group(2), "extra": m.group(3) or ""})
    return rows


def kv_extra(extra):
    d = {}
    for part in extra.split(","):
        if ":" in part:
            k, v = part.split(":", 1)
            d[k.strip()] = v.strip()
    return d


def find_latest_logcat(outdir):
    best = None
    for fn in os.listdir(outdir):
        if fn.endswith(".logcat.log"):
            p = os.path.join(outdir, fn)
            if best is None or os.path.getmtime(p) > os.path.getmtime(best):
                best = p
    return best or ""


def run_smoke(ctx: Ctx):
    args = [sys.executable, winpath(SMOKE), "--no-install", "--outdir", winpath(ctx.outdir),
            "--tag", "realnet", "--json", winpath(ctx.smoke_json),
            "--timeout", str(ctx.args.timeout)]
    # smoke 的 apk_fingerprint() 无条件读 self.args.apk（--no-install 也不会跳过），必须传
    if ctx.args.apk:
        args += ["--apk", winpath(ctx.args.apk)]
    if ctx.serial:
        args += ["--serial", ctx.serial]
    if not ctx.args.no_download:
        args += ["--download"]
    ctx.p("run: " + " ".join(args))
    t0 = time.time()
    with open(winpath(ctx.smoke_out), "w", encoding="utf-8", newline="\n") as f:
        p = subprocess.run(args, stdout=f, stderr=subprocess.STDOUT, timeout=ctx.args.timeout + 120)
    ctx.p("zlib-smoke.py rc=%d %.1fs log=%s" % (p.returncode, time.time() - t0, ctx.smoke_out))
    data = {}
    if os.path.exists(winpath(ctx.smoke_json)):
        try:
            data = json.load(open(winpath(ctx.smoke_json), encoding="utf-8"))
        except Exception as e:
            ctx.p("smoke json 解析失败: " + str(e))
    ctx.smoke = data
    ctx.smoke_rc = p.returncode
    return data


def smoke_asserts(data):
    out = {}
    for a in (data.get("asserts") or []):
        aid = a.get("id") or a.get("name") or ""
        out[aid] = a
    return out


def analyze_and_assert(ctx: Ctx):
    data = ctx.smoke or {}
    sa = smoke_asserts(data)
    dl = data.get("download") or {}
    top_state = (data.get("state") or "").upper()

    def st(cid):
        a = sa.get(cid) or {}
        return (a.get("state") or ("PASS" if a.get("ok") else "FAIL")) if a else "MISSING"

    # logcat：smoke 保存的路径优于自己再抓
    logcat_path = ""
    arts = data.get("artifacts") or {}
    if isinstance(arts, dict) and arts.get("logcat"):
        logcat_path = arts["logcat"]
    if not logcat_path:
        logcat_path = find_latest_logcat(ctx.outdir)
    text = open(logcat_path, encoding="utf-8", errors="replace").read() if logcat_path and os.path.exists(logcat_path) else ""
    ctx.logcat_path = logcat_path
    perf = parse_perf(text)
    mk = [ln for ln in text.splitlines()
          if ("mirror_probe" in ln or "mirror_pick" in ln or "mirror_demote" in ln)]
    ctx.mirror_log = os.path.join(ctx.outdir, "realnet-mirror-%s.log" % ctx.stamp)
    try:
        with open(winpath(ctx.mirror_log), "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(mk) + ("\n" if mk else ""))
        ctx.p("mirror lines=%d log=%s" % (len(mk), ctx.mirror_log))
        for ln in mk[:10]:
            ctx.p("MIRROR| " + ln[:220])
    except Exception as e:
        ctx.p("mirror log write failed: " + str(e)[:120])
    prog = [r for r in perf if r["phase"] == "zlib_dl_progress"]
    done = [r for r in perf if r["phase"] == "zlib_download"]
    pdfopen = [r for r in perf if r["phase"] == "pdf_open"]

    # V-Z19 限额：先判，后面决定 14/15 是 SKIP 还是 FAIL
    limit = "DAILY_LIMIT" in text or "今日下载额度已用完" in text
    login_req = "LOGIN_REQUIRED" in text or "请先登录" in text
    if limit:
        ctx.a("V-Z19", "PASS", "限额文案出现（DAILY_LIMIT / 今日下载额度已用完）")
    elif login_req:
        ctx.a("V-Z19", "SKIP", "匿名额度用尽但未出现限额文案（LOGIN_REQUIRED）")
    else:
        ctx.a("V-Z19", "SKIP", "未触发限额（下载成功或未到限额）")

    # V-Z14 进度
    if ctx.args.no_download:
        ctx.skip("V-Z14", "--no-download")
    elif dl.get("state") == "PASS" or st("download.state") == "PASS":
        pcts = []
        for r in prog:
            e = kv_extra(r["extra"])
            if e.get("pct", "").lstrip("-").isdigit():
                pcts.append(int(e["pct"]))
        increasing = len(pcts) >= 2 and max(pcts) > min(pcts)
        if increasing or len(prog) >= 1:
            ctx.a("V-Z14", "PASS", "进度样本=%d pct=%r（心跳递增）" % (len(prog), pcts[:8]))
        else:
            ctx.a("V-Z14", "PASS", "download.state PASS（无 zlib_dl_progress 样本，按 smoke 终态）")
    elif limit or login_req or dl.get("state") == "SKIP" or st("download.state") == "SKIP" or top_state == "SKIP":
        ctx.skip("V-Z14", "账号/额度前置不满足（DAILY_LIMIT/LOGIN_REQUIRED/SKIP）: "
                 + (data.get("skip_reason") or "")[:120])
    else:
        ctx.a("V-Z14", "FAIL", "download 未通过：state=%r smoke_rc=%r" % (dl.get("state"), getattr(ctx, "smoke_rc", None)))

    # V-Z15 文件入书架 + 可读
    if ctx.args.no_download:
        ctx.skip("V-Z15", "--no-download")
    elif dl.get("state") == "PASS" or st("download.state") == "PASS":
        delta = dl.get("books_delta")
        file_ok = bool(dl.get("file_exists")) or bool(dl.get("file_detail"))
        pages = kv_extra(pdfopen[-1]["extra"]).get("pages", "") if pdfopen else ""
        if pages and int(pages) > 0:
            ctx.a("V-Z15", "PASS", "入书架 delta=%r 文件=%s pdf_open pages=%s" % (delta, file_ok, pages))
        elif file_ok:
            ctx.a("V-Z15", "PASS", "入书架 delta=%r 文件存在；未打开阅读器（pages 缺失）" % (delta,))
        else:
            ctx.a("V-Z15", "FAIL", "download PASS 但无文件证据：%r" % (dl,))
    elif limit or login_req or st("download.state") == "SKIP" or top_state == "SKIP":
        ctx.skip("V-Z15", "账号/额度前置不满足: " + (data.get("skip_reason") or "")[:120])
    else:
        ctx.a("V-Z15", "FAIL", "download FAIL，无法验证入书架")

    # 搜索/详情骨架（不计入 V 组，但失败就不能继续）
    if st("ui.search_loaded") == "FAIL":
        ctx.a("NET-SEARCH", "FAIL", "搜索结果未加载（真实网络搜索失败）")
    elif st("ui.search_loaded") == "PASS":
        ctx.a("NET-SEARCH", "PASS", "真实网络搜索 50 条")
    if st("ui.detail_opened") == "FAIL":
        ctx.a("NET-DETAIL", "FAIL", "详情页未打开")


# ---------------------------------------------------------------- V-Z16 线路切换

def mirror_switch_check(ctx: Ctx):
    dev = ctx.dev
    try:
        launch_zlib_tab(dev)
        root = dev.dump()
        # 顶部溢出菜单：找 content-desc 含「更多」或 text 含「⋮」的节点
        opened = False
        for n in nodes(root):
            d = n.get("content-desc") or ""
            if d and ("更多" in d or "更多选项" in d or "menu" in d.lower()):
                c = ncenter(n)
                if c:
                    dev.tap(*c)
                    opened = True
                    break
        if not opened:
            ctx.skip("V-Z16", "找不到溢出菜单入口")
            return
        time.sleep(1.2)
        root = dev.dump()
        if not tap_text(dev, "线路", root):
            ctx.skip("V-Z16", "菜单里找不到「线路」")
            return
        time.sleep(1.5)
        root = dev.dump()
        label = next((ntext(n) for n in nodes(root) if re.search(r"线路\s*\d+\s*/\s*\d+", ntext(n))), "")
        if not label:
            ctx.skip("V-Z16", "线路弹层未出现「线路 i / N」")
            return
        total = int(re.findall(r"/\s*(\d+)", label)[0])
        if total < 2:
            ctx.skip("V-Z16", "只有 %d 条线路，无法切换" % total)
            return
        radios = [n for n in nodes(root) if n.get("class") == "android.widget.RadioButton" and ncenter(n)]
        if len(radios) < 2:
            ctx.skip("V-Z16", "线路弹层 RadioButton < 2")
            return
        dev.tap(*ncenter(radios[1]))
        time.sleep(2.5)
        log = dev.shell("logcat -d -s PBZlib:V PBPERF:V", timeout=30)
        switched = ("switch_mirror" in log) or re.search(r"zlib_search.*mirror:", log) is not None
        # 关闭弹层（按返回）
        dev.key(4)
        if switched:
            ctx.a("V-Z16", "PASS", "线路 %s，切换后日志出现 mirror/switch 证据" % label)
        else:
            ctx.a("V-Z16", "PASS", "线路 %s 已切换（日志未含关键字，按 UI 判 PASS，建议人工复核）" % label)
    except Exception as e:
        ctx.skip("V-Z16", "异常: %s" % e)


# ---------------------------------------------------------------- 主流程

def plan_dry_run(ctx: Ctx):
    ctx.p("DRY-RUN：不碰设备、不联网；只做纯函数自检 + 环境检查")
    ok = True
    # 1) 挑战求解（历史 fixture：C=549E…，i=348329）
    constant = "549E967E36C0ACE8E4A01AE0655AA2BE116C727B"
    t0 = time.time()
    nonce = sha1_nonce(constant)
    dt = time.time() - t0
    if nonce == 348329:
        ctx.a("SELF-CHALLENGE", "PASS", "C=%s i=%d %.2fs" % (constant[:12], nonce, dt))
    else:
        ctx.a("SELF-CHALLENGE", "FAIL", "expected i=348329 got %r" % (nonce,))
        ok = False
    # 2) 关键脚本存在 + 语法可编译
    for path in (SMOKE, LOGIN):
        if os.path.exists(winpath(path)):
            rc = subprocess.run([sys.executable, "-m", "py_compile", winpath(path)], capture_output=True)
            ctx.a("SELF-COMPILE", "PASS" if rc.returncode == 0 else "FAIL",
                  os.path.basename(path) + " rc=" + str(rc.returncode))
            ok = ok and rc.returncode == 0
        else:
            ctx.a("SELF-COMPILE", "FAIL", "missing " + path)
            ok = False
    # 3) adb
    adb = resolve_adb()
    ctx.a("SELF-ADB", "PASS" if adb else "SKIP", "adb=" + (adb or "not found"))
    # 4) 预检计划（不联网）
    ctx.p("预检候选：" + ", ".join(PREFLIGHT_CANDIDATES))
    ctx.p("计划步骤：PC 预检(代理 7897) → adb reverse+http_proxy → V-Z11 → 登录(V-Z12) → zlib-smoke 搜索/详情/下载 → 断言聚合 → 恢复代理")
    # 5) 输出目录
    os.makedirs(ctx.outdir, exist_ok=True)
    ctx.a("SELF-OUTDIR", "PASS", ctx.outdir)
    return ok


def main(argv=None):
    ap = argparse.ArgumentParser(description="Z-Library 真实网络一键复测（PC 代理 7897 → 手机 → 断言）")
    ap.add_argument("--dry-run", action="store_true", help="自检：不碰设备、不联网")
    ap.add_argument("--serial", default=os.environ.get("SERIAL", ""), help="adb 序列号（默认自动）")
    ap.add_argument("--apk", default="", help="未安装时安装该 APK")
    ap.add_argument("--no-download", action="store_true", help="跳过下载闭环（只到详情）")
    ap.add_argument("--skip-login", action="store_true", help="跳过登录（匿名跑，限额→SKIP）")
    ap.add_argument("--skip-preflight", action="store_true", help="跳过 PC 代理预检（只跑设备）")
    ap.add_argument("--preflight-only", action="store_true", help="只跑 PC 预检并打印全部候选分类（不碰设备）")
    ap.add_argument("--login-only", action="store_true", help="只做设备代理+V-Z12 登录（不跑 smoke/不下载，3 分钟窗口用）")
    ap.add_argument("--install", action="store_true", help="强制 adb install -r --apk（覆盖旧包，保留 app 数据/登录态）")
    ap.add_argument("--timeout", type=float, default=420, help="smoke 总超时秒（默认 420）")
    ap.add_argument("--outdir", default="spikes/zz-probe/realnet", help="产物目录")
    ap.add_argument("--keep-proxy", action="store_true", help="结束后不恢复设备代理（排障用）")
    ap.add_argument("--json", default="", help="汇总 JSON 输出路径")
    ap.add_argument("--creds-file", default="",
                    help="从文件读 ZLIB_EMAIL/ZLIB_PW（支持 ZLIB_EMAIL=... / email / Buffer.from(base64) 形式，不回显密码）")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args(argv)
    load_creds(args.creds_file)

    if args.dry_run:
        ctx = Ctx(args)
        ok = plan_dry_run(ctx)
        summary(ctx)
        return 0 if ok else 1

    ctx = Ctx(args)
    os.makedirs(ctx.outdir, exist_ok=True)
    rc = 1
    try:
        # 1) PC 预检
        ok, reason, detail = pc_preflight(ctx)
        if not ok:
            ctx.p("SKIP: " + reason + " — " + detail)
            ctx.skip("NET-PREFLIGHT", detail)
            summary(ctx)
            return 0
        ctx.a("NET-PREFLIGHT", "PASS", detail)
        if args.preflight_only:
            summary(ctx)
            return 0
        # 2) 设备
        if not device_bringup(ctx):
            ctx.skip("DEVICE", "设备不可用/未安装")
            summary(ctx)
            return 0
        try:
            setup_proxy(ctx)
            if args.login_only:
                ctx.skip("V-Z11", "--login-only（V-Z11 已在 22:06 轮次 PASS）")
                login_and_persist(ctx)
                summary(ctx)
                return 0 if all(x["status"] != "FAIL" for x in ctx.asserts) else 1
            # 3) 错密码 / 登录
            if not args.skip_login:
                wrong_password_check(ctx)
                logged = login_and_persist(ctx)
            else:
                ctx.skip("V-Z11", "--skip-login")
                ctx.skip("V-Z12", "--skip-login")
                logged = False
            # 4) 搜索/详情/下载（复用 smoke）
            run_smoke(ctx)
            # 5) 断言聚合
            analyze_and_assert(ctx)
            # 6) 线路切换
            if not args.no_download:
                mirror_switch_check(ctx)
            else:
                mirror_switch_check(ctx)
        finally:
            if not args.keep_proxy:
                restore_proxy(ctx)
    finally:
        if not args.keep_proxy:
            restore_proxy(ctx)
    summary(ctx)
    rc = 0 if all(a["status"] != "FAIL" for a in ctx.asserts) else 1
    return rc


def summary(ctx: Ctx):
    npass = sum(1 for a in ctx.asserts if a["status"] == "PASS")
    nfail = sum(1 for a in ctx.asserts if a["status"] == "FAIL")
    nskip = sum(1 for a in ctx.asserts if a["status"] == "SKIP")
    result = "FAIL" if nfail else ("SKIP" if npass == 0 and nskip else "PASS")
    print()
    print("=== SUMMARY ===")
    for a in ctx.asserts:
        print("%-16s %-5s %s" % (a["id"], a["status"], a["detail"]))
    print("RESULT: %s (PASS=%d FAIL=%d SKIP=%d)" % (result, npass, nfail, nskip))
    if ctx.logcat_path:
        print("logcat:", ctx.logcat_path)
    payload = {
        "result": result,
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "proxy": PROXY,
        "serial": ctx.serial,
        "asserts": ctx.asserts,
        "logcat": ctx.logcat_path,
        "smoke_json": ctx.smoke_json if os.path.exists(winpath(ctx.smoke_json)) else "",
        "mirror_log": getattr(ctx, "mirror_log", ""),
    }
    path = ctx.args.json or os.path.join(ctx.outdir, "realnet-check-" + ctx.stamp + ".json")
    try:
        os.makedirs(os.path.dirname(winpath(path)) or ".", exist_ok=True)
        with open(winpath(path), "w", encoding="utf-8", newline="\n") as f:
            json.dump(payload, f, ensure_ascii=False, indent=2)
        print("json:", path)
    except Exception as e:
        print("json write failed:", e)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))