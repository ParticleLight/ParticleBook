#!/usr/bin/env python
"""compare.py — 独立复算 vs bench parse.py 同输入差异表（pdf-verify / task-6）

用法: python compare.py --out DIR [--drop-first 0] RAWLOG [RAWLOG...]
对每个 raw log：先调 bench parse.py 产出 stats json（只读使用，不改 bench 代码），
再用本审计 parser（recompute.py 的 scan/summarize）重算，按 (scenario,phase) 对齐比差。

输出: DIR/compare-<stem>.md + DIR/compare-<stem>.csv（stdout 同步打印 md）
判定: |diff|<=5% PASS；5-10% 需解释；>10% FAIL（复算口径已在 audit.md §2 说明）。
"""
import argparse, csv, importlib.util, json, os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("recompute", os.path.join(HERE, "recompute.py"))
rc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rc)


def bench_parse(parse_py, raw, outdir, app, drop_first):
    name = "cmp-" + os.path.splitext(os.path.basename(raw))[0]
    cmd = [sys.executable, parse_py, "--out", outdir, "--name", name,
           "--app", app, "--drop-first", str(drop_first), raw]
    # bench parse.py 的 --out 需要真实目录；路径用 Windows 形式
    subprocess.run(cmd, check=True, capture_output=True, text=True, encoding="utf-8", errors="replace")
    js = os.path.join(outdir, name + ".stats.json")
    with open(js, encoding="utf-8") as f:
        return json.load(f), js


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="F:/tmp/verify/compare-out")
    ap.add_argument("--parse-py", default="F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/scripts/android/spike2/parse.py")
    ap.add_argument("--app", default="app.pb.spike2t")
    ap.add_argument("--drop-first", type=int, default=0)
    ap.add_argument("logs", nargs="+")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    for raw in a.logs:
        raw = raw.replace("\\", "/")
        bench, js = bench_parse(a.parse_py, raw, a.out, a.app, a.drop_first)
        events, dones, times, opens, pages = rc.scan([raw])
        groups = {}
        for e in events:
            groups.setdefault((e["scenario"], e["phase"]), []).append(e)
        for t in times:
            groups.setdefault(("PBTIME", t["phase"]), []).append({
                "file": t["file"], "line": t["line"], "scenario": "PBTIME", "phase": t["phase"],
                "page": -1, "w": -1, "h": -1, "ms": t["ms"], "extra": ""})
        mine = {}
        for (sc, ph), evs in groups.items():
            if ph == "pss":
                continue
            vals = [e["ms"] for e in evs if not e["extra"].startswith("error:")]
            seen, cw = {}, []
            for e in evs:
                if e["extra"].startswith("error:"):
                    continue
                idx = seen.get(e["file"], 0); seen[e["file"]] = idx + 1
                cw.append((e["file"], idx, e["ms"]))
            cold, warm = rc.cold_warm(cw)
            mine[(sc, ph)] = {"n": len(vals), "all": rc.summarize(vals),
                              "cold": rc.summarize(cold), "warm": rc.summarize(warm)}

        rows = []
        bench_by = {}
        for g in bench.get("groups", []):
            if g["phase"] == "pss":
                continue
            bench_by[(g["scenario"], g["phase"])] = g
        for key in sorted(set(mine) | set(bench_by)):
            m = mine.get(key)
            b = bench_by.get(key)
            if not m or not b:
                rows.append({"scenario": key[0], "phase": key[1],
                             "n_mine": m["n"] if m else 0, "n_bench": b["n"] if b else 0,
                             "status": "ONLY_MINE" if m else "ONLY_BENCH"})
                continue
            am, ab = m["all"], b
            d50 = (m["all"]["p50"] - ab["median"]) / ab["median"] * 100 if ab["median"] else None
            d95 = (m["all"]["p95_nearest"] - ab["p95"]) / ab["p95"] * 100 if ab["p95"] else None
            worst = max(abs(d50 or 0), abs(d95 or 0))
            rows.append({
                "scenario": key[0], "phase": key[1],
                "n_mine": m["n"], "n_bench": ab["n"],
                "median_mine": am["p50"], "median_bench": ab["median"],
                "diff_p50_pct": None if d50 is None else round(d50, 2),
                "p95near_mine": am["p95_nearest"], "p95_bench": ab["p95"],
                "diff_p95_pct": None if d95 is None else round(d95, 2),
                "min_mine": am["min"], "min_bench": ab["min"],
                "max_mine": am["max"], "max_bench": ab["max"],
                "cold_n": m["cold"].get("n", 0), "warm_n": m["warm"].get("n", 0),
                "verdict": "PASS" if worst <= 5 else ("WARN" if worst <= 10 else "FAIL"),
            })

        stem = os.path.splitext(os.path.basename(raw))[0]
        md = ["# 复算 vs bench parse.py 同输入差异 · %s" % stem,
              "",
              "- raw: `%s`" % raw,
              "- bench: `scripts/android/spike2/parse.py --drop-first %d`（只读调用，未改其代码）" % a.drop_first,
              "- mine: `recompute.py`（独立实现，median=bootstrap 95%CI；p50=statistics.median；p95=nearest-rank）",
              "- 差异 = (mine - bench)/bench*100%%；判定 PASS<=5%% / WARN 5-10%% / FAIL>10%%",
              "",
              "| scen | phase | n(mine/bench) | median mine/bench | ΔP50% | P95near mine/bench | ΔP95% | min mine/bench | max mine/bench | cold/warm(n) | 判定 |",
              "|---|---|---|---|---|---|---|---|---|---|---|"]
        for r in rows:
            if r.get("status"):
                md.append("| %s | %s | %s/%s | - | - | - | - | - | - | - | %s |" % (
                    r["scenario"], r["phase"], r["n_mine"], r["n_bench"], r["status"]))
                continue
            def f3(x):
                return "-" if x is None else "%.3f" % x
            def fp(x):
                return "-" if x is None else "%+.2f%%" % x
            md.append("| %s | %s | %s/%s | %s/%s | %s | %s/%s | %s | %s/%s | %s/%s | %d/%d | %s |" % (
                r["scenario"], r["phase"], r["n_mine"], r["n_bench"],
                f3(r["median_mine"]), f3(r["median_bench"]), fp(r["diff_p50_pct"]),
                f3(r["p95near_mine"]), f3(r["p95_bench"]), fp(r["diff_p95_pct"]),
                f3(r["min_mine"]), f3(r["min_bench"]), f3(r["max_mine"]), f3(r["max_bench"]),
                r["cold_n"], r["warm_n"], r["verdict"]))
        md_path = os.path.join(a.out, "compare-%s.md" % stem)
        with open(md_path, "w", encoding="utf-8") as f:
            f.write("\n".join(md) + "\n")
        csv_path = os.path.join(a.out, "compare-%s.csv" % stem)
        if rows:
            keys = ["scenario", "phase", "n_mine", "n_bench", "median_mine", "median_bench",
                    "diff_p50_pct", "p95near_mine", "p95_bench", "diff_p95_pct",
                    "min_mine", "min_bench", "max_mine", "max_bench",
                    "cold_n", "warm_n", "verdict", "status"]
            with open(csv_path, "w", encoding="utf-8", newline="") as f:
                w = csv.DictWriter(f, fieldnames=keys, extrasaction="ignore")
                w.writeheader()
                for r in rows:
                    w.writerow(r)
        print("\n".join(md))
        print("\n[compare] md=%s csv=%s bench_stats=%s\n" % (md_path, csv_path, js))


if __name__ == "__main__":
    main()
