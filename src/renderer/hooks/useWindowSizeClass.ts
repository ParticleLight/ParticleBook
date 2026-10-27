import { useEffect, useState } from 'react'

// ── M3 window size class ────────────────────────────────────────────
// Material 3 把窗口宽度分成 compact / medium / expanded 三档。导航形态随档位
// 变化（bottom bar / navigation rail / navigation drawer），本应用落在：
//   compact  (< 640px)   顶栏汉堡 + 模态导航抽屉
//   medium   (640–839px) 80px navigation rail（书柜等长列表走临时模态抽屉）
//   expanded (>= 840px)  常驻 248px navigation drawer（改造前的行为）
//
// 用 matchMedia 而不是 resize 监听：媒体查询只在跨过阈值时触发一次，不随每帧
// 拖拽窗口回调，成本更低；两个查询各自独立监听，任一变化都能重算出档位。
// SSR / 非浏览器环境（typeof window === 'undefined'）返回 'expanded'，与改造
// 前「总是有常驻抽屉」一致，不会在无水合环境里闪一下 rail。

export type WindowSizeClass = 'compact' | 'medium' | 'expanded'

export const MEDIUM_MIN_WIDTH = 640
export const EXPANDED_MIN_WIDTH = 840

const MEDIUM_QUERY = `(min-width: ${MEDIUM_MIN_WIDTH}px)`
const EXPANDED_QUERY = `(min-width: ${EXPANDED_MIN_WIDTH}px)`

function classForWidth(width: number): WindowSizeClass {
  if (width >= EXPANDED_MIN_WIDTH) return 'expanded'
  if (width >= MEDIUM_MIN_WIDTH) return 'medium'
  return 'compact'
}

// 初始值用 window.innerWidth（首帧就有正确档位，避免 rail→drawer 的闪动）；
// 挂载后的 effect 再用 matchMedia 校正，两者在正常情况下一致。
function initialSizeClass(): WindowSizeClass {
  if (typeof window === 'undefined') return 'expanded'
  const width = window.innerWidth
  return Number.isFinite(width) && width > 0 ? classForWidth(width) : 'expanded'
}

export function useWindowSizeClass(): WindowSizeClass {
  const [sizeClass, setSizeClass] = useState<WindowSizeClass>(initialSizeClass)

  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return
    const mediumQuery = window.matchMedia(MEDIUM_QUERY)
    const expandedQuery = window.matchMedia(EXPANDED_QUERY)

    const sync = () => {
      const next: WindowSizeClass = expandedQuery.matches
        ? 'expanded'
        : mediumQuery.matches
          ? 'medium'
          : 'compact'
      // 只在档位真正变化时 setState，避免拖拽窗口时每帧重渲染
      setSizeClass((prev) => (prev === next ? prev : next))
    }

    // render 与 effect 之间窗口可能已经变了，先同步一次
    sync()
    mediumQuery.addEventListener('change', sync)
    expandedQuery.addEventListener('change', sync)
    return () => {
      mediumQuery.removeEventListener('change', sync)
      expandedQuery.removeEventListener('change', sync)
    }
  }, [])

  return sizeClass
}
