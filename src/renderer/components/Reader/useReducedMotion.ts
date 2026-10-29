import { useEffect, useState } from 'react'

// U13（review ux-i18n，2026-10-29）：ReaderView 里有几处**内联** transition
// （侧栏 / 标注面板的 opacity 过渡）。globals.css 里的 prefers-reduced-motion 块
// 覆盖不到内联样式，所以这里在组件内判断一次并直接输出 transition: none。
const QUERY = '(prefers-reduced-motion: reduce)'

/** 用户是否开启了「减少动效」；窗口/系统设置变化时实时更新。 */
export function usePrefersReducedMotion(): boolean {
  const [reduced, setReduced] = useState(() => {
    try {
      return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
        ? window.matchMedia(QUERY).matches
        : false
    } catch {
      return false
    }
  })

  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return
    const media = window.matchMedia(QUERY)
    const onChange = () => setReduced(media.matches)
    onChange()
    media.addEventListener('change', onChange)
    return () => media.removeEventListener('change', onChange)
  }, [])

  return reduced
}

/** 内联 transition 取值：减少动效时 `none`，否则用传入的过渡。 */
export function motionTransition(css: string, reduced: boolean): string {
  return reduced ? 'none' : css
}
