# Spike2 基准统计 · tiles-t34-merged

> 由 `scripts/android/spike2/parse.py` 生成；本文件所有数字均来自解析结果，未做估算。
> 生成时间：2026-11-01T21:16:12+08:00　|　解析失败行：0

## 1. 环境与输入

| 文件 | run_id | app | 总行数 | 记录行 | 解析失败 |
|---|---|---|---|---|---|
| spikes\spike2-results\raw\tiles-t34-run1-20261101-211458.log | tiles-t34-run1-20261101-211458 | spike2t | 244 | 35 | 0 |

**RUNENV 元数据**

| 文件 | run_id | app | apk_md5 | scenario | ts |
|---|---|---|---|---|---|
| spikes\spike2-results\raw\tiles-t34-run1-20261101-211458.log | tiles-t34-run1-20261101-211458 | spike2t | 7ab3634ff0d97dcad0a759c988304e5d | T3,T4 | 2026-11-01T21:15:06+0800 |

| 参数 | 值 |
|---|---|
| --app | spike2t |
| --drop-first | 0 |
| --drop-mode | first-per-phase |
| --min-samples | 5 |
| 输入路径 | spikes/spike2-results/raw/tiles-t34-run1-20261101-211458.log |

## 2. 样本构成

| record | CSV 行数 |
|---|---|
| AMSTART | 1 |
| PBDONE | 3 |
| PBPERF | 28 |
| PBTIME | 2 |
| RUNENV | 1 |

- 输入 1 个文件 / 244 行；识别为本契约记录 35 行；CSV 总计 35 行。
- 参与统计的样本（ms 非空、非 warmup、非 error）：20 行；其中 ms 样本 20 行、pss 样本 1 行。
- warmup：11 行（其中剔除 11 行；drop_mode=first-per-phase，仍保留在 CSV，统计排除）。
- error：0 行（confidence=LOW_CONFIDENCE，不进 ms 统计）。

## 3. ms 统计（跨 run）

> 口径：median=`statistics.median`；p50/p95=nearest-rank（idx=ceil(q*n)-1，1-based）；单位 ms，保留 3 位小数。
> 参与：PBPERF（ms 非空、非 warmup、非 error）、PBPDF、AMSTART、PBTIME；PBDONE/RUNENV 不参与；pss 行见第 5 节。

| app | scenario | phase | n | median | p50 | p95 | max | min | 来源 |
|---|---|---|---|---|---|---|---|---|---|
| spike2t | T3 | frame_stat | 10 | 53.125 | 52.083 | 66.667 | 66.667 | 37.879 | spikes\spike2-results\raw\tiles-t34-run1-20261101-211458.log:193-211(n=10) |
| spike2t | T3 | frame_stat_all | 2 | 48.301 | 39.785 | 56.818 | 56.818 | 39.785 | spikes\spike2-results\raw\tiles-t34-run1-20261101-211458.log:205-212(n=2) |
| spike2t | T4 | gesture_stat | 4 | 53.519 | 53.333 | 57.843 | 57.843 | 49.167 | spikes\spike2-results\raw\tiles-t34-run1-20261101-211458.log:220-235(n=4) |
| spike2t | T4 | rerender | 4 | 644.577 | 505.308 | 807.601 | 807.601 | 464.019 | spikes\spike2-results\raw\tiles-t34-run1-20261101-211458.log:221-238(n=4) |

## 4. ms 统计（按 run）

| run_id | app | scenario | phase | n | median | p50 | p95 | max | min | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|
| tiles-t34-run1-20261101-211458 | spike2t | T3 | frame_stat | 10 | 53.125 | 52.083 | 66.667 | 66.667 | 37.879 | spikes\spike2-results\raw\tiles-t34-run1-20261101-211458.log:193-211(n=10) |
| tiles-t34-run1-20261101-211458 | spike2t | T3 | frame_stat_all | 2 | 48.301 | 39.785 | 56.818 | 56.818 | 39.785 | spikes\spike2-results\raw\tiles-t34-run1-20261101-211458.log:205-212(n=2) |
| tiles-t34-run1-20261101-211458 | spike2t | T4 | gesture_stat | 4 | 53.519 | 53.333 | 57.843 | 57.843 | 49.167 | spikes\spike2-results\raw\tiles-t34-run1-20261101-211458.log:220-235(n=4) |
| tiles-t34-run1-20261101-211458 | spike2t | T4 | rerender | 4 | 644.577 | 505.308 | 807.601 | 807.601 | 464.019 | spikes\spike2-results\raw\tiles-t34-run1-20261101-211458.log:221-238(n=4) |

## 5. 内存（PSS，不进 ms 表）

> 单位 KB；每格为 `min / median / max`，n 为该组 pss 行数；`-` 表示该组没有对应分项。

**跨 run**

（没有 pss 行。）

**按 run**

（没有 pss 行。）

## 6. 缺 PBDONE 的 scenario

> 同一 run_id 内没有对应 `PBDONE|scenario=<scenario>`（`scenario=ALL` 视为覆盖该 run 全部 scenario）。

（所有 scenario 都有对应 PBDONE。）

## 7. LOW_CONFIDENCE 组

> 组 (run_id, app, scenario, phase) 的有效样本数 < --min-samples 时，该组所有行 confidence=LOW_CONFIDENCE 且 flags 含 `n<5`。

| run_id | app | scenario | phase | n | error 行 | warmup 行 | 原因 |
|---|---|---|---|---|---|---|---|
| tiles-t34-run1-20261101-211458 | spike2t | ALL | pss | 0 | 0 | 1 | n<5 |
| tiles-t34-run1-20261101-211458 | spike2t | PBTIME | first_frame | 0 | 0 | 1 | n<5 |
| tiles-t34-run1-20261101-211458 | spike2t | PBTIME | refresh_hz | 0 | 0 | 1 | n<5 |
| tiles-t34-run1-20261101-211458 | spike2t | T1 | lowres | 0 | 0 | 1 | n<5 |
| tiles-t34-run1-20261101-211458 | spike2t | T1 | open | 0 | 0 | 1 | n<5 |
| tiles-t34-run1-20261101-211458 | spike2t | T1 | tileprobe | 0 | 0 | 1 | n<5 |
| tiles-t34-run1-20261101-211458 | spike2t | T3 | frame_stat_all | 2 | 0 | 1 | n<5 |
| tiles-t34-run1-20261101-211458 | spike2t | T4 | gesture_stat | 4 | 0 | 1 | n<5 |
| tiles-t34-run1-20261101-211458 | spike2t | T4 | rerender | 4 | 0 | 1 | n<5 |
| tiles-t34-run1-20261101-211458 | spike2t | cold_start | am_start | 0 | 0 | 1 | n<5 |

带 `error` flag 的行（单行级 LOW_CONFIDENCE，不进 ms 统计）：

（没有 error 行。）

## 8. 解析失败行

- 合计：0 行（`--strict` 时非 0 退出）。

（没有解析失败行。）
