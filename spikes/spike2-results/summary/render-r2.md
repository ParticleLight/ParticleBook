# Spike2 汇总 · render-r2

> 由 scripts/android/spike2/summarize.py 从 parse.py 的 CSV 汇总；所有数字可由 raw logcat 重算（CSV 的 src_file/src_line 指回 raw 行号）。
> 生成时间：2026-11-01T20:58:11+08:00　|　真机推算系数 x1.80（[推测]，PDFium 纯 CPU 光栅，真机/模拟器约 1.4-2.5）
> 口径：median=statistics.median；p95=nearest-rank（idx=ceil(q*n)-1）；单位 ms。

## 1. 输入与环境

| CSV | CSV 行数 | 参与统计行 | run 数 | apps |
|---|---|---|---|---|
| spikes\spike2-results\csv\render-r2-merged.csv | 205 | 205 | 5 | spike2 |

**RUNENV 环境头**

| run_id | app | apk | md5 | api | model | abi | build | ts | 来源 |
|---|---|---|---|---|---|---|---|---|---|
| render-r2-run1-20261101-204959 | spike2 | spikes/spike2-pdf/app/build/outputs/apk/debug/app-debug.apk | 8ea3edc683ebf0b5be2f32f32dc57bb9 | 35 | Android_SDK_built_for_x86_64 | x86_64 | 12368160 | 2026-11-01T20:50:07+0800 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r2-run1-20261101-204959.log:1 |
| render-r2-run1-20261101-205037 | spike2 | spikes/spike2-pdf/app/build/outputs/apk/debug/app-debug.apk | 8ea3edc683ebf0b5be2f32f32dc57bb9 | 35 | Android_SDK_built_for_x86_64 | x86_64 | 12368160 | 2026-11-01T20:50:44+0800 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r2-run1-20261101-205037.log:1 |
| render-r2-run1-20261101-205144 | spike2 | spikes/spike2-pdf/app/build/outputs/apk/debug/app-debug.apk | 8ea3edc683ebf0b5be2f32f32dc57bb9 | 35 | Android_SDK_built_for_x86_64 | x86_64 | 12368160 | 2026-11-01T20:51:52+0800 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r2-run1-20261101-205144.log:1 |
| render-r2-run2-20261101-205107 | spike2 | spikes/spike2-pdf/app/build/outputs/apk/debug/app-debug.apk | 8ea3edc683ebf0b5be2f32f32dc57bb9 | 35 | Android_SDK_built_for_x86_64 | x86_64 | 12368160 | 2026-11-01T20:51:11+0800 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r2-run2-20261101-205107.log:1 |
| render-r2-run2-20261101-205218 | spike2 | spikes/spike2-pdf/app/build/outputs/apk/debug/app-debug.apk | 8ea3edc683ebf0b5be2f32f32dc57bb9 | 35 | Android_SDK_built_for_x86_64 | x86_64 | 12368160 | 2026-11-01T20:52:22+0800 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\render-r2-run2-20261101-205218.log:1 |

## 2. ms 统计（跨 run，冷热混合，仅总览）

| app | scenario | phase | dims | n | min | p50 | p95 | max | 真机推算 p50 | run 数 | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| spike2 | S1 | open | - | 5 | 285.956 | 343.120 | 415.786 | 415.786 | 617.6 [推测] | 5 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run1-20261101-204959.log:119-119; F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run1-20261101-205037.log:115-115; F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run1-20261101-205144.log:140-140; F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run2-20261101-205107.log:124-124; F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run2-20261101-205218.log:122-122 |
| spike2 | S4 | total | 1080x1529 | 120 | 42.763 | 83.707 | 284.111 | 1070.376 | 150.7 [推测] | 5 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run1-20261101-204959.log:135-182; F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run1-20261101-205037.log:127-184; F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run1-20261101-205144.log:169-198; F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run2-20261101-205107.log:136-194; F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run2-20261101-205218.log:138-186 |
| spike2 | S5 | text | 594x841 | 15 | 0.484 | 3.023 | 8.681 | 8.681 | 5.4 [推测] | 5 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run1-20261101-204959.log:186-188; F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run1-20261101-205037.log:188-190; F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run1-20261101-205144.log:202-204; F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run2-20261101-205107.log:198-200; F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run2-20261101-205218.log:190-192 |

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
| render-r2-run1-20261101-204959 | spike2 | S7 | pss | 22770 | 3956 | 685 | cold |  |  |  |  |  |  |  | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run1-20261101-204959.log:101 |
| render-r2-run1-20261101-204959 | spike2 | S4 | pss | 65683 | 40166 | 1637 | before |  |  |  |  |  |  |  | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run1-20261101-204959.log:121 |
| render-r2-run1-20261101-204959 | spike2 | S4 | pss | 80153 | 51749 | 1792 | after |  |  |  |  |  |  |  | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run1-20261101-204959.log:183 |
| render-r2-run1-20261101-205037 | spike2 | S7 | pss | 24645 | 3901 | 689 | cold |  |  |  |  |  |  |  | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run1-20261101-205037.log:97 |
| render-r2-run1-20261101-205037 | spike2 | S4 | pss | 66376 | 40391 | 1641 | before |  |  |  |  |  |  |  | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run1-20261101-205037.log:120 |
| render-r2-run1-20261101-205037 | spike2 | S4 | pss | 82021 | 51808 | 1792 | after |  |  |  |  |  |  |  | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run1-20261101-205037.log:185 |
| render-r2-run1-20261101-205144 | spike2 | S7 | pss | 24574 | 3909 | 685 | cold |  |  |  |  |  |  |  | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run1-20261101-205144.log:119 |
| render-r2-run1-20261101-205144 | spike2 | S4 | pss | 66149 | 40338 | 1637 | before |  |  |  |  |  |  |  | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run1-20261101-205144.log:142 |
| render-r2-run1-20261101-205144 | spike2 | S4 | pss | 80314 | 51795 | 1788 | after |  |  |  |  |  |  |  | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run1-20261101-205144.log:199 |
| render-r2-run2-20261101-205107 | spike2 | S7 | pss | 24697 | 3943 | 689 | cold |  |  |  |  |  |  |  | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run2-20261101-205107.log:106 |
| render-r2-run2-20261101-205107 | spike2 | S4 | pss | 66475 | 40503 | 1641 | before |  |  |  |  |  |  |  | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run2-20261101-205107.log:127 |
| render-r2-run2-20261101-205107 | spike2 | S4 | pss | 81899 | 51864 | 1792 | after |  |  |  |  |  |  |  | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run2-20261101-205107.log:195 |
| render-r2-run2-20261101-205218 | spike2 | S7 | pss | 22701 | 3895 | 685 | cold |  |  |  |  |  |  |  | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run2-20261101-205218.log:105 |
| render-r2-run2-20261101-205218 | spike2 | S4 | pss | 65355 | 39755 | 1641 | before |  |  |  |  |  |  |  | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run2-20261101-205218.log:125 |
| render-r2-run2-20261101-205218 | spike2 | S4 | pss | 81733 | 51810 | 1792 | after |  |  |  |  |  |  |  | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run2-20261101-205218.log:187 |

## 3. 冷/热分组（跨 app 对比只允许同状态）

> cold = 同页同尺寸第一次渲染；warm = 之后。派生自 extra 的 cold:1 / cold:0（或 temp:）。

| app | scenario | phase | 状态 | n | min | p50 | p95 | max | 真机推算 p50 | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|
| spike2 | S4 | total | cold | 25 | 79.322 | 160.348 | 971.857 | 1070.376 | 288.6 [推测] | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run1-20261101-204959.log:135-158; F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run1-20261101-205037.log:127-161; F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run1-20261101-205144.log:169-175; F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run2-20261101-205107.log:136-164; F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run2-20261101-205218.log:138-163 |
| spike2 | S4 | total | warm | 95 | 42.763 | 78.568 | 126.284 | 160.194 | 141.4 [推测] | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run1-20261101-204959.log:161-182; F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run1-20261101-205037.log:163-184; F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run1-20261101-205144.log:178-198; F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run2-20261101-205107.log:166-194; F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run2-20261101-205218.log:165-186 |

## 4. renderAnnot 分组（A/B）

> 派生自 extra 的 annot:1 / annot:0（或 renderAnnot:）。1=走注解/文本层路径，0=不走。

（样本里没有 annot 标注。）

## 5. 漂移哨兵

> 参照组：scenario=S1 phase=open。比较最早与最晚 run 的中位数；差 > 20% 标 DRIFT_WARN。

| # | run_id | app | n | median | avg | ts | 来源 |
|---|---|---|---|---|---|---|---|
| 1 | render-r2-run1-20261101-204959 | spike2 | 1 | 371.722 | 371.722 | 11-01 12:50:12.738 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run1-20261101-204959.log:119-119 |
| 2 | render-r2-run1-20261101-205037 | spike2 | 1 | 308.896 | 308.896 | 11-01 12:50:50.602 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run1-20261101-205037.log:115-115 |
| 3 | render-r2-run2-20261101-205107 | spike2 | 1 | 343.120 | 343.120 | 11-01 12:51:17.986 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run2-20261101-205107.log:124-124 |
| 4 | render-r2-run1-20261101-205144 | spike2 | 1 | 415.786 | 415.786 | 11-01 12:51:59.943 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run1-20261101-205144.log:140-140 |
| 5 | render-r2-run2-20261101-205218 | spike2 | 1 | 285.956 | 285.956 | 11-01 12:52:28.238 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/render-r2-run2-20261101-205218.log:122-122 |

- 首 run median=371.722 ms，末 run median=285.956 ms，变化 -23.1% DRIFT_WARN
- **DRIFT_WARN**：本批首末漂移超阈值，跨批绝对值不可直接比，只能同批内相对比较。

## 6. LOW_CONFIDENCE

（所有组 n >= 5。）

## 7. 缺 cold/warm 标注的 render 记录

（无。）

## 8. 口径与限制

- 环境：Android emulator（x86_64/API35/1080x2400/dpi420/swiftshader/WHPX）。
- 绝对值只用于同批次对比；跨批次只报中位数与相对差（模拟器漂移 30-50%）。
- [推测] 列 = 模拟器 p50 x 1.80，仅用于给真机量级预期，不是实测。
- 失败/异常不测不报：extra=error: 行不参与统计。

