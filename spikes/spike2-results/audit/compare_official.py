#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""compare_official.py — 官方 summary 复现审计（pdf-verify task-6，独立 parser）

对每个 render-r* 批次：
  1) 独立解析 raw -> 复现 parse.py 的 drop 规则（每 (run,app,scenario,phase) 丢第一条「未标 cold」样本）；
  2) 与 summary/<batch>.summary.json 的 groups 比 n / p50 / p95 / min / max；
  3) 同时给「不 drop」口径，量化 drop 规则本身对数字的影响（报告用）。
用法：
  python spikes/spike2-results/audit/compare_official.py --batch render-r1 \
    --raw-glob 'spikes/spike2-results/raw/render-r1-run*.log' \
    --summary spikes/spike2-results/summary/render-r1.summary.json
"""
import argparse, glob, json, os, re, statistics
from collections import defaultdict

PB = re.compile(r"PBPERF\|scenario=(\w+)\|phase=(\w+)\|page=(-?\d+)\|w=(-?\d+)\|h=(-?\d+)\|ms=(-?\d+(?:\.\d+)?)\|extra=([^\s|]*)")


def p95(vals):
    xs = sorted(vals)
    k = int(-(-0.95 * len(xs) // 1))
    return xs[min(k, len(xs)) - 1]


def st(vals):
    xs = sorted(vals)
    return dict(n=len(xs), mn=xs[0], p50=statistics.median(xs), p95=p95(xs), mx=xs[-1])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--batch", required=True)
    ap.add_argument("--raw-glob", required=True)
    ap.add_argument("--summary", required=True)
    ap.add_argument("--out", default="")
    a = ap.parse_args()
    rows = []
    for f in sorted(glob.glob(a.raw_glob)):
        for ln in open(f, encoding="utf-8", errors="replace"):
            m = PB.search(ln)
            if not m:
                continue
            sc, ph = m.group(1), m.group(2)
            if ph == "pss":
                continue
            if "error:" in m.group(7):
                continue
            rows.append(dict(run=os.path.basename(f), sc=sc, ph=ph, ms=float(m.group(6)),
                             cold=("cold:1" in m.group(7)), order=len(rows)))
    groups = defaultdict(list)
    for r in rows:
        groups[(r["run"], r["sc"], r["ph"])].append(r)
    dropped = 0
    for k, mem in groups.items():
        mem.sort(key=lambda x: x["order"])
        mem = [r for r in mem if not r["cold"]]
        for r in mem[:1]:
            r["dropped"] = True
            dropped += 1
    kept = [r for r in rows if not r.get("dropped")]
    def pool(rs):
        g = defaultdict(list)
        for r in rs:
            g[(r["sc"], r["ph"])].append(r["ms"])
        return {k: st(v) for k, v in g.items()}
    mine_drop, mine_all = pool(kept), pool(rows)
    summ = json.load(open(a.summary, encoding="utf-8"))
    sg = {(g["scenario"], g["phase"]): g for g in summ["groups"]}
    out = ["# 官方 summary 复现审计 · %s" % a.batch, "",
           "原始 PBPERF（含 check，不含 pss/error）：%d；按 drop 规则剔除：%d；保留：%d" % (len(rows), dropped, len(kept)), "",
           "| scenario/phase | summary n | summary p50 | 复算(drop) n | 复算(drop) p50 | dP50 | 复算(不drop) p50 | dP50 vs 不drop | 判定 |",
           "|---|---|---|---|---|---|---|---|---|"]
    worst = 0.0
    for k in sorted(mine_drop):
        s = sg.get(k); md = mine_drop[k]; ma = mine_all.get(k, {})
        if not s:
            out.append("| %s/%s | — | — | %d | %.3f | — | %.3f | — | **缺 summary 组** |" % (k[0], k[1], md["n"], md["p50"], ma.get("p50", float("nan"))))
            continue
        d1 = 100.0 * (md["p50"] - s["p50"]) / s["p50"] if s["p50"] else 0.0
        d2 = 100.0 * (ma["p50"] - s["p50"]) / s["p50"] if s["p50"] else 0.0
        worst = max(worst, abs(d1))
        verdict = "PASS" if abs(d1) <= 5 else ("WARN" if abs(d1) <= 10 else "FAIL")
        out.append("| %s/%s | %d | %.3f | %d | %.3f | %+.2f%% | %.3f | %+.2f%% | %s |" % (k[0], k[1], s["n"], s["p50"], md["n"], md["p50"], d1, ma.get("p50", float("nan")), d2, verdict))
    out.append("")
    out.append("最大 dP50（同口径）= %.2f%%。" % worst)
    out.append("")
    out.append("> dP50=复算(drop)-summary；dP50 vs 不drop 只量化 drop 规则本身影响，不是错误。")
    txt = "\n".join(out)
    print(txt)
    if a.out:
        open(a.out, "w", encoding="utf-8").write(txt + "\n")
        print("\n[written] " + a.out)


if __name__ == "__main__":
    main()

