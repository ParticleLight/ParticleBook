import { cloneElement, useCallback, useEffect, useRef, useState, type ReactElement, type ReactNode } from 'react'
import { createPortal } from 'react-dom'

// ── shared/ui · Popover ──────────────────────────────────────────────
// PB-025-A：锚定浮层。trigger 必须是单个 DOM 元素（button 等）；
// ESC / 点外部关闭；打开时把焦点移进面板，关闭时还给 trigger。
// 位置用 fixed + 视口夹取，滚动/resize 时重新定位。
// 颜色/阴影/圆角全部走 token（--ui-surface-raised / --shadow-lg / --radius-lg）。

export type PopoverPlacement = 'bottom-start' | 'bottom-end' | 'top-start' | 'top-end'

export interface PopoverProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** 触发元素（单个 DOM 元素，会被 clone 以挂 aria/onClick/ref） */
  trigger: ReactElement<Record<string, unknown>>
  children: ReactNode | ((api: { close: () => void }) => ReactNode)
  placement?: PopoverPlacement
  /** 面板的无障碍名称 */
  label?: string
  className?: string
}

const FOCUSABLE = 'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'

export function Popover({ open, onOpenChange, trigger, children, placement = 'bottom-start', label, className = '' }: PopoverProps) {
  const anchorRef = useRef<HTMLElement | null>(null)
  const panelRef = useRef<HTMLDivElement | null>(null)
  const [position, setPosition] = useState<{ left: number; top: number } | null>(null)
  const close = useCallback(() => onOpenChange(false), [onOpenChange])

  const reposition = useCallback(() => {
    const anchor = anchorRef.current
    const panel = panelRef.current
    if (!anchor || !panel) return
    const rect = anchor.getBoundingClientRect()
    const width = panel.offsetWidth
    const height = panel.offsetHeight
    const margin = 8
    let left = placement.endsWith('end') ? rect.right - width : rect.left
    let top = placement.startsWith('top') ? rect.top - height - 8 : rect.bottom + 8
    left = Math.min(Math.max(left, margin), Math.max(margin, window.innerWidth - width - margin))
    top = Math.min(Math.max(top, margin), Math.max(margin, window.innerHeight - height - margin))
    setPosition({ left, top })
  }, [placement])

  useEffect(() => {
    if (!open) { setPosition(null); return }
    const frame = window.requestAnimationFrame(reposition)
    window.addEventListener('resize', reposition)
    window.addEventListener('scroll', reposition, true)
    return () => {
      window.cancelAnimationFrame(frame)
      window.removeEventListener('resize', reposition)
      window.removeEventListener('scroll', reposition, true)
    }
  }, [open, reposition])

  useEffect(() => {
    if (!open) return
    const onPointerDown = (event: MouseEvent) => {
      const target = event.target as Node
      if (panelRef.current?.contains(target) || anchorRef.current?.contains(target)) return
      close()
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      event.stopPropagation()
      // ESC 关闭时把焦点还给 trigger（WAI-ARIA 的 popover 模式）；
      // 点外部关闭则把焦点留给用户点到的地方，不抢焦点。
      const anchor = anchorRef.current
      close()
      window.requestAnimationFrame(() => { try { anchor?.focus() } catch { /* ignore */ } })
    }
    document.addEventListener('mousedown', onPointerDown)
    document.addEventListener('keydown', onKeyDown, true)
    return () => {
      document.removeEventListener('mousedown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown, true)
    }
  }, [open, close])

  // 打开时把焦点移进面板（有可聚焦元素就聚焦第一个，否则聚焦面板本身）。
  // 必须等 position 就位：首帧面板是 visibility:hidden，隐藏元素无法获得焦点。
  useEffect(() => {
    if (!open || !position) return
    const panel = panelRef.current
    if (!panel || panel.contains(document.activeElement)) return
    const target = panel.querySelector<HTMLElement>(FOCUSABLE) || panel
    target.focus()
  }, [open, position])

  const mergeRef = (node: HTMLElement | null) => {
    anchorRef.current = node
    const originalRef = (trigger as unknown as { ref?: unknown }).ref
    if (typeof originalRef === 'function') (originalRef as (n: HTMLElement | null) => void)(node)
    else if (originalRef && typeof originalRef === 'object') (originalRef as { current: HTMLElement | null }).current = node
  }

  const triggerElement = cloneElement(trigger as ReactElement<Record<string, unknown>>, {
    ref: mergeRef,
    'aria-haspopup': 'dialog',
    'aria-expanded': open,
    onClick: (event: React.MouseEvent) => {
      const original = (trigger.props as { onClick?: (e: React.MouseEvent) => void }).onClick
      if (original) original(event)
      if (!event.defaultPrevented) onOpenChange(!open)
    },
  } as Record<string, unknown>)

  return (
    <>
      {triggerElement}
      {open && createPortal(
        <div
          ref={panelRef}
          role="dialog"
          aria-label={label}
          tabIndex={-1}
          className={['pb-popover', className].filter(Boolean).join(' ')}
          style={position ? { left: position.left, top: position.top } : { left: -9999, top: -9999, visibility: 'hidden' }}
        >
          {typeof children === 'function' ? (children as (api: { close: () => void }) => ReactNode)({ close }) : children}
        </div>,
        document.body,
      )}
    </>
  )
}
