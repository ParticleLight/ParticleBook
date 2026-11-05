#!/usr/bin/env bash
# Build libmobi 0.12 as Android shared libraries (arm64-v8a + x86_64).
# Third-party source and build outputs stay outside the repo (default F:/tmp/libmobi-p0).
set -e

VERSION=0.12
SHA256=78826d161c02ce93ff1cd62838b4d749df754f52185474b82e4093badf4689c1
URL=https://codeload.github.com/bfabiszewski/libmobi/tar.gz/refs/tags/v0.12

ROOT="$LIBMOBI_BUILD_ROOT"
if [ -z "$ROOT" ]; then ROOT="F:/tmp/libmobi-p0"; fi

NDK="$ANDROID_NDK_HOME"
if [ -z "$NDK" ]; then NDK="G:/Android/sdk/ndk/28.2.13676358"; fi

CMAKE_BIN="$CMAKE_BIN"
if [ -z "$CMAKE_BIN" ]; then CMAKE_BIN="/c/Program Files/CMake/bin/cmake"; fi

NINJA_BIN="$NINJA_BIN"
if [ -z "$NINJA_BIN" ]; then NINJA_BIN="G:/Android/sdk/cmake/3.22.1/ninja.exe"; fi

STRIP_BIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe"
TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
TARBALL="$ROOT/libmobi-$VERSION.tar.gz"
SRC="$ROOT/libmobi-$VERSION"

command -v curl >/dev/null 2>&1 || { echo "curl not found"; exit 1; }
[ -x "$CMAKE_BIN" ] || { echo "cmake not executable: $CMAKE_BIN"; exit 1; }
[ -x "$NINJA_BIN" ] || { echo "ninja not executable: $NINJA_BIN"; exit 1; }
[ -x "$STRIP_BIN" ] || { echo "llvm-strip not executable: $STRIP_BIN"; exit 1; }
[ -f "$TOOLCHAIN" ] || { echo "NDK toolchain not found: $TOOLCHAIN"; exit 1; }

mkdir -p "$ROOT"
cd "$ROOT"

if [ ! -f "$TARBALL" ]; then
  echo "downloading $URL"
  curl -sL --max-time 120 "$URL" -o "$TARBALL"
fi
printf '%s  %s\n' "$SHA256" "$TARBALL" | sha256sum -c -

if [ ! -d "$SRC" ]; then
  tar -xzf "$TARBALL" -C "$ROOT"
fi
[ -d "$SRC" ] || { echo "source dir missing: $SRC"; exit 1; }

mkdir -p "$ROOT/out"
for ABI in arm64-v8a x86_64; do
  BUILD="$ROOT/build-$ABI"
  OUT="$ROOT/out/$ABI"
  rm -rf "$BUILD"
  mkdir -p "$OUT"

  echo "=== configure $ABI ==="
  "$CMAKE_BIN" -S "$SRC" -B "$BUILD" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$NINJA_BIN" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-26 \
    -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON \
    -DUSE_LIBXML2=OFF -DUSE_ZLIB=OFF -DUSE_MINIZ=ON -DUSE_ENCRYPTION=ON

  echo "=== build $ABI ==="
  "$CMAKE_BIN" --build "$BUILD" --target mobi --parallel
  cp "$BUILD/src/libmobi.so" "$OUT/libmobi.so"
  "$STRIP_BIN" --strip-unneeded "$OUT/libmobi.so"

  echo "=== $ABI ==="
  ls -l "$OUT/libmobi.so"
  sha256sum "$OUT/libmobi.so"
done

echo "=== done ==="
du -ch "$ROOT"/out/*/libmobi.so | tail -1
