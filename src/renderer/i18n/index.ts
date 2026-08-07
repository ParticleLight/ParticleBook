import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import { en } from './locales/en'

export type Lang = 'zh' | 'en'

// Detect the initial language for each page load. The C++ side injects the
// persisted language as window.__pbLang on every document (before this module
// runs), so a full WebView2 reload — e.g. closing Z-Library — restores the
// user's language instantly instead of flashing the OS-default language until
// the async loadSettings() resolves. Fall back to the system language only
// when the injected anchor is absent.
export function detectSystemLang(): Lang {
  try {
    if (typeof window !== 'undefined' && (window.__pbLang === 'en' || window.__pbLang === 'zh')) {
      return window.__pbLang
    }
    const nav = typeof navigator !== 'undefined' ? navigator.language || '' : ''
    return nav.toLowerCase().startsWith('en') ? 'en' : 'zh'
  } catch {
    return 'zh'
  }
}

i18n.use(initReactI18next).init({
  resources: {
    en: { translation: en },
  },
  lng: detectSystemLang(),
  fallbackLng: 'zh', // missing key → return the key itself (the Chinese text)
  keySeparator: false, // Chinese keys may contain '.' or ':'
  nsSeparator: false,
  returnNull: false,
  debug: false,
  interpolation: { escapeValue: false },
})

function applyHtmlLang(lng: string) {
  document.documentElement.lang = lng === 'en' ? 'en' : 'zh-CN'
}
applyHtmlLang(i18n.language)
i18n.on('languageChanged', applyHtmlLang)

export default i18n
