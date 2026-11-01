#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Spike2 汇总器：parse.py 产出的 CSV -> 跨 run 汇总（summary.md / summary.json）。

职责（口径见 docs/android-port/spike2-protocol.md §5 与 spike2-data.md）：
  * 只读 CSV；CSV 一律可由 raw 用 parse.py 重算，故本文件不直接读 raw；
  * 按 (app, scenario, phase) 聚合：n / min / p50 / p95 / max，p95 用 nearest-rank；
  * 从 extra 派生 cold/warm 与 annot（renderAnnot），单列分组表：
    跨 app / 跨路径对比只允许同 cold/warm 状态相比；
  * 每个绝对耗时旁给「推算真机值」= ms x factor（默认 1.8，标 [推测]）；
  * 漂移哨兵：每个 app 选同一参照组，比较最早与最晚 run 的中位数，超阈值报警；
  * 来源追溯：每个数字附 raw 文件与行号范围（来自 CSV 的 src_file/src_line）；
  * 样本数 < --min-samples 的组标 LOW_CONFIDENCE。

用法：
    python summarize.py --csv spikes/spike2-results/csv/render-merged.csv \
        --out spikes/spike2-results/summary --name render-merged
    python summarize.py --csv a.csv b.csv --name all [--ref S2:render:0] [--factor 1.8]

纯标准库，Python >= 3.9。
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
import sys
from collections import defaultdict
from datetime import datetime, timezone, timedelta
from pathlib import Path

DEFAULT_FACTOR = 1.8
DEFAULT_MIN_SAMPLES = 5
DRIFT_WARN_RATIO = 0.20

REF_PHASE_PRIORITY = ("render", "first_screen", "open", "frame_stat", "tile")
REF_SCENARIO_PRIORITY = ("S2", "T1", "S1", "T6")

TEMP_KEYS = ("cold", "temp", "coldstart")
ANNOT_KEYS = ("annot", "renderannot", "annotation")

# tiles app 的 *_stat 行是「app 侧已经聚合好的块统计」：ms 只是 avg_ms，
# 真正要报的是 extra 里的 n / p50_ms / p95_ms。这类行不能混进逐样本 ms 统计
# （否则一个 n=5 的块被当成 1 个样本），单独进「app 侧聚合行」一节。
AGG_EXTRA_STATS = ("n", "avg_ms", "p50_ms", "p95_ms", "max_ms",
                   "over16_ratio", "jank_ratio", "fps_avg", "tiles", "tile_ms")
AGG_LABEL_SKIP = set(AGG_EXTRA_STATS) | {"ms"}

CST = timezone(timedelta(hours=8))


class UsageError(Exception):
    pass


def _now() -> str:
    return datetime.now(CST).isoformat(timespec="seconds")


def _f(value):
    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    try:
        out = float(text)
    except ValueError:
        return None
    if math.isnan(out) or math.isinf(out):
        return None
    return out


def _round(value, digits=3):
    if value is None:
        return None
    return round(float(value), digits)


def nearest_rank(sorted_values, q: float):
    if not sorted_values:
        return None
    n = len(sorted_values)
    idx = int(math.ceil(q * n)) - 1
    return sorted_values[max(0, min(idx, n - 1))]


def summarise(values):
    values = sorted(v for v in values if v is not None)
    if not values:
        return None
    return {
        "n": len(values),
        "min": _round(values[0]),
        "p50": _round(statistics.median(values)),
        "p95": _round(nearest_rank(values, 0.95)),
        "max": _round(values[-1]),
    }


def split_extra(extra: str) -> dict:
    out = {}
    if not extra:
        return out
    for item in extra.split(","):
        item = item.strip()
        if not item:
            continue
        if ":" in item:
            key, value = item.split(":", 1)
            out[key.strip().lower()] = value.strip()
        else:
            out[item.lower()] = ""
    return out


def derive_temp(extra_map: dict) -> str:
    for key in TEMP_KEYS:
        if key not in extra_map:
            continue
        value = (extra_map[key] or "").strip().lower()
        if value in ("1", "true", "yes", "cold", ""):
            return "cold"
        if value in ("0", "false", "no", "warm"):
            return "warm"
    if "warm" in extra_map:
        return "warm"
    return ""


def derive_annot(extra_map: dict) -> str:
    for key in ANNOT_KEYS:
        if key not in extra_map:
            continue
        value = (extra_map[key] or "").strip().lower()
        if value in ("1", "true", "yes", "on"):
            return "1"
        if value in ("0", "false", "no", "off"):
            return "0"
    return ""


def md_cell(value) -> str:
    text = "" if value is None else str(value)
    return text.replace("|", "\\|")


def md_table(headers, rows) -> str:
    lines = ["| " + " | ".join(md_cell(h) for h in headers) + " |"]
    lines.append("|" + "|".join("---" for _ in headers) + "|")
    for row in rows:
        lines.append("| " + " | ".join(md_cell(c) for c in row) + " |")
    return "\n".join(lines)


def fmt_ms(value) -> str:
    if value is None:
        return "-"
    return "%.3f" % float(value)


def fmt_est(value, factor) -> str:
    if value is None:
        return "-"
    return "%.1f [推测]" % (float(value) * factor)


class Row:
    __slots__ = (
        "run_id", "app", "scenario", "phase", "page", "w", "h", "ms", "extra",
        "ts", "record", "src_file", "src_line", "warmup", "flags",
        "temp", "annot", "extra_map", "order",
    )

    def __init__(self, raw, order):
        self.order = order
        self.run_id = (raw.get("run_id") or "").strip()
        self.app = (raw.get("app") or "").strip()
        self.scenario = (raw.get("scenario") or "").strip()
        self.phase = (raw.get("phase") or "").strip()
        self.page = _f(raw.get("page"))
        self.w = _f(raw.get("w"))
        self.h = _f(raw.get("h"))
        self.ms = _f(raw.get("ms"))
        self.extra = raw.get("extra") or ""
        self.ts = (raw.get("ts") or "").strip()
        self.record = (raw.get("record") or "").strip()
        self.src_file = (raw.get("src_file") or "").strip()
        try:
            self.src_line = int(raw.get("src_line") or 0)
        except ValueError:
            self.src_line = 0
        self.warmup = str(raw.get("warmup") or "").strip().lower() in ("true", "1", "yes")
        self.flags = (raw.get("flags") or "").strip()
        self.extra_map = split_extra(self.extra)
        self.temp = derive_temp(self.extra_map)
        self.annot = derive_annot(self.extra_map)

    @property
    def is_pss(self) -> bool:
        return self.phase == "pss" or "pss_kb" in self.extra_map

    @property
    def is_error(self) -> bool:
        return "error" in self.flags.split(";") or "error:" in self.extra.lower()

    @property
    def is_aggregate(self) -> bool:
        """app 侧聚合块（tile_stat / frame_stat / gesture_stat …）：ms 是块均值，不是样本。"""
        return self.phase.endswith("_stat") or "p50_ms" in self.extra_map

    @property
    def countable(self) -> bool:
        return (
            self.ms is not None
            and not self.is_pss
            and not self.is_aggregate
            and self.record in ("PBPERF", "PBTIME", "PBPDF", "AMSTART")
            and not self.warmup
            and not self.is_error
        )


def load_csvs(paths):
    rows = []
    meta = []
    for path in paths:
        path = Path(path)
        if not path.exists():
            raise UsageError("CSV 不存在：%s" % path)
        with path.open("r", encoding="utf-8", newline="") as fh:
            reader = csv.DictReader(fh)
            if reader.fieldnames is None:
                raise UsageError("CSV 为空：%s" % path)
            count = 0
            for raw in reader:
                rows.append(Row(raw, len(rows)))
                count += 1
        meta.append({"path": str(path), "rows": count})
    return rows, meta


def group_rows(rows, key_fn):
    groups = defaultdict(list)
    for row in rows:
        if row.countable:
            groups[key_fn(row)].append(row)
    return groups


def source_of(rows):
    by_file = defaultdict(list)
    for row in rows:
        if row.src_file:
            # Windows 反斜杠换成正斜杠，方便直接复制去 grep
            by_file[row.src_file.replace("\\", "/")].append(row.src_line)
    parts = []
    for file in sorted(by_file):
        lines = [ln for ln in by_file[file] if ln > 0]
        if lines:
            parts.append("%s:%d-%d" % (file, min(lines), max(lines)))
        else:
            parts.append(file)
    return "; ".join(parts)


def aggregate_label(extra_map: dict) -> str:
    parts = []
    for key, value in extra_map.items():
        if key in AGG_LABEL_SKIP or value == "":
            continue
        parts.append("%s:%s" % (key, value))
    return ",".join(parts) if parts else "-"


def build_aggregates(rows):
    """app 侧聚合行（n 已由 app 汇总）：按 scenario/phase/块标签 汇总 p50/p95。"""
    buckets = defaultdict(list)
    for row in rows:
        if row.is_aggregate and not row.is_error:
            buckets[(row.app, row.scenario, row.phase, aggregate_label(row.extra_map))].append(row)
    out = []
    for (app, scenario, phase, label), items in sorted(buckets.items()):
        def _vals(name):
            return [v for v in (_f(r.extra_map.get(name)) for r in items) if v is not None]
        ns = [int(v) for v in _vals("n")]
        p50s = sorted(_vals("p50_ms"))
        p95s = sorted(_vals("p95_ms"))
        fps = sorted(_vals("fps_avg"))
        dims = sorted({"%dx%d" % (int(r.w), int(r.h)) for r in items
                       if r.w and r.h and r.w > 0 and r.h > 0})
        out.append({
            "app": app, "scenario": scenario, "phase": phase, "block": label,
            "blocks": len(items),
            "samples": sum(ns) if ns else None,
            "n_per_block": (ns[0] if ns and len(set(ns)) == 1 else None),
            "p50_ms_med": _round(statistics.median(p50s)) if p50s else None,
            "p95_ms_max": _round(p95s[-1]) if p95s else None,
            "fps_avg_med": _round(statistics.median(fps)) if fps else None,
            "dims": ",".join(dims) or "-",
            "src": source_of(items),
        })
    return out


def build_first_screen(rows):
    """T1 首屏两个口径：first_screen（不含 open）vs extra.read_path_ms（含 open）。"""
    out = []
    for row in rows:
        if row.is_error or row.scenario != "T1" or row.phase != "first_screen":
            continue
        out.append({
            "run_id": row.run_id,
            "app": row.app,
            "first_screen_ms": _round(row.ms),
            "open_ms": _round(_f(row.extra_map.get("open_ms"))),
            "read_path_ms": _round(_f(row.extra_map.get("read_path_ms"))),
            "from_create_ms": _round(_f(row.extra_map.get("from_create"))),
            "tiles": row.extra_map.get("tiles", ""),
            "src": "%s:%d" % (row.src_file.replace("\\", "/"), row.src_line),
        })
    return out


def build_mem(rows):
    """PSS / 缓存状态行（S7、T5、T6…）：这些行没有 ms 语义，单列一节。"""
    out = []
    for row in rows:
        keys = row.extra_map
        if not (row.is_pss or "hit" in keys or "pss_kb" in keys):
            continue
        out.append({
            "run_id": row.run_id, "app": row.app, "scenario": row.scenario,
            "phase": row.phase,
            "pss_kb": keys.get("pss_kb", ""),
            "native_kb": keys.get("native_kb", ""),
            "java_kb": keys.get("java_kb", ""),
            "tag": keys.get("tag", ""),
            "hit": keys.get("hit", ""), "evict": keys.get("evict", ""),
            "miss": keys.get("miss", ""), "bytes": keys.get("bytes", ""),
            "limit": keys.get("limit", ""), "tiles": keys.get("tiles", ""),
            "cycles": keys.get("cycles", ""),
            "src": "%s:%d" % (row.src_file.replace("\\", "/"), row.src_line),
        })
    return out


def stat_block(rows, factor, min_samples):
    values = [row.ms for row in rows]
    stats = summarise(values)
    if stats is None:
        return None
    stats["est_p50"] = _round((stats["p50"] or 0) * factor, 1)
    stats["src"] = source_of(rows)
    stats["runs"] = sorted({row.run_id for row in rows})
    stats["dims"] = sorted(
        {"%dx%d" % (int(row.w), int(row.h)) for row in rows
         if row.w and row.h and row.w > 0 and row.h > 0}
    )
    stats["low_confidence"] = stats["n"] < min_samples
    return stats


def build_groups(rows, factor, min_samples):
    out = []
    for (app, scenario, phase), items in sorted(
        group_rows(rows, lambda r: (r.app, r.scenario, r.phase)).items()
    ):
        stats = stat_block(items, factor, min_samples)
        if stats is None:
            continue
        stats.update({"app": app, "scenario": scenario, "phase": phase})
        out.append(stats)
    return out


def build_temp_groups(rows, factor, min_samples):
    temp_rows = [row for row in rows if row.temp]
    out = []
    for (app, scenario, phase, temp), items in sorted(
        group_rows(temp_rows, lambda r: (r.app, r.scenario, r.phase, r.temp)).items()
    ):
        stats = stat_block(items, factor, min_samples)
        if stats is None:
            continue
        stats.update({"app": app, "scenario": scenario, "phase": phase, "temp": temp})
        out.append(stats)
    return out


def build_annot_groups(rows, factor, min_samples):
    annot_rows = [row for row in rows if row.annot]
    out = []
    for (app, scenario, phase, annot), items in sorted(
        group_rows(annot_rows, lambda r: (r.app, r.scenario, r.phase, r.annot)).items()
    ):
        stats = stat_block(items, factor, min_samples)
        if stats is None:
            continue
        stats.update({"app": app, "scenario": scenario, "phase": phase, "annot": annot})
        out.append(stats)
    return out


def choose_ref(rows):
    candidates = [row for row in rows if row.countable]
    if not candidates:
        return None
    for scenario in REF_SCENARIO_PRIORITY:
        for phase in REF_PHASE_PRIORITY:
            picked = [row for row in candidates if row.scenario == scenario and row.phase == phase]
            if picked:
                pages = {row.page for row in picked}
                page = 0 if 0 in pages else None
                return (scenario, phase, page)
    first = candidates[0]
    return (first.scenario, first.phase, None)


def build_drift(rows, ref=None):
    if ref is None:
        ref = choose_ref(rows)
    if ref is None:
        return None
    scenario, phase, page = ref
    picked = [
        row for row in rows
        if row.countable and row.scenario == scenario and row.phase == phase
        and (page is None or row.page == page)
    ]
    if not picked:
        return None
    by_run = defaultdict(list)
    for row in picked:
        by_run[row.run_id].append(row)
    runs = []
    for run_id, items in by_run.items():
        values = [row.ms for row in items if row.ms is not None]
        if not values:
            continue
        runs.append({
            "run_id": run_id,
            "avg": _round(sum(values) / len(values)),
            "med": _round(statistics.median(values)),
            "n": len(values),
            "ts": min((row.ts for row in items if row.ts), default=""),
            "app": items[0].app,
            "src": source_of(items),
        })
    runs.sort(key=lambda item: (item["ts"] or "", item["run_id"]))
    first = runs[0]
    last = runs[-1]
    ratio = None
    flag = ""
    if len(runs) >= 2 and first["med"] and last["med"]:
        ratio = _round((last["med"] - first["med"]) / first["med"], 3)
        if abs(ratio) >= DRIFT_WARN_RATIO:
            flag = "DRIFT_WARN"
    return {
        "scenario": scenario, "phase": phase, "page": page,
        "runs": runs, "first": first, "last": last,
        "ratio": ratio, "flag": flag, "src": source_of(picked),
    }


def env_rows(rows):
    env = []
    keys = ("apk", "apk_md5", "apk_bytes", "scenario", "api", "model", "abi", "build", "ts", "host")
    for row in rows:
        if row.record != "RUNENV":
            continue
        item = {"app": row.app, "run_id": row.run_id, "src": "%s:%d" % (row.src_file, row.src_line)}
        for key in keys:
            item[key] = row.extra_map.get(key, "")
        env.append(item)
    return env


def render_md(rows, groups, temp_groups, annot_groups, aggregates, first_screen, mem, drift,
              factor, min_samples, name, meta, env):
    lines = []
    add = lines.append
    add("# Spike2 汇总 · %s" % name)
    add("")
    add("> 由 scripts/android/spike2/summarize.py 从 parse.py 的 CSV 汇总；"
        "所有数字可由 raw logcat 重算（CSV 的 src_file/src_line 指回 raw 行号）。")
    add("> 生成时间：%s　|　真机推算系数 x%.2f（[推测]，PDFium 纯 CPU 光栅，真机/模拟器约 1.4-2.5）"
        % (_now(), factor))
    add("> 口径：median=statistics.median；p95=nearest-rank（idx=ceil(q*n)-1）；单位 ms。")
    add("")

    add("## 1. 输入与环境")
    add("")
    add(md_table(
        ["CSV", "CSV 行数", "参与统计行", "run 数", "apps"],
        [[m["path"], m["rows"], len(rows), len({row.run_id for row in rows}),
          ", ".join(sorted({row.app for row in rows if row.app}))] for m in meta],
    ))
    add("")
    if env:
        add("**RUNENV 环境头**")
        add("")
        add(md_table(
            ["run_id", "app", "apk", "md5", "api", "model", "abi", "build", "ts", "来源"],
            [[e.get("run_id", ""), e.get("app", ""), e.get("apk", ""), e.get("apk_md5", ""),
              e.get("api", ""), e.get("model", ""), e.get("abi", ""), e.get("build", ""),
              e.get("ts", ""), e.get("src", "")] for e in env],
        ))
        add("")
    else:
        add("（输入中没有 RUNENV 行；环境头缺失 -> 该批数据只能做相对比较。）")
        add("")

    add("## 2. ms 统计（跨 run，冷热混合，仅总览）")
    add("")
    if groups:
        add(md_table(
            ["app", "scenario", "phase", "dims", "n", "min", "p50", "p95", "max",
             "真机推算 p50", "run 数", "来源"],
            [[g["app"], g["scenario"], g["phase"], ",".join(g["dims"]) or "-", g["n"],
              fmt_ms(g["min"]), fmt_ms(g["p50"]), fmt_ms(g["p95"]), fmt_ms(g["max"]),
              fmt_est(g["p50"], factor), len(g["runs"]), g["src"]] for g in groups],
        ))
    else:
        add("（没有可统计的样本。）")
    add("")
    add("> 第 2 节把 cold/warm 混在一起，只用于总览；跨 app / 跨路径对比必须用第 3 节的同冷热分组。")
    add("> 注意：tiles app 的 *_stat 行（tile_stat/frame_stat/gesture_stat…）是 app 侧已聚合的块统计，"
        "不按单样本计入本节，单独列在第 2b 节。")
    add("")

    add("## 2b. app 侧聚合行（*_stat：n/p50/p95 由 app 自己汇总）")
    add("")
    add("> 这些行每个是一次「块」测量（app 内部 n 次取 avg/p50/p95）；本节报块数、总样本数与各块 p50 的中位数，"
        "不参与第 2/3 节的逐样本统计。")
    add("")
    if aggregates:
        add(md_table(
            ["app", "scenario", "phase", "块标签", "块数", "总样本", "每块 n", "dims",
             "块 p50 中位", "块 p95 最大", "fps 中位", "来源"],
            [[a["app"], a["scenario"], a["phase"], a["block"], a["blocks"],
              a["samples"] if a["samples"] is not None else "-",
              a["n_per_block"] if a["n_per_block"] is not None else "-",
              a["dims"],
              fmt_ms(a["p50_ms_med"]), fmt_ms(a["p95_ms_max"]),
              "-" if a["fps_avg_med"] is None else "%.1f" % a["fps_avg_med"],
              a["src"]] for a in aggregates],
        ))
    else:
        add("（没有 *_stat 聚合行。）")
    add("")

    add("## 2c. T1 首屏两个口径（first_screen 不含 open；read_path_ms 含 open）")
    add("")
    add("> L3「首屏 ≤200ms」按协议用 `first_screen`（setPage → 9 个 tile 渲染并呈现一帧），"
        "**不含 open()**；用户体感对应 `read_path_ms`（open + 首屏）。两列并列，禁止混用。")
    add("")
    if first_screen:
        add(md_table(
            ["run_id", "app", "first_screen ms", "open_ms", "read_path_ms", "from_create_ms", "tiles", "来源"],
            [[r["run_id"], r["app"], fmt_ms(r["first_screen_ms"]), fmt_ms(r["open_ms"]),
              fmt_ms(r["read_path_ms"]), fmt_ms(r["from_create_ms"]), r["tiles"], r["src"]]
             for r in first_screen],
        ))
    else:
        add("（没有 T1 first_screen 行。）")
    add("")

    add("## 2d. 内存与缓存（PSS / cache 状态行）")
    add("")
    add("> 这些行没有 ms 语义，不进耗时统计；T5 的 PSS 必须同一进程生命周期内取前后值得出增量。")
    add("")
    if mem:
        add(md_table(
            ["run_id", "app", "scenario", "phase", "pss_kb", "native_kb", "java_kb", "tag",
             "hit", "evict", "miss", "bytes", "limit", "tiles", "cycles", "来源"],
            [[m["run_id"], m["app"], m["scenario"], m["phase"], m["pss_kb"], m["native_kb"],
              m["java_kb"], m["tag"], m["hit"], m["evict"], m["miss"], m["bytes"],
              m["limit"], m["tiles"], m["cycles"], m["src"]] for m in mem],
        ))
    else:
        add("（没有 PSS/cache 行。）")
    add("")

    add("## 3. 冷/热分组（跨 app 对比只允许同状态）")
    add("")
    add("> cold = 同页同尺寸第一次渲染；warm = 之后。派生自 extra 的 cold:1 / cold:0（或 temp:）。")
    add("")
    if temp_groups:
        add(md_table(
            ["app", "scenario", "phase", "状态", "n", "min", "p50", "p95", "max", "真机推算 p50", "来源"],
            [[g["app"], g["scenario"], g["phase"], g["temp"], g["n"],
              fmt_ms(g["min"]), fmt_ms(g["p50"]), fmt_ms(g["p95"]), fmt_ms(g["max"]),
              fmt_est(g["p50"], factor), g["src"]] for g in temp_groups],
        ))
    else:
        add("（样本里没有 cold/warm 标注——检查被测 app 的 extra 是否带 cold:1 / cold:0。）")
    add("")

    add("## 4. renderAnnot 分组（A/B）")
    add("")
    add("> 派生自 extra 的 annot:1 / annot:0（或 renderAnnot:）。1=走注解/文本层路径，0=不走。")
    add("")
    if annot_groups:
        add(md_table(
            ["app", "scenario", "phase", "annot", "n", "p50", "p95", "max", "来源"],
            [[g["app"], g["scenario"], g["phase"], g["annot"], g["n"],
              fmt_ms(g["p50"]), fmt_ms(g["p95"]), fmt_ms(g["max"]), g["src"]]
             for g in annot_groups],
        ))
    else:
        add("（样本里没有 annot 标注。）")
    add("")

    add("## 5. 漂移哨兵")
    add("")
    if drift:
        add("> 参照组：scenario=%s phase=%s%s。比较最早与最晚 run 的中位数；"
            "差 > %.0f%% 标 DRIFT_WARN。" % (
                drift["scenario"], drift["phase"],
                "" if drift["page"] is None else " page=%d" % drift["page"],
                DRIFT_WARN_RATIO * 100))
        add("")
        add(md_table(
            ["#", "run_id", "app", "n", "median", "avg", "ts", "来源"],
            [[i + 1, r["run_id"], r["app"], r["n"], fmt_ms(r["med"]), fmt_ms(r["avg"]), r["ts"], r["src"]]
             for i, r in enumerate(drift["runs"])],
        ))
        add("")
        if drift["ratio"] is None:
            add("- 只有 1 个 run，无法判断漂移（至少 2 个 run 才有哨兵意义）。")
        else:
            add("- 首 run median=%s ms，末 run median=%s ms，变化 %+.1f%% %s"
                % (fmt_ms(drift["first"]["med"]), fmt_ms(drift["last"]["med"]),
                   drift["ratio"] * 100, drift["flag"]))
        if drift["flag"]:
            add("- **DRIFT_WARN**：本批首末漂移超阈值，跨批绝对值不可直接比，只能同批内相对比较。")
        add("")
    else:
        add("（没有可用于哨兵的参照组。）")
        add("")

    low = [g for g in groups if g["n"] < min_samples]
    add("## 6. LOW_CONFIDENCE")
    add("")
    if low:
        add("> 组样本数 < %d。只作方向参考，不能写进结论。" % min_samples)
        add("")
        add(md_table(
            ["app", "scenario", "phase", "n", "p50", "来源"],
            [[g["app"], g["scenario"], g["phase"], g["n"], fmt_ms(g["p50"]), g["src"]] for g in low],
        ))
    else:
        add("（所有组 n >= %d。）" % min_samples)
    add("")

    render_rows = [row for row in rows if row.countable
                   and row.phase in ("render", "rerender", "zoom_rerender") and not row.temp]
    add("## 7. 缺 cold/warm 标注的 render 记录")
    add("")
    if render_rows:
        add("> 协议要求每条 render 记录带 cold/warm；以下记录缺失，不能与带标注数据混用。")
        add("")
        grouped = sorted(group_rows(
            render_rows, lambda r: (r.run_id, r.app, r.scenario, r.phase)).items())
        add(md_table(
            ["run_id", "app", "scenario", "phase", "n", "来源"],
            [[run_id, app, scenario, phase, len(items), source_of(items)]
             for (run_id, app, scenario, phase), items in grouped],
        ))
    else:
        add("（无。）")
    add("")

    add("## 8. 口径与限制")
    add("")
    add("- 环境：Android emulator（x86_64/API35/1080x2400/dpi420/swiftshader/WHPX）。")
    add("- 绝对值只用于同批次对比；跨批次只报中位数与相对差（模拟器漂移 30-50%）。")
    add("- [推测] 列 = 模拟器 p50 x %.2f，仅用于给真机量级预期，不是实测。" % factor)
    add("- 失败/异常不测不报：extra=error: 行不参与统计。")
    add("")
    return "\n".join(lines) + "\n"


def build_json(rows, groups, temp_groups, annot_groups, aggregates, first_screen, mem, drift,
               factor, min_samples, name, meta, env):
    return {
        "name": name,
        "generated_at": _now(),
        "factor": factor,
        "min_samples": min_samples,
        "drift_warn_ratio": DRIFT_WARN_RATIO,
        "inputs": meta,
        "env": env,
        "groups": groups,
        "by_temp": temp_groups,
        "by_annot": annot_groups,
        "aggregates": aggregates,
        "first_screen": first_screen,
        "mem": mem,
        "drift": drift,
        "row_counts": {
            "total": len(rows),
            "countable": sum(1 for row in rows if row.countable),
            "runs": len({row.run_id for row in rows}),
        },
    }


def build_parser():
    parser = argparse.ArgumentParser(
        description="Spike2 汇总：parse.py 的 CSV -> summary.md / summary.json",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--csv", nargs="+", required=True, help="一个或多个 parse.py 产出的 CSV")
    parser.add_argument("--out", default=None, help="输出目录（默认 spikes/spike2-results/summary）")
    parser.add_argument("--name", default=None, help="输出文件名（默认首 CSV 的 stem）")
    parser.add_argument("--factor", type=float, default=DEFAULT_FACTOR,
                        help="真机推算系数（默认 1.8，标 [推测]）")
    parser.add_argument("--ref", default=None,
                        help="漂移参照组 scenario:phase[:page]（默认自动挑 S2:render）")
    parser.add_argument("--min-samples", type=int, default=DEFAULT_MIN_SAMPLES)
    return parser


def repo_root() -> Path:
    return Path(__file__).resolve().parents[3]


def main(argv=None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        rows, meta = load_csvs(args.csv)
    except UsageError as exc:
        print("[summarize] %s" % exc, file=sys.stderr)
        return 2

    min_samples = args.min_samples if args.min_samples > 0 else DEFAULT_MIN_SAMPLES
    factor = args.factor
    groups = build_groups(rows, factor, min_samples)
    temp_groups = build_temp_groups(rows, factor, min_samples)
    annot_groups = build_annot_groups(rows, factor, min_samples)
    aggregates = build_aggregates(rows)
    first_screen = build_first_screen(rows)
    mem = build_mem(rows)

    ref = None
    if args.ref:
        parts = args.ref.split(":")
        if len(parts) < 2:
            print("[summarize] --ref 形如 scenario:phase[:page]", file=sys.stderr)
            return 2
        page = int(parts[2]) if len(parts) > 2 and parts[2] not in ("", "-") else None
        ref = (parts[0], parts[1], page)
    drift = build_drift(rows, ref)
    env = env_rows(rows)

    name = args.name or Path(args.csv[0]).stem
    out_dir = Path(args.out) if args.out else repo_root() / "spikes" / "spike2-results" / "summary"
    out_dir.mkdir(parents=True, exist_ok=True)

    md = render_md(rows, groups, temp_groups, annot_groups, aggregates, first_screen, mem,
                   drift, factor, min_samples, name, meta, env)
    data = build_json(rows, groups, temp_groups, annot_groups, aggregates, first_screen, mem,
                      drift, factor, min_samples, name, meta, env)

    md_path = out_dir / ("%s.md" % name)
    json_path = out_dir / ("%s.summary.json" % name)
    md_path.write_text(md, encoding="utf-8")
    json_path.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")

    print("[summarize] 输入 %d 个 CSV / %d 行；参与统计 %d 行；分组 %d（cold/warm %d，annot %d，聚合块 %d）"
          % (len(meta), data["row_counts"]["total"], data["row_counts"]["countable"],
             len(groups), len(temp_groups), len(annot_groups), len(aggregates)))
    if drift:
        print("[summarize] 漂移哨兵 %s/%s: %s"
              % (drift["scenario"], drift["phase"],
                 "单 run 无法判断" if drift["ratio"] is None
                 else "%+.1f%% %s" % (drift["ratio"] * 100, drift["flag"])))
    print("[summarize] 输出：%s / %s" % (md_path, json_path))
    return 0


if __name__ == "__main__":
    sys.exit(main())
