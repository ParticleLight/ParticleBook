#!/usr/bin/env bash
# M0 EPUB 设备探针驱动（debug / benchmark APK）。
# 用法：bash scripts/android/m0-probe.sh <local.epub> [chapterIndex] [serial]
# 环境：
#   M0_FORCE_STOP=0  热启动（默认 1 冷启动前 force-stop）
#   M0_MODE=auto|internal|external  默认 auto：debug 包用 run-as，benchmark 包用外部目录
set -euo pipefail
# Git Bash 会把 /data/... 当 Windows 路径转换；所有 adb shell 参数保持原样。
export MSYS_NO_PATHCONV=1
PKG="app.particlebook"
ACT="${PKG}/app.particlebook.android.debug.M0ProbeActivity"
EPUB="${1:-}"
CHAPTER="${2:-0}"
SERIAL="${3:-${ANDROID_SERIAL:-8A9Y0G2XJ}}"
if [ -z "$EPUB" ] || [ ! -f "$EPUB" ]; then
  echo "usage: $0 <local.epub> [chapterIndex] [serial]" >&2
  exit 2
fi
ADB=(adb -s "$SERIAL")
"${ADB[@]}" get-state >/dev/null

MODE="${M0_MODE:-auto}"
if [ "$MODE" = "auto" ]; then
  if "${ADB[@]}" shell run-as "$PKG" true >/dev/null 2>&1; then
    MODE="internal"
  else
    MODE="external"
  fi
fi

if [ "$MODE" = "external" ]; then
  REMOTE="/sdcard/Android/data/${PKG}/files/m0/book.epub"
  RESULT_PATH="/sdcard/Android/data/${PKG}/files/m0-result.json"
  "${ADB[@]}" shell mkdir -p "/sdcard/Android/data/${PKG}/files/m0"
  echo "[m0] mode=external push $EPUB"
  "${ADB[@]}" push "$EPUB" "$REMOTE" >/dev/null
  "${ADB[@]}" shell rm -f "$RESULT_PATH"
else
  TMP="/data/local/tmp/m0-$$.epub"
  REMOTE="/data/user/0/${PKG}/files/m0/book.epub"
  RESULT_PATH="files/m0-result.json"
  echo "[m0] mode=internal push $EPUB"
  "${ADB[@]}" push "$EPUB" "$TMP" >/dev/null
  "${ADB[@]}" shell run-as "$PKG" mkdir -p files/m0
  "${ADB[@]}" shell run-as "$PKG" cp "$TMP" "$REMOTE"
  "${ADB[@]}" shell rm -f "$TMP"
  "${ADB[@]}" shell run-as "$PKG" rm -f files/m0-result.json
fi

"${ADB[@]}" logcat -c
echo "[m0] start chapter=$CHAPTER"
if [ "${M0_FORCE_STOP:-1}" = "1" ]; then
  "${ADB[@]}" shell am force-stop "$PKG"
fi
"${ADB[@]}" shell am start -W -n "$ACT" --es epub "$REMOTE" --ei chapter "$CHAPTER" >/dev/null

RESULT=""
for _ in $(seq 1 90); do
  RESULT="$("${ADB[@]}" logcat -d -s PBM0:I '*:S' 2>/dev/null | grep 'RESULT ' | tail -1 || true)"
  if [ -n "$RESULT" ]; then break; fi
  sleep 1
done
echo "[m0] logcat: $RESULT"
if [ "$MODE" = "external" ]; then
  JSON="$("${ADB[@]}" shell cat "$RESULT_PATH" 2>/dev/null || true)"
else
  JSON="$("${ADB[@]}" shell run-as "$PKG" cat files/m0-result.json 2>/dev/null || true)"
fi
if [ -n "$JSON" ]; then
  echo "[m0] json: $JSON"
else
  echo "[m0] result json missing" >&2
fi
if [ -z "$RESULT" ] && [ -z "$JSON" ]; then exit 1; fi
