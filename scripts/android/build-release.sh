#!/usr/bin/env bash
# 构建并签名 Android release APK，输出 md5/大小。
# 用法: scripts/android/build-release.sh [额外 gradle 参数...]
# 例:   scripts/android/build-release.sh            # 标准 release
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Microsoft/JDK21}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/g/Android/gradle-home}"
GRADLE="${GRADLE_BIN:-/g/Android/gradle-8.11.1/bin/gradle}"
SIGNER="${APKSIGNER:-/g/Android/sdk/build-tools/35.0.0/apksigner.bat}"
KS="${SIGN_KEYSTORE:-$HOME/.android/debug.keystore}"
OUT_DIR="$ROOT/android/app/build/outputs/apk/release"
cd "$ROOT/android"
echo "[build] gradle assembleRelease ..."
"$GRADLE" assembleRelease -x lintVitalAnalyzeRelease -x lintVitalRelease --console=plain "$@"
SRC="$OUT_DIR/app-release-unsigned.apk"
DST="$OUT_DIR/app-release-signed.apk"
cp -f "$SRC" "$DST"
"$SIGNER" sign --ks "$KS" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android "$DST"
echo "[ok] apk: $DST"
md5sum "$DST"
stat -c "[size] %s bytes" "$DST"
