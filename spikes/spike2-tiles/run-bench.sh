#!/usr/bin/env bash
# Runner for app.pb.spike2t (tiles). All adb goes through scripts/android/spike2/lock.sh.
#   bash spikes/spike2-tiles/run-bench.sh [apk] [seconds] [scenario]
#   SCEN: comma list of T1,T2,T3,T4,T5,T6 (empty = all)
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WT="${WT:-/f/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5}"
. "$WT/scripts/android/spike2/env.sh"

# Args are [seconds] [scenario]; a first arg ending in .apk overrides the APK path instead.
# (Getting this wrong installs the *stale* APK and silently benchmarks the previous build.)
APK=""
if [ $# -gt 0 ] && [ "${1%.apk}" != "$1" ]; then APK="$1"; shift; fi
DEFAPK="$HERE/app/build/outputs/apk/debug/app-debug.apk"
if [ -n "$(find "$HERE/app/src" -name '*.kt' -newer "$DEFAPK" 2>/dev/null | head -1)" ]; then
  echo "[run] WARNING: sources are newer than $DEFAPK - rebuild before trusting this run" >&2
fi
[ -z "$APK" ] && APK="$DEFAPK"

wait_device || { echo "[run] device not ready" >&2; exit 5; }

exec bash "$WT/scripts/android/spike2/lock.sh" emu bash "$HERE/bench-body.sh" "$APK" "$@"
