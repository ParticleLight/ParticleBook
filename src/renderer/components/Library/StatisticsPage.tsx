import { useState, useEffect } from 'react'
import { useLibraryStore } from '../../stores/libraryStore'
import type { Book } from '../../stores/libraryStore'
import { formatReadingTime, extractTextPreview } from '../../utils/format'
import { generatePdfPreview, generateCbzPreview } from '../../utils/preview'

interface StatisticsPageProps { onBack: () => void }

function BookRow({ book, readingTime, progress }: { book: Book; readingTime: number; progress: number }) {
  const [coverUrl, setCoverUrl] = useState<string | null>(null)
  const [textPreview, setTextPreview] = useState<string | null>(null)

  useEffect(() => {
    let mounted = true
    const load = async () => {
      const cover = await window.electronAPI.getCoverImage(book.id)
      if (!mounted) return
      if (cover) { setCoverUrl(cover) }
      else if (book.format === 'pdf') { const p = await generatePdfPreview(book.file_path); if (mounted && p) setCoverUrl(p) }
      else if (book.format === 'cbz' || book.format === 'cbr') { const p = await generateCbzPreview(book.file_path); if (mounted && p) setCoverUrl(p) }
      else { const p = await extractTextPreview(book.file_path); if (mounted) setTextPreview(p) }
    }
    load()
    return () => { mounted = false }
  }, [book.id, book.file_path, book.format])

  return (
    <div className="flex items-center gap-3 py-2">
      <div className="w-10 h-14 rounded overflow-hidden flex-shrink-0" style={{ background: 'var(--bg-tertiary)' }}>
        {coverUrl ? <img src={coverUrl} alt={book.title} className="w-full h-full object-cover" />
          : textPreview ? (
            <div className="w-full h-full flex flex-col p-1.5 relative overflow-hidden" style={{ background: 'linear-gradient(135deg, var(--color-amber-bg), var(--color-amber) / 0.15)' }}>
              <span className="text-[8px] font-medium truncate" style={{ color: 'var(--color-amber)' }}>{book.title}</span>
              <p className="text-[7px] leading-[10px] break-all line-clamp-[4] mt-0.5" style={{ color: 'var(--text-secondary)' }}>{textPreview}</p>
            </div>
          ) : <div className="w-full h-full flex items-center justify-center text-[10px]" style={{ color: 'var(--text-tertiary)' }}>{book.format.toUpperCase()}</div>}
      </div>
      <div className="flex-1 min-w-0">
        <div className="text-sm truncate" style={{ color: 'var(--text-primary)' }}>{book.title}</div>
        <div className="text-xs truncate" style={{ color: 'var(--text-tertiary)' }}>{book.author || '未知作者'}</div>
        <div className="mt-1.5 h-1 rounded-full" style={{ background: 'var(--bg-tertiary)' }}>
          <div className="h-full rounded-full transition-all" style={{ width: `${Math.min(100, Math.round(progress))}%`, background: 'var(--accent)' }} />
        </div>
      </div>
      <div className="text-right flex-shrink-0 w-24">
        <div className="text-sm" style={{ color: 'var(--text-secondary)' }}>{Math.min(100, Math.round(progress * 10) / 10)}%</div>
        <div className="text-xs" style={{ color: 'var(--text-tertiary)' }}>{readingTime > 0 ? formatReadingTime(readingTime) : '未阅读'}</div>
      </div>
    </div>
  )
}

export function StatisticsPage({ onBack }: StatisticsPageProps) {
  const readingTimeMap = useLibraryStore((s) => s.readingTimeMap)
  const readingProgressMap = useLibraryStore((s) => s.readingProgressMap)
  const loadReadingTime = useLibraryStore((s) => s.loadReadingTime)
  const loadReadingProgress = useLibraryStore((s) => s.loadReadingProgress)
  const [allBooks, setAllBooks] = useState<Book[]>([])

  // Refresh stats on mount — reading sessions finish async (endReadingSession)
  // so the values loaded at Library mount can be stale by the time we get here.
  useEffect(() => {
    loadReadingTime()
    loadReadingProgress()
    window.electronAPI.getBooks().then((books) => setAllBooks(books as Book[]))
  }, [loadReadingTime, loadReadingProgress])

  const totalTime = Object.values(readingTimeMap).reduce((sum, t) => sum + t, 0)
  const booksWithProgress = Object.keys(readingProgressMap).length

  const bookStats = allBooks.map((book) => ({ book, readingTime: readingTimeMap[book.id] || 0, progress: readingProgressMap[book.id]?.progress || 0 })).sort((a, b) => b.readingTime - a.readingTime)

  return (
    <div className="h-full flex flex-col" style={{ background: 'var(--bg)' }}>
      {/* Header */}
      <div className="flex items-center gap-3 px-6 py-4" style={{ borderBottom: '1px solid var(--border)' }}>
        <button onClick={onBack} className="p-1 rounded-md transition-colors" style={{ color: 'var(--text-tertiary)' }}>
          <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" /></svg>
        </button>
        <div className="flex items-center gap-2">
          <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" style={{ color: 'var(--accent)' }}><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" /></svg>
          <span className="text-lg font-semibold" style={{ color: 'var(--text-primary)' }}>阅读统计</span>
        </div>
      </div>

      {/* Summary */}
      <div className="grid grid-cols-3 gap-4 px-6 py-4" style={{ borderBottom: '1px solid var(--border)' }}>
        <div className="text-center"><div className="text-2xl font-bold" style={{ color: 'var(--accent)' }}>{allBooks.length}</div><div className="text-xs mt-1" style={{ color: 'var(--text-tertiary)' }}>总书籍</div></div>
        <div className="text-center"><div className="text-2xl font-bold" style={{ color: 'var(--color-green)' }}>{formatReadingTime(totalTime)}</div><div className="text-xs mt-1" style={{ color: 'var(--text-tertiary)' }}>总阅读时间</div></div>
        <div className="text-center"><div className="text-2xl font-bold" style={{ color: 'var(--color-yellow)' }}>{booksWithProgress}</div><div className="text-xs mt-1" style={{ color: 'var(--text-tertiary)' }}>已开始阅读</div></div>
      </div>

      {/* Book list */}
      <div className="flex-1 overflow-y-auto px-6 py-3">
        {bookStats.length === 0 ? <div className="text-center py-8" style={{ color: 'var(--text-tertiary)' }}>暂无书籍</div> : <div className="space-y-3">{bookStats.map((item) => <BookRow key={item.book.id} book={item.book} readingTime={item.readingTime} progress={item.progress} />)}</div>}
      </div>
    </div>
  )
}
