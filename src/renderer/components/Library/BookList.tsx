import { useState, useEffect, useRef } from 'react'
import { Book } from '../../stores/libraryStore'
import { useLibraryStore } from '../../stores/libraryStore'
import { safeText } from '../../utils/safeText'
import { formatColors } from '../../utils/format'
import { ConfirmDialog } from '../UI/ConfirmDialog'
import { useTranslation } from 'react-i18next'
import i18n from '../../i18n'

interface BookListProps { books: Book[]; onOpenBook: (bookId: number) => void }

// ── PB-017: 列表虚拟化 ────────────────────────────────────────────────
// 行高固定（py-3 + 40px 封面 = 64px），5000 本时只渲染可见行 + 上下 overscan；
// 表头常驻。小书库（<= 120 本）仍走原来的全量 map，避免布局差异。
const ROW_HEIGHT = 64
const OVERSCAN_ROWS = 6
const VIRTUALIZATION_THRESHOLD = 120

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

function ListCover({ book }: { book: Book }) {
  const [coverUrl, setCoverUrl] = useState<string | null>(null)

  useEffect(() => {
    let mounted = true
    window.electronAPI.getCoverImage(book.id).then((c) => { if (mounted && c) setCoverUrl(c) }).catch(() => {})
    return () => { mounted = false }
  }, [book.id])

  if (coverUrl) return <img src={coverUrl} alt="" className="w-7 h-10 rounded object-cover flex-shrink-0" style={{ boxShadow: 'var(--shadow-sm)' }} />
  return (
    <div className="w-7 h-10 rounded flex-shrink-0 flex items-center justify-center" style={{ background: 'var(--bg-tertiary)', color: 'var(--text-tertiary)' }}>
      <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253" /></svg>
    </div>
  )
}

export function BookList({ books, onOpenBook }: BookListProps) {
  const deleteBook = useLibraryStore((s) => s.deleteBook)
  const activeShelfId = useLibraryStore((s) => s.activeShelfId)
  const removeBookFromShelf = useLibraryStore((s) => s.removeBookFromShelf)
  const [deleteTarget, setDeleteTarget] = useState<Book | null>(null)
  const { t } = useTranslation()

  const headerRef = useRef<HTMLDivElement>(null)
  const rootRef = useRef<HTMLDivElement>(null)
  const [layout, setLayout] = useState({ viewportHeight: 0, offsetTop: 0 })
  const [scrollTop, setScrollTop] = useState(0)
  const virtualized = books.length > VIRTUALIZATION_THRESHOLD

  useEffect(() => {
    if (!virtualized) return
    const root = rootRef.current
    if (!root) return
    const scroller = root.closest('[data-library-scroll]') as HTMLElement | null
    if (!scroller) return
    let frame = 0
    const measure = () => {
      frame = 0
      const rootRect = root.getBoundingClientRect()
      const scrollRect = scroller.getBoundingClientRect()
      const headerHeight = headerRef.current?.offsetHeight ?? 0
      setLayout({
        viewportHeight: scroller.clientHeight,
        offsetTop: (rootRect.top - scrollRect.top) + scroller.scrollTop + headerHeight,
      })
      setScrollTop(scroller.scrollTop)
    }
    const onScroll = () => {
      if (frame) return
      frame = requestAnimationFrame(measure)
    }
    measure()
    scroller.addEventListener('scroll', onScroll, { passive: true })
    const observer = new ResizeObserver(measure)
    observer.observe(root)
    observer.observe(scroller)
    return () => {
      scroller.removeEventListener('scroll', onScroll)
      observer.disconnect()
      if (frame) cancelAnimationFrame(frame)
    }
  }, [virtualized, books.length])

  const handleDelete = (e: React.MouseEvent, book: Book) => {
    e.stopPropagation()
    if (activeShelfId != null) { removeBookFromShelf(activeShelfId, book.id); return }
    setDeleteTarget(book)
  }

  const renderRow = (book: Book) => (
    <div key={book.id} data-testid="book-row" onClick={() => onOpenBook(book.id)}
      role="button" tabIndex={0} aria-label={safeText(book.title)}
      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onOpenBook(book.id) } }}
      className="grid grid-cols-12 gap-4 px-3 py-3 rounded-lg cursor-pointer transition-colors duration-150 group hover:bg-[var(--md-sys-state-hover)] outline-none focus-visible:outline-2 focus-visible:outline-[var(--md-sys-state-focus-ring)]">
      <div className="col-span-6 flex items-center gap-3">
        <ListCover book={book} />
        <span className="m3-title-small truncate">{safeText(book.title)}</span>
      </div>
      <div className="col-span-2 text-sm truncate flex items-center" style={{ color: 'var(--text-secondary)' }}>{safeText(book.author) || '—'}</div>
      <div className="col-span-1 flex items-center">
        <span className={`m3-badge ${formatColors[book.format] || ''}`}>{book.format.toUpperCase()}</span>
      </div>
      <div className="col-span-1 text-sm flex items-center" style={{ color: 'var(--text-tertiary)' }}>{formatFileSize(book.file_size)}</div>
      <div className="col-span-1 text-sm flex items-center" style={{ color: 'var(--text-tertiary)' }}>{new Date(book.added_at).toLocaleDateString(i18n.language)}</div>
      <div className="col-span-1 flex items-center justify-end">
        <button onClick={(e) => handleDelete(e, book)} aria-label={activeShelfId != null ? t('从书柜移除') : t('删除')} title={activeShelfId != null ? t('从书柜移除') : t('删除')}
          className="m3-icon-button opacity-60 hover:opacity-100" style={{ color: activeShelfId != null ? 'var(--color-orange)' : 'var(--md-sys-color-error)' }}>
          <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d={activeShelfId != null ? "M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" : "M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"} />
          </svg>
        </button>
      </div>
    </div>
  )

  const header = (
    <div ref={headerRef} className="grid grid-cols-12 gap-4 px-3 py-2 m3-label-medium" style={{ borderBottom: '1px solid var(--md-sys-color-outline-variant)' }}>
      <div className="col-span-6">{t('书名')}</div>
      <div className="col-span-2">{t('作者')}</div>
      <div className="col-span-1">{t('格式')}</div>
      <div className="col-span-1">{t('大小')}</div>
      <div className="col-span-1">{t('添加时间')}</div>
      <div className="col-span-1"></div>
    </div>
  )

  let body: React.ReactNode
  if (!virtualized) {
    body = books.map(renderRow)
  } else {
    const totalRows = books.length
    const firstVisibleRow = Math.max(0, Math.floor((scrollTop - layout.offsetTop) / ROW_HEIGHT))
    const rowsInView = Math.max(1, Math.ceil((layout.viewportHeight || 800) / ROW_HEIGHT))
    let startRow = Math.max(0, firstVisibleRow - OVERSCAN_ROWS)
    let endRow = Math.min(totalRows, firstVisibleRow + rowsInView + OVERSCAN_ROWS + 1)
    if (startRow > endRow) startRow = Math.max(0, endRow - 1)
    const visible: Book[] = []
    for (let row = startRow; row < endRow; row++) {
      if (books[row]) visible.push(books[row])
    }
    body = (
      <div data-virtualized="1" data-row-height={ROW_HEIGHT} style={{ position: 'relative', height: totalRows * ROW_HEIGHT }}>
        {visible.map((book, index) => (
          <div key={book.id} style={{ position: 'absolute', top: (startRow + index) * ROW_HEIGHT, left: 0, right: 0 }}>
            {renderRow(book)}
          </div>
        ))}
      </div>
    )
  }

  return (
    <div ref={rootRef}>
      {header}
      {body}

      {deleteTarget && (
        <ConfirmDialog title={t('删除书籍')} message={t('确定要删除《{{title}}》吗？此操作不可撤销。', { title: safeText(deleteTarget.title) })} confirmText={t('删除')} danger
          onConfirm={() => { deleteBook(deleteTarget.id); setDeleteTarget(null) }} onCancel={() => setDeleteTarget(null)} />
      )}
    </div>
  )
}
