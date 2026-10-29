import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useLibraryStore } from '../../stores/libraryStore'
import { useTranslation } from 'react-i18next'
import { ConfirmDialog } from '../UI/ConfirmDialog'
import { Modal } from '../UI/Modal'

interface BookShelfPanelProps {
  onOpenBookSource?: () => void
  onOpenZLibrary?: () => void
  onAddFromAll?: () => void
  // 选中某个书柜（含「全部书籍」）之后的回调。窄窗口下书柜列表挂在模态导航
  // 抽屉里，选中后要自动收起抽屉；expanded 档传的是 no-op，行为与改造前一致。
  onAfterSelect?: () => void
}

export function BookShelfPanel({ onOpenBookSource, onOpenZLibrary, onAddFromAll, onAfterSelect }: BookShelfPanelProps) {
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
  // 删除书柜是破坏性操作（C++ 会级联删掉整柜的分组记录，不可撤销），且"删除"紧贴在
  // "重命名"下方极易误点 —— 同应用删书都有确认，这里此前没有。
  const [pendingDeleteId, setPendingDeleteId] = useState<number | null>(null)
  const { t } = useTranslation()
  const createTitleId = useId()
  const contextMenuRef = useRef<HTMLDivElement | null>(null)
  // 触发右键菜单的那个书柜项：Esc 关闭后把焦点还回去（WAI-ARIA 的菜单模式）
  const contextMenuTriggerRef = useRef<HTMLElement | null>(null)

  // 菜单内的方向键导航（右键菜单里没有输入框，绑在菜单自己的 onKeyDown 上即可）
  const focusMenuItem = (step: 1 | -1 | 'first' | 'last') => {
    const items = Array.from(contextMenuRef.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? [])
    if (items.length === 0) return
    const current = items.indexOf(document.activeElement as HTMLElement)
    const next = step === 'first' ? 0
      : step === 'last' ? items.length - 1
        : current < 0 ? 0 : (current + step + items.length) % items.length
    items[next]?.focus()
  }

  // 视口夹边：在窗口右/下边缘右键时菜单不能溢出到窗口外。
  // 在 layout effect（首帧绘制前）量一次尺寸再纠正坐标，不会先闪一下再跳。
  useLayoutEffect(() => {
    const el = contextMenuRef.current
    if (!contextMenu || !el) return
    const margin = 8
    const left = Math.min(Math.max(contextMenu.x, margin), Math.max(margin, window.innerWidth - el.offsetWidth - margin))
    const top = Math.min(Math.max(contextMenu.y, margin), Math.max(margin, window.innerHeight - el.offsetHeight - margin))
    if (left !== contextMenu.x || top !== contextMenu.y) setContextMenu({ ...contextMenu, x: left, y: top })
  }, [contextMenu])

  // Esc / 键盘可达：打开时把焦点收进第一项；Esc 在捕获阶段吃掉这次按键，
  // 避免逐层关闭的语义（书库/阅读器的全局 Esc）也跟着响应。
  const contextMenuOpen = contextMenu !== null
  useEffect(() => {
    if (!contextMenuOpen) return
    contextMenuRef.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus()
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      e.stopPropagation()
      setContextMenu(null)
      const trigger = contextMenuTriggerRef.current
      window.requestAnimationFrame(() => { try { trigger?.focus() } catch { /* ignore */ } })
    }
    document.addEventListener('keydown', onKeyDown, true)
    return () => document.removeEventListener('keydown', onKeyDown, true)
  }, [contextMenuOpen])

  const closeCreateDialog = () => { setShowDialog(false); setNewName('') }
  const handleCreate = () => { if (newName.trim()) { createBookshelf(newName.trim()); setNewName(''); setShowDialog(false) } }
  const handleRename = () => { if (editingId !== null && editName.trim()) { renameBookshelf(editingId, editName.trim()); setEditingId(null); setEditName('') } }

  const isActive = (id: number | null) => activeShelfId === id

  const navItem = (label: string, icon: React.ReactNode, active: boolean, onClick: () => void, onContextMenu?: (e: React.MouseEvent) => void) => (
    <button onClick={onClick} onContextMenu={onContextMenu}
      className={'m3-nav-item ' + (active ? 'is-active' : '')}
      aria-current={active ? 'page' : undefined}
      aria-label={label}>
      <span className="flex-shrink-0 w-5 h-5 flex items-center justify-center">{icon}</span>
      <span className="truncate flex-1">{label}</span>
    </button>
  )

  return (
    <div className="flex flex-col h-full">
      {/* Header */}
      <div className="px-4 pt-3 pb-1 flex items-center justify-between">
        <h3 className="m3-section-title">{t('书柜')}</h3>
        <div className="flex items-center gap-1">
          {activeShelfId != null && onAddFromAll && (
            <button onClick={onAddFromAll} aria-label={t('从全部添加')} title={t('从全部添加')}
              className="m3-text-button !min-h-8 !px-2 !text-[12px]">
              <svg className="w-3 h-3 inline mr-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" /></svg>
              {t('从全部添加')}
            </button>
          )}
          <button onClick={() => setShowDialog(true)} aria-label={t('新建书柜')} title={t('新建书柜')}
            className="m3-icon-button !w-8 !h-8">
            <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" /></svg>
          </button>
        </div>
      </div>
      <div className="flex-1 overflow-y-auto px-3 py-1 space-y-1">
        {navItem(t('全部书籍'),
          <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 6.253v13m0-13C10.832 5.477 9.246 5 7.5 5S4.168 5.477 3 6.253v13C4.168 18.477 5.754 18 7.5 18s3.332.477 4.5 1.253m0-13C13.168 5.477 14.754 5 16.5 5c1.747 0 3.332.477 4.5 1.253v13C19.832 18.477 18.247 18 16.5 18c-1.746 0-3.332.477-4.5 1.253" /></svg>,
          isActive(null), () => { setActiveShelf(null); onAfterSelect?.() })}

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
                isActive(shelf.id), () => { setActiveShelf(shelf.id); onAfterSelect?.() },
                (e) => {
                  e.preventDefault()
                  contextMenuTriggerRef.current = e.currentTarget as HTMLElement
                  setContextMenu({ id: shelf.id, x: e.clientX, y: e.clientY })
                })
            )}
          </div>
        ))}
      </div>

      {/* Bottom actions */}
      <div className="px-3 py-2 space-y-1" style={{ borderTop: '1px solid var(--md-sys-color-outline-variant)' }}>
        {onOpenZLibrary && (
          <button onClick={onOpenZLibrary} className="m3-nav-item">
            <svg className="w-4 h-4 flex-shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3.055 11H5a2 2 0 012 2v1a2 2 0 002 2 2 2 0 012 2v2.945M8 3.935V5.5A2.5 2.5 0 0010.5 8h.5a2 2 0 012 2 2 2 0 104 0 2 2 0 012-2h1.064M15 20.488V18a2 2 0 012-2h3.064M21 12a9 9 0 11-18 0 9 9 0 0118 0z" /></svg>
            Z-Library
          </button>
        )}
        {onOpenBookSource && (
          <button onClick={onOpenBookSource} className="m3-nav-item">
            <svg className="w-4 h-4 flex-shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 12a9 9 0 01-9 9m9-9a9 9 0 00-9-9m9 9H3m9 9a9 9 0 01-9-9m9 9c1.657 0 3-4.03 3-9s-1.343-9-3-9m0 18c-1.657 0-3-4.03-3-9s1.343-9 3-9m-9 9a9 9 0 019-9" /></svg>
            {t('书源管理')}
          </button>
        )}

        {/* New shelf button */}
        <button onClick={() => setShowDialog(true)} className="m3-nav-item">
          <svg className="w-4 h-4 flex-shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" /></svg>
          {t('新建书柜')}
        </button>
      </div>

      {/* Context menu —— 必须 portal 到 body：外层侧栏 nav 带 backdrop-filter，CSS 规定
          它会成为 fixed 后代的包含块，于是 inset-0 只盖住侧栏、菜单坐标被解释成"相对
          侧栏"，整体下移并在靠右时被 overflow 裁掉。
          表面统一到 .m3-menu / .m3-menu-item（与书库「更多操作」菜单同一套 token：
          surface-container-high + radius 12 + elevation-2 + 40px 项高）。 */}
      {contextMenu && createPortal(
        <div className="fixed inset-0 z-50" onClick={() => setContextMenu(null)}
          onContextMenu={(e) => { e.preventDefault(); setContextMenu(null) }}>
          <div
            ref={contextMenuRef}
            role="menu"
            aria-label={t('书柜操作')}
            className="m3-menu absolute animate-scale-in"
            style={{ left: contextMenu.x, top: contextMenu.y }}
            onClick={(e) => e.stopPropagation()}
            onContextMenu={(e) => { e.preventDefault(); e.stopPropagation() }}
            onKeyDown={(e) => {
              if (e.key === 'ArrowDown' || e.key === 'ArrowUp') { e.preventDefault(); focusMenuItem(e.key === 'ArrowDown' ? 1 : -1); return }
              if (e.key === 'Home' || e.key === 'End') { e.preventDefault(); focusMenuItem(e.key === 'Home' ? 'first' : 'last') }
            }}
          >
            <button role="menuitem" onClick={() => { setEditingId(contextMenu.id); setEditName(bookshelves.find((s) => s.id === contextMenu.id)?.name || ''); setContextMenu(null) }}
              className="m3-menu-item">{t('重命名')}</button>
            <button role="menuitem" onClick={() => { setPendingDeleteId(contextMenu.id); setContextMenu(null) }}
              className="m3-menu-item is-destructive">{t('删除')}</button>
          </div>
        </div>,
        document.body
      )}

      {/* Create dialog —— 走 UI/Modal：它 portal 到 body（否则 320px 宽的弹窗会被
          256px 的侧栏挤扁），并补齐 role="dialog"/aria-modal/Esc/焦点陷阱/关闭回焦。
          review U1：宽度写 !w-80 兜底，别让 .m3-dialog 的默认 560px 压掉设计宽度。 */}
      {showDialog && (
        <Modal
          onClose={closeCreateDialog}
          labelledBy={createTitleId}
          containerClassName="fixed inset-0 z-50 flex items-center justify-center animate-fade-in p-4"
          panelClassName="m3-dialog relative !w-80 p-6 animate-scale-in"
        >
          <h3 className="m3-title-large mb-4" id={createTitleId}>{t('新建书柜')}</h3>
          <input autoFocus value={newName} onChange={(e) => setNewName(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') handleCreate() }}
            placeholder={t('请输入书柜名称...')} className="w-full input mb-4" />
          <div className="flex justify-end gap-2">
            <button onClick={closeCreateDialog} className="m3-text-button">{t('取消')}</button>
            <button onClick={handleCreate} className="m3-filled-button">{t('创建')}</button>
          </div>
        </Modal>
      )}

      {/* 删除书柜确认 */}
      {pendingDeleteId !== null && (
        <ConfirmDialog
          title={t('删除书柜')}
          message={t('确定要删除书柜「{{name}}」吗？柜内书籍不会被删除，但分组会丢失，且不可撤销。', { name: bookshelves.find((s) => s.id === pendingDeleteId)?.name || '' })}
          confirmText={t('删除')}
          danger
          onConfirm={() => { const id = pendingDeleteId; setPendingDeleteId(null); if (id !== null) deleteBookshelf(id) }}
          onCancel={() => setPendingDeleteId(null)}
        />
      )}
    </div>
  )
}
