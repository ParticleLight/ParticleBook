# 独立复验脚本（pdf-verify / task-6）

本目录是 task-6 的独立复算与污染检测工具，**不 import / 不复用 pdf-bench 的 parse.py**。
正式集验收时，每份 raw 都应先过 contam.py，再用 recompute.py 复算。

- recompute.py：独立 parser。输入 raw logcat（含 PBPERF/PBDONE/PBTIME/PBPdf），输出每组 n / min / P50 / P95(nearest-rank + 线性) / max / mean，冷热分段（每文件每组首样本=cold）、bootstrap 95% CI（n>=5，2000 次，seed=12345）；--p0 模式解析 P0 的 pdf_open/pdf_page。
  - 用法：python spikes/spike2-results/audit/recompute.py spikes/spike2-results/raw/RawName.log
  - --json 输出结构化结果；--p0 解析 P0 基线。
- contam.py：污染检测。判据：foreign_proc / foreign_start / anr_or_fatal / second_runmeta / unexpected_pid -> INVALID；multi_pid 仅 WARN。
  - 用法：python spikes/spike2-results/audit/contam.py --pkg app.particlebook --allow-pids 1234,5678 RawName.log
- compare.py：与 bench parse.py 做同输入对比（subprocess 只读调用，不算复用）。输出 md+csv，判定 PASS<=5% / WARN<=10% / FAIL>10%。
  - 用法：在工作区根运行 python spikes/spike2-results/audit/compare.py RawName.log

已知边界：P95 用 nearest-rank 时 n<20 几乎等于 max（n=5 时就是 max），读表必须同时看 n；pss 行 ms 非计时样本，不参与 ms 统计。
