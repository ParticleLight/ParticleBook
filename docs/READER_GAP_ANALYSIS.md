# 阅读器竞品与差距分析

> 调研日期：2026-10-25。对象：ParticleBook 2.2.0（2.2.1 开发中）与主流桌面 / 移动阅读器。
> 方法：官方 README、官网、应用商店页 + 本项目代码核对。只把有公开资料支持的能力写进结论；未验证项标注「未验证」。
>
> 结论先行：我们的独有优势是「内容获取闭环」（内置 Z-Library + Legado 书源规则）与「原生轻量 / 本地优先 / 安全」。差距集中在五块：听与查（TTS、翻译、词典）、带走（同步/备份、笔记导出）、PDF 标注、书库管理深度、无障碍与 AI；移动端单独立项。

## 1. 竞品速览

| 产品 | 平台 | 技术栈 | 公开资料里的核心强项 | 对我们的启示 |
|---|---|---|---|---|
| Readest | Windows / macOS / Linux / Android / iOS / Web | Tauri v2 + React + foliate-js / PDF.js | 跨平台同步、TTS（含离线音色）、DeepL/Yandex 翻译、词典（Yomitan）、OPDS/Calibre、分屏并行阅读、无障碍、阅读尺/速度阅读、网页剪藏 | 最接近的现代全能型对手；免费开源，功能面是主要对标 |
| Koodo Reader | Windows / macOS / Linux / Android / iOS / Web | Electron | 多网盘同步（OneDrive/Drive/Dropbox/WebDAV/S3 等）、AI 翻译/词典/摘要、笔记导出（CSV/MD/HTML/TXT/PDF）与 Readwise/Notion/Obsidian 集成、OCR、50+ 插件、OPDS、竖排、版本快照 | 功能深度与生态最全；但 Electron 体积/内存是我们的机会 |
| Anx Reader | Windows / macOS / iOS / Android | Flutter | AI 助手（摘要/思维导图/AI 词典与翻译）、WebDAV 同步、笔记工作台（多色/导出/分享卡片）、阅读热力图、TTS 多音色、全书对照翻译 | AI + 笔记 + 移动端组合最完整 |
| Thorium Reader | Windows / macOS / Linux / iOS | Electron + Readium | 无障碍（JAWS/NVDA/Narrator/VoiceOver）、LCP DRM、OPDS、EPUB/PDF/CBZ/有声书、28+ 语言 | 无障碍与标准合规标杆 |
| Foliate | Linux（GTK） | GJS / GTK | 简洁、单/双栏/连续滚动、脚注弹层、Wiktionary/Google 翻译、基础 TTS、书签注解 | EPUB 阅读体验参考 |
| KOReader | 电子墨水设备 / Android / Linux | Lua | PDF/DjVu/CHM 等广泛格式、扫描 PDF 重排（K2pdfopt）、StarDict 词典、Calibre/OPDS、插件、统计 | PDF 重排与电子墨水体验参考 |
| Moon+ Reader Pro | Android | 原生 | PDF 高亮/批注/手写/表单、TTS、词典翻译、手势、云同步、双页、阅读尺、蓝光过滤 | Android 上 PDF 标注体验标杆 |
| Legado（阅读） | Android | Kotlin | 书源规则生态（XPath/JSON/JS）、TTS、WebDAV 备份与书籍同步、本地导入、RSS | 书源规则生态的源头；我们已兼容其 JSON 格式 |
| Calibre | Windows / macOS / Linux | Python / Qt | 元数据抓取与编辑、20+ 格式转换、内容服务器 / OPDS、全文检索、插件 | 书库管理标杆，适合集成而非替代 |
| 微信读书 / Kindle / Apple Books | 全平台 | 商业 | 正版内容、跨端同步、AI 导读/问书、TTS/有声书、X-Ray/词典/翻译 | 生态型产品；不是同赛道，但抬高了用户对同步 + 听书 + 查词的预期 |

## 2. 能力差距（只列有公开资料支持的能力）

| 能力 | ParticleBook | 有该能力的竞品（公开资料） | 影响 |
|---|---|---|---|
| TTS 朗读 | 无 | Readest、Koodo、Anx、Moon+、Legado、Thorium、Foliate（基础） | 2026 年阅读器标配；没有它，通勤/睡前场景直接流失 |
| 翻译 / 词典 | 无 | Readest、Koodo、Anx、Moon+、Foliate、KOReader | 外文书（Z-Library 上大量英文书）读起来门槛高 |
| 跨设备同步 / 云备份 | 无 | Readest、Koodo、Anx（WebDAV）、Moon+（Dropbox/WebDAV）、Legado（WebDAV）、BookFusion、Neat Reader | 换机/重装即丢书库与进度；也是多设备使用的前提 |
| PDF 标注 | 无 | Moon+（高亮/批注/手写/表单）、BookFusion（图片/区域标注）；Koodo/Readest 宣称标注但公开资料未细分 PDF | 我们支持 PDF 阅读与搜索，但不能标注，PDF 学习用户会走 |
| 笔记 / 高亮导出 | 无 | Koodo（CSV/Markdown/HTML/TXT/PDF + Readwise/Notion/Obsidian/Joplin）、Anx（TXT/Markdown/CSV + 分享卡片） | 用户投入的标注被锁在应用里；也无法进入知识管理流程 |
| 书库管理深度（元数据/标签/系列/评分） | 部分：只有书柜 + 排序 + 标题/作者搜索，详情只读 | Calibre（元数据抓取/编辑/系列/标签）、Koodo、Moon+、BookFusion（系列/标签/自定义列） | 书一多就难找；Calibre 用户无法平滑迁移 |
| 元数据编辑 / 自动抓取 | 无 | Calibre（下载并编辑元数据）、BookFusion（同步元数据/系列/标签） | 封面/作者/简介错了只能忍 |
| OPDS / Calibre 内容服务器 | 无 | Readest、Koodo、Thorium、KOReader、Moon+、Calibre | 接入现有 Calibre 书库/公共书库的入口缺失 |
| 跨书全文搜索 | 无：只有书内搜索 + 标题/作者搜索 | Readest（当前书架内搜索）、Calibre（全文检索） | 「我记得书里那句话」场景无解 |
| 阅读统计 | 部分：总量/单书阅读时长与进度 | Anx（日/周/月/年 + 热力图）、Koodo、KOReader、Moon+ | 缺少习惯反馈，留存弱 |
| AI 助手 | 无 | Anx（摘要/思维导图/问书）、Koodo（AI 翻译/词典/摘要/百科）、微信读书（AI 导读/问书）；Readest 计划中 | 正在从加分项变成新卖点；可用 BYOK 低成本跟进 |
| 无障碍 | 部分：有零散 aria-label，未做系统审计 | Thorium（JAWS/NVDA/Narrator/VoiceOver）、Readest（键盘导航 + 屏幕阅读器） | 键盘/读屏用户无法完整使用；也是产品质量问题 |
| 双栏 / 分屏 / 竖排 | 无：epub.js 当前 spread=none，无竖排 | Koodo（双栏/竖排）、Readest（分屏并行）、Foliate/KOReader（双栏） | 大屏与漫画/日文用户受限 |
| 插件 / 扩展 | 无 | Koodo（50+ 插件）、KOReader（插件） | 长尾需求只能等官方排期 |
| 格式广度 | 部分：缺 AZW3/AZW、DOCX、DjVu、CHM、ODT/RTF、CBT/CB7 | Readest、Koodo、Moon+、KOReader、Foliate | Kindle 用户导入即碰壁；DOCX/DjVu 是长尾 |
| 移动端 | 无 | Readest、Koodo、Anx、Thorium、Moon+、Legado、微信读书、Kindle、Apple Books | issue #7；不是补丁级工作，需单独立项 |
| 文件关联 / 打开方式 | 无：安装包未注册文件关联 | Readest（一键打开/打开方式） | Windows 用户双击书文件无法直接进应用 |
| 界面多语言 | 部分：中文 / English | Koodo（40+）、Thorium（28+）、Readest（多语言社区） | 海外用户门槛；优先级低于功能 |
| 有声书 / 媒体覆盖 / 跟读 | 无 | Readest（有声书 + EPUB Media Overlays 跟读）、Thorium（有声书） | 属于下一阶段能力 |

> 说明：竞品「未列」不代表不支持，只代表本次未找到公开证据；「部分」= 已支持但不完整。

## 3. 我们的优势（不要为了追功能丢掉）

1. 内容获取闭环，竞品没有：内置 Z-Library 浏览器 + 镜像探测 + 下载/导入书架，是获客与留存的核心。
2. Legado 书源规则兼容：桌面端少见；多源搜索、下载、组装、导入一条链。可继续加深（规则编辑/校验/导入 URL/QR、替换规则、RSS）。
3. 原生轻量：C++ Win32 + WebView2，安装包约 25 MB、启动小于 1 秒、内存约 100 MB（项目自述）。对比 Electron（Koodo/Thorium）、Flutter（Anx）、Tauri（Readest）是明确差异点。
4. 安全与稳健：更新 SHA-512 + URL 白名单、阅读内容消毒、桥接来源校验、JSON 数据库原子写 + 损坏备份。竞品很少把这条讲清楚。
5. 本地优先 / 无账号：书库与进度在本机，不上传；适合隐私敏感用户。
6. 中文优先 + 每本书独立排版设置 + 阅读统计：对中文用户比国际化大而全更顺手。

## 4. 建议优先级与路线

### P0：阅读闭环（建议 2.3）

1. TTS 朗读：系统音色优先（WebView2/Chromium speechSynthesis 或 C++ SAPI），支持语速、暂停、睡眠定时、按段高亮跟读。先验证中文音色可用性。
2. 翻译 + 词典：选中文本 → 弹层翻译/查词；支持用户配置的翻译服务（DeepL/Google/LLM），结果缓存；离线词典（StarDict/MDX）作为后续。
3. 笔记 / 高亮导出：Markdown / CSV / JSON，支持单本与全库；后续可加 Readwise/Notion。
4. PDF 标注 MVP：高亮/下划线/文字笔记，按页 + 百分比坐标持久化，缩放/换页不丢；先不做手写/表单。
5. WebDAV 备份/恢复：先做「快照备份 + 手动恢复」，再做进度同步；复用现有 WinHTTP，不引入账号体系。

### P1：书库与生态（建议 2.4）

6. 元数据编辑 + 标签/系列/评分；支持从 Calibre 导入元数据。
7. OPDS / Calibre 内容服务器：浏览、搜索、下载、导入。
8. 跨书全文搜索：导入时建索引；可先用独立索引文件，避免动 JSON 主库。
9. 统计升级：日/周/月热力图、连续阅读、目标。
10. 格式补齐：AZW3/AZW、DOCX 优先；DjVu/CHM 视需求。
11. 无障碍审计：键盘焦点、ARIA、屏幕阅读器（NVDA）、高对比/大字号。
12. 排版：双栏、竖排、翻页动画（epub.js 的 spread 可先放开）。

### P2：中长期（2.5 / 3.0）

13. AI 助手（BYOK）：章节/全书摘要、问书、划词解释；用户自带 API Key，默认关闭，明确隐私提示。
14. Android 客户端（issue #7）：单独立项，复用 React 渲染层 + 桥接契约，Kotlin 实现服务层；不要试图移植 C++。
15. 插件系统：先做书源/词典/翻译的插件接口，再考虑第三方生态。
16. 有声书 / Media Overlays / 跟读：等 TTS 稳定后再做。

### 版本建议

| 版本 | 主题 | 内容 |
|---|---|---|
| 2.2.1 | 稳定 | issue #6（已修）、#7 方向决策；不塞大功能 |
| 2.3 | 阅读增强 | TTS、翻译/词典、笔记导出、PDF 标注 MVP、WebDAV 备份 |
| 2.4 | 书库与生态 | 元数据/标签/系列、OPDS/Calibre、跨书搜索、统计、AZW3/DOCX、无障碍、双栏/竖排 |
| 2.5 / 3.0 | 多端与 AI | Android 客户端、AI BYOK、插件、有声书 |

## 5. 实现路径参考

- 格式引擎：Readest 基于 foliate-js（MIT）+ PDF.js（Apache-2.0），覆盖 EPUB/MOBI/AZW3/FB2/CBZ；可评估用它替换/补齐自研渲染层。DOCX 可用 mammoth.js。注意不要复制 AGPL 项目代码。
- TTS：优先 Chromium speechSynthesis（Windows 走系统音色）；不足时 C++ ISpVoice；在线 Edge TTS 作为可选增强。跟读高亮可用 epub.js CFI / 文本节点映射。
- 翻译/词典：C++ 侧代理请求（绕开 CORS/CSP），API Key 存本地设置；缓存译文；默认关闭。
- 同步/备份：WebDAV 快照（zip：数据库 + 设置 + 可选封面）；冲突先「保留双方 + 时间戳」；后续再做增量进度。
- PDF 标注：WebView2 内覆盖 canvas 层，存 page / x% / y% / w% / h% / color / note；渲染时按当前缩放换算；后续再接 MuPDF 原生标注。
- 导出：直接读现有 highlights/notes 表，生成 Markdown 模板（书名/作者/章节/原文/笔记/颜色/时间）。
- OPDS：Atom XML + 下载队列；Calibre 内容服务器用其 OPDS 端点，避免自己实现 Calibre 协议。
- 跨书搜索：导入时抽取纯文本建倒排索引；大库可用 SQLite FTS5（需引入 sqlite，当前是 JSON 文件库）。
- AI：BYOK（OpenAI 兼容接口），只发送用户选中的章节/段落；默认关闭并提示数据出境。
- 无障碍：先跑 NVDA + 纯键盘走查，修焦点顺序/可访问名称/对比度；再做高对比主题与字号缩放。

## 6. 风险与取舍

- 不要变成 Electron 全能型：我们的差异是轻量、原生、内容获取；每加一个功能都要问「是否破坏启动速度/内存/安全」。
- 网络功能默认关闭：翻译、TTS 在线音色、AI、同步都涉及隐私/延迟；本地优先 + 可选开启。
- Z-Library 的法律风险不变：不引入 DRM 绕过；保持「本地阅读器 + 内容入口」的边界。
- PDF 标注复杂度高：先做高亮/笔记 MVP，手写/表单/签名不进近期。
- 移动端是新产品：不要在半移植状态发布；按独立客户端排期。
- 数据迁移：JSON 库改 schema 前先备份 + 版本号，避免老用户升级丢数据。

## 7. 来源

- Readest README：https://github.com/readest/readest
- Koodo Reader README：https://github.com/koodo-reader/koodo-reader
- Anx Reader README：https://github.com/Anxcye/anx-reader
- Thorium Reader 官网：https://www.thoriumreader.com/
- Foliate README：https://github.com/johnfactotum/foliate
- KOReader README：https://github.com/koreader/koreader
- Moon+ Reader Pro（Google Play）：https://play.google.com/store/apps/details?id=com.flyersoft.moonreaderp
- Legado 仓库：https://github.com/LegadoTeam/legado
- Calibre About：https://calibre-ebook.com/about
- 微信读书：https://weread.qq.com/
- Neat Reader：https://neatreader.github.io/
- Aquile Reader（Google Play）：https://play.google.com/store/apps/details?id=com.optimiliastudios.aquilereader
- BookFusion（App Store）：https://apps.apple.com/app/bookfusion/id1141834096
- Kindle（App Store）：https://apps.apple.com/app/id302584613

> 本文是产品分析，不是承诺排期；优先级需结合 2.2.1 的 issue 处理结果与你的产品判断再定。
