# Spike2 汇总 · render-smoke

> 由 scripts/android/spike2/summarize.py 从 parse.py 的 CSV 汇总；所有数字可由 raw logcat 重算（CSV 的 src_file/src_line 指回 raw 行号）。
> 生成时间：2026-11-01T20:11:23+08:00　|　真机推算系数 x1.80（[推测]，PDFium 纯 CPU 光栅，真机/模拟器约 1.4-2.5）
> 口径：median=statistics.median；p95=nearest-rank（idx=ceil(q*n)-1）；单位 ms。

## 1. 输入与环境

| CSV | CSV 行数 | 参与统计行 | run 数 | apps |
|---|---|---|---|---|
| F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\csv\render-smoke-merged.csv | 75 | 75 | 1 | spike2 |

**RUNENV 环境头**

| run_id | app | apk | md5 | api | model | abi | build | ts | 来源 |
|---|---|---|---|---|---|---|---|---|---|
| render-smoke-run1-20261101-201032 | spike2 | spikes/spike2-pdf/app/build/outputs/apk/debug/app-debug.apk | b844a40431795edd0bba9dc1ec1b0773 | 35 | Android_SDK_built_for_x86_64 | x86_64 | 12368160 | 2026-11-01T20:10:45+0800 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1 |

## 2. ms 统计（跨 run，冷热混合，仅总览）

| app | scenario | phase | dims | n | min | p50 | p95 | max | 真机推算 p50 | run 数 | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| spike2 | PBTIME | first_frame | - | 1 | 2338.892 | 2338.892 | 2338.892 | 2338.892 | 4210.0 [推测] | 1 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1477-1477 |
| spike2 | PBTIME | first_screen | - | 1 | 5350.925 | 5350.925 | 5350.925 | 5350.925 | 9631.7 [推测] | 1 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1521-1521 |
| spike2 | PBTIME | open_to_first_screen | - | 1 | 3712.824 | 3712.824 | 3712.824 | 3712.824 | 6683.1 [推测] | 1 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1522-1522 |
| spike2 | S1 | open | - | 2 | 996.607 | 1411.215 | 1825.822 | 1825.822 | 2540.2 [推测] | 1 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1479-1491 |
| spike2 | S10 | check | 1080x1529,1080x2400 | 2 | 0.000 | 0.000 | 0.000 | 0.000 | 0.0 [推测] | 1 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1609-1620 |
| spike2 | S10 | first_frame | 1080x2400 | 1 | 49.979 | 49.979 | 49.979 | 49.979 | 90.0 [推测] | 1 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1596-1596 |
| spike2 | S10 | present | 1080x1529,1080x2400 | 9 | 31.612 | 42.332 | 72.428 | 72.428 | 76.2 [推测] | 1 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1598-1619 |
| spike2 | S10 | render | 1080x1529,1080x2400 | 10 | 184.056 | 229.332 | 936.474 | 936.474 | 412.8 [推测] | 1 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1595-1618 |
| spike2 | S2 | present | 1188x1682 | 20 | 27.602 | 57.810 | 98.630 | 105.296 | 104.1 [推测] | 1 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1518-1573 |
| spike2 | S2 | render | 1188x1682 | 20 | 28.732 | 129.256 | 485.376 | 596.391 | 232.7 [推测] | 1 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1515-1572 |

> 第 2 节把 cold/warm 混在一起，只用于总览；跨 app / 跨路径对比必须用第 3 节的同冷热分组。

## 3. 冷/热分组（跨 app 对比只允许同状态）

> cold = 同页同尺寸第一次渲染；warm = 之后。派生自 extra 的 cold:1 / cold:0（或 temp:）。

| app | scenario | phase | 状态 | n | min | p50 | p95 | max | 真机推算 p50 | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|
| spike2 | S2 | present | cold | 4 | 42.206 | 79.263 | 105.296 | 105.296 | 142.7 [推测] | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1518-1563 |
| spike2 | S2 | present | warm | 16 | 27.602 | 57.600 | 91.875 | 91.875 | 103.7 [推测] | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1524-1573 |
| spike2 | S2 | render | cold | 4 | 32.264 | 105.729 | 596.391 | 596.391 | 190.3 [推测] | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1515-1562 |
| spike2 | S2 | render | warm | 16 | 28.732 | 129.256 | 485.376 | 485.376 | 232.7 [推测] | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1523-1572 |

## 4. renderAnnot 分组（A/B）

> 派生自 extra 的 annot:1 / annot:0（或 renderAnnot:）。1=走注解/文本层路径，0=不走。

| app | scenario | phase | annot | n | p50 | p95 | max | 来源 |
|---|---|---|---|---|---|---|---|---|
| spike2 | S10 | render | 0 | 10 | 229.332 | 936.474 | 936.474 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1595-1618 |
| spike2 | S2 | render | 1 | 20 | 129.256 | 485.376 | 596.391 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1515-1572 |

## 5. 漂移哨兵

> 参照组：scenario=S2 phase=render page=0。比较最早与最晚 run 的中位数；差 > 20% 标 DRIFT_WARN。

| # | run_id | app | n | median | avg | ts | 来源 |
|---|---|---|---|---|---|---|---|
| 1 | render-smoke-run1-20261101-201032 | spike2 | 5 | 466.012 | 487.205 | 11-01 12:10:54.471 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1515-1530 |

- 只有 1 个 run，无法判断漂移（至少 2 个 run 才有哨兵意义）。

## 6. LOW_CONFIDENCE

> 组样本数 < 5。只作方向参考，不能写进结论。

| app | scenario | phase | n | p50 | 来源 |
|---|---|---|---|---|---|
| spike2 | PBTIME | first_frame | 1 | 2338.892 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1477-1477 |
| spike2 | PBTIME | first_screen | 1 | 5350.925 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1521-1521 |
| spike2 | PBTIME | open_to_first_screen | 1 | 3712.824 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1522-1522 |
| spike2 | S1 | open | 2 | 1411.215 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1479-1491 |
| spike2 | S10 | check | 2 | 0.000 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1609-1620 |
| spike2 | S10 | first_frame | 1 | 49.979 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1596-1596 |

## 7. 缺 cold/warm 标注的 render 记录

> 协议要求每条 render 记录带 cold/warm；以下记录缺失，不能与带标注数据混用。

| run_id | app | scenario | phase | n | 来源 |
|---|---|---|---|---|---|
| render-smoke-run1-20261101-201032 | spike2 | S10 | render | 10 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke-run1-20261101-201032.log:1595-1618 |

## 8. 口径与限制

- 环境：Android emulator（x86_64/API35/1080x2400/dpi420/swiftshader/WHPX）。
- 绝对值只用于同批次对比；跨批次只报中位数与相对差（模拟器漂移 30-50%）。
- [推测] 列 = 模拟器 p50 x 1.80，仅用于给真机量级预期，不是实测。
- 失败/异常不测不报：extra=error: 行不参与统计。

