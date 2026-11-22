#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""P1-EPUB performance collector and strict evaluator.

The single-process entry points (run_epub/run_epub_turns) take a small device
adapter from perf-device.py. The staged path (init/step/finish) collects one
bounded unit per host call through RunDevice and keeps every adb command on
disk before it is dispatched, so a 03/04 run survives the 120 s segment budget.
Thresholds, failure codes and graders are shared by both paths, so negative
controls still run on a workstation without a device.
"""
from __future__ import annotations

import contextlib
import hashlib
import json
import math
import os
import re
import subprocess
import time
import unicodedata
import uuid
import zlib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Set, Tuple
from xml.etree import ElementTree as ET

PKG = "app.particlebook"
ACT = "app.particlebook/.android.MobileActivity"

FIRST_PHASE = "reader_epub_first_chapter"
PAGE_PHASE = "reader_epub_page"
PBPERF_TAG = "PBPERF"

FIRST_FRAME_P95_BUDGET_MS = 500.0
TURN_P95_BUDGET_MS = 16.0
MIN_COLD_SAMPLES = 5
MIN_TURNS = 20
MIN_ROUNDS = 3
QA_BOOK_ID = 201
MOBY_SHA256 = "4f0a70cac781ab8f30b5efb8d20ee78db9e7e1fc23e0c261e35c8690f48db34c"
QA_BOOK_PATH = "/data/user/0/app.particlebook/files/_pb_files/qa-moby.epub"
PRIMARY_SERIAL = "8A9Y0G2XJ"

REQUIRED_COMMON = ("book_id", "chapter", "page", "text_only", "start_ns",
                   "draw_end_ns", "end_ns", "end_kind")
REQUIRED_FIRST = REQUIRED_COMMON
REQUIRED_PAGE = REQUIRED_COMMON + ("turn",)
ALLOWED_END_KINDS = ("commit", "draw")


class PerfParseError(ValueError):
    pass


@dataclass(frozen=True)
class PerfEvent:
    phase: str
    ms: float
    extra: Dict[str, str]
    raw: str
    pid: Optional[int] = None
    log_time: Optional[str] = None

    def integer(self, key: str) -> int:
        value = self.extra.get(key)
        if value is None:
            raise PerfParseError("missing field %s" % key)
        try:
            return int(value)
        except ValueError:
            raise PerfParseError("field %s is not an integer: %r" % (key, value))

    @property
    def book_id(self) -> int:
        return self.integer("book_id")

    @property
    def chapter(self) -> int:
        return self.integer("chapter")

    @property
    def page(self) -> int:
        return self.integer("page")

    @property
    def text_only(self) -> bool:
        return self.integer("text_only") == 1

    @property
    def start_ns(self) -> int:
        return self.integer("start_ns")

    @property
    def draw_end_ns(self) -> int:
        return self.integer("draw_end_ns")

    @property
    def end_ns(self) -> int:
        return self.integer("end_ns")

    @property
    def end_kind(self) -> str:
        return self.extra.get("end_kind", "")

    @property
    def turn(self) -> Optional[int]:
        value = self.extra.get("turn")
        return int(value) if value is not None else None

    @property
    def formal(self) -> bool:
        return self.end_kind == "commit"


@dataclass
class Verdict:
    pass_: bool = True
    failures: List[Dict[str, str]] = field(default_factory=list)
    diagnostics: List[Dict[str, str]] = field(default_factory=list)

    def fail(self, code: str, message: str) -> None:
        self.pass_ = False
        self.failures.append({"code": code, "message": message})

    def diagnostic(self, code: str, message: str) -> None:
        # Any diagnostic means the run is not acceptance-PASS, even when no
        # individual sample failed.
        self.pass_ = False
        self.diagnostics.append({"code": code, "message": message})

    @property
    def label(self) -> str:
        if self.pass_:
            return "PASS"
        if self.failures:
            return "FAIL"
        return "DIAGNOSTIC"

    def as_dict(self) -> Dict[str, Any]:
        return {"verdict": self.label, "pass": self.pass_,
                "failures": self.failures, "diagnostics": self.diagnostics}


@dataclass
class FrameStat:
    flags: int
    intended_vsync_ns: int
    draw_start_ns: int
    sync_queued_ns: int
    frame_completed_ns: int
    window: str = ""
    pid: Optional[int] = None

    @property
    def key(self) -> Tuple[Optional[int], str, int, int]:
        # RT-only and UI renders can share a vsync; retain each queued request.
        return self.pid, self.window, self.intended_vsync_ns, self.sync_queued_ns

    @property
    def duration_ms(self) -> float:
        # Lead-confirmed app-frame metric: total frame latency, not draw time.
        return (self.frame_completed_ns - self.intended_vsync_ns) / 1_000_000.0


def _parse_int(value: str, field_name: str) -> int:
    if not re.fullmatch(r"-?[0-9]+", value or ""):
        raise PerfParseError("%s is not an integer: %r" % (field_name, value))
    return int(value)


def _parse_ms(value: str) -> float:
    """Kotlin emits either integer or fractional milliseconds (550.0/16.9)."""
    try:
        ms = float(value)
    except (TypeError, ValueError):
        raise PerfParseError("ms is not a number: %r" % (value,))
    if not math.isfinite(ms):
        raise PerfParseError("ms must be finite: %r" % (value,))
    if ms < 0:
        raise PerfParseError("ms must be non-negative: %r" % (value,))
    return ms


def _split_extra(extra: str) -> Dict[str, str]:
    if not extra:
        raise PerfParseError("empty extra")
    out: Dict[str, str] = {}
    for item in extra.split(","):
        if not item:
            raise PerfParseError("empty extra item")
        if ":" not in item:
            raise PerfParseError("extra item without colon: %r" % item)
        key, value = item.split(":", 1)
        if not key or not value:
            raise PerfParseError("empty extra key/value: %r" % item)
        if key in out:
            raise PerfParseError("duplicate extra key: %s" % key)
        out[key] = value
    return out


def parse_perf_line(line: str) -> Optional[PerfEvent]:
    """Parse one logcat line. Return None for non-PBPERF/unknown phases.

    Strict for the two EPUB phases: malformed field syntax raises.
    """
    raw = line.rstrip("\r\n")
    if "PBPERF|" not in raw:
        return None
    pid = None
    log_time = None
    m = re.search(r"(?:^|\s)([0-9]{2}-[0-9]{2}\s+[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3})\s+([0-9]+)\s+([0-9]+)\s+([VDIWEF])\s+([^:]+?)\s*:\s*(PBPERF\|.*)$", raw)
    if m:
        log_time, pid_s, _tid, priority, tag, body = m.groups()
        # Only INFO/PBPERF is a formal sample; W/E lines are app diagnostics.
        if priority != "I" or tag.strip() != "PBPERF":
            return None
        pid = int(pid_s)
    else:
        # Bare PBPERF is accepted only for offline fixtures/tests.
        body = raw[raw.index("PBPERF|"):]
    parts = body.split("|")
    if len(parts) != 4:
        raise PerfParseError("PBPERF line must have exactly 4 pipe fields: %r" % body)
    if parts[0] != "PBPERF":
        raise PerfParseError("bad PBPERF tag: %r" % parts[0])
    phase_part, ms_part, extra_part = parts[1], parts[2], parts[3]
    if not phase_part.startswith("phase="):
        raise PerfParseError("missing phase=: %r" % phase_part)
    if not ms_part.startswith("ms="):
        raise PerfParseError("missing ms=: %r" % ms_part)
    phase = phase_part.split("=", 1)[1]
    if phase not in (FIRST_PHASE, PAGE_PHASE):
        return None
    ms = _parse_ms(ms_part.split("=", 1)[1])
    if not extra_part.startswith("extra="):
        raise PerfParseError("missing extra=: %r" % extra_part)
    extra = _split_extra(extra_part.split("=", 1)[1])
    required = REQUIRED_FIRST if phase == FIRST_PHASE else REQUIRED_PAGE
    missing = [key for key in required if key not in extra]
    if missing:
        raise PerfParseError("missing field(s): %s" % ",".join(missing))
    for key in ("book_id", "chapter", "page", "text_only", "start_ns",
                "draw_end_ns", "end_ns"):
        _parse_int(extra[key], key)
    if phase == PAGE_PHASE:
        _parse_int(extra["turn"], "turn")
    if extra["text_only"] not in ("0", "1"):
        raise PerfParseError("text_only must be 0 or 1")
    if extra["end_kind"] not in ALLOWED_END_KINDS:
        raise PerfParseError("end_kind must be commit or draw")
    ev = PerfEvent(phase=phase, ms=ms, extra=extra, raw=raw, pid=pid,
                   log_time=log_time)
    if ev.start_ns <= 0 or ev.draw_end_ns <= 0 or ev.end_ns <= 0:
        raise PerfParseError("timestamps must be positive")
    if not (ev.start_ns <= ev.draw_end_ns <= ev.end_ns):
        raise PerfParseError("expected start_ns <= draw_end_ns <= end_ns")
    if ev.book_id <= 0 or ev.chapter < 0 or ev.page < 0:
        raise PerfParseError("book_id/chapter/page out of range")
    if phase == PAGE_PHASE and (ev.turn or 0) <= 0:
        raise PerfParseError("turn must be positive")
    # The timestamp delta is the authoritative duration; ms is redundant but
    # must agree within a small logging tolerance.
    delta_ms = (ev.end_ns - ev.start_ns) / 1_000_000.0
    if abs(delta_ms - ms) > 0.001:
        raise PerfParseError("ms=%s disagrees with end_ns-start_ns=%.2fms" % (ms, delta_ms))
    # Retain raw ms for audit, but gate on the full ns delta even when the
    # redundant printed value differs by a sub-microsecond logging tolerance.
    return PerfEvent(phase=phase, ms=delta_ms, extra=extra, raw=raw, pid=pid, log_time=log_time)


def parse_perf_lines(lines: Iterable[str]) -> List[PerfEvent]:
    out: List[PerfEvent] = []
    for line in lines:
        if "PBPERF|" not in line:
            continue
        if FIRST_PHASE not in line and PAGE_PHASE not in line:
            continue
        ev = parse_perf_line(line)
        if ev is not None:
            out.append(ev)
    return out


def event_key(ev: PerfEvent) -> Tuple[str, int]:
    return ev.phase, ev.start_ns


def select_new_lines(before: Sequence[str], after: Sequence[str]) -> List[str]:
    """Per-run logcat cursor without logcat -c.

    A multiset difference is enough because start_ns is unique per event. If
    the ring buffer wrapped, only new lines remain and are still selected.
    """
    counts: Dict[str, int] = {}
    for line in before:
        counts[line] = counts.get(line, 0) + 1
    out: List[str] = []
    for line in after:
        n = counts.get(line, 0)
        if n > 0:
            counts[line] = n - 1
        else:
            out.append(line)
    return out


def single_pid(events: Sequence[PerfEvent]) -> Optional[int]:
    pids = {ev.pid for ev in events if ev.pid is not None}
    if len(pids) == 1:
        return next(iter(pids))
    return None


def check_event_pid(events: Sequence[PerfEvent], expected_pid: Optional[int],
                    v: Verdict) -> None:
    if expected_pid is None or expected_pid <= 0:
        v.fail("APP_PID_MISSING", "running app PID was not observed")
    if not events:
        return
    if any(ev.pid is None or ev.pid <= 0 for ev in events):
        v.fail("PID_MISSING", "every measured event must expose a positive logcat PID")
    pids = {ev.pid for ev in events if ev.pid is not None}
    if len(pids) > 1:
        v.fail("MULTIPLE_PIDS", "events came from multiple app pids: %s" % sorted(pids))
    if expected_pid is not None and pids != {expected_pid}:
        v.fail("PID_MISMATCH", "event pid %s != app pid %s" % (sorted(pids), expected_pid))

def check_event_identity(events: Sequence[PerfEvent], v: Verdict) -> None:
    seen: Dict[Tuple[str, int], PerfEvent] = {}
    for ev in events:
        key = event_key(ev)
        if key in seen:
            v.fail("DUPLICATE_EVENT", "duplicate event phase/start_ns: %s" % (key,))
        seen[key] = ev
    book_ids = {ev.book_id for ev in events}
    if len(book_ids) > 1:
        v.fail("BOOK_ID_MISMATCH", "multiple book_id values: %s" % sorted(book_ids))


def nearest_rank(values: Sequence[float], q: float) -> Optional[float]:
    if not values:
        return None
    vs = sorted(values)
    idx = max(0, min(len(vs) - 1, int(math.ceil(q * len(vs))) - 1))
    return vs[idx]


def summarize_ms(values: Sequence[float]) -> Dict[str, Any]:
    # Preserve the original float at thresholds (16.0001 must fail 16ms).
    vs = [float(value) for value in values]
    return {
        "n": len(vs), "p50_ms": nearest_rank(vs, 0.5),
        "p95_ms": nearest_rank(vs, 0.95),
        "min_ms": min(vs) if vs else None,
        "max_ms": max(vs) if vs else None,
        "samples_ms": vs,
    }

def validate_first_chapter(events: Sequence[PerfEvent],
                           expected_chapter: int,
                           expected_page: int,
                           expected_book_id: Optional[int] = None,
                           min_samples: int = MIN_COLD_SAMPLES,
                           require_budget: bool = True) -> Verdict:
    v = Verdict()
    first = [ev for ev in events if ev.phase == FIRST_PHASE]
    if not first:
        v.fail("MISSING_FIRST_EVENT", "no reader_epub_first_chapter event")
        return v
    check_event_identity(first, v)
    if expected_book_id is not None:
        if any(ev.book_id != expected_book_id for ev in first):
            v.fail("BOOK_ID_MISMATCH", "first event book_id != %d" % expected_book_id)
    commit = [ev for ev in first if ev.formal]
    draw_only = [ev for ev in first if not ev.formal]
    if not commit:
        v.fail("NO_COMMIT_SAMPLE", "only draw-end events; draw cannot be a formal sample")
    for ev in first:
        if ev.text_only is not True:
            v.fail("IMAGE_PAGE", "first chapter page is not text_only=1 (page %d)" % ev.page)
        if ev.chapter != expected_chapter:
            v.fail("CHAPTER_MISMATCH", "chapter %d != expected %d" % (ev.chapter, expected_chapter))
        if ev.page != expected_page:
            # Lead rule: a restored/mismatched start is not something to drop
            # while collecting other samples; the whole 03 run is non-PASS.
            v.fail("PAGE_RESTORED", "page %d != expected %d; run is not acceptance-PASS" % (ev.page, expected_page))
        if ev.ms <= 0:
            v.fail("NON_POSITIVE_MS", "ms must be positive")
    # A restored first page is not a valid 03 sample. Keep the event for
    # evidence but do not let it pollute the percentile.
    valid = [ev for ev in commit if ev.chapter == expected_chapter and ev.page == expected_page and ev.text_only]
    if draw_only:
        v.diagnostic("DRAW_EVENTS_PRESENT", "%d draw-only events present (not used in p95)" % len(draw_only))
    if len(valid) < min_samples:
        v.fail("INSUFFICIENT_SAMPLES", "%d valid first-chapter samples < %d" % (len(valid), min_samples))
    summary = summarize_ms([ev.ms for ev in valid])
    if require_budget and summary["p95_ms"] is not None and summary["p95_ms"] > FIRST_FRAME_P95_BUDGET_MS:
        v.fail("FIRST_FRAME_BUDGET", "p95 %.2fms > %.0fms" % (summary["p95_ms"], FIRST_FRAME_P95_BUDGET_MS))
    return v


def validate_page_events(events: Sequence[PerfEvent],
                         expected_chapter: int,
                         expected_book_id: Optional[int] = None,
                         expected_turns: int = MIN_TURNS,
                         turn_start: int = 1,
                         require_samples: bool = True,
                         require_reverse: bool = False,
                         start_page: Optional[int] = None) -> Verdict:
    v = Verdict()
    pages = [ev for ev in events if ev.phase == PAGE_PHASE]
    if not pages:
        v.fail("MISSING_PAGE_EVENTS", "no reader_epub_page event")
        return v
    check_event_identity(pages, v)
    if expected_book_id is not None and any(ev.book_id != expected_book_id for ev in pages):
        v.fail("BOOK_ID_MISMATCH", "page event book_id != %d" % expected_book_id)
    commit = [ev for ev in pages if ev.formal]
    if not commit:
        v.fail("NO_COMMIT_SAMPLE", "only draw-end page events")
    turns = [ev.turn for ev in commit]
    if any(t is None for t in turns):
        v.fail("TURN_MISSING", "page event without turn")
    expected = list(range(turn_start, turn_start + expected_turns))
    if turns != expected:
        v.fail("TURN_SEQUENCE", "turn sequence %s != %s" % (turns, expected))
    if require_samples and len(commit) < expected_turns:
        v.fail("INSUFFICIENT_SAMPLES", "%d commit page events < %d" % (len(commit), expected_turns))
    if start_page is not None and commit:
        first_expected = start_page - 1 if require_reverse else start_page
        if commit[0].page != first_expected:
            v.fail("START_PAGE_MISMATCH", "first turn page %d != expected %d" % (commit[0].page, first_expected))
    for ev in pages:
        if ev.chapter != expected_chapter:
            v.fail("CHAPTER_MISMATCH", "page event chapter %d != %d" % (ev.chapter, expected_chapter))
        if ev.text_only is not True:
            v.fail("IMAGE_PAGE", "page turn reached a non-text page (page %d)" % ev.page)
        if ev.ms <= 0:
            v.fail("NON_POSITIVE_MS", "ms must be positive")
    for prev, cur in zip(commit, commit[1:]):
        if cur.start_ns <= prev.end_ns:
            v.fail("EVENT_WINDOW_OVERLAP", "turn timestamps overlap or run backwards")
        if cur.page == prev.page:
            v.fail("PAGE_NOT_CHANGED", "turn %s did not change page %d" % (cur.turn, cur.page))
        elif require_reverse and abs(cur.page - prev.page) != 1:
            v.fail("PAGE_NOT_ADJACENT", "turn %s jumped %d -> %d" % (cur.turn, prev.page, cur.page))
        elif not require_reverse and cur.page != prev.page + 1:
            v.fail("PAGE_NOT_ADJACENT_FORWARD", "turn %s was not a forward adjacent page: %d -> %d" % (cur.turn, prev.page, cur.page))
    if len({ev.page for ev in commit}) < expected_turns:
        v.fail("INSUFFICIENT_DISTINCT_PAGES", "%d distinct pages < %d requested turns; do not reuse cached pages" % (len({ev.page for ev in commit}), expected_turns))
    if require_reverse:
        directions = []
        for prev, cur in zip(commit, commit[1:]):
            if cur.page > prev.page:
                directions.append("next")
            elif cur.page < prev.page:
                directions.append("prev")
        if "next" not in directions or "prev" not in directions:
            v.fail("NO_REVERSE_TURN", "both forward and backward turns are required")
    draw_only = [ev for ev in pages if not ev.formal]
    if draw_only:
        v.diagnostic("DRAW_EVENTS_PRESENT", "%d draw-only page events (not used in p95)" % len(draw_only))
    # input->commit is a separate distribution, not an acceptance gate. The
    # formal 04 gate is the app-frame FrameCompleted-IntendedVsync p95 below.
    return v


# ---------------------------------------------------------------------------
# gfxinfo / framestats

GFX_HEADER_FIELDS = (
    "Flags", "IntendedVsync", "Vsync", "OldestInputEvent", "NewestInputEvent",
    "HandleInputStart", "AnimationStart", "PerformTraversalsStart", "DrawStart",
    "SyncQueued", "SyncStart", "IssueDrawCommandsStart", "SwapBuffers",
    "FrameCompleted", "DequeueBufferDuration", "QueueBufferDuration",
    "GpuCompleted", "SwapBuffersCompleted", "DisplayPresentTime",
)


def parse_gfxinfo_summary(text: str) -> Dict[str, Any]:
    out: Dict[str, Any] = {"total_frames": None, "janky_frames": None,
                           "janky_percent": None, "percentiles": {}}
    m = re.search(r"Total frames rendered:\s*([0-9]+)", text)
    if m:
        out["total_frames"] = int(m.group(1))
    m = re.search(r"Janky frames(?:\s*\([^)]*\))?\s*:\s*([0-9]+)\s*\(([0-9.]+)%\)", text)
    if m:
        out["janky_frames"] = int(m.group(1))
        out["janky_percent"] = float(m.group(2))
    for p in (50, 90, 95, 99):
        m = re.search(r"%d(?:st|nd|rd|th) percentile:\s*([0-9]+)ms" % p, text)
        if m:
            out["percentiles"]["p%d_ms" % p] = int(m.group(1))
    return out


def parse_framestats(text: str) -> List[FrameStat]:
    """Reject incomplete columns/rows instead of silently improving a result."""
    rows: List[FrameStat] = []
    header = None
    saw_header = False
    window = ""
    pid_match = re.search(r"Graphics info for pid\s+(\d+)\s+\[([^]]+)\]", text)
    pid = int(pid_match.group(1)) if pid_match else None
    if pid_match and pid_match.group(2) != PKG:
        raise PerfParseError("framestats process is not %s" % PKG)
    required = {"Flags", "IntendedVsync", "DrawStart", "SyncQueued", "FrameCompleted"}
    seen = set()
    for line_number, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if line.startswith("Window:"):
            window = line.split(":", 1)[1].strip()
            header = None
        if line == "---PROFILEDATA---":
            header = None
            continue
        if line.startswith("Flags,"):
            saw_header = True
            header = [part.strip() for part in line.rstrip(",").split(",")]
            if len(set(header)) != len(header) or not required.issubset(header):
                raise PerfParseError("framestats header missing/duplicate required columns at line %d" % line_number)
            continue
        if header is None or not line:
            continue
        parts = [part.strip() for part in line.rstrip(",").split(",")]
        if len(parts) != len(header):
            raise PerfParseError("framestats row column count at line %d: %d != %d" % (line_number, len(parts), len(header)))
        values = {key: _parse_int(value, "framestats." + key)
                  for key, value in zip(header, parts)}
        flags, intended, draw, queued, completed = [values[key] for key in
            ("Flags", "IntendedVsync", "DrawStart", "SyncQueued", "FrameCompleted")]
        if not (0 < intended <= draw <= queued <= completed < 2 ** 63 - 1):
            raise PerfParseError("invalid/incomplete framestats timestamps at line %d" % line_number)
        frame = FrameStat(flags, intended, draw, queued, completed, window, pid)
        if frame.key in seen:
            raise PerfParseError("duplicate framestats frame at line %d" % line_number)
        seen.add(frame.key)
        rows.append(frame)
    if not saw_header:
        raise PerfParseError("framestats header is missing")
    return rows


def match_frames_for_event(ev: PerfEvent, frames: Sequence[FrameStat]) -> Tuple[Optional[FrameStat], List[FrameStat]]:
    """draw_end_ns must belong to exactly one app frame's DrawStart..SyncQueued."""
    if not ev.formal:
        return None, []
    candidates = [frame for frame in frames
                  if frame.draw_start_ns <= ev.draw_end_ns <= frame.sync_queued_ns]
    return (candidates[0] if len(candidates) == 1 else None), candidates


def validate_frames(events: Sequence[PerfEvent], frames: Sequence[FrameStat],
                    v: Verdict) -> List[Dict[str, Any]]:
    matched = []
    if not frames:
        v.fail("NO_FRAMESTATS", "no parsable gfxinfo framestats")
        return matched
    used = set()
    for ev in events:
        if not ev.formal:
            continue
        frame, candidates = match_frames_for_event(ev, frames)
        if frame is None:
            code = "AMBIGUOUS_FRAME" if len(candidates) > 1 else "FRAME_NOT_FOUND"
            v.fail(code, "draw_end_ns %d matched %d app frames" % (ev.draw_end_ns, len(candidates)))
            continue
        if frame.flags != 0:
            v.fail("FRAME_FLAGS", "frame flags=%d for draw_end_ns %d" % (frame.flags, ev.draw_end_ns))
        if frame.key in used:
            v.fail("FRAME_REUSED", "same app frame matched more than one event")
        used.add(frame.key)
        matched.append({
            "phase": ev.phase, "turn": ev.turn, "page": ev.page,
            "start_ns": ev.start_ns, "end_ns": ev.end_ns,
            "draw_end_ns": ev.draw_end_ns, "window": frame.window,
            "window_end_ns": max(ev.end_ns, frame.frame_completed_ns),
            "frame_intended_vsync_ns": frame.intended_vsync_ns,
            "frame_duration_ms": frame.duration_ms, "frame_flags": frame.flags,
        })
    return matched


def frames_in_windows(frames: Sequence[FrameStat], windows: Sequence[Dict[str, Any]]) -> List[FrameStat]:
    # A turn window includes every overlapping app frame, not only the frame
    # carrying the new page. Idle frames between distinct turns stay outside.
    return [frame for frame in frames if any(
        frame.intended_vsync_ns < window["window_end_ns"] and
        frame.frame_completed_ns > window["start_ns"] for window in windows)]


def evaluate_gfxinfo(text: str, frames: Sequence[FrameStat], events: Sequence[PerfEvent],
                     v: Verdict, min_total_frames: int = 1,
                     refresh_rate_hz: Optional[float] = 60.0,
                     expected_pid: Optional[int] = None) -> Dict[str, Any]:
    reset = parse_gfxinfo_summary(text)
    if reset["total_frames"] is None or reset["total_frames"] < min_total_frames:
        v.fail("NO_GFX_FRAMES", "Total frames rendered is missing or 0")
    elif len(frames) != reset["total_frames"]:
        v.fail("FRAMESTATS_COUNT", "captured %d distinct rows != reset summary %d" % (len(frames), reset["total_frames"]))
    if reset["janky_percent"] is None:
        v.fail("NO_JANKY_SUMMARY", "Janky frames summary missing")
    if expected_pid is not None and any(frame.pid != expected_pid for frame in frames):
        v.fail("FRAME_PID_MISMATCH", "framestats process PID missing or differs from measured app")
    if expected_pid is not None and any(not frame.window or PKG not in frame.window for frame in frames):
        v.fail("FRAME_WINDOW_MISSING", "framestats app window identity missing")
    if len({frame.key for frame in frames}) != len(frames):
        v.fail("DUPLICATE_FRAME", "duplicate app frames in collector input")
    windows = validate_frames(events, frames, v)
    selected = frames_in_windows(frames, windows)
    if not selected:
        v.fail("NO_WINDOW_FRAMES", "no app frames in correlated action windows")
    if any(frame.flags != 0 for frame in selected):
        v.fail("FRAME_FLAGS", "correlated action window contains nonzero frame flags")
    durations = [frame.duration_ms for frame in selected]
    summary = summarize_ms(durations)
    if summary["p95_ms"] is not None and summary["p95_ms"] > TURN_P95_BUDGET_MS:
        v.fail("FRAME_DURATION_BUDGET", "app frame p95 %.6fms > 16ms" % summary["p95_ms"])
    valid_hz = isinstance(refresh_rate_hz, (float, int)) and math.isfinite(refresh_rate_hz) and refresh_rate_hz > 0
    if not valid_hz:
        v.fail("REFRESH_RATE_MISSING", "active display refresh rate was not observed")
    interval_ms = 1000.0 / refresh_rate_hz if valid_hz else None
    janky = sum(duration > interval_ms for duration in durations) if interval_ms else None
    janky_percent = 100.0 * janky / len(selected) if selected and janky is not None else None
    if janky_percent is not None and janky_percent > 2.0:
        v.fail("JANKY_BUDGET", "window janky %.6f%% > 2.0%%" % janky_percent)
    return {
        "reset_summary": reset, "total_frames": len(selected),
        "janky_frames": janky, "janky_percent": janky_percent,
        "jank_definition": "FrameCompleted-IntendedVsync > active refresh interval",
        "refresh_rate_hz": refresh_rate_hz, "refresh_interval_ms": interval_ms,
        "frame_duration": summary, "framestats_count": len(frames),
        "outside_window_frames": len(frames) - len(selected), "windows": windows,
        "window_frames": [dict(window=frame.window, intended_vsync_ns=frame.intended_vsync_ns,
                               frame_completed_ns=frame.frame_completed_ns,
                               duration_ms=frame.duration_ms, flags=frame.flags)
                          for frame in selected],
    }


def parse_refresh_rate(text: str) -> Optional[float]:
    for pattern in (r"mActiveSfDisplayMode[^\n]*?(?:refreshRate|fps)\s*[=:]\s*([0-9.]+)",
                    r"mActiveMode[^\n]*?(?:refreshRate|fps)\s*[=:]\s*([0-9.]+)",
                    r"renderFrameRate\s*[=: ]\s*([0-9.]+)",
                    r"mRefreshRate\s*=\s*([0-9.]+)"):
        match = re.search(pattern, text)
        if match and float(match.group(1)) > 0:
            return float(match.group(1))
    return None

# ---------------------------------------------------------------------------
# preflight

def sha256_file(path: str) -> Optional[str]:
    if not path or not os.path.isfile(path):
        return None
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


SOURCE_TREE_ALGORITHM = "git-visible-android-and-scripts-sha256-v1"


def source_tree_info(root: Optional[str] = None) -> Dict[str, Any]:
    root = root or os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
    def git(*args: str) -> bytes:
        return subprocess.run(["git", *args], cwd=root, check=True,
                              capture_output=True).stdout
    paths = {os.fsdecode(path).replace("\\", "/") for path in
             git("ls-files", "-co", "--exclude-standard", "-z", "--", "android", "scripts/android").split(b"\0") if path}
    digest = hashlib.sha256()
    count = 0
    for path in sorted(paths):
        filename = os.path.join(root, *path.split("/"))
        if not os.path.isfile(filename):
            continue
        digest.update(path.encode("utf-8") + b"\0" + sha256_file(filename).encode("ascii") + b"\n")
        count += 1
    return {"git_head": git("rev-parse", "HEAD").decode().strip(),
            "git_status": git("status", "--porcelain").decode("utf-8", "replace"),
            "source_tree_algorithm": SOURCE_TREE_ALGORITHM,
            "source_tree_sha256": digest.hexdigest(), "source_files": count}


def load_evidence(path: str, prefix: str, v: Verdict) -> Dict[str, Any]:
    if not path:
        v.fail(prefix + "_MISSING", "evidence JSON path is required")
        return {}
    try:
        with open(path, "r", encoding="utf-8-sig") as handle:
            data = json.load(handle)
        if not isinstance(data, dict) or not data:
            raise ValueError("expected a nonempty JSON object")
        return data
    except (OSError, ValueError) as exc:
        v.fail(prefix + "_INVALID", str(exc))
        return {}


def load_release_evidence(path: str, v: Verdict) -> Dict[str, Any]:
    return load_evidence(path, "RELEASE_EVIDENCE", v)


def validate_fixture_evidence(path: str, app: Dict[str, Any], book_title: str,
                              serial: Optional[str], v: Verdict) -> Dict[str, Any]:
    """Recheck raw debug inspection and the stock-device install-r custody chain."""
    data = load_evidence(path, "FIXTURE_EVIDENCE", v)
    if not data:
        return data
    for key in ("raw_evidence", "qa201_book_record_live", "qa201_device_file", "installed_apk", "retained_install"):
        if not isinstance(data.get(key, {}), dict):
            v.fail("FIXTURE_SCHEMA", "expected JSON object for " + key)
            return {"path": os.path.abspath(path), "evidence": data}
    raw = {}
    for key in ("binding_commands", "device_sha256", "device_size", "books", "installed_apk"):
        filename = data.get("raw_evidence", {}).get(key)
        try:
            if not isinstance(filename, str) or not filename:
                raise ValueError("missing raw evidence " + key)
            filename = os.path.join(os.path.dirname(os.path.abspath(path)), filename)
            with open(filename, "r", encoding="utf-8-sig") as handle:
                raw[key] = handle.read()
        except (OSError, ValueError) as exc:
            v.fail("FIXTURE_RAW_EVIDENCE", str(exc))
    record = data.get("qa201_book_record_live", {})
    device_file = data.get("qa201_device_file", {})
    if data.get("device_serial") != serial or serial != PRIMARY_SERIAL or data.get("package") != PKG:
        v.fail("FIXTURE_DEVICE_MISMATCH", "fixture device/package differs from fixed live target")
    if data.get("checked_via") != "adb-exec-out-run-as":
        v.fail("FIXTURE_METHOD", "expected explicit stock-device debug run-as inspection")
    if record.get("id") != QA_BOOK_ID or record.get("title") != book_title or record.get("filePath") != QA_BOOK_PATH:
        v.fail("FIXTURE_BOOK_BINDING", "book201 title/path differs from requested shelf book")
    if device_file.get("path") != QA_BOOK_PATH or device_file.get("sha256") != MOBY_SHA256:
        v.fail("FIXTURE_SHA_MISMATCH", "on-device QA201 must be the fixed Moby fixture")
    checked_sha = data.get("checked_apk_sha256")
    if not checked_sha or checked_sha != data.get("installed_apk", {}).get("sha256"):
        v.fail("FIXTURE_CHECKED_APK", "debug inspection APK identity is incomplete")
    try:
        raw_books = json.loads(raw["books"])
        if not isinstance(raw_books, dict) or not isinstance(raw_books.get("books"), list):
            raise ValueError("raw books must contain a books array")
        raw_records = raw_books["books"]
        if any(not isinstance(item, dict) for item in raw_records):
            raise ValueError("raw books array contains a non-object record")
        qa_records = [item for item in raw_records if item.get("id") == QA_BOOK_ID]
        if qa_records != [record]:
            raise ValueError("raw books do not bind the reported QA201 record")
        if raw["device_sha256"].split()[0] != MOBY_SHA256:
            raise ValueError("raw device SHA differs from fixed Moby SHA")
        if int(raw["device_size"].strip()) != device_file.get("size_bytes") or device_file.get("size_bytes") != 1165503:
            raise ValueError("raw fixture size differs from fixed Moby size")
        if raw["installed_apk"].split()[0] != checked_sha:
            raise ValueError("raw checked APK SHA differs from inspection APK")
        commands = json.loads(raw["binding_commands"])
        if (not isinstance(commands, list) or not commands or
                any(not isinstance(command, dict) or command.get("returncode") != 0 for command in commands)):
            raise ValueError("binding command log is empty or contains a failed/missing returncode")
    except (KeyError, ValueError, TypeError, IndexError) as exc:
        v.fail("FIXTURE_RAW_BINDING", str(exc))
    retained = data.get("retained_install", {})
    if (retained.get("method") != "adb-install-r" or retained.get("data_cleared") is not False or
            retained.get("exit_code") != 0 or "Success" not in str(retained.get("stdout", "")) or
            retained.get("from_apk_sha256") != checked_sha or
            retained.get("to_apk_sha256") != app.get("installed_sha256")):
        v.fail("FIXTURE_INSTALL_CHAIN", "successful retained install-r from checked debug APK to measured release is required")
    try:
        log_path = retained.get("log_path")
        if not isinstance(log_path, str) or not log_path:
            raise ValueError("raw install-r log path missing")
        with open(os.path.join(os.path.dirname(os.path.abspath(path)), log_path), "r", encoding="utf-8-sig") as handle:
            install_log = handle.read()
        if "Success" not in install_log:
            raise ValueError("raw install-r log has no Success")
        raw["install"] = install_log
    except (OSError, ValueError) as exc:
        v.fail("FIXTURE_INSTALL_LOG", str(exc))
    return {"path": os.path.abspath(path), "evidence": data, "raw": raw,
            "method": "debug run-as inspection + retained install-r; release sandbox unchanged"}


def build_preflight(apk_path: str, host_epub: str, app: Dict[str, Any],
                    book_title: str, v: Verdict, book_id: Optional[int] = None,
                    release_evidence: str = "", fixture_evidence: str = "",
                    serial: Optional[str] = None,
                    source: Optional[Dict[str, Any]] = None,
                    apk_sha256: Optional[str] = None) -> Dict[str, Any]:
    # The staged path already hashed the APK when it bound the run; reuse that
    # digest so a step never depends on re-reading an immutable binding.
    local_sha = apk_sha256 or sha256_file(apk_path)
    installed_sha = app.get("installed_sha256")
    evidence = load_release_evidence(release_evidence, v)
    host_sha = sha256_file(host_epub)
    out = {
        "package": PKG, "book_title": book_title, "book_id": book_id,
        "host_epub": os.path.abspath(host_epub) if host_epub else None,
        "host_epub_sha256": host_sha,
        "host_epub_bytes": os.path.getsize(host_epub) if host_sha else None,
        "expected_epub_sha256": MOBY_SHA256,
        "apk": os.path.abspath(apk_path) if apk_path else None,
        "apk_sha256": local_sha, "installed_apk_sha256": installed_sha,
        "installed_apk_path": app.get("apk_path"), "debuggable": app.get("debuggable"),
        "versionName": app.get("versionName"), "release_evidence": evidence,
        "release_evidence_path": release_evidence or None, "source": source or {},
    }
    if evidence:
        if evidence.get("build_type") != "release":
            v.fail("BENCHMARK_BUILD", "build_type must be release")
        if not re.fullmatch(r"[0-9a-fA-F]{64}", str(evidence.get("signer_sha256", ""))):
            v.fail("RELEASE_SIGNATURE_MISSING", "valid signer_sha256 is required")
        if evidence.get("apksigner_verified") is not True:
            v.fail("RELEASE_SIGNATURE_UNVERIFIED", "apksigner_verified must be true")
        if not local_sha or evidence.get("apk_sha256") != local_sha or evidence.get("apk_sha256") != installed_sha:
            v.fail("RELEASE_EVIDENCE_MISMATCH", "release evidence must bind local and installed APK SHA")
        if (not source or evidence.get("source_tree_algorithm") != SOURCE_TREE_ALGORITHM or
                evidence.get("source_tree_sha256") != source.get("source_tree_sha256") or
                evidence.get("git_head") != source.get("git_head")):
            v.fail("RELEASE_SOURCE_MISMATCH", "release evidence must bind the current source tree and HEAD")
    if host_sha != MOBY_SHA256:
        v.fail("EPUB_SHA_MISMATCH", "host fixture must match fixed Moby SHA256")
    if book_id != QA_BOOK_ID:
        v.fail("BOOK_ID_REQUIRED", "fixed QA Moby book_id=201 is required")
    if not book_title:
        v.fail("BOOK_TITLE_MISSING", "exact inspected shelf title is required")
    if not local_sha:
        v.fail("APK_NOT_FOUND", "local measured APK file is required")
    if not re.fullmatch(r"[0-9a-f]{64}", str(installed_sha or "")):
        v.fail("INSTALLED_SHA_MISSING", "installed base.apk SHA256 is missing")
    elif local_sha and local_sha != installed_sha:
        v.fail("APK_MISMATCH", "local APK SHA256 differs from installed base.apk")
    if app.get("debuggable") is True:
        v.fail("DEBUGGABLE_PACKAGE", "installed package is debuggable")
    elif app.get("debuggable") is not False:
        v.fail("DEBUGGABLE_UNKNOWN", "installed package debuggable flag is missing")
    out["fixture_evidence"] = validate_fixture_evidence(fixture_evidence, app, book_title, serial, v)
    return out

# ---------------------------------------------------------------------------
# device-side flow helpers (no adb import)

def _sleep(seconds: float) -> None:
    time.sleep(seconds)


# Presentation punctuation is not identity: Compose/EPUB producers mix ASCII
# quotes and dashes with typographic ones (LIV: The Town-Ho's Story vs ’).
_FOLD_PUNCT = str.maketrans({
    "\u2018": "'", "\u2019": "'", "\u201a": "'", "\u201b": "'", "\u2032": "'",
    "\u201c": '"', "\u201d": '"', "\u201e": '"', "\u201f": '"', "\u2033": '"',
    "\u2010": "-", "\u2011": "-", "\u2012": "-", "\u2013": "-", "\u2014": "-",
    "\u2015": "-", "\u2212": "-", "\u00a0": " ", "\u2007": " ", "\u2009": " ",
    "\u202f": " ", "\u200b": "", "\u00ad": "", "\ufeff": "",
})


def _fold_text(value: Any) -> str:
    """Canonical comparison form for on-device labels (never for evidence)."""
    text = unicodedata.normalize("NFKC", value if isinstance(value, str) else "")
    return " ".join(text.translate(_FOLD_PUNCT).split()).casefold()


def _node_text(node: Dict[str, Any]) -> str:
    return (node.get("text") or "") + " " + (node.get("desc") or "")


def _visible_rect(node: Dict[str, Any]) -> bool:
    rect = node.get("rect")
    if not rect or len(rect) != 4:
        return False
    # Compose LazyColumn exposes off-screen items with [0,0][0,0]. Those must
    # never be tapped, otherwise the tap silently lands at the screen corner.
    return rect[2] > rect[0] and rect[3] > rect[1]


def find_node(nodes: Sequence[Dict[str, Any]], needle: str) -> Optional[Dict[str, Any]]:
    needle = (needle or "").strip()
    if not needle:
        return None
    low = _fold_text(needle)
    for node in nodes:
        if low in _fold_text(_node_text(node)) and _visible_rect(node):
            return node
    return None


def find_node_exact(nodes: Sequence[Dict[str, Any]], needle: str) -> Optional[Dict[str, Any]]:
    needle = _fold_text(needle)
    if not needle:
        return None
    for node in nodes:
        if not _visible_rect(node):
            continue
        if _fold_text(node.get("text")) == needle:
            return node
        if _fold_text(node.get("desc")) == needle:
            return node
    return None


def tap_node(device: Any, node: Dict[str, Any]) -> None:
    x = (node["rect"][0] + node["rect"][2]) // 2
    y = (node["rect"][1] + node["rect"][3]) // 2
    device.sh("input", "tap", str(x), str(y))


def _swipe_list(device: Any, toward_end: bool) -> None:
    """Finger bottom->top scrolls toward later rows; top->bottom toward top."""
    x, y = device.screen_center()
    y1 = int(y * 1.55) if toward_end else int(y * 0.45)
    y2 = int(y * 0.45) if toward_end else int(y * 1.55)
    device.sh("input", "swipe", str(x), str(y1), str(x), str(y2), "280")
    _sleep(0.45)


def _list_signature(nodes: Sequence[Dict[str, Any]]) -> Tuple[Any, ...]:
    return tuple((_node_text(node), tuple(node["rect"])) for node in nodes
                 if _visible_rect(node) and (node.get("text") or node.get("desc")))


def _swipe_scrollable(device: Any, nodes: Sequence[Dict[str, Any]], toward_end: bool) -> None:
    """Keep both endpoints inside the actual list, away from sheet headers."""
    lists = [node for node in nodes if node.get("scrollable")
             and node.get("enabled") is not False and _visible_rect(node)]
    if len(lists) != 1:
        raise ValueError("TOC_SCROLL_CONTAINER: expected one visible scrollable list")
    left, top, right, bottom = lists[0]["rect"]
    x = (left + right) // 2
    inset = max(1, (bottom - top) // 5)
    high, low = top + inset, bottom - inset
    if high >= low:
        raise ValueError("TOC_SCROLL_CONTAINER: list bounds are too small")
    y1, y2 = (low, high) if toward_end else (high, low)
    device.sh("input", "swipe", str(x), str(y1), str(x), str(y2), "280")
    _sleep(0.45)


def reset_list_to_top(device: Any, max_swipes: int = 16,
                      nodes: Optional[List[Dict[str, Any]]] = None) -> List[Dict[str, Any]]:
    """Reset the fixed Moby TOC without pulling an already-top sheet down."""
    nodes = device.ui_dump() if nodes is None else nodes
    for _ in range(max_swipes):
        if find_node_exact(nodes, "Titlepage"):
            break
        before = _list_signature(nodes)
        _swipe_scrollable(device, nodes, toward_end=False)
        nodes = device.ui_dump()
        if _list_signature(nodes) == before:
            break
    return nodes


def scroll_to_node(device: Any, needle: str, max_swipes: int = 12,
                   reset_top: bool = False) -> Tuple[Optional[Dict[str, Any]], List[Dict[str, Any]]]:
    """Find a visible item before resetting; search within the list until stable."""
    nodes = device.ui_dump()
    found = find_node(nodes, needle)
    if found:
        return found, nodes
    if reset_top:
        nodes = reset_list_to_top(device, nodes=nodes)
        found = find_node(nodes, needle)
        if found:
            return found, nodes
    for _ in range(max_swipes):
        before = _list_signature(nodes)
        _swipe_scrollable(device, nodes, toward_end=True)
        nodes = device.ui_dump()
        found = find_node(nodes, needle)
        if found:
            return found, nodes
        if _list_signature(nodes) == before:
            break
    return None, nodes


def find_book_node(nodes: Sequence[Dict[str, Any]], book_title: str) -> Optional[Dict[str, Any]]:
    """Resolve one exact shelf identity to its own enabled clickable target.

    Compose can expose a semantic book label and a clickable cover as siblings.
    The retained UI parent indices bind those siblings; unrelated covers and
    substring title matches never qualify. Ambiguous visible cards fail closed.
    """
    expected = _fold_text(book_title)
    if not expected:
        return None

    def semantic_title(index: int) -> str:
        desc = (nodes[index].get("desc") or "").strip()
        match = re.match(r"^(?:继续阅读)?《([^》]+)》(?:[，,]|$)", desc)
        return _fold_text(match.group(1)) if match else ""

    def lineage(index: int) -> List[int]:
        result = [index]
        parent = nodes[index].get("parent")
        while type(parent) is int and 0 <= parent < result[-1]:
            result.append(parent)
            parent = nodes[parent].get("parent")
        return result

    paths = [lineage(index) for index in range(len(nodes))]
    semantic = [semantic_title(index) for index in range(len(nodes))]
    anchors = [index for index, title in enumerate(semantic)
               if title == expected and _visible_rect(nodes[index])]
    has_semantic_identity = bool(anchors)
    anchors = sorted(set(anchors) | {index for index, node in enumerate(nodes)
                                   if _fold_text(node.get("text")) == expected
                                   and _visible_rect(node)})
    targets = {}
    for anchor in anchors:
        if any(nodes[parent].get("enabled") is False for parent in paths[anchor]):
            raise ValueError("SHELF_BOOK_TARGET_NOT_CLICKABLE: " + book_title)
        target = None
        for scope in paths[anchor]:
            node = nodes[scope]
            if node.get("scrollable") or node.get("enabled") is False:
                break
            members = [index for index, path in enumerate(paths) if scope in path]
            if any(semantic[index] and semantic[index] != expected for index in members):
                break
            if node.get("clickable") is True and _visible_rect(node):
                target = scope
                break
            if not has_semantic_identity:
                # A plain title only establishes identity for its own ancestors.
                continue
            clickable = {index for index in members
                         if nodes[index].get("clickable") is True
                         and nodes[index].get("enabled") is not False
                         and not nodes[index].get("scrollable")
                         and _visible_rect(nodes[index])
                         and not any(nodes[parent].get("enabled") is False for parent in paths[index])
                         and (not _node_text(nodes[index]).strip()
                              or semantic[index] == expected
                              or _fold_text(nodes[index].get("text")) == expected)}
            # A nested clickable child of the same cover is not another card.
            outer = [index for index in clickable
                     if not any(parent in clickable for parent in paths[index][1:])]
            if len(outer) > 1:
                raise ValueError("SHELF_BOOK_TARGET_AMBIGUOUS: " + book_title)
            if outer:
                target = outer[0]
                break
        if target is None:
            raise ValueError("SHELF_BOOK_TARGET_NOT_CLICKABLE: " + book_title)
        targets[target] = nodes[target]
    if len(targets) > 1:
        raise ValueError("SHELF_BOOK_TARGET_AMBIGUOUS: " + book_title)
    return next(iter(targets.values()), None)


def scroll_to_book(device: Any, book_title: str, max_swipes: int = 12
                   ) -> Tuple[Optional[Dict[str, Any]], List[Dict[str, Any]]]:
    """Scroll only when no exact identity is visible; never scroll past ambiguity."""
    nodes = device.ui_dump()
    for attempt in range(max_swipes + 1):
        found = find_book_node(nodes, book_title)
        if found:
            return found, nodes
        if attempt < max_swipes:
            _swipe_list(device, toward_end=True)
            nodes = device.ui_dump()
    return None, nodes


def reader_visible(nodes: Sequence[Dict[str, Any]]) -> bool:
    # Compose reports the bottom pill children with zero bounds on this device,
    # so the page label text is the reliable reader signal, not a tappable rect.
    return progress_page(nodes) is not None or find_node(nodes, "目录") is not None


def progress_page(nodes: Sequence[Dict[str, Any]]) -> Optional[Tuple[int, int]]:
    for node in nodes:
        text = node.get("text") or ""
        m = re.search(r"·\s*([0-9]+)\s*/\s*([0-9]+)", text)
        if m:
            return int(m.group(1)), int(m.group(2))
    return None


def reader_nav_tap(device: Any, direction: str) -> None:
    """Tap the bottom-pill arrow by layout coordinate.

    The Compose bottom pill exposes its IconButtons with [0,0][0,0] bounds via
    uiautomator, so a node-rect tap would hit the screen corner. The pill itself
    is fixed at the bottom: left arrow ~16.4% width, right arrow ~69%, center at
    94% height. Page labels remain readable as text nodes and are used to verify
    every tap.
    """
    cx, cy = device.screen_center()
    w, h = cx * 2, cy * 2
    x = int(w * (0.69 if direction == "next" else 0.164))
    y = int(h * 0.94)
    device.sh("input", "tap", str(x), str(y))


def wait_for(device: Any, predicate: Any, timeout: float = 12.0,
             interval: float = 0.35) -> Any:
    end = time.time() + timeout
    last = None
    while time.time() < end:
        last = predicate(device.ui_dump())
        if last:
            return last
        _sleep(interval)
    return last


def show_chrome(device: Any) -> List[Dict[str, Any]]:
    nodes = device.ui_dump()
    if reader_visible(nodes):
        return nodes
    x, y = device.screen_center()
    device.sh("input", "tap", str(x), str(y))
    _sleep(0.35)
    return device.ui_dump()


def open_reader(device: Any, book_title: str, toc_match: str = "",
                target_page: int = 1) -> Tuple[bool, str, List[Dict[str, Any]]]:
    """Force-stop, open shelf book and preset chapter/page outside measurement.

    Returns (ok, detail, nodes); every failed preparation makes acceptance FAIL.
    """
    device.sh("am", "force-stop", PKG)
    _sleep(0.5)
    device.sh("am", "start", "-W", "-n", ACT)
    _sleep(1.5)
    nodes = device.ui_dump()
    book, nodes = scroll_to_book(device, book_title)
    if not book:
        # Try the shelf tab/all filter before giving up.
        all_node = find_node(nodes, "全部")
        if all_node:
            tap_node(device, all_node)
            _sleep(0.8)
            nodes = device.ui_dump()
            book, nodes = scroll_to_book(device, book_title)
    if not book:
        return False, "SHELF_BOOK_NOT_FOUND", nodes
    tap_node(device, book)
    _sleep(1.2)
    nodes = device.ui_dump()
    if not reader_visible(nodes):
        read = find_node(nodes, "阅读") or find_node(nodes, "开始阅读")
        if not read:
            return False, "READ_BUTTON_NOT_FOUND", nodes
        tap_node(device, read)
        _sleep(1.8)
        nodes = device.ui_dump()
    if not reader_visible(nodes):
        nodes = wait_for(device, lambda ns: ns if reader_visible(ns) else None, timeout=8.0)
    if not reader_visible(nodes):
        return False, "READER_NOT_OPEN", nodes
    # TOC is needed to select the expected spine chapter. The title is the
    # only stable UIA text; href is not exposed in the TOC row.
    show_chrome(device)
    toc = find_node(device.ui_dump(), "目录")
    if not toc:
        return False, "TOC_BUTTON_NOT_FOUND", device.ui_dump()
    tap_node(device, toc)
    _sleep(0.6)
    toc_nodes = device.ui_dump()
    match = None
    if toc_match:
        match, toc_nodes = scroll_to_node(device, toc_match, max_swipes=40, reset_top=True)
    if not match:
        # Conservative defaults; never guess a random chapter.
        for candidate in ("Chapter 1", "第 1 章", "chapter-1"):
            match, toc_nodes = scroll_to_node(device, candidate, max_swipes=8, reset_top=True)
            if match:
                break
    if not match:
        return False, "TOC_MATCH_NOT_FOUND", toc_nodes
    tap_node(device, match)
    _sleep(1.3)
    nodes = device.ui_dump()
    if not reader_visible(nodes):
        return False, "READER_NOT_OPEN_AFTER_TOC", nodes
    ok, detail = set_page(device, target_page)
    return ok, detail, device.ui_dump()


def set_page(device: Any, target_page_1based: int) -> Tuple[bool, str]:
    if target_page_1based < 1:
        return False, "INVALID_TARGET_PAGE"
    for _ in range(80):
        nodes = show_chrome(device)
        pos = progress_page(nodes)
        if not pos:
            return False, "PAGE_LABEL_NOT_FOUND"
        current, total = pos
        if target_page_1based > total:
            return False, "TARGET_PAGE_OUT_OF_RANGE"
        if current == target_page_1based:
            return True, "PAGE_OK"
        direction = "prev" if current > target_page_1based else "next"
        reader_nav_tap(device, direction)
        _sleep(0.18)
    return False, "PAGE_NAVIGATION_LIMIT"


def first_frame_baseline(device: Any) -> List[str]:
    return device.perf_lines()


def new_events(device: Any, before: Sequence[str]) -> List[PerfEvent]:
    after = device.perf_lines()
    return parse_perf_lines(select_new_lines(before, after))


class EventCapture:
    """Incremental cursor: retain early samples and malformed/raw lines as well."""
    def __init__(self, device: Any):
        self.device = device
        self.before = device.perf_lines()
        self.cursor = list(self.before)
        self.lines: List[str] = []
        self.events: List[PerfEvent] = []
        self.failures: List[Dict[str, str]] = []

    def poll(self) -> None:
        after = self.device.perf_lines()
        lines = select_new_lines(self.cursor, after)
        self.cursor = list(after)
        self.lines.extend(lines)
        for line in lines:
            try:
                ev = parse_perf_line(line)
                if ev is not None:
                    self.events.append(ev)
            except PerfParseError as exc:
                self.failures.append({"code": "EVENT_PARSE", "message": str(exc)})

    def wait(self, phase: str, count: int, timeout: float = 5.0) -> None:
        deadline = time.monotonic() + timeout
        while True:
            self.poll()
            if sum(ev.phase == phase for ev in self.events) >= count or time.monotonic() >= deadline:
                return
            _sleep(0.12)

    @classmethod
    def from_cursor(cls, device: Any, cursor: Sequence[str]) -> "EventCapture":
        """Resume the exact cursor a previous host call stopped at.

        A resumed segment must not re-collect lines it already retained, and it
        must not skip lines emitted while no host call was running.
        """
        capture = cls(device)
        capture.before = list(cursor)
        capture.cursor = list(cursor)
        return capture


class FrameCapture:
    """Read each turn before gfxinfo's finite per-window ring can wrap."""
    def __init__(self, device: Any):
        self.device = device
        self.raw: List[str] = []
        self.frames: Dict[Tuple[Optional[int], str, int, int], FrameStat] = {}
        self.failures: List[Dict[str, str]] = []

    def poll(self) -> None:
        self.absorb(self.device.framestats_text())

    def absorb(self, text: str) -> None:
        """Parse one framestats dump that was already read by the caller."""
        self.raw.append(text)
        try:
            for frame in parse_framestats(text):
                previous = self.frames.get(frame.key)
                if previous is not None and previous != frame:
                    raise PerfParseError("conflicting snapshots for frame %s" % (frame.key,))
                self.frames[frame.key] = frame
        except PerfParseError as exc:
            self.failures.append({"code": "FRAMESTATS_PARSE", "message": str(exc)})


def check_foreground(device: Any, result: Dict[str, Any]) -> bool:
    foreground = device.foreground_state()
    result.setdefault("foreground", []).append(foreground)
    if not re.search(r"\bapp\.particlebook/", foreground):
        result.setdefault("collection_failures", []).append({
            "code": "BACKGROUND_WINDOW", "message": "measured app is not foreground: " + foreground})
        return False
    return True


def finish_capture(device: Any, result: Dict[str, Any], logs: EventCapture,
                   frames: Optional[FrameCapture]) -> Dict[str, Any]:
    # This final read is essential: the last page's commit can arrive after its
    # progress label changed and after the immediate logcat read.
    _sleep(0.5)
    logs.poll()
    if frames is not None:
        frames.poll()
    check_foreground(device, result)
    result["pid_end"] = device.app_pid()
    if result.get("pid") != result["pid_end"]:
        result.setdefault("collection_failures", []).append({
            "code": "PID_CHANGED_DURING_RUN", "message": "app process changed during action window"})
    result.update(events=logs.events, raw_logcat=logs.lines,
                  raw_logcat_before=logs.before, raw_logcat_after=logs.cursor,
                  frames=list(frames.frames.values()) if frames is not None else [],
                  raw_framestats=frames.raw if frames is not None else [],
                  gfx_text=device.gfxinfo_report() if frames is not None else "")
    result.setdefault("collection_failures", []).extend(logs.failures)
    if frames is not None:
        result["collection_failures"].extend(frames.failures)
    return result


def run_first_entry(device: Any, book_title: str, toc_match: str,
                    expected_page: int, reset_gfx: bool = True) -> Dict[str, Any]:
    """One process-cold shelf click; OS/disk caches are deliberately retained."""
    result = {"ok": False, "detail": "FIRST_ENTRY", "collection_failures": [],
              "pid_before": device.app_pid(), "process_cold": True}
    device.sh("am", "force-stop", PKG)
    _sleep(0.5)
    result["pid_after_stop"] = device.app_pid()
    if result["pid_before"] is None:
        result["collection_failures"].append({"code": "PID_BEFORE_MISSING", "message": "preparation process PID missing"})
    if result["pid_after_stop"] is not None:
        result["collection_failures"].append({"code": "FORCE_STOP_FAILED", "message": "PID still exists after force-stop"})
    result["start_output"] = device.sh("am", "start", "-W", "-n", ACT)
    _sleep(1.5)
    result["pid"] = device.app_pid()
    if result["pid"] is None or result["pid"] == result["pid_before"]:
        result["collection_failures"].append({"code": "PID_NOT_CHANGED", "message": "fresh app process PID was not proven"})
    book, nodes = scroll_to_book(device, book_title)
    if not book:
        all_node = find_node(nodes, "全部")
        if all_node:
            tap_node(device, all_node)
            _sleep(0.8)
            book, nodes = scroll_to_book(device, book_title)
    if reset_gfx:
        result["gfx_reset_output"] = device.sh("dumpsys", "gfxinfo", PKG, "reset")
    logs = EventCapture(device)
    frames = FrameCapture(device) if reset_gfx else None
    if not book:
        result.update(detail="SHELF_BOOK_NOT_FOUND", nodes=nodes)
        return finish_capture(device, result, logs, frames)
    check_foreground(device, result)
    tap_node(device, book)
    _sleep(1.2)
    nodes = device.ui_dump()
    if not reader_visible(nodes):
        read = find_node_exact(nodes, "阅读") or find_node_exact(nodes, "开始阅读")
        if not read:
            result.update(detail="READ_BUTTON_NOT_FOUND", nodes=nodes)
            return finish_capture(device, result, logs, frames)
        tap_node(device, read)
    nodes = wait_for(device, lambda ns: ns if reader_visible(ns) else None, timeout=10.0)
    if not nodes:
        result.update(detail="READER_NOT_OPEN", nodes=[])
        return finish_capture(device, result, logs, frames)
    result["nodes"] = nodes
    pos = progress_page(nodes)
    result["observed_position"] = pos
    if not pos or pos[0] != expected_page + 1:
        result["collection_failures"].append({"code": "PAGE_RESTORED", "message": "reader did not restore the preset first page"})
    logs.wait(FIRST_PHASE, 1, timeout=8.0)
    result["ok"] = True
    return finish_capture(device, result, logs, frames)


def merge_verdict(dst: Verdict, src: Verdict) -> None:
    dst.pass_ = dst.pass_ and src.pass_
    dst.failures.extend(src.failures)
    dst.diagnostics.extend(src.diagnostics)


def prepare_progress(device: Any, book_title: str, toc_match: str,
                     target_page: int, v: Verdict) -> Dict[str, Any]:
    """UI Back awaits the reader exit/flush path before any process force-stop."""
    ok, detail, nodes = open_reader(device, book_title, toc_match, target_page)
    result = {"ok": ok, "detail": detail, "nodes": nodes}
    if not ok:
        v.fail("PROGRESS_PREP_FAILED", detail)
        return result
    device.sh("input", "keyevent", "4")
    exited = wait_for(device, lambda ns: not reader_visible(ns), timeout=8.0)
    result["reader_exited"] = bool(exited)
    result["exit_nodes"] = device.ui_dump()
    if not exited:
        result["ok"] = False
        v.fail("PROGRESS_FLUSH_EXIT", "UI Back did not complete the reader exit/flush path")
    _sleep(0.5)
    return result


def run_page_turn_round(device: Any, book_title: str, toc_match: str,
                        expected_chapter: int, start_page: int,
                        turns: int, turn_mode: str = "forward") -> Dict[str, Any]:
    result = {"ok": False, "detail": "TURN_ROUND", "events": [], "frames": [],
              "collection_failures": [], "start_page": start_page}
    if start_page != 1 or turn_mode != "forward":
        result["detail"] = "START_PAGE_OR_MODE: require page1 and forward new pages"
        return result
    prep_v = Verdict()
    prep = prepare_progress(device, book_title, toc_match, start_page, prep_v)
    result["preparation"] = prep
    result["collection_failures"].extend(prep_v.failures)
    if not prep["ok"]:
        result["detail"] = "PROGRESS_PREP_FAILED"
        return result
    # Reopen after preparation so any set_page user turns cannot contaminate
    # turn numbering: the measured tracker must start with turn1.
    entry = run_first_entry(device, book_title, toc_match, start_page - 1, reset_gfx=False)
    result["entry"] = attempt_evidence(entry)
    result["collection_failures"].extend(entry.get("collection_failures", []))
    if not entry.get("ok"):
        result["detail"] = entry.get("detail")
        return result
    first_v = validate_first_chapter(entry["events"], expected_chapter, 0,
                                     expected_book_id=QA_BOOK_ID, min_samples=1,
                                     require_budget=False)
    result["collection_failures"].extend(first_v.failures)
    if any(ev.phase == PAGE_PHASE for ev in entry["events"]):
        result["collection_failures"].append({"code": "PRESET_TURN_POLLUTION", "message": "fresh reader already emitted page-turn events"})
    result["pid"] = device.app_pid()
    nodes = show_chrome(device)
    pos = progress_page(nodes)
    if not pos or pos[0] != start_page:
        result["detail"] = "PAGE_LABEL_NOT_AT_START"
        return result
    current, total = pos
    result["page_count"] = total
    if current + turns > total:
        result["detail"] = "PAGE_COUNT_INSUFFICIENT: %d pages from page%d require %d forward new pages" % (total, current, turns)
        return result
    _sleep(0.5)
    result["gfx_reset_output"] = device.sh("dumpsys", "gfxinfo", PKG, "reset")
    logs = EventCapture(device)
    frames = FrameCapture(device)
    for turn in range(1, turns + 1):
        if not check_foreground(device, result):
            result["detail"] = "BACKGROUND_WINDOW"
            break
        reader_nav_tap(device, "next")
        expected = current + 1
        changed = wait_for(device, lambda ns: progress_page(ns) if
                           progress_page(ns) and progress_page(ns)[0] == expected else None,
                           timeout=3.0, interval=0.12)
        logs.wait(PAGE_PHASE, turn)
        frames.poll()
        if not changed:
            result["detail"] = "PAGE_DID_NOT_CHANGE"
            break
        current = changed[0]
    else:
        result["ok"] = True
    result["end_page"] = current
    return finish_capture(device, result, logs, frames)


def attempt_evidence(attempt: Dict[str, Any]) -> Dict[str, Any]:
    """Serialize all evidence including failed runs; never drop bad samples."""
    return {key: ([event.raw for event in value] if key == "events" else
                  [dict(vars(frame)) for frame in value] if key == "frames" else value)
            for key, value in attempt.items()}


def validate_run_config(cmd: str, args: Dict[str, Any], v: Verdict) -> None:
    chapter = 6 if cmd == "epub" else 59
    if args.get("chapter") != chapter:
        v.fail("FIXED_CHAPTER", "acceptance requires Moby spine %d" % chapter)
    if args.get("page", 1) != 1:
        v.fail("START_PAGE_NOT_ONE", "acceptance starts at chapter page1; preset user turns pollute numbering")
    if cmd == "epub" and args.get("cold", MIN_COLD_SAMPLES) != MIN_COLD_SAMPLES:
        v.fail("COLD_RUN_COUNT", "retain exactly 5 process-cold attempts")
    if cmd == "epub-turn":
        if args.get("turns", MIN_TURNS) != MIN_TURNS or args.get("rounds", MIN_ROUNDS) != MIN_ROUNDS:
            v.fail("TURN_RUN_COUNT", "retain all 20 turns x 3 rounds")
        if args.get("turn_mode", "forward") != "forward":
            v.fail("TURN_MODE", "acceptance requires forward new pages")


def run_context(device: Any, args: Dict[str, Any], v: Verdict) -> Tuple[Dict[str, Any], Dict[str, Any], Dict[str, Any]]:
    app = device.app_info()
    info = device.device_info()
    try:
        source = source_tree_info()
    except (OSError, subprocess.SubprocessError) as exc:
        source = {"error": str(exc)}
        v.fail("SOURCE_TREE_UNAVAILABLE", str(exc))
    preflight = build_preflight(args.get("apk", ""), args.get("epub", ""), app,
                                args.get("book_title", ""), v, book_id=args.get("book_id"),
                                release_evidence=args.get("release_evidence", ""),
                                fixture_evidence=args.get("fixture_evidence", ""),
                                serial=info.get("serial"), source=source)
    if info.get("serial") != PRIMARY_SERIAL or info.get("model") != "Pixel 3 XL":
        v.fail("PRIMARY_DEVICE", "P1 acceptance requires Pixel3XL serial " + PRIMARY_SERIAL)
    if not info.get("refresh_rate_hz"):
        v.fail("REFRESH_RATE_MISSING", "active display refresh rate missing")
    return app, info, preflight


def guard_collection(operation: Any, device: Any, *args: Any, **kwargs: Any) -> Dict[str, Any]:
    """Keep each failed attempt in place even if UI/transport collection raises."""
    command_start = len(getattr(device, "command_log", []))
    try:
        result = operation(device, *args, **kwargs)
    except (OSError, ValueError, subprocess.SubprocessError) as exc:
        result = {"ok": False, "detail": "COLLECTION_ERROR: " + str(exc),
                  "events": [], "frames": [], "collection_failures": [
                      {"code": "COLLECTION_ERROR", "message": str(exc)}]}
    result["raw_commands"] = list(getattr(device, "command_log", []))[command_start:]
    return result


def record_attempt_failures(attempt: Dict[str, Any], v: Verdict, label: str) -> None:
    for failure in attempt.get("collection_failures", []):
        v.fail(failure["code"], label + ": " + failure["message"])
    if not attempt.get("ok"):
        v.fail("ATTEMPT_FAILED", label + ": " + str(attempt.get("detail")))
    check_event_pid(attempt.get("events", []), attempt.get("pid"), v)


def png_evidence(path: str) -> Dict[str, Any]:
    """Validate a complete PNG container and retain its exact file digest."""
    with open(path, "rb") as handle:
        data = handle.read()
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise ValueError("screenshot has no PNG signature")
    offset, chunks, width, height = 8, [], 0, 0
    while offset + 12 <= len(data):
        size = int.from_bytes(data[offset:offset + 4], "big")
        kind = data[offset + 4:offset + 8]
        end = offset + size + 12
        if end > len(data):
            raise ValueError("screenshot has a truncated PNG chunk")
        payload = data[offset + 8:end - 4]
        crc = int.from_bytes(data[end - 4:end], "big")
        if zlib.crc32(kind + payload) & 0xffffffff != crc:
            raise ValueError("screenshot has an invalid PNG CRC")
        if not chunks:
            if kind != b"IHDR" or size != 13:
                raise ValueError("screenshot has no valid PNG IHDR")
            width = int.from_bytes(payload[:4], "big")
            height = int.from_bytes(payload[4:8], "big")
        chunks.append(kind)
        offset = end
        if kind == b"IEND":
            if size != 0 or offset != len(data):
                raise ValueError("screenshot has an invalid PNG end")
            break
    if not width or not height or b"IDAT" not in chunks or not chunks or chunks[-1] != b"IEND":
        raise ValueError("screenshot is an incomplete PNG")
    return {"path": os.path.abspath(path), "sha256": hashlib.sha256(data).hexdigest(),
            "size_bytes": len(data), "width": width, "height": height}


def capture_cold_screenshot(device: Any, attempt: Dict[str, Any], args: Dict[str, Any],
                            app: Dict[str, Any], info: Dict[str, Any],
                            preflight: Dict[str, Any], index: int) -> None:
    """Capture after finish_capture, preserving timing evidence even on failure."""
    screenshot = {"ok": False, "run_index": index, "pid": attempt.get("pid"),
                  "apk_sha256": app.get("installed_sha256"), "device_serial": info.get("serial"),
                  "fixture_sha256": preflight.get("host_epub_sha256"),
                  "book_id": QA_BOOK_ID, "chapter": 6, "page": 0,
                  "outside_measurement_window": True, "text_verified": None,
                  "expected_text_for_manual_review": "Call me Ishmael.",
                  "event_end_ns": [event.end_ns for event in attempt.get("events", [])
                                   if event.phase == FIRST_PHASE]}
    attempt["body_screenshot"] = screenshot
    command_start = len(getattr(device, "command_log", []))
    try:
        output = args.get("json_out") or ""
        directory = args.get("screenshot_dir") or os.path.dirname(output) or "."
        if not output and not args.get("screenshot_dir"):
            raise ValueError("screenshot needs json_out or screenshot_dir")
        stem = os.path.splitext(os.path.basename(output))[0] if output else "epub03"
        path = os.path.abspath(os.path.join(directory, "%s-cold%02d.png" % (stem, index)))
        screenshot["path"] = path
        capture = getattr(device, "screenshot_png", None)
        if not callable(capture):
            raise ValueError("device has no screenshot_png adapter")
        screenshot["pid_before"] = device.app_pid()
        screenshot["foreground_before"] = device.foreground_state()
        capture(path)
        screenshot.update(png_evidence(path))
        screenshot["pid_after"] = device.app_pid()
        screenshot["foreground_after"] = device.foreground_state()
        if not screenshot["pid"] or any(screenshot[key] != screenshot["pid"]
                                        for key in ("pid_before", "pid_after")):
            raise ValueError("screenshot PID differs from the measured run")
        if any(PKG not in screenshot[key] for key in ("foreground_before", "foreground_after")):
            raise ValueError("screenshot reader is not foreground")
        screenshot["ok"] = True
    except (OSError, ValueError, subprocess.SubprocessError) as exc:
        screenshot["error"] = str(exc)
        attempt.setdefault("collection_failures", []).append(
            {"code": "BODY_SCREENSHOT_FAILED", "message": str(exc)})
    finally:
        attempt.setdefault("raw_commands", []).extend(
            list(getattr(device, "command_log", []))[command_start:])


def epub_cold_report(attempt: Dict[str, Any], index: int, run_v: Verdict,
                     seen_pids: set, samples: List[float], frame_samples: List[float],
                     preparation: Dict[str, Any]) -> Dict[str, Any]:
    """Validate one retained cold attempt; a failed attempt is never dropped."""
    events = attempt.get("events", [])
    first = [event for event in events if event.phase == FIRST_PHASE]
    samples.extend(event.ms for event in first)
    record_attempt_failures(attempt, run_v, "cold%d" % (index + 1))
    if len(first) != 1 or any(event.phase == PAGE_PHASE for event in events):
        run_v.fail("FIRST_EVENT_COUNT", "exactly one first event and no turn events are required per cold attempt")
    merge_verdict(run_v, validate_first_chapter(events, 6, 0, QA_BOOK_ID, min_samples=1))
    pid = attempt.get("pid")
    if pid in seen_pids:
        run_v.fail("COLD_PID_REUSED", "process PID reused across cold samples")
    if pid is not None:
        seen_pids.add(pid)
    matched = validate_frames(events, attempt.get("frames", []), run_v)
    gfx = parse_gfxinfo_summary(attempt.get("gfx_text", ""))
    if not gfx.get("total_frames"):
        run_v.fail("NO_GFX_FRAMES", "cold attempt has no raw gfxinfo summary")
    elif gfx["total_frames"] != len(attempt.get("frames", [])):
        run_v.fail("FRAMESTATS_COUNT", "cold attempt raw rows do not equal reset frame count")
    if gfx.get("janky_percent") is None:
        run_v.fail("NO_JANKY_SUMMARY", "cold attempt has no raw janky summary")
    if any(not frame.window or PKG not in frame.window for frame in attempt.get("frames", [])):
        run_v.fail("FRAME_WINDOW_MISSING", "cold framestats app window identity missing")
    if any(frame.pid != pid for frame in attempt.get("frames", [])):
        run_v.fail("FRAME_PID_MISMATCH", "first-frame stats do not match measured process")
    frame_samples.extend(item["frame_duration_ms"] for item in matched)
    report = attempt_evidence(attempt)
    report.update(index=index + 1, preparation=preparation, matched_frames=matched, gfx=gfx,
                  **run_v.as_dict())
    return report


def build_epub_report(args: Dict[str, Any], preflight: Dict[str, Any], app: Dict[str, Any],
                      info: Dict[str, Any], v: Verdict, runs: List[Dict[str, Any]],
                      all_events: Sequence[PerfEvent], samples: Sequence[float],
                      frame_samples: Sequence[float]) -> Dict[str, Any]:
    """Assemble the 03 report; shared by the single-process and staged paths."""
    book = args.get("book_title", "")
    toc = args.get("toc_match") or "I: Loomings"
    if len(runs) != MIN_COLD_SAMPLES:
        v.fail("COLD_ATTEMPTS_INCOMPLETE", "%d/5 attempts retained" % len(runs))
    if runs:
        merge_verdict(v, validate_first_chapter(all_events, 6, 0, QA_BOOK_ID, MIN_COLD_SAMPLES))
    summary = summarize_ms(samples)
    extra = {
        "fixture": {"chapter_0based": 6, "page_1based": 1, "book_id": QA_BOOK_ID,
                    "book_title": book, "toc_match": toc},
        "cold_kind": "process-cold", "runs": runs,
        "samples": summary, "first_frame_samples": summary,
        "frame_duration": summarize_ms(frame_samples), "events": [event.raw for event in all_events],
        "notes": ["All five attempts retained in execution order, including invalid/slow samples.",
                  "No disk-cache clearing, no warm-up exclusion and no best-of selection."],
    }
    return make_run_report("epub", args, preflight, app, info, v, extra)


def turn_round_report(attempt: Dict[str, Any], index: int, info: Dict[str, Any],
                      run_v: Verdict, frame_samples: List[float]) -> Tuple[Dict[str, Any], Dict[str, Any]]:
    """Validate one retained 20-turn round; returns the report row and gfx summary."""
    events = attempt.get("events", [])
    record_attempt_failures(attempt, run_v, "round%d" % (index + 1))
    merge_verdict(run_v, validate_page_events(events, 59, QA_BOOK_ID, MIN_TURNS, start_page=1))
    if any(event.phase == FIRST_PHASE for event in events):
        run_v.fail("PRESET_EVENT_POLLUTION", "new reader entry appeared inside turn-only collection")
    gfx = evaluate_gfxinfo(attempt.get("gfx_text", ""), attempt.get("frames", []), events,
                           run_v, refresh_rate_hz=info.get("refresh_rate_hz"),
                           expected_pid=attempt.get("pid"))
    frame_samples.extend(gfx["frame_duration"]["samples_ms"])
    report = attempt_evidence(attempt)
    report.update(round=index + 1, gfx=gfx, frame_duration=gfx["frame_duration"], **run_v.as_dict())
    return report, gfx


def build_turn_report(args: Dict[str, Any], preflight: Dict[str, Any], app: Dict[str, Any],
                      info: Dict[str, Any], v: Verdict, rounds: List[Dict[str, Any]],
                      all_events: Sequence[PerfEvent], frame_samples: Sequence[float],
                      total_window_frames: int, total_janky: int) -> Dict[str, Any]:
    """Assemble the 04 report; shared by the single-process and staged paths."""
    book = args.get("book_title", "")
    toc = args.get("toc_match") or "LIV: The Town-Ho"
    if len(rounds) != MIN_ROUNDS:
        v.fail("TURN_ROUNDS_INCOMPLETE", "%d/3 rounds retained" % len(rounds))
    commit_ms = [event.ms for event in all_events if event.phase == PAGE_PHASE]
    summary = summarize_ms(frame_samples)
    input_summary = summarize_ms(commit_ms)
    jank = 100.0 * total_janky / total_window_frames if total_window_frames else None
    extra = {
        "fixture": {"chapter_0based": 59, "page_1based": 1, "book_id": QA_BOOK_ID,
                    "book_title": book, "toc_match": toc, "turn_mode": "forward"},
        "rounds": rounds, "samples": summary, "frame_duration": summary,
        "turn_samples": input_summary, "input_commit": input_summary,
        "janky_percent": jank, "janky_frames": total_janky,
        "window_frame_count": total_window_frames, "events": [event.raw for event in all_events],
        "notes": ["20 distinct forward new pages per round, all 3 rounds retained.",
                  "FrameCompleted-IntendedVsync in floating milliseconds for all app frames overlapping action windows.",
                  "Raw reset summary and idle frames retained; idle time excluded from percentile and window jank."],
    }
    return make_run_report("epub-turn", args, preflight, app, info, v, extra)


def run_epub(device: Any, args: Dict[str, Any]) -> Dict[str, Any]:
    v = Verdict()
    validate_run_config("epub", args, v)
    app, info, preflight = run_context(device, args, v)
    runs: List[Dict[str, Any]] = []
    all_events: List[PerfEvent] = []
    samples: List[float] = []
    frame_samples: List[float] = []
    seen_pids: set = set()
    book = args.get("book_title", "")
    toc = args.get("toc_match") or "I: Loomings"
    # Invalid provenance/configuration returns a complete FAIL report before
    # interacting with book progress or collecting a misleading benchmark.
    if v.pass_:
        for index in range(MIN_COLD_SAMPLES):
            run_v = Verdict()
            prep = guard_collection(prepare_progress, device, book, toc, 1, run_v)
            if prep["ok"]:
                attempt = guard_collection(run_first_entry, device, book, toc, 0)
            else:
                attempt = {"ok": False, "detail": "PROGRESS_PREP_FAILED", "events": [], "frames": []}
            capture_cold_screenshot(device, attempt, args, app, info, preflight, index + 1)
            all_events.extend(attempt.get("events", []))
            runs.append(epub_cold_report(attempt, index, run_v, seen_pids, samples,
                                         frame_samples, prep))
            merge_verdict(v, run_v)
            print("epub process-cold %d/5: %s" % (index + 1, run_v.label))
    return build_epub_report(args, preflight, app, info, v, runs, all_events, samples, frame_samples)


def run_epub_turns(device: Any, args: Dict[str, Any]) -> Dict[str, Any]:
    v = Verdict()
    validate_run_config("epub-turn", args, v)
    app, info, preflight = run_context(device, args, v)
    book = args.get("book_title", "")
    toc = args.get("toc_match") or "LIV: The Town-Ho"
    rounds: List[Dict[str, Any]] = []
    all_events: List[PerfEvent] = []
    frame_samples: List[float] = []
    total_janky = 0
    total_window_frames = 0
    if v.pass_:
        for index in range(MIN_ROUNDS):
            attempt = guard_collection(run_page_turn_round, device, book, toc, 59, 1, MIN_TURNS)
            run_v = Verdict()
            all_events.extend(attempt.get("events", []))
            report, gfx = turn_round_report(attempt, index, info, run_v, frame_samples)
            total_window_frames += gfx["total_frames"]
            total_janky += gfx.get("janky_frames") or 0
            rounds.append(report)
            merge_verdict(v, run_v)
            print("epub-turn round %d/3: %s" % (index + 1, run_v.label))
    return build_turn_report(args, preflight, app, info, v, rounds, all_events, frame_samples,
                             total_window_frames, total_janky)


def make_run_report(cmd: str, args: Dict[str, Any], preflight: Dict[str, Any],
                    app: Dict[str, Any], device: Dict[str, Any],
                    v: Verdict, extra: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    out = {
        "cmd": cmd, "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "args": args, "preflight": preflight, "device": device, "app": app,
        "apk_sha256": preflight.get("apk_sha256"),
        "thresholds": {"first_frame_p95_ms": FIRST_FRAME_P95_BUDGET_MS,
                       "frame_duration_p95_ms": TURN_P95_BUDGET_MS, "janky_percent": 2.0},
    }
    if extra:
        out.update(extra)
    samples = out.get("samples", {})
    out.update(p50_ms=samples.get("p50_ms"), p95_ms=samples.get("p95_ms"))
    out.update(v.as_dict())
    return out

# ---------------------------------------------------------------------------
# Staged collection (one host tool call == one bounded segment).
#
# A full 03/04 run costs far more than the 120 s device-segment budget, so the
# staged path splits it: init binds inputs and the frozen source, each step
# collects exactly one cold entry (03) or one 20-turn round (04) while every adb
# command is fsynced to disk before it is dispatched, and finish replays the
# retained evidence through the same graders as the single-process path.
# Validation, budgets and thresholds are shared with run_epub/run_epub_turns; the
# staged path only changes where the evidence lives between host calls.
# ---------------------------------------------------------------------------

SEGMENT_SCHEMA = "pbepub-run-v1"
SEGMENT_POLICY = {"adb_seconds": 30, "screenshot_seconds": 30, "segment_seconds": 120,
                  "deadline_guard_seconds": 4, "unit_reserve_seconds": 20}
GESTURE_VERBS = ("tap", "swipe", "keyevent", "text", "roll", "draganddrop")
FIXTURE_RAW_KEYS = ("binding_commands", "device_sha256", "device_size", "books", "installed_apk")
SOURCE_FIELDS = ("git_head", "source_tree_algorithm", "source_tree_sha256", "source_files")
UNIT_TOTAL = {"epub": MIN_COLD_SAMPLES, "epub-turn": MIN_ROUNDS}

# Mid-round resume (04). One 20-turn round costs far more than the 120 s host
# call budget, so the round is journalled and continued inside the same app
# process. Thresholds, adb/segment budgets and graders are unchanged; only the
# boundary between "one host call" and "one measured round" moves. Every reserve
# below is a *pre-dispatch* check: a segment pauses instead of starting an
# action it cannot finish, and it never pauses between a tap and its frame read.
ROUND_SCHEMA = "pbepub-round-v1"
ROUND_SEGMENT_SCHEMA = "pbepub-round-segment-v1"
ROUND_TURN_RESERVE_SECONDS = 16.0
ROUND_TURN_COST_SLACK = 1.5
ROUND_STAGE_RESERVE_SECONDS = {"prepare": 45.0, "entry": 20.0, "turns": 16.0,
                               "finish": 32.0, "verify": 24.0}


class EvidenceError(RuntimeError):
    """Retained evidence is missing, malformed or contradicting itself."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


class TransportError(RuntimeError):
    """A device command or the segment budget failed; retry only after observing."""

    def __init__(self, code: str, message: str, retryable: bool = False,
                 command_id: Optional[str] = None) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.retryable = retryable
        self.command_id = command_id


def digest_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def dump_bytes(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, allow_nan=False,
                       default=str) + "\n").encode("utf-8")


def _strict_json(data: bytes) -> Any:
    def reject(value: str) -> None:
        raise ValueError("nonfinite JSON constant: " + value)
    return json.loads(data.decode("utf-8"), parse_constant=reject)


def _fsync_directory(path: Path) -> None:
    if os.name == "nt":
        return
    handle = os.open(str(path), os.O_RDONLY)
    try:
        os.fsync(handle)
    finally:
        os.close(handle)


class RunStore:
    """Append-only run directory. Every file is written once and fsynced."""

    def __init__(self, root: Any, create: bool = False, fault: Any = None) -> None:
        self.root = Path(root).resolve()
        self.fault = fault
        if create:
            self.root.mkdir(parents=True, exist_ok=True)
        elif not self.root.is_dir():
            raise EvidenceError("RUN_DIR_MISSING", "run directory not found: %s" % self.root)
        self._writers = 0

    @contextlib.contextmanager
    def writer(self):
        self._writers += 1
        try:
            yield self
        finally:
            self._writers -= 1

    def path(self, relative: str) -> Path:
        candidate = (self.root / relative).resolve()
        try:
            candidate.relative_to(self.root)
        except ValueError:
            raise EvidenceError("PATH_ESCAPE", "path leaves the run directory: " + str(relative))
        return candidate

    def put(self, relative: str, value: Any, exclusive: bool = False) -> Dict[str, Any]:
        data = value if isinstance(value, bytes) else dump_bytes(value)
        path = self.path(relative)
        path.parent.mkdir(parents=True, exist_ok=True)
        if exclusive and path.exists():
            raise EvidenceError("EVIDENCE_EXISTS", "refusing to overwrite " + str(relative))
        temp = path.with_name(path.name + ".tmp-" + uuid.uuid4().hex)
        handle = open(temp, "xb")
        try:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        finally:
            handle.close()
        if self.fault is not None:
            self.fault("after_publish", temp)
        os.replace(temp, path)
        _fsync_directory(path.parent)
        return {"path": str(relative), "algorithm": "sha256", "bytes": len(data),
                "sha256": digest_bytes(data)}

    def ref(self, relative: str) -> Dict[str, Any]:
        path = self.path(relative)
        try:
            data = path.read_bytes()
        except FileNotFoundError:
            raise EvidenceError("RAW_EVIDENCE_MISSING", "missing file: " + str(relative))
        return {"path": str(relative), "algorithm": "sha256", "bytes": len(data),
                "sha256": digest_bytes(data)}

    def get(self, ref: Dict[str, Any]) -> bytes:
        if not isinstance(ref, dict) or not isinstance(ref.get("path"), str):
            raise EvidenceError("EVIDENCE_REF", "malformed evidence reference")
        data = self.path(ref["path"]).read_bytes() if self.path(ref["path"]).is_file() else None
        if data is None:
            raise EvidenceError("RAW_EVIDENCE_MISSING", "missing file: " + ref["path"])
        algorithm = ref.get("algorithm", "sha256")
        if algorithm != "sha256" or len(data) != ref.get("bytes") or digest_bytes(data) != ref.get("sha256"):
            raise EvidenceError("EVIDENCE_DRIFT", "evidence changed on disk: " + ref["path"])
        return data

    def json(self, relative: str) -> Any:
        return _strict_json(self.get(self.ref(relative)))

    def manifest(self) -> Tuple[Dict[str, Any], str]:
        try:
            data = self.path("manifest.json").read_bytes()
        except FileNotFoundError:
            raise EvidenceError("MANIFEST_MISSING", "manifest.json is missing")
        manifest = _strict_json(data)
        if manifest.get("schema") != SEGMENT_SCHEMA:
            raise EvidenceError("MANIFEST_SCHEMA", "run manifest schema mismatch")
        if manifest.get("policy") != SEGMENT_POLICY:
            raise EvidenceError("MANIFEST_POLICY", "segment budget policy changed")
        for key, ref in (manifest.get("bindings") or {}).items():
            self.get(ref)
        for key, ref in (manifest.get("snapshots") or {}).items():
            self.get(ref)
        return manifest, digest_bytes(data)


class SegmentDeadline:
    """One bounded host call. Raises instead of starting work that cannot finish."""

    def __init__(self, seconds: float = SEGMENT_POLICY["segment_seconds"],
                 clock: Any = time.monotonic_ns, sleep: Any = time.sleep) -> None:
        self.clock = clock
        self.sleep = sleep
        self.start_ns = clock()
        self.end_ns = self.start_ns + int(seconds * 1_000_000_000)

    def remaining(self) -> float:
        return (self.end_ns - self.clock()) / 1e9

    def timeout(self, limit: float = SEGMENT_POLICY["adb_seconds"]) -> float:
        remaining = self.remaining() - SEGMENT_POLICY["deadline_guard_seconds"]
        if remaining <= 0:
            raise TransportError("SEGMENT_DEADLINE", "segment budget exhausted before dispatch")
        return min(float(limit), remaining)

    def wait(self, seconds: float) -> Dict[str, int]:
        if self.remaining() - SEGMENT_POLICY["deadline_guard_seconds"] < seconds:
            raise TransportError("SEGMENT_DEADLINE", "insufficient bounded wait time")
        start = self.clock()
        self.sleep(seconds)
        return {"start_ns": start, "end_ns": self.clock()}


def command_tail(argv: Sequence[Any]) -> List[str]:
    parts = [str(item) for item in list(argv)[1:]]
    if parts[:1] == ["-s"]:
        parts = parts[2:]
    if parts[:1] in (["shell"], ["exec-out"]):
        parts = parts[1:]
    return parts


def command_class(argv: Sequence[Any]) -> str:
    tail = command_tail(argv)
    if not tail:
        return "other"
    if tail[0] == "logcat":
        return "logcat"
    if tail[0] == "dumpsys" and "framestats" in tail:
        return "framestats"
    if tail[0] == "dumpsys" and "gfxinfo" in tail:
        return "gfxinfo"
    if tail[0] == "uiautomator":
        return "source"
    if tail[0] in ("scan", "idx", "search", "sel", "t", "id", "ph"):
        return "cas"
    return "source"


def gesture_command(argv: Sequence[Any]) -> bool:
    tail = command_tail(argv)
    return len(tail) >= 2 and tail[0] == "input" and tail[1] in GESTURE_VERBS


def parse_ui_dump(text: str) -> List[Dict[str, Any]]:
    end = (text or "").rfind("</hierarchy>")
    if end < 0:
        return []
    try:
        root = ET.fromstring(text[:end + len("</hierarchy>")])
    except ET.ParseError:
        return []
    elements = list(root.iter("node"))
    indices = {element: index for index, element in enumerate(elements)}
    parents = {child: parent for parent in root.iter() for child in parent}
    nodes = []
    for node in elements:
        match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds") or "")
        nodes.append({"text": (node.get("text") or "").strip(),
                      "desc": (node.get("content-desc") or "").strip(),
                      "cls": node.get("class") or "",
                      "clickable": node.get("clickable") == "true",
                      "enabled": node.get("enabled") != "false",
                      "scrollable": node.get("scrollable") == "true",
                      "parent": indices.get(parents.get(node)),
                      "rect": [int(value) for value in match.groups()] if match else None})
    return nodes


class RunDevice:
    """Journaling adb adapter: planned -> dispatching -> raw bytes -> receipt."""

    def __init__(self, store: RunStore, state: Dict[str, Any], manifest: Dict[str, Any],
                 deadline: SegmentDeadline, unit: int, attempt: int,
                 runner: Any = subprocess.run, dump_parser: Any = None,
                 start_index: int = 0) -> None:
        self.store = store
        self.state = state
        self.manifest = manifest
        self.deadline = deadline
        self.unit = unit
        self.attempt = attempt
        self.runner = runner
        self.dump_parser = dump_parser
        self.serial = manifest.get("serial") or ""
        # A resumed attempt continues the command sequence where the previous
        # host call stopped, so command ids stay unique inside the attempt and
        # the finish-time audit sees one continuous 000001..N run.
        self.count = int(start_index)
        self.phase = "unit"
        self.command_log: List[Dict[str, Any]] = []
        self.command_failures: List[Dict[str, Any]] = []
        self.last_receipt: Optional[Dict[str, Any]] = None

    def call(self, *args: Any, timeout: Optional[float] = None, binary: bool = False,
             absent: bool = False, sink: Optional[str] = None) -> Any:
        limit = SEGMENT_POLICY["adb_seconds"] if timeout is None else min(float(timeout), SEGMENT_POLICY["adb_seconds"])
        seconds = self.deadline.timeout(limit)
        argv = [self.manifest.get("adb") or "adb"]
        if self.serial:
            argv += ["-s", self.serial]
        argv += [str(item) for item in args]
        prefix = "commands/%03d/%02d/%06d" % (self.unit, self.attempt, self.count + 1)
        if self.store.path(prefix + ".planned.json").is_file():
            # Never overwrite a command that a previous segment already sent.
            raise TransportError("COMMAND_SLOT_TAKEN",
                                 "a command is already journalled at " + prefix,
                                 retryable=False, command_id=prefix)
        self.count += 1
        planned = {"command_id": prefix, "argv": argv, "phase": self.phase, "unit": self.unit,
                   "attempt": self.attempt, "state": "PLANNED", "host_ns": time.time_ns(),
                   "timeout_seconds": seconds, "sink": sink, "binary": bool(binary)}
        self.store.put(prefix + ".planned.json", planned)
        self.store.put(prefix + ".dispatch.json", dict(planned, state="DISPATCHING"))
        self.state["in_flight"] = {"unit": self.unit, "attempt": self.attempt,
                                   "command_id": prefix, "argv": argv, "phase": self.phase}
        self.state["updated_ns"] = time.time_ns()
        self.store.put("state.json", self.state)
        started = self.deadline.clock()
        code, stdout, stderr, error, timed_out = None, b"", b"", None, False
        try:
            done = self.runner(argv, capture_output=True, timeout=seconds)
            code, stdout, stderr = done.returncode, done.stdout or b"", done.stderr or b""
        except subprocess.TimeoutExpired as exc:
            stdout = getattr(exc, "stdout", b"") or b""
            stderr = getattr(exc, "stderr", b"") or b""
            error, timed_out = "TIMEOUT after %.1fs: %s" % (seconds, exc), True
        except OSError as exc:
            error = "EXEC_ERROR: %s" % exc
        if isinstance(stdout, str):
            stdout = stdout.encode("utf-8", "replace")
        if isinstance(stderr, str):
            stderr = stderr.encode("utf-8", "replace")
        if sink and not timed_out and code == 0:
            out_ref = self.store.put(sink, stdout, exclusive=True)
        else:
            out_ref = self.store.put(prefix + ".stdout", stdout)
        err_ref = self.store.put(prefix + ".stderr", stderr)
        receipt = {"command_id": prefix, "argv": argv, "returncode": code, "timed_out": timed_out,
                   "error": error, "phase": self.phase, "timeout_seconds": seconds,
                   "start_ns": started, "end_ns": self.deadline.clock(),
                   "stdout": out_ref, "stderr": err_ref}
        ref = self.store.put(prefix + ".receipt.json", receipt)
        self.last_receipt = receipt
        expected_absent = bool(absent) and code == 1 and not stdout.strip() and not stderr.strip()
        row = {"command_id": prefix, "argv": argv, "args": [str(item) for item in args],
               "phase": self.phase, "host_ns": planned["host_ns"], "exit_code": code,
               "timed_out": timed_out, "error": error, "timeout_seconds": seconds,
               "stdout": out_ref, "stderr": err_ref, "receipt": ref}
        self.command_log.append(row)
        if (code != 0 and not expected_absent) or timed_out or code is None:
            self.command_failures.append(row)
        self.state["in_flight"] = None
        self.state["command_count"] = int(self.state.get("command_count") or 0) + 1
        self.state["updated_ns"] = time.time_ns()
        self.store.put("state.json", self.state)
        if timed_out or code is None:
            gesture = gesture_command(argv)
            if gesture:
                self.state["pending"] = {"unit": self.unit, "attempt": self.attempt,
                                         "command_id": prefix, "argv": argv, "phase": self.phase,
                                         "gesture": True, "status": "UNKNOWN"}
                self.store.put("state.json", self.state)
            raise TransportError("COMMAND_TIMEOUT" if timed_out else "COMMAND_UNKNOWN",
                                 error or "unknown exit", retryable=not gesture, command_id=prefix)
        if code != 0 and not expected_absent:
            raise TransportError("COMMAND_FAILED", "%s exit=%s" % (prefix, code),
                                 retryable=True, command_id=prefix)
        return stdout if binary else stdout.decode("utf-8", "replace")

    def sh(self, *args: Any) -> str:
        return self.call("shell", *args)

    def ui_dump(self) -> List[Dict[str, Any]]:
        if self.dump_parser is not None:
            return self.dump_parser(self.call)
        for _ in range(3):
            nodes = parse_ui_dump(self.call("exec-out", "uiautomator", "dump", "/dev/tty"))
            if nodes:
                return nodes
            self.deadline.wait(0.4)
        return []

    def screen_center(self) -> Tuple[int, int]:
        text = self.sh("wm", "size")
        matches = re.findall(r"Override size:\s*(\d+)x(\d+)", text) or \
            re.findall(r"Physical size:\s*(\d+)x(\d+)", text)
        if not matches:
            raise ValueError("screen geometry is missing")
        return int(matches[0][0]) // 2, int(matches[0][1]) // 2

    def app_info(self) -> Dict[str, Any]:
        out = self.sh("dumpsys", "package", PKG)
        path = self.sh("pm", "path", PKG).strip()
        apk = path.split(":", 1)[1] if path.startswith("package:") else ""
        sha = self.sh("sha256sum", apk).split() if apk else []
        flags = re.search(r"flags=\[([^\]]*)\]", out)
        version = re.search(r"versionName=([^\n]+)", out)
        code = re.search(r"versionCode=([0-9]+)", out)
        return {"installed_sha256": sha[0] if sha else None, "apk_path": apk,
                "debuggable": None if flags is None else "DEBUGGABLE" in flags.group(1),
                "versionName": version.group(1).strip() if version else None,
                "versionCode": code.group(1) if code else None, "raw_package": out}

    def device_info(self) -> Dict[str, Any]:
        display = self.sh("dumpsys", "display")
        return {"serial": self.call("get-serialno").strip(),
                "model": self.sh("getprop", "ro.product.model").strip(),
                "sdk": self.sh("getprop", "ro.build.version.sdk").strip(),
                "release": self.sh("getprop", "ro.build.version.release").strip(),
                "abi": self.sh("getprop", "ro.product.cpu.abi").strip(),
                "fingerprint": self.sh("getprop", "ro.build.fingerprint").strip(),
                "device_time": time.strftime("%Y-%m-%dT%H:%M:%S"), "raw_display": display,
                "refresh_rate_hz": parse_refresh_rate(display)}

    def app_pid(self) -> Optional[int]:
        text = self.call("shell", "pidof", PKG, absent=True)
        text = text.strip() if isinstance(text, str) else ""
        return int(text) if re.fullmatch(r"[0-9]+", text) else None

    def foreground_state(self) -> str:
        text = self.sh("dumpsys", "activity", "activities")
        match = re.search(r"(?:mResumedActivity|topResumedActivity)[^\n]*", text)
        return match.group(0).strip() if match else ""

    def perf_lines(self) -> List[str]:
        out = self.sh("logcat", "-v", "threadtime", "-d", "-s", "PBPERF:I", "*:S")
        return [line for line in out.splitlines() if line.strip()]

    def gfxinfo_report(self) -> str:
        return self.sh("dumpsys", "gfxinfo", PKG)

    def framestats_text(self) -> str:
        return self.sh("dumpsys", "gfxinfo", PKG, "framestats")

    def screenshot_png(self, path: str) -> None:
        try:
            relative = Path(path).resolve().relative_to(self.store.root).as_posix()
        except ValueError:
            raise EvidenceError("SCREENSHOT_NOT_IN_RUN", "screenshot must live in the run directory")
        data = self.call("exec-out", "screencap", "-p", binary=True, sink=relative,
                         timeout=SEGMENT_POLICY["screenshot_seconds"])
        with open(path, "wb") as handle:
            handle.write(data)


def collector_hashes() -> Dict[str, str]:
    """SHA-256 of the collector modules; a staged run refuses to mix builds."""
    here = os.path.dirname(os.path.abspath(__file__))
    return {name: sha256_file(os.path.join(here, name))
            for name in ("perf_epub.py", "perf-device.py")}


def current_source(source: Any = None) -> Dict[str, Any]:
    if isinstance(source, dict):
        return dict(source)
    if callable(source):
        return source()
    return source_tree_info()


def _fixture_relative(name: str) -> str:
    if not isinstance(name, str) or not name:
        raise EvidenceError("BINDING_ESCAPE", "empty fixture raw evidence path")
    normal = os.path.normpath(name)
    if os.path.isabs(normal) or normal.startswith(".."):
        raise EvidenceError("BINDING_ESCAPE", name)
    return normal.replace(os.sep, "/")


def offline_inputs(args: Dict[str, Any], cmd: str, source: Dict[str, Any]) -> Tuple[Any, Any, Any, Any, Any]:
    """Validate provenance without adb; returns (apk, sha, inputs, verdict, fixture)."""
    v = Verdict()
    apk = os.path.abspath(args.get("apk") or "") if args.get("apk") else ""
    apk_sha = sha256_file(apk) if apk else None
    if not apk_sha:
        v.fail("APK_NOT_FOUND", "local measured APK file is required")
    release = os.path.abspath(args.get("release_evidence") or "") if args.get("release_evidence") else ""
    fixture = os.path.abspath(args.get("fixture_evidence") or "") if args.get("fixture_evidence") else ""
    host = os.path.abspath(args.get("epub") or "") if args.get("epub") else ""
    evidence = load_release_evidence(release, v)
    if evidence:
        for key in ("apk_sha256", "signer_sha256", "source_tree_sha256"):
            if not re.fullmatch(r"[0-9a-f]{64}", str(evidence.get(key) or "")):
                v.fail("RELEASE_EVIDENCE", "release evidence needs a sha256 for " + key)
        if evidence.get("apk_sha256") != apk_sha:
            v.fail("APK_MISMATCH", "release evidence APK differs from the local APK")
        if not evidence.get("apksigner_verified") or evidence.get("debuggable") is not False:
            v.fail("RELEASE_EVIDENCE", "signed non-debuggable release evidence is required")
        for key in ("git_head", "source_tree_algorithm"):
            if evidence.get(key) != source.get(key):
                v.fail("RELEASE_SOURCE_MISMATCH", "release evidence was built from another source tree")
                break
        if evidence.get("source_tree_sha256") != source.get("source_tree_sha256"):
            v.fail("RELEASE_SOURCE_MISMATCH", "release evidence was built from another source tree")
    host_sha = sha256_file(host)
    if host_sha is not None and host_sha != MOBY_SHA256:
        v.fail("EPUB_SHA_MISMATCH", "host fixture must be the fixed Moby file")
    if int(args.get("book_id") or 0) != QA_BOOK_ID:
        v.fail("BOOK_ID_REQUIRED", "fixed QA Moby book_id=201 is required")
    if not args.get("book_title"):
        v.fail("BOOK_TITLE_MISSING", "exact inspected shelf title is required")
    validate_run_config(cmd, args, v)
    fixture_info = validate_fixture_evidence(fixture, {"installed_sha256": apk_sha},
                                             args.get("book_title", ""), args.get("serial"), v)
    inputs: Dict[str, Dict[str, str]] = {}
    if v.pass_:
        inputs = {"release": {"path": release, "relative": "bindings/inputs/release/" + os.path.basename(release)},
                  "fixture": {"path": fixture, "relative": "bindings/inputs/fixture/" + os.path.basename(fixture)},
                  "host_epub": {"path": host, "relative": "bindings/inputs/host.epub"}}
        raw = ((fixture_info or {}).get("evidence") or {}).get("raw_evidence") or {}
        for key in FIXTURE_RAW_KEYS:
            name = raw.get(key)
            if not isinstance(name, str) or not name:
                v.fail("FIXTURE_RAW_EVIDENCE", "fixture evidence needs a raw file for " + key)
                continue
            inputs["fixture_raw:" + key] = {
                "path": os.path.join(os.path.dirname(fixture), name),
                "relative": "bindings/inputs/fixture/" + _fixture_relative(name)}
        log_path = ((fixture_info or {}).get("evidence") or {}).get("retained_install") or {}
        name = log_path.get("log_path") if isinstance(log_path, dict) else None
        if isinstance(name, str) and name:
            inputs["fixture_raw:retained_install"] = {
                "path": os.path.join(os.path.dirname(fixture), name),
                "relative": "bindings/inputs/fixture/" + _fixture_relative(name)}
    return apk, apk_sha, inputs, v, fixture_info


def stage_inputs(store: RunStore, inputs: Dict[str, Dict[str, str]]) -> Dict[str, Dict[str, Any]]:
    """Copy every input into the run so finish() can re-check them byte for byte."""
    refs = {}
    for key, item in inputs.items():
        data = Path(item["path"]).read_bytes()
        ref = store.put(item["relative"], data, exclusive=True)
        ref["origin"] = os.path.abspath(item["path"])
        refs[key] = ref
    return refs


def input_paths(store: RunStore, manifest: Dict[str, Any]) -> Dict[str, str]:
    bindings = manifest.get("bindings") or {}
    return {"release": str(store.root / bindings["release"]["path"]),
            "fixture": str(store.root / bindings["fixture"]["path"]),
            "host_epub": str(store.root / bindings["host_epub"]["path"])}


def build_manifest(cmd: str, args: Dict[str, Any], apk: str, apk_sha: Optional[str],
                   source: Dict[str, Any], bindings: Dict[str, Dict[str, Any]]) -> Dict[str, Any]:
    unit_total = UNIT_TOTAL[cmd]
    return {
        "schema": SEGMENT_SCHEMA, "policy": dict(SEGMENT_POLICY),
        "run_id": uuid.uuid4().hex, "created_ns": time.time_ns(), "created_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "cmd": cmd, "unit_total": unit_total,
        "adb": args.get("adb") or "adb", "serial": args.get("serial") or PRIMARY_SERIAL,
        "apk": apk, "apk_sha256": apk_sha,
        "book_title": args.get("book_title", ""), "book_id": int(args.get("book_id") or QA_BOOK_ID),
        "chapter": int(args.get("chapter") or 0), "page": int(args.get("page") or 1),
        "cold": int(args.get("cold") or MIN_COLD_SAMPLES),
        "turns": int(args.get("turns") or MIN_TURNS), "rounds": int(args.get("rounds") or MIN_ROUNDS),
        "turn_mode": args.get("turn_mode") or "forward", "toc_match": args.get("toc_match") or "",
        "source": {key: source.get(key) for key in SOURCE_FIELDS},
        "collector": collector_hashes(), "bindings": bindings,
    }


def new_state(manifest: Dict[str, Any], manifest_sha: str) -> Dict[str, Any]:
    return {
        "schema": SEGMENT_SCHEMA, "run_id": manifest["run_id"], "cmd": manifest["cmd"],
        "manifest_sha256": manifest_sha, "unit_total": manifest["unit_total"],
        "next_unit": 1, "command_count": 0, "updated_ns": time.time_ns(),
        "device_sha256": None, "identity_sha256": None, "in_flight": None, "pending": None,
        "units": [{"unit": number, "status": "EMPTY", "sha256": None, "attempts": 0, "commands": 0}
                  for number in range(1, manifest["unit_total"] + 1)],
        "failures": [], "interruptions": [], "reconciled": [],
    }


def device_preflight(store: RunStore, manifest: Dict[str, Any], app: Dict[str, Any],
                     info: Dict[str, Any], source: Dict[str, Any]) -> Tuple[Dict[str, Any], Verdict]:
    v = Verdict()
    paths = input_paths(store, manifest)
    preflight = build_preflight(manifest["apk"], paths["host_epub"], app, manifest["book_title"], v,
                                book_id=manifest["book_id"], release_evidence=paths["release"],
                                fixture_evidence=paths["fixture"], serial=info.get("serial"),
                                source=source, apk_sha256=manifest["apk_sha256"])
    if info.get("serial") != PRIMARY_SERIAL or manifest.get("serial") != PRIMARY_SERIAL:
        v.fail("PRIMARY_DEVICE", "P1 acceptance requires Pixel3XL serial " + PRIMARY_SERIAL)
    if info.get("model") != "Pixel 3 XL":
        v.fail("PRIMARY_DEVICE", "P1 acceptance requires the fixed Pixel3XL device")
    if not info.get("refresh_rate_hz"):
        v.fail("REFRESH_RATE_MISSING", "active display refresh rate missing")
    if app.get("installed_sha256") != manifest["apk_sha256"]:
        v.fail("APK_MISMATCH", "installed base.apk differs from the bound APK")
    return preflight, v


def ensure_device(device: RunDevice, store: RunStore, state: Dict[str, Any],
                  manifest: Dict[str, Any], source: Dict[str, Any]) -> Dict[str, Any]:
    if state.get("device_sha256"):
        ref = store.ref("device.json")
        if ref["sha256"] != state["device_sha256"]:
            raise EvidenceError("DEVICE_DRIFT", "device snapshot changed after the first step")
        doc = store.json("device.json")
        verify_device(device, doc)
        return doc
    app = device.app_info()
    info = device.device_info()
    preflight, v = device_preflight(store, manifest, app, info, source)
    if not v.pass_:
        raise EvidenceError("PREFLIGHT_FAILED", ",".join(sorted({item["code"] for item in v.failures})))
    geometry = re.findall(r"Override size:\s*(\d+)x(\d+)", device.sh("wm", "size")) or \
        re.findall(r"Physical size:\s*(\d+)x(\d+)", device.sh("wm", "size"))
    doc = {"schema": SEGMENT_SCHEMA, "run_id": manifest["run_id"], "created_ns": time.time_ns(),
           "serial": info.get("serial"), "model": info.get("model"), "sdk": info.get("sdk"),
           "fingerprint": info.get("fingerprint"), "refresh_rate_hz": info.get("refresh_rate_hz"),
           "viewport": list(geometry[-1]) if geometry else None,
           "installed_apk_sha256": app.get("installed_sha256"), "app": app, "device": info,
           "preflight": preflight, "source_sha256": manifest["source"].get("source_tree_sha256"),
           "collector": collector_hashes(),
           "preflight_failures": [dict(item) for item in v.failures]}
    ref = store.put("device.json", doc, exclusive=True)
    state["device_sha256"] = ref["sha256"]
    state["identity_sha256"] = (manifest.get("snapshots") or {}).get("identity", {}).get("sha256")
    state["updated_ns"] = time.time_ns()
    store.put("state.json", state)
    return doc


def verify_device(device: RunDevice, doc: Dict[str, Any]) -> None:
    """Re-prove the frozen device/APK identity before committing a unit."""
    if collector_hashes() != doc.get("collector"):
        raise EvidenceError("COLLECTOR_DRIFT", "collector scripts changed during the run")
    serial = device.call("get-serialno").strip()
    if serial != doc.get("serial"):
        raise EvidenceError("DEVICE_DRIFT", "serial %r is not the frozen device" % serial)
    for prop, key in (("ro.product.model", "model"), ("ro.build.version.sdk", "sdk"),
                      ("ro.build.fingerprint", "fingerprint")):
        value = device.sh("getprop", prop).strip()
        if value != doc.get(key):
            raise EvidenceError("DEVICE_DRIFT", "device %s changed during the run" % prop)
    geometry = re.findall(r"Override size:\s*(\d+)x(\d+)", device.sh("wm", "size")) or \
        re.findall(r"Physical size:\s*(\d+)x(\d+)", device.sh("wm", "size"))
    if (list(geometry[-1]) if geometry else None) != doc.get("viewport"):
        raise EvidenceError("DEVICE_DRIFT", "viewport changed during the run")
    paths = device.sh("pm", "path", PKG).strip().splitlines()
    if len(paths) != 1 or not paths[0].startswith("package:"):
        raise EvidenceError("DEVICE_DRIFT", "a single installed base.apk is required")
    installed = device.sh("sha256sum", paths[0].split(":", 1)[1]).split()
    if not installed or installed[0] != doc.get("installed_apk_sha256"):
        raise EvidenceError("INSTALLED_APK_DRIFT", "installed base.apk changed during the run")
    flags = re.search(r"flags=\[([^\]]*)\]", device.sh("dumpsys", "package", PKG))
    if flags is None or "DEBUGGABLE" in flags.group(1):
        raise EvidenceError("DEBUGGABLE_PACKAGE", "measured process must be the signed non-debuggable release")


def guard_unit(operation: Any, device: Any, *args: Any, **kwargs: Any) -> Dict[str, Any]:
    """Keep a logical collection failure inside the unit; transport errors abort it."""
    command_start = len(getattr(device, "command_log", []))
    try:
        result = operation(device, *args, **kwargs)
    except (OSError, ValueError, subprocess.SubprocessError) as exc:
        result = {"ok": False, "detail": "COLLECTION_ERROR: " + str(exc),
                  "events": [], "frames": [], "collection_failures": [
                      {"code": "COLLECTION_ERROR", "message": str(exc)}]}
    result["raw_commands"] = list(getattr(device, "command_log", []))[command_start:]
    return result


def collect_cold_unit(device: RunDevice, args: Dict[str, Any], doc: Dict[str, Any],
                      index: int) -> Dict[str, Any]:
    """One process-cold entry: prepare progress, measure the first entry, screenshot."""
    book = args.get("book_title", "")
    toc = args.get("toc_match") or "I: Loomings"
    run_v = Verdict()
    device.phase = "prepare"
    prep = guard_unit(prepare_progress, device, book, toc, 1, run_v)
    if prep.get("ok"):
        device.phase = "entry"
        attempt = guard_unit(run_first_entry, device, book, toc, 0)
    else:
        attempt = {"ok": False, "detail": "PROGRESS_PREP_FAILED", "events": [], "frames": []}
    device.phase = "screenshot"
    shot_args = dict(args)
    shot_args["json_out"] = str(device.store.root / "screenshots" / "unit.json")
    shot_args["screenshot_dir"] = ""
    capture_cold_screenshot(device, attempt, shot_args, doc["app"], doc["device"],
                           doc["preflight"], index)
    return {"preparation": prep, "preparation_verdict": run_v.as_dict(), "attempt": attempt}


# ---------------------------------------------------------------------------
# Mid-round resume: one 20-turn round split across bounded host calls.
#
# A round owns one app process, one reader page chain and one gfxinfo reset
# window. Rebuilding that inside every 120 s host call is what made the 04
# round time out, so the round is journalled and continued instead. The journal
# is the only mutable file (rewritten atomically after every complete turn);
# each finished host call also publishes one immutable segment holding exactly
# the bytes it retained. Committing merges the segments in order, so the shared
# graders and the report still see one round, never a splice of two different
# rounds or a re-sent gesture.

def round_paths(unit: int, attempt: int) -> Dict[str, str]:
    base = "rounds/%03d/%02d" % (unit, attempt)
    return {"base": base, "journal": base + "/journal.json",
            "segment": base + "/segments/%02d.json"}


def new_round_journal(unit: int, attempt: int, manifest: Dict[str, Any],
                      manifest_sha: str) -> Dict[str, Any]:
    return {"schema": ROUND_SCHEMA, "run_id": manifest["run_id"],
            "manifest_sha256": manifest_sha, "unit": unit, "attempt": attempt,
            "cmd": manifest["cmd"], "chapter": manifest["chapter"],
            "book_id": manifest["book_id"], "turns": manifest["turns"],
            "start_page": manifest["page"], "stage": "prepare", "status": "RUNNING",
            "pid": None, "page_count": None, "current_page": None, "next_turn": 1,
            "gfx_reset": False, "cursor": None, "commands": 0, "segments": [],
            "preparation": None, "entry": None, "tail_error": None,
            "detail": None, "ok": None, "created_ns": time.time_ns(),
            "updated_ns": time.time_ns()}


def load_round_journal(store: RunStore, unit: int,
                       attempt: int) -> Optional[Dict[str, Any]]:
    relative = round_paths(unit, attempt)["journal"]
    if not store.path(relative).is_file():
        return None
    journal = store.json(relative)
    if not isinstance(journal, dict) or journal.get("schema") != ROUND_SCHEMA:
        raise EvidenceError("ROUND_SCHEMA", "round journal schema mismatch: " + relative)
    if journal.get("unit") != unit or journal.get("attempt") != attempt:
        raise EvidenceError("ROUND_IDENTITY", "round journal belongs to another unit/attempt")
    return journal


def write_round_journal(store: RunStore, journal: Dict[str, Any]) -> None:
    journal["updated_ns"] = time.time_ns()
    store.put(round_paths(journal["unit"], journal["attempt"])["journal"], journal)


def resumable_attempt(store: RunStore, slot: Dict[str, Any],
                      unit: int) -> Optional[int]:
    """Only a cleanly paused round continues; an interrupted one is never resumed."""
    if slot.get("status") != "IN_PROGRESS":
        return None
    number = int(slot.get("attempts") or 0)
    if number < 1:
        return None
    journal = load_round_journal(store, unit, number)
    if journal is None:
        return None
    # Only a round that is still open may continue; a sealed one is evidence.
    if journal.get("stage") == "done" or journal.get("status") not in (None, "RUNNING"):
        return None
    return number


def attempt_command_files(store: RunStore, unit: int,
                          attempt: int) -> Tuple[List[str], List[str]]:
    directory = store.path("commands/%03d/%02d" % (unit, attempt))
    if not directory.is_dir():
        return [], []
    planned = sorted(path.name for path in directory.glob("*.planned.json"))
    receipts = sorted(path.name for path in directory.glob("*.receipt.json"))
    return planned, receipts


def attempt_receipt_digests(store: RunStore, unit: int, attempt: int) -> List[str]:
    _, receipts = attempt_command_files(store, unit, attempt)
    base = "commands/%03d/%02d/" % (unit, attempt)
    return [store.ref(base + name)["sha256"] for name in receipts]


def command_tail_problems(store: RunStore, unit: int, attempt: int) -> List[Dict[str, str]]:
    """Every planned command needs one complete receipt, in a continuous sequence.

    A timeout, a missing receipt or an unknown exit means the bytes on disk cannot
    prove what reached the device, so the caller has to fail closed.
    """
    planned, receipts = attempt_command_files(store, unit, attempt)
    keys = sorted(name.split(".", 1)[0] for name in planned)
    if keys != ["%06d" % number for number in range(1, len(keys) + 1)]:
        return [{"code": "COMMAND_SEQUENCE_GAP",
                 "message": "planned command sequence is not continuous for unit %d" % unit}]
    base = "commands/%03d/%02d/" % (unit, attempt)
    receipt_keys = set(name.split(".", 1)[0] for name in receipts)
    problems: List[Dict[str, str]] = []
    for key in keys:
        if key not in receipt_keys:
            problems.append({"code": "COMMAND_TAIL_INCOMPLETE", "message": "no receipt for " + base + key})
            continue
        receipt = store.json(base + key + ".receipt.json")
        if receipt.get("timed_out") or receipt.get("returncode") is None:
            problems.append({"code": "COMMAND_TAIL_INCOMPLETE",
                             "message": "aborted command retained: " + base + key})
    for key in receipt_keys:
        if key not in set(keys):
            problems.append({"code": "COMMAND_TAIL_DRIFT", "message": "receipt without plan: " + base + key})
    return problems


def verify_round_tail(store: RunStore, journal: Dict[str, Any]) -> None:
    """A resumed round may only start from a closed, fully receipted command tail."""
    unit, attempt = journal["unit"], journal["attempt"]
    planned, _ = attempt_command_files(store, unit, attempt)
    total = int(journal.get("commands") or 0)
    if len(planned) != total:
        raise TransportError("COMMAND_TAIL_DRIFT",
                             "%d planned commands != %d journalled" % (len(planned), total))
    problems = command_tail_problems(store, unit, attempt)
    if problems:
        raise TransportError(problems[0]["code"], problems[0]["message"])


def turn_round_key() -> Dict[str, Any]:
    return {"events": [], "frames": [], "raw_logcat": [], "raw_logcat_before": None,
            "raw_framestats": [], "pages": [], "collection_failures": [],
            "foreground": []}


def prior_segment_failures(store: RunStore,
                          journal: Dict[str, Any]) -> List[Dict[str, str]]:
    """Collection failures the earlier segments of this round already retain.

    One round is one unit of evidence: a failure recorded before the pause stays
    part of the round, so the host call that happens to run last may not seal it
    complete just because its own slice looked clean.
    """
    out: List[Dict[str, str]] = []
    for item in journal.get("segments") or []:
        try:
            segment = store.json(item["path"])
        except (OSError, ValueError, KeyError) as exc:
            out.append({"code": "ROUND_SEGMENT_UNREADABLE",
                        "message": "cannot read %s: %s" % (item.get("path"), exc)})
            continue
        out.extend(dict(entry) for entry in segment.get("collection_failures") or [])
    return out


def retained_frames(store: RunStore, journal: Dict[str, Any]) -> List[FrameStat]:
    """Every frame the earlier segments of this round already hold."""
    held: List[FrameStat] = []
    for item in journal.get("segments") or []:
        segment = store.json(item["path"])
        for raw in segment.get("frames") or []:
            held.append(_replay_frame(raw))
    return held


class TurnRoundCollector:
    """Advance one journalled 20-turn round inside this host call's budget.

    Every pause happens between complete turns: the segment boundary never sits
    between a dispatched tap and the frame read that belongs to it. Each stage
    reserves the budget it needs *before* dispatching anything, so a host call
    that cannot finish a stage stops at the previous boundary instead.
    """

    def __init__(self, device: RunDevice, args: Dict[str, Any],
                 journal: Dict[str, Any]) -> None:
        self.device = device
        self.args = args
        self.journal = journal
        self.book = args.get("book_title", "")
        self.toc = args.get("toc_match") or "LIV: The Town-Ho"
        self.state = turn_round_key()
        self.preparation: Optional[Dict[str, Any]] = None
        self.entry: Optional[Dict[str, Any]] = None
        self.pid_end: Optional[int] = None
        self.gfx_text = ""
        self.cursor: List[str] = list(journal.get("cursor") or [])
        self.stages: List[str] = []
        self.page_events_expected = 0
        self.turn_cost = float(journal.get("turn_cost_seconds") or 0.0)
        self.logs: Optional[EventCapture] = None
        self.frames_capture: Optional[FrameCapture] = None
        self.tail_failures: List[Dict[str, str]] = []
        self.tail_problems: List[Dict[str, str]] = []
        self.prior_failures = prior_segment_failures(device.store, journal)

    # -- evidence ---------------------------------------------------------
    def _collect_tail(self) -> None:
        """One last cursor read so no line emitted during the segment is lost.

        The read is best effort only in the sense that it must not abort the
        evidence write; the failure itself is fatal for this round (see flush).
        """
        if self.logs is None:
            return
        try:
            self.logs.poll()
        except TransportError as exc:
            self.tail_failures.append({"code": exc.code or "TAIL_READ_FAILED",
                                       "message": "logcat: " + exc.message,
                                       "command_id": exc.command_id or ""})
        if self.frames_capture is not None:
            try:
                self.frames_capture.poll()
            except TransportError as exc:
                self.tail_failures.append({"code": exc.code or "TAIL_READ_FAILED",
                                           "message": "framestats: " + exc.message,
                                           "command_id": exc.command_id or ""})
        if self.tail_failures:
            self.journal["tail_failures"] = [dict(item) for item in self.tail_failures]

    def _drain(self) -> None:
        if self.logs is not None:
            if self.state["raw_logcat_before"] is None:
                self.state["raw_logcat_before"] = list(self.logs.before)
            self.state["raw_logcat"] = list(self.logs.lines)
            self.state["events"] = [event.raw for event in self.logs.events]
            self.state["collection_failures"].extend(
                dict(item) for item in self.logs.failures if dict(item) not in self.state["collection_failures"])
            self.cursor = list(self.logs.cursor)
            self.journal["cursor"] = list(self.cursor)
        if self.frames_capture is not None:
            self.state["frames"] = [dict(vars(frame)) for frame in self.frames_capture.frames.values()]
            self.state["raw_framestats"] = list(self.frames_capture.raw)
            self.state["collection_failures"].extend(
                dict(item) for item in self.frames_capture.failures
                if dict(item) not in self.state["collection_failures"])

    def flush(self, closed_ns: Optional[int] = None) -> Optional[Dict[str, Any]]:
        """Publish the bytes this host call retained as one immutable segment."""
        self._collect_tail()
        self._drain()
        problems = [dict(item) for item in self.tail_failures]
        problems.extend(command_tail_problems(self.device.store, self.journal["unit"],
                                              self.journal["attempt"]))
        if problems:
            # A timed-out tail read, a missing receipt or an unknown exit means
            # the bytes on disk cannot prove what reached the device. Such a
            # round is never sealed COMPLETED and never continued later.
            self.tail_problems = problems
            self.state["collection_failures"].extend(
                dict(item) for item in problems
                if dict(item) not in self.state["collection_failures"])
            if self.journal.get("status") != "FAILED":
                # Seal with the concrete reason; a round that already failed
                # keeps its first, more specific detail.
                self._close(problems[0]["code"], True)
        if not (self.state["events"] or self.state["raw_logcat"] or self.state["pages"]
                or self.preparation or self.entry or self.gfx_text
                or self.state["collection_failures"]):
            return None
        index = len(self.journal["segments"]) + 1
        record = {"schema": ROUND_SEGMENT_SCHEMA, "run_id": self.journal["run_id"],
                  "unit": self.journal["unit"], "attempt": self.journal["attempt"],
                  "index": index, "stages": list(self.stages),
                  "closed_ns": int(closed_ns or time.time_ns()),
                  "pid": self.journal.get("pid"), "pid_end": self.pid_end,
                  "next_turn": self.journal["next_turn"], "stage": self.journal["stage"],
                  "page_count": self.journal.get("page_count"),
                  "pages": list(self.state["pages"]),
                  "preparation": self.preparation, "entry": self.entry,
                  "events": list(self.state["events"]),
                  "frames": list(self.state["frames"]),
                  "raw_logcat": list(self.state["raw_logcat"]),
                  "raw_logcat_before": list(self.state["raw_logcat_before"] or []),
                  "raw_logcat_after": list(self.cursor),
                  "raw_framestats": list(self.state["raw_framestats"]),
                  "gfx_text": self.gfx_text,
                  "foreground": list(self.state["foreground"]),
                  "collection_failures": [dict(item) for item in self.state["collection_failures"]]}
        relative = round_paths(self.journal["unit"], self.journal["attempt"])["segment"] % index
        ref = self.device.store.put(relative, record, exclusive=True)
        self.journal["segments"].append({"index": index, "path": relative,
                                         "sha256": ref["sha256"],
                                         "stages": list(self.stages),
                                         "turns": len(self.state["pages"]),
                                         "commands": self.device.count})
        return record

    # -- stages -----------------------------------------------------------
    def _reserve(self, stage: str) -> bool:
        return self.device.deadline.remaining() >= ROUND_STAGE_RESERVE_SECONDS[stage]

    def _turn_reserve(self) -> float:
        """Never start a turn the rest of this call could not finish."""
        need = ROUND_TURN_RESERVE_SECONDS
        if self.turn_cost:
            need = max(need, self.turn_cost * ROUND_TURN_COST_SLACK
                       + SEGMENT_POLICY["deadline_guard_seconds"])
        return need

    def _fail(self, code: str, message: str) -> None:
        self.state["collection_failures"].append({"code": code, "message": message})

    def _close(self, detail: str, failed: bool) -> None:
        self.journal["detail"] = detail
        # The round is one unit: a failure an earlier segment retained still
        # belongs to it, so this call may not seal the round complete.
        failed = bool(failed) or bool(self.prior_failures)
        if self.prior_failures:
            self.journal["prior_failures"] = [dict(item) for item in self.prior_failures]
        self.journal["ok"] = not failed
        self.journal["status"] = "FAILED" if failed else "COMPLETED"
        self.journal["stage"] = "done"

    def _same_pid(self, pid: Optional[int]) -> bool:
        expected = self.journal.get("pid")
        return expected is not None and pid is not None and int(pid) == int(expected)

    def advance(self) -> str:
        while True:
            stage = self.journal["stage"]
            if stage == "done":
                return "DONE"
            if stage == "prepare":
                if not self._reserve("prepare"):
                    return "PAUSE"
                self._prepare()
            elif stage == "entry":
                if not self._reserve("entry"):
                    return "PAUSE"
                self._entry()
            elif stage == "turns":
                if self._turns() == "PAUSE":
                    return "PAUSE"
            elif stage == "finish":
                if not self._reserve("finish"):
                    return "PAUSE"
                self._finish()
            else:
                raise EvidenceError("ROUND_STAGE", "unknown round stage: %r" % (stage,))

    def _prepare(self) -> None:
        self.device.phase = "prepare"
        self.stages.append("prepare")
        v = Verdict()
        prep = guard_unit(prepare_progress, self.device, self.book, self.toc,
                          self.journal["start_page"], v)
        self.preparation = prep
        self.journal["preparation"] = prep
        self.state["collection_failures"].extend(dict(item) for item in v.failures)
        if not prep.get("ok"):
            self._close("PROGRESS_PREP_FAILED", True)
            return
        self.journal["stage"] = "entry"

    def _entry(self) -> None:
        self.device.phase = "entry"
        self.stages.append("entry")
        entry = guard_unit(run_first_entry, self.device, self.book, self.toc,
                          self.journal["start_page"] - 1, False)
        evidence = attempt_evidence(entry)
        self.entry = evidence
        self.journal["entry"] = evidence
        self.journal["pid"] = entry.get("pid")
        self.cursor = list(evidence.get("raw_logcat_after") or [])
        self.journal["cursor"] = list(self.cursor)
        failures = [dict(item) for item in entry.get("collection_failures") or []]
        entry_events = [event for event in entry.get("events") or [] if isinstance(event, PerfEvent)]
        if entry.get("ok"):
            first_v = validate_first_chapter(entry_events, self.journal["chapter"], 0,
                                             expected_book_id=self.journal.get("book_id"),
                                             min_samples=1, require_budget=False)
            failures.extend(first_v.failures)
            if any(event.phase == PAGE_PHASE for event in entry_events):
                failures.append({"code": "PRESET_TURN_POLLUTION",
                                 "message": "fresh reader already emitted page-turn events"})
        self.state["collection_failures"].extend(failures)
        if not entry.get("ok") or failures:
            self._close(entry.get("detail") or "ENTRY_INVALID", True)
            return
        self.journal["stage"] = "turns"

    def _open_window(self) -> None:
        # Open the window exactly where the fresh entry left the reader, the same
        # way the single-process round snapshots its cursor before turn 1.
        self.logs = EventCapture.from_cursor(self.device, self.device.perf_lines())
        nodes = show_chrome(self.device)
        pos = progress_page(nodes)
        pid = self.device.app_pid()
        if not self._same_pid(pid):
            self._fail("ROUND_PID_CHANGED",
                       "measured app process changed: %s != %s"
                       % (pid, self.journal.get("pid")))
            self._close("ROUND_PID_CHANGED", True)
            return
        if not pos or pos[0] != self.journal["start_page"]:
            self._fail("PAGE_LABEL_NOT_AT_START",
                       "reader is at %s, expected page %s" % (pos, self.journal["start_page"]))
            self._close("PAGE_LABEL_NOT_AT_START", True)
            return
        current, total = pos
        if current + self.journal["turns"] > total:
            self._fail("PAGE_COUNT_INSUFFICIENT",
                       "%d pages from page%d cannot supply %d forward new pages"
                       % (total, current, self.journal["turns"]))
            self._close("PAGE_COUNT_INSUFFICIENT", True)
            return
        self.journal["page_count"] = total
        self.journal["current_page"] = current
        _sleep(0.5)
        self.journal["gfx_reset_output"] = self.device.sh("dumpsys", "gfxinfo", PKG, "reset")
        self.frames_capture = FrameCapture(self.device)
        self.journal["gfx_reset"] = True
        self.journal["stage"] = "turns"

    def _restore_streams(self) -> bool:
        """Re-open both captures after a pause, from a provably unchanged reader.

        One cursor read continues exactly where the previous segment stopped, so
        nothing emitted while the host call was asleep is lost and the earlier
        capture's parse failures stay part of this segment. Anything that shows
        up there is an untracked transition, and the round fails closed.
        """
        probe = {"foreground": self.state["foreground"],
                 "collection_failures": self.state["collection_failures"]}
        if not check_foreground(self.device, probe):
            self._close("BACKGROUND_WINDOW", True)
            return False
        pid = self.device.app_pid()
        if not self._same_pid(pid):
            self._fail("ROUND_PID_CHANGED",
                       "measured app process changed between segments: %s != %s"
                       % (pid, self.journal.get("pid")))
            self._close("ROUND_PID_CHANGED", True)
            return False
        nodes = show_chrome(self.device)
        pos = progress_page(nodes)
        if not pos or pos[0] != self.journal["current_page"]:
            self._fail("PAGE_LABEL_DRIFT",
                       "reader is at %s, the paused round stopped at page %s"
                       % (pos, self.journal["current_page"]))
            self._close("PAGE_LABEL_DRIFT", True)
            return False
        if self.journal.get("page_count") and len(pos) > 1 and int(pos[1]) != int(self.journal["page_count"]):
            self._fail("PAGE_COUNT_DRIFT",
                       "chapter page total changed from %s to %s between segments"
                       % (self.journal["page_count"], pos[1]))
            self._close("PAGE_COUNT_DRIFT", True)
            return False
        self.logs = EventCapture.from_cursor(self.device, self.journal.get("cursor") or [])
        try:
            self.logs.poll()
        except TransportError as exc:
            self._fail("ROUND_LOG_CONTINUITY",
                       "cannot prove logcat continuity across segments: %s: %s"
                       % (exc.code, exc.message))
            self._close("ROUND_LOG_CONTINUITY", True)
            return False
        if self.logs.lines or self.logs.events:
            # No reader line may appear while no host call was running.
            self._fail("ROUND_UNTRACKED_TURN",
                       "%d reader line(s) appeared while the round was paused"
                       % len(self.logs.lines))
            self._close("ROUND_UNTRACKED_TURN", True)
            return False
        # Read the frame ring once before the round goes on: gfxinfo's buffer is
        # finite, so a segment gap that let rows scroll away would silently lose
        # this round's frames. One dump carries both the frame rows and the
        # summary header (the device repeats it on top of the framestats table),
        # so the "Total frames rendered" figure and the rows it must cover come
        # from the very same read: no second command, no race on a moving ring.
        self.frames_capture = FrameCapture(self.device)
        self.frames_capture.absorb(self.device.framestats_text())
        summary = parse_gfxinfo_summary(self.frames_capture.raw[-1])
        total = summary.get("total_frames")
        held = retained_frames(self.device.store, self.journal)
        held.extend(self.frames_capture.frames.values())
        expected_pid = self.journal.get("pid")
        foreign = sorted({str(frame.pid) for frame in held
                          if expected_pid is None or frame.pid is None
                          or int(frame.pid) != int(expected_pid)})
        if foreign:
            # One round belongs to one process: a frame identity from another pid
            # can never be folded into this round's evidence.
            self._fail("ROUND_FRAME_PID",
                       "frame(s) in this round's ring come from %s, the round runs "
                       "in pid %s" % (foreign, expected_pid))
            self._close("ROUND_FRAME_PID", True)
            return False
        retained = {frame.key for frame in held}
        if total is None or int(total) != len(retained):
            self._fail("ROUND_FRAME_GAP",
                       "%s frame(s) rendered since reset but %d can still be read "
                       "after the pause" % (total, len(retained)))
            self._close("ROUND_FRAME_GAP", True)
            return False
        return True

    def _reopen_window(self) -> None:
        self._restore_streams()

    def _turns(self) -> str:
        if not self._reserve("turns"):
            return "PAUSE"
        self.device.phase = "turns"
        self.stages.append("turns")
        if self.journal.get("gfx_reset"):
            self._reopen_window()
        else:
            self._open_window()
        if self.journal["stage"] != "turns":
            return "DONE"
        while self.journal["next_turn"] <= self.journal["turns"]:
            if self.device.deadline.remaining() < self._turn_reserve():
                return "PAUSE"
            turn = self.journal["next_turn"]
            turn_started = self.device.deadline.clock()
            self.device.phase = "turn%02d" % turn
            probe = {"foreground": self.state["foreground"],
                     "collection_failures": self.state["collection_failures"]}
            if not check_foreground(self.device, probe):
                self._close("BACKGROUND_WINDOW", True)
                return "DONE"
            expected = self.journal["current_page"] + 1
            reader_nav_tap(self.device, "next")
            changed = wait_for(self.device, lambda ns: progress_page(ns) if
                               progress_page(ns) and progress_page(ns)[0] == expected else None,
                               timeout=3.0, interval=0.12)
            self.page_events_expected += 1
            self.logs.wait(PAGE_PHASE, self.page_events_expected)
            self.frames_capture.poll()
            if not changed:
                self._fail("PAGE_DID_NOT_CHANGE", "turn %d did not advance the page label" % turn)
                self._close("PAGE_DID_NOT_CHANGE", True)
                return "DONE"
            cost = (self.device.deadline.clock() - turn_started) / 1e9
            if cost > self.turn_cost:
                self.turn_cost = cost
                self.journal["turn_cost_seconds"] = round(cost, 3)
            self.journal["current_page"] = changed[0]
            self.journal["next_turn"] = turn + 1
            self.state["pages"].append({"turn": turn, "page": changed[0]})
            if self.journal["next_turn"] > self.journal["turns"]:
                self.journal["stage"] = "finish"
                return "DONE"
        self.journal["stage"] = "finish"
        return "DONE"

    def _finish(self) -> None:
        self.device.phase = "finish"
        if self.logs is None or self.frames_capture is None:
            # A round can pause exactly at its last turn boundary and be resumed
            # by a brand new host process. Both captures have to be re-opened
            # from the journalled cursor, then finish_capture reads the tail the
            # same way an uninterrupted segment does.
            if not self._restore_streams():
                return
        self.stages.append("finish")
        scratch: Dict[str, Any] = {"pid": self.journal.get("pid"), "collection_failures": [],
                                   "foreground": []}
        finish_capture(self.device, scratch, self.logs, self.frames_capture)
        self.state["collection_failures"].extend(
            dict(item) for item in scratch.get("collection_failures") or [])
        self.state["foreground"].extend(scratch.get("foreground") or [])
        self.gfx_text = scratch.get("gfx_text") or ""
        self.pid_end = scratch.get("pid_end")
        missing: List[Any] = []
        if self.logs is not None and self.frames_capture is not None:
            events, frames = round_evidence(self.device.store, self.journal,
                                            self.logs.events,
                                            list(self.frames_capture.frames.values()))
            missing = [event.turn for event in events
                       if event.phase == PAGE_PHASE and event.formal
                       and not match_frames_for_event(event, frames)[1]]
        if missing:
            # The round may not seal complete on frames it does not actually hold.
            self._fail("ROUND_FRAME_MISSING",
                       "turn(s) %s carry a page event with no app frame in the window"
                       % (missing,))
        failed = bool(self.state["collection_failures"])
        self._close("TURN_ROUND", failed)


def collect_turn_unit(device: RunDevice, args: Dict[str, Any],
                      journal: Dict[str, Any]) -> str:
    """One host call of a journalled 20-page round: PAUSE, or DONE when the round ends."""
    collector = TurnRoundCollector(device, args, journal)
    try:
        outcome = collector.advance()
    except TransportError:
        try:
            collector.flush()
        except (OSError, ValueError, KeyError, TypeError, EvidenceError):
            pass  # the gesture bytes already on disk stay authoritative
        journal["commands"] = device.count
        raise
    collector.flush()
    journal["commands"] = device.count
    if collector.tail_problems:
        raise EvidenceError("ROUND_TAIL_UNPROVEN",
                            "; ".join("%s: %s" % (item["code"], item["message"])
                                      for item in collector.tail_problems[:4]))
    return outcome


def merge_round_attempt(store: RunStore, journal: Dict[str, Any]) -> Dict[str, Any]:
    """Reassemble one round from its ordered segments, re-verifying every digest."""
    if not journal.get("segments"):
        raise EvidenceError("ROUND_SEGMENT_MISSING", "a round needs at least one retained segment")
    events: List[PerfEvent] = []
    frames: Dict[Tuple[Optional[int], str, int, int], FrameStat] = {}
    failures: List[Dict[str, str]] = []
    raw: List[str] = []
    raw_framestats: List[str] = []
    before: Optional[List[str]] = None
    foreground: List[str] = []
    pages: List[Dict[str, int]] = []
    gfx_text = ""
    pid_end = None
    expected = 1
    for item in journal["segments"]:
        if item.get("index") != expected:
            raise EvidenceError("ROUND_SEGMENT_ORDER",
                                "segment index %r != %d" % (item.get("index"), expected))
        expected += 1
        if store.ref(item["path"])["sha256"] != item["sha256"]:
            raise EvidenceError("ROUND_SEGMENT_DRIFT", "segment digest changed: " + item["path"])
        segment = store.json(item["path"])
        if segment.get("run_id") != journal["run_id"] or segment.get("unit") != journal["unit"] \
                or segment.get("attempt") != journal["attempt"]:
            raise EvidenceError("ROUND_SEGMENT_IDENTITY",
                                "segment belongs to another run: " + item["path"])
        for raw_line in segment.get("events") or []:
            events.append(_replay_event(raw_line))
        for raw_frame in segment.get("frames") or []:
            frame = _replay_frame(raw_frame)
            previous = frames.get(frame.key)
            if previous is not None and previous != frame:
                raise EvidenceError("ROUND_FRAME_DRIFT",
                                    "framestats row changed between segments")
            frames[frame.key] = frame
        failures.extend(dict(entry) for entry in segment.get("collection_failures") or [])
        raw.extend(segment.get("raw_logcat") or [])
        raw_framestats.extend(segment.get("raw_framestats") or [])
        foreground.extend(segment.get("foreground") or [])
        pages.extend(segment.get("pages") or [])
        if before is None:
            before = list(segment.get("raw_logcat_before") or [])
        if segment.get("gfx_text"):
            gfx_text = segment["gfx_text"]
        pid_end = segment.get("pid_end") or pid_end
    return {"ok": bool(journal.get("ok")), "detail": journal.get("detail") or "TURN_ROUND",
            "events": events, "frames": list(frames.values()),
            "collection_failures": failures, "start_page": journal["start_page"],
            "page_count": journal.get("page_count"), "end_page": journal.get("current_page"),
            "preparation": journal.get("preparation"), "entry": journal.get("entry"),
            "pid": journal.get("pid"), "pid_end": pid_end,
            "raw_logcat": raw, "raw_logcat_before": list(before or []),
            "raw_logcat_after": list(journal.get("cursor") or []),
            "raw_framestats": raw_framestats, "gfx_text": gfx_text,
            "foreground": foreground, "pages": pages,
            "segment_count": len(journal["segments"])}


def round_evidence(store: RunStore, journal: Dict[str, Any],
                   extra_events: Sequence[Any] = (),
                   extra_frames: Sequence[Any] = ()) -> Tuple[List[PerfEvent], List[FrameStat]]:
    """Merged events/frames of a round, including the bytes this call still holds."""
    events: List[PerfEvent] = []
    frames: Dict[Tuple[Optional[int], str, int, int], FrameStat] = {}
    for item in journal.get("segments") or []:
        segment = store.json(item["path"])
        for raw in segment.get("events") or []:
            events.append(_replay_event(raw))
        for raw in segment.get("frames") or []:
            frame = _replay_frame(raw)
            frames.setdefault(frame.key, frame)
    for event in extra_events:
        events.append(event if isinstance(event, PerfEvent) else _replay_event(event))
    for frame in extra_frames:
        item = frame if isinstance(frame, FrameStat) else _replay_frame(frame)
        frames.setdefault(item.key, item)
    return events, list(frames.values())


def unit_screenshot(device: RunDevice, index: int, attempt: Dict[str, Any]) -> Optional[str]:
    shot = attempt.get("body_screenshot") or {}
    if not shot.get("ok"):
        return None
    source = Path(str(shot.get("path") or ""))
    relative = "screenshots/unit-cold%02d.png" % index
    target = device.store.path(relative)
    data = source.read_bytes()
    if hashlib.sha256(data).hexdigest() != shot.get("sha256"):
        raise EvidenceError("SCREENSHOT_DRIFT", "captured screenshot digest changed")
    if target.exists():
        if target.read_bytes() != data:
            raise EvidenceError("SCREENSHOT_DRIFT", "bound screenshot differs from the capture")
        device.store.ref(relative)
    else:
        device.store.put(relative, data, exclusive=True)
    return relative


def build_unit_record(store: RunStore, state: Dict[str, Any], manifest: Dict[str, Any],
                      unit: int, attempt_number: int, collected: Dict[str, Any],
                      device: RunDevice,
                      command_sha256: Optional[Sequence[str]] = None) -> Dict[str, Any]:
    """Freeze one unit: evidence plus the command chain that produced it.

    A resumed round spreads its commands over several host calls, so the command
    chain is the whole authoritative attempt directory, not just this call.
    """
    planned, _ = attempt_command_files(store, unit, attempt_number)
    if len(planned) != device.count:
        raise EvidenceError("COMMAND_COUNT_MISMATCH",
                            "%d planned commands on disk != %d dispatched"
                            % (len(planned), device.count))
    record = {"schema": SEGMENT_SCHEMA, "run_id": manifest["run_id"],
              "manifest_sha256": state["manifest_sha256"], "unit": unit,
              "attempt_number": attempt_number, "cmd": manifest["cmd"],
              "collected_ns": time.time_ns(),
              "previous_sha256": state["units"][unit - 2]["sha256"] if unit > 1 else None,
              "commands": len(planned),
              "command_sha256": list(command_sha256) if command_sha256 is not None
              else [row["receipt"]["sha256"] for row in device.command_log],
              "screenshot": None}
    if "preparation" in collected:
        record["preparation"] = collected["preparation"]
        record["preparation_verdict"] = collected["preparation_verdict"]
    record["attempt"] = attempt_evidence(collected["attempt"])
    if manifest["cmd"] == "epub":
        record["screenshot"] = unit_screenshot(device, unit, collected["attempt"])
    return record



def state_failure(code: str, message: str, v: Optional[Verdict]) -> None:
    if v is None:
        raise EvidenceError(code, message)
    v.fail(code, message)


def check_state(state: Any, manifest: Dict[str, Any], manifest_sha: str,
                v: Optional[Verdict] = None) -> None:
    if not isinstance(state, dict):
        state_failure("STATE_SCHEMA", "state.json is not an object", v)
        return
    if state.get("schema") != SEGMENT_SCHEMA:
        state_failure("STATE_SCHEMA", "state schema mismatch", v)
    if state.get("run_id") != manifest.get("run_id"):
        state_failure("STATE_RUN_ID", "state run_id drifted from the manifest", v)
    if state.get("cmd") != manifest.get("cmd"):
        state_failure("STATE_CMD", "state command drifted from the manifest", v)
    if state.get("manifest_sha256") != manifest_sha:
        state_failure("STATE_MANIFEST", "state and manifest hashes differ", v)
    units = state.get("units")
    if not isinstance(units, list) or len(units) != manifest.get("unit_total") or \
            state.get("unit_total") != manifest.get("unit_total"):
        state_failure("STATE_UNITS", "state unit table drifted from the manifest", v)


def reconcile_units(store: RunStore, state: Dict[str, Any]) -> None:
    """Adopt unit files that were published before a crash updated state.json."""
    directory = store.path("units")
    names = sorted(path.name for path in directory.glob("*.json")) if directory.is_dir() else []
    expected = ["%03d.json" % number for number in range(1, state["unit_total"] + 1)]
    extra = [name for name in names if name not in expected]
    if extra:
        raise EvidenceError("UNIT_DUPLICATE", "unexpected unit records: " + ",".join(extra))
    previous = None
    for number, name in enumerate(expected, 1):
        slot = state["units"][number - 1]
        if name not in names:
            if slot.get("status") == "COMMITTED":
                raise EvidenceError("STATE_UNIT_MISMATCH", "state claims unit %d without a record" % number)
            continue
        ref = store.ref("units/" + name)
        if slot.get("status") == "COMMITTED":
            if slot.get("sha256") != ref["sha256"]:
                raise EvidenceError("UNIT_EVIDENCE_DRIFT", "unit record changed: " + name)
        else:
            record = store.json("units/" + name)
            if record.get("unit") != number:
                raise EvidenceError("UNIT_INDEX_MISMATCH", name)
            if record.get("run_id") != state["run_id"] or \
                    record.get("manifest_sha256") != state["manifest_sha256"]:
                raise EvidenceError("UNIT_MANIFEST_MISMATCH", name)
            if record.get("previous_sha256") != previous:
                raise EvidenceError("UNIT_CHAIN_MISMATCH", "unit chain broken at " + name)
            slot.update(status="COMMITTED", sha256=ref["sha256"], attempts=record.get("attempt"),
                        commands=record.get("commands"))
            state["reconciled"].append({"unit": number, "sha256": ref["sha256"],
                                        "at_ns": time.time_ns()})
        previous = ref["sha256"]
    missing = [number for number in range(1, state["unit_total"] + 1)
               if state["units"][number - 1].get("status") != "COMMITTED"]
    state["next_unit"] = min(missing) if missing else state["unit_total"] + 1


def record_interruption(store: RunStore, state: Dict[str, Any], unit: int, attempt_number: int,
                        exc: TransportError) -> None:
    # Seal the round journal so nobody can mistake an aborted segment for a
    # paused one and resume on top of an input whose outcome is unknown.
    try:
        journal = load_round_journal(store, unit, attempt_number)
        if journal is not None and journal.get("stage") != "done":
            journal["status"] = "ABORTED"
            journal["detail"] = "%s: %s" % (exc.code, exc.message)
            write_round_journal(store, journal)
    except (EvidenceError, OSError, ValueError):
        pass
    state["interruptions"].append({"unit": unit, "attempt": attempt_number, "code": exc.code,
                                   "message": exc.message, "command_id": exc.command_id,
                                   "at_ns": time.time_ns()})
    state["failures"].append({"code": exc.code, "message": exc.message})
    slot = state["units"][unit - 1]
    if slot.get("status") != "COMMITTED":
        slot["attempts"] = max(int(slot.get("attempts") or 0), attempt_number)
        slot["status"] = "INTERRUPTED"
    state["updated_ns"] = time.time_ns()
    store.put("state.json", state)


def step_summary(state: Dict[str, Any], manifest: Dict[str, Any]) -> Dict[str, Any]:
    return {"run_id": manifest["run_id"], "cmd": manifest["cmd"],
            "unit_total": manifest["unit_total"], "next_unit": state.get("next_unit"),
            "commands": state.get("command_count"),
            "committed": [item["unit"] for item in state["units"] if item.get("status") == "COMMITTED"],
            "paused": [item["unit"] for item in state["units"] if item.get("status") == "IN_PROGRESS"],
            "interrupted": [item["code"] for item in state.get("interruptions") or []]}


def verify_run_inputs(store: RunStore, manifest: Dict[str, Any], current: Dict[str, Any]) -> None:
    for key in SOURCE_FIELDS:
        if manifest["source"].get(key) != current.get(key):
            raise EvidenceError("SOURCE_DRIFT", "source tree changed since init: " + key)
    if collector_hashes() != manifest.get("collector"):
        raise EvidenceError("COLLECTOR_DRIFT", "collector scripts changed since init")
    for key, ref in (manifest.get("bindings") or {}).items():
        store.get(ref)


def verify_bindings(store: RunStore, manifest: Dict[str, Any], v: Verdict) -> None:
    for key, ref in (manifest.get("bindings") or {}).items():
        try:
            store.get(ref)
        except EvidenceError as exc:
            v.fail(exc.code, "%s: %s" % (key, exc.message))
    for key, ref in (manifest.get("snapshots") or {}).items():
        try:
            store.get(ref)
        except EvidenceError as exc:
            v.fail(exc.code, "%s: %s" % (key, exc.message))


def load_device(store: RunStore, state: Dict[str, Any], manifest: Dict[str, Any],
                v: Verdict) -> Dict[str, Any]:
    try:
        ref = store.ref("device.json")
        doc = store.json("device.json")
    except EvidenceError:
        v.fail("DEVICE_EVIDENCE_MISSING", "no device snapshot was recorded")
        return {}
    if not state.get("device_sha256") or state["device_sha256"] != ref["sha256"]:
        v.fail("DEVICE_DRIFT", "device snapshot changed after the first step")
    if doc.get("run_id") != manifest["run_id"]:
        v.fail("DEVICE_DRIFT", "device snapshot belongs to another run")
    if doc.get("installed_apk_sha256") != manifest["apk_sha256"]:
        v.fail("INSTALLED_APK_MISMATCH", "measured process did not run the bound APK")
    if doc.get("collector") != manifest["collector"]:
        v.fail("COLLECTOR_DRIFT", "device snapshot was taken by another collector build")
    if state.get("identity_sha256"):
        identity = (manifest.get("snapshots") or {}).get("identity", {}).get("sha256")
        if identity != state["identity_sha256"]:
            v.fail("IDENTITY_DRIFT", "init identity snapshot changed")
    return doc


def load_units(store: RunStore, state: Dict[str, Any], manifest: Dict[str, Any],
               v: Verdict) -> List[Tuple[int, Dict[str, Any], Dict[str, Any]]]:
    rows = []
    previous = None
    for number in range(1, manifest["unit_total"] + 1):
        slot = state["units"][number - 1]
        name = "units/%03d.json" % number
        try:
            ref = store.ref(name)
            record = store.json(name)
        except EvidenceError:
            v.fail("UNIT_MISSING", "unit %d has no retained record" % number)
            break
        if slot.get("status") != "COMMITTED" or slot.get("sha256") != ref["sha256"]:
            v.fail("UNIT_STATE_MISMATCH", "unit %d is not committed with this evidence" % number)
        if record.get("unit") != number or record.get("run_id") != manifest["run_id"] or \
                record.get("manifest_sha256") != state.get("manifest_sha256"):
            v.fail("UNIT_MANIFEST_MISMATCH", "unit %d does not belong to this run/manifest" % number)
        if record.get("previous_sha256") != previous:
            v.fail("UNIT_CHAIN_MISMATCH", "unit chain broken at %d" % number)
        previous = ref["sha256"]
        rows.append((number, ref, record))
    prefix = "units/%03d.json" % (manifest["unit_total"] + 1)
    if store.path(prefix).exists():
        v.fail("UNIT_DUPLICATE", "extra unit record beyond the frozen total")
    return rows


def replay_attempt(record: Dict[str, Any]) -> Dict[str, Any]:
    attempt = dict(record.get("attempt") or {})
    attempt["events"] = [_replay_event(raw) for raw in attempt.get("events") or []]
    attempt["frames"] = [_replay_frame(item) for item in attempt.get("frames") or []]
    entry = attempt.get("entry")
    if isinstance(entry, dict):
        entry = dict(entry)
        entry["events"] = [_replay_event(raw) for raw in entry.get("events") or []]
        entry["frames"] = [_replay_frame(item) for item in entry.get("frames") or []]
        attempt["entry"] = entry
    return attempt


def _replay_event(raw: Any) -> PerfEvent:
    event = parse_perf_line(str(raw))
    if event is None:
        raise EvidenceError("UNIT_EVENT_UNPARSABLE", "retained event does not parse: %.80s" % (raw,))
    return event


def _replay_frame(item: Any) -> FrameStat:
    if isinstance(item, FrameStat):
        return item
    try:
        return FrameStat(**item)
    except TypeError:
        raise EvidenceError("UNIT_FRAME_UNPARSABLE", "retained frame does not parse: %.80s" % (item,))


def audit_unit_commands(store: RunStore, record: Dict[str, Any], manifest: Dict[str, Any],
                        v: Verdict) -> List[Dict[str, Any]]:
    """Prove every claimed command of the authoritative attempt exists on disk."""
    unit, attempt = record.get("unit"), record.get("attempt_number")
    if not isinstance(attempt, int) or attempt < 1:
        raise EvidenceError("COMMAND_EVIDENCE_MISSING", "unit record has no attempt number")
    directory = "commands/%03d/%02d" % (unit, attempt)
    base = store.path(directory)
    names = sorted(path.name for path in base.glob("*.planned.json")) if base.is_dir() else []
    if not names:
        raise EvidenceError("COMMAND_EVIDENCE_MISSING", directory)
    if len(names) != record.get("commands"):
        raise EvidenceError("COMMAND_COUNT_MISMATCH",
                            "%d planned files != %d recorded" % (len(names), record.get("commands")))
    if list(base.glob("*.tmp-*")):
        raise EvidenceError("RAW_PARTIAL_WRITE", "temporary files retained in " + directory)
    rows = []
    for number, name in enumerate(names, 1):
        if name != "%06d.planned.json" % number:
            raise EvidenceError("COMMAND_SEQUENCE_GAP", "%s/%s" % (directory, name))
        rows.append(read_command(store, "%s/%06d" % (directory, number), manifest, v))
    return rows


def read_command(store: RunStore, prefix: str, manifest: Dict[str, Any],
                 v: Verdict) -> Dict[str, Any]:
    planned = store.json(prefix + ".planned.json")
    if planned.get("command_id") != prefix or planned.get("state") != "PLANNED":
        raise EvidenceError("COMMAND_DRIFT", "planned record drifted: " + prefix)
    argv = planned.get("argv") or []
    if len(argv) < 3 or argv[0] != manifest.get("adb") or argv[1] != "-s" or \
            argv[2] != manifest.get("serial"):
        raise EvidenceError("COMMAND_DRIFT", "command does not address the frozen device: " + prefix)
    timeout = planned.get("timeout_seconds")
    if not isinstance(timeout, (int, float)) or not 0 < timeout <= SEGMENT_POLICY["adb_seconds"]:
        raise EvidenceError("COMMAND_TIMEOUT_BOUND", "command timeout exceeds the segment policy: " + prefix)
    dispatch = store.json(prefix + ".dispatch.json")
    if dispatch != dict(planned, state="DISPATCHING"):
        raise EvidenceError("COMMAND_DRIFT", "dispatch record drifted: " + prefix)
    receipt = store.json(prefix + ".receipt.json")
    if receipt.get("command_id") != prefix or receipt.get("argv") != argv:
        raise EvidenceError("COMMAND_DRIFT", "receipt does not match the dispatch: " + prefix)
    for key in ("start_ns", "end_ns"):
        if not isinstance(receipt.get(key), int):
            raise EvidenceError("COMMAND_DRIFT", "missing host timing in " + prefix)
    if receipt["end_ns"] < receipt["start_ns"]:
        raise EvidenceError("COMMAND_DRIFT", "host timing runs backwards in " + prefix)
    code = receipt.get("returncode")
    if code is not None and not isinstance(code, int):
        raise EvidenceError("COMMAND_DRIFT", "exit code is not an integer in " + prefix)
    if receipt.get("timed_out"):
        raise EvidenceError("COMMAND_TIMEOUT", "aborted command retained: " + prefix)
    out = store.get(receipt["stdout"])
    err = store.get(receipt["stderr"])
    absent = command_tail(argv) == ["pidof", PKG] and code == 1 and not out.strip() and not err.strip()
    if code != 0 and not absent:
        v.fail("COMMAND_FAILED", "%s exit=%s" % (prefix, code))
    if receipt.get("error"):
        v.fail("COMMAND_FAILED", "%s %s" % (prefix, receipt["error"]))
    return {"unit": planned.get("unit"), "attempt": planned.get("attempt"), "command_id": prefix,
            "argv": argv, "phase": planned.get("phase"), "timeout_seconds": timeout,
            "returncode": code, "timed_out": False, "error": receipt.get("error"),
            "stdout": receipt["stdout"], "stderr": receipt["stderr"],
            "receipt": store.ref(prefix + ".receipt.json")}


def audit_round_journal(store: RunStore, record: Dict[str, Any],
                        manifest: Dict[str, Any], v: Verdict) -> Dict[str, Any]:
    """Cross-check a mid-round journal against the unit record that committed it."""
    if record.get("cmd") != "epub-turn":
        return {}
    journal = load_round_journal(store, record.get("unit"), record.get("attempt_number"))
    if journal is None:
        # Every staged epub-turn attempt journals itself before it dispatches a
        # command, so a missing journal is a broken or edited run, not a legacy
        # record: the round can no longer be cross-checked, and that is a fail.
        raise EvidenceError("ROUND_NOT_CLOSED",
                            "unit %s committed a staged round without its journal"
                            % record.get("unit"))
    if journal.get("run_id") != manifest["run_id"] or \
            journal.get("manifest_sha256") != record.get("manifest_sha256"):
        raise EvidenceError("ROUND_SEGMENT_IDENTITY", "round journal belongs to another run")
    if journal.get("status") not in ("COMPLETED", "FAILED"):
        raise EvidenceError("ROUND_NOT_CLOSED",
                            "unit %s committed a round journal that was never sealed"
                            % record.get("unit"))
    if int(journal.get("commands") or 0) != int(record.get("commands") or 0):
        raise EvidenceError("ROUND_SEGMENT_DRIFT",
                            "journal commands %s != committed %s"
                            % (journal.get("commands"), record.get("commands")))
    turns: List[int] = []
    previous = 0
    for item in journal.get("segments") or []:
        if store.ref(item["path"])["sha256"] != item["sha256"]:
            raise EvidenceError("ROUND_SEGMENT_DRIFT", "segment digest changed: " + item["path"])
        segment = store.json(item["path"])
        count = int(item.get("commands") or 0)
        if count < previous or count > int(journal.get("commands") or 0):
            raise EvidenceError("ROUND_SEGMENT_DRIFT",
                                "segment %s command high-water mark %d is out of order"
                                % (item.get("path"), count))
        previous = count
        turns.extend(int(page["turn"]) for page in segment.get("pages") or [])
    if sorted(turns) != list(range(1, len(turns) + 1)):
        raise EvidenceError("ROUND_SEGMENT_DRIFT",
                            "unit %s round turns are not one 1..n sequence" % record.get("unit"))
    if journal.get("status") == "COMPLETED" and len(turns) != int(journal.get("turns") or 0):
        raise EvidenceError("ROUND_SEGMENT_DRIFT",
                            "unit %s round completed with %d/%d turns"
                            % (record.get("unit"), len(turns), journal.get("turns")))
    return {"segments": len(journal.get("segments") or []), "turns": len(turns),
            "commands": journal.get("commands"), "journal": round_paths(
                record.get("unit"), record.get("attempt_number"))["journal"],
            "segment_count": len(journal.get("segments") or [])}


def verify_unit_evidence(store: RunStore, record: Dict[str, Any], rows: List[Dict[str, Any]]) -> None:
    """Every retained event, frame and PNG must exist inside the journalled raw bytes."""
    attempt = record.get("attempt") or {}
    texts: Dict[str, List[str]] = {}
    for row in rows:
        kind = command_class(row["argv"])
        if kind in ("logcat", "framestats", "gfxinfo"):
            texts.setdefault(kind, []).append(store.get(row["stdout"]).decode("utf-8", "replace"))
    lines = set()
    for text in texts.get("logcat", []):
        lines.update(line.strip() for line in text.splitlines() if line.strip())
    for raw in list(attempt.get("raw_logcat") or []) + [event for event in attempt.get("events") or []]:
        if str(raw).strip() not in lines:
            raise EvidenceError("EVENT_NOT_IN_RAW",
                                "unit %s retains an event absent from raw logcat: %.80s"
                                % (record.get("unit"), raw))
    observed = set()
    for text in texts.get("framestats", []):
        try:
            observed.update(frame.key for frame in parse_framestats(text))
        except PerfParseError:
            continue
    for item in attempt.get("frames") or []:
        frame = item if isinstance(item, FrameStat) else FrameStat(**item)
        if frame.key not in observed:
            raise EvidenceError("FRAME_NOT_IN_RAW",
                                "unit %s retains a frame absent from raw framestats" % record.get("unit"))
    gfx_text = attempt.get("gfx_text") or ""
    if gfx_text and gfx_text not in texts.get("gfxinfo", []):
        raise EvidenceError("GFX_TEXT_NOT_IN_RAW",
                            "unit %s gfxinfo report is not a retained command output" % record.get("unit"))
    shot = attempt.get("body_screenshot") or {}
    if shot.get("ok"):
        relative = record.get("screenshot")
        if not relative:
            raise EvidenceError("SCREENSHOT_NOT_IN_RUN", "unit %s claims a PNG without a bound copy" % record.get("unit"))
        ref = store.ref(relative)
        if shot.get("sha256") and shot["sha256"] != ref["sha256"]:
            raise EvidenceError("SCREENSHOT_DRIFT", "bound PNG differs from the measured capture")


def check_run_args(args: Dict[str, Any], manifest: Dict[str, Any], v: Verdict) -> None:
    if args.get("cmd") and args["cmd"] != manifest["cmd"]:
        v.fail("RUN_ARG_DRIFT", "collector command %r != %r" % (args["cmd"], manifest["cmd"]))
    for key in ("book_title", "chapter", "toc_match"):
        expected = manifest.get(key)
        given = args.get(key)
        if given in (None, "") and expected in (None, ""):
            continue
        if given is not None and given != expected:
            v.fail("RUN_ARG_DRIFT", "%s %r != %r" % (key, given, expected))


def report_arguments(store: RunStore, manifest: Dict[str, Any], args: Dict[str, Any]) -> Dict[str, Any]:
    paths = input_paths(store, manifest)
    return {"cmd": manifest["cmd"], "run_dir": str(store.root), "stage": "finish",
            "apk": manifest["apk"], "epub": paths["host_epub"],
            "book_title": manifest["book_title"], "chapter": manifest["chapter"],
            "page": manifest["page"], "cold": manifest["cold"], "turns": manifest["turns"],
            "rounds": manifest["rounds"], "turn_mode": manifest["turn_mode"],
            "book_id": manifest["book_id"], "toc_match": manifest["toc_match"],
            "serial": manifest["serial"], "adb": manifest["adb"],
            "release_evidence": paths["release"], "fixture_evidence": paths["fixture"],
            "screenshot_dir": "",
            "json_out": os.path.abspath(args["json_out"]) if args.get("json_out") else ""}


def init_run(args: Dict[str, Any], source: Any = None, fault: Any = None) -> Tuple[Dict[str, Any], int]:
    """Bind inputs, the frozen source tree and one run directory. No adb."""
    cmd = args.get("cmd")
    if cmd not in UNIT_TOTAL:
        raise EvidenceError("RUN_COMMAND", "unknown collection command: %r" % (cmd,))
    if not args.get("run_dir"):
        raise EvidenceError("RUN_DIR_REQUIRED", "--run-dir is required for the staged path")
    current = current_source(source)
    apk, apk_sha, inputs, v, _ = offline_inputs(args, cmd, current)
    if not v.pass_:
        return dict({"status": "INPUT_REJECTED", "stage": "init",
                     "run_dir": os.path.abspath(args["run_dir"]), "cmd": cmd}, **v.as_dict()), 1
    store = RunStore(args["run_dir"], create=True, fault=fault)
    bindings = stage_inputs(store, inputs)
    copied = dict(args)
    copied["release_evidence"] = str(store.root / inputs["release"]["relative"])
    copied["fixture_evidence"] = str(store.root / inputs["fixture"]["relative"])
    copied["epub"] = str(store.root / inputs["host_epub"]["relative"])
    _, _, _, check, _ = offline_inputs(copied, cmd, current)
    if not check.pass_:
        raise EvidenceError("BINDING_FAILED", ",".join(sorted({item["code"] for item in check.failures})))
    manifest = build_manifest(cmd, args, apk, apk_sha, current, bindings)
    source_ref = store.put("source.json",
                           {key: value for key, value in current.items() if key != "git_status"},
                           exclusive=True)
    identity = {"schema": SEGMENT_SCHEMA, "run_id": manifest["run_id"], "cmd": cmd,
                "run_dir": str(store.root), "created_ns": time.time_ns(),
                "serial": manifest["serial"], "apk_sha256": apk_sha,
                "source_tree_sha256": manifest["source"].get("source_tree_sha256"),
                "collector": manifest["collector"], "unit_total": manifest["unit_total"],
                "policy": dict(SEGMENT_POLICY), "args": {"book_title": manifest["book_title"],
                                                         "chapter": manifest["chapter"],
                                                         "page": manifest["page"],
                                                         "toc_match": manifest["toc_match"]}}
    identity_ref = store.put("identity.json", identity, exclusive=True)
    manifest["snapshots"] = {"source": source_ref, "identity": identity_ref}
    manifest_ref = store.put("manifest.json", manifest, exclusive=True)
    state = new_state(manifest, manifest_ref["sha256"])
    state["identity_sha256"] = identity_ref["sha256"]
    store.put("state.json", state)
    return {"status": "INITIALIZED_NOT_EXECUTED", "stage": "init", "run_id": manifest["run_id"],
            "run_dir": str(store.root), "cmd": cmd, "unit_total": manifest["unit_total"],
            "manifest": manifest_ref, "source": source_ref, "identity": identity_ref,
            "bindings": bindings, "next_stage": "step"}, 0


def pause_round(store: RunStore, state: Dict[str, Any], manifest: Dict[str, Any],
                unit: int, attempt_number: int, journal: Dict[str, Any],
                deadline: SegmentDeadline, count: int,
                stage: Optional[str] = None) -> Tuple[Dict[str, Any], int]:
    """Close this host call cleanly: the round keeps its process, page and window."""
    slot = state["units"][unit - 1]
    slot.update(status="IN_PROGRESS", attempts=attempt_number, commands=count)
    state["updated_ns"] = time.time_ns()
    store.put("state.json", state)
    print("staged %s unit %d/%d: paused at %s, turn %d/%d, %d commands"
          % (manifest["cmd"], unit, manifest["unit_total"], stage or journal["stage"],
             journal["next_turn"], journal["turns"], count))
    return dict(step_summary(state, manifest), status="ROUND_PAUSED", unit=unit,
                attempt=attempt_number, stage=stage or journal["stage"],
                next_turn=journal["next_turn"], turns=journal["turns"], commands=count,
                remaining_seconds=round(deadline.remaining(), 1)), 0


def step_run(args: Dict[str, Any], runner: Any = subprocess.run, source: Any = None,
             clock: Any = time.monotonic_ns, sleep: Any = time.sleep,
             dump_parser: Any = None, fault: Any = None) -> Tuple[Dict[str, Any], int]:
    """Collect exactly one unit inside one bounded host call."""
    store = RunStore(args.get("run_dir") or "", fault=fault)
    with store.writer():
        manifest, manifest_sha = store.manifest()
        state = store.json("state.json")
        check_state(state, manifest, manifest_sha)
        reconcile_units(store, state)
        if state.get("pending"):
            return dict(step_summary(state, manifest), status="OBSERVE_REQUIRED",
                        code="GESTURE_UNKNOWN",
                        message="an input gesture has an unknown outcome; the run stays read-only"), 3
        unit = state["next_unit"]
        if unit > manifest["unit_total"]:
            return dict(step_summary(state, manifest), status="ALL_UNITS_COMMITTED"), 0
        deadline = SegmentDeadline(clock=clock, sleep=sleep)
        attempt_number = int(state["units"][unit - 1].get("attempts") or 0) + 1
        resumed = None
        if manifest["cmd"] == "epub-turn":
            number = resumable_attempt(store, state["units"][unit - 1], unit)
            if number:
                resumed = load_round_journal(store, unit, number)
                attempt_number = number
        try:
            if resumed is None and deadline.remaining() < SEGMENT_POLICY["unit_reserve_seconds"]:
                raise TransportError("BUDGET_LOW", "insufficient budget for a whole unit")
            current = current_source(source)
            verify_run_inputs(store, manifest, current)
            if resumed is not None:
                verify_round_tail(store, resumed)
                if deadline.remaining() < ROUND_STAGE_RESERVE_SECONDS["finish"]:
                    # Keep the paused round untouched: a fresh host call continues
                    # it, an interruption here would only throw its evidence away.
                    return pause_round(store, state, manifest, unit, attempt_number, resumed,
                                       deadline, int(resumed.get("commands") or 0))
            device = RunDevice(store, state, manifest, deadline, unit, attempt_number,
                               runner, dump_parser,
                               start_index=int((resumed or {}).get("commands") or 0))
            doc = ensure_device(device, store, state, manifest, current)
            device.phase = "unit"
            journal = None
            if manifest["cmd"] == "epub":
                collected = collect_cold_unit(device, args, doc, unit)
            else:
                journal = resumed or new_round_journal(unit, attempt_number, manifest,
                                                       state["manifest_sha256"])
                device.phase = "verify" if journal["stage"] == "done" else journal["stage"]
                try:
                    outcome = collect_turn_unit(device, args, journal)
                finally:
                    write_round_journal(store, journal)
                if outcome == "PAUSE":
                    return pause_round(store, state, manifest, unit, attempt_number, journal,
                                       deadline, device.count)
                if deadline.remaining() < ROUND_STAGE_RESERVE_SECONDS["verify"]:
                    # The round itself is finished; commit it from a fresh call.
                    return pause_round(store, state, manifest, unit, attempt_number, journal,
                                       deadline, device.count, stage="verify")
            device.phase = "verify"
            verify_device(device, doc)
            device.phase = "commit"
            if journal is not None:
                # The verify commands belong to the same attempt directory, so the
                # journal must account for them before the record freezes.
                journal["commands"] = device.count
                write_round_journal(store, journal)
            if journal is None:
                record = build_unit_record(store, state, manifest, unit, attempt_number,
                                           collected, device)
            else:
                merged = {"attempt": merge_round_attempt(store, journal)}
                record = build_unit_record(store, state, manifest, unit, attempt_number,
                                           merged, device,
                                           attempt_receipt_digests(store, unit, attempt_number))
            ref = store.put("units/%03d.json" % unit, record, exclusive=True)
            slot = state["units"][unit - 1]
            slot.update(status="COMMITTED", sha256=ref["sha256"], attempts=attempt_number,
                        commands=record["commands"])
            state["next_unit"] = unit + 1
            state["updated_ns"] = time.time_ns()
            store.put("state.json", state)
            print("staged %s unit %d/%d: committed" % (manifest["cmd"], unit, manifest["unit_total"]))
            return dict(step_summary(state, manifest), status="UNIT_COMMITTED", unit=unit,
                        unit_sha256=ref["sha256"]), 0
        except TransportError as exc:
            record_interruption(store, state, unit, attempt_number, exc)
            return dict(step_summary(state, manifest), status="INTERRUPTED", unit=unit,
                        code=exc.code, message=exc.message, retryable=exc.retryable), 3
        except EvidenceError as exc:
            state["failures"].append({"code": exc.code, "message": exc.message})
            state["updated_ns"] = time.time_ns()
            store.put("state.json", state)
            return dict(step_summary(state, manifest), status="REJECTED", unit=unit,
                        code=exc.code, message=exc.message), 1
        except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as exc:
            state["failures"].append({"code": "SEGMENT_ERROR", "message": str(exc)})
            state["updated_ns"] = time.time_ns()
            store.put("state.json", state)
            return dict(step_summary(state, manifest), status="FAILED", unit=unit,
                        code="SEGMENT_ERROR", message=str(exc)), 1


def finish_run(args: Dict[str, Any], source: Any = None) -> Tuple[Dict[str, Any], int]:
    """Replay retained evidence through the shared graders. Local only."""
    store = RunStore(args.get("run_dir") or "")
    manifest, manifest_sha = store.manifest()
    v = Verdict()
    audit_rows: List[Dict[str, Any]] = []
    state = store.json("state.json")
    check_state(state, manifest, manifest_sha, v)
    check_run_args(args, manifest, v)
    try:
        current = current_source(source)
    except (OSError, subprocess.SubprocessError) as exc:
        current = {}
        v.fail("SOURCE_TREE_UNAVAILABLE", str(exc))
    for key in SOURCE_FIELDS:
        if manifest["source"].get(key) != current.get(key):
            v.fail("SOURCE_DRIFT", "source tree changed since init: " + key)
            break
    if collector_hashes() != manifest.get("collector"):
        v.fail("COLLECTOR_DRIFT", "collector scripts changed since init")
    verify_bindings(store, manifest, v)
    doc = load_device(store, state, manifest, v)
    units = load_units(store, state, manifest, v)
    segment_info: Dict[int, Dict[str, Any]] = {}
    for index, ref, record in units:
        try:
            rows = audit_unit_commands(store, record, manifest, v)
            audit_rows.extend(rows)
            verify_unit_evidence(store, record, rows)
            info = audit_round_journal(store, record, manifest, v)
            if info:
                # Segmentation is how the round was collected, not a measurement
                # fault: the graders above already judged the merged evidence.
                # Keep it visible in the report (and on stdout) without touching
                # the verdict, so a resumed 04 can still be acceptance-PASS.
                segment_info[index] = info
                print("unit %d: round retained %d segment(s), %d turn(s), %d command(s)"
                      % (index, info["segments"], info["turns"], info["commands"]))
        except EvidenceError as exc:
            v.fail(exc.code, "unit %d: %s" % (index, exc.message))
    for item in state.get("interruptions") or []:
        v.diagnostic("SEGMENT_INTERRUPTED", "unit %s attempt %s: %s (%s)" % (
            item.get("unit"), item.get("attempt"), item.get("code"), item.get("command_id")))
    if state.get("pending"):
        v.fail("GESTURE_UNKNOWN",
               "an input gesture has an unknown outcome: " + str(state["pending"].get("command_id")))
    if state.get("reconciled"):
        v.diagnostic("STATE_RECONCILED", "%d unit record(s) adopted from disk" % len(state["reconciled"]))
    app = (doc or {}).get("app") or {}
    info = (doc or {}).get("device") or {}
    preflight = (doc or {}).get("preflight") or {}
    if doc:
        replayed, pv = device_preflight(store, manifest, app, info, current or manifest["source"])
        if pv.pass_:
            preflight = replayed
        for failure in pv.failures:
            v.fail(failure["code"], "preflight replay: " + failure["message"])
    report_args = report_arguments(store, manifest, args)
    runs: List[Dict[str, Any]] = []
    all_events: List[PerfEvent] = []
    samples: List[float] = []
    frame_samples: List[float] = []
    seen_pids: set = set()
    total_window_frames = 0
    total_janky = 0
    for index, ref, record in units:
        try:
            attempt = replay_attempt(record)
        except EvidenceError as exc:
            v.fail(exc.code, "unit %d: %s" % (index, exc.message))
            continue
        if manifest["cmd"] == "epub":
            seeded = record.get("preparation_verdict") or {}
            run_v = Verdict(pass_=bool(seeded.get("pass", True)),
                            failures=[dict(item) for item in seeded.get("failures") or []],
                            diagnostics=[dict(item) for item in seeded.get("diagnostics") or []])
            all_events.extend(attempt.get("events", []))
            runs.append(epub_cold_report(attempt, index - 1, run_v, seen_pids, samples,
                                         frame_samples, record.get("preparation") or {}))
            merge_verdict(v, run_v)
        else:
            run_v = Verdict()
            all_events.extend(attempt.get("events", []))
            report, gfx = turn_round_report(attempt, index - 1, info, run_v, frame_samples)
            total_window_frames += gfx["total_frames"]
            total_janky += gfx.get("janky_frames") or 0
            runs.append(report)
            merge_verdict(v, run_v)
    if manifest["cmd"] == "epub":
        report = build_epub_report(report_args, preflight, app, info, v, runs, all_events,
                                   samples, frame_samples)
    else:
        report = build_turn_report(report_args, preflight, app, info, v, runs, all_events,
                                   frame_samples, total_window_frames, total_janky)
    report["raw_commands"] = audit_rows
    report["run"] = {"run_id": manifest["run_id"], "schema": SEGMENT_SCHEMA,
                     "manifest_sha256": manifest_sha, "run_dir": str(store.root),
                     "policy": manifest["policy"], "unit_total": manifest["unit_total"],
                     "committed_units": [{"unit": index, "sha256": ref["sha256"],
                                          "attempt": record.get("attempt_number"),
                                          "commands": record.get("commands"),
                                          "round_segments": (segment_info.get(index) or {}).get("segments"),
                                          "round_turns": (segment_info.get(index) or {}).get("turns"),
                                          "round_journal": (segment_info.get(index) or {}).get("journal")}
                                         for index, ref, record in units],
                     "source": manifest["source"], "collector": manifest["collector"],
                     "bindings": manifest["bindings"],
                     "interruptions": state.get("interruptions") or [],
                     "reconciled": state.get("reconciled") or [],
                     "device_sha256": state.get("device_sha256")}
    if args.get("json_out"):
        path = Path(args["json_out"]).resolve()
        path.parent.mkdir(parents=True, exist_ok=True)
        report["result_file"] = RunStore(path.parent).put(path.name, report)
    return report, 0 if report.get("pass") else (2 if report.get("verdict") == "DIAGNOSTIC" else 1)


def run_stage(cmd: str, stage: str, args: Dict[str, Any], runner: Any = subprocess.run,
              source: Any = None, clock: Any = time.monotonic_ns, sleep: Any = time.sleep,
              dump_parser: Any = None, fault: Any = None) -> Tuple[Dict[str, Any], int]:
    """Entry point for perf-device.py epub/epub-turn --stage {init,step,finish}."""
    args = dict(args)
    args["cmd"] = cmd
    args["stage"] = stage
    try:
        if stage == "init":
            return init_run(args, source=source, fault=fault)
        if stage == "step":
            return step_run(args, runner=runner, source=source, clock=clock, sleep=sleep,
                            dump_parser=dump_parser, fault=fault)
        if stage == "finish":
            return finish_run(args, source=source)
        return {"status": "BAD_STAGE", "stage": stage,
                "message": "stage must be init, step or finish"}, 1
    except EvidenceError as exc:
        return {"status": "EVIDENCE_ERROR", "stage": stage, "cmd": cmd,
                "code": exc.code, "message": exc.message}, 1
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError, TransportError) as exc:
        return {"status": "COLLECTOR_FAIL", "stage": stage, "cmd": cmd,
                "error": type(exc).__name__ + ": " + str(exc)}, 1
