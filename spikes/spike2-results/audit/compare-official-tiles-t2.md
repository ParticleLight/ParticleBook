# 官方 summary 复现审计 · tiles-t2

原始 PBPERF（含 check，不含 pss/error）：104；按 drop 规则剔除：11；保留：93

| scenario/phase | summary n | summary p50 | 复算(drop) n | 复算(drop) p50 | dP50 | 复算(不drop) p50 | dP50 vs 不drop | 判定 |
|---|---|---|---|---|---|---|---|---|
| T2/full_curve | 9 | 102.054 | 9 | 102.054 | +0.00% | 123.250 | +20.77% | PASS |
| T2/full_ref | 2 | 922.726 | 2 | 922.726 | +0.00% | 532.211 | -42.32% | PASS |
| T2/tile | 71 | 142.848 | 71 | 142.848 | +0.00% | 143.261 | +0.29% | PASS |
| T2/tile_stat | — | — | 11 | 164.747 | — | 138.402 | — | **缺 summary 组** |

最大 dP50（同口径）= 0.00%。

> dP50=复算(drop)-summary；dP50 vs 不drop 只量化 drop 规则本身影响，不是错误。
