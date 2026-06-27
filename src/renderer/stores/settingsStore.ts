import { create } from 'zustand'

export type AccentColor = 'blue' | 'purple' | 'green' | 'orange'

export interface SettingsState {
  theme: 'light' | 'dark' | 'sepia'
  accentColor: AccentColor
  fontSize: number
  fontFamily: string
  lineHeight: number
  margin: number
  textAlign: 'left' | 'justify'

  // 阅读设置
  pageTurnMode: 'click' | 'scroll'
  autoSaveProgress: boolean
  showReadingTime: boolean

  // 书架设置
  defaultViewMode: 'grid' | 'list'
  defaultSortBy: 'title' | 'author' | 'added_at' | 'last_opened'

  activeBookId: number | null

  setTheme: (theme: 'light' | 'dark' | 'sepia') => void
  setAccentColor: (color: AccentColor) => void
  setFontSize: (size: number) => void
  setFontFamily: (family: string) => void
  setLineHeight: (height: number) => void
  setMargin: (margin: number) => void
  setTextAlign: (align: 'left' | 'justify') => void
  setPageTurnMode: (mode: 'click' | 'scroll') => void
  setAutoSaveProgress: (v: boolean) => void
  setShowReadingTime: (v: boolean) => void
  setDefaultViewMode: (mode: 'grid' | 'list') => void
  setDefaultSortBy: (sort: 'title' | 'author' | 'added_at' | 'last_opened') => void
  saveSettings: () => void
  loadSettings: () => Promise<void>
  loadBookSettings: (bookId: number) => Promise<void>
  clearBookSettings: () => void
}

const SETTINGS_KEYS = [
  'theme', 'accentColor', 'fontSize', 'fontFamily', 'lineHeight', 'margin', 'textAlign',
  'pageTurnMode', 'autoSaveProgress', 'showReadingTime',
  'defaultViewMode', 'defaultSortBy',
] as const

export const useSettingsStore = create<SettingsState>((set, get) => ({
  theme: 'dark',
  accentColor: 'blue',
  fontSize: 18,
  fontFamily: 'Georgia, Noto Serif SC, serif',
  lineHeight: 1.8,
  margin: 40,
  textAlign: 'justify',

  pageTurnMode: 'click',
  autoSaveProgress: true,
  showReadingTime: true,

  defaultViewMode: 'grid',
  defaultSortBy: 'last_opened',

  activeBookId: null,

  setTheme: (theme) => { set({ theme }); get().saveSettings() },
  setAccentColor: (accentColor) => { set({ accentColor }); get().saveSettings() },
  setFontSize: (fontSize) => { set({ fontSize }); get().saveSettings() },
  setFontFamily: (fontFamily) => { set({ fontFamily }); get().saveSettings() },
  setLineHeight: (lineHeight) => { set({ lineHeight }); get().saveSettings() },
  setMargin: (margin) => { set({ margin }); get().saveSettings() },
  setTextAlign: (textAlign) => { set({ textAlign }); get().saveSettings() },
  setPageTurnMode: (pageTurnMode) => { set({ pageTurnMode }); get().saveSettings() },
  setAutoSaveProgress: (autoSaveProgress) => { set({ autoSaveProgress }); get().saveSettings() },
  setShowReadingTime: (showReadingTime) => { set({ showReadingTime }); get().saveSettings() },
  setDefaultViewMode: (defaultViewMode) => { set({ defaultViewMode }); get().saveSettings() },
  setDefaultSortBy: (defaultSortBy) => { set({ defaultSortBy }); get().saveSettings() },

  saveSettings: () => {
    const { activeBookId } = get()
    const settings: Record<string, any> = {}
    for (const key of SETTINGS_KEYS) {
      settings[key] = (get() as any)[key]
    }
    if (activeBookId !== null) {
      window.electronAPI.updateBookSettings(activeBookId, settings).catch((e) => {
        console.error('Failed to save book settings:', e)
      })
    } else {
      window.electronAPI.updateSettings(settings).catch((e) => {
        console.error('Failed to save settings:', e)
      })
    }
  },

  loadSettings: async () => {
    try {
      const settings = await window.electronAPI.getSettings()
      const patch: Record<string, any> = {}
      for (const key of SETTINGS_KEYS) {
        if (settings[key] !== undefined) patch[key] = settings[key]
      }
      if (Object.keys(patch).length > 0) set(patch)
    } catch (e) {
      console.error('Failed to load settings:', e)
    }
  },

  loadBookSettings: async (bookId: number) => {
    try {
      const globalSettings = await window.electronAPI.getSettings()
      const baseSettings: Record<string, any> = {}
      for (const key of SETTINGS_KEYS) {
        if (globalSettings[key] !== undefined) baseSettings[key] = globalSettings[key]
      }

      const bookSettings = await window.electronAPI.getBookSettings(bookId)
      if (bookSettings) {
        for (const key of SETTINGS_KEYS) {
          if (bookSettings[key] !== undefined) baseSettings[key] = bookSettings[key]
        }
      }

      set({
        ...baseSettings,
        activeBookId: bookId,
      })
    } catch (e) {
      console.error('Failed to load book settings:', e)
      set({ activeBookId: bookId })
    }
  },

  clearBookSettings: () => {
    set({ activeBookId: null })
    get().loadSettings()
  },
}))
