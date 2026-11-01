# 官方 summary 复现审计 · render-r3

原始 PBPERF（含 check，不含 pss/error）：22；按 drop 规则剔除：3；保留：19

| scenario/phase | summary n | summary p50 | 复算(drop) n | 复算(drop) p50 | dP50 | 复算(不drop) p50 | dP50 vs 不drop | 判定 |
|---|---|---|---|---|---|---|---|---|
| S1/open | 1 | 1685.539 | 1 | 1685.539 | +0.00% | 1200.689 | -28.77% | PASS |
| S6/render | 14 | 467.117 | 14 | 467.117 | +0.00% | 417.063 | -10.72% | PASS |
| S8/render_hw | 4 | 8.440 | 4 | 8.440 | +0.00% | 9.531 | +12.93% | PASS |

最大 dP50（同口径）= 0.00%。

> dP50=复算(drop)-summary；dP50 vs 不drop 只量化 drop 规则本身影响，不是错误。
