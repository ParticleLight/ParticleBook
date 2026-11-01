# Spike2 汇总 · tiles-t34

> 由 scripts/android/spike2/summarize.py 从 parse.py 的 CSV 汇总；所有数字可由 raw logcat 重算（CSV 的 src_file/src_line 指回 raw 行号）。
> 生成时间：2026-11-01T21:16:12+08:00　|　真机推算系数 x1.80（[推测]，PDFium 纯 CPU 光栅，真机/模拟器约 1.4-2.5）
> 口径：median=statistics.median；p95=nearest-rank（idx=ceil(q*n)-1）；单位 ms。

## 1. 输入与环境

| CSV | CSV 行数 | 参与统计行 | run 数 | apps |
|---|---|---|---|---|
| spikes\spike2-results\csv\tiles-t34-merged.csv | 35 | 35 | 1 | spike2t |

**RUNENV 环境头**

| run_id | app | apk | md5 | api | model | abi | build | ts | 来源 |
|---|---|---|---|---|---|---|---|---|---|
| tiles-t34-run1-20261101-211458 | spike2t | spikes/spike2-tiles/app/build/outputs/apk/debug/app-debug.apk | 7ab3634ff0d97dcad0a759c988304e5d | 35 | Android_SDK_built_for_x86_64 | x86_64 | 12368160 | 2026-11-01T21:15:06+0800 | spikes\spike2-results\raw\tiles-t34-run1-20261101-211458.log:1 |

## 2. ms 统计（跨 run，冷热混合，仅总览）

| app | scenario | phase | dims | n | min | p50 | p95 | max | 真机推算 p50 | run 数 | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| spike2t | T4 | rerender | 1080x2400 | 4 | 464.019 | 644.577 | 807.601 | 807.601 | 1160.2 [推测] | 1 | spikes/spike2-results/raw/tiles-t34-run1-20261101-211458.log:221-238 |

> 第 2 节把 cold/warm 混在一起，只用于总览；跨 app / 跨路径对比必须用第 3 节的同冷热分组。
> 注意：tiles app 的 *_stat 行（tile_stat/frame_stat/gesture_stat…）是 app 侧已聚合的块统计，不按单样本计入本节，单独列在第 2b 节。

## 2b. app 侧聚合行（*_stat：n/p50/p95 由 app 自己汇总）

> 这些行每个是一次「块」测量（app 内部 n 次取 avg/p50/p95）；本节报块数、总样本数与各块 p50 的中位数，不参与第 2/3 节的逐样本统计。

| app | scenario | phase | 块标签 | 块数 | 总样本 | 每块 n | dims | 块 p50 中位 | 块 p95 最大 | fps 中位 | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| spike2t | T3 | frame_stat | rep:0,zoom:2.0,ab:bg | 1 | 11 | 11 | 1080x2400 | 33.330 | 66.670 | 26.4 | spikes/spike2-results/raw/tiles-t34-run1-20261101-211458.log:207-207 |
| spike2t | T3 | frame_stat | rep:0,zoom:2.0,ab:lowres | 1 | 8 | 8 | 1080x2400 | 50.000 | 83.330 | 18.5 | spikes/spike2-results/raw/tiles-t34-run1-20261101-211458.log:201-201 |
| spike2t | T3 | frame_stat | rep:0,zoom:2.0,ab:tiles | 1 | 4 | 4 | 1080x2400 | 100.000 | 183.330 | 9.6 | spikes/spike2-results/raw/tiles-t34-run1-20261101-211458.log:180-180 |
| spike2t | T3 | frame_stat | rep:1,zoom:2.0,ab:bg | 1 | 10 | 10 | 1080x2400 | 33.330 | 66.670 | 25.0 | spikes/spike2-results/raw/tiles-t34-run1-20261101-211458.log:208-208 |
| spike2t | T3 | frame_stat | rep:1,zoom:2.0,ab:lowres | 1 | 7 | 7 | 1080x2400 | 50.000 | 83.330 | 17.5 | spikes/spike2-results/raw/tiles-t34-run1-20261101-211458.log:203-203 |
| spike2t | T3 | frame_stat | rep:1,zoom:2.0,ab:tiles | 1 | 6 | 6 | 1080x2400 | 66.670 | 83.330 | 15.0 | spikes/spike2-results/raw/tiles-t34-run1-20261101-211458.log:193-193 |
| spike2t | T3 | frame_stat | rep:2,zoom:2.0,ab:bg | 1 | 10 | 10 | 1080x2400 | 50.000 | 50.000 | 24.0 | spikes/spike2-results/raw/tiles-t34-run1-20261101-211458.log:211-211 |
| spike2t | T3 | frame_stat | rep:2,zoom:2.0,ab:lowres | 1 | 7 | 7 | 1080x2400 | 66.670 | 66.670 | 16.8 | spikes/spike2-results/raw/tiles-t34-run1-20261101-211458.log:204-204 |
| spike2t | T3 | frame_stat | rep:2,zoom:2.0,ab:tiles | 1 | 10 | 10 | 1080x2400 | 50.000 | 83.330 | 19.4 | spikes/spike2-results/raw/tiles-t34-run1-20261101-211458.log:195-195 |
| spike2t | T3 | frame_stat | rep:3,zoom:2.0,ab:tiles | 1 | 8 | 8 | 1080x2400 | 50.000 | 66.670 | 19.2 | spikes/spike2-results/raw/tiles-t34-run1-20261101-211458.log:197-197 |
| spike2t | T3 | frame_stat | rep:4,zoom:2.0,ab:tiles | 1 | 8 | 8 | 1080x2400 | 50.000 | 83.330 | 18.5 | spikes/spike2-results/raw/tiles-t34-run1-20261101-211458.log:199-199 |
| spike2t | T3 | frame_stat_all | reps:3,zoom:2.0,ab:bg | 1 | 31 | 31 | 1080x2400 | 33.330 | 66.670 | 25.1 | spikes/spike2-results/raw/tiles-t34-run1-20261101-211458.log:212-212 |
| spike2t | T3 | frame_stat_all | reps:3,zoom:2.0,ab:lowres | 1 | 22 | 22 | 1080x2400 | 50.000 | 83.330 | 17.6 | spikes/spike2-results/raw/tiles-t34-run1-20261101-211458.log:205-205 |
| spike2t | T3 | frame_stat_all | reps:5,zoom:2.0,ab:tiles | 1 | 36 | 36 | 1080x2400 | 66.670 | 100.000 | 16.5 | spikes/spike2-results/raw/tiles-t34-run1-20261101-211458.log:200-200 |
| spike2t | T4 | gesture_stat | rep:0,zlo:1.818,zhi:5.455,tiles_hi:15 | 1 | 18 | 18 | 1080x2400 | 50.000 | 83.330 | 18.3 | spikes/spike2-results/raw/tiles-t34-run1-20261101-211458.log:216-216 |
| spike2t | T4 | gesture_stat | rep:1,zlo:1.818,zhi:5.455,tiles_hi:15 | 1 | 18 | 18 | 1080x2400 | 50.000 | 83.330 | 18.6 | spikes/spike2-results/raw/tiles-t34-run1-20261101-211458.log:220-220 |
| spike2t | T4 | gesture_stat | rep:2,zlo:1.818,zhi:5.455,tiles_hi:15 | 1 | 17 | 17 | 1080x2400 | 50.000 | 100.000 | 17.3 | spikes/spike2-results/raw/tiles-t34-run1-20261101-211458.log:226-226 |
| spike2t | T4 | gesture_stat | rep:3,zlo:1.818,zhi:5.455,tiles_hi:15 | 1 | 20 | 20 | 1080x2400 | 50.000 | 100.000 | 18.8 | spikes/spike2-results/raw/tiles-t34-run1-20261101-211458.log:231-231 |
| spike2t | T4 | gesture_stat | rep:4,zlo:1.818,zhi:5.455,tiles_hi:15 | 1 | 20 | 20 | 1080x2400 | 50.000 | 66.670 | 20.3 | spikes/spike2-results/raw/tiles-t34-run1-20261101-211458.log:235-235 |

## 2c. T1 首屏两个口径（first_screen 不含 open；read_path_ms 含 open）

> L3「首屏 ≤200ms」按协议用 `first_screen`（setPage → 9 个 tile 渲染并呈现一帧），**不含 open()**；用户体感对应 `read_path_ms`（open + 首屏）。两列并列，禁止混用。

（没有 T1 first_screen 行。）

## 2d. 内存与缓存（PSS / cache 状态行）

> 这些行没有 ms 语义，不进耗时统计；T5 的 PSS 必须同一进程生命周期内取前后值得出增量。

| run_id | app | scenario | phase | pss_kb | native_kb | java_kb | tag | hit | evict | miss | bytes | limit | tiles | cycles | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| tiles-t34-run1-20261101-211458 | spike2t | ALL | pss | 52975 | 24128 | 736 |  |  |  |  |  |  |  |  | spikes/spike2-results/raw/tiles-t34-run1-20261101-211458.log:240 |

## 3. 冷/热分组（跨 app 对比只允许同状态）

> cold = 同页同尺寸第一次渲染；warm = 之后。派生自 extra 的 cold:1 / cold:0（或 temp:）。

（样本里没有 cold/warm 标注——检查被测 app 的 extra 是否带 cold:1 / cold:0。）

## 4. renderAnnot 分组（A/B）

> 派生自 extra 的 annot:1 / annot:0（或 renderAnnot:）。1=走注解/文本层路径，0=不走。

（样本里没有 annot 标注。）

## 5. 漂移哨兵

> 参照组：scenario=T4 phase=rerender。比较最早与最晚 run 的中位数；差 > 20% 标 DRIFT_WARN。

| # | run_id | app | n | median | avg | ts | 来源 |
|---|---|---|---|---|---|---|---|
| 1 | tiles-t34-run1-20261101-211458 | spike2t | 4 | 644.577 | 640.194 | 11-01 13:15:34.515 | spikes/spike2-results/raw/tiles-t34-run1-20261101-211458.log:221-238 |

- 只有 1 个 run，无法判断漂移（至少 2 个 run 才有哨兵意义）。

## 6. LOW_CONFIDENCE

> 组样本数 < 5。只作方向参考，不能写进结论。

| app | scenario | phase | n | p50 | 来源 |
|---|---|---|---|---|---|
| spike2t | T4 | rerender | 4 | 644.577 | spikes/spike2-results/raw/tiles-t34-run1-20261101-211458.log:221-238 |

## 7. 缺 cold/warm 标注的 render 记录

> 协议要求每条 render 记录带 cold/warm；以下记录缺失，不能与带标注数据混用。

| run_id | app | scenario | phase | n | 来源 |
|---|---|---|---|---|---|
| tiles-t34-run1-20261101-211458 | spike2t | T4 | rerender | 4 | spikes/spike2-results/raw/tiles-t34-run1-20261101-211458.log:221-238 |

## 8. 口径与限制

- 环境：Android emulator（x86_64/API35/1080x2400/dpi420/swiftshader/WHPX）。
- 绝对值只用于同批次对比；跨批次只报中位数与相对差（模拟器漂移 30-50%）。
- [推测] 列 = 模拟器 p50 x 1.80，仅用于给真机量级预期，不是实测。
- 失败/异常不测不报：extra=error: 行不参与统计。

