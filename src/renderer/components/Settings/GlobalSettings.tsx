import { useState, useEffect, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { useSettingsStore } from '../../stores/settingsStore'
import {
  NAV_ITEMS,
  SvgIcon,
  AppearancePage,
  ReadingPage,
  LibraryPage,
  ZLibraryPage,
  AboutPage,
  AdvancedSettingsPage,
  BookSourcesPage,
  type GlobalSettingsProps,
  type NavKey,
} from './GlobalSettingsPages'

export function GlobalSettings({ onBack }: GlobalSettingsProps) {
  const [activeNav, setActiveNav] = useState<NavKey>('appearance')
  const { t } = useTranslation()
  const navItems = useMemo(() => NAV_ITEMS(t), [t])

  // Apply accent color to document
  const accentColor = useSettingsStore((s) => s.accentColor)
  useEffect(() => {
    const root = document.documentElement
    if (accentColor === 'blue') {
      root.removeAttribute('data-accent')
    } else {
      root.setAttribute('data-accent', accentColor)
    }
  }, [accentColor])

  const renderPage = () => {
    switch (activeNav) {
      case 'appearance': return <AppearancePage />
      case 'reading': return <ReadingPage />
      case 'library': return <LibraryPage />
      case 'zlibrary': return <ZLibraryPage />
      case 'about': return <AboutPage />
      case 'advanced': return <AdvancedSettingsPage />
      case 'booksources': return <BookSourcesPage />
    }
  }

  return (
    <div className="h-screen flex flex-col" style={{ background: 'var(--bg)' }}>
      {/* Header */}
      <header className="drag-region flex items-center gap-4 px-6 py-3.5 shrink-0" style={{ borderBottom: '1px solid var(--border)' }}>
        <button onClick={onBack} className="no-drag p-1.5 rounded-lg transition-colors hover:bg-[var(--surface-hover)]" style={{ color: 'var(--text-secondary)' }}>
          <SvgIcon d="M15 19l-7-7 7-7" />
        </button>
        <h1 className="no-drag text-base font-semibold" style={{ color: 'var(--text-primary)' }}>{t('设置')}</h1>
      </header>

      {/* Body */}
      <div className="flex-1 flex min-h-0">
        {/* Left nav */}
        <nav className="w-56 shrink-0 p-3 overflow-y-auto" style={{ borderRight: '1px solid var(--border)' }}>
          <div className="space-y-0.5">
            {navItems.map((item) => (
              <button
                key={item.key}
                onClick={() => setActiveNav(item.key)}
                className={`settings-nav-item ${activeNav === item.key ? 'active' : ''}`}
                style={{ position: 'relative' }}
              >
                <SvgIcon d={item.icon} className="w-[18px] h-[18px] shrink-0" />
                {item.label}
              </button>
            ))}
          </div>
        </nav>

        {/* Right content */}
        <main className="flex-1 overflow-y-auto p-8">
          {/* 内容列铺满可用宽度：此前是 max-w-2xl，窗口一宽右侧就空一大片
              （用户截图："全屏下设置界面全挤到左侧"）。按用户要求改为用满，
              不做居中留白。 */}
          <div className="w-full">
            {renderPage()}
          </div>
        </main>
      </div>
    </div>
  )
}
