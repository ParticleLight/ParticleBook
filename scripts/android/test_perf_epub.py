#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Offline negative controls for the P1 EPUB performance collector."""
import collections
import dataclasses
import gzip
import hashlib
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import perf_epub as pe
import fake_turns as ft


def event_line(phase, ms, book_id=201, chapter=6, page=0, text_only=1,
               start_ns=None, turn=None, end_kind="commit", pid=1234,
               timestamp="11-20 20:00:00.000"):
    if start_ns is None:
        start_ns = 1_000_000_000 + (turn or 0) * 1_000_000_000
    end_ns = start_ns + round(ms * 1_000_000)
    draw_ns = start_ns + (end_ns - start_ns) // 2
    fields = [
        "book_id:%d" % book_id,
        "chapter:%d" % chapter,
        "page:%d" % page,
        "text_only:%d" % text_only,
        "start_ns:%d" % start_ns,
        "draw_end_ns:%d" % draw_ns,
        "end_ns:%d" % end_ns,
        "end_kind:%s" % end_kind,
    ]
    if turn is not None:
        fields.append("turn:%d" % turn)
    body = "PBPERF|phase=%s|ms=%s|extra=%s" % (phase, ms, ",".join(fields))
    return "%s %d %d I PBPERF : %s" % (timestamp, pid, pid, body)


def first_event(ms=400, **kw):
    return pe.parse_perf_line(event_line(pe.FIRST_PHASE, ms, **kw))


def page_event(turn, page, ms=12, **kw):
    return pe.parse_perf_line(event_line(pe.PAGE_PHASE, ms, page=page,
                                         turn=turn, **kw))


class ParserTests(unittest.TestCase):
    def test_parse_first_and_page(self):
        a = first_event()
        self.assertEqual(a.phase, pe.FIRST_PHASE)
        self.assertEqual(a.chapter, 6)
        self.assertEqual(a.page, 0)
        self.assertTrue(a.text_only)
        self.assertTrue(a.formal)
        self.assertEqual(a.pid, 1234)
        b = page_event(3, 4)
        self.assertEqual(b.turn, 3)
        self.assertEqual(b.page, 4)

    def test_unknown_phase_is_ignored(self):
        line = "11-20 20:00:00.000 1 1 I PBPERF : PBPERF|phase=turn|ms=5|extra=book_id:1"
        self.assertIsNone(pe.parse_perf_line(line))

    def test_malformed_missing_field_rejected(self):
        line = "PBPERF|phase=reader_epub_first_chapter|ms=10|extra=book_id:1,chapter:6"
        with self.assertRaises(pe.PerfParseError):
            pe.parse_perf_line(line)

    def test_malformed_extra_rejected(self):
        line = "PBPERF|phase=reader_epub_first_chapter|ms=10|extra=book_id=1"
        with self.assertRaises(pe.PerfParseError):
            pe.parse_perf_line(line)

    def test_negative_ms_rejected(self):
        line = "PBPERF|phase=reader_epub_first_chapter|ms=-1|extra=" + \
            "book_id:1,chapter:6,page:0,text_only:1,start_ns:1,draw_end_ns:2,end_ns:3,end_kind:commit"
        with self.assertRaises(pe.PerfParseError):
            pe.parse_perf_line(line)

    def test_cursor_excludes_old_events(self):
        old = event_line(pe.FIRST_PHASE, 100, start_ns=1_000_000_000)
        new = event_line(pe.FIRST_PHASE, 200, start_ns=9_000_000_000)
        self.assertEqual(pe.select_new_lines([old], [old, new]), [new])
        self.assertEqual(pe.select_new_lines([old], [old]), [])
        self.assertEqual(pe.select_new_lines([], [new]), [new])


class FirstChapterTests(unittest.TestCase):
    def test_missing_first_event_fails(self):
        v = pe.validate_first_chapter([], 6, 0, min_samples=1)
        self.assertFalse(v.pass_)
        self.assertIn("MISSING_FIRST_EVENT", [f["code"] for f in v.failures])

    def test_valid_sample_passes(self):
        v = pe.validate_first_chapter([first_event()], 6, 0, min_samples=1)
        self.assertTrue(v.pass_, v.as_dict())

    def test_draw_only_is_not_formal(self):
        v = pe.validate_first_chapter([first_event(end_kind="draw")], 6, 0, min_samples=1)
        self.assertFalse(v.pass_)
        self.assertIn("NO_COMMIT_SAMPLE", [f["code"] for f in v.failures])

    def test_image_page_fails(self):
        v = pe.validate_first_chapter([first_event(text_only=0)], 6, 0, min_samples=1)
        self.assertFalse(v.pass_)
        self.assertIn("IMAGE_PAGE", [f["code"] for f in v.failures])

    def test_chapter_mismatch_fails(self):
        v = pe.validate_first_chapter([first_event(chapter=0)], 6, 0, min_samples=1)
        self.assertFalse(v.pass_)
        self.assertIn("CHAPTER_MISMATCH", [f["code"] for f in v.failures])

    def test_page_restore_fails_and_is_excluded(self):
        v = pe.validate_first_chapter([first_event(page=2)], 6, 0, min_samples=1)
        self.assertFalse(v.pass_)
        self.assertIn("PAGE_RESTORED", [f["code"] for f in v.failures])
        self.assertIn("INSUFFICIENT_SAMPLES", [f["code"] for f in v.failures])

    def test_book_id_mismatch_fails(self):
        v = pe.validate_first_chapter([first_event(book_id=999)], 6, 0,
                                      expected_book_id=201, min_samples=1)
        self.assertFalse(v.pass_)
        self.assertIn("BOOK_ID_MISMATCH", [f["code"] for f in v.failures])

    def test_insufficient_samples_fails(self):
        evs = [first_event(start_ns=1_000_000_000), first_event(start_ns=2_000_000_000)]
        v = pe.validate_first_chapter(evs, 6, 0, min_samples=5)
        self.assertFalse(v.pass_)
        self.assertIn("INSUFFICIENT_SAMPLES", [f["code"] for f in v.failures])

    def test_budget_fails(self):
        evs = [first_event(ms=501)]
        v = pe.validate_first_chapter(evs, 6, 0, min_samples=1)
        self.assertFalse(v.pass_)
        self.assertIn("FIRST_FRAME_BUDGET", [f["code"] for f in v.failures])


class TurnTests(unittest.TestCase):
    def forward(self, n=20, ms=12, text_only=1, chapter=59):
        return [page_event(i + 1, i + 1, ms=ms, text_only=text_only, chapter=chapter) for i in range(n)]

    def test_forward_pages_pass(self):
        v = pe.validate_page_events(self.forward(), 59, expected_turns=20,
                                    start_page=1)
        self.assertTrue(v.pass_, v.as_dict())

    def test_missing_page_event_fails(self):
        v = pe.validate_page_events([], 59, expected_turns=20, start_page=1)
        self.assertFalse(v.pass_)
        self.assertIn("MISSING_PAGE_EVENTS", [f["code"] for f in v.failures])

    def test_image_page_fails(self):
        v = pe.validate_page_events(self.forward(text_only=0), 59,
                                    expected_turns=20, start_page=1)
        self.assertFalse(v.pass_)
        self.assertIn("IMAGE_PAGE", [f["code"] for f in v.failures])

    def test_turn_gap_fails(self):
        evs = self.forward()
        evs[4] = page_event(5, 5)
        # Replace turn 5 with turn 9 while keeping page adjacency.
        evs[4] = page_event(9, 5)
        v = pe.validate_page_events(evs, 59, expected_turns=20, start_page=1)
        self.assertFalse(v.pass_)
        self.assertIn("TURN_SEQUENCE", [f["code"] for f in v.failures])

    def test_reverse_or_same_page_fails_forward(self):
        evs = self.forward()
        evs[1] = page_event(2, 1)
        v = pe.validate_page_events(evs, 59, expected_turns=20, start_page=1)
        self.assertFalse(v.pass_)
        self.assertIn("PAGE_NOT_CHANGED", [f["code"] for f in v.failures])

    def test_non_adjacent_fails_forward(self):
        evs = self.forward()
        evs[2] = page_event(3, 5)
        v = pe.validate_page_events(evs, 59, expected_turns=20, start_page=1)
        self.assertFalse(v.pass_)
        self.assertIn("PAGE_NOT_ADJACENT_FORWARD", [f["code"] for f in v.failures])

    def test_cached_pingpong_insufficient_distinct_pages(self):
        evs = []
        for i in range(20):
            page = 1 if i % 2 == 0 else 2
            evs.append(page_event(i + 1, page))
        v = pe.validate_page_events(evs, 59, expected_turns=20, start_page=1)
        self.assertFalse(v.pass_)
        self.assertIn("INSUFFICIENT_DISTINCT_PAGES", [f["code"] for f in v.failures])

    def test_start_page_mismatch_fails(self):
        v = pe.validate_page_events(self.forward(), 59, expected_turns=20, start_page=2)
        self.assertFalse(v.pass_)
        self.assertIn("START_PAGE_MISMATCH", [f["code"] for f in v.failures])

    def test_input_to_commit_is_separate_from_frame_gate(self):
        v = pe.validate_page_events(self.forward(ms=17), 59, expected_turns=20,
                                    start_page=1)
        self.assertTrue(v.pass_, v.as_dict())


class FrameTests(unittest.TestCase):
    def frame(self, draw=1_050_000_000, queued=1_060_000_000,
              completed=1_066_000_000, flags=0):
        return pe.FrameStat(flags=flags, intended_vsync_ns=1_000_000_000,
                            draw_start_ns=draw, sync_queued_ns=queued,
                            frame_completed_ns=completed)

    def test_unique_frame_match(self):
        ev = page_event(1, 1)
        ev = pe.PerfEvent(phase=ev.phase, ms=ev.ms,
                          extra=dict(ev.extra, draw_end_ns="1050000000"),
                          raw=ev.raw, pid=ev.pid)
        frame, cands = pe.match_frames_for_event(ev, [self.frame()])
        self.assertIsNotNone(frame)
        self.assertEqual(len(cands), 1)

    def test_ambiguous_frame_fails(self):
        ev = page_event(1, 1)
        ev = pe.PerfEvent(phase=ev.phase, ms=ev.ms,
                          extra=dict(ev.extra, draw_end_ns="1050000000"),
                          raw=ev.raw, pid=ev.pid)
        frames = [self.frame(draw=1_040_000_000, queued=1_060_000_000),
                  self.frame(draw=1_045_000_000, queued=1_065_000_000)]
        v = pe.Verdict()
        pe.validate_frames([ev], frames, v)
        self.assertFalse(v.pass_)
        self.assertIn("AMBIGUOUS_FRAME", [f["code"] for f in v.failures])

    def test_missing_frame_fails(self):
        ev = page_event(1, 1)
        ev = pe.PerfEvent(phase=ev.phase, ms=ev.ms,
                          extra=dict(ev.extra, draw_end_ns="999999999"),
                          raw=ev.raw, pid=ev.pid)
        v = pe.Verdict()
        pe.validate_frames([ev], [self.frame()], v)
        self.assertFalse(v.pass_)
        self.assertIn("FRAME_NOT_FOUND", [f["code"] for f in v.failures])

    def test_frame_flags_fail(self):
        ev = page_event(1, 1)
        ev = pe.PerfEvent(phase=ev.phase, ms=ev.ms,
                          extra=dict(ev.extra, draw_end_ns="1050000000"),
                          raw=ev.raw, pid=ev.pid)
        v = pe.Verdict()
        pe.validate_frames([ev], [self.frame(flags=1)], v)
        self.assertFalse(v.pass_)
        self.assertIn("FRAME_FLAGS", [f["code"] for f in v.failures])

    def test_framestats_parse_and_raw_p95(self):
        header = "Flags,IntendedVsync,Vsync,OldestInputEvent,NewestInputEvent,HandleInputStart,AnimationStart,PerformTraversalsStart,DrawStart,SyncQueued,SyncStart,IssueDrawCommandsStart,SwapBuffers,FrameCompleted,DequeueBufferDuration,QueueBufferDuration,GpuCompleted,SwapBuffersCompleted,DisplayPresentTime"
        rows = []
        for i in range(3):
            base = 1_000_000_000 + i * 1_000_000_000
            fields = [0, base, base, 0, 0, base, base, base,
                      base + 10_000_000, base + 12_000_000,
                      base + 14_000_000, base + 16_000_000,
                      base + 18_000_000, base + 16_000_000, 0, 0,
                      base + 16_000_000, base + 18_000_000,
                      base + 18_000_000]
            rows.append(",".join(str(v) for v in fields))
        text = "Graphics info\n" + header + "\n" + "\n".join(rows) + "\n"
        frames = pe.parse_framestats(text)
        self.assertEqual(len(frames), 3)
        self.assertAlmostEqual(frames[0].duration_ms, 16.0)
        gfx = pe.parse_gfxinfo_summary("Total frames rendered: 3\nJanky frames (legacy): 1 (33.33%)\n")
        self.assertEqual(gfx["total_frames"], 3)
        self.assertAlmostEqual(gfx["janky_percent"], 33.33)

    def test_gfx_jank_budget_fails(self):
        ev = page_event(1, 1, start_ns=1_000_000_000)
        frame = pe.FrameStat(0, 1_000_000_000, 1_005_000_000, 1_007_000_000, 1_017_000_000)
        v = pe.Verdict()
        text = "Total frames rendered: 1\nJanky frames (legacy): 1 (100.00%)\n"
        pe.evaluate_gfxinfo(text, [frame], [ev], v)
        codes = [f["code"] for f in v.failures]
        self.assertIn("JANKY_BUDGET", codes)
        self.assertIn("FRAME_DURATION_BUDGET", codes)


class PreflightTests(unittest.TestCase):
    def test_debug_package_fails(self):
        with tempfile.TemporaryDirectory() as d:
            apk = os.path.join(d, "app.apk")
            epub = os.path.join(d, "book.epub")
            with open(apk, "wb") as fh:
                fh.write(b"apk")
            with open(epub, "wb") as fh:
                fh.write(b"epub")
            import hashlib
            sha = hashlib.sha256(b"apk").hexdigest()
            app = {"installed_sha256": sha, "debuggable": True,
                   "apk_path": "/data/app/base.apk", "versionName": "x"}
            v = pe.Verdict()
            out = pe.build_preflight(apk, epub, app, "QA Moby", v, book_id=201)
            self.assertFalse(v.pass_)
            self.assertIn("DEBUGGABLE_PACKAGE", [f["code"] for f in v.failures])
            self.assertEqual(out["book_id"], 201)

    def test_apk_mismatch_fails(self):
        with tempfile.TemporaryDirectory() as d:
            apk = os.path.join(d, "app.apk")
            with open(apk, "wb") as fh:
                fh.write(b"local")
            app = {"installed_sha256": "0" * 64, "debuggable": False}
            v = pe.Verdict()
            pe.build_preflight(apk, "", app, "QA Moby", v)
            self.assertFalse(v.pass_)
            self.assertIn("APK_MISMATCH", [f["code"] for f in v.failures])

    def test_missing_provenance_is_fail(self):
        v = pe.Verdict()
        pe.build_preflight("", "", {"debuggable": False}, "", v)
        self.assertEqual(v.label, "FAIL")
        codes = [f["code"] for f in v.failures]
        self.assertIn("RELEASE_EVIDENCE_MISSING", codes)
        self.assertIn("FIXTURE_EVIDENCE_MISSING", codes)
        self.assertIn("INSTALLED_SHA_MISSING", codes)

class DeviceFlowTests(unittest.TestCase):
    def test_zero_bounds_node_is_not_tappable(self):
        nodes = [
            {"text": "", "desc": "《QA Moby Dick (p1/books)》，Herman Melville，EPUB，未读",
             "rect": [0, 0, 0, 0]},
            {"text": "QA Moby Dick (p1/books)", "desc": "", "rect": [56, 422, 699, 1509]},
        ]
        self.assertFalse(pe._visible_rect(nodes[0]))
        self.assertIsNone(pe.find_node([nodes[0]], "QA Moby Dick"))
        self.assertEqual(pe.find_node(nodes, "QA Moby Dick")["rect"], [56, 422, 699, 1509])

    def test_progress_label_reads_even_with_zero_bounds(self):
        nodes = [{"text": "全书 4% · 2 / 13", "desc": "", "rect": [0, 0, 0, 0]}]
        self.assertEqual(pe.progress_page(nodes), (2, 13))
        self.assertTrue(pe.reader_visible(nodes))

    def test_bottom_nav_is_coordinate_tap(self):
        class FakeDevice:
            def __init__(self):
                self.calls = []
            def screen_center(self):
                return (720, 1480)
            def sh(self, *args):
                self.calls.append(tuple(args))
        d = FakeDevice()
        pe.reader_nav_tap(d, "next")
        pe.reader_nav_tap(d, "prev")
        self.assertEqual(d.calls[0], ("input", "tap", "993", "2782"))
        self.assertEqual(d.calls[1], ("input", "tap", "236", "2782"))

    def test_no_events_does_not_fail_pid(self):
        v = pe.Verdict()
        pe.check_event_pid([], 1234, v)
        self.assertTrue(v.pass_, v.as_dict())

    def test_event_pid_mismatch_fails(self):
        v = pe.Verdict()
        pe.check_event_pid([first_event()], 999, v)
        self.assertFalse(v.pass_)
        self.assertIn("PID_MISMATCH", [f["code"] for f in v.failures])


def frame_for(ev, duration_ms=12.0, intended_ns=None, flags=0, window=None):
    intended = ev.start_ns if intended_ns is None else intended_ns
    return pe.FrameStat(flags, intended, ev.draw_end_ns - 1, ev.draw_end_ns + 1,
                        intended + round(duration_ms * 1_000_000),
                        window or pe.PKG + "/MobileActivity", ev.pid)


def frame_text(frames):
    header = "Flags,IntendedVsync,DrawStart,SyncQueued,FrameCompleted,"
    groups = {}
    for frame in frames:
        groups.setdefault((frame.pid, frame.window), []).append(frame)
    return "\n".join("Graphics info for pid %d [%s]\nWindow: %s\n---PROFILEDATA---\n%s\n%s\n---PROFILEDATA---" % (
        pid, pe.PKG, window, header, "\n".join("%d,%d,%d,%d,%d," % (
            row.flags, row.intended_vsync_ns, row.draw_start_ns,
            row.sync_queued_ns, row.frame_completed_ns) for row in rows))
        for (pid, window), rows in groups.items())


def gfx_summary(count, janky=0):
    return "Total frames rendered: %d\nJanky frames (legacy): %d (%.2f%%)\n95th percentile: 16ms\n" % (
        count, janky, 100.0 * janky / count if count else 0.0)


class LabelFoldTests(unittest.TestCase):
    """Device typography differs from EPUB metadata; identity folds punctuation."""

    def node(self, text="", desc="", rect=(40, 100, 600, 160)):
        return {"text": text, "desc": desc, "rect": list(rect)}

    def test_curly_apostrophe_matches_ascii_needle(self):
        nodes = [self.node(text="LIV: The Town-Ho’s Story")]
        self.assertIs(pe.find_node(nodes, "LIV: The Town-Ho's Story"), nodes[0])

    def test_ascii_apostrophe_matches_curly_needle(self):
        nodes = [self.node(text="LIV: The Town-Ho's Story")]
        self.assertIs(pe.find_node(nodes, "LIV: The Town-Ho’s Story"), nodes[0])

    def test_em_dash_folds_to_ascii_hyphen(self):
        # Device TOC renders "LXXIV: The Sperm Whale’s Head—Contrasted View".
        nodes = [self.node(text="LXXIV: The Sperm Whale’s Head—Contrasted View")]
        self.assertIs(pe.find_node(nodes, "LXXIV: The Sperm Whale's Head-Contrasted View"),
                      nodes[0])

    def test_non_breaking_space_folds(self):
        nodes = [self.node(desc="继续阅读《QA Moby Dick (p1/books)》")]
        self.assertIsNotNone(pe.find_node(nodes, "QA Moby Dick (p1/books)"))

    def test_exact_match_uses_same_fold(self):
        other = [self.node(text="LIV: The Town-Ho’s Story")]
        self.assertIs(pe.find_node_exact(other, "liv: the town-ho's story"), other[0])
        self.assertIsNone(pe.find_node_exact(other, "LIV: The Town-Ho's Story II"))

    def test_unrelated_chapter_never_matches(self):
        nodes = [self.node(text="LIII: The Gam")]
        self.assertIsNone(pe.find_node(nodes, "LIV: The Town-Ho's Story"))


class PrecisionAndCompletenessTests(unittest.TestCase):
    def evaluate(self, frames, events, count=None, hz=60.0):
        v = pe.Verdict()
        result = pe.evaluate_gfxinfo(gfx_summary(len(frames) if count is None else count),
                                     frames, events, v, refresh_rate_hz=hz, expected_pid=1234)
        return v, result

    def test_nearest_rank_and_float_samples_remain_unrounded(self):
        values = [float(index) + 0.000001 for index in range(1, 21)]
        result = pe.summarize_ms(values)
        self.assertEqual(result["p50_ms"], 10.000001)
        self.assertEqual(result["p95_ms"], 19.000001)
        self.assertEqual(result["samples_ms"], values)

    def test_first_budget_uses_full_ns_delta_even_with_logged_tolerance(self):
        ev = pe.parse_perf_line(event_line(pe.FIRST_PHASE, 500.000001).replace("ms=500.000001", "ms=500.0"))
        self.assertGreater(ev.ms, 500.0)
        v = pe.validate_first_chapter([ev], 6, 0, min_samples=1)
        self.assertIn("FIRST_FRAME_BUDGET", [f["code"] for f in v.failures])

    def test_frame_above_16_by_one_ns_fails_without_histogram_rounding(self):
        ev = page_event(1, 1)
        v, result = self.evaluate([frame_for(ev, 16.000001)], [ev])
        self.assertEqual(result["frame_duration"]["p95_ms"], 16.000001)
        self.assertIn("FRAME_DURATION_BUDGET", [f["code"] for f in v.failures])

    def test_exact_16ms_passes(self):
        ev = page_event(1, 1)
        v, result = self.evaluate([frame_for(ev, 16.0)], [ev])
        self.assertTrue(v.pass_, v.as_dict())
        self.assertEqual(result["janky_percent"], 0.0)

    def test_non_page_slow_frame_inside_window_also_fails(self):
        ev = page_event(1, 1, ms=30)
        committed = frame_for(ev, 12.0, intended_ns=ev.start_ns + 10_000_000)
        earlier = pe.FrameStat(0, ev.start_ns, ev.start_ns + 1_000_000,
                               ev.start_ns + 2_000_000, ev.start_ns + 25_000_000,
                               pe.PKG + "/MobileActivity", ev.pid)
        v, result = self.evaluate([earlier, committed], [ev])
        self.assertEqual(result["total_frames"], 2)
        self.assertEqual(result["frame_duration"]["p95_ms"], 25.0)
        self.assertIn("JANKY_BUDGET", [f["code"] for f in v.failures])

    def test_idle_slow_frame_outside_window_is_retained_but_excluded(self):
        ev = page_event(1, 1)
        idle = pe.FrameStat(0, ev.end_ns + 100_000_000, ev.end_ns + 101_000_000,
                            ev.end_ns + 102_000_000, ev.end_ns + 200_000_000,
                            pe.PKG + "/MobileActivity", ev.pid)
        v, result = self.evaluate([frame_for(ev), idle], [ev])
        self.assertTrue(v.pass_, v.as_dict())
        self.assertEqual(result["outside_window_frames"], 1)
        self.assertEqual(result["framestats_count"], 2)
        self.assertEqual(result["frame_duration"]["samples_ms"], [12.0])

    def test_slow_frame_overlapping_action_start_is_included(self):
        ev = page_event(1, 1)
        earlier = pe.FrameStat(0, ev.start_ns - 30_000_000, ev.start_ns - 29_000_000,
                               ev.start_ns - 28_000_000, ev.start_ns + 1_000_000,
                               pe.PKG + "/MobileActivity", ev.pid)
        v, result = self.evaluate([earlier, frame_for(ev)], [ev])
        self.assertFalse(v.pass_)
        self.assertEqual(result["total_frames"], 2)

    def test_missing_rows_compared_with_reset_summary_fails(self):
        ev = page_event(1, 1)
        v, _ = self.evaluate([frame_for(ev)], [ev], count=2)
        self.assertIn("FRAMESTATS_COUNT", [f["code"] for f in v.failures])

    def test_missing_refresh_rate_fails(self):
        ev = page_event(1, 1)
        v, _ = self.evaluate([frame_for(ev)], [ev], hz=None)
        self.assertIn("REFRESH_RATE_MISSING", [f["code"] for f in v.failures])

    def test_frame_pid_and_window_identity_required(self):
        ev = page_event(1, 1)
        frame = frame_for(ev)
        frame.pid, frame.window = None, ""
        v, _ = self.evaluate([frame], [ev])
        self.assertIn("FRAME_PID_MISMATCH", [f["code"] for f in v.failures])
        self.assertIn("FRAME_WINDOW_MISSING", [f["code"] for f in v.failures])

    def test_duplicate_frame_fails(self):
        ev = page_event(1, 1)
        frame = frame_for(ev)
        v, _ = self.evaluate([frame, frame], [ev])
        self.assertIn("DUPLICATE_FRAME", [f["code"] for f in v.failures])

    def test_overlapping_turn_timestamps_fail(self):
        first = page_event(1, 1, chapter=59)
        second = page_event(2, 2, chapter=59, start_ns=first.end_ns - 1)
        v = pe.validate_page_events([first, second], 59, expected_turns=2, start_page=1)
        self.assertIn("EVENT_WINDOW_OVERLAP", [f["code"] for f in v.failures])

    def test_every_event_requires_pid(self):
        ev = first_event()
        bare = pe.parse_perf_line(ev.raw.split(" : ", 1)[1])
        v = pe.Verdict()
        pe.check_event_pid([bare], 1234, v)
        self.assertIn("PID_MISSING", [f["code"] for f in v.failures])

    def test_strict_columns_rows_timestamps_and_pid(self):
        text = frame_text([frame_for(page_event(1, 1))])
        self.assertEqual(len(pe.parse_framestats(text)), 1)
        for broken in (text.replace("SyncQueued,", "Missing,"),
                       text.replace("Flags,", "Flags,Flags,"),
                       text.replace("1234 [", "1234 [other."),
                       text.replace("0,2000000000,", "oops,2000000000,"),
                       text.replace("0,2000000000,", "0,0,"),
                       text.replace("2012000000,", "0,"),
                       text.replace("2012000000,", "9223372036854775807,"),
                       text.replace("0,2000000000,", "0,")):
            with self.subTest(broken=broken):
                with self.assertRaises(pe.PerfParseError):
                    pe.parse_framestats(broken)

    def test_display_uses_active_rate_not_supported_rate(self):
        text = "supportedModes=[fps=120.0]\nmActiveSfDisplayMode=DisplayMode{id=0, refreshRate=60.0}"
        self.assertEqual(pe.parse_refresh_rate(text), 60.0)
        self.assertIsNone(pe.parse_refresh_rate("supportedModes=[fps=60.0]"))

    def test_fixed_acceptance_rejects_page2_and_short_rounds(self):
        v = pe.Verdict()
        pe.validate_run_config("epub-turn", {"chapter": 59, "page": 2, "turns": 19, "rounds": 2}, v)
        self.assertIn("START_PAGE_NOT_ONE", [f["code"] for f in v.failures])
        self.assertIn("TURN_RUN_COUNT", [f["code"] for f in v.failures])


class CaptureTests(unittest.TestCase):
    def test_final_poll_retains_late_last_commit_and_raw_logs(self):
        late = event_line(pe.PAGE_PHASE, 12, turn=20, page=20, chapter=59)
        class Device:
            def __init__(self):
                self.reads = 0
            def perf_lines(self):
                self.reads += 1
                return [late] if self.reads >= 3 else []
            def foreground_state(self):
                return "mResumedActivity: " + pe.PKG + "/.android.MobileActivity"
            def app_pid(self):
                return 1234
        device = Device()
        logs = pe.EventCapture(device)
        logs.poll()
        with patch.object(pe, "_sleep"):
            result = pe.finish_capture(device, {"pid": 1234}, logs, None)
        self.assertEqual(len(result["events"]), 1)
        self.assertEqual(result["events"][0].turn, 20)
        self.assertEqual(result["raw_logcat"], [late])
        self.assertFalse(result["collection_failures"])

    def test_early_bad_log_is_not_dropped_by_later_good_event(self):
        bad = "PBPERF|phase=reader_epub_page|ms=2|extra=book_id:201"
        good = event_line(pe.PAGE_PHASE, 12, turn=1, page=1)
        class Device:
            def __init__(self):
                self.values = iter([[], [bad], [bad, good]])
            def perf_lines(self):
                return next(self.values)
        logs = pe.EventCapture(Device())
        logs.poll()
        logs.poll()
        self.assertEqual(logs.lines, [bad, good])
        self.assertEqual(len(logs.events), 1)
        self.assertEqual(logs.failures[0]["code"], "EVENT_PARSE")

    def test_incremental_snapshots_survive_ring_wrap(self):
        first, second = frame_for(page_event(1, 1)), frame_for(page_event(2, 2))
        class Device:
            def __init__(self):
                self.values = iter([frame_text([first]), frame_text([second])])
            def framestats_text(self):
                return next(self.values)
        capture = pe.FrameCapture(Device())
        capture.poll()
        capture.poll()
        self.assertEqual(len(capture.frames), 2)
        self.assertEqual(len(capture.raw), 2)
        self.assertFalse(capture.failures)

    def test_changed_snapshot_is_fail_and_raw_is_kept(self):
        ev = page_event(1, 1)
        class Device:
            def __init__(self):
                self.values = iter([frame_text([frame_for(ev)]), frame_text([frame_for(ev, 13)])])
            def framestats_text(self):
                return next(self.values)
        capture = pe.FrameCapture(Device())
        capture.poll()
        capture.poll()
        self.assertEqual(capture.failures[0]["code"], "FRAMESTATS_PARSE")
        self.assertEqual(len(capture.raw), 2)

    def test_background_is_fail(self):
        class Device:
            def foreground_state(self):
                return "mResumedActivity: other.app/.MainActivity"
        result = {}
        self.assertFalse(pe.check_foreground(Device(), result))
        self.assertEqual(result["collection_failures"][0]["code"], "BACKGROUND_WINDOW")

    def test_ui_back_waits_for_exit_before_measured_force_stop(self):
        class Device:
            def __init__(self):
                self.calls = []
            def sh(self, *args):
                self.calls.append(args)
            def ui_dump(self):
                return []
        device = Device()
        v = pe.Verdict()
        with patch.object(pe, "open_reader", return_value=(True, "PAGE_OK", [])), \
                patch.object(pe, "_sleep"):
            result = pe.prepare_progress(device, "QA Moby", "I: Loomings", 1, v)
        self.assertTrue(result["reader_exited"])
        self.assertEqual(device.calls, [("input", "keyevent", "4")])

    def test_rejected_nonfirst_page_never_collects(self):
        result = pe.run_page_turn_round(object(), "QA Moby", "LIV", 59, 2, 20)
        self.assertFalse(result["ok"])
        self.assertIn("START_PAGE_OR_MODE", result["detail"])


class EvidenceChainTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = self.temp.name
        self.apk = os.path.join(self.root, "release.apk")
        with open(self.apk, "wb") as handle:
            handle.write(b"offline release fixture")
        self.apk_sha = pe.sha256_file(self.apk)
        self.debug_sha = "b" * 64
        self.title = "QA Moby Dick (p1/books)"
        self.app = {"installed_sha256": self.apk_sha, "debuggable": False}
        self.record = {"id": pe.QA_BOOK_ID, "title": self.title, "filePath": pe.QA_BOOK_PATH}
        self.source = {"source_tree_algorithm": pe.SOURCE_TREE_ALGORITHM,
                       "source_tree_sha256": "a" * 64, "git_head": "c" * 40}
        self.release = dict(self.source, build_type="release", apk_sha256=self.apk_sha,
                            signer_sha256="d" * 64, apksigner_verified=True)
        self.fixture = {"device_serial": pe.PRIMARY_SERIAL, "package": pe.PKG,
                        "checked_via": "adb-exec-out-run-as", "checked_apk_sha256": self.debug_sha,
                        "installed_apk": {"sha256": self.debug_sha},
                        "qa201_book_record_live": self.record,
                        "qa201_device_file": {"path": pe.QA_BOOK_PATH,
                                              "sha256": pe.MOBY_SHA256, "size_bytes": 1165503},
                        "raw_evidence": {},
                        "retained_install": {"method": "adb-install-r", "data_cleared": False,
                                             "exit_code": 0, "stdout": "Success", "log_path": "install.txt",
                                             "from_apk_sha256": self.debug_sha, "to_apk_sha256": self.apk_sha}}
        raw = {"binding_commands": json.dumps([{"returncode": 0}]),
               "device_sha256": pe.MOBY_SHA256 + "  files/_pb_files/qa-moby.epub\n",
               "device_size": "1165503\n", "books": json.dumps({"books": [self.record]}),
               "installed_apk": self.debug_sha + "  /data/app/base.apk\n"}
        for name, content in raw.items():
            self.fixture["raw_evidence"][name] = name + ".txt"
            self.write_text(name + ".txt", content)
        self.write_text("install.txt", "Performing Streamed Install\nSuccess\n")
        self.fixture_path = os.path.join(self.root, "fixture.json")
        self.release_path = os.path.join(self.root, "release.json")

    def write_text(self, name, content):
        with open(os.path.join(self.root, name), "w", encoding="utf-8") as handle:
            handle.write(content)

    def validate(self, host_sha=pe.MOBY_SHA256):
        self.write_text("fixture.json", json.dumps(self.fixture))
        self.write_text("release.json", json.dumps(self.release))
        real_sha = pe.sha256_file
        with patch.object(pe, "sha256_file", side_effect=lambda path: host_sha if path == "host.epub" else real_sha(path)), \
                patch.object(pe.os.path, "getsize", return_value=1165503):
            v = pe.Verdict()
            result = pe.build_preflight(self.apk, "host.epub", self.app, self.title, v, book_id=201,
                                        release_evidence=self.release_path, fixture_evidence=self.fixture_path,
                                        serial=pe.PRIMARY_SERIAL, source=self.source)
        return v, result

    def test_complete_raw_source_release_fixture_chain_passes(self):
        v, result = self.validate()
        self.assertTrue(v.pass_, v.as_dict())
        self.assertIn("books", result["fixture_evidence"]["raw"])

    def test_wrong_fixed_host_epub_fails(self):
        v, _ = self.validate(host_sha="0" * 64)
        self.assertIn("EPUB_SHA_MISMATCH", [f["code"] for f in v.failures])

    def test_release_source_drift_fails(self):
        self.release["source_tree_sha256"] = "f" * 64
        v, _ = self.validate()
        self.assertIn("RELEASE_SOURCE_MISMATCH", [f["code"] for f in v.failures])

    def test_unverified_or_unsigned_release_fails(self):
        self.release.update(apksigner_verified=False, signer_sha256="")
        v, _ = self.validate()
        self.assertIn("RELEASE_SIGNATURE_UNVERIFIED", [f["code"] for f in v.failures])
        self.assertIn("RELEASE_SIGNATURE_MISSING", [f["code"] for f in v.failures])

    def test_wrong_device_fixture_and_title_fail(self):
        self.fixture["qa201_device_file"]["sha256"] = "0" * 64
        self.fixture["device_serial"] = "other"
        self.record["title"] = "Other Moby"
        v, _ = self.validate()
        self.assertIn("FIXTURE_SHA_MISMATCH", [f["code"] for f in v.failures])
        self.assertIn("FIXTURE_DEVICE_MISMATCH", [f["code"] for f in v.failures])
        self.assertIn("FIXTURE_BOOK_BINDING", [f["code"] for f in v.failures])

    def test_raw_books_must_independently_bind_book201(self):
        self.write_text("books.txt", json.dumps({"books": []}))
        v, _ = self.validate()
        self.assertIn("FIXTURE_RAW_BINDING", [f["code"] for f in v.failures])

    def test_missing_raw_file_fails(self):
        self.fixture["raw_evidence"]["device_sha256"] = "missing.txt"
        v, _ = self.validate()
        self.assertIn("FIXTURE_RAW_EVIDENCE", [f["code"] for f in v.failures])

    def test_absent_or_failed_retained_install_fails(self):
        for retained in ({}, dict(self.fixture["retained_install"], exit_code=1),
                         dict(self.fixture["retained_install"], data_cleared=True),
                         dict(self.fixture["retained_install"], to_apk_sha256="0" * 64)):
            with self.subTest(retained=retained):
                self.fixture["retained_install"] = retained
                v, _ = self.validate()
                self.assertIn("FIXTURE_INSTALL_CHAIN", [f["code"] for f in v.failures])

    def test_raw_install_success_is_required(self):
        self.write_text("install.txt", "Failure [INSTALL_FAILED]\n")
        v, _ = self.validate()
        self.assertIn("FIXTURE_INSTALL_LOG", [f["code"] for f in v.failures])


class RunnerRegressionTests(unittest.TestCase):
    def context(self):
        return ({"debuggable": False}, {"serial": pe.PRIMARY_SERIAL, "refresh_rate_hz": 60.0}, {})

    def first_attempt(self, index, ms=100):
        ev = first_event(ms=ms, start_ns=(index + 1) * 1_000_000_000, pid=2000 + index)
        frame = frame_for(ev, intended_ns=ev.draw_end_ns - 6_000_000)
        return {"ok": True, "detail": "FIRST_ENTRY", "pid": ev.pid, "events": [ev],
                "frames": [frame], "gfx_text": gfx_summary(1), "raw_logcat": [ev.raw],
                "raw_framestats": [frame_text([frame])], "collection_failures": []}

    def test_five_cold_runs_include_slow_middle_sample(self):
        attempts = [self.first_attempt(index, ms) for index, ms in enumerate([100, 100, 900, 100, 100])]
        with patch.object(pe, "run_context", return_value=self.context()), \
                patch.object(pe, "prepare_progress", return_value={"ok": True}), \
                patch.object(pe, "capture_cold_screenshot"), \
                patch.object(pe, "run_first_entry", side_effect=attempts):
            result = pe.run_epub(object(), {"chapter": 6, "page": 1, "cold": 5})
        self.assertFalse(result["pass"])
        self.assertEqual(len(result["runs"]), 5)
        self.assertEqual(result["samples"]["samples_ms"], [100.0, 100.0, 900.0, 100.0, 100.0])
        self.assertEqual(result["p95_ms"], 900.0)
        self.assertEqual([run["pass"] for run in result["runs"]], [True, True, False, True, True])
        self.assertEqual(result["runs"][2]["raw_logcat"], [attempts[2]["events"][0].raw])
        self.assertIn("FIRST_FRAME_BUDGET", [f["code"] for f in result["failures"]])
        json.dumps(result)

    def test_cold_exception_stays_in_original_five_attempts(self):
        attempts = [self.first_attempt(index) for index in range(5)]
        attempts[1] = ValueError("lost UI geometry")
        with patch.object(pe, "run_context", return_value=self.context()), \
                patch.object(pe, "prepare_progress", return_value={"ok": True}), \
                patch.object(pe, "capture_cold_screenshot"), \
                patch.object(pe, "run_first_entry", side_effect=attempts):
            result = pe.run_epub(object(), {"chapter": 6})
        self.assertEqual([run["index"] for run in result["runs"]], [1, 2, 3, 4, 5])
        self.assertIn("lost UI geometry", result["runs"][1]["detail"])
        self.assertFalse(result["pass"])
        self.assertEqual(len(result["events"]), 4)

    def test_all_three_rounds_retained_after_bad_middle_round(self):
        attempts = []
        for index in range(3):
            events = [page_event(turn, turn, chapter=59, start_ns=(index * 30 + turn) * 1_000_000_000)
                      for turn in range(1, 21)]
            frames = [frame_for(event, 25.0 if index == 1 else 12.0) for event in events]
            attempts.append({"ok": True, "pid": 1234, "events": events, "frames": frames,
                             "gfx_text": gfx_summary(len(frames)), "collection_failures": [],
                             "raw_framestats": [frame_text(frames)], "raw_logcat": [ev.raw for ev in events]})
        with patch.object(pe, "run_context", return_value=self.context()), \
                patch.object(pe, "run_page_turn_round", side_effect=attempts):
            result = pe.run_epub_turns(object(), {"chapter": 59})
        self.assertEqual(len(result["rounds"]), 3)
        self.assertEqual([item["pass"] for item in result["rounds"]], [True, False, True])
        self.assertEqual(len(result["events"]), 60)
        self.assertEqual(result["window_frame_count"], 60)
        self.assertEqual(result["frame_duration"]["p95_ms"], 25.0)
        self.assertAlmostEqual(result["janky_percent"], 100.0 / 3.0)
        self.assertFalse(result["pass"])
        json.dumps(result)

    def test_invalid_page_config_does_not_start_measurement(self):
        with patch.object(pe, "run_context", return_value=self.context()), \
                patch.object(pe, "run_page_turn_round") as run:
            result = pe.run_epub_turns(object(), {"chapter": 59, "page": 2})
        run.assert_not_called()
        self.assertFalse(result["pass"])
        self.assertEqual(result["rounds"], [])

    def test_turn_collection_uses_fresh_session_and_captures_all_twenty(self):
        class Device:
            def __init__(self):
                self.page = 1
                self.events = []
                self.frames = []
            def sh(self, *args):
                return ""
            def ui_dump(self):
                return [{"text": "全书 4%% · %d / 30" % self.page}]
            def foreground_state(self):
                return "mResumedActivity: " + pe.PKG + "/.android.MobileActivity"
            def perf_lines(self):
                return [event.raw for event in self.events]
            def framestats_text(self):
                return frame_text(self.frames)
            def gfxinfo_report(self):
                return gfx_summary(len(self.frames))
            def app_pid(self):
                return 1234
            def turn(self, direction):
                self.page += 1
                event = page_event(self.page - 1, self.page - 1, chapter=59)
                self.events.append(event)
                self.frames.append(frame_for(event))
        device = Device()
        entry = {"ok": True, "events": [first_event(ms=12, chapter=59)], "collection_failures": []}
        with patch.object(pe, "prepare_progress", return_value={"ok": True}) as prep, \
                patch.object(pe, "run_first_entry", return_value=entry) as first, \
                patch.object(pe, "show_chrome", side_effect=lambda _: device.ui_dump()), \
                patch.object(pe, "reader_nav_tap", side_effect=lambda _, direction: device.turn(direction)), \
                patch.object(pe, "_sleep"):
            result = pe.run_page_turn_round(device, "QA Moby", "LIV", 59, 1, 20)
        prep.assert_called_once()
        first.assert_called_once_with(device, "QA Moby", "LIV", 0, reset_gfx=False)
        self.assertTrue(result["ok"], result)
        self.assertEqual([event.turn for event in result["events"]], list(range(1, 21)))
        self.assertEqual(result["end_page"], 21)
        self.assertEqual(len(result["raw_framestats"]), 21)
        self.assertEqual(len(result["frames"]), 20)
        self.assertFalse(result["collection_failures"])

    def test_process_cold_keeps_old_stop_new_pid_proof(self):
        class Device:
            def __init__(self):
                self.pids = iter([1234, None, 2345, 2345])
                self.logs = 0
            def app_pid(self):
                return next(self.pids)
            def sh(self, *args):
                return ""
            def foreground_state(self):
                return "mResumedActivity: " + pe.PKG + "/.android.MobileActivity"
            def perf_lines(self):
                self.logs += 1
                return [] if self.logs == 1 else [event_line(pe.FIRST_PHASE, 100, pid=2345)]
            def ui_dump(self):
                return [{"text": "全书 4% · 1 / 30"}]
        device = Device()
        book = {"rect": [0, 0, 20, 20]}
        with patch.object(pe, "scroll_to_book", return_value=(book, [])), patch.object(pe, "_sleep"):
            result = pe.run_first_entry(device, "QA Moby", "I", 0, reset_gfx=False)
        self.assertEqual((result["pid_before"], result["pid_after_stop"], result["pid"]), (1234, None, 2345))
        self.assertFalse(result["collection_failures"])


class DeviceAdapterTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location("perf_device_test", os.path.join(os.path.dirname(__file__), "perf-device.py"))
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)

    def test_failed_adb_exit_is_audited_and_expected_no_pid_is_not_failure(self):
        device = self.module.PerfEpubDevice(pe.PRIMARY_SERIAL)
        failed = pe.subprocess.CompletedProcess([], 1, stdout="", stderr="device offline")
        no_pid = pe.subprocess.CompletedProcess([], 1, stdout="", stderr="")
        with patch.object(self.module.subprocess, "run", side_effect=[failed, no_pid, failed]):
            device.sh("wm", "size")
            device.sh("pidof", pe.PKG)
            device.sh("pidof", pe.PKG)
        self.assertEqual(len(device.command_log), 3)
        self.assertEqual(len(device.command_failures), 2)
        self.assertEqual(device.command_failures[0]["stderr"], "device offline")

    def test_cli_propagates_evidence_paths_and_failed_adb_forces_exit1(self):
        class Device:
            command_log = [{"exit_code": 1}]
            command_failures = command_log
        argv = ["perf-device.py", "epub", "--book-title", "QA Moby", "--chapter", "6",
                "--release-evidence", "release.json", "--fixture-evidence", "fixture.json"]
        report = {"pass": True, "verdict": "PASS", "failures": [], "diagnostics": []}
        with patch.object(sys, "argv", argv), patch.object(self.module, "PerfEpubDevice", return_value=Device()), \
                patch.object(self.module.perf_epub, "run_epub", return_value=report) as run, \
                patch.object(self.module, "dump_json"):
            status = self.module.main()
        self.assertEqual(status, 1)
        self.assertEqual(run.call_args.args[1]["release_evidence"], "release.json")
        self.assertEqual(run.call_args.args[1]["fixture_evidence"], "fixture.json")
        self.assertEqual(run.call_args.args[1]["book_id"], 201)
        self.assertEqual(report["failures"][0]["code"], "ADB_COMMAND_FAILED")

    def test_missing_cli_evidence_exits2_before_device_access(self):
        argv = ["perf-device.py", "epub-turn", "--book-title", "QA Moby", "--chapter", "59"]
        with patch.object(sys, "argv", argv), patch.object(sys, "stderr"), \
                patch.object(self.module, "PerfEpubDevice") as device:
            with self.assertRaises(SystemExit) as error:
                self.module.main()
        self.assertEqual(error.exception.code, 2)
        device.assert_not_called()

    def test_source_fingerprint_sorts_deduplicates_and_ignores_deleted_files(self):
        with tempfile.TemporaryDirectory() as root:
            os.makedirs(os.path.join(root, "android"))
            os.makedirs(os.path.join(root, "scripts", "android"))
            with open(os.path.join(root, "android", "a.kt"), "wb") as handle:
                handle.write(b"one")
            with open(os.path.join(root, "scripts", "android", "b.py"), "wb") as handle:
                handle.write(b"two")
            def git(command, **kwargs):
                if "ls-files" in command:
                    out = b"scripts/android/b.py\0android/a.kt\0android/a.kt\0android/deleted.kt\0"
                elif "rev-parse" in command:
                    out = b"abc123\n"
                else:
                    out = b" M android/a.kt\n"
                return pe.subprocess.CompletedProcess(command, 0, stdout=out)
            expected = hashlib.sha256()
            for name, content in [("android/a.kt", b"one"), ("scripts/android/b.py", b"two")]:
                expected.update(name.encode("utf-8") + b"\0" + hashlib.sha256(content).hexdigest().encode("ascii") + b"\n")
            with patch.object(pe.subprocess, "run", side_effect=git):
                result = pe.source_tree_info(root)
        self.assertEqual(result["source_tree_sha256"], expected.hexdigest())
        self.assertEqual(result["source_files"], 2)


def load_device_module():
    spec = importlib.util.spec_from_file_location(
        "perf_device_navigation_test", os.path.join(os.path.dirname(__file__), "perf-device.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def tiny_png():
    def chunk(kind, payload):
        crc = pe.zlib.crc32(kind + payload) & 0xffffffff
        return len(payload).to_bytes(4, "big") + kind + payload + crc.to_bytes(4, "big")
    header = (1).to_bytes(4, "big") * 2 + bytes([8, 6, 0, 0, 0])
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
            + chunk(b"IDAT", pe.zlib.compress(b"\0\0\0\0\xff")) + chunk(b"IEND", b""))


class BookTargetTests(unittest.TestCase):
    title = "QA Moby Dick (p1/books)"

    def cards(self, titles):
        nodes = [{"rect": [0, 300, 1440, 2621], "scrollable": True, "parent": None}]
        for slot, title in enumerate(titles):
            x = slot * 700
            root = len(nodes)
            nodes.extend([
                {"rect": [x, 400, x + 600, 1700], "parent": 0},
                {"text": title, "rect": [x, 1500, x + 600, 1600], "parent": root},
                {"desc": "《%s》，QA Fixture，EPUB，未读" % title,
                 "rect": [x, 400, x + 600, 1700], "parent": root},
                {"rect": [x, 400, x + 600, 1400], "clickable": True, "parent": root},
            ])
        return nodes

    def test_nonclickable_title_before_cover_selects_own_cover(self):
        nodes = self.cards([self.title])
        self.assertIs(pe.find_node(nodes, self.title), nodes[2])
        self.assertIs(pe.find_book_node(nodes, self.title), nodes[4])

    def test_typographic_shelf_title_still_qualifies(self):
        nodes = self.cards(["LIV: The Town-Ho’s Story"])
        self.assertIs(pe.find_book_node(nodes, "LIV: The Town-Ho's Story"), nodes[4])

    def test_similar_title_and_same_format_do_not_qualify(self):
        for title in (self.title + " extended", "prefix " + self.title, "QA Moby Dick"):
            nodes = self.cards([title, self.title])
            self.assertIs(pe.find_book_node(nodes, self.title), nodes[8])

    def test_only_similar_title_is_missing(self):
        self.assertIsNone(pe.find_book_node(self.cards([self.title + " extended"]), self.title))

    def test_two_same_title_cards_are_explicit_ambiguity(self):
        with self.assertRaisesRegex(ValueError, "SHELF_BOOK_TARGET_AMBIGUOUS"):
            pe.find_book_node(self.cards([self.title, self.title]), self.title)

    def test_mixed_plain_card_also_participates_in_same_title_ambiguity(self):
        nodes = self.cards([self.title])
        root = len(nodes)
        nodes.extend([{"parent": 0, "rect": [700, 400, 1300, 1700], "clickable": True},
                      {"parent": root, "text": self.title, "rect": [710, 1500, 1290, 1600]}])
        with self.assertRaisesRegex(ValueError, "AMBIGUOUS"):
            pe.find_book_node(nodes, self.title)

    def test_missing_cover_never_uses_a_neighbor(self):
        nodes = self.cards([self.title, "Other EPUB"])
        nodes[4]["clickable"] = False
        with self.assertRaisesRegex(ValueError, "SHELF_BOOK_TARGET_NOT_CLICKABLE"):
            pe.find_book_node(nodes, self.title)

    def test_disabled_cover_and_disabled_card_are_not_targets(self):
        for index in (1, 4):
            nodes = self.cards([self.title])
            nodes[index]["enabled"] = False
            with self.subTest(index=index), self.assertRaisesRegex(ValueError, "NOT_CLICKABLE"):
                pe.find_book_node(nodes, self.title)

    def test_offscreen_identity_does_not_bind_a_cover(self):
        nodes = self.cards([self.title])
        nodes[2]["rect"] = nodes[3]["rect"] = [0, 0, 0, 0]
        self.assertIsNone(pe.find_book_node(nodes, self.title))

    def test_multiple_card_click_targets_fail_instead_of_choosing_first(self):
        nodes = self.cards([self.title])
        nodes.append({"parent": 1, "rect": [10, 500, 30, 600], "clickable": True})
        with self.assertRaisesRegex(ValueError, "AMBIGUOUS"):
            pe.find_book_node(nodes, self.title)

    def test_menu_is_not_a_book_open_target(self):
        nodes = self.cards([self.title])
        nodes[4]["desc"] = "更多"
        with self.assertRaisesRegex(ValueError, "NOT_CLICKABLE"):
            pe.find_book_node(nodes, self.title)

    def test_exact_title_resolves_clickable_ancestor(self):
        nodes = [{"clickable": True, "rect": [0, 0, 600, 1700]},
                 {"parent": 0, "text": self.title, "rect": [10, 1500, 500, 1600]}]
        self.assertIs(pe.find_book_node(nodes, self.title), nodes[0])

    def test_desc_only_continue_card_resolves_own_ancestor(self):
        nodes = [{"clickable": True, "rect": [56, 743, 1384, 1359]},
                 {"parent": 0, "desc": "继续阅读《%s》，已读 7%%，打开" % self.title,
                  "rect": [56, 743, 1384, 1359]}]
        self.assertIs(pe.find_book_node(nodes, self.title), nodes[0])

    def test_ambiguous_opener_fails_without_tap_or_scroll(self):
        nodes = self.cards([self.title, self.title])
        class Device:
            def __init__(self):
                self.calls = []
            def sh(self, *args):
                self.calls.append(args)
            def ui_dump(self):
                return nodes
        device = Device()
        with patch.object(pe, "_sleep"):
            result = pe.guard_collection(pe.open_reader, device, self.title, "I: Loomings", 1)
        self.assertFalse(result["ok"])
        self.assertIn("AMBIGUOUS", result["detail"])
        self.assertFalse(any(call[:2] in (("input", "tap"), ("input", "swipe"))
                             for call in device.calls))

    def test_preparation_path_uses_book_selector_and_preserves_toc_finder(self):
        class Device:
            def __init__(self):
                self.calls = []
            def sh(self, *args):
                self.calls.append(args)
            def ui_dump(self):
                return [{"text": "目录", "rect": [0, 0, 20, 20]}]
        device = Device()
        book = {"rect": [56, 400, 699, 1400], "clickable": True}
        chapter = {"text": "I: Loomings", "rect": [100, 500, 400, 600]}
        with patch.object(pe, "scroll_to_book", return_value=(book, [])) as selector, \
                patch.object(pe, "scroll_to_node", return_value=(chapter, [])) as toc, \
                patch.object(pe, "reader_visible", return_value=True), \
                patch.object(pe, "set_page", return_value=(True, "PAGE_OK")), patch.object(pe, "_sleep"):
            result = pe.open_reader(device, self.title, "I: Loomings", 1)
        self.assertTrue(result[0])
        selector.assert_called_once_with(device, self.title)
        toc.assert_called_once_with(device, "I: Loomings", max_swipes=40, reset_top=True)
        self.assertIn(("input", "tap", "377", "900"), device.calls)


class TocNavigationTests(unittest.TestCase):
    class Device:
        def __init__(self, view=0):
            self.view = view
            self.calls = []
            self.raw_dumps = []
            self.pages = [["Titlepage", "I: Loomings"], ["XX: All Astir"], ["LIV: The Town-Ho’s Story"]]
        def ui_dump(self):
            nodes = [{"scrollable": True, "rect": [0, 333, 1440, 2621]}]
            nodes.extend({"text": title, "parent": 0, "rect": [100, 400 + i * 150, 900, 470 + i * 150]}
                         for i, title in enumerate(self.pages[self.view]))
            self.raw_dumps.append(nodes)
            return nodes
        def sh(self, *args):
            self.calls.append(args)
            if args[:2] == ("input", "swipe"):
                x1, y1, x2, y2 = map(int, args[2:6])
                if not (0 < x1 < 1440 and 0 < x2 < 1440 and 333 < y1 < 2621 and 333 < y2 < 2621):
                    raise AssertionError("sheet was dismissed by an out-of-list swipe")
                self.view = min(2, self.view + 1) if y1 > y2 else max(0, self.view - 1)

    def test_current_target_returns_before_any_reset_swipe(self):
        device = self.Device(1)
        with patch.object(pe, "_sleep"):
            found, _ = pe.scroll_to_node(device, "XX: All Astir", reset_top=True)
        self.assertEqual(found["text"], "XX: All Astir")
        self.assertEqual(device.calls, [])

    def test_already_top_does_not_pull_sheet_down(self):
        device = self.Device()
        with patch.object(pe, "_sleep"):
            nodes = pe.reset_list_to_top(device)
        self.assertIsNotNone(pe.find_node(nodes, "Titlepage"))
        self.assertEqual(device.calls, [])

    def test_offscreen_spine59_uses_only_list_interior(self):
        device = self.Device(1)
        with patch.object(pe, "_sleep"):
            found, _ = pe.scroll_to_node(device, "LIV: The Town-Ho", reset_top=True)
        self.assertEqual(found["text"], "LIV: The Town-Ho’s Story")
        self.assertEqual(len(device.calls), 3)

    def test_missing_target_stops_at_stable_boundary_and_keeps_last_dump(self):
        device = self.Device()
        with patch.object(pe, "_sleep"):
            found, nodes = pe.scroll_to_node(device, "MISSING CHAPTER", max_swipes=40, reset_top=True)
        self.assertIsNone(found)
        self.assertEqual(len(device.calls), 3)
        self.assertIs(nodes, device.raw_dumps[-1])
        self.assertEqual(len(device.raw_dumps), 4)

    def test_missing_scroll_container_fails_without_screen_coordinate_guess(self):
        class Device:
            def ui_dump(self):
                return [{"text": "目录", "rect": [0, 0, 40, 40]}]
            def sh(self, *args):
                raise AssertionError("unexpected swipe")
        with self.assertRaisesRegex(ValueError, "TOC_SCROLL_CONTAINER"):
            pe.scroll_to_node(Device(), "MISSING", reset_top=True)


class NavigationAdapterTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.module = load_device_module()

    def test_ui_dump_retains_parent_clickability_enabled_and_scrollable(self):
        xml = ('<hierarchy><node text="" bounds="[0,300][1440,2621]" scrollable="true">'
               '<node text="" bounds="[56,400][699,1700]" enabled="false">'
               '<node text="QA Moby Dick (p1/books)" bounds="[56,1500][699,1600]"/>'
               '<node content-desc="《QA Moby Dick (p1/books)》，QA Fixture，EPUB，未读" '
               'bounds="[56,400][699,1700]"/>'
               '<node bounds="[56,400][699,1400]" clickable="true"/>'
               '</node></node></hierarchy>UI hierchary dumped to: /dev/tty')
        nodes = self.module.ui_dump(None, adb_call=lambda *args, **kwargs: xml)
        self.assertEqual([node["parent"] for node in nodes], [None, 0, 1, 1, 1])
        self.assertTrue(nodes[0]["scrollable"])
        self.assertFalse(nodes[1]["enabled"])
        self.assertTrue(nodes[4]["clickable"])
        with self.assertRaisesRegex(ValueError, "NOT_CLICKABLE"):
            pe.find_book_node(nodes, "QA Moby Dick (p1/books)")

    def test_binary_screenshot_is_audited_and_existing_file_is_preserved(self):
        device = self.module.PerfEpubDevice(pe.PRIMARY_SERIAL)
        result = pe.subprocess.CompletedProcess([], 0, stdout=tiny_png(), stderr=b"")
        with tempfile.TemporaryDirectory() as root:
            path = os.path.join(root, "cold.png")
            with patch.object(self.module.subprocess, "run", return_value=result) as run:
                evidence = device.screenshot_png(path)
            self.assertNotIn("text", run.call_args.kwargs)
            self.assertEqual(run.call_args.args[0][-3:], ["exec-out", "screencap", "-p"])
            self.assertEqual(evidence["sha256"], hashlib.sha256(tiny_png()).hexdigest())
            self.assertEqual(device.command_log[0]["stdout_bytes"], len(tiny_png()))
            self.assertEqual(device.command_log[0]["path"], path)
            with patch.object(self.module.subprocess, "run", return_value=result):
                with self.assertRaises(FileExistsError):
                    device.screenshot_png(path)
            self.assertEqual(pe.png_evidence(path)["sha256"], evidence["sha256"])
            self.assertEqual(len(device.command_failures), 1)

    def test_nonzero_screencap_keeps_raw_output_and_marks_adb_failure(self):
        device = self.module.PerfEpubDevice(pe.PRIMARY_SERIAL)
        result = pe.subprocess.CompletedProcess([], 1, stdout=b"raw failed capture", stderr=b"device offline")
        with tempfile.TemporaryDirectory() as root:
            path = os.path.join(root, "failed.png")
            with patch.object(self.module.subprocess, "run", return_value=result):
                with self.assertRaisesRegex(ValueError, "screencap failed"):
                    device.screenshot_png(path)
            self.assertTrue(os.path.isfile(path))
            self.assertEqual(os.path.getsize(path), len(result.stdout))
            self.assertEqual(device.command_failures[0]["exit_code"], 1)
            self.assertEqual(device.command_failures[0]["stderr"], "device offline")

    def test_cli_resolves_output_before_runner_so_default_png_hook_has_a_path(self):
        class Device:
            command_log = []
            command_failures = []
        argv = ["perf-device.py", "epub", "--book-title", "QA Moby", "--chapter", "6",
                "--release-evidence", "release.json", "--fixture-evidence", "fixture.json"]
        report = {"pass": True, "verdict": "PASS", "failures": [], "diagnostics": []}
        with patch.object(sys, "argv", argv), patch.object(self.module, "PerfEpubDevice", return_value=Device()), \
                patch.object(self.module.perf_epub, "run_epub", return_value=report) as run, \
                patch.object(self.module, "dump_json") as save:
            self.assertEqual(self.module.main(), 0)
        args = run.call_args.args[1]
        self.assertTrue(args["json_out"].endswith(".json"))
        self.assertEqual(save.call_args.args[0], args["json_out"])
        self.assertEqual(args["screenshot_dir"], "")


class ColdScreenshotTests(unittest.TestCase):
    class Device:
        def __init__(self, bad=None):
            self.pid = 2000
            self.bad = bad or {}
            self.captures = []
            self.command_log = []
        def app_pid(self):
            return self.pid
        def foreground_state(self):
            return pe.PKG + "/.android.MobileActivity"
        def screenshot_png(self, path):
            self.captures.append(path)
            data = self.bad.get(len(self.captures), tiny_png())
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "xb") as handle:
                handle.write(data)
            self.command_log.append({"args": ["exec-out", "screencap", "-p"], "path": path})

    def capture(self, device, root, attempt=None):
        attempt = attempt or {"pid": device.pid, "events": [first_event(pid=device.pid)], "frames": []}
        pe.capture_cold_screenshot(device, attempt, {"json_out": os.path.join(root, "epub03.json")},
                                  {"installed_sha256": "a" * 64}, {"serial": pe.PRIMARY_SERIAL},
                                  {"host_epub_sha256": pe.MOBY_SHA256}, 1)
        return attempt

    def test_png_binding_contains_run_process_apk_fixture_and_raw_digest(self):
        with tempfile.TemporaryDirectory() as root:
            attempt = self.capture(self.Device(), root)
            evidence = attempt["body_screenshot"]
            self.assertTrue(evidence["ok"], evidence)
            self.assertEqual(evidence["sha256"], hashlib.sha256(tiny_png()).hexdigest())
            self.assertEqual((evidence["run_index"], evidence["pid"], evidence["chapter"], evidence["page"]),
                             (1, 2000, 6, 0))
            self.assertEqual(evidence["apk_sha256"], "a" * 64)
            self.assertEqual(evidence["fixture_sha256"], pe.MOBY_SHA256)
            self.assertIsNone(evidence["text_verified"])
            self.assertEqual(os.path.basename(evidence["path"]), "epub03-cold01.png")
            self.assertEqual(len(attempt["raw_commands"]), 1)

    def test_empty_wrong_magic_truncated_or_bad_crc_is_fail_with_raw_file(self):
        for data in (b"", b"not a PNG", tiny_png()[:-5], tiny_png()[:20] + b"x" + tiny_png()[21:]):
            with self.subTest(data=data[:10]), tempfile.TemporaryDirectory() as root:
                attempt = self.capture(self.Device({1: data}), root)
                self.assertFalse(attempt["body_screenshot"]["ok"])
                self.assertEqual(attempt["collection_failures"][0]["code"], "BODY_SCREENSHOT_FAILED")
                self.assertTrue(os.path.isfile(attempt["body_screenshot"]["path"]))
                self.assertEqual(len(attempt["events"]), 1)

    def test_changed_pid_or_background_png_fails(self):
        for kind in ("pid", "foreground"):
            device = self.Device()
            capture = device.screenshot_png
            def change(path):
                capture(path)
                if kind == "pid":
                    device.pid += 1
                else:
                    device.foreground_state = lambda: "other.app/.MainActivity"
            device.screenshot_png = change
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as root:
                attempt = self.capture(device, root)
                self.assertFalse(attempt["body_screenshot"]["ok"])
                self.assertTrue(os.path.isfile(attempt["body_screenshot"]["path"]))

    def test_missing_screenshot_adapter_is_explicit_fail(self):
        with tempfile.TemporaryDirectory() as root:
            attempt = self.capture(object(), root, {"pid": 2000, "events": [], "frames": []})
        self.assertIn("no screenshot_png adapter", attempt["body_screenshot"]["error"])

    def test_five_cold_pngs_follow_finished_capture_and_failure_does_not_drop_samples(self):
        with tempfile.TemporaryDirectory() as root:
            device = self.Device({3: b"bad PNG"})
            runner = RunnerRegressionTests()
            order = []
            count = 0
            capture = device.screenshot_png
            def shot(path):
                order.append("png%d" % (len(device.captures) + 1))
                capture(path)
            device.screenshot_png = shot
            def entry(*args, **kwargs):
                nonlocal count
                attempt = runner.first_attempt(count)
                count += 1
                device.pid = attempt["pid"]
                order.append("finish%d" % count)
                return attempt
            context = ({"installed_sha256": "a" * 64}, {"serial": pe.PRIMARY_SERIAL},
                       {"host_epub_sha256": pe.MOBY_SHA256})
            with patch.object(pe, "run_context", return_value=context), \
                    patch.object(pe, "prepare_progress", return_value={"ok": True}), \
                    patch.object(pe, "run_first_entry", side_effect=entry):
                result = pe.run_epub(device, {"chapter": 6, "json_out": os.path.join(root, "epub03.json")})
            self.assertEqual(order, [item for index in range(1, 6) for item in ("finish%d" % index, "png%d" % index)])
            self.assertEqual(len(result["runs"]), 5)
            self.assertEqual(result["samples"]["samples_ms"], [100.0] * 5)
            self.assertEqual([run["body_screenshot"]["ok"] for run in result["runs"]], [True, True, False, True, True])
            self.assertEqual([run["pass"] for run in result["runs"]], [True, True, False, True, True])
            self.assertIn("BODY_SCREENSHOT_FAILED", [failure["code"] for failure in result["failures"]])
            self.assertEqual(len(result["events"]), 5)
            self.assertEqual(len(device.captures), 5)


class Api31ReplayTests(unittest.TestCase):
    """Replay sealed captures offline; never rewrite their original FAIL reports."""

    @classmethod
    def setUpClass(cls):
        root = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                            "fixtures", "framestats-api31")
        with open(os.path.join(root, "manifest.json"), encoding="utf-8") as source:
            cls.manifest = json.load(source)
        with open(os.path.join(root, cls.manifest["bundle"]["file"]), "rb") as source:
            cls.compressed = source.read()
        cls.uncompressed = gzip.decompress(cls.compressed)
        cls.fixture = json.loads(cls.uncompressed)

    @staticmethod
    def canonical(value):
        return (json.dumps(value, ensure_ascii=False, sort_keys=True,
                           separators=(",", ":")) + "\n").encode("utf-8")

    @staticmethod
    def pointer(value, path):
        for part in path.strip("/").split("/"):
            part = part.replace("~1", "/").replace("~0", "~")
            value = value[int(part)] if isinstance(value, list) else value[part]
        return value

    @staticmethod
    def capture(snapshots):
        class Device:
            def __init__(self):
                self.snapshots = iter(snapshots)

            def framestats_text(self):
                return next(self.snapshots)
        capture = pe.FrameCapture(Device())
        for _ in snapshots:
            capture.poll()
        return capture

    @staticmethod
    def change_first_row(text, column, change):
        lines = text.splitlines()
        header_index = next(index for index, line in enumerate(lines)
                            if line.startswith("Flags,"))
        header = lines[header_index].rstrip(",").split(",")
        row_index = header_index + 1
        row = lines[row_index].split(",")
        index = header.index(column)
        row[index] = str(change(int(row[index])))
        lines[row_index] = ",".join(row)
        return "\n".join(lines) + ("\n" if text.endswith("\n") else "")

    def test_fixture_integrity_and_portable_source_pointers(self):
        bundle = self.manifest["bundle"]
        self.assertEqual(len(self.compressed), bundle["bytes"])
        self.assertEqual(hashlib.sha256(self.compressed).hexdigest(), bundle["sha256"])
        self.assertEqual(len(self.uncompressed), bundle["uncompressed_bytes"])
        self.assertEqual(hashlib.sha256(self.uncompressed).hexdigest(),
                         bundle["uncompressed_sha256"])
        self.assertEqual(self.uncompressed, self.canonical(self.fixture))
        self.assertEqual(self.compressed[4:8], bytes(4))
        self.assertEqual(self.compressed[3] & 8, 0)  # no original filename
        self.assertEqual(self.manifest["schema_version"], 1)
        self.assertEqual(self.fixture["schema_version"], 1)
        expected_sources = {
            "epub03.json": "fd664e089b67ed4a7a1d9c39a7949da1b0bf21092d643b820c3a2856cbc390c8",
            "epub04.json": "50cb20d5e5f5bebc00a0e7b10b00904a68930854cfc79579b24013fc93dacdf7",
        }
        self.assertEqual({key: value["sha256"] for key, value in
                          self.manifest["sources"].items()}, expected_sources)
        count = 0
        for capture in self.manifest["captures"]:
            for dump in capture["dumps"]:
                text = self.pointer(self.fixture, dump["payload_pointer"])
                data = text.encode("utf-8")
                self.assertEqual(len(data), dump["utf8_bytes"])
                self.assertEqual(hashlib.sha256(data).hexdigest(), dump["sha256"])
                self.assertTrue(dump["source_pointer"].startswith(
                    capture["source_pointer"] + "/raw_framestats/"))
                count += 1
        self.assertEqual(count, 68)
        for source in self.manifest["sources"].values():
            self.assertTrue(source["path"].startswith("docs/"))
            self.assertNotIn(":", source["path"])

    def test_all_five_cold_dumps_retain_249_rows_and_nine_vsync_pairs(self):
        total, pairs = 0, 0
        for index, cold in enumerate(self.fixture["cold"]):
            frames = pe.parse_framestats(cold["raw_framestats"][0])
            self.assertEqual(len(frames), [50, 49, 50, 50, 50][index])
            self.assertEqual(len(frames), pe.parse_gfxinfo_summary(cold["gfx_text"])["total_frames"])
            self.assertEqual(sum(frame.flags == 2 for frame in frames), [35, 34, 33, 33, 32][index])
            grouped = collections.defaultdict(list)
            for frame in frames:
                grouped[(frame.pid, frame.window, frame.intended_vsync_ns)].append(frame)
            for group in grouped.values():
                if len(group) > 1:
                    self.assertEqual([frame.flags for frame in group], [2, 0])
                    self.assertEqual(len({frame.sync_queued_ns for frame in group}), 2)
                    self.assertEqual(len({frame.key for frame in group}), 2)
                    pairs += 1
            total += len(frames)
        self.assertEqual(total, 249)
        self.assertEqual(pairs, 9)

    def test_cold_first_commit_still_matches_exactly_one_flags_zero_frame(self):
        for cold in self.fixture["cold"]:
            verdict = pe.Verdict()
            events = [pe.parse_perf_line(line) for line in cold["events"]]
            matched = pe.validate_frames(events, pe.parse_framestats(cold["raw_framestats"][0]), verdict)
            self.assertFalse(verdict.failures)
            self.assertEqual(len(matched), 1)
            self.assertEqual(matched[0]["frame_flags"], 0)
            self.assertEqual(cold["verdict"], "FAIL")
            self.assertEqual([item["code"] for item in cold["failures"]],
                             ["FRAMESTATS_PARSE", "NO_FRAMESTATS", "FRAMESTATS_COUNT"])

    def test_real_63_snapshots_reproduce_all_3175_original_frame_projections(self):
        manifests = [item for item in self.manifest["captures"]
                     if item["source"] == "epub04.json"]
        total = 0
        for index, record in enumerate(self.fixture["rounds"]):
            self.assertEqual(len(record["raw_framestats"]), 21)
            capture = self.capture(record["raw_framestats"])
            self.assertFalse(capture.failures)
            self.assertEqual(capture.raw, record["raw_framestats"])
            frames = list(capture.frames.values())
            self.assertEqual(len(frames), [1055, 1060, 1060][index])
            self.assertEqual(sum(frame.flags == 2 for frame in frames), [792, 795, 798][index])
            digest = hashlib.sha256(self.canonical([dataclasses.asdict(frame) for frame in frames])).hexdigest()
            self.assertEqual(digest, manifests[index]["original_frames_projection"]["canonical_json_sha256"])
            total += len(frames)
        self.assertEqual(total, 3175)

    def test_real_overlap_has_4132_stable_existing_projection_reappearances(self):
        reappearances, occurrences = 0, 0
        for record in self.fixture["rounds"]:
            seen = {}
            for text in record["raw_framestats"]:
                self.assertEqual(sum(line.startswith("Window:") for line in text.splitlines()), 1)
                for frame in pe.parse_framestats(text):
                    if frame.key in seen:
                        self.assertEqual(frame, seen[frame.key])
                        reappearances += 1
                    seen[frame.key] = frame
                    occurrences += 1
        self.assertEqual(reappearances, 4132)
        self.assertEqual(occurrences, 7307)

    def test_real_120_row_ring_wrap_and_final_catchup_keep_every_frame(self):
        for index, record in enumerate(self.fixture["rounds"]):
            seen, previous = set(), set()
            for snapshot, text in enumerate(record["raw_framestats"]):
                frames = pe.parse_framestats(text)
                current = {frame.key for frame in frames}
                expected_rows = ([48, 101] if index == 0 else [53, 106]) + [120] * 19
                self.assertEqual(len(current), expected_rows[snapshot])
                if 2 <= snapshot < 20:
                    self.assertEqual(len(current & previous), 67)
                    self.assertEqual(len(current - seen), 53)
                elif snapshot == 20:
                    self.assertEqual(len(current & previous), 120)
                    self.assertEqual(len(current - seen), 0)
                seen.update(current)
                self.assertEqual(len(seen), pe.parse_gfxinfo_summary(text)["total_frames"])
                previous = current

    def test_original_04_windows_statistics_and_all_six_failures_replay_unchanged(self):
        durations, failures, janky, total = [], [], 0, 0
        for index, record in enumerate(self.fixture["rounds"]):
            capture = self.capture(record["raw_framestats"])
            events = [pe.parse_perf_line(line) for line in record["events"]]
            verdict = pe.Verdict()
            gfx = pe.evaluate_gfxinfo(record["gfx_text"], list(capture.frames.values()), events, verdict,
                                     refresh_rate_hz=self.fixture["refresh_rate_hz"], expected_pid=record["pid"])
            self.assertEqual(gfx, record["gfx"])
            self.assertEqual(gfx["frame_duration"], record["frame_duration"])
            self.assertEqual(verdict.failures, record["failures"])
            self.assertEqual(verdict.label, record["verdict"])
            self.assertEqual(gfx["frame_duration"]["p95_ms"], [98.642175, 27.929318, 28.895938][index])
            self.assertEqual(gfx["janky_frames"], [24, 28, 25][index])
            self.assertEqual(gfx["total_frames"], [41, 41, 40][index])
            durations.extend(gfx["frame_duration"]["samples_ms"])
            failures.extend(verdict.failures)
            janky += gfx["janky_frames"]
            total += gfx["total_frames"]
        aggregate = self.fixture["aggregate"]
        self.assertEqual(pe.summarize_ms(durations), aggregate["frame_duration"])
        self.assertEqual(janky, aggregate["janky_frames"])
        self.assertEqual(total, aggregate["window_frame_count"])
        self.assertEqual(100.0 * janky / total, aggregate["janky_percent"])
        self.assertEqual(failures, aggregate["failures"])
        self.assertEqual(aggregate["verdict"], "FAIL")

    def test_same_snapshot_true_duplicate_and_repeated_window_block_still_fail(self):
        text = self.fixture["cold"][0]["raw_framestats"][0]
        row = text.splitlines()[77]
        for broken in (text.replace(row, row + "\n" + row, 1), text + "\n" + text):
            with self.subTest(kind=len(broken)), self.assertRaisesRegex(pe.PerfParseError, "duplicate framestats"):
                pe.parse_framestats(broken)

    def test_same_vsync_same_flags_with_distinct_queue_times_are_both_kept(self):
        text = self.fixture["cold"][0]["raw_framestats"][0]
        lines = text.splitlines()
        self.assertTrue(lines[77].startswith("2,"))
        lines[77] = "0," + lines[77].split(",", 1)[1]
        frames = pe.parse_framestats("\n".join(lines))
        pair = [frame for frame in frames if frame.intended_vsync_ns == 854728664912373]
        self.assertEqual(len(pair), 2)
        self.assertEqual([frame.flags for frame in pair], [0, 0])
        self.assertNotEqual(pair[0].key, pair[1].key)
        self.assertEqual(len(frames), 50)

    def test_different_windows_are_not_merged_and_ambiguous_event_still_fails(self):
        cold = self.fixture["cold"][0]
        text = cold["raw_framestats"][0]
        other = text.replace("Window: " + pe.PKG + "/", "Window: " + pe.PKG + "/Other/")
        frames = pe.parse_framestats(text + "\n" + other)
        self.assertEqual(len(frames), 100)
        self.assertEqual(len({frame.window for frame in frames}), 2)
        verdict = pe.Verdict()
        pe.validate_frames([pe.parse_perf_line(cold["events"][0])], frames, verdict)
        self.assertIn("AMBIGUOUS_FRAME", [item["code"] for item in verdict.failures])

    def test_process_pid_is_part_of_identity_and_existing_seven_fields_are_unchanged(self):
        frame = pe.parse_framestats(self.fixture["cold"][0]["raw_framestats"][0])[0]
        self.assertNotEqual(frame.key, dataclasses.replace(frame, pid=frame.pid + 1).key)
        self.assertEqual(frame.key, dataclasses.replace(frame, frame_completed_ns=frame.frame_completed_ns + 1).key)
        self.assertEqual(frame.key, dataclasses.replace(frame, draw_start_ns=frame.draw_start_ns + 1).key)
        self.assertEqual({field.name for field in dataclasses.fields(pe.FrameStat)},
                         {"flags", "intended_vsync_ns", "draw_start_ns", "sync_queued_ns",
                          "frame_completed_ns", "window", "pid"})

    def test_same_real_snapshot_read_twice_is_not_double_counted(self):
        text = self.fixture["cold"][0]["raw_framestats"][0]
        capture = self.capture([text, text])
        self.assertFalse(capture.failures)
        self.assertEqual(len(capture.frames), 50)
        self.assertEqual(capture.raw, [text, text])

    def test_changed_existing_projection_in_real_overlap_fails_and_raw_is_kept(self):
        first, second = self.fixture["rounds"][0]["raw_framestats"][:2]
        for column in ("FrameCompleted", "DrawStart", "Flags"):
            changed = self.change_first_row(second, column, lambda value: value + 1)
            with self.subTest(column=column):
                capture = self.capture([first, changed])
                self.assertEqual(len(capture.frames), 48)
                self.assertEqual(capture.raw, [first, changed])
                self.assertEqual(capture.failures[0]["code"], "FRAMESTATS_PARSE")
                self.assertIn("conflicting snapshots", capture.failures[0]["message"])

    def test_unstored_extra_column_remains_raw_without_expanding_projection(self):
        first, second = self.fixture["rounds"][0]["raw_framestats"][:2]
        changed = self.change_first_row(second, "GpuCompleted", lambda value: value + 1)
        capture = self.capture([first, changed])
        self.assertFalse(capture.failures)
        self.assertEqual(len(capture.frames), 101)
        self.assertEqual(capture.raw, [first, changed])

    def test_missing_real_snapshots_across_ring_wrap_still_fail_count(self):
        record = self.fixture["rounds"][0]
        snapshots = record["raw_framestats"][:8] + record["raw_framestats"][11:]
        capture = self.capture(snapshots)
        self.assertFalse(capture.failures)
        self.assertEqual(len(capture.frames), 963)
        verdict = pe.Verdict()
        pe.evaluate_gfxinfo(record["gfx_text"], list(capture.frames.values()),
                           [pe.parse_perf_line(line) for line in record["events"]], verdict,
                           refresh_rate_hz=self.fixture["refresh_rate_hz"], expected_pid=record["pid"])
        self.assertIn("FRAMESTATS_COUNT", [item["code"] for item in verdict.failures])


if __name__ == "__main__":
    unittest.main()

TITLE = "QA Moby Dick (p1/books)"
MOBY_BYTES = 1165503


class StagedCollectionTests(unittest.TestCase):
    """Offline controls for the one-unit-per-host-call staged collector."""

    def setUp(self):
        import shutil
        import subprocess as sp
        self.sp = sp
        self.root = tempfile.mkdtemp(prefix="pbseg-")
        self.addCleanup(shutil.rmtree, self.root, True)
        self.run_dir = os.path.join(self.root, "run")
        self.serial = pe.PRIMARY_SERIAL
        self.adb = os.path.join(self.root, "adb")
        self.apk = os.path.join(self.root, "app-release-signed.apk")
        self.write(self.apk, b"release-apk-bytes")
        self.apk_sha = pe.sha256_file(self.apk)
        self.epub = os.path.join(self.root, "qa-moby.epub")
        self.write(self.epub, b"\0" * MOBY_BYTES)
        self.epub_sha = pe.sha256_file(self.epub)
        self.source = {"git_head": "a" * 40,
                       "source_tree_algorithm": pe.SOURCE_TREE_ALGORITHM,
                       "source_tree_sha256": "b" * 64, "source_files": 397}
        self.release = self.write_release()
        self.fixture = self.write_fixture()
        self.pid = 5000
        self.fail_on = None
        for target, value in (("MOBY_SHA256", self.epub_sha),
                              ("source_tree_info", lambda: dict(self.source)),
                              ("prepare_progress", self.fake_prepare),
                              ("run_first_entry", self.fake_entry)):
            patcher = patch.object(pe, target, value)
            patcher.start()
            self.addCleanup(patcher.stop)

    # -- fixtures -----------------------------------------------------------
    def write(self, path, data):
        with open(path, "wb") as handle:
            handle.write(data)
        return path

    def write_json(self, path, value):
        return self.write(path, json.dumps(value, sort_keys=True).encode("utf-8"))

    def write_release(self):
        return self.write_json(os.path.join(self.root, "release.json"), {
            "build_type": "release", "debuggable": False, "apksigner_verified": True,
            "apk_sha256": self.apk_sha, "apk_bytes": os.path.getsize(self.apk),
            "signer_sha256": "c" * 64, "git_head": self.source["git_head"],
            "source_tree_algorithm": self.source["source_tree_algorithm"],
            "source_tree_sha256": self.source["source_tree_sha256"],
            "source_files": self.source["source_files"]})

    def write_fixture(self):
        record = {"id": pe.QA_BOOK_ID, "title": TITLE, "filePath": pe.QA_BOOK_PATH,
                  "fileSize": MOBY_BYTES}
        self.write_json(os.path.join(self.root, "books.json"), {"books": [record]})
        self.write(os.path.join(self.root, "device_sha256.txt"), self.epub_sha.encode())
        self.write(os.path.join(self.root, "device_size.txt"), str(MOBY_BYTES).encode())
        self.write(os.path.join(self.root, "installed_apk.txt"),
                   ("%s  /data/app/base.apk" % self.apk_sha).encode())
        self.write_json(os.path.join(self.root, "binding_commands.json"),
                        [{"argv": ["exec-out", "run-as", pe.PKG, "cat", pe.QA_BOOK_PATH],
                          "returncode": 0}])
        self.write(os.path.join(self.root, "install-r.log"), b"Performing Streamed Install\nSuccess\n")
        return self.write_json(os.path.join(self.root, "fixture.json"), {
            "device_serial": self.serial, "package": pe.PKG,
            "checked_via": "adb-exec-out-run-as", "checked_apk_sha256": self.apk_sha,
            "qa201_book_record_live": record,
            "qa201_device_file": {"path": pe.QA_BOOK_PATH, "sha256": self.epub_sha,
                                  "size_bytes": MOBY_BYTES},
            "installed_apk": {"sha256": self.apk_sha, "package": pe.PKG},
            "raw_evidence": {"binding_commands": "binding_commands.json",
                             "device_sha256": "device_sha256.txt",
                             "device_size": "device_size.txt",
                             "books": "books.json",
                             "installed_apk": "installed_apk.txt"},
            "retained_install": {"method": "adb-install-r", "data_cleared": False,
                                 "exit_code": 0, "stdout": "Success",
                                 "from_apk_sha256": self.apk_sha,
                                 "to_apk_sha256": self.apk_sha,
                                 "log_path": "install-r.log"}})

    # -- fake device --------------------------------------------------------
    def event(self):
        return first_event(ms=400, page=0,
                           start_ns=1_000_000_000 + self.pid * 1_000_000_000,
                           pid=self.pid)

    def frames(self):
        # One real app frame whose DrawStart..SyncQueued brackets draw_end_ns.
        ev = self.event()
        return [pe.FrameStat(0, ev.start_ns, ev.draw_end_ns - 1, ev.draw_end_ns + 1,
                             ev.end_ns + 1_000_000, pe.PKG + "/MobileActivity", self.pid)]

    def runner(self, argv, capture_output=True, timeout=None):
        tail = list(argv[3:])
        if tail and tail[0] == "shell":
            tail = tail[1:]
        if self.fail_on and tail[:len(self.fail_on)] == self.fail_on:
            raise self.sp.TimeoutExpired(argv, timeout or 0)
        out = self.answer(tail)
        if isinstance(out, bytes):
            return self.sp.CompletedProcess(argv, 0, out, b"")
        return self.sp.CompletedProcess(argv, 0, out.encode("utf-8"), b"")

    def answer(self, tail):
        key = tuple(tail)
        if key == ("get-serialno",):
            return self.serial + "\n"
        if key == ("exec-out", "screencap", "-p"):
            return tiny_png()
        if key and key[0] == "shell":
            key = key[1:]
        if key == ("pidof", pe.PKG):
            return "%d\n" % self.pid
        if key[0] == "getprop":
            return {"ro.product.model": "Pixel 3 XL",
                    "ro.build.version.sdk": "33",
                    "ro.build.version.release": "13",
                    "ro.product.cpu.abi": "arm64-v8a",
                    "ro.build.fingerprint": "google/crosshatch/crosshatch:13/TQ3A/1:user/release-keys"
                    }.get(key[1], "") + "\n"
        if key == ("wm", "size"):
            return "Physical size: 1440x2960\nOverride size: 1440x2960\n"
        if key == ("dumpsys", "display"):
            return "DisplayDeviceInfo{mActiveSfDisplayMode=1 fps=60.000004 width=1440}\n"
        if key == ("dumpsys", "package", pe.PKG):
            return ("Packages:\n  Package [app.particlebook] (abc):\n"
                    "    versionCode=1 minSdk=26 targetSdk=35\n"
                    "    versionName=2.2.1-p0\n"
                    "    flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]\n")
        if key == ("pm", "path", pe.PKG):
            return "package:/data/app/base.apk\n"
        if key == ("sha256sum", "/data/app/base.apk"):
            return "%s  /data/app/base.apk\n" % self.apk_sha
        if key == ("dumpsys", "activity", "activities"):
            return "  mResumedActivity: ActivityRecord{x app.particlebook/.android.MobileActivity}\n"
        if key == ("logcat", "-v", "threadtime", "-d", "-s", "PBPERF:I", "*:S"):
            return self.event().raw + "\n"
        if key == ("dumpsys", "gfxinfo", pe.PKG, "framestats"):
            return frame_text(self.frames())
        if key == ("dumpsys", "gfxinfo", pe.PKG):
            return gfx_summary(len(self.frames()))
        if key[0] == "input":
            return ""
        raise AssertionError("unexpected adb command: %r" % (key,))

    # -- patched device-side flows -------------------------------------------
    def fake_prepare(self, device, book, toc_match, target_page, v):
        device.sh("input", "keyevent", "4")
        return {"ok": True, "detail": "PREPARED"}

    def timeout_prepare(self, device, book, toc_match, target_page, v):
        device.sh("input", "tap", "720", "1480")
        return {"ok": True, "detail": "TAPPED"}

    def fake_entry(self, device, book, toc_match, expected_page, reset_gfx=True):
        self.pid += 1
        lines = device.perf_lines()
        events = [item for item in (pe.parse_perf_line(line) for line in lines) if item]
        return {"ok": True, "detail": "FIRST_ENTRY", "pid": self.pid, "process_cold": True,
                "events": events, "frames": pe.parse_framestats(device.framestats_text()),
                "gfx_text": device.gfxinfo_report(), "raw_logcat": lines,
                "collection_failures": []}

    # -- driver -------------------------------------------------------------
    def args(self, **kw):
        base = {"run_dir": self.run_dir, "apk": self.apk, "release_evidence": self.release,
                "fixture_evidence": self.fixture, "epub": self.epub, "book_title": TITLE,
                "book_id": pe.QA_BOOK_ID, "chapter": 6, "page": 1,
                "cold": pe.MIN_COLD_SAMPLES, "turns": pe.MIN_TURNS, "rounds": pe.MIN_ROUNDS,
                "turn_mode": "forward", "toc_match": "I: Loomings",
                "serial": self.serial, "adb": self.adb}
        base.update(kw)
        return base

    def stage(self, name, source=None, **kw):
        ticks = iter(range(1, 10 ** 9))
        return pe.run_stage("epub", name, self.args(**kw), runner=self.runner,
                            source=dict(source or self.source),
                            clock=lambda: next(ticks) * 1_000_000,
                            sleep=lambda seconds: None)

    def prepare_run(self, units=5):
        out, code = self.stage("init")
        self.assertEqual(code, 0, out)
        self.assertEqual(out.get("status"), "INITIALIZED_NOT_EXECUTED")
        for index in range(1, units + 1):
            out, code = self.stage("step")
            self.assertEqual(code, 0, out)
            self.assertEqual(out.get("status"), "UNIT_COMMITTED", out)
            self.assertEqual(out.get("unit"), index)
        return self.stage("finish")

    def codes(self, report):
        return [item["code"] for item in report.get("failures") or []]

    def state_doc(self):
        with open(os.path.join(self.run_dir, "state.json"), "rb") as handle:
            return json.loads(handle.read().decode("utf-8"))

    def save_state(self, state):
        path = os.path.join(self.run_dir, "state.json")
        data = json.dumps(state, sort_keys=True).encode("utf-8")
        self.write(path, data)
        return hashlib.sha256(data).hexdigest()

    # -- controls -----------------------------------------------------------
    def test_five_steps_then_finish_replays_to_pass(self):
        report, code = self.prepare_run()
        self.assertEqual(code, 0, report.get("failures"))
        self.assertTrue(report.get("pass"), report.get("failures"))
        self.assertEqual(report.get("verdict"), "PASS")
        self.assertEqual(len(report["run"]["committed_units"]), 5)
        self.assertEqual(len(report["runs"]), 5)
        self.assertEqual(report["samples"]["n"], 5)
        self.assertEqual(report["samples"]["p95_ms"], 400.0)
        self.assertTrue(report["raw_commands"])
        for row in report["raw_commands"]:
            self.assertEqual(row["argv"][1:3], ["-s", self.serial])
            self.assertLessEqual(row["timeout_seconds"], pe.SEGMENT_POLICY["adb_seconds"])

    def test_missing_unit_fails_finish(self):
        report, code = self.prepare_run(units=4)
        self.assertEqual(code, 1)
        self.assertFalse(report.get("pass"))
        self.assertIn("UNIT_MISSING", self.codes(report))
        self.assertIn("COLD_ATTEMPTS_INCOMPLETE", self.codes(report))

    def test_state_digest_tamper_fails_finish(self):
        self.prepare_run(units=5)
        state = self.state_doc()
        state["units"][0]["sha256"] = "0" * 64
        self.save_state(state)
        report, code = self.stage("finish")
        self.assertEqual(code, 1)
        self.assertIn("UNIT_STATE_MISMATCH", self.codes(report))

    def test_forged_frame_fails_finish(self):
        self.prepare_run(units=5)
        path = os.path.join(self.run_dir, "units", "005.json")
        with open(path, "rb") as handle:
            record = json.loads(handle.read().decode("utf-8"))
        forged = dict(record["attempt"]["frames"][0])
        forged.update(draw_start_ns=1, sync_queued_ns=2)
        record["attempt"]["frames"].append(forged)
        data = json.dumps(record, sort_keys=True).encode("utf-8")
        self.write(path, data)
        state = self.state_doc()
        state["units"][4]["sha256"] = hashlib.sha256(data).hexdigest()
        self.save_state(state)
        report, code = self.stage("finish")
        self.assertEqual(code, 1)
        self.assertIn("FRAME_NOT_IN_RAW", self.codes(report))

    def test_gesture_timeout_stops_the_run(self):
        patcher = patch.object(pe, "prepare_progress", self.timeout_prepare)
        patcher.start()
        self.addCleanup(patcher.stop)
        out, code = self.stage("init")
        self.assertEqual(code, 0, out)
        self.fail_on = ["input", "tap", "720", "1480"]
        out, code = self.stage("step")
        self.assertEqual(code, 3)
        self.assertEqual(out.get("status"), "INTERRUPTED")
        self.assertEqual(out.get("code"), "COMMAND_TIMEOUT")
        self.assertFalse(out.get("retryable"))
        self.assertFalse(os.path.exists(os.path.join(self.run_dir, "units", "001.json")))
        self.assertIsNotNone(self.state_doc().get("pending"))
        out, code = self.stage("step")
        self.assertEqual(code, 3)
        self.assertEqual(out.get("status"), "OBSERVE_REQUIRED")
        self.assertEqual(out.get("code"), "GESTURE_UNKNOWN")
        report, code = self.stage("finish")
        self.assertEqual(code, 1)
        self.assertIn("GESTURE_UNKNOWN", self.codes(report))

    def test_source_drift_is_rejected(self):
        out, code = self.stage("init")
        self.assertEqual(code, 0, out)
        drifted = dict(self.source, source_tree_sha256="d" * 64)
        out, code = self.stage("step", source=drifted)
        self.assertEqual(code, 1)
        self.assertEqual(out.get("status"), "REJECTED")
        self.assertEqual(out.get("code"), "SOURCE_DRIFT")
        self.assertFalse(os.path.exists(os.path.join(self.run_dir, "units", "001.json")))


class FakeRoundHarness(object):
    """Shared offline harness: fake device, fake provenance, real collector."""

    def start_fake(self):
        self.root = tempfile.mkdtemp(prefix="p1-round-")
        self.addCleanup(shutil.rmtree, self.root, True)
        self.inputs = ft.prepare_inputs(self.root)
        self.device = ft.new_state(self.inputs["apk_sha256"])
        ft.save_state(self.state_path(), self.device)
        patchers = ft.patch_runtime(self.inputs, state=self.device, root=self.root)
        self.addCleanup(ft._MEMORY.update, {"state": None, "root": None})
        self.addCleanup(lambda: [item.stop() for item in patchers])
        self.run_dir = self.inputs["run_dir"]

    def state_path(self):
        return os.path.join(self.root, "fake_state.json")

    def rel(self, *parts):
        return os.path.join(self.run_dir, *parts)

    def load(self, *parts):
        with open(self.rel(*parts), "r", encoding="utf-8") as handle:
            return json.load(handle)

    def save(self, value, *parts):
        path = self.rel(*parts)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(json.dumps(value, sort_keys=True))

    def runner(self, **kw):
        return ft.InProcessRunner(self.root, self.device, **kw)

    def stage(self, name, runner=None, clock=None):
        return pe.run_stage("epub-turn", name, ft.run_args(self.inputs),
                            runner=runner or self.runner(),
                            source=dict(self.inputs["source"]),
                            clock=clock or (lambda: ft.now(self.root)),
                            sleep=lambda seconds: None)

    def init(self):
        out, code = self.stage("init")
        if code != 0:
            raise AssertionError("init failed: %r" % (out,))
        return out

    def freeze_clock(self):
        """One host call's clock: freeze the budget once the last tap is out.

        Jumping to 98 s of the 120 s segment leaves 22 s, which is above the
        guard but below the finished-round reserve, so the call stops at the
        finish boundary exactly like a slow real device does.
        """
        state = {"start": None, "frozen": None}

        def clock():
            now = ft.now(self.root)
            if state["start"] is None:
                state["start"] = now
            if state["frozen"] is None and int(self.device.get("taps") or 0) >= pe.MIN_TURNS:
                state["frozen"] = state["start"] + int(98e9)
            return state["frozen"] if state["frozen"] is not None else now
        return clock

    def drive(self, limit=24, runner=None, clock=None):
        """Run host calls until a round commits; returns every (out, code) pair."""
        steps = []
        for _ in range(limit):
            out, code = self.stage("step", runner=runner, clock=clock)
            steps.append((out, code))
            if out.get("status") not in ("ROUND_PAUSED",):
                return steps
        raise AssertionError("round never committed in %d host calls" % limit)

    def journal(self, unit=1, attempt=1):
        return self.load("rounds", "%03d" % unit, "%02d" % attempt, "journal.json")

    def segments(self, unit=1, attempt=1):
        return self.load("rounds", "%03d" % unit, "%02d" % attempt, "segments",
                         "%02d.json" % 1)

    def planned(self, unit=1, attempt=1):
        directory = self.rel("commands", "%03d" % unit, "%02d" % attempt)
        rows = []
        for name in sorted(os.listdir(directory)) if os.path.isdir(directory) else []:
            if name.endswith(".planned.json"):
                with open(os.path.join(directory, name), "r", encoding="utf-8") as handle:
                    rows.append(json.load(handle))
        return rows

    def taps(self, unit=1, attempt=1):
        return [row for row in self.planned(unit, attempt)
                if row["argv"][3:] == ["shell", "input", "tap", ft.NEXT_ARROW_X, "2782"]]

    def record(self, unit=1):
        return self.load("units", "%03d.json" % unit)


class StagedTurnRoundTests(FakeRoundHarness, unittest.TestCase):
    """One 20-turn round spread over several host calls, offline."""

    def setUp(self):
        self.start_fake()

    def drive_to_finish_pause(self):
        """Drive until every turn is spent and only the finish stage is left.

        The clock freezes at a remaining budget that is under the finish reserve
        but over the guard, which is exactly the boundary the reviewer hit: the
        next host call has no live capture and must rebuild both of them.
        """
        steps = []
        for _ in range(24):
            out, code = self.stage("step", clock=self.freeze_clock())
            steps.append((out, code))
            if out.get("status") != "ROUND_PAUSED" or self.journal()["stage"] == "finish":
                break
        journal = self.journal()
        self.assertEqual(journal["stage"], "finish", journal)
        self.assertEqual(journal["next_turn"], pe.MIN_TURNS + 1, journal)
        self.assertEqual(journal["status"], "RUNNING", journal)
        slot = self.load("state.json")["units"][0]
        self.assertEqual(slot["status"], "IN_PROGRESS", slot)
        self.assertEqual(slot["attempts"], 1, slot)
        self.assertEqual(steps[-1][0].get("status"), "ROUND_PAUSED", steps[-1])
        self.assertFalse(os.path.exists(self.rel("units", "001.json")))
        return steps

    # -- structure ----------------------------------------------------------
    def test_first_host_call_pauses_between_complete_turns(self):
        self.init()
        out, code = self.stage("step")
        self.assertEqual(code, 0, out)
        self.assertEqual(out.get("status"), "ROUND_PAUSED", out)
        self.assertEqual(out.get("stage"), "turns", out)
        journal = self.journal()
        self.assertEqual(journal["status"], "RUNNING")
        self.assertGreaterEqual(journal["next_turn"], 2)
        self.assertEqual(journal["next_turn"], self.device["taps"] + 1)
        self.assertEqual(len(journal["segments"]), 1)
        self.assertFalse(os.path.exists(self.rel("units", "001.json")))
        segment = self.segments()
        self.assertEqual(segment["schema"], pe.ROUND_SEGMENT_SCHEMA)
        self.assertEqual(segment["next_turn"], journal["next_turn"])
        self.assertEqual([page["turn"] for page in segment["pages"]],
                         list(range(1, journal["next_turn"])))

    def test_one_round_spans_segments_and_passes_the_shared_graders(self):
        self.init()
        steps = self.drive()
        out, code = steps[-1]
        self.assertEqual(code, 0, out)
        self.assertEqual(out.get("status"), "UNIT_COMMITTED", out)
        journal = self.journal()
        self.assertEqual(journal["status"], "COMPLETED")
        self.assertEqual(len(journal["segments"]), len(steps))
        self.assertGreater(len(steps), 1)
        record = self.record()
        attempt = record["attempt"]
        self.assertTrue(attempt["ok"], attempt.get("detail"))
        self.assertEqual(attempt["segment_count"], len(steps))
        self.assertEqual([page["turn"] for page in attempt["pages"]],
                         list(range(1, pe.MIN_TURNS + 1)))
        self.assertEqual([page["page"] for page in attempt["pages"]],
                         list(range(2, pe.MIN_TURNS + 2)))
        events = [pe.parse_perf_line(raw) for raw in attempt["events"]]
        self.assertTrue(all(item is not None for item in events))
        verdict = pe.validate_page_events(events, 59, pe.QA_BOOK_ID, pe.MIN_TURNS,
                                          start_page=1)
        self.assertTrue(verdict.pass_, verdict.failures)
        replay = pe.replay_attempt(record)
        gfx = pe.evaluate_gfxinfo(attempt["gfx_text"], replay["frames"], replay["events"],
                                  pe.Verdict(), refresh_rate_hz=60,
                                  expected_pid=attempt["pid"])
        self.assertEqual(gfx["total_frames"], pe.MIN_TURNS)
        self.assertEqual(gfx["janky_frames"], 0)
        self.assertLessEqual(gfx["frame_duration"]["p95_ms"], pe.TURN_P95_BUDGET_MS)

    def test_no_page_is_turned_twice_across_segments(self):
        self.init()
        self.drive()
        taps = self.taps()
        self.assertEqual(len(taps), pe.MIN_TURNS)
        self.assertEqual(self.device["taps"], pe.MIN_TURNS)
        self.assertEqual(self.device["entries"], 1)
        journal = self.journal()
        turns = []
        for item in journal["segments"]:
            segment = self.load(*item["path"].split("/"))
            turns.extend(page["turn"] for page in segment["pages"])
        self.assertEqual(turns, list(range(1, pe.MIN_TURNS + 1)))

    def test_unit_record_commands_span_every_segment(self):
        self.init()
        self.drive()
        record = self.record()
        journal = self.journal()
        rows = self.planned()
        self.assertEqual(record["commands"], len(rows))
        self.assertEqual(journal["commands"], record["commands"])
        self.assertEqual(len(record["command_sha256"]), record["commands"])
        self.assertEqual([row["command_id"] for row in rows],
                         ["commands/001/01/%06d" % number
                          for number in range(1, len(rows) + 1)])
        self.assertGreater(record["commands"], pe.MIN_TURNS)
        marks = [int(item["commands"]) for item in journal["segments"]]
        self.assertEqual(marks, sorted(marks))
        self.assertEqual(len(set(marks)), len(marks))
        self.assertLessEqual(marks[-1], record["commands"])
        for item in journal["segments"]:
            segment = self.load(*item["path"].split("/"))
            self.assertEqual(segment["schema"], pe.ROUND_SEGMENT_SCHEMA)
            self.assertTrue(segment["stages"])

    def test_three_rounds_commit_and_finish_replays_clean(self):
        self.init()
        for index in range(pe.MIN_ROUNDS):
            steps = self.drive()
            out, code = steps[-1]
            self.assertEqual(code, 0, out)
            self.assertEqual(out.get("unit"), index + 1, out)
        report, code = self.stage("finish")
        self.assertNotIn("UNIT_MISSING", self.codes(report))
        # Segmentation is a collection fact, not a measurement fault: the combined
        # evidence still has to pass the shared 16 ms / 2 % graders on its own.
        self.assertEqual(report.get("verdict"), "PASS", report.get("failures"))
        self.assertEqual(len(report["rounds"]), pe.MIN_ROUNDS)
        self.assertEqual(report["samples"]["n"], pe.MIN_ROUNDS * pe.MIN_TURNS)
        self.assertEqual(report["janky_percent"], 0.0)
        self.assertLessEqual(report["samples"]["p95_ms"], pe.TURN_P95_BUDGET_MS)
        for row in report["run"]["committed_units"]:
            self.assertGreater(row["round_segments"], 1)
            self.assertEqual(row["round_turns"], pe.MIN_TURNS)
            self.assertTrue(row["round_journal"], row)
            self.assertTrue(os.path.exists(os.path.join(
                report["run"]["run_dir"], *row["round_journal"].split("/"))), row)
        self.assertEqual([row["code"] for row in report["diagnostics"]], [])
        self.assertEqual(self.device["entries"], pe.MIN_ROUNDS)
        self.assertEqual(self.device["taps"], pe.MIN_TURNS)

    def codes(self, report):
        return [row["code"] for row in report.get("failures") or []]

    # -- budget -------------------------------------------------------------
    def test_low_budget_pauses_without_sending_a_command(self):
        self.init()
        self.stage("step")
        before = self.planned()
        journal = self.journal()
        seq = iter([0] + [int(118e9)] * 500)

        def clock():
            try:
                return next(seq)
            except StopIteration:
                return int(118e9)

        out, code = self.stage("step", clock=clock)
        self.assertEqual(code, 0, out)
        self.assertEqual(out.get("status"), "ROUND_PAUSED", out)
        after = self.planned()
        self.assertEqual(len(after), len(before))
        tail = self.journal()
        self.assertEqual(tail["next_turn"], journal["next_turn"])
        self.assertEqual(tail["commands"], len(before))
        self.assertEqual(len(tail["segments"]), len(journal["segments"]))

    def test_resume_is_refused_when_the_tail_receipts_are_incomplete(self):
        self.init()
        self.stage("step")
        directory = self.rel("commands", "001", "01")
        names = sorted(os.listdir(directory))
        last = [name for name in names if name.endswith(".receipt.json")][-1]
        os.remove(os.path.join(directory, last))
        out, code = self.stage("step")
        self.assertEqual(code, 3, out)
        self.assertEqual(out.get("status"), "INTERRUPTED", out)
        self.assertEqual(out.get("code"), "COMMAND_TAIL_INCOMPLETE", out)
        self.assertEqual(self.journal()["status"], "ABORTED")
        self.assertFalse(os.path.exists(self.rel("units", "001.json")))

    def test_resume_is_refused_when_the_command_sequence_has_a_gap(self):
        self.init()
        self.stage("step")
        directory = self.rel("commands", "001", "01")
        names = sorted(name for name in os.listdir(directory)
                       if name.endswith(".planned.json"))
        os.remove(os.path.join(directory, names[2]))
        with open(os.path.join(directory, names[3]), "r", encoding="utf-8") as handle:
            row = json.load(handle)
        row["command_id"] = "commands/001/01/999999"
        with open(os.path.join(directory, "999999.planned.json"), "w", encoding="utf-8") as handle:
            handle.write(json.dumps(row, sort_keys=True))
        out, code = self.stage("step")
        self.assertEqual(code, 3, out)
        self.assertEqual(out.get("code"), "COMMAND_SEQUENCE_GAP", out)

    def test_resume_is_refused_when_the_journal_command_count_drifts(self):
        self.init()
        self.stage("step")
        journal = self.journal()
        journal["commands"] = int(journal["commands"]) + 1
        self.save(journal, "rounds", "001", "01", "journal.json")
        out, code = self.stage("step")
        self.assertEqual(code, 3, out)
        self.assertEqual(out.get("code"), "COMMAND_TAIL_DRIFT", out)

    # -- tamper and drift ---------------------------------------------------
    def test_segment_digest_tamper_is_rejected_at_commit(self):
        self.init()
        self.stage("step")
        path = ("rounds", "001", "01", "segments", "01.json")
        segment = self.load(*path)
        segment["next_turn"] = int(segment["next_turn"]) + 1
        self.save(segment, *path)
        steps = self.drive()
        out, code = steps[-1]
        self.assertEqual(code, 1, out)
        self.assertEqual(out.get("status"), "REJECTED", out)
        self.assertEqual(out.get("code"), "ROUND_SEGMENT_DRIFT", out)
        self.assertFalse(os.path.exists(self.rel("units", "001.json")))

    def test_frame_drift_between_segments_is_rejected(self):
        self.init()
        self.stage("step")
        state = json.loads(json.dumps(self.device))
        state["frames"][0]["frame_completed_ns"] = int(state["frames"][0]["frame_completed_ns"]) + 1
        ft.save_state(self.state_path(), state)
        self.device["frames"] = state["frames"]
        steps = self.drive()
        out, code = steps[-1]
        self.assertEqual(code, 1, out)
        self.assertEqual(out.get("code"), "ROUND_FRAME_DRIFT", out)

    def test_process_change_between_segments_fails_the_round(self):
        self.init()
        self.stage("step")
        self.device["pid"] = 6100
        ft.save_state(self.state_path(), self.device)
        steps = self.drive()
        out, code = steps[-1]
        self.assertEqual(code, 0, out)
        attempt = self.record()["attempt"]
        self.assertFalse(attempt["ok"])
        self.assertEqual(attempt["detail"], "ROUND_PID_CHANGED")

    def test_page_label_drift_between_segments_fails_the_round(self):
        self.init()
        self.stage("step")
        self.device["page"] = int(self.device["page"]) + 1
        ft.save_state(self.state_path(), self.device)
        steps = self.drive()
        attempt = self.record()["attempt"]
        self.assertFalse(attempt["ok"])
        self.assertEqual(attempt["detail"], "PAGE_LABEL_DRIFT")

    def test_turn_emitted_while_paused_fails_the_round(self):
        self.init()
        self.stage("step")
        expected = self.journal()["current_page"] + 1
        self.device["events"].append(ft.event_line(self.device["taps"] + 1, expected,
                                                   self.device["pid"]))
        ft.save_state(self.state_path(), self.device)
        steps = self.drive()
        attempt = self.record()["attempt"]
        self.assertFalse(attempt["ok"])
        self.assertEqual(attempt["detail"], "ROUND_UNTRACKED_TURN")

    def test_tail_read_failure_never_seals_a_completed_round(self):
        self.init()
        budget = {"left": 1, "reads": 0}

        def fail_on(tail):
            # Fail the third read after the last tap: turn 20's own wait, then
            # finish_capture, then the segment tail. Only the tail is allowed to
            # hurt the round, and it must hurt it.
            if tuple(tail[:1]) != ("logcat",) or int(self.device.get("taps") or 0) < pe.MIN_TURNS:
                return False
            budget["reads"] += 1
            if budget["left"] and budget["reads"] == 3:
                budget["left"] -= 1
                return True
            return False

        runner = self.runner(fail_on=fail_on)
        steps = self.drive(runner=runner)
        out, code = steps[-1]
        self.assertEqual(code, 1, out)
        self.assertEqual(out.get("code"), "ROUND_TAIL_UNPROVEN", out)
        self.assertFalse(os.path.exists(self.rel("units", "001.json")))
        journal = self.journal()
        self.assertEqual(journal["status"], "FAILED")
        self.assertEqual(journal["stage"], "done")
        self.assertTrue(journal.get("tail_failures"), journal)
        self.assertTrue(journal.get("tail_failures")[0]["message"].startswith("logcat:"))
        # The sealed round is evidence, never a resume point.
        self.assertIsNone(pe.resumable_attempt(pe.RunStore(self.rel()), self.load("state.json")["units"][0], 1))
        out, code = self.stage("step")
        self.assertEqual(code, 0, out)
        self.assertTrue(os.path.isdir(self.rel("commands", "001", "02")),
                        "the retry must open a fresh attempt directory")

    def test_malformed_log_line_while_paused_fails_the_round(self):
        self.init()
        self.stage("step")
        self.device["events"].append(
            "11-20 20:10:00.000 %d %d E PBPERF : PBPERF|phase=reader_epub_page|ms=oops|extra=page:7"
            % (self.device["pid"], self.device["pid"]))
        ft.save_state(self.state_path(), self.device)
        self.drive()
        attempt = self.record()["attempt"]
        self.assertFalse(attempt["ok"])
        self.assertEqual(attempt["detail"], "ROUND_UNTRACKED_TURN")

    def test_resume_requires_the_measured_app_in_front(self):
        self.init()
        self.stage("step")
        self.device["foreground"] = \
            "  mResumedActivity: ActivityRecord{x org.telegram.messenger/.LaunchActivity}\n"
        ft.save_state(self.state_path(), self.device)
        self.drive()
        attempt = self.record()["attempt"]
        self.assertFalse(attempt["ok"])
        self.assertEqual(attempt["detail"], "BACKGROUND_WINDOW")
        self.assertTrue(any(item["code"] == "BACKGROUND_WINDOW"
                            for item in attempt["collection_failures"]))

    def test_chapter_page_total_drift_fails_the_round(self):
        self.init()
        self.stage("step")
        self.device["page_total"] = ft.TOTAL_PAGES + 1
        ft.save_state(self.state_path(), self.device)
        self.drive()
        attempt = self.record()["attempt"]
        self.assertFalse(attempt["ok"])
        self.assertEqual(attempt["detail"], "PAGE_COUNT_DRIFT")

    def test_round_paused_at_finish_resumes_with_both_captures(self):
        self.init()
        self.drive_to_finish_pause()
        before = len(self.planned())
        out, code = self.stage("step")
        self.assertEqual(code, 0, out)
        self.assertEqual(out.get("status"), "UNIT_COMMITTED", out)
        journal = self.journal()
        self.assertEqual(journal["status"], "COMPLETED")
        self.assertGreaterEqual(len(journal["segments"]), 2, journal["segments"])
        # Not the exact shape of the last slice (the pause boundary can land on a
        # segment that already drained its tail): the round must have run finish
        # somewhere after the 20th turn, and no turn may be counted twice.
        stages = [name for item in journal["segments"] for name in item["stages"]]
        self.assertIn("finish", stages)
        self.assertEqual(journal["next_turn"], pe.MIN_TURNS + 1)
        self.assertEqual(sum(int(item["turns"]) for item in journal["segments"]),
                         pe.MIN_TURNS)
        self.assertGreater(len(self.planned()), before)
        record = self.record()
        attempt = record["attempt"]
        self.assertTrue(attempt["ok"], attempt.get("detail"))
        self.assertEqual(len(attempt["pages"]), pe.MIN_TURNS)
        self.assertEqual(attempt["pid"], attempt["pid_end"])
        self.assertIn("Total frames rendered", attempt["gfx_text"])
        events = [pe.parse_perf_line(raw) for raw in attempt["events"]]
        self.assertTrue(pe.validate_page_events(events, 59, pe.QA_BOOK_ID, pe.MIN_TURNS,
                                                start_page=1).pass_)
        # Twenty taps for twenty pages: the resumed host call invented no gesture.
        self.assertEqual(len(self.taps()), pe.MIN_TURNS)
        self.assertEqual(self.device["taps"], pe.MIN_TURNS)

    def test_finish_resume_refuses_a_changed_process(self):
        self.init()
        self.drive_to_finish_pause()
        self.device["pid"] = 6200
        ft.save_state(self.state_path(), self.device)
        self.drive()
        attempt = self.record()["attempt"]
        self.assertFalse(attempt["ok"])
        self.assertEqual(attempt["detail"], "ROUND_PID_CHANGED")

    def test_finish_resume_refuses_a_drifted_page(self):
        self.init()
        self.drive_to_finish_pause()
        self.device["page"] = int(self.device["page"]) + 1
        ft.save_state(self.state_path(), self.device)
        self.drive()
        attempt = self.record()["attempt"]
        self.assertFalse(attempt["ok"])
        self.assertEqual(attempt["detail"], "PAGE_LABEL_DRIFT")

    def test_finish_resume_refuses_an_untracked_line(self):
        self.init()
        self.drive_to_finish_pause()
        self.device["events"].append(
            ft.event_line(self.device["taps"], int(self.device["page"]) - 1, self.device["pid"]))
        ft.save_state(self.state_path(), self.device)
        self.drive()
        attempt = self.record()["attempt"]
        self.assertFalse(attempt["ok"])
        self.assertEqual(attempt["detail"], "ROUND_UNTRACKED_TURN")

    def test_gesture_timeout_keeps_the_round_unresumable(self):
        self.init()
        runner = self.runner(fail_on=("input", "tap", ft.NEXT_ARROW_X, "2782"))
        out, code = self.stage("step", runner=runner)
        self.assertEqual(code, 3, out)
        self.assertEqual(out.get("code"), "COMMAND_TIMEOUT", out)
        self.assertFalse(out.get("retryable"))
        self.assertIsNotNone(self.load("state.json").get("pending"))
        # A timed-out gesture leaves one command whose outcome is unknown, so the
        # round is sealed failed here instead of looking resumable.
        tail = self.journal()
        self.assertEqual(tail["status"], "FAILED")
        self.assertEqual(tail["detail"], "COMMAND_TAIL_INCOMPLETE")
        segment = self.load(*tail["segments"][0]["path"].split("/"))
        self.assertTrue(any("aborted command retained" in item["message"]
                            for item in segment["collection_failures"]),
                        segment["collection_failures"])
        self.assertFalse(os.path.exists(self.rel("units", "001.json")))
        out, code = self.stage("step")
        self.assertEqual(code, 3, out)
        self.assertEqual(out.get("status"), "OBSERVE_REQUIRED", out)
        self.assertEqual(out.get("code"), "GESTURE_UNKNOWN", out)


    # -- reviewer follow-ups ------------------------------------------------
    def receipts(self, kind=None, unit=1, attempt=1):
        directory = self.rel("commands", "%03d" % unit, "%02d" % attempt)
        rows = []
        for name in sorted(os.listdir(directory)) if os.path.isdir(directory) else []:
            if not name.endswith(".receipt.json"):
                continue
            with open(os.path.join(directory, name), "r", encoding="utf-8") as handle:
                receipt = json.load(handle)
            if kind is None or pe.command_class(receipt["argv"]) == kind:
                rows.append(receipt)
        return rows

    def test_line_after_the_last_tap_is_never_lost(self):
        """A marker emitted right after the 20th tap must survive the tail read."""
        self.init()
        marker = "PBPERF|phase=trace_probe|ms=1.0|extra="
        latched = {"done": False}

        def on_call(tail):
            if not latched["done"] and tail[:1] == ["logcat"] \
                    and int(self.device.get("taps") or 0) >= pe.MIN_TURNS:
                latched["done"] = True
                self.device["events"].append(marker)

        steps = self.drive(runner=self.runner(on_call=on_call))
        self.assertTrue(latched["done"], "the control never reached the last turn")
        self.assertEqual(steps[-1][0].get("status"), "UNIT_COMMITTED", steps[-1])
        attempt = self.record()["attempt"]
        self.assertTrue(attempt["ok"], attempt.get("detail"))
        self.assertIn(marker, attempt["raw_logcat"])
        self.assertEqual(len(self.taps()), pe.MIN_TURNS)

    def test_finish_refuses_a_round_whose_journal_is_gone(self):
        self.init()
        self.drive()
        os.remove(self.rel("rounds", "001", "01", "journal.json"))
        out, code = self.stage("finish")
        self.assertEqual(code, 1, out)
        self.assertIn("ROUND_NOT_CLOSED",
                      [item["code"] for item in out.get("failures") or []])

    def test_failure_retained_by_an_earlier_segment_still_fails_the_round(self):
        """The last host call may not seal a round an earlier slice broke."""
        self.init()
        polluted = {"done": False}

        def on_call(tail):
            if not polluted["done"] and tail[:1] == ["logcat"] \
                    and int(self.device.get("taps") or 0) >= 2:
                polluted["done"] = True
                self.device["events"].append("PBPERF|phase=broken|ms=oops")

        steps = self.drive(runner=self.runner(on_call=on_call))
        self.assertTrue(polluted["done"], "the control never injected the bad line")
        self.assertEqual(steps[-1][0].get("status"), "UNIT_COMMITTED", steps[-1])
        journal = self.journal()
        self.assertEqual(journal["status"], "FAILED", journal)
        self.assertFalse(journal["ok"], journal)
        self.assertEqual(journal["prior_failures"][0]["code"], "EVENT_PARSE",
                         journal["prior_failures"])
        attempt = self.record()["attempt"]
        self.assertFalse(attempt["ok"], attempt.get("detail"))

    def test_frame_ring_overflow_between_segments_fails_closed(self):
        """Rows scrolled out of gfxinfo's ring cannot be proven; the round stops."""
        self.init()
        self.stage("step")
        journal = self.journal()
        self.assertEqual(journal["stage"], "turns", journal)
        for extra in range(ft.FRAME_RING + 40):
            self.device["frames"].append(ft.frame_row(self.device, 500 + extra))
        ft.save_state(self.state_path(), self.device)
        out, code = self.stage("step")
        self.assertEqual(code, 0, out)
        self.assertEqual(out.get("status"), "UNIT_COMMITTED", out)
        attempt = self.record()["attempt"]
        self.assertFalse(attempt["ok"], attempt.get("detail"))
        self.assertEqual(attempt["detail"], "ROUND_FRAME_GAP")
        self.assertEqual(self.journal()["status"], "FAILED")
        self.assertEqual(len(self.taps()), journal["next_turn"] - 1)

    def test_frames_from_another_process_cannot_join_the_round(self):
        """One round, one pid: a ring that switched process is not this round."""
        self.init()
        self.stage("step")
        journal = self.journal()
        self.assertEqual(journal["stage"], "turns", journal)
        self.device["frame_pid"] = int(journal["pid"]) + 1
        ft.save_state(self.state_path(), self.device)
        out, code = self.stage("step")
        self.assertEqual(code, 0, out)
        self.assertEqual(out.get("status"), "UNIT_COMMITTED", out)
        attempt = self.record()["attempt"]
        self.assertFalse(attempt["ok"], attempt.get("detail"))
        self.assertEqual(attempt["detail"], "ROUND_FRAME_PID")
        self.assertEqual(len(self.taps()), journal["next_turn"] - 1)

    def test_resumed_finish_keeps_a_real_gfxinfo_read(self):
        self.init()
        self.drive_to_finish_pause()
        before = len(self.receipts("gfxinfo"))
        out, code = self.stage("step")
        self.assertEqual(code, 0, out)
        self.assertEqual(out.get("status"), "UNIT_COMMITTED", out)
        attempt = self.record()["attempt"]
        self.assertIn("Total frames rendered", attempt["gfx_text"])
        rows = self.receipts("gfxinfo")
        self.assertGreater(len(rows), before)
        texts = []
        for row in rows:
            path = self.rel(*row["stdout"]["path"].split("/")) \
                if isinstance(row["stdout"], dict) else self.rel(*row["stdout"].split("/"))
            with open(path, "rb") as handle:
                texts.append(handle.read().decode("utf-8", "replace"))
        self.assertIn(attempt["gfx_text"], texts)


class CrossProcessRoundTests(FakeRoundHarness, unittest.TestCase):
    """Every segment of one round runs in its own host process."""

    def setUp(self):
        self.start_fake()

    def test_finish_stage_resumes_in_a_new_process(self):
        pid, code, out = self.spawn("init")
        self.assertEqual(code, 0, out)
        for _ in range(24):
            out, code = self.stage("step", clock=self.freeze_clock())
            self.assertEqual(code, 0, out)
            if self.journal()["stage"] == "finish":
                break
        self.assertEqual(self.journal()["stage"], "finish", self.journal()["status"])
        self.assertFalse(os.path.exists(self.rel("units", "001.json")))
        pid, code, out = self.spawn("step")
        self.assertEqual(code, 0, out)
        self.assertEqual(out.get("status"), "UNIT_COMMITTED", out)
        self.assertNotEqual(pid, os.getpid())
        journal = self.journal()
        self.assertEqual(journal["status"], "COMPLETED")
        stages = [name for item in journal["segments"] for name in item["stages"]]
        self.assertIn("finish", stages)
        self.assertEqual(sum(int(item["turns"]) for item in journal["segments"]),
                         pe.MIN_TURNS)
        attempt = self.record()["attempt"]
        self.assertTrue(attempt["ok"], attempt.get("detail"))
        self.assertEqual(len(attempt["pages"]), pe.MIN_TURNS)
        self.assertEqual(len(self.taps()), pe.MIN_TURNS)

    def spawn(self, stage):
        done = subprocess.run([sys.executable, os.path.join(HERE, "fake_turns.py"),
                               stage, self.root], capture_output=True, timeout=900)
        self.assertEqual(done.returncode, 0, done.stderr[-2000:])
        payload = json.loads(done.stdout.decode("utf-8").strip().splitlines()[-1])
        return payload["step_pid"], payload["code"], payload["out"]

    def test_each_segment_is_a_separate_process(self):
        pid, code, out = self.spawn("init")
        self.assertEqual(code, 0, out)
        self.assertNotEqual(pid, os.getpid())
        pids = []
        for _ in range(12):
            pid, code, out = self.spawn("step")
            pids.append(pid)
            if out.get("status") != "ROUND_PAUSED":
                break
        self.assertEqual(code, 0, out)
        self.assertEqual(out.get("status"), "UNIT_COMMITTED", out)
        self.assertGreaterEqual(len(pids), 3)
        self.assertEqual(len(set(pids)), len(pids))
        self.assertNotIn(os.getpid(), pids)
        journal = self.journal()
        self.assertEqual(len(journal["segments"]), len(pids))
        record = self.record()
        attempt = record["attempt"]
        self.assertTrue(attempt["ok"], attempt.get("detail"))
        events = [pe.parse_perf_line(raw) for raw in attempt["events"]]
        verdict = pe.validate_page_events(events, 59, pe.QA_BOOK_ID, pe.MIN_TURNS,
                                          start_page=1)
        self.assertTrue(verdict.pass_, verdict.failures)
        self.assertEqual(attempt["pid"], attempt["pid_end"])
