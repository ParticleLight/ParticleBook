import { create } from 'zustand'
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
  importSources: () => Promise<{ imported: number; total: number }>
  toggleSource: (id: number) => Promise<void>
  deleteSource: (id: number) => Promise<void>
  clearAllSources: () => Promise<void>
  search: (keyword: string, page?: number) => Promise<void>
  clearSearch: () => void
  startDownload: (sourceId: number, bookUrl: string, bookName: string, format?: string) => Promise<number>
  resetDownload: () => void
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
    const result = await window.electronAPI.importBookSources()
    await get().loadSources()
    return result
  },

  toggleSource: async (id: number) => {
    await window.electronAPI.toggleBookSource(id)
    set({ sources: get().sources.map((s) => (s.id === id ? { ...s, enabled: !s.enabled } : s)) })
  },

  deleteSource: async (id: number) => {
    await window.electronAPI.deleteBookSource(id)
    set({ sources: get().sources.filter((s) => s.id !== id) })
  },

  clearAllSources: async () => {
    await window.electronAPI.clearAllBookSources()
    set({ sources: [] })
  },

  search: async (keyword: string, page = 1) => {
    set({ isSearching: true, searchKeyword: keyword, searchPage: page, searchError: null })
    try {
      const result = await window.electronAPI.searchBooks(keyword, page)
      if (result.error) {
        set({ searchResults: [], searchError: result.error })
      } else {
        set({ searchResults: result.results || [], searchError: null })
      }
    } catch (e: any) {
      console.error('Search failed:', e)
      set({ searchResults: [], searchError: e?.message || '搜索失败' })
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
}))
