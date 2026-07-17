# ParticleBook

**内置 Z-Library 的轻量级电子书阅读器** — C++ Win32 原生应用 + WebView2 前端，安装包仅 ~25MB（Electron 版 ~150MB）。

## 为什么选择 ParticleBook？

- **极致轻量**：C++ 原生编译，启动速度 3x+，内存占用仅为 Electron 版的 1/3
- **内置 Z-Library**：无需浏览器，应用内直接浏览、搜索、下载 Z-Library 书籍，一键导入书架
- **全格式覆盖**：EPUB、PDF、MOBI、TXT、FB2、CBZ/CBR、HTML、Markdown
- **全书全文搜索**：8 种格式全支持，EPUB 跨章节索引，PDF 原生文本提取

## 支持格式

| 格式 | 引擎 | 说明 |
|------|------|------|
| EPUB | epub.js | 目录、高亮、全书搜索 |
| PDF | MuPDF (mutool) | 原生 C 引擎渲染、文本提取搜索 |
| MOBI | MuPDF | 自动编码修复（Latin-1→UTF-8） |
| TXT | 原生 | 目录提取、字符偏移高亮 |
| FB2 | 原生 | XML 解析渲染 |
| CBZ / CBR | JSZip | 漫画连续阅读 |
| HTML | 原生 | 网页渲染 |
| Markdown | markdown-it | 实时渲染 |

## 功能特性

**Z-Library 深度集成**
- 内置 Z-Library 浏览器（WebView2 原生渲染）
- 悬浮工具栏：前进/后退/刷新/线路切换
- 一键下载自动导入书架，下载进度实时显示
- 动态镜像获取 + 域名过滤
- 下载位置自定义

**书架管理**
- 网格 / 列表视图，多维度排序
- 拖放导入、自定义书柜分组
- 右键"详情"查看书籍完整元数据

**阅读功能**
- 全书文本搜索（Ctrl+F），底部搜索栏实时高亮
- 目录导航（DOM Range API 像素级定位）
- 书签、多色高亮标注、笔记
- 阅读进度自动保存、阅读时间统计
- 鼠标滚轮翻页

**个性化**
- 深色 / 浅色 / 护眼三种主题
- 字体、字号、行距、边距自由调节
- 每本书可独立设置

**在线书源**
- 兼容 Legado 书源格式
- 多源并发搜索、一键下载

## 快捷键

| 按键 | 功能 |
|------|------|
| `←` / `→` | 上一页 / 下一页 |
| `Ctrl+F` | 全书搜索 |
| `Enter` / `Shift+Enter` | 下一个 / 上一个搜索结果 |
| `Esc` | 关闭搜索 / 返回书架 |
| `B` | 添加书签 |
| `+` / `-` | 缩放（PDF） |
| `Ctrl + 滚轮` | 缩放（PDF） |
| `Space` | 下一页（PDF / 漫画） |

## 技术栈

| 技术 | 用途 |
|------|------|
| C++17 (MSVC) | 原生应用核心 |
| Win32 API | 窗口管理、原生对话框 |
| WebView2 (Edge/Chromium) | 前端渲染引擎 |
| MuPDF (mutool) | PDF 渲染、MOBI 转换、文本提取 |
| WinHTTP | Z-Library 下载 |
| React 19 + TypeScript | 前端 UI |
| Zustand | 状态管理 |
| Tailwind CSS | 样式 |
| Vite + electron-vite | 前端构建 |
| epub.js | EPUB 渲染 |
| JSZip | CBZ/CBR 解压 |
| JSON 文件数据库 | 数据持久化（%APPDATA%/particle-book/） |

## 开发

> **本项目现为 C++ Win32 + WebView2 原生应用，Electron 版已废弃并从仓库移除。**
> 不要运行 `npm start` / `npm run dev` —— 那些是已删除的 Electron 入口。
> 启动应用请用 `npm run start:cpp`（直接打开编译好的 ParticleBook.exe）。

```bash
# 安装前端依赖
npm install

# 构建前端（React → out/renderer，C++ 后端会通过 CMake 自动拷贝到 build2/renderer）
npm run build

# 启动 C++ 版应用（需先完成 C++ 构建）
npm run start:cpp

# 一键重建前端 + C++
npm run rebuild:cpp

# C++ 构建（需要 Visual Studio 2022 Build Tools + CMake 3.25+）
cd particlebook-cpp/build2
cmake .. -G "NMake Makefiles" -DCMAKE_BUILD_TYPE=Release
# 在 VS2022 x64 开发命令提示符下（或调用 vcvarsall.bat x64）：
nmake

# 打包安装包（需要 NSIS）
cd particlebook-cpp/scripts
makensis installer.nsi
```

> 历史对比：旧 Electron 版安装包 ~150MB / 启动 3-5 秒；现 C++ 版 ~25MB / 启动 <1 秒 / 内存 ~100MB。

## 项目结构

```
particlebook-cpp/           # C++ 后端
  src/
    main.cpp                # 应用入口
    App.cpp/h               # 应用初始化与生命周期
    WebViewHost.cpp/h       # Win32 窗口 + WebView2 管理
    BridgeServer.cpp/h      # C++ ↔ JS 桥接通信层
    services/
      DatabaseService       # JSON 数据库
      LibraryService        # 书库元数据提取（EPUB/PDF/FB2）
      PdfService            # MuPDF 封装（渲染/文本提取）
      ZLibraryService       # Z-Library 下载/镜像管理
      BookSourceService     # 在线书源
      ContentCache          # 内容缓存
    handlers/               # 桥接方法注册
src/                        # React 前端
  components/
    Library/                # 书架界面
    Reader/                 # 阅读器（6 个渲染器）
    Settings/               # 设置界面
    ZLibrary/               # Z-Library 浏览器
  stores/                   # Zustand 状态管理
  utils/                    # 工具函数
```

## 许可证

MIT
