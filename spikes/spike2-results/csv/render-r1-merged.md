# Spike2 基准统计 · render-r1-merged

> 由 `scripts/android/spike2/parse.py` 生成；本文件所有数字均来自解析结果，未做估算。
> 生成时间：2026-11-01T20:49:30+08:00　|　解析失败行：0

## 1. 环境与输入

| 文件 | run_id | app | 总行数 | 记录行 | 解析失败 |
|---|---|---|---|---|---|
| F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204131.log | render-r1-run1-20261101-204131 | spike2 | 240 | 57 | 0 |
| F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204647.log | render-r1-run1-20261101-204647 | spike2 | 224 | 57 | 0 |
| F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204732.log | render-r1-run1-20261101-204732 | spike2 | 237 | 57 | 0 |
| F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204818.log | render-r1-run1-20261101-204818 | spike2 | 222 | 57 | 0 |
| F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204859.log | render-r1-run1-20261101-204859 | spike2 | 221 | 57 | 0 |

**RUNENV 元数据**

| 文件 | run_id | app | apk_md5 | scenario | ts |
|---|---|---|---|---|---|
| F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204131.log | render-r1-run1-20261101-204131 | spike2 | 8ea3edc683ebf0b5be2f32f32dc57bb9 | S1,S2,S3 | 2026-11-01T20:41:39+0800 |
| F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204647.log | render-r1-run1-20261101-204647 | spike2 | 8ea3edc683ebf0b5be2f32f32dc57bb9 | S1,S2,S3 | 2026-11-01T20:46:55+0800 |
| F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204732.log | render-r1-run1-20261101-204732 | spike2 | 8ea3edc683ebf0b5be2f32f32dc57bb9 | S1,S2,S3 | 2026-11-01T20:47:40+0800 |
| F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204818.log | render-r1-run1-20261101-204818 | spike2 | 8ea3edc683ebf0b5be2f32f32dc57bb9 | S1,S2,S3 | 2026-11-01T20:48:25+0800 |
| F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204859.log | render-r1-run1-20261101-204859 | spike2 | 8ea3edc683ebf0b5be2f32f32dc57bb9 | S1,S2,S3 | 2026-11-01T20:49:06+0800 |

| 参数 | 值 |
|---|---|
| --app | spike2 |
| --drop-first | 0 |
| --drop-mode | first-per-phase |
| --min-samples | 5 |
| 输入路径 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r1-run1-20261101-204131.log F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r1-run1-20261101-204647.log F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r1-run1-20261101-204732.log F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r1-run1-20261101-204818.log F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r1-run1-20261101-204859.log |

## 2. 样本构成

| record | CSV 行数 |
|---|---|
| AMSTART | 5 |
| PBDONE | 20 |
| PBPERF | 240 |
| PBTIME | 15 |
| RUNENV | 5 |

- 输入 5 个文件 / 1144 行；识别为本契约记录 285 行；CSV 总计 285 行。
- 参与统计的样本（ms 非空、非 warmup、非 error）：215 行；其中 ms 样本 215 行、pss 样本 5 行。
- warmup：45 行（其中剔除 45 行；drop_mode=first-per-phase，仍保留在 CSV，统计排除）。
- error：0 行（confidence=LOW_CONFIDENCE，不进 ms 统计）。

## 3. ms 统计（跨 run）

> 口径：median=`statistics.median`；p50/p95=nearest-rank（idx=ceil(q*n)-1，1-based）；单位 ms，保留 3 位小数。
> 参与：PBPERF（ms 非空、非 warmup、非 error）、PBPDF、AMSTART、PBTIME；PBDONE/RUNENV 不参与；pss 行见第 5 节。

| app | scenario | phase | n | median | p50 | p95 | max | min | 来源 |
|---|---|---|---|---|---|---|---|---|---|
| spike2 | S1 | open | 5 | 540.686 | 540.686 | 665.414 | 665.414 | 324.164 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204131.log:122-122(n=1)<br>F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204647.log:118-118(n=1)<br>F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204732.log:128-128(n=1)<br>F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204818.log:122-122(n=1)<br>F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204859.log:124-124(n=1) |
| spike2 | S2 | present | 95 | 42.206 | 42.206 | 251.172 | 705.791 | 18.029 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204131.log:163-224(n=19)<br>F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204647.log:155-210(n=19)<br>F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204732.log:147-219(n=19)<br>F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204818.log:156-202(n=19)<br>F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204859.log:156-202(n=19) |
| spike2 | S2 | render | 95 | 102.717 | 102.717 | 582.050 | 1525.398 | 4.924 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204131.log:133-223(n=19)<br>F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204647.log:152-208(n=19)<br>F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204732.log:139-218(n=19)<br>F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204818.log:133-201(n=19)<br>F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204859.log:134-201(n=19) |
| spike2 | S3 | render | 20 | 298.816 | 297.206 | 520.122 | 549.477 | 136.061 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204131.log:226-232(n=4)<br>F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204647.log:212-216(n=4)<br>F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204732.log:222-229(n=4)<br>F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204818.log:204-215(n=4)<br>F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204859.log:204-214(n=4) |

## 4. ms 统计（按 run）

| run_id | app | scenario | phase | n | median | p50 | p95 | max | min | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|
| render-r1-run1-20261101-204131 | spike2 | S1 | open | 1 | 466.173 | 466.173 | 466.173 | 466.173 | 466.173 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204131.log:122-122(n=1) |
| render-r1-run1-20261101-204131 | spike2 | S2 | present | 19 | 40.478 | 40.478 | 572.397 | 572.397 | 22.321 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204131.log:163-224(n=19) |
| render-r1-run1-20261101-204131 | spike2 | S2 | render | 19 | 122.287 | 122.287 | 889.735 | 889.735 | 24.344 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204131.log:133-223(n=19) |
| render-r1-run1-20261101-204131 | spike2 | S3 | render | 4 | 327.389 | 300.426 | 379.821 | 379.821 | 297.206 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204131.log:226-232(n=4) |
| render-r1-run1-20261101-204647 | spike2 | S1 | open | 1 | 665.414 | 665.414 | 665.414 | 665.414 | 665.414 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204647.log:118-118(n=1) |
| render-r1-run1-20261101-204647 | spike2 | S2 | present | 19 | 49.371 | 49.371 | 251.172 | 251.172 | 28.882 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204647.log:155-210(n=19) |
| render-r1-run1-20261101-204647 | spike2 | S2 | render | 19 | 126.797 | 126.797 | 1525.398 | 1525.398 | 23.583 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204647.log:152-208(n=19) |
| render-r1-run1-20261101-204647 | spike2 | S3 | render | 4 | 308.933 | 219.923 | 492.736 | 492.736 | 215.040 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204647.log:212-216(n=4) |
| render-r1-run1-20261101-204732 | spike2 | S1 | open | 1 | 557.827 | 557.827 | 557.827 | 557.827 | 557.827 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204732.log:128-128(n=1) |
| render-r1-run1-20261101-204732 | spike2 | S2 | present | 19 | 39.601 | 39.601 | 306.479 | 306.479 | 18.029 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204732.log:147-219(n=19) |
| render-r1-run1-20261101-204732 | spike2 | S2 | render | 19 | 93.316 | 93.316 | 1026.550 | 1026.550 | 4.924 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204732.log:139-218(n=19) |
| render-r1-run1-20261101-204732 | spike2 | S3 | render | 4 | 297.978 | 282.918 | 549.477 | 549.477 | 199.300 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204732.log:222-229(n=4) |
| render-r1-run1-20261101-204818 | spike2 | S1 | open | 1 | 324.164 | 324.164 | 324.164 | 324.164 | 324.164 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204818.log:122-122(n=1) |
| render-r1-run1-20261101-204818 | spike2 | S2 | present | 19 | 38.528 | 38.528 | 705.791 | 705.791 | 20.978 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204818.log:156-202(n=19) |
| render-r1-run1-20261101-204818 | spike2 | S2 | render | 19 | 99.531 | 99.531 | 1089.814 | 1089.814 | 12.865 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204818.log:133-201(n=19) |
| render-r1-run1-20261101-204818 | spike2 | S3 | render | 4 | 232.447 | 190.727 | 520.122 | 520.122 | 136.061 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204818.log:204-215(n=4) |
| render-r1-run1-20261101-204859 | spike2 | S1 | open | 1 | 540.686 | 540.686 | 540.686 | 540.686 | 540.686 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204859.log:124-124(n=1) |
| render-r1-run1-20261101-204859 | spike2 | S2 | present | 19 | 36.222 | 36.222 | 597.233 | 597.233 | 23.998 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204859.log:156-202(n=19) |
| render-r1-run1-20261101-204859 | spike2 | S2 | render | 19 | 95.027 | 95.027 | 582.050 | 582.050 | 27.232 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204859.log:134-201(n=19) |
| render-r1-run1-20261101-204859 | spike2 | S3 | render | 4 | 258.726 | 192.374 | 372.365 | 372.365 | 143.363 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r1-run1-20261101-204859.log:204-214(n=4) |

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
| render-r1-run1-20261101-204131 | spike2 | PBTIME | first_frame | 0 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204131 | spike2 | PBTIME | first_screen | 0 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204131 | spike2 | PBTIME | open_to_first_screen | 0 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204131 | spike2 | S1 | open | 1 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204131 | spike2 | S3 | render | 4 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204131 | spike2 | S7 | pss | 0 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204131 | spike2 | cold_start | am_start | 0 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204647 | spike2 | PBTIME | first_frame | 0 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204647 | spike2 | PBTIME | first_screen | 0 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204647 | spike2 | PBTIME | open_to_first_screen | 0 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204647 | spike2 | S1 | open | 1 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204647 | spike2 | S3 | render | 4 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204647 | spike2 | S7 | pss | 0 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204647 | spike2 | cold_start | am_start | 0 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204732 | spike2 | PBTIME | first_frame | 0 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204732 | spike2 | PBTIME | first_screen | 0 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204732 | spike2 | PBTIME | open_to_first_screen | 0 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204732 | spike2 | S1 | open | 1 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204732 | spike2 | S3 | render | 4 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204732 | spike2 | S7 | pss | 0 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204732 | spike2 | cold_start | am_start | 0 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204818 | spike2 | PBTIME | first_frame | 0 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204818 | spike2 | PBTIME | first_screen | 0 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204818 | spike2 | PBTIME | open_to_first_screen | 0 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204818 | spike2 | S1 | open | 1 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204818 | spike2 | S3 | render | 4 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204818 | spike2 | S7 | pss | 0 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204818 | spike2 | cold_start | am_start | 0 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204859 | spike2 | PBTIME | first_frame | 0 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204859 | spike2 | PBTIME | first_screen | 0 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204859 | spike2 | PBTIME | open_to_first_screen | 0 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204859 | spike2 | S1 | open | 1 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204859 | spike2 | S3 | render | 4 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204859 | spike2 | S7 | pss | 0 | 0 | 1 | n<5 |
| render-r1-run1-20261101-204859 | spike2 | cold_start | am_start | 0 | 0 | 1 | n<5 |

带 `error` flag 的行（单行级 LOW_CONFIDENCE，不进 ms 统计）：

（没有 error 行。）

## 8. 解析失败行

- 合计：0 行（`--strict` 时非 0 退出）。

（没有解析失败行。）
