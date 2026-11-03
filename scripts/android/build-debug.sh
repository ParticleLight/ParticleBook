#!/usr/bin/env bash
# 构建 debug APK（离线 + build 锁），用于离线 E2E（pb_zlib_mirror 钩子 + cleartext 只在 debug 变体）。
# 用法: scripts/android/build-debug.sh [额外 gradle 参数...]
# 输出: android/app/build/outputs/apk/debug/app-debug.apk
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Microsoft/JDK21}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/g/Android/gradle-home}"
GRADLE="${GRADLE_BIN:-/g/Android/gradle-8.11.1/bin/gradle}"

TASKS=(:app:assembleDebug)
cd "$ROOT/android" || { echo "[build] no android dir" >&2; exit 4; }
echo "[build] debug (offline, build lock) tasks=${TASKS[*]}"
exec bash "$ROOT/scripts/android/spike2/lock.sh" build "$GRADLE" --offline --console=plain "${TASKS[@]}" "$@"
