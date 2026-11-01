# Spike 2 · B — 原生 PDF 分块渲染（tiles）

包名 `app.pb.spike2t`，入口 `.MainActivity`。代码在 `spikes/spike2-tiles/`，raw 日志在
`spikes/spike2-results/raw/tiles-dev-*.log`，CSV 在 `spikes/spike2-results/csv/`。

## 0. 作废数据清单（**复算前先看这里，别误用**）

| run | 状态 | 原因 |
|---|---|---|
| `20261101-190131` | **修复前**，只可当量级参考 | 每 tile 一次 `openPage`/`close`、`renderAnnot=true`、TileCache 未加锁。是 T3–T6 的唯一来源，但其 tile 绝对值偏高 1.3–4.8×。 |
| `20261101-191330` | **作废** | `run-bench.sh` 的参数解析 bug 让 install 被跳过，实际跑的是旧 APK（`adb: filename doesn't end .apk`）。 |
| `20261101-193349` | 有效（修复后） | T1 完整 + T2 前段。 |
| `20261101-193520` | 有效（修复后） | T2 完整（含 tiles_serial 判决 A/B）。 |
| `20261101-201201` | 有效（修复后 + T3 abMode + T7） | T3 abMode A/B、T7 三路架构对比、视口带内存账。 |
| `20261101-201201` 的截图 | **作废** | 拍在 T7 进行中，且 T3 结束时把 `abMode` 留在 1（只画背景）→ 空白。已修（最终画面前强制 `abMode=0`）。 |
| `20261101-201451` | 有效（T7 + 修复后的最终画面） | T7 第二轮 + 缩放态截图 `screenshots/tiles-20261101-201451.png`。 |

口径提醒：**跨 block 的时间不能直接比**。这台模拟器被 5 个人共享，同一操作不同 block 能差 2–4 倍
（典型：同一张 1080×1529 整页，19:44 测 205 ms，20:12 测 1466 ms）。只有**同 block、背靠背**的
相对比较有效。

## 1. 怎么构建 / 怎么跑

```bash
bash scripts/android/spike2/build.sh spikes/spike2-tiles :app:assembleDebug
bash spikes/spike2-tiles/run-bench.sh 72 T1,T2      # [秒] [场景子集]；全场景省略第三个参数
```

- `run-bench.sh` 自己不做 adb：`env.sh` → `wait_device` → **`lock.sh emu`** → `bench-body.sh`。
- `bench-body.sh`：install → force-stop → `logcat -c` → **起 `logcat -s PBPERF:V PBTIME:V` 流式落盘** →
  `am start -W` → sleep → 截图 → meminfo → kill 流 → force-stop。
  流式采集是必须的：共享设备上任何一方的 `logcat -c` 都会清掉「跑完再 `logcat -d`」的整份数据
  （19:31 那次就拿到 0 行）。
- 截图用 `adb exec-out "screencap -p" > file`，不要 `adb pull /sdcard/...`——Git Bash 会把
  `/sdcard` 改写成 `C:/Program Files/Git/sdcard`。

Intent 入口：`--es scenario T1,T3`（子集，逗号分隔）、`--ez autorun false`（不自动跑）。
跑完发 `PBDONE` 后停在最终画面、不 finish（便于截图）。

「修复后」= 复用打开的 `PdfPage`（LRU 8 页）、`renderAnnot=false`、TileCache 全部 `@Synchronized`。
哪份数据能用见开头 §0。

## 2. PDFium Android 1.0.24 的 API 硬事实（javap 实测，非文档）

`io.legere.pdfiumandroid.PdfPage`（`final class ... implements Closeable`）**只有**：

1. `renderPageBitmap(Bitmap bmp, int startX, int startY, int drawSizeX, int drawSizeY, boolean renderAnnot, boolean textMask)`
   → `FPDF_RenderPageBitmap`（经典偏移重载）
2. `renderPageBitmap(Bitmap bmp, Matrix matrix, RectF clipping, boolean renderAnnot, boolean textMask)`
   → `FPDF_RenderPageBitmapWithMatrix`
3. `renderPage(Surface, int startX, int startY, int drawSizeX, int drawSizeY, boolean renderAnnot)`
   → 零拷贝直上屏（`renderPage$default` 也在）

其它要点：
- `PdfiumCore.openPage(PdfDocument,int)` 返回裸 `long`；要 `PdfDocument.openPage(int)` 才拿到 `PdfPage`。
- `getPageWidthPoint()`/`getPageHeightPoint()` 返回 **int**。seed-03 的 MediaBox 是 594.96×841.92pt，
  所以报出来是 `pt:594x841`——是截断，不是精度 bug。
- 第 4 个 boolean `textMask` 在 1.0.24 native 里没被读取（死参数）。
- `renderAnnot=true` 会额外走 `FPDFDOC_InitFormFillEnvironment` + `FPDF_FFLDraw`。本实现所有渲染
  统一用 **`renderAnnot=false`**（两个重载的比较也是同 flag 对同 flag）。
- 所有 native 方法都 `synchronized(PdfiumCore.Companion.lock)`（JVM 全局监视器）：
  **多线程 tile 渲染是假的**，只能单渲染线程 + 队列。本实现即如此（`pdf-render` HandlerThread）。
- `Bitmap.Config.HARDWARE` / `ALPHA_8` 静默失败（logcat `jniPdfium`），只能用 ARGB_8888 / RGB_565。
  本实现一律 mutable ARGB_8888 + `eraseColor(WHITE)`。

## 3. 实现要点

- **tile 几何**：内容像素空间 = `pagePt × zoom`，与 view 1:1；512px 固定网格。
  矩阵 = `setScale(scale)` + `postTranslate(-tx*512, -ty*512)`，clip = `(0,0,tw,th)`。
- **page handle 复用**（关键）：`LinkedHashMap<Int, PdfPage>` accessOrder，容量 8，逐出时 `close()`，
  `PdfDoc.close()` 统一关。`keepPagesOpen=false` 可复现「每次渲染重开页」的旧管线，用于 A/B。
- **TileCache**：字节上限 32 MB 的 access-order LRU。**所有方法 `@Synchronized`**——access-order
  `LinkedHashMap.get()` 会改链表结构，UI 线程 `get()` 与渲染线程 `put()/trim()` 并发时，
  `trim()` 的迭代器会抛 `ConcurrentModificationException`（lead 提的 TileCache.kt:48，已修）。
  逐出的 bitmap 故意不 recycle：HWUI display list 可能还在读。
- **手势**：`gestureActive` 期间只画一张低分辨率整页 bitmap（360×510，30–125 ms），松手后重渲高清块。
- **自检 `tileprobe`**：矩阵 tile vs 整页渲染后裁剪，逐像素比较，差 >12 就自动降级到 `crop` 模式。

## 4. 结果

> 口径：emulator x86_64/API35/1080×2400/dpi420，AOSP，**swiftshader 软件 GL**，WHPX 加速。
> CPU 侧数字可用；fps 只能 A/B 横向比。**这台模拟器在 19:29–19:39 被 5 个人共享**，
> 同一操作在不同 block 能差 2–4 倍（下表的 p50 与 p95 差距就是它）。

### 4.1 正确性（最重要）

- `tileprobe`：`mode:matrix,diff:0.011,inkFull:0.2704,inkTile:0.2704,px:512x512`
  → 矩阵 tile 与「整页渲染后裁剪」**逐像素一致**（平均差 0.011/255，墨迹占比完全相同）。
- `overload_probe`：`vdiff:0.0114,bigpx:0.00000` → 两个重载输出**逐像素一致**，
  且没有任何像素差 >32/255。
- 截图 `screenshots/tiles-20261101-193520.png`：9 个 tile 拼出完整封面页（中文标题清晰、无接缝、
  无空白/错位），状态栏 `DONE view=1080x2400 tiles=9 cache=6450KB`。
- 截图 `screenshots/tiles-20261101-201451.png`：**2.0× 缩放态**，18 tile，cache 18,348 KB。
  teal 渐变在 15 个拼接点完全连续、中文锐利、无空白无错位。渐变是最容易暴露接缝/色带的图案，
  这一张是「缩放态无错位」的视觉证据。

### 4.2 tile 成本到底由什么决定（判决实验）

同一 block、同一页、同一 scale（fit=1.818），cached page handle，矩阵重载，n=5：

| tile 尺寸 | 实际像素 | P50 ms | P95 ms | 归一化 µs/px（P50） |
|---|---|---|---|---|
| 512 | 512×512 = 262 kpx | 21.2 | 75.4 | 0.081 |
| 1024 | 1024×1024 = 1048 kpx | 411.9 | 506.5 | 0.393 |
| 2048→整页 | 1080×1529 = 1651 kpx | 266.9 | 601.5 | 0.162 |

单看这张表会得出「1024 面积 4 倍、耗时 19 倍」的怪结论——**但那是 block 间噪声**，不是真实缩放关系。
真正干净的是下面这个同 block 双 pass 的 A/B：

| 实验（1.25×，1350×1911 = 2.58 Mpx，同一块像素） | ms |
|---|---|
| **12 个 512 tile 串行**（3 列 × 4 行） | **141.8** |
| **1 次整页渲染**（同尺寸） | **142.9** |
| `tile_overhead` | **−1.1，ratio 0.99，per_tile 11.8** |

**结论：tile 的 per-call 固定开销 ≈ 0，成本纯粹 ∝ 实际栅格化的像素数。**
渲染 12 次和渲染 1 次、像素总量相同时，耗时相同（141.8 vs 142.9 ms）。

于是「tile 按像素比整页慢 2–3 倍」的旧观测被推翻了：那是 19:01 那轮**每次渲染都 openPage/close**
（丢掉 PDFium 的页级渲染缓存）+ 跨 block 噪声叠加的产物。修复后两路像素产出完全等价。

实测光栅化吞吐：**18–28 kpx/ms（≈14–28 Mpx/s）**，即 1080×1529（1.65 Mpx）整页 **60–130 ms**。
这一条与 P0 的「同页 1188×1682 中位 160 ms」在同一个量级，互相印证。

### 4.3 复用打开的 PdfPage（关键修复）

同 block 相邻、512² tile、矩阵重载、fit，n=5：

| 管线 | avg | P50 | P95 |
|---|---|---|---|
| 每次渲染 `openPage`/`close`（旧） | 109.9 | 121.4 | 207.3 |
| 复用同一 `PdfPage`（新） | **26.7** | **25.4** | **35.9** |

→ **1.3×–4.8× 提速**（两个 block 分别测得 1.3× 与 4.8×，方向一致）。
机理：`FPDF_LoadPage` 每次会新建 `CPDF_Page`，其 `CPDF_PageRenderCache`（已解码图片、
已解析字体、内容流对象）随之丢弃。**一页只 open 一次、滚出 viewport 再 close** 是必须的。

### 4.4 两个重载对比（matrix+clip vs 经典偏移）

tile (0,0)、fit、cached page、n=5，先 `overload_probe` 证明像素一致：

| tile | matrix+clip P50 / P95 | offset P50 / P95 | 倍率 |
|---|---|---|---|
| 512 | 136.1 / 204.1 | **45.2 / 114.1** | offset 快 ~3.0× |
| 1024 | 252.4 / 445.8 | 293.9 / 452.3 | 持平 |

- 512 那一组 offset 明显快；1024 那组持平。**方向不一致，说明还有 block 噪声**，需要复测确认倍率。
- 但两条已经可以定：**① 两者像素级等价；② offset 从不更慢，且实现更简单**
  （不需要构造 `Matrix`/`RectF`，`drawSize` 直接给整页尺寸，负偏移即 tile 原点）。
- **建议 tile 实现改用 offset 重载**：
  `renderPageBitmap(bmp, -tx*512, -ty*512, fullW, fullH, renderAnnot=false, false)`。

### 4.5 冷热曲线（580 ms 到底是不是冷启动）

同一页连续整页渲染 5 次，1080×1529，`renderAnnot=false`，cached page：

| seq | 0 | 1 | 2 | 3 | 4 |
|---|---|---|---|---|---|
| page 0（此前已被渲过很多次） | 116.8 | 101.0 | 121.6 | 124.4 | 131.9 |
| page 1（**本轮第一次触碰**） | 50.2 | 51.2 | 35.1 | 62.9 | 100.7 |

**没有冷启动惩罚**：page 1 首渲（50 ms）反而比 page 0 的稳定态（117 ms）快一倍。
→ 19:01 的 `full 1080x1529 = 580 ms` 不是冷启动，是**旧的 openPage/close 管线 + 并发噪声**。
真实稳态：1080×1529 整页 60–130 ms。

### 4.6 分块到底值不值（判决）

由 4.2，成本只跟「实际栅格化的像素数」有关，所以分块的收益**恰好等于**
「可见像素 / 整页像素」，不多不少：

| zoom | 整页像素 | 整页渲染 | 屏幕所需像素 | 分块渲染 | 分块收益 |
|---|---|---|---|---|---|
| 1.0（fit） | 1080×1529 = 1.65 M | 205 ms（实测） | 1.65 M（整页都可见） | ≈205 ms | **无** |
| 2.0 | 2160×3058 = 6.6 M | 237 ms（实测） | ≈3.9 M（15 tile） | ≈140 ms | ~1.7× |
| 3.0 | 3240×4587 = 14.9 M | 462 ms（实测） | ≈3.9 M（15 tile） | ≈110 ms | ~3.8× |

（分块列由 18–28 kpx/ms 推算，实测 `tiles_serial` 给出 11.8 ms/tile 作为锚点。）

**所以：fit 宽度下整页渲染就够（分块没有收益）；只有 zoom ≥ 2 才有收益，收益量级由「可见像素 / 整页像素」决定。**

> ⚠️ **本节到此为止，下面的推论已被 §4.10 的实测推翻。**
> 我当时由「per-call 开销 ≈ 0」推出「没有任何理由用 512 固定网格，应该改成单张视口带渲染」。
> T7 实测结果是反的：**z=3 时网格比视口带快 1.3–2.0×，z=2 时两者持平**。
> 架构结论以下面 §4.10 / §4.11 为准。

### 4.7 T1 首屏（含打开）

run `193349`（修复后）：`open` 639.8 ms（4 页，152,591 B，mode:matrix），
逐 tile 523.5 / 91.1 / 0.6 / 112.0 / 128.2 / 0.6 / 27.9 / 13.5 / 0.3 ms（**第一个 tile 523 ms 是
页级一次性工作：内容流解析 + 字体加载**，之后同页 tile 降到 13–128 ms），
`first_screen` 2074.5 ms（tiles:9, ok:1），`read_path = open + first_screen = 2714.3 ms`。

字段口径（回应 lead 的 `fromOpen`）：
- `first_screen`：**`open()` 返回之后**，从 `setPage` 到「9 个 tile 全部画完并呈现一帧」，不含打开；
- `read_path_ms`：`open() + first_screen`，即「给一个文件 → 屏幕上有可读正文」；
- `from_create`：`Activity.onCreate` 到同一时刻，含进程启动、view 膨胀、线程启动、JIT、autorun 延迟。
  旧字段名 `fromOpen` 就是这么来的，新日志已更名为 `from_create` 并补上 `read_path_ms`。

注意 `first_screen`(2074) ≫ tile 之和(897)：差额是「等 vsync 且把 9 帧画面推上去」的时间。
swiftshader 下合成一帧本身就要 ~40 ms，9 块渐进上屏必然吃掉几百 ms。

### 4.8 T3–T6（**只有修复前的那一轮**，需在冻结 APK 上复测）

run `190131`（旧管线，仅供量级参考）：

- **T3 平移**（zoom 2.0，5 rep，42 帧）：avg 50.8 / P50 50.0 / P95 83.3 / max 150.0 ms，
  `fps_avg 19.7`，`over16_ratio 1.0`。
- **T4 手势缩放**（1.0→3.0→1.0，每段 400 ms）：avg 43.7–48.3 ms（≈20–23 fps），
  松手后重渲 701–815 ms（tiles:9，tiles_hi:15）。
- **T5 20 次平移+缩放**：PSS 81,897 → 108,362 KB（**+26.5 MB**），
  cache hit 1,176 / evict 213 / miss 2,042，bytes 32.8 MB / limit 32 MB，tiles 36。
  ⚠️ **这个增量取决于进程内的内存高水位，不只看 tile 缓存**：`runT5` 开头会 `cache.clear()`，
  但 native allocator 已占住的页不会还给系统。同一份代码，T5 跟在 T1–T4 后面跑 = **+26.5 MB**，
  而进程里先跑过 T2（大量分配/回收大位图）再跑 T5 = **+7.0 MB**（run 191330）。
  → 能进 L3 判决（≤80 MB）的应取**单独冷启的 T5**；进程复用后的 +7.0 MB 只能当「二次运行」参考。
  两个数都远低于 80 MB，结论不变。
- **T6 realbook.pdf（60 页）**：open 1,573 ms，5 次翻页 `first_screen` 574/605/504/385/532 ms（每页 9 tile），
  收尾 PSS 143,338 KB；ALL PSS 118,782 KB。

L3 目标对照（**旧管线**）：首屏 ≤200 ms ✗（2074）；单页 ≤50 ms ✗（tile P50 21–85 波动，整页 60–130）；
翻页 P95 ≤70 ms ✗（384–605）；zoom ≥50 fps ✗（20–25）；PSS Δ≤80 MB ✓（+26.5）。

**T3/T4 的 20–25 fps 需要 A/B 归因**，我已在代码里加了 `view.abMode`（0=tiles / 1=只有背景 /
2=一张低分辨率整页），T3 会分别跑 5/3/3 个 rep 并打 `ab:tiles|bg|lowres`。
我的推测：瓶颈在 **swiftshader 软件栅格化 15×512² 纹理**，而不是 PDFium——但必须由这次 A/B 证实。

### 4.9 T3 帧率归因：这台模拟器的 fps 天花板（**fps 不可外推真机**）

同一 block、zoom 2.0、同一段平移动画，只换 `view.abMode`（`FrameSampler` 采 vsync 间隔）：

| 每帧画什么 | n | avg ms | P50 ms | P95 ms | fps_avg |
|---|---|---|---|---|---|
| 15 个 512 tile（真实路径） | 46 | 49.6 | 50.0 | 116.7 | **20.1** |
| 1 张低分辨率整页（360×510） | 28 | 45.2 | 33.3 | 83.3 | 22.1 |
| **什么都不画（只有背景色）** | 34 | 37.3 | **33.3** | 66.7 | **26.8** |

**结论：把每帧内容全部去掉，帧时间也只从 49.6 ms 掉到 37.3 ms——下限 33.3 ms/frame 是这台
模拟器 swiftshader 软件合成的固定成本，跟 PDFium、跟 tile 都无关。** 换句话说 tile 管线自己只占
**49.6 − 37.3 = 12.3 ms/帧**（约四分之一），剩下四分之三是宿主环境的软件合成。

因此：
- 本机测得的 20–25 fps **不能用来判 L3 的「zoom ≥50 fps」**——空 view 都只有 26.8 fps，
  50 fps 在这台机器上数学上不可达；
- 唯一可移植的数字是 **12.3 ms/帧的 tile 提交成本**，上真 GPU 后会小得多；
- 报告里这一项必须写「**fps 不可外推真机，仅作 A/B 相对比较**」。

### 4.10 架构判决：512 网格 vs 单张视口带 vs 整页（T7）

**背景**：由 4.2，tile 的 per-call 开销≈0，成本只跟像素数走 → 我原本预测「一次渲出视口大小的
位图」应该和 15 个 tile 等价甚至更快、还能省掉网格簿记。**数据推翻了这个预测。**

同一 block、pan(0,0)、同一可见区域（1080×2400 = 2.59 Mpx）、每路 1 次预热 + 3 次计时、cached page handle、
offset 重载：

| zoom | 方案 | 位图 | n | avg ms | P50 | P95 |
|---|---|---|---|---|---|---|
| 2 | 整页 2160×3058 | 25 MB | 3 | 1466.0 | 1425.7 | 1637.3 |
| 2 | **15 个 512 tile** | 15×1 MB | 3 | **353.8** | 337.0 | 398.5 |
| 2 | **单张视口带** | 9.9 MB | 3 | **380.5** | 382.6 | 415.8 |
| 3 | 整页 3240×4587 | 56 MB | 3 | 920.1 | 888.7 | 1096.4 |
| 3 | **15 个 512 tile** | 15×1 MB | 3 | **197.5** | 201.8 | 239.1 |
| 3 | **单张视口带** | 9.9 MB | 3 | **385.6** | 369.3 | 488.4 |

比值（`arch_ratio`）：z=2 `band_over_tiles 1.076`、`band_over_full 0.260`、`tiles_over_full 0.241`；
z=3 `band_over_tiles 1.952`、`band_over_full 0.419`、`tiles_over_full 0.215`。

第二轮复测（run `201451`，竞争更小，同一套代码）**复现了 z=3 的方向、把 z=2 拉平**：

| zoom | 整页 | 网格 | 视口带 | band/tiles | tiles/整页 |
|---|---|---|---|---|---|
| 2 | 623.7 | 366.3 | 283.5 | **0.774** | 0.587 |
| 3 | 496.3 | **162.1** | 213.1 | **1.315** | 0.327 |

**判决（两轮合并）：**
- **z=2：网格与视口带持平**（band/tiles 两轮 1.08 / 0.77，来回翻转 → 在噪声内）；
- **z=3：网格稳定更快**（1.95 / 1.32，两轮方向一致），差距随 zoom 放大；
- 两者都远快于整页（`tiles_over_full` 0.22–0.59），符合「成本 ∝ 可见像素」；
- **结论：没有理由用视口带替换网格，L3 维持网格。** 网格另外还有视口带完全没有的增量复用能力（§4.11）。

为什么 15 次小裁剪反而比 1 次大裁剪快？最可能的机制是 **PDFium 按「设备裁剪框 vs 对象 bbox」做
对象级剔除**：小裁剪框能整片拒掉大量对象/长路径/长文本行，大裁剪框让更多对象「部分可见」而进入
昂贵的裁剪-栅格化路径；同时 1 MB 的 tile 位图对 CPU 缓存友好，10 MB 的带位图会抖。**这是基于
观测的推测，没有读 pdfium 源码验证。**

### 4.11 内存账：一条能定死架构的算术

`T7|mem` 实测与推算（MB）：

| 常驻对象 | 单个 | 3 页常驻 |
|---|---|---|
| 整页位图（fit，1080×1529） | **6.30** | **18.90** |
| 视口带位图（1080×2400） | **9.89** | **29.66** |
| 整页位图（z=2，2160×3058） | 25.2 | 75.6 |
| 整页位图（z=3，3240×4587） | 56.8 | 170.4 |
| 512 tile | 1.0 | — |
| tile 缓存上限（T5 实测 32.8 MB） | — | 32.00 |

三个把架构定死的点：
1. **视口带比整页位图更占内存**（9.89 vs 6.30 MB/页）——屏幕比页面高，band 按 viewport 给尺寸。
2. **整页位图无法随 zoom 常驻**：z=3 一页 56.8 MB，3 页就 170 MB，直接 OOM。
3. **只有 tile 能增量复用**：T5 实测 cache hit 1,176 / miss 2,042 / evict 213（命中率 36.5%），
   平移时只渲新露出的那条边；视口带每次平移都要整屏重渲（≈380 ms），**复用率为 0**。

→ L3 应该维持「**512 网格 + offset 重载 + 页 handle 复用 + 32 MB LRU**」，
不要「视口带 + 页位图 LRU」。

### 4.12 PSS 口径修复

旧日志 `native_kb:-1`：`Debug.MemoryInfo.getMemoryStat("summary.native-pss")` 在普通构建上返回 null
（依赖 `debug.memory_info.*` 系统属性）。已改用同一次 `/proc` 遍历填充的
`mi.nativePss` / `mi.dalvikPss`。修复后实测一例：`pss_kb:58735,native_kb:29654,java_kb:832`。

## 5. 真机预期换算（**推测**）

PDFium 是纯 CPU 光栅化，模拟器是 WHPX 加速的 x86 翻译执行。按团队统一口径
**真机 ≈ 本模拟器 × 1.8（区间 1.4–2.5）**：

- 1080×1529 整页：60–130 ms → **真机约 33–72 ms**；
- 512² tile（cached handle）：21–45 ms → **真机约 12–25 ms**；
- 首屏 9 tile（冷文件）：约 900 ms 栅格化 + 打开 640 ms → 真机估算 **≈850 ms**，
  仍远高于 L3 的 200 ms，主要卡在「一次开 60 页 + 每页首次解析」而不是分块本身。

**这是推算，不是实测**，标注为推测。

**fps 不能这样换算**：§4.9 已证明本机「什么都不画」只有 26.8 fps，瓶颈是模拟器的软件合成而不是
PDFium 光栅化，所以**本机的 20–25 fps 乘 1.8 没有意义**。可以外推的只有 CPU 侧的栅格化 ms，
fps 必须写「**仅作 A/B 相对比较，不可外推真机**」。

## 6. 交给 pdf-bench 的正式测量包

- APK：`spikes/spike2-tiles/app/build/outputs/apk/debug/app-debug.apk`
  `md5 7ab3634ff0d97dcad0a759c988304e5d`（20:15 构建：T1–T7）
- **冻结参考版**（19:11 构建，只到 T6）：`spikes/spike2-tiles/app-debug-frozen-193349.apk`
  `md5 5f1312f24539c257706ef410dcab97eb`
- 包名 `app.pb.spike2t`，入口 `.MainActivity`
- 命令：
  ```bash
  adb install -r spikes/spike2-tiles/app/build/outputs/apk/debug/app-debug.apk
  adb shell am start -W -n app.pb.spike2t/.MainActivity                      # 全场景
  adb shell am start -W -n app.pb.spike2t/.MainActivity --es scenario T1,T3 # 子集，逗号分隔
  adb shell am start -W -n app.pb.spike2t/.MainActivity --ez autorun false  # 只起来不跑
  ```
- scenario 列表与工作量（单次冷启，串行）：T1 首屏 ≈9 s；T2 tile_cost ≈40 s；T3 帧率 ≈16 s；
  T4 手势 ≈13 s；T5 内存 20 循环 ≈26 s；T6 60 页翻页 ≈8 s；T7 架构 ≈8 s。**全场景一次 ≈120 s。**
- 日志 tag `PBPERF`/`PBTIME`；跑完发 `PBDONE` 并停住不 finish。
- 采集：**启动前**起 `adb logcat -s PBPERF:V PBTIME:V > file &` 流式落盘（别 `logcat -d`）；
  截图用 `adb exec-out "screencap -p" > f.png`（别 `adb pull /sdcard/...`）；不要 `am kill-all`。

## 7. 已知问题 / 还没做的

1. **T3–T6 的正式数据已交给 pdf-bench**（用 §6 的冻结 APK，每场景 ≥5 样本 ×3 重装冷启）。
   T3 的 `abMode` A/B 我已跑完（§4.9）；T4/T5/T6 仍只有修复前的 190131 那轮数字。
2. ~~缩放态截图缺失~~ **已补**：`screenshots/tiles-20261101-201451.png`（2.0× 缩放、18 tile、
   cache 18,348 KB）。teal 渐变在 15 个 tile 拼接处**完全连续**、中文字形锐利、无空白无错位——
   渐变最容易暴露接缝/色带 bug，这一张可以直接当视觉证据。
3. **offset vs matrix 的倍率需要复测**：512 组差 3×、1024 组持平，方向不一致。
4. **`renderPage(Surface)` 零拷贝直上屏仍未做**（编号 T7 已被架构判决实验占用，这里叫 T8）。
   它针对的不是「网格 vs 带」而是「bitmap → GPU 纹理上传」这一段：本机测到空 view 也只有
   26.8 fps，说明合成侧才是大头，Surface 直上屏能否省下这一段**值得在真机验，模拟器上验不出来**。
5. `renderAnnot=false` 是**全量应用**但没有单独 A/B（原计划 5–30% 的收益未量化，本页无表单域，
   预期接近 0）。
6. 分块渲染的 `first_screen` 里「等 vsync 上屏」占比很大（2074 vs 897 ms），
   说明**渐进上屏策略**（先画低分辨率占位再补高清）比纯 tile 流水更能改善首屏观感。
