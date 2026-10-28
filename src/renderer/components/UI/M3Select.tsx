import {
  useCallback,
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type KeyboardEvent,
} from 'react'
import { createPortal } from 'react-dom'

// ── shared/ui · M3Select ─────────────────────────────────────────────
// 原生 <select> 的 M3 替身（task-5）。
//
// 为什么需要它：原生下拉的「弹出列表」由浏览器 / 系统绘制，WebView2 里是白底
// 浅色的系统菜单，完全不跟暗色 / 护眼主题 —— 这正是「下拉菜单样式没同步」的
// 主因。这里触发器沿用 .m3-select / .m3-select.is-field 的外观（边框、圆角、
// 字阶、右侧双渐变箭头），弹层换成与书库「更多操作」菜单同一套 .m3-menu 表面。
//
// 触发器：button[aria-haspopup=listbox][aria-expanded][aria-controls]
// 弹层：  div.m3-menu > div[role=listbox][aria-activedescendant]
//                        > button[role=option][aria-selected][aria-disabled]
//
// 必须 portal 到 body：globals.css 里 .mica / .acrylic / 弹层表面用了
// backdrop-filter，CSS 规定它会成为 fixed 后代的包含块 —— 侧栏、卡片里的下拉
// 若不 portal，坐标会被解释成「相对该容器」，还会被 overflow 裁掉
// （BookShelfPanel 的右键菜单踩过同一个坑）。
//
// 无障碍：打开后焦点停在 listbox 容器上，用 aria-activedescendant 表达高亮项
// （WAI-ARIA listbox 模式）。这样 Tab 关闭时焦点能同步交回触发器，浏览器随后
// 按正常顺序把焦点移到触发器的下一个元素 —— 不会掉到 body 上。

export interface M3SelectOption {
  value: string
  label: string
  disabled?: boolean
}

export interface M3SelectProps {
  value: string
  options: M3SelectOption[]
  onChange: (value: string) => void
  'aria-label': string
  title?: string
  /** compact = 32px 工具条/筛选触发器（m3-select）；field = 44px 表单（m3-select.is-field） */
  variant?: 'compact' | 'field'
  /** 筛选类：当前值非默认时加 is-selected（保持既有观感） */
  active?: boolean
  disabled?: boolean
  className?: string
  /** 透传到触发器，保持既有 data-testid 不变 */
  testid?: string
  maxMenuHeight?: number
}

/** 额外透传到触发器的 data-* 观测点（data-tts-sleep / data-source-sort /
    data-setting-key …）。替换前后属性仍落在同一个元素上。 */
export type M3SelectDataAttributes = {
  [K in `data-${string}`]?: string | number | boolean | undefined
}

const MENU_MARGIN = 8 // 与视口边缘留出的间距
const MENU_GAP = 4 // 触发器与弹层之间的间距
const MENU_MIN_WIDTH = 180 // 与 .m3-menu 的 min-width 一致
const MENU_LIST_MIN_HEIGHT = 96 // 空/极小空间下也至少露出两行
const MENU_MAX_HEIGHT = 320 // 默认选项区最大高度

interface MenuPlacement {
  left: number
  top: number
  minWidth: number
  maxWidth: number
  listMaxHeight: number
}

/** 从 start 起按 step 找第一个可用（非 disabled）选项，找不到返回 -1 */
function findEnabledIndex(options: M3SelectOption[], start: number, step: 1 | -1): number {
  for (let index = start; index >= 0 && index < options.length; index += step) {
    if (!options[index].disabled) return index
  }
  return -1
}

export function M3Select({
  value,
  options,
  onChange,
  'aria-label': ariaLabel,
  title,
  variant = 'compact',
  active = false,
  disabled = false,
  className = '',
  testid,
  maxMenuHeight = MENU_MAX_HEIGHT,
  ...dataAttributes
}: M3SelectProps & M3SelectDataAttributes) {
  const [open, setOpen] = useState(false)
  const [placement, setPlacement] = useState<MenuPlacement | null>(null)
  const [activeIndex, setActiveIndex] = useState(-1)
  const triggerRef = useRef<HTMLButtonElement | null>(null)
  const menuRef = useRef<HTMLDivElement | null>(null)
  const listRef = useRef<HTMLDivElement | null>(null)
  const focusedOnceRef = useRef(false)
  const reactId = useId()
  const listboxId = `m3-select-listbox${reactId}`
  const optionId = (index: number) => `${listboxId}-option-${index}`

  const selectedIndex = options.findIndex((option) => option.value === value)
  const selectedLabel = selectedIndex >= 0 ? options[selectedIndex].label : ''
  const listMaxHeight = placement ? placement.listMaxHeight : maxMenuHeight

  // ── 定位：fixed + 视口夹取，下方不够时翻到上方 ────────────────────
  // 测量放在 useLayoutEffect 里：首帧弹层 visibility:hidden 但仍有布局，
  // 量完立刻 setPlacement，浏览器在绘制前就拿到最终坐标（无闪跳）。
  const reposition = useCallback(() => {
    const trigger = triggerRef.current
    const menu = menuRef.current
    const list = listRef.current
    if (!trigger || !menu || !list) return
    const rect = trigger.getBoundingClientRect()
    const viewportWidth = document.documentElement.clientWidth || window.innerWidth
    const viewportHeight = document.documentElement.clientHeight || window.innerHeight
    // .m3-menu 的 padding + border：用实测差值算，不硬编码 8px/1px
    const chrome = Math.max(0, menu.offsetHeight - list.offsetHeight)
    const contentListHeight = Math.min(Math.max(list.scrollHeight, 0), maxMenuHeight)
    const spaceBelow = viewportHeight - rect.bottom - MENU_GAP - MENU_MARGIN
    const spaceAbove = rect.top - MENU_GAP - MENU_MARGIN
    // 默认向下展开；下方装不下且上方更宽裕时翻到上方
    const flipAbove = spaceBelow < contentListHeight + chrome && spaceAbove > spaceBelow
    const usableHeight = Math.max(0, (flipAbove ? spaceAbove : spaceBelow) - chrome)
    const nextListMaxHeight = Math.max(
      MENU_LIST_MIN_HEIGHT,
      Math.min(maxMenuHeight, usableHeight),
    )
    const panelHeight = Math.min(contentListHeight, nextListMaxHeight) + chrome
    const minWidth = Math.max(rect.width, MENU_MIN_WIDTH)
    const maxWidth = Math.max(MENU_MIN_WIDTH, viewportWidth - MENU_MARGIN * 2)
    const panelWidth = Math.min(Math.max(menu.offsetWidth, minWidth), maxWidth)
    let left = rect.left
    if (left + panelWidth > viewportWidth - MENU_MARGIN) left = viewportWidth - MENU_MARGIN - panelWidth
    left = Math.max(MENU_MARGIN, left)
    let top = flipAbove ? rect.top - MENU_GAP - panelHeight : rect.bottom + MENU_GAP
    top = Math.min(
      Math.max(top, MENU_MARGIN),
      Math.max(MENU_MARGIN, viewportHeight - panelHeight - MENU_MARGIN),
    )
    setPlacement((prev) => (
      prev
      && prev.left === left
      && prev.top === top
      && prev.minWidth === minWidth
      && prev.maxWidth === maxWidth
      && prev.listMaxHeight === nextListMaxHeight
        ? prev
        : { left, top, minWidth, maxWidth, listMaxHeight: nextListMaxHeight }
    ))
  }, [maxMenuHeight])

  useLayoutEffect(() => {
    if (!open) {
      setPlacement(null)
      return
    }
    reposition()
  }, [open, reposition, options.length])

  // 打开期间跟随滚动 / 窗口尺寸重新定位（scroll 用捕获阶段：祖先容器滚动也算）
  useEffect(() => {
    if (!open) return
    const onReflow = () => { reposition() }
    window.addEventListener('resize', onReflow)
    window.addEventListener('scroll', onReflow, true)
    return () => {
      window.removeEventListener('resize', onReflow)
      window.removeEventListener('scroll', onReflow, true)
    }
  }, [open, reposition])

  // 点外部关闭（点触发器本身交给 onClick 的 toggle 处理，避免双重切换）
  useEffect(() => {
    if (!open) return
    const onPointerDown = (event: MouseEvent) => {
      const target = event.target as Node | null
      if (!target) return
      if (menuRef.current?.contains(target) || triggerRef.current?.contains(target)) return
      setOpen(false)
    }
    document.addEventListener('mousedown', onPointerDown)
    return () => document.removeEventListener('mousedown', onPointerDown)
  }, [open])

  // 首次定位完成后再把焦点交给 listbox（隐藏元素拿不到焦点）
  useEffect(() => {
    if (!open) {
      focusedOnceRef.current = false
      return
    }
    if (!placement || focusedOnceRef.current) return
    focusedOnceRef.current = true
    listRef.current?.focus({ preventScroll: true })
  }, [open, placement])

  // 高亮项自动滚进可见区域（listbox 是 position:relative，offsetTop 相对它）
  useEffect(() => {
    if (!open || !placement || activeIndex < 0) return
    const list = listRef.current
    if (!list) return
    const node = list.children[activeIndex] as HTMLElement | undefined
    if (!node) return
    const top = node.offsetTop
    const bottom = top + node.offsetHeight
    if (top < list.scrollTop) list.scrollTop = top
    else if (bottom > list.scrollTop + list.clientHeight) list.scrollTop = bottom - list.clientHeight
  }, [activeIndex, open, placement])

  const openMenu = useCallback((preferLast = false) => {
    if (disabled) return
    const selectedSelectable = selectedIndex >= 0 && !options[selectedIndex]?.disabled
    setActiveIndex(selectedSelectable
      ? selectedIndex
      : findEnabledIndex(options, preferLast ? options.length - 1 : 0, preferLast ? -1 : 1))
    setPlacement(null)
    setOpen(true)
  }, [disabled, options, selectedIndex])

  const closeMenu = useCallback((restoreFocus = false) => {
    setOpen(false)
    // 同步聚焦：Tab 分支要在浏览器执行默认「移动到下一个元素」之前把焦点
    // 放回触发器，否则菜单卸载后焦点会掉到 body
    if (restoreFocus) triggerRef.current?.focus()
  }, [])

  const selectOption = useCallback((index: number) => {
    const option = options[index]
    if (!option || option.disabled) return
    if (option.value !== value) onChange(option.value)
    closeMenu(true)
  }, [closeMenu, onChange, options, value])

  const moveActive = useCallback((step: 1 | -1) => {
    setActiveIndex((current) => {
      if (options.length === 0) return -1
      if (current < 0) return findEnabledIndex(options, step > 0 ? 0 : options.length - 1, step)
      let next = current + step
      while (next >= 0 && next < options.length) {
        if (!options[next].disabled) return next
        next += step
      }
      return current // 到边界不动（APG listbox 语义，不循环）
    })
  }, [options])

  const handleTriggerClick = useCallback(() => {
    if (disabled) return
    if (open) closeMenu()
    else openMenu()
  }, [closeMenu, disabled, open, openMenu])

  const handleTriggerKeyDown = useCallback((event: KeyboardEvent<HTMLButtonElement>) => {
    if (disabled) return
    if (event.key === 'Enter' || event.key === ' ' || event.key === 'Spacebar') {
      // preventDefault 压掉按钮的默认激活 click，否则 keydown 打开后又会被 click 关掉
      event.preventDefault()
      if (open) closeMenu()
      else openMenu()
      return
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      if (!open) openMenu(event.key === 'ArrowUp')
    }
  }, [closeMenu, disabled, open, openMenu])

  const handleListKeyDown = useCallback((event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault()
      // 别让 Esc 冒到阅读器/弹层的全局快捷键（否则关下拉会连带关面板）
      event.stopPropagation()
      closeMenu(true)
      return
    }
    if (event.key === 'Tab') {
      // 不 preventDefault：把焦点同步交回触发器，浏览器随后按正常顺序继续 Tab
      closeMenu(true)
      return
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      moveActive(event.key === 'ArrowDown' ? 1 : -1)
      return
    }
    if (event.key === 'Home' || event.key === 'End') {
      event.preventDefault()
      setActiveIndex(findEnabledIndex(
        options,
        event.key === 'Home' ? 0 : options.length - 1,
        event.key === 'Home' ? 1 : -1,
      ))
      return
    }
    if (event.key === 'Enter' || event.key === ' ' || event.key === 'Spacebar') {
      event.preventDefault()
      if (activeIndex >= 0) selectOption(activeIndex)
    }
  }, [activeIndex, closeMenu, moveActive, options, selectOption])

  const triggerClassName = [
    'm3-select',
    variant === 'field' ? 'is-field' : '',
    active ? 'is-selected' : '',
    'm3-select-trigger',
    className,
  ].filter(Boolean).join(' ')

  const menuStyle = placement
    ? {
        left: placement.left,
        top: placement.top,
        minWidth: placement.minWidth,
        maxWidth: placement.maxWidth,
      }
    : { left: 0, top: 0, minWidth: MENU_MIN_WIDTH, visibility: 'hidden' as const }

  return (
    <>
      <button
        ref={triggerRef}
        type="button"
        className={triggerClassName}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? listboxId : undefined}
        aria-label={ariaLabel}
        title={title}
        disabled={disabled}
        data-testid={testid}
        {...dataAttributes}
        onClick={handleTriggerClick}
        onKeyDown={handleTriggerKeyDown}
      >
        <span className="m3-select-label">{selectedLabel}</span>
      </button>
      {open && createPortal(
        <div ref={menuRef} className="m3-menu m3-select-menu" style={menuStyle}>
          <div
            ref={listRef}
            id={listboxId}
            role="listbox"
            tabIndex={-1}
            aria-label={ariaLabel}
            aria-activedescendant={activeIndex >= 0 ? optionId(activeIndex) : undefined}
            className="m3-select-list"
            style={{ maxHeight: listMaxHeight }}
            onKeyDown={handleListKeyDown}
          >
            {options.map((option, index) => {
              const selected = option.value === value
              return (
                <button
                  key={option.value === '' ? `__empty_${index}` : option.value}
                  type="button"
                  role="option"
                  id={optionId(index)}
                  aria-selected={selected}
                  aria-disabled={option.disabled ? true : undefined}
                  disabled={option.disabled}
                  tabIndex={-1}
                  className={'m3-menu-item' + (index === activeIndex ? ' is-active' : '')}
                  onMouseEnter={() => { if (!option.disabled) setActiveIndex(index) }}
                  // 选项不接管焦点：焦点留在 listbox 上（scrollbar 拖动不受影响）
                  onMouseDown={(event) => {
                    if ((event.target as HTMLElement).closest('[role="option"]')) event.preventDefault()
                  }}
                  onClick={() => selectOption(index)}
                >
                  <span className="m3-menu-check" aria-hidden="true">
                    {selected && (
                      <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                        <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                      </svg>
                    )}
                  </span>
                  <span className="m3-select-option-label">{option.label}</span>
                </button>
              )
            })}
          </div>
        </div>,
        document.body,
      )}
    </>
  )
}
