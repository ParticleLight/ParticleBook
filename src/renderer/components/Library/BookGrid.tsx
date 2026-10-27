import { useCallback, useEffect, useRef, useState } from 'react'
import { Book } from '../../stores/libraryStore'
import { useLibraryStore } from '../../stores/libraryStore'
import { BookCard } from './BookCard'

interface BookGridProps { books: Book[]; onOpenBook: (bookId: number) => void; onOpenSplit?: (bookId: number) => void }

// ── PB-017: 网格虚拟化 ────────────────────────────────────────────────
// 5000 本时只渲染「可见行 + 上下 OVERSCAN_ROWS 行」的 BookCard，DOM 数量与
// 书量无关；滚动容器是 Library 里的 data-library-scroll（不是 window）。
// 断点与原来的 grid-cols-2/3/4/5/6/7 完全一致；小书库（<= 120 本）仍走原来的
// 全量 grid，避免给常规使用引入布局差异。
const BREAKPOINTS: Array<{ minWidth: number; columns: number }> = [
  { minWidth: 1536, columns: 7 },
  { minWidth: 1280, columns: 6 },
  { minWidth: 1024, columns: 5 },
  { minWidth: 768, columns: 4 },
  { minWidth: 640, columns: 3 },
  { minWidth: 0, columns: 2 },
]
const GAP = 20 // 原 gap-5
const OVERSCAN_ROWS = 2
const VIRTUALIZATION_THRESHOLD = 120
// 封面 aspect-[2/3]，下面还有 title/author/series/rating/reading-time 至多 ~100px。
const TEXT_BLOCK_HEIGHT = 112

function columnsForWidth(width: number): number {
  for (const breakpoint of BREAKPOINTS) {
    if (width >= breakpoint.minWidth) return breakpoint.columns
  }
  return 2
}

function estimateRowHeight(width: number, columns: number): number {
  const cardWidth = Math.max(80, (width - GAP * (columns - 1)) / columns)
  return Math.ceil(cardWidth * 1.5) + TEXT_BLOCK_HEIGHT
}

export function BookGrid({ books, onOpenBook, onOpenSplit }: BookGridProps) {
  const deleteBook = useLibraryStore((s) => s.deleteBook)
  const activeShelfId = useLibraryStore((s) => s.activeShelfId)
  const removeBookFromShelf = useLibraryStore((s) => s.removeBookFromShelf)

  const handleRemoveFromShelf = useCallback((bookId: number) => {
    if (activeShelfId != null) removeBookFromShelf(activeShelfId, bookId)
  }, [activeShelfId, removeBookFromShelf])

  const rootRef = useRef<HTMLDivElement>(null)
  const [layout, setLayout] = useState({
    width: 0,
    viewportHeight: 0,
    viewportWidth: typeof window === 'undefined' ? 0 : window.innerWidth,
    offsetTop: 0,
  })
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
      setLayout({
        width: root.clientWidth,
        viewportHeight: scroller.clientHeight,
        // 列数与原来的 grid-cols-* 一样按「视口宽度」断点（不是内容区宽度）
        viewportWidth: window.innerWidth,
        // root 在滚动内容里的位置（包含顶部筛选栏 / padding 的偏移）
        offsetTop: (rootRect.top - scrollRect.top) + scroller.scrollTop,
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

  if (!virtualized) {
    return (
      <div ref={rootRef} data-virtualized="0"
        className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 2xl:grid-cols-7 gap-5">
        {books.map((book) => (
          <BookCard key={book.id} book={book} onOpen={onOpenBook} onDelete={deleteBook} onOpenSplit={onOpenSplit}
            onRemoveFromShelf={activeShelfId != null ? handleRemoveFromShelf : undefined}
            activeShelfId={activeShelfId} />
        ))}
      </div>
    )
  }

  const width = layout.width
  const columns = columnsForWidth(layout.viewportWidth || (typeof window !== 'undefined' ? window.innerWidth : 0))
  const rowHeight = estimateRowHeight(width, columns)
  const totalRows = Math.ceil(books.length / columns)
  const firstVisibleRow = Math.max(0, Math.floor((scrollTop - layout.offsetTop) / rowHeight))
  const rowsInView = Math.max(1, Math.ceil((layout.viewportHeight || 800) / rowHeight))
  let startRow = Math.max(0, firstVisibleRow - OVERSCAN_ROWS)
  let endRow = Math.min(totalRows, firstVisibleRow + rowsInView + OVERSCAN_ROWS + 1)
  if (startRow > endRow) startRow = Math.max(0, endRow - 1)

  const rows: number[] = []
  for (let row = startRow; row < endRow; row++) rows.push(row)

  return (
    <div ref={rootRef} data-virtualized="1" data-row-height={rowHeight} data-columns={columns}
      style={{ position: 'relative', height: totalRows * rowHeight }}>
      {rows.map((row) => {
        const rowBooks = books.slice(row * columns, row * columns + columns)
        return (
          <div key={row} data-virtual-row={row}
            style={{
              position: 'absolute',
              top: row * rowHeight,
              left: 0,
              right: 0,
              display: 'grid',
              gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))`,
              gap: GAP,
              alignItems: 'start',
            }}>
            {rowBooks.map((book) => (
              <BookCard key={book.id} book={book} onOpen={onOpenBook} onDelete={deleteBook} onOpenSplit={onOpenSplit}
                onRemoveFromShelf={activeShelfId != null ? handleRemoveFromShelf : undefined}
                activeShelfId={activeShelfId} />
            ))}
          </div>
        )
      })}
    </div>
  )
}
