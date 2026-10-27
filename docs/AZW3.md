# PB-008-C：AZW3 / KF8 可行性 + 集成方案

> 任务：task-53 / PB-008-C。日期：2026-10-27。分支 main（未 commit / 未 push）。
> 本轮只写 docs/AZW3.md 与 scripts/azw3/**，**未改** ReaderView / FileHandlers / CMakeLists；集成代码留待方案确认后做。

## 结论（TL;DR）

- **可行**：libmobi 0.12（LGPL-3.0-or-later）的 mobitool.exe 能正确解析示例 KF8（Mobi version 8）：
  <code>-e</code> 直接转出 **681,999 字节 EPUB**，<code>-d</code> 转出 **4,984 字节 rawml HTML（2,812 字符正文）**；
  转出的 EPUB 在真实 ParticleBook 里以 epub 导入并正常渲染（见 §4）。
- **MSVC 可构建**：无 zlib / libxml2 / OpenSSL 外部依赖；启用内置 miniz + 内置 xmlwriter + /MT，
  产出 **385,024 字节** 单文件 exe，dumpbin 显示只依赖 **KERNEL32.dll**（与主程序一致，不需要 VC 运行库）。
- **现有 MuPDF 路径确实不行**：示例 AZW3 直接给 mutool 报 cannot find document handler；
  改名 .mobi 后返回 0 字节（只有 kindle:flow:... 样式表 warning），与现象一致。
- **推荐集成方式**：像 mutool 一样把 mobitool.exe 作为独立工具随程序分发；导入 .azw3/.azw 时调用
  <code>mobitool -e</code> 转成 EPUB，缓存后交给现有 EPUB 渲染管线（**不改 ReaderView**）。文本兜底可用 <code>-d</code>。
- 许可证是主要决策点：LGPLv3 要求随二进制提供对应源码/许可证/构建方式（本仓库提供固定 commit 的构建脚本），
  推荐把 libmobi 源码按固定 commit vendor 进仓库或构建时拉取，并在“关于/许可”里加 LGPL 声明。

## 1. 现状与样本

| 项 | 值 |
|---|---|
| 样本 | particlebook-cpp/build2/.issue-tmp/example.azw3（build 目录，gitignore，不在仓库里） |
| 大小 / SHA-256 | 694,801 B / 53ab77f6723cb286d35b539c5543b6da064e74d6d014a8fef8fea06684c5b674 |
| file | Mobipocket E-book "example", 4984 bytes uncompressed, version 8, codepage 65001 |
| 元数据 | title example，author Online-Convert.com，calibre 2.4.0，KF8（version 8） |
| 现有实现 | FileFormatService::ConvertMobiToText()：mutool convert -F text（先把 .azw3 当 MOBI 处理） |
| 实测 MuPDF | 直接 .azw3：unsupported error: cannot find document handler；改名 .mobi：输出 **0 字节**，仅 kindle:flow:0001/0002 样式表 warning |
| 导入行为 | FileImportService 的 AZW3 探针转不出文字就 recordError("azw3 text unavailable")，不入库（PB-008-A 的保护逻辑） |

原因：KF8 的 flow/resource 打包（kindle:flow:*、kindle:embed:*）不是 MOBI7 的线性文本，
MuPDF 1.25 的 MOBI 解析器只覆盖一部分，正文抽取为空。继续在 MuPDF 上补 KF8 解析成本高且不在当前依赖内。

## 2. 候选方案对比

| 方案 | 许可 | 语言/依赖 | 体积/构建 | 结论 |
|---|---|---|---|---|
| **libmobi + mobitool** | **LGPL-3.0-or-later** | C99，CMake；无外部依赖可构建 | 源码 ~7.5 MB（22 个 .c + 21 个 .h）；exe 385 KB | **推荐**：KF8/KF7、EPUB/rawml/封面导出、MSVC 已实测 |
| KindleUnpack | GPLv3 | Python | 需 Python 运行时；GPL 传染 | 拒绝（许可 + 运行时） |
| calibre ebook-convert | GPLv3 | Python/Qt，数百 MB | 体积/许可都不合适 | 拒绝 |
| 自研 KF8 解析 | 自有 | C++ | 工作量大、易踩边界 | 不划算；可作为长期备选 |
| foliate-js | AGPL | JS | 与原生/许可都不合 | 拒绝 |

libmobi 自带工具：mobitool（元数据、-d rawml、-s 重建源文件、-e EPUB、-c 封面、-t 拆分 hybrid、-7 解析 KF7 部分）、mobimeta、mobidrm。DRM 文件不支持（会报错，符合预期）。

## 3. MSVC 构建验证（已跑通）

固定版本：libmobi **0.12**，commit 906274205c11944b628da1c553b255acb1af7c55。

### 3.1 两个 Windows 坑（脚本已处理）

1. tools/CMakeLists.txt 没把 tools/win32/getopt.c 加入编译 → LNK2019 getopt/optind/optarg...。
2. 顶层在 USE_ZLIB=OFF 时只 add_definitions(-DUSE_MINIZ)，没有 set(USE_MINIZ ON)，
   src/CMakeLists.txt 的 if(USE_MINIZ) 不成立 → miniz.c 不编译 → 链接缺符号。脚本显式传 -DUSE_MINIZ=ON。

### 3.2 可复现脚本

~~~powershell
pwsh -File scripts/azw3/build-mobitool.ps1
# 可选：-SourceDir <libmobi 源码> -BuildDir <构建目录> -OutDir <输出目录> -SkipClone
~~~

脚本流程：clone/fetch 固定 commit → 补 win32/getopt.c → CMake 配置（无外部依赖）→ 构建 mobitool →
拷贝 mobitool.exe + COPYING 到输出目录，打印 SHA-256。关键 CMake 参数：

~~~text
-DUSE_ENCRYPTION=OFF -DUSE_LIBXML2=OFF -DUSE_ZLIB=OFF -DUSE_MINIZ=ON
-DUSE_XMLWRITER=ON -DTOOLS_STATIC=ON -DBUILD_SHARED_LIBS=OFF
-DCMAKE_C_FLAGS_RELEASE="/MT /O2 /DNDEBUG"
~~~

### 3.3 构建产物（实测）

| 项 | 值 |
|---|---|
| exe | mobitool.exe，**385,024 字节** |
| SHA-256 | 0cbe65379d1fd0570680b3401690ecedc08216c1196a0ffd235ddb8cdf3bacd6 |
| 依赖 | **KERNEL32.dll**（dumpbin /dependents；/MT 静态 CRT，无 VCRUNTIME140/无 UCRT 转发 DLL 依赖） |
| 编译告警 | 只有 C4996（asctime/strerror/strcpy 等 MSVC 弃用告警）；上游 MinGW 的 -Wl,--allow-multiple-definition 在 MSVC 下报 LNK4044 并忽略，不影响 |
| 构建耗时 | CMake 配置约 15–22 s；构建约 15–30 s（本机 VS2022 17.14） |

> 说明：-e（EPUB）需要 USE_XMLWRITER=ON；初期只开文本模式的构建里 -e 会打印 usage。
> 本方案启用 xmlwriter + 内置 miniz，实测 MSVC 链接无冲突。

## 4. 转换结果（示例 KF8）

| 命令 | 结果 |
|---|---|
| mobitool.exe example.azw3 | 正确元数据：Title example、Author Online-Convert.com、Mobi version 8、calibre 2.4.0 |
| mobitool.exe -d -o out example.azw3 | out/example.rawml，**4,984 B / 2,812 字符正文**（HTML，含标题、John Doe 示例段落） |
| mobitool.exe -s -o out example.azw3 | out/example_markup/：part00000.html（4,294 B）、2 个 CSS、PNG/JPG/GIF 资源 |
| mobitool.exe -c -o out example.azw3 | out/example_cover.jpg，**549,132 B** |
| mobitool.exe -e -o out example.azw3 | out/example.epub，**681,999 B**，13 个 zip entry（mimetype、container.xml、part00000.html、2 CSS、6 图片、content.opf、resource00006.ncx） |
| EPUB 正文 | 去标签后 **2,679 字符**；OPF 含 dc:title=example、dc:creator=Online-Convert.com、meta name="cover" content="resource00002" |
| 耗时（-e，3 次） | 67.4 / 52.1 / 48.2 ms（Windows 本机，含进程启动与写盘） |
| 重复运行 | 同一输出目录覆盖已有 example.epub，退出码 0 |
| 损坏输入 | 2 KB 随机数据 → 退出码 1，Error while loading document (Unsupported document format) |

### 4.1 真实应用程序验证（不改代码）

用现有 E2E 沙箱启动 ParticleBook.exe，把转出的 example.epub 通过 window.electronAPI.importBooks() 导入：

~~~json
{"imported":[{"id":1,"title":"example","format":"epub"}]}
~~~

点开书后 iframe 渲染出：

~~~text
AZW3 test file

Purpose: Provide example of this file type

Document file type: AZW3
...
~~~

结论：**libmobi 转出的 EPUB 与现有 EPUB 渲染管线兼容**（文本验证通过；封面/目录见 §6 风险）。
截图：%TEMP%\azw3-converted-reader.png（临时文件，未入库）。

## 5. 集成方案（建议）

### 5.1 推荐路径：独立 mobitool.exe + 预转 EPUB

保持与 mutool 同样的“外部工具”模式，进程隔离、无 ReaderView 改动：

1. **构建**：把 libmobi 固定 commit 源码 vendor 到 third_party/libmobi（或构建时按 commit 拉取），
   用 scripts/azw3/build-mobitool.ps1 的逻辑做成 CMake custom target，把 mobitool.exe 复制到
   ParticleBook.exe 同目录（与 mutool.exe 同级）。CI/离线构建需带源码或缓存。
2. **导入**（FileFormatService 新增 ConvertAzw3ToEpub()，FileImportService 在 format == "azw3" 时调用）：
   - 建临时目录（%TEMP%\pb-azw3-<rand>），CreateProcessW 隐藏窗口执行
     mobitool.exe -e -o <tmp> <input.azw3>，30 s 超时，超时 TerminateProcess；
   - 校验退出码 0 且 <tmp>/<base>.epub 存在、大小 > 0；
   - 把 EPUB 移到数据目录 <data>/converted/<fnv1a64>.epub，original_path 保留原 azw3；
   - 清理临时目录；失败时回退到现有 ConvertMobiToText() 文本探针，仍为空则按 PB-008-A 记录
     azw3 text unavailable (DRM or unsupported)。
3. **格式/渲染**：转换后建议直接把书按 format="epub" 入库（现有 EPUB 渲染器直接可读，已验证）；
   若必须显示“AZW3”标签，后续可在 DB 增加 source_format/展示层字段，不在本次范围。
4. **封面**：优先用 mobitool -c 导出的 jpg（或转出 EPUB 的 meta name="cover"），写 cover_path；
   EPUB 的 resource00002.jpg 已是封面候选。
5. **兼容**：.mobi 继续走 mutool（回归面最小）；.azw3/.azw 先试 libmobi，失败再回退 mutool。
   hybrid KF7/KF8 默认解析 KF8；需要时用 -7 解析 KF7 部分，-t 拆分。
6. **测试**：新增 ~695 KB 的 KF8 测试样本书（许可允许的示例或自制），
   单测覆盖正常/损坏/超时/DRM；E2E 增加“导入 AZW3 → 书架出现 → 打开正文非空 → 封面”步骤。

### 5.2 备选：libmobi 作为静态/动态库

- 少一次进程启动，但需要 C/C++ 封装层、异常/内存边界、线程安全自查；
- LGPL 静态链接的分发义务更重（要提供可重链接的目标文件），不建议第一版走这条；
- 若选动态库：BUILD_SHARED_LIBS=ON 产出 libmobi.dll + 工具动态链接，用户可替换库，LGPL 更易满足。

## 6. 风险与未决

| 风险 | 说明 / 缓解 |
|---|---|
| **LGPLv3 合规** | 分发 mobitool.exe 必须附 libmobi 对应源码、COPYING、构建方式；本脚本固定 commit 可复现。若静态链接进主程序，需法务确认并准备 relink 材料；第一版建议独立 exe + 源码归档。 |
| DRM / 加密 AZW3 | libmobi 不破解 DRM，会返回错误；按现有逻辑提示“DRM 或格式不支持”，不重试。 |
| 转出 EPUB 的目录 | 示例 resource00006.ncx 的 navMap 为空（单 HTML 文件），EPUB 阅读器可能没有目录；文本/图片正常。后续可校验/补 TOC，或接受无 TOC。 |
| 保真度 | 转出为单 HTML + 资源，CSS 保留；复杂 KF8 版式/字体可能简化；需更多真实样本回归。 |
| 体积 | exe 385 KB（+ 源码归档）；对安装包可接受。 |
| 运行时 | 进程启动 48–67 ms/本（含转 EPUB）；临时文件需清理；杀软可能对新增 exe 误报，需签名/白名单策略。 |
| 路径/编码 | 路径含空格/中文需 CreateProcessW 正确引号；输出文件名取源文件 basename，需碰撞处理。 |
| 测试样本 | 当前样本在 gitignore 的 build2/.issue-tmp，未入库；集成前需放入测试 fixtures（确认可再分发）。 |

## 7. 复现命令与证据

~~~powershell
# 1) 构建（固定 commit 9062742，MSVC，无外部依赖）
pwsh -File scripts/azw3/build-mobitool.ps1
# 输出：<OutDir>\mobitool.exe（385,024 B，sha256 0cbe6537...）+ LIBMOBI-COPYING

# 2) 转换
mobitool.exe -e -o C:\tmp\azw3 C:\path\example.azw3
mobitool.exe -d -o C:\tmp\azw3 C:\path\example.azw3   # rawml HTML
mobitool.exe -c -o C:\tmp\azw3 C:\path\example.azw3   # cover

# 3) 现有 MuPDF 对照（0 字节）
copy example.azw3 renamed.mobi
mutool.exe convert -F text -o out.txt renamed.mobi   # out.txt = 0 B
~~~

关键数字（全部来自本机实跑，2026-10-27）：

- libmobi 0.12 / commit 906274205c11944b628da1c553b255acb1af7c55；源码 7.5 MB（不含 .git）。
- mobitool.exe：385,024 B，SHA-256 0cbe65379d1fd0570680b3401690ecedc08216c1196a0ffd235ddb8cdf3bacd6，仅依赖 KERNEL32.dll。
- 示例 AZW3 → EPUB：681,999 B，13 entries，正文 2,679 字符；-e 耗时 48–67 ms。
- -d rawml：4,984 B / 2,812 字符；-c 封面：549,132 B；损坏输入退出码 1。
- 真实 app：importBooks 返回 format="epub"，EPUB iframe 正文渲染通过。

## 8. 建议的下一步（PB-008 落地）

1. 产品/法务确认分发方式（独立 exe + vendored 源码 / 动态库），确定是否接受 LGPL 声明。
2. vendor libmobi 固定 commit + 构建脚本进 CI，产出 mobitool.exe 随程序（构建缓存可离线）。
3. 改 FileFormatService/FileImportService：azw3 预转 EPUB + 缓存 + 封面 + 失败回退；**不改 ReaderView**。
4. 加 KF8 测试样本 + 单测（正常/损坏/超时/DRM）+ E2E 导入/阅读/封面步骤。
5. 用 2–3 个真实 KF8（含目录/图片/多章）验证保真度；决定 EPUB TOC 是否补。
