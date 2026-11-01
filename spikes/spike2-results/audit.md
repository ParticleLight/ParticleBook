# Spike 2 · 独立复验与协议审计（task-6 / pdf-verify）

> **状态：进行中（2026-11-01 20:53 更新）**。审计清单与复算口径已定稿；独立 P0 基线 rep1 已落盘（§3.1）；与 bench parse.py 的同输入差异表已算完（§5.1，4 份 raw 全 PASS 0.00%）；S9 编码口径差异已单列（§2.2）；污染检测工具与结论见 §5.3；已知口径/追溯问题见 §5.4；截图抽查已用 rep1 的 03-scroll1/2 完成 2 张（§7）。**正式集进行中**：APK md5 8ea3edc683ebf0b5be2f32f32dc57bb9（新构建，S8 修复待验证）。render-r1（S1/S2/S3）5 reps 已完成且逐份过结构性检查（单 pid、RUNENV、PBDONE 齐全）→ 独立复算见 §5.2b；render-r2（S4/S5）20:49 起跑中。**待补**：S6–S10 正式批次、P0 正式集（同批窗口 + 尺寸分组）、与 summary 的 <5% 对照、场景复跑、S8 新 APK 判决、最终 PASS/FAIL。
>
> **独立性声明**：本审计不 import / 复用 pdf-bench 的 parse 脚本、CSV 或 summary 数字；所有统计从原始 logcat 文本由本人独立实现的 parser 重算（附录 A）。P0 基线由本人独立驱动重跑（§3，驱动方式如实记录）。凡是引用他人数据的行，都标明「引用，未独立复算」。
>
> **写作用域**：本文件与 spikes/spike2-results/raw/baseline-verify-* 由 pdf-verify 写；其余 raw/csv/summary 只读。

- 审计人：pdf-verify（task-6）
- 审计对象：task-3 数据集（spikes/spike2-results/{raw,csv,summary}）、docs/android-port/spike2-data.md
- 协议基线：docs/android-port/spike2-protocol.md v1 + §4C（2026-11-01 更新，含 S10/T7）
- 环境：emulator-5554 x86_64/API35/1080x2400/dpi420，swiftshader，WHPX 加速；宿主 i7-13650HX/15.6GB
- 时间：设备 TZ=GMT（logcat UTC），宿主 UTC+08:00；差 8h，所有对齐按此换算（§1 A4）

---

## 1. 审计清单（逐项 PASS / FAIL / WARN / N/A + 证据）

标记：`[x]` 已判、`[~]` 部分/待正式集复核、`[ ]` 未判（依赖正式集或设备窗口）。

### A. 数据来源与批次
- [x] **A1 可追溯性：部分 FAIL。**
  - 已归档 raw 的数字可追到行号（例：render-run1-20261101-193254.log:6/8/9）。
  - 但 data.md §4.0 冒烟表的来源是 `/f/tmp/smoke/smoke.log:182/208/244…`，该文件**未归档进 raw/** → 该表数字当前不可追溯，正式报告不得直接引用。
  - csv/pbperf.csv、pbdone.csv、pbtime.csv 是 pdf-tiles 的手工 dev 导出（bench 已确认，见 csv/README.md），表头 `run,scenario,phase,page,w,h,ms,extra` 无 app/src_file/src_line 列 → **不是** parse.py 产物；正式追溯只认 `<tag>-merged.csv`（16 列含 src_file/src_line）。此项在正式集前必须成立。
- [x] **A2 运行头：FAIL（tiles 族）。** 有 `RUNENV`（含 apk_md5/api/model/abi）：render-run1-20261101-193254.log（md5 2dadaf173e8e79f1a9016ba4922c8964）、render-smoke-run1-20261101-201032.log 与 spike2-smoke-hw-run1-20261101-201115.log（同 md5 b844a4043179）、baseline-p0-run1-20261101-190509.log（md5 8e0e54f1fae2）。**无 RUNENV**：tiles-dev-190131/191330/193142/193349/193520/201201/201451 全部；tiles-devcheck.md 自己写明「输入中没有 RUNENV 行；环境头缺失 → 只能相对比较」。→ 正式 tiles 集必须补 RUNENV。
- [~] **A3 同批次：待正式集。** 当前 render（含 S9）run 与 P0 rep1 不在同一持锁窗口（19:33 vs 19:40），跨批不可直比；正式集要求 S9 与 P0 在同批窗口采集并在 raw 记录持锁 at。
- [x] **A4 时钟差：PASS。** 实测 rep1 pdf_open 设备 11:42:03.663 ↔ 宿主 19:42:03；RUNENV ts 用宿主时间（约定：设备戳=宿主-8h）。

### B. 场景覆盖与口径
- [~] **B1 S1 open：WARN。** spike2 的 S1 `extra=asset:seed-03.pdf,pages:4,pt:594x841,mode:bitmap` **缺 bytes**（协议要求 pages/bytes/pt）；tiles 的 T1 open 有 `bytes:152591`。且 S1 同 phase 混两个资产：seed-03 395.478ms（:6）与 realbook 3493.171ms（:8，pages:60）→ P50 1944.324 无意义，正式集必须按 asset 分组。
- [x] **B2 S2 render_full：PASS。** 1188x1682、`path:argb8888`、render+present 两相各 20 样本（render-run1 与 smoke 均如此，extra 含 cold:0/1）。
- [~] **B3 S3 render_565：WARN（已有正式数据，口径缺 path 标记）。** render-r1 5 reps 共 25 条：`w=1188,h=1682,extra=cfg:rgb565,cold:x,bytes:3996432`（=1188*1682*2，确为 RGB_565 输出）；P50 300.426 / warm 312.795 / P95 520.122（§5.2b）。**协议要求区分 `path:native565` 与 `path:convert565`，实际 extra 只有 `cfg:rgb565`** → 无法从日志判定走的是原生 565 还是 §4C-3 的 BGR888 临时缓冲转换；且 S3 P50 约为 S2 ARGB 的 2.8×（300 vs 107，同尺寸），与「565 需转换开销」方向一致但未证。建议 bench 在 S3 extra 加 `path:` 标记。
- [ ] **B4 S4 turn：未测。** 无 S4 行。
- [ ] **B5 S5 text_layer：未测。** 无 S5 行。
- [ ] **B6 S6 dpi_scale：未测。** 无 S6 行。
- [~] **B7 S7 pss：WARN。** 仅 1 条 `S7|pss`（render-run1:5，pss_kb:22288/native_kb:3886/java_kb:685），**不满足协议「4 个快照」**；且 `ms=0` 违反 3 位小数（见 B13）。
- [x] **B8 S8 bitmap_hw：FAIL（当前仍静默失败，修复未验证）。** 冒烟 render-smoke-201032 的 S8 为 `runs=0` + `extra=error:java.lang.IllegalArgumentException`（协议允许失败上报，但 L3 判定需成功样本）。pdf-render 的 s8fix 运行（s8fix-run1-20261101-201840.log / s8fix2-run1-20261101-202041.log）S8 `render_hw` 5 条，extra 全为 `cfg:hardware,hw:1,white:1.0000,blank:0.0000,crc:8f994c48,changed:0` → **像素未变（全白）**；同时 logcat 有 `E jniPdfium: Locking bitmap failed: No such file or directory`（12:18:58.750）→ 符合 §4C-2「HARDWARE 静默失败」判据（像素未变 + jniPdfium 日志）。修复必须同时证明 `changed>0` 或 `white<1`，否则不能关单。
- [x] **B9 S9 png_legacy：PASS（口径见 §2.2）。** render/encode/write/total 四相各 5 样本，extra `png_bytes:76236,path:baos`，无 WebView 加载。
- [~] **B10 S10 surface_page：WARN。** smoke 有 render 10 / present 9 / first_frame 1 条，`mode:surface,seq,drawW/H,surfaceW/H` 齐全；但 **w=1080 h=2400（与协议 1188x1682 不一致，data.md §5.4 已自述 hardcode）**、缺 `extra=surface:ok` 标记（协议 §4C 要求）、只测首帧无 warm 对照。check 行 `mid_white:1.000,bot_white:1.000`。
- [~] **B11 T1–T6：部分覆盖。** tiles-dev-190131 与 191330 覆盖 T1(13)/T2(24)/T3(6)/T4(10)/T5(1)/T6(6)；T5 只有 1 条 pss 行、T1 first_screen n=1 → 样本不足；T7 见 B12。
- [~] **B12 T7 surface_tile：WARN。** tiles-dev-201201 有 `arch`24/`arch_ratio`2/`arch_stat`6/`mem`1 行；协议 §4C 写的是 render+zoom_rerender+frame_stat，与实际 phase 名不一致（arch* 架构判决实验，data.md §2.9 已声明 T7 不属正式指标）。需在协议/文档统一命名。
- [~] **B13 格式合规：WARN。** PBPERF 前缀、extra 无空格/竖线、计时行 ms 3 位小数：**全部合规（0 违例）**；但 **pss 行 ms 格式混用**：`ms=0`（14 行）与 `ms=0.000`（9 行）并存，且部分 `native_kb:-1`（tiles-dev-190131 的 T5/T6 pss）→ 统计上 pss 不参与 ms 计时，但协议应写明「pss 行 ms 固定 0.000」或允许整数，否则格式校验器无法一键判定。

### C. 样本数与统计
- [~] **C1 每 phase ≥5：冒烟不满足，已标 LOW_CONFIDENCE。** 例：T1 first_screen n=1（1224.852）、S1 open n=2、T2 full n=3、S10 first_frame n=1。正式集必须 ≥5。
- [x] **C2 分位口径：PASS。** data.md §2.4 与 parse.py:742-743 一致（median / nearest-rank）→ 本审计同法复算并另给线性插值敏感性。
- [x] **C3 同窗口混算：已在冒烟数据中出现，正式集必须分组。** tiles-devcheck.csv 把 193349 的 T1 tile 与 193520 的 T2 tile 合并（n=96，2 run，冷热混合）；S1 open 混两个 asset（见 B1）；T2 full 混三个尺寸（1080x1529/2160x3058/3240x4587，见 §5.2）。
- [ ] **C4 多轮聚合方式：待 bench 写明。** 当前是「所有 run 样本合并」；建议正式集先各 run 取中位再跨 run 汇总（或至少同时给两版），并在 summary 标注。

### D. warmup 剔除
- [x] **D1 规则已建、执行待验。** 协议缺口确认：日志 schema 无 warmup 标记，两个 app 都不打（spike2t MainActivity.kt:386 仅注释）；bench 已在 parse.py 加 `--drop-mode choices ["none","first-per-phase"]`（:1449-1453，**default 仍是 none**）并在 data.md §2.2 要求正式 parse 显式传 `first-per-phase`（例外：extra cold:1/temp:cold 不丢）。正式集必须核对命令行确实传了。
- [ ] **D2 双口径复算：待正式集。** 按 first-per-phase 复算一版 + 不剔除版，报 P50/P95 影响。
- [x] **D3 混入风险实测（PASS=风险确认）。** 不剔除时：S9 render all P50 477.882 vs warm 387.674（+23.3%）；S2 render all 105.656 vs warm 91.884（+15.0%）；S9 encode all 1100.934 vs warm 928.496（+18.6%）；tail 被首个 cold 样本主导（S9 P95=max 1295.730 / S2 P95 1142.839 全部来自 cold）。→ warmup 不剔会显著抬高尾部，正式集必须剔。

### E. PBDONE 与异常上报
- [~] **E1 PBDONE 存在但覆盖不齐。** 例：190131/191330 全场景（T1=1/T2=18/T3=5/T4=5/T5=20/T6=5/ALL=39）；193349 仅 T1=1；193520 T2=82/ALL=44；201201 T3=11/ALL=56；201451 仅 ALL=56。**且 201451 raw 只有 37 条 PBPERF、时间窗仅 12:15:01–12:15:22（21s）** → PBDONE runs=56 与 raw 行数不匹配，需解释（buffer 截断？提前退出？）。
- [x] **E2 失败上报：PASS（有 error 样本）。** S8 `runs=0` + `extra=error:java.lang.IllegalArgumentException`；未被静默丢弃。
- [ ] **E3 样本数 < PBDONE 缺口解释：部分有证据（见 E1），正式集逐 run 核。**
- [x] **E4 crash/ANR：如实记录。** S8 fatal IllegalArgumentException（smoke）；P0 app ANR（§3.2 事故记录）；并发污染事故（§3.2/§5.3）。

### F. 环境头
- [x] **F1 环境表：PASS（文档层）。** data.md §1 有设备/宿主/adb/构建/app；但 §4.x 数据表尚未填 raw 来源，正式集需逐表补。
- [x] **F2 swiftshader/fps 声明：PASS。** data.md §1 + §2.9（fps 不可外推真机，空转 ≈26.8fps/33.3ms/帧）。
- [~] **F3 CPU 可信/GPU 不可信：WARN。** data.md 未显式写这一条（只在 §2.9 写 fps），建议补一句，避免报告把软渲染数字当 GPU 结论。

### G. 锁与可复现性
- [~] **G1 持锁窗口：待正式集。** 本审计 rep1 的 emu 锁 19:40:46–19:43:36（owner holder=verify），raw 首/末设备戳可对齐；正式集需逐 run 对照 lock owner。
- [x] **G2 单次持锁 ≤20min：PASS（已执行样本）。** rep1 约 3 分钟；19:30:50–19:32:04 事故窗口也 <20min；19:43 的中断为 harness 超时（已记录）。
- [x] **G3 脚本化：PASS。** run-render/run-tiles/run-baseline-p0 + parse/summarize + p0-cdp.py；参数见 data.md §3.2。

---

## 2. 复算方法（独立 parser 规格）

- 输入：原始 `adb logcat -v threadtime` 文本（非 bench 的 csv/summary）。
- 提取（与 logcat 行首无关，`PBPERF|` 后按 `|` 切分）：`scenario|phase|page|w|h|ms|extra`；`PBDONE|scenario|runs|extra`；`PBTIME|app|phase|ms`；P0 用 tag `PBPdf` 的 `pdf_open`/`pdf_page` 两行。
- 统计：P50=`statistics.median`；P95 同时给 **nearest-rank**（`sorted[ceil(0.95n)-1]`）与线性插值；n≥5 时给 bootstrap 95% CI（2000 次重采样，seed=12345）。
- cold/warm：每 (file, scenario, phase) **按日志出现顺序的第一条为 cold**，其余为 warm（app 的 extra cold:1/cold:0 只作交叉核对，不参与分组）。
- 对比阈值：|复算-bench|/bench ≤5% PASS，5–10% WARN，>10% FAIL。
- 脚本（全文与落盘副本，独立实现，不 import bench）：
  - `F:/tmp/verify/recompute.py`（独立 parser；--json 输出 all/cold/warm 统计）
  - `F:/tmp/verify/contam.py`（污染检测；--pkg --allow-pids）
  - `F:/tmp/verify/compare.py`（只读调用 bench parse.py 做同输入 diff，subprocess，utf-8）
  - 交付前复制到 `spikes/spike2-results/audit-recompute.py` 等（附录 A 待落盘）。

### 2.1 被审计方统计口径（bench parse.py 只读审计，不复用其代码）

- 分位数：`scripts/android/spike2/parse.py:742-743` 用 `_nearest_rank`（`idx=ceil(q*n)-1`，1-based）；P50 用 `statistics.median`（:1135 注释）。→ 与 data.md §2.4 一致。
- warmup：`:714` `if "warmup" in lowered or "warmup" in (seed.phase or "").lower(): row.warmup=True`；`--drop-first N`（:819-822）每组丢最先 N 个有效样本；**默认 0**（run-app.sh:20 `PB_DROP_FIRST=0`）；新增 `--drop-mode first-per-phase`（:1449-1453）。
- 样本不足：`<min-samples` 组打 `LOW_CONFIDENCE` + `n<N`（:855-858），并跟踪缺 PBDONE/warmup/error 行。
- 风险：两个 app 都不打 warmup 字样，且默认 none → **若正式命令漏传 --drop-mode，warmup 会静默混入**（D3 已量化影响）。正式集验收时我会核对 data.md §3.2 命令与实际 `ps` 参数。

### 2.2 S9 编码口径差异（单列；Lead 20:14 要求查清「4.3×」）

数据源：render-run1-20261101-193254.log（S9 5 样本，`path:baos`，png_bytes:76236）。

| phase | cold (n=1) | warm P50 (n=4) | all P50 | warm P95 | 备注 |
|---|---|---|---|---|---|
| render | 1302.377 | 387.674 | 477.882 | 977.415 | cold 是首个样本 |
| encode | 1295.730 | 928.496 | 1100.934 | 1213.832 | |
| write | 288.517 | 145.688 | 159.326 | 174.510 | |
| total | 2886.624 | 1485.845 | 1823.764 | 2252.859 | |

**与 P0（rep1，690x977）对照**：P0 render 290.3/225.5/271.9/52.8（median 248.7，n=4）、encode 252.9/286.9/259.8/309.7（median 273.35，n=4）、bytes 40–64KB。

**关键归一化**：S9 像素 1188×1682 = 1,998,216 px；P0 690×977 = 674,130 px → **面积 2.96×**。
- 原始差：S9 warm encode 928.496 vs P0 273.35 = **3.40×**（不是 4.3×；4.3× 来自用 S9 cold/all 对 P0 warm 或尺寸标错时混算）。
- 归一化：S9 464.7 ms/MP vs P0 405.6 ms/MP = **1.15×**。
- 含 write（同链路完整落盘）：S9 warm 1074.2 vs P0 273.35 = 3.93× 原始；归一化 537.6 vs 405.6 = **1.33×**。

**原因排序**（按证据强度）：
1. **分辨率是主因（约 3×）**：面积 2.96× 已解释原始差的 ~87%；跨尺寸必须先归一化到 ms/MP，否则「S9 比 P0 慢 4 倍」是伪结论。
2. **BAOS 二次拷贝 + 独立 write 阶段**：S9 走 `ByteArrayOutputStream` 后再 write（PNG 76236B），多一次内存拷贝与一个阶段；含 write 后仍只 1.33×/MP，说明这部分约 0.18×。
3. **debug 构建**：两个 app 都是 debug，无 release 对照 → 无法把 1.15–1.33× 归给构建类型；正式报告应注明「debug 绝对慢于 release，实测未覆盖」。
4. **GC/并发负载**：S9 采集窗口（19:33）前后有并发 run（§5.3），且 2MP ARGB 每页 ~8MB 临时内存 → cold 首样本 total 2886.6ms 是 warm 的 1.94×；warm 内 P50/P95 也翻倍（encode P95 1213.832）。只能同批 A/B，不能与 P0（干净窗口）直接比。
5. **页句柄复用**：P0 4 页在同一 doc 句柄下（page3 render 仅 52.8ms 已缓存），S9 每轮 render 同一页 5 次也有缓存（warm 387.674 vs cold 1302.377）→ 两者 warm 状态可比，但 P0 的「页切换」语义与 S9 的「同页重渲」不同，报告需在表注说明。

结论：**S9 encode 与 P0 encode 在像素归一化后同量级（1.15×，完整链路 1.33×）；PNG 落盘路径没有被「消除」，但也没有异常放大。** L3 判决可用 S9 的 ms/MP 折算，不可直接拿 1100ms vs 273ms 比。

### 2.3 分位数与样本量（nearest-rank 的坑）

- n=5 时 `ceil(0.95*5)-1 = 4`（0-based）→ **P95 = max**（5 个样本的第 5 个）。
- n=20 时 idx=18（0-based）→ 第 19 个，≈次大；n=10 时 idx=9 → max。→ **P95 在 n<20 时几乎就是 max**，对单个异常值极不稳定（例：S2 render P95 1142.839、S9 render P95 1302.377 都等于 cold 首样本）。
- 建议：正式集每 phase n≥20，或同时报 P90 / 截尾 P95（去掉最大 5%）；summary 必须标 n，读表人才能判断 P95 的有效位数。
- 本审计所有 P95 均给 n 与 nearest-rank；差异表同法比较。

---

## 3. P0 独立基线（app.particlebook，只读运行）

- **目标**：与 pdf-bench 的 raw/baseline-p0-* 同口径独立跑，验收「模拟器漂移」。
- **口径**：同一 APK（android/app/build/outputs/apk/debug/app-debug.apk，sha256 121b819d…，versionName 2.2.1-p0）、seed-03.pdf（152591B，4 页）、阅读器实际渲染尺寸 **690x977**（不是 1188x1682；由列宽决定）、ARGB_8888 + PNG(100) + `cacheDir` 落盘、无 WebView 复用。
- **驱动方式（如实记录）**：adb `input tap` 在 CPU 饱和的模拟器上会被 WebView 判成长按（弹「打开/分屏阅读/详情/删除」菜单），不可靠；改用 **WebView 远程调试（CDP）** 执行 `document.querySelector('[data-book-id="2"]').click()`（只读驱动，不改 apk/源码/不注入业务逻辑）→ 返回 `CLICKED 2`（真实 React onClick）。成品脚本 `scripts/android/spike2/p0-cdp.py`（子命令 list/eval/open/pages/scroll）已入仓，bench 的 _one-p0.sh 已接。
- **记录**：宿主/设备时间、logcat 原文、截图、CDP 动作、force-stop 时刻、锁 owner。

### 3.1 rep1 结果（有效，已落盘）

raw：`spikes/spike2-results/raw/baseline-verify-p0-rep1-20261101-194046.log`（含 provenance 头；同前缀阶段快照 `-01-open.txt`/`-02-render0.txt`/`-03-scroll1..3.txt` 与截图 `-00-shelf.png`/`-02-page1.png`/`-03-scroll1.png`/`-03-scroll2.png`）。

| 指标（PBPdf） | rep1 | 证据（raw 行/设备时间） |
|---|---|---|
| 冷启 am start TotalTime | 11735 ms | RUNENV/AMSTART 段 |
| BENCH books（书架数据） | 2192 ms | BENCH books 行 |
| 首屏 tti first-paint | 7612 ms | tti 行 |
| pdf_open（engine） | 382.0 ms | :52，设备 11:42:03.663 |
| pdf_open（Bridge INVOKE pdf:open） | 937.92 ms | PBBridge 行 |
| 页数 / docBytes | 4 页 / 152591 B | pdf_open extra |
| page0 | render 290.3 / encode 252.9 / 40062 B | :56 |
| page1 | render 271.9 / encode 259.8 / 64012 B | :60 |
| page2 | render 225.5 / encode 286.9 / 64454 B | :58 |
| page3（滚动触发） | render 52.8 / encode 309.7 / 52114 B | :65（句柄已缓存） |
| 阅读后 PSS TOTAL | 98285 KB | PSS 行 |

复算：render median 248.7（n=4）、encode median 273.35（n=4）、bytes 40–64KB。**注意 page3 的 52.8ms 是已缓存句柄，不能当作「单页冷渲染」**。

### 3.2 事故与方法记录

- **19:30:50–19:32:04 并发污染（已记录）**：本人持 emu 锁期间，19:31:42 bench 产生 tiles-dev-20261101-193142.log（0B）+ meminfo；本人 19:32:02 截图 05-scrolled2.png 显示的是 `app.pb.spike2t` 的「T2 tile_cost」界面而非 P0 app；19:32:02 抓 logcat 时 PBPdf/PBBridge 已被清空 → 该次 P0 基线作废，已重跑（即 v5/rep1）。bench 的 `run-app.sh:86` 确实逐 rep 包 lock.sh emu，但其 19:31:42 的运行未等锁（其 19:32:05 才建锁）→ 协议风险，已报 Lead（19:37 已升级强制纪律 + lock.sh 加死 pid/TTL 自愈）。
- **19:40:46–19:43:36 v5 rep1（有效）**：以 LOCK_HOLDER=verify 持锁；rep1 完整；rep2 被 harness 超时中断（`interrupted after recorded, no durable result`），残留锁 owner pid=637 经核实已死，20:10:38 清理。
- **教训**：per-rep 的 `kill $!` 杀不掉 Windows adb.exe → rep2 日志漏进 rep1 流（contam 检出 multi_pid 8494+8764）；**下次整个 run 单流 + 阶段 dump**。
- **污染判定**：contam.py 对 rep1 判 VALID（仅 multi_pid WARN；无 foreign_proc/foreign_start/anr_or_fatal/second_runmeta/unexpected_pid）→ 采用。

### 3.3 rep2 计划（等 Lead 窗口）

- 单流 logcat（一个 run 一个文件）+ 阶段 dump（open/render0/scroll1..3）+ 开图后立即落 PBPdf/PBBridge 段。
- 加「焦点包名污染检测」：每阶段前后 `dumpsys window | grep mCurrentFocus`，断言 = app.particlebook；否则标 INVALID。
- ≥2 rep、每 rep 打开 4 页；图后等 `pdf_page` 再截图（P0 懒渲染，03 截图曾全白）。
- 落盘 `baseline-verify-p0-rep2-<宿主ts>.log`，完成后文件名+rep 号发 bench 写 data.md §4.3。
- **rep2 追加项（因 §3.4 尺寸分歧）**：开图时用 CDP 记录 `window.devicePixelRatio`、`visualViewport.width/height`、阅读器容器 `getBoundingClientRect()`，并对每页 `dumpsys window` 焦点断言；同时用 `run-as app.particlebook`（debuggable）把 cacheDir 最新 PNG 拉出，做像素非白断言（白页 PNG 只 ~2–3KB，实测 40–64KB 已是非白旁证，但仍以解码像素为准）。

### 3.1b 追加两次独立冷启（2026-11-01 20:38 / 20:42，窗口内）

raw：`raw/baseline-verify-p0-rep2-20261101-203813.log`（690 模式）与 `raw/baseline-verify-p0-rep3-offlineglitch-20261101-204206.log`（1188 模式，设备启动瞬间 adb offline 抖动，数据为单 pid=10326、12:42:57 后采集）。

| 指标 | rep2（20:38，690 模式） | rep3（20:42，1188 模式） |
|---|---|---|
| 冷启 TotalTime | 13014 ms | 10017 ms（offline 抖动后重连） |
| BENCH books count=2 | 1554 ms | — |
| tti first-paint | 10974 ms | — |
| pdf_open | 247.3 ms | 224.6 ms |
| DPR / visualViewport / body | 2.625 / 412.19×915.05 / 412×915 | 2.625 / 412.19×915.05 / 412×915 |
| page0 | 690x977 render 465.2 / encode 298.4 / 40062 B | **1188x1682** render 412.1 / encode 748.1 / 76236 B |
| page1 | 690x977 429.8 / 304.6 / 64012 B | 1188x1682 1616.9 / 472.9 / 125893 B |
| page2 | 690x977 476.2 / 302.1 / 64454 B | 1188x1682 650.2 / 814.7 / 123944 B |
| page3（滚动触发） | 690x977 87.3 / 283.6 / 52114 B | 690x977 50.9 / 122.5 / 52114 B |
| 阅读器容器（CDP pages） | pages:4, sh:2002, ch:915 | pages:4, sh:2002, ch:915 |

关键：**DPR 与 CSS viewport 完全相同（2.625 / 412.19×915.05），但渲染位图尺寸不同** → 尺寸由 WebView 里 P0 的 JS 在调用 `pdf:renderPage` 时传的 width 参数决定（android/app/src/main/java/app/particlebook/android/Bridge.kt:90-94 只透传 JS 参数，默认 800），不是设备 DPI 推导。且**同一 run 内可混尺寸**（rep3 前 3 页 1188、滚动后 page3 回到 690）。

### 3.4 P0 渲染尺寸分歧（690x977 vs 1188x1682，rep2/rep3 已定性）

- 本审计 rep1（有效样本）：4 页全部 `690x977`（render 52.8–290.3ms、encode 252.9–309.7ms）。
- bench 的 `p0-cdpcheck-run1-20261101-202240.log`（被 bench 自己判 INVALID：与 Lead 的 S8 run 并发 + 设备 offline + 缺结构行）里：page0/1/2 = `1188x1682`（render 955.9/271.0/1023.4ms，encode 391.8/529.7/350.1ms），page3 = `690x977`。
- 两者是**同一 APK、同一设备**，尺寸比 1188/690 = 1682/977 = 1.722（均匀缩放）→ 更像「阅读器渲染宽度/DPR 状态不同」而非页面本身不同；也可能是 bench 的 run 在 WebView 尚未完成布局时先渲染、稍后列宽收敛。
- 后果：data.md §5.1 / §4.3 若把两种尺寸混算，render/encode 中位数会失去意义（同为 page0，290.3 vs 955.9ms 差 3.3×）。
- **已验证（20:38/20:42 两次独立冷启 + 19:40 rep1）**：
  - DPR=2.625、CSS viewport 412.19×915.05、阅读器 scrollHeight=2002/ch=915 在 690 模式与 1188 模式**完全相同** → 尺寸不是 DPR/viewport 推导；
  - 1188 = 594×2（PDF pt 的 2 倍），690 = 594×1.1616；两模式渲染的 page0 PNG 字节数：1188 模式 76236 B **与 S9 的 png_bytes:76236 完全一致**，690 模式 40062 B；
  - 同一 run 内可混：rep3 前 3 页 1188、滚动触发的 page3 为 690（且 52114 B 与 690 模式 rep1/rep2 的 page3 字节一致）；
  - 结论：**宽高由 P0 的 WebView JS 调 `pdf:renderPage` 时传入的 width/height 决定**（Bridge.kt:90-94 只透传，默认 800），随阅读器布局/调用时机变化，不是固定值。
- 处置：①bench 的 INVALID run 结论「尺寸不固定」**方向正确**，但需用本审计的三次有效样本作证；②正式 P0 对照表按 (w,h) 分组（至少 690x977 与 1188x1682 两组），跨尺寸只比 ms/MP；③S9 的 1188x1682 应与 P0 的 1188 模式比（同尺寸、同 page0 76236 B），与 690 模式比属跨尺寸。

---

## 4. 模拟器漂移量化（bench baseline-p0-* vs 本审计 baseline-verify-*）

| 指标（PBPdf） | bench 同批次基线（引用） | 本审计独立基线 rep1 | 漂移 |
|---|---|---|---|
| pdf_open engine ms | 待 bench 正式集 | 382.0 | TODO |
| pdf_open bridge ms | 待 | 937.92 | TODO |
| render ms/页（median） | 待 | 248.7（n=4，690x977） | TODO |
| encode ms/页（median） | 待 | 273.35（n=4） | TODO |
| png bytes/页 | 待 | 40–64KB | TODO |
| 阅读后 PSS KB | 待 | 98285 | TODO |

漂移定义：`(verify - bench) / bench`，同口径（median）比较；>30–50% 时按协议 §1 视为跨批次不可直比，结论降级。**前提：bench 的 P0 正式集必须与本审计 rep2 在同一持锁窗口/同一天内**，否则只列参考。

---

## 5. 独立复算与差异表

### 5.1 与 bench parse.py 同输入对比（本审计独立 parser vs 官方 parser）

方法：同一 raw 文件分别喂给 `F:/tmp/verify/recompute.py` 与 `scripts/android/spike2/parse.py`（subprocess 只读调用，不 import），按 (scenario, phase) 逐组比较 n/median/P95/min/max；pss 行不参与（ms 非计时）。

| raw（同输入） | 组数 | 最大差异 | 判定 |
|---|---|---|---|
| tiles-dev-20261101-191330.log | 17 | 0.00% | PASS |
| tiles-dev-20261101-193520.log | 12 | 0.00% | PASS |
| tiles-dev-20261101-201201.log | 11 | 0.00% | PASS |
| render-run1-20261101-193254.log | 10（S1/S2/S9，含 PBTIME） | 0.00% | PASS |

结论：**算术实现一致（0.00%）**。风险不在统计，在采集侧（warmup 规则/污染/锁/跨批混合），见 §1 D/C/A 与 §5.3。

### 5.2 cold/warm 分组复算（主要组；cold=每文件每组首样本）

| raw | 组 | cold | warm P50 | all P50 | warm P95 | n |
|---|---|---|---|---|---|---|
| render-193254 | S2 render | 710.894 | 91.884 | 105.656 | 1142.839 | 20 |
| render-193254 | S2 present | 59.048 | 78.930 | 78.657 | 317.301 | 20 |
| render-193254 | S9 render | 1302.377 | 387.674 | 477.882 | 977.415 | 5 |
| render-193254 | S9 encode | 1295.730 | 928.496 | 1100.934 | 1213.832 | 5 |
| render-193254 | S9 write | 288.517 | 145.688 | 159.326 | 174.510 | 5 |
| render-193254 | S9 total | 2886.624 | 1485.845 | 1823.764 | 2252.859 | 5 |
| tiles-191330 | T1 first_screen | 1224.852 | — | 1224.852 | — | 1 |
| tiles-191330 | T1 tile | 151.165 | 67.1 | 67.1 | 277.347 | 9 |
| tiles-191330 | T2 tile | 217.761 | 113.5 | 118.044 | 185.3 | 18 |
| tiles-191330 | T3 frame_stat | 43.939 | 39.4 | 39.394 | 40.3 | 5 |
| tiles-191330 | T4 rerender | 589.2 | 530.9 | 546.270 | 870.501 | 5 |
| tiles-191330 | T6 first_screen | 607.837 | 519.0 | 529.470 | 570.8 | 5 |
| smoke-201032 | S10 render | 936.474 | 226.127 | 229.332 | 357.326 | 10 |
| smoke-201032 | S10 present | 34.259 | 44.328 | 42.332 | 72.428 | 9 |
| smoke-201032 | S10 first_frame | 49.979 | — | 49.979 | — | 1 |
| smoke-201032 | S2 render | 596.391 | 99.108 | 129.256 | 485.376 | 20 |

**不可合并的组（正式集必须拆分）**：
- `S1 open`：seed-03.pdf 395.478ms（4 页）与 realbook.pdf 3493.171ms（60 页）混在同一 phase → 按 asset 拆。
- `T2 full`（tiles-191330）：三条样本尺寸不同 —— 1080x1529 337.490、2160x3058 651.584、3240x4587 1310.371 → 按 zoom/尺寸拆，不能算一个 P50。
- `T1 tile`（193349/193520 混算）与 `T2 tile`（多尺寸/多 run）：按 (run, size) 拆。
- `T7 arch`（201201）：arch 24 条里混 1350x1911 等不同尺寸（见 §5.3 证据），按 size 拆。

### 5.2b 正式集 render-r1（S1/S2/S3）独立复算（5 reps，1188x1682，APK 8ea3edc）

输入：`raw/render-r1-run1-20261101-204131.log`、`-204647`、`-204732`、`-204818`、`-204859`（每份 48 条 PBPERF、单 pid、PBDONE S1=2/S2=20/S3=5、RUNENV apk_md5=8ea3edc683ebf0b5be2f32f32dc57bb9）。统计由本审计 parser 独立重算（不复用 parse.py 结果；同输入算术一致性见 §5.1）。

| 组 | n | P50 | P95near | min | max | cold P50 | warm P50 | warm P95 | 每 rep P50（rep1..5） | rep 间极差 |
|---|---|---|---|---|---|---|---|---|---|---|
| S1 open | 10 | 294.240 | 665.414 | 152.587 | 665.414 | 无 cold 标记 | 294.240 | 665.414 | 309.380 / 442.985 / 385.343 / 273.722 / 402.502 | 169.263（43.9%） |
| S2 render | 100 | 107.328 | 582.050 | 4.924 | 1525.398 | 150.532（n=20） | 93.856（n=80） | 409.732 | 128.764 / 127.332 / 98.949 / 99.788 / 100.718 | 29.816（29.6%） |
| S2 present | 100 | 42.785 | 126.506 | 18.029 | 705.791 | 50.931（n=20） | 39.907（n=80） | 91.171 | 40.618 / 50.983 / 40.904 / 37.709 / 38.862 | 13.273（32.7%） |
| S3 render | 25 | 300.426 | 520.122 | 136.061 | 549.477 | 199.300（n=5） | 312.795（n=20） | 520.122 | 348.929 / 397.943 / 282.918 / 190.727 / 312.551 | 207.216（66.3%） |

要点：

- **S1 无 cold 标记**（extra 无 cold:1），且 spike2 S1 同 phase 混 seed-03 与 realbook 两个资产（B1）→ 该组的 P50 仍不可直接当「打开耗时」结论；正式 summary 若给 S1 单一 P50 会失真。
- **S2 warm render P50 93.856 + present 39.907 ≈ 133.8ms/页**（1188x1682）。对 L3「单页（含合成）≤50ms」是 2.7×。但模拟器为 swiftshader 软件渲染、无 GPU，此阈值只能用于 A/B 与相对比较，不能据此判真机 FAIL。
- **P95 长尾明显**：S2 render pooled P95 582.050 由离群样本主导（max 1525.398，min 4.924）；warm P95 也到 409.732 → 与 §2.3 一致，读 P95 必须带 n 与 tail 说明。
- **S3（RGB565）异常慢**：P50 300.426 ≈ S2 ARGB 的 2.8×，且 cold（199.300）反而快于 warm（312.795），与 S2 的 cold>warm 相反。方向与 §4C-3「RGB_565 非原生、需 BGR888 临时缓冲再转」一致，但 extra 缺 `path:` 标记、且冷/热反向 → 记为待复跑/待代码确认项，不能当稳定结论。
- **rep 间抖动**：同 APK/同设备/同参数下 5 reps 的组内 P50 极差 29.6%（S2 render）～66.3%（S3）→ §6 原定「复跑差 ≤20% 视为抖动」阈值过严；模拟器跨 rep 噪声实际 ≥30–50%，跨 rep 结论应改用「同 rep 内配对」或放宽阈值并标注。

### 5.3 污染检测（工具 + 已知模式 + 当前判定）

工具：`F:/tmp/verify/contam.py --pkg app.particlebook --allow-pids <...>`。判据（任一命中 → INVALID，不静默采用）：
- `foreign_proc`：raw 里出现非目标 package 的 am start/pid；
- `foreign_start`：am start 的 package 不是目标；
- `anr_or_fatal`：出现 `Input dispatching timed out` / `ANR in` / `FATAL EXCEPTION`；
- `second_runmeta`：同一 raw 出现第二个 RUNMETA/RUNENV 头（第二次运行混入）；
- `unexpected_pid`：PBPERF 的 pid 不在允许集合；
- `multi_pid`：同一 raw 多个 pid（仅 WARN，用于识别「同一 capture 两份命名」或跨 app 混入）。

**已确认的污染模式（写进审计，供正式集验收）**：
1. **同一 buffer 双份 raw（跨 run 复用）**：`render-run1-20261101-193254.full.log` ↔ `render-run1-20261101-193254.log` 的 PBPERF 序列 63/63 完全一致；`render-smoke-run1-20261101-201032.log` ↔ `spike2-smoke-hw-run1-20261101-201115.log` 66/66 完全一致（同 ts+同 pid）→ 同一 capture 被两个文件名引用。判据：两份 raw 的 PBPERF payload 多重集相同或时间戳区间重叠。
2. **logcat 全 buffer 转储带入上一 app 的行**：`s8fix-run1-20261101-201840.log` 含 45 条 PBPERF，其中 T1/T7 共 36 条与 `tiles-dev-20261101-201451.log` 的 36 条 payload **完全相同**（同 pid 9281、设备 12:15:01–12:15:22）；而同一文件的 S8 行 pid 是 9407/9489（另一 app/进程）→ 该 raw 同时含两个 app 的日志段。判据：**单份 raw 同时出现 S* 与 T* 场景族**（S* 属 app.pb.spike2，T* 属 app.pb.spike2t），或同文件 pid 跨 app。
3. **PBDONE runs 与 raw 行数不符**：`tiles-dev-20261101-201451.log` PBDONE ALL=56，但 raw 只有 37 条 PBPERF、窗口 21s → 疑 buffer 截断/提前 dump；正式集必须逐 run 对账。
4. **持锁窗口被并发 adb 使用**（19:30:50–19:32:04，详见 §3.2）。

**当前判定**：本人 P0 rep1 VALID（仅 multi_pid WARN）。bench 冒烟集因 A2（无 RUNENV）与上述 1–3 模式，**只能作管线 sanity，不能进正式结论**（与 data.md §0 一致）。

### 5.4 已知口径/追溯问题（需 bench 在正式集前修正；均已告知）

1. `data.md §4.0` 冒烟表来源 `/f/tmp/smoke/smoke.log` 未归档 → 不可追溯（A1）。
2. `data.md §5.1` 「P0 同页 1188x1682 中位 160ms」与 raw 冲突：P0 实际 690x977、render median 248.7（n=4）。**bench 20:20 已改**：§5.1 现写 690x977/248.7、tiles 1080x1529 面积 2.44×、S9 1188x1682 面积 2.96×；§2.8 也补 690x977。→ 此项关闭（保留记录）。
3. `data.md §0` 引用「§5.5」但 §5 只有 4 条 → 文档 bug，建议改 §5 条目号或补 §5.5。
4. csv/pbperf.csv 等手工 dev 导出（无 app/src_file）→ bench 已加 csv/README.md 声明「不是 parse.py 产物、不进正式追溯」；正式 CSV 只认 `<tag>-merged.csv`（16 列含 src_file/src_line）。
5. PSS 行格式：`ms=0` 与 `ms=0.000` 混用（14 vs 9），tiles-dev-190131 的 T5/T6 pss `native_kb:-1` → 协议/校验器需约定。
6. S1 缺 bytes、S10 缺 `surface:ok`、T7 phase 名与协议 §4C 不一致（见 B1/B10/B12）。
7. **P0 pdf_page 尺寸口径未定**：本审计 rep1 全 690x977；bench 的 INVALID p0-cdpcheck 为 1188x1682（前 3 页）。bench 据此在 INVALID.md 写「P0 尺寸不固定」，但该 run 无效（并发/offline），不能当结论 → 本审计 §3.4 要求 rep2 用 DPR/viewport 与同 rep 多采样定性，正式表按 (w,h) 分组。
8. `p0-cdpcheck` 里 P0 pdf_open 208.0ms 与本审计 382.0ms（同 APK）差 1.8×，同为冷启打开不同页 → 说明 P0 open 也受设备状态影响，需同批对照。

---

## 6. 场景复跑（可复现性）

- 计划（>=2 个，等设备窗口）：① `app.pb.spike2` S2 render（1188x1682，cold+warm，n≥5）复跑，与 render-run1 的 warm P50 91.884 比；② `app.pb.spike2t` T1 tile（512x512，n≥5）复跑，与 tiles-191330 的 warm P50 67.1 比。
- **正式集已自带 5 reps 重复性（§5.2b）**：S2 render warm P50 93.856 与旧 render-run1（不同 APK）的 91.884 差 +2.1% → 跨批基本一致；但同批 5 reps 的组内 P50 极差 29.6–66.3% → 模拟器跨 rep 噪声大。因此原「复跑差 ≤20% 视为抖动」过严，复跑判定改为：差 ≤50% 视为模拟器噪声（须同时给两 rep 的 raw 与 n），>50% 标 DRIFT_WARN 并复核锁定状态/负载。
- 条件：同一 APK md5、同一设备、同参数；记录持锁窗口与 RUNENV；单流采集。
- 判定（已按 §5.2b 收紧前证据修正）：复跑中位与原值差 ≤50% 视为模拟器自身抖动（data.md §2.7 的 30–50% 上限内），>50% 标 DRIFT_WARN 并给两次 raw。

---

## 7. 截图抽查

已抽查 4 张 rep1 截图（1080x2400），结论：

| 截图 | 内容 | 判定 |
|---|---|---|
| `baseline-verify-p0-rep1-03-scroll1.png`（250,771B） | P0 阅读器第 4 页：真实中文 PDF 文字（「第二章 图表会撒谎」「第三章 相关不等于因果」+ 正文 + 浅蓝练习框），底部进度 100% / 第 4 页 | **PASS**（证明 PNG 链路真的渲染出内容，非空白 View） |
| `baseline-verify-p0-rep1-03-scroll2.png`（195,998B） | 同一第 4 页另一滚动位：第三章标题与正文可读，进度 100% / 第 4 页 | **PASS**（第二张独立证据，布局/文字一致） |
| `baseline-verify-p0-rep1-00-shelf.png`（45,951B） | 书架页显示「All books / **0 books**」+ 加载转圈，无 PDF 卡片 | **不可用**：截图时机太早（书籍列表未加载）；只能作「P0 首屏加载慢」的旁证 |
| `baseline-verify-p0-rep1-02-page1.png`（48,930B） | 阅读器页面区**全白**，进度 0.00%，仅工具栏/翻页按钮 | **不可用**：截图在 PDF 图片加载完成前；但 raw 的 `pdf_page bytes=40062` 说明 PNG 非空白（纯白 690x977 PNG 压缩后仅 ~2–3KB）→ 需「等图片 onload 后再截」 |

- 结论：**P0 渲染链路有真实内容（2 张 PASS）**，但「按阶段截图」的时机必须改为「等可见元素/图片加载完成」，否则会拍到白页/0 books 的假象（这也是 19:32 那次 03 截图全白的原因之一，另一因是并发清 logcat）。
- rep2 追加：截图后立即 `run-as app.particlebook` 拉 cacheDir PNG 做像素非白断言（见 §3.3）。
- 另抽查 bench 正式集 1–2 张运行时截图（若 run 脚本产生）；判定「截图里的页面内容与 raw 的 pdf_page/phase 对得上」。

---

## 8. 结论（中间态）

- **通过项**：统计口径与实现一致（§5.1 全 0.00%）；P0 独立基线 rep1 有效且可追溯（§3.1）；S9 与 P0 encode 差异可归因于分辨率 + BAOS/write + debug/负载（§2.2，归一化后 1.15–1.33×）；污染检测工具已建立并抓到 3 类模式（§5.3）；**正式集 render-r1 5 reps 逐份通过结构性检查（单 pid/RUNENV/PBDONE），独立复算出表（§5.2b），S2 warm render 93.856ms@1188x1682 与旧批 91.884（不同 APK）差 +2.1%**。
- **驳回/风险项**：A2 tiles 无 RUNENV（FAIL）；A1 冒烟表来源未归档（FAIL）；B8 S8 仍静默失败（FAIL，修复需像素证据；新 APK 8ea3edc 的 S8 尚未进正式集）；B1/B10/B12 口径缺项；B3 S3 缺 `path:native565|convert565` 标记且比 ARGB 慢 2.8×（WARN，待复跑/代码确认）；C3 跨资产/跨尺寸混算；E1/E3 PBDONE 与 raw 行数不匹配；D2 待双口径复算；**rep 间 P50 抖动 29.6–66.3%（S2 render/S3）→ 跨 rep 直比风险高**。
- **数据可用于 final report 的条件**：正式集每 run 有 RUNENV + 单 app 单锁窗口 + `--drop-mode first-per-phase` 显式执行 + 逐表 raw:line 来源 + 每份 raw 过 contam.py（VALID）+ P0 与 S9 同批窗口 + n≥5（P95 有效需 n≥20 或加 P90）。满足后，本审计对每个场景给 PASS/WARN/FAIL 与复算差异 <5%。

---

## 附录 A：证据路径与脚本

- 本审计独立 P0 raw（写作用域）：`spikes/spike2-results/raw/baseline-verify-p0-rep1-20261101-194046.log`（+ 同前缀 .txt 阶段快照与 .png 截图）。
- 被审计 raw（只读）：`spikes/spike2-results/raw/*.log`；正式 CSV：`csv/<tag>-merged.csv`；summary：`summary/*.md`；文档：`docs/android-port/spike2-data.md`。
- 独立脚本（当前在 F:/tmp/verify/，交付前复制到仓库）：
  - `recompute.py`：PBPERF/PBDONE/PBTIME/pdf_open/pdf_page 解析；all/cold/warm；median/nearest-rank P95/线性 P95/bootstrap CI；pss 单列；--p0 模式。
  - `contam.py`：污染判据（见 §5.3）。
  - `compare.py`：与 bench parse.py 同输入 diff（subprocess 只读）。
  - `scripts/android/spike2/p0-cdp.py`（已入仓）：CDP 驱动 P0（list/eval/open/pages/scroll）。
- 运行头/锁记录：`/f/tmp/pb-emu.lock/owner`（at/holder/pid/cmd）与各自 stream 日志。

## 附录 B：raw 清单与运行头状态（2026-11-01 20:53）

| raw 文件 | 行数 | RUNENV | apk_md5 | PBPERF 数 | PBDONE 行 | 备注 |
|---|---|---|---|---|---|---|
| render-run1-20261101-193254.log | 74 | 1 | 2dadaf173e8e… | 63 | S1/S2/S9/ALL | .full.log 同内容双份 |
| render-smoke-run1-20261101-201032.log | 1987 | 1 | b844a4043179… | 66 | — | 与 spike2-smoke-hw 同 66 条 |
| spike2-smoke-hw-run1-20261101-201115.log | 1989 | 1 | b844a4043179… | 66 | — | 同上，双份命名 |
| baseline-p0-run1-20261101-190509.log | 4 | 1 | 8e0e54f1fae2… | 0 | 0 | 冒烟失败 rep（无 pdf_open） |
| baseline-verify-p0-rep1-20261101-194046.log | — | provenance 头 | P0 apk sha256 记录 | 0（PBPdf） | 0 | 本审计 rep1（有效） |
| tiles-dev-20261101-190131.log | 87 | 0 | — | 64 | 7 | A2 FAIL |
| tiles-dev-20261101-191330.log | 74 | 0 | — | 64 | 7 | A2 FAIL |
| tiles-dev-20261101-193142.log | 0B | — | — | 0 | 0 | 事故产物（19:31:42） |
| tiles-dev-20261101-193349.log | 46 | 0 | — | 41 | 1 | A2 FAIL |
| tiles-dev-20261101-193520.log | 110 | 0 | — | 105 | 2 | A2 FAIL |
| tiles-dev-20261101-201201.log | 56 | 0 | — | 51 | 2 | T7 架构实验 |
| tiles-dev-20261101-201451.log | 41 | 0 | — | 37 | 1 | PBDONE ALL=56 与行数不符 |
| s8fix-run1-20261101-201840.log | — | 0 | — | 45 | — | pdf-render S8 调试；含 T* 跨 app 行 |
| s8fix2-run1-20261101-202041.log | — | 0 | — | 53 | — | 同上；S8 white:1.0000/changed:0 |
| render-r1-run1-20261101-202656.log(.rt/.struct) | — | 1 | 8ea3edc683eb… | 101（4 pids） | — | 首版 r1：被 buffer 污染（early=71，pids=9281/9407/9489/9579/9775）→ bench 已判 INVALID 并记 INVALID.md |
| render-r1-run1-20261101-204131.log | — | 1 | 8ea3edc683eb… | 48 | S1/S2/S3/ALL | r1 rep1，check_raw PASS（pid=10271, early=0）；独立复算 §5.2b |
| render-r1-run1-20261101-204647.log | — | 1 | 8ea3edc683eb… | 48 | S1/S2/S3/ALL | r1 rep2（pid=10571） |
| render-r1-run1-20261101-204732.log | — | 1 | 8ea3edc683eb… | 48 | S1/S2/S3/ALL | r1 rep3（pid=10652） |
| render-r1-run1-20261101-204818.log | — | 1 | 8ea3edc683eb… | 48 | S1/S2/S3/ALL | r1 rep4（pid=10738） |
| render-r1-run1-20261101-204859.log | — | 1 | 8ea3edc683eb… | 48 | S1/S2/S3/ALL | r1 rep5（pid=10823） |
| render-r2-run1-20261101-204959.log | — | 1 | 8ea3edc683eb… | — | S1/S4/S5/S7 | r2 批次（S4/S5）20:49 起，审计时仍在跑 |
| baseline-verify-p0-rep2-20261101-203813.log | 1401 | provenance | P0 apk sha256 | 0（PBPdf） | 0 | 本审计 20:38 冷启（690 模式）；单 pid=9950 |
| baseline-verify-p0-rep3-offlineglitch-20261101-204206.log | 1164 | provenance | 同上 | 0（PBPdf） | 0 | 本审计 20:42 冷启（1188 模式，设备 offline 抖动后采集）；单 pid=10326 |
| p0-cdpcheck-run1-20261101-202240.log | 463KB | — | P0 apk | 0（PBPdf） | 0 | INVALID（并发+offline）；但含 P0 pdf_open 208.0ms 与 4 页 pdf_page（尺寸 1188x1682×3 + 690x977×1） |
| INVALID.md | 1.7KB | — | — | — | — | bench 的作废 raw 清单（判据/时间/原因/处置） |

> 本表随正式集更新；「—」= 未核。
