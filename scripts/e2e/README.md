# ParticleBook E2E smoke（PB-018）

Windows 上的**真实应用**关键路径 E2E：启动 C++ + WebView2 的可执行文件，用 WebView2
自带的 CDP（Chrome DevTools Protocol）驱动真实窗口（真实 DOM / 真实桥接调用 / 真实
输入事件），跑 10 步 smoke（5 条基础链路 + PB-018-B 追加的 TTS / 备份 / 翻译 /
schema）。不联网、不污染真实书库。

脚本零第三方依赖：只使用 Node 22+ 全局 `fetch` / `WebSocket`，fixture EPUB 由脚本
自己生成（store-only ZIP 写在 `lib/fixtures.mjs`）。

## 前置条件

- Windows 10/11，已安装 **WebView2 Runtime**（Win11 自带；Win10 一般随 Edge 安装）。
- **Node.js 22+**（全局 `WebSocket`；CI 使用 Node 22）。
- 应用已构建：`ParticleBook.exe` 和它旁边的 `renderer/index.html` 都存在。
  ```sh
  npm run build          # vite -> out/renderer
  npm run rebuild:cpp    # 或按仓库约定 cmake/nmake，POST_BUILD 会拷 renderer
  ```
- **当前没有正在运行的 `ParticleBook.exe`**。应用用会话级单实例互斥体；如果真实实例
  在跑，测试进程只会把 fixture 路径**转发给真实实例**（会污染真实书库）。脚本启动前
  会检测并报错；`--allow-running` 仅在你明确知道后果时使用。
- 不要用 `npm start` / `npm run dev` 启动（仓库红线）；直接跑下面的 node 命令。

## 运行

```sh
node scripts/e2e/smoke.mjs --app particlebook-cpp/build2/ParticleBook.exe
```

常用参数：

| 参数 | 说明 |
|---|---|
| `--app <path>` | 可执行文件；默认 `particlebook-cpp/build2/ParticleBook.exe` |
| `--artifacts <dir>` | 截图 / app 日志 / report.json 输出目录；默认 `%TEMP%\particlebook-e2e-artifacts\<runId>` |
| `--port <n>` | 固定 CDP 端口；默认自动挑空闲端口 |
| `--timeout <ms>` | 每个等待步骤的超时，默认 120000（冷启动 WebView2 可能较慢） |
| `--keep` | 成功也保留沙箱，便于事后检查 |
| `--fail-at <step>` | 故意在指定步骤失败（`launch-and-cdp` / `bridge-read` / `import-epub` / `open-read-turn-close` / `settings-forward-exit` / `extended-app` / `tts` / `backup` / `translation` / `schema`），用来验证失败截图 / 退出码 |
| `--allow-running` | 跳过「已有实例」检查（危险，见上） |
| `--verbose` | 打印更多中间信息 |
| `-h, --help` | 用法 |

退出码：`0` = 全部通过；`1` = 某条 smoke 失败；`2` = 参数 / 平台 / 前置条件错误。

## Smoke 步骤（5 条基础 + PB-018-B 追加 5 步）

1. **launch-and-cdp**：进程启动 → CDP 端口就绪 → `document.title` 变成 ParticleBook
   → `window.electronAPI` 注入成功 → 沙箱隔离自检（WebView2 user data 必须在临时
   profile 下，否则直接失败，避免污染真实数据）。
2. **bridge-read**：调 `getPendingOpenFiles()`（干净启动应为 `[]`）、`getBooks()`
   （空书架）、`getSettings()`（对象；theme / fontSize 存在时校验类型）。
3. **import-epub**：脚本生成一个最小 EPUB 2.0（mimetype + container.xml + OPF + NCX +
   2 章 XHTML）→ `importBooks([path])` → 返回书本对象 → `getBooks()` 含该书 →
   书架 DOM 真的刷新出标题（覆盖「桥接成功但 UI 没刷新」）。
4. **open-read-turn-close**：CDP 鼠标点击书卡 → epubjs iframe 里出现第一章正文 →
   阅读进度 cfi 落盘（waitFor `getReadingProgress`）→ `ArrowRight` 翻页到第二章 →
   进度 cfi 变化 → `Escape` 返回书架 → 进度仍在。
5. **settings-forward-exit**：`updateSettings({theme:'light'})` 往返 → 第二个进程带
   第二个 fixture 启动（单实例转发，PB-019）→ 主实例导入并自动打开该书 → Esc 回书架 →
   `taskkill`（WM_CLOSE）优雅退出（不能用强杀伪装成功）→ 核对磁盘
   `reader.json`：两本书都在、`settings.theme === 'light'`、阅读进度 cfi 非空。

以下 5 步是 PB-018-B 追加（旧 5 条全部通过后才会执行；会额外重启应用）：

6. **extended-app**：旧 5 条结束时主实例已优雅退出；在同一沙箱上重启应用，断言
   `getBooks()` 仍是 2 本、`settings.theme` 仍是 `light`（持久化 + 重启加载），
   并为后 4 类 smoke 提供运行中的实例。
7. **tts**：`getTtsVoices()` 非空 → `ttsSetVoice(first)` → `ttsSetVolume(0)`（静音，
   不会真的出声）→ `ttsSpeak(短句)` 返回 utterance id → 轮询 `ttsStatus().speaking`
   变 true → `ttsStop()` → `speaking` 变 false 且 volume 仍为 0 → 恢复 volume=100。
8. **backup**：`createBackup()` 返回 `{ok,path,bookCount,bytes}`，path 必须在沙箱
   `.../particle-book/backups/` 下且文件真实存在；`restoreBackup(path)` 返回
   `{ok,bookCount,preRestorePath}`，pre-restore 文件存在且文件名含 `pre-restore`；
   恢复后 `getBooks()` 仍为 2 本（DB 已重新加载）。
9. **translation**：`translationHasKey()` 初始 false → `translationSetKey(临时假 Key)`
   → true → `translationClearKey()` → false；断言假 Key 不在 `reader.json`，
   也不以明文出现在 `%LOCALAPPDATA%\ParticleBook\secrets.dat`（DPAPI 密文）。
   **不调用 `translationTranslate`**，不联网。
10. **schema**：当前沙箱 `reader.json` 的 `schema_version === 3`；随后关闭扩展实例，
    在另一个临时沙箱里手写一个 v2 `reader.json`，启动应用后断言迁移到 v3、出现
    `annotations` 数组、并留有 `reader.json.pre-migrate-v2-<ts>` 备份（备份内容仍是 v2）。

失败时：CDP 截图（`failure-<step>.png`）、页面 console（`page-console.log`）、
应用 stdout/stderr（`app.log`）、`reader.json` 快照（`failure-reader.json`）、
`report.json`，退出码 1；沙箱默认保留并打印路径（成功时默认删除）。

## 数据隔离（为什么不污染真实书库）

App 的数据目录由 `SHGetFolderPathW(CSIDL_APPDATA)` 决定。实测**只改 `APPDATA` 环境
变量无效**；必须把 `USERPROFILE` 指到临时目录，并预先建出 Windows 认识的 profile
形状（`<user>\AppData\Roaming`、`<user>\AppData\Local`），这样：
`SHGetFolderPathW` 才会返回临时路径。因此脚本为每个 run 建沙箱并设置：

- `USERPROFILE` / `APPDATA` / `LOCALAPPDATA` / `TEMP` / `TMP` / `HOMEDRIVE` / `HOMEPATH`
- `WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=--remote-debugging-port=<port>`（WebView2 官方
  支持的浏览器参数，不需要改 C++）

于是 DB（`.../particle-book/data/reader.json`）、WebView2 user data、SecretStore、
日志全部落在沙箱里；`assertIsolation()` 会检查 `EBWebView` 目录是否在沙箱内，作为
「隔离真的生效」的硬断言。

副作用（不可避免、已尽量收敛）：

- 应用会把 `DebugLog` 追加到 **exe 旁边的 `debug.log`**（`book:import` 路径）——构建
  目录文件，gitignore，不影响仓库；每次 E2E 会追加几行。
- 打开书时 `file:read` 会把 fixture 复制到 `build2/renderer/_pb_files/<fnv1a64(path)>`
  （应用缓存）。脚本结束后**只删除本次 fixture 对应的那两个哈希文件**，不碰既有缓存。
- 无封面 EPUB 在书架卡片上会把文件头当图片显示（fixture 没有封面；真实书带封面时正常）。

## CI

`.github/workflows/build.yml` 末尾追加了独立 job `e2e-smoke`：

- 只在 **workflow_dispatch（手动）** 或 **schedule（每周一 03:17 UTC）** 运行，
  不挂在 push / PR 上，不阻塞日常 build。
- 自己 checkout / npm ci / `npm run build` / MSVC / cmake build，然后
  `node scripts/e2e/smoke.mjs --app particlebook-cpp/build2/ParticleBook.exe --artifacts e2e-artifacts`，
  最后 `actions/upload-artifact` 上传截图 / 日志 / report.json（always）。
- 注意：手动 / 定时触发时现有的 `build-windows` job 也会按 `on:` 的语义跑一遍（重复构建，
  但互不阻塞）；如果以后觉得浪费，可以给 build-windows 加 `if:` 只跑 push/PR —— 本任务
  只追加 E2E 步骤，没动既有 job。
- Runner 需要 WebView2 Runtime（windows-latest 一般随 Edge 自带）和可用的交互式桌面
  会话（WebView2 是 GUI 程序）。如果初始化失败，smoke 会在 launch 步骤超时，report.json /
  app.log 里有原始原因；该 job 只在手动 / 定时运行，即使 runner 环境不允许 GUI 也不会影响
  push / PR 的日常构建。
- **TTS 步骤需要 runner 装了 SAPI 语音且有可用音频端点**（GitHub 托管 runner 不一定有）。
  没有语音会失败在 `getTtsVoices() 非空`，没有音频设备可能失败在 `speak/status.speaking`；
  CI 日志会明确报错。若该环境长期缺语音/音频，可把 TTS 步骤改成环境探测后 skip（当前按
  PB-018-B 要求保留硬断言）。

## 当前不覆盖（后续 PB-018 增量）

- PDF / CBZ / TXT / FB2 / Markdown 阅读链路（本 fixture 用 EPUB；PDF 需要 mutool 渲染，
  判定文本更麻烦）
- Z-Library 进站/下载、词典、导出
- 设置页 / 阅读设置的真实 UI 交互、i18n 文案、主题切换
- 多窗口 / DPI / 窗口位置、崩溃重启、断网/代理
- 真实第三方服务（AI provider / Z-Library 镜像）
- **TTS**：只验证 bridge 方法与合成/播放状态机（音量 0 静音），不验证音频真的从扬声器
  出来，也不测语速/音色切换/暂停恢复
- **翻译**：只验证 Key 的 SecretStore 存取（不调真实 provider、不测错误码/翻译结果）
- **备份**：只验证 create/restore 同一库 + pre-restore 文件；不验证跨机恢复、损坏包、
  ZIP 完整性/大库
- **schema**：只验证 v2→v3 与 pre-migrate 备份；不验证 v1→v3、未来版本拒绝降级、损坏库

## 已知 / 待修（E2E 发现，不阻塞 smoke）

- 注入桥接脚本里 `window.__refreshLibrary` 只在桥接响应到达时判了一次真，50ms 后再调用；
  如果这期间 Library 已卸载（例如导入后立刻打开阅读器），会抛
  `TypeError: window.__refreshLibrary is not a function`。非致命（仅控制台 error）。
  建议 BridgeServer 在 setTimeout 回调里再判一次 `typeof === 'function'`。
- 书架卡片无封面 fallback 会把文件头当图片渲染（fixture 无封面导致；观感问题）。
- **同一时间只能跑一个 E2E / 验证实例**：应用是会话级单实例互斥体，另一个 smoke、调试
  脚本或用户手动打开的 ParticleBook 都会让 preflight 报 "already running"（这是保护，
  不是 bug）。本机开发时会遇到其他 agent 的调试脚本占用 app，等它退出再跑即可。

## 新增 smoke

在 `smoke.mjs` 里加一个 `await step('name', async () => { ... })`：

- 调桥接：`await bridge('getBooks')`（自动 await Promise）；多实例时用
  `await bridgeOn(cdp, 'getBooks')`；
- 等页面：`await state.cdp.waitFor('expression', { timeoutMs, label })`；
- 需要重启 / 换沙箱：`await startAppOnSandbox(sandbox, 'app-xxx.log')`（自带 CDP 连接、
  登记到统一清理列表）；`await closeAppGracefully(app, label)`；
- 等磁盘文件：`waitForFileJson(filePath, predicate, label)`；
- 读写 DOM / iframe：见 `iframeHas()` / `iframeTextExpression()`；
- 断言失败直接 `throw new Error(...)`，框架自动截图（用当前活跃 cdp）/ 写 report / 退出码 1；
- 需要新 fixture 时在 `lib/fixtures.mjs` 里生成（保持 store-only ZIP、无外部依赖）。
## PB-018-C 补充（搜索 / PDF / Z-Library）

smoke 现在是 **13 步**（前 10 步见上；11-13 为 PB-018-C 追加，schema 之后在同一沙箱重开实例）：

11. **search**：书架搜索框过滤 2→1→0→2；再通过原始桥接 invoke 调 PB-006 全文接口
    （渲染层存根暂未暴露 search 成员）：`search:build` 索引 2 本 EPUB → `search:status`
    → `search:query "reader"` 命中第一本、snippet 含关键词。rawBridgeInvoke 走真实
    BridgeServer/JobService，不是 mock。
12. **pdf**：脚本生成 1 页文本 PDF（`/Title` + 唯一 TEXT MARKER，零依赖手写 xref）→
    `pdfOpen`（pageCount=1、pageBounds 有效）→ `pdfExtractText` 含 MARKER →
    `pdfRenderPage` 返回 `/_pb_pdf/` URL 且 PNG 文件存在 → `pdfClose` → `importBooks`
    （format=pdf、title 来自 metadata）→ 点开书卡 → PdfRenderer 挂载 `img[alt="Page 1"]`
    且页码 `1 / 1` → ArrowRight 单页边界仍停在第 1 页 → Esc 回书架。
13. **zlib**：**不联网、不触发真实导航/下载**；验证 `electronAPI` 的 zlib API 面 + 只读
    `zlibGetMirrorInfo` / `zlibGetDownloadPath`（返回 `{path}`）+ 3 个 `onZlib*` 事件可
    退订；report.notes 记录「真实进站/下载 skip（依赖外网）」。

`--fail-at` 新增可选值：`search` / `pdf` / `zlib`。

副作用与依赖：PDF 渲染页由 PdfService 写到
`build2/renderer/_pb_pdf/doc_<id>_page_<n>.png`；smoke 开始前快照该目录、结束只删本次
新增 PNG（不碰既有缓存）；`_pb_files` 缓存清理现在含 2 EPUB + 1 PDF。PDF 步骤依赖 exe
旁边的 `mutool.exe`（CMake POST_BUILD 已拷）；Z-Library 步骤完全离线。

未覆盖：CBZ/TXT/FB2/Markdown 阅读；PDF 多页/大文件/扫描版；跨书全文搜索的**渲染层 UI**
（当前直接调 bridge handler）；Z-Library 真实进站、登录、下载、镜像切换（外网依赖）。
## PB-018-D 补充（翻译 mock / Z-Library 受控 mock）

smoke 现在是 **15 步**（14/15 为 PB-018-D 追加；前 13 步见上）：

14. **translation-mock**：smoke 进程内起 127.0.0.1 随机端口的 OpenAI 兼容 HTTP mock
    （`lib/mock-provider.mjs`，零依赖 http server）。通过真实 settings 通道写
    `translation.enabled/baseUrl/model`、SecretStore 写临时 Key → `translationTranslate`
    （真实 C++ → WinHTTP → 本机 mock 全链路）→ 断言返回 `MOCKED: ...`、requestsSent /
    segmentsTranslated、mock 实收 Authorization/model/targetLang/正文；mock 返回 429 →
    bridge reject `quota`；结束清 Key + 重置 settings。全程不联网。
15. **zlib-mock**：通过 `chrome.webview.dispatchEvent` 注入合成 bridge event（不联网、不真实下载）：
    `zlib:downloadError` → 断言红色 banner + HTTP 503 文案；`zlib:allMirrorsFailed` → 断言
    镜像横幅；`zlib:downloadProgress` / `zlib:importComplete` / `library:changed` → 断言无
    page exception 且 bridge 仍可用（前两个事件目前没有渲染层消费者，report.notes 已注明）。
    若当前 WebView2 运行时不允许 `dispatchEvent`，该步 skip 并写 report note（不判失败）。

`--fail-at` 新增可选值：`translation-mock` / `zlib-mock`。

未覆盖：真实第三方 provider（翻译 mock 只验证 OpenAI 兼容协议与错误映射）；Z-Library
真实进站/登录/下载/镜像切换（受控事件 mock 只验证渲染层事件消费与容错）；跨书全文搜索 UI。
