# 官方 summary 复现审计 · render-r2

原始 PBPERF（含 check，不含 pss/error）：155；按 drop 规则剔除：15；保留：140

| scenario/phase | summary n | summary p50 | 复算(drop) n | 复算(drop) p50 | dP50 | 复算(不drop) p50 | dP50 vs 不drop | 判定 |
|---|---|---|---|---|---|---|---|---|
| S1/open | 5 | 343.120 | 5 | 343.120 | +0.00% | 376.551 | +9.74% | PASS |
| S4/total | 120 | 83.707 | 120 | 83.707 | +0.00% | 83.547 | -0.19% | PASS |
| S5/text | 15 | 3.023 | 15 | 3.023 | +0.00% | 4.857 | +60.68% | PASS |

最大 dP50（同口径）= 0.00%。

> dP50=复算(drop)-summary；dP50 vs 不drop 只量化 drop 规则本身影响，不是错误。
