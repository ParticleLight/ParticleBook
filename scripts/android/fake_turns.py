"""Offline fake device for the staged epub-turn round (draft test support).

One JSON document is the whole device: reader page, measured process id, the
PBPERF lines the app would log, and the framestats rows it would render. The
same module backs the in-process runner and the cross-process driver, so a
segment boundary is exercised the way it is on a device: a new OS process with
no in-memory carry-over.

Nothing here is a measurement. It only proves that the collector keeps one
round continuous across host calls, never repeats an input, and fails closed
when the retained evidence stops being trustworthy.

Usage:
    python fake_turns.py adb <root> <adb tail...>   # one fake adb command
    python fake_turns.py <stage> <root>             # init/step/finish in a new process
"""
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import perf_epub as pe

TOTAL_PAGES = 400
BASE_NS = 1_000_000_000
STEP_NS = 1_000_000_000
FRAME_MS = 12.0
TICK_NS = 250_000_000
COMMAND_TICKS = 8  # one fake adb command costs 2 s of segment budget
FRAME_RING = 120  # gfxinfo keeps a finite frame ring, exactly like the device
SERIAL = pe.PRIMARY_SERIAL
TITLE = "QA Moby Dick (p1/books)"
MOBY_BYTES = 1165503
NEXT_ARROW_X = "993"


# -- device state ----------------------------------------------------------
def new_state(apk_sha, pid=5000):
    return {"page": 1, "pid": pid, "taps": 0, "entries": 0, "events": [], "frames": [],
            "apk_sha": apk_sha, "commands": [], "serial": SERIAL}


def load_state(path):
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def save_state(path, state):
    temp = path + ".tmp"
    with open(temp, "w", encoding="utf-8") as handle:
        json.dump(state, handle, sort_keys=True)
    os.replace(temp, path)


def event_line(turn, page, pid):
    start_ns = BASE_NS + turn * STEP_NS
    end_ns = start_ns + int(FRAME_MS * 1_000_000)
    draw_ns = start_ns + (end_ns - start_ns) // 2
    extra = ",".join([
        "book_id:%d" % pe.QA_BOOK_ID, "chapter:59", "page:%d" % page, "text_only:1",
        "start_ns:%d" % start_ns, "draw_end_ns:%d" % draw_ns, "end_ns:%d" % end_ns,
        "end_kind:commit", "turn:%d" % turn])
    body = "PBPERF|phase=%s|ms=%s|extra=%s" % (pe.PAGE_PHASE, FRAME_MS, extra)
    return "11-20 20:00:%02d.000 %d %d I PBPERF : %s" % (turn % 60, pid, pid, body)


def first_line(entry, pid):
    """The chapter-open sample the app logs once per fresh process."""
    start_ns = BASE_NS // 2 + entry * STEP_NS
    end_ns = start_ns + 400 * 1_000_000
    draw_ns = start_ns + (end_ns - start_ns) // 2
    extra = ",".join([
        "book_id:%d" % pe.QA_BOOK_ID, "chapter:59", "page:0", "text_only:1",
        "start_ns:%d" % start_ns, "draw_end_ns:%d" % draw_ns, "end_ns:%d" % end_ns,
        "end_kind:commit"])
    body = "PBPERF|phase=%s|ms=400|extra=%s" % (pe.FIRST_PHASE, extra)
    return "11-20 19:59:%02d.000 %d %d I PBPERF : %s" % (entry % 60, pid, pid, body)


def frame_row(state, turn):
    start_ns = BASE_NS + turn * STEP_NS
    end_ns = start_ns + int(FRAME_MS * 1_000_000)
    draw_ns = start_ns + (end_ns - start_ns) // 2
    return {"flags": 0, "intended_vsync_ns": start_ns, "draw_start_ns": draw_ns - 1,
            "sync_queued_ns": draw_ns + 1, "frame_completed_ns": end_ns,
            "window": pe.PKG + "/MobileActivity", "pid": state["pid"]}


def frame_text(rows, pid=None):
    header = "Flags,IntendedVsync,DrawStart,SyncQueued,FrameCompleted,"
    body = "\n".join("%d,%d,%d,%d,%d," % (row["flags"], row["intended_vsync_ns"],
                                          row["draw_start_ns"], row["sync_queued_ns"],
                                          row["frame_completed_ns"]) for row in rows)
    return ("Graphics info for pid %d [%s]\nWindow: %s\n---PROFILEDATA---\n%s\n%s\n---PROFILEDATA---"
            % (pid or (rows[0]["pid"] if rows else 0), pe.PKG,
               rows[0]["window"] if rows else pe.PKG + "/MobileActivity", header, body))


def gfx_summary(count, janky=0):
    return ("Total frames rendered: %d\nJanky frames (legacy): %d (%.2f%%)\n"
            "95th percentile: 16ms\n"
            % (count, janky, 100.0 * janky / count if count else 0.0))


def ui_dump_text(page, total=None):
    return ("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n"
            "<hierarchy rotation=\"0\">\n"
            "  <node index=\"0\" text=\"\" class=\"android.widget.FrameLayout\" "
            "bounds=\"[0,0][1440,2960]\">\n"
            "    <node index=\"0\" text=\"\u00b7 %d / %d\" class=\"android.widget.TextView\" "
            "bounds=\"[600,2800][900,2900]\" />\n"
            "  </node>\n"
            "</hierarchy>\n" % (page, total or TOTAL_PAGES))


def tiny_png():
    def chunk(kind, payload):
        crc = pe.zlib.crc32(kind + payload) & 0xffffffff
        return len(payload).to_bytes(4, "big") + kind + payload + crc.to_bytes(4, "big")
    header = (1).to_bytes(4, "big") * 2 + bytes([8, 6, 0, 0, 0])
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
            + chunk(b"IDAT", pe.zlib.compress(b"\0\0\0\0\xff")) + chunk(b"IEND", b""))


# -- one fake adb command --------------------------------------------------
def answer(state, tail):
    """Return the fake device's reply; mutating commands update the state."""
    key = tuple(str(item) for item in tail)
    state["commands"].append(list(key))
    if key == ("get-serialno",):
        return SERIAL + "\n"
    if key == ("exec-out", "screencap", "-p"):
        return tiny_png()
    if key and key[0] == "shell":
        key = key[1:]
    if key == ("pidof", pe.PKG):
        return ("%d\n" % state["pid"]) if state["pid"] else ""
    if key and key[0] == "getprop":
        return {"ro.product.model": "Pixel 3 XL", "ro.build.version.sdk": "33",
                "ro.build.version.release": "13", "ro.product.cpu.abi": "arm64-v8a",
                "ro.build.fingerprint":
                    "google/crosshatch/crosshatch:13/TQ3A/1:user/release-keys"
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
        return "%s  /data/app/base.apk\n" % state["apk_sha"]
    if key == ("dumpsys", "activity", "activities"):
        return state.get("foreground") or (
            "  mResumedActivity: ActivityRecord{x app.particlebook/.android.MobileActivity}\n")
    if key == ("logcat", "-v", "threadtime", "-d", "-s", "PBPERF:I", "*:S"):
        return "\n".join(state["events"]) + "\n"
    if key == ("dumpsys", "gfxinfo", pe.PKG, "framestats"):
        # The real dump repeats the gfxinfo summary header before the table: the
        # total counts every frame since the reset, while only the newest rows
        # are still inside the finite ring.
        return (gfx_summary(len(state["frames"]))
                + frame_text(state["frames"][-FRAME_RING:], state.get("frame_pid")))
    if key == ("dumpsys", "gfxinfo", pe.PKG):
        return gfx_summary(len(state["frames"]))
    if key == ("dumpsys", "gfxinfo", pe.PKG, "reset"):
        state["frames"] = []
        return gfx_summary(0)
    if key[:2] == ("exec-out", "uiautomator"):
        return ui_dump_text(state["page"], state.get("page_total"))
    if key == ("am", "force-stop", pe.PKG):
        return ""
    if key[:2] == ("am", "start"):
        state["entries"] = int(state.get("entries") or 0) + 1
        # A fresh process means a fresh in-app turn tracker, exactly like the
        # production comment on reopening after preparation.
        state["taps"] = 0
        state["events"].append(first_line(state["entries"], state["pid"]))
        return "Starting: Intent\n"
    if key == ("input", "keyevent", "4"):
        return ""
    if key == ("input", "tap", NEXT_ARROW_X, "2782"):
        state["page"] += 1
        state["taps"] += 1
        state["events"].append(event_line(state["taps"], state["page"] - 1, state["pid"]))
        state["frames"].append(frame_row(state, state["taps"]))
        return ""
    if key[:2] == ("input", "tap"):
        return ""
    raise AssertionError("unexpected fake adb command: %r" % (key,))


# -- runners ---------------------------------------------------------------
class InProcessRunner:
    """Callable runner that answers from one in-memory device state."""

    def __init__(self, root, state, fail_on=None, on_call=None):
        self.root = root
        self.state = state
        self.fail_on = fail_on
        self.on_call = on_call

    def __call__(self, argv, capture_output=True, timeout=None):
        advance(self.root)
        _MEMORY["state"] = self.state
        tail = list(argv)[3:]
        if tail and tail[0] == "shell":
            tail = tail[1:]
        hit = False
        if self.fail_on is not None:
            if callable(self.fail_on):
                hit = bool(self.fail_on(tail))
            else:
                hit = tuple(tail[:len(self.fail_on)]) == tuple(self.fail_on)
        if hit:
            raise subprocess.TimeoutExpired(argv, timeout or 0)
        if self.on_call is not None:
            # Test hook: let a control mutate the device right before it answers.
            self.on_call(tail)
        out = answer(self.state, tail)
        if self.root:
            save_state(os.path.join(self.root, "fake_state.json"), self.state)
        if isinstance(out, bytes):
            return subprocess.CompletedProcess(argv, 0, out, b"")
        return subprocess.CompletedProcess(argv, 0, out.encode("utf-8"), b"")


def subprocess_runner(root):
    """Callable runner that spawns one OS process per adb command."""
    fail_path = os.path.join(root, "fake_fail.txt")

    def run(argv, capture_output=True, timeout=None):
        advance(root)
        tail = list(argv)[3:]
        if tail and tail[0] == "shell":
            tail = tail[1:]
        if os.path.isfile(fail_path):
            with open(fail_path, "r", encoding="utf-8") as handle:
                prefix = tuple(handle.read().split())
            if tuple(tail[:len(prefix)]) == prefix:
                raise subprocess.TimeoutExpired(argv, timeout or 0)
        done = subprocess.run([sys.executable, os.path.join(HERE, "fake_turns.py"),
                               "adb", root] + [str(item) for item in tail],
                              capture_output=True, timeout=timeout or 120)
        return subprocess.CompletedProcess(argv, done.returncode, done.stdout, done.stderr)
    return run


def now(root):
    """Read-only segment clock: only dispatched commands consume the budget."""
    path = os.path.join(root, "clock.txt")
    if not os.path.isfile(path):
        return 0
    with open(path, "r", encoding="utf-8") as handle:
        return int(handle.read().strip() or "0") * TICK_NS


def advance(root, ticks=COMMAND_TICKS):
    """Charge one fake adb command to the segment budget."""
    path = os.path.join(root, "clock.txt")
    count = 0
    if os.path.isfile(path):
        with open(path, "r", encoding="utf-8") as handle:
            count = int(handle.read().strip() or "0")
    count += ticks
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(str(count))
    return count * TICK_NS


def tick(root):
    return now(root)


# -- patched device-side flows ---------------------------------------------
def fake_prepare(device, book, toc_match, target_page, v):
    device.sh("input", "keyevent", "4")
    reset_page()
    return {"ok": True, "detail": "PREPARED", "reader_exited": True}


def reset_page():
    """Preparation rewinds the reader to chapter page 1 (set_page in production)."""
    if _MEMORY.get("state") is not None:
        _MEMORY["state"]["page"] = 1
    root = _MEMORY.get("root")
    if root:
        path = os.path.join(root, "fake_state.json")
        state = load_state(path)
        state["page"] = 1
        save_state(path, state)


def fake_entry(device, book, toc_match, expected_page, reset_gfx=True):
    # Same cursor semantics as the real entry: only lines emitted after the fresh
    # process starts belong to this entry, never an earlier round's buffer.
    before = device.perf_lines()
    device.sh("am", "force-stop", pe.PKG)
    device.sh("am", "start", "-W", "-n", pe.ACT)
    after = device.perf_lines()
    lines = pe.select_new_lines(before, after)
    events = [item for item in (pe.parse_perf_line(line) for line in lines) if item]
    return {"ok": True, "detail": "FIRST_ENTRY", "pid": device.app_pid(),
            "process_cold": True, "events": events,
            "frames": pe.parse_framestats(device.framestats_text()),
            "gfx_text": device.gfxinfo_report(), "raw_logcat": lines,
            "raw_logcat_before": before, "raw_logcat_after": after,
            "collection_failures": []}


# -- run inputs ------------------------------------------------------------
def write(path, data):
    with open(path, "wb") as handle:
        handle.write(data)
    return path


def write_json(path, value):
    return write(path, json.dumps(value, sort_keys=True).encode("utf-8"))


def prepare_inputs(root):
    """Write the apk/epub/provenance fixtures the staged run binds at init."""
    apk = write(os.path.join(root, "app-release-signed.apk"), b"release-apk-bytes")
    apk_sha = pe.sha256_file(apk)
    epub = write(os.path.join(root, "qa-moby.epub"), b"\0" * MOBY_BYTES)
    epub_sha = pe.sha256_file(epub)
    source = {"git_head": "a" * 40,
              "source_tree_algorithm": pe.SOURCE_TREE_ALGORITHM,
              "source_tree_sha256": "b" * 64, "source_files": 397}
    write_json(os.path.join(root, "release.json"), {
        "build_type": "release", "debuggable": False, "apksigner_verified": True,
        "apk_sha256": apk_sha, "apk_bytes": os.path.getsize(apk),
        "signer_sha256": "c" * 64, "git_head": source["git_head"],
        "source_tree_algorithm": source["source_tree_algorithm"],
        "source_tree_sha256": source["source_tree_sha256"],
        "source_files": source["source_files"]})
    record = {"id": pe.QA_BOOK_ID, "title": TITLE, "filePath": pe.QA_BOOK_PATH,
              "fileSize": MOBY_BYTES}
    write_json(os.path.join(root, "books.json"), {"books": [record]})
    write(os.path.join(root, "device_sha256.txt"), epub_sha.encode())
    write(os.path.join(root, "device_size.txt"), str(MOBY_BYTES).encode())
    write(os.path.join(root, "installed_apk.txt"),
          ("%s  /data/app/base.apk" % apk_sha).encode())
    write_json(os.path.join(root, "binding_commands.json"),
               [{"argv": ["exec-out", "run-as", pe.PKG, "cat", pe.QA_BOOK_PATH],
                 "returncode": 0}])
    write(os.path.join(root, "install-r.log"), b"Performing Streamed Install\nSuccess\n")
    fixture = write_json(os.path.join(root, "fixture.json"), {
        "device_serial": SERIAL, "package": pe.PKG, "checked_via": "adb-exec-out-run-as",
        "checked_apk_sha256": apk_sha, "qa201_book_record_live": record,
        "qa201_device_file": {"path": pe.QA_BOOK_PATH, "sha256": epub_sha,
                              "size_bytes": MOBY_BYTES},
        "installed_apk": {"sha256": apk_sha, "package": pe.PKG},
        "raw_evidence": {"binding_commands": "binding_commands.json",
                         "device_sha256": "device_sha256.txt",
                         "device_size": "device_size.txt", "books": "books.json",
                         "installed_apk": "installed_apk.txt"},
        "retained_install": {"method": "adb-install-r", "data_cleared": False,
                             "exit_code": 0, "stdout": "Success",
                             "from_apk_sha256": apk_sha, "to_apk_sha256": apk_sha,
                             "log_path": "install-r.log"}})
    inputs = {"apk": apk, "apk_sha256": apk_sha, "epub": epub, "epub_sha256": epub_sha,
              "release": os.path.join(root, "release.json"),
              "fixture": fixture, "source": source, "serial": SERIAL,
              "run_dir": os.path.join(root, "run")}
    write_json(os.path.join(root, "inputs.json"), inputs)
    return inputs


def run_args(inputs, **kw):
    base = {"run_dir": inputs["run_dir"], "apk": inputs["apk"],
            "release_evidence": inputs["release"], "fixture_evidence": inputs["fixture"],
            "epub": inputs["epub"], "book_title": TITLE, "book_id": pe.QA_BOOK_ID,
            "chapter": 59, "page": 1, "cold": pe.MIN_COLD_SAMPLES,
            "turns": pe.MIN_TURNS, "rounds": pe.MIN_ROUNDS, "turn_mode": "forward",
            "toc_match": "LIV: The Town-Ho", "serial": inputs["serial"],
            "adb": os.path.join(os.path.dirname(inputs["run_dir"]), "adb")}
    base.update(kw)
    return base


_MEMORY = {"state": None, "root": None}


def patch_runtime(inputs, state=None, root=None):
    """Point the collector at the fake provenance and the fake device flows."""
    from unittest.mock import patch
    _MEMORY["state"] = state
    _MEMORY["root"] = root
    patchers = [patch.object(pe, "MOBY_SHA256", inputs["epub_sha256"]),
                patch.object(pe, "source_tree_info", lambda: dict(inputs["source"])),
                patch.object(pe, "prepare_progress", fake_prepare),
                patch.object(pe, "run_first_entry", fake_entry)]
    for patcher in patchers:
        patcher.start()
    return patchers


# -- cross-process entry point ---------------------------------------------
def run_stage_in_process(stage, root, runner=None):
    with open(os.path.join(root, "inputs.json"), "r", encoding="utf-8") as handle:
        inputs = json.load(handle)
    patchers = patch_runtime(inputs, root=root)
    try:
        return pe.run_stage("epub-turn", stage, run_args(inputs), runner=runner,
                            source=dict(inputs["source"]), clock=lambda: now(root),
                            sleep=lambda seconds: None)
    finally:
        for patcher in patchers:
            patcher.stop()


def main(argv):
    if len(argv) >= 3 and argv[1] == "adb":
        root = argv[2]
        state_path = os.path.join(root, "fake_state.json")
        state = load_state(state_path)
        out = answer(state, argv[3:])
        save_state(state_path, state)
        sys.stdout.buffer.write(out if isinstance(out, bytes) else out.encode("utf-8"))
        return 0
    if len(argv) == 3:
        root = argv[2]
        out, code = run_stage_in_process(argv[1], root, runner=subprocess_runner(root))
        sys.stdout.write(json.dumps({"step_pid": os.getpid(), "code": code,
                                     "out": out}, sort_keys=True) + "\n")
        return code if code in (0, 1, 2, 3) else 1
    sys.stderr.write(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
