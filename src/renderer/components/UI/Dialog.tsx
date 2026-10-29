import { useEffect, useId, type ReactNode } from 'react'
import { Modal } from './Modal'

// ── shared/ui · Dialog ───────────────────────────────────────────────
// PB-025-A：模态对话框（标题 / 描述 / 页脚）。2026-10-29 U5：行为层统一到
// UI/Modal —— Esc、Tab 焦点循环、打开聚焦、关闭回焦、逐层 Esc（叠确认框时只关
// 最上层）都由 Modal 提供，这里只保留弹窗自己的「尺寸 + 滚动锁 + 结构」。
// 视觉走 globals.css 的 .pb-dialog（已映射到 M3 surface-container-high）。
// portal 到 body：见 docs/memory/pitfalls.md（backdrop-filter 会成为包含块）。

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

export function Dialog({
  open, onClose, title, description, children, footer,
  size = 'md', closeOnBackdrop = true, className = '',
}: DialogProps) {
  const titleId = useId()
  const descriptionId = useId()

  // 打开时锁定 body 滚动（弹层内容自己滚，背景不跟着动）。
  useEffect(() => {
    if (!open) return
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = previousOverflow
    }
  }, [open])

  if (!open) return null

  return (
    <Modal
      onClose={onClose}
      labelledBy={title ? titleId : undefined}
      // 没有可见标题时也要有个可访问名（沿用改造前的占位词，不是用户可见文案）
      ariaLabel={title ? undefined : 'dialog'}
      describedBy={description ? descriptionId : undefined}
      closeOnBackdrop={closeOnBackdrop}
      backdropClassName="pb-dialog-backdrop absolute inset-0"
      panelClassName={['pb-dialog flex flex-col overflow-hidden', className].filter(Boolean).join(' ')}
      panelStyle={{ width: SIZE_WIDTH[size], maxWidth: '92vw', maxHeight: '86vh' }}
    >
      {(title || description) && (
        <div className="px-5 pt-4 pb-2">
          {title && <h2 id={titleId} className="pb-dialog-title">{title}</h2>}
          {description && <p id={descriptionId} className="pb-dialog-description">{description}</p>}
        </div>
      )}
      <div className="px-5 py-2 overflow-y-auto flex-1">{children}</div>
      {footer && <div className="px-5 py-3 pb-dialog-footer">{footer}</div>}
    </Modal>
  )
}
