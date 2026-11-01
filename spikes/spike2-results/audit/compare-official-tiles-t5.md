# 官方 summary 复现审计 · tiles-t5

原始 PBPERF（含 check，不含 pss/error）：8；按 drop 规则剔除：8；保留：0

| scenario/phase | summary n | summary p50 | 复算(drop) n | 复算(drop) p50 | dP50 | 复算(不drop) p50 | dP50 vs 不drop | 判定 |
|---|---|---|---|---|---|---|---|---|

最大 dP50（同口径）= 0.00%。

> dP50=复算(drop)-summary；dP50 vs 不drop 只量化 drop 规则本身影响，不是错误。
