# Spike2 基准统计 · render-smoke-merged

> 由 `scripts/android/spike2/parse.py` 生成；本文件所有数字均来自解析结果，未做估算。
> 生成时间：2026-11-01T20:11:22+08:00　|　解析失败行：0

## 1. 环境与输入

| 文件 | run_id | app | 总行数 | 记录行 | 解析失败 |
|---|---|---|---|---|---|
| F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log | render-smoke-run1-20261101-201032 | spike2 | 1959 | 75 | 0 |

**RUNENV 元数据**

| 文件 | run_id | app | apk_md5 | scenario | ts |
|---|---|---|---|---|---|
| F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log | render-smoke-run1-20261101-201032 | spike2 | b844a40431795edd0bba9dc1ec1b0773 | S1,S2,S8,S10 | 2026-11-01T20:10:45+0800 |

| 参数 | 值 |
|---|---|
| --app | spike2 |
| --drop-first | 0 |
| --drop-mode | none |
| --min-samples | 5 |
| 输入路径 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-smoke-run1-20261101-201032.log |

## 2. 样本构成

| record | CSV 行数 |
|---|---|
| PBDONE | 5 |
| PBPERF | 66 |
| PBTIME | 3 |
| RUNENV | 1 |

- 输入 1 个文件 / 1959 行；识别为本契约记录 75 行；CSV 总计 75 行。
- 参与统计的样本（ms 非空、非 warmup、非 error）：68 行；其中 ms 样本 67 行、pss 样本 1 行。
- warmup：0 行（其中剔除 0 行；drop_mode=none，仍保留在 CSV，统计排除）。
- error：1 行（confidence=LOW_CONFIDENCE，不进 ms 统计）。

## 3. ms 统计（跨 run）

> 口径：median=`statistics.median`；p50/p95=nearest-rank（idx=ceil(q*n)-1，1-based）；单位 ms，保留 3 位小数。
> 参与：PBPERF（ms 非空、非 warmup、非 error）、PBPDF、AMSTART、PBTIME；PBDONE/RUNENV 不参与；pss 行见第 5 节。

| app | scenario | phase | n | median | p50 | p95 | max | min | 来源 |
|---|---|---|---|---|---|---|---|---|---|
| spike2 | PBTIME | first_frame | 1 | 2338.892 | 2338.892 | 2338.892 | 2338.892 | 2338.892 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1477-1477(n=1) |
| spike2 | PBTIME | first_screen | 1 | 5350.925 | 5350.925 | 5350.925 | 5350.925 | 5350.925 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1521-1521(n=1) |
| spike2 | PBTIME | open_to_first_screen | 1 | 3712.824 | 3712.824 | 3712.824 | 3712.824 | 3712.824 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1522-1522(n=1) |
| spike2 | S1 | open | 2 | 1411.215 | 996.607 | 1825.822 | 1825.822 | 996.607 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1479-1491(n=2) |
| spike2 | S10 | check | 2 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1609-1620(n=2) |
| spike2 | S10 | first_frame | 1 | 49.979 | 49.979 | 49.979 | 49.979 | 49.979 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1596-1596(n=1) |
| spike2 | S10 | present | 9 | 42.332 | 42.332 | 72.428 | 72.428 | 31.612 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1598-1619(n=9) |
| spike2 | S10 | render | 10 | 229.332 | 226.127 | 936.474 | 936.474 | 184.056 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1595-1618(n=10) |
| spike2 | S2 | present | 20 | 57.810 | 57.684 | 98.630 | 105.296 | 27.602 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1518-1573(n=20) |
| spike2 | S2 | render | 20 | 129.256 | 99.108 | 485.376 | 596.391 | 28.732 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1515-1572(n=20) |

## 4. ms 统计（按 run）

| run_id | app | scenario | phase | n | median | p50 | p95 | max | min | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|
| render-smoke-run1-20261101-201032 | spike2 | PBTIME | first_frame | 1 | 2338.892 | 2338.892 | 2338.892 | 2338.892 | 2338.892 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1477-1477(n=1) |
| render-smoke-run1-20261101-201032 | spike2 | PBTIME | first_screen | 1 | 5350.925 | 5350.925 | 5350.925 | 5350.925 | 5350.925 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1521-1521(n=1) |
| render-smoke-run1-20261101-201032 | spike2 | PBTIME | open_to_first_screen | 1 | 3712.824 | 3712.824 | 3712.824 | 3712.824 | 3712.824 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1522-1522(n=1) |
| render-smoke-run1-20261101-201032 | spike2 | S1 | open | 2 | 1411.215 | 996.607 | 1825.822 | 1825.822 | 996.607 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1479-1491(n=2) |
| render-smoke-run1-20261101-201032 | spike2 | S10 | check | 2 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1609-1620(n=2) |
| render-smoke-run1-20261101-201032 | spike2 | S10 | first_frame | 1 | 49.979 | 49.979 | 49.979 | 49.979 | 49.979 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1596-1596(n=1) |
| render-smoke-run1-20261101-201032 | spike2 | S10 | present | 9 | 42.332 | 42.332 | 72.428 | 72.428 | 31.612 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1598-1619(n=9) |
| render-smoke-run1-20261101-201032 | spike2 | S10 | render | 10 | 229.332 | 226.127 | 936.474 | 936.474 | 184.056 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1595-1618(n=10) |
| render-smoke-run1-20261101-201032 | spike2 | S2 | present | 20 | 57.810 | 57.684 | 98.630 | 105.296 | 27.602 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1518-1573(n=20) |
| render-smoke-run1-20261101-201032 | spike2 | S2 | render | 20 | 129.256 | 99.108 | 485.376 | 596.391 | 28.732 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1515-1572(n=20) |

## 5. 内存（PSS，不进 ms 表）

> 单位 KB；每格为 `min / median / max`，n 为该组 pss 行数；`-` 表示该组没有对应分项。

**跨 run**

| app | scenario | n | pss_kb | native_kb | java_kb | 来源 |
|---|---|---|---|---|---|---|
| spike2 | S7 | 1 | 22797.000 / 22797.000 / 22797.000 | 3906.000 / 3906.000 / 3906.000 | 689.000 / 689.000 / 689.000 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1463-1463(n=1) |

**按 run**

| run_id | app | scenario | n | pss_kb | native_kb | java_kb | 来源 |
|---|---|---|---|---|---|---|---|
| render-smoke-run1-20261101-201032 | spike2 | S7 | 1 | 22797.000 / 22797.000 / 22797.000 | 3906.000 / 3906.000 / 3906.000 | 689.000 / 689.000 / 689.000 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1463-1463(n=1) |

## 6. 缺 PBDONE 的 scenario

> 同一 run_id 内没有对应 `PBDONE|scenario=<scenario>`（`scenario=ALL` 视为覆盖该 run 全部 scenario）。

（所有 scenario 都有对应 PBDONE。）

## 7. LOW_CONFIDENCE 组

> 组 (run_id, app, scenario, phase) 的有效样本数 < --min-samples 时，该组所有行 confidence=LOW_CONFIDENCE 且 flags 含 `n<5`。

| run_id | app | scenario | phase | n | error 行 | warmup 行 | 原因 |
|---|---|---|---|---|---|---|---|
| render-smoke-run1-20261101-201032 | spike2 | PBTIME | first_frame | 1 | 0 | 0 | n<5 |
| render-smoke-run1-20261101-201032 | spike2 | PBTIME | first_screen | 1 | 0 | 0 | n<5 |
| render-smoke-run1-20261101-201032 | spike2 | PBTIME | open_to_first_screen | 1 | 0 | 0 | n<5 |
| render-smoke-run1-20261101-201032 | spike2 | S1 | open | 2 | 0 | 0 | n<5 |
| render-smoke-run1-20261101-201032 | spike2 | S10 | check | 2 | 0 | 0 | n<5 |
| render-smoke-run1-20261101-201032 | spike2 | S10 | first_frame | 1 | 0 | 0 | n<5 |
| render-smoke-run1-20261101-201032 | spike2 | S7 | pss | 1 | 0 | 0 | n<5 |
| render-smoke-run1-20261101-201032 | spike2 | S8 | fatal | 0 | 1 | 0 | n<5 |

带 `error` flag 的行（单行级 LOW_CONFIDENCE，不进 ms 统计）：

| 文件:行 | run_id | scenario | phase | extra |
|---|---|---|---|---|
| F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1591 | render-smoke-run1-20261101-201032 | S8 | fatal | error:IllegalArgumentException |

## 8. 解析失败行

- 合计：0 行（`--strict` 时非 0 退出）。

（没有解析失败行。）
