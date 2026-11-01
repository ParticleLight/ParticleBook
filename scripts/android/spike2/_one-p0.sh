#!/usr/bin/env bash
# _one-p0.sh — 单轮 P0 基线（app.particlebook，旧链路 render -> PNG -> 落盘 -> WebView）
#   装 APK -> 冷启 -> 等书架数据（PBBridge INVOKE db:getBooks）-> 等 2 张封面 -> 截图
#   -> 打开 PDF 书（默认 CDP 真实 React onClick，回退 adb input tap）
#   -> 等 PBPdf pdf_open -> 滚动触发第 4 页 -> 日志安静 -> 截图 -> force-stop
# 日志落盘：raw/<run_id>.log.struct（结构行）+ raw/<run_id>.log.rt（logcat），收尾合并成 .log
# 必须在 lock.sh emu 内执行；环境变量由 run-app.sh 导出。
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/env.sh"
. "$HERE/runlib.sh"
cd "$WT"

: "${PB_TAG_PREFIX:?}" "${PB_APP:?}" "${PB_PKG:?}" "${PB_COMPONENT:?}" "${PB_APK:?}"
PB_REP="${PB_REP:-0}"
PB_TIMEOUT="${PB_TIMEOUT:-150}"
PB_SCREENSHOT="${PB_SCREENSHOT:-1}"
PB_TAP_X="${PB_TAP_X:-660}"
PB_TAP_Y="${PB_TAP_Y:-700}"
PB_TAP_WAIT="${PB_TAP_WAIT:-40}"
# 打开 PDF 的方式：cdp（默认，WebView devtools 真实 onClick）/ tap（adb input tap，易被长按菜单挡）
PB_P0_MODE="${PB_P0_MODE:-cdp}"
PB_BOOK_ID="${PB_BOOK_ID:-2}"          # fixtures: id=2 是 PDF《统计之美》
PB_CDP_PORT="${PB_CDP_PORT:-9333}"
export REP="$PB_REP" APP="$PB_APP"

stamp="$(ts_stamp)"
run_id="${PB_TAG_PREFIX}-run${PB_REP}-${stamp}"
base="$RESULT_DIR/raw/$run_id"
STRUCT="$base.log.struct"
MAN="$RESULT_DIR/raw/${PB_TAG_PREFIX}-manifest.tsv"
mkdir -p "$RESULT_DIR/raw"

wait_device || { echo "[run] device $SERIAL not ready" >&2; exit 1; }

cdp_cleanup() {
  [ -n "${PB_CDP_PORT:-}" ] && adb_ forward --remove "tcp:$PB_CDP_PORT" >/dev/null 2>&1 || true
}
cleanup() { stop_logcat_stream; cdp_cleanup; force_stop "$PB_PKG"; }
trap cleanup EXIT INT TERM

finish() {
  local rc="$1" miss="" check="" rawfile
  stop_logcat_stream
  cdp_cleanup
  # 三道断言（Lead 20:31）：时间窗 / PID 唯一 / 结构行；失败 => 改名 .log.INVALID（parse 不匹配）
  check=$(python scripts/android/spike2/check_raw.py \
    --rt "$base.log.rt" --struct "$STRUCT" \
    --dev-year "${DEV_YEAR:-2026}" --dev-epoch "${DEV_EPOCH:-0}" \
    --tags PBPdf --min-records 2 2>&1 | tail -1 || true)
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

runenv_line "$run_id" "$PB_APP" "$PB_APK" "cdp,pdf" > "$STRUCT"

adb_ shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb_ shell wm dismiss-keyguard >/dev/null 2>&1 || true
force_stop "$PB_PKG"

clk="$(device_clock)"; DEV_EPOCH="${clk%%|*}"; DEV_YEAR="${clk##*|}"
printf 'RUNMETA|phase=clock|dev_epoch=%s|dev_year=%s\n' "$DEV_EPOCH" "$DEV_YEAR" >> "$STRUCT"
if ! clear_logcat "$PB_PKG"; then
  printf 'RUNMETA|phase=logcatclean|ok=0\n' >> "$STRUCT"
  finish 6
fi
printf 'RUNMETA|phase=logcatclean|ok=1\n' >> "$STRUCT"

start_logcat_stream "$base.log" "$DEV_EPOCH" || true
sleep 1

am_start_cold "$PB_COMPONENT" | tee -a "$STRUCT" >/dev/null

# 等书架数据到达（P0 桥接层每次 invoke 都有 INVOKE <method> <ms> 日志）
if ! wait_log PBBridge 'INVOKE db:getBooks' 90; then
  printf 'RUNMETA|phase=books|ok=0\n' >> "$STRUCT"
  force_stop "$PB_PKG"
  finish 2
fi
printf 'RUNMETA|phase=books|ok=1\n' >> "$STRUCT"

# 再等 2 张封面（book:cover）出现再点，避免点在一半渲染的卡片上
wait_log_count PBBridge 'INVOKE book:cover' 2 60 || true
sleep 6

adb_ exec-out screencap -p > "$base.shelf.png" 2>/dev/null || true

opened=0
if [ "$PB_P0_MODE" = "cdp" ]; then
  sock=$(adb_ shell cat /proc/net/unix 2>/dev/null | grep -o 'webview_devtools_remote_[0-9]*' | head -1 | tr -d '\r')
  if [ -n "$sock" ]; then
    cdp_cleanup
    adb_ forward "tcp:$PB_CDP_PORT" "localabstract:$sock" >/dev/null 2>&1 || true
    printf 'CDP|phase=forward|socket=%s|port=%s\n' "$sock" "$PB_CDP_PORT" >> "$STRUCT"
    cdp_out=$(python "$(cygpath -w "$HERE/p0-cdp.py")" "$PB_CDP_PORT" open "$PB_BOOK_ID" 2>&1 | tr -d '\r' | tail -3 | tr '\n' ' ' || true)
    printf 'CLICK|mode=cdp|book=%s|out=%s\n' "$PB_BOOK_ID" "${cdp_out// /_}" >> "$STRUCT"
    case "$cdp_out" in *CLICKED*) opened=1 ;; esac
  else
    printf 'CDP|phase=forward|socket=none\n' >> "$STRUCT"
  fi
fi

if [ "$opened" -eq 0 ]; then
  adb_ shell input tap "$PB_TAP_X" "$PB_TAP_Y" >/dev/null 2>&1
  printf 'TAP|rep=%s|x=%s|y=%s|ts=%s\n' "$PB_REP" "$PB_TAP_X" "$PB_TAP_Y" "$(ts_iso)" >> "$STRUCT"
fi

# 等 P0 真正开始渲染 PDF
if ! wait_log PBPdf 'pdf_open' "$PB_TAP_WAIT"; then
  printf 'RUNMETA|phase=pdf_open|ok=0\n' >> "$STRUCT"
  adb_ exec-out screencap -p > "$base.tapfail.png" 2>/dev/null || true
  force_stop "$PB_PKG"
  finish 2
fi
printf 'RUNMETA|phase=pdf_open|ok=1|open_mode=%s\n' "$([ "$opened" -eq 1 ] && echo cdp || echo tap)" >> "$STRUCT"

# P0 阅读器 4 页：page0/1/2 打开后自动排队，page3 需滚动到第 4 页附近才渲染
if [ "$PB_P0_MODE" = "cdp" ] && [ "$opened" -eq 1 ]; then
  scroll_out=$(python "$(cygpath -w "$HERE/p0-cdp.py")" "$PB_CDP_PORT" scroll 0.95 2>&1 | tr -d '\r' | tail -1 || true)
  printf 'SCROLL|phase=page3|ok=1|out=%s\n' "${scroll_out// /_}" >> "$STRUCT"
fi

# 等 PBPdf 行数安静下来（P0 无 PBDONE；连续 5 s 不增长视为跑完）
wait_quiet PBPdf 5 "$PB_TIMEOUT" '.' || true
sleep 1

if [ "$PB_SCREENSHOT" = "1" ]; then
  adb_ exec-out screencap -p > "$base.reader.png" 2>/dev/null || true
fi

# 复核：读 logcat 流本体（合并还没发生）
if ! grep -q 'pdf_open' "$base.log.rt" 2>/dev/null; then
  finish 2
fi
finish 0
