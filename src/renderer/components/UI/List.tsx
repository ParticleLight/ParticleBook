import { useCallback, useRef, type Key, type ReactNode } from 'react'

// ── shared/ui · List ─────────────────────────────────────────────────
// PB-025-A：可选中的列表（role=listbox/option），方向键/Home/End 移动焦点，
// Enter/Space 选中；不可选中时退化成普通 role=list。
// 交互色走 M3 state / primary-container 语义（见 globals.css 的 .pb-list*）。

export interface ListState {
  selected: boolean
  active: boolean
  index: number
}

export interface ListProps<T> {
  items: T[]
  getKey: (item: T) => Key
  renderItem: (item: T, state: ListState) => ReactNode
  /** 传入 onSelect 时列表变成可选中（listbox） */
  onSelect?: (item: T, index: number) => void
  selectedKey?: Key | null
  /** 无障碍名称 */
  label?: string
  empty?: ReactNode
  className?: string
  itemClassName?: string
  maxHeight?: number | string
}

export function List<T>({
  items, getKey, renderItem, onSelect, selectedKey = null, label,
  empty = null, className = '', itemClassName = '', maxHeight,
}: ListProps<T>) {
  const rootRef = useRef<HTMLDivElement>(null)
  const selectable = typeof onSelect === 'function'

  const focusItem = useCallback((index: number) => {
    const root = rootRef.current
    if (!root) return
    const nodes = Array.from(root.querySelectorAll<HTMLElement>('[data-pb-list-item]'))
    const node = nodes[Math.max(0, Math.min(index, nodes.length - 1))]
    node?.focus()
  }, [])

  const onKeyDown = useCallback((event: React.KeyboardEvent<HTMLDivElement>) => {
    if (!selectable) return
    const root = rootRef.current
    if (!root) return
    const nodes = Array.from(root.querySelectorAll<HTMLElement>('[data-pb-list-item]'))
    if (nodes.length === 0) return
    const index = nodes.indexOf(document.activeElement as HTMLElement)
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      const delta = event.key === 'ArrowDown' ? 1 : -1
      focusItem(index < 0 ? 0 : (index + delta + nodes.length) % nodes.length)
      return
    }
    if (event.key === 'Home') { event.preventDefault(); focusItem(0); return }
    if (event.key === 'End') { event.preventDefault(); focusItem(nodes.length - 1); return }
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      const node = nodes[index]
      if (node) node.click()
    }
  }, [focusItem, selectable])

  return (
    <div
      ref={rootRef}
      role={selectable ? 'listbox' : 'list'}
      aria-label={label}
      className={['pb-list', className].filter(Boolean).join(' ')}
      style={maxHeight !== undefined ? { maxHeight, overflowY: 'auto' } : undefined}
      onKeyDown={onKeyDown}
    >
      {items.length === 0 ? empty : items.map((item, index) => {
        const key = getKey(item)
        const selected = selectedKey !== null && selectedKey !== undefined && key === selectedKey
        return (
          <div
            key={key}
            role={selectable ? 'option' : 'listitem'}
            aria-selected={selectable ? selected : undefined}
            tabIndex={selectable ? 0 : -1}
            data-pb-list-item="1"
            data-selected={selected ? 'true' : 'false'}
            className={['pb-list-item', itemClassName].filter(Boolean).join(' ')}
            onClick={selectable ? () => onSelect && onSelect(item, index) : undefined}
            onKeyDown={selectable ? (event) => {
              if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); onSelect && onSelect(item, index) }
            } : undefined}
          >
            {renderItem(item, { selected, active: false, index })}
          </div>
        )
      })}
    </div>
  )
}
