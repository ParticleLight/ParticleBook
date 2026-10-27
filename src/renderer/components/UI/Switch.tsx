import { forwardRef } from 'react'

// ── shared/ui · Switch ───────────────────────────────────────────────
// PB-025-A：主题 token 驱动的开关。
// 语义：role="switch" + aria-checked；键盘 Space/Enter 由原生 button 处理。
// 尺寸与配色在 globals.css 的 .pb-switch（M3 规格：轨道 52×32 / thumb 24-16 /
// on 态 --md-sys-color-primary），三个主题由 M3 语义 token 自动适配。

export interface SwitchProps {
  checked: boolean
  onChange: (checked: boolean) => void
  disabled?: boolean
  /** 没有可见文字标签时必填（aria-label） */
  label?: string
  size?: 'sm' | 'md'
  className?: string
  id?: string
}

export const Switch = forwardRef<HTMLButtonElement, SwitchProps>(function Switch(
  { checked, onChange, disabled = false, label, size = 'md', className = '', id },
  ref,
) {
  const classes = ['pb-switch', size === 'sm' ? 'pb-switch-sm' : '', className]
    .filter(Boolean)
    .join(' ')
  return (
    <button
      ref={ref}
      id={id}
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      aria-disabled={disabled || undefined}
      disabled={disabled}
      className={classes}
      data-state={checked ? 'on' : 'off'}
      onClick={() => { if (!disabled) onChange(!checked) }}
    />
  )
})
