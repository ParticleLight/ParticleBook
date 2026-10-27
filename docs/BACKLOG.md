# ParticleBook 待办清单

> 建立：2026-10-26。维护规则：用户口述要做的事 → 追加到「待办」；完成后移入「已完成」并注明提交 / 版本。
> 状态：📋 待办 · 🚧 进行中 · ✅ 完成 · ⏸ 暂缓 · ❌ 否决
> 优先级：P0（先做） · P1（下一步） · P2（未排期）
> 说明：这是工作清单，不是发布承诺；「版本」未定的写「待定」。

## 待办

| ID | 事项 | 优先级 | 状态 | 版本 | 一句话说明 |
|---|---|---|---|---|---|
| PB-002 | AI 翻译（用户自带 Key） | P0 | 🚧 进行中 | 2.2.1 | 全链已接；真实 provider/共用弹层待补 |
| PB-006 | 跨书全文搜索 | P1 | 🚧 进行中 | 2.2.1 | 搜索 UI/索引已接；精确跳转/PDF/FB2 待补 |
| PB-008 | 格式补齐 | P2 | 🚧 进行中 | 2.2.1 | DOCX 完成；AZW3 待真实样本 |
| PB-009 | 双栏 / 分屏 / 竖排 | P2 | 📋 待办 | 2.2.1 | 大屏与竖排阅读 |
| PB-024 | god files 拆分 + 注入 JS 外置 | P2 | 🚧 进行中 | 2.2.1 | ZLibrary/FileHandlers/注入 JS 已拆；ReaderView 等未拆 |

## 2.2.1 执行顺序

> 全部 25 项都归入 2.2.1；只按 P0 → P1 → P2 推进。

### 批次 1 · P0（9 项，先做）

1. ✅ PB-019 单实例 + 文件打开转发（已完成）
2. ✅ PB-020 UiDispatcher + JobService + 长任务迁移（已完成）
3. ✅ PB-015 桥接契约扩展（已完成）
4. ✅ PB-011 备份 / 恢复（MVP 已完成）
5. ✅ PB-014 数据 schema 版本 + 迁移（已完成）
6. ✅ PB-021 Locator + Annotation 统一模型（已完成）
7. ✅ PB-023 Settings + SecretStore（已完成）
8. ✅ PB-001 TTS 朗读（已完成）
9. PB-002 AI 翻译（用户自带 Key）

### 批次 2 · P1（10 项）

1. ✅ PB-016 本地诊断日志（已完成）
2. ✅ PB-018 关键路径 E2E（16/16，已完成）
3. ✅ PB-022 ReaderAdapter + 共享阅读交互（已完成）
4. ✅ PB-010 本地词典（已完成）
5. ✅ PB-004 笔记 / 高亮导出（已完成；PDF 标注依赖 PB-003）
6. ✅ PB-012 文件关联 / 打开方式（已完成）
7. ✅ PB-003 PDF 标注（已完成）
8. ✅ PB-005 元数据 / 标签 / 系列 / 评分（已完成）
9. PB-006 跨书全文搜索
10. ✅ PB-013 书源生态增强（已完成）

### 批次 3 · P2（6 项）

1. 🚧 PB-024 god files 拆分 + 注入 JS 外置（部分完成）
2. ✅ PB-025 设计基元 + 主题审计（已完成）
3. ✅ PB-017 大书库性能（已完成）
4. ✅ PB-007 统计升级（已完成）
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
- [x] 音色列表（中 / 英，去重），可选音色与语速
- [x] 播放 / 暂停 / 恢复 / 停止；关书或退出应用即停
- [x] 句子队列 + 当前句高亮 + 自动滚动（EPUB / TXT / HTML）
- [x] PDF 可播报（先不做精确高亮）
- [x] Z-Library 导航、窗口最小化时不断播
- [x] 睡眠定时
- [x] 无可用音色时提示去 Windows 安装语音
- [x] 设置持久化（音色 / 语速）

**非目标（本版）**：词级 karaoke、声音克隆、在线音色、Piper 音色包、有声书。

**备注**：先做 1 天 spike，验证 SAPI 词边界事件、后台播放、Z-Library 导航后不断播。

**进度（2026-10-26）：已完成。** SapiTts（`ea2308a`）+ bridge/App 单例（`444583b`）+ 阅读器 TtsBar/句子高亮/持久化（`8a1e4f2`）+ 暂停竞态修复（`8f149ce`）+ 睡眠定时/PDF/后台验证（`fe1c5dc`）。真机 CDP 37/37：6 音色、播放/暂停/恢复/停止、句高亮、PDF 文本层朗读、最小化/整页导航不断播、睡眠定时。已知：PDF 无页内高亮（验收允许）；Z-Library 导航后睡眠定时随页面丢失（follow-up）。

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

**进度（2026-10-26）**：TranslationService（`bfc4fd2`）+ SecretStore/handler/设置页（`23fd60b`）+ 选中翻译弹层/缓存/测试连接（`5019267`），CDP mock 成功/离线缓存/错误分支。剩余：真实 provider 验证、与高亮共用同一弹层、PDF 划词（非目标）。

**决策记录**：2026-10-26 用户明确——翻译只用 AI，必须用户填写第三方 Key 才可使用；不做离线模型 / 免费公共接口。


## PB-003 · PDF 标注

**目标**：在 PDF 上做高亮 / 下划线 / 文字笔记，持久化并可跳转、导出。

**前置**：当前 PDF 是图片渲染 + 文本搜索，没有文本层；需先补 PDF 文本层（可选中/复制），才能划词标注。扫描版 PDF 需要 OCR，不在本项范围。

**MVP 验收**
- [x] PDF 文本层：可选中、复制
- [x] 高亮（多色）、下划线、文字笔记
- [x] 按页 + 百分比坐标持久化；缩放/换页/重开不丢
- [x] 侧栏标注列表 + 点击跳转
- [x] 删除 / 编辑标注
- [x] 导出复用 PB-004

**非目标**：手写、PDF 表单、签名、扫描件 OCR、直接修改原 PDF 文件。

**进度（2026-10-27）：已完成。** `pdf:textLayer`（mutool stext 字符 quad）→ PdfRenderer 可见页 ±1 透明文本层（可选中/复制）；划词 4 色高亮/下划线（tags）/文字笔记，百分比 rect 持久化；页内渲染/编辑/删除；AnnotationPanel PDF 过滤/下划线标记；导出经旧表双写包含 PDF 标注。CDP 全链通过；E2E 15/15。

## PB-004 · 笔记 / 高亮导出

**目标**：把书签 / 高亮 / 笔记 / PDF 标注导出为可带走、可进入知识管理的文件。

**MVP 验收**
- [x] Markdown / CSV / JSON 三种格式
- [x] 单本导出 + 全库导出
- [x] 字段：书名 / 作者 / 格式 / 章节 / 原文 / 笔记 / 颜色 / 位置 / 时间
- [x] 复制到剪贴板
- [x] 导出前预览 / 选择范围
- [ ] PDF 标注在 PB-003 完成后接入

**非目标**：Readwise / Notion / Obsidian 直连、PDF / HTML 排版导出（可后续）。

**进度（2026-10-26）：已完成（PDF 标注待 PB-003）。** ExportService + 11 组单测（`c903980`）；bridge/下载/复制（`4d4bb15`/`655d2d1`）；预览弹层（`6d11a5d`）；书签/高亮/笔记范围选择（`2655f6c`）。E2E 未单独覆盖导出 UI。

## PB-005 · 元数据 / 标签 / 系列 / 评分

**目标**：让书库从「文件夹」变成可管理的书库。

**MVP 验收**
- [x] 编辑书名 / 作者 / 语言 / 出版社 / 简介 / 封面
- [x] 标签（多选）、系列（名称 + 序号）、评分（1–5 星）
- [x] 按标签 / 系列 / 评分筛选与排序
- [x] 书库搜索覆盖标签 / 系列
- [x] 旧数据迁移默认值（无标签 / 无系列 / 未评分）
- [x] 详情弹窗可编辑，不再只读

**非目标（本版）**：在线元数据抓取（豆瓣 / Google Books）、Calibre 元数据导入、批量编辑（可后续）。

**进度（2026-10-26）：已完成。** schema v4（tags/series_name/series_index/rating，v3->v4 迁移默认值）+ UpdateBookMetadata/GetAllTags/GetAllSeries + bridge `book:updateMetadata`/`library:getTags`/`getSeries` + 详情弹窗编辑（标签 chips/系列/星级）+ 书库筛选/排序/搜索覆盖。真机 CDP 沙箱验证持久化/筛选/标签搜索；18 ctest / 15 E2E。

## PB-006 · 跨书全文搜索

**目标**：在全部书籍里搜一句话，定位到书 / 章节 / 位置。

**MVP 验收**
- [ ] 导入书籍时抽取纯文本建索引（EPUB / PDF / TXT / FB2 / HTML / MD；CBZ 图片不索引）
- [ ] 索引随导入 / 删除更新；支持手动重建
- [ ] 全局搜索入口 + 结果列表（书名 / 章节 / 高亮片段）
- [ ] 点击结果跳转到书内位置（EPUB CFI / TXT 偏移 / PDF 页）
- [ ] 大书库索引大小与搜索延迟可接受

**非目标**：语义搜索、OCR 扫描件、跨书正则 / 高级语法（可后续）。

**进度（2026-10-27）**：SearchIndexService + JobService/bridge + 全局搜索 UI；TXT/MOBI/FB2/Markdown 点击结果按 offset 经 ReaderAdapter.goTo 跳到命中位置（`5bd55f4`）。剩余：EPUB CFI、HTML/PDF 页码映射、建索引百分比进度。

**备注**：索引单独存储，不塞进主 JSON 库；大库可评估 SQLite FTS5。

## PB-007 · 统计升级

**目标**：把现有「总量 + 单书」升级成能反馈习惯的统计。

**MVP 验收**
- [x] 日 / 周 / 月阅读时长视图
- [x] 日历热力图 + 连续阅读天数
- [x] 阅读目标（每日 / 每周分钟数）
- [x] 按书 / 格式排行
- [x] 数据只来自本地 reading_sessions，不上传

**非目标**：社交排行、成就系统、跨设备同步（可后续）。

**进度（2026-10-26）：已完成。** `reading_sessions` 暴露 + `stats:getReadingSessions`；Settings 新增 daily/weekly goal；统计页日/周/月/累计、目标进度、365 天热力图、连续天数、按书/格式排行。数据全部本地。

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

**进度（2026-10-26）**：DOCX 完成（OOXML→自包含 HTML，标题/粗斜体/表格/内联图片/元数据；导入后 docx→HtmlRenderer）；AZW3 复用 mobi 文本管线探测，失败安全跳过，本机无真实样本；其余格式结论已记录。commit `98da5d3`。

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
- [x] 选中单词弹层：音标 / 释义 / 词形 / 考试标签
- [x] 英文 lemma / 词形匹配；中文分词取词
- [x] StarDict 导入 / 启用 / 禁用 / 删除
- [x] 无网络依赖；查词延迟可接受
- [x] EPUB / TXT / HTML / FB2 / Markdown 可用；PDF 待 PB-003 文本层

**非目标**：AI 词典解释、MDX（后续）、在线词典。

**进度（2026-10-26）：已完成。** DictionaryService + 14 组单测（`bfc4fd2`）；bridge/JobService/导入缓存/阅读器查词弹层（`84f68ea`）；启用/禁用 + 中文 2 字分词（`0a1e9fb`）。CDP 实测 ECDICT 导入/精确/前缀/词形/缓存/清空；StarDict 由单测覆盖。单一词典存储，无按本启用/禁用列表。

## PB-011 · 备份 / 恢复

**目标**：本地快照 + 恢复，防止磁盘故障 / 重装丢数据；后续可接 WebDAV。

**MVP 验收**
- [x] 导出 zip：数据库、设置、标注 / 笔记、封面（书籍文件可选）
- [x] 导入恢复：覆盖模式（MVP）；合并后续
- [x] 恢复前自动备份当前数据
- [x] schema 版本检查；不兼容时拒绝并提示
- [x] 大库导出 / 导入进度与失败重试
- [ ] Phase 2：WebDAV 自动备份（用户自己的存储，不做我们的服务器）

**非目标**：实时多端同步（后续）、账号体系。

**进度（2026-10-26）：MVP 已完成。** BackupService（`ea2308a`）+ handler/UI（`9475e3c`）+ 封面/进度/取消（`24de96c`）+ backup:progress/设置页进度条（`d07e1b9`）+ 失败重试 UI（`5019267`）。22 组单测 + E2E backup 10/10。WebDAV 属 Phase 2，未做。

## PB-012 · 文件关联 / 打开方式（不抢默认）

**目标**：让用户可以用 ParticleBook 打开电子书，但绝不篡改默认程序。

**约束**
- 只注册 ProgID / OpenWithProgids 和应用能力，**不写默认关联**，不调用强制设置默认的 API。
- 安装 / 首次运行不自动成为默认；提供「打开 Windows 默认应用设置」的入口，让用户自己选。
- 卸载时清理注册项，不残留。
- 与用户已有默认阅读器共存。

**MVP 验收**
- [x] 安装器注册 ProgID + OpenWithProgids（EPUB / PDF / MOBI / FB2 / TXT / CBZ / CBR）
- [x] 右键「打开方式」可见 ParticleBook
- [x] 应用内提供「打开 Windows 默认应用设置」按钮
- [x] 卸载清理；不改变任何现有默认关联
- [x] 启动参数接收文件路径并导入 / 打开

**非目标**：静默设为默认、系统级文件类型劫持。

**进度（2026-10-26）：已完成。** installer 注册 7 种扩展名 ProgID/OpenWithProgids/Capabilities（不写默认/UserChoice），卸载清理；设置页「打开默认应用设置」深链；makensis 编译与沙箱注册/反注册实测通过。commit `444583b`，说明 `docs/FILE_ASSOCIATION.md`。

## PB-013 · 书源生态增强

**目标**：把 Legado 兼容从「能导入」提升到「可维护」。

**MVP 验收**
- [x] 从 URL / 剪贴板 / JSON 文件导入（含批量）
- [x] 规则编辑：搜索 / 详情 / 目录 / 正文的 URL 与规则
- [x] 单源测试：搜索关键词、取章节、试下载，错误定位到具体规则
- [x] 启用 / 禁用 / 分组 / 排序；替换规则（可选）
- [x] 导入失败 / 重复源检测与提示

**非目标**：RSS、TTS 书源、JS 规则完整兼容（按 Legado 规则复杂度逐步补）。

**进度（2026-10-26）：已完成。** 设置页书源管理：列表/启停/删除/清空、粘贴/文件/同源 URL（CSP 已允许 https，跨域仍受 CORS）/剪贴板导入去重、名称/URL/分组/规则 JSON 编辑（未知字段保留）、单源搜索测试、分组筛选、按名称/分组/添加时间排序、批量启停/删除。真机 CDP 20/20 + 19/19；已知限制：外部 URL 受目标站 CORS、无更新排序、批量逐条 bridge。

## PB-014 · 数据 schema 版本 + 迁移

**目标**：给 JSON 库加显式版本与可测试迁移，保障升级不丢数据。

**MVP 验收**
- [x] 数据库顶层 schema_version
- [x] 迁移注册表：按版本顺序执行；可重复执行
- [x] 迁移前自动备份（复用 PB-011 或内部备份）
- [x] 失败回滚 / 保留原文件；启动时提示
- [x] 降级保护：旧版本打开新库时拒绝并提示
- [x] 迁移单测：v1→v2→… 样例数据

**前置**：PB-005（元数据）/ PB-006（索引）开工前完成。

**进度（2026-10-26）：已完成。** `Load()` 顶层 `schema_version` + 迁移注册表；v1→v2 自动备份 `.pre-migrate-v1-<ts>` 后原子写回；更新版本直接拒绝且不改文件；`database_test` 覆盖 v1 迁移/备份/数据保留与 newer 拒绝。commit `87331a0`。

## PB-015 · 桥接契约扩展

**目标**：TTS / 翻译 / 词典 / 标注 / 备份 / 搜索新增接口时，契约、生成物、C++ 实现、CI 同步。

**MVP 验收**
- [x] 新成员先在 bridge/contract.mjs 定义（名称 / 方法 / 参数 / 返回 / 事件）
- [x] gen-bridge-dts 生成物与 --check 通过
- [x] check-bridge-contract / audit-bridge-usage 通过
- [x] C++ BridgeServer 注册与渲染层调用一致
- [x] 每个新成员有消费者，或明确标注「预留」并纳入审计白名单

**前置**：PB-001 / PB-002 / PB-003 / PB-010 开工前。

**进度（2026-10-26）：已完成。** 契约单一来源 + `gen-bridge-dts --check` + `check-bridge-contract` + `check-bridge-handlers` + `audit-bridge-usage` 均通过；CI 已跑；74 个非 local 成员 0 个无消费者，6 个明确 `reserved`。后续 TTS/翻译/词典/标注/备份新增接口按此流程走。

## PB-016 · 本地诊断日志

**目标**：出问题时能定位，不上传任何数据。

**MVP 验收**
- [x] 滚动日志（大小 / 数量上限），级别可调
- [x] 记录：启动、C++ 异常、网络失败、AI provider 报错、下载 / 导入失败
- [x] 「导出诊断包」：日志 + 版本 + 系统信息 + 脱敏设置
- [x] 自动脱敏：API Key、Cookie、用户路径（可配置）
- [x] 默认本地记录；无遥测、无上传

**非目标**：崩溃上报服务、远程日志。

**进度（2026-10-26）：已完成。** LogService + App 启动日志（`e87c766`/`2372236`）；诊断包导出 + 设置页按钮（`f03800f`）；未捕获异常/网络/下载/导入/AI provider 失败埋点（`a7c49d9`）。实测导出 logs/version/system/settings/README，日志脱敏/无遥测。

## PB-017 · 大书库性能

**目标**：书多了不卡：导入、滚动、封面、搜索。

**MVP 验收**
- [x] 5000 本书基准：启动 / 切页 / 滚动 / 搜索可接受
- [x] 网格 / 列表虚拟化；封面懒加载 + 缩略图缓存
- [x] 数据库读写不阻塞 UI；批量导入有进度
- [x] 封面缓存可配置 / 可淘汰，内存有上限
- [x] 提供基准脚本 / 测试数据生成器

**非目标**：分布式 / 多进程；先做单机优化。

**进度（2026-10-27）：已完成。** 网格/列表 >120 本虚拟化、封面 LRU + 并发去重、`npm run bench:library` 5000 本基准（首屏 947ms、网格 p95 19.3ms、列表 6.7ms、搜索 3.8ms）；`book:importProgress` 逐文件进度/错误汇总；`library.coverCacheSize` 50-1000 可配置。commit `5dd4468` / `62106d1`。

## PB-018 · 关键路径 E2E

**目标**：关键链路回归自动化，CI 能拦住「构建过但功能坏」。

**MVP 验收**
- [x] 干净构建 smoke：启动 → 导入 EPUB / PDF → 打开 → 搜索 → 退出
- [x] Z-Library 下载 → 导入（网络可用时或 mock）
- [x] 翻译请求（mock provider）/ TTS 启动（SAPI 可用时）
- [x] 备份 → 恢复 → 数据一致
- [x] schema 迁移样例
- [x] 在 CI（Windows runner）跑；失败附日志 / 截图

**非目标**：全量 UI 覆盖；先覆盖 5–8 条关键路径。

**进度（2026-10-27）：已完成。** E2E 16 条 smoke（`be52dbe`/`d990a19`/`3db7d31`/`ceddc7b`/`21b3719`），本机 16/16 PASS（12.8s）：启动/桥接/导入/阅读/单实例/TTS/备份/翻译 Key/schema/搜索/PDF/Z-Library API/翻译 mock/Z-Library 合成事件/真实下载→导入（本地 HTTP mock）。同时修复 `BridgeServer::InvokeMethod` 只查同步表导致下载后 import 永失败的回归。


## PB-019 · 单实例 + 文件打开转发

**目标**：同一时间只允许一个 ParticleBook 进程；第二次启动把文件路径转给已运行实例。

**MVP 验收**
- [x] 命名 mutex（Local\ParticleBook.SingleInstance）；第二次启动不进入主流程
- [x] 命名管道或 WM_COPYDATA 转发路径（可多个），支持「导入」/「打开」两种动作
- [x] 第一个实例收到后恢复窗口并导入/打开
- [x] 同一 DB 无并发写；退出时转发端不挂起
- [x] PB-012 文件关联之前完成

**非目标**：多窗口 / 标签页。

**进度（2026-10-26）**：已完成（commit `9e5943d`）；手工验证二次启动 108ms 退出、主实例收到 `app:openFiles` 事件、仅一个进程。

## PB-020 · UiDispatcher + JobService + 长任务迁移

**目标**：长任务不再占用 WebView2 UI 线程；所有后台事件统一回 UI 线程。

**MVP 验收**
- [x] UiDispatcher：单一 WM_APP 消息 + std::function 队列，替代散落的 WM_USER+N 与裸指针 LPARAM
- [x] JobService：submit/cancel/progress；worker 池；事件 job:progress / job:done / job:error
- [x] 迁移：book:import、file:read（大文件/MOBI）、pdf:open/render/extractText、bookSource:search/searchOne/getBookInfo/getChapterList
- [x] 长任务 handler 立即返回 jobId；UI 不阻塞
- [x] Shutdown 先停 JobService/join，再拆 bridge/webview/db
- [x] 为 TTS/翻译/备份/索引预留同一套 job 接口

**非目标**：多进程 worker；先做单进程线程池。

**进度（2026-10-26）：已完成。** 平台核心 `6d9dc58`；UiDispatcher/JobService 接线 `fecad16`；pdf:open `fecad16`、pdf:renderPage/extractText、bookSource:search/searchOne/getBookInfo/getChapterList `e60e933`、file:read `e022aa2`、book:import `ff7ffc9` 全部异步化。CDP 实测同步桥接、PDF 打开/渲染/抽取、readFile、importBooks 均正常；ctest 9/9。

> 踩坑：改 `WebViewHost.h` 后未全量重编，出现桥接消息断流/多重定义；按 AGENTS 规则删 `*.obj` 全量重建后恢复。

> 踩坑：改 `WebViewHost.h` 的构造函数后未全量重编，出现桥接消息断流 / 多重定义；按 AGENTS 规则删 `*.obj` 全量重建后恢复。

## PB-021 · Locator + Annotation 统一模型

**目标**：给书签、高亮、笔记、PDF 标注、TTS、搜索提供统一的「书内位置」表示。

**MVP 验收**
- [x] Locator JSON（带 version/kind）：epub cfi / text offset / html anchor / pdf page+归一化 rect / scroll percent
- [x] Annotation 统一：bookmark / highlight / note / pdf-annot；id、book_id、locator、text、color、note、created_at、updated_at
- [x] 旧数据迁移：cfi、page、pos-<offset>、scrollPosition 映射到 Locator（配合 PB-014）
- [x] ReaderAdapter 用 Locator 读写位置；导出/搜索/TTS 复用
- [x] PDF 页内矩形可存取；缩放/换页不丢

**非目标**：手写笔迹；先做文本/矩形标注。

**进度（2026-10-27）：已完成。** Locator/Annotation（`3839b27`）+ schema v3（`8a017be`）+ CRUD/双写（`b30877a`）+ renderer store（`2ed55a6`）+ 标注侧栏（`b6b14af`）+ PDF 页内 rect/文本层（`c7e8844`/`2b54943`）+ ReaderAdapter 统一位置/选区/跳转/标注（`2f1d6e0`/`46ba5b2`）。TTS/翻译/词典/搜索均接 adapter。

## PB-022 · ReaderAdapter + 共享阅读交互

**目标**：5 个渲染器不再各写一套搜索/进度/高亮；TTS/翻译/词典只对接一层。

**MVP 验收**
- [x] ReaderAdapter 接口：getLocator / goTo / getSelection / getTextModel / search / applyAnnotation / removeAnnotation
- [x] 共享 SelectionToolbar、SearchController、ProgressController
- [x] EPUB / TXT / HTML 先接；PDF / Comic 后续
- [x] TTS/翻译/词典/导出只依赖 adapter，不按格式分支
- [x] 现有阅读行为不回退

**非目标**：重写渲染器；保留各格式渲染内核。

**进度（2026-10-27）：已完成。** ReaderAdapter 接口 + EPUB/TXT/HTML 适配；共享 SelectionToolbar（翻译/词典/朗读/高亮）、SearchController、ProgressController；TTS/翻译/词典统一走 adapter 选区；E2E 16/16 无回归。导出/搜索部分能力已复用，剩余格式映射见 PB-006。

## PB-023 · Settings + SecretStore

**目标**：设置有了 typed schema；第三方 Key 安全存储，不进 settings/备份/日志。

**MVP 验收**
- [x] Settings schema：全局/书级、默认值、校验、版本迁移
- [x] SecretStore：Windows DPAPI 加密；只存 provider key
- [x] settings 只存 provider id / base URL / model；备份与日志不含 Key
- [x] 渲染层由 schema 驱动表单；GlobalSettings 按页拆分
- [x] TTS/翻译/词典/备份的设置项可增量加 schema，不再加散落字段

**非目标**：系统凭据管理器集成（可后续）。

**进度（2026-10-26）：已完成。** SecretStore DPAPI（`6d9dc58`）；SettingsService 33 键 schema（`e87c766`）；DbHandlers 归一化；App SecretStore + 翻译 Key（`23fd60b`）；schema 驱动高级设置页（`91b8f92`，26 行/7 分组/即改即存/恢复默认）。settings 不存 Key。剩余：语言/fontFamily 等专门控件可继续合并（非阻塞）。

## PB-024 · god files 拆分 + 注入 JS 外置

**目标**：把 1000+ 行文件和 App.cpp 里的注入 JS 拆成可维护单元；纯重构，不改行为。

**MVP 验收**
- [ ] 抽 HttpClient / DownloadManager / ImportService / UpdateService / MirrorService
- [ ] ZLibraryService / BookSourceService / FileHandlers / WebViewHost 按职责收缩
- [ ] 注入 JS 移到 resources/zlib_toolbar.js、resources/download_card.js，使用 __pbLang + CSS 变量
- [ ] GlobalSettings / ReaderView / EpubRenderer 拆到可读大小（按功能改到哪拆到哪）
- [ ] 重构前后构建、测试、关键路径 smoke 通过

**非目标**：一次性全部拆完；不追求「每文件 < 300 行」的形式指标。

**进度（2026-10-27）**：注入 JS 外置（`resources/zlib_toolbar.js`、`download_card.js`，缺失回退+WARN）；ZLibraryService 拆为 Service/Mirror/Download 3 TU + Internal.h；FileHandlers 拆出 Update；FileHandlers 1247→1079。剩余：ReaderView/EpubRenderer/GlobalSettings 拆分、FileHandlers 导入段、BookSourceService/WebViewHost 收缩；installer.nsi 需确认 resources。commit `1fe15e4`。

## PB-025 · 设计基元 + 主题审计

**目标**：新 UI（TTS 控制条、翻译/词典弹层、标注侧栏）不再重复主题 bug。

**MVP 验收**
- [x] shared/ui 基元：Popover / Dialog / Toolbar / Switch / Field / List
- [x] 颜色只走 CSS token；移除 ReaderView 加载页 bg-gray-900 等硬编码深色
- [x] 亮色 / 暗色 / 护眼三主题走查：新弹层、菜单、tooltip、空状态
- [x] 键盘焦点、ESC 逐层关闭、aria 名称纳入基元
- [x] issue #6 同类问题有回归检查

**非目标**：整体视觉重设计。

**进度（2026-10-27）：已完成。** `components/UI` 新增 Switch/Field/Toolbar/Popover/Dialog/List + `--ui-*` token + 焦点环/reduced-motion；`docs/THEME_AUDIT.md` 审计；ReaderView 加载页/移除按钮/spinner token 化 + Dialog 接线，TextRenderer 高亮弹层 token 化，DictionaryPopup 清 fallback，Settings Toggle 换 Switch；三主题真机走查 11/11。commit `78633d0` / `a116c5d`。


## 待排期

- （暂无；用户口述后追加）

## 已完成

| 事项 | 完成时间 | 提交 / 版本 |
|---|---|---|
| issue #6 亮色设置页文字不可读 | 2026-10-25 | `27d9dcd`（未发版） |
| PB-019 单实例 + 文件打开转发 | 2026-10-26 | `9e5943d`（未发版） |
| PB-020 UiDispatcher + JobService + 长任务迁移 | 2026-10-26 | `fecad16` / `e60e933` / `e022aa2` / `ff7ffc9`（未发版） |
| PB-014 数据 schema 版本 + 迁移 | 2026-10-26 | `87331a0`（未发版） |
| PB-015 桥接契约扩展 | 2026-10-26 | 既有 `02c62a5` + contract/dts/handler/usage 审计全绿（未发版） |
| PB-012 文件关联 / 打开方式 | 2026-10-26 | `444583b`（未发版） |
| PB-016 本地诊断日志 | 2026-10-26 | `a7c49d9`（未发版） |
| PB-011 备份 / 恢复（MVP） | 2026-10-26 | `ea2308a` / `9475e3c` / `24de96c` / `5019267`（未发版） |
| PB-004 笔记 / 高亮导出 | 2026-10-26 | `c903980` / `4d4bb15` / `655d2d1` / `6d11a5d` / `2655f6c`（未发版） |
| PB-023 Settings + SecretStore | 2026-10-26 | `6d9dc58` / `e87c766` / `23fd60b` / `91b8f92`（未发版） |
| PB-010 本地词典 | 2026-10-26 | `bfc4fd2` / `84f68ea` / `0a1e9fb`（未发版） |
| PB-005 元数据 / 标签 / 系列 / 评分 | 2026-10-26 | `53a747f`（未发版） |
| PB-007 统计升级 | 2026-10-26 | `1727fb8`（未发版） |
| PB-013 书源生态增强 | 2026-10-26 | `37648a9` / `ba02f31`（未发版） |
| PB-003 PDF 标注 | 2026-10-27 | `2b54943`（未发版） |
| PB-017 大书库性能 | 2026-10-27 | `5dd4468` / `62106d1`（未发版） |
| PB-025 设计基元 + 主题审计 | 2026-10-27 | `78633d0` / `a116c5d`（未发版） |
| PB-018 关键路径 E2E | 2026-10-27 | `21b3719`（未发版） |
| PB-021 Locator + Annotation 统一模型 | 2026-10-27 | `2b54943` / `46ba5b2`（未发版） |
| PB-022 ReaderAdapter + 共享阅读交互 | 2026-10-27 | `46ba5b2`（未发版） |
| PB-001 TTS 朗读 | 2026-10-26 | `ea2308a` / `444583b` / `8a1e4f2` / `8f149ce` / `fe1c5dc`（未发版） |

## 暂缓 / 否决

| 事项 | 原因 | 日期 |
|---|---|---|
| issue #7 安卓适配 | 需单独立项；方向待定 | 2026-10-25 |
