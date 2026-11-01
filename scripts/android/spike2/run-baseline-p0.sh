#!/usr/bin/env bash
# run-baseline-p0.sh — 同批次 P0 基线（app.particlebook，旧链路 render + PNG 编码 + 落盘）
#   冷启 -> 等书架数据 -> 打开第二本书（PDF：统计之美；默认 CDP 真实 onClick，回退 adb input tap）
#   -> 滚动触发第 4 页 -> 抓 PBPdf 的 pdf_open / pdf_page 行
# 与 spike2 的 S9（同批次 legazy 路径）一起构成「PNG 路径 vs 直上屏」的同批次对照。
# 注：P0 APK 的 assets/fixtures.json 只挂 seed-03.pdf 一本 PDF；realbook.pdf 无法在不改 APK 的前提下加载，
#     故 realbook 的同批次对照走 S9（见 docs/android-port/spike2-data.md 口径说明）。
# 用法见 run-app.sh --help；例：
#   bash scripts/android/spike2/run-baseline-p0.sh --reps 5
#   PB_P0_MODE=tap bash scripts/android/spike2/run-baseline-p0.sh --reps 3 --tap-x 660 --tap-y 700
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
export PB_APP="particlebook"
export PB_PKG="app.particlebook"
export PB_COMPONENT="app.particlebook/.android.MainActivity"
export PB_APK_DEFAULT="android/app/build/outputs/apk/debug/app-debug.apk"
export PB_TAG_PREFIX="baseline-p0"
export PB_INNER="_one-p0.sh"
# P0 每次冷启只打开 PDF 一次，首条 pdf_page 就是被测样本，不能按 warmup 剔除
export PB_DROP_MODE="${PB_DROP_MODE:-none}"
# 打开 PDF 的方式：cdp（默认，WebView devtools 真实 React onClick）/ tap（adb input tap）
export PB_P0_MODE="${PB_P0_MODE:-cdp}"
export PB_BOOK_ID="${PB_BOOK_ID:-2}"
export PB_CDP_PORT="${PB_CDP_PORT:-9333}"
export PB_TAP_WAIT="${PB_TAP_WAIT:-40}"
export PB_REPS="${PB_REPS:-5}"
export PB_TIMEOUT="${PB_TIMEOUT:-150}"
export PB_SCREENSHOT="${PB_SCREENSHOT:-1}"
exec bash "$HERE/run-app.sh" "$@"
