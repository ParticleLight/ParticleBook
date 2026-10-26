import { forwardRef } from 'react'

// ── shared/ui · Switch ───────────────────────────────────────────────
// PB-025-A：主题 token 驱动的开关。
// 语义：role="switch" + aria-checked；键盘 Space/Enter 由原生 button 处理。
// 颜色全部走 globals.css 的 --ui-switch-* token，亮/暗/护眼自动适配。

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
