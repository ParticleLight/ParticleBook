#!/usr/bin/env python3
"""P1-6 legacy data fixture generator and migration-plan oracle.

Reads no device data. It reconstructs the four seed books from
android/app/src/main/assets/fixtures.json and adds explicit migration edge cases.
The expected plan is a pure-Python reimplementation of
android/.../data/LegacyJson.kt + Migration.kt. Production Kotlin remains the source
of truth; this script is a reproducible fixture/oracle, not a second implementation.

Usage:
  python scripts/android/p1-realdata.py
  python scripts/android/p1-realdata.py --out docs/android-port/p1/real-data
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import zipfile
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "android/app/src/main/assets/fixtures.json"
OUT_DEFAULT = ROOT / "docs/android-port/p1/real-data"
NOW_MS = int(datetime(2026, 11, 5, 18, 30, tzinfo=timezone(timedelta(hours=8))).timestamp() * 1000)
SHA_A = hashlib.sha256(b"particlebook-p1-fixture-a").hexdigest()
SHA_B = hashlib.sha256(b"particlebook-p1-fixture-b").hexdigest()
SHA_C = hashlib.sha256(b"particlebook-p1-fixture-c").hexdigest()
SHA_SAF = hashlib.sha256(b"content://com.android.providers.downloads.documents/document/42").hexdigest()
SEED_DIR = "/data/user/0/app.particlebook/files/_pb_files/"
DOWNLOAD_DIR = "/storage/emulated/0/Android/data/app.particlebook/files/Download/Z-Library/"

# The four device seed books are taken from the checked-in fixture asset. They are
# not a live device dump; app.particlebook is a release-signed, non-debuggable build.
SEED_BOOKS_SNAKE = [
    {
        "id": 1, "title": "故乡的河", "author": "沈从文", "format": "epub",
        "file_path": SEED_DIR + "seed-01.epub", "file_size": 94478,
        "added_at": "2026-11-01T08:27:40.880Z", "tags": [],
    },
    {
        "id": 2, "title": "统计之美", "author": None, "format": "pdf",
        "file_path": SEED_DIR + "seed-03.pdf", "file_size": 152591,
        "added_at": "2026-11-01T08:27:40.880Z", "tags": [],
    },
    {
        "id": 3, "title": "统计之美（第二册）", "author": "李航", "format": "pdf",
        "file_path": SEED_DIR + "seed-03.pdf", "file_size": 152591,
        "added_at": "2026-11-02T09:12:05.120Z", "tags": ["demo-fixture"],
    },
    {
        "id": 4, "title": "故乡的河（下）", "author": "沈从文", "format": "epub",
        "file_path": SEED_DIR + "seed-01.epub", "file_size": 94478,
        "added_at": "2026-11-03T11:03:40.000Z", "tags": ["demo-fixture"],
    },
]


def camel(book: dict[str, Any]) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for key, value in book.items():
        out[{"file_path": "filePath", "file_size": "fileSize", "added_at": "addedAt"}.get(key, key)] = value
    return out


# Mixed fixture: first four seed rows + ten explicit edge cases. IDs 5..13 and the
# duplicate id=8 are synthetic; zlib ids and titles are fixture values, not account data.
MIXED_BOOKS = [
    *[camel(b) for b in SEED_BOOKS_SNAKE],
    {
        "id": 5, "title": "Android编程权威指南（中文版）", "author": "Bill Phillips",
        "format": "PDF", "filePath": DOWNLOAD_DIR + "Android编程权威指南（中文版）.pdf",
        "fileSize": 10441971, "addedAt": "2026-11-03T23:20:00Z",
        "tags": ["zlib-download", "zlib:9000001"],
    },
    {
        "id": 6, "title": "Thinking, Fast and Slow", "author": "Daniel Kahneman",
        "format": "EPUB", "filePath": DOWNLOAD_DIR + "thinking-fast-and-slow.epub",
        "fileSize": 0, "addedAt": "2026-10-20T08:00:00Z",
        "tags": ["zlib:1234567", "zlib-download"],
    },
    {
        "id": 7, "title": "Thinking Fast and Slow", "author": "Daniel Kahneman",
        "format": "epub", "filePath": SEED_DIR + "thinking-fast-and-slow.epub",
        "fileSize": 5000000, "addedAt": "2026-11-02T10:00:00Z",
        "tags": ["zlib:1234567", " 收藏 "],
    },
    {
        "id": 8, "title": "三体", "author": "刘慈欣", "format": "epub",
        "filePath": DOWNLOAD_DIR + "三体.epub", "fileSize": 3200000,
        "addedAt": "2026-11-01T12:00:00Z", "tags": ["zlib:777"],
    },
    {
        "id": 8, "title": "三体（另一个版本）", "author": "刘慈欣", "format": "epub",
        "filePath": SEED_DIR + "three-body-alt.epub", "fileSize": 3100000,
        "addedAt": "2026-11-02T12:00:00Z", "tags": ["fp:3100000-" + SHA_B],
    },
    {
        "id": 9, "title": "文件已丢失", "author": None, "format": "epub",
        "filePath": "/storage/emulated/0/Download/not-there.epub", "fileSize": 1234,
        "addedAt": None, "tags": ["missing"],
    },
    {
        "id": 10, "title": "SAF 文档", "author": None, "format": "EPUB",
        "filePath": "content://com.android.providers.downloads.documents/document/42",
        "fileSize": 0, "addedAt": "2026-10-31T00:00:00Z",
        "tags": ["saf-uri:" + SHA_SAF],
    },
    {
        "id": 11, "title": "未知格式", "author": None, "format": "MOBI",
        "filePath": SEED_DIR + "unknown.mobi", "fileSize": 8888,
        "addedAt": "2026-10-30T00:00:00Z", "tags": [],
    },
    {
        "id": 12, "title": "哈希书", "author": "Fixture", "format": "PDF",
        "filePath": SEED_DIR + "hashed.pdf", "fileSize": 4096,
        "addedAt": "not-a-date", "tags": ["sha256:" + SHA_A, "zlib-download", "missing", " 随笔 ", "随笔"],
    },
    {
        "id": 13, "title": "空路径书", "author": None, "format": "txt",
        "filePath": "", "fileSize": 0, "addedAt": "2026-10-29T00:00:00Z", "tags": ["未分类"],
    },
    {
        "id": 0, "title": "非法 id=0，解析期应丢弃", "author": None, "format": "epub",
        "filePath": SEED_DIR + "invalid.epub", "fileSize": 1,
        "addedAt": None, "tags": [],
    },
]

EXISTS_PATHS = {
    SEED_DIR + "seed-01.epub",
    SEED_DIR + "seed-03.pdf",
    SEED_DIR + "thinking-fast-and-slow.epub",
    SEED_DIR + "three-body-alt.epub",
    SEED_DIR + "unknown.mobi",
    SEED_DIR + "hashed.pdf",
    DOWNLOAD_DIR + "Android编程权威指南（中文版）.pdf",
    DOWNLOAD_DIR + "三体.epub",
}

PROGRESS = {
    "page_1": 12, "pages_1": 230, "last_read_1": 1762000000000,
    "page_2": 0, "pages_2": 0,
    "page_3": 999, "pages_3": 100,
    "pages_4": 50,
    "page_5": 5,
    "page_6": -1, "pages_6": 0, "last_read_6": 1762000000001,
    "page_7": 2, "pages_7": 20, "last_read_7": 1762300000000,
    "page_8": 1, "pages_8": 10,
    "pages_10": 1,
    "page_11": 0, "pages_11": 10,
    "page_12": 100, "pages_12": 200, "last_read_12": 1762200000000,
    "page_13": 0, "pages_13": 0,
    "page_999": 3, "pages_999": 10, "last_read_999": 1762100000000,
    "page_abc": 5, "page_0": 1,
}

DOWNLOADS = [
    {
        "bookId": 9000001, "title": "Android编程权威指南（中文版）", "author": "Bill Phillips",
        "format": "PDF", "downloadPath": "/dl/v0/9000001",
        "fileName": "Android编程权威指南（中文版）.pdf",
        "filePath": DOWNLOAD_DIR + "Android编程权威指南（中文版）.pdf",
        "storage": "file", "size": 10441971, "total": 10441971,
        "status": "COMPLETED", "finishedAt": "2026-11-03T23:20:00Z",
    },
    {
        "bookId": 1234567, "title": "Thinking, Fast and Slow", "author": "Daniel Kahneman",
        "format": "epub", "downloadPath": "/dl/v0/1234567",
        "fileName": "thinking-fast-and-slow.epub",
        "filePath": SEED_DIR + "thinking-fast-and-slow.epub",
        "storage": "file", "size": 5000000, "total": 5000000,
        "status": "COMPLETED", "finishedAt": 1762190000000,
    },
    {
        "bookId": 1234567, "title": "Thinking, Fast and Slow (old)", "author": "Daniel Kahneman",
        "format": "epub", "downloadPath": "/dl/v0/1234567",
        "fileName": "thinking-fast-and-slow.epub",
        "filePath": SEED_DIR + "thinking-fast-and-slow.epub",
        "storage": "file", "size": 1024, "total": 1024,
        "status": "FAILED", "finishedAt": 1762100000000,
    },
    {
        "bookId": 999999, "title": "孤立下载", "author": None, "format": "epub",
        "downloadPath": "/dl/v0/999999", "fileName": "orphan.epub",
        "filePath": DOWNLOAD_DIR + "orphan.epub", "storage": "file",
        "size": 2048, "total": 2048, "status": "FAILED", "finishedAt": 1762195000000,
    },
    {
        "bookId": 777, "title": "三体", "author": "刘慈欣", "format": "epub",
        "downloadPath": "/dl/v0/777", "fileName": "三体.epub",
        "filePath": DOWNLOAD_DIR + "三体.epub", "storage": "file",
        "size": 3200000, "total": 3200000, "status": "COMPLETED", "finishedAt": 1762196000000,
    },
    {
        "bookId": None, "title": "手工导入", "author": None, "format": "PDF",
        "downloadPath": "/import/manual", "fileName": "manual.pdf",
        "filePath": DOWNLOAD_DIR + "manual.pdf", "storage": "file",
        "size": 4096, "total": 4096, "status": "COMPLETED",
        "finishedAt": "2026-11-04T09:00:00Z",
    },
    {
        "bookId": 9000001, "title": "Android编程权威指南（中文版）", "author": "Bill Phillips",
        "format": "pdf", "downloadPath": "/dl/v0/9000001",
        "fileName": "Android编程权威指南（中文版）.pdf",
        "filePath": "content://com.android.providers.downloads.documents/document/43",
        "storage": "saf", "size": 10441971, "total": 10441971,
        "status": "COMPLETED", "finishedAt": 1762197000000,
    },
]


def as_string(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, (str, int, float, bool)):
        return str(value)
    return ""


def as_long(value: Any) -> int | None:
    if isinstance(value, bool):
        return 1 if value else 0
    if isinstance(value, (int, float)):
        return int(value)
    if isinstance(value, str):
        try:
            return int(value.strip())
        except ValueError:
            return None
    return None


def parse_epoch_ms(value: Any) -> int:
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return int(value)
    if not isinstance(value, str):
        return 0
    text = value.strip()
    if not text:
        return 0
    try:
        return int(text)
    except ValueError:
        pass
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return int(parsed.timestamp() * 1000)
    except ValueError:
        return 0


def normalize_sha(value: str | None) -> str | None:
    text = (value or "").strip().lower()
    return text if len(text) == 64 and all(c in "0123456789abcdef" for c in text) else None


def tag_value(tags: list[str], prefix: str) -> str | None:
    for tag in tags:
        trimmed = tag.strip()
        if trimmed.lower().startswith(prefix.lower()):
            value = trimmed[len(prefix):].strip()
            if value:
                return value
    return None


def zlib_id(tags: list[str]) -> int | None:
    value = tag_value(tags, "zlib:")
    if value is None:
        return None
    try:
        parsed = int(value)
    except ValueError:
        return None
    return parsed if parsed > 0 else None


SYSTEM_EXACT = {"zlib-download", "recovered", "missing"}
SYSTEM_PREFIXES = ("zlib:", "sha256:", "saf-uri:", "fp:")


def is_system_tag(tag: str) -> bool:
    normalized = tag.strip().lower()
    return normalized in SYSTEM_EXACT or any(normalized.startswith(p) for p in SYSTEM_PREFIXES)


def user_tags(tags: list[str]) -> list[str]:
    result: list[str] = []
    for tag in tags:
        trimmed = tag.strip()
        if trimmed and not is_system_tag(trimmed) and trimmed not in result:
            result.append(trimmed)
    return result


def content_hash_from_tags(tags: list[str]) -> str | None:
    sha = normalize_sha(tag_value(tags, "sha256:"))
    if sha:
        return sha
    fp = tag_value(tags, "fp:")
    if not fp:
        return None
    return normalize_sha(fp.rsplit("-", 1)[-1])


def stable_key_for(legacy_id: int, tags: list[str], content_hash: str | None = None) -> str:
    zid = zlib_id(tags)
    if zid is not None:
        return f"zlib:{zid}"
    sha = normalize_sha(content_hash) or normalize_sha(tag_value(tags, "sha256:"))
    if sha:
        return f"sha256:{sha}"
    saf = tag_value(tags, "saf-uri:")
    if saf:
        return f"saf-uri:{saf}"
    fp = tag_value(tags, "fp:")
    if fp:
        return f"fp:{fp}"
    return f"legacy:{legacy_id}"


FORMATS = {"epub", "pdf", "txt", "html", "md", "fb2", "azw3", "docx", "cbz"}


def normalize_format(raw: str) -> str:
    lower = raw.strip().lower().removeprefix(".")
    if not lower:
        return "unknown"
    if lower in FORMATS:
        return lower
    if lower == "text/plain":
        return "txt"
    if lower in {"text/html", "htm", "xhtml", "application/xhtml+xml"}:
        return "html"
    if lower in {"text/markdown", "markdown"}:
        return "md"
    if lower == "application/pdf":
        return "pdf"
    if lower in {"application/epub+zip", "epub+zip"}:
        return "epub"
    if lower in {"application/x-mobipocket-ebook", "application/vnd.amazon.ebook"}:
        return "azw3"
    return lower


def raw_format(raw: str) -> str:
    return raw.strip().lower()


def file_name_from_path(path: str) -> str:
    trimmed = path.strip()
    if not trimmed:
        return ""
    without_query = trimmed.split("?", 1)[0].split("#", 1)[0]
    name = without_query.rsplit("/", 1)[-1]
    return name if name else without_query


def read_books(raw_json: dict[str, Any]) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for raw in raw_json.get("books", []):
        if not isinstance(raw, dict):
            continue
        bid = as_long(raw.get("id"))
        if bid is None or bid <= 0:
            continue
        tags = [t for t in raw.get("tags", []) if isinstance(t, str)] if isinstance(raw.get("tags"), list) else []
        out.append({
            "id": bid,
            "title": as_string(raw.get("title")),
            "author": as_string(raw.get("author")),
            "format": as_string(raw.get("format")) or "epub",
            "filePath": as_string(raw.get("filePath") if raw.get("filePath") is not None else raw.get("file_path")),
            "fileSize": as_long(raw.get("fileSize") if raw.get("fileSize") is not None else raw.get("file_size")) or 0,
            "addedAtRaw": as_string(raw.get("addedAt") if raw.get("addedAt") is not None else raw.get("added_at")),
            "tags": tags,
        })
    return out


def read_downloads(raw_json: dict[str, Any]) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for raw in raw_json.get("downloads", []):
        if not isinstance(raw, dict):
            continue
        title = as_string(raw.get("title"))
        dest = as_string(raw.get("filePath") if raw.get("filePath") is not None else raw.get("destPath") if raw.get("destPath") is not None else raw.get("file_path"))
        download_path = as_string(raw.get("downloadPath") if raw.get("downloadPath") is not None else raw.get("download_path"))
        if not title and not dest and not download_path:
            continue
        out.append({
            "bookId": as_long(raw.get("bookId") if raw.get("bookId") is not None else raw.get("book_id")),
            "title": title,
            "author": as_string(raw.get("author")),
            "format": as_string(raw.get("format")) or "epub",
            "downloadPath": download_path,
            "fileName": as_string(raw.get("fileName") if raw.get("fileName") is not None else raw.get("file_name")),
            "destPath": dest,
            "storage": as_string(raw.get("storage")) or "file",
            "size": as_long(raw.get("size")) or 0,
            "total": as_long(raw.get("total")) or 0,
            "status": as_string(raw.get("status")) or "completed",
            "errorKind": as_string(raw.get("errorKind") if raw.get("errorKind") is not None else raw.get("error_kind")) or None,
            "errorMessage": as_string(raw.get("errorMessage") if raw.get("errorMessage") is not None else raw.get("error_message")) or None,
            "finishedAt": parse_epoch_ms(raw.get("finishedAt") if raw.get("finishedAt") is not None else raw.get("finished_at")),
        })
    return out


def read_progress(prefs: dict[str, Any]) -> list[dict[str, Any]]:
    acc: dict[int, dict[str, Any]] = {}
    for key, value in prefs.items():
        if key.startswith("page_"):
            bid = as_long(key[5:])
            if bid is None or bid <= 0:
                continue
            page = as_long(value)
            if page is None:
                continue
            acc.setdefault(bid, {})["page"] = page
        elif key.startswith("pages_"):
            bid = as_long(key[6:])
            if bid is None or bid <= 0:
                continue
            total = as_long(value)
            if total is None:
                continue
            acc.setdefault(bid, {})["totalPages"] = total
        elif key.startswith("last_read_"):
            bid = as_long(key[10:])
            if bid is None or bid <= 0:
                continue
            last = as_long(value)
            if last is None:
                continue
            acc.setdefault(bid, {})["lastReadAt"] = last
    return [
        {"bookId": bid, "page": v.get("page"), "totalPages": v.get("totalPages"), "lastReadAt": v.get("lastReadAt")}
        for bid, v in acc.items()
    ]


def build_plan(books: list[dict[str, Any]], progress: list[dict[str, Any]], downloads: list[dict[str, Any]], exists: set[str] | None, now_ms: int = NOW_MS) -> dict[str, Any]:
    warnings: list[str] = []
    stats = {
        "inputBooks": 0, "uniqueBooks": 0, "duplicateBooks": 0, "userTags": 0,
        "bookTagLinks": 0, "progressRows": 0, "downloadRows": 0,
        "skippedProgress": 0, "skippedDownloads": 0,
    }
    next_synthetic = max((b["id"] for b in books if b["id"] > 0), default=0) + 1
    used_ids: set[int] = set()
    by_key: dict[str, dict[str, Any]] = {}
    id_remap: dict[int, int] = {}

    def safe_exists(path: str) -> bool:
        return bool(path) and (exists is None or path in exists)

    for legacy in books:
        stats["inputBooks"] += 1
        requested = legacy["id"]
        proposed = requested if requested > 0 else next_synthetic
        if requested <= 0:
            next_synthetic += 1
        stable = stable_key_for(proposed, legacy["tags"], content_hash_from_tags(legacy["tags"]))
        existing = by_key.get(stable)
        if existing is not None:
            stats["duplicateBooks"] += 1
            warnings.append(f"重复书籍 stable_key={stable}，合并 id {requested} -> {existing['row']['id']}")
            id_remap.setdefault(requested, existing["row"]["id"])
            if proposed != requested:
                id_remap.setdefault(proposed, existing["row"]["id"])
            old = existing["row"]
            merged_tags = set(existing["tags"]) | {t.strip() for t in legacy["tags"] if t.strip()}
            old_path, new_path = old["filePath"], legacy["filePath"]
            old_exists, new_exists = safe_exists(old_path), safe_exists(new_path)
            if not old_path:
                chosen = new_path
            elif not new_path:
                chosen = old_path
            elif not old_exists and new_exists:
                chosen = new_path
            else:
                chosen = old_path
            if old_path and new_path and old_path != new_path and not old_exists and not new_exists:
                warnings.append(f"重复书籍 {old['id']} 的路径均不存在，保留先到的 {old_path}")
            added = [x for x in (old["addedAt"], parse_epoch_ms(legacy["addedAtRaw"])) if x > 0]
            old.update({
                "legacyId": old["legacyId"] or (legacy["id"] if legacy["id"] > 0 else None),
                "title": old["title"] or legacy["title"],
                "sortTitle": old["sortTitle"] or legacy["title"].lower(),
                "author": old["author"] or legacy["author"],
                "format": old["format"] or normalize_format(legacy["format"]),
                "filePath": chosen,
                "fileName": old["fileName"] or file_name_from_path(chosen),
                "fileSize": max(old["fileSize"], legacy["fileSize"]),
                "contentHash": old["contentHash"] or content_hash_from_tags(list(merged_tags)),
                "source": "zlib" if old["source"] == "zlib" or zlib_id(list(merged_tags)) is not None else old["source"],
                "sourceId": old["sourceId"] or (str(zlib_id(list(merged_tags))) if zlib_id(list(merged_tags)) is not None else None),
                "addedAt": min(added) if added else 0,
                "updatedAt": now_ms,
            })
            existing["tags"] = merged_tags
            continue

        canonical = proposed
        if canonical in used_ids:
            canonical = next_synthetic
            next_synthetic += 1
            warnings.append(f"重复 id {requested} 且 stable_key 不同，后一条改用新 id {canonical}")
        used_ids.add(canonical)
        id_remap.setdefault(requested, canonical)
        if proposed != canonical:
            id_remap.setdefault(proposed, canonical)
        tags = list(dict.fromkeys(t.strip() for t in legacy["tags"] if t.strip()))
        zid = zlib_id(tags)
        row = {
            "id": canonical,
            "stableKey": stable,
            "legacyId": legacy["id"] if legacy["id"] > 0 else None,
            "title": legacy["title"],
            "sortTitle": legacy["title"].lower(),
            "author": legacy["author"],
            "format": normalize_format(legacy["format"]),
            "filePath": legacy["filePath"],
            "fileName": file_name_from_path(legacy["filePath"]),
            "fileSize": legacy["fileSize"],
            "contentHash": content_hash_from_tags(tags),
            "fileMissing": False,
            "source": "zlib" if zid is not None else "local",
            "sourceId": str(zid) if zid is not None else None,
            "addedAt": parse_epoch_ms(legacy["addedAtRaw"]),
            "updatedAt": now_ms,
        }
        if raw_format(legacy["format"]) != normalize_format(legacy["format"]):
            warnings.append(f"book {legacy['id']} format={legacy['format']} 已收敛为 {normalize_format(legacy['format'])}")
        by_key[stable] = {"row": row, "tags": set(tags)}

    final_books = []
    for acc in sorted(by_key.values(), key=lambda x: x["row"]["id"]):
        row = dict(acc["row"])
        row["fileMissing"] = (not row["filePath"]) or (not safe_exists(row["filePath"]))
        final_books.append(row)
    final_by_id = {b["id"]: b for b in final_books}

    tags_all: set[str] = set()
    book_tags: list[dict[str, Any]] = []
    for book in final_books:
        acc = next(v for v in by_key.values() if v["row"]["id"] == book["id"])
        tags = user_tags(list(acc["tags"]))
        tags_all.update(tags)
        for tag in sorted(tags):
            book_tags.append({"bookId": book["id"], "tagName": tag})

    progress_rows: dict[int, dict[str, Any]] = {}
    for legacy in progress:
        canonical = id_remap.get(legacy["bookId"])
        if canonical is None or canonical not in final_by_id:
            stats["skippedProgress"] += 1
            warnings.append(f"进度指向不存在的 book id={legacy['bookId']}，已跳过")
            continue
        page = legacy["page"] if legacy["page"] is not None and legacy["page"] >= 0 else None
        total = legacy["totalPages"] if legacy["totalPages"] is not None and legacy["totalPages"] > 0 else None
        if page is None and total is None:
            stats["skippedProgress"] += 1
            continue
        book = final_by_id[canonical]
        updated = legacy["lastReadAt"] if legacy["lastReadAt"] is not None and legacy["lastReadAt"] > 0 else now_ms
        row = {
            "bookId": canonical,
            "locatorJson": json.dumps({"kind": "pdf" if book["format"] == "pdf" else (book["format"] or "epub"), **({"page": page} if page is not None else {}), **({"total": total} if total is not None else {})}, ensure_ascii=False, separators=(",", ":")),
            "page": page,
            "totalPages": total,
            "percent": (page / total) if page is not None and total is not None and total > 0 else None,
            "updatedAt": updated,
        }
        previous = progress_rows.get(canonical)
        if previous is None:
            progress_rows[canonical] = row
        else:
            newer = row if row["updatedAt"] >= previous["updatedAt"] else previous
            merged_page = max(previous["page"] if previous["page"] is not None else -1, row["page"] if row["page"] is not None else -1)
            merged_total = newer["totalPages"] if newer["totalPages"] is not None else previous["totalPages"]
            progress_rows[canonical] = {
                **newer,
                "page": merged_page if merged_page >= 0 else None,
                "totalPages": merged_total,
                "percent": (merged_page / merged_total) if merged_page >= 0 and merged_total and merged_total > 0 else newer["percent"],
                "locatorJson": json.dumps({"kind": "pdf", **({"page": merged_page} if merged_page >= 0 else {}), **({"total": merged_total} if merged_total is not None else {})}, ensure_ascii=False, separators=(",", ":")),
            }

    zlib_key_to_id: dict[int, int] = {}
    for book in final_books:
        if book["stableKey"].startswith("zlib:"):
            try:
                zlib_key_to_id[int(book["stableKey"][5:])] = book["id"]
            except ValueError:
                pass
    downloads_by_key: dict[str, dict[str, Any]] = {}
    for legacy in downloads:
        source_id = legacy["bookId"]
        book_id = zlib_key_to_id.get(source_id) if source_id is not None else None
        if source_id is not None and book_id is None:
            warnings.append(f"下载记录 zlib book={source_id} 找不到对应书架条目，保留为孤立下载")
        fmt = normalize_format(legacy["format"])
        if raw_format(legacy["format"]) != fmt:
            warnings.append(f"下载记录 format={legacy['format']} 已收敛为 {fmt}")
        dest = legacy["destPath"] or legacy["downloadPath"]
        row = {
            "bookId": book_id,
            "sourceBookId": source_id,
            "title": legacy["title"],
            "author": legacy["author"],
            "format": fmt,
            "downloadPath": legacy["downloadPath"],
            "fileName": legacy["fileName"] or file_name_from_path(dest),
            "destPath": dest,
            "storage": legacy["storage"] or "file",
            "size": legacy["size"],
            "total": legacy["total"],
            "status": legacy["status"] or "completed",
            "errorKind": legacy["errorKind"],
            "errorMessage": legacy["errorMessage"],
            "finishedAt": legacy["finishedAt"],
            "createdAt": legacy["finishedAt"],
            "updatedAt": legacy["finishedAt"],
        }
        key = f"{source_id if source_id is not None else '-'}|{dest}"
        previous = downloads_by_key.get(key)
        if previous is None or previous["finishedAt"] <= row["finishedAt"]:
            downloads_by_key[key] = row
        else:
            stats["skippedDownloads"] += 1

    stats["uniqueBooks"] = len(final_books)
    stats["userTags"] = len(tags_all)
    stats["bookTagLinks"] = len(book_tags)
    stats["progressRows"] = len(progress_rows)
    stats["downloadRows"] = len(downloads_by_key)
    return {
        "stats": stats,
        "books": final_books,
        "tags": sorted(tags_all),
        "bookTags": book_tags,
        "progress": [progress_rows[k] for k in sorted(progress_rows)],
        "downloads": sorted(downloads_by_key.values(), key=lambda r: (r["sourceBookId"] if r["sourceBookId"] is not None else 1 << 63, r["destPath"])),
        "idRemap": {str(k): v for k, v in sorted(id_remap.items())},
        "warnings": warnings,
    }


def load_seed() -> list[dict[str, Any]]:
    data = json.loads(ASSETS.read_text(encoding="utf-8"))
    books = []
    for raw in data["books"]:
        books.append(dict(raw))
    return books


def xml_prefs(prefs: dict[str, int]) -> str:
    lines = ["<?xml version='1.0' encoding='utf-8' standalone='yes' ?>", "<map>"]
    for key in sorted(prefs):
        value = prefs[key]
        tag = "long" if key.startswith("last_read_") else "int"
        lines.append(f'    <{tag} name="{key}" value="{value}" />')
    lines.append("</map>")
    return "\n".join(lines) + "\n"


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, default=OUT_DEFAULT)
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)

    seed_raw = load_seed()
    seed_books = read_books({"books": seed_raw})
    mixed_books = read_books({"books": MIXED_BOOKS})
    progress = read_progress(PROGRESS)
    downloads = read_downloads({"downloads": DOWNLOADS})

    seed_plan = build_plan(seed_books, [], [], EXISTS_PATHS)
    mixed_plan = build_plan(mixed_books, progress, downloads, EXISTS_PATHS)
    mixed_all_exist = build_plan(mixed_books, progress, downloads, None)
    mixed_plan["fileExistsSemantics"] = "File.exists-style: only paths in EXISTS_PATHS are present; content:// is false"
    mixed_all_exist["fileExistsSemantics"] = "LibraryMigrator() default fileExists={true}; only blank paths are missing"

    write_json(out / "books.seed4.json", {"version": 1, "books": seed_raw})
    write_json(out / "books.json", {"version": 1, "books": MIXED_BOOKS})
    write_json(out / "zlib_downloads.json", {"version": 1, "downloads": DOWNLOADS})
    write_json(out / "pb_reading.json", PROGRESS)
    (out / "pb_reading.xml").write_text(xml_prefs(PROGRESS), encoding="utf-8")

    expected = {
        "generatedAt": datetime.fromtimestamp(NOW_MS / 1000, timezone.utc).isoformat(),
        "nowMs": NOW_MS,
        "fixtureKind": "device-seed-reconstruction-plus-synthetic-edge-cases",
        "deviceDataAvailable": False,
        "deviceEvidence": {
            "serial": "8A9Y0G2XJ",
            "package": "app.particlebook",
            "versionName": "2.2.1-p0",
            "runAsError": "run-as: package not debuggable: app.particlebook",
            "packageFlags": ["HAS_CODE", "ALLOW_CLEAR_USER_DATA", "LARGE_HEAP"],
        },
        "seed4": seed_plan,
        "mixed": mixed_plan,
        "mixedDefaultFileExists": mixed_all_exist,
        "sourceHashes": {
            "books.json": sha256_file(out / "books.json"),
            "books.seed4.json": sha256_file(out / "books.seed4.json"),
            "zlib_downloads.json": sha256_file(out / "zlib_downloads.json"),
            "pb_reading.xml": sha256_file(out / "pb_reading.xml"),
        },
        "formatCounts": {
            "mixedFinal": {fmt: sum(1 for b in mixed_plan["books"] if b["format"] == fmt) for fmt in sorted({b["format"] for b in mixed_plan["books"]})},
            "seed4Final": {fmt: sum(1 for b in seed_plan["books"] if b["format"] == fmt) for fmt in sorted({b["format"] for b in seed_plan["books"]})},
        },
        "missingFileCounts": {
            "mixedFileExists": sum(1 for b in mixed_plan["books"] if b["fileMissing"]),
            "mixedDefault": sum(1 for b in mixed_all_exist["books"] if b["fileMissing"]),
        },
    }
    write_json(out / "expected-migration.json", expected)
    print(json.dumps({
        "out": str(out),
        "seed": seed_plan["stats"],
        "mixed": mixed_plan["stats"],
        "mixedDefault": mixed_all_exist["stats"],
        "missing": expected["missingFileCounts"],
        "formats": expected["formatCounts"],
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
