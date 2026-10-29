import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent,
  type ReactNode,
} from 'react'
import { createPortal } from 'react-dom'

// ── shared/ui · Modal（2026-10-29，review U2/U5 修复）────────────────
// 弹层「行为基座」：只负责对话框语义与焦点管理，视觉仍由调用方给类名，
// 因此书库/设置里那些已有布局的弹层可以最小改动接入：
//   * role="dialog" + aria-modal="true" + aria-labelledby/aria-label
//   * Esc 关闭（逐层：只有最上层响应，见下方 modalStack）
//   * Tab 焦点循环（首尾环绕，焦点不跑到背景页面）
//   * 打开时把焦点移进面板（子元素已自己 autoFocus 时让位）
//   * 关闭/卸载后把焦点还给触发元素（若有别的弹层已接手焦点则让位）
//   * 遮罩默认点击关闭（closeOnBackdrop 可关）
// portal 到 body：globals.css 里 .mica/.acrylic/弹层用的 backdrop-filter 会成为
// fixed 后代的包含块（docs/memory/pitfalls.md），挂在渲染树里会定位错乱。
//
// 需要「有未保存修改时二次确认」的调用方，把 onClose 换成自己的 requestClose，
// 并在确认后再调用真正的关闭（见 BookDetailDialog）。

const FOCUSABLE =
  'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

// 弹层栈（模块级）：Esc 兜底只关最上层，避免「一次 Esc 关掉详情 + 确认框两层」。
// 同一个栈里既有 Modal 也有 Dialog（Dialog 内部就是 Modal），所以确认框叠在
// 详情框上时也是正确的先后顺序。
const modalStack: number[] = []
let modalSeq = 0

export interface ModalProps {
  /** 关闭请求（Esc / 遮罩 / 调用方按钮都会走这里） */
  onClose: () => void
  /** 标题元素 id（aria-labelledby）；没有可见标题时用 ariaLabel */
  labelledBy?: string
  ariaLabel?: string
  /** 描述元素 id（aria-describedby，可选） */
  describedBy?: string
  children: ReactNode
  /** 最外层定位容器，默认 fixed 满屏 + 居中 */
  containerClassName?: string
  containerStyle?: CSSProperties
  /** 遮罩层类名（默认 .m3-dialog-backdrop；.pb-dialog-backdrop 与它同色） */
  backdropClassName?: string
  /** 面板（通常带 m3-dialog） */
  panelClassName?: string
  panelStyle?: CSSProperties
  /** 点遮罩是否关闭，默认 true */
  closeOnBackdrop?: boolean
  /** 是否 portal 到 body，默认 true */
  portal?: boolean
  /** 打开时是否把焦点移入面板，默认 true */
  autoFocus?: boolean
  /** 关闭后是否回焦触发元素，默认 true */
  restoreFocus?: boolean
  /** 调用方自己的按键处理（在 Modal 处理之前调用；preventDefault 后 Modal 不再处理） */
  onKeyDown?: (event: KeyboardEvent<HTMLDivElement>) => void
  /** 额外的 data-* 测试钩子（挂在最外层容器上） */
  containerData?: Record<string, string>
  testId?: string
  panelTestId?: string
}

export function Modal({
  onClose,
  labelledBy,
  ariaLabel,
  describedBy,
  children,
  containerClassName = 'fixed inset-0 z-[80] flex items-center justify-center p-4',
  containerStyle,
  backdropClassName = 'm3-dialog-backdrop absolute inset-0',
  panelClassName,
  panelStyle,
  closeOnBackdrop = true,
  portal = true,
  autoFocus = true,
  restoreFocus = true,
  onKeyDown,
  containerData,
  testId,
  panelTestId,
}: ModalProps) {
  const panelRef = useRef<HTMLDivElement>(null)
  const [modalId] = useState(() => {
    modalSeq += 1
    return modalSeq
  })
  // 首次渲染时记下触发元素（此时弹层还没进 DOM，document.activeElement 仍是触发器）
  const [previousFocus] = useState<HTMLElement | null>(() =>
    typeof document === 'undefined' ? null : (document.activeElement as HTMLElement | null),
  )

  // 入栈顺序 = 挂载顺序：后挂载的（确认框）就是最上层。
  useEffect(() => {
    modalStack.push(modalId)
    return () => {
      const index = modalStack.lastIndexOf(modalId)
      if (index >= 0) modalStack.splice(index, 1)
    }
  }, [modalId])

  useEffect(() => {
    const panel = panelRef.current
    if (!panel) return
    if (autoFocus) {
      const active = document.activeElement
      // 子元素自己 autoFocus 过（例如搜索框）就不抢
      if (!active || !panel.contains(active)) {
        const target = panel.querySelector<HTMLElement>(FOCUSABLE) ?? panel
        target?.focus({ preventScroll: true })
      }
    }
    return () => {
      if (!restoreFocus || !previousFocus || !previousFocus.isConnected) return
      // 已经打开的别的弹层（确认框等）接手了焦点时不要抢回来
      const active = document.activeElement
      if (active && active !== document.body) return
      try {
        previousFocus.focus()
      } catch {
        /* 元素已不可聚焦时静默 */
      }
    }
  }, [autoFocus, restoreFocus, previousFocus])

  // Esc / Tab 兜底：焦点可能不在面板里 —— closeOnBackdrop=false 时点遮罩会让焦点
  // 落到 body（浏览器会 blur 当前元素），此时面板上的 React onKeyDown 收不到按键。
  // 面板内的 Esc / Tab 仍由 handleKeyDown 处理（子控件如 M3Select 可以先自己
  // stopPropagation，于是「先关下拉、再关弹层」的层次不被破坏）。
  // 两层弹层叠加时只有最上层（modalStack 末尾）响应，一次 Esc 只关一层。
  useEffect(() => {
    const onDocumentKeyDown = (event: globalThis.KeyboardEvent) => {
      if (modalStack[modalStack.length - 1] !== modalId) return
      const panel = panelRef.current
      const inside = Boolean(panel && panel.contains(event.target as Node))
      if (event.key === 'Escape') {
        if (inside) return
        event.preventDefault()
        event.stopPropagation()
        onClose()
        return
      }
      // 焦点已经漏到弹层外时，Tab 也拉回面板里（否则能 Tab 进背后的书库）
      if (event.key === 'Tab' && !inside) {
        event.preventDefault()
        const target = panel?.querySelector<HTMLElement>(FOCUSABLE) ?? panel
        target?.focus({ preventScroll: true })
      }
    }
    document.addEventListener('keydown', onDocumentKeyDown, true)
    return () => document.removeEventListener('keydown', onDocumentKeyDown, true)
  }, [modalId, onClose])

  const handleKeyDown = useCallback(
    (event: KeyboardEvent<HTMLDivElement>) => {
      onKeyDown?.(event)
      if (event.defaultPrevented) return
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
        (el) => !el.hasAttribute('disabled') && el.getAttribute('aria-hidden') !== 'true',
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
    [onClose, onKeyDown],
  )

  const content = (
    <div className={containerClassName} style={containerStyle} data-testid={testId} {...containerData}>
      <div
        className={backdropClassName}
        aria-hidden="true"
        onClick={closeOnBackdrop ? onClose : undefined}
      />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={labelledBy}
        aria-describedby={describedBy}
        aria-label={labelledBy ? undefined : ariaLabel}
        tabIndex={-1}
        className={panelClassName}
        style={panelStyle}
        onKeyDown={handleKeyDown}
        data-testid={panelTestId}
      >
        {children}
      </div>
    </div>
  )

  return portal ? createPortal(content, document.body) : content
}
