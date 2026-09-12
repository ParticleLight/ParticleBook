import { create } from 'zustand'
import i18n, { type Lang } from '../i18n'

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
  autoSaveProgress: boolean
  showReadingTime: boolean

  // 书架设置
  defaultViewMode: 'grid' | 'list'
  defaultSortBy: 'title' | 'author' | 'added_at' | 'last_opened'

  // 语言（全局，不进 SETTINGS_KEYS —— 语言不能被写进书级设置或被覆盖）
  language: Lang

  activeBookId: number | null

  setTheme: (theme: 'light' | 'dark' | 'sepia') => void
  setAccentColor: (color: AccentColor) => void
  setFontSize: (size: number) => void
  setFontFamily: (family: string) => void
  setLineHeight: (height: number) => void
  setMargin: (margin: number) => void
  setTextAlign: (align: 'left' | 'justify') => void
  setAutoSaveProgress: (v: boolean) => void
  setShowReadingTime: (v: boolean) => void
  setDefaultViewMode: (mode: 'grid' | 'list') => void
  setDefaultSortBy: (sort: 'title' | 'author' | 'added_at' | 'last_opened') => void
  setLanguage: (lang: Lang) => void
  saveSettings: () => void
  loadSettings: () => Promise<void>
  loadBookSettings: (bookId: number) => Promise<void>
  clearBookSettings: () => void
}

const SETTINGS_KEYS = [
  'theme', 'accentColor', 'fontSize', 'fontFamily', 'lineHeight', 'margin', 'textAlign',
  'autoSaveProgress', 'showReadingTime',
  'defaultViewMode', 'defaultSortBy',
] as const

// 只有【阅读排版】类设置才按书保存（与读者设置面板暴露的项一致）。其余都是全局偏好：
// 此前 saveSettings 把 11 个 key 一股脑写进 book_settings[bookId]，于是用户回到书架把
// "自动保存进度/显示阅读时间"关掉后，再打开那本书又被书级旧值覆盖回 true —— 全局页
// 看起来没生效，而书内面板根本没有这两个开关，用户无法就地纠正。
const BOOK_SETTINGS_KEYS = ['fontSize', 'fontFamily', 'lineHeight', 'margin', 'textAlign'] as const

export const useSettingsStore = create<SettingsState>((set, get) => ({
  theme: 'dark',
  accentColor: 'blue',
  fontSize: 18,
  fontFamily: 'Georgia, Noto Serif SC, serif',
  lineHeight: 1.8,
  margin: 40,
  textAlign: 'justify',

  autoSaveProgress: true,
  showReadingTime: true,

  defaultViewMode: 'grid',
  defaultSortBy: 'last_opened',

  language: i18n.language === 'en' ? 'en' : 'zh',

  activeBookId: null,

  setTheme: (theme) => { set({ theme }); get().saveSettings() },
  setAccentColor: (accentColor) => { set({ accentColor }); get().saveSettings() },
  setFontSize: (fontSize) => { set({ fontSize }); get().saveSettings() },
  setFontFamily: (fontFamily) => { set({ fontFamily }); get().saveSettings() },
  setLineHeight: (lineHeight) => { set({ lineHeight }); get().saveSettings() },
  setMargin: (margin) => { set({ margin }); get().saveSettings() },
  setTextAlign: (textAlign) => { set({ textAlign }); get().saveSettings() },
  setAutoSaveProgress: (autoSaveProgress) => { set({ autoSaveProgress }); get().saveSettings() },
  setShowReadingTime: (showReadingTime) => { set({ showReadingTime }); get().saveSettings() },
  setDefaultViewMode: (defaultViewMode) => { set({ defaultViewMode }); get().saveSettings() },
  setDefaultSortBy: (defaultSortBy) => { set({ defaultSortBy }); get().saveSettings() },

  setLanguage: (language) => {
    i18n.changeLanguage(language)
    set({ language })
    // 语言是全局设置：独立写库（不进 SETTINGS_KEYS，避免污染书级设置）+ 通知 C++
    window.electronAPI.updateSettings({ language }).catch((e) => {
      console.error('Failed to save language:', e)
    })
    window.electronAPI.setLanguage(language).catch((e) => {
      console.error('Failed to notify C++ language:', e)
    })
  },

  saveSettings: () => {
    const { activeBookId } = get()
    const settings: Record<string, any> = {}
    for (const key of SETTINGS_KEYS) {
      settings[key] = (get() as any)[key]
    }
    // 全局行始终写：阅读中改主题/主题色等也应成为全局偏好（此前这些只落到书级行，
    // 于是关掉书后主题又回退）。
    window.electronAPI.updateSettings(settings).catch((e) => {
      console.error('Failed to save settings:', e)
    })
    if (activeBookId !== null) {
      const bookSettings: Record<string, any> = {}
      for (const key of BOOK_SETTINGS_KEYS) bookSettings[key] = settings[key]
      window.electronAPI.updateBookSettings(activeBookId, bookSettings).catch((e) => {
        console.error('Failed to save book settings:', e)
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
      // 语言是全局设置，单独恢复（不在 SETTINGS_KEYS 里）
      if (settings.language === 'zh' || settings.language === 'en') {
        patch.language = settings.language
        if (i18n.language !== settings.language) i18n.changeLanguage(settings.language)
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
        // 只覆盖排版键：书级行里的历史遗留全局项（修复前写入的）一律忽略，
        // 否则旧数据仍会把全局开关"顶"回去。
        for (const key of BOOK_SETTINGS_KEYS) {
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
