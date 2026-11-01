#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""verify_batch.py — 正式集 raw 批量审计（pdf-verify task-6）

对每份 raw 输出：运行头(RUNENV/apk_md5/scenario)、PBPERF 数、场景覆盖、PBDONE、pid 集合、
时间窗、跨 app 家族(S*/T*)混入、以及独立复算的 (scenario,phase) 统计表。
用法：
  python spikes/spike2-results/audit/verify_batch.py --out /f/tmp/verify/batch.md       spikes/spike2-results/raw/render-r*.log spikes/spike2-results/raw/tiles-*.log
"""
import argparse, glob, json, os, re, subprocess, sys
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
RECOMPUTE = os.path.join(HERE, "recompute.py")

TS = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d)\s+(\d+)\s+(\d+)\s+[VDIWEFAS]\s+(\S+?)\s*:")
PB = re.compile(r"PBPERF\|scenario=(\w+)\|phase=(\w+)\|page=(-?\d+)\|w=(-?\d+)\|h=(-?\d+)\|ms=(-?\d+(?:\.\d+)?)\|extra=([^\s|]*)")


def parse_runenv(lines):
    envs = []
    for ln in lines:
        if ln.startswith("RUNENV|"):
            kv = {}
            for part in ln.split("|")[1:]:
                if "=" in part:
                    k, v = part.split("=", 1)
                    kv[k] = v
            envs.append(kv)
    return envs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("files", nargs="+")
    ap.add_argument("--out", default="")
    args = ap.parse_args()
    files = []
    for pat in args.files:
        files.extend(sorted(glob.glob(pat)))
    files = [f for f in files if os.path.isfile(f) and os.path.getsize(f) > 0]
    out = []
    out.append("# 批量审计报告（verify_batch.py）\n")
    for f in files:
        try:
            lines = open(f, encoding="utf-8", errors="replace").read().splitlines()
        except OSError as e:
            out.append("- %s: 读取失败 %r" % (f, e)); continue
        envs = parse_runenv(lines)
        scen = Counter()
        pids = Counter()
        first_ts = last_ts = ""
        s_fam = t_fam = False
        for ln in lines:
            m = TS.match(ln)
            if m:
                first_ts = first_ts or m.group(1)
                last_ts = m.group(1)
            mb = PB.search(ln)
            if mb:
                scen[mb.group(1)] += 1
                if mb.group(1).startswith("S"): s_fam = True
                if mb.group(1).startswith("T"): t_fam = True
            if "PBPERF|" in ln or "PBPdf" in ln:
                m2 = TS.match(ln)
                if m2: pids[m2.group(2)] += 1
        pbdone = [ln for ln in lines if "PBDONE|" in ln]
        sizes = sorted({(int(m.group(4)), int(m.group(5))) for m in (PB.search(ln) for ln in lines) if m})
        out.append("## %s" % os.path.basename(f))
        out.append("- 大小: %d B, PBPERF: %d, PBDONE: %d, 时间窗: %s .. %s" % (os.path.getsize(f), sum(scen.values()), len(pbdone), first_ts or "-", last_ts or "-"))
        out.append("- RUNENV: %s" % ("; ".join("app=%s run_id=%s apk_md5=%s scen=%s" % (e.get("app"), e.get("run_id"), e.get("apk_md5"), e.get("scenario")) for e in envs) if envs else "**缺失（A2 FAIL）**"))
        out.append("- 场景: %s" % dict(sorted(scen.items())))
        out.append("- pid: %s" % dict(pids.most_common(8)))
        out.append("- 尺寸: %s" % (", ".join("%dx%d" % s for s in sizes) if sizes else "-"))
        flags = []
        if len(envs) != 1: flags.append("RUNENV_COUNT=%d" % len(envs))
        if len(pids) > 1: flags.append("MULTI_PID")
        if s_fam and t_fam: flags.append("CROSS_APP_BUFFER(S*+T*)")
        out.append("- 标记: %s" % (", ".join(flags) if flags else "none"))
        if pbdone:
            out.append("- PBDONE: %s" % " | ".join(ln.split("PBPERF  : ")[-1].strip() for ln in pbdone[:12]))
        # 独立复算
        try:
            r = subprocess.run([sys.executable, RECOMPUTE, "--json", f], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120)
            data = json.loads(r.stdout)
        except Exception as e:  # noqa: BLE001
            out.append("- 复算失败: %r" % (e,)); out.append(""); continue
        out.append("")
        out.append("| scenario | phase | n | min | P50 | P95near | max | cold n/P50 | warm n/P50 |")
        out.append("|---|---|---|---|---|---|---|---|---|")
        for g in data.get("groups", []):
            a = g["all"]; c = g.get("cold", {}); w = g.get("warm", {})
            out.append("| %s | %s | %d | %.3f | %.3f | %.3f | %.3f | %s/%s | %s/%s |" % (
                g["scenario"], g["phase"], a["n"], a["min"], a["p50"], a["p95_nearest"], a["max"],
                c.get("n", 0), ("%.3f" % c["p50"]) if c.get("p50") is not None else "-",
                w.get("n", 0), ("%.3f" % w["p50"]) if w.get("p50") is not None else "-"))
        out.append("")
    txt = "\n".join(out)
    print(txt)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as fh:
            fh.write(txt)
        print("\n[written] " + args.out)


if __name__ == "__main__":
    main()

