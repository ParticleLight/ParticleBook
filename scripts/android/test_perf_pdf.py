#!/usr/bin/env python
"""Portable PBPDF tests. All generated maps/times/devices are explicitly synthetic."""
import argparse
import base64
import copy
import importlib.util
import io
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import uuid
import zlib

import perf_pdf as p

FIXTURES = Path(__file__).resolve().parent / "fixtures" / "pdf-perf-v1"
GOLDEN = json.loads((FIXTURES / "wire-golden.json").read_text(encoding="utf-8"))
GOOD = json.loads((FIXTURES / "complete-five-rounds.json").read_text(encoding="utf-8"))
CACHED = json.loads((FIXTURES / "cache-hit-three-page.json").read_text(encoding="utf-8"))


def lines(events, pid=4321):
    return "\n".join("1795270000.000 %d %d I PBPDF : %s" %
                     (pid, pid, json.dumps(e, separators=(",", ":"))) for e in events) + "\n"


def event_log(row):
    log = p.EventLog()
    log.add(lines(row["events"], row["pid"]), "synthetic.log")
    return log


def renumber(events):
    for seq, event in enumerate(events, 1):
        event["seq"] = seq
        if event["event"] == "session_close":
            event["final_seq"] = seq
    return events


def evaluated(fixture=GOOD):
    return [p.evaluate_round(copy.deepcopy(row["state"]), event_log(row)) for row in fixture["rounds"]]


class WireTests(unittest.TestCase):
    def test_input_markers_require_one_ordered_pair_for_the_command(self):
        begin = "PBPDFINPUT: PDF_test_BEGIN"
        end = "PBPDFINPUT: PDF_test_END"
        self.assertTrue(p.markers(begin + chr(10) + end, "PDF_test"))
        for rows in ([end], [end, begin], [begin, begin, end],
                     [begin, "PBPDFINPUT: PDF_other_END"]):
            with self.subTest(rows=rows):
                self.assertFalse(p.markers(chr(10).join(rows), "PDF_test"))

    def test_native_literal_golden_keeps_queued_total(self):
        self.assertTrue(GOLDEN["synthetic"])
        for event in GOLDEN["events"]:
            p.validate_event(event)
        pools, details, diagnostics = p.session_samples(p.inspect_session(GOLDEN["events"]))
        self.assertEqual(pools["open"], [20])
        for name in ("opening_render", "opening_present", "turn_render"):
            self.assertEqual(pools[name], GOLDEN["expected"][name + "_ms"])
        turn = details[-1]
        self.assertEqual((turn["total_ms"], turn["queue_ms"], turn["worker_ms"], turn["return_ms"]),
                         (126, 120, 5, 1))
        self.assertEqual(diagnostics, [])

    def test_duplicate_json_keys_and_nonfinite_rejected(self):
        for text in ('{"v":1,"v":1}', '{"x":NaN}', '{"x":Infinity}', '{"x":-Infinity}'):
            with self.subTest(text=text), self.assertRaises(ValueError):
                p.strict_json(text)

    def test_strict_types_and_explicit_nullable_fields(self):
        for key, value in (("v", True), ("session_id", 1.0), ("seq", -1),
                           ("ts_ns", True), ("process_id", "not-a-uuid"), ("v", 2)):
            bad = dict(GOLDEN["events"][0], **{key: value})
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                p.validate_event(bad)
        bad = copy.deepcopy(GOLDEN["events"][1])
        del bad["page"]
        with self.assertRaises(ValueError):
            p.validate_event(bad)

    def test_noncanonical_uuid_rejected(self):
        bad = dict(GOLDEN["events"][0], process_id=GOLDEN["events"][0]["process_id"].upper())
        with self.assertRaises(ValueError):
            p.validate_event(bad)

    def test_legacy_completion_log_is_not_v1_sample(self):
        log = p.EventLog()
        log.add("1795270000.000 4321 4321 I PBPERF : page_render ms=1 page=0")
        with self.assertRaises(ValueError):
            log.session(4321)
        self.assertEqual(log.rows, {})

    def test_snapshot_union_keeps_all_occurrences(self):
        log = p.EventLog()
        log.add(lines(GOLDEN["events"]), "first")
        log.add(lines(GOLDEN["events"]), "second")
        self.assertEqual(len(log.rows), 19)
        self.assertEqual(len(log.occurrences), 38)
        self.assertEqual(log.errors, [])

    def test_same_sequence_conflict_and_cross_pid_fail(self):
        for body, pid in ((dict(GOLDEN["events"][0], ts_ns=1), 4321), (GOLDEN["events"][0], 4322)):
            log = p.EventLog()
            log.add(lines(GOLDEN["events"]))
            log.add(lines([body], pid))
            self.assertEqual(len(log.errors), 1)

    def test_pid_reuse_session_and_missing_prefix_fail(self):
        log = p.EventLog()
        log.add(lines(GOLDEN["events"]))
        other = copy.deepcopy(GOLDEN["events"])
        for event in other:
            event["session_id"] = 2
        log.add(lines(other))
        with self.assertRaises(ValueError):
            log.session(4321)
        with self.assertRaises(ValueError):
            p.inspect_session(GOLDEN["events"][1:])

    def test_no_duration_derived_start_and_missing_boundaries(self):
        for name in ("op_queued", "op_begin", "request_start", "nav_begin", "session_close"):
            events = copy.deepcopy(GOLDEN["events"])
            index = next(i for i, e in enumerate(events) if e["event"] == name)
            events.pop(index)
            with self.subTest(name=name), self.assertRaises((ValueError, KeyError)):
                p.inspect_session(renumber(events))

    def test_reversed_queue_execution_delivery_and_worker_fail(self):
        for name, ts in (("op_begin", 0), ("op_end", 0)):
            events = copy.deepcopy(GOLDEN["events"])
            next(e for e in events if e["event"] == name)["ts_ns"] = ts
            with self.subTest(name=name), self.assertRaises(ValueError):
                p.inspect_session(events)
        events = copy.deepcopy(GOLDEN["events"])
        next(e for e in events if e["event"] == "op_end")["worker_end_ns"] = 0
        with self.assertRaises(ValueError):
            p.inspect_session(events)

    def test_producer_transfers_page_changes_and_claimed_future_nav_fail(self):
        for change in ("producer", "page", "nav"):
            events = copy.deepcopy(GOLDEN["events"])
            if change == "producer":
                next(e for e in events if e["event"] == "request_end")["producer_op_id"] = 3
            elif change == "page":
                next(e for e in events if e["event"] == "op_end" and e["kind"] == "render")["page"] = 2
            else:
                next(e for e in events if e["event"] == "request_start")["nav_id"] = 1
            with self.subTest(change=change), self.assertRaises(ValueError):
                p.inspect_session(events)

    def test_late_commit_never_resurrects_cancelled_request(self):
        for outcome in ("cancelled", "error", "closed"):
            events = copy.deepcopy(GOLDEN["events"])
            next(e for e in events if e["event"] == "request_end")["outcome"] = outcome
            with self.subTest(outcome=outcome), self.assertRaises(ValueError):
                p.inspect_session(events)

    def test_real_commit_fields_and_endpoints_required(self):
        present = next(e for e in GOLDEN["events"] if e["event"] == "page_present")
        for key, value in (("commit_ts", None), ("commit_ts", present["ts_ns"] - 1),
                           ("draw_ns", present["commit_ts"] + 1)):
            with self.subTest(key=key), self.assertRaises(ValueError):
                p.validate_event(dict(present, **{key: value}))

    def test_trailing_data_and_unclosed_object_fail(self):
        events = copy.deepcopy(GOLDEN["events"])
        events.append(dict(events[-1], seq=20, final_seq=20))
        with self.assertRaises(ValueError):
            p.inspect_session(events)
        events = [copy.deepcopy(e) for e in GOLDEN["events"] if e["event"] != "nav_end"]
        with self.assertRaises(ValueError):
            p.inspect_session(renumber(events))

    def test_final_seq_page_count_and_close_enums(self):
        bad = dict(GOLDEN["events"][-1], final_seq=20)
        with self.assertRaises(ValueError):
            p.validate_event(bad)
        for name in ("source_close", "text_close"):
            with self.assertRaises(ValueError):
                p.validate_event(dict(GOLDEN["events"][-1], **{name: "closed"}))
        events = copy.deepcopy(GOLDEN["events"])
        next(e for e in events if e["event"] == "op_end")["page_count"] = 12
        with self.assertRaises(ValueError):
            p.inspect_session(events)

    def test_reused_presentation_never_claims_opening_membership(self):
        base = [copy.deepcopy(e) for e in GOLDEN["events"]
                if e["event"] in {"session_start", "session_close"}
                or (e["event"].startswith("op_") and e.get("kind") == "open")
                or e["event"].startswith("nav_")]
        common = {"v": 1, "process_id": GOLDEN["events"][0]["process_id"], "session_id": 1}

        def session(opening, origin="reused"):
            events = list(base)
            index = next(i for i, e in enumerate(events) if e["event"] == "nav_begin")
            events[index:index] = [
                dict(common, event="request_start", ts_ns=1_500_000_000, request_id=7, page=0,
                     origin=origin, opening_member=opening, nav_id=None),
                dict(common, event="request_end", ts_ns=1_510_000_000, request_id=7, page=0,
                     outcome="ok", cache="reused", producer_op_id=None),
                dict(common, event="page_present", ts_ns=1_530_000_000, request_id=7, page=0,
                     producer_op_id=None, visible_ns=1_510_000_000, draw_ns=1_520_000_000,
                     commit_ts=1_530_000_000, outcome="ok"),
            ]
            return renumber(events)

        p.inspect_session(session(False))
        for opening, origin in ((True, "reused"), (False, "demand")):
            with self.subTest(opening=opening, origin=origin), \
                    self.assertRaisesRegex(p.Invalid, "opening membership"):
                p.inspect_session(session(opening, origin))


class EvaluationTests(unittest.TestCase):
    def test_complete_five_round_protocol_positive(self):
        result = p.evaluate_batch(evaluated())
        self.assertTrue(result["pass"], result["failures"])
        self.assertEqual(result["metrics"]["turn_render"]["n"], 50)
        self.assertEqual([r["round"] for r in result["rounds"]], [1, 2, 3, 4, 5])

    def test_cached_three_page_has_zero_attributable_samples(self):
        result = p.evaluate_batch(evaluated(CACHED))
        self.assertEqual(result["metrics"]["turn_render"], {"n": 0, "values_ms": [], "p50_ms": None, "p95_ms": None})
        self.assertFalse(result["05_pass"])
        self.assertTrue(result["06_pass"], result["failures"])
        self.assertEqual(result["collection_status"], "COMPLETE")
        self.assertIn("05_INSUFFICIENT_ATTRIBUTABLE_TURN_RENDER", [f["code"] for f in result["failures"]])

    def test_insufficient_and_overbudget_coexist(self):
        rounds = evaluated(CACHED)
        rounds[0]["metrics"]["turn_render"] = p.summary([126])
        result = p.evaluate_batch(rounds)
        codes = {f["code"] for f in result["failures"]}
        self.assertIn("05_INSUFFICIENT_ATTRIBUTABLE_TURN_RENDER", codes)
        self.assertIn("05_TURN_RENDER_BUDGET", codes)

    def test_complete_round_slots_required_without_replacement(self):
        for rows in (evaluated()[:4], evaluated() + evaluated()[:1]):
            result = p.evaluate_batch(rows)
            self.assertFalse(result["pass"])
            self.assertIn("FIVE_ROUNDS_REQUIRED", {f["code"] for f in result["failures"]})

    def test_nonce_reuse_is_not_cold(self):
        rows = evaluated()
        rows[1]["events"][0]["process_id"] = rows[0]["events"][0]["process_id"]
        self.assertIn("COLD_PROCESS_REUSED", {f["code"] for f in p.evaluate_batch(rows)["failures"]})

    def test_close_leak_and_total_pss_fail_only06(self):
        row = copy.deepcopy(GOOD["rounds"][0])
        row["events"][-1].update(cache_count=3, cache_bytes=35164800)
        row["state"]["memory"]["home"]["total_kb"] = 80000
        result = p.evaluate_round(row["state"], event_log(row))
        self.assertTrue(result["05_pass"])
        self.assertFalse(result["06_pass"])
        self.assertEqual(result["exit_delta_mib"], 30000 / 1024)
        self.assertEqual(result["memory"]["home"]["graphics_kb"], 2000)

    def test_graphics_not_subtracted_and_exact20_boundary(self):
        row = copy.deepcopy(GOOD["rounds"][0])
        row["state"]["memory"]["home"].update(total_kb=70480, graphics_kb=50000)
        self.assertTrue(p.evaluate_round(row["state"], event_log(row))["06_pass"])
        row["state"]["memory"]["home"]["total_kb"] += 1
        self.assertFalse(p.evaluate_round(row["state"], event_log(row))["06_pass"])

    def test_lost_original_home_anchor_does_not_become_new8s(self):
        row = copy.deepcopy(GOOD["rounds"][0])
        row["state"]["home_wait"]["end_ns"] -= 1
        self.assertFalse(p.evaluate_round(row["state"], event_log(row))["06_pass"])
        del row["state"]["home_wait"]
        self.assertFalse(p.evaluate_round(row["state"], event_log(row))["06_pass"])

    def test_ten_gestures_are_not_ten_native_turns(self):
        row = copy.deepcopy(GOOD["rounds"][0])
        row["state"]["turns"][-1]["nav_id"] = 9
        result = p.evaluate_round(row["state"], event_log(row))
        self.assertFalse(result["05_pass"])
        self.assertEqual(result["collection_status"], "INCOMPLETE")

    def test_failures_survive_successful_metrics(self):
        row = copy.deepcopy(GOOD["rounds"][0])
        row["state"]["failures"].append({"code": "UNKNOWN_ACTION_EXIT", "message": "input happened"})
        result = p.evaluate_round(row["state"], event_log(row))
        self.assertEqual(result["metrics"]["turn_render"]["n"], 10)
        self.assertFalse(result["05_pass"])
        self.assertFalse(result["06_pass"])

    def test_nearest_rank_not_interpolated(self):
        self.assertEqual(p.summary([5, 1, 4, 2, 3])["p95_ms"], 5)
        self.assertIsNone(p.summary([])["p95_ms"])


SOURCE = {"git_head": "a" * 40, "source_tree_algorithm": "synthetic-local-test", "source_tree_sha256": "b" * 64}


class Clock:
    def __init__(self):
        self.now = 1_000_000_000_000
    def __call__(self):
        return self.now
    def sleep(self, seconds):
        self.now += int(seconds * 1e9)


def synthetic_inputs(directory):
    root = Path(directory)
    apk = root / "synthetic.apk"
    apk.write_bytes(b"synthetic-test-only-not-an-Android-package")
    apk_sha = p.file_sha(apk)
    debug = "d" * 64
    release = dict(SOURCE, build_type="release", apksigner_verified=True,
                   signer_sha256="e" * 64, apk_sha256=apk_sha)
    (root / "release.json").write_text(json.dumps(release), encoding="utf-8")
    row = {"id": 204, "title": p.BOOK_TITLE, "format": "pdf", "filePath": p.PDF_PATH, "fileSize": 3886}
    ids = [1,2,3,4,101,102,103,104,201,202,203,204,205,206,207,208]
    books = [row if i == 204 else {"id": i} for i in ids]
    raw = {"binding_commands": json.dumps([{"returncode": 0, "argv": ["SYNTHETIC"]}]),
           "device_sha256": p.PDF_SHA + "  " + p.PDF_PATH + "\n", "device_size": "3886\n",
           "books": json.dumps({"books": books}), "installed_apk": debug + "  /synthetic/base.apk\n"}
    for key, value in raw.items():
        (root / (key + ".txt")).write_text(value, encoding="utf-8")
    (root / "install.txt").write_text("Success\n", encoding="utf-8")
    fixture = {"device_serial": p.SERIAL, "package": p.PKG, "checked_via": "adb-exec-out-run-as",
               "checked_apk_sha256": debug, "raw_evidence": {k: k + ".txt" for k in raw},
               "qa204_book_record_live": row,
               "qa204_device_file": {"sha256": p.PDF_SHA, "path": p.PDF_PATH, "size_bytes": 3886},
               "retained_install": {"from_apk_sha256": debug, "to_apk_sha256": apk_sha,
                                    "data_cleared": False, "method": "adb-install-r", "exit_code": 0,
                                    "stdout": "Success", "log_path": "install.txt"}}
    (root / "fixture.json").write_text(json.dumps(fixture), encoding="utf-8")
    return argparse.Namespace(out=str(root / "run"), apk=str(apk), serial=p.SERIAL,
                              release_evidence=str(root / "release.json"), pdf_fixture_evidence=str(root / "fixture.json"),
                              swipe_distance_px=900, swipe_ms=900, adb="FAKE_ADB_NOT_AN_EXECUTABLE")


def png():
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1440, 2960, 8, 0, 0, 0, 0)) +
            chunk(b"IDAT", zlib.compress(b"\x00" * (1441 * 2960), 1)) + chunk(b"IEND", b""))


class FakeDevice:
    """ADB argv interpreter. Every mutation passes through the production journal."""
    image = png()
    def __init__(self, apk_sha, clock, cached=False):
        self.apk_sha, self.clock = apk_sha, clock
        self.fixture = CACHED if cached else GOOD
        self.calls, self.mutations, self.logs = [], [], ""
        self.pid, self.starts, self.reader, self.home, self.page, self.nav = None, 0, False, False, 0, 0
        self.current = []

    def emit(self, events):
        self.logs += lines(events, self.pid)

    def open(self):
        self.reader, self.home, self.page, self.nav = True, False, 0, 0
        index = ((self.starts - 1) // 2) % 5
        self.current = copy.deepcopy(self.fixture["rounds"][index]["events"])
        nonce = str(uuid.uuid5(uuid.NAMESPACE_OID, "synthetic-pid-" + str(self.pid)))
        for event in self.current:
            event["process_id"] = nonce
        self.emit([e for e in self.current if e["ts_ns"] < next(
            x["ts_ns"] for x in self.current if x["event"] == "nav_begin")])

    def ui(self):
        if self.reader:
            body = ('<node package="%s" text="%d / 3" bounds="[0,0][1440,100]" />'
                    '<node package="%s" content-desc="第 %d 页" bounds="[0,150][1440,2700]" />') % (
                        p.PKG, self.page + 1, p.PKG, self.page + 1)
        else:
            body = ('<node package="%s" text="书架" bounds="[0,0][1440,100]" />'
                    '<node package="%s" bounds="[56,700][699,1300]">'
                    '<node package="%s" content-desc="《%s》" bounds="[60,1100][690,1200]" />'
                    '<node package="%s" clickable="true" bounds="[56,700][699,1099]" />'
                    '</node>') % (p.PKG, p.PKG, p.PKG, p.BOOK_TITLE, p.PKG)
        return '<hierarchy><node package="%s" bounds="[0,0][1440,2960]">%s</node></hierarchy>' % (p.PKG, body)

    def __call__(self, argv, capture_output, timeout):
        assert capture_output and 0 < timeout <= 30
        assert argv[:3] == ["FAKE_ADB_NOT_AN_EXECUTABLE", "-s", p.SERIAL]
        self.clock.sleep(.005)
        a = argv[3:]
        self.calls.append(a)
        code, out = 0, ""
        if a == ["get-serialno"]:
            out = p.SERIAL
        elif a == ["shell", "getprop", "ro.product.model"]:
            out = "Pixel 3 XL"
        elif a == ["shell", "getprop", "ro.build.version.sdk"]:
            out = "31"
        elif a == ["shell", "getprop", "ro.build.fingerprint"]:
            out = "google/crosshatch/crosshatch:12/SP1A.210812.016.C1/8029091:user/release-keys"
        elif a == ["shell", "getconf", "PAGESIZE"]:
            out = "4096"
        elif a == ["shell", "wm", "size"]:
            out = "Physical size: 1440x2960"
        elif a == ["shell", "dumpsys", "package", p.PKG]:
            out = "flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]"
        elif a == ["shell", "pm", "path", p.PKG]:
            out = "package:/synthetic/base.apk"
        elif a == ["shell", "sha256sum", "/synthetic/base.apk"]:
            out = self.apk_sha + "  /synthetic/base.apk"
        elif a[:2] == ["shell", "pidof"]:
            out = str(990 if a[2] != p.PKG else self.pid) if a[2] != p.PKG or self.pid else ""
            code = 0 if out else 1
        elif a == ["shell", "am", "force-stop", p.PKG]:
            self.mutations.append(a)
            self.pid, self.reader, self.home = None, False, False
        elif a[:4] == ["shell", "am", "start", "-W"]:
            self.mutations.append(a)
            self.starts += 1
            self.pid, self.home, self.reader = 3000 + self.starts, False, False
            out = "Status: ok\nLaunchState: COLD\n"
        elif a == ["shell", "dumpsys", "activity", "activities"]:
            package, pid = ("com.google.android.apps.nexuslauncher", 990) if self.home else (p.PKG, self.pid)
            out = ("RootTask #1\n * Hist #0: ActivityRecord{abc u0 %s/.Main t1}\n"
                   "    app=ProcessRecord{def %s:%s/u0a123}\n"
                   " mResumedActivity: ActivityRecord{abc u0 %s/.Main t1}\n"
                   " topResumedActivity=ActivityRecord{abc u0 %s/.Main t1}\n") % (package, pid, package, package, package)
        elif a == ["shell", "dumpsys", "window"]:
            package = "com.google.android.apps.nexuslauncher" if self.home else p.PKG
            out = "mCurrentFocus=Window{xyz u0 " + package + "/.Main}"
        elif a == ["exec-out", "uiautomator", "dump", "/dev/tty"]:
            out = self.ui()
        elif a[:3] == ["shell", "input", "tap"]:
            self.mutations.append(a)
            self.open()
        elif len(a) == 2 and a[0] == "shell" and a[1].startswith("log -p i -t PBPDFINPUT "):
            self.mutations.append(a)
            token = re.search(r"PBPDFINPUT (\w+)_BEGIN", a[1])[1]
            self.logs += "1795270000.000 %d %d I PBPDFINPUT : %s_BEGIN\n" % (self.pid, self.pid, token)
            self.nav += 1
            starts = [e for e in self.current if e["event"] == "nav_begin"]
            left = starts[self.nav - 1]["ts_ns"]
            right = starts[self.nav]["ts_ns"] if self.nav < 10 else 6_000_000_000
            part = [e for e in self.current if left <= e["ts_ns"] < right and e["event"] != "session_close"]
            self.emit(part)
            self.page = next(e["to_page"] for e in part if e["event"] == "nav_end")
            self.logs += "1795270000.000 %d %d I PBPDFINPUT : %s_END\n" % (self.pid, self.pid, token)
        elif a == ["shell", "input", "keyevent", "4"]:
            self.mutations.append(a)
            session = [json.loads(line.split("PBPDF : ", 1)[1]) for line in self.logs.splitlines()
                       if "PBPDF : " in line and (" %d %d " % (self.pid, self.pid)) in line]
            close = copy.deepcopy(self.current[-1])
            close["seq"] = close["final_seq"] = len(session) + 1
            self.emit([close])
            self.reader = False
        elif a == ["shell", "input", "keyevent", "3"]:
            self.mutations.append(a)
            self.home = True
        elif a == ["shell", "dumpsys", "meminfo", p.PKG]:
            total = 60000 if self.home else (130000 if self.reader else 50000)
            out = "** MEMINFO in pid %d [%s] **\n TOTAL PSS: %d\n Graphics: 1000\n" % (self.pid, p.PKG, total)
        elif a == ["exec-out", "screencap", "-p"]:
            out = self.image
        elif a[:3] == ["shell", "logcat", "-d"]:
            out = self.logs
        else:
            raise AssertionError("unexpected/unsafe fake adb: " + repr(a))
        return subprocess.CompletedProcess(argv, code, out if isinstance(out, bytes) else out.encode("utf-8"), b"")


class Crash(BaseException):
    pass


class JournalTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="pbpdf-test-")
        self.addCleanup(self.temp.cleanup)
        self.args = synthetic_inputs(self.temp.name)
        p.init_run(self.args, source=lambda: SOURCE)
        self.clock = Clock()
        self.fake = FakeDevice(p.file_sha(self.args.apk), self.clock)

    def step(self, number=1, one=True):
        args = argparse.Namespace(out=self.args.out, round=number, execute=True, one_phase=one)
        return p.collect_step(args, self.fake, lambda: SOURCE, self.clock, self.clock.sleep)

    def resume(self, observe=False):
        args = argparse.Namespace(out=self.args.out, round=1, observe=observe)
        return p.resume_run(args, self.fake, lambda: SOURCE, self.clock, self.clock.sleep)

    def pending_open(self, point):
        self.assertEqual(self.step()[1], 0)
        self.assertEqual(self.step()[1], 0)
        store = p.RunStore(self.args.out)
        manifest, state, _, _ = store.load(1)
        self.assertEqual(state["phase"], "PRESET_OPEN")
        fired = []
        def crash(where):
            if where == point and not fired:
                fired.append(where)
                raise Crash(where)
        store.fault = crash
        device = p.Device(store, state, manifest, p.Deadline(self.clock, self.clock.sleep), self.fake)
        with self.assertRaises(Crash):
            device.call("shell", "input", "tap", 720, 1000,
                        action={"kind": "open", "next_phase": "PRESET_RESET", "preset": True})
        return store

    def test_init_readonly_and_five_independent_slots(self):
        self.assertEqual(self.fake.calls, [])
        for number in range(1, 6):
            _, state, revision, _ = p.RunStore(self.args.out).load(number)
            self.assertEqual((state["round"], state["phase"], revision), (number, "PRESET_STOP", 1))
        before = sorted(Path(self.args.out).rglob("*"))
        self.assertEqual(self.resume()[1], 0)
        self.assertEqual(before, sorted(Path(self.args.out).rglob("*")))
        self.assertEqual(self.fake.calls, [])

    def test_existing_run_and_evidence_never_overwritten(self):
        with self.assertRaises(FileExistsError):
            p.init_run(self.args, source=lambda: SOURCE)
        store = p.RunStore(self.args.out)
        ref = store.put("test/original.raw", b"first")
        with self.assertRaises(FileExistsError):
            store.put("test/original.raw", b"replacement")
        self.assertEqual(store.get(ref), b"first")

    def test_one_phase_boundary_and_actual_action_count(self):
        result, code = self.step()
        self.assertEqual((code, result["phase"]), (0, "PRESET_START"))
        self.assertEqual(len(self.fake.mutations), 1)
        self.assertLess(self.clock.now - 1_000_000_000_000, 120_000_000_000)
        _, untouched, revision, _ = p.RunStore(self.args.out).load(2)
        self.assertEqual(revision, 1)
        self.assertEqual(untouched["phase"], "PRESET_STOP")

    def test_full_five_round_fake_entry_preserves_all_turns(self):
        for number in range(1, 6):
            result, code = self.step(number, one=False)
            self.assertEqual(code, 0, result)
            self.assertEqual((result["phase"], result["turn_count"]), ("SEALED", 10))
        result, code = p.evaluate_run(argparse.Namespace(out=self.args.out, json_out=""))
        self.assertEqual(code, 0, result["failures"])
        self.assertTrue(result["pass"])
        self.assertEqual(result["metrics"]["turn_render"]["n"], 50)
        self.assertEqual(sum(1 for a in self.fake.mutations
                             if len(a) == 2 and a[0] == "shell" and "input swipe" in a[1]), 50)

    def test_dispatch_only_crash_never_reissues_input(self):
        self.pending_open("after_dispatch")
        before = copy.deepcopy(self.fake.mutations)
        self.assertEqual(self.resume()[1], 1)
        result, code = self.resume(True)
        self.assertEqual(code, 1)
        self.assertIsNotNone(result["pending"])
        self.assertEqual(self.fake.mutations, before)
        with self.assertRaises(ValueError):
            self.step()
        self.assertEqual(self.fake.mutations, before)

    def test_executed_unknown_exit_observed_without_fake_zero(self):
        self.pending_open("after_execute")
        before = copy.deepcopy(self.fake.mutations)
        result, code = self.resume(True)
        self.assertEqual(code, 1, result)
        self.assertEqual(result["phase"], "PRESET_RESET")
        self.assertEqual(self.fake.mutations, before)
        _, state, _, _ = p.RunStore(self.args.out).load(1)
        self.assertEqual(state["actions"][-1]["status"], "observed_performed")
        self.assertIsNone(state["actions"][-1].get("returncode"))
        self.assertIn("UNKNOWN_COMMAND_EXIT", {x["code"] for x in state["failures"]})

    def test_original_receipt_after_crash_recovers_real_exit(self):
        self.pending_open("after_receipt")
        before = copy.deepcopy(self.fake.mutations)
        result, code = self.resume(True)
        self.assertEqual(code, 0, result)
        self.assertEqual(result["phase"], "PRESET_RESET")
        self.assertEqual(self.fake.mutations, before)
        _, state, _, _ = p.RunStore(self.args.out).load(1)
        self.assertEqual(state["actions"][-1]["returncode"], 0)
        self.assertEqual(state["actions"][-1]["status"], "CONFIRMED")

    def test_planned_without_dispatch_can_start_once_later(self):
        self.pending_open("after_plan")
        before = len(self.fake.mutations)
        result, code = self.resume(True)
        self.assertEqual(code, 0, result)
        self.assertIsNone(result["pending"])
        self.assertEqual(len(self.fake.mutations), before)
        self.assertEqual(self.step()[1], 0)
        self.assertEqual(len(self.fake.mutations), before + 1)

    def test_partial_stdout_kept_and_unknown_exit_sticky(self):
        self.pending_open("after_stdout")
        result, code = self.resume(True)
        self.assertEqual(code, 1, result)
        _, state, _, _ = p.RunStore(self.args.out).load(1)
        self.assertTrue(any(r["path"].endswith(".stdout") for r in state["evidence"]))
        self.assertIn("UNKNOWN_COMMAND_EXIT", {x["code"] for x in state["failures"]})

    def test_timeout_preserves_partial_stdout_stderr_and_null_exit(self):
        store = p.RunStore(self.args.out)
        manifest, state, _, _ = store.load(1)
        def timeout(argv, **kwargs):
            raise subprocess.TimeoutExpired(argv, kwargs["timeout"], output=b"partial-out", stderr=b"partial-err")
        device = p.Device(store, state, manifest, p.Deadline(self.clock, self.clock.sleep), timeout)
        with self.assertRaises(ValueError):
            device.call("shell", "logcat", "-d")
        rows, errors = p.audit_commands(p.RunStore(self.args.out), state)
        receipt = next(iter(rows.values()))["receipt"]
        self.assertIsNone(receipt["returncode"])
        self.assertEqual(store.get(receipt["stdout"]), b"partial-out")
        self.assertEqual(store.get(receipt["stderr"]), b"partial-err")
        self.assertTrue(errors)

    def test_manifest_raw_tamper_and_checkpoint_gap_fail_closed(self):
        store = p.RunStore(self.args.out)
        manifest, state, _, _ = store.load(1)
        source = manifest["bindings"]["pdf-raw-device_sha256"]
        (store.root / source["path"]).write_bytes(b"changed")
        with self.assertRaises(ValueError):
            p.RunStore(self.args.out).load(1)

    def test_torn_checkpoint_and_os_single_writer(self):
        store = p.RunStore(self.args.out)
        path = store.root / "round01/checkpoints/000002.json.tmp-test"
        path.write_bytes(b"half")
        with self.assertRaises(ValueError):
            store.load(1)
        path.unlink()
        with store.writer():
            with self.assertRaises((OSError, ValueError)):
                with p.RunStore(self.args.out).writer():
                    self.fail("two writers acquired the same OS lock")

    def test_source_drift_is_sticky_and_no_input(self):
        args = argparse.Namespace(out=self.args.out, round=1, execute=True, one_phase=True)
        result, code = p.collect_step(args, self.fake, lambda: dict(SOURCE, source_tree_sha256="f" * 64),
                                      self.clock, self.clock.sleep)
        self.assertEqual(code, 1)
        self.assertEqual(self.fake.mutations, [])
        self.assertIn("SEGMENT_FAILED", {x["code"] for x in result["failures"]})

    def test_default_resume_and_evaluate_never_call_subprocess(self):
        with mock.patch.object(subprocess, "run", side_effect=AssertionError("subprocess forbidden")):
            self.assertEqual(self.resume()[1], 0)
            result, code = p.evaluate_run(argparse.Namespace(out=self.args.out, json_out=""))
            self.assertEqual(code, 1)
            self.assertEqual(result["metrics"]["turn_render"]["n"], 0)



class SegmentBudgetTests(unittest.TestCase):
    """Segment exhaustion must pause, never turn a correct round into a failure."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="pbpdf-seg-")
        self.addCleanup(self.temp.cleanup)
        self.args = synthetic_inputs(self.temp.name)
        p.init_run(self.args, source=lambda: SOURCE)
        self.clock = Clock()
        self.fake = FakeDevice(p.file_sha(self.args.apk), self.clock)

    def test_exhausted_segment_pauses_without_sticky_failure(self):
        base = self.clock
        seen = []

        def clock():
            seen.append(1)
            if len(seen) > 3:
                base.now += 200_000_000_000
            return base.now

        args = argparse.Namespace(out=self.args.out, round=1, execute=True, one_phase=True)
        result, code = p.collect_step(args, self.fake, lambda: SOURCE, clock, base.sleep)
        self.assertEqual(code, 0)
        self.assertEqual(result["status"], "PAUSED")
        self.assertEqual(result["reason"], "SEGMENT_EXHAUSTED")
        _, state, _, _ = p.RunStore(self.args.out).load(1)
        self.assertEqual(state["failures"], [])
        self.assertEqual(state["phase"], "PRESET_STOP")


class OpeningBudgetTests(unittest.TestCase):
    """Only a delivered render may charge the opening budget."""

    def test_cancelled_opening_render_is_not_an_opening_sample(self):
        events = copy.deepcopy(GOLDEN["events"])
        end = next(e for e in events
                   if e["event"] == "request_end" and e["request_id"] == 1)
        # A producer can finish successfully on the worker while the owner
        # cancels before cache delivery (observed on the fixed QA204 fixture).
        end.update(outcome="cancelled", cache=None)
        present = next(e for e in events
                       if e["event"] == "page_present" and e["request_id"] == 1)
        present.update(outcome="cancelled", draw_ns=None, commit_ts=None)
        pools, details, _ = p.session_samples(p.inspect_session(events))
        opening = next(item for item in details if item["op_id"] == 2)
        self.assertEqual(opening["outcome"], "ok")
        self.assertEqual(pools["opening_render"], [])
        self.assertNotIn(opening["total_ms"], pools["all_render"])
        self.assertEqual(GOLDEN["expected"]["opening_render_ms"], [10.0])
        self.assertNotEqual(pools["all_render"], [])

    def test_unresolved_cache_requires_failed_request_and_own_producer(self):
        for outcome, cache, producer, message in (
            ("ok", None, 2, "successful request has no cache resolution"),
            ("cancelled", None, 3, "unresolved cache producer belongs to another request"),
            ("ok", "hit", 2, "non-miss producer is its own render"),
            ("ok", "join", 2, "non-miss producer is its own render"),
        ):
            with self.subTest(outcome=outcome, cache=cache, producer=producer):
                events = copy.deepcopy(GOLDEN["events"])
                req = next(e for e in events
                           if e["event"] == "request_end" and e["request_id"] == 1)
                req.update(outcome=outcome, cache=cache, producer_op_id=producer)
                if outcome == "cancelled":
                    # Keep producer page equal so the ownership check is decisive.
                    req_start = next(e for e in events
                                     if e["event"] == "request_start" and e["request_id"] == 1)
                    foreign = next(e for e in events
                                   if e["event"] == "op_queued" and e["op_id"] == producer)
                    foreign["page"] = req_start["page"]
                with self.assertRaisesRegex(ValueError, message):
                    p.inspect_session(events)

    def test_reused_request_cannot_own_a_render_op(self):
        events = copy.deepcopy(GOLDEN["events"])
        op_id = next(e["op_id"] for e in events
                     if e["event"] == "op_queued" and e["opening_member"])
        for item in events:
            if item.get("op_id") == op_id:
                item["origin"] = "reused"
                item["opening_member"] = False
        start = next(e for e in events
                     if e["event"] == "request_start" and e["request_id"] == 1)
        start.update(origin="reused", opening_member=False)
        end = next(e for e in events
                   if e["event"] == "request_end" and e["request_id"] == 1)
        end.update(cache="reused", producer_op_id=None)
        present = next(e for e in events
                       if e["event"] == "page_present" and e["request_id"] == 1)
        present.update(producer_op_id=None)
        with self.assertRaises(ValueError) as caught:
            p.inspect_session(events)
        self.assertIn("reused presentation consumed a render operation", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
