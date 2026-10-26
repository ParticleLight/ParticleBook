# 架构评审（2.2.1 前置）

> 日期：2026-10-26。范围：C++ 后端 + React/WebView2 渲染层 + 桥接 + 构建/测试。
> 目标：在 PB-001..PB-018 开工前判断哪些结构必须先动，避免 18 个功能各自造一套线程、状态和桥接。

## 0. 结论

现有架构对一个单窗口阅读器是自洽的：服务分层、桥接契约、DB 原子写、来源校验都有明确意识，不是需要推倒重来的项目。

但有 5 个结构性风险，如果按现在的模式继续加 TTS / 翻译 / PDF 标注 / 索引 / 备份，会迅速变成屎山：

1. 长任务跑在 WebView2 UI 线程：导入、文件读取、PDF 渲染、书源搜索都会让窗口「未响应」。
2. 没有单实例：两个进程各写一份 reader.json，后写覆盖先写；PB-012 文件关联会让双击启动变成常态。
3. 后台线程直接碰 WebView2 / 裸指针服务：Z-Library 下载线程直接 EmitEvent，退出时 bridge/db 可能已被 reset。
4. 桥接是四向手工同步：契约表、手写 stub、handler 注册、生成的 d.ts；加 40+ 成员必然漂移。
5. 没有统一的 Locator / Annotation 模型：5 个渲染器各用 cfi / page / offset / scroll 百分比，PDF 矩形和 TTS 文本偏移无处安放。

建议：不重写，先补一层平台层 + 领域模型，再把功能接上去。

## 1. 现状

### C++（约 7.8k 行）

| 模块 | 行数 | 职责 |
|---|---|---|
| App.cpp / App.h | 270 | 单例组合根；服务装配；约 200 行注入 JS 字符串 |
| WebViewHost | 509 | 窗口、WebView2、11 个回调、WndProc、下载/更新/导入消息 |
| BridgeServer | 346 | 方法表、消息分发、事件广播、手写 73 成员 stub |
| DatabaseService | 705 | JSON 库 + 写线程 + 全部实体 CRUD + settings |
| ZLibraryService | 1449 | 镜像策略、探测、导航、下载、导入、设置、UI 事件 |
| BookSourceService | 644 | HTTP、HTML 规则解析、线程池、下载/导入 |
| PdfService | 440 | mutool 子进程、页面渲染、文本提取 |
| FileHandlers | 1010 | 对话框、file:read、book:import、封面、更新、书源导入 |
| DbHandlers | 202 | 书/进度/标注/设置/书柜/会话 handler |
| LibraryService / ContentCache / utils | 约 800 | 元数据提取、文件缓存、编码/压缩/哈希 |

测试：7 个 CTest（utils + database）；无服务级测试。

### Renderer（约 8.5k 行）

| 模块 | 行数 | 备注 |
|---|---|---|
| App.tsx | 224 | 页面切换、Z-Library 遮罩/横幅、设置加载 |
| GlobalSettings | 641 | 外观/阅读/书架/Z-Library/关于 全在一个文件 |
| ReaderView | 458 | 打开流程、键盘、滚轮、阅读会话、侧栏、错误页 |
| EpubRenderer | 590 | epub.js 渲染、CFI、搜索、高亮、进度 |
| TextRenderer / HtmlRenderer / PdfRenderer / ComicRenderer | 396 / 258 / 272 / 226 | 各自实现搜索、进度、滚动/高亮 |
| stores | 约 970 | reader/library/settings/bookSource；直接调 electronAPI |
| globals.css | 623 | 设计 token + 全局组件类 |

Renderer 里 81 处直接 window.electronAPI 调用，没有 service 层。

### 已有护栏（保留，不要推翻）

- bridge/contract.mjs 单一来源 + 生成 electron.d.ts + --check。
- DB 原子写 + 损坏备份；CRUD 有锁。
- Bridge 来源分类（app / external / unknown）+ external allowlist。
- i18n key 检查、版本三处同步、CI 构建 + ctest。
- 测试对 DB/编码/更新元数据等纯逻辑已有覆盖。

## 2. 发现与建议

### P0-1 长任务跑在 WebView2 UI 线程

证据：
- 所有桥接 invoke 从 WebViewHost::OnWebMessageReceived 同步进入 BridgeServer::HandleMessage -> ProcessInvoke -> handler，没有任务队列。
- book:import（FileHandlers.cpp:486）在 handler 内同步做元数据提取、PDF 封面 mutool 渲染、DB 插入。
- file:read（FileHandlers.cpp:384）同步复制文件、MOBI 走 mutool 转文本。
- pdf:open / pdf:renderPage / pdf:extractText（PdfService.cpp:399+）同步跑 mutool 子进程。
- bookSource:search / searchOne / getBookInfo / getChapterList（BookSourceService.cpp:618+）同步 WinHTTP，SearchAll 还是逐个源串行。

影响：大 PDF / 大 MOBI / 多书源搜索时窗口直接卡死；没有进度、不能取消；即将加入的 TTS 合成、翻译请求、索引、备份会复制同样的卡顿。

建议：引入 JobService + UiDispatcher：
- JobService::Submit(spec) -> jobId，worker 池执行，事件 job:progress / job:done / job:error，支持 cancel。
- 长操作 handler 立即返回 { jobId }；渲染层订阅进度。
- 先迁移：book:import、file:read（大文件/MOBI）、pdf:open/render/extractText、bookSource:search、未来 TTS/翻译/备份/索引。

### P0-2 没有单实例

证据：main.cpp 只有 wWinMain；没有命名 mutex / 单实例 IPC；DB 是整个 JSON 文件，两个实例各自持有内存副本并整文件覆盖。

影响：双击文件/快捷方式启动第二个实例会静默丢数据；PB-012 文件关联会放大这个问题。

建议：命名 mutex（Local\ParticleBook.SingleInstance）+ 命名管道或 WM_COPYDATA 把文件路径/命令转给已运行实例；PB-012 之前必须完成。

### P0-3 后台线程直接碰 WebView2 和裸指针

证据：
- Z-Library 下载/导入线程直接调 m_bridge->EmitEvent（ZLibraryService.cpp:714/724/989/1149 等），最终走到 WebViewHost::PostMessageToRenderer -> ICoreWebView2::PostWebMessageAsJson，不是 UI 线程。
- ZLibraryService 持 BridgeServer* / DatabaseService* 裸指针；App::Shutdown 先 reset webview/bridge 再 reset db，而 detached 线程可能还在跑。
- RunMessageLoop 对 DispatchMessage 的异常直接 catch (...) 吞掉。

影响：跨线程调用 COM 属于脆弱/未定义行为；退出或切换页面时可能 UAF / 静默异常。

建议：
- 所有 UI 事件与回调统一经 UiDispatcher::Post(std::function<void()>) 投递到 UI 线程；服务不直接持有 WebView2。
- 服务用 shared_ptr + weak_ptr 生命周期 token，禁止裸 this / 裸 bridge/db。
- Shutdown 顺序：先停 JobService/join 线程，再拆 bridge/webview/db；异常要进日志。

### P0-4 桥接是四向手工同步

证据：
- bridge/contract.mjs 73 成员；BridgeServer.cpp 里手写 73 成员 stub；handler 分散在 4 个文件（71 个 RegisterMethod）；electron.d.ts 生成。
- check-bridge-contract.js 只比较「表 <-> stub」，不校验 handler 是否注册；Z-Library 还有 8 个内部方法不在契约里，走原始 chrome.webview.postMessage 协议。
- 事件订阅 ProcessSubscribe/Unsubscribe 是空实现，事件实际是全局广播。

影响：新增 TTS/翻译/词典/标注/备份/搜索几十个成员后，很容易出现「表里有、stub 有、C++ 没注册 -> 运行时 Method not found」；external allowlist 也要手工维护。

建议（可扩展现有 PB-015）：
- 契约按域拆文件（books / reader / settings / sources / tts / translation / annotations / backup / search），聚合导出。
- 从契约生成 stub，删掉手写大对象；stub 的特殊逻辑（readFile/getFilePath）用显式 override 表。
- handler 按域注册；CI 增加「契约 invoke 方法 <-> RegisterMethod 集合」校验（解析源码或 dev introspection）。
- 契约条目加 exposure: app|external|internal，external allowlist 由契约生成，不再手写。
- 统一错误：新 API 用 { ok:true, data } / { ok:false, code, message }，不要用 null 同时表示空结果和失败。
- 复杂 payload（Locator/Annotation/Settings/TTS config）加必填字段校验。

### P0-5 缺少 Locator / Annotation 模型

证据：
- PbBookmark / PbHighlight / PbNote 用可选 cfi / page；PbProgress 用 cfi / page / scrollPosition（contract.mjs、readerStore.ts）。
- EPUB 用 CFI、TXT 用 pos-<offset>、HTML 用滚动百分比、PDF 用页码；五个渲染器各自做映射。
- 没有表示「PDF 页内矩形」「文本字符区间」的结构。

影响：PB-003 PDF 标注、PB-004 导出、PB-006 跨书搜索、TTS 跟读都要再发明一套定位；以后迁移数据成本更高。

建议：定义统一 Locator（JSON，带 version/kind）：
- { kind:'epub', cfi }
- { kind:'text', offset, length? }
- { kind:'html', anchor, offset? }
- { kind:'pdf', page, rect?: {x,y,w,h}（归一化） }
- { kind:'scroll', percent }

统一 Annotation：id、book_id、kind（bookmark/highlight/note/pdf-annot）、locator、text、color、note、created_at、updated_at；旧字段在 PB-014 迁移时映射。

### P1-1 五个渲染器重复实现阅读能力

EpubRenderer 590 / TextRenderer 396 / HtmlRenderer 258 / PdfRenderer 272 / ComicRenderer 226；每个都实现进度、搜索、滚动/高亮、键盘/滚轮的一部分。TTS、翻译、词典、标注导出如果继续按格式写，就是 5 份逻辑。

建议：定义 ReaderAdapter（imperative handle 或 hook）：
- getLocator() / goTo(locator)
- getSelection()（文本/矩形）
- getTextModel()（TTS/搜索/索引共用）
- search(query)
- applyAnnotation() / removeAnnotation()

共享 SelectionToolbar、SearchController、ProgressController；渲染器只保留格式特有渲染。

### P1-2 God files / 混合职责

- ZLibraryService 1449 行：镜像策略 + 探测 + 导航 + 下载 + 导入 + 设置 + UI 事件。
- FileHandlers 1010 行：对话框、文件读取、导入、封面、更新、书源导入、拖放文件。
- BookSourceService 644 行：HTTP + HTML 解析 + 线程池 + 下载/导入。
- App.cpp 270 行含约 200 行注入 JS（工具栏 + 下载卡片，硬编码深色与中英文字典）。
- WebViewHost 405 行 + 11 个 std::function 回调 + WM_USER+N 消息（ID 在两个 cpp 里重复定义）。
- GlobalSettings 641、ReaderView 458、EpubRenderer 590。

建议（增量，不一次性拆完）：
- 抽 HttpClient（WinHTTP + 超时/代理/取消）、DownloadManager（队列/进度/取消）、ImportService（格式识别/元数据/封面/入库）、UpdateService（检查/下载/安装）、MirrorService（名单/探测/排序）。
- ZLibraryService 变成编排层；BookSourceService 只保留规则解析。
- 注入 JS 移到 resources/zlib_toolbar.js / resources/download_card.js，用 __pbLang + CSS 变量；不要继续塞进 App.cpp 字符串。
- GlobalSettings 按页拆文件；ReaderView 拆 useReaderSession + ReaderShell。

### P1-3 Renderer 没有 service 层

81 处直接 window.electronAPI，分散在 stores / 组件 / App；每个调用点各自 try/catch + console.error。新增 TTS/翻译/词典/备份会继续散。

建议：src/renderer/services/{books,reader,settings,sources,tts,translation,dictionary,backup}.ts 包一层 typed client；stores/components 只调 service；统一错误提示与加载态。不要引入重型数据框架，Zustand 足够。

### P1-4 Settings / Secrets 无 schema

settingsStore 用 SETTINGS_KEYS 字符串数组，C++ 侧存任意 JSON；没有默认值/校验/版本；没有 DPAPI/SecretStore（代码里无 CryptProtectData）。PB-002 的 AI Key 不能进 settings（会被备份/导出/渲染层读到）。

建议：
- SettingsService：typed schema、默认值、校验、全局/书级覆盖、版本迁移。
- SecretStore：DPAPI 加密，只存 provider key；settings 只存 provider id / model / base URL。
- 渲染层由 settings schema 驱动表单；拆页后新设置只加 schema，不加组件逻辑。

### P1-5 DB 扩展性

- WriterThread 持锁写整个 JSON（DatabaseService.cpp:161-183），磁盘 I/O 阻塞所有读；书多/标注多时 UI 卡。
- 单文件 JSON、无 schema/索引；GetBooks 每次复制全量数组。

建议：保留 JSON 存储但加 Repository 接口 + schema；写时「锁内快照、锁外写、代际号」；搜索索引单独存储；5000 本基准（PB-017）。

### P1-6 PDF 文档模型薄

PdfService 用 mutool 子进程逐页渲染，临时目录写在书文件旁边（filePath + ".mutool_tmp"），没有文本层/坐标。PB-003 需要文本层 + 页内矩形，PB-004 要导出，TTS 要文本。

建议：PdfDocumentService 缓存页文本/坐标（mutool draw -F stext 或 MuPDF API），临时目录放 app cache；渲染器只消费统一文档模型。

### P1-7 日志/诊断缺失

DebugLog 只存在于 FileHandlers（OutputDebugString 风格），其他服务无持久日志；RunMessageLoop 静默吞异常。出问题只能靠用户描述。

建议：PB-016 提前到 P0 批次末尾：LogService（滚动文件、级别、脱敏、导出诊断包）+ 统一错误码。

### P1-8 测试缝不足

- CMake 只有单个 exe；测试只能链接 DatabaseService.cpp 这种无 Win32 依赖的 TU。
- 无 renderer 测试（无 Vitest），CI 无 E2E。
- 服务直接依赖 Win32/WebView/App 单例，无法 mock。

建议：拆 pb_core（services/domain/utils，最小 Win32）静态库；接口注入；C++ 单测覆盖 JobService/locator/annotation/settings/migration/translation/TTS；renderer 用 Vitest + Testing Library；PB-018 E2E 用 CDP 测试钩子。

### P2 其他

- CSS/主题：单一 globals.css + 内联样式 + 硬编码深色（ReaderView 加载页 bg-gray-900、注入 JS 深色）；新增 TTS/翻译/标注弹层会复制主题 bug。建议先做 shared/ui 基元（Popover/Dialog/Toolbar/Switch/Field）与 token 约束。
- i18n 检查不失败：check-i18n-keys.js 只打印 missing，不 process.exit(1)；当前 missing=0，但新增功能会漏。建议让它失败。
- 类型重复：contract 的 Pb* 与 readerStore 的 Bookmark/Highlight/Note 手工两份；生成 renderer model 类型，或从同一 schema 生成。
- WM_USER 消息编号：WebViewHost.cpp 与 ZLibraryService.cpp 各自定义 10-21；已有撞车史。用单一 UiDispatcher 替代，不再新增编号。
- 事件订阅：ProcessSubscribe 空实现、事件全局广播；新增 TTS/翻译事件前，明确「广播即可」还是按 jobId 过滤，避免 UI 收到别人的进度。

## 3. 目标结构（增量，不重写）

C++：

    app/            wWinMain、组合根、生命周期
    platform/       UiDispatcher、JobService、Logger、SecretStore、WebViewHost（只做窗口/WebView）
    bridge/         transport、registry、generated stub、exposure、error envelope
    domain/         Locator、Annotation、Settings、Book、Job
    services/       import、pdf、zlibrary、booksource、tts、translation、dictionary、backup、search、update
    storage/        Database、repositories、migrations、search index
    handlers/       按域注册

Renderer：

    app/            shell、页面切换
    features/       library、reader、settings、sources、search、tts、translation
    shared/         ui 基元、services（typed client）、hooks、stores
    types/          generated

## 4. 建议路线

### Phase 0（PB-001/002/003 之前）

1. 单实例 + 文件打开转发（新 P0）。
2. UiDispatcher + JobService；迁移 book:import / file:read / pdf:* / bookSource:search（新 P0）。
3. Bridge 加固：契约拆分、生成 stub、handler 注册校验、exposure、错误 envelope（扩 PB-015）。
4. LogService（PB-016 提前）。

### Phase 1

5. Locator + Annotation + 迁移（新 P0，配合 PB-014）。
6. ReaderAdapter + 共享选中/搜索/高亮/进度（新 P1）。
7. Settings + SecretStore（新 P1，PB-001/002 之前）。

### Phase 2

8. 拆 god files + 注入 JS 外置（P1/P2，随功能改到哪拆到哪）。
9. pb_core + renderer 测试 + E2E（并入 PB-018）。
10. DB 仓库/锁优化/索引（并入 PB-017）。
11. CSS 基元 + 主题审计、i18n 失败、类型收口（P2）。

## 5. 建议新增到 BACKLOG

| 新 ID | 事项 | 优先级 | 前置/说明 |
|---|---|---|---|
| PB-019 | 单实例 + 文件打开转发 | P0 | PB-012 之前 |
| PB-020 | UiDispatcher + JobService + 长任务迁移 | P0 | PB-001/002/003/006 之前 |
| PB-021 | Locator + Annotation 统一模型 | P0 | PB-003/004 之前；配合 PB-014 |
| PB-022 | ReaderAdapter + 共享阅读交互 | P1 | 随 P0-5/P1-1 落地 |
| PB-023 | Settings + SecretStore | P1 | PB-001/002 之前 |
| PB-024 | god files 拆分 + 注入 JS 外置 | P1/P2 | 增量，不一次性 |
| PB-025 | 设计基元 + 主题审计 | P2 | 新 UI 弹层之前 |

另外建议：
- PB-015 扩为 Bridge 加固（不只是加成员）。
- PB-016 提前（日志是所有网络/长任务功能的排障基础）。
- PB-018 扩为 test seams（pb_core + renderer 测试）。
- PB-017 扩为 DB 写锁/仓库。

## 6. 不要做

- 不要为了「架构整洁」重写 C++ 或换成 Electron/Tauri。
- 不要引入 DI 框架/事件总线框架/ORM；用小的 interface + 手写组合即可。
- 不要给每个功能单独开线程 + 单独 WM_USER 消息。
- 不要把 AI Key 放进 settings JSON / 备份。
- 不要为了跨书搜索立刻把 JSON 库换成 SQLite；先加独立索引，按需再评估。

## 7. 验收指标（建议）

- UI 线程单次 handler 目标 < 50 ms；长任务一律返回 jobId。
- 所有后台事件只经 UiDispatcher；无服务直接持有 WebView2。
- 契约 100% handler 注册；d.ts 新鲜；exposure 由契约生成。
- 单实例：第二次启动把路径转给已运行实例，DB 无并发写。
- 迁移测试：v1→v2→… 样例数据；失败自动备份。
- E2E 覆盖 5-8 条关键路径；renderer 至少覆盖 stores/services。
- 5000 本书：启动/滚动/搜索在目标阈值内（PB-017 定具体数）。

> 本文是架构建议，不是排期承诺；新增 PB-019..PB-025 是否入清单、以什么顺序，按你的决定来。
