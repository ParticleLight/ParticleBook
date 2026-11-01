# Spike2 基准统计 · tiles-devcheck

> 由 `scripts/android/spike2/parse.py` 生成；本文件所有数字均来自解析结果，未做估算。
> 生成时间：2026-11-01T20:16:38+08:00　|　解析失败行：0

## 1. 环境与输入

| 文件 | run_id | app | 总行数 | 记录行 | 解析失败 |
|---|---|---|---|---|---|
| spikes\spike2-results\raw\tiles-dev-20261101-193520.log | tiles-dev-20261101-193520 | spike2t | 110 | 109 | 0 |
| spikes\spike2-results\raw\tiles-dev-20261101-193349.log | tiles-dev-20261101-193349 | spike2t | 46 | 45 | 0 |

**RUNENV 元数据**

（输入中没有 RUNENV 行；run_id 取文件名 stem，app 取文件名前缀映射或 unknown。）

| 参数 | 值 |
|---|---|
| --app | spike2t |
| --drop-first | 0 |
| --drop-mode | none |
| --min-samples | 5 |
| 输入路径 | spikes/spike2-results/raw/tiles-dev-20261101-193520.log spikes/spike2-results/raw/tiles-dev-20261101-193349.log |

## 2. 样本构成

| record | CSV 行数 |
|---|---|
| PBDONE | 3 |
| PBPERF | 146 |
| PBTIME | 5 |

- 输入 2 个文件 / 156 行；识别为本契约记录 154 行；CSV 总计 154 行。
- 参与统计的样本（ms 非空、非 warmup、非 error）：151 行；其中 ms 样本 150 行、pss 样本 1 行。
- warmup：0 行（其中剔除 0 行；drop_mode=none，仍保留在 CSV，统计排除）。
- error：0 行（confidence=LOW_CONFIDENCE，不进 ms 统计）。

## 3. ms 统计（跨 run）

> 口径：median=`statistics.median`；p50/p95=nearest-rank（idx=ceil(q*n)-1，1-based）；单位 ms，保留 3 位小数。
> 参与：PBPERF（ms 非空、非 warmup、非 error）、PBPDF、AMSTART、PBTIME；PBDONE/RUNENV 不参与；pss 行见第 5 节。

| app | scenario | phase | n | median | p50 | p95 | max | min | 来源 |
|---|---|---|---|---|---|---|---|---|---|
| spike2t | PBTIME | first_frame | 2 | 2709.037 | 1883.409 | 3534.665 | 3534.665 | 1883.409 | spikes\spike2-results\raw\tiles-dev-20261101-193349.log:3-3(n=1)<br>spikes\spike2-results\raw\tiles-dev-20261101-193520.log:3-3(n=1) |
| spike2t | PBTIME | read_path | 1 | 2714.282 | 2714.282 | 2714.282 | 2714.282 | 2714.282 | spikes\spike2-results\raw\tiles-dev-20261101-193349.log:15-15(n=1) |
| spike2t | PBTIME | refresh_hz | 2 | 60.000 | 60.000 | 60.000 | 60.000 | 60.000 | spikes\spike2-results\raw\tiles-dev-20261101-193349.log:2-2(n=1)<br>spikes\spike2-results\raw\tiles-dev-20261101-193520.log:2-2(n=1) |
| spike2t | T1 | first_screen | 1 | 2074.532 | 2074.532 | 2074.532 | 2074.532 | 2074.532 | spikes\spike2-results\raw\tiles-dev-20261101-193349.log:14-14(n=1) |
| spike2t | T1 | lowres | 2 | 174.156 | 30.615 | 317.697 | 317.697 | 30.615 | spikes\spike2-results\raw\tiles-dev-20261101-193349.log:18-18(n=1)<br>spikes\spike2-results\raw\tiles-dev-20261101-193520.log:6-6(n=1) |
| spike2t | T1 | open | 2 | 514.447 | 389.144 | 639.751 | 639.751 | 389.144 | spikes\spike2-results\raw\tiles-dev-20261101-193349.log:4-4(n=1)<br>spikes\spike2-results\raw\tiles-dev-20261101-193520.log:4-4(n=1) |
| spike2t | T1 | tile | 9 | 27.927 | 27.927 | 523.456 | 523.456 | 0.301 | spikes\spike2-results\raw\tiles-dev-20261101-193349.log:5-13(n=9) |
| spike2t | T1 | tileprobe | 2 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | spikes\spike2-results\raw\tiles-dev-20261101-193349.log:17-17(n=1)<br>spikes\spike2-results\raw\tiles-dev-20261101-193520.log:5-5(n=1) |
| spike2t | T2 | full | 1 | 142.869 | 142.869 | 142.869 | 142.869 | 142.869 | spikes\spike2-results\raw\tiles-dev-20261101-193520.log:96-96(n=1) |
| spike2t | T2 | full_curve | 10 | 100.829 | 100.660 | 131.882 | 131.882 | 35.090 | spikes\spike2-results\raw\tiles-dev-20261101-193520.log:98-107(n=10) |
| spike2t | T2 | full_ref | 3 | 237.138 | 237.138 | 462.058 | 462.058 | 205.327 | spikes\spike2-results\raw\tiles-dev-20261101-193520.log:78-94(n=3) |
| spike2t | T2 | overload_probe | 1 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | spikes\spike2-results\raw\tiles-dev-20261101-193520.log:42-42(n=1) |
| spike2t | T2 | tile | 96 | 99.207 | 95.471 | 497.131 | 625.273 | 13.468 | spikes\spike2-results\raw\tiles-dev-20261101-193349.log:19-45(n=24)<br>spikes\spike2-results\raw\tiles-dev-20261101-193520.log:7-92(n=72) |
| spike2t | T2 | tile_overhead | 1 | -1.089 | -1.089 | -1.089 | -1.089 | -1.089 | spikes\spike2-results\raw\tiles-dev-20261101-193520.log:97-97(n=1) |
| spike2t | T2 | tile_stat | 16 | 110.581 | 109.891 | 406.638 | 406.638 | 26.667 | spikes\spike2-results\raw\tiles-dev-20261101-193349.log:25-46(n=4)<br>spikes\spike2-results\raw\tiles-dev-20261101-193520.log:13-93(n=12) |
| spike2t | T2 | tiles_serial | 1 | 141.780 | 141.780 | 141.780 | 141.780 | 141.780 | spikes\spike2-results\raw\tiles-dev-20261101-193520.log:95-95(n=1) |

## 4. ms 统计（按 run）

| run_id | app | scenario | phase | n | median | p50 | p95 | max | min | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|
| tiles-dev-20261101-193349 | spike2t | PBTIME | first_frame | 1 | 3534.665 | 3534.665 | 3534.665 | 3534.665 | 3534.665 | spikes\spike2-results\raw\tiles-dev-20261101-193349.log:3-3(n=1) |
| tiles-dev-20261101-193349 | spike2t | PBTIME | read_path | 1 | 2714.282 | 2714.282 | 2714.282 | 2714.282 | 2714.282 | spikes\spike2-results\raw\tiles-dev-20261101-193349.log:15-15(n=1) |
| tiles-dev-20261101-193349 | spike2t | PBTIME | refresh_hz | 1 | 60.000 | 60.000 | 60.000 | 60.000 | 60.000 | spikes\spike2-results\raw\tiles-dev-20261101-193349.log:2-2(n=1) |
| tiles-dev-20261101-193349 | spike2t | T1 | first_screen | 1 | 2074.532 | 2074.532 | 2074.532 | 2074.532 | 2074.532 | spikes\spike2-results\raw\tiles-dev-20261101-193349.log:14-14(n=1) |
| tiles-dev-20261101-193349 | spike2t | T1 | lowres | 1 | 317.697 | 317.697 | 317.697 | 317.697 | 317.697 | spikes\spike2-results\raw\tiles-dev-20261101-193349.log:18-18(n=1) |
| tiles-dev-20261101-193349 | spike2t | T1 | open | 1 | 639.751 | 639.751 | 639.751 | 639.751 | 639.751 | spikes\spike2-results\raw\tiles-dev-20261101-193349.log:4-4(n=1) |
| tiles-dev-20261101-193349 | spike2t | T1 | tile | 9 | 27.927 | 27.927 | 523.456 | 523.456 | 0.301 | spikes\spike2-results\raw\tiles-dev-20261101-193349.log:5-13(n=9) |
| tiles-dev-20261101-193349 | spike2t | T1 | tileprobe | 1 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | spikes\spike2-results\raw\tiles-dev-20261101-193349.log:17-17(n=1) |
| tiles-dev-20261101-193349 | spike2t | T2 | tile | 24 | 120.888 | 95.471 | 529.284 | 625.273 | 25.642 | spikes\spike2-results\raw\tiles-dev-20261101-193349.log:19-45(n=24) |
| tiles-dev-20261101-193349 | spike2t | T2 | tile_stat | 4 | 120.199 | 111.271 | 406.638 | 406.638 | 74.450 | spikes\spike2-results\raw\tiles-dev-20261101-193349.log:25-46(n=4) |
| tiles-dev-20261101-193520 | spike2t | PBTIME | first_frame | 1 | 1883.409 | 1883.409 | 1883.409 | 1883.409 | 1883.409 | spikes\spike2-results\raw\tiles-dev-20261101-193520.log:3-3(n=1) |
| tiles-dev-20261101-193520 | spike2t | PBTIME | refresh_hz | 1 | 60.000 | 60.000 | 60.000 | 60.000 | 60.000 | spikes\spike2-results\raw\tiles-dev-20261101-193520.log:2-2(n=1) |
| tiles-dev-20261101-193520 | spike2t | T1 | lowres | 1 | 30.615 | 30.615 | 30.615 | 30.615 | 30.615 | spikes\spike2-results\raw\tiles-dev-20261101-193520.log:6-6(n=1) |
| tiles-dev-20261101-193520 | spike2t | T1 | open | 1 | 389.144 | 389.144 | 389.144 | 389.144 | 389.144 | spikes\spike2-results\raw\tiles-dev-20261101-193520.log:4-4(n=1) |
| tiles-dev-20261101-193520 | spike2t | T1 | tileprobe | 1 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | spikes\spike2-results\raw\tiles-dev-20261101-193520.log:5-5(n=1) |
| tiles-dev-20261101-193520 | spike2t | T2 | full | 1 | 142.869 | 142.869 | 142.869 | 142.869 | 142.869 | spikes\spike2-results\raw\tiles-dev-20261101-193520.log:96-96(n=1) |
| tiles-dev-20261101-193520 | spike2t | T2 | full_curve | 10 | 100.829 | 100.660 | 131.882 | 131.882 | 35.090 | spikes\spike2-results\raw\tiles-dev-20261101-193520.log:98-107(n=10) |
| tiles-dev-20261101-193520 | spike2t | T2 | full_ref | 3 | 237.138 | 237.138 | 462.058 | 462.058 | 205.327 | spikes\spike2-results\raw\tiles-dev-20261101-193520.log:78-94(n=3) |
| tiles-dev-20261101-193520 | spike2t | T2 | overload_probe | 1 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | spikes\spike2-results\raw\tiles-dev-20261101-193520.log:42-42(n=1) |
| tiles-dev-20261101-193520 | spike2t | T2 | tile | 72 | 98.386 | 93.830 | 497.005 | 601.532 | 13.468 | spikes\spike2-results\raw\tiles-dev-20261101-193520.log:7-92(n=72) |
| tiles-dev-20261101-193520 | spike2t | T2 | tile_overhead | 1 | -1.089 | -1.089 | -1.089 | -1.089 | -1.089 | spikes\spike2-results\raw\tiles-dev-20261101-193520.log:97-97(n=1) |
| tiles-dev-20261101-193520 | spike2t | T2 | tile_stat | 12 | 97.575 | 85.259 | 363.538 | 363.538 | 26.667 | spikes\spike2-results\raw\tiles-dev-20261101-193520.log:13-93(n=12) |
| tiles-dev-20261101-193520 | spike2t | T2 | tiles_serial | 1 | 141.780 | 141.780 | 141.780 | 141.780 | 141.780 | spikes\spike2-results\raw\tiles-dev-20261101-193520.log:95-95(n=1) |

## 5. 内存（PSS，不进 ms 表）

> 单位 KB；每格为 `min / median / max`，n 为该组 pss 行数；`-` 表示该组没有对应分项。

**跨 run**

| app | scenario | n | pss_kb | native_kb | java_kb | 来源 |
|---|---|---|---|---|---|---|
| spike2t | ALL | 1 | 58735.000 / 58735.000 / 58735.000 | 29654.000 / 29654.000 / 29654.000 | 832.000 / 832.000 / 832.000 | spikes\spike2-results\raw\tiles-dev-20261101-193520.log:109-109(n=1) |

**按 run**

| run_id | app | scenario | n | pss_kb | native_kb | java_kb | 来源 |
|---|---|---|---|---|---|---|---|
| tiles-dev-20261101-193520 | spike2t | ALL | 1 | 58735.000 / 58735.000 / 58735.000 | 29654.000 / 29654.000 / 29654.000 | 832.000 / 832.000 / 832.000 | spikes\spike2-results\raw\tiles-dev-20261101-193520.log:109-109(n=1) |

## 6. 缺 PBDONE 的 scenario

> 同一 run_id 内没有对应 `PBDONE|scenario=<scenario>`（`scenario=ALL` 视为覆盖该 run 全部 scenario）。

| run_id | app | scenario | 影响行数 |
|---|---|---|---|
| tiles-dev-20261101-193349 | spike2t | PBTIME | 3 |
| tiles-dev-20261101-193349 | spike2t | T2 | 28 |

## 7. LOW_CONFIDENCE 组

> 组 (run_id, app, scenario, phase) 的有效样本数 < --min-samples 时，该组所有行 confidence=LOW_CONFIDENCE 且 flags 含 `n<5`。

| run_id | app | scenario | phase | n | error 行 | warmup 行 | 原因 |
|---|---|---|---|---|---|---|---|
| tiles-dev-20261101-193349 | spike2t | PBTIME | first_frame | 1 | 0 | 0 | n<5 |
| tiles-dev-20261101-193349 | spike2t | PBTIME | read_path | 1 | 0 | 0 | n<5 |
| tiles-dev-20261101-193349 | spike2t | PBTIME | refresh_hz | 1 | 0 | 0 | n<5 |
| tiles-dev-20261101-193349 | spike2t | T1 | first_screen | 1 | 0 | 0 | n<5 |
| tiles-dev-20261101-193349 | spike2t | T1 | lowres | 1 | 0 | 0 | n<5 |
| tiles-dev-20261101-193349 | spike2t | T1 | open | 1 | 0 | 0 | n<5 |
| tiles-dev-20261101-193349 | spike2t | T1 | tileprobe | 1 | 0 | 0 | n<5 |
| tiles-dev-20261101-193349 | spike2t | T2 | tile_stat | 4 | 0 | 0 | n<5 |
| tiles-dev-20261101-193520 | spike2t | ALL | pss | 1 | 0 | 0 | n<5 |
| tiles-dev-20261101-193520 | spike2t | PBTIME | first_frame | 1 | 0 | 0 | n<5 |
| tiles-dev-20261101-193520 | spike2t | PBTIME | refresh_hz | 1 | 0 | 0 | n<5 |
| tiles-dev-20261101-193520 | spike2t | T1 | lowres | 1 | 0 | 0 | n<5 |
| tiles-dev-20261101-193520 | spike2t | T1 | open | 1 | 0 | 0 | n<5 |
| tiles-dev-20261101-193520 | spike2t | T1 | tileprobe | 1 | 0 | 0 | n<5 |
| tiles-dev-20261101-193520 | spike2t | T2 | full | 1 | 0 | 0 | n<5 |
| tiles-dev-20261101-193520 | spike2t | T2 | full_ref | 3 | 0 | 0 | n<5 |
| tiles-dev-20261101-193520 | spike2t | T2 | overload_probe | 1 | 0 | 0 | n<5 |
| tiles-dev-20261101-193520 | spike2t | T2 | tile_overhead | 1 | 0 | 0 | n<5 |
| tiles-dev-20261101-193520 | spike2t | T2 | tiles_serial | 1 | 0 | 0 | n<5 |

带 `error` flag 的行（单行级 LOW_CONFIDENCE，不进 ms 统计）：

（没有 error 行。）

## 8. 解析失败行

- 合计：0 行（`--strict` 时非 0 退出）。

（没有解析失败行。）
