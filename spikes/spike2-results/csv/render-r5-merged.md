# Spike2 基准统计 · render-r5-merged

> 由 `scripts/android/spike2/parse.py` 生成；本文件所有数字均来自解析结果，未做估算。
> 生成时间：2026-11-01T20:58:11+08:00　|　解析失败行：0

## 1. 环境与输入

| 文件 | run_id | app | 总行数 | 记录行 | 解析失败 |
|---|---|---|---|---|---|
| spikes\spike2-results\raw\render-r5-run1-20261101-205253.log | render-r5-run1-20261101-205253 | spike2 | 222 | 52 | 0 |
| spikes\spike2-results\raw\render-r5-run1-20261101-205342.log | render-r5-run1-20261101-205342 | spike2 | 237 | 52 | 0 |
| spikes\spike2-results\raw\render-r5-run1-20261101-205527.log | render-r5-run1-20261101-205527 | spike2 | 212 | 52 | 0 |
| spikes\spike2-results\raw\render-r5-run2-20261101-205433.log | render-r5-run2-20261101-205433 | spike2 | 224 | 52 | 0 |
| spikes\spike2-results\raw\render-r5-run2-20261101-205600.log | render-r5-run2-20261101-205600 | spike2 | 210 | 52 | 0 |

**RUNENV 元数据**

| 文件 | run_id | app | apk_md5 | scenario | ts |
|---|---|---|---|---|---|
| spikes\spike2-results\raw\render-r5-run1-20261101-205253.log | render-r5-run1-20261101-205253 | spike2 | 8ea3edc683ebf0b5be2f32f32dc57bb9 | S9,S10 | 2026-11-01T20:53:00+0800 |
| spikes\spike2-results\raw\render-r5-run1-20261101-205342.log | render-r5-run1-20261101-205342 | spike2 | 8ea3edc683ebf0b5be2f32f32dc57bb9 | S9,S10 | 2026-11-01T20:53:54+0800 |
| spikes\spike2-results\raw\render-r5-run1-20261101-205527.log | render-r5-run1-20261101-205527 | spike2 | 8ea3edc683ebf0b5be2f32f32dc57bb9 | S9,S10 | 2026-11-01T20:55:35+0800 |
| spikes\spike2-results\raw\render-r5-run2-20261101-205433.log | render-r5-run2-20261101-205433 | spike2 | 8ea3edc683ebf0b5be2f32f32dc57bb9 | S9,S10 | 2026-11-01T20:54:39+0800 |
| spikes\spike2-results\raw\render-r5-run2-20261101-205600.log | render-r5-run2-20261101-205600 | spike2 | 8ea3edc683ebf0b5be2f32f32dc57bb9 | S9,S10 | 2026-11-01T20:56:05+0800 |

| 参数 | 值 |
|---|---|
| --app | spike2 |
| --drop-first | 0 |
| --drop-mode | first-per-phase |
| --min-samples | 5 |
| 输入路径 | spikes/spike2-results/raw/render-r5-run1-20261101-205253.log spikes/spike2-results/raw/render-r5-run1-20261101-205342.log spikes/spike2-results/raw/render-r5-run1-20261101-205527.log spikes/spike2-results/raw/render-r5-run2-20261101-205433.log spikes/spike2-results/raw/render-r5-run2-20261101-205600.log |

## 2. 样本构成

| record | CSV 行数 |
|---|---|
| AMSTART | 5 |
| PBDONE | 20 |
| PBPERF | 225 |
| PBTIME | 5 |
| RUNENV | 5 |

- 输入 5 个文件 / 1105 行；识别为本契约记录 260 行；CSV 总计 260 行。
- 参与统计的样本（ms 非空、非 warmup、非 error）：175 行；其中 ms 样本 175 行、pss 样本 5 行。
- warmup：60 行（其中剔除 60 行；drop_mode=first-per-phase，仍保留在 CSV，统计排除）。
- error：0 行（confidence=LOW_CONFIDENCE，不进 ms 统计）。

## 3. ms 统计（跨 run）

> 口径：median=`statistics.median`；p50/p95=nearest-rank（idx=ceil(q*n)-1，1-based）；单位 ms，保留 3 位小数。
> 参与：PBPERF（ms 非空、非 warmup、非 error）、PBPDF、AMSTART、PBTIME；PBDONE/RUNENV 不参与；pss 行见第 5 节。

| app | scenario | phase | n | median | p50 | p95 | max | min | 来源 |
|---|---|---|---|---|---|---|---|---|---|
| spike2 | S1 | open | 5 | 473.016 | 473.016 | 1114.952 | 1114.952 | 190.097 | spikes\spike2-results\raw\render-r5-run1-20261101-205253.log:129-129(n=1)<br>spikes\spike2-results\raw\render-r5-run1-20261101-205342.log:147-147(n=1)<br>spikes\spike2-results\raw\render-r5-run1-20261101-205527.log:116-116(n=1)<br>spikes\spike2-results\raw\render-r5-run2-20261101-205433.log:130-130(n=1)<br>spikes\spike2-results\raw\render-r5-run2-20261101-205600.log:122-122(n=1) |
| spike2 | S10 | check | 5 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | spikes\spike2-results\raw\render-r5-run1-20261101-205253.log:220-220(n=1)<br>spikes\spike2-results\raw\render-r5-run1-20261101-205342.log:234-234(n=1)<br>spikes\spike2-results\raw\render-r5-run1-20261101-205527.log:202-202(n=1)<br>spikes\spike2-results\raw\render-r5-run2-20261101-205433.log:221-221(n=1)<br>spikes\spike2-results\raw\render-r5-run2-20261101-205600.log:203-203(n=1) |
| spike2 | S10 | present | 40 | 49.567 | 48.119 | 93.342 | 101.713 | 23.541 | spikes\spike2-results\raw\render-r5-run1-20261101-205253.log:199-219(n=8)<br>spikes\spike2-results\raw\render-r5-run1-20261101-205342.log:218-233(n=8)<br>spikes\spike2-results\raw\render-r5-run1-20261101-205527.log:179-201(n=8)<br>spikes\spike2-results\raw\render-r5-run2-20261101-205433.log:204-220(n=8)<br>spikes\spike2-results\raw\render-r5-run2-20261101-205600.log:180-202(n=8) |
| spike2 | S10 | render | 45 | 278.872 | 278.872 | 770.328 | 1079.189 | 105.974 | spikes\spike2-results\raw\render-r5-run1-20261101-205253.log:196-218(n=9)<br>spikes\spike2-results\raw\render-r5-run1-20261101-205342.log:214-232(n=9)<br>spikes\spike2-results\raw\render-r5-run1-20261101-205527.log:176-200(n=9)<br>spikes\spike2-results\raw\render-r5-run2-20261101-205433.log:201-219(n=9)<br>spikes\spike2-results\raw\render-r5-run2-20261101-205600.log:177-201(n=9) |
| spike2 | S9 | encode | 20 | 461.735 | 455.721 | 886.038 | 1163.983 | 253.072 | spikes\spike2-results\raw\render-r5-run1-20261101-205253.log:166-185(n=4)<br>spikes\spike2-results\raw\render-r5-run1-20261101-205342.log:181-201(n=4)<br>spikes\spike2-results\raw\render-r5-run1-20261101-205527.log:157-169(n=4)<br>spikes\spike2-results\raw\render-r5-run2-20261101-205433.log:165-190(n=4)<br>spikes\spike2-results\raw\render-r5-run2-20261101-205600.log:158-170(n=4) |
| spike2 | S9 | render | 20 | 417.285 | 409.151 | 1091.097 | 1424.152 | 95.704 | spikes\spike2-results\raw\render-r5-run1-20261101-205253.log:155-184(n=4)<br>spikes\spike2-results\raw\render-r5-run1-20261101-205342.log:161-200(n=4)<br>spikes\spike2-results\raw\render-r5-run1-20261101-205527.log:127-168(n=4)<br>spikes\spike2-results\raw\render-r5-run2-20261101-205433.log:139-189(n=4)<br>spikes\spike2-results\raw\render-r5-run2-20261101-205600.log:134-169(n=4) |
| spike2 | S9 | total | 20 | 935.981 | 929.040 | 1736.399 | 1927.570 | 409.723 | spikes\spike2-results\raw\render-r5-run1-20261101-205253.log:168-187(n=4)<br>spikes\spike2-results\raw\render-r5-run1-20261101-205342.log:183-203(n=4)<br>spikes\spike2-results\raw\render-r5-run1-20261101-205527.log:159-171(n=4)<br>spikes\spike2-results\raw\render-r5-run2-20261101-205433.log:167-192(n=4)<br>spikes\spike2-results\raw\render-r5-run2-20261101-205600.log:160-172(n=4) |
| spike2 | S9 | write | 20 | 84.594 | 80.758 | 161.150 | 201.272 | 42.054 | spikes\spike2-results\raw\render-r5-run1-20261101-205253.log:167-186(n=4)<br>spikes\spike2-results\raw\render-r5-run1-20261101-205342.log:182-202(n=4)<br>spikes\spike2-results\raw\render-r5-run1-20261101-205527.log:158-170(n=4)<br>spikes\spike2-results\raw\render-r5-run2-20261101-205433.log:166-191(n=4)<br>spikes\spike2-results\raw\render-r5-run2-20261101-205600.log:159-171(n=4) |

## 4. ms 统计（按 run）

| run_id | app | scenario | phase | n | median | p50 | p95 | max | min | 来源 |
|---|---|---|---|---|---|---|---|---|---|---|
| render-r5-run1-20261101-205253 | spike2 | S1 | open | 1 | 287.802 | 287.802 | 287.802 | 287.802 | 287.802 | spikes\spike2-results\raw\render-r5-run1-20261101-205253.log:129-129(n=1) |
| render-r5-run1-20261101-205253 | spike2 | S10 | check | 1 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | spikes\spike2-results\raw\render-r5-run1-20261101-205253.log:220-220(n=1) |
| render-r5-run1-20261101-205253 | spike2 | S10 | present | 8 | 52.478 | 51.016 | 86.606 | 86.606 | 32.903 | spikes\spike2-results\raw\render-r5-run1-20261101-205253.log:199-219(n=8) |
| render-r5-run1-20261101-205253 | spike2 | S10 | render | 9 | 278.872 | 278.872 | 770.328 | 770.328 | 192.510 | spikes\spike2-results\raw\render-r5-run1-20261101-205253.log:196-218(n=9) |
| render-r5-run1-20261101-205253 | spike2 | S9 | encode | 4 | 609.917 | 608.793 | 686.574 | 686.574 | 312.317 | spikes\spike2-results\raw\render-r5-run1-20261101-205253.log:166-185(n=4) |
| render-r5-run1-20261101-205253 | spike2 | S9 | render | 4 | 456.001 | 429.334 | 1424.152 | 1424.152 | 425.419 | spikes\spike2-results\raw\render-r5-run1-20261101-205253.log:155-184(n=4) |
| render-r5-run1-20261101-205253 | spike2 | S9 | total | 4 | 1161.827 | 1156.505 | 1204.993 | 1204.993 | 942.923 | spikes\spike2-results\raw\render-r5-run1-20261101-205253.log:168-187(n=4) |
| render-r5-run1-20261101-205253 | spike2 | S9 | write | 4 | 118.812 | 93.000 | 201.272 | 201.272 | 65.043 | spikes\spike2-results\raw\render-r5-run1-20261101-205253.log:167-186(n=4) |
| render-r5-run1-20261101-205342 | spike2 | S1 | open | 1 | 1114.952 | 1114.952 | 1114.952 | 1114.952 | 1114.952 | spikes\spike2-results\raw\render-r5-run1-20261101-205342.log:147-147(n=1) |
| render-r5-run1-20261101-205342 | spike2 | S10 | check | 1 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | spikes\spike2-results\raw\render-r5-run1-20261101-205342.log:234-234(n=1) |
| render-r5-run1-20261101-205342 | spike2 | S10 | present | 8 | 62.029 | 60.033 | 101.713 | 101.713 | 34.082 | spikes\spike2-results\raw\render-r5-run1-20261101-205342.log:218-233(n=8) |
| render-r5-run1-20261101-205342 | spike2 | S10 | render | 9 | 398.996 | 398.996 | 1079.189 | 1079.189 | 158.527 | spikes\spike2-results\raw\render-r5-run1-20261101-205342.log:214-232(n=9) |
| render-r5-run1-20261101-205342 | spike2 | S9 | encode | 4 | 566.308 | 467.749 | 705.854 | 705.854 | 455.721 | spikes\spike2-results\raw\render-r5-run1-20261101-205342.log:181-201(n=4) |
| render-r5-run1-20261101-205342 | spike2 | S9 | render | 4 | 380.067 | 372.219 | 945.708 | 945.708 | 222.435 | spikes\spike2-results\raw\render-r5-run1-20261101-205342.log:161-200(n=4) |
| render-r5-run1-20261101-205342 | spike2 | S9 | total | 4 | 1022.203 | 929.040 | 1126.222 | 1126.222 | 753.777 | spikes\spike2-results\raw\render-r5-run1-20261101-205342.log:183-203(n=4) |
| render-r5-run1-20261101-205342 | spike2 | S9 | write | 4 | 82.346 | 63.593 | 129.882 | 129.882 | 62.582 | spikes\spike2-results\raw\render-r5-run1-20261101-205342.log:182-202(n=4) |
| render-r5-run1-20261101-205527 | spike2 | S1 | open | 1 | 190.097 | 190.097 | 190.097 | 190.097 | 190.097 | spikes\spike2-results\raw\render-r5-run1-20261101-205527.log:116-116(n=1) |
| render-r5-run1-20261101-205527 | spike2 | S10 | check | 1 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | spikes\spike2-results\raw\render-r5-run1-20261101-205527.log:202-202(n=1) |
| render-r5-run1-20261101-205527 | spike2 | S10 | present | 8 | 39.231 | 38.548 | 90.267 | 90.267 | 23.541 | spikes\spike2-results\raw\render-r5-run1-20261101-205527.log:179-201(n=8) |
| render-r5-run1-20261101-205527 | spike2 | S10 | render | 9 | 187.309 | 187.309 | 350.265 | 350.265 | 127.155 | spikes\spike2-results\raw\render-r5-run1-20261101-205527.log:176-200(n=9) |
| render-r5-run1-20261101-205527 | spike2 | S9 | encode | 4 | 297.543 | 293.785 | 342.907 | 342.907 | 253.072 | spikes\spike2-results\raw\render-r5-run1-20261101-205527.log:157-169(n=4) |
| render-r5-run1-20261101-205527 | spike2 | S9 | render | 4 | 138.887 | 112.805 | 1091.097 | 1091.097 | 95.704 | spikes\spike2-results\raw\render-r5-run1-20261101-205527.log:127-168(n=4) |
| render-r5-run1-20261101-205527 | spike2 | S9 | total | 4 | 495.818 | 456.159 | 625.238 | 625.238 | 409.723 | spikes\spike2-results\raw\render-r5-run1-20261101-205527.log:159-171(n=4) |
| render-r5-run1-20261101-205527 | spike2 | S9 | write | 4 | 62.686 | 60.947 | 76.725 | 76.725 | 42.054 | spikes\spike2-results\raw\render-r5-run1-20261101-205527.log:158-170(n=4) |
| render-r5-run2-20261101-205433 | spike2 | S1 | open | 1 | 476.253 | 476.253 | 476.253 | 476.253 | 476.253 | spikes\spike2-results\raw\render-r5-run2-20261101-205433.log:130-130(n=1) |
| render-r5-run2-20261101-205433 | spike2 | S10 | check | 1 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | spikes\spike2-results\raw\render-r5-run2-20261101-205433.log:221-221(n=1) |
| render-r5-run2-20261101-205433 | spike2 | S10 | present | 8 | 60.579 | 56.799 | 93.823 | 93.823 | 27.889 | spikes\spike2-results\raw\render-r5-run2-20261101-205433.log:204-220(n=8) |
| render-r5-run2-20261101-205433 | spike2 | S10 | render | 9 | 382.508 | 382.508 | 792.168 | 792.168 | 262.192 | spikes\spike2-results\raw\render-r5-run2-20261101-205433.log:201-219(n=9) |
| render-r5-run2-20261101-205433 | spike2 | S9 | encode | 4 | 780.750 | 675.462 | 1163.983 | 1163.983 | 469.076 | spikes\spike2-results\raw\render-r5-run2-20261101-205433.log:165-190(n=4) |
| render-r5-run2-20261101-205433 | spike2 | S9 | render | 4 | 686.020 | 602.437 | 838.072 | 838.072 | 409.151 | spikes\spike2-results\raw\render-r5-run2-20261101-205433.log:139-189(n=4) |
| render-r5-run2-20261101-205433 | spike2 | S9 | total | 4 | 1450.804 | 1165.210 | 1927.570 | 1927.570 | 1011.246 | spikes\spike2-results\raw\render-r5-run2-20261101-205433.log:167-192(n=4) |
| render-r5-run2-20261101-205433 | spike2 | S9 | write | 4 | 134.139 | 133.018 | 161.150 | 161.150 | 80.758 | spikes\spike2-results\raw\render-r5-run2-20261101-205433.log:166-191(n=4) |
| render-r5-run2-20261101-205600 | spike2 | S1 | open | 1 | 473.016 | 473.016 | 473.016 | 473.016 | 473.016 | spikes\spike2-results\raw\render-r5-run2-20261101-205600.log:122-122(n=1) |
| render-r5-run2-20261101-205600 | spike2 | S10 | check | 1 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | spikes\spike2-results\raw\render-r5-run2-20261101-205600.log:203-203(n=1) |
| render-r5-run2-20261101-205600 | spike2 | S10 | present | 8 | 43.958 | 43.744 | 61.354 | 61.354 | 23.718 | spikes\spike2-results\raw\render-r5-run2-20261101-205600.log:180-202(n=8) |
| render-r5-run2-20261101-205600 | spike2 | S10 | render | 9 | 177.783 | 177.783 | 341.642 | 341.642 | 105.974 | spikes\spike2-results\raw\render-r5-run2-20261101-205600.log:177-201(n=9) |
| render-r5-run2-20261101-205600 | spike2 | S9 | encode | 4 | 287.228 | 285.528 | 357.469 | 357.469 | 276.018 | spikes\spike2-results\raw\render-r5-run2-20261101-205600.log:158-170(n=4) |
| render-r5-run2-20261101-205600 | spike2 | S9 | render | 4 | 295.124 | 239.368 | 1000.008 | 1000.008 | 235.974 | spikes\spike2-results\raw\render-r5-run2-20261101-205600.log:134-169(n=4) |
| render-r5-run2-20261101-205600 | spike2 | S9 | total | 4 | 658.705 | 655.493 | 762.533 | 762.533 | 584.027 | spikes\spike2-results\raw\render-r5-run2-20261101-205600.log:160-172(n=4) |
| render-r5-run2-20261101-205600 | spike2 | S9 | write | 4 | 73.778 | 59.125 | 140.107 | 140.107 | 54.185 | spikes\spike2-results\raw\render-r5-run2-20261101-205600.log:159-171(n=4) |

## 5. 内存（PSS，不进 ms 表）

> 单位 KB；每格为 `min / median / max`，n 为该组 pss 行数；`-` 表示该组没有对应分项。

**跨 run**

（没有 pss 行。）

**按 run**

（没有 pss 行。）

## 6. 缺 PBDONE 的 scenario

> 同一 run_id 内没有对应 `PBDONE|scenario=<scenario>`（`scenario=ALL` 视为覆盖该 run 全部 scenario）。

（所有 scenario 都有对应 PBDONE。）

## 7. LOW_CONFIDENCE 组

> 组 (run_id, app, scenario, phase) 的有效样本数 < --min-samples 时，该组所有行 confidence=LOW_CONFIDENCE 且 flags 含 `n<5`。

| run_id | app | scenario | phase | n | error 行 | warmup 行 | 原因 |
|---|---|---|---|---|---|---|---|
| render-r5-run1-20261101-205253 | spike2 | PBTIME | first_frame | 0 | 0 | 1 | n<5 |
| render-r5-run1-20261101-205253 | spike2 | S1 | open | 1 | 0 | 1 | n<5 |
| render-r5-run1-20261101-205253 | spike2 | S10 | check | 1 | 0 | 1 | n<5 |
| render-r5-run1-20261101-205253 | spike2 | S10 | first_frame | 0 | 0 | 1 | n<5 |
| render-r5-run1-20261101-205253 | spike2 | S7 | pss | 0 | 0 | 1 | n<5 |
| render-r5-run1-20261101-205253 | spike2 | S9 | encode | 4 | 0 | 1 | n<5 |
| render-r5-run1-20261101-205253 | spike2 | S9 | render | 4 | 0 | 1 | n<5 |
| render-r5-run1-20261101-205253 | spike2 | S9 | total | 4 | 0 | 1 | n<5 |
| render-r5-run1-20261101-205253 | spike2 | S9 | write | 4 | 0 | 1 | n<5 |
| render-r5-run1-20261101-205253 | spike2 | cold_start | am_start | 0 | 0 | 1 | n<5 |
| render-r5-run1-20261101-205342 | spike2 | PBTIME | first_frame | 0 | 0 | 1 | n<5 |
| render-r5-run1-20261101-205342 | spike2 | S1 | open | 1 | 0 | 1 | n<5 |
| render-r5-run1-20261101-205342 | spike2 | S10 | check | 1 | 0 | 1 | n<5 |
| render-r5-run1-20261101-205342 | spike2 | S10 | first_frame | 0 | 0 | 1 | n<5 |
| render-r5-run1-20261101-205342 | spike2 | S7 | pss | 0 | 0 | 1 | n<5 |
| render-r5-run1-20261101-205342 | spike2 | S9 | encode | 4 | 0 | 1 | n<5 |
| render-r5-run1-20261101-205342 | spike2 | S9 | render | 4 | 0 | 1 | n<5 |
| render-r5-run1-20261101-205342 | spike2 | S9 | total | 4 | 0 | 1 | n<5 |
| render-r5-run1-20261101-205342 | spike2 | S9 | write | 4 | 0 | 1 | n<5 |
| render-r5-run1-20261101-205342 | spike2 | cold_start | am_start | 0 | 0 | 1 | n<5 |
| render-r5-run1-20261101-205527 | spike2 | PBTIME | first_frame | 0 | 0 | 1 | n<5 |
| render-r5-run1-20261101-205527 | spike2 | S1 | open | 1 | 0 | 1 | n<5 |
| render-r5-run1-20261101-205527 | spike2 | S10 | check | 1 | 0 | 1 | n<5 |
| render-r5-run1-20261101-205527 | spike2 | S10 | first_frame | 0 | 0 | 1 | n<5 |
| render-r5-run1-20261101-205527 | spike2 | S7 | pss | 0 | 0 | 1 | n<5 |
| render-r5-run1-20261101-205527 | spike2 | S9 | encode | 4 | 0 | 1 | n<5 |
| render-r5-run1-20261101-205527 | spike2 | S9 | render | 4 | 0 | 1 | n<5 |
| render-r5-run1-20261101-205527 | spike2 | S9 | total | 4 | 0 | 1 | n<5 |
| render-r5-run1-20261101-205527 | spike2 | S9 | write | 4 | 0 | 1 | n<5 |
| render-r5-run1-20261101-205527 | spike2 | cold_start | am_start | 0 | 0 | 1 | n<5 |
| render-r5-run2-20261101-205433 | spike2 | PBTIME | first_frame | 0 | 0 | 1 | n<5 |
| render-r5-run2-20261101-205433 | spike2 | S1 | open | 1 | 0 | 1 | n<5 |
| render-r5-run2-20261101-205433 | spike2 | S10 | check | 1 | 0 | 1 | n<5 |
| render-r5-run2-20261101-205433 | spike2 | S10 | first_frame | 0 | 0 | 1 | n<5 |
| render-r5-run2-20261101-205433 | spike2 | S7 | pss | 0 | 0 | 1 | n<5 |
| render-r5-run2-20261101-205433 | spike2 | S9 | encode | 4 | 0 | 1 | n<5 |
| render-r5-run2-20261101-205433 | spike2 | S9 | render | 4 | 0 | 1 | n<5 |
| render-r5-run2-20261101-205433 | spike2 | S9 | total | 4 | 0 | 1 | n<5 |
| render-r5-run2-20261101-205433 | spike2 | S9 | write | 4 | 0 | 1 | n<5 |
| render-r5-run2-20261101-205433 | spike2 | cold_start | am_start | 0 | 0 | 1 | n<5 |
| render-r5-run2-20261101-205600 | spike2 | PBTIME | first_frame | 0 | 0 | 1 | n<5 |
| render-r5-run2-20261101-205600 | spike2 | S1 | open | 1 | 0 | 1 | n<5 |
| render-r5-run2-20261101-205600 | spike2 | S10 | check | 1 | 0 | 1 | n<5 |
| render-r5-run2-20261101-205600 | spike2 | S10 | first_frame | 0 | 0 | 1 | n<5 |
| render-r5-run2-20261101-205600 | spike2 | S7 | pss | 0 | 0 | 1 | n<5 |
| render-r5-run2-20261101-205600 | spike2 | S9 | encode | 4 | 0 | 1 | n<5 |
| render-r5-run2-20261101-205600 | spike2 | S9 | render | 4 | 0 | 1 | n<5 |
| render-r5-run2-20261101-205600 | spike2 | S9 | total | 4 | 0 | 1 | n<5 |
| render-r5-run2-20261101-205600 | spike2 | S9 | write | 4 | 0 | 1 | n<5 |
| render-r5-run2-20261101-205600 | spike2 | cold_start | am_start | 0 | 0 | 1 | n<5 |

带 `error` flag 的行（单行级 LOW_CONFIDENCE，不进 ms 统计）：

（没有 error 行。）

## 8. 解析失败行

- 合计：0 行（`--strict` 时非 0 退出）。

（没有解析失败行。）
