import { useCallback, useEffect, useRef, type KeyboardEvent, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import './NavRail.css'

// ── M3 navigation rail / modal navigation drawer ─────────────────────
// 供书库与设置页在「窄窗口」下复用（见 hooks/useWindowSizeClass）：
//   expanded (>= 840px) 沿用原有 248px 常驻抽屉，不用这里的组件；
//   medium   (640–839) 用 <NavRail>：80px 图标 rail，书柜这种数据驱动长列表
//                      放不进 rail，由一个「书柜」destination 打开临时抽屉；
//   compact  (< 640)   顶栏汉堡 → <ModalNavDrawer>（scrim + 焦点陷阱 + Esc）。
//
// 颜色/圆角/阴影/动效全部走 globals.css 的 --md-sys-* / --ui-* token，
// 结构样式在本目录的 NavRail.css（不往 globals.css 里加东西）。

export interface NavRailItem {
  /** 稳定 id，用于测试与 key */
  id: string
  /** 可见 label（同时作为 title 提示与可访问名） */
  label: string
  /** 24px 图标；尺寸由 .pb-nav-rail-icon svg 统一约束 */
  icon: ReactNode
  /** 当前所在 destination：true 时画 primary-container 指示条并设置 aria-current */
  active?: boolean
  onSelect: () => void
}

export interface NavRailProps {
  items: NavRailItem[]
  /** 导航地标名（<nav aria-label>） */
  'aria-label': string
  className?: string
}

const FOCUSABLE =
  'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

export function NavRail({ items, 'aria-label': ariaLabel, className }: NavRailProps) {
  return (
    <nav
      className={['pb-nav-rail', className].filter(Boolean).join(' ')}
      aria-label={ariaLabel}
      data-testid="nav-rail"
    >
      {items.map((item) => (
        <button
          key={item.id}
          type="button"
          onClick={item.onSelect}
          className="pb-nav-rail-item"
          data-nav-id={item.id}
          aria-current={item.active ? 'page' : undefined}
          title={item.label}
        >
          <span className="pb-nav-rail-indicator" aria-hidden="true">
            <span className="pb-nav-rail-icon">{item.icon}</span>
          </span>
          <span className="pb-nav-rail-label">{item.label}</span>
        </button>
      ))}
    </nav>
  )
}

export interface ModalNavDrawerProps {
  open: boolean
  onClose: () => void
  /** 抽屉标题（同时作为 dialog 的可访问名） */
  title: string
  children: ReactNode
  /** 关闭按钮的可访问名；调用方必须传 t('关闭') 之类的已本地化文案 */
  closeLabel: string
  className?: string
}

export function ModalNavDrawer({ open, onClose, title, children, closeLabel, className }: ModalNavDrawerProps) {
  const panelRef = useRef<HTMLDivElement>(null)
  const previousFocusRef = useRef<HTMLElement | null>(null)

  // 打开时记住触发元素并把焦点移进抽屉；关闭/卸载时把焦点还回去（通常是汉堡
  // 按钮或 rail 里的「书柜」）。只在 open 变化时跑，不影响抽屉内的重渲染。
  useEffect(() => {
    if (!open) return
    const panel = panelRef.current
    previousFocusRef.current = (document.activeElement as HTMLElement | null) ?? null
    const target = panel?.querySelector<HTMLElement>(FOCUSABLE) ?? panel
    target?.focus()
    return () => {
      const previous = previousFocusRef.current
      previousFocusRef.current = null
      // 触发按钮可能已被卸载（例如从书库跳走），isConnected 兜底避免异常
      if (!previous || !previous.isConnected) return
      // 抽屉关闭时如果已经有别的元素接手了焦点（典型场景：抽屉里的「从全部
      // 添加」同时打开了自动聚焦的选书弹层），不要把它抢回汉堡按钮 ——
      // autoFocus 在 layout 阶段生效，比这里的 passive cleanup 早。
      const active = document.activeElement
      if (active && active !== document.body) return
      try {
        previous.focus()
      } catch {
        /* 元素不可聚焦时静默 */
      }
    }
  }, [open])

  // Esc 关闭 + Tab 焦点循环。挂在 panel 上（而不是 document）是刻意的：
  // 抽屉里打开的二级弹层（新建书柜 / 右键菜单 / ConfirmDialog）都 portal 到
  // body，不在 panel 子树里，因此它们的键事件不会被这里劫持；反过来焦点在
  // 抽屉内时，Esc 与 Tab 都能被捕获。
  const onKeyDown = useCallback(
    (event: KeyboardEvent<HTMLDivElement>) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        event.stopPropagation()
        onClose()
        return
      }
      if (event.key !== 'Tab') return
      const panel = panelRef.current
      if (!panel) return
      const focusable = Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(
        (el) => !el.hasAttribute('disabled') && el.getAttribute('aria-hidden') !== 'true'
      )
      if (focusable.length === 0) {
        event.preventDefault()
        panel.focus()
        return
      }
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      const active = document.activeElement
      if (event.shiftKey && (active === first || active === panel)) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && active === last) {
        event.preventDefault()
        first.focus()
      }
    },
    [onClose]
  )

  if (!open) return null

  // portal 到 body：globals.css 里 .mica / .acrylic 以及弹层的 backdrop-filter /
  // transform 会成为 fixed 后代的包含块（见 docs/memory/pitfalls.md），
  // 挂在渲染树里会让 inset-0 只盖住某个卡片而不是整个窗口。
  return createPortal(
    <div className="pb-nav-drawer-root" data-testid="modal-nav-drawer">
      <div className="pb-nav-drawer-scrim" aria-hidden="true" onClick={onClose} />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        tabIndex={-1}
        className={['pb-nav-drawer-panel', className].filter(Boolean).join(' ')}
        onKeyDown={onKeyDown}
      >
        <div className="pb-nav-drawer-header">
          <h2 className="m3-title-medium pb-nav-drawer-title">{title}</h2>
          <button type="button" className="m3-icon-button" onClick={onClose} aria-label={closeLabel} title={closeLabel}>
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" aria-hidden="true">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>
        <div className="pb-nav-drawer-body">{children}</div>
      </div>
    </div>,
    document.body
  )
}
