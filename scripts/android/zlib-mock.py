#!/usr/bin/env python3
"""zlib-mock - 离线确定性 Z-Library 镜像，供 ParticleBook P1c 下载链路 E2E / 故障注入使用。

不依赖手机，也不访问外网；默认监听 127.0.0.1:8899，真机通过 adb reverse 访问。

用法（宿主）：
    python scripts/android/zlib-mock.py --port 8899 --log F:/tmp/zlib-mock-8899.jsonl
真机侧：
    adb -s 8A9Y0G2XJ reverse tcp:8899 tcp:8899
    adb shell settings put global http_proxy :0
    adb shell am start -n app.particlebook/.android.MobileActivity --es pb_zlib_mirror http://127.0.0.1:8899

端点（app 真实契约）：
    GET  /s/<q>?page=N          搜索结果 HTML（夹具 search-android.html）
    GET  /book/<hash>[/<slug>]  详情 HTML（夹具 book-detail.html 的 N 个变体）
    GET  /dl/<hash>             下载页 HTML（含绝对 http://.../cdn/... 锚），可配倒计时/登录墙
    GET  /go/<hash>             302 到 /cdn 直链（可选用 --dl-link go 让下载页指向这里）
    GET  /cdn/<file>            文件本体：200 + Content-Length + Accept-Ranges，支持 Range -> 206/416
    GET/POST /login             最小登录实现（--logged-in 预置登录态）
    GET  /papi/user/get-downloads  登录态校验 JSON

控制端点（宿主/测试脚本用）：
    GET  /__health              健康与状态 JSON
    GET/POST /__fault           读/写故障注入配置
    GET  /__stats               计数器 JSON
    POST /__reset               清零计数器与故障
    GET  /__log?tail=N          最近 N 行请求日志（JSON 数组）

故障可配：
    first_packet_lost  回 200 + 正确 Content-Length，但只写 first_bytes 字节（默认 0）就断连
    range_416          只对带 Range 的请求注入：首个非探测 Range GET 回 416 + Content-Range: bytes */total；无 Range 的 GET 正常 200 且不消耗故障（探测 HEAD / bytes=0-0 不触发）
    slow               按 rate_kbps 限速；stall_ms 可中途停顿

日志：每请求一行 JSONL（文件 + stdout 摘要行）。字段见 MockState.record()；其中 cookie_names/cookie_len 记录请求 Cookie 的键名（不落值），供 P1C-11 断言；/__stats 相应有 cookie_requests、dl_cookie_requests。
"""

from __future__ import annotations

import argparse
import hashlib
import http.client
import json
import re
import socket
import sys
import tempfile
import threading
import time
from collections import deque
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse

VERSION = "1.0.1"
REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_FIXTURES = REPO_ROOT / "android" / "app" / "src" / "test" / "resources" / "zlib"
FAULT_MODES = ("none", "first_packet_lost", "range_416", "fresh_416", "slow")
COUNTER_KEYS = (
    "requests",
    "cdn_requests",
    "range_requests",
    "partial_206",
    "status_416",
    "faults_injected",
    "fault_first_packet_lost",
    "fault_range_416",
    "fault_fresh_416",
    "fault_slow",
    "bytes_served",
    "client_closed",
    "login_posts",
    "login_ok",
    "cookie_requests",
    "dl_cookie_requests",
)


def iso_now() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="milliseconds")


def cookie_names(cookie: str) -> list[str]:
    """请求 Cookie 头里的键名（不落值，避免把账号 cookie 写进日志）。"""
    names = []
    for part in (cookie or "").split(";"):
        name = part.split("=", 1)[0].strip()
        if name and name not in names:
            names.append(name)
    return names


def build_pdf(total_bytes: int) -> bytes:
    """生成一个结构合法、页数 1、可被 PDFium/Android PdfRenderer 打开的最小 PDF。

    体积由第 4 对象内容流里的一条超长注释补齐（注释到换行为止，解析成本极低）。
    """
    total = max(int(total_bytes), 4096)
    head = b"BT /F1 24 Tf 72 760 Td (ParticleBook mock book) Tj ET\n"

    def assemble(pad: int) -> bytes:
        out = bytearray()
        out += b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n"
        offsets = {}

        def add(num: int, payload: bytes) -> None:
            offsets[num] = len(out)
            out.extend(b"%d 0 obj\n" % num)
            out.extend(payload)
            out.extend(b"\nendobj\n")

        content = head + b"%" + (b"A" * pad) + b"\n"
        add(1, b"<< /Type /Catalog /Pages 2 0 R >>")
        add(2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        add(
            3,
            b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] "
            b"/Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
        )
        add(4, b"<< /Length %d >>\nstream\n" % len(content) + content + b"endstream")
        add(5, b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        xref = len(out)
        out += b"xref\n0 6\n0000000000 65535 f \n"
        for num in range(1, 6):
            out += b"%010d 00000 n \n" % offsets[num]
        out += (
            b"trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n"
            + str(xref).encode("ascii")
            + b"\n%%EOF\n"
        )
        return bytes(out)

    pad = 0
    for _ in range(5):
        data = assemble(pad)
        if len(data) >= total:
            return data
        pad += total - len(data) + 4
    return assemble(pad)


def build_variants(fixtures: Path, count: int) -> list[bytes]:
    """把详情页夹具复制成 N 个变体：不同 data-book_id / 标题 / 下载 hash，后处理不变。"""
    raw = (fixtures / "book-detail.html").read_bytes()
    text = raw.decode("utf-8", "replace")
    title_re = re.compile(r"(<h1[^>]*book-title[^>]*>)([\s\S]*?)(</h1>)", re.IGNORECASE)
    variants: list[bytes] = []
    for index in range(max(1, count)):
        body = text
        body = re.sub(r'data-book_id="\d+"', 'data-book_id="%d"' % (9000000 + index), body)
        body = body.replace("/dl/AX1jDr5bOm", "/dl/v%d" % index)
        body = title_re.sub(
            lambda m, i=index: m.group(1) + "Mock Book %d" % i + m.group(3),
            body,
            count=1,
        )
        variants.append(body.encode("utf-8"))
    return variants


def _tail_json(value: object) -> str:
    return json.dumps(value, ensure_ascii=False)


class MockState:
    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        self.lock = threading.RLock()
        self.started_at = time.time()
        self.port = int(getattr(args, "port", 0) or 0)
        self.public_base = (args.public_base or "").rstrip("/")
        self.quiet = bool(args.quiet)
        self.fixtures = Path(args.fixtures)
        self.book_format = "pdf"
        self.book = build_pdf(args.book_size)
        self.book_sha256 = hashlib.sha256(self.book).hexdigest()
        self.book_ctype = "application/pdf"
        self.variants = build_variants(self.fixtures, args.book_count)
        self.req_seq = 0
        self.attempts: dict[str, int] = {}
        self.log_buffer: deque[dict] = deque(maxlen=5000)
        self.log_path: Path | None = None
        self.counters = {key: 0 for key in COUNTER_KEYS}
        self.by_path: dict[str, int] = {}
        self.logged_in = bool(args.logged_in)
        self.countdown_left = int(args.countdown or 0)
        fault_count = int(args.fault_count) if args.fault != "none" else 0
        self.fault = {
            "mode": args.fault,
            "count": fault_count,
            "path": args.fault_path,
            "first_bytes": int(args.first_bytes),
            "rate_kbps": float(args.rate_kbps),
            "stall_ms": int(args.stall_ms),
        }

    # ---------------------------------------------------------- 状态快照

    def fault_snapshot(self) -> dict:
        with self.lock:
            return dict(self.fault)

    def stats_snapshot(self) -> dict:
        with self.lock:
            data = dict(self.counters)
            data["by_path_count"] = len(self.by_path)
            data["attempts"] = len(self.attempts)
            data["log_lines"] = len(self.log_buffer)
            return data

    def apply_fault(self, payload: dict) -> None:
        with self.lock:
            fault = self.fault
            if "mode" in payload:
                mode = str(payload["mode"])
                if mode not in FAULT_MODES:
                    raise ValueError("unknown fault mode: %s" % mode)
                fault["mode"] = mode
                if mode == "none":
                    fault["count"] = 0
                elif "count" not in payload:
                    fault["count"] = 1
            for key, cast in (
                ("count", int),
                ("first_bytes", int),
                ("rate_kbps", float),
                ("stall_ms", int),
            ):
                if key in payload and payload[key] is not None:
                    fault[key] = cast(payload[key])
            if "path" in payload and payload["path"]:
                fault["path"] = str(payload["path"])
            if fault["mode"] != "none" and int(fault["count"]) <= 0:
                fault["count"] = 1

    def peek_fault(self, path: str) -> str | None:
        """查看下一个将注入的故障但不消耗计数（用于只对带 Range 的请求注入 416）。"""
        with self.lock:
            fault = self.fault
            mode = fault["mode"]
            if mode == "none" or int(fault["count"]) <= 0:
                return None
            prefix = fault["path"] or "/cdn/"
            if not path.startswith(prefix):
                return None
            return mode

    def take_fault(self, path: str) -> str | None:
        with self.lock:
            fault = self.fault
            mode = fault["mode"]
            if mode == "none" or int(fault["count"]) <= 0:
                return None
            prefix = fault["path"] or "/cdn/"
            if not path.startswith(prefix):
                return None
            fault["count"] = int(fault["count"]) - 1
            self.counters["faults_injected"] += 1
            counter_key = "fault_" + mode
            if counter_key in self.counters:
                self.counters[counter_key] += 1
            return mode

    def reset(self) -> None:
        with self.lock:
            for key in self.counters:
                self.counters[key] = 0
            self.by_path.clear()
            self.attempts.clear()
            self.fault = {
                "mode": "none",
                "count": 0,
                "path": self.fault.get("path", "/cdn/"),
                "first_bytes": int(self.fault.get("first_bytes", 0)),
                "rate_kbps": float(self.fault.get("rate_kbps", 32.0)),
                "stall_ms": int(self.fault.get("stall_ms", 0)),
            }
            self.countdown_left = int(self.args.countdown or 0)

    # ---------------------------------------------------------- 日志

    def record(
        self,
        req_id: int,
        method: str,
        path: str,
        query: str,
        range_header: str,
        status: int,
        sent: int,
        content_length: int | None,
        content_range: str | None,
        fault: str,
        duration_ms: float,
        attempt: int,
        remote: str,
        client_closed: bool,
        cookie: str = "",
    ) -> None:
        entry = {
            "ts": iso_now(),
            "req_id": req_id,
            "method": method,
            "path": path,
            "query": query,
            "range": range_header or "-",
            "status": status,
            "bytes": sent,
            "content_length": content_length,
            "content_range": content_range or "-",
            "fault": fault,
            "duration_ms": round(duration_ms, 2),
            "remote": remote,
            "attempt": attempt,
            "client_closed": bool(client_closed),
            "cookie_names": cookie_names(cookie),
            "cookie_len": len(cookie or ""),
        }
        with self.lock:
            self.counters["requests"] += 1
            self.by_path[path] = self.by_path.get(path, 0) + 1
            self.counters["bytes_served"] += sent
            if path.startswith("/cdn/"):
                self.counters["cdn_requests"] += 1
            if range_header:
                self.counters["range_requests"] += 1
            if status == 206:
                self.counters["partial_206"] += 1
            if status == 416:
                self.counters["status_416"] += 1
            if client_closed:
                self.counters["client_closed"] += 1
            if cookie:
                self.counters["cookie_requests"] += 1
                if path.startswith("/dl/"):
                    self.counters["dl_cookie_requests"] += 1
            self.log_buffer.append(entry)
            if self.log_path is not None:
                try:
                    with self.log_path.open("a", encoding="utf-8") as handle:
                        handle.write(_tail_json(entry) + "\n")
                except OSError:
                    pass
            if not self.quiet:
                suffix = ("?" + query) if query else ""
                print(
                    "[mock] %s %s%s -> %d bytes=%d range=%s fault=%s %.0fms"
                    % (method, path, suffix, status, sent, range_header or "-", fault, duration_ms),
                    flush=True,
                )


class MockServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, state: MockState) -> None:
        super().__init__(address, MockHandler)
        self.state = state


class MockHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "zlib-mock/" + VERSION

    def log_message(self, fmt, *args):  # noqa: N802 - 框架回调
        pass

    @property
    def st(self) -> MockState:
        return self.server.state  # type: ignore[attr-defined]

    # ---------------------------------------------------------- 分发

    def do_GET(self):  # noqa: N802
        self.handle_any("GET")

    def do_HEAD(self):  # noqa: N802
        self.handle_any("HEAD")

    def do_POST(self):  # noqa: N802
        self.handle_any("POST")

    def handle_any(self, method: str) -> None:
        parsed = urlparse(self.path)
        path = unquote(parsed.path or "/")
        query = parsed.query or ""
        range_header = (self.headers.get("Range") or "") if self.headers else ""
        cookie = (self.headers.get("Cookie") or "") if self.headers else ""
        remote = self.client_address[0] if self.client_address else "-"
        with self.st.lock:
            self.st.req_seq += 1
            req_id = self.st.req_seq
            attempt = self.st.attempts.get(path, 0) + 1
            self.st.attempts[path] = attempt
        self._rid = req_id
        self._attempt = attempt
        self._range = range_header
        self._status = 0
        self._sent = 0
        self._content_length = None
        self._content_range = None
        self._fault = "none"
        self._client_closed = False
        began = time.perf_counter()
        try:
            self._route(method, path, query)
        except (BrokenPipeError, ConnectionResetError):
            self._client_closed = True
            self.close_connection = True
        except socket.timeout:
            self._client_closed = True
            self.close_connection = True
        except Exception as exc:  # noqa: BLE001 - mock 不应把异常吞掉
            if self._fault == "none":
                self._fault = "server_error"
            try:
                if self._status == 0:
                    self._send_html(500, "<html><body>mock error: %s</body></html>" % exc)
            except Exception:
                self.close_connection = True
        finally:
            duration_ms = (time.perf_counter() - began) * 1000.0
            self.st.record(
                self._rid,
                method,
                path,
                query,
                self._range,
                self._status,
                self._sent,
                self._content_length,
                self._content_range,
                self._fault,
                duration_ms,
                self._attempt,
                remote,
                self._client_closed,
                cookie,
            )

    def _route(self, method: str, path: str, query: str) -> None:
        if path == "/__health":
            return self._health()
        if path == "/__fault":
            return self._fault_endpoint(method)
        if path == "/__stats":
            return self._stats()
        if path == "/__reset":
            return self._reset()
        if path == "/__log":
            return self._log_endpoint(query)
        if path.startswith("/s/"):
            return self._search(path)
        if path.startswith("/book/"):
            return self._detail(path)
        if path.startswith("/dl/"):
            return self._download_page(path)
        if path.startswith("/go/"):
            return self._go(path)
        if path.startswith("/cdn/"):
            return self._cdn(method, path)
        if path == "/login":
            return self._login(method)
        if path.startswith("/papi/"):
            return self._papi(path)
        if path in ("/", "/index.html"):
            return self._send_html(200, "<html><body>zlib-mock %s</body></html>" % VERSION)
        return self._send_html(404, "<html><body>404</body></html>")

    # ---------------------------------------------------------- 发送原语

    def _send_bytes(self, status: int, body: bytes, ctype: str, headers=None) -> None:
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for key, value in headers or []:
            self.send_header(key, value)
        self.end_headers()
        self._status = status
        self._content_length = len(body)
        if not body:
            return
        try:
            self.wfile.write(body)
            self.wfile.flush()
            self._sent = len(body)
        except (BrokenPipeError, ConnectionResetError):
            self._client_closed = True
            self.close_connection = True

    def _send_html(self, status: int, html: str) -> None:
        self._send_bytes(status, html.encode("utf-8"), "text/html; charset=utf-8")

    def _send_json(self, status: int, payload: dict) -> None:
        self._send_bytes(
            status,
            json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            "application/json; charset=utf-8",
        )

    def _send_headers(self, status: int, length: int, ctype: str, headers=None) -> None:
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(length))
        for key, value in headers or []:
            self.send_header(key, value)
        self.end_headers()
        self._status = status
        self._content_length = length

    def _read_body(self) -> bytes:
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        if length <= 0:
            return b""
        return self.rfile.read(min(length, 1_048_576))

    def _read_json(self) -> dict:
        raw = self._read_body()
        if not raw:
            return {}
        return json.loads(raw.decode("utf-8"))

    # ---------------------------------------------------------- 控制端点

    def _health(self) -> None:
        st = self.st
        self._send_json(
            200,
            {
                "ok": True,
                "ready": True,
                "version": VERSION,
                "port": st.port,
                "public_base": st.public_base,
                "started_at": datetime.fromtimestamp(st.started_at).astimezone().isoformat(timespec="seconds"),
                "book": {"format": st.book_format, "bytes": len(st.book), "sha256": st.book_sha256},
                "fixtures": {"dir": str(st.fixtures), "ok": (st.fixtures / "book-detail.html").is_file()},
                "fault": st.fault_snapshot(),
                "dl": {
                    "mode": st.args.dl_mode,
                    "link": st.args.dl_link,
                    "countdown_left": st.countdown_left,
                },
                "logged_in": st.logged_in,
                "stats": st.stats_snapshot(),
            },
        )

    def _fault_endpoint(self, method: str) -> None:
        if method == "POST":
            try:
                payload = self._read_json()
                self.st.apply_fault(payload)
            except (ValueError, json.JSONDecodeError) as exc:
                return self._send_json(400, {"ok": False, "error": str(exc)})
        self._send_json(200, {"ok": True, "fault": self.st.fault_snapshot()})

    def _stats(self) -> None:
        self._send_json(200, {"ok": True, "stats": self.st.stats_snapshot()})

    def _reset(self) -> None:
        self.st.reset()
        self._send_json(200, {"ok": True, "fault": self.st.fault_snapshot()})

    def _log_endpoint(self, query: str) -> None:
        tail = 200
        try:
            values = parse_qs(query)
            if "tail" in values:
                tail = max(1, min(2000, int(values["tail"][0])))
        except (ValueError, IndexError):
            pass
        with self.st.lock:
            lines = list(self.st.log_buffer)[-tail:]
        self._send_json(200, {"ok": True, "count": len(lines), "lines": lines})

    # ---------------------------------------------------------- 数据端点

    def _search(self, path: str) -> None:
        query = unquote(path[len("/s/"):]).strip()
        fixture = self.st.fixtures / ("search-empty.html" if not query else "search-android.html")
        self._send_bytes(200, fixture.read_bytes(), "text/html; charset=utf-8")

    def _detail(self, path: str) -> None:
        hash_value = path[len("/book/"):].split("/")[0]
        variants = self.st.variants
        index = int(hashlib.sha1(hash_value.encode("utf-8")).hexdigest()[:8], 16) % len(variants)
        self._send_bytes(200, variants[index], "text/html; charset=utf-8")

    def _download_page(self, path: str) -> None:
        st = self.st
        if st.countdown_left > 0:
            st.countdown_left -= 1
            seconds = 2
            html = (
                "<html><body><p>Please wait %d seconds</p>"
                "<div data-countdown=\"%d\"></div></body></html>" % (seconds, seconds)
            )
            return self._send_html(200, html)
        if st.args.require_login and not self._request_logged_in():
            html = (
                "<html><body><a href=\"/login?redirectUrl=/dl/x\">Log In to access</a>"
                "</body></html>"
            )
            return self._send_html(200, html)
        base = st.public_base
        cdn_url = self._cdn_url(base)
        if st.args.dl_mode == "redirect":
            # R7-G 关键分支：真实站点 /dl 302 到签名 CDN。Location 必须是带
            # filename/expires/md5 的绝对 URL，app 会判定「签名直链、只能取一次」直接流式。
            target = cdn_url if st.args.dl_link == "cdn" else base + "/go" + path
            self.send_response(302)
            self.send_header("Location", target)
            self.send_header("Content-Length", "0")
            self.end_headers()
            self._status = 302
            return
        if st.args.dl_mode == "limit":
            # 匿名额度用尽页（app: ZlibDownloadParser.PageState.DailyLimit）
            html = (
                "<html><head><title>Daily limit</title></head><body>"
                "<h2>Daily limit reached</h2>"
                "<p>You have reached the daily download limit. Please come back tomorrow.</p>"
                "</body></html>"
            )
            return self._send_html(200, html)
        if st.args.dl_mode == "direct":
            # 少数镜像把 /dl 直接做成二进制直出（app: 200 + 非 HTML → 当前 URL 即直链）
            return self._cdn("GET", path)
        link = cdn_url if st.args.dl_link == "cdn" else base + "/go" + path
        html = (
            "<html><head><title>Download</title></head><body>"
            "<h2>Mock book download</h2>"
            "<a class=\"btn btn-default dlButton addDownloadedBook\" href=\"%s\">download</a>"
            "</body></html>" % link
        )
        self._send_html(200, html)

    def _cdn_url(self, base: str) -> str:
        return (
            base
            + "/cdn/mock-book."
            + self.st.book_format
            + "?filename=mock-book."
            + self.st.book_format
            + "&s=fake-signature&md5="
            + self.st.book_sha256[:16]
            + "&expires="
            + str(int(time.time()) + 3600)
        )

    def _go(self, path: str) -> None:
        url = self._cdn_url(self.st.public_base)
        self.send_response(302)
        self.send_header("Location", url)
        self.send_header("Content-Length", "0")
        self.end_headers()
        self._status = 302

    def _login(self, method: str) -> None:
        st = self.st
        if method == "POST":
            st.counters["login_posts"] += 1
            raw = self._read_body().decode("utf-8", "replace")
            fields = {
                key: values[0]
                for key, values in parse_qs(raw, keep_blank_values=True).items()
            }
            email = fields.get("email", "")
            password = fields.get("password", "")
            if st.args.email and email != st.args.email:
                return self._send_html(
                    200,
                    "<html><body><div class=\"form-error validation-error\">"
                    "Incorrect email or password</div></body></html>",
                )
            if st.args.password and password != st.args.password:
                return self._send_html(
                    200,
                    "<html><body><div class=\"form-error validation-error\">"
                    "Incorrect email or password</div></body></html>",
                )
            st.logged_in = True
            st.counters["login_ok"] += 1
            html = (
                "<html><head><script>const CurrentUser = new User("
                "{\"email\": \"%s\", \"name\": \"Mock User\"});</script></head>"
                "<body>logged in</body></html>" % (email or "mock@example.com")
            )
            headers = [
                ("Set-Cookie", "remix_userid=9000001; Path=/; HttpOnly"),
                ("Set-Cookie", "remix_userkey=mockkey; Path=/; HttpOnly"),
            ]
            return self._send_bytes(200, html.encode("utf-8"), "text/html; charset=utf-8", headers)
        html = (
            "<html><head><title>Log in</title></head><body>"
            "<form method=\"post\" action=\"/login\">"
            "<input name=\"email\"><input name=\"password\" type=\"password\">"
            "<button type=\"submit\">Log in</button></form></body></html>"
        )
        self._send_html(200, html)

    def _papi(self, path: str) -> None:
        if path.endswith("/get-downloads"):
            if self._request_logged_in():
                return self._send_json(200, {"success": 1, "downloads": []})
            return self._send_json(200, {"success": 0})
        self._send_json(200, {"success": 1})

    def _request_logged_in(self) -> bool:
        if self.st.logged_in:
            return True
        cookie = self.headers.get("Cookie") or "" if self.headers else ""
        return "remix_userid" in cookie

    # ---------------------------------------------------------- /cdn

    def _cdn(self, method: str, path: str) -> None:
        st = self.st
        total = len(st.book)
        range_header = self._range or ""
        is_probe = method == "HEAD" or range_header.strip() == "bytes=0-0"
        # Lead m01850：range_416 只对带 Range 的请求注入（真实服务器不会对无 Range 的 GET 回 416）。
        # 无 Range 的请求正常 200，故障计数保留给下一次续传请求。
        fault = None if is_probe else st.peek_fault(path)
        if fault == "range_416" and not range_header.strip():
            fault = None
        elif fault == "fresh_416" and range_header.strip():
            # 全新下载（无 Range）首包 416：给「无 Range 416 自愈」用例用；只在无 Range 请求上触发。
            fault = None
        elif fault:
            st.take_fault(path)
        if method == "HEAD":
            self._send_headers(
                200,
                total,
                st.book_ctype,
                [("Accept-Ranges", "bytes"), ("Content-Disposition", self._disposition())],
            )
            return
        if fault in ("range_416", "fresh_416"):
            self._fault = fault
            return self._send_bytes(
                416,
                b"",
                "text/plain; charset=utf-8",
                [("Content-Range", "bytes */%d" % total), ("Accept-Ranges", "bytes")],
            )
        start = None
        end = None
        match = re.fullmatch(r"bytes=(\d+)-(\d*)", range_header.strip()) if range_header else None
        if match:
            start = int(match.group(1))
            end = int(match.group(2)) if match.group(2) else total - 1
        if start is not None:
            if start >= total or start > end:
                return self._send_bytes(
                    416,
                    b"",
                    "text/plain; charset=utf-8",
                    [("Content-Range", "bytes */%d" % total), ("Accept-Ranges", "bytes")],
                )
            end = min(end, total - 1)
            body = st.book[start : end + 1]
            status = 206
            headers = [
                ("Accept-Ranges", "bytes"),
                ("Content-Disposition", self._disposition()),
                ("Content-Range", "bytes %d-%d/%d" % (start, end, total)),
            ]
            self._content_range = headers[-1][1]
        else:
            body = st.book
            status = 200
            headers = [("Accept-Ranges", "bytes"), ("Content-Disposition", self._disposition())]
        self._send_headers(status, len(body), st.book_ctype, headers)
        if fault == "first_packet_lost":
            self._fault = "first_packet_lost"
            first = max(0, min(int(st.fault.get("first_bytes") or 0), len(body)))
            if first:
                try:
                    self.wfile.write(body[:first])
                    self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError):
                    self._client_closed = True
            self._sent = first
            self.close_connection = True
            try:
                self.connection.shutdown(socket.SHUT_WR)
            except OSError:
                pass
            return
        if fault == "slow":
            self._fault = "slow"
        self._write_stream(body, fault)

    def _write_stream(self, body: bytes, fault: str | None) -> None:
        st = self.st
        sent = 0
        rate = float(st.fault.get("rate_kbps") or 0.0) if fault == "slow" else 0.0
        stall_ms = int(st.fault.get("stall_ms") or 0) if fault == "slow" else 0
        chunk = 16384
        delay = (chunk / 1024.0) / rate if rate > 0 else 0.0
        stalled = False
        try:
            for offset in range(0, len(body), chunk):
                part = body[offset : offset + chunk]
                self.wfile.write(part)
                sent += len(part)
                if stall_ms and not stalled and offset * 2 >= len(body):
                    self.wfile.flush()
                    time.sleep(stall_ms / 1000.0)
                    stalled = True
                if delay:
                    time.sleep(delay)
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            self._client_closed = True
            self.close_connection = True
        self._sent = sent

    def _disposition(self) -> str:
        return 'attachment; filename="mock-book.%s"' % self.st.book_format


# ---------------------------------------------------------------- 自测


def _http_request(host: str, port: int, method: str, path: str, headers=None, body=None):
    conn = http.client.HTTPConnection(host, port, timeout=30)
    try:
        conn.request(method, path, body=body, headers=headers or {})
        response = conn.getresponse()
        try:
            data = response.read()
        except http.client.IncompleteRead as exc:
            data = exc.partial
        headers: dict[str, str] = {}
        for key, value in response.getheaders():
            headers[key] = (headers[key] + ", " + value) if key in headers else value
        return response.status, headers, data
    finally:
        conn.close()


def run_self_test(args: argparse.Namespace) -> int:
    args.quiet = True
    args.fault = "none"
    args.fault_count = 0
    args.book_size = min(int(args.book_size), 262144)
    args.countdown = 0
    state = MockState(args)
    server = MockServer((args.host, 0), state)
    host, port = server.server_address[0], server.server_address[1]
    state.port = port
    state.public_base = "http://%s:%d" % (host, port)
    state.log_path = None
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    results: list[tuple[str, bool, str]] = []

    def check(name: str, condition: bool, detail: str = "") -> None:
        results.append((name, bool(condition), detail))
        line = ("PASS " if condition else "FAIL ") + name
        if detail and not condition:
            line += " :: " + detail
        print(line, flush=True)

    try:
        status, headers, data = _http_request(host, port, "GET", "/__health")
        health = json.loads(data.decode("utf-8"))
        check("health 200", status == 200)
        check("health ok+ready", health.get("ok") is True and health.get("ready") is True)
        check("health dl mode", health.get("dl", {}).get("mode") == "page")
        check("health book bytes", health.get("book", {}).get("bytes") == len(state.book))

        status, headers, data = _http_request(host, port, "GET", "/s/android?page=1")
        check("search 200", status == 200, "status=%s" % status)
        check("search html has book links", b"/book/" in data)

        status, headers, data = _http_request(host, port, "GET", "/book/6y7KYwknyQ")
        check("detail 200", status == 200)
        check("detail has details-book", b"details-book" in data)
        check("detail has book-title", b"book-title" in data)
        check("detail has /dl/ anchor", b"/dl/" in data)

        status, headers, data = _http_request(host, port, "GET", "/dl/6y7KYwknyQ")
        text = data.decode("utf-8", "replace")
        match = re.search(r'href="(http://[^"]*/cdn/[^"]+)"', text)
        check("dl page 200", status == 200)
        check("dl page absolute cdn link", bool(match), text[:200])
        cdn_path = ""
        if match:
            cdn_path = urlparse(match.group(1)).path

        # /dl 模式矩阵：redirect（R7-G 关键分支：302 到签名 CDN）
        state.args.dl_mode = "redirect"
        status, headers, data = _http_request(host, port, "GET", "/dl/6y7KYwknyQ")
        check("dl redirect 302", status == 302, "status=%s" % status)
        redirect_url = headers.get("Location", "")
        check(
            "dl redirect absolute cdn",
            redirect_url.startswith("http://") and "/cdn/" in redirect_url,
            redirect_url,
        )
        check(
            "dl redirect signed params",
            all(token in redirect_url for token in ("filename=", "expires=", "md5=", "s=")),
            redirect_url,
        )
        signed = urlparse(redirect_url)
        signed_path = signed.path + (("?" + signed.query) if signed.query else "")
        status, headers, data = _http_request(host, port, "HEAD", signed_path)
        check("dl redirect target HEAD 200", status == 200, "status=%s" % status)
        status, headers, data = _http_request(host, port, "GET", signed_path)
        check(
            "dl redirect target GET pdf",
            status == 200 and data.startswith(b"%PDF"),
            "status=%s bytes=%d" % (status, len(data)),
        )

        # 302 → /go → 签名 CDN（候选经一跳 302，覆盖 probeDirectLink 的 redirect 分支）
        state.args.dl_mode = "redirect"
        state.args.dl_link = "go"
        status, headers, data = _http_request(host, port, "GET", "/dl/6y7KYwknyQ")
        go_location = headers.get("Location", "")
        check("dl redirect go 302", status == 302 and "/go/" in go_location, go_location)
        go_path = urlparse(go_location).path
        status, headers, data = _http_request(host, port, "HEAD", go_path)
        check("dl go HEAD 302", status == 302, "status=%s" % status)
        check(
            "dl go HEAD location signed",
            "md5=" in headers.get("Location", "") and "expires=" in headers.get("Location", ""),
            headers.get("Location", ""),
        )
        status, headers, data = _http_request(host, port, "GET", go_path)
        check(
            "dl go GET 302",
            status == 302 and "/cdn/" in headers.get("Location", ""),
            "status=%s loc=%s" % (status, headers.get("Location", "")),
        )

        # HTML 限额页（app: PageState.DailyLimit）
        state.args.dl_mode = "limit"
        state.args.dl_link = "cdn"
        status, headers, data = _http_request(host, port, "GET", "/dl/6y7KYwknyQ")
        check("dl limit 200", status == 200, "status=%s" % status)
        check("dl limit phrase", b"Daily limit reached" in data, data[:160])

        # 二进制直出（少数镜像：200 + application/pdf，app 当前 URL 即直链）
        state.args.dl_mode = "direct"
        status, headers, data = _http_request(host, port, "GET", "/dl/6y7KYwknyQ")
        check("dl direct 200", status == 200, "status=%s" % status)
        check("dl direct pdf bytes", data.startswith(b"%PDF"), data[:8])
        check(
            "dl direct ctype pdf",
            "application/pdf" in headers.get("Content-Type", ""),
            headers.get("Content-Type", ""),
        )

        state.args.dl_mode = "page"

        status, headers, data = _http_request(host, port, "HEAD", cdn_path or "/cdn/mock-book.pdf")
        check("cdn HEAD 200", status == 200)
        check(
            "cdn HEAD content-length",
            int(headers.get("Content-Length", "0")) == len(state.book),
        )
        check("cdn HEAD accept-ranges", headers.get("Accept-Ranges") == "bytes")
        check("cdn HEAD content-disposition", "mock-book.pdf" in headers.get("Content-Disposition", ""))

        status, headers, data = _http_request(host, port, "GET", cdn_path or "/cdn/mock-book.pdf")
        check("cdn GET 200", status == 200)
        check("cdn GET full bytes", len(data) == len(state.book))

        status, headers, data = _http_request(
            host, port, "GET", cdn_path or "/cdn/mock-book.pdf", {"Range": "bytes=1024-2047"}
        )
        check("cdn range 206", status == 206)
        check("cdn range bytes", len(data) == 1024)
        check(
            "cdn range content-range",
            headers.get("Content-Range", "").startswith("bytes 1024-2047/"),
            headers.get("Content-Range", ""),
        )

        status, headers, data = _http_request(
            host, port, "GET", cdn_path or "/cdn/mock-book.pdf", {"Range": "bytes=%d-" % len(state.book)}
        )
        check("cdn range past EOF 416", status == 416)
        check(
            "cdn 416 content-range",
            headers.get("Content-Range", "") == "bytes */%d" % len(state.book),
            headers.get("Content-Range", ""),
        )

        control = json.dumps(
            {"mode": "first_packet_lost", "count": 1, "first_bytes": 4096}
        ).encode("utf-8")
        status, headers, data = _http_request(
            host, port, "POST", "/__fault", {"Content-Type": "application/json"}, control
        )
        check("fault set 200", status == 200)
        status, headers, data = _http_request(host, port, "GET", cdn_path or "/cdn/mock-book.pdf")
        check("first_packet_lost status 200", status == 200)
        check("first_packet_lost truncated", len(data) == 4096, "got=%d" % len(data))
        status, headers, data = _http_request(
            host, port, "GET", cdn_path or "/cdn/mock-book.pdf", {"Range": "bytes=4096-"}
        )
        check("resume after drop 206", status == 206)
        check("resume after drop bytes", len(data) == len(state.book) - 4096)

        status, headers, data = _http_request(
            host,
            port,
            "POST",
            "/__fault",
            {"Content-Type": "application/json"},
            json.dumps({"mode": "range_416", "count": 1}).encode("utf-8"),
        )
        # Lead m01850：range_416 只对带 Range 的请求注入；无 Range 的 GET 必须正常 200 且不消耗故障。
        status, headers, data = _http_request(host, port, "GET", cdn_path or "/cdn/mock-book.pdf")
        check("range_416 no-range GET 200", status == 200)
        check("range_416 no-range full bytes", len(data) == len(state.book))
        status, headers, data = _http_request(
            host, port, "GET", cdn_path or "/cdn/mock-book.pdf", {"Range": "bytes=%d-" % len(state.book)}
        )
        check("range_416 first range stream 416", status == 416)
        check(
            "range_416 header",
            headers.get("Content-Range", "") == "bytes */%d" % len(state.book),
        )
        status, headers, data = _http_request(host, port, "GET", cdn_path or "/cdn/mock-book.pdf")
        check("range_416 recovers 200", status == 200)
        check("range_416 recovers bytes", len(data) == len(state.book))

        status, headers, data = _http_request(
            host,
            port,
            "POST",
            "/__fault",
            {"Content-Type": "application/json"},
            json.dumps({"mode": "slow", "count": 1, "rate_kbps": 256}).encode("utf-8"),
        )
        began = time.perf_counter()
        status, headers, data = _http_request(host, port, "GET", cdn_path or "/cdn/mock-book.pdf")
        elapsed = time.perf_counter() - began
        check("slow 200", status == 200)
        check("slow full bytes", len(data) == len(state.book))
        check("slow took time", elapsed > 0.3, "elapsed=%.2fs" % elapsed)

        status, headers, data = _http_request(
            host, port, "POST", "/login", {"Content-Type": "application/x-www-form-urlencoded"},
            b"email=test%40example.com&password=secret",
        )
        check("login POST 200", status == 200)
        check("login sets CurrentUser", b"CurrentUser" in data)
        cookies = headers.get("Set-Cookie", "")
        check("login sets cookie", "remix_userid" in cookies, cookies)
        status, headers, data = _http_request(host, port, "GET", "/papi/user/get-downloads")
        check("downloads json logged in", b"\"downloads\"" in data)

        status, headers, data = _http_request(
            host, port, "GET", "/dl/6y7KYwknyQ", {"Cookie": "remix_userid=42; remix_userkey=secret"}
        )
        check("dl with cookie 200", status == 200)
        status, headers, data = _http_request(host, port, "GET", "/__log?tail=20")
        lines = json.loads(data.decode("utf-8")).get("lines", [])
        dl_lines = [e for e in lines if str(e.get("path", "")).startswith("/dl/")]
        check(
            "jsonl cookie_names",
            bool(dl_lines) and "remix_userid" in (dl_lines[-1].get("cookie_names") or []),
            _tail_json(dl_lines[-1] if dl_lines else {}),
        )
        status, headers, data = _http_request(host, port, "GET", "/__stats")
        stats_cookie = json.loads(data.decode("utf-8")).get("stats", {})
        check("stats cookie_requests", stats_cookie.get("cookie_requests", 0) >= 1, _tail_json(stats_cookie))
        check("stats dl_cookie_requests", stats_cookie.get("dl_cookie_requests", 0) >= 1, _tail_json(stats_cookie))

        status, headers, data = _http_request(host, port, "GET", "/__stats")
        stats = json.loads(data.decode("utf-8")).get("stats", {})
        check("stats faults_injected", stats.get("faults_injected", 0) >= 3, _tail_json(stats))
        check("stats cdn_requests", stats.get("cdn_requests", 0) >= 6, _tail_json(stats))
        check("stats range_requests", stats.get("range_requests", 0) >= 2, _tail_json(stats))

        status, headers, data = _http_request(host, port, "POST", "/__reset")
        reset = json.loads(data.decode("utf-8"))
        check("reset fault none", reset.get("fault", {}).get("mode") == "none")
        status, headers, data = _http_request(host, port, "GET", "/__stats")
        stats_after = json.loads(data.decode("utf-8")).get("stats", {})
        check("reset counters cleared", stats_after.get("faults_injected", 0) == 0)
    finally:
        server.shutdown()
        server.server_close()

    failed = [item for item in results if not item[1]]
    print(
        "\nself-test: %d/%d checks passed%s"
        % (len(results) - len(failed), len(results), "" if not failed else ", FAILED: " + ", ".join(f[0] for f in failed)),
        flush=True,
    )
    return 0 if not failed else 1


# ---------------------------------------------------------------- CLI


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="离线确定性 Z-Library 镜像（ParticleBook P1c E2E / 故障注入）"
    )
    parser.add_argument("--host", default="127.0.0.1", help="监听地址（默认 127.0.0.1）")
    parser.add_argument("--port", type=int, default=8899, help="监听端口（默认 8899）")
    parser.add_argument("--fixtures", default=str(DEFAULT_FIXTURES), help="夹具目录")
    parser.add_argument("--log", default=None, help="JSONL 日志路径（默认临时目录 zlib-mock-<port>.jsonl；传 - 关闭）")
    parser.add_argument("--public-base", default=None, help="写进下载页/直链的基址（默认按监听地址生成）")
    parser.add_argument("--book-size", type=int, default=2_000_000, help="mock 文件字节数（默认 2,000,000）")
    parser.add_argument("--book-count", type=int, default=3, help="详情页变体数量（默认 3）")
    parser.add_argument("--book-seed", type=int, default=20261103, help="保留参数（当前 PDF 内容确定）")
    parser.add_argument("--logged-in", action="store_true", help="启动即为已登录态")
    parser.add_argument("--require-login", action="store_true", help="/dl 未登录时返回登录墙")
    parser.add_argument("--email", default=None, help="严格校验的登录邮箱（可选）")
    parser.add_argument("--password", default=None, help="严格校验的登录密码（可选）")
    parser.add_argument("--fault", default="none", choices=FAULT_MODES, help="启动时的故障模式")
    parser.add_argument("--fault-count", type=int, default=1, help="故障注入次数（默认 1）")
    parser.add_argument("--fault-path", default="/cdn/", help="故障生效路径前缀（默认 /cdn/）")
    parser.add_argument("--first-bytes", type=int, default=0, help="first_packet_lost 先发多少字节再断（默认 0）")
    parser.add_argument("--rate-kbps", type=float, default=32.0, help="slow 限速 KB/s（默认 32）")
    parser.add_argument("--stall-ms", type=int, default=0, help="slow 中途停顿毫秒（默认 0）")
    parser.add_argument(
        "--dl-mode",
        "--mode",
        default="page",
        choices=["page", "redirect", "limit", "direct"],
        help="/dl 模式：page=HTML 下载页（默认）、redirect=302 到签名 CDN、limit=HTML 限额页、direct=二进制直出",
    )
    parser.add_argument("--dl-link", default="cdn", choices=["cdn", "go"], help="下载页链接直指 cdn 还是经 /go 302（默认 cdn）")
    parser.add_argument("--countdown", type=int, default=0, help="/dl 前 N 次回倒计时页（默认 0）")
    parser.add_argument("--quiet", action="store_true", help="不打印每请求摘要行")
    parser.add_argument("--self-test", action="store_true", help="纯宿主自测（不依赖手机）")
    parser.add_argument("--version", action="version", version="zlib-mock " + VERSION)
    return parser


def resolve_log_path(args: argparse.Namespace, port: int) -> Path | None:
    if args.log == "-":
        return None
    if args.log:
        return Path(args.log)
    return Path(tempfile.gettempdir()) / ("zlib-mock-%d.jsonl" % port)


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    if not Path(args.fixtures).is_dir():
        print("fixtures dir not found: %s" % args.fixtures, file=sys.stderr)
        return 2
    if args.self_test:
        return run_self_test(args)

    state = MockState(args)
    try:
        server = MockServer((args.host, args.port), state)
    except OSError as exc:
        print("bind %s:%d failed: %s" % (args.host, args.port, exc), file=sys.stderr)
        return 2
    host, port = server.server_address[0], server.server_address[1]
    state.port = port
    state.public_base = (args.public_base or ("http://%s:%d" % (host, port))).rstrip("/")
    state.log_path = resolve_log_path(args, port)
    if not args.quiet:
        print(
            "[mock] zlib-mock %s listening on %s:%d base=%s"
            % (VERSION, host, port, state.public_base),
            flush=True,
        )
        print(
            "[mock] fixtures=%s book=%d bytes sha256=%s fault=%s"
            % (state.fixtures, len(state.book), state.book_sha256[:16], state.fault["mode"]),
            flush=True,
        )
        if state.log_path:
            print("[mock] log=%s" % state.log_path, flush=True)
        print("[mock] device: adb -s 8A9Y0G2XJ reverse tcp:%d tcp:%d" % (port, port), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[mock] shutting down", flush=True)
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
