import { cloneElement, isValidElement, useId, type ReactElement, type ReactNode } from 'react'

// ── shared/ui · Field ────────────────────────────────────────────────
// PB-025-A：label + 控件 + 提示 + 错误 的组合，自动把 id / aria-invalid /
// aria-describedby / aria-required 接到子控件上（子控件是单个 DOM 元素时）。
// 样式走 --text-* / --border / --color-red token。

export interface FieldProps {
  label: string
  children: ReactNode
  hint?: string
  error?: string
  required?: boolean
  /** 覆盖自动生成的控件 id */
  htmlFor?: string
  className?: string
}

export function Field({ label, children, hint, error, required = false, htmlFor, className = '' }: FieldProps) {
  const autoId = useId()
  const controlId = htmlFor || autoId
  const hintId = hint ? controlId + '-hint' : undefined
  const errorId = error ? controlId + '-error' : undefined
  const describedBy = [hintId, errorId].filter(Boolean).join(' ') || undefined

  let control: ReactNode = children
  if (isValidElement(children)) {
    const child = children as ReactElement<Record<string, unknown>>
    control = cloneElement(child, {
      id: (child.props.id as string | undefined) || controlId,
      'aria-invalid': error ? true : undefined,
      'aria-describedby': describedBy,
      'aria-required': required || undefined,
    } as Record<string, unknown>)
  }

  return (
    <div className={['pb-field', className].filter(Boolean).join(' ')}>
      <label className="pb-field-label" htmlFor={controlId}>
        {label}
        {required && <span className="pb-field-required" aria-hidden="true"> *</span>}
      </label>
      {control}
      {hint && <p id={hintId} className="pb-field-hint">{hint}</p>}
      {error && <p id={errorId} className="pb-field-error" role="alert">{error}</p>}
    </div>
  )
}
