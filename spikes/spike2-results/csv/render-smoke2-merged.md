# Spike2 基准统计 · render-smoke2-merged

> 由 `scripts/android/spike2/parse.py` 生成；本文件所有数字均来自解析结果，未做估算。
> 生成时间：2026-11-01T20:41:18+08:00　|　解析失败行：0

## 1. 环境与输入

| 文件 | run_id | app | 总行数 | 记录行 | 解析失败 |
|---|---|---|---|---|---|
| F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke2-run1-20261101-204037.log | render-smoke2-run1-20261101-204037 | spike2 | 228 | 51 | 0 |

**RUNENV 元数据**

| 文件 | run_id | app | apk_md5 | scenario | ts |
|---|---|---|---|---|---|
| F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke2-run1-20261101-204037.log | render-smoke2-run1-20261101-204037 | spike2 | 8ea3edc683ebf0b5be2f32f32dc57bb9 | S1,S2 | 2026-11-01T20:40:48+0800 |

| 参数 | 值 |
|---|---|
| --app | spike2 |
| --drop-first | 0 |
| --drop-mode | none |
| --min-samples | 5 |
| 输入路径 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-smoke2-run1-20261101-204037.log |

## 2. 样本构成

| record | CSV 行数 |
|---|---|
| AMSTART | 1 |
| PBDONE | 3 |
| PBPERF | 43 |
| PBTIME | 3 |
| RUNENV | 1 |

- 输入 1 个文件 / 228 行；识别为本契约记录 51 行；CSV 总计 51 行。
- 参与统计的样本（ms 非空、非 warmup、非 error）：47 行；其中 ms 样本 46 行、pss 样本 1 行。
- warmup：0 行（其中剔除 0 行；drop_mode=none，仍保留在 CSV，统计排除）。
- error：0 行（confidence=LOW_CONFIDENCE，不进 ms 统计）。

## 3. ms 统计（跨 run）

> 口径：median=`statistics.median`；p50/p95=nearest-rank（idx=ceil(q*n)-1，1-based）；单位 ms，保留 3 位小数。
> 参与：PBPERF（ms 非空、非 warmup、非 error）、PBPDF、AMSTART、PBTIME；PBDONE/RUNENV 不参与；pss 行见第 5 节。

| app | scenario | phase | n | median | p50 | p95 | max | min | 来源 |
|---|---|---|---|---|---|---|---|---|---|
| spike2 | PBTIME | first_frame | 1 | 1403.849 | 1403.849 | 1403.849 | 1403.849 | 1403.849 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke2-run1-20261101-204037.log:129-129(n=1) |
| spike2 | PBTIME | first_screen | 1 | 3827.834 | 3827.834 | 3827.834 | 3827.834 | 3827.834 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke2-run1-20261101-204037.log:159-159(n=1) |
| spike2 | PBTIME | open_to_first_screen | 1 | 2842.865 | 2842.865 | 2842.865 | 2842.865 | 2842.865 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke2-run1-20261101-204037.log:160-160(n=1) |
| spike2 | S1 | open | 2 | 529.840 | 370.231 | 689.449 | 689.449 | 370.231 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke2-run1-20261101-204037.log:131-135(n=2) |
| spike2 | S2 | present | 20 | 40.380 | 40.008 | 97.255 | 215.607 | 17.356 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke2-run1-20261101-204037.log:156-221(n=20) |
| spike2 | S2 | render | 20 | 94.454 | 93.935 | 775.821 | 1242.749 | 21.799 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke2-run1-20261101-204037.log:148-220(n=20) |
| spike2 | cold_start | am_start | 1 | 6871.000 | 6871.000 | 6871.000 | 6871.000 | 6871.000 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke2-run1-20261101-204037.log:4-4(n=1) |

## 4. ms 统计（按 run）

| run_id | app | scenario | phase | n | median | p50 | p95 | max | min | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|
| render-smoke2-run1-20261101-204037 | spike2 | PBTIME | first_frame | 1 | 1403.849 | 1403.849 | 1403.849 | 1403.849 | 1403.849 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke2-run1-20261101-204037.log:129-129(n=1) |
| render-smoke2-run1-20261101-204037 | spike2 | PBTIME | first_screen | 1 | 3827.834 | 3827.834 | 3827.834 | 3827.834 | 3827.834 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke2-run1-20261101-204037.log:159-159(n=1) |
| render-smoke2-run1-20261101-204037 | spike2 | PBTIME | open_to_first_screen | 1 | 2842.865 | 2842.865 | 2842.865 | 2842.865 | 2842.865 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke2-run1-20261101-204037.log:160-160(n=1) |
| render-smoke2-run1-20261101-204037 | spike2 | S1 | open | 2 | 529.840 | 370.231 | 689.449 | 689.449 | 370.231 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke2-run1-20261101-204037.log:131-135(n=2) |
| render-smoke2-run1-20261101-204037 | spike2 | S2 | present | 20 | 40.380 | 40.008 | 97.255 | 215.607 | 17.356 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke2-run1-20261101-204037.log:156-221(n=20) |
| render-smoke2-run1-20261101-204037 | spike2 | S2 | render | 20 | 94.454 | 93.935 | 775.821 | 1242.749 | 21.799 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke2-run1-20261101-204037.log:148-220(n=20) |
| render-smoke2-run1-20261101-204037 | spike2 | cold_start | am_start | 1 | 6871.000 | 6871.000 | 6871.000 | 6871.000 | 6871.000 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke2-run1-20261101-204037.log:4-4(n=1) |

## 5. 内存（PSS，不进 ms 表）

> 单位 KB；每格为 `min / median / max`，n 为该组 pss 行数；`-` 表示该组没有对应分项。

**跨 run**

| app | scenario | n | pss_kb | native_kb | java_kb | 来源 |
|---|---|---|---|---|---|---|
| spike2 | S7 | 1 | 25073.000 / 25073.000 / 25073.000 | 3890.000 / 3890.000 / 3890.000 | 702.000 / 702.000 / 702.000 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke2-run1-20261101-204037.log:117-117(n=1) |

**按 run**

| run_id | app | scenario | n | pss_kb | native_kb | java_kb | 来源 |
|---|---|---|---|---|---|---|---|
| render-smoke2-run1-20261101-204037 | spike2 | S7 | 1 | 25073.000 / 25073.000 / 25073.000 | 3890.000 / 3890.000 / 3890.000 | 702.000 / 702.000 / 702.000 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke2-run1-20261101-204037.log:117-117(n=1) |

## 6. 缺 PBDONE 的 scenario

> 同一 run_id 内没有对应 `PBDONE|scenario=<scenario>`（`scenario=ALL` 视为覆盖该 run 全部 scenario）。

（所有 scenario 都有对应 PBDONE。）

## 7. LOW_CONFIDENCE 组

> 组 (run_id, app, scenario, phase) 的有效样本数 < --min-samples 时，该组所有行 confidence=LOW_CONFIDENCE 且 flags 含 `n<5`。

| run_id | app | scenario | phase | n | error 行 | warmup 行 | 原因 |
|---|---|---|---|---|---|---|---|
| render-smoke2-run1-20261101-204037 | spike2 | PBTIME | first_frame | 1 | 0 | 0 | n<5 |
| render-smoke2-run1-20261101-204037 | spike2 | PBTIME | first_screen | 1 | 0 | 0 | n<5 |
| render-smoke2-run1-20261101-204037 | spike2 | PBTIME | open_to_first_screen | 1 | 0 | 0 | n<5 |
| render-smoke2-run1-20261101-204037 | spike2 | S1 | open | 2 | 0 | 0 | n<5 |
| render-smoke2-run1-20261101-204037 | spike2 | S7 | pss | 1 | 0 | 0 | n<5 |
| render-smoke2-run1-20261101-204037 | spike2 | cold_start | am_start | 1 | 0 | 0 | n<5 |

带 `error` flag 的行（单行级 LOW_CONFIDENCE，不进 ms 统计）：

（没有 error 行。）

## 8. 解析失败行

- 合计：0 行（`--strict` 时非 0 退出）。

（没有解析失败行。）
