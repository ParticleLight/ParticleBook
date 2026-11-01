#!/usr/bin/env bash
# run-render.sh — S2-A 正式测量入口（app.pb.spike2，全页直渲染/翻页/文本层/内存/dpi/PNG 对照/Surface）
# 用法见 run-app.sh --help；例：
#   bash scripts/android/spike2/run-render.sh --reps 5                 # 全部场景
#   bash scripts/android/spike2/run-render.sh --scenarios S2,S4 --reps 3
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
export PB_APP="spike2"
export PB_PKG="app.pb.spike2"
export PB_COMPONENT="app.pb.spike2/.BenchActivity"
export PB_APK_DEFAULT="spikes/spike2-pdf/app/build/outputs/apk/debug/app-debug.apk"
export PB_TAG_PREFIX="render"
export PB_INNER="_one-app.sh"
export PB_DROP_MODE="${PB_DROP_MODE:-first-per-phase}"
exec bash "$HERE/run-app.sh" "$@"
