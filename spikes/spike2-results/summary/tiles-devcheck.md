# Spike2 汇总 · tiles-devcheck

> 由 scripts/android/spike2/summarize.py 从 parse.py 的 CSV 汇总；所有数字可由 raw logcat 重算（CSV 的 src_file/src_line 指回 raw 行号）。
> 生成时间：2026-11-01T20:20:46+08:00　|　真机推算系数 x1.80（[推测]，PDFium 纯 CPU 光栅，真机/模拟器约 1.4-2.5）
> 口径：median=statistics.median；p95=nearest-rank（idx=ceil(q*n)-1）；单位 ms。

## 1. 输入与环境

| CSV | CSV 行数 | 参与统计行 | run 数 | apps |
|---|---|---|---|---|
| spikes\spike2-results\csv\tiles-devcheck.csv | 154 | 154 | 2 | spike2t |

（输入中没有 RUNENV 行；环境头缺失 -> 该批数据只能做相对比较。）

## 2. ms 统计（跨 run，冷热混合，仅总览）

| app | scenario | phase | dims | n | min | p50 | p95 | max | 真机推算 p50 | run 数 | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| spike2t | PBTIME | first_frame | - | 2 | 1883.409 | 2709.037 | 3534.665 | 3534.665 | 4876.3 [推测] | 2 | spikes/spike2-results/raw/tiles-dev-20261101-193349.log:3-3; spikes/spike2-results/raw/tiles-dev-20261101-193520.log:3-3 |
| spike2t | PBTIME | read_path | - | 1 | 2714.282 | 2714.282 | 2714.282 | 2714.282 | 4885.7 [推测] | 1 | spikes/spike2-results/raw/tiles-dev-20261101-193349.log:15-15 |
| spike2t | PBTIME | refresh_hz | - | 2 | 60.000 | 60.000 | 60.000 | 60.000 | 108.0 [推测] | 2 | spikes/spike2-results/raw/tiles-dev-20261101-193349.log:2-2; spikes/spike2-results/raw/tiles-dev-20261101-193520.log:2-2 |
| spike2t | T1 | first_screen | 1080x2400 | 1 | 2074.532 | 2074.532 | 2074.532 | 2074.532 | 3734.2 [推测] | 1 | spikes/spike2-results/raw/tiles-dev-20261101-193349.log:14-14 |
| spike2t | T1 | lowres | - | 2 | 30.615 | 174.156 | 317.697 | 317.697 | 313.5 [推测] | 2 | spikes/spike2-results/raw/tiles-dev-20261101-193349.log:18-18; spikes/spike2-results/raw/tiles-dev-20261101-193520.log:6-6 |
| spike2t | T1 | open | - | 2 | 389.144 | 514.447 | 639.751 | 639.751 | 926.0 [推测] | 2 | spikes/spike2-results/raw/tiles-dev-20261101-193349.log:4-4; spikes/spike2-results/raw/tiles-dev-20261101-193520.log:4-4 |
| spike2t | T1 | tile | 512x505,512x512,56x505,56x512 | 9 | 0.301 | 27.927 | 523.456 | 523.456 | 50.3 [推测] | 1 | spikes/spike2-results/raw/tiles-dev-20261101-193349.log:5-13 |
| spike2t | T1 | tileprobe | 512x512 | 2 | 0.000 | 0.000 | 0.000 | 0.000 | 0.0 [推测] | 2 | spikes/spike2-results/raw/tiles-dev-20261101-193349.log:17-17; spikes/spike2-results/raw/tiles-dev-20261101-193520.log:5-5 |
| spike2t | T2 | full | 1350x1911 | 1 | 142.869 | 142.869 | 142.869 | 142.869 | 257.2 [推测] | 1 | spikes/spike2-results/raw/tiles-dev-20261101-193520.log:96-96 |
| spike2t | T2 | full_curve | 1080x1529 | 10 | 35.090 | 100.829 | 131.882 | 131.882 | 181.5 [推测] | 1 | spikes/spike2-results/raw/tiles-dev-20261101-193520.log:98-107 |
| spike2t | T2 | full_ref | 1080x1529,2160x3058,3240x4587 | 3 | 205.327 | 237.138 | 462.058 | 462.058 | 426.8 [推测] | 1 | spikes/spike2-results/raw/tiles-dev-20261101-193520.log:78-94 |
| spike2t | T2 | overload_probe | 512x512 | 1 | 0.000 | 0.000 | 0.000 | 0.000 | 0.0 [推测] | 1 | spikes/spike2-results/raw/tiles-dev-20261101-193520.log:42-42 |
| spike2t | T2 | tile | 1024x1024,1080x1529,512x512 | 96 | 13.468 | 99.207 | 497.131 | 625.273 | 178.6 [推测] | 2 | spikes/spike2-results/raw/tiles-dev-20261101-193349.log:19-45; spikes/spike2-results/raw/tiles-dev-20261101-193520.log:7-92 |
| spike2t | T2 | tile_overhead | 1350x1911 | 1 | -1.089 | -1.089 | -1.089 | -1.089 | -2.0 [推测] | 1 | spikes/spike2-results/raw/tiles-dev-20261101-193520.log:97-97 |
| spike2t | T2 | tiles_serial | 1350x1911 | 1 | 141.780 | 141.780 | 141.780 | 141.780 | 255.2 [推测] | 1 | spikes/spike2-results/raw/tiles-dev-20261101-193520.log:95-95 |

> 第 2 节把 cold/warm 混在一起，只用于总览；跨 app / 跨路径对比必须用第 3 节的同冷热分组。
> 注意：tiles app 的 *_stat 行（tile_stat/frame_stat/gesture_stat…）是 app 侧已聚合的块统计，不按单样本计入本节，单独列在第 2b 节。

## 2b. app 侧聚合行（*_stat：n/p50/p95 由 app 自己汇总）

> 这些行每个是一次「块」测量（app 内部 n 次取 avg/p50/p95）；本节报块数、总样本数与各块 p50 的中位数，不参与第 2/3 节的逐样本统计。

| app | scenario | phase | 块标签 | 块数 | 总样本 | 每块 n | dims | 块 p50 中位 | 块 p95 最大 | fps 中位 | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| spike2t | T2 | tile_stat | zoom:1.0,tx:0,ty:0,kpx:256,ab:pagehandle,pagehandle:cached | 2 | 10 | 5 | 512x512 | 38.295 | 176.020 | 25.4 | spikes/spike2-results/raw/tiles-dev-20261101-193349.log:32-32; spikes/spike2-results/raw/tiles-dev-20261101-193520.log:20-20 |
| spike2t | T2 | tile_stat | zoom:1.0,tx:0,ty:0,kpx:256,ab:pagehandle,pagehandle:fresh | 2 | 10 | 5 | 512x512 | 93.015 | 376.600 | 8.4 | spikes/spike2-results/raw/tiles-dev-20261101-193349.log:25-25; spikes/spike2-results/raw/tiles-dev-20261101-193520.log:13-13 |
| spike2t | T2 | tile_stat | zoom:1.0,tx:0,ty:0,tilesize:1024,ab:overload,overload:matrix | 1 | 5 | 5 | 1024x1024 | 252.430 | 445.760 | 3.3 | spikes/spike2-results/raw/tiles-dev-20261101-193520.log:63-63 |
| spike2t | T2 | tile_stat | zoom:1.0,tx:0,ty:0,tilesize:1024,ab:overload,overload:offset | 1 | 5 | 5 | 1024x1024 | 293.930 | 452.280 | 3.4 | spikes/spike2-results/raw/tiles-dev-20261101-193520.log:70-70 |
| spike2t | T2 | tile_stat | zoom:1.0,tx:0,ty:0,tilesize:1024,kpx:1048,ab:tilesize | 2 | 10 | 5 | 1024x1024 | 403.615 | 529.280 | 2.6 | spikes/spike2-results/raw/tiles-dev-20261101-193349.log:46-46; spikes/spike2-results/raw/tiles-dev-20261101-193520.log:34-34 |
| spike2t | T2 | tile_stat | zoom:1.0,tx:0,ty:0,tilesize:2048,kpx:1651,ab:tilesize | 1 | 5 | 5 | 1080x1529 | 266.950 | 601.530 | 3.1 | spikes/spike2-results/raw/tiles-dev-20261101-193520.log:41-41 |
| spike2t | T2 | tile_stat | zoom:1.0,tx:0,ty:0,tilesize:512,ab:overload,overload:matrix | 1 | 5 | 5 | 512x512 | 136.130 | 204.110 | 7.2 | spikes/spike2-results/raw/tiles-dev-20261101-193520.log:49-49 |
| spike2t | T2 | tile_stat | zoom:1.0,tx:0,ty:0,tilesize:512,ab:overload,overload:offset | 1 | 5 | 5 | 512x512 | 45.180 | 114.060 | 17.6 | spikes/spike2-results/raw/tiles-dev-20261101-193520.log:56-56 |
| spike2t | T2 | tile_stat | zoom:1.0,tx:0,ty:0,tilesize:512,kpx:262,ab:tilesize | 2 | 10 | 5 | 512x512 | 51.860 | 227.690 | 17.8 | spikes/spike2-results/raw/tiles-dev-20261101-193349.log:39-39; spikes/spike2-results/raw/tiles-dev-20261101-193520.log:27-27 |
| spike2t | T2 | tile_stat | zoom:1.0,tx:1,ty:1,kpx:262,scale:1.818,pagehandle:cached | 1 | 5 | 5 | 512x512 | 85.780 | 133.550 | 11.7 | spikes/spike2-results/raw/tiles-dev-20261101-193520.log:77-77 |
| spike2t | T2 | tile_stat | zoom:2.0,tx:1,ty:1,kpx:262,scale:3.636,pagehandle:cached | 1 | 5 | 5 | 512x512 | 54.060 | 93.830 | 18.5 | spikes/spike2-results/raw/tiles-dev-20261101-193520.log:85-85 |
| spike2t | T2 | tile_stat | zoom:3.0,tx:1,ty:1,kpx:262,scale:5.455,pagehandle:cached | 1 | 5 | 5 | 512x512 | 37.950 | 65.030 | 26.5 | spikes/spike2-results/raw/tiles-dev-20261101-193520.log:93-93 |

## 2c. T1 首屏两个口径（first_screen 不含 open；read_path_ms 含 open）

> L3「首屏 ≤200ms」按协议用 `first_screen`（setPage → 9 个 tile 渲染并呈现一帧），**不含 open()**；用户体感对应 `read_path_ms`（open + 首屏）。两列并列，禁止混用。

| run_id | app | first_screen ms | open_ms | read_path_ms | from_create_ms | tiles | 来源 |
|---|---|---|---|---|---|---|---|
| tiles-dev-20261101-193349 | spike2t | 2074.532 | 639.800 | 2714.300 | 8022.800 | 9 | spikes/spike2-results/raw/tiles-dev-20261101-193349.log:14 |

## 2d. 内存与缓存（PSS / cache 状态行）

> 这些行没有 ms 语义，不进耗时统计；T5 的 PSS 必须同一进程生命周期内取前后值得出增量。

| run_id | app | scenario | phase | pss_kb | native_kb | java_kb | tag | hit | evict | miss | bytes | limit | tiles | cycles | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| tiles-dev-20261101-193520 | spike2t | ALL | pss | 58735 | 29654 | 832 |  |  |  |  |  |  |  |  | spikes/spike2-results/raw/tiles-dev-20261101-193520.log:109 |

## 3. 冷/热分组（跨 app 对比只允许同状态）

> cold = 同页同尺寸第一次渲染；warm = 之后。派生自 extra 的 cold:1 / cold:0（或 temp:）。

| app | scenario | phase | 状态 | n | min | p50 | p95 | max | 真机推算 p50 | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|
| spike2t | T2 | tile | warm | 16 | 13.468 | 129.237 | 625.273 | 625.273 | 232.6 [推测] | spikes/spike2-results/raw/tiles-dev-20261101-193349.log:19-40; spikes/spike2-results/raw/tiles-dev-20261101-193520.log:7-87 |

## 4. renderAnnot 分组（A/B）

> 派生自 extra 的 annot:1 / annot:0（或 renderAnnot:）。1=走注解/文本层路径，0=不走。

| app | scenario | phase | annot | n | p50 | p95 | max | 来源 |
|---|---|---|---|---|---|---|---|---|
| spike2t | T2 | full_curve | 0 | 10 | 100.829 | 131.882 | 131.882 | spikes/spike2-results/raw/tiles-dev-20261101-193520.log:98-107 |

## 5. 漂移哨兵

> 参照组：scenario=T1 phase=first_screen page=0。比较最早与最晚 run 的中位数；差 > 20% 标 DRIFT_WARN。

| # | run_id | app | n | median | avg | ts | 来源 |
|---|---|---|---|---|---|---|---|
| 1 | tiles-dev-20261101-193349 | spike2t | 1 | 2074.532 | 2074.532 | 11-01 11:34:07.518 | spikes/spike2-results/raw/tiles-dev-20261101-193349.log:14-14 |

- 只有 1 个 run，无法判断漂移（至少 2 个 run 才有哨兵意义）。

## 6. LOW_CONFIDENCE

> 组样本数 < 5。只作方向参考，不能写进结论。

| app | scenario | phase | n | p50 | 来源 |
|---|---|---|---|---|---|
| spike2t | PBTIME | first_frame | 2 | 2709.037 | spikes/spike2-results/raw/tiles-dev-20261101-193349.log:3-3; spikes/spike2-results/raw/tiles-dev-20261101-193520.log:3-3 |
| spike2t | PBTIME | read_path | 1 | 2714.282 | spikes/spike2-results/raw/tiles-dev-20261101-193349.log:15-15 |
| spike2t | PBTIME | refresh_hz | 2 | 60.000 | spikes/spike2-results/raw/tiles-dev-20261101-193349.log:2-2; spikes/spike2-results/raw/tiles-dev-20261101-193520.log:2-2 |
| spike2t | T1 | first_screen | 1 | 2074.532 | spikes/spike2-results/raw/tiles-dev-20261101-193349.log:14-14 |
| spike2t | T1 | lowres | 2 | 174.156 | spikes/spike2-results/raw/tiles-dev-20261101-193349.log:18-18; spikes/spike2-results/raw/tiles-dev-20261101-193520.log:6-6 |
| spike2t | T1 | open | 2 | 514.447 | spikes/spike2-results/raw/tiles-dev-20261101-193349.log:4-4; spikes/spike2-results/raw/tiles-dev-20261101-193520.log:4-4 |
| spike2t | T1 | tileprobe | 2 | 0.000 | spikes/spike2-results/raw/tiles-dev-20261101-193349.log:17-17; spikes/spike2-results/raw/tiles-dev-20261101-193520.log:5-5 |
| spike2t | T2 | full | 1 | 142.869 | spikes/spike2-results/raw/tiles-dev-20261101-193520.log:96-96 |
| spike2t | T2 | full_ref | 3 | 237.138 | spikes/spike2-results/raw/tiles-dev-20261101-193520.log:78-94 |
| spike2t | T2 | overload_probe | 1 | 0.000 | spikes/spike2-results/raw/tiles-dev-20261101-193520.log:42-42 |
| spike2t | T2 | tile_overhead | 1 | -1.089 | spikes/spike2-results/raw/tiles-dev-20261101-193520.log:97-97 |
| spike2t | T2 | tiles_serial | 1 | 141.780 | spikes/spike2-results/raw/tiles-dev-20261101-193520.log:95-95 |

## 7. 缺 cold/warm 标注的 render 记录

（无。）

## 8. 口径与限制

- 环境：Android emulator（x86_64/API35/1080x2400/dpi420/swiftshader/WHPX）。
- 绝对值只用于同批次对比；跨批次只报中位数与相对差（模拟器漂移 30-50%）。
- [推测] 列 = 模拟器 p50 x 1.80，仅用于给真机量级预期，不是实测。
- 失败/异常不测不报：extra=error: 行不参与统计。

