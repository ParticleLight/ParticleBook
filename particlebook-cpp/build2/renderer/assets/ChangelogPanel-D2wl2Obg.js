import { j as jsxRuntimeExports } from "./index-BwmGFUpy.js";
const changelog = [
  {
    version: "2.0.0",
    date: "2026-05-19",
    changes: [
      { type: "feature", text: "架构重写：从 Electron + Node.js 移植为 C++ Win32 原生应用 + WebView2 前端" },
      { type: "feature", text: "C++ 原生窗口替代 Electron BrowserWindow，启动速度提升 3x+，内存占用减少 60%+" },
      { type: "feature", text: "WebView2 渲染引擎替代 Electron Chromium，安装包从 ~150MB 降至 ~25MB" },
      { type: "feature", text: "全新 C++ ↔ JavaScript 桥接通信层（BridgeServer），替代 Electron IPC" },
      { type: "feature", text: "MuPDF (mutool.exe) 原生引擎替代 pdf.js：PDF 页面渲染、MOBI 文本转换、PDF 文本提取" },
      { type: "feature", text: "虚拟主机映射（particlebook.app → renderer/）：前端资源 + 大文件零 IPC 直接加载" },
      { type: "feature", text: "WinHTTP 原生 HTTP 客户端：Z-Library 下载带 Cookie 转发 + 重定向跟随 + 进度回调" },
      { type: "feature", text: "全书文本搜索：TXT/MOBI/FB2/HTML/MD 基于字符偏移高亮，EPUB 基于 CSS Highlight API + 跨章节全文索引，PDF 基于 mutool 文本提取 + 页码跳转" },
      { type: "feature", text: "Z-Library 悬浮工具栏：前进/后退/刷新 + 线路切换菜单 + 下载进度卡片（4 阶段流水）" },
      { type: "feature", text: "Z-Library 镜像动态获取（z.wwwnav.com）+ 域名黑名单过滤" },
      { type: "feature", text: "Z-Library 弹窗下载拦截：NewWindowRequested → 主窗口导航 → DownloadStarting → WinHTTP 接管" },
      { type: "feature", text: '书籍右键菜单"详情"：弹出元数据面板（书名/作者/ISBN/出版社/简介/文件信息）' },
      { type: "feature", text: "Z-Library 下载位置可自定义：设置页 + 原生文件夹选择对话框" },
      { type: "improve", text: "阅读统计改为独立全屏页面，支持按需生成各类格式封面" },
      { type: "improve", text: "数据库持久化于 %APPDATA%/particle-book/data/reader.json，300ms 防抖写入" },
      { type: "improve", text: "大文件（CBZ/大文本）通过虚拟主机 URL 传输，消除 JSON 字节数组序列化瓶颈" },
      { type: "improve", text: "MOBI 中文编码自动检测修复：MuPDF 默认 Latin-1 → 补丁为 UTF-8" },
      { type: "improve", text: "MOBI/TXT/FB2/HTML/MD 行尾归一化处理（单换行→空格），文字正确回绕填充屏幕宽度" },
      { type: "improve", text: "目录导航改用 DOM Range API 像素级定位，替代百分比估算" },
      { type: "improve", text: '高亮标注改为精确字符偏移存储（cfi: "pos-{offset}"），仅高亮选中位置' },
      { type: "improve", text: "CBZ/CBR 封面在导入时自动生成（mutool 渲染第一页缩略图）" },
      { type: "improve", text: "PDF 页面渲染从 Base64 编码改为 PNG 文件 URL 直传，消除编解码开销" },
      { type: "improve", text: "EPUB 搜索改用 CSS Highlight API（不修改 DOM），消除 epub.js 分页布局偏移" },
      { type: "improve", text: "搜索栏集成到下方悬浮控制面板，搜索时面板强制可见" },
      { type: "fix", text: "修复 MOBI 打开乱码问题（MOBI 编码头补丁 + mutool 文本转换）" },
      { type: "fix", text: "修复 CBZ 导入闪退（路径斜杠混用 + 冗余调用）" },
      { type: "fix", text: "修复 CBZ 打开无限加载（38MB 文件 JSON 序列化瓶颈）" },
      { type: "fix", text: "修复 Z-Library 弹窗下载 503 错误（NewWindowRequested 拦截 + 主窗口重定向）" },
      { type: "fix", text: "修复 Z-Library 下载按钮无响应（m_zlibDlInProgress 标记冲突）" },
      { type: "fix", text: "修复 Z-Library 后退离开界面问题（NavigationStarting 域名守卫）" },
      { type: "fix", text: "修复下载书籍名包含文件路径的问题" },
      { type: "fix", text: "修复书签定位位置不准确（进度计算公式修正）" },
      { type: "fix", text: "修复右键菜单位置与点击位置不对应（改用 clientX/clientY）" },
      { type: "fix", text: "修复管理高亮时位置跳转问题（删除按钮添加 stopPropagation）" },
      { type: "fix", text: "移除多处调试弹窗（EPUB 导入、PDF 打开、崩溃处理等）" },
      { type: "fix", text: "修复 EPUB 搜索后页面左移/遮挡 + 仅限当前章节的问题" },
      { type: "feature", text: "启动自动检测更新（打开 2 秒后查询 GitHub，有新版弹出绿色横幅）" },
      { type: "feature", text: "NSIS 安装包：首次可选安装目录，后续更新自动沿用无需重选" },
      { type: "feature", text: "书源导入功能（Legado JSON 格式）：原生文件对话框 + 自动解析入库" },
      { type: "feature", text: "从 Electron 旧版更新后自动清理残留文件（Chromium DLL / locales / resources）" },
      { type: "fix", text: "修复更新下载 UI 冻结 — URLDownloadToFileW 主线程阻塞改后台线程下载 + PostMessage 通知" },
      { type: "fix", text: '修复更新下载后无提示 — 下载完成绿色横幅显示"立即重启"，批处理等待退出后静默安装' },
      { type: "fix", text: "修复更新检测改用 latest.yml（不消耗 GitHub API 配额，避免限流 403）" },
      { type: "fix", text: "修复 Z-Library 默认线路不可用时工具栏不显示 + 自动切换镜像 + 死循环拦截" },
      { type: "fix", text: "修复 Z-Library 工具栏在非 ZL 页面显示 + 错误页不显示问题" },
      { type: "improve", text: "Z-Library 加载体验优化（移除遮罩，documentElement 挂载工具栏）" }
    ]
  },
  {
    version: "1.8.7",
    date: "2026-05-14",
    changes: [
      { type: "feature", text: "PDF 渲染引擎从 pdf.js 更换为原生 MuPDF（SumatraPDF 同款 C 语言引擎），性能提升 10x+" },
      { type: "improve", text: "PDF 页面直接渲染为 PNG 图片显示，GPU 合成，CPU 占用大幅降低" },
      { type: "feature", text: "缩放功能：Ctrl+/- 和 Ctrl+滚轮，范围 0.5x-4x" },
      { type: "feature", text: "页面宽度自适应铺满，ResizeObserver 监听实时调整" },
      { type: "improve", text: "注册 book-file:// 自定义协议，本地文件直接读取零 IPC 传输开销" },
      { type: "improve", text: "IntersectionObserver 页码追踪、书签、进度条等所有阅读功能正常" },
      { type: "fix", text: "修复 Windows 环境下 ELECTRON_RUN_AS_NODE=1 导致 Electron 无法启动的问题" }
    ]
  },
  {
    version: "1.8.6",
    date: "2026-05-13",
    changes: [
      { type: "improve", text: "UI 全面重构为 Win11 Fluent Design 风格（毛玻璃侧边栏、亚克力浮层、圆角阴影）" },
      { type: "improve", text: "暗色模式改为纯黑灰色调，天蓝强调色" },
      { type: "feature", text: '书柜新增"从全部添加"功能，可批量选取已有书籍加入当前书柜' },
      { type: "fix", text: "导入书籍时自动添加到当前书柜（修复导入后书柜内看不到的问题）" },
      { type: "improve", text: "命令栏简化，搜索框居中放大，次要操作收入更多菜单" },
      { type: "improve", text: "列表视图每行显示封面缩略图，格式标签改为圆角胶囊" },
      { type: "fix", text: "修复 EPUB 阅读器主题切换时颜色不响应 CSS 变量的问题" },
      { type: "improve", text: "Library、BookShelf、Sidebar 改为精确 Zustand selector，减少无效重渲染" },
      { type: "fix", text: "修复 deleteBook 不同步 allBooks、批量添加竞态等数据一致性问题" }
    ]
  },
  {
    version: "1.8.5",
    date: "2026-05-10",
    changes: [
      { type: "fix", text: "修复打开阅读设置面板时滚轮仍触发翻页的问题" },
      { type: "improve", text: '菜单栏"编辑"隐藏，减少干扰' },
      { type: "improve", text: '菜单栏"导入书籍"功能修复，点击弹出文件选择对话框' },
      { type: "improve", text: '菜单栏"关于 ParticleBook"功能修复，点击跳转设置页面' }
    ]
  },
  {
    version: "1.8.4",
    date: "2026-05-10",
    changes: [
      { type: "improve", text: "全面性能优化：所有阅读器组件改用精确 Zustand selector，减少无效重渲染" },
      { type: "improve", text: "ReaderControls 颜色适配主题系统，亮色/暗色/护眼模式统一响应" },
      { type: "fix", text: "修复设置保存无错误处理、margin=0 被错误跳过等稳定性问题" },
      { type: "fix", text: "修复 EPUB 箭头键双重点击翻页的问题" },
      { type: "fix", text: "添加全局错误边界，应用崩溃时显示恢复界面而非白屏" },
      { type: "improve", text: "TypeScript 类型优化：book: any 改为 Book 类型，消除 as any 强制转换" },
      { type: "improve", text: "清理死代码 zipRef、inline opacity 冲突等问题" }
    ]
  },
  {
    version: "1.8.3",
    date: "2026-05-10",
    changes: [
      { type: "feature", text: "启动时自动检查更新，发现新版本顶部弹出绿色提示条，支持下载和一键重启安装" },
      { type: "improve", text: "菜单栏改为中文显示（文件、编辑、视图、帮助）" },
      { type: "fix", text: "修复关闭书籍时阅读进度丢失的严重 bug（新增 flushProgress 立即保存机制）" },
      { type: "fix", text: "修复书籍加载失败时无限转圈的问题（添加异常处理和自动返回）" },
      { type: "fix", text: "修复打开目录/书签侧边栏时滚轮仍触发翻页的问题" },
      { type: "fix", text: "修复更新检查重复发送错误事件、阅读会话重复创建等 9 个稳定性 bug" }
    ]
  },
  {
    version: "1.8.2",
    date: "2026-05-10",
    changes: [
      { type: "fix", text: "修复打开目录/书签侧边栏时滚轮仍触发翻页的问题" }
    ]
  },
  {
    version: "1.8.1",
    date: "2026-05-10",
    changes: [
      { type: "feature", text: "设置→关于新增自动更新功能，支持检查更新、下载、一键重启安装" },
      { type: "feature", text: "更新通过 GitHub Release 托管，零服务器成本" }
    ]
  },
  {
    version: "1.8.0",
    date: "2026-05-10",
    changes: [
      { type: "feature", text: "Z-Library 进入时自动从 z.wwwnav.com 动态获取最新镜像线路，告别手动切换" },
      { type: "feature", text: "Z-Library 工具栏新增原生线路选择菜单，支持一键切换镜像" },
      { type: "improve", text: "Z-Library 切换线路时重建视图，避免连续加载导致的崩溃" },
      { type: "fix", text: "修复数据库写入永不持久化的严重 bug，所有阅读进度、书签等数据重启后丢失" },
      { type: "fix", text: "修复命令注入漏洞，ebook-convert 调用改用安全的 execFile" },
      { type: "fix", text: "修复 local-file:// 协议可读取系统任意文件的安全漏洞" },
      { type: "fix", text: "修复 Markdown 渲染器 XSS 风险，禁用原始 HTML 透传" },
      { type: "improve", text: "阅读进度保存添加 500ms 防抖，减少 IPC 洪泛" },
      { type: "improve", text: "PDF 阅读器卸载时正确清理资源，修复内存泄漏" },
      { type: "improve", text: "书架卡片组件使用 React.memo 缓存，减少不必要的重渲染" },
      { type: "improve", text: "提取重复的封面预览代码为公共工具函数" },
      { type: "improve", text: "构建启用最大压缩，移除未使用的依赖和临时文件" }
    ]
  },
  {
    version: "1.7.0",
    date: "2026-05-08",
    changes: [
      { type: "feature", text: "PDF 阅读器改为连续滚动模式，滚轮自由滚动浏览所有页面" },
      { type: "improve", text: "删除书籍改为弹出确认对话框，替代原来的两次点击确认" },
      { type: "fix", text: "修复 EPUB 滚轮翻页失效的问题，iframe 内滚轮事件正确转发到父窗口" },
      { type: "fix", text: "修复统计面板中 PDF/CBZ 封面不显示的问题" },
      { type: "feature", text: "Z-Library 工具栏新增退出登录按钮，清除登录状态并返回首页" }
    ]
  },
  {
    version: "1.6.0",
    date: "2026-05-08",
    changes: [
      { type: "improve", text: "排除 @napi-rs/canvas 无用原生模块，安装包减少约 37MB" },
      { type: "improve", text: "TXT 渲染器使用 useMemo 缓存解码结果，避免每次渲染重复解码" },
      { type: "improve", text: "TXT/EPUB 滚动保存进度添加 500ms 节流，减少 IPC 调用频率" },
      { type: "improve", text: "PDF 阅读器关闭时正确销毁文档资源，修复内存泄漏" },
      { type: "improve", text: "漫画阅读器改为按需加载图片，只保留当前页 ±2 页在内存中" },
      { type: "improve", text: "JSZip 改为动态导入，减少主包体积" },
      { type: "improve", text: "阅读器主组件使用 Zustand selector 精确订阅，减少每秒不必要的全量重渲染" },
      { type: "improve", text: "书架列表过滤和排序使用 useMemo 缓存，避免重复计算" },
      { type: "improve", text: "数据库写入改为 300ms 防抖批量写入，减少磁盘 IO" },
      { type: "improve", text: "自动清理超过 90 天的已结束阅读会话记录" },
      { type: "fix", text: "修复 EPUB 高亮标注重复添加的问题" },
      { type: "fix", text: "修复亮色模式下多个界面文字不可见的问题" },
      { type: "fix", text: "修复亮色模式下阅读器侧边栏、设置面板颜色异常" },
      { type: "fix", text: "Z-Library 改为全屏独立页面，提升浏览体验" },
      { type: "feature", text: "支持鼠标滚轮翻页（EPUB、CBZ/CBR 格式），滚动间隔 200ms 防抖" },
      { type: "feature", text: "书架工具栏新增刷新按钮，可手动刷新书籍列表" }
    ]
  },
  {
    version: "1.5.0",
    date: "2026-05-08",
    changes: [
      { type: "feature", text: "内置 Z-Library 网页浏览器，支持在应用内直接登录并浏览 Z-Library" },
      { type: "feature", text: "Z-Library 下载的书籍自动导入书架，无需手动操作" },
      { type: "feature", text: "Z-Library 登录状态持久保存，下次打开无需重复登录" },
      { type: "feature", text: "Z-Library 工具栏支持前进、后退、刷新导航操作" }
    ]
  },
  {
    version: "1.4.0",
    date: "2026-05-08",
    changes: [
      { type: "feature", text: "书源管理：支持导入 Legado 格式 JSON 书源配置文件" },
      { type: "feature", text: "在线搜索：通过书源在线搜索书籍，支持多源并发搜索" },
      { type: "feature", text: "在线下载：搜索结果可一键下载到本地书架，支持下载进度显示" },
      { type: "feature", text: "书源管理面板：启用/禁用、删除书源，查看源列表" }
    ]
  },
  {
    version: "1.3.0",
    date: "2026-05-08",
    changes: [
      { type: "improve", text: "渲染器组件懒加载，主包从 2.5MB 降至 651KB，启动速度提升" },
      { type: "improve", text: "排除 pdfjs-dist 冗余资源，减少约 25MB 安装包体积" },
      { type: "improve", text: "排除 epubjs 冗余文件，减少约 2.7MB 安装包体积" },
      { type: "improve", text: "精简 Chromium 语言包，减少约 35MB 安装包体积" },
      { type: "improve", text: "移除未使用的 react-router-dom 依赖" },
      { type: "fix", text: "提取重复代码到公共工具文件，消除多处代码冗余" }
    ]
  },
  {
    version: "1.2.0",
    date: "2026-05-07",
    changes: [
      { type: "feature", text: "高亮和笔记支持点击跳转到对应位置" },
      { type: "feature", text: "添加更新日志面板，记录每次更新内容" },
      { type: "feature", text: '设置中新增"关于"页面，显示软件介绍和版本信息' },
      { type: "improve", text: "降低护眼模式亮度，减少视觉疲劳" },
      { type: "improve", text: "应用正式命名为 ParticleBook" },
      { type: "fix", text: "修复拖拽文件到主界面无法导入的问题（Electron 35 移除了 File.path）" },
      { type: "fix", text: "修复拖拽文件时界面闪烁的问题" },
      { type: "fix", text: "清理残留调试日志" }
    ]
  },
  {
    version: "1.1.0",
    date: "2026-05-07",
    changes: [
      { type: "feature", text: "阅读统计面板：显示所有书籍的阅读进度和阅读时间" },
      { type: "feature", text: "书签支持重命名，双击或点击编辑按钮即可修改" },
      { type: "feature", text: "书签右下角显示位置占全书的百分比，精确到一位小数" },
      { type: "feature", text: "新建书柜改为弹窗输入方式" },
      { type: "fix", text: "修复阅读时间统计始终为0的问题" },
      { type: "fix", text: "修复统计界面中书籍不显示封面的问题" },
      { type: "fix", text: "为TXT格式书籍添加文本预览封面" },
      { type: "fix", text: "修复进入书柜后统计只显示书柜内书籍的问题" },
      { type: "fix", text: "修复TXT阅读器中目录显示另一本书内容的问题" },
      { type: "fix", text: "修复书签重命名后重新打开书籍失效的问题" },
      { type: "fix", text: "修复翻页后书签图标状态不正确的问题" },
      { type: "fix", text: "修复字体加载被CSP拒绝的问题" },
      { type: "fix", text: "修复统计面板中进度显示超过100%的问题" }
    ]
  },
  {
    version: "1.0.0",
    date: "2026-05-06",
    changes: [
      { type: "feature", text: "支持 EPUB、PDF、MOBI、TXT、FB2、CBZ/CBR、HTML、Markdown 格式" },
      { type: "feature", text: "书架管理：书柜分类、拖放导入、搜索排序" },
      { type: "feature", text: "阅读器：目录导航、书签、高亮标注、笔记" },
      { type: "feature", text: "阅读进度自动保存与恢复" },
      { type: "feature", text: "亮色/暗色/护眼三种主题切换" },
      { type: "feature", text: "字体、字号、行距、边距等自定义设置" }
    ]
  }
];
const typeLabels = {
  feature: { label: "新功能", style: { color: "var(--color-green)", backgroundColor: "var(--color-green-bg)" } },
  fix: { label: "修复", style: { color: "var(--color-red)", backgroundColor: "var(--color-red-bg)" } },
  improve: { label: "优化", style: { color: "var(--color-indigo)", backgroundColor: "var(--color-indigo-bg)" } }
};
function ChangelogPanel({ onClose, isClosing }) {
  return /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: `absolute inset-0 z-50 flex items-center justify-center ${isClosing ? "animate-fade-out" : ""}`, children: [
    /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "absolute inset-0 bg-black/50", onClick: onClose, style: { transition: "opacity 0.2s" } }),
    /* @__PURE__ */ jsxRuntimeExports.jsxs(
      "div",
      {
        className: `relative w-[600px] max-h-[80vh] rounded-xl shadow-2xl overflow-hidden ${isClosing ? "animate-scale-out" : "animate-scale-in"}`,
        style: { backgroundColor: "var(--reader-sidebar, #1e1e2e)" },
        children: [
          /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "flex items-center justify-between px-6 py-4 border-b", style: { borderColor: "var(--reader-border)" }, children: [
            /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "flex items-center gap-2", children: [
              /* @__PURE__ */ jsxRuntimeExports.jsx("svg", { className: "w-5 h-5", fill: "none", viewBox: "0 0 24 24", stroke: "currentColor", style: { color: "var(--color-indigo)" }, children: /* @__PURE__ */ jsxRuntimeExports.jsx("path", { strokeLinecap: "round", strokeLinejoin: "round", strokeWidth: 2, d: "M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" }) }),
              /* @__PURE__ */ jsxRuntimeExports.jsx("span", { className: "text-lg font-semibold", style: { color: "var(--reader-text)" }, children: "更新日志" })
            ] }),
            /* @__PURE__ */ jsxRuntimeExports.jsx("button", { onClick: onClose, className: "p-1 rounded-lg transition-colors", style: { color: "var(--reader-text)", opacity: 0.6 }, children: /* @__PURE__ */ jsxRuntimeExports.jsx("svg", { className: "w-5 h-5", fill: "none", viewBox: "0 0 24 24", stroke: "currentColor", children: /* @__PURE__ */ jsxRuntimeExports.jsx("path", { strokeLinecap: "round", strokeLinejoin: "round", strokeWidth: 2, d: "M6 18L18 6M6 6l12 12" }) }) })
          ] }),
          /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "overflow-y-auto max-h-[calc(80vh-64px)] px-6 py-4", children: changelog.map((entry, i) => /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: i < changelog.length - 1 ? "mb-6 pb-6 border-b" : "", style: i < changelog.length - 1 ? { borderColor: "var(--reader-border)" } : void 0, children: [
            /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "flex items-center gap-3 mb-3", children: [
              /* @__PURE__ */ jsxRuntimeExports.jsxs("span", { className: "text-base font-semibold", style: { color: "var(--reader-text)" }, children: [
                "v",
                entry.version
              ] }),
              /* @__PURE__ */ jsxRuntimeExports.jsx("span", { className: "text-xs", style: { color: "var(--reader-text)", opacity: 0.4 }, children: entry.date })
            ] }),
            /* @__PURE__ */ jsxRuntimeExports.jsx("div", { className: "space-y-2", children: entry.changes.map((change, j) => /* @__PURE__ */ jsxRuntimeExports.jsxs("div", { className: "flex items-start gap-2", children: [
              /* @__PURE__ */ jsxRuntimeExports.jsx("span", { className: "flex-shrink-0 mt-0.5 px-1.5 py-0.5 text-[10px] font-medium rounded", style: typeLabels[change.type]?.style || { color: "var(--reader-text)", opacity: 0.5 }, children: typeLabels[change.type]?.label || change.type }),
              /* @__PURE__ */ jsxRuntimeExports.jsx("span", { className: "text-sm", style: { color: "var(--reader-text)", opacity: 0.8 }, children: change.text })
            ] }, j)) })
          ] }, entry.version)) })
        ]
      }
    )
  ] });
}
export {
  ChangelogPanel
};
