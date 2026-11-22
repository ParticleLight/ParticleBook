#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""PBPDF v1 strict evaluation and bounded, append-only device collection.

No adb is called at import, init, evaluate, or the default read-only resume.
All fixtures/tests are portable; ignored docs are never runtime dependencies.
"""
from __future__ import annotations

import argparse
import contextlib
import copy
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid
from xml.etree import ElementTree as ET

PKG = "app.particlebook"
ACT = PKG + "/.android.MobileActivity"
SERIAL = "8A9Y0G2XJ"
BOOK_ID = 204
BOOK_TITLE = "QA text.pdf (6页文本)"
PDF_PATH = "/data/user/0/app.particlebook/files/_pb_files/qa-text.pdf"
PDF_SHA = "748fb6851f5a8a47c8e2bea6854b609b340311b5f699419bfba5ba2074f82c04"
POLICY = {"rounds": 5, "turns": 10, "pages": 3, "open_ms": 150,
          "opening_render_ms": 100, "opening_present_ms": 127,
          "turn_render_ms": 102, "min_samples": 5, "exit_mib": 20,
          "home_seconds": 8, "segment_seconds": 120, "adb_seconds": 30}
OUTCOMES = {"ok", "error", "cancelled", "closed"}
ORIGINS = {"demand", "prefetch", "lazy", "reused"}
EVENTS = {"session_start", "session_close", "op_queued", "op_begin", "op_end",
          "request_start", "request_end", "nav_begin", "nav_end", "page_present"}
LOG_HEADER = re.compile(
    r"^\s*(?:\d+\.\d+|\d\d-\d\d\s+\d\d:\d\d:\d\d\.\d+)\s+"
    r"(\d+)\s+\d+\s+[VDIWEF]\s+PBPDF\s*:\s*(.*)$")


class Invalid(ValueError):
    pass


def need(condition, message):
    if not condition:
        raise Invalid(message)


def integer(value, label, minimum=0, nullable=False):
    if value is None and nullable:
        return None
    need(type(value) is int and minimum <= value <= 2**63 - 1,
         label + " must be an integer >= " + str(minimum))
    return value


def identity(value, label, nullable=False):
    return integer(value, label, 1, nullable)


def fields(obj, *names):
    need(all(name in obj for name in names), "missing fields: " + ",".join(
        name for name in names if name not in obj))


def strict_json(text):
    def pairs(items):
        result = {}
        for key, value in items:
            need(key not in result, "duplicate JSON key: " + key)
            result[key] = value
        return result
    def constant(value):
        raise Invalid("non-finite JSON constant: " + value)
    return json.loads(text, object_pairs_hook=pairs, parse_constant=constant)


def canonical(obj):
    return json.dumps(obj, sort_keys=True, ensure_ascii=False,
                      separators=(",", ":"), allow_nan=False).encode("utf-8")


def sha(data):
    return hashlib.sha256(data).hexdigest()


def file_sha(path):
    return sha(Path(path).read_bytes())


def validate_event(e):
    need(type(e) is dict, "event must be an object")
    fields(e, "v", "process_id", "session_id", "seq", "event", "ts_ns")
    need(type(e["v"]) is int and e["v"] == 1, "unsupported PBPDF version")
    need(type(e["process_id"]) is str, "process_id must be a UUID string")
    try:
        need(str(uuid.UUID(e["process_id"])) == e["process_id"], "noncanonical process UUID")
    except (ValueError, AttributeError) as exc:
        raise Invalid("invalid process UUID") from exc
    for key in ("session_id", "seq"):
        identity(e[key], key)
    integer(e["ts_ns"], "ts_ns")
    name = e["event"]
    need(name in EVENTS, "unknown event: " + str(name))
    if name.startswith("op_"):
        fields(e, "op_id", "kind", "page", "request_id", "origin", "opening_member", "nav_id")
        identity(e["op_id"], "op_id")
        need(e["kind"] in {"open", "render"}, "invalid op kind")
        need(type(e["opening_member"]) is bool, "opening_member must be boolean")
        need(e["origin"] in ORIGINS, "invalid op origin")
        if e["kind"] == "open":
            need(e["page"] is None and e["request_id"] is None and e["nav_id"] is None,
                 "open has no page/request/nav")
            need(e["origin"] == "demand" and e["opening_member"], "open must be opening demand")
        else:
            integer(e["page"], "page")
            identity(e["request_id"], "request_id")
            identity(e["nav_id"], "nav_id", True)
        if name == "op_end":
            fields(e, "outcome", "page_count", "worker_end_ns")
            integer(e["worker_end_ns"], "worker_end_ns", nullable=True)
            integer(e["page_count"], "page_count", 1, True)
            need((e["kind"] == "open" and e["outcome"] == "ok") or e["page_count"] is None,
                 "only successful open carries page_count")
    elif name.startswith("request_"):
        fields(e, "request_id", "page")
        identity(e["request_id"], "request_id")
        integer(e["page"], "page")
        if name == "request_start":
            fields(e, "origin", "opening_member", "nav_id")
            need(e["origin"] in ORIGINS, "invalid request origin")
            need(type(e["opening_member"]) is bool, "opening_member must be boolean")
            identity(e["nav_id"], "nav_id", True)
            need(e["nav_id"] is None or e["origin"] == "demand", "only visible demand owns nav")
        else:
            fields(e, "outcome", "cache", "producer_op_id")
            need(e["cache"] in {None, "miss", "join", "hit", "reused"}, "invalid cache resolution")
            identity(e["producer_op_id"], "producer_op_id", True)
    elif name.startswith("nav_"):
        fields(e, "nav_id")
        identity(e["nav_id"], "nav_id")
        if name == "nav_begin":
            fields(e, "from_page", "target_page", "source")
            integer(e["from_page"], "from_page")
            integer(e["target_page"], "target_page", nullable=True)
            need(e["source"] in {"drag", "toc", "slider"}, "invalid nav source")
            need(e["source"] != "drag" or e["target_page"] is None, "drag target starts null")
        else:
            fields(e, "to_page", "outcome")
            integer(e["to_page"], "to_page", nullable=True)
    elif name == "page_present":
        fields(e, "request_id", "page", "producer_op_id", "visible_ns", "draw_ns", "commit_ts", "outcome")
        identity(e["request_id"], "request_id")
        identity(e["producer_op_id"], "producer_op_id", True)
        integer(e["page"], "page")
        for key in ("visible_ns", "draw_ns", "commit_ts"):
            integer(e[key], key, nullable=True)
        if e["outcome"] == "ok":
            need(all(e[k] is not None for k in ("visible_ns", "draw_ns", "commit_ts")),
                 "successful present needs visible/draw/commit")
            need(e["visible_ns"] <= e["draw_ns"] <= e["commit_ts"] == e["ts_ns"],
                 "present endpoint ordering")
    elif name == "session_start":
        fields(e, "book_id")
        identity(e["book_id"], "book_id")
    else:
        fields(e, "final_seq", "outcome", "cache_count", "cache_bytes", "source_close", "text_close")
        need(e["final_seq"] == e["seq"] and type(e["final_seq"]) is int, "final_seq must equal seq")
        for key in ("cache_count", "cache_bytes"):
            integer(e[key], key)
        for key in ("source_close", "text_close"):
            need(e[key] in {"ok", "error", "not_opened"}, "invalid close outcome")
        need(e["outcome"] in {"ok", "error"}, "invalid session close outcome")
    if "outcome" in e:
        need(e["outcome"] in OUTCOMES, "invalid outcome")
    return e


class EventLog:
    """Union by exact session sequence; raw occurrences remain in journal snapshots."""
    def __init__(self):
        self.rows = {}
        self.occurrences = []
        self.errors = []

    def add(self, text, snapshot=""):
        for number, line in enumerate(text.splitlines(), 1):
            if not re.search(r"\bPBPDF\s*:", line):
                continue
            try:
                need(len(line.encode("utf-8")) <= 16384, "oversized PBPDF line")
                match = LOG_HEADER.fullmatch(line)
                need(match is not None, "unrecognized PBPDF logcat header")
                pid = integer(int(match[1]), "logcat PID", 1)
                event = validate_event(strict_json(match[2]))
                key = (event["process_id"], event["session_id"], event["seq"])
                item = {"pid": pid, "body": event}
                previous = self.rows.get(key)
                need(previous is None or previous == item, "conflicting sequence body or PID")
                self.rows.setdefault(key, item)
                self.occurrences.append({"snapshot": snapshot, "line": number, "key": list(key)})
            except (ValueError, TypeError, KeyError) as exc:
                self.errors.append({"snapshot": snapshot, "line": number, "message": str(exc)})

    def session(self, pid, process_id=None, session_id=None):
        starts = [r["body"] for r in self.rows.values() if r["pid"] == pid and
                  r["body"]["event"] == "session_start" and r["body"]["book_id"] == BOOK_ID]
        if process_id is not None:
            starts = [e for e in starts if e["process_id"] == process_id and e["session_id"] == session_id]
        need(len(starts) == 1, "one fixed-book session_start required for measured PID")
        start = starts[0]
        chosen = [r for r in self.rows.values() if (r["body"]["process_id"], r["body"]["session_id"]) ==
                  (start["process_id"], start["session_id"])]
        need(all(r["pid"] == pid for r in chosen), "cross-PID session")
        need(not any(r["pid"] == pid and (r["body"]["process_id"], r["body"]["session_id"]) !=
                     (start["process_id"], start["session_id"]) for r in self.rows.values()),
             "multiple sessions/nonces in measured PID")
        return sorted((r["body"] for r in chosen), key=lambda e: e["seq"])


def summary(values):
    ordered = sorted(values)
    def rank(q):
        return ordered[max(0, math.ceil(len(ordered) * q) - 1)] if ordered else None
    return {"n": len(values), "values_ms": values, "p50_ms": rank(.5), "p95_ms": rank(.95)}


def inspect_session(events, sealed=True):
    """Validate native causality; no host gesture or duration guesses enter pairing."""
    need(events, "empty PBPDF session")
    need([e["seq"] for e in events] == list(range(1, len(events) + 1)), "sequence gap")
    need(events[0]["event"] == "session_start" and events[0]["book_id"] == BOOK_ID,
         "session_start/book mismatch")
    need(sum(e["event"] == "session_start" for e in events) == 1, "duplicate session_start")
    closes = [e for e in events if e["event"] == "session_close"]
    if sealed:
        need(len(closes) == 1 and events[-1] == closes[0], "missing/trailing session_close")
    else:
        need(len(closes) <= 1 and (not closes or events[-1] == closes[0]), "trailing session data")
    ops, requests, navs, presents = {}, {}, {}, {}
    for e in events:
        name = e["event"]
        if name.startswith("op_"):
            table, key = ops, e["op_id"]
        elif name.startswith("request_"):
            table, key = requests, e["request_id"]
        elif name.startswith("nav_"):
            table, key = navs, e["nav_id"]
        elif name == "page_present":
            need(e["request_id"] not in presents, "multiple presents for one request")
            presents[e["request_id"]] = e
            continue
        else:
            continue
        item = table.setdefault(key, {})
        need(name not in item, "duplicate object boundary: " + name)
        item[name] = e
    opens = [x for x in ops.values() if next(iter(x.values()))["kind"] == "open"]
    good_open = [x["op_end"] for x in opens if "op_end" in x and x["op_end"]["outcome"] == "ok"]
    need(len(opens) == 1, "one actual open operation required")
    if sealed:
        need(len(good_open) == 1, "open failed or missing")
    pages = good_open[0]["page_count"] if good_open else None
    if pages is not None:
        need(pages == POLICY["pages"], "runtime PDF page_count differs from fixed three-page fixture")
    def complete(item, begin, end):
        if sealed:
            need(begin in item and end in item, "unclosed object " + begin)
        if begin in item and end in item:
            need(item[begin]["ts_ns"] <= item[end]["ts_ns"], "negative object duration")
    first_nav = min((n["nav_begin"]["ts_ns"] for n in navs.values() if "nav_begin" in n), default=None)
    for key, item in requests.items():
        complete(item, "request_start", "request_end")
        start, end = item.get("request_start"), item.get("request_end")
        if not start:
            need(not sealed, "request end without start")
            continue
        if first_nav is not None:
            if start["origin"] == "reused":
                # reused presentations never belong to the opening set: Native reports
                # opening_member=false even when the cached page is visible before the
                # first real navigation, so the time-window rule does not apply.
                need(start["opening_member"] is False, "reused request claimed opening membership")
            else:
                need(start["opening_member"] == (start["ts_ns"] < first_nav), "opening membership changed/fabricated")
        if start["nav_id"] is not None:
            need(start["nav_id"] in navs, "request references unknown nav")
            navigation = navs[start["nav_id"]].get("nav_begin")
            need(navigation is not None and navigation["ts_ns"] <= start["ts_ns"],
                 "request precedes its claimed navigation")
        if end:
            need(start["page"] == end["page"], "request page changed")
            cache, producer = end["cache"], end["producer_op_id"]
            if end["outcome"] == "ok":
                need(cache is not None, "successful request has no cache resolution")
                need(cache != "miss" or producer is not None, "miss has no producer")
            if producer is not None:
                need(producer in ops and "op_queued" in ops[producer], "unknown producer")
                queued = ops[producer]["op_queued"]
                need(queued["kind"] == "render" and queued["page"] == start["page"], "producer page/kind mismatch")
                if cache == "miss":
                    need(queued["request_id"] == key, "miss transferred an existing producer")
                elif cache is None:
                    # A failed request may already have queued its own render, even
                    # though it never got a cache result (including prompt cancellation).
                    need(queued["request_id"] == key, "unresolved cache producer belongs to another request")
                else:
                    need(queued["request_id"] != key, "non-miss producer is its own render")
                if end["outcome"] == "ok":
                    terminal = ops[producer].get("op_end")
                    need(terminal is not None and terminal["outcome"] == "ok" and terminal["ts_ns"] <= end["ts_ns"],
                         "successful request without successful delivered producer")
            if end["cache"] == "reused":
                # A cache presentation that also owns a render op is a relabelled
                # demand miss; it would otherwise escape the 05 required gate.
                need(not any(item.get("op_queued", {}).get("request_id") == key and
                             item["op_queued"]["kind"] == "render" for item in ops.values()),
                     "reused presentation consumed a render operation")
        present = presents.get(key)
        if sealed and start["origin"] in {"demand", "reused"}:
            need(present is not None, "visible request missing present terminal")
        if present:
            need(present["page"] == start["page"] and start["origin"] != "prefetch", "present is offscreen/wrong page")
            need(end is not None and present["producer_op_id"] == end["producer_op_id"], "present producer mismatch")
            if present["outcome"] == "ok":
                need(end["outcome"] == "ok", "late present resurrected cancelled/failed request")
                need(start["ts_ns"] <= present["visible_ns"] and end["ts_ns"] <= present["draw_ns"],
                     "present before request/data ready")
    need(all(k in requests for k in presents), "present without request")
    for key, item in ops.items():
        complete(item, "op_queued", "op_end")
        queued, begin, end = (item.get(n) for n in ("op_queued", "op_begin", "op_end"))
        if not queued:
            need(not sealed, "operation without queued boundary")
            continue
        for e in item.values():
            for field in ("kind", "page", "request_id", "origin", "opening_member", "nav_id"):
                need(e[field] == queued[field], "producer identity/origin changed: " + field)
        if begin:
            need(queued["ts_ns"] <= begin["ts_ns"], "begin before queue")
        if end:
            need(begin is not None or end["outcome"] != "ok", "success without execution")
            if begin:
                need(begin["ts_ns"] <= end["ts_ns"], "delivery before execution")
            worker = end["worker_end_ns"]
            if worker is not None:
                need(begin is not None and begin["ts_ns"] <= worker <= end["ts_ns"], "invalid worker endpoint")
        if queued["kind"] == "render":
            need(queued["request_id"] in requests, "render without creator request")
            creator = requests[queued["request_id"]].get("request_start")
            need(creator is not None, "creator has no start")
            for field in ("page", "origin", "opening_member", "nav_id"):
                need(queued[field] == creator[field], "render transferred creator " + field)
            need(creator["ts_ns"] <= queued["ts_ns"], "render precedes creator")
    for item in navs.values():
        complete(item, "nav_begin", "nav_end")
        begin, end = item.get("nav_begin"), item.get("nav_end")
        if begin and end and end["outcome"] == "ok":
            need(end["to_page"] is not None and end["to_page"] != begin["from_page"], "ok nav did not move")
            if begin["target_page"] is not None:
                need(begin["target_page"] == end["to_page"], "nav missed explicit target")
    if pages is not None:
        for e in events:
            for key in ("page", "from_page", "to_page", "target_page"):
                if key in e and e[key] is not None:
                    need(0 <= e[key] < pages, "page outside runtime count")
    return {"events": events, "ops": ops, "requests": requests, "navs": navs,
            "presents": presents, "close": closes[0] if closes else None,
            "page_count": pages, "first_nav_ns": first_nav}


def session_samples(chain):
    pools = {k: [] for k in ("open", "opening_render", "opening_present", "turn_render", "all_render")}
    details, diagnostics = [], []
    navs = chain["navs"]
    for op_id, item in chain["ops"].items():
        queued, begin, end = (item.get(k) for k in ("op_queued", "op_begin", "op_end"))
        if not queued or not end:
            continue
        total = (end["ts_ns"] - queued["ts_ns"]) / 1e6
        detail = {"op_id": op_id, "request_id": queued["request_id"],
                  "page": queued["page"], "origin": queued["origin"],
                  "opening_member": queued["opening_member"], "outcome": end["outcome"],
                  "queued_ns": queued["ts_ns"], "begin_ns": begin["ts_ns"] if begin else None,
                  "end_ns": end["ts_ns"], "total_ms": total,
                  "queue_ms": (begin["ts_ns"] - queued["ts_ns"]) / 1e6 if begin else None,
                  "worker_ms": ((end["worker_end_ns"] - begin["ts_ns"]) / 1e6
                                if begin and end["worker_end_ns"] is not None else None),
                  "return_ms": ((end["ts_ns"] - end["worker_end_ns"]) / 1e6
                                if end["worker_end_ns"] is not None else None),
                  "turn_nav_id": None}
        details.append(detail)
        if end["outcome"] != "ok":
            diagnostics.append({"op_id": op_id, "outcome": end["outcome"], "reason": end.get("reason")})
            continue
        if queued["kind"] == "open":
            pools["open"].append(total)
            continue
        request = chain["requests"][queued["request_id"]]
        start, finish = request["request_start"], request["request_end"]
        # A render the caller cancelled never delivered a page; counting it would
        # charge the opening budget for work the reader never received.
        if finish["outcome"] == "ok":
            pools["all_render"].append(total)
            if queued["opening_member"]:
                pools["opening_render"].append(total)
        present = chain["presents"].get(queued["request_id"])
        nav = navs.get(queued["nav_id"], {})
        nb, ne = nav.get("nav_begin"), nav.get("nav_end")
        eligible = (not queued["opening_member"] and queued["origin"] == "demand" and
                    finish["outcome"] == "ok" and finish["cache"] == "miss" and
                    finish["producer_op_id"] == op_id and nb and ne and ne["outcome"] == "ok" and
                    present and present["outcome"] == "ok" and
                    start["ts_ns"] >= nb["ts_ns"] and present["visible_ns"] >= nb["ts_ns"] and
                    queued["page"] == ne["to_page"] and abs(ne["to_page"] - nb["from_page"]) == 1)
        if eligible:
            pools["turn_render"].append(total)
            detail["turn_nav_id"] = queued["nav_id"]
        else:
            detail["not_turn"] = "not a new successful visible target demand producer"
    for request_id, item in chain["requests"].items():
        start, finish = item["request_start"], item["request_end"]
        present = chain["presents"].get(request_id)
        if present and present["outcome"] == "ok" and start["opening_member"]:
            seal = chain["first_nav_ns"]
            if seal is None or present["visible_ns"] < seal:
                pools["opening_present"].append((present["commit_ts"] - start["ts_ns"]) / 1e6)
        if finish["outcome"] != "ok":
            diagnostics.append({"request_id": request_id, "outcome": finish["outcome"]})
    return pools, details, diagnostics


def evaluate_round(state, log):
    result = {"round": state.get("round"), "failures": [], "diagnostics": [],
              "metrics": {}, "render_details": [], "events": [], "collection_status": "COMPLETE"}
    def fail(gate, code, message):
        result["failures"].append({"gate": gate, "code": code, "message": str(message)})
    if state.get("phase") != "SEALED" or state.get("pending"):
        fail("BOTH", "ROUND_INCOMPLETE", "round not sealed or action unresolved")
    for item in state.get("failures", []):
        fail("BOTH", item.get("code", "COLLECTOR_FAILURE"), item.get("message", item))
    for item in log.errors:
        fail("BOTH", "PBPDF_PARSE", item)
    try:
        pid = integer(state.get("pid"), "measured PID", 1)
        need(state.get("cold_pid_before") != pid and state.get("cold_verified") is True,
             "missing process-cold proof")
        need(state.get("initial_page") == 0, "formal opening not on first page")
        events = log.session(pid, state.get("process_id"), state.get("session_id"))
        result["events"] = events
        chain = inspect_session(events)
        pools, details, diagnostics = session_samples(chain)
        result["metrics"] = {k: summary(v) for k, v in pools.items()}
        result["render_details"] = details
        result["diagnostics"].extend(diagnostics)
        observations = state.get("turns", [])
        need(len(observations) == POLICY["turns"], "ten actual host gestures required")
        need(len(chain["navs"]) == POLICY["turns"], "ten actual native navs required")
        used = set()
        previous = 0
        for observation in observations:
            nav_id = identity(observation.get("nav_id"), "observed nav_id")
            need(nav_id not in used and nav_id in chain["navs"], "duplicate/missing host nav")
            used.add(nav_id)
            nav = chain["navs"][nav_id]
            begin, end = nav["nav_begin"], nav["nav_end"]
            need(end["outcome"] == "ok" and begin["source"] == "drag", "turn not successful native drag")
            need(observation.get("pid") == pid and observation.get("foreground_before") is True and
                 observation.get("foreground_after") is True, "host turn PID/focus proof missing")
            need(observation.get("returncode") == 0 and type(observation.get("returncode")) is int and
                 observation.get("markers_verified") is True, "input exit/boundary proof missing")
            need(begin["from_page"] == observation.get("before_page") == previous and
                 end["to_page"] == observation.get("after_page") and
                 abs(end["to_page"] - begin["from_page"]) == 1, "host UI/native adjacency mismatch")
            previous = end["to_page"]
        for request_id, request in chain["requests"].items():
            start, end = request["request_start"], request["request_end"]
            present = chain["presents"].get(request_id)
            rendered_ok = {item["request_id"] for item in details
                           if item.get("request_id") is not None and item.get("outcome") == "ok"}
            required = ((start["origin"] == "demand" and
                         (start["opening_member"] or start["nav_id"] in used)) or
                        (start["origin"] == "reused" and request_id in rendered_ok))
            if required and (end["outcome"] != "ok" or not present or present["outcome"] != "ok"):
                fail("05", "05_REQUIRED_REQUEST_FAILED", request_id)
        for detail in details:
            if detail["outcome"] == "error":
                fail("05", "05_NATIVE_OPERATION_ERROR", detail["op_id"])
        initial = [r for key, r in chain["presents"].items() if r["outcome"] == "ok" and
                   chain["requests"][key]["request_start"]["opening_member"] and
                   (chain["first_nav_ns"] is None or r["visible_ns"] < chain["first_nav_ns"])]
        need(initial, "first actual visible opening commit missing")
        close = chain["close"]
        if close["outcome"] != "ok" or close["source_close"] != "ok" or close["text_close"] == "error":
            fail("06", "06_RESOURCE_CLOSE", close)
        if close["cache_count"] != 0 or close["cache_bytes"] != 0:
            fail("06", "06_CACHE_NOT_EMPTY", close)
    except (ValueError, KeyError, TypeError) as exc:
        fail("BOTH", "PBPDF_CHAIN", exc)
    try:
        baseline, home = state["memory"]["baseline"], state["memory"]["home"]
        for observation in (baseline, home):
            need(observation["pid"] == state["pid"], "PSS PID mismatch")
            integer(observation["total_kb"], "TOTAL PSS", 1)
            integer(observation["graphics_kb"], "Graphics PSS")
            need(observation["graphics_kb"] <= observation["total_kb"], "Graphics exceeds TOTAL")
        need(baseline["foreground"] is True and home["foreground"] is False, "PSS foreground stages missing")
        wait = state["home_wait"]
        need(wait["returncode"] == 0 and wait["end_ns"] - wait["start_ns"] >= 8_000_000_000,
             "original bounded Home wait <8s or unknown")
        delta = (home["total_kb"] - baseline["total_kb"]) / 1024.0
        result["exit_delta_mib"] = delta
        result["memory"] = state["memory"]
        if delta > POLICY["exit_mib"]:
            fail("06", "06_TOTAL_PSS_BUDGET", delta)
    except (ValueError, KeyError, TypeError) as exc:
        fail("06", "06_MEMORY_EVIDENCE", exc)
    if any(x["gate"] == "BOTH" for x in result["failures"]):
        result["collection_status"] = "INCOMPLETE"
    result["05_pass"] = not any(x["gate"] in {"05", "BOTH"} for x in result["failures"])
    result["06_pass"] = not any(x["gate"] in {"06", "BOTH"} for x in result["failures"])
    return result


def evaluate_batch(rounds):
    result = {"schema": "pbpdf-result-v1", "pass": False, "failures": [], "rounds": rounds,
              "budgets": copy.deepcopy(POLICY), "endpoint": "request_start-to-same-window-draw-and-commit-v1"}
    all_pools = {k: [] for k in ("open", "opening_render", "opening_present", "turn_render", "all_render")}
    if len(rounds) != 5 or sorted(r.get("round", 0) for r in rounds) != [1, 2, 3, 4, 5]:
        result["failures"].append({"gate": "BOTH", "code": "FIVE_ROUNDS_REQUIRED", "message": "no replacement slots"})
    identities = []
    for row in rounds:
        result["failures"].extend(dict(x, round=row.get("round")) for x in row["failures"])
        for key, metrics in row["metrics"].items():
            all_pools[key].extend(metrics["values_ms"])
        if row["events"]:
            e = row["events"][0]
            identities.append(e["process_id"])
    if len(set(identities)) != len(identities):
        result["failures"].append({"gate": "BOTH", "code": "COLD_PROCESS_REUSED", "message": "process nonce reused"})
    result["metrics"] = {k: summary(v) for k, v in all_pools.items()}
    for key in ("open", "opening_render", "opening_present", "turn_render"):
        stats = result["metrics"][key]
        if stats["n"] < POLICY["min_samples"]:
            code = "05_INSUFFICIENT_ATTRIBUTABLE_TURN_RENDER" if key == "turn_render" else "05_INSUFFICIENT_" + key.upper()
            result["failures"].append({"gate": "05", "code": code, "message": "actual n=" + str(stats["n"])})
        if stats["p95_ms"] is not None and stats["p95_ms"] > POLICY[key + "_ms"]:
            result["failures"].append({"gate": "05", "code": "05_" + key.upper() + "_BUDGET",
                                       "message": str(stats["p95_ms"])})
    result["05_pass"] = not any(x["gate"] in {"05", "BOTH"} for x in result["failures"])
    result["06_pass"] = not any(x["gate"] in {"06", "BOTH"} for x in result["failures"])
    result["pass"] = result["05_pass"] and result["06_pass"]
    result["collection_status"] = "INCOMPLETE" if any(x["gate"] == "BOTH" for x in result["failures"]) else "COMPLETE"
    return result


def read_json(path):
    return strict_json(Path(path).read_text(encoding="utf-8-sig"))


def source_info():
    import perf_epub
    return {k: v for k, v in perf_epub.source_tree_info().items() if k != "git_status"}


def collector_hashes():
    here = Path(__file__).resolve().parent
    return {name: file_sha(here / name) for name in ("perf_pdf.py", "perf-device.py", "perf_epub.py")}


def validate_inputs(args, current_source):
    apk = Path(args.apk).resolve()
    release_path, fixture_path = Path(args.release_evidence).resolve(), Path(args.pdf_fixture_evidence).resolve()
    release, fixture = read_json(release_path), read_json(fixture_path)
    apk_sha = file_sha(apk)
    need(release.get("build_type") == "release" and release.get("apksigner_verified") is True,
         "verified signed release evidence required")
    need(re.fullmatch(r"[0-9a-fA-F]{64}", str(release.get("signer_sha256", ""))), "signer SHA missing")
    need(release.get("apk_sha256") == apk_sha, "release/local APK mismatch")
    for key in ("git_head", "source_tree_algorithm", "source_tree_sha256"):
        need(release.get(key) == current_source.get(key), "release/source mismatch: " + key)
    need(args.serial == SERIAL and fixture.get("device_serial") == args.serial and fixture.get("package") == PKG,
         "fixed device/package mismatch")
    need(fixture.get("checked_via") == "adb-exec-out-run-as", "PDF custody inspection method")
    checked = fixture.get("checked_apk_sha256")
    need(re.fullmatch(r"[0-9a-f]{64}", str(checked)) and checked != apk_sha, "inspected debug identity required")
    raw = {}
    inputs = {"release": release_path, "pdf-fixture": fixture_path}
    for key in ("binding_commands", "device_sha256", "device_size", "books", "installed_apk"):
        filename = (fixture_path.parent / fixture["raw_evidence"][key]).resolve()
        inputs["pdf-raw-" + key] = filename
        raw[key] = filename.read_text(encoding="utf-8-sig")
    commands = strict_json(raw["binding_commands"])
    need(type(commands) is list and commands and all(type(x.get("returncode")) is int and
         x["returncode"] == 0 for x in commands), "PDF custody raw command failure")
    books = strict_json(raw["books"])["books"]
    need(len(books) == 16 and {b["id"] for b in books} == {1,2,3,4,101,102,103,104,201,202,203,204,205,206,207,208},
         "PDF custody lost/duplicated fixed books")
    rows = [b for b in books if b["id"] == BOOK_ID]
    need(len(rows) == 1 and rows[0] == fixture.get("qa204_book_record_live"), "raw/reported QA204 mismatch")
    row = rows[0]
    need(row.get("title") == BOOK_TITLE and row.get("format") == "pdf" and
         row.get("filePath") == PDF_PATH and row.get("fileSize") == 3886, "fixed PDF record mismatch")
    need(raw["device_sha256"].split() == [PDF_SHA, PDF_PATH] and int(raw["device_size"].strip()) == 3886,
         "raw PDF SHA/size/path mismatch")
    need(raw["installed_apk"].split()[0] == checked, "inspected APK raw mismatch")
    file = fixture.get("qa204_device_file", {})
    need(file.get("sha256") == PDF_SHA and file.get("path") == PDF_PATH and file.get("size_bytes") == 3886,
         "reported PDF mismatch")
    install = fixture.get("retained_install", {})
    need(install.get("from_apk_sha256") == checked and install.get("to_apk_sha256") == apk_sha and
         install.get("data_cleared") is False and install.get("method") == "adb-install-r" and
         type(install.get("exit_code")) is int and install["exit_code"] == 0 and
         "Success" in str(install.get("stdout", "")), "retained-install chain mismatch")
    inputs["retained-install"] = (fixture_path.parent / install["log_path"]).resolve()
    need("Success" in inputs["retained-install"].read_text(encoding="utf-8-sig"), "raw retained install missing")
    need(type(args.swipe_distance_px) is int and 0 < args.swipe_distance_px < 2368 and
         type(args.swipe_ms) is int and 0 < args.swipe_ms <= 3000, "uncalibrated swipe geometry")
    return apk, apk_sha, inputs


class RunStore:
    """Immutable content + hash-linked snapshots. The OS releases writer lock on crash."""
    def __init__(self, root, create=False, fault=None):
        self.root = Path(root).resolve()
        self.fault = fault or (lambda point: None)
        self.heads = {}
        if create:
            self.root.mkdir(parents=True, exist_ok=False)
        need(self.root.is_dir(), "run directory missing")

    def put(self, relative, value):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        data = value if isinstance(value, bytes) else canonical(value) + b"\n"
        temporary = path.with_name(path.name + ".tmp-" + uuid.uuid4().hex)
        with temporary.open("xb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        self.fault("after_tmp_flush")
        # Hard-link publishes atomically without POSIX rename's overwrite behavior.
        os.link(temporary, path)
        self.fault("after_publish")
        temporary.unlink()
        if os.name != "nt":
            directory = os.open(str(path.parent), os.O_RDONLY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        return {"path": path.relative_to(self.root).as_posix(), "bytes": len(data), "sha256": sha(data)}

    def get(self, ref):
        path = (self.root / ref["path"]).resolve()
        need(path.is_relative_to(self.root), "evidence path escaped run")
        data = path.read_bytes()
        need(len(data) == ref["bytes"] and sha(data) == ref["sha256"], "evidence byte/SHA mismatch: " + ref["path"])
        return data

    def manifest(self):
        data = (self.root / "manifest.json").read_bytes()
        manifest = strict_json(data.decode("utf-8"))
        need(manifest.get("schema") == "pbpdf-run-v1" and manifest.get("policy") == POLICY, "run schema/policy drift")
        need(manifest.get("package") == PKG and manifest.get("book_id") == BOOK_ID and
             manifest.get("pdf_sha256") == PDF_SHA and manifest.get("serial") == SERIAL,
             "immutable run target drift")
        for ref in manifest["bindings"].values():
            self.get(ref)
        return manifest, sha(data)

    def load(self, number):
        manifest, manifest_sha = self.manifest()
        files = sorted((self.root / ("round%02d/checkpoints" % number)).glob("*.json"))
        need(files, "round checkpoint missing")
        previous, state = None, None
        for revision, path in enumerate(files, 1):
            need(path.name == "%06d.json" % revision, "checkpoint revision gap")
            data = path.read_bytes()
            item = strict_json(data.decode("utf-8"))
            need(item["revision"] == revision and item["previous_sha256"] == previous and
                 item["manifest_sha256"] == manifest_sha and item["state"]["round"] == number,
                 "checkpoint version/hash chain mismatch")
            previous, state = sha(data), item["state"]
        for ref in state.get("evidence", []):
            self.get(ref)
        need(not list((self.root / ("round%02d/checkpoints" % number)).glob("*.tmp-*")),
             "torn checkpoint retained; explicit forensic handling required")
        return manifest, state, len(files), previous

    def save(self, state):
        number = state["round"]
        if number not in self.heads:
            _, _, revision, previous = self.load(number)
            self.heads[number] = (revision, previous)
        revision, previous = self.heads[number]
        ref = self.put("round%02d/checkpoints/%06d.json" % (number, revision + 1), {
            "revision": revision + 1, "previous_sha256": previous,
            "manifest_sha256": sha((self.root / "manifest.json").read_bytes()), "state": state})
        self.heads[number] = (revision + 1, ref["sha256"])
        return ref

    @contextlib.contextmanager
    def writer(self):
        lock = (self.root / "writer.lock").open("a+b")
        try:
            lock.seek(0, os.SEEK_END)
            if lock.tell() == 0:
                lock.write(b"0")
                lock.flush()
            lock.seek(0)
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            yield
        finally:
            lock.close()


def init_run(args, source=None):
    current = (source or source_info)()
    apk, apk_sha, inputs = validate_inputs(args, current)
    store = RunStore(args.out, create=True)
    bindings = {key: store.put("bindings/" + key + ".raw", path.read_bytes()) for key, path in inputs.items()}
    manifest = {"schema": "pbpdf-run-v1", "run_id": uuid.uuid4().hex, "created_at_ns": time.time_ns(),
                "package": PKG, "serial": args.serial, "book_id": BOOK_ID, "book_title": BOOK_TITLE,
                "pdf_sha256": PDF_SHA, "pdf_size_bytes": 3886, "pdf_path": PDF_PATH,
                "policy": copy.deepcopy(POLICY), "source": current, "collector": collector_hashes(),
                "apk": str(apk), "apk_sha256": apk_sha, "bindings": bindings,
                "adb": args.adb, "swipe_distance_px": args.swipe_distance_px, "swipe_ms": args.swipe_ms}
    ref = store.put("manifest.json", manifest)
    for number in range(1, 6):
        state = {"round": number, "phase": "PRESET_STOP", "pending": None, "pid": None,
                 "turns": [], "memory": {}, "snapshots": [], "evidence": [], "failures": [],
                 "actions": [], "command_count": 0, "identity_checks": [], "direction": 1}
        store.put("round%02d/checkpoints/000001.json" % number, {
            "revision": 1, "previous_sha256": None, "manifest_sha256": ref["sha256"], "state": state})
    return {"status": "INITIALIZED_NOT_EXECUTED", "run_id": manifest["run_id"], "manifest": ref}


class Deadline:
    def __init__(self, clock=time.monotonic_ns, sleep=time.sleep):
        self.clock, self.sleep = clock, sleep
        self.end = clock() + POLICY["segment_seconds"] * 1_000_000_000

    def timeout(self):
        remaining = (self.end - self.clock()) / 1e9 - 5
        need(remaining > 0, "segment deadline exhausted before command")
        return min(POLICY["adb_seconds"], remaining)

    def wait(self, seconds):
        need((self.end - self.clock()) / 1e9 >= seconds + 5, "insufficient bounded wait time")
        start = self.clock()
        self.sleep(seconds)
        return {"start_ns": start, "end_ns": self.clock(), "returncode": 0}


class Device:
    def __init__(self, store, state, manifest, deadline, runner=subprocess.run):
        self.store, self.state, self.manifest = store, state, manifest
        self.deadline, self.runner = deadline, runner
        self.last_receipt = None

    def call(self, *args, action=None, absent=False, binary=False):
        timeout = self.deadline.timeout()
        self.state["command_count"] += 1
        prefix = "round%02d/commands/%06d" % (self.state["round"], self.state["command_count"])
        argv = [self.manifest["adb"], "-s", self.manifest["serial"]] + [str(x) for x in args]
        planned = {"command_id": prefix, "argv": argv, "phase": self.state["phase"],
                   "state": "PLANNED", "host_ns": time.time_ns(), "timeout_seconds": timeout, "action": copy.deepcopy(action)}
        self.state["evidence"].append(self.store.put(prefix + ".planned.json", planned))
        if action:
            need(self.state["pending"] is None, "unresolved action; input not resent")
            self.state["pending"] = dict(action, command_id=prefix, status="PLANNED")
        self.store.save(self.state)
        self.store.fault("after_plan")
        dispatch = dict(planned, state="DISPATCHING")
        self.state["evidence"].append(self.store.put(prefix + ".dispatch.json", dispatch))
        if action:
            self.state["pending"]["status"] = "DISPATCHING"
        self.store.save(self.state)
        self.store.fault("after_dispatch")
        started = self.deadline.clock()
        code, stdout, stderr, error = None, b"", b"", None
        try:
            done = self.runner(argv, capture_output=True, timeout=timeout)
            code, stdout, stderr = done.returncode, done.stdout or b"", done.stderr or b""
        except (OSError, subprocess.TimeoutExpired) as exc:
            stdout = getattr(exc, "stdout", b"") or b""
            stderr = getattr(exc, "stderr", b"") or b""
            error = str(exc)
        self.store.fault("after_execute")
        if isinstance(stdout, str):
            stdout = stdout.encode("utf-8")
        if isinstance(stderr, str):
            stderr = stderr.encode("utf-8")
        out_ref = self.store.put(prefix + ".stdout", stdout)
        self.store.fault("after_stdout")
        err_ref = self.store.put(prefix + ".stderr", stderr)
        self.store.fault("after_stderr")
        receipt = {"command_id": prefix, "argv": argv, "returncode": code, "error": error,
                   "start_ns": started, "end_ns": self.deadline.clock(), "stdout": out_ref, "stderr": err_ref}
        ref = self.store.put(prefix + ".receipt.json", receipt)
        self.store.fault("after_receipt")
        self.state["evidence"].extend([out_ref, err_ref, ref])
        self.last_receipt = receipt
        if action:
            self.state["pending"].update(status="RETURNED", receipt=ref, returncode=code)
        expected_absent = absent and code == 1 and not stdout.strip() and not stderr.strip()
        if code != 0 and not expected_absent:
            self.state["failures"].append({"code": "COMMAND_FAILED", "message": prefix, "returncode": code})
        self.store.save(self.state)
        self.store.fault("after_checkpoint")
        need(code == 0 or expected_absent, "command failed/unknown: " + prefix)
        return stdout if binary else stdout.decode("utf-8", "strict")

    def sh(self, *args, **kwargs):
        return self.call("shell", *args, **kwargs)

    def pid(self):
        text = self.sh("pidof", PKG, absent=True).strip()
        need(not text or re.fullmatch(r"[0-9]+", text), "multiple/unreadable package PIDs")
        return int(text) if text else None

    def drain(self):
        raw = self.sh("logcat", "-d", "-v", "epoch", "-s", "PBPDF:I", "PBPDFINPUT:I", "*:S")
        self.state["snapshots"].append(self.last_receipt["stdout"])
        self.store.save(self.state)
        return raw

    def log(self):
        log = EventLog()
        for ref in self.state["snapshots"]:
            log.add(self.store.get(ref).decode("utf-8"), ref["path"])
        return log

    def foreground(self, expected=True):
        text = self.sh("dumpsys", "activity", "activities")
        resumed = re.findall(r"^.*(?:mResumedActivity|topResumedActivity).*?$", text, re.M)
        matches = [re.search(r"ActivityRecord\{(\S+)\s+u\d+\s+([^/\s]+)/([^\s}]+)", line) for line in resumed]
        need(matches and all(matches), "missing/malformed resumed activity")
        identities = {(m[1], m[2], m[3]) for m in matches}
        need(len(identities) == 1, "ambiguous resumed activity")
        record, package, _ = next(iter(identities))
        blocks = re.findall(r"^\s*\* Hist #\d+: ActivityRecord\{" + re.escape(record) +
                            r"\s+u\d+\s+" + re.escape(package) +
                            r"/[^\n]+\n(.*?)(?=^\s*\* Hist #|^\s*RootTask #|\Z)", text, re.M | re.S)
        need(len(blocks) == 1, "resumed activity detail missing")
        processes = re.findall(r"^\s*app=ProcessRecord\{\S+\s+(\d+):([^/\s}]+)/", blocks[0], re.M)
        need(len(processes) == 1 and processes[0][1] == package, "resumed process mismatch")
        focus = re.findall(r"mCurrentFocus=Window\{\S+\s+u\d+\s+([^/\s}]+)/", self.sh("dumpsys", "window"))
        need(focus == [package] and (package == PKG) is expected, "unexpected foreground/focus")
        if not expected:
            need(package == "com.google.android.apps.nexuslauncher", "Home did not reach fixed launcher")
        live = self.sh("pidof", package).strip()
        need(live.isdigit() and int(live) == int(processes[0][0]), "foreground PID mismatch")
        if expected and self.state.get("pid") is not None:
            need(int(live) == self.state["pid"], "measured PID changed")
        return expected


    def ui(self):
        raw = self.call("exec-out", "uiautomator", "dump", "/dev/tty")
        start, end = raw.find("<hierarchy"), raw.rfind("</hierarchy>")
        need(start >= 0 and end > start, "UI XML missing")
        root = ET.fromstring(raw[start:end + len("</hierarchy>")])
        elements = list(root.iter("node"))
        indices = {node: index for index, node in enumerate(elements)}
        parents = {child: parent for parent in root.iter() for child in parent}
        nodes = []
        for node in elements:
            match = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
            if not match:
                continue
            rect = list(map(int, match.groups()))
            if rect[2] > rect[0] and rect[3] > rect[1]:
                nodes.append({"index": indices[node], "parent": indices.get(parents.get(node)),
                              "text": node.get("text", ""), "desc": node.get("content-desc", ""),
                              "package": node.get("package", ""), "clickable": node.get("clickable") == "true",
                              "rect": rect})
        return nodes

    def shelf(self):
        self.foreground()
        nodes = self.ui()
        need(any(n["package"] == PKG and n["text"] == "书架" for n in nodes), "shelf heading missing")
        need(not any(re.fullmatch(r"第\s*\d+\s*页", n["desc"]) for n in nodes), "reader still visible")
        return nodes

    def page(self):
        bars, overlap = set(), {}
        for node in self.ui():
            if node["package"] != PKG:
                continue
            bar = re.fullmatch(r"(\d+)\s*/\s*(\d+)", node["text"].strip())
            if bar:
                need(int(bar[2]) == 3, "PDF UI/runtime page count mismatch")
                bars.add(int(bar[1]) - 1)
            image = re.fullmatch(r"第\s*(\d+)\s*页", node["desc"].strip())
            if image:
                area = max(0, min(2960, node["rect"][3]) - max(0, node["rect"][1]))
                page = int(image[1]) - 1
                overlap[page] = max(overlap.get(page, 0), area)
        need(len(bars) <= 1, "conflicting page indicators")
        ranked = sorted(overlap, key=overlap.get, reverse=True)
        dominant = ranked[0] if ranked and (len(ranked) == 1 or overlap[ranked[0]] > overlap[ranked[1]]) else None
        page = next(iter(bars)) if bars else dominant
        need(page is not None and 0 <= page < 3, "current PDF page unobservable")
        need(not bars or dominant is None or page == dominant, "page indicator/image disagree")
        return page

    def memory(self, foreground):
        need(self.pid() == self.state["pid"], "PSS process changed")
        self.foreground(foreground)
        text = self.sh("dumpsys", "meminfo", PKG)
        ref = self.last_receipt["stdout"]
        pids = re.findall(r"MEMINFO in pid (\d+)", text)
        need(pids == [str(self.state["pid"])], "meminfo PID missing/mismatched")
        total = re.search(r"^\s*TOTAL PSS:\s*(\d+)", text, re.M)
        if not total:
            total = re.search(r"^\s*TOTAL\s+(\d+)\s+", text, re.M)
        graphics = re.search(r"^\s*Graphics:?\s+(\d+)(?:\s|$)", text, re.M)
        need(total is not None and graphics is not None, "TOTAL/Graphics PSS missing")
        result = {"pid": self.state["pid"], "foreground": foreground, "total_kb": int(total[1]),
                  "graphics_kb": int(graphics[1]), "raw": ref}
        need(0 < result["total_kb"] and result["graphics_kb"] <= result["total_kb"], "invalid PSS")
        need(self.pid() == self.state["pid"], "PSS process changed after capture")
        return result

    def screenshot(self):
        data = self.call("exec-out", "screencap", "-p", binary=True)
        need(data.startswith(bytes([137,80,78,71,13,10,26,10])), "screenshot is not PNG")
        # Reuse the existing CRC/IHDR validator without touching EPUB behavior.
        ref = self.store.put("round%02d/screenshots/%06d.png" %
                             (self.state["round"], self.state["command_count"]), data)
        import perf_epub
        perf_epub.png_evidence(str(self.store.root / ref["path"]))
        self.state["evidence"].append(ref)
        self.store.save(self.state)
        return ref


def cover(nodes):
    anchors = [n for n in nodes if n["package"] == PKG and n["desc"].startswith("《" + BOOK_TITLE + "》")]
    need(len(anchors) <= 1, "ambiguous fixed PDF card")
    if not anchors:
        return None
    by_id = {n["index"]: n for n in nodes}
    def inside(node, parent):
        for _ in range(30):
            if node["index"] == parent["index"]:
                return True
            node = by_id.get(node["parent"])
            if node is None:
                return False
        return False
    parent = by_id.get(anchors[0]["parent"])
    for _ in range(12):
        if parent is None:
            break
        members = [n for n in nodes if inside(n, parent)]
        titles = {n["desc"].split("》", 1)[0][1:] for n in members if n["desc"].startswith("《")}
        if titles != {BOOK_TITLE}:
            break
        targets = [n for n in members if n["package"] == PKG and n["clickable"]]
        need(len(targets) <= 1, "PDF cover has multiple clickable targets")
        if targets:
            return targets[0]
        parent = by_id.get(parent["parent"])
    raise Invalid("fixed PDF card has no associated clickable cover")


def check_identity(d, source=source_info):
    manifest = d.manifest
    need(source() == manifest["source"], "source identity drift")
    need(collector_hashes() == manifest["collector"], "collector identity drift")
    need(file_sha(manifest["apk"]) == manifest["apk_sha256"], "local APK changed")
    need(d.call("get-serialno").strip() == manifest["serial"], "device serial drift")
    need(d.sh("getprop", "ro.product.model").strip() == "Pixel 3 XL", "device model drift")
    need(d.sh("getprop", "ro.build.version.sdk").strip() == "31", "device SDK drift")
    need(d.sh("getconf", "PAGESIZE").strip() == "4096", "device page size drift")
    fingerprint = d.sh("getprop", "ro.build.fingerprint").strip()
    need(fingerprint == "google/crosshatch/crosshatch:12/SP1A.210812.016.C1/8029091:user/release-keys",
         "device fingerprint drift")
    geometry = re.findall(r"(\d+)x(\d+)", d.sh("wm", "size"))
    need(geometry and geometry[-1] == ("1440", "2960"), "fixed viewport drift")
    package = d.sh("dumpsys", "package", PKG)
    flags = re.search(r"flags=\[([^\]]*)\]", package)
    need(flags is not None and "DEBUGGABLE" not in flags[1], "installed package flags/debuggable invalid")
    paths = d.sh("pm", "path", PKG).strip().splitlines()
    need(len(paths) == 1 and paths[0].startswith("package:"), "single installed base.apk required")
    installed = d.sh("sha256sum", paths[0].split(":", 1)[1]).split()
    need(installed and installed[0] == manifest["apk_sha256"], "installed APK drift")
    # Release private bytes are certified by frozen debug raw + retained install-r.
    # Do not pretend non-debuggable run-as can re-read the private PDF each segment.
    d.state["identity_checks"].append({"source_sha256": manifest["source"]["source_tree_sha256"],
                                       "installed_sha256": installed[0], "fingerprint": fingerprint,
                                       "at_command": d.state["command_count"]})
    d.store.save(d.state)


def measured_chain(d, sealed=False, preset=False):
    log = d.log()
    need(not log.errors, "PBPDF parse/conflict failure: " + str(log.errors))
    pid = d.state.get("preset_pid") if preset else d.state.get("pid")
    events = log.session(pid, None if preset else d.state.get("process_id"),
                         None if preset else d.state.get("session_id"))
    return inspect_session(events, sealed=sealed)


def markers(raw, token):
    values = re.findall(r"\bPBPDFINPUT\s*:\s*" + re.escape(token) + r"_(BEGIN|END)\s*$", raw, re.M)
    return values == ["BEGIN", "END"]


def confirm_action(d, recovery=False):
    state, pending = d.state, d.state["pending"]
    need(pending is not None, "no action to confirm")
    kind = pending["kind"]
    if kind == "stop":
        need(d.pid() is None, "force-stop left a live PID")
    elif kind == "start":
        pid = d.pid()
        need(pid is not None and pid != pending.get("old_pid"), "new process PID missing/reused")
        if pending.get("formal"):
            state["pid"] = pid
        else:
            state["preset_pid"] = pid
        d.shelf()
    elif kind == "open":
        raw = d.drain()
        chain = measured_chain(d, preset=pending.get("preset", False))
        need(chain["page_count"] == 3, "open runtime page count missing")
        page = d.page()
        if not pending.get("preset"):
            need(page == 0, "formal PDF did not open on first page")
            need(any(e["outcome"] == "ok" and e["page"] == 0 for e in chain["presents"].values()),
                 "first page real commit not yet observed")
            first = chain["events"][0]
            state.update(process_id=first["process_id"], session_id=first["session_id"], initial_page=page)
        d.foreground()
        if not recovery:
            d.screenshot()
    elif kind == "swipe":
        raw = d.drain()
        chain = measured_chain(d, preset=pending.get("preset", False))
        new = [n for n in chain["navs"].values() if n.get("nav_begin", {}).get("seq", 0) > pending["before_seq"]]
        need(len(new) == 1 and "nav_end" in new[0], "one new completed nav required")
        begin, end = new[0]["nav_begin"], new[0]["nav_end"]
        need(begin["source"] == "drag" and begin["from_page"] == pending["before_page"] and
             end["outcome"] == "ok" and end["to_page"] == pending["target"], "native nav missed adjacent target")
        need(d.page() == pending["target"], "UI missed adjacent target")
        d.foreground()
        marker_proof = markers(raw, pending["token"])
        need(marker_proof or (recovery and pending.get("returncode") is None),
             "input begin/end markers missing/conflicting")
        if not recovery:
            d.screenshot()
        if not pending.get("preset"):
            state["turns"].append({"nav_id": begin["nav_id"], "pid": state["pid"],
                                   "before_page": pending["before_page"], "after_page": pending["target"],
                                   "foreground_before": True, "foreground_after": True,
                                   "returncode": pending.get("returncode"), "markers_verified": marker_proof,
                                   "command_id": pending["command_id"]})
    elif kind in {"back", "shelf_scroll"}:
        d.shelf()
    elif kind == "home":
        need(d.pid() == state["pid"], "PID lost after Home")
        d.foreground(False)
        receipt = pending.get("receipt")
        if receipt:
            state["home_start_ns"] = strict_json(d.store.get(receipt).decode("utf-8"))["end_ns"]
    else:
        raise Invalid("unknown pending action")
    known_exit = pending.get("returncode") == 0
    if not known_exit:
        state["failures"].append({"code": "UNKNOWN_ACTION_EXIT", "message": pending["command_id"]})
    pending["status"] = "CONFIRMED" if known_exit else "observed_performed"
    state["actions"].append(copy.deepcopy(pending))
    state["phase"] = pending["next_phase"]
    state["pending"] = None
    d.store.save(state)


def perform(d, argv, kind, next_phase, **metadata):
    need(d.state["pending"] is None, "unresolved action; use read-only resume observation")
    d.call(*argv, action=dict(metadata, kind=kind, next_phase=next_phase))
    if kind == "start" and metadata.get("formal"):
        receipt = d.last_receipt
        text = d.store.get(receipt["stdout"]).decode("utf-8")
        need(re.search(r"^Status:\s*ok\s*$", text, re.M) and
             re.search(r"^LaunchState:\s*COLD\s*$", text, re.M) and
             "Warning:" not in text and "Error:" not in text, "launch not an actual cold start")
        d.state["cold_verified"] = True
    # Native/UI may settle asynchronously; input itself is never repeated.
    last = None
    for _ in range(20):
        try:
            confirm_action(d)
            return
        except Invalid as exc:
            last = exc
            transient = ("one new completed nav required", "first page real commit not yet observed",
                         "one fixed-book session_start required", "one actual open operation required",
                         "open runtime page count missing",
                         "current PDF page unobservable", "shelf heading missing", "reader still visible")
            if kind not in {"open", "swipe", "back"} or not any(x in str(exc) for x in transient):
                raise
            d.deadline.wait(.2)
    raise Invalid("confirmation missing; input not repeated: " + str(last))


def swipe(d, before, target, preset=False):
    d.foreground()
    need(d.page() == before, "pre-input UI drift")
    d.drain()
    chain = measured_chain(d, preset=preset)
    before_seq = chain["events"][-1]["seq"]
    distance = d.manifest["swipe_distance_px"]
    y1, y2 = 1480 + distance // 2, 1480 - distance // 2
    if target < before:
        y1, y2 = y2, y1
    token = "PDF_" + d.manifest["run_id"] + "_" + str(d.state["round"]) + "_" + str(d.state["command_count"])
    script = ("log -p i -t PBPDFINPUT " + token + "_BEGIN; input swipe 720 " + str(y1) +
              " 720 " + str(y2) + " " + str(d.manifest["swipe_ms"]) +
              "; rc=$?; log -p i -t PBPDFINPUT " + token + "_END; exit $rc")
    next_phase = "PRESET_RESET" if preset else ("READING" if len(d.state["turns"]) == 9 else "TURN")
    # adb joins shell arguments before remote interpretation, so a nested
    # unquoted sh -c would consume only "log" and lose the BEGIN marker.
    perform(d, ["shell", script], "swipe", next_phase, before_page=before,
            target=target, before_seq=before_seq, token=token, preset=preset)


def open_card(d, preset=False):
    node = cover(d.shelf())
    if node is None:
        count = d.state.get("shelf_scrolls", 0)
        need(count < 18, "fixed PDF card not found; no alternate fixture")
        d.state["shelf_scrolls"] = count + 1
        y1, y2 = (2131, 888) if count < 9 else (888, 2131)
        perform(d, ["shell", "input", "swipe", 720, y1, 720, y2, 500], "shelf_scroll", d.state["phase"])
        return
    rect = node["rect"]
    perform(d, ["shell", "input", "tap", (rect[0] + rect[2]) // 2, (rect[1] + rect[3]) // 2],
            "open", "PRESET_RESET" if preset else "TURN", preset=preset)


def advance(d):
    state, phase = d.state, d.state["phase"]
    if phase in {"PRESET_STOP", "COLD_STOP"}:
        old = d.pid()
        if phase == "COLD_STOP":
            state["cold_pid_before"] = old
        state["pid"] = None
        perform(d, ["shell", "am", "force-stop", PKG], "stop",
                "PRESET_START" if phase == "PRESET_STOP" else "COLD_START")
    elif phase in {"PRESET_START", "COLD_START"}:
        formal = phase == "COLD_START"
        perform(d, ["shell", "am", "start", "-W", "-n", ACT], "start",
                "BASELINE" if formal else "PRESET_OPEN", formal=formal,
                old_pid=state.get("cold_pid_before") if formal else None)
    elif phase in {"PRESET_OPEN", "OPEN"}:
        open_card(d, preset=phase == "PRESET_OPEN")
    elif phase == "PRESET_RESET":
        page = d.page()
        if page:
            swipe(d, page, page - 1, preset=True)
        else:
            state["phase"] = "PRESET_BACK"
            d.store.save(state)
    elif phase in {"PRESET_BACK", "BACK"}:
        perform(d, ["shell", "input", "keyevent", 4], "back", "COLD_STOP" if phase == "PRESET_BACK" else "CLOSE_CHECK")
    elif phase == "BASELINE":
        state["memory"]["baseline"] = d.memory(True)
        state["phase"] = "OPEN"
        d.store.save(state)
    elif phase == "TURN":
        before = state["turns"][-1]["after_page"] if state["turns"] else 0
        if before == 2:
            state["direction"] = -1
        elif before == 0:
            state["direction"] = 1
        swipe(d, before, before + state["direction"])
    elif phase == "READING":
        state["memory"]["reading"] = d.memory(True)
        state["phase"] = "BACK"
        d.store.save(state)
    elif phase == "CLOSE_CHECK":
        last = None
        for _ in range(20):
            try:
                d.drain()
                measured_chain(d, sealed=True)
                last = None
                break
            except Invalid as exc:
                last = exc
                d.deadline.wait(.2)
        need(last is None, "native close chain incomplete: " + str(last))
        state["memory"]["back"] = d.memory(True)
        state["phase"] = "HOME"
        d.store.save(state)
    elif phase == "HOME":
        perform(d, ["shell", "input", "keyevent", 3], "home", "HOME_WAIT")
    elif phase == "HOME_WAIT":
        anchor = state.get("home_start_ns")
        need(anchor is not None and d.deadline.clock() >= anchor, "original Home timestamp missing/reset")
        elapsed = (d.deadline.clock() - anchor) / 1e9
        d.deadline.wait(max(0, 8 - elapsed))
        state["home_wait"] = {"start_ns": anchor, "end_ns": d.deadline.clock(), "returncode": 0}
        state["memory"]["home"] = d.memory(False)
        d.drain()
        state["phase"] = "SEAL"
        d.store.save(state)
    elif phase == "SEAL":
        d.drain()
        measured_chain(d, sealed=True)
        state["phase"] = "SEALED"
        ref = d.store.save(state)
        d.store.put("round%02d/seal.json" % state["round"], {"checkpoint": ref})
    else:
        raise Invalid("unknown phase: " + str(phase))


def evidence_ref(store, path):
    data = path.read_bytes()
    return {"path": path.relative_to(store.root).as_posix(), "bytes": len(data), "sha256": sha(data)}


def audit_commands(store, state):
    """Recover original receipts only. Missing receipts never become exit zero."""
    rows, errors = {}, []
    directory = store.root / ("round%02d/commands" % state["round"])
    for number, path in enumerate(sorted(directory.glob("*.planned.json")), 1):
        prefix = "round%02d/commands/%06d" % (state["round"], number)
        need(path.name == "%06d.planned.json" % number, "command sequence gap")
        planned = read_json(path)
        need(planned["command_id"] == prefix and planned["state"] == "PLANNED", "planned command identity drift")
        argv = planned["argv"]
        manifest, _ = store.manifest()
        need(argv[:3] == [manifest["adb"], "-s", manifest["serial"]], "command device identity drift")
        timeout = planned["timeout_seconds"]
        need(type(timeout) in (int, float) and 0 < timeout <= 30, "unbounded command timeout")
        refs = [evidence_ref(store, path)]
        dispatch_path = store.root / (prefix + ".dispatch.json")
        receipt_path = store.root / (prefix + ".receipt.json")
        dispatched, receipt, receipt_ref = dispatch_path.exists(), None, None
        if dispatched:
            dispatch = read_json(dispatch_path)
            need(dispatch == dict(planned, state="DISPATCHING"), "dispatch drift")
            refs.append(evidence_ref(store, dispatch_path))
        if receipt_path.exists():
            need(dispatched, "receipt without dispatch")
            receipt = read_json(receipt_path)
            need(receipt["command_id"] == prefix and receipt["argv"] == argv, "original receipt drift")
            for key in ("start_ns", "end_ns"):
                integer(receipt[key], key)
            need(receipt["end_ns"] >= receipt["start_ns"], "negative host command duration")
            code = receipt["returncode"]
            need(code is None or type(code) is int, "noninteger command exit")
            out, err = store.get(receipt["stdout"]), store.get(receipt["stderr"])
            absent = argv[3:] == ["shell", "pidof", PKG] and code == 1 and not out.strip() and not err.strip()
            if (code != 0 and not absent) or receipt.get("error"):
                errors.append({"code": "COMMAND_FAILED", "message": prefix, "returncode": code})
            receipt_ref = evidence_ref(store, receipt_path)
            refs.extend([receipt["stdout"], receipt["stderr"], receipt_ref])
        elif dispatched:
            errors.append({"code": "UNKNOWN_COMMAND_EXIT", "message": prefix, "returncode": None})
        for suffix in (".stdout", ".stderr"):
            partial = store.root / (prefix + suffix)
            if partial.exists() and not any(r["path"] == partial.relative_to(store.root).as_posix() for r in refs):
                refs.append(evidence_ref(store, partial))
        rows[prefix] = {"planned": planned, "dispatched": dispatched, "receipt": receipt,
                        "receipt_ref": receipt_ref, "refs": refs}
    if list(directory.glob("*.tmp-*")):
        errors.append({"code": "RAW_PARTIAL_WRITE", "message": "original temp files retained"})
    return rows, errors


def add_audit(state, rows, errors):
    known = {r["path"] for r in state["evidence"]}
    for row in rows.values():
        for ref in row["refs"]:
            if ref["path"] not in known:
                state["evidence"].append(ref)
                known.add(ref["path"])
    state["command_count"] = max(state["command_count"], len(rows))
    for error in errors:
        if error not in state["failures"]:
            state["failures"].append(error)


def state_summary(state):
    return {"round": state["round"], "phase": state["phase"], "turn_count": len(state["turns"]),
            "action_count": len(state["actions"]), "pending": state["pending"],
            "failures": state["failures"], "command_count": state["command_count"]}


def collect_step(args, runner=subprocess.run, source=source_info, clock=time.monotonic_ns, sleep=time.sleep):
    need(args.execute, "step requires explicit --execute")
    store = RunStore(args.out)
    with store.writer():
        manifest, state, _, _ = store.load(args.round)
        if state["phase"] == "SEALED":
            return dict(state_summary(state), status="ALREADY_SEALED"), 0 if not state["failures"] else 1
        need(state["pending"] is None, "unresolved action: use resume --observe; no input resent")
        rows, errors = audit_commands(store, state)
        add_audit(state, rows, errors)
        device = Device(store, state, manifest, Deadline(clock, sleep), runner)
        try:
            check_identity(device, source)
            while state["phase"] not in {"SEAL", "SEALED"}:
                # Leave room for the after-identity check; continue this same round next tool.
                reserve = max(45_000_000_000, 2 * POLICY["adb_seconds"] * 1_000_000_000)
                if device.deadline.end - clock() < reserve:
                    break
                advance(device)
                if args.one_phase:
                    break
            check_identity(device, source)
            if state["phase"] == "SEAL":
                advance(device)
            return dict(state_summary(state), status="PROGRESS"), 0 if not state["failures"] else 1
        except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError) as exc:
            if device.deadline.end - clock() < 5_000_000_000:
                # Running out of segment budget is not evidence failure: keep the
                # round's phase and failures untouched so the next host call resumes.
                return dict(state_summary(state), status="PAUSED",
                            reason="SEGMENT_EXHAUSTED"), 0
            state["failures"].append({"code": "SEGMENT_FAILED", "message": str(exc)})
            # A write may have published before an interruption. Never overwrite/retry it.
            fresh = RunStore(args.out)
            _, actual, _, _ = fresh.load(args.round)
            if actual == state:
                pass
            elif not any(x.get("message") == str(exc) for x in actual["failures"]):
                actual["failures"].append({"code": "SEGMENT_FAILED", "message": str(exc)})
                fresh.save(actual)
            return dict(state_summary(actual), status="INTERRUPTED", error=str(exc)), 1


def resume_run(args, runner=subprocess.run, source=source_info, clock=time.monotonic_ns, sleep=time.sleep):
    store = RunStore(args.out)
    manifest, state, revision, _ = store.load(args.round)
    rows, errors = audit_commands(store, state)
    if not args.observe:
        result = dict(state_summary(state), status="READ_ONLY", checkpoint_revision=revision,
                      audit_errors=errors)
        return result, 1 if state["pending"] or state["failures"] or errors else 0
    if state["phase"] == "SEALED":
        return dict(state_summary(state), status="ALREADY_SEALED_READ_ONLY"), 1 if state["failures"] or errors else 0
    with store.writer():
        # Re-read after acquiring the single-writer lock.
        manifest, state, _, _ = store.load(args.round)
        rows, errors = audit_commands(store, state)
        add_audit(state, rows, errors)
        pending = state["pending"]
        if pending:
            row = rows[pending["command_id"]]
            if row["receipt"]:
                pending.update(status="RETURNED", returncode=row["receipt"]["returncode"], receipt=row["receipt_ref"])
            elif not row["dispatched"]:
                pending["status"] = "NOT_DISPATCHED"
                state["actions"].append(copy.deepcopy(pending))
                state["pending"] = None
        store.save(state)
        if state["phase"] == "SEALED":
            return dict(state_summary(state), status="OBSERVED_SEALED"), 1 if state["failures"] else 0
        device = Device(store, state, manifest, Deadline(clock, sleep), runner)
        try:
            check_identity(device, source)
            if state["pending"]:
                confirm_action(device, recovery=True)
            check_identity(device, source)
            return dict(state_summary(state), status="OBSERVED_NO_INPUT"), 1 if state["failures"] else 0
        except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError) as exc:
            state["failures"].append({"code": "RESUME_OBSERVATION_FAILED", "message": str(exc)})
            # All observation failures are sticky; no command/input is retried here.
            fresh = RunStore(args.out)
            _, actual, _, _ = fresh.load(args.round)
            actual["failures"].append({"code": "RESUME_OBSERVATION_FAILED", "message": str(exc)})
            fresh.save(actual)
            return dict(state_summary(actual), status="OBSERVATION_INCOMPLETE", error=str(exc)), 1


def evaluate_run(args):
    store = RunStore(args.out)
    manifest, manifest_sha = store.manifest()
    rounds = []
    for number in range(1, 6):
        try:
            _, state, revision, checkpoint_sha = store.load(number)
            rows, errors = audit_commands(store, state)
            add_audit(state, rows, errors)  # Local copy only; evaluate never mutates evidence.
            seal_path = store.root / ("round%02d/seal.json" % number)
            if state["phase"] == "SEALED":
                seal = read_json(seal_path)
                data = store.get(seal["checkpoint"])
                need(sha(data) == checkpoint_sha and seal["checkpoint"]["path"] ==
                     "round%02d/checkpoints/%06d.json" % (number, revision), "seal is not final checkpoint")
            log = EventLog()
            for ref in state["snapshots"]:
                log.add(store.get(ref).decode("utf-8"), ref["path"])
            row = evaluate_round(state, log)
            row.update(checkpoint_revision=revision, checkpoint_sha256=checkpoint_sha,
                       snapshots=state["snapshots"], command_receipts=[x["receipt_ref"] for x in rows.values()],
                       raw_occurrences=log.occurrences)
        except (ValueError, KeyError, TypeError, OSError) as exc:
            row = {"round": number, "metrics": {}, "events": [], "05_pass": False, "06_pass": False,
                   "collection_status": "INCOMPLETE", "failures": [
                       {"gate": "BOTH", "code": "ROUND_EVIDENCE", "message": str(exc)}]}
        rounds.append(row)
    result = evaluate_batch(rounds)
    result.update(run_id=manifest["run_id"], manifest_sha256=manifest_sha, source=manifest["source"],
                  apk_sha256=manifest["apk_sha256"], collector=manifest["collector"], bindings=manifest["bindings"])
    if args.json_out:
        path = Path(args.json_out).resolve()
        path.parent.mkdir(parents=True, exist_ok=True)
        result["result_file"] = RunStore(path.parent).put(path.name, result)
    return result, 0 if result["pass"] else 1


def add_arguments(parser):
    commands = parser.add_subparsers(dest="pdf_action", required=True)
    init = commands.add_parser("init", help="bind a new five-slot run; no adb")
    init.add_argument("--out", required=True)
    init.add_argument("--apk", required=True)
    init.add_argument("--release-evidence", required=True)
    init.add_argument("--pdf-fixture-evidence", required=True)
    init.add_argument("--swipe-distance-px", required=True, type=int)
    init.add_argument("--swipe-ms", type=int, default=900)
    init.add_argument("--adb", default="adb")
    step = commands.add_parser("step", help="advance exactly one round in a bounded segment")
    step.add_argument("--out", required=True)
    step.add_argument("--round", required=True, type=int, choices=range(1, 6))
    step.add_argument("--execute", action="store_true", required=True)
    step.add_argument("--one-phase", action="store_true")
    resume = commands.add_parser("resume", help="local read-only by default; observe uses read-only adb")
    resume.add_argument("--out", required=True)
    resume.add_argument("--round", required=True, type=int, choices=range(1, 6))
    resume.add_argument("--observe", action="store_true")
    evaluate = commands.add_parser("evaluate", help="pure local evaluation; originals unchanged")
    evaluate.add_argument("--out", required=True)
    evaluate.add_argument("--json-out", default="")


def dispatch(args):
    try:
        if args.pdf_action == "init":
            result, code = init_run(args), 0
        elif args.pdf_action == "step":
            result, code = collect_step(args)
        elif args.pdf_action == "resume":
            result, code = resume_run(args)
        else:
            result, code = evaluate_run(args)
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError) as exc:
        result, code = {"status": "COLLECTOR_FAIL", "error": str(exc)}, 1
    print(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False))
    return code


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default=SERIAL)
    add_arguments(parser)
    sys.exit(dispatch(parser.parse_args()))
