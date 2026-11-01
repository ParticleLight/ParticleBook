# Spike2 基准脚本（scripts/android/spike2）

> 这层脚本负责：装 APK → 冷启 → 等场景跑完 → 抓 logcat → 解析 CSV → 汇总。
> 产物三件套在 spikes/spike2-results/{raw,csv,summary}：raw 是原始 logcat（验收要求必留），csv/summary 全部可再从 raw 重算。

## 0. 现状（2026-11-01 21:20：正式集已完成并冻结）

- 脚本 + 单测（24 个）已完成；冒烟已跑通「装 → 冷启 → 抓 logcat → parse → summarize」全链路（20:10，作废，见 data.md §4.0）。
- P0 基线改为 **CDP 打开 PDF**（p0-cdp.py，真实 React onClick；adb tap 易被长按菜单挡），CDP 失败自动回退 tap。
- **采集已加固**（20:45，针对 render-r1 污染事故）：每轮先 `clear_logcat`（三缓冲清空 + 读回 main=0 行，失败重试 3 次）
  再以 `logcat -v threadtime -T <设备 epoch>.000` 起流（只收本轮之后的行），收尾用 `check_raw.py`
  跑三道断言（时间窗 / PID 唯一 / 结构行），任一失败把 raw 改名 `.log.INVALID`（parse 不匹配）+ rc=7。
- **最新冻结 APK（Lead 20:26 FREEZE GO）**：spikes/spike2-pdf/app/build/outputs/apk/debug/app-debug.apk，
  md5 `8ea3edc683ebf0b5be2f32f32dc57bb9` / 7,287,902B；场景语义同 19:43，只把 S8 判据改成 silent-fail。
- **正式集已于 20:41–21:15 跑完 8 个批次**（render-r1/r2/r3/r5 + tiles-t1t6/t2/t5/t34），逐批三道断言 PASS、逐批 parse+summarize 落盘；数据表与缺口见 docs/android-port/spike2-data.md §4–§5。测量结束后不再有新的 run。
- 口径与数据表在 docs/android-port/spike2-data.md（本 README 只讲怎么跑）。

## 1. 一条命令跑正式测量

```bash
# 全页直渲染 app（S1-S10 全场景，5 轮重装+冷启）
bash scripts/android/spike2/run-render.sh --reps 5

# 分块 app（T1-T6；冻结 APK 只到 T6，不含 T7 架构实验）
bash scripts/android/spike2/run-tiles.sh --reps 3 --scenarios T1,T2 \
  --apk spikes/spike2-tiles/app-debug-frozen-193349.apk
# 其它场景各一次冷启即可（app 内部已保证 ≥5 rep）；T1/T6 建议 3 次重装冷启

# P0 基线（旧链路 render + PNG 编码 + 落盘）
bash scripts/android/spike2/run-baseline-p0.sh --reps 5
```

每轮自动做：`install -r -t` → 写 RUNENV 头 → 冷启（`am start -W`）→ 等 PBDONE → 写 RUNMETA → 可选截图 → manifest 行。
全部跑完自动 `parse.py` → `csv/<tag>-merged.csv`，再 `summarize.py` → `summary/<tag>.md` + `<tag>.summary.json`。

常用选项（run-app.sh --help）：`--reps N`、`--scenarios S1,S2`、`--apk PATH`、`--timeout SEC`、`--tag NAME`、
`--drop-mode first-per-phase|none`、`--drop-first N`、`--screenshot`、`--no-parse`。

## 2. 从 raw 一键重算（验收口径）

```bash
python scripts/android/spike2/parse.py --out spikes/spike2-results/csv --name render-merged \
  --app spike2 --drop-mode first-per-phase spikes/spike2-results/raw/render-run*.log

python scripts/android/spike2/summarize.py \
  --csv spikes/spike2-results/csv/render-merged.csv \
  --out spikes/spike2-results/summary --name render
```

- `--drop-mode first-per-phase`：每个 (run_id, scenario, phase) 组丢第一条**未标冷**样本（warmup）。
  带 `cold:1` / `temp:cold` 的样本不丢——冷样本本身就是 S2/S6/S10 的被测对象。（两个被测 app 都不打 warmup 字样，所以正式测量必须显式指定。）
- P0 基线用 `--drop-mode none`：P0 每次冷启只打开 PDF 一次，首条 pdf_page 就是被测样本。

## 3. 脚本职责

| 脚本 | 职责 |
|---|---|
| env.sh | SDK/adb/gradle/工作区路径、adb_()、wait_device() 唯一来源 |
| lock.sh | 互斥锁（build / emu），带 LOCK_MAX_WAIT、LOCK_HOLDER、stale 自愈（owner pid 死或锁龄 > TTL 自动回收） |
| runlib.sh | 共用函数：流式 logcat、wait_log/wait_pbdone/wait_quiet、RUNENV、install、manifest |
| run-app.sh | 通用 N 轮跑法（每轮单独持 emu 锁），跑完自动 parse + summarize |
| run-render.sh / run-tiles.sh / run-baseline-p0.sh | 三个入口，只设默认值 |
| _one-app.sh | 单轮 spike2 app：装 → 冷启 → 等 PBDONE → 截图 → 收尾 |
| _one-p0.sh | 单轮 P0：装 → 冷启 → 等书架/封面 → CDP 点击（回退 tap）→ 等 pdf_open → 滚到第 4 页 → 收尾 |
| p0-cdp.py | P0 WebView CDP 只读驱动（pdf-verify 提供）：list/eval/open/pages/scroll；open 返回 CLICKED/NO_CARD |
| parse.py | logcat → CSV（16 列）/ stats.json / md；支持 PBPERF/PBDONE/PBTIME/PBPdf/RUNENV/AMSTART |
| summarize.py | CSV → summary.md/json：ms 统计、**§2b app 侧聚合行（*_stat）**、cold/warm 分组、annot A/B、漂移哨兵、真机推算 ×1.8 |
| build.sh | gradle 包装（build 锁 + offline），所有构建必须走它 |
| tests/ | parse.py 单测（24 用例）+ fixtures |

## 4. raw 日志契约

脚本追加的结构行与 app 的 PBPERF 合约行混在同一个 raw 文件里（设备时区，UTC）：

```text
RUNENV|app=spike2|run_id=render-run1-20261101-200000|apk=...|apk_md5=...|apk_bytes=...|scenario=all|api=35|model=...|abi=x86_64|build=...|ts=2026-11-01T20:00:00+08:00
AMSTART|rep=1|TotalTime=5986|WaitTime=5990|status=ok|app=spike2
RUNMETA|phase=alive|alive=1|rep=1
TAP|rep=1|x=660|y=700|ts=...                       （仅 P0，tap 回退时）
CDP|phase=forward|socket=webview_devtools_remote_1234|port=9333   （仅 P0）
CLICK|mode=cdp|book=2|out=CLICKED_2                （仅 P0）
SCROLL|phase=page3|ok=1|out=...                    （仅 P0）
PBPERF|scenario=S2|phase=render|page=0|w=1188|h=1682|ms=183.879|extra=path:argb8888,cold:0,annot:1
PBDONE|scenario=ALL|runs=10|extra=ok
PBTIME|app=spike2|phase=first_frame|ms=1785.455
11-01 11:35:11.765  8157  8183 I PBPdf   : pdf_open pages=4 docBytes=152591 took=500.1ms   （仅 P0）
```

- 采集方式（20:45 起）：`clear_logcat` 清三缓冲 → 取设备 epoch → `start_logcat_stream` 用
  `"$ADB" -s "$SERIAL" logcat -v threadtime -T <epoch>.000 >> raw/<run>.log.rt &` 起流 → 结束 kill + 1s 后 SIGKILL 兜底。
  - 必须直接起 `$ADB`，不要经 `adb_` 函数：经函数会多一层 bash 子 shell，kill 只杀子 shell，adb.exe 变孤儿继续写 raw（20:10 踩过）。
  - `-T` 是 20:42 事故后加的：裸 logcat 会把设备 200–2000 行 buffer 全量重放，把前几轮/别的 app 的 PBPERF 当成本轮数据
    （render-r1 里 101 条 PBPERF 只有 48 条属于本轮 pid，53 条来自 tiles/s8fix 旧 run）。
  - `clear_logcat` 在共享设备上会清掉别人的 buffer ⇒ **只允许在持有 emu 锁 + 团队约定同一时刻只有一个正式 run 时使用**。
- 收尾三道断言（`check_raw.py`，Lead 20:31）：时间窗（所有被测行 ts ≥ 本 rep 设备起始 -1s）、PID 唯一、AMSTART+RUNMETA 结构行；
  失败 ⇒ raw 改名 `<raw>.log.INVALID`（parse 的 glob 不匹配）+ rc=7，manifest 里能看到 INVALID 文件名。
  断言结果同时以 `RUNMETA|phase=assert|CHECK|...` 写进 struct / 合并后的 raw，便于 verify 复核。
- 失败轮也会留下 raw（至少 RUNENV/AMSTART/clock），manifest 记录 rc；不参与统计，但可追溯。
- 收尾自检：`_one-app.sh` / `_one-p0.sh` 的 finish 会检查 `AMSTART|` / `RUNMETA|` 是否存在，缺失打 WARN；raw 里还有
  `RUNMETA|phase=clock|dev_epoch=..|dev_year=..` 与 `RUNMETA|phase=logcatclean|ok=1` 两条采集证据行。

## 5. CSV 字段（parse.py 输出）

```text
run_id,app,scenario,phase,page,w,h,ms,extra,ts,record,src_file,src_line,warmup,confidence,flags
```

- `record`：PBPERF / PBDONE / PBTIME / PBPDF / AMSTART / RUNENV；P0 的 `pdf_page` 一行拆成 render / encode 两行。
- `extra` 里的关键键：`cold:1|0`、`annot:1|0`、`path:`、`dpi:`、`bytes:`、`tiles:`、`zoom:`、`hit:`、`pss_kb:`。
- `src_file`/`src_line`：指回 raw 文件与行号（summary 的「来源」列即由此生成）。
- `warmup=true` 的行保留在 CSV，但不进统计；`confidence=LOW_CONFIDENCE` 表示组样本 <5。
- **`*_stat` 聚合行**（T2 `tile_stat` / T3 `frame_stat` / T4 `gesture_stat` 等）：一行 = app 内部 n 次测量的块统计，
  `ms` 只是 avg，真正的 n/p50/p95/max/over16_ratio/fps_avg 在 extra 里。summarize.py 把它们单独汇总到
  **§2b app 侧聚合行**（块数 / 总样本 / 块 p50 中位 / 块 p95 最大 / fps 中位），**不混进逐样本 §2/§3**。

## 6. 锁与设备纪律（团队约定）

- 一切 adb 写操作（install / am start / logcat / input / screencap）必须在 `bash scripts/android/spike2/lock.sh emu <cmd...>` 内；gradle 必须走 `build.sh`。
- 单次持锁 ≤20 分钟；锁 TTL 25 分钟，超时会被等待者判陈旧回收，长测量请分批。
- 共享设备禁令：`am kill-all`（杀别人 app）、`adb pull /sdcard/...`（Git Bash 改写路径）；截图用 `adb exec-out screencap -p > file`。
  `logcat -c` 只允许在**持有 emu 锁、且团队约定同一时刻只有一个正式 run** 时由 run 脚本内部调用（clear_logcat），不要在锁外用。
- 抢不到锁：等或做离线工作，不要手动 rm 别人的锁（stale 由 lock.sh 自愈）。

## 7. 测量口径

- 每场景 ≥5 有效样本、≥3 次「重装 + 冷启」重复；环境头（RUNENV）每轮必记。
- warmup：`--drop-mode first-per-phase`；带 `cold:1` 的冷样本不丢。
- 漂移哨兵：summary 第 5 节自动选 S2:render page0（可用 `--ref` 指定）比较最早/最晚 run 的 median，差 >20% 标 DRIFT_WARN。
- 真机推算：summary 第 2/3/4 节给出 p50 × 1.8，标 [推测]（PDFium 纯 CPU 光栅，真机/模拟器 ≈1.4–2.5）。
- 跨 app / 跨路径对比只允许同 cold/warm 状态（summary 第 3 节）。
- tiles 专有：`*_stat` 行走 §2b；T1 首屏两个口径走 §2c（first_screen 不含 open / read_path_ms 含 open）；PSS 与 cache 行走 §2d；
  **T5 必须单独冷启**才是诚实增量（进程复用会因 native 高水位偏乐观：+26.5MB vs +7.0MB），变体「T5 放批次最后」单独成批并标口径；
  `--tag` 只进文件名，app 不读（批次区分靠文件名）；
  **fps 不可外推真机**（swiftshader「什么都不画」也只有 26.8fps，是模拟器下限）；
  **跨 block 绝对时间不可比**（同页 205ms vs 1466ms，7×），只有同 block 背靠背相对比较有效；
  T5 的 PSS 必须同进程生命周期内取前后值（T5 前不要 force-stop）。

## 8. P0 基线特殊说明

- `app.particlebook` 的 assets 里只有 seed-01.epub 与 seed-03.pdf，**没有 realbook.pdf**（fixtures.json 打包在 APK 内，不改 APK 无法加书）→ P0 基线只覆盖 seed-03.pdf；realbook 的同批次对照走 spike2 的 S9。
- 打开方式（默认 CDP）：等 `INVOKE db:getBooks` + 2 次 `INVOKE book:cover` → 取 `webview_devtools_remote_*` socket →
  `adb forward tcp:9333 localabstract:<sock>` → `python p0-cdp.py 9333 open 2`（等 `CLICKED 2`）→ `pdf_open` →
  `p0-cdp.py 9333 scroll 0.95` 触发第 4 页。CDP 失败自动回退 `adb input tap`（默认 660,700，`PB_P0_MODE=tap` 可强制）。
- 成功门槛：40s 内出现 PBPdf 的 `pdf_open`；否则存 `raw/<run>.tapfail.png` 并 rc=2。
- 备选：pdf-verify 用 CDP `[data-book-id="2"].click()` 更稳（前缀 baseline-verify-*，两套 raw 不合并）。

## 9. 故障排查

| 症状 | 原因 | 处理 |
|---|---|---|
| `[lock] busy` 一直等 | 别人在跑（或异常退出留 stale） | 看 owner 行；stale 会被自动回收；不要手动 rm |
| `device offline` / `daemon not running` | adb server 被杀、模拟器掉线 | `wait_device` 会 start-server 重试；仍失败找 Lead 重启模拟器 |
| `ANR in app.particlebook` | WebView 冷启期主线程卡顿 + 并发抢焦点 | 重试；或改用 CDP 打开；确保独占锁 |
| 截图 21KB 全空白 | 截太早（WebView 还没画） | 等 `book:cover` 后再截图 |
| logcat 抓到 0 行 | 别人 `logcat -c` 清过 | 已改流式；确认脚本版本 |
| CSV 解析失败 | 行结构非法（缺字段/多竖线） | `parse.py --strict` 看 ParseFailure 行号 |
| raw 里没有 AMSTART/RUNMETA | 孤儿 adb logcat 写同一文件、或与别人并发 | 已修 start/stop_logcat_stream；确认独占锁后重跑，finish 会打 WARN |
| P0 点不开 PDF（`NO_CARD` / 菜单弹出） | tap 被 WebView 判长按 | 默认走 CDP；确认 socket 已 forward、`PB_P0_MODE=cdp` |

## 10. 自测

```bash
python -m unittest discover -s scripts/android/spike2/tests -v
bash -n scripts/android/spike2/*.sh
```
