// ── PB-022：ProgressController —— 阅读位置的读写封装 ──────────────────
//
// getLocator / goTo 直接复用 ReaderAdapter（EPUB cfi、TXT 字符偏移、PDF 页码、
// HTML 滚动百分比都由 adapter 归一化）。调用方（ReaderView 的 PB-006 跳转、
// 进度条、目录跳转、跨书搜索跳转）不再各自拼 navigateTo/seekTo。
//
// 纯函数式、无 React 依赖；adapter 由调用方持有（ReaderView useMemo）。

import type { ReaderAdapter, ReaderLocator } from './types'

export interface ProgressSnapshot {
  locator: ReaderLocator | null
  /** 滚动百分比（仅 scroll locator 有；其它格式返回 null）。 */
  percent: number | null
  /** PDF/CBZ 页码（仅 pdf locator 有）。 */
  page: number | null
}

export interface ProgressController {
  getLocator(): ReaderLocator | null
  getSnapshot(): ProgressSnapshot
  /** 跳到指定位置；返回渲染器是否受理。 */
  goTo(locator: ReaderLocator): boolean
  /** 异步版本（给 pendingLocator 这类等待渲染器挂载的场景用）。 */
  applyPending(locator: ReaderLocator): Promise<boolean>
}

export function createProgressController(adapter: ReaderAdapter | null): ProgressController {
  const getLocator = () => (adapter ? adapter.getLocator() : null)

  return {
    getLocator,
    getSnapshot(): ProgressSnapshot {
      const locator = getLocator()
      if (!locator) return { locator: null, percent: null, page: null }
      const percent = locator.kind === 'scroll' && typeof locator.percent === 'number'
        ? locator.percent
        : null
      const page = locator.kind === 'pdf' && typeof locator.page === 'number'
        ? locator.page
        : null
      return { locator, percent, page }
    },
    goTo(locator: ReaderLocator): boolean {
      if (!adapter) return false
      try {
        return adapter.goTo(locator)
      } catch (error) {
        console.error('ProgressController.goTo failed:', error)
        return false
      }
    },
    async applyPending(locator: ReaderLocator): Promise<boolean> {
      // 渲染器可能刚挂载，交给调用方决定延迟；这里只负责调用 + 兜异常。
      return this.goTo(locator)
    },
  }
}
