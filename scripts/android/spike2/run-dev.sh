#!/usr/bin/env bash
# 快速冒烟：装 APK -> 清日志 -> 起 Activity -> 等 N 秒 -> 抓 PBPERF 日志
#   bash scripts/android/spike2/run-dev.sh <apk> <pkg/.Activity> [seconds] [scenario]
# 全程持 emu 锁。正式基准请用 pdf-bench 的 run-*.sh。
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/env.sh"

APK="${1:?usage: run-dev.sh <apk> <pkg/.Activity> [seconds] [scenario]}"
ACT="${2:?activity required}"
SECS="${3:-20}"
SCEN="${4:-}"
PKG="${ACT%%/*}"
export APK ACT SECS SCEN PKG

body() {
  adb_ install -r -t "$APK" | tail -1
  adb_ shell am force-stop "$PKG"
  adb_ logcat -c
  if [ -n "$SCEN" ]; then
    adb_ shell am start -W -n "$ACT" --es scenario "$SCEN"
  else
    adb_ shell am start -W -n "$ACT"
  fi
  sleep "$SECS"
  echo "--- PBPERF ---"
  adb_ logcat -d -s PBPERF:V | sed -n 's/.*PBPERF|\(.*\)/\1/p; s/.*PBDONE|\(.*\)/DONE|\1/p' | head -400
  echo "--- CRASH? ---"
  adb_ logcat -d -s AndroidRuntime:E | tail -20
}
bash "$HERE/lock.sh" emu bash -c "$(declare -f body adb_); body"