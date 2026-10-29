import { useEffect, useMemo, useState, useRef, useCallback, lazy, Suspense } from 'react'
import { useTranslation } from 'react-i18next'
import { useReaderStore } from '../../stores/readerStore'
import { useSettingsStore } from '../../stores/settingsStore'
import { isTypingTarget } from '../../utils/keyboard'

// PB-024-B：状态视图已抽到 ReaderStatusView（最小 targeted edit，见该文件注释）
import { ReaderErrorView, ReaderLoadingView, ReaderSuspenseFallback, ReadingTimeDisplay, useAnimatedMount, type LoadError } from './ReaderStatusView'
import { Sidebar } from './Sidebar'
import { AnnotationPanel } from './AnnotationPanel'
import { SelectionToolbar } from './SelectionToolbar'
import { ReaderControls } from './ReaderControls'
import { TtsBar } from './TtsBar'
import { TranslationPopup } from './TranslationPopup'
import {
  createPagingApi,
  createReaderAdapter,
  createProgressController,
  createSearchController,
  PagingProvider,
  ReaderAdapterProvider,
  type ReaderSelection,
  type TranslationRequest,
} from '../../reader'
import { currentSelectionText } from '../../reader/locator'
import { motionTransition, usePrefersReducedMotion } from './useReducedMotion'
import { formatReadingTime } from '../../utils/format'
import { readBookFile } from '../../utils/fileReader'
import { Dialog } from '../UI/Dialog'
import type { Book } from '../../stores/libraryStore'

const EpubRenderer = lazy(() => import('./EpubRenderer').then(m => ({ default: m.EpubRenderer })))
const PdfRenderer = lazy(() => import('./PdfRenderer').then(m => ({ default: m.PdfRenderer })))
const ComicRenderer = lazy(() => import('./ComicRenderer').then(m => ({ default: m.ComicRenderer })))
const HtmlRenderer = lazy(() => import('./HtmlRenderer').then(m => ({ default: m.HtmlRenderer })))
const TextRenderer = lazy(() => import('./TextRenderer').then(m => ({ default: m.TextRenderer })))
const SettingsPanel = lazy(() => import('../Settings/SettingsPanel').then(m => ({ default: m.SettingsPanel })))

interface ReaderViewProps {
  bookId: number
  onClose: () => void
  /** PB-009-D：分屏阅读入口（App 负责选第二本书） */
  onOpenSplit?: () => void
}

export function ReaderView({ bookId, onClose, onOpenSplit }: ReaderViewProps) {
  const { t } = useTranslation()
  const [book, setBook] = useState<Book | null>(null)
  const [bookContent, setBookContent] = useState<Uint8Array | null>(null)
  const [showSettings, setShowSettings] = useState(false)
  const [showTts, setShowTts] = useState(false)
  // PB-021-D：统一标注面板（annotations API）；与旧 Sidebar 互斥。
  const [showAnnotations, setShowAnnotations] = useState(false)
  const [loadError, setLoadError] = useState<LoadError | null>(null)
  const [confirmRemove, setConfirmRemove] = useState(false)
  // 工具栏手动收起（用户 2026-10-29 要求）：收起后不再随鼠标自动出现，
  // 改为底部中间一个小圆形向上箭头，点它才重新弹出。
  const [chromeCollapsed, setChromeCollapsed] = useState(false)
  // 鼠标贴左右边缘 → 唤出翻页箭头（否则箭头只跟随工具栏，想点时它已经没了）。
  const [nearEdge, setNearEdge] = useState(false)
  // 指针在 chrome（工具栏 / 顶部按钮 / 箭头）上时锁定可见，避免「手刚伸过去它就没了」。
  const [chromeHovered, setChromeHovered] = useState(false)
  const [arrowHovered, setArrowHovered] = useState(false)
  const sessionActiveRef = useRef(false)
  const settingsOpenRef = useRef(false)
  // PB-001-B：Esc 需要知道朗读条是否开着（事件处理器里不能读闭包里的旧 state）
  const ttsOpenRef = useRef(false)
  // PB-021-D：标注面板同理（Esc / 滚轮处理器里要读最新值）
  const annotationsOpenRef = useRef(false)
  const bookIdRef = useRef(bookId)
  // PB-021-E：统一阅读能力（位置 / 选区 / 跳转 / 标注）。format 要等 book 加载；
  // adapter 只是 DOM + store + bridge 的薄封装，不持有渲染器内部状态。
  const readerAdapter = useMemo(
    () => (book ? createReaderAdapter(book.format, bookId) : null),
    [book, bookId],
  )

  // PB-022：共享控制器（位置 / 搜索），工具条与 ReaderControls 复用同一实例。
  const progressController = useMemo(() => createProgressController(readerAdapter), [readerAdapter])
  const searchController = useMemo(() => createSearchController(readerAdapter, { limit: 50 }), [readerAdapter])
  // PB-022：SelectionToolbar 发出的弹层请求（工具条是选区唯一入口）
  const translationSeq = useRef(0)
  const dictionarySeq = useRef(0)
  const [translationRequest, setTranslationRequest] = useState<TranslationRequest | null>(null)
  const [dictionaryRequest, setDictionaryRequest] = useState<{ id: number; text: string } | null>(null)
  // 输入层（键盘 / 点击区域）要读最新的浮层状态，但监听器只注册一次 —— 用 ref 同步。
  const translationRequestRef = useRef<TranslationRequest | null>(null)
  const dictionaryRequestRef = useRef<{ id: number; text: string } | null>(null)
  useEffect(() => { translationRequestRef.current = translationRequest }, [translationRequest])
  useEffect(() => { dictionaryRequestRef.current = dictionaryRequest }, [dictionaryRequest])

  // 工具条动作：翻译/词典把选区作为请求发给对应弹层；朗读打开 TTS（TtsBar 读 adapter 选区）；
  // 高亮直接用 adapter.applyAnnotation 写 annotations bridge（EPUB 选区无 CFI 时返回 null）。
  const handleSelectionTranslate = (selection: ReaderSelection) => {
    setTranslationRequest({
      id: ++translationSeq.current,
      text: selection.text,
      rect: selection.rect,
      // PB-002-B：把 adapter 抓到的前后文一起发给翻译（长句/代词消歧）。
      context: selection.context,
    })
  }
  const handleSelectionDictionary = (selection: ReaderSelection) => {
    setShowControls(true)
    setDictionaryRequest({ id: ++dictionarySeq.current, text: selection.text })
  }
  const handleSelectionRead = () => {
    setShowControls(true)
    setShowTts(true)
  }
  // 工具条「高亮」：优先让当前渲染器弹取色板 —— EPUB 需要 cfiRange、TXT 需要
  // 字符偏移，只有渲染器自己拿得到；渲染器不在（HTML / PDF 等）才退回 adapter 默认色。
  // 2026-10-28：取色板以前在选区结束时自己弹，和工具条叠在一起（用户截图反馈），
  // 现在改为「工具条 → 渲染器 → 取色板」的单一路径。
  const handleSelectionHighlight = (selection: ReaderSelection) => {
    let handled = false
    const onOpened = () => { handled = true }
    window.addEventListener('pb:highlightPaletteOpened', onOpened)
    window.dispatchEvent(new CustomEvent('pb:openHighlightPalette'))
    window.setTimeout(() => {
      window.removeEventListener('pb:highlightPaletteOpened', onOpened)
      // 取色板接管时不用动选区：readerStore.highlightPaletteOpen 已让工具条隐藏，
      // 取色板自己关闭时会收掉选区（两件事都在渲染器里做）。
      if (handled) return
      if (!readerAdapter) return
      void readerAdapter.applyAnnotation({ type: 'highlight', color: '#fbbf24', text: selection.text })
        .then((created) => { if (created) useReaderStore.getState().clearSelection() })
        .catch((error) => console.error('Failed to highlight selection:', error))
    }, 0)
  }

  // PB-022：调试 / E2E 钩子（与 window.__pbReaderAdapter 同风格）
  useEffect(() => {
    ;(window as any).__pbReaderProgress = progressController
    ;(window as any).__pbReaderSearch = searchController
    return () => {
      delete (window as any).__pbReaderProgress
      delete (window as any).__pbReaderSearch
    }
  }, [progressController, searchController])

  // 取色板打开期间隐藏选区工具条（否则两者叠在同一处，用户截图反馈）
  const highlightPaletteOpen = useReaderStore((s) => s.highlightPaletteOpen)
  // 词典弹层挂在工具栏的自动隐藏容器里：容器淡出会让弹层「鼠标一动就消失」。
  // 可见性由 ReaderControls 上报，容器在弹层打开期间保持可见。
  const [dictionaryVisible, setDictionaryVisible] = useState(false)
  const showSidebar = useReaderStore((s) => s.showSidebar)
  const showControls = useReaderStore((s) => s.showControls)
  const controlsLocked = useReaderStore((s) => s.controlsLocked)
  const progress = useReaderStore((s) => s.progress)
  const setShowControls = useReaderStore((s) => s.setShowControls)
  const setShowSidebar = useReaderStore((s) => s.setShowSidebar)
  const loadProgress = useReaderStore((s) => s.loadProgress)
  const loadBookmarks = useReaderStore((s) => s.loadBookmarks)
  const loadHighlights = useReaderStore((s) => s.loadHighlights)
  const loadNotes = useReaderStore((s) => s.loadNotes)
  // 统一翻页：渲染器注册翻页器，输入层（键盘 / 点击 / 滚轮 / 箭头）只调这里。
  // 队列串行执行，连点几下就翻几页（旧实现用 store 的 turnPageDelta，同值不触发）。
  const saveProgress = useReaderStore((s) => s.saveProgress)
  const flushProgress = useReaderStore((s) => s.flushProgress)
  const pendingLocator = useReaderStore((s) => s.pendingLocator)
  const clearPendingLocator = useReaderStore((s) => s.clearPendingLocator)

  // PB-006: search result -> wait for the renderer to mount, then goTo.
  // PB-022：走共享 ProgressController（adapter.getLocator/goTo 的同一封装）。
  useEffect(() => {
    if (!readerAdapter || !book || !pendingLocator) return
    const timer = window.setTimeout(() => {
      Promise.resolve(progressController.applyPending(pendingLocator))
        .finally(() => clearPendingLocator())
    }, 400)
    return () => window.clearTimeout(timer)
  }, [readerAdapter, book, pendingLocator, clearPendingLocator, progressController])
  const setBookId = useReaderStore((s) => s.setBookId)
  const startReadingSession = useReaderStore((s) => s.startReadingSession)
  const endReadingSession = useReaderStore((s) => s.endReadingSession)
  const updateReadingTime = useReaderStore((s) => s.updateReadingTime)
  const showSearch = useReaderStore((s) => s.showSearch)
  const setShowSearch = useReaderStore((s) => s.setShowSearch)
  const clearSearch = useReaderStore((s) => s.clearSearch)

  // 侧栏合并成一个按钮后，标签页按书记忆：「打开侧栏沿用这本书上次关闭前的标签页」。
  // 存 localStorage（与分栏/竖排同一层持久化），键按 bookId 分开。
  const sidebarTab = useReaderStore((s) => s.sidebarTab)
  const sidebarBookRef = useRef(bookId)
  useEffect(() => {
    sidebarBookRef.current = bookId
    let saved: string | null = null
    try { saved = localStorage.getItem('pb.reader.sidebarTab.' + bookId) } catch { /* ignore */ }
    if (saved === 'toc' || saved === 'bookmarks' || saved === 'highlights' || saved === 'notes') {
      useReaderStore.getState().restoreSidebarTab(saved)
    }
  }, [bookId])
  useEffect(() => {
    try { localStorage.setItem('pb.reader.sidebarTab.' + sidebarBookRef.current, sidebarTab) } catch { /* ignore */ }
  }, [sidebarTab])

  // PB-009-A：EPUB 分栏 / 竖排（持久化在 localStorage，见 settingsStore）
  const epubSpread = useSettingsStore((s) => s.epubSpread)
  const verticalText = useSettingsStore((s) => s.verticalText)
  const cycleEpubSpread = useSettingsStore((s) => s.cycleEpubSpread)
  const setVerticalText = useSettingsStore((s) => s.setVerticalText)

  const paging = useMemo(() => createPagingApi(), [bookId])
  const { loadBookSettings, clearBookSettings } = useSettingsStore()
  // 阅读器「chrome」= 顶部返回/标注 + 底部工具栏 + 两侧箭头，统一可见性：
  //   · 自动模式：鼠标在上下热区 / 工具栏被锁定 / 朗读条打开时出现；
  //   · 手动收起后（chromeCollapsed）不再自动出现，只留底部中间的小圆钮重新唤出；
  //   · 两侧箭头额外支持「贴边悬停唤出」——鼠标移到左右边缘就出现，否则点不到。
  // U13：内联 transition 也要尊重「减少动效」（globals.css 覆盖不到内联样式）
  const reducedMotion = usePrefersReducedMotion()
  const chromeVisible = !chromeCollapsed && (showControls || controlsLocked || showTts || chromeHovered)
  const arrowsVisible = chromeVisible || nearEdge || arrowHovered
  const { shouldRender: renderSidebar, isClosing: sidebarClosing } = useAnimatedMount(showSidebar, 200)
  const { shouldRender: renderSettings, isClosing: settingsClosing } = useAnimatedMount(showSettings, 200)
  const { shouldRender: renderAnnotations, isClosing: annotationsClosing } = useAnimatedMount(showAnnotations, 200)

  // 换书（同一个 ReaderView 实例换 bookId，例如「打开方式」转发新文件）：
  // 先清掉上一本的正文，避免继续用旧 content 渲染新 bookId 的工具栏/书签；
  // 并给加载流程一个 cancelled 标记 —— 旧请求的 setBook/setBookContent 必须早退，
  // 否则两次加载交错时会把 A 书塞给 B（review F-06）。
  useEffect(() => {
    bookIdRef.current = bookId
    setBookId(bookId)
    setBook(null)
    setBookContent(null)
    setTranslationRequest(null)
    setDictionaryRequest(null)
    let cancelled = false
    const loadBook = async () => {
      setLoadError(null)
      try {
        const bookData = await window.electronAPI.getBook(bookId)
        if (cancelled) return
        if (!bookData) {
          // db:getBook returns null when the id no longer exists (e.g. the book
          // was deleted from another view while the reader was opening).
          console.error('Book not found:', bookId)
          setLoadError({ kind: 'gone' })
          return
        }
        setBook(bookData)

        // Load progress BEFORE content so renderer has correct position on mount
        await loadProgress(bookId)
        if (cancelled) return
        await loadBookmarks(bookId)
        if (cancelled) return
        await loadHighlights(bookId)
        if (cancelled) return
        await loadNotes(bookId)
        if (cancelled) return
        await loadBookSettings(bookId)
        if (cancelled) return

        const content = await readBookFile(bookData.file_path)
        if (cancelled) return
        if (!content) {
          // readBookFile returns null on virtual-host fetch failure — recover
          // instead of leaving the reader on an infinite spinner.
          console.error('Failed to load book content:', bookData.file_path)
          // 必须清掉书级设置上下文：loadBookSettings 已把 activeBookId 设成本书，
          // 若不清理，之后在设置页改的任何"全局"设置都会被写进这本没能打开的书
          // 的行里，重启后静默回滚（Esc / 返回键那两条路径本来就调了它）。
          clearBookSettings()
          // 不再静默 onClose()：书架记录还在、只是文件没了，要说清楚是哪个路径
          setLoadError({ kind: 'missing', path: bookData.file_path })
          return
        }
        setBookContent(content)
      } catch (e) {
        if (cancelled) return
        console.error('Failed to load book:', e)
        clearBookSettings()
        setLoadError({ kind: 'unknown', detail: e instanceof Error ? e.message : String(e) })
      }
    }
    loadBook()

    return () => {
      cancelled = true
      setBookId(null)
    }
  }, [bookId])

  // Sync settings panel open state to ref for wheel handler
  useEffect(() => {
    settingsOpenRef.current = showSettings
  }, [showSettings])

  // PB-001-B：朗读条状态同步 + 切书时收起并停止朗读
  useEffect(() => {
    ttsOpenRef.current = showTts
  }, [showTts])

  // PB-021-E：把 adapter 暴露出去（上下文 + window 调试钩子），并把当前选区同步到
  // readerStore.selection。主文档用 selectionchange；EPUB iframe 的选区事件不会冒泡
  // 到主文档，所以再补一个 500ms 轮询。
  //
  // review F-01（2026-10-29）：旧实现对每个事件都调 adapter.getSelection()，而它内部
  // 为了算字符偏移会把**整个滚动容器** toString() 再逐码点计数 ——
  // 几 MB 的 TXT 里拖选一次就是几十毫秒，而 selectionchange 在拖动时每次移动都触发。
  // 现在两级处理：
  //   1) currentSelectionText() 只读 Selection 文本，做「选区变了吗」的廉价判定；
  //   2) 变了才做完整计算（text+locator+rect+context），且两次之间至少隔
  //      SYNC_MIN_INTERVAL_MS；拖动过程中的中间帧由尾随定时器补最后一次，
  //      保证松手后的位置/rect 一定是最新的。
  useEffect(() => {
    window.__pbReaderAdapter = readerAdapter
    if (!readerAdapter) return
    const SYNC_MIN_INTERVAL_MS = 150
    let lastText: string | null = null
    let lastRunAt = 0
    let trailing: number | null = null

    // 昂贵路径：算出 locator / rect / context 并写入 store
    const commit = () => {
      lastRunAt = performance.now()
      try {
        const selection = readerAdapter.getSelection()
        lastText = selection?.text ?? null
        const reader = useReaderStore.getState()
        if (selection) reader.setSelection(selection)
        else reader.clearSelection()
      } catch (error) {
        console.debug('ReaderAdapter: selection sync failed', error)
      }
    }

    const sync = () => {
      let text: string | null = null
      try {
        text = currentSelectionText()   // 廉价：不做 locator / rect / context
      } catch { /* ignore */ }
      // 文本没变但 store 里的选区被别处清掉（工具条动作/弹层关闭）→ 重新写入，
      // 否则同一次 DOM 选区再也不会让工具条出现。
      const storeCleared = useReaderStore.getState().selection === null
      if (text === lastText && (text === null) === storeCleared) return
      const wait = lastRunAt + SYNC_MIN_INTERVAL_MS - performance.now()
      if (wait > 0) {
        // 节流窗口内：只保留最后一次补算，避免拖动时每个中间帧都全量计算
        if (trailing === null) {
          trailing = window.setTimeout(() => {
            trailing = null
            commit()
          }, wait)
        }
        return
      }
      commit()
    }

    sync()
    const timer = window.setInterval(sync, 500)
    document.addEventListener('selectionchange', sync)
    document.addEventListener('mouseup', sync)
    document.addEventListener('keyup', sync)
    return () => {
      if (trailing !== null) window.clearTimeout(trailing)
      window.clearInterval(timer)
      document.removeEventListener('selectionchange', sync)
      document.removeEventListener('mouseup', sync)
      document.removeEventListener('keyup', sync)
      if (window.__pbReaderAdapter === readerAdapter) window.__pbReaderAdapter = null
      useReaderStore.getState().clearSelection()
    }
  }, [readerAdapter])


  // PB-021-D：标注面板状态同步 + 与旧侧栏互斥（两个都是全屏遮罩层）
  useEffect(() => {
    annotationsOpenRef.current = showAnnotations
  }, [showAnnotations])

  useEffect(() => {
    if (showAnnotations && showSidebar) setShowSidebar(false)
  }, [showAnnotations, showSidebar, setShowSidebar])

  useEffect(() => {
    // 换书：旧书的朗读必须停（TtsBar 卸载时也会 stop，这里兜住"同实例换书"的情况）
    setShowTts(false)
    setShowAnnotations(false)
    window.electronAPI.ttsStop().catch(() => {})
  }, [bookId])

  // Start reading session when both bookId and content are ready
  useEffect(() => {
    if (!bookContent || !book) return
    if (sessionActiveRef.current) return

    const startSession = async () => {
      try {
        const state = useReaderStore.getState()
        if (state.bookId === null) return
        sessionActiveRef.current = true
        await startReadingSession()
      } catch (e) {
        sessionActiveRef.current = false
        console.error('Failed to start reading session:', e)
      }
    }
    startSession()
  }, [bookContent, book])

  // End session only on actual unmount or bookId change
  useEffect(() => {
    return () => {
      if (sessionActiveRef.current) {
        sessionActiveRef.current = false
        const { flushProgress } = useReaderStore.getState()
        flushProgress(bookIdRef.current).finally(() => endReadingSession())
      }
    }
  }, [bookId])

  // Update the reading-time display every second (was 10s — that read as
  // "not realtime" since it jumped 0→10→20 instead of ticking up smoothly).
  useEffect(() => {
    if (!bookContent) return
    const interval = setInterval(() => {
      updateReadingTime()
    }, 1000)

    return () => clearInterval(interval)
  }, [bookContent])

  useEffect(() => {
    const handleKeyDown = async (e: KeyboardEvent) => {
      // 在输入框/文本域里打字时（含用 Esc 取消编辑），不触发任何全局快捷键
      if (isTypingTarget(e)) return
      if (e.key === 'Escape') {
        // Esc 逐层关闭：搜索栏 → 阅读设置 → 侧边栏 → 最后才退出阅读器。
        // 此前只判断了搜索栏，于是开着设置面板或侧边栏时按 Esc 会直接关掉整本书。
        const { showSearch: srch, clearSearch: cls, showSidebar: sidebar, setShowSidebar } = useReaderStore.getState()
        if (srch) { cls(); return }
        if (settingsOpenRef.current) { setShowSettings(false); return }
        // 朗读条优先于侧边栏关闭：Esc 先收朗读（并停读），再逐层往外
        if (ttsOpenRef.current) { setShowTts(false); return }
        if (annotationsOpenRef.current) { setShowAnnotations(false); return }
        if (sidebar) { setShowSidebar(false); return }
        await endReadingSession()
        await flushProgress()
        clearBookSettings()
        onClose()
      }
      if ((e.ctrlKey || e.metaKey) && e.key === 'f') {
        e.preventDefault()
        setShowSearch(true)
      }
      // ── 统一翻页：所有格式同一套键位（EPUB/CBZ/PDF 翻页，TXT/HTML 按视口滚动）──
      // 浮层（设置 / 标注 / 侧栏 / 搜索 / 翻译 / 词典 / 取色板）打开时不翻页，避免误触。
      const store = useReaderStore.getState()
      const overlayOpen = settingsOpenRef.current || annotationsOpenRef.current ||
        store.showSidebar || store.showSearch || store.highlightPaletteOpen ||
        !!translationRequestRef.current || !!dictionaryRequestRef.current
      // 焦点在按钮/链接上时，空格留给「按一下」语义（否则会同时翻页并触发按钮）
      const focusEl = document.activeElement as HTMLElement | null
      const spaceTaken = !!focusEl && focusEl !== document.body &&
        (focusEl.tagName === 'BUTTON' || focusEl.tagName === 'A' || focusEl.getAttribute('role') === 'button')
      if (!overlayOpen && !e.ctrlKey && !e.metaKey && !e.altKey) {
        const isNext = e.key === 'ArrowRight' || e.key === 'PageDown' || (e.key === ' ' && !spaceTaken)
        const isPrev = e.key === 'ArrowLeft' || e.key === 'PageUp' || (e.key === ' ' && e.shiftKey)
        if (isNext) { e.preventDefault(); paging.next(); return }
        if (isPrev) { e.preventDefault(); paging.prev(); return }
      }
      // 书签快捷键：全格式统一（此前只有 EPUB 阅读器实现了它，而设置页把
      // "B = 添加书签"写成通用快捷键）。切换判断在 store 里与按钮共用一处。
      if ((e.key === 'b' || e.key === 'B') && !e.ctrlKey && !e.metaKey && !e.altKey) {
        void useReaderStore.getState().toggleBookmarkAtProgress()
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [onClose, paging])

  // 滚轮是否落在「正文表面」上（data-reader-surface 那个容器里）。
  // 只认正文表面，而不是「不在可滚浮层里」—— 用户 2026-10-29 反馈：短翻译不需要
  // 滚动时，鼠标停在翻译浮层上滚轮仍会翻页。浮层/工具栏/箭头/页码胶囊都是正文
  // 容器的兄弟节点，只要目标不在正文表面里就一律不翻页。
  // EPUB 的滚轮由 iframe 内容 hook 转发（window.dispatchEvent），事件目标是 window，
  // 视作正文表面。
  const isOverReaderSurface = (target: EventTarget | null): boolean => {
    if (target === window) return true
    return target instanceof Element && target.closest('[data-reader-surface]') !== null
  }

  // Mouse wheel page turning for paginated formats
  useEffect(() => {
    if (!book) return
    const isPaginated = ['epub', 'cbz', 'cbr'].includes(book.format)
    if (!isPaginated) return

    let wheelTimer: ReturnType<typeof setTimeout> | null = null
    const handleWheel = (e: WheelEvent) => {
      // Don't intercept Ctrl+Wheel (used for zoom in PDF)
      if (e.ctrlKey) return

      // 滚轮不在正文上（翻译/词典浮层、工具栏、箭头、页码胶囊…）→ 不拦、不翻页
      if (!isOverReaderSurface(e.target)) return

      // Don't turn pages when sidebar or settings is open
      const { showSidebar } = useReaderStore.getState()
      if (showSidebar || annotationsOpenRef.current || settingsOpenRef.current) return

      e.preventDefault()
      if (wheelTimer) return
      wheelTimer = setTimeout(() => { wheelTimer = null }, 200)

      if (e.deltaY > 0) paging.next()
      else if (e.deltaY < 0) paging.prev()
    }

    window.addEventListener('wheel', handleWheel, { passive: false })
    return () => {
      window.removeEventListener('wheel', handleWheel)
      if (wheelTimer) clearTimeout(wheelTimer)
    }
  }, [book, paging])

  useEffect(() => {
    const handleMouseMove = (e: MouseEvent) => {
      // 贴边唤出翻页箭头（进入 96px 内出现，退出 150px 外才收 —— 中间留滞回带，
      // 否则手在边界上来回几像素，箭头就一闪一闪「乱动」）。
      const x = e.clientX
      const w = window.innerWidth
      if (x <= 96 || x >= w - 96) setNearEdge(true)
      else if (x > 150 && x < w - 150) setNearEdge(false)

      if (controlsLocked || chromeCollapsed || chromeHovered) return
      const y = e.clientY
      const h = window.innerHeight
      // 进入上下热区就显示；只有离开「扩大的」热区才收起（同样是为了不抖）。
      if (y > h - 120 || y < 60) setShowControls(true)
      else if (y < h - 220 && y > 130) setShowControls(false)
    }
    window.addEventListener('mousemove', handleMouseMove)
    return () => {
      window.removeEventListener('mousemove', handleMouseMove)
    }
  }, [controlsLocked, chromeCollapsed, chromeHovered])

  // 打开失败时给一个能看懂、能操作的界面（PB-024-C：整页已抽到 ReaderStatusView）
  if (loadError) {
    const removeBook = async () => {
      try {
        await window.electronAPI.deleteBook(bookId)
      } catch (e) {
        console.error('Failed to delete book:', e)
      }
      clearBookSettings()
      onClose()
    }
    return <ReaderErrorView error={loadError} book={book} onClose={onClose} onRemove={removeBook} />
  }

  if (!book || !bookContent) return <ReaderLoadingView />

  const loadingFallback = <ReaderSuspenseFallback />

  const renderContent = () => {
    const props = { book, content: bookContent, bookId }

    return (
      <Suspense fallback={loadingFallback}>
        {(() => {
          switch (book.format) {
            case 'epub':
              // PB-009-A：分栏 / 竖排只在 EPUB 生效（epub.js iframe 内注入）
              return <EpubRenderer {...props} spread={epubSpread} verticalText={verticalText} />
            case 'pdf': return <PdfRenderer {...props} />
            case 'cbz':
            case 'cbr': return <ComicRenderer {...props} />
            case 'fb2':
            case 'html':
            case 'markdown':
            case 'docx':   // PB-008-A：导入时已转成自包含 HTML
              // HTML/FB2 走 HtmlRenderer：竖排靠继承 writing-mode 实现（仅竖排开关；
              // 分栏对 HTML 不适用，工具栏也不显示分栏按钮）。
              return verticalText && (book.format === 'html' || book.format === 'fb2')
                ? (
                  <div className="h-full" style={{ writingMode: 'vertical-rl' }}>
                    <HtmlRenderer {...props} />
                  </div>
                )
                : <HtmlRenderer {...props} />
            case 'txt':
            case 'mobi':
            case 'azw3':   // PB-008-A：AZW3/AZW 走 mutool 文本管线
            default: return <TextRenderer {...props} />
          }
        })()}
      </Suspense>
    )
  }

  return (
    <ReaderAdapterProvider adapter={readerAdapter}>
    <PagingProvider api={paging}>
    <div className="h-screen flex bg-[var(--reader-bg)] text-[var(--reader-text)] relative">
      {/* Sidebar overlay */}
      {renderSidebar && (
        <div className={`absolute inset-0 z-40 flex ${sidebarClosing ? 'animate-fade-out' : ''}`}>
          {/* Backdrop */}
          <div
            className={`absolute inset-0 ${sidebarClosing ? 'opacity-0' : ''}`}
            style={{ background: 'var(--ui-backdrop)', transition: motionTransition('opacity 0.2s', reducedMotion) }}
            onClick={() => setShowSidebar(false)}
          />
          {/* Panel */}
          <div className={`relative w-80 h-full border-r border-[var(--reader-border)] shadow-2xl ${sidebarClosing ? 'animate-slide-out' : 'animate-slide-in'}`} style={{ backgroundColor: 'var(--reader-sidebar)', opacity: 0.9 }}>
            <Sidebar bookId={bookId} onClose={onClose} />
          </div>
        </div>
      )}

      {/* 标注面板（PB-021-D）：右侧滑入，与旧 Sidebar 互斥 */}
      {renderAnnotations && (
        <div
          className={
            'absolute inset-0 z-40 flex justify-end ' +
            (annotationsClosing ? 'animate-fade-out' : '')
          }
        >
          <div
            className={'absolute inset-0 ' + (annotationsClosing ? 'opacity-0' : '')}
            style={{ background: 'var(--ui-backdrop)', transition: motionTransition('opacity 0.2s', reducedMotion) }}
            onClick={() => setShowAnnotations(false)}
          />
          <div
            className={
              'relative w-96 max-w-[90vw] h-full border-l border-[var(--reader-border)] shadow-2xl ' +
              (annotationsClosing ? 'animate-slide-out-right' : 'animate-slide-in-right')
            }
            style={{ backgroundColor: 'var(--reader-sidebar)', opacity: 0.97 }}
          >
            <AnnotationPanel
              bookId={bookId}
              format={book.format}
              onClose={() => setShowAnnotations(false)}
            />
          </div>
        </div>
      )}

      {/* Main Content */}
      <div className="flex-1 relative overflow-hidden">
        {/* 顶部两个悬浮按钮也跟随 chrome 显隐：此前它们常驻，阅读正文时压住首行文字
            （用户 2026-10-29 反馈「有时会遮挡字」）。鼠标移到顶部即可唤出。 */}
        {chromeVisible && (
          <>
            <button
              onClick={async () => { await endReadingSession(); await flushProgress(); clearBookSettings(); onClose() }}
              className="m3-icon-button absolute top-4 left-4 z-30 !w-12 !h-12"
              style={{ background: 'var(--md-sys-color-surface-container-high)', color: 'var(--md-sys-color-on-surface)', boxShadow: 'var(--md-sys-elevation-1)' }}
              title={t('返回')}
              aria-label={t('返回')}
              data-testid="reader-back"
              onMouseEnter={() => setChromeHovered(true)}
              onMouseLeave={() => setChromeHovered(false)}
            >
              <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
              </svg>
            </button>

            {/* 标注面板入口（PB-021-D）。旧侧栏仍由 ReaderControls 的目录按钮打开；
                两者互斥，本按钮不替代旧侧栏。 */}
            <button
              onClick={() => setShowAnnotations((value) => !value)}
              className="m3-icon-button absolute top-4 right-4 z-30 !w-12 !h-12"
              style={{ background: 'var(--md-sys-color-surface-container-high)', color: 'var(--md-sys-color-on-surface)', boxShadow: 'var(--md-sys-elevation-1)' }}
              title={t('标注')}
              aria-label={t('标注')}
              data-testid="annotation-toggle"
              onMouseEnter={() => setChromeHovered(true)}
              onMouseLeave={() => setChromeHovered(false)}
            >
              <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                      d="M7 8h10M7 12h6m-4 8l-4-4V6a2 2 0 012-2h12a2 2 0 012 2v8a2 2 0 01-2 2H9l-6 6z" />
              </svg>
            </button>
          </>
        )}

        {/* Book content —— 点击左 30% 上一页 / 右 30% 下一页。
            DOM 格式（TXT/HTML/PDF/漫画）的点击在这里处理；EPUB 在 iframe 里，
            由 EpubRenderer 的内容 hook 转发到同一个 paging.click()。 */}
        <div
          className="h-full"
          data-reader-surface="1"
          onClick={(e) => {
            const store = useReaderStore.getState()
            const overlayOpen = showSettings || showAnnotations || showSidebar || showSearch ||
              store.highlightPaletteOpen || !!translationRequest || !!dictionaryRequest
            if (overlayOpen) return
            const selection = window.getSelection()
            if (selection && !selection.isCollapsed) return
            paging.click(e.clientX)
          }}
        >
          {renderContent()}
        </div>

        {/* Page turn arrows：与工具栏同步出现/消失（chromeVisible），不再有独立的
            2 秒淡出计时器 —— 也就没有「隐身但吃点击」「淡出后点不到」的中间态。
            不显示时，同一位置仍是内容点击区（左右 30%），照样能翻页。 */}
        {/* Page turn arrows：**始终挂载**，只切换 opacity —— 位置恒定，不会因为
            显隐条件变化而重新挂载/跳动。不可见时 pointer-events:none，点击会落到
            正文点击区（左右 30%），照样翻页。 */}
        <>
          <button
            onClick={(e) => { e.stopPropagation(); paging.prev() }}
            onMouseEnter={() => setArrowHovered(true)}
            onMouseLeave={() => setArrowHovered(false)}
            className={`m3-icon-button reader-edge-arrow absolute left-2 z-20 !w-12 !h-12 transition-opacity duration-200 ${arrowsVisible ? 'opacity-100' : 'opacity-0 pointer-events-none'}`}
            style={{ top: 'calc(50% - 24px)', background: 'var(--md-sys-color-surface-container-high)', color: 'var(--md-sys-color-on-surface)', boxShadow: 'var(--md-sys-elevation-1)' }}
            title={t('上一页')}
            data-testid="reader-prev-page"
          >
            <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
            </svg>
          </button>
          <button
            onClick={(e) => { e.stopPropagation(); paging.next() }}
            onMouseEnter={() => setArrowHovered(true)}
            onMouseLeave={() => setArrowHovered(false)}
            className={`m3-icon-button reader-edge-arrow absolute right-2 z-20 !w-12 !h-12 transition-opacity duration-200 ${arrowsVisible ? 'opacity-100' : 'opacity-0 pointer-events-none'}`}
            style={{ top: 'calc(50% - 24px)', background: 'var(--md-sys-color-surface-container-high)', color: 'var(--md-sys-color-on-surface)', boxShadow: 'var(--md-sys-elevation-1)' }}
            title={t('下一页')}
            data-testid="reader-next-page"
          >
            <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
            </svg>
          </button>
        </>

        {/* TTS 朗读条 + Controls（PB-001-B） */}
        <div
          className={`absolute bottom-0 left-0 right-0 transition-opacity duration-300 ${(!chromeCollapsed && (showControls || showSearch || showTts || dictionaryVisible || chromeHovered)) ? 'opacity-100' : 'opacity-0 pointer-events-none'}`}
          onMouseEnter={() => setChromeHovered(true)}
          onMouseLeave={() => setChromeHovered(false)}
        >
          {showTts && book && (
            <TtsBar
              bookId={bookId}
              filePath={book.file_path}
              format={book.format}
              onClose={() => setShowTts(false)}
            />
          )}
          <ReaderControls
            bookId={bookId}
            format={book?.format || ''}
            onOpenSettings={() => setShowSettings(true)}
            onOpenTts={() => setShowTts((v) => !v)}
            ttsActive={showTts}
            verticalText={verticalText}
            onCycleSpread={cycleEpubSpread}
            onToggleVertical={() => setVerticalText(!verticalText)}
            dictionaryRequest={dictionaryRequest}
            onDictionaryRequestHandled={() => setDictionaryRequest(null)}
            onDictionaryVisibilityChange={setDictionaryVisible}
            searchController={searchController}
            onOpenSplit={onOpenSplit}
            onCollapse={() => setChromeCollapsed(true)}
          />
        </div>

        {/* 工具栏手动收起后：底部中间的小圆形向上箭头，点它才重新弹出。
            收起状态不再随鼠标自动出现（用户 2026-10-29 要求），阅读区更干净。 */}
        {chromeCollapsed && (
          <button
            onClick={() => { setChromeCollapsed(false); setShowControls(true) }}
            className="m3-icon-button reader-edge-arrow absolute bottom-4 z-30 !w-9 !h-9"
            style={{ left: 'calc(50% - 18px)', background: 'var(--md-sys-color-surface-container-high)', color: 'var(--md-sys-color-on-surface)', boxShadow: 'var(--md-sys-elevation-1)' }}
            title={t('展开工具栏')}
            aria-label={t('展开工具栏')}
            data-testid="reader-chrome-expand"
          >
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 15l7-7 7 7" />
            </svg>
          </button>
        )}

        {/* Page indicator */}
        {/* 页码 / 进度胶囊：统一走 .reader-overlay-chip（容器色 + on-surface）。
            不要再用 inverse-surface —— 暗色主题下它是近白色，会在正文上变成白斑。 */}
        <div
          className="reader-overlay-chip absolute bottom-3 right-3 z-10 backdrop-blur-sm text-xs px-3 py-1.5 rounded-full flex items-center gap-3"
        >
          <span>{['txt','mobi','azw3','fb2','html','markdown','docx'].includes(book.format) ? `${(progress.progress || 0).toFixed(2)}%` : progress.page != null ? t('第 {{page}} 页', { page: progress.page }) : `${Math.round(progress.progress || 0)}%`}</span>
          <span className="opacity-70">|</span>
          <ReadingTimeDisplay />
        </div>
      </div>

      {/* Settings Panel */}
      {renderSettings && (
        <Suspense fallback={null}>
          <SettingsPanel onClose={() => setShowSettings(false)} format={book.format} isClosing={settingsClosing} />
        </Suspense>
      )}

      {/* PB-002-B：选中文本 → 翻译浮层（主文档与 EPUB iframe 都支持）。
          bottomInset 跟着朗读条走，保证浮层不压在 TTS 条上。 */}
      {/* PB-022：选区唯一入口 —— 翻译 / 词典 / 朗读 / 高亮都从这里触发 */}
      <SelectionToolbar
        hidden={highlightPaletteOpen || showSettings || showAnnotations || showSearch || showSidebar || !!translationRequest || !!dictionaryRequest}
        onTranslate={handleSelectionTranslate}
        onDictionary={handleSelectionDictionary}
        onRead={handleSelectionRead}
        onHighlight={handleSelectionHighlight}
      />

      <TranslationPopup
        bottomInset={showTts ? 200 : 140}
        onOpenSettings={() => setShowSettings(true)}
        request={translationRequest}
        onDismiss={() => setTranslationRequest(null)}
      />
    </div>
    </PagingProvider>
    </ReaderAdapterProvider>
  )
}