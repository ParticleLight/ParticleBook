# Spike2 基准统计 · render-r2-merged

> 由 `scripts/android/spike2/parse.py` 生成；本文件所有数字均来自解析结果，未做估算。
> 生成时间：2026-11-01T21:16:08+08:00　|　解析失败行：0

## 1. 环境与输入

| 文件 | run_id | app | 总行数 | 记录行 | 解析失败 |
|---|---|---|---|---|---|
| spikes\spike2-results\raw\render-r2-run1-20261101-204959.log | render-r2-run1-20261101-204959 | spike2 | 197 | 41 | 0 |
| spikes\spike2-results\raw\render-r2-run1-20261101-205037.log | render-r2-run1-20261101-205037 | spike2 | 200 | 41 | 0 |
| spikes\spike2-results\raw\render-r2-run1-20261101-205144.log | render-r2-run1-20261101-205144 | spike2 | 217 | 41 | 0 |
| spikes\spike2-results\raw\render-r2-run2-20261101-205107.log | render-r2-run2-20261101-205107 | spike2 | 207 | 41 | 0 |
| spikes\spike2-results\raw\render-r2-run2-20261101-205218.log | render-r2-run2-20261101-205218 | spike2 | 205 | 41 | 0 |

**RUNENV 元数据**

| 文件 | run_id | app | apk_md5 | scenario | ts |
|---|---|---|---|---|---|
| spikes\spike2-results\raw\render-r2-run1-20261101-204959.log | render-r2-run1-20261101-204959 | spike2 | 8ea3edc683ebf0b5be2f32f32dc57bb9 | S4,S5 | 2026-11-01T20:50:07+0800 |
| spikes\spike2-results\raw\render-r2-run1-20261101-205037.log | render-r2-run1-20261101-205037 | spike2 | 8ea3edc683ebf0b5be2f32f32dc57bb9 | S4,S5 | 2026-11-01T20:50:44+0800 |
| spikes\spike2-results\raw\render-r2-run1-20261101-205144.log | render-r2-run1-20261101-205144 | spike2 | 8ea3edc683ebf0b5be2f32f32dc57bb9 | S4,S5 | 2026-11-01T20:51:52+0800 |
| spikes\spike2-results\raw\render-r2-run2-20261101-205107.log | render-r2-run2-20261101-205107 | spike2 | 8ea3edc683ebf0b5be2f32f32dc57bb9 | S4,S5 | 2026-11-01T20:51:11+0800 |
| spikes\spike2-results\raw\render-r2-run2-20261101-205218.log | render-r2-run2-20261101-205218 | spike2 | 8ea3edc683ebf0b5be2f32f32dc57bb9 | S4,S5 | 2026-11-01T20:52:22+0800 |

| 参数 | 值 |
|---|---|
| --app | spike2 |
| --drop-first | 0 |
| --drop-mode | first-per-phase |
| --min-samples | 5 |
| 输入路径 | spikes/spike2-results/raw/render-r2-run1-20261101-204959.log spikes/spike2-results/raw/render-r2-run1-20261101-205037.log spikes/spike2-results/raw/render-r2-run1-20261101-205144.log spikes/spike2-results/raw/render-r2-run2-20261101-205107.log spikes/spike2-results/raw/render-r2-run2-20261101-205218.log |

## 2. 样本构成

| record | CSV 行数 |
|---|---|
| AMSTART | 5 |
| PBDONE | 20 |
| PBPERF | 170 |
| PBTIME | 5 |
| RUNENV | 5 |

- 输入 5 个文件 / 1026 行；识别为本契约记录 205 行；CSV 总计 205 行。
- 参与统计的样本（ms 非空、非 warmup、非 error）：145 行；其中 ms 样本 140 行、pss 样本 15 行。
- warmup：35 行（其中剔除 35 行；drop_mode=first-per-phase，仍保留在 CSV，统计排除）。
- error：0 行（confidence=LOW_CONFIDENCE，不进 ms 统计）。

## 3. ms 统计（跨 run）

> 口径：median=`statistics.median`；p50/p95=nearest-rank（idx=ceil(q*n)-1，1-based）；单位 ms，保留 3 位小数。
> 参与：PBPERF（ms 非空、非 warmup、非 error）、PBPDF、AMSTART、PBTIME；PBDONE/RUNENV 不参与；pss 行见第 5 节。

| app | scenario | phase | n | median | p50 | p95 | max | min | 来源 |
|---|---|---|---|---|---|---|---|---|---|
| spike2 | S1 | open | 5 | 343.120 | 343.120 | 415.786 | 415.786 | 285.956 | spikes\spike2-results\raw\render-r2-run1-20261101-204959.log:119-119(n=1)<br>spikes\spike2-results\raw\render-r2-run1-20261101-205037.log:115-115(n=1)<br>spikes\spike2-results\raw\render-r2-run1-20261101-205144.log:140-140(n=1)<br>spikes\spike2-results\raw\render-r2-run2-20261101-205107.log:124-124(n=1)<br>spikes\spike2-results\raw\render-r2-run2-20261101-205218.log:122-122(n=1) |
| spike2 | S4 | total | 120 | 83.707 | 83.612 | 284.111 | 1070.376 | 42.763 | spikes\spike2-results\raw\render-r2-run1-20261101-204959.log:135-182(n=24)<br>spikes\spike2-results\raw\render-r2-run1-20261101-205037.log:127-184(n=24)<br>spikes\spike2-results\raw\render-r2-run1-20261101-205144.log:169-198(n=24)<br>spikes\spike2-results\raw\render-r2-run2-20261101-205107.log:136-194(n=24)<br>spikes\spike2-results\raw\render-r2-run2-20261101-205218.log:138-186(n=24) |
| spike2 | S5 | text | 15 | 3.023 | 3.023 | 8.681 | 8.681 | 0.484 | spikes\spike2-results\raw\render-r2-run1-20261101-204959.log:186-188(n=3)<br>spikes\spike2-results\raw\render-r2-run1-20261101-205037.log:188-190(n=3)<br>spikes\spike2-results\raw\render-r2-run1-20261101-205144.log:202-204(n=3)<br>spikes\spike2-results\raw\render-r2-run2-20261101-205107.log:198-200(n=3)<br>spikes\spike2-results\raw\render-r2-run2-20261101-205218.log:190-192(n=3) |

## 4. ms 统计（按 run）

| run_id | app | scenario | phase | n | median | p50 | p95 | max | min | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|
| render-r2-run1-20261101-204959 | spike2 | S1 | open | 1 | 371.722 | 371.722 | 371.722 | 371.722 | 371.722 | spikes\spike2-results\raw\render-r2-run1-20261101-204959.log:119-119(n=1) |
| render-r2-run1-20261101-204959 | spike2 | S4 | total | 24 | 87.057 | 83.803 | 323.809 | 783.554 | 56.137 | spikes\spike2-results\raw\render-r2-run1-20261101-204959.log:135-182(n=24) |
| render-r2-run1-20261101-204959 | spike2 | S5 | text | 3 | 0.746 | 0.746 | 5.025 | 5.025 | 0.603 | spikes\spike2-results\raw\render-r2-run1-20261101-204959.log:186-188(n=3) |
| render-r2-run1-20261101-205037 | spike2 | S1 | open | 1 | 308.896 | 308.896 | 308.896 | 308.896 | 308.896 | spikes\spike2-results\raw\render-r2-run1-20261101-205037.log:115-115(n=1) |
| render-r2-run1-20261101-205037 | spike2 | S4 | total | 24 | 83.568 | 82.988 | 272.708 | 741.598 | 66.717 | spikes\spike2-results\raw\render-r2-run1-20261101-205037.log:127-184(n=24) |
| render-r2-run1-20261101-205037 | spike2 | S5 | text | 3 | 4.690 | 4.690 | 7.488 | 7.488 | 0.667 | spikes\spike2-results\raw\render-r2-run1-20261101-205037.log:188-190(n=3) |
| render-r2-run1-20261101-205144 | spike2 | S1 | open | 1 | 415.786 | 415.786 | 415.786 | 415.786 | 415.786 | spikes\spike2-results\raw\render-r2-run1-20261101-205144.log:140-140(n=1) |
| render-r2-run1-20261101-205144 | spike2 | S4 | total | 24 | 76.588 | 76.503 | 212.154 | 1070.376 | 42.763 | spikes\spike2-results\raw\render-r2-run1-20261101-205144.log:169-198(n=24) |
| render-r2-run1-20261101-205144 | spike2 | S5 | text | 3 | 6.369 | 6.369 | 8.681 | 8.681 | 0.889 | spikes\spike2-results\raw\render-r2-run1-20261101-205144.log:202-204(n=3) |
| render-r2-run2-20261101-205107 | spike2 | S1 | open | 1 | 343.120 | 343.120 | 343.120 | 343.120 | 343.120 | spikes\spike2-results\raw\render-r2-run2-20261101-205107.log:124-124(n=1) |
| render-r2-run2-20261101-205107 | spike2 | S4 | total | 24 | 87.508 | 87.302 | 284.111 | 795.830 | 59.213 | spikes\spike2-results\raw\render-r2-run2-20261101-205107.log:136-194(n=24) |
| render-r2-run2-20261101-205107 | spike2 | S5 | text | 3 | 3.023 | 3.023 | 5.766 | 5.766 | 0.955 | spikes\spike2-results\raw\render-r2-run2-20261101-205107.log:198-200(n=3) |
| render-r2-run2-20261101-205218 | spike2 | S1 | open | 1 | 285.956 | 285.956 | 285.956 | 285.956 | 285.956 | spikes\spike2-results\raw\render-r2-run2-20261101-205218.log:122-122(n=1) |
| render-r2-run2-20261101-205218 | spike2 | S4 | total | 24 | 76.971 | 75.375 | 281.936 | 971.857 | 45.041 | spikes\spike2-results\raw\render-r2-run2-20261101-205218.log:138-186(n=24) |
| render-r2-run2-20261101-205218 | spike2 | S5 | text | 3 | 0.757 | 0.757 | 5.286 | 5.286 | 0.484 | spikes\spike2-results\raw\render-r2-run2-20261101-205218.log:190-192(n=3) |

## 5. 内存（PSS，不进 ms 表）

> 单位 KB；每格为 `min / median / max`，n 为该组 pss 行数；`-` 表示该组没有对应分项。

**跨 run**

| app | scenario | n | pss_kb | native_kb | java_kb | 来源 |
|---|---|---|---|---|---|---|
| spike2 | S4 | 5 | 80153.000 / 81733.000 / 82021.000 | 51749.000 / 51808.000 / 51864.000 | 1788.000 / 1792.000 / 1792.000 | spikes\spike2-results\raw\render-r2-run1-20261101-204959.log:183-183(n=1)<br>spikes\spike2-results\raw\render-r2-run1-20261101-205037.log:185-185(n=1)<br>spikes\spike2-results\raw\render-r2-run1-20261101-205144.log:199-199(n=1)<br>spikes\spike2-results\raw\render-r2-run2-20261101-205107.log:195-195(n=1)<br>spikes\spike2-results\raw\render-r2-run2-20261101-205218.log:187-187(n=1) |

**按 run**

| run_id | app | scenario | n | pss_kb | native_kb | java_kb | 来源 |
|---|---|---|---|---|---|---|---|
| render-r2-run1-20261101-204959 | spike2 | S4 | 1 | 80153.000 / 80153.000 / 80153.000 | 51749.000 / 51749.000 / 51749.000 | 1792.000 / 1792.000 / 1792.000 | spikes\spike2-results\raw\render-r2-run1-20261101-204959.log:183-183(n=1) |
| render-r2-run1-20261101-205037 | spike2 | S4 | 1 | 82021.000 / 82021.000 / 82021.000 | 51808.000 / 51808.000 / 51808.000 | 1792.000 / 1792.000 / 1792.000 | spikes\spike2-results\raw\render-r2-run1-20261101-205037.log:185-185(n=1) |
| render-r2-run1-20261101-205144 | spike2 | S4 | 1 | 80314.000 / 80314.000 / 80314.000 | 51795.000 / 51795.000 / 51795.000 | 1788.000 / 1788.000 / 1788.000 | spikes\spike2-results\raw\render-r2-run1-20261101-205144.log:199-199(n=1) |
| render-r2-run2-20261101-205107 | spike2 | S4 | 1 | 81899.000 / 81899.000 / 81899.000 | 51864.000 / 51864.000 / 51864.000 | 1792.000 / 1792.000 / 1792.000 | spikes\spike2-results\raw\render-r2-run2-20261101-205107.log:195-195(n=1) |
| render-r2-run2-20261101-205218 | spike2 | S4 | 1 | 81733.000 / 81733.000 / 81733.000 | 51810.000 / 51810.000 / 51810.000 | 1792.000 / 1792.000 / 1792.000 | spikes\spike2-results\raw\render-r2-run2-20261101-205218.log:187-187(n=1) |

## 6. 缺 PBDONE 的 scenario

> 同一 run_id 内没有对应 `PBDONE|scenario=<scenario>`（`scenario=ALL` 视为覆盖该 run 全部 scenario）。

（所有 scenario 都有对应 PBDONE。）

## 7. LOW_CONFIDENCE 组

> 组 (run_id, app, scenario, phase) 的有效样本数 < --min-samples 时，该组所有行 confidence=LOW_CONFIDENCE 且 flags 含 `n<5`。

| run_id | app | scenario | phase | n | error 行 | warmup 行 | 原因 |
|---|---|---|---|---|---|---|---|
| render-r2-run1-20261101-204959 | spike2 | PBTIME | first_frame | 0 | 0 | 1 | n<5 |
| render-r2-run1-20261101-204959 | spike2 | S1 | open | 1 | 0 | 1 | n<5 |
| render-r2-run1-20261101-204959 | spike2 | S4 | pss | 1 | 0 | 1 | n<5 |
| render-r2-run1-20261101-204959 | spike2 | S5 | text | 3 | 0 | 1 | n<5 |
| render-r2-run1-20261101-204959 | spike2 | S7 | pss | 0 | 0 | 1 | n<5 |
| render-r2-run1-20261101-204959 | spike2 | cold_start | am_start | 0 | 0 | 1 | n<5 |
| render-r2-run1-20261101-205037 | spike2 | PBTIME | first_frame | 0 | 0 | 1 | n<5 |
| render-r2-run1-20261101-205037 | spike2 | S1 | open | 1 | 0 | 1 | n<5 |
| render-r2-run1-20261101-205037 | spike2 | S4 | pss | 1 | 0 | 1 | n<5 |
| render-r2-run1-20261101-205037 | spike2 | S5 | text | 3 | 0 | 1 | n<5 |
| render-r2-run1-20261101-205037 | spike2 | S7 | pss | 0 | 0 | 1 | n<5 |
| render-r2-run1-20261101-205037 | spike2 | cold_start | am_start | 0 | 0 | 1 | n<5 |
| render-r2-run1-20261101-205144 | spike2 | PBTIME | first_frame | 0 | 0 | 1 | n<5 |
| render-r2-run1-20261101-205144 | spike2 | S1 | open | 1 | 0 | 1 | n<5 |
| render-r2-run1-20261101-205144 | spike2 | S4 | pss | 1 | 0 | 1 | n<5 |
| render-r2-run1-20261101-205144 | spike2 | S5 | text | 3 | 0 | 1 | n<5 |
| render-r2-run1-20261101-205144 | spike2 | S7 | pss | 0 | 0 | 1 | n<5 |
| render-r2-run1-20261101-205144 | spike2 | cold_start | am_start | 0 | 0 | 1 | n<5 |
| render-r2-run2-20261101-205107 | spike2 | PBTIME | first_frame | 0 | 0 | 1 | n<5 |
| render-r2-run2-20261101-205107 | spike2 | S1 | open | 1 | 0 | 1 | n<5 |
| render-r2-run2-20261101-205107 | spike2 | S4 | pss | 1 | 0 | 1 | n<5 |
| render-r2-run2-20261101-205107 | spike2 | S5 | text | 3 | 0 | 1 | n<5 |
| render-r2-run2-20261101-205107 | spike2 | S7 | pss | 0 | 0 | 1 | n<5 |
| render-r2-run2-20261101-205107 | spike2 | cold_start | am_start | 0 | 0 | 1 | n<5 |
| render-r2-run2-20261101-205218 | spike2 | PBTIME | first_frame | 0 | 0 | 1 | n<5 |
| render-r2-run2-20261101-205218 | spike2 | S1 | open | 1 | 0 | 1 | n<5 |
| render-r2-run2-20261101-205218 | spike2 | S4 | pss | 1 | 0 | 1 | n<5 |
| render-r2-run2-20261101-205218 | spike2 | S5 | text | 3 | 0 | 1 | n<5 |
| render-r2-run2-20261101-205218 | spike2 | S7 | pss | 0 | 0 | 1 | n<5 |
| render-r2-run2-20261101-205218 | spike2 | cold_start | am_start | 0 | 0 | 1 | n<5 |

带 `error` flag 的行（单行级 LOW_CONFIDENCE，不进 ms 统计）：

（没有 error 行。）

## 8. 解析失败行

- 合计：0 行（`--strict` 时非 0 退出）。

（没有解析失败行。）
