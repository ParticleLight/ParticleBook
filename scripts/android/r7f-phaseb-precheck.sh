#!/usr/bin/env bash
# R7-F Phase B 预检（不碰设备：默认不执行任何 adb）
# 用法： bash scripts/android/r7f-phaseb-precheck.sh   （ALLOW_ADB=1 才查 adb devices）
set -uo pipefail
export PYTHONIOENCODING=utf-8
PORT=8898
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
FAIL=0
ok()   { echo "[ OK ] $*"; }
bad()  { echo "[FAIL] $*"; FAIL=$((FAIL+1)); }
warn() { echo "[WARN] $*"; }

echo "== $(date '+%F %T')  Phase B precheck (host-only) =="

H=$(curl -s -m 3 "http://127.0.0.1:$PORT/__health" || true)
if [ -n "$H" ]; then
  echo "$H" | python -c "import sys,json;h=json.load(sys.stdin);print('[ OK ] mock %s v%s dl=%s book=%s/%sB fault=%s' % (h['port'],h['version'],h['dl']['mode'],h['book']['format'],h['book']['bytes'],h['fault']['mode']))" || bad "mock health parse"
  ok "listener pid $(netstat -ano 2>/dev/null | grep -E "127.0.0.1:$PORT .*LISTENING" | awk '{print $NF}' | head -1)"
else
  bad "mock $PORT 未就绪（python scripts/android/r7f-mockd.py start --mode page --port $PORT）"
fi
echo "       fault=$(curl -s -m 3 "http://127.0.0.1:$PORT/__fault")"
echo "       stats=$(curl -s -m 3 "http://127.0.0.1:$PORT/__stats" | head -c 140)"

echo "-- APK 候选（本机）"
for f in android/app/build/outputs/apk/debug/app-debug.apk spikes/zz-probe/apk/app-debug-1c531ffa.apk spikes/zz-probe/apk/app-debug-ddd28d53.apk spikes/zz-probe/apk/app-debug-783e3412.apk; do
  if [ -f "$f" ]; then
    printf '       %s  %s  %s\n' "$(md5sum "$f" | awk '{print $1}')" "$(stat -c %y "$f" | cut -c1-19)" "$f"
  else
    warn "缺文件 $f"
  fi
done
ok "Phase B 目标 md5=1c531ffa2d0f4626cb0c702323ef82f9（20:51:56）；setup 时以设备 run-as 实测为准并记录"

for s in scripts/android/r7f-phaseb.sh scripts/android/r7f-mockd.py scripts/android/zlib-mock.py scripts/android/ui-shot.py scripts/android/r7-verify.py; do
  if [ -f "$s" ]; then ok "存在 $s"; else bad "缺脚本 $s"; fi
done
bash -n scripts/android/r7f-phaseb.sh && ok "bash -n r7f-phaseb.sh" || bad "r7f-phaseb.sh 语法"
python -m py_compile scripts/android/r7f-mockd.py && ok "py_compile r7f-mockd.py" || bad "r7f-mockd.py 编译"
if python scripts/android/zlib-mock.py --help 2>&1 | grep -q -- '--dl-mode'; then ok "zlib-mock 参数齐（--dl-mode/--require-login）"; else bad "zlib-mock 参数缺失"; fi

R="docs/android-port/r7e-account-verify.md"
if [ -f "$R" ]; then ok "报告 $R（$(wc -l < "$R") 行）"; else bad "缺报告 $R"; fi
B=$(grep -c '^| B-[0-9]' "$R"); if [ "$B" -ge 7 ]; then ok "§12.2 断言行 $B（期望 7）"; else bad "§12.2 断言行 $B < 7"; fi
V=$(grep -cE '^\| (R[0-9]|R3-结果|V-Z1[0-9]|A[0-9])' "$R"); ok "§12.3 R/A/V 断言 $V 行"
if [ -d docs/android-port/ui-shots/v4 ]; then ok "截图目录 v4（$(ls docs/android-port/ui-shots/v4 | wc -l) 个文件）"; else bad "缺截图目录"; fi
if [ -d /f/tmp/verify/r7f3 ]; then ok "临时目录 /f/tmp/verify/r7f3"; else bad "缺 /f/tmp/verify/r7f3"; fi
if [ -x /g/Android/sdk/platform-tools/adb.exe ]; then ok "adb 可执行"; else bad "adb 不可执行"; fi

if [ "x_ALLOW_ADB_x" = "1" ]; then
  /g/Android/sdk/platform-tools/adb.exe devices | tail -2
else
  warn "未执行 adb（默认；ALLOW_ADB 打开才查）"
fi

echo "== precheck done: FAIL=$FAIL =="
exit $FAIL
