#!/usr/bin/env python
"""Independent recompute for Spike2 (pdf-verify / task-6).

Does NOT import or reuse pdf-bench parse.py. Input = raw logcat text only.

Modes
  PBPERF mode (default): recompute app.pb.spike2 / app.pb.spike2t scenarios.
  --p0 mode: recompute P0 baseline (PBPdf lines from app.particlebook).

Outputs per (scenario, phase): n, cold/warm split, min, P50, bootstrap 95% CI of P50,
P95 (nearest-rank AND linear), max, mean; plus PBDONE / PBTIME / error samples.

Cold/warm definition (documented for audit): samples are ordered by (file, line);
within each file the FIRST sample of a (scenario, phase) group is "cold" (first touch /
cold caches), the rest are "warm". When P0 mode: first pdf_page per file is cold.
This is an audit-side definition because the protocol has no warmup marker in the log
schema (see audit.md §D).

Usage
  python recompute.py [--drop-first N] [--json] [--p0] file1.log [file2.log ...]
"""
import argparse
import json
import math
import random
import re
import sys
from statistics import median

PBPERF = re.compile(
    r"PBPERF\|scenario=([A-Za-z0-9_]+)\|phase=([A-Za-z0-9_]+)\|page=(-?\d+)\|"
    r"w=(-?\d+)\|h=(-?\d+)\|ms=([0-9.]+)\|extra=(\S*)"
)
PBDONE = re.compile(r"PBDONE\|scenario=([A-Za-z0-9_]+)\|runs=(\d+)\|extra=(\S*)")
PBTIME = re.compile(r"PBTIME\|app=(\S+)\|phase=(\S+)\|ms=([0-9.]+)")
PDF_OPEN = re.compile(r"pdf_open pages=(\d+) docBytes=(\d+) took=([0-9.]+)ms")
PDF_PAGE = re.compile(r"pdf_page page=(\d+) (\d+)x(\d+) render=([0-9.]+)ms encode=([0-9.]+)ms bytes=(\d+)")
TS = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s+(\d+)\s+(\d+)")


def p95_nearest(xs):
    if not xs:
        return float("nan")
    s = sorted(xs)
    k = max(1, math.ceil(0.95 * len(s)))
    return s[k - 1]


def p95_linear(xs):
    if not xs:
        return float("nan")
    s = sorted(xs)
    if len(s) == 1:
        return s[0]
    pos = 0.95 * (len(s) - 1)
    lo, hi = int(math.floor(pos)), int(math.ceil(pos))
    if lo == hi:
        return s[lo]
    return s[lo] + (s[hi] - s[lo]) * (pos - lo)


def boot_ci(vals, stat=median, n_boot=2000, ci=95, seed=12345):
    """Nonparametric bootstrap percentile CI for a statistic. Needs n>=5 to be meaningful."""
    if len(vals) < 5:
        return None, None
    rnd = random.Random(seed)
    n = len(vals)
    stats = []
    for _ in range(n_boot):
        sample = [vals[rnd.randrange(n)] for _ in range(n)]
        stats.append(stat(sample))
    stats.sort()
    lo = max(0, int(round((50 - ci / 2) / 100 * n_boot)) - 1)
    hi = min(n_boot - 1, int(round((50 + ci / 2) / 100 * n_boot)) - 1)
    return stats[lo], stats[hi]


def r3(x):
    return None if x is None else round(x, 3)


def summarize(vals):
    if not vals:
        return {"n": 0}
    lo, hi = boot_ci(vals)
    return {
        "n": len(vals), "min": r3(min(vals)), "p50": r3(median(vals)),
        "ci50_lo": r3(lo), "ci50_hi": r3(hi),
        "p95_nearest": r3(p95_nearest(vals)), "p95_linear": r3(p95_linear(vals)),
        "max": r3(max(vals)), "mean": r3(sum(vals) / len(vals)),
    }


def scan(paths):
    events, dones, times = [], [], []
    opens, pages = [], []
    for p in paths:
        with open(p, "r", encoding="utf-8", errors="replace") as f:
            for lineno, line in enumerate(f, 1):
                for m in PBPERF.finditer(line):
                    events.append({"file": p, "line": lineno, "scenario": m.group(1),
                                   "phase": m.group(2), "page": int(m.group(3)),
                                   "w": int(m.group(4)), "h": int(m.group(5)),
                                   "ms": float(m.group(6)), "extra": m.group(7)})
                for m in PBDONE.finditer(line):
                    dones.append({"file": p, "line": lineno, "scenario": m.group(1),
                                  "runs": int(m.group(2)), "extra": m.group(3)})
                for m in PBTIME.finditer(line):
                    times.append({"file": p, "line": lineno, "app": m.group(1),
                                  "phase": m.group(2), "ms": float(m.group(3))})
                mo = PDF_OPEN.search(line)
                if mo:
                    tm = TS.match(line)
                    opens.append({"file": p, "line": lineno,
                                  "ts": tm.group(1) if tm else "",
                                  "pages": int(mo.group(1)), "docBytes": int(mo.group(2)),
                                  "took": float(mo.group(3))})
                mp = PDF_PAGE.search(line)
                if mp:
                    tm = TS.match(line)
                    pages.append({"file": p, "line": lineno,
                                  "ts": tm.group(1) if tm else "",
                                  "page": int(mp.group(1)), "w": int(mp.group(2)), "h": int(mp.group(3)),
                                  "render": float(mp.group(4)), "encode": float(mp.group(5)),
                                  "bytes": int(mp.group(6))})
    return events, dones, times, opens, pages


def cold_warm(vals_all):
    """vals_all = list of (file, order_index, value). cold = order_index==0 within file."""
    cold = [v for (f, idx, v) in vals_all if idx == 0]
    warm = [v for (f, idx, v) in vals_all if idx != 0]
    return cold, warm


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--drop-first", type=int, default=0,
                    help="audit-side warmup rule: drop first N valid samples per (scenario,phase)")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--p0", action="store_true", help="recompute P0 baseline PBPdf lines")
    ap.add_argument("files", nargs="+")
    args = ap.parse_args()

    events, dones, times, opens, pdf_pages = scan(args.files)

    if args.p0:
        out = {"files": args.files, "p0": {"opens": opens, "pages": pdf_pages}}
        by_page = {}
        for e in pdf_pages:
            by_page.setdefault(e["page"], []).append(e)
        groups = {}
        for page, evs in sorted(by_page.items()):
            idx = {id(e): i for i, e in enumerate(evs)}
            render_all = [(e["file"], idx[id(e)], e["render"]) for e in evs]
            enc_all = [(e["file"], idx[id(e)], e["encode"]) for e in evs]
            cold_r, warm_r = cold_warm(render_all)
            cold_e, warm_e = cold_warm(enc_all)
            groups["page=%d" % page] = {
                "render": {"all": summarize([e["render"] for e in evs]),
                           "cold": summarize(cold_r), "warm": summarize(warm_r)},
                "encode": {"all": summarize([e["encode"] for e in evs]),
                           "cold": summarize(cold_e), "warm": summarize(warm_e)},
                "bytes": sorted({e["bytes"] for e in evs}),
                "ts": [e["ts"] for e in evs],
            }
        agg_render = [e["render"] for e in pdf_pages]
        agg_encode = [e["encode"] for e in pdf_pages]
        out["p0"]["by_page"] = groups
        out["p0"]["all_pages"] = {"render": summarize(agg_render), "encode": summarize(agg_encode),
                                  "n": len(pdf_pages)}
        out["p0"]["opens_summary"] = summarize([o["took"] for o in opens])
        if args.json:
            print(json.dumps(out, ensure_ascii=False, indent=1))
        else:
            print("# P0 independent recompute files=%s" % ",".join(args.files))
            for o in opens:
                print("open  ts=%s pages=%d docBytes=%d took=%.3fms" %
                      (o["ts"], o["pages"], o["docBytes"], o["took"]))
            print("%-8s %5s %9s %9s %9s %9s %9s %9s" % ("phase", "n", "min", "P50", "CI50lo", "CI50hi", "P95near", "max"))
            for key, s in ([("open", summarize([o["took"] for o in opens])),
                            ("render_all", summarize(agg_render)),
                            ("encode_all", summarize(agg_encode))]):
                if s["n"]:
                    print("%-8s %5d %9.3f %9.3f %9s %9s %9.3f %9.3f" % (
                        key, s["n"], s["min"], s["p50"],
                        "-" if s["ci50_lo"] is None else "%.3f" % s["ci50_lo"],
                        "-" if s["ci50_hi"] is None else "%.3f" % s["ci50_hi"],
                        s["p95_nearest"], s["max"]))
            for page, g in groups.items():
                for ph in ("render", "warm"):
                    pass
                for ph, s in (("render_all", g["render"]["all"]), ("render_warm", g["render"]["warm"]),
                              ("encode_all", g["encode"]["all"]), ("encode_warm", g["encode"]["warm"])):
                    if not s["n"]:
                        continue
                    print("%-8s %5d %9.3f %9.3f %9s %9s %9.3f %9.3f" % (
                        page + "/" + ph, s["n"], s["min"], s["p50"],
                        "-" if s["ci50_lo"] is None else "%.3f" % s["ci50_lo"],
                        "-" if s["ci50_hi"] is None else "%.3f" % s["ci50_hi"],
                        s["p95_nearest"], s["max"]))
        return

    groups, pss_rows = {}, []
    for e in events:
        if e["phase"] == "pss":
            pss_rows.append(e)
            continue
        groups.setdefault((e["scenario"], e["phase"]), []).append(e)
    for t in times:
        groups.setdefault(("PBTIME", t["phase"]), []).append({
            "file": t["file"], "line": t["line"], "scenario": "PBTIME", "phase": t["phase"],
            "page": -1, "w": -1, "h": -1, "ms": t["ms"], "extra": ""})

    results = []
    for (sc, ph), evs in sorted(groups.items()):
        errs = [e for e in evs if e["extra"].startswith("error:")]
        vals_all = [e["ms"] for e in evs if not e["extra"].startswith("error:")]
        vals = vals_all[args.drop_first:] if args.drop_first else vals_all
        # cold/warm: first valid sample per file for this group
        seen = {}
        cw = []
        for e in evs:
            if e["extra"].startswith("error:"):
                continue
            idx = seen.get(e["file"], 0)
            seen[e["file"]] = idx + 1
            cw.append((e["file"], idx, e["ms"]))
        cold, warm = cold_warm(cw)
        rec = {"scenario": sc, "phase": ph, "n_raw": len(evs),
               "n_valid": len(vals), "n_error": len(errs),
               "n_dropped": max(0, len(vals_all) - len(vals)),
               "pages": sorted({e["page"] for e in evs}),
               "sizes": sorted({(e["w"], e["h"]) for e in evs}),
               "extras_sample": sorted({e["extra"] for e in evs})[:6],
               "error_samples": sorted({e["extra"] for e in errs})[:6],
               "all": summarize(vals), "cold": summarize(cold), "warm": summarize(warm),
               "low_confidence": len(vals) < 5}
        results.append(rec)

    if args.json:
        print(json.dumps({"files": args.files, "drop_first": args.drop_first,
                          "groups": results, "done": dones, "ptime": times,
                          "pss": [{"file": e["file"], "line": e["line"], "extra": e["extra"]} for e in pss_rows]},
                         ensure_ascii=False, indent=1))
        return

    print("# independent recompute  files=%s  drop_first=%d" % (",".join(args.files), args.drop_first))
    hdr = "%-6s %-14s %5s %4s %4s %9s %9s %9s %9s %9s %9s %5s" % (
        "scen", "phase", "n_val", "nC", "nW", "min", "P50", "CI50lo", "CI50hi", "P95near", "max", "LC")
    print(hdr)
    for r in results:
        a = r["all"]
        def f(x):
            return "-" if x is None else ("%.3f" % x)
        print("%-6s %-14s %5d %4d %4d %9s %9s %9s %9s %9s %9s %5s" % (
            r["scenario"], r["phase"], r["n_valid"], r["cold"].get("n", 0), r["warm"].get("n", 0),
            f(a.get("min")), f(a.get("p50")), f(a.get("ci50_lo")), f(a.get("ci50_hi")),
            f(a.get("p95_nearest")), f(a.get("max")), "YES" if r["low_confidence"] else ""))
        if r["error_samples"]:
            print("        errors: %s" % r["error_samples"])
    print("\nPBDONE:")
    for d in dones:
        print("  %s runs=%d extra=%s (%s:%d)" % (d["scenario"], d["runs"], d["extra"], d["file"], d["line"]))
    print("PSS(extra-only, ms=0 not a timing sample):")
    for e in pss_rows:
        print("  %s:%d extra=%s" % (e["file"], e["line"], e["extra"]))


if __name__ == "__main__":
    main()
