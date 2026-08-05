import { useState, useCallback, useEffect, useRef, useMemo, lazy, Suspense } from 'react'
import { useLibraryStore } from '../../stores/libraryStore'
import type { Book } from '../../stores/libraryStore'
import { BookGrid } from './BookGrid'
import { BookList } from './BookList'
import { BookShelfPanel } from './BookShelfPanel'
import { safeText } from '../../utils/safeText'

const ChangelogPanel = lazy(() => import('./ChangelogPanel').then(m => ({ default: m.ChangelogPanel })))
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

interface LibraryProps { onOpenBook: (bookId: number) => void; onOpenSettings: () => void; onOpenZLibrary: () => void; onOpenStatistics: () => void }

function BookPickerDialog({ books, shelfName, shelfBookIds, onAdd, onClose }: { books: Book[]; shelfName: string; shelfBookIds: number[]; onAdd: (ids: number[]) => void; onClose: () => void }) {
  const [selected, setSelected] = useState<Set<number>>(new Set())
  const [search, setSearch] = useState('')

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
      <div className="absolute inset-0" style={{ background: 'rgba(0,0,0,0.45)' }} />
      <div className="relative w-[500px] max-h-[70vh] rounded-xl shadow-win-lg flex flex-col animate-scale-in"
        style={{ background: 'var(--acrylic-bg)', backdropFilter: 'blur(24px)', border: '1px solid var(--acrylic-border)' }}
        onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between px-5 py-3.5" style={{ borderBottom: '1px solid var(--border)' }}>
          <div>
            <h3 className="text-base font-semibold" style={{ color: 'var(--text-primary)' }}>添加到「{shelfName}」</h3>
            <p className="text-xs mt-0.5" style={{ color: 'var(--text-tertiary)' }}>从全部书籍中选择，已选 {selected.size} 本</p>
          </div>
          <button onClick={onClose} className="p-1 rounded-md transition-colors" style={{ color: 'var(--text-tertiary)' }}
            onMouseEnter={(e) => { e.currentTarget.style.color = 'var(--text-primary)'; e.currentTarget.style.background = 'var(--surface-hover)' }}
            onMouseLeave={(e) => { e.currentTarget.style.color = 'var(--text-tertiary)'; e.currentTarget.style.background = 'transparent' }}>
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" /></svg>
          </button>
        </div>
        <div className="px-5 py-3" style={{ borderBottom: '1px solid var(--border)' }}>
          <input type="text" placeholder="搜索书籍..." value={search} onChange={(e) => setSearch(e.target.value)}
            className="w-full input" autoFocus />
        </div>
        <div className="flex-1 overflow-y-auto p-2">
          {filtered.length === 0 ? (
            <p className="text-center py-8 text-sm" style={{ color: 'var(--text-tertiary)' }}>{search ? '无匹配结果' : '没有可添加的书籍'}</p>
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
                <p className="text-xs truncate" style={{ color: 'var(--text-tertiary)' }}>{safeText(book.author) || '未知作者'}{alreadyIn && ' · 已添加'}</p>
              </div>
              <span className="text-[11px] font-semibold text-white px-1.5 py-0.5 rounded-full" style={{ background: alreadyIn ? 'var(--text-tertiary)' : 'var(--text-tertiary)' }}>{book.format.toUpperCase()}</span>
            </div>
          )})}
        </div>
        <div className="flex justify-end gap-2 px-5 py-3.5" style={{ borderTop: '1px solid var(--border)' }}>
          <button onClick={onClose} className="btn-secondary">取消</button>
          <button onClick={() => onAdd(Array.from(selected))} disabled={selected.size === 0} className="btn-primary" style={selected.size === 0 ? { opacity: 0.5, cursor: 'not-allowed' } : undefined}>添加 {selected.size > 0 ? `(${selected.size})` : ''}</button>
        </div>
      </div>
    </div>
  )
}

export function Library({ onOpenBook, onOpenSettings, onOpenZLibrary, onOpenStatistics }: LibraryProps) {
  const books = useLibraryStore((s) => s.books)
  const isLoading = useLibraryStore((s) => s.isLoading)
  const viewMode = useLibraryStore((s) => s.viewMode)
  const searchQuery = useLibraryStore((s) => s.searchQuery)
  const sortBy = useLibraryStore((s) => s.sortBy)
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
  const [isDragOver, setIsDragOver] = useState(false)
  const [showChangelog, setShowChangelog] = useState(false)
  const [showBookSource, setShowBookSource] = useState(false)
  const [showMore, setShowMore] = useState(false)
  const [showBookPicker, setShowBookPicker] = useState(false)
  const dragCounterRef = useRef(0)
  const moreBtnRef = useRef<HTMLButtonElement>(null)

  useEffect(() => { loadBookshelves(); loadReadingTime(); loadReadingProgress() }, [loadBookshelves, loadReadingTime, loadReadingProgress])

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

  const sortedBooks = useMemo(() => {
    const filtered = books.filter((book) => {
      if (!searchQuery) return true
      const q = searchQuery.toLowerCase()
      return safeText(book.title).toLowerCase().includes(q) || safeText(book.author).toLowerCase().includes(q)
    })
    return [...filtered].sort((a, b) => {
      switch (sortBy) { case 'title': return safeText(a.title).localeCompare(safeText(b.title)); case 'author': return safeText(a.author).localeCompare(safeText(b.author)); case 'added_at': return new Date(b.added_at).getTime() - new Date(a.added_at).getTime(); case 'last_opened': return new Date(b.last_opened || 0).getTime() - new Date(a.last_opened || 0).getTime(); default: return 0 }
    })
  }, [books, searchQuery, sortBy])

  const handleImport = useCallback(async () => { const filePath = await window.electronAPI.openFile(); if (filePath) await importBooks([filePath]) }, [importBooks])

  useEffect(() => { return window.electronAPI.onMenuImportBooks((filePaths) => { importBooks(filePaths) }) }, [importBooks])

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
          alert(`「${file.name}」超过 50MB，拖放导入不支持超大文件，请改用「导入」按钮`)
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

  const handleDragEnter = useCallback((e: React.DragEvent) => { e.preventDefault(); dragCounterRef.current++; setIsDragOver(true) }, [])
  const handleDragOver = useCallback((e: React.DragEvent) => { e.preventDefault(); e.dataTransfer.dropEffect = 'copy' }, [])
  const handleDragLeave = useCallback(() => { dragCounterRef.current--; if (dragCounterRef.current <= 0) { dragCounterRef.current = 0; setIsDragOver(false) } }, [])

  const shelfName = activeShelfId !== null ? bookshelves.find((s) => s.id === activeShelfId)?.name : '全部书籍'

  return (
    <div className="h-screen flex flex-col" style={{ background: 'var(--bg)' }}
      onDrop={handleDrop} onDragEnter={handleDragEnter} onDragOver={handleDragOver} onDragLeave={handleDragLeave}>

      {/* Compact command bar */}
      <header className="drag-region flex items-center justify-between px-4 py-2 gap-3" style={{ background: 'var(--mica-bg)', borderBottom: '1px solid var(--border)' }}>
        {/* Left: app identity */}
        <div className="no-drag flex items-center gap-2.5">
          <div className="w-7 h-7 rounded-md flex items-center justify-center" style={{ background: 'var(--accent)' }}>
            <svg className="w-4 h-4 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253" /></svg>
          </div>
          <span className="text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>ParticleBook</span>
        </div>

        {/* Center: search */}
        <div className="no-drag flex-1 max-w-lg mx-auto relative">
          <svg className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 pointer-events-none" fill="none" viewBox="0 0 24 24" stroke="currentColor" style={{ color: 'var(--text-tertiary)' }}><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" /></svg>
          <input type="text" placeholder="搜索书名或作者..." value={searchQuery} onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full pl-9 pr-3 py-1.5 text-sm rounded-lg transition-all duration-150"
            style={{ background: 'var(--bg-tertiary)', color: 'var(--text-primary)', border: '1px solid transparent' }}
            onFocus={(e) => e.currentTarget.style.borderColor = 'var(--border-focus)'}
            onBlur={(e) => e.currentTarget.style.borderColor = 'transparent'}
          />
        </div>

        {/* Right: actions */}
        <div className="no-drag flex items-center gap-1">
          <select value={sortBy} onChange={(e) => setSortBy(e.target.value as any)}
            className="px-2 py-1.5 text-xs rounded-md" style={{ background: 'var(--bg-tertiary)', color: 'var(--text-secondary)', border: '1px solid var(--border)' }}>
            <option value="last_opened">最近</option><option value="added_at">添加</option><option value="title">书名</option><option value="author">作者</option>
          </select>

          <div className="flex rounded-md overflow-hidden ml-1" style={{ border: '1px solid var(--border)' }}>
            <button onClick={() => setViewMode('grid')} aria-label="网格视图" className="p-1.5 transition-colors" style={{ background: viewMode === 'grid' ? 'var(--accent)' : 'transparent', color: viewMode === 'grid' ? '#fff' : 'var(--text-tertiary)' }}>
              <svg className="w-3.5 h-3.5" fill="currentColor" viewBox="0 0 16 16"><path d="M1 2.5A1.5 1.5 0 012.5 1h3A1.5 1.5 0 017 2.5v3A1.5 1.5 0 015.5 7h-3A1.5 1.5 0 011 5.5v-3zm8 0A1.5 1.5 0 0110.5 1h3A1.5 1.5 0 0115 2.5v3A1.5 1.5 0 0113.5 7h-3A1.5 1.5 0 019 5.5v-3zm-8 8A1.5 1.5 0 012.5 9h3A1.5 1.5 0 017 10.5v3A1.5 1.5 0 015.5 15h-3A1.5 1.5 0 011 13.5v-3zm8 0A1.5 1.5 0 0110.5 9h3a1.5 1.5 0 011.5 1.5v3a1.5 1.5 0 01-1.5 1.5h-3A1.5 1.5 0 019 13.5v-3z" /></svg>
            </button>
            <button onClick={() => setViewMode('list')} aria-label="列表视图" className="p-1.5 transition-colors" style={{ background: viewMode === 'list' ? 'var(--accent)' : 'transparent', color: viewMode === 'list' ? '#fff' : 'var(--text-tertiary)' }}>
              <svg className="w-3.5 h-3.5" fill="currentColor" viewBox="0 0 16 16"><path fillRule="evenodd" d="M2.5 12a.5.5 0 01.5-.5h10a.5.5 0 010 1H3a.5.5 0 01-.5-.5zm0-4a.5.5 0 01.5-.5h10a.5.5 0 010 1H3a.5.5 0 01-.5-.5zm0-4a.5.5 0 01.5-.5h10a.5.5 0 010 1H3a.5.5 0 01-.5-.5z" /></svg>
            </button>
          </div>

          <button onClick={handleImport} className="btn-primary flex items-center gap-1.5 ml-1 text-xs" aria-label="导入书籍">
            <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" /></svg>
            导入
          </button>

          {/* More menu */}
          <div className="relative ml-0.5">
            <button ref={moreBtnRef} onClick={() => setShowMore(!showMore)} aria-label="更多操作"
              className="p-1.5 rounded-md transition-colors" style={{ color: 'var(--text-secondary)' }}
              onMouseEnter={(e) => { e.currentTarget.style.color = 'var(--text-primary)'; e.currentTarget.style.background = 'var(--surface-hover)' }}
              onMouseLeave={(e) => { if (!showMore) { e.currentTarget.style.color = 'var(--text-secondary)'; e.currentTarget.style.background = 'transparent' } }}>
              <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 12h.01M12 12h.01M19 12h.01M6 12a1 1 0 11-2 0 1 1 0 012 0zm7 0a1 1 0 11-2 0 1 1 0 012 0zm7 0a1 1 0 11-2 0 1 1 0 012 0z" /></svg>
            </button>
            {showMore && (
              <div className="absolute right-0 top-full mt-1 rounded-lg overflow-hidden shadow-win-lg animate-scale-in z-50 min-w-[180px]"
                style={{ background: 'var(--acrylic-bg)', backdropFilter: 'blur(24px)', border: '1px solid var(--acrylic-border)' }}>
<button onClick={() => { loadBooks(); setShowMore(false) }} className="w-full text-left px-4 py-2 text-sm flex items-center gap-2 whitespace-nowrap transition-colors" style={{ color: 'var(--text-primary)' }} onMouseEnter={(e) => e.currentTarget.style.background = 'var(--surface-hover)'} onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}>
                  <svg className="w-4 h-4 flex-shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" /></svg> 刷新书架
                </button>
                <button onClick={() => { onOpenStatistics(); setShowMore(false) }} className="w-full text-left px-4 py-2 text-sm flex items-center gap-2 whitespace-nowrap transition-colors" style={{ color: 'var(--text-primary)' }} onMouseEnter={(e) => e.currentTarget.style.background = 'var(--surface-hover)'} onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}>
                  <svg className="w-4 h-4 flex-shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" /></svg> 阅读统计
                </button>
                <button onClick={() => { setShowChangelog(true); setShowMore(false) }} className="w-full text-left px-4 py-2 text-sm flex items-center gap-2 whitespace-nowrap transition-colors" style={{ color: 'var(--text-primary)' }} onMouseEnter={(e) => e.currentTarget.style.background = 'var(--surface-hover)'} onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}>
                  <svg className="w-4 h-4 flex-shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" /></svg> 更新日志
                </button>
              </div>
            )}
          </div>

          <button onClick={onOpenSettings} aria-label="全局设置"
            className="p-1.5 rounded-md transition-colors ml-0.5" style={{ color: 'var(--text-secondary)' }}
            onMouseEnter={(e) => { e.currentTarget.style.color = 'var(--text-primary)'; e.currentTarget.style.background = 'var(--surface-hover)' }}
            onMouseLeave={(e) => { e.currentTarget.style.color = 'var(--text-secondary)'; e.currentTarget.style.background = 'transparent' }}>
            <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.066 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.573 1.066c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.066-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" /><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" /></svg>
          </button>
        </div>
      </header>

      {/* Body */}
      <main className="flex-1 flex overflow-hidden">
        {/* Acrylic navigation sidebar */}
        <nav className="w-64 flex-shrink-0 overflow-y-auto flex flex-col" style={{ background: 'var(--acrylic-bg)', backdropFilter: 'blur(24px)', borderRight: '1px solid var(--acrylic-border)' }}>
          {/* Sidebar header */}
          <div className="px-4 pt-4 pb-1.5">
            <p className="text-xs font-semibold uppercase tracking-wider" style={{ color: 'var(--text-tertiary)' }}>{shelfName}</p>
            <p className="text-xs mt-0.5" style={{ color: 'var(--text-tertiary)' }}>{books.length} 本书</p>
          </div>
          <div className="flex-1">
            <BookShelfPanel onOpenBookSource={() => setShowBookSource(true)} onOpenZLibrary={onOpenZLibrary} onAddFromAll={() => setShowBookPicker(true)} />
          </div>
        </nav>

        {/* Content */}
        <div className="flex-1 overflow-y-auto p-8 relative" style={{ background: 'var(--bg)' }}>
          {isDragOver && (
            <div className="absolute inset-4 z-50 flex items-center justify-center rounded-xl pointer-events-none animate-scale-in"
              style={{ background: 'var(--color-indigo-bg)', border: '2px dashed var(--accent)' }}>
              <div className="text-center">
                <svg className="w-20 h-20 mx-auto mb-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" style={{ color: 'var(--accent)' }}>
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" />
                </svg>
                <p className="text-xl font-semibold" style={{ color: 'var(--accent)' }}>拖放电子书文件到此处</p>
                <p className="text-sm mt-1" style={{ color: 'var(--text-secondary)' }}>支持 EPUB、PDF、MOBI、TXT、FB2、CBZ/CBR、HTML、Markdown</p>
              </div>
            </div>
          )}

          {isLoading ? (
            <div className="flex items-center justify-center h-full"><div className="w-10 h-10 rounded-full border-2 border-[var(--border)] border-t-[var(--accent)] animate-spin" /></div>
          ) : sortedBooks.length === 0 ? (
            <div className="flex flex-col items-center justify-center h-full" style={{ color: 'var(--text-tertiary)' }}>
              <div className="w-32 h-32 rounded-full flex items-center justify-center mb-6" style={{ background: 'var(--bg-tertiary)' }}>
                <svg className="w-16 h-16 opacity-30" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1} d="M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253" /></svg>
              </div>
              <p className="text-xl font-semibold mb-1" style={{ color: 'var(--text-secondary)' }}>开始你的阅读之旅</p>
              <p className="text-sm mb-4" style={{ color: 'var(--text-tertiary)' }}>点击「导入」或拖放文件到此处</p>
              <button onClick={handleImport} className="btn-primary">导入第一本书</button>
            </div>
          ) : viewMode === 'grid' ? (
            <BookGrid books={sortedBooks} onOpenBook={onOpenBook} />
          ) : (
            <BookList books={sortedBooks} onOpenBook={onOpenBook} />
          )}
        </div>
      </main>

      {showChangelog && <Suspense fallback={null}><ChangelogPanel onClose={() => setShowChangelog(false)} isClosing={false} /></Suspense>}
      {showBookSource && <Suspense fallback={null}><BookSourcePanel onClose={() => setShowBookSource(false)} isClosing={false} /></Suspense>}

      {/* BookPicker: add existing books to current shelf */}
      {showBookPicker && (
        <BookPickerDialog books={allBooks} shelfName={shelfName} shelfBookIds={shelfBookIds}
          onAdd={async (bookIds) => { for (const id of bookIds) { await addBookToShelf(activeShelfId!, id) }; setShowBookPicker(false) }}
          onClose={() => setShowBookPicker(false)} />
      )}
    </div>
  )
}
