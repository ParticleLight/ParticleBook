#!/usr/bin/env bash
# runlib.sh — spike2 跑法共用函数（source 用；必须先 source env.sh）
# 约定：raw 文件名 <tag-prefix>-run<N>-<YYYYmmdd-HHMMSS>.log；行内结构行 RUNENV|/AMSTART|/RUNMETA|/TAP|
# 本文件只被 run-*.sh 与 _one-*.sh 引用，不单独执行。
#
# 日志采集：共享设备上不再 logcat -c（会清掉别人的 buffer），改为启动前
#   adb logcat -v threadtime >> <raw.log> &
# 流式落盘，跑完 kill 掉（见 start_logcat_stream / stop_logcat_stream）。

# ---------- 时间 ----------
ts_iso()   { date +%Y-%m-%dT%H:%M:%S%z; }
ts_stamp() { date +%Y%m%d-%H%M%S; }

# ---------- 设备 / 进程 ----------
app_pid() { adb_ shell pidof "$1" 2>/dev/null | tr -d '\r' | head -1; }
app_running() { [ -n "$(app_pid "$1")" ]; }
force_stop() { adb_ shell am force-stop "$1" >/dev/null 2>&1 || true; }

# ---------- logcat 清空（正式集数据完整性要求；Lead 20:31）----------
# clear_logcat [pkg]：三缓冲全清 + 读回 main 缓冲必须 0 行；不 0 就 force-stop pkg 后重试，最多 3 次。
# 返回 0 = 已清空；1 = 3 次后 main 仍有残留（该 rep 不可信）。
clear_logcat() {
  local pkg="${1:-}" i=0 n=0
  while [ "$i" -lt 3 ]; do
    "$ADB" -s "$SERIAL" logcat -b main -b system -b crash -c >/dev/null 2>&1 || true
    sleep 0.5
    n=$("$ADB" -s "$SERIAL" logcat -d -b main 2>/dev/null | wc -l | tr -d ' \r')
    if [ "${n:-1}" -eq 0 ]; then return 0; fi
    [ -n "$pkg" ] && force_stop "$pkg"
    i=$((i + 1))
    sleep 1
  done
  echo "[run] WARN: logcat 清空失败（main 仍剩 ${n:-?} 行，重试 3 次）" >&2
  return 1
}

# device_clock -> "epoch|year"（设备本地时间；用于 raw 时间窗断言）
device_clock() {
  local e y
  e=$(adb_ shell date +%s 2>/dev/null | tr -d '\r' | head -1)
  y=$(adb_ shell date +%Y 2>/dev/null | tr -d '\r' | head -1)
  printf '%s|%s' "${e:-0}" "${y:-0}"
}

# ---------- 日志流 ----------
LC_FILE=""
LC_PID=""

# start_logcat_stream <raw.log>：启动前调用。
# 重要：logcat 流写到 **<raw.log>.rt**，脚本自己的结构行写 <raw.log>.struct，收尾时
#   merge_raw_logs 把两者合成最终的 <raw.log>。
# 原因（2026-11-01 实测两次踩坑）：adb logcat 连续持有同一文件写句柄后，同进程再打开该文件
#   追加会静默失败（AMSTART/RUNMETA/CDP 行全部丢失，只剩 RUNENV 和 logcat），
#   而继续用独立文件不受影响；合并后行号在每次重算时稳定可追溯。
start_logcat_stream() {
  LC_FILE="$1.rt"
  local since="${2:-}"
  # 直接起 $ADB（不要经 adb_ 函数）：经函数会多一层 bash 子 shell，kill 只杀子 shell，adb.exe 变孤儿。
  # -T <设备 epoch>.000：只收该时刻之后的日志，避免把 buffer 里前几轮的历史行当作本轮数据
  # （pdf-verify 20:42 发现裸 logcat 会重放整个 buffer；配合 clear_logcat 双保险）。
  if [ -n "$since" ] && [ "$since" != "0" ]; then
    "$ADB" -s "$SERIAL" logcat -v threadtime -T "$since.000" >> "$LC_FILE" 2>/dev/null &
  else
    "$ADB" -s "$SERIAL" logcat -v threadtime >> "$LC_FILE" 2>/dev/null &
  fi
  LC_PID=$!
  local i=0
  while [ "$i" -lt 50 ]; do
    kill -0 "$LC_PID" 2>/dev/null && return 0
    sleep 0.1; i=$((i + 1))
  done
  echo "[run] WARN: logcat 流未能启动（继续跑，可能丢数据）" >&2
  return 1
}

# stop_logcat_stream：结束流；可重复调用
stop_logcat_stream() {
  if [ -n "${LC_PID:-}" ] && kill -0 "$LC_PID" 2>/dev/null; then
    kill "$LC_PID" 2>/dev/null || true
    wait "$LC_PID" 2>/dev/null || true
    # 1s 内没退出就强杀，避免孤儿进程继续追加 raw 文件
    local i=0
    while [ "$i" -lt 10 ] && kill -0 "$LC_PID" 2>/dev/null; do sleep 0.1; i=$((i+1)); done
    kill -9 "$LC_PID" 2>/dev/null || true
    wait "$LC_PID" 2>/dev/null || true
  fi
  LC_PID=""
  LC_FILE=""
}

# merge_raw_logs <raw.log>：把 <raw.log>.struct + <raw.log>.rt 合成最终 raw（可重复调用）
merge_raw_logs() {
  local out="$1"
  [ -f "$out.struct" ] || [ -f "$out.rt" ] || return 0
  {
    [ -f "$out.struct" ] && cat "$out.struct"
    [ -f "$out.rt" ] && cat "$out.rt"
  } > "$out"
}

# _log_view <tag>：有流就读文件，否则回退 adb logcat -d（兼容单次调试）
_log_view() {
  local tag="$1"
  if [ -n "${LC_FILE:-}" ] && [ -f "$LC_FILE" ]; then
    cat "$LC_FILE"
  else
    adb_ logcat -d -s "$tag":V 2>/dev/null
  fi
}

# ---------- logcat 等待（统一走 _log_view，适配流式/一次性两种模式） ----------
# wait_log <tag> <grep -E pattern> <timeout_s>
wait_log() {
  local tag="$1" pat="$2" timeout="${3:-180}" i=0
  while [ "$i" -lt "$timeout" ]; do
    if _log_view "$tag" | grep -qE -- "$pat"; then return 0; fi
    i=$((i + 2)); sleep 2
  done
  return 1
}

# wait_log_count <tag> <grep -E pattern> <min_count> <timeout_s>
wait_log_count() {
  local tag="$1" pat="$2" min="${3:-1}" timeout="${4:-180}" i=0 n
  while [ "$i" -lt "$timeout" ]; do
    n=$(_log_view "$tag" | grep -cE -- "$pat" || true)
    if [ "${n:-0}" -ge "$min" ]; then return 0; fi
    i=$((i + 2)); sleep 2
  done
  return 1
}

# wait_pbdone <S1,S2,...> <timeout_s>：所有 scenario 的 PBDONE 都出现才算完成
wait_pbdone() {
  local want="${1:-}" timeout="${2:-240}" i=0 s log ok
  [ -n "$want" ] || return 0
  while [ "$i" -lt "$timeout" ]; do
    log=$(_log_view PBPERF)
    ok=1
    for s in $(printf '%s' "$want" | tr ',' ' '); do
      printf '%s\n' "$log" | grep -qF "PBDONE|scenario=$s|" || { ok=0; break; }
    done
    [ "$ok" = "1" ] && return 0
    i=$((i + 2)); sleep 2
  done
  return 1
}

# wait_quiet <tag> <settle_s> <timeout_s> [pattern]：行数连续 settle 秒不增长（用于没有 PBDONE 的旧链路）
wait_quiet() {
  local tag="$1" settle="${2:-5}" timeout="${3:-120}" pat="${4:-.}" i=0 last=-1 stable=0 n
  while [ "$i" -lt "$timeout" ]; do
    n=$(_log_view "$tag" | grep -cE -- "$pat" || true)
    if [ "${n:-0}" -gt 0 ] && [ "${n:-0}" -eq "$last" ]; then stable=$((stable + 1)); else stable=0; fi
    last="${n:-0}"
    if [ "$stable" -ge "$settle" ]; then return 0; fi
    i=$((i + 1)); sleep 1
  done
  return 1
}

# ---------- 冷启动 ----------
# am_start_cold <component> [am extra args...]  -> stdout 打印 AMSTART| 结构行；am 原始输出走 stderr
am_start_cold() {
  local comp="$1"; shift || true
  local out total wt stat
  out=$(adb_ shell am start -W -n "$comp" "$@" 2>&1 | tr -d '\r')
  printf '%s\n' "$out" >&2
  total=$(printf '%s\n' "$out" | sed -n 's/^TotalTime: *//p' | head -1)
  wt=$(printf '%s\n' "$out" | sed -n 's/^WaitTime: *//p' | head -1)
  stat=$(printf '%s\n' "$out" | sed -n 's/^Status: *//p' | head -1)
  printf 'AMSTART|rep=%s|TotalTime=%s|WaitTime=%s|status=%s|app=%s\n' \
    "${REP:-0}" "${total:-}" "${wt:-}" "${stat:-}" "${APP:-}"
}

# ---------- 采集（兼容旧调用：一次性 dump；正式跑法已改用流式） ----------
dump_logs_append() {
  local base="$1" tag="$2"
  adb_ logcat -d -s "$tag":V 2>/dev/null >> "$base.log"
  adb_ logcat -d -v threadtime > "$base.full.log" 2>/dev/null
}

# device_env -> 单行 k=v（不含 | 和空格）
device_env() {
  local api model build abi
  api=$(adb_ shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')
  model=$(adb_ shell getprop ro.product.model 2>/dev/null | tr -d '\r' | tr ' ' '_')
  build=$(adb_ shell getprop ro.build.version.incremental 2>/dev/null | tr -d '\r' | tr ' ' '_')
  abi=$(adb_ shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')
  printf 'api=%s|model=%s|abi=%s|build=%s' "${api:-}" "${model:-}" "${abi:-}" "${build:-}"
}

# runenv_line <run_id> <app> <apk> <scenarios>
runenv_line() {
  local run_id="$1" app="$2" apk="$3" scen="${4:-all}" md5="" size="" dev=""
  if [ -f "$apk" ]; then
    md5=$(md5sum "$apk" 2>/dev/null | awk '{print $1}')
    size=$(stat -c %s "$apk" 2>/dev/null)
  fi
  dev=$(device_env)
  printf 'RUNENV|app=%s|run_id=%s|apk=%s|apk_md5=%s|apk_bytes=%s|scenario=%s|%s|ts=%s\n' \
    "$app" "$run_id" "$apk" "${md5:-}" "${size:-}" "$scen" "${dev:-}" "$(ts_iso)"
}

# require_apk <path>
require_apk() {
  if [ ! -f "$1" ]; then
    echo "[run] APK 不存在: $1" >&2
    echo "[run] 先构建: bash scripts/android/spike2/build.sh <proj> :app:assembleDebug" >&2
    return 4
  fi
}

# install_apk <path>：失败直接非 0
install_apk() {
  local out rc
  out=$(adb_ install -r -t "$1" 2>&1 | tr -d '\r')
  printf '%s\n' "$out" >&2
  printf '%s' "$out" | grep -q "Success" || return 5
}

# parse_results <out_csv_dir> <name> <app> <log globs...>
parse_results() {
  local outdir="$1" name="$2" app="$3"; shift 3
  python scripts/android/spike2/parse.py --out "$outdir" --name "$name" --app "$app" "$@" || true
}
