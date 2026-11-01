# Spike2 汇总 · render-r5

> 由 scripts/android/spike2/summarize.py 从 parse.py 的 CSV 汇总；所有数字可由 raw logcat 重算（CSV 的 src_file/src_line 指回 raw 行号）。
> 生成时间：2026-11-01T21:16:09+08:00　|　真机推算系数 x1.80（[推测]，PDFium 纯 CPU 光栅，真机/模拟器约 1.4-2.5）
> 口径：median=statistics.median；p95=nearest-rank（idx=ceil(q*n)-1）；单位 ms。

## 1. 输入与环境

| CSV | CSV 行数 | 参与统计行 | run 数 | apps |
|---|---|---|---|---|
| spikes\spike2-results\csv\render-r5-merged.csv | 260 | 260 | 5 | spike2 |

**RUNENV 环境头**

| run_id | app | apk | md5 | api | model | abi | build | ts | 来源 |
|---|---|---|---|---|---|---|---|---|---|
| render-r5-run1-20261101-205253 | spike2 | spikes/spike2-pdf/app/build/outputs/apk/debug/app-debug.apk | 8ea3edc683ebf0b5be2f32f32dc57bb9 | 35 | Android_SDK_built_for_x86_64 | x86_64 | 12368160 | 2026-11-01T20:53:00+0800 | spikes\spike2-results\raw\render-r5-run1-20261101-205253.log:1 |
| render-r5-run1-20261101-205342 | spike2 | spikes/spike2-pdf/app/build/outputs/apk/debug/app-debug.apk | 8ea3edc683ebf0b5be2f32f32dc57bb9 | 35 | Android_SDK_built_for_x86_64 | x86_64 | 12368160 | 2026-11-01T20:53:54+0800 | spikes\spike2-results\raw\render-r5-run1-20261101-205342.log:1 |
| render-r5-run1-20261101-205527 | spike2 | spikes/spike2-pdf/app/build/outputs/apk/debug/app-debug.apk | 8ea3edc683ebf0b5be2f32f32dc57bb9 | 35 | Android_SDK_built_for_x86_64 | x86_64 | 12368160 | 2026-11-01T20:55:35+0800 | spikes\spike2-results\raw\render-r5-run1-20261101-205527.log:1 |
| render-r5-run2-20261101-205433 | spike2 | spikes/spike2-pdf/app/build/outputs/apk/debug/app-debug.apk | 8ea3edc683ebf0b5be2f32f32dc57bb9 | 35 | Android_SDK_built_for_x86_64 | x86_64 | 12368160 | 2026-11-01T20:54:39+0800 | spikes\spike2-results\raw\render-r5-run2-20261101-205433.log:1 |
| render-r5-run2-20261101-205600 | spike2 | spikes/spike2-pdf/app/build/outputs/apk/debug/app-debug.apk | 8ea3edc683ebf0b5be2f32f32dc57bb9 | 35 | Android_SDK_built_for_x86_64 | x86_64 | 12368160 | 2026-11-01T20:56:05+0800 | spikes\spike2-results\raw\render-r5-run2-20261101-205600.log:1 |

## 2. ms 统计（跨 run，冷热混合，仅总览）

| app | scenario | phase | dims | n | min | p50 | p95 | max | 真机推算 p50 | run 数 | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| spike2 | S1 | open | - | 5 | 190.097 | 473.016 | 1114.952 | 1114.952 | 851.4 [推测] | 5 | spikes/spike2-results/raw/render-r5-run1-20261101-205253.log:129-129; spikes/spike2-results/raw/render-r5-run1-20261101-205342.log:147-147; spikes/spike2-results/raw/render-r5-run1-20261101-205527.log:116-116; spikes/spike2-results/raw/render-r5-run2-20261101-205433.log:130-130; spikes/spike2-results/raw/render-r5-run2-20261101-205600.log:122-122 |
| spike2 | S10 | check | 1080x1529 | 5 | 0.000 | 0.000 | 0.000 | 0.000 | 0.0 [推测] | 5 | spikes/spike2-results/raw/render-r5-run1-20261101-205253.log:220-220; spikes/spike2-results/raw/render-r5-run1-20261101-205342.log:234-234; spikes/spike2-results/raw/render-r5-run1-20261101-205527.log:202-202; spikes/spike2-results/raw/render-r5-run2-20261101-205433.log:221-221; spikes/spike2-results/raw/render-r5-run2-20261101-205600.log:203-203 |
| spike2 | S10 | present | 1080x1529,1080x2400 | 40 | 23.541 | 49.567 | 93.342 | 101.713 | 89.2 [推测] | 5 | spikes/spike2-results/raw/render-r5-run1-20261101-205253.log:199-219; spikes/spike2-results/raw/render-r5-run1-20261101-205342.log:218-233; spikes/spike2-results/raw/render-r5-run1-20261101-205527.log:179-201; spikes/spike2-results/raw/render-r5-run2-20261101-205433.log:204-220; spikes/spike2-results/raw/render-r5-run2-20261101-205600.log:180-202 |
| spike2 | S10 | render | 1080x1529,1080x2400 | 45 | 105.974 | 278.872 | 770.328 | 1079.189 | 502.0 [推测] | 5 | spikes/spike2-results/raw/render-r5-run1-20261101-205253.log:196-218; spikes/spike2-results/raw/render-r5-run1-20261101-205342.log:214-232; spikes/spike2-results/raw/render-r5-run1-20261101-205527.log:176-200; spikes/spike2-results/raw/render-r5-run2-20261101-205433.log:201-219; spikes/spike2-results/raw/render-r5-run2-20261101-205600.log:177-201 |
| spike2 | S9 | encode | 1188x1682 | 20 | 253.072 | 461.735 | 886.038 | 1163.983 | 831.1 [推测] | 5 | spikes/spike2-results/raw/render-r5-run1-20261101-205253.log:166-185; spikes/spike2-results/raw/render-r5-run1-20261101-205342.log:181-201; spikes/spike2-results/raw/render-r5-run1-20261101-205527.log:157-169; spikes/spike2-results/raw/render-r5-run2-20261101-205433.log:165-190; spikes/spike2-results/raw/render-r5-run2-20261101-205600.log:158-170 |
| spike2 | S9 | render | 1188x1682 | 20 | 95.704 | 417.285 | 1091.097 | 1424.152 | 751.1 [推测] | 5 | spikes/spike2-results/raw/render-r5-run1-20261101-205253.log:155-184; spikes/spike2-results/raw/render-r5-run1-20261101-205342.log:161-200; spikes/spike2-results/raw/render-r5-run1-20261101-205527.log:127-168; spikes/spike2-results/raw/render-r5-run2-20261101-205433.log:139-189; spikes/spike2-results/raw/render-r5-run2-20261101-205600.log:134-169 |
| spike2 | S9 | total | 1188x1682 | 20 | 409.723 | 935.981 | 1736.399 | 1927.570 | 1684.8 [推测] | 5 | spikes/spike2-results/raw/render-r5-run1-20261101-205253.log:168-187; spikes/spike2-results/raw/render-r5-run1-20261101-205342.log:183-203; spikes/spike2-results/raw/render-r5-run1-20261101-205527.log:159-171; spikes/spike2-results/raw/render-r5-run2-20261101-205433.log:167-192; spikes/spike2-results/raw/render-r5-run2-20261101-205600.log:160-172 |
| spike2 | S9 | write | 1188x1682 | 20 | 42.054 | 84.594 | 161.150 | 201.272 | 152.3 [推测] | 5 | spikes/spike2-results/raw/render-r5-run1-20261101-205253.log:167-186; spikes/spike2-results/raw/render-r5-run1-20261101-205342.log:182-202; spikes/spike2-results/raw/render-r5-run1-20261101-205527.log:158-170; spikes/spike2-results/raw/render-r5-run2-20261101-205433.log:166-191; spikes/spike2-results/raw/render-r5-run2-20261101-205600.log:159-171 |

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
| render-r5-run1-20261101-205253 | spike2 | S7 | pss | 24580 | 3902 | 685 | cold |  |  |  |  |  |  |  | spikes/spike2-results/raw/render-r5-run1-20261101-205253.log:110 |
| render-r5-run1-20261101-205342 | spike2 | S7 | pss | 24778 | 3958 | 689 | cold |  |  |  |  |  |  |  | spikes/spike2-results/raw/render-r5-run1-20261101-205342.log:129 |
| render-r5-run1-20261101-205527 | spike2 | S7 | pss | 24539 | 3885 | 685 | cold |  |  |  |  |  |  |  | spikes/spike2-results/raw/render-r5-run1-20261101-205527.log:101 |
| render-r5-run2-20261101-205433 | spike2 | S7 | pss | 22703 | 3921 | 685 | cold |  |  |  |  |  |  |  | spikes/spike2-results/raw/render-r5-run2-20261101-205433.log:110 |
| render-r5-run2-20261101-205600 | spike2 | S7 | pss | 22735 | 3937 | 685 | cold |  |  |  |  |  |  |  | spikes/spike2-results/raw/render-r5-run2-20261101-205600.log:104 |

## 3. 冷/热分组（跨 app 对比只允许同状态）

> cold = 同页同尺寸第一次渲染；warm = 之后。派生自 extra 的 cold:1 / cold:0（或 temp:）。

| app | scenario | phase | 状态 | n | min | p50 | p95 | max | 真机推算 p50 | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|
| spike2 | S9 | render | cold | 5 | 838.072 | 1000.008 | 1424.152 | 1424.152 | 1800.0 [推测] | spikes/spike2-results/raw/render-r5-run1-20261101-205253.log:155-155; spikes/spike2-results/raw/render-r5-run1-20261101-205342.log:161-161; spikes/spike2-results/raw/render-r5-run1-20261101-205527.log:127-127; spikes/spike2-results/raw/render-r5-run2-20261101-205433.log:139-139; spikes/spike2-results/raw/render-r5-run2-20261101-205600.log:134-134 |
| spike2 | S9 | render | warm | 15 | 95.704 | 372.219 | 769.603 | 769.603 | 670.0 [推测] | spikes/spike2-results/raw/render-r5-run1-20261101-205253.log:169-184; spikes/spike2-results/raw/render-r5-run1-20261101-205342.log:186-200; spikes/spike2-results/raw/render-r5-run1-20261101-205527.log:160-168; spikes/spike2-results/raw/render-r5-run2-20261101-205433.log:168-189; spikes/spike2-results/raw/render-r5-run2-20261101-205600.log:161-169 |

## 4. renderAnnot 分组（A/B）

> 派生自 extra 的 annot:1 / annot:0（或 renderAnnot:）。1=走注解/文本层路径，0=不走。

| app | scenario | phase | annot | n | p50 | p95 | max | 来源 |
|---|---|---|---|---|---|---|---|---|
| spike2 | S10 | render | 0 | 45 | 278.872 | 770.328 | 1079.189 | spikes/spike2-results/raw/render-r5-run1-20261101-205253.log:196-218; spikes/spike2-results/raw/render-r5-run1-20261101-205342.log:214-232; spikes/spike2-results/raw/render-r5-run1-20261101-205527.log:176-200; spikes/spike2-results/raw/render-r5-run2-20261101-205433.log:201-219; spikes/spike2-results/raw/render-r5-run2-20261101-205600.log:177-201 |

## 5. 漂移哨兵

> 参照组：scenario=S1 phase=open。比较最早与最晚 run 的中位数；差 > 20% 标 DRIFT_WARN。

| # | run_id | app | n | median | avg | ts | 来源 |
|---|---|---|---|---|---|---|---|
| 1 | render-r5-run1-20261101-205253 | spike2 | 1 | 287.802 | 287.802 | 11-01 12:53:07.366 | spikes/spike2-results/raw/render-r5-run1-20261101-205253.log:129-129 |
| 2 | render-r5-run1-20261101-205342 | spike2 | 1 | 1114.952 | 1114.952 | 11-01 12:54:06.613 | spikes/spike2-results/raw/render-r5-run1-20261101-205342.log:147-147 |
| 3 | render-r5-run2-20261101-205433 | spike2 | 1 | 476.253 | 476.253 | 11-01 12:54:47.490 | spikes/spike2-results/raw/render-r5-run2-20261101-205433.log:130-130 |
| 4 | render-r5-run1-20261101-205527 | spike2 | 1 | 190.097 | 190.097 | 11-01 12:55:41.446 | spikes/spike2-results/raw/render-r5-run1-20261101-205527.log:116-116 |
| 5 | render-r5-run2-20261101-205600 | spike2 | 1 | 473.016 | 473.016 | 11-01 12:56:13.985 | spikes/spike2-results/raw/render-r5-run2-20261101-205600.log:122-122 |

- 首 run median=287.802 ms，末 run median=473.016 ms，变化 +64.4% DRIFT_WARN
- **DRIFT_WARN**：本批首末漂移超阈值，跨批绝对值不可直接比，只能同批内相对比较。

## 6. LOW_CONFIDENCE

（所有组 n >= 5。）

## 7. 缺 cold/warm 标注的 render 记录

> 协议要求每条 render 记录带 cold/warm；以下记录缺失，不能与带标注数据混用。

| run_id | app | scenario | phase | n | 来源 |
|---|---|---|---|---|---|
| render-r5-run1-20261101-205253 | spike2 | S10 | render | 9 | spikes/spike2-results/raw/render-r5-run1-20261101-205253.log:196-218 |
| render-r5-run1-20261101-205342 | spike2 | S10 | render | 9 | spikes/spike2-results/raw/render-r5-run1-20261101-205342.log:214-232 |
| render-r5-run1-20261101-205527 | spike2 | S10 | render | 9 | spikes/spike2-results/raw/render-r5-run1-20261101-205527.log:176-200 |
| render-r5-run2-20261101-205433 | spike2 | S10 | render | 9 | spikes/spike2-results/raw/render-r5-run2-20261101-205433.log:201-219 |
| render-r5-run2-20261101-205600 | spike2 | S10 | render | 9 | spikes/spike2-results/raw/render-r5-run2-20261101-205600.log:177-201 |

## 8. 口径与限制

- 环境：Android emulator（x86_64/API35/1080x2400/dpi420/swiftshader/WHPX）。
- 绝对值只用于同批次对比；跨批次只报中位数与相对差（模拟器漂移 30-50%）。
- [推测] 列 = 模拟器 p50 x 1.80，仅用于给真机量级预期，不是实测。
- 失败/异常不测不报：extra=error: 行不参与统计。

