# Spike2 基准统计 · tiles-t1t6-merged

> 由 `scripts/android/spike2/parse.py` 生成；本文件所有数字均来自解析结果，未做估算。
> 生成时间：2026-11-01T20:59:29+08:00　|　解析失败行：0

## 1. 环境与输入

| 文件 | run_id | app | 总行数 | 记录行 | 解析失败 |
|---|---|---|---|---|---|
| F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\tiles-t1t6-run1-20261101-205642.log | tiles-t1t6-run1-20261101-205642 | spike2t | 216 | 29 | 0 |
| F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\tiles-t1t6-run1-20261101-205821.log | tiles-t1t6-run1-20261101-205821 | spike2t | 234 | 29 | 0 |
| F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\tiles-t1t6-run2-20261101-205718.log | tiles-t1t6-run2-20261101-205718 | spike2t | 225 | 29 | 0 |
| F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\tiles-t1t6-run2-20261101-205859.log | tiles-t1t6-run2-20261101-205859 | spike2t | 206 | 29 | 0 |

**RUNENV 元数据**

| 文件 | run_id | app | apk_md5 | scenario | ts |
|---|---|---|---|---|---|
| F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\tiles-t1t6-run1-20261101-205642.log | tiles-t1t6-run1-20261101-205642 | spike2t | 7ab3634ff0d97dcad0a759c988304e5d | T1,T6 | 2026-11-01T20:56:50+0800 |
| F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\tiles-t1t6-run1-20261101-205821.log | tiles-t1t6-run1-20261101-205821 | spike2t | 7ab3634ff0d97dcad0a759c988304e5d | T1,T6 | 2026-11-01T20:58:29+0800 |
| F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\tiles-t1t6-run2-20261101-205718.log | tiles-t1t6-run2-20261101-205718 | spike2t | 7ab3634ff0d97dcad0a759c988304e5d | T1,T6 | 2026-11-01T20:57:24+0800 |
| F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\tiles-t1t6-run2-20261101-205859.log | tiles-t1t6-run2-20261101-205859 | spike2t | 7ab3634ff0d97dcad0a759c988304e5d | T1,T6 | 2026-11-01T20:59:03+0800 |

| 参数 | 值 |
|---|---|
| --app | spike2t |
| --drop-first | 0 |
| --drop-mode | first-per-phase |
| --min-samples | 5 |
| 输入路径 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t1t6-run1-20261101-205642.log F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t1t6-run1-20261101-205821.log F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t1t6-run2-20261101-205718.log F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t1t6-run2-20261101-205859.log |

## 2. 样本构成

| record | CSV 行数 |
|---|---|
| AMSTART | 4 |
| PBDONE | 12 |
| PBPERF | 84 |
| PBTIME | 12 |
| RUNENV | 4 |

- 输入 4 个文件 / 881 行；识别为本契约记录 116 行；CSV 总计 116 行。
- 参与统计的样本（ms 非空、非 warmup、非 error）：48 行；其中 ms 样本 48 行、pss 样本 8 行。
- warmup：52 行（其中剔除 52 行；drop_mode=first-per-phase，仍保留在 CSV，统计排除）。
- error：0 行（confidence=LOW_CONFIDENCE，不进 ms 统计）。

## 3. ms 统计（跨 run）

> 口径：median=`statistics.median`；p50/p95=nearest-rank（idx=ceil(q*n)-1，1-based）；单位 ms，保留 3 位小数。
> 参与：PBPERF（ms 非空、非 warmup、非 error）、PBPDF、AMSTART、PBTIME；PBDONE/RUNENV 不参与；pss 行见第 5 节。

| app | scenario | phase | n | median | p50 | p95 | max | min | 来源 |
|---|---|---|---|---|---|---|---|---|---|
| spike2t | T1 | tile | 32 | 13.111 | 11.452 | 203.918 | 208.669 | 0.206 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\tiles-t1t6-run1-20261101-205642.log:175-183(n=8)<br>F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\tiles-t1t6-run1-20261101-205821.log:189-197(n=8)<br>F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\tiles-t1t6-run2-20261101-205718.log:183-190(n=8)<br>F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\tiles-t1t6-run2-20261101-205859.log:163-171(n=8) |
| spike2t | T6 | first_screen | 16 | 378.299 | 372.998 | 600.994 | 600.994 | 263.677 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\tiles-t1t6-run1-20261101-205642.log:202-206(n=4)<br>F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\tiles-t1t6-run1-20261101-205821.log:216-225(n=4)<br>F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\tiles-t1t6-run2-20261101-205718.log:211-215(n=4)<br>F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\tiles-t1t6-run2-20261101-205859.log:183-196(n=4) |

## 4. ms 统计（按 run）

| run_id | app | scenario | phase | n | median | p50 | p95 | max | min | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|
| tiles-t1t6-run1-20261101-205642 | spike2t | T1 | tile | 8 | 13.973 | 3.348 | 208.669 | 208.669 | 0.505 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\tiles-t1t6-run1-20261101-205642.log:175-183(n=8) |
| tiles-t1t6-run1-20261101-205642 | spike2t | T6 | first_screen | 4 | 350.704 | 317.808 | 399.981 | 399.981 | 273.559 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\tiles-t1t6-run1-20261101-205642.log:202-206(n=4) |
| tiles-t1t6-run1-20261101-205821 | spike2t | T1 | tile | 8 | 13.662 | 5.933 | 139.812 | 139.812 | 0.481 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\tiles-t1t6-run1-20261101-205821.log:189-197(n=8) |
| tiles-t1t6-run1-20261101-205821 | spike2t | T6 | first_screen | 4 | 350.711 | 292.044 | 503.567 | 503.567 | 276.575 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\tiles-t1t6-run1-20261101-205821.log:216-225(n=4) |
| tiles-t1t6-run2-20261101-205718 | spike2t | T1 | tile | 8 | 12.957 | 11.144 | 68.167 | 68.167 | 0.206 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\tiles-t1t6-run2-20261101-205718.log:183-190(n=8) |
| tiles-t1t6-run2-20261101-205718 | spike2t | T6 | first_screen | 4 | 374.101 | 314.247 | 600.994 | 600.994 | 263.677 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\tiles-t1t6-run2-20261101-205718.log:211-215(n=4) |
| tiles-t1t6-run2-20261101-205859 | spike2t | T1 | tile | 8 | 18.206 | 11.452 | 149.102 | 149.102 | 0.211 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\tiles-t1t6-run2-20261101-205859.log:163-171(n=8) |
| tiles-t1t6-run2-20261101-205859 | spike2t | T6 | first_screen | 4 | 430.098 | 372.998 | 509.539 | 509.539 | 295.373 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\tiles-t1t6-run2-20261101-205859.log:183-196(n=4) |

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
| tiles-t1t6-run1-20261101-205642 | spike2t | ALL | pss | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run1-20261101-205642 | spike2t | PBTIME | first_frame | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run1-20261101-205642 | spike2t | PBTIME | read_path | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run1-20261101-205642 | spike2t | PBTIME | refresh_hz | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run1-20261101-205642 | spike2t | T1 | first_screen | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run1-20261101-205642 | spike2t | T1 | lowres | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run1-20261101-205642 | spike2t | T1 | open | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run1-20261101-205642 | spike2t | T1 | tileprobe | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run1-20261101-205642 | spike2t | T6 | first_screen | 4 | 0 | 1 | n<5 |
| tiles-t1t6-run1-20261101-205642 | spike2t | T6 | open | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run1-20261101-205642 | spike2t | T6 | pss | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run1-20261101-205642 | spike2t | cold_start | am_start | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run1-20261101-205821 | spike2t | ALL | pss | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run1-20261101-205821 | spike2t | PBTIME | first_frame | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run1-20261101-205821 | spike2t | PBTIME | read_path | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run1-20261101-205821 | spike2t | PBTIME | refresh_hz | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run1-20261101-205821 | spike2t | T1 | first_screen | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run1-20261101-205821 | spike2t | T1 | lowres | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run1-20261101-205821 | spike2t | T1 | open | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run1-20261101-205821 | spike2t | T1 | tileprobe | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run1-20261101-205821 | spike2t | T6 | first_screen | 4 | 0 | 1 | n<5 |
| tiles-t1t6-run1-20261101-205821 | spike2t | T6 | open | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run1-20261101-205821 | spike2t | T6 | pss | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run1-20261101-205821 | spike2t | cold_start | am_start | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run2-20261101-205718 | spike2t | ALL | pss | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run2-20261101-205718 | spike2t | PBTIME | first_frame | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run2-20261101-205718 | spike2t | PBTIME | read_path | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run2-20261101-205718 | spike2t | PBTIME | refresh_hz | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run2-20261101-205718 | spike2t | T1 | first_screen | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run2-20261101-205718 | spike2t | T1 | lowres | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run2-20261101-205718 | spike2t | T1 | open | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run2-20261101-205718 | spike2t | T1 | tileprobe | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run2-20261101-205718 | spike2t | T6 | first_screen | 4 | 0 | 1 | n<5 |
| tiles-t1t6-run2-20261101-205718 | spike2t | T6 | open | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run2-20261101-205718 | spike2t | T6 | pss | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run2-20261101-205718 | spike2t | cold_start | am_start | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run2-20261101-205859 | spike2t | ALL | pss | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run2-20261101-205859 | spike2t | PBTIME | first_frame | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run2-20261101-205859 | spike2t | PBTIME | read_path | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run2-20261101-205859 | spike2t | PBTIME | refresh_hz | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run2-20261101-205859 | spike2t | T1 | first_screen | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run2-20261101-205859 | spike2t | T1 | lowres | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run2-20261101-205859 | spike2t | T1 | open | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run2-20261101-205859 | spike2t | T1 | tileprobe | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run2-20261101-205859 | spike2t | T6 | first_screen | 4 | 0 | 1 | n<5 |
| tiles-t1t6-run2-20261101-205859 | spike2t | T6 | open | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run2-20261101-205859 | spike2t | T6 | pss | 0 | 0 | 1 | n<5 |
| tiles-t1t6-run2-20261101-205859 | spike2t | cold_start | am_start | 0 | 0 | 1 | n<5 |

带 `error` flag 的行（单行级 LOW_CONFIDENCE，不进 ms 统计）：

（没有 error 行。）

## 8. 解析失败行

- 合计：0 行（`--strict` 时非 0 退出）。

（没有解析失败行。）
