# spikes/spike2-results/csv — CSV 来源说明

本目录同时存在两类 CSV，**追溯性质不同，不要混用**：

## 1. 正式链路 CSV（parse.py 产物，可用于 §6 追溯）

- 命名：`<tag>-merged.csv`，配套 `<tag>-merged.md`（parse 的 md）与 `<tag>-merged.stats.json`。
- 表头（16 列，固定）：
  `run_id,app,scenario,phase,page,w,h,ms,extra,ts,record,src_file,src_line,warmup,confidence,flags`
- 每行可由 raw 重算，`src_file`/`src_line` 指回 raw 行号；生成命令见 docs/android-port/spike2-data.md §3.3。
- 例：`render-smoke-merged.csv`（20:10 冒烟，已作废但保留了链路证据）、`tiles-devcheck.csv`（tiles dev 日志的链路验证）。

## 2. dev 手工导出（**不是** parse.py 产物，不进正式追溯）

- `pbperf.csv` / `pbdone.csv` / `pbtime.csv`：pdf-tiles 在自己的 dev 冒烟期间手工导出的原始记录视图
  （2026-11-01 20:16 落盘）。
- 表头不同：`run,scenario,phase,page,w,h,ms,extra`（**没有 app 列、没有 src_file/src_line/warmup/confidence**），
  无法回溯到具体 raw 行号，也不能被 summarize.py 直接使用（缺 app 列）。
- 用途仅限：pdf-tiles 自己核对 dev 冒烟；正式数据一律以 `<tag>-merged.csv` 为准。
- 如果 pdf-tiles 需要保留，建议改名加前缀 `devmanual-`，避免与正式 CSV 混淆。
