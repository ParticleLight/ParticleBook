# Spike2 汇总 · render-r1

> 由 scripts/android/spike2/summarize.py 从 parse.py 的 CSV 汇总；所有数字可由 raw logcat 重算（CSV 的 src_file/src_line 指回 raw 行号）。
> 生成时间：2026-11-01T21:16:07+08:00　|　真机推算系数 x1.80（[推测]，PDFium 纯 CPU 光栅，真机/模拟器约 1.4-2.5）
> 口径：median=statistics.median；p95=nearest-rank（idx=ceil(q*n)-1）；单位 ms。

## 1. 输入与环境

| CSV | CSV 行数 | 参与统计行 | run 数 | apps |
|---|---|---|---|---|
| spikes\spike2-results\csv\render-r1-merged.csv | 285 | 285 | 5 | spike2 |

**RUNENV 环境头**

| run_id | app | apk | md5 | api | model | abi | build | ts | 来源 |
|---|---|---|---|---|---|---|---|---|---|
| render-r1-run1-20261101-204131 | spike2 | spikes/spike2-pdf/app/build/outputs/apk/debug/app-debug.apk | 8ea3edc683ebf0b5be2f32f32dc57bb9 | 35 | Android_SDK_built_for_x86_64 | x86_64 | 12368160 | 2026-11-01T20:41:39+0800 | spikes\spike2-results\raw\render-r1-run1-20261101-204131.log:1 |
| render-r1-run1-20261101-204647 | spike2 | spikes/spike2-pdf/app/build/outputs/apk/debug/app-debug.apk | 8ea3edc683ebf0b5be2f32f32dc57bb9 | 35 | Android_SDK_built_for_x86_64 | x86_64 | 12368160 | 2026-11-01T20:46:55+0800 | spikes\spike2-results\raw\render-r1-run1-20261101-204647.log:1 |
| render-r1-run1-20261101-204732 | spike2 | spikes/spike2-pdf/app/build/outputs/apk/debug/app-debug.apk | 8ea3edc683ebf0b5be2f32f32dc57bb9 | 35 | Android_SDK_built_for_x86_64 | x86_64 | 12368160 | 2026-11-01T20:47:40+0800 | spikes\spike2-results\raw\render-r1-run1-20261101-204732.log:1 |
| render-r1-run1-20261101-204818 | spike2 | spikes/spike2-pdf/app/build/outputs/apk/debug/app-debug.apk | 8ea3edc683ebf0b5be2f32f32dc57bb9 | 35 | Android_SDK_built_for_x86_64 | x86_64 | 12368160 | 2026-11-01T20:48:25+0800 | spikes\spike2-results\raw\render-r1-run1-20261101-204818.log:1 |
| render-r1-run1-20261101-204859 | spike2 | spikes/spike2-pdf/app/build/outputs/apk/debug/app-debug.apk | 8ea3edc683ebf0b5be2f32f32dc57bb9 | 35 | Android_SDK_built_for_x86_64 | x86_64 | 12368160 | 2026-11-01T20:49:06+0800 | spikes\spike2-results\raw\render-r1-run1-20261101-204859.log:1 |

## 2. ms 统计（跨 run，冷热混合，仅总览）

| app | scenario | phase | dims | n | min | p50 | p95 | max | 真机推算 p50 | run 数 | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| spike2 | S1 | open | - | 5 | 324.164 | 540.686 | 665.414 | 665.414 | 973.2 [推测] | 5 | spikes/spike2-results/raw/render-r1-run1-20261101-204131.log:122-122; spikes/spike2-results/raw/render-r1-run1-20261101-204647.log:118-118; spikes/spike2-results/raw/render-r1-run1-20261101-204732.log:128-128; spikes/spike2-results/raw/render-r1-run1-20261101-204818.log:122-122; spikes/spike2-results/raw/render-r1-run1-20261101-204859.log:124-124 |
| spike2 | S2 | present | 1188x1682 | 95 | 18.029 | 42.206 | 251.172 | 705.791 | 76.0 [推测] | 5 | spikes/spike2-results/raw/render-r1-run1-20261101-204131.log:163-224; spikes/spike2-results/raw/render-r1-run1-20261101-204647.log:155-210; spikes/spike2-results/raw/render-r1-run1-20261101-204732.log:147-219; spikes/spike2-results/raw/render-r1-run1-20261101-204818.log:156-202; spikes/spike2-results/raw/render-r1-run1-20261101-204859.log:156-202 |
| spike2 | S2 | render | 1188x1682 | 95 | 4.924 | 102.717 | 582.050 | 1525.398 | 184.9 [推测] | 5 | spikes/spike2-results/raw/render-r1-run1-20261101-204131.log:133-223; spikes/spike2-results/raw/render-r1-run1-20261101-204647.log:152-208; spikes/spike2-results/raw/render-r1-run1-20261101-204732.log:139-218; spikes/spike2-results/raw/render-r1-run1-20261101-204818.log:133-201; spikes/spike2-results/raw/render-r1-run1-20261101-204859.log:134-201 |
| spike2 | S3 | render | 1188x1682 | 20 | 136.061 | 298.816 | 520.122 | 549.477 | 537.9 [推测] | 5 | spikes/spike2-results/raw/render-r1-run1-20261101-204131.log:226-232; spikes/spike2-results/raw/render-r1-run1-20261101-204647.log:212-216; spikes/spike2-results/raw/render-r1-run1-20261101-204732.log:222-229; spikes/spike2-results/raw/render-r1-run1-20261101-204818.log:204-215; spikes/spike2-results/raw/render-r1-run1-20261101-204859.log:204-214 |

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
| render-r1-run1-20261101-204131 | spike2 | S7 | pss | 24923 | 3897 | 702 | cold |  |  |  |  |  |  |  | spikes/spike2-results/raw/render-r1-run1-20261101-204131.log:103 |
| render-r1-run1-20261101-204647 | spike2 | S7 | pss | 24461 | 3886 | 685 | cold |  |  |  |  |  |  |  | spikes/spike2-results/raw/render-r1-run1-20261101-204647.log:101 |
| render-r1-run1-20261101-204732 | spike2 | S7 | pss | 22796 | 3926 | 685 | cold |  |  |  |  |  |  |  | spikes/spike2-results/raw/render-r1-run1-20261101-204732.log:110 |
| render-r1-run1-20261101-204818 | spike2 | S7 | pss | 24560 | 3908 | 685 | cold |  |  |  |  |  |  |  | spikes/spike2-results/raw/render-r1-run1-20261101-204818.log:105 |
| render-r1-run1-20261101-204859 | spike2 | S7 | pss | 24480 | 3890 | 685 | cold |  |  |  |  |  |  |  | spikes/spike2-results/raw/render-r1-run1-20261101-204859.log:106 |

## 3. 冷/热分组（跨 app 对比只允许同状态）

> cold = 同页同尺寸第一次渲染；warm = 之后。派生自 extra 的 cold:1 / cold:0（或 temp:）。

| app | scenario | phase | 状态 | n | min | p50 | p95 | max | 真机推算 p50 | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|
| spike2 | S2 | present | cold | 20 | 18.029 | 50.931 | 597.233 | 705.791 | 91.7 [推测] | spikes/spike2-results/raw/render-r1-run1-20261101-204131.log:163-211; spikes/spike2-results/raw/render-r1-run1-20261101-204647.log:155-193; spikes/spike2-results/raw/render-r1-run1-20261101-204732.log:147-210; spikes/spike2-results/raw/render-r1-run1-20261101-204818.log:156-193; spikes/spike2-results/raw/render-r1-run1-20261101-204859.log:156-194 |
| spike2 | S2 | present | warm | 75 | 20.978 | 38.528 | 91.171 | 126.506 | 69.4 [推测] | spikes/spike2-results/raw/render-r1-run1-20261101-204131.log:181-224; spikes/spike2-results/raw/render-r1-run1-20261101-204647.log:164-210; spikes/spike2-results/raw/render-r1-run1-20261101-204732.log:168-219; spikes/spike2-results/raw/render-r1-run1-20261101-204818.log:165-202; spikes/spike2-results/raw/render-r1-run1-20261101-204859.log:163-202 |
| spike2 | S2 | render | cold | 20 | 41.036 | 150.532 | 1089.814 | 1525.398 | 271.0 [推测] | spikes/spike2-results/raw/render-r1-run1-20261101-204131.log:133-210; spikes/spike2-results/raw/render-r1-run1-20261101-204647.log:152-192; spikes/spike2-results/raw/render-r1-run1-20261101-204732.log:139-209; spikes/spike2-results/raw/render-r1-run1-20261101-204818.log:133-192; spikes/spike2-results/raw/render-r1-run1-20261101-204859.log:134-193 |
| spike2 | S2 | render | warm | 75 | 4.924 | 82.364 | 409.732 | 561.947 | 148.3 [推测] | spikes/spike2-results/raw/render-r1-run1-20261101-204131.log:180-223; spikes/spike2-results/raw/render-r1-run1-20261101-204647.log:163-208; spikes/spike2-results/raw/render-r1-run1-20261101-204732.log:167-218; spikes/spike2-results/raw/render-r1-run1-20261101-204818.log:164-201; spikes/spike2-results/raw/render-r1-run1-20261101-204859.log:162-201 |
| spike2 | S3 | render | cold | 5 | 143.363 | 199.300 | 397.943 | 397.943 | 358.7 [推测] | spikes/spike2-results/raw/render-r1-run1-20261101-204131.log:226-226; spikes/spike2-results/raw/render-r1-run1-20261101-204647.log:212-212; spikes/spike2-results/raw/render-r1-run1-20261101-204732.log:222-222; spikes/spike2-results/raw/render-r1-run1-20261101-204818.log:204-204; spikes/spike2-results/raw/render-r1-run1-20261101-204859.log:204-204 |
| spike2 | S3 | render | warm | 15 | 136.061 | 313.039 | 549.477 | 549.477 | 563.5 [推测] | spikes/spike2-results/raw/render-r1-run1-20261101-204131.log:230-232; spikes/spike2-results/raw/render-r1-run1-20261101-204647.log:214-216; spikes/spike2-results/raw/render-r1-run1-20261101-204732.log:227-229; spikes/spike2-results/raw/render-r1-run1-20261101-204818.log:206-215; spikes/spike2-results/raw/render-r1-run1-20261101-204859.log:212-214 |

## 4. renderAnnot 分组（A/B）

> 派生自 extra 的 annot:1 / annot:0（或 renderAnnot:）。1=走注解/文本层路径，0=不走。

| app | scenario | phase | annot | n | p50 | p95 | max | 来源 |
|---|---|---|---|---|---|---|---|---|
| spike2 | S2 | render | 1 | 95 | 102.717 | 582.050 | 1525.398 | spikes/spike2-results/raw/render-r1-run1-20261101-204131.log:133-223; spikes/spike2-results/raw/render-r1-run1-20261101-204647.log:152-208; spikes/spike2-results/raw/render-r1-run1-20261101-204732.log:139-218; spikes/spike2-results/raw/render-r1-run1-20261101-204818.log:133-201; spikes/spike2-results/raw/render-r1-run1-20261101-204859.log:134-201 |

## 5. 漂移哨兵

> 参照组：scenario=S2 phase=render page=0。比较最早与最晚 run 的中位数；差 > 20% 标 DRIFT_WARN。

| # | run_id | app | n | median | avg | ts | 来源 |
|---|---|---|---|---|---|---|---|
| 1 | render-r1-run1-20261101-204131 | spike2 | 4 | 426.952 | 517.443 | 11-01 12:41:46.368 | spikes/spike2-results/raw/render-r1-run1-20261101-204131.log:133-185 |
| 2 | render-r1-run1-20261101-204647 | spike2 | 4 | 418.932 | 656.491 | 11-01 12:47:03.705 | spikes/spike2-results/raw/render-r1-run1-20261101-204647.log:152-167 |
| 3 | render-r1-run1-20261101-204732 | spike2 | 4 | 395.462 | 536.019 | 11-01 12:47:48.293 | spikes/spike2-results/raw/render-r1-run1-20261101-204732.log:139-172 |
| 4 | render-r1-run1-20261101-204818 | spike2 | 4 | 365.313 | 509.530 | 11-01 12:48:32.325 | spikes/spike2-results/raw/render-r1-run1-20261101-204818.log:133-168 |
| 5 | render-r1-run1-20261101-204859 | spike2 | 4 | 345.632 | 395.115 | 11-01 12:49:13.033 | spikes/spike2-results/raw/render-r1-run1-20261101-204859.log:134-169 |

- 首 run median=426.952 ms，末 run median=345.632 ms，变化 -19.0% 

## 6. LOW_CONFIDENCE

（所有组 n >= 5。）

## 7. 缺 cold/warm 标注的 render 记录

（无。）

## 8. 口径与限制

- 环境：Android emulator（x86_64/API35/1080x2400/dpi420/swiftshader/WHPX）。
- 绝对值只用于同批次对比；跨批次只报中位数与相对差（模拟器漂移 30-50%）。
- [推测] 列 = 模拟器 p50 x 1.80，仅用于给真机量级预期，不是实测。
- 失败/异常不测不报：extra=error: 行不参与统计。

