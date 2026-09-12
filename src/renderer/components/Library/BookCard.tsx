import { useState, useEffect, useRef, memo } from 'react'
import { Book, useLibraryStore } from '../../stores/libraryStore'
import { safeText } from '../../utils/safeText'
import { formatReadingTime, extractTextPreview, formatColors } from '../../utils/format'
import { generatePdfPreview, generateCbzPreview } from '../../utils/preview'
import { ConfirmDialog } from '../UI/ConfirmDialog'
import { BookDetailDialog } from './BookDetailDialog'
import { useTranslation } from 'react-i18next'

interface BookCardProps {
  book: Book
  onOpen: (bookId: number) => void
  onDelete: (bookId: number) => void
  onRemoveFromShelf?: (bookId: number) => void
  activeShelfId?: number | null
}

function BookCardInner({ book, onOpen, onDelete, onRemoveFromShelf, activeShelfId }: BookCardProps) {
  const [coverUrl, setCoverUrl] = useState<string | null>(null)
  const [textPreview, setTextPreview] = useState<string | null>(null)
  const [showMenu, setShowMenu] = useState(false)
  const [showShelfMenu, setShowShelfMenu] = useState(false)
  const [showConfirmDelete, setShowConfirmDelete] = useState(false)
  const [showDetail, setShowDetail] = useState(false)
  const [menuPos, setMenuPos] = useState({ x: 0, y: 0 })
  const objectUrlRef = useRef<string | null>(null)

  const bookshelves = useLibraryStore((s) => s.bookshelves)
  const addBookToShelf = useLibraryStore((s) => s.addBookToShelf)
  const readingTime = useLibraryStore((s) => s.readingTimeMap[book.id] || 0)
  const { t } = useTranslation()

  useEffect(() => {
    let mounted = true
    const loadCover = async () => {
      try {
        const cover = await window.electronAPI.getCoverImage(book.id)
        if (mounted && cover) { setCoverUrl(cover); return }
      } catch {}
      if (book.format === 'pdf') {
        const preview = await generatePdfPreview(book.file_path)
        if (mounted && preview) setCoverUrl(preview)
      } else if (book.format === 'cbz' || book.format === 'cbr') {
        const preview = await generateCbzPreview(book.file_path)
        if (mounted && preview) { objectUrlRef.current = preview; setCoverUrl(preview) }
      } else {
        const preview = await extractTextPreview(book.file_path)
        if (mounted && preview) setTextPreview(preview)
      }
    }
    loadCover()
    return () => { mounted = false; if (objectUrlRef.current) URL.revokeObjectURL(objectUrlRef.current) }
  }, [book.id, book.file_path, book.format])

  // 打开菜单时把"添加到书柜"子菜单复位：此前子菜单展开态只在真正选了书柜时才清，
  // 鼠标移出关闭菜单后再右键同一张卡，子菜单会莫名其妙已经展开着。
  const handleContextMenu = (e: React.MouseEvent) => { e.preventDefault(); setMenuPos({ x: e.clientX, y: e.clientY }); setShowShelfMenu(false); setShowMenu(true) }

  return (
    <div className="group relative cursor-pointer" onClick={() => onOpen(book.id)} onContextMenu={handleContextMenu} onMouseLeave={() => setShowMenu(false)}>

      {/* Cover card */}
      <div className="relative aspect-[2/3] rounded-lg overflow-hidden transition-all duration-300 group-hover:-translate-y-1 group-hover:shadow-win-lg" style={{ boxShadow: 'var(--shadow-sm)' }}>
        {coverUrl ? (
          <img src={coverUrl} alt={book.title} className="w-full h-full object-cover" />
        ) : textPreview ? (
          <div className="w-full h-full flex flex-col p-4 relative overflow-hidden" style={{ background: 'linear-gradient(135deg, var(--color-amber-bg), var(--color-amber) / 0.15)' }}>
            <div className="absolute inset-0 opacity-5 bg-[repeating-linear-gradient(0deg,transparent,transparent_23px,#fff_24px)]" />
            <span className="text-xs font-medium mb-2 truncate relative z-10" style={{ color: 'var(--color-amber)' }}>{safeText(book.title)}</span>
            <p className="text-[11px] leading-[18px] break-all line-clamp-[8] relative z-10" style={{ color: 'var(--text-secondary)' }}>{textPreview}...</p>
          </div>
        ) : (
          <div className="w-full h-full flex flex-col items-center justify-center p-4" style={{ background: 'linear-gradient(135deg, var(--bg-secondary), var(--bg-tertiary))' }}>
            <svg className="w-12 h-12 mb-3" fill="none" viewBox="0 0 24 24" stroke="currentColor" style={{ color: 'var(--text-tertiary)' }}><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253" /></svg>
            <span className="text-xs text-center line-clamp-2" style={{ color: 'var(--text-tertiary)' }}>{safeText(book.title)}</span>
          </div>
        )}

        {/* Format badge */}
        <span className={`absolute top-2 right-2 text-xs font-bold text-white px-2 py-0.5 rounded ${formatColors[book.format] || 'bg-[var(--text-tertiary)]'}`}>{book.format.toUpperCase()}</span>

        {/* Hover overlay */}
        <div className="absolute inset-0 bg-black/0 group-hover:bg-black/25 transition-colors duration-300 flex items-center justify-center">
          <svg className="w-10 h-10 text-white opacity-0 group-hover:opacity-100 transition-opacity duration-300" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" /><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" /></svg>
        </div>
      </div>

      {/* Title & author */}
      <div className="mt-2 px-0.5">
        <p className="text-sm font-medium truncate" style={{ color: 'var(--text-primary)' }}>{safeText(book.title)}</p>
        <p className="text-xs truncate" style={{ color: 'var(--text-tertiary)' }}>{safeText(book.author) || t('未知作者')}</p>
        {readingTime > 0 && (
          <p className="text-xs mt-0.5 flex items-center gap-1" style={{ color: 'var(--text-tertiary)' }}>
            <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" /></svg>
            {t('已读 {{time}}', { time: formatReadingTime(readingTime) })}
          </p>
        )}
      </div>

      {/* Acrylic context menu */}
      {showMenu && (
        <div className="fixed z-50 rounded-lg overflow-hidden shadow-win-lg py-0.5 min-w-[120px] animate-scale-in"
          style={{ left: menuPos.x, top: menuPos.y, background: 'var(--acrylic-bg)', backdropFilter: 'blur(24px)', border: '1px solid var(--acrylic-border)' }}>
          <button onClick={(e) => { e.stopPropagation(); onOpen(book.id); setShowMenu(false) }}
            className="w-full text-left px-4 py-2 text-sm transition-colors" style={{ color: 'var(--text-primary)' }}
            onMouseEnter={(e) => e.currentTarget.style.background = 'var(--surface-hover)'}
            onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}>{t('打开')}</button>
          <button onClick={(e) => { e.stopPropagation(); setShowDetail(true); setShowMenu(false) }}
            className="w-full text-left px-4 py-2 text-sm transition-colors" style={{ color: 'var(--text-primary)' }}
            onMouseEnter={(e) => e.currentTarget.style.background = 'var(--surface-hover)'}
            onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}>{t('详情')}</button>
          {bookshelves.length > 0 && (
            <button onClick={(e) => { e.stopPropagation(); setShowShelfMenu(!showShelfMenu) }}
              className="w-full text-left px-4 py-2 text-sm transition-colors flex items-center justify-between" style={{ color: 'var(--text-primary)' }}
              onMouseEnter={(e) => e.currentTarget.style.background = 'var(--surface-hover)'}
              onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}>
              {t('添加到书柜')}
              <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" /></svg>
            </button>
          )}
          {showShelfMenu && bookshelves.map((shelf) => (
            <button key={shelf.id} onClick={(e) => { e.stopPropagation(); addBookToShelf(shelf.id, book.id); setShowMenu(false); setShowShelfMenu(false) }}
              className="w-full text-left px-6 py-1.5 text-sm transition-colors" style={{ color: 'var(--text-secondary)' }}
              onMouseEnter={(e) => { e.currentTarget.style.background = 'var(--surface-hover)'; e.currentTarget.style.color = 'var(--text-primary)' }}
              onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = 'var(--text-secondary)' }}>{shelf.name}</button>
          ))}
          {activeShelfId != null && onRemoveFromShelf ? (
            <button onClick={(e) => { e.stopPropagation(); onRemoveFromShelf(book.id); setShowMenu(false) }}
              className="w-full text-left px-4 py-2 text-sm transition-colors" style={{ color: 'var(--color-orange)' }}
              onMouseEnter={(e) => e.currentTarget.style.background = 'var(--color-orange-bg)'}
              onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}>{t('从书柜移除')}</button>
          ) : (
            <button onClick={(e) => { e.stopPropagation(); setShowConfirmDelete(true); setShowMenu(false) }}
              className="w-full text-left px-4 py-2 text-sm transition-colors" style={{ color: 'var(--color-red)' }}
              onMouseEnter={(e) => e.currentTarget.style.background = 'var(--color-red-bg)'}
              onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}>{t('删除')}</button>
          )}
        </div>
      )}

      {showConfirmDelete && (
        <ConfirmDialog title={t('删除书籍')} message={t('确定要删除《{{title}}》吗？此操作不可撤销。', { title: safeText(book.title) })} confirmText={t('删除')} danger
          onConfirm={() => { onDelete(book.id); setShowConfirmDelete(false) }} onCancel={() => setShowConfirmDelete(false)} />
      )}
      {showDetail && (
        <BookDetailDialog book={book} onClose={() => setShowDetail(false)} />
      )}
    </div>
  )
}

export const BookCard = memo(BookCardInner)
