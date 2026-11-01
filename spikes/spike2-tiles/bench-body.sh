#!/usr/bin/env bash
# Invoked by run-bench.sh under the emu lock. Args: [apk] [seconds] [scenario]
set -uo pipefail
WT="${WT:-/f/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5}"
. "$WT/scripts/android/spike2/env.sh"

APK="${1:-$WT/spikes/spike2-tiles/app/build/outputs/apk/debug/app-debug.apk}"
SECS="${2:-100}"
SCEN="${3:-}"
TS="$(date +%Y%m%d-%H%M%S)"
OUT="$WT/spikes/spike2-results/raw/tiles-dev-$TS.log"
SHOTS="$WT/spikes/spike2-tiles/screenshots"
mkdir -p "$SHOTS" "$(dirname "$OUT")"

echo "[run] apk=$APK secs=$SECS scen=${SCEN:-ALL} out=$OUT"
adb_ install -r -t "$APK" | tail -2
adb_ shell am force-stop app.pb.spike2t
sleep 1
adb_ logcat -c
# Stream the tags into the log for the whole run instead of dumping the ring buffer afterwards:
# this emulator is shared with four other team members, and any of their scripts calling
# `logcat -c` or `am force-stop` mid-run silently empties a post-hoc `logcat -d`.
adb_ logcat -s PBPERF:V PBTIME:V > "$OUT" 2>&1 &
LOGPID=$!
adb_ logcat -s AndroidRuntime:E > "$OUT.rt" 2>&1 &
RTPID=$!
sleep 2
if [ -n "${SCEN:-}" ]; then
  adb_ shell am start -W -n app.pb.spike2t/.MainActivity --es scenario "$SCEN" | tail -5
else
  adb_ shell am start -W -n app.pb.spike2t/.MainActivity | tail -5
fi
sleep "$SECS"
APP_PID="$(adb_ shell pidof app.pb.spike2t 2>/dev/null | tr -d '\r\n')"
echo "[run] app_pid=${APP_PID:-DEAD} (empty means the app died during the run)"
# exec-out writes the PNG straight to the local file: no /sdcard path argument reaches
# adb.exe, so MSYS does not rewrite it into C:/Program Files/Git/sdcard (run 1's failure).
adb_ exec-out "screencap -p" > "$SHOTS/tiles-$TS.png" 2>/dev/null
echo "[run] shot=$SHOTS/tiles-$TS.png bytes=$(wc -c < "$SHOTS/tiles-$TS.png" 2>/dev/null || echo 0)"
adb_ shell dumpsys meminfo app.pb.spike2t > "$WT/spikes/spike2-results/raw/tiles-meminfo-$TS.txt"
sleep 1
kill "$LOGPID" "$RTPID" 2>/dev/null
wait "$LOGPID" "$RTPID" 2>/dev/null
adb_ shell am force-stop app.pb.spike2t
echo "[run] shot=$SHOTS/tiles-$TS.png"
echo "[run] log=$OUT"
echo "----- PBPERF lines: $(grep -c 'PBPERF' "$OUT" || true)  PBDONE lines: $(grep -c 'PBDONE' "$OUT" || true) -----"
sed -n 's/.*PBPERF|\(.*\)/\1/p; s/.*PBDONE|\(.*\)/DONE|\1/p; s/.*PBTIME|\(.*\)/TIME|\1/p' "$OUT"
echo "----- crashes -----"
tail -30 "$OUT.rt" 2>/dev/null
