#!/usr/bin/env bash
# Spike2 共用环境（source 用）；路径与设备坐标唯一来源
export SDK="${SDK:-/g/Android/sdk}"
export ADB="${ADB:-$SDK/platform-tools/adb.exe}"
export SERIAL="${SERIAL:-emulator-5554}"
export GRADLE="${GRADLE:-/g/Android/gradle-8.11.1/bin/gradle}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/g/Android/gradle-home}"
export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Microsoft/JDK21}"
export WT="${WT:-/f/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5}"
export BUILD_LOCK="${BUILD_LOCK:-/f/tmp/pb-build.lock}"
export EMU_LOCK="${EMU_LOCK:-/f/tmp/pb-emu.lock}"
export RESULT_DIR="${RESULT_DIR:-$WT/spikes/spike2-results}"

adb_() { "$ADB" -s "$SERIAL" "$@"; }

# 等待设备回到 device 状态（adb server 重启后模拟器需要几秒重连）
wait_device() {
  local i=0
  while [ "$i" -lt 60 ]; do
    if [ "$("$ADB" -s "$SERIAL" get-state 2>/dev/null)" = "device" ]; then return 0; fi
    "$ADB" start-server >/dev/null 2>&1
    # 20:23 事故：模拟器进程在跑但 adb 显示 offline，单靠 start-server 不够，必须 reconnect offline
    if "$ADB" devices 2>/dev/null | grep -qE "^$SERIAL[[:space:]]+offline"; then
      "$ADB" reconnect offline >/dev/null 2>&1 || true
    fi
    sleep 1; i=$((i + 1))
  done
  echo "[env] device $SERIAL not ready after 45s" >&2
  return 1
}
now_ms() { date +%s%3N; }
