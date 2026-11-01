# Spike2 汇总 · tiles-t2

> 由 scripts/android/spike2/summarize.py 从 parse.py 的 CSV 汇总；所有数字可由 raw logcat 重算（CSV 的 src_file/src_line 指回 raw 行号）。
> 生成时间：2026-11-01T21:08:54+08:00　|　真机推算系数 x1.80（[推测]，PDFium 纯 CPU 光栅，真机/模拟器约 1.4-2.5）
> 口径：median=statistics.median；p95=nearest-rank（idx=ceil(q*n)-1）；单位 ms。

## 1. 输入与环境

| CSV | CSV 行数 | 参与统计行 | run 数 | apps |
|---|---|---|---|---|
| F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\csv\tiles-t2-merged.csv | 111 | 111 | 1 | spike2t |

**RUNENV 环境头**

| run_id | app | apk | md5 | api | model | abi | build | ts | 来源 |
|---|---|---|---|---|---|---|---|---|---|
| tiles-t2-run1-20261101-210735 | spike2t | spikes/spike2-tiles/app/build/outputs/apk/debug/app-debug.apk | 7ab3634ff0d97dcad0a759c988304e5d | 35 | Android_SDK_built_for_x86_64 | x86_64 | 12368160 | 2026-11-01T21:07:48+0800 | F:\VScode\Read\.agents\worktrees\dsh-2998e44b-11d5\spikes\spike2-results\raw\tiles-t2-run1-20261101-210735.log:1 |

## 2. ms 统计（跨 run，冷热混合，仅总览）

| app | scenario | phase | dims | n | min | p50 | p95 | max | 真机推算 p50 | run 数 | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| spike2t | T2 | full_curve | 1080x1529 | 9 | 44.469 | 102.054 | 299.091 | 299.091 | 183.7 [推测] | 1 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t2-run1-20261101-210735.log:342-351 |
| spike2t | T2 | full_ref | 2160x3058,3240x4587 | 2 | 532.211 | 922.726 | 1313.241 | 1313.241 | 1660.9 [推测] | 1 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t2-run1-20261101-210735.log:325-335 |
| spike2t | T2 | tile | 1024x1024,1080x1529,512x512 | 71 | 18.485 | 142.848 | 738.176 | 1010.736 | 257.1 [推测] | 1 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t2-run1-20261101-210735.log:205-332 |

> 第 2 节把 cold/warm 混在一起，只用于总览；跨 app / 跨路径对比必须用第 3 节的同冷热分组。
> 注意：tiles app 的 *_stat 行（tile_stat/frame_stat/gesture_stat…）是 app 侧已聚合的块统计，不按单样本计入本节，单独列在第 2b 节。

## 2b. app 侧聚合行（*_stat：n/p50/p95 由 app 自己汇总）

> 这些行每个是一次「块」测量（app 内部 n 次取 avg/p50/p95）；本节报块数、总样本数与各块 p50 的中位数，不参与第 2/3 节的逐样本统计。

| app | scenario | phase | 块标签 | 块数 | 总样本 | 每块 n | dims | 块 p50 中位 | 块 p95 最大 | fps 中位 | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| spike2t | T2 | tile_stat | zoom:1.0,tx:0,ty:0,kpx:256,ab:pagehandle,pagehandle:cached | 1 | 5 | 5 | 512x512 | 66.790 | 132.420 | 11.8 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t2-run1-20261101-210735.log:229-229 |
| spike2t | T2 | tile_stat | zoom:1.0,tx:0,ty:0,kpx:256,ab:pagehandle,pagehandle:fresh | 1 | 5 | 5 | 512x512 | 95.790 | 233.510 | 9.0 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t2-run1-20261101-210735.log:221-221 |
| spike2t | T2 | tile_stat | zoom:1.0,tx:0,ty:0,tilesize:1024,ab:overload,overload:matrix | 1 | 5 | 5 | 1024x1024 | 551.030 | 813.870 | 1.7 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t2-run1-20261101-210735.log:295-295 |
| spike2t | T2 | tile_stat | zoom:1.0,tx:0,ty:0,tilesize:1024,ab:overload,overload:offset | 1 | 5 | 5 | 1024x1024 | 361.380 | 1010.740 | 2.0 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t2-run1-20261101-210735.log:306-306 |
| spike2t | T2 | tile_stat | zoom:1.0,tx:0,ty:0,tilesize:1024,kpx:1048,ab:tilesize | 1 | 5 | 5 | 1024x1024 | 356.970 | 524.820 | 2.7 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t2-run1-20261101-210735.log:248-248 |
| spike2t | T2 | tile_stat | zoom:1.0,tx:0,ty:0,tilesize:2048,kpx:1651,ab:tilesize | 1 | 5 | 5 | 1080x1529 | 448.460 | 616.450 | 2.2 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t2-run1-20261101-210735.log:262-262 |
| spike2t | T2 | tile_stat | zoom:1.0,tx:0,ty:0,tilesize:512,ab:overload,overload:matrix | 1 | 5 | 5 | 512x512 | 37.950 | 70.170 | 23.8 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t2-run1-20261101-210735.log:273-273 |
| spike2t | T2 | tile_stat | zoom:1.0,tx:0,ty:0,tilesize:512,ab:overload,overload:offset | 1 | 5 | 5 | 512x512 | 52.070 | 139.470 | 14.3 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t2-run1-20261101-210735.log:281-281 |
| spike2t | T2 | tile_stat | zoom:1.0,tx:0,ty:0,tilesize:512,kpx:262,ab:tilesize | 1 | 5 | 5 | 512x512 | 180.270 | 227.430 | 6.1 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t2-run1-20261101-210735.log:237-237 |
| spike2t | T2 | tile_stat | zoom:1.0,tx:1,ty:1,kpx:262,scale:1.818,pagehandle:cached | 1 | 5 | 5 | 512x512 | 105.540 | 184.290 | 8.9 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t2-run1-20261101-210735.log:314-314 |
| spike2t | T2 | tile_stat | zoom:2.0,tx:1,ty:1,kpx:262,scale:3.636,pagehandle:cached | 1 | 5 | 5 | 512x512 | 243.130 | 484.120 | 4.4 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t2-run1-20261101-210735.log:324-324 |
| spike2t | T2 | tile_stat | zoom:3.0,tx:1,ty:1,kpx:262,scale:5.455,pagehandle:cached | 1 | 5 | 5 | 512x512 | 86.490 | 142.850 | 11.7 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t2-run1-20261101-210735.log:333-333 |

## 2c. T1 首屏两个口径（first_screen 不含 open；read_path_ms 含 open）

> L3「首屏 ≤200ms」按协议用 `first_screen`（setPage → 9 个 tile 渲染并呈现一帧），**不含 open()**；用户体感对应 `read_path_ms`（open + 首屏）。两列并列，禁止混用。

（没有 T1 first_screen 行。）

## 2d. 内存与缓存（PSS / cache 状态行）

> 这些行没有 ms 语义，不进耗时统计；T5 的 PSS 必须同一进程生命周期内取前后值得出增量。

| run_id | app | scenario | phase | pss_kb | native_kb | java_kb | tag | hit | evict | miss | bytes | limit | tiles | cycles | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| tiles-t2-run1-20261101-210735 | spike2t | ALL | pss | 58558 | 29664 | 824 |  |  |  |  |  |  |  |  | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t2-run1-20261101-210735.log:354 |

## 3. 冷/热分组（跨 app 对比只允许同状态）

> cold = 同页同尺寸第一次渲染；warm = 之后。派生自 extra 的 cold:1 / cold:0（或 temp:）。

| app | scenario | phase | 状态 | n | min | p50 | p95 | max | 真机推算 p50 | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|
| spike2t | T2 | tile | warm | 11 | 62.934 | 176.129 | 904.975 | 904.975 | 317.0 [推测] | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t2-run1-20261101-210735.log:222-327 |

## 4. renderAnnot 分组（A/B）

> 派生自 extra 的 annot:1 / annot:0（或 renderAnnot:）。1=走注解/文本层路径，0=不走。

| app | scenario | phase | annot | n | p50 | p95 | max | 来源 |
|---|---|---|---|---|---|---|---|---|
| spike2t | T2 | full_curve | 0 | 9 | 102.054 | 299.091 | 299.091 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t2-run1-20261101-210735.log:342-351 |

## 5. 漂移哨兵

> 参照组：scenario=T2 phase=tile。比较最早与最晚 run 的中位数；差 > 20% 标 DRIFT_WARN。

| # | run_id | app | n | median | avg | ts | 来源 |
|---|---|---|---|---|---|---|---|
| 1 | tiles-t2-run1-20261101-210735 | spike2t | 71 | 142.848 | 241.363 | 11-01 13:08:06.762 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t2-run1-20261101-210735.log:205-332 |

- 只有 1 个 run，无法判断漂移（至少 2 个 run 才有哨兵意义）。

## 6. LOW_CONFIDENCE

> 组样本数 < 5。只作方向参考，不能写进结论。

| app | scenario | phase | n | p50 | 来源 |
|---|---|---|---|---|---|
| spike2t | T2 | full_ref | 2 | 922.726 | F:/VScode/Read/.agents/worktrees/dsh-2998e44b-11d5/spikes/spike2-results/raw/tiles-t2-run1-20261101-210735.log:325-335 |

## 7. 缺 cold/warm 标注的 render 记录

（无。）

## 8. 口径与限制

- 环境：Android emulator（x86_64/API35/1080x2400/dpi420/swiftshader/WHPX）。
- 绝对值只用于同批次对比；跨批次只报中位数与相对差（模拟器漂移 30-50%）。
- [推测] 列 = 模拟器 p50 x 1.80，仅用于给真机量级预期，不是实测。
- 失败/异常不测不报：extra=error: 行不参与统计。

