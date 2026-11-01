#!/usr/bin/env bash
# 构建（自动加 build 锁；默认 offline）
#   bash scripts/android/spike2/build.sh spikes/spike2-pdf :app:assembleDebug
#   PB_ONLINE=1 ...  # 需要拉新依赖时
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/env.sh"

PROJ="${1:-}"; [ -n "$PROJ" ] || { echo "usage: build.sh <projectDir> [gradle tasks...]" >&2; exit 2; }
shift
TASKS=("$@"); [ ${#TASKS[@]} -eq 0 ] && TASKS=(:app:assembleDebug)

ARGS=(--console=plain -q)
[ "${PB_ONLINE:-0}" != "1" ] && ARGS=(--offline "${ARGS[@]}")
ARGS+=("${TASKS[@]}")

cd "$PROJ" || { echo "[build] no such project dir: $PROJ" >&2; exit 4; }
echo "[build] proj=$(pwd) tasks=${TASKS[*]} offline=$([ "${PB_ONLINE:-0}" != "1" ] && echo 1 || echo 0)"
exec bash "$HERE/lock.sh" build "$GRADLE" "${ARGS[@]}"
