# ParticleBook 待办清单

> 建立：2026-10-26。维护规则：用户口述要做的事 → 追加到「待办」；完成后移入「已完成」并注明提交 / 版本。
> 状态：📋 待办 · 🚧 进行中 · ✅ 完成 · ⏸ 暂缓 · ❌ 否决
> 优先级：P0（先做） · P1（下一步） · P2（未排期）
> 说明：这是工作清单，不是发布承诺；「版本」未定的写「待定」。

## 待办

| ID | 事项 | 优先级 | 状态 | 版本 | 一句话说明 |
|---|---|---|---|---|---|
| PB-001 | TTS 朗读 | P0 | 📋 待办 | 2.2.1 | 本地系统 TTS，离线、零下载、零安装包增量 |
| PB-002 | AI 翻译（用户自带 Key） | P0 | 📋 待办 | 2.2.1 | 仅 AI；用户配置第三方 Key 后才可用；无我们的服务器 |
| PB-003 | PDF 标注 | P1 | 📋 待办 | 2.2.1 | 文本层 + 高亮/批注；导出复用 PB-004 |
| PB-004 | 笔记 / 高亮导出 | P1 | 📋 待办 | 2.2.1 | Markdown / CSV / JSON，单本 + 全库 |
| PB-005 | 元数据 / 标签 / 系列 / 评分 | P1 | 📋 待办 | 2.2.1 | 书库管理深度：可编辑、可筛选 |
| PB-006 | 跨书全文搜索 | P1 | 📋 待办 | 2.2.1 | 全书库建索引，结果可跳转 |
| PB-007 | 统计升级 | P2 | 📋 待办 | 2.2.1 | 热力图、连续天数、目标 |
| PB-008 | 格式补齐 | P2 | 📋 待办 | 2.2.1 | AZW3 / DOCX 优先，其余评估 |
| PB-009 | 双栏 / 分屏 / 竖排 | P2 | 📋 待办 | 2.2.1 | 大屏与竖排阅读 |
| PB-010 | 本地词典 | P1 | 📋 待办 | 2.2.1 | 离线查词：ECDICT + StarDict 导入 |
| PB-011 | 备份 / 恢复 | P0 | 📋 待办 | 2.2.1 | 本地 zip 快照 + 恢复；后续可选 WebDAV |
| PB-012 | 文件关联 / 打开方式 | P1 | 📋 待办 | 2.2.1 | 只进「打开方式」，不抢默认 |
| PB-013 | 书源生态增强 | P1 | 📋 待办 | 2.2.1 | URL/剪贴板导入、规则编辑、单源测试 |
| PB-014 | 数据 schema 版本 + 迁移 | P0 | 📋 待办 | 2.2.1 | 工程前置：PB-005/006 之前必做 |
| PB-015 | 桥接契约扩展 | P0 | 🚧 进行中 | 2.2.1 | handler 注册校验已完成；契约拆分/stub 生成/exposure/error 待续 |
| PB-016 | 本地诊断日志 | P1 | 📋 待办 | 2.2.1 | 脱敏日志 + 导出诊断包，无遥测 |
| PB-017 | 大书库性能 | P2 | 📋 待办 | 2.2.1 | 虚拟列表、封面缓存、索引性能 |
| PB-018 | 关键路径 E2E | P1 | 📋 待办 | 2.2.1 | CI 跑 5–8 条关键链路 |
| PB-020 | UiDispatcher + JobService + 长任务迁移 | P0 | 🚧 进行中 | 2.2.1 | 平台核心已落；App/WebViewHost 接线待续 |
| PB-021 | Locator + Annotation 统一模型 | P0 | 📋 待办 | 2.2.1 | 统一 cfi/offset/page/rect；标注/搜索/TTS 共用 |
| PB-022 | ReaderAdapter + 共享阅读交互 | P1 | 📋 待办 | 2.2.1 | 5 个渲染器收敛到统一 adapter |
| PB-023 | Settings + SecretStore | P0 | 🚧 进行中 | 2.2.1 | SecretStore 已落；Settings schema/接线待续 |
| PB-024 | god files 拆分 + 注入 JS 外置 | P2 | 📋 待办 | 2.2.1 | ZLibrary/FileHandlers/App/WebViewHost 等增量拆 |
| PB-025 | 设计基元 + 主题审计 | P2 | 📋 待办 | 2.2.1 | Popover/Dialog/Toolbar 等基元 + token 约束 |

## 2.2.1 执行顺序

> 全部 25 项都归入 2.2.1；只按 P0 → P1 → P2 推进。

### 批次 1 · P0（9 项，先做）

1. ✅ PB-019 单实例 + 文件打开转发（已完成）
2. PB-020 UiDispatcher + JobService + 长任务迁移
3. PB-015 桥接契约扩展（扩为 Bridge 加固）
4. PB-011 备份 / 恢复
5. PB-014 数据 schema 版本 + 迁移
6. PB-021 Locator + Annotation 统一模型
7. PB-023 Settings + SecretStore
8. PB-001 TTS 朗读
9. PB-002 AI 翻译（用户自带 Key）

### 批次 2 · P1（10 项）

1. PB-016 本地诊断日志（建议从 P0 起并行）
2. PB-018 关键路径 E2E（随 P0 开始，持续补）
3. PB-022 ReaderAdapter + 共享阅读交互
4. PB-010 本地词典
5. PB-004 笔记 / 高亮导出
6. PB-012 文件关联 / 打开方式
7. PB-003 PDF 标注
8. PB-005 元数据 / 标签 / 系列 / 评分
9. PB-006 跨书全文搜索
10. PB-013 书源生态增强

### 批次 3 · P2（6 项）

1. PB-024 god files 拆分 + 注入 JS 外置
2. PB-025 设计基元 + 主题审计
3. PB-017 大书库性能
4. PB-007 统计升级
5. PB-008 格式补齐
6. PB-009 双栏 / 分屏 / 竖排

> 优先级：P0 = 先做 / 阻塞项；P1 = 紧跟；P2 = 最后。
> 依赖：PB-019 在 PB-012 前；PB-020 / PB-015 在 TTS / 翻译 / 导入 / 搜索前；PB-011 建议在 PB-014 迁移前可用；PB-021 在 PB-003/004 前（配合 PB-014）；PB-023 在 PB-002 前；PB-018 随 P0 起持续补。


## PB-001 · TTS 朗读

**目标**：在本地离线条件下朗读 EPUB / TXT / HTML / PDF，不依赖云端、不默认捆绑模型。

**技术路线**
- 主：C++ SAPI5 `ISpVoice`（Win10/11 自带；本机 zh-CN：Huihui / Yaoyao / Kangkang，en-US：David / Zira / Mark）
- 升级：OneCore / WinRT `Windows.Media.SpeechSynthesis`（音色更新；Windows 11 Natural Voices 视系统安装情况）
- 可选（后续）：Edge TTS 在线自然音色（默认关闭、需联网提示）；Piper 离线音色包（可选下载，不捆绑）

**MVP 验收**
- [ ] 音色列表（中 / 英，去重），可选音色与语速
- [ ] 播放 / 暂停 / 恢复 / 停止；关书或退出应用即停
- [ ] 句子队列 + 当前句高亮 + 自动滚动（EPUB / TXT / HTML）
- [ ] PDF 可播报（先不做精确高亮）
- [ ] Z-Library 导航、窗口最小化时不断播
- [ ] 睡眠定时
- [ ] 无可用音色时提示去 Windows 安装语音
- [ ] 设置持久化（音色 / 语速）

**非目标（本版）**：词级 karaoke、声音克隆、在线音色、Piper 音色包、有声书。

**备注**：先做 1 天 spike，验证 SAPI 词边界事件、后台播放、Z-Library 导航后不断播。

## PB-002 · AI 翻译（用户自带 Key）

**目标**：选中文本 → 调用用户自己配置的 AI 翻译服务，返回译文。我们不提供 Key、不中转、不内置免费源。

**约束**
- 仅 AI 翻译：不内置离线模型、不内置免费公共接口、不做我们的中转服务器。
- 用户必须在设置里填写 API Base URL、API Key、模型名后功能才可用；未配置时入口禁用并引导配置。
- Key 仅存本机（优先 Windows DPAPI 加密），不写日志、不上传。
- 默认关闭；每次翻译明确提示「文本将发送到用户配置的第三方服务」。
- 兼容 OpenAI 兼容接口（DeepSeek / OpenAI / Kimi / 智谱 / 硅基流动等），Base URL 与模型名可填。

**MVP 验收**
- [ ] 设置页：Base URL / API Key / 模型名 / 目标语言 / 「测试连接」
- [ ] 选中文本弹层「翻译」，显示原文 + 译文，可复制
- [ ] 段落翻译（EPUB / TXT / HTML / FB2 / Markdown；PDF 无文本层，先不做划词）
- [ ] 结果本地缓存（按原文 hash），重复翻译/离线可看
- [ ] 错误处理：Key 无效、额度不足、网络失败、超时、模型不存在，给可读提示
- [ ] 只发送选中的文本 + 必要参数；不发送书名 / 文件路径（可选上下文由用户勾选）
- [ ] 与 TTS / 高亮共用同一个选中文本弹层

**非目标（本版）**：离线模型、免费公共接口、全书一键翻译、词典释义、语音翻译、流式输出（可后续）。

**决策记录**：2026-10-26 用户明确——翻译只用 AI，必须用户填写第三方 Key 才可使用；不做离线模型 / 免费公共接口。


## PB-003 · PDF 标注

**目标**：在 PDF 上做高亮 / 下划线 / 文字笔记，持久化并可跳转、导出。

**前置**：当前 PDF 是图片渲染 + 文本搜索，没有文本层；需先补 PDF 文本层（可选中/复制），才能划词标注。扫描版 PDF 需要 OCR，不在本项范围。

**MVP 验收**
- [ ] PDF 文本层：可选中、复制
- [ ] 高亮（多色）、下划线、文字笔记
- [ ] 按页 + 百分比坐标持久化；缩放/换页/重开不丢
- [ ] 侧栏标注列表 + 点击跳转
- [ ] 删除 / 编辑标注
- [ ] 导出复用 PB-004

**非目标**：手写、PDF 表单、签名、扫描件 OCR、直接修改原 PDF 文件。

## PB-004 · 笔记 / 高亮导出

**目标**：把书签 / 高亮 / 笔记 / PDF 标注导出为可带走、可进入知识管理的文件。

**MVP 验收**
- [ ] Markdown / CSV / JSON 三种格式
- [ ] 单本导出 + 全库导出
- [ ] 字段：书名 / 作者 / 格式 / 章节 / 原文 / 笔记 / 颜色 / 位置 / 时间
- [ ] 复制到剪贴板
- [ ] 导出前预览 / 选择范围
- [ ] PDF 标注在 PB-003 完成后接入

**非目标**：Readwise / Notion / Obsidian 直连、PDF / HTML 排版导出（可后续）。

## PB-005 · 元数据 / 标签 / 系列 / 评分

**目标**：让书库从「文件夹」变成可管理的书库。

**MVP 验收**
- [ ] 编辑书名 / 作者 / 语言 / 出版社 / 简介 / 封面
- [ ] 标签（多选）、系列（名称 + 序号）、评分（1–5 星）
- [ ] 按标签 / 系列 / 评分筛选与排序
- [ ] 书库搜索覆盖标签 / 系列
- [ ] 旧数据迁移默认值（无标签 / 无系列 / 未评分）
- [ ] 详情弹窗可编辑，不再只读

**非目标（本版）**：在线元数据抓取（豆瓣 / Google Books）、Calibre 元数据导入、批量编辑（可后续）。

## PB-006 · 跨书全文搜索

**目标**：在全部书籍里搜一句话，定位到书 / 章节 / 位置。

**MVP 验收**
- [ ] 导入书籍时抽取纯文本建索引（EPUB / PDF / TXT / FB2 / HTML / MD；CBZ 图片不索引）
- [ ] 索引随导入 / 删除更新；支持手动重建
- [ ] 全局搜索入口 + 结果列表（书名 / 章节 / 高亮片段）
- [ ] 点击结果跳转到书内位置（EPUB CFI / TXT 偏移 / PDF 页）
- [ ] 大书库索引大小与搜索延迟可接受

**非目标**：语义搜索、OCR 扫描件、跨书正则 / 高级语法（可后续）。

**备注**：索引单独存储，不塞进主 JSON 库；大库可评估 SQLite FTS5。

## PB-007 · 统计升级

**目标**：把现有「总量 + 单书」升级成能反馈习惯的统计。

**MVP 验收**
- [ ] 日 / 周 / 月阅读时长视图
- [ ] 日历热力图 + 连续阅读天数
- [ ] 阅读目标（每日 / 每周分钟数）
- [ ] 按书 / 格式排行
- [ ] 数据只来自本地 reading_sessions，不上传

**非目标**：社交排行、成就系统、跨设备同步（可后续）。

## PB-008 · 格式补齐

**目标**：补齐常用格式，优先 Kindle 与办公文档。

**候选与优先级**
1. AZW3 / AZW（Kindle KF8）：优先；需评估 foliate-js 或 MuPDF 支持，DRM 文件不支持
2. DOCX：优先；mammoth.js 转 HTML
3. ODT / RTF：次优先；zip + XML / RTF 解析
4. DjVu / CHM / CBT / CB7：先做可行性 + 许可证评估（DjVu / CHM 相关库的许可可能不兼容 MIT）

**MVP 验收（第一批）**
- [ ] AZW3 / AZW 导入 + 阅读 + 目录 + 搜索
- [ ] DOCX 导入 + 阅读（文字 / 图片）
- [ ] 其余格式给出「做 / 不做」结论与理由

**非目标**：DRM 解密、扫描件 OCR。

## PB-009 · 双栏 / 分屏 / 竖排

**目标**：大屏与竖排阅读。

**MVP 验收**
- [ ] EPUB 双栏（宽屏自动 / 手动）
- [ ] 分屏阅读（两本书并排；或同书两位置，二选一）
- [ ] 竖排模式（EPUB / HTML，writing-mode: vertical-rl；PDF 不支持）
- [ ] 排版设置持久化
- [ ] 与现有主题 / 字体 / 边距设置兼容

**非目标**：PDF 双页 / 漫画双页、RTL 漫画模式（可后续）。


## PB-010 · 本地词典

**目标**：离线查词，不依赖 AI / 网络。

**方案**：ECDICT 核心子集（MIT，英→中）+ StarDict 用户词库导入；中文按分词取词；CC-CEDICT（中→英）可选。

**MVP 验收**
- [ ] 选中单词弹层：音标 / 释义 / 词形 / 考试标签
- [ ] 英文 lemma / 词形匹配；中文分词取词
- [ ] StarDict 导入 / 启用 / 禁用 / 删除
- [ ] 无网络依赖；查词延迟可接受
- [ ] EPUB / TXT / HTML / FB2 / Markdown 可用；PDF 待 PB-003 文本层

**非目标**：AI 词典解释、MDX（后续）、在线词典。

## PB-011 · 备份 / 恢复

**目标**：本地快照 + 恢复，防止磁盘故障 / 重装丢数据；后续可接 WebDAV。

**MVP 验收**
- [ ] 导出 zip：数据库、设置、标注 / 笔记、封面（书籍文件可选）
- [ ] 导入恢复：覆盖模式（MVP）；合并后续
- [ ] 恢复前自动备份当前数据
- [ ] schema 版本检查；不兼容时拒绝并提示
- [ ] 大库导出 / 导入进度与失败重试
- [ ] Phase 2：WebDAV 自动备份（用户自己的存储，不做我们的服务器）

**非目标**：实时多端同步（后续）、账号体系。

## PB-012 · 文件关联 / 打开方式（不抢默认）

**目标**：让用户可以用 ParticleBook 打开电子书，但绝不篡改默认程序。

**约束**
- 只注册 ProgID / OpenWithProgids 和应用能力，**不写默认关联**，不调用强制设置默认的 API。
- 安装 / 首次运行不自动成为默认；提供「打开 Windows 默认应用设置」的入口，让用户自己选。
- 卸载时清理注册项，不残留。
- 与用户已有默认阅读器共存。

**MVP 验收**
- [ ] 安装器注册 ProgID + OpenWithProgids（EPUB / PDF / MOBI / FB2 / TXT / CBZ / CBR）
- [ ] 右键「打开方式」可见 ParticleBook
- [ ] 应用内提供「打开 Windows 默认应用设置」按钮
- [ ] 卸载清理；不改变任何现有默认关联
- [ ] 启动参数接收文件路径并导入 / 打开

**非目标**：静默设为默认、系统级文件类型劫持。

## PB-013 · 书源生态增强

**目标**：把 Legado 兼容从「能导入」提升到「可维护」。

**MVP 验收**
- [ ] 从 URL / 剪贴板 / JSON 文件导入（含批量）
- [ ] 规则编辑：搜索 / 详情 / 目录 / 正文的 URL 与规则
- [ ] 单源测试：搜索关键词、取章节、试下载，错误定位到具体规则
- [ ] 启用 / 禁用 / 分组 / 排序；替换规则（可选）
- [ ] 导入失败 / 重复源检测与提示

**非目标**：RSS、TTS 书源、JS 规则完整兼容（按 Legado 规则复杂度逐步补）。

**进度（2026-10-26）**：handler 注册校验已完成（`scripts/check-bridge-handlers.js` + CI，commit `02c62a5`）；契约拆分 / 生成 stub / exposure / error envelope 待续。

## PB-014 · 数据 schema 版本 + 迁移

**目标**：给 JSON 库加显式版本与可测试迁移，保障升级不丢数据。

**MVP 验收**
- [ ] 数据库顶层 schema_version
- [ ] 迁移注册表：按版本顺序执行；可重复执行
- [ ] 迁移前自动备份（复用 PB-011 或内部备份）
- [ ] 失败回滚 / 保留原文件；启动时提示
- [ ] 降级保护：旧版本打开新库时拒绝并提示
- [ ] 迁移单测：v1→v2→… 样例数据

**前置**：PB-005（元数据）/ PB-006（索引）开工前完成。

## PB-015 · 桥接契约扩展

**目标**：TTS / 翻译 / 词典 / 标注 / 备份 / 搜索新增接口时，契约、生成物、C++ 实现、CI 同步。

**MVP 验收**
- [ ] 新成员先在 bridge/contract.mjs 定义（名称 / 方法 / 参数 / 返回 / 事件）
- [ ] gen-bridge-dts 生成物与 --check 通过
- [ ] check-bridge-contract / audit-bridge-usage 通过
- [ ] C++ BridgeServer 注册与渲染层调用一致
- [ ] 每个新成员有消费者，或明确标注「预留」并纳入审计白名单

**前置**：PB-001 / PB-002 / PB-003 / PB-010 开工前。

## PB-016 · 本地诊断日志

**目标**：出问题时能定位，不上传任何数据。

**MVP 验收**
- [ ] 滚动日志（大小 / 数量上限），级别可调
- [ ] 记录：启动、C++ 异常、网络失败、AI provider 报错、下载 / 导入失败
- [ ] 「导出诊断包」：日志 + 版本 + 系统信息 + 脱敏设置
- [ ] 自动脱敏：API Key、Cookie、用户路径（可配置）
- [ ] 默认本地记录；无遥测、无上传

**非目标**：崩溃上报服务、远程日志。

## PB-017 · 大书库性能

**目标**：书多了不卡：导入、滚动、封面、搜索。

**MVP 验收**
- [ ] 5000 本书基准：启动 / 切页 / 滚动 / 搜索可接受
- [ ] 网格 / 列表虚拟化；封面懒加载 + 缩略图缓存
- [ ] 数据库读写不阻塞 UI；批量导入有进度
- [ ] 封面缓存可配置 / 可淘汰，内存有上限
- [ ] 提供基准脚本 / 测试数据生成器

**非目标**：分布式 / 多进程；先做单机优化。

## PB-018 · 关键路径 E2E

**目标**：关键链路回归自动化，CI 能拦住「构建过但功能坏」。

**MVP 验收**
- [ ] 干净构建 smoke：启动 → 导入 EPUB / PDF → 打开 → 搜索 → 退出
- [ ] Z-Library 下载 → 导入（网络可用时或 mock）
- [ ] 翻译请求（mock provider）/ TTS 启动（SAPI 可用时）
- [ ] 备份 → 恢复 → 数据一致
- [ ] schema 迁移样例
- [ ] 在 CI（Windows runner）跑；失败附日志 / 截图

**非目标**：全量 UI 覆盖；先覆盖 5–8 条关键路径。


## PB-019 · 单实例 + 文件打开转发

**目标**：同一时间只允许一个 ParticleBook 进程；第二次启动把文件路径转给已运行实例。

**MVP 验收**
- [ ] 命名 mutex（Local\ParticleBook.SingleInstance）；第二次启动不进入主流程
- [ ] 命名管道或 WM_COPYDATA 转发路径（可多个），支持「导入」/「打开」两种动作
- [ ] 第一个实例收到后恢复窗口并导入/打开
- [ ] 同一 DB 无并发写；退出时转发端不挂起
- [ ] PB-012 文件关联之前完成

**非目标**：多窗口 / 标签页。

**进度（2026-10-26）**：已完成（commit `9e5943d`）；手工验证二次启动 108ms 退出、主实例收到 `app:openFiles` 事件、仅一个进程。

## PB-020 · UiDispatcher + JobService + 长任务迁移

**目标**：长任务不再占用 WebView2 UI 线程；所有后台事件统一回 UI 线程。

**MVP 验收**
- [ ] UiDispatcher：单一 WM_APP 消息 + std::function 队列，替代散落的 WM_USER+N 与裸指针 LPARAM
- [ ] JobService：submit/cancel/progress；worker 池；事件 job:progress / job:done / job:error
- [ ] 迁移：book:import、file:read（大文件/MOBI）、pdf:open/render/extractText、bookSource:search/searchOne/getBookInfo/getChapterList
- [ ] 长任务 handler 立即返回 jobId；UI 不阻塞
- [ ] Shutdown 先停 JobService/join，再拆 bridge/webview/db
- [ ] 为 TTS/翻译/备份/索引预留同一套 job 接口

**非目标**：多进程 worker；先做单进程线程池。

**进度（2026-10-26）**：平台核心 6 文件 + 单测已落（commit `6d9dc58`）；App/WebViewHost 接线待续。首次集成尝试会破坏桥接消息投递（页面 invoke 无响应），已整体回退；下一步按最小步集成：先只接 UiDispatcher，再逐项接 JobService。

## PB-021 · Locator + Annotation 统一模型

**目标**：给书签、高亮、笔记、PDF 标注、TTS、搜索提供统一的「书内位置」表示。

**MVP 验收**
- [ ] Locator JSON（带 version/kind）：epub cfi / text offset / html anchor / pdf page+归一化 rect / scroll percent
- [ ] Annotation 统一：bookmark / highlight / note / pdf-annot；id、book_id、locator、text、color、note、created_at、updated_at
- [ ] 旧数据迁移：cfi、page、pos-<offset>、scrollPosition 映射到 Locator（配合 PB-014）
- [ ] ReaderAdapter 用 Locator 读写位置；导出/搜索/TTS 复用
- [ ] PDF 页内矩形可存取；缩放/换页不丢

**非目标**：手写笔迹；先做文本/矩形标注。

## PB-022 · ReaderAdapter + 共享阅读交互

**目标**：5 个渲染器不再各写一套搜索/进度/高亮；TTS/翻译/词典只对接一层。

**MVP 验收**
- [ ] ReaderAdapter 接口：getLocator / goTo / getSelection / getTextModel / search / applyAnnotation / removeAnnotation
- [ ] 共享 SelectionToolbar、SearchController、ProgressController
- [ ] EPUB / TXT / HTML 先接；PDF / Comic 后续
- [ ] TTS/翻译/词典/导出只依赖 adapter，不按格式分支
- [ ] 现有阅读行为不回退

**非目标**：重写渲染器；保留各格式渲染内核。

## PB-023 · Settings + SecretStore

**目标**：设置有了 typed schema；第三方 Key 安全存储，不进 settings/备份/日志。

**MVP 验收**
- [ ] Settings schema：全局/书级、默认值、校验、版本迁移
- [ ] SecretStore：Windows DPAPI 加密；只存 provider key
- [ ] settings 只存 provider id / base URL / model；备份与日志不含 Key
- [ ] 渲染层由 schema 驱动表单；GlobalSettings 按页拆分
- [ ] TTS/翻译/词典/备份的设置项可增量加 schema，不再加散落字段

**非目标**：系统凭据管理器集成（可后续）。

**进度（2026-10-26）**：SecretStore + 13 段单测已落（commit `6d9dc58`）；Settings schema / App 接线待续。

## PB-024 · god files 拆分 + 注入 JS 外置

**目标**：把 1000+ 行文件和 App.cpp 里的注入 JS 拆成可维护单元；纯重构，不改行为。

**MVP 验收**
- [ ] 抽 HttpClient / DownloadManager / ImportService / UpdateService / MirrorService
- [ ] ZLibraryService / BookSourceService / FileHandlers / WebViewHost 按职责收缩
- [ ] 注入 JS 移到 resources/zlib_toolbar.js、resources/download_card.js，使用 __pbLang + CSS 变量
- [ ] GlobalSettings / ReaderView / EpubRenderer 拆到可读大小（按功能改到哪拆到哪）
- [ ] 重构前后构建、测试、关键路径 smoke 通过

**非目标**：一次性全部拆完；不追求「每文件 < 300 行」的形式指标。

## PB-025 · 设计基元 + 主题审计

**目标**：新 UI（TTS 控制条、翻译/词典弹层、标注侧栏）不再重复主题 bug。

**MVP 验收**
- [ ] shared/ui 基元：Popover / Dialog / Toolbar / Switch / Field / List
- [ ] 颜色只走 CSS token；移除 ReaderView 加载页 bg-gray-900 等硬编码深色
- [ ] 亮色 / 暗色 / 护眼三主题走查：新弹层、菜单、tooltip、空状态
- [ ] 键盘焦点、ESC 逐层关闭、aria 名称纳入基元
- [ ] issue #6 同类问题有回归检查

**非目标**：整体视觉重设计。


## 待排期

- （暂无；用户口述后追加）

## 已完成

| 事项 | 完成时间 | 提交 / 版本 |
|---|---|---|
| issue #6 亮色设置页文字不可读 | 2026-10-25 | `27d9dcd`（未发版） |
| PB-019 单实例 + 文件打开转发 | 2026-10-26 | `9e5943d`（未发版） |

## 暂缓 / 否决

| 事项 | 原因 | 日期 |
|---|---|---|
| issue #7 安卓适配 | 需单独立项；方向待定 | 2026-10-25 |
