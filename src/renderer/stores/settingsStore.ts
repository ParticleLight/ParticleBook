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

  // TTS（PB-001-B）：全局偏好，不按书保存
  //   ttsVoiceId = SAPI token id（registry 路径，跨进程稳定，见 PB-001-A SapiTts.h）
  //   ttsRate    = -10..10（0 = 正常语速）
  ttsVoiceId: string
  ttsRate: number

  // EPUB 版式（PB-009-A）：
  //   epubSpread = 分栏模式：auto（宽屏自动双栏）/ single（单栏）/ double（强制双栏）
  //   verticalText = 竖排（writing-mode: vertical-rl），仅 EPUB 生效
  // 这两个键**不走 updateSettings**（C++ schema 还没登记，本轮按任务约定用
  // localStorage 持久化），所以也不进 SETTINGS_KEYS / BOOK_SETTINGS_KEYS。
  epubSpread: 'auto' | 'single' | 'double'
  verticalText: boolean

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
  setTtsVoiceId: (id: string) => void
  setTtsRate: (rate: number) => void
  setEpubSpread: (spread: 'auto' | 'single' | 'double') => void
  /** 渲染器实测出来的「当前看起来是几栏」（epub.js layout.divisor）：
      工具栏切换按它判断，避免 auto 在宽窗口下"点一下没反应"。 */
  epubSpreadEffective: 'single' | 'double'
  setEpubSpreadEffective: (spread: 'single' | 'double') => void
  cycleEpubSpread: () => void
  setVerticalText: (on: boolean) => void
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
  // TTS 偏好：全局。C++ 的 SettingsService 采用 Preserve 策略，本版本 schema
  // 还没登记的键会原样保留（见 platform/SettingsService.h），所以不需要先改 C++；
  // 后续 PB-023 补 schema 时把它们登记成 tts.voiceId / tts.rate 即可。
  'ttsVoiceId', 'ttsRate',
] as const

// 只有【阅读排版】类设置才按书保存（与读者设置面板暴露的项一致）。其余都是全局偏好：
// 此前 saveSettings 把 11 个 key 一股脑写进 book_settings[bookId]，于是用户回到书架把
// "自动保存进度/显示阅读时间"关掉后，再打开那本书又被书级旧值覆盖回 true —— 全局页
// 看起来没生效，而书内面板根本没有这两个开关，用户无法就地纠正。
const BOOK_SETTINGS_KEYS = ['fontSize', 'fontFamily', 'lineHeight', 'margin', 'textAlign'] as const

// ── PB-009-A：EPUB 版式的本地持久化（不写 C++ settings）──────────────
const LS_EPUB_SPREAD = 'pb.reader.epubSpread'
const LS_VERTICAL_TEXT = 'pb.reader.verticalText'

function readEpubSpread(): 'auto' | 'single' | 'double' {
  try {
    const raw = window.localStorage.getItem(LS_EPUB_SPREAD)
    if (raw === 'auto' || raw === 'single' || raw === 'double') return raw
  } catch { /* localStorage 不可用（隐私模式等） */ }
  // 默认沿用历史行为：单栏（用户可在工具栏切到自动/双栏）
  return 'single'
}

function readVerticalText(): boolean {
  try {
    return window.localStorage.getItem(LS_VERTICAL_TEXT) === '1'
  } catch {
    return false
  }
}

function writeLocal(key: string, value: string) {
  try { window.localStorage.setItem(key, value) } catch { /* ignore */ }
}

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

  ttsVoiceId: '',
  ttsRate: 0,

  epubSpread: readEpubSpread(),
  epubSpreadEffective: 'single',
  verticalText: readVerticalText(),

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
  setTtsVoiceId: (ttsVoiceId) => { set({ ttsVoiceId }); get().saveSettings() },
  setTtsRate: (ttsRate) => { set({ ttsRate }); get().saveSettings() },

  // EPUB 版式：只写 localStorage（见上方说明），不触发 saveSettings
  setEpubSpread: (epubSpread) => {
    set({ epubSpread })
    writeLocal(LS_EPUB_SPREAD, epubSpread)
  },
  // 只有「单栏 / 双栏」两态（2026-10-29 用户反馈：点一下分栏、点第二下还是分栏、
  // 第三下才回归）。旧的三态 auto→single→double 里 auto 在宽窗口本来就渲染成双栏，
  // 于是 double→auto 那一次点击看不出任何变化；现在按渲染器实测的当前栏数切换。
  cycleEpubSpread: () => {
    const next = get().epubSpreadEffective === 'double' ? 'single' : 'double'
    set({ epubSpread: next })
    writeLocal(LS_EPUB_SPREAD, next)
  },
  setEpubSpreadEffective: (epubSpreadEffective) => {
    if (get().epubSpreadEffective !== epubSpreadEffective) set({ epubSpreadEffective })
  },
  setVerticalText: (verticalText) => {
    set({ verticalText })
    writeLocal(LS_VERTICAL_TEXT, verticalText ? '1' : '0')
  },

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
      // PbSettings 是生成物（bridge 契约里登记的字段）；TTS 的 ttsVoiceId/ttsRate
      // 是本版本新加的、C++ schema 还没登记的键，SettingsService 按 Preserve 策略
      // 原样保留（见 particlebook-cpp/src/platform/SettingsService.h），所以这里
      // 用宽松类型读回，等 PB-023 补 schema 后再收紧。
      const settings: Record<string, any> = await window.electronAPI.getSettings()
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
      const globalSettings: Record<string, any> = await window.electronAPI.getSettings()
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
