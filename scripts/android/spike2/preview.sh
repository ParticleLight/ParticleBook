#!/usr/bin/env bash
# 切到「带窗口」模拟器并装好预览用的 app（spike2 测量完成后使用）
# 用法: bash scripts/android/spike2/preview.sh
set -u
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"

EMU="${SDK}/emulator/emulator.exe"
WT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
P0_APK="${WT}/android/app/build/outputs/apk/debug/app-debug.apk"
SPIKE2_APK="${WT}/spikes/spike2-pdf/app/build/outputs/apk/debug/app-debug.apk"
TILES_APK="${WT}/spikes/spike2-tiles/app/build/outputs/apk/debug/app-debug.apk"

echo "[preview] 关闭无窗口模拟器..."
"${ADB}" emu kill >/dev/null 2>&1 || true
sleep 3
powershell -NoProfile -Command "Get-Process emulator -ErrorAction SilentlyContinue | Stop-Process -Force" >/dev/null 2>&1 || true
sleep 2

echo "[preview] 启动带窗口模拟器（swiftshader，无快照）..."
powershell -NoProfile -Command "Start-Process -FilePath '${EMU}' -ArgumentList '-avd','pb35','-no-audio','-no-boot-anim','-gpu','swiftshader_indirect','-no-snapshot','-netdelay','none','-netspeed','full'"

echo "[preview] 等待设备..."
"${ADB}" wait-for-device
for i in $(seq 1 90); do
  bc=$("${ADB}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
  [ "${bc}" = "1" ] && break
  sleep 2
done
echo "[preview] boot_completed=${bc:-unknown}"

echo "[preview] 安装 app..."
"${ADB}" install -r "${P0_APK}" >/dev/null && echo "  P0  app.particlebook OK"
"${ADB}" install -r "${SPIKE2_APK}" >/dev/null && echo "  spike2 整页直渲染 OK"
"${ADB}" install -r "${TILES_APK}" >/dev/null && echo "  spike2t 分块 OK"

echo "[preview] 打开 P0 安卓版..."
"${ADB}" shell am start -n app.particlebook/.MainActivity >/dev/null
echo "[preview] 完成：窗口应该已经出现，直接点即可。"
