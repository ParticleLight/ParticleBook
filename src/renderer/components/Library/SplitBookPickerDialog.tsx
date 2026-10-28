import { useEffect, useMemo, useState, type CSSProperties } from 'react'
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
          <button className="m3-outlined-button" onClick={onCancel}>{t('取消')}</button>
          <button
            className="m3-filled-button"
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
          className="m3-text-field is-dense w-full mb-3"
          aria-label={t('搜索书籍')}
          data-split-search="1"
        />
        {candidates.length === 0 ? (
          <p className="m3-body-medium" style={{ color: 'var(--md-sys-color-on-surface-variant)' }}>{t('书架里没有其它书')}</p>
        ) : (
          /* 列表表面：仍由 Dialog（surface-container-high）承担 —— 再套一层同色的 .m3-menu
             只会多出一道边框、双层阴影，并不比现在更像菜单。行本身已用 .m3-menu-item
             （40px / radius 8 / body-medium / hover state）。
             选中态走 aria-selected，交给 globals.css 的
             `.m3-menu-item[aria-selected="true"]`（primary-container + on-primary-container）；
             这里只补一圈 primary 描边（inset 阴影画，不占位、不导致行移位）。
             禁用态交给 `.m3-menu-item:disabled`（38% 不透明度），只额外压掉禁用项的悬停底色。 */
          <div role="listbox" aria-label={t('选择第二本书')} className="space-y-1 max-h-[46vh] overflow-y-auto">
            {candidates.map((book) => {
              const bookSupported = isSplitReadable(book.format)
              const isSelected = selected === book.id
              // 不再给未选中行内联 background/opacity/cursor：内联样式会盖掉更靠后的
              // .m3-menu-item:hover / :disabled，这列候选此前完全没有悬停、禁用观感也不统一。
              const ringStyle: CSSProperties | undefined = isSelected
                ? { boxShadow: 'inset 0 0 0 1px var(--md-sys-color-primary)' }
                : undefined
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
                  className="m3-menu-item w-full text-left disabled:hover:bg-transparent"
                  style={ringStyle}
                >
                  <span className="flex-1 truncate text-sm">{book.title}</span>
                  {book.author && <span className="text-xs truncate max-w-[35%]" style={{ color: 'var(--md-sys-color-on-surface-variant)' }}>{book.author}</span>}
                  <span className="text-[10px] px-1.5 py-0.5 rounded shrink-0" style={{ background: 'var(--md-sys-color-surface-container-highest)', color: 'var(--md-sys-color-on-surface-variant)' }}>
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
