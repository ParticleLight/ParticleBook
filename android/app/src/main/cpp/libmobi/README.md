# libmobi Android 构建（P0-5 spike）

状态：**构建可行性已验证**（2026-11-05）；未接入 app 构建，未做 JNI/真机验证。结论与完整证据见 docs/android-port/p0/libmobi.md。

## 结论

- libmobi 0.12 可用 NDK 28.2 构建为 arm64-v8a / x86_64 动态库，无需修改上游源码。
- 实际构建配置：CMake 4.3.2 + Ninja 1.10.2 + Android NDK clang 19.0.1，minSdk 26。
- 产物（strip 后）：
  - arm64-v8a/libmobi.so：199,520 B，SHA-256 e1143f9c0ef7976f32ba83690dad84fec36bdb728cc6a4d8c6fd6d67c6f9c15b
  - x86_64/libmobi.so：206,200 B，SHA-256 ddb0e432e6562d0522390b483d450830addc2d11165082380bd5fa6e3c351018
  - 两 ABI 合计 405,720 B（约 0.39 MB；APK 使用 useLegacyPackaging，按此大小直接增加）。
- 未 strip 的中间产物约 0.85–0.89 MB/ABI，仅用于本地调试；发布包应使用 strip 后的文件。

## 构建命令

在仓库根目录（Git Bash）：

    bash android/app/src/main/cpp/libmobi/build-libmobi.sh

默认 source/output 在 F:/tmp/libmobi-p0，不写入仓库。可用环境变量覆盖：

- LIBMOBI_BUILD_ROOT：构建根目录
- ANDROID_NDK_HOME：NDK 28.2 根目录
- CMAKE_BIN：CMake 可执行文件
- NINJA_BIN：Ninja 可执行文件

脚本会：

1. 下载 https://codeload.github.com/bfabiszewski/libmobi/tar.gz/refs/tags/v0.12
2. 校验 SHA-256：78826d161c02ce93ff1cd62838b4d749df754f52185474b82e4093badf4689c1
3. 分别 configure/build 两个 ABI 的 mobi target
4. 用 NDK llvm-strip --strip-unneeded 裁剪
5. 输出到 F:/tmp/libmobi-p0/out/<abi>/libmobi.so 并打印大小/SHA-256

## 关键构建参数

    -DCMAKE_TOOLCHAIN_FILE=<NDK>/build/cmake/android.toolchain.cmake
    -DANDROID_ABI=arm64-v8a|x86_64
    -DANDROID_PLATFORM=android-26
    -DCMAKE_BUILD_TYPE=Release
    -DBUILD_SHARED_LIBS=ON
    -DUSE_LIBXML2=OFF
    -DUSE_ZLIB=OFF
    -DUSE_MINIZ=ON
    -DUSE_ENCRYPTION=ON

注意：上游 CMakeLists 在 USE_ZLIB=OFF 时只定义了 USE_MINIZ 宏，却没有设置 USE_MINIZ 变量，导致 src/CMakeLists.txt 不编译 miniz，链接时出现 undefined symbol: mz_crc32 / mz_uncompress。必须显式追加 -DUSE_MINIZ=ON；这是构建参数 workaround，不是源码 patch。

## 集成说明（Lead 执行）

- 本目录不含第三方源码，也不含预编译 .so；只放本脚本和说明。
- Lead 可二选一：
  1. 预编译集成：把 F:/tmp/libmobi-p0/out/<abi>/libmobi.so 放到 android/app/src/main/jniLibs/<abi>/，由 AGP 打包；
  2. CMake 集成：在 app 的 CMakeLists.txt 中 add_library(mobi SHARED IMPORTED) 并设置 IMPORTED_LOCATION，再让 pbcore 链接；不要修改本目录脚本以外的现有文件。
- JNI 包装、解析线程/内存释放、DRM 拒绝逻辑由后续任务实现；本 spike 只证明库能编出来。

## LGPL 提醒

libmobi 是 LGPL-3.0-or-later。Android APK 内 .so 不满足 LGPL-3 §4(d)(1) 的“用户系统已有库”，发布必须按 §4(d)(0) 提供 libmobi 源码、构建脚本、GPL/LGPL 文本、notice，并允许用户重编/重签应用；发布前需法务确认。详见 docs/android-port/p0/libmobi.md 的合规清单。
