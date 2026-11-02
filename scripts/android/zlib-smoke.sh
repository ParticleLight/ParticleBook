#!/usr/bin/env bash
# zlib-smoke.sh — ParticleBook Z-Library 可重复冒烟（PASS/FAIL 一条命令）
#
# 做的事：设备锁内装 APK → 冷启 → Z-Library tab → 搜索 android → 断言「已加载 N 本」
#         → 打开首条 → 断言「书籍详情 + 下载 · + 书名」→ 可选 --download 三态下载闭环
#           （30s 触发证据 / 180s 终态 / 书架+1 / 下载目录新文件）
#         → 抓 PBPERF（cold_start/zlib_search/zlib_login/zlib_download）→ JSON + md5 + PASS/FAIL/SKIP。
#
# 用法：bash scripts/android/zlib-smoke.sh --apk F:/path/app-debug.apk [--download]
# 细节：--help；自检：--selfcheck（不碰设备）；退出码 0=PASS 或 SKIP，非 0=FAIL（见 --help）。
#
# 约定：所有 adb 写操作都在 lock.sh emu 锁内；本脚本默认自动获取设备锁。
#       APK 用 Windows 路径；MSYS_NO_PATHCONV=1；单设备（--serial 或自动检测唯一在线设备）。
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
# python (Windows) 不认 /f/... 形式的 MSYS 路径；cwd 已 cd 到仓库根，用相对路径
DRIVER="scripts/android/zlib-smoke.py"
SELF="$HERE/zlib-smoke.sh"

# shellcheck source=spike2/env.sh
source "$HERE/spike2/env.sh"
export MSYS_NO_PATHCONV=1
export PYTHONIOENCODING=utf-8
# 不要继承 env.sh 里的 emulator-5554 默认值：设备由 --serial 或自动检测决定
export SERIAL=""
cd "$ROOT" || { echo "zlib-smoke: cannot cd to $ROOT" >&2; exit 4; }

LOCK_HELD=0
NO_LOCK=0
SELFCHECK=0
LOCK_WAIT="120"
LOCK_HOLDER="pdf-bench"
if [ -n "$(printenv LOCK_MAX_WAIT || true)" ]; then LOCK_WAIT="$(printenv LOCK_MAX_WAIT)"; fi
if [ -n "$(printenv LOCK_HOLDER || true)" ]; then LOCK_HOLDER="$(printenv LOCK_HOLDER)"; fi
ARGS=()

if [ "$#" -eq 0 ]; then
  python "$DRIVER" --help
  echo "zlib-smoke: 缺少参数；示例：bash scripts/android/zlib-smoke.sh --apk F:/path/app-debug.apk --download" >&2
  exit 2
fi

while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help)
      exec python "$DRIVER" --help
      ;;
    --selfcheck)
      SELFCHECK=1
      shift
      ;;
    --no-lock)
      NO_LOCK=1
      shift
      ;;
    --lock-held)
      LOCK_HELD=1
      shift
      ;;
    --lock-wait)
      if [ "$#" -lt 2 ]; then echo "zlib-smoke: --lock-wait needs seconds" >&2; exit 2; fi
      LOCK_WAIT="$2"
      shift 2
      ;;
    --lock-holder)
      if [ "$#" -lt 2 ]; then echo "zlib-smoke: --lock-holder needs a label" >&2; exit 2; fi
      LOCK_HOLDER="$2"
      shift 2
      ;;
    --)
      shift
      while [ "$#" -gt 0 ]; do ARGS+=("$1"); shift; done
      ;;
    *)
      ARGS+=("$1")
      shift
      ;;
  esac
done

if [ "$SELFCHECK" = 1 ]; then
  rc=0
  if ! bash -n "$SELF"; then echo "SELFCHECK|FAIL|bash -n"; rc=2; fi
  if ! python -m py_compile "$DRIVER"; then echo "SELFCHECK|FAIL|py_compile"; rc=2; fi
  if ! python "$DRIVER" --help >/dev/null; then echo "SELFCHECK|FAIL|driver --help"; rc=2; fi
  if ! python "$DRIVER" --selftest; then echo "SELFCHECK|FAIL|driver --selftest"; rc=2; fi
  if [ "$rc" = 0 ]; then
    echo "SELFCHECK|PASS|bash -n|py_compile|driver --help|driver=$DRIVER"
  fi
  exit "$rc"
fi

if [ "$NO_LOCK" = 0 ] && [ "$LOCK_HELD" = 0 ]; then
  exec env LOCK_MAX_WAIT="$LOCK_WAIT" LOCK_HOLDER="$LOCK_HOLDER" bash "$HERE/spike2/lock.sh" emu bash "$SELF" --lock-held ${ARGS[@]}
fi

exec python "$DRIVER" ${ARGS[@]}
