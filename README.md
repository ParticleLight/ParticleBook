# ParticleBook

**内置 Z-Library 的轻量级电子书阅读器** — C++ Win32 原生应用 + WebView2 前端，安装包仅 ~29MB（Electron 版 ~150MB）。当前版本 **v2.2.1**。

Windows 10/11 x64，需要 WebView2 运行时（Win10/11 一般已预装）。

## 为什么选择 ParticleBook？

- **极致轻量**：C++ 原生编译，启动 3x+，内存占用约为 Electron 版的 1/3
- **内置 Z-Library**：应用内直接浏览、搜索、下载，一键导入书架，下载历史可追溯
- **全格式覆盖**：EPUB、PDF、MOBI、AZW3/AZW、DOCX、TXT、FB2、CBZ/CBR、HTML、Markdown
- **阅读体验完整**：AI 翻译、离线朗读（TTS）、本地词典、标注笔记与导出、双栏 / 分屏 / 竖排
- **全书检索**：书内全文搜索 + 跨书全文搜索（导入即建索引，一次搜遍整个书架）
- **安全加固**：更新下载带 SHA-512 完整性校验 + URL 白名单；外部网页无法调用本地特权方法；阅读内容统一消毒，杜绝恶意电子书注入

## 支持格式

| 格式 | 引擎 | 说明 |
|------|------|------|
| EPUB | epub.js | 目录、高亮、书内全文搜索；宽屏双栏 / 竖排；点击页面两侧翻页 |
| PDF | MuPDF (mutool) | 原生 C 引擎渲染、文本提取搜索；4 色高亮 / 下划线 / 页内文字标注 |
| MOBI | MuPDF | 自动编码修复（Latin-1→UTF-8） |
| AZW3 / AZW | mobitool → EPUB | Kindle KF8 转 EPUB，保留原文件与封面 |
| DOCX | 原生 OOXML | zip + `word/document.xml` 解析，保留内嵌图片 |
| TXT | 原生 | 目录提取、字符偏移高亮 |
| FB2 | 原生 | XML 解析渲染 |
| CBZ / CBR | JSZip | 漫画连续阅读 |
| HTML | 原生 | 网页渲染 |
| Markdown | markdown-it | 实时渲染 |

## 功能特性

**Z-Library 深度集成**
- 内置 Z-Library 浏览器（WebView2 原生渲染），站内搜索、书籍详情、下载全在应用内完成
- 悬浮工具栏：前进 / 后退 / 刷新 / 线路切换 / 退出登录
- 动态镜像获取 + 域名过滤，线路不可达自动换线
- 一键下载自动导入书架，下载卡片实时显示百分比与字节数
- 下载卡死自愈：CDN 长时间无数据（45 秒）自动收尾并清理半截 `.part`，可直接重试
- 下载位置自定义（设置 → Z-Library），冷启动即显示真正生效的目录
- 下载历史：文件名 / 大小 / 下载时间，可打开、跳到所在文件夹、删除记录（可选连同硬盘文件）

**书架管理**
- 网格 / 列表视图，多维度排序，大书库（>120 本）自动虚拟化 + 封面懒加载
- 拖放导入、自定义书柜分组；单实例运行，打开文件时把路径转交给已运行实例
- 元数据管理：编辑书名 / 作者 / 语言 / 出版社 / 简介，新增标签
- 阅读统计独立页面：日 / 周 / 月 / 累计时长、365 天热力图（悬停看当天明细）、连续阅读天数
- 备份与恢复：一键导出 zip（数据库 / 设置 / 标注 / 封面），恢复前自动留底
- 文件关联：安装器注册 ProgID 与「打开方式」

**阅读功能**
- 书内全文搜索（Ctrl+F），底部搜索栏实时高亮
- 跨书全文搜索：导入时自动建索引，一次搜遍书架并跳到书内命中位置
- 目录导航（DOM Range API 像素级定位）
- 书签、多色高亮标注、笔记；笔记 / 高亮 / PDF 标注可导出为 Markdown / CSV / JSON
- 文本朗读（TTS）：离线 SAPI 朗读，可选音色与语速，逐句高亮
- AI 翻译：选中即译（也支持整段），兼容 OpenAI 兼容 / Anthropic Messages / Responses 三类接口
- 本地词典：内置 ECDICT 离线词库，选中单词看音标 / 释义 / 词形 / 考试标签
- 双栏 / 分屏 / 竖排：EPUB 宽屏双栏与竖排；分屏把两本书并排阅读，两栏各自翻页
- 阅读进度自动保存、阅读时长统计；鼠标滚轮翻页、PDF 精确位置恢复

**个性化**
- 深色 / 浅色 / 护眼三种主题 × 强调色（蓝 / 紫 / 绿 / 橙）
- Material 3 语义 token 界面；原生标题栏跟随主题同色
- 窄窗口自适应：宽屏常驻导航抽屉，窄屏收窄，更窄时改为抽屉
- 字体、字号、行距、边距自由调节；每本书可独立设置

**在线书源**
- 兼容 Legado 书源格式，支持 URL / 剪贴板 / 文件（含批量）导入并去重
- 多源并发搜索、一键下载；章节下载后自动组装并导入书架，进度实时显示

**更新与安全**
- 版本号单一来源（CMake），升级不再因漏改版本导致更新检测失效
- 更新自动检查：启动检测 + SHA-512 校验 + 一键下载重启安装
- 桥接消息来源校验：外部 Z-Library 页面不能调用本地特权方法
- 阅读器 HTML / FB2 / Markdown 统一消毒，EPUB 关闭脚本执行
- 数据库原子写 + 损坏自动备份 + 版本化迁移（迁移前自动备份）
- 本地诊断日志，可一键导出诊断包（日志 / 版本 / 系统信息 / 脱敏设置）

## 快捷键

| 按键 | 功能 |
|------|------|
| `←` / `→` | 上一页 / 下一页（EPUB、漫画） |
| `Ctrl+F` | 全书搜索 |
| `Enter` / `Shift+Enter` | 下一个 / 上一个搜索结果 |
| `Esc` | 关闭搜索 / 返回书架 |
| `B` | 添加书签（EPUB） |
| `+` / `-` | 缩放（PDF） |
| `Ctrl + 滚轮` | 缩放（PDF） |
| `Space` | 下一页（漫画） |
| `Home` / `End` | 首页 / 末页（漫画） |

## 技术栈

| 技术 | 用途 |
|------|------|
| C++17 (MSVC) | 原生应用核心 |
| Win32 API | 窗口管理、原生对话框 |
| WebView2 (Edge/Chromium) | 前端渲染引擎；Z-Library 下载由 Chromium 完成，应用只接管落盘位置 |
| MuPDF (mutool) | PDF 渲染、MOBI 转换、文本提取 |
| mobitool | AZW3 / KF8 → EPUB 转换 |
| WinHTTP | 镜像探测、更新下载 |
| nlohmann/json | C++ JSON 序列化 |
| tinyxml2 | FB2 XML 解析 |
| miniz / ZipReader | 书库元数据提取（封面/目录）、DOCX OOXML 解析 |
| React 19 + TypeScript | 前端 UI |
| Zustand | 状态管理 |
| Tailwind CSS | 样式 |
| Vite | 前端构建（vite.config.ts，纯 Vite，无 Electron） |
| epub.js | EPUB 渲染 |
| JSZip | CBZ/CBR 解压（漫画阅读） |
| markdown-it | Markdown 渲染 |
| DOMPurify | 阅读内容消毒（XSS 防护） |
| Windows SAPI | 离线 TTS 朗读 |
| ECDICT | 离线词典词库 |
| JSON 文件数据库 | 数据持久化（%APPDATA%/particle-book/，原子写入 + 损坏备份） |

## 开发

> **本项目现为 C++ Win32 + WebView2 原生应用，Electron 版已废弃并从仓库移除。**
> 不要运行 `npm start` / `npm run dev` —— 那些是已删除的 Electron 入口。
> 启动应用请用 `npm run start:cpp`（直接打开编译好的 ParticleBook.exe）。

```bash
# 安装前端依赖
npm install

# 构建前端（React → out/renderer）
npm run build

# 启动 C++ 版应用（需先完成 C++ 构建）
npm run start:cpp

# 一键重建前端 + C++
npm run rebuild:cpp

# 打包安装包（自动用 package.json 的版本号传入 NSIS；需要 makensis 在 PATH）
npm run package

# C++ 构建（需要 Visual Studio 2022 Build Tools + CMake 3.25+）
cd particlebook-cpp/build2
cmake .. -G "NMake Makefiles" -DCMAKE_BUILD_TYPE=Release
# 在 VS2022 x64 开发命令提示符下（或调用 vcvarsall.bat x64）：
nmake

# 手动打包安装包（需要 NSIS，用 /DPRODUCT_VERSION= 传入版本号）
cd particlebook-cpp/scripts
makensis /DPRODUCT_VERSION=2.2.1 installer.nsi
```

提交前建议跑一遍校验：

```bash
npx tsc --noEmit                      # 前端类型
npm run build                         # 前端构建
npm run i18n:check                    # i18n key 覆盖
npm run check:changelog               # 更新日志翻译齐全
node scripts/check-bridge-contract.js # 桥接契约（TS ↔ C++）
node scripts/check-version-sync.js    # 三处版本号同步
ctest --test-dir particlebook-cpp/build2 --output-on-failure
```

> 历史对比：旧 Electron 版安装包 ~150MB / 启动 3-5 秒；现 C++ 版 ~29MB / 启动 <1 秒 / 内存 ~100MB。

## 项目结构

```
particlebook-cpp/             # C++ 后端
  src/
    main.cpp                  # 应用入口（单实例 + 文件转发）
    App.cpp/h                 # 应用初始化与生命周期
    WebViewHost.cpp/h         # Win32 窗口 + WebView2 管理
    BridgeServer.cpp/h        # C++ ↔ JS 桥接通信层（含来源校验）
    services/
      DatabaseService         # JSON 数据库（原子写入 + 版本迁移 + 损坏备份）
      LibraryService          # 书库元数据提取（EPUB/PDF/FB2/CBZ/DOCX…）
      PdfService              # MuPDF 封装（渲染/文本提取/MOBI 编码修复）
      ZLibraryService         # Z-Library 入口、镜像管理
      ZLibraryDownload        # 下载接管、进度、卡死自愈、下载历史
      ZLibraryMirror          # 镜像探测与线路切换
      BookSourceService       # 在线书源（章节下载→组装→导入）
      SearchIndexService      # 跨书全文索引
      DictionaryService       # 离线词典（ECDICT）
      TranslationService      # AI 翻译（多接口格式）
      BackupService           # 备份与恢复（zip）
      ExportService           # 笔记 / 标注导出
      ContentCache            # 内容缓存
    handlers/                 # 桥接方法注册
      DbHandlers              # db:* 方法（含删除物理文件）
      FileHandlers            # 文件/更新/拖放导入
      FileFormatService       # 格式识别与转换编排
      AnnotationHandlers      # 书签 / 高亮 / 笔记 / PDF 标注
      BookMetadataHandlers    # 元数据编辑
      DictionaryHandlers      # 词典查询
      ExportHandlers          # 导出
      SearchHandlers          # 书内 / 跨书搜索
      TranslationHandlers     # 翻译
      TtsHandlers             # 朗读
      BackupHandlers          # 备份恢复
    utils/
      ZipReader               # zip 解压（元数据、DOCX）
  resources/                  # 注入脚本（zlib_toolbar.js / download_card.js）
  scripts/                    # installer.nsi 等打包脚本
src/                          # React 前端
  components/
    Library/                  # 书架、详情、统计、下载历史、书源、更新日志
    Reader/                   # 阅读器（EPUB/PDF/文本/漫画/HTML）、分屏、朗读、翻译、词典
    Settings/                 # 设置界面（外观/阅读/书架/Z-Library/翻译/关于）
    UI/                       # 通用组件（错误边界、确认框、更新横幅）
  stores/                     # Zustand 状态管理
  data/                       # 更新日志等静态数据
  i18n/                       # 中英文案
```

> Z-Library 页面由 C++ 侧 WebView2 直接承载（`resources/zlib_toolbar.js` 注入悬浮工具栏），不属于 React 前端组件。

## 更新日志

应用内「更多操作 → 更新日志」可查看每个版本的完整条目；历史版本见 [Releases](https://github.com/ParticleLight/ParticleBook/releases)。

## 许可证

MIT
