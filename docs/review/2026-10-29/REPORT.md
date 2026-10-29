# ParticleBook 全面 review（2026-10-29）

> 方式：Agent Team 5 路并行只读评审（前端 / C++ 后端 / 桥接·构建·CI / 安全 / UI·i18n·可访问性），
> 每路独立阅读代码、给 `file:line` 与证据片段；Lead 对关键 P0/P1 做了独立复核。
> 基线：`1feb175`（工作区干净）。全程只读，未构建、未运行应用、未改源码。
> 明细报告：`frontend.md` · `backend.md` · `security.md` · `bridge-build.md` · `ux-i18n.md`（同目录）

## 结论

**共 57 条：1×P0、15×P1、41×P2。**

整体质量比预期好：类型检查、i18n（585 个 key 零缺失）、契约三方一致性（117↔117）、E2E 18 步无假通过、
密钥不进设置/日志/备份、EPUB 沙箱无 `allow-scripts` —— 这些都有独立复核结论。
问题集中在三类：**不可信输入的解析/写回**（ZIP、备份、镜像名单）、**长任务同步跑在 UI 线程**（封面、镜像探测、书源 HTTP 无超时）、**弹层/列表的可访问性与误操作防护**。

## 最该先修的 10 条（跨域排序）

| # | 来源 | 严重度 | 位置 | 问题 | 影响 |
|---|------|--------|------|------|------|
| 1 | B-01 | **P0** | `utils/ZipReader.cpp:112-124` | 中央目录遍历只判 `pos < cdBuf.size()`，随后把 `data()+pos` 当 46B 结构体读、按 `nameLen`(≤64KB) 拷文件名 | 畸形 EPUB/DOCX（Z-Library 下载的书 = 不可信输入）导入时堆越界读 → 整进程崩溃 |
| 2 | B-02 | P1 | `services/BookSourceHttp.cpp:59`、`BookSourceContent.cpp:191` | 从不 `WinHttpSetTimeouts`（resolve 默认无限）+ `while(completed<total) Sleep(100)` 忙等 | 一条章节卡住 → 下载永久挂起、2~4 个工作线程占死、退出 join 卡住 |
| 3 | B-03 | P1 | `services/BackupService.cpp:1259-1267`（校验 :398-419） | 恢复备份把清单里的 `sourcePath` 当绝对路径直接写，只挡 `..`/UNC，默认开启 | 伪造「备份」zip = 任意目录写任意字节（含 Startup）= 持久化 |
| 4 | SEC-01 | P1 | `ZLibraryMirror.cpp:321/87/218` + `WebViewHostWebView.cpp:81-83` | 镜像名单来自第三方导航页 HTML，不校验 host 与 Z-Library 关系；会话期证书错误对任意主机 ALWAYS_ALLOW | 导航页被投毒 → 用户被送到自签证书的伪登录页（账号密码） |
| 5 | F-02 | P1 | `TextRenderer.tsx:82-97,185-260` | TXT 搜索命中无上限，每个命中一个 `<mark>` | 搜常用字直接构造数万节点 → 界面假死数秒~十几秒 |
| 6 | F-03 | P1 | `PdfRenderer.tsx:254-264` | 翻页器用闭包旧 `visiblePage` + `scrollIntoView` 同步返回 | PDF 快速连点只前进 1 页（翻页队列对 PDF 完全失效） |
| 7 | F-04 | P1 | `EpubRenderer.tsx:352-378` | 重画高亮只按「当前列表」remove，已删项永不摘除 | 删除高亮后正文仍显示，重开书才消失 → 用户以为删失败 |
| 8 | B-04 | P1 | `FileHandlers.cpp:230-305` + `LibraryService.cpp:276-286` | `book:cover` 同步跑在 UI 线程；无封面时现场 spawn mutool 等 30s，结果不写回 DB | 书架每渲染一次为每本无封面 PDF 重跑一遍子进程 → 窗口未响应 |
| 9 | F1 | P1 | `scripts/installer.nsi:135-137` | 安装包只带 ParticleBook/mutool/WebView2Loader，**漏了 mobitool.exe**（CMake install 有、NSIS 没有） | 安装版/更新版的 AZW3·KF8 转换失效（E2E 跑 build2 查不出） |
| 10 | U4 | P1 | `Sidebar.tsx:191-198/243-252`、`AnnotationPanel.tsx:415-426` | 删除笔记/高亮/标注一键即删，无确认无撤销（删书、删书柜都有 ConfirmDialog） | 误点一次永久丢失用户手写内容 |

## 分域摘要

### 前端（5×P1 / 6×P2）— `frontend.md`
P1：TXT 搜索无上限（F-02）· 选区同步每次事件整本 `toString()` + 逐码点计数，大 TXT 拖选 30-100ms/次（F-01）· PDF 连点翻页丢步（F-03）· 删除高亮正文残留（F-04）· PDF 文本层逐字符 span + 拖框 mousemove 全量重渲染（F-05）。
P2：换书无取消/旧内容残留、HTML·MD·FB2·DOCX「高亮」写入成功但正文不渲染、EPUB 搜索索引未就绪永久 0 命中、漫画 blob 并发 revoke、EPUB `ready.then` 无取消无 catch、词典查词竞态。
未发现 P0；书页内容走 DOMPurify、EPUB 无 `allow-scripts`，书内脚本拿不到桥接。

### C++ 后端（1×P0 / 4×P1 / 8×P2）— `backend.md`
上述 B-01（P0）、B-02/B-03/B-04；另有 `zlib:fetchMirrors` 仍同步跑 UI 线程（B-05）、ZIP 按包内字段分配最大 4GB（B-06）、`_pb_pdf` 页面缓存无清理（B-07）、`WM_ZLIB_*` 堆指针在回调缺失时泄漏（B-08）等。
已核对无问题：JobService/UiDispatcher 线程契约、原子写 + 迁移备份、WebView2 源判定、zlib 下载 `.part` + 四道校验、单实例与关停顺序、SapiTts COM/线程规约。

### 安全（3×P1 / 7×P2）— `security.md`
SEC-01（镜像/证书）、SEC-02（raw postMessage 只挡 Unknown：任何 http(s) 页面可发 `zlibSwitchTo`/`zlibLogout`/`zlibClose`）、SEC-03（`EmitEvent` 不按文档来源过滤，会话期把 `app:openFiles` 的本地路径投给不可信镜像页）。
P2：更新包 sha512 由渲染层传入且空串/“dummy” 跳过、`_pb_files` 与 app 同源 + 虚拟主机 ALLOW、`file:read` 接受任意绝对路径、证书放行按会话而非按域、生产构建开 DevTools、翻译允许 `http://` base URL、被拒 invoke 不回包。
已核对无问题：来源校验 fail-closed、EPUB 无脚本、注入脚本顶层守卫、DOMPurify 全覆盖、密钥不进设置/日志/备份、下载文件名净化。

### 桥接 · 构建 · CI（1×P1 / 9×P2）— `bridge-build.md`
F1 安装包漏 mobitool（见上）；F2 installer renderer 白名单漏 `app-icon.png` → 安装版「关于」页破图；F3 `check:changelog` 从未进 CI；F4 tag 发布不跑 ctest/bridge-handlers/version-sync；F7 契约参数键名无护栏（`filePath` vs 实际 `path`，检查只比元数所以全绿）；另有 POST_BUILD copy_directory 无 EXISTS 守卫、audit 脚本 Windows 分隔符假绿、package-lock 仍 2.1.0、E2E 固定 80 端口且只在 schedule 跑。
已核对无问题：117↔117 零漂移（独立脚本复算）、d.ts CRLF 归一化、ctest 18 条真断言、E2E 18 步无 skip。

### UI/UX · i18n · 可访问性（4×P1 / 10×P2）— `ux-i18n.md`
P1：删笔记/高亮/标注无确认（U4）· 书籍详情点遮罩丢弃未保存编辑 + 无 Esc/焦点陷阱（U2）· 关键列表行 `div+onClick` 无键盘可达（U3）· `.m3-dialog` 的 `width` 压掉 call site（书源弹层设计 900 实际 560、新建书柜设计 320 实际 560）（U1）。
P2：5 个大弹层缺对话框语义/焦点陷阱、原生 `confirm/alert` 与 M3 ConfirmDialog 并存、书源启用开关两套形态、5 个图标按钮无可访问名、tab 无 ARIA、护眼主题 primary 白字 3.31:1、窄窗口 TTS 语速/睡眠定时消失、reduced-motion 漏内联 transition。
i18n 反向结论：585 个 key 0 缺失、占位符三方 0 错配、288 条 changelog 译文齐全、原生 `select` 已清零。

## Lead 独立复核（不是转述）

以下条目我逐条回读代码确认，非仅采信报告：
- **B-01** `ZipReader.cpp` 循环：确认无边界判断后 `reinterpret_cast<const ZipCentralDirEntry*>(cdBuf.data()+pos)` 并按 `nameLen` 构造 `std::string`。
- **B-02** `BookSourceHttp.cpp` 全文无 `WinHttpSetTimeouts`；`BookSourceContent.cpp` 确认 `while (completed < totalChapters) Sleep(100)`；对比 zlib 侧 `ZLibraryDownload.cpp:443` 已有该调用。
- **B-03** `BackupService.cpp` 确认 `WriteFileAtomicallyUtf8(cover.sourcePath, ...)` 由 zip 清单驱动；`IsSafeAbsoluteCoverPath` 只拒 `..`/UNC。
- **SEC-02/03** `BridgeServer.cpp:74-156` 确认 raw 分支只挡 `Unknown`，`EmitEvent` 无来源过滤。
- **F-02/F-03/F-04/F-07、U1（CSS 层叠）** 均在源码中复核成立。
- **F1/安装包** 确认 `installer.nsi` 的 `File` 清单无 `mobitool.exe`、renderer 只打包 `*.js/*.css`。
- 需修正报告的一处口径：契约里 `readFile` 的键名写 `filePath`，而 JS 存根与 C++ 实际都用 `path` —— **当前运行时正常**，属「契约文档与实际键名漂移」，不是功能 bug（按 P2 处理）。

## 建议修复批次

**批次 1（本周，低风险高收益）**
1. B-01 ZIP 边界校验（3 行，唯一的整进程崩溃路径）
2. B-02 补 `WinHttpSetTimeouts`（照抄 zlib 侧参数）+ 忙等改 `wait_for` 超时
3. F-02 TXT 搜索命中上限（200/500 + 提示）
4. F1 安装包补 `mobitool.exe` + renderer assets 改成按目录打包（含 `app-icon.png`）
5. F4 CI：tag 发布补跑 ctest / bridge-handlers / version-sync；F3 `check:changelog` 进 build.yml

**批次 2（安全与数据面，需要决策）**
6. B-03 默认 `restoreCoversToOriginalPath=false`（或限定在应用数据目录内）
7. SEC-01 登录页 host 白名单 + 证书错误只对白名单放行；SEC-02 raw 类型加来源 allowlist；SEC-03 `EmitEvent` 加 target 门
8. F-03 PDF 翻页目标用 ref 递推；F-04 维护已渲染 CFI 集合做 diff

**批次 3（体验与可访问性）**
9. U4 删除加 ConfirmDialog；U2 `closeOnBackdrop=false` + Esc + 焦点陷阱
10. U1 `.m3-dialog` 宽度降权（`:where()`/`@layer`），恢复各处设计宽度；U3/U5 键盘可达与对话框语义；U10 护眼主题对比度
11. B-04 封面生成移入 JobService 并写回 DB

## 局限

- 全部为静态审查：动态可达性（如 SEC-02 需要 DevTools 注入验证）、GitHub 托管 runner 上 E2E 是否可跑、`install(DIRECTORY genex)` 行为均标注为「存疑」并给了验证方法。
- 未覆盖：`particlebook-cpp/third_party/**` 第三方代码、安装器脚本的执行路径实测、真实大文件（>100MB）与 5000 本库的性能实测。
## 修复落地（2026-10-29，同日完成）

5 条修复线由 Agent Team 并行执行，Lead 负责集成、编译、测试与验收。**57 条全部处理**：54 条已修，3 条按设计口径降级并记录残余风险。

| 域 | 条目 | 状态 |
|---|---|---|
| C++ 数据/IO | B-01（ZIP 越界，**P0**）、B-02、B-03、B-04（含 cover_path 回写）、B-05（删注册）、B-06、B-07、B-08、B-09、B-10、B-11、B-12、B-13 | 全部已修 |
| 安全 | SEC-01（镜像域名族 + 会话 host 放行）、SEC-02（raw 消息来源收紧）、SEC-03（AppOnlyEvents + 对外剥本地路径）、SEC-04（sha512 强制）、SEC-07（证书策略）、SEC-08（Release 关 DevTools）、SEC-09（翻译回环校验）、SEC-10（被拒回包） | 已修 |
| 安全（降级） | SEC-05 `_pb_files` 同源 → 不做独立虚拟主机（会让 `fetch(_pb_url)` 跨域失败），改为**取消顶层文档导航进入 _pb_files** 的纵深防御；SEC-06 `file:read` 保留 UNC 放行（NAS 书库）但加绝对路径/存在性/大小/设备命名空间校验 | 降级 + 记录残余风险 |
| 前端阅读器 | F-01 ~ F-11（选区性能、搜索上限、PDF 翻页、删高亮残留、文本层性能、换书取消、HTML 高亮渲染、索引重算、漫画缓存、epub ready、词典竞态） | 全部已修 |
| 桥接/构建/CI | F1（installer 补 mobitool）、F2（递归打包）、F3/F4（CI 护栏）、F5（EXISTS 守卫）、F6（audit 跨平台）、F7（契约键名对齐 + 6 处漂移）、F8（锁文件版本）、F9（E2E 端口可配）、F10（check-installer） | 全部已修 |
| UI/UX | U1 ~ U14（弹层宽度/对话框语义/键盘可达/删除确认/原生 confirm→M3/开关统一/aria/tabs/对比度/TTS 窄窗/reduced-motion/遮罩 token） | 全部已修 |

### 验收证据（本机实跑）

- `npx tsc --noEmit` 0 error；`i18n:check` MISSING 0；`check:changelog` OK；契约 117↔117 OK、d.ts FRESH、`check-bridge-handlers` OK（108 个注册）、`check-version-sync` OK、`check:installer` OK
- C++ 全量重建（删全部 obj）通过；`ctest` **18/18**
- `node scripts/e2e/smoke.mjs` **18/18 PASS**
- 真机验收（CDP 截图）：更新日志 620px / 新建书柜 320px / 书源弹层 900px 恢复设计宽度（截图 228-231）；EPUB 打开正常且书页内无 Z-Library 工具条（233）；**PDF 连点 3 次翻页 1 → 4 页**（F-03 修复实测，234）
- 更新日志新增 12 条修复条目（中英双语）

### 新增护栏

- `scripts/check-installer-manifest.mjs` + `npm run check:installer`（安装包必需二进制/许可文件/递归打包规则），已接入 build.yml 与 release.yml
- 备份路径策略、ZIP 边界、Z-E 镜像域名族等新增单测/回归断言（`tests/unit/backup_service_test.cpp` 第 23 项等）

### 残余风险（已知、已记录）

1. SEC-05：`_pb_files` 与 app 同源，子资源仍可被同源脚本读取；彻底方案需独立虚拟主机 + CORS。
2. 镜像域名族是**模式白名单**，挡不住仿冒注册域（`zlib-lookalike.com`）。
3. `file:read` 放行 UNC 是刻意取舍（NAS 书库可用性 > 该路径的有限收益）。
