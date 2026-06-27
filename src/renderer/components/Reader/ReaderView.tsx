import { useEffect, useState, useRef, useCallback, lazy, Suspense } from 'react'
import { useReaderStore } from '../../stores/readerStore'
import { useSettingsStore } from '../../stores/settingsStore'

function useAnimatedMount(isOpen: boolean, duration = 200) {
  const [shouldRender, setShouldRender] = useState(isOpen)
  const [isClosing, setIsClosing] = useState(false)

  useEffect(() => {
    if (isOpen) {
      setShouldRender(true)
      setIsClosing(false)
    } else if (shouldRender) {
      setIsClosing(true)
      const timer = setTimeout(() => {
        setShouldRender(false)
        setIsClosing(false)
      }, duration)
      return () => clearTimeout(timer)
    }
  }, [isOpen, shouldRender])

  return { shouldRender, isClosing }
}
import { Sidebar } from './Sidebar'
import { ReaderControls } from './ReaderControls'
import { formatReadingTime } from '../../utils/format'
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
}

function ReadingTimeDisplay() {
  const currentReadingTime = useReaderStore((s) => s.currentReadingTime)
  return (
    <span title="本次阅读时长">
      <svg className="w-3 h-3 inline mr-1" fill="none" viewBox="0 0 24 24" stroke="currentColor">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
      </svg>
      {formatReadingTime(currentReadingTime)}
    </span>
  )
}

export function ReaderView({ bookId, onClose }: ReaderViewProps) {
  const [book, setBook] = useState<Book | null>(null)
  const [bookContent, setBookContent] = useState<Uint8Array | null>(null)
  const [showSettings, setShowSettings] = useState(false)
  const [showArrows, setShowArrows] = useState(false)
  const arrowTimerRef = useRef<number | null>(null)
  const sessionActiveRef = useRef(false)
  const settingsOpenRef = useRef(false)
  const bookIdRef = useRef(bookId)

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
  const setBookId = useReaderStore((s) => s.setBookId)
  const startReadingSession = useReaderStore((s) => s.startReadingSession)
  const endReadingSession = useReaderStore((s) => s.endReadingSession)
  const updateReadingTime = useReaderStore((s) => s.updateReadingTime)
  const showSearch = useReaderStore((s) => s.showSearch)
  const setShowSearch = useReaderStore((s) => s.setShowSearch)
  const clearSearch = useReaderStore((s) => s.clearSearch)

  const { loadBookSettings, clearBookSettings } = useSettingsStore()
  const { shouldRender: renderSidebar, isClosing: sidebarClosing } = useAnimatedMount(showSidebar, 200)
  const { shouldRender: renderSettings, isClosing: settingsClosing } = useAnimatedMount(showSettings, 200)

  useEffect(() => {
    bookIdRef.current = bookId
    setBookId(bookId)
    const loadBook = async () => {
      try {
        const bookData = await window.electronAPI.getBook(bookId)
        setBook(bookData)

        // Load progress BEFORE content so renderer has correct position on mount
        await loadProgress(bookId)
        await loadBookmarks(bookId)
        await loadHighlights(bookId)
        await loadNotes(bookId)
        await loadBookSettings(bookId)

        const content = await window.electronAPI.readFile(bookData.file_path)
        setBookContent(new Uint8Array(content))
      } catch (e) {
        console.error('Failed to load book:', e)
        onClose()
      }
    }
    loadBook()

    return () => { setBookId(null) }
  }, [bookId])

  // Sync settings panel open state to ref for wheel handler
  useEffect(() => {
    settingsOpenRef.current = showSettings
  }, [showSettings])

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

  // Update reading time display every 10 seconds (reduces store updates)
  useEffect(() => {
    if (!bookContent) return
    const interval = setInterval(() => {
      updateReadingTime()
    }, 10000)

    return () => clearInterval(interval)
  }, [bookContent])

  useEffect(() => {
    const handleKeyDown = async (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        const { showSearch: srch, clearSearch: cls } = useReaderStore.getState()
        if (srch) { cls(); return }
        await endReadingSession()
        await flushProgress()
        clearBookSettings()
        onClose()
      }
      if ((e.ctrlKey || e.metaKey) && e.key === 'f') {
        e.preventDefault()
        setShowSearch(true)
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
      if (showSidebar || settingsOpenRef.current) return

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
      if (controlsLocked) return
      const y = e.clientY
      const h = window.innerHeight
      setShowControls(y > h - 120 || y < 60)

      setShowArrows(true)
      if (arrowTimerRef.current) clearTimeout(arrowTimerRef.current)
      arrowTimerRef.current = window.setTimeout(() => setShowArrows(false), 2000)
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

  if (!book || !bookContent) {
    return (
      <div className="h-screen flex items-center justify-center bg-gray-900">
        <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-indigo-500"></div>
      </div>
    )
  }

  const loadingFallback = (
    <div className="h-full flex items-center justify-center">
      <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-indigo-500"></div>
    </div>
  )

  const renderContent = () => {
    const props = { book, content: bookContent, bookId }

    return (
      <Suspense fallback={loadingFallback}>
        {(() => {
          switch (book.format) {
            case 'epub': return <EpubRenderer {...props} />
            case 'pdf': return <PdfRenderer {...props} />
            case 'cbz':
            case 'cbr': return <ComicRenderer {...props} />
            case 'fb2':
            case 'html':
            case 'markdown': return <HtmlRenderer {...props} />
            case 'txt':
            case 'mobi':
            default: return <TextRenderer {...props} />
          }
        })()}
      </Suspense>
    )
  }

  return (
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

      {/* Main Content */}
      <div className="flex-1 relative overflow-hidden">
        {/* Back button */}
        <button
          onClick={async () => { await endReadingSession(); await flushProgress(); clearBookSettings(); onClose() }}
          className="absolute top-4 left-4 z-30 p-2 rounded-lg bg-black/20 hover:bg-black/40 text-white/70 hover:text-white transition-all backdrop-blur-sm"
        >
          <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
          </svg>
        </button>

        {/* Book content */}
        <div className="h-full">
          {renderContent()}
        </div>

        {/* Page turn arrows */}
        <button
          onClick={(e) => { e.stopPropagation(); turnPage(-1) }}
          className={`absolute left-2 top-1/2 -translate-y-1/2 z-20 p-3 rounded-full bg-black/30 hover:bg-black/50 text-white/60 hover:text-white transition-all duration-300 backdrop-blur-sm ${showArrows ? 'opacity-100' : 'opacity-0 pointer-events-none'}`}
          title="上一页"
        >
          <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
          </svg>
        </button>
        <button
          onClick={(e) => { e.stopPropagation(); turnPage(1) }}
          className={`absolute right-2 top-1/2 -translate-y-1/2 z-20 p-3 rounded-full bg-black/30 hover:bg-black/50 text-white/60 hover:text-white transition-all duration-300 backdrop-blur-sm ${showArrows ? 'opacity-100' : 'opacity-0 pointer-events-none'}`}
          title="下一页"
        >
          <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
          </svg>
        </button>

        {/* Controls */}
        <div className={`absolute bottom-0 left-0 right-0 transition-opacity duration-300 ${(showControls || showSearch) ? 'opacity-100' : 'opacity-0 pointer-events-none'}`}>
          <ReaderControls bookId={bookId} format={book?.format || ''} onOpenSettings={() => setShowSettings(true)} />
        </div>

        {/* Page indicator */}
        <div className="absolute bottom-3 right-3 z-10 bg-black/40 backdrop-blur-sm text-white/70 text-xs px-3 py-1.5 rounded-full flex items-center gap-3">
          <span>{['txt','mobi','fb2','html','markdown'].includes(book.format) ? `${(progress.progress || 0).toFixed(2)}%` : progress.page != null ? `第 ${progress.page} 页` : `${Math.round(progress.progress || 0)}%`}</span>
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
    </div>
  )
}
