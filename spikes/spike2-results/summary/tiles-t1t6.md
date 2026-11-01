# Spike2 汇总 · tiles-t1t6

> 由 scripts/android/spike2/summarize.py 从 parse.py 的 CSV 汇总；所有数字可由 raw logcat 重算（CSV 的 src_file/src_line 指回 raw 行号）。
> 生成时间：2026-11-01T20:59:29+08:00　|　真机推算系数 x1.80（[推测]，PDFium 纯 CPU 光栅，真机/模拟器约 1.4-2.5）
> 口径：median=statistics.median；p95=nearest-rank（idx=ceil(q*n)-1）；单位 ms。

## 1. 输入与环境

| CSV | CSV 行数 | 参与统计行 | run 数 | apps |
|---|---|---|---|---|
| F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\csv\tiles-t1t6-merged.csv | 116 | 116 | 4 | spike2t |

**RUNENV 环境头**

| run_id | app | apk | md5 | api | model | abi | build | ts | 来源 |
|---|---|---|---|---|---|---|---|---|---|
| tiles-t1t6-run1-20261101-205642 | spike2t | spikes/spike2-tiles/app/build/outputs/apk/debug/app-debug.apk | 7ab3634ff0d97dcad0a759c988304e5d | 35 | Android_SDK_built_for_x86_64 | x86_64 | 12368160 | 2026-11-01T20:56:50+0800 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\tiles-t1t6-run1-20261101-205642.log:1 |
| tiles-t1t6-run1-20261101-205821 | spike2t | spikes/spike2-tiles/app/build/outputs/apk/debug/app-debug.apk | 7ab3634ff0d97dcad0a759c988304e5d | 35 | Android_SDK_built_for_x86_64 | x86_64 | 12368160 | 2026-11-01T20:58:29+0800 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\tiles-t1t6-run1-20261101-205821.log:1 |
| tiles-t1t6-run2-20261101-205718 | spike2t | spikes/spike2-tiles/app/build/outputs/apk/debug/app-debug.apk | 7ab3634ff0d97dcad0a759c988304e5d | 35 | Android_SDK_built_for_x86_64 | x86_64 | 12368160 | 2026-11-01T20:57:24+0800 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\tiles-t1t6-run2-20261101-205718.log:1 |
| tiles-t1t6-run2-20261101-205859 | spike2t | spikes/spike2-tiles/app/build/outputs/apk/debug/app-debug.apk | 7ab3634ff0d97dcad0a759c988304e5d | 35 | Android_SDK_built_for_x86_64 | x86_64 | 12368160 | 2026-11-01T20:59:03+0800 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\tiles-t1t6-run2-20261101-205859.log:1 |

## 2. ms 统计（跨 run，冷热混合，仅总览）

| app | scenario | phase | dims | n | min | p50 | p95 | max | 真机推算 p50 | run 数 | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| spike2t | T1 | tile | 512x505,512x512,56x505,56x512 | 32 | 0.206 | 13.111 | 203.918 | 208.669 | 23.6 [推测] | 4 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t1t6-run1-20261101-205642.log:175-183; F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t1t6-run1-20261101-205821.log:189-197; F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t1t6-run2-20261101-205718.log:183-190; F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t1t6-run2-20261101-205859.log:163-171 |
| spike2t | T6 | first_screen | 1080x2400 | 16 | 263.677 | 378.299 | 600.994 | 600.994 | 680.9 [推测] | 4 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t1t6-run1-20261101-205642.log:202-206; F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t1t6-run1-20261101-205821.log:216-225; F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t1t6-run2-20261101-205718.log:211-215; F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t1t6-run2-20261101-205859.log:183-196 |

> 第 2 节把 cold/warm 混在一起，只用于总览；跨 app / 跨路径对比必须用第 3 节的同冷热分组。
> 注意：tiles app 的 *_stat 行（tile_stat/frame_stat/gesture_stat…）是 app 侧已聚合的块统计，不按单样本计入本节，单独列在第 2b 节。

## 2b. app 侧聚合行（*_stat：n/p50/p95 由 app 自己汇总）

> 这些行每个是一次「块」测量（app 内部 n 次取 avg/p50/p95）；本节报块数、总样本数与各块 p50 的中位数，不参与第 2/3 节的逐样本统计。

（没有 *_stat 聚合行。）

## 2c. T1 首屏两个口径（first_screen 不含 open；read_path_ms 含 open）

> L3「首屏 ≤200ms」按协议用 `first_screen`（setPage → 9 个 tile 渲染并呈现一帧），**不含 open()**；用户体感对应 `read_path_ms`（open + 首屏）。两列并列，禁止混用。

| run_id | app | first_screen ms | open_ms | read_path_ms | from_create_ms | tiles | 来源 |
|---|---|---|---|---|---|---|---|
| tiles-t1t6-run1-20261101-205642 | spike2t | 1009.090 | 297.100 | 1306.200 | 5166.200 | 9 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t1t6-run1-20261101-205642.log:184 |
| tiles-t1t6-run1-20261101-205821 | spike2t | 1042.063 | 329.600 | 1371.700 | 6342.300 | 9 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t1t6-run1-20261101-205821.log:198 |
| tiles-t1t6-run2-20261101-205718 | spike2t | 1040.480 | 490.000 | 1530.500 | 5895.600 | 9 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t1t6-run2-20261101-205718.log:192 |
| tiles-t1t6-run2-20261101-205859 | spike2t | 873.060 | 366.800 | 1239.900 | 4504.400 | 9 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t1t6-run2-20261101-205859.log:172 |

## 2d. 内存与缓存（PSS / cache 状态行）

> 这些行没有 ms 语义，不进耗时统计；T5 的 PSS 必须同一进程生命周期内取前后值得出增量。

| run_id | app | scenario | phase | pss_kb | native_kb | java_kb | tag | hit | evict | miss | bytes | limit | tiles | cycles | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| tiles-t1t6-run1-20261101-205642 | spike2t | T6 | pss | 125102 | 92140 | 804 |  |  |  |  |  |  |  |  | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t1t6-run1-20261101-205642.log:208 |
| tiles-t1t6-run1-20261101-205642 | spike2t | ALL | pss | 133966 | 100740 | 828 |  |  |  |  |  |  |  |  | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t1t6-run1-20261101-205642.log:210 |
| tiles-t1t6-run1-20261101-205821 | spike2t | T6 | pss | 125266 | 92220 | 800 |  |  |  |  |  |  |  |  | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t1t6-run1-20261101-205821.log:226 |
| tiles-t1t6-run1-20261101-205821 | spike2t | ALL | pss | 134120 | 100860 | 816 |  |  |  |  |  |  |  |  | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t1t6-run1-20261101-205821.log:229 |
| tiles-t1t6-run2-20261101-205718 | spike2t | T6 | pss | 125698 | 92160 | 792 |  |  |  |  |  |  |  |  | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t1t6-run2-20261101-205718.log:216 |
| tiles-t1t6-run2-20261101-205718 | spike2t | ALL | pss | 135562 | 100772 | 820 |  |  |  |  |  |  |  |  | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t1t6-run2-20261101-205718.log:224 |
| tiles-t1t6-run2-20261101-205859 | spike2t | T6 | pss | 125458 | 92204 | 740 |  |  |  |  |  |  |  |  | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t1t6-run2-20261101-205859.log:197 |
| tiles-t1t6-run2-20261101-205859 | spike2t | ALL | pss | 134378 | 100844 | 764 |  |  |  |  |  |  |  |  | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t1t6-run2-20261101-205859.log:199 |

## 3. 冷/热分组（跨 app 对比只允许同状态）

> cold = 同页同尺寸第一次渲染；warm = 之后。派生自 extra 的 cold:1 / cold:0（或 temp:）。

（样本里没有 cold/warm 标注——检查被测 app 的 extra 是否带 cold:1 / cold:0。）

## 4. renderAnnot 分组（A/B）

> 派生自 extra 的 annot:1 / annot:0（或 renderAnnot:）。1=走注解/文本层路径，0=不走。

（样本里没有 annot 标注。）

## 5. 漂移哨兵

> 参照组：scenario=T1 phase=tile page=0。比较最早与最晚 run 的中位数；差 > 20% 标 DRIFT_WARN。

| # | run_id | app | n | median | avg | ts | 来源 |
|---|---|---|---|---|---|---|---|
| 1 | tiles-t1t6-run1-20261101-205642 | spike2t | 8 | 13.973 | 59.811 | 11-01 12:56:59.396 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t1t6-run1-20261101-205642.log:175-183 |
| 2 | tiles-t1t6-run2-20261101-205718 | spike2t | 8 | 12.957 | 21.010 | 11-01 12:57:35.298 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t1t6-run2-20261101-205718.log:183-190 |
| 3 | tiles-t1t6-run1-20261101-205821 | spike2t | 8 | 13.662 | 44.120 | 11-01 12:58:41.642 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t1t6-run1-20261101-205821.log:189-197 |
| 4 | tiles-t1t6-run2-20261101-205859 | spike2t | 8 | 18.206 | 46.458 | 11-01 12:59:12.628 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t1t6-run2-20261101-205859.log:163-171 |

- 首 run median=13.973 ms，末 run median=18.206 ms，变化 +30.3% DRIFT_WARN
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

