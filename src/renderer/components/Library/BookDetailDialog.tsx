import { createPortal } from 'react-dom'
import type { Book } from '../../stores/libraryStore'
import { safeText } from '../../utils/safeText'
import { useTranslation } from 'react-i18next'
import i18n from '../../i18n'

interface BookDetailDialogProps {
  book: Book
  onClose: () => void
}

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function formatDate(dateStr: string): string {
  if (!dateStr) return '-'
  try {
    const d = new Date(dateStr)
    if (isNaN(d.getTime())) return dateStr
    return d.toLocaleString(i18n.language, { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
  } catch {
    return dateStr
  }
}

export function BookDetailDialog({ book, onClose }: BookDetailDialogProps) {
  const { t } = useTranslation()
  const fields: { label: string; value: string }[] = [
    { label: t('书名'), value: safeText(book.title) },
    { label: t('作者'), value: safeText(book.author) || '-' },
    { label: t('格式'), value: book.format.toUpperCase() },
    { label: t('语言'), value: book.language || '-' },
    { label: 'ISBN', value: book.isbn || '-' },
    { label: t('出版社'), value: book.publisher || '-' },
    { label: t('简介'), value: book.description || '-' },
    { label: t('文件路径'), value: book.file_path },
    { label: t('文件大小'), value: formatFileSize(book.file_size) },
    { label: t('添加时间'), value: formatDate(book.added_at) },
    { label: t('最后阅读'), value: formatDate(book.last_opened || '') },
  ]

  return createPortal(
    <div className="fixed inset-0 z-[9999] flex items-center justify-center animate-fade-in" onClick={onClose} role="dialog" aria-modal="true">
      <div className="absolute inset-0" style={{ background: 'rgba(0,0,0,0.45)' }} />
      <div className="relative rounded-xl shadow-win-lg p-6 max-w-md w-full mx-4 animate-scale-in max-h-[80vh] flex flex-col"
        style={{ background: 'var(--acrylic-bg)', backdropFilter: 'blur(24px)', border: '1px solid var(--acrylic-border)' }}
        onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between mb-4">
          <h3 className="text-base font-semibold" style={{ color: 'var(--text-primary)' }}>{t('书籍详情')}</h3>
          <button onClick={onClose} className="w-7 h-7 rounded flex items-center justify-center transition-colors hover:bg-white/10" style={{ color: 'var(--text-tertiary)' }}>
            <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" /></svg>
          </button>
        </div>
        <div className="overflow-y-auto space-y-2.5 pr-1">
          {fields.map((f) => (
            <div key={f.label} className="flex flex-col gap-0.5">
              <span className="text-xs font-medium" style={{ color: 'var(--text-tertiary)' }}>{f.label}</span>
              <span className="text-sm break-all" style={{ color: 'var(--text-primary)' }}>{f.value}</span>
            </div>
          ))}
        </div>
        <div className="mt-4 pt-3 border-t flex justify-end" style={{ borderColor: 'var(--acrylic-border)' }}>
          <button onClick={onClose} className="btn-secondary text-sm">{t('关闭')}</button>
        </div>
      </div>
    </div>,
    document.body
  )
}
