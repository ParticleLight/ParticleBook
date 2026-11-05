#!/usr/bin/env bash
# Build the standalone arm64-v8a mobi_probe for P0-5b.
# Requires F:/tmp/libmobi-p0/out/arm64-v8a/libmobi.so from build-libmobi.sh.
set -e

ROOT="$LIBMOBI_BUILD_ROOT"
if [ -z "$ROOT" ]; then ROOT="F:/tmp/libmobi-p0"; fi

NDK="$ANDROID_NDK_HOME"
if [ -z "$NDK" ]; then NDK="G:/Android/sdk/ndk/28.2.13676358"; fi

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/mobi_probe.c"
LIBSO="$ROOT/out/arm64-v8a/libmobi.so"
OUT_DIR="$ROOT/tools"
OUT="$OUT_DIR/mobi_probe"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64"
CC="$TOOLCHAIN/bin/clang.exe"
SYSROOT="$TOOLCHAIN/sysroot"
READELF="$TOOLCHAIN/bin/llvm-readelf.exe"
SHA="$(command -v sha256sum)"

[ -f "$SRC" ] || { echo "missing $SRC"; exit 1; }
[ -f "$LIBSO" ] || { echo "missing $LIBSO; run build-libmobi.sh first"; exit 1; }
[ -x "$CC" ] || { echo "missing NDK clang: $CC"; exit 1; }

mkdir -p "$OUT_DIR"
"$CC" --target=aarch64-linux-android26 --sysroot="$SYSROOT" \
  -O2 -fPIE -pie -Wall -Wextra \
  -I"$ROOT/libmobi-0.12/src" \
  "$SRC" \
  -L"$ROOT/out/arm64-v8a" -lmobi \
  -o "$OUT"

echo "built: $OUT"
ls -l "$OUT" "$LIBSO"
"$SHA" "$OUT"
"$READELF" -h "$OUT" | grep -E 'Class|Type|Machine'
"$READELF" -d "$OUT" | grep -E 'NEEDED|RPATH|RUNPATH|SONAME'
