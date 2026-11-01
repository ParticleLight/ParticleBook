#!/usr/bin/env bash
# scripts/android/spike2/tests/run-tests.sh
# 从任意目录调用都可运行（按脚本自身位置推仓库根）：
#   bash scripts/android/spike2/tests/run-tests.sh [unittest 额外参数]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"
PYTHON_BIN="${PYTHON:-python}"

cd "${REPO_ROOT}"
echo "[run-tests] repo root: ${REPO_ROOT}"
"${PYTHON_BIN}" -m unittest discover -s scripts/android/spike2/tests -v "$@"
