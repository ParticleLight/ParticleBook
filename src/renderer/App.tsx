import { useState, useEffect, useCallback, useRef, lazy, Suspense } from 'react'
import { useTranslation } from 'react-i18next'
import { Library } from './components/Library/Library'
import { ReaderView } from './components/Reader/ReaderView'
import { UpdateBanner } from './components/UI/UpdateBanner'
import { useSettingsStore } from './stores/settingsStore'
import { useLibraryStore } from './stores/libraryStore'

const GlobalSettings = lazy(() => import('./components/Settings/GlobalSettings').then(m => ({ default: m.GlobalSettings })))
const StatisticsPage = lazy(() => import('./components/Library/StatisticsPage').then(m => ({ default: m.StatisticsPage })))

// Only auto-check for updates once per app session — the check useEffect below
// runs on every App mount, and going in/out of Z-Library reloads the page (and
// thus remounts App), which would otherwise re-trigger the check each time.
let autoCheckFired = false

type Page = 'library' | 'settings' | 'statistics'

const PageLoader = () => (
  <div className="h-screen flex items-center justify-center" style={{ background: 'var(--bg)' }}>
    <div className="w-8 h-8 rounded-full border-2 border-[var(--border)] border-t-[var(--accent)] animate-spin" />
  </div>
)

const ZlibLoadingOverlay = () => {
  const { t } = useTranslation()
  return (
    <div className="fixed inset-0 z-50 flex flex-col items-center justify-center animate-fade-in" style={{ background: 'var(--bg)' }}>
      <div className="w-10 h-10 rounded-full border-2 border-[var(--border)] border-t-[var(--accent)] animate-spin mb-4" />
      <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>{t('正在连接 Z-Library...')}</p>
    </div>
  )
}

const ZlibFailedBanner = ({ onDismiss }: { onDismiss: () => void }) => {
  const { t } = useTranslation()
  return (
    <div className="fixed top-0 left-0 right-0 z-50 flex items-center justify-between px-4 py-2 animate-fade-in"
      style={{ background: 'rgba(220,38,38,0.9)', backdropFilter: 'blur(8px)', color: '#fff' }}>
      <span className="text-sm">{t('所有 Z-Library 镜像暂时不可达，请稍后重试或手动切换线路')}</span>
      <button onClick={onDismiss} className="text-white opacity-70 hover:opacity-100 ml-4 text-lg leading-none">&times;</button>
    </div>
  )
}

// C++ 只发机器可读错误码（见 ZLibraryService 的 fail()），在此本地化 ——
// 与 bookSource 下载失败同一处理方式，后端不产出未翻译的用户可见文案。
const ZLIB_DOWNLOAD_ERROR_KEYS: Record<string, string> = {
  invalid_url: '下载地址无效',
  http_open_failed: '无法建立下载连接',
  connect_failed: '连接下载服务器失败',
  request_failed: '下载请求失败',
  network_error: '网络错误，下载中断',
  file_create_failed: '无法创建本地文件',
  empty_response: '下载内容为空',
  too_many_redirects: '重定向次数过多',
  // 与注入工具栏（App.cpp 的 emap）保持一致：那份是 Z-Library 会话期间的活界面，
  // 这份只在「返回书架后下载才失败」时才有机会显示。
  incomplete_download: '下载不完整（连接中断）',
  not_a_book: '不是电子书文件（可能是登录页）',
  file_write_failed: '写入本地文件失败'
}

const ZlibDownloadFailedBanner = ({ code, onDismiss }: { code: string; onDismiss: () => void }) => {
  const { t } = useTranslation()
  // http_<状态码> 是动态生成的，单独归类
  const reason = ZLIB_DOWNLOAD_ERROR_KEYS[code]
    ?? (/^http_\d+$/.test(code) ? t('服务器返回错误 {{code}}', { code: code.slice(5) }) : '未知错误')
  return (
    <div className="fixed top-0 left-0 right-0 z-50 flex items-center justify-between px-4 py-2 animate-fade-in"
      style={{ background: 'rgba(220,38,38,0.9)', backdropFilter: 'blur(8px)', color: '#fff' }}>
      <span className="text-sm">{t('Z-Library 下载失败：{{reason}}', { reason: t(reason) })}</span>
      <button onClick={onDismiss} className="text-white opacity-70 hover:opacity-100 ml-4 text-lg leading-none">&times;</button>
    </div>
  )
}

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
  const [zlibDownloadError, setZlibDownloadError] = useState<string | null>(null)
  const theme = useSettingsStore((s) => s.theme)
  const accentColor = useSettingsStore((s) => s.accentColor)
  const loadBooks = useLibraryStore((s) => s.loadBooks)
  const loadSettings = useSettingsStore((s) => s.loadSettings)
  const zlibTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)

  useEffect(() => { loadBooks() }, [loadBooks])
  // Load persisted global settings (theme, language, …) on startup — was never
  // called on boot before, so theme/language didn't restore until opening a book.
  useEffect(() => { loadSettings() }, [loadSettings])

  // Auto check for updates on startup — checkUpdate runs on a C++ background
  // thread and the result arrives via the app:updateChecked event (the invoke
  // itself returns immediately, so startup never blocks on GitHub).
  useEffect(() => {
    if (autoCheckFired) return
    autoCheckFired = true
    let cancelled = false
    const onChecked = (info: any) => {
      if (!cancelled && info?.version) {
        window.dispatchEvent(new CustomEvent('pb:updateAvailable', { detail: info }))
      }
    }
    const unsub = window.electronAPI.onUpdateChecked(onChecked)
    const t = setTimeout(() => { window.electronAPI.checkUpdate().catch(() => {}) }, 2000)
    return () => { cancelled = true; clearTimeout(t); unsub() }
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

  // 这里原有一个 onMenuShowAbout 订阅（用于菜单项跳到设置页），但本应用【没有原生菜单】，
  // 且 C++ 从未 emit menu:showAbout —— 该订阅永不触发，已随死接口一并移除。

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
    // 下载失败此前完全静默：C++ 的 9 条失败路径都在发 zlib:downloadError，
    // 但存根里没有可订阅的成员（本次补上 onZlibDownloadError）。
    const unsub3 = window.electronAPI.onZlibDownloadError((d) => {
      console.error('Z-Library download failed:', d.fileName, d.error)
      setZlibDownloadError(d.error)
    })
    return () => { unsub1(); unsub2(); unsub3() }
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
      {zlibDownloadError && (
        <ZlibDownloadFailedBanner code={zlibDownloadError} onDismiss={() => setZlibDownloadError(null)} />
      )}
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
