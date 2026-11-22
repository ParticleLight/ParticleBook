# API 31 framestats replay fixture

This is an offline regression fixture extracted from the sealed Pixel 3 XL Android 12 captures. It is replay data, not a new device measurement. Both original formal reports remain FAIL.

- `captures.json.gz` contains all five 03 dumps and all three ordered 04 chains of 21 snapshots, their PBPERF events, gfx summaries and original result details. Repeated command containers, UI dumps, screenshots and machine-specific absolute paths are excluded.
- `manifest.json` binds the two source report byte/SHA256 identities, capture JSON pointers, every unmodified UTF-8 dump byte/SHA256 identity, the compressed/uncompressed payload and canonical hashes of the original 04 seven-field frame projections. Source paths are provenance only; tests never open the reports under `docs/`.
- Canonical projection/payload encoding is UTF-8 JSON with `ensure_ascii=False`, `sort_keys=True`, compact separators and one trailing LF. List order is unchanged.
- Compression uses Python `gzip.GzipFile(fileobj=BytesIO(), filename="", mode="wb", compresslevel=9, mtime=0)`. The archive has no timestamp or original filename. Regeneration with the same Python/zlib implementation and identical source reports produces identical bytes.

Replay assertions retain 249 cold rows, nine same-vsync/different-queue pairs, 63 real turn snapshots, 3175 unique turn frames, all Flags=2 rows, 120-row ring wrapping and all six original 04 budget failures. The original 04 window statistics must remain exactly equal. Synthetic mutations are explicit negative controls and are never written into this fixture or the original reports.

The production frame projection still contains only flags, intended_vsync_ns, draw_start_ns, sync_queued_ns, frame_completed_ns, window and pid. Extra CSV columns stay in raw evidence; they are not part of the collector's existing projection-conflict comparison.

Run from any directory using the full path to `scripts/android/test_perf_epub.py`, or from the repository root:

```sh
PYTHONDONTWRITEBYTECODE=1 python -B -m unittest discover -s scripts/android -p test_perf_epub.py -v
```
