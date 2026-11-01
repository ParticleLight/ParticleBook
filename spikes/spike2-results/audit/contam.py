#!/usr/bin/env python
# contam.py — 独立复验污染检测（pdf-verify / task-6）
# 用法: python contam.py --pkg app.particlebook [--allow-pids 8494,8764] FILE [FILE...]
# 判定: (a) 只出现目标 package 的进程/am start；(b) 无 ANR/Input dispatch timeout/FATAL；
#       (c) 无第二个 RUNMETA 运行头（跨 run 混入）；(d) 多 pid 时须全部在 --allow-pids 内（否则 INVALID）。
import argparse, json, re, sys

PKG_PROC = re.compile(r"Start proc \d+:(?P<pkg>[\w.]+)/")
PKG_AM = re.compile(r"cmp=(?P<comp>[\w./]+?)(?:\s|$)")
LINE_PID = re.compile(r"^\d\d-\d\d\s+\d\d:\d\d:\d\d\.\d+\s+(?P<pid>\d+)\s+(?P<tid>\d+)")
ANR_PAT = [re.compile(p) for p in (
    r"ANR in ", r"Input dispatching timed out", r"FATAL EXCEPTION",
    r"Application Not Responding")]
RUNMETA = re.compile(r"RUNMETA|RUNENV|RUNBEGIN")


def scan(path, pkg, allow_pids):
    pids, foreign_proc, foreign_am, anrs, metas = set(), [], [], [], []
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            for i, line in enumerate(f, 1):
                m = LINE_PID.match(line)
                if m:
                    pids.add(m.group("pid"))
                for m2 in PKG_PROC.finditer(line):
                    if m2.group("pkg") != pkg:
                        foreign_proc.append((i, m2.group("pkg")))
                for m3 in PKG_AM.finditer(line):
                    comp = m3.group("comp").split("/")[0]
                    if comp and comp != pkg and ("ActivityTaskManager" in line or "START u0" in line):
                        foreign_am.append((i, comp))
                for pat in ANR_PAT:
                    if pat.search(line):
                        anrs.append((i, pat.pattern))
                        break
                if RUNMETA.search(line):
                    metas.append((i, line.strip()[:120]))
    except FileNotFoundError:
        return {"file": path, "error": "missing", "verdict": "INVALID", "flags": ["missing"]}
    flags, warns = [], []
    extra_pids = sorted(pids - set(allow_pids)) if allow_pids else sorted(pids)
    if len(pids) > 1:
        warns.append("multi_pid:" + ",".join(sorted(pids)))
    if allow_pids and extra_pids:
        flags.append("unexpected_pid:" + ",".join(extra_pids))
    if foreign_proc:
        flags.append("foreign_proc:" + ",".join(sorted({p for _, p in foreign_proc}))[:200])
    if foreign_am:
        flags.append("foreign_start:" + ",".join(sorted({p for _, p in foreign_am}))[:200])
    if anrs:
        flags.append("anr_or_fatal:%d" % len(anrs))
    if len(metas) > 1:
        flags.append("second_runmeta:%d" % len(metas))
    return {"file": path, "pids": sorted(pids), "n_runmeta": len(metas),
            "anr_samples": anrs[:3], "foreign_proc": foreign_proc[:3],
            "foreign_am": foreign_am[:3],
            "flags": flags, "warnings": warns,
            "verdict": "INVALID" if flags else "VALID"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pkg", default="app.particlebook")
    ap.add_argument("--allow-pids", default="", help="逗号分隔的允许 pid（多 pid 合法时用）")
    ap.add_argument("files", nargs="+")
    a = ap.parse_args()
    allow = {p.strip() for p in a.allow_pids.split(",") if p.strip()}
    out = [scan(f, a.pkg, allow) for f in a.files]
    print(json.dumps(out, ensure_ascii=False, indent=1))
    sys.exit(0 if all(o.get("verdict") == "VALID" for o in out) else 1)


if __name__ == "__main__":
    main()
