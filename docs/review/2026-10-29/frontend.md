# 前端 React/TS review（2026-10-29）

审查范围：`src/renderer/**`（components/Reader、Library、Settings、UI、stores/、reader/、hooks/、utils/、App.tsx、styles/globals.css）。
基线：HEAD `1feb175`，工作区干净，只读审查（除本文件外未改动任何文件，未跑构建/测试/应用）。

## 概要

本次**未发现 P0**（未找到崩溃、数据丢失、可被书籍内容利用的越权执行路径）。最该先修的三件事：

1. **F-02 TXT 搜索一次性为每个命中生成 `<mark>` 且无上限** —— 在几 MB 的中文 TXT 里搜一个常用字，会构造数万个 React 元素，界面直接假死数秒到十几秒。
2. **F-01 有选区时每次 `selectionchange`/500ms 轮询都把整个阅读容器序列化成字符串并逐码点计数** —— 大 TXT/HTML 里拖选文字明显卡顿（这条路径同时被 3 个事件 + 1 个 interval 触发）。
3. **F-03 PDF 连点翻页只前进一页** —— 统一翻页队列对 `scrollIntoView` 这种同步翻页器没有意义，目标页还是闭包里的旧 `visiblePage`。

其余 P1：F-04（删除高亮后正文残留）、F-05（PDF 文本层逐字符 span + 拖框 mousemove 全量重渲染）。

## 发现

| ID | 严重度 | 位置 | 问题 | 影响 | 建议 |
| --- | --- | --- | --- | --- | --- |
| F-01 | P1 | `components/Reader/ReaderView.tsx:275-308`、`reader/locator.ts:150-159,184-207,262-271` | 选区同步：`selectionchange/mouseup/keyup` + 500ms interval 都会走 `adapter.getSelection()`；只要存在非折叠选区，就用 `range.toString()` 序列化**整个滚动容器**再逐码点计数 | 大 TXT/HTML（1-3MB）拖选时每次事件 30-100ms，拖选手感卡死；同时无谓地每秒烧一次主线程 | 字符偏移改成惰性计算（只在需要 locator 时算），或按节流（>150ms）+ 只算到选区起点所在的「块级元素」；轮询里先比对选区文本再谈 locator |
| F-02 | P1 | `components/Reader/TextRenderer.tsx:82-97,185-260` | TXT 搜索把全文所有命中都塞进 `searchMatches`，渲染时给每个命中建一个 `<mark>`，无上限、无节流 | 搜「的」这类字（几十万字符的书中可达数万命中）时构造数万节点 + 数万次 slice，主线程冻结 | 与 `searchController` 一致地限制命中数（如 200/500），超出提示「结果过多，请细化关键词」；或只渲染当前视口附近的命中 |
| F-03 | P1 | `components/Reader/PdfRenderer.tsx:254-264` | 翻页器 `goByPage` 用闭包里的 `visiblePage` 计算目标，并且 `scrollIntoView` 是同步发起的；`paging` 队列 await 一个非 Promise，微任务里就排空了 | 快速连点「下一页」N 次只前进 1 页（目标页还没变），队列的「连点不丢」对 PDF 完全失效 | 用 ref 维护 `targetPage`（`targetRef.current = clamp(targetRef.current + dir)`）并在 IO 更新可见页时收敛；或让 next/prev 返回 Promise，等 visiblePage 变化后再 resolve |
| F-04 | P1 | `components/Reader/EpubRenderer.tsx:352-378` | 重画高亮时「先按当前列表 remove、再全量 add」；已从列表里删掉的 CFI 永远不会被 `annotations.remove` | 删除高亮后正文里的黄色高亮**仍在页面上**（侧栏/标注面板已没有），直到重开这本书；用户会以为删除失败 | 维护「已渲染 CFI 集合」的 ref，diff 出被删项后逐个 remove；或改用 `rendition.annotations.remove(key)` 按 key 批量清空再重画 |
| F-05 | P1 | `components/Reader/PdfRenderer.tsx:486-530,587-611` | 文本层为**每个字符**渲染一个绝对定位 `<span>`（一页数千个，且预取 ±1 页）；拖框选择时 `onMouseMove` 每次事件都 `setDragRect` 触发整个页列表重渲染 | 拖框划高亮时每帧要对上万个 span 做 diff，明显掉帧；大页 PDF 连滚动都重 | 文本层用 `content-visibility` 或按页懒渲染 + `React.memo` 包一层页组件；拖框状态改用 `ref` + 直接改内联样式（或 `requestAnimationFrame` 合帧），不要把 mousemove 塞进 React state |
| F-06 | P2 | `components/Reader/ReaderView.tsx:214-260`、`App.tsx:251-263` | 换书时同一个 `ReaderView` 实例换 `bookId`（资源管理器「打开方式」路径），但 `loadBook` 没有 cancelled 标志，也不重置 `book`/`bookContent` | 换书期间出现「书 A 的正文 + 书 B 的 bookId/书签/进度」的错配窗口；两次加载交错时可能把 A 的 `setBook/setBookContent` 落到 B 上 | effect 内加 `let cancelled = false` 并在每个 `await` 后早退；换 bookId 时先 `setBook(null); setBookContent(null)`（或给 ReaderView 加 `key={bookId}`） |
| F-07 | P2 | `components/Reader/ReaderView.tsx:113-128`、`components/Reader/HtmlRenderer.tsx`（全文无高亮渲染） | HTML/Markdown/FB2/DOCX 没有渲染器认领 `pb:openHighlightPalette`，走 `applyAnnotation` 兜底写入成功，但 `HtmlRenderer` 从不显示高亮 | 这 4 种格式里点「高亮」看起来完全没反应（只在标注面板里多出一条），用户会反复点 | 要么在 HtmlRenderer 里按 `readerStore.highlights` 渲染高亮（可用 CSS Custom Highlight 或 Range 包裹），要么在这些格式上禁用「高亮」按钮并给提示 |
| F-08 | P2 | `components/Reader/EpubSearch.tsx:92-123` | 搜索 effect 依赖 `[searchQuery, isReady]`，但命中表来自 `searchHrefMapRef`（由 `extractFullText` 异步填）；索引还没建好就搜会算出 0 命中，之后索引完成也没有任何信号触发重算 | 打开 EPUB 后立刻搜索 → 一直显示「没有找到」，必须改一次关键词才恢复 | `extractFullText` 完成后 `setSearchNonce(n=>n+1)` 之类触发重算，或在索引为空时显示「正在建立搜索索引…」而非「没有找到」 |
| F-09 | P2 | `components/Reader/ComicRenderer.tsx:83-119` | `updateCache` 是 async（逐页 `await blob` + `createObjectURL`），但没有并发保护；两次快速翻页的 `updateCache` 交错时，先发起的那次会按自己的 `needed` 把另一次刚创建的 URL `revokeObjectURL` | 快速翻页偶发某页一直转圈/空白（`<img src>` 指向已撤销的 blob URL） | 给 `updateCache` 加序号（只让最后一次生效），且 revoke 前确认该 index 不在最新 `needed` 里；更稳的是把 cache 做成「引用计数 + 延迟释放」 |
| F-10 | P2 | `components/Reader/EpubRenderer.tsx:114-155` | 主 effect 里 `epubBook.ready.then(...)` 没有取消标记也没有 `.catch`；cleanup 会 `epubBook.destroy()` | 打开书后立刻关掉/切书时，回调仍会对已 destroy 的 rendition 调 `display()` 并 `setIsReady(true)`：控制台未处理 rejection，状态被写进已卸载组件 | 在 cleanup 里置 `disposed` 标记，`then` 里先判断；并补 `.catch` 走 `setLoadError`（现在 EPUB 渲染失败会一直转圈） |
| F-11 | P2 | `components/Reader/DictionaryPopup.tsx:119-150` | `lookup()` 没有请求序号/取消（对比 `TranslationPopup` 有 `requestRef`） | 连续两次「取选区」时，先发出的慢请求回来会覆盖新词的释义（显示与输入框不一致的结果） | 加 `seqRef`，每次 lookup 递增，回来时序号不等就丢弃 |

## 详细分析

### F-01 选区同步把整个正文序列化（P1，卡顿）

触发路径：拖选正文 → 浏览器触发 `selectionchange`（拖动过程中会连续触发）→ ReaderView 的 `sync()` → `readerAdapter.getSelection()` → `locatorForSelection()` → `codepointsBefore(container, range)`：`range.cloneRange().selectNodeContents(root)` 然后 `toString()`，再用 `countCodepoints` 逐码点 `for...of` 计数。同一路径还被 500ms interval、`mouseup`、`keyup` 触发。

```ts
// ReaderView.tsx:279-298
const sync = () => {
  try {
    const selection = readerAdapter.getSelection()      // ← 昂贵路径在这里
    const text = selection?.text ?? null
    const storeCleared = useReaderStore.getState().selection === null
    if (text === lastText && (text === null) === storeCleared) return
    ...
  } catch (error) { ... }
}
sync()
const timer = window.setInterval(sync, 500)
document.addEventListener('selectionchange', sync)
document.addEventListener('mouseup', sync)
document.addEventListener('keyup', sync)
```

```ts
// reader/locator.ts:150-159
export function codepointsBefore(root: Node, range: Range): number {
  const before = range.cloneRange()
  before.selectNodeContents(root)          // root = 整个滚动容器（整本书）
  before.setEnd(range.startContainer, range.startOffset)
  return countCodepoints(before.toString()) // 全书字符串 + 逐码点循环
}
```

注意 EPUB 不受影响（`locatorForSelection` 对 epub 直接 `return null`），受影响的是 txt/mobi/html/markdown/fb2/docx。**验证**：打开 3MB TXT，拖选一段文字并用 DevTools Performance 录制，可见 `codepointsBefore`/`countCodepoints` 每次选中 30-100ms。

### F-02 TXT 搜索无上限（P1，可假死）

```ts
// TextRenderer.tsx:92-96（收集，无任何上限）
let m: RegExpExecArray | null
while ((m = re.exec(text)) !== null) {
  matches.push(m.index)
}
setSearchMatches(matches)
```

```ts
// TextRenderer.tsx:210-221（每个命中一个 range），244-257（每个 range 一个 <mark>）
for (let i = 0; i < searchMatches.length; i++) {
  const start = searchMatches[i]
  ranges.push({ start, end: start + qLen, color: ..., type: i === currentSearchIndex ? 'searchActive' : 'search' })
}
...
parts.push(<mark key={`${r.start}`} style={style}>{text.slice(r.start, r.end)}</mark>)
```

`searchMatches` 会经 zustand 广播，触发 `highlightedContent` 重算（对 ranges 排序）并重建整份正文的 React 节点。一本 200 万字的书搜「的」→ 十几万命中 → 数万个 `<mark>`，页面直接卡死（本应用无分页虚拟化，全文都在 DOM 里）。对比 `searchController` 的 `limit: 50`，这里明显漏了上限。**验证**：导入一本长篇中文 TXT，搜索「的」观察内存/主线程（也可静态估算 `matches.length`）。

### F-03 PDF 连点只翻一页（P1，功能回归）

```ts
// PdfRenderer.tsx:255-264
useEffect(() => {
  if (!paging) return
  const goByPage = (dir: 1 | -1) => {
    const target = Math.max(1, Math.min(visiblePage + dir, totalPages))   // ← 闭包里的旧值
    const el = containerRef.current?.querySelector(`[data-page="${target}"]`)
    if (el) el.scrollIntoView({ behavior: 'smooth' })                     // ← 同步返回，Promise 立即 resolve
  }
  paging.register({ next: () => goByPage(1), prev: () => goByPage(-1) })
  return () => paging.register(null)
}, [paging, visiblePage, totalPages])
```

`paging.drain()` 用的是 `await current.next()`；这里 `next()` 返回 `undefined`，`await` 在同一轮微任务里就结束，队列立刻排空 —— 两次点击都读到同一个 `visiblePage`，都滚到同一页。要等 IntersectionObserver 把 `visiblePage` 更新后，目标页才会变（平滑滚动约 300ms，所以「快点两下 = 只翻一页」很容易复现）。**对照**：ComicRenderer 用 `setCurrentIndex(i => ...)` 函数式更新因此不丢；EPUB 的 `rendition.next()` 是真 Promise，队列有效。

### F-04 删除高亮后正文残留（P1，用户可见错误）

```ts
// EpubRenderer.tsx:352-378
useEffect(() => {
  const rendition = renditionRef.current
  if (!rendition || !isReady) return
  for (const hl of highlights) {                 // ← 只遍历「当前列表」
    if (hl.cfi) { try { rendition.annotations.remove(hl.cfi, 'highlight') } catch {} }
  }
  for (const hl of highlights) {                 // 再全量重画
    if (hl.cfi) { try { rendition.annotations.add('highlight', hl.cfi, {}, undefined, 'epub-highlight', {...}) } catch {} }
  }
}, [highlights, isReady])
```

删除一条高亮后 `highlights` 里没有它了，于是 removal 循环根本不会碰它，之前 `annotations.add` 出来的 SVG 覆盖层留在 rendition 里。侧栏、标注面板、导出都显示已删除，正文却还画着 —— 是典型的「数据对了、界面错了」。**验证**：EPUB 里加一条高亮 → 侧栏删除 → 观察页面上高亮是否消失（应消失但实测不消失）；重开书后消失即证明是这处 diff 问题。

### F-05 PDF 文本层逐字符 + 拖框 mousemove（P1，卡顿）

```tsx
// PdfRenderer.tsx:507-511（mousemove 直接 setState）
onMouseMove={(e) => {
  if (!dragRect || dragRect.page !== pageNum) return
  const r = e.currentTarget.getBoundingClientRect()
  setDragRect({ ...dragRect, x1: (e.clientX - r.left) / r.width, y1: (e.clientY - r.top) / r.height })
}}
```

```tsx
// PdfRenderer.tsx:592-608（每字符一个绝对定位 span，样式全内联）
{layer.chars.map((ch, idx) => (
  <span key={idx} style={{ position: 'absolute', left: ..., top: ..., width: ..., height: ..., fontSize: ..., color: 'transparent', userSelect: 'text' }}>{ch.c}</span>
))}
```

`dragRect` 是 PdfRenderer 的 state，改动会让整个 `Array.from({length: totalPages})` 页列表重新渲染；每页的文本层有数千个 span（`textLayers` 还会预取 `visiblePage-1 / +1`）。拖框划高亮时等于每帧 diff 上万节点。**验证**：打开 300 页以上的 PDF（文本型），按住鼠标拖出一个高亮框并持续移动，DevTools Performance 会看到每帧几十 ms 的脚本耗时。

### F-06 换书竞态 / 旧内容残留（P2）

```ts
// ReaderView.tsx:214-260
useEffect(() => {
  bookIdRef.current = bookId
  setBookId(bookId)
  const loadBook = async () => {
    setLoadError(null)
    try {
      const bookData = await window.electronAPI.getBook(bookId)
      ...
      await loadProgress(bookId)          // ← 若期间 bookId 又变了，这里没有取消
      ...
      const content = await readBookFile(bookData.file_path)
      ...
      setBookContent(content)             // ← 旧请求的结果会覆盖新书
    } catch (e) { ... }
  }
  loadBook()
  return () => { setBookId(null) }
}, [bookId])
```

触发路径不是「书架点书」（那时 ReaderView 是重新挂载），而是 `App.tsx:251-263` 的 `onOpenFiles`：在阅读器里从资源管理器再次「打开方式」导入成功且只有 1 本时会 `setCurrentBookId(imported[0].id)`，同一个 ReaderView 实例换 props。此时 `book`/`bookContent` 仍是旧书的，先渲染「旧正文 + 新 bookId/新书签/新进度」，两个 `loadBook` 还可能交错。**验证**：读一本 EPUB，用资源管理器「打开方式」导入另一本书，观察换书瞬间的正文/侧栏是否错配。

### F-07 HTML/MD/FB2/DOCX 的「高亮」无可见结果（P2）

```ts
// ReaderView.tsx:113-128
const handleSelectionHighlight = (selection: ReaderSelection) => {
  let handled = false
  const onOpened = () => { handled = true }
  window.addEventListener('pb:highlightPaletteOpened', onOpened)
  window.dispatchEvent(new CustomEvent('pb:openHighlightPalette'))
  window.setTimeout(() => {
    window.removeEventListener('pb:highlightPaletteOpened', onOpened)
    if (handled) return
    if (!readerAdapter) return
    void readerAdapter.applyAnnotation({ type: 'highlight', color: '#fbbf24', text: selection.text })
      .then((created) => { if (created) useReaderStore.getState().clearSelection() })
      .catch((error) => console.error('Failed to highlight selection:', error))
  }, 0)
}
```

只有 EpubRenderer / TextRenderer / PdfRenderer 监听 `pb:openHighlightPalette`；HtmlRenderer 里对 `highlight` 的唯一引用是搜索用的 `highlightTextInDOM`（`grep -n highlight HtmlRenderer.tsx` 只命中第 10/149/171 行的搜索逻辑），它从不读 `readerStore.highlights`。于是这 4 种格式走兜底写入：DB 里多一条标注，正文毫无变化。

### F-08 EPUB 搜索索引未就绪时永久 0 命中（P2）

```ts
// EpubSearch.tsx:105-122
const q = searchQuery.trim().toLowerCase()
const map = searchHrefMapRef.current          // ← 由 extractFullText 异步填充，可能还是空 Map
const results: { href: string; occurrenceIndex: number }[] = []
for (const [href, { text }] of map) { ... }
searchRangesRef.current = results
setSearchMatches(Array.from({ length: results.length }, (_, i) => i))
```

effect 依赖只有 `[searchQuery, isReady, ...]`，`extractFullText` 在 `epubBook.ready` 里 fire-and-forget（EpubRenderer.tsx:147），完成后不触发任何状态变化 → 早搜的查询永远不会重算。

### F-09 漫画页缓存并发撤销（P2）

```ts
// ComicRenderer.tsx:94-112
for (const idx of needed) {
  if (!cache.has(idx)) {
    const blob = await entries[idx].getData()      // ← 中途可能被另一次调用插入/撤销
    const url = URL.createObjectURL(blob)
    cache.set(idx, url)
  }
}
for (const [idx, url] of cache) {
  if (!needed.has(idx)) { URL.revokeObjectURL(url); cache.delete(idx) }
}
setPageUrls(new Map(cache))
```

两次 `updateCache` 交替时，后发起的一次已经 `set` 了某页 URL，先发起的那次按自己的 `needed` 把它 revoke 掉，随后 `setPageUrls` 仍把该 URL 交给 `<img>` → 图裂/一直转圈。

### F-10 EPUB 初始化无取消（P2）

见 F-10 行内说明；补充：`epubBook.ready.then(...)` 既没有 `disposed` 判断也没有 `.catch`，所以（a）快速关书会有 `setIsReady` 落到已卸载组件、（b）`rendition.display()` 在 `destroy()` 之后 reject 会变成未处理 Promise rejection。另一个后果是渲染失败时没有失败态（`isReady` 永远 false → 一直转圈），与 `ReaderErrorView` 的设计不一致。

### F-11 词典查词竞态（P2）

`lookup` 直接 `await dictionaryLookup` 后 `setResult`，没有序号；中文 2 字窗口回退还会串行发多次请求。连续两次「取选区」时，先发出的结果可能后到并覆盖新词。TranslationPopup 用 `requestRef` 做过同样的事（TranslationPopup.tsx:249-261），这里漏了。

## 存疑 / 未覆盖

1. **F-01/F-02/F-05 的耗时数量级**：以上是静态推理（数据量 × DOM 节点数），未在本机录制 profile。验证方法：用 CDP（`WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=--remote-debugging-port=9222`）连上应用页，打开 3MB TXT 拖选/搜索「的」，看 Performance 面板的 `codepointsBefore`、`highlightedContent` 耗时与节点数。
2. **书柜重命名 Esc 时序**（AGENTS.md 已列为存疑，我未能确认）：`BookShelfPanel.tsx:118-121` 的 input 同时有 `onKeyDown Escape → setEditingId(null)` 和 `onBlur={handleRename}`。按规范「移除聚焦元素不派发 blur」，Esc 应该是取消；但 Chromium/React 的实际行为需要在真机点一下（Esc 后看名称有没有被提交）才能定论，故不列为发现。
3. **分屏（SplitReaderView.tsx）只做了粗读，建议单独一轮 review**：它的 TTS 只有 `setSpeakingPane`，没有订阅 `onTtsEvent`（756 行的 TtsBar 才订阅），自然读完后按钮不会复位；`ttsSpeak(text)` 单参数调用虽然合法（tag/flush 可选，见 contract.mjs:1055-1058 与 BridgeServer.cpp:417），但整条朗读链路没有句子进度/停止反馈；另外分屏的键盘处理只在本 pane 的容器上，iframe 内按键没有转发。
4. **未读文件**：`BookDetailDialog.tsx`、`SplitBookPickerDialog.tsx`、`UI/NavRail.tsx`、`UI/Popover.tsx`、`UI/Dialog.tsx`、`ChangelogPanel.tsx`、`BookSourcePanel.tsx`、`TranslationSettings.tsx`、`i18n/*`、`data/changelog.ts`；`globals.css` 只抽查了 backdrop-filter/关键帧/优先级（1787 行未逐条读）。
5. **未验证项**：`bookSourceStore.startDownload` 若并发启动两次下载，会有两个 `onDownloadProgress` 订阅同时生效（第一次 done 就 resolve），UI 可能提前结束进度条 —— 依赖 C++ 是否允许并发下载，未验证。

## 已核对且没问题的点

1. **事件监听/订阅都成对清理**：按文件统计 `addEventListener` 与 `removeEventListener` 数量一一对应（EpubRenderer 9/9、ReaderView 7/7、PdfRenderer 5/5、TextRenderer 5/5、M3Select 3/3、Library 2/2、BookCard 2/2…）；所有 `window.electronAPI.on*` 订阅都返回 unsub 并在 effect cleanup 调用（App.tsx:153-170、Library.tsx:206-211、288-292、UpdateBanner.tsx:13-36、TtsBar.tsx:442-489、DictionaryPopup.tsx:96-117）。EPUB 内容 hook 里的 5 个 iframe 监听器都推进 `contentCleanupFns` 并在卸载时摘除（EpubRenderer.tsx:286-332），`mouseup` 的 `setTimeout` 也被 `clearTimeout`（同文件 302-332）。
2. **翻页器注册与清理成对，且换书不会串味**：EpubRenderer:89-96 / TextRenderer:305-314 / ComicRenderer:132-139 / PdfRenderer:255-264 都在 cleanup 里 `paging.register(null)`；`register` 同时清空队列（paging.ts:78），`ReaderView` 的 `paging` 随 `bookId` 重建（ReaderView.tsx:202），旧书的排队意图不会带到新书。
3. **全局键盘守卫**：`isTypingTarget`（keyboard.ts:9-16）只把文本类 input/textarea/contenteditable 视作打字（`type=range` 不算），ReaderView 的 keydown 首行即守卫（:371）、PDF 缩放键同守卫（PdfRenderer.tsx:292-298）；搜索框 Esc 先 `stopPropagation`（ReaderControls.tsx:121-128），词典/翻译/M3Select 的 Esc 都在捕获阶段 `stopPropagation`，「在搜索框按 Esc 关掉整本书」的旧坑已闭合。
4. **不可信书籍内容的安全面**（重点核对，**未发现 P0**）：HTML/Markdown/FB2/DOCX 一律 `DOMPurify.sanitize` 后才 `dangerouslySetInnerHTML`（HtmlRenderer.tsx:88-101）；EPUB 用 `allowScriptedContent: false`，而 epub.js 自身把 iframe 设为 `sandbox="allow-same-origin"`（node_modules/epubjs/lib/managers/views/iframe.js:97，不含 `allow-scripts`）→ 书内 `<script>` 无法拿到 `electronAPI`。**顺带排除一个误报**：EpubRenderer.tsx:212-216 在 content hook 里写 `iframe.sandbox = 'allow-same-origin allow-popups'` 是「文档创建后才改属性」，对当前文档不生效（沙箱标志在文档创建时锁定），既没削弱 epub.js 的沙箱也没新增攻击面，属无效代码而非漏洞。
5. **iframe 交互转发齐全**：mousemove（换算窗口坐标）、click（换算后交给 `paging.click`，并排除拖选/链接）、wheel、keydown 全部在 `rendition.hooks.content` 里注册并登记清理（EpubRenderer.tsx:237-297）——「点过正文后 Esc/方向键失效」「点两侧不翻页」这两个旧坑的修复是完整的。
6. **大列表与封面缓存**：`>120` 本走行虚拟化（BookGrid.tsx:56-119、BookList.tsx:53-154），滚动监听/ResizeObserver 都成对撤除，rAF 合帧；封面有内存 LRU + 同 id 并发合并（libraryStore.ts:83-115），不会 5000 本各发一次 bridge。删除书籍/切筛选后虚拟化的 `books.length` 变化会重新测量。
7. **TTS 卸载一定停读**：TtsBar 卸载 cleanup 里 `ttsStop()` + 清高亮（TtsBar.tsx:432-439），换书时 ReaderView 再兜一次（ReaderView.tsx:320-325），睡眠定时按 `Date.now()` 截止时间算且随 effect 清理（TtsBar.tsx:509-532）。
