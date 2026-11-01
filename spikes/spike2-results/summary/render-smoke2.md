# Spike2 汇总 · render-smoke2

> 由 scripts/android/spike2/summarize.py 从 parse.py 的 CSV 汇总；所有数字可由 raw logcat 重算（CSV 的 src_file/src_line 指回 raw 行号）。
> 生成时间：2026-11-01T20:41:18+08:00　|　真机推算系数 x1.80（[推测]，PDFium 纯 CPU 光栅，真机/模拟器约 1.4-2.5）
> 口径：median=statistics.median；p95=nearest-rank（idx=ceil(q*n)-1）；单位 ms。

## 1. 输入与环境

| CSV | CSV 行数 | 参与统计行 | run 数 | apps |
|---|---|---|---|---|
| F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\csv\render-smoke2-merged.csv | 51 | 51 | 1 | spike2 |

**RUNENV 环境头**

| run_id | app | apk | md5 | api | model | abi | build | ts | 来源 |
|---|---|---|---|---|---|---|---|---|---|
| render-smoke2-run1-20261101-204037 | spike2 | spikes/spike2-pdf/app/build/outputs/apk/debug/app-debug.apk | 8ea3edc683ebf0b5be2f32f32dc57bb9 | 35 | Android_SDK_built_for_x86_64 | x86_64 | 12368160 | 2026-11-01T20:40:48+0800 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-smoke2-run1-20261101-204037.log:1 |

## 2. ms 统计（跨 run，冷热混合，仅总览）

| app | scenario | phase | dims | n | min | p50 | p95 | max | 真机推算 p50 | run 数 | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| spike2 | PBTIME | first_frame | - | 1 | 1403.849 | 1403.849 | 1403.849 | 1403.849 | 2526.9 [推测] | 1 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-smoke2-run1-20261101-204037.log:129-129 |
| spike2 | PBTIME | first_screen | - | 1 | 3827.834 | 3827.834 | 3827.834 | 3827.834 | 6890.1 [推测] | 1 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-smoke2-run1-20261101-204037.log:159-159 |
| spike2 | PBTIME | open_to_first_screen | - | 1 | 2842.865 | 2842.865 | 2842.865 | 2842.865 | 5117.2 [推测] | 1 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-smoke2-run1-20261101-204037.log:160-160 |
| spike2 | S1 | open | - | 2 | 370.231 | 529.840 | 689.449 | 689.449 | 953.7 [推测] | 1 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-smoke2-run1-20261101-204037.log:131-135 |
| spike2 | S2 | present | 1188x1682 | 20 | 17.356 | 40.380 | 97.255 | 215.607 | 72.7 [推测] | 1 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-smoke2-run1-20261101-204037.log:156-221 |
| spike2 | S2 | render | 1188x1682 | 20 | 21.799 | 94.454 | 775.821 | 1242.749 | 170.0 [推测] | 1 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-smoke2-run1-20261101-204037.log:148-220 |
| spike2 | cold_start | am_start | - | 1 | 6871.000 | 6871.000 | 6871.000 | 6871.000 | 12367.8 [推测] | 1 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-smoke2-run1-20261101-204037.log:4-4 |

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
| render-smoke2-run1-20261101-204037 | spike2 | S7 | pss | 25073 | 3890 | 702 | cold |  |  |  |  |  |  |  | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-smoke2-run1-20261101-204037.log:117 |

## 3. 冷/热分组（跨 app 对比只允许同状态）

> cold = 同页同尺寸第一次渲染；warm = 之后。派生自 extra 的 cold:1 / cold:0（或 temp:）。

| app | scenario | phase | 状态 | n | min | p50 | p95 | max | 真机推算 p50 | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|
| spike2 | S2 | present | cold | 4 | 27.839 | 71.309 | 215.607 | 215.607 | 128.4 [推测] | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-smoke2-run1-20261101-204037.log:156-212 |
| spike2 | S2 | present | warm | 16 | 17.356 | 36.915 | 97.255 | 97.255 | 66.4 [推测] | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-smoke2-run1-20261101-204037.log:179-221 |
| spike2 | S2 | render | cold | 4 | 21.799 | 150.480 | 1242.749 | 1242.749 | 270.9 [推测] | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-smoke2-run1-20261101-204037.log:148-211 |
| spike2 | S2 | render | warm | 16 | 32.613 | 94.454 | 775.821 | 775.821 | 170.0 [推测] | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-smoke2-run1-20261101-204037.log:178-220 |

## 4. renderAnnot 分组（A/B）

> 派生自 extra 的 annot:1 / annot:0（或 renderAnnot:）。1=走注解/文本层路径，0=不走。

| app | scenario | phase | annot | n | p50 | p95 | max | 来源 |
|---|---|---|---|---|---|---|---|---|
| spike2 | S2 | render | 1 | 20 | 94.454 | 775.821 | 1242.749 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-smoke2-run1-20261101-204037.log:148-220 |

## 5. 漂移哨兵

> 参照组：scenario=S2 phase=render page=0。比较最早与最晚 run 的中位数；差 > 20% 标 DRIFT_WARN。

| # | run_id | app | n | median | avg | ts | 来源 |
|---|---|---|---|---|---|---|---|
| 1 | render-smoke2-run1-20261101-204037 | spike2 | 5 | 683.470 | 685.672 | 11-01 12:41:00.057 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-smoke2-run1-20261101-204037.log:148-185 |

- 只有 1 个 run，无法判断漂移（至少 2 个 run 才有哨兵意义）。

## 6. LOW_CONFIDENCE

> 组样本数 < 5。只作方向参考，不能写进结论。

| app | scenario | phase | n | p50 | 来源 |
|---|---|---|---|---|---|
| spike2 | PBTIME | first_frame | 1 | 1403.849 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-smoke2-run1-20261101-204037.log:129-129 |
| spike2 | PBTIME | first_screen | 1 | 3827.834 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-smoke2-run1-20261101-204037.log:159-159 |
| spike2 | PBTIME | open_to_first_screen | 1 | 2842.865 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-smoke2-run1-20261101-204037.log:160-160 |
| spike2 | S1 | open | 2 | 529.840 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-smoke2-run1-20261101-204037.log:131-135 |
| spike2 | cold_start | am_start | 1 | 6871.000 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-smoke2-run1-20261101-204037.log:4-4 |

## 7. 缺 cold/warm 标注的 render 记录

（无。）

## 8. 口径与限制

- 环境：Android emulator（x86_64/API35/1080x2400/dpi420/swiftshader/WHPX）。
- 绝对值只用于同批次对比；跨批次只报中位数与相对差（模拟器漂移 30-50%）。
- [推测] 列 = 模拟器 p50 x 1.80，仅用于给真机量级预期，不是实测。
- 失败/异常不测不报：extra=error: 行不参与统计。

