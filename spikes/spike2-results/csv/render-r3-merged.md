# Spike2 基准统计 · render-r3-merged

> 由 `scripts/android/spike2/parse.py` 生成；本文件所有数字均来自解析结果，未做估算。
> 生成时间：2026-11-01T21:07:24+08:00　|　解析失败行：0

## 1. 环境与输入

| 文件 | run_id | app | 总行数 | 记录行 | 解析失败 |
|---|---|---|---|---|---|
| F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r3-run1-20261101-210622.log | render-r3-run1-20261101-210622 | spike2 | 228 | 34 | 0 |

**RUNENV 元数据**

| 文件 | run_id | app | apk_md5 | scenario | ts |
|---|---|---|---|---|---|
| F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r3-run1-20261101-210622.log | render-r3-run1-20261101-210622 | spike2 | 8ea3edc683ebf0b5be2f32f32dc57bb9 | S6,S7,S8 | 2026-11-01T21:06:33+0800 |

| 参数 | 值 |
|---|---|
| --app | spike2 |
| --drop-first | 0 |
| --drop-mode | first-per-phase |
| --min-samples | 5 |
| 输入路径 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r3-run1-20261101-210622.log |

## 2. 样本构成

| record | CSV 行数 |
|---|---|
| AMSTART | 1 |
| PBDONE | 5 |
| PBPERF | 26 |
| PBTIME | 1 |
| RUNENV | 1 |

- 输入 1 个文件 / 228 行；识别为本契约记录 34 行；CSV 总计 34 行。
- 参与统计的样本（ms 非空、非 warmup、非 error）：22 行；其中 ms 样本 19 行、pss 样本 4 行。
- warmup：6 行（其中剔除 6 行；drop_mode=first-per-phase，仍保留在 CSV，统计排除）。
- error：0 行（confidence=LOW_CONFIDENCE，不进 ms 统计）。

## 3. ms 统计（跨 run）

> 口径：median=`statistics.median`；p50/p95=nearest-rank（idx=ceil(q*n)-1，1-based）；单位 ms，保留 3 位小数。
> 参与：PBPERF（ms 非空、非 warmup、非 error）、PBPDF、AMSTART、PBTIME；PBDONE/RUNENV 不参与；pss 行见第 5 节。

| app | scenario | phase | n | median | p50 | p95 | max | min | 来源 |
|---|---|---|---|---|---|---|---|---|---|
| spike2 | S1 | open | 1 | 1685.539 | 1685.539 | 1685.539 | 1685.539 | 1685.539 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r3-run1-20261101-210622.log:146-146(n=1) |
| spike2 | S6 | render | 14 | 467.117 | 417.063 | 1311.447 | 1311.447 | 135.844 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r3-run1-20261101-210622.log:175-201(n=14) |
| spike2 | S8 | render_hw | 4 | 8.440 | 5.588 | 16.155 | 16.155 | 0.107 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r3-run1-20261101-210622.log:217-225(n=4) |

## 4. ms 统计（按 run）

| run_id | app | scenario | phase | n | median | p50 | p95 | max | min | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|
| render-r3-run1-20261101-210622 | spike2 | S1 | open | 1 | 1685.539 | 1685.539 | 1685.539 | 1685.539 | 1685.539 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r3-run1-20261101-210622.log:146-146(n=1) |
| render-r3-run1-20261101-210622 | spike2 | S6 | render | 14 | 467.117 | 417.063 | 1311.447 | 1311.447 | 135.844 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r3-run1-20261101-210622.log:175-201(n=14) |
| render-r3-run1-20261101-210622 | spike2 | S8 | render_hw | 4 | 8.440 | 5.588 | 16.155 | 16.155 | 0.107 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r3-run1-20261101-210622.log:217-225(n=4) |

## 5. 内存（PSS，不进 ms 表）

> 单位 KB；每格为 `min / median / max`，n 为该组 pss 行数；`-` 表示该组没有对应分项。

**跨 run**

| app | scenario | n | pss_kb | native_kb | java_kb | 来源 |
|---|---|---|---|---|---|---|
| spike2 | S7 | 3 | 71635.000 / 72458.000 / 75493.000 | 42234.000 / 42643.000 / 42694.000 | 689.000 / 693.000 / 701.000 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r3-run1-20261101-210622.log:203-208(n=3) |

**按 run**

| run_id | app | scenario | n | pss_kb | native_kb | java_kb | 来源 |
|---|---|---|---|---|---|---|---|
| render-r3-run1-20261101-210622 | spike2 | S7 | 3 | 71635.000 / 72458.000 / 75493.000 | 42234.000 / 42643.000 / 42694.000 | 689.000 / 693.000 / 701.000 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r3-run1-20261101-210622.log:203-208(n=3) |

## 6. 缺 PBDONE 的 scenario

> 同一 run_id 内没有对应 `PBDONE|scenario=<scenario>`（`scenario=ALL` 视为覆盖该 run 全部 scenario）。

（所有 scenario 都有对应 PBDONE。）

## 7. LOW_CONFIDENCE 组

> 组 (run_id, app, scenario, phase) 的有效样本数 < --min-samples 时，该组所有行 confidence=LOW_CONFIDENCE 且 flags 含 `n<5`。

| run_id | app | scenario | phase | n | error 行 | warmup 行 | 原因 |
|---|---|---|---|---|---|---|---|
| render-r3-run1-20261101-210622 | spike2 | PBTIME | first_frame | 0 | 0 | 1 | n<5 |
| render-r3-run1-20261101-210622 | spike2 | S1 | open | 1 | 0 | 1 | n<5 |
| render-r3-run1-20261101-210622 | spike2 | S7 | pss | 3 | 0 | 1 | n<5 |
| render-r3-run1-20261101-210622 | spike2 | S8 | render_hw | 4 | 0 | 1 | n<5 |
| render-r3-run1-20261101-210622 | spike2 | cold_start | am_start | 0 | 0 | 1 | n<5 |

带 `error` flag 的行（单行级 LOW_CONFIDENCE，不进 ms 统计）：

（没有 error 行。）

## 8. 解析失败行

- 合计：0 行（`--strict` 时非 0 退出）。

（没有解析失败行。）
