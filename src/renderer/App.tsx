import { useState, useEffect, useCallback, useRef, lazy, Suspense } from 'react'
import { useTranslation } from 'react-i18next'
import { Library } from './components/Library/Library'
import { SplitBookPickerDialog } from './components/Library/SplitBookPickerDialog'
import { ReaderView } from './components/Reader/ReaderView'
import { UpdateBanner } from './components/UI/UpdateBanner'
import { useSettingsStore } from './stores/settingsStore'
import { useLibraryStore } from './stores/libraryStore'

// PB-009-D：分屏视图按需加载（内含 epub.js，避免启动即拉进来）
const SplitReaderView = lazy(() => import('./components/Reader/SplitReaderView').then(m => ({ default: m.SplitReaderView })))
const GlobalSettings = lazy(() => import('./components/Settings/GlobalSettings').then(m => ({ default: m.GlobalSettings })))
const StatisticsPage = lazy(() => import('./components/Library/StatisticsPage').then(m => ({ default: m.StatisticsPage })))
const DownloadHistoryPage = lazy(() => import('./components/Library/DownloadHistoryPage').then(m => ({ default: m.DownloadHistoryPage })))

// Only auto-check for updates once per app session — the check useEffect below
// runs on every App mount, and going in/out of Z-Library reloads the page (and
// thus remounts App), which would otherwise re-trigger the check each time.
let autoCheckFired = false

type Page = 'library' | 'settings' | 'statistics' | 'downloads'

const PageLoader = () => (
  <div className="h-screen flex items-center justify-center" style={{ background: 'var(--md-sys-color-surface)' }} role="status" aria-label="Loading">
    <div className="w-9 h-9 rounded-full border-2 animate-spin" style={{ borderColor: 'var(--md-sys-color-surface-container-highest)', borderTopColor: 'var(--md-sys-color-primary)' }} />
  </div>
)

// Z-Library 进站遮罩（2026-10-29）：
//  * 从顶部工具栏（.m3-top-app-bar，64px）下面开始铺，工具栏保持可见可点 —— 用户
//    连接期间还能切页面 / 取消，不会卡在一个全屏转圈里；
//  * 提供「取消」：撤掉遮罩并让 C++ 放弃这次进站（zlibHide 会 ReloadPage 回到书架）。
const ZlibLoadingOverlay = ({ onCancel }: { onCancel: () => void }) => {
  const { t } = useTranslation()
  return (
    <div className="fixed left-0 right-0 bottom-0 top-16 z-50 flex flex-col items-center justify-center animate-fade-in" style={{ background: 'var(--md-sys-color-surface)' }} role="status" aria-live="polite">
      <div className="w-10 h-10 rounded-full border-2 animate-spin mb-4" style={{ borderColor: 'var(--md-sys-color-surface-container-highest)', borderTopColor: 'var(--md-sys-color-primary)' }} />
      <p className="m3-body-medium mb-4" style={{ color: 'var(--md-sys-color-on-surface-variant)' }}>{t('正在连接 Z-Library...')}</p>
      <button type="button" onClick={onCancel} className="m3-outlined-button" data-testid="zlib-loading-cancel">{t('取消')}</button>
    </div>
  )
}

const ZlibFailedBanner = ({ onDismiss }: { onDismiss: () => void }) => {
  const { t } = useTranslation()
  return (
    <div className="fixed top-0 left-0 right-0 z-50 flex items-center justify-between px-4 py-3 animate-fade-in"
      // 同 UpdateBanner：--md-sys-color-error-container 映射到 --color-red-bg，是 alpha 8%~15% 的 rgba，
      // 单用它当背景会半透明、让底下的顶栏文字透上来和横幅文字叠在一起；叠一层不透明的页面底色 --bg。
      style={{ background: 'linear-gradient(var(--md-sys-color-error-container), var(--md-sys-color-error-container)), var(--bg)', color: 'var(--md-sys-color-on-error-container)', borderBottom: '1px solid var(--md-sys-color-error)' }} role="alert">
      <span className="m3-body-medium">{t('所有 Z-Library 镜像暂时不可达，请稍后重试或手动切换线路')}</span>
      <button onClick={onDismiss} className="m3-icon-button" aria-label={t('关闭')} title={t('关闭')}>×</button>
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
    <div className="fixed top-0 left-0 right-0 z-50 flex items-center justify-between px-4 py-3 animate-fade-in"
      // 同 UpdateBanner：--md-sys-color-error-container 映射到 --color-red-bg，是 alpha 8%~15% 的 rgba，
      // 单用它当背景会半透明、让底下的顶栏文字透上来和横幅文字叠在一起；叠一层不透明的页面底色 --bg。
      style={{ background: 'linear-gradient(var(--md-sys-color-error-container), var(--md-sys-color-error-container)), var(--bg)', color: 'var(--md-sys-color-on-error-container)', borderBottom: '1px solid var(--md-sys-color-error)' }} role="alert">
      <span className="m3-body-medium">{t('Z-Library 下载失败：{{reason}}', { reason: t(reason) })}</span>
      <button onClick={onDismiss} className="m3-icon-button" aria-label={t('关闭')} title={t('关闭')}>×</button>
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
  // PB-009-D：分屏 = 两本书并排（primary/secondary bookId）；null 表示单栏
  const [splitBooks, setSplitBooks] = useState<{ primary: number; secondary: number } | null>(null)
  // 正在为哪本书挑第二本（非 null 时显示选书弹层）
  const [splitPickerFor, setSplitPickerFor] = useState<number | null>(null)
  // 分屏当前激活的一栏（TTS 归属）
  const [splitActivePane, setSplitActivePane] = useState<'primary' | 'secondary'>('primary')
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

  // 取消进站：撤遮罩 + 让 C++ 退出 Z-Library 会话（zlib:hide → ReloadPage 回到书架）。
  const cancelZLibrary = useCallback(() => {
    if (zlibTimer.current) { clearTimeout(zlibTimer.current); zlibTimer.current = undefined }
    setZlibLoading(false)
    setZlibAllFailed(false)
    window.electronAPI.zlibHide().catch(() => {})
  }, [])

  const navigateTo = useCallback((p: Page) => {
    // 进站途中切页面 = 放弃这次进站：撤遮罩并让 C++ 退出 Z-Library 会话，
    // 否则遮罩会一直挂着、后端也可能在你已经回到书架后才把页面导航走。
    if (zlibLoading) cancelZLibrary()
    setPage(p)
    setPageKey(k => k + 1)
  }, [zlibLoading, cancelZLibrary])

  const openBook = useCallback((bookId: number) => {
    setCurrentBookId(bookId)
  }, [])

  const closeBook = useCallback(() => {
    setCurrentBookId(null)
    loadBooks()
  }, [loadBooks])

  // ── PB-009-D：分屏 ──────────────────────────────────────────────
  const requestSplit = useCallback((bookId: number) => {
    setSplitPickerFor(bookId)
  }, [])

  const confirmSplit = useCallback((secondaryBookId: number) => {
    const primary = splitPickerFor
    setSplitPickerFor(null)
    if (primary === null || secondaryBookId === primary) return
    // 进入分屏前停掉单栏里的朗读，避免两处声音叠在一起
    void window.electronAPI.ttsStop().catch(() => {})
    setSplitActivePane('primary')
    setSplitBooks({ primary, secondary: secondaryBookId })
    setCurrentBookId(null)
  }, [splitPickerFor])

  // TTS 路由：切换激活栏时停掉旧栏的朗读（避免两段文本串在一起）；
  // 新栏由用户点该栏「朗读」按钮开始（SplitReaderView 内部会 claimTts）。
  const handleSplitActivePaneChange = useCallback((pane: 'primary' | 'secondary') => {
    setSplitActivePane(pane)
    void window.electronAPI.ttsStop().catch(() => {})
  }, [])

  const closeSplit = useCallback(() => {
    const primary = splitBooks ? splitBooks.primary : currentBookId
    void window.electronAPI.ttsStop().catch(() => {})
    setSplitBooks(null)
    setSplitActivePane('primary')
    // 退出分屏回到 primary 的单栏阅读（任务要求「退出返回单栏」）
    setCurrentBookId(primary ?? null)
  }, [splitBooks, currentBookId])

  // 选书弹层：阅读器与书库两处入口共用
  const splitPicker = (
    <SplitBookPickerDialog
      open={splitPickerFor !== null}
      primaryBookId={splitPickerFor}
      onCancel={() => setSplitPickerFor(null)}
      onConfirm={confirmSplit}
    />
  )

  const openZLibrary = useCallback(() => {
    setZlibAllFailed(false)
    setZlibLoading(true)
    // 兜底超时：正常情况下由 mirrorChanged（导航完成时发出）撤掉遮罩；
    // 镜像响应慢时（实测唯一可用线路要 ~13 秒）不要把提示提前收走。
    zlibTimer.current = setTimeout(() => setZlibLoading(false), 25000)
    window.electronAPI.zlibShow()
  }, [])


  // PB-019: import paths forwarded by a second process (or the first-launch
  // command line), then jump into the book when exactly one was opened.
  const handleOpenPaths = useCallback(async (paths: string[]) => {
    if (!paths || paths.length === 0) return
    try {
      const imported = await window.electronAPI.importBooks(paths)
      setPage('library')
      await loadBooks()
      if (imported && imported.length === 1) {
        setCurrentBookId(imported[0].id)
      }
    } catch (e) {
      console.error('Failed to open forwarded files:', e)
    }
  }, [loadBooks])

  useEffect(() => {
    let active = true
    const unsub = window.electronAPI.onOpenFiles((paths) => {
      if (active) void handleOpenPaths(paths)
    })
    // Subscribe first, then drain anything that arrived before React mounted.
    window.electronAPI.getPendingOpenFiles()
      .then((paths) => { if (active && paths && paths.length > 0) void handleOpenPaths(paths) })
      .catch((e) => console.error('Failed to get pending open files:', e))
    return () => { active = false; unsub() }
  }, [handleOpenPaths])

  // PB-009-D：分屏优先于单栏 ReaderView
  if (splitBooks) {
    return (
      <PageShell show key={'split-' + splitBooks.primary + '-' + splitBooks.secondary}>
        <div style={{ height: '100%' }} data-split-active-pane={splitActivePane}>
          <Suspense fallback={<PageLoader />}>
            <SplitReaderView
              primary={{ bookId: splitBooks.primary }}
              secondary={{ bookId: splitBooks.secondary }}
              onClose={closeSplit}
              onActivePaneChange={handleSplitActivePaneChange}
            />
          </Suspense>
        </div>
      </PageShell>
    )
  }

  if (currentBookId !== null) {
    return (
      <PageShell show>
        <ReaderView bookId={currentBookId} onClose={closeBook} onOpenSplit={() => requestSplit(currentBookId)} />
        {splitPicker}
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

  // PB-030：下载历史。阅读器分支排在 page 分支之前，所以这里 onOpenBook 能直接进阅读器，
  // 关闭后 currentBookId 归 null，又回到本页。
  if (page === 'downloads') {
    return (
      <PageShell show key={`downloads-${pageKey}`}>
        <Suspense fallback={<PageLoader />}>
          <DownloadHistoryPage onBack={() => navigateTo('library')} onOpenBook={openBook} />
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
      {zlibLoading && <ZlibLoadingOverlay onCancel={cancelZLibrary} />}
      <Library
        onOpenBook={openBook}
        onOpenSettings={() => navigateTo('settings')}
        onOpenZLibrary={openZLibrary}
        onOpenStatistics={() => navigateTo('statistics')}
        onOpenDownloads={() => navigateTo('downloads')}
        onOpenSplit={requestSplit}
      />
      {splitPicker}
    </PageShell>
  )
}
