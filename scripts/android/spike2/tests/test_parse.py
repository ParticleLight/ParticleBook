"""scripts/android/spike2/parse.py 的单元测试。

运行（仓库根执行）：

    python -m unittest discover -s scripts/android/spike2/tests -v

只依赖标准库；所有日志都在临时目录里合成，不写仓库文件。
"""

import csv
import io
import json
import sys
import tempfile
import textwrap
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

HERE = Path(__file__).resolve()
SPIKE2_DIR = HERE.parents[1]
REPO_ROOT = HERE.parents[4]
FIXTURES_DIR = HERE.parent / "fixtures"

if not (REPO_ROOT / "scripts" / "android" / "spike2" / "parse.py").is_file():
    raise RuntimeError("无法定位仓库根（从 %s 推得 %s）" % (HERE, REPO_ROOT))
if str(SPIKE2_DIR) not in sys.path:
    sys.path.insert(0, str(SPIKE2_DIR))

import parse  # noqa: E402

CSV_HEADER = (
    "run_id,app,scenario,phase,page,w,h,ms,extra,ts,record,src_file,src_line,warmup,confidence,flags"
)

PERF_LINE = "11-01 18:52:54.123  4321  4350 I PBPERF  : PBPERF|%s"
BARE_LINE = "PBPERF|%s"


def perf(**fields):
    """按 契约顺序 拼一条 PBPERF payload。"""
    order = ("scenario", "phase", "page", "w", "h", "ms", "extra")
    parts = []
    for key in order:
        if key in fields and fields[key] is not None:
            parts.append("%s=%s" % (key, fields[key]))
    return "|".join(parts)


class ParseTestBase(unittest.TestCase):
    def setUp(self):
        self._tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmpdir.cleanup)
        self.tmp = Path(self._tmpdir.name)
        self.out = self.tmp / "out"

    def write_log(self, name, text, folder=None):
        path = Path(folder) / name if folder is not None else self.tmp / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(textwrap.dedent(text).lstrip("\n"), encoding="utf-8")
        return path

    def run_cli(self, argv):
        out_buf = io.StringIO()
        err_buf = io.StringIO()
        with redirect_stdout(out_buf), redirect_stderr(err_buf):
            code = parse.main([str(item) for item in argv])
        return code, out_buf.getvalue(), err_buf.getvalue()

    def parse_paths(self, paths, name="run", extra=()):
        argv = ["--out", self.out, "--name", name, "--quiet"]
        argv.extend(extra)
        argv.extend(paths)
        code, _stdout, stderr = self.run_cli(argv)
        self.assertEqual(code, 0, "解析器退出码应为 0，stderr:\n" + stderr)
        self.assertTrue((self.out / (name + ".csv")).is_file(), "缺少 CSV")
        self.assertTrue((self.out / (name + ".stats.json")).is_file(), "缺少 stats.json")
        self.assertTrue((self.out / (name + ".md")).is_file(), "缺少 md")
        return self.rows(name), self.stats(name), self.md(name)

    def rows(self, name):
        with (self.out / (name + ".csv")).open(encoding="utf-8", newline="") as handle:
            return list(csv.DictReader(handle))

    def stats(self, name):
        return json.loads((self.out / (name + ".stats.json")).read_text(encoding="utf-8"))

    def md(self, name):
        return (self.out / (name + ".md")).read_text(encoding="utf-8")

    def csv_text(self, name):
        return (self.out / (name + ".csv")).read_text(encoding="utf-8")

    @staticmethod
    def group(stats, scenario, phase, scope="all"):
        for item in stats["groups"]:
            if item["scope"] == scope and item["scenario"] == scenario and item["phase"] == phase:
                return item
        raise AssertionError("找不到分组 scope=%s scenario=%s phase=%s" % (scope, scenario, phase))


class TestBasicParsing(ParseTestBase):
    def test_decimal_ms_and_negative_page(self):
        log = self.write_log(
            "render-run5.log",
            """
            11-01 18:52:54.123  4321  4350 I PBPERF  : PBPERF|scenario=S2|phase=render|page=0|w=1188|h=1682|ms=42.700|extra=path:argb8888
            11-01 18:52:54.456  4321  4350 I PBPERF  : PBPERF|scenario=S4|phase=render|page=-1|w=-1|h=-1|ms=0|extra=-
            """,
        )
        rows, _stats, _md = self.parse_paths([log])
        self.assertEqual(len(rows), 2)
        first, second = rows
        self.assertEqual(first["run_id"], "render-run5")
        self.assertEqual(first["app"], "spike2")
        self.assertEqual(first["record"], "PBPERF")
        self.assertEqual(first["scenario"], "S2")
        self.assertEqual(first["phase"], "render")
        self.assertEqual(first["page"], "0")
        self.assertEqual(first["w"], "1188")
        self.assertEqual(first["h"], "1682")
        self.assertEqual(first["ms"], "42.700")
        self.assertEqual(first["extra"], "path:argb8888")
        self.assertEqual(first["ts"], "11-01 18:52:54.123")
        self.assertEqual(first["src_line"], "1")
        self.assertEqual(first["warmup"], "false")
        self.assertEqual(second["page"], "-1")
        self.assertEqual(second["ms"], "0")

    def test_error_row_flags_and_exclusion(self):
        log = self.write_log(
            "render-err.log",
            """
            11-01 18:00:00.000  1  2 I PBPERF  : PBPERF|scenario=S8|phase=render|page=-1|w=-1|h=-1|ms=10.000|extra=-
            11-01 18:00:00.100  1  2 I PBPERF  : PBPERF|scenario=S8|phase=render|page=-1|w=-1|h=-1|ms=20.000|extra=-
            11-01 18:00:00.200  1  2 I PBPERF  : PBPERF|scenario=S8|phase=render|page=-1|w=-1|h=-1|ms=0|extra=error:java.lang.IllegalArgumentException.
            11-01 18:00:00.300  1  2 I PBPERF  : PBDONE|scenario=S8|runs=3|extra=ok
            """,
        )
        rows, stats, _md = self.parse_paths([log])
        error_rows = [row for row in rows if row["extra"].startswith("error:")]
        self.assertEqual(len(error_rows), 1)
        error_row = error_rows[0]
        self.assertEqual(error_row["confidence"], "LOW_CONFIDENCE")
        self.assertIn("error", error_row["flags"].split(";"))
        # 组内有效样本只有 2 条（MS 非空、非 warmup、非 error），error 行不参与统计
        group = self.group(stats, "S8", "render")
        self.assertEqual(group["n"], 2)
        self.assertEqual(group["median"], 15.0)
        self.assertEqual(stats["counts"]["error"], 1)

    def test_missing_fields_do_not_crash(self):
        log = self.write_log(
            "render-miss.log",
            """
            11-01 18:00:00.000  1  2 I PBPERF  : PBPERF|scenario=S1|phase=open
            11-01 18:00:00.100  1  2 I PBPERF  : PBPERF|scenario=S1|phase=open|page=3
            11-01 18:00:00.200  1  2 I PBPERF  : PBPERF|scenario=S1|phase=open|extra=x
            """,
        )
        rows, _stats, _md = self.parse_paths([log])
        self.assertEqual(len(rows), 3)
        for row in rows:
            self.assertEqual(row["ms"], "")
            self.assertEqual(row["w"], "-1")
            self.assertEqual(row["h"], "-1")
        self.assertEqual(rows[0]["page"], "-1")
        self.assertEqual(rows[0]["extra"], "")
        self.assertEqual(rows[1]["page"], "3")
        self.assertEqual(rows[2]["extra"], "x")

    def test_warmup_markers(self):
        log = self.write_log(
            "render-warm.log",
            """
            11-01 18:00:00.000  1  2 I PBPERF  : PBPERF|scenario=S2|phase=render|page=0|w=1|h=1|ms=99.000|extra=warmup
            11-01 18:00:00.100  1  2 I PBPERF  : PBPERF|scenario=S2|phase=render|page=0|w=1|h=1|ms=98.000|extra=WARMUP:pages
            11-01 18:00:00.200  1  2 I PBPERF  : PBPERF|scenario=S2|phase=render_warmup|page=0|w=1|h=1|ms=97.000|extra=-
            11-01 18:00:00.300  1  2 I PBPERF  : PBPERF|scenario=S2|phase=render|page=0|w=1|h=1|ms=40.000|extra=-
            """,
        )
        rows, stats, _md = self.parse_paths([log])
        self.assertEqual([row["warmup"] for row in rows], ["true", "true", "true", "false"])
        self.assertEqual(self.group(stats, "S2", "render")["n"], 1)
        self.assertEqual(self.group(stats, "S2", "render")["median"], 40.0)
        self.assertEqual(stats["counts"]["warmup"], 3)

    def test_drop_first_marks_leading_samples(self):
        log = self.write_log(
            "render-drop.log",
            """
            11-01 18:00:00.000  1  2 I PBPERF  : PBPERF|scenario=S2|phase=render|page=0|w=1|h=1|ms=100.000|extra=-
            11-01 18:00:00.100  1  2 I PBPERF  : PBPERF|scenario=S2|phase=render|page=0|w=1|h=1|ms=200.000|extra=-
            11-01 18:00:00.200  1  2 I PBPERF  : PBPERF|scenario=S2|phase=render|page=0|w=1|h=1|ms=300.000|extra=-
            """,
        )
        rows, stats, _md = self.parse_paths([log], extra=["--drop-first", "1"])
        self.assertEqual(len(rows), 3)
        self.assertEqual([row["warmup"] for row in rows], ["true", "false", "false"])
        group = self.group(stats, "S2", "render")
        self.assertEqual(group["n"], 2)
        self.assertEqual(group["min"], 200.0)
        self.assertEqual(stats["counts"]["warmup_dropped"], 1)

    def test_drop_mode_first_per_phase(self):
        """--drop-mode first-per-phase：每个 (scenario, phase) 组各自丢第一条。"""
        log = self.write_log(
            "render-dropmode.log",
            """
            11-01 18:00:00.000  1  2 I PBPERF  : PBPERF|scenario=S2|phase=render|page=0|w=1|h=1|ms=100.000|extra=-
            11-01 18:00:00.100  1  2 I PBPERF  : PBPERF|scenario=S2|phase=render|page=0|w=1|h=1|ms=200.000|extra=-
            11-01 18:00:00.200  1  2 I PBPERF  : PBPERF|scenario=S2|phase=present|page=0|w=1|h=1|ms=9.000|extra=-
            11-01 18:00:00.300  1  2 I PBPERF  : PBPERF|scenario=S2|phase=present|page=0|w=1|h=1|ms=8.000|extra=-
            """,
        )
        rows, stats, _md = self.parse_paths([log], extra=["--drop-mode", "first-per-phase"])
        self.assertEqual(len(rows), 4)
        self.assertEqual(
            [row["warmup"] for row in rows], ["true", "false", "true", "false"]
        )
        render_group = self.group(stats, "S2", "render")
        present_group = self.group(stats, "S2", "present")
        self.assertEqual(render_group["n"], 1)
        self.assertEqual(render_group["median"], 200.0)
        self.assertEqual(present_group["n"], 1)
        self.assertEqual(present_group["median"], 8.0)
        self.assertEqual(stats["counts"]["warmup_dropped"], 2)
        self.assertEqual(stats["options"]["drop_mode"], "first-per-phase")

    def test_drop_mode_keeps_cold_annotated_rows(self):
        """显式 cold:1 的样本不是 warmup：first-per-phase 只丢未标冷的第一条。"""
        log = self.write_log(
            "render-colddrop.log",
            """
            11-01 18:00:00.000  1  2 I PBPERF  : PBPERF|scenario=S2|phase=render|page=0|w=1|h=1|ms=100.000|extra=path:argb8888,cold:1,annot:1
            11-01 18:00:00.100  1  2 I PBPERF  : PBPERF|scenario=S2|phase=render|page=0|w=1|h=1|ms=200.000|extra=path:argb8888,cold:0,annot:1
            11-01 18:00:00.200  1  2 I PBPERF  : PBPERF|scenario=S2|phase=render|page=0|w=1|h=1|ms=300.000|extra=path:argb8888,cold:0,annot:1
            """,
        )
        rows, stats, _md = self.parse_paths([log], extra=["--drop-mode", "first-per-phase"])
        self.assertEqual([row["warmup"] for row in rows], ["false", "true", "false"])
        group = self.group(stats, "S2", "render")
        self.assertEqual(group["n"], 2)
        self.assertEqual(group["min"], 100.0)
        self.assertEqual(stats["counts"]["warmup_dropped"], 1)

    def test_unrelated_lines_are_ignored(self):
        log = self.write_log(
            "render-noisy.log",
            """
            --------- beginning of main
            11-01 18:00:00.000  1  2 I ActivityManager: Start proc 1234:app.pb.spike2/u0a1
            at foo.bar.Baz.qux(Baz.java:12)
            PBPERF|scenario=S2|phase=render|page=0|w=1|h=1|ms=1.000|extra=-
            """,
        )
        rows, stats, _md = self.parse_paths([log])
        self.assertEqual(len(rows), 1)
        self.assertEqual(stats["counts"]["parse_failures"], 0)
        self.assertEqual(stats["counts"]["records"], 1)


class TestConfidence(ParseTestBase):
    def test_missing_pbdone(self):
        lines = [PERF_LINE % perf(scenario="S2", phase="render", page=0, w=1, h=1, ms="%d.000" % i, extra="-") for i in range(1, 6)]
        log = self.write_log("render-nodone.log", "\n".join(lines) + "\n")
        rows, stats, md = self.parse_paths([log])
        for row in rows:
            self.assertIn("no_pbdone", row["flags"].split(";"))
        self.assertIn(
            {"run_id": "render-nodone", "app": "spike2", "scenario": "S2"},
            stats["missing_pbdone"],
        )
        self.assertIn("## 6.", md)
        self.assertIn("| S2 |", md)

    def test_low_confidence_sample_count(self):
        few = [PERF_LINE % perf(scenario="S3", phase="total", page=-1, w=-1, h=-1, ms="%d.000" % i, extra="-") for i in range(1, 5)]
        short_log = self.write_log("render-short.log", "\n".join(few) + "\n")
        rows, stats, md = self.parse_paths([short_log], name="short")
        self.assertEqual(self.group(stats, "S3", "total")["n"], 4)
        for row in rows:
            self.assertEqual(row["confidence"], "LOW_CONFIDENCE")
            self.assertIn("n<5", row["flags"])
        self.assertIn("## 7.", md)

        enough = [PERF_LINE % perf(scenario="S3", phase="total", page=-1, w=-1, h=-1, ms="%d.000" % i, extra="-") for i in range(1, 6)]
        ok_log = self.write_log("render-ok.log", "\n".join(enough) + "\nPBDONE|scenario=S3|runs=5|extra=ok\n")
        rows_ok, stats_ok, _md_ok = self.parse_paths([ok_log], name="ok")
        self.assertEqual(self.group(stats_ok, "S3", "total")["n"], 5)
        for row in rows_ok:
            self.assertEqual(row["confidence"], "ok")
            self.assertEqual(row["flags"], "")

    def test_pss_rows_are_kept_out_of_ms_groups(self):
        total = [PERF_LINE % perf(scenario="S4", phase="total", page=-1, w=-1, h=-1, ms="%d.000" % i, extra="-") for i in range(1, 6)]
        pss = [
            PERF_LINE % perf(scenario="S4", phase="pss", page=-1, w=-1, h=-1, ms=0, extra="pss_kb:100000,native_kb:20000,java_kb:3000"),
            PERF_LINE % perf(scenario="S4", phase="pss", page=-1, w=-1, h=-1, ms=0, extra="pss_kb:110000,native_kb:21000,java_kb:3100"),
        ]
        log = self.write_log("render-pss.log", "\n".join(total + pss) + "\n")
        _rows, stats, md = self.parse_paths([log])
        self.assertEqual(self.group(stats, "S4", "total")["n"], 5)
        phases = {item["phase"] for item in stats["groups"] if item["scope"] == "all"}
        self.assertNotIn("pss", phases)
        mem_all = [item for item in stats["mem"] if item["scope"] == "all" and item["scenario"] == "S4"]
        self.assertEqual(len(mem_all), 1)
        self.assertEqual(mem_all[0]["pss_kb"]["min"], 100000.0)
        self.assertEqual(mem_all[0]["pss_kb"]["max"], 110000.0)
        self.assertEqual(stats["counts"]["pss"], 2)
        self.assertIn("## 5.", md)


class TestRecords(ParseTestBase):
    def test_pbpdf_open_and_page_split(self):
        log = self.write_log(
            "p0-pdf.log",
            """
            10-31 19:10:00.000  4321  4350 I PBPdf   : pdf_open pages=4 docBytes=152591 took=154.0ms
            10-31 19:10:00.100  4321  4350 I PBPdf   : pdf_page page=0 1188x1682 render=160.5ms encode=258.9ms bytes=78321
            """,
        )
        rows, _stats, _md = self.parse_paths([log])
        self.assertEqual(len(rows), 3)
        self.assertEqual([row["record"] for row in rows], ["PBPDF", "PBPDF", "PBPDF"])
        self.assertEqual([row["scenario"] for row in rows], ["pdf_open", "pdf_page", "pdf_page"])
        opened = rows[0]
        self.assertEqual(opened["phase"], "open")
        self.assertEqual(opened["ms"], "154.0")
        self.assertEqual(opened["extra"], "pages:4,docBytes:152591")
        self.assertEqual(opened["app"], "particlebook")
        render_row, encode_row = rows[1], rows[2]
        self.assertEqual(render_row["phase"], "render")
        self.assertEqual(render_row["ms"], "160.5")
        self.assertEqual(encode_row["phase"], "encode")
        self.assertEqual(encode_row["ms"], "258.9")
        for row in (render_row, encode_row):
            self.assertEqual(row["page"], "0")
            self.assertEqual(row["w"], "1188")
            self.assertEqual(row["h"], "1682")
            self.assertEqual(row["extra"], "bytes:78321")
            self.assertEqual(row["src_line"], "2")

    def test_amstart_and_pbtime(self):
        log = self.write_log(
            "p0-amstart.log",
            """
            AMSTART|rep=1|TotalTime=4287|WaitTime=4300|app=particlebook
            PBTIME|app=spike2t|phase=first_frame|ms=210.500
            11-01 18:00:00.500  1  2 I PBPERF  : PBTIME|app=spike2|phase=cold_frame|ms=99.000|extra=x
            """,
        )
        rows, stats, _md = self.parse_paths([log])
        self.assertEqual(len(rows), 3)
        amstart, pbtime_bare, pbtime_tagged = rows
        self.assertEqual(amstart["record"], "AMSTART")
        self.assertEqual(amstart["scenario"], "cold_start")
        self.assertEqual(amstart["phase"], "am_start")
        self.assertEqual(amstart["ms"], "4287")
        self.assertEqual(amstart["extra"], "WaitTime:4300")
        self.assertEqual(amstart["ts"], "")
        self.assertEqual(amstart["app"], "particlebook")
        self.assertEqual(pbtime_bare["record"], "PBTIME")
        self.assertEqual(pbtime_bare["scenario"], "PBTIME")
        self.assertEqual(pbtime_bare["phase"], "first_frame")
        self.assertEqual(pbtime_bare["ms"], "210.500")
        self.assertEqual(pbtime_bare["app"], "spike2t")
        self.assertEqual(pbtime_tagged["app"], "spike2")
        self.assertEqual(pbtime_tagged["ts"], "11-01 18:00:00.500")
        self.assertEqual(self.group(stats, "PBTIME", "first_frame")["n"], 1)
        self.assertEqual(self.group(stats, "cold_start", "am_start")["n"], 1)

    def test_run_id_from_runenv(self):
        log = self.write_log(
            "stem-name.log",
            """
            RUNENV|app=spike2|run_id=actual-run|apk_md5=zzz|scenario=S2|ts=2026-11-01T10:00:00+08:00
            PBPERF|scenario=S2|phase=render|page=0|w=1|h=1|ms=1.000|extra=-
            """,
        )
        rows, stats, _md = self.parse_paths([log], name="runenv-run")
        self.assertEqual({row["run_id"] for row in rows}, {"actual-run"})
        self.assertEqual(stats["counts"]["by_record"]["RUNENV"], 1)
        self.assertEqual(len(stats["runenv"]), 1)
        self.assertEqual(stats["runenv"][0]["fields"]["apk_md5"], "zzz")


class TestStatistics(ParseTestBase):
    def test_header_row_count_and_p95(self):
        lines = ["--------- beginning of main"]
        for value in range(1, 21):
            lines.append(PERF_LINE % perf(scenario="S9", phase="total", page=-1, w=-1, h=-1, ms="%d.000" % value, extra="-"))
        log = self.write_log("render-p95.log", "\n".join(lines) + "\n")
        rows, stats, _md = self.parse_paths([log])
        self.assertEqual(len(rows), 20)
        self.assertEqual(self.csv_text("run").splitlines()[0], CSV_HEADER)
        group = self.group(stats, "S9", "total")
        self.assertEqual(group["n"], 20)
        self.assertEqual(group["min"], 1.0)
        self.assertEqual(group["max"], 20.0)
        self.assertEqual(group["median"], 10.5)
        self.assertEqual(group["p50"], 10.0)
        # nearest-rank: ceil(0.95 * 20) - 1 = 18 -> 第 19 个值
        self.assertEqual(group["p95"], 19.0)
        self.assertEqual(group["sources"][0]["n"], 20)

    def test_multi_file_merge_keeps_runs_apart(self):
        first = self.write_log(
            "first.log",
            "\n".join(
                [
                    "RUNENV|app=spike2|run_id=r1|apk_md5=a|scenario=S2|ts=2026-11-01T10:00:00+08:00",
                    PERF_LINE % perf(scenario="S2", phase="render", page=0, w=1, h=1, ms="10.000", extra="-"),
                    PERF_LINE % perf(scenario="S2", phase="render", page=0, w=1, h=1, ms="20.000", extra="-"),
                    "PBDONE|scenario=S2|runs=2|extra=ok",
                ]
            )
            + "\n",
        )
        second = self.write_log(
            "second.log",
            "\n".join(
                [
                    "RUNENV|app=spike2|run_id=r2|apk_md5=b|scenario=S2|ts=2026-11-01T11:00:00+08:00",
                    PERF_LINE % perf(scenario="S2", phase="render", page=0, w=1, h=1, ms="30.000", extra="-"),
                    PERF_LINE % perf(scenario="S2", phase="render", page=0, w=1, h=1, ms="40.000", extra="-"),
                    "PBDONE|scenario=S2|runs=2|extra=ok",
                ]
            )
            + "\n",
        )
        code, _stdout, stderr = self.run_cli(["--out", self.out, "--quiet", first, second])
        self.assertEqual(code, 0, stderr)
        rows = self.rows("first_merged")
        stats = self.stats("first_merged")
        self.assertEqual(len(rows), 8)  # 每个文件 RUNENV + 2 PBPERF + PBDONE
        perf_rows = [row for row in rows if row["record"] == "PBPERF"]
        self.assertEqual({row["run_id"] for row in perf_rows}, {"r1", "r2"})
        combined = self.group(stats, "S2", "render")
        self.assertEqual(combined["n"], 4)
        self.assertEqual(combined["median"], 25.0)
        run_groups = [
            item for item in stats["groups"] if item["scope"] == "run" and item["scenario"] == "S2" and item["phase"] == "render"
        ]
        self.assertEqual(sorted((item["run_id"], item["n"]) for item in run_groups), [("r1", 2), ("r2", 2)])

    def test_directory_input_recursion(self):
        root = self.tmp / "logs"
        self.write_log(
            "one.log",
            PERF_LINE % perf(scenario="S1", phase="render", page=0, w=1, h=1, ms="5.000", extra="-") + "\n",
            folder=root / "sub",
        )
        self.write_log(
            "two.txt",
            PERF_LINE % perf(scenario="S2", phase="render", page=0, w=1, h=1, ms="6.000", extra="-") + "\n",
            folder=root / "sub" / "deep",
        )
        self.write_log(
            "plain.txt",
            "hello\n11-01 18:00:00.000  1  2 I Chatty  : uid=1000 expire 3 lines\n",
            folder=root,
        )
        self.write_log(
            "skip.md",
            PERF_LINE % perf(scenario="S9", phase="render", page=0, w=1, h=1, ms="7.000", extra="-") + "\n",
            folder=root / "sub" / "deep" / "nested",
        )
        rows, stats, _md = self.parse_paths([root], name="dir-input")
        self.assertEqual(len(rows), 2)
        self.assertEqual({row["run_id"] for row in rows}, {"one", "two"})
        self.assertNotIn("S9", {row["scenario"] for row in rows})
        self.assertEqual(stats["counts"]["parse_failures"], 0)
        self.assertEqual(stats["counts"]["files"], 3)


class TestCliSurface(ParseTestBase):
    def test_default_output_name_is_file_stem(self):
        log = self.write_log(
            "whatever.log",
            PERF_LINE % perf(scenario="S2", phase="render", page=0, w=1, h=1, ms="1.000", extra="-") + "\n",
        )
        code, _stdout, stderr = self.run_cli(["--out", self.out, "--quiet", log])
        self.assertEqual(code, 0, stderr)
        for suffix in (".csv", ".stats.json", ".md"):
            self.assertTrue((self.out / ("whatever" + suffix)).is_file(), suffix)

    def test_app_override(self):
        log = self.write_log(
            "mystery.log",
            PERF_LINE % perf(scenario="S2", phase="render", page=0, w=1, h=1, ms="1.000", extra="-") + "\n",
        )
        rows, _stats, _md = self.parse_paths([log], extra=["--app", "forced-app"])
        self.assertEqual(rows[0]["app"], "forced-app")

        rows_auto, _stats_auto, _md_auto = self.parse_paths([log], name="auto-app")
        self.assertEqual(rows_auto[0]["app"], "unknown")

        p0 = self.write_log(
            "p0-naming.log",
            PERF_LINE % perf(scenario="S1", phase="render", page=0, w=1, h=1, ms="1.000", extra="-") + "\n",
        )
        rows_p0, _s, _m = self.parse_paths([p0], name="p0-app")
        self.assertEqual(rows_p0[0]["app"], "particlebook")

    def test_quiet_suppresses_stdout(self):
        log = self.write_log(
            "q.log",
            PERF_LINE % perf(scenario="S2", phase="render", page=0, w=1, h=1, ms="1.000", extra="-") + "\n",
        )
        code, stdout, _stderr = self.run_cli(["--out", self.out, "--quiet", "--name", "quiet-run", log])
        self.assertEqual(code, 0)
        self.assertEqual(stdout.strip(), "")
        code_loud, stdout_loud, _stderr_loud = self.run_cli(["--out", self.out, "--name", "loud-run", log])
        self.assertEqual(code_loud, 0)
        self.assertTrue(stdout_loud.strip())

    def test_strict_exit_code(self):
        log = self.write_log(
            "render-bad.log",
            "\n".join(
                [
                    PERF_LINE % perf(scenario="S2", phase="render", page=0, w=1, h=1, ms="1.000", extra="-"),
                    "PBPERF|scenario=S2|phase=render|bogus",
                ]
            )
            + "\n",
        )
        code, _stdout, stderr = self.run_cli(["--out", self.out, "--name", "bad", "--quiet", log])
        self.assertEqual(code, 0, stderr)
        stats = self.stats("bad")
        self.assertEqual(stats["counts"]["parse_failures"], 1)
        self.assertEqual(len(self.rows("bad")), 1)
        self.assertEqual(len(stats["parse_failures"]), 1)
        self.assertEqual(stats["parse_failures"][0]["line"], 2)
        self.assertIn("## 8.", self.md("bad"))

        code_strict, _stdout2, _stderr2 = self.run_cli(
            ["--out", self.out, "--name", "bad-strict", "--quiet", "--strict", log]
        )
        self.assertEqual(code_strict, 2)

    def test_usage_errors_exit_two(self):
        code, _stdout, _stderr = self.run_cli(["--min-samples", "0", "--out", self.out, "missing.log"])
        self.assertEqual(code, 2)

    def test_help_exits_zero(self):
        buffer = io.StringIO()
        with redirect_stdout(buffer), self.assertRaises(SystemExit) as raised:
            parse.main(["--help"])
        self.assertEqual(raised.exception.code, 0)
        self.assertIn("usage:", buffer.getvalue())


class TestGoldFixtureBundle(ParseTestBase):
    def test_golden_bundle(self):
        self.assertTrue(FIXTURES_DIR.is_dir(), FIXTURES_DIR)
        code, _stdout, stderr = self.run_cli(
            ["--out", self.out, "--name", "fixtures", "--quiet", FIXTURES_DIR]
        )
        self.assertEqual(code, 0, stderr)
        rows = self.rows("fixtures")
        stats = self.stats("fixtures")
        md = self.md("fixtures")
        self.assertEqual(len(rows), 34)
        self.assertEqual(stats["counts"]["files"], 3)
        self.assertEqual(stats["counts"]["parse_failures"], 0)
        self.assertEqual(stats["counts"]["records"], 34)
        self.assertEqual(
            {row["run_id"] for row in rows},
            {
                "render-run1-20261101-190000",
                "tiles-run1-20261101-190500",
                "p0-run1-20261101-191000",
            },
        )
        self.assertEqual({row["app"] for row in rows}, {"spike2", "spike2t", "particlebook"})
        pdf_pages = [row for row in rows if row["scenario"] == "pdf_page"]
        self.assertEqual(len(pdf_pages), 7)
        self.assertEqual({row["phase"] for row in pdf_pages}, {"render", "encode"})
        self.assertEqual(len([row for row in rows if row["scenario"] == "pdf_open"]), 1)
        self.assertEqual(len([row for row in rows if row["warmup"] == "true"]), 1)
        amstart = [row for row in rows if row["record"] == "AMSTART"]
        self.assertEqual(len(amstart), 1)
        self.assertEqual(amstart[0]["ms"], "4287")
        self.assertEqual(amstart[0]["extra"], "WaitTime:4300")
        self.assertEqual(len([row for row in rows if row["record"] == "PBTIME"]), 2)
        # PBDONE|scenario=ALL 视为覆盖 tiles run 全部 scenario
        self.assertNotIn(
            {"run_id": "tiles-run1-20261101-190500", "app": "spike2t", "scenario": "T1"},
            stats["missing_pbdone"],
        )
        self.assertIn(
            {"run_id": "render-run1-20261101-190000", "app": "spike2", "scenario": "S1"},
            stats["missing_pbdone"],
        )
        self.assertIn("## 5.", md)
        self.assertIn("## 6.", md)
        self.assertIn("## 8.", md)
        self.assertEqual(self.csv_text("fixtures").splitlines()[0], CSV_HEADER)
        self.assertEqual(self.group(stats, "S2", "render")["n"], 6)
        self.assertEqual(len(stats["groups"]), 20)


if __name__ == "__main__":
    unittest.main()
