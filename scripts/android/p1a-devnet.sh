#!/usr/bin/env bash
# P1a 开发期真机网络：把电脑的本地代理（默认 127.0.0.1:7897）通过 USB reverse 桥进手机，并设系统代理。
# 用法：bash scripts/android/p1a-devnet.sh [on|off|status]
set -u
source "$(dirname "$0")/spike2/env.sh"
SERIAL="${SERIAL_OVERRIDE:-8A9Y0G2XJ}"
PORT="${PROXY_PORT:-7897}"
cmd="${1:-status}"
case "$cmd" in
  on)
    "$ADB" -s "$SERIAL" reverse "tcp:$PORT" "tcp:$PORT" || exit 1
    "$ADB" -s "$SERIAL" shell settings put global http_proxy "127.0.0.1:$PORT"
    echo "[devnet] reverse tcp:$PORT + global http_proxy=127.0.0.1:$PORT (serial $SERIAL)"
    "$ADB" -s "$SERIAL" reverse --list
    ;;
  off)
    "$ADB" -s "$SERIAL" reverse --remove "tcp:$PORT" 2>/dev/null || true
    "$ADB" -s "$SERIAL" shell settings put global http_proxy :0
    echo "[devnet] cleared reverse + http_proxy"
    ;;
  status)
    echo "reverse:"; "$ADB" -s "$SERIAL" reverse --list
    echo "http_proxy: $("$ADB" -s "$SERIAL" shell settings get global http_proxy | tr -d '\r')"
    ;;
  *) echo "usage: $0 [on|off|status]"; exit 2;;
esac
