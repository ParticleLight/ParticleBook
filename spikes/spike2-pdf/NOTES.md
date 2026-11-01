# spike2-pdf · `app.pb.spike2` 全页直渲染基准 app —— 实现与运行说明

> 作者：pdf-render（ParticleBook Spike2 队员，S2-A）
> 最后更新：2026-11-01 21:05（代码在 2026-11-01 19:32 的协议语义下冻结；Build/判定细节可能由 Lead 微调，改前先 `git diff`）
> 相关文档：协议 `docs/android-port/spike2-protocol.md`（§3 日志契约、§4A 场景矩阵、§4C 库事实）、研究 `docs/android-port/spike2-research.md`（§1 库事实、§6b S8/S10/S6 实测判决）

## 0. 一句话

一个单 Activity 的 Kotlin app：启动即按 `scenario` extra 自动跑 S1–S10；所有测量只通过 logcat tag `PBPERF` 的结构化行出数据；渲染库只用 `io.legere:pdfiumandroid:1.0.24`，不依赖 WebView。

## 1. 目录与产物

    spikes/spike2-pdf/
    ├── settings.gradle.kts / build.gradle.kts / gradle.properties / local.properties   # AGP 8.7.3 / Kotlin 2.0.21 / compileSdk 35 / minSdk 26
    ├── app/build.gradle.kts        # 唯一依赖 io.legere:pdfiumandroid:1.0.24；abiFilters arm64-v8a,x86_64
    ├── app/src/main/AndroidManifest.xml   # 入口 .BenchActivity（exported）
    ├── app/src/main/assets/seed-03.pdf    # 4 页 594×841pt, 152,591B（主测试书）
    ├── app/src/main/assets/realbook.pdf   # 60 页 443×581pt, 219,671B（翻页场景）
    ├── app/src/main/java/app/pb/spike2/BenchActivity.kt   # 全部实现（~890 行）
    └── evidence/                   # 实拍/截图证据（S10 视觉验证等）

APK：`spikes/spike2-pdf/app/build/outputs/apk/debug/app-debug.apk`（包名 `app.pb.spike2`）。
正式集冻结 APK：md5 `8ea3edc683ebf0b5be2f32f32dc57bb9`，7,287,902B（2026-11-01 20:22 构建）。

## 2. 构建 / 安装 / 运行

构建（必须走构建锁脚本，offline，40–120s）：

    bash scripts/android/spike2/build.sh spikes/spike2-pdf :app:assembleDebug

运行（单场景冒烟，含截图；锁由脚本内部持有）：

    bash scripts/android/spike2/run-render.sh --scenarios S10 --reps 1 --screenshot --tag s10-shot --no-parse
    bash scripts/android/spike2/run-render.sh --scenarios S2,S4 --reps 3
    bash scripts/android/spike2/run-render.sh --reps 5          # 全场景

`run-render.sh` 是 `run-app.sh` 的 preset（PB_APP=spike2 / PKG=app.pb.spike2 / COMPONENT=app.pb.spike2/.BenchActivity / APK 默认 debug 包）；每 rep = 重装 APK + `am start` 冷启 + 等 PBDONE + 收 logcat + 截图 + force-stop，产出 `spikes/spike2-results/raw/<tag>-run<N>-<stamp>.log`（由 `.struct` 脚本结构行与 `.rt` 流式 logcat 合并而成）。

设备命令一律走锁（单把 emu 锁，单次 ≤20min）：

    bash scripts/android/spike2/lock.sh emu <adb 命令...>   # 锁=/f/tmp/pb-emu.lock

约束：宿主单次工具调用有 120s 硬上限（timeoutMs 也会被截断）⇒ 一个 rep 一次调用（45–60s），不要一次跑 5 reps；跑前确认锁主存活、上一 run 已结束。

直接 am start 调试：

    adb shell am start -W -n app.pb.spike2/.BenchActivity --es scenario "S1,S2,S10" --ei runs 5
    adb shell am start -W -n app.pb.spike2/.BenchActivity --es scenario S2 --es size 1080x1529 --ei runs 3

（`size=WxH` 可覆盖 S2 渲染尺寸；`runs` 默认 5 / 范围 1–50；scenario 省略 = 全部。S1 总是最先跑，其它场景依赖已打开的文档。）

## 3. 场景与日志契约

日志行格式（tag PBPERF，Locale.US，ms 3 位小数；page/w/h 不适用填 -1；extra 逗号分项、冒号键值、无空格/竖线）：

    PBPERF|scenario=..|phase=..|page=..|w=..|h=..|ms=..|extra=..
    PBDONE|scenario=..|runs=..|extra=..
    PBTIME|app=spike2|phase=first_frame|ms=..

| 场景 | phase | page | w×h | extra 关键键 | 说明 |
|---|---|---|---|---|---|
| S1 open | open | -1 | -1 | `asset,pages,pt:WxH,mode:bitmap` | 打开 seed-03 / realbook，报 pages 与页尺寸（pt） |
| S2 整页 ARGB_8888 | render / present | 0..3 | 1188×1682 | `path:argb8888,cold,annot` | 每页 ×runs；present=Choreographer 首帧；另报 PBTIME phase=first_screen / open_to_first_screen |
| S3 RGB_565 | render | 0 | 1188×1682 | `cfg:rgb565,cold,bytes` | Bitmap.Config.RGB_565 同尺寸对照 |
| S4 连续翻页 | total | 0..4 | 1080×1529 | `round,render,present,cold` | realbook 5 页 ×runs 轮；total 内拆 render/present；前后各一个 pss 点 |
| S5 文本层 | text | 每页 | 页面 pt | `chars,boxes` | openTextPage + 逐字符 textPageGetCharBox |
| S6 dpi 曲线 | render | 0 | w×h | `dpi,bytes,cold` | dpi ∈ {72,130,200} ×runs 轮；尺寸 = round(pt/72*dpi) → 594×841 / 1073×1518 / 1650×2336 |
| S7 PSS | pss | -1 | -1 | `tag,pss_kb,native_kb,java_kb` | 4 个点：cold（onCreate，PDFium 调用前）/ open / 10pages / 30pages（Debug.MemoryInfo） |
| S8 HARDWARE | render_hw | 0 | 1188×1682 | `cfg:hardware,hw,seq,white,blank,crc,changed,px8,err,verdict` | 5 次探测；像素指纹+白占比判 silent-fail；PBDONE extra=verdict:…,painted:..,silent:..,throw:..,changed:.. |
| S9 PNG 旧链路 | render / encode / write / total | 0 | 1188×1682 | `bytes,path:baos` / `bytes,dst:cacheDir` / `render:..,encode:..,write:..` | 与 P0 对照：render→PNG compress(100)→cacheDir 落盘+fsync |
| S10 Surface 整页直写 | render / first_frame / present / check | idx 0/1 | drawW×drawH | `mode:surface,seq,startX,annot,drawW,drawH,surfaceW,surfaceH` / check 加 `mid_white,bot_white` | PdfPage.renderPage(Surface,0,0,dw,dh,false)；idx0=Surface 实际 1080×2400，idx1=1080×1529 对照；每档 runs 次（≥2） |

所有 phase 每场景 ≥5 有效样本（S1/S5/S7 按自然样本数），warmup 由解析侧按 --drop-first/--drop-mode 剔除；失败行 extra 里带 error:<Class>。

## 4. 实现路径（BenchActivity.kt）

- 线程模型：全部 PDFium 调用在单条 HandlerThread("bench") 上串行执行（库内部 PdfiumCore.Companion.lock 全局锁，多线程不会更快，只会互相等）；UI 呈现类测量回到 UI 线程 + Choreographer.postFrameCallback 取首帧。
- 打开：PdfiumCore(this) → newDocument(asset InputStream) → doc.openPage(i)；每页用完 p.close()（库无 dispose，只有 close）。
- present：presentFirstFrame(bmp) = runOnUiThread { iv.setImageBitmap(bmp); postFrameCallback { latch.countDown() } }，返回调用→首帧耗时；同一 UI 线程内换图后再回收上一张，避免画到已回收位图。
- PSS：Debug.MemoryInfo + Debug.getMemoryInfo，报 totalPss / nativePss / dalvikPss（KB）。
- S8 判定（v3）：err!=none && ink<0 → unreadable；err!=none → throw；ink>=0.9999 → silent-fail；ms<10 → silent-fail；否则 painted。像素侧同时报 CRC32 指纹（步长 8 采样）+ changed + px8；jniPdfium 日志是第三方证据（见 §5）。
- S10：sv.visibility=VISIBLE → 等 SurfaceHolder.surfaceCreated latch（6s 超时）→ 每档每 seq：openPage(0) + renderPage(surface, 0, 0, dw, dh, false)；每档结束用 PixelCopy 采样 mid/bot 白占比（HARDWARE surface 不能直接读像素；PixelCopy 失败返回 -1 不崩）。

## 5. pdfiumandroid 1.0.24 API 限制（已核实，详见 research §1/§1.6/§6b）

AAR 位置：/g/Android/gradle-home/caches/modules-2/files-2.1/io.legere/pdfiumandroid/1.0.24/…/pdfiumandroid-1.0.24.aar（John Gray 的 Kotlin 版；native 含 x86_64/arm64-v8a）。

1. Bitmap.Config.HARDWARE 不可用（API 35 实测）：createBitmap(w,h,HARDWARE) 直接抛 IllegalArgumentException: can't create mutable bitmap with Config.HARDWARE；用 ARGB_8888.copy(HARDWARE,false) 造出 immutable 后 render 不抛异常但零像素写入——native 卡在 AndroidBitmap_lockPixels，logcat jniPdfium: Locking bitmap failed: No such file or directory。L3 唯一渲染配置 = ARGB_8888 + eraseColor(WHITE) + renderAnnot=false。
2. renderPage(Surface, …)：真零拷贝、整页直写，但 ①无 tile/缩放复用；②drawSize 直接映射页面（会拉伸，不做 aspect fit/letterbox）——必须传页面比例的 rect（594:841 → 1080×1529）；③只写 [0,drawW]×[0,drawH]，区域外不清屏（实测下方未绘区域是未初始化垃圾内存，非黑色/非 #848484）；④只支持 RGBA_8888 格式（jniPdfium: Set format to RGBA_8888 是路径指纹）；⑤参数坑：startX/startY 实际只取一个 int 且同时当两者，永远传 0。
3. RGB_565：native 先 malloc(w*h*3) 渲 BGR888 再转 565（多一次全图转换 + 临时内存），比 ARGB_8888 无优势。
4. 分块：只有 renderPageBitmap(bitmap, Matrix, RectF clip, boolean, boolean) 这个重载（Matrix 只透传 [a,d,tx,ty]），分块要自己算矩阵。
5. 全局串行：PdfPage 所有方法 synchronized(PdfiumCore.Companion.lock)；native 无 ByteBuffer 像素 API。
6. 无 dispose：PdfDocument/PdfPage/PdfTextPage 只有 close()。

## 6. 坑清单（都是踩过的）

- adb logcat -c 不一定真清空：正式集必须断言 logcat -c 后 adb logcat -d -s PBPERF:V | wc -l == 0；否则上一 run 的 PBPERF 混进本次（5 样本变 10）。并发跑两个 run 同理。
- 两个 run 时间重叠会互相杀 app（后装的 APK 会 force-stop 前一个进程）——跑前确认锁主存活、上一 run 已结束。
- 工具调用 120s 硬上限：整批 5 reps 必死，改一 rep 一次；脚本侧收尾（RUNMETA/manifest）若没跑完，raw 标 INVALID。
- S8 白占比/耗时判据会假阳性：HARDWARE no-op 时 5 帧全白、CRC 恒定、ms 0.1–3.5ms；但 s8fix2 也出现过 12.083ms 的 no-op（与真渲染 17–30ms 重叠）⇒ 硬判据 = 渲染前后像素 CRC 指纹 + jniPdfium 错误日志。
- S10 截图：screencap 能抓到 SurfaceView 内容（swiftshader + -no-window 下实测可），但页面区外是未定义内容；--screenshot 拍的是跑完后的最后一帧（idx1 状态）。
- warmup：S2 首次渲染含 PDFium 初始化（首帧 800ms–1.4s），统计必须剔除 cold 样本（extra 里 cold:1）。
- ABI：x86_64 模拟器必须有 x86_64 native（1.0.24 有）；不要用 arm 模拟器。

## 7. 采集与解析

- 原始日志：spikes/spike2-results/raw/（render-*=spike2，tiles-*=spike2t，baseline-p0-*=P0 WebView）。每 run 三件：.log（合并后正式输入）、.log.struct（RUNENV/AMSTART/RUNMETA）、.log.rt（原始 logcat 流）。作废清单：raw/INVALID.md。
- 解析：python scripts/android/spike2/parse.py <raw.log ...> --drop-mode first-per-phase --min-samples 5 → 同名 .csv/.stats.json/.md；run-app.sh 跑完自动调用。
- 安全序列：build.sh → run-render.sh --scenarios S10 --reps 1 --screenshot --tag s10-shot --no-parse（45–60s）→ 解析/看图。

## 8. 关键实测数字（摘要）

| 指标 | 值 | 出处 |
|---|---|---|
| S1 open | seed-03（4 页，594×841pt）：152.6–264.3ms（5 reps）；realbook（60 页）：324.2–665.4ms | 正式集 render-r1（raw/render-r1-*） |
| S2 整页 render（1188×1682, warm） | 各页差异大（page0 明显高于 page1–3）；**以正式集 render-r1 的 csv/data.md 为准，本文不复制**；首帧含 PDFium 初始化 800ms–1.4s | 正式集 render-r1 / results/csv |
| S9 PNG 编码+落盘 | spike2 同批 S9 见 results；P0 基线占单页 40–50%（259–445ms/页） | P0 报告 / S9 |
| S8 HARDWARE | 不可用（silent-fail，零像素）；正式集 render-r3：verdict:silent-fail, painted:0, silent:5, throw:0, changed:0 | research §6b |
| S10 Surface 整页直写 | 可跑通+视觉验证上屏；1080×1529 warm 中位 245ms（p90 384）；只作首屏打底 | research §6b |
| S6 dpi（整页，warm n=4/档） | 72dpi 594×841：161.9ms（bytes 1,998,216）；130dpi 1073×1518：389.0ms（6,515,256）；200dpi 1650×2336：507.2ms（15,417,600）。像素 ×7.72 / 耗时 ×3.13 ⇒ 固定开销≈110ms/页+103ms/Mpx；**默认 dpi 应按视口宽动态（1080px 屏→130dpi），72dpi 只配缩略图** | 正式集 render-r3（research §6b） |

## 9. 变更记录

- 2026-11-01 18:16 工程骨架（Lead 搭）。
- 2026-11-01 19:32 协议语义冻结（场景编号/phase/extra 不再改）。
- 2026-11-01 19:43 S8/S10 测量加固（CRC/px8/changed + S10 两档 drawSize/PixelCopy），APK b844a404。
- 2026-11-01 20:22 S8 verdict v3（Lead），APK 8ea3edc6（正式集冻结版）。
- 2026-11-01 20:36 S10 --screenshot 视觉验证通过（中文正文可辨），证据 evidence/s10-shot-20261101-203630-*.png。
