// ── PB-021-C：标注 store（annotations API 的渲染层数据层）────────────
//
// 目标（docs/BACKLOG.md PB-021）：书签 / 高亮 / 笔记 / PDF 标注共用一套
// 数据结构与读写入口，Reader 组件不再各写一套 map。本 store 只管数据：
//
//   load(bookId?)        拉取（bookId 缺省 / <= 0 = 全库）
//   ensureLoaded(bookId) 只在没拉过时拉（侧栏打开时用）
//   add / update / remove 写路径（成功即本地 upsert，无需整表重拉）
//   byType / forBook / visible  按类型 / 书 / 当前筛选器取数
//   loading / error / typeFilter / sort  视图状态
//
// 与 readerStore 的关系（并行双写期，刻意解耦）：
//   * 本 store 不读也不写 readerStore；现有侧栏继续用 readerStore 的
//     bookmarks / highlights / notes，行为不变。
//   * C++ 侧 annotations 写入会双写到旧表（见 DatabaseService.h），所以两个
//     视图对「新 API 产生的标注」是一致的；旧 API 入口产生的标注暂不回流到
//     annotations（PB-021-D 切换 Reader 组件后才统一），这是已知过渡缺口。
//   * 需要把两边同一行对上时用 legacyIdOf() / legacyIds()，不要假设 id 相等。
//
// 桥接契约（bridge/contract.mjs → electron.d.ts）：
//   getAnnotations(bookId?) / addAnnotation(annotation) /
//   updateAnnotation(id, patch) / deleteAnnotation(id)
//   add / update 在 C++ 校验失败时返回 null（不抛），本 store 会置 error 并
//   返回 null，调用方按「保存失败」提示即可。
//
// 线程/并发：写操作不做乐观更新 —— 等 C++ 返回落库行再并入 state，避免本地
// id / legacyId 与库里不一致。load 用递增序号忽略过期响应（快速切书时）。

import { create } from 'zustand'
import i18n from '../i18n'
import {
  countByType,
  filterAnnotations,
  findAnnotation,
  groupByBook,
  isAnnotationType,
  legacyIdMap,
  sortAnnotations,
  type Annotation,
  type AnnotationInput,
  type AnnotationSort,
  type AnnotationType,
  type AnnotationTypeFilter,
  type Locator,
} from '../types/annotation'

// 过期 load 的判别号（模块级、不进 state：组件不需要它）。
let loadSequence = 0

function isAnnotationRow(value: unknown): value is Annotation {
  if (!value || typeof value !== 'object') return false
  const row = value as Partial<Annotation>
  return (
    typeof row.id === 'number' &&
    typeof row.bookId === 'number' &&
    isAnnotationType(row.type) &&
    typeof row.locator === 'object' &&
    row.locator !== null
  )
}

function normalizeRows(raw: unknown): Annotation[] {
  if (!Array.isArray(raw)) return []
  return raw.filter(isAnnotationRow)
}

function replaceById(list: Annotation[], next: Annotation): Annotation[] {
  const index = list.findIndex((item) => item.id === next.id)
  if (index < 0) return [...list, next]
  const copy = [...list]
  copy[index] = next
  return copy
}

function removeById(list: Annotation[], id: number): Annotation[] {
  return list.filter((item) => item.id !== id)
}

function errorMessage(key: string, error: unknown): string {
  const detail = error instanceof Error ? error.message : String(error)
  return i18n.t(key, { error: detail })
}

/** 全库范围（load 不传书 / 传 0）在内部用 0 表示。 */
const ALL_BOOKS = 0

export interface AnnotationState {
  /** 当前已加载范围内的标注（已按 sort 排序；load 全库时就是全库）。 */
  annotations: Annotation[]
  /** 按 bookId 分组的只读视图（与 annotations 同步维护）。 */
  byBook: Record<number, Annotation[]>
  /** 已通过 load/ensureLoaded 拉过的书 id。 */
  loadedBooks: number[]
  /** 是否已做过一次全库 load（ensureLoaded 据此跳过逐书请求）。 */
  loadedAll: boolean
  loading: boolean
  /** 最近一次失败的用户可读文案（i18n 后的字符串）；成功时清空。 */
  error: string | null
  /** 侧栏/筛选器状态：只看某类型或全部。 */
  typeFilter: AnnotationTypeFilter
  /** 排序口径：书内位置 / 创建时间 / 更新时间。 */
  sort: AnnotationSort
  /** 最近一次成功 load 的时间戳（ms），便于调试/刷新判断。 */
  lastLoadedAt: number | null

  load: (bookId?: number) => Promise<Annotation[]>
  ensureLoaded: (bookId: number) => Promise<Annotation[]>
  add: (input: AnnotationInput) => Promise<Annotation | null>
  update: (id: number, patch: Partial<AnnotationInput>) => Promise<Annotation | null>
  remove: (id: number) => Promise<boolean>

  setTypeFilter: (filter: AnnotationTypeFilter) => void
  setSort: (sort: AnnotationSort) => void
  clear: () => void

  /** id → 标注（不存在返回 undefined）。 */
  getById: (id: number) => Annotation | undefined
  /** 某本书的标注；types 缺省 = 全部类型。 */
  forBook: (bookId: number, types?: readonly AnnotationType[]) => Annotation[]
  /** 某类型的标注（'all' = 当前范围全部）；bookId 缺省 = 不按书过滤。 */
  byType: (type: AnnotationTypeFilter, bookId?: number) => Annotation[]
  /** 应用当前 typeFilter 后的列表；bookId 缺省 = 不按书过滤。 */
  visible: (bookId?: number) => Annotation[]
  /** 各类型计数（按当前范围/书过滤）。 */
  counts: (bookId?: number) => Record<AnnotationType, number>
  /** annotation.id → 旧表行 id（并行双写期对账）。 */
  legacyIdOf: (id: number) => number | undefined
  legacyIds: (bookId?: number) => Record<number, number>
}

export const useAnnotationStore = create<AnnotationState>((set, get) => ({
  annotations: [],
  byBook: {},
  loadedBooks: [],
  loadedAll: false,
  loading: false,
  error: null,
  typeFilter: 'all',
  sort: 'position',
  lastLoadedAt: null,

  load: async (bookId) => {
    const scope = typeof bookId === 'number' && bookId > 0 ? bookId : ALL_BOOKS
    const sequence = ++loadSequence
    set({ loading: true, error: null })

    try {
      const raw = await window.electronAPI.getAnnotations(scope > 0 ? scope : undefined)
      const fetched = normalizeRows(raw)
      // 过期响应：期间又发起了 load（切书），丢掉旧结果，但把数据返回给调用方。
      if (sequence !== loadSequence) return fetched

      set((state) => {
        const annotations =
          scope > 0
            ? sortAnnotations(
                [...state.annotations.filter((item) => item.bookId !== scope), ...fetched],
                state.sort,
              )
            : sortAnnotations(fetched, state.sort)
        const loadedBooks =
          scope > 0
            ? state.loadedBooks.includes(scope)
              ? state.loadedBooks
              : [...state.loadedBooks, scope]
            : []
        return {
          annotations,
          byBook: groupByBook(annotations),
          loadedBooks,
          loadedAll: scope > 0 ? state.loadedAll : true,
          loading: false,
          error: null,
          lastLoadedAt: Date.now(),
        }
      })
      return fetched
    } catch (e) {
      if (sequence === loadSequence) {
        set({ loading: false, error: errorMessage('加载标注失败：{{error}}', e) })
      }
      console.error('Failed to load annotations:', e)
      return []
    }
  },

  ensureLoaded: async (bookId) => {
    const state = get()
    if (state.loadedAll || state.loadedBooks.includes(bookId)) return state.forBook(bookId)
    return state.load(bookId)
  },

  add: async (input) => {
    set({ error: null })
    try {
      const created = await window.electronAPI.addAnnotation(input)
      if (!created) {
        // C++ 拒绝（type / locator / 颜色 / 书 id 不合法）：不落库、不动 state。
        set({ error: i18n.t('标注未保存：数据无效') })
        return null
      }
      set((state) => {
        const annotations = sortAnnotations(replaceById(state.annotations, created), state.sort)
        const loadedBooks = state.loadedBooks.includes(created.bookId)
          ? state.loadedBooks
          : [...state.loadedBooks, created.bookId]
        return { annotations, byBook: groupByBook(annotations), loadedBooks, error: null }
      })
      return created
    } catch (e) {
      console.error('Failed to add annotation:', e)
      set({ error: errorMessage('保存标注失败：{{error}}', e) })
      return null
    }
  },

  update: async (id, patch) => {
    set({ error: null })
    try {
      const updated = await window.electronAPI.updateAnnotation(id, patch)
      if (!updated) {
        set({ error: i18n.t('标注更新失败：标注无效或已被删除') })
        return null
      }
      set((state) => {
        // bookId 可能被 patch 改掉：先删旧行再按新行的书并入。
        const without = removeById(state.annotations, updated.id)
        const annotations = sortAnnotations(replaceById(without, updated), state.sort)
        return { annotations, byBook: groupByBook(annotations), error: null }
      })
      return updated
    } catch (e) {
      console.error('Failed to update annotation:', e)
      set({ error: errorMessage('更新标注失败：{{error}}', e) })
      return null
    }
  },

  remove: async (id) => {
    set({ error: null })
    try {
      await window.electronAPI.deleteAnnotation(id)
      set((state) => {
        const annotations = removeById(state.annotations, id)
        return { annotations, byBook: groupByBook(annotations), error: null }
      })
      return true
    } catch (e) {
      console.error('Failed to delete annotation:', e)
      set({ error: errorMessage('删除标注失败：{{error}}', e) })
      return false
    }
  },

  setTypeFilter: (typeFilter) => set({ typeFilter }),

  setSort: (sort) => {
    set((state) => {
      const annotations = sortAnnotations(state.annotations, sort)
      return { sort, annotations, byBook: groupByBook(annotations) }
    })
  },

  clear: () =>
    set({
      annotations: [],
      byBook: {},
      loadedBooks: [],
      loadedAll: false,
      loading: false,
      error: null,
      lastLoadedAt: null,
    }),

  getById: (id) => findAnnotation(get().annotations, id),

  forBook: (bookId, types) => filterAnnotations(get().byBook[bookId] ?? [], { types }),

  byType: (type, bookId) => {
    const scope = typeof bookId === 'number' && bookId > 0 ? bookId : 0
    return filterAnnotations(get().annotations, {
      bookId: scope,
      types: type === 'all' ? undefined : [type],
    })
  },

  visible: (bookId) => {
    const { annotations, typeFilter } = get()
    const scope = typeof bookId === 'number' && bookId > 0 ? bookId : 0
    return filterAnnotations(annotations, {
      bookId: scope,
      types: typeFilter === 'all' ? undefined : [typeFilter],
    })
  },

  counts: (bookId) => {
    const scope = typeof bookId === 'number' && bookId > 0 ? bookId : 0
    const inScope = filterAnnotations(get().annotations, { bookId: scope })
    return countByType(inScope)
  },

  legacyIdOf: (id) => {
    const annotation = findAnnotation(get().annotations, id)
    return annotation?.legacyId
  },

  legacyIds: (bookId) => {
    const scope = typeof bookId === 'number' && bookId > 0 ? bookId : 0
    const inScope = filterAnnotations(get().annotations, { bookId: scope })
    return legacyIdMap(inScope)
  },
}))

// ── 便于组件按需订阅的派生 selector（保持纯函数，不在 store 里缓存）────
//
// 用法：const bookmarks = useAnnotationStore((s) => s.forBook(bookId, ['bookmark']))
// 注意 zustand v5 的 selector 结果需保持引用稳定：这些函数每次返回新数组，
// 在组件里请配合 useShallow，或直接订阅 state 片段再用 useMemo。

/** 未使用的 locator 类型导出占位：给后续 Reader 组件做类型收窄用。 */
export type AnnotationLocator = Locator
