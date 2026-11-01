#!/usr/bin/env bash
# P0 真机数据采集：接上手机（已开 USB 调试）后运行：
#   bash scripts/android/run-p0.sh [设备序列号]
set -u
SDK="${ANDROID_SDK_ROOT:-G:/Android/sdk}"
ADB="$SDK/platform-tools/adb.exe"
APK=android/app/build/outputs/apk/debug/app-debug.apk
PKG=app.particlebook
ACT="$PKG/$PKG.android.MainActivity"
SERIAL=${1:-}
if [ -n "$SERIAL" ]; then ADB_ARGS="-s $SERIAL"; else ADB_ARGS=""; fi
run() { $ADB $ADB_ARGS "$@"; }

echo "== 1. 设备 =="
run devices -l
run shell getprop ro.product.model
run shell getprop ro.build.version.sdk

echo "== 2. 安装 ==="
run install -r -t "$APK" | tail -1

echo "== 3. 冷启动 x3 =="
i=1
while [ "$i" -le 3 ]; do
  run shell am force-stop "$PKG"
  sleep 2
  run logcat -c
  run shell am start -W -n "$ACT" | grep -E "TotalTime|WaitTime|Status"
  sleep 6
  run logcat -d -s ParticleBook:V PBBridge:V | grep -E "TIMING|BENCH" | head -6
  i=$((i+1))
done

echo "== 4. 内存 =="
run shell dumpsys meminfo "$PKG" | grep -E "TOTAL|Native Heap|Dalvik Heap" | head -4
for pid in $(run shell ps -A | grep webview | awk '{print $2}'); do
  tot=$(run shell dumpsys meminfo "$pid" | grep -E "^ *TOTAL" | head -1)
  echo "  webview pid $pid: $tot"
done

echo "== 5. 手动交互 =="
echo "请在设备上打开 EPUB 与 PDF；脚本抓 12 秒日志："
run logcat -c
sleep 12
run logcat -d -s PBBridge:V PBPdf:V | grep -E "INVOKE|pdf_" | tail -30
