import { useCallback, useEffect, useId, useRef, type KeyboardEvent, type ReactNode } from 'react'
import { createPortal } from 'react-dom'

// ── shared/ui · Dialog ───────────────────────────────────────────────
// PB-025-A：模态对话框。ESC / 背景点击关闭（可关），Tab 焦点循环，
// 打开时锁定 body 滚动并在关闭后把焦点还给触发元素。
// 主题走 --ui-backdrop / --ui-surface-raised token，亮/暗/护眼一致。

export interface DialogProps {
  open: boolean
  onClose: () => void
  title?: string
  description?: string
  children: ReactNode
  footer?: ReactNode
  size?: 'sm' | 'md' | 'lg'
  closeOnBackdrop?: boolean
  className?: string
}

const SIZE_WIDTH: Record<'sm' | 'md' | 'lg', number> = { sm: 420, md: 560, lg: 760 }
const FOCUSABLE = 'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'

export function Dialog({
  open, onClose, title, description, children, footer,
  size = 'md', closeOnBackdrop = true, className = '',
}: DialogProps) {
  const panelRef = useRef<HTMLDivElement>(null)
  const previousFocusRef = useRef<HTMLElement | null>(null)
  const titleId = useId()
  const descriptionId = useId()

  useEffect(() => {
    if (!open) return
    previousFocusRef.current = document.activeElement as HTMLElement | null
    const panel = panelRef.current
    const target = panel?.querySelector<HTMLElement>(FOCUSABLE) || panel
    target?.focus()
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = previousOverflow
      try { previousFocusRef.current?.focus() } catch { /* ignore */ }
    }
  }, [open])

  const onKeyDown = useCallback((event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.stopPropagation()
      onClose()
      return
    }
    if (event.key !== 'Tab') return
    const panel = panelRef.current
    if (!panel) return
    const items = Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE))
      .filter((el) => !el.hasAttribute('disabled') && el.getAttribute('aria-hidden') !== 'true')
    if (items.length === 0) {
      event.preventDefault()
      panel.focus()
      return
    }
    const first = items[0]
    const last = items[items.length - 1]
    const active = document.activeElement
    if (event.shiftKey && (active === first || active === panel)) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && active === last) {
      event.preventDefault()
      first.focus()
    }
  }, [onClose])

  if (!open) return null

  return createPortal(
    <div
      className="pb-dialog-backdrop fixed inset-0 z-[80] flex items-center justify-center p-4"
      onMouseDown={(event) => { if (closeOnBackdrop && event.target === event.currentTarget) onClose() }}
    >
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={title ? titleId : undefined}
        aria-describedby={description ? descriptionId : undefined}
        aria-label={title ? undefined : 'dialog'}
        tabIndex={-1}
        className={['pb-dialog flex flex-col overflow-hidden', className].filter(Boolean).join(' ')}
        style={{ width: SIZE_WIDTH[size], maxWidth: '92vw', maxHeight: '86vh' }}
        onKeyDown={onKeyDown}
      >
        {(title || description) && (
          <div className="px-5 pt-4 pb-2">
            {title && <h2 id={titleId} className="pb-dialog-title">{title}</h2>}
            {description && <p id={descriptionId} className="pb-dialog-description">{description}</p>}
          </div>
        )}
        <div className="px-5 py-2 overflow-y-auto flex-1">{children}</div>
        {footer && <div className="px-5 py-3 pb-dialog-footer">{footer}</div>}
      </div>
    </div>,
    document.body,
  )
}
