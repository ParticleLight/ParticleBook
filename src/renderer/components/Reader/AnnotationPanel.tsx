// ── PB-021-D：阅读器标注面板（annotations API 的 UI 层）──────────────
//
// 与旧 Sidebar 并存：旧侧栏继续读 readerStore 的 bookmarks/highlights/notes，
// 本面板读 annotationStore（统一 annotations 表）。C++ 写入双写两份，所以
// 「通过本面板新增/删除」会同步刷新旧侧栏（refreshLegacyViews），老 UI 不会
// 出现幽灵行；反向（旧侧栏写入）暂不回流 annotations，属分阶段迁移的已知缺口。
//
// 能力：
//   * 当前书的标注列表（全部 / 书签 / 高亮 / 笔记 过滤，按位置 / 时间排序）
//   * 显示引用 / 标题 / 笔记、位置提示与创建时间
//   * 点击行尝试跳转：优先用 legacyId 对应的旧表行（navigateTo 的既有路径最稳），
//     没有旧行时按 locator 映射（epub cfi / pdf-page / text 'toc-<offset>' /
//     html #fragment / scroll percent）
//   * 删除（同时删统一行与旧表行，再刷新旧侧栏）
//   * 在当前阅读进度添加书签 / 笔记
//   * 高亮创建不在本面板做：正文选区 → 高亮仍走各渲染器现有的高亮按钮
//     （EPUB/TXT 的选区到码点 offset 的映射由渲染器掌握，面板拿不到可靠数据，
//     硬做会把高亮落错位置 —— 这里只展示 / 删除已有高亮）。
//
// 性能：annotations 数组由 store 在变更时整体替换，组件直接订阅该数组 +
// useMemo 派生列表 / 计数，避免 zustand v5 选择器返回新数组导致的循环渲染。

import { useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import i18n from '../../i18n'
import { useAnnotationStore } from '../../stores/annotationStore'
import { useReaderStore, type ReadingProgress } from '../../stores/readerStore'
import {
  annotationText,
  countByType,
  filterAnnotations,
  sortAnnotations,
  type Annotation,
  type AnnotationSort,
  type AnnotationType,
  type AnnotationTypeFilter,
  type Locator,
} from '../../types/annotation'

interface AnnotationPanelProps {
  bookId: number
  format: string
  onClose: () => void
}

const TYPE_ICONS: Record<AnnotationType, string> = {
  bookmark: 'M5 5a2 2 0 012-2h10a2 2 0 012 2v16l-7-3.5L5 21V5z',
  highlight: 'M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z',
  note: 'M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z',
}

// 旧 i18n key（en-reader 已有对应英文）
const TYPE_KEYS: Record<AnnotationType, string> = {
  bookmark: '书签',
  highlight: '高亮',
  note: '笔记',
}

function clampPercent(value: number): number {
  if (!Number.isFinite(value)) return 0
  return Math.min(100, Math.max(0, value))
}

// 当前阅读进度 → 统一 Locator（用于「在当前位置添加」）。
// 语义按格式区分（与旧渲染层的写法一致）：
//   epub → cfi；pdf/cbz/cbr → page；txt/mobi/html/markdown/fb2 → 百分比。
function locatorFromProgress(format: string, progress: ReadingProgress): Locator {
  if (format === 'epub' && progress.cfi) {
    return { version: 1, kind: 'epub', cfi: progress.cfi }
  }
  if (['pdf', 'cbz', 'cbr'].includes(format)) {
    const page = typeof progress.page === 'number' && progress.page >= 1
      ? Math.round(progress.page)
      : 1
    return { version: 1, kind: 'pdf', page }
  }
  // 文本类：旧进度把百分比写在 page（TextRenderer）或 progress 里。
  const percent = clampPercent(
    typeof progress.page === 'number' && progress.page > 0
      ? progress.page
      : typeof progress.progress === 'number'
        ? progress.progress
        : 0,
  )
  return { version: 1, kind: 'scroll', percent }
}

function formatTime(iso: string | undefined): string {
  if (!iso) return ''
  const time = new Date(iso)
  if (Number.isNaN(time.getTime())) return ''
  return time.toLocaleString(i18n.language)
}

export function AnnotationPanel({ bookId, format, onClose }: AnnotationPanelProps) {
  const { t } = useTranslation()

  // 只订阅原子切片：数组引用在写入时整体替换，派生列表用 useMemo。
  const annotations = useAnnotationStore((s) => s.annotations)
  const loading = useAnnotationStore((s) => s.loading)
  const error = useAnnotationStore((s) => s.error)
  const load = useAnnotationStore((s) => s.load)
  const add = useAnnotationStore((s) => s.add)
  const remove = useAnnotationStore((s) => s.remove)

  const [typeFilter, setTypeFilter] = useState<AnnotationTypeFilter>('all')
  const [sort, setSort] = useState<AnnotationSort>('position')
  const [newNote, setNewNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [feedback, setFeedback] = useState<string | null>(null)
  const feedbackTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    void load(bookId)
    return () => {
      if (feedbackTimer.current) clearTimeout(feedbackTimer.current)
    }
  }, [bookId, load])

  const showFeedback = (text: string) => {
    setFeedback(text)
    if (feedbackTimer.current) clearTimeout(feedbackTimer.current)
    feedbackTimer.current = setTimeout(() => setFeedback(null), 2500)
  }

  const bookAnnotations = useMemo(
    () => filterAnnotations(annotations, { bookId }),
    [annotations, bookId],
  )
  const counts = useMemo(() => countByType(bookAnnotations), [bookAnnotations])
  const visible = useMemo(() => {
    const types = typeFilter === 'all' ? undefined : [typeFilter]
    return sortAnnotations(filterAnnotations(bookAnnotations, { types }), sort)
  }, [bookAnnotations, typeFilter, sort])

  // 旧侧栏数据刷新（C++ 已双写，重拉一次三个旧列表即可；不触碰旧 store 的其它状态）。
  const refreshLegacyViews = async () => {
    const reader = useReaderStore.getState()
    try {
      await Promise.all([
        reader.loadBookmarks(bookId),
        reader.loadHighlights(bookId),
        reader.loadNotes(bookId),
      ])
    } catch (e) {
      console.error('Failed to refresh legacy annotation lists:', e)
    }
  }

  // legacyId 对账：统一行对应的旧表行（旧渲染器的跳转/展示口径）。
  const lookupLegacyRow = (annotation: Annotation) => {
    if (!annotation.legacyId) return undefined
    const reader = useReaderStore.getState()
    const legacyType = annotation.legacyType ?? annotation.type
    if (legacyType === 'bookmark') {
      return reader.bookmarks.find((row) => row.id === annotation.legacyId)
    }
    if (legacyType === 'highlight') {
      return reader.highlights.find((row) => row.id === annotation.legacyId)
    }
    return reader.notes.find((row) => row.id === annotation.legacyId)
  }

  const jumpTo = (annotation: Annotation) => {
    const reader = useReaderStore.getState()
    const legacy = lookupLegacyRow(annotation)
    if (legacy) {
      reader.navigateTo({ cfi: legacy.cfi, page: legacy.page })
      onClose()
      return
    }
    // 没有旧行（例如旧行被删或迁移异常）：按 locator 直接映射到各渲染器认识的入口。
    const locator = annotation.locator
    if (locator.kind === 'epub' && locator.cfi) {
      reader.navigateTo({ cfi: locator.cfi })
      onClose()
      return
    }
    if (locator.kind === 'pdf' && typeof locator.page === 'number' && locator.page >= 1) {
      reader.navigateTo({ page: locator.page })
      onClose()
      return
    }
    if (locator.kind === 'text' && typeof locator.offset === 'number' && locator.offset >= 0) {
      // TextRenderer 的既有锚点格式：toc-<字符 offset>
      reader.navigateTo({ cfi: 'toc-' + locator.offset })
      onClose()
      return
    }
    if (locator.kind === 'scroll' && typeof locator.percent === 'number') {
      reader.seekTo(clampPercent(locator.percent))
      onClose()
      return
    }
    if (locator.kind === 'html' && locator.href) {
      const fragment = locator.href.split('#')[1]
      if (fragment) {
        reader.navigateTo({ cfi: fragment })
        onClose()
        return
      }
    }
    showFeedback(t('这个标注没有可用的跳转位置'))
  }

  const locatorHint = (locator: Locator): string => {
    switch (locator.kind) {
      case 'epub':
        return t('EPUB 位置')
      case 'pdf':
        return t('第 {{page}} 页', { page: locator.page ?? 1 })
      case 'text':
        return t('字符位置 {{offset}}', { offset: locator.offset ?? 0 })
      case 'html':
        return locator.href ?? t('EPUB 位置')
      case 'scroll':
        return t('位置 {{percent}}%', { percent: Math.round(clampPercent(locator.percent ?? 0)) })
      default:
        return ''
    }
  }

  const handleAddBookmark = async () => {
    if (busy) return
    setBusy(true)
    try {
      const locator = locatorFromProgress(format, useReaderStore.getState().progress)
      const title = t('书签{{n}}', { n: counts.bookmark + 1 })
      const created = await add({ bookId, type: 'bookmark', locator, title })
      if (created) {
        showFeedback(t('已添加书签'))
        await refreshLegacyViews()
      } else {
        showFeedback(t('添加失败，请重试'))
      }
    } finally {
      setBusy(false)
    }
  }

  const handleAddNote = async () => {
    const note = newNote.trim()
    if (!note || busy) return
    setBusy(true)
    try {
      const locator = locatorFromProgress(format, useReaderStore.getState().progress)
      const created = await add({ bookId, type: 'note', locator, note })
      if (created) {
        setNewNote('')
        showFeedback(t('已添加笔记'))
        await refreshLegacyViews()
      } else {
        showFeedback(t('添加失败，请重试'))
      }
    } finally {
      setBusy(false)
    }
  }

  const handleRemove = async (annotation: Annotation) => {
    if (busy) return
    setBusy(true)
    try {
      const ok = await remove(annotation.id)
      if (ok) {
        showFeedback(t('已删除标注'))
        await refreshLegacyViews()
      } else {
        showFeedback(t('删除失败，请重试'))
      }
    } finally {
      setBusy(false)
    }
  }

  const filters: { id: AnnotationTypeFilter; label: string }[] = [
    { id: 'all', label: t('全部') },
    { id: 'bookmark', label: t('书签') },
    { id: 'highlight', label: t('高亮') },
    { id: 'note', label: t('笔记') },
  ]

  return (
    <div className="h-full flex flex-col text-[var(--reader-text)]" data-testid="annotation-panel">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3 border-b border-[var(--reader-border)]">
        <div className="flex items-center gap-2">
          <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                  d="M7 8h10M7 12h6m-4 8l-4-4V6a2 2 0 012-2h12a2 2 0 012 2v8a2 2 0 01-2 2H9l-6 6z" />
          </svg>
          <h2 className="text-sm font-semibold">{t('标注')}</h2>
          <span className="text-xs opacity-50">
            {counts.bookmark + counts.highlight + counts.note}
          </span>
        </div>
        <button
          onClick={onClose}
          className="p-1 rounded opacity-50 hover:opacity-80"
          title={t('关闭')}
          aria-label={t('关闭')}
        >
          <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      {/* Filters + sort */}
      <div className="px-3 pt-3 flex items-center justify-between gap-2">
        <div className="flex gap-1 flex-wrap">
          {filters.map((item) => (
            <button
              key={item.id}
              onClick={() => setTypeFilter(item.id)}
              className={
                'px-2 py-1 rounded-lg text-xs transition-colors ' +
                (typeFilter === item.id
                  ? 'bg-[var(--reader-accent)] text-white'
                  : 'opacity-60 hover:opacity-90 hover:bg-[var(--reader-border)]')
              }
            >
              {item.label}
            </button>
          ))}
        </div>
        <select
          value={sort}
          onChange={(e) => setSort(e.target.value as AnnotationSort)}
          className="px-2 py-1 rounded-lg text-xs bg-[var(--reader-bg)] border border-[var(--reader-border)] focus:outline-none"
          title={t('排序')}
          aria-label={t('排序')}
        >
          <option value="position">{t('按位置')}</option>
          <option value="created">{t('按时间')}</option>
        </select>
      </div>

      {error && (
        <div className="mx-3 mt-3 px-3 py-2 rounded-lg text-xs break-words"
             style={{ background: 'var(--color-red-bg)', color: 'var(--color-red)' }}>
          {error}
        </div>
      )}
      {feedback && (
        <div className="mx-3 mt-3 px-3 py-2 rounded-lg text-xs"
             style={{ background: 'var(--reader-border)', opacity: 0.8 }}>
          {feedback}
        </div>
      )}

      {/* List */}
      <div className="flex-1 overflow-y-auto p-3 space-y-2">
        {loading && bookAnnotations.length === 0 ? (
          <p className="text-sm opacity-50 text-center py-6">{t('加载标注...')}</p>
        ) : visible.length === 0 ? (
          <p className="text-sm opacity-50 text-center py-6">{t('暂无标注')}</p>
        ) : (
          visible.map((annotation) => {
            const mainText = annotationText(annotation) || t('无内容')
            const notePreview =
              annotation.note && annotation.type !== 'note' ? annotation.note : ''
            return (
              <div
                key={annotation.id}
                className="group p-2 rounded-lg hover:bg-[var(--reader-border)] cursor-pointer"
                onClick={() => jumpTo(annotation)}
                data-testid="annotation-row"
                data-annotation-type={annotation.type}
              >
                <div className="flex items-start gap-2">
                  {annotation.type === 'highlight' ? (
                    <span
                      className="w-3 h-3 rounded-full mt-1 flex-shrink-0"
                      style={{ backgroundColor: annotation.color || 'var(--color-yellow)' }}
                    />
                  ) : (
                    <svg className="w-4 h-4 mt-0.5 flex-shrink-0 opacity-70" fill="none"
                         viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                            d={TYPE_ICONS[annotation.type]} />
                    </svg>
                  )}
                  <div className="flex-1 min-w-0">
                    <p className="text-sm opacity-85 line-clamp-3 whitespace-pre-wrap">{mainText}</p>
                    {notePreview && (
                      <p className="text-xs opacity-50 mt-1 line-clamp-2">{notePreview}</p>
                    )}
                    <div className="flex items-center gap-1.5 text-[11px] opacity-40 mt-1 flex-wrap">
                      <span>{t(TYPE_KEYS[annotation.type])}</span>
                      <span>·</span>
                      <span className="truncate max-w-[12rem]">{locatorHint(annotation.locator)}</span>
                      <span>·</span>
                      <span>{formatTime(annotation.createdAt)}</span>
                    </div>
                  </div>
                  <button
                    onClick={(e) => { e.stopPropagation(); void handleRemove(annotation) }}
                    className="opacity-0 group-hover:opacity-100 p-1 opacity-40 hover:opacity-80 flex-shrink-0"
                    title={t('删除')}
                    aria-label={t('删除')}
                    data-testid="annotation-delete"
                  >
                    <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                    </svg>
                  </button>
                </div>
              </div>
            )
          })
        )}
      </div>

      {/* Composer */}
      <div className="border-t border-[var(--reader-border)] p-3 space-y-2">
        <button
          onClick={() => void handleAddBookmark()}
          disabled={busy}
          className="w-full flex items-center justify-center gap-2 px-3 py-2 rounded-lg text-sm text-white disabled:opacity-50"
          style={{ backgroundColor: 'var(--reader-accent)' }}
          title={t('在当前位置添加书签')}
          data-testid="annotation-add-bookmark"
        >
          <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                  d="M5 5a2 2 0 012-2h10a2 2 0 012 2v16l-7-3.5L5 21V5z" />
          </svg>
          {t('在当前位置添加书签')}
        </button>

        <div className="flex gap-2">
          <textarea
            value={newNote}
            onChange={(e) => setNewNote(e.target.value)}
            onKeyDown={(e) => {
              if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
                e.preventDefault()
                void handleAddNote()
              }
            }}
            rows={2}
            placeholder={t('在当前位置添加笔记')}
            className="flex-1 px-3 py-2 bg-[var(--reader-bg)] border border-[var(--reader-border)] rounded-lg text-sm placeholder-gray-500 focus:outline-none focus:border-[var(--reader-accent)] resize-none text-[var(--reader-text)]"
            data-testid="annotation-note-input"
          />
          <button
            onClick={() => void handleAddNote()}
            disabled={busy || !newNote.trim()}
            className="px-3 py-2 rounded-lg text-sm text-white disabled:opacity-40 self-stretch"
            style={{ backgroundColor: 'var(--reader-accent)' }}
            data-testid="annotation-add-note"
          >
            {t('添加')}
          </button>
        </div>

        <p className="text-[11px] opacity-40 leading-snug">
          {t('高亮请在正文中选中文字后添加（沿用现有高亮按钮）')}
        </p>
      </div>
    </div>
  )
}
