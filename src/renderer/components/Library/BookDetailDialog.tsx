// ── PB-005：书籍详情 / 元数据编辑弹窗 ────────────────────────────────
//
// 从只读详情升级为可编辑：书名 / 作者 / 语言 / 出版社 / 简介 + 标签多选、
// 系列名 + 序号、1-5 星评分。保存走 libraryStore.updateBookMetadata →
// bridge book:updateMetadata（C++ 侧白名单 + 空书名/类型校验，非法返回 null）。
//
// 默认值：tags 空数组、series_name 空串、series_index 0、rating 0（未评分）；
// 保存成功后父组件（BookCard）随 store 里的 book 对象更新自动重渲染。
// 只读的文件信息（路径 / 大小 / 时间）保留在弹窗底部，不影响编辑。

import { useState } from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import i18n from '../../i18n'
import type { Book } from '../../stores/libraryStore'
import { useLibraryStore } from '../../stores/libraryStore'
import { safeText } from '../../utils/safeText'

interface BookDetailDialogProps {
  book: Book
  onClose: () => void
}

function formatFileSize(bytes: number): string {
  if (!Number.isFinite(bytes)) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
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

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="m3-label-medium">{label}</span>
      {children}
    </label>
  )
}

export function BookDetailDialog({ book, onClose }: BookDetailDialogProps) {
  const { t } = useTranslation()
  const updateBookMetadata = useLibraryStore((s) => s.updateBookMetadata)

  const [title, setTitle] = useState(safeText(book.title))
  const [author, setAuthor] = useState(book.author ?? '')
  const [language, setLanguage] = useState(book.language ?? '')
  const [publisher, setPublisher] = useState(book.publisher ?? '')
  const [description, setDescription] = useState(book.description ?? '')
  const [tags, setTags] = useState<string[]>(Array.isArray(book.tags) ? book.tags : [])
  const [tagInput, setTagInput] = useState('')
  const [seriesName, setSeriesName] = useState(book.series_name ?? '')
  const [seriesIndex, setSeriesIndex] = useState(
    typeof book.series_index === 'number' ? String(book.series_index) : '0',
  )
  const [rating, setRating] = useState(typeof book.rating === 'number' ? book.rating : 0)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const addTag = (raw: string) => {
    const text = raw.trim().replace(/,$/, '').replace(/，$/, '').trim()
    if (!text) return
    if (tags.includes(text)) {
      setTagInput('')
      return
    }
    if (tags.length >= 32) {
      setError(t('最多 32 个标签'))
      return
    }
    setTags([...tags, text])
    setTagInput('')
    setError(null)
  }

  const removeTag = (tag: string) => setTags(tags.filter((item) => item !== tag))

  const handleSave = async () => {
    const trimmedTitle = title.trim()
    if (!trimmedTitle) {
      setError(t('书名不能为空'))
      return
    }
    const indexValue = Number.parseFloat(seriesIndex)
    const safeIndex = Number.isFinite(indexValue) && indexValue > 0 ? indexValue : 0
    setSaving(true)
    setError(null)
    const updated = await updateBookMetadata(book.id, {
      title: trimmedTitle,
      author: author.trim() || null,
      language: language.trim() || null,
      publisher: publisher.trim() || null,
      description: description.trim() || null,
      tags,
      series_name: seriesName.trim(),
      series_index: safeIndex,
      rating,
    })
    setSaving(false)
    if (!updated) {
      setError(t('保存失败，请检查输入后重试'))
      return
    }
    onClose()
  }

  return createPortal(
    <div
      className="fixed inset-0 z-[9999] flex items-center justify-center animate-fade-in"
      onClick={onClose}
      role="dialog"
      aria-modal="true"
      data-testid="book-detail-dialog"
    >
      <div className="m3-dialog-backdrop absolute inset-0" />
      <div
        className="m3-dialog relative p-6 max-w-2xl w-full mx-4 animate-scale-in max-h-[88vh] flex flex-col"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between mb-4">
          <h3 className="m3-title-large">{t('编辑书籍信息')}</h3>
          <button onClick={onClose} aria-label={t('关闭')} data-testid="metadata-cancel"
            className="m3-icon-button">
            <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" /></svg>
          </button>
        </div>

        <div className="overflow-y-auto space-y-4 pr-1">
          <Field label={t('书名')}>
            <input type="text" value={title} onChange={(e) => setTitle(e.target.value)}
              className="m3-text-field w-full" data-testid="metadata-title" autoFocus />
          </Field>

          <div className="grid grid-cols-2 gap-3">
            <Field label={t('作者')}>
              <input type="text" value={author} onChange={(e) => setAuthor(e.target.value)}
                className="m3-text-field w-full" data-testid="metadata-author" />
            </Field>
            <Field label={t('语言')}>
              <input type="text" value={language} onChange={(e) => setLanguage(e.target.value)}
                className="m3-text-field w-full" data-testid="metadata-language" placeholder="zh / en" />
            </Field>
          </div>

          <Field label={t('出版社')}>
            <input type="text" value={publisher} onChange={(e) => setPublisher(e.target.value)}
              className="m3-text-field w-full" data-testid="metadata-publisher" />
          </Field>

          <Field label={t('简介')}>
            <textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={3}
              className="m3-text-field w-full resize-y" data-testid="metadata-description" />
          </Field>

          <Field label={t('标签')}>
            <div className="flex flex-wrap items-center gap-1.5 rounded-lg px-2 py-1.5">
              {tags.map((tag) => (
                <span key={tag} className="inline-flex items-center gap-1 text-xs px-2 py-0.5 rounded-full"
                  style={{ background: 'var(--md-sys-color-primary-container)', color: 'var(--md-sys-color-on-primary-container)' }} data-testid="metadata-tag">
                  {tag}
                  <button type="button" onClick={() => removeTag(tag)} aria-label={t('删除')}
                    className="opacity-60 hover:opacity-100" style={{ color: 'var(--text-tertiary)' }}>
                    <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" /></svg>
                  </button>
                </span>
              ))}
              <input
                type="text"
                value={tagInput}
                placeholder={tags.length === 0 ? t('添加标签（回车或逗号）') : ''}
                onChange={(e) => setTagInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ',' || e.key === '，') {
                    e.preventDefault()
                    addTag(tagInput)
                  } else if (e.key === 'Backspace' && !tagInput && tags.length > 0) {
                    setTags(tags.slice(0, -1))
                  }
                }}
                onBlur={() => addTag(tagInput)}
                className="flex-1 min-w-[8rem] bg-transparent outline-none text-sm py-0.5"
                style={{ color: 'var(--md-sys-color-on-surface)' }}
                data-testid="metadata-tags-input"
              />
            </div>
          </Field>

          <div className="grid grid-cols-2 gap-3">
            <Field label={t('系列')}>
              <input type="text" value={seriesName} onChange={(e) => setSeriesName(e.target.value)}
                className="m3-text-field w-full" data-testid="metadata-series" placeholder={t('系列名称')} />
            </Field>
            <Field label={t('系列序号')}>
              <input type="number" min="0" step="0.1" value={seriesIndex}
                onChange={(e) => setSeriesIndex(e.target.value)}
                className="m3-text-field w-full" data-testid="metadata-series-index" />
            </Field>
          </div>

          <Field label={t('评分')}>
            <div className="flex items-center gap-1" data-testid="metadata-rating">
              {[1, 2, 3, 4, 5].map((star) => (
                <button
                  key={star}
                  type="button"
                  onClick={() => setRating(star === rating ? 0 : star)}
                  aria-label={t('评分 ≥ {{n}} 星', { n: star })}
                  data-testid={'metadata-rating-' + star}
                  style={{ color: 'var(--color-amber)' }}
                >
                  <svg className="w-6 h-6" viewBox="0 0 24 24" fill={star <= rating ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth={1.6}>
                    <path strokeLinecap="round" strokeLinejoin="round" d="M11.48 3.5a.562.562 0 011.04 0l2.1 4.6 5.03.5a.563.563 0 01.31.98l-3.76 3.35 1.06 4.94a.562.562 0 01-.83.6L12 16.6l-4.43 2.42a.562.562 0 01-.83-.6l1.06-4.94-3.76-3.35a.563.563 0 01.31-.98l5.03-.5 2.1-4.6z" />
                  </svg>
                </button>
              ))}
              <span className="text-xs ml-2" style={{ color: 'var(--text-tertiary)' }}>
                {rating > 0 ? t('{{n}} 星', { n: rating }) : t('未评分')}
              </span>
              {rating > 0 && (
                <button type="button" onClick={() => setRating(0)} className="text-xs ml-2 underline"
                  style={{ color: 'var(--text-tertiary)' }} data-testid="metadata-rating-clear">
                  {t('清除评分')}
                </button>
              )}
            </div>
          </Field>

          <div className="pt-2 mt-1 border-t text-xs space-y-1" style={{ borderColor: 'var(--acrylic-border)', color: 'var(--text-tertiary)' }}>
            <p className="font-medium" style={{ color: 'var(--text-secondary)' }}>{t('文件信息')}</p>
            <p className="break-all">{t('格式')}: {book.format.toUpperCase()} · {t('文件大小')}: {formatFileSize(book.file_size)}</p>
            <p className="break-all">{t('文件路径')}: {book.file_path}</p>
            <p>{t('添加时间')}: {formatDate(book.added_at)} · {t('最后阅读')}: {formatDate(book.last_opened || '')}</p>
          </div>
        </div>

        {error && (
          <p className="m3-body-small mt-3 px-2 py-1.5 rounded" style={{ background: 'var(--md-sys-color-error-container)', color: 'var(--md-sys-color-error)' }} data-testid="metadata-error">
            {error}
          </p>
        )}

        <div className="mt-4 pt-3 border-t flex justify-end gap-2" style={{ borderColor: 'var(--acrylic-border)' }}>
          <button onClick={onClose} className="m3-outlined-button" disabled={saving}>{t('取消')}</button>
          <button onClick={() => void handleSave()} className="m3-filled-button" disabled={saving} data-testid="metadata-save">
            {saving ? t('保存中…') : t('保存')}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  )
}
