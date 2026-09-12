import { create } from 'zustand'
import { useSettingsStore } from './settingsStore'

let saveTimer: ReturnType<typeof setTimeout> | null = null

// Progress writes honor the "auto-save progress" setting — when the user turns
// it off, neither the debounced save nor the close-time flush writes progress.
function autoSaveEnabled(): boolean {
  return useSettingsStore.getState().autoSaveProgress
}

export interface Bookmark {
  id: number
  book_id: number
  cfi?: string
  page?: number
  progress?: number
  title?: string
  note?: string
  created_at: string
}

export interface Highlight {
  id: number
  book_id: number
  cfi?: string
  page?: number
  text: string
  color: string
  note?: string
  created_at: string
}

export interface Note {
  id: number
  book_id: number
  highlight_id?: number
  cfi?: string
  page?: number
  content: string
  created_at: string
  updated_at: string
}

export interface ReadingProgress {
  progress: number
  cfi?: string
  page?: number
  scrollPosition?: number
}

export interface ReaderState {
  bookId: number | null
  progress: ReadingProgress
  bookmarks: Bookmark[]
  highlights: Highlight[]
  notes: Note[]
  sidebarTab: 'toc' | 'bookmarks' | 'highlights' | 'notes'
  showSidebar: boolean
  showControls: boolean
  tableOfContents: any[]

  // Reading time tracking
  readingSessionId: number | null
  readingStartTime: number | null
  currentReadingTime: number

  setBookId: (id: number | null) => void
  setProgress: (progress: ReadingProgress) => void
  saveProgress: () => void
  flushProgress: (overrideBookId?: number) => Promise<void>
  loadProgress: (bookId: number) => Promise<void>
  startReadingSession: () => Promise<void>
  endReadingSession: () => Promise<void>
  updateReadingTime: () => void

  loadBookmarks: (bookId: number) => Promise<void>
  addBookmark: (bookmark: Omit<Bookmark, 'id' | 'created_at'>) => Promise<void>
  removeBookmark: (id: number) => Promise<void>
  updateBookmark: (id: number, title: string) => Promise<void>

  loadHighlights: (bookId: number) => Promise<void>
  addHighlight: (highlight: Omit<Highlight, 'id' | 'created_at'>) => Promise<void>
  removeHighlight: (id: number) => Promise<void>

  loadNotes: (bookId: number) => Promise<void>
  addNote: (note: Omit<Note, 'id' | 'created_at' | 'updated_at'>) => Promise<void>
  updateNote: (id: number, content: string) => Promise<void>
  removeNote: (id: number) => Promise<void>

  controlsLocked: boolean
  setControlsLocked: (locked: boolean) => void

  navigateTarget: { page?: number; cfi?: string } | null
  navigateTo: (target: { page?: number; cfi?: string }) => void
  clearNavigateTarget: () => void

  turnPageDelta: number | null
  turnPage: (delta: number) => void
  clearTurnPage: () => void

  seekTarget: number | null
  seekTo: (percent: number) => void
  clearSeekTarget: () => void

  // Full-text search
  showSearch: boolean
  searchQuery: string
  searchMatches: number[]
  currentSearchIndex: number
  setShowSearch: (show: boolean) => void
  setSearchQuery: (query: string) => void
  setSearchMatches: (matches: number[]) => void
  nextSearchMatch: () => void
  prevSearchMatch: () => void
  clearSearch: () => void

  setSidebarTab: (tab: 'toc' | 'bookmarks' | 'highlights' | 'notes') => void
  toggleSidebar: () => void
  setShowSidebar: (show: boolean) => void
  setShowControls: (show: boolean) => void
  setTableOfContents: (toc: any[]) => void
}

export const useReaderStore = create<ReaderState>((set, get) => ({
  bookId: null,
  progress: { progress: 0 },
  bookmarks: [],
  highlights: [],
  notes: [],
  sidebarTab: 'toc',
  showSidebar: false,
  showControls: true,
  controlsLocked: false,
  navigateTarget: null,
  turnPageDelta: null,
  seekTarget: null,
  showSearch: false,
  searchQuery: '',
  searchMatches: [],
  currentSearchIndex: -1,
  tableOfContents: [],

  // Reading time tracking
  readingSessionId: null,
  readingStartTime: null,
  currentReadingTime: 0,

  setBookId: (bookId) => set({ bookId, tableOfContents: [] }),

  setProgress: (progress) => set({ progress }),

  saveProgress: () => {
    const { bookId } = get()
    if (bookId === null) return
    if (!autoSaveEnabled()) return
    if (saveTimer) clearTimeout(saveTimer)
    saveTimer = setTimeout(async () => {
      saveTimer = null
      try {
        const { bookId, progress } = get()
        if (bookId !== null) {
          await window.electronAPI.updateReadingProgress(bookId, progress)
        }
      } catch (e) {
        console.error('Failed to save progress:', e)
      }
    }, 500)
  },

  flushProgress: async (overrideBookId?: number) => {
    if (saveTimer) {
      clearTimeout(saveTimer)
      saveTimer = null
    }
    if (!autoSaveEnabled()) return
    const state = get()
    const bookId = overrideBookId ?? state.bookId
    if (bookId === null) return
    try {
      await window.electronAPI.updateReadingProgress(bookId, state.progress)
    } catch (e) {
      console.error('Failed to save progress:', e)
    }
  },

  loadProgress: async (bookId: number) => {
    try {
      // db:getProgress returns an EMPTY OBJECT (not null) when there is no
      // record, so every field is optional: fall back to 0 rather than storing
      // undefined into a field typed as number (which broke the progress bar).
      const record = await window.electronAPI.getReadingProgress(bookId)
      set({
        progress: {
          progress: typeof record?.progress === 'number' ? record.progress : 0,
          cfi: record?.cfi,
          page: record?.page,
          scrollPosition: record?.scroll_position
        }
      })
    } catch (e) {
      console.error('Failed to load progress:', e)
    }
  },

  loadBookmarks: async (bookId: number) => {
    try {
      const bookmarks = await window.electronAPI.getBookmarks(bookId)
      set({ bookmarks: bookmarks as Bookmark[] })
    } catch (e) {
      console.error('Failed to load bookmarks:', e)
    }
  },

  addBookmark: async (bookmark) => {
    try {
      await window.electronAPI.addBookmark(bookmark)
      await get().loadBookmarks(bookmark.book_id)
    } catch (e) {
      console.error('Failed to add bookmark:', e)
    }
  },

  removeBookmark: async (id: number) => {
    try {
      await window.electronAPI.deleteBookmark(id)
      set({ bookmarks: get().bookmarks.filter((b) => b.id !== id) })
    } catch (e) {
      console.error('Failed to remove bookmark:', e)
    }
  },

  updateBookmark: async (id: number, title: string) => {
    try {
      await window.electronAPI.updateBookmarkTitle(id, title)
      set({
        bookmarks: get().bookmarks.map((b) =>
          b.id === id ? { ...b, title } : b
        ),
      })
    } catch (e) {
      console.error('Failed to update bookmark:', e)
    }
  },

  loadHighlights: async (bookId: number) => {
    try {
      const highlights = await window.electronAPI.getHighlights(bookId)
      set({ highlights: highlights as Highlight[] })
    } catch (e) {
      console.error('Failed to load highlights:', e)
    }
  },

  addHighlight: async (highlight) => {
    try {
      await window.electronAPI.addHighlight(highlight)
      await get().loadHighlights(highlight.book_id)
    } catch (e) {
      console.error('Failed to add highlight:', e)
    }
  },

  removeHighlight: async (id: number) => {
    try {
      await window.electronAPI.deleteHighlight(id)
      set({ highlights: get().highlights.filter((h) => h.id !== id) })
    } catch (e) {
      console.error('Failed to remove highlight:', e)
    }
  },

  loadNotes: async (bookId: number) => {
    try {
      const notes = await window.electronAPI.getNotes(bookId)
      set({ notes: notes as Note[] })
    } catch (e) {
      console.error('Failed to load notes:', e)
    }
  },

  addNote: async (note) => {
    try {
      await window.electronAPI.addNote(note)
      await get().loadNotes(note.book_id)
    } catch (e) {
      console.error('Failed to add note:', e)
    }
  },

  updateNote: async (id: number, content: string) => {
    try {
      await window.electronAPI.updateNote(id, content)
      set({
        notes: get().notes.map((n) =>
          n.id === id ? { ...n, content, updated_at: new Date().toISOString() } : n
        ),
      })
    } catch (e) {
      console.error('Failed to update note:', e)
    }
  },

  removeNote: async (id: number) => {
    try {
      await window.electronAPI.deleteNote(id)
      set({ notes: get().notes.filter((n) => n.id !== id) })
    } catch (e) {
      console.error('Failed to remove note:', e)
    }
  },

  startReadingSession: async () => {
    const { bookId } = get()
    if (bookId === null) return
    try {
      const sessionId = await window.electronAPI.startReadingSession(bookId)
      const now = Date.now()
      set({
        readingSessionId: sessionId,
        readingStartTime: now,
        currentReadingTime: 0,
      })
    } catch (e) {
      console.error('Failed to start reading session:', e)
    }
  },

  endReadingSession: async () => {
    const { readingSessionId, readingStartTime } = get()
    if (readingSessionId === null) return
    try {
      // Update final duration before ending
      if (readingStartTime) {
        const duration = Math.floor((Date.now() - readingStartTime) / 1000)
        await window.electronAPI.updateReadingSessionDuration(readingSessionId, duration)
      }
      await window.electronAPI.endReadingSession(readingSessionId)
      set({
        readingSessionId: null,
        readingStartTime: null,
        currentReadingTime: 0,
      })
    } catch (e) {
      console.error('Failed to end reading session:', e)
    }
  },

  updateReadingTime: () => {
    const { readingStartTime, readingSessionId } = get()
    if (readingStartTime === null || readingSessionId === null) return
    const elapsed = Math.floor((Date.now() - readingStartTime) / 1000)
    set({ currentReadingTime: elapsed })
  },

  setControlsLocked: (controlsLocked) => set({ controlsLocked }),
  navigateTo: (target) => set({ navigateTarget: target }),
  clearNavigateTarget: () => set({ navigateTarget: null }),
  turnPage: (delta) => set({ turnPageDelta: delta }),
  clearTurnPage: () => set({ turnPageDelta: null }),
  seekTo: (percent) => set({ seekTarget: percent }),
  clearSeekTarget: () => set({ seekTarget: null }),

  setShowSearch: (show) => set({ showSearch: show }),
  setSearchQuery: (query) => set({ searchQuery: query, currentSearchIndex: -1, searchMatches: [] }),
  setSearchMatches: (matches) => set({ searchMatches: matches, currentSearchIndex: matches.length > 0 ? 0 : -1 }),
  nextSearchMatch: () => {
    const { currentSearchIndex, searchMatches } = get()
    if (searchMatches.length === 0) return
    set({ currentSearchIndex: (currentSearchIndex + 1) % searchMatches.length })
  },
  prevSearchMatch: () => {
    const { currentSearchIndex, searchMatches } = get()
    if (searchMatches.length === 0) return
    set({ currentSearchIndex: currentSearchIndex <= 0 ? searchMatches.length - 1 : currentSearchIndex - 1 })
  },
  clearSearch: () => set({ showSearch: false, searchQuery: '', searchMatches: [], currentSearchIndex: -1 }),
  setSidebarTab: (sidebarTab) => set({ sidebarTab, showSidebar: true }),
  toggleSidebar: () => set({ showSidebar: !get().showSidebar }),
  setShowSidebar: (show) => set({ showSidebar: show }),
  setShowControls: (showControls) => set({ showControls }),
  setTableOfContents: (tableOfContents) => set({ tableOfContents }),
}))
