import { useState, useEffect, useLayoutEffect, useRef, memo } from 'react'
import { Book, useLibraryStore } from '../../stores/libraryStore'
import { safeText } from '../../utils/safeText'
import { formatReadingTime, extractTextPreview, formatBadgeLabel, formatColors } from '../../utils/format'
import { generatePdfPreview, generateCbzPreview } from '../../utils/preview'

// 卡片上做「文字预览」的格式白名单（2026-10-29）：二进制容器（pdf/cbz/cbr/mobi/azw3）
// 不按文本解码；epub 有封面，也不需要。
const TEXT_PREVIEW_FORMATS = new Set(['txt', 'markdown', 'html', 'fb2', 'docx'])
import { ConfirmDialog } from '../UI/ConfirmDialog'
import { BookDetailDialog } from './BookDetailDialog'
import { useTranslation } from 'react-i18next'

interface BookCardProps {
  book: Book
  onOpen: (bookId: number) => void
  onDelete: (bookId: number) => void
  onRemoveFromShelf?: (bookId: number) => void
  activeShelfId?: number | null
  /** PB-009-D：分屏阅读（选择第二本书） */
  onOpenSplit?: (bookId: number) => void
}

function BookCardInner({ book, onOpen, onDelete, onRemoveFromShelf, activeShelfId, onOpenSplit }: BookCardProps) {
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
  const menuRef = useRef<HTMLDivElement | null>(null)
  const cardRef = useRef<HTMLDivElement | null>(null)

  // 菜单内的方向键导航
  const focusMenuItem = (step: 1 | -1 | 'first' | 'last') => {
    const items = Array.from(menuRef.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? [])
    if (items.length === 0) return
    const current = items.indexOf(document.activeElement as HTMLElement)
    const next = step === 'first' ? 0
      : step === 'last' ? items.length - 1
        : current < 0 ? 0 : (current + step + items.length) % items.length
    items[next]?.focus()
  }

  // 视口夹边：书卡可能贴着窗口右/下边缘，右键后 fixed 定位的菜单会溢出到窗口外。
  // 先按 clientX/clientY 落位，再在首帧绘制前量一次尺寸纠正到 [8, innerWidth - w] 内。
  // 依赖里带上 showShelfMenu：子菜单展开/收起会改变菜单高度，靠底部时要重新夹。
  useLayoutEffect(() => {
    const el = menuRef.current
    if (!showMenu || !el) return
    const margin = 8
    const left = Math.min(Math.max(menuPos.x, margin), Math.max(margin, window.innerWidth - el.offsetWidth - margin))
    const top = Math.min(Math.max(menuPos.y, margin), Math.max(margin, window.innerHeight - el.offsetHeight - margin))
    if (left !== menuPos.x || top !== menuPos.y) setMenuPos({ x: left, y: top })
  }, [showMenu, showShelfMenu, menuPos.x, menuPos.y])

  // Esc / 点击外部关闭：都是窗口级监听（菜单项不在书卡的可聚焦路径里，只绑 onKeyDown 收不全）。
  // mousedown 而非 click：要在按下的瞬间收起，别让它漏到下层卡片上。
  // Esc 在捕获阶段 stopPropagation，保证这次的 Esc 只关菜单，不触发上层逐层关闭。
  useEffect(() => {
    if (!showMenu) return
    menuRef.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus()
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      e.stopPropagation()
      setShowMenu(false)
      setShowShelfMenu(false)
      window.requestAnimationFrame(() => { try { cardRef.current?.focus() } catch { /* ignore */ } })
    }
    const onMouseDown = (e: MouseEvent) => {
      if (menuRef.current?.contains(e.target as Node)) return
      setShowMenu(false)
      setShowShelfMenu(false)
    }
    document.addEventListener('keydown', onKeyDown, true)
    document.addEventListener('mousedown', onMouseDown)
    return () => {
      document.removeEventListener('keydown', onKeyDown, true)
      document.removeEventListener('mousedown', onMouseDown)
    }
  }, [showMenu])

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
      } else if (TEXT_PREVIEW_FORMATS.has(book.format)) {
        // 只对「纯文本 / 可读标记」格式做文字预览：mobi / azw3 是二进制容器，
        // 直接按 UTF-8 解码会把文件头（"Libmobi test sample … Title: …"）当成正文显示。
        const preview = await extractTextPreview(book.file_path, book.format)
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
    <div ref={cardRef} className="group relative cursor-pointer m3-card p-2.5 outline-none" onClick={() => onOpen(book.id)} onContextMenu={handleContextMenu} onMouseLeave={() => setShowMenu(false)}
      role="button" tabIndex={0} aria-label={safeText(book.title) + (book.author ? ' — ' + safeText(book.author) : '')}
      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onOpen(book.id) } }}
      data-testid="book-card" data-book-id={book.id}>

      {/* Cover card */}
      <div className="relative aspect-[2/3] overflow-hidden" style={{ borderRadius: 'var(--md-sys-shape-sm)', background: 'var(--md-sys-color-surface-container-high)' }}>
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
        <span className={`absolute top-2 right-2 m3-badge !min-h-[22px] max-w-[calc(100%-16px)] truncate ${formatColors[book.format] || ''}`}>{formatBadgeLabel(book.format)}</span>

        {/* Hover overlay */}
        <div className="absolute inset-0 bg-black/0 group-hover:bg-black/25 transition-colors duration-300 flex items-center justify-center">
          <svg className="w-10 h-10 text-white opacity-0 group-hover:opacity-100 transition-opacity duration-300" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" /><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" /></svg>
        </div>
      </div>

      {/* Title & author */}
      <div className="mt-3 px-1 pb-1">
        <p className="m3-title-small truncate">{safeText(book.title)}</p>
        <p className="m3-body-small truncate mt-0.5">{safeText(book.author) || t('未知作者')}</p>
        {book.series_name ? (
          <p className="m3-label-small truncate mt-0.5" title={book.series_name}>
            {book.series_name}{book.series_index ? ' #' + book.series_index : ''}
          </p>
        ) : null}
        {(book.rating ?? 0) > 0 ? (
          <div className="flex items-center gap-0.5 mt-0.5" style={{ color: 'var(--color-amber)' }} data-testid="book-rating" title={t('评分')}>
            {[1, 2, 3, 4, 5].map((star) => (
              <svg key={star} className="w-3 h-3" viewBox="0 0 24 24"
                fill={star <= (book.rating ?? 0) ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth={1.4}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M11.48 3.5a.562.562 0 011.04 0l2.1 4.6 5.03.5a.563.563 0 01.31.98l-3.76 3.35 1.06 4.94a.562.562 0 01-.83.6L12 16.6l-4.43 2.42a.562.562 0 01-.83-.6l1.06-4.94-3.76-3.35a.563.563 0 01.31-.98l5.03-.5 2.1-4.6z" />
              </svg>
            ))}
          </div>
        ) : null}
        {readingTime > 0 && (
          <p className="m3-label-small mt-1 flex items-center gap-1">
            <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" /></svg>
            {t('已读 {{time}}', { time: formatReadingTime(readingTime) })}
          </p>
        )}
      </div>

      {/* 右键菜单：表面统一到 .m3-menu / .m3-menu-item（与书库「更多操作」同一套 token）。
          保留原来的非 portal 写法（书卡内部 fixed 定位），只补视口夹边与键盘/点击外部关闭。 */}
      {showMenu && (
        <div ref={menuRef} role="menu" aria-label={t('书籍操作')}
          className="m3-menu fixed z-50 animate-scale-in"
          style={{ left: menuPos.x, top: menuPos.y }}
          onClick={(e) => e.stopPropagation()}
          onContextMenu={(e) => { e.preventDefault(); e.stopPropagation() }}
          onKeyDown={(e) => {
            // 菜单项本身是 <button>，Enter/Space 的原生激活已经够用；这里只拦住冒泡，
            // 否则书卡的 onKeyDown 会把同一次回车当成「打开这本书」再触发一遍。
            e.stopPropagation()
            if (e.key === 'ArrowDown' || e.key === 'ArrowUp') { e.preventDefault(); focusMenuItem(e.key === 'ArrowDown' ? 1 : -1); return }
            if (e.key === 'Home' || e.key === 'End') { e.preventDefault(); focusMenuItem(e.key === 'Home' ? 'first' : 'last') }
          }}>
          <button role="menuitem" onClick={(e) => { e.stopPropagation(); onOpen(book.id); setShowMenu(false) }}
            className="m3-menu-item">{t('打开')}</button>
          {onOpenSplit && (
            <button role="menuitem" onClick={(e) => { e.stopPropagation(); onOpenSplit(book.id); setShowMenu(false) }}
              className="m3-menu-item"
              data-book-split={book.id}>{t('分屏阅读')}</button>
          )}
          <button role="menuitem" onClick={(e) => { e.stopPropagation(); setShowDetail(true); setShowMenu(false) }}
            className="m3-menu-item">{t('详情')}</button>
          {bookshelves.length > 0 && (
            /* 子菜单展开态：aria-expanded + 箭头转 90° + 状态层背景（内联，因为
               Tailwind 工具类会被更靠后的 .m3-menu-item 同权重规则盖掉）。 */
            <button role="menuitem" aria-haspopup="menu" aria-expanded={showShelfMenu}
              onClick={(e) => { e.stopPropagation(); setShowShelfMenu(!showShelfMenu) }}
              className="m3-menu-item justify-between"
              style={showShelfMenu ? { background: 'var(--md-sys-state-hover)' } : undefined}>
              {t('添加到书柜')}
              <svg className={'w-3 h-3 shrink-0 transition-transform' + (showShelfMenu ? ' rotate-90' : '')}
                style={{ color: showShelfMenu ? 'var(--md-sys-color-primary)' : 'var(--md-sys-color-on-surface-variant)' }}
                fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" /></svg>
            </button>
          )}
          {showShelfMenu && bookshelves.map((shelf) => (
            <button key={shelf.id} role="menuitem" onClick={(e) => { e.stopPropagation(); addBookToShelf(shelf.id, book.id); setShowMenu(false); setShowShelfMenu(false) }}
              className="m3-menu-item !pl-8">{shelf.name}</button>
          ))}
          {activeShelfId != null && onRemoveFromShelf ? (
            <button role="menuitem" onClick={(e) => { e.stopPropagation(); onRemoveFromShelf(book.id); setShowMenu(false) }}
              className="m3-menu-item">{t('从书柜移除')}</button>
          ) : (
            <button role="menuitem" onClick={(e) => { e.stopPropagation(); setShowConfirmDelete(true); setShowMenu(false) }}
              className="m3-menu-item is-destructive">{t('删除')}</button>
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
