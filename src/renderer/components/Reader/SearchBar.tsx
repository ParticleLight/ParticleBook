import { useEffect, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { useReaderStore } from '../../stores/readerStore'

export function SearchBar() {
  const { t } = useTranslation()
  const searchQuery = useReaderStore((s) => s.searchQuery)
  const searchMatches = useReaderStore((s) => s.searchMatches)
  const currentSearchIndex = useReaderStore((s) => s.currentSearchIndex)
  const setSearchQuery = useReaderStore((s) => s.setSearchQuery)
  const nextSearchMatch = useReaderStore((s) => s.nextSearchMatch)
  const prevSearchMatch = useReaderStore((s) => s.prevSearchMatch)
  const clearSearch = useReaderStore((s) => s.clearSearch)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    inputRef.current?.focus()
  }, [])

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      e.preventDefault()
      if (e.shiftKey) prevSearchMatch()
      else nextSearchMatch()
    } else if (e.key === 'Escape') {
      e.preventDefault()
      clearSearch()
    }
  }

  const handleInput = (e: React.ChangeEvent<HTMLInputElement>) => {
    setSearchQuery(e.target.value)
  }

  const matchLabel = searchMatches.length > 0
    ? `${currentSearchIndex + 1}/${searchMatches.length}`
    : searchQuery ? '0/0' : ''

  return (
    <div className="absolute top-12 right-4 z-30 flex items-center gap-1 rounded-xl px-3 py-2 animate-scale-in"
      style={{ background: 'var(--acrylic-bg)', backdropFilter: 'blur(20px)', border: '1px solid var(--acrylic-border)', boxShadow: '0 8px 32px rgba(0,0,0,0.4)' }}>
      <svg className="w-4 h-4 shrink-0" style={{ color: 'var(--text-tertiary)' }} fill="none" viewBox="0 0 24 24" stroke="currentColor">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
      </svg>
      <input
        ref={inputRef}
        type="text"
        value={searchQuery}
        onChange={handleInput}
        onKeyDown={handleKeyDown}
        placeholder={t('搜索全书...')}
        className="bg-transparent text-sm outline-none w-48"
        style={{ color: 'var(--text-primary)' }}
      />
      <span className="text-xs shrink-0 min-w-[2.5rem] text-center" style={{ color: searchMatches.length === 0 && searchQuery ? 'var(--color-red)' : 'var(--text-tertiary)' }}>
        {matchLabel}
      </span>
      <button
        onClick={prevSearchMatch}
        className="w-7 h-7 rounded flex items-center justify-center hover:bg-white/10 transition-colors"
        style={{ color: 'var(--text-secondary)' }}
        title={t('上一个 (Shift+Enter)')}
      >
        <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 15l7-7 7 7" />
        </svg>
      </button>
      <button
        onClick={nextSearchMatch}
        className="w-7 h-7 rounded flex items-center justify-center hover:bg-white/10 transition-colors"
        style={{ color: 'var(--text-secondary)' }}
        title={t('下一个 (Enter)')}
      >
        <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
        </svg>
      </button>
      <button
        onClick={clearSearch}
        className="w-7 h-7 rounded flex items-center justify-center hover:bg-white/10 transition-colors"
        style={{ color: 'var(--text-tertiary)' }}
        title={t('关闭 (Esc)')}
      >
        <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
        </svg>
      </button>
    </div>
  )
}
