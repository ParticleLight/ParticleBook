# Spike2 汇总 · tiles-t5

> 由 scripts/android/spike2/summarize.py 从 parse.py 的 CSV 汇总；所有数字可由 raw logcat 重算（CSV 的 src_file/src_line 指回 raw 行号）。
> 生成时间：2026-11-01T21:16:11+08:00　|　真机推算系数 x1.80（[推测]，PDFium 纯 CPU 光栅，真机/模拟器约 1.4-2.5）
> 口径：median=statistics.median；p95=nearest-rank（idx=ceil(q*n)-1）；单位 ms。

## 1. 输入与环境

| CSV | CSV 行数 | 参与统计行 | run 数 | apps |
|---|---|---|---|---|
| spikes\spike2-results\csv\tiles-t5-merged.csv | 39 | 39 | 3 | spike2t |

**RUNENV 环境头**

| run_id | app | apk | md5 | api | model | abi | build | ts | 来源 |
|---|---|---|---|---|---|---|---|---|---|
| tiles-t5-run1-20261101-210906 | spike2t | spikes/spike2-tiles/app/build/outputs/apk/debug/app-debug.apk | 7ab3634ff0d97dcad0a759c988304e5d | 35 | Android_SDK_built_for_x86_64 | x86_64 | 12368160 | 2026-11-01T21:09:14+0800 | spikes\spike2-results\raw\tiles-t5-run1-20261101-210906.log:1 |
| tiles-t5-run1-20261101-211023 | spike2t | spikes/spike2-tiles/app/build/outputs/apk/debug/app-debug.apk | 7ab3634ff0d97dcad0a759c988304e5d | 35 | Android_SDK_built_for_x86_64 | x86_64 | 12368160 | 2026-11-01T21:10:33+0800 | spikes\spike2-results\raw\tiles-t5-run1-20261101-211023.log:1 |
| tiles-t5-run1-20261101-211344 | spike2t | spikes/spike2-tiles/app/build/outputs/apk/debug/app-debug.apk | 7ab3634ff0d97dcad0a759c988304e5d | 35 | Android_SDK_built_for_x86_64 | x86_64 | 12368160 | 2026-11-01T21:13:54+0800 | spikes\spike2-results\raw\tiles-t5-run1-20261101-211344.log:1 |

## 2. ms 统计（跨 run，冷热混合，仅总览）

（没有可统计的样本。）

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
| tiles-t5-run1-20261101-210906 | spike2t | T5 | pss | 58630 | 28309 | 1380 |  |  |  |  |  |  |  |  | spikes/spike2-results/raw/tiles-t5-run1-20261101-210906.log:165 |
| tiles-t5-run1-20261101-210906 | spike2t | T5 | pss | 115290 | 79632 | 832 |  |  |  |  |  |  |  |  | spikes/spike2-results/raw/tiles-t5-run1-20261101-210906.log:211 |
| tiles-t5-run1-20261101-210906 | spike2t | T5 | cache |  |  |  |  | 765 | 380 | 2176 | 33412480 | 33554432 | 42 | 20 | spikes/spike2-results/raw/tiles-t5-run1-20261101-210906.log:212 |
| tiles-t5-run1-20261101-210906 | spike2t | ALL | pss | 118454 | 89692 | 512 |  |  |  |  |  |  |  |  | spikes/spike2-results/raw/tiles-t5-run1-20261101-210906.log:230 |
| tiles-t5-run1-20261101-211023 | spike2t | T5 | pss | 56378 | 28186 | 1384 |  |  |  |  |  |  |  |  | spikes/spike2-results/raw/tiles-t5-run1-20261101-211023.log:177 |
| tiles-t5-run1-20261101-211023 | spike2t | T5 | pss | 128680 | 94120 | 780 |  |  |  |  |  |  |  |  | spikes/spike2-results/raw/tiles-t5-run1-20261101-211023.log:226 |
| tiles-t5-run1-20261101-211023 | spike2t | T5 | cache |  |  |  |  | 1084 | 408 | 1857 | 32531520 | 33554432 | 34 | 20 | spikes/spike2-results/raw/tiles-t5-run1-20261101-211023.log:227 |
| tiles-t5-run1-20261101-211023 | spike2t | ALL | pss | 103574 | 68184 | 800 |  |  |  |  |  |  |  |  | spikes/spike2-results/raw/tiles-t5-run1-20261101-211023.log:242 |
| tiles-t5-run1-20261101-211344 | spike2t | T5 | pss | 56526 | 28370 | 1384 |  |  |  |  |  |  |  |  | spikes/spike2-results/raw/tiles-t5-run1-20261101-211344.log:183 |
| tiles-t5-run1-20261101-211344 | spike2t | T5 | pss | 125702 | 90840 | 808 |  |  |  |  |  |  |  |  | spikes/spike2-results/raw/tiles-t5-run1-20261101-211344.log:235 |
| tiles-t5-run1-20261101-211344 | spike2t | T5 | cache |  |  |  |  | 913 | 351 | 1975 | 32768736 | 33554432 | 37 | 20 | spikes/spike2-results/raw/tiles-t5-run1-20261101-211344.log:236 |
| tiles-t5-run1-20261101-211344 | spike2t | ALL | pss | 114198 | 78720 | 780 |  |  |  |  |  |  |  |  | spikes/spike2-results/raw/tiles-t5-run1-20261101-211344.log:247 |

## 3. 冷/热分组（跨 app 对比只允许同状态）

> cold = 同页同尺寸第一次渲染；warm = 之后。派生自 extra 的 cold:1 / cold:0（或 temp:）。

（样本里没有 cold/warm 标注——检查被测 app 的 extra 是否带 cold:1 / cold:0。）

## 4. renderAnnot 分组（A/B）

> 派生自 extra 的 annot:1 / annot:0（或 renderAnnot:）。1=走注解/文本层路径，0=不走。

（样本里没有 annot 标注。）

## 5. 漂移哨兵

（没有可用于哨兵的参照组。）

## 6. LOW_CONFIDENCE

（所有组 n >= 5。）

## 7. 缺 cold/warm 标注的 render 记录

（无。）

## 8. 口径与限制

- 环境：Android emulator（x86_64/API35/1080x2400/dpi420/swiftshader/WHPX）。
- 绝对值只用于同批次对比；跨批次只报中位数与相对差（模拟器漂移 30-50%）。
- [推测] 列 = 模拟器 p50 x 1.80，仅用于给真机量级预期，不是实测。
- 失败/异常不测不报：extra=error: 行不参与统计。

