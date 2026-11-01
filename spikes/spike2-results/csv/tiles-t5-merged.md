# Spike2 基准统计 · tiles-t5-merged

> 由 `scripts/android/spike2/parse.py` 生成；本文件所有数字均来自解析结果，未做估算。
> 生成时间：2026-11-01T21:16:11+08:00　|　解析失败行：0

## 1. 环境与输入

| 文件 | run_id | app | 总行数 | 记录行 | 解析失败 |
|---|---|---|---|---|---|
| spikes\spike2-results\raw\tiles-t5-run1-20261101-210906.log | tiles-t5-run1-20261101-210906 | spike2t | 234 | 13 | 0 |
| spikes\spike2-results\raw\tiles-t5-run1-20261101-211023.log | tiles-t5-run1-20261101-211023 | spike2t | 244 | 13 | 0 |
| spikes\spike2-results\raw\tiles-t5-run1-20261101-211344.log | tiles-t5-run1-20261101-211344 | spike2t | 254 | 13 | 0 |

**RUNENV 元数据**

| 文件 | run_id | app | apk_md5 | scenario | ts |
|---|---|---|---|---|---|
| spikes\spike2-results\raw\tiles-t5-run1-20261101-210906.log | tiles-t5-run1-20261101-210906 | spike2t | 7ab3634ff0d97dcad0a759c988304e5d | T5 | 2026-11-01T21:09:14+0800 |
| spikes\spike2-results\raw\tiles-t5-run1-20261101-211023.log | tiles-t5-run1-20261101-211023 | spike2t | 7ab3634ff0d97dcad0a759c988304e5d | T5 | 2026-11-01T21:10:33+0800 |
| spikes\spike2-results\raw\tiles-t5-run1-20261101-211344.log | tiles-t5-run1-20261101-211344 | spike2t | 7ab3634ff0d97dcad0a759c988304e5d | T5 | 2026-11-01T21:13:54+0800 |

| 参数 | 值 |
|---|---|
| --app | spike2t |
| --drop-first | 0 |
| --drop-mode | first-per-phase |
| --min-samples | 5 |
| 输入路径 | spikes/spike2-results/raw/tiles-t5-run1-20261101-210906.log spikes/spike2-results/raw/tiles-t5-run1-20261101-211023.log spikes/spike2-results/raw/tiles-t5-run1-20261101-211344.log |

## 2. 样本构成

| record | CSV 行数 |
|---|---|
| AMSTART | 3 |
| PBDONE | 6 |
| PBPERF | 21 |
| PBTIME | 6 |
| RUNENV | 3 |

- 输入 3 个文件 / 732 行；识别为本契约记录 39 行；CSV 总计 39 行。
- 参与统计的样本（ms 非空、非 warmup、非 error）：3 行；其中 ms 样本 0 行、pss 样本 9 行。
- warmup：27 行（其中剔除 27 行；drop_mode=first-per-phase，仍保留在 CSV，统计排除）。
- error：0 行（confidence=LOW_CONFIDENCE，不进 ms 统计）。

## 3. ms 统计（跨 run）

> 口径：median=`statistics.median`；p50/p95=nearest-rank（idx=ceil(q*n)-1，1-based）；单位 ms，保留 3 位小数。
> 参与：PBPERF（ms 非空、非 warmup、非 error）、PBPDF、AMSTART、PBTIME；PBDONE/RUNENV 不参与；pss 行见第 5 节。

（没有可统计的 ms 样本。）

## 4. ms 统计（按 run）

（没有可统计的 ms 样本。）

## 5. 内存（PSS，不进 ms 表）

> 单位 KB；每格为 `min / median / max`，n 为该组 pss 行数；`-` 表示该组没有对应分项。

**跨 run**

| app | scenario | n | pss_kb | native_kb | java_kb | 来源 |
|---|---|---|---|---|---|---|
| spike2t | T5 | 3 | 115290.000 / 125702.000 / 128680.000 | 79632.000 / 90840.000 / 94120.000 | 780.000 / 808.000 / 832.000 | spikes\spike2-results\raw\tiles-t5-run1-20261101-210906.log:211-211(n=1)<br>spikes\spike2-results\raw\tiles-t5-run1-20261101-211023.log:226-226(n=1)<br>spikes\spike2-results\raw\tiles-t5-run1-20261101-211344.log:235-235(n=1) |

**按 run**

| run_id | app | scenario | n | pss_kb | native_kb | java_kb | 来源 |
|---|---|---|---|---|---|---|---|
| tiles-t5-run1-20261101-210906 | spike2t | T5 | 1 | 115290.000 / 115290.000 / 115290.000 | 79632.000 / 79632.000 / 79632.000 | 832.000 / 832.000 / 832.000 | spikes\spike2-results\raw\tiles-t5-run1-20261101-210906.log:211-211(n=1) |
| tiles-t5-run1-20261101-211023 | spike2t | T5 | 1 | 128680.000 / 128680.000 / 128680.000 | 94120.000 / 94120.000 / 94120.000 | 780.000 / 780.000 / 780.000 | spikes\spike2-results\raw\tiles-t5-run1-20261101-211023.log:226-226(n=1) |
| tiles-t5-run1-20261101-211344 | spike2t | T5 | 1 | 125702.000 / 125702.000 / 125702.000 | 90840.000 / 90840.000 / 90840.000 | 808.000 / 808.000 / 808.000 | spikes\spike2-results\raw\tiles-t5-run1-20261101-211344.log:235-235(n=1) |

## 6. 缺 PBDONE 的 scenario

> 同一 run_id 内没有对应 `PBDONE|scenario=<scenario>`（`scenario=ALL` 视为覆盖该 run 全部 scenario）。

（所有 scenario 都有对应 PBDONE。）

## 7. LOW_CONFIDENCE 组

> 组 (run_id, app, scenario, phase) 的有效样本数 < --min-samples 时，该组所有行 confidence=LOW_CONFIDENCE 且 flags 含 `n<5`。

| run_id | app | scenario | phase | n | error 行 | warmup 行 | 原因 |
|---|---|---|---|---|---|---|---|
| tiles-t5-run1-20261101-210906 | spike2t | ALL | pss | 0 | 0 | 1 | n<5 |
| tiles-t5-run1-20261101-210906 | spike2t | PBTIME | first_frame | 0 | 0 | 1 | n<5 |
| tiles-t5-run1-20261101-210906 | spike2t | PBTIME | refresh_hz | 0 | 0 | 1 | n<5 |
| tiles-t5-run1-20261101-210906 | spike2t | T1 | lowres | 0 | 0 | 1 | n<5 |
| tiles-t5-run1-20261101-210906 | spike2t | T1 | open | 0 | 0 | 1 | n<5 |
| tiles-t5-run1-20261101-210906 | spike2t | T1 | tileprobe | 0 | 0 | 1 | n<5 |
| tiles-t5-run1-20261101-210906 | spike2t | T5 | cache | 0 | 0 | 1 | n<5 |
| tiles-t5-run1-20261101-210906 | spike2t | T5 | pss | 1 | 0 | 1 | n<5 |
| tiles-t5-run1-20261101-210906 | spike2t | cold_start | am_start | 0 | 0 | 1 | n<5 |
| tiles-t5-run1-20261101-211023 | spike2t | ALL | pss | 0 | 0 | 1 | n<5 |
| tiles-t5-run1-20261101-211023 | spike2t | PBTIME | first_frame | 0 | 0 | 1 | n<5 |
| tiles-t5-run1-20261101-211023 | spike2t | PBTIME | refresh_hz | 0 | 0 | 1 | n<5 |
| tiles-t5-run1-20261101-211023 | spike2t | T1 | lowres | 0 | 0 | 1 | n<5 |
| tiles-t5-run1-20261101-211023 | spike2t | T1 | open | 0 | 0 | 1 | n<5 |
| tiles-t5-run1-20261101-211023 | spike2t | T1 | tileprobe | 0 | 0 | 1 | n<5 |
| tiles-t5-run1-20261101-211023 | spike2t | T5 | cache | 0 | 0 | 1 | n<5 |
| tiles-t5-run1-20261101-211023 | spike2t | T5 | pss | 1 | 0 | 1 | n<5 |
| tiles-t5-run1-20261101-211023 | spike2t | cold_start | am_start | 0 | 0 | 1 | n<5 |
| tiles-t5-run1-20261101-211344 | spike2t | ALL | pss | 0 | 0 | 1 | n<5 |
| tiles-t5-run1-20261101-211344 | spike2t | PBTIME | first_frame | 0 | 0 | 1 | n<5 |
| tiles-t5-run1-20261101-211344 | spike2t | PBTIME | refresh_hz | 0 | 0 | 1 | n<5 |
| tiles-t5-run1-20261101-211344 | spike2t | T1 | lowres | 0 | 0 | 1 | n<5 |
| tiles-t5-run1-20261101-211344 | spike2t | T1 | open | 0 | 0 | 1 | n<5 |
| tiles-t5-run1-20261101-211344 | spike2t | T1 | tileprobe | 0 | 0 | 1 | n<5 |
| tiles-t5-run1-20261101-211344 | spike2t | T5 | cache | 0 | 0 | 1 | n<5 |
| tiles-t5-run1-20261101-211344 | spike2t | T5 | pss | 1 | 0 | 1 | n<5 |
| tiles-t5-run1-20261101-211344 | spike2t | cold_start | am_start | 0 | 0 | 1 | n<5 |

带 `error` flag 的行（单行级 LOW_CONFIDENCE，不进 ms 统计）：

（没有 error 行。）

## 8. 解析失败行

- 合计：0 行（`--strict` 时非 0 退出）。

（没有解析失败行。）
