// ── PB-009-C：分屏阅读的「每书会话」（readerStore 的会话工厂）────────────
//
// 背景：现有 readerStore 是**单本当前书**的全局会话（progress/bookmarks/
// highlights/search/sidebar/TTS…）。两本书并排时如果共用它，两边会互相覆盖
// 进度与选区。本文件提供一个轻量的 per-book 会话工厂，供 SplitReaderView
// 用「两个独立 session」渲染双栏；**不修改** readerStore / ReaderView / App。
//
// 设计边界（本轮的取舍）：
//   * 只承载分屏真正需要隔离的状态：进度（progress/cfi/page/scrollPosition）、
//     选区文本、搜索查询/结果、当前激活 pane 与 TTS 归属；
//   * bookmark/highlight/note 的 CRUD 仍走数据库 + 现有 store（下一轮接线时再
//     决定是「每 pane 各自订阅」还是「active pane 独占」）；
//   * 进度持久化走现有 bridge：updateReadingProgress / getReadingProgress，
//     去抖 500ms（与 readerStore 一致的节奏）。
//
// 用法（SplitReaderView 已经这样接）：
//   const primary = createReaderSession({ book, format: 'epub' })
//   const secondary = createReaderSession({ book, format: 'txt' })
//   await primary.init(); await secondary.init()
//   primary.store.getState().setProgress({ progress: 42, cfi })
//   useReaderSession(primary, (s) => s.progress)   // 组件里订阅
//   primary.dispose()                              // 卸载时 flush + 清定时器

import { createStore, type StoreApi } from 'zustand/vanilla'
import { useStore } from 'zustand'

export type SplitReadableFormat = 'epub' | 'txt'

/** 本轮分屏实际支持的格式（PDF / CBZ / CBR / MOBI / HTML 等暂不支持）。 */
export const SPLIT_READABLE_FORMATS: readonly SplitReadableFormat[] = ['epub', 'txt']

export function isSplitReadable(format: string | undefined | null): format is SplitReadableFormat {
  if (!format) return false
  const normalized = format.toLowerCase()
  return SPLIT_READABLE_FORMATS.includes(normalized as SplitReadableFormat)
}

export type SplitUnsupportedReason = 'pdf' | 'comic' | 'other'

/** 给 UI 用的「为什么不能分屏」原因；可读文案由调用方本地化。 */
export function splitUnsupportedReason(format: string | undefined | null): SplitUnsupportedReason {
  const normalized = (format || '').toLowerCase()
  if (normalized === 'pdf') return 'pdf'
  if (normalized === 'cbz' || normalized === 'cbr') return 'comic'
  return 'other'
}

export interface ReaderSessionProgress {
  progress: number          // 0..100
  cfi?: string              // EPUB 当前位置
  page?: number             // 分页类格式（PDF 等，本轮不会用到）
  scrollPosition?: number   // TXT 滚动位置（px）
}

export interface ReaderSessionBook {
  id: number
  title: string
  format: string
  filePath: string
}

export interface ReaderSessionState {
  bookId: number
  title: string
  format: string
  filePath: string

  /** 进度：pane 内部更新，去抖写库 */
  progress: ReaderSessionProgress
  /** 当前 pane 选区文本（与另一侧完全隔离） */
  selectionText: string
  /** 每 pane 自己的搜索状态（不共享、不互相打断） */
  searchQuery: string
  searchResults: unknown[]
  searchError: string

  /** 正在加载/出错（pane 内容层） */
  loading: boolean
  error: string
  /** 是否是当前激活 pane（键盘/TTS 归属） */
  active: boolean
  /** 当前是否由本会话持有 TTS */
  ttsOwner: boolean

  init: () => Promise<void>
  setProgress: (patch: Partial<ReaderSessionProgress>, options?: { persist?: boolean }) => void
  flushProgress: () => Promise<void>
  setSelection: (text: string) => void
  clearSelection: () => void
  setSearch: (query: string, results?: unknown[], error?: string) => void
  clearSearch: () => void
  setActive: (active: boolean) => void
  claimTts: () => boolean
  releaseTts: () => void
  setLoading: (loading: boolean) => void
  setError: (error: string) => void
}

export interface ReaderSession {
  readonly bookId: number
  readonly format: string
  readonly store: StoreApi<ReaderSessionState>
  /** 读一次进度（不重复调用也安全） */
  init: () => Promise<void>
  /** flush + 取消待写 + 标记销毁（之后 setProgress 不再写库） */
  dispose: () => void
}

export interface CreateReaderSessionOptions {
  book: ReaderSessionBook
  /** 关闭自动写库（测试 / 只读预览用） */
  autoSave?: boolean
  /** 进度写库失败时的回调（默认 console.error） */
  onSaveError?: (error: unknown) => void
}

const SAVE_DEBOUNCE_MS = 500

function toSessionProgress(record: PbProgressRecord | null | undefined): ReaderSessionProgress {
  return {
    progress: typeof record?.progress === 'number' ? record.progress : 0,
    cfi: record?.cfi || undefined,
    page: typeof record?.page === 'number' ? record.page : undefined,
    scrollPosition: typeof record?.scroll_position === 'number' ? record.scroll_position : undefined,
  }
}

export function createReaderSession(options: CreateReaderSessionOptions): ReaderSession {
  const { book, autoSave = true, onSaveError } = options
  let disposed = false
  let saveTimer: ReturnType<typeof setTimeout> | null = null
  let initPromise: Promise<void> | null = null

  const persist = async (state: ReaderSessionState) => {
    if (!autoSave || disposed) return
    try {
      await window.electronAPI.updateReadingProgress(state.bookId, {
        progress: state.progress.progress,
        cfi: state.progress.cfi,
        page: state.progress.page,
        scrollPosition: state.progress.scrollPosition,
      })
    } catch (error) {
      if (onSaveError) onSaveError(error)
      else console.error('readerSession: save progress failed', error)
    }
  }

  const store = createStore<ReaderSessionState>((set, get) => ({
    bookId: book.id,
    title: book.title,
    format: book.format,
    filePath: book.filePath,

    progress: { progress: 0 },
    selectionText: '',
    searchQuery: '',
    searchResults: [],
    searchError: '',
    loading: true,
    error: '',
    active: false,
    ttsOwner: false,

    init: async () => {
      if (initPromise) return initPromise
      initPromise = (async () => {
        try {
          const record = await window.electronAPI.getReadingProgress(book.id)
          if (!disposed) set({ progress: toSessionProgress(record), loading: false })
        } catch (error) {
          if (!disposed) set({ loading: false, error: 'progress_load_failed' })
          console.error('readerSession: load progress failed', error)
        }
      })()
      return initPromise
    },

    // 进度更新：默认去抖写库；persist:false 只更新内存（例如恢复滚动位置时）
    setProgress: (patch, opts) => {
      if (disposed) return
      const next: ReaderSessionProgress = { ...get().progress, ...patch }
      set({ progress: next })
      if (!autoSave || opts?.persist === false) return
      if (saveTimer) clearTimeout(saveTimer)
      saveTimer = setTimeout(() => {
        saveTimer = null
        void persist(get())
      }, SAVE_DEBOUNCE_MS)
    },

    flushProgress: async () => {
      if (saveTimer) {
        clearTimeout(saveTimer)
        saveTimer = null
      }
      await persist(get())
    },

    setSelection: (text) => { if (!disposed) set({ selectionText: text }) },
    clearSelection: () => { if (!disposed) set({ selectionText: '' }) },

    setSearch: (query, results, error) => {
      if (disposed) return
      set({ searchQuery: query, searchResults: results ?? [], searchError: error ?? '' })
    },
    clearSearch: () => { if (!disposed) set({ searchQuery: '', searchResults: [], searchError: '' }) },

    setActive: (active) => {
      if (disposed) return
      set({ active })
      // 失活的一侧立即放弃 TTS 归属，避免两个 pane 同时"以为"自己在朗读
      if (!active && get().ttsOwner) set({ ttsOwner: false })
    },

    // 只有 active 的 pane 可以拿 TTS；返回 false 表示被另一侧占用/自己不是激活 pane
    claimTts: () => {
      if (disposed) return false
      const state = get()
      if (!state.active) return false
      set({ ttsOwner: true })
      return true
    },
    releaseTts: () => { if (!disposed) set({ ttsOwner: false }) },

    setLoading: (loading) => { if (!disposed) set({ loading }) },
    setError: (error) => { if (!disposed) set({ error, loading: false }) },
  }))

  return {
    bookId: book.id,
    format: book.format,
    store,
    init: () => store.getState().init(),
    dispose: () => {
      if (disposed) return
      const state = store.getState()
      if (saveTimer) {
        clearTimeout(saveTimer)
        saveTimer = null
      }
      void persist(state)
      disposed = true
    },
  }
}

/** 组件里订阅某个会话（等价于 useStore(store, selector)）。 */
export function useReaderSession<T>(session: ReaderSession, selector: (state: ReaderSessionState) => T): T {
  return useStore(session.store, selector)
}
