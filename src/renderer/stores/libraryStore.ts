import { create } from 'zustand'
import { useSettingsStore } from './settingsStore'

// ── PB-017: 封面内存 LRU 缓存 + 并发去重 ─────────────────────────────
// 书库卡片（BookCard / BookList 的 ListCover）都直接调
// window.electronAPI.getCoverImage(bookId)；5000 本时即使虚拟化只渲染可见
// 卡片，来回滚动/筛选仍会反复请求同一封面。这里在 store 加载时把该 bridge
// 方法包一层：
//   * 命中内存 Map（字符串 / data URL）直接返回，不发 bridge；
//   * 同 bookId 的并发请求合并成一个 in-flight Promise；
//   * 超过上限按插入顺序淘汰最久未用（get 时 refresh 顺序 = LRU）；
//   * 缓存值不写入磁盘，只活在渲染进程内存里，退出即释放。
// 这样 BookCard 不需要改动（不在本次改动范围），也天然对列表视图生效。
const COVER_CACHE_MAX = 300
const coverCache = new Map<number, string>()
const coverInflight = new Map<number, Promise<string | null>>()
let coverHits = 0
let coverMisses = 0
let coverEvictions = 0

export interface CoverCacheStats {
  size: number
  max: number
  hits: number
  misses: number
  evictions: number
  inflight: number
}

function touchCover(bookId: number, dataUrl: string): void {
  // 重新插入到 Map 末尾 = 最近使用；Map 迭代顺序作为 LRU 顺序。
  if (coverCache.has(bookId)) coverCache.delete(bookId)
  coverCache.set(bookId, dataUrl)
  while (coverCache.size > COVER_CACHE_MAX) {
    const oldest = coverCache.keys().next().value
    if (oldest === undefined) break
    coverCache.delete(oldest)
    coverEvictions++
  }
}

export function getCoverCacheStats(): CoverCacheStats {
  return {
    size: coverCache.size,
    max: COVER_CACHE_MAX,
    hits: coverHits,
    misses: coverMisses,
    evictions: coverEvictions,
    inflight: coverInflight.size,
  }
}

export function clearCoverCache(): void {
  coverCache.clear()
  coverInflight.clear()
  coverHits = 0
  coverMisses = 0
  coverEvictions = 0
}

async function getCoverImageCached(
  bookId: number,
  original: (id: number) => Promise<string | null>,
): Promise<string | null> {
  const cached = coverCache.get(bookId)
  if (cached !== undefined) {
    coverHits++
    touchCover(bookId, cached)
    return cached
  }
  const pending = coverInflight.get(bookId)
  if (pending) return pending
  coverMisses++
  const request = original(bookId)
    .then((value) => {
      if (typeof value === 'string' && value.length > 0) touchCover(bookId, value)
      return (value ?? null) as string | null
    })
    .catch(() => null)
    .finally(() => { coverInflight.delete(bookId) })
  coverInflight.set(bookId, request)
  return request
}

const bridgeApi = window.electronAPI as unknown as {
  getCoverImage?: (id: number) => Promise<string | null>
  __pbCoverCachePatched?: boolean
}
if (bridgeApi && typeof bridgeApi.getCoverImage === 'function' && !bridgeApi.__pbCoverCachePatched) {
  const original = bridgeApi.getCoverImage.bind(bridgeApi)
  bridgeApi.getCoverImage = (bookId: number) => getCoverImageCached(bookId, original)
  bridgeApi.__pbCoverCachePatched = true
}
// 基准脚本 / 调试用：window.__pbCoverCacheStats() 读取命中/淘汰统计。
;(window as unknown as Record<string, unknown>).__pbCoverCacheStats = getCoverCacheStats
;(window as unknown as Record<string, unknown>).__pbCoverCacheClear = clearCoverCache

// Book 的唯一定义在桥接契约的 PbBook（由 bridge/contract.mjs 生成到
// electron.d.ts，形状与 C++ 侧实际拼装对齐）。此处只做别名，避免第二份手工副本。
// 注意 C++ 在字段为空时写 null 而非省略，故作者等字段是 string | null。
// （原先此处声明 publish_date?: string，但 C++ 从不设置该字段、全项目也无人读取，
//   已随别名化移除。）
export type Book = PbBook

export interface Bookshelf {
  id: number
  name: string
  created_at: string
}

export interface LibraryState {
  books: Book[]
  allBooks: Book[]
  isLoading: boolean
  viewMode: 'grid' | 'list'
  searchQuery: string
  sortBy: 'title' | 'author' | 'added_at' | 'last_opened' | 'rating' | 'series'
  // PB-005 元数据筛选（空串 = 不筛）。minRating 0 = 不限，1..5 = 至少 N 星，
  // -1 = 只看未评分（0 星）。
  filterTag: string
  filterSeries: string
  minRating: number
  // 去重后的标签 / 系列（由 C++ library:getTags / getSeries 提供，供筛选下拉框）
  allTags: string[]
  allSeries: string[]

  // Bookshelves
  bookshelves: Bookshelf[]
  activeShelfId: number | null
  shelfBookIds: number[]

  // Reading time
  readingTimeMap: Record<number, number>
  readingProgressMap: Record<number, { progress: number; page?: number; updated_at: string }>

  loadBooks: () => Promise<void>
  importBook: (filePath: string) => Promise<Book | null>
  importBooks: (filePaths: string[]) => Promise<Book[]>
  deleteBook: (id: number) => Promise<void>
  setViewMode: (mode: 'grid' | 'list') => void
  /** 应用设置页的默认视图/排序（见实现处的说明）。 */
  applyDisplayDefaults: () => void
  setSearchQuery: (query: string) => void
  setSortBy: (sort: 'title' | 'author' | 'added_at' | 'last_opened' | 'rating' | 'series') => void
  setFilterTag: (tag: string) => void
  setFilterSeries: (series: string) => void
  setMinRating: (rating: number) => void
  /** 重新拉取标签 / 系列去重列表（元数据变更后调用）。 */
  loadMetadataFacets: () => Promise<void>
  /** 保存书籍元数据；成功返回更新后的行（同时更新 books/allBooks 本地缓存）。 */
  updateBookMetadata: (id: number, patch: Partial<PbBookMetadataPatch>) => Promise<Book | null>

  // Bookshelf actions
  loadBookshelves: () => Promise<void>
  createBookshelf: (name: string) => Promise<void>
  deleteBookshelf: (id: number) => Promise<void>
  renameBookshelf: (id: number, name: string) => Promise<void>
  setActiveShelf: (id: number | null) => Promise<void>
  addBookToShelf: (shelfId: number, bookId: number) => Promise<void>
  removeBookFromShelf: (shelfId: number, bookId: number) => Promise<void>

  // Reading time actions
  loadReadingTime: () => Promise<void>
  loadReadingProgress: () => Promise<void>
}

export const useLibraryStore = create<LibraryState>((set, get) => ({
  books: [],
  allBooks: [],
  isLoading: false,
  viewMode: 'grid',
  searchQuery: '',
  sortBy: 'last_opened',
  filterTag: '',
  filterSeries: '',
  minRating: 0,
  allTags: [],
  allSeries: [],
  // 把设置页的「默认视图 / 默认排序」应用到书架。这两个设置此前只写不读
  // （全仓只有设置页自己在用），用户在设置里选"列表"后书架永远是网格。
  // 工具栏上的切换仍是会话内的（不写回设置），符合"默认值"的语义。
  applyDisplayDefaults: () => {
    const s = useSettingsStore.getState()
    const patch: { viewMode?: 'grid' | 'list'; sortBy?: 'title' | 'author' | 'added_at' | 'last_opened' } = {}
    if (s.defaultViewMode) patch.viewMode = s.defaultViewMode
    if (s.defaultSortBy) patch.sortBy = s.defaultSortBy
    if (Object.keys(patch).length) set(patch)
  },

  bookshelves: [],
  activeShelfId: null,
  shelfBookIds: [],

  readingTimeMap: {},
  readingProgressMap: {},

  loadBooks: async () => {
    set({ isLoading: true })
    try {
      const { activeShelfId } = get()
      const allBooks = (await window.electronAPI.getBooks()) as Book[]
      let books: Book[]
      if (activeShelfId !== null) {
        const bookIds = await window.electronAPI.getBooksInShelf(activeShelfId)
        books = allBooks.filter((b) => bookIds.includes(b.id))
      } else {
        books = allBooks
      }
      set({ books, allBooks })
      // 标签 / 系列下拉框的数据源（新导入的书没有标签，但删除的书可能带走旧标签）
      void get().loadMetadataFacets()
    } catch (e) {
      console.error('Failed to load books:', e)
    } finally {
      set({ isLoading: false })
    }
  },

  importBook: async (filePath: string) => {
    try {
      const books = await window.electronAPI.importBooks([filePath])
      if (books.length > 0) {
        await get().loadBooks()
        return books[0] as Book
      }
    } catch (e) {
      console.error('Failed to import book:', e)
    }
    return null
  },

  importBooks: async (filePaths: string[]) => {
    try {
      const imported = await window.electronAPI.importBooks(filePaths) as Book[]
      await get().loadBooks()
      // Also add to active shelf if viewing one
      const { activeShelfId, addBookToShelf } = get()
      if (activeShelfId !== null) {
        for (const book of imported) {
          await addBookToShelf(activeShelfId, book.id)
        }
      }
      return imported
    } catch (e) {
      console.error('Failed to import books:', e)
      return []
    }
  },

  deleteBook: async (id: number) => {
    try {
      await window.electronAPI.deleteBook(id)
      set((s) => ({ books: s.books.filter((b) => b.id !== id), allBooks: s.allBooks.filter((b) => b.id !== id) }))
    } catch (e) {
      console.error('Failed to delete book:', e)
    }
  },

  setViewMode: (viewMode) => set({ viewMode }),
  setSearchQuery: (searchQuery) => set({ searchQuery }),
  setSortBy: (sortBy) => set({ sortBy }),
  setFilterTag: (filterTag) => set({ filterTag }),
  setFilterSeries: (filterSeries) => set({ filterSeries }),
  setMinRating: (minRating) => set({ minRating }),

  loadMetadataFacets: async () => {
    try {
      const [allTags, allSeries] = await Promise.all([
        window.electronAPI.getLibraryTags(),
        window.electronAPI.getLibrarySeries(),
      ])
      set({ allTags: allTags as string[], allSeries: allSeries as string[] })
    } catch (e) {
      console.error('Failed to load metadata facets:', e)
    }
  },

  updateBookMetadata: async (id, patch) => {
    try {
      const updated = (await window.electronAPI.updateBookMetadata(id, patch)) as Book | null
      if (!updated) return null
      // 原地替换：BookCard 是 memo 的，book 对象变化才会重渲染；不必整表重拉（避免封面重载）。
      set((s) => ({
        books: s.books.map((b) => (b.id === id ? updated : b)),
        allBooks: s.allBooks.map((b) => (b.id === id ? updated : b)),
      }))
      void get().loadMetadataFacets()
      return updated
    } catch (e) {
      console.error('Failed to update book metadata:', e)
      return null
    }
  },

  // Bookshelf actions
  loadBookshelves: async () => {
    try {
      const bookshelves = await window.electronAPI.getBookshelves()
      set({ bookshelves: bookshelves as Bookshelf[] })
    } catch (e) {
      console.error('Failed to load bookshelves:', e)
    }
  },

  createBookshelf: async (name: string) => {
    try {
      await window.electronAPI.addBookshelf(name)
      await get().loadBookshelves()
    } catch (e) {
      console.error('Failed to create bookshelf:', e)
    }
  },

  deleteBookshelf: async (id: number) => {
    try {
      await window.electronAPI.deleteBookshelf(id)
      const { activeShelfId } = get()
      if (activeShelfId === id) {
        set({ activeShelfId: null })
        await get().loadBooks()
      }
      await get().loadBookshelves()
    } catch (e) {
      console.error('Failed to delete bookshelf:', e)
    }
  },

  renameBookshelf: async (id: number, name: string) => {
    try {
      await window.electronAPI.renameBookshelf(id, name)
      await get().loadBookshelves()
    } catch (e) {
      console.error('Failed to rename bookshelf:', e)
    }
  },

  setActiveShelf: async (id: number | null) => {
    set({ activeShelfId: id })
    await get().loadBooks()
    if (id !== null) {
      const { books } = get()
      set({ shelfBookIds: books.map((b) => b.id) })
    } else {
      set({ shelfBookIds: [] })
    }
  },

  addBookToShelf: async (shelfId: number, bookId: number) => {
    try {
      await window.electronAPI.addBookToShelf(shelfId, bookId)
      const { activeShelfId } = get()
      if (activeShelfId === shelfId) {
        await get().loadBooks()
        // 必须同步 shelfBookIds（removeBookFromShelf 一直有这一步，这里漏了）：
        // "从全部添加"选择器用 shelfBookIds 判断 alreadyIn，不更新就会把刚加进来的书
        // 仍显示为未加入、可再次勾选。
        set({ shelfBookIds: get().books.map((b) => b.id) })
      }
    } catch (e) {
      console.error('Failed to add book to shelf:', e)
    }
  },

  removeBookFromShelf: async (shelfId: number, bookId: number) => {
    try {
      await window.electronAPI.removeBookFromShelf(shelfId, bookId)
      const { activeShelfId } = get()
      if (activeShelfId === shelfId) {
        await get().loadBooks()
        set({ shelfBookIds: get().books.map((b) => b.id) })
      }
    } catch (e) {
      console.error('Failed to remove book from shelf:', e)
    }
  },

  loadReadingTime: async () => {
    try {
      const readingTimeMap = await window.electronAPI.getAllReadingTime()
      set({ readingTimeMap })
    } catch (e) {
      console.error('Failed to load reading time:', e)
    }
  },

  loadReadingProgress: async () => {
    try {
      const readingProgressMap = await window.electronAPI.getAllReadingProgress()
      set({ readingProgressMap })
    } catch (e) {
      console.error('Failed to load reading progress:', e)
    }
  },
}))
