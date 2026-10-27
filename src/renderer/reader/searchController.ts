// ── PB-022：SearchController —— 基于 ReaderAdapter 的搜索控制器 ──────
//
// 数据层搜索（adapter.search / adapter.getTextModel），不画高亮：
//   * 现有渲染器各自的搜索 UI（readerStore.searchQuery + 渲染器内部高亮）保持不动；
//   * 本控制器供"跨书搜索跳转"、无渲染器搜索实现时的兜底、以及将来的共享
//     搜索栏使用。goToMatch 在没有 locator 时按格式回退：
//       txt/mobi → text offset；html/md/fb2 → scroll 百分比；EPUB/PDF → false。
//
// 无 React 依赖；ReaderControls 用 useMemo 创建一个实例（Enter 时兜底跳转）。

import type { ReaderAdapter, ReaderLocator, ReaderSearchMatch } from './types'

export interface SearchState {
  query: string
  matches: ReaderSearchMatch[]
  /** 当前命中下标；无命中为 -1。 */
  index: number
  /** adapter 文本模型范围：document（TXT/HTML 全书）或 chapter（EPUB 当前章）。 */
  scope: 'document' | 'chapter' | null
  format: string
}

export interface SearchController {
  getState(): SearchState
  subscribe(listener: () => void): () => void
  setQuery(query: string): ReaderSearchMatch[]
  clear(): void
  current(): ReaderSearchMatch | null
  next(): ReaderSearchMatch | null
  prev(): ReaderSearchMatch | null
  goToMatch(match: ReaderSearchMatch): boolean
  goToCurrent(): boolean
  /** "3/17" 形式的位置文案（没有命中返回空串）。 */
  describe(): string
}

export function createSearchController(
  adapter: ReaderAdapter | null,
  options: { limit?: number } = {},
): SearchController {
  const limit = Math.max(1, Math.min(options.limit ?? 50, 200))
  const listeners = new Set<() => void>()
  let state: SearchState = {
    query: '',
    matches: [],
    index: -1,
    scope: null,
    format: adapter?.format ?? '',
  }
  const emit = () => { for (const listener of listeners) { try { listener() } catch { /* ignore */ } } }

  const goToMatch = (match: ReaderSearchMatch): boolean => {
    if (!adapter) return false
    if (match.locator) return adapter.goTo(match.locator)
    // 没有 locator（EPUB 章节内 / 某些 DOM 场景）：按格式构造可跳转位置
    try {
      if (adapter.format === 'txt' || adapter.format === 'mobi') {
        const locator: ReaderLocator = {
          version: 1,
          kind: 'text',
          offset: match.start,
          length: match.length,
        }
        return adapter.goTo(locator)
      }
      if (['html', 'markdown', 'fb2', 'docx'].includes(adapter.format)) {
        const model = adapter.getTextModel()
        if (!model || model.length <= 0) return false
        const percent = Math.min(100, Math.max(0, (match.start / model.length) * 100))
        const locator: ReaderLocator = { version: 1, kind: 'scroll', percent }
        return adapter.goTo(locator)
      }
    } catch (error) {
      console.error('SearchController.goToMatch failed:', error)
    }
    return false
  }

  const controller: SearchController = {
    getState: () => state,
    subscribe(listener: () => void) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    setQuery(query: string) {
      const model = adapter ? adapter.getTextModel() : null
      const matches = adapter ? adapter.search(query, { limit }) : []
      state = { query, matches, index: matches.length > 0 ? 0 : -1, scope: model?.scope ?? null, format: adapter?.format ?? '' }
      emit()
      return matches
    },
    clear() {
      state = { query: '', matches: [], index: -1, scope: null, format: adapter?.format ?? '' }
      emit()
    },
    current() {
      return state.index >= 0 && state.index < state.matches.length ? state.matches[state.index] : null
    },
    next() {
      if (state.matches.length === 0) return null
      state = { ...state, index: (state.index + 1) % state.matches.length }
      emit()
      return controller.current()
    },
    prev() {
      if (state.matches.length === 0) return null
      state = { ...state, index: state.index <= 0 ? state.matches.length - 1 : state.index - 1 }
      emit()
      return controller.current()
    },
    goToMatch,
    goToCurrent() {
      const match = controller.current()
      return match ? goToMatch(match) : false
    },
    describe() {
      return state.matches.length > 0 ? (state.index + 1) + '/' + state.matches.length : ''
    },
  }
  return controller
}
