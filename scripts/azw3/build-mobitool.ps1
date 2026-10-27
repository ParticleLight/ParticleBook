<#
.SYNOPSIS
  用 MSVC 从固定版本的 libmobi 源码构建 mobitool.exe（AZW3/KF8 → EPUB / HTML 转换工具）。

.DESCRIPTION
  libmobi (LGPL-3.0-or-later) 自带 CMake，但 Windows/MSVC 下有两个坑：
    1) tools/CMakeLists.txt 没有把 tools/win32/getopt.c 编进来 → LNK2019 getopt；
    2) 关闭 USE_ZLIB 时顶层只 add_definitions(-DUSE_MINIZ)，没有 set(USE_MINIZ ON)，
       会导致 miniz.c 不参与编译 → 链接缺符号。脚本显式传 -DUSE_MINIZ=ON。
  另外用 /MT 静态 CRT，产出的 exe 只依赖 KERNEL32.dll（与主程序一致，不额外带 VC 运行库）。

.PARAMETER SourceDir   libmobi 源码目录（默认 %TEMP%\libmobi-src）
.PARAMETER BuildDir    CMake 构建目录（默认 %TEMP%\libmobi-build）
.PARAMETER OutDir      输出目录（默认 %TEMP%\azw3-mobitool）
.PARAMETER Ref         git ref（默认固定 commit，可复现）
.PARAMETER SkipClone   已有源码时跳过 clone/checkout（离线构建）

.EXAMPLE
  pwsh -File scripts/azw3/build-mobitool.ps1
  pwsh -File scripts/azw3/build-mobitool.ps1 -SourceDir C:\src\libmobi -SkipClone

.NOTES
  LGPL 合规：分发 mobitool.exe 时需同时提供 libmobi 对应源码（本脚本固定 commit 可拉取）、
  COPYING 许可证与构建方式；详见 docs/AZW3.md。
#>
[CmdletBinding()]
param(
    [string]$SourceDir = (Join-Path $env:TEMP 'libmobi-src'),
    [string]$BuildDir = (Join-Path $env:TEMP 'libmobi-build'),
    [string]$OutDir = (Join-Path $env:TEMP 'azw3-mobitool'),
    [string]$Ref = '906274205c11944b628da1c553b255acb1af7c55',
    [switch]$SkipClone,
    [string]$Generator = 'Visual Studio 17 2022'
)

$ErrorActionPreference = 'Stop'
function Require-Command([string]$name) {
    if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
        throw "required command not found: $name"
    }
}
Require-Command git
Require-Command cmake

# ── 1) 源码（固定 commit，可复现）─────────────────────────────────────
$CMakeTools = Join-Path $SourceDir 'tools/CMakeLists.txt'
if (-not $SkipClone) {
    if (Test-Path (Join-Path $SourceDir '.git')) {
        Write-Host "[azw3] fetch libmobi $Ref"
        git -C $SourceDir fetch --depth 1 origin $Ref
        if ($LASTEXITCODE -ne 0) { throw "git fetch failed ($LASTEXITCODE)" }
        git -C $SourceDir checkout --detach FETCH_HEAD
        if ($LASTEXITCODE -ne 0) { throw "git checkout failed ($LASTEXITCODE)" }
    } else {
        Write-Host "[azw3] clone libmobi into $SourceDir"
        git clone https://github.com/bfabiszewski/libmobi.git $SourceDir
        if ($LASTEXITCODE -ne 0) { throw "git clone failed ($LASTEXITCODE)" }
        git -C $SourceDir checkout --detach $Ref
        if ($LASTEXITCODE -ne 0) { throw "git checkout failed ($LASTEXITCODE)" }
    }
}
if (-not (Test-Path $CMakeTools)) { throw "libmobi source not found: $SourceDir" }

# ── 2) Windows/MSVC：补 tools/win32/getopt.c ───────────────────────────
$cmake = Get-Content -Raw -LiteralPath $CMakeTools
if ($cmake -notmatch 'win32/getopt\.c') {
    $needle = "add_executable(mobitool mobitool.c)`r`ntarget_link_libraries(mobitool PUBLIC mobi)"
    if ($cmake -notmatch [regex]::Escape($needle)) {
        $needle = "add_executable(mobitool mobitool.c)`ntarget_link_libraries(mobitool PUBLIC mobi)"
    }
    if ($cmake -notmatch [regex]::Escape($needle)) { throw 'tools/CMakeLists.txt: mobitool block not found' }
    $patch = $needle + "`nif(WIN32)`n    target_sources(mobitool PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/win32/getopt.c)`n    target_include_directories(mobitool PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/win32)`nendif()"
    $cmake = $cmake.Replace($needle, $patch)
    Set-Content -LiteralPath $CMakeTools -Value $cmake -Encoding UTF8
    Write-Host '[azw3] patched tools/CMakeLists.txt (win32/getopt.c)'
}

# ── 3) 配置 + 构建（无外部依赖：不用 zlib/libxml2/openssl）─────────────
if (Test-Path $BuildDir) { Remove-Item -Recurse -Force $BuildDir }
$configure = @(
    '-S', $SourceDir,
    '-B', $BuildDir,
    '-G', $Generator,
    '-A', 'x64',
    '-DUSE_ENCRYPTION=OFF',
    '-DUSE_LIBXML2=OFF',
    '-DUSE_ZLIB=OFF',
    '-DUSE_MINIZ=ON',
    '-DUSE_XMLWRITER=ON',
    '-DTOOLS_STATIC=ON',
    '-DBUILD_SHARED_LIBS=OFF',
    '-DCMAKE_C_FLAGS_RELEASE=/MT /O2 /DNDEBUG'
)
& cmake @configure
if ($LASTEXITCODE -ne 0) { throw "cmake configure failed ($LASTEXITCODE)" }
& cmake --build $BuildDir --config Release --target mobitool
if ($LASTEXITCODE -ne 0) { throw "cmake build failed ($LASTEXITCODE)" }

# ── 4) 输出 exe + 许可证 ───────────────────────────────────────────────
$exe = Join-Path $BuildDir 'tools/Release/mobitool.exe'
if (-not (Test-Path $exe)) { throw "mobitool.exe not found: $exe" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
Copy-Item -LiteralPath $exe -Destination (Join-Path $OutDir 'mobitool.exe') -Force
Copy-Item -LiteralPath (Join-Path $SourceDir 'COPYING') -Destination (Join-Path $OutDir 'LIBMOBI-COPYING') -Force
$hash = (Get-FileHash -Algorithm SHA256 (Join-Path $OutDir 'mobitool.exe')).Hash.ToLowerInvariant()
$size = (Get-Item (Join-Path $OutDir 'mobitool.exe')).Length
Write-Host ("[azw3] done: {0} ({1} bytes, sha256={2})" -f (Join-Path $OutDir 'mobitool.exe'), $size, $hash)
Write-Host '[azw3] smoke: mobitool.exe -e -o <outdir> <book.azw3>'
