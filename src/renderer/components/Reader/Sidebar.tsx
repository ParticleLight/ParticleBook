import { useState, useRef, useEffect, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import i18n from '../../i18n'
import { useReaderStore } from '../../stores/readerStore'
import { ConfirmDialog } from '../UI/ConfirmDialog'

interface SidebarProps {
  bookId: number
  onClose: () => void
}

/** 待确认的删除（review U4：笔记/高亮/标注是用户手写内容，误点一次就永久丢失） */
type PendingDelete =
  | { kind: 'bookmark'; id: number }
  | { kind: 'highlight'; id: number }
  | { kind: 'note'; id: number }

export function Sidebar({ bookId, onClose }: SidebarProps) {
  const { t } = useTranslation()
  const {
    sidebarTab, setSidebarTab, tableOfContents,
    bookmarks, highlights, notes,
    removeBookmark, updateBookmark, removeHighlight, removeNote,
    addNote, progress, navigateTo, setShowSidebar,
  } = useReaderStore()
  const [newNote, setNewNote] = useState('')
  const [editingBookmarkId, setEditingBookmarkId] = useState<number | null>(null)
  const [editingBookmarkTitle, setEditingBookmarkTitle] = useState('')
  // review U4：删除书签/高亮/笔记先确认（删书、删书柜、清空书源都有确认，只有这里没有）
  const [pendingDelete, setPendingDelete] = useState<PendingDelete | null>(null)

  const handleSaveBookmark = async () => {
    const id = editingBookmarkId
    const title = editingBookmarkTitle.trim()
    setEditingBookmarkId(null)
    if (id !== null && title) {
      const bm = bookmarks.find((b) => b.id === id)
      if (bm && title !== bm.title) {
        await updateBookmark(id, title)
      }
    }
  }

  const tabs = useMemo(() => [
    { id: 'toc' as const, label: t('目录'), icon: 'M4 6h16M4 10h16M4 14h16M4 18h16' },
    { id: 'bookmarks' as const, label: t('书签'), icon: 'M5 5a2 2 0 012-2h10a2 2 0 012 2v16l-7-3.5L5 21V5z' },
    { id: 'highlights' as const, label: t('高亮'), icon: 'M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z' },
    { id: 'notes' as const, label: t('笔记'), icon: 'M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z' },
  ], [t])

  return (
    <div className="h-full flex flex-col">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3 border-b border-[var(--reader-border)]">
        {/* review U9：补 WAI-ARIA tabs 语义 + ←/→ 切换（旧实现只有视觉选中态） */}
        <div
          className="flex gap-1"
          role="tablist"
          aria-label={t('侧栏')}
          onKeyDown={(e) => {
            if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return
            e.preventDefault()
            const index = tabs.findIndex((tab) => tab.id === sidebarTab)
            const step = e.key === 'ArrowRight' ? 1 : -1
            const next = tabs[(index + step + tabs.length) % tabs.length]
            if (!next) return
            setSidebarTab(next.id)
            const el = e.currentTarget.querySelector<HTMLElement>(`[data-sidebar-tab="${next.id}"]`)
            el?.focus()
          }}
        >
          {tabs.map((tab) => (
            <button
              key={tab.id}
              id={`pb-sidebar-tab-${tab.id}`}
              role="tab"
              aria-selected={sidebarTab === tab.id}
              aria-controls="pb-sidebar-panel"
              tabIndex={sidebarTab === tab.id ? 0 : -1}
              data-sidebar-tab={tab.id}
              onClick={() => setSidebarTab(tab.id)}
              className={`p-2 rounded-lg text-xs font-medium transition-colors ${
                sidebarTab === tab.id
                  ? 'bg-[var(--reader-accent)] text-white'
                  : 'text-[var(--reader-text)] opacity-50 hover:opacity-80 hover:bg-[var(--reader-border)]'
              }`}
              title={tab.label}
              aria-label={tab.label}
            >
              <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" aria-hidden="true">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d={tab.icon} />
              </svg>
            </button>
          ))}
        </div>
        {/* U8：纯图标按钮要有可访问名 */}
        <button
          onClick={() => setShowSidebar(false)}
          className="p-1 text-[var(--reader-text)] opacity-50 hover:opacity-80"
          aria-label={t('关闭')}
          title={t('关闭')}
        >
          <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      {/* Content */}
      <div
        className="flex-1 overflow-y-auto p-3"
        id="pb-sidebar-panel"
        role="tabpanel"
        aria-labelledby={`pb-sidebar-tab-${sidebarTab}`}
      >
        {sidebarTab === 'toc' && (
          <div className="space-y-1">
            {tableOfContents.length === 0 ? (
              <p className="text-sm text-[var(--reader-text)] opacity-50 text-center py-4">{t('无目录信息')}</p>
            ) : (
              tableOfContents.map((item: any, i: number) => (
                <button
                  key={i}
                  onClick={() => {
                    if (item.href) {
                      navigateTo({ cfi: item.href, page: item.page })
                    } else if (item.id) {
                      navigateTo({ cfi: item.id, page: item.page })
                    } else if (item.page !== undefined) {
                      navigateTo({ page: item.page })
                    }
                  }}
                  className="w-full text-left px-3 py-2 text-sm rounded-lg hover:bg-[var(--reader-border)] text-[var(--reader-text)] opacity-80 hover:opacity-100 transition-colors"
                  style={{ paddingLeft: `${(item.level || 1) * 12}px` }}
                >
                  {item.label || item.title || t('章节 {{n}}', { n: i + 1 })}
                </button>
              ))
            )}
          </div>
        )}

        {sidebarTab === 'bookmarks' && (
          <div className="space-y-2">
            {bookmarks.length === 0 ? (
              <p className="text-sm text-[var(--reader-text)] opacity-50 text-center py-4">{t('暂无书签')}</p>
            ) : (
              bookmarks.map((bm) => (
                <div
                  key={bm.id}
                  role="button"
                  tabIndex={0}
                  aria-label={bm.title || t('书签')}
                  className="group flex items-start gap-2 p-2 rounded-lg hover:bg-[var(--reader-border)] cursor-pointer"
                  onClick={() => { if (editingBookmarkId !== bm.id) navigateTo({ page: bm.page, cfi: bm.cfi }) }}
                  // review U3：列表行要能用键盘跳到位置（Enter/Space）；行内有输入框/按钮时
                  // 不抢它们的键（e.target !== e.currentTarget）
                  onKeyDown={(e) => {
                    if (e.target !== e.currentTarget) return
                    if (e.key !== 'Enter' && e.key !== ' ') return
                    e.preventDefault()
                    if (editingBookmarkId !== bm.id) navigateTo({ page: bm.page, cfi: bm.cfi })
                  }}
                >
                  <svg className="w-4 h-4 mt-0.5 flex-shrink-0" fill="currentColor" viewBox="0 0 20 20" style={{ color: 'var(--color-yellow)' }}>
                    <path d="M5 4a2 2 0 012-2h6a2 2 0 012 2v14l-5-2.5L5 18V4z" />
                  </svg>
                  <div className="flex-1 min-w-0">
                    {editingBookmarkId === bm.id ? (
                      <input
                        autoFocus
                        value={editingBookmarkTitle}
                        onChange={(e) => setEditingBookmarkTitle(e.target.value)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter') handleSaveBookmark()
                          if (e.key === 'Escape') setEditingBookmarkId(null)
                        }}
                        onBlur={handleSaveBookmark}
                        onClick={(e) => e.stopPropagation()}
                        className="m3-text-field is-dense w-full"
                      />
                    ) : (
                      <>
                        <p
                          className="text-sm text-[var(--reader-text)] opacity-80 truncate cursor-pointer hover:opacity-100"
                          onDoubleClick={(e) => {
                            e.stopPropagation()
                            setEditingBookmarkId(bm.id)
                            setEditingBookmarkTitle(bm.title || '')
                          }}
                          title={t('双击重命名')}
                        >
                          {bm.title || t('书签')}
                        </p>
                        <div className="flex items-center justify-between">
                          <p className="text-xs text-[var(--reader-text)] opacity-40">{new Date(bm.created_at).toLocaleString(i18n.language)}</p>
                          {bm.progress != null && (
                            <p className="text-xs text-[var(--reader-text)] opacity-40">{bm.progress.toFixed(1)}%</p>
                          )}
                        </div>
                      </>
                    )}
                  </div>
                  <button
                    onClick={(e) => {
                      e.stopPropagation()
                      setEditingBookmarkId(bm.id)
                      setEditingBookmarkTitle(bm.title || '')
                    }}
                    className="opacity-0 group-hover:opacity-100 p-1 text-[var(--reader-text)] opacity-40 hover:opacity-80"
                    title={t('重命名')}
                    aria-label={t('重命名')}
                  >
                    <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z" />
                    </svg>
                  </button>
                  <button
                    onClick={(e) => { e.stopPropagation(); setPendingDelete({ kind: 'bookmark', id: bm.id }) }}
                    className="opacity-0 group-hover:opacity-100 p-1 text-[var(--reader-text)] opacity-40 hover:opacity-80"
                    title={t('删除')}
                    aria-label={t('删除书签')}
                  >
                    <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                    </svg>
                  </button>
                </div>
              ))
            )}
          </div>
        )}

        {sidebarTab === 'highlights' && (
          <div className="space-y-2">
            {highlights.length === 0 ? (
              <p className="text-sm text-[var(--reader-text)] opacity-50 text-center py-4">{t('暂无高亮')}</p>
            ) : (
              highlights.map((hl) => (
                <div
                  key={hl.id}
                  role="button"
                  tabIndex={0}
                  aria-label={hl.text}
                  className="group p-2 rounded-lg hover:bg-[var(--reader-border)] cursor-pointer"
                  onClick={() => navigateTo({ page: hl.page, cfi: hl.cfi })}
                  onKeyDown={(e) => {
                    if (e.target !== e.currentTarget) return
                    if (e.key !== 'Enter' && e.key !== ' ') return
                    e.preventDefault()
                    navigateTo({ page: hl.page, cfi: hl.cfi })
                  }}
                >
                  <div className="flex items-start gap-2">
                    <div className="w-3 h-3 rounded-full mt-1 flex-shrink-0" style={{ backgroundColor: hl.color }} />
                    <div className="flex-1 min-w-0">
                      <p className="text-sm text-[var(--reader-text)] opacity-80 line-clamp-3">{hl.text}</p>
                      {hl.note && <p className="text-xs text-[var(--reader-text)] opacity-40 mt-1">{hl.note}</p>}
                    </div>
                    <button
                      onClick={(e) => { e.stopPropagation(); setPendingDelete({ kind: 'highlight', id: hl.id }) }}
                      className="opacity-0 group-hover:opacity-100 p-1 text-[var(--reader-text)] opacity-40 hover:opacity-80"
                      title={t('删除')}
                      aria-label={t('删除高亮')}
                    >
                      <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                      </svg>
                    </button>
                  </div>
                </div>
              ))
            )}
          </div>
        )}

        {sidebarTab === 'notes' && (
          <div className="space-y-2">
            {/* Add note */}
            <div className="flex gap-2 mb-3">
              <input
                type="text"
                value={newNote}
                onChange={(e) => setNewNote(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && newNote.trim()) {
                    addNote({ book_id: bookId, page: progress.page, cfi: progress.cfi, content: newNote.trim() })
                    setNewNote('')
                  }
                }}
                placeholder={t('添加笔记...')}
                className="m3-text-field is-dense flex-1"
              />
              <button
                onClick={() => {
                  if (newNote.trim()) {
                    addNote({ book_id: bookId, page: progress.page, cfi: progress.cfi, content: newNote.trim() })
                    setNewNote('')
                  }
                }}
                className="m3-filled-button is-compact"
              >
                {t('添加')}
              </button>
            </div>
            {notes.length === 0 ? (
              <p className="text-sm text-[var(--reader-text)] opacity-50 text-center py-4">{t('暂无笔记')}</p>
            ) : (
              notes.map((note) => (
                <div
                  key={note.id}
                  role="button"
                  tabIndex={0}
                  aria-label={note.content}
                  className="group p-3 rounded-lg hover:bg-[var(--reader-border)] cursor-pointer"
                  onClick={() => navigateTo({ page: note.page, cfi: note.cfi })}
                  onKeyDown={(e) => {
                    if (e.target !== e.currentTarget) return
                    if (e.key !== 'Enter' && e.key !== ' ') return
                    e.preventDefault()
                    navigateTo({ page: note.page, cfi: note.cfi })
                  }}
                >
                  <p className="text-sm text-[var(--reader-text)] opacity-80 whitespace-pre-wrap">{note.content}</p>
                  <div className="flex items-center justify-between mt-2">
                    <p className="text-xs text-[var(--reader-text)] opacity-40">{new Date(note.updated_at).toLocaleString(i18n.language)}</p>
                    <button
                      // 所在行容器有"跳到该笔记位置"的 onClick（书签与高亮行都写了
                      // stopPropagation，只有这里漏了）→ 点删除会顺带把阅读位置跳过去。
                      onClick={(e) => { e.stopPropagation(); setPendingDelete({ kind: 'note', id: note.id }) }}
                      className="opacity-0 group-hover:opacity-100 p-1 text-[var(--reader-text)] opacity-40 hover:opacity-80"
                      title={t('删除')}
                      aria-label={t('删除笔记')}
                    >
                      <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                      </svg>
                    </button>
                  </div>
                </div>
              ))
            )}
          </div>
        )}
      </div>

      {/* review U4：删除笔记/高亮/书签的二次确认（复用 UI/ConfirmDialog） */}
      {pendingDelete && (
        <ConfirmDialog
          title={t('删除{{type}}', {
            type: pendingDelete.kind === 'bookmark' ? t('书签') : pendingDelete.kind === 'highlight' ? t('高亮') : t('笔记'),
          })}
          message={
            pendingDelete.kind === 'note'
              ? t('确定删除这条笔记吗？手写内容删除后无法恢复。')
              : pendingDelete.kind === 'highlight'
                ? t('确定删除这条高亮吗？删除后正文里的标记也会消失。')
                : t('确定删除这个书签吗？')
          }
          confirmText={t('删除')}
          cancelText={t('取消')}
          danger
          onCancel={() => setPendingDelete(null)}
          onConfirm={() => {
            const target = pendingDelete
            setPendingDelete(null)
            if (target.kind === 'bookmark') void removeBookmark(target.id)
            else if (target.kind === 'highlight') void removeHighlight(target.id)
            else void removeNote(target.id)
          }}
        />
      )}
    </div>
  )
}
