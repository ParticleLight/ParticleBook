# Spike2 汇总 · render-r3

> 由 scripts/android/spike2/summarize.py 从 parse.py 的 CSV 汇总；所有数字可由 raw logcat 重算（CSV 的 src_file/src_line 指回 raw 行号）。
> 生成时间：2026-11-01T21:07:24+08:00　|　真机推算系数 x1.80（[推测]，PDFium 纯 CPU 光栅，真机/模拟器约 1.4-2.5）
> 口径：median=statistics.median；p95=nearest-rank（idx=ceil(q*n)-1）；单位 ms。

## 1. 输入与环境

| CSV | CSV 行数 | 参与统计行 | run 数 | apps |
|---|---|---|---|---|
| F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\csv\render-r3-merged.csv | 34 | 34 | 1 | spike2 |

**RUNENV 环境头**

| run_id | app | apk | md5 | api | model | abi | build | ts | 来源 |
|---|---|---|---|---|---|---|---|---|---|
| render-r3-run1-20261101-210622 | spike2 | spikes/spike2-pdf/app/build/outputs/apk/debug/app-debug.apk | 8ea3edc683ebf0b5be2f32f32dc57bb9 | 35 | Android_SDK_built_for_x86_64 | x86_64 | 12368160 | 2026-11-01T21:06:33+0800 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r3-run1-20261101-210622.log:1 |

## 2. ms 统计（跨 run，冷热混合，仅总览）

| app | scenario | phase | dims | n | min | p50 | p95 | max | 真机推算 p50 | run 数 | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| spike2 | S1 | open | - | 1 | 1685.539 | 1685.539 | 1685.539 | 1685.539 | 3034.0 [推测] | 1 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r3-run1-20261101-210622.log:146-146 |
| spike2 | S6 | render | 1073x1518,1650x2336,594x841 | 14 | 135.844 | 467.117 | 1311.447 | 1311.447 | 840.8 [推测] | 1 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r3-run1-20261101-210622.log:175-201 |
| spike2 | S8 | render_hw | 1188x1682 | 4 | 0.107 | 8.440 | 16.155 | 16.155 | 15.2 [推测] | 1 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r3-run1-20261101-210622.log:217-225 |

> 第 2 节把 cold/warm 混在一起，只用于总览；跨 app / 跨路径对比必须用第 3 节的同冷热分组。
> 注意：tiles app 的 *_stat 行（tile_stat/frame_stat/gesture_stat…）是 app 侧已聚合的块统计，不按单样本计入本节，单独列在第 2b 节。

## 2b. app 侧聚合行（*_stat：n/p50/p95 由 app 自己汇总）

> 这些行每个是一次「块」测量（app 内部 n 次取 avg/p50/p95）；本节报块数、总样本数与各块 p50 的中位数，不参与第 2/3 节的逐样本统计。

（没有 *_stat 聚合行。）

## 2c. T1 首屏两个口径（first_screen 不含 open；read_path_ms 含 open）

> L3「首屏 ≤200ms」按协议用 `first_screen`（setPage → 9 个 tile 渲染并呈现一帧），**不含 open()**；用户体感对应 `read_path_ms`（open + 首屏）。两列并列，禁止混用。

（没有 T1 first_screen 行。）

## 2d. 内存与缓存（PSS / cache 状态行）

> 这些行没有 ms 语义，不进耗时统计；T5 的 PSS 必须同一进程生命周期内取前后值得出增量。

| run_id | app | scenario | phase | pss_kb | native_kb | java_kb | tag | hit | evict | miss | bytes | limit | tiles | cycles | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| render-r3-run1-20261101-210622 | spike2 | S7 | pss | 24228 | 3887 | 678 | cold |  |  |  |  |  |  |  | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r3-run1-20261101-210622.log:126 |
| render-r3-run1-20261101-210622 | spike2 | S7 | pss | 71635 | 42643 | 689 | open |  |  |  |  |  |  |  | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r3-run1-20261101-210622.log:203 |
| render-r3-run1-20261101-210622 | spike2 | S7 | pss | 72458 | 42694 | 693 | 10pages |  |  |  |  |  |  |  | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r3-run1-20261101-210622.log:206 |
| render-r3-run1-20261101-210622 | spike2 | S7 | pss | 75493 | 42234 | 701 | 30pages |  |  |  |  |  |  |  | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r3-run1-20261101-210622.log:208 |

## 3. 冷/热分组（跨 app 对比只允许同状态）

> cold = 同页同尺寸第一次渲染；warm = 之后。派生自 extra 的 cold:1 / cold:0（或 temp:）。

| app | scenario | phase | 状态 | n | min | p50 | p95 | max | 真机推算 p50 | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|
| spike2 | S6 | render | cold | 3 | 556.250 | 588.464 | 1311.447 | 1311.447 | 1059.2 [推测] | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r3-run1-20261101-210622.log:175-186 |
| spike2 | S6 | render | warm | 11 | 135.844 | 384.174 | 782.025 | 782.025 | 691.5 [推测] | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r3-run1-20261101-210622.log:191-201 |

## 4. renderAnnot 分组（A/B）

> 派生自 extra 的 annot:1 / annot:0（或 renderAnnot:）。1=走注解/文本层路径，0=不走。

（样本里没有 annot 标注。）

## 5. 漂移哨兵

> 参照组：scenario=S1 phase=open。比较最早与最晚 run 的中位数；差 > 20% 标 DRIFT_WARN。

| # | run_id | app | n | median | avg | ts | 来源 |
|---|---|---|---|---|---|---|---|
| 1 | render-r3-run1-20261101-210622 | spike2 | 1 | 1685.539 | 1685.539 | 11-01 13:06:50.428 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r3-run1-20261101-210622.log:146-146 |

- 只有 1 个 run，无法判断漂移（至少 2 个 run 才有哨兵意义）。

## 6. LOW_CONFIDENCE

> 组样本数 < 5。只作方向参考，不能写进结论。

| app | scenario | phase | n | p50 | 来源 |
|---|---|---|---|---|---|
| spike2 | S1 | open | 1 | 1685.539 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r3-run1-20261101-210622.log:146-146 |
| spike2 | S8 | render_hw | 4 | 8.440 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r3-run1-20261101-210622.log:217-225 |

## 7. 缺 cold/warm 标注的 render 记录

（无。）

## 8. 口径与限制

- 环境：Android emulator（x86_64/API35/1080x2400/dpi420/swiftshader/WHPX）。
- 绝对值只用于同批次对比；跨批次只报中位数与相对差（模拟器漂移 30-50%）。
- [推测] 列 = 模拟器 p50 x 1.80，仅用于给真机量级预期，不是实测。
- 失败/异常不测不报：extra=error: 行不参与统计。

