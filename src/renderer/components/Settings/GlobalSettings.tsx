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
      {/* M3 top app bar */}
      <header className="m3-top-app-bar drag-region shrink-0">
        <button onClick={onBack} className="no-drag m3-icon-button" aria-label={t('返回')} title={t('返回')}>
          <SvgIcon d="M15 19l-7-7 7-7" />
        </button>
        <div className="no-drag min-w-0">
          <h1 className="m3-title-large truncate">{t('设置')}</h1>
          <p className="m3-label-medium truncate">{navItems.find((item) => item.key === activeNav)?.label}</p>
        </div>
      </header>

      {/* Body */}
      <div className="flex-1 flex min-h-0">
        {/* M3 navigation drawer */}
        <nav className="m3-nav-drawer w-60 shrink-0 p-3 overflow-y-auto" aria-label={t('设置导航')}>
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
        <main className="flex-1 overflow-y-auto px-6 py-6 lg:px-10 lg:py-8">
          {/* 内容列铺满可用宽度：此前是 max-w-2xl，窗口一宽右侧就空一大片
              （用户截图："全屏下设置界面全挤到左侧"）。按用户要求改为用满，
              不做居中留白。 */}
          <div className="w-full max-w-[1200px] mx-auto">
            {renderPage()}
          </div>
        </main>
      </div>
    </div>
  )
}
