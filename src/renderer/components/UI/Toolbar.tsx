import { useCallback, useRef, type KeyboardEvent, type ReactNode } from 'react'

// ── shared/ui · Toolbar ──────────────────────────────────────────────
// PB-025-A：role="toolbar" 容器 + 方向键/Home/End 在可聚焦子元素间移动焦点。
// 不做 roving tabindex（子元素仍是普通按钮，Tab 也能正常进入），只补方向键。

export interface ToolbarProps {
  /** 无障碍名称（aria-label） */
  label: string
  children: ReactNode
  orientation?: 'horizontal' | 'vertical'
  className?: string
}

const FOCUSABLE = 'button, [role="button"], a[href], input, select, textarea, [tabindex]:not([tabindex="-1"])'

export function Toolbar({ label, children, orientation = 'horizontal', className = '' }: ToolbarProps) {
  const rootRef = useRef<HTMLDivElement>(null)

  const onKeyDown = useCallback((event: KeyboardEvent<HTMLDivElement>) => {
    const forwardKey = orientation === 'horizontal' ? 'ArrowRight' : 'ArrowDown'
    const backwardKey = orientation === 'horizontal' ? 'ArrowLeft' : 'ArrowUp'
    if (![forwardKey, backwardKey, 'Home', 'End'].includes(event.key)) return
    const root = rootRef.current
    if (!root) return
    const items = Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE))
      .filter((el) => !el.hasAttribute('disabled') && el.getAttribute('aria-hidden') !== 'true')
    if (items.length === 0) return
    const active = document.activeElement as HTMLElement | null
    let index = active ? items.indexOf(active) : -1
    if (event.key === 'Home') index = 0
    else if (event.key === 'End') index = items.length - 1
    else if (index < 0) index = event.key === forwardKey ? 0 : items.length - 1
    else index = (index + (event.key === forwardKey ? 1 : -1) + items.length) % items.length
    event.preventDefault()
    items[index]?.focus()
  }, [orientation])

  return (
    <div
      ref={rootRef}
      role="toolbar"
      aria-label={label}
      aria-orientation={orientation}
      className={['pb-toolbar', className].filter(Boolean).join(' ')}
      onKeyDown={onKeyDown}
    >
      {children}
    </div>
  )
}
