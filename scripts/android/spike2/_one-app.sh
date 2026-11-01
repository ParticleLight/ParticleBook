#!/usr/bin/env bash
# _one-app.sh — 单轮（spike2 两个 app 通用）：装 APK -> 冷启 -> 等 PBDONE -> 截图 -> force-stop
# 日志落盘分两个文件再合并（避免 adb logcat 持有写句柄导致结构行丢失，详见 runlib.sh 注释）：
#   raw/<run_id>.log.struct  脚本自己写的 RUNENV/AMSTART/RUNMETA …
#   raw/<run_id>.log.rt      全量 logcat 流（含 app 的 PBPERF 合约行）
#   收尾合并成 raw/<run_id>.log（struct 在前，logcat 在后；parse.py 只读这个合并文件）
# 必须在 lock.sh emu 内执行；环境变量由 run-app.sh 导出。
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/env.sh"
. "$HERE/runlib.sh"
cd "$WT"

: "${PB_TAG_PREFIX:?}" "${PB_APP:?}" "${PB_PKG:?}" "${PB_COMPONENT:?}" "${PB_APK:?}"
PB_REP="${PB_REP:-0}"
PB_SCENARIOS="${PB_SCENARIOS:-}"
PB_TIMEOUT="${PB_TIMEOUT:-240}"
PB_SCREENSHOT="${PB_SCREENSHOT:-0}"
export REP="$PB_REP" APP="$PB_APP"

stamp="$(ts_stamp)"
run_id="${PB_TAG_PREFIX}-run${PB_REP}-${stamp}"
base="$RESULT_DIR/raw/$run_id"
STRUCT="$base.log.struct"
MAN="$RESULT_DIR/raw/${PB_TAG_PREFIX}-manifest.tsv"
mkdir -p "$RESULT_DIR/raw"

wait_device || { echo "[run] device $SERIAL not ready" >&2; exit 1; }

cleanup() { stop_logcat_stream; force_stop "$PB_PKG"; }
trap cleanup EXIT INT TERM

finish() {
  local rc="$1" miss="" check="" rawfile
  stop_logcat_stream
  # 三道断言（Lead 20:31）：时间窗 / PID 唯一 / 结构行。失败 => 该 rep 作废（改名 .log.INVALID，parse 不匹配）
  check=$(python scripts/android/spike2/check_raw.py \
    --rt "$base.log.rt" --struct "$STRUCT" \
    --dev-year "${DEV_YEAR:-2026}" --dev-epoch "${DEV_EPOCH:-0}" \
    --min-records 1 2>&1 | tail -1 || true)
  [ -n "$check" ] || check='CHECK|result=FAIL|fail=checker_error'
  printf 'RUNMETA|phase=assert|%s\n' "$check" >> "$STRUCT"
  merge_raw_logs "$base.log"
  rawfile="$(basename "$base.log")"
  if ! printf '%s' "$check" | grep -q 'result=PASS'; then
    [ "$rc" -eq 0 ] && rc=7
    mv "$base.log" "$base.log.INVALID" 2>/dev/null || true
    rawfile="$rawfile.INVALID"
    echo "[run] WARN: 断言失败，raw 作废 -> $rawfile" >&2
  fi
  grep -q '^RUNENV|' "$STRUCT" 2>/dev/null || miss="${miss}RUNENV,"
  grep -q '^AMSTART|' "$STRUCT" 2>/dev/null || miss="${miss}AMSTART,"
  grep -q '^RUNMETA|' "$STRUCT" 2>/dev/null || miss="${miss}RUNMETA,"
  [ -n "$miss" ] && echo "[run] WARN: raw 缺少结构行 ${miss%,*}（见 $STRUCT）" >&2
  printf '%s\t%s\t%s\t%s\n' "$PB_REP" "$(ts_iso)" "$rc" "$rawfile" >> "$MAN"
  echo "[run] rep=$PB_REP rc=$rc raw=$rawfile lines=$(wc -l < "$STRUCT" 2>/dev/null || echo 0)+$(wc -l < "$base.log.rt" 2>/dev/null || echo 0) $check"
  exit "$rc"
}

install_apk "$PB_APK" || { echo "[run] install failed: $PB_APK" >&2; finish 5; }

# RUNENV 头先落盘：即使后续超时/崩溃，也留下本轮标识与环境
runenv_line "$run_id" "$PB_APP" "$PB_APK" "$PB_SCENARIOS" > "$STRUCT"

adb_ shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb_ shell wm dismiss-keyguard >/dev/null 2>&1 || true
force_stop "$PB_PKG"

# 三道断言需要的设备起始时刻（时间窗断言基线），先写进 struct
clk="$(device_clock)"; DEV_EPOCH="${clk%%|*}"; DEV_YEAR="${clk##*|}"
printf 'RUNMETA|phase=clock|dev_epoch=%s|dev_year=%s\n' "$DEV_EPOCH" "$DEV_YEAR" >> "$STRUCT"

# 清空 logcat（正式集要求；同一时刻只允许一个持 emu 锁的 run，因此清空不会毁别人的数据）
if ! clear_logcat "$PB_PKG"; then
  printf 'RUNMETA|phase=logcatclean|ok=0\n' >> "$STRUCT"
  finish 6
fi
printf 'RUNMETA|phase=logcatclean|ok=1\n' >> "$STRUCT"

start_logcat_stream "$base.log" "$DEV_EPOCH" || true
sleep 1

if [ -n "$PB_SCENARIOS" ]; then
  am_start_cold "$PB_COMPONENT" --es scenario "$PB_SCENARIOS" | tee -a "$STRUCT" >/dev/null
else
  am_start_cold "$PB_COMPONENT" | tee -a "$STRUCT" >/dev/null
fi

rc=0
if [ -n "$PB_SCENARIOS" ]; then
  wait_pbdone "$PB_SCENARIOS" "$PB_TIMEOUT" || rc=2
else
  wait_log PBPERF 'PBDONE\|' "$PB_TIMEOUT" || rc=2
fi
# 收尾：等日志安静 3 s，避免抓漏最后几行
wait_quiet PBPERF 3 60 'PBPERF\|' || true
sleep 1

if app_running "$PB_PKG"; then
  printf 'RUNMETA|phase=alive|alive=1|rep=%s\n' "$PB_REP" >> "$STRUCT"
else
  printf 'RUNMETA|phase=alive|alive=0|rep=%s\n' "$PB_REP" >> "$STRUCT"
  [ "$rc" -eq 0 ] && rc=3
fi

if [ "$PB_SCREENSHOT" = "1" ]; then
  adb_ exec-out screencap -p > "$base.png" 2>/dev/null || true
fi

finish "$rc"
