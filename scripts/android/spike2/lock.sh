#!/usr/bin/env bash
# 互斥锁：lock.sh <build|emu> <cmd...>
# build 锁 = gradle；emu 锁 = adb install/am/logcat/dumpsys/screencap
# 环境变量：
#   LOCK_MAX_WAIT  最多等待多少秒（默认：build 40min / emu 25min，超时报 3）
#   LOCK_HOLDER    持有者标签（写进 owner 文件，默认 bench）
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/env.sh"

usage() { echo "usage: lock.sh <build|emu> <command...>" >&2; exit 2; }
kind="${1:-}"; [ -n "$kind" ] || usage; shift || usage
case "$kind" in
  build) LOCK="$BUILD_LOCK"; TTL_MIN=40 ;;
  emu)   LOCK="$EMU_LOCK";   TTL_MIN=25 ;;
  *) usage ;;
esac
[ "$#" -ge 1 ] || usage
MAX_WAIT="${LOCK_MAX_WAIT:-$((TTL_MIN * 60))}"
HOLDER="${LOCK_HOLDER:-bench}"

deadline=$(( $(date +%s) + MAX_WAIT ))
waited=0
while ! mkdir "$LOCK" 2>/dev/null; do
  # 陈旧锁自愈：owner pid 已死，或锁龄超过 TTL，则强制回收（避免死进程把所有人卡到超时）
  if [ -f "$LOCK/owner" ]; then
    opid=$(sed -n 's/^pid=\([0-9][0-9]*\).*/\1/p' "$LOCK/owner" 2>/dev/null | head -1)
    if [ -n "$opid" ] && ! kill -0 "$opid" 2>/dev/null; then
      echo "[lock] stale lock detected (pid=$opid dead) -> breaking $LOCK"
      rm -rf "$LOCK" 2>/dev/null
      continue
    fi
  fi
  if [ -d "$LOCK" ]; then
    age=$(( $(date +%s) - $(stat -c %Y "$LOCK" 2>/dev/null || date +%s) ))
    if [ "$age" -gt $((TTL_MIN * 60)) ]; then
      echo "[lock] stale lock detected (age ${age}s > TTL) -> breaking $LOCK"
      rm -rf "$LOCK" 2>/dev/null
      continue
    fi
  fi
  if [ "$waited" -eq 0 ]; then
    echo "[lock] $LOCK busy ($(cat "$LOCK/owner" 2>/dev/null | tr '\n' ' ')) -> waiting (max ${MAX_WAIT}s)"
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "[lock] TIMEOUT: $LOCK still held after ${MAX_WAIT}s; owner=$(cat "$LOCK/owner" 2>/dev/null | tr '\n' ' ')" >&2
    exit 3
  fi
  sleep 2; waited=$((waited + 1))
done
printf 'pid=%s holder=%s at=%s cmd=%s cwd=%s\n' "$$" "$HOLDER" "$(date -Iseconds)" "$kind" "$(pwd)" > "$LOCK/owner"
trap 'rm -rf "$LOCK"' EXIT INT TERM HUP
"$@"
