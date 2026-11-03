#!/usr/bin/env python3
"""Independent verifier for the Z-Library JS proof-of-work challenge (R7-G).

This does NOT import or call the Kotlin implementation. It parses the challenge
fixtures, derives n from the first character exactly like the page JS
(parseInt('0x' + c[0])), and brute-forces SHA-1(C + decimal_i) until
digest[n] == 0xB0 and digest[n+1] == 0x0B.

Usage:
    python scripts/android/zlib-challenge-verify.py
    python scripts/android/zlib-challenge-verify.py --json
    python scripts/android/zlib-challenge-verify.py --limit 5000000
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FIXTURE_DIR = ROOT / "android/app/src/test/resources/zlib"
FIXTURES = ["challenge.html", "challenge-rotated.html"]
EXPECTED = {
    "challenge.html": ("549E967E36C0ACE8E4A01AE0655AA2BE116C727B", 348329),
    "challenge-rotated.html": ("690F18407F22620E29113A816269C2F73D3E5766", 79746),
}
CONST_RE = re.compile(r"'([0-9A-Fa-f]{40})'")
CTX_RE = re.compile(r"\['([0-9A-Fa-f]{40})','c_token='")
TARGET = 0xB0
TARGET_NEXT = 0x0B


def sha1_bytes(text: str) -> bytes:
    return hashlib.sha1(text.encode("ascii")).digest()


def parse_js_hex_char(ch: str):
    """Equivalent of parseInt('0x' + ch, 16); None for NaN/empty."""
    if len(ch) != 1 or ch not in "0123456789abcdefABCDEF":
        return None
    return int(ch, 16)


def extract_first_quoted(html: str):
    """Same selection as ZlibChallenge.CONSTANT_RE.find(html): first match."""
    m = CONST_RE.search(html)
    return m.group(1) if m else None


def extract_js_context(html: str):
    """Independent stronger selection: the 40-hex string followed by 'c_token='."""
    m = CTX_RE.search(html)
    if m:
        return m.group(1)
    return extract_first_quoted(html)


def find_nonce(constant: str, limit: int = 5_000_000):
    """Return (nonce, digest) for the smallest i satisfying the page condition."""
    index = parse_js_hex_char(constant[0] if constant else "")
    if index is None:
        return None
    prefix = constant.encode("ascii")
    for i in range(limit):
        digest = hashlib.sha1(prefix + str(i).encode("ascii")).digest()
        if digest[index] == TARGET and digest[index + 1] == TARGET_NEXT:
            return i, digest
    return None


def check_fixtures(limit: int):
    rows = []
    ok = True
    for name in FIXTURES:
        path = FIXTURE_DIR / name
        html = path.read_text(encoding="utf-8")
        first = extract_first_quoted(html)
        contextual = extract_js_context(html)
        expected_constant, expected_nonce = EXPECTED[name]
        constant = contextual or first
        index = parse_js_hex_char(constant[0]) if constant else None
        hit = find_nonce(constant, limit) if constant else None
        nonce, digest = hit if hit else (None, None)
        row = {
            "fixture": name,
            "bytes": len(html.encode("utf-8")),
            "constant": constant,
            "first_quoted": first,
            "contextual": contextual,
            "index": index,
            "nonce": nonce,
            "expected_constant": expected_constant,
            "expected_nonce": expected_nonce,
            "token_len": len(constant) + len(str(nonce)) if constant and nonce is not None else None,
            "digest_hex": digest.hex() if digest else None,
            "digest_byte_n": digest[index] if digest and index is not None else None,
            "digest_byte_n1": digest[index + 1] if digest and index is not None else None,
            "pass": (
                constant == expected_constant
                and nonce == expected_nonce
                and index is not None
                and digest is not None
                and digest[index] == TARGET
                and digest[index + 1] == TARGET_NEXT
            ),
        }
        ok = ok and row["pass"]
        rows.append(row)
    return ok, rows


def check_index_mapping():
    cases = [
        ("0", 0), ("5", 5), ("6", 6), ("9", 9),
        ("A", 10), ("a", 10), ("F", 15), ("f", 15),
        ("G", None), ("-", None), ("", None),
    ]
    rows = []
    for ch, expected in cases:
        actual = parse_js_hex_char(ch)
        rows.append({"char": ch, "actual": actual, "expected": expected, "pass": actual == expected})
    return all(r["pass"] for r in rows), rows


def check_bounds():
    # A valid hex first character is 0..15, so n+1 is 1..16; SHA-1 has 20 bytes.
    # n=19 is unreachable from parseInt('0x' + one hex digit), so no digest[20] access.
    max_index = max(parse_js_hex_char(ch) for ch in "0123456789abcdef")
    return {
        "max_index": max_index,
        "max_index_plus_one": max_index + 1,
        "digest_length": 20,
        "n19_reachable": False,
        "safe": (max_index + 1) < 20,
    }


def check_lengths():
    rows = []
    for n in (39, 40, 41):
        html = "<script>const a0_0x2a54=['" + ("A" * n) + "','c_token=','array']</script>"
        m = CONST_RE.search(html)
        rows.append({"hex_chars": n, "regex_matches": bool(m), "pass": (n == 40) == bool(m)})
    return all(r["pass"] for r in rows), rows


def check_multiple_candidates():
    decoy = "A" * 40
    real = EXPECTED["challenge.html"][0]
    html = (
        "<script>const decoy='" + decoy + "';</script>"
        "<script>const a0_0x2a54=['" + real + "','c_token=','array'];</script>"
    )
    first = extract_first_quoted(html)
    contextual = extract_js_context(html)
    return {
        "first_quoted": first,
        "contextual": contextual,
        "first_is_decoy": first == decoy,
        "contextual_is_real": contextual == real,
        "pass": contextual == real,
    }


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--limit", type=int, default=5_000_000)
    args = ap.parse_args(argv)

    fixtures_ok, fixtures = check_fixtures(args.limit)
    index_ok, index_rows = check_index_mapping()
    bounds = check_bounds()
    lengths_ok, length_rows = check_lengths()
    multiple = check_multiple_candidates()

    result = {
        "fixtures": fixtures,
        "fixtures_ok": fixtures_ok,
        "index_mapping": index_rows,
        "index_mapping_ok": index_ok,
        "bounds": bounds,
        "lengths": length_rows,
        "lengths_ok": lengths_ok,
        "multiple_candidates": multiple,
        "all_ok": bool(fixtures_ok and index_ok and bounds["safe"] and lengths_ok and multiple["pass"]),
    }

    if args.json:
        print(json.dumps(result, indent=2, ensure_ascii=False))
    else:
        print("== fixtures ==")
        for r in fixtures:
            print(
                "{fixture}: bytes={bytes} constant={constant} index={index} nonce={nonce} "
                "expected={expected_nonce} token_len={token_len} digest={digest_hex} "
                "digest[n]={digest_byte_n:#04x} digest[n+1]={digest_byte_n1:#04x} "
                "[{status}]".format(
                    **r, status="PASS" if r["pass"] else "FAIL"
                )
            )
        print("== index mapping (parseInt('0x' + c[0])) ==")
        for r in index_rows:
            print("  c[0]={char!r} -> {actual} (expected {expected}) [{status}]".format(
                **r, status="PASS" if r["pass"] else "FAIL"
            ))
        print("== bounds ==")
        print("  {bounds}".format(bounds=bounds))
        print("== length regex ==")
        for r in length_rows:
            print("  quote + {hex_chars} hex chars: regex_match={regex_matches} [{status}]".format(
                **r, status="PASS" if r["pass"] else "FAIL"
            ))
        print("== multiple 40-hex candidates ==")
        print("  first_quoted={first_quoted}".format(**multiple))
        print("  contextual={contextual}".format(**multiple))
        print("  first_is_decoy={first_is_decoy} contextual_is_real={contextual_is_real}".format(**multiple))
        print("RESULT:", "PASS" if result["all_ok"] else "FAIL")

    return 0 if result["all_ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
