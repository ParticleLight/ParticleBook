import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useLibraryStore } from '../../stores/libraryStore'
import { isSplitReadable } from '../../stores/readerSession'
import { Dialog } from '../UI/Dialog'

// ── PB-009-D：书库/阅读器的「分屏阅读」选书弹层 ─────────────────────
// 选择要和 primary 并排的第二本书；只允许 EPUB / TXT（其余格式禁用并标注）。
// 数据来自 libraryStore（已在 App 启动时 loadBooks）。

export interface SplitBookPickerDialogProps {
  open: boolean
  /** 第一本书（已在读或右键的那本）；null 表示只选第二本 */
  primaryBookId: number | null
  onCancel: () => void
  onConfirm: (secondaryBookId: number) => void
}

export function SplitBookPickerDialog({ open, primaryBookId, onCancel, onConfirm }: SplitBookPickerDialogProps) {
  const { t } = useTranslation()
  const books = useLibraryStore((state) => state.books)
  const [query, setQuery] = useState('')
  const [selected, setSelected] = useState<number | null>(null)

  useEffect(() => {
    if (open) {
      setQuery('')
      setSelected(null)
    }
  }, [open])

  const primary = useMemo(
    () => books.find((book) => book.id === primaryBookId) || null,
    [books, primaryBookId],
  )

  const candidates = useMemo(() => {
    const keyword = query.trim().toLowerCase()
    return books
      .filter((book) => book.id !== primaryBookId)
      .filter((book) => !keyword || book.title.toLowerCase().includes(keyword) || (book.author || '').toLowerCase().includes(keyword))
  }, [books, primaryBookId, query])

  const supported = candidates.filter((book) => isSplitReadable(book.format))
  const canConfirm = selected !== null && supported.some((book) => book.id === selected)

  return (
    <Dialog
      open={open}
      onClose={onCancel}
      title={t('分屏阅读')}
      description={primary
        ? t('与《{{title}}》并排阅读，选择第二本书', { title: primary.title })
        : t('选择第二本书')}
      footer={(
        <>
          <button className="btn-secondary" onClick={onCancel}>{t('取消')}</button>
          <button
            className="btn-primary"
            disabled={!canConfirm}
            onClick={() => { if (canConfirm && selected !== null) onConfirm(selected) }}
            data-split-confirm="1"
          >
            {t('开始分屏')}
          </button>
        </>
      )}
    >
      <div data-split-picker="1">
        <input
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder={t('搜索书籍')}
          className="w-full text-sm rounded-md px-2 py-1.5 mb-3"
          style={{ background: 'var(--bg-tertiary)', color: 'var(--text-primary)', border: '1px solid var(--border)' }}
          aria-label={t('搜索书籍')}
          data-split-search="1"
        />
        {candidates.length === 0 ? (
          <p className="text-sm" style={{ color: 'var(--text-tertiary)' }}>{t('书架里没有其它书')}</p>
        ) : (
          <div role="listbox" aria-label={t('选择第二本书')} className="space-y-1 max-h-[46vh] overflow-y-auto">
            {candidates.map((book) => {
              const bookSupported = isSplitReadable(book.format)
              const isSelected = selected === book.id
              return (
                <button
                  key={book.id}
                  type="button"
                  role="option"
                  aria-selected={isSelected}
                  disabled={!bookSupported}
                  onClick={() => setSelected(book.id)}
                  data-split-candidate={book.id}
                  data-split-supported={bookSupported ? '1' : '0'}
                  className="w-full text-left px-3 py-2 rounded-md flex items-center gap-2 transition-colors"
                  style={{
                    background: isSelected ? 'var(--color-indigo-bg)' : 'transparent',
                    color: bookSupported ? 'var(--text-primary)' : 'var(--text-tertiary)',
                    opacity: bookSupported ? 1 : 0.6,
                    cursor: bookSupported ? 'pointer' : 'not-allowed',
                    border: '1px solid ' + (isSelected ? 'var(--border-focus)' : 'transparent'),
                  }}
                >
                  <span className="flex-1 truncate text-sm">{book.title}</span>
                  {book.author && <span className="text-xs truncate max-w-[35%]" style={{ color: 'var(--text-tertiary)' }}>{book.author}</span>}
                  <span className="text-[10px] px-1.5 py-0.5 rounded shrink-0" style={{ background: 'var(--surface-hover)', color: 'var(--text-secondary)' }}>
                    {book.format.toUpperCase()}
                  </span>
                  {!bookSupported && (
                    <span className="text-[10px] shrink-0" style={{ color: 'var(--color-orange)' }}>{t('不支持分屏')}</span>
                  )}
                </button>
              )
            })}
          </div>
        )}
        {candidates.length > 0 && supported.length === 0 && (
          <p className="text-xs mt-2" style={{ color: 'var(--color-orange)' }}>{t('只能选择 EPUB 或 TXT 格式的书')}</p>
        )}
      </div>
    </Dialog>
  )
}
