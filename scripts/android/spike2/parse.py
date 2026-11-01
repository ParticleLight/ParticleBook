#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Spike2 基准日志解析器：logcat -> CSV / stats.json / markdown。

输入（三种 logcat 前缀都接受：threadtime / brief / raw）：
    PBPERF|scenario=..|phase=..|page=..|w=..|h=..|ms=..|extra=..
    PBDONE|scenario=..|runs=..|extra=..
    PBTIME|app=..|phase=..|ms=..
    RUNENV|app=..|run_id=..|apk_md5=..|scenario=..|ts=..      （脚本追加的 metadata）
    AMSTART|rep=..|TotalTime=..|WaitTime=..|app=..           （脚本追加 / am start -W）
    PBPdf  tag: pdf_open pages=.. docBytes=.. took=..ms
               pdf_page page=.. WxH render=..ms encode=..ms bytes=..

输出三件套（同目录同名）：
    <out>/<name>.csv         每行一条记录；pdf_page 一行拆 render / encode 两行
    <out>/<name>.stats.json  分组统计（跨 run + 按 run + 内存）
    <out>/<name>.md          markdown 报告，所有数字均来自统计结果

契约见 docs/android-port/spike2-protocol.md。

纯标准库，Python >= 3.9。
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import re
import statistics
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

# --------------------------------------------------------------------------
# 常量
# --------------------------------------------------------------------------

CSV_HEADER = [
    "run_id",
    "app",
    "scenario",
    "phase",
    "page",
    "w",
    "h",
    "ms",
    "extra",
    "ts",
    "record",
    "src_file",
    "src_line",
    "warmup",
    "confidence",
    "flags",
]

RECORD_PBPERF = "PBPERF"
RECORD_PBDONE = "PBDONE"
RECORD_PBTIME = "PBTIME"
RECORD_PBPDF = "PBPDF"
RECORD_AMSTART = "AMSTART"
RECORD_RUNENV = "RUNENV"

RECORDS = (
    RECORD_PBPERF,
    RECORD_PBDONE,
    RECORD_PBTIME,
    RECORD_PBPDF,
    RECORD_AMSTART,
    RECORD_RUNENV,
)

# 参与 ms 统计的记录类型（PBDONE / RUNENV 不参与）
STATS_RECORDS = frozenset({RECORD_PBPERF, RECORD_PBPDF, RECORD_AMSTART, RECORD_PBTIME})

_MARKERS = (
    ("PBPERF|", RECORD_PBPERF),
    ("PBDONE|", RECORD_PBDONE),
    ("PBTIME|", RECORD_PBTIME),
    ("RUNENV|", RECORD_RUNENV),
    ("AMSTART|", RECORD_AMSTART),
)

# 文件名前缀 -> app（优先级最低，仅在没有 --app / RUNENV app 时使用）
_APP_PREFIX = (
    ("render-", "spike2"),
    ("tiles-", "spike2t"),
    ("p0-", "particlebook"),
)

_THREADTIME_RE = re.compile(
    r"^(?P<ts>\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+"
    r"(?P<pid>\d+)\s+(?P<tid>\d+)\s+[VDIWEFAS]\s+"
    r"(?P<tag>[^\s:]+)\s*:\s?(?P<msg>.*)$"
)

_BRIEF_RE = re.compile(
    r"^(?P<level>[VDIWEFAS])/(?P<tag>[^(]+?)\(\s*(?P<pid>\d+)\s*\)\s*:\s?(?P<msg>.*)$"
)

_DIMS_RE = re.compile(r"^(\d+)x(\d+)$")

_MEM_KEYS = ("pss_kb", "native_kb", "java_kb")


def repo_root() -> Path:
    """parse.py 位于 <repo>/scripts/android/spike2/ -> 上溯 4 层到仓库根。"""
    return Path(__file__).resolve().parents[3]


DEFAULT_OUT_DIR = repo_root() / "spikes" / "spike2-results" / "csv"


# --------------------------------------------------------------------------
# 基础工具
# --------------------------------------------------------------------------


class UsageError(Exception):
    """命令行/路径层面的错误（退出码 2，不产生输出文件）。"""


class ParseError(Exception):
    """单行解析失败（跳过该行，计入 stderr 统计）。"""


def _need_int(text: object, name: str) -> int:
    try:
        return int(str(text).strip())
    except (TypeError, ValueError):
        raise ParseError("%s=%r 不是整数" % (name, text)) from None


def _need_float(text: object, name: str) -> float:
    try:
        return float(str(text).strip())
    except (TypeError, ValueError):
        raise ParseError("%s=%r 不是数字" % (name, text)) from None


def _as_float(text):
    if text is None:
        return None
    text = str(text).strip()
    if not text:
        return None
    try:
        value = float(text)
    except ValueError:
        return None
    if math.isnan(value) or math.isinf(value):
        return None
    return value


def _round3(value):
    if value is None:
        return None
    return round(float(value), 3)


def _strip_ms_suffix(text):
    """'154.0ms' -> '154.0'；None/空 -> None。"""
    if text is None:
        return None
    value = str(text).strip()
    if value.lower().endswith("ms"):
        value = value[:-2].strip()
    return value or None


def _flag_sort_key(flag: str):
    if flag == "error":
        return (0, flag)
    if flag.startswith("n<"):
        return (1, flag)
    if flag == "no_pbdone":
        return (2, flag)
    return (3, flag)


# --------------------------------------------------------------------------
# 行识别
# --------------------------------------------------------------------------


def split_prefix(line: str):
    """返回 (ts, tag, msg)：兼容 threadtime / brief / raw 三种格式。"""
    text = line.rstrip("\r\n")
    if not text.strip():
        return "", "", ""

    m = _THREADTIME_RE.match(text)
    if m:
        return m.group("ts"), m.group("tag"), m.group("msg")

    m = _BRIEF_RE.match(text)
    if m:
        return "", m.group("tag"), m.group("msg")

    return "", "", text.strip()


def classify(msg: str, tag: str):
    """识别记录类型；不是本契约的行返回 (None, None)。"""
    for marker, record in _MARKERS:
        idx = msg.find(marker)
        if idx >= 0:
            return record, msg[idx:]

    body = msg.strip()
    if body.startswith("pdf_open") or body.startswith("pdf_page"):
        return RECORD_PBPDF, body

    return None, None


def parse_fields(payload: str, record: str) -> dict:
    """把 'PBPERF|k=v|k=v' 拆成有序 dict；结构错误抛 ParseError。"""
    parts = payload.split("|")
    if parts[0].strip() != record:
        raise ParseError("记录头 %r 与 %s 不符" % (parts[0], record))

    fields = {}
    for token in parts[1:]:
        if not token:
            continue
        if "=" not in token:
            raise ParseError("字段缺少 '='：%r" % token)
        key, value = token.split("=", 1)
        key = key.strip()
        if not key:
            raise ParseError("空字段名：%r" % token)
        if key not in fields:
            fields[key] = value

    if not fields:
        raise ParseError("%s 没有任何 key=value 字段" % record)
    return fields


_COLD_KEYS = ("cold", "temp", "coldstart")


def has_cold_annotation(extra: str) -> bool:
    """extra 里显式标了 cold:1 / temp:cold 的样本不是 warmup。

    冷样本（同页同尺寸第一次渲染）本身就是 S2/S6/S10 的被测对象，
    所以 --drop-mode first-per-phase 只丢弃「未标冷」的每组第一条。
    """
    text = (extra or "").lower()
    if not text:
        return False
    for item in text.split(","):
        if ":" not in item:
            continue
        key, value = item.split(":", 1)
        if key.strip() not in _COLD_KEYS:
            continue
        if value.strip() in ("1", "true", "cold", ""):
            return True
    return False


def parse_mem_extra(extra: str) -> dict:
    """从 extra 里取 pss_kb / native_kb / java_kb（没有就缺省）。"""
    found = {}
    if not extra:
        return found
    for item in extra.split(","):
        if ":" not in item:
            continue
        key, value = item.split(":", 1)
        key = key.strip().lower()
        if key not in _MEM_KEYS:
            continue
        value = value.strip()
        if not value:
            continue
        try:
            found[key] = float(value)
        except ValueError:
            continue
    return found


# --------------------------------------------------------------------------
# 记录 -> 行种子
# --------------------------------------------------------------------------


@dataclass
class Seed:
    """一条待落地的 CSV 行（尚未绑定 run_id / app / 源文件）。"""

    record: str
    scenario: str = ""
    phase: str = ""
    page: str = "-1"
    w: str = "-1"
    h: str = "-1"
    ms: str = ""
    extra: str = ""
    app: str = ""
    runs: str = ""
    meta: dict = field(default_factory=dict)


def _int_field(fields: dict, key: str, default: int = -1) -> str:
    if key not in fields:
        return str(default)
    return str(_need_int(fields[key], key))


def _float_field(fields: dict, key: str) -> str:
    if key not in fields:
        return ""
    raw = str(fields[key]).strip()
    if not raw:
        return ""
    _need_float(raw, key)
    return raw


def _seed_pbperf(fields: dict) -> list:
    page = _int_field(fields, "page")
    w = _int_field(fields, "w")
    h = _int_field(fields, "h")
    ms = _float_field(fields, "ms")
    return [
        Seed(
            record=RECORD_PBPERF,
            scenario=fields.get("scenario", ""),
            phase=fields.get("phase", ""),
            page=page,
            w=w,
            h=h,
            ms=ms,
            extra=fields.get("extra", ""),
        )
    ]


def _seed_pbdone(fields: dict) -> list:
    runs = str(fields.get("runs", "")).strip()
    if runs:
        runs = str(_need_int(runs, "runs"))
    extra = "runs:%s" % runs if runs else fields.get("extra", "")
    return [
        Seed(
            record=RECORD_PBDONE,
            scenario=fields.get("scenario", ""),
            phase="done",
            ms="",
            extra=extra,
            runs=runs,
            meta=dict(fields),
        )
    ]


def _seed_pbtime(fields: dict) -> list:
    return [
        Seed(
            record=RECORD_PBTIME,
            scenario="PBTIME",
            phase=fields.get("phase", ""),
            ms=_float_field(fields, "ms"),
            extra=fields.get("extra", ""),
            app=fields.get("app", ""),
            meta=dict(fields),
        )
    ]


def _seed_amstart(fields: dict) -> list:
    total = _float_field(fields, "TotalTime")
    wait = str(fields.get("WaitTime", "")).strip()
    if wait:
        _need_int(wait, "WaitTime")
    extra = "WaitTime:%s" % wait if wait else ""
    return [
        Seed(
            record=RECORD_AMSTART,
            scenario="cold_start",
            phase="am_start",
            ms=total,
            extra=extra,
            app=fields.get("app", ""),
            meta=dict(fields),
        )
    ]


def _seed_runenv(fields: dict) -> list:
    rest = [item for item in fields.items() if item[0] not in ("app", "run_id")]
    extra = ",".join("%s:%s" % (k, v) for k, v in rest)
    return [
        Seed(
            record=RECORD_RUNENV,
            scenario="runenv",
            phase="env",
            ms="",
            extra=extra,
            app=fields.get("app", ""),
            meta=dict(fields),
        )
    ]


_SEED_BUILDERS = {
    RECORD_PBPERF: _seed_pbperf,
    RECORD_PBDONE: _seed_pbdone,
    RECORD_PBTIME: _seed_pbtime,
    RECORD_AMSTART: _seed_amstart,
    RECORD_RUNENV: _seed_runenv,
}


def _seed_pbpdf(body: str) -> list:
    """PBPdf 的 pdf_open / pdf_page（空格分词、无 | 分隔）。"""
    parts = body.split()
    kind = parts[0]

    kv = {}
    dims = None
    for token in parts[1:]:
        if "=" in token:
            key, value = token.split("=", 1)
            if not key:
                raise ParseError("PBPDF 空字段名：%r" % token)
            kv[key] = value
            continue
        m = _DIMS_RE.match(token)
        if m:
            dims = (int(m.group(1)), int(m.group(2)))
            continue
        raise ParseError("PBPDF 无法识别的字段：%r" % token)

    if kind == "pdf_open":
        took = _strip_ms_suffix(kv.get("took"))
        if took is not None:
            _need_float(took, "took")
        extra_bits = []
        for key in ("pages", "docBytes"):
            if key in kv:
                _need_int(kv[key], key)
                extra_bits.append("%s:%s" % (key, kv[key]))
        return [
            Seed(
                record=RECORD_PBPDF,
                scenario="pdf_open",
                phase="open",
                ms=took or "",
                extra=",".join(extra_bits),
            )
        ]

    if kind == "pdf_page":
        if "page" not in kv:
            raise ParseError("pdf_page 缺 page=")
        page = _need_int(kv["page"], "page")
        if dims is None:
            raise ParseError("pdf_page 缺 WxH 渲染尺寸")
        extra = ""
        if "bytes" in kv:
            _need_int(kv["bytes"], "bytes")
            extra = "bytes:%s" % kv["bytes"]

        seeds = []
        for phase, key in (("render", "render"), ("encode", "encode")):
            value = _strip_ms_suffix(kv.get(key))
            if value is None:
                continue
            _need_float(value, key)
            seeds.append(
                Seed(
                    record=RECORD_PBPDF,
                    scenario="pdf_page",
                    phase=phase,
                    page=str(page),
                    w=str(dims[0]),
                    h=str(dims[1]),
                    ms=value,
                    extra=extra,
                )
            )
        if not seeds:
            raise ParseError("pdf_page 缺 render= / encode=")
        return seeds

    raise ParseError("未知的 PBPDF 记录：%r" % kind)


def seeds_for(record: str, payload: str) -> list:
    if record == RECORD_PBPDF:
        return _seed_pbpdf(payload)
    fields = parse_fields(payload, record)
    builder = _SEED_BUILDERS[record]
    return builder(fields)


# --------------------------------------------------------------------------
# 数据结构
# --------------------------------------------------------------------------


@dataclass
class ParseFailure:
    file: str
    line: int
    reason: str
    text: str

    def as_dict(self) -> dict:
        return {"file": self.file, "line": self.line, "reason": self.reason, "text": self.text}


@dataclass
class SourceLine:
    line_no: int
    ts: str
    record: str
    seeds: list


@dataclass
class FileParse:
    path: Path
    src: str
    lines: int
    entries: list = field(default_factory=list)
    failures: list = field(default_factory=list)
    runenv: list = field(default_factory=list)
    run_id: str = ""
    app: str = ""

    @property
    def record_lines(self) -> int:
        return len(self.entries)


@dataclass
class Row:
    run_id: str
    app: str
    scenario: str
    phase: str
    page: str
    w: str
    h: str
    ms: str
    extra: str
    ts: str
    record: str
    src_file: str
    src_line: int
    order: int = 0
    warmup: bool = False
    warmup_dropped: bool = False
    confidence: str = "ok"
    flags: set = field(default_factory=set)
    runs: str = ""

    # ---- 派生属性 -----------------------------------------------------
    @property
    def ms_value(self):
        return _as_float(self.ms)

    @property
    def is_error(self) -> bool:
        return "error" in self.flags

    @property
    def is_pss(self) -> bool:
        if self.record != RECORD_PBPERF:
            return False
        if self.phase == "pss":
            return True
        return "pss_kb:" in (self.extra or "").lower()

    @property
    def countable(self) -> bool:
        """参与统计：记录类型对、ms 非空、非 warmup、非 error。"""
        return (
            self.record in STATS_RECORDS
            and self.ms_value is not None
            and not self.warmup
            and not self.is_error
        )

    @property
    def flags_text(self) -> str:
        return ";".join(sorted(self.flags, key=_flag_sort_key))

    def csv_row(self) -> list:
        return [
            self.run_id,
            self.app,
            self.scenario,
            self.phase,
            self.page,
            self.w,
            self.h,
            self.ms,
            self.extra,
            self.ts,
            self.record,
            self.src_file,
            str(self.src_line),
            "true" if self.warmup else "false",
            self.confidence,
            self.flags_text,
        ]

    def as_dict(self) -> dict:
        return dict(zip(CSV_HEADER, self.csv_row()))


@dataclass
class Analysis:
    valid: list = field(default_factory=list)
    dropped: int = 0
    groups: list = field(default_factory=list)
    mem: list = field(default_factory=list)
    low_confidence_groups: list = field(default_factory=list)
    missing_pbdone: list = field(default_factory=list)


# --------------------------------------------------------------------------
# 解析文件
# --------------------------------------------------------------------------


def read_lines(path: Path) -> list:
    data = path.read_bytes()
    text = data.decode("utf-8", errors="replace")
    if text.startswith("\ufeff"):
        text = text[1:]
    return text.splitlines()


def parse_file(path: Path) -> FileParse:
    src = str(path)
    lines = read_lines(path)
    fp = FileParse(path=path, src=src, lines=len(lines))

    for line_no, raw in enumerate(lines, start=1):
        ts, tag, msg = split_prefix(raw)
        if not msg:
            continue
        record, payload = classify(msg, tag)
        if record is None:
            continue  # 不属于本契约的日志行（其他 tag、栈帧等）直接忽略
        try:
            seeds = seeds_for(record, payload)
        except ParseError as exc:
            fp.failures.append(
                ParseFailure(file=src, line=line_no, reason=str(exc), text=raw.strip()[:200])
            )
            continue
        fp.entries.append(SourceLine(line_no=line_no, ts=ts, record=record, seeds=seeds))
        if record == RECORD_RUNENV:
            fp.runenv.append(dict(seeds[0].meta))

    return fp


def _app_from_name(path: Path) -> str:
    name = path.name.lower()
    for prefix, app in _APP_PREFIX:
        if name.startswith(prefix):
            return app
    return ""


def expand_paths(raw_paths) -> list:
    files = []
    for raw in raw_paths:
        path = Path(raw)
        if not path.exists():
            raise UsageError("输入路径不存在：%s" % raw)
        if path.is_dir():
            found = [
                item
                for item in path.rglob("*")
                if item.is_file() and item.suffix.lower() in (".log", ".txt")
            ]
            found.sort(key=lambda item: str(item))
            if not found:
                raise UsageError("目录下没有 *.log / *.txt：%s" % raw)
            files.extend(found)
        else:
            files.append(path)
    return files


def build_rows(parsed_files: list, forced_app) -> list:
    rows = []
    order = 0

    for fp in parsed_files:
        runenv_run_id = next(
            (meta.get("run_id") for meta in fp.runenv if meta.get("run_id")), None
        )
        runenv_app = next((meta.get("app") for meta in fp.runenv if meta.get("app")), None)

        fp.run_id = runenv_run_id or fp.path.stem
        fp.app = forced_app or runenv_app or _app_from_name(fp.path) or "unknown"

        for entry in fp.entries:
            for seed in entry.seeds:
                order += 1
                app = forced_app or seed.app or fp.app
                row = Row(
                    run_id=fp.run_id,
                    app=app,
                    scenario=seed.scenario,
                    phase=seed.phase,
                    page=seed.page,
                    w=seed.w,
                    h=seed.h,
                    ms=seed.ms,
                    extra=seed.extra,
                    ts=entry.ts,
                    record=seed.record,
                    src_file=fp.src,
                    src_line=entry.line_no,
                    order=order,
                    runs=seed.runs,
                )
                lowered = (seed.extra or "").lower()
                if "warmup" in lowered or "warmup" in (seed.phase or "").lower():
                    row.warmup = True
                if "error:" in lowered:
                    row.flags.add("error")
                    row.confidence = "LOW_CONFIDENCE"
                rows.append(row)

    return rows


# --------------------------------------------------------------------------
# 统计
# --------------------------------------------------------------------------


def _nearest_rank(sorted_values: list, q: float):
    if not sorted_values:
        return None
    idx = math.ceil(q * len(sorted_values)) - 1
    idx = max(0, min(idx, len(sorted_values) - 1))
    return sorted_values[idx]


def summarise(values: list) -> dict:
    ordered = sorted(values)
    return {
        "n": len(ordered),
        "median": _round3(statistics.median(ordered)),
        "p50": _round3(_nearest_rank(ordered, 0.50)),
        "p95": _round3(_nearest_rank(ordered, 0.95)),
        "max": _round3(ordered[-1]),
        "min": _round3(ordered[0]),
    }


def sources_of(rows: list) -> list:
    per_file = defaultdict(list)
    for row in rows:
        per_file[row.src_file].append(row.src_line)
    result = []
    for src in sorted(per_file):
        line_nos = per_file[src]
        result.append(
            {
                "file": src,
                "min_line": min(line_nos),
                "max_line": max(line_nos),
                "n": len(line_nos),
            }
        )
    return result


def _group_entry(scope: str, run_id: str, app: str, scenario: str, phase: str, rows: list) -> dict:
    stats = summarise([row.ms_value for row in rows])
    entry = {
        "scope": scope,
        "run_id": run_id,
        "app": app,
        "scenario": scenario,
        "phase": phase,
    }
    entry.update(stats)
    entry["sources"] = sources_of(rows)
    return entry


def _mem_entry(scope: str, run_id: str, app: str, scenario: str, rows: list) -> dict:
    per_metric = {key: [] for key in _MEM_KEYS}
    for row in rows:
        values = parse_mem_extra(row.extra)
        for key in _MEM_KEYS:
            if key in values:
                per_metric[key].append(values[key])

    entry = {
        "scope": scope,
        "run_id": run_id,
        "app": app,
        "scenario": scenario,
        "n": len(rows),
        "sources": sources_of(rows),
    }
    for key in _MEM_KEYS:
        values = per_metric[key]
        entry[key] = {
            "n": len(values),
            "min": _round3(min(values)) if values else None,
            "median": _round3(statistics.median(values)) if values else None,
            "max": _round3(max(values)) if values else None,
        }
    return entry


def analyse(rows: list, min_samples: int, drop_first: int, drop_mode: str = "none") -> Analysis:
    result = Analysis()

    # ---- 剔除 warmup 样本 ------------------------------------------------
    # 两个被测 app 都不在日志里打 "warmup" 字样，因此正式测量必须显式丢弃每组
    # 第一条有效样本（冷启动 / 首次渲染开销）：
    #   --drop-first N         每个 (run_id,app,scenario,phase) 丢前 N 条
    #   --drop-mode first-per-phase  等价于每组 N=1（与 --drop-first 取较大者）
    effective_drop = drop_first
    if drop_mode == "first-per-phase" and effective_drop < 1:
        effective_drop = 1
    if effective_drop > 0:
        groups = defaultdict(list)
        for row in rows:
            if row.countable:
                groups[(row.run_id, row.app, row.scenario, row.phase)].append(row)
        for key in groups:
            members = sorted(groups[key], key=lambda item: item.order)
            # 显式标冷的样本不丢（冷样本是被测对象，不是 warmup）
            members = [row for row in members if not has_cold_annotation(row.extra)]
            for row in members[:effective_drop]:
                row.warmup = True
                row.warmup_dropped = True
                result.dropped += 1

    # ---- 缺 PBDONE 判定（PBDONE|scenario=ALL 覆盖该 run 全部 scenario）---
    pbdone_keys = {(row.run_id, row.scenario) for row in rows if row.record == RECORD_PBDONE}
    pbdone_all = {
        row.run_id
        for row in rows
        if row.record == RECORD_PBDONE and row.scenario == "ALL"
    }

    missing = {}
    for row in rows:
        if row.record in (RECORD_PBDONE, RECORD_RUNENV):
            continue
        if (row.run_id, row.scenario) in pbdone_keys:
            continue
        if row.run_id in pbdone_all:
            continue
        row.flags.add("no_pbdone")
        missing[(row.run_id, row.scenario)] = row.app
    result.missing_pbdone = [
        {"run_id": run_id, "app": app, "scenario": scenario}
        for (run_id, scenario), app in sorted(missing.items())
    ]

    # ---- 样本数与 n<min_samples -----------------------------------------
    members = defaultdict(list)
    for row in rows:
        if row.record in STATS_RECORDS:
            members[(row.run_id, row.app, row.scenario, row.phase)].append(row)

    for key, group_rows in members.items():
        valid_n = sum(1 for row in group_rows if row.countable)
        if valid_n >= min_samples:
            continue
        for row in group_rows:
            row.flags.add("n<%d" % min_samples)
            row.confidence = "LOW_CONFIDENCE"
        result.low_confidence_groups.append(
            {
                "run_id": key[0],
                "app": key[1],
                "scenario": key[2],
                "phase": key[3],
                "n": valid_n,
                "errors": sum(1 for row in group_rows if row.is_error),
                "warmup": sum(1 for row in group_rows if row.warmup),
            }
        )
    result.low_confidence_groups.sort(
        key=lambda item: (item["run_id"], item["app"], item["scenario"], item["phase"])
    )

    # ---- 有效样本，分 ms / 内存两路 ------------------------------------
    result.valid = [row for row in rows if row.countable]
    ms_rows = [row for row in result.valid if not row.is_pss]
    mem_rows = [row for row in result.valid if row.is_pss]

    per_run_ms = defaultdict(list)
    for row in ms_rows:
        per_run_ms[(row.run_id, row.app, row.scenario, row.phase)].append(row)
    for key in sorted(per_run_ms):
        result.groups.append(
            _group_entry("run", key[0], key[1], key[2], key[3], per_run_ms[key])
        )

    cross_ms = defaultdict(list)
    for row in ms_rows:
        cross_ms[(row.app, row.scenario, row.phase)].append(row)
    for key in sorted(cross_ms):
        result.groups.append(_group_entry("all", "", key[0], key[1], key[2], cross_ms[key]))

    per_run_mem = defaultdict(list)
    for row in mem_rows:
        per_run_mem[(row.run_id, row.app, row.scenario)].append(row)
    for key in sorted(per_run_mem):
        result.mem.append(_mem_entry("run", key[0], key[1], key[2], per_run_mem[key]))

    cross_mem = defaultdict(list)
    for row in mem_rows:
        cross_mem[(row.app, row.scenario)].append(row)
    for key in sorted(cross_mem):
        result.mem.append(_mem_entry("all", "", key[0], key[1], cross_mem[key]))

    return result


# --------------------------------------------------------------------------
# 输出：CSV
# --------------------------------------------------------------------------


def write_csv(path: Path, rows: list) -> None:
    with open(path, "w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(CSV_HEADER)
        for row in rows:
            writer.writerow(row.csv_row())


# --------------------------------------------------------------------------
# 输出：stats.json
# --------------------------------------------------------------------------


def build_stats(rows: list, result: Analysis, parsed_files: list, args, name: str) -> dict:
    failures = [failure for fp in parsed_files for failure in fp.failures]
    by_record = defaultdict(int)
    for row in rows:
        by_record[row.record] += 1

    return {
        "schema": 1,
        "tool": "scripts/android/spike2/parse.py",
        "generated_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        "name": name,
        "options": {
            "app": args.app,
            "drop_first": args.drop_first,
            "drop_mode": args.drop_mode,
            "min_samples": args.min_samples,
            "out": str(args.out_dir) if getattr(args, "out_dir", None) else None,
        },
        "inputs": [
            {
                "file": fp.src,
                "lines": fp.lines,
                "record_lines": fp.record_lines,
                "parse_failures": len(fp.failures),
                "run_id": fp.run_id,
                "app": fp.app,
                "runenv": fp.runenv,
            }
            for fp in parsed_files
        ],
        "counts": {
            "files": len(parsed_files),
            "lines": sum(fp.lines for fp in parsed_files),
            "records": len(rows),
            "source_record_lines": sum(fp.record_lines for fp in parsed_files),
            "parse_failures": len(failures),
            "by_record": dict(sorted(by_record.items())),
            "warmup": sum(1 for row in rows if row.warmup),
            "warmup_dropped": result.dropped,
            "error": sum(1 for row in rows if row.is_error),
            "pss": sum(1 for row in rows if row.is_pss),
            "stats_samples": len(result.valid),
        },
        "groups": result.groups,
        "mem": result.mem,
        "low_confidence_groups": result.low_confidence_groups,
        "missing_pbdone": result.missing_pbdone,
        "runenv": [
            {"file": fp.src, "fields": meta} for fp in parsed_files for meta in fp.runenv
        ],
        "parse_failures": [failure.as_dict() for failure in failures],
    }


# --------------------------------------------------------------------------
# 输出：markdown
# --------------------------------------------------------------------------


def _md_cell(value) -> str:
    text = "" if value is None else str(value)
    return text.replace("|", "\\|").replace("\n", " ")


def _md_table(headers: list, rows: list) -> str:
    lines = ["| " + " | ".join(headers) + " |"]
    lines.append("|" + "|".join(["---"] * len(headers)) + "|")
    for row in rows:
        lines.append("| " + " | ".join(_md_cell(cell) for cell in row) + " |")
    return "\n".join(lines)


def _fmt(value) -> str:
    if value is None:
        return "-"
    return "%.3f" % float(value)


def _source_cell(entry: dict) -> str:
    sources = entry.get("sources") or []
    if not sources:
        return "-"
    return "<br>".join(
        "%s:%d-%d(n=%d)" % (src["file"], src["min_line"], src["max_line"], src["n"])
        for src in sources
    )


def _mem_cell(entry: dict, key: str) -> str:
    metric = entry.get(key) or {}
    if not metric.get("n"):
        return "-"
    return "%s / %s / %s" % (
        _fmt(metric.get("min")),
        _fmt(metric.get("median")),
        _fmt(metric.get("max")),
    )


def render_md(
    name: str,
    rows: list,
    result: Analysis,
    parsed_files: list,
    args,
    failures: list,
) -> str:
    by_record = defaultdict(int)
    for row in rows:
        by_record[row.record] += 1

    total_lines = sum(fp.lines for fp in parsed_files)
    warmup_rows = [row for row in rows if row.warmup]
    error_rows = [row for row in rows if row.is_error]
    pss_rows = [row for row in rows if row.is_pss]
    stats_samples = result.valid

    out = []
    out.append("# Spike2 基准统计 · %s" % name)
    out.append("")
    out.append(
        "> 由 `scripts/android/spike2/parse.py` 生成；本文件所有数字均来自解析结果，未做估算。"
    )
    out.append(
        "> 生成时间：%s　|　解析失败行：%d"
        % (datetime.now().astimezone().isoformat(timespec="seconds"), len(failures))
    )
    out.append("")

    # ---- 1. 环境 --------------------------------------------------------
    out.append("## 1. 环境与输入")
    out.append("")
    out.append(
        _md_table(
            ["文件", "run_id", "app", "总行数", "记录行", "解析失败"],
            [
                [fp.src, fp.run_id, fp.app, fp.lines, fp.record_lines, len(fp.failures)]
                for fp in parsed_files
            ],
        )
    )
    out.append("")

    runenv_rows = []
    for fp in parsed_files:
        for meta in fp.runenv:
            runenv_rows.append(
                [
                    fp.src,
                    meta.get("run_id", ""),
                    meta.get("app", ""),
                    meta.get("apk_md5", ""),
                    meta.get("scenario", ""),
                    meta.get("ts", ""),
                ]
            )
    out.append("**RUNENV 元数据**")
    out.append("")
    if runenv_rows:
        out.append(
            _md_table(
                ["文件", "run_id", "app", "apk_md5", "scenario", "ts"], runenv_rows
            )
        )
    else:
        out.append("（输入中没有 RUNENV 行；run_id 取文件名 stem，app 取文件名前缀映射或 unknown。）")
    out.append("")
    out.append(
        _md_table(
            ["参数", "值"],
            [
                ["--app", args.app or "（未指定，按 RUNENV/文件名前缀）"],
                ["--drop-first", args.drop_first],
                ["--drop-mode", args.drop_mode],
                ["--min-samples", args.min_samples],
                ["输入路径", args.safe_paths],
            ],
        )
    )
    out.append("")

    # ---- 2. 样本构成 ----------------------------------------------------
    out.append("## 2. 样本构成")
    out.append("")
    out.append(
        _md_table(
            ["record", "CSV 行数"],
            [[record, count] for record, count in sorted(by_record.items())],
        )
    )
    out.append("")
    out.append(
        "- 输入 %d 个文件 / %d 行；识别为本契约记录 %d 行；CSV 总计 %d 行。"
        % (len(parsed_files), total_lines, sum(fp.record_lines for fp in parsed_files), len(rows))
    )
    out.append(
        "- 参与统计的样本（ms 非空、非 warmup、非 error）：%d 行；其中 ms 样本 %d 行、pss 样本 %d 行。"
        % (len(stats_samples), len([row for row in stats_samples if not row.is_pss]), len(pss_rows))
    )
    out.append(
        "- warmup：%d 行（其中剔除 %d 行；drop_mode=%s，仍保留在 CSV，统计排除）。"
        % (len(warmup_rows), result.dropped, args.drop_mode)
    )
    out.append("- error：%d 行（confidence=LOW_CONFIDENCE，不进 ms 统计）。" % len(error_rows))
    out.append("")

    # ---- 3. ms 统计（跨 run） -------------------------------------------
    out.append("## 3. ms 统计（跨 run）")
    out.append("")
    out.append(
        "> 口径：median=`statistics.median`；p50/p95=nearest-rank（idx=ceil(q*n)-1，1-based）；"
        "单位 ms，保留 3 位小数。"
    )
    out.append(
        "> 参与：PBPERF（ms 非空、非 warmup、非 error）、PBPDF、AMSTART、PBTIME；"
        "PBDONE/RUNENV 不参与；pss 行见第 5 节。"
    )
    out.append("")
    cross = [entry for entry in result.groups if entry["scope"] == "all"]
    if cross:
        out.append(
            _md_table(
                ["app", "scenario", "phase", "n", "median", "p50", "p95", "max", "min", "来源"],
                [
                    [
                        entry["app"],
                        entry["scenario"],
                        entry["phase"],
                        entry["n"],
                        _fmt(entry["median"]),
                        _fmt(entry["p50"]),
                        _fmt(entry["p95"]),
                        _fmt(entry["max"]),
                        _fmt(entry["min"]),
                        _source_cell(entry),
                    ]
                    for entry in cross
                ],
            )
        )
    else:
        out.append("（没有可统计的 ms 样本。）")
    out.append("")

    # ---- 4. ms 统计（按 run） -------------------------------------------
    out.append("## 4. ms 统计（按 run）")
    out.append("")
    per_run = [entry for entry in result.groups if entry["scope"] == "run"]
    if per_run:
        out.append(
            _md_table(
                ["run_id", "app", "scenario", "phase", "n", "median", "p50", "p95", "max", "min", "来源"],
                [
                    [
                        entry["run_id"],
                        entry["app"],
                        entry["scenario"],
                        entry["phase"],
                        entry["n"],
                        _fmt(entry["median"]),
                        _fmt(entry["p50"]),
                        _fmt(entry["p95"]),
                        _fmt(entry["max"]),
                        _fmt(entry["min"]),
                        _source_cell(entry),
                    ]
                    for entry in per_run
                ],
            )
        )
    else:
        out.append("（没有可统计的 ms 样本。）")
    out.append("")

    # ---- 5. 内存 --------------------------------------------------------
    out.append("## 5. 内存（PSS，不进 ms 表）")
    out.append("")
    out.append(
        "> 单位 KB；每格为 `min / median / max`，n 为该组 pss 行数；`-` 表示该组没有对应分项。"
    )
    out.append("")
    mem_cross = [entry for entry in result.mem if entry["scope"] == "all"]
    mem_run = [entry for entry in result.mem if entry["scope"] == "run"]

    out.append("**跨 run**")
    out.append("")
    if mem_cross:
        out.append(
            _md_table(
                ["app", "scenario", "n", "pss_kb", "native_kb", "java_kb", "来源"],
                [
                    [
                        entry["app"],
                        entry["scenario"],
                        entry["n"],
                        _mem_cell(entry, "pss_kb"),
                        _mem_cell(entry, "native_kb"),
                        _mem_cell(entry, "java_kb"),
                        _source_cell(entry),
                    ]
                    for entry in mem_cross
                ],
            )
        )
    else:
        out.append("（没有 pss 行。）")
    out.append("")

    out.append("**按 run**")
    out.append("")
    if mem_run:
        out.append(
            _md_table(
                ["run_id", "app", "scenario", "n", "pss_kb", "native_kb", "java_kb", "来源"],
                [
                    [
                        entry["run_id"],
                        entry["app"],
                        entry["scenario"],
                        entry["n"],
                        _mem_cell(entry, "pss_kb"),
                        _mem_cell(entry, "native_kb"),
                        _mem_cell(entry, "java_kb"),
                        _source_cell(entry),
                    ]
                    for entry in mem_run
                ],
            )
        )
    else:
        out.append("（没有 pss 行。）")
    out.append("")

    # ---- 6. 缺 PBDONE ---------------------------------------------------
    out.append("## 6. 缺 PBDONE 的 scenario")
    out.append("")
    out.append(
        "> 同一 run_id 内没有对应 `PBDONE|scenario=<scenario>`（`scenario=ALL` 视为覆盖该 run 全部 scenario）。"
    )
    out.append("")
    if result.missing_pbdone:
        out.append(
            _md_table(
                ["run_id", "app", "scenario", "影响行数"],
                [
                    [
                        item["run_id"],
                        item["app"],
                        item["scenario"],
                        sum(
                            1
                            for row in rows
                            if row.record not in (RECORD_PBDONE, RECORD_RUNENV)
                            and row.run_id == item["run_id"]
                            and row.scenario == item["scenario"]
                        ),
                    ]
                    for item in result.missing_pbdone
                ],
            )
        )
    else:
        out.append("（所有 scenario 都有对应 PBDONE。）")
    out.append("")

    # ---- 7. LOW_CONFIDENCE ---------------------------------------------
    out.append("## 7. LOW_CONFIDENCE 组")
    out.append("")
    out.append(
        "> 组 (run_id, app, scenario, phase) 的有效样本数 < --min-samples 时，该组所有行 "
        "confidence=LOW_CONFIDENCE 且 flags 含 `n<%d`。" % args.min_samples
    )
    out.append("")
    if result.low_confidence_groups:
        out.append(
            _md_table(
                ["run_id", "app", "scenario", "phase", "n", "error 行", "warmup 行", "原因"],
                [
                    [
                        item["run_id"],
                        item["app"],
                        item["scenario"],
                        item["phase"],
                        item["n"],
                        item["errors"],
                        item["warmup"],
                        "n<%d" % args.min_samples,
                    ]
                    for item in result.low_confidence_groups
                ],
            )
        )
    else:
        out.append("（没有 LOW_CONFIDENCE 组。）")
    out.append("")
    out.append("带 `error` flag 的行（单行级 LOW_CONFIDENCE，不进 ms 统计）：")
    out.append("")
    if error_rows:
        out.append(
            _md_table(
                ["文件:行", "run_id", "scenario", "phase", "extra"],
                [
                    [
                        "%s:%d" % (row.src_file, row.src_line),
                        row.run_id,
                        row.scenario,
                        row.phase,
                        row.extra,
                    ]
                    for row in error_rows
                ],
            )
        )
    else:
        out.append("（没有 error 行。）")
    out.append("")

    # ---- 8. 解析失败 ----------------------------------------------------
    out.append("## 8. 解析失败行")
    out.append("")
    out.append("- 合计：%d 行（`--strict` 时非 0 退出）。" % len(failures))
    out.append("")
    if failures:
        out.append(
            _md_table(
                ["文件:行", "原因", "原文"],
                [
                    ["%s:%d" % (item.file, item.line), item.reason, item.text]
                    for item in failures[:50]
                ],
            )
        )
        if len(failures) > 50:
            out.append("")
            out.append("（仅列出前 50 条。）")
    else:
        out.append("（没有解析失败行。）")
    out.append("")

    return "\n".join(out).rstrip() + "\n"


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="parse.py",
        description="Spike2 基准日志解析：logcat -> CSV + stats.json + markdown。",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "示例：\n"
            "  python scripts/android/spike2/parse.py spikes/spike2-results/raw/render-run1.log\n"
            "  python scripts/android/spike2/parse.py --name merged --drop-first 1 \\\n"
            "      spikes/spike2-results/raw/\n"
            "\n"
            "记录类型：PBPERF / PBDONE / PBTIME / PBPDF(pdf_open,pdf_page) / AMSTART / RUNENV。\n"
            "输出：<out>/<name>.csv / .stats.json / .md。"
        ),
    )
    parser.add_argument(
        "paths",
        nargs="+",
        metavar="PATH",
        help="logcat 文件或目录（目录递归 *.log / *.txt）",
    )
    parser.add_argument(
        "--out",
        default=None,
        metavar="DIR",
        help="输出目录（默认 spikes/spike2-results/csv，相对仓库根；显式相对路径按 cwd 解析）",
    )
    parser.add_argument(
        "--name",
        default=None,
        metavar="NAME",
        help="输出文件基名；默认单文件=文件 stem、多文件=首个 stem_merged（auto 同默认）",
    )
    parser.add_argument("--app", default=None, metavar="APP", help="强制 app（覆盖 RUNENV / 文件名映射）")
    parser.add_argument(
        "--drop-first",
        type=int,
        default=0,
        metavar="N",
        dest="drop_first",
        help="每个 (run_id,app,scenario,phase) 丢掉最先出现的 N 个有效样本（标 warmup=true，默认 0）",
    )
    parser.add_argument(
        "--drop-mode",
        choices=["none", "first-per-phase"],
        default="none",
        dest="drop_mode",
        help="warmup 剔除模式：none=只用 --drop-first；first-per-phase=每组丢第一条（正式测量用这个）",
    )
    parser.add_argument(
        "--min-samples",
        type=int,
        default=5,
        metavar="N",
        dest="min_samples",
        help="低于该样本数的组标 LOW_CONFIDENCE（默认 5）",
    )
    parser.add_argument("--quiet", action="store_true", help="不打 stdout 摘要")
    parser.add_argument("--strict", action="store_true", help="有解析失败行时退出码 2")
    return parser


def resolve_out_dir(raw) -> Path:
    if raw is None:
        return DEFAULT_OUT_DIR
    path = Path(raw)
    if path.is_absolute():
        return path
    return (Path.cwd() / path).resolve()


def resolve_name(raw, files: list) -> str:
    if raw and raw != "auto":
        return raw
    stem = files[0].stem
    if len(files) == 1:
        return stem
    return stem + "_merged"


def _utf8_stdio() -> None:
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is None:
            continue
        try:
            reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass


def run(argv) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.drop_first < 0:
        raise UsageError("--drop-first 不能为负数")
    if getattr(args, "drop_mode", "none") not in ("none", "first-per-phase"):
        raise UsageError("--drop-mode 只支持 none / first-per-phase")
    if args.min_samples < 1:
        raise UsageError("--min-samples 必须 >= 1")

    args.safe_paths = " ".join(args.paths)
    files = expand_paths(args.paths)

    parsed_files = [parse_file(path) for path in files]
    rows = build_rows(parsed_files, args.app)
    result = analyse(rows, args.min_samples, args.drop_first, args.drop_mode)

    out_dir = resolve_out_dir(args.out)
    args.out_dir = out_dir
    name = resolve_name(args.name, files)
    out_dir.mkdir(parents=True, exist_ok=True)

    csv_path = out_dir / ("%s.csv" % name)
    stats_path = out_dir / ("%s.stats.json" % name)
    md_path = out_dir / ("%s.md" % name)

    stats = build_stats(rows, result, parsed_files, args, name)
    failures = [failure for fp in parsed_files for failure in fp.failures]

    write_csv(csv_path, rows)
    stats_path.write_text(
        json.dumps(stats, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    md_path.write_text(
        render_md(name, rows, result, parsed_files, args, failures), encoding="utf-8"
    )

    if not args.quiet:
        print(
            "[parse] 输入 %d 个文件 / %d 行；记录 %d 行；CSV %d 行；参与统计 %d 行"
            % (
                len(parsed_files),
                stats["counts"]["lines"],
                stats["counts"]["source_record_lines"],
                len(rows),
                len(result.valid),
            )
        )
        print(
            "[parse] warmup %d（drop-first %d）；error %d；pss %d；LOW_CONFIDENCE 组 %d；缺 PBDONE %d；解析失败 %d"
            % (
                stats["counts"]["warmup"],
                result.dropped,
                stats["counts"]["error"],
                stats["counts"]["pss"],
                len(result.low_confidence_groups),
                len(result.missing_pbdone),
                len(failures),
            )
        )
        print("[parse] 输出：%s / %s / %s" % (csv_path, stats_path.name, md_path.name))

    for failure in failures[:20]:
        print(
            "[parse] WARN %s:%d: %s | %s"
            % (failure.file, failure.line, failure.reason, failure.text),
            file=sys.stderr,
        )
    if len(failures) > 20:
        print("[parse] WARN ... 其余 %d 条见 stats.json" % (len(failures) - 20), file=sys.stderr)
    print("[parse] 解析失败行：%d" % len(failures), file=sys.stderr)

    if args.strict and failures:
        return 2
    return 0


def main(argv=None) -> int:
    _utf8_stdio()
    try:
        return run(sys.argv[1:] if argv is None else argv)
    except UsageError as exc:
        print("parse.py: %s" % exc, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
