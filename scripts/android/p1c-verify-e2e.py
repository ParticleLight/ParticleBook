#!/usr/bin/env python3
"""P1c /dl 302 -> CDN -> 书架 独立 E2E 复核（mock 环境）。

设计目标（Lead 2026-11-03 派发）：
- 自己起 scripts/android/zlib-mock.py（--dl-mode redirect），不读 bench 的断言；
- adb reverse + am start --es pb_zlib_mirror；
- uiautomator dump 取元素 bounds 中心，驱动 搜索 -> 详情 -> 下载；
- 证据三件套：mock JSONL 的 /dl 与 /cdn 请求行、应用私有目录下载文件的 size+sha1、books.json 新增条目；
- 断言 fileBytes == mockBytes == books.fileSize 且 sha1 一致。

用法（宿主；设备独占时）：
    python scripts/android/p1c-verify-e2e.py --serial 8A9Y0G2XJ
    python scripts/android/p1c-verify-e2e.py --self-test   # 只验证 mock 部分，不碰设备

注意：
- 脚本会 adb install -r 当前 debug APK、pm clear app.particlebook、设置 http_proxy=:0；
  这些都会改变设备状态，运行前必须取得设备独占。
- 只用于离线 mock 环境；真实 CDN 未验证。
"""

from __future__ import annotations

import argparse
import atexit
import hashlib
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Iterable, Optional

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_APK = ROOT / "android" / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
DEFAULT_SERIAL = os.environ.get("P1C_SERIAL", "8A9Y0G2XJ")
EXPECTED_APK_MD5 = "783e34127af7f9c2d185bb0510b8ff10"
APP = "app.particlebook"
ACTIVITY = ".android.MobileActivity"
MOCK_SCRIPT = ROOT / "scripts" / "android" / "zlib-mock.py"
DEFAULT_RESULT_TITLE = "Android编程权威指南（中文版）"


class VerifyError(RuntimeError):
    pass


def now_stamp() -> str:
    return time.strftime("%Y%m%d-%H%M%S")


def log(msg: str) -> None:
    print("[verify] " + msg, flush=True)


def sha1_bytes(data: bytes) -> str:
    return hashlib.sha1(data).hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def md5_file(path: Path) -> str:
    h = hashlib.md5()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def resolve_adb() -> str:
    raw = os.environ.get("ADB", "").strip()
    if raw:
        return raw
    for candidate in (
        r"G:\Android\sdk\platform-tools\adb.exe",
        r"G:/Android/sdk/platform-tools/adb.exe",
    ):
        if Path(candidate).exists():
            return candidate
    found = shutil.which("adb")
    if found:
        return found
    raise VerifyError("找不到 adb；请设置 ADB 或把 adb 加入 PATH")


def no_proxy_open(url: str, timeout: float = 8.0) -> bytes:
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    request = urllib.request.Request(url, headers={"Cache-Control": "no-cache"})
    with opener.open(request, timeout=timeout) as response:
        return response.read()


class Adb:
    def __init__(self, serial: str) -> None:
        self.serial = serial
        self.exe = resolve_adb()

    def run(self, args: list[str], *, binary: bool = False, timeout: float = 60.0,
            check: bool = True) -> bytes | str:
        cmd = [self.exe, "-s", self.serial] + list(args)
        proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        stdout: bytes = proc.stdout
        stderr: bytes = proc.stderr
        text = stdout.decode("utf-8", "replace")
        err = stderr.decode("utf-8", "replace")
        if check and proc.returncode != 0:
            raise VerifyError("adb %s 失败 rc=%s\nstdout=%s\nstderr=%s" %
                              (" ".join(args), proc.returncode, text[-1000:], err[-1000:]))
        return stdout if binary else text

    def shell(self, *args: str, timeout: float = 60.0, check: bool = True) -> str:
        return self.run(["shell"] + [str(a) for a in args], timeout=timeout, check=check)  # type: ignore[return-value]

    def exec_out(self, remote_cmd: str, *, timeout: float = 120.0) -> bytes:
        # remote_cmd 作为一个字符串传给 adb，让远端 shell 自己解析引号。
        return self.run(["exec-out", remote_cmd], binary=True, timeout=timeout)  # type: ignore[return-value]

    def exec_out_args(self, *args: str, timeout: float = 120.0) -> bytes:
        return self.run(["exec-out"] + [str(a) for a in args], binary=True, timeout=timeout)  # type: ignore[return-value]

    def get_state(self) -> str:
        return self.run(["get-state"], timeout=15).strip()  # type: ignore[union-attr]

    def wait_device(self, timeout: float = 30.0) -> None:
        deadline = time.time() + timeout
        last = ""
        while time.time() < deadline:
            try:
                last = self.get_state()
                if last == "device":
                    return
            except Exception as exc:  # noqa: BLE001
                last = str(exc)
            time.sleep(1.0)
        raise VerifyError("设备 %s 未就绪: %s" % (self.serial, last))

    def foreground_package(self) -> str:
        text = self.shell("dumpsys", "activity", "activities", timeout=30, check=False)
        for line in text.splitlines():
            if "mResumedActivity" in line or "topResumedActivity" in line:
                match = re.search(r"[A-Za-z0-9_.]+/[A-Za-z0-9_.]+", line)
                if match:
                    return match.group(0).split("/", 1)[0]
        return ""

    def wait_foreground(self, package: str = APP, timeout: float = 30.0) -> None:
        deadline = time.time() + timeout
        current = ""
        while time.time() < deadline:
            current = self.foreground_package()
            if current == package:
                return
            time.sleep(0.5)
        raise VerifyError("前台不是 %s（当前 %s）" % (package, current or "unknown"))

    def install_apk(self, apk: Path) -> None:
        log("安装 APK: " + str(apk))
        out = self.run(["install", "-r", "-d", str(apk)], timeout=180)
        if "Success" not in str(out):
            raise VerifyError("adb install 未返回 Success: " + str(out)[-1000:])

    def clear_app(self) -> None:
        out = self.shell("pm", "clear", APP, timeout=30)
        log("pm clear " + APP + ": " + out.strip())
        if "Success" not in out:
            raise VerifyError("pm clear 失败: " + out)

    def start_app(self, mirror: str) -> None:
        out = self.shell(
            "am", "start", "-W", "-n", APP + "/" + ACTIVITY,
            "--es", "pb_zlib_mirror", mirror,
            timeout=60,
        )
        if "Error" in out and "Warning" not in out:
            raise VerifyError("am start 失败: " + out[-1000:])
        log("am start: " + " ".join(line.strip() for line in out.splitlines() if line.strip())[-500:])

    def read_books(self) -> dict:
        raw = self.exec_out_args("run-as", APP, "cat", "files/books.json", timeout=30)
        text = raw.decode("utf-8", "replace").strip()
        if not text:
            return {"books": []}
        try:
            return json.loads(text)
        except json.JSONDecodeError as exc:
            raise VerifyError("books.json 不是合法 JSON: " + text[:500]) from exc

    def read_file_bytes(self, path: str) -> bytes:
        # 路径可能包含空格（书名生成的文件名），用远端 sh 的单引号安全传参。
        quoted = shlex.quote(path)
        return self.exec_out("run-as %s cat %s" % (APP, quoted), timeout=120)

    def pull_file(self, remote_path: str, local_path: Path) -> bytes:
        # Android 12 的 run-as 无法穿过 app-specific external storage（permission denied），
        # 但 adb shell 可以读；用 adb pull 拉原始字节，避免 exec-out 文本化。
        self.run(["pull", remote_path, str(local_path)], timeout=120)
        return local_path.read_bytes()

    def file_md5(self, path: str) -> str:
        quoted = shlex.quote(path)
        out = self.exec_out("run-as %s md5sum %s" % (APP, quoted), timeout=30).decode("utf-8", "replace")
        return out.split()[0] if out.split() else ""

    def package_apk_md5(self) -> str:
        text = self.shell("pm", "path", APP, timeout=30).strip()
        base = ""
        for line in text.splitlines():
            if line.startswith("package:"):
                base = line[len("package:"):].strip()
                break
        if not base:
            raise VerifyError("pm path 未找到 " + APP)
        quoted = shlex.quote(base)
        out = self.exec_out("run-as %s md5sum %s" % (APP, quoted), timeout=30).decode("utf-8", "replace")
        return out.split()[0] if out.split() else ""


@dataclass
class UNode:
    attrs: dict[str, str]
    x1: int
    y1: int
    x2: int
    y2: int
    parent: Optional["UNode"] = None
    children: list["UNode"] = field(default_factory=list)

    @property
    def text(self) -> str:
        return self.attrs.get("text", "")

    @property
    def desc(self) -> str:
        return self.attrs.get("content-desc", "")

    @property
    def cls(self) -> str:
        return self.attrs.get("class", "")

    @property
    def clickable(self) -> bool:
        return self.attrs.get("clickable", "false").lower() == "true"

    @property
    def enabled(self) -> bool:
        return self.attrs.get("enabled", "true").lower() == "true"

    @property
    def bounds(self) -> tuple[int, int, int, int]:
        return self.x1, self.y1, self.x2, self.y2

    @property
    def center(self) -> tuple[int, int]:
        return (self.x1 + self.x2) // 2, (self.y1 + self.y2) // 2

    def label(self) -> str:
        return self.text or self.desc or self.cls


def clickable_ancestor(node: UNode) -> UNode:
    cursor: Optional[UNode] = node
    while cursor is not None:
        if cursor.clickable:
            return cursor
        cursor = cursor.parent
    return node


def parse_bounds(raw: str) -> tuple[int, int, int, int]:
    match = re.search(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", raw or "")
    if not match:
        return 0, 0, 0, 0
    return tuple(int(value) for value in match.groups())  # type: ignore[return-value]


def parse_ui(xml_text: str) -> list[UNode]:
    root = ET.fromstring(xml_text)
    nodes: list[UNode] = []

    def walk(element: ET.Element, parent: Optional[UNode]) -> None:
        current = parent
        if element.tag == "node":
            current = UNode(dict(element.attrib), *parse_bounds(element.attrib.get("bounds", "")), parent)
            if parent is not None:
                parent.children.append(current)
            nodes.append(current)
        for child in list(element):
            walk(child, current)

    walk(root, None)
    return nodes


class Ui:
    def __init__(self, adb: Adb, outdir: Path) -> None:
        self.adb = adb
        self.outdir = outdir
        self.serial = adb.serial
        self.dump_count = 0
        self.last_xml = ""

    def dump(self, save: str = "") -> list[UNode]:
        remote = "/data/local/tmp/p1c-verify-ui.xml"
        last_error = ""
        for _ in range(4):
            try:
                out = self.adb.shell("uiautomator", "dump", remote, timeout=35, check=False)
                if "dumped" not in out.lower() and "error" in out.lower():
                    last_error = out.strip()
                    time.sleep(0.8)
                    continue
                raw = self.adb.exec_out_args("cat", remote, timeout=30).decode("utf-8", "replace")
                if not raw.lstrip().startswith("<?xml"):
                    last_error = "UI XML 非 XML: " + raw[:200]
                    time.sleep(0.8)
                    continue
                self.last_xml = raw
                self.dump_count += 1
                if save:
                    (self.outdir / save).write_text(raw, encoding="utf-8")
                return parse_ui(raw)
            except (ET.ParseError, VerifyError) as exc:
                last_error = str(exc)
                time.sleep(0.8)
        raise VerifyError("uiautomator dump 失败: " + last_error)

    def screenshot(self, name: str) -> None:
        try:
            data = self.adb.exec_out_args("screencap", "-p", timeout=30)
            (self.outdir / name).write_bytes(data)
        except Exception as exc:  # noqa: BLE001 - 截图失败不阻断主证据
            log("截图失败 %s: %s" % (name, exc))

    def wait(self, predicate: Callable[[UNode], bool], *, timeout: float = 30.0,
             what: str = "条件") -> UNode:
        deadline = time.time() + timeout
        last_nodes: list[UNode] = []
        while time.time() < deadline:
            try:
                last_nodes = self.dump()
                for node in last_nodes:
                    if predicate(node):
                        return node
            except VerifyError as exc:
                log(str(exc))
            time.sleep(0.7)
        labels = " | ".join(node.label() for node in last_nodes if node.label())[:1200]
        raise VerifyError("等待超时: " + what + "；当前节点: " + labels)

    def wait_text(self, text: str, *, contains: bool = False, timeout: float = 30.0) -> UNode:
        def match(node: UNode) -> bool:
            value = node.text
            return (text in value) if contains else (value == text)

        return self.wait(match, timeout=timeout, what=("text contains " if contains else "text == ") + text)

    def find_all(self, predicate: Callable[[UNode], bool]) -> list[UNode]:
        return [node for node in self.dump() if predicate(node)]

    def tap(self, node: UNode, what: str = "") -> None:
        x, y = node.center
        log("tap %s at (%d,%d) bounds=%s" % (what or node.label(), x, y, node.bounds))
        self.adb.shell("input", "tap", str(x), str(y), timeout=30)
        time.sleep(0.35)

    def tap_text(self, text: str, *, contains: bool = False, what: str = "") -> UNode:
        node = self.wait_text(text, contains=contains, timeout=30)
        self.tap(node, what or ("text=" + text))
        return node

    def tap_desc(self, desc: str, what: str = "") -> UNode:
        node = self.wait(lambda n: n.desc == desc, timeout=30, what="desc=" + desc)
        self.tap(node, what or ("desc=" + desc))
        return node


@dataclass
class MockServer:
    process: subprocess.Popen
    port: int
    log_path: Path
    health: dict
    reference_bytes: bytes
    reference_path: Path
    stdout_path: Path
    stderr_path: Path
    out_handle: object
    err_handle: object


def mock_health(port: int) -> dict:
    raw = no_proxy_open("http://127.0.0.1:%d/__health" % port, timeout=2.0)
    return json.loads(raw.decode("utf-8"))


def start_mock(port: int, book_size: int, outdir: Path) -> MockServer:
    try:
        existing = mock_health(port)
        raise VerifyError("端口 %d 已有 mock 在运行（%s）；请等待或改用 --port" % (port, existing.get("version")))
    except (urllib.error.URLError, ConnectionError, TimeoutError, json.JSONDecodeError, OSError):
        pass
    log_path = outdir / "mock.jsonl"
    stdout_path = outdir / "mock.stdout.log"
    stderr_path = outdir / "mock.stderr.log"
    for path in (log_path, stdout_path, stderr_path):
        if path.exists():
            path.unlink()
    out_handle = stdout_path.open("wb")
    err_handle = stderr_path.open("wb")
    cmd = [
        sys.executable, str(MOCK_SCRIPT),
        "--host", "127.0.0.1",
        "--port", str(port),
        "--log", str(log_path),
        "--book-size", str(book_size),
        "--book-count", "1",
        "--dl-mode", "redirect",
        "--quiet",
    ]
    log("启动 mock: " + " ".join(cmd))
    proc = subprocess.Popen(cmd, cwd=str(ROOT), stdout=out_handle, stderr=err_handle)
    deadline = time.time() + 20
    health: dict | None = None
    last = ""
    while time.time() < deadline:
        if proc.poll() is not None:
            raise VerifyError("mock 启动即退出 rc=%s，见 %s" % (proc.returncode, stderr_path))
        try:
            health = mock_health(port)
            break
        except Exception as exc:  # noqa: BLE001
            last = str(exc)
            time.sleep(0.25)
    if health is None:
        raise VerifyError("mock 健康检查超时: " + last)
    expected = int(health["book"]["bytes"])
    # build_pdf 会把内容补齐到 >= --book-size，实际长度可能略大（默认 2,000,000 -> 2,000,013）。
    # 以 health 的 book.bytes 为准，不把请求值当实际值。
    log("mock book 请求=%d 实际=%d" % (book_size, expected))
    reference_url = "http://127.0.0.1:%d/cdn/mock-book.pdf?ref=verify" % port
    reference = no_proxy_open(reference_url, timeout=30)
    reference_path = outdir / "mock-reference.pdf"
    reference_path.write_bytes(reference)
    if len(reference) != expected:
        raise VerifyError("mock 参考文件 bytes=%d != health bytes=%d" % (len(reference), expected))
    if sha256_bytes(reference) != health["book"]["sha256"]:
        raise VerifyError("mock 参考文件 sha256 与 health 不一致")
    # 参考请求发生在 app 之前；清空 JSONL，保证后续只记录 App 的请求。
    log_path.write_text("", encoding="utf-8")
    log("mock 就绪 port=%d bytes=%d sha256=%s sha1=%s" %
        (port, expected, health["book"]["sha256"][:16], sha1_bytes(reference)[:16]))
    return MockServer(proc, port, log_path, health, reference, reference_path,
                      stdout_path, stderr_path, out_handle, err_handle)


def stop_mock(mock: MockServer) -> None:
    try:
        mock.process.terminate()
        try:
            mock.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            mock.process.kill()
            mock.process.wait(timeout=5)
    finally:
        for handle in (mock.out_handle, mock.err_handle):
            try:
                handle.close()
            except Exception:  # noqa: BLE001
                pass


def parse_mock_log(path: Path) -> list[dict]:
    rows: list[dict] = []
    if not path.exists():
        return rows
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    return rows


def print_mock_rows(rows: list[dict]) -> None:
    print("[verify] mock /dl /cdn 请求行:")
    for row in rows:
        path = str(row.get("path", ""))
        if not (path.startswith("/dl/") or path.startswith("/cdn/")):
            continue
        print("  %s %s status=%s bytes=%s range=%s content_range=%s closed=%s" % (
            row.get("method"), path, row.get("status"), row.get("bytes"),
            row.get("range"), row.get("content_range"), row.get("client_closed"),
        ))


def detect_new_book(before: dict, after: dict) -> dict:
    old_paths = {
        str(item.get("filePath", ""))
        for item in before.get("books", [])
        if str(item.get("filePath", ""))
    }
    new_items = [
        item for item in after.get("books", [])
        if str(item.get("filePath", "")) and str(item.get("filePath", "")) not in old_paths
    ]
    if len(new_items) == 1:
        return new_items[0]
    if not new_items:
        raise VerifyError("books.json 没有新增条目")
    # 多本时选带 zlib 标记或标题为 Mock Book 的条目；仍多于 1 则报错。
    tagged = [item for item in new_items if any("zlib:" in str(tag) for tag in item.get("tags", []))]
    if len(tagged) == 1:
        return tagged[0]
    raise VerifyError("books.json 新增 %d 条，无法唯一确定: %s" % (
        len(new_items), [item.get("filePath") for item in new_items],
    ))


def run_device(args: argparse.Namespace, outdir: Path, mock: MockServer) -> dict:
    adb = Adb(args.serial)
    adb.wait_device()
    log("设备 %s 就绪" % args.serial)

    apk = Path(args.apk).resolve()
    local_md5 = md5_file(apk)
    if args.expect_apk_md5 and local_md5 != args.expect_apk_md5:
        raise VerifyError("APK md5=%s != expected=%s" % (local_md5, args.expect_apk_md5))
    log("APK md5=" + local_md5)

    original_proxy = adb.shell("settings", "get", "global", "http_proxy", timeout=15, check=False).strip()
    if original_proxy in ("", "null"):
        original_proxy = "null"

    def restore_proxy() -> None:
        try:
            if original_proxy == "null":
                adb.shell("settings", "delete", "global", "http_proxy", timeout=15, check=False)
            else:
                adb.shell("settings", "put", "global", "http_proxy", original_proxy, timeout=15, check=False)
        except Exception:  # noqa: BLE001
            pass

    # 任何异常退出都恢复代理，避免把设备留在 http_proxy=:0。
    atexit.register(restore_proxy)
    installed_md5 = ""
    try:
        adb.install_apk(apk)
        installed_md5 = adb.package_apk_md5()
        log("设备内 base.apk md5=" + installed_md5)
        if args.expect_apk_md5 and installed_md5 != args.expect_apk_md5:
            raise VerifyError("安装后设备 APK md5=%s != expected=%s" % (installed_md5, args.expect_apk_md5))
        adb.clear_app()
        adb.shell("settings", "put", "global", "http_proxy", ":0", timeout=15)
        adb.run(["reverse", "--remove", "tcp:%d" % args.port], check=False, timeout=15)
        adb.run(["reverse", "tcp:%d" % args.port, "tcp:%d" % args.port], timeout=15)
        reverse_list = adb.run(["reverse", "--list"], timeout=15)
        log("adb reverse: " + str(reverse_list).strip().replace("\n", " | "))
        if "tcp:%d" % args.port not in str(reverse_list):
            raise VerifyError("adb reverse 未建立")
        adb.start_app("http://127.0.0.1:%d" % args.port)
        adb.wait_foreground(APP, timeout=30)
    except Exception:
        # 失败也尽量恢复代理；reverse 保留给排查。
        try:
            if original_proxy == "null":
                adb.shell("settings", "delete", "global", "http_proxy", timeout=15, check=False)
            else:
                adb.shell("settings", "put", "global", "http_proxy", original_proxy, timeout=15, check=False)
        except Exception:  # noqa: BLE001
            pass
        raise

    ui = Ui(adb, outdir)
    ui.wait_text("书架", timeout=40)
    ui.screenshot("00-launch.png")
    ui.dump("00-launch.xml")
    log("已到书架")

    # 导航：书架 -> Z-Library
    ui.tap_text("Z-Library", what="nav Z-Library")
    ui.wait(lambda n: n.text == "书名 / 作者 / ISBN" or "EditText" in n.cls,
            timeout=40, what="Z-Library 搜索页")
    ui.screenshot("01-discover.png")
    ui.dump("01-discover.xml")
    log("已到 Z-Library 搜索页")

    # 搜索框：优先 EditText，退回 hint 文本
    try:
        field = ui.wait(lambda n: "EditText" in n.cls, timeout=12, what="搜索框 EditText")
    except VerifyError:
        field = ui.wait_text("书名 / 作者 / ISBN", timeout=12)
    ui.tap(field, "搜索框")
    time.sleep(0.25)
    adb.shell("input", "text", args.query, timeout=20)
    time.sleep(0.5)
    ui.screenshot("02-query.png")
    try:
        ui.tap_desc("搜索", "搜索按钮")
    except VerifyError:
        log("未找到搜索图标，改按 IME Search")
        adb.shell("input", "keyevent", "66", timeout=20)

    # 搜索结果：mock 的 search-android.html 第一条标题。
    result = ui.wait(
        lambda n: n.text == args.result_title or args.result_title in n.text,
        timeout=60, what="搜索结果《" + args.result_title + "》",
    )
    ui.screenshot("03-results.png")
    ui.dump("03-results.xml")
    log("搜索结果: " + result.text)
    # 点标题或它的可点击祖先，确保命中整行。
    target = result
    cursor = result.parent
    while cursor is not None and not cursor.clickable:
        cursor = cursor.parent
    if cursor is not None and cursor.clickable:
        target = cursor
    ui.tap(target, "搜索结果行")
    ui.wait_text("书籍详情", timeout=40)
    ui.screenshot("04-detail.png")
    ui.dump("04-detail.xml")
    log("已到详情页")

    # 详情页加载完成后再记录 books.json 基线（LibraryStore 启动时会把 fixtures 写进索引）。
    ui.wait(lambda n: n.text.startswith("下载") and "下载中" not in n.text, timeout=30, what="下载按钮")
    time.sleep(1.0)
    before_books = adb.read_books()
    (outdir / "books-before.json").write_text(
        json.dumps(before_books, ensure_ascii=False, indent=2), encoding="utf-8")
    log("books.json 基线: %d 条" % len(before_books.get("books", [])))

    download = ui.wait(lambda n: n.text.startswith("下载") and "下载中" not in n.text,
                       timeout=30, what="可点击下载按钮")
    ui.tap(clickable_ancestor(download), "下载按钮")
    ui.screenshot("05-download-start.png")

    # 等待 books.json 新增条目：不使用 UI 文案作为唯一判据。
    deadline = time.time() + args.timeout
    new_book: dict | None = None
    failure_text = ""
    while time.time() < deadline:
        try:
            after = adb.read_books()
            try:
                new_book = detect_new_book(before_books, after)
                break
            except VerifyError:
                pass
        except VerifyError as exc:
            log("读取 books.json 暂不可用: " + str(exc))
        try:
            nodes = ui.dump()
            for node in nodes:
                if "下载失败" in node.text or "下载失败" in node.desc:
                    failure_text = node.text or node.desc
                    break
        except VerifyError:
            pass
        if failure_text:
            ui.screenshot("99-download-failed.png")
            raise VerifyError("下载失败 UI: " + failure_text)
        time.sleep(1.0)
    if new_book is None:
        ui.screenshot("99-download-timeout.png")
        raise VerifyError("等待下载完成超时（%ss）" % args.timeout)

    ui.screenshot("06-completed.png")
    ui.dump("06-completed.xml")
    log("books.json 新增条目: " + json.dumps({
        "title": new_book.get("title"),
        "filePath": new_book.get("filePath"),
        "fileSize": new_book.get("fileSize"),
        "tags": new_book.get("tags"),
    }, ensure_ascii=False))

    file_path = str(new_book.get("filePath", ""))
    if not file_path:
        raise VerifyError("books.json 新增条目没有 filePath")
    pulled_path = outdir / "device-download.bin"
    device_bytes = adb.pull_file(file_path, pulled_path)
    device_sha1 = sha1_bytes(device_bytes)
    device_sha256 = sha256_bytes(device_bytes)
    # 读一次 books.json 的最终版本，确保 fileSize 与文件字节数一致。
    final_books = adb.read_books()
    final_item = next(
        (item for item in final_books.get("books", []) if str(item.get("filePath", "")) == file_path),
        None,
    )
    if final_item is None:
        raise VerifyError("最终 books.json 中找不到 filePath=" + file_path)
    reported_size = int(final_item.get("fileSize") or 0)
    mock_bytes = int(mock.health["book"]["bytes"])
    mock_sha1 = sha1_bytes(mock.reference_bytes)

    mock_rows = parse_mock_log(mock.log_path)
    (outdir / "mock-app.jsonl").write_text(
        "\n".join(json.dumps(row, ensure_ascii=False) for row in mock_rows) + ("\n" if mock_rows else ""),
        encoding="utf-8",
    )
    print_mock_rows(mock_rows)
    dl_rows = [row for row in mock_rows if str(row.get("path", "")).startswith("/dl/")]
    cdn_rows = [row for row in mock_rows if str(row.get("path", "")).startswith("/cdn/")]
    full_cdn = [
        row for row in cdn_rows
        if str(row.get("method")) == "GET"
        and int(row.get("status") or 0) in (200, 206)
        and int(row.get("bytes") or 0) == mock_bytes
    ]
    checks = {
        "dl_302": any(int(row.get("status") or 0) == 302 for row in dl_rows),
        "cdn_full_bytes": bool(full_cdn),
        "file_bytes_eq_mock": len(device_bytes) == mock_bytes,
        "books_fileSize_eq_file": reported_size == len(device_bytes),
        "sha1_eq_mock": device_sha1 == mock_sha1,
        "sha256_eq_mock": device_sha256 == sha256_bytes(mock.reference_bytes),
    }
    result = {
        "serial": args.serial,
        "apk_md5": local_md5,
        "installed_apk_md5": installed_md5,
        "mock": {
            "port": args.port,
            "dl_mode": "redirect",
            "bytes": mock_bytes,
            "sha1": mock_sha1,
            "sha256": sha256_bytes(mock.reference_bytes),
            "reference_file": str(mock.reference_path),
            "request_rows": mock_rows,
        },
        "download": {
            "filePath": file_path,
            "bytes": len(device_bytes),
            "sha1": device_sha1,
            "sha256": device_sha256,
            "books_fileSize": reported_size,
            "title": final_item.get("title"),
            "tags": final_item.get("tags"),
        },
        "checks": checks,
        "passed": all(checks.values()),
        "limitation": "mock 环境只证明 /dl 302 -> CDN -> 落盘 -> 书架 的机制；真实 CDN 未验证。",
    }
    (outdir / "result.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print("[verify] checks=" + json.dumps(checks, ensure_ascii=False))
    print("[verify] PASS" if result["passed"] else "[verify] FAIL")
    if not result["passed"]:
        raise VerifyError("断言未全部通过: " + json.dumps(checks, ensure_ascii=False))

    # 恢复代理（设备留给后续人工检查，reverse 可保留）；atexit 兜底。
    restore_proxy()
    return result


def self_test(port: int, book_size: int, outdir: Path) -> int:
    outdir.mkdir(parents=True, exist_ok=True)
    mock = start_mock(port, book_size, outdir)
    try:
        rows = parse_mock_log(mock.log_path)
        log("self-test: mock 参考文件 sha1=%s bytes=%d" % (sha1_bytes(mock.reference_bytes), len(mock.reference_bytes)))
        log("self-test: app 前 JSONL 已清空（当前 %d 行）" % len(rows))
        print(json.dumps({
            "health": mock.health,
            "reference_sha1": sha1_bytes(mock.reference_bytes),
            "reference_sha256": sha256_bytes(mock.reference_bytes),
            "reference_bytes": len(mock.reference_bytes),
        }, ensure_ascii=False, indent=2))
    finally:
        stop_mock(mock)
    log("self-test: PASS（只验证 mock，不碰设备）")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="P1c /dl 302 独立 E2E 复核")
    parser.add_argument("--serial", default=DEFAULT_SERIAL, help="adb serial，默认 8A9Y0G2XJ")
    parser.add_argument("--apk", default=str(DEFAULT_APK), help="debug APK 路径")
    parser.add_argument("--expect-apk-md5", default=EXPECTED_APK_MD5, help="期望 APK md5；传空跳过")
    parser.add_argument("--port", type=int, default=8899, help="mock 端口，默认 8899")
    parser.add_argument("--book-size", type=int, default=2_000_000,
                        help="传给 mock 的目标书字节数，默认 2000000（实际 2000013）")
    parser.add_argument("--query", default="android", help="搜索关键词")
    parser.add_argument("--result-title", default=DEFAULT_RESULT_TITLE, help="搜索结果标题")
    parser.add_argument("--timeout", type=float, default=180.0, help="等待下载完成秒数")
    parser.add_argument("--outdir", default="", help="证据目录；默认 F:/tmp/pb-p1c-verify-<ts>")
    parser.add_argument("--self-test", action="store_true", help="只起 mock 做宿主自测，不碰设备")
    return parser


def main(argv: list[str]) -> int:
    args = build_parser().parse_args(argv)
    outdir = Path(args.outdir) if args.outdir else Path("F:/tmp") / ("pb-p1c-verify-" + now_stamp())
    outdir.mkdir(parents=True, exist_ok=True)
    log("证据目录: " + str(outdir))
    if args.self_test:
        return self_test(args.port, args.book_size, outdir)
    mock = start_mock(args.port, args.book_size, outdir)
    try:
        result = run_device(args, outdir, mock)
        log("完成: " + str(outdir / "result.json"))
        return 0 if result.get("passed") else 1
    finally:
        stop_mock(mock)


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except VerifyError as exc:
        print("[verify] FAIL: " + str(exc), file=sys.stderr)
        raise SystemExit(1)
