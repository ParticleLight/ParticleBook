import { create } from 'zustand'
import i18n from '../i18n'
import type { SearchResult, DownloadProgress, BookSourceInfo } from '../types/bookSource'

interface BookSourceState {
  sources: BookSourceInfo[]
  isLoading: boolean
  searchResults: SearchResult[]
  isSearching: boolean
  searchKeyword: string
  searchPage: number
  searchError: string | null
  downloadProgress: DownloadProgress | null
  isDownloading: boolean

  loadSources: () => Promise<void>
  importSources: () => Promise<{ imported: number } | null>
  toggleSource: (id: number) => Promise<void>
  deleteSource: (id: number) => Promise<void>
  clearAllSources: () => Promise<void>
  // PB-013-A：书源管理页用
  updateSource: (id: number, updates: Partial<PbBookSourceInput>) => Promise<boolean>
  insertSource: (source: PbBookSourceInput) => Promise<PbBookSource | null>
  importSourcesFromJson: (raw: string) => Promise<BookSourceImportSummary>
  // PB-013-B：批量操作（逐条调用 bridge，返回成功/失败计数）
  setEnabledMany: (ids: number[], enabled: boolean) => Promise<{ ok: number; failed: number }>
  deleteMany: (ids: number[]) => Promise<{ ok: number; failed: number }>
  search: (keyword: string, page?: number) => Promise<void>
  clearSearch: () => void
  // The C++ side starts the download on a worker and returns immediately;
  // results arrive via downloadProgress/downloadComplete events, so there is
  // no id to hand back (callers ignore the return value).
  startDownload: (sourceId: number, bookUrl: string, bookName: string, format?: string) => Promise<void>
  resetDownload: () => void
}

export interface BookSourceImportSummary {
  imported: number
  duplicates: number
  invalid: number
  failed: number
  // 解析/网络层面的错误（JSON 不合法、URL 拉取失败等）；条目级问题只计数
  error: string
}

// 从剪贴板 / URL / 文件读到的书源 JSON → 数组。
// 兼容三种常见形态：[...]、{ sources: [...] }、{ bookSources: [...] }；
// 单个对象也算一条（方便手动粘贴一个源）。
function extractSourceArray(raw: string): unknown[] {
  const parsed = JSON.parse(raw)
  if (Array.isArray(parsed)) return parsed
  if (parsed && typeof parsed === 'object') {
    const obj = parsed as Record<string, unknown>
    if (Array.isArray(obj.sources)) return obj.sources
    if (Array.isArray(obj.bookSources)) return obj.bookSources
    return [parsed]
  }
  return []
}

function normalizedUrl(value: unknown): string {
  return typeof value === 'string' ? value.trim().toLowerCase() : ''
}

export const useBookSourceStore = create<BookSourceState>((set, get) => ({
  sources: [],
  isLoading: false,
  searchResults: [],
  isSearching: false,
  searchKeyword: '',
  searchPage: 1,
  searchError: null,
  downloadProgress: null,
  isDownloading: false,

  loadSources: async () => {
    set({ isLoading: true })
    try {
      const sources = await window.electronAPI.getBookSources()
      set({ sources })
    } catch (e) {
      console.error('Failed to load book sources:', e)
    } finally {
      set({ isLoading: false })
    }
  },

  importSources: async () => {
    try {
      const result = await window.electronAPI.importBookSources()
      await get().loadSources()
      // 取消文件对话框时 C++ 返回 null —— 原样传给调用方，由其决定是否提示
      return result
    } catch (e) {
      console.error('importBookSources failed:', e)
      return null
    }
  },

  // 以下写操作都要 catch：invoke 失败会 reject（BridgeServer 侧 reject(new Error)），
  // 原先既不捕获也不提示，失败被吞成未处理的 Promise 拒绝，UI 只是"没反应"。
  toggleSource: async (id: number) => {
    try {
      await window.electronAPI.toggleBookSource(id)
      set({ sources: get().sources.map((s) => (s.id === id ? { ...s, enabled: !s.enabled } : s)) })
    } catch (e) {
      console.error('toggleBookSource failed:', e)
    }
  },

  deleteSource: async (id: number) => {
    try {
      await window.electronAPI.deleteBookSource(id)
      set({ sources: get().sources.filter((s) => s.id !== id) })
    } catch (e) {
      console.error('deleteBookSource failed:', e)
    }
  },

  clearAllSources: async () => {
    try {
      await window.electronAPI.clearAllBookSources()
      set({ sources: [] })
    } catch (e) {
      console.error('clearAllBookSources failed:', e)
    }
  },

  search: async (keyword: string, page = 1) => {
    set({ isSearching: true, searchKeyword: keyword, searchPage: page, searchError: null })
    try {
      // SearchAll returns a PLAIN ARRAY of results (BookSourceService::SearchAll
      // returns json::array()), not an { error, results } envelope. Treating it
      // as an envelope made this always resolve to [] and silently killed
      // online book-source search. Failures arrive as a rejected promise and
      // are handled by the catch below.
      const results = await window.electronAPI.searchBooks(keyword, page)
      set({ searchResults: results || [], searchError: null })
    } catch (e: any) {
      console.error('Search failed:', e)
      set({ searchResults: [], searchError: e?.message || i18n.t('搜索失败') })
    } finally {
      set({ isSearching: false })
    }
  },

  clearSearch: () => set({ searchResults: [], searchKeyword: '', searchPage: 1, searchError: null }),

  startDownload: async (sourceId, bookUrl, bookName, format = 'txt') => {
    set({ isDownloading: true, downloadProgress: null })
    // The C++ side runs the download on a background thread and returns
    // "started" immediately, so keep the listener alive until it reports
    // done/error — unsubscribing on the immediate return would drop every
    // progress/completion event (regression fixed here).
    await new Promise<void>((resolve) => {
      let settled = false
      let unsub: (() => void) | null = null
      let timer: ReturnType<typeof setTimeout> | null = null
      const finish = () => {
        if (settled) return
        settled = true
        if (timer) clearTimeout(timer)
        if (unsub) unsub()
        resolve()
      }
      unsub = window.electronAPI.onDownloadProgress((progress: DownloadProgress) => {
        set({ downloadProgress: progress })
        if (progress.status === 'done' || progress.status === 'error') finish()
      })
      // Safety net: never hang forever if the download thread dies silently.
      timer = setTimeout(finish, 10 * 60 * 1000)
      window.electronAPI.downloadBook(sourceId, bookUrl, bookName, format).catch(() => finish())
    })
    set({ isDownloading: false })
  },

  resetDownload: () => set({ downloadProgress: null, isDownloading: false }),

  // ── PB-013-A：单条更新 / 单条新增 / 批量导入 ─────────────────────────
  // 只把用户真正改动的键交给 C++（DatabaseService::UpdateBookSource 是逐键 merge），
  // 因此未知规则字段（Legado 里几十个字段）都不会被覆盖掉。
  updateSource: async (id, updates) => {
    try {
      await window.electronAPI.updateBookSource(id, updates)
      set({
        sources: get().sources.map((s) => (s.id === id ? { ...s, ...updates } as BookSourceInfo : s)),
      })
      return true
    } catch (e) {
      console.error('updateBookSource failed:', e)
      return false
    }
  },

  insertSource: async (source) => {
    try {
      const created = await window.electronAPI.insertBookSource(source)
      if (created && typeof created.id === 'number') {
        set({ sources: [...get().sources, created] })
        return created
      }
      return null
    } catch (e) {
      console.error('insertBookSource failed:', e)
      return null
    }
  },

  // ── PB-013-B：批量启用/禁用、批量删除 ───────────────────────────
  // updateBookSource(id, { enabled }) 是幂等的：已经是目标状态再写一遍也不会出错，
  // 比逐条 toggle 安全（toggle 会把「本来就想禁用」的源又打开）。
  setEnabledMany: async (ids, enabled) => {
    let ok = 0
    let failed = 0
    for (const id of ids) {
      try {
        await window.electronAPI.updateBookSource(id, { enabled })
        ok++
      } catch (e) {
        console.error('updateBookSource(enabled) failed:', e)
        failed++
      }
    }
    if (ok > 0) await get().loadSources()
    return { ok, failed }
  },

  deleteMany: async (ids) => {
    let ok = 0
    let failed = 0
    for (const id of ids) {
      try {
        await window.electronAPI.deleteBookSource(id)
        ok++
      } catch (e) {
        console.error('deleteBookSource failed:', e)
        failed++
      }
    }
    if (ok > 0) await get().loadSources()
    return { ok, failed }
  },

  // 从任意来源拿到的 JSON 文本 → 批量导入；按 bookSourceUrl 去重（与现有列表比较，
  // 以及本批次内部去重）。返回逐项计数，供 UI 显示「导入 N / 重复 M / 无效 K」。
  importSourcesFromJson: async (raw) => {
    const summary: BookSourceImportSummary = { imported: 0, duplicates: 0, invalid: 0, failed: 0, error: '' }
    let entries: unknown[] = []
    try {
      entries = extractSourceArray(raw)
    } catch {
      summary.error = 'invalid_json'
      return summary
    }
    if (entries.length === 0) {
      summary.error = 'empty'
      return summary
    }

    const known = new Set(get().sources.map((s) => normalizedUrl(s.bookSourceUrl)).filter(Boolean))
    for (const entry of entries) {
      if (!entry || typeof entry !== 'object' || Array.isArray(entry)) { summary.invalid++; continue }
      const source = { ...(entry as Record<string, unknown>) } as PbBookSourceInput
      const name = typeof source.bookSourceName === 'string' ? source.bookSourceName.trim() : ''
      const url = typeof source.bookSourceUrl === 'string' ? source.bookSourceUrl.trim() : ''
      if (!name && !url) { summary.invalid++; continue }
      const key = url.toLowerCase()
      if (key && known.has(key)) { summary.duplicates++; continue }
      // 保留原始字段（含 Legado 的未知规则字段），只补齐/规整必填项
      source.bookSourceName = name
      source.bookSourceUrl = url
      if (typeof source.enabled !== 'boolean') source.enabled = true
      try {
        const created = await window.electronAPI.insertBookSource(source)
        if (created && typeof created.id === 'number') {
          set({ sources: [...get().sources, created] })
          summary.imported++
          if (key) known.add(key)
        } else {
          summary.failed++
        }
      } catch (e) {
        console.error('insertBookSource failed during import:', e)
        summary.failed++
      }
    }
    return summary
  },
}))
