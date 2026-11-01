# 官方 summary 复现审计 · render-r1

原始 PBPERF（含 check，不含 pss/error）：235；按 drop 规则剔除：20；保留：215

| scenario/phase | summary n | summary p50 | 复算(drop) n | 复算(drop) p50 | dP50 | 复算(不drop) p50 | dP50 vs 不drop | 判定 |
|---|---|---|---|---|---|---|---|---|
| S1/open | 5 | 540.686 | 5 | 540.686 | +0.00% | 294.240 | -45.58% | PASS |
| S2/present | 95 | 42.206 | 95 | 42.206 | +0.00% | 42.785 | +1.37% | PASS |
| S2/render | 95 | 102.717 | 95 | 102.717 | +0.00% | 107.328 | +4.49% | PASS |
| S3/render | 20 | 298.816 | 20 | 298.816 | +0.00% | 300.426 | +0.54% | PASS |

最大 dP50（同口径）= 0.00%。

> dP50=复算(drop)-summary；dP50 vs 不drop 只量化 drop 规则本身影响，不是错误。
