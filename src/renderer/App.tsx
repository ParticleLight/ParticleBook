import { useState, useEffect, useCallback, useRef, lazy, Suspense } from 'react'
import { Library } from './components/Library/Library'
import { ReaderView } from './components/Reader/ReaderView'
import { UpdateBanner } from './components/UI/UpdateBanner'
import { useSettingsStore } from './stores/settingsStore'
import { useLibraryStore } from './stores/libraryStore'

const GlobalSettings = lazy(() => import('./components/Settings/GlobalSettings').then(m => ({ default: m.GlobalSettings })))
const StatisticsPage = lazy(() => import('./components/Library/StatisticsPage').then(m => ({ default: m.StatisticsPage })))

type Page = 'library' | 'settings' | 'statistics'

const PageLoader = () => (
  <div className="h-screen flex items-center justify-center" style={{ background: 'var(--bg)' }}>
    <div className="w-8 h-8 rounded-full border-2 border-[var(--border)] border-t-[var(--accent)] animate-spin" />
  </div>
)

const ZlibLoadingOverlay = () => (
  <div className="fixed inset-0 z-50 flex flex-col items-center justify-center animate-fade-in" style={{ background: 'var(--bg)' }}>
    <div className="w-10 h-10 rounded-full border-2 border-[var(--border)] border-t-[var(--accent)] animate-spin mb-4" />
    <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>正在连接 Z-Library...</p>
  </div>
)

const ZlibFailedBanner = ({ onDismiss }: { onDismiss: () => void }) => (
  <div className="fixed top-0 left-0 right-0 z-50 flex items-center justify-between px-4 py-2 animate-fade-in"
    style={{ background: 'rgba(220,38,38,0.9)', backdropFilter: 'blur(8px)', color: '#fff' }}>
    <span className="text-sm">所有 Z-Library 镜像暂时不可达，请稍后重试或手动切换线路</span>
    <button onClick={onDismiss} className="text-white opacity-70 hover:opacity-100 ml-4 text-lg leading-none">&times;</button>
  </div>
)

const PageShell = ({ children, show }: { children: React.ReactNode; show: boolean }) => (
  <div className={`h-screen overflow-hidden ${show ? 'animate-fade-in' : ''}`}>
    <UpdateBanner />
    {children}
  </div>
)

export default function App() {
  const [currentBookId, setCurrentBookId] = useState<number | null>(null)
  const [page, setPage] = useState<Page>('library')
  const [pageKey, setPageKey] = useState(0)
  const [zlibLoading, setZlibLoading] = useState(false)
  const [zlibAllFailed, setZlibAllFailed] = useState(false)
  const theme = useSettingsStore((s) => s.theme)
  const accentColor = useSettingsStore((s) => s.accentColor)
  const loadBooks = useLibraryStore((s) => s.loadBooks)
  const zlibTimer = useRef<ReturnType<typeof setTimeout>>()

  useEffect(() => { loadBooks() }, [loadBooks])

  // Auto check for updates on startup
  useEffect(() => {
    let cancelled = false
    const check = async () => {
      try {
        const info = await window.electronAPI.checkUpdate()
        if (!cancelled && info?.version) {
          window.dispatchEvent(new CustomEvent('pb:updateAvailable', { detail: info }))
        }
      } catch {}
    }
    const t = setTimeout(check, 2000)
    return () => { cancelled = true; clearTimeout(t) }
  }, [])

  useEffect(() => {
    const root = document.documentElement
    root.classList.remove('dark', 'light', 'sepia')
    root.classList.add(theme)
  }, [theme])

  useEffect(() => {
    const root = document.documentElement
    if (accentColor === 'blue') {
      root.removeAttribute('data-accent')
    } else {
      root.setAttribute('data-accent', accentColor)
    }
  }, [accentColor])

  useEffect(() => {
    return window.electronAPI.onMenuShowAbout(() => setPage('settings'))
  }, [])

  // Z-Library: subscribe to mirror events for overlay + failure banner
  useEffect(() => {
    const unsub1 = window.electronAPI.onZlibMirrorChanged(() => {
      if (zlibTimer.current) { clearTimeout(zlibTimer.current); zlibTimer.current = undefined }
      setZlibLoading(false)
    })
    const unsub2 = window.electronAPI.onZlibAllMirrorsFailed(() => {
      if (zlibTimer.current) { clearTimeout(zlibTimer.current); zlibTimer.current = undefined }
      setZlibLoading(false)
      setZlibAllFailed(true)
    })
    return () => { unsub1(); unsub2() }
  }, [])

  const navigateTo = useCallback((p: Page) => {
    setPage(p)
    setPageKey(k => k + 1)
  }, [])

  const openBook = useCallback((bookId: number) => {
    setCurrentBookId(bookId)
  }, [])

  const closeBook = useCallback(() => {
    setCurrentBookId(null)
    loadBooks()
  }, [loadBooks])

  const openZLibrary = useCallback(() => {
    setZlibAllFailed(false)
    setZlibLoading(true)
    zlibTimer.current = setTimeout(() => setZlibLoading(false), 4000)
    window.electronAPI.zlibShow()
  }, [])

  if (currentBookId !== null) {
    return (
      <PageShell show>
        <ReaderView bookId={currentBookId} onClose={closeBook} />
      </PageShell>
    )
  }

  if (page === 'settings') {
    return (
      <PageShell show key={`settings-${pageKey}`}>
        <Suspense fallback={<PageLoader />}>
          <GlobalSettings onBack={() => navigateTo('library')} />
        </Suspense>
      </PageShell>
    )
  }

  if (page === 'statistics') {
    return (
      <PageShell show key={`statistics-${pageKey}`}>
        <Suspense fallback={<PageLoader />}>
          <StatisticsPage onBack={() => navigateTo('library')} />
        </Suspense>
      </PageShell>
    )
  }

  return (
    <PageShell show key="library">
      {zlibAllFailed && <ZlibFailedBanner onDismiss={() => setZlibAllFailed(false)} />}
      {zlibLoading && <ZlibLoadingOverlay />}
      <Library
        onOpenBook={openBook}
        onOpenSettings={() => navigateTo('settings')}
        onOpenZLibrary={openZLibrary}
        onOpenStatistics={() => navigateTo('statistics')}
      />
    </PageShell>
  )
}
