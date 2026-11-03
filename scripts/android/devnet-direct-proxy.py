#!/usr/bin/env python3
"""开发期直连 HTTP 代理（CONNECT 隧道 + 普通 HTTP 转发）。

用途：真机测试时手机没有自己的出口（Wi-Fi 未连、自装 VPN 无上游），
用 `adb reverse tcp:7897 tcp:7897` 把手机的 127.0.0.1:7897 反向映射到本机，
本脚本在本机直连目标站点（不走任何上游代理），手机侧因此获得网络。

    python scripts/android/devnet-direct-proxy.py [--port 7897]

与 Clash 的区别：不做规则分流、不做 UDP，只把 TCP 直通。需要分流时改用
`scripts/android/p1a-devnet.sh on`（走系统代理 127.0.0.1:7897 的 Clash）。
"""
import argparse
import select
import socket
import threading
from urllib.parse import urlsplit

BUFSIZE = 65536


def pipe(a: socket.socket, b: socket.socket) -> None:
    sent = 0
    recv = 0
    try:
        while True:
            readable, _, _ = select.select([a, b], [], [], 120)
            if not readable:
                continue
            for src in readable:
                data = src.recv(BUFSIZE)
                if not data:
                    return
                if src is a:
                    recv += len(data)
                else:
                    sent += len(data)
                (b if src is a else a).sendall(data)
    except OSError as exc:
        print(f"  pipe error: {exc}", flush=True)
    finally:
        print(f"  pipe closed (up={recv}B down={sent}B)", flush=True)
        for s in (a, b):
            try:
                s.close()
            except OSError:
                pass


def handle(conn: socket.socket) -> None:
    try:
        conn.settimeout(30)
        buf = b""
        while b"\r\n\r\n" not in buf:
            chunk = conn.recv(4096)
            if not chunk:
                conn.close()
                return
            buf += chunk
        head, rest = buf.split(b"\r\n\r\n", 1)
        lines = head.split(b"\r\n")
        method, target, version = lines[0].split(b" ", 2)
        print(f"{method.decode('ascii', 'replace')} {target.decode('ascii', 'replace')}", flush=True)
        if method.upper() == b"CONNECT":
            host_raw, _, port_raw = target.rpartition(b":")
            upstream = socket.create_connection(
                (host_raw.decode("idna"), int(port_raw)), timeout=30
            )
            conn.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            if rest:
                upstream.sendall(rest)
            pipe(conn, upstream)
            return
        url = urlsplit(target.decode())
        if not url.hostname:
            conn.close()
            return
        upstream = socket.create_connection(
            (url.hostname, url.port or 80), timeout=30
        )
        path = url.path or "/"
        if url.query:
            path += "?" + url.query
        new_head = [b" ".join([method, path.encode(), version])] + lines[1:]
        upstream.sendall(b"\r\n".join(new_head) + b"\r\n\r\n" + (rest or b""))
        pipe(conn, upstream)
    except Exception as exc:  # noqa: BLE001 - 代理不因单连接失败退出
        print(f"  handle error: {type(exc).__name__}: {exc}", flush=True)
        try:
            conn.close()
        except OSError:
            pass


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=7897)
    ap.add_argument("--host", default="127.0.0.1")
    args = ap.parse_args()
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((args.host, args.port))
    srv.listen(128)
    print(f"direct proxy listening on {args.host}:{args.port}", flush=True)
    while True:
        client, _ = srv.accept()
        threading.Thread(target=handle, args=(client,), daemon=True).start()


if __name__ == "__main__":
    main()
