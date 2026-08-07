import { useState, useRef, useEffect, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import i18n from '../../i18n'
import { useReaderStore } from '../../stores/readerStore'

interface SidebarProps {
  bookId: number
  onClose: () => void
}

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
        <div className="flex gap-1">
          {tabs.map((tab) => (
            <button
              key={tab.id}
              onClick={() => setSidebarTab(tab.id)}
              className={`p-2 rounded-lg text-xs font-medium transition-colors ${
                sidebarTab === tab.id
                  ? 'bg-[var(--reader-accent)] text-white'
                  : 'text-[var(--reader-text)] opacity-50 hover:opacity-80 hover:bg-[var(--reader-border)]'
              }`}
              title={tab.label}
            >
              <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d={tab.icon} />
              </svg>
            </button>
          ))}
        </div>
        <button onClick={() => setShowSidebar(false)} className="p-1 text-[var(--reader-text)] opacity-50 hover:opacity-80">
          <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto p-3">
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
                  className="group flex items-start gap-2 p-2 rounded-lg hover:bg-[var(--reader-border)] cursor-pointer"
                  onClick={() => { if (editingBookmarkId !== bm.id) navigateTo({ page: bm.page, cfi: bm.cfi }) }}
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
                        className="w-full px-1 py-0.5 text-sm bg-[var(--reader-bg)] border border-[var(--reader-border)] rounded text-[var(--reader-text)] focus:outline-none focus:border-[var(--reader-accent)]"
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
                  >
                    <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z" />
                    </svg>
                  </button>
                  <button
                    onClick={(e) => { e.stopPropagation(); removeBookmark(bm.id) }}
                    className="opacity-0 group-hover:opacity-100 p-1 text-[var(--reader-text)] opacity-40 hover:opacity-80"
                    title={t('删除')}
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
                <div key={hl.id} className="group p-2 rounded-lg hover:bg-[var(--reader-border)] cursor-pointer" onClick={() => navigateTo({ page: hl.page, cfi: hl.cfi })}>
                  <div className="flex items-start gap-2">
                    <div className="w-3 h-3 rounded-full mt-1 flex-shrink-0" style={{ backgroundColor: hl.color }} />
                    <div className="flex-1 min-w-0">
                      <p className="text-sm text-[var(--reader-text)] opacity-80 line-clamp-3">{hl.text}</p>
                      {hl.note && <p className="text-xs text-[var(--reader-text)] opacity-40 mt-1">{hl.note}</p>}
                    </div>
                    <button
                      onClick={(e) => { e.stopPropagation(); removeHighlight(hl.id) }}
                      className="opacity-0 group-hover:opacity-100 p-1 text-[var(--reader-text)] opacity-40 hover:opacity-80"
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
                className="flex-1 px-3 py-2 bg-[var(--reader-bg)] border border-[var(--reader-border)] rounded-lg text-sm text-[var(--reader-text)] placeholder-gray-500 focus:outline-none focus:border-[var(--reader-accent)]"
              />
              <button
                onClick={() => {
                  if (newNote.trim()) {
                    addNote({ book_id: bookId, page: progress.page, cfi: progress.cfi, content: newNote.trim() })
                    setNewNote('')
                  }
                }}
                className="px-3 py-2 text-white text-sm rounded-lg"
                style={{ backgroundColor: 'var(--reader-accent)' }}
              >
                {t('添加')}
              </button>
            </div>
            {notes.length === 0 ? (
              <p className="text-sm text-[var(--reader-text)] opacity-50 text-center py-4">{t('暂无笔记')}</p>
            ) : (
              notes.map((note) => (
                <div key={note.id} className="group p-3 rounded-lg hover:bg-[var(--reader-border)] cursor-pointer" onClick={() => navigateTo({ page: note.page, cfi: note.cfi })}>
                  <p className="text-sm text-[var(--reader-text)] opacity-80 whitespace-pre-wrap">{note.content}</p>
                  <div className="flex items-center justify-between mt-2">
                    <p className="text-xs text-[var(--reader-text)] opacity-40">{new Date(note.updated_at).toLocaleString(i18n.language)}</p>
                    <button
                      onClick={() => removeNote(note.id)}
                      className="opacity-0 group-hover:opacity-100 p-1 text-[var(--reader-text)] opacity-40 hover:opacity-80"
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
    </div>
  )
}
