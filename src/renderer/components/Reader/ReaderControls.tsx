import { useState, useEffect, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { useReaderStore } from '../../stores/readerStore'
import { useSettingsStore } from '../../stores/settingsStore'

interface ReaderControlsProps {
  bookId: number
  format: string
  onOpenSettings: () => void
}

export function ReaderControls({ bookId, format, onOpenSettings }: ReaderControlsProps) {
  const { t } = useTranslation()
  const progress = useReaderStore((s) => s.progress)
  const bookmarks = useReaderStore((s) => s.bookmarks)
  const addBookmark = useReaderStore((s) => s.addBookmark)
  const removeBookmark = useReaderStore((s) => s.removeBookmark)
  const addNote = useReaderStore((s) => s.addNote)
  const setSidebarTab = useReaderStore((s) => s.setSidebarTab)
  const setControlsLocked = useReaderStore((s) => s.setControlsLocked)
  const seekTo = useReaderStore((s) => s.seekTo)

  const showSearch = useReaderStore((s) => s.showSearch)
  const setShowSearch = useReaderStore((s) => s.setShowSearch)
  const searchQuery = useReaderStore((s) => s.searchQuery)
  const searchMatches = useReaderStore((s) => s.searchMatches)
  const currentSearchIndex = useReaderStore((s) => s.currentSearchIndex)
  const setSearchQuery = useReaderStore((s) => s.setSearchQuery)
  const nextSearchMatch = useReaderStore((s) => s.nextSearchMatch)
  const prevSearchMatch = useReaderStore((s) => s.prevSearchMatch)
  const clearSearch = useReaderStore((s) => s.clearSearch)
  const searchInputRef = useRef<HTMLInputElement>(null)

  const theme = useSettingsStore((s) => s.theme)
  const setTheme = useSettingsStore((s) => s.setTheme)

  const [showNoteInput, setShowNoteInput] = useState(false)
  const [noteText, setNoteText] = useState('')

  const toggleNoteInput = () => {
    const next = !showNoteInput
    setShowNoteInput(next)
    setControlsLocked(next)
    if (!next) setNoteText('')
  }

  const themes = ['light', 'dark', 'sepia'] as const

  useEffect(() => {
    if (showSearch) {
      setTimeout(() => searchInputRef.current?.focus(), 100)
    }
  }, [showSearch])

  const handleSearchKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      e.preventDefault()
      if (e.shiftKey) prevSearchMatch()
      else nextSearchMatch()
    } else if (e.key === 'Escape') {
      e.preventDefault()
      clearSearch()
      // 必须阻止冒泡：clearSearch() 会同步把 showSearch 置 false，等事件冒到
      // window 上的 ReaderView 处理器时，它看到的已经是 false，于是走"关闭阅读器"
      // 分支 —— 表现为在搜索框按一次 Esc 把整本书关掉。
      e.stopPropagation()
    }
  }

  const currentPage = progress.page || 0
  const isBookmarked = bookmarks.some((bm) => {
    if (progress.cfi && bm.cfi) return bm.cfi === progress.cfi
    return bm.page === currentPage && currentPage !== 0
  })

  const handleToggleBookmark = () => {
    if (isBookmarked) {
      const bm = bookmarks.find((b) => {
        if (progress.cfi && b.cfi) return b.cfi === progress.cfi
        return b.page === currentPage && currentPage !== 0
      })
      if (bm) removeBookmark(bm.id)
    } else {
      const nextNum = bookmarks.length + 1
      addBookmark({
        book_id: bookId,
        page: currentPage,
        cfi: progress.cfi,
        progress: progress.progress,
        title: t('书签{{n}}', { n: nextNum }),
      })
    }
  }

  const handleAddNote = () => {
    if (!noteText.trim()) return
    addNote({
      book_id: bookId,
      page: currentPage,
      cfi: progress.cfi,
      content: noteText.trim(),
    })
    setNoteText('')
    setShowNoteInput(false)
    setControlsLocked(false)
  }

  const ctrlClasses = {
    input: 'px-3 py-2 rounded-lg text-sm border focus:outline-none transition-colors',
    inputBg: 'bg-[var(--reader-sidebar)] border-[var(--reader-border)] text-[var(--reader-text)]',
    btn: 'p-2 rounded-lg transition-colors',
    btnDefault: 'text-[var(--reader-text)] opacity-70 hover:opacity-100 hover:bg-[var(--reader-bg)]',
    btnPrimary: 'px-4 py-2 text-white text-sm rounded-lg transition-colors',
    dialogBtn: 'px-3 py-2 text-sm rounded-lg transition-colors',
  }

  return (
    <div className="bg-gradient-to-t from-black/60 via-black/30 to-transparent px-6 py-4">
      {showNoteInput && (
        <div className="max-w-2xl mx-auto mb-3">
          <div className="flex gap-2">
            <input
              type="text"
              value={noteText}
              onChange={(e) => setNoteText(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleAddNote()}
              placeholder={t('输入笔记内容...')}
              className={`flex-1 ${ctrlClasses.input} ${ctrlClasses.inputBg} placeholder:opacity-40`}
              autoFocus
            />
            <button onClick={handleAddNote} className={`${ctrlClasses.btnPrimary} bg-[var(--reader-accent)] hover:brightness-110`}>{t('保存')}</button>
            <button onClick={toggleNoteInput} className={`${ctrlClasses.dialogBtn} bg-[var(--reader-sidebar)] text-[var(--reader-text)] border border-[var(--reader-border)]`}>{t('取消')}</button>
          </div>
        </div>
      )}

      {showSearch && (
        <div className="max-w-2xl mx-auto mb-3">
          <div className="flex items-center gap-2">
            <svg className="w-4 h-4 shrink-0" style={{ color: 'var(--reader-text)', opacity: 0.5 }} fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
            </svg>
            <input
              ref={searchInputRef}
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              onKeyDown={handleSearchKeyDown}
              placeholder={t('搜索全书... (Enter/Shift+Enter)')}
              className={`flex-1 ${ctrlClasses.input} ${ctrlClasses.inputBg} placeholder:opacity-40`}
            />
            <span className={`text-xs shrink-0 min-w-[2.5rem] text-center ${searchMatches.length === 0 && searchQuery ? 'text-red-400' : ''}`}
              style={{ color: searchMatches.length === 0 && searchQuery ? undefined : 'var(--reader-text)', opacity: searchMatches.length === 0 && searchQuery ? 1 : 0.5 }}>
              {searchMatches.length > 0 ? `${currentSearchIndex + 1}/${searchMatches.length}` : searchQuery ? '0/0' : ''}
            </span>
            <button onClick={prevSearchMatch} className={`${ctrlClasses.btn} ${ctrlClasses.btnDefault}`} title={t('上一个 (Shift+Enter)')}>
              <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 15l7-7 7 7" /></svg>
            </button>
            <button onClick={nextSearchMatch} className={`${ctrlClasses.btn} ${ctrlClasses.btnDefault}`} title={t('下一个 (Enter)')}>
              <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" /></svg>
            </button>
            <button onClick={clearSearch} className={`${ctrlClasses.btn} ${ctrlClasses.btnDefault}`} title={t('关闭搜索 (Esc)')}>
              <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" /></svg>
            </button>
          </div>
        </div>
      )}

      <div className="max-w-2xl mx-auto mb-3">
        <input
          type="range"
          min="0"
          max="100"
          value={progress.progress || 0}
          onChange={(e) => seekTo(Number(e.target.value))}
          className="w-full h-1 rounded-lg appearance-none cursor-pointer"
          style={{ background: 'var(--reader-border)', accentColor: 'var(--reader-accent)' }}
        />
        <div className="flex justify-between text-xs mt-1" style={{ color: 'var(--reader-text)', opacity: 0.4 }}>
          <span>{(progress.progress || 0).toFixed(2)}%</span>
          <span>{['txt','mobi','fb2','html','markdown'].includes(format) ? '' : progress.page != null ? t('第 {{page}} 页', { page: progress.page }) : ''}</span>
        </div>
      </div>

      <div className="flex items-center justify-center gap-2">
        <button onClick={() => setSidebarTab('toc')} className={`${ctrlClasses.btn} ${ctrlClasses.btnDefault}`} title={t('目录')} aria-label={t('目录')}>
          <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 6h16M4 10h16M4 14h16M4 18h16" />
          </svg>
        </button>

        <button
          onClick={handleToggleBookmark}
          className={`${ctrlClasses.btn} ${isBookmarked ? 'text-yellow-400 bg-yellow-400/20 hover:bg-yellow-400/30' : ctrlClasses.btnDefault}`}
          title={t(isBookmarked ? '取消书签' : '添加书签')}
          aria-label={t(isBookmarked ? '取消书签' : '添加书签')}
        >
          <svg className="w-5 h-5" fill={isBookmarked ? 'currentColor' : 'none'} viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 5a2 2 0 012-2h10a2 2 0 012 2v16l-7-3.5L5 21V5z" />
          </svg>
        </button>

        <button onClick={() => setSidebarTab('bookmarks')} className={`${ctrlClasses.btn} ${ctrlClasses.btnDefault}`} title={t('书签列表')} aria-label={t('书签列表')}>
          <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10" />
          </svg>
        </button>

        <button
          onClick={toggleNoteInput}
          className={`${ctrlClasses.btn} ${showNoteInput ? 'text-[var(--reader-accent)] bg-[var(--color-indigo-bg)]' : ctrlClasses.btnDefault}`}
          title={t('添加笔记')}
          aria-label={t('添加笔记')}
        >
          <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
          </svg>
        </button>

        <button
          onClick={() => setShowSearch(!showSearch)}
          className={`${ctrlClasses.btn} ${showSearch ? 'text-[var(--reader-accent)] bg-[var(--color-indigo-bg)]' : ctrlClasses.btnDefault}`}
          title={t('搜索 (Ctrl+F)')}
          aria-label={t('搜索')}
        >
          <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
          </svg>
        </button>

        <button onClick={() => setSidebarTab('notes')} className={`${ctrlClasses.btn} ${ctrlClasses.btnDefault}`} title={t('笔记列表')} aria-label={t('笔记列表')}>
          <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
          </svg>
        </button>

        <div className="flex items-center gap-1 bg-[var(--reader-bg)]/30 rounded-lg p-1">
          {themes.map((themeId) => (
            <button
              key={themeId}
              onClick={() => setTheme(themeId)}
              className={`px-2 py-1 text-xs rounded transition-colors ${
                theme === themeId ? 'bg-[var(--reader-accent)] text-white' : 'opacity-50 hover:opacity-100'
              }`}
              style={{ color: theme !== themeId ? 'var(--reader-text)' : undefined }}
            >
              {themeId === 'light' ? t('亮') : themeId === 'dark' ? t('暗') : t('护眼')}
            </button>
          ))}
        </div>

        <button onClick={onOpenSettings} className={`${ctrlClasses.btn} ${ctrlClasses.btnDefault}`} title={t('设置')} aria-label={t('设置')}>
          <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.066 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.573 1.066c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.066-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
          </svg>
        </button>
      </div>
    </div>
  )
}
