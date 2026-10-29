# UI/UX 一致性、i18n 与可访问性 review（2026-10-29）

> 范围：src/renderer/components/{UI,Settings,Library}/**、components/Reader 的 Toolbar/Menu/Dialog/Popup、styles/globals.css、i18n/locales/**、data/changelog.ts。
> 基线：HEAD 1feb175，工作区干净。只读审查，未运行构建/测试/应用。
> 取证方式：源码 + 本地既有构建产物 out/renderer/assets/index-C96qSchY.css（2026-10-29 20:21，用于确认 CSS 层叠顺序）+ 自写脚本复算 i18n/占位符/ARIA。

## 概要（最该先修的三件事）

1. **U1 弹层宽度全线失效**：globals.css 的 .m3-dialog { width: min(560px,92vw) } 在层叠上压过 call site 的 w-[900px]/w-[760px]/w-[620px]/w-[500px]/w-80（已用构建产物定位确认），「新建书柜」对话框实际宽 560（设计 320）、书源弹层 560（设计 900）。
2. **U2 / U4 两个数据面风险**：书籍详情对话框点遮罩即关闭并**丢弃未保存的元数据编辑**；侧栏删除笔记/高亮/标注**没有二次确认**（而删书、删书柜、清空书源都有）。
3. **U3 键盘断流**：书库「从全部书籍添加」的书籍行、阅读器侧栏的书签/高亮/笔记行、标注面板行都是 div + onClick（无 role/tabIndex/keydown），纯键盘用户无法完成选书与跳转——同一仓库的 BookCard/BookList 已经是正确写法。

## 发现

| ID | 严重度 | 位置(file:line) | 问题 | 影响 | 建议修法 |
|---|---|---|---|---|---|
| U1 | P1 | globals.css:1430；BookSourcePanel.tsx:83、LibrarySearch.tsx:346、ChangelogPanel.tsx:56、Library.tsx:66、BookShelfPanel.tsx:193 | .m3-dialog 的 width/max-height 覆盖 call site 的宽度工具类，设计尺寸全部失效 | 新建书柜 320→560、书源弹层 900→560、搜索面板 760→560、更新日志 620→560，观感与信息密度都不对 | 把 .m3-dialog 的 width/max-height 降权（:where(.m3-dialog) 或移入 @layer components），或 call site 统一用 !w-[…] |
| U2 | P1 | BookDetailDialog.tsx:117-129 | 遮罩 onClick={onClose}；无 Esc、无焦点陷阱、role="dialog" 无 aria-label/labelledby | 元数据编辑填到一半点窗外 → 输入全部丢失；Tab 会跑到背后书库；读屏只念 "dialog" | 改用共享 UI/Dialog（自带 closeOnBackdrop/Esc/焦点循环/labelled-by），或至少 closeOnBackdrop=false + Esc + 焦点陷阱 |
| U3 | P1 | Library.tsx:87、Sidebar.tsx:109/184/239、AnnotationPanel.tsx:381-387 | 关键列表行是 div + onClick，无 role/tabIndex/键盘处理 | 键盘用户无法在多选弹层里勾书、无法从侧栏跳到书签/高亮/笔记、无法跳标注 | 参照 BookCard.tsx:119-122 / BookList.tsx:96：role="button" + tabIndex={0} + Enter/Space，或直接改成 <button> |
| U4 | P1 | Sidebar.tsx:191-198、Sidebar.tsx:243-252、AnnotationPanel.tsx:415-426 | 删除笔记 / 高亮 / 标注一键即删，无确认、无撤销（删书/删书柜/清空书源都有 ConfirmDialog） | 误点一次就永久丢失用户手写的笔记内容 | 复用 ConfirmDialog（或提供撤销 toast）；删除按钮至少给可访问名 |
| U5 | P2 | BookSourcePanel.tsx:80-83、LibrarySearch.tsx:337-348、ChangelogPanel.tsx:52-58、Library.tsx:64-67、BookShelfPanel.tsx:191-193 | 五个大弹层缺对话框语义与焦点管理：role="dialog"/aria-modal 不齐、无焦点陷阱、多数无 Esc | Tab 可离开弹层进入背后页面；读屏不播报"对话框"；键盘用户找不到关闭路径 | 统一收敛到 UI/Dialog 或 ModalNavDrawer 式实现（portal + Esc + Tab 循环 + 关闭回焦 + aria-modal） |
| U6 | P2 | BookSourcePanel.tsx:259、GlobalSettingsPages.tsx:764/773、DictionaryPopup.tsx:192、BookSourcePanel.tsx:47、Library.tsx:307 | 破坏性/结果提示用原生 confirm()/alert()，与 M3 ConfirmDialog 并存 | 系统弹窗不跟暗色/护眼主题、阻塞渲染、样式与别处删除确认不一致 | 删源/恢复备份/清词库改 ConfirmDialog；成功提示改用页面内 banner/toast |
| U7 | P2 | BookSourcePanel.tsx:283-294 vs BookSourceManager.tsx:709-715 | 「书源启用」同一概念两种控件：书库弹层是 40×20 自造按钮（无 role="switch"/aria-checked/可访问名），设置页是 52×32 M3 Switch | 读屏无法得知开关状态；触控目标 20px 高；两处视觉不一致 | 书库弹层改用 UI/Switch（Switch.tsx:32-33 已带 role/aria-checked/label） |
| U8 | P2 | Sidebar.tsx:64、Sidebar.tsx:191、Sidebar.tsx:243、BookSourcePanel.tsx:318、SettingsPanel.tsx:42 | 纯图标按钮没有 aria-label/title（无可见文本） | 读屏一律念 "button"；其中两个还是删除动作 | 补 aria-label={t('关闭')} / t('删除') 等既有 key |
| U9 | P2 | Sidebar.tsx:47-62、BookSourcePanel.tsx:107-128 | 标签页只有视觉选中态：无 role="tablist"/tab、aria-selected、aria-controls | 读屏无法知道当前是哪一页；键盘也没有 ←/→ 切页 | 补 WAI-ARIA tabs 模式，或退而求其次加 aria-pressed |
| U10 | P2 | globals.css:170（.sepia --accent #b8860b）+ globals.css:1055/1651 | 护眼主题下 --accent-text 仍是 #ffffff，白字在 #b8860b 上对比度 **3.31:1**（AA 需 4.5:1）；.m3-badge 还有两份冲突定义（1415 / 1651），后者把徽章也变成 primary 实底 | 护眼主题所有填充按钮、选中段控、徽章文字偏难读 | sepia 的 --accent-text 改深色（如 #1a1510，实测对比 5.45:1）；删掉 1415 或 1651 之一 |
| U11 | P2 | Sidebar.tsx:142/189/242、BookSourcePanel.tsx:112/123/301 | 次要信息在 --text-secondary/--reader-text 之上再叠 opacity-40/50 | 有效对比度：亮色 **2.50:1**、暗色 **3.25:1**（12px 文字），低于 AA 4.5:1 | 去掉叠加 opacity，直接改用 --text-tertiary 并整体调亮，或把字号提到 14px+ |
| U12 | P2 | TtsBar.tsx:700（hidden sm:flex）、TtsBar.tsx:716（hidden md:flex） | 窄窗口下「语速」与「睡眠定时」整块隐藏，且没有替代入口 | <640 宽时两功能完全不可达（应用已正式支持 compact 档）；<768 时睡眠定时也不可达 | 收进「更多」菜单或折叠成图标按钮，保证任何窗口宽度都可达 |
| U13 | P2 | ChangelogPanel.tsx:54、SettingsPanel.tsx:32、ReaderView.tsx:531/549、globals.css:1428 | 减少动效覆盖不全：4 处内联 transition: 'opacity 0.2s' 与进度条 transition: width 不在任何 prefers-reduced-motion 块内 | 开启"减少动效"的用户仍会看到淡入与进度条滑动 | 加一条 @media (prefers-reduced-motion: reduce){ *,*::before,*::after{ animation:none!important; transition:none!important } }（可覆盖内联样式） |
| U14 | P2 | SettingsPanel.tsx:32、ReaderView.tsx:531/548/712 | 遮罩硬编码 bg-black/40，与 token --ui-backdrop（亮色 0.35 / 暗色 0.55）不一致 | 亮色主题下这几个浮层的压暗比其它弹层重，同屏两种遮罩浓度 | 改用 style={{ background: 'var(--ui-backdrop)' }} 或 .m3-dialog-backdrop |

## 详细分析

### U1 · .m3-dialog 压掉 call site 的宽度（P1）

globals.css 先 `@tailwind utilities`（文件第 1-3 行），而 .m3-dialog 定义在 1430 行、晚于所有工具类，二者同为一类选择器（specificity 0,1,0）→ **后者胜出**：

```css
/* globals.css:1430 */
.m3-dialog {
  width: min(560px, 92vw);
  max-height: 86vh;
  ...
}
```

构建产物可复核顺序（out/renderer/assets/index-C96qSchY.css）：.w-[900px] 在字节 14319、.w-80 在 13963、.max-h-[70vh] 在 13057，而 .m3-dialog 在 63658。于是下列 call site 的宽度都是**死代码**：

```tsx
// BookSourcePanel.tsx:83（设计 900）→ 实际 560
<div className={`m3-dialog relative w-[900px] max-h-[85vh] flex flex-col overflow-hidden ...
// Library.tsx:66（设计 500）/ LibrarySearch.tsx:346（760）/ ChangelogPanel.tsx:56（620）
// BookShelfPanel.tsx:193（设计 320，w-80）→ 实际 560
<div className="m3-dialog relative w-80 p-6 animate-scale-in">
```

仓库里已经有人踩过并绕过：PdfRenderer.tsx:700 写的是 `!w-[420px]`（`!` 提升为 !important 才生效）。这同时说明「新建书柜」对话框——一个只有名字输入框的小弹层——被拉到 560px 宽，而 BookShelfPanel.tsx:122 的注释还在说明它「320px 宽」。

### U2 · 书籍详情对话框：点遮罩即丢弃未保存编辑（P1）

```tsx
// BookDetailDialog.tsx:117-129
return createPortal(
  <div className="fixed inset-0 z-[9999] flex items-center justify-center animate-fade-in"
    onClick={onClose}
    role="dialog"
    aria-modal="true"
    data-testid="book-detail-dialog">
    <div className="m3-dialog-backdrop absolute inset-0" />
    <div className="m3-dialog relative p-6 ..." onClick={(e) => e.stopPropagation()}>
```

外层捕获所有"窗外点击" → onClose()。该对话框是可编辑表单（书名/作者/标签/系列/评分/简介，第一个输入框 autoFocus），界面上没有任何"放弃修改？"提示；同一弹层也没有 Esc（想关只能点窗外或 ×，两条路都不提示未保存）。此外 role="dialog" 没有 aria-label/aria-labelledby，读屏只知道"对话框"。对照 UI/Dialog.tsx：它已有 Esc、Tab 循环、关闭回焦、aria-labelledby，本弹层是唯一绕过它的编辑型弹层。

### U3 · 只能鼠标点的关键行（P1）

```tsx
// Library.tsx:87-91「从全部书籍添加」的选择行
<div key={book.id} onClick={alreadyIn ? undefined : () => toggle(book.id)}
  className="flex items-center gap-3 px-3 py-2 rounded-md transition-colors"
  style={{ ... cursor: alreadyIn ? 'default' : 'pointer' }}>
// Sidebar.tsx:109（书签）/ :184（高亮）/ :239（笔记）都是同一形态
<div ... onClick={() => navigateTo({ page: bm.page, cfi: bm.cfi })}>
// AnnotationPanel.tsx:381-387
<div key={annotation.id} className="... cursor-pointer" onClick={() => jumpTo(annotation)}>
```

没有 role / tabIndex / onKeyDown，键盘无法聚焦，选中 / 跳转这两个主流程对键盘用户完全不可用。仓库里正确写法就在隔壁：BookCard.tsx:119-122 用 `role="button" tabIndex={0} aria-label=... onKeyDown`，BookList.tsx:96 亦然——本项属"改造漏网"而非设计取舍。

### U4 · 删除笔记 / 高亮 / 标注没有确认（P1）

```tsx
// Sidebar.tsx:243-252（笔记行的删除按钮；高亮行 :191-198 同构）
<button
  onClick={(e) => { e.stopPropagation(); removeNote(note.id) }}
  className="opacity-0 group-hover:opacity-100 p-1 text-[var(--reader-text)] opacity-40 hover:opacity-80"
>
  <svg className="w-3 h-3" .../>
</button>
```

按钮无 aria-label（U8）、无确认、无撤销，直接调 store 删库；而删书（BookCard.tsx:228-231）、删书柜（BookShelfPanel 的 ConfirmDialog）、清空书源（BookSourcePanel.tsx:259，虽是原生 confirm）都有二次确认。笔记是用户手写内容，误点一次即永久丢失——这是本报告里唯一一处"一次点击丢用户数据"的路径，故列 P1（未列 P0：无静默损坏，必须显式点击）。

### U5 · 大弹层缺对话框语义与焦点陷阱（P2）

```tsx
// BookSourcePanel.tsx:80-83 —— 全屏弹层，无 role/aria-modal，无 Esc，无焦点管理
<div className="fixed inset-0 z-50 flex items-center justify-center animate-fade-in">
  <div className="m3-dialog-backdrop absolute inset-0" onClick={onClose} />
  <div className={`m3-dialog relative w-[900px] max-h-[85vh] flex flex-col overflow-hidden ...
// LibrarySearch.tsx:337-348 —— 有 Esc（window 捕获），无 role/aria-modal，无焦点陷阱
// ChangelogPanel.tsx:52-58 / Library.tsx:64-67 / BookShelfPanel.tsx:191-193 —— 同上
```

这些弹层打开后焦点仍在触发按钮 / 页面上（LibrarySearch 是例外，它 inputRef.focus()），Tab 会一路走进背后书库的卡片与工具栏。修复成本低：UI/Dialog.tsx 已经把 portal、Esc、Tab 循环、关闭回焦、aria-labelledby 全做好了，直接改用它即可。

### U6 · 原生 confirm/alert 与 M3 ConfirmDialog 并存（P2）

```tsx
// BookSourcePanel.tsx:259
if (confirm(t('确定清空所有书源？此操作不可撤销。'))) clearAllSources()
// GlobalSettingsPages.tsx:764（恢复备份）/ :773（重试恢复）
if (!window.confirm(t('恢复会覆盖当前书库、进度与笔记。继续？'))) return
// DictionaryPopup.tsx:192（清空本地词库）
// alert：BookSourcePanel.tsx:47（导入成功）、Library.tsx:307（拖放超 50MB）
```

文案本身已本地化（不是 i18n 问题），但 WebView2 的系统弹窗固定白底 + "particlebook.app 显示"标题，暗色 / 护眼主题下很突兀，且 confirm/alert 会阻塞渲染线程；同样语义的删除确认在 BookCard / BookShelfPanel / BookSourceManager 都已用 .m3-dialog 版本。

### U7 · 同一"书源启用"开关两种形态（P2）

```tsx
// BookSourcePanel.tsx:283-294（书库弹层）：40×20 自造开关，无语义、无名字、命中区 20px
<button onClick={() => toggleSource(source.id)}
  className={`relative w-10 h-5 rounded-full transition-colors flex-shrink-0 ${...}`}>
  <div className={`absolute top-0.5 w-4 h-4 rounded-full bg-white ...`} />
// BookSourceManager.tsx:709-715（设置页）：M3 Switch 52×32 + role=switch + aria-checked + label
<Switch size="sm" checked={enabled} onChange={...} label={enabled ? t('已启用') : t('已禁用')} />
```

两处操作的是同一份书源数据，视觉与语义却完全不同；Switch.tsx:32-33 已经提供了 role="switch" + aria-checked，直接替换即可，顺带满足 48px 触控目标的方向。

### U8 · 纯图标按钮没有可访问名（P2）

```tsx
// Sidebar.tsx:64（关闭侧栏）
<button onClick={() => setShowSidebar(false)} className="p-1 text-[var(--reader-text)] opacity-50 hover:opacity-80">
  <svg className="w-4 h-4" ...>M6 18L18 6M6 6l12 12</svg>
// Sidebar.tsx:191 / :243（删除高亮 / 删除笔记）、BookSourcePanel.tsx:318（删除书源）、SettingsPanel.tsx:42（关闭阅读设置）
```

同一个文件里其它按钮都写了 aria-label（如 BookList.tsx:111、AnnotationPanel.tsx:424、ChangelogPanel.tsx:67），这 5 处是遗漏。另注：Sidebar.tsx:193 / :247 的 `className="opacity-0 group-hover:opacity-100 ... opacity-40"` 中 opacity-0 是死代码（构建产物里 .opacity-0@24804 早于 .opacity-40@24894，后者生效），"悬停才显示"的意图实际没实现——按钮一直以 40% 淡显。

### U9 · 标签页没有 ARIA 状态（P2）

```tsx
// Sidebar.tsx:47-62：4 个图标 tab，选中态只有类名
<button key={tab.id} onClick={() => setSidebarTab(tab.id)}
  className={`p-2 rounded-lg text-xs font-medium transition-colors ${
    sidebarTab === tab.id ? 'bg-[var(--reader-accent)] text-white' : 'text-[var(--reader-text)] opacity-50 ...'}`}
  title={tab.label}>
// BookSourcePanel.tsx:107-128：2 个文字 tab，选中态是 border-b-2 + 内联 color
```

缺 role="tablist" + role="tab" + aria-selected（也没有 aria-controls / 方向键）。可见文案给了名字，但"当前选中哪一页"只靠颜色传达——违反"不只用颜色表达状态"的既有约定（M3 部分已经把选中态做成 primary-container + aria-current，这里没有跟上）。

### U10 · 护眼主题 primary 控件对比度不足（P2）

```css
/* globals.css:163-172 */
.sepia { --accent: #b8860b; --accent-text: #ffffff; ... }
/* globals.css:1055 */
.m3-filled-button { border: 0; background: var(--md-sys-color-primary); color: var(--md-sys-color-on-primary); }
```

按 WCAG 相对亮度计算：#b8860b 的 L = 0.2675，白色 L = 1.0 → 对比度 (1.05)/(0.3175) = **3.31:1**，低于正文 AA 的 4.5:1（14px/500 的 label-large 不属于大字号豁免）。受影响：所有 m3-filled-button、is-active 段控、.m3-badge、侧栏激活 tab（text-white on --reader-accent）。改 --accent-text: #1a1510 后为 5.45:1。另：.m3-badge 在 globals.css:1415 与 :1651 各定义一次，后者把徽章改成 primary 实底，让上述问题也覆盖到列表里的格式徽章。

### U11 · 次要文字层层叠加 opacity（P2）

```tsx
// Sidebar.tsx:142 / :242 —— 时间戳
<p className="text-xs text-[var(--reader-text)] opacity-40">{new Date(bm.created_at).toLocaleString(i18n.language)}</p>
// BookSourcePanel.tsx:112 / :123 —— 未选中的 tab
: 'text-[var(--reader-text)] opacity-50 hover:opacity-80'
// BookSourcePanel.tsx:301 —— 书源 URL
<div className="text-xs text-[var(--reader-text)] opacity-40 truncate">
```

--reader-text 本身是 --text-primary（不透明），但 --text-secondary/--text-tertiary 已是 0.60/0.38 alpha；再乘 0.40 后有效 alpha≈0.40：亮色（0.4×#1a1a1a on #f7f7f7）对比 **2.50:1**、暗色 **3.25:1**。12px 文字在这个对比度下弱视 / 低亮度屏基本读不出。

### U12 · 窄窗口下 TTS 语速与睡眠定时消失（P2）

```tsx
// TtsBar.tsx:699-713
<div className="hidden sm:flex items-center gap-1 shrink-0" title={t('语速')}>
  <input type="range" min={-10} max={10} ... aria-label={t('语速')} />
// TtsBar.tsx:715-728
<div className="hidden md:flex items-center gap-1 shrink-0" title={t('睡眠定时')}>
  <M3Select value={sleepMode} options={sleepOptions} ... data-tts-sleep="1" />
```

sm=640、md=768。项目已正式支持 compact(<640) 与 medium(640–839) 两档窗口（见 hooks/useWindowSizeClass），而在 640 以下两者**同时消失**，768 以下睡眠定时消失，且不设替代入口（全局设置页也没有这两项）。表现为"窗口拉窄后朗读语速改不了、睡眠定时设不了"。

### U13 · 减少动效覆盖不全（P2）

```tsx
// ChangelogPanel.tsx:54 / SettingsPanel.tsx:32 / ReaderView.tsx:531 / :549
<div className="m3-dialog-backdrop absolute inset-0" onClick={onClose} style={{ transition: 'opacity 0.2s' }} />
// globals.css:1428
.m3-linear-progress > span { ... transition: width var(--md-sys-motion-medium); }
```

globals.css 里已有 5 个 prefers-reduced-motion 块，但都是按类名逐个关（.animate-*、m3-* 按钮、.m3-select-menu…），内联 style 的 transition 与进度条宽度动画没有覆盖。用一条全局 `*` 选择器规则 + `!important` 即可同时覆盖内联样式（含内联 style）。

### U14 · 遮罩色硬编码绕过 token（P2）

```tsx
// SettingsPanel.tsx:32 / ReaderView.tsx:531 / :548 / :712
<div className={`absolute inset-0 bg-black/40 ...`} style={{ transition: 'opacity 0.2s' }} onClick={onClose} />
```

应用其它浮层统一用 --ui-backdrop（暗 0.55 / 亮 0.35 / 护眼 0.55）或 .m3-dialog-backdrop；这四处固定 0.40，亮色主题下"阅读设置 / 侧栏"的遮罩比别的弹层更暗，同屏出现两种浓度。

## 存疑 / 未覆盖

- **U1 的结论依赖构建产物顺序**：.m3-dialog 覆盖 w-[…] 是按本地 out/renderer/assets/index-C96qSchY.css（20:21 构建）确认的；若后续构建改成原生 @layer（Tailwind v4 或手动分层），该覆盖关系会变。复核方法：grep -o 出 .m3-dialog 规则与 .w-\[900px\] 规则，比较二者在 CSS 文件中的字节偏移。
- **对比度是计算值，不是像素采样**：U10 / U11 用 WCAG 公式对 token 计算，未在真实窗口取色（禁止启动应用）。复核方法：用 CDP 的 Emulation.setDeviceMetricsOverride + Runtime.evaluate 取 getComputedStyle(el).color 后按相对亮度公式复算，或对整屏截图取色。
- **i18next 复数在 zh 下的行为未实跑**：i18n/index.ts 只注册了 en 资源、fallbackLng: 'zh'，{{count}} 本书的 _one/_other 变体只在 en 里；按 i18next 语义 zh 会回落到 key 本身（中文原文），所以中文界面不受影响。若后续补 zh 资源需要复测。
- **未覆盖**：PdfRenderer / EpubRenderer / TextRenderer 内部非 Popup/Dialog 的浮层、stores/* 里生成的错误文案、C++ 注入的 Z-Library 工具栏（zlib_toolbar.js）文案、Windows 高对比度模式与真实屏幕阅读器（NVDA / Narrator）实测。
- **Library.tsx:497** 的 m3-segmented hidden sm:inline-flex 中 hidden 是失效工具类（同 U1 的层叠机制），目前靠外层 {!isCompact && …} 条件渲染保证正确，属遗留噪声，可在下次触碰时删掉。

## 已核对且没问题的点

1. **i18n 覆盖**：自写脚本复算 585 个 t() key，en 词典 0 缺失；占位符三方一致（key / 译文 / 调用点）0 处不匹配；data/changelog.ts 的 288 条文案全部有 changelog.en.ts 译文；动态 key（错误码到中文的映射：translationErrorMessageKey、DOWNLOAD_ERROR_KEYS、searchErrorKey、TYPE_KEYS、annotationStore 的 4 条）逐条核对均存在，en 模式不会漏出中文。
2. **原生控件收口**：全仓已无原生 select（唯一命中在 M3Select.tsx:13 的注释）；M3Select 的 listbox 键盘模型核对通过（M3Select.tsx:275-306：Esc / Tab / 上下键 / Home / End / Enter，焦点留在 listbox 并用 aria-activedescendant，Tab 关闭时同步回焦触发器，避免焦点掉到 body）。
3. **共享 Dialog / 模态抽屉**：UI/Dialog.tsx:33-73 的 Esc、Tab 循环、关闭回焦、滚动锁与 aria-labelledby 齐全；UI/NavRail.tsx 的 ModalNavDrawer 同样齐全（portal 到 body 规避 backdrop-filter 包含块问题）。
4. **焦点可见性**：globals.css:247 有全局 *:focus-visible 焦点环，所有原生 button/input 键盘可见；程序聚焦的菜单按设计关掉了大纲环、改用高亮底色（.m3-menu-item:focus-visible / .m3-select-list:focus-visible）。
5. **z-index 阶梯与 portal**：50 / 58 / 61 / 70 / 80 五级与 portal 组合核对，M3Select 弹层（z-80）能盖住 z-70 的设置页弹窗；三处 z-[9999]（BookDetailDialog / UpdateBanner / EpubRenderer）内部没有下拉或菜单，暂未形成遮挡，但建议后续统一进阶梯。
6. **既有键盘可达性范例**：BookCard.tsx:119-122 与 BookList.tsx:96 已具备 role="button" + tabIndex + Enter/Space + aria-label，可直接作为 U3 的修复模板。
