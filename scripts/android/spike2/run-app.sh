#!/usr/bin/env bash
# run-app.sh — 通用正式跑法：N 轮「重装 + 冷启 + 跑场景 + 抓日志」，每轮单独持 emu 锁；
# 全部跑完自动 parse.py -> CSV/md，并尽量 summarize.py -> summary/。
# 一般由 run-render.sh / run-tiles.sh / run-baseline-p0.sh 设好默认再调用。
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/env.sh"
. "$HERE/runlib.sh"
cd "$WT"

: "${PB_APP:?run-app.sh 需要 PB_APP（由 run-*.sh 设置）}" "${PB_PKG:?}" "${PB_COMPONENT:?}"
: "${PB_APK_DEFAULT:?}" "${PB_TAG_PREFIX:?}"

REPS="${PB_REPS:-5}"
SCEN="${PB_SCENARIOS:-}"
APK="${PB_APK:-$PB_APK_DEFAULT}"
TIMEOUT="${PB_TIMEOUT:-240}"
TAG="${PB_TAG:-$PB_TAG_PREFIX}"
INNER="${PB_INNER:-_one-app.sh}"
DROP_FIRST="${PB_DROP_FIRST:-0}"
DROP_MODE="${PB_DROP_MODE:-none}"
SHOT="${PB_SCREENSHOT:-0}"
NOPARSE="${PB_NOPARSE:-0}"
TAP_X="${PB_TAP_X:-660}"
TAP_Y="${PB_TAP_Y:-700}"

usage() {
  cat >&2 <<EOF
用法: bash scripts/android/spike2/$(basename "$0") [options]
  --reps N        重复次数（默认 $REPS）：每轮重装 APK + 冷启 + 跑一遍
  --scenarios L   传给 --es scenario 的场景子集（如 S1,S2；默认空=全部）
  --apk PATH      APK 路径（默认 $PB_APK_DEFAULT）
  --timeout SEC   每轮等待 PBDONE 的超时（默认 240）
  --tag NAME      raw/csv 文件前缀（默认 $PB_TAG_PREFIX）
  --drop-first N  parse 时每 (run,scenario,phase) 丢弃前 N 个样本并标 warmup（默认 0）
  --drop-mode M   parse 的 warmup 剔除模式：none | first-per-phase（默认沿用 PB_DROP_MODE；正式测量用 first-per-phase）
  --screenshot    每轮抓一张 screenshot（放 raw/<run_id>.png）
  --no-parse      跑完不自动 parse
  --tap-x N       仅 P0 基线：点书架 PDF 卡片的 x（默认 660）
  --tap-y N       仅 P0 基线：y（默认 700）
环境: PB_ONLINE=1 不适用（本脚本不构建）。构建请走 build.sh。
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --reps)      REPS="${2:?}"; shift 2 ;;
    --scenarios) SCEN="${2:?}"; shift 2 ;;
    --apk)       APK="${2:?}"; shift 2 ;;
    --timeout)   TIMEOUT="${2:?}"; shift 2 ;;
    --tag)       TAG="${2:?}"; shift 2 ;;
    --drop-first) DROP_FIRST="${2:?}"; shift 2 ;;
    --drop-mode)  DROP_MODE="${2:?}"; shift 2 ;;
    --screenshot) SHOT=1; shift ;;
    --no-parse)  NOPARSE=1; shift ;;
    --tap-x)     TAP_X="${2:?}"; shift 2 ;;
    --tap-y)     TAP_Y="${2:?}"; shift 2 ;;
    -h|--help)   usage; exit 0 ;;
    *) echo "[run] 未知参数: $1" >&2; usage; exit 2 ;;
  esac
done

mkdir -p "$RESULT_DIR/raw" "$RESULT_DIR/csv" "$RESULT_DIR/summary"
MAN="$RESULT_DIR/raw/$TAG-manifest.tsv"
[ -f "$MAN" ] || printf 'rep\tts\trc\traw_log\n' > "$MAN"

require_apk "$APK" || exit 4

echo "[run] app=$PB_APP pkg=$PB_PKG comp=$PB_COMPONENT"
echo "[run] reps=$REPS scenarios=${SCEN:-all} timeout=${TIMEOUT}s tag=$TAG"
echo "[run] parse drop_mode=$DROP_MODE drop_first=$DROP_FIRST"
echo "[run] apk=$APK ($(stat -c %s "$APK") B)"
echo "[run] inner=$INNER out=$RESULT_DIR"

rc_all=0
rep=1
while [ "$rep" -le "$REPS" ]; do
  echo "[run] ===== rep $rep/$REPS $(ts_iso) ====="
  PB_REP="$rep" \
  PB_SCENARIOS="$SCEN" \
  PB_APK="$APK" \
  PB_TIMEOUT="$TIMEOUT" \
  PB_SCREENSHOT="$SHOT" \
  PB_TAG_PREFIX="$TAG" \
  PB_APP="$PB_APP" \
  PB_PKG="$PB_PKG" \
  PB_COMPONENT="$PB_COMPONENT" \
  PB_TAP_X="$TAP_X" \
  PB_TAP_Y="$TAP_Y" \
    bash "$HERE/lock.sh" emu bash "$HERE/$INNER"
  rc=$?
  echo "[run] rep $rep rc=$rc"
  [ "$rc" -eq 0 ] || rc_all="$rc"
  rep=$((rep + 1))
  sleep 2
done

# ---- 自动重算 CSV + markdown 摘要（一条命令从 raw 重算的入口就是这行） ----
if [ "$NOPARSE" -eq 0 ] && [ -f "$HERE/parse.py" ]; then
  LOGS=$(ls "$RESULT_DIR"/raw/"$TAG"-run*.log 2>/dev/null || true)
  if [ -n "$LOGS" ]; then
    echo "[run] parse -> $RESULT_DIR/csv/$TAG-merged.csv"
    # shellcheck disable=SC2086
    python "$HERE/parse.py" --out "$RESULT_DIR/csv" --name "$TAG-merged" \
      --app "$PB_APP" --drop-first "$DROP_FIRST" --drop-mode "$DROP_MODE" $LOGS || rc_all="$rc_all"
    if [ -f "$HERE/summarize.py" ]; then
      python "$HERE/summarize.py" --out "$RESULT_DIR/summary" --name "$TAG" \
        --csv "$RESULT_DIR/csv/$TAG-merged.csv" || true
    fi
  else
    echo "[run] 没有 raw 日志可解析（$TAG-run*.log）" >&2
  fi
else
  echo "[run] 跳过 parse（--no-parse 或 parse.py 不存在）"
fi

echo "[run] done rc=$rc_all"
echo "[run] raw     : $RESULT_DIR/raw"
echo "[run] csv     : $RESULT_DIR/csv"
echo "[run] summary : $RESULT_DIR/summary"
exit "$rc_all"
