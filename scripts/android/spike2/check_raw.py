#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check_raw.py — 正式集 raw 完整性断言（Lead 20:31 的三道断言，任一失败 -> 该 rep 不进 CSV）。

断言：
  1) 时间窗：所有被测记录（PBPERF / PBPdf）的 logcat 时间戳 >= 本 rep 起始设备时间 - 1s；
     出现更早的行 => 说明 logcat -c 失败或抓到了上一轮的残留。
  2) PID 唯一：被测记录只能来自同一个 pid（app 主进程），出现第二个 pid => 污染。
  3) 结构行：raw.struct 里必须有 AMSTART 与 RUNMETA，且至少 min 条被测记录。

用法：
  python check_raw.py --rt raw/x.log.rt --struct raw/x.log.struct \
      --dev-year 2026 --dev-epoch 1762xxxxxx --min-records 1 [--tags PBPERF,PBPdf]

输出（单行，机器可读）：
  CHECK|result=PASS|time_ok=1|pid_ok=1|pids=9036|n_records=75|early=0|struct_ok=1|first_epoch=..|last_epoch=..
退出码：0 全部通过；2 有失败（fail=<原因，逗号分隔>）。
"""
import argparse
import calendar
import os
import re
import sys

DEFAULT_TAGS = ("PBPERF", "PBPdf")


def build_regex(tags):
    tag_group = "|".join(re.escape(t) for t in tags)
    return re.compile(
        r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d\d\d)\s+(\d+)\s+(\d+)\s+[VDIWEFA]\s+("
        + tag_group
        + r")\s*:"
    )


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--rt", required=True)
    ap.add_argument("--struct", required=True)
    ap.add_argument("--dev-year", type=int, required=True)
    ap.add_argument("--dev-epoch", type=int, required=True,
                    help="本 rep 起始的设备 epoch 秒（am start 前抓的 date +%s）")
    ap.add_argument("--min-records", type=int, default=1)
    ap.add_argument("--tags", default=",".join(DEFAULT_TAGS))
    args = ap.parse_args(argv)

    tags = tuple(t.strip() for t in args.tags.split(",") if t.strip())
    pattern = build_regex(tags)

    pids = set()
    n = 0
    early = 0
    first = None
    last = None
    if os.path.exists(args.rt):
        with open(args.rt, "r", encoding="utf-8", errors="replace") as fh:
            for line in fh:
                m = pattern.match(line)
                if not m:
                    continue
                mon, day, hh, mm, ss, ms, pid, _tid, _tag = m.groups()
                n += 1
                pids.add(pid)
                epoch = calendar.timegm(
                    (args.dev_year, int(mon), int(day), int(hh), int(mm), int(ss))
                ) + int(ms) / 1000.0
                first = epoch if first is None else min(first, epoch)
                last = epoch if last is None else max(last, epoch)
                if epoch + 1.0 < args.dev_epoch:
                    early += 1

    struct_text = ""
    if os.path.exists(args.struct):
        with open(args.struct, "r", encoding="utf-8", errors="replace") as fh:
            struct_text = fh.read()
    struct_ok = ("AMSTART|" in struct_text) and ("RUNMETA|" in struct_text)

    time_ok = early == 0 and n > 0
    pid_ok = len(pids) <= 1 and n > 0
    count_ok = n >= args.min_records

    fails = []
    if not struct_ok:
        fails.append("struct_missing")
    if n < args.min_records:
        fails.append("no_records")
    if early:
        fails.append("early_lines=%d" % early)
    if len(pids) > 1:
        fails.append("multi_pid=%s" % ",".join(sorted(pids)))
    result = "PASS" if not fails else "FAIL"

    print(
        "CHECK|result=%s|time_ok=%d|pid_ok=%d|pids=%s|n_records=%d|early=%d|"
        "struct_ok=%d|count_ok=%d|first_epoch=%s|last_epoch=%s|fail=%s"
        % (
            result, int(time_ok), int(pid_ok), ",".join(sorted(pids)) or "-",
            n, early, int(struct_ok), int(count_ok),
            "-" if first is None else ("%.3f" % first),
            "-" if last is None else ("%.3f" % last),
            ",".join(fails) or "-",
        )
    )
    return 0 if result == "PASS" else 2


if __name__ == "__main__":
    sys.exit(main())
