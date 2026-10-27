import { useState, useCallback, useEffect, useRef, useMemo, lazy, Suspense } from 'react'
import { useLibraryStore } from '../../stores/libraryStore'
import { useSettingsStore } from '../../stores/settingsStore'
import type { Book } from '../../stores/libraryStore'
import { BookGrid } from './BookGrid'
import { BookList } from './BookList'
import { BookShelfPanel } from './BookShelfPanel'
import { safeText } from '../../utils/safeText'
import { useTranslation } from 'react-i18next'

const ChangelogPanel = lazy(() => import('./ChangelogPanel').then(m => ({ default: m.ChangelogPanel })))
const LibrarySearch = lazy(() => import('./LibrarySearch').then(m => ({ default: m.LibrarySearch })))
const BookSourcePanel = lazy(() => import('./BookSourcePanel').then(m => ({ default: m.BookSourcePanel })))

// WebView2 File objects have no .path — convert bytes to base64 to hand to C++.
function arrayBufferToBase64(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer)
  let binary = ''
  const chunk = 0x8000
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk) as unknown as number[])
  }
  return btoa(binary)
}

interface LibraryProps { onOpenBook: (bookId: number) => void; onOpenSettings: () => void; onOpenZLibrary: () => void; onOpenStatistics: () => void; onOpenSplit?: (bookId: number) => void }

function BookPickerDialog({ books, shelfName, shelfBookIds, onAdd, onClose }: { books: Book[]; shelfName: string; shelfBookIds: number[]; onAdd: (ids: number[]) => void; onClose: () => void }) {
  const [selected, setSelected] = useState<Set<number>>(new Set())
  const [search, setSearch] = useState('')
  const { t } = useTranslation()

  const filtered = books.filter((b) => {
    if (!search) return true
    const q = search.toLowerCase()
    return safeText(b.title).toLowerCase().includes(q) || safeText(b.author).toLowerCase().includes(q)
  })

  const toggle = (id: number) => {
    const next = new Set(selected)
    if (next.has(id)) next.delete(id); else next.add(id)
    setSelected(next)
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center animate-fade-in" onClick={onClose}>
      <div className="m3-dialog-backdrop absolute inset-0" />
      <div className="m3-dialog relative w-[500px] max-h-[70vh] flex flex-col animate-scale-in"
        onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between px-5 py-3.5" style={{ borderBottom: '1px solid var(--border)' }}>
          <div>
            <h3 className="m3-title-large">{t('添加到「{{shelfName}}」', { shelfName })}</h3>
            <p className="m3-body-small mt-1">{t('从全部书籍中选择，已选 {{count}} 本', { count: selected.size })}</p>
          </div>
          <button onClick={onClose} className="m3-icon-button" aria-label={t('关闭')}>
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" /></svg>
          </button>
        </div>
        <div className="px-5 py-3" style={{ borderBottom: '1px solid var(--border)' }}>
          <input type="text" placeholder={t('搜索书籍...')} value={search} onChange={(e) => setSearch(e.target.value)}
            className="w-full input" autoFocus />
        </div>
        <div className="flex-1 overflow-y-auto p-2">
          {filtered.length === 0 ? (
            <p className="text-center py-8 text-sm" style={{ color: 'var(--text-tertiary)' }}>{search ? t('无匹配结果') : t('没有可添加的书籍')}</p>
          ) : filtered.map((book) => {
            const alreadyIn = shelfBookIds.includes(book.id)
            return (
            <div key={book.id} onClick={alreadyIn ? undefined : () => toggle(book.id)}
              className="flex items-center gap-3 px-3 py-2 rounded-md transition-colors"
              style={{ background: selected.has(book.id) ? 'var(--color-indigo-bg)' : 'transparent', cursor: alreadyIn ? 'default' : 'pointer' }}
              onMouseEnter={(e) => { if (!selected.has(book.id) && !alreadyIn) e.currentTarget.style.background = 'var(--surface-hover)' }}
              onMouseLeave={(e) => { if (!selected.has(book.id) && !alreadyIn) e.currentTarget.style.background = 'transparent' }}>
              {alreadyIn ? (
                <div className="w-5 h-5 rounded flex items-center justify-center flex-shrink-0" style={{ background: 'var(--accent)' }}>
                  <svg className="w-3.5 h-3.5 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M5 13l4 4L19 7" /></svg>
                </div>
              ) : (
                <div className="w-5 h-5 rounded flex items-center justify-center flex-shrink-0"
                  style={{ background: selected.has(book.id) ? 'var(--accent)' : 'transparent', border: selected.has(book.id) ? 'none' : '1px solid var(--border)' }}>
                  {selected.has(book.id) && <svg className="w-3.5 h-3.5 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M5 13l4 4L19 7" /></svg>}
                </div>
              )}
              <div className="flex-1 min-w-0">
                <p className="text-sm truncate" style={{ color: alreadyIn ? 'var(--text-tertiary)' : 'var(--text-primary)' }}>{safeText(book.title)}</p>
                <p className="text-xs truncate" style={{ color: 'var(--text-tertiary)' }}>{safeText(book.author) || t('未知作者')}{alreadyIn && t(' · 已添加')}</p>
              </div>
              <span className="text-[11px] font-semibold text-white px-1.5 py-0.5 rounded-full" style={{ background: alreadyIn ? 'var(--text-tertiary)' : 'var(--text-tertiary)' }}>{book.format.toUpperCase()}</span>
            </div>
          )})}
        </div>
        <div className="flex justify-end gap-2 px-5 py-3.5" style={{ borderTop: '1px solid var(--md-sys-color-outline-variant)' }}>
          <button onClick={onClose} className="m3-text-button">{t('取消')}</button>
          <button onClick={() => onAdd(Array.from(selected))} disabled={selected.size === 0} className="m3-filled-button">{t('添加')} {selected.size > 0 ? `(${selected.size})` : ''}</button>
        </div>
      </div>
    </div>
  )
}

export function Library({ onOpenBook, onOpenSettings, onOpenZLibrary, onOpenStatistics, onOpenSplit }: LibraryProps) {
  const books = useLibraryStore((s) => s.books)
  const isLoading = useLibraryStore((s) => s.isLoading)
  const viewMode = useLibraryStore((s) => s.viewMode)
  const searchQuery = useLibraryStore((s) => s.searchQuery)
  const sortBy = useLibraryStore((s) => s.sortBy)
  // PB-005：标签 / 系列 / 评分筛选
  const filterTag = useLibraryStore((s) => s.filterTag)
  const filterSeries = useLibraryStore((s) => s.filterSeries)
  const minRating = useLibraryStore((s) => s.minRating)
  const allTags = useLibraryStore((s) => s.allTags)
  const allSeries = useLibraryStore((s) => s.allSeries)
  const setFilterTag = useLibraryStore((s) => s.setFilterTag)
  const setFilterSeries = useLibraryStore((s) => s.setFilterSeries)
  const setMinRating = useLibraryStore((s) => s.setMinRating)
  const loadMetadataFacets = useLibraryStore((s) => s.loadMetadataFacets)
  const applyDisplayDefaults = useLibraryStore((s) => s.applyDisplayDefaults)
  // 设置页的「默认视图 / 默认排序」此前只写不读（Library 一直用 store 里硬编码的
  // grid/last_opened）。这里跟随设置值变化应用一次：启动加载设置完成后生效，
  // 用户在设置页改动也立即生效；工具栏上的切换仍是会话内的。
  const defaultViewMode = useSettingsStore((s) => s.defaultViewMode)
  const defaultSortBy = useSettingsStore((s) => s.defaultSortBy)
  const activeShelfId = useLibraryStore((s) => s.activeShelfId)
  const bookshelves = useLibraryStore((s) => s.bookshelves)
  const shelfBookIds = useLibraryStore((s) => s.shelfBookIds)
  const allBooks = useLibraryStore((s) => s.allBooks)
  const setViewMode = useLibraryStore((s) => s.setViewMode)
  const setSearchQuery = useLibraryStore((s) => s.setSearchQuery)
  const setSortBy = useLibraryStore((s) => s.setSortBy)
  const importBooks = useLibraryStore((s) => s.importBooks)
  const addBookToShelf = useLibraryStore((s) => s.addBookToShelf)
  const loadBooks = useLibraryStore((s) => s.loadBooks)
  const loadBookshelves = useLibraryStore((s) => s.loadBookshelves)
  const loadReadingTime = useLibraryStore((s) => s.loadReadingTime)
  const loadReadingProgress = useLibraryStore((s) => s.loadReadingProgress)
  const loadCoverCacheConfig = useLibraryStore((s) => s.loadCoverCacheConfig)
  // PB-017-B：批量导入进度 / 逐条错误（C++ book:importProgress）
  const importProgress = useLibraryStore((s) => s.importProgress)
  const importErrors = useLibraryStore((s) => s.importErrors)
  const clearImportErrors = useLibraryStore((s) => s.clearImportErrors)
  const [isDragOver, setIsDragOver] = useState(false)
  const [showChangelog, setShowChangelog] = useState(false)
  const [showBookSource, setShowBookSource] = useState(false)
  const [showMore, setShowMore] = useState(false)
  const [showBookPicker, setShowBookPicker] = useState(false)
  // PB-006-B：全局全文搜索面板（书库顶部按钮 / Ctrl+Shift+F）
  const [showGlobalSearch, setShowGlobalSearch] = useState(false)
  const dragCounterRef = useRef(0)
  const moreBtnRef = useRef<HTMLButtonElement>(null)
  const { t } = useTranslation()

  useEffect(() => {
    loadBookshelves(); loadReadingTime(); loadReadingProgress(); loadMetadataFacets()
    // PB-017-B：进入书库就应用 settings 里的封面缓存上限（设置页改完返回即生效，
    // 不必等下一次 loadBooks）。
    void loadCoverCacheConfig()
  }, [loadBookshelves, loadReadingTime, loadReadingProgress, loadMetadataFacets, loadCoverCacheConfig])

  useEffect(() => { applyDisplayDefaults() }, [applyDisplayDefaults, defaultViewMode, defaultSortBy])

  // Register global refresh function for bridge script to call after import/delete.
  // Goes through the zustand store so the active-bookshelf filter is respected
  // (the old code wrote *all* books into a localBooks state, which permanently
  // broke bookshelf filtering after the first import/delete).
  useEffect(() => {
    window.__refreshLibrary = () => {
      useLibraryStore.getState().loadBooks()
    }
    return () => { delete window.__refreshLibrary }
  }, [])

  // PB-017-B：C++ 在 book:import 里逐文件发 book:importProgress；这里写入 store，
  // 进度条 + 错误列表由下面的 UI 渲染（事件里 done=true 是收尾汇总）。
  useEffect(() => {
    const unsubscribe = window.electronAPI.onImportProgress((progress) => {
      useLibraryStore.getState().setImportProgress(progress)
    })
    return unsubscribe
  }, [])

  const sortedBooks = useMemo(() => {
    const query = searchQuery.trim().toLowerCase()
    const filtered = books.filter((book) => {
      // PB-005：标签 / 系列 / 评分筛选（空串 / 0 = 不筛；-1 = 只看未评分）
      if (filterTag && !(Array.isArray(book.tags) && book.tags.includes(filterTag))) return false
      if (filterSeries && (book.series_name ?? '') !== filterSeries) return false
      if (minRating === -1) {
        if ((book.rating ?? 0) !== 0) return false
      } else if (minRating > 0 && (book.rating ?? 0) < minRating) {
        return false
      }
      if (!query) return true
      // 搜索覆盖书名 / 作者 / 标签 / 系列（PB-005 验收）
      const haystack = [
        safeText(book.title),
        safeText(book.author),
        book.series_name ?? '',
        ...(Array.isArray(book.tags) ? book.tags : []),
      ].join('\n').toLowerCase()
      return haystack.includes(query)
    })
    return [...filtered].sort((a, b) => {
      switch (sortBy) {
        case 'title': return safeText(a.title).localeCompare(safeText(b.title))
        case 'author': return safeText(a.author).localeCompare(safeText(b.author))
        case 'added_at': return new Date(b.added_at).getTime() - new Date(a.added_at).getTime()
        case 'last_opened': return new Date(b.last_opened || 0).getTime() - new Date(a.last_opened || 0).getTime()
        case 'rating': {
          const byRating = (b.rating ?? 0) - (a.rating ?? 0)
          return byRating !== 0 ? byRating : safeText(a.title).localeCompare(safeText(b.title))
        }
        case 'series': {
          const sa = a.series_name ?? ''
          const sb = b.series_name ?? ''
          if (sa !== sb) return sa.localeCompare(sb)
          return (a.series_index ?? 0) - (b.series_index ?? 0)
        }
        default: return 0
      }
    })
  }, [books, searchQuery, sortBy, filterTag, filterSeries, minRating])

  const handleImport = useCallback(async () => { const filePath = await window.electronAPI.openFile(); if (filePath) await importBooks([filePath]) }, [importBooks])

  // C++ 侧【内部】发起的导入（书源下载、Z-Library 下载后自动入库）不会走
  // BridgeServer 里那个只对 JS 发起的 book:import 生效的刷新钩子，所以由它们发
  // library:changed，渲染层收到后刷新书架 —— 否则"下载完成"后书要等手动刷新
  // 或重启才出现。此前用的是 menu:importBooks（本应用没有原生菜单，且它的语义
  // 是"导入这些路径"，C++ 传空对象过来只会触发一次无效导入）。
  useEffect(() => {
    return window.electronAPI.onLibraryChanged(() => {
      useLibraryStore.getState().loadBooks()
    })
  }, [])

  const handleDrop = useCallback(async (e: React.DragEvent) => {
    e.preventDefault(); dragCounterRef.current = 0; setIsDragOver(false)
    const files = Array.from(e.dataTransfer.files)
    if (files.length === 0) return
    const filePaths: string[] = []
    for (const file of files) {
      try {
        // WebView2 File objects carry no filesystem path. If getFilePath can't
        // resolve one (the normal case), read the content here and hand the
        // bytes to C++ via base64.
        const p = window.electronAPI.getFilePath(file)
        if (p) { filePaths.push(p); continue }
        if (file.size > 50 * 1024 * 1024) {
          alert(t('「{{name}}」超过 50MB，拖放导入不支持超大文件，请改用「导入」按钮', { name: file.name }))
          continue
        }
        const buf = await file.arrayBuffer()
        const b64 = arrayBufferToBase64(buf)
        const res = await window.electronAPI.writeDroppedFile(file.name, b64)
        if (res?.path) filePaths.push(res.path)
      } catch (err) {
        console.warn('Failed to import dropped file:', err)
      }
    }
    if (filePaths.length > 0) await importBooks(filePaths)
  }, [importBooks])

  useEffect(() => {
    const handleClick = (e: MouseEvent) => { if (moreBtnRef.current && !moreBtnRef.current.contains(e.target as Node)) setShowMore(false) }
    if (showMore) document.addEventListener('click', handleClick)
    return () => document.removeEventListener('click', handleClick)
  }, [showMore])

  // PB-006-B：Ctrl+Shift+F 打开全局全文搜索（书库页才挂载，不会和阅读器快捷键冲突）
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.shiftKey && (e.key === 'F' || e.key === 'f')) {
        e.preventDefault()
        setShowGlobalSearch(true)
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [])

  const handleDragEnter = useCallback((e: React.DragEvent) => { e.preventDefault(); dragCounterRef.current++; setIsDragOver(true) }, [])
  const handleDragOver = useCallback((e: React.DragEvent) => { e.preventDefault(); e.dataTransfer.dropEffect = 'copy' }, [])
  const handleDragLeave = useCallback(() => { dragCounterRef.current--; if (dragCounterRef.current <= 0) { dragCounterRef.current = 0; setIsDragOver(false) } }, [])

  // Fall back to "" when the active shelf id no longer resolves (e.g. the
  // shelf was deleted) so this stays a string; an empty title is inert.
  const shelfName = activeShelfId !== null
    ? (bookshelves.find((s) => s.id === activeShelfId)?.name ?? '')
    : t('全部书籍')
  const importPercent = importProgress && importProgress.total > 0
    ? Math.min(100, Math.round((importProgress.current / importProgress.total) * 100))
    : 0

  return (
    <div className="h-screen flex flex-col" style={{ background: 'var(--bg)' }}
      onDrop={handleDrop} onDragEnter={handleDragEnter} onDragOver={handleDragOver} onDragLeave={handleDragLeave}>

      {/* M3 top app bar: destination + search + primary action */}
      <header className="m3-top-app-bar library-top-bar drag-region" data-testid="library-app-bar">
        <div className="no-drag flex items-center gap-3 min-w-0">
          <span className="m3-title-large truncate">ParticleBook</span>

        </div>

        <div className="no-drag flex-1 max-w-[560px] mx-auto">
          <div className="m3-search" role="search">
            <svg className="w-5 h-5 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.8} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
            </svg>
            <input
              type="text"
              placeholder={t('搜索书名 / 作者 / 标签 / 系列...')}
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              data-testid="library-search-input"
              aria-label={t('搜索书库')}
            />
            <button
              onClick={() => setShowGlobalSearch(true)}
              className="m3-icon-button"
              title={t('全文搜索 (Ctrl+Shift+F)')}
              aria-label={t('全文搜索')}
              data-global-search-open="1"
            >
              <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.8} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
              </svg>
            </button>
          </div>
        </div>

        <div className="no-drag flex items-center gap-1.5">
          <select
            value={sortBy}
            onChange={(e) => setSortBy(e.target.value as any)}
            data-testid="library-sort"
            aria-label={t('排序')}
            className="m3-select hidden md:block"
            title={t('排序')}
          >
            <option value="last_opened">{t('最近')}</option>
            <option value="added_at">{t('添加', { context: 'sort' })}</option>
            <option value="title">{t('书名')}</option>
            <option value="author">{t('作者')}</option>
            <option value="rating">{t('按评分')}</option>
            <option value="series">{t('按系列')}</option>
          </select>

          <div className="m3-segmented hidden sm:inline-flex" role="group" aria-label={t('视图')}>
            <button onClick={() => setViewMode('grid')} aria-label={t('网格视图')} aria-pressed={viewMode === 'grid'} data-testid="view-grid" className={'m3-segmented-btn ' + (viewMode === 'grid' ? 'is-active' : '')}>
              <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 16 16"><path d="M1 2.5A1.5 1.5 0 012.5 1h3A1.5 1.5 0 017 2.5v3A1.5 1.5 0 015.5 7h-3A1.5 1.5 0 011 5.5v-3zm8 0A1.5 1.5 0 0110.5 1h3A1.5 1.5 0 0115 2.5v3A1.5 1.5 0 0113.5 7h-3A1.5 1.5 0 019 5.5v-3zm-8 8A1.5 1.5 0 012.5 9h3A1.5 1.5 0 017 10.5v3A1.5 1.5 0 015.5 15h-3A1.5 1.5 0 011 13.5v-3zm8 0A1.5 1.5 0 0110.5 9h3a1.5 1.5 0 011.5 1.5v3a1.5 1.5 0 01-1.5 1.5h-3A1.5 1.5 0 019 13.5v-3z" /></svg>
            </button>
            <button onClick={() => setViewMode('list')} aria-label={t('列表视图')} aria-pressed={viewMode === 'list'} data-testid="view-list" className={'m3-segmented-btn ' + (viewMode === 'list' ? 'is-active' : '')}>
              <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 16 16"><path fillRule="evenodd" d="M2.5 12a.5.5 0 01.5-.5h10a.5.5 0 010 1H3a.5.5 0 01-.5-.5zm0-4a.5.5 0 01.5-.5h10a.5.5 0 010 1H3a.5.5 0 01-.5-.5zm0-4a.5.5 0 01.5-.5h10a.5.5 0 010 1H3a.5.5 0 01-.5-.5z" /></svg>
            </button>
          </div>

          <button onClick={handleImport} className="m3-filled-button" aria-label={t('导入书籍')} title={t('导入书籍')}>
            <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" /></svg>
            <span className="hidden sm:inline">{t('导入')}</span>
          </button>

          <div className="relative">
            <button ref={moreBtnRef} onClick={() => setShowMore(!showMore)} aria-label={t('更多操作')} aria-expanded={showMore} className="m3-icon-button">
              <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 12h.01M12 12h.01M19 12h.01M6 12a1 1 0 11-2 0 1 1 0 012 0zm7 0a1 1 0 11-2 0 1 1 0 012 0zm7 0a1 1 0 11-2 0 1 1 0 012 0z" /></svg>
            </button>
            {showMore && (
              <div className="m3-menu absolute right-0 top-full mt-2 z-50 animate-scale-in">
                <button onClick={() => { loadBooks(); setShowMore(false) }} className="m3-menu-item">
                  <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" /></svg> {t('刷新书架')}
                </button>
                <button onClick={() => { onOpenStatistics(); setShowMore(false) }} className="m3-menu-item">
                  <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" /></svg> {t('阅读统计')}
                </button>
                <button onClick={() => { setShowChangelog(true); setShowMore(false) }} className="m3-menu-item">
                  <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" /></svg> {t('更新日志')}
                </button>
              </div>
            )}
          </div>

          <button onClick={onOpenSettings} aria-label={t('全局设置')} title={t('全局设置')} className="m3-icon-button">
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.066 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.573 1.066c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.066-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" /><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" /></svg>
          </button>
        </div>
      </header>

      {/* Body */}
      <main className="flex-1 flex overflow-hidden">
        {/* M3 navigation drawer */}
        <nav className="m3-nav-drawer w-[248px] flex-shrink-0 overflow-y-auto flex flex-col" aria-label={t('书库导航')}>
          <div className="px-5 pt-5 pb-2">
            <p className="m3-section-title">{t('书库')}</p>
            <p className="m3-body-small mt-1">{t('{{count}} 本书', { count: books.length })}</p>
          </div>
          <div className="flex-1">
            <BookShelfPanel onOpenBookSource={() => setShowBookSource(true)} onOpenZLibrary={onOpenZLibrary} onAddFromAll={() => setShowBookPicker(true)} />
          </div>
        </nav>

        {/* Content */}
        <div className="flex-1 overflow-y-auto relative" data-library-scroll="1"
          style={{ background: 'var(--bg)', overflowAnchor: 'none' }}>
          <div className="mx-auto w-full max-w-[1600px] px-5 py-6 lg:px-8 lg:py-8">
            <div className="flex items-end justify-between gap-4 mb-5">
              <div className="min-w-0">
                <h1 className="m3-headline-small truncate">{shelfName}</h1>
                <p className="m3-supporting-text mt-1">{t('{{count}} 本书', { count: sortedBooks.length })}</p>
              </div>
            </div>
          {/* PB-017-B：批量导入进度 / 逐条错误汇总（C++ book:importProgress 事件） */}
          {importProgress && importProgress.total > 0 && (
            <div data-testid="import-progress" className="m3-card p-3 mb-4">
              <div className="flex items-center justify-between gap-3 m3-body-small">
                <span className="truncate">
                  {importProgress.done
                    ? t('导入完成')
                    : t('导入中 {{current}}/{{total}}：{{message}}', {
                        current: importProgress.current,
                        total: importProgress.total,
                        message: importProgress.message || '…',
                      })}
                </span>
                <span className="font-mono flex-shrink-0">{importPercent}%</span>
              </div>
              <div className="m3-linear-progress mt-2" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={importPercent}>
                <span style={{ width: `${importPercent}%` }} />
              </div>
            </div>
          )}
          {importErrors.length > 0 && (
            <div data-testid="import-errors" className="m3-card p-3 mb-4" style={{ background: 'var(--md-sys-color-error-container)', borderColor: 'transparent' }}>
              <div className="flex items-center justify-between gap-2">
                <span className="m3-title-small" style={{ color: 'var(--md-sys-color-on-error-container)' }}>
                  {t('部分文件导入失败（{{count}}）', { count: importErrors.length })}
                </span>
                <button onClick={clearImportErrors} className="m3-text-button" aria-label={t('关闭')}>{t('关闭')}</button>
              </div>
              <ul className="mt-1 max-h-24 overflow-y-auto text-xs" style={{ color: 'var(--text-secondary)' }}>
                {importErrors.slice(0, 20).map((error, index) => (
                  <li key={`${index}-${error}`} className="truncate" title={error}>{error}</li>
                ))}
                {importErrors.length > 20 && (
                  <li>{t('还有 {{count}} 条…', { count: importErrors.length - 20 })}</li>
                )}
              </ul>
            </div>
          )}

          {/* PB-005：标签 / 系列 / 评分筛选（与搜索、排序叠加） */}
          {books.length > 0 && (
            <div className="flex flex-wrap items-center gap-2 mb-4" data-testid="library-filters">
              <select
                value={filterTag}
                onChange={(e) => setFilterTag(e.target.value)}
                className={'m3-select ' + (filterTag ? 'is-selected' : '')}
                aria-label={t('标签')}
                title={t('标签')}
                data-testid="filter-tag"
              >
                <option value="">{t('全部标签')}</option>
                {allTags.map((tag) => <option key={tag} value={tag}>{tag}</option>)}
              </select>
              <select
                value={filterSeries}
                onChange={(e) => setFilterSeries(e.target.value)}
                className={'m3-select ' + (filterSeries ? 'is-selected' : '')}
                aria-label={t('系列')}
                title={t('系列')}
                data-testid="filter-series"
              >
                <option value="">{t('全部系列')}</option>
                {allSeries.map((series) => <option key={series} value={series}>{series}</option>)}
              </select>
              <select
                value={String(minRating)}
                onChange={(e) => setMinRating(Number(e.target.value))}
                className={'m3-select ' + (minRating !== 0 ? 'is-selected' : '')}
                aria-label={t('评分')}
                title={t('评分')}
                data-testid="filter-rating"
              >
                <option value="0">{t('全部评分')}</option>
                <option value="-1">{t('仅未评分')}</option>
                {[5, 4, 3, 2, 1].map((n) => (
                  <option key={n} value={n}>{t('评分 ≥ {{n}} 星', { n })}</option>
                ))}
              </select>
              {(filterTag || filterSeries || minRating !== 0) && (
                <button
                  onClick={() => { setFilterTag(''); setFilterSeries(''); setMinRating(0) }}
                  className="m3-text-button"
                  data-testid="filter-clear"
                >
                  {t('清除筛选')}
                </button>
              )}
              <span className="m3-label-medium ml-auto" aria-live="polite">
                {t('{{count}} 本书', { count: sortedBooks.length })}
              </span>
            </div>
          )}

          {isDragOver && (
            <div className="absolute inset-4 z-50 flex items-center justify-center rounded-xl pointer-events-none animate-scale-in"
              style={{ background: 'var(--color-indigo-bg)', border: '2px dashed var(--accent)' }}>
              <div className="text-center">
                <svg className="w-20 h-20 mx-auto mb-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" style={{ color: 'var(--accent)' }}>
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" />
                </svg>
                <p className="text-xl font-semibold" style={{ color: 'var(--accent)' }}>{t('拖放电子书文件到此处')}</p>
                <p className="text-sm mt-1" style={{ color: 'var(--text-secondary)' }}>{t('支持 EPUB、PDF、MOBI、TXT、FB2、CBZ/CBR、HTML、Markdown')}</p>
              </div>
            </div>
          )}

          {isLoading ? (
            <div className="flex items-center justify-center h-full"><div className="w-10 h-10 rounded-full border-2 border-[var(--border)] border-t-[var(--accent)] animate-spin" role="status" aria-label={t('加载中...')} /></div>
          ) : sortedBooks.length === 0 ? (
            <div className="m3-empty-state">
              <div className="m3-empty-state-icon">
                <svg className="w-10 h-10" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}><path strokeLinecap="round" strokeLinejoin="round" d="M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253" /></svg>
              </div>
              <p className="m3-title-large mb-1">{t('开始你的阅读之旅')}</p>
              <p className="m3-body-medium mb-5" style={{ color: 'var(--md-sys-color-on-surface-variant)' }}>{t('点击「导入」或拖放文件到此处')}</p>
              <button onClick={handleImport} className="m3-filled-button">{t('导入第一本书')}</button>
            </div>
          ) : viewMode === 'grid' ? (
            <BookGrid books={sortedBooks} onOpenBook={onOpenBook} onOpenSplit={onOpenSplit} />
          ) : (
            <BookList books={sortedBooks} onOpenBook={onOpenBook} />
          )}
          </div>
        </div>
      </main>

      {showChangelog && <Suspense fallback={null}><ChangelogPanel onClose={() => setShowChangelog(false)} isClosing={false} /></Suspense>}
      {showBookSource && <Suspense fallback={null}><BookSourcePanel onClose={() => setShowBookSource(false)} isClosing={false} /></Suspense>}

      {/* PB-006-B：全局全文搜索 */}
      {showGlobalSearch && (
        <Suspense fallback={null}>
          <LibrarySearch onClose={() => setShowGlobalSearch(false)} onOpenBook={onOpenBook} />
        </Suspense>
      )}

      {/* BookPicker: add existing books to current shelf */}
      {showBookPicker && (
        <BookPickerDialog books={allBooks} shelfName={shelfName} shelfBookIds={shelfBookIds}
          onAdd={async (bookIds) => { for (const id of bookIds) { await addBookToShelf(activeShelfId!, id) }; setShowBookPicker(false) }}
          onClose={() => setShowBookPicker(false)} />
      )}
    </div>
  )
}
