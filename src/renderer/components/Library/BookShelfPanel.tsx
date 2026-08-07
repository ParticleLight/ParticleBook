import { useState } from 'react'
import { useLibraryStore } from '../../stores/libraryStore'
import { useTranslation } from 'react-i18next'

interface BookShelfPanelProps { onOpenBookSource?: () => void; onOpenZLibrary?: () => void; onAddFromAll?: () => void }

export function BookShelfPanel({ onOpenBookSource, onOpenZLibrary, onAddFromAll }: BookShelfPanelProps) {
  const bookshelves = useLibraryStore((s) => s.bookshelves)
  const activeShelfId = useLibraryStore((s) => s.activeShelfId)
  const setActiveShelf = useLibraryStore((s) => s.setActiveShelf)
  const createBookshelf = useLibraryStore((s) => s.createBookshelf)
  const deleteBookshelf = useLibraryStore((s) => s.deleteBookshelf)
  const renameBookshelf = useLibraryStore((s) => s.renameBookshelf)
  const [newName, setNewName] = useState('')
  const [showDialog, setShowDialog] = useState(false)
  const [editingId, setEditingId] = useState<number | null>(null)
  const [editName, setEditName] = useState('')
  const [contextMenu, setContextMenu] = useState<{ id: number; x: number; y: number } | null>(null)
  const { t } = useTranslation()

  const handleCreate = () => { if (newName.trim()) { createBookshelf(newName.trim()); setNewName(''); setShowDialog(false) } }
  const handleRename = () => { if (editingId !== null && editName.trim()) { renameBookshelf(editingId, editName.trim()); setEditingId(null); setEditName('') } }

  const isActive = (id: number | null) => activeShelfId === id

  const navItem = (label: string, icon: React.ReactNode, active: boolean, onClick: () => void, onContextMenu?: (e: React.MouseEvent) => void) => (
    <button onClick={onClick} onContextMenu={onContextMenu}
      className="w-full text-left px-3 py-2 rounded-md text-sm flex items-center gap-3 transition-all duration-150 relative group"
      style={{ color: active ? 'var(--text-primary)' : 'var(--text-secondary)', background: active ? 'var(--surface-active)' : 'transparent', fontWeight: active ? 600 : 400 }}
      onMouseEnter={(e) => { if (!active) { e.currentTarget.style.background = 'var(--surface-hover)'; e.currentTarget.style.color = 'var(--text-primary)' } }}
      onMouseLeave={(e) => { if (!active) { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = 'var(--text-secondary)' } }}>
      {active && <div className="absolute left-0 top-1.5 bottom-1.5 w-0.5 rounded-full" style={{ background: 'var(--accent)' }} />}
      <span className="flex-shrink-0 w-5 h-5 flex items-center justify-center">{icon}</span>
      <span className="truncate flex-1">{label}</span>
    </button>
  )

  return (
    <div className="flex flex-col h-full">
      {/* Header */}
      <div className="px-4 py-2.5 flex items-center justify-between">
        <h3 className="text-xs font-semibold uppercase tracking-wider" style={{ color: 'var(--text-tertiary)' }}>{t('书柜')}</h3>
        <div className="flex items-center gap-1">
          {activeShelfId != null && onAddFromAll && (
            <button onClick={onAddFromAll} aria-label={t('从全部添加')} title={t('从全部添加')}
              className="px-1.5 py-0.5 text-[11px] rounded-md transition-all duration-150"
              style={{ color: 'var(--accent)', background: 'var(--color-indigo-bg)' }}>
              <svg className="w-3 h-3 inline mr-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" /></svg>
              {t('从全部添加')}
            </button>
          )}
          <button onClick={() => setShowDialog(true)} aria-label={t('新建书柜')}
            className="p-0.5 rounded-md transition-all duration-150"
            style={{ color: 'var(--text-tertiary)' }}
            onMouseEnter={(e) => { e.currentTarget.style.color = 'var(--text-primary)'; e.currentTarget.style.background = 'var(--surface-hover)' }}
            onMouseLeave={(e) => { e.currentTarget.style.color = 'var(--text-tertiary)'; e.currentTarget.style.background = 'transparent' }}>
            <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" /></svg>
          </button>
        </div>
      </div>
      <div className="flex-1 overflow-y-auto px-3 space-y-0.5">
        {navItem(t('全部书籍'),
          <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253" /></svg>,
          isActive(null), () => setActiveShelf(null))}

        {bookshelves.map((shelf) => (
          <div key={shelf.id}>
            {editingId === shelf.id ? (
              <div className="flex gap-1 px-3 py-1">
                <input autoFocus value={editName} onChange={(e) => setEditName(e.target.value)}
                  onKeyDown={(e) => { if (e.key === 'Enter') handleRename(); if (e.key === 'Escape') setEditingId(null) }}
                  onBlur={handleRename}
                  className="flex-1 px-2 py-1 text-sm rounded-md" style={{ background: 'var(--bg-tertiary)', color: 'var(--text-primary)', border: '1px solid var(--border)' }} />
              </div>
            ) : (
              navItem(shelf.name,
                <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z" /></svg>,
                isActive(shelf.id), () => setActiveShelf(shelf.id),
                (e) => { e.preventDefault(); setContextMenu({ id: shelf.id, x: e.clientX, y: e.clientY }) })
            )}
          </div>
        ))}
      </div>

      {/* Bottom actions */}
      <div className="px-3 py-2 space-y-0.5" style={{ borderTop: '1px solid var(--border)' }}>
        {onOpenZLibrary && (
          <button onClick={onOpenZLibrary} className="w-full text-left px-3 py-2 rounded-md text-sm flex items-center gap-3 transition-all duration-150" style={{ color: 'var(--text-tertiary)' }}
            onMouseEnter={(e) => { e.currentTarget.style.color = 'var(--text-primary)'; e.currentTarget.style.background = 'var(--surface-hover)' }}
            onMouseLeave={(e) => { e.currentTarget.style.color = 'var(--text-tertiary)'; e.currentTarget.style.background = 'transparent' }}>
            <svg className="w-4 h-4 flex-shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3.055 11H5a2 2 0 012 2v1a2 2 0 002 2 2 2 0 012 2v2.945M8 3.935V5.5A2.5 2.5 0 0010.5 8h.5a2 2 0 012 2 2 2 0 104 0 2 2 0 012-2h1.064M15 20.488V18a2 2 0 012-2h3.064M21 12a9 9 0 11-18 0 9 9 0 0118 0z" /></svg>
            Z-Library
          </button>
        )}
        {onOpenBookSource && (
          <button onClick={onOpenBookSource} className="w-full text-left px-3 py-2 rounded-md text-sm flex items-center gap-3 transition-all duration-150" style={{ color: 'var(--text-tertiary)' }}
            onMouseEnter={(e) => { e.currentTarget.style.color = 'var(--text-primary)'; e.currentTarget.style.background = 'var(--surface-hover)' }}
            onMouseLeave={(e) => { e.currentTarget.style.color = 'var(--text-tertiary)'; e.currentTarget.style.background = 'transparent' }}>
            <svg className="w-4 h-4 flex-shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 12a9 9 0 01-9 9m9-9a9 9 0 00-9-9m9 9H3m9 9a9 9 0 01-9-9m9 9c1.657 0 3-4.03 3-9s-1.343-9-3-9m0 18c-1.657 0-3-4.03-3-9s1.343-9 3-9m-9 9a9 9 0 019-9" /></svg>
            {t('书源管理')}
          </button>
        )}

        {/* New shelf button */}
        <button onClick={() => setShowDialog(true)} className="w-full text-left px-3 py-2 rounded-md text-sm flex items-center gap-3 transition-all duration-150" style={{ color: 'var(--text-tertiary)' }}
          onMouseEnter={(e) => { e.currentTarget.style.color = 'var(--text-primary)'; e.currentTarget.style.background = 'var(--surface-hover)' }}
          onMouseLeave={(e) => { e.currentTarget.style.color = 'var(--text-tertiary)'; e.currentTarget.style.background = 'transparent' }}>
          <svg className="w-4 h-4 flex-shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" /></svg>
          {t('新建书柜')}
        </button>
      </div>

      {/* Context menu */}
      {contextMenu && (
        <div className="fixed inset-0 z-50" onClick={() => setContextMenu(null)}>
          <div className="absolute rounded-lg overflow-hidden shadow-win-lg animate-scale-in" style={{ left: contextMenu.x, top: contextMenu.y, background: 'var(--acrylic-bg)', backdropFilter: 'blur(24px)', border: '1px solid var(--acrylic-border)' }}>
            <button onClick={() => { setEditingId(contextMenu.id); setEditName(bookshelves.find((s) => s.id === contextMenu.id)?.name || ''); setContextMenu(null) }}
              className="w-full text-left px-4 py-2 text-sm transition-colors" style={{ color: 'var(--text-primary)' }}
              onMouseEnter={(e) => e.currentTarget.style.background = 'var(--surface-hover)'} onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}>{t('重命名')}</button>
            <button onClick={() => { deleteBookshelf(contextMenu.id); setContextMenu(null) }}
              className="w-full text-left px-4 py-2 text-sm transition-colors" style={{ color: 'var(--color-red)' }}
              onMouseEnter={(e) => e.currentTarget.style.background = 'var(--color-red-bg)'} onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}>{t('删除')}</button>
          </div>
        </div>
      )}

      {/* Create dialog */}
      {showDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center animate-fade-in" onClick={() => { setShowDialog(false); setNewName('') }}>
          <div className="absolute inset-0" style={{ background: 'rgba(0,0,0,0.45)' }} />
          <div className="relative w-80 rounded-xl shadow-win-lg p-6 animate-scale-in" style={{ background: 'var(--acrylic-bg)', backdropFilter: 'blur(24px)', border: '1px solid var(--acrylic-border)' }}>
            <h3 className="text-base font-semibold mb-4" style={{ color: 'var(--text-primary)' }}>{t('新建书柜')}</h3>
            <input autoFocus value={newName} onChange={(e) => setNewName(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') handleCreate(); if (e.key === 'Escape') { setShowDialog(false); setNewName('') } }}
              placeholder={t('请输入书柜名称...')} className="w-full input mb-4" />
            <div className="flex justify-end gap-2">
              <button onClick={() => { setShowDialog(false); setNewName('') }} className="btn-secondary">{t('取消')}</button>
              <button onClick={handleCreate} className="btn-primary">{t('创建')}</button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
