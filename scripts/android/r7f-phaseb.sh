#!/usr/bin/env bash
# R7-F Phase B 离线 mock 验收编排（ui-verify）
# 用法：
#   bash scripts/android/r7f-phaseb.sh status
#   bash scripts/android/r7f-phaseb.sh setup
#   bash scripts/android/r7f-phaseb.sh mock page|limit|require-login [book_count] [book_size]
#   bash scripts/android/r7f-phaseb.sh fault none|slow|first_packet_lost ...
#   bash scripts/android/r7f-phaseb.sh reset / shot NAME / ui / tap X Y / text S / start
#   bash scripts/android/r7f-phaseb.sh teardown
set -uo pipefail
export MSYS_NO_PATHCONV=1 PYTHONIOENCODING=utf-8
ADB=${ADB:-/g/Android/sdk/platform-tools/adb.exe}
SER=${SER:-8A9Y0G2XJ}
PORT=${PORT:-8898}
OUT=${OUT:-/f/tmp/verify/r7f3}
APK_MD5_EXPECT=${APK_MD5_EXPECT:-1c531ffa2d0f4626cb0c702323ef82f9}
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MOCK_PID_FILE="$OUT/mock.pid"
mkdir -p "$OUT"

dev() { "$ADB" -s "$SER" "$@"; }
mock_up() { curl -s -m 2 "http://127.0.0.1:$PORT/__health" >/dev/null 2>&1; }
mock_pid() {
  if [ -s "$MOCK_PID_FILE" ]; then cat "$MOCK_PID_FILE"; return; fi
  netstat -ano 2>/dev/null | grep -E "127.0.0.1:$PORT .*LISTENING" | awk '{print $NF}' | head -1
}

start_mock() { # mode book_count book_size
  local mode="${1:-page}" books="${2:-3}" size="${3:-2000000}"
  mock_up && { echo "[mock] already up (reuse)"; return 0; }
  cd "$ROOT"
  python scripts/android/r7f-mockd.py start --mode "$mode" --books "$books" --size "$size" --port "$PORT" --out "F:/tmp/verify/r7f3" || return 1
}

stop_mock() {
  cd "$ROOT"
  python scripts/android/r7f-mockd.py stop --port "$PORT" --out "F:/tmp/verify/r7f3"
  rm -f "$MOCK_PID_FILE"
  echo "[mock] stopped"
}

case "${1:-}" in
  status)
    echo "== time: $(date '+%F %T')"
    echo "== mock:"; curl -s -m 2 "http://127.0.0.1:$PORT/__health" | head -c 400; echo
    echo "== fault:"; curl -s -m 2 "http://127.0.0.1:$PORT/__fault"; echo
    echo "== stats:"; curl -s -m 2 "http://127.0.0.1:$PORT/__stats" | head -c 300; echo
    echo "== device:"; "$ADB" devices | tail -2
    echo "== app pkg: $(dev shell 'dumpsys package app.particlebook | grep -E "lastUpdateTime"')"
    echo "== pid: $(dev shell pidof app.particlebook)"
    echo "== fg:  $(dev shell 'dumpsys activity activities | grep mResumedActivity')"
    echo "== display: $(dev shell wm size) / $(dev shell wm density)"
    echo "== font/rot/proxy: $(dev shell 'settings get system font_scale; settings get system accelerometer_rotation; settings get system user_rotation; settings get global http_proxy' | tr '\n' ' ')"
    echo "== reverse: $(dev reverse --list)"
    ;;
  mock)
    stop_mock; start_mock "${2:-page}" "${3:-3}" "${4:-2000000}"
    ;;
  fault)
    case "${2:-none}" in
      none) curl -s -m 3 -X POST "http://127.0.0.1:$PORT/__fault" -d '{"mode":"none"}' ;;
      slow) curl -s -m 3 -X POST "http://127.0.0.1:$PORT/__fault" -d "{\"mode\":\"slow\",\"count\":${4:-1},\"rate_kbps\":${3:-200},\"stall_ms\":${5:-0}}" ;;
      first_packet_lost) curl -s -m 3 -X POST "http://127.0.0.1:$PORT/__fault" -d "{\"mode\":\"first_packet_lost\",\"count\":${3:-1},\"first_bytes\":${4:-0}}" ;;
      *) echo "usage: fault none|slow|first_packet_lost" >&2; exit 2 ;;
    esac
    echo
    ;;
  reset)
    curl -s -m 3 "http://127.0.0.1:$PORT/__reset"; echo
    ;;
  setup)
    mock_up || start_mock page 3 2000000 || exit 1
    dev reverse "tcp:$PORT" "tcp:$PORT" >/dev/null
    dev shell settings put global http_proxy :0
    dev shell settings put system font_scale 1.0
    dev shell settings put system accelerometer_rotation 0
    dev shell settings put system user_rotation 0
    dev logcat -c 2>/dev/null
    dev shell am force-stop app.particlebook
    sleep 1
    dev shell am start -n app.particlebook/.android.MobileActivity --es pb_zlib_mirror "http://127.0.0.1:$PORT"
    sleep 4
    echo "[setup] pid=$(dev shell pidof app.particlebook)"
    IMP=$(dev shell pm path app.particlebook | sed 's/package://' | tr -d '\r')
    IMD5=$(dev shell "run-as app.particlebook md5sum $IMP" 2>/dev/null | awk '{print $1}')
    echo "[setup] installed md5=$IMD5 expect=$APK_MD5_EXPECT"
    if [ "$IMD5" = "$APK_MD5_EXPECT" ]; then echo "[setup] md5 MATCH"; else echo "[setup] md5 MISMATCH -> 以设备实测为准并记入报告"; fi
    dev logcat -d -v brief 2>/dev/null | grep -aE 'debug_mirror_override|cold_start|library_load' | tail -5
    ;;
  teardown)
    dev shell am force-stop app.particlebook
    dev reverse --remove "tcp:$PORT" >/dev/null 2>&1
    stop_mock
    echo "[teardown] app stopped, reverse removed, mock stopped"
    ;;
  shot)
    dev exec-out screencap -p > "$OUT/${2:-shot}.png" && echo "$OUT/${2:-shot}.png $(stat -c %s "$OUT/${2:-shot}.png" 2>/dev/null) bytes"
    ;;
  ui)
    dev exec-out uiautomator dump /dev/tty > "$OUT/${2:-ui}.xml" 2>/dev/null
    python "$ROOT/scripts/android/ui-shot.py" ui 2>&1 | head -80
    ;;
  tap)
    dev shell input tap "$2" "$3"; echo "tap $2,$3"
    ;;
  text)
    dev shell input text "$2"; dev shell input keyevent 66; echo "text $2 + enter"
    ;;
  start)
    dev shell am start -n app.particlebook/.android.MobileActivity --es pb_zlib_mirror "http://127.0.0.1:$PORT" >/dev/null
    echo "started"
    ;;
  installed-md5)
    p=$(dev shell pm path app.particlebook | sed 's/package://' | tr -d '\r')
    dev shell "run-as app.particlebook md5sum $p" 2>/dev/null | awk '{print $1}'
    ;;
  *)
    sed -n '3,12p' "$0"; exit 2 ;;
esac
