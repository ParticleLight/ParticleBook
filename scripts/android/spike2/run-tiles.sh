#!/usr/bin/env bash
# run-tiles.sh — S2-B 正式测量入口（app.pb.spike2t，tile/pan/zoom/缓存/Surface tile）
# 用法见 run-app.sh --help；例：
#   bash scripts/android/spike2/run-tiles.sh --reps 5
#   bash scripts/android/spike2/run-tiles.sh --scenarios T1,T3,T4 --reps 3
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
export PB_APP="spike2t"
export PB_PKG="app.pb.spike2t"
export PB_COMPONENT="app.pb.spike2t/.MainActivity"
export PB_APK_DEFAULT="spikes/spike2-tiles/app/build/outputs/apk/debug/app-debug.apk"
export PB_TAG_PREFIX="tiles"
export PB_INNER="_one-app.sh"
export PB_DROP_MODE="${PB_DROP_MODE:-first-per-phase}"
exec bash "$HERE/run-app.sh" "$@"
