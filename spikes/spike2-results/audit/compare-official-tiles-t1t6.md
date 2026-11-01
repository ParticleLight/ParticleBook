# 官方 summary 复现审计 · tiles-t1t6

原始 PBPERF（含 check，不含 pss/error）：95；按 drop 规则剔除：35；保留：60

| scenario/phase | summary n | summary p50 | 复算(drop) n | 复算(drop) p50 | dP50 | 复算(不drop) p50 | dP50 vs 不drop | 判定 |
|---|---|---|---|---|---|---|---|---|
| T1/tile | 40 | 13.111 | 40 | 13.111 | +0.00% | 21.390 | +63.15% | PASS |
| T6/first_screen | 20 | 345.539 | 20 | 345.539 | +0.00% | 372.998 | +7.95% | PASS |

最大 dP50（同口径）= 0.00%。

> dP50=复算(drop)-summary；dP50 vs 不drop 只量化 drop 规则本身影响，不是错误。
