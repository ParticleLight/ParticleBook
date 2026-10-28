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
  createReaderAdapter,
  createProgressController,
  createSearchController,
  ReaderAdapterProvider,
  type ReaderSelection,
  type TranslationRequest,
} from '../../reader'
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
  const [showArrows, setShowArrows] = useState(false)
  const [loadError, setLoadError] = useState<LoadError | null>(null)
  const [confirmRemove, setConfirmRemove] = useState(false)
  const arrowTimerRef = useRef<number | null>(null)
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
  const turnPage = useReaderStore((s) => s.turnPage)
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

  const { loadBookSettings, clearBookSettings } = useSettingsStore()
  const { shouldRender: renderSidebar, isClosing: sidebarClosing } = useAnimatedMount(showSidebar, 200)
  const { shouldRender: renderSettings, isClosing: settingsClosing } = useAnimatedMount(showSettings, 200)
  const { shouldRender: renderAnnotations, isClosing: annotationsClosing } = useAnimatedMount(showAnnotations, 200)

  useEffect(() => {
    bookIdRef.current = bookId
    setBookId(bookId)
    const loadBook = async () => {
      setLoadError(null)
      try {
        const bookData = await window.electronAPI.getBook(bookId)
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
        await loadBookmarks(bookId)
        await loadHighlights(bookId)
        await loadNotes(bookId)
        await loadBookSettings(bookId)

        const content = await readBookFile(bookData.file_path)
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
        console.error('Failed to load book:', e)
        clearBookSettings()
        setLoadError({ kind: 'unknown', detail: e instanceof Error ? e.message : String(e) })
      }
    }
    loadBook()

    return () => { setBookId(null) }
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
  // 到主文档，所以再补一个 500ms 轮询（只在文本变化时写 store，避免无谓重渲染）。
  useEffect(() => {
    window.__pbReaderAdapter = readerAdapter
    if (!readerAdapter) return
    let lastText: string | null = null
    const sync = () => {
      try {
        const selection = readerAdapter.getSelection()
        const text = selection?.text ?? null
        // 文本没变但 store 里的选区被别处清掉（工具条动作/弹层关闭）→ 重新写入，
        // 否则同一次 DOM 选区再也不会让工具条出现。
        const storeCleared = useReaderStore.getState().selection === null
        if (text === lastText && (text === null) === storeCleared) return
        lastText = text
        const reader = useReaderStore.getState()
        if (selection) reader.setSelection(selection)
        else reader.clearSelection()
      } catch (error) {
        console.debug('ReaderAdapter: selection sync failed', error)
      }
    }
    sync()
    const timer = window.setInterval(sync, 500)
    document.addEventListener('selectionchange', sync)
    document.addEventListener('mouseup', sync)
    document.addEventListener('keyup', sync)
    return () => {
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
      // 书签快捷键：全格式统一（此前只有 EPUB 阅读器实现了它，而设置页把
      // "B = 添加书签"写成通用快捷键）。切换判断在 store 里与按钮共用一处。
      if ((e.key === 'b' || e.key === 'B') && !e.ctrlKey && !e.metaKey && !e.altKey) {
        void useReaderStore.getState().toggleBookmarkAtProgress()
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [onClose])

  // Mouse wheel page turning for paginated formats
  useEffect(() => {
    if (!book) return
    const isPaginated = ['epub', 'cbz', 'cbr'].includes(book.format)
    if (!isPaginated) return

    let wheelTimer: ReturnType<typeof setTimeout> | null = null
    const handleWheel = (e: WheelEvent) => {
      // Don't intercept Ctrl+Wheel (used for zoom in PDF)
      if (e.ctrlKey) return

      // Don't turn pages when sidebar or settings is open
      const { showSidebar } = useReaderStore.getState()
      if (showSidebar || annotationsOpenRef.current || settingsOpenRef.current) return

      e.preventDefault()
      if (wheelTimer) return
      wheelTimer = setTimeout(() => { wheelTimer = null }, 200)

      if (e.deltaY > 0) turnPage(1)
      else if (e.deltaY < 0) turnPage(-1)
    }

    window.addEventListener('wheel', handleWheel, { passive: false })
    return () => {
      window.removeEventListener('wheel', handleWheel)
      if (wheelTimer) clearTimeout(wheelTimer)
    }
  }, [book, turnPage])

  useEffect(() => {
    const handleMouseMove = (e: MouseEvent) => {
      // 翻页箭头和工具栏自动隐藏解耦：controlsLocked 只是「别把工具栏收起来」
      // （添加笔记时会置位）。若连 mousemove 一起 return，箭头隐藏后永远不再出现，
      // 表现就是「点了下底部工具栏，翻页键就不管用了」（2026-10-28 反馈）。
      setShowArrows(true)
      if (arrowTimerRef.current) clearTimeout(arrowTimerRef.current)
      arrowTimerRef.current = window.setTimeout(() => setShowArrows(false), 2000)

      if (controlsLocked) return
      const y = e.clientY
      const h = window.innerHeight
      setShowControls(y > h - 120 || y < 60)
    }
    window.addEventListener('mousemove', handleMouseMove)
    return () => {
      window.removeEventListener('mousemove', handleMouseMove)
      if (arrowTimerRef.current) {
        clearTimeout(arrowTimerRef.current)
        arrowTimerRef.current = null
      }
    }
  }, [controlsLocked])

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
    <div className="h-screen flex bg-[var(--reader-bg)] text-[var(--reader-text)] relative">
      {/* Sidebar overlay */}
      {renderSidebar && (
        <div className={`absolute inset-0 z-40 flex ${sidebarClosing ? 'animate-fade-out' : ''}`}>
          {/* Backdrop */}
          <div className={`absolute inset-0 bg-black/40 ${sidebarClosing ? 'opacity-0' : ''}`} style={{ transition: 'opacity 0.2s' }} onClick={() => setShowSidebar(false)} />
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
            className={'absolute inset-0 bg-black/40 ' + (annotationsClosing ? 'opacity-0' : '')}
            style={{ transition: 'opacity 0.2s' }}
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
        {/* Back button */}
        <button
          onClick={async () => { await endReadingSession(); await flushProgress(); clearBookSettings(); onClose() }}
          className="m3-icon-button absolute top-4 left-4 z-30 !w-12 !h-12"
          style={{ background: 'var(--md-sys-color-surface-container-high)', color: 'var(--md-sys-color-on-surface)', boxShadow: 'var(--md-sys-elevation-1)' }}
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
        >
          <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                  d="M7 8h10M7 12h6m-4 8l-4-4V6a2 2 0 012-2h12a2 2 0 012 2v8a2 2 0 01-2 2H9l-6 6z" />
          </svg>
        </button>

        {/* Book content */}
        <div className="h-full">
          {renderContent()}
        </div>

        {/* Page turn arrows */}
        <button
          onClick={(e) => { e.stopPropagation(); turnPage(-1) }}
          className={`m3-icon-button absolute left-2 top-1/2 -translate-y-1/2 z-20 !w-12 !h-12 ${showArrows ? 'opacity-100' : 'opacity-0 pointer-events-none'}`}
          style={{ background: 'var(--md-sys-color-surface-container-high)', color: 'var(--md-sys-color-on-surface)', boxShadow: 'var(--md-sys-elevation-1)' }}
          title={t('上一页')}
        >
          <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
          </svg>
        </button>
        <button
          onClick={(e) => { e.stopPropagation(); turnPage(1) }}
          className={`m3-icon-button absolute right-2 top-1/2 -translate-y-1/2 z-20 !w-12 !h-12 ${showArrows ? 'opacity-100' : 'opacity-0 pointer-events-none'}`}
          style={{ background: 'var(--md-sys-color-surface-container-high)', color: 'var(--md-sys-color-on-surface)', boxShadow: 'var(--md-sys-elevation-1)' }}
          title={t('下一页')}
        >
          <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
          </svg>
        </button>

        {/* TTS 朗读条 + Controls（PB-001-B） */}
        <div className={`absolute bottom-0 left-0 right-0 transition-opacity duration-300 ${(showControls || showSearch || showTts || dictionaryVisible) ? 'opacity-100' : 'opacity-0 pointer-events-none'}`}>
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
            epubSpread={epubSpread}
            verticalText={verticalText}
            onCycleSpread={cycleEpubSpread}
            onToggleVertical={() => setVerticalText(!verticalText)}
            dictionaryRequest={dictionaryRequest}
            onDictionaryRequestHandled={() => setDictionaryRequest(null)}
            onDictionaryVisibilityChange={setDictionaryVisible}
            searchController={searchController}
            onOpenSplit={onOpenSplit}
          />
        </div>

        {/* Page indicator */}
        <div className="absolute bottom-3 right-3 z-10 bg-black/40 backdrop-blur-sm text-white/70 text-xs px-3 py-1.5 rounded-full flex items-center gap-3">
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
    </ReaderAdapterProvider>
  )
}
